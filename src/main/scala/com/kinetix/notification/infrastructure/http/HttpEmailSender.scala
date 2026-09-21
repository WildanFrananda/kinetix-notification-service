package com.kinetix.notification.infrastructure.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.Authorization

import com.kinetix.notification.domain.{Address, Channel, EmailAddress, Message, NotificationError}
import com.kinetix.notification.domain.ports.NotificationSender

final class HttpEmailSender(
    client: Client[IO],
    endpoint: Uri,
    apiKey: String,
    from: EmailAddress
) extends NotificationSender[IO]:

  val channel: Channel = Channel.Email

  def send(to: Address, message: Message): IO[Either[NotificationError, Unit]] =
    to match
      case Address.Push(_) =>
        IO.pure(
          NotificationError
            .SenderRejected(Channel.Email, "a device token was handed to the email sender")
            .asLeft
        )

      case Address.Email(recipient) =>
        val request = Request[IO](Method.POST, endpoint)
          .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)))
          .withEntity(body(recipient, message))

        client
          .run(request)
          .use(interpret)
          .handleError(throwable =>
            NotificationError.SenderUnavailable(Channel.Email, throwable.getMessage).asLeft
          )

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
      response.bodyText.compile.string.map: detail =>
        val trimmed = if detail.length > 300 then detail.take(300) else detail

        if response.status.responseClass == Status.ServerError then
          NotificationError.SenderUnavailable(Channel.Email, s"${response.status.code}: $trimmed").asLeft
        else NotificationError.SenderRejected(Channel.Email, s"${response.status.code}: $trimmed").asLeft
