# 163 — Add a read-only `AuditEventListener` SPI invoked after the authoritative write

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2)
once the tasks in Depends on have landed on that line. Do not start until owner decisions D-163-A to D-163-D below are
recorded in this file.
**Depends on:** 157, 159, 161, 164
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventListener.java (new; the `audit` contract package as 157 leaves it)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEventListeners.java (new, the dispatcher; name may differ, must stay in `audit`, not a subpackage)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java (the post-write hook and a constructor or factory that accepts listeners; no existing constructor removed)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditEventListener*Test.java (new)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/AuditRecorderTest.java (insertions only)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/*Audit*Configuration.java (the class that declares `dataPrismAuditRecorder` after 159; `DataPrismAutoConfiguration.java` instead if 159 left that bean there; the recorder bean method only)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java (only if D-163-C picks (b), (c) or (d): one row, plus one enum value for (c))
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanClassificationTest.java (insertions only, only if D-163-C adds a bean)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanInventoryTest.java and its checked-in lists (only if D-163-C adds a bean)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/*Audit*Properties.java and data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/** (only if D-163-A or D-163-B needs a property or a startup refusal)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AuditEventListenerWiringTest.java (new)
- docs/audit.md (one new section)
- docs/extending.md (one new section)
- docs/configuration.md (the `dataprism.audit` table, insertions only, only if a property is added)
- docs/conventions.md (one bullet inserted under "Deliberate, reviewed exception", only if D-163-D picks (x))

## Goal
Owner decision D-0.6-8 D1. Teams want to react to or forward audit events
without replacing the JSON projection, which 161 made non-replaceable. Add a
read-only listener SPI. Each listener receives the same `AuditEvent` that is
in the authoritative log. It is called only after that write has succeeded.
It cannot modify or veto the event, and its failures never affect the tool
call, the hash chain or the projection.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditRecorder.java:168-215 at 438ef802. `record(AuditEntry)` is `synchronized`. It advances `previousHash` only after `sink.record(event)` returns, and rolls the sequence back if the sink throws. The hook goes after `previousHash = hash`, never before.
- TeeAuditSink.java: with `json-directory` set, `sink.record` returns only after the primary (hash-chained) write and the projection write. So "after the recorder's sink returns" means after both. `FileAuditSink` fsyncs before `record` returns (FileAuditSink.java:14).
- AuditEvent.java:43-97. A record whose collection components are copied unmodifiable in the compact constructor, so "cannot modify" already holds by type. The test proves it; it does not re-implement it.
- DataPrismAutoConfiguration.java:566-571 at 438ef802. `dataPrismAuditRecorder` takes `ObjectProvider<AuditCheckpointSink>`. Listeners are discovered the same way (`ObjectProvider<AuditEventListener>.orderedStream()`), so `@Order` and `Ordered` are honoured.
- The default `dataprism.audit.sink` is not `hash-chained` (docs/configuration.md:75, :157). D-163-B settles what a listener sees then.
- PrivacyExtensionPoints.java:1-40. It classifies **framework** `@Bean` methods by name. A listener the application declares is not one of them. D-163-C settles whether a framework bean is added at all.
- docs/conventions.md, "Errors": no silent catch; log with a stable code, never with the payload. Also the "Deliberate, reviewed exception" paragraph, for what may and may not be logged from a caught object.
- 164 runs first. If this task adds a property, 164's metadata check fails until the property has Javadoc and a docs/configuration.md row.

## Owner decisions required before starting

**D-163-A: how listeners are executed.**
- **(1) Synchronously on the recording thread, inside the recorder's lock.**
  - For: events arrive in sequence order. Nothing is lost while the process lives. Simplest code, no new threads, no shutdown handling.
  - Against: a slow or hanging listener stalls every audited call in the process, because `record` is `synchronized`. The JVM cannot time out synchronous code safely. A slow listener therefore *does* affect the call's latency and availability, which is in tension with "never affecting the call".
- **(2) Synchronously on the recording thread, after the lock is released.**
  - For: a slow listener delays only its own call, not other threads' calls. No queue, so nothing is lost while the process lives.
  - Against: a slow listener still delays that call and holds its request thread. Listeners may see events out of sequence order across threads (each event still carries its `sequence`). The recorder's API has to return before dispatch, or dispatch has to move to the caller of `record`.
- **(3) Asynchronously on a bounded executor: one dispatcher thread and a bounded queue (capacity a property, for example `dataprism.audit.listeners.queue-capacity`, default 1024). On overflow the event is dropped for listeners only and logged with `AUDIT_LISTENER_DROPPED`.**
  - For: the call, the chain and the projection are never slowed by a listener. One thread keeps the sequence order.
  - Against: events can be lost to listeners under overflow or a crash. The log stays complete, but a listener is not a reliable copy, and the docs must say so. It adds a property, a thread, and a shutdown drain policy (for example drain with a bounded wait, then log `AUDIT_LISTENER_DROPPED` with a count). Overflow logging needs rate limiting or it floods.
- **(4) Asynchronous as in (3), but the caller blocks for a bounded time when the queue is full, instead of dropping.**
  - For: nothing is lost unless the timeout expires.
  - Against: a stuck listener adds up to the timeout to every call once the queue fills, and still drops after it. It has two tunables, not one.

**D-163-B: what happens when the configured sink is not `hash-chained` (`slf4j`, `approved-sink`).**
- **(i) Refuse startup when any `AuditEventListener` bean exists and the sink is not `hash-chained`, with a new stable code (for example `AUDIT_LISTENER_REQUIRES_HASH_CHAINED`).**
  - For: matches D1's wording ("durably written to the authoritative hash-chained log") literally, and fails closed.
  - Against: a development setup on `slf4j` cannot try out a listener. It adds a refusal code and a validation rule.
- **(ii) Invoke listeners after whichever sink is configured. The docs define "authoritative" as the configured sink.**
  - For: simplest, and works in every mode.
  - Against: with `slf4j` there is no durable, hash-chained record to reconcile the listener's copy against. That weakens D1's guarantee without saying so at runtime.
- **(iii) Do not invoke listeners unless the sink is `hash-chained`. Log `AUDIT_LISTENER_INACTIVE` once at startup.**
  - For: keeps D1's guarantee and does not block development.
  - Against: a misconfigured production deployment silently forwards nothing (one startup line is easy to miss). This is not fail-closed in the sense the project uses, though no data leaks.

**D-163-C: classification and wiring of the listener mechanism.**
- **(a) No new framework bean. `dataPrismAuditRecorder` collects `AuditEventListener` beans through `ObjectProvider`. `PrivacyExtensionPoints` gains no row, and its class Javadoc gains one sentence saying listeners are additive application beans outside the sweep.**
  - For: the smallest change, and the `@Bean` count stays at 56.
  - Against: the sweep, the project's own register of extension points, does not record the listener SPI anywhere it checks.
- **(b) A framework `dataPrismAuditEventListeners` dispatcher bean, classified `REPLACEABLE`/`NONE`.**
  - For: listed in the sweep.
  - Against: an application can then replace the dispatcher itself, for example with one that runs before the write or swallows nothing. The listener contract would rest on the replacement behaving.
- **(c) As (b), but under a new `Classification` value (for example `ADDITIVE`) with a guard that the dispatcher is unconditional.**
  - For: names the shape exactly: applications add listeners and cannot remove or replace the dispatcher.
  - Against: it changes the classification enum, and `PrivacyExtensionPointsTest` needs a behavioural proof for the new value. It is a vocabulary change for one bean.
- **(d) As (b), but `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`, using the existing "unconditional and collected into a list" meaning (as `LlmResponseValidator`).**
  - For: reuses the existing vocabulary, and the dispatcher cannot be replaced.
  - Against: it stretches "privacy-critical". The dispatcher protects the contract's ordering and isolation, not privacy directly. It can only see what the log holds.

**D-163-D: what a listener failure log line contains.**
- **(x) The code `AUDIT_LISTENER_FAILED`, the listener's class name, the event's `eventId` and `sequence`, and the caught `Throwable` (stack trace included).**
  - For: operators can debug the listener.
  - Against: a listener's exception message may quote the event or the listener's destination (a URL or a credential-bearing endpoint). Logging it moves that text into the server log. This needs a new entry under conventions' "Deliberate, reviewed exception".
- **(y) The same fields, plus the throwable's class name only, with no message and no stack trace.**
  - For: nothing listener-authored reaches the log.
  - Against: harder to debug. The listener author must log inside their own listener.

## Acceptance
- [ ] `AuditEventListener` is a public interface in `io.github.aindriub.dataprism.audit` with exactly one abstract method, taking an `AuditEvent` and returning `void`. Its Javadoc states: called only after the authoritative write succeeded; it receives the logged event; it cannot veto; its failures are isolated; the destination is the operator's responsibility.
- [ ] A core test with a `FileAuditSink` in a temp directory shows the listener receives an event `equals` to the one `AuditRecorder.record` returned. That event's `eventHash` equals the hash on the last line of the file. The file passes `AuditChainVerifier`.
- [ ] A core test with a sink that throws shows the listener is never invoked and the recorder's sequence is rolled back as before.
- [ ] A core test with a listener that throws `RuntimeException`, and one with a listener that throws `AssertionError`, shows: `record` returns the event normally; the next `record` succeeds with the next sequence; a second listener registered after the throwing one still receives both events; the chain verifies; one log line with the D-163-D content and the code `AUDIT_LISTENER_FAILED` is emitted per failure, asserted with a log capture.
- [ ] A core test with a slow listener (blocking on a latch) proves the behaviour D-163-A chose. For (1) or (2): the documented latency effect, asserted. For (3): `record` returns within 200 ms while the listener is blocked, queue overflow logs `AUDIT_LISTENER_DROPPED`, and after the latch releases the listener sees events in `sequence` order. For (4): the bounded wait and the drop after it.
- [ ] A core test shows a listener that tries to mutate `sourceSystems`, `rejectedArguments` or `fieldDispositions` gets `UnsupportedOperationException`, and the logged line and the projection are unchanged.
- [ ] Mutation proofs in the hand-back: moving the hook before `sink.record` fails the "sink throws" test, and removing the per-listener catch fails the throwing-listener test.
- [ ] The listener sees no field the log does not hold. The only type passed is `AuditEvent`; no `AuditEntry`, request, payload or source data reaches the listener (checkable in the diff of `AuditRecorder` and the dispatcher).
- [ ] `AuditEventListenerWiringTest`, using `ApplicationContextRunner` with `hash-chained`: two listener beans with `@Order` both receive events in order. With no listener bean, the context starts and the recorder behaves as before. The non-`hash-chained` case behaves as D-163-B chose, asserted.
- [ ] The classification follows D-163-C. `AutoConfiguredBeanClassificationTest` passes. The `@Bean` count is 56 for (a), or 57 for (b) to (d).
- [ ] If a property or refusal code was added, it is in docs/configuration.md and 164's metadata check passes without new allow-list entries.
- [ ] docs/audit.md has a section "Audit event listeners". It covers: when listeners are called, what they receive, failure isolation, the D-163-A delivery guarantee (including loss, if (3) or (4)), the D-163-B behaviour, and the sentence that where a listener sends events is the operator's responsibility, including that destination's access control and retention.
- [ ] docs/extending.md has a section "React to audit events with `AuditEventListener`". It has a minimal compiling example and links to the audit.md section.
- [ ] The hand-back contains the one-line CHANGELOG `### Added` entry for task 162 to copy.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- D-0.6-8 D2: application-written events in the hash-chained trail (roadmap only).
- Listeners for checkpoints, retention anchors or verifier results.
- Any change to `AuditEvent`, the record format, the record version, the hash, `TeeAuditSink` or the projection.
- A listener failure counter in metrics or in `auditIntegrity` health. File it as a follow-up if wanted.
- Retry, persistence or exactly-once delivery to listeners.
- `CHANGELOG.md` (task 162 writes the entry from this task's hand-back).

## Owner decision D-163-A: decided 2026-10-08, option 3

Listeners run on one dispatcher thread fed by a bounded queue, with a new capacity property.
- Calls are never slowed by listeners, and order is preserved.
- When the queue is full, events are dropped for listeners only, never from the audit log or the projection. Each drop is logged as `AUDIT_LISTENER_DROPPED`, rate-limited.
- A shutdown drain policy is required.
- Listeners are documented as a best-effort feed, not a reliable copy.

Owner's reason: audit logging must not become a bottleneck for many agents polling at high volume.

Acceptance adds:
- a high-volume concurrency test showing that call latency is unaffected by a slow or hung listener;
- a test that queue overflow drops for listeners only and leaves the chain intact.

## Owner decision D-163-B: decided 2026-10-08, option (ii)

Listeners are called after whichever audit sink is configured has accepted the event. The contract reads "after the configured sink accepted the event". D1's "durably written to the hash-chained log" holds only when `dataprism.audit.sink` is `hash-chained`. The docs state plainly that with `slf4j` the event has only been handed to the logger, which is neither durable nor tamper-evident.

Acceptance:
- listener delivery works with both sinks, with a test for each;
- the docs carry the durability caveat.

## Owner decision D-163-C: decided 2026-10-08, option (d)

The listener dispatcher is its own `@Bean`, classified `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`. It uses the "collected into a list" meaning already used for `LlmResponseValidator`, so applications add listeners but cannot replace the dispatcher. This adds a row in `PrivacyExtensionPoints` and the matching `AutoConfiguredBeanClassificationTest` coverage.

Acceptance:
- a test shows that a competing dispatcher bean refuses startup;
- the `@Bean` inventory is updated by exactly one entry.

## Owner decision D-163-D: decided 2026-10-08, option (y)

A listener failure is logged to the general application log, never to the audit trail, as one WARN line: `AUDIT_LISTENER_FAILED`, with the listener class, event id, sequence and the exception's class name only. It carries no exception message and no stack trace, so text written by listener code never reaches data-prism's logs. Listener authors log their own detail inside the listener. No new conventions exception is needed.

Acceptance:
- a test with a listener throwing an exception whose message contains a synthetic URL with credentials shows that the message text appears nowhere in the captured log output.
