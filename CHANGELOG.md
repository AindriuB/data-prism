# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-09-23

Nested JSON catalogues, one level deep, and a durable, hash-chained audit
trail — protect a real API without writing Java, and prove what happened.

### Added

- The configuration-driven JSON REST connector gains one level of named
  nested catalogues: a field can declare `nested: <name>`, pointing at an
  entry in a top-level nested-catalogues map whose own fields use the same
  three-shape vocabulary flat catalogues already have. No dotted paths, no
  JSONPath, no wildcard descent, no inferring structure from the wire —
  `subject-json-path` is untouched and a nested object never carries its own
  subject. A response nesting deeper than declared — a leaf the catalogue
  says is a scalar turning up as a structure — refuses with the new,
  distinct `NESTED_LEAF_NOT_SCALAR` code rather than falling through to
  core's generic `UNCLASSIFIED_STRUCTURE`; the mirror case, a declared
  structure that turns up as a scalar, refuses with `NESTED_FIELD_NOT_STRUCTURED`.
- A durable, append-only, hash-chained audit sink. Selecting
  `dataprism.audit.sink: hash-chained`, alongside the now-required
  `dataprism.audit.file-path`, produces a `FileAuditSink` bean: one file,
  fsync per record, no rotation. Omitting the file path refuses at startup
  with `MISSING_AUDIT_FILE_PATH`; a path this process cannot open refuses
  with `AUDIT_SINK_FILE_UNUSABLE`, instead of degrading silently to no
  auditing. The canonical audit-record hash covers nineteen fields,
  including `timestamp` and `sourceSystems`.
- An offline `AuditChainVerifier` CLI replays a hash-chained audit file and
  reports what it finds: exit 0 intact, 1 unreadable input, 2 a detected
  break, 3 a tail that may simply be in flight, 4 a structural anomaly (never
  returned together with 2). It distinguishes several ordinary,
  non-tampering failure modes — a torn trailing record, a mid-file
  concatenation after a restarted sink, a new writer's chain starting fresh
  after a process restart — from genuine tampering, but it is unable to rule
  out truncation of the most recent record or records: a chain that simply
  stops cannot be told apart from one an attacker cut short, which is why
  that case exits 3 rather than 0.

### Fixed

- `AuditRecorder` no longer advances its in-memory `previousHash` until the
  sink's `record` call actually succeeds. Previously, a throwing sink still
  left the chain head pointing past an event that was never durably
  written, so the next successful write chained against a hash for a record
  that does not exist; a throwing sink now rolls the recorder back to
  byte-identical prior state instead.
- `AutoConfiguredBeanClassificationTest`'s sweep now walks `@Import`ed and
  nested configuration classes recursively, closing a gap that let a bean be
  placed specifically to dodge classification.
- The reviewed-adapter allow-list is restored: `DataPrismContractValidator`
  again refuses, with the stable code `UNREVIEWED_SOURCE_ADAPTER`, any
  `DataSourceAdapter` bean named by neither `dataprism.sources` nor the JSON
  catalogue's own source names. This closes a rule-1 enforcement gap task
  54's earlier relaxation had silently dropped.

### Changed

- `data-prism-example` is renamed to `data-prism-integration-tests`: it hosts
  11 integration test classes with no duplicate elsewhere, including
  `PiiLogScanTest`, the sole enforcement of privacy rule 7, and was never a
  demo. Package `io.github.aindriub.dataprism.example` and `ExampleApplication`
  are unchanged. Six `data-prism-example` strings survive deliberately inside
  `data-prism-integration-tests` — its own JWT `issuer` and audit `writer-id`
  config values, and the tests asserting on them — because they are
  observable audit output, not a module identifier.
- Documentation reconciled against the shipped code rather than the plan that
  preceded it: two consumer guides, `docs/extending.md` and `docs/tools.md`,
  are now linked from `README.md`, `docs/quickstart.md` and
  `docs/agents/README.md`; every stale "one tool" claim across those files and
  `docs/architecture.md` is corrected to name both shipped tools,
  `get_entity_context` and `compare_entity_sources`; `docs/architecture.md`
  now attributes `ArchitectureTest` to `data-prism-architecture`, where task 23
  moved it, instead of the renamed module; and `README.md`'s "Until Task 20
  delivers…" claim is replaced — the configuration-driven JSON REST mode
  shipped as the published `data-prism-connectors-rest` artefact, self-
  registering via Spring's `AutoConfiguration.imports`, requiring no Java, but
  covering only flat JSON (`ConfiguredJsonFieldMetadataResolver.descendable()`
  always returns `false`, so a nested object is never covered).

### Not changed

