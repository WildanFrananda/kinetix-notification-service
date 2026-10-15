package com.kinetix.notification.infrastructure.config

import cats.effect.IO

object Env:
  def required(name: String): IO[String] =
    IO(sys.env.get(name).map(_.trim).filter(_.nonEmpty)).flatMap:
      case Some(value) => IO.pure(value)
      case None        =>
        IO.raiseError(new IllegalStateException(s"$name is required and has no default."))

  def declared(name: String): IO[String] =
    IO(sys.env.get(name).map(_.trim)).flatMap:
      case Some(value) => IO.pure(value)
      case None        =>
        IO.raiseError(
          new IllegalStateException(
            s"$name must be set, even if empty: an empty value grants nobody, and an unset one " +
              "is a deploy that forgot it."
          )
        )

  def int(name: String): IO[Int] =
    required(name).flatMap: raw =>
      raw.toIntOption match
        case Some(port) => IO.pure(port)
        case None       =>
          IO.raiseError(new IllegalStateException(s"$name is '$raw', which is not a port number."))
