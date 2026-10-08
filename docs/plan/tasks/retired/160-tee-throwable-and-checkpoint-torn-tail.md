# 160 — Poison `TeeAuditSink` on any `Throwable`; terminate a torn checkpoint tail before appending

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2).
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/TeeAuditSink.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/FileAuditCheckpointSink.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/TeeAuditSinkTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/FileAuditCheckpointSink*Test.java (new)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java (checkpoint-file reading path only, and only if D-0.6-7 requires a change there)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditCheckpointTest.java (insertions only)
- docs/audit.md (the checkpoint-file paragraphs only)

## Goal
Two 0.5.0 follow-ups from `PLAN.md`, folded into 0.6.0 because they are small
behaviour fixes in files task 157 will move; landing them first keeps that
task a pure move. (g): `TeeAuditSink` catches only `RuntimeException`, so an
`Error` from the projection after the primary write leaves the tee unpoisoned
and a sequence number can be reused. (am): `FileAuditCheckpointSink` has the
same append-after-torn-tail hazard task 153 fixed in the audit sink.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/TeeAuditSink.java (47 lines).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/FileAuditCheckpointSink.java (116 lines).
- docs/plan/PLAN.md, "Decisions recorded for task 153" — D-153-A (a resumed writer terminates a torn tail with `"\r\n"` and fsyncs before appending; the verifier reports a raw-`\r`-ended line as `INTERRUPTED_WRITE_FRAGMENT`).
- docs/plan/tasks/retired/153-terminate-torn-tail-before-resumed-append.md — the audit-sink fix and its tests, the pattern to follow.

## Acceptance
- [ ] `TeeAuditSink` catches `Throwable` after the primary write, marks itself poisoned, and rethrows the original throwable unchanged. A test drives a projection that throws `AssertionError` (an `Error`) and asserts: the error propagates, and the next `record(...)` call refuses without writing to the primary.
- [ ] Mutation proof in the hand-back: narrowing the catch back to `RuntimeException` fails that test.
- [ ] **Assumption, confirm with the owner before starting (D-0.6-7 in the plan's return note):** the checkpoint file follows D-153-A. On open, if the file's last byte is not `\n`, `FileAuditCheckpointSink` writes `"\r\n"` and fsyncs before its first append. A test writes a checkpoint file with a torn final line, opens the sink, appends one checkpoint, and asserts that the new checkpoint is on its own line and parses as an `AuditCheckpoint`. A second test runs `AuditChainVerifier.verify(audit, checkpoints)` over that file and asserts the outcome D-0.6-7 settles for the torn line (proposed default: reported as an anomaly with a stable code, never parsed into a checkpoint and never silently skipped). `FileAuditCheckpointSink`'s own read-back on open (`FileAuditCheckpointSink.java:81`) must not fail on the terminated torn line.
- [ ] Mutation proof in the hand-back: removing the terminator write fails that test.
- [ ] The audit record format, record version and the checkpoint record format are unchanged: the existing golden and format tests in `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/` pass with no edits.
- [ ] `docs/audit.md` says, in the checkpoint section, that a torn checkpoint tail is terminated before the next append.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- (t), the projection as its own bean and `TeeAuditSink` made `Closeable` (task 161).
- (ak) and (al), the 153 verifier wording and test-assertion follow-ups. Not folded in; they stay in `PLAN.md`.
- Moving either class to a subpackage (task 157).
- Any change to `FileAuditSink` or `SegmentedFileAuditSink`.

## Owner decision D-0.6-7: decided 2026-10-08, options (i) and (a)

- **Writer:** on open or resume, if the checkpoint file does not end in `\n`, the checkpoint sink terminates it with `"\r\n"` and fsyncs before appending, as in D-153-A. Failure to do so fails closed: the open fails and nothing is appended.
- **Verifier:** a damaged checkpoint line, one ending in a raw `\r` or otherwise unparseable as a checkpoint, is reported as a named anomaly, `TORN_CHECKPOINT_LINE`. It is never parsed as a checkpoint, never skipped silently, and never reported as a break. The intact checkpoints are still used. Exit-code handling follows the existing anomaly precedence.
- **Rationale, from the owner:** a crash is not tampering, but it must not be ignored either.
- **Docs:** state the reduced tail-truncation coverage for a writer whose last checkpoint is torn. That writer falls back to its previous checkpoint.

Acceptance adds:
- a test that the torn-checkpoint restart produces exactly one `TORN_CHECKPOINT_LINE` and that the later checkpoints still verify;
- a mutation proof.

## Outcome (2026-10-08, wave 1)
Attempt 1 failed review: TeeAuditSink did not catch Throwable. Fixed. Owner decisions 2026-10-08: (a) Owns widened by one line in AuditChainVerifierCli.header(AnomalyType); (b) NARROW torn rule: only \r-ended lines and a final unterminated unparseable chunk are torn, other garbage stays exit 1 (narrows D-0.6-7). Tester PASS 1386/0/0/0, both mutations caught. Reviewer APPROVE. Residual risk: an attacker who can write the checkpoint can label a deletion as not tampering; a CRLF-converted checkpoint reports every line torn (exit 4).
