package com.kinetix.notification.infrastructure.http

import io.circe.Decoder

final case class DeviceBody(deviceToken: String, platform: String)

object DeviceBody:
  given Decoder[DeviceBody] = Decoder.forProduct2("device_token", "platform")(DeviceBody.apply)
