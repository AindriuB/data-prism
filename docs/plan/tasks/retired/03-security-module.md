# 03 — Build the security module: caller, authorisation, purpose, scope resolution

**Repo:** `.`
**Depends on:** 01
**Owns:**
- data-prism-security/src/**

## Goal
Turn a validated token's claims into an authenticated caller, decide what that
caller may do from capabilities configured per role, validate its stated purpose
against a configured list, and resolve the pair into a `PrivacyContext` plus an
`InvestigationContext`. This is where "scope isolation is vacuous because there is
only ever one scope" stops being true: the scope id derives from the caller's
`case_id` claim, so two cases are two different sets of pseudonyms.

No Spring Security type appears in this module. It takes a claims map and returns
records, so it is testable without a servlet container and reusable by the
re-identification surface later.

## Context
- docs/pack.md:1665-1691 — §50 `AuthorizationService` / `AuthorizationDecision`, and §51's field list
- docs/pack.md:1693-1717 — §51: never trust the caller for `principalId`, `caseId` or authorisation
- docs/architecture.md — boundary 4, and the `security` row: depends on `core` only
- docs/plan/PLAN.md:51-59 — the four questions, all settled: JWKS-based validation with no IdP
  integration; `case_id` as a claim; purpose as a claim validated against a configurable list, failing
  closed on an unknown value; stdio survives as a single-principal development mode
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/policy/PrivacyProfiles.java — the
  house style for a hand-parsed YAML configuration object
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PseudonymisationVersion.java:20-45 —
  `withKey` and `withVocabulary`; a scope pins both at creation
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PrivacyScopeType.java

## Acceptance
- [ ] `AuthenticatedCaller` is a record of `principalId`, `clientId`, `Set<String> roles`, `purpose`,
      `caseId` and `expiresAt`, built by `AuthenticatedCaller.fromClaims(Map<String,Object>, ClaimNames)`.
      `ClaimNames` configures which claim carries each, defaulting to `sub`, `azp`/`client_id`, `roles`,
      `purpose` and `case_id`. The record retains neither the claims map nor the token string.
- [ ] A claims map missing any of subject, client, purpose or `case_id` produces a refusal carrying a
      stable code (`MISSING_PRINCIPAL`, `MISSING_CLIENT`, `MISSING_PURPOSE`, `MISSING_CASE`), not a
      caller with a defaulted field. One test per code.
- [ ] `SecurityPolicy.fromYaml(InputStream)` loads the allowed purposes and a role-to-capability map;
      an unknown key, an empty purpose list or a capability outside `Capability.KNOWN` is a startup
      failure naming the offending entry.
- [ ] `PurposeValidator` accepts only a purpose in the configured list, case-sensitively, and refuses
      null, blank and unknown with code `UNKNOWN_PURPOSE`. A test asserts that adding a purpose to
      configuration is the only way to make a previously refused purpose pass.
- [ ] `AuthorizationService.authorize(AuthenticatedCaller, ToolInvocation)` returns
      `AuthorizationDecision(boolean allowed, String privacyProfile, PrivacyScopeType scopeType,
      Set<String> capabilities, String denialCode)`. Capabilities are exactly the union of the mapped
      capabilities of the caller's known roles; an unmapped role contributes nothing and does not fail
      the call, and a caller with no mapped role is denied with code `NO_CAPABILITIES`.
- [ ] A caller lacking the capability for the invoked tool is denied with code `TOOL_NOT_PERMITTED`,
      and the decision is `allowed = false` — there is no path returning a decision that is denied but
      carries capabilities a caller could use.
- [ ] `ScopeResolver.resolve(AuthenticatedCaller, AuthorizationDecision, Clock)` returns a
      `PrivacySession(PrivacyContext, InvestigationContext)` in which: `scopeId` is a pure function of
      the `case_id` claim; `keyId` and `vocabularyId` on the `PseudonymisationVersion` come from
      resolver configuration and are pinned at creation; `expiresAt` is the earlier of the token expiry
      and the configured maximum scope lifetime; `purpose` is the validated purpose; and
      `InvestigationContext` carries the caller's principal, client, case and capabilities.
- [ ] Tests prove the isolation property directly: two callers with different `case_id` claims resolve
      to different `scopeId`s, and the same `case_id` in two separate sessions resolves to the same
      `scopeId` — with no shared state between the two resolver instances.
- [ ] A caller whose token has already expired against the injected `Clock` is refused by the resolver;
      no test uses `Instant.now()` or a sleep.
- [ ] `ReservedArguments` exposes the immutable set of tool argument names a caller may never supply —
      at least `principalId`, `scopeId`, `scopeType`, `purpose`, `caseId`, `profile`, `capabilities` —
      and `Set<String> rejected(Set<String> argumentNames)` returning those of a call's argument names
      that are reserved. It never returns or logs an argument *value*.
- [ ] `mvn -B verify` from the repo root passes; the existing 212 tests at `7db0491` all still pass.

## Follow-ups carried from wave 1

Two findings from reviewing tasks 01 and 02. Neither blocked its task's merge;
both are real and are acceptance criteria here because this task already touches
capabilities, and because the second is a recurring pattern worth stopping.

- [ ] `Capability.KNOWN` in `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/Capability.java`
      is not pinned by any test, so a fifth capability added later without updating `KNOWN` fails
      silently. `Metric` has exactly such a test in `PrivacyMetricsTest`; mirror it for `Capability`.
- [ ] In `data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/MutualTlsRestClientsHttpsTest.java`
      the no-client-certificate direction asserts `isInstanceOf(RuntimeException.class)`, which would
      also pass if the server were simply unreachable, so it proves less than it appears to. Assert on
      an SSL/handshake cause instead. This is the fourth time this project has shipped a test that
      cannot fail for the reason it claims — see `docs/conventions.md`'s requirement for non-vacuous
      assertions.

## Out of scope
- JWT signature verification, JWKS fetching and any `spring-security-oauth2-resource-server`
  dependency. Validation happens in the app's filter chain (task 07); this module starts from claims
  that have already been verified, and its javadoc says so.
- Any MCP or transport type. The extractor that puts a caller into the transport context is task 06.
- Scope lifecycle as a user-facing surface: no create, extend or select operation. Revocation already
  exists as `ScopeIdentityIndex.endScope()`.
- Re-identification, and any dependency on `data-prism-hazelcast`.
