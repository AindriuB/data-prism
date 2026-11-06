# 297 — Make audit-sink writes survive a thread interrupt

**Repo:** `.`
**Base branch:** `origin/main`. One PR, targeting `main`.
**Depends on:** none
**Owns** (under `data-prism-core/src/`):
- `main/java/io/github/aindriub/dataprism/audit/sink/FileAuditSink.java`
- `main/java/io/github/aindriub/dataprism/audit/sink/SegmentedFileAuditSink.java`
- `main/java/io/github/aindriub/dataprism/audit/sink/UninterruptedWrites.java` *(new, package-private)*
- `test/java/io/github/aindriub/dataprism/audit/sink/AuditSinkInterruptTest.java` *(new)*

## Goal
`FileChannel.write` and `force` on an interrupted thread close the channel. The sink then stores `poisonedBy`, and every later request is refused with `AUDIT_SINK_POISONED` until a restart, so one interrupted fan-out takes audited traffic down. An interrupt must no longer reach the channel. A genuine I/O failure must still poison the sink, and no call may return unaudited.

## Context
- `FileAuditSink.java:150-165` is `recordLine`. The class Javadoc explains why a failed write poisons: it may leave a torn prefix.
- `SegmentedFileAuditSink.java:168-200` poisons on any `RuntimeException`. So a rollover is exposed too: `switchTo` (:220-250) calls `terminateTornTail` and `channel.size()`, which are also interruptible. The fix must cover all of `record`, not just `recordLine`.
- `SourceFanOut.java:471-491` restores the flag on the request thread. PLAN.md's `:158-161` is out of date.
- **Decision: run the channel calls on an internal thread no caller can reach.** The caller waits uninterruptibly and restores the flag in a `finally`.
  - Clearing the flag around the write (PLAN.md's first example) is rejected: an interrupt landing after the clear still closes the channel.
  - `FileOutputStream` is rejected: it replaces the `FileChannel` seam that `FileAuditSinkTest`, `SegmentedFileAuditSinkTest`, `SegmentedJsonAuditSinkTest`, `SegmentedFileAuditSinkSizeCapTest` and `FileAuditSinkTestAccess` inject through.
  - Reopening after `ClosedByInterruptException` is rejected: the torn prefix is the reason the sink poisons.
- Only `record` hops threads. `recordLine` stays direct, because `SegmentedFileAuditSink` calls it from inside its own hop, and a second hop onto a single thread deadlocks.
- A closed `SegmentedFileAuditSink` reopens a segment on the next `record`, so the internal thread needs no close lifecycle of its own. A shared daemon pool is enough.

| During `record` | Caller sees | Flag on return | Sink |
|---|---|---|---|
| Flag already set on entry | durable record, normal return | set | usable |
| Interrupt during write, `force` or rollover | durable record, normal return | set | usable |
| Channel throws `IOException`, including `ClosedByInterruptException` | `UncheckedIOException`, as today | as the caller left it | poisoned |
| Hand-off to the internal thread fails | an exception, nothing returned as written | as the caller left it | poisoned |

## Acceptance
- [ ] `AuditSinkInterruptTest` covers each row above for `FileAuditSink` and `SegmentedFileAuditSink`, with no sleeps (`docs/conventions.md`).
- [ ] The mid-write case injects a channel that parks inside `write` until the test has interrupted the calling thread, then delegates to a real channel. It asserts the record is on disk and a second `record` succeeds. A clear-and-restore implementation fails this test; the PR says this was checked.
- [ ] A size-capped `SegmentedFileAuditSink` rolling to its next segment with the flag set writes the record to the new segment and is not poisoned.
- [ ] An injected channel throwing `ClosedByInterruptException` poisons the sink: the next `record` throws `PoisonedException` with code `AUDIT_SINK_POISONED`.
- [ ] Behaviour after `close()` is unchanged for both sinks, and a closed sink leaves no non-daemon thread.
- [ ] No existing test file changes, and `mvn -pl data-prism-core -am test -Dtest='*AuditSink*,InterruptedAppendRestartTest' -Dsurefire.failIfNoSpecifiedTests=false` passes.

## Out of scope
- `FileAuditCheckpointSink.java`: same write pattern, but it is written at boot, from `AuditMaintenance` and at shutdown, never by a request thread. A follow-up for the coordinator.
- A timeout on the wait. A hung disk holds the request; it must not return unaudited.
- `SourceFanOut.java` (272 owns it in wave 8), `CHANGELOG.md` (291 inserts into it) and `docs/plan/design/contract-register.md` (272 inserts into it; no code or status changes here).
- Recovering a poisoned sink, and the constructors' open path.

## Outcome (2026-11-06, 0.8; PR #214)
`record` on both audit sinks now runs the channel work on an internal daemon writer thread (`UninterruptedWrites`). The caller waits uninterruptibly and gets its interrupt flag back, so an interrupt arriving mid-write no longer closes the channel and poisons the sink. The hand-off guard catches `RuntimeException | Error`: a `Thread.start` `OutOfMemoryError` poisons the sink and the recorder rolls its sequence back (attempted sequences 1, 2, 2; no gap). `FileAuditSink.record` is not synchronized, `recordLine` serializes writes, and `record` refuses a poisoned sink before the hand-off (this fixed a deadlock found during the task). `SegmentedFileAuditSink` is not affected by that deadlock.

Accepted: because the write runs on an internal thread, a hung disk holds a request uninterruptibly. That is fail closed and was accepted.

Tests: `AuditSinkInterruptTest` (20 tests, no sleeps, `@Timeout(30)` with `SEPARATE_THREAD`) covers each row of the task table for both sinks, the size-capped rollover with the flag set, `ClosedByInterruptException` poisoning, close behaviour, the flag on failure paths (including a caller interrupted while parked in a write that then fails) and a hand-off `OutOfMemoryError` through a real `AuditRecorder`. Mutations checked: clear-and-restore in the hop fails the mid-write tests; moving the flag restore from `finally` to the normal-return path fails four tests; narrowing the guard to `RuntimeException` fails the OOM test; re-adding `synchronized` fails at the 30 s timeout rather than hanging. `mvn -pl data-prism-core -am test -Dtest='*AuditSink*,InterruptedAppendRestartTest'`: 78 tests, 0 failures. No existing test file changed.

Not changed: `FileAuditCheckpointSink` has the same write pattern (PLAN follow-up). The wave-6 call that the interrupt flag is not restored "to protect the audit sink" lost its reason with this task (PLAN follow-up to reconsider it).
