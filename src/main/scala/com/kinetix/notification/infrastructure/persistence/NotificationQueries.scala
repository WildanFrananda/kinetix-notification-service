package com.kinetix.notification.infrastructure.persistence

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import com.kinetix.notification.domain.*

private[persistence] object NotificationQueries:
  def selectById(sender: Caller, id: NotificationId): Query0[NotificationRow] =
    sql"""
      SELECT id, sender, recipient_principal_id, template, params, idempotency_key, created_at
        FROM notifications
       WHERE id = ${id.value}
         AND sender = ${sender.value}
    """.query[NotificationRow]

  def selectByIdempotencyKey(sender: Caller, key: IdempotencyKey): Query0[NotificationRow] =
    sql"""
      SELECT id, sender, recipient_principal_id, template, params, idempotency_key, created_at
        FROM notifications
       WHERE sender = ${sender.value}
         AND idempotency_key = ${key.value}
    """.query[NotificationRow]

  def selectAttempts(id: String): Query0[AttemptRow] =
    sql"""
      SELECT channel, status, attempts, last_error, last_attempt_at
        FROM notification_attempts
       WHERE notification_id = $id
       ORDER BY channel
    """.query[AttemptRow]

  def insert(notification: Notification): Update0 =
    sql"""
      INSERT INTO notifications
             (id, sender, recipient_principal_id, template, params, idempotency_key, created_at)
      VALUES (
        ${notification.id.value},
        ${notification.sender.value},
        ${notification.recipient.value},
        ${Codecs.templateTo(notification.template)},
        ${Codecs.paramsTo(notification.params)}::jsonb,
        ${notification.idempotencyKey.map(_.value)},
        ${notification.createdAt}
      )
      ON CONFLICT (sender, idempotency_key) DO NOTHING
    """.update

  def upsertAttempt(id: String, attempt: DeliveryAttempt): Update0 =
    sql"""
      INSERT INTO notification_attempts
             (notification_id, channel, status, attempts, last_error, last_attempt_at)
      VALUES (
        $id,
        ${Codecs.channelTo(attempt.channel)},
        ${Codecs.statusTo(attempt.status)},
        ${attempt.attempts},
        ${attempt.lastError},
        ${attempt.lastAttemptAt}
      )
      ON CONFLICT (notification_id, channel) DO UPDATE
         SET status = EXCLUDED.status,
             attempts = notification_attempts.attempts + EXCLUDED.attempts,
             last_error = EXCLUDED.last_error,
             last_attempt_at = EXCLUDED.last_attempt_at
    """.update
