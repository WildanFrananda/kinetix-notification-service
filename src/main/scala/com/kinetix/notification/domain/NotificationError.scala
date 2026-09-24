package com.kinetix.notification.domain

enum NotificationError:
  case NoRecipient
  case NoTemplate
  case TemplateParamMissing(template: Template, name: String)
  case RecipientUnknown(principal: PrincipalId)
  case DirectoryUnavailable(cause: String)
  case NoChannelAvailable(principal: PrincipalId)
  case SenderRejected(channel: Channel, cause: String)
  case SenderUnavailable(channel: Channel, cause: String)
  case StoreUnavailable(cause: String)
  case InvalidDeviceToken

  def detail: String = this match
    case NoRecipient                => "recipient_principal_id is required"
    case NoTemplate                 => "template is required and must be one this service knows"
    case TemplateParamMissing(t, n) => s"$t needs the parameter '$n', which the caller did not send"
    case RecipientUnknown(p)        => s"identity holds no profile for ${p.value}"
    case DirectoryUnavailable(d)    => s"identity could not be asked: $d"
    case NoChannelAvailable(p)      => s"no usable address for ${p.value} on any channel"
    case SenderRejected(c, d)       => s"$c refused it: $d"
    case SenderUnavailable(c, d)    => s"$c did not answer: $d"
    case StoreUnavailable(d)        => s"the delivery record could not be reached: $d"
    case InvalidDeviceToken         => "device_token is required"

  def isTransient: Boolean = this match
    case DirectoryUnavailable(_) => true
    case SenderUnavailable(_, _) => true
    case StoreUnavailable(_)     => true
    case _                       => false
