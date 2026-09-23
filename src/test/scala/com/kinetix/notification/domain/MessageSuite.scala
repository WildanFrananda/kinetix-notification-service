package com.kinetix.notification.domain

import munit.FunSuite

class MessageSuite extends FunSuite:
  private val catalogue = MessageCatalogue(
    Map(
      Template.OrderPaid -> MessageCopy("Paid", "Order {order_number} is paid."),
      Template.OrderPacked -> MessageCopy("Packed", "Order {order_number} is packed."),
      Template.CourierAssigned ->
        MessageCopy("Courier", "Order {order_number} is moving. Waybill {awb_number}."),
      Template.OrderDelivered -> MessageCopy("Delivered", "Order {order_number} arrived."),
      Template.CourierOffer -> MessageCopy("Offer", "Carry {order_number} for {shipping_fee}."),
      Template.ReturnOpened ->
        MessageCopy("Return", "Return {return_number} for order {order_number}.")
    )
  )

  test("every template renders when it has what it needs") {
    val facts = Map(
      "order_number" -> "ORD-20260921-ABCD1234",
      "awb_number" -> "KNX-20260921-ABCDEFGH",
      "shipping_fee" -> "Rp15.000",
      "return_number" -> "RMA-20260921-ABCD1234"
    )

    Template.values.foreach: template =>
      Message.render(catalogue, template, facts) match
        case Left(error)    => fail(s"$template did not render: ${error.detail}")
        case Right(message) =>
          assert(message.title.nonEmpty, s"$template has no title")
          assert(message.body.nonEmpty, s"$template has no body")
  }

  test("a missing parameter is refused rather than rendered as a gap") {
    val result = Message.render(catalogue, Template.OrderPacked, Map.empty)

    assertEquals(
      result,
      Left(NotificationError.TemplateParamMissing(Template.OrderPacked, "order_number"))
    )
  }

  test("a blank parameter is as missing as an absent one") {
    val result = Message.render(catalogue, Template.OrderPacked, Map("order_number" -> "   "))

    assertEquals(
      result,
      Left(NotificationError.TemplateParamMissing(Template.OrderPacked, "order_number"))
    )
  }

  test("a template that needs two facts names the one it is missing") {
    val result =
      Message.render(catalogue, Template.CourierAssigned, Map("order_number" -> "ORD-1"))

    assertEquals(
      result,
      Left(NotificationError.TemplateParamMissing(Template.CourierAssigned, "awb_number"))
    )
  }

  test("the facts reach the words") {
    val result = Message.render(
      catalogue,
      Template.CourierAssigned,
      Map("order_number" -> "ORD-1", "awb_number" -> "KNX-9")
    )

    assert(result.exists(_.body.contains("ORD-1")))
    assert(result.exists(_.body.contains("KNX-9")))
  }

  test("a placeholder in the title is filled too, not left showing") {
    val titled = MessageCatalogue(
      catalogue.entries + (Template.OrderPaid -> MessageCopy("Order {order_number}", "Paid."))
    )

    val result = Message.render(titled, Template.OrderPaid, Map("order_number" -> "ORD-7"))

    assertEquals(result.map(_.title), Right("Order ORD-7"))
  }

  test("a placeholder that appears twice is filled in both places") {
    val twice = MessageCatalogue(
      catalogue.entries + (Template.OrderPaid -> MessageCopy(
        "Paid",
        "{order_number}/{order_number}"
      ))
    )

    val result = Message.render(twice, Template.OrderPaid, Map("order_number" -> "ORD-7"))

    assertEquals(result.map(_.body), Right("ORD-7/ORD-7"))
  }

  test("copy that asks for a fact nobody sends is refused, not sent with a hole in it") {
    val wrong = MessageCatalogue(
      catalogue.entries + (Template.OrderPaid -> MessageCopy(
        "Paid",
        "Order {order_nmber} is paid."
      ))
    )

    val result = Message.render(wrong, Template.OrderPaid, Map("order_number" -> "ORD-7"))

    assertEquals(
      result,
      Left(NotificationError.TemplateParamMissing(Template.OrderPaid, "order_nmber"))
    )
  }

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
