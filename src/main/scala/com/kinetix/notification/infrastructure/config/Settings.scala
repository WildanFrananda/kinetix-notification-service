package com.kinetix.notification.infrastructure.config

import cats.effect.IO

import com.kinetix.notification.domain.TemplateGrants
import com.kinetix.notification.infrastructure.grpc.{PeerAllowList, TemplateGrantList}

final case class Settings(
  grpcPort: Int,
  httpPort: Int,
  identityGrpcUrl: String,
  identityJwksUrl: String,
  jwtIssuer: String,
  jwtAudience: String,
  database: DatabaseSettings,
  pushProviderUrl: String,
  pushCredentialsB64: String,
  emailProviderUrl: String,
  emailProviderKey: String,
  emailFromAddress: String,
  pkiDir: String,
  allowedPeers: PeerAllowList,
  templateGrants: TemplateGrants
)

object Settings:
  val AllowedPeers: String = "KINETIX_GRPC_ALLOWED_PEERS"
  val TemplateGrantsVariable: String = "KINETIX_NOTIFICATION_TEMPLATE_GRANTS"

  def load: IO[Settings] =
    for
      grpcPort <- Env.int("GRPC_PORT")
      httpPort <- Env.int("HTTP_PORT")
      identity <- Env.required("IDENTITY_GRPC_URL")
      jwks <- Env.required("IDENTITY_JWKS_URL")
      issuer <- Env.required("JWT_ISSUER")
      audience <- Env.required("JWT_AUDIENCE")
      database <- DatabaseSettings.load
      pushUrl <- Env.required("PUSH_PROVIDER_URL")
      pushCredentials <- Env.required("PUSH_PROVIDER_CREDENTIALS_B64")
      emailUrl <- Env.required("EMAIL_PROVIDER_URL")
      emailKey <- Env.required("EMAIL_PROVIDER_KEY")
      emailFrom <- Env.required("EMAIL_FROM_ADDRESS")
      pki <- Env.required("KINETIX_PKI_DIR")
      peers <- Env.declared(AllowedPeers)
      allowed <- parsed(PeerAllowList.parse(AllowedPeers, peers))
      templates <- Env.declared(TemplateGrantsVariable)
      grants <- parsed(TemplateGrantList.parse(TemplateGrantsVariable, templates))
    yield Settings(
      grpcPort,
      httpPort,
      identity,
      jwks,
      issuer,
      audience,
      database,
      pushUrl,
      pushCredentials,
      emailUrl,
      emailKey,
      emailFrom,
      pki,
      allowed,
      grants
    )

  private def parsed[A](result: Either[String, A]): IO[A] =
    IO.fromEither(result.left.map(IllegalStateException(_)))
