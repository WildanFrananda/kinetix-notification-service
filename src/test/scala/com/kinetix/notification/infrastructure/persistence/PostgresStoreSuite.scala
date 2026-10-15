package com.kinetix.notification.infrastructure.persistence

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import munit.{CatsEffectSuite, TestOptions}
import org.typelevel.log4cats.noop.NoOpLogger

import com.kinetix.notification.domain.*
import com.kinetix.notification.fakes.Fixtures
import com.kinetix.notification.infrastructure.config.DatabaseSettings

class PostgresStoreSuite extends CatsEffectSuite:
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
        sql"TRUNCATE notification_attempts, notifications, notification_devices".update.run
          .transact(xa)
          .void

  private def unavailable[A](result: Either[NotificationError, A]): Boolean = result match
    case Left(NotificationError.StoreUnavailable(_)) => true
    case _                                           => false

  private val key: IdempotencyKey = IdempotencyKey.fromString("ORDER-42-PACKED").get

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
      _ <- registry.register(owner, token, DevicePlatform.Android)
      _ <- registry.forgetOwned(stranger, token)
      afterStranger <- registry.tokensFor(owner)
      _ <- registry.forgetOwned(owner, token)
      afterOwner <- registry.tokensFor(owner)
    yield
      assertEquals(afterStranger, Right(List(token)))
      assertEquals(afterOwner, Right(Nil))
  }

  onPostgres("a token another principal held moves to the one who registers it now") { xa =>
    val registry = PostgresDeviceRegistry(xa, NoOpLogger[IO])
    val before = Fixtures.recipient
    val after = PrincipalId.fromString("99999999-8888-7777-6666-555555555555").get
    val token = DeviceToken.fromString("handset-token-1").get

    for
      _ <- registry.register(before, token, DevicePlatform.Android)
      moved <- registry.register(after, token, DevicePlatform.Ios)
      left <- registry.tokensFor(before)
      arrived <- registry.tokensFor(after)
    yield
      assertEquals(moved, Right(false))
      assertEquals(left, Right(Nil))
      assertEquals(arrived, Right(List(token)))
  }
