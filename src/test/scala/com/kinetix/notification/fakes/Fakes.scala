package com.kinetix.notification.fakes

import java.time.Instant

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.*

final class FakeDirectory(
  answer: Either[NotificationError, Recipient],
  val asked: Ref[IO, List[PrincipalId]]
) extends RecipientDirectory[IO]:
  def lookup(principal: PrincipalId): IO[Either[NotificationError, Recipient]] =
    asked.update(_ :+ principal).as(answer)

object FakeDirectory:
  def holding(addresses: Address*): IO[FakeDirectory] =
    Ref
      .of[IO, List[PrincipalId]](Nil)
      .map: asked =>
        FakeDirectory(Recipient(Fixtures.recipient, addresses.toList).asRight, asked)

  def failing(error: NotificationError): IO[FakeDirectory] =
    Ref.of[IO, List[PrincipalId]](Nil).map(FakeDirectory(error.asLeft, _))

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

final class FakeRepository(
  stored: Ref[IO, Map[String, Notification]],
  val saves: Ref[IO, List[NotificationId]]
) extends NotificationRepository[IO]:
  def find(id: NotificationId): IO[Either[NotificationError, Option[Notification]]] =
    stored.get.map(_.get(id.value).asRight)

  def findByIdempotencyKey(
    key: IdempotencyKey
  ): IO[Either[NotificationError, Option[Notification]]] =
    stored.get.map(_.values.find(_.idempotencyKey.exists(_.value == key.value)).asRight)

  def save(notification: Notification): IO[Either[NotificationError, Unit]] =
    stored.update(_ + (notification.id.value -> notification)) *>
      saves.update(_ :+ notification.id).as(().asRight)

  def recordAttempt(
    id: NotificationId,
    attempt: DeliveryAttempt
  ): IO[Either[NotificationError, Unit]] =
    stored
      .update: current =>
        current.get(id.value) match
          case None           => current
          case Some(existing) =>
            val kept = existing.attempts.filterNot(_.channel == attempt.channel)
            current + (id.value -> existing.copy(attempts = kept :+ attempt))
      .as(().asRight)

  def contents: IO[List[Notification]] = stored.get.map(_.values.toList)

object FakeRepository:
  def empty: IO[FakeRepository] =
    for
      stored <- Ref.of[IO, Map[String, Notification]](Map.empty)
      saves <- Ref.of[IO, List[NotificationId]](Nil)
    yield FakeRepository(stored, saves)

  def holding(notification: Notification): IO[FakeRepository] =
    empty.flatTap(_.save(notification))

final class CountingRetry(maxAttempts: Int, val runs: Ref[IO, Int]) extends RetryPolicy[IO]:
  def retrying[A](operation: IO[Either[NotificationError, A]]): IO[Either[NotificationError, A]] =
    attempt(operation, maxAttempts - 1)

  private def attempt[A](
    operation: IO[Either[NotificationError, A]],
    remaining: Int
  ): IO[Either[NotificationError, A]] =
    runs.update(_ + 1) *> operation.flatMap:
      case right @ Right(_)   => right.pure[IO].widen
      case left @ Left(error) =>
        if remaining <= 0 || !error.isTransient then left.pure[IO].widen
        else attempt(operation, remaining - 1)

object CountingRetry:
  def upTo(maxAttempts: Int): IO[CountingRetry] =
    Ref.of[IO, Int](0).map(CountingRetry(maxAttempts, _))

final class FixedTime(at: Instant) extends TimeSource[IO]:
  def now: IO[Instant] = IO.pure(at)

final class FixedIds(id: NotificationId) extends NotificationIdSource[IO]:
  def next: IO[NotificationId] = IO.pure(id)

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

  def contents: IO[Map[String, (PrincipalId, DevicePlatform)]] = tokens.get

object FakeDeviceRegistry:
  def empty: IO[FakeDeviceRegistry] =
    Ref.of[IO, Map[String, (PrincipalId, DevicePlatform)]](Map.empty).map(FakeDeviceRegistry(_))

object Fixtures:
  val recipient: PrincipalId = PrincipalId.fromString("11111111-2222-3333-4444-555555555555").get
  val notificationId: NotificationId = NotificationId.fromString("NTF-1").get
  val at: Instant = Instant.parse("2026-09-21T10:00:00Z")

  val email: Address = Address.Email(EmailAddress.fromString("buyer@kinetix.test").get)
  val push: Address = Address.Push(DeviceToken.fromString("handset-token-1").get)

  val orderParams: Map[String, String] = Map("order_number" -> "ORD-20260921-ABCD1234")

  val catalogue: MessageCatalogue = MessageCatalogue(
    Template.values
      .map(template => template -> MessageCopy(template.toString, "Order {order_number}."))
      .toMap
  )
