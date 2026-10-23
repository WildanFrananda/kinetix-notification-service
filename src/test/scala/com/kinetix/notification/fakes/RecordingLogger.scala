package com.kinetix.notification.fakes

import cats.effect.{IO, Ref}
import org.typelevel.log4cats.Logger

final class RecordingLogger(val lines: Ref[IO, List[(String, String)]]) extends Logger[IO]:
  def error(message: => String): IO[Unit] = record("ERROR", message)
  def warn(message: => String): IO[Unit] = record("WARN", message)
  def info(message: => String): IO[Unit] = record("INFO", message)
  def debug(message: => String): IO[Unit] = record("DEBUG", message)
  def trace(message: => String): IO[Unit] = record("TRACE", message)

  def error(t: Throwable)(message: => String): IO[Unit] = record("ERROR", s"$message: $t")
  def warn(t: Throwable)(message: => String): IO[Unit] = record("WARN", s"$message: $t")
  def info(t: Throwable)(message: => String): IO[Unit] = record("INFO", s"$message: $t")
  def debug(t: Throwable)(message: => String): IO[Unit] = record("DEBUG", s"$message: $t")
  def trace(t: Throwable)(message: => String): IO[Unit] = record("TRACE", s"$message: $t")

  private def record(level: String, message: String): IO[Unit] =
    lines.update(_ :+ (level, message))

object RecordingLogger:
  def empty: IO[RecordingLogger] = Ref.of[IO, List[(String, String)]](Nil).map(RecordingLogger(_))
