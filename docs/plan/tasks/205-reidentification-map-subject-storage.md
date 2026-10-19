# 205 — Settle how the re-identification map stores the subject id

**Status: SUPERSEDED — not executable and not implemented.** Owner decision in this chat, 2026-10-19: the approved sealed-state design governs; encrypted re-identification values and separate cluster/name keys replace the plaintext and isolation-only options. Task 215 plans the implementation successors. The original brief below is retained as history; its release, decisions, dependencies and acceptance criteria are not current instructions.

See `docs/plan/reconciliation-2026-10-19.md` and `docs/plan/design/cluster-security.md`.

## Original brief (superseded)

Renumbered from 190 on 2026-10-19 (id collision with the 0.7.0 plan). Scope superseded by the sealed-state design on 2026-10-19.

**Repo:** `.`
**Release:** 1.0.0 (data-protection hardening)
**Base branch:** `origin/main` once 204 has merged
**Depends on:** 204
**Blocked on:** **D-1.0-3 (open).** Does the re-identification map keep the raw subject id in plaintext,
or encrypt it? Do not fan out until the owner has answered. If the answer is "encrypt", the key's source
also needs a decision. Ask whether it follows D-1.0-7 (the subject-key HMAC key) or is separate, and
record the answer here before starting.

**Owns:**
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSource.java *(the re-identification `set` at :131-135 only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeIdentityIndex.java
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/PrivacyCluster.java *(the class Javadoc and `REIDENTIFICATION_MAP` Javadoc only; under "encrypt", also the constructor and factory parameters that carry the cipher key)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ReidentificationCipher.java *(new, "encrypt" only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/HazelcastStoredValueBoundaryTest.java *(insertions only)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/ReidentificationStorageTest.java *(new)*

## Goal
The re-identification index maps a pseudonym to its subject. It is off by default, and it is the one
cluster map that cannot be recomputed (`PrivacyCluster.java:25-29,54-55`). Its value is the raw subject
id (`CachingSyntheticValueSource.java:131-135`), so after task 204 it is the last place in the cluster
where a subject id, possibly a national identifier, is readable by anyone who reaches the member port.
Implement whichever storage D-1.0-3 chooses, and make the result explicit in code and tests.

## Context
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSource.java:114-136 — `store`, including the re-identification write
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeIdentityIndex.java:34-75 — `subjectFor` and `endScope`
- docs/architecture.md:303-314 — why the index is a store, not a cache, and therefore off by default
- docs/reidentification.md — the operator surface that reads this index; task 213 updates it

## Acceptance
Under **"keep plaintext"**:
- [ ] `PrivacyCluster`'s class Javadoc and `REIDENTIFICATION_MAP`'s Javadoc both say that this map, off by default, is the one place the cluster stores a raw subject id, and that network isolation of the member port is what protects it.
- [ ] `ReidentificationStorageTest` asserts that with the index disabled no entry is written to `REIDENTIFICATION_MAP`, and with it enabled the value is the subject id and the key contains no subject id.

Under **"encrypt"**:
- [ ] `ReidentificationCipher` encrypts each value with AES-GCM under a key of at least 256 bits, with a fresh 96-bit nonce per value from `SecureRandom`. The scope id, namespace and pseudonym are bound as associated data, so a value moved to another key fails to decrypt. Its `toString()` contains no key bytes, and a test asserts this.
- [ ] Enabling the index without a cipher key refuses with a new `PrivacyClusterRefusal` code `MISSING_REIDENTIFICATION_KEY`.
- [ ] `ScopeIdentityIndex.subjectFor` returns the original subject id after a round trip. A value whose ciphertext was altered, or moved under another pseudonym's key, makes `subjectFor` return empty and increment a failure metric. It never returns a partial value or throws the cipher's message. Tests cover all three.
- [ ] `HazelcastStoredValueBoundaryTest` gains a case asserting that no value in `REIDENTIFICATION_MAP` contains the fixture subject id. Mutation proof: storing the plaintext makes it fail.
- [ ] The nonce source is the only randomness added, and it is not in a pseudonymisation path (docs/conventions.md: no `Random` there).

Either way:
- [ ] The close-out states which branch was implemented and quotes the owner's D-1.0-3 answer with its date.
- [ ] `./mvnw -B -pl data-prism-hazelcast -am verify` passes.

## Out of scope
- Wiring a key or property at the Spring layer (task 212).
- The identity and budget maps (task 204).
- The operator surface, the four-eyes approval flow and `docs/reidentification.md` (task 213 updates the doc).
- Persistence or `MapStore` for the index, which stay refused.
