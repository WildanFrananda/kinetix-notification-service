package com.kinetix.notification

import scala.jdk.CollectionConverters.*

import cats.effect.std.Dispatcher
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.comcast.ip4s.{Port, ipv4}
import doobie.hikari.HikariTransactor
import fs2.grpc.syntax.all.*
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.{NettyChannelBuilder, NettyServerBuilder}
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import identity.v1.identity.{IdentityServiceFs2Grpc, IdentityServiceGrpc}
import notification.v1.notification.NotificationServiceFs2Grpc

import com.kinetix.notification.application.*
import com.kinetix.notification.domain.{Caller, EmailAddress}
import com.kinetix.notification.domain.ports.*
import com.kinetix.notification.infrastructure.{
  JsonMessageCatalogue,
  RandomNotificationIdSource,
  SecureRandomNonceSource,
  ServiceIdentity,
  SystemTimeSource
}
import com.kinetix.notification.infrastructure.config.Settings
import com.kinetix.notification.infrastructure.grpc.*
import com.kinetix.notification.infrastructure.http.{
  DeviceApi,
  GoogleAccessTokens,
  GoogleServiceAccount,
  HttpApi,
  HttpEmailSender,
  HttpMetrics,
  HttpPushSender,
  NimbusAccessTokenVerifier
}
import com.kinetix.notification.infrastructure.observability.{MetricKey, Metrics}
import com.kinetix.notification.infrastructure.persistence.{
  PostgresDeviceRegistry,
  PostgresNotificationRepository,
  PostgresReadiness
}
import com.kinetix.notification.infrastructure.retry.ExponentialBackoffRetry

