package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.NotificationSender

final class FakeSender(
  val channel: Channel,
  answers: Ref[IO, List[Either[NotificationError, Unit]]],
  val sent: Ref[IO, List[(Address, Message)]]
) extends NotificationSender[IO]:
  def send(to: Address, message: Message): IO[Either[NotificationError, Unit]] =
    for
      _ <- sent.update(_ :+ (to, message))
      remaining <- answers.get
      answer = remaining.headOption.getOrElse(().asRight)
      _ <- answers.update(_.drop(1))
    yield answer

object FakeSender:
  def working(channel: Channel): IO[FakeSender] =
    for
      answers <- Ref.of[IO, List[Either[NotificationError, Unit]]](Nil)
      sent <- Ref.of[IO, List[(Address, Message)]](Nil)
    yield FakeSender(channel, answers, sent)

  def answering(channel: Channel, answers: List[Either[NotificationError, Unit]]): IO[FakeSender] =
    for
      queue <- Ref.of[IO, List[Either[NotificationError, Unit]]](answers)
      sent <- Ref.of[IO, List[(Address, Message)]](Nil)
    yield FakeSender(channel, queue, sent)
