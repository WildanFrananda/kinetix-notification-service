package com.kinetix.notification.application

import java.time.Instant

enum Registration:
  case Registered(alreadyKnown: Boolean)
  case VerificationPending(expiresAt: Instant)
