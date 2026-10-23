package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO
import notification.v1.notification as wire

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.application.{GetDelivery, Notify, RegisterDevice}
import com.kinetix.notification.domain.*
import com.kinetix.notification.fakes.*

class GrpcRefusalSuite extends BoundedSuite:
  private val limit: DeviceLimit = DeviceLimit.fromInt(10).get
  private val token: DeviceToken = DeviceToken.fromString("handset-token-1").get
  private val claimant: PrincipalId = PrincipalId.fromString("claimant-2").get

  test("a refusal over gRPC carries its code and a fixed message, never the dependency's words") {
    val directoryDown = NotificationError.DirectoryUnavailable(
      "UNAVAILABLE: io exception: identity.internal:50052 connection refused"
    )
    val pushRefused =
      NotificationError.SenderRejected(Channel.Push, "403: SenderId mismatch for kinetix-commerce")

    for
      directory <- FakeDirectory.failing(directoryDown)
      sender <- FakeSender.working(Channel.Email)
      repository <- FakeRepository.empty
      devices <- FakeDeviceRegistry.empty
      challenges <- FakeChallengeSender.answering(List(Left(pushRefused)))
      nonces <- SequentialNonces.start
      retry <- CountingRetry.upTo(1)
      server = GrpcNotificationServer(
        Notify[IO](
          Fixtures.catalogue,
          Fixtures.everything,
          directory,
          devices,
          List(sender),
          repository,
          retry,
          FixedTime(Fixtures.at),
          FixedIds(Fixtures.notificationId)
        ),
        GetDelivery[IO](repository),
        RegisterDevice[IO](devices, challenges, nonces, retry, FixedTime(Fixtures.at), limit)
      )
      notified <- server.notify(
        wire.NotifyRequest(
          recipientPrincipalId = Fixtures.recipient.value,
          template = wire.Template.TEMPLATE_ORDER_PACKED,
          params = Fixtures.orderParams
        ),
        Fixtures.sender
      )
      _ <- devices.register(Fixtures.recipient, token, DevicePlatform.Android, limit)
      registered <- server.registerDevice(
        wire.RegisterDeviceRequest(
          principalId = claimant.value,
          deviceToken = token.value,
          platform = wire.DevicePlatform.DEVICE_PLATFORM_ANDROID
        ),
        Fixtures.sender
      )
    yield
      assertEquals(
        notified.error.map(error => (error.errorCode, error.message)),
        Some(
          (
            "DIRECTORY_UNAVAILABLE",
            "identity could not be asked for the recipient's addresses; try again later"
          )
        )
      )
      assertEquals(
        registered.error.map(error => (error.errorCode, error.message)),
        Some(
          (
            "SENDER_REJECTED",
            "the push provider refused the message; repeating the request unchanged does not help"
          )
        )
      )
  }
