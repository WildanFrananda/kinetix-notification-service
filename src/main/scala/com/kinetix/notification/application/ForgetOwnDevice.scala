package com.kinetix.notification.application

import com.kinetix.notification.domain.{DeviceToken, NotificationError, PrincipalId}
import com.kinetix.notification.domain.ports.DeviceRegistry

final class ForgetOwnDevice[F[_]](registry: DeviceRegistry[F]):
  def apply(principal: PrincipalId, token: DeviceToken): F[Either[NotificationError, Unit]] =
    registry.forgetOwned(principal, token)
