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
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java (widened after attempt 1: the `seq` field's scan shape, and the :264 comment)
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFilePiiScanTest.java (widened during attempt 2: the `instanceId` field's scan shape only)

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

## Attempt 1 — failed on review (2026-09-23)

Tester: PASS. Full-reactor `mvn verify` green (625 tests, incl.
ServerPackagingIT). Restart verified against the real packaged server: boot 1
wrote 3 records, boot 2 (same writer-id, same file) wrote 4; the CLI reported
two intact writers `restart-writer/<uuid>`, exit 0. On that same file, a
middle-record edit, a last-record edit and deleting boot 2's first record all
gave exit 2 with the right findings. A writer-id of `bad/writer` is refused
with `INVALID_AUDIT_WRITER` and a blank one with `MISSING_AUDIT_WRITER`.
Reviewer confirmed `AuditChainVerifier` is untouched, detection is unchanged,
the suffix is `UUID.randomUUID()` minted once and not influenceable by config,
and the `McpHttpEndToEndTest` prefix assertions are no longer vacuous. Keep
all of it. WHAT FAILED:

1. BLOCKING, flaky test. `PiiLogScanTest.java:816-823`: the Slf4j `seq`
   field is not in `EXEMPT_SHAPES`, so it gets a plain `contains` scan, and it
   now carries a random UUID (`seq=<writer>/<uuid>/<n>`). A UUID containing
   `123` or `456` (e.g. `…-4123-…`) reports a banned fixture value as leaked
   when nothing leaked — roughly 1-3% of runs red at random, the same
   false-positive class the timestamp exemption at `:283-311` exists for, and
   a breach of the no-flaky-tests rule in `docs/conventions.md` "Tests". Owns
   is now widened to cover this. Fix by pinning the `seq` field's shape
   (`<writer-id>/<uuid>/<digits>`, UUID in canonical form) the way the
   timestamp is pinned — exempt ONLY that exact shape, so anything else in the
   field is still scanned. Prove the fix is load-bearing: a test (or a
   demonstrated temporary mutation) where a UUID containing `123` would have
   failed before and passes after, and a `seq` value carrying a banned value
   outside the pinned shape is still caught. `AuditFilePiiScanTest` should be
   checked for the same exposure (the instanceId field of each record); if
   it has it, report it rather than editing it — it is not in Owns.
2. Minor, fix while there: add a `HashChainedAuditSinkTest` case asserting a
   blank writer-id refuses with `MISSING_AUDIT_WRITER`. Extend
   `AuditRecorderRestartTest`'s edit case to also edit a boot-B record
   (ideally the last), matching its "either boot" name. Remove the "task 75"
   citations from code comments and javadocs (`DataPrismProperties.java:189-192`
   and the tests) — `docs/conventions.md` "Code comments" says comments do not
   restate the task file; say what the code does and why.

**Attempt 2, scope widened mid-attempt (2026-09-23):** the attempt-2
implementer found, and the main session confirmed, that
`AuditFilePiiScanTest` has the same exposure: its `EXEMPT_SHAPES` (`:170-175`)
does not cover `instanceId`, and the full-run recorder (`:380`) now mints a
random per-boot UUID into it. Owns now covers that file's `instanceId` scan
shape. Pin it to exactly `<writer-id>/<uuid>` the same way, with the same
two-sided proof (a UUID containing a banned value passes; a banned value
outside the pinned shape is still caught).
