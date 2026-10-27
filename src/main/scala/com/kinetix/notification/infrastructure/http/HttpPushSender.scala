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
import com.kinetix.notification.infrastructure.observability.{Metrics, ProviderFailure}

final class HttpPushSender(
  client: Client[IO],
  endpoint: Uri,
  tokens: BearerTokens,
  time: TimeSource[IO],
  metrics: Metrics,
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
    tokens.token
      .flatMap: credential =>
        client
          .run(request(body, credential))
          .use(firstAnswer(credential))
      .flatMap:
        case Some(answer) => IO.pure(answer)
        case None         =>
          tokens.token.flatMap(fresh => client.run(request(body, fresh)).use(interpret))
      .handleErrorWith(throwable => unavailable(throwable.toString))

  private def request(body: Json, credential: String): Request[IO] =
    Request[IO](Method.POST, endpoint)
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, credential)))
      .withEntity(body)

  private def firstAnswer(
    credential: String
  )(response: Response[IO]): IO[Option[Either[NotificationError, Unit]]] =
    if response.status == Status.Unauthorized then
      response.bodyText.compile.string.flatMap: detail =>
        if FcmRefusal.read(detail).thirdPartyAuth then judge(response, detail).map(Some(_))
        else stale(credential, reported(response.status, detail)).as(None)
    else interpret(response).map(Some(_))

  private def stale(credential: String, cause: String): IO[Unit] =
    logger.warn(
      s"the push provider refused the access token in hand; asking once more with a fresh one: $cause"
    ) *> tokens.discard(credential)

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
    else response.bodyText.compile.string.flatMap(judge(response, _))

  private def judge(response: Response[IO], detail: String): IO[Either[NotificationError, Unit]] =
    val cause = reported(response.status, detail)
    val refusal = FcmRefusal.read(detail)

    response.status match
      case status if status.responseClass == Status.ServerError => unavailable(cause)
      case Status.TooManyRequests      => throttled(cause, ProviderPause.asked(response.headers))
      case _ if refusal.senderMismatch => foreign(cause)
      case Status.Unauthorized | Status.Forbidden => credentialRefused(cause)
      case _ if refusal.addressGone               => gone(cause)
      case _                                      => rejected(cause)

  private def reported(status: Status, detail: String): String =
    s"${status.code}: ${detail.take(300)}"

  private def unavailable(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.Unavailable) *>
      logger
        .warn(s"the push provider did not answer: $cause")
        .as(NotificationError.SenderUnavailable(Channel.Push, cause).asLeft)

  private def throttled(
    cause: String,
    asked: Option[Either[HttpDate, Long]]
  ): IO[Either[NotificationError, Unit]] =
    time.now.flatMap: at =>
      val pause = ProviderPause.of(asked, at, HttpPushSender.UnstatedQuotaPause)

      failed(ProviderFailure.Quota) *>
        logger
          .warn(
            s"the push provider is limiting this service's sends and asks for a pause of " +
              s"${pause.getSeconds} s: $cause"
          )
          .as(NotificationError.SenderThrottled(Channel.Push, at.plus(pause), cause).asLeft)

  private def credentialRefused(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.Credential) *>
      logger
        .error(
          "the push provider refused this service's own credentials; check " +
            "PUSH_PROVIDER_CREDENTIALS_B64 and the service account's permission to send in its " +
            s"Firebase project: $cause"
        )
        .as(NotificationError.SenderCredentialRefused(Channel.Push, cause).asLeft)

  private def rejected(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.Rejected) *>
      logger
        .error(s"the push provider refused a message: $cause")
        .as(NotificationError.SenderRejected(Channel.Push, cause).asLeft)

  private def foreign(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.AddressForeign) *>
      logger
        .warn(
          "the push provider says a device token belongs to another app; if every token says so, " +
            "PUSH_PROVIDER_URL and PUSH_PROVIDER_CREDENTIALS_B64 name the wrong Firebase project: " +
            cause
        )
        .as(NotificationError.ForeignDeviceToken(cause).asLeft)

  private def failed(failure: ProviderFailure): IO[Unit] =
    metrics.providerFailure(Channel.Push, failure)

  private def gone(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.AddressGone) *>
      logger
        .info(s"the push provider says a device token no longer exists: $cause")
        .as(NotificationError.AddressGone(Channel.Push, cause).asLeft)

object HttpPushSender:
  val ChallengeKind: String = "DEVICE_VERIFICATION"
  val UnstatedQuotaPause: Duration = Duration.ofSeconds(60)
