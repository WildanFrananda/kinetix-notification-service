package com.kinetix.notification.infrastructure.grpc

import com.kinetix.notification.domain.NotificationError

private[infrastructure] object Codes:
  def of(error: NotificationError): String = error match
    case NotificationError.NoRecipient                   => "NO_RECIPIENT"
    case NotificationError.NoTemplate                    => "NO_TEMPLATE"
    case NotificationError.TemplateNotGranted(_, _)      => "TEMPLATE_NOT_GRANTED"
    case NotificationError.TemplateParamMissing(_, _)    => "TEMPLATE_PARAM_MISSING"
    case NotificationError.InvalidIdempotencyKey         => "INVALID_IDEMPOTENCY_KEY"
    case NotificationError.IdempotencyKeyReused(_)       => "IDEMPOTENCY_KEY_REUSED"
    case NotificationError.RecipientUnknown(_)           => "RECIPIENT_UNKNOWN"
    case NotificationError.DirectoryUnavailable(_)       => "DIRECTORY_UNAVAILABLE"
    case NotificationError.NoChannelAvailable(_)         => "NO_CHANNEL_AVAILABLE"
    case NotificationError.SenderRejected(_, _)          => "SENDER_REJECTED"
    case NotificationError.SenderUnavailable(_, _)       => "SENDER_UNAVAILABLE"
    case NotificationError.AddressGone(_, _)             => "ADDRESS_GONE"
    case NotificationError.StoreUnavailable(_, _)        => "STORE_UNAVAILABLE"
    case NotificationError.InvalidDeviceToken            => "INVALID_DEVICE_TOKEN"
    case NotificationError.InvalidPlatform               => "INVALID_PLATFORM"
    case NotificationError.InvalidNonce                  => "INVALID_NONCE"
    case NotificationError.VerificationNotPending        => "VERIFICATION_NOT_PENDING"
    case NotificationError.VerificationFailed(_)         => "VERIFICATION_FAILED"
    case NotificationError.VerificationAlreadyPending(_) => "VERIFICATION_ALREADY_PENDING"
    case NotificationError.VerificationLimitReached(_)   => "VERIFICATION_LIMIT_REACHED"

  val VerificationPending: String = "DEVICE_VERIFICATION_PENDING"
  val PrincipalRequired: String = "PRINCIPAL_REQUIRED"
