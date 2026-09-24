package com.kinetix.notification.domain

import munit.FunSuite

class ValueSuite extends FunSuite:
  test("a blank principal is not a principal") {
    assertEquals(PrincipalId.fromString(""), None)
    assertEquals(PrincipalId.fromString("   "), None)
    assertEquals(PrincipalId.fromString(" abc ").map(_.value), Some("abc"))
  }

  test("a blank device token is not a token") {
    assertEquals(DeviceToken.fromString(" "), None)
    assertEquals(DeviceToken.fromString("handset-1").map(_.value), Some("handset-1"))
  }

  test("an email address needs a local part, an at, and a dot in the domain") {
    assertEquals(
      EmailAddress.fromString("buyer@kinetix.test").map(_.value),
      Some("buyer@kinetix.test")
    )
    assertEquals(EmailAddress.fromString("buyer@localhost"), None)
    assertEquals(EmailAddress.fromString("@kinetix.test"), None)
    assertEquals(EmailAddress.fromString("buyer kinetix.test"), None)
    assertEquals(EmailAddress.fromString(""), None)
  }

  test("an address knows which channel carries it") {
    val email = Address.Email(EmailAddress.fromString("buyer@kinetix.test").get)
    val push = Address.Push(DeviceToken.fromString("handset-1").get)

    assertEquals(email.channel, Channel.Email)
    assertEquals(push.channel, Channel.Push)
  }

  test("only the unknowable failures are transient") {
    assert(NotificationError.DirectoryUnavailable("x").isTransient)
    assert(NotificationError.SenderUnavailable(Channel.Push, "x").isTransient)
    assert(NotificationError.StoreUnavailable("x").isTransient)

    assert(!NotificationError.SenderRejected(Channel.Push, "dead token").isTransient)
    assert(!NotificationError.RecipientUnknown(PrincipalId.fromString("p").get).isTransient)
    assert(!NotificationError.NoRecipient.isTransient)
  }

  test("a recipient reports the channels it can actually be reached on") {
    val recipient = Recipient(
      PrincipalId.fromString("p").get,
      List(Address.Email(EmailAddress.fromString("buyer@kinetix.test").get))
    )

    assertEquals(recipient.channels, List(Channel.Email))
    assertEquals(recipient.addressOn(Channel.Push), None)
    assert(recipient.isReachable)
  }
