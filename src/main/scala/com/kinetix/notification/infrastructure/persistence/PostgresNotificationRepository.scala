package com.kinetix.notification.infrastructure.persistence

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.NotificationRepository

final class PostgresNotificationRepository(transactor: Transactor[IO])
  extends NotificationRepository[IO]:
  def find(id: NotificationId): IO[Either[NotificationError, Option[Notification]]] =
    val query =
      for
        header <- Queries.selectById(id.value).option
        attempts <- header
          .traverse(_ => Queries.selectAttempts(id.value).to[List])
          .map(_.getOrElse(Nil))
      yield header.map(Rows.toNotification(_, attempts))

    run(query)

  def findByIdempotencyKey(
    key: IdempotencyKey
  ): IO[Either[NotificationError, Option[Notification]]] =
    val query =
      for
        header <- Queries.selectByIdempotencyKey(key.value).option
        attempts <- header
          .traverse(row => Queries.selectAttempts(row.id).to[List])
          .map(_.getOrElse(Nil))
      yield header.map(Rows.toNotification(_, attempts))

    run(query)

  def save(notification: Notification): IO[Either[NotificationError, Unit]] =
    run(Queries.insert(notification).run.void)

  def recordAttempt(
    id: NotificationId,
    attempt: DeliveryAttempt
  ): IO[Either[NotificationError, Unit]] =
    run(Queries.upsertAttempt(id.value, attempt).run.void)

  private def run[A](query: ConnectionIO[A]): IO[Either[NotificationError, A]] =
    query
      .transact(transactor)
      .map(_.asRight[NotificationError])
      .handleError(throwable => NotificationError.StoreUnavailable(throwable.getMessage).asLeft[A])

private object Rows:
  final case class Header(
    id: String,
    recipientPrincipalId: String,
    template: String,
    params: String,
    idempotencyKey: Option[String],
    createdAt: Instant
  )

  final case class Attempt(
    channel: String,
    status: String,
    attempts: Int,
    lastError: Option[String],
    lastAttemptAt: Instant
  )

  def toNotification(header: Header, attempts: List[Attempt]): Notification =
    Notification(
      id = NotificationId.fromString(header.id).getOrElse(NotificationId.fromString("unknown").get),
      recipient = PrincipalId.unsafe(header.recipientPrincipalId),
      template = Codecs.templateFrom(header.template),
      params = Codecs.paramsFrom(header.params),
      idempotencyKey = header.idempotencyKey.flatMap(IdempotencyKey.fromString),
      attempts = attempts.map(toAttempt),
      createdAt = header.createdAt
    )

  private def toAttempt(row: Attempt): DeliveryAttempt =
    DeliveryAttempt(
      channel = Codecs.channelFrom(row.channel),
      status = Codecs.statusFrom(row.status),
      attempts = row.attempts,
      lastError = row.lastError,
      lastAttemptAt = row.lastAttemptAt
    )

private object Queries:
  import Rows.*

  def selectById(id: String): Query0[Header] =
    sql"""
      SELECT id, recipient_principal_id, template, params, idempotency_key, created_at
        FROM notifications
       WHERE id = $id
    """.query[Header]

  def selectByIdempotencyKey(key: String): Query0[Header] =
    sql"""
      SELECT id, recipient_principal_id, template, params, idempotency_key, created_at
        FROM notifications
       WHERE idempotency_key = $key
    """.query[Header]

  def selectAttempts(id: String): Query0[Attempt] =
    sql"""
      SELECT channel, status, attempts, last_error, last_attempt_at
        FROM notification_attempts
       WHERE notification_id = $id
       ORDER BY channel
    """.query[Attempt]

  def insert(notification: Notification): Update0 =
    sql"""
      INSERT INTO notifications
             (id, recipient_principal_id, template, params, idempotency_key, created_at)
      VALUES (${notification.id.value},
              ${notification.recipient.value},
              ${Codecs.templateTo(notification.template)},
              ${Codecs.paramsTo(notification.params)},
              ${notification.idempotencyKey.map(_.value)},
              ${notification.createdAt})
    """.update

  def upsertAttempt(id: String, attempt: DeliveryAttempt): Update0 =
    sql"""
      INSERT INTO notification_attempts
             (notification_id, channel, status, attempts, last_error, last_attempt_at)
      VALUES ($id,
              ${Codecs.channelTo(attempt.channel)},
              ${Codecs.statusTo(attempt.status)},
              ${attempt.attempts},
              ${attempt.lastError},
              ${attempt.lastAttemptAt})
      ON CONFLICT (notification_id, channel) DO UPDATE
         SET status = EXCLUDED.status,
             attempts = notification_attempts.attempts + EXCLUDED.attempts,
             last_error = EXCLUDED.last_error,
             last_attempt_at = EXCLUDED.last_attempt_at
    """.update
