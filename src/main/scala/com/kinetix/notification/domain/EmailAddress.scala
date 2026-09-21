package com.kinetix.notification.domain

opaque type EmailAddress = String

object EmailAddress:
  private val Shape = """^[^@\s]+@[^@\s.]+\.[^@\s]+$""".r

  def fromString(raw: String): Option[EmailAddress] =
    val trimmed = raw.trim
    if Shape.matches(trimmed) then Some(trimmed) else None

  extension (address: EmailAddress) def value: String = address
