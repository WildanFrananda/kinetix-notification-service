package com.kinetix.notification.infrastructure.persistence

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.Logger

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.DeviceRegistry

final class PostgresDeviceRegistry(transactor: Transactor[IO], logger: Logger[IO])
  extends DeviceRegistry[IO]:
  def tokensFor(principal: PrincipalId): IO[Either[NotificationError, List[DeviceToken]]] =
    run(DeviceQueries.tokensFor(principal).to[List].flatMap(_.traverse(readToken)))

  def holderOf(token: DeviceToken): IO[Either[NotificationError, Option[PrincipalId]]] =
    run(DeviceQueries.holderOf(token).option.flatMap(_.traverse(readPrincipal)))

  def register(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform,
    limit: DeviceLimit
  ): IO[Either[NotificationError, DeviceClaim]] =
    val claim =
      for
        _ <- DeviceQueries.lockPrincipal(principal).unique
        holder <- DeviceQueries.lockedHolderOf(token).option
        claimed <- holder match
          case None                                  => add(principal, token, platform, limit)
          case Some(held) if held == principal.value =>
            DeviceQueries.refresh(principal, token, platform).run.as(DeviceClaim.Refreshed)
          case Some(_) => DeviceClaim.HeldByAnother.pure[ConnectionIO]
      yield claimed

    run(claim)

  def holdChallenge(
    challenge: DeviceChallenge,
    nonce: DeviceNonce,
    at: Instant
  ): IO[Either[NotificationError, ChallengeHold]] =
    val since = at.minus(DeviceChallenge.IssueWindow)
    val hold =
      for
        _ <- DeviceQueries.lockToken(challenge.token).unique
        _ <- DeviceQueries.dropExpiredChallenges(at).run
        _ <- DeviceQueries.dropIssuesUpTo(since).run
        pending <- DeviceQueries.pendingUntil(challenge.claimant, challenge.token).option
        issued <- DeviceQueries.issuedAfter(challenge.token, since).to[List]
        verdict = DeviceChallenge.mayHold(pending, issued, at)
        _ <- verdict match
          case ChallengeHold.Held => keep(challenge, NonceDigest.of(nonce), at)
          case ChallengeHold.AlreadyPending(_) | ChallengeHold.LimitReached(_) =>
            ().pure[ConnectionIO]
      yield verdict

    run(hold)

  def releaseChallenge(
    challenge: DeviceChallenge,
    nonce: DeviceNonce
  ): IO[Either[NotificationError, Unit]] =
    val digest = NonceDigest.of(nonce)
    val release =
      for
        _ <- DeviceQueries.releaseChallenge(challenge, digest).run
        _ <- DeviceQueries.forgetIssue(challenge.token, digest).run
      yield ()

    run(release)

  def redeem(
    claimant: PrincipalId,
    token: DeviceToken,
    nonce: DeviceNonce,
    at: Instant,
    limit: DeviceLimit
  ): IO[Either[NotificationError, Redemption]] =
    val redemption =
      DeviceQueries
        .lockedChallenge(claimant, token)
        .option
        .flatMap:
          case None      => Redemption.NotPending.pure[ConnectionIO]
          case Some(row) =>
            row
              .toChallenge(token, claimant)
              .leftMap(IllegalStateException(_))
              .liftTo[ConnectionIO]
              .flatMap(challenge =>
                settle(challenge, NonceDigest.matches(row.nonceDigest, nonce), at, limit)
              )

    run(redemption)

  def forget(token: DeviceToken): IO[Either[NotificationError, Unit]] =
    run(DeviceQueries.forget(token).run.void)

  def forgetOwned(principal: PrincipalId, token: DeviceToken): IO[Either[NotificationError, Unit]] =
    run(DeviceQueries.forgetOwned(principal, token).run.void)

  private def add(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform,
    limit: DeviceLimit
  ): ConnectionIO[DeviceClaim] =
    DeviceQueries
      .insert(principal, token, platform)
      .run
      .flatMap:
        case 1 => DeviceQueries.evictBeyond(principal, limit).run.as(DeviceClaim.Added)
        case _ => DeviceClaim.HeldByAnother.pure[ConnectionIO]

  private def keep(challenge: DeviceChallenge, digest: String, at: Instant): ConnectionIO[Unit] =
    for
      _ <- DeviceQueries.holdChallenge(challenge, digest).run
      _ <- DeviceQueries.recordIssue(challenge, digest, at).run
    yield ()

  private def settle(
    challenge: DeviceChallenge,
    nonceMatches: Boolean,
    at: Instant,
    limit: DeviceLimit
  ): ConnectionIO[Redemption] =
    val claimant = challenge.claimant
    val token = challenge.token

    challenge.judge(nonceMatches, at) match
      case ChallengeVerdict.Matched =>
        for
          _ <- DeviceQueries.dropChallenge(claimant, token).run
          _ <- DeviceQueries.lockPrincipal(claimant).unique
          _ <- DeviceQueries.move(claimant, token, challenge.platform).run
          _ <- DeviceQueries.evictBeyond(claimant, limit).run
        yield Redemption.Verified

      case ChallengeVerdict.Mismatched =>
        val left = challenge.attemptsLeftAfterAMiss
        val counted =
          if left == 0 then DeviceQueries.dropChallenge(claimant, token)
          else DeviceQueries.countMiss(claimant, token)

        counted.run.as(Redemption.Refused(left))

      case ChallengeVerdict.Expired | ChallengeVerdict.Exhausted =>
        DeviceQueries.dropChallenge(claimant, token).run.as(Redemption.NotPending)

  private def readToken(raw: String): ConnectionIO[DeviceToken] =
    DeviceToken
      .fromString(raw)
      .toRight(IllegalStateException("a stored device token is empty or too long"))
      .liftTo[ConnectionIO]

  private def readPrincipal(raw: String): ConnectionIO[PrincipalId] =
    PrincipalId
      .fromString(raw)
      .toRight(IllegalStateException("a stored device holder is empty or too long"))
      .liftTo[ConnectionIO]

  private def run[A](query: ConnectionIO[A]): IO[Either[NotificationError, A]] =
    query
      .transact(transactor)
      .map(_.asRight[NotificationError])
      .handleErrorWith: failure =>
        logger
          .error(failure)("the device registry could not be read or written")
          .as(
            NotificationError
              .StoreUnavailable(Store.Devices, PostgresFailure.describe(failure))
              .asLeft[A]
          )
