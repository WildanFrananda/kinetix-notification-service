package com.kinetix.notification.infrastructure.config

import munit.FunSuite

class SettingsSuite extends FunSuite:
  test("the handset limit is a whole number of at least one, and nothing else starts") {
    assertEquals(Settings.deviceLimit(" 10 ").map(_.value), Right(10))
    assert(Settings.deviceLimit("0").isLeft)
    assert(Settings.deviceLimit("-1").isLeft)
    assert(Settings.deviceLimit("ten").isLeft)
    assert(Settings.deviceLimit("").isLeft)
  }
