package com.kinetix.notification.domain

opaque type IdempotencyKey = String

object IdempotencyKey:
  def fromString(raw: String): Option[IdempotencyKey] =
    val trimmed = raw.trim
    if trimmed.isEmpty then None else Some(trimmed)

  extension (key: IdempotencyKey) def value: String = key
