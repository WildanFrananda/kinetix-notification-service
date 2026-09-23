package com.kinetix.notification.infrastructure.observability

final case class HistogramSample(buckets: Vector[Long], sum: Double, count: Long):
  def record(seconds: Double): HistogramSample =
    val raised = HistogramSample.Bounds.indices.toVector.map: index =>
      if seconds <= HistogramSample.Bounds(index) then buckets(index) + 1L else buckets(index)
    HistogramSample(raised, sum + seconds, count + 1L)

object HistogramSample:
  val Bounds: Vector[Double] =
    Vector(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)

  val empty: HistogramSample = HistogramSample(Bounds.map(_ => 0L), 0.0, 0L)
