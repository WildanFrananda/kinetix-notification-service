package com.kinetix.notification.infrastructure.persistence

import cats.effect.IO
import org.flywaydb.core.Flyway

import com.kinetix.notification.infrastructure.config.DatabaseSettings

object SchemaMigration:
  val Location: String = "classpath:db/migration"

  def run(settings: DatabaseSettings): IO[Int] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(settings.url, settings.user, settings.password)
        .locations(Location)
        .loggers("slf4j")
        .load()
        .migrate()
    }.map(_.migrationsExecuted)
