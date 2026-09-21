package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.*

trait DeviceRegistry[F[_]]:
  def tokensFor(principal: PrincipalId): F[Either[NotificationError, List[DeviceToken]]]

  def register(
      principal: PrincipalId,
      token: DeviceToken,
      platform: DevicePlatform
  ): F[Either[NotificationError, Boolean]]

  def forget(token: DeviceToken): F[Either[NotificationError, Unit]]
