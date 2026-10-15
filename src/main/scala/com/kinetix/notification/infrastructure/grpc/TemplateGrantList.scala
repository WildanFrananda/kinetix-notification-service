package com.kinetix.notification.infrastructure.grpc

import cats.syntax.all.*
import notification.v1.notification as wire

import com.kinetix.notification.domain.{Caller, Template, TemplateGrants}

object TemplateGrantList:
  def parse(variable: String, raw: String): Either[String, TemplateGrants] =
    raw
      .split(',')
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
      .traverse(entry(variable, _))
      .map(entries => TemplateGrants(entries.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap))

  def summary(grants: TemplateGrants): String =
    if grants.granted.isEmpty then "nobody"
    else
      grants.granted.toList
        .map((caller, templates) =>
          s"${caller.value} (${templates.toList.map(_.toString).sorted.mkString(", ")})"
        )
        .sorted
        .mkString("; ")

  private def entry(variable: String, raw: String): Either[String, (Caller, Template)] =
    raw.split(":", 2) match
      case Array(peer, name) =>
        (Caller.fromString(peer), wire.Template.fromName(name.trim).flatMap(Reads.template)) match
          case (Some(caller), Some(template)) => (caller -> template).asRight
          case _                              => malformed(variable, raw).asLeft
      case _ => malformed(variable, raw).asLeft

  private def malformed(variable: String, entry: String): String =
    s"'$entry' in $variable is not a template grant; write peer:TEMPLATE_NAME, with the name as " +
      "notification.v1.Template spells it (for example order:TEMPLATE_ORDER_PAID)."
