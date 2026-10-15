package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.*

trait NotificationRepository[F[_]]:
  def find(sender: Caller, id: NotificationId): F[Either[NotificationError, Option[Notification]]]

  def findByIdempotencyKey(
    sender: Caller,
    key: IdempotencyKey
  ): F[Either[NotificationError, Option[Notification]]]

  def save(notification: Notification): F[Either[NotificationError, Insertion]]

  def recordAttempt(
    id: NotificationId,
    attempt: DeliveryAttempt
  ): F[Either[NotificationError, Unit]]
