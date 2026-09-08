# Data Prism

A privacy layer between MCP clients and enterprise APIs.

**Status: design complete, nothing implemented.** There is no code in this
repository yet — only the specification, its review, and the plan. Do not depend
on it. This notice comes down when the walking skeleton in `docs/plan/PLAN.md`
lands.

## The problem

An organisation wants an LLM to investigate live business data spread across
several systems. Giving the model direct API access is not acceptable: those APIs
carry personal and confidential data, each system represents the same entity
differently, and raw identifiers let anything downstream correlate across
sessions.

The obvious fix — redact everything sensitive — destroys the investigation. Once
three systems' names for one person are all `[REDACTED]`, the model cannot tell
whether it is looking at one person or three.

## What Data Prism does

It sits between the two and does two things that are easy to confuse:

**It makes identity consistent.** One subject gets one synthetic identity across
every source, derived deterministically from `(scope, subject, namespace,
algorithm version, key)` — never random, never stored in plaintext, and
reproducible without the cache. The same person in three systems reads as one
person to the model.

**It leaves the data inconsistent, and says so.** If those three systems disagree
about a name, the answer carries a finding that says they disagree. The platform
never makes enterprise data look cleaner than it is. That distinction is the
point of the project:

> Identity representation becomes consistent. Underlying data inconsistencies
> become *more* visible, not less.

Pseudonyms are scoped. The same person in two different investigations gets two
different synthetic identities, so nothing correlates across cases by accident.

## What it is not

Not an API gateway, not an ETL platform, not a master-data system, not an
identity provider, and not an entity-resolution engine — correlation requires a
key the sources already share, behind a documented SPI. It carries no business
domain: no `Customer`, `Taxpayer` or `Employee` type exists outside the example
application.

**It is not anonymisation.** Under GDPR Art. 4(5), pseudonymised data is still
personal data. Sending Data Prism output to a third-party model is still
processing, and still needs a lawful basis, a DPIA, and a transfer mechanism
where the provider is outside the EU. The platform reduces exposure; it does not
remove the obligation.

## Documentation

| | |
|---|---|
| `docs/architecture.md` | Module map, dependency rules, the boundaries that must not be crossed, dated decisions |
| `docs/design-review.md` | Amendments to the specification, with reasoning. **Authoritative** |
| `docs/development-plan.md` | Slice order, sizing, and the decisions that block the first one |
| `docs/pack.md` | The original specification. Historical; superseded where the review disagrees |
| `docs/conventions.md` | Code style and the privacy rules a diff must satisfy |
| `docs/workflow.md` | How work is split and run |
| `docs/plan/PLAN.md` | What is open, in priority order |
| `docs/plan/HISTORY-INDEX.md` | What was built, and what it cost to find out |

`docs/plan/PLAN.md` is the working queue. GitHub Issues is the front door for
anything coming from outside — file there, not in `PLAN.md`.

## Stack

Java 21, Spring Boot 3.x, Maven multi-module, Hazelcast, Model Context Protocol.
The published group id is not yet chosen; see decision **D** in
`docs/plan/PLAN.md`.

## Contributing

See `CONTRIBUTING.md`. The short version: read `docs/conventions.md` before
opening a pull request, and expect the privacy rules in it to be enforced
literally.

## Licence

Apache License 2.0 — see `LICENSE` and `NOTICE`.
