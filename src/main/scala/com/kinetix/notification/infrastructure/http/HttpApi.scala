package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.dsl.io.*
import org.http4s.{Header, HttpApp, HttpRoutes, Request}
import org.typelevel.ci.CIString

import com.kinetix.notification.infrastructure.observability.Metrics

object HttpApi:
  val Health: String = "/health"
  val Ready: String = "/health/ready"
  val MetricsPath: String = "/metrics"

  val Probes: List[String] = List(Health, Ready, MetricsPath)
  val Routes: List[String] = Probes :+ DeviceApi.Path
  val Unmatched: String = "unmatched"

  def apply(metrics: Metrics, ready: IO[Boolean], devices: HttpRoutes[IO]): HttpApp[IO] =
    (probes(metrics, ready) <+> devices).orNotFound

  def route(request: Request[IO]): String =
    val path = request.pathInfo.renderString

    Routes.find(_ == path).getOrElse(Unmatched)

  private def probes(metrics: Metrics, ready: IO[Boolean]): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "health" =>
        Ok("""{"status":"ok"}""").map(_.putHeaders(contentType("application/json")))

      case GET -> Root / "health" / "ready" =>
        ready.flatMap:
          case true =>
            Ok("""{"status":"ready"}""").map(_.putHeaders(contentType("application/json")))
          case false =>
            ServiceUnavailable("""{"status":"unready"}""")
              .map(_.putHeaders(contentType("application/json")))

      case GET -> Root / "metrics" =>
        metrics.scrape.flatMap: body =>
          Ok(body).map(_.putHeaders(contentType(Metrics.ContentType)))

  private def contentType(value: String): Header.Raw =
    Header.Raw(CIString("Content-Type"), value)
