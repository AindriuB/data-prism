# 75 — Give every process lifetime its own audit chain identity

**Repo:** .
**Depends on:** none
**Blocks:** 70; gates 62 (its `docs/audit.md` must describe the instanceId shape this task ships)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java (javadoc only)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCli.java (`--help` text only)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java (audit writer-id validation only)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/HashChainedAuditSinkTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/McpHttpEndToEndTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java (comment at :264 only)

## Goal
A hash-chained audit deployment restarted with the same `dataprism.audit.writer-id`
(documented example `writer-id: ${HOSTNAME}`) currently yields DUPLICATE_SEQUENCE
(exit 4) and then a false CHAIN BREAK (exit 2), because `AuditRecorder` restarts at
sequence 1 / GENESIS under a config-fixed `instanceId` and the verifier keys chains
on `instanceId`. Make each recorder instance (one per process lifetime in every
shipped wiring) stamp a distinct `instanceId` = configured writer-id plus a per-boot
suffix, so a restart reads as a new writer starting at GENESIS. Detection strength
must be exactly what it is today.

## Context
- `AuditRecorder.java:26-41` — `instanceId` is taken verbatim from the constructor;
  `:28,:35` GENESIS start. Keep the three-arg constructor signature
  `(AuditSink, Clock, String writerId)` so no call site changes; derive the suffix
  inside (e.g. `writerId + "/" + UUID.randomUUID()`), expose it via an accessor
  (`instanceId()`) for tests.
- Call sites that must keep compiling unchanged: `DataPrismAutoConfiguration.java:390`,
  `data-prism-integration-tests/src/main/java/.../example/DataPrismAssembly.java:85`,
  `ExampleApplication.java:84`, and the test constructions in data-prism-mcp,
  data-prism-orchestration, data-prism-connectors-rest (none assert on instanceId).
- `AuditChainVerifier.java:153` — chains keyed on `event.instanceId()`; `:173-214` —
  non-GENESIS first record and break logic; `:60-78` javadoc already claims "a new
  writer's chain starting at GENESIS partway through the file — an ordinary process
  restart". Verifier logic should need no change; if it does, that is a sign the
  design is wrong — stop and report.
- `AuditChainVerifierCli.java:167-171` — exit-4 help text calls a non-GENESIS start
  "an ordinary restart"; after this task an ordinary restart is a GENESIS start, so
  correct the wording without softening what exit 4 / exit 2 report.
- `AuditRecordFormat.java:158-170` — escapes separators, so `/` in instanceId round-trips.
- `DataPrismProperties.java:188` — `required(audit.writerId, "MISSING_AUDIT_WRITER", ...)`.
- `McpHttpEndToEndTest.java:233,:280` — exact-equality filters on
  `"data-prism-example"`; `:280` is a `noneMatch` that would pass vacuously once
  instanceIds carry a suffix.
- `docs/plan/PLAN.md` owner decision 2026-09-23 (threat model; truncation limitation).

## Acceptance
- [ ] Two `AuditRecorder`s built with the same writer-id produce events whose
      `instanceId()` values differ, and each starts with `<writer-id>/`.
- [ ] Every event from one recorder carries the same `instanceId`; the first event's
      `previousHash` is GENESIS and sequence is 1 (unchanged behaviour).
- [ ] `AuditRecorder` throws `IllegalArgumentException` for a blank writer-id or one
      containing the separator character; `DataPrismProperties.validate()` refuses the
      same at startup with a named code (e.g. `INVALID_AUDIT_WRITER`), shown by a
      test in `HashChainedAuditSinkTest`.
- [ ] New test in `data-prism-core/src/test/.../audit/`: recorder A with a
      `FileAuditSink` on file F writes N records; recorder B (same writer-id, new
      `FileAuditSink`, same F) writes more than N records; `AuditChainVerifierCli`
      on F returns 0 and the report shows two writers, zero anomalies, zero breaks.
- [ ] In that same restarted file, each of these still yields exactly today's
      result, asserted by exit code and finding type: deleting boot A's first
      record (non-GENESIS start → exit 2); editing any field of any record in
      either boot (exit 2); a first record for a writer whose `previousHash` is not
      GENESIS (exit 2); a duplicate sequence within one boot (exit 4).
- [ ] All pre-existing tests in `AuditChainVerifierTest` and
      `AuditChainVerifierCliTest` pass without having their expected exit codes or
      finding types changed.
- [ ] `McpHttpEndToEndTest` filters on `instanceId().startsWith("data-prism-example/")`
      at both :233 and :280, so the `noneMatch` is not vacuous.
- [ ] No text added anywhere claims the truncation (tail/whole-boot deletion)
      limitation is fixed; `AuditChainVerifierCli`'s printed limitation is unchanged.
- [ ] `./mvnw -q verify` green across the reactor.

## Out of scope
- docs/audit.md, docs/architecture.md, docs/protect-your-own-api.md,
  docs/extending.md, examples/json-sources/** — task 62's files. Report the new
  instanceId shape (`<writer-id>/<uuid>`) and that deleting an entire boot's records
  is as undetectable as truncation, so 62 can state it.
- docs/configuration.md:120 (`writer-id: ${HOSTNAME}` example) and the writer-id
  reference text — task 59's file. Record in the handback what 59 should say
  (writer-id need no longer be unique per boot; it must not contain `/`).
- CHANGELOG.md — task 70 writes the 0.3.0 line from this diff.
- Keyed/HMAC hashing, rotation, cross-writer anchoring, any verifier logic change.
- `AuditEvent` / `AuditEventHash` / `AuditRecordFormat` shape changes.
