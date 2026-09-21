package com.kinetix.notification.domain

final case class Message(title: String, body: String)

object Message:

  def render(template: Template, params: Map[String, String]): Either[NotificationError, Message] =
    template match
      case Template.OrderPaid =>
        required(template, params, "order_number").map: order =>
          Message("Pembayaran diterima", s"Pesanan $order sudah dibayar dan sedang disiapkan.")

      case Template.OrderPacked =>
        required(template, params, "order_number").map: order =>
          Message("Pesanan dikemas", s"Pesanan $order sudah dikemas dan menunggu kurir.")

      case Template.CourierAssigned =>
        for
          order <- required(template, params, "order_number")
          awb <- required(template, params, "awb_number")
        yield Message("Kurir ditugaskan", s"Pesanan $order dalam perjalanan. Nomor resi: $awb.")

      case Template.OrderDelivered =>
        required(template, params, "order_number").map: order =>
          Message("Pesanan sampai", s"Pesanan $order sudah sampai di tujuan.")

      case Template.CourierOffer =>
        for
          order <- required(template, params, "order_number")
          fee <- required(template, params, "shipping_fee")
        yield Message("Tawaran antar", s"Antar pesanan $order, ongkos $fee. Terima sebelum kedaluwarsa.")

      case Template.ReturnOpened =>
        for
          order <- required(template, params, "order_number")
          rma <- required(template, params, "return_number")
        yield Message("Pengembalian dibuka", s"Pengembalian $rma untuk pesanan $order sudah dicatat.")

  private def required(
      template: Template,
      params: Map[String, String],
      name: String
  ): Either[NotificationError, String] =
    params.get(name).map(_.trim).filter(_.nonEmpty) match
      case Some(value) => Right(value)
      case None        => Left(NotificationError.TemplateParamMissing(template, name))
