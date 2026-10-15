package com.kinetix.notification.infrastructure.grpc

import munit.FunSuite

import com.kinetix.notification.domain.Caller

class PeerAllowListSuite extends FunSuite:
  private val order = Caller.fromString("order").get
  private val matching = Caller.fromString("matching").get

  private val notify = "notification.v1.NotificationService/Notify"
  private val getDelivery = "notification.v1.NotificationService/GetDelivery"

  private def parsed(raw: String): PeerAllowList =
    PeerAllowList.parse("TEST", raw).fold(failure => fail(failure), list => list)

  test("a method grant permits that method and no other") {
    val allowed = parsed(s"order@$notify")

    assert(allowed.permits(order, s"/$notify"))
    assert(!allowed.permits(order, getDelivery))
  }

  test("a service grant permits every method of that service") {
    val allowed = parsed("order@notification.v1.NotificationService")

    assert(allowed.permits(order, notify))
    assert(allowed.permits(order, getDelivery))
  }

  test("a bare peer is granted everything, as order and identity read it") {
    assert(parsed("order").permits(order, getDelivery))
  }

  test("grants for one peer add up and do not leak to another") {
    val allowed = parsed(s"order@$notify, order@$getDelivery")

    assert(allowed.permits(order, notify))
    assert(allowed.permits(order, getDelivery))
    assert(!allowed.names(matching))
    assert(!allowed.permits(matching, notify))
  }

  test("an empty list names nobody") {
    val allowed = parsed("  ")

    assert(!allowed.names(order))
    assert(!allowed.permits(order, notify))
    assertEquals(allowed.summary, "nobody")
  }

  test("reflection is open to a verified peer the list does not name") {
    assert(parsed("").permits(order, "grpc.reflection.v1.ServerReflection/ServerReflectionInfo"))
  }

  test("an entry that is not a grant fails the start") {
    List("Order", "order@", "@notification.v1.NotificationService", "order@Notify", "order@a.B/")
      .foreach: raw =>
        assert(PeerAllowList.parse("TEST", raw).isLeft, s"'$raw' was accepted")
  }

  test("a grant for something this server does not serve is reported") {
    val allowed = parsed(s"order@$notify, order@notification.v1.NotificationService/Notfy")

    assertEquals(
      allowed.unknownScopes(Set(notify, getDelivery, "notification.v1.NotificationService")),
      List("notification.v1.NotificationService/Notfy")
    )
  }
