# 192 — Design: securing a multi-instance deployment on open-source Hazelcast

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/cluster-security.md (new)
- docs/plan/tasks/192-design-cluster-security-open-source-hazelcast.md (Outcome section only)

(Location per D-070-12.)

## Goal
1.0 supports multiple instances, secured, on open-source Hazelcast with no
Enterprise features (D-0.4.1-A, D-RM-1). Today member traffic is neither
encrypted nor authenticated, so anyone who reaches the member port can read
and write cluster state, including forging an approval. This task designs what
0.8 builds: the threat model for the cluster, the options available without
Enterprise, a recommendation, and the configuration keys and startup refusal
codes the build will add.

## Context
- `docs/multiple-instances.md:8-90` — what is shared, the warning about
  unencrypted, unauthenticated member traffic, current `dataprism.hazelcast.*` keys.
- `docs/architecture.md:206-218` — boundary 6 (Hazelcast never holds raw
  sensitive values; subject ids appear in the keys of three maps) and
  `HazelcastStoredValueBoundaryTest`.
- `data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/`
  — `HazelcastApprovalStore`, `HazelcastOversightState`,
  `HazelcastCallerRateLimiter`, `HazelcastScopeBudget`,
  `CachingSyntheticValueSource`, `ScopeIdentityIndex`, `ScopeKeys`,
  `PrivacyCluster`, `ClusterMembership`.
- `docs/plan/roadmap.md` 0.8 "Cluster security build", "Soak, including the cluster".
- Sealed links are stateless and never stored in Hazelcast (task 191); this
  design must not depend on them and must say how link keys reach every member.

## Required content
1. Inventory: every distributed structure, what its keys and values hold, and
   the impact of read, write and delete by an attacker on the member network
   (for example forged approval, reset read budget, poisoned identity cache,
   re-identification index disclosure).
2. Options, each with cost, residual risk and operational burden, at least:
   (a) network isolation only, documented and checked at startup;
   (b) transport protection outside Hazelcast (mTLS sidecar or service mesh,
   WireGuard), with what Data Prism can and cannot verify at startup;
   (c) application-level protection: authenticated encryption of values and
   keyed pseudonymous map keys under a cluster key from `SecretKeyProvider`,
   with what it does not stop (deletion, replay of old entries, availability);
   (d) a join secret or member allowlist using only open-source Hazelcast
   features, with an honest statement of what it does not prevent;
   (e) combinations. Name anything that needs Enterprise and exclude it.
3. Recommendation, with the reasoning, left to the owner to decide.
4. Proposed configuration keys (with defaults and validation), startup refusal
   codes, health or metric signals, and how a mixed-version cluster during a
   rolling upgrade behaves.
5. The soak and test obligations for 0.8 (including a test that a member
   without the cluster key cannot forge an approval, if (c) is chosen).
6. "Boundaries" section addressing boundaries 5, 6 and 7; "Open decisions for
   the owner" with options and a recommendation each.

## Acceptance
- [ ] The file exists and contains every numbered item as a heading.
- [ ] Every proposed key and code is in a closing table and does not collide with
      an existing one (`grep -rn` over `docs/configuration.md`,
      `docs/multiple-instances.md`, `docs/tools.md`; commands in the Outcome).
- [ ] No Enterprise-only feature is recommended; each option states the
      Hazelcast version and documentation it relies on (5.7.0 today).
- [ ] **Review gate:** `reviewer` returns APPROVE after checking the document
      against D-0.4.1-A and D-RM-1, boundaries 5, 6 and 7 in
      `docs/architecture.md`, and confirms no product decision is taken in the
      text. The verdict is recorded in the Outcome.

## Out of scope
- Any code or configuration change, and any edit to `docs/multiple-instances.md`
  (that is 0.8 build work).
- Sealed links (task 191), read budget per source (0.8 fan-out work).
