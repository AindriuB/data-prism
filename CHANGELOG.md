# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.0] - 2026-10-08

### Breaking

- The starter and auto-configuration now require Spring Boot 4.1 (4.1.1,
  Spring Framework 7.0.9). Spring Boot 3 users stay on 0.4.x. The library jars
  still run on Java 21 or newer.

### Added

- Inbound correlation id. A validated external correlation id can arrive on
  MCP tool calls through a configured HTTP header
  (`dataprism.correlation.inbound.*`). The strict default pattern accepts only a
  UUID, 16 to 128 hex characters containing at least one letter a to f, or a
  W3C `traceparent`. Digit-only values, such as card numbers, are refused. A
  broader pattern is available only by explicit configuration. It is off
  unless a header is set.
- Outbound correlation header. REST sources send the id as a header. It is off
  unless configured, and a call without an id sends nothing.
- `dataprism.correlation.outbound.header`, an optional global outbound header.
  A per-source `correlation-header` overrides it.
- `dataprism.correlation.mdc-key`, unset by default. When set, it puts only the
  validated id into the SLF4J MDC on the tool handler thread and on each
  fan-out task, and clears it afterwards. Warning: whatever the inbound pattern
  admits then appears in every log line written on those threads, so keep the
  pattern to generated ids.
- A JSON/ECS audit projection, written through a tee, with
  `dataprism.audit.output.*` configuration (field preset, field names, routing
  constants and an optional NDJSON `json-directory`). The hash-chained `.log`
  stays authoritative and the JSON is a separate projection. ECS
  `event.outcome` is derived from `policyDecision`, and the raw decision is
  kept beside it.
- `dataprism.audit.entity-types`, an optional list of the entity types that may
  appear verbatim in an audit record.
- New startup refusal codes, each failing closed:
  `INVALID_CORRELATION_FORMAT`, `INVALID_CORRELATION_PATTERN`,
  `CORRELATION_PATTERN_NOT_APPLICABLE`, `INVALID_CORRELATION_HEADER`,
  `CORRELATION_REQUIRED_WITHOUT_HEADER`, `CORRELATION_REQUIRES_HTTP_TRANSPORT`,
  `INVALID_CORRELATION_MDC_KEY`, `CORRELATION_MDC_KEY_RESERVED`,
  `CORRELATION_MDC_KEY_WITHOUT_HEADER`, `INVALID_AUDIT_FIELD_PRESET`,
  `INVALID_AUDIT_FIELD_PATH`, `UNKNOWN_AUDIT_FIELD`,
  `AUDIT_FIELD_MAPPING_CONFLICT`, `INVALID_AUDIT_ROUTING_VALUE`,
  `AUDIT_JSON_REQUIRES_SEGMENTED_SINK`, `AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT`,
  `AUDIT_JSON_DIRECTORY_UNUSABLE` and `INVALID_AUDIT_ENTITY_TYPE`.
- New runtime codes, which are not startup refusals: a call refused and audited
  before any source is called with `EXTERNAL_CORRELATION_ID_REQUIRED` or
  `EXTERNAL_CORRELATION_ID_INVALID` when `inbound.required` is true;
  `EXTERNAL_CORRELATION_ID_DROPPED` logged at WARN when an invalid inbound id is
  dropped and the call proceeds without one;
  and `AUDIT_PROJECTION_FAILED`, which fails later audited calls closed after a
  JSON projection write fails, until the process restarts. The verifier reports
  `FIELD_COUNT_MISMATCH` and `INTERRUPTED_WRITE_FRAGMENT` as anomalies, not as
  startup refusals.

### Changed

- The published images run Java 25. Library bytecode is still Java 21
  (`--release 21`, class major 65).
- Audit record version 3. Records are written as `recordVersion` 3 with 25
  fields, and `externalCorrelationId` is inside the hash. Version 1 and
  version 2 records still verify. The offline verifier reports a line with too many
  fields, or a full-length line that declares the wrong version, as
  `FIELD_COUNT_MISMATCH`, a break (exit code 2). A shorter line that could be a
  torn write is reported as an interrupted write (exit code 4), not a break.
- The audited `entityType`. `entityType` is audited verbatim only when it is a
  registered entity type or an upper-case-identifier-shaped token; otherwise it
  is recorded as `<unregistered>`. With no list configured, an upper-case token
  still passes, so set `dataprism.audit.entity-types`.
