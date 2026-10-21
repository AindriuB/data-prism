# 220 — Build labelled HKDF, the sealing envelope and keyed names, with known-answer vectors

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` (or the 0.8 integration branch), at a commit that contains 218.
**Depends on:** 218 (labels, envelope grammar, code names and metric names are fixed there)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/crypto/** *(new package)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/crypto/** *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java *(insertions only: every `dataprism.cluster.*` constant registered by 218)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetrics.java *(only the gauge addition decided under OD-215-5, if any)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetricsTest.java *(insertions only)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/seal/** *(new package)*
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/seal/** *(new)*
- data-prism-hazelcast/src/test/resources/seal/** *(new: known-answer vector files)*
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/FailSafeMetrics.java *(the gauge forwarding only, if OD-215-5 adds one)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/ServerPrivacyMetrics.java *(the gauge method only, if OD-215-5 adds one)*
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/http/MicrometerPrivacyMetrics.java *(the gauge method only, if OD-215-5 adds one)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/MicrometerPrivacyMetricsTest.java *(insertions only, if OD-215-5 adds a gauge)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPrivacyMetricsGaugeTest.java *(new, if OD-215-5 adds a gauge)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java *(insertions only: the T14 rule)*

## Goal
Provide the cryptographic core every later cluster task uses, with nothing wired to a map yet. That means a labelled HKDF (RFC 5869) that task 191's sealed links can reuse, the `dps1` AES-256-GCM envelope with per-process salt and counter nonces, keyed HMAC names for map keys, lock keys and the scope token under the separate name key, and a key holder for the current, next, previous and name keys. Also add every cluster metric constant now, so the later tasks never edit `Metric.java` and its pinning test.

## Context
- docs/plan/design/cluster-security.md 2.3.1 (envelope, nonce construction, failure behaviour, salt cache), 2.3.2 (keyed names, scope token truncated to 128 bits), 4.6 (two secrets, write-key rule), 4.7 (labels, key check value), 6.1 boundary 7 (JCA failures are caught and reported as a counter and a constant message), as amended by 218.
- docs/plan/design/sealed-links.md "Key derivation" (lines 155-171): the link design's HKDF use (zero salt, one-block expand, length-prefixed `info`). The helper must serve it unchanged.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/SecretKeyProvider.java: how key material is obtained; never cached beyond use, never logged.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PropertiesValidation.java:129-132: the existing 32-byte strength rule for the HMAC key.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java and PrivacyMetricsTest.java: one constant per signal, with the full set pinned.
- data-prism-architecture/.../ArchitectureTest.java, `ONLY_REIDENTIFICATION_CALLS_SUBJECT_FOR` (about :699-716): the shape of a boundary rule.
- From 218/219 (2026-10-21): Add `Metric.CLUSTER_LEGACY_ENTRIES`.

## Required shape (later tasks read these fields; state them all now)
- `LabelledHkdf` (core): `extract(salt, ikm)`, `expand(prk, info, length)`, and an `info` encoder that length-prefixes a label and context items. The prefix width is fixed here and pinned by vectors.
- `ClusterKeys` (seal): the current cluster key id and material, the optional `next` and `previous` ids and material, and the name key id and material. Every key is at least 32 bytes. The ids are pairwise distinct, and the name-key material differs from every cluster key. A constructor refuses otherwise with `IllegalArgumentException`, naming the field and never the value.
- `Sealer` (seal): `seal(map, mapKey, plaintext)` writes under the key that a `WriteKeyPolicy` chooses (the default returns `current`). `open(map, mapKey, envelope)` returns an `Opened` result with three outcomes: authentic (with the key id used), tampered, or unsupported (unknown version or key id). It never throws to the caller for a bad input, and it counts `seal.tamper` and `seal.unsupported_format`. It reports each tamper to a `TamperListener` (default: none) with a map-name constant and no entry data. A `ServingGate` hook (default: open) lets task 227 make `seal` and `open` refuse with `ClusterOperationRefusedException` while the member is not serving.
- `KeyedNames` (seal): `mapKey(map, canonical)`, `lockKey(map, canonical)`, `scopeToken(scopeId)` and `scoped(map, scopeId, canonical)` (scope token, separator, keyed part). These are HMAC-SHA256 under subkeys of the name key only, URL-safe base64, with labels from 218.
- Key check value (seal): computed by the formula decided under OD-215-7, for a purpose key id plus its material.
- Salt cache: bounded and LRU, keyed by (salt, key id, map). An admission refused under a spray of distinct salts counts `seal.salt_rejected` and is an unsupported result, never an exception.
- `WriteKeyPolicy` throws `ClusterOperationRefusedException` (and the sealer counts `rotation.blocked`) when no held key is acceptable. That gives callers the 0.7 refusal code.

## Acceptance
- [ ] `LabelledHkdf` reproduces RFC 5869 test cases 1, 2 and 3 (SHA-256) byte for byte. The test fails if one byte of an expected OKM is changed (shown once in the Outcome).
- [ ] A vector file under `src/test/resources/seal/` pins, for a fixed key, salt and counter: each AEAD subkey, each name, lock and scope-token output, one envelope per map name, and one key check value. A test regenerates them and compares. The file header names the format version `dps1`.
- [ ] T2 at envelope level: a valid envelope opened under another map name, another map key, a flipped bit in each field, a truncated envelope, an unknown format version (`dps2`) and an unknown key id each give a non-authentic result. None throws, and each increments the right counter.
- [ ] T16: 100,000 seals across 16 threads under one salt produce no repeated (subkey, nonce) pair. Two `Sealer` instances (a simulated restart) draw different salts. A spray of 10,000 distinct salts leaves the cache at its bound and counts `salt_rejected`.
- [ ] `ClusterKeys` refuses a key under 32 bytes, duplicated ids, and a name key equal to a cluster key. Each refusal message names the field only. A test asserts that no message contains key material, encoded in hex or base64.
- [ ] `KeyedNames` output for a scope id, a subject id, a principal id, an approval id and a pseudonym contains none of them as a substring. Two different name keys give different names for one input. The same name key and input always give the same name.
- [ ] Every `dataprism.cluster.*` metric in register section 5 (after 218) is a `Metric` constant, and `PrivacyMetricsTest` pins the full set. No other `Metric` constant changes.
- [ ] OD-215-5 (a), a gauge method: it is a `default` no-op on `PrivacyMetrics`, `FailSafeMetrics` forwards it, and every existing implementer compiles unchanged. The two Micrometer implementations (`ServerPrivacyMetrics`, `MicrometerPrivacyMetrics`) register a real gauge, and a test per implementation reads the registered gauge's value back from the meter registry. This lands here, not in a later cluster task, because the core plan's link-key gauge (OD-08-10) needs the same method.
- [ ] T14: a new `ArchitectureTest` rule fails when a class in `mcp`, `orchestration` or `connectors` depends on `io.github.aindriub.dataprism.hazelcast.seal`. A fixture class nested in `ArchitectureTest` proves the rule can fail (as task 235 did).
- [ ] JCA exceptions inside `seal` and `open` are caught at the sealing boundary. A test with a failing provider shows that the result carries a constant message and no exception text or input.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-hazelcast,data-prism-architecture -am verify` exits 0. The Outcome gives test counts from the XML reports.

## Preserves
No store changes, so no 0.7 approval property is touched: creation tokens, claim validity, owner-tagged pending slots, atomic commit with TTL, sweeps, bounded locks, the two cluster codes, audit-first decisions and `ApprovalView` status. The rotation refusal reuses `CLUSTER_OPERATION_REFUSED`, so it reaches callers by the 233 path.

## Out of scope
- Using the sealer or keyed names in any store, or changing `PrivacyCluster` (tasks 221 to 229).
- Spring properties and key resolution from `SecretKeyProvider` (task 222).
- Member cards and the card-based `WriteKeyPolicy` (tasks 227 and 229).
- Sealed links themselves. Only the HKDF helper is shared.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Key material in tests is generated or fixed test bytes, never a real secret. Identifiers are invented and neutral.
- Say "supports", never "compliant".
- JDK `javax.crypto` only. No new dependency, no Enterprise feature (D-0.4.1-A).
- No sleeps. Anything time-dependent takes an injected clock.
