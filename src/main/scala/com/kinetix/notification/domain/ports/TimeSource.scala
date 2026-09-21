package com.kinetix.notification.domain.ports

import java.time.Instant

trait TimeSource[F[_]]:
  def now: F[Instant]
