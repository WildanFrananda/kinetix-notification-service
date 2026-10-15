package com.kinetix.notification.infrastructure.grpc

import cats.syntax.all.*

import com.kinetix.notification.domain.Caller

final case class PeerAllowList(grants: Map[Caller, Option[Set[String]]]):
  def names(peer: Caller): Boolean = grants.contains(peer)

  def permits(peer: Caller, method: String): Boolean =
    val called = method.dropWhile(_ == '/')
    val service = called.takeWhile(_ != '/')

    if PeerAllowList.SchemaServices.contains(service) then true
    else
      grants.get(peer) match
        case None               => false
        case Some(None)         => true
        case Some(Some(scopes)) => scopes.contains(called) || scopes.contains(service)

  def unknownScopes(served: Set[String]): List[String] =
    grants.values.flatten.flatten.filterNot(served.contains).toList.distinct.sorted

  def summary: String =
    if grants.isEmpty then "nobody"
    else
      grants.toList
        .map:
          case (peer, None)         => peer.value
          case (peer, Some(scopes)) => s"${peer.value} (${scopes.toList.sorted.mkString(", ")})"
        .sorted
        .mkString("; ")

object PeerAllowList:
  val SchemaServices: Set[String] = Set(
    "grpc.reflection.v1.ServerReflection",
    "grpc.reflection.v1alpha.ServerReflection"
  )

  def parse(variable: String, raw: String): Either[String, PeerAllowList] =
    raw
      .split(',')
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
      .traverse(entry(variable, _))
      .map: entries =>
        PeerAllowList(
          entries
            .groupMap(_._1)(_._2)
            .view
            .mapValues(scopes => scopes.sequence.map(_.toSet))
            .toMap
        )

  private def entry(variable: String, raw: String): Either[String, (Caller, Option[String])] =
    raw.split("@", 2) match
      case Array(peer) =>
        Caller.fromString(peer).map(_ -> none[String]).toRight(malformed(variable, raw))
      case Array(peer, scope) =>
        val trimmed = scope.dropWhile(_ == '/')

        (Caller.fromString(peer), Option.when(validScope(trimmed))(trimmed)) match
          case (Some(caller), Some(named)) => (caller -> named.some).asRight
          case _                           => malformed(variable, raw).asLeft
      case _ => malformed(variable, raw).asLeft

  private def validScope(scope: String): Boolean =
    scope.split("/", -1) match
      case Array(service)         => service.contains('.') && !service.exists(_.isWhitespace)
      case Array(service, method) =>
        service.contains('.') && method.nonEmpty && !scope.exists(_.isWhitespace)
      case _ => false

  private def malformed(variable: String, entry: String): String =
    s"'$entry' in $variable is not a grant; write peer, peer@package.Service or " +
      "peer@package.Service/Method, with peer a lowercase service name."
