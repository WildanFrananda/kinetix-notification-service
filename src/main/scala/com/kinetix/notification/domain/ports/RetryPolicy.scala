package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.NotificationError

trait RetryPolicy[F[_]]:
  def retrying[A](
      operation: F[Either[NotificationError, A]]
  ): F[Either[NotificationError, A]]
