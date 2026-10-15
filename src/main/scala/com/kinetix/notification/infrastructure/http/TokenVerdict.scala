package com.kinetix.notification.infrastructure.http

import com.kinetix.notification.domain.PrincipalId

enum TokenVerdict:
  case Valid(principal: PrincipalId)
  case Invalid(reason: String)
  case Unverifiable(cause: String)
