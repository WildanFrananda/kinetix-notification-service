package com.kinetix.notification.fakes

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, LongAdder}

import cats.effect.{IO, Resource}
import com.sun.net.httpserver.{HttpExchange, HttpServer}

final class JwksServer(server: HttpServer, requests: LongAdder, answering: AtomicBoolean):
  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}/.well-known/jwks.json"

  def fetches: Long = requests.sum()

  def goQuiet: IO[Unit] = IO(answering.set(false))

  def stop: IO[Unit] = IO.blocking(server.stop(0))

object JwksServer:
  def serving(keySet: String): Resource[IO, JwksServer] =
    Resource.make(IO.blocking(started(keySet)))(_.stop)

  private def started(keySet: String): JwksServer =
    val requests = LongAdder()
    val answering = AtomicBoolean(true)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val _ = server.createContext(
      "/.well-known/jwks.json",
      (exchange: HttpExchange) => answer(exchange, requests, answering, keySet)
    )

    server.start()
    JwksServer(server, requests, answering)

  private def answer(
    exchange: HttpExchange,
    requests: LongAdder,
    answering: AtomicBoolean,
    keySet: String
  ): Unit =
    requests.increment()
    val (status, body) =
      if answering.get() then (200, keySet) else (503, """{"error":"unavailable"}""")
    val bytes = body.getBytes(StandardCharsets.UTF_8)

    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
