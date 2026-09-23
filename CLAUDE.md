# CLAUDE.md — kinetix-notification-service

Guidance for Claude Code when working in this repository. This service is part of
the **Kinetix** polyglot e-commerce project (one language per service, gRPC-only
between services). This service is written in **Scala 3** using **Cats Effect**
and the **Tagless Final** pattern, inside a strict **Hexagonal Architecture**.

## Core principles (non-negotiable)

1. **Strict typing.** No `Any`, no `asInstanceOf`, no stringly-typed values for
   domain concepts (use `NotificationId`, `UserId`, etc., never raw `String`).
   Prefer sealed traits + case classes (ADTs) over primitives and flags.
2. **Explicit DI.** All dependencies are wired via constructor parameters at
   the composition root (`Main.scala`). No DI containers, no annotations, no
   implicit magic for wiring dependencies (implicits are fine for typeclass
   evidence like `Monad[F]`, `Concurrent[F]`, never for swapping in a service).
3. **Clean layer separation (hexagonal / ports & adapters).** Dependencies only
   point inward: `infrastructure → application → domain`. `domain` never
   imports from `application` or `infrastructure`.

## Layer responsibilities

### `domain/`
- Pure data: case classes, sealed traits (ADTs), value classes.
- **Ports**: traits parametrized over `F[_]`, e.g. `trait NotificationSender[F[_]]`.
  These describe *capabilities*, not implementations.
- Zero third-party imports beyond `cats` core typeclasses (`Functor`, `Monad`)
  if strictly needed for a domain-level combinator. No `cats.effect`, no `IO`,
  no gRPC/HTTP types here.

### `application/`
- Use cases / services: classes generic over `F[_]: Monad` (or a narrower
  constraint — request only what the use case actually needs, e.g. `MonadThrow`).
- Orchestrates one or more `domain` ports. Contains business rules and
  sequencing (e.g. retry-then-persist-result), not I/O mechanics.
- Constructor-injected with its port dependencies. No global state.

### `infrastructure/`
- Concrete adapters implementing `domain` ports, fixed to `cats.effect.IO`.
- Subfolders by concern: `infrastructure/grpc`, `infrastructure/http`,
  `infrastructure/persistence`, `infrastructure/retry`.
- gRPC server adapter is generated from `kinetix-contracts` `.proto` files via
  **fs2-grpc** — implement the generated trait by delegating to an
  `application` use case; do not put business logic in the gRPC adapter itself.
- HTTP calls to external providers (email/push/SMS) go through `http4s` client
  or `sttp`, wrapped behind a domain port — never call a provider SDK directly
  from `application`.

### `Main.scala` (composition root)
- The only place allowed to construct concrete adapters and wire them into
  use cases. Reading this file top to bottom should show the entire
  dependency graph with no hidden resolution.

## Conventions

- **Error handling:** model expected failures as part of the domain (`sealed
  trait NotificationError`), returned via `F[Either[NotificationError, A]]` or
  `EitherT[F, NotificationError, A]` — do not use exceptions for expected
  failure paths. Reserve `F.raiseError` for truly unexpected failures.
- **Retries:** implemented as a port (`RetryPolicy[F]`) in `domain`, concrete
  policy (e.g. exponential backoff) lives in `infrastructure/retry`. Never
  hardcode retry loops inside a use case.
- **Testing:** unit-test `application` use cases against hand-written fake
  implementations of `domain` ports (no mocking framework) — this is the
  payoff of the tagless-final boundary. Adapter tests in `infrastructure` may
  use real `IO` and integration-test against local containers.
- **Effect constraints:** use the narrowest typeclass constraint a use case
  actually needs (`Monad`, `MonadThrow`, `Concurrent`) rather than defaulting
  every signature to `IO`. Only `infrastructure` and `Main` should mention
  `IO` by name.
- **Naming:** ports are nouns describing a capability (`NotificationSender`,
  not `EmailService`); adapters are prefixed by their mechanism
  (`HttpEmailSender`, `GrpcNotificationServer`, `PostgresNotificationRepository`).

## gRPC / contracts

- All inter-service calls use gRPC. `.proto` definitions live in the shared
  `kinetix-contracts` repo — do not hand-write message types that duplicate a
  contract; regenerate via `fs2-grpc` instead.
- REST is only added at the edge if the frontend needs it directly (not for
  service-to-service calls).

## When adding a new feature

1. Start in `domain`: does it need a new port, a new ADT case, or a new error case?
2. Add/extend the use case in `application`, generic over `F[_]`.
3. Implement or extend the adapter in `infrastructure`.
4. Wire it in `Main.scala`.
5. If it touches inter-service communication, update the `.proto` in
   `kinetix-contracts` first, then regenerate stubs here.

## What to avoid

- Don't reach for a DI framework (MacWire, Guice) — wiring stays manual and visible.
- Don't leak `infrastructure` types (grpc messages, http4s requests) into `domain` or `application` signatures — convert at the adapter boundary.
- Don't use `Future` — `cats.effect.IO` only, referentially transparent throughout.
- Don't put retry/timeout policy directly in adapter code — route it through the `RetryPolicy` port so it stays swappable and testable.

---

## How this repository enforces the rules above

The rules are not advice here; several of them fail the build.

- `-Xfatal-warnings` with `-Wunused:all`, `-Wvalue-discard` and
  `-Wnonunit-statement`. An unused import or a discarded value stops the build,
  so "keep it tidy" is not something anyone has to remember.
