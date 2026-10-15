package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.RecipientDirectory

final class FakeDirectory(
  answer: Either[NotificationError, Recipient],
  val asked: Ref[IO, List[PrincipalId]]
) extends RecipientDirectory[IO]:
  def lookup(principal: PrincipalId): IO[Either[NotificationError, Recipient]] =
    asked.update(_ :+ principal).as(answer)

object FakeDirectory:
  def holding(addresses: Address*): IO[FakeDirectory] =
    Ref
      .of[IO, List[PrincipalId]](Nil)
      .map: asked =>
        FakeDirectory(Recipient(Fixtures.recipient, addresses.toList).asRight, asked)

  def failing(error: NotificationError): IO[FakeDirectory] =
    Ref.of[IO, List[PrincipalId]](Nil).map(FakeDirectory(error.asLeft, _))
