package com.kinetix.notification.infrastructure.grpc

import cats.syntax.all.*
import notification.v1.notification as wire

import com.kinetix.notification.application.NotifyCommand
import com.kinetix.notification.domain as model

private[grpc] object Reads:
  def notifyCommand(
    sender: model.Caller,
    request: wire.NotifyRequest
  ): Either[model.NotificationError, NotifyCommand] =
    for
      recipient <- model.PrincipalId
        .fromString(request.recipientPrincipalId)
        .toRight(model.NotificationError.NoRecipient)
      template <- template(request.template).toRight(model.NotificationError.NoTemplate)
      key <- request.idempotencyKey.traverse(sent =>
        model.IdempotencyKey
          .fromString(sent.key)
          .toRight(model.NotificationError.InvalidIdempotencyKey)
      )
    yield NotifyCommand(
      sender = sender,
      recipient = recipient,
      template = template,
      params = request.params,
      preferred = request.channels.toList.flatMap(channel),
      idempotencyKey = key
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

  def platform(raw: wire.DevicePlatform): Option[model.DevicePlatform] = raw match
    case wire.DevicePlatform.DEVICE_PLATFORM_ANDROID => Some(model.DevicePlatform.Android)
    case wire.DevicePlatform.DEVICE_PLATFORM_IOS     => Some(model.DevicePlatform.Ios)
    case _                                           => None
