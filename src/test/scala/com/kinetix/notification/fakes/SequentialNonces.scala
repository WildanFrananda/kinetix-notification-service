package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}

import com.kinetix.notification.domain.DeviceNonce
import com.kinetix.notification.domain.ports.NonceSource

final class SequentialNonces(drawn: Ref[IO, Int]) extends NonceSource[IO]:
  def next: IO[DeviceNonce] =
    drawn
      .updateAndGet(_ + 1)
      .flatMap(n =>
        IO.fromOption(DeviceNonce.fromString(s"nonce-$n"))(
          IllegalStateException(s"nonce-$n is not a nonce")
        )
      )

object SequentialNonces:
  def start: IO[SequentialNonces] = Ref.of[IO, Int](0).map(SequentialNonces(_))
