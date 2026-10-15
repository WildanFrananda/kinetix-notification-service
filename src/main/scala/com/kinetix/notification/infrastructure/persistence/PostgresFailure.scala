package com.kinetix.notification.infrastructure.persistence

private[persistence] object PostgresFailure:
  def describe(failure: Throwable): String =
    Option(failure.getMessage).filter(_.nonEmpty).getOrElse(failure.getClass.getName)
