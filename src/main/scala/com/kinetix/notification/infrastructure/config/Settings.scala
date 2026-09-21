package com.kinetix.notification.infrastructure.config

import cats.effect.IO
import cats.syntax.all.*

final case class Settings(
    grpcPort: Int,
    httpPort: Int,
    identityGrpcUrl: String,
    databaseUrl: String,
    databaseUser: String,
    databasePassword: String,
    pushProviderUrl: String,
    pushProviderKey: String,
    emailProviderUrl: String,
    emailProviderKey: String,
    emailFromAddress: String,
    pkiDir: String,
    allowedPeers: Set[String]
)

object Settings:

  def load: IO[Settings] =
    for
      grpcPort <- int("GRPC_PORT")
      httpPort <- int("HTTP_PORT")
      identity <- required("IDENTITY_GRPC_URL")
      dbUrl <- required("DATABASE_URL")
      dbUser <- required("DB_USERNAME")
      dbPassword <- required("DB_PASSWORD")
      pushUrl <- required("PUSH_PROVIDER_URL")
      pushKey <- required("PUSH_PROVIDER_KEY")
      emailUrl <- required("EMAIL_PROVIDER_URL")
      emailKey <- required("EMAIL_PROVIDER_KEY")
      emailFrom <- required("EMAIL_FROM_ADDRESS")
      pki <- required("KINETIX_PKI_DIR")
      peers <- required("KINETIX_GRPC_ALLOWED_PEERS")
      allowed = peers.split(',').map(_.trim).filter(_.nonEmpty).toSet
      _ <- IO.raiseUnless(allowed.nonEmpty)(
        new IllegalStateException("KINETIX_GRPC_ALLOWED_PEERS is set but names no services.")
      )
    yield Settings(
      grpcPort,
      httpPort,
      identity,
      dbUrl,
      dbUser,
      dbPassword,
      pushUrl,
      pushKey,
      emailUrl,
      emailKey,
      emailFrom,
      pki,
      allowed
    )

  private def required(name: String): IO[String] =
    IO(sys.env.get(name).map(_.trim).filter(_.nonEmpty)).flatMap:
      case Some(value) => IO.pure(value)
      case None =>
        IO.raiseError(new IllegalStateException(s"$name is required and has no default."))

  private def int(name: String): IO[Int] =
    required(name).flatMap: raw =>
      raw.toIntOption match
        case Some(port) => IO.pure(port)
        case None =>
          IO.raiseError(new IllegalStateException(s"$name is '$raw', which is not a port number."))
