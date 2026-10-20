# 242 — Build the sealed-link codec, link-key derivation and the failed-attempt limiter

**Repo:** `.`
**Release:** 0.8.0 (links stay off by default through 0.9, sealed-links OD-7)
**Base branch:** `origin/main` once task 220 (`plan/0.8-sealed-cluster`) has merged
**Depends on:** 220 (`LabelledHkdf` in `core.crypto`, which 220 builds to serve the link design unchanged)
**Wave:** 3 (combined 0.8 graph of 213, 218 to 231 and 238 to 263; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/** *(new package)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/** *(new)*
- data-prism-core/src/test/resources/link/** *(new: known-answer vectors)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/LinkArchitectureTest.java *(new)*

## Goal
A link is the one new authority object in 0.8 and the one place new cryptography enters a privacy path. Build it as a self-contained codec: AES-256-GCM under per-scope keys derived from a dedicated link key (sealed-links OD-1 (a')), a fixed binary layout with every authority-bearing field in the associated data, fixed 64-byte padding of the chained key, a verification that performs the same work for every invalid input and classifies the reason without an oracle, and a per-principal failed-attempt limiter (OD-8). Nothing here reads a record, calls a source or emits a link; tasks 250, 251 and 252 use it.

## Context
- docs/plan/design/sealed-links.md sections 2 (threats T1 to T17, oracle analysis), 3 (algorithm, key derivation, rotation, layout, size, expiry and skew), 4 (uniform refusal, limiter) and 7 (obligations 1 to 9, 11, 18, 19, 22, 26), as corrected by 238 and register OD-200-4 and OD-200-8.
- docs/plan/design/batch-and-bulk-follow.md Part A.4 and A.5: the catalog-page payload (relation `0x02`) and the four post-authentication reasons; 3.2 "Relation bytes".
- Task 220's brief, "Required shape": `LabelledHkdf.extract`, `expand` and the `info` encoder. Use them; do not write a second HKDF.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/SecretKeyProvider.java: `secret(keyId)` throws on an unknown id; never fall back (HISTORY, "S2a key rotation").
- docs/conventions.md "Tests": an injected `Clock`, no sleeps.

## Required shape (later tasks read these)
- `LinkRelation`: `DIRECT 0x00`, `TRANSLATED 0x01`, `CATALOG_PAGE 0x02`, and reserved `COLLECTION_PAGE 0x03`, `GROUP 0x04` with no payload type in 0.8.
- `LinkCodec`: `seal(FollowPayload | CatalogPagePayload, LinkBinding)` returns `Optional<SealedLink>` (empty when the key is over 256 bytes or the encoding exceeds `maxLength`, with the reason); `open(String token, LinkBinding, Set<LinkRelation> accepted)` returns `Opened` (valid payload) or `Refused(LinkReason, boolean counted)`. `LinkBinding` carries scope id, principal id, scope expiry and token expiry, all from the session.
- `FollowPayload`: link id, issue time, key namespace, optional resolver name, chained key, target label, relation. `CatalogPagePayload`: link id, issue time, entity-type filter, key-namespace filter, label kind, page size, last label, catalog tag.
- `LinkKeys`: per-scope `fingerprint(byte[])` (12 bytes, for item ordering and duplicate collapse), `catalogTag(List<String> labels)` (16 bytes), `attemptCounterKey(scopeId, principalId)`; `heldKeyIds()` for health (255).
- `LinkReason`: `MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY`, `AUTH_FAILED` (counted); `EXPIRED`, `ISSUED_IN_FUTURE`, `WRONG_TOOL`, `TARGET_NOT_PERMITTED`, `RELATION_UNDECLARED`, `PAYLOAD_MISMATCH`, `ORIGIN_DRIFT`, `FILTER_MISMATCH`, `LABELS_CHANGED`, `CATALOG_CHANGED` (never counted). Each maps to the audit value `REFUSED:LINK_<REASON>`.
- `LinkAttemptLimiter` SPI: `boolean locked(String counterKey)`, `void recordFailure(String counterKey)`; `InMemoryLinkAttemptLimiter(int limit, Duration window, Clock clock)`.
- `LinkSettings`: key id, lifetime (a duration or `scope`), max length, bind caller, clock skew; validated in its constructor (bound in 254 and 255).

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] Layout is exactly that of `sealed-links.md` section 3 with the OD-200-4 caps (target at most 64 bytes, namespace and resolver name at most 32), version `0x01`, key id at most 16 characters of `[A-Za-z0-9_-]`. A relation byte from `0x05` to `0xFF` is `MALFORMED`. The associated data is the length-prefixed concatenation of version, key id, expiry, target, relation, scope id and caller tag.
- [ ] Key derivation uses `LabelledHkdf` with a 32-zero-byte salt, one expand block, and `info` of the label (`dataprism/link/v1/seal`, `/fingerprint`, `/caller`) and the scope id. A vector file under `src/test/resources/link/` pins the derived keys, one sealed follow link and one sealed page link for a fixed key, scope, nonce and clock; a test regenerates and compares. Derived keys differ per label and per scope.
- [ ] Construction refuses, with a message starting with the code and naming no key byte or reference: an unresolvable key id (`LINK_KEY_UNRESOLVED`), material under 32 bytes (`LINK_KEY_WEAK`), the same id as the pseudonymisation key (`LINK_KEY_SHARED`), and a `maxLength` below 616 or above 4096 (`INVALID_LINK_LIMIT`).
- [ ] Verification checks the length before decoding (a mutant that decodes first fails a test), then the structure, then always runs AEAD verification, against a fixed dummy key when the key id is unknown, and only then evaluates expiry, issue time and relation as booleans. A token failing authentication is `AUTH_FAILED` whatever its header says (expired expiry, foreign target, other relation): a test with forged headers of each kind proves it.
- [ ] One test per associated-data field (version, key id, expiry, target, relation, scope, caller) changes only that field and gets a refusal. A parameterised test flips one bit at every byte position and gets a refusal for each. Replay across scope, caller (when bound) and target is refused.
- [ ] Expiry is `min(now + lifetime, scope expiry, token expiry)`; `lifetime=scope` never exceeds the scope expiry; each of the three can be the binding one. A link is expired when `now >= expiry + skew`, and `ISSUED_IN_FUTURE` when its issue time is after `now + skew`. Boundary tests at `skew - 1s`, `skew` and `skew + 1s`, with skew 0 and 30 seconds. Skew never extends the scope or the token.
- [ ] Rotation: verification uses exactly the key id the token names, never another. A provider holding one key asked for a different id refuses as `UNKNOWN_KEY`. A two-member simulation (A holds old and new with current old; B holds both with current new) issues and verifies both ways with no `UNKNOWN_KEY`; a member holding only the old key refuses a new-key link as `UNKNOWN_KEY`.
- [ ] Padding: chained keys of 1 and 64 bytes give tokens of equal length, 65 bytes gives the next bucket, and 257 bytes gives no link. The raw bytes before base64url contain neither the chained key nor any subject id. A test opens the payload with the test key and finds only the padded chained key.
- [ ] Size: a test recomputes the worst-case follow link (616 characters) and the worst-case page link from the layout constants and asserts both are at most the 1024 default.
- [ ] 100,000 seals under one derived key produce no repeated nonce.
- [ ] Limiter: only counted reasons are recorded; at `limit` failures the counter key is locked, including for valid links; another principal's key and the same principal in another scope are unaffected; increments do not extend the window. Whether the window has ended is decided by comparing the stored window start with the injected `Clock`, never by an entry expiring. Sixteen threads recording concurrently lose no count and lock exactly at the limit. The counter key contains neither the scope id nor the principal id. Memory is bounded: an entry past its window is dropped on the next access or sweep.
- [ ] No token, payload, chained key, fingerprint or key byte appears in an exception message, `toString()` or log line (sentinel tests in every failure path).
- [ ] `LinkArchitectureTest` (new file) fails when a class outside `core.link` and the cluster sealing package of task 220 calls `Cipher.getInstance` with an `AES/GCM` transformation; when `core.link` depends on `data-prism-reidentification` or `data-prism-hazelcast`; and when a class in `mcp` or `connectors` depends on `core.link`. Nested fixtures prove each rule can fail.
- [ ] A whole-component bug hunt over `core.link` (concurrency of the limiter, oracle uniformity, boundary leaks) runs before merge; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-architecture -am verify` passes.

## Out of scope
- A shared, cluster-wide limiter (OD-08-2; cluster work).
- Building links from records (251), following them (252), paging `list_sources` (250).
- Spring properties and key resolution from configuration (254, 255).
- The collection-page and group payloads (0.9).
- The timing soak test of sealed-links obligation 10 (252 writes it, 263 runs it).
