package com.kinetix.notification.infrastructure.grpc

import com.kinetix.notification.domain.NotificationError

private[grpc] object Codes:
  def of(error: NotificationError): String = error match
    case NotificationError.NoRecipient                => "NO_RECIPIENT"
    case NotificationError.NoTemplate                 => "NO_TEMPLATE"
    case NotificationError.TemplateParamMissing(_, _) => "TEMPLATE_PARAM_MISSING"
    case NotificationError.RecipientUnknown(_)        => "RECIPIENT_UNKNOWN"
    case NotificationError.DirectoryUnavailable(_)    => "DIRECTORY_UNAVAILABLE"
    case NotificationError.NoChannelAvailable(_)      => "NO_CHANNEL_AVAILABLE"
    case NotificationError.SenderRejected(_, _)       => "SENDER_REJECTED"
    case NotificationError.SenderUnavailable(_, _)    => "SENDER_UNAVAILABLE"
    case NotificationError.StoreUnavailable(_)        => "STORE_UNAVAILABLE"
    case NotificationError.InvalidDeviceToken         => "INVALID_DEVICE_TOKEN"
