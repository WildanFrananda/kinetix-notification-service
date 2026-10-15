package com.kinetix.notification.infrastructure.http

import io.circe.Decoder

final case class ForgetBody(deviceToken: String)

object ForgetBody:
  given Decoder[ForgetBody] = Decoder.forProduct1("device_token")(ForgetBody.apply)
