package com.kinetix.notification.infrastructure.http

import java.net.URI

import cats.effect.IO
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext, SimpleSecurityContext}
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}

import com.kinetix.notification.domain.PrincipalId

final class NimbusAccessTokenVerifier(processor: DefaultJWTProcessor[SecurityContext])
  extends AccessTokenVerifier:
  def verify(token: String): IO[TokenVerdict] =
    IO.blocking(processor.process(token, SimpleSecurityContext()))
      .attempt
      .map:
        case Right(claims) =>
          PrincipalId
            .fromString(Option(claims.getSubject).getOrElse(""))
            .fold(TokenVerdict.Invalid("the token names no principal"))(TokenVerdict.Valid(_))
        case Left(failure: KeySourceException) =>
          TokenVerdict.Unverifiable(failure.getMessage)
        case Left(failure) =>
          TokenVerdict.Invalid(failure.getMessage)

object NimbusAccessTokenVerifier:
  def over(
    keys: JWKSource[SecurityContext],
    issuer: String,
    audience: String
  ): NimbusAccessTokenVerifier =
    val processor = DefaultJWTProcessor[SecurityContext]()

    processor.setJWSKeySelector(
      JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, keys)
    )
    processor.setJWTClaimsSetVerifier(
      DefaultJWTClaimsVerifier[SecurityContext](
        audience,
        JWTClaimsSet.Builder().issuer(issuer).claim("token_use", "access").build(),
        java.util.Set.of("sub", "exp", "iat", "jti")
      )
    )

    NimbusAccessTokenVerifier(processor)

  def remote(jwksUrl: String, issuer: String, audience: String): IO[NimbusAccessTokenVerifier] =
    IO(URI.create(jwksUrl).toURL).map: url =>
      over(JWKSourceBuilder.create[SecurityContext](url).retrying(true).build(), issuer, audience)
