package com.kinetix.notification.application

import cats.effect.IO

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.*
import com.kinetix.notification.fakes.*

class NotifySuite extends BoundedSuite:
  private def notify(
    directory: RecipientDirectory[IO],
    senders: List[NotificationSender[IO]],
    repository: NotificationRepository[IO],
    retry: RetryPolicy[IO],
    grants: TemplateGrants = Fixtures.everything,
    ids: NotificationIdSource[IO] = FixedIds(Fixtures.notificationId),
    devices: DeviceRegistry[IO] = FakeDeviceRegistry.fresh()
  ): Notify[IO] =
    Notify[IO](
      Fixtures.catalogue,
      grants,
      directory,
      devices,
      senders,
      repository,
      retry,
      FixedTime(Fixtures.at),
      ids
    )

  test("a missing parameter is refused before the recipient is even looked up") {
    for
      directory <- FakeDirectory.holding(Fixtures.email)
      sender <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(sender), repository, retry)(
        Fixtures.packed(params = Map.empty)
      )
      asked <- directory.asked.get
      stored <- repository.contents
      sent <- sender.sent.get
    yield
      assertEquals(
        result,
        Left(NotificationError.TemplateParamMissing(Template.OrderPacked, "order_number"))
      )
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
      result <- notify(directory, List(email, push), repository, retry)(Fixtures.packed())
      emailSent <- email.sent.get
      pushSent <- push.sent.get
    yield
      assert(result.isRight)
      assertEquals(
        result.toOption.get.attempts.map(_.status),
        List(DeliveryStatus.Sent, DeliveryStatus.Sent)
      )
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
        Fixtures.packed(preferred = List(Channel.Push))
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
        Fixtures.packed(preferred = List(Channel.Push))
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
      result <- notify(directory, List(email), repository, retry)(Fixtures.packed())
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
      _ <- notify(directory, List(email), repository, retry)(Fixtures.packed())
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
      first <- use(Fixtures.packed(key = Some(key)))
      second <- use(Fixtures.packed(key = Some(key)))
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
      result <- notify(directory, List(email), repository, retry)(Fixtures.packed())
      stored <- repository.contents
    yield
      assert(result.isRight, "the notification was accepted even though the send failed")
      assertEquals(result.toOption.get.reached, false)
      assertEquals(stored.head.attempts.map(_.status), List(DeliveryStatus.Failed))
      assertEquals(stored.head.attempts.head.lastError, Some(rejected.detail))
  }

  test("a delivery record names the refusal and keeps the provider's own words out") {
    val refused = NotificationError.SenderRejected(
      Channel.Push,
      "403: SenderId mismatch for projects/kinetix-commerce"
    )
    val unavailable =
      NotificationError.SenderUnavailable(Channel.Email, "Connection refused: smtp-egress:443")

    for
      directory <- FakeDirectory.holding(Fixtures.push, Fixtures.email)
      push <- FakeSender.answering(Channel.Push, List(Left(refused)))
      email <- FakeSender.answering(Channel.Email, List.fill(3)(Left(unavailable)))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(push, email), repository, retry)(Fixtures.packed())
      stored <- repository.contents
    yield
      val reasons = stored.flatMap(_.attempts).flatMap(_.lastError)

      assertEquals(result.map(_.attempts.map(_.status)), Right(List.fill(2)(DeliveryStatus.Failed)))
      assertEquals(
        reasons,
        List(
          "the push provider refused the message; repeating the request unchanged does not help",
          "the email provider did not answer; try again later"
        )
      )
  }

  test("a rejection is not retried") {
    val rejected = NotificationError.SenderRejected(Channel.Email, "token is dead")

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.answering(Channel.Email, List(Left(rejected), Right(())))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      _ <- notify(directory, List(email), repository, retry)(Fixtures.packed())
      sent <- email.sent.get
    yield assertEquals(sent.length, 1, "a rejection must not be tried again")
  }

  test("a provider that did not answer is retried, and a later success counts") {
    val unavailable = NotificationError.SenderUnavailable(Channel.Email, "connection reset")

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.answering(
        Channel.Email,
        List(Left(unavailable), Left(unavailable), Right(()))
      )
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(Fixtures.packed())
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
      result <- notify(directory, List(email), repository, retry)(Fixtures.packed())
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
      result <- notify(directory, List(email), repository, retry)(Fixtures.packed())
    yield assertEquals(result, Left(NotificationError.NoChannelAvailable(Fixtures.recipient)))
  }

  test("a template the caller is not granted is refused before anything is read") {
    val onlyPaid = TemplateGrants(Map(Fixtures.sender -> Set(Template.OrderPaid)))

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry, grants = onlyPaid)(
        Fixtures.packed()
      )
      asked <- directory.asked.get
      stored <- repository.contents
      sent <- email.sent.get
    yield
      assertEquals(
        result,
        Left(NotificationError.TemplateNotGranted(Fixtures.sender, Template.OrderPacked))
      )
      assertEquals(asked, Nil)
      assertEquals(stored, Nil)
      assertEquals(sent, Nil)
  }

  test("a caller with no grants at all may send nothing") {
    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry, grants = TemplateGrants.none)(
        Fixtures.packed()
      )
      sent <- email.sent.get
    yield
      assertEquals(
        result,
        Left(NotificationError.TemplateNotGranted(Fixtures.sender, Template.OrderPacked))
      )
      assertEquals(sent, Nil)
  }

  test("the same key with a different request is refused, not answered with the first") {
    val key = IdempotencyKey.fromString("NOTIFY-ONCE").get
    val other = Map("order_number" -> "ORD-20260921-OTHER000")

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      use = notify(directory, List(email), repository, retry)
      first <- use(Fixtures.packed(key = Some(key)))
      second <- use(Fixtures.packed(key = Some(key), params = other))
      sent <- email.sent.get
    yield
      assertEquals(first.map(_.alreadyAccepted), Right(false))
      assertEquals(second, Left(NotificationError.IdempotencyKeyReused(key)))
      assertEquals(sent.length, 1)
  }

  test("a key belongs to the service that sent it") {
    val key = IdempotencyKey.fromString("NOTIFY-ONCE").get

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      ids <- SequentialIds.start
      use = notify(directory, List(email), repository, retry, ids = ids)
      ours <- use(Fixtures.packed(key = Some(key)))
      theirs <- use(Fixtures.packed(key = Some(key), from = Fixtures.otherSender))
      sent <- email.sent.get
      stored <- repository.contents
    yield
      assertEquals(ours.map(_.alreadyAccepted), Right(false))
      assertEquals(theirs.map(_.alreadyAccepted), Right(false))
      assertNotEquals(ours.map(_.id), theirs.map(_.id))
      assertEquals(sent.length, 2, "another service's key must not swallow this one's message")
      assertEquals(stored.map(_.sender).toSet, Set(Fixtures.sender, Fixtures.otherSender))
  }

  test("losing the race for a key answers with the notification that won it") {
    val key = IdempotencyKey.fromString("NOTIFY-ONCE").get
    val winner = Notification(
      NotificationId.fromString("NTF-WINNER").get,
      Fixtures.sender,
      Fixtures.recipient,
      Template.OrderPacked,
      Fixtures.orderParams,
      Some(key),
      Nil,
      Fixtures.at
    )

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- RacingRepository.losingTo(winner)
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(Fixtures.packed(key = Some(key)))
      sent <- email.sent.get
    yield
      assertEquals(result.map(_.id), Right(winner.id))
      assertEquals(result.map(_.alreadyAccepted), Right(true))
      assertEquals(sent, Nil, "the loser must not send what the winner is sending")
  }

  test("losing the race to a different request is refused") {
    val key = IdempotencyKey.fromString("NOTIFY-ONCE").get
    val winner = Notification(
      NotificationId.fromString("NTF-WINNER").get,
      Fixtures.sender,
      Fixtures.recipient,
      Template.OrderPaid,
      Fixtures.orderParams,
      Some(key),
      Nil,
      Fixtures.at
    )

    for
      directory <- FakeDirectory.holding(Fixtures.email)
      email <- FakeSender.working(Channel.Email)
      repository <- RacingRepository.losingTo(winner)
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email), repository, retry)(Fixtures.packed(key = Some(key)))
      sent <- email.sent.get
    yield
      assertEquals(result, Left(NotificationError.IdempotencyKeyReused(key)))
      assertEquals(sent, Nil)
  }

  test("a recipient who prefers push hears it on every handset, not only the newest") {
    val older = Address.Push(DeviceToken.fromString("handset-token-2").get)

    for
      directory <- FakeDirectory.holding(Fixtures.email, Fixtures.push, older)
      email <- FakeSender.working(Channel.Email)
      push <- FakeSender.working(Channel.Push)
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(email, push), repository, retry)(
        Fixtures.packed(preferred = List(Channel.Push))
      )
      pushSent <- push.sent.get
      emailSent <- email.sent.get
    yield
      assertEquals(pushSent.map(_._1), List(Fixtures.push, older))
      assertEquals(emailSent, Nil)
      assertEquals(
        result.map(_.attempts.map(attempt => (attempt.channel, attempt.status, attempt.attempts))),
        Right(List((Channel.Push, DeliveryStatus.Sent, 2)))
      )
  }

  test("a dead handset is forgotten, and the live one still hears the message") {
    val older = DeviceToken.fromString("handset-token-2").get
    val gone = NotificationError.AddressGone(Channel.Push, "404: UNREGISTERED")
    val limit = DeviceLimit.fromInt(10).get

    for
      devices <- FakeDeviceRegistry.empty
      _ <- devices.register(Fixtures.recipient, older, DevicePlatform.Android, limit)
      _ <- devices.register(
        Fixtures.recipient,
        DeviceToken.fromString("handset-token-1").get,
        DevicePlatform.Ios,
        limit
      )
      directory <- FakeDirectory.holding(Fixtures.push, Address.Push(older))
      push <- FakeSender.answering(Channel.Push, List(Right(()), Left(gone)))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(push), repository, retry, devices = devices)(
        Fixtures.packed()
      )
      kept <- devices.contents
      stored <- repository.contents
    yield
      assertEquals(kept.keySet, Set("handset-token-1"))
      assertEquals(
        result.map(_.attempts.map(attempt => (attempt.status, attempt.attempts))),
        Right(List((DeliveryStatus.Sent, 2)))
      )
      assertEquals(stored.head.attempts.map(_.status), List(DeliveryStatus.Sent))
  }

  test("a refusal that does not say the token is dead keeps the handset") {
    val refused = NotificationError.SenderRejected(Channel.Push, "403: SENDER_ID_MISMATCH")
    val limit = DeviceLimit.fromInt(10).get
    val token = DeviceToken.fromString("handset-token-1").get

    for
      devices <- FakeDeviceRegistry.empty
      _ <- devices.register(Fixtures.recipient, token, DevicePlatform.Ios, limit)
      directory <- FakeDirectory.holding(Fixtures.push)
      push <- FakeSender.answering(Channel.Push, List(Left(refused)))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(push), repository, retry, devices = devices)(
        Fixtures.packed()
      )
      kept <- devices.contents
    yield
      assertEquals(kept.keySet, Set("handset-token-1"))
      assertEquals(
        result.map(_.attempts.map(attempt => (attempt.status, attempt.lastError))),
        Right(List((DeliveryStatus.Failed, Some(refused.detail))))
      )
  }

  test("when every handset fails, the push attempt is failed and says why for each") {
    val gone = NotificationError.AddressGone(Channel.Push, "404: UNREGISTERED")
    val refused = NotificationError.SenderRejected(Channel.Push, "401: THIRD_PARTY_AUTH_ERROR")
    val older = Address.Push(DeviceToken.fromString("handset-token-2").get)

    for
      directory <- FakeDirectory.holding(Fixtures.push, older)
      push <- FakeSender.answering(Channel.Push, List(Left(gone), Left(refused)))
      repository <- FakeRepository.empty
      retry <- CountingRetry.upTo(3)
      result <- notify(directory, List(push), repository, retry)(Fixtures.packed())
    yield assertEquals(
      result.map(_.attempts.map(attempt => (attempt.status, attempt.attempts, attempt.lastError))),
      Right(List((DeliveryStatus.Failed, 2, Some(s"${gone.detail}; ${refused.detail}"))))
    )
  }
