package com.kinetix.notification.infrastructure.http

import java.net.URI

import cats.effect.IO
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{
  JWKSource,
  JWKSourceBuilder,
  RateLimitReachedException,
  URLBasedJWKSetSource
}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext, SimpleSecurityContext}
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}

import com.kinetix.notification.domain.PrincipalId

final class NimbusAccessTokenVerifier(
  processor: DefaultJWTProcessor[SecurityContext],
  lastKeyReadAnswered: IO[Boolean]
) extends AccessTokenVerifier:
  def verify(token: String): IO[TokenVerdict] =
    IO.blocking(processor.process(token, SimpleSecurityContext()))
      .attempt
      .flatMap:
        case Right(claims) =>
          IO.pure(
            PrincipalId
              .fromString(Option(claims.getSubject).getOrElse(""))
              .fold(TokenVerdict.Invalid("the token names no principal"))(TokenVerdict.Valid(_))
          )
        case Left(_: RateLimitReachedException) =>
          lastKeyReadAnswered.map: answered =>
            if answered then
              TokenVerdict.Invalid(
                "no key in the key set identity served moments ago matches the token"
              )
            else
              TokenVerdict.Unverifiable(
                "identity's key set was read too recently to read again, and that read failed"
              )
        case Left(failure: KeySourceException) =>
          IO.pure(TokenVerdict.Unverifiable(described(failure)))
        case Left(failure) =>
          IO.pure(TokenVerdict.Invalid(described(failure)))

  private def described(failure: Throwable): String =
    Option(failure.getMessage).filter(_.nonEmpty).getOrElse(failure.getClass.getName)

object NimbusAccessTokenVerifier:
  def over(
    keys: JWKSource[SecurityContext],
    issuer: String,
    audience: String,
    lastKeyReadAnswered: IO[Boolean]
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

    NimbusAccessTokenVerifier(processor, lastKeyReadAnswered)

  def remote(jwksUrl: String, issuer: String, audience: String): IO[NimbusAccessTokenVerifier] =
    IO(URI.create(jwksUrl).toURL).map: url =>
      val fetched = WitnessedJWKSetSource(
        URLBasedJWKSetSource[SecurityContext](
          url,
          DefaultResourceRetriever(
            JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT,
            JWKSourceBuilder.DEFAULT_HTTP_READ_TIMEOUT,
            JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT
          )
        )
      )
      val keys = JWKSourceBuilder.create[SecurityContext](fetched).retrying(true).build()

      over(keys, issuer, audience, fetched.lastReadAnswered)
