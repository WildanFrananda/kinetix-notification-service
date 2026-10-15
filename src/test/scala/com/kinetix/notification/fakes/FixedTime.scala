package com.kinetix.notification.fakes

import java.time.Instant

import cats.effect.IO

import com.kinetix.notification.domain.ports.TimeSource

final class FixedTime(at: Instant) extends TimeSource[IO]:
  def now: IO[Instant] = IO.pure(at)
