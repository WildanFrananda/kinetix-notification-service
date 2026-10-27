package com.kinetix.notification.infrastructure.http

import io.circe.{ACursor, Json, parser}

private[http] final case class FcmRefusal(
  errorCode: Option[String],
  status: Option[String],
  message: Option[String],
  violatedFields: List[String]
):
  def addressGone: Boolean =
    errorCode.contains(FcmRefusal.Unregistered) || tokenInvalid

  def senderMismatch: Boolean =
    errorCode.contains(FcmRefusal.SenderIdMismatch)

  def thirdPartyAuth: Boolean =
    errorCode.contains(FcmRefusal.ThirdPartyAuthError)

  private def tokenInvalid: Boolean =
    errorCode.orElse(status).contains(FcmRefusal.InvalidArgument) &&
      (namesOnlyTheToken || (violatedFields.isEmpty && errorCode.isDefined && blamesTheToken))

  private def namesOnlyTheToken: Boolean =
    violatedFields.nonEmpty && violatedFields.forall(_ == FcmRefusal.TokenField)

  private def blamesTheToken: Boolean =
    message.exists(_.toLowerCase.contains(FcmRefusal.TokenWording))

private[http] object FcmRefusal:
  val Unregistered: String = "UNREGISTERED"
  val SenderIdMismatch: String = "SENDER_ID_MISMATCH"
  val ThirdPartyAuthError: String = "THIRD_PARTY_AUTH_ERROR"
  val InvalidArgument: String = "INVALID_ARGUMENT"
  val TokenField: String = "message.token"
  val TokenWording: String = "registration token"
  val FcmErrorType: String = "type.googleapis.com/google.firebase.fcm.v1.FcmError"
  val BadRequestType: String = "type.googleapis.com/google.rpc.BadRequest"

  def read(body: String): FcmRefusal =
    parser
      .parse(body)
      .toOption
      .fold(FcmRefusal(None, None, None, Nil)): json =>
        val error = json.hcursor.downField("error")
        val details = error.downField("details").as[List[Json]].getOrElse(Nil)

        FcmRefusal(
          errorCode =
            details.filter(typed(_, FcmErrorType)).flatMap(text(_, "errorCode")).headOption,
          status = error.get[String]("status").toOption,
          message = error.get[String]("message").toOption,
          violatedFields = details.filter(typed(_, BadRequestType)).flatMap(fields)
        )

  private def typed(detail: Json, kind: String): Boolean =
    text(detail, "@type").contains(kind)

  private def text(detail: Json, name: String): Option[String] =
    detail.hcursor.get[String](name).toOption

  private def fields(detail: Json): List[String] =
    violations(detail.hcursor.downField("fieldViolations")).flatMap(text(_, "field"))

  private def violations(cursor: ACursor): List[Json] =
    cursor.as[List[Json]].getOrElse(Nil)
