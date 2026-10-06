# 120 — Cap live pending approvals per requester, refused as TOO_MANY_PENDING

**Release:** 0.4.0
**Repo:** `.`
**Depends on:** 101, 119
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/ApprovalStore.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/ApprovalRefusedException.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/oversight/InMemoryApprovalStore.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/oversight/**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastApprovalStore.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ApprovalStoreContractTest.java
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/OversightPolicy.java
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ToolAdmission.java
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/OversightPolicyTest.java
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/ToolAdmissionTest.java
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationPolicy.java
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationService.java
- data-prism-reidentification/src/test/java/io/github/aindriub/dataprism/reidentification/ReidentificationServiceTest.java
- docs/tools.md *(one `TOO_MANY_PENDING` row in task 101's "Admission codes: oversight refusals" table only)*
- docs/reidentification.md *(one `TOO_MANY_PENDING` row in "Refusal codes" only)*

## Goal

Task 100 dropped deduplication of identical re-identification requests, and
each new tool-call fingerprint opens its own approval. One requester can
therefore fill the approvers' queue. This task caps the number of live
pending approvals each requester may hold, for each approval kind. A request
over the cap is refused with `TOO_MANY_PENDING`. The refusal is audited and
creates no approval. The cap is enforced inside the store, atomically, so
two instances in a cluster cannot both admit the request that takes a
requester over the cap.

This is the decision the notes on tasks 104 and 105 asked for. The cap is a
library mechanism, built here. Task 104 binds its configuration. Task 105
maps the refusal to an HTTP status and adds no separate rate limit.

## Context

- `ToolAdmission.java:75-96` `approvalStep`: consume, then `findPending`,
  then `create`. Everything inside `admit` that throws becomes
  `OVERSIGHT_UNAVAILABLE` (`:70-72`).
- `ReidentificationService.java:162-185` `open`: creates a `REIDENTIFICATION`
  approval, then audits `ALLOW:REQUESTED`. `refuse(...)` (`~:205-226`) writes
  `DENY:<code>`.
- `HazelcastApprovalStore.create` locks the bare approval id. Entry keys are
  `ScopeKeys.approval(scope, id)`. A per-requester lock must not be equal to
  either kind of key.
- Task 119's `ApprovalStoreContractTest` runs every case against both stores.
- Task 101 (in flight) creates the admission-codes table in `docs/tools.md`.
  The MCP tools audit any admission refusal code as a DENY with that code, so
  no file under `data-prism-mcp` changes here.

## Acceptance

- [ ] `ApprovalStore` gains `create(ApprovalRequest pending, int
      maxLivePendingPerRequester)`. It counts the requester's requests of the
      same `kind` that are `PENDING` and unexpired at `pending.createdAt()`. If
      that count is already at the maximum, it throws
      `ApprovalRefusedException` with the new code `TOO_MANY_PENDING` and
      stores nothing. A non-positive maximum throws
      `IllegalArgumentException`.
- [ ] Both stores implement the method atomically. In-memory uses the store's
      monitor. Hazelcast takes a per-(kind, requester) lock whose key cannot
      equal an entry key or an approval-id lock key, and a test asserts that
      the key is disjoint from both.
- [ ] `ApprovalStoreContractTest` gains the following cases, run against both
      stores:
      - at the cap, create is refused;
      - an expired, approved, rejected or consumed request does not count;
      - another requester, or the other kind, is unaffected.

      A Hazelcast case runs concurrent creates for one requester from two
      members and asserts that the number of live pending requests never
      exceeds the cap.
- [ ] `OversightPolicy` and `ReidentificationPolicy` each gain
      `maxPendingPerRequester` (positive, otherwise `IllegalArgumentException`),
      default `5`. Every existing constructor and factory keeps compiling and
      uses the default.
- [ ] `ToolAdmission` and `ReidentificationService` create approvals only
      through the bounded `create`, and only in the place where a new request
      is opened. A matching `APPROVAL_PENDING` retry is never answered
      `TOO_MANY_PENDING`.
- [ ] `ToolAdmission.admit` returns `refuse("TOO_MANY_PENDING")`, not
      `OVERSIGHT_UNAVAILABLE`, when the store refuses with that code. Any
      other store exception still gives `OVERSIGHT_UNAVAILABLE`.
      `ToolAdmissionTest` asserts both.
- [ ] `ReidentificationService` returns `Refused("TOO_MANY_PENDING")` and
      writes one `DENY:TOO_MANY_PENDING` audit event that carries no
      `approvalId`. If that audit write fails, the result is
      `AUDIT_UNAVAILABLE`. `ReidentificationServiceTest` asserts both, and
      asserts that no approval was stored.
- [ ] `docs/tools.md` and `docs/reidentification.md` each gain one
      `TOO_MANY_PENDING` row naming the cap and its default.
- [ ] `mvn -pl data-prism-core,data-prism-hazelcast,data-prism-security,data-prism-reidentification,data-prism-mcp -am verify`
      passes.

## Out of scope

- `dataprism.*` properties. Task 104 binds them.
- HTTP status mapping on the operator port. That is task 105.
- Deduplicating identical requests.
- Changing the order of the rate-limit and approval steps (task 98 / 104).
