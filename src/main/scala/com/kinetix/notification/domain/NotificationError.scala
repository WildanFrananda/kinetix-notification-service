package com.kinetix.notification.domain

import java.time.Instant

enum NotificationError:
  case NoRecipient
  case NoTemplate
  case TemplateNotGranted(caller: Caller, template: Template)
  case TemplateParamMissing(template: Template, name: String)
  case InvalidIdempotencyKey
  case IdempotencyKeyReused(key: IdempotencyKey)
  case RecipientUnknown(principal: PrincipalId)
  case DirectoryUnavailable(cause: String)
  case NoChannelAvailable(principal: PrincipalId)
  case SenderRejected(channel: Channel, cause: String)
  case SenderUnavailable(channel: Channel, cause: String)
  case AddressGone(channel: Channel, cause: String)
  case StoreUnavailable(store: Store, cause: String)
  case InvalidDeviceToken
  case InvalidPlatform
  case InvalidNonce
  case VerificationNotPending
  case VerificationFailed(attemptsLeft: Int)
  case VerificationAlreadyPending(until: Instant)
  case VerificationLimitReached(retryAt: Instant)

  def detail: String = this match
    case NoRecipient =>
      s"recipient_principal_id is required and at most ${PrincipalId.MaxLength} characters"
    case NoTemplate                 => "template is required and must be one this service knows"
    case TemplateNotGranted(c, t)   => s"${c.value} is not granted $t"
    case TemplateParamMissing(t, n) => s"$t needs the parameter '$n', which the caller did not send"
    case InvalidIdempotencyKey      =>
      s"idempotency_key, when sent, must be 1 to ${IdempotencyKey.MaxLength} characters"
    case IdempotencyKeyReused(k) =>
      s"idempotency key '${k.value}' already names a different notification from this caller"
    case RecipientUnknown(p)     => s"identity holds no profile for ${p.value}"
    case DirectoryUnavailable(_) =>
      "identity could not be asked for the recipient's addresses; try again later"
    case NoChannelAvailable(p) => s"no usable address for ${p.value} on any channel"
    case SenderRejected(c, _)  =>
      s"the ${provider(c)} refused the message; repeating the request unchanged does not help"
    case SenderUnavailable(c, _)      => s"the ${provider(c)} did not answer; try again later"
    case AddressGone(Channel.Push, _) =>
      "the push provider says this device token no longer exists; " +
        "get a fresh FCM token and register it again"
    case AddressGone(Channel.Email, _) => "the email provider says this address no longer exists"
    case StoreUnavailable(Store.Deliveries, _) =>
      "the delivery record could not be reached; try again later"
    case StoreUnavailable(Store.Devices, _) =>
      "the device registry could not be reached; try again later"
    case InvalidDeviceToken =>
      s"device_token is required and at most ${DeviceToken.MaxLength} characters"
    case InvalidPlatform => "platform must be ANDROID or IOS"
    case InvalidNonce    => s"nonce is required and at most ${DeviceNonce.MaxLength} characters"
    case VerificationNotPending =>
      "no proof is pending for this device and principal; register the device again"
    case VerificationFailed(left) =>
      s"the nonce does not match the one sent to this device; $left attempt(s) left"
    case VerificationAlreadyPending(until) =>
      "a proof was already sent to this device for this principal; echo its nonce, " +
        s"or register again after $until"
    case VerificationLimitReached(retryAt) =>
      s"this device was sent ${DeviceChallenge.MaxIssuedPerWindow} proofs within an hour; " +
        s"register again after $retryAt"

  def isTransient: Boolean = this match
    case DirectoryUnavailable(_) => true
    case SenderUnavailable(_, _) => true
    case StoreUnavailable(_, _)  => true
    case _                       => false

  private def provider(channel: Channel): String = channel match
    case Channel.Push  => "push provider"
    case Channel.Email => "email provider"
