package com.kinetix.notification.infrastructure.grpc

import io.grpc.*

final class PeerAuthorizationInterceptor(allowed: Set[String]) extends ServerInterceptor:

  override def interceptCall[Q, S](
      call: ServerCall[Q, S],
      headers: Metadata,
      next: ServerCallHandler[Q, S]
  ): ServerCall.Listener[Q] =
    peerName(call) match
      case Some(peer) if allowed.contains(peer) => next.startCall(call, headers)
      case Some(peer)                           => refuse(call, s"'$peer' is not on this service's caller list")
      case None                                 => refuse(call, "the caller's certificate names no service")

  private def peerName[Q, S](call: ServerCall[Q, S]): Option[String] =
    Option(call.getAttributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)).flatMap { session =>
      scala.util
        .Try(session.getPeerPrincipal.getName)
        .toOption
        .flatMap(commonName)
    }

  private def commonName(distinguishedName: String): Option[String] =
    distinguishedName
      .split(',')
      .map(_.trim)
      .collectFirst { case part if part.startsWith("CN=") => part.drop(3) }
      .map(_.trim)
      .filter(_.nonEmpty)

  private def refuse[Q, S](call: ServerCall[Q, S], reason: String): ServerCall.Listener[Q] =
    call.close(Status.PERMISSION_DENIED.withDescription(reason), Metadata())
    new ServerCall.Listener[Q] {}
