package com.kinetix.notification.application

import cats.Monad
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.*

final class RegisterDevice[F[_]: Monad](
  registry: DeviceRegistry[F],
  challenges: DeviceChallengeSender[F],
  nonces: NonceSource[F],
  retry: RetryPolicy[F],
  time: TimeSource[F],
  limit: DeviceLimit
):
  def signedIn(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform
  ): F[Either[NotificationError, Registration]] =
    registry
      .register(principal, token, platform, limit)
      .flatMap:
        case Left(error)                      => refuse(error)
        case Right(DeviceClaim.Added)         => registered(alreadyKnown = false)
        case Right(DeviceClaim.Refreshed)     => registered(alreadyKnown = true)
        case Right(DeviceClaim.HeldByAnother) => challenge(principal, token, platform)

  def vouchedFor(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform
  ): F[Either[NotificationError, Registration]] =
    registry
      .holderOf(token)
      .flatMap:
        case Left(error)                                => refuse(error)
        case Right(Some(holder)) if holder == principal => signedIn(principal, token, platform)
        case Right(_)                                   => challenge(principal, token, platform)

  private def challenge(
    claimant: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform
  ): F[Either[NotificationError, Registration]] =
    for
      nonce <- nonces.next
      at <- time.now
      pending = DeviceChallenge.issued(token, claimant, platform, at)
      held <- registry.holdChallenge(pending, nonce, at)
      sent <- held match
        case Left(error)                                => error.asLeft[Unit].pure[F]
        case Right(ChallengeHold.Held)                  => delivered(pending, nonce)
        case Right(ChallengeHold.AlreadyPending(until)) =>
          NotificationError.VerificationAlreadyPending(until).asLeft[Unit].pure[F]
        case Right(ChallengeHold.LimitReached(retryAt)) =>
          NotificationError.VerificationLimitReached(retryAt).asLeft[Unit].pure[F]
    yield sent.as(Registration.VerificationPending(pending.expiresAt))

  private def delivered(
    pending: DeviceChallenge,
    nonce: DeviceNonce
  ): F[Either[NotificationError, Unit]] =
    retry
      .retrying(challenges.sendChallenge(pending.token, nonce, pending.expiresAt))
      .flatTap:
        case Right(_)                                  => ().pure[F]
        case Left(NotificationError.AddressGone(_, _)) =>
          registry.releaseChallenge(pending, nonce) *> registry.forget(pending.token).void
        case Left(_) => registry.releaseChallenge(pending, nonce).void

  private def registered(alreadyKnown: Boolean): F[Either[NotificationError, Registration]] =
    Registration.Registered(alreadyKnown).asRight[NotificationError].pure[F]

  private def refuse(error: NotificationError): F[Either[NotificationError, Registration]] =
    error.asLeft[Registration].pure[F]
