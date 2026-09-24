package com.kinetix.notification.infrastructure

import munit.CatsEffectSuite

import com.kinetix.notification.domain.{Message, Template}

class JsonMessageCatalogueSuite extends CatsEffectSuite:
  private val facts = Map(
    "order_number" -> "ORD-20260921-ABCD1234",
    "awb_number" -> "KNX-20260921-ABCDEFGH",
    "shipping_fee" -> "Rp15.000",
    "return_number" -> "RMA-20260921-ABCD1234"
  )

  test("the shipped catalogue has copy for every template") {
    JsonMessageCatalogue
      .load()
      .map: catalogue =>
        Template.values.foreach: template =>
          assert(catalogue.copyFor(template).isDefined, s"$template has no copy")
  }

  test("every shipped template renders from the facts this estate sends") {
    JsonMessageCatalogue
      .load()
      .map: catalogue =>
        Template.values.foreach: template =>
          Message.render(catalogue, template, facts) match
            case Left(error) =>
              fail(s"$template asks for something no caller sends: ${error.detail}")
            case Right(message) =>
              assert(message.title.nonEmpty, s"$template has an empty title")
              assert(message.body.nonEmpty, s"$template has an empty body")
              assert(
                !message.body.contains("{"),
                s"$template left a placeholder showing: ${message.body}"
              )
  }

  test("a catalogue that is not there fails the start, rather than starting silent") {
    JsonMessageCatalogue
      .load("/messages/nowhere.json")
      .attempt
      .map: result =>
        assert(result.isLeft, "a missing catalogue was accepted")
  }
