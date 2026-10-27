package com.kinetix.notification.infrastructure.http

import java.nio.charset.StandardCharsets.UTF_8
import java.security.Signature
import java.time.Instant
import java.util.Base64

import scala.concurrent.duration.*

import cats.effect.{Clock, IO, Ref}
import org.http4s.*
import org.http4s.client.Client

final class GoogleAccessTokens private (
  client: Client[IO],
  account: GoogleServiceAccount,
  scope: String,
  held: Ref[IO, Option[HeldToken]]
) extends BearerTokens:
  val token: IO[String] =
    for
      now <- Clock[IO].realTimeInstant
      current <- held.get
      value <- current match
        case Some(existing) if existing.usableAt(now) => IO.pure(existing.value)
        case _                                        => refresh(now)
    yield value

  def discard(refused: String): IO[Unit] =
    held.update(_.filterNot(_.value == refused))

  private def refresh(now: Instant): IO[String] =
    for
      granted <- exchange(now)
      _ <- held.set(
        Some(HeldToken(granted.access_token, now.plusSeconds(granted.expires_in.toLong)))
      )
    yield granted.access_token

  private def exchange(now: Instant): IO[GrantedToken] =
    val tokenUri = Uri.unsafeFromString(account.tokenUri)
    val form = UrlForm(
      "grant_type" -> "urn:ietf:params:oauth:grant-type:jwt-bearer",
      "assertion" -> assertion(now)
    )

    client.expect[GrantedToken](Request[IO](Method.POST, tokenUri).withEntity(form))

  private def assertion(now: Instant): String =
    val issued = now.getEpochSecond
    val header = """{"alg":"RS256","typ":"JWT"}"""
    val claims =
      s"""{"iss":"${account.clientEmail}","scope":"$scope","aud":"${account.tokenUri}",""" +
        s""""iat":$issued,"exp":${issued + 3600}}"""

    val signing = s"${base64Url(header.getBytes(UTF_8))}.${base64Url(claims.getBytes(UTF_8))}"
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(account.privateKey)
    signer.update(signing.getBytes(UTF_8))

    s"$signing.${base64Url(signer.sign())}"

  private def base64Url(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

object GoogleAccessTokens:
  val MessagingScope: String = "https://www.googleapis.com/auth/firebase.messaging"

  val Margin: FiniteDuration = 60.seconds

  def create(
    client: Client[IO],
    account: GoogleServiceAccount,
    scope: String = MessagingScope
  ): IO[GoogleAccessTokens] =
    Ref.of[IO, Option[HeldToken]](None).map(new GoogleAccessTokens(client, account, scope, _))
