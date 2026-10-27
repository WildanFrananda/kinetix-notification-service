package com.kinetix.notification.infrastructure.http

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.domain.{Address, EmailAddress, Message, NotificationError}
import com.kinetix.notification.fakes.{FixedTime, RecordingLogger}
import com.kinetix.notification.infrastructure.grpc.Codes
import com.kinetix.notification.infrastructure.observability.Metrics

class HttpEmailSenderSuite extends BoundedSuite:
  private val endpoint = Uri.unsafeFromString("https://api.resend.com/emails")
  private val from = EmailAddress.fromString("orders@kinetix.test").getOrElse(fail("bad sender"))
  private val to =
    Address.Email(EmailAddress.fromString("buyer@kinetix.test").getOrElse(fail("bad")))
  private val message = Message("Order packed", "Order ORD-1 is packed.")
  private val now = Instant.parse("2026-10-21T10:00:00Z")

  private def resend(status: Int, name: String, text: String): String =
    s"""{"statusCode":$status,"name":"$name","message":"$text"}"""

  private val rateLimited = resend(429, "rate_limit_exceeded", "Too many requests.")
  private val dailyQuota = resend(429, "daily_quota_exceeded", "You have reached your daily quota.")
  private val missingKey =
    resend(401, "missing_api_key", "Missing API key in the authorization header")
  private val invalidKey = resend(401, "validation_error", "API key is invalid")
  private val inactiveKey = resend(403, "restricted_api_key", "API key is not active.")
  private val unverifiedDomain =
    resend(403, "validation_error", "The kinetix.test domain is not verified.")
  private val missingField = resend(422, "missing_required_field", "Missing `to` field.")
  private val invalidField = resend(400, "validation_error", "Invalid `to` field.")
  private val broken = resend(500, "application_error", "An unexpected error occurred.")

  private def answering(status: Status, body: String, pause: Option[String] = None): Client[IO] =
    val answer = Response[IO](status).withEntity(body)

    Client.fromHttpApp(
      HttpApp[IO](_ =>
        IO.pure(
          pause.fold(answer)(asked => answer.putHeaders(Header.Raw(CIString("Retry-After"), asked)))
        )
      )
    )

  private def mailing(
    client: Client[IO],
    logger: Logger[IO] = NoOpLogger[IO],
    metrics: IO[Metrics] = Metrics.create("kinetix-notification-service", "test")
  ): IO[HttpEmailSender] =
    metrics.map(counted =>
      HttpEmailSender(client, endpoint, "re_test_key", from, FixedTime(now), counted, logger)
    )

  private def verdict(status: Status, body: String): IO[Either[NotificationError, Unit]] =
    mailing(answering(status, body)).flatMap(_.send(to, message))

  private def judged(
    answers: List[(Status, String)]
  ): IO[List[(String, Boolean)]] =
    answers.traverse: (status, body) =>
      verdict(status, body).map:
        case Left(error) => (Codes.of(error), error.isTransient)
        case Right(_)    => ("DELIVERED", false)

  test("Resend's 429 is a rate or a quota: unavailable to the caller, and worth repeating") {
    judged(List(Status.TooManyRequests -> rateLimited, Status.TooManyRequests -> dailyQuota)).map:
      verdicts => assertEquals(verdicts, List.fill(2)(("SENDER_UNAVAILABLE", true)))
  }

  test("Resend's 401 and 403 are this service's key or sender: unavailable, and not repeated") {
    judged(
      List(
        Status.Unauthorized -> missingKey,
        Status.Unauthorized -> invalidKey,
        Status.Forbidden -> inactiveKey,
        Status.Forbidden -> unverifiedDomain
      )
    ).map(verdicts => assertEquals(verdicts, List.fill(4)(("SENDER_UNAVAILABLE", false))))
  }

  test("any other refusal stays a rejection, and a 5xx stays a provider that did not answer") {
    judged(
      List(
        Status.UnprocessableEntity -> missingField,
        Status.BadRequest -> invalidField,
        Status.InternalServerError -> broken
      )
    ).map: verdicts =>
      assertEquals(
        verdicts,
        List(("SENDER_REJECTED", false), ("SENDER_REJECTED", false), ("SENDER_UNAVAILABLE", true))
      )
  }

  test("a 429 carries Resend's retry-after; unsaid, Resend's one-second window; at most an hour") {
    def retryAt(pause: Option[String]): IO[Option[Instant]] =
      mailing(answering(Status.TooManyRequests, rateLimited, pause))
        .flatMap(_.send(to, message))
        .map:
          case Left(NotificationError.SenderThrottled(_, at, _)) => Some(at)
          case _                                                 => None

    for
      stated <- retryAt(Some("2"))
      unsaid <- retryAt(None)
      excessive <- retryAt(Some("86400"))
    yield
      assertEquals(stated, Some(now.plusSeconds(2)))
      assertEquals(unsaid, Some(now.plusSeconds(1)))
      assertEquals(excessive, Some(now.plusSeconds(3600)))
  }

  test("a refused key is logged at ERROR naming the setting to check, a rate at WARN") {
    def logged(status: Status, body: String): IO[List[(String, String)]] =
      for
        logger <- RecordingLogger.empty
        sender <- mailing(answering(status, body), logger = logger)
        _ <- sender.send(to, message)
        lines <- logger.lines.get
      yield lines

    for
      key <- logged(Status.Forbidden, unverifiedDomain)
      rate <- logged(Status.TooManyRequests, rateLimited)
    yield
      assertEquals(key.map(_._1), List("ERROR"))
      assert(key.head._2.contains("EMAIL_PROVIDER_KEY"), key.head._2)
      assert(key.head._2.contains("not verified"), key.head._2)
      assertEquals(rate.map(_._1), List("WARN"))
      assert(rate.head._2.contains("a pause of 1 s"), rate.head._2)
  }

  test("every failed email is counted under the email channel and its reason") {
    val answers = List(
      Status.Ok -> "{}",
      Status.TooManyRequests -> rateLimited,
      Status.Unauthorized -> missingKey,
      Status.UnprocessableEntity -> missingField,
      Status.InternalServerError -> broken
    )

    for
      metrics <- Metrics.create("kinetix-notification-service", "test")
      _ <- answers.traverse_ { (status, body) =>
        mailing(answering(status, body), metrics = IO.pure(metrics)).flatMap(_.send(to, message))
      }
      scraped <- metrics.scrape
    yield List("quota", "credential", "rejected", "unavailable").foreach: reason =>
      assert(
        scraped.contains(
          s"""kinetix_notification_provider_failures_total{channel="email",reason="$reason"} 1"""
        ),
        scraped
      )
  }
