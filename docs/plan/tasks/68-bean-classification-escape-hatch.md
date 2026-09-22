# 68 — Close the bean-classification escape hatch

**Repo:** .
**Depends on:** none
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/AutoConfiguredBeanClassificationTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPointsTest.java

## Goal
Task 53 placed a new `IdentityResolver` bean on a nested `@Import`ed static
configuration class, which keeps it outside the classification sweep. The
reviewer ruled this a guardrail evasion, acceptable then only because task 53's
`Owns` forbade editing `PrivacyExtensionPoints.java`. Classify the bean and widen
the sweep, so the same placement cannot dodge classification again.

## Context
- `DataPrismAutoConfiguration.java:108-134` — `IdentityResolverSelection`, the
  nested `@Configuration` holding `dataPrismPassThroughIdentityResolver`, and the
  javadoc at `:117-126` that overstates why the nesting was necessary: it claims
  `@ConditionalOnBean` visibility required it, but a `@Bean` method declared above
  its dependants would have been visible too.
- `AutoConfiguredBeanClassificationTest.java:24-42` — the sweep, over
  `DataPrismAutoConfiguration.class.getDeclaredMethods()` only, plus the
  no-stale-entries test that pins the registry to exactly that set.
- `PrivacyExtensionPoints.java:31-77` — the classification table, its `Guard`
  invariant, and the "add a row in the same change" contract.
- `docs/plan/PLAN.md`, "Small open items, unscheduled" (2026-09-21, 53's review)
   — the finding this closes, and that both halves are required.

## Acceptance
- [ ] `PrivacyExtensionPoints` carries a row for `dataPrismPassThroughIdentityResolver`
      classified `REPLACEABLE` / `Guard.NONE`.
- [ ] The sweep collects `@Bean` methods from `DataPrismAutoConfiguration` **and**
      from every nested class it declares and every class it `@Import`s, so
      `dataPrismPassThroughIdentityResolver` appears in `declaredBeanMethods()`.
- [ ] The widened sweep is proven non-vacuous: a test-local configuration class
      carrying an unclassified `@Bean` method, nested or imported the same way, is
      reported by the sweep's collection step. (Prove it against a fixture the test
      owns, not by mutating `DataPrismAutoConfiguration`.)
- [ ] `the_classification_registry_has_no_stale_entries` still passes — the
      registry matches the widened set exactly, with no entry for a bean that no
      longer exists.
- [ ] The `PRIVACY_CRITICAL` structural checks (`@ConditionalOnMissingBean`
      forbidden, `@Primary` or competing-bean refusal required) apply to the
      nested/imported methods too, not only the top-level ones.
- [ ] The javadoc at `DataPrismAutoConfiguration.java:117-126` is corrected: it
      states the real reason for the placement (`@ConditionalOnMissingBean` against
      an application-supplied `IdentityResolver`, and bean-definition ordering) and
      no longer claims `@ConditionalOnBean` visibility made nesting necessary.
- [ ] `mvn -pl data-prism-spring-boot-autoconfigure -am test` passes.

## Out of scope
- `DataPrismProperties.java` and `DataPrismContractValidator.java` (tasks 67 and 69).
- Moving or reshaping the `IdentityResolver` bean itself — task 53's behaviour is
  merged and verified; this task classifies it, it does not relocate it.
- Adding any new `@Bean`.
