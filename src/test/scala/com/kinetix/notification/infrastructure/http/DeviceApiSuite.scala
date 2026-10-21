package com.kinetix.notification.infrastructure.http

import java.time.Duration

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.application.{ForgetOwnDevice, RegisterDevice, VerifyDevice}
import com.kinetix.notification.domain.*
import com.kinetix.notification.fakes.*

class DeviceApiSuite extends BoundedSuite:
  private val other: PrincipalId =
    PrincipalId.fromString("99999999-8888-7777-6666-555555555555").get

  private val limit: DeviceLimit = DeviceLimit.fromInt(3).get

  private val tokens = FakeAccessTokens(
    Map(
      "buyer" -> TokenVerdict.Valid(Fixtures.recipient),
      "other" -> TokenVerdict.Valid(other),
      "keys-down" -> TokenVerdict.Unverifiable("connect timed out")
    )
  )

  private def app(
    devices: FakeDeviceRegistry,
    challenges: FakeChallengeSender,
    time: MovableTime
  ): IO[HttpApp[IO]] =
    for
      nonces <- SequentialNonces.start
      retry <- CountingRetry.upTo(3)
    yield DeviceApi(
      tokens,
      RegisterDevice[IO](devices, challenges, nonces, retry, time, limit),
      VerifyDevice[IO](devices, time, limit),
      ForgetOwnDevice[IO](devices),
      NoOpLogger[IO]
    ).orNotFound

  private def handsets(
    use: (FakeDeviceRegistry, FakeChallengeSender, MovableTime, HttpApp[IO]) => IO[Unit]
  ): IO[Unit] =
    for
      devices <- FakeDeviceRegistry.empty
      challenges <- FakeChallengeSender.working
      time <- MovableTime.at(Fixtures.at)
      served <- app(devices, challenges, time)
      _ <- use(devices, challenges, time, served)
    yield ()

  private def call(
    method: Method,
    bearer: Option[String],
    body: String,
    path: String = DeviceApi.Path
  ): Request[IO] =
    val request = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)

    bearer.fold(request): token =>
      request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

  private def verifying(bearer: String, nonce: String, token: String = "handset-token-1") =
    call(
      Method.POST,
      Some(bearer),
      s"""{"device_token":"$token","nonce":"$nonce"}""",
      DeviceApi.VerifyPath
    )

  private val handset = """{"device_token":"handset-token-1","platform":"ANDROID"}"""

  private def owner(stored: Map[String, (PrincipalId, DevicePlatform)]): Option[PrincipalId] =
    stored.get("handset-token-1").map(_._1)

  test("a handset is registered to the principal its token names, never one it claims") {
    val claimsSomeoneElse =
      s"""{"device_token":"handset-token-1","platform":"IOS","principal_id":"${other.value}"}"""

    handsets: (devices, _, _, served) =>
      for
        response <- served.run(call(Method.POST, Some("buyer"), claimsSomeoneElse))
        body <- response.as[String]
        stored <- devices.contents
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(body, """{"registered":true,"already_registered":false}""")
        assertEquals(stored, Map("handset-token-1" -> (Fixtures.recipient, DevicePlatform.Ios)))
  }

  test("the same principal registering its own token again is told it was already there") {
    handsets: (_, challenges, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        again <- served.run(call(Method.POST, Some("buyer"), handset))
        body <- again.as[String]
        sent <- challenges.sent.get
      yield
        assertEquals(again.status, Status.Ok)
        assertEquals(body, """{"registered":true,"already_registered":true}""")
        assertEquals(sent, Nil, "a principal does not prove possession of its own token")
  }

  test("no token, or one identity did not sign, is unauthenticated and stores nothing") {
    handsets: (devices, _, _, served) =>
      for
        none <- served.run(call(Method.POST, None, handset))
        forged <- served.run(call(Method.POST, Some("forged"), handset))
        unverified <- served.run(verifying("forged", "nonce-1"))
        stored <- devices.contents
      yield
        assertEquals(none.status, Status.Unauthorized)
        assertEquals(forged.status, Status.Unauthorized)
        assertEquals(unverified.status, Status.Unauthorized)
        assertEquals(
          forged.headers.headers.find(_.name.toString == "WWW-Authenticate").map(_.value),
          Some("Bearer")
        )
        assertEquals(stored, Map.empty)
  }

  test("signing keys that could not be read are a 503, not a verdict on the token") {
    handsets: (_, _, _, served) =>
      for
        response <- served.run(call(Method.POST, Some("keys-down"), handset))
        verify <- served.run(verifying("keys-down", "nonce-1"))
      yield
        assertEquals(response.status, Status.ServiceUnavailable)
        assertEquals(verify.status, Status.ServiceUnavailable)
  }

  test("a platform the service does not know is refused, not stored as Android") {
    handsets: (devices, _, _, served) =>
      for
        response <- served.run(
          call(Method.POST, Some("buyer"), """{"device_token":"t","platform":"WINDOWS"}""")
        )
        body <- response.as[String]
        stored <- devices.contents
      yield
        assertEquals(response.status, Status.BadRequest)
        assert(body.contains("INVALID_PLATFORM"), body)
        assertEquals(stored, Map.empty)
  }

  test("a body that is not the expected JSON is a 400") {
    handsets: (_, _, _, served) =>
      for
        response <- served.run(call(Method.POST, Some("buyer"), "device_token=t"))
        noNonce <- served.run(
          call(Method.POST, Some("buyer"), """{"device_token":"t"}""", DeviceApi.VerifyPath)
        )
        blankNonce <- served.run(verifying("buyer", " "))
        body <- blankNonce.as[String]
      yield
        assertEquals(response.status, Status.BadRequest)
        assertEquals(noNonce.status, Status.BadRequest)
        assertEquals(blankNonce.status, Status.BadRequest)
        assert(body.contains("INVALID_NONCE"), body)
  }

  test("a body larger than the limit is refused before it is parsed") {
    val huge = s"""{"device_token":"${"x" * (DeviceApi.MaxBodyBytes + 1)}","platform":"IOS"}"""

    handsets: (_, _, _, served) =>
      for response <- served.run(call(Method.POST, Some("buyer"), huge))
      yield assertEquals(response.status, Status.PayloadTooLarge)
  }

  test("a handset can forget its own token, and nobody else's") {
    handsets: (devices, _, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        theirs <- served.run(
          call(Method.DELETE, Some("other"), """{"device_token":"handset-token-1"}""")
        )
        afterOther <- devices.contents
        own <- served.run(
          call(Method.DELETE, Some("buyer"), """{"device_token":"handset-token-1"}""")
        )
        afterOwn <- devices.contents
      yield
        assertEquals(theirs.status, Status.NoContent)
        assertEquals(afterOther.keySet, Set("handset-token-1"), "another principal removed it")
        assertEquals(own.status, Status.NoContent)
        assertEquals(afterOwn, Map.empty)
  }

  test("a token another principal holds is not moved; a nonce goes to that handset instead") {
    handsets: (devices, challenges, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        claimed <- served.run(call(Method.POST, Some("other"), handset))
        body <- claimed.as[String]
        stored <- devices.contents
        sent <- challenges.sent.get
      yield
        assertEquals(claimed.status, Status.Accepted)
        assertEquals(
          body,
          """{"registered":false,"state":"DEVICE_VERIFICATION_PENDING",""" +
            s""""expires_at":"${Fixtures.at.plus(DeviceChallenge.Lifetime)}"}"""
        )
        assertEquals(owner(stored), Some(Fixtures.recipient))
        assertEquals(
          sent.map((token, nonce, _) => (token.value, nonce.value)),
          List(("handset-token-1", "nonce-1"))
        )
  }

  test("the claimant who echoes the nonce sent to the handset takes the token") {
    handsets: (devices, _, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        _ <- served.run(call(Method.POST, Some("other"), handset))
        verified <- served.run(verifying("other", "nonce-1"))
        body <- verified.as[String]
        stored <- devices.contents
        pending <- devices.pending
      yield
        assertEquals(verified.status, Status.Ok)
        assertEquals(body, """{"registered":true,"already_registered":false}""")
        assertEquals(owner(stored), Some(other))
        assertEquals(pending, Nil, "a nonce that was used must not be usable again")
  }

  test("a nonce is used once: echoing it again finds nothing pending") {
    handsets: (_, _, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        _ <- served.run(call(Method.POST, Some("other"), handset))
        _ <- served.run(verifying("other", "nonce-1"))
        again <- served.run(verifying("other", "nonce-1"))
        body <- again.as[String]
      yield
        assertEquals(again.status, Status.UnprocessableEntity)
        assert(body.contains("VERIFICATION_NOT_PENDING"), body)
  }

  test("the nonce proves the claimant's request only; the holder echoing it moves nothing") {
    handsets: (devices, _, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        _ <- served.run(call(Method.POST, Some("other"), handset))
        byHolder <- served.run(verifying("buyer", "nonce-1"))
        stored <- devices.contents
      yield
        assertEquals(byHolder.status, Status.UnprocessableEntity)
        assertEquals(owner(stored), Some(Fixtures.recipient))
  }

  test("a wrong nonce moves nothing, and five of them spend the proof") {
    handsets: (devices, _, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        _ <- served.run(call(Method.POST, Some("other"), handset))
        first <- served.run(verifying("other", "guess"))
        firstBody <- first.as[String]
        _ <- served.run(verifying("other", "guess"))
        _ <- served.run(verifying("other", "guess"))
        _ <- served.run(verifying("other", "guess"))
        last <- served.run(verifying("other", "guess"))
        lastBody <- last.as[String]
        late <- served.run(verifying("other", "nonce-1"))
        lateBody <- late.as[String]
        stored <- devices.contents
      yield
        assertEquals(first.status, Status.UnprocessableEntity)
        assert(firstBody.contains("VERIFICATION_FAILED"), firstBody)
        assert(firstBody.contains("4 attempt(s) left"), firstBody)
        assert(lastBody.contains("0 attempt(s) left"), lastBody)
        assertEquals(late.status, Status.UnprocessableEntity)
        assert(lateBody.contains("VERIFICATION_NOT_PENDING"), lateBody)
        assertEquals(owner(stored), Some(Fixtures.recipient))
  }

  test("a nonce echoed after it expired moves nothing") {
    handsets: (devices, _, time, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        _ <- served.run(call(Method.POST, Some("other"), handset))
        _ <- time.advance(DeviceChallenge.Lifetime.plus(Duration.ofSeconds(1)))
        late <- served.run(verifying("other", "nonce-1"))
        body <- late.as[String]
        stored <- devices.contents
      yield
        assertEquals(late.status, Status.UnprocessableEntity)
        assert(body.contains("VERIFICATION_NOT_PENDING"), body)
        assertEquals(owner(stored), Some(Fixtures.recipient))
  }

  test("a push provider that did not answer the proof is a 503, and moves nothing") {
    val down = NotificationError.SenderUnavailable(Channel.Push, "503: busy")

    for
      devices <- FakeDeviceRegistry.empty
      challenges <- FakeChallengeSender.answering(List.fill(3)(Left(down)))
      time <- MovableTime.at(Fixtures.at)
      served <- app(devices, challenges, time)
      _ <- served.run(call(Method.POST, Some("buyer"), handset))
      claimed <- served.run(call(Method.POST, Some("other"), handset))
      stored <- devices.contents
      sent <- challenges.sent.get
    yield
      assertEquals(claimed.status, Status.ServiceUnavailable)
      assertEquals(sent.length, 3, "an outage is worth asking again")
      assertEquals(owner(stored), Some(Fixtures.recipient))
  }

  test("a token the push provider calls dead is refused, and forgotten for its holder too") {
    val gone = NotificationError.AddressGone(Channel.Push, "404: UNREGISTERED")

    for
      devices <- FakeDeviceRegistry.empty
      challenges <- FakeChallengeSender.answering(List(Left(gone)))
      time <- MovableTime.at(Fixtures.at)
      served <- app(devices, challenges, time)
      _ <- served.run(call(Method.POST, Some("buyer"), handset))
      claimed <- served.run(call(Method.POST, Some("other"), handset))
      body <- claimed.as[String]
      stored <- devices.contents
    yield
      assertEquals(claimed.status, Status.UnprocessableEntity)
      assert(body.contains("ADDRESS_GONE"), body)
      assertEquals(stored, Map.empty)
  }

  test("a principal keeps at most the configured number of handsets, losing the oldest") {
    def registering(token: String) =
      call(Method.POST, Some("buyer"), s"""{"device_token":"$token","platform":"ANDROID"}""")

    handsets: (devices, _, _, served) =>
      for
        answers <- List("first", "second", "third", "fourth").traverse(t =>
          served.run(registering(t))
        )
        stored <- devices.contents
      yield
        assertEquals(answers.map(_.status), List.fill(4)(Status.Ok))
        assertEquals(stored.keySet, Set("second", "third", "fourth"))
  }
