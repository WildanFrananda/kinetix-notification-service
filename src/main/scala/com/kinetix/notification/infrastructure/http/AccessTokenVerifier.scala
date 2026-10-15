package com.kinetix.notification.infrastructure.http

import cats.effect.IO

trait AccessTokenVerifier:
  def verify(token: String): IO[TokenVerdict]
