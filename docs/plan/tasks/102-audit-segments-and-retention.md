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
