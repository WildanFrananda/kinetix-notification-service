package com.kinetix.notification.application

import com.kinetix.notification.domain.*

final case class NotifyCommand(
  sender: Caller,
  recipient: PrincipalId,
  template: Template,
  params: Map[String, String],
  preferred: List[Channel],
  idempotencyKey: Option[IdempotencyKey]
)
