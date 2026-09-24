package com.kinetix.notification.infrastructure.http

import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey}
import java.util.Base64

import cats.effect.IO
import io.circe.Decoder
import io.circe.parser.decode

final case class GoogleServiceAccount(clientEmail: String, tokenUri: String, privateKey: PrivateKey)

object GoogleServiceAccount:
  private final case class Raw(client_email: String, token_uri: String, private_key: String)

  private given Decoder[Raw] =
    Decoder.forProduct3("client_email", "token_uri", "private_key")(Raw.apply)

  def fromBase64(name: String, encoded: String): IO[GoogleServiceAccount] =
    for
      json <- IO
        .fromEither(
          scala.util
            .Try(String(Base64.getDecoder.decode(encoded.trim), "UTF-8"))
            .toEither
            .left
            .map(_ => IllegalStateException(s"$name is not base64."))
        )
      raw <- IO.fromEither(
        decode[Raw](json).left.map(failure =>
          IllegalStateException(s"$name does not hold a Google service account key: $failure")
        )
      )
      key <- parseKey(name, raw.private_key)
    yield GoogleServiceAccount(raw.client_email, raw.token_uri, key)

  private def parseKey(name: String, pem: String): IO[PrivateKey] =
    val body = pem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replaceAll("\\s", "")

    IO
      .fromEither(
        scala.util
          .Try {
            val decoded = Base64.getDecoder.decode(body)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoded))
          }
          .toEither
          .left
          .map(_ =>
            IllegalStateException(s"$name holds a private_key that is not a PKCS#8 RSA key.")
          )
      )
