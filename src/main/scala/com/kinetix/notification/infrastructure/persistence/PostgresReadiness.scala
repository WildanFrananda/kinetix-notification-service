package com.kinetix.notification.infrastructure.persistence

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.slf4j.Slf4jLogger


object PostgresReadiness:
  def check(transactor: Transactor[IO]): IO[Boolean] =
    sql"SELECT 1"
      .query[Int]
      .unique
      .transact(transactor)
      .attempt
      .flatMap:
        case Right(_)      => IO.pure(true)
        case Left(failure) =>
          Slf4jLogger
            .getLogger[IO]
            .warn(failure)("readiness: the database did not answer")
            .as(false)
