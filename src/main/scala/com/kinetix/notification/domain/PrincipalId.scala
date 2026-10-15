package com.kinetix.notification.domain

opaque type PrincipalId = String

object PrincipalId:
  val MaxLength: Int = 64

  def fromString(raw: String): Option[PrincipalId] =
    val trimmed = raw.trim
    if trimmed.isEmpty || trimmed.length > MaxLength then None else Some(trimmed)

  extension (id: PrincipalId) def value: String = id
