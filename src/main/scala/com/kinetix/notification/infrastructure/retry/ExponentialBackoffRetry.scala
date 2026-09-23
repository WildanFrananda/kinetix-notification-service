package com.kinetix.notification.infrastructure.retry

import scala.concurrent.duration.*

import cats.effect.Temporal
import cats.syntax.all.*

import com.kinetix.notification.domain.NotificationError
import com.kinetix.notification.domain.ports.RetryPolicy

final class ExponentialBackoffRetry[F[_]: Temporal](
  maxAttempts: Int,
  initialDelay: FiniteDuration,
  factor: Double
) extends RetryPolicy[F]:
  require(maxAttempts >= 1, "a retry policy that never attempts anything is not a policy")

  def retrying[A](
    operation: F[Either[NotificationError, A]]
  ): F[Either[NotificationError, A]] =
    attempt(operation, remaining = maxAttempts - 1, delay = initialDelay)

  private def attempt[A](
    operation: F[Either[NotificationError, A]],
    remaining: Int,
    delay: FiniteDuration
  ): F[Either[NotificationError, A]] =
    operation.flatMap:
      case right @ Right(_)   => right.pure[F].widen
      case left @ Left(error) =>
        if remaining <= 0 || !error.isTransient then left.pure[F].widen
        else
          Temporal[F].sleep(delay) *>
            attempt(operation, remaining - 1, delay * factor.toLong)

object ExponentialBackoffRetry:
  def default[F[_]: Temporal]: RetryPolicy[F] =
    new ExponentialBackoffRetry[F](maxAttempts = 3, initialDelay = 200.millis, factor = 3.0)
