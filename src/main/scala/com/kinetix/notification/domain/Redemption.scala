package com.kinetix.notification.domain

enum Redemption:
  case Verified
  case NotPending
  case Refused(attemptsLeft: Int)
