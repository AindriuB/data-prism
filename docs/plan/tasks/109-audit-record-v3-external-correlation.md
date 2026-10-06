# 109 — Record the external correlation id in audit record version 3

**Repo:** `.`
**Depends on:** 102, 108, 117
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/** *(except `Slf4jAuditSink.java`, which must stay unchanged; task 112 changes it)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFieldDispositionTest.java *(the `recordVersion` assertion at `:97` only)*

## Goal

The audit record gains `externalCorrelationId`, next to Data Prism's own
`correlationId`. The field must be inside the hash, or it can be edited
without breaking the chain. A v2 record's hash cannot include it without
changing what v2 means, so new records are written as `recordVersion` 3 and
the verifier accepts v1, v2 and v3 records in one chain. Version 3 hashes
with task 117's length-prefixed v2 encoding, and appends
`externalCorrelationId` as one more encoded field. It defines no encoding of
its own. (Amended 2026-10-06: the C2 encoding items moved to task 117, in
release 0.4.0.)

## Context

- `AuditEvent.java` — v2 added `recordVersion`, `fieldDispositions`,
  `approvalId` and `approverId`. It is a record with a 20-argument
  compatibility constructor. `CURRENT_VERSION = 2`.
- `AuditEventHash.java` — the v1 body is `|`-joined and unchanged. After
  task 117, v2 is a length-prefixed encoding that starts with the version and
  ends with `approverId`, documented in the class Javadoc. Task 117's
  `src/test/resources/audit/hash-vectors.txt` pins one v1 and one v2 vector.
- `AuditRecordFormat.java` — v1 has 20 fields, v2 has 24. `parse` decides the
  version by field count.
- `AuditEntry.java` — the recorder input. It is constructed positionally
  (17 arguments) in `data-prism-orchestration`, `data-prism-mcp`,
  `data-prism-security` and `data-prism-reidentification`, all outside
  `Owns`. Those calls must keep compiling.
- Task 102's `SegmentedFileAuditSink`, `AuditRetention` and directory-mode
  verifier, and task 97's checkpoints. Checkpoints carry sequence and hash
  only, so v3 does not change them.
- Task 108's `ExternalCorrelationId` and its ceiling character class.

## Acceptance

- [ ] `AuditEvent` gains `externalCorrelationId` (null becomes `""`).
      `CURRENT_VERSION = 3`. The constructor throws
      `IllegalArgumentException` if a non-empty value fails task 108's
      ceiling (length at most 256, characters in `[A-Za-z0-9._:/+=-]`). This
      is a second fail-closed check behind the inbound validation.
- [ ] Every existing `AuditEvent`, `AuditEntry` and `AuditEventHash.compute`
      signature still compiles and keeps its current meaning. The v2-shaped
      24-argument `AuditEvent` constructor still produces a record with
      whatever `recordVersion` it is given. `AuditEntry` gains an 18-argument
      constructor, and the 17-argument one passes `""`.
- [ ] `AuditRecorder` writes `recordVersion` 3 records carrying
      `entry.externalCorrelationId()`.
- [ ] The v3 hash input is task 117's v2 encoding with version `3`, followed
      by `enc(externalCorrelationId)`. The `AuditEventHash` Javadoc says so.
- [ ] v1 and v2 hashes are unchanged. `hash-vectors.txt` gains one v3 line,
      and its v1 and v2 lines are byte-identical in `git diff`. A test asserts
      all three vectors.
- [ ] `AuditRecordFormat` writes v3 as 25 fields, with `externalCorrelationId`
      last, and parses 20, 24 or 25 fields. Any other count throws as today.
      A round-trip test covers v3 with an empty and a non-empty value.
- [ ] The verifier, in both file and directory mode, accepts one writer
      chain that starts with v2 records and continues with v3 records. It
      reports `VERSION_REGRESSION` with a non-zero exit when a writer's
      `recordVersion` decreases within its chain. One test covers each case.
- [ ] Editing `externalCorrelationId` in one line of a v3 file makes the
      verifier report a break at that line. A test asserts this.
- [ ] `Slf4jAuditSink.java` is unchanged.
- [ ] `AuditFieldDispositionTest`'s `recordVersion` assertion reads
      `AuditEvent.CURRENT_VERSION`, and nothing else in that file changes.
- [ ] `mvn verify` over the full reactor passes with no edit outside `Owns`.

## Out of scope

- Populating the field from a request. That is task 110.
- The slf4j sink and any JSON output. That is task 112.
- `docs/audit.md`, which tasks 102 and 106 own until they land. The v3
  format is documented by task 116. Until then, the Javadoc on
  `AuditRecordFormat` and `AuditEventHash` states the v3 layout and encoding.
- A keyed chain. Task 107 was dropped (D2).
