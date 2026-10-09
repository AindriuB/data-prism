# 180 — Remove the dead FactoryBean loop from the JSON-projection preflight; keep the final drop line when `close()` runs on an interrupted thread; de-flake the listener thread-count checks

**Repo:** `.`
**Depends on:** none (wave 12)
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditSinkSelection.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditOutputConfigurationTest.java (insertions only, and only if the loop turns out to be reachable)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventListeners.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditEventListenerTest.java

## Goal
Clear three audit follow-ups, from 161 and 163.

1. The preflight's second loop (AuditSinkSelection.java:139-148) adds FactoryBeans whose product is an `AuditSink`. `getType(name, false)` returns the product type for a FactoryBean, or `null`, and never the `FactoryBean` class. So the `FactoryBean.class.isAssignableFrom(type)` branch never runs, and `getBeanNamesForType(AuditSink.class, true, false)` already finds typed FactoryBeans.
2. When `close()` runs on a thread whose interrupt flag is already set, the `join` on the final-report thread throws at once. `close()` then returns before the `AUDIT_LISTENER_DROPPED` line is written.
3. `AuditEventListenerTest.dispatcherThreads()` counts every live thread with the dispatcher's names across the whole JVM. A thread from an earlier test that exits between the "before" and "after" samples makes the count wrong.

## Context
- AuditSinkSelection.java:124-164: the preflight `dataPrismJsonAuditProjectionPreflight` and `isBuiltInSink`.
- AuditOutputConfigurationTest.java:249-252 and :275-278: a typed `FactoryBean<AuditSink>` that must stay refused. AuditOutputConfigurationTest.java:288-302: an untyped FactoryBean that must leave the built-in sink in force.
- AuditEventListeners.java:140-171: `reportDropsWithoutBlocking`, a daemon thread joined for at most `FINAL_REPORT_WAIT_MILLIS` (1000). AuditEventListeners.java:223-266: `close()`.
- docs/plan/tasks/retired/178-close-and-json-dir-condition.md: the final report moved off the closing thread because a blocked appender hung shutdown. **Do not log on the closing thread.** A plain `tryLock()` followed by an inline `log.warn` would bring that bug back.
- AuditEventListenerTest.java:77-81: `dispatcherThreads()`, used at :546-612.
- docs/plan/tasks/retired/163-audit-event-listener-spi.md: Outcome, the last bullet.

## Acceptance
FactoryBean loop:
- [ ] The loop over `getBeanDefinitionNames()` is gone from AuditSinkSelection.java. `grep -n "FactoryBean.class.isAssignableFrom\|ResolvableType" data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditSinkSelection.java` prints nothing.
- [ ] `an_application_factory_bean_audit_sink_with_a_json_directory_is_refused` and `an_untyped_factory_bean_does_not_replace_the_built_in_sink_so_the_projection_stays_wired` pass unmodified. So does every other test in AuditOutputConfigurationTest.
- [ ] If removing the loop makes a test fail, the loop stays instead. A comment above it then names that test, and AuditOutputConfigurationTest gains no other change. The hand-back reports which of the two outcomes happened.
- [ ] `isBuiltInSink`'s Javadoc says it depends on `AnnotatedBeanDefinition` factory-method metadata, and would therefore refuse hash-chained plus json-directory under Spring AOT or native images, which are unsupported.

`close()` on an interrupted thread:
- [ ] A new test does the following. It gives the dispatcher a WARN sink that sleeps about 100 ms before recording the line. It produces at least one drop. It sets the calling thread's interrupt flag, then calls `close()`. It asserts that the `AUDIT_LISTENER_DROPPED` line, with the correct count, has been recorded by the time `close()` returns, and that `Thread.currentThread().isInterrupted()` is still `true` afterwards. It clears the flag in `finally`. Against the pre-change `AuditEventListeners` the test fails, because the line is written after `close()` returns. Show this by committing the test first or with a temporary WIP commit, and quote the failure in the hand-back.
- [ ] `close()` still returns within `drainTimeout + 1 s + 1 s + FINAL_REPORT_WAIT_MILLIS` (plus a small margin) when an appender blocks forever. The existing `close_returns_even_if_an_appender_hangs_forever_inside_a_report_tick` (AuditEventListenerTest.java:436) and `a_blocking_log_appender_never_stalls_recording` (:393) stay green and unmodified.
- [ ] The final report is still logged only on the `data-prism-audit-listeners-final-report` thread. Recommended mechanism: wait for that thread in a bounded loop that tolerates interrupts. Clear the flag, join until a deadline of `FINAL_REPORT_WAIT_MILLIS`, then restore the flag.

Thread-count flake:
- [ ] `dispatcherThreads()` is replaced by a helper that compares thread identities. It takes the live dispatcher and reporter threads before constructing, takes them again afterwards, and the difference is the threads under test. The tests at :546-612 assert on that set: size 2 after start, and every member `!isAlive()` after `close()`, joining each with a bound before asserting. `grep -n "isEqualTo(before" data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditEventListenerTest.java` prints nothing.
- [ ] `for i in 1 2 3 4 5 6 7 8 9 10; do mvn -q -pl data-prism-core test -Dtest=AuditEventListenerTest || exit 1; done` passes.

Build:
- [ ] `mvn -q -pl data-prism-core,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- `close()` skipping the drain when the calling thread is interrupted. `thread.join(drainTimeout)` throws at once, so queued events are dropped and counted. Report it as a follow-up and do not change it here.
- Supporting Spring AOT, or catching an untyped FactoryBean whose product can only be known by creating it.
- Bean counts, the `PrivacyExtensionPoints` inventory, and any property or refusal code.
- CHANGELOG.md. Both changes are internal to features that have not been released yet.
