package com.kinetix.notification.infrastructure.grpc

import munit.FunSuite

import com.kinetix.notification.domain.{Caller, Template}

class TemplateGrantListSuite extends FunSuite:
  private val order = Caller.fromString("order").get
  private val matching = Caller.fromString("matching").get

  test("each peer is granted exactly the templates named for it") {
    val grants = TemplateGrantList
      .parse(
        "TEST",
        "order:TEMPLATE_ORDER_PAID, order:TEMPLATE_ORDER_DELIVERED, matching:TEMPLATE_COURIER_OFFER"
      )
      .fold(failure => fail(failure), parsed => parsed)

    assert(grants.permits(order, Template.OrderPaid))
    assert(grants.permits(order, Template.OrderDelivered))
    assert(!grants.permits(order, Template.CourierOffer))
    assert(grants.permits(matching, Template.CourierOffer))
    assert(!grants.permits(matching, Template.OrderPaid))
  }

  test("an empty value grants no template to anyone") {
    assertEquals(TemplateGrantList.parse("TEST", "").map(_.granted), Right(Map.empty))
  }

  test("a name the contract does not define fails the start") {
    List(
      "order:ORDER_PAID",
      "order:TEMPLATE_UNSPECIFIED",
      "order",
      ":TEMPLATE_ORDER_PAID",
      "Order:TEMPLATE_ORDER_PAID"
    ).foreach: raw =>
      assert(TemplateGrantList.parse("TEST", raw).isLeft, s"'$raw' was accepted")
  }
