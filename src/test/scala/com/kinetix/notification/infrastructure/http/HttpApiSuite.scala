package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import org.http4s.{HttpRoutes, Method, Request, Response, Status, Uri}

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.infrastructure.observability.Metrics

class HttpApiSuite extends BoundedSuite:
  private def get(path: String) = Request[IO](Method.GET, Uri.unsafeFromString(path))

  private def metrics = Metrics.create("kinetix-notification-service", "v0.1.0")

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.headers.find(_.name.toString == name).map(_.value)

  test("liveness answers while the database is gone") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(false), HttpRoutes.empty[IO])
      response <- app.run(get("/health"))
    yield assertEquals(response.status, Status.Ok)
  }

  test("readiness answers 200 only when the database answered") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(true), HttpRoutes.empty[IO])
      response <- app.run(get("/health/ready"))
    yield assertEquals(response.status, Status.Ok)
  }

  test("a database that did not answer makes the container unhealthy, not merely quiet") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(false), HttpRoutes.empty[IO])
      response <- app.run(get("/health/ready"))
      body <- response.as[String]
    yield
      assertEquals(response.status, Status.ServiceUnavailable)
      assertEquals(body, """{"status":"unready"}""")
  }

  test("/metrics is served as Prometheus text, not as JSON") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(true), HttpRoutes.empty[IO])
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
    assertEquals(HttpApi.route(get(DeviceApi.Path)), DeviceApi.Path)
    assertEquals(HttpApi.route(get(DeviceApi.VerifyPath)), DeviceApi.VerifyPath)
  }

  test("a served request is counted under its own template") {
    for
      recorder <- metrics
      counted = HttpMetrics(recorder, HttpApi(recorder, IO.pure(true), HttpRoutes.empty[IO]))
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

  test("a path nobody serves is a JSON 404, like every other refusal this service gives") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(true), HttpRoutes.empty[IO])
      response <- app.run(get("/nothing/here"))
      body <- response.as[String]
    yield
      assertEquals(response.status, Status.NotFound)
      assertEquals(header(response, "Content-Type"), Some("application/json"))
      assertEquals(body, """{"error":"NOT_FOUND"}""")
  }

  test("a served path asked with a method it does not take is a 405 naming the ones it does") {
    for
      recorder <- metrics
      app = HttpApi(recorder, IO.pure(true), HttpRoutes.empty[IO])
      devices <- app.run(Request[IO](Method.PUT, Uri.unsafeFromString(DeviceApi.Path)))
      body <- devices.as[String]
      probe <- app.run(Request[IO](Method.POST, Uri.unsafeFromString(HttpApi.Health)))
    yield
      assertEquals(devices.status, Status.MethodNotAllowed)
      assertEquals(header(devices, "Allow"), Some("POST, DELETE"))
      assertEquals(header(devices, "Content-Type"), Some("application/json"))
      assertEquals(body, """{"error":"METHOD_NOT_ALLOWED"}""")
      assertEquals(probe.status, Status.MethodNotAllowed)
      assertEquals(header(probe, "Allow"), Some("GET"))
  }
