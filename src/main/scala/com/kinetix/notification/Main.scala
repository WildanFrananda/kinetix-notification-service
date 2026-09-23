package com.kinetix.notification

import cats.effect.{ExitCode, IO, IOApp, Resource}
import doobie.hikari.HikariTransactor
import fs2.grpc.syntax.all.*
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.{NettyChannelBuilder, NettyServerBuilder}
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder

import identity.v1.identity.IdentityServiceFs2Grpc
import notification.v1.notification.NotificationServiceFs2Grpc

import com.kinetix.notification.application.*
import com.kinetix.notification.domain.EmailAddress
import com.kinetix.notification.domain.ports.*
import com.kinetix.notification.infrastructure.{
  JsonMessageCatalogue,
  RandomNotificationIdSource,
  ServiceIdentity,
  SystemTimeSource
}
import com.kinetix.notification.infrastructure.config.Settings
import com.kinetix.notification.infrastructure.grpc.*
import com.kinetix.notification.infrastructure.http.{HttpEmailSender, HttpPushSender}
import com.kinetix.notification.infrastructure.persistence.{
  PostgresDeviceRegistry,
  PostgresNotificationRepository
}
import com.kinetix.notification.infrastructure.retry.ExponentialBackoffRetry

object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Settings.load.flatMap(serve)

  private def serve(settings: Settings): IO[ExitCode] =
    resources(settings).use(_ => IO.never).as(ExitCode.Success)

  private def resources(settings: Settings): Resource[IO, Unit] =
    for
      transactor <- database(settings)
      httpClient <- EmberClientBuilder.default[IO].build
      identity <- identityClient(settings)

      repository: NotificationRepository[IO] = PostgresNotificationRepository(transactor)
      devices: DeviceRegistry[IO] = PostgresDeviceRegistry(transactor)

      emailFrom <- Resource.eval(
        IO.fromOption(EmailAddress.fromString(settings.emailFromAddress))(
          IllegalStateException(
            s"EMAIL_FROM_ADDRESS is '${settings.emailFromAddress}', which is not an email address."
          )
        )
      )
      pushEndpoint <- Resource.eval(uri("PUSH_PROVIDER_URL", settings.pushProviderUrl))
      emailEndpoint <- Resource.eval(uri("EMAIL_PROVIDER_URL", settings.emailProviderUrl))

      senders: List[NotificationSender[IO]] = List(
        HttpPushSender(httpClient, pushEndpoint, settings.pushProviderKey),
        HttpEmailSender(httpClient, emailEndpoint, settings.emailProviderKey, emailFrom)
      )

      directory: RecipientDirectory[IO] = CompositeRecipientDirectory(
        List(
          GrpcIdentityRecipientDirectory(identity),
          DeviceRegistryRecipientDirectory(devices)
        )
      )

      catalogue <- Resource.eval(JsonMessageCatalogue.load())

      retry: RetryPolicy[IO] = ExponentialBackoffRetry.default[IO]
      time: TimeSource[IO] = SystemTimeSource()
      ids: NotificationIdSource[IO] = RandomNotificationIdSource()

      notify = Notify[IO](catalogue, directory, senders, repository, retry, time, ids)
      getDelivery = GetDelivery[IO](repository)
      registerDevice = RegisterDevice[IO](devices)
      forgetDevice = ForgetDevice[IO](devices)

      service <- NotificationServiceFs2Grpc.bindServiceResource[IO](
        GrpcNotificationServer(notify, getDelivery, registerDevice, forgetDevice)
      )
      serverTls <- Resource.eval(ServiceIdentity.server(settings.pkiDir))
      _ <- NettyServerBuilder
        .forPort(settings.grpcPort)
        .sslContext(serverTls)
        .intercept(PeerAuthorizationInterceptor(settings.allowedPeers))
        .addService(service)
        .resource[IO]
        .evalMap(server => IO(server.start()))
    yield ()

  private def database(settings: Settings): Resource[IO, HikariTransactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "org.postgresql.Driver",
      url = settings.database.url,
      user = settings.database.user,
      pass = settings.database.password,
      connectEC = scala.concurrent.ExecutionContext.global
    )

  private def identityClient(
    settings: Settings
  ): Resource[IO, IdentityServiceFs2Grpc[IO, Metadata]] =
    for
      clientTls <- Resource.eval(ServiceIdentity.client(settings.pkiDir))
      channel <- NettyChannelBuilder
        .forTarget(settings.identityGrpcUrl)
        .sslContext(clientTls)
        .resource[IO]
      stub <- IdentityServiceFs2Grpc.stubResource[IO](channel)
    yield stub

  private def uri(name: String, raw: String): IO[Uri] =
    IO.fromEither(
      Uri.fromString(raw).left.map(_ => IllegalStateException(s"$name is not a URL: $raw"))
    )
