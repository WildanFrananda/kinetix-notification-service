package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

import com.kinetix.notification.infrastructure.observability.Metrics

class HttpApiSuite extends CatsEffectSuite:
  private def get(path: String) = Request[IO](Method.GET, Uri.unsafeFromString(path))

  private def metrics = Metrics.create("kinetix-notification-service", "v0.1.0")

  test("liveness answers while the database is gone") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(false))
      response <- app.run(get("/health"))
    yield assertEquals(response.status, Status.Ok)
  }

  test("readiness answers 200 only when the database answered") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(true))
      response <- app.run(get("/health/ready"))
    yield assertEquals(response.status, Status.Ok)
  }

  test("a database that did not answer makes the container unhealthy, not merely quiet") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(false))
      response <- app.run(get("/health/ready"))
      body <- response.as[String]
    yield
      assertEquals(response.status, Status.ServiceUnavailable)
      assertEquals(body, """{"status":"unready"}""")
  }

  test("/metrics is served as Prometheus text, not as JSON") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(true))
      response <- app.run(get("/metrics"))
      body <- response.as[String]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(
        response.headers.headers.find(_.name.toString == "Content-Type").map(_.value),
        Some("text/plain; version=0.0.4; charset=utf-8")
      )
      assert(body.startsWith("# HELP "), body.take(80))
  }

  test("a path nobody serves is one label, not one label per path") {
    assertEquals(HttpApi.route(get("/orders/8f3a2b1c")), HttpApi.Unmatched)
    assertEquals(HttpApi.route(get("/orders/99999")), HttpApi.Unmatched)
    assertEquals(HttpApi.route(get("/health/ready")), HttpApi.Ready)
  }

  test("a served request is counted under its own template") {
    for
      recorder <- metrics
      counted = HttpMetrics(recorder, HttpApi(recorder, IO.pure(true)))
      _ <- counted.run(get("/health"))
      _ <- counted.run(get("/nothing/here"))
      body <- recorder.scrape
    yield
      assert(
        body.contains(
          """kinetix_http_requests_total{method="GET",route="/health",status="200"} 1"""
        ),
        body
      )
      assert(body.contains("""route="unmatched",status="404"} 1"""), body)
  }
