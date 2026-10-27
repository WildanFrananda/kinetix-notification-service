package com.kinetix.notification.infrastructure.http

import java.time.Instant

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.fakes.{FixedTime, RecordingLogger, RenewableBearer}
import com.kinetix.notification.domain.{
  Address,
  DeviceNonce,
  DeviceToken,
  Message,
  NotificationError
}
import com.kinetix.notification.infrastructure.grpc.Codes
import com.kinetix.notification.infrastructure.observability.Metrics

class HttpPushSenderSuite extends BoundedSuite:
  private val endpoint =
    Uri.unsafeFromString("https://fcm.googleapis.com/v1/projects/kinetix-push/messages:send")

  private val to = Address.Push(DeviceToken.fromString("handset-1").getOrElse(fail("bad token")))
  private val message = Message("Order packed", "Order ORD-1 is packed.")
  private val now = Instant.parse("2026-10-21T10:00:00Z")
  private val clock = FixedTime(now)

  private def pushing(
    client: Client[IO],
    bearer: IO[RenewableBearer] = RenewableBearer.start,
    logger: Logger[IO] = NoOpLogger[IO],
    metrics: IO[Metrics] = Metrics.create("kinetix-notification-service", "test")
  ): IO[HttpPushSender] =
    (bearer, metrics).mapN((tokens, counted) =>
      HttpPushSender(client, endpoint, tokens, clock, counted, logger)
    )

  private def recording(seen: Ref[IO, List[String]], answer: IO[Response[IO]]): Client[IO] =
    Client.fromHttpApp(
      HttpApp[IO]: request =>
        seen
          .update(
            _ :+ request.headers.get[headers.Authorization].fold("none")(_.credentials.toString)
          )
          .flatMap(_ => answer)
    )

  test(
    "the credential on the wire is the one the token source holds now, not the one at start-up"
  ) {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      tokens <- RenewableBearer.start
      sender <- pushing(recording(seen, Ok("{}")), IO.pure(tokens))
      _ <- sender.send(to, message)
      _ <- tokens.discard("token-1")
      _ <- sender.send(to, message)
      sent <- seen.get
    yield assertEquals(sent, List("Bearer token-1", "Bearer token-2"))
  }

  test("a provider that refuses is a rejection, and is not worth repeating") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      sender <- pushing(recording(seen, BadRequest("""{"error":"registration token not valid"}""")))
      result <- sender.send(to, message)
    yield result match
      case Left(error: NotificationError.SenderRejected) => assert(!error.isTransient)
      case other => fail(s"a 400 should be a rejection, not $other")
  }

  test("a provider that did not answer is unavailable, and is worth repeating") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      sender <- pushing(recording(seen, ServiceUnavailable("busy")))
      result <- sender.send(to, message)
    yield result match
      case Left(error: NotificationError.SenderUnavailable) => assert(error.isTransient)
      case other => fail(s"a 503 should be unavailable, not $other")
  }

  private def answering(status: Status, body: String): Client[IO] =
    Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](status).withEntity(body))))

  private def verdict(status: Status, body: String): IO[Either[NotificationError, Unit]] =
    pushing(answering(status, body)).flatMap(_.send(to, message))

  private def isGone(result: Either[NotificationError, Unit]): Boolean = result match
    case Left(NotificationError.AddressGone(_, _)) => true
    case _                                         => false

  private def isRejected(result: Either[NotificationError, Unit]): Boolean = result match
    case Left(NotificationError.SenderRejected(_, _)) => true
    case _                                            => false

  private val unregistered =
    """{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND",""" +
      """"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
      """"errorCode":"UNREGISTERED"}]}}"""

  private val invalidToken =
    """{"error":{"code":400,""" +
      """"message":"The registration token is not a valid FCM registration token",""" +
      """"status":"INVALID_ARGUMENT","details":[""" +
      """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
      """"errorCode":"INVALID_ARGUMENT"}]}}"""

  private val invalidDataValue =
    """{"error":{"code":400,""" +
      """"message":"Invalid value at 'message.data[0].value' (TYPE_STRING), 12",""" +
      """"status":"INVALID_ARGUMENT","details":[""" +
      """{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[""" +
      """{"field":"message.data[0].value","description":"Invalid value at 'message.data[0].value'"}""" +
      """]}]}}"""

  private val invalidTokenField =
    """{"error":{"code":400,"message":"bad token","status":"INVALID_ARGUMENT","details":[""" +
      """{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[""" +
      """{"field":"message.token","description":"Invalid registration token"}]}]}}"""

  private val senderMismatch =
    """{"error":{"code":403,"message":"SenderId mismatch","status":"PERMISSION_DENIED",""" +
      """"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
      """"errorCode":"SENDER_ID_MISMATCH"}]}}"""

  private val tooBig =
    """{"error":{"code":400,"message":"Message is too big","status":"INVALID_ARGUMENT",""" +
      """"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
      """"errorCode":"INVALID_ARGUMENT"}]}}"""

  private val wrongProject =
    """{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND"}}"""

  private val overQuota =
    """{"error":{"code":429,"message":"Quota exceeded for quota metric 'Send requests'",""" +
      """"status":"RESOURCE_EXHAUSTED","details":[""" +
      """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
      """"errorCode":"QUOTA_EXCEEDED"}]}}"""

  private val expiredCredential =
    """{"error":{"code":401,"message":"Request had invalid authentication credentials.",""" +
      """"status":"UNAUTHENTICATED"}}"""

  private val thirdPartyAuth =
    """{"error":{"code":401,"message":"Auth error from APNS or Web Push Service",""" +
      """"status":"UNAUTHENTICATED","details":[""" +
      """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
      """"errorCode":"THIRD_PARTY_AUTH_ERROR"}]}}"""

  private val permissionDenied =
    """{"error":{"code":403,"message":"Permission 'cloudmessaging.messages.create' denied",""" +
      """"status":"PERMISSION_DENIED"}}"""

  test("FCM's UNREGISTERED says the token is dead") {
    verdict(Status.NotFound, unregistered).map(result => assert(isGone(result), s"$result"))
  }

  test("FCM's INVALID_ARGUMENT about the token says the token is dead") {
    for
      fcmError <- verdict(Status.BadRequest, invalidToken)
      fieldNamed <- verdict(Status.BadRequest, invalidTokenField)
    yield
      assert(isGone(fcmError), s"$fcmError")
      assert(isGone(fieldNamed), s"$fieldNamed")
  }

  test("INVALID_ARGUMENT about another field is this message's fault, and keeps the token") {
    for
      field <- verdict(Status.BadRequest, invalidDataValue)
      size <- verdict(Status.BadRequest, tooBig)
    yield
      assert(isRejected(field), s"$field")
      assert(isRejected(size), s"$size")
  }

  test("a refusal that is not about the token keeps it: a wrong project, an unreadable answer") {
    for
      project <- verdict(Status.NotFound, wrongProject)
      unreadable <- verdict(Status.BadRequest, "<html>bad request</html>")
    yield
      assert(isRejected(project), s"$project")
      assert(isRejected(unreadable), s"$unreadable")
  }

  test("FCM's 429 is its quota: unavailable to the caller, and worth repeating") {
    verdict(Status.TooManyRequests, overQuota).map:
      case Left(error) =>
        assertEquals(Codes.of(error), "SENDER_UNAVAILABLE")
        assert(error.isTransient, s"$error")
      case Right(_) => fail("a 429 is not a delivery")
  }

  test("FCM's 401 and 403 are this service's credential: unavailable, and not repeated") {
    List(
      Status.Unauthorized -> expiredCredential,
      Status.Unauthorized -> thirdPartyAuth,
      Status.Forbidden -> permissionDenied
    ).traverse_ { (status, body) =>
      verdict(status, body).map:
        case Left(error) =>
          assertEquals(Codes.of(error), "SENDER_UNAVAILABLE", s"$status $body")
          assert(!error.isTransient, s"$error")
        case Right(_) => fail(s"a $status is not a delivery")
    }
  }

  test("FCM's SENDER_ID_MISMATCH is the token's: answered as ADDRESS_GONE, never repeated") {
    verdict(Status.Forbidden, senderMismatch).map:
      case Left(error) =>
        assertEquals(Codes.of(error), "ADDRESS_GONE")
        assert(!error.isTransient, s"$error")
        assert(!isGone(Left(error)), "a token of another sender is not a dead token, and is kept")
      case Right(_) => fail("a SENDER_ID_MISMATCH is not a delivery")
  }

  private def answeringInTurn(
    seen: Ref[IO, List[String]],
    answers: Ref[IO, List[Status]]
  ): Client[IO] =
    Client.fromHttpApp(
      HttpApp[IO]: request =>
        for
          _ <- seen.update(
            _ :+ request.headers.get[headers.Authorization].fold("none")(_.credentials.toString)
          )
          status <- answers.modify(left => (left.drop(1), left.headOption.getOrElse(Status.Ok)))
        yield Response[IO](status).withEntity(
          if status == Status.Unauthorized then expiredCredential
          else if status == Status.Forbidden then permissionDenied
          else "{}"
        )
    )

  test("a 401 drops the access token in hand and asks once more, at once, with a fresh one") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      answers <- Ref.of[IO, List[Status]](List(Status.Unauthorized))
      tokens <- RenewableBearer.start
      sender <- pushing(answeringInTurn(seen, answers), IO.pure(tokens))
      result <- sender.send(to, message)
      sent <- seen.get
      discarded <- tokens.discarded.get
    yield
      assertEquals(result, Right(()))
      assertEquals(sent, List("Bearer token-1", "Bearer token-2"))
      assertEquals(discarded, List("token-1"))
  }

  test("a 401 on the fresh token too is this service's credential; a 403 is asked only once") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      answers <- Ref.of[IO, List[Status]](List.fill(3)(Status.Unauthorized))
      tokens <- RenewableBearer.start
      sender <- pushing(answeringInTurn(seen, answers), IO.pure(tokens))
      unauthorized <- sender.send(to, message)
      afterUnauthorized <- seen.get
      _ <- answers.set(List.fill(3)(Status.Forbidden))
      _ <- seen.set(Nil)
      forbidden <- sender.send(to, message)
      afterForbidden <- seen.get
      discarded <- tokens.discarded.get
    yield
      assertEquals(afterUnauthorized, List("Bearer token-1", "Bearer token-2"))
      assertEquals(afterForbidden, List("Bearer token-2"), "a 403 is asked once, token kept")
      assertEquals(discarded, List("token-1"))
      List(unauthorized, forbidden).foreach:
        case Left(error) =>
          assertEquals(Codes.of(error), "SENDER_UNAVAILABLE")
          assert(!error.isTransient, s"$error")
        case Right(_) => fail("a refused credential is not a delivery")
  }

  test("FCM's THIRD_PARTY_AUTH_ERROR is the APNs or web push key, so the access token is kept") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      tokens <- RenewableBearer.start
      sender <- pushing(
        recording(seen, IO.pure(Response[IO](Status.Unauthorized).withEntity(thirdPartyAuth))),
        IO.pure(tokens)
      )
      result <- sender.send(to, message)
      sent <- seen.get
      discarded <- tokens.discarded.get
    yield
      assertEquals(sent.length, 1)
      assertEquals(discarded, Nil)
      assertEquals(result.left.map(Codes.of), Left("SENDER_UNAVAILABLE"))
  }

  private def overQuotaPausing(pause: Option[String]): Client[IO] =
    Client.fromHttpApp(
      HttpApp[IO]: _ =>
        val refused = Response[IO](Status.TooManyRequests).withEntity(overQuota)

        IO.pure(
          pause.fold(refused)(asked =>
            refused.putHeaders(Header.Raw(CIString("Retry-After"), asked))
          )
        )
    )

  test(
    "a 429 is retried when FCM says; unsaid or unreadable, after FCM's minute; at most an hour"
  ) {
    def retryAt(pause: Option[String]): IO[Option[Instant]] =
      pushing(overQuotaPausing(pause))
        .flatMap(_.send(to, message))
        .map:
          case Left(NotificationError.SenderThrottled(_, at, _)) => Some(at)
          case _                                                 => None

    for
      seconds <- retryAt(Some("30"))
      dated <- retryAt(Some("Wed, 21 Oct 2026 10:02:00 GMT"))
      unsaid <- retryAt(None)
      unreadable <- retryAt(Some("soon"))
      excessive <- retryAt(Some("86400"))
      past <- retryAt(Some("Wed, 21 Oct 2026 09:00:00 GMT"))
    yield
      assertEquals(seconds, Some(now.plusSeconds(30)))
      assertEquals(dated, Some(now.plusSeconds(120)))
      assertEquals(unsaid, Some(now.plusSeconds(60)))
      assertEquals(unreadable, Some(now.plusSeconds(60)))
      assertEquals(excessive, Some(now.plusSeconds(3600)))
      assertEquals(past, Some(now))
  }

  test("every failed send is counted under its reason, and a delivery under none") {
    val answers = List(
      Status.Ok -> "{}",
      Status.NotFound -> unregistered,
      Status.BadRequest -> invalidDataValue,
      Status.TooManyRequests -> overQuota,
      Status.TooManyRequests -> overQuota,
      Status.Unauthorized -> expiredCredential,
      Status.Forbidden -> senderMismatch,
      Status.ServiceUnavailable -> "busy"
    )

    for
      metrics <- Metrics.create("kinetix-notification-service", "test")
      _ <- answers.traverse_ { (status, body) =>
        pushing(answering(status, body), metrics = IO.pure(metrics)).flatMap(_.send(to, message))
      }
      scraped <- metrics.scrape
    yield List(
      "address_gone" -> 1,
      "address_foreign" -> 1,
      "rejected" -> 1,
      "quota" -> 2,
      "credential" -> 1,
      "unavailable" -> 1
    ).foreach: (reason, times) =>
      assert(
        scraped.contains(
          "kinetix_notification_provider_failures_total" +
            s"""{channel="push",reason="$reason"} $times"""
        ),
        scraped
      )
  }

  test("a proof of possession travels as a data message that names its nonce and expiry") {
    val nonce = DeviceNonce.fromString("nonce-1").getOrElse(fail("bad nonce"))
    val expiresAt = Instant.parse("2026-10-21T10:10:00Z")

    for
      bodies <- Ref.of[IO, List[String]](Nil)
      client = Client.fromHttpApp(
        HttpApp[IO](request =>
          request.bodyText.compile.string.flatMap(b => bodies.update(_ :+ b)) *> Ok("{}")
        )
      )
      sender <- pushing(client)
      result <- sender.sendChallenge(
        DeviceToken.fromString("handset-1").getOrElse(fail("bad token")),
        nonce,
        expiresAt
      )
      sent <- bodies.get
    yield
      assertEquals(result, Right(()))
      val message = parser.parse(sent.head).toOption.get.hcursor.downField("message")
      assertEquals(message.get[String]("token"), Right("handset-1"))
      assertEquals(message.downField("data").get[String]("kind"), Right("DEVICE_VERIFICATION"))
      assertEquals(message.downField("data").get[String]("nonce"), Right("nonce-1"))
      assertEquals(
        message.downField("data").get[String]("expires_at"),
        Right("2026-10-21T10:10:00Z")
      )
      assert(message.downField("notification").failed, "a proof must not be shown to the user")
      assertEquals(message.downField("android").get[String]("priority"), Right("HIGH"))
      assertEquals(message.downField("android").get[String]("ttl"), Right("600s"))
      assertEquals(
        message.downField("apns").downField("headers").get[String]("apns-expiration"),
        Right(expiresAt.getEpochSecond.toString)
      )
      assertEquals(
        message
          .downField("apns")
          .downField("payload")
          .downField("aps")
          .get[Int]("content-available"),
        Right(1)
      )
  }

  test("a proof whose nonce has already expired is not sent at all") {
    val nonce = DeviceNonce.fromString("nonce-1").getOrElse(fail("bad nonce"))

    for
      seen <- Ref.of[IO, List[String]](Nil)
      sender <- pushing(recording(seen, Ok("{}")))
      result <- sender.sendChallenge(
        DeviceToken.fromString("handset-1").getOrElse(fail("bad token")),
        nonce,
        Instant.parse("2026-10-21T10:00:00Z")
      )
      sent <- seen.get
    yield
      assertEquals(sent, Nil)
      assert(result.isLeft, s"$result")
  }

  test("what the provider said is logged where it is read, at a level that says how bad it is") {
    def logged(status: Status, body: String): IO[List[(String, String)]] =
      for
        logger <- RecordingLogger.empty
        sender <- pushing(answering(status, body), logger = logger)
        _ <- sender.send(to, message)
        lines <- logger.lines.get
      yield lines

    for
      gone <- logged(Status.NotFound, unregistered)
      refused <- logged(Status.BadRequest, invalidDataValue)
      credential <- logged(Status.Forbidden, permissionDenied)
      foreign <- logged(Status.Forbidden, senderMismatch)
      expired <- logged(Status.Unauthorized, expiredCredential)
      quota <- logged(Status.TooManyRequests, overQuota)
      down <- logged(Status.ServiceUnavailable, "the backend is overloaded")
    yield
      assertEquals(gone.map(_._1), List("INFO"))
      assert(gone.head._2.contains("UNREGISTERED"), gone.head._2)
      assertEquals(refused.map(_._1), List("ERROR"))
      assert(refused.head._2.contains("message.data[0].value"), refused.head._2)
      assertEquals(credential.map(_._1), List("ERROR"))
      assert(credential.head._2.contains("cloudmessaging.messages.create"), credential.head._2)
      assert(credential.head._2.contains("PUSH_PROVIDER_CREDENTIALS_B64"), credential.head._2)
      assertEquals(foreign.map(_._1), List("WARN"))
      assert(foreign.head._2.contains("SenderId mismatch"), foreign.head._2)
      assert(foreign.head._2.contains("wrong Firebase project"), foreign.head._2)
      assertEquals(expired.map(_._1), List("WARN", "ERROR"))
      assert(expired.head._2.contains("asking once more with a fresh one"), expired.head._2)
      assertEquals(quota.map(_._1), List("WARN"))
      assert(quota.head._2.contains("a pause of 60 s"), quota.head._2)
      assertEquals(down.map(_._1), List("WARN"))
      assert(down.head._2.contains("503: the backend is overloaded"), down.head._2)
  }
