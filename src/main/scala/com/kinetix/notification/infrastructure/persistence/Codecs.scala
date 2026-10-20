package com.kinetix.notification.infrastructure.persistence

import io.circe.{Encoder, parser}

import com.kinetix.notification.domain.*

private[persistence] object Codecs:
  def templateTo(template: Template): String = template match
    case Template.OrderPaid          => "ORDER_PAID"
    case Template.OrderPacked        => "ORDER_PACKED"
    case Template.CourierAssigned    => "COURIER_ASSIGNED"
    case Template.OrderDelivered     => "ORDER_DELIVERED"
    case Template.CourierOffer       => "COURIER_OFFER"
    case Template.ReturnOpened       => "RETURN_OPENED"
    case Template.LiabilityStatement => "LIABILITY_STATEMENT"

  def templateFrom(raw: String): Option[Template] = raw match
    case "ORDER_PAID"          => Some(Template.OrderPaid)
    case "ORDER_PACKED"        => Some(Template.OrderPacked)
    case "COURIER_ASSIGNED"    => Some(Template.CourierAssigned)
    case "ORDER_DELIVERED"     => Some(Template.OrderDelivered)
    case "COURIER_OFFER"       => Some(Template.CourierOffer)
    case "RETURN_OPENED"       => Some(Template.ReturnOpened)
    case "LIABILITY_STATEMENT" => Some(Template.LiabilityStatement)
    case _                     => None

  def channelTo(channel: Channel): String = channel match
    case Channel.Push  => "PUSH"
    case Channel.Email => "EMAIL"

  def channelFrom(raw: String): Option[Channel] = raw match
    case "PUSH"  => Some(Channel.Push)
    case "EMAIL" => Some(Channel.Email)
    case _       => None

  def statusTo(status: DeliveryStatus): String = status match
    case DeliveryStatus.Pending     => "PENDING"
    case DeliveryStatus.Sent        => "SENT"
    case DeliveryStatus.Failed      => "FAILED"
    case DeliveryStatus.Unreachable => "UNREACHABLE"

  def statusFrom(raw: String): Option[DeliveryStatus] = raw match
    case "PENDING"     => Some(DeliveryStatus.Pending)
    case "SENT"        => Some(DeliveryStatus.Sent)
    case "FAILED"      => Some(DeliveryStatus.Failed)
    case "UNREACHABLE" => Some(DeliveryStatus.Unreachable)
    case _             => None

  def platformTo(platform: DevicePlatform): String = platform match
    case DevicePlatform.Android => "ANDROID"
    case DevicePlatform.Ios     => "IOS"

  def paramsTo(params: Map[String, String]): String =
    Encoder[Map[String, String]].apply(params).noSpaces

  def paramsFrom(raw: String): Option[Map[String, String]] =
    parser.decode[Map[String, String]](raw).toOption
