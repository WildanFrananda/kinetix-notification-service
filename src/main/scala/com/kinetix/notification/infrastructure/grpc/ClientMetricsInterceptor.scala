package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO
import cats.effect.std.Dispatcher
import io.grpc.{
  CallOptions,
  Channel,
  ClientCall,
  ClientInterceptor,
  ForwardingClientCall,
  ForwardingClientCallListener,
  Metadata,
  MethodDescriptor,
  Status
}

import com.kinetix.notification.infrastructure.observability.Metrics

final class ClientMetricsInterceptor(metrics: Metrics, dispatcher: Dispatcher[IO], peer: String)
  extends ClientInterceptor:
  override def interceptCall[Q, P](
    method: MethodDescriptor[Q, P],
    options: CallOptions,
    next: Channel
  ): ClientCall[Q, P] =
    new ForwardingClientCall.SimpleForwardingClientCall[Q, P](next.newCall(method, options)) {
      override def start(listener: ClientCall.Listener[P], headers: Metadata): Unit = {
        val counted =
          new ForwardingClientCallListener.SimpleForwardingClientCallListener[P](listener) {
            override def onClose(status: Status, trailers: Metadata): Unit = {
              dispatcher.unsafeRunAndForget(
                metrics.grpcClientCall(peer, method.getFullMethodName, status.getCode.name)
              )
              super.onClose(status, trailers)
            }
          }
        super.start(counted, headers)
      }
    }
