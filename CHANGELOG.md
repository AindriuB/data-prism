# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.1] - 2026-09-24

No runtime behaviour changed on the server. This release refreshes the
docs site and the MCP Registry listing, and corrects several documentation
and CHANGELOG claims found while writing them.

### Added

- A MkDocs Material docs site published from the existing user docs, with an
  identity (mark, favicon, palette, landing page), six concept diagrams
  embedded in context, and dedicated FAQ, comparison and three intent
  use-case pages.
- A developer guide overview with tutorials for writing an adapter and
  writing a custom `IdentityResolver`, both checked against the code they
  document, plus an example `IdentityResolver` and its unit tests in
  `data-prism-quickstart-extension`.
- `websiteUrl` and the current canonical tagline in the MCP Registry
  `server.json` entry, so the next registry publish carries them instead of
  the stale v0.3.0 description and missing `websiteUrl`.

### Changed

- The changelog page on the docs site now collapses every release but the
  latest, without editing `CHANGELOG.md` itself.
- CHANGELOG: corrected the 0.3.0 `AuditChainVerifier` entry, which attached
  "caught even on the last record" to deletions as well as edits; only edits
  are caught at a chain's tail, a deletion is caught only when a later record
  follows it (see `docs/audit.md`).
- Adopted a new canonical project description across README, the poms,
  `CITATION.cff`, `server.json` and the Dockerfile LABEL, replacing v0.3.0's
  "A privacy layer between MCP clients and enterprise APIs.": it now says
  Data Prism pseudonymises enterprise API data and refuses anything
  unclassified.

### Fixed

- `docs/tools.md`'s claim that an unclassified field is "dropped"; the
  shipped `DEFAULT`/`STRICT` profiles both refuse the whole request instead.

Not published to Maven Central; the Maven artifacts remain at 0.3.0.

## [0.3.0] - 2026-09-23

Nested JSON catalogues, one level deep, and a durable, hash-chained audit
trail — protect a real API without writing Java, and prove what happened.

### Added

- The configuration-driven JSON REST connector gains one level of named
  nested catalogues: a field can declare `nested: <name>`, pointing at an
  entry in a top-level nested-catalogues map whose own leaves may be
  `nonSensitive` or classified only — a nested leaf carries no identifier of
  its own (it inherits its subject from the enclosing record), so
  `identifier: true` and a further `nested:` are both refused there. No
  dotted paths, no JSONPath, no wildcard descent, no inferring structure
  from the wire — `subject-json-path` is untouched and a nested object never
  carries its own subject. A response nesting deeper than declared — a leaf
  the catalogue says is a scalar turning up as a structure — refuses with the new,
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
- An offline `AuditChainVerifier` CLI replays a hash-chained audit file's
  writers from a copy and reports one of five outcomes: exit 0 intact; 1
  unreadable input; 2 a detected break — an edit inside one writer's chain,
  caught anywhere including that chain's own last record, or a deletion
  caught only when a later record follows it in the same chain; 3 the final
  record has no terminating newline, reported as possibly in flight, which
  is proof of neither health nor tampering; and 4 a structural anomaly (an
  interrupted-write fragment, a duplicate sequence, or a chain not starting
  at `GENESIS` immediately after another anomaly), never returned together
  with a break. Exit codes 3 and 4 name shapes ordinary operation can also
  produce; neither rules out tampering. The verifier cannot detect
  truncation of a writer's most recent records at all — a file with its
  tail removed verifies intact at exit 0, because nothing remains in the
  file to disagree with — and the same blind spot extends to a whole
  process boot: deleting every record of one boot leaves the surviving
  writers reporting intact and never mentions the deleted one,
  indistinguishable from that boot never having run.
- An opt-in `dataprism.identity.resolver: pass-through` property selects
  `PassThroughIdentityResolver`, refusing an unrecognised value with
  `UNSUPPORTED_IDENTITY_RESOLVER`; an application-supplied `IdentityResolver`
  bean still wins. Combined with the configuration-driven JSON REST
  connector, this makes protecting a flat JSON API genuinely possible with
  no Java class. A Spring `FailureAnalyzer` now renders every
  `DataPrismConfigurationException` as an operator-facing block naming the
  refusal code, what to supply, and the two docs pages that explain it,
  `docs/configuration.md` and `docs/quickstart.md`, with no stack frame. A
  new walkthrough, `docs/protect-your-own-api.md`, takes a reader with a flat
  JSON REST API from nothing to a pseudonymised MCP response using only YAML.
- A one-command demo, `examples/quickstart-demo/run.sh`, drives the running
  Compose quickstart end to end over its real MCP transport (obtaining a
  token, handshaking over SSE-framed `tools/call` bodies, and printing a
  pseudonymised response), extracted out of the CI smoke test so a reader can
  run the same thing locally.
- The four Compose quickstart images (server-with-extension, fixtures,
  issuer, certs-init) are now published as multi-architecture GHCR
  manifests; `docker compose up` pulls them by default, with the
  from-source build path moved to `docker compose -f compose.yaml -f
  compose.build.yaml up --build`.

### Changed

- `data-prism-example` is renamed to `data-prism-integration-tests`: it hosts
  11 integration test classes with no duplicate elsewhere, including
  `PiiLogScanTest`, the sole enforcement of privacy rule 7, and was never a
  demo. Package `io.github.aindriub.dataprism.example` and `ExampleApplication`
  are unchanged. Six `data-prism-example` strings survive deliberately inside
  `data-prism-integration-tests` — its own JWT `issuer` and audit `writer-id`
  config values, and the tests asserting on them — because they are
  observable audit output, not a module identifier.
