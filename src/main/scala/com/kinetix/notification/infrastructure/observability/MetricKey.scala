package com.kinetix.notification.infrastructure.observability

final case class MetricKey(name: String, labels: List[(String, String)])
