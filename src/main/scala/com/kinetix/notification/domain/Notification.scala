package com.kinetix.notification.domain

import java.time.Instant

final case class Notification(
  id: NotificationId,
  sender: Caller,
  recipient: PrincipalId,
  template: Template,
  params: Map[String, String],
  idempotencyKey: Option[IdempotencyKey],
  attempts: List[DeliveryAttempt],
  createdAt: Instant
):
  def reached: Boolean = attempts.exists(_.status == DeliveryStatus.Sent)

  def attemptOn(channel: Channel): Option[DeliveryAttempt] =
    attempts.find(_.channel == channel)

  def answers(
    recipient: PrincipalId,
    template: Template,
    params: Map[String, String]
  ): Boolean =
    this.recipient == recipient && this.template == template && this.params == params
