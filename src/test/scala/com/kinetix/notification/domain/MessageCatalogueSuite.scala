package com.kinetix.notification.domain

import munit.FunSuite

class MessageCatalogueSuite extends FunSuite:
  test("a catalogue missing a template is refused when it is built") {
    val partial = Map(Template.OrderPaid -> MessageCopy("Paid", "Order {order_number} is paid."))

    MessageCatalogue.of(partial) match
      case Right(_)     => fail("a catalogue with one of six templates was accepted")
      case Left(reason) =>
        assert(reason.contains("OrderPacked"), s"the reason does not name what is missing: $reason")
  }

  test("a complete catalogue is accepted") {
    val full = Template.values.map(t => t -> MessageCopy("t", "b")).toMap

    assert(MessageCatalogue.of(full).isRight)
  }
