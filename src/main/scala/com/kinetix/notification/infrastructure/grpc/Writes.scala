package com.kinetix.notification.infrastructure.grpc

import notification.v1.notification as wire

import com.kinetix.notification.domain as model

private[grpc] object Writes:
  def delivery(notification: model.Notification): wire.GetDeliveryResponse =
    wire.GetDeliveryResponse(
      found = true,
      notificationId = notification.id.value,
      recipientPrincipalId = notification.recipient.value,
      template = template(notification.template),
      attempts = notification.attempts.map(attempt)
    )

  private def attempt(source: model.DeliveryAttempt): wire.DeliveryAttempt =
    wire.DeliveryAttempt(
      channel = channel(source.channel),
      status = status(source.status),
      attempts = source.attempts,
      lastError = source.lastError.getOrElse(""),
      lastAttemptAt = source.lastAttemptAt.toString
    )

  private def template(source: model.Template): wire.Template = source match
    case model.Template.OrderPaid       => wire.Template.TEMPLATE_ORDER_PAID
    case model.Template.OrderPacked     => wire.Template.TEMPLATE_ORDER_PACKED
    case model.Template.CourierAssigned => wire.Template.TEMPLATE_COURIER_ASSIGNED
    case model.Template.OrderDelivered  => wire.Template.TEMPLATE_ORDER_DELIVERED
    case model.Template.CourierOffer    => wire.Template.TEMPLATE_COURIER_OFFER
    case model.Template.ReturnOpened    => wire.Template.TEMPLATE_RETURN_OPENED

  private def channel(source: model.Channel): wire.Channel = source match
    case model.Channel.Push  => wire.Channel.CHANNEL_PUSH
    case model.Channel.Email => wire.Channel.CHANNEL_EMAIL

  private def status(source: model.DeliveryStatus): wire.DeliveryStatus = source match
    case model.DeliveryStatus.Pending     => wire.DeliveryStatus.DELIVERY_STATUS_PENDING
    case model.DeliveryStatus.Sent        => wire.DeliveryStatus.DELIVERY_STATUS_SENT
    case model.DeliveryStatus.Failed      => wire.DeliveryStatus.DELIVERY_STATUS_FAILED
    case model.DeliveryStatus.Unreachable => wire.DeliveryStatus.DELIVERY_STATUS_UNREACHABLE
