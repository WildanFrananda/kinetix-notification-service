package com.kinetix.notification.application

import cats.Monad
import cats.syntax.all.*

import com.kinetix.notification.domain.*
import com.kinetix.notification.domain.ports.*

final class Notify[F[_]: Monad](
  catalogue: MessageCatalogue,
  grants: TemplateGrants,
  directory: RecipientDirectory[F],
  senders: List[NotificationSender[F]],
  repository: NotificationRepository[F],
  retry: RetryPolicy[F],
  time: TimeSource[F],
  ids: NotificationIdSource[F]
):
  private val senderFor: Map[Channel, NotificationSender[F]] =
    senders.map(sender => sender.channel -> sender).toMap

  def apply(command: NotifyCommand): F[Either[NotificationError, NotifyOutcome]] =
    if !grants.permits(command.sender, command.template) then
      refuse(NotificationError.TemplateNotGranted(command.sender, command.template))
    else
      Message.render(catalogue, command.template, command.params) match
        case Left(error)    => refuse(error)
        case Right(message) =>
          command.idempotencyKey match
            case None      => accept(command, message)
            case Some(key) =>
              repository
                .findByIdempotencyKey(command.sender, key)
                .flatMap:
                  case Left(error)           => refuse(error)
                  case Right(Some(existing)) => replay(command, key, existing).pure[F]
                  case Right(None)           => accept(command, message)

  private def refuse(error: NotificationError): F[Either[NotificationError, NotifyOutcome]] =
    error.asLeft[NotifyOutcome].pure[F]

  private def replay(
    command: NotifyCommand,
    key: IdempotencyKey,
    existing: Notification
  ): Either[NotificationError, NotifyOutcome] =
    if existing.answers(command.recipient, command.template, command.params) then
      NotifyOutcome(existing.id, alreadyAccepted = true, existing.attempts).asRight
    else NotificationError.IdempotencyKeyReused(key).asLeft

  private def accept(
    command: NotifyCommand,
    message: Message
  ): F[Either[NotificationError, NotifyOutcome]] =
    directory
      .lookup(command.recipient)
      .flatMap:
        case Left(error)  => refuse(error)
        case Right(found) =>
          chosen(found, command.preferred) match
            case Nil       => refuse(NotificationError.NoChannelAvailable(command.recipient))
            case addresses =>
              record(command).flatMap:
                case Left(error)                          => refuse(error)
                case Right((id, Insertion.Inserted))      => deliver(id, addresses, message)
                case Right((_, Insertion.KeyAlreadyUsed)) => raced(command)

  private def record(
    command: NotifyCommand
  ): F[Either[NotificationError, (NotificationId, Insertion)]] =
    for
      id <- ids.next
      createdAt <- time.now
      notification = Notification(
        id,
        command.sender,
        command.recipient,
        command.template,
        command.params,
        command.idempotencyKey,
        Nil,
        createdAt
      )
      saved <- repository.save(notification)
    yield saved.map(insertion => (id, insertion))

  private def raced(command: NotifyCommand): F[Either[NotificationError, NotifyOutcome]] =
    command.idempotencyKey match
      case None =>
        refuse(
          NotificationError.StoreUnavailable(
            "a notification with no key was refused as a duplicate"
          )
        )
      case Some(key) =>
        repository
          .findByIdempotencyKey(command.sender, key)
          .map:
            case Left(error)           => error.asLeft
            case Right(Some(existing)) => replay(command, key, existing)
            case Right(None)           =>
              NotificationError
                .StoreUnavailable(
                  "the idempotency key was reported taken, but no notification holds it"
                )
                .asLeft

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
