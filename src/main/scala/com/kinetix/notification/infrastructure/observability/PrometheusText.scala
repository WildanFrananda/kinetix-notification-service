package com.kinetix.notification.infrastructure.observability

object PrometheusText:
  def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

  def labels(pairs: List[(String, String)]): String =
    if pairs.isEmpty then ""
    else pairs.map((name, value) => s"""$name="${escape(value)}"""").mkString("{", ",", "}")
