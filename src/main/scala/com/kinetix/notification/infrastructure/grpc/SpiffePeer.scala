package com.kinetix.notification.infrastructure.grpc

import java.security.cert.X509Certificate

import javax.net.ssl.SSLSession

import scala.jdk.CollectionConverters.*

object SpiffePeer:
  private val DefaultTrustDomain = "kinetix.local"

  private val UriSanType = 6

  def trustDomains(): List[String] =
    val configured = sys.env
      .getOrElse("KINETIX_TRUST_DOMAIN", "")
      .split(',')
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

    if configured.isEmpty then List(DefaultTrustDomain) else configured

  def serviceOf(id: String, domain: String): Option[String] =
    val prefix = s"spiffe://$domain/service/"

    if !id.startsWith(prefix) then None
    else
      val name = id.drop(prefix.length)
      if name.isEmpty || name.contains('/') then None else Some(name)

  def serviceIn(id: String, domains: List[String]): Option[String] =
    domains.view.flatMap(domain => serviceOf(id, domain)).headOption

  def uriNames(session: SSLSession): List[String] =
    val leaf = scala.util
      .Try(session.getPeerCertificates)
      .toOption
      .flatMap(_.headOption)
      .collect { case certificate: X509Certificate => certificate }

    leaf
      .flatMap(certificate => Option(certificate.getSubjectAlternativeNames))
      .map(_.asScala.toList)
      .getOrElse(Nil)
      .collect:
        case entry if entry.size >= 2 && entry.get(0) == UriSanType =>
          entry.get(1) match
            case uri: String => Some(uri)
            case _           => None
      .flatten

  def peerService(session: SSLSession): Option[String] =
    val domains = trustDomains()
    uriNames(session).view.flatMap(uri => serviceIn(uri, domains)).headOption
