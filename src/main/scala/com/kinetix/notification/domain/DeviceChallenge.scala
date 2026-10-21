package com.kinetix.notification.domain

import java.time.{Duration, Instant}

final case class DeviceChallenge(
  token: DeviceToken,
  claimant: PrincipalId,
  platform: DevicePlatform,
  expiresAt: Instant,
  failedAttempts: Int
):
  def judge(nonceMatches: Boolean, at: Instant): ChallengeVerdict =
    if !at.isBefore(expiresAt) then ChallengeVerdict.Expired
    else if failedAttempts >= DeviceChallenge.MaxFailedAttempts then ChallengeVerdict.Exhausted
    else if nonceMatches then ChallengeVerdict.Matched
    else ChallengeVerdict.Mismatched

  def attemptsLeftAfterAMiss: Int =
    (DeviceChallenge.MaxFailedAttempts - failedAttempts - 1).max(0)

object DeviceChallenge:
  val Lifetime: Duration = Duration.ofMinutes(10)
  val MaxFailedAttempts: Int = 5

  def issued(
    token: DeviceToken,
    claimant: PrincipalId,
    platform: DevicePlatform,
    at: Instant
  ): DeviceChallenge =
    DeviceChallenge(token, claimant, platform, at.plus(Lifetime), 0)
