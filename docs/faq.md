---
title: Data Prism FAQ
description: Direct answers about pseudonymisation, PII detection, Java requirements, the audit trail and prompt injection in Data Prism.
---

# FAQ

Short, direct answers to the questions people actually ask about Data Prism.
Each answer's first sentence is a complete answer on its own; the rest is the
detail and the source.

## Is the output anonymous?

No — pseudonymised data is still personal data under GDPR Article 4(5), so
Data Prism's output is not anonymous. It replaces identifying values with a
deterministic pseudonym scoped to the use case; sending that output to a
third-party model is still processing personal data, and still needs a
lawful basis, a DPIA, and a transfer mechanism where the provider is outside
the EU. Data Prism reduces exposure — it does not remove that obligation.
See ["What it is not"](../README.md#what-it-is-not) in the README.

## How are pseudonyms made?

Each pseudonym is derived deterministically from `(scope, subject, namespace,
algorithm version, key)` using an HMAC-based generator — never random, never
stored in plaintext, and reproducible without a cache, so the same person
reads as the same synthetic identity in every source, and two different
investigations get two different identities for the same person. As of
version 0.3.0 the generator's discriminator is 40 bits (eight Crockford
base32 characters), widened from the 20 bits used in 0.2.0, and every
namespace — including `ADDRESS`, which previously carried none — now renders
one. See ["What Data Prism does"](../README.md#what-data-prism-does) in the
README and the 0.3.0 entry in [`CHANGELOG.md`](../CHANGELOG.md).

## Does it detect PII in free text?

Only for a fixed set of identifier shapes, not general PII detection: a
fail-closed validator scans every response — including free-text fields such
as a note — for values that look like an IBAN, a payment card number, an
Irish PPSN, a US Social Security Number, an email address, an international
phone number, a JWT, or a known API-key prefix, at any depth up to a size
budget, and refuses the response if it finds one that is not already one of
the scope's own emitted pseudonyms. This is shape-based matching for a fixed
list of formats, not open-ended named-entity recognition, so it will not
catch, for example, a bare name typed into a note; the repository does not
document a reason for that scope beyond it being what shipped. The check
allows the scope's own pseudonyms through a per-scope allowlist, because
without one a synthesised value that happens to match a detector's shape —
a synthesised email, for instance — would make the validator refuse Data
Prism's own valid output. See the `validation` module and the
2026-09-08 decision to allowlist a scope's own pseudonyms in
[`architecture.md`](architecture.md#components) and
[`architecture.md`](architecture.md#decisions-worth-knowing), and the
detector list in
[`SensitiveDataScanner.java`](../data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitiveDataScanner.java).
Free-text values are checked separately for instruction-like phrasing, not
PII — see "Does it stop prompt injection?" below and the worked example in
[`tools.md`](tools.md#get_entity_context).

## Do I need Java?

No, not to protect a flat or one-level-nested JSON REST API: the
configuration-driven JSON REST mode lets you do that with only a YAML
catalogue — no Java class, `pom.xml`, or `META-INF` registration step — see
[`protect-your-own-api.md`](protect-your-own-api.md). Data Prism itself
still runs on a JVM (the standalone server or the Spring Boot starter), and
the published server image still refuses to start until you supply that
catalogue or a reviewed adapter and a full deployment configuration — see
["If you found this on the MCP registry"](../README.md#if-you-found-this-on-the-mcp-registry)
in the README. For a response nested two levels or more, custom fetch logic,
or a non-JSON source, you do need to write a reviewed Java
`DataSourceAdapter` — see [`extending.md`](extending.md).

## What does the audit trail prove?

For every record the offline verifier can see, in each writer's own hash
chain, it proves that replaying that chain found no edit or deletion of any
of the nineteen hashed fields — nothing more. It does not prove several
things, deliberately: truncation of a writer's most recent records is
undetectable, because an append-only file with its tail removed verifies
perfectly end to end, with nothing left in the file to disagree with, and
the same blind spot covers deleting an entire boot's records outright — the
surviving writers still verify intact, and the report never mentions the
boot whose records are gone. The chain is also intra-writer edit-and-delete
detection only, not a guarantee against a capable adversary:
`AuditEventHash` is unkeyed SHA-256 over the record body, so anyone able to
write to the file directly can edit or delete a record and simply recompute
every hash that follows it, and the chain still verifies. Durable
append-only-ness is an operator responsibility this class does not enforce,
and metric labels and trace attributes are not scanned by any test in this
release. No sentence here, or in the source, should be read as a claim that
the durable audit log resists a determined attacker, cannot be changed, or
is a complete account by itself — it is not. See the full account in
["What this does and does not prove"](audit.md#what-this-does-and-does-not-prove)
in `audit.md`.

## Does it stop prompt injection?

No — `InstructionContentHeuristic` flags source content that reads like an
instruction to a model; it is deliberately not a prompt-injection defence.
It matches a fixed set of signals (phrases like "ignore previous
instructions", a fake system-prompt marker, and similar) against a source
value, attaches a finding saying what it looks like, and passes the value
through unchanged, because rewriting it would hide the record's true content
from the one person who could recognise the attack. Anyone determined can
phrase around a fixed list of signals, so treat a finding as putting a human
on notice, not as protection. See
[`InstructionContentHeuristic.java`](../data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/InstructionContentHeuristic.java)
and [`design-review.md`](https://github.com/AindriuB/data-prism/blob/main/docs/design-review.md#d3-prompt-injection--flag-never-sanitise)
(an internal doc kept off the docs site, linked here at its GitHub source).

## Is it production-ready?

No — [`SECURITY.md`](../SECURITY.md#supported-versions) states plainly that
the project is pre-1.0, and that only the latest released version receives
security fixes. Version 0.3.0 is that latest release, and across its
history the privacy engine, deterministic pseudonymisation, correlation and
consistency findings, the JSON REST and reviewed-adapter connectors, the
OAuth2 resource server, and an opt-in durable hash-chained audit sink with
an offline verifier have all been built and are covered by tests — see the
full release history in [`CHANGELOG.md`](../CHANGELOG.md). Two of the four
originally designed MCP tools, `search_entity_data` and
`describe_entity_model`, are not implemented — see
["Not yet built"](tools.md#not-yet-built) in `tools.md` — and the
re-identification operator surface and an Elasticsearch connector are
deferred by design, not missing by accident.
