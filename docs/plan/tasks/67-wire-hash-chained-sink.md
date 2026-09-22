# 67 — Give `dataprism.audit.sink: hash-chained` a bean to be

**Repo:** .
**Depends on:** 64, 68
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfigurationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/HashChainedAuditSinkTest.java (new)

## Goal
`DataPrismProperties` validates and accepts `approved-sink` and `hash-chained`
as audit sink values with no bean behind either, so an operator configuring
`hash-chained` passes config validation and only trips `MISSING_AUDIT_SINK` at a
later startup phase — fail-closed by accident, not by design. Wire
`hash-chained` to task 64's `FileAuditSink`, and make every remaining accepted
value either resolve to a bean or refuse where it is stated.

## Context
- `DataPrismProperties.java:167-170` — `Set.of("approved-sink", "slf4j",
  "hash-chained")` accepted, `UNKNOWN_AUDIT_SINK` otherwise.
- `DataPrismContractValidator.java:38` — `MISSING_AUDIT_SINK`, the later phase
  that catches it today.
- `DataPrismAutoConfiguration.java:135-140` — `dataPrismPropertiesValidated`, where
  `validate()` and `validateIntegrations` run.
- `PrivacyExtensionPoints.java:44-77` — the checked-in classification table; task
  68 widens the sweep that enforces it, which is why this task waits on 68.
- `docs/plan/tasks/64-file-audit-sink.md` — the sink being wired and its
  constructor contract.

## Acceptance
- [ ] `dataprism.audit.sink: hash-chained` produces a `FileAuditSink` bean bound to
      a configured file path property, asserted with an `ApplicationContextRunner`.
- [ ] The file path property is required when `hash-chained` is selected: omitting
      it refuses at the same phase as the rest of `DataPrismProperties.validate()`,
      with a stable code and a message naming the property, not a
      `NullPointerException` later.
- [ ] `dataprism.audit.sink: slf4j` still produces `Slf4jAuditSink` and no
      `FileAuditSink`, and the two are never both registered.
- [ ] `approved-sink` either resolves to a bean or is refused at
      `DataPrismProperties.validate()` with a code and message saying it is not
      implemented here and an `AuditSink` bean must be supplied — no value survives
      config validation with nothing behind it.
- [ ] `PrivacyExtensionPoints` gains a row for the new `@Bean` method, and task
      68's widened sweep passes with it.
- [ ] A test asserts the audit file written by a context configured with
      `hash-chained` is readable by task 66's verifier and reports an intact chain.
- [ ] `mvn -pl data-prism-spring-boot-autoconfigure -am test` passes.

## Out of scope
- `DataPrismContractValidator.java` and its test (task 69 owns them).
- `AutoConfiguredBeanClassificationTest.java` (task 68 owns it).
- Rotation, retention or a second sink type.
- Documenting the property (task 62 for `docs/audit.md`; `docs/configuration.md`
  belongs to task 59).
