package com.kinetix.notification.infrastructure.grpc

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import cats.effect.{IO, Resource}
import fs2.grpc.syntax.all.*
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.{NettyChannelBuilder, NettyServerBuilder}
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.grpc.reflection.v1.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse
}
import io.grpc.stub.StreamObserver
import munit.CatsEffectSuite
import notification.v1.notification.NotificationServiceFs2Grpc

import com.kinetix.notification.application.{ForgetDevice, GetDelivery, Notify, RegisterDevice}
import com.kinetix.notification.domain.Channel
import com.kinetix.notification.fakes.*

class GrpcReflectionSuite extends CatsEffectSuite:
  private val served: Resource[IO, Int] =
    for
      directory <- Resource.eval(FakeDirectory.holding(Fixtures.email))
      sender <- Resource.eval(FakeSender.working(Channel.Email))
      repository <- Resource.eval(FakeRepository.empty)
      devices <- Resource.eval(FakeDeviceRegistry.empty)
      retry <- Resource.eval(CountingRetry.upTo(1))
      handler = GrpcNotificationServer(
        Notify[IO](
          Fixtures.catalogue,
          directory,
          List(sender),
          repository,
          retry,
          FixedTime(Fixtures.at),
          FixedIds(Fixtures.notificationId)
        ),
        GetDelivery[IO](repository),
        RegisterDevice[IO](devices),
        ForgetDevice[IO](devices)
      )
      service <- NotificationServiceFs2Grpc.bindServiceResource[IO](handler)
      server <- NettyServerBuilder
        .forPort(0)
        .addService(service)
        .addService(ProtoReflectionServiceV1.newInstance())
        .resource[IO]
        .evalMap(running => IO(running.start()))
    yield server.getPort

  private def channel(port: Int): Resource[IO, ManagedChannel] =
    NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().resource[IO]

  private def listServices(channel: ManagedChannel): IO[List[String]] =
    IO.blocking {
      val done = CountDownLatch(1)
      val found = AtomicReference[List[String]](Nil)

      val answers = new StreamObserver[ServerReflectionResponse] {
        override def onNext(response: ServerReflectionResponse): Unit =
          found.set(response.getListServicesResponse.getServiceList.asScala.toList.map(_.getName))
        override def onError(failure: Throwable): Unit = done.countDown()
        override def onCompleted(): Unit = done.countDown()
      }

      val asks = ServerReflectionGrpc.newStub(channel).serverReflectionInfo(answers)
      asks.onNext(ServerReflectionRequest.newBuilder().setListServices("*").build())
      asks.onCompleted()

      val answered = done.await(10, TimeUnit.SECONDS)
      if !answered then Nil else found.get()
    }

  test("the server lists what it serves when asked by reflection") {
    served
      .flatMap(channel)
      .use(listServices)
      .map: services =>
        assert(
          services.contains("notification.v1.NotificationService"),
          s"reflection did not name this service; it answered: $services"
        )
  }

  test("reflection names itself too, which is how a client knows it may ask") {
    served
      .flatMap(channel)
      .use(listServices)
      .map: services =>
        assert(
          services.exists(_.contains("ServerReflection")),
          s"reflection is not on its own list: $services"
        )
  }
