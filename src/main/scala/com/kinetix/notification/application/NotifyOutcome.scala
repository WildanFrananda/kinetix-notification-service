package com.kinetix.notification.application

import com.kinetix.notification.domain.{DeliveryAttempt, NotificationId}

final case class NotifyOutcome(
    id: NotificationId,
    alreadyAccepted: Boolean,
    attempts: List[DeliveryAttempt]
):
  def reached: Boolean = attempts.exists(_.status == com.kinetix.notification.domain.DeliveryStatus.Sent)
