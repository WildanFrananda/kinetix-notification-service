package com.kinetix.notification.application

import cats.Monad
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.{DeviceRegistry, TimeSource}

final class VerifyDevice[F[_]: Monad](
  registry: DeviceRegistry[F],
  time: TimeSource[F],
  limit: DeviceLimit
):
  def apply(
    claimant: PrincipalId,
    token: DeviceToken,
    nonce: DeviceNonce
  ): F[Either[NotificationError, Unit]] =
    time.now
      .flatMap(at => registry.redeem(claimant, token, nonce, at, limit))
      .map:
        case Left(error)                     => error.asLeft
        case Right(Redemption.Verified)      => ().asRight
        case Right(Redemption.NotPending)    => NotificationError.VerificationNotPending.asLeft
        case Right(Redemption.Refused(left)) => NotificationError.VerificationFailed(left).asLeft
