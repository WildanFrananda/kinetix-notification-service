package com.kinetix.notification.domain

opaque type Caller = String

object Caller:
  private val Shape = "[a-z][a-z0-9-]{0,62}".r

  def fromString(raw: String): Option[Caller] =
    val trimmed = raw.trim
    if Shape.matches(trimmed) then Some(trimmed) else None

  extension (caller: Caller) def value: String = caller