- The hash-chained audit trail is durable and tamper-evident against an
  outside forger, and no more than that. `AuditEventHash` is unkeyed
  SHA-256, so anyone with write access to the audit file can recompute the
  whole chain; the trail does not resist the operator. Nothing here should
  be read as, or later restated as, a claim that the audit log is
  tamper-proof, immutable, or independently complete.

## [0.2.0] - 2026-09-17

A second MCP tool, `compare_entity_sources`, and no other new feature.

### Added

- `compare_entity_sources`, the second MCP tool: per-field `identity` plus
  findings over the same correlated, scrubbed `ContextResponse`
  `get_entity_context` already builds. Findings report agreement,
  disagreement (`INCONSISTENT`/`FORMATTING_ONLY`/`ABBREVIATION`) and
  `MISSING_IN_SOME_SOURCES`, each distinguishable by an explicit
  discriminator rather than by absence, so a caller can tell "compared and
  consistent" from "never compared".

### Changed

- The MCP registry namespace is corrected to `io.github.AindriuB/data-prism`,
  matching the casing the registry actually grants for the GitHub login.
  Maven Central's `io.github.aindriub` and the GHCR path
  `ghcr.io/aindriub/data-prism-server` are different identifiers, each
  correct in its own system and untouched by this correction — do not
  "fix" the casing inconsistency between them; doing so would break two
  already-published artifacts. No MCP registry listing for this server exists
  yet; this corrected namespace, and the label it is checked against, is what
  the first successful publish will use. `server.json`'s OCI package
  reference is also corrected to the canonical form the registry requires: no
  `registryBaseUrl`, and `identifier` now carries the image tag directly
  (`ghcr.io/aindriub/data-prism-server:0.2.0`) rather than a bare path paired
  with a separate `version` field.

### Behavioural change for API consumers

- `ContextResponse` gained a new record component (`fieldsByNamespace`), so
  its `equals`, `hashCode` and `toString` now include it. This is not a
  linkage break — binary compatibility was verified with `javap` against the
  published 0.1.1 jar, and the old constructor signature still works — but
  two `ContextResponse` values that compared equal under 0.1.1 may no longer
  compare equal under 0.2.0.

### Not changed

- The privacy engine, pseudonymisation and security modules: no behaviour
  change in this release.

## [0.1.1] - 2026-09-17

A release-plumbing patch, no features. 0.1.1 supersedes 0.1.0 for the
published image: the `v0.1.0` tag predates the multi-architecture publish
pipeline, so a dispatch against that tag would silently re-run the old
single-architecture workflow rather than publish a multi-arch image. `main`
has also diverged from the tag since (CI action bumps, documentation, a
quickstart dependency pin), so an image built from `main` and labelled
0.1.0 would not correspond to the tagged tree. Cutting a patch version is
cheaper and more honest than force-moving a tag a published GitHub Release
already points at. No production source differs from 0.1.0, so the library
artifacts on Maven Central are functionally identical and consumers of
those jars have no functional reason to upgrade; 0.1.1 exists for the image
and the publish pipeline, where multi-architecture support is a real
improvement for anyone running the image.

### Changed

- The server image is now published as a multi-architecture manifest list
  (`linux/amd64` + `linux/arm64`) instead of `linux/amd64` only.
- Three GitHub Actions dependency bumps in CI workflows.
- nimbus-jose-jwt bumped to 10.9.1 in `data-prism-quickstart-issuer`. This
  affects the quickstart issuer only, not the published server or library
  artifacts, which never depend on it directly.

## [0.1.0] - 2026-09-16

First release: the walking skeleton and every slice through S9a.

### Added

- Privacy engine that assigns one deterministic synthetic identity per
  `(scope, subject, namespace, algorithm version, key)`, never random and
  never stored in plaintext.
- Correlation and consistency findings that surface source-data
  inconsistencies instead of hiding them.
- Parallel mTLS connectors to enterprise source APIs.
- Embedded Hazelcast identity cache and per-scope read budget.
- OAuth2 resource server with session-derived `PrivacyContext`.
- Audit logging and metrics.
- One MCP tool, `get_entity_context`.
- Standalone server as the primary deployment surface, and a Spring Boot
  starter for embedding the privacy layer directly.
- A one-command local Compose quickstart.

### Not included in this release

- The re-identification operator surface, deferred past V1 by decision (see
  `docs/architecture.md#decisions-worth-knowing`).
- The Elasticsearch connector and its search tools.
- The three additional MCP tools named in the design review — only
  `get_entity_context` exists today.
- An append-only audit sink with hash-chain verifier. The only audit sink in
  this release writes to a file and to SLF4J.

[0.3.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.3.0
[0.2.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.2.0
[0.1.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.1
[0.1.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.0
