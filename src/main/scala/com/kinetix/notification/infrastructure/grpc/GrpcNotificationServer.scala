package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO
import io.grpc.Metadata

import common.v1.common.ErrorDetail
import notification.v1.notification as wire

import com.kinetix.notification.application.*
import com.kinetix.notification.domain as model

final class GrpcNotificationServer(
    notify: Notify[IO],
    getDelivery: GetDelivery[IO],
    registerDevice: RegisterDevice[IO],
    forgetDevice: ForgetDevice[IO]
) extends wire.NotificationServiceFs2Grpc[IO, Metadata]:

  def notify(request: wire.NotifyRequest, ctx: Metadata): IO[wire.NotifyResponse] =
    Reads.notifyRequest(request) match
      case Left(error) => IO.pure(refuseNotify(error))
      case Right(read) =>
        notify(read.recipient, read.template, request.params, read.channels, read.idempotencyKey)
          .map:
            case Left(error) => refuseNotify(error)
            case Right(outcome) =>
              wire.NotifyResponse(
                accepted = true,
                notificationId = outcome.id.value,
                alreadyAccepted = outcome.alreadyAccepted
              )

  def getDelivery(request: wire.GetDeliveryRequest, ctx: Metadata): IO[wire.GetDeliveryResponse] =
    model.NotificationId.fromString(request.notificationId) match
      case None => IO.pure(wire.GetDeliveryResponse(found = false))
      case Some(id) =>
        getDelivery(id).map:
          case Left(_)        => wire.GetDeliveryResponse(found = false)
          case Right(None)    => wire.GetDeliveryResponse(found = false)
          case Right(Some(n)) => Writes.delivery(n)

  def registerDevice(
      request: wire.RegisterDeviceRequest,
      ctx: Metadata
  ): IO[wire.RegisterDeviceResponse] =
    (
      model.PrincipalId.fromString(request.principalId),
      model.DeviceToken.fromString(request.deviceToken)
    ) match
      case (None, _) =>
        IO.pure(refuseRegister(model.NotificationError.NoRecipient))
      case (_, None) =>
        IO.pure(refuseRegister(model.NotificationError.InvalidDeviceToken))
      case (Some(principal), Some(token)) =>
        registerDevice(principal, token, Reads.platform(request.platform)).map:
          case Left(error) => refuseRegister(error)
          case Right(alreadyKnown) =>
            wire.RegisterDeviceResponse(registered = true, alreadyRegistered = alreadyKnown)

  def forgetDevice(request: wire.ForgetDeviceRequest, ctx: Metadata): IO[wire.ForgetDeviceResponse] =
    model.DeviceToken.fromString(request.deviceToken) match
      case None =>
        IO.pure(
          wire.ForgetDeviceResponse(
            forgotten = false,
            error = Some(detail("INVALID_DEVICE_TOKEN", model.NotificationError.InvalidDeviceToken))
          )
        )
      case Some(token) =>
        forgetDevice(token).map:
          case Left(error) =>
            wire.ForgetDeviceResponse(forgotten = false, error = Some(detail(Codes.of(error), error)))
          case Right(())  => wire.ForgetDeviceResponse(forgotten = true)

  private def refuseNotify(error: model.NotificationError): wire.NotifyResponse =
    wire.NotifyResponse(accepted = false, error = Some(detail(Codes.of(error), error)))

  private def refuseRegister(error: model.NotificationError): wire.RegisterDeviceResponse =
    wire.RegisterDeviceResponse(registered = false, error = Some(detail(Codes.of(error), error)))

  private def detail(code: String, error: model.NotificationError): ErrorDetail =
    ErrorDetail(errorCode = code, message = error.detail)
