# 225 — Seal the identity cache and the re-identification index

Kept for 1.0 by owner decision, 2026-10-23 (restored after owner decision (b) had deferred it the same day): the 2026-10-19 decision "encrypted re-identification values" stays in force for 1.0. It depends on no deferred brief: `Sealer`, `SealedWrites`, keyed names and the boundary checkers are on `main` (220, 223), and it reads no setting 230 would have bound. Key rotation (229) stays deferred, so in 1.0 every identity and re-identification value is sealed under the current cluster key only, and a cluster-key change is a planned full restart that empties both maps (213 documents it).

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 223.
**Depends on:** 223 (keyed names, `SealedWrites`, `IdentityMapsBoundary`), 220 (the T14 rule this task extends)
**Wave:** 5 (restored 2026-10-23; no open dependency)
**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSource.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeIdentityIndex.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/IdentityMaps.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/boundary/IdentityMapsBoundary.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSourceTest.java, IdentityMetricsTest.java *(changes only where a stored value is read or written raw)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/IdentityPoisoningTest.java *(new, T3)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ReidentificationSealingTest.java *(new)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java *(insertions only: the second T14 rule)*

## Goal
After this task the identity cache and the re-identification index hold only sealed values. Subject ids exist in the cluster only inside authenticated ciphertext under the re-identification map's own subkey, and arbitrary data written into the identity map can never change a value Data Prism returns. The one decrypt-and-return site for the index stays `ScopeIdentityIndex.subjectFor` (boundary 5).

## Context
- docs/plan/design/cluster-security.md 1.1, 1.2, 2.3.1 (per-structure failure: `identity` recomputes, `reidentification` returns empty), 2.3.7 (deleted index entries are lost for good), 6.1 boundaries 5 and 6, as amended by 218.
- Task 223 Outcome: the call-site table for identity and scope-end writes; `KeyedScopeEndTest`.
- data-prism-hazelcast/.../ScopeIdentityIndex.java (`subjectFor`, `endScope`); CachingSyntheticValueSource.java (cache, conflict count).
- data-prism-architecture/.../ArchitectureTest.java, `ONLY_REIDENTIFICATION_CALLS_SUBJECT_FOR` and the T14 rule from 220.
- docs/architecture.md boundaries 5 and 6.

## Acceptance
- [ ] Every value in `dataprism.identity.s1` and `dataprism.reidentification.s1` is a `dps1` envelope with the canonical key parts inside the plaintext. Writes go through `SealedWrites`.
- [ ] T3, in `IdentityPoisoningTest`: a keyless member, a keyless client and a member with another key write plaintext, 0.7-format and random-envelope values under the keyed name of a real entry. Every synthetic value returned afterwards equals the generator's output for that input (boundary 6), and `seal.tamper` or `seal.unsupported_format` counts each.
- [ ] `ReidentificationSealingTest`: a tampered or foreign index entry makes `subjectFor` return empty, never another subject, and is counted. A deleted entry returns empty. The test is named for the residual: "deleted re-identification entries are lost".
- [ ] A ciphertext from `dataprism.identity.s1` copied into `dataprism.reidentification.s1` under the same keyed name does not open (distinct subkey and associated data).
- [ ] `endScope` still removes exactly one scope's entries in both maps, on one and two members (`KeyedScopeEndTest` from 223 passes unchanged).
- [ ] T14, second rule: `ArchitectureTest` fails when any class other than `ScopeIdentityIndex` opens an envelope read from the re-identification map. A nested fixture shows the rule can fail. `onlyReidentificationCallsSubjectFor` is unchanged.
- [ ] `IdentityMapsBoundary`: every value opens under the test key and its decrypted parts recompute the keyed name and the allowed value; the raw-value fixture is still rejected.
- [ ] Public constructors of `CachingSyntheticValueSource` and `ScopeIdentityIndex` are unchanged, so `ReidentificationWiring` needs no edit.
- [ ] A closed serving gate (227, same wave) is not tamper: a `Sealer` refusal because the member is not serving surfaces from `subjectFor` as `CLUSTER_OPERATION_REFUSED`, never as an empty result and never counted as `seal.tamper`; the identity cache recomputes, as for any cache failure. A test closes the gate with a stub `ServingGate`.
- [ ] Only the current key: with no `previous` configured, values are sealed and opened under `current`; the Outcome states, for 213, that a cluster-key change empties both maps (index entries are lost, pseudonyms already issued cannot be re-identified).
- [ ] `./mvnw -B -pl data-prism-hazelcast,data-prism-reidentification,data-prism-architecture -am verify` and `./mvnw -B verify` exit 0. Report the counts.

## Preserves
Bounded writes and lock keys from 223; `CLUSTER_LOCK_TIMEOUT` and `CLUSTER_OPERATION_REFUSED` on the re-identification path (233); the identity cache conflict counter and its metric.

## Out of scope
- Approvals and counters (224, 226, deferred past 1.0) and pause state (228). Rotation re-seal of index entries (229, deferred past 1.0): write and open under the current key only; do not build a previous-key path beyond what `Sealer` already does.
- `endScope` and `forgetScope` having no production caller (232 follow-up). Not changed here.
- Migrating 0.7 index entries. Under OD-215-1 there is no migration; the index starts empty.
- `TestMembers.java` and every file under `data-prism-reidentification/` are read only (core task 245 edits that module). If a re-identification test there reads a raw stored value, stop and report it. `docs/` pages: facts for 213 in the Outcome.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Neutral, invented identifiers in tests; no real person's data. Keys are generated test bytes.
- Say "supports", never "compliant".
- No Hazelcast Enterprise feature (D-0.4.1-A). No new dependency.
- No sleeps.
