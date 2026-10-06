# 100 — Build the `data-prism-reidentification` module: audited, purpose-bound, optional four-eyes

**Repo:** `.`
**Depends on:** 92, 95
**Owns:**
- data-prism-reidentification/** *(new module)*
- pom.xml *(the `<modules>` list and a `dependencyManagement` entry for the new artifact only)*
- data-prism-architecture/pom.xml
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java *(insertions only)*
- docs/reidentification.md *(new)*
- mkdocs.yml *(one nav entry under Reference)*
- docs/architecture.md *(the module-table `reidentification` row only)*

## Goal

This task builds `docs/design-review.md` §B1 as a library: the one controlled
path from a pseudonym back to a subject id. Every request is made under an
authenticated caller, with a mandatory purpose from a re-identification-only
list. Each step writes an audit event recording caller identity and never the
subject id. When four-eyes is on, nothing resolves until a second, distinct
principal approves. The module becomes the only permitted caller of
`ScopeIdentityIndex.subjectFor`, which today has no caller and only bumps a
metric. Architecture rules make that, and "never reachable from MCP",
mechanical.

## Context

- `docs/design-review.md:125-140` (§B1) is the authoritative property list.
- `docs/architecture.md:48` has the planned row (`hazelcast`, `security`).
  `:170-172` is boundary 5, currently prose only. `:303-307` is the
  2026-09-08 decision deferring this surface past V1. **This task must not
  start until the owner has recorded that the deferral is lifted.** See the
  plan return.
- `ScopeIdentityIndex.java:47-56` — `subjectFor(scopeId, namespace,
  synthetic)` returns empty when the index is disabled, the scope has ended
  or the entry has expired.
- Task 95's `ApprovalStore` with `Kind.REIDENTIFICATION`. Task 92's
  `AuditEntry` carries `approvalId` and `approverId`.
- `ArchitectureTest.java` holds the module rules. `ArchitectureCoverageTest`
  requires every module with main code to be a dependency of
  `data-prism-architecture/pom.xml`.
- `docs-site/page-meta.yml:8-10` — new pages carry their own front matter.

## Acceptance

- [ ] A new module `data-prism-reidentification` depends on
      `data-prism-core`, `data-prism-security` and `data-prism-hazelcast`
      only, with no Spring dependency, and is listed in the root `<modules>`.
- [ ] `ReidentificationPolicy` is a record `(Set<String> purposes,
      Map<String, Set<Permission>> roles, boolean fourEyes, Duration
      approvalTtl)`, where `Permission` is `REQUEST` or `APPROVE`. Its
      vocabulary is separate from `Capability`: `Capability.java` is
      unchanged.
- [ ] `ReidentificationService` exposes three methods:
      `request(AuthenticatedCaller, ReidentificationRequest(scopeId,
      namespace, syntheticValue, purpose, caseId))`,
      `approve(AuthenticatedCaller, approvalId)` and
      `collect(AuthenticatedCaller, approvalId)`. Each returns a
      `ReidentificationOutcome` that is one of `RESOLVED(subjectId)`,
      `PENDING_APPROVAL(approvalId)`, `APPROVED` or `REFUSED(code)`.
- [ ] Each of the following refusal codes has a test, and each refusal writes
      a DENY audit event with that code:
      - `REIDENTIFICATION_NOT_PERMITTED`: the caller lacks the needed
        permission.
      - `PURPOSE_REQUIRED`: the purpose is blank.
      - `PURPOSE_NOT_ALLOWED`: the purpose is not in `purposes`.
      - `REIDENTIFICATION_NOT_FOUND`: empty lookup.
      - `SELF_APPROVAL`.
      - `NOT_REQUESTER`: `collect` is called by someone other than the
        requester.
      - `APPROVAL_NOT_FOUND`.
      - `APPROVAL_EXPIRED`.
- [ ] With `fourEyes=false`, `request` resolves immediately. With
      `fourEyes=true`, `request` returns `PENDING_APPROVAL` and does not call
      `subjectFor`. `collect` resolves only after a distinct principal's
      `approve`, and only once.
- [ ] Every audit event uses tool `reidentify`, `subjectPseudonym` = the
      synthetic value, `entityType` = the namespace name, and the request's
      purpose, caseId and scopeId, along with requester principal and client
      ids, `approvalId` and `approverId`. A test asserts that the resolved
      subject id appears in no audit event field.
- [ ] If `AuditRecorder.record` throws, no subject id is returned and the
      outcome is `REFUSED("AUDIT_UNAVAILABLE")`. A test asserts this.
- [ ] `ArchitectureTest` gains two rules. The first: no class in `..mcp..`,
      `..orchestration..` or `..connectors..` depends on
      `..reidentification..`. The second: no class outside
      `..reidentification..` calls `ScopeIdentityIndex.subjectFor`. Both
      pass, and the diff to that file is insertions only.
- [ ] `mvn -pl data-prism-reidentification,data-prism-architecture -am verify`
      passes, including `ArchitectureCoverageTest`.
- [ ] `docs/reidentification.md` (with its own front matter, linked from the
      Reference nav) documents the flow, the codes and four-eyes. It states
      that re-identification is never an MCP tool. It says "supports", never
      "compliant". `mkdocs build --strict` passes.

## Out of scope

- The HTTP surface and its separate port. That is task 105.
- Spring wiring and properties. That is task 104.
- Changing `ScopeIdentityIndex`. Task 99 owns it.
- Resolving a subject id to a person. That is the source systems' job.
- Updating boundary 5's enforcement status in `docs/architecture.md`. That is
  task 106.

## Attempt 1 — failed

Branch `task/100-reidentification` (7cfed28). Tester: PASS (full reactor exit 0, 20/20; mkdocs NOT RUN — not installed). Reviewer: CHANGES.

Owner decisions in force: D1 lifted; D8 four-eyes defaults ON (bound in 104, see below).

- Defect 1 — ArchitectureTest.java:349-354: `callMethod` misses method
  references, so `index::subjectFor` outside `..reidentification..` passes the
  rule (the service itself uses one, ReidentificationService.java:49). Use
  `accessTargetWhere(owner == ScopeIdentityIndex && name == "subjectFor")` (or
  equivalent covering calls and references), and add a negative fixture proving
  a method reference from another package fails the rule.
- Defect 2 — ReidentificationService.java:141-143: `collect(approvalId)`
  consumes by binding (requester, scope, tool, fingerprint), not by id. Two
  approvals A (case C1, bob) and B (case C2, carol) for the same synthetic:
  `collect(alice, B)` consumes A but audits B/carol/C2; a second `collect(B)`
  succeeds. Fix within Owns if possible:
  - before consuming, `find(approvalId)` and require status APPROVED, requester
    == caller, and a binding that matches;
  - after `consumeApproved`, refuse (and audit) unless the consumed request's id
    equals `approvalId`;
  - prevent two live (PENDING or APPROVED, unconsumed) approvals for one
    binding, so consume-by-binding is unambiguous.
  If a correct fix needs a `consumeApproved(id, …)` on the core `ApprovalStore`
  SPI (outside Owns), stop and report rather than editing core.
- Defect 3 — :229-233 with :160-161: the binding fingerprint omits purpose and
  caseId, so `request(P2, C2)` while A is pending for (P1, C1) returns A and
  audits P2/C2 against an approval nobody saw for that purpose. Include purpose
  and caseId in the binding fingerprint.
- Tests for each defect (a test that fails on 7cfed28).
- Also fix (cheap):
  - `approve` (:108-113) and `open` (:166-169): if the audit write fails after
    the store change, undo it (reject/expire the approval) so nothing
    unaudited stays usable.
  - `collect` should re-check `Permission.REQUEST` for the caller.
- Run full reactor `mvn verify`; report the real exit code.
