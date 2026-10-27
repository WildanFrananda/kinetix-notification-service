package com.kinetix.notification.infrastructure.http

import java.time.Duration

import scala.concurrent.duration.DurationInt

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import doobie.Transactor
import io.circe.Json
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Header, HttpApp, Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.application.{ForgetOwnDevice, RegisterDevice, VerifyDevice}
import com.kinetix.notification.domain.*
import com.kinetix.notification.fakes.*
import com.kinetix.notification.infrastructure.observability.Metrics
import com.kinetix.notification.infrastructure.persistence.PostgresDeviceRegistry
import com.kinetix.notification.infrastructure.retry.{Backoff, ExponentialBackoffRetry}

class DeviceApiSuite extends BoundedSuite:
  private val other: PrincipalId =
    PrincipalId.fromString("99999999-8888-7777-6666-555555555555").get

  private val limit: DeviceLimit = DeviceLimit.fromInt(3).get

  private val third: PrincipalId = PrincipalId.fromString("claimant-3").get
  private val fourth: PrincipalId = PrincipalId.fromString("claimant-4").get
  private val fifth: PrincipalId = PrincipalId.fromString("claimant-5").get

  private val tokens = FakeAccessTokens(
    Map(
      "buyer" -> TokenVerdict.Valid(Fixtures.recipient),
      "other" -> TokenVerdict.Valid(other),
      "third" -> TokenVerdict.Valid(third),
      "fourth" -> TokenVerdict.Valid(fourth),
      "fifth" -> TokenVerdict.Valid(fifth),
      "keys-down" -> TokenVerdict.Unverifiable("connect timed out")
    )
  )

  private val project = "kinetix-commerce"

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
      ExponentialBackoffRetry.default[IO].backoff,
      time,
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

  private def retryAfter(response: Response[IO]): Option[String] =
    response.headers.headers.find(_.name.toString == "Retry-After").map(_.value)

  private def answerThrough(fcm: Client[IO]): IO[Response[IO]] =
    FakeDeviceRegistry.empty.flatMap(answerThrough(fcm, _))

  private def answerThrough(fcm: Client[IO], devices: FakeDeviceRegistry): IO[Response[IO]] =
    val retry = ExponentialBackoffRetry.default[IO]

    for
      nonces <- SequentialNonces.start
      time <- MovableTime.at(Fixtures.at)
      metrics <- Metrics.create("kinetix-notification-service", "test")
      pushTokens <- RenewableBearer.start
      push = HttpPushSender(
        fcm,
        Uri.unsafeFromString(s"https://fcm.googleapis.com/v1/projects/$project/messages:send"),
        pushTokens,
        time,
        metrics,
        NoOpLogger[IO]
      )
      served = DeviceApi(
        tokens,
        RegisterDevice[IO](devices, push, nonces, retry, time, limit),
        VerifyDevice[IO](devices, time, limit),
        ForgetOwnDevice[IO](devices),
        retry.backoff,
        time,
        NoOpLogger[IO]
      ).orNotFound
      _ <- served.run(call(Method.POST, Some("buyer"), handset))
      claimed <- served.run(call(Method.POST, Some("other"), handset))
    yield claimed

  private def claimedThrough(fcm: Client[IO]): IO[(Status, String)] =
    answerThrough(fcm).flatMap(claimed => claimed.as[String].map(body => (claimed.status, body)))

  private def fcmAnswering(status: Status, body: String): Client[IO] =
    Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](status).withEntity(body))))

  private def fcmCounting(calls: Ref[IO, Int], answer: Response[IO]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO](_ => calls.update(_ + 1).as(answer)))

  private def overQuota(pause: Option[String]): Response[IO] =
    val refused = Response[IO](Status.TooManyRequests).withEntity(
      s"""{"error":{"code":429,"message":"Quota exceeded for projects/$project",""" +
        """"status":"RESOURCE_EXHAUSTED","details":[""" +
        """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
        """"errorCode":"QUOTA_EXCEEDED"}]}}"""
    )

    pause.fold(refused)(seconds => refused.putHeaders(Header.Raw(CIString("Retry-After"), seconds)))

  private val foreignToken = refusal(
    "ADDRESS_GONE",
    "the push provider says this device token belongs to another app; " +
      "get an FCM token from this app and register it"
  )

  private val credentialRefused = refusal(
    "SENDER_UNAVAILABLE",
    "the push provider did not accept this service's credentials; the request is not at fault; " +
      "try again later"
  )

  private def refusal(code: String, message: String): String =
    Json
      .obj("error" -> Json.fromString(code), "message" -> Json.fromString(message))
      .noSpaces

  test("the push provider's own words never reach the handset; each refusal has its message") {
    val unregistered =
      s"""{"error":{"code":404,"message":"Requested entity was not found: projects/$project",""" +
        """"status":"NOT_FOUND","details":[""" +
        """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
        """"errorCode":"UNREGISTERED"}]}}"""
    val mismatch =
      s"""{"error":{"code":403,"message":"SenderId mismatch for projects/$project",""" +
        """"status":"PERMISSION_DENIED","details":[""" +
        """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
        """"errorCode":"SENDER_ID_MISMATCH"}]}}"""
    val denied =
      s"""{"error":{"code":403,"message":"Permission denied on projects/$project",""" +
        """"status":"PERMISSION_DENIED"}}"""
    val tooBig =
      s"""{"error":{"code":400,"message":"Message is too big for projects/$project",""" +
        """"status":"INVALID_ARGUMENT","details":[""" +
        """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
        """"errorCode":"INVALID_ARGUMENT"}]}}"""
    val unreachable = Client[IO](_ =>
      Resource.raiseError[IO, Response[IO], Throwable](
        java.net.ConnectException(s"Connection refused: fcm-egress.internal:443 for $project")
      )
    )

    for
      gone <- claimedThrough(fcmAnswering(Status.NotFound, unregistered))
      rejected <- claimedThrough(fcmAnswering(Status.BadRequest, tooBig))
      foreign <- claimedThrough(fcmAnswering(Status.Forbidden, mismatch))
      credential <- claimedThrough(fcmAnswering(Status.Forbidden, denied))
      overloaded <- claimedThrough(
        fcmAnswering(Status.ServiceUnavailable, s"projects/$project is overloaded")
      )
      refused <- claimedThrough(unreachable)
    yield
      List(gone, rejected, foreign, credential, overloaded, refused).foreach: (_, body) =>
        assert(!body.contains(project), body)
        assert(!body.contains("fcm-egress"), body)

      assertEquals(
        gone,
        (
          Status.UnprocessableEntity,
          refusal(
            "ADDRESS_GONE",
            "the push provider says this device token no longer exists; " +
              "get a fresh FCM token and register it again"
          )
        )
      )
      assertEquals(
        rejected,
        (
          Status.BadGateway,
          refusal(
            "SENDER_REJECTED",
            "the push provider refused the message; repeating the request unchanged does not help"
          )
        )
      )

      val unavailable = refusal(
        "SENDER_UNAVAILABLE",
        "the push provider did not answer; 3 attempts were made, with 200 ms and 600 ms " +
          "back-off; try again later"
      )

      assertEquals(overloaded, (Status.ServiceUnavailable, unavailable))
      assertEquals(refused, (Status.ServiceUnavailable, unavailable))
      assertEquals(credential, (Status.ServiceUnavailable, credentialRefused))
      assertEquals(foreign, (Status.UnprocessableEntity, foreignToken))
  }

  test("a token FCM says belongs to another sender is refused as gone, and kept for its holder") {
    val mismatch =
      """{"error":{"code":403,"message":"SenderId mismatch","status":"PERMISSION_DENIED",""" +
        """"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",""" +
        """"errorCode":"SENDER_ID_MISMATCH"}]}}"""

    for
      devices <- FakeDeviceRegistry.empty
      calls <- Ref.of[IO, Int](0)
      claimed <- answerThrough(
        fcmCounting(calls, Response[IO](Status.Forbidden).withEntity(mismatch)),
        devices
      )
      body <- claimed.as[String]
      made <- calls.get
      stored <- devices.contents
    yield
      assertEquals(made, 1, "a token of another sender stays one, however often it is asked")
      assertEquals((claimed.status, body), (Status.UnprocessableEntity, foreignToken))
      assertEquals(owner(stored), Some(Fixtures.recipient))
  }

  test("an access token FCM refuses with a 401 is replaced at once, and the proof still goes out") {
    for
      calls <- Ref.of[IO, Int](0)
      fcm = Client.fromHttpApp(
        HttpApp[IO](_ =>
          calls
            .updateAndGet(_ + 1)
            .map: made =>
              if made == 1 then
                Response[IO](Status.Unauthorized).withEntity(
                  """{"error":{"code":401,"status":"UNAUTHENTICATED"}}"""
                )
              else Response[IO](Status.Ok).withEntity("{}")
        )
      )
      claimed <- answerThrough(fcm)
      made <- calls.get
    yield
      assertEquals(claimed.status, Status.Accepted)
      assertEquals(made, 2)
  }

  test(
    "FCM's quota refusal is asked again with the usual back-off, then 503 with its Retry-After"
  ) {
    for
      calls <- Ref.of[IO, Int](0)
      claimed <- answerThrough(fcmCounting(calls, overQuota(Some("30"))))
      body <- claimed.as[String]
      made <- calls.get
    yield
      assertEquals(made, 3, "a quota refusal is worth asking again")
      assertEquals(claimed.status, Status.ServiceUnavailable)
      assertEquals(retryAfter(claimed), Some("30"))
      assert(!body.contains(project), body)
      assertEquals(
        body,
        refusal(
          "SENDER_UNAVAILABLE",
          "the push provider is limiting how much this service may send; 3 attempts were made, " +
            "with 200 ms and 600 ms back-off; try again after Retry-After"
        )
      )
  }

  test("a quota refusal naming no usable pause waits FCM's minute; one past an hour, an hour") {
    for
      calls <- Ref.of[IO, Int](0)
      unstated <- answerThrough(fcmCounting(calls, overQuota(None)))
      garbled <- answerThrough(fcmCounting(calls, overQuota(Some("soon"))))
      excessive <- answerThrough(fcmCounting(calls, overQuota(Some("86400"))))
    yield
      assertEquals(retryAfter(unstated), Some("60"))
      assertEquals(retryAfter(garbled), Some("60"))
      assertEquals(retryAfter(excessive), Some("3600"))
  }

  test("FCM refusing this service's credentials is a 503 without a loop, and no Retry-After") {
    val expired =
      s"""{"error":{"code":401,"message":"Request had invalid credentials for projects/$project",""" +
        """"status":"UNAUTHENTICATED"}}"""
    val denied =
      s"""{"error":{"code":403,"message":"Permission denied on projects/$project",""" +
        """"status":"PERMISSION_DENIED"}}"""

    for
      calls <- Ref.of[IO, Int](0)
      unauthorized <- answerThrough(
        fcmCounting(calls, Response[IO](Status.Unauthorized).withEntity(expired))
      )
      forbidden <- answerThrough(
        fcmCounting(calls, Response[IO](Status.Forbidden).withEntity(denied))
      )
      bodies <- List(unauthorized, forbidden).traverse(_.as[String])
      made <- calls.get
    yield
      assertEquals(made, 3, "a 401 is asked once more with a fresh token, a 403 only once")
      assertEquals(
        List(unauthorized, forbidden).map(_.status),
        List.fill(2)(Status.ServiceUnavailable)
      )
      assertEquals(List(unauthorized, forbidden).map(retryAfter), List(None, None))
      assertEquals(bodies, List.fill(2)(credentialRefused))
  }

  test("a device registry that did not answer says so, not that a delivery record did") {
    val unreachable = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      "jdbc:postgresql://127.0.0.1:1/notification_test",
      "nobody",
      "nothing",
      None
    )
    val registry = PostgresDeviceRegistry(unreachable, NoOpLogger[IO])
    val expected = refusal(
      "STORE_UNAVAILABLE",
      "the device registry could not be reached; try again later"
    )

    for
      challenges <- FakeChallengeSender.working
      nonces <- SequentialNonces.start
      retry <- CountingRetry.upTo(1)
      time <- MovableTime.at(Fixtures.at)
      served = DeviceApi(
        tokens,
        RegisterDevice[IO](registry, challenges, nonces, retry, time, limit),
        VerifyDevice[IO](registry, time, limit),
        ForgetOwnDevice[IO](registry),
        ExponentialBackoffRetry.default[IO].backoff,
        time,
        NoOpLogger[IO]
      ).orNotFound
      answers <- List(
        call(Method.POST, Some("buyer"), handset),
        verifying("buyer", "nonce-1"),
        call(Method.DELETE, Some("buyer"), """{"device_token":"handset-token-1"}""")
      ).traverse(request => served.run(request).flatMap(r => r.as[String].map((r.status, _))))
    yield assertEquals(answers, List.fill(3)((Status.ServiceUnavailable, expected)))
  }

  test("a claim already waiting on its proof wakes the handset no more, and is told to wait") {
    handsets: (_, challenges, _, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        first <- served.run(call(Method.POST, Some("other"), handset))
        again <- served.run(call(Method.POST, Some("other"), handset))
        body <- again.as[String]
        sent <- challenges.sent.get
      yield
        assertEquals(first.status, Status.Accepted)
        assertEquals(again.status, Status.TooManyRequests)
        assert(body.contains("VERIFICATION_ALREADY_PENDING"), body)
        assertEquals(retryAfter(again), Some(DeviceChallenge.Lifetime.getSeconds.toString))
        assertEquals(sent.length, 1, "one claim must not wake the holder's handset twice")
  }

  test("a token is sent at most three proofs an hour, whoever claims it") {
    handsets: (_, challenges, time, served) =>
      for
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        claims <- List("other", "third", "fourth").traverse(bearer =>
          served.run(call(Method.POST, Some(bearer), handset))
        )
        limited <- served.run(call(Method.POST, Some("fifth"), handset))
        body <- limited.as[String]
        sentWithinTheHour <- challenges.sent.get
        _ <- time.advance(Duration.ofHours(1))
        later <- served.run(call(Method.POST, Some("fifth"), handset))
        sentAfter <- challenges.sent.get
      yield
        assertEquals(claims.map(_.status), List.fill(3)(Status.Accepted))
        assertEquals(limited.status, Status.TooManyRequests)
        assert(body.contains("VERIFICATION_LIMIT_REACHED"), body)
        assertEquals(retryAfter(limited), Some("3600"))
        assertEquals(sentWithinTheHour.length, 3)
        assertEquals(later.status, Status.Accepted)
        assertEquals(sentAfter.length, 4)
  }

  test("a proof the push provider did not take leaves the claimant free to claim again") {
    val down = NotificationError.SenderUnavailable(Channel.Push, "503: busy")

    for
      devices <- FakeDeviceRegistry.empty
      challenges <- FakeChallengeSender.answering(List.fill(3)(Left(down)))
      time <- MovableTime.at(Fixtures.at)
      served <- app(devices, challenges, time)
      _ <- served.run(call(Method.POST, Some("buyer"), handset))
      failed <- served.run(call(Method.POST, Some("other"), handset))
      again <- served.run(call(Method.POST, Some("other"), handset))
      sent <- challenges.sent.get
    yield
      assertEquals(failed.status, Status.ServiceUnavailable)
      assertEquals(again.status, Status.Accepted)
      assertEquals(sent.length, 4)
  }

  test("the outage message states the attempts and pauses of the policy that made them") {
    val down = NotificationError.SenderUnavailable(Channel.Push, "503: busy")

    def answered(backoff: Backoff): IO[String] =
      for
        devices <- FakeDeviceRegistry.empty
        challenges <- FakeChallengeSender.answering(List.fill(4)(Left(down)))
        nonces <- SequentialNonces.start
        retry <- CountingRetry.upTo(backoff.attempts)
        time <- MovableTime.at(Fixtures.at)
        served = DeviceApi(
          tokens,
          RegisterDevice[IO](devices, challenges, nonces, retry, time, limit),
          VerifyDevice[IO](devices, time, limit),
          ForgetOwnDevice[IO](devices),
          backoff,
          time,
          NoOpLogger[IO]
        ).orNotFound
        _ <- served.run(call(Method.POST, Some("buyer"), handset))
        claimed <- served.run(call(Method.POST, Some("other"), handset))
        body <- claimed.as[String]
      yield body

    for
      once <- answered(Backoff(Nil))
      twice <- answered(Backoff(List(1.second)))
      four <- answered(Backoff(List(100.millis, 300.millis, 900.millis)))
    yield
      assert(once.contains("did not answer; 1 attempt was made; try again later"), once)
      assert(twice.contains("2 attempts were made, with 1000 ms back-off"), twice)
      assert(four.contains("4 attempts were made, with 100 ms, 300 ms and 900 ms back-off"), four)
      assertEquals(
        ExponentialBackoffRetry.default[IO].backoff,
        Backoff(List(200.millis, 600.millis)),
        "the reference documents 3 attempts with 200 ms and 600 ms back-off"
      )
  }
