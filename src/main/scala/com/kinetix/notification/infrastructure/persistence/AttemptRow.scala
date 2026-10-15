package com.kinetix.notification.infrastructure.persistence

import java.time.Instant

import com.kinetix.notification.domain.DeliveryAttempt

private[persistence] final case class AttemptRow(
  channel: String,
  status: String,
  attempts: Int,
  lastError: Option[String],
  lastAttemptAt: Instant
):
  def toAttempt: Either[String, DeliveryAttempt] =
    for
      readChannel <- Codecs.channelFrom(channel).toRight("channel")
      readStatus <- Codecs.statusFrom(status).toRight("status")
    yield DeliveryAttempt(readChannel, readStatus, attempts, lastError, lastAttemptAt)
