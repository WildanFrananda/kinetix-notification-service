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
- **Push is FCM HTTP v1, and its credential expires every hour.** `HttpPushSender` takes
  `bearer: IO[String]`, not an API key: `GoogleAccessTokens` signs an RS256 assertion with
  the service account key and exchanges it for an access token, holding that token until a
  minute before it expires. The key-in-a-header form belonged to the legacy FCM endpoint,
  which Google retired in July 2024 — a static credential there does not degrade, it returns
  401 for every notification the estate ever sends. The JSON key arrives as
  `PUSH_PROVIDER_CREDENTIALS_B64`, base64 because it is multi-line and holds a PEM block, and
  every layer between the secret store and here has its own opinion about newlines.
- **Email is Resend.** `HttpEmailSender` posts `{from, to: [...], subject, text}` with a
  bearer key, which is Resend's API exactly. Brevo wants `sender`/`textContent` and an
  `api-key` header, and an SMTP relay is not an HTTP call at all — either would be a rewrite
  of this adapter, not a change of URL.
- **No Google client library.** `java.security` signs the assertion and the existing http4s
  client makes the exchange, so the token cache is a `Ref` with no background thread and no
  second HTTP stack in the image.

- **The HTTP port serves the probes, the scrape, and the handset.** `/health`, `/health/ready` and
  `/metrics` need no credential so an orchestrator and a scraper can do their jobs.
  `POST` and `DELETE /api/v1/notifications/devices` are the one piece of business here, because
  the caller is a phone, not a service: it has no mesh certificate and reaches the estate only
  through Kong. A device is registered to the principal identity's access token names (`sub`),
  verified here against identity's JWKS with issuer, audience and `token_use: access` — Kong's own
  check is a second lock, not the only one. The body never names a principal; one that tries is
  ignored. A handset can forget only its own token. The body is read up to 4 KiB and no further.
- **Signing keys that cannot be fetched are a 503, not a 401.** "Your token is bad" and "identity
  did not answer" are different facts; reporting the second as the first sends a logged-in user to
  log in again. `TokenVerdict.Unverifiable` carries it, from nimbus's `KeySourceException`.
- **nimbus-jose-jwt verifies tokens, and nothing else is hand-rolled.** Algorithm pinning (RS256
  only, so `none` and HS256-over-the-public-key are refused), key rotation by `kid`, and claim
  checks are where hand-written JWT code goes wrong. Its JWKS fetcher uses the JDK's own
  `HttpURLConnection`, so it brings no second HTTP library.

- **gRPC callers are granted per RPC, and templates per caller.** `KINETIX_GRPC_ALLOWED_PEERS` reads
  `peer`, `peer@package.Service` or `peer@package.Service/Method`, as order and identity read it.
  The list used to name services only, so any of them could register its own handset under any
  principal and receive their order and AWB numbers. `KINETIX_NOTIFICATION_TEMPLATE_GRANTS`
  (`order:TEMPLATE_ORDER_PAID`) says which templates a caller may send: without it, any caller
  allowed `Notify` could tell a buyer their order was paid. Both must be set even when empty — an
  empty value grants nobody, an unset one is a deploy that forgot it — and a grant naming a method
  this server does not serve stops the start, because a typo there grants nothing silently.
  Reflection is open to any verified peer: it describes a contract that is public anyway.
- **The caller is the certificate's, and reaches the handler as a value.** The interceptor writes the
  verified peer into `x-kinetix-verified-caller` after discarding whatever the client sent under that
  name, and fs2-grpc's context function turns it into a `Caller`. A handler never reads a caller from
  the request.
- **An idempotency key belongs to the service that sent it.** The unique constraint is
  `(sender, idempotency_key)`; a replay is answered only when recipient, template and parameters
  match, and otherwise refused as `IDEMPOTENCY_KEY_REUSED` rather than answered with somebody
  else's notification. The insert is `ON CONFLICT DO NOTHING`, so the loser of two simultaneous
  first calls reads the winner instead of reporting `STORE_UNAVAILABLE` for a notification that
  exists. `GetDelivery` shows a service only what it sent.
- **A row this service cannot read is a failure, never a guess.** The codecs once read an unknown
  template as `ORDER_PAID`, an unknown channel as push and unreadable parameters as none. They now
  refuse, the read is `STORE_UNAVAILABLE`, and the table's CHECK constraints name every template.
- **`params` is cast to `jsonb` in the insert.** Without the cast Postgres refuses the varchar the
  driver sends — every `Notify` on the code before this failed that way, and the fake repository
  could not see it. `PostgresStoreSuite` runs against a real Postgres 16 in both CIs and fails,
  rather than skips, when CI has no database; on a laptop it is reported as ignored unless
  `NOTIFICATION_TEST_DATABASE_URL` names a database with `test` in its name, because it truncates.
- **A store failure says so, and only in the log says why.** The driver's message is logged at ERROR
  and kept out of what a caller receives; `GetDelivery` answers `UNAVAILABLE` when the store did
  not answer, instead of `found = false`.

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
  `HttpApi.Routes` or the single `unmatched` bucket — never the path as asked for. Only
  `HttpApi.Probes` are seeded at start; the device routes appear once a handset uses them. An id in
  a label is one time series per request, forever.
- **Reflection is `ProtoReflectionServiceV1`, not `ProtoReflectionService`.** The latter is
  `@Deprecated` in grpc-java, and `-deprecation -Xfatal-warnings` refuses to compile it.
- **The runtime stage upgrades its packages.** The JRE base is pinned by digest, so its packages age
  while the pin does not, and `build-and-push.yml` fails on any CRITICAL that has a fix — search's
  merge build went red that way on perl-base (2026-10-14). On 2026-10-15 the pinned base held 0
  CRITICAL and 5 fixable HIGH (curl, openssl). `apt-get upgrade` keeps the pin and takes the fixes;
  curl stays, because the compose healthcheck uses it.
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

