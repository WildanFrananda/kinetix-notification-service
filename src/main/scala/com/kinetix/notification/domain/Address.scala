package com.kinetix.notification.domain

enum Address:
  case Push(token: DeviceToken)
  case Email(address: EmailAddress)

  def channel: Channel = this match
    case Push(_)  => Channel.Push
    case Email(_) => Channel.Email
