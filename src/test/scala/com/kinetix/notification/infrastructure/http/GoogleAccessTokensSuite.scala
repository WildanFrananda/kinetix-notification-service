package com.kinetix.notification.infrastructure.http

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyPairGenerator, Signature}
import java.util.Base64

import cats.effect.{IO, Ref}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

class GoogleAccessTokensSuite extends CatsEffectSuite:
  private val keys =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private val pem =
    val encoded =
      Base64.getMimeEncoder(64, Array('\n'.toByte)).encodeToString(keys.getPrivate.getEncoded)
    s"-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n"

  private def accountJson(privateKey: String = pem): String =
    val escaped = privateKey.replace("\n", "\\n")
    s"""{"type":"service_account","project_id":"kinetix-push",
       |"client_email":"notifier@kinetix-push.iam.gserviceaccount.com",
       |"token_uri":"https://oauth2.googleapis.com/token",
       |"private_key":"$escaped"}""".stripMargin.replace("\n", "")

  private def base64(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(UTF_8))

  private def account = GoogleServiceAccount.fromBase64("PUSH", base64(accountJson()))

  private def granting(expiresIn: Int, calls: Ref[IO, List[UrlForm]]): Client[IO] =
    Client.fromHttpApp(
      HttpApp[IO]: request =>
        for
          form <- request.as[UrlForm]
          _ <- calls.update(_ :+ form)
          response <- Ok(
            s"""{"access_token":"token-${form.values.size}","expires_in":$expiresIn}"""
          )
            .map(_.withContentType(headers.`Content-Type`(MediaType.application.json)))
        yield response
    )

  test("a key that is not base64 is refused by name") {
    GoogleServiceAccount
      .fromBase64("PUSH_PROVIDER_CREDENTIALS_B64", "not base64 at all!!")
      .attempt
      .map:
        case Left(failure) =>
          assert(failure.getMessage.contains("PUSH_PROVIDER_CREDENTIALS_B64"), failure.getMessage)
        case Right(_) => fail("rubbish was accepted as a service account key")
  }

  test("base64 of something that is not a service account key is refused") {
    GoogleServiceAccount
      .fromBase64("PUSH", base64("""{"hello":"world"}"""))
      .attempt
      .map: result =>
        assert(result.isLeft, "a JSON object with none of the fields was accepted")
  }

  test("a private_key that is not a PKCS#8 RSA key is refused") {
    val wrong = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
    GoogleServiceAccount
      .fromBase64("PUSH", base64(accountJson(wrong)))
      .attempt
      .map: result =>
        assert(result.isLeft, "a key that is not a key was accepted")
  }

  test("the key is read, and the caller it names comes with it") {
    account.map: parsed =>
      assertEquals(parsed.clientEmail, "notifier@kinetix-push.iam.gserviceaccount.com")
      assertEquals(parsed.tokenUri, "https://oauth2.googleapis.com/token")
  }

  test("the assertion is a JWT Google will accept: RS256, its own signature, the messaging scope") {
    for
      calls <- Ref.of[IO, List[UrlForm]](Nil)
      parsed <- account
      tokens <- GoogleAccessTokens.create(granting(3600, calls), parsed)
      _ <- tokens.token
      sent <- calls.get
    yield
      val form = sent.head
      assertEquals(
        form.values.get("grant_type").flatMap(_.headOption),
        Some("urn:ietf:params:oauth:grant-type:jwt-bearer")
      )
      val jwt =
        form.values.get("assertion").flatMap(_.headOption).getOrElse(fail("no assertion sent"))
      val parts = jwt.split('.')
      assertEquals(parts.length, 3, s"not a three-part JWT: $jwt")

      val decode = (part: String) => String(Base64.getUrlDecoder.decode(part), UTF_8)
      val header = parse(decode(parts(0))).getOrElse(fail("header is not JSON"))
      val claims = parse(decode(parts(1))).getOrElse(fail("claims are not JSON"))

      assertEquals(header.hcursor.get[String]("alg").toOption, Some("RS256"))
      assertEquals(
        claims.hcursor.get[String]("iss").toOption,
        Some("notifier@kinetix-push.iam.gserviceaccount.com")
      )
      assertEquals(
        claims.hcursor.get[String]("scope").toOption,
        Some("https://www.googleapis.com/auth/firebase.messaging")
      )
      assertEquals(
        claims.hcursor.get[String]("aud").toOption,
        Some("https://oauth2.googleapis.com/token")
      )

      val issued = claims.hcursor.get[Long]("iat").toOption.getOrElse(0L)
      val expires = claims.hcursor.get[Long]("exp").toOption.getOrElse(0L)
      assertEquals(expires - issued, 3600L)

      val verifier = Signature.getInstance("SHA256withRSA")
      verifier.initVerify(keys.getPublic)
      verifier.update(s"${parts(0)}.${parts(1)}".getBytes(UTF_8))
      val signed = verifier.verify(Base64.getUrlDecoder.decode(parts(2)))
      assert(signed, "the assertion is not signed by the account's own key")
  }

  test("a token still in date is reused, not bought again") {
    for
      calls <- Ref.of[IO, List[UrlForm]](Nil)
      parsed <- account
      tokens <- GoogleAccessTokens.create(granting(3600, calls), parsed)
      first <- tokens.token
      second <- tokens.token
      sent <- calls.get
    yield
      assertEquals(first, second)
      assertEquals(sent.size, 1, "the token endpoint was asked twice for one live token")
  }

  test("a token inside the refresh margin is replaced before a notification finds out") {
    for
      calls <- Ref.of[IO, List[UrlForm]](Nil)
      parsed <- account
      tokens <- GoogleAccessTokens.create(granting(30, calls), parsed)
      _ <- tokens.token
      _ <- tokens.token
      sent <- calls.get
    yield assertEquals(sent.size, 2, "a nearly-expired token was reused")
  }
