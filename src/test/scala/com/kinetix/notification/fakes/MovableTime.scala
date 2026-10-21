package com.kinetix.notification.fakes

import java.time.{Duration, Instant}

import cats.effect.{IO, Ref}

import com.kinetix.notification.domain.ports.TimeSource

final class MovableTime(current: Ref[IO, Instant]) extends TimeSource[IO]:
  def now: IO[Instant] = current.get

  def advance(by: Duration): IO[Unit] = current.update(_.plus(by))

object MovableTime:
  def at(start: Instant): IO[MovableTime] = Ref.of[IO, Instant](start).map(MovableTime(_))
