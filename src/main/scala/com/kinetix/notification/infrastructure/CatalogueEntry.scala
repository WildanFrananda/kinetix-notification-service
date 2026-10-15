package com.kinetix.notification.infrastructure

import io.circe.Decoder

private[infrastructure] final case class CatalogueEntry(title: String, body: String)

private[infrastructure] object CatalogueEntry:
  given Decoder[CatalogueEntry] = Decoder.forProduct2("title", "body")(CatalogueEntry.apply)
