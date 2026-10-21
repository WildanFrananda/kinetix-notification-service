package com.kinetix.notification.infrastructure.http

import java.time.Instant
import java.util.Date

import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.{ImmutableJWKSet, JWKSource}
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, KeySourceException}
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import cats.effect.IO
import cats.syntax.all.*

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.domain.PrincipalId
import com.kinetix.notification.fakes.JwksServer

class NimbusAccessTokenVerifierSuite extends BoundedSuite:
  private val issuer = "https://identity.kinetix.test"
  private val audience = "kinetix"
  private val principal = "11111111-2222-3333-4444-555555555555"

  private val signing: RSAKey = RSAKeyGenerator(2048).keyID("identity-1").generate()
  private val stranger: RSAKey = RSAKeyGenerator(2048).keyID("identity-1").generate()
  private val retired: RSAKey = RSAKeyGenerator(2048).keyID("identity-retired").generate()

  private val verifier = NimbusAccessTokenVerifier.over(
    ImmutableJWKSet[SecurityContext](JWKSet(signing.toPublicJWK)),
    issuer,
    audience,
    IO.pure(true)
  )

  private def claims(
    use: String = "access",
    from: String = issuer,
    to: String = audience,
    expires: Instant = Instant.now().plusSeconds(600)
  ): JWTClaimsSet =
    JWTClaimsSet
      .Builder()
      .subject(principal)
      .jwtID("jti-1")
      .issuer(from)
      .audience(to)
      .issueTime(Date.from(Instant.now()))
      .expirationTime(Date.from(expires))
      .claim("token_use", use)
      .build()

  private def signed(body: JWTClaimsSet, key: RSAKey = signing): String =
    val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).build(), body)
    token.sign(RSASSASigner(key))
    token.serialize()

  private def isInvalid(verdict: TokenVerdict): Boolean = verdict match
    case TokenVerdict.Invalid(_) => true
    case _                       => false

  private def isUnverifiable(verdict: TokenVerdict): Boolean = verdict match
    case TokenVerdict.Unverifiable(_) => true
    case _                            => false

  test("an access token identity signed names its principal") {
    verifier
      .verify(signed(claims()))
      .map(verdict =>
        assertEquals(verdict, TokenVerdict.Valid(PrincipalId.fromString(principal).get))
      )
  }

  test("a refresh token is not an access token") {
    verifier.verify(signed(claims(use = "refresh"))).map(verdict => assert(isInvalid(verdict)))
  }

  test("a token from another issuer, or for another audience, is refused") {
    for
      wrongIssuer <- verifier.verify(signed(claims(from = "https://elsewhere.test")))
      wrongAudience <- verifier.verify(signed(claims(to = "someone-else")))
    yield
      assert(isInvalid(wrongIssuer))
      assert(isInvalid(wrongAudience))
  }

  test("an expired token is refused") {
    verifier
      .verify(signed(claims(expires = Instant.now().minusSeconds(3600))))
      .map(verdict => assert(isInvalid(verdict)))
  }

  test("a key with the right id but the wrong material is refused") {
    verifier.verify(signed(claims(), stranger)).map(verdict => assert(isInvalid(verdict)))
  }

  test("an unsigned token and an HMAC token keyed with the public key are refused") {
    val plain = PlainJWT(claims()).serialize()
    val hmac =
      SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("identity-1").build(), claims())
    hmac.sign(MACSigner(signing.toPublicJWK.toRSAPublicKey.getEncoded))

    for
      unsigned <- verifier.verify(plain)
      confused <- verifier.verify(hmac.serialize())
    yield
      assert(isInvalid(unsigned), s"alg none was accepted: $unsigned")
      assert(isInvalid(confused), s"HS256 over the public key was accepted: $confused")
  }

  test("signing keys that cannot be fetched make the token unverifiable, not invalid") {
    val unreachable = new JWKSource[SecurityContext]:
      def get(
        selector: com.nimbusds.jose.jwk.JWKSelector,
        context: SecurityContext
      ): java.util.List[com.nimbusds.jose.jwk.JWK] =
        throw KeySourceException("connect timed out")

    NimbusAccessTokenVerifier
      .over(unreachable, issuer, audience, IO.pure(false))
      .verify(signed(claims()))
      .map(verdict => assert(isUnverifiable(verdict), s"an outage was reported as $verdict"))
  }

  test("garbage is invalid") {
    verifier.verify("not.a.token").map(verdict => assert(isInvalid(verdict)))
  }

  test("a key identity does not publish is a bad token every time, not an outage from the second") {
    JwksServer
      .serving(JWKSet(signing.toPublicJWK).toString)
      .use: identity =>
        for
          remote <- NimbusAccessTokenVerifier.remote(identity.url, issuer, audience)
          verdicts <- List.fill(4)(signed(claims(), retired)).traverse(remote.verify)
          valid <- remote.verify(signed(claims()))
        yield
          assert(
            verdicts.forall(isInvalid),
            s"identity answered all ${identity.fetches} reads, and the verdicts were $verdicts"
          )
          assertEquals(valid, TokenVerdict.Valid(PrincipalId.fromString(principal).get))
  }

  test("identity going quiet stays an outage, however often its key set was asked for") {
    JwksServer
      .serving(JWKSet(signing.toPublicJWK).toString)
      .use: identity =>
        for
          remote <- NimbusAccessTokenVerifier.remote(identity.url, issuer, audience)
          first <- remote.verify(signed(claims()))
          _ <- identity.goQuiet
          verdicts <- List.fill(4)(signed(claims(), retired)).traverse(remote.verify)
        yield
          assertEquals(first, TokenVerdict.Valid(PrincipalId.fromString(principal).get))
          assert(verdicts.forall(isUnverifiable), s"an outage was reported as $verdicts")
  }
