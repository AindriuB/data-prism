# 192 — Design: securing a multi-instance deployment on open-source Hazelcast

**Repo:** .
**Branch:** from `main` at the commit that carries this task file
**Depends on:** none
**Kind:** design, docs only. No code.
**Owns:**
- docs/plan/design/cluster-security.md (new)
- docs/plan/tasks/192-design-cluster-security-open-source-hazelcast.md (Outcome section only)

(Location decided by D-070-12 (a): `docs/plan/design/`, internal.)

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

## Outcome

Written 2026-10-09 on `task/192-cluster-design`. The design is
`docs/plan/design/cluster-security.md` (sections 0 to 6; 0 is the threat model,
1 to 6 are the six required items as headings).

Acceptance:

- [x] The file exists and contains every numbered item as a heading (1 Inventory, 2 Options, 3 Recommendation, 4 Proposed configuration/codes/signals/mixed versions, 5 Soak and test obligations, 6 Boundaries and open decisions).
- [x] Closing table is section 4.9 (51 names after revision 2: 22 keys, 14 codes, 13 metrics, 1 audit event, 1 health contributor). Collision check, run from the worktree root:
  - Names extracted from the 4.9 table and each searched with `grep -rIn -F -- <name> docs/configuration.md docs/multiple-instances.md docs/tools.md data-prism-core data-prism-hazelcast data-prism-server data-prism-spring-boot-autoconfigure data-prism-mcp data-prism-security`.
  - Result: no hits for any key, code, metric or audit event. The only hit was the first draft's health name `cluster` (a common word that appears in unrelated prose), renamed `clusterSecurity`, matching the camelCase of the existing `auditIntegrity` contributor.
  - The build must repeat the search before adding anything: other 0.8 work adds codes.
- [x] No Enterprise-only feature is recommended. Section 0.4 and 2.5 list what is excluded and the evidence (5.7.0 jar strings and the 5.7 editions page table); section 2.0 lists the documentation each option relies on. Hazelcast version is 5.7.0 throughout.
- [ ] **Review gate: pending.** The implementer cannot run the `reviewer` agent. The caller runs `reviewer` against D-0.4.1-A, D-RM-1, boundaries 5, 6 and 7 in `docs/architecture.md`, and checks that no product decision is taken in the text (every choice is an OD-n in 6.2 with a recommendation). Verdict: _to be recorded here_.

Findings the owner should know before reading the design:

- OSS Hazelcast 5.7.0 has no inbound member allowlist that was found. `NetworkConfig.interfaces` selects the local bind address, and `trusted-interfaces` is multicast-only. Marked **to verify in the 0.8 spike**.
- The cluster name cannot serve as a join secret: it is sent in the clear and (to verify on 5.7.0) is part of every Hazelcast log line.
- Application-level sealing cannot stop deletion, replay or availability attacks. The design bounds budgets and rate limits with a per-instance floor, converts pause deletion into a fail-closed denial of service with a signed, leased pause document, and leaves the rest to transport protection (option (b)), which Data Prism cannot verify.
- Approval forging, identity poisoning and re-identification index disclosure are closed by sealing. Re-identification index entries lost to deletion are lost for good.
- Deserialisation of Java classes over the member protocol is a code-execution surface that no other option addresses. The OSS `java-serialization-filter` is proposed.

Existing defects found while reading the code, proposed as **separate fix tasks** (not fixed here; they stand without the design):

1. **Approval can be consumed twice under a split brain.** Exactly-once consumption relies on `IMap.lock`, an AP lock with no consensus: `HazelcastApprovalStore.java:151` (`consumeApproved`), `:193` (`decide`), `:59` and `:90` (`create`). Fix options include a compare-and-set on the stored status instead of, or as well as, the lock.
2. **Locks have no timeout, so a held lock hangs a request thread.** `HazelcastApprovalStore.java:59`, `:90`, `:151`, `:193`; `HazelcastScopeBudget.java:44`; `HazelcastCallerRateLimiter.java:32`. The budget's fail-closed `catch` (`:45-47`, `:58-60`) only handles thrown exceptions. Fix: `tryLock` with a bounded wait and a lease, failing closed on timeout.
3. **Jet and phone-home are not forced off for a supplied config or instance.** `PrivacyCluster.using` (`:92-102`) forces and checks neither; `PrivacyCluster.configure` (`:134-141`) forces phone-home but not Jet; `ClusterMembership.java:91-92` does both but only on the `toConfig` path.
4. **Pinned Hazelcast is 5.7.0; the 5.7 documentation lists 5.7.2.** `pom.xml:74` (`hazelcast.version`). The open-source line gets no emergency CVE patches (editions page), so bump to the latest 5.7.x patch and re-run the cluster tests.

Revision 2 (after review): rotation (4.6) is now a phased procedure with a separate name key; the pause document is compare-and-set with merge and rewrite rules; the 1.0 claim is OD-13; per-map subkeys are consistent between 2.3.1 and 6.1; 1.7 and 4.5 corrected against the code. Open decisions are OD-1 to OD-16. The reviewer verdict is still to be recorded in the review-gate item above.
