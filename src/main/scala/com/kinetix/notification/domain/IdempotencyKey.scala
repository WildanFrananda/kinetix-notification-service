package com.kinetix.notification.domain

opaque type IdempotencyKey = String

object IdempotencyKey:
  val MaxLength: Int = 128

  def fromString(raw: String): Option[IdempotencyKey] =
    val trimmed = raw.trim
    if trimmed.isEmpty || trimmed.length > MaxLength then None else Some(trimmed)

  extension (key: IdempotencyKey) def value: String = key
