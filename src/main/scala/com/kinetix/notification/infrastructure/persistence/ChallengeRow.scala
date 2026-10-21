package com.kinetix.notification.infrastructure.persistence

import java.time.Instant

import com.kinetix.notification.domain.{DeviceChallenge, DeviceToken, PrincipalId}

private[persistence] final case class ChallengeRow(
  platform: String,
  nonceDigest: String,
  expiresAt: Instant,
  failedAttempts: Int
):
  def toChallenge(token: DeviceToken, claimant: PrincipalId): Either[String, DeviceChallenge] =
    Codecs
      .platformFrom(platform)
      .toRight(s"a device challenge names the platform '$platform'")
      .map(DeviceChallenge(token, claimant, _, expiresAt, failedAttempts))
