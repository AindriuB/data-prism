# 218 — Align the cluster design and the contract register with the 0.8 decisions

**Repo:** `.`
**Release:** 0.8.0
**Kind:** planning documents only; no code.
**Base branch:** `origin/main` (or the 0.8 integration branch).
**Depends on:** none
**Decisions:** OD-215-1 to OD-215-10 are decided (2026-10-20) in `docs/plan/tasks/OD-215-open-decisions.md`. Apply exactly what was decided there. OD-215-11 to OD-215-16 belong to later tasks and are not applied here.
**Owns:**
- docs/plan/design/cluster-security.md
- docs/plan/design/contract-register.md *(cluster rows only: the cluster rows of sections 3.2 and 3.5, the `dataprism.cluster.*` rows of section 5, the `dataprism.hazelcast.security.*` rows of sections 6.2 and 6.4, the cluster rows of sections 8.1, 9 and 10, rows C3, D7 and D8, and the cluster entries of section 13)*

## Goal
`cluster-security.md` was approved before 0.7 reworked the Hazelcast approval store (tasks 202, 216, 232, 233, 235, 236) and before the contract register decided OD-200-7, -9, -10, -14 and -15. Every implementation task 219 to 231 takes its names from these two documents. Bring both into line with what was decided, so that no implementer has to choose a name, a code or a label. This is the cluster part of register successor S1. The `sealed-links.md` and `key-resolvers.md` rows of S1 stay out of scope.

## Context
- docs/plan/tasks/OD-215-open-decisions.md: OD-215-1 to -10, each with its options and the recorded decision.
- docs/plan/tasks/215-plan-sealed-cluster-implementation.md, Outcome: the gap list and the test-obligation map.
- docs/plan/design/contract-register.md section 14: OD-200-7 (`_` inside a segment), OD-200-9 (drop `dataprism.hazelcast.security.mode`, `MISSING_CLUSTER_SECURITY_MODE`, `UNSUPPORTED_CLUSTER_SECURITY_MODE`), OD-200-10 (`<AREA>_KEY_WEAK`, `_UNRESOLVED`, `_SHARED`), OD-200-14 (Spring-bound `Duration` properties, bounds refused at startup with their own code, pinned in the frozen list), OD-200-15 (one `Metric` constant per outcome).
- contract-register.md section 10 and rows C3 and D7: `dataprism.hazelcast.security.lock-timeout` is withdrawn. The shipped `dataprism.hazelcast.lock-timeout` governs.
- docs/plan/tasks/retired/232-approval-creation-tokens-and-bounded-cleanup.md, "Value formats": the 0.7 approval, claim and pending value formats.
- docs/plan/tasks/retired/216-atomic-approval-creation-in-hazelcast.md, Outcome: the maps `dataprism.approval.id` and `dataprism.approval.pending`.
- cluster-security.md sections 1, 2.3.1, 2.3.2, 4.1, 4.2, 4.4, 4.7, 4.8 and 4.9: the text to change.

