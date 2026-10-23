package com.kinetix.notification.infrastructure.http

import java.time.Instant

import cats.effect.{IO, Ref}
import io.circe.parser
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.fakes.{FixedTime, RecordingLogger}
import com.kinetix.notification.domain.{
  Address,
  DeviceNonce,
  DeviceToken,
  Message,
  NotificationError
}

class HttpPushSenderSuite extends BoundedSuite:
  private val endpoint =
    Uri.unsafeFromString("https://fcm.googleapis.com/v1/projects/kinetix-push/messages:send")

  private val to = Address.Push(DeviceToken.fromString("handset-1").getOrElse(fail("bad token")))
  private val message = Message("Order packed", "Order ORD-1 is packed.")
  private val clock = FixedTime(Instant.parse("2026-10-21T10:00:00Z"))

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
      rotating <- Ref.of[IO, Int](0)
      bearer = rotating.updateAndGet(_ + 1).map(n => s"token-$n")
      sender = HttpPushSender(recording(seen, Ok("{}")), endpoint, bearer, clock, NoOpLogger[IO])
      _ <- sender.send(to, message)
      _ <- sender.send(to, message)
      sent <- seen.get
    yield assertEquals(sent, List("Bearer token-1", "Bearer token-2"))
  }

  test("a provider that refuses is a rejection, and is not worth repeating") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      sender = HttpPushSender(
        recording(seen, BadRequest("""{"error":"registration token not valid"}""")),
        endpoint,
        IO.pure("t"),
        clock,
        NoOpLogger[IO]
      )
      result <- sender.send(to, message)
    yield result match
      case Left(error: NotificationError.SenderRejected) => assert(!error.isTransient)
      case other => fail(s"a 400 should be a rejection, not $other")
  }

  test("a provider that did not answer is unavailable, and is worth repeating") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      sender = HttpPushSender(
        recording(seen, ServiceUnavailable("busy")),
        endpoint,
        IO.pure("t"),
        clock,
        NoOpLogger[IO]
      )
      result <- sender.send(to, message)
    yield result match
      case Left(error: NotificationError.SenderUnavailable) => assert(error.isTransient)
      case other => fail(s"a 503 should be unavailable, not $other")
  }

  private def answering(status: Status, body: String): Client[IO] =
    Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](status).withEntity(body))))

  private def verdict(status: Status, body: String): IO[Either[NotificationError, Unit]] =
    HttpPushSender(answering(status, body), endpoint, IO.pure("t"), clock, NoOpLogger[IO])
      .send(to, message)

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

  test("a refusal that is not about the token keeps it: a sender mismatch, a wrong project") {
    for
      mismatch <- verdict(Status.Forbidden, senderMismatch)
      project <- verdict(Status.NotFound, wrongProject)
      unreadable <- verdict(Status.BadRequest, "<html>bad request</html>")
    yield
      assert(isRejected(mismatch), s"$mismatch")
      assert(isRejected(project), s"$project")
      assert(isRejected(unreadable), s"$unreadable")
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
      sender = HttpPushSender(client, endpoint, IO.pure("t"), clock, NoOpLogger[IO])
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
      sender = HttpPushSender(
        recording(seen, Ok("{}")),
        endpoint,
        IO.pure("t"),
        clock,
        NoOpLogger[IO]
      )
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
        _ <- HttpPushSender(answering(status, body), endpoint, IO.pure("t"), clock, logger)
          .send(to, message)
        lines <- logger.lines.get
      yield lines

    for
      gone <- logged(Status.NotFound, unregistered)
      refused <- logged(Status.Forbidden, senderMismatch)
      down <- logged(Status.ServiceUnavailable, "the backend is overloaded")
    yield
      assertEquals(gone.map(_._1), List("INFO"))
      assert(gone.head._2.contains("UNREGISTERED"), gone.head._2)
      assertEquals(refused.map(_._1), List("ERROR"))
      assert(refused.head._2.contains("SenderId mismatch"), refused.head._2)
      assertEquals(down.map(_._1), List("WARN"))
      assert(down.head._2.contains("503: the backend is overloaded"), down.head._2)
  }
