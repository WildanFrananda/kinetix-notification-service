package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.{Address, Channel, Message, NotificationError}

trait NotificationSender[F[_]]:
  def channel: Channel

  def send(to: Address, message: Message): F[Either[NotificationError, Unit]]
