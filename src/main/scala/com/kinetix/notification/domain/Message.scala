package com.kinetix.notification.domain

final case class Message(title: String, body: String)

object Message:
  private val Placeholder = """\{([a-z_]+)\}""".r

  def render(
    catalogue: MessageCatalogue,
    template: Template,
    params: Map[String, String]
  ): Either[NotificationError, Message] =
    catalogue.copyFor(template) match
      case None =>
        Left(NotificationError.TemplateParamMissing(template, "copy"))

      case Some(copy) =>
        for
          title <- fill(template, copy.title, params)
          body <- fill(template, copy.body, params)
        yield Message(title, body)

  private def fill(
    template: Template,
    text: String,
    params: Map[String, String]
  ): Either[NotificationError, String] =
    val names = Placeholder.findAllMatchIn(text).map(_.group(1)).toList.distinct

    names.foldLeft(Right(text): Either[NotificationError, String]) { (acc, name) =>
      for
        filled <- acc
        value <- required(template, params, name)
      yield filled.replace(s"{$name}", value)
    }

  private def required(
    template: Template,
    params: Map[String, String],
    name: String
  ): Either[NotificationError, String] =
    params.get(name).map(_.trim).filter(_.nonEmpty) match
      case Some(value) => Right(value)
      case None        => Left(NotificationError.TemplateParamMissing(template, name))
