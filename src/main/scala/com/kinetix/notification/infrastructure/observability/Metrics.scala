package com.kinetix.notification.infrastructure.observability

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Ref}

final class Metrics private (
  counters: Ref[IO, Map[MetricKey, Long]],
  histograms: Ref[IO, Map[MetricKey, HistogramSample]],
  serviceName: String,
  version: String
):
  def httpRequest(
    method: String,
    route: String,
    status: Int,
    elapsed: FiniteDuration
  ): IO[Unit] =
    val shared = List("method" -> method, "route" -> route)
    count(MetricKey(Metrics.HttpRequests, shared :+ ("status" -> status.toString))) *>
      observe(MetricKey(Metrics.HttpDuration, shared), elapsed.toNanos.toDouble / 1e9d)

  def grpcServerCall(grpcMethod: String, code: String): IO[Unit] =
    count(
      MetricKey(Metrics.GrpcServerCalls, List("grpc_method" -> grpcMethod, "grpc_code" -> code))
    )

  def grpcClientCall(peer: String, grpcMethod: String, code: String): IO[Unit] =
    count(
      MetricKey(
        Metrics.GrpcClientCalls,
        List("peer" -> peer, "grpc_method" -> grpcMethod, "grpc_code" -> code)
      )
    )

  def seed(keys: List[MetricKey]): IO[Unit] =
    val counterSeeds = keys.filterNot(_.name == Metrics.HttpDuration)
    val histogramSeeds = keys.filter(_.name == Metrics.HttpDuration)
    counters.update(current => counterSeeds.foldLeft(current)(atZero)) *>
      histograms.update(current => histogramSeeds.foldLeft(current)(emptySample))

  val scrape: IO[String] =
    for
      counted <- counters.get
      observed <- histograms.get
    yield render(counted, observed)

  private def atZero(current: Map[MetricKey, Long], key: MetricKey): Map[MetricKey, Long] =
    if current.contains(key) then current else current.updated(key, 0L)

  private def emptySample(
    current: Map[MetricKey, HistogramSample],
    key: MetricKey
  ): Map[MetricKey, HistogramSample] =
    if current.contains(key) then current else current.updated(key, HistogramSample.empty)

  private def count(key: MetricKey): IO[Unit] =
    counters.update(current => current.updated(key, current.getOrElse(key, 0L) + 1L))

  private def observe(key: MetricKey, seconds: Double): IO[Unit] =
    histograms.update: current =>
      current.updated(key, current.getOrElse(key, HistogramSample.empty).record(seconds))

  private def render(
    counted: Map[MetricKey, Long],
    observed: Map[MetricKey, HistogramSample]
  ): String =
    List(
      family(
        Metrics.BuildInfo,
        "gauge",
        "The build this process is running, as a constant 1.",
        List(
          Metrics.BuildInfo +
            PrometheusText.labels(List("service" -> serviceName, "version" -> version)) + " 1"
        )
      ),
      family(
        Metrics.HttpRequests,
        "counter",
        "HTTP requests served.",
        counterLines(Metrics.HttpRequests, counted)
      ),
      family(
        Metrics.HttpDuration,
        "histogram",
        "How long an HTTP request took, in seconds.",
        histogramLines(observed)
      ),
      family(
        Metrics.GrpcServerCalls,
        "counter",
        "gRPC calls this service answered.",
        counterLines(Metrics.GrpcServerCalls, counted)
      ),
      family(
        Metrics.GrpcClientCalls,
        "counter",
        "gRPC calls this service made to another service.",
        counterLines(Metrics.GrpcClientCalls, counted)
      )
    ).mkString

  private def family(name: String, kind: String, help: String, lines: List[String]): String =
    (s"# HELP $name $help" :: s"# TYPE $name $kind" :: lines).mkString("", "\n", "\n")

  private def counterLines(name: String, counted: Map[MetricKey, Long]): List[String] =
    counted.toList
      .filter((key, _) => key.name == name)
      .map((key, value) => s"$name${PrometheusText.labels(key.labels)} $value")
      .sorted

  private def histogramLines(observed: Map[MetricKey, HistogramSample]): List[String] =
    observed.toList
      .sortBy((key, _) => PrometheusText.labels(key.labels))
      .flatMap: (key, sample) =>
        val bucketed = HistogramSample.Bounds
          .zip(sample.buckets)
          .toList
          .map: (bound, hits) =>
            val bounded = key.labels :+ ("le" -> bound.toString)
            s"${key.name}_bucket${PrometheusText.labels(bounded)} $hits"
        val unbounded = key.labels :+ ("le" -> "+Inf")
        bucketed
          :+ s"${key.name}_bucket${PrometheusText.labels(unbounded)} ${sample.count}"
          :+ s"${key.name}_sum${PrometheusText.labels(key.labels)} ${sample.sum}"
          :+ s"${key.name}_count${PrometheusText.labels(key.labels)} ${sample.count}"

object Metrics:
  val HttpRequests: String = "kinetix_http_requests_total"
  val HttpDuration: String = "kinetix_http_request_duration_seconds"
  val GrpcServerCalls: String = "kinetix_grpc_server_calls_total"
  val GrpcClientCalls: String = "kinetix_grpc_client_calls_total"
  val BuildInfo: String = "kinetix_build_info"

  val ContentType: String = "text/plain; version=0.0.4; charset=utf-8"

  def create(serviceName: String, version: String): IO[Metrics] =
    for
      counters <- Ref.of[IO, Map[MetricKey, Long]](Map.empty)
      histograms <- Ref.of[IO, Map[MetricKey, HistogramSample]](Map.empty)
    yield new Metrics(counters, histograms, serviceName, version)
