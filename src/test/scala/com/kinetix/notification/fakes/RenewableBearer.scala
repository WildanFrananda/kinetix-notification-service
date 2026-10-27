package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.infrastructure.http.BearerTokens

final class RenewableBearer(issued: Ref[IO, Int], val discarded: Ref[IO, List[String]])
  extends BearerTokens:
  def token: IO[String] = issued.get.map(RenewableBearer.named)

  def discard(refused: String): IO[Unit] =
    discarded.update(_ :+ refused) *>
      issued.update(n => if RenewableBearer.named(n) == refused then n + 1 else n)

object RenewableBearer:
  def named(issue: Int): String = s"token-$issue"

  def start: IO[RenewableBearer] =
    (Ref.of[IO, Int](1), Ref.of[IO, List[String]](Nil)).mapN(RenewableBearer(_, _))
