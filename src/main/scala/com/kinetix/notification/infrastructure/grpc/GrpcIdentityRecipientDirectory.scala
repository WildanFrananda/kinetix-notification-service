package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO
import cats.syntax.all.*
import io.grpc.Metadata
import org.typelevel.log4cats.Logger

import identity.v1.identity.{GetUserProfileRequest, IdentityServiceFs2Grpc}

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.RecipientDirectory

final class GrpcIdentityRecipientDirectory(
  identity: IdentityServiceFs2Grpc[IO, Metadata],
  logger: Logger[IO]
) extends RecipientDirectory[IO]:

  def lookup(principal: PrincipalId): IO[Either[NotificationError, Recipient]] =
    identity
      .getUserProfile(GetUserProfileRequest(principalId = principal.value), new Metadata())
      .map: response =>
        if !response.found then NotificationError.RecipientUnknown(principal).asLeft
        else
          val addresses = EmailAddress.fromString(response.email).map(Address.Email.apply).toList
          Recipient(principal, addresses).asRight
      .handleErrorWith: throwable =>
        logger
          .warn(throwable)("identity could not be asked for a recipient's addresses")
          .as(NotificationError.DirectoryUnavailable(throwable.toString).asLeft)