- Build and tests. Test ports are claimed by an OS file lock and probes run
  sequentially, to stop port-race flakes. Dependabot version updates are
  grouped into one pull request per ecosystem per week, and Java image majors
  and non-LTS Java 26 and later are ignored.

### Fixed

- An interrupted audit write is no longer reported as tampering after a
  restart. A writer that opened a file or segment ending in an unterminated
  fragment appended its first record onto that same line, and the verifier
  reported the over-long line as `FIELD_COUNT_MISMATCH` (exit code 2). A
  resumed writer now writes `\r\n` and fsyncs before its first record when the
  last byte is not a newline. It never truncates or rewrites existing bytes,
  and it fails the open (`AUDIT_SINK_OPEN_FAILED`) if it cannot do so. The
  verifier reads a line ending in a raw carriage return, which a serialized
  record never contains, as an `INTERRUPTED_WRITE_FRAGMENT` (exit code 4) and
  does not parse it. Directory mode uses the same terminator. The rule that an
  over-count line with a parseable version is a break is unchanged. One live
  writer per file or segment directory is assumed.
- The configured outbound correlation header (including `traceparent`) is now
  removed from every outbound REST request, so a client-level default or preset
  header can no longer send an unvalidated value when no valid id is present.

### Upgrade notes

- Logs written before 0.5.0, including every 0.4.x release, can already hold a
  torn fragment fused with a restarted writer's first record on one line. The
  0.4.x verifier mostly reported these as `INTERRUPTED_WRITE_FRAGMENT` (exit
  code 4). The 0.5.0 verifier reports them as `FIELD_COUNT_MISMATCH` (exit code
  2), and retention stops there. To check, see whether the trailing 20, 24 or
  25 fields of the line parse as a `GENESIS` record of a new `instanceId`, and
  whether the record after the line continues that writer. If so it is a legacy
  interrupted write, not an edit.

## [0.4.1] - 2026-10-07

Real, explicit clustering. In 0.4.0, `topology: embedded` started a bare
Hazelcast member that was never configured to join anything, so separate
instances never shared pause, approvals, the read budget or rate limits, even
though the docs said they did. This release lets members join one cluster by
explicit configuration, refuses to start without it, and says plainly what is
and is not shared and protected. Member traffic is **not encrypted** and members
do not authenticate each other: open-source Hazelcast has neither.

### Breaking

- `topology: embedded` now refuses startup unless `dataprism.hazelcast.cluster-name`
  and `dataprism.hazelcast.join.mode` are set (`MISSING_CLUSTER_NAME`,
  `MISSING_CLUSTER_JOIN`), and the cluster name must not be `dev`
  (`RESERVED_CLUSTER_NAME`). The migration for a single member is one line:
  `dataprism.hazelcast.join.mode: none` (plus a `cluster-name`), an explicit
  single member bound to loopback that neither discovers nor accepts other
  members. A multi-instance deployment chooses `tcp-ip` or `kubernetes`
  instead. 0.4.0 `embedded` members never clustered, so no working
  multi-instance deployment breaks; only the configuration needs editing.
- `dataprism.hazelcast.tls-key-reference` and `tls-trust-reference` now refuse
  startup with `HAZELCAST_TLS_UNSUPPORTED`. They were read by nothing in 0.4.0.

### Security

- Hazelcast auto-detection, multicast and phone-home are always disabled, so a
  member can no longer join an unrelated cluster named `dev` by discovery.
  `PrivacyCluster` also refuses an enabled advanced network config and an
  application-supplied instance that has auto-detection or multicast on
  (`UNSAFE_HAZELCAST_DISCOVERY`). Application `PrivacyCluster` beans are
  validated, and cluster properties set beside one refuse with
  `CLUSTER_SETTINGS_IGNORED`.
- Member TLS is unsupported, because open-source Hazelcast 5.7.0 has no member
  TLS engine and no member authentication. Member traffic is not encrypted.
  Network isolation (a private network, a `NetworkPolicy` or an mTLS mesh) is
  the deployer's responsibility, and `member.interface` should pin the member to
  that network. Without it, a `tcp-ip` or `kubernetes` member binds every
  interface.
- The server ships, and the starter declares, Hazelcast 5.7.0. 0.4.0 shipped
  5.5.0, because Spring Boot's dependency management overrode the declared
  version; the root pom now pins 5.7.0 ahead of the Boot BOM. A starter consumer
  must pin it too (see "Upgrading starter consumers to Hazelcast 5.7.0" below). 0.4.0's multi-member behaviour was only ever
  exercised on 5.5.0.

