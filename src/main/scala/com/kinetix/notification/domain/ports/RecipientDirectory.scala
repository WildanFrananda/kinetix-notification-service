package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.{NotificationError, PrincipalId, Recipient}

trait RecipientDirectory[F[_]]:
  def lookup(principal: PrincipalId): F[Either[NotificationError, Recipient]]
