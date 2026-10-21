package com.kinetix.notification.fakes

import java.time.Instant

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.DeviceRegistry

final class FakeDeviceRegistry(
  devices: Ref[IO, Map[String, (PrincipalId, DevicePlatform, Long)]],
  challenges: Ref[IO, Map[(String, String), (DeviceChallenge, DeviceNonce)]],
  clock: Ref[IO, Long]
) extends DeviceRegistry[IO]:
  def tokensFor(principal: PrincipalId): IO[Either[NotificationError, List[DeviceToken]]] =
    devices.get.map: current =>
      current.toList
        .collect { case (token, (owner, _, at)) if owner == principal => (token, at) }
        .sortBy(-_._2)
        .flatMap((token, _) => DeviceToken.fromString(token))
        .asRight

  def holderOf(token: DeviceToken): IO[Either[NotificationError, Option[PrincipalId]]] =
    devices.get.map(_.get(token.value).map(_._1).asRight)

  def register(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform,
    limit: DeviceLimit
  ): IO[Either[NotificationError, DeviceClaim]] =
    clock
      .updateAndGet(_ + 1)
      .flatMap: at =>
        devices.modify: current =>
          current.get(token.value) match
            case None =>
              (
                within(current + (token.value -> (principal, platform, at)), principal, limit),
                DeviceClaim.Added
              )
            case Some((holder, _, _)) if holder == principal =>
              (current + (token.value -> (principal, platform, at)), DeviceClaim.Refreshed)
            case Some(_) => (current, DeviceClaim.HeldByAnother)
      .map(_.asRight)

  def holdChallenge(
    challenge: DeviceChallenge,
    nonce: DeviceNonce
  ): IO[Either[NotificationError, Unit]] =
    challenges
      .update(_ + ((challenge.token.value, challenge.claimant.value) -> (challenge, nonce)))
      .as(().asRight)

  def redeem(
    claimant: PrincipalId,
    token: DeviceToken,
    nonce: DeviceNonce,
    at: Instant,
    limit: DeviceLimit
  ): IO[Either[NotificationError, Redemption]] =
    val key = (token.value, claimant.value)

    challenges.get
      .map(_.get(key))
      .flatMap:
        case None                        => IO.pure(Redemption.NotPending)
        case Some((challenge, expected)) =>
          challenge.judge(expected.value == nonce.value, at) match
            case ChallengeVerdict.Matched =>
              for
                _ <- challenges.update(_ - key)
                stamp <- clock.updateAndGet(_ + 1)
                _ <- devices.update: current =>
                  within(
                    current + (token.value -> (claimant, challenge.platform, stamp)),
                    claimant,
                    limit
                  )
              yield Redemption.Verified
            case ChallengeVerdict.Mismatched =>
              val left = challenge.attemptsLeftAfterAMiss
              val missed = challenge.copy(failedAttempts = challenge.failedAttempts + 1)

              challenges
                .update(current =>
                  if left == 0 then current - key else current + (key -> (missed, expected))
                )
                .as(Redemption.Refused(left))
            case ChallengeVerdict.Expired | ChallengeVerdict.Exhausted =>
              challenges.update(_ - key).as(Redemption.NotPending)
      .map(_.asRight)

  def forget(token: DeviceToken): IO[Either[NotificationError, Unit]] =
    devices.update(_ - token.value).as(().asRight)

  def forgetOwned(principal: PrincipalId, token: DeviceToken): IO[Either[NotificationError, Unit]] =
    devices
      .update: current =>
        if current.get(token.value).exists(_._1 == principal) then current - token.value
        else current
      .as(().asRight)

  def contents: IO[Map[String, (PrincipalId, DevicePlatform)]] =
    devices.get.map(_.view.mapValues((owner, platform, _) => (owner, platform)).toMap)

  def pending: IO[List[DeviceChallenge]] = challenges.get.map(_.values.map(_._1).toList)

  private def within(
    current: Map[String, (PrincipalId, DevicePlatform, Long)],
    principal: PrincipalId,
    limit: DeviceLimit
  ): Map[String, (PrincipalId, DevicePlatform, Long)] =
    val kept = current.toList
      .collect { case (token, (owner, _, at)) if owner == principal => (token, at) }
      .sortBy(-_._2)
      .take(limit.value)
      .map(_._1)
      .toSet

    current.filter((token, entry) => entry._1 != principal || kept.contains(token))

object FakeDeviceRegistry:
  def fresh(): FakeDeviceRegistry =
    FakeDeviceRegistry(Ref.unsafe(Map.empty), Ref.unsafe(Map.empty), Ref.unsafe(0L))

  def empty: IO[FakeDeviceRegistry] =
    for
      devices <- Ref.of[IO, Map[String, (PrincipalId, DevicePlatform, Long)]](Map.empty)
      challenges <- Ref.of[IO, Map[(String, String), (DeviceChallenge, DeviceNonce)]](Map.empty)
      clock <- Ref.of[IO, Long](0L)
    yield FakeDeviceRegistry(devices, challenges, clock)
