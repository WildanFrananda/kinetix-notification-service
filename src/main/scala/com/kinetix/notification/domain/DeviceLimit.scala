package com.kinetix.notification.domain

opaque type DeviceLimit = Int

object DeviceLimit:
  def fromInt(raw: Int): Option[DeviceLimit] =
    if raw >= 1 then Some(raw) else None

  extension (limit: DeviceLimit) def value: Int = limit
