package com.kinetix.notification.infrastructure.grpc

import munit.FunSuite

class SpiffePeerSuite extends FunSuite:
  test("an unset variable is the domain the estate runs today") {
    assertEquals(SpiffePeer.trustDomains(), List("kinetix.local"))
  }

  test("an id from the configured domain names its service") {
    assertEquals(
      SpiffePeer.serviceOf("spiffe://kinetix.local/service/order", "kinetix.local"),
      Some("order")
    )
  }

  test("an id from another trust domain names nobody") {
    assertEquals(SpiffePeer.serviceOf("spiffe://prod.kinetix/service/order", "kinetix.local"), None)
  }

  test("a cutover can accept both domains at once") {
    val both = List("kinetix.local", "prod.kinetix")

    assertEquals(SpiffePeer.serviceIn("spiffe://kinetix.local/service/order", both), Some("order"))
    assertEquals(SpiffePeer.serviceIn("spiffe://prod.kinetix/service/order", both), Some("order"))
  }

  test("a domain outside the list is still refused") {
    val both = List("kinetix.local", "prod.kinetix")

    assertEquals(SpiffePeer.serviceIn("spiffe://staging.kinetix/service/order", both), None)
  }

  test("a domain this one is merely a prefix of is refused") {
    assertEquals(
      SpiffePeer.serviceOf("spiffe://kinetix.local.example.com/service/order", "kinetix.local"),
      None
    )
  }

  test("a path is not a service name, and neither is nothing") {
    assertEquals(SpiffePeer.serviceOf("spiffe://kinetix.local/service/a/b", "kinetix.local"), None)
    assertEquals(SpiffePeer.serviceOf("spiffe://kinetix.local/service/", "kinetix.local"), None)
    assertEquals(SpiffePeer.serviceOf("spiffe://kinetix.local/agent/order", "kinetix.local"), None)
  }

  test("a common name is not read, whatever it says") {
    assertEquals(SpiffePeer.serviceIn("CN=order,O=kinetix", List("kinetix.local")), None)
    assertEquals(SpiffePeer.serviceIn("order", List("kinetix.local")), None)
  }
