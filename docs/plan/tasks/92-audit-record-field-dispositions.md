# 92 — Carry per-field dispositions and approval identity in the audit record

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEvent.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEntry.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventHash.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecordFormat.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/** *(new fixtures only)*
- docs/audit.md *(the record-fields section only)*

## Goal

EU AI Act Arts. 12, 19 and 26(6) ask for logs that let a deployer reconstruct
what a system did with each input. Today an `AuditEvent` records who asked,
what was decided and which sources answered, but not what happened to each
field. This task adds three fields to the audit record: per-field disposition
(field path to action, never a value), `approvalId` and `approverId`. It also
adds a record version so that files written by 0.3.x still verify. This task
fixes the record shape that tasks 96, 100, 101 and 105 will write into.

## Context

- `AuditEvent.java:28-54` — the 20-component record. External test code
  constructs it positionally: `DefaultContextOrchestratorTest`,
  `CompareEntitySourcesToolTest`, `ValidationBoundaryTest`. Those files are
  outside `Owns`, so the 20-argument constructor must keep compiling.
- `AuditRecorder.java:61-112` — the 14-argument `record(...)`. Its callers are
  `DefaultContextOrchestrator:329` and the two MCP tools' deny paths. They stay
  on it until 96 and 101 move them.
- `AuditEventHash.compute` folds 19 fields (task 72). `AuditRecordFormat`
  serialises one JSON line per record, and `AuditChainVerifier` replays it.
- `docs/audit.md:78` lists the record fields.
- `docs/conventions.md#privacy-rules-a-diff-must-satisfy` — an audit record
  holds no sensitive value.

The consumers need this shape, so freeze it here:
- `fieldDispositions`: `Map<String, String>`, field path to action name.
  Paths look like `<sourceName>:<json-pointer>`, with array indices collapsed
  to `*` (for example `crm:/contacts/*/email`). The action is one of the
  `PrivacyAction` names or `REFUSED`. Task 96 produces it.
- `approvalId`: the approval request id, or `""`. Tasks 101 and 105 use it
  for tool-call approvals, and task 100 for re-identification four-eyes.
- `approverId`: the second principal's id, or `""`. Same consumers.

## Acceptance

- [ ] `AuditEvent` has three new components, `fieldDispositions`
      (`Map<String,String>`, defensively copied and sorted by key),
      `approvalId` (`String`) and `approverId` (`String`). It also has a
      `recordVersion` (`int`, `2` for every record written after this
      change). The pre-existing 20-argument constructor still compiles and
      yields `recordVersion == 1`, an empty map and `""` for the new strings.
- [ ] The `AuditEvent` constructor throws `IllegalArgumentException` when a
      disposition value is not a `PrivacyAction` name or `REFUSED`. A unit
      test covers this case.
- [ ] A new `AuditEntry` record carries every caller-supplied field: the 14
      existing ones plus the three new ones. `AuditRecorder.record(AuditEntry)`
      exists, and the existing 14-argument `record(...)` delegates to it with
      empty dispositions and `""` approval fields.
- [ ] `AuditEventHash` folds `fieldDispositions` (as sorted `path=ACTION`
      pairs), `approvalId` and `approverId` into the hash for version-2
      records only. Version-1 records still hash over exactly the 19 fields
      task 72 defined.
- [ ] `AuditRecordFormat` writes `recordVersion`, `fieldDispositions`,
      `approvalId` and `approverId` for version-2 records. A line without
      `recordVersion` parses as version 1.
- [ ] A committed fixture `data-prism-core/src/test/resources/audit/v1-chain.jsonl`
      holds a two-record version-1 chain, written synthetically with no real
      data. The verifier reports it intact (exit 0). A test asserts this.
- [ ] A version-2 chain whose dispositions are edited in one record is
      reported as a break (exit 2). A test asserts this.
- [ ] `git diff --stat` shows no file changed outside `Owns`.
- [ ] `mvn -pl data-prism-core -am verify` passes.
- [ ] `docs/audit.md` documents the three new fields and `recordVersion`, and
      states that dispositions name field paths and actions and never values.

## Out of scope

- Populating dispositions from a real scrub. That is task 96, and the scrub
  side is task 93.
- Checkpoints, retention and keyed hashing. Those are tasks 97, 102 and 107,
  and all of them edit these same files later.
- Moving any existing caller to `record(AuditEntry)`.
- Documenting `correlationId` as a join key. That is tasks 101 and 106.

## Attempt 1 — failed

Branch `task/92-audit-record-field-dispositions` (50d84a2). Reviewer: APPROVE. Tester: FAIL.

- Full `mvn verify` fails in data-prism-integration-tests: all 6 tests in
  `AuditFilePiiScanTest` error with "AuditEvent component recordVersion has
  unrecognised type java.lang.Integer" (`leaksIn`, AuditFilePiiScanTest.java:400).
  The task's `-pl data-prism-core -am` command never reaches that module.
- Fix: add
  `data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFilePiiScanTest.java`
  to Owns. Teach `leaksIn` to treat Integer as a non-PII scalar and to scan
  `fieldDispositions` map **keys and values** (do not exempt the map — it is
  the defence against the task-93 leak). Run the full reactor `mvn verify`.
- Also in Owns and worth doing: AuditChainVerifier.java:22 still says
  "nineteen" hashed fields; say v2 adds three.
- Reviewer suggestion, for a follow-up (not this attempt): AuditEventHash joins
  dispositions with `,`/`=` unescaped, so two different disposition maps can
  hash identically (same weakness as the existing `sourceSystems` join).
  Keying the chain (107) does not fix an ambiguous encoding.
