package com.lightbend.lagom.internal.broker.pubsub

import java.io.FileInputStream

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, Props, Status}
import akka.pattern.pipe
import akka.stream.{KillSwitch, KillSwitches, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import Protocol._
import PubsubSubscriberActor._
import com.google.api.gax.core.{FixedCredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{AlreadyExistsException, FixedTransportChannelProvider}
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, SubscriptionAdminSettings}
import com.google.pubsub.v1._
import io.grpc.ManagedChannelBuilder

import scala.concurrent.{ExecutionContext, Future, Promise}

private[lagom] class PubsubSubscriberActor[Message](pubsubConfig: PubsubConfig,
                                                    consumerConfig: ConsumerConfig,
                                                    topicId: String,
                                                    flow: Flow[Message, Done, _],
                                                    streamCompleted: Promise[Done],
                                                    transform: PubsubMessage => Message)
                                                   (implicit mat: Materializer, ec: ExecutionContext)
  extends Actor with ActorLogging {

  /** Switch used to terminate the on-going Pub/Sub publishing stream when this actor fails. */
  private var shutdown: Option[KillSwitch] = None

  override def preStart(): Unit = {
    val topic: ProjectTopicName = ProjectTopicName.of(pubsubConfig.projectId, topicId)
    val subscription: ProjectSubscriptionName =
      ProjectSubscriptionName.of(pubsubConfig.projectId, consumerConfig.subscriptionName)

    createSubscription(consumerConfig, topic, subscription, pubsubConfig).map(_ => SubscriptionCreated) pipeTo self
    context.become(creatingSubscription(topic, subscription))
  }

  override def postStop(): Unit = shutdown.foreach(_.shutdown())

  override def receive: Receive = PartialFunction.empty

  private def creatingSubscription(topic: ProjectTopicName, subscription: ProjectSubscriptionName): Receive = {
    case SubscriptionCreated =>
      log.debug("Subscription [{}] created", subscription.getSubscription)
      run(subscription)
  }

  private def run(subscription: ProjectSubscriptionName): Unit = {
    val source: Source[PubsubMessage, NotUsed] = Source.fromGraph(new PubsubSource(subscription, pubsubConfig))

    val (killSwitch, stream) =
      source
        .map[Message](transform)
        .via(flow)
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run()

    shutdown = Some(killSwitch)
    stream pipeTo self
    context.become(running)
  }

  private def running: Receive = {
    case Status.Failure(e) =>
      log.error("Topic subscription interrupted due to failure", e)
      throw e

    case Done =>
      log.info("Pub/Sub subscriber stream for topic {} was completed.", topicId)
      streamCompleted.success(Done)
      context.stop(self)
  }
}

object PubsubSubscriberActor {
  def createSubscription(consumerConfig: ConsumerConfig, topic: ProjectTopicName,
                         subscription: ProjectSubscriptionName, pubsubConfig: PubsubConfig)
                        (implicit ec: ExecutionContext): Future[Unit] = Future {
    val settings: SubscriptionAdminSettings = {
      val builder = SubscriptionAdminSettings.newBuilder()
      pubsubConfig.emulatorHost
        .map { host =>
          val channel = ManagedChannelBuilder.forTarget(host).usePlaintext(true).build()
          val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
          builder
            .setTransportChannelProvider(channelProvider)
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

    val client: SubscriptionAdminClient = SubscriptionAdminClient.create(settings)

    try {
      client.createSubscription(subscription, topic, PushConfig.getDefaultInstance, consumerConfig.ackDeadline)
    } catch {
      case _: AlreadyExistsException =>
    }
  }

  def props[Message](pubsubConfig: PubsubConfig, consumerConfig: ConsumerConfig, topicId: String,
                     flow: Flow[Message, Done, _], streamCompleted: Promise[Done], transform: PubsubMessage => Message)
                    (implicit mat: Materializer, ec: ExecutionContext) =
    Props(new PubsubSubscriberActor[Message](pubsubConfig, consumerConfig, topicId, flow, streamCompleted, transform))
}

private object Protocol {

  case object SubscriptionCreated

}