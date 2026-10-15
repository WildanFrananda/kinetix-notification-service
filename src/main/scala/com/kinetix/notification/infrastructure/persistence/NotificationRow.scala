package com.kinetix.notification.infrastructure.persistence

import java.time.Instant

import cats.syntax.all.*

import com.kinetix.notification.domain.*

private[persistence] final case class NotificationRow(
  id: String,
  sender: String,
  recipientPrincipalId: String,
  template: String,
  params: String,
  idempotencyKey: Option[String],
  createdAt: Instant
):
  def toNotification(attempts: List[AttemptRow]): Either[String, Notification] =
    for
      notificationId <- NotificationId.fromString(id).toRight(unreadable("id"))
      caller <- Caller.fromString(sender).toRight(unreadable("sender"))
      recipient <- PrincipalId.fromString(recipientPrincipalId).toRight(unreadable("recipient"))
      kind <- Codecs.templateFrom(template).toRight(unreadable("template"))
      values <- Codecs.paramsFrom(params).toRight(unreadable("params"))
      key <- idempotencyKey.traverse(raw =>
        IdempotencyKey.fromString(raw).toRight(unreadable("idempotency_key"))
      )
      read <- attempts.traverse(_.toAttempt.leftMap(column => unreadable(s"attempt $column")))
    yield Notification(notificationId, caller, recipient, kind, values, key, read, createdAt)

  private def unreadable(column: String): String =
    s"notification '$id' has a $column this service cannot read"
