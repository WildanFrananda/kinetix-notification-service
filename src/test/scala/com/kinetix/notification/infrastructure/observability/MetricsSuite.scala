package com.kinetix.notification.infrastructure.observability

import scala.concurrent.duration.*

import munit.CatsEffectSuite

class MetricsSuite extends CatsEffectSuite:
  private val seeds = List(
    MetricKey(
      Metrics.HttpRequests,
      List("method" -> "GET", "route" -> "/health", "status" -> "200")
    ),
    MetricKey(Metrics.HttpDuration, List("method" -> "GET", "route" -> "/health")),
    MetricKey(Metrics.GrpcServerCalls, List("grpc_method" -> "a.B/C", "grpc_code" -> "OK")),
    MetricKey(
      Metrics.GrpcClientCalls,
      List("peer" -> "identity", "grpc_method" -> "a.B/C", "grpc_code" -> "OK")
    )
  )

  private def seeded =
    Metrics.create("kinetix-notification-service", "v0.1.0").flatTap(_.seed(seeds))

  test("a freshly started process already serves every family the contract names") {
    seeded
      .flatMap(_.scrape)
      .map: body =>
        List(
          Metrics.HttpRequests,
          Metrics.HttpDuration,
          Metrics.GrpcServerCalls,
          Metrics.GrpcClientCalls,
          Metrics.BuildInfo
        ).foreach: name =>
          assert(
            body.linesIterator.exists(_.startsWith(name)),
            s"$name has no sample line, only a header:\n$body"
          )
  }

  test("it is Prometheus text format, with a HELP line") {
    seeded
      .flatMap(_.scrape)
      .map: body =>
        assert(body.linesIterator.exists(_.startsWith("# HELP ")), body)
        assert(body.endsWith("\n"), "the exposition does not end with a newline")
  }

  test("build_info carries the service and its version, and equals one") {
    seeded
      .flatMap(_.scrape)
      .map: body =>
        val line = body.linesIterator.find(_.startsWith(Metrics.BuildInfo)).getOrElse("")
        assertEquals(
          line,
          """kinetix_build_info{service="kinetix-notification-service",version="v0.1.0"} 1"""
        )
  }

  test("a request raises its counter and lands in a bucket") {
    for
      metrics <- seeded
      _ <- metrics.httpRequest("GET", "/health", 200, 20.milliseconds)
      body <- metrics.scrape
    yield
      assert(
        body.contains(
          """kinetix_http_requests_total{method="GET",route="/health",status="200"} 1"""
        ),
        body
      )
      assert(
        body.contains(
          """kinetix_http_request_duration_seconds_bucket{method="GET",route="/health",le="0.025"} 1"""
        ),
        body
      )
      assert(
        body.contains(
          """kinetix_http_request_duration_seconds_count{method="GET",route="/health"} 1"""
        ),
        body
      )

  }

  test("a histogram counts every bucket at or above the observation, and +Inf holds them all") {
    for
      metrics <- seeded
      _ <- metrics.httpRequest("GET", "/health", 200, 20.milliseconds)
      body <- metrics.scrape
    yield
      val buckets = body.linesIterator
        .filter(_.startsWith("kinetix_http_request_duration_seconds_bucket"))
        .filter(_.contains("""route="/health""""))
        .toList
      val below = buckets.count(_.endsWith(" 0"))
      val above = buckets.count(_.endsWith(" 1"))
      assertEquals(below, 2, s"0.005 and 0.01 should not hold a 20ms observation:\n$body")
      assertEquals(above, 10, s"every bucket from 0.025 up, plus +Inf, should:\n$body")
  }

  test("a gRPC call is counted against the code it closed with") {
    for
      metrics <- seeded
      _ <- metrics.grpcServerCall("a.B/C", "UNAVAILABLE")
      _ <- metrics.grpcClientCall("identity", "a.B/C", "DEADLINE_EXCEEDED")
      body <- metrics.scrape
    yield
      assert(
        body.contains(
          """kinetix_grpc_server_calls_total{grpc_method="a.B/C",grpc_code="UNAVAILABLE"} 1"""
        ),
        body
      )
      assert(
        body.contains(
          """kinetix_grpc_client_calls_total{peer="identity",grpc_method="a.B/C",grpc_code="DEADLINE_EXCEEDED"} 1"""
        ),
        body
      )
  }

  test("a quote in a label value is escaped, not left to break the line") {
    for
      metrics <- Metrics.create("kinetix-notification-service", """a"b\c""")
      body <- metrics.scrape
    yield assert(body.contains("""version="a\"b\\c""""), body)
  }
