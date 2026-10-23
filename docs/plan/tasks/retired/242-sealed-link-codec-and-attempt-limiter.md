# 242 — Build the sealed-link codec, link-key derivation and the failed-attempt limiter

**Repo:** `.`
**Release:** 0.8.0 (links stay off by default through 0.9, sealed-links OD-7)
**Base branch:** `origin/main` once task 220 (`plan/0.8-sealed-cluster`) has merged
**Depends on:** 220 (`LabelledHkdf` in `core.crypto`, which 220 builds to serve the link design unchanged)
**Wave:** 3 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
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
- `LinkAttemptLimiter` SPI: `boolean locked(String counterKey)`, `void recordFailure(String counterKey)`, `default boolean clusterWide()` returning `false` (255's startup check reads it; the sealed-cluster Hazelcast limiter of task 264 returns `true`); `InMemoryLinkAttemptLimiter(int limit, Duration window, Clock clock)`, which is not cluster-wide.
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
- [ ] Limiter: only counted reasons are recorded; at `limit` failures the counter key is locked, including for valid links; another principal's key and the same principal in another scope are unaffected; increments do not extend the window. Whether the window has ended is decided by comparing the stored window start with the injected `Clock`, never by an entry expiring. Sixteen threads recording concurrently lose no count and lock exactly at the limit. The counter key contains neither the scope id nor the principal id. Memory is bounded: an entry past its window is dropped on the next access or sweep. `clusterWide()` is `false` for `InMemoryLinkAttemptLimiter` and for an implementation that does not override it.
- [ ] No token, payload, chained key, fingerprint or key byte appears in an exception message, `toString()` or log line (sentinel tests in every failure path).
- [ ] `LinkArchitectureTest` (new file) fails when a class outside `core.link` and the cluster sealing package of task 220 calls `Cipher.getInstance` with an `AES/GCM` transformation; when `core.link` depends on `data-prism-reidentification` or `data-prism-hazelcast`; and when a class in `mcp` or `connectors` depends on `core.link`. Nested fixtures prove each rule can fail.
- [ ] A whole-component bug hunt over `core.link` (concurrency of the limiter, oracle uniformity, boundary leaks) runs before merge; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-architecture -am verify` passes.

## Out of scope
- A shared, cluster-wide limiter (OD-08-2 (a); sealed-cluster task 264, which implements this SPI).
- Building links from records (251), following them (252), paging `list_sources` (250).
- Spring properties and key resolution from configuration (254, 255).
- The collection-page and group payloads (0.9).
- The timing soak test of sealed-links obligation 10 (252 writes it, 263 runs it).

## Outcome

Branch `task/242-sealed-link-codec`. Tests written first: `LinkCodecTest`, `LinkKeysTest`, `InMemoryLinkAttemptLimiterTest` (core `link` package) and `LinkArchitectureTest`; none compiles on the base commit. A known-answer file, `src/test/resources/link/vectors.properties`, pins the derived keys (checked against an independent Python HKDF), one sealed follow link and one sealed page link.

Decisions made after review (coordinator, on the fail-closed rule):

- **Scope and token end.** `open` refuses as `EXPIRED` when `now >= scopeExpiry` or `now >= tokenExpiry`, with no skew on those two. `sealDetailed` omits (`ALREADY_EXPIRED`) when the computed expiry is not after now. Task 252 still re-checks the live session; this is defence in depth.
- **Key allow-list.** `open` verifies only ids in `heldKeyIds()` (the current id plus the supplied set, read on every call). Any other id takes the dummy-key path and is `UNKNOWN_KEY`. The pseudonymisation key id is refused at construction (`LINK_KEY_SHARED`) if the held set contains it, and is `UNKNOWN_KEY` if it appears later. The default held set is the current id only. A supplier that returns null or throws is treated as "current id only", so `open` still refuses instead of throwing.
- **Error codes follow the design** (sealed-links.md 415-421). A bad attempt limit, attempt window, clock skew, `maxLength` or key id is `INVALID_LINK_LIMIT`, with the detail after the code. The design names no key-id code. A non-positive lifetime is `INVALID_LINK_LIFETIME`.
- **No caps beyond the design.** The design gives no maximum lifetime or window, so none is enforced. `LinkLayout.plus` saturates at `Instant.MAX`, and tests show a `Long.MAX_VALUE`-second window constructs, counts and locks at the limit, and a huge lifetime is bounded by the scope and token ends.

APIs added beyond the brief, and why:

- `LinkCodec.sealDetailed` returning `SealResult` (`Issued` or `Omitted(OmitReason)`): the brief asks for the empty result "with the reason"; `seal` delegates to it.
- `LinkOpenResult.Opened.keyId`: a page verifier recomputes the catalog tag under the key the link names, so a walk begun before a rotation still verifies.
- `LinkKeys.forScope(scopeId[, keyId])` returning `ScopeKeys`: `fingerprint` and `catalogTag` live there, not on `LinkKeys` as the brief's shape says, because they are per-scope. `attemptCounterKey` and `heldKeyIds` stay on `LinkKeys`.
- The four-argument `LinkKeys` constructor, with a `Supplier<Set<String>>` of held ids: `SecretKeyProvider` has no listing method, so task 255 must supply the real set.

Bug hunt (limiter concurrency, oracle uniformity, boundary leaks) was run inline over `core.link`. It found and fixed:

- A hostile unauthenticated expiry value threw in `Instant.ofEpochSecond`; it is now clamped before use.
- The `LinkKeys` constructor zeroed the provider's returned array, which could mutate a provider's own copy; removed, with a test.
- `Instant.plus` overflow in lifetime, skew and window arithmetic; now saturating.
- A key id the provider held but the member did not list could verify; now the allow-list refuses it.
- A past-the-end scope or token could be sealed and opened; now refused (decision above).

Checked and found sound: the limiter's `compute` and `computeIfPresent` are atomic and a 16-thread test loses no count; the structural checks depend only on public layout, and every refusal after the length and structure checks runs the same key derivation and AEAD, including for an unknown key (a key id the member holds also costs a provider fetch and a SHA-256 of the material, which an unknown id does not; see round 2); no token, payload, key byte or provider message reaches an exception message or `toString` (sentinel tests).

Notes for later tasks:

- Rotation and the per-principal limit: the counter key is derived under the current key id, so during phase 2 of a rotation the counts split across key ids, both for the single-node limiter and for task 264's cluster limiter. A principal can have up to one limit per key id in flight, and 264 must decide whether to accept that or key the counter on something stable.
- Task 255 must supply `heldKeyIds`; until then only the current key verifies. The supplier should not return null or throw (both fail closed to current-only, silently).
- Forged and genuine tokens do different work after authentication: only an authenticated token has its payload parsed. The difference is small; 252's timing soak should cover it.
- Amendment for when 264 is planned: sealed-links.md line 286 describes the counter entry as having a "time-to-live". The in-memory limiter decides window end by comparing the stored window start with the injected clock, never by an entry expiring, so a shared limiter must do the same.
- The unpadded worst-case follow link is 615 characters (the design's 616 counts padding); the `TOO_LONG` omit in `sealDetailed` is unreachable with validated settings and is defensive.

### External review of PR #154 — fixes (branch `fix/242-external-review`)

- **Held key ids.** `heldKeyIds()` lists only ids that resolve to at least 32 bytes right now, through the same check `master()` uses. At construction a declared held id that does not resolve refuses `LINK_KEY_UNRESOLVED`, and one that is under 32 bytes refuses `LINK_KEY_WEAK`; neither message names a key or reference. Null entries in the held set are treated as absent.
- **Pseudonymisation key.** `pseudonymisationKeyId` is required: a null refuses `LINK_KEY_SHARED` (the setting is named, no value). If the pseudonymisation key cannot be resolved at construction, construction refuses `LINK_KEY_UNRESOLVED` naming the setting and no reference; there is no id-only fallback. The construction keeps only a SHA-256 digest of the pseudonymisation key's material, never the material. Every held key that resolves is compared with that digest by `MessageDigest.isEqual`, at construction (refuses `LINK_KEY_SHARED`) and again on every `heldKeyIds()` and `lookup`, because the held set is a live supplier: a key added later with the pseudonymisation bytes is not held, is `UNKNOWN_KEY` (counted) at open, and never appears in `heldKeyIds()`. The resolved pseudonymisation bytes are not zeroed, because a provider may return its own array (the earlier bug-hunt finding).
- **Provider outage at open time.** A held id whose key the provider cannot return (failure, null, or under 32 bytes) is refused as the new `KEY_UNAVAILABLE`, uncounted. The dummy-key AEAD still runs, so timing is unchanged. An id not held stays `UNKNOWN_KEY`, counted.
- **Post-authentication parse failure.** An authenticated payload that does not parse is `MALFORMED` but uncounted.
- **Seal with a vanished current key.** `sealDetailed` returns `Omitted(KEY_UNAVAILABLE)` instead of throwing.
- **Follow payload.** `DIRECT` with a resolver, `TRANSLATED` without one, and an empty namespace are refused at construction.
- **Architecture rule.** The cipher rule sees `Cipher::getInstance` method references, and the sealer package match is the package or a sub-package, so `core.linkx` is outside.
- **Limiter test.** `aLockedPrincipalIsRefusedEvenWithAValidLinkAtTheCallersGate` now drives a gate that checks the lock, opens and records, and fails if the gate ignores the lock. The gate is test code, because the production gate arrives in 251 and 252.

Design amendments for the scribe: `LinkReason.KEY_UNAVAILABLE` (uncounted, audit value `REFUSED:LINK_KEY_UNAVAILABLE`) and `SealResult.OmitReason.KEY_UNAVAILABLE` are not in sealed-links.md section 4. The follow-payload rules (resolver present only for `TRANSLATED`, namespace 1 to 32 bytes) are now enforced in code.

Recorded for later tasks, with no code change here:

- The codec seals the issue time and the link id the caller supplies. Callers 251 and 252 must take the issue time from the clock and the link id from a random source.
- The counter key derives from the current key id, so a rotation lifts existing locks. Tasks 264 and 255 must decide a stable counter key.
- The limiter contract is check then record, so concurrent attempts can exceed the limit. Settle this before 264.
- The in-memory limiter's map is unbounded, and it sweeps on the request thread every 256 failures. Bound it in 264 or 255.
- `Refused.counted()` is authoritative; `LinkReason.counted()` is only the default (a post-authentication `MALFORMED` is uncounted). Tasks 251 and 252 must gate the limiter on `refused.counted()`, never on `reason.counted()`.

### External review of PR #154 — round 2

- **Base64 test.** The old test could not fail: its token was a multiple of 3 bytes and it accepted `AUTH_FAILED`. It now seals a token whose raw length is not a multiple of 3, builds every variant that decodes to the same bytes by changing the spare trailing bits, and asserts exactly `MALFORMED`. With the canonical-encoding check removed in `LinkCodec.decode`, it fails.
- **Binding bounds.** A scope id or principal id over 65535 UTF-8 bytes (the associated-data item limit; the design gives no tighter bound) makes `open` refuse `MALFORMED`, uncounted, because the session and not the token is at fault, and makes `sealDetailed` omit with the new `BINDING_TOO_LONG`. Neither throws. `LinkBinding` still constructs, so the caller can build one. Tested at 65535 (works) and 65536 (refused and omitted). Note for 251/252: `attemptCounterKey` still throws for an id over the limit, so the caller must check bounds before it.
- **Long target.** A follow target over 64 bytes now constructs and `sealDetailed` omits it with the new `TARGET_TOO_LONG`. `FollowPayload` still refuses an empty target.
- **Limiter gate test.** `uncountedRefusalsLeaveTheCounterUntouchedAtTheGate` drives post-authentication `MALFORMED` and `KEY_UNAVAILABLE` through the test gate, which records on `refused.counted()`, and shows the counter is untouched after 10 of each. It fails if the gate records on `reason.counted()`.
- **Key id mismatch.** The `LinkCodec` constructor refusal now starts `INVALID_LINK_LIMIT:`, as for the other settings errors.
- **Uniformity wording.** What is uniform is the key derivation and the AEAD. What differs is that a held key id costs a provider fetch and a SHA-256 of the material, while an unknown id costs neither; key ids are public, so this reveals nothing secret. Javadoc and comments now say so. For 255: a forged token naming a held id costs a key-store round trip per attempt, so consider caching resolved held keys.
- **Older pseudonymisation keys.** The shared-material check covers only the current pseudonymisation key, not older ones that live scopes still pin. Recorded for 255.
- **Left alone, by instruction.** The limiter's check-then-record contract, its map bound and the counter-key derivation are an open owner decision and are unchanged.

Design amendments for the scribe: `OmitReason.BINDING_TOO_LONG` and `TARGET_TOO_LONG`.

### External review of PR #154 — round 3

- **Sentinel tests.** Three could not fail and now can.
  - `forScopeNamesNoReferenceWhenAHeldKeyIsUnavailable` declares `k2` held, then removes it so the provider throws with a sentinel in its message, and asserts the refusal text and cause exclude it. It fails when the provider's message is passed into the exception.
  - `payloadConstructorsNameNoValue` puts the secret in the namespace, resolver name and entity type filter, where validation builds the message, and fails when a message includes the value.
  - `anUnknownKeyNeverSurfacesTheProvidersMessage` is deleted. A `Refused` holds only an enum and a boolean, so its `toString` cannot carry text; the provider-message guarantee is covered by the key test above and by the outage test, which asserts the reason and that the refusal has no text.
- **Associated data in isolation.** `LinkVectors.reauthenticate` seals the same payload under the same key but changes only the associated data (another version byte, another scope id, or the item left out). `theVersionIsInTheAssociatedData` and `theScopeIsInTheAssociatedData` assert `AUTH_FAILED` for each, and `anIndependentlyBuiltTokenWithTheFullAssociatedDataOpens` asserts the unchanged form opens. Dropping the version from the codec's associated data, and dropping the scope, each fail these three named tests as well as the vectors.
- **Nits.** Lines over 120 characters wrapped and fully-qualified names inlined in `core.link` (main and test). `LinkBinding`'s constant is first and `LinkKeys`' nested types sit before `ScopeKeys`. The JCA `GeneralSecurityException` from sealing and the `NoSuchAlgorithmException` from SHA-256 are now kept as causes, because they carry an algorithm or key-size message and no key material. The provider's own exceptions are still dropped, because their messages may name a reference, and the AEAD tag failure in `decrypt` is expected control flow with nothing to keep.
- **Not touched.** The limiter, `AttemptSubject` and the counter key (task 269).

### External review of PR #154 — round 4

All Low; no Critical, High or Medium. Branch `fix/242-review-4`.

- **MAC purpose labels.** The fingerprint key MACed three kinds of input with no label, so `catalogTag([scope, principal])` equalled the counter key. Every MAC input now begins with a length-prefixed purpose label: `dataprism/link/v1/fingerprint` (fingerprint), `dataprism/link/v1/catalog-tag` (catalog tag) and `dataprism/link/v1/attempt` (attempt counter key, the label 269 will keep). The known-answer vectors `fingerprint`, `catalog.tag` and `counter.key` changed; the tokens and keys did not. They were derived by an independent script (Python `hmac`, sharing no code with the project) and pinned, and the tests also check them against `LinkVectors`. Section 3 of `sealed-links.md` records the labels. `attemptCounterKey` was edited only to add the label, as the brief required; 269 still owns its removal. The limiter classes are untouched.
- **Dummy key.** `aTokenSealedUnderThePublicDummyKeyNamingAnUnheldKeyIdIsRefused` builds a link that decrypts under the public dummy key and names the unheld id `kz`, and asserts `UNKNOWN_KEY` (counted). With the `knownKey` guards removed (`authenticated = plain != null` and the `UNKNOWN_KEY` return) it is `Opened`, and the test fails.
- **Lifetime.** A lifetime under 1 s now refuses with `INVALID_LINK_LIFETIME` (the codec rounds expiry down to whole seconds, so every link would have been omitted `ALREADY_EXPIRED`).
- **Coded refusals.** `LinkConfigurationException extends IllegalArgumentException` has `code()`, and its message keeps the `CODE: ` prefix. Every construction refusal in `LinkKeys`, `LinkSettings` and `LinkCodec` uses it. This changes the type of the three `LINK_KEY_*` refusals from `IllegalStateException` to this `IllegalArgumentException` subtype. `LinkKeys.forScope(scope, keyId)` still throws `IllegalStateException` (a runtime lookup, not construction). For 254 and 255: catch `LinkConfigurationException` and read `code()`.
- **Clamp.** `aHostileExpiryIsClampedAndNeverThrows` forges `Long.MAX_VALUE`, `Long.MIN_VALUE` and `+-2^62` headers and asserts `AUTH_FAILED` without an exception; `anAuthenticatedFarFutureExpiryIsReportedAsTheClampedValue` seals a valid link with expiry 2^50 (independently, in `LinkVectors.rebind`) and asserts `Opened.expiry` is 2^40. Without the clamp both fail with a `DateTimeException` or the wrong value.
- **Nits.** `Opened.expiry` is documented as the clamped value. `LinkPayload.Check` (public by interface rule) is now the package-private top-level `PayloadCheck`. A null token is `MALFORMED`, uncounted, as are empty scope or principal ids (`LinkBinding.withinBounds` now requires non-empty; `sealDetailed` omits with `BINDING_TOO_LONG`, whose documentation now says empty or too long). An empty string token stays counted. The `Optional` record components (`FollowPayload.resolverName`, `CatalogPagePayload` filters, `LinkSettings.lifetime`) are unchanged: `docs/conventions.md` has no rule on them, so that needs a decision.

