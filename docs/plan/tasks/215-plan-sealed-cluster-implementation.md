# 215 — Plan the 0.8 sealed-cluster implementation

**Repo:** `.`
**Release:** 0.8 planning, after the 0.7 integration work
**Base branch:** `origin/main` after 0.7 integration, or its designated integration branch.
**Depends on:** 192, 200, 202
**Kind:** planning; no runtime code.
**Owns:**
- docs/plan/tasks/218-*.md through docs/plan/tasks/231-*.md (new briefs only; check unused ids before allocation)
- docs/plan/tasks/213-document-key-rotation-and-1-0-controls.md (replace descriptive dependencies with allocated ids only)
- docs/plan/tasks/215-plan-sealed-cluster-implementation.md (Outcome)

## Goal
Turn the approved `docs/plan/design/cluster-security.md` into reviewable implementation units. Tasks 204, 205 and 212 are superseded alternatives, not completed work. Owner confirmed on 2026-10-19 that the sealed-state design governs, including encrypted re-identification values and separate cluster/name keys.

## Context
- `docs/plan/design/cluster-security.md`: full accepted design, threat model, decisions, test obligations and qualified claim.
- `docs/plan/design/contract-register.md` (200): reconcile public keys/codes before implementation.
- Retired 202 Outcome: CAS approvals, quorum, bounded locks, merge policy, remaining unbounded writes and encrypted approval tombstones.
- `docs/plan/reconciliation-2026-10-19.md`: release allocation and superseded options.

## Acceptance
- [ ] Allocate unused task ids and an acyclic dependency graph. Every brief has exact ownership, test obligations and a base containing its dependencies. Do not use one unbounded “implement cluster security” task.
- [ ] Cover the resolved Hazelcast capability spike and approved serialization/configuration hardening; seal/envelope and key derivation with vectors; map and lock key protection; encrypted identity and re-identification storage; signed/leased fail-closed pause state; sealed approvals and consumed tombstones; budget/rate floors; member cards, mismatch handling and rotation; Spring configuration, migration and multi-member adversarial tests.
- [ ] Carry all accepted residual risks and fail-closed rules from the design. No isolation-only downgrade, Enterprise dependency, shared pseudonymisation/name key, or plaintext re-identification alternative is reintroduced.
- [ ] Explicitly handle coexistence with task 202 and serialize shared files against 210, 208 and 211. Account for blocking writes and lock keys, not just payload encryption.
- [ ] Preserve the design's controlled non-rolling old-format cutover and separate rolling encryption-key procedure. Mixed old/new budget namespaces must not silently expand a scope's allowance.
- [ ] Update 213 with concrete implementation dependencies. Record which new tasks own operator documentation and full integration verification.
- [ ] Review each brief against the design's test obligations and record gaps as unresolved, not silently decided. No design protection is claimed implemented by this planning task.

## Out of scope
Implementation, benchmarking, publication, and changing accepted product/security decisions.
