package com.kinetix.notification.domain

opaque type DeviceToken = String

object DeviceToken:
  val MaxLength: Int = 512

  def fromString(raw: String): Option[DeviceToken] =
    val trimmed = raw.trim
    if trimmed.isEmpty || trimmed.length > MaxLength then None else Some(trimmed)

  extension (token: DeviceToken) def value: String = token
