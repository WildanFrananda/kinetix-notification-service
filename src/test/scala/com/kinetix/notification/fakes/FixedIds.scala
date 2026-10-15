package com.kinetix.notification.fakes

import cats.effect.IO

import com.kinetix.notification.domain.NotificationId
import com.kinetix.notification.domain.ports.NotificationIdSource

final class FixedIds(id: NotificationId) extends NotificationIdSource[IO]:
  def next: IO[NotificationId] = IO.pure(id)
