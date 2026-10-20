package com.kinetix.notification.infrastructure.grpc

import scala.concurrent.duration.*

import cats.effect.std.Dispatcher
import cats.effect.{IO, Resource}
import fs2.grpc.syntax.all.*
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import munit.CatsEffectSuite
import notification.v1.notification as wire

import com.kinetix.notification.fakes.Fixtures
import com.kinetix.notification.infrastructure.observability.{Metrics, PrometheusText}

class MetricsInterceptorSuite extends CatsEffectSuite:
  private val notifyMethod: String = "notification.v1.NotificationService/Notify"

  private val request: wire.NotifyRequest =
    wire.NotifyRequest(
      recipientPrincipalId = Fixtures.recipient.value,
      template = wire.Template.TEMPLATE_ORDER_PACKED,
      params = Fixtures.orderParams
    )

  private def once(name: String, labels: List[(String, String)]): String =
    name + PrometheusText.labels(labels) + " 1"

  private def recorded(metrics: Metrics, line: String): IO[Unit] =
    (IO.sleep(10.millis) *> metrics.scrape)
      .iterateUntil(_.linesIterator.contains(line))
      .timeoutTo(
        5.seconds,
        metrics.scrape.flatMap(body => IO(fail(s"$line was never counted:\n$body")))
      )
      .void

  test("a call through both interceptors is answered and counted on each side") {
    val headers = Metadata()
    headers.put(CallerHeader.Key, "order")

    val running =
      for
        metrics <- Resource.eval(Metrics.create("kinetix-notification-service", "test"))
        dispatcher <- Dispatcher.parallel[IO]
        served <- Served.start(Some(ServerMetricsInterceptor(metrics, dispatcher)))
        channel <- NettyChannelBuilder
          .forAddress("127.0.0.1", served.port)
          .usePlaintext()
          .intercept(ClientMetricsInterceptor(metrics, dispatcher, "notification"))
          .resource[IO]
        client <- wire.NotificationServiceFs2Grpc.stubResource[IO](channel)
      yield (metrics, client)

    running.use: (metrics, client) =>
      for
        response <- client.notify(request, headers).timeout(10.seconds)
        _ <- recorded(
          metrics,
          once(Metrics.GrpcServerCalls, List("grpc_method" -> notifyMethod, "grpc_code" -> "OK"))
        )
        _ <- recorded(
          metrics,
          once(
            Metrics.GrpcClientCalls,
            List("peer" -> "notification", "grpc_method" -> notifyMethod, "grpc_code" -> "OK")
          )
        )
      yield assert(response.accepted, s"the call was refused: ${response.error}")
  }
