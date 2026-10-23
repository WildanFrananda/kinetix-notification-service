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

  val backoff: Backoff = Backoff(List.iterate(initialDelay, maxAttempts - 1)(_ * factor.toLong))

  def retrying[A](
    operation: F[Either[NotificationError, A]]
  ): F[Either[NotificationError, A]] =
    attempt(operation, backoff.pauses)

  private def attempt[A](
    operation: F[Either[NotificationError, A]],
    pauses: List[FiniteDuration]
  ): F[Either[NotificationError, A]] =
    operation.flatMap:
      case right @ Right(_)   => right.pure[F].widen
      case left @ Left(error) =>
        pauses match
          case pause :: rest if error.isTransient =>
            Temporal[F].sleep(pause) *> attempt(operation, rest)
          case _ => left.pure[F].widen

object ExponentialBackoffRetry:
  def default[F[_]: Temporal]: ExponentialBackoffRetry[F] =
    new ExponentialBackoffRetry[F](maxAttempts = 3, initialDelay = 200.millis, factor = 3.0)
