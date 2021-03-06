package com.lightbend.lagom.internal.broker.pubsub

import java.io.FileInputStream

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Status, SupervisorStrategy}
import akka.cluster.sharding.ClusterShardingSettings
import akka.pattern.{BackoffSupervisor, pipe}
import akka.persistence.query.Offset
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Sink, Source, Unzip, Zip}
import akka.stream.{FlowShape, KillSwitch, KillSwitches, Materializer}
import com.google.api.core.{ApiFutureCallback, ApiFutures}
import com.google.api.gax.core.{FixedCredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.pubsub.v1.Publisher
import com.google.pubsub.v1.{ProjectTopicName, PubsubMessage}
import com.lightbend.lagom.internal.persistence.cluster.ClusterDistribution.EnsureActive
import com.lightbend.lagom.internal.persistence.cluster.{ClusterDistribution, ClusterDistributionSettings}
import com.lightbend.lagom.spi.persistence.{OffsetDao, OffsetStore}
import io.grpc.ManagedChannelBuilder

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future, Promise}

private[lagom] object Producer {

  import Protocol._
  import TaggedOffsetProducerActor._

  def startTaggedOffsetProducer[Message](
                                          system: ActorSystem,
                                          tags: immutable.Seq[String],
                                          pubsubConfig: PubsubConfig,
                                          topicId: String,
                                          eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
                                          transform: Message => PubsubMessage,
                                          offsetStore: OffsetStore
                                        )(implicit mat: Materializer, ec: ExecutionContext): Unit = {

    val producerConfig: ProducerConfig = ProducerConfig(system.settings.config)
    val publisherProps = TaggedOffsetProducerActor.props(pubsubConfig, topicId, eventStreamFactory,
      transform, offsetStore)

    val backoffPublisherProps = BackoffSupervisor.propsWithSupervisorStrategy(
      publisherProps, s"producer", producerConfig.minBackoff, producerConfig.maxBackoff,
      producerConfig.randomBackoffFactor, SupervisorStrategy.stoppingStrategy
    )
    val clusterShardingSettings = ClusterShardingSettings(system).withRole(producerConfig.role)

    ClusterDistribution(system).start(
      s"pubsubProducer-$topicId",
      backoffPublisherProps,
      tags.toSet,
      ClusterDistributionSettings(system).copy(clusterShardingSettings = clusterShardingSettings)
    )
  }

  private class TaggedOffsetProducerActor[Message](pubsubConfig: PubsubConfig,
                                                   topicId: String,
                                                   eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
                                                   transform: Message => PubsubMessage,
                                                   offsetStore: OffsetStore
                                                  )(implicit mat: Materializer, ec: ExecutionContext) extends Actor with ActorLogging {
    /** Switch used to terminate the on-going Pub/Sub publishing stream when this actor fails. */
    private var shutdown: Option[KillSwitch] = None

    override def postStop(): Unit = {
      shutdown.foreach(_.shutdown())
    }

    override def receive: Receive = {
      case EnsureActive(tag) =>
        offsetStore.prepare(s"topicProducer-$topicId", tag) pipeTo self

        val topic: ProjectTopicName = ProjectTopicName.of(pubsubConfig.projectId, topicId)

        Future {
          PubsubSubscriberActor.createTopic(topic, pubsubConfig)
        }.map(_ => TopicCreated) pipeTo self

        context.become(creatingTopic(tag, topic))
    }

    def generalHandler: Receive = {
      case Status.Failure(e) => throw e
      case EnsureActive(_) =>
    }

    private def creatingTopic(tag: String, topic: ProjectTopicName,
                              topicCreated: Boolean = false, offsetDao: Option[OffsetDao] = None): Receive = {
      case TopicCreated =>
        log.debug("Topic [{}] found/created", topic.getTopic)

        if (offsetDao.isDefined) {
          run(tag, topic, offsetDao.get)
        } else {
          context.become(creatingTopic(tag, topic, topicCreated = true, offsetDao))
        }
      case od: OffsetDao =>
        log.debug("OffsetDao prepared for subscriber of [{}]", topicId)

        if (topicCreated) {
          run(tag, topic, od)
        } else {
          context.become(creatingTopic(tag, topic, topicCreated, Some(od)))
        }
    }

    private def active: Receive = generalHandler.orElse {
      case Done =>
        log.info("Pub/Sub producer stream for topic {} was completed.", topicId)
        context.stop(self)
    }

    private def run(tag: String, topic: ProjectTopicName, dao: OffsetDao): Unit = {
      val readSideSource = eventStreamFactory(tag, dao.loadedOffset)

      val (killSwitch, streamDone) = readSideSource
        .viaMat(KillSwitches.single)(Keep.right)
        .via(eventsPublisherFlow(topic, dao))
        .toMat(Sink.ignore)(Keep.both)
        .run()

      shutdown = Some(killSwitch)
      streamDone pipeTo self
      context.become(active)
    }

    private def eventsPublisherFlow(topic: ProjectTopicName, offsetDao: OffsetDao) =
      Flow.fromGraph(GraphDSL.create(pubsubFlowPublisher(topic)) { implicit builder =>
        publishFlow =>
          import GraphDSL.Implicits._
          val unzip = builder.add(Unzip[Message, Offset])
          val zip = builder.add(Zip[Any, Offset])
          val offsetCommitter = builder.add(Flow.fromFunction { e: (Any, Offset) =>
            offsetDao.saveOffset(e._2)
          })

          unzip.out0 ~> publishFlow ~> zip.in0
          unzip.out1 ~> zip.in1
          zip.out ~> offsetCommitter.in
          FlowShape(unzip.in, offsetCommitter.out)
      })

    private def pubsubFlowPublisher(topic: ProjectTopicName): Flow[Message, _, _] = {
      val publisher = {
        val builder = Publisher.newBuilder(topic)
        pubsubConfig.emulatorHost
          .map { host =>
            val channel = ManagedChannelBuilder.forTarget(host).usePlaintext(true).build()
            val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
            builder
              .setChannelProvider(channelProvider)
              .setCredentialsProvider(NoCredentialsProvider.create).build
          }
          .getOrElse {
            pubsubConfig.serviceAccountPath
              .map { path =>
                builder.setCredentialsProvider(
                  FixedCredentialsProvider.create(
                    ServiceAccountCredentials.fromStream(
                      new FileInputStream(path)))).build()
              }
              .getOrElse {
                builder.build()
              }
          }
      }

      Flow
        .fromFunction[Message, PubsubMessage](transform)
        .mapAsync(1)(msg => publishMessage(publisher, msg))
    }
  }

  private object TaggedOffsetProducerActor {
    def props[Message](pubsubConfig: PubsubConfig, topicId: String,
                       eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
                       transform: Message => PubsubMessage, offsetStore: OffsetStore)
                      (implicit mat: Materializer, ec: ExecutionContext) =
      Props(new TaggedOffsetProducerActor[Message](pubsubConfig, topicId, eventStreamFactory, transform, offsetStore))

    def publishMessage(publisher: Publisher, message: PubsubMessage): Future[Done] = {
      val resultFuture = publisher.publish(message)
      val promise = Promise[Done]()
      ApiFutures.addCallback(resultFuture, new ApiFutureCallback[String]() {
        def onSuccess(result: String): Unit = {
          promise.success(Done)
        }

        def onFailure(throwable: Throwable): Unit = {
          promise.failure(throwable)
        }
      })
      promise.future
    }
  }

  private object Protocol {

    case object TopicCreated

  }

}
