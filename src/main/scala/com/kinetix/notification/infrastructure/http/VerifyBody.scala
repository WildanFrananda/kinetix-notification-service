package com.kinetix.notification.infrastructure.http

import io.circe.Decoder

final case class VerifyBody(deviceToken: String, nonce: String)

object VerifyBody:
  given Decoder[VerifyBody] = Decoder.forProduct2("device_token", "nonce")(VerifyBody.apply)
