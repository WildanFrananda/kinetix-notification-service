# kinetix-notification-service

Reaching a person.

One service owns *how do we contact this principal*, so that no other service has to hold an email
address or a telephone number in order to say something to somebody. Identity holds a person's
profile; this service asks identity at send time and keeps none of it. What it keeps is a delivery
record: which principal, which event, which channel, and whether it arrived.

Scala 3, Cats Effect 3, tagless final inside a hexagonal boundary. The rules this repository is held
to are in [CLAUDE.md](CLAUDE.md).

## What it does

| RPC | For |
|---|---|
| `Notify` | Tell one principal that one thing happened |
| `GetDelivery` | What happened to one notification |
| `RegisterDevice` | Remember which handset belongs to a principal |
| `ForgetDevice` | Stop pushing to a handset |

The caller names an **event**, never a sentence. Wording, language and channel belong here: a caller
that passed a finished message would be deciding how the platform speaks, from a service with no
idea what else the recipient has been sent today.

## Shape

```
domain/          pure data, ADTs, and ports — traits over F[_]. No IO, no gRPC, no HTTP.
application/     use cases generic in F, orchestrating ports. No I/O mechanics.
infrastructure/  adapters fixed to IO: grpc, http, persistence, retry, config.
Main.scala       the composition root, and the only place that constructs an adapter.
```

Reading `Main.scala` top to bottom shows the whole dependency graph. Nothing is resolved at runtime,
nothing is annotated, nothing is looked up.

## Running it

```sh
bin/sync-contracts     # fetch the .proto at the pinned tag — never committed here
sbt test
sbt run
```

Every setting is required and none has a default; see `infrastructure/config/Settings.scala` for the
list and why. A missing name stops the service at boot rather than at the first request.

## What it deliberately does not store

No email addresses, no telephone numbers. Device tokens, yes — a token is not a profile attribute,
it says which handset is currently logged in as somebody, and that is an address on a channel rather
than a fact about a person.

## Two distinctions the code keeps

**A provider that said no, against a provider that did not answer.** A dead token is an answer, and
repeating it is waste; a dropped connection is not an answer, and is the one case worth retrying.
`NotificationError.isTransient` is where that lives.

**Nowhere to send, against a send that failed.** A recipient with no address is `Unreachable` and no
record is written — a notification to nobody is not a notification. A send that was attempted and
failed *is* recorded, so that "why didn't they get it" has an answer.
