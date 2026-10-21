package com.kinetix.notification.infrastructure

import java.security.SecureRandom
import java.util.Base64

import cats.effect.IO

import com.kinetix.notification.domain.DeviceNonce
import com.kinetix.notification.domain.ports.NonceSource

final class SecureRandomNonceSource(random: SecureRandom) extends NonceSource[IO]:
  def next: IO[DeviceNonce] =
    IO(drawn()).flatMap: raw =>
      IO.fromOption(DeviceNonce.fromString(raw))(
        IllegalStateException(s"a drawn nonce of ${raw.length} characters is not a nonce")
      )

  private def drawn(): String =
    val bytes = new Array[Byte](SecureRandomNonceSource.Bytes)

    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

object SecureRandomNonceSource:
  val Bytes: Int = 32

  def create: IO[SecureRandomNonceSource] =
    IO(SecureRandomNonceSource(SecureRandom()))
