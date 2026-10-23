# 264 — Share the sealed-link failed-attempt limiter across a Hazelcast cluster

**Repo:** `.`
**Release:** 0.8.0 (links stay off by default through 0.9). If this task is cut from 0.8, the fallback below ships instead.
**Base branch:** the 0.8 integration branch, at a commit that contains 229, 230, core 242, core 269 and core 255.
**Depends on:** 229 (every sealed store and the rotation sweeper exist; through it 223 `SealedWrites`, keyed names and `ClusterSecuritySettings`, and 226 `CounterMaps`, `LocalFloor` and the counter merge policy), 242 (core plan: the codec and the first limiter SPI, both revised by 269), 230 (serialised after 230 on `ClusterBackedState.java` and the bean-inventory lists; through it core 255, which owns the `MISSING_SHARED_LINK_LIMITER` startup refusal and the in-memory limiter bean), 269 (the settled limiter contract this task implements: `AttemptSubject`, atomic `recordFailure` returning `Admission`, the ceiling, `LinkAttemptGate`)
**Wave:** 13 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Decision:** core OD-08-2 (a), decided by the owner 2026-10-20, which also settles OD-215-14 with this id. The owner accepts this task slipping to 0.9.0; the fallback (c) below is agreed for that case. Per-instance limiting on a cluster (option (b)) is excluded outright.
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastLinkAttemptLimiter.java *(new)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(the link-attempt map-name constant only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CounterMaps.java *(the link-attempt map configuration only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/RotationSweeper.java *(adding the link-attempt map to the walked maps only, and only if 229 lists the maps explicitly)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/CounterMapsBoundary.java *(the link-attempt map checks only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/PrivacyClusterMembershipTest.java *(the link-attempt map's settings and list entry only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastLinkAttemptLimiterTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/LinkAttemptResetReplayTest.java *(new)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/LinkAttemptRotationTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(the shared limiter bean only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/LinkWiring.java *(the in-memory limiter bean's condition only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for the bean this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/SharedLinkAttemptLimiterConfigurationTest.java *(new)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/cluster/SharedLinkAttemptLimiterEndToEndTest.java *(new)*
- docs/plan/design/cluster-security.md *(the link-attempt map's rows in the section 1 inventory and the 2.3.1 per-structure failure table only)*
- docs/plan/design/contract-register.md *(the link-attempt map-name row in section 8.1 and the `MISSING_SHARED_LINK_LIMITER` row only)*

## Goal
Sealed links count failed attempts per principal and scope and lock the principal out at the limit (sealed-links section 4, OD-8). Today the only limiter is in memory, so on a cluster a forger gets the limit once per member. This task adds a Hazelcast limiter that every member shares. It keeps its counts in a new sealed counter map, which joins the boundary inventory. Page tokens from `list_sources` (core 250) count their failures in the same limiter, so the limiter is needed whenever any sealed-token consumer is active: `dataprism.links.enabled=true`, or a configured link key (core 254's link-codec bean, which makes `list_sources` issue page links; paging has no `enabled` property of its own). Core 255 already refuses startup with `MISSING_SHARED_LINK_LIMITER` on an embedded member that joins a cluster, has a sealed-token consumer active and has no cluster-wide limiter. This task supplies that limiter as a bean, so the refusal no longer fires on a correctly configured cluster.

## Context
- docs/plan/design/sealed-links.md section 4 (limiter: counted reasons, lock including valid links, window from the stored start, "cluster-wide on a shared topology") and section 7 (obligations on the limiter), as corrected by 238.
- Core 269 brief, "Required shape" (supersedes 242's limiter shape): `LinkAttemptLimiter` with `lockState` (`UNLOCKED`, `LOCKED`, `UNAVAILABLE`), `recordFailure` returning `Admission` (`WITHIN_LIMIT`, `OVER_LIMIT`, `CEILING`, `UNAVAILABLE`) and `clusterWide()`, which defaults to `false`; `AttemptSubject` with `canonicalText()`; `InMemoryLinkAttemptLimiter(limit, window, clock[, maxEntries])`; and the list "What 264 may relax, and what it must keep". Its limiter acceptance items are the semantics to reproduce.
- Core 255 brief: the limiter acceptance item (which topologies get the in-memory limiter, the `MISSING_SHARED_LINK_LIMITER` refusal, `dataprism.links.invalid-attempt-limit` and `invalid-attempt-window`).
- Task 226: the counter pattern this task copies. That covers the sealed value with its window, one computing processor from `SealedWrites`, a value that does not open failing closed rather than resetting to zero, `LocalFloor`, and the counter merge policy decided under OD-215-11.
- Task 223: keyed map keys (`KeyedNames`), `HazelcastStoredValueBoundaryTest` taking its map list from the `PrivacyCluster` constants, and the per-area checkers.
- Task 229: `RotationSweeper` walks every sealed map except identity and the pause document.
- Task 227: `ServingGate`. Under `unverified-member.action: refuse`, a closed gate refuses tool-path stores.
- docs/plan/design/cluster-security.md 1 (inventory), 2.3.1 (per-structure failure), 2.3.5 (local floors), 4.6 (rotation), as amended by 218.
- From 242 and the external review of PR #154 (2026-10-22): the counter key, the check-then-record race, the map bound and the window rule were raised there. 269 settles all of them; read 269, not 242, for the limiter. In particular, the shared limiter keeps a map TTL, but only to bound memory (see "Required shape").
- From 269 (OD-269-1 to 3 decided (a) by the owner 2026-10-22; brief revised 2026-10-23): this task implements 269's `LinkAttemptLimiter` contract. "Required shape" below is rewritten to it, and 269's "Required shape" is the reference for the SPI, `AttemptSubject` and the gate.

## Required shape
- Map `dataprism.linkattempt.s1` (or the name 218's naming rule gives), configured in `CounterMaps`. It has the counter merge policy, no size-based eviction policy, and a TTL of at least the attempt window plus the clock-skew tolerance, measured from the entry's last write (269's "must keep"). Expiry can then remove only an entry whose window has ended on every member's clock, so it never lifts a live lock. The TTL only bounds memory: whether a window has ended is decided by comparing the sealed window start with the injected `Clock`, never by an entry expiring. This replaces 269's in-memory expiry queue, which 264 may relax.
- The map key is `KeyedNames.mapKey(<this map>, subject.canonicalText())` under the name key: the lowercase hex of `AttemptSubject.canonical()`, which is lossless (269; pinned in core's `vectors.properties` as `attempt.subject.canonical.text`). Never decode `canonical()` as UTF-8. The name key does not rotate in the rolling procedure, so counts neither split nor reset during a link-key or cluster-key rotation. The stored value is sealed with its count, its window start and the canonical bytes, which are encrypted and never stored in the clear.
- `HazelcastLinkAttemptLimiter implements LinkAttemptLimiter` (269's SPI), and `clusterWide()` returns `true`. `lockState(AttemptSubject)` is a read. `recordFailure(AttemptSubject)` is one computing processor from `SealedWrites`: it opens the value, opens a new window if the stored one has ended, adds one, seals once on the primary and copies the bytes to the backup. It returns the new count, and the limiter answers `WITHIN_LIMIT` when that count is at most the limit, otherwise `OVER_LIMIT`. Neither method throws. The class is in `io.github.aindriub.dataprism.hazelcast`, the one package where 269's architecture rule lets a class call a limiter directly.
- Local floor (`ClusterSecuritySettings.localFloorEnabled`). The member also holds an `InMemoryLinkAttemptLimiter`, and **both** operations combine the two limiters. `lockState` is `LOCKED` if either says `LOCKED`, otherwise `UNAVAILABLE` if either says `UNAVAILABLE`, otherwise `UNLOCKED`. `recordFailure` records into both and returns the more restrictive answer, in the order `UNAVAILABLE`, `CEILING`, `OVER_LIMIT`, `WITHIN_LIMIT`. A failure of either side is `UNAVAILABLE`, including a floor `UNAVAILABLE` while the shared side admits. A null answer from either is treated as `UNAVAILABLE`.
- Ceiling (269, OD-269-3 (a), within "may relax / must keep"). No live entry is evicted to make room. At the ceiling, a new subject's failure is `CEILING` and is not stored; an existing subject keeps counting. The cluster-wide check may be approximate (for example `IMap.size()` read before the processor, or a member-local estimate), and the Outcome states the bound it gives. The exact atomic count and the per-call queue bounds of 269's in-memory limiter are not required here. A subject that is not stored is never locked, as 269 records and the owner accepted.
- Fail closed: `lockState` returns `UNAVAILABLE`, and `recordFailure` returns `UNAVAILABLE`, in each of these cases: a stored value does not open (this also counts tamper through the 220 `TamperListener` with the map-name constant); a cluster operation is refused or times out; the serving gate is closed. The gate turns either into `Exhausted(LIMITER_UNAVAILABLE)`, so the audit tells an outage from a lock. A missing value counts as zero, which is why the local floor exists.
- Spring: `ClusterBackedState` creates the bean when any sealed-token consumer is active (links enabled, or the link-codec bean exists so page links are issued; the same condition 255's refusal uses, read from one shared condition, not restated) and the member joins a cluster (`join.mode` `tcp-ip` or `kubernetes`). Links off with a link key configured is not a reason to skip the bean. It reads its limit and window from `dataprism.links.invalid-attempt-limit` and `invalid-attempt-window` (255). On `single-node` and `join.mode: none` the 255 in-memory limiter stays. An application-supplied `LinkAttemptLimiter` bean still wins over both.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `HazelcastLinkAttemptLimiterTest`, on two embedded members with an injected clock, runs 269's limiter cases against the Hazelcast limiter:
  - Failures recorded on member A lock the counter key on member B at exactly `limit`, and a valid link is then refused too.
  - Another principal, or the same principal in another scope, is unaffected.
  - Increments do not extend the window. At window start plus the window, the key unlocks, whatever the entry's TTL.
  - Sixteen threads split across both members lose no count and lock exactly at the limit.
  - 269's race test: 64 concurrent forged attempts for one subject, split across both members, get exactly `limit` `WITHIN_LIMIT` results.
  - With the local floor on, a subject locked only in the floor (the shared entry deleted) is still `LOCKED` on that member.
- [ ] `LinkAttemptResetReplayTest`, with the local floor on:
  - Deleting the shared entry, or replaying a captured lower sealed count, never lets one member accept more than `limit` failures for a key before it locks. Across two members the total never exceeds `2 × limit`.
  - With the floor off, the test documents the reset; the case is named for it.
  - A tampered or foreign value makes `lockState` return `UNAVAILABLE` and counts tamper. The value is not replaced by zero.
- [ ] A refused processor and a timed-out lock each make `lockState` and `recordFailure` return `UNAVAILABLE`, and the gate answers `Exhausted(LIMITER_UNAVAILABLE)`. No exception text reaches the caller. Under `refuse`, an unverified member present makes `lockState` return `UNAVAILABLE` once the gate closes.
- [ ] Merge: a split-and-heal case shows the merged count is never below the larger side's count under OD-215-11 (a), or names the documented loss under (b).
- [ ] `CounterMapsBoundary` covers the new map. Every value opens under the test key and recomputes its keyed name, and the raw-value fixture is rejected. `HazelcastStoredValueBoundaryTest` passes without being edited and fails if the constant is removed from the area checker. `PrivacyClusterMembershipTest` pins the new map's TTL, eviction and merge policy, and the map-list test matches register section 8.1.
- [ ] `LinkAttemptRotationTest`: after a phase 2 rotation and one sweeper pass, `seal.entries_under_previous` counts no link-attempt entry. A locked key stays locked across the rotation.
- [ ] No map key or value contains a scope id, principal id, link token or counter key in the clear. A sentinel test covers exception messages, `toString()` and log lines.
- [ ] `SharedLinkAttemptLimiterConfigurationTest`, table-driven, one row per case:
  - `embedded` with `join.mode: tcp-ip` (and `kubernetes`), links enabled and keys configured: starts, and the `LinkAttemptLimiter` bean is the Hazelcast limiter.
  - The same with this task's bean removed: refused with `MISSING_SHARED_LINK_LIMITER` (255's check, now proven not to fire when the bean is present).
  - `embedded` with `join.mode: tcp-ip` (and `kubernetes`), links disabled and a link key configured (page links on): starts, and the bean is the Hazelcast limiter; `list_sources` page-link failures on two members lock the principal at `invalid-attempt-limit` in total.
  - The same paging-only configuration with this task's bean removed: refused with `MISSING_SHARED_LINK_LIMITER`.
  - `join.mode: none` with links enabled, and with links disabled and a link key configured: each starts with the in-memory limiter and is never refused.
  - `single-node` with links enabled, and with links disabled and a link key configured: each starts with the in-memory limiter and is never refused.
  - Links disabled and no link key (no sealed-token consumer): no limiter bean from this task, and no refusal on any topology.
  - Fallback (c) configuration (this task's bean class absent from the context): a joining member with links enabled is refused, a joining member with links disabled and a link key configured is refused, and a joining member with neither starts; `join.mode: none` and `single-node` start in every combination.
  - An application-supplied limiter bean is kept.
- [ ] `SharedLinkAttemptLimiterEndToEndTest`, on two servers sharing one cluster: invalid `follow` calls split between the servers lock the principal at `invalid-attempt-limit` in total. A following valid `follow` on either server is refused with `LINK_ATTEMPTS_EXHAUSTED` and reads nothing.
- [ ] `cluster-security.md` lists the new map in the section 1 inventory and in the 2.3.1 failure table (a refusal locks, it never resets). `contract-register.md` 8.1 has the map name, and the register has a `MISSING_SHARED_LINK_LIMITER` row naming 255 as the check and this task as the bean.
- [ ] `AutoConfiguredBeanClassificationTest` and `AutoConfiguredBeanInventoryTest` pass, and the inventory diff contains only this task's line.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## If this task is cut from 0.8: fallback (c)
This is what ships instead (core OD-08-2 (c)). No code change is needed beyond 255, because 255's refusal fires whenever no cluster-wide limiter exists:
- On a member that joins a cluster (`embedded` with `join.mode` `tcp-ip` or `kubernetes`), every sealed-token consumer is refused at startup with `MISSING_SHARED_LINK_LIMITER`: `dataprism.links.enabled=true`, and a configured link key (page links), whether or not links are enabled. A joining member therefore runs with links off and no link key, so `list_sources` answers on one page (core 250), and an estate larger than the default page size is refused by core 254's `MISSING_PAGING_KEY`. Per-instance limiting (option (b)) is not a fallback for either consumer.
- A solo embedded member (`join.mode: none`) and `single-node` are never refused. They use the in-memory limiter for both consumers.
- Whoever cuts the task removes 264 from the Depends-on lines of 263, 213 and 260 (260 then pins the map names without `dataprism.linkattempt.s1`). Then 263 runs its three-member mix with links disabled and no link key (no follow and no page links), and the link and paging mix on a single node only, and 213 states the refusal, for links and page links, in `docs/multiple-instances.md`.
- The cut and the fallback are recorded in 215's Outcome and in the 0.8 release notes.

## Out of scope
- The startup refusal itself, the `dataprism.links.*` properties and the in-memory limiter bean (core 255). The SPI, its in-memory implementation and the gate (core 269). This task adds no property, no refusal code and no `docs/configuration.md` row.
- `HazelcastStoredValueBoundaryTest.java`, `TestMembers.java`, `Metric.java` and `PrivacyMetricsTest.java` are read only; no new metric constant.
- The follow path's order of checks (core 252) and the page-link counting (core 250).
- Operator documentation: put the facts for 213 (`docs/multiple-instances.md`) in the Outcome.
- The end-to-end adversarial suite (231) and the soak (263).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Neutral, invented identifiers in tests. Keys are generated test bytes, with invented key ids.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps. Windows use an injected clock.
