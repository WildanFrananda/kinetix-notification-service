package com.kinetix.notification.infrastructure

import java.util.UUID

import cats.effect.IO
import cats.effect.std.UUIDGen

import com.kinetix.notification.domain.NotificationId
import com.kinetix.notification.domain.ports.NotificationIdSource

final class RandomNotificationIdSource extends NotificationIdSource[IO]:
  def next: IO[NotificationId] = UUIDGen[IO].randomUUID.map(fromUuid)

  private def fromUuid(uuid: UUID): NotificationId = NotificationId.fromUuid(uuid)