## Acceptance
- [ ] `cluster-security.md` section 1 inventories `dataprism.approval.id` and `dataprism.approval.pending` in the shape of 1.5, with their 0.7 keys and values (232 "Value formats") and their lock keys. Section 1.7 no longer lists `HazelcastApprovalStore` line numbers that 0.7 removed.
- [ ] `grep -n "security.mode\|MISSING_CLUSTER_SECURITY_MODE\|UNSUPPORTED_CLUSTER_SECURITY_MODE\|security.lock-timeout" docs/plan/design/cluster-security.md` prints only lines that say the name is withdrawn and cite OD-200-9 or D7.
- [ ] Section 4.2 and the closing table 4.9 use the key-failure codes decided under OD-215-3. Each code appears once in 4.9 and once in register section 3.5, with the same spelling.
- [ ] The envelope grammar in 2.3.1 is the one decided under OD-215-9. It names a nonce field. It says where `expiresAt` is authenticated, and the associated-data list agrees with that.
- [ ] Every key-derivation label (AEAD subkeys, name and lock subkeys, scope token, key check value) is listed once, with the prefix decided under OD-215-4. The KCV formula is the one decided under OD-215-7. The labels are added to register section 8.1 as `proposed` rows.
- [ ] Register section 8.1 lists every map 0.8 creates, with the generation suffix (`.s1`, as in design 4.8, unless OD-215-1 decides otherwise): the eight sealed successors of the 0.7 maps (identity, re-identification, budget, oversight, approval, approval id, approval pending, caller rate), the consumed-approval tombstone map `dataprism.approval.consumed.s1`, and the member-card map (`dataprism.membercard.s1` under the same suffix rule). The member-card row says which reserved key holds the sentinel.
- [ ] The cutover text in 4.8 and OD-5's "Decided" line agree with OD-215-1. Under option (a), the compatibility mode is marked withdrawn and `UNSEALED_CLUSTER_STATE` is defined as the refusal that enforces the non-rolling cutover. The text states that a mixed 0.7/0.8 cluster never serves budgeted reads.
- [ ] Section 2.3.2 states the approval key shape decided under OD-215-2.
- [ ] Section 4.4 says how gauges are emitted, per OD-215-5, and lists the client-visible codes decided under OD-215-10 for: a pause document that is not fresh, an unverified member under `refuse`, and `rotation.blocked`.
- [ ] OD-215-6 (b)+(c): `CLUSTER_STATE_TAMPER` as an audit record is marked withdrawn, and tamper is a metric plus a rate-bounded WARN log line; `isolation.attestation` is kept, reported on health and in one startup log line, and no text (including 2.2's "recorded in the audit startup event") says it is audited. The register rows agree.
- [ ] Section 4.1 lists each remaining `dataprism.hazelcast.security.*` key with its type, default, bounds and refusal code, in the OD-200-14 style. The register's 6.4 rows match it one for one. `pause-lease`, `clock-skew-tolerance` and `unverified-member.grace` are `Duration`. `local-floor.enabled` is `boolean`.
- [ ] Section 4.1's applicability paragraph (today: "With `single-node` or `join.mode: none` they are refused with the existing `CLUSTER_SETTINGS_IGNORED`") states OD-215-8 (a) and separates the two cases. `single-node`, and an application-supplied `PrivacyCluster` bean, refuse every `dataprism.hazelcast.security.*` key with `CLUSTER_SETTINGS_IGNORED`. `join.mode: none` is a solo member: the cluster and name keys are optional, generated at random per process when absent, and validated with the same codes as on a joining member when supplied (both or neither); `pause-lease` and `clock-skew-tolerance` are allowed; `unverified-member.*`, `local-floor.enabled` and `isolation.attestation` are refused with `CLUSTER_SETTINGS_IGNORED`, and the attestation is not required. Each 4.1 row, and its register 6.4 row, says which of these applies on `none`.
- [ ] Each metric in 4.4 and 4.9 uses `_` inside a segment (OD-200-7) and is one `Metric` constant (OD-200-15).
- [ ] The document's status line says the design is approved, names the 2026-10-19 confirmation (reconciliation-2026-10-19.md) and lists the post-approval amendments made by this task, each with its decision id.
- [ ] No sentence presents an isolation-only mode, an Enterprise feature, a shared pseudonymisation and name key, or plaintext re-identification storage as allowed.
- [ ] `git diff --stat` touches only the two owned files.

## Out of scope
- The `sealed-links.md` and `key-resolvers.md` rows of successor S1, and the OD-200-4 length caps.
- Any shipped document (`docs/*.md` outside `docs/plan/`). Task 213 owns them.
- Choosing an answer to any OD-215 decision. If a decision is still open, stop.
- Code.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Use only invented, neutral example identifiers. No real person, organisation, workplace or real-world domain.
- Say the product "supports" a requirement, never that it is "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A).

## Outcome (2026-10-21, 0.8 wave 1)
Merged onto `record/0.8-wave1a` (task branch head 8161b34f; tester PASS with `mkdocs --strict` at ae557384, later commits touch only `docs/plan/`, which mkdocs excludes; reviewer APPROVE at ed8784bf; the final two-line wording commit 8161b34f was checked by the coordinator). `cluster-security.md` and `contract-register.md` are aligned with OD-215-1 to OD-215-10, so no implementer of 220 to 231 has to choose a name, code or label. Scope was extended with the OD-200-8 register rows so the counts agree; the register now has 185 rows and 266 names.

Coordinator decisions, 2026-10-21:

- The lock key is the keyed map key of the entry it guards. There are no lock subkeys and no lock label.
- Every compare-and-set on a lockable key runs under a bounded `tryLock`, because spike Q10 showed `replace(k,old,new)` and `remove(k,v)` wait without bound on a locked key.
- OD-215-16: pause is allowed and resume is refused while the member is not serving.
- An unverified member produces no audit event.
- The consumed-tombstone map has no size eviction. Entries expire by `expiresAt` plus the clock-skew tolerance, merge as a union, and use the approval quorum and backups.
- The serialization allowlist includes every Data Prism processor and merge-policy class, and T12 enumerates them.
- A KCV mismatch makes health DOWN.
- Naming is pinned: `.` separator, unpadded base64url, and the short names equal the full `.s1` names.
- OD-215-13: only the sweeper re-seals.
- The `UNSEALED_CLUSTER_STATE` check runs before the starting member writes its own card.
- The member card uses the standard envelope, with the lease expiry in plaintext.
- New metric `CLUSTER_LEGACY_ENTRIES`.

Owner decision, 2026-10-21 (extends OD-215-1): every serving 0.8 member fences 0.7 members by keeping key `all` = TRUE in `dataprism.oversight`, re-asserted at each pause-lease refresh. Verified against v0.7.0: `ToolAdmission` checks `allPaused()` uncached on every call. `UNSEALED_CLUSTER_STATE` refuses only when no other verified 0.8 member is present, and ignores the fence. Afterwards legacy entries are counted and WARN-logged. Residuals: anyone on the network can delete the fence until it is re-asserted, and plain-Java embedders using `ToolAdmission.none()` are not fenced.