- `-Wconf:src=.*src_managed.*:silent` exempts ScalaPB's output. Generated code
  is not this repository's code to hold to this repository's style, and without
  the exemption the rule stops being about anything anyone wrote.
- `bin/sync-contracts` narrows the fetched contract to **only** `common`,
  `identity` and `notification`, out of the repository's ten packages. Wire types
  for payment, pricing and the fleet never reach the classpath, so reaching for
  one is a compile error rather than a review comment. The narrowing is in the
  fetch script and not in `build.sbt` for a measured reason: a setting that
  copies files is evaluated at a moment sbt decides, and `sbt clean` then leaves
  the build pointing at a directory that no longer exists — which showed up as
  `Not found: notification` on the first clean build.
- `.scalafmt.conf` pins **every** indent site to 2, not just `indent.main`. Scalafmt's
  defaults indent a parameter list, a constructor, an `extends` clause and a `case`
  by 4 while indenting blocks by 2, so a file drifts between two widths depending on
  what is on the line above. `sbt scalafmtAll` formats, `sbt scalafmtCheckAll` fails
  on anything unformatted.
- **`sbt-scalafmt` is pinned to 2.5.5, and that is not an oversight.** 2.6.0 and later
  require sbt 1.12.9+; this build is on 1.10.7, and so is the `SBT_VERSION` argument in
  the Dockerfile. Bumping the plugin means bumping both, which changes what the image
  builds with — a separate decision from how the source is laid out.

- `.contracts/` is gitignored. `git ls-files '*.proto'` stays empty, as it does
  in every other service in this estate.

## Things decided here, and why

- **Device tokens live in this service, not identity.** A token is not a profile
  attribute: it says which handset is currently logged in as somebody, which
  changes when they reinstall the app. It is an address on a channel, so it
  belongs with the service that owns addresses on channels.
- **`SenderRejected` and `SenderUnavailable` are different errors.** A provider
  saying "this token is dead" is an answer and retrying it is waste; a provider
  not answering is not an answer, and is the only case worth repeating. That
  distinction is `NotificationError.isTransient`, and `RetryPolicy` reads it.
- **`Unreachable` is not `Failed`.** A recipient with no address on a channel is
  not a send that went wrong — there was nowhere to send to. Calling it a
  failure has somebody retrying it forever.
- **The record is written before anything is sent.** A crash in between leaves a
  notification that looks unsent, which somebody can act on, rather than nothing
  at all.
- **A missing template parameter is refused, not rendered as a gap.** "Pesanan
  sudah dikemas" with a hole in it is a worse message than no message: the
  recipient cannot act on it and cannot tell that anything is wrong.
- **The HTTP port serves three things and no business.** `/health`, `/health/ready` and
  `/metrics`. Everything a caller wants from this service is gRPC behind mTLS; the HTTP
  listener exists so a container orchestrator and a scraper can do their jobs without
  holding a client certificate.
- **Readiness asks the database a real question.** A process that has bound a port is not
  a service that can work — the pool can be exhausted, the password can have rotated. The
  reason a probe failed is logged and deliberately **not** put in the response body: a
  driver's message can carry the connection string, and that endpoint is unauthenticated.
- **Metrics are seeded at zero when the process starts.** A counter that appears only once
  it has been incremented makes "nothing has failed yet" and "this service does not report
  failures" the same observation, and the estate's deploy gate greps for a sample line, not
  a `# TYPE` header. The gRPC method names come off the bound service descriptor, so a
  method renamed in the contract cannot leave a counter behind under its old name.
- **Every metric label is bounded by construction.** A route label is one of
  `HttpApi.Routes` or the single `unmatched` bucket — never the path as asked for. An id in
  a label is one time series per request, forever.
- **Reflection is `ProtoReflectionServiceV1`, not `ProtoReflectionService`.** The latter is
  `@Deprecated` in grpc-java, and `-deprecation -Xfatal-warnings` refuses to compile it.
- **`logback.xml` exists so the root logger is not DEBUG.** Logback with no configuration
  defaults to DEBUG on root, which had Flyway printing every statement it parsed and would
  have had doobie printing every query in production.

- **The schema has its own entry point.** `Migrate` runs Flyway once and exits; the
  service waits for that exit to be a zero, the way every other service in this estate
  does it. Migrating from inside `Main` would have every replica racing to change the
  same schema at start-up, and would make a failed migration look like a service that
  would not boot.
- **The migrator reads `DatabaseSettings`, not `Settings`.** Creating a table does not
  need a push provider key, and a container that holds one it never uses is a credential
  in an extra place for no reason. It also means the schema stays fixable on a day the
  provider has not been chosen.

- **The words live in a catalogue, not in the code.** `Message.render` used to
  hold Indonesian prose in Scala string interpolation, which put product copy
  inside a codebase the estate keeps in English and left no room for a second
  language. The copy is `src/main/resources/messages/id.json` now; `domain`
  carries `MessageCatalogue` and `MessageCopy` as plain data, and
  `infrastructure/JsonMessageCatalogue` is the only part that reads a file.
  What a template *requires* is read from its own copy — every `{name}` in the
  title or body must arrive in `params` — so a hand-kept list of required
  parameters can no longer disagree with the words beside it.
- **An incomplete catalogue fails the start.** `MessageCatalogue.of` refuses a
  map that is missing any `Template`, and `Main` loads it before the server
  binds. A template with no copy would otherwise surface as a customer who was
  never told their order shipped, long after the deploy that caused it.

