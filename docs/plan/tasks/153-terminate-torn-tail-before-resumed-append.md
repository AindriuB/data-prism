# 153 — Terminate a torn audit tail before a resumed writer appends, so an interrupted write is never reported as tampering

**Repo:** `.`
**Depends on:** none. **Blocked on owner decisions D-153-A and D-153-B.** Do not fan this task out until both are recorded below.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/FileAuditSink.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/SegmentedFileAuditSink.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifier.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCli.java *(report wording only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecordFormat.java *(Javadoc only. `isOverCount`, `parse` and `serialize` behaviour must not change.)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/InterruptedAppendRestartTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/FileAuditSinkTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/SegmentedFileAuditSinkTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditChainVerifierTest.java *(insertions only)*
- docs/audit.md
- CHANGELOG.md *(the `[0.5.0]` section only, and insertions only)*

## Goal

External review of release PR #117 (head e23de419), finding B, P2, release
blocker for 0.5.0. Neither sink checks the existing file before it appends.
`FileAuditSink(Path)` and `SegmentedFileAuditSink.switchTo` both open with
`APPEND`. Suppose a process died mid-write and left an unterminated fragment
whose `recordVersion` field is intact. The restarted writer then appends its
first record onto the same physical line. That line has more than 25 fields
and a parseable version, so `AuditRecordFormat.isOverCount` (:93-114)
classifies it as `FIELD_COUNT_MISMATCH`, a break with exit 2. A crash is
reported as tampering.

The fix belongs on the writer side. Task 109's rule stays as it is: an
over-count line with a parseable version is a break, because a torn write can
only truncate. That rule is what closed the unhashed-field-injection gap, and
it must not be weakened.

## Context

- `FileAuditSink.java:12-45` (Javadoc): it already names this exact hazard
  for the in-process case and handles it by poisoning the sink. It also says
  the class "deliberately does not attempt to inspect, truncate or repair the
  file". That sentence changes with this task. The no-truncate and no-repair
  half stays.
- `FileAuditSink.java:52-71`: the public constructor opens its own channel.
  The package-private `(Path, FileChannel)` constructor is used in production
  by `SegmentedFileAuditSink.switchTo` (:121-137, through `APPEND_OPENER`,
  which opens `WRITE|APPEND` with no `READ`). It is also used in tests with
  channels built to fail. Both production paths must get the fix.
- `SegmentedJsonAuditSink` sits on top of `SegmentedFileAuditSink` with
  `.ndjson` segments, so it inherits whatever the fix does. Its output is not
  chained.
- `AuditChainVerifier.java:148-162`: directory mode already appends a `'\n'`
  to a non-final segment that ends torn, so the fragment cannot fuse with
  the next segment's first record. That is the same remedy on the read side,
  and it is the precedent for this task.
- `AuditChainVerifier.java:372-383` (`processLine`) and `:548-570`
  (`classifyParseFailure`). The `INTERRUPTED_WRITE_FRAGMENT` message already
  describes "a torn fragment followed directly ... by the first record a
  restarted writer appended". Task 109 turned the most likely form of that
  shape into a break.
- `AuditRecorder.java:91`: every boot gets a fresh `instanceId` and starts at
  GENESIS. The restarted writer's first record therefore verifies on its own
  once it is on its own line.
- `AuditRecorderRestartTest.java` is the existing restart fixture. Its
  truncation case tears earlier in the line, which is why this was missed.
- `AuditRecordFormat.escape` (:271-283) escapes `\`, `\n`, `\r`, 0x1F and
  0x1E. **A raw `\r` (0x0D) byte therefore never appears in a serialized
  record.** Option A2 below depends on this.
- `docs/plan/tasks/retired/109-*.md`, attempts 2 to 4, explain why
  over-count is a break. `docs/audit.md:428-440` (the version and field-count
  paragraph), `:276-286` (exit codes), `:612-625` ("possibly in flight") and
  `:665-673` (directory mode).

### Where the fragment ends, and what the bare-newline fix gives

The verifier output below assumes the writer only adds a bare `'\n'` before
appending (option A1). The table is for a version 3 line.

| Write torn inside | Line after a `'\n'` is added | Verifier today, given that line |
|---|---|---|
| fields 0-18 (fewer than 20 fields) | 1-19 fields | `INTERRUPTED_WRITE_FRAGMENT`, exit 4. Correct. |
| field 19 (`eventHash`) | 20 fields, parses as **v1** with a truncated hash | hash mismatch, **break** |
| fields 20-22 (the review's scenario) | 21-23 fields | `INTERRUPTED_WRITE_FRAGMENT`, exit 4. Correct. |
| field 23 (`approverId`) | 24 fields declaring v3 | `FIELD_COUNT_MISMATCH`, **break** |
| field 24, `externalCorrelationId` not empty | 25 fields with a truncated id | hash mismatch, **break** |
| after the last byte, before `'\n'` | a complete record | verifies |

A1 fixes the scenario the review reported. It leaves three tear points that
are still reported as tampering. Field 19 is a 64-character hash and field 24
can be up to 256 characters, so those cases are not rare. Directory mode
already has the same residual for non-final segments.

## Owner decisions

### D-153-A — how the resumed writer terminates a torn tail

Options:

- **A1. Bare `'\n'`.** On open, if the file is non-empty and its last byte is
  not `'\n'`, write `'\n'` and `force(true)`, then append. The verifier does
  not change. It is the smallest change and mirrors directory mode. The
  three tear points in the table are still reported as breaks.
- **A2. Mark the torn line: write `"\r\n"`, not `'\n'`.** The writer change
  is otherwise the same as A1. The verifier gains one narrow rule: a line
  whose final byte is a raw `\r` is a writer-terminated torn fragment. It is
  reported as `INTERRUPTED_WRITE_FRAGMENT` and never parsed. Directory mode's
  `join` adds `"\r\n"` to a torn non-final segment for the same reason.
  `isOverCount` and `parse` do not change.
  - *Tamper analysis.* A serialized record can never contain a raw `\r`, so
    a writer cannot produce this marker by accident. An attacker who adds
    `\r` to a valid line hides that record. That is the same power as
    deleting the line. If the record is in the middle of a writer's chain,
    the writer's next record fails its `previousHash` check, which is a
    break. If it is the writer's last record, the effect is tail truncation.
    That gap is already documented and is covered by `--checkpoints`, which
    reports `TRUNCATED_BEFORE_CHECKPOINT`, exit 5. The marker cannot inject
    a field, because the line is never parsed into an event.
  - *Cost.* About 10 lines of verifier code, a docs paragraph and the tests.
- **A3. Truncate the fragment back to the last `'\n'`.** Rejected. It
  destroys evidence and breaks the append-only, never-truncate contract.
- **A4. Relax the over-count rule in the verifier.** Rejected by the
  constraint task 109 set.

**Recommendation: A2.** Only A2 meets the acceptance, "interrupted write,
not a break", at every tear point. Under A1 a third or more of real tears
would still be reported as tampering. A2 does not touch task 109's rule.

### D-153-B — logs already holding a concatenated line

Writers before this fix, including every 0.4.x release, can already have
produced a fragment fused with a resumed writer's first record. The 0.4.x
verifier reported most of these as `INTERRUPTED_WRITE_FRAGMENT`, exit 4. The
0.5.0 verifier reports them as `FIELD_COUNT_MISMATCH`, exit 2. Upgrading the
verifier therefore turns an existing exit-4 log into exit 2.

Options:

- **B1. Keep the break, and explain it.** Exit 2 stays. The
  `FIELD_COUNT_MISMATCH` text in the report and the CLI names a legacy
  interrupted write followed by a restart as one known cause for logs written
  before 0.5.0. It tells the operator how to check: the trailing 20, 24 or 25
  fields should parse as a GENESIS record of a new `instanceId`, and the
  record after the line should continue that writer. `docs/audit.md` and the
  CHANGELOG upgrade notes say so.
- **B2. A distinct anomaly that is still a break.** The verifier tries to
  split the line into a truncated prefix and a trailing record that verifies
  as a GENESIS head. If that works it reports
  `CONCATENATED_AFTER_TORN_WRITE`, which `isBreak()` counts, so the exit is 2
  and retention stops there. The diagnosis is better. The cost is new
  parsing heuristics inside the release window.
- **B3. Downgrade a matching line to exit 4.** Not recommended. The
  heuristic decides whether a line is a break, and an attacker can build
  input to satisfy it. That reopens the class of ambiguity task 109 closed.

**Recommendation: B1.** It does not weaken anything, and it is limited to
message and docs changes. B2 can follow after 0.5.0 if operators report the
shape.

Record the decisions here before fan-out:
- D-153-A: _pending_
- D-153-B: _pending_

## Acceptance

These items assume the recommendations, A2 and B1. If the owner picks A1, the
three residual rows of the table become documented, asserted breaks. Do not
leave them untested.

Writer:
- [ ] `FileAuditSink(Path)` and `SegmentedFileAuditSink`'s segment open
      (`switchTo`) both check the existing file before their first append.
      If the file is non-empty and its last byte is not `'\n'`, they write
      the terminator and `force(true)` before any record. A file that is
      empty, absent or newline-terminated is not changed. A test asserts it
      is byte-identical.
- [ ] Fail closed. If the tail cannot be read, or the terminator write or
      fsync fails, opening fails with `OpenFailedException`
      (`AUDIT_SINK_OPEN_FAILED`). No record is appended. A test uses an
      injected failing channel or opener.
- [ ] The existing bytes are never truncated or rewritten. The test asserts
      that the file's original bytes are a prefix of its new content.
- [ ] `FileAuditSink`'s class Javadoc describes resume-time termination. The
      sentence "does not attempt to inspect ... the file" is corrected. "Never
      truncates or repairs" stays.

Verifier:
- [ ] Under A2, a line ending in a raw `\r` is reported as
      `INTERRUPTED_WRITE_FRAGMENT` (not a break) and is never passed to
      `AuditRecordFormat.parse`. `isOverCount` and `parse` are unchanged in
      `git diff`. Only Javadoc lines of `AuditRecordFormat.java` change.
- [ ] Directory mode's `join` uses the same terminator as the writer.

Regression, through the real sink, in the new `InterruptedAppendRestartTest`:
- [ ] The review's scenario. Write records through `AuditRecorder` and
      `SegmentedFileAuditSink`, and close. Append a torn fragment of the next
      v3 line that keeps `recordVersion` intact and has no newline (21-23
      fields). Reopen a new sink and recorder on the same directory and
      record one event. The verifier, in file and directory mode, reports
      exactly one `INTERRUPTED_WRITE_FRAGMENT`, no break, and exit 4. The new
      record verifies as its writer's GENESIS head. The CLI header does not
      say tampering.
- [ ] The same assertions, parameterised, for each tear point in the table:
      inside field 19, inside field 23, inside a non-empty field 24, and
      complete except for the newline. Run it through both
      `FileAuditSink(Path)` and `SegmentedFileAuditSink`.
- [ ] Task 109's tamper cases still report breaks with exit 2, and the tests
      assert each one exactly. Cover an unhashed field appended to a v2 and
      to a v3 line, mid-file and on the newline-terminated last line
      (`FIELD_COUNT_MISMATCH`), a v3 line relabelled v1 or v2, and
      `VERSION_REGRESSION`. Existing tests that already assert these are
      unchanged in `git diff`.
- [ ] Under A2, adding a raw `\r` to a valid mid-chain record is reported as
      a break, because the next record's `previousHash` does not match, with
      exit 2. Adding it to the last record of a writer, with `--checkpoints`
      covering that record, reports `TRUNCATED_BEFORE_CHECKPOINT`, exit 5.
- [ ] Under B1, a fixture built the old way (a fragment directly followed by
      a full record, no terminator) still reports `FIELD_COUNT_MISMATCH`,
      exit 2. The report text names the legacy interrupted-write cause.
- [ ] Retention is unchanged for real breaks. `AuditRetention` still stops
      at, and keeps, a segment that holds `FIELD_COUNT_MISMATCH` or
      `VERSION_REGRESSION`. The existing `AuditRetention*Test` classes are
      unchanged and pass. A segment whose only anomaly is a writer-terminated
      fragment is purgeable as any `INTERRUPTED_WRITE_FRAGMENT` segment is
      today.
- [ ] `SegmentedJsonAuditSinkTest` and `JsonAuditRetentionTest` are unchanged
      and pass.
- [ ] Mutation proofs, each with the names of the failing tests in the
      hand-back:
      1. Disable the resume-time terminator. The review-scenario test fails.
      2. Under A2, remove the `\r` rule from the verifier. The field-19,
         field-23 and field-24 cases fail.
      3. Make `isOverCount` return false when `raw.length > 25`. A task 109
         tamper test fails.

Docs:
- [ ] `docs/audit.md` describes the resume-time terminator and, under A2,
      the `\r` marker and its tamper analysis. It covers the legacy
      concatenated-line case under B1. Under A2 it states that the
      directory-mode terminator matches the writer's. The exit-code table
      changes only if a code's meaning changes.
- [ ] `docs/audit.md` states that one live writer process appends to a given
      file or segment directory. A second live writer opening the same file
      could otherwise terminate the first writer's in-flight line.
- [ ] `CHANGELOG.md [0.5.0]` gains a Fixed entry for this task and, under
      B1, an upgrade note on the legacy exit-4 to exit-2 change. It also gains
      a Fixed entry for task 152 (the outbound correlation header is stripped
      when no validated id is present). Only insertions.
- [ ] `mvn verify` over the full reactor passes, with no edit outside
      `Owns`.

## Out of scope

- Changing `isOverCount`, `parse`, the field counts, or what is hashed.
- `FileAuditCheckpointSink`. Its checkpoint file has the same
  append-after-a-torn-tail hazard. Name it in the hand-back as a follow-up.
- Locking or detecting concurrent writers on one file. Document the
  assumption and do nothing more.
- B2's split-line heuristic, unless the owner chooses B2.
- The pre-existing wording follow-up from task 109 attempt 3, where an
  unterminated final line is reported as "possibly in flight".
- `OutboundCorrelationInterceptor` and anything else in
  `data-prism-connectors-rest`. That is task 152.

## Owner decisions: decided 2026-10-08

- **D-153-A: A2.** A resumed writer terminates a torn tail with `"\r\n"` and fsyncs before appending. The verifier treats any line ending in a raw `\r` as INTERRUPTED_WRITE_FRAGMENT and never parses it. Directory mode uses the same terminator. `isOverCount`, `parse` and `serialize` do not change.
- **D-153-B: B1.** A fused line already present in existing logs stays a break (exit 2). The FIELD_COUNT_MISMATCH report text, `docs/audit.md` and a CHANGELOG upgrade note explain the legacy cause and how to check for it.

## Amendment, 2026-10-08: Owns widened

Two existing tests built a fused line by restarting a `FileAuditSink` on a torn file. With D-153-A the writer now terminates the tail, so the premise of both tests is gone by design. Owns therefore adds edits, not just insertions, to:
- `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditChainVerifierTest.java`, the test `midFileFieldCountErrorFromARestartFragmentIsReportedGracefullyAndSurroundingRecordsStillVerify` only;
- `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditChainVerifierCliTest.java`, the test `midFileFragmentReadsAsInterruptedWriteNotABreakOnTheCli` only;
- the stale class-Javadoc bullet in `AuditChainVerifier.java` about a "mid-file field-count error from a restart".

Rule: keep each test's intent, re-expressed. A restart after a torn write must produce one INTERRUPTED_WRITE_FRAGMENT, and the surrounding records and the restarted writer's GENESIS head must verify. Where a test checks the legacy fused shape, build the fused line explicitly, as the 153 legacy test does, and assert it is a break that names the legacy cause. Do not weaken any assertion beyond what the new behaviour requires. List each changed assertion in the commit body.
