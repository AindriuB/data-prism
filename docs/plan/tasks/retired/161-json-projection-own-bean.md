# 161 — Make the JSON audit projection its own classified bean and `TeeAuditSink` `Closeable`

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2) after 159 has merged into it.
**Depends on:** 159, 160
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/sink/TeeAuditSink.java (location after 157; adjust to where 157 put it)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/sink/TeeAuditSinkTest.java (same)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/AuditSinkSelection.java (declares `dataPrismHashChainedAuditSink`; owns `JsonProjection`), `AuditWiring.java` if the new bean belongs there, and `JsonProjection.java`
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/ (the checked-in inventory lists from 159)
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java (insertions only: one row)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanClassificationTest.java (insertions only, if it pins a count)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanInventoryTest.java and its checked-in lists (from 159)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/{HashChainedAuditSinkTest,AuditOutputConfigurationTest}.java

## Goal
PLAN follow-up (t), folded into 0.6.0 because 159 has just isolated the audit
wiring it touches. Today the JSON projection is built inside
`dataPrismHashChainedAuditSink` and closed through `registerDisposableBean`.
Make it its own `@Bean` with a `PrivacyExtensionPoints` classification, and
make `TeeAuditSink` `Closeable` so closing the tee closes the projection.
This is a behaviour change (one more bean), deliberately kept out of the
pure-move tasks.

## Context
- DataPrismAutoConfiguration.java:321 at 438ef802 — `JsonProjection implements DisposableBean`; after 159 it is the package-private `JsonProjection` class, used by `AuditSinkSelection`.
- PrivacyExtensionPoints.java:1-40 — the classification contract; a `PRIVACY_CRITICAL` bean needs a guard and must not carry `@ConditionalOnMissingBean`.
- docs/plan/HISTORY.md, grep `0.5.0 wave 3, part 1` and the 148 cost note — task 148 was blocked once because a new `@Bean` needed a `PrivacyExtensionPoints` row outside its Owns. That row is in Owns here.

## Acceptance
- [ ] The projection is a `@Bean` with a stable name, present only when `dataprism.audit.output.json-directory` is set, and the hash-chained sink receives it by injection. `registerDisposableBean` no longer appears in `data-prism-spring-boot-autoconfigure/src/main`.
- [ ] `PrivacyExtensionPoints` has one new row for it, classified per D-0.6-8 (planner's proposal: `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`, because a replaced projection would write audit-derived JSON the framework no longer controls). The `@Bean` sweep finds 51 methods (50 after 159, plus this one).
- [ ] `TeeAuditSink implements Closeable`; `close()` closes the projection exactly once and is idempotent. A unit test calls `close()` twice and asserts one close of the projection.
- [ ] A context test shows that shutting the context down closes the projection once and the native sink once, in that order or the order the hand-back states and justifies.
- [ ] The poisoning behaviour 160 added is unchanged: `TeeAuditSinkTest`'s `Error` case passes unedited.
- [ ] `AutoConfiguredBeanInventoryTest`'s checked-in lists change only by the one new bean, in the scenarios with a json directory.
- [ ] Audit output is unchanged: `AuditOutputConfigurationTest` and the record format tests pass with no assertion edits.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- (s), recording a projection purge failure in `auditIntegrity` health.
- Any change to the native sink, checkpoint or retention wiring.
- Reclassifying any existing bean.

## Owner decision D-0.6-8: decided 2026-10-08 (A + D1)

- **A.** The JSON audit projection bean is PRIVACY_CRITICAL with COMPETING_BEAN_REFUSAL. It cannot be replaced by a user bean.
- **D1, new and to be planned as its own task.** It adds a read-only audit event listener SPI, so teams can react to or forward audit events without replacing the projection:
  - It is invoked only after the event is durably written to the authoritative hash-chained log.
  - It receives the same filtered `AuditEvent` that is in the log, never more.
  - It cannot modify or veto the event.
  - Failures are isolated: they are logged and do not affect the call, the chain or the projection.
  - The docs state that the listener's destination is the operator's responsibility.
- **D2 goes on the roadmap only, not 0.6.0.** It would let application code write its own events into the hash-chained trail, which needs its own design: a custom event schema, field limits so personal data cannot be logged raw, and verifier support.

## Outcome
- `JsonProjection` is its own `PRIVACY_CRITICAL` / `COMPETING_BEAN_REFUSAL` bean, `dataPrismJsonAuditProjection` (package-private final). It is deliberately not an `AuditSink`: implementing `AuditSink` made `AuditRecorder` injection ambiguous.
- `TeeAuditSink` is `Closeable`: closes the projection, then the primary; idempotent; the primary is closed even if the projection close throws. The record/close race is documented as fail-closed.
- The sink bean resolves the projection BEFORE opening the authoritative file, and closes the primary if the tee construction throws.
- New refusals: `AUDIT_JSON_PROJECTION_WITHOUT_BUILT_IN_SINK` (static BFPP `dataPrismJsonAuditProjectionPreflight`, REPLACEABLE like the other preflights, plus an in-bean guard requiring the built-in sink definition) and `AUDIT_JSON_PROJECTION_MISSING` (json-directory set but no projection resolves, for example with bean overriding on). No message contains a path.
- DELIBERATE AMENDMENT to the task text: 52 `@Bean` methods (not 51), two `PrivacyExtensionPoints` rows, two inventory-list additions (projection and preflight).
- Review: CHANGES (primary sink `FileChannel` leaked when the projection was refused; in-bean sink check missed FactoryBean/lazy sinks; `COMPETING_BEAN_REFUSAL` not real with overriding on), fixed, then APPROVE. Tester PASS at 044b5d46 (full reactor JDK 21, 1681 tests, 0 failed, HEAD unchanged; release-profile package green).
