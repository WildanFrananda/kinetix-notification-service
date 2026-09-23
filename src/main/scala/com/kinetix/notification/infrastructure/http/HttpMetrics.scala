package com.kinetix.notification.infrastructure.http

import cats.effect.{Clock, IO}
import org.http4s.HttpApp

import com.kinetix.notification.infrastructure.observability.Metrics

object HttpMetrics:
  def apply(metrics: Metrics, app: HttpApp[IO]): HttpApp[IO] =
    HttpApp[IO]: request =>
      for
        started <- Clock[IO].monotonic
        response <- app.run(request)
        finished <- Clock[IO].monotonic
        _ <- metrics.httpRequest(
          request.method.name,
          HttpApi.route(request),
          response.status.code,
          finished - started
        )
      yield response
