package com.kinetix.notification.domain

import munit.FunSuite

class MessageSuite extends FunSuite:
  test("every template renders when it has what it needs") {
    val facts = Map(
      "order_number" -> "ORD-20260921-ABCD1234",
      "awb_number" -> "KNX-20260921-ABCDEFGH",
      "shipping_fee" -> "Rp15.000",
      "return_number" -> "RMA-20260921-ABCD1234"
    )

    Template.values.foreach: template =>
      Message.render(template, facts) match
        case Left(error) => fail(s"$template did not render: ${error.detail}")
        case Right(message) =>
          assert(message.title.nonEmpty, s"$template has no title")
          assert(message.body.nonEmpty, s"$template has no body")
  }

  test("a missing parameter is refused rather than rendered as a gap") {
    val result = Message.render(Template.OrderPacked, Map.empty)

    assertEquals(result, Left(NotificationError.TemplateParamMissing(Template.OrderPacked, "order_number")))
  }

  test("a blank parameter is as missing as an absent one") {
    val result = Message.render(Template.OrderPacked, Map("order_number" -> "   "))

    assertEquals(result, Left(NotificationError.TemplateParamMissing(Template.OrderPacked, "order_number")))
  }

  test("a template that needs two facts names the one it is missing") {
    val result = Message.render(Template.CourierAssigned, Map("order_number" -> "ORD-1"))

    assertEquals(result, Left(NotificationError.TemplateParamMissing(Template.CourierAssigned, "awb_number")))
  }

  test("the facts reach the words") {
    val result = Message.render(
      Template.CourierAssigned,
      Map("order_number" -> "ORD-1", "awb_number" -> "KNX-9")
    )

    assert(result.exists(_.body.contains("ORD-1")))
    assert(result.exists(_.body.contains("KNX-9")))
  }
