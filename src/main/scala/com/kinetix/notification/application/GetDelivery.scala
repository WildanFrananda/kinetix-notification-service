package com.kinetix.notification.application

import com.kinetix.notification.domain.{Caller, Notification, NotificationError, NotificationId}
import com.kinetix.notification.domain.ports.NotificationRepository

final class GetDelivery[F[_]](repository: NotificationRepository[F]):
  def apply(
    sender: Caller,
    id: NotificationId
  ): F[Either[NotificationError, Option[Notification]]] =
    repository.find(sender, id)
