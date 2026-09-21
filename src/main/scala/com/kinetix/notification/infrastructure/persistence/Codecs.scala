package com.kinetix.notification.infrastructure.persistence

import io.circe.{Encoder, parser}

import com.kinetix.notification.domain.*

private[persistence] object Codecs:

  def templateTo(template: Template): String = template match
    case Template.OrderPaid       => "ORDER_PAID"
    case Template.OrderPacked     => "ORDER_PACKED"
    case Template.CourierAssigned => "COURIER_ASSIGNED"
    case Template.OrderDelivered  => "ORDER_DELIVERED"
    case Template.CourierOffer    => "COURIER_OFFER"
    case Template.ReturnOpened    => "RETURN_OPENED"

  def templateFrom(raw: String): Template = raw match
    case "ORDER_PAID"       => Template.OrderPaid
    case "ORDER_PACKED"     => Template.OrderPacked
    case "COURIER_ASSIGNED" => Template.CourierAssigned
    case "ORDER_DELIVERED"  => Template.OrderDelivered
    case "COURIER_OFFER"    => Template.CourierOffer
    case "RETURN_OPENED"    => Template.ReturnOpened
    case _                  => Template.OrderPaid

  def channelTo(channel: Channel): String = channel match
    case Channel.Push  => "PUSH"
    case Channel.Email => "EMAIL"

  def channelFrom(raw: String): Channel = raw match
    case "EMAIL" => Channel.Email
    case _       => Channel.Push

  def statusTo(status: DeliveryStatus): String = status match
    case DeliveryStatus.Pending     => "PENDING"
    case DeliveryStatus.Sent        => "SENT"
    case DeliveryStatus.Failed      => "FAILED"
    case DeliveryStatus.Unreachable => "UNREACHABLE"

  def statusFrom(raw: String): DeliveryStatus = raw match
    case "SENT"        => DeliveryStatus.Sent
    case "FAILED"      => DeliveryStatus.Failed
    case "UNREACHABLE" => DeliveryStatus.Unreachable
    case _             => DeliveryStatus.Pending

  def platformTo(platform: DevicePlatform): String = platform match
    case DevicePlatform.Android => "ANDROID"
    case DevicePlatform.Ios     => "IOS"

  def platformFrom(raw: String): DevicePlatform = raw match
    case "IOS" => DevicePlatform.Ios
    case _     => DevicePlatform.Android

  def paramsTo(params: Map[String, String]): String =
    Encoder[Map[String, String]].apply(params).noSpaces

  def paramsFrom(raw: String): Map[String, String] =
    parser.decode[Map[String, String]](raw).getOrElse(Map.empty)
