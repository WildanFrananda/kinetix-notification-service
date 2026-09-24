package com.kinetix.notification.infrastructure.http

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

import com.kinetix.notification.domain.{Address, DeviceToken, Message, NotificationError}

class HttpPushSenderSuite extends CatsEffectSuite:
  private val endpoint =
    Uri.unsafeFromString("https://fcm.googleapis.com/v1/projects/kinetix-push/messages:send")

  private val to = Address.Push(DeviceToken.fromString("handset-1").getOrElse(fail("bad token")))
  private val message = Message("Order packed", "Order ORD-1 is packed.")

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
      sender = HttpPushSender(recording(seen, Ok("{}")), endpoint, bearer)
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
        IO.pure("t")
      )
      result <- sender.send(to, message)
    yield result match
      case Left(error: NotificationError.SenderRejected) => assert(!error.isTransient)
      case other => fail(s"a 400 should be a rejection, not $other")
  }

  test("a provider that did not answer is unavailable, and is worth repeating") {
    for
      seen <- Ref.of[IO, List[String]](Nil)
      sender = HttpPushSender(recording(seen, ServiceUnavailable("busy")), endpoint, IO.pure("t"))
      result <- sender.send(to, message)
    yield result match
      case Left(error: NotificationError.SenderUnavailable) => assert(error.isTransient)
      case other => fail(s"a 503 should be unavailable, not $other")
  }
