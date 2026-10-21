package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import org.http4s.dsl.io.*
import org.http4s.{Header, HttpApp, HttpRoutes, Method, Request, Response, Status}
import org.typelevel.ci.CIString

import com.kinetix.notification.infrastructure.observability.Metrics

object HttpApi:
  val Health: String = "/health"
  val Ready: String = "/health/ready"
  val MetricsPath: String = "/metrics"

  val Probes: List[String] = List(Health, Ready, MetricsPath)
  val Routes: List[String] = Probes ++ DeviceApi.Allowed.map(_._1)
  val Unmatched: String = "unmatched"

  private val allowed: Map[String, List[Method]] =
    (Probes.map(_ -> List(Method.GET)) ++ DeviceApi.Allowed).toMap

  def apply(metrics: Metrics, ready: IO[Boolean], devices: HttpRoutes[IO]): HttpApp[IO] =
    val routes = probes(metrics, ready) <+> devices

    HttpApp[IO](request => routes.run(request).getOrElseF(unmatched(request)))

  def route(request: Request[IO]): String =
    val path = request.pathInfo.renderString

    Routes.find(_ == path).getOrElse(Unmatched)

  private def unmatched(request: Request[IO]): IO[Response[IO]] =
    allowed.get(request.pathInfo.renderString) match
      case Some(methods) =>
        refusal(Status.MethodNotAllowed, "METHOD_NOT_ALLOWED")
          .map(_.putHeaders(Header.Raw(CIString("Allow"), methods.map(_.name).mkString(", "))))
      case None => refusal(Status.NotFound, "NOT_FOUND")

  private def refusal(status: Status, code: String): IO[Response[IO]] =
    IO.pure(
      Response[IO](status)
        .withEntity(Json.obj("error" -> Json.fromString(code)).noSpaces)
        .putHeaders(contentType("application/json"))
    )

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
