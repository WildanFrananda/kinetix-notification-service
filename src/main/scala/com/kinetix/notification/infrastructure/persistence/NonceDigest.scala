package com.kinetix.notification.infrastructure.persistence

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

import com.kinetix.notification.domain.DeviceNonce

private[persistence] object NonceDigest:
  def of(nonce: DeviceNonce): String =
    HexFormat.of().formatHex(sha256(nonce))

  def matches(stored: String, presented: DeviceNonce): Boolean =
    MessageDigest.isEqual(
      stored.trim.getBytes(StandardCharsets.US_ASCII),
      of(presented).getBytes(StandardCharsets.US_ASCII)
    )

  private def sha256(nonce: DeviceNonce): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(nonce.value.getBytes(StandardCharsets.UTF_8))
