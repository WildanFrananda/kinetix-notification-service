package com.kinetix.notification.application

import com.kinetix.notification.domain.{DeviceToken, NotificationError}
import com.kinetix.notification.domain.ports.DeviceRegistry

final class ForgetDevice[F[_]](registry: DeviceRegistry[F]):
  def apply(token: DeviceToken): F[Either[NotificationError, Unit]] =
    registry.forget(token)
