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

  test("a nonce is not blank and not longer than the limit") {
    assertEquals(DeviceNonce.fromString(" "), None)
    assertEquals(DeviceNonce.fromString("x" * (DeviceNonce.MaxLength + 1)), None)
    assertEquals(DeviceNonce.fromString(" n-1 ").map(_.value), Some("n-1"))
  }

  test("a device limit holds at least one handset") {
    assertEquals(DeviceLimit.fromInt(0), None)
    assertEquals(DeviceLimit.fromInt(-3), None)
    assertEquals(DeviceLimit.fromInt(10).map(_.value), Some(10))
  }

  test("a proof is judged by its expiry first, then its spent attempts, then the nonce") {
    val at = java.time.Instant.parse("2026-10-21T10:00:00Z")
    val issued = DeviceChallenge.issued(
      DeviceToken.fromString("handset-1").get,
      PrincipalId.fromString("p-1").get,
      DevicePlatform.Android,
      at
    )
    val spent = issued.copy(failedAttempts = DeviceChallenge.MaxFailedAttempts)

    assertEquals(issued.judge(nonceMatches = true, at), ChallengeVerdict.Matched)
    assertEquals(issued.judge(nonceMatches = false, at), ChallengeVerdict.Mismatched)
    assertEquals(issued.judge(nonceMatches = true, issued.expiresAt), ChallengeVerdict.Expired)
    assertEquals(spent.judge(nonceMatches = true, at), ChallengeVerdict.Exhausted)
    assertEquals(issued.expiresAt, at.plus(DeviceChallenge.Lifetime))
    assertEquals(issued.attemptsLeftAfterAMiss, DeviceChallenge.MaxFailedAttempts - 1)
  }

  test("a dead address is not worth repeating") {
    assert(!NotificationError.AddressGone(Channel.Push, "404: UNREGISTERED").isTransient)
  }
