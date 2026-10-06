# 98 — Add tool admission to security: pause, approval gate and per-caller rate limit

**Repo:** `.`
**Depends on:** 95
**Owns:**
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ToolAdmission.java *(new)*
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/AdmissionDecision.java *(new)*
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/OversightPolicy.java *(new)*
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/ToolAdmissionTest.java *(new)*
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/OversightPolicyTest.java *(new)*

## Goal

A single admission check decides whether an authenticated, authorised call
may proceed. It refuses when the deployment is paused globally, when the tool
or the scope is paused, when the caller is over its per-principal rate limit,
or when the tool is configured as high-impact and has no approved, unconsumed
human approval bound to this exact call. Every refusal carries a stable code.
Any error from the backing state refuses the call.

## Context

- `AuthorizationService.java` / `AuthorizationDecision.java` are the shape
  to mirror: an immutable decision with a denial code.
- `AuthenticatedCaller` (`principalId`, `clientId`, `roles`, `purpose`,
  `caseId`).
- Task 95's `OversightState`, `ApprovalStore`, `ApprovalRequest`,
  `CallerRateLimiter`.
- Per-scope budgets already exist (`ScopeBudget`). This task adds a
  per-caller limit and leaves the scope budget untouched.
- Task 101 calls `admit` after scope resolution, with a `bindingFingerprint`
  produced by `ParameterFingerprinter` over the call's arguments.

## Acceptance

- [ ] `OversightPolicy` is a record `(Set<String> approvalRequiredTools,
      OptionalInt callerRequestLimit, Duration callerWindow, Duration
      approvalTtl)`. `OversightPolicy.none()` has no approval-required tools
      and no limit. The constructor rejects a non-positive limit, window or
      TTL.
- [ ] `AdmissionDecision` is a record `(boolean admitted, String code, String
      approvalId, String approverPrincipalId)`. `code` is `null` when
      admitted.
- [ ] `ToolAdmission.admit(AuthenticatedCaller caller, String tool, String
      scopeId, String bindingFingerprint)` evaluates the following in order
      and returns the first refusal:
      1. `DATAPRISM_PAUSED`
      2. `TOOL_PAUSED`
      3. `SCOPE_PAUSED`
      4. `CALLER_RATE_LIMITED`
      5. the approval step, which yields `APPROVAL_REQUIRED` (a new pending
         request is created and its `approvalId` returned) or
         `APPROVAL_PENDING` (an identical pending request exists; its id is
         returned, and no duplicate is created).

      One test per code covers this, plus a test proving the order: a paused
      tool that is also rate-limited returns `TOOL_PAUSED`, and its
      rate-limit counter does not advance.
- [ ] For an approval-required tool with an approved request bound to the
      same `(principalId, scopeId, tool, bindingFingerprint)`, `admit`
      returns admitted with that `approvalId` and `approverPrincipalId`, and
      consumes the approval. A second identical call returns
      `APPROVAL_REQUIRED` again. A call with a different `bindingFingerprint`
      is not admitted by that approval.
- [ ] Any `RuntimeException` from `OversightState`, `ApprovalStore` or
      `CallerRateLimiter` yields `OVERSIGHT_UNAVAILABLE`, never admission.
      One test per collaborator covers this.
- [ ] `ToolAdmission.none()` admits every call. Its javadoc says it is the
      no-oversight-configured behaviour and is equivalent to the
      pre-existing behaviour.
- [ ] No `Capability` constant is added. `Capability.java` and
      `CapabilityKnownSetTest.java` are unchanged.
- [ ] `mvn -pl data-prism-security -am verify` passes.

## Out of scope

- Calling `admit` from the MCP tools. That is task 101.
- Spring properties for the policy. That is task 104.
- The operator endpoints that approve requests or toggle pauses. That is
  task 105.
- Re-identification authorisation. That is task 100, which has its own policy.
