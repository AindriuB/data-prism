# 215 — Plan the 0.8 sealed-cluster implementation

**Repo:** `.`
**Release:** 0.8 planning, after the 0.7 integration work
**Base branch:** `origin/main` after 0.7 integration, or its designated integration branch.
**Depends on:** 192, 200, 202
**Kind:** planning; no runtime code.
**Owns:**
- docs/plan/tasks/218-*.md through docs/plan/tasks/231-*.md (new briefs only; check unused ids before allocation)
- docs/plan/tasks/264-*.md (new brief; id allocated by the coordinator under OD-215-14)
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

## Outcome (2026-10-20)

Planning only. No design protection is implemented by this task.

### Briefs and order

Waves are on the combined 0.8 graph of 213, 218 to 231 and 238 to 264, with 203 and 206 to 211 counted as landed; they match the core briefs' **Wave** lines.

| Id | Title | Depends on | Wave |
|---|---|---|---|
| 218 | Align the cluster design and the contract register with the 0.8 decisions | none (decisions OD-215-1 to -10 recorded) | 1 |
| 219 | Pin the Hazelcast 5.7 behaviours the sealed cluster relies on | none | 1 |
| 220 | Labelled HKDF, the sealing envelope and keyed names, with vectors | 218 | 2 |
| 221 | Force safe Hazelcast settings, move to `.s1` maps, refuse old-format state | 218, 219 | 2 |
| 222 | Configure the cluster and name keys; refuse a multi-member join without them | 220, 221, 211 | 3 |
| 223 | Key every map and lock name; deterministic sealed writes; `ClusterSecuritySettings` | 219, 220, 222 | 4 |
| 224 | Seal approvals, claims and pending slots; consumed tombstones | 219, 223 | 5 |
| 225 | Seal the identity cache and the re-identification index | 220, 223 | 5 |
| 226 | Seal counters, local floors, Hazelcast budget reservations | 219, 223, 243 (core) | 5 |
| 227 | Member cards, sentinel at join, unverified members | 219, 220, 223 | 5 |
| 228 | Sealed, sequenced, leased pause document | 219, 223, 227 | 6 |
| 229 | Phased cluster-key rotation | 219, 224, 225, 226, 227, 228 | 7 |
| 230 | Spring wiring of the remaining settings, `clusterSecurity` health, tamper reporting | 222, 229, 255 (core) | 12 |
| 231 | End-to-end adversarial and integration verification | 230 | 13 |
| 264 | Share the sealed-link failed-attempt limiter across a Hazelcast cluster | 229, 242 (core), 230 | 13 |

Owner decisions: `docs/plan/tasks/OD-215-open-decisions.md` (OD-215-1 to -16).

Coexistence with 202 and the 0.7 approval rework: no 0.7 approval property is dropped. 221 keeps every merge policy, quorum and eviction setting under the new names; 223 keeps the 232 lock table true on keyed names and bounds the writes 202 left unbounded; 224 moves consumed-wins to tombstones, as 202 said 0.8 must. Serialisation against 208, 210 and 211: 222 depends on 211 (which follows 208 and 210), and every later task touching the shared Spring files (230) comes after 222. Mixed old and new budget namespaces: 221 refuses to start on old-format state (OD-215-1 (a)), and 227 counts a later 0.7 member as unverified.

Operator documentation: 213 (`docs/multiple-instances.md`, `docs/key-rotation.md`, `docs/migration-0.8.md`); 222 and 230 own their `docs/configuration.md` rows. Full integration verification: 231. The soak (design 5.2) is core task 263.

### Test obligations

| Obligation | Task |
|---|---|
| T1 forged approvals | 224 (module), 231 (servers) |
| T2 splice, bit-flip, unknown format and key | 220 (envelope), 224 (entry level) |
| T3 identity poisoning | 225 |
| T4 pause document | 228 |
| T5 approval replay and its residual | 224 |
| T6 counter reset and replay | 226 |
| T7 stored-value boundary | 223 (keys), 224 to 228 (each area's checker) |
| T8 startup codes | 221, 222, 227, 230; completeness in 231 |
| T9 key mismatch | 227 |
| T10 unverified member | 227 (module), 230 (health), 231 (servers) |
| T11 lock hold | 223 |
| T12 serialisation filter | 221 |
| T13 mixed format | 221 (old maps), 231 (`.s1` maps and `dps2`) |
| T14 architecture rules | 220, 225 |
| T15 log, metric and audit scan | 231 |
| T16 HKDF, nonce uniqueness, salt cache | 220 |

### Gaps, unresolved

- The rotation soak under load and the environment packet capture (design 5.2) are core task 263's; 229 tests rotation in process only.
- The Hazelcast failed-attempt limiter for sealed links is 264 (core OD-08-2 (a), OD-215-14 (a)). If 264 is cut from 0.8, fallback (c) ships: core 255's `MISSING_SHARED_LINK_LIMITER` refusal stops links on members that join a cluster, never on a solo member or `single-node`. 264's brief lists the edges to remove.
- Two 232 follow-ups stay open: `endScope` and `forgetScope` have no production caller, and a forget during a split is undone by the heal.
- Boundary 7 stays partly enforced: metric names and health details are scanned by 231, but trace attributes are not.
- `PUBLIC_MEMBER_INTERFACE` is not built (cluster-security OD-10 (ii)).
- Mixed Hazelcast patch versions may be "not run" in 219 if no second 5.7 patch resolves; then only the release-checklist note covers it.
- Under `unverified-member.action: alert`, a member without the key (including a late 0.7 member) keeps the cluster serving; this is the accepted cost of `alert`.
- The envelope, key derivation and pause document go to the 0.9 external review (cluster-security OD-12); nothing in 0.8 replaces that review.

### Shared with the core plan (238 to 263)

`Metric.java` and `PrivacyMetricsTest.java` (220 with 241, 244; insertions); `DataPrismPropertiesValidator.java`, `frozen-property-names.txt`, `additional-spring-configuration-metadata.json`, `docs/configuration.md` (222, 230 with 254, 255; 261 for `docs/configuration.md`); `PrivacyExtensionPoints.java` and `bean-inventory/*.txt` (230 with 254, 255); root `pom.xml` (221 `hazelcast.version` with 257 `jackson3.version`); `PiiLogScanTest.java` (231 with 255); `ClusterBackedState.java`, `LinkWiring.java` and `bean-inventory/*.txt` (264 after 230 and 255); `contract-register.md` and `cluster-security.md` (264 after 218); `data-prism-server/src/test/**` (222's argument edits with 245's new files); 213's Depends-on line. 242 depends on 220; 246 depends on 226; 263 and 213 depend on 264.
