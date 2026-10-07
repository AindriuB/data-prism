# 114 — Extend the PII scans to the correlation id and the JSON projection

**Repo:** `.`
**Depends on:** 110, 112
*(Followed by 148, added 2026-10-07 by D-148-A: MDC coverage of the PII log scan is 148's, not this task's. This module logs through `slf4j-simple`, whose `NOPMDCAdapter` discards MDC, so 148 scans through a recording `MDCAdapter`. 148 inserts cases into `PiiLogScanTest` and adds `DataPrismAssembly` overloads after this task merges. Owns and Acceptance are unchanged.)*
**Owns:**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFilePiiScanTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/PiiLogScanTest.java
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java *(additive overloads only)*

## Goal

The two PII scans are the repository's proof that a full run writes no
fixture value into audit output. After this plan there are two new routes
into that output: the caller's correlation id, and the JSON projection. Each
scan must exercise both on a full integration run that carries a synthetic
correlation id, and each must prove it is not vacuous by catching a planted
fixture value.

## Context

- `AuditFilePiiScanTest` — full run through `FileAuditSink`, parsed via
  `AuditRecordFormat`, with not-vacuous and escaping cases (`:215-320`).
- `PiiLogScanTest` — task 112 already taught its parser `extCorrelation` and
  key-value pairs. This task adds the full run with an id present.
- `DataPrismAssembly` builds the tool chain by hand. It may need an overload
  that installs a transport context carrying an `InboundCorrelation`, and a
  sink of the caller's choosing. Existing signatures must not change.
- Leak tests need a mutation proof (`docs/conventions.md#tests`).

## Acceptance

- [ ] `AuditFilePiiScanTest` gains a full run through
      `TeeAuditSink(FileAuditSink, SegmentedJsonAuditSink)` with the `ecs`
      preset and routing set. Every call carries the correlation id
      `synthetic-clid-0001`. The native file and every `.ndjson` line are
      scanned against the derived banned set. The test asserts at least one
      `.ndjson` line whose `trace.id` equals `synthetic-clid-0001`, so the
      scan cannot pass on an empty projection.
- [ ] `AuditFilePiiScanTest` gains a not-vacuous case: a fixture value placed
      in `externalCorrelationId` through a correlation policy permissive
      enough to admit it (for example the pattern `[A-Za-z0-9._:-]{1,128}`
      and a fixture surname) is caught in both the native and the JSON
      output.
- [ ] `PiiLogScanTest` gains a full run with `synthetic-clid-0001` present.
      It asserts at least one audit line carrying
      `extCorrelation=synthetic-clid-0001` and scans the line and its
      key-value pairs.
- [ ] A test asserts that a correlation-shaped tool argument such as
      `correlationId=<fixture value>` does not reach any audit output. It
      appears only as a name in `rejectedArguments`.
- [ ] Each new leak assertion is shown to be non-vacuous. The commit body
      records the mutation used (for example, the renderer copying
      `subjectId` into the JSON) and that the test then failed.
- [ ] Existing cases in both test classes pass unchanged.
- [ ] `mvn -pl data-prism-integration-tests -am verify` passes.

## Out of scope

- Spring-wired output (task 113 has its own tests).
- Any change under `data-prism-core`, `data-prism-mcp` or
  `data-prism-orchestration`. If a scan finds a real leak, stop and report.
