package com.kinetix.notification.infrastructure.http

import java.nio.charset.StandardCharsets

import cats.effect.IO
import io.circe.{Decoder, Json, parser}
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Header, HttpRoutes, Request, Response, Status}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

import com.kinetix.notification.application.{ForgetOwnDevice, RegisterDevice}
import com.kinetix.notification.domain.{DevicePlatform, DeviceToken, NotificationError, PrincipalId}
import com.kinetix.notification.infrastructure.grpc.Codes

object DeviceApi:
  val Path: String = "/api/v1/notifications/devices"
  val MaxBodyBytes: Int = 4096

  def apply(
    tokens: AccessTokenVerifier,
    register: RegisterDevice[IO],
    forget: ForgetOwnDevice[IO],
    logger: Logger[IO]
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case request @ POST -> Root / "api" / "v1" / "notifications" / "devices" =>
        authenticated(tokens, logger, request): principal =>
          body[DeviceBody](request): sent =>
            (DeviceToken.fromString(sent.deviceToken), platform(sent.platform)) match
              case (None, _) => refuse(Status.BadRequest, NotificationError.InvalidDeviceToken)
              case (_, None) => refuse(Status.BadRequest, NotificationError.InvalidPlatform)
              case (Some(token), Some(kind)) =>
                register(principal, token, kind).flatMap:
                  case Left(error)         => failed(logger, error)
                  case Right(alreadyKnown) =>
                    json(
                      Status.Ok,
                      Json.obj(
                        "registered" -> Json.True,
                        "already_registered" -> Json.fromBoolean(alreadyKnown)
                      )
                    )

      case request @ DELETE -> Root / "api" / "v1" / "notifications" / "devices" =>
        authenticated(tokens, logger, request): principal =>
          body[ForgetBody](request): sent =>
            DeviceToken.fromString(sent.deviceToken) match
              case None        => refuse(Status.BadRequest, NotificationError.InvalidDeviceToken)
              case Some(token) =>
                forget(principal, token).flatMap:
                  case Left(error) => failed(logger, error)
                  case Right(())   => NoContent()

  private def authenticated(
    tokens: AccessTokenVerifier,
    logger: Logger[IO],
    request: Request[IO]
  )(use: PrincipalId => IO[Response[IO]]): IO[Response[IO]] =
    request.headers.get[Authorization] match
      case Some(Authorization(Credentials.Token(AuthScheme.Bearer, token))) =>
        tokens
          .verify(token)
          .flatMap:
            case TokenVerdict.Valid(principal)    => use(principal)
            case TokenVerdict.Invalid(_)          => unauthenticated
            case TokenVerdict.Unverifiable(cause) =>
              logger.warn(s"identity's signing keys could not be read: $cause") *>
                code(Status.ServiceUnavailable, "TOKEN_UNVERIFIABLE")
      case _ => unauthenticated

  private def body[A: Decoder](request: Request[IO])(use: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body
      .take(MaxBodyBytes.toLong + 1)
      .compile
      .to(Array)
      .flatMap: bytes =>
        if bytes.length > MaxBodyBytes then code(Status.PayloadTooLarge, "BODY_TOO_LARGE")
        else
          parser.decode[A](String(bytes, StandardCharsets.UTF_8)) match
            case Left(_)     => code(Status.BadRequest, "MALFORMED_BODY")
            case Right(sent) => use(sent)

  private def platform(raw: String): Option[DevicePlatform] = raw.trim match
    case "ANDROID" => Some(DevicePlatform.Android)
    case "IOS"     => Some(DevicePlatform.Ios)
    case _         => None

  private def failed(logger: Logger[IO], error: NotificationError): IO[Response[IO]] =
    error match
      case NotificationError.StoreUnavailable(cause) =>
        logger.error(s"the device registry could not be reached: $cause") *>
          refuse(Status.ServiceUnavailable, error)
      case _ => refuse(Status.BadRequest, error)

  private def refuse(status: Status, error: NotificationError): IO[Response[IO]] =
    json(
      status,
      Json.obj(
        "error" -> Json.fromString(Codes.of(error)),
        "message" -> Json.fromString(error.detail)
      )
    )

  private def code(status: Status, name: String): IO[Response[IO]] =
    json(status, Json.obj("error" -> Json.fromString(name)))

  private val unauthenticated: IO[Response[IO]] =
    code(Status.Unauthorized, "UNAUTHENTICATED")
      .map(_.putHeaders(Header.Raw(CIString("WWW-Authenticate"), "Bearer")))

  private def json(status: Status, body: Json): IO[Response[IO]] =
    IO.pure(
      Response[IO](status)
        .withEntity(body.noSpaces)
        .putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))
    )
