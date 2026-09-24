package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.RecipientDirectory

final class CompositeRecipientDirectory(parts: List[RecipientDirectory[IO]])
  extends RecipientDirectory[IO]:

  def lookup(principal: PrincipalId): IO[Either[NotificationError, Recipient]] =
    parts
      .traverse(_.lookup(principal))
      .map: answers =>
        val addresses = answers.collect { case Right(found) => found.addresses }.flatten
        val failures = answers.collect { case Left(error) => error }

        if addresses.nonEmpty then Recipient(principal, addresses).asRight
        else
          failures.headOption match
            case Some(error) => error.asLeft
            case None        => Recipient(principal, Nil).asRight
