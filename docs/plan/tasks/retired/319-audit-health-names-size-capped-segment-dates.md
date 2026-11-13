# 319 — Name the date of a size-capped segment in the audit health detail

**Repo:** `.`
**Release:** 0.8.0 (before the cut, 321)
**Executor:** `implementer`
**Depends on:** none
**Wave:** 1 (0.8.0 pre-cut, filed 2026-11-13)
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditMaintenance.java *(the `SEGMENT` pattern only)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditIntegrityHealthTest.java

## Goal
When a retention purge fails, `auditIntegrity` health goes DOWN with the refusal code and the date of the
first refused segment. The date is read from the exception message with a pattern that matches only
`audit-YYYY-MM-DD.log`. Size-capped segments, new in 0.8.0, are named `audit-YYYY-MM-DD.NNNN.log`, so for
them the date is dropped, or a later plain segment's date is reported instead. Match both forms.

## Context
- Confirmed at filing (2026-11-13): `AuditMaintenance.java:28` (`SEGMENT`), `:70-72` (first `find()` on the
  message gives `segmentDate`), `:95-98`; read by `AuditIntegrityHealth.java:24-25` as detail `segmentDate`.
  The code and the DOWN status are unaffected; only the date detail is wrong.
- Messages that name a segment: `AuditRetention.java:227`, `:237`, `:251`, `:354`, `:817`, `:862`
  (`getFileName()` or a path).
- Both name forms: `SegmentedFileAuditSink.segmentDate` (`:158`), tested by
  `SegmentedFileAuditSinkSizeCapTest.java:137-145`; the verifier at `AuditChainVerifier.java:382`.
- `AuditIntegrityHealthTest.java:62-90` — the DOWN-then-UP test to follow; 2 tests at filing.

## Acceptance
- [ ] AC1 A purge failure whose message names `audit-2026-03-01.0002.log` gives health detail
  `segmentDate` = `2026-03-01`.
- [ ] AC2 The plain form `audit-2025-01-10.log` still gives `2025-01-10` (the existing test passes unchanged).
- [ ] AC3 A message naming no segment still gives no `segmentDate` detail.
- [ ] AC4 The index part follows `SegmentedFileAuditSink.java:39` (`SEGMENT_NAME`: four digits, never `0000`);
  a message naming only `audit-2026-03-01.12.log` or `audit-2026-03-01.0000.log` gives no `segmentDate`.
- [ ] AC5 Nothing else in `AuditMaintenance.java` changes.

## Checks
- `health` test — AC1 to AC4
  run: `./mvnw -q -pl data-prism-spring-boot-autoconfigure -am test -Dtest=AuditIntegrityHealthTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-spring-boot-autoconfigure/target/surefire-reports/TEST-*AuditIntegrityHealthTest.xml`
  min-passing: 5  max-skipped: 0
- `full-build` command — AC5
  run: `./mvnw -B verify`
- `scope` command — Owns, AC5
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'data-prism-spring-boot-autoconfigure/src/(main|test)/java/io/github/aindriub/dataprism/spring/boot/(AuditMaintenance|AuditIntegrityHealthTest)\.java')"`
- `one-line` read — AC5
  how: the `AuditMaintenance.java` hunk changes the `SEGMENT` literal only.

## Out of scope
- Reusing `SegmentedFileAuditSink.segmentDate` on a parsed path (a larger change; the message is free text).
- What the health detail exposes, and any metric.

## Review round 1 — findings

Reviewed HEAD: 1ab4b7c2ceed92f086f1eb3ab90e485a821b2fcd

- [P2] data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditIntegrityHealthTest.java:126-133 — The two AC4 tests (`an_index_that_is_not_four_digits_names_no_date`, `an_index_of_0000_names_no_date`) pass without exercising the pattern; they would pass with the old pattern or a loose `(?:\.\d+)?`. Renaming the tampered seed to `audit-2025-01-10.12.log` or `.0000.log` makes `AuditRetention.purge` skip it (`SegmentedFileAuditSink.segmentDate`, SEGMENT_NAME at SegmentedFileAuditSink.java:38-39; AuditRetention.java:182-185), so nothing fails and health is UP with empty details; `doesNotContainKey("segmentDate")` passes whatever `AuditMaintenance.SEGMENT` is, and neither test asserts DOWN. route: implementer — make each test drive a purge failure whose message names only that file and assert health DOWN with the expected code and no `segmentDate`. If that is impossible without a main-code change beyond the `SEGMENT` literal (AC5), stop and report BLOCKED: spec change.

## Outcome

Landed 2026-11-13, PR #274, merge `aeaab781`, verified at `842c7771`. The `auditIntegrity` health detail now names the date of a size-capped segment (`audit-YYYY-MM-DD.NNNN.log`) instead of dropping it or reporting a later plain segment. Review round 1 found the two index-edge tests passed without exercising the pattern; they were rewritten. Tester PASS, reviewer APPROVE. Follow-ups are in `PLAN.md`.
