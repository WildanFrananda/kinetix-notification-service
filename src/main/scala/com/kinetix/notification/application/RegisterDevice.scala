package com.kinetix.notification.application

import cats.Monad

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.DeviceRegistry

final class RegisterDevice[F[_]: Monad](registry: DeviceRegistry[F]):
  def apply(
      principal: PrincipalId,
      token: DeviceToken,
      platform: DevicePlatform
  ): F[Either[NotificationError, Boolean]] =
    registry.register(principal, token, platform)
