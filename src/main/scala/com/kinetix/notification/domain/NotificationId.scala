package com.kinetix.notification.domain

import java.util.UUID

opaque type NotificationId = String

object NotificationId:
  def fromString(raw: String): Option[NotificationId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then None else Some(trimmed)

  def fromUuid(uuid: UUID): NotificationId = uuid.toString

  extension (id: NotificationId) def value: String = id
