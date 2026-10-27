package com.kinetix.notification.infrastructure.http

import java.time.Duration

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.typelevel.log4cats.Logger

import com.kinetix.notification.domain.{Address, Channel, EmailAddress, Message, NotificationError}
import com.kinetix.notification.domain.ports.{NotificationSender, TimeSource}
import com.kinetix.notification.infrastructure.observability.{Metrics, ProviderFailure}

final class HttpEmailSender(
  client: Client[IO],
  endpoint: Uri,
  apiKey: String,
  from: EmailAddress,
  time: TimeSource[IO],
  metrics: Metrics,
  logger: Logger[IO]
) extends NotificationSender[IO]:
  val channel: Channel = Channel.Email

  def send(to: Address, message: Message): IO[Either[NotificationError, Unit]] =
    to match
      case Address.Push(_) => rejected("a device token was handed to the email sender")

      case Address.Email(recipient) =>
        val request = Request[IO](Method.POST, endpoint)
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)))
          .withEntity(body(recipient, message))

        client
          .run(request)
          .use(interpret)
          .handleErrorWith(throwable => unavailable(throwable.toString))

  private def body(to: EmailAddress, message: Message): Json =
    Json.obj(
      "from" -> from.value.asJson,
      "to" -> Json.arr(to.value.asJson),
      "subject" -> message.title.asJson,
      "text" -> message.body.asJson
    )

  private def interpret(response: Response[IO]): IO[Either[NotificationError, Unit]] =
    if response.status.isSuccess then IO.pure(().asRight)
    else
      response.bodyText.compile.string.flatMap: detail =>
        val trimmed = if detail.length > 300 then detail.take(300) else detail
        val reported = s"${response.status.code}: $trimmed"

        response.status match
          case status if status.responseClass == Status.ServerError => unavailable(reported)
          case Status.TooManyRequests                               =>
            throttled(reported, ProviderPause.asked(response.headers))
          case Status.Unauthorized | Status.Forbidden => credentialRefused(reported)
          case _                                      => rejected(reported)

  private def unavailable(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.Unavailable) *>
      logger
        .warn(s"the email provider did not answer: $cause")
        .as(NotificationError.SenderUnavailable(Channel.Email, cause).asLeft)

  private def throttled(
    cause: String,
    asked: Option[Either[HttpDate, Long]]
  ): IO[Either[NotificationError, Unit]] =
    time.now.flatMap: at =>
      val pause = ProviderPause.of(asked, at, HttpEmailSender.UnstatedQuotaPause)

      failed(ProviderFailure.Quota) *>
        logger
          .warn(
            s"the email provider is limiting this service's sends and asks for a pause of " +
              s"${pause.getSeconds} s: $cause"
          )
          .as(NotificationError.SenderThrottled(Channel.Email, at.plus(pause), cause).asLeft)

  private def credentialRefused(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.Credential) *>
      logger
        .error(
          "the email provider refused this service's own key or sender; check EMAIL_PROVIDER_KEY " +
            s"and that the domain of EMAIL_FROM_ADDRESS is verified with the provider: $cause"
        )
        .as(NotificationError.SenderCredentialRefused(Channel.Email, cause).asLeft)

  private def rejected(cause: String): IO[Either[NotificationError, Unit]] =
    failed(ProviderFailure.Rejected) *>
      logger
        .error(s"the email provider refused a message: $cause")
        .as(NotificationError.SenderRejected(Channel.Email, cause).asLeft)

  private def failed(failure: ProviderFailure): IO[Unit] =
    metrics.providerFailure(Channel.Email, failure)

object HttpEmailSender:
  val UnstatedQuotaPause: Duration = Duration.ofSeconds(1)
