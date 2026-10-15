package com.kinetix.notification.fakes

import cats.effect.IO

import com.kinetix.notification.infrastructure.http.{AccessTokenVerifier, TokenVerdict}

final class FakeAccessTokens(verdicts: Map[String, TokenVerdict]) extends AccessTokenVerifier:
  def verify(token: String): IO[TokenVerdict] =
    IO.pure(verdicts.getOrElse(token, TokenVerdict.Invalid("not a token this fake issued")))
