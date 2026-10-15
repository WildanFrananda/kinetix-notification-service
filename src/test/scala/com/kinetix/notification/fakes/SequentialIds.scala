package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}

import com.kinetix.notification.domain.NotificationId
import com.kinetix.notification.domain.ports.NotificationIdSource

final class SequentialIds(issued: Ref[IO, Int]) extends NotificationIdSource[IO]:
  def next: IO[NotificationId] =
    issued.updateAndGet(_ + 1).map(n => NotificationId.fromString(s"NTF-$n").get)

object SequentialIds:
  def start: IO[SequentialIds] = Ref.of[IO, Int](0).map(SequentialIds(_))
