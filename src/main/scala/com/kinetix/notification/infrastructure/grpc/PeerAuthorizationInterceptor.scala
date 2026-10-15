package com.kinetix.notification.infrastructure.grpc

import io.grpc.*
import org.slf4j.LoggerFactory

import com.kinetix.notification.domain.Caller

final class PeerAuthorizationInterceptor(
  allowed: PeerAllowList,
  identify: Attributes => Option[Caller]
) extends ServerInterceptor:
  private val logger = LoggerFactory.getLogger(classOf[PeerAuthorizationInterceptor])

  override def interceptCall[Q, S](
    call: ServerCall[Q, S],
    headers: Metadata,
    next: ServerCallHandler[Q, S]
  ): ServerCall.Listener[Q] =
    val method = call.getMethodDescriptor.getFullMethodName

    identify(call.getAttributes) match
      case None =>
        logger.warn(s"refused a gRPC call to $method from a peer with no SPIFFE identity")
        refuse(
          call,
          Status.UNAUTHENTICATED,
          "a client certificate carrying a SPIFFE id is required"
        )

      case Some(peer) if allowed.permits(peer, method) =>
        CallerHeader.stamp(headers, peer)
        next.startCall(call, headers)

      case Some(peer) if allowed.names(peer) =>
        logger.warn(
          s"refused a gRPC call to $method from ${peer.value}, which is not permitted that method"
        )
        refuse(call, Status.PERMISSION_DENIED, s"service '${peer.value}' may not call $method")

      case Some(peer) =>
        logger.warn(
          s"refused a gRPC call to $method from ${peer.value}, which is not on the allow list"
        )
        refuse(call, Status.PERMISSION_DENIED, s"service '${peer.value}' may not call this server")

  private def refuse[Q, S](
    call: ServerCall[Q, S],
    status: Status,
    reason: String
  ): ServerCall.Listener[Q] =
    call.close(status.withDescription(reason), Metadata())
    new ServerCall.Listener[Q] {}

object PeerAuthorizationInterceptor:
  def spiffe(allowed: PeerAllowList): PeerAuthorizationInterceptor =
    PeerAuthorizationInterceptor(allowed, certificateOf)

  private def certificateOf(attributes: Attributes): Option[Caller] =
    Option(attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
      .flatMap(session => scala.util.Try(SpiffePeer.peerService(session)).toOption.flatten)
      .flatMap(Caller.fromString)
