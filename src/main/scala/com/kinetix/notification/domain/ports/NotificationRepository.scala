package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.*

trait NotificationRepository[F[_]]:
  def find(id: NotificationId): F[Either[NotificationError, Option[Notification]]]

  def findByIdempotencyKey(key: IdempotencyKey): F[Either[NotificationError, Option[Notification]]]

  def save(notification: Notification): F[Either[NotificationError, Unit]]

  def recordAttempt(
    id: NotificationId,
    attempt: DeliveryAttempt
  ): F[Either[NotificationError, Unit]]
