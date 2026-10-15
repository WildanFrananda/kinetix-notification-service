package com.kinetix.notification.infrastructure.http

import io.circe.Decoder

private[http] final case class ServiceAccountKey(
  client_email: String,
  token_uri: String,
  private_key: String
)

private[http] object ServiceAccountKey:
  given Decoder[ServiceAccountKey] =
    Decoder.forProduct3("client_email", "token_uri", "private_key")(ServiceAccountKey.apply)
