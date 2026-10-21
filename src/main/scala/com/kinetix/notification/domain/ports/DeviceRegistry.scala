package com.kinetix.notification.domain.ports

import java.time.Instant

import com.kinetix.notification.domain.*

trait DeviceRegistry[F[_]]:
  def tokensFor(principal: PrincipalId): F[Either[NotificationError, List[DeviceToken]]]

  def holderOf(token: DeviceToken): F[Either[NotificationError, Option[PrincipalId]]]

  def register(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform,
    limit: DeviceLimit
  ): F[Either[NotificationError, DeviceClaim]]

  def holdChallenge(
    challenge: DeviceChallenge,
    nonce: DeviceNonce
  ): F[Either[NotificationError, Unit]]

  def redeem(
    claimant: PrincipalId,
    token: DeviceToken,
    nonce: DeviceNonce,
    at: Instant,
    limit: DeviceLimit
  ): F[Either[NotificationError, Redemption]]

  def forget(token: DeviceToken): F[Either[NotificationError, Unit]]

  def forgetOwned(principal: PrincipalId, token: DeviceToken): F[Either[NotificationError, Unit]]
