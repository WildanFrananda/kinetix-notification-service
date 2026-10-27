package com.kinetix.notification.infrastructure.http

import cats.effect.IO

trait BearerTokens:
  def token: IO[String]

  def discard(refused: String): IO[Unit]
