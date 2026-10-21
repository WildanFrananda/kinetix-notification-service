package com.kinetix.notification.infrastructure.http

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.IO
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.{JWKSetCacheRefreshEvaluator, JWKSetSource}
import com.nimbusds.jose.proc.SecurityContext

final class WitnessedJWKSetSource(source: JWKSetSource[SecurityContext])
  extends JWKSetSource[SecurityContext]:
  private val answered = AtomicBoolean(false)

  def lastReadAnswered: IO[Boolean] = IO(answered.get())

  def getJWKSet(
    refresh: JWKSetCacheRefreshEvaluator,
    currentTime: Long,
    context: SecurityContext
  ): JWKSet =
    try
      val keys = source.getJWKSet(refresh, currentTime, context)

      answered.set(true)
      keys
    catch
      case failure: Exception =>
        answered.set(false)
        throw failure

  def close(): Unit = source.close()
