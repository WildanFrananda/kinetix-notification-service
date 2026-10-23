package com.kinetix.notification.domain

import java.time.Instant

enum ChallengeHold:
  case Held
  case AlreadyPending(until: Instant)
  case LimitReached(retryAt: Instant)
