package com.kinetix.notification.infrastructure.persistence

import java.time.Duration

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import io.circe.parser
import munit.TestOptions
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.application.{ForgetOwnDevice, RegisterDevice, VerifyDevice}
import com.kinetix.notification.domain.*
import com.kinetix.notification.fakes.{CountingRetry, FakeAccessTokens, Fixtures}
import com.kinetix.notification.infrastructure.config.DatabaseSettings
import com.kinetix.notification.infrastructure.http.{DeviceApi, HttpPushSender, TokenVerdict}
import com.kinetix.notification.infrastructure.retry.ExponentialBackoffRetry
import com.kinetix.notification.infrastructure.{SecureRandomNonceSource, SystemTimeSource}

class PostgresStoreSuite extends BoundedSuite:
  private val settings: Option[DatabaseSettings] =
    (
      sys.env.get("NOTIFICATION_TEST_DATABASE_URL"),
      sys.env.get("NOTIFICATION_TEST_DB_USERNAME"),
      sys.env.get("NOTIFICATION_TEST_DB_PASSWORD")
    ).mapN(DatabaseSettings.apply)

  private val database = ResourceSuiteLocalFixture(
    "postgres",
    settings match
      case None     => Resource.pure[IO, Option[Transactor[IO]]](None)
      case Some(db) =>
        for
          _ <- Resource.eval(
            IO.raiseUnless(db.url.split('/').lastOption.exists(_.contains("test")))(
              IllegalStateException(
                s"${db.url} does not name a test database; this suite truncates it"
              )
            )
          )
          _ <- Resource.eval(SchemaMigration.run(db))
          transactor <- HikariTransactor.newHikariTransactor[IO](
            driverClassName = "org.postgresql.Driver",
            url = db.url,
            user = db.user,
            pass = db.password,
            connectEC = scala.concurrent.ExecutionContext.global
          )
        yield Some(transactor)
  )

  override def munitFixtures = List(database)

  private def onPostgres(name: String)(body: Transactor[IO] => IO[Unit]): Unit =
    val options = TestOptions(name)

    if settings.isEmpty && !sys.env.contains("CI") then test(options.ignore)(IO.unit)
    else test(options)(emptied.flatMap(body))

  private def emptied: IO[Transactor[IO]] =
    IO(database())
      .flatMap:
        case Some(found) => IO.pure(found)
        case None        =>
          IO.raiseError(
            IllegalStateException(
              "CI must provide NOTIFICATION_TEST_DATABASE_URL, _DB_USERNAME and _DB_PASSWORD"
            )
          )
      .flatTap: xa =>
        sql"""
          TRUNCATE notification_attempts,
                   notifications,
                   notification_devices,
                   notification_device_challenges,
                   notification_device_challenge_issues
        """.update.run
          .transact(xa)
          .void

  private def unavailable[A](result: Either[NotificationError, A]): Boolean = result match
    case Left(NotificationError.StoreUnavailable(Store.Deliveries, _)) => true
    case _                                                             => false

  private val key: IdempotencyKey = IdempotencyKey.fromString("ORDER-42-PACKED").get

  private val limit: DeviceLimit = DeviceLimit.fromInt(10).get
  private val claimant: PrincipalId =
    PrincipalId.fromString("99999999-8888-7777-6666-555555555555").get
  private val handset: DeviceToken = DeviceToken.fromString("handset-token-1").get
  private val nonce: DeviceNonce = DeviceNonce.fromString("q8PZ1-nonce-sent-to-the-handset").get

  private def claimed(registry: PostgresDeviceRegistry): IO[Unit] =
    for
      _ <- registry.register(Fixtures.recipient, handset, DevicePlatform.Android, limit)
      _ <- registry.holdChallenge(issuedBy(claimant, Fixtures.at), nonce, Fixtures.at)
    yield ()

  private def issuedBy(who: PrincipalId, at: java.time.Instant): DeviceChallenge =
    DeviceChallenge.issued(handset, who, DevicePlatform.Ios, at)

  private def asking(bearer: String, path: String, body: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withEntity(body)
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, bearer)))

  private val registering: String = """{"device_token":"handset-token-1","platform":"ANDROID"}"""

  private def sent(
    id: String,
    from: Caller = Fixtures.sender,
    withKey: Option[IdempotencyKey] = Some(key)
  ): Notification =
    Notification(
      NotificationId.fromString(id).get,
      from,
      Fixtures.recipient,
      Template.OrderPacked,
      Fixtures.orderParams,
      withKey,
      Nil,
      Fixtures.at
    )

  onPostgres("a key is taken once per sender, and another sender may use the same key") { xa =>
    val store = PostgresNotificationRepository(xa, NoOpLogger[IO])
    val keyless = List(sent("NTF-4", withKey = None), sent("NTF-5", withKey = None))

    for
      first <- store.save(sent("NTF-1"))
      again <- store.save(sent("NTF-2"))
      other <- store.save(sent("NTF-3", from = Fixtures.otherSender))
      unkeyed <- keyless.traverse(store.save)
    yield
      assertEquals(first, Right(Insertion.Inserted))
      assertEquals(again, Right(Insertion.KeyAlreadyUsed))
      assertEquals(other, Right(Insertion.Inserted))
      assertEquals(unkeyed, List(Right(Insertion.Inserted), Right(Insertion.Inserted)))
  }

  onPostgres("every template the service knows is one the table accepts and reads back") { xa =>
    val store = PostgresNotificationRepository(xa, NoOpLogger[IO])

    Template.values.toList.zipWithIndex
      .traverse: (template, i) =>
        val id = NotificationId.fromString(s"NTF-T$i").get
        val notification = sent(s"NTF-T$i", withKey = None).copy(template = template)

        store.save(notification) *> store.find(Fixtures.sender, id).map(found => (template, found))
      .map: results =>
        results.foreach: (template, found) =>
          assertEquals(found.map(_.map(_.template)), Right(Some(template)), s"$template")
  }

  onPostgres("two first saves of one key at once: exactly one wins, the other is told so") { xa =>
    val store = PostgresNotificationRepository(xa, NoOpLogger[IO])

    (store.save(sent("NTF-A")), store.save(sent("NTF-B"))).parTupled.map: (a, b) =>
      assertEquals(
        Set(a, b),
        Set(Right(Insertion.Inserted), Right(Insertion.KeyAlreadyUsed))
      )
  }

  onPostgres("a notification reads back whole, and only for the service that sent it") { xa =>
    val store = PostgresNotificationRepository(xa, NoOpLogger[IO])
    val id = NotificationId.fromString("NTF-1").get
    val failed = DeliveryAttempt(
      Channel.Email,
      DeliveryStatus.Failed,
      1,
      Some("timeout"),
      Fixtures.at
    )
    val delivered = DeliveryAttempt(Channel.Email, DeliveryStatus.Sent, 1, None, Fixtures.at)

    for
      _ <- store.save(sent("NTF-1"))
      _ <- store.recordAttempt(id, failed)
      _ <- store.recordAttempt(id, delivered)
      ours <- store.find(Fixtures.sender, id)
      theirs <- store.find(Fixtures.otherSender, id)
      byKey <- store.findByIdempotencyKey(Fixtures.sender, key)
      byTheirKey <- store.findByIdempotencyKey(Fixtures.otherSender, key)
    yield
      assertEquals(ours.map(_.map(_.params)), Right(Some(Fixtures.orderParams)))
      assertEquals(ours.map(_.map(_.sender)), Right(Some(Fixtures.sender)))
      assertEquals(
        ours.map(_.toList.flatMap(_.attempts).map(a => (a.status, a.attempts))),
        Right(List((DeliveryStatus.Sent, 2)))
      )
      assertEquals(theirs, Right(None))
      assertEquals(byKey.map(_.map(_.id)), Right(Some(id)))
      assertEquals(byTheirKey, Right(None))
  }

  onPostgres("a row this service cannot read is a store failure, never a guess") { xa =>
    val store = PostgresNotificationRepository(xa, NoOpLogger[IO])
    val odd =
      sql"""
        INSERT INTO notifications (id, sender, recipient_principal_id, template, params)
        VALUES ('NTF-ODD', 'order', 'p-1', 'ORDER_PAID', '{"order_number": 7}'::jsonb)
      """
    val unknown =
      sql"""
        INSERT INTO notifications (id, sender, recipient_principal_id, template)
        VALUES ('NTF-NEW', 'order', 'p-1', 'ORDER_REFUNDED')
      """

    for
      _ <- odd.update.run.transact(xa)
      read <- store.find(Fixtures.sender, NotificationId.fromString("NTF-ODD").get)
      refused <- unknown.update.run.transact(xa).attempt
    yield
      assert(unavailable(read), s"a row with a number for a parameter was read as $read")
      assert(refused.isLeft, "the table accepted a template this service does not know")
  }

  onPostgres("a handset is forgotten only by the principal it belongs to") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val owner = Fixtures.recipient
    val stranger = PrincipalId.fromString("99999999-8888-7777-6666-555555555555").get
    val token = DeviceToken.fromString("handset-token-1").get

    for
      _ <- registry.register(owner, token, DevicePlatform.Android, limit)
      _ <- registry.forgetOwned(stranger, token)
      afterStranger <- registry.tokensFor(owner)
      _ <- registry.forgetOwned(owner, token)
      afterOwner <- registry.tokensFor(owner)
    yield
      assertEquals(afterStranger, Right(List(token)))
      assertEquals(afterOwner, Right(Nil))
  }

  onPostgres("a token another principal holds stays with them when someone else registers it") {
    xa =>
      val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])

      for
        first <- registry.register(Fixtures.recipient, handset, DevicePlatform.Android, limit)
        again <- registry.register(Fixtures.recipient, handset, DevicePlatform.Ios, limit)
        taken <- registry.register(claimant, handset, DevicePlatform.Ios, limit)
        kept <- registry.tokensFor(Fixtures.recipient)
        arrived <- registry.tokensFor(claimant)
        holder <- registry.holderOf(handset)
      yield
        assertEquals(first, Right(DeviceClaim.Added))
        assertEquals(again, Right(DeviceClaim.Refreshed))
        assertEquals(taken, Right(DeviceClaim.HeldByAnother))
        assertEquals(kept, Right(List(handset)))
        assertEquals(arrived, Right(Nil))
        assertEquals(holder, Right(Some(Fixtures.recipient)))
  }

  onPostgres("a nonce is kept as its SHA-256 digest, never as itself") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val expected = java.util.HexFormat
      .of()
      .formatHex(
        java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(nonce.value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      )

    for
      _ <- claimed(registry)
      rows <-
        sql"SELECT nonce_digest, platform, failed_attempts FROM notification_device_challenges"
          .query[(String, String, Int)]
          .to[List]
          .transact(xa)
    yield assertEquals(rows, List((expected, "IOS", 0)))
  }

  onPostgres("only the claimant's own matching nonce moves the token, and only once") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val during = Fixtures.at.plusSeconds(60)
    val wrong = DeviceNonce.fromString("guessed").get

    for
      _ <- claimed(registry)
      byHolder <- registry.redeem(Fixtures.recipient, handset, nonce, during, limit)
      missed <- registry.redeem(claimant, handset, wrong, during, limit)
      verified <- registry.redeem(claimant, handset, nonce, during, limit)
      again <- registry.redeem(claimant, handset, nonce, during, limit)
      holder <- registry.holderOf(handset)
      left <- sql"SELECT COUNT(*) FROM notification_device_challenges"
        .query[Int]
        .unique
        .transact(xa)
    yield
      assertEquals(byHolder, Right(Redemption.NotPending))
      assertEquals(missed, Right(Redemption.Refused(DeviceChallenge.MaxFailedAttempts - 1)))
      assertEquals(verified, Right(Redemption.Verified))
      assertEquals(again, Right(Redemption.NotPending))
      assertEquals(holder, Right(Some(claimant)))
      assertEquals(left, 0)
  }

  onPostgres("a nonce presented at or after its expiry is spent and moves nothing") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val expiry = Fixtures.at.plus(DeviceChallenge.Lifetime)

    for
      _ <- claimed(registry)
      late <- registry.redeem(claimant, handset, nonce, expiry, limit)
      retried <- registry.redeem(claimant, handset, nonce, Fixtures.at, limit)
      holder <- registry.holderOf(handset)
    yield
      assertEquals(late, Right(Redemption.NotPending))
      assertEquals(retried, Right(Redemption.NotPending), "an expired proof must not survive")
      assertEquals(holder, Right(Some(Fixtures.recipient)))
  }

  onPostgres("five wrong nonces spend the proof, and the right one afterwards moves nothing") {
    xa =>
      val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
      val wrong = DeviceNonce.fromString("guessed").get
      val during = Fixtures.at.plusSeconds(60)

      for
        _ <- claimed(registry)
        misses <- List
          .fill(DeviceChallenge.MaxFailedAttempts)(wrong)
          .traverse(registry.redeem(claimant, handset, _, during, limit))
        late <- registry.redeem(claimant, handset, nonce, during, limit)
        holder <- registry.holderOf(handset)
      yield
        assertEquals(
          misses,
          List(4, 3, 2, 1, 0).map(left => Right(Redemption.Refused(left)))
        )
        assertEquals(late, Right(Redemption.NotPending))
        assertEquals(holder, Right(Some(Fixtures.recipient)))
  }

  onPostgres("a claim holds one proof until it expires, and a new one then starts afresh") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val wrong = DeviceNonce.fromString("guessed").get
    val fresh = DeviceNonce.fromString("a-second-nonce").get
    val during = Fixtures.at.plusSeconds(60)
    val expiry = Fixtures.at.plus(DeviceChallenge.Lifetime)

    for
      _ <- claimed(registry)
      _ <- registry.redeem(claimant, handset, wrong, during, limit)
      again <- registry.holdChallenge(issuedBy(claimant, during), fresh, during)
      unmoved <- sql"SELECT failed_attempts FROM notification_device_challenges"
        .query[Int]
        .unique
        .transact(xa)
      renewed <- registry.holdChallenge(issuedBy(claimant, expiry), fresh, expiry)
      stale <- registry.redeem(claimant, handset, nonce, expiry.plusSeconds(1), limit)
      verified <- registry.redeem(claimant, handset, fresh, expiry.plusSeconds(1), limit)
    yield
      assertEquals(again, Right(ChallengeHold.AlreadyPending(expiry)))
      assertEquals(unmoved, 1, "a refused hold must not reset the misses")
      assertEquals(renewed, Right(ChallengeHold.Held))
      assertEquals(stale, Right(Redemption.Refused(DeviceChallenge.MaxFailedAttempts - 1)))
      assertEquals(verified, Right(Redemption.Verified))
  }

  onPostgres("a token is held at most three proofs an hour, whoever claims it") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val claimants = (1 to 4).toList.map(n => PrincipalId.fromString(s"claimant-$n").get)
    val nonces = (1 to 5).toList.map(n => DeviceNonce.fromString(s"nonce-$n").get)
    val hourLater = Fixtures.at.plus(DeviceChallenge.IssueWindow)

    for
      holds <- claimants
        .zip(nonces)
        .zipWithIndex
        .traverse:
          case ((who, sent), minute) =>
            val at = Fixtures.at.plusSeconds(60L * minute)

            registry.holdChallenge(issuedBy(who, at), sent, at)
      late <- registry.holdChallenge(issuedBy(claimants(3), hourLater), nonces(4), hourLater)
      issued <- sql"SELECT COUNT(*) FROM notification_device_challenge_issues"
        .query[Int]
        .unique
        .transact(xa)
    yield
      assertEquals(holds.take(3), List.fill(3)(Right(ChallengeHold.Held)))
      assertEquals(holds(3), Right(ChallengeHold.LimitReached(hourLater)))
      assertEquals(late, Right(ChallengeHold.Held))
      assertEquals(issued, 3, "issues older than the window are pruned when a proof is held")
  }

  onPostgres(
    "a proof the provider did not take is released, and does not count against the token"
  ) { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val other = DeviceNonce.fromString("someone-elses-nonce").get

    for
      _ <- claimed(registry)
      _ <- registry.releaseChallenge(issuedBy(claimant, Fixtures.at), other)
      kept <- sql"SELECT COUNT(*) FROM notification_device_challenges"
        .query[Int]
        .unique
        .transact(xa)
      _ <- registry.releaseChallenge(issuedBy(claimant, Fixtures.at), nonce)
      rows <- sql"""
          SELECT (SELECT COUNT(*) FROM notification_device_challenges),
                 (SELECT COUNT(*) FROM notification_device_challenge_issues)
        """.query[(Int, Int)].unique.transact(xa)
      again <- registry.holdChallenge(issuedBy(claimant, Fixtures.at), nonce, Fixtures.at)
    yield
      assertEquals(kept, 1, "a release names its own nonce, and only that proof goes")
      assertEquals(rows, (0, 0))
      assertEquals(again, Right(ChallengeHold.Held))
  }

  onPostgres("claims of one token held at once are counted under one lock") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val claimants = (1 to 8).toList.map(n => PrincipalId.fromString(s"claimant-$n").get)

    val limited = ChallengeHold.LimitReached(Fixtures.at.plus(DeviceChallenge.IssueWindow))

    for holds <- claimants.parTraverse(who =>
        registry.holdChallenge(issuedBy(who, Fixtures.at), nonce, Fixtures.at)
      )
    yield
      assertEquals(holds.count(_ == Right(ChallengeHold.Held)), 3)
      assertEquals(holds.count(_ == Right(limited)), 5)
  }

  onPostgres("expired proofs are cleared whenever a new one is held") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val longAgo = Fixtures.at.minus(Duration.ofDays(1))

    for
      _ <- registry.holdChallenge(issuedBy(claimant, longAgo), nonce, longAgo)
      _ <- registry.holdChallenge(
        DeviceChallenge.issued(
          DeviceToken.fromString("handset-token-2").get,
          claimant,
          DevicePlatform.Ios,
          Fixtures.at
        ),
        nonce,
        Fixtures.at
      )
      tokens <- sql"SELECT device_token FROM notification_device_challenges"
        .query[String]
        .to[List]
        .transact(xa)
    yield assertEquals(tokens, List("handset-token-2"))
  }

  onPostgres("a principal keeps the newest handsets up to the limit, however they arrive") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val two = DeviceLimit.fromInt(2).get
    val tokens = List("first", "second", "third").map(DeviceToken.fromString(_).get)

    for
      claims <- tokens.traverse(registry.register(claimant, _, DevicePlatform.Android, two))
      kept <- registry.tokensFor(claimant)
      _ <- registry.register(Fixtures.recipient, handset, DevicePlatform.Android, two)
      _ <- registry.holdChallenge(issuedBy(claimant, Fixtures.at), nonce, Fixtures.at)
      verified <- registry.redeem(claimant, handset, nonce, Fixtures.at.plusSeconds(1), two)
      afterProof <- registry.tokensFor(claimant)
    yield
      assertEquals(claims, List.fill(3)(Right(DeviceClaim.Added)))
      assertEquals(kept, Right(tokens.drop(1).reverse))
      assertEquals(verified, Right(Redemption.Verified))
      assertEquals(afterProof, Right(List(handset, tokens(2))))
  }

  onPostgres("the same nonce echoed twice at once moves the token exactly once") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val during = Fixtures.at.plusSeconds(60)

    for
      _ <- claimed(registry)
      both <- (
        registry.redeem(claimant, handset, nonce, during, limit),
        registry.redeem(claimant, handset, nonce, during, limit)
      ).parTupled
    yield assertEquals(
      Set(both._1, both._2),
      Set(Right(Redemption.Verified), Right(Redemption.NotPending))
    )
  }

  onPostgres("handsets registered at once by one principal still respect the limit") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val one = DeviceLimit.fromInt(1).get
    val tokens = (1 to 8).toList.map(n => DeviceToken.fromString(s"handset-$n").get)

    for
      _ <- tokens.parTraverse(registry.register(claimant, _, DevicePlatform.Android, one))
      kept <- registry.tokensFor(claimant)
    yield assertEquals(kept.map(_.length), Right(1))
  }

  onPostgres(
    "over HTTP, the claimant takes the token with the nonce FCM was handed, and no other"
  ) { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val tokens = FakeAccessTokens(
      Map(
        "holder" -> TokenVerdict.Valid(Fixtures.recipient),
        "claimant" -> TokenVerdict.Valid(claimant)
      )
    )
    val time = SystemTimeSource()

    for
      pushed <- Ref.of[IO, List[String]](Nil)
      fcm = Client.fromHttpApp(
        HttpApp[IO](request =>
          request.bodyText.compile.string.flatMap(body => pushed.update(_ :+ body)) *> Ok("{}")
        )
      )
      push = HttpPushSender(
        fcm,
        Uri.unsafeFromString("https://fcm.googleapis.com/v1/projects/kinetix-test/messages:send"),
        IO.pure("access-token"),
        time,
        NoOpLogger[IO]
      )
      nonces <- SecureRandomNonceSource.create
      retry <- CountingRetry.upTo(1)
      api = DeviceApi(
        tokens,
        RegisterDevice[IO](registry, push, nonces, retry, time, limit),
        VerifyDevice[IO](registry, time, limit),
        ForgetOwnDevice[IO](registry),
        ExponentialBackoffRetry.default[IO].backoff,
        time,
        NoOpLogger[IO]
      ).orNotFound
      held <- api.run(asking("holder", DeviceApi.Path, registering))
      claim <- api.run(asking("claimant", DeviceApi.Path, registering))
      sent <- pushed.get
      nonce = parser
        .parse(sent.head)
        .flatMap(_.hcursor.downField("message").downField("data").get[String]("nonce"))
        .getOrElse(fail(s"no nonce in ${sent.head}"))
      stolen <- api.run(
        asking(
          "holder",
          DeviceApi.VerifyPath,
          s"""{"device_token":"handset-token-1","nonce":"$nonce"}"""
        )
      )
      guessed <- api.run(
        asking(
          "claimant",
          DeviceApi.VerifyPath,
          """{"device_token":"handset-token-1","nonce":"x"}"""
        )
      )
      before <- registry.holderOf(handset)
      verified <- api.run(
        asking(
          "claimant",
          DeviceApi.VerifyPath,
          s"""{"device_token":"handset-token-1","nonce":"$nonce"}"""
        )
      )
      after <- registry.holderOf(handset)
      stored <- sql"SELECT nonce_digest FROM notification_device_challenges"
        .query[String]
        .to[List]
        .transact(xa)
    yield
      assertEquals(held.status, Status.Ok)
      assertEquals(claim.status, Status.Accepted)
      assertEquals(sent.length, 1)
      assertEquals(nonce.length, 43, "32 random bytes, base64url without padding")
      assertEquals(stolen.status, Status.UnprocessableEntity)
      assertEquals(guessed.status, Status.UnprocessableEntity)
      assertEquals(before, Right(Some(Fixtures.recipient)))
      assertEquals(verified.status, Status.Ok)
      assertEquals(after, Right(Some(claimant)))
      assertEquals(stored, Nil)
  }

  onPostgres("over HTTP, principals claiming one token at once wake its handset three times") {
    xa =>
      val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
      val claimants = (1 to 8).toList.map(n => s"claimant-$n")
      val verdicts = claimants.map(c => c -> TokenVerdict.Valid(PrincipalId.fromString(c).get))
      val tokens = FakeAccessTokens(
        (("holder" -> TokenVerdict.Valid(Fixtures.recipient)) :: verdicts).toMap
      )
      val time = SystemTimeSource()

      for
        pushed <- Ref.of[IO, Int](0)
        fcm = Client.fromHttpApp(HttpApp[IO](_ => pushed.update(_ + 1) *> Ok("{}")))
        push = HttpPushSender(
          fcm,
          Uri.unsafeFromString("https://fcm.googleapis.com/v1/projects/kinetix-test/messages:send"),
          IO.pure("access-token"),
          time,
          NoOpLogger[IO]
        )
        nonces <- SecureRandomNonceSource.create
        retry <- CountingRetry.upTo(1)
        api = DeviceApi(
          tokens,
          RegisterDevice[IO](registry, push, nonces, retry, time, limit),
          VerifyDevice[IO](registry, time, limit),
          ForgetOwnDevice[IO](registry),
          ExponentialBackoffRetry.default[IO].backoff,
          time,
          NoOpLogger[IO]
        ).orNotFound
        _ <- api.run(asking("holder", DeviceApi.Path, registering))
        answers <- claimants.parTraverse(c => api.run(asking(c, DeviceApi.Path, registering)))
        accepted = claimants.zip(answers).collect {
          case (c, answer) if answer.status == Status.Accepted => c
        }
        again <- api.run(
          asking(accepted.headOption.getOrElse("holder"), DeviceApi.Path, registering)
        )
        againBody <- again.as[String]
        sent <- pushed.get
        held <- sql"SELECT COUNT(*) FROM notification_device_challenges"
          .query[Int]
          .unique
          .transact(xa)
      yield
        assertEquals(accepted.length, 3)
        assertEquals(answers.count(_.status == Status.TooManyRequests), 5)
        assertEquals(again.status, Status.TooManyRequests)
        assert(againBody.contains("VERIFICATION_ALREADY_PENDING"), againBody)
        assertEquals(sent, 3)
        assertEquals(held, 3)
  }
