package com.kinetix.notification.infrastructure.persistence

import java.time.Instant

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import com.kinetix.notification.domain.*

private[persistence] object DeviceQueries:
  def tokensFor(principal: PrincipalId): Query0[String] =
    sql"""
      SELECT device_token
        FROM notification_devices
       WHERE principal_id = ${principal.value}
       ORDER BY registered_at DESC
    """.query[String]

  def holderOf(token: DeviceToken): Query0[String] =
    sql"""
      SELECT principal_id
        FROM notification_devices
       WHERE device_token = ${token.value}
    """.query[String]

  def lockedHolderOf(token: DeviceToken): Query0[String] =
    sql"""
      SELECT principal_id
        FROM notification_devices
       WHERE device_token = ${token.value}
         FOR UPDATE
    """.query[String]

  def lockPrincipal(principal: PrincipalId): Query0[Int] =
    val key = s"notification_devices:${principal.value}"

    sql"SELECT 1 FROM pg_advisory_xact_lock(hashtextextended($key, 0))".query[Int]

  def insert(principal: PrincipalId, token: DeviceToken, platform: DevicePlatform): Update0 =
    sql"""
      INSERT INTO notification_devices (device_token, principal_id, platform, registered_at)
      VALUES (${token.value}, ${principal.value}, ${Codecs.platformTo(platform)}, NOW())
      ON CONFLICT (device_token) DO NOTHING
    """.update

  def refresh(principal: PrincipalId, token: DeviceToken, platform: DevicePlatform): Update0 =
    sql"""
      UPDATE notification_devices
         SET platform = ${Codecs.platformTo(platform)},
             registered_at = NOW()
       WHERE device_token = ${token.value}
         AND principal_id = ${principal.value}
    """.update

  def move(principal: PrincipalId, token: DeviceToken, platform: DevicePlatform): Update0 =
    sql"""
      INSERT INTO notification_devices (device_token, principal_id, platform, registered_at)
      VALUES (${token.value}, ${principal.value}, ${Codecs.platformTo(platform)}, NOW())
      ON CONFLICT (device_token) DO UPDATE
         SET principal_id = EXCLUDED.principal_id,
             platform = EXCLUDED.platform,
             registered_at = EXCLUDED.registered_at
    """.update

  def evictBeyond(principal: PrincipalId, limit: DeviceLimit): Update0 =
    sql"""
      DELETE FROM notification_devices
       WHERE principal_id = ${principal.value}
         AND device_token NOT IN (
               SELECT device_token
                 FROM notification_devices
                WHERE principal_id = ${principal.value}
                ORDER BY registered_at DESC, device_token
                LIMIT ${limit.value}
             )
    """.update

  def forget(token: DeviceToken): Update0 =
    sql"DELETE FROM notification_devices WHERE device_token = ${token.value}".update

  def forgetOwned(principal: PrincipalId, token: DeviceToken): Update0 =
    sql"""
      DELETE FROM notification_devices
       WHERE device_token = ${token.value}
         AND principal_id = ${principal.value}
    """.update

  def lockToken(token: DeviceToken): Query0[Int] =
    val key = s"notification_device_challenges:${token.value}"

    sql"SELECT 1 FROM pg_advisory_xact_lock(hashtextextended($key, 0))".query[Int]

  def dropExpiredChallenges(at: Instant): Update0 =
    sql"DELETE FROM notification_device_challenges WHERE expires_at <= $at".update

  def pendingUntil(claimant: PrincipalId, token: DeviceToken): Query0[Instant] =
    sql"""
      SELECT expires_at
        FROM notification_device_challenges
       WHERE device_token = ${token.value}
         AND claimant_principal_id = ${claimant.value}
    """.query[Instant]

  def issuedAfter(token: DeviceToken, since: Instant): Query0[Instant] =
    sql"""
      SELECT issued_at
        FROM notification_device_challenge_issues
       WHERE device_token = ${token.value}
         AND issued_at > $since
    """.query[Instant]

  def recordIssue(challenge: DeviceChallenge, digest: String, at: Instant): Update0 =
    sql"""
      INSERT INTO notification_device_challenge_issues
             (device_token, claimant_principal_id, nonce_digest, issued_at)
      VALUES (${challenge.token.value}, ${challenge.claimant.value}, $digest, $at)
    """.update

  def forgetIssue(token: DeviceToken, digest: String): Update0 =
    sql"""
      DELETE FROM notification_device_challenge_issues
       WHERE device_token = ${token.value}
         AND nonce_digest = $digest
    """.update

  def dropIssuesUpTo(since: Instant): Update0 =
    sql"""
      DELETE FROM notification_device_challenge_issues
       WHERE id IN (
               SELECT id
                 FROM notification_device_challenge_issues
                WHERE issued_at <= $since
                  FOR UPDATE SKIP LOCKED
             )
    """.update

  def releaseChallenge(challenge: DeviceChallenge, digest: String): Update0 =
    sql"""
      DELETE FROM notification_device_challenges
       WHERE device_token = ${challenge.token.value}
         AND claimant_principal_id = ${challenge.claimant.value}
         AND nonce_digest = $digest
    """.update

  def holdChallenge(challenge: DeviceChallenge, digest: String): Update0 =
    sql"""
      INSERT INTO notification_device_challenges
             (device_token, claimant_principal_id, platform, nonce_digest, expires_at, failed_attempts)
      VALUES (
        ${challenge.token.value},
        ${challenge.claimant.value},
        ${Codecs.platformTo(challenge.platform)},
        $digest,
        ${challenge.expiresAt},
        ${challenge.failedAttempts}
      )
      ON CONFLICT (device_token, claimant_principal_id) DO UPDATE
         SET platform = EXCLUDED.platform,
             nonce_digest = EXCLUDED.nonce_digest,
             expires_at = EXCLUDED.expires_at,
             failed_attempts = EXCLUDED.failed_attempts
    """.update

  def lockedChallenge(claimant: PrincipalId, token: DeviceToken): Query0[ChallengeRow] =
    sql"""
      SELECT platform, nonce_digest, expires_at, failed_attempts
        FROM notification_device_challenges
       WHERE device_token = ${token.value}
         AND claimant_principal_id = ${claimant.value}
         FOR UPDATE
    """.query[ChallengeRow]

  def countMiss(claimant: PrincipalId, token: DeviceToken): Update0 =
    sql"""
      UPDATE notification_device_challenges
         SET failed_attempts = failed_attempts + 1
       WHERE device_token = ${token.value}
         AND claimant_principal_id = ${claimant.value}
    """.update

  def dropChallenge(claimant: PrincipalId, token: DeviceToken): Update0 =
    sql"""
      DELETE FROM notification_device_challenges
       WHERE device_token = ${token.value}
         AND claimant_principal_id = ${claimant.value}
    """.update
