# 08 — Make three inert controls actually run

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-security/src/**
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/MutualTlsRestClientsHttpsTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java

## Goal
Three defects found during S8 wave 2 verification share one shape: **a check
exists but nothing forces it to run.** A purpose validator that fails closed
correctly and is never called; an mTLS test that passes on any exception at all,
including a typo in a URL; a reserved-argument test whose assertions a blanket
denial also satisfies. Each looks like a control to a reviewer asking "is there a
check?", and each is worth nothing. This task composes the first into the path
that needs it and makes the other two able to fail.

This is the most dangerous defect shape in this repository, because the privacy
guarantees are enforced by controls rather than by types, and an inert control is
indistinguishable from a live one at reading distance.

## Context
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/PurposeValidator.java:106 —
  `validate` refuses a null, blank or unlisted purpose with `UNKNOWN_PURPOSE`. Correct, and called
  from nowhere outside its own test at `9a07088`
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java:42-72 —
  `resolve` builds the `PrivacyContext` from `caller.purpose()` with nothing between the claim and
  the context. This is the path the purpose has to be validated on
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/MutualTlsRestClientsHttpsTest.java:162-163 —
  `assertThatThrownBy(...).isInstanceOf(RuntimeException.class)`; the positive control in the same
  class is `factoryBuiltClientCompletesTheHandshake` at :137, and the server demands a client
  certificate at :114
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapter.java:69-82 —
  only a 404 is caught, so a transport failure propagates in Spring's own wrapper type
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java:114-125 —
  `ignoresCallerSuppliedContext` asserts only the audited `scopeId` and `rejectedArguments`, both
  of which a denial produces too. Compare :104-110, where a real denial is asserted
- docs/conventions.md#tests — "Leak tests assert on absence, which is easy to write vacuously.
  Every leak test is accompanied by a mutation proving it non-vacuous"
- docs/conventions.md#reviewer-isolation — do not schedule a reviewer against this worktree while
  a mutation proof is in progress
- docs/architecture.md:114-118 — boundary 3, a response that fails validation is not returned, and
  boundary 4, the caller never supplies its own scope, principal, purpose or case id

## Acceptance

Purpose validation, composed:

- [ ] `ScopeResolver` has exactly one public constructor and it takes a `PurposeValidator`:
      `(PseudonymisationVersion, Duration, PurposeValidator)`. No overload keeps the old
      two-argument form, and no constructor supplies a default validator — a defaulted validator is
      the same inert-control shape this task exists to remove.
- [ ] `resolve` calls `purposeValidator.validate(caller.purpose())` after the existing
      `TOKEN_EXPIRED` and denial checks and before any `PrivacyContext` is constructed, and the
      context's purpose is the validator's return value rather than the raw claim. The existing
      refusal codes and their order are unchanged, so every current `ScopeResolverTest` case still
      passes on the code it already asserts.
- [ ] A caller whose purpose is not in the configured list is refused: a test asserts
      `SecurityRefusedException` with code `UNKNOWN_PURPOSE` and that no `PrivacySession` is
      returned. It is a refusal, not a log line — nothing in the diff warns and continues.
- [ ] That test is proved able to fail: with the unrecognised purpose added to the validator's
      allowed set, it fails. The observed failure is quoted in the commit body and the mutation is
      reverted in the same working session.
- [ ] A second test asserts that a caller whose purpose *is* in the list still resolves to a session
      carrying that purpose. A validator that refuses everything is the opposite failure and would
      otherwise satisfy the criterion above.

The mTLS test, made able to fail:

- [ ] `clientWithoutCertificateIsRefused` no longer asserts `isInstanceOf(RuntimeException.class)`.
      It asserts the concrete exception type the run actually produces — take the type from an
      observed failure rather than guessing it — and that the cause chain contains a
      `javax.net.ssl.SSLException`. Assert types, not message text: alert wording varies by JDK and
      platform.
- [ ] The no-certificate client differs from the passing client in exactly one thing: it has no key
      manager. Same server instance, same trust store, and a base URI built by the same expression
      as the positive test — so a wrong URL, a missing file or a store password cannot produce the
      asserted failure. Stated in the test so a reader can check it without running it.
- [ ] The test is proved able to fail: with `setNeedClientAuth(true)` at :114 changed to `false`,
      the fetch succeeds and the test fails. The observed failure is quoted in the commit body and
      the mutation reverted.

The reserved-argument test, made able to distinguish:

- [ ] `ignoresCallerSuppliedContext` supplies all four reserved names — `principalId`, `scopeId`,
      `purpose`, `caseId` — alongside `entityType` and `subjectId`.
- [ ] It asserts the call was served rather than refused: `result.isError()` is not `TRUE`, and the
      content equals the content of the same call made with only `entityType` and `subjectId`.
      Equal content is what separates "the reserved argument was ignored and the call proceeded on
      session-derived context" from "the call was refused"; a denial cannot satisfy it.
- [ ] It asserts exactly one audit event exists, carrying `policyDecision` `ALLOW`, the session's
      own `scopeId`, and `rejectedArguments` containing exactly those four names.
- [ ] The test is proved able to distinguish: run the same assertions against a call that denies
      (subject `nope`, as at :105) and confirm they fail. Quote the failure in the commit body; this
      is a scratch run, not a committed test.
- [ ] The test still drives the tool through the existing private `call(...)` helper at :42-48 and
      adds no second `GetEntityContextTool` construction site, so task 06's change to that
      constructor lands in one place.

Whole build:

- [ ] `mvn -B verify` from the repo root passes. The 285 tests at `9a07088` — 277 `@Test` plus 8
      `@ArchTest` — still pass, plus the tests this task adds.

## Out of scope
- `data-prism-mcp/**` and `data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java`.
  Task 06 owns both, and owns wiring the three-argument `ScopeResolver` into the tool. If task 06
  has already landed a `new ScopeResolver(...)` call site when this task starts, stop and report
  rather than editing it.
- `DataPrismAssembly.java`. It grants `EXPOSE_SOURCE_NAMES` to the example's development caller at
  :105-109; deliberate for the example, and it belongs to whoever next owns that file.
- Adding purpose validation to `AuthorizationService` or `SecurityPolicy` as well. One enforcement
  point, on the path that builds the `PrivacyContext`, is the fix. A second one in the authorisation
  path would change decisions task 06's tests depend on.
- Hunting the other instances of "a test that cannot fail" elsewhere in the suite. Three named ones,
  no sweep. File a successor task if a fourth turns up while working on these.
- Any new capability, role, claim mapping or purpose taxonomy content. The allowed list stays
  configuration.
- Module POMs, surefire configuration, and the TLS store passwords the connectors-rest POM sets.
