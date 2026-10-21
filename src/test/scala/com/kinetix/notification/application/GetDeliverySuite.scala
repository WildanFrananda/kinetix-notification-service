package com.kinetix.notification.application

import cats.effect.IO

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.domain.*
import com.kinetix.notification.fakes.*

class GetDeliverySuite extends BoundedSuite:
  private val sent = Notification(
    Fixtures.notificationId,
    Fixtures.sender,
    Fixtures.recipient,
    Template.OrderPacked,
    Fixtures.orderParams,
    None,
    Nil,
    Fixtures.at
  )

  test("the service that sent a notification can read it") {
    for
      repository <- FakeRepository.holding(sent)
      found <- GetDelivery[IO](repository)(Fixtures.sender, Fixtures.notificationId)
    yield assertEquals(found, Right(Some(sent)))
  }

  test("another service cannot read it, and is told it does not exist") {
    for
      repository <- FakeRepository.holding(sent)
      found <- GetDelivery[IO](repository)(Fixtures.otherSender, Fixtures.notificationId)
    yield assertEquals(found, Right(None))
  }
