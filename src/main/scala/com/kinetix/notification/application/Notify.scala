package com.kinetix.notification.application

import cats.Monad
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.*

final class Notify[F[_]: Monad](
  catalogue: MessageCatalogue,
  directory: RecipientDirectory[F],
  senders: List[NotificationSender[F]],
  repository: NotificationRepository[F],
  retry: RetryPolicy[F],
  time: TimeSource[F],
  ids: NotificationIdSource[F]
):
  private val senderFor: Map[Channel, NotificationSender[F]] =
    senders.map(sender => sender.channel -> sender).toMap

  def apply(
    recipient: PrincipalId,
    template: Template,
    params: Map[String, String],
    preferred: List[Channel],
    idempotencyKey: Option[IdempotencyKey]
  ): F[Either[NotificationError, NotifyOutcome]] =
    Message.render(catalogue, template, params) match
      case Left(error)    => error.asLeft[NotifyOutcome].pure[F]
      case Right(message) =>
        alreadyAccepted(idempotencyKey).flatMap:
          case Left(error)           => error.asLeft[NotifyOutcome].pure[F]
          case Right(Some(existing)) => seen(existing).asRight[NotificationError].pure[F]
          case Right(None)           =>
            accept(recipient, template, params, preferred, idempotencyKey, message)

  private def alreadyAccepted(
    key: Option[IdempotencyKey]
  ): F[Either[NotificationError, Option[Notification]]] =
    key match
      case None      => none[Notification].asRight[NotificationError].pure[F]
      case Some(now) => repository.findByIdempotencyKey(now)

  private def seen(existing: Notification): NotifyOutcome =
    NotifyOutcome(existing.id, alreadyAccepted = true, existing.attempts)

  private def accept(
    recipient: PrincipalId,
    template: Template,
    params: Map[String, String],
    preferred: List[Channel],
    idempotencyKey: Option[IdempotencyKey],
    message: Message
  ): F[Either[NotificationError, NotifyOutcome]] =
    directory
      .lookup(recipient)
      .flatMap:
        case Left(error)  => error.asLeft[NotifyOutcome].pure[F]
        case Right(found) =>
          chosen(found, preferred) match
            case Nil =>
              NotificationError.NoChannelAvailable(recipient).asLeft[NotifyOutcome].pure[F]
            case addresses =>
              for
                id <- ids.next
                createdAt <- time.now
                record = Notification(
                  id,
                  recipient,
                  template,
                  params,
                  idempotencyKey,
                  Nil,
                  createdAt
                )
                saved <- repository.save(record)
                outcome <- saved match
                  case Left(error) => error.asLeft[NotifyOutcome].pure[F]
                  case Right(())   => deliver(id, addresses, message)
              yield outcome

  private def chosen(recipient: Recipient, preferred: List[Channel]): List[Address] =
    val usable = recipient.addresses.filter(address => senderFor.contains(address.channel))

    if preferred.isEmpty then usable
    else preferred.distinct.flatMap(channel => usable.find(_.channel == channel))

  private def deliver(
    id: NotificationId,
    addresses: List[Address],
    message: Message
  ): F[Either[NotificationError, NotifyOutcome]] =
    addresses
      .traverse(address => attempt(id, address, message))
      .map(attempts =>
        NotifyOutcome(id, alreadyAccepted = false, attempts).asRight[NotificationError]
      )

  private def attempt(
    id: NotificationId,
    address: Address,
    message: Message
  ): F[DeliveryAttempt] =
    val channel = address.channel

    senderFor.get(channel) match
      case None =>
        time.now.map(
          DeliveryAttempt(
            channel,
            DeliveryStatus.Unreachable,
            0,
            Some("no sender for this channel"),
            _
          )
        )

      case Some(sender) =>
        for
          result <- retry.retrying(sender.send(address, message))
          at <- time.now
          attempt = result match
            case Right(())   => DeliveryAttempt(channel, DeliveryStatus.Sent, 1, None, at)
            case Left(error) =>
              DeliveryAttempt(channel, DeliveryStatus.Failed, 1, Some(error.detail), at)
          _ <- repository.recordAttempt(id, attempt)
        yield attempt
