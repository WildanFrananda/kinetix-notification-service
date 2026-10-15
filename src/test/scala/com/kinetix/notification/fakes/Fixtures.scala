package com.kinetix.notification.fakes

import java.time.Instant

import com.kinetix.notification.application.NotifyCommand
import com.kinetix.notification.domain.*

object Fixtures:
  val sender: Caller = Caller.fromString("order").get
  val otherSender: Caller = Caller.fromString("matching").get
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

  val everything: TemplateGrants = TemplateGrants(
    Map(
      sender -> Template.values.toSet,
      otherSender -> Template.values.toSet
    )
  )

  def packed(
    preferred: List[Channel] = Nil,
    key: Option[IdempotencyKey] = None,
    from: Caller = sender,
    params: Map[String, String] = orderParams
  ): NotifyCommand =
    NotifyCommand(from, recipient, Template.OrderPacked, params, preferred, key)
