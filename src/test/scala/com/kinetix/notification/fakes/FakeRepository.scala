package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.NotificationRepository

final class FakeRepository(
  stored: Ref[IO, Map[String, Notification]],
  val saves: Ref[IO, List[NotificationId]]
) extends NotificationRepository[IO]:
  def find(
    sender: Caller,
    id: NotificationId
  ): IO[Either[NotificationError, Option[Notification]]] =
    stored.get.map(_.get(id.value).filter(_.sender == sender).asRight)

  def findByIdempotencyKey(
    sender: Caller,
    key: IdempotencyKey
  ): IO[Either[NotificationError, Option[Notification]]] =
    stored.get.map(current => holding(current, sender, key).asRight)

  def save(notification: Notification): IO[Either[NotificationError, Insertion]] =
    stored
      .modify: current =>
        val taken = notification.idempotencyKey.flatMap(holding(current, notification.sender, _))

        taken match
          case Some(_) => (current, Insertion.KeyAlreadyUsed)
          case None    => (current + (notification.id.value -> notification), Insertion.Inserted)
      .flatTap:
        case Insertion.Inserted       => saves.update(_ :+ notification.id)
        case Insertion.KeyAlreadyUsed => IO.unit
      .map(_.asRight)

  def recordAttempt(
    id: NotificationId,
    attempt: DeliveryAttempt
  ): IO[Either[NotificationError, Unit]] =
    stored
      .update: current =>
        current.get(id.value) match
          case None           => current
          case Some(existing) =>
            val kept = existing.attempts.filterNot(_.channel == attempt.channel)
            current + (id.value -> existing.copy(attempts = kept :+ attempt))
      .as(().asRight)

  def contents: IO[List[Notification]] = stored.get.map(_.values.toList)

  private def holding(
    current: Map[String, Notification],
    sender: Caller,
    key: IdempotencyKey
  ): Option[Notification] =
    current.values.find(n => n.sender == sender && n.idempotencyKey.contains(key))

object FakeRepository:
  def empty: IO[FakeRepository] =
    for
      stored <- Ref.of[IO, Map[String, Notification]](Map.empty)
      saves <- Ref.of[IO, List[NotificationId]](Nil)
    yield FakeRepository(stored, saves)

  def holding(notification: Notification): IO[FakeRepository] =
    empty.flatTap(_.save(notification))
