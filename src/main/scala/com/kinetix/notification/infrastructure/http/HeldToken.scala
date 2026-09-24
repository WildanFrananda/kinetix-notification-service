package com.kinetix.notification.infrastructure.http

import java.time.Instant

final case class HeldToken(value: String, expiresAt: Instant):
  def usableAt(now: Instant): Boolean =
    now.isBefore(expiresAt.minusSeconds(GoogleAccessTokens.Margin.toSeconds))