### Added

- `dataprism.hazelcast.cluster-name`, `join.mode` (`tcp-ip`, `kubernetes` or
  `none`), `join.members`, `join.kubernetes.namespace`, `join.kubernetes.service-name`
  or `service-dns`, `member.port` (default 5701) and `member.interface`, with
  startup refusals for missing or invalid values and for a member port shared
  with another listener (`CLUSTER_PORT_SHARED`).
- `docker/multi-instance/`: a two-member Docker Compose example and a
  Kubernetes manifest (DNS mode first; API mode, with its own ServiceAccount and
  Role, commented).
- A new "multiple instances" page, `docs/multiple-instances.md`, covering what
  members share, what they do not, and the residual risk that losing an entry's
  owner and its backup together loses the entry (backup count is 1).
- `EXPOSE 5701` in the server image. It is for private networks only; do not
  publish it, for example with `docker run -P`.
- `server.json` gains the cluster variables (`DATAPRISM_HAZELCAST_CLUSTERNAME`,
  join and member settings) and the operator variables (`DATAPRISM_OPERATOR_*`).
- A multi-member test that runs real server members and checks pause, approvals,
  the read budget, rate limits and re-identification are shared, and that
  refusals survive losing the member that owns the pause key.

### Fixed

- The docs claimed cluster-wide sharing, member TLS and member validation that
  did not exist. The configuration, EU AI Act support, architecture and
  re-identification pages now say what clustering shares and what it does not.

### Publication

0.4.1 publishes the library modules to Maven Central (not `data-prism-server`),
the quickstart images and the server image (`ghcr.io/aindriub/data-prism-server`)
to GHCR, and the server entry to the MCP Registry.

### Known limitations

- Anyone who can reach the member port can **read and write** cluster state.
  They can read subject ids, **forge an APPROVED approval and bypass four-eyes**,
  **lift a pause by deleting its flag**, and **reset read budgets and rate
  limits**. Member traffic is neither authenticated nor encrypted on open-source
  Hazelcast, so isolating the member port is the deployer's job. This includes a
  plain Hazelcast *client* configured with the cluster name: it can connect to a
  `tcp-ip` or `kubernetes` member's port and read or write maps. A `none` member
  refuses both members and clients.
- The Docker Compose example's `cluster` network is not a security boundary. The
  members bind and advertise only their cluster address, but OrbStack was
  observed to route across Docker networks, so a container on another network
  reached port 5701 on that address. Docker Desktop may behave the same (not
  tested). Linux Docker's network isolation is expected to block it (not tested
  here). Isolate 5701 with a host firewall, a `NetworkPolicy` or a private
  network in production.

### Upgrading starter consumers to Hazelcast 5.7.0

The starter declares Hazelcast 5.7.0, but Spring Boot's dependency management in
your build overrides that declaration, so a starter consumer gets 5.7.0 only by
saying so:

- With `spring-boot-starter-parent`: set `<hazelcast.version>5.7.0</hazelcast.version>`
  in your `<properties>`.
- When importing `spring-boot-dependencies`: add your own `<dependencyManagement>`
  entry for `com.hazelcast:hazelcast:5.7.0`. The property override does nothing
  for an imported BOM.
- With Gradle and the Spring dependency-management plugin:
  `ext['hazelcast.version'] = '5.7.0'`.

See "Using the starter" in `docs/multiple-instances.md`.

## [0.4.0] - 2026-10-06

Human oversight, a separate operator surface, audited re-identification, and
an audit trail with daily segments, external checkpoints and retention. These
features support an operator's work toward the human-oversight, record-keeping
and special-category obligations in the EU AI Act and GDPR Art. 9; they do not
by themselves establish that a deployment meets those obligations. Several defaults and refusals change
(see "Breaking and behaviour changes").

### Breaking and behaviour changes

- **Release note (task 94):** a PHI rule weaker than `REDACT` now refuses to
  start with `SPECIAL_CATEGORY_EXPOSED`, and a Java-built profile with no PHI
  rule resolves PHI to `REMOVE`. Seven GDPR Art. 9 special-category
  classifications fail closed. Check any custom profile before upgrading.
