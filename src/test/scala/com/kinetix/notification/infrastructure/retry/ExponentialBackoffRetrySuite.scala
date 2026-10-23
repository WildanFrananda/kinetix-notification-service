package com.kinetix.notification.infrastructure.retry

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*

import com.kinetix.notification.BoundedSuite
import com.kinetix.notification.domain.{Channel, NotificationError}

class ExponentialBackoffRetrySuite extends BoundedSuite:
  private def attemptsAt(
    policy: ExponentialBackoffRetry[IO],
    answer: NotificationError
  ): IO[(Either[NotificationError, Unit], List[FiniteDuration])] =
    for
      stamps <- Ref.of[IO, List[FiniteDuration]](Nil)
      result <- policy.retrying(
        IO.monotonic.flatMap(at => stamps.update(_ :+ at)).as(answer.asLeft[Unit])
      )
      seen <- stamps.get
    yield (result, seen)

  test("the default policy makes three attempts, pausing 200 ms and then 600 ms, as it says") {
    val down = NotificationError.SenderUnavailable(Channel.Push, "503: busy")
    val policy = ExponentialBackoffRetry.default[IO]

    attemptsAt(policy, down).map: (result, seen) =>
      val gaps = seen.zip(seen.drop(1)).map((before, after) => after - before)

      assertEquals(result, Left(down))
      assertEquals(policy.backoff, Backoff(List(200.millis, 600.millis)))
      assertEquals(seen.length, policy.backoff.attempts)
      gaps
        .zip(policy.backoff.pauses)
        .foreach: (gap, pause) =>
          assert(gap >= pause, s"waited $gap where the policy says $pause")
  }

  test("an answer is not repeated, however many attempts the policy allows") {
    val refused = NotificationError.SenderRejected(Channel.Push, "403: SENDER_ID_MISMATCH")

    attemptsAt(ExponentialBackoffRetry.default[IO], refused).map: (result, seen) =>
      assertEquals(result, Left(refused))
      assertEquals(seen.length, 1)
  }
