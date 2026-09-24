package com.kinetix.notification.infrastructure.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.DeviceRegistry

final class PostgresDeviceRegistry(transactor: Transactor[IO]) extends DeviceRegistry[IO]:
  def tokensFor(principal: PrincipalId): IO[Either[NotificationError, List[DeviceToken]]] =
    val query =
      sql"""
        SELECT device_token
          FROM notification_devices
         WHERE principal_id = ${principal.value}
         ORDER BY registered_at DESC
      """.query[String].to[List]

    run(query).map(_.map(_.flatMap(DeviceToken.fromString)))

  def register(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform
  ): IO[Either[NotificationError, Boolean]] =
    val query =
      for
        existing <-
          sql"""
            SELECT principal_id
              FROM notification_devices
             WHERE device_token = ${token.value}
          """.query[String].option
        _ <-
          sql"""
            INSERT INTO notification_devices (device_token, principal_id, platform, registered_at)
            VALUES (${token.value},
                    ${principal.value},
                    ${Codecs.platformTo(platform)},
                    NOW())
            ON CONFLICT (device_token) DO UPDATE
               SET principal_id = EXCLUDED.principal_id,
                   platform = EXCLUDED.platform,
                   registered_at = EXCLUDED.registered_at
          """.update.run
      yield existing.contains(principal.value)

    run(query)

  def forget(token: DeviceToken): IO[Either[NotificationError, Unit]] =
    run(sql"DELETE FROM notification_devices WHERE device_token = ${token.value}".update.run.void)

  private def run[A](query: ConnectionIO[A]): IO[Either[NotificationError, A]] =
    query
      .transact(transactor)
      .map(_.asRight[NotificationError])
      .handleError(throwable => NotificationError.StoreUnavailable(throwable.getMessage).asLeft[A])
