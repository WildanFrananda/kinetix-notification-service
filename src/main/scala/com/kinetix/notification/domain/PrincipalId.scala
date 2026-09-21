package com.kinetix.notification.domain

opaque type PrincipalId = String

object PrincipalId:
  def fromString(raw: String): Option[PrincipalId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then None else Some(trimmed)

  def unsafe(raw: String): PrincipalId = raw

  extension (id: PrincipalId) def value: String = id
