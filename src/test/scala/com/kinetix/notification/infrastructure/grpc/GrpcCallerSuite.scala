package com.kinetix.notification.infrastructure.grpc

import cats.effect.{IO, Resource}
import fs2.grpc.syntax.all.*
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.{Metadata, Status, StatusRuntimeException}
import notification.v1.notification as wire

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.domain.{Caller, DeviceLimit, DevicePlatform, DeviceToken}
import com.kinetix.notification.fakes.Fixtures

class GrpcCallerSuite extends BoundedSuite:
  private val order: Caller = Caller.fromString("order").get

  private val notifyOnly: String = "order@notification.v1.NotificationService/Notify"

  private val request: wire.NotifyRequest =
    wire.NotifyRequest(
      recipientPrincipalId = Fixtures.recipient.value,
      template = wire.Template.TEMPLATE_ORDER_PACKED,
      params = Fixtures.orderParams
    )

  private def connected(
    grants: String,
    identity: Option[Caller]
  ): Resource[IO, (Served, wire.NotificationServiceFs2Grpc[IO, Metadata])] =
    val allowed =
      PeerAllowList.parse("TEST", grants).fold(failure => sys.error(failure), list => list)

    for
      served <- Served.start(Some(PeerAuthorizationInterceptor(allowed, _ => identity)))
      channel <- NettyChannelBuilder
        .forAddress("127.0.0.1", served.port)
        .usePlaintext()
        .resource[IO]
      client <- wire.NotificationServiceFs2Grpc.stubResource[IO](channel)
    yield (served, client)

  private def refusal[A](call: IO[A]): IO[Option[Status.Code]] =
    call.attempt.map:
      case Left(failure: StatusRuntimeException) => Some(failure.getStatus.getCode)
      case _                                     => None

  test("the sender is the peer the certificate names, whatever header the client sends") {
    val spoofed = Metadata()
    spoofed.put(CallerHeader.Key, "matching")

    connected(notifyOnly, Some(order)).use: (served, client) =>
      for
        response <- client.notify(request, spoofed)
        stored <- served.repository.contents
      yield
        assert(response.accepted, s"the call was refused: ${response.error}")
        assertEquals(stored.map(_.sender), List(order))
  }

  test("a method the caller is not granted is refused before it runs") {
    connected(notifyOnly, Some(order)).use: (_, client) =>
      refusal(client.getDelivery(wire.GetDeliveryRequest(notificationId = "NTF-1"), Metadata()))
        .map(code => assertEquals(code, Some(Status.Code.PERMISSION_DENIED)))
  }

  test("an empty list grants nobody, not everybody") {
    connected("", Some(order)).use: (served, client) =>
      for
        code <- refusal(client.notify(request, Metadata()))
        stored <- served.repository.contents
      yield
        assertEquals(code, Some(Status.Code.PERMISSION_DENIED))
        assertEquals(stored, Nil)
  }

  test("a peer whose certificate names no service is unauthenticated") {
    connected(notifyOnly, None).use: (_, client) =>
      refusal(client.notify(request, Metadata()))
        .map(code => assertEquals(code, Some(Status.Code.UNAUTHENTICATED)))
  }

  test("a device cannot be registered over gRPC by a peer granted only Notify") {
    val register = wire.RegisterDeviceRequest(
      principalId = Fixtures.recipient.value,
      deviceToken = "attacker-handset",
      platform = wire.DevicePlatform.DEVICE_PLATFORM_ANDROID
    )

    connected(notifyOnly, Some(order)).use: (served, client) =>
      for
        code <- refusal(client.registerDevice(register, Metadata()))
        devices <- served.devices.contents
      yield
        assertEquals(code, Some(Status.Code.PERMISSION_DENIED))
        assertEquals(devices, Map.empty)
  }

  test("a peer granted RegisterDevice still cannot bind a handset its principal has not proved") {
    val register = wire.RegisterDeviceRequest(
      principalId = Fixtures.recipient.value,
      deviceToken = "attacker-handset",
      platform = wire.DevicePlatform.DEVICE_PLATFORM_ANDROID
    )
    val granted = "order@notification.v1.NotificationService/RegisterDevice"

    connected(granted, Some(order)).use: (served, client) =>
      for
        response <- client.registerDevice(register, Metadata())
        devices <- served.devices.contents
        sent <- served.challenges.sent.get
      yield
        assertEquals(response.registered, false)
        assertEquals(response.error.map(_.errorCode), Some("DEVICE_VERIFICATION_PENDING"))
        assertEquals(devices, Map.empty)
        assertEquals(sent.map(_._1.value), List("attacker-handset"))
  }

  test("ForgetDevice over gRPC forgets nothing, because a token alone names no holder") {
    val granted = "order@notification.v1.NotificationService/ForgetDevice"
    val token = DeviceToken.fromString("victim-handset").get

    connected(granted, Some(order)).use: (served, client) =>
      for
        _ <- served.devices.register(
          Fixtures.recipient,
          token,
          DevicePlatform.Android,
          DeviceLimit.fromInt(10).get
        )
        response <- client.forgetDevice(
          wire.ForgetDeviceRequest(deviceToken = "victim-handset"),
          Metadata()
        )
        devices <- served.devices.contents
      yield
        assertEquals(response.forgotten, false)
        assertEquals(response.error.map(_.errorCode), Some("PRINCIPAL_REQUIRED"))
        assertEquals(devices.keySet, Set("victim-handset"))
  }
