package com.kinetix.notification.infrastructure.http

import java.time.{Duration, Instant}

import org.http4s.headers.`Retry-After`
import org.http4s.{Headers, HttpDate}

object ProviderPause:
  val Longest: Duration = Duration.ofHours(1)

  def asked(headers: Headers): Option[Either[HttpDate, Long]] =
    headers.get[`Retry-After`].map(_.retry)

  def of(asked: Option[Either[HttpDate, Long]], at: Instant, unstated: Duration): Duration =
    val stated = asked match
      case Some(Right(seconds)) => Duration.ofSeconds(seconds)
      case Some(Left(date))     => Duration.between(at, date.toInstant)
      case None                 => unstated

    if stated.isNegative then Duration.ZERO
    else if stated.compareTo(Longest) > 0 then Longest
    else stated
