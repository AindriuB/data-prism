# 102 — Segment the audit file by day and purge expired segments with retention anchors

**Repo:** `.`
**Depends on:** 97
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**
- data-prism-core/src/test/resources/audit/**
- docs/audit.md *(a new "Retention" section and the verifier's directory mode)*

## Goal

EU AI Act Arts. 19 and 26(6) require deployers to keep automatically
generated logs for at least six months. GDPR storage limitation means the
logs should then actually go. A single append-only file can do neither
without breaking its own chain. This task adds a sink that writes one file
per UTC day and a purge that deletes whole expired day-segments. Before it
deletes, the purge records a `RETENTION_ANCHOR` checkpoint for each writer's
last record in the segment. The verifier then accepts a chain that starts
right after an anchor, and still flags any gap no anchor explains.

## Context

- `FileAuditSink.java` — the single-file, append-mode, fsync-per-record sink
  with poisoning. The new sink reuses its write discipline.
- The v0.3.0 decision (`docs/plan/PLAN.md` ~line 662) shipped "no rotation
  (rotation is operational)". **This task reverses that for one new sink
  and needs owner confirmation.** See the plan return. `FileAuditSink`
  itself is not changed.
- Task 97: `AuditCheckpoint.Kind.RETENTION_ANCHOR`, `AuditCheckpointSink`,
  and the verifier's `--checkpoints`.
- Art. 19: "at least six months, unless provided otherwise in applicable
  Union or national law".

## Note from owner decisions D3 and D5 (2026-10-06)

D3 is answered: the segmented sink, anchors and purge are approved, and the
existing `FileAuditSink` stays unchanged. D5 is answered: a retention below
six months is refused **unless an explicit override is passed**; the
acceptance item below that reads as a hard refusal is amended. Required:

- `AuditRetention` takes an explicit `boolean allowBelowMinimum` (named
  `retention-override` in configuration, `dataprism.audit.retention-override`,
  wired by task 103). Without it, a short retention throws
  `IllegalArgumentException` containing `AUDIT_RETENTION_BELOW_MINIMUM`.
- Tests: refusal without the override, acceptance with it, and the six-month
  boundary accepted either way.
- `docs/audit.md` states that Art. 19 allows other periods under Union or
  national law and that using the override is the operator's legal
  responsibility.

## Acceptance

- [ ] `SegmentedFileAuditSink(Path directory)` appends each event to
      `directory/audit-YYYY-MM-DD.jsonl`, where the date is the UTC date of
      `event.timestamp()`. It uses the same fsync and poisoning semantics as
      `FileAuditSink`, and a test asserts them by porting
      `FileAuditSinkTest`'s torn-write case.
- [ ] `AuditRetention(Path directory, Period retention, AuditCheckpointSink
      anchors, Clock clock)` rejects any `retention` that, added to
      2025-01-01, lands before 2025-07-01. It throws
      `IllegalArgumentException` containing `AUDIT_RETENTION_BELOW_MINIMUM`.
- [ ] `AuditRetention.purge()` deletes only segments whose date is strictly
      before `today(clock) - retention`, and never today's segment. For each
      writer with records in a segment, it first writes a `RETENTION_ANCHOR`
      checkpoint carrying that writer's last sequence and hash in the
      segment. If any anchor write fails, it deletes nothing and throws. A
      test with a fixed `Clock` asserts the exact set of files left and the
      anchors written.
- [ ] The verifier accepts a directory and reads segments in date order.
      Given `--checkpoints`, a writer whose first surviving record follows a
      `RETENTION_ANCHOR` (its `previousHash` equals the anchor hash and its
      sequence equals the anchor sequence + 1) is reported intact with a line
      naming the anchor, at exit 0. The same chain without the anchor is
      reported as it is today for a non-genesis start. A test covers both.
- [ ] A segment deleted by hand, without an anchor, while later segments
      survive, is still reported as a break or as `MISSING_WRITER` / exit 5.
      A test asserts this.
- [ ] Every `FileAuditSinkTest` and existing verifier test passes unchanged.
- [ ] `mvn -pl data-prism-core -am verify` passes.
- [ ] `docs/audit.md` documents the segment layout, the six-month minimum
      and why, the anchor mechanism, and that purge is deletion. It does not
      say "compliant".

## Out of scope

- `dataprism.*` properties and scheduling the daily purge. That is task 103.
- Changing `FileAuditSink`'s single-file behaviour.
- Archiving to external storage. That is an operator responsibility.

## Attempt 1 — failed

Branch `task/102-audit-segments-and-retention` (93ce165). Reviewer: CHANGES.

- Defect 1 — anchors can launder recent deletions (AuditChainVerifier.java
  :269-272, :367-376; docs/audit.md:146-149). Someone who can append one line
  to the checkpoint file and delete from the audit directory can hide a recent
  deletion: delete the last three days' segments, append a RETENTION_ANCHOR
  with the last deleted seq/hash, and the verifier reports intact (exit 0).
  The same trick on a whole writer suppresses MISSING_WRITER. Fix:
  - RETENTION_ANCHOR records the purged segment's UTC date (and the anchored
    sequence). Update AuditCheckpoint and its parser; older checkpoint files
    without the field must still parse, and a RETENTION_ANCHOR without it is
    treated as not covering anything.
  - The verifier accepts an anchored start only if the anchor's segment date is
    at least the minimum retention before the anchor's recordedAt. Add a
    verifier option for the minimum (default P6M) so deployments that use the
    override can pass their period. Otherwise report it as an anchor anomaly
    (non-zero exit) naming the writer.
  - Disclose plainly in docs/audit.md that whoever can append to the checkpoint
    file can make deletions of segments older than the retention window look
    legitimate, so checkpoint custody must be separate.
- Defect 2 — day-based Periods bypass the 6-month floor (AuditRetention.java:55).
  P181D–P183D are accepted, yet a six-month window can be up to 184 days.
  Fix both:
  - the constructor refuses any Period that can be shorter than six calendar
    months from some start date (e.g. require totalMonths ≥ 6, or days ≥ 184
    when the Period has no month or year part);
  - purge() also guards at run time: refuse unless
    cutoff ≤ today.minusMonths(6), or the override is set.
  Tests: P181D, P183D refused; P184D and P6M accepted; a purge on 2026-12-31
  never deletes a segment dated after 2026-06-30.
- Defect 3 — a clock stepping back across UTC midnight writes one writer's
  consecutive records into earlier files (SegmentedFileAuditSink.java:84-87),
  which gives a false break. Fix: the segment date never goes backwards, i.e.
  date = max(current segment date, event date). Add a test.
- Also required:
  - purge() verifies an expiring segment's chain before anchoring it. If the
    chain doesn't verify, refuse to purge that segment (and every later one)
    with a coded RetentionException, so purge never erases evidence of
    tampering.
  - Add a test for the torn-fragment newline insertion between segments.
- Run the full reactor `mvn verify` and mkdocs `--strict`; report real exit codes.

## Attempt 2 — failed

Branch `task/102-audit-segments-and-retention` (eaa32da). Reviewer: CHANGES. Minimum-retention probe, run-time guard, monotonic date, old-checkpoint parsing and test edit all confirmed correct.

- Defect 1 — a forged anchor date still launders recent deletions
  (AuditChainVerifier `anchorIsOldEnough`; docs/audit.md "Retention anchors").
  Delete the last 3 days, append a RETENTION_ANCHOR with the last deleted
  seq/hash, recordedAt = now and segmentDate = 2020-01-01: the verifier reports
  intact (exit 0), and the same through `anchoredPast` hides MISSING_WRITER.
  Fix:
  - Reject an anchor (seq n, date D) if the same writer has any BOOT, PERIODIC
    or SHUTDOWN checkpoint with seq < n and recordedAt UTC date after D.
  - Also require the first surviving record after the anchor to be dated ≥ D.
  - Test each case, including a forged anchor rejected because of a later
    head checkpoint.
  - Rewrite the disclosure accurately: whoever can append to the checkpoint
    file can still make a recent deletion look like a purge when the writer
    has no head checkpoint after the forged date. Regular PERIODIC
    checkpoints (task 103 schedules them) narrow that window. Checkpoint
    custody must be separate.
- Defect 2 — purge erases evidence of a hand deletion (`verifySegments` with
  lenientStart, used at AuditRetention.java:133). If segment D0 is deleted by
  hand, or the front of D1 is cut, purge anchors D1 and deletes it, and the
  verifier then reports intact. Fix: AuditRetention reads the existing
  checkpoint file's earlier anchors. Each writer's first expiring record must
  start at GENESIS or follow an earlier RETENTION_ANCHOR exactly (same seq − 1
  and hash); otherwise refuse with CHAIN_UNVERIFIED. Test both inputs.
- Defect 3 — docs/audit.md exit-codes table row 2 must name RETENTION_ANCHOR_REJECTED.
- Suggestion to take: document that the monotonic segment date is per sink
  instance, so a restart with the clock behind can put a reused writer-id's
  record in an earlier-dated file. The verifier reports a break, which fails
  loud; say so.
- Run the full reactor `mvn verify` and mkdocs `--strict`; report real exit codes.
- Owner decision (2026-10-06): rename the native segment files from
  `audit-YYYY-MM-DD.jsonl` to `audit-YYYY-MM-DD.log`, because the content is
  not JSON. Change SUFFIX/SEGMENT_NAME (SegmentedFileAuditSink.java:32-33),
  the verifier's directory-mode glob, the tests and docs/audit.md.
