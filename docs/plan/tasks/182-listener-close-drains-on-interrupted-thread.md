# 182 — Drain queued listener events and join the reporter when `close()` runs on an interrupted thread

**Repo:** `.`
**Depends on:** none (wave 12). Owner decision D-180-1 = (b), already made.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventListeners.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditEventListenerTest.java (insertions only; existing tests are not edited)

## Goal
`AuditEventListeners.close()` on a thread whose interrupt flag is already set
skips the drain: `thread.join(drainTimeout)` throws at once, so queued events
are abandoned and counted as dropped, and `rt.join(1000)` on the reporter
thread also throws, so the reporter is not joined
(`AuditEventListeners.java` about lines 232-266; PLAN.md "OPEN (found in 180
review)"). Per D-180-1 (b), `close()` must deliver queued events and join both
threads exactly as it does on an uninterrupted thread, then leave the caller's
interrupt flag set. Interrupts that arrive while `close()` is waiting are
recorded and restored in the same way.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventListeners.java:164-183 — the pattern task 180 used for the final-report wait: `Thread.interrupted()` captured up front, a `System.nanoTime()` deadline loop around `TimeUnit.NANOSECONDS.timedJoin` that sets `interrupted = true` on `InterruptedException` and keeps waiting, the flag restored in `finally`. Apply the same shape to the three joins in `close()`: dispatcher drain (`drainTimeout`), dispatcher after abandon (1000 ms), reporter (1000 ms). A small private helper ("join until deadline, return whether interrupted") is acceptable if it serves all four waits.
- AuditEventListeners.java:228-266 — current `close()`. Its `catch (InterruptedException)` sets `abandoned` and interrupts the dispatcher at once; under D-180-1 (b) an interrupt no longer ends the drain early. Abandon happens only when the drain deadline passes.
- docs/plan/tasks/retired/178-close-and-json-dir-condition.md — rule: nothing is logged inline on the closing thread; the final line goes through `reportDropsWithoutBlocking()`.
- docs/plan/tasks/retired/180-audit-dead-factorybean-loop-and-listener-close.md — prior task on the same method.
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditEventListenerTest.java:593-627 — existing `close_on_an_interrupted_thread_still_writes_the_final_drop_line_and_keeps_the_flag`; the new test follows its shape (interrupt before `close()`, `Thread.interrupted()` in `finally`). Test-class rule (Javadoc line 35): every wait is a latch, a future with a deadline bound, or `close()`; no sleeps used for synchronisation.
- Suggested deterministic red test: a listener that blocks on a gate for the first event; record several events so they queue behind it; set the test thread's interrupt flag; start a helper thread that polls the closing thread until `getState()` is `TIMED_WAITING` (bounded by `BOUND_SECONDS`) and then opens the gate; call `close()`. Today `close()` never waits, so events are dropped. After the fix the closing thread waits in the timed join, the helper opens the gate, and every event is delivered.
- docs/audit.md:658-668 describes the five-second shutdown drain without mentioning interrupts. It stays accurate after this fix and is not edited.
- CHANGELOG.md:120 — the listener is in `[Unreleased]` (0.6.0 work), so this fix gets no CHANGELOG entry.

## Acceptance
- [ ] A commit `182: ...` that adds only the new failing test(s) to `AuditEventListenerTest.java` comes before the fix commit. `git show <that commit>` touches only the test file, and running the test at that commit fails.
- [ ] New test: N ≥ 3 events are queued behind a slow or gated listener, the interrupt flag is set before `close()`, and after `close()` returns: the listener has received all N + 1 events in sequence order; `droppedCount()` is `0`; no `AUDIT_LISTENER_DROPPED` line was written; `Thread.currentThread().isInterrupted()` is `true`. The flag is cleared in a `finally` block.
- [ ] New test: an interrupt delivered to the closing thread while it waits in the drain (helper interrupts it once it is `TIMED_WAITING`, then opens the gate) does not shorten the drain. All queued events are delivered, `droppedCount()` is `0`, and the flag is set when `close()` returns. The flag is cleared in `finally`.
- [ ] New or extended assertion: after `close()` on an interrupted thread, the dispatcher and reporter threads started by that instance have both terminated (checked with the existing `startedSince` and `assertAllStopped` helpers).
- [ ] `close()` contains no `catch (InterruptedException ...)` that returns early, sets `abandoned`, or skips a remaining join. Every wait in `close()` is deadline-bounded with `System.nanoTime()`, and the worst-case duration is unchanged: drain timeout + 1 s abandon join + 1 s reporter join + the final-report wait.
- [ ] `close()` makes no `log.*` call on the closing thread. `git diff` shows no new `log.` call in `close()`; the final line still goes through `reportDropsWithoutBlocking()`.
- [ ] Every existing test method in `AuditEventListenerTest` is byte-for-byte unchanged (`git diff <task base>..HEAD -- <test file>` shows insertions only) and passes. This includes `close_returns_even_if_an_appender_hangs_forever_inside_a_report_tick`, `close_returns_even_if_an_appender_hangs_forever_on_the_final_report`, `close_abandons_a_hung_listener_after_the_drain_timeout_and_logs_the_remainder_as_dropped` and `close_on_an_interrupted_thread_still_writes_the_final_drop_line_and_keeps_the_flag`.
- [ ] `mvn -pl data-prism-core -Dtest=AuditEventListenerTest -Dsurefire.failIfNoSpecifiedTests=false test` passes 10 consecutive runs. Report the loop command and its output.
- [ ] `mvn -pl data-prism-core,data-prism-spring-boot-autoconfigure -am verify` is green.

## Out of scope
- The `run()` / `deliver()` loop, the reporter tick, `reportDropsWithoutBlocking()`'s own wait (already fixed in 180), queue capacity and the drain-timeout default.
- Making an interrupt cut the drain short (option (a) of D-180-1, rejected).
- `docs/audit.md`, `CHANGELOG.md`, `docs/migration-0.6.md` (CHANGELOG and migration belong to task 179's Owns).
- `AuditSinkSelection` and anything else in `data-prism-spring-boot-autoconfigure`.
- PLAN.md / HISTORY.md: the scribe removes the open item on record.
- Never read credential files (`~/.m2/settings.xml`, `~/.gnupg/**`, tokens).
