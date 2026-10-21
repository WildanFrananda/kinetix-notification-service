package com.kinetix.notification.domain

opaque type DeviceNonce = String

object DeviceNonce:
  val MaxLength: Int = 128

  def fromString(raw: String): Option[DeviceNonce] =
    val trimmed = raw.trim
    if trimmed.isEmpty || trimmed.length > MaxLength then None else Some(trimmed)

  extension (nonce: DeviceNonce) def value: String = nonce
