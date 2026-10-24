# 203 — Add case authorisation and a client allowlist to the security module

Renumbered from 188 on 2026-10-19 (id collision with the 0.7.0 plan). Reconciled on 2026-10-19; see `docs/plan/reconciliation-2026-10-19.md`.

**Repo:** `.`
**Release:** 0.8.0 (before the 0.9 API freeze)
**Base branch:** `origin/main` after 0.7.0 integration; refresh the cited paths and call sites against that base before implementation.
**Depends on:** none
**Wave:** 4 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Decided (owner, 2026-10-15):**
- **D-1.0-1.** At the Spring layer, startup refuses without a `CaseAuthorizer` bean unless
  `dataprism.security-policy.case-authorisation=token-trusted` is set explicitly. The default bean is
  `REPLACEABLE`. Task 210 implements the Spring part; this task provides the SPI it wires.
- **D-1.0-5.** Everything here is required by 1.0.0; implementation ships in 0.8.0 under the reconciled roadmap. Breaking changes are accepted. Constructors change in
  place: no deprecated or forwarding overload is left behind, because it would freeze into the 1.0 API.

**Owns:**
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/** *(`ScopeResolver.java`, `AuthorizationService.java`; new `CaseAuthorizer.java`, `CaseAccessRequest.java`, `ClientAllowlist.java`)*
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/** *(`ScopeResolverTest.java`, `AuthorizationServiceTest.java`; new `CaseAuthorizerTest.java`, `ClientAllowlistTest.java`)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/CaseAuthorisationDenialAuditTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ClientAllowlistDenialAuditTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/*.java *(existing files: the `new ScopeResolver(` and `new AuthorizationService(` call sites only)*
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java *(the two constructor calls at :84-85 only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/**/*.java *(the `new ScopeResolver(` and `new AuthorizationService(` call sites only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SecurityWiring.java *(the two constructor calls at :23 and :25 only, behaviour-preserving)*

## Goal
Today the token's `case_id` claim becomes the privacy scope with no check that the principal may
work on that case (`ScopeResolver.java:16-17,72-78,86-88`). The calling client's id is audited but
never checked (`AuthorizationService.java:29-41`). Add a case-authorisation SPI that `ScopeResolver`
consults before any `PrivacyContext` is built, and an optional allowlist of client ids that
`AuthorizationService` checks before capabilities. Both fail closed. Both refusals reach the audit
trail through the existing DENY path, which needs no change.

## Context
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java:33-88 — the constructor and `resolve`; the check goes after `purposeValidator.validate` (:65) and before `new PrivacyContext` (:72)
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/AuthorizationService.java:23-41 — the constructor and `authorize`
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/PurposeValidator.java — the fail-closed, code-constant style to follow
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/AuthenticatedCaller.java:21-66 — `clientId` and `caseId` are already mandatory and non-blank
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java:205-214,308-333 — a denied decision and a `SecurityRefusedException` from `resolve` are both audited as `DENY:<code>` with the caller's `clientId` and `caseId`. `CompareEntitySourcesTool.java:205-214` is identical. No change to `data-prism-mcp/src/main` is needed or allowed.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/refusal/RefusalCodes.java — new codes must match `[A-Z][A-Z0-9_]{0,63}`
- Call sites to update, found with `rg -n 'new (ScopeResolver|AuthorizationService)\(' --glob '!**/target/**'`: the security tests, 11 files under `data-prism-mcp/src/test`, `ExampleApplication.java` and 8 test files under `data-prism-integration-tests/src/test`, and `SecurityWiring.java`. `docs/tools.md:69-70` also shows the old constructors; task 213 owns that file.
- docs/conventions.md, "Privacy rules a diff must satisfy" — "No caller-supplied scope, principal, purpose or case id"

## Acceptance
- [ ] `CaseAuthorizer` is a public `@FunctionalInterface` in `io.github.aindriub.dataprism.security`. It has one abstract method that takes a `CaseAccessRequest` and returns `boolean`. `CaseAccessRequest` is a public record with exactly `principalId`, `clientId`, `caseId`, `purpose` and `roles`, and no `PrivacyContext`, scope id or subject id.
- [ ] `CaseAuthorizer.tokenTrusted()` is a static factory that always returns `true`. Its Javadoc says that it trusts the token's case claim without checking assignment, and that a Spring deployment gets it only through the explicit `token-trusted` setting (D-1.0-1).
- [ ] `ScopeResolver`'s only public constructor takes `(PseudonymisationVersion, Duration, PurposeValidator, CaseAuthorizer)`. The three-argument constructor no longer exists.
- [ ] `resolve` calls the authorizer exactly once per call, after the purpose check and before `new PrivacyContext(...)`. A test with a counting authorizer proves the single call. A test proves the authorizer is not called when the purpose is refused.
- [ ] When the authorizer returns `false`, `resolve` throws `SecurityRefusedException` with code `CASE_NOT_AUTHORISED` and returns no `PrivacySession`.
- [ ] When the authorizer throws any `RuntimeException`, `resolve` throws `SecurityRefusedException` with code `CASE_AUTHORISATION_UNAVAILABLE`. The thrown exception's message does not contain the caught exception's message. A test uses a sentinel message and asserts its absence.
- [ ] `ClientAllowlist` is a public final class with `unrestricted()` and `of(Set<String>)`. `of` rejects an empty set or a blank entry with `IllegalArgumentException`. Matching is exact and case-sensitive: a test shows `Agent-A` is refused when only `agent-a` is listed.
- [ ] `AuthorizationService`'s only public constructor takes `(SecurityPolicy, String, PrivacyScopeType, ClientAllowlist)`. When the allowlist does not permit `caller.clientId()`, `authorize` returns `AuthorizationDecision.denied("CLIENT_NOT_ALLOWED")` before `policy.capabilitiesFor` is called. A test uses a caller whose roles would otherwise be allowed.
- [ ] With `ClientAllowlist.unrestricted()`, every pre-existing `AuthorizationServiceTest` case passes with only its constructor call changed.
- [ ] `CaseAuthorisationDenialAuditTest` drives `GetEntityContextTool` with an authorizer that returns `false`. It asserts that one `AuditEvent` is recorded with `policyDecision` `DENY:CASE_NOT_AUTHORISED` and the caller's `caseId` and `clientId`, that the orchestrator is never invoked, and that the tool result is an error.
- [ ] `ClientAllowlistDenialAuditTest` does the same for a disallowed client and asserts `DENY:CLIENT_NOT_ALLOWED`.
- [ ] Every other caller passes `CaseAuthorizer.tokenTrusted()` and `ClientAllowlist.unrestricted()` explicitly, so behaviour outside the new tests is unchanged. `SecurityWiring` does the same until task 210 replaces it.
- [ ] Mutation proof: with the authorizer call removed from `resolve`, at least one `ScopeResolverTest` case fails; with the allowlist check removed, at least one `AuthorizationServiceTest` case fails. Name both in the close-out.
- [ ] `git diff --stat origin/main` lists no file under `data-prism-mcp/src/main`.
- [ ] `./mvnw -B -pl data-prism-security,data-prism-mcp,data-prism-integration-tests,data-prism-spring-boot-autoconfigure -am verify` passes.

## Out of scope
- Properties, startup refusals, the `dataPrismCaseAuthorizer` bean, `PrivacyExtensionPoints` and `docs/configuration.md`. Task 210 owns all of them.
- A built-in authorizer that reads assignments from a store or the token. The SPI is the deliverable; deployers supply the implementation.
- The operator (re-identification) surface. It has its own audience and authorisation.
- Enforcing which model endpoint a client talks to, or where data is transferred. That is a gateway or egress control; tasks 210 and 214 document it.
- `ClaimNames` and the claims that carry the client and case ids.
- `docs/tools.md` and every other file under `docs/` (task 213).

## Outcome (2026-10-23, 0.8)
A `CaseAuthorizer` SPI (`isAuthorised(CaseAccessRequest)` and `tokenTrusted()`) and a `ClientAllowlist` (`unrestricted()` and `of(Set)`) in `data-prism-security`. Three refusal codes: `CASE_NOT_AUTHORISED`, `CASE_AUTHORISATION_UNAVAILABLE` and `CLIENT_NOT_ALLOWED`. The authorizer is called exactly once per request. A caught authorizer `RuntimeException` is logged at WARN with the exception and no case data, and the client sees only the code. `Error` is not caught.

Tests that fail on the base: the new `CaseAuthorizerTest` and `ClientAllowlistTest` (compile failure).

Mutation proof. Removing `requireCaseAccess` fails `falseRefusesWithCaseNotAuthorised`, `authorizerCalledExactlyOnce`, `throwRefusesWithUnavailableAndLeaksNothing` and `errorFromAuthorizerPropagates`. Forcing the allowlist to pass fails `clientOutsideAllowlistIsDenied`.

Notes for later tasks. `SecurityWiring` hard-wires `tokenTrusted()` and `unrestricted()` until 210, so 0.8.0 must not ship without 210. `docs/tools.md` lines 69-70 still show the old constructors (213). 213 should tell implementers to keep case data out of the messages of the exceptions they throw.

**Review round (PR #163).** `SecurityWiring` now uses a deployer-supplied `CaseAuthorizer` or `ClientAllowlist` bean, and two beans of either type refuse startup. With none it falls back to `tokenTrusted()` and `unrestricted()` and logs one WARN. The Javadoc is corrected: it had referred to a `token-trusted` setting that was never built. 210 remains the 0.8.0 gate.

**Review round 2 (PR #163).** `SecurityWiring` now refuses with `CASE_AUTHORIZER_BYPASSED` or `CLIENT_ALLOWLIST_BYPASSED` when an application replaces `ScopeResolver` or `AuthorizationService` while a deployer `CaseAuthorizer` or `ClientAllowlist` bean exists, because the replacement would silently skip the deployer's check. A new `dataPrismSecurityBypassCheck` `BeanFactoryPostProcessor` does this; it is classified REPLACEABLE and the bean inventory is updated. The unrestricted allowlist fallback now logs one WARN. The 0.8.0 gate on 210 is now a BLOCKING item in PLAN.md's release-cut obligations.

**Review round 3 (PR #163).** The bypass guard is now a `SmartInitializingSingleton` that compares the live `ScopeResolver` and `AuthorizationService` instances, so a same-named replacement and a `FactoryBean` replacement both refuse. The bean-inventory fixtures were updated for the type change. `ScopeResolver` logs only the exception class when the authorizer throws.

**Review round 5 (PR #163; reviewer ids 163-1 to 163-15).** The bypass guard unwraps proxies with `AopProxyUtils`. `ScopeResolver` and `AuthorizationService` are `final` with no interface, so they cannot be proxied at all and the false refusal in 163-3 is unreachable today; the unwrapping is tested directly. The guard bean is `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`. `UnrestrictedFallbackRemovedTest` fails a non-SNAPSHOT 0.8.x or later build while the unrestricted fallback exists; it reads a Maven-filtered `project-version.properties`, and the module pom gains two `<testResources>` entries. For 210: delete that test, the resource and the two pom entries when the fallback is removed.

**Review round 6 (PR #163; reviewer ids 163-1 to 163-19).** The release gate is now `TokenTrustedFallbackRemovedTest`. It gates only the `CaseAuthorizer.tokenTrusted()` fallback, accepts only the expected refusal or a non-fallback bean, and skips only `-SNAPSHOT` (an RC is gated). The unrestricted allowlist default is intended 1.0 design (D-1.0-1, 210), so it is not gated and its WARN stays; this is a coordinator decision of 2026-10-24. The shadowing test asserts its root cause. A proxied deployer `CaseAuthorizer` context test was added; `ScopeResolver` and `AuthorizationService` are final and cannot be proxied.
