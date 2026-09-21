package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.NotificationId

trait NotificationIdSource[F[_]]:
  def next: F[NotificationId]
