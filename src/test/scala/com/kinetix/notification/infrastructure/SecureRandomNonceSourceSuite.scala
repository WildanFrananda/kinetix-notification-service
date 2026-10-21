package com.kinetix.notification.infrastructure

import cats.syntax.all.*

import com.kinetix.notification.BoundedSuite

class SecureRandomNonceSourceSuite extends BoundedSuite:
  test("a nonce is 32 random bytes in URL-safe base64, and no two are alike") {
    for
      source <- SecureRandomNonceSource.create
      drawn <- List.fill(64)(source.next).sequence
    yield
      assert(drawn.forall(_.value.matches("[A-Za-z0-9_-]{43}")), drawn.map(_.value).toString)
      assertEquals(drawn.map(_.value).distinct.length, 64)
  }
