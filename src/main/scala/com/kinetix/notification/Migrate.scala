package com.kinetix.notification

import cats.effect.{ExitCode, IO, IOApp}

import com.kinetix.notification.infrastructure.config.DatabaseSettings
import com.kinetix.notification.infrastructure.persistence.SchemaMigration

object Migrate extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    for
      settings <- DatabaseSettings.load
      applied <- SchemaMigration.run(settings)
      _ <- IO.println(report(applied))
    yield ExitCode.Success

  private def report(applied: Int): String = applied match
    case 0 => "schema already up to date, nothing applied"
    case 1 => "1 migration applied"
    case n => s"$n migrations applied"
