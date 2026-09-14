# 22 — Make the policy resolver and leak validator impossible to replace

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPointsTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanClassificationTest.java *(new)*

## Goal

`PrivacyPolicyResolver` and `LlmResponseValidator` are `@ConditionalOnMissingBean`,
so an application bean silently replaces them: a resolver returning `PASS_THROUGH`
disables scrubbing and a no-op validator disables leak detection, both without a
startup refusal. `SecretKeyProvider` is deliberately `@Primary` for exactly this
reason. Make the privacy-critical beans consistent with it and fail closed, and
leave a mechanical guard so the next bean added cannot re-open the hole.

## Context

- `DataPrismAutoConfiguration.java:125-129` — `SecretKeyProvider`, `@Primary`:
  the pattern the other two should follow.
- `DataPrismAutoConfiguration.java:131-137` — `PrivacyPolicyResolver`,
  `@ConditionalOnMissingBean`.
- `DataPrismAutoConfiguration.java:142` — `LlmResponseValidator`, same.
- `DataPrismAutoConfiguration.java:160-170` — the orchestrator receives
  `List.of(validator)`, a single validator, which is why an override removes
  leak detection entirely rather than adding to it.
- `DataPrismAutoConfigurationTest.java:72-78` —
  `application_secret_provider_cannot_override_the_configured_reference`, the
  shape the new tests should follow. That file belongs to task 21; leave it
  unchanged.
- `docs/conventions.md:110-113` — a leak test ships with the mutation that
  proves it non-vacuous.
- Baseline: `main` at `99b419b`, 377 tests, 0 failures.

## Acceptance

- [ ] An application-supplied `PrivacyPolicyResolver` bean causes startup to
      refuse with stable code `FORBIDDEN_PRIVACY_OVERRIDE`; the framework's
      profile-backed resolver is always the one the scrubber receives.
- [ ] The built-in `RawValueLeakValidator` always runs. An application-supplied
      `LlmResponseValidator` is additive: a test registers one, asserts both it
      and `RawValueLeakValidator` are in the orchestrator's validator list, and
      asserts a raw source value still fails the leak check.
- [ ] Non-vacuity, stated as a mutation: a test configuration whose
      `PrivacyPolicyResolver` returns `PASS_THROUGH` for every field is proven to
      start and emit an unscrubbed value before the change, and to refuse
      startup after it. Report both observations in the close-out.
- [ ] A sweep test enumerates every `@Bean` method declared in
      `DataPrismAutoConfiguration` by reflection and asserts each one appears in
      a checked-in classification as either `REPLACEABLE` or `PRIVACY_CRITICAL`.
      Adding a `@Bean` without classifying it fails the test.
- [ ] The same sweep asserts no `PRIVACY_CRITICAL` bean method carries
      `@ConditionalOnMissingBean`, and that each is `@Primary` or guarded by the
      competing-bean refusal. `SecretKeyProvider`, `PrivacyPolicyResolver` and
      `LlmResponseValidator` are all classified `PRIVACY_CRITICAL`.
- [ ] `DataPrismAutoConfigurationTest.java` is unchanged by this task.
- [ ] `mvn -B verify` is green; the new test total is reported against 377.

## Out of scope

- `DataPrismProperties`, `DataPrismContractValidator` and the fixture-development
  refusal — task 21 owns those files.
- Wiring the Hazelcast scope budget — task 25, which will add a bean and must
  therefore insert its classification row in this task's sweep list.
- Widening the privacy engine with new extension SPIs.

## Note for later tasks

The classification list this task creates is a shared exhaustiveness guard: any
later task that adds a `@Bean` to `DataPrismAutoConfiguration` must own
`AutoConfiguredBeanClassificationTest.java` for insertions, or it will stop
mid-flight for the same permission.
