package com.kinetix.notification.fakes

import java.time.Instant

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.{DeviceNonce, DeviceToken, NotificationError}
import com.kinetix.notification.domain.ports.DeviceChallengeSender

final class FakeChallengeSender(
  answers: Ref[IO, List[Either[NotificationError, Unit]]],
  val sent: Ref[IO, List[(DeviceToken, DeviceNonce, Instant)]]
) extends DeviceChallengeSender[IO]:
  def sendChallenge(
    token: DeviceToken,
    nonce: DeviceNonce,
    expiresAt: Instant
  ): IO[Either[NotificationError, Unit]] =
    for
      _ <- sent.update(_ :+ (token, nonce, expiresAt))
      answer <- answers.modify(remaining => (remaining.drop(1), remaining.headOption))
    yield answer.getOrElse(().asRight)

object FakeChallengeSender:
  def working: IO[FakeChallengeSender] = answering(Nil)

  def answering(answers: List[Either[NotificationError, Unit]]): IO[FakeChallengeSender] =
    for
      queue <- Ref.of[IO, List[Either[NotificationError, Unit]]](answers)
      sent <- Ref.of[IO, List[(DeviceToken, DeviceNonce, Instant)]](Nil)
    yield FakeChallengeSender(queue, sent)
