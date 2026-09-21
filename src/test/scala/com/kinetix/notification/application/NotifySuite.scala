package com.kinetix.notification.application

import cats.effect.IO
import munit.CatsEffectSuite

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.*
import com.kinetix.notification.fakes.*

class NotifySuite extends CatsEffectSuite:

  private def notify(
      directory: RecipientDirectory[IO],
      senders: List[NotificationSender[IO]],
      repository: NotificationRepository[IO],
      retry: RetryPolicy[IO]
  ): Notify[IO] =
    Notify[IO](
      directory,
      senders,
      repository,
      retry,
      FixedTime(Fixtures.at),
      FixedIds(Fixtures.notificationId)
    )

  test("a missing parameter is refused before the recipient is even looked up") {
    for
      directory <- FakeDirectory.holding(Fixtures.email)
      sender <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(sender), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Map.empty,
        Nil,
        None
      )
      asked <- directory.asked.get
      stored <- repository.contents
      sent <- sender.sent.get
    yield
      assertEquals(result, Left(NotificationError.TemplateParamMissing(Template.OrderPacked, "order_number")))
      assertEquals(asked, Nil, "identity must not be asked about a request that cannot be rendered")
      assertEquals(stored, Nil)
      assertEquals(sent, Nil)
  }

  test("a notification goes out on every channel the recipient has") {
    for
      directory <- FakeDirectory.holding(Fixtures.email, Fixtures.push)
      email <- FakeSender.working(Channel.Email)
      push <- FakeSender.working(Channel.Push)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email, push), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
      emailSent <- email.sent.get
      pushSent <- push.sent.get
    yield
      assert(result.isRight)
      assertEquals(result.toOption.get.attempts.map(_.status), List(DeliveryStatus.Sent, DeliveryStatus.Sent))
      assertEquals(emailSent.length, 1)
      assertEquals(pushSent.length, 1)
  }

  test("a caller's preference narrows the channels and cannot add one") {
    for
      directory <- FakeDirectory.holding(Fixtures.email, Fixtures.push)
      email <- FakeSender.working(Channel.Email)
      push <- FakeSender.working(Channel.Push)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      _ <- notify(directory, List(email, push), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        List(Channel.Push),
        None
      )
      emailSent <- email.sent.get
      pushSent <- push.sent.get
    yield
      assertEquals(pushSent.length, 1)
      assertEquals(emailSent, Nil, "the caller asked for push only")
  }

  test("asking for a channel the recipient has no address on sends nothing on it") {
    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      push <- FakeSender.working(Channel.Push)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email, push), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        List(Channel.Push),
        None
      )
      pushSent <- push.sent.get
    yield
      assertEquals(result, Left(NotificationError.NoChannelAvailable(Fixtures.recipient)))
      assertEquals(pushSent, Nil)
  }

  test("a recipient with no address at all is refused and leaves no record") {
    for
      directory <- FakeDirectory.holding()
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
      stored <- repository.contents
    yield
      assertEquals(result, Left(NotificationError.NoChannelAvailable(Fixtures.recipient)))
      assertEquals(stored, Nil)
  }

  test("the record exists before a provider is called") {
    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      _ <- notify(directory, List(email), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
      saves <- repository.saves.get
      stored <- repository.contents
    yield
      assertEquals(saves, List(Fixtures.notificationId))
      assertEquals(stored.head.attempts.map(_.status), List(DeliveryStatus.Sent))
  }

  test("the same idempotency key twice is one notification and one message") {
    val key = IdempotencyKey.fromString("NOTIFY-ONCE").get

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      use = notify(directory, List(email), repository, retry)
      first <- use(Fixtures.recipient, Template.OrderPacked, Fixtures.orderParams, Nil, Some(key))
      second <- use(Fixtures.recipient, Template.OrderPacked, Fixtures.orderParams, Nil, Some(key))
      sent <- email.sent.get
      stored <- repository.contents
    yield
      assertEquals(first.map(_.alreadyAccepted), Right(false))
      assertEquals(second.map(_.alreadyAccepted), Right(true))
      assertEquals(second.map(_.id), first.map(_.id))
      assertEquals(sent.length, 1, "the second call must not send a second message")
      assertEquals(stored.length, 1)
  }

  test("a failed send is recorded and reported, not raised") {
    val rejected = NotificationError.SenderRejected(Channel.Email, "mailbox does not exist")

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.answering(Channel.Email, List(Left(rejected)))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
      stored <- repository.contents
    yield
      assert(result.isRight, "the notification was accepted even though the send failed")
      assertEquals(result.toOption.get.reached, false)
      assertEquals(stored.head.attempts.map(_.status), List(DeliveryStatus.Failed))
      assertEquals(stored.head.attempts.head.lastError, Some(rejected.detail))
  }

  test("a rejection is not retried") {
    val rejected = NotificationError.SenderRejected(Channel.Email, "token is dead")

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.answering(Channel.Email, List(Left(rejected), Right(())))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      _ <- notify(directory, List(email), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
      sent <- email.sent.get
    yield assertEquals(sent.length, 1, "a rejection must not be tried again")
  }

  test("a provider that did not answer is retried, and a later success counts") {
    val unavailable = NotificationError.SenderUnavailable(Channel.Email, "connection reset")

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.answering(Channel.Email, List(Left(unavailable), Left(unavailable), Right(())))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
      sent <- email.sent.get
    yield
      assertEquals(sent.length, 3)
      assertEquals(result.map(_.reached), Right(true))
  }

  test("identity being unreachable is reported as such") {
    val unavailable = NotificationError.DirectoryUnavailable("deadline exceeded")

    for
      directory <- FakeDirectory.failing(unavailable)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
      sent <- email.sent.get
      stored <- repository.contents
    yield
      assertEquals(result, Left(unavailable))
      assertEquals(sent, Nil)
      assertEquals(stored, Nil)
  }

  test("an address with no sender behind it does not count as a channel") {
    for
      directory <- FakeDirectory.holding(Fixtures.push)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(
        Fixtures.recipient,
        Template.OrderPacked,
        Fixtures.orderParams,
        Nil,
        None
      )
    yield assertEquals(result, Left(NotificationError.NoChannelAvailable(Fixtures.recipient)))
  }
