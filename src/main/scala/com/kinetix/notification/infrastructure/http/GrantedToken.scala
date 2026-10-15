package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import io.circe.Decoder
import org.http4s.EntityDecoder
import org.http4s.circe.jsonOf

private[http] final case class GrantedToken(access_token: String, expires_in: Int)

private[http] object GrantedToken:
  given Decoder[GrantedToken] =
    Decoder.forProduct2("access_token", "expires_in")(GrantedToken.apply)

  given EntityDecoder[IO, GrantedToken] = jsonOf[IO, GrantedToken]
