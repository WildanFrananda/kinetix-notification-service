package com.kinetix.notification.infrastructure.config

import cats.effect.IO

final case class DatabaseSettings(url: String, user: String, password: String)

object DatabaseSettings:
  def load: IO[DatabaseSettings] =
    for
      url <- Env.required("DATABASE_URL")
      user <- Env.required("DB_USERNAME")
      password <- Env.required("DB_PASSWORD")
    yield DatabaseSettings(url, user, password)
