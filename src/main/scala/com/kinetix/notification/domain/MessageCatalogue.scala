package com.kinetix.notification.domain

final case class MessageCatalogue(entries: Map[Template, MessageCopy]):
  def copyFor(template: Template): Option[MessageCopy] = entries.get(template)

object MessageCatalogue:
  def of(entries: Map[Template, MessageCopy]): Either[String, MessageCatalogue] =
    val missing = Template.values.toList.filterNot(entries.contains)

    if missing.isEmpty then Right(MessageCatalogue(entries))
    else Left(s"the catalogue has no copy for: ${missing.map(_.toString).mkString(", ")}")
