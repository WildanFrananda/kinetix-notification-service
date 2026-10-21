package com.kinetix.notification.infrastructure.grpc

import cats.effect.{IO, Resource}
import fs2.grpc.syntax.all.*
import io.grpc.ServerInterceptor
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import notification.v1.notification.NotificationServiceFs2Grpc

import com.kinetix.notification.application.{GetDelivery, Notify, RegisterDevice}
import com.kinetix.notification.domain.{Caller, Channel, DeviceLimit}
import com.kinetix.notification.fakes.*

final case class Served(
  port: Int,
  repository: FakeRepository,
  devices: FakeDeviceRegistry,
  challenges: FakeChallengeSender
)

object Served:
  def start(interceptor: Option[ServerInterceptor]): Resource[IO, Served] =
    for
      directory <- Resource.eval(FakeDirectory.holding(Fixtures.email))
      sender <- Resource.eval(FakeSender.working(Channel.Email))
      repository <- Resource.eval(FakeRepository.empty)
      devices <- Resource.eval(FakeDeviceRegistry.empty)
      challenges <- Resource.eval(FakeChallengeSender.working)
      nonces <- Resource.eval(SequentialNonces.start)
      retry <- Resource.eval(CountingRetry.upTo(1))
      handler = GrpcNotificationServer(
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
        RegisterDevice[IO](
          devices,
          challenges,
          nonces,
          retry,
          FixedTime(Fixtures.at),
          DeviceLimit.fromInt(10).get
        )
      )
      service <- NotificationServiceFs2Grpc.serviceResource[IO, Caller](handler, CallerHeader.read)
      builder = NettyServerBuilder
        .forPort(0)
        .addService(service)
        .addService(ProtoReflectionServiceV1.newInstance())
      server <- interceptor
        .fold(builder)(builder.intercept)
        .resource[IO]
        .evalMap(running => IO(running.start()))
    yield Served(server.getPort, repository, devices, challenges)
