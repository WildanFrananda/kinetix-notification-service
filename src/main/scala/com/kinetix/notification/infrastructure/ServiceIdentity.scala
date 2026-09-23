package com.kinetix.notification.infrastructure

import java.io.File

import cats.effect.IO
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.netty.handler.ssl.{ClientAuth, SslContext}

object ServiceIdentity:
  def server(pkiDir: String): IO[SslContext] =
    for
      files <- read(pkiDir)
      (ca, cert, key) = files
      context <- IO(
        GrpcSslContexts
          .configure(GrpcSslContexts.forServer(cert, key))
          .trustManager(ca)
          .clientAuth(ClientAuth.REQUIRE)
          .build()
      )
    yield context

  def client(pkiDir: String): IO[SslContext] =
    for
      files <- read(pkiDir)
      (ca, cert, key) = files
      context <- IO(
        GrpcSslContexts.forClient().trustManager(ca).keyManager(cert, key).build()
      )
    yield context

  private def read(pkiDir: String): IO[(File, File, File)] =
    val ca = File(pkiDir, "ca.pem")
    val cert = File(pkiDir, "tls.crt")
    val key = File(pkiDir, "tls.key")

    IO.raiseUnless(ca.isFile && cert.isFile && key.isFile)(
      IllegalStateException(
        s"the service PKI is mounted at $pkiDir and must hold ca.pem, tls.crt and tls.key; " +
          "issue it with kinetix-infrastructure/bin/kinetix-pki issue"
      )
    ).as((ca, cert, key))
