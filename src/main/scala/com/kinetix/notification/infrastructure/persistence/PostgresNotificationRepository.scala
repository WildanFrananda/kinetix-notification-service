package com.kinetix.notification.infrastructure.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.Logger

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.NotificationRepository

final class PostgresNotificationRepository(transactor: Transactor[IO], logger: Logger[IO])
  extends NotificationRepository[IO]:
  def find(
    sender: Caller,
    id: NotificationId
  ): IO[Either[NotificationError, Option[Notification]]] =
    run(withAttempts(NotificationQueries.selectById(sender, id).option))

  def findByIdempotencyKey(
    sender: Caller,
    key: IdempotencyKey
  ): IO[Either[NotificationError, Option[Notification]]] =
    run(withAttempts(NotificationQueries.selectByIdempotencyKey(sender, key).option))

  def save(notification: Notification): IO[Either[NotificationError, Insertion]] =
    run(
      NotificationQueries
        .insert(notification)
        .run
        .map(inserted => if inserted == 1 then Insertion.Inserted else Insertion.KeyAlreadyUsed)
    )

  def recordAttempt(
    id: NotificationId,
    attempt: DeliveryAttempt
  ): IO[Either[NotificationError, Unit]] =
    run(NotificationQueries.upsertAttempt(id.value, attempt).run.void)

  private def withAttempts(
    header: ConnectionIO[Option[NotificationRow]]
  ): ConnectionIO[Option[Notification]] =
    header.flatMap:
      case None      => none[Notification].pure[ConnectionIO]
      case Some(row) =>
        NotificationQueries
          .selectAttempts(row.id)
          .to[List]
          .flatMap: attempts =>
            row
              .toNotification(attempts)
              .leftMap(IllegalStateException(_))
              .liftTo[ConnectionIO]
              .map(_.some)

  private def run[A](query: ConnectionIO[A]): IO[Either[NotificationError, A]] =
    query
      .transact(transactor)
      .map(_.asRight[NotificationError])
      .handleErrorWith: failure =>
        logger
          .error(failure)("the notifications table could not be read or written")
          .as(
            NotificationError
              .StoreUnavailable(Store.Deliveries, PostgresFailure.describe(failure))
              .asLeft[A]
          )
