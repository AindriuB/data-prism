# 269 — Settle the sealed-link attempt limiter's contract: atomic record-and-decide, a bounded map, and a counter key that survives rotation

**Repo:** `.`
**Release:** 0.8.0 (links stay off by default through 0.9, sealed-links OD-7)
**Base branch:** the 0.8 integration branch, at a commit that contains 242 and both rounds of its external-review fixes (`record/0.8-wave3` at 7cf7c1a3 or later, PR #154).
**Depends on:** 242 (`core.link`: `LinkAttemptLimiter`, `InMemoryLinkAttemptLimiter`, `LinkKeys`, `LinkCodec.open`, `LinkBinding.withinBounds`, `LinkOpenResult.Refused.counted()`)
**Decisions:** owner decision 2026-10-22: settle the limiter contract in this separate task, before 251 and 264 build on it. **Decided by the owner 2026-10-22:** OD-269-1 (a), OD-269-2 (a), OD-269-3 (a) and OD-269-4 (coordinator: 269 added to 250's Depends on). "Required shape" is written to these decisions. Revised 2026-10-23 after the external review of PR #155. The review did not change any decision. It replaced the two-step gate with one call, stated the real bounds and the ceiling's consequence, and fixed the sweep and the binding bounds. The owner accepted the ceiling consequence under OD-269-3 on 2026-10-23. Revised again 2026-10-23 after a fourth external review, with no decision changed. The changes are a lossless `canonicalText()` for 264, a package-private `LinkOpener` test seam, `OPEN_FAILED`, audit-only causes on `Exhausted` (so `locked` becomes `lockState`, and `Admission` gains `CEILING` and `UNAVAILABLE`), one marker per entry with bounded head examination, a deterministic ceiling race, method references in the bypass rule, and what 264 may relax.
**Wave:** 4 (combined 0.8 graph of 213, 218 to 231 and 238 to 269; 203 and 206 to 211 counted as landed). It runs alongside 223, 241 and 248; it owns none of their files.
**Serialised with:** none in wave 4. 250, 251, 252, 255 and 264 depend on this task, directly or through 251.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptLimiter.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiter.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/AttemptSubject.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptGate.java *(new; `GateDecision` and `ExhaustedCause` are nested)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkOpener.java *(new, package-private)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkKeys.java *(removing `attemptCounterKey` and the Javadoc that mentions it only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiterTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkAttemptGateTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/AttemptSubjectTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkKeysTest.java *(the counter-key test and the vector-writer line only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkVectors.java *(the counter-key entry only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkTestSupport.java *(insertions only)*
- data-prism-core/src/test/resources/link/vectors.properties *(replace `counter.key` with `attempt.subject.canonical` and `attempt.subject.canonical.text`)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/LinkArchitectureTest.java *(insertions only: the rules below and their nested fixtures)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/hazelcast/archfixture/LinkLimiterFloorFixture.java *(new; a test fixture only, in the Hazelcast package so the bypass rule's one exception can be proven; imported explicitly, never by package scan)*
- docs/plan/design/sealed-links.md *(section 3 "Fingerprinting" row; section 4: the round-2 "Binding bounds" bullet and the post-authentication `MALFORMED` bullet (about lines 277 and 280), and "Attempt limiter"; section 6 boundary 3; section 7 obligation 22; only as "Design amendments" below says)*
- docs/plan/design/batch-and-bulk-follow.md *(Part A "What happens in a call", items 1 and 2 only, as "Design amendments" below says)*

## Goal
Task 242 built the limiter as "check `locked`, then `recordFailure`". That lets concurrent forged attempts get past the limit. Its map has no ceiling and is swept on the request thread. Its counter key is derived from the current link key, so a link-key rotation lifts every lock, a shared limiter splits counts during the roll, and `attemptCounterKey` throws when the current key cannot be read. This task replaces all of that:
- an atomic contract that a Hazelcast entry processor can also honour (264);
- a fixed ceiling that fails closed without letting an attacker lock out honest users;
- a sweep whose cost per call has a fixed bound;
- a counter identity that does not depend on the link key.

The only way to open a token from outside `core.link` is one gate call, which owns the whole sequence. Callers 250 and 252 therefore cannot skip the lock or count wrongly.

## Context
- docs/plan/design/sealed-links.md:
  - section 4 "Attempt limiter": counted reasons, per principal within a scope, a fixed window decided by the stored start and the clock, a lock that refuses valid links too;
  - section 4's round-2 amendment on binding bounds: an id over 65,535 UTF-8 bytes is `MALFORMED`, uncounted;
  - section 3 "Key derivation" (the `fingerprint` label is used today for the counter key) and "Rotation";
  - section 6 boundary 3, and section 7 obligation 22.
- docs/plan/tasks/retired/242-sealed-link-codec-and-attempt-limiter.md, Outcome: "Notes for later tasks" and the round 1 and round 2 review sections. Gate on `Refused.counted()`, never on `LinkReason.counted()`. `LinkBinding.withinBounds()` is package-private in `core.link`, and the gate uses it.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiter.java: keep the window logic (`ended`, saturating `LinkLayout.plus`). The `SWEEP_EVERY` full-map sweep and the public `sweep()` go.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkKeys.java, `attemptCounterKey`: it calls `forScope(scopeId)`, which throws `LINK_KEY_UNRESOLVED` when the current key is unreadable, and it throws for an id over the bound.
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiterTest.java: the existing cases, including the window, isolation, sixteen threads, the `Long.MAX_VALUE` window, and the test gates `aLockedPrincipalIsRefusedEvenWithAValidLinkAtTheCallersGate` and `uncountedRefusalsLeaveTheCounterUntouchedAtTheGate`. Port them to the new SPI, and the gate cases to `LinkAttemptGate`. No assertion may be weakened. The one exception: tests that call the removed public `sweep()` are rewritten to rely on the per-call examination, with assertions at least as strong (an exact `size()` after a stated number of calls).
- docs/plan/design/cluster-security.md 2.3.2 and 4.6: the name key is separate from the cluster key, is the same on every member, and does not rotate in the rolling procedure. Each map gets its own name subkey (`dataprism/cluster/v1/name/<map>`).
- docs/plan/design/batch-and-bulk-follow.md Part A "What happens in a call", items 1 and 2: they describe the counter key and the lock in pre-269 terms.
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/seal/KeyedNames.java: `mapKey(String map, String canonical)` takes a `String`, hence `canonicalText()`.
- docs/conventions.md "Tests": an injected `Clock`, no sleeps. Use latches for ordering.

## Owner decisions (decided 2026-10-22)

**OD-269-1: the atomic contract. Decided (a), 2026-10-22, owner.**
- (a) **Verify, then one atomic record-and-decide (recommended).** The gate checks `locked` first, as a cheap pre-check. It then opens the token, which is pure CPU and no I/O. On a counted refusal it calls `recordFailure`. That call atomically opens or extends the window, increments the count, and returns whether the attempt is still within the limit (`count <= limit` after the increment). An attempt over the limit is answered `LINK_ATTEMPTS_EXHAUSTED`, not `LINK_INVALID`, so at most `limit` counted attempts per window ever learn their verification result, however many run at once. There is one write per failed attempt, with no reservation and no undo. Concurrent valid links are never penalised, and a crash cannot leak a reservation. In Hazelcast this is one entry processor that returns the new count.
  - *Correction, 2026-10-23 (review of PR #155; the decision is unchanged).* "At most `limit` ... however many run at once" holds only for **failures**. At most `limit` counted failures per window are answered `LINK_INVALID`. A guess that would succeed is answered `PROCEED` if its `locked` pre-check ran before the count reached `limit`. So the number of guesses that can reveal a success is at most `limit` plus the attempts for that subject already past the pre-check when the lock engaged. That second term is bounded by the caller's concurrency for one principal, not by this contract. Against a 128-bit tag, the extra term is immaterial.
- (b) Reserve, then commit or release (`tryAttempt`). This is exact, but every attempt writes twice. Honest concurrent follows use up reservations, so a principal making 20 concurrent valid follows is refused. A member that dies while holding a reservation leaks it, which needs its own expiry in 264.
- (c) Record first, then undo on success. Every valid follow writes twice. Honest concurrency is penalised as in (b). The undo has to find the same window, or it decrements a new one.

**OD-269-2: the counter key. Decided (a), 2026-10-22, owner.**
- (a) **The SPI takes the subject; each implementation keys it under its own non-rotating key (recommended).** `LinkAttemptLimiter` takes `AttemptSubject(scopeId, principalId)`. The in-memory limiter keys its map by `HMAC-SHA256(k, subject.canonical())`, truncated to 16 bytes, where `k` is 32 random bytes drawn per instance at construction. 264 keys its map by `KeyedNames` under the cluster **name key**, as every other shared map is keyed. `LinkKeys.attemptCounterKey` is deleted, so the limiter never reads link-key material. Why the name key's rotation is acceptable: it never rotates in the rolling procedure, so every member derives the same key throughout a link-key or cluster-key rotation. Changing it is a planned full restart that drops all cluster state on every member at once, so counts reset together and never split. The reset is worth at most `limit` further guesses per principal against a 128-bit tag. The per-instance random key in memory is acceptable because in-memory counts already die with the process. This also removes the throw: a link-key outage cannot affect the limiter.
  - *Clarification, 2026-10-23.* `k` is drawn from `SecureRandom`. `canonical()` begins with a fixed domain label, so a counter input can never equal a fingerprint input or another map's keyed-name input, even under a shared key.
- (b) A dedicated HKDF label, `dataprism/link/v1/attempt`, over the pseudonymisation key. That key is shared by every member and rotates only with a full re-pseudonymisation. This reverses 242's review decision that `LinkKeys` keeps only a digest of the pseudonymisation key, and couples links to the identity key.
- (c) A separate, non-rotating "attempt key" property. That is one more secret for operators to provision, for no gain over the name key.

**OD-269-3: the ceiling, and what happens at it. Decided (a), 2026-10-22, owner.**
- (a) **Recommended.** The default is 100,000 entries per instance, roughly 20 MB at most. It is a constructor parameter, with no `dataprism.*` property in 0.8. At the ceiling, a counted failure for a subject **not already in the map** is answered over the limit (`LINK_ATTEMPTS_EXHAUSTED`) and is not stored. Subjects already in the map keep counting, and **no live entry is ever evicted**. An attacker who fills the map therefore cannot lift anyone's lock (eviction would) or lock anyone out. `locked` for an absent subject is `false`, so honest users with valid links proceed. Only new forgers get no verification answer, and that is the fail-closed direction.
  - **Consequence accepted by the owner, 2026-10-23 (raised in the review of PR #155; the decision is unchanged).** While the map is full, a new subject is never stored, so it is **never locked**. Each of its failed guesses gets `LINK_ATTEMPTS_EXHAUSTED`, but a guess that succeeds gets `PROCEED`. That subject therefore has **unlimited guesses** against the 128-bit tag for as long as the ceiling holds. The attacker has to keep the map full: one live entry per scope-and-principal pair, refreshed every window. The chance of success per guess stays 2^-128, so the practical risk is negligible. Still, the per-principal limit does not hold for such a subject, and the design text says so. The alternatives all lock or refuse honest users: refuse every `PROCEED` for an unstored subject, or evict. The owner accepted this on 2026-10-23: deployments are expected to be internal, so an attacker reaching the MCP endpoint is unlikely, and the mechanism is still worth having against a breach of the internal network or a public-facing deployment. A 2^-128 per-guess risk is acceptable against alternatives that lock out or refuse honest users.
- (b) As (a), but with a property `dataprism.links.invalid-attempt-capacity` bound by 255.
- (c) Evict the oldest entry. Rejected: an attacker can lift a locked principal's lock by flooding the map.

**OD-269-4: an edge from 250. Decided 2026-10-22, coordinator: done.** 250 counts page-link failures in the same limiter but does not reach this task through 251. `269` is added to 250's Depends on, with a dated Context bullet in 250 (the coordinator's edit, since this task may not edit that file).

**Binding bounds (planner's choice under the review, 2026-10-23): bound, do not hash.** `AttemptSubject` uses the same bound as `LinkBinding`: each id at most 65,535 UTF-8 bytes and non-empty. Reasons:
- One rule covers both, and the codec already refuses such a binding as `MALFORMED`, uncounted, because the session is at fault, not the token.
- Hashing each part would accept a binding that the codec refuses anyway.
- Hashing would also add a step that 264 must reproduce exactly.

The gate checks the bounds before anything else and answers `MALFORMED`, uncounted.

## Required shape (later tasks read these; matches the owner's decisions above; revised 2026-10-23 after the fourth review)
- **`AttemptSubject`.**
  - A record `AttemptSubject(String scopeId, String principalId)`. Each id must be non-null, non-empty and at most `LinkBinding.MAX_ID_BYTES` (65,535) UTF-8 bytes; otherwise the constructor throws `IllegalArgumentException` with a message that names no value.
  - `static Optional<AttemptSubject> of(LinkBinding binding)` is empty for a null binding or an id outside those bounds, empty ids included. It never throws.
  - `canonical()` is `LabelledHkdf.lengthPrefixed` of three items: the ASCII label `dataprism/link/v1/attempt` (exactly this; the parallel 242 fix uses the same label for the attempt input), the scope id and the principal id. The label separates counter inputs from fingerprint inputs and other keyed names, and the length prefixes make `("ab","c")` differ from `("a","bc")`.
  - `canonicalText()` is the lowercase hex of `canonical()`, which is lossless. It is the string a `String`-keyed API takes, such as 264's `KeyedNames.mapKey(map, canonical)`. Never build that string by decoding `canonical()` as UTF-8.
  - `toString()` is `AttemptSubject[redacted]`.
- **`LinkAttemptLimiter`** (the SPI):
  - `LockState lockState(AttemptSubject subject)`, where `enum LockState { UNLOCKED, LOCKED, UNAVAILABLE }`. `LOCKED` means `count >= limit` within the current window. `UNAVAILABLE` means the implementation could not decide, and the gate treats it as locked.
  - `Admission recordFailure(AttemptSubject subject)`, where `enum Admission { WITHIN_LIMIT, OVER_LIMIT, CEILING, UNAVAILABLE }`. It is one atomic operation per subject. It opens a window if none is live (absent, or ended), adds one (saturating), and returns `WITHIN_LIMIT` when the new count is at most `limit`, otherwise `OVER_LIMIT`. It returns `CEILING` when the subject is not held and cannot be stored, and `UNAVAILABLE` on any internal failure. Every value but `WITHIN_LIMIT` refuses.
  - `default boolean clusterWide()` returns `false`.
  - The Javadoc lists what every implementation must keep (264 included): atomic per subject; the window decided by the stored start and the clock, never by an entry expiring; no live entry evicted; a new subject at the ceiling gets `CEILING` and is not stored; fail closed through `UNAVAILABLE`, never by throwing.
- **`InMemoryLinkAttemptLimiter`.**
  - Constructors:
    - `(int limit, Duration window, Clock clock)`;
    - `(int limit, Duration window, Clock clock, int maxEntries)`, where `maxEntries < 1` is `INVALID_LINK_LIMIT` and the default is `DEFAULT_MAX_ENTRIES = 100_000`;
    - a package-private test constructor that also takes the HMAC key and an insert probe (a `Runnable` run inside `compute` for a new key, after the slot is reserved and before the entry is stored; a no-op in production).
  - The HMAC key is 32 bytes from `SecureRandom`, drawn once at construction. The map key is the 16-byte truncated `HMAC-SHA256(k, subject.canonical())`, as 32 hex characters.
  - **Entries and the count.** Only `recordFailure` creates entries or re-opens their windows. The entry count is an `AtomicInteger`. Inside `compute`, a new key reserves a slot with `incrementAndGet`; if the result exceeds `maxEntries`, it decrements again and stores nothing. Every removal decrements the count, and re-opening a window in place leaves it unchanged. The ceiling therefore holds exactly under concurrency.
  - **One marker per entry.** The expiry queue holds exactly one marker `(mapKey, windowStart)` per stored entry, appended when the entry is created. So the queue never holds more than `maxEntries` markers.
  - **Examining the queue head**, outside any `compute`. Peek at the head, then:
    - Its window has ended, and the entry's stored start equals the marker's: poll it and remove the entry through `computeIfPresent` returning `null` (the count is decremented after).
    - The entry was re-opened in place (stored start differs): poll and re-append a marker carrying the stored start.
    - The entry is gone: poll and drop the marker.
    - The marker's start is after `now`, because the clock stepped back: poll and re-append it, so the head never stalls.
    - Otherwise (a live head started at or before `now`): stop.
  - **Per-call bounds.**
    - Every `lockState` and `recordFailure` call examines at most **2** heads before its own work.
    - When `compute` finds the map full, the call examines at most **16** more heads, outside `compute`. During that drain a live head is rotated (poll, re-append) instead of stopping the drain. The call then retries the insert **once**; if the map is still full the answer is `CEILING`.
    - Each examination is at most three queue operations (peek, poll, append), and a created entry adds one append. So a call does at most **7** queue operations below the ceiling and at most **55** at it.
    - There is no full-map sweep, no `removeIf` over the map, and no background thread.
- **`LinkOpener`**, a package-private functional interface with the one method `LinkOpenResult open(String token, LinkBinding binding, Set<LinkRelation> accepted)`. Only `LinkAttemptGate` uses it. `LinkCodec` is not changed.
- **`LinkAttemptGate`**, the only way code outside `core.link` opens a token:
  - Constructors: `public LinkAttemptGate(LinkCodec codec, LinkAttemptLimiter limiter)` delegates to a package-private `LinkAttemptGate(LinkOpener opener, LinkAttemptLimiter limiter)` with `codec::open`. Tests use the package-private constructor with a stub, a spy or a latching `LinkOpener`.
  - `GateDecision open(String token, LinkBinding binding, Set<LinkRelation> accepted)` runs, in this order:
    1. A null token, binding or `accepted`, or `AttemptSubject.of(binding)` empty (an empty id or an id over the bound) → `Invalid(MALFORMED)`. Nothing is recorded, neither the limiter nor the opener is called, and nothing throws.
    2. `lockState(subject)` → `LOCKED` gives `Exhausted(LOCKED)`; `UNAVAILABLE` or a throw gives `Exhausted(LIMITER_UNAVAILABLE)`. In both cases the opener is not called. This is where a locked principal's valid link is refused.
    3. `opener.open(...)`. If it throws, the answer is `Exhausted(OPEN_FAILED)`, nothing is recorded, and the gate does not throw.
    4. `Opened` → `Proceed(opened)`. `Refused` with `counted() == false` → `Invalid(reason)`, with nothing recorded.
    5. `Refused` with `counted() == true` → `recordFailure(subject)`:
       - `WITHIN_LIMIT` → `Invalid(reason)`;
       - `OVER_LIMIT` → `Exhausted(OVER_LIMIT)`;
       - `CEILING` → `Exhausted(CEILING)`;
       - `UNAVAILABLE`, or a throw → `Exhausted(LIMITER_UNAVAILABLE)`.
  - `GateDecision` is sealed: `Proceed(LinkOpenResult.Opened opened)`, `Invalid(LinkReason reason)`, `Exhausted(ExhaustedCause cause)`. `enum ExhaustedCause { LOCKED, OVER_LIMIT, CEILING, LIMITER_UNAVAILABLE, OPEN_FAILED }`.
  - **What the client sees, and the audit.** The client never sees the cause: every `Exhausted` is the same `LINK_ATTEMPTS_EXHAUSTED` result. Each decision has `auditValue()`:
    - `Invalid` → `REFUSED:LINK_<REASON>`;
    - `Exhausted` → `REFUSED:LINK_ATTEMPTS_<CAUSE>`, for example `REFUSED:LINK_ATTEMPTS_CEILING`.

    The caller (250, 252) writes that value under the `link` disposition key of the call's audit record, as sealed-links section 4 places the reason (OD-13 shape C; no grammar change). The decision is `DENY:LINK_INVALID` or `DENY:LINK_ATTEMPTS_EXHAUSTED`.
  - Refusals the caller decides after `Proceed` are uncounted and do not touch the limiter: target permission, relation declared, payload checks, catalog tag.
  - The gate never reads `LinkReason.counted()` and never throws. No limiter or opener exception text reaches a decision, an exception or `toString()`.
- **Removed:** `LinkKeys.attemptCounterKey`. The `fingerprint` label keeps its other uses.
- **What 264 may relax, and what it must keep.**
  - **May relax:**
    - The ceiling may be approximate (for example `IMap.size()` read before the processor). The Outcome must state the bound.
    - Memory is bounded by a map time-to-live instead of an expiry queue, so the per-call queue bounds do not apply.
  - **Must keep:**
    - every SPI guarantee listed above;
    - the key: `KeyedNames.mapKey(map, subject.canonicalText())` under the name key;
    - a time-to-live of at least the window plus the clock-skew tolerance, measured from the entry's last write. Expiry can then remove only an entry whose window has ended on every member's clock, so it never lifts a live lock.
    - no size-based eviction policy.

## Acceptance
- [ ] Tests are written first and fail on the base commit (they do not compile against 242's SPI); the Outcome names them. Each failing-input test below names the mutant it kills.
- [ ] **One call, no bypass.** In `LinkAttemptGateTest`, which uses a stub and a spy `LinkOpener` through the package-private constructor:
  - After 20 counted failures for a subject, `open` with a valid token returns `Exhausted(LOCKED)`, and the opener spy records no call. So does a genuine token that would be refused uncounted (`EXPIRED`).
  - Another principal in the same scope, and the same principal in another scope, get `Proceed`.
  - Mutants: skip the pre-check; run the pre-check after `open` but return `Proceed` anyway.
- [ ] **Race, failures (OD-269-1).** With `limit = 20`, 64 threads are released together by a latch. Each calls `open` with a stub opener that returns `Refused(AUTH_FAILED, true)`, for one subject.
  - Each thread's result counts as follows: `Invalid(AUTH_FAILED)` if its failure was recorded `WITHIN_LIMIT`; `Exhausted(LOCKED)` if it was refused at the pre-check, with nothing recorded; `Exhausted(OVER_LIMIT)` if it was recorded over the limit.
  - The test asserts exactly 20 `Invalid` and 64 results in all, and that the stored count equals 20 plus the number of `OVER_LIMIT` results. It is repeated 50 times.
  - A deterministic interleaving: two attempts are latched inside the opener, after the pre-check at count 19, and both fail. Exactly one is `Invalid` and one is `Exhausted(OVER_LIMIT)`.
  - Mutants: `recordFailure` deciding from the count before the increment; the gate deciding from `lockState` instead of the `Admission`.
- [ ] **Race, the stated bound.** A valid token whose pre-check passes at count 19 is latched inside the opener while one forged attempt takes the count to 20. It gets `Proceed`. The test is named for the "limit plus in-flight" bound and documents it; it asserts nothing stronger.
- [ ] **Lock semantics kept.** The window ends at the stored start plus the window, and increments do not extend it. The `Long.MAX_VALUE`-second window still constructs and locks. Sixteen threads recording concurrently lose no count. Mutants: extend the window on increment; key by scope only; count every reason.
- [ ] **Gate on `counted()`.** A stub `Refused(MALFORMED, false)` (post-authentication) and a stub `Refused(KEY_UNAVAILABLE, false)` give `Invalid` and record nothing over ten calls each. A `Refused(AUTH_FAILED, true)` records once. Mutant: gating on `reason().counted()`.
- [ ] **Malformed inputs, one rule.** Each of these gives `Invalid(MALFORMED)` with no limiter call and no opener call (spies), and nothing throws: a null token; a null binding; a null `accepted`; an empty scope id; an empty principal id; a scope id of 65,536 UTF-8 bytes; a principal id of 65,536 UTF-8 bytes. At 65,535 bytes the call proceeds normally. `new AttemptSubject` with any of the bad ids throws `IllegalArgumentException`, and the message contains no part of the id.
- [ ] **Fail closed, with a cause.** A table of stub rows, each asserting the decision, the cause, `auditValue()`, and that no exception escapes:
  - `lockState` returns `UNAVAILABLE` → `Exhausted(LIMITER_UNAVAILABLE)`, opener not called;
  - `lockState` throws → `Exhausted(LIMITER_UNAVAILABLE)`, opener not called;
  - the opener throws → `Exhausted(OPEN_FAILED)`, `recordFailure` not called;
  - `recordFailure` returns `UNAVAILABLE` → `Exhausted(LIMITER_UNAVAILABLE)`;
  - `recordFailure` throws → `Exhausted(LIMITER_UNAVAILABLE)`;
  - `recordFailure` returns `CEILING` → `Exhausted(CEILING)`.

  No limiter or opener message text appears in any decision, `auditValue()`, `toString()` or exception (sentinel). Mutants: let the opener's exception propagate; map every `Exhausted` to one cause.
- [ ] **Ceiling (OD-269-3).**
  - With `maxEntries = 4`, four subjects have one failure each. A fifth subject's counted failure returns `CEILING`, `size()` stays 4, and the gate answers `Exhausted(CEILING)`. A valid token for the fifth subject gets `Proceed`, and so does one for a sixth subject.
  - A subject locked before the map filled stays locked while new subjects keep arriving.
  - Once the first entries' windows have ended, the fifth subject's next failure is `WITHIN_LIMIT`, through the drain and one retry, with no explicit sweep call.
  - A test named for the OD-269-3 consequence shows the fifth subject making 100 counted failures and still getting `Proceed` for a valid token.
  - Mutants: evict the oldest entry (a lock lifts); refuse every subject at the ceiling (a valid token is refused); store the fifth entry anyway (`size()` is 5); retry the insert without a bound.
- [ ] **Ceiling, deterministic race.** With `maxEntries = 1` and the test constructor's insert probe, thread A records subject 1, and the probe blocks A on a latch after A has reserved the slot. Thread B then records subject 2 and gets `CEILING`. Then A is released and gets `WITHIN_LIMIT`. Afterwards `size()` is 1 and the entry count is 1. Mutant: check the size with `entries.size()` before `compute`; both are stored, and the test fails every run.
- [ ] **Sweep, bounded and complete.**
  - A package-private counter records each call's queue operations, counting every peek, poll and append. Over 10,000 calls against 10,000 live entries it is at most 7 per call below the ceiling and at most 55 at it.
  - With N = 1,000 entries whose windows have all ended and no live ones, after k calls to `lockState` on an absent subject, `size()` equals `max(0, N − 2k)`.
  - A window re-opened in place (fail, pass the window, fail again) leaves exactly one entry and one marker. The entry is removed once its second window ends.
  - The clock steps back an hour after 10 entries are created. One hour later, every entry that ended is removed, and no call examines more than its cap.
  - The queue size never exceeds `size()` (asserted after every call in these tests).
  - The class contains no `removeIf` over the map, has no public `sweep()`, and calls no queue or removal method inside a `compute` lambda (a review item named in the Outcome).
  - Mutants: restore the every-256 full sweep (operation count over the cap); drop the marker of a re-opened entry (the entry is never removed); remove without comparing the window start (a live re-opened entry is removed); stop at a future-start head (no removals after the step back).
- [ ] **Counter identity (OD-269-2).**
  - `AttemptSubjectTest`: `canonical()` of `("ab","c")` differs from that of `("a","bc")`, and `canonical()` begins with the length-prefixed label `dataprism/link/v1/attempt`.
  - `canonicalText()` is lowercase hex, twice the length of `canonical()`, and decodes back to it. A test subject whose canonical bytes are not valid UTF-8 as a whole (the length prefixes) shows that decoding them as UTF-8 would lose bytes and that the hex does not.
  - `vectors.properties` pins `attempt.subject.canonical` and `attempt.subject.canonical.text` for a fixed subject, and the `counter.key` vector is gone.
  - `LinkKeys` has no public method that takes a principal id (reflection).
- [ ] **No key dependency (ArchUnit).** `LinkArchitectureTest` fails when `LinkAttemptLimiter`, `InMemoryLinkAttemptLimiter`, `AttemptSubject`, `LinkOpener` or `LinkAttemptGate` depends directly on `LinkKeys` or `SecretKeyProvider`. A nested fixture proves the rule can fail.
- [ ] **No bypass (ArchUnit).** `LinkArchitectureTest` fails when a main class outside `core.link`, by a call **or a method reference** (`codec::open`, `limiter::recordFailure`):
  - uses `LinkCodec.open`; or
  - uses `LinkAttemptLimiter.lockState` or `recordFailure`, on the interface or on any implementation. The one exception is a class in `io.github.aindriub.dataprism.hazelcast..` that itself implements `LinkAttemptLimiter`.

  Fixtures, imported explicitly as the existing ones are:
  - nested fixtures that call `open`, reference `codec::open`, call `recordFailure`, and reference `limiter::recordFailure` each make the rule fail;
  - a nested fixture that implements the SPI outside the Hazelcast package also makes it fail;
  - the fixture file `LinkLimiterFloorFixture` (see Owns), in package `io.github.aindriub.dataprism.hazelcast.archfixture`, implements the SPI, delegates to an `InMemoryLinkAttemptLimiter`, and passes.
- [ ] **No leak.** The in-memory map's keys are 32-character hex strings that contain neither id, in plain or hex form. `AttemptSubject.toString()`, `GateDecision.toString()` and the limiter's and the gate's `toString()` contain neither id, nor does any exception message (sentinel ids). `canonicalText()` is used only as an HMAC input and is never logged.
- [ ] `clusterWide()` is `false` for `InMemoryLinkAttemptLimiter` and for an implementation that does not override it.
- [ ] `sealed-links.md` and `batch-and-bulk-follow.md` carry the amendments below, word for word in substance.
- [ ] A whole-component bug hunt over the limiter, the gate and the sweep (races, the ceiling, the clock stepping back) runs before merge; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-architecture -am verify` passes. Report the counts.

## Design amendments (recorded by this task)

**sealed-links.md:**
- **Section 3, "Fingerprinting" row:** drop "and for the attempt-counter key (section 4)". The counter key is no longer derived from the link key.
- **Section 4, the round-2 "Binding bounds" bullet (about line 277):** replace its last sentence, "`attemptCounterKey` still throws for an id over the limit, so the caller checks the bounds before it (briefs 251, 252; 269 replaces `attemptCounterKey`).", with "`LinkAttemptGate.open` checks the same bounds before anything else and answers `MALFORMED`, uncounted, without calling the limiter or the codec; `attemptCounterKey` is removed (task 269)."
- **Section 4, the post-authentication `MALFORMED` bullet (about line 280):** replace "Callers gate the limiter on `refused.counted()`." with "`LinkAttemptGate.open` records a failure only when `refused.counted()` is true; no other caller touches the limiter (task 269)."
- **Section 4, "Attempt limiter", replace the counter-key bullet with:** "It counts per principal within a scope. The limiter receives the subject (scope id and principal id, each 1 to 65,535 UTF-8 bytes; outside that bound, or empty, the call is `MALFORMED`, uncounted). Its input is the length-prefixed label `dataprism/link/v1/attempt` followed by the two ids, and `canonicalText()` is that input as lowercase hex for `String`-keyed APIs. It keys that input under a key that does not rotate with the link key: a per-instance `SecureRandom` key in memory, and the cluster name key (`KeyedNames`) in the shared limiter (task 264). Every member therefore derives the same key, and a link-key rotation neither lifts a lock nor splits a count. Changing the name key is a planned full restart that resets every count at once (cluster-security 4.6). No map holds a scope id or principal id in the clear (boundary 6) (task 269, 2026-10-22)."
- **Section 4, add:** "Code outside `core.link` opens a token only through `LinkAttemptGate.open`. That one call:
  - checks the inputs and the binding bounds (`MALFORMED`, uncounted);
  - checks the lock: a locked principal is refused before the token is opened, valid links included;
  - opens the token;
  - on a counted refusal, makes one atomic `recordFailure`, which returns whether the new count is within the limit.

  An attempt over the limit is answered `LINK_ATTEMPTS_EXHAUSTED`. The client sees one code. The audit's `link` value records the cause as `REFUSED:LINK_ATTEMPTS_<CAUSE>`, where the cause is `LOCKED`, `OVER_LIMIT`, `CEILING`, `LIMITER_UNAVAILABLE` or `OPEN_FAILED`.

  Guarantee: at most `invalid-attempt-limit` counted failures per window are answered `LINK_INVALID`. The number of guesses that can reveal a success is at most that limit plus the attempts already past the lock check when the lock engaged.

  Each instance holds at most a fixed number of entries (100,000 by default), checked atomically. At that ceiling a counted failure for a subject not yet held is answered `LINK_ATTEMPTS_EXHAUSTED` and is not stored. No live entry is evicted, so a flood can neither lift a lock nor lock out a principal with a valid link. The cost, accepted by the owner under OD-269-3: while the ceiling holds, a subject that is not stored is never locked, so it can keep guessing against the 128-bit tag.

  In memory, every entry has one expiry marker. Each call examines a fixed, small number of markers, with a bounded drain at the ceiling. A head that has ended is removed only when the stored window start matches, a re-opened entry is re-queued, and a head whose start lies in the future is rotated. There is never a full sweep on the request thread.

  Any limiter failure, or a failure to open, is treated as exhausted, never as a pass (task 269, 2026-10-22; revised 2026-10-23)."
- **Section 6, boundary 3:** replace "with a time-to-live" with "whose time-to-live, at least the window plus the clock-skew tolerance from the last write, only bounds memory; the window is decided by the stored start and the clock".
- **Section 7, obligation 22, append:** "Concurrency: N parallel forged attempts against a fresh subject yield exactly `invalid-attempt-limit` `LINK_INVALID`, and the rest `LINK_ATTEMPTS_EXHAUSTED`. Ceiling: a full map answers new subjects `CEILING`, keeps existing locks, lets valid links proceed, and does not lock a subject it cannot store (OD-269-3). Gate: a locked principal's valid link is refused before the token is opened, and every `Exhausted` carries an audit-only cause. Architecture: nothing outside `core.link` calls or references `LinkCodec.open` or the limiter, except a Hazelcast implementation of the limiter."

**batch-and-bulk-follow.md, Part A "What happens in a call", item 2 "Verification" (about line 816):**
- Replace "(the count key is `HMAC(fingerprintKey, scopeId | principalId)` hex-encoded, as there; this design adds no map)" with "(each link is opened through `LinkAttemptGate.open`, keyed by `AttemptSubject` as task 269 specifies; this design adds no map)".
- Replace "When a failure brings the counter to the limit, verification stops" with "When the gate answers `Exhausted` for a link, verification stops".
- Item 1's lock pre-check before any link is looked at needs a gate method that checks the lock without opening. That is for the 0.9 bulk-follow task, not 269. Add the sentence "(0.9: through a lock-only method on `LinkAttemptGate`, to be added with bulk follow)" after item 1's limiter sentence.

## Out of scope
- The Hazelcast limiter, its map, its entry processor and its ceiling (264, which implements this contract within the "may relax / must keep" list above).
- Wiring the gate into `follow` (252), `list_sources` page links (250) and Spring (255), issuing links (251), and documenting the audit values in `docs/audit.md` (261).
- The lock-only gate method for bulk follow (0.9).
- Any Spring property or bean, including a capacity property (OD-269-3 (a): none in 0.8).
- A metric for saturation or attempts: `Metric.java` belongs to 241 in this wave. Expose `size()` package-private only.
- Changes to `LinkCodec`, `LinkBinding`, `LinkReason` and the rest of `LinkKeys` (`heldKeyIds`, `forScope`, the pseudonymisation digest).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Use neutral, invented identifiers in tests, and generated test key bytes.
- No sleeps. Windows use an injected clock, and ordering uses latches.
