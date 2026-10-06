# 119 — Make InMemoryApprovalStore match HazelcastApprovalStore under one contract test

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/InMemoryApprovalStore.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/ApprovalStore.java *(Javadoc only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/oversight/**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java *(the comment at `:55` only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalStoreContractTest.java *(new)*

## Goal

The two `ApprovalStore` implementations enforce different rules. The
Hazelcast store refuses to create a request that is not `PENDING`, refuses a
duplicate id, and refuses a null approver. The in-memory store accepts all
three. The in-memory store is what a single-instance deployment and
`ToolAdmission.none()` use, so it must be at least as strict. This task brings
it in line and pins the shared behaviour in one contract test that runs
against both stores.

## Context

- `HazelcastApprovalStore.java:44-75` `create`: throws
  `IllegalArgumentException` for a non-`PENDING` request and for a duplicate
  id. `:85-95` `approve`: `Objects.requireNonNull(approver, "approver")`.
- `InMemoryApprovalStore.java:19-23` `create` overwrites silently. `:37-44`
  `approve` accepts null.
- `HazelcastApprovalStore.java:55` comment claims the bare-id lock key "is
  never an entry key". That holds only while ids contain no NUL, which is the
  `ScopeKeys` separator. The comment must say so. Code behaviour is
  unchanged.
- There is no test-jar in this build. `data-prism-hazelcast` depends on
  `data-prism-core`, so the contract test lives in the hazelcast module and
  instantiates both stores. Follow `HazelcastOversightTest.java:55-70` for
  starting an embedded member.
- `findPending` is still called by `ToolAdmission.java:85`. Keep it.

## Acceptance

- [ ] `InMemoryApprovalStore.create` throws `IllegalArgumentException` for a
      request whose status is not `PENDING`, and for an id that is already
      stored in any status. Neither throw changes the stored state.
- [ ] `InMemoryApprovalStore.approve` throws `NullPointerException` for a null
      approver before any state changes.
- [ ] `ApprovalStoreContractTest` is parameterised over an
      `InMemoryApprovalStore` and a `HazelcastApprovalStore` on an embedded
      member. Each case runs against both stores:
      - create, find and pending listing;
      - non-`PENDING` create refused;
      - duplicate id refused, including a duplicate in a different scope;
      - null approver refused;
      - `SELF_APPROVAL`, `UNKNOWN_APPROVAL`, `NOT_PENDING` and `EXPIRED`
        codes;
      - `reject` on an unknown id;
      - `consumeApproved` succeeds once and then returns empty;
      - `forgetScope` removes only that scope.
- [ ] Every existing test in `data-prism-core`, `data-prism-hazelcast`,
      `data-prism-security` and `data-prism-reidentification` passes. Any
      test that relied on the old lenient behaviour is fixed in `Owns`. If
      such a test lies outside `Owns`, stop and report.
- [ ] The `HazelcastApprovalStore.java:55` comment states the NUL condition.
      `git diff` on that file shows comment lines only.
- [ ] `mvn -pl data-prism-core,data-prism-hazelcast,data-prism-security,data-prism-reidentification -am verify`
      passes.

## Out of scope

- The per-requester pending cap. That is task 120, which extends this
  contract test.
- A revoke or expire path for APPROVED requests (separate follow-up).
- Renaming tests in `HazelcastOversightTest`.
