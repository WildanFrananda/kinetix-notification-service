package com.kinetix.notification.infrastructure.grpc

import cats.effect.IO
import cats.effect.std.Dispatcher
import io.grpc.{
  ForwardingServerCall,
  Metadata,
  ServerCall,
  ServerCallHandler,
  ServerInterceptor,
  Status
}

import com.kinetix.notification.infrastructure.observability.Metrics

final class ServerMetricsInterceptor(metrics: Metrics, dispatcher: Dispatcher[IO])
  extends ServerInterceptor:
  override def interceptCall[Q, P](
    call: ServerCall[Q, P],
    headers: Metadata,
    next: ServerCallHandler[Q, P]
  ): ServerCall.Listener[Q] =
    val method = call.getMethodDescriptor.getFullMethodName
    val counted = new ForwardingServerCall.SimpleForwardingServerCall[Q, P](call) {
      override def close(status: Status, trailers: Metadata): Unit = {
        dispatcher.unsafeRunAndForget(metrics.grpcServerCall(method, status.getCode.name))
        super.close(status, trailers)
      }
    }
    next.startCall(counted, headers)
