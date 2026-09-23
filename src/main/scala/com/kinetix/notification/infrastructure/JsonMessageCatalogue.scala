package com.kinetix.notification.infrastructure

import scala.io.Source
import scala.util.Using

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode

import com.kinetix.notification.domain.{MessageCatalogue, MessageCopy, Template}

object JsonMessageCatalogue:
  private final case class Entry(title: String, body: String)

  private given io.circe.Decoder[Entry] = io.circe.Decoder.forProduct2("title", "body")(Entry.apply)

  val DefaultResource: String = "/messages/id.json"

  def load(resource: String = DefaultResource): IO[MessageCatalogue] =
    for
      text <- read(resource)
      raw <- IO.fromEither(
        decode[Map[String, Entry]](text)
          .leftMap(error => IllegalStateException(s"$resource is not a message catalogue: $error"))
      )
      entries <- IO.fromEither(byTemplate(resource, raw))
      catalogue <- IO.fromEither(
        MessageCatalogue.of(entries).leftMap(reason => IllegalStateException(s"$resource: $reason"))
      )
    yield catalogue

  private def read(resource: String): IO[String] =
    IO.blocking(Option(getClass.getResourceAsStream(resource)))
      .flatMap:
        case None => IO.raiseError(IllegalStateException(s"no message catalogue at $resource"))
        case Some(stream) =>
          IO.blocking(Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString))

  private def byTemplate(
    resource: String,
    raw: Map[String, Entry]
  ): Either[Throwable, Map[Template, MessageCopy]] =
    val known = Template.values.map(template => template.toString -> template).toMap

    raw.toList
      .traverse: (name, entry) =>
        known.get(name) match
          case Some(template) => Right(template -> MessageCopy(entry.title, entry.body))
          case None           =>
            Left(IllegalStateException(s"$resource names a template this service has no: $name"))
      .map(_.toMap)