object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Settings.load.flatMap(serve)

  private def serve(settings: Settings): IO[ExitCode] =
    resources(settings).use(_ => IO.never).as(ExitCode.Success)

  private def resources(settings: Settings): Resource[IO, Unit] =
    for
      dispatcher <- Dispatcher.parallel[IO]
      metrics <- Resource.eval(Metrics.create(Main.ServiceName, Main.version))
      logger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO](Main.ServiceName)

      transactor <- database(settings)
      httpClient <- EmberClientBuilder.default[IO].build
      identity <- identityClient(settings, metrics, dispatcher)

      repository: NotificationRepository[IO] = PostgresNotificationRepository(transactor, logger)
      devices: DeviceRegistry[IO] = PostgresDeviceRegistry(transactor, logger)

      accessTokens <- Resource.eval(
        NimbusAccessTokenVerifier.remote(
          settings.identityJwksUrl,
          settings.jwtIssuer,
          settings.jwtAudience
        )
      )

      emailFrom <- Resource.eval(
        IO.fromOption(EmailAddress.fromString(settings.emailFromAddress))(
          IllegalStateException(
            s"EMAIL_FROM_ADDRESS is '${settings.emailFromAddress}', which is not an email address."
          )
        )
      )
      pushEndpoint <- Resource.eval(uri("PUSH_PROVIDER_URL", settings.pushProviderUrl))
      emailEndpoint <- Resource.eval(uri("EMAIL_PROVIDER_URL", settings.emailProviderUrl))

      pushAccount <- Resource.eval(
        GoogleServiceAccount
          .fromBase64("PUSH_PROVIDER_CREDENTIALS_B64", settings.pushCredentialsB64)
      )
      pushTokens <- Resource.eval(GoogleAccessTokens.create(httpClient, pushAccount))

      time: TimeSource[IO] = SystemTimeSource()
      push = HttpPushSender(httpClient, pushEndpoint, pushTokens.token, time, logger)
      senders: List[NotificationSender[IO]] = List(
        push,
        HttpEmailSender(httpClient, emailEndpoint, settings.emailProviderKey, emailFrom, logger)
      )

      directory: RecipientDirectory[IO] = CompositeRecipientDirectory(
        List(
          GrpcIdentityRecipientDirectory(identity, logger),
          DeviceRegistryRecipientDirectory(devices)
        )
      )

      catalogue <- Resource.eval(JsonMessageCatalogue.load())

      backoffRetry = ExponentialBackoffRetry.default[IO]
      retry: RetryPolicy[IO] = backoffRetry
      ids: NotificationIdSource[IO] = RandomNotificationIdSource()
      nonces <- Resource.eval(SecureRandomNonceSource.create)

      notify = Notify[IO](
        catalogue,
        settings.templateGrants,
        directory,
        devices,
        senders,
        repository,
        retry,
        time,
        ids
      )
      getDelivery = GetDelivery[IO](repository)
      registerDevice = RegisterDevice[IO](
        devices,
        push,
        nonces,
        retry,
        time,
        settings.deviceLimit
      )
      verifyDevice = VerifyDevice[IO](devices, time, settings.deviceLimit)
      forgetOwnDevice = ForgetOwnDevice[IO](devices)

      service <- NotificationServiceFs2Grpc.serviceResource[IO, Caller](
        GrpcNotificationServer(notify, getDelivery, registerDevice),
        CallerHeader.read
      )
      _ <- Resource.eval(grantsNameWhatIsServed(settings, service.getServiceDescriptor))
      _ <- Resource.eval(
        logger.info(
          s"gRPC callers: ${settings.allowedPeers.summary}; " +
            s"templates: ${TemplateGrantList.summary(settings.templateGrants)}; " +
            s"devices per principal: ${settings.deviceLimit.value}"
        )
      )
      serverTls <- Resource.eval(ServiceIdentity.server(settings.pkiDir))
      _ <- NettyServerBuilder
        .forPort(settings.grpcPort)
        .sslContext(serverTls)
        .intercept(PeerAuthorizationInterceptor.spiffe(settings.allowedPeers))
        .intercept(ServerMetricsInterceptor(metrics, dispatcher))
        .addService(service)
        .addService(ProtoReflectionServiceV1.newInstance())
        .resource[IO]
        .evalMap(server => IO(server.start()))

      _ <- Resource.eval(
        metrics.seed(
          seeds(service.getServiceDescriptor.getMethods.asScala.toList.map(_.getFullMethodName))
        )
      )

      devicesApi = DeviceApi(
        accessTokens,
        registerDevice,
        verifyDevice,
        forgetOwnDevice,
        backoffRetry.backoff,
        time,
        logger
      )

      httpPort <- Resource.eval(
        IO.fromOption(Port.fromInt(settings.httpPort))(
          IllegalStateException(s"HTTP_PORT is ${settings.httpPort}, which is not a port number.")
        )
      )
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(httpPort)
        .withHttpApp(
          HttpMetrics(
            metrics,
            HttpApi(metrics, PostgresReadiness.check(transactor), devicesApi)
          )
        )
        .build
    yield ()

  private def seeds(grpcMethods: List[String]): List[MetricKey] =
    val http = HttpApi.Probes.flatMap: route =>
      List(
        MetricKey(
          Metrics.HttpRequests,
          List("method" -> "GET", "route" -> route, "status" -> "200")
        ),
        MetricKey(Metrics.HttpDuration, List("method" -> "GET", "route" -> route))
      )
    val served = grpcMethods.map: method =>
      MetricKey(Metrics.GrpcServerCalls, List("grpc_method" -> method, "grpc_code" -> "OK"))
    val called = List(
      MetricKey(
        Metrics.GrpcClientCalls,
        List(
          "peer" -> Main.IdentityPeer,
          "grpc_method" -> IdentityServiceGrpc.METHOD_GET_USER_PROFILE.getFullMethodName,
          "grpc_code" -> "OK"
        )
      )
    )
    http ++ served ++ called

  private def grantsNameWhatIsServed(
    settings: Settings,
    served: io.grpc.ServiceDescriptor
  ): IO[Unit] =
    val names = served.getMethods.asScala.map(_.getFullMethodName).toSet + served.getName
    val unknown = settings.allowedPeers.unknownScopes(names)

    IO.raiseUnless(unknown.isEmpty)(
      IllegalStateException(
        s"${Settings.AllowedPeers} grants ${unknown.mkString(", ")}, which this server does not " +
          s"serve; it serves ${names.toList.sorted.mkString(", ")}."
      )
    )

  private def database(settings: Settings): Resource[IO, HikariTransactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "org.postgresql.Driver",
      url = settings.database.url,
      user = settings.database.user,
      pass = settings.database.password,
      connectEC = scala.concurrent.ExecutionContext.global
    )

  private def identityClient(
    settings: Settings,
    metrics: Metrics,
    dispatcher: Dispatcher[IO]
  ): Resource[IO, IdentityServiceFs2Grpc[IO, Metadata]] =
    for
      clientTls <- Resource.eval(ServiceIdentity.client(settings.pkiDir))
      channel <- NettyChannelBuilder
        .forTarget(settings.identityGrpcUrl)
        .sslContext(clientTls)
        .intercept(ClientMetricsInterceptor(metrics, dispatcher, Main.IdentityPeer))
        .resource[IO]
      stub <- IdentityServiceFs2Grpc.stubResource[IO](channel)
    yield stub

  private def uri(name: String, raw: String): IO[Uri] =
    IO.fromEither(
      Uri.fromString(raw).left.map(_ => IllegalStateException(s"$name is not a URL: $raw"))
    )

  val ServiceName: String = "kinetix-notification-service"
  val IdentityPeer: String = "identity"

  def version: String =
    sys.env.get("KINETIX_SERVICE_VERSION").map(_.trim).filter(_.nonEmpty).getOrElse("unknown")
