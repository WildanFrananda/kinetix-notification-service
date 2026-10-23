package com.kinetix.notification.infrastructure.http

import java.time.{Duration, Instant}

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.typelevel.log4cats.Logger

import com.kinetix.notification.domain.{
  Address,
  Channel,
  DeviceNonce,
  DeviceToken,
  Message,
  NotificationError
}
import com.kinetix.notification.domain.ports.{DeviceChallengeSender, NotificationSender, TimeSource}

final class HttpPushSender(
  client: Client[IO],
  endpoint: Uri,
  bearer: IO[String],
  time: TimeSource[IO],
  logger: Logger[IO]
) extends NotificationSender[IO]
    with DeviceChallengeSender[IO]:
  val channel: Channel = Channel.Push

  def send(to: Address, message: Message): IO[Either[NotificationError, Unit]] =
    to match
      case Address.Email(_) => rejected("an email address was handed to the push sender")

      case Address.Push(token) =>
        post(notification(token, message))

  def sendChallenge(
    token: DeviceToken,
    nonce: DeviceNonce,
    expiresAt: Instant
  ): IO[Either[NotificationError, Unit]] =
    time.now.flatMap: at =>
      val remaining = Duration.between(at, expiresAt).getSeconds

      if remaining <= 0 then rejected(s"the proof expired at $expiresAt before it was sent")
      else post(challenge(token, nonce, expiresAt, remaining))

  private def post(body: Json): IO[Either[NotificationError, Unit]] =
    bearer
      .flatMap: credential =>
        val request = Request[IO](Method.POST, endpoint)
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, credential)))
          .withEntity(body)

        client.run(request).use(interpret)
      .handleErrorWith(throwable => unavailable(throwable.toString))

  private def notification(token: DeviceToken, message: Message): Json =
    Json.obj(
      "message" -> Json.obj(
        "token" -> token.value.asJson,
        "notification" -> Json.obj(
          "title" -> message.title.asJson,
          "body" -> message.body.asJson
        )
      )
    )

  private def challenge(
    token: DeviceToken,
    nonce: DeviceNonce,
    expiresAt: Instant,
    remainingSeconds: Long
  ): Json =
    Json.obj(
      "message" -> Json.obj(
        "token" -> token.value.asJson,
        "data" -> Json.obj(
          "kind" -> HttpPushSender.ChallengeKind.asJson,
          "nonce" -> nonce.value.asJson,
          "expires_at" -> expiresAt.toString.asJson
        ),
        "android" -> Json.obj(
          "priority" -> "HIGH".asJson,
          "ttl" -> s"${remainingSeconds}s".asJson
        ),
        "apns" -> Json.obj(
          "headers" -> Json.obj(
            "apns-push-type" -> "background".asJson,
            "apns-priority" -> "5".asJson,
            "apns-expiration" -> expiresAt.getEpochSecond.toString.asJson
          ),
          "payload" -> Json.obj("aps" -> Json.obj("content-available" -> 1.asJson))
        )
      )
    )

  private def interpret(response: Response[IO]): IO[Either[NotificationError, Unit]] =
    if response.status.isSuccess then IO.pure(().asRight)
    else
      response.bodyText.compile.string.flatMap: detail =>
        val trimmed = if detail.length > 300 then detail.take(300) else detail
        val reported = s"${response.status.code}: $trimmed"

        if response.status.responseClass == Status.ServerError then unavailable(reported)
        else if FcmRefusal.read(detail).addressGone then gone(reported)
        else rejected(reported)

  private def unavailable(cause: String): IO[Either[NotificationError, Unit]] =
    logger
      .warn(s"the push provider did not answer: $cause")
      .as(NotificationError.SenderUnavailable(Channel.Push, cause).asLeft)

  private def rejected(cause: String): IO[Either[NotificationError, Unit]] =
    logger
      .error(s"the push provider refused a message: $cause")
      .as(NotificationError.SenderRejected(Channel.Push, cause).asLeft)

  private def gone(cause: String): IO[Either[NotificationError, Unit]] =
    logger
      .info(s"the push provider says a device token no longer exists: $cause")
      .as(NotificationError.AddressGone(Channel.Push, cause).asLeft)

object HttpPushSender:
  val ChallengeKind: String = "DEVICE_VERIFICATION"
