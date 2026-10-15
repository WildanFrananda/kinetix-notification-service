package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.DeviceRegistry

final class FakeDeviceRegistry(
  tokens: Ref[IO, Map[String, (PrincipalId, DevicePlatform)]]
) extends DeviceRegistry[IO]:
  def tokensFor(principal: PrincipalId): IO[Either[NotificationError, List[DeviceToken]]] =
    tokens.get.map: current =>
      current
        .collect {
          case (token, (owner, _)) if owner.value == principal.value =>
            DeviceToken.fromString(token)
        }
        .flatten
        .toList
        .asRight

  def register(
    principal: PrincipalId,
    token: DeviceToken,
    platform: DevicePlatform
  ): IO[Either[NotificationError, Boolean]] =
    for
      current <- tokens.get
      alreadyOurs = current.get(token.value).exists(_._1.value == principal.value)
      _ <- tokens.update(_ + (token.value -> (principal, platform)))
    yield alreadyOurs.asRight

  def forget(token: DeviceToken): IO[Either[NotificationError, Unit]] =
    tokens.update(_ - token.value).as(().asRight)

  def forgetOwned(principal: PrincipalId, token: DeviceToken): IO[Either[NotificationError, Unit]] =
    tokens
      .update: current =>
        if current.get(token.value).exists(_._1.value == principal.value) then current - token.value
        else current
      .as(().asRight)

  def contents: IO[Map[String, (PrincipalId, DevicePlatform)]] = tokens.get

object FakeDeviceRegistry:
  def empty: IO[FakeDeviceRegistry] =
    Ref.of[IO, Map[String, (PrincipalId, DevicePlatform)]](Map.empty).map(FakeDeviceRegistry(_))
