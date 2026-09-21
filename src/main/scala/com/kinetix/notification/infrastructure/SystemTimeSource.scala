package com.kinetix.notification.infrastructure

import java.time.Instant

import cats.effect.{Clock, IO}

import com.kinetix.notification.domain.ports.TimeSource

final class SystemTimeSource extends TimeSource[IO]:
  def now: IO[Instant] = Clock[IO].realTimeInstant
