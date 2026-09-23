# 73 — Refuse a missing `approved-sink` binding with a code that names the cause

**Repo:** .
**Depends on:** 67, 69
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidatorTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ConfiguredIdentityResolverTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ModelDescriptorsConfigurationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPointsTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/SharedReadBudgetTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/FixtureDevelopmentRefusalTest.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerStartupTest.java
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerSecurityBoundaryTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/StarterStartupFailureTest.java

## Goal
`dataprism.audit.sink=approved-sink` passes `DataPrismProperties.validate()` and,
when the deployment has not supplied an `AuditSink` bean, refuses later at
`dataPrismContractValidator` construction with `MISSING_AUDIT_SINK` — a code that
names the symptom ("no AuditSink bean") rather than the cause ("the value you
configured requires you to supply one"). Make the refusal name the cause and the
property, without moving it and without relaxing it. Task 67 stopped at its `Owns`
boundary rather than editing the ten files below; this is that owed work, and its
reviewer required it filed before 67 could be recorded done.

**This is an inelegance, not a defect, and nothing here is a security fix.**
Nothing unsafe is reachable today: `approved-sink` produces no `AuditSink`,
`dataPrismAuditRecorder` is `@ConditionalOnBean(AuditSink.class)`, and the
pipeline beans require `AuditSink.class`, so startup refuses either way. It is a
worse error at a worse phase, not a fail-open. Do not describe the change as
closing a hole; there is none to close.

## The decision, already made — do not re-open it
`approved-sink` **stays in the accepted set**. It is not a dangling value: it is
the contract for "this deployment supplies its own reviewed `AuditSink` bean",
and the code says so in three places.

- `docs/configuration.md:119` — `sink: approved-sink` is the value in the
  representative *protected deployment* example. Removing it from the accepted
  set would refuse the configuration this project documents for production.
- Five fixtures configure `approved-sink` **and register an `AuditSink` bean**,
  which is the deployment-supplied shape working as intended:
  `PrivacyExtensionPointsTest.java:148`,
  `ModelDescriptorsConfigurationTest.java:201`,
  `ConfiguredIdentityResolverTest.java:138`,
  `StarterStartupFailureTest.java:148`,
  `ServerSecurityBoundaryTest.java:322`.
- `FixtureDevelopmentRefusalTest.java:91` drives `properties.validate()` directly
  with `approved-sink` as part of an otherwise-valid configuration. A refusal at
  `validate()` would make that value unconfigurable at binding time even for a
  deployment that does supply the bean — `validate()` runs before the bean
  factory is queryable and cannot tell the two cases apart.

So the fix is the **code and the message**, at the phase that can actually see
the bean. Concretely:

1. Leave the accepted set in `DataPrismProperties.validate()` as it stands (post-67).
   `UNKNOWN_AUDIT_SINK` still covers anything outside it.
2. Give `DataPrismProperties` a single named constant for the
   deployment-supplied value (`approved-sink`) and a short javadoc stating the
   contract, so the refusal below can name the property value without a second
   string literal drifting from the first.
3. In `DataPrismContractValidator`, replace the generic
   `new DataPrismConfigurationException("MISSING_AUDIT_SINK", "provide an AuditSink bean")`
   with a stable code naming the cause — `AUDIT_SINK_BEAN_REQUIRED` — whose
   message names `dataprism.audit.sink=approved-sink` and the bean type the
   deployment must supply. Same phase, same fail-closed outcome.

**Verify the reachability claim before renaming.** The rename is only correct if,
after 67, `approved-sink` is the *only* accepted value that can reach the
bean-absent branch (`slf4j` and `hash-chained` both produce a bean). Establish
that from `DataPrismAutoConfiguration` as merged. If some other accepted value
can still reach it, keep `MISSING_AUDIT_SINK` for that case and raise
`AUDIT_SINK_BEAN_REQUIRED` only for `approved-sink`.

## Context
- `DataPrismProperties.java:167-170` — `required(audit.sink, "MISSING_AUDIT_SINK", …)`
  then the accepted `Set.of(...)`, `UNKNOWN_AUDIT_SINK` otherwise. Note the same
  code is used for two different failures; keeping `MISSING_AUDIT_SINK` for the
  *absent property* case and splitting the *absent bean* case off is half the point.
- `DataPrismContractValidator.java:38` — the later-phase refusal to change.
- `ServerStartupTest.java:140-146` — `approvedAuditSinkWithoutAReviewedBindingRefusesStartup`,
  the test that pins the current code. It is the behavioural anchor: it must keep
  asserting that startup *refuses*, only with the new code.
- `docs/configuration.md:75` — the `dataprism.audit` row: "Refuse startup for an
  unknown sink, missing required sink reference, or missing writer identity; do
  not downgrade to no-op auditing". The new code must keep that sentence true.
- `docs/conventions.md`, "Errors" — every failure a caller can act on carries a
  stable machine-readable code; CLAUDE.md rule 2, fail closed.

## Acceptance
- [ ] `dataprism.audit.sink=approved-sink` is still accepted by
      `DataPrismProperties.validate()`; a diff that removes it from the accepted
      set fails this criterion.
- [ ] Booting the server with `approved-sink` and no `AuditSink` bean still fails
      startup — `ServerStartupTest.approvedAuditSinkWithoutAReviewedBindingRefusesStartup`
      still asserts a `DataPrismConfigurationException`, updated to the new code
      rather than deleted or weakened to a bare `RuntimeException`.
- [ ] That refusal's code is `AUDIT_SINK_BEAN_REQUIRED` and its message contains
      the literal string `dataprism.audit.sink=approved-sink`. Asserted on the
      exception's code and message, not on a log line.
- [ ] A test in `DataPrismContractValidatorTest` asserts the same refusal
      directly at the validator, and a sibling asserts that with an `AuditSink`
      bean present the validator does not throw — so the first cannot pass
      because construction fails for an unrelated reason.
- [ ] `MISSING_AUDIT_SINK` is still raised for the case it actually describes —
      `dataprism.audit.sink` absent or blank at `validate()` — with a test pinning
      it, so the two failures are distinguishable in a dashboard.
- [ ] All ten owned files compile and the module suites pass: `mvn -q clean verify`
      over `data-prism-spring-boot-autoconfigure`, `data-prism-server` and
      `data-prism-integration-tests`. Baseline for this task is commit `aaea5ae`
      plus whatever 67 and 69 land; re-measure rather than quoting a count forward.
- [ ] Each of the eight pinning fixtures still configures `approved-sink` and
      still exercises the path it was written for. None is deleted, and none is
      made to pass by dropping the `approved-sink` line.
- [ ] `SharedReadBudgetTest` and `FixtureDevelopmentRefusalTest` — the two that
      configure `approved-sink` without registering an `AuditSink` bean — assert
      whatever refusal or success they now get explicitly, rather than relying on
      the context never being started far enough to notice.

## Out of scope
- `FileAuditSink`, `AuditRecorder`, `AuditEventHash` and anything else under
  `data-prism-core/audit` — task 72 owns those.
- The `hash-chained` wiring itself (task 67) and the adapter allow-list (task 69).
  This task starts from both as merged.
- What an MCP client is told when a *running* sink throws — that is task 74, a
  different phase and a different file set.
- `docs/configuration.md` and any other doc: tasks 59 and 62 own the docs. If the
  new code needs documenting, name it in the close-out for the scribe rather than
  editing a doc here.
