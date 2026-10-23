package com.kinetix.notification.infrastructure.retry

import scala.concurrent.duration.FiniteDuration

final case class Backoff(pauses: List[FiniteDuration]):
  def attempts: Int = pauses.length + 1
