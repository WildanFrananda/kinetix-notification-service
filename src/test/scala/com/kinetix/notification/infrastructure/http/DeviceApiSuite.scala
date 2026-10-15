package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.application.{ForgetOwnDevice, RegisterDevice}
import com.kinetix.notification.domain.{DevicePlatform, PrincipalId}
import com.kinetix.notification.fakes.{FakeAccessTokens, FakeDeviceRegistry, Fixtures}

class DeviceApiSuite extends CatsEffectSuite:
  private val other: PrincipalId =
    PrincipalId.fromString("99999999-8888-7777-6666-555555555555").get

  private val tokens = FakeAccessTokens(
    Map(
      "buyer" -> TokenVerdict.Valid(Fixtures.recipient),
      "other" -> TokenVerdict.Valid(other),
      "keys-down" -> TokenVerdict.Unverifiable("connect timed out")
    )
  )

  private def app(devices: FakeDeviceRegistry): HttpApp[IO] =
    DeviceApi(
      tokens,
      RegisterDevice[IO](devices),
      ForgetOwnDevice[IO](devices),
      NoOpLogger[IO]
    ).orNotFound

  private def call(method: Method, bearer: Option[String], body: String): Request[IO] =
    val request = Request[IO](method, Uri.unsafeFromString(DeviceApi.Path)).withEntity(body)

    bearer.fold(request): token =>
      request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

  private val handset = """{"device_token":"handset-token-1","platform":"ANDROID"}"""

  test("a handset is registered to the principal its token names, never one it claims") {
    val claimsSomeoneElse =
      s"""{"device_token":"handset-token-1","platform":"IOS","principal_id":"${other.value}"}"""

    for
      devices <- FakeDeviceRegistry.empty
      response <- app(devices).run(call(Method.POST, Some("buyer"), claimsSomeoneElse))
      body <- response.as[String]
      stored <- devices.contents
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, """{"registered":true,"already_registered":false}""")
      assertEquals(stored, Map("handset-token-1" -> (Fixtures.recipient, DevicePlatform.Ios)))
  }

  test("no token, or one identity did not sign, is unauthenticated and stores nothing") {
    for
      devices <- FakeDeviceRegistry.empty
      none <- app(devices).run(call(Method.POST, None, handset))
      forged <- app(devices).run(call(Method.POST, Some("forged"), handset))
      stored <- devices.contents
    yield
      assertEquals(none.status, Status.Unauthorized)
      assertEquals(forged.status, Status.Unauthorized)
      assertEquals(
        forged.headers.headers.find(_.name.toString == "WWW-Authenticate").map(_.value),
        Some("Bearer")
      )
      assertEquals(stored, Map.empty)
  }

  test("signing keys that could not be read are a 503, not a verdict on the token") {
    for
      devices <- FakeDeviceRegistry.empty
      response <- app(devices).run(call(Method.POST, Some("keys-down"), handset))
    yield assertEquals(response.status, Status.ServiceUnavailable)
  }

  test("a platform the service does not know is refused, not stored as Android") {
    for
      devices <- FakeDeviceRegistry.empty
      response <- app(devices).run(
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
    for
      devices <- FakeDeviceRegistry.empty
      response <- app(devices).run(call(Method.POST, Some("buyer"), "device_token=t"))
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("a body larger than the limit is refused before it is parsed") {
    val huge = s"""{"device_token":"${"x" * (DeviceApi.MaxBodyBytes + 1)}","platform":"IOS"}"""

    for
      devices <- FakeDeviceRegistry.empty
      response <- app(devices).run(call(Method.POST, Some("buyer"), huge))
    yield assertEquals(response.status, Status.PayloadTooLarge)
  }

  test("a handset can forget its own token, and nobody else's") {
    for
      devices <- FakeDeviceRegistry.empty
      _ <- app(devices).run(call(Method.POST, Some("buyer"), handset))
      theirs <- app(devices).run(
        call(Method.DELETE, Some("other"), """{"device_token":"handset-token-1"}""")
      )
      afterOther <- devices.contents
      own <- app(devices).run(
        call(Method.DELETE, Some("buyer"), """{"device_token":"handset-token-1"}""")
      )
      afterOwn <- devices.contents
    yield
      assertEquals(theirs.status, Status.NoContent)
      assertEquals(afterOther.keySet, Set("handset-token-1"), "another principal removed it")
      assertEquals(own.status, Status.NoContent)
      assertEquals(afterOwn, Map.empty)
  }
