package com.kinetix.notification.domain.ports

import java.time.Instant

import com.kinetix.notification.domain.{DeviceNonce, DeviceToken, NotificationError}

trait DeviceChallengeSender[F[_]]:
  def sendChallenge(
    token: DeviceToken,
    nonce: DeviceNonce,
    expiresAt: Instant
  ): F[Either[NotificationError, Unit]]