- All denials are recorded in the audit trail as `DENY:<code>`, from both the
  orchestrator and the MCP tools. A malformed code is recorded as
  `DENY:INVALID_REFUSAL_CODE`. Anything that filters audit records on the old
  denial value must change.
- Audit record version 2: the hash is computed over an unambiguous,
  length-prefixed encoding and the record carries per-field dispositions and
  approval identity. Version 1 records still verify.
- A configured audit retention below 6 months refuses startup unless
  `dataprism.audit.retention-override` is set.
- Audited calls are refused with `AUDIT_CHECKPOINT_UNAVAILABLE` while an audit
  checkpoint cannot be written.
- `ContextOrchestrator.buildContext` now throws `AuditedRefusalException` (a
  `PrivacyRefusedException`) with code `REQUEST_FAILED` for audited internal
  failures. Starter users who map `PrivacyRefusedException` to 403 should check
  `code()`.
- The MCP server always enforces admission (pause, rate limit, approval).
  Four-eyes defaults to on for re-identification
  (`dataprism.reidentification.four-eyes=true`). Tool calls need an approval
  only when the tool is listed in `dataprism.oversight.approval-required-tools`,
  which is empty by default. There are at most 5 live pending approvals per
  requester and kind, by default (`TOO_MANY_PENDING`); the oversight and
  re-identification caps are configured separately.
- `data-prism-server` now excludes Spring Boot's `HazelcastAutoConfiguration`.
  This is visible only with a `hazelcast.xml` or `hazelcast.yaml` on its
  classpath.
- Refusal codes returned by application scrubbers, validators and resolvers are
  validated before they reach the client; a malformed one becomes
  `INVALID_REFUSAL_CODE`.
- The MCP `serverInfo` version is read from the build rather than hardcoded.

### Added

- Human oversight: pause (cluster-wide with Hazelcast), per-caller rate limits,
  approvals bound to the call's purpose, profile, client and capabilities, and
  four-eyes approval, with in-memory and Hazelcast implementations.
- The operator surface, served on `dataprism.operator.port` in the same process
  with its own filter chain; the MCP and operator endpoints never share a port.
  It carries audited pause, approvals and re-identification.
- The `data-prism-reidentification` module and index: audited, purpose-bound
  re-identification with optional four-eyes approval, wired over the
  application's synthetic value source.
- External audit checkpoints (BOOT, PERIODIC and SHUTDOWN) and audit retention
  that purges expired segments behind `RETENTION_ANCHOR` checkpoints; the
  verifier gains directory mode and `--checkpoints`, which exits 5 on tail
  truncation or a missing boot.
- Opt-in daily audit segments named `audit-YYYY-MM-DD.log`, written when
  `dataprism.audit.directory` is set. `dataprism.audit.file-path` still writes
  a single file.
- Per-field dispositions in the audit record, and a `correlationId` returned in
  the MCP tool result `_meta`.
- The EU AI Act and GDPR Art. 9 support page, mapping what the project
  supports to each provision, and a table of every `policyDecision` form in
  `docs/audit.md`.

### Changed

- Undeclared payload keys render as `<undeclared>` and bracketed indices as
  `[*]` on every refusal and warning sink; undeclared property names never
  reach the model (`<undeclared-N>` under `REDACT_AND_WARN`, dropped under
  `DROP_AND_WARN`) and are not scanned by validators.
- `InMemoryApprovalStore` now refuses non-pending, duplicate and null-approver
  approvals exactly as the Hazelcast store does.
- Integration tests derive the artifact version from the build.

### Security

- Special-category (Art. 9) data fails closed: see the release note above.
- Refusal paths no longer leak undeclared payload keys, and refusal codes from
  application code cannot carry free text to the client.

### Publication

0.4.0 is published to Maven Central (the library modules; `data-prism-server`
stays off Central and comes from source, a GitHub Release or GHCR), to GHCR,
and to the MCP Registry.

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
- Adopted a new canonical project description in `README.md` and the root
  `pom.xml`, replacing v0.3.0's "A privacy layer between MCP clients and
  enterprise APIs.": it now says Data Prism pseudonymises personal data per
  privacy scope and refuses anything unclassified. The same text is the
  abstract of the new `CITATION.cff`.

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

[0.5.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.5.0
[0.4.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.4.1
[0.4.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.4.0
[0.3.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.3.1
[0.3.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.3.0
[0.2.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.2.0
[0.1.1]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.1
[0.1.0]: https://github.com/AindriuB/data-prism/releases/tag/v0.1.0
