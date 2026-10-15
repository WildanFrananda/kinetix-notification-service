package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.NotificationRepository

final class RacingRepository(winner: Notification, landed: Ref[IO, Boolean])
  extends NotificationRepository[IO]:
  def find(
    sender: Caller,
    id: NotificationId
  ): IO[Either[NotificationError, Option[Notification]]] =
    visible.map(_.filter(n => n.sender == sender && n.id == id).asRight)

  def findByIdempotencyKey(
    sender: Caller,
    key: IdempotencyKey
  ): IO[Either[NotificationError, Option[Notification]]] =
    visible.map(_.filter(n => n.sender == sender && n.idempotencyKey.contains(key)).asRight)

  def save(notification: Notification): IO[Either[NotificationError, Insertion]] =
    landed.set(true).as(Insertion.KeyAlreadyUsed.asRight)

  def recordAttempt(
    id: NotificationId,
    attempt: DeliveryAttempt
  ): IO[Either[NotificationError, Unit]] =
    IO.pure(().asRight)

  private def visible: IO[Option[Notification]] =
    landed.get.map(Option.when(_)(winner))

object RacingRepository:
  def losingTo(winner: Notification): IO[RacingRepository] =
    Ref.of[IO, Boolean](false).map(RacingRepository(winner, _))
