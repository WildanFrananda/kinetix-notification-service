package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.NotificationError
import com.kinetix.notification.domain.ports.RetryPolicy

final class CountingRetry(maxAttempts: Int, val runs: Ref[IO, Int]) extends RetryPolicy[IO]:
  def retrying[A](operation: IO[Either[NotificationError, A]]): IO[Either[NotificationError, A]] =
    attempt(operation, maxAttempts - 1)

  private def attempt[A](
    operation: IO[Either[NotificationError, A]],
    remaining: Int
  ): IO[Either[NotificationError, A]] =
    runs.update(_ + 1) *> operation.flatMap:
      case right @ Right(_)   => right.pure[IO].widen
      case left @ Left(error) =>
        if remaining <= 0 || !error.isTransient then left.pure[IO].widen
        else attempt(operation, remaining - 1)

object CountingRetry:
  def upTo(maxAttempts: Int): IO[CountingRetry] =
    Ref.of[IO, Int](0).map(CountingRetry(maxAttempts, _))
