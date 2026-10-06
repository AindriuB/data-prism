# 95 — Add oversight SPIs to core: pause state, approvals, caller rate limits

**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/** *(new package)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/oversight/** *(new)*

## Goal

Human oversight (EU AI Act Arts. 14 and 26(1)-(2)) needs three kinds of
shared state that nothing holds today: pause flags (global, per tool and per
scope), pending and decided approval requests, and per-caller request
counters. This task defines the three SPIs in `core` with in-memory
implementations. The SPIs are what Hazelcast (task 99), tool admission
(task 98) and re-identification (task 100) all build on.

## Context

- `data-prism-core/.../core/ScopeBudget.java` and `InMemoryScopeBudget.java`
  are the pattern to follow: an SPI in core, an in-memory implementation, and
  a Hazelcast one elsewhere. `InMemoryScopeBudget` documents that its ceiling
  is per instance.
- `docs/conventions.md#tests` — anything time-dependent takes an injected
  `Clock`.
- Consumers that later waves will read, so fix the shape now:
  - task 98 calls `allPaused`, `toolPaused`, `scopePaused`,
    `CallerRateLimiter.tryAcquire` and
    `ApprovalStore.create/findPending/consumeApproved`.
  - task 99 implements all three SPIs over Hazelcast, and must store no
    resolved subject id.
  - task 100 uses `ApprovalStore` with `Kind.REIDENTIFICATION`.
  - task 105 calls `pause*/resume*/snapshot`,
    `ApprovalStore.pending/approve/reject`.

## Acceptance

- [ ] `OversightState` declares the following methods. Any `RuntimeException`
      from an implementation means "unavailable" and callers fail closed.
  ```
  boolean allPaused(); boolean toolPaused(String tool); boolean scopePaused(String scopeId);
  void pauseAll(); void resumeAll(); void pauseTool(String tool); void resumeTool(String tool);
  void pauseScope(String scopeId); void resumeScope(String scopeId);
  OversightSnapshot snapshot();   // record(boolean allPaused, Set<String> pausedTools, Set<String> pausedScopes)
  ```
- [ ] `ApprovalRequest` is a record with these components: `approvalId`,
      `Kind kind` (`TOOL_CALL`, `REIDENTIFICATION`), `requesterPrincipalId`,
      `requesterClientId`, `scopeId`, `tool`, `bindingFingerprint`,
      `namespace`, `syntheticValue`, `purpose`, `caseId`, `Instant createdAt`,
      `Instant expiresAt`, `Status status` (`PENDING`, `APPROVED`,
      `REJECTED`, `CONSUMED`, `EXPIRED`), `approverPrincipalId` and
      `Instant decidedAt`. It has no field that could hold a resolved subject
      id, and its javadoc says so.
- [ ] `ApprovalStore` declares the methods below. `ApprovalRefusedException`
      carries a `code()` from `UNKNOWN_APPROVAL`, `NOT_PENDING`, `EXPIRED` or
      `SELF_APPROVAL`.
  ```
  ApprovalRequest create(ApprovalRequest pending);
  Optional<ApprovalRequest> find(String approvalId);
  Optional<ApprovalRequest> findPending(Kind kind, String requesterPrincipalId, String scopeId, String tool, String bindingFingerprint, Instant now);
  ApprovalRequest approve(String approvalId, String approverPrincipalId, Instant now);   // throws ApprovalRefusedException
  ApprovalRequest reject(String approvalId, String approverPrincipalId, Instant now);    // throws ApprovalRefusedException
  Optional<ApprovalRequest> consumeApproved(Kind kind, String requesterPrincipalId, String scopeId, String tool, String bindingFingerprint, Instant now);
  List<ApprovalRequest> pending(Instant now);
  void forgetScope(String scopeId);
  ```
- [ ] `CallerRateLimiter` declares `boolean tryAcquire(String principalId, int
      limit, Duration window, Instant now)`. A refused acquire is not counted.
- [ ] `InMemoryOversightState`, `InMemoryApprovalStore` and
      `InMemoryCallerRateLimiter` exist and are thread-safe. Unit tests prove
      six behaviours:
  - `approve` by the requester throws `SELF_APPROVAL`.
  - `approve` after `expiresAt` throws `EXPIRED`.
  - `consumeApproved` succeeds exactly once, and a second call returns empty.
  - `consumeApproved` never returns a `PENDING` or `REJECTED` request.
  - The rate limiter admits `limit` calls in a window, refuses the next, and
    admits again in the next window. The test uses only explicit `Instant`s,
    with no sleeps.
  - `forgetScope` removes every approval for that scope.
- [ ] No class in the new package imports anything from `..security..`,
      `..mcp..`, `..orchestration..` or `..hazelcast..`.
      `ArchitectureTest.coreDoesNotDependOnOuterLayers` still passes unchanged.
- [ ] `mvn -pl data-prism-core -am verify` passes.

## Out of scope

- The Hazelcast implementations. That is task 99.
- Admission logic, meaning the order of checks and the refusal codes. That
  is task 98.
- Any new `Metric` or `Capability` constant. `PrivacyMetricsTest` and
  `CapabilityKnownSetTest` pin both sets, and no task in this plan owns them.