- A configured JSON source's `DataSourceAdapter` bean no longer needs a
  matching `dataprism.sources` entry: `DataPrismContractValidator`'s
  cross-check between `dataprism.sources` and the supplied adapters now
  requires only that `dataprism.sources` be a subset of the supplied
  adapters, not an exact match, so a source whose transport lives entirely in
  its own JSON catalogue can supply an adapter with no corresponding
  `dataprism.sources` entry. Every `DataSourceAdapter` bean still has to earn
  its way onto the review allow-list, though: it must be named by
  `dataprism.sources` or supplied by the JSON-catalogue mechanism (the
  catalogue's own source names), or startup refuses with the stable code
  `UNREVIEWED_SOURCE_ADAPTER` — an adapter bean present on the classpath for
  neither reason is not implicitly approved.
- Documentation reconciled against the shipped code rather than the plan that
  preceded it: two consumer guides, `docs/extending.md` and `docs/tools.md`,
  are now linked from `README.md`, `docs/quickstart.md` and
  `docs/agents/README.md`; every stale "one tool" claim across those files and
  `docs/architecture.md` is corrected to name both shipped tools,
  `get_entity_context` and `compare_entity_sources`; `docs/architecture.md`
  now attributes `ArchitectureTest` to `data-prism-architecture`, the module
  that hosts it, instead of the renamed module; and `README.md`'s "Until Task
  20 delivers…" claim is replaced — the configuration-driven JSON REST mode
  shipped as the published `data-prism-connectors-rest` artefact, self-
  registering via Spring's `AutoConfiguration.imports` and requiring no Java.

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
- A sink failure raised while recording an audit event no longer reaches the
  MCP client carrying its own exception text. `GetEntityContextTool` and
  `CompareEntitySourcesTool` now catch only the audit-record failure and
  rethrow it as `AuditUnavailableException`, exposed to the client as the
  stable code `AUDIT_UNAVAILABLE` with no text derived from the caught
  exception — closing a path by which a hash-chained sink's failure could
  disclose the server-side audit file's path to the MCP client. The response
  is still refused, exactly as before; only what the client is told changed.

### Behavioural change for API consumers

- `HmacSyntheticGenerator`'s discriminator widens from 20 bits (masked out of
  a single 4-byte digest word, four Crockford base32 characters) to 40 bits
  (eight distinct digest bytes, eight characters), and `ADDRESS` — which
  previously rendered no discriminator at all — now carries one like every
  other namespace. Every pseudonym this generator produces changes as a
  result; a pseudonym stored or compared under 0.2.0 will not match the one
  produced under 0.3.0 for the same input. `PseudonymisationVersion` now
  rejects a MAC algorithm whose digest is too short for the generator's own
  reads at construction time, with the stable code
  `pseudonymisation.algorithm-digest-too-short`, rather than surfacing an
  `ArrayIndexOutOfBoundsException` later; `HmacMD5` and `HmacSHA1` are both
  now rejected, so an operator configured with either must move to an
  algorithm whose MAC output is at least 24 bytes. This reduces collision
  probability substantially; it does not make collisions impossible, and no
  such claim is made.
- `AuditRecorder` now derives `instanceId` as `<writer-id>/<per-boot random
  UUID>` instead of the writer-id alone, so a restart under the same
  writer-id is reported as a new writer starting at `GENESIS` rather than a
  false chain break. `instanceId` values recorded before this change are not
  comparable to ones recorded after it. A writer-id containing `/` is now
  refused at startup with the new code `INVALID_AUDIT_WRITER`; rename any
  writer-id that contains a `/` before upgrading.
- Configuring `dataprism.audit.sink: approved-sink` with no matching
  `AuditSink` bean now refuses with the new code `AUDIT_SINK_BEAN_REQUIRED`
  instead of the generic `MISSING_AUDIT_SINK`, which is retained unchanged
  for the separate case of an absent or blank `dataprism.audit.sink`
  property. Anything keyed on the old code for the bean-absent case must
  switch to the new one.
- In 0.2.0, `DataPrismContractValidator` compared `dataprism.sources` and the
  supplied `DataSourceAdapter` beans for exact equality, so an adapter bean
  present on the classpath with no matching `dataprism.sources` entry refused
  with `UNRESOLVED_SOURCE_ADAPTER`. In 0.3.0 that same case — an adapter
  named by neither `dataprism.sources` nor the JSON-catalogue mechanism —
  refuses instead with the new code `UNREVIEWED_SOURCE_ADAPTER` (see the
  Changed entry above); `UNRESOLVED_SOURCE_ADAPTER` is retained, unchanged,
  for the other direction: a `dataprism.sources` entry with no adapter bean
  supplied for it. Anything keyed on the old code for the adapter-present
  case must switch to the new one.

### Not changed

- What the hash-chained audit trail's tamper-evidence covers, stated
  precisely: an edit of a record inside one writer's chain, caught anywhere
  including that writer's own last record, and a deletion of a record inside
  one writer's chain, caught only when a later record follows it. It does
  not cover truncation of a
  writer's most recent records, or deletion of an entire process boot's
  records — both are undetectable from inside the file alone (see the
  verifier entry above). `AuditEventHash` is also unkeyed SHA-256, so anyone
  with write access to the audit file can recompute the whole chain after
  tampering with it; the trail does not resist an operator, or anyone else
  who already has that access. Nothing here should be read as, or later
  restated as, a claim that the audit log is tamper-proof, immutable, or
  independently complete.

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

[0.3.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.3.1
[0.3.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.3.0
[0.2.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.2.0
[0.1.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.1
[0.1.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.0
