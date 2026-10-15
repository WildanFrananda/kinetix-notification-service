package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO
import io.grpc.Metadata

import com.kinetix.notification.domain.Caller

object CallerHeader:
  val Key: Metadata.Key[String] =
    Metadata.Key.of("x-kinetix-verified-caller", Metadata.ASCII_STRING_MARSHALLER)

  def stamp(headers: Metadata, caller: Caller): Unit =
    headers.discardAll(Key)
    headers.put(Key, caller.value)

  def read(headers: Metadata): IO[Caller] =
    IO.fromOption(Option(headers.get(Key)).flatMap(Caller.fromString))(
      IllegalStateException(
        "the call reached a handler without a verified caller; " +
          "PeerAuthorizationInterceptor must run first"
      )
    )
