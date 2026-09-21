package com.kinetix.notification.domain

import java.time.Instant

final case class DeliveryAttempt(
    channel: Channel,
    status: DeliveryStatus,
    attempts: Int,
    lastError: Option[String],
    lastAttemptAt: Instant
)
