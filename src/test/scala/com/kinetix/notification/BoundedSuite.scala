package com.kinetix.notification

import scala.concurrent.duration.*

import munit.CatsEffectSuite

abstract class BoundedSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 20.seconds
