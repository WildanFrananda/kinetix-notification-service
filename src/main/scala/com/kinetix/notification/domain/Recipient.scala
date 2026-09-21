package com.kinetix.notification.domain

final case class Recipient(principal: PrincipalId, addresses: List[Address]):
  def addressOn(channel: Channel): Option[Address] =
    addresses.find(_.channel == channel)

  def channels: List[Channel] = addresses.map(_.channel).distinct

  def isReachable: Boolean = addresses.nonEmpty
