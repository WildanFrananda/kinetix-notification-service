package com.kinetix.notification.infrastructure.grpc

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import cats.effect.{IO, Resource}
import fs2.grpc.syntax.all.*
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.reflection.v1.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse
}
import io.grpc.stub.StreamObserver

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.domain.Caller

class GrpcReflectionSuite extends BoundedSuite:
  private val served: Resource[IO, Int] = Served.start(None).map(_.port)

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

  test("reflection answers any verified peer, even when the list grants nobody") {
    val nobody = PeerAllowList.parse("TEST", "").toOption.get
    val order = Caller.fromString("order")

    Served
      .start(Some(PeerAuthorizationInterceptor(nobody, _ => order)))
      .map(_.port)
      .flatMap(channel)
      .use(listServices)
      .map: services =>
        assert(
          services.contains("notification.v1.NotificationService"),
          s"reflection was refused to a verified peer: $services"
        )
  }
