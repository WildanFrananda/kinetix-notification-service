package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import org.http4s.dsl.io.*
import org.http4s.{Header, HttpApp, HttpRoutes, Request}
import org.typelevel.ci.CIString

import com.kinetix.notification.infrastructure.observability.Metrics

object HttpApi:
  val Health: String = "/health"
  val Ready: String = "/health/ready"
  val MetricsPath: String = "/metrics"

  val Routes: List[String] = List(Health, Ready, MetricsPath)
  val Unmatched: String = "unmatched"

  def apply(metrics: Metrics, ready: IO[Boolean]): HttpApp[IO] =
    HttpRoutes
      .of[IO]:
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
      .orNotFound

  def route(request: Request[IO]): String = request.pathInfo.renderString match
    case Health      => Health
    case Ready       => Ready
    case MetricsPath => MetricsPath
    case _           => Unmatched

  private def contentType(value: String): Header.Raw =
    Header.Raw(CIString("Content-Type"), value)
