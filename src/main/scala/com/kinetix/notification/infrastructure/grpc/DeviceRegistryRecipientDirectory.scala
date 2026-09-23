package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.{DeviceRegistry, RecipientDirectory}

final class DeviceRegistryRecipientDirectory(registry: DeviceRegistry[IO])
  extends RecipientDirectory[IO]:

  def lookup(principal: PrincipalId): IO[Either[NotificationError, Recipient]] =
    registry
      .tokensFor(principal)
      .map(_.map(tokens => Recipient(principal, tokens.map(Address.Push.apply))))
