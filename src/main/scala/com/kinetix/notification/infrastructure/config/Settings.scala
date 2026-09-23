package com.kinetix.notification.infrastructure.config

import cats.effect.IO

final case class Settings(
  grpcPort: Int,
  httpPort: Int,
  identityGrpcUrl: String,
  database: DatabaseSettings,
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
      grpcPort <- Env.int("GRPC_PORT")
      httpPort <- Env.int("HTTP_PORT")
      identity <- Env.required("IDENTITY_GRPC_URL")
      database <- DatabaseSettings.load
      pushUrl <- Env.required("PUSH_PROVIDER_URL")
      pushKey <- Env.required("PUSH_PROVIDER_KEY")
      emailUrl <- Env.required("EMAIL_PROVIDER_URL")
      emailKey <- Env.required("EMAIL_PROVIDER_KEY")
      emailFrom <- Env.required("EMAIL_FROM_ADDRESS")
      pki <- Env.required("KINETIX_PKI_DIR")
      peers <- Env.required("KINETIX_GRPC_ALLOWED_PEERS")
      allowed = peers.split(',').map(_.trim).filter(_.nonEmpty).toSet
      _ <- IO.raiseUnless(allowed.nonEmpty)(
        new IllegalStateException("KINETIX_GRPC_ALLOWED_PEERS is set but names no services.")
      )
    yield Settings(
      grpcPort,
      httpPort,
      identity,
      database,
      pushUrl,
      pushKey,
      emailUrl,
      emailKey,
      emailFrom,
      pki,
      allowed
    )
