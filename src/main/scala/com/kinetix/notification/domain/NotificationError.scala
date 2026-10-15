package com.kinetix.notification.domain

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
  case StoreUnavailable(cause: String)
  case InvalidDeviceToken
  case InvalidPlatform

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
    case DirectoryUnavailable(d) => s"identity could not be asked: $d"
    case NoChannelAvailable(p)   => s"no usable address for ${p.value} on any channel"
    case SenderRejected(c, d)    => s"$c refused it: $d"
    case SenderUnavailable(c, d) => s"$c did not answer: $d"
    case StoreUnavailable(_)     => "the delivery record could not be reached"
    case InvalidDeviceToken      =>
      s"device_token is required and at most ${DeviceToken.MaxLength} characters"
    case InvalidPlatform => "platform must be ANDROID or IOS"

  def isTransient: Boolean = this match
    case DirectoryUnavailable(_) => true
    case SenderUnavailable(_, _) => true
    case StoreUnavailable(_)     => true
    case _                       => false
