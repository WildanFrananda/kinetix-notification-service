package com.kinetix.notification.infrastructure.grpc

import notification.v1.notification as wire

import com.kinetix.notification.domain as model

private[grpc] object Reads:
  final case class NotifyRead(
    recipient: model.PrincipalId,
    template: model.Template,
    channels: List[model.Channel],
    idempotencyKey: Option[model.IdempotencyKey]
  )

  def notifyRequest(request: wire.NotifyRequest): Either[model.NotificationError, NotifyRead] =
    for
      recipient <- model.PrincipalId
        .fromString(request.recipientPrincipalId)
        .toRight(model.NotificationError.NoRecipient)
      template <- template(request.template).toRight(model.NotificationError.NoTemplate)
    yield NotifyRead(
      recipient = recipient,
      template = template,
      channels = request.channels.toList.flatMap(channel),
      idempotencyKey =
        request.idempotencyKey.flatMap(key => model.IdempotencyKey.fromString(key.key))
    )

  def template(raw: wire.Template): Option[model.Template] = raw match
    case wire.Template.TEMPLATE_ORDER_PAID       => Some(model.Template.OrderPaid)
    case wire.Template.TEMPLATE_ORDER_PACKED     => Some(model.Template.OrderPacked)
    case wire.Template.TEMPLATE_COURIER_ASSIGNED => Some(model.Template.CourierAssigned)
    case wire.Template.TEMPLATE_ORDER_DELIVERED  => Some(model.Template.OrderDelivered)
    case wire.Template.TEMPLATE_COURIER_OFFER    => Some(model.Template.CourierOffer)
    case wire.Template.TEMPLATE_RETURN_OPENED    => Some(model.Template.ReturnOpened)
    case _                                       => None

  def channel(raw: wire.Channel): Option[model.Channel] = raw match
    case wire.Channel.CHANNEL_PUSH  => Some(model.Channel.Push)
    case wire.Channel.CHANNEL_EMAIL => Some(model.Channel.Email)
    case _                          => None

  def platform(raw: wire.DevicePlatform): model.DevicePlatform = raw match
    case wire.DevicePlatform.DEVICE_PLATFORM_IOS => model.DevicePlatform.Ios
    case _                                       => model.DevicePlatform.Android
