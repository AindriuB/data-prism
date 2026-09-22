# 65 — Scan the durable audit file for stub identifying values

**Repo:** .
**Depends on:** 64
**Owns:**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFilePiiScanTest.java (new)

## Goal
`docs/architecture.md:166-170` records boundary 7 as enforced only for log
output, by `PiiLogScanTest`. That holds today because the log is the only sink.
A durable file sink adds a second place a source value could reach, with no test
watching it. Add the file-capturing analogue, reusing `PiiLogScanTest`'s
derivation of banned values from the stub adapters.

## Context
- `data-prism-integration-tests/.../http/PiiLogScanTest.java:88-160` — the derived
  banned set, computed from the three stub adapters' fixture records at class load
  so nobody has to maintain a list; `:314-366` — the scan and the failure-capture
  write to `logs/`.
- `docs/plan/tasks/64-file-audit-sink.md` — the record format being scanned.
- `docs/conventions.md`, "Logs" — a leak-detection test writes what it captured to
  `logs/` before asserting, never only on success.

## Acceptance
- [ ] A full integration run is configured to write audit records through
      `FileAuditSink` to a temporary file, and the test asserts that file contains
      no value from the derived banned set.
- [ ] The banned set is derived from the same stub adapters `PiiLogScanTest` uses,
      not from a hand-maintained literal list, and the test fails if the derived set
      is empty.
- [ ] The scan is proven non-vacuous: a test writes a known banned value into a
      scanned file and asserts the scanner reports it — the same mutation discipline
      `PiiLogScanTest.scannerIsNotVacuous` already applies.
- [ ] On failure the test writes the captured file content to
      `logs/audit-file-pii-scan-<millis>.log` before asserting, so a failure can be
      read once rather than re-derived.
- [ ] The test asserts at least one audit record was actually written during the
      run, so an empty file cannot pass the scan.
- [ ] `mvn -pl data-prism-integration-tests -am test` passes.

## Out of scope
- Editing `PiiLogScanTest.java` — including its two known latent traps, which
  remain open items in `docs/plan/PLAN.md`.
- The sink itself (task 64) and its Spring wiring (task 67).
- Scanning metric labels or trace attributes; boundary 7 stays partially enforced
  for those, and no doc may claim otherwise.
