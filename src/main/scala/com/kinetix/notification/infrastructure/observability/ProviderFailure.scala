package com.kinetix.notification.infrastructure.observability

import com.kinetix.notification.domain.Channel

enum ProviderFailure(val label: String):
  case AddressGone extends ProviderFailure("address_gone")
  case AddressForeign extends ProviderFailure("address_foreign")
  case Rejected extends ProviderFailure("rejected")
  case Quota extends ProviderFailure("quota")
  case Credential extends ProviderFailure("credential")
  case Unavailable extends ProviderFailure("unavailable")

object ProviderFailure:
  def of(channel: Channel): List[ProviderFailure] = channel match
    case Channel.Push  => values.toList
    case Channel.Email => List(Rejected, Quota, Credential, Unavailable)
