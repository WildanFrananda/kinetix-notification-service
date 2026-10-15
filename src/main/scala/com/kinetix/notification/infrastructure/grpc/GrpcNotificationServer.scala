package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO

import common.v1.common.ErrorDetail
import notification.v1.notification as wire

import com.kinetix.notification.application.*
import com.kinetix.notification.domain as model

final class GrpcNotificationServer(
  notify: Notify[IO],
  getDelivery: GetDelivery[IO],
  registerDevice: RegisterDevice[IO],
  forgetDevice: ForgetDevice[IO]
) extends wire.NotificationServiceFs2Grpc[IO, model.Caller]:
  def notify(request: wire.NotifyRequest, caller: model.Caller): IO[wire.NotifyResponse] =
    Reads.notifyCommand(caller, request) match
      case Left(error)    => IO.pure(refuseNotify(error))
      case Right(command) =>
        notify(command).map:
          case Left(error)    => refuseNotify(error)
          case Right(outcome) =>
            wire.NotifyResponse(
              accepted = true,
              notificationId = outcome.id.value,
              alreadyAccepted = outcome.alreadyAccepted
            )

  def getDelivery(
    request: wire.GetDeliveryRequest,
    caller: model.Caller
  ): IO[wire.GetDeliveryResponse] =
    model.NotificationId.fromString(request.notificationId) match
      case None     => IO.pure(wire.GetDeliveryResponse(found = false))
      case Some(id) =>
        getDelivery(caller, id).flatMap:
          case Left(error)    => IO.raiseError(unavailable(error))
          case Right(None)    => IO.pure(wire.GetDeliveryResponse(found = false))
          case Right(Some(n)) => IO.pure(Writes.delivery(n))

  def registerDevice(
    request: wire.RegisterDeviceRequest,
    caller: model.Caller
  ): IO[wire.RegisterDeviceResponse] =
    (
      model.PrincipalId.fromString(request.principalId),
      model.DeviceToken.fromString(request.deviceToken),
      Reads.platform(request.platform)
    ) match
      case (None, _, _) =>
        IO.pure(refuseRegister(model.NotificationError.NoRecipient))
      case (_, None, _) =>
        IO.pure(refuseRegister(model.NotificationError.InvalidDeviceToken))
      case (_, _, None) =>
        IO.pure(refuseRegister(model.NotificationError.InvalidPlatform))
      case (Some(principal), Some(token), Some(platform)) =>
        registerDevice(principal, token, platform).map:
          case Left(error)         => refuseRegister(error)
          case Right(alreadyKnown) =>
            wire.RegisterDeviceResponse(registered = true, alreadyRegistered = alreadyKnown)

  def forgetDevice(
    request: wire.ForgetDeviceRequest,
    caller: model.Caller
  ): IO[wire.ForgetDeviceResponse] =
    model.DeviceToken.fromString(request.deviceToken) match
      case None =>
        IO.pure(refuseForget(model.NotificationError.InvalidDeviceToken))
      case Some(token) =>
        forgetDevice(token).map:
          case Left(error) => refuseForget(error)
          case Right(())   => wire.ForgetDeviceResponse(forgotten = true)

  private def refuseNotify(error: model.NotificationError): wire.NotifyResponse =
    wire.NotifyResponse(accepted = false, error = Some(detail(error)))

  private def refuseRegister(error: model.NotificationError): wire.RegisterDeviceResponse =
    wire.RegisterDeviceResponse(registered = false, error = Some(detail(error)))

  private def refuseForget(error: model.NotificationError): wire.ForgetDeviceResponse =
    wire.ForgetDeviceResponse(forgotten = false, error = Some(detail(error)))

  private def detail(error: model.NotificationError): ErrorDetail =
    ErrorDetail(errorCode = Codes.of(error), message = error.detail)

  private def unavailable(error: model.NotificationError): io.grpc.StatusException =
    io.grpc.Status.UNAVAILABLE
      .withDescription(s"${Codes.of(error)}: ${error.detail}")
      .asException()
