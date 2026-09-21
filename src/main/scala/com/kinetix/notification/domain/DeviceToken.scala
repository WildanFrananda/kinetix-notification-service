package com.kinetix.notification.domain

opaque type DeviceToken = String

object DeviceToken:
  def fromString(raw: String): Option[DeviceToken] =
    val trimmed = raw.trim
    if trimmed.isEmpty then None else Some(trimmed)

  extension (token: DeviceToken) def value: String = token
