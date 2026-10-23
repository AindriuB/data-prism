# 269 — Settle the sealed-link attempt limiter's contract: atomic record-and-decide, a bounded map, and a counter key that survives rotation

**Repo:** `.`
**Release:** 0.8.0 (links stay off by default through 0.9, sealed-links OD-7)
**Base branch:** the 0.8 integration branch, at a commit that contains 242 and both rounds of its external-review fixes (`record/0.8-wave3` at 334f5c52, or `main` once PR #154 has merged), including 242 round 4: `LinkKeys.ATTEMPT_LABEL` (`dataprism/link/v1/attempt`), the purpose-labelled `attemptCounterKey`, and `LinkConfigurationException`.
**Depends on:** 242 (`core.link`: `LinkAttemptLimiter`, `InMemoryLinkAttemptLimiter`, `LinkKeys`, `LinkCodec.open`, `LinkBinding.withinBounds`, `LinkOpenResult.Refused.counted()`)
**Decisions:** owner decision 2026-10-22: settle the limiter contract in this separate task, before 251 and 264 build on it. **Decided by the owner 2026-10-22:** OD-269-1 (a), OD-269-2 (a), OD-269-3 (a) and OD-269-4 (coordinator: 269 added to 250's Depends on). **Decided by the owner 2026-10-23:** OD-269-5 (b), a new retryable `LINK_CHECK_UNAVAILABLE` for a limiter outage and `LINK_INVALID` for a failure to open. "Required shape" is written to these decisions. Revised 2026-10-23 after the external review of PR #155. The review did not change any decision. It replaced the two-step gate with one call, stated the real bounds and the ceiling's consequence, and fixed the sweep and the binding bounds. The owner accepted the ceiling consequence under OD-269-3 on 2026-10-23. Revised again 2026-10-23 after a fourth external review, with no decision changed. The changes are a lossless `canonicalText()` for 264, a package-private `LinkOpener` test seam, `OPEN_FAILED`, audit-only causes on `Exhausted` (so `locked` becomes `lockState`, and `Admission` gains `CEILING` and `UNAVAILABLE`), one marker per entry with bounded head examination, a deterministic ceiling race, method references in the bypass rule, and what 264 may relax. Revised 2026-10-23 after a fifth review, again with no decision changed. Head examination becomes exclusive (`tryLock`), with the removal decided inside `computeIfPresent` and markers that carry the entry id. Slots are reserved by a non-blocking CAS outside `compute`. An empty token is `MALFORMED` (coordinator). Null results are refusals. The client code for an outage is now settled: OD-269-5 (b), decided 2026-10-23 and recorded below. A split-package rule is added. The drain and race tests are reachable and deadlock-free. The design amendments cover 242 round 4's label table and closed set.
**Wave:** 4 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed). It runs alongside 203, 206, 223, 241 and 248; it owns none of their files.
**Serialised with:** none in wave 4. 250, 251, 252, 255 and 264 depend on this task, directly or through 251.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptLimiter.java *(including its Javadoc reference to `attemptCounterKey`)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiter.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/AttemptSubject.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptGate.java *(new; `GateDecision` and `ExhaustedCause` are nested)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkOpener.java *(new, package-private)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkKeys.java *(removing `attemptCounterKey`, `ATTEMPT_LABEL` (it moves to `AttemptSubject`) and the Javadoc that mentions them; and the key-id grammar check on held ids (Acceptance, "LinkCodec and LinkKeys follow-ups") only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkCodec.java *(the four follow-ups in Acceptance, "LinkCodec and LinkKeys follow-ups", only: the `knownKey` guard's comment and extraction, the unused import; no behaviour change. Moved here from #160's review, 2026-10-23, because no open brief owned it)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkCodecTest.java *(the tests for those follow-ups only: the exact scope-end and token-end cases)*
- pom.xml *(the `mutation` profile's PIT configuration: one `<excludedMethods>` entry for the extracted `knownKey` guard method only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiterTest.java *(rewritten to the new SPI; its 6 `attemptCounterKey` references at the base are removed or ported)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkAttemptGateTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/AttemptSubjectTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkKeysTest.java *(remove or port the attempt-key uses only: its 9 references to `attemptCounterKey` or `ATTEMPT_LABEL` at the base, including the vector-writer line; and the two leak assertions at about lines 64 and 177, listed in Acceptance)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkVectors.java *(the counter-key entry only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkTestSupport.java *(insertions only)*
- data-prism-core/src/test/resources/link/vectors.properties *(replace `counter.key` with `attempt.subject.canonical` and `attempt.subject.canonical.text`)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/LinkArchitectureTest.java *(insertions only: the rules below and their nested fixtures)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/hazelcast/archfixture/LinkLimiterFloorFixture.java *(new; a test fixture only, in the Hazelcast package so the bypass rule's one exception can be proven; imported explicitly, never by package scan)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/core/link/SplitPackageFixture.java *(new; a test fixture only, packaged in `core.link` but compiled into data-prism-architecture's test classes, so the split-package rule can be shown to fail; imported explicitly, never by package scan)*
- docs/plan/design/sealed-links.md *(section 3: the "Fingerprinting" row and the "MAC input labels" table with the sentence after it; section 4: the `link` disposition row, the closed-set sentence, the round-2 "Binding bounds" bullet, the post-authentication `MALFORMED` bullet, and "Attempt limiter"; section 6 boundary 3; section 7 obligation 22; section 9's `LINK_<REASON>` row and a new `LINK_CHECK_UNAVAILABLE` row; only as "Design amendments" below says)*
- docs/plan/design/contract-register.md *(the `LINK_CHECK_UNAVAILABLE` row only, OD-269-5)*
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
- docs/plan/tasks/retired/242-sealed-link-codec-and-attempt-limiter.md, round 4: the purpose labels on every fingerprint-key MAC (`fingerprint`, `catalog-tag`, `attempt`) and `LinkConfigurationException` for construction refusals.
- docs/conventions.md "Tests": an injected `Clock`, no sleeps. Use latches for ordering.

## Owner decisions (OD-269-1 to 4 decided 2026-10-22; OD-269-5 decided 2026-10-23)

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

**OD-269-5: the client code for a limiter outage or a failure to open. Decided (b), 2026-10-23, owner.** A limiter outage does not depend on the token, so a distinct retryable code reveals nothing. A failure to open does depend on the token, so a distinct code would tell an attacker which input trips it; it stays `LINK_INVALID`. The audit still records the distinct causes. The options as put:
- (a) Keep `LINK_ATTEMPTS_EXHAUSTED`. No new code; the cause is in the audit.
- (b) **Decided.** Split the constant in two. `LIMITER_UNAVAILABLE` gets a new retryable code, `LINK_CHECK_UNAVAILABLE`. It is not token-dependent, so it reveals nothing, and it needs a register row and entries in `docs/tools.md` and `docs/audit.md` (261). `OPEN_FAILED` gets `LINK_INVALID`, because it depends on the token, and any distinct answer would be an oracle for whatever input trips the failure. The gate has no single `UNAVAILABLE_CLIENT_CODE` constant any more.
- (c) One new code for both. This is simple, but the `OPEN_FAILED` half has the oracle concern of (b).

**Binding bounds (planner's choice under the review, 2026-10-23): bound, do not hash.** `AttemptSubject` uses the same bound as `LinkBinding`: each id at most 65,535 UTF-8 bytes and non-empty. Reasons:
- One rule covers both, and the codec already refuses such a binding as `MALFORMED`, uncounted, because the session is at fault, not the token.
- Hashing each part would accept a binding that the codec refuses anyway.
- Hashing would also add a step that 264 must reproduce exactly.

The gate checks the bounds before anything else and answers `MALFORMED`, uncounted.

## Required shape (later tasks read these; matches the owner's decisions above; revised 2026-10-23 after the fifth review)
- **`AttemptSubject`.**
  - A record `AttemptSubject(String scopeId, String principalId)`. Each id must be non-null, non-empty and at most `LinkBinding.MAX_ID_BYTES` (65,535) UTF-8 bytes; otherwise the constructor throws `IllegalArgumentException` with a message that names no value.
  - `static Optional<AttemptSubject> of(LinkBinding binding)` is empty for a null binding or an id outside those bounds, including an empty id. It never throws.
  - `canonical()` is `LabelledHkdf.lengthPrefixed` of three items: the ASCII label `dataprism/link/v1/attempt` (exactly this; 242 round 4 already defines it as `LinkKeys.ATTEMPT_LABEL`, which moves to `AttemptSubject`), then the scope id, then the principal id. The label makes the counter input differ from every other MAC input in the section 3 label table, and the length prefixes make `("ab","c")` differ from `("a","bc")`.
  - `canonicalText()` is the lowercase hex of `canonical()`, which is lossless. It is the string a `String`-keyed API takes, such as 264's `KeyedNames.mapKey(map, canonical)`. Never build that string by decoding `canonical()` as UTF-8.
  - `toString()` is `AttemptSubject[redacted]`.
- **`LinkAttemptLimiter`** (the SPI):
  - `LockState lockState(AttemptSubject subject)`, where `enum LockState { UNLOCKED, LOCKED, UNAVAILABLE }`. `LOCKED` means `count >= limit` within the current window. `UNAVAILABLE` means the implementation could not decide.
  - `Admission recordFailure(AttemptSubject subject)`, where `enum Admission { WITHIN_LIMIT, OVER_LIMIT, CEILING, UNAVAILABLE }`. It is one atomic operation per subject. It opens a window if none is live (absent, or ended), adds one (saturating), and returns `WITHIN_LIMIT` when the new count is at most `limit`, otherwise `OVER_LIMIT`. It returns `CEILING` when the subject is not held and cannot be stored, and `UNAVAILABLE` on any internal failure. Every value except `WITHIN_LIMIT` refuses.
  - `default boolean clusterWide()` returns `false`.
  - The Javadoc lists what every implementation, 264 included, must keep:
    - atomic per subject;
    - the window is decided by the stored start and the clock, never by an entry expiring;
    - no live entry is evicted;
    - a new subject at the ceiling gets `CEILING` and is not stored;
    - fail closed through `UNAVAILABLE`, never by throwing or returning `null`.
- **`InMemoryLinkAttemptLimiter`.**
  - **Constructors.**
    - `(int limit, Duration window, Clock clock)`.
    - `(int limit, Duration window, Clock clock, int maxEntries)`. `maxEntries < 1` is `INVALID_LINK_LIMIT`, and the default is `DEFAULT_MAX_ENTRIES = 100_000`.
    - A package-private test constructor that also takes the HMAC key, a probe and a queue factory. In production, the probe is a no-op and the factory gives a `ConcurrentLinkedQueue`.
      - The probe is a `Consumer<Stage>`, run at two points and never inside a map lambda: `RESERVED`, run only when the `tryReserve()` outside `compute` returned `true`, after that reservation and before `compute` is entered (a failed reservation never runs the probe); and `STORED`, after `compute` has stored a new entry and returned, before its marker is appended.
      - The queue factory is a `Supplier<Queue<Marker>>`, so a test can supply a queue that refuses an `offer`. "Refuses an offer" means `offer` returns `false` or throws a `RuntimeException`; the limiter handles both identically.
      - **Package-private test seams that Acceptance relies on**, none of them used in production:
        - `queueSnapshot()`, the markers in queue order;
        - `queueSize()`;
        - `checkInvariant()`, which asserts exactly one marker per stored entry id and must be called single-threaded;
        - `size()`, and the entry count;
        - per-call counters for the last call on the calling thread: queue operations, entries removed by the pre-check, entries removed by the drain, and insert attempts;
        - `examinationLock()`, the lock hook that lets a test thread hold the examination lock.
  - **Key.** The HMAC key is 32 bytes from `SecureRandom`, drawn once at construction. The map key is the 16-byte truncated `HMAC-SHA256(k, subject.canonical())`, as 32 hex characters.
  - **Entries.** An entry holds an id, unique per instance (from an `AtomicLong`) and assigned at creation, plus the window start and the count. Re-opening a window in place keeps the id and changes the start. Only `recordFailure` creates or re-opens entries.
  - **Slots.** The entry count is an `AtomicInteger`, and `tryReserve()` is a non-blocking CAS that increments it only while it is below `maxEntries`. `recordFailure` works in this order:
    1. Compute the map key (the HMAC) and run the head examination below, before any slot is reserved.
    2. If `get(mapKey)` is null, call `tryReserve()` outside `compute`; only if it returned `true`, run the `RESERVED` probe.
    3. In `compute`:
       - absent key with a reservation held: store a new entry, which consumes the slot;
       - absent key without one: call `tryReserve()` once, inside the lambda (a CAS, never a wait); if it fails, store nothing;
       - present key: update it, and release any reservation held, after `compute` returns.
    4. A new entry's marker is appended after `compute` returns.
    5. If nothing was stored because the map was full, run the ceiling drain, then retry steps 2 and 3 **once**. If still full, the answer is `CEILING`.

    Every removal decrements the count. The count never exceeds `maxEntries`.

    **No slot leaks.** The whole method body runs in a `try` whose `finally` releases any reservation not consumed by a stored entry. That covers a throw from the probe, from `compute`, or from anything after the reservation, and it covers the present-key path. If appending the new entry's marker fails (the queue refuses the offer, as defined above), the entry is rolled back **by id**. `computeIfPresent(mapKey, ...)` returns `null` only if the stored entry's id equals the id just created. That holds even if another thread has since updated the entry in place, which keeps the id but replaces the value, so a value-based `remove(mapKey, entry)` would miss. The slot is released only if that lambda actually removed. A concurrent failure recorded into the rolled-back entry is lost with it. That is accepted: the failing call answers `UNAVAILABLE`, and a refused `offer` is an out-of-memory-class event. No entry can exist without a marker. Any `RuntimeException` inside `recordFailure` or `lockState` becomes `UNAVAILABLE`; the limiter never throws.
  - **Markers.** The expiry queue holds markers `(mapKey, entryId, windowStart)`. Each stored entry has exactly one marker; only marker examination removes an entry, and it polls that marker in the same examination. So the queue holds at most one marker per entry, plus markers appended but not yet matched (bounded by the threads in flight).
  - **Head examination is exclusive.** A `ReentrantLock` guards it, taken with `tryLock()`, which never waits. A call that does not get the lock skips examination entirely. Chosen over removing the exact peeked marker (`queue.remove(marker)` is O(n); a CAS on the head needs a custom queue): with one examiner at a time, peek-then-poll always acts on the marker it peeked, and appends at the tail never interfere. The lock is released in a `finally`, so a throw during examination can never leave it held, and the throw becomes `UNAVAILABLE` for the calling operation. **Re-append order:** wherever a marker is re-appended, the examiner offers the new marker **before** polling the old one. If the queue refuses that offer (returns `false` or throws), the examination stops at once and leaves the old marker in place, so the entry never loses its marker. While holding the lock, the examiner peeks at the head and decides **inside `computeIfPresent`**, re-checking the stored entry:
    - Same id, stored start equal to the marker's, and the window ended: return `null` (remove). Only when the lambda actually removed does the examiner decrement the count and poll the marker.
    - Same id, stored start different (the window was re-opened in place): keep the entry; offer a marker with the stored start, then poll the old one.
    - Different id, or the key absent: the marker is stale; poll and drop it.
    - Same id and live: keep the entry. If the marker's start is after `now` (the clock stepped back), or the call is in the ceiling drain, offer the same marker again, then poll the old copy (rotation). Otherwise stop.
  - **Per-call bounds.**
    - Each `lockState` and `recordFailure` call examines at most **2** heads before its own work.
    - The ceiling drain examines at most **16** more. It examines heads, removing ended entries and rotating live ones (offer the same marker, then poll the old copy), and stops as soon as one slot is freed, or after 16 heads, or when the queue is empty or refuses an offer. The insert is retried once afterwards. Mutants: stop at a live head; continue past the first freed slot. It runs under the same examination `tryLock`. If the lock is held by another call, this call skips the drain, still retries the insert once (the holder may have freed a slot), and otherwise answers `CEILING`. This is accepted rather than waiting: the holder is itself removing ended entries, the miss is transient and fails closed, and no request thread ever blocks on the lock.
    - One examination is at most one peek, one poll and one append, and a created entry adds one append. So, counting every peek, poll and append, a call does at most **7** queue operations below the ceiling and at most **55** with the drain.
    - There is no full-map sweep, no `removeIf` over the map, no background thread, and no queue or lock operation inside a map lambda.
- **`LinkOpener`**, a package-private functional interface with the one method `LinkOpenResult open(String token, LinkBinding binding, Set<LinkRelation> accepted)`. It exists only as a test seam: `LinkCodec` is final, `data-prism-core` has no mocking library, and the gate's tests need stub, spy and latching openers. Production code never implements it except through `codec::open`. `LinkCodec` is not changed.
- **`LinkAttemptGate`**, the only way code outside `core.link` opens a token:
  - **Constructors.** `public LinkAttemptGate(LinkCodec codec, LinkAttemptLimiter limiter)` delegates to a package-private `LinkAttemptGate(LinkOpener opener, LinkAttemptLimiter limiter)` with `codec::open`.
  - **`GateDecision open(String token, LinkBinding binding, Set<LinkRelation> accepted)`** runs, in this order:
    1. A null or empty (`""`) token, a null binding, a null `accepted`, or `AttemptSubject.of(binding)` empty (an empty id or an id over the bound) → `Invalid(MALFORMED)`. Nothing is recorded, neither the limiter nor the opener is called, and nothing throws. This settles 252's question about `""` (coordinator decision, 2026-10-23): an empty token is "no link" gone wrong, not a guess, so it is not counted.
    2. `lockState(subject)`: `LOCKED` → `Exhausted(LOCKED)`; `UNAVAILABLE`, `null` or a thrown `RuntimeException` → `Exhausted(LIMITER_UNAVAILABLE)`. In each case the opener is not called. This is where a locked principal's valid link is refused.
    3. `opener.open(...)`. A thrown `RuntimeException` or a `null` result → `Exhausted(OPEN_FAILED)`, and nothing is recorded.
    4. `Opened` → `Proceed(opened)`. `Refused` with `counted() == false` → `Invalid(reason)`, with nothing recorded.
    5. `Refused` with `counted() == true` → `recordFailure(subject)`: `WITHIN_LIMIT` → `Invalid(reason)`; `OVER_LIMIT` → `Exhausted(OVER_LIMIT)`; `CEILING` → `Exhausted(CEILING)`; `UNAVAILABLE`, `null` or a thrown `RuntimeException` → `Exhausted(LIMITER_UNAVAILABLE)`.

    The gate catches `RuntimeException` only. An `Error` propagates.
  - **`GateDecision`** is sealed: `Proceed(LinkOpenResult.Opened opened)`, `Invalid(LinkReason reason)` and `Exhausted(ExhaustedCause cause)`, with `enum ExhaustedCause { LOCKED, OVER_LIMIT, CEILING, LIMITER_UNAVAILABLE, OPEN_FAILED }`.
  - **Client code.** Each refusing decision (`Invalid` and `Exhausted`, through the nested `LinkAttemptGate.Refusal` interface; `Proceed` has none) has `clientCode()`: `LINK_INVALID` for `Invalid`; `LINK_ATTEMPTS_EXHAUSTED` for `Exhausted` with `LOCKED`, `OVER_LIMIT` or `CEILING`; `LINK_CHECK_UNAVAILABLE` (new, retryable) for `LIMITER_UNAVAILABLE`, which does not depend on the token; and `LINK_INVALID` for `OPEN_FAILED`, which does, so a distinct code would be an oracle for the input that trips it (**OD-269-5 (b), decided by the owner 2026-10-23**). There is no `UNAVAILABLE_CLIENT_CODE` constant. The audit keeps the distinct causes (`REFUSED:LINK_ATTEMPTS_LIMITER_UNAVAILABLE`, `REFUSED:LINK_ATTEMPTS_OPEN_FAILED`), and the decision is `DENY:` plus `clientCode()`. Callers answer with `clientCode()` and never compute the code themselves.
  - **Audit.** Each refusing decision has `auditValue()` (also on `Refusal`): `REFUSED:LINK_<REASON>` for `Invalid`, and `REFUSED:LINK_ATTEMPTS_<CAUSE>` for `Exhausted`. The caller (250, 252) writes it under the `link` disposition key of the call's audit record (OD-13 shape C, no grammar change). The decision is `DENY:` plus `clientCode()`.
  - **After `Proceed`.** Refusals the caller decides after `Proceed` are uncounted and do not touch the limiter: target permission, relation declared, payload checks, catalog tag.
  - The gate never reads `LinkReason.counted()`. No limiter or opener exception text reaches a decision, an exception or `toString()`.
- **Removed:** `LinkKeys.attemptCounterKey`, and `LinkKeys.ATTEMPT_LABEL` (it moves to `AttemptSubject`). The `fingerprint` and `catalog-tag` MACs and their labels are unchanged. `LinkConfigurationException` (242 round 4) is unchanged; `InMemoryLinkAttemptLimiter`'s `INVALID_LINK_LIMIT` refusals use it.
- **What 264 may relax, and what it must keep.**
  - *May relax:*
    - The ceiling may be approximate, for example `IMap.size()` read before the processor; the Outcome states the bound.
    - Memory may be bounded by a map time-to-live instead of an expiry queue, so the queue, the lock and the per-call bounds do not apply.
  - *Must keep:*
    - every SPI guarantee above;
    - the key: `KeyedNames.mapKey(map, subject.canonicalText())` under the name key;
    - a time-to-live of at least the window plus the clock-skew tolerance, measured from the entry's last write, so expiry never lifts a live lock;
    - no size-based eviction policy.

## Acceptance
- [ ] Tests are written first and fail on the base commit (they do not compile against 242's SPI); the Outcome names them. Each failing-input test below names the mutant it kills.
- [ ] **One call, no bypass.** In `LinkAttemptGateTest`, which uses stub and spy `LinkOpener`s through the package-private constructor:
  - After 20 counted failures for a subject, `open` with a valid token returns `Exhausted(LOCKED)`, and the opener spy records no call. So does a genuine token that would be refused uncounted (`EXPIRED`).
  - Another principal in the same scope, and the same principal in another scope, get `Proceed`.
  - Mutants: skip the pre-check; run the pre-check after `open` but return `Proceed` anyway.
- [ ] **Race, failures (OD-269-1).** With `limit = 20` and N = 64, 64 threads are released together by a latch. Each calls `open` with a stub opener returning `Refused(AUTH_FAILED, true)`, for one subject.
  - Each thread's result counts as follows: `Invalid(AUTH_FAILED)` if its failure was recorded `WITHIN_LIMIT`; `Exhausted(LOCKED)` if it was refused at the pre-check (nothing recorded); `Exhausted(OVER_LIMIT)` if it was recorded over the limit.
  - The test asserts exactly 20 `Invalid` and 64 results in all, and that the stored count equals 20 plus the number of `OVER_LIMIT` results. It is repeated 50 times.
  - A deterministic interleaving: two attempts are latched inside the opener, after the pre-check at count 19, and both fail. Exactly one is `Invalid` and one is `Exhausted(OVER_LIMIT)`.
  - Mutants: `recordFailure` deciding from the count before the increment; the gate deciding from `lockState` instead of the `Admission`.
- [ ] **Race, the stated bound.** A valid token whose pre-check passes at count 19 is latched inside the opener while one forged attempt takes the count to 20. It gets `Proceed`. The test is named for the "limit plus in-flight" bound and documents it; it asserts nothing stronger.
- [ ] **Lock semantics kept.** The window ends at the stored start plus the window, and increments do not extend it. The `Long.MAX_VALUE`-second window still constructs and locks. Sixteen threads recording concurrently lose no count. Mutants: extend the window on increment; key by scope only; count every reason.
- [ ] **Gate on `counted()`.** Stub `Refused(MALFORMED, false)` (post-authentication) and `Refused(KEY_UNAVAILABLE, false)` give `Invalid` and record nothing over ten calls each. A `Refused(AUTH_FAILED, true)` records once. Mutant: gating on `reason().counted()`.
- [ ] **Malformed inputs, one rule.** Each of these gives `Invalid(MALFORMED)` with no limiter call and no opener call (spies), and nothing throws:
  - a null token, and an empty token `""`;
  - a null binding, and a null `accepted`;
  - an empty scope id, and an empty principal id;
  - a scope id of 65,536 UTF-8 bytes, and a principal id of 65,536 UTF-8 bytes.

  At 65,535 bytes the call proceeds normally. `new AttemptSubject` with any of the bad ids throws `IllegalArgumentException`, and the message contains no part of the id. Mutant: counting `""`.
- [ ] **Fail closed, with a cause.** A table of stub rows. Each row asserts the decision, the cause, `clientCode()` and `auditValue()`, and that no exception escapes:
  - `lockState` returns `UNAVAILABLE`, returns `null`, or throws `IllegalStateException` → `Exhausted(LIMITER_UNAVAILABLE)`, and the opener is not called;
  - the opener throws `IllegalStateException`, or returns `null` → `Exhausted(OPEN_FAILED)`, and `recordFailure` is not called;
  - `recordFailure` returns `UNAVAILABLE`, returns `null`, or throws `IllegalStateException` → `Exhausted(LIMITER_UNAVAILABLE)`;
  - `recordFailure` returns `CEILING` → `Exhausted(CEILING)`;
  - the opener throws an `AssertionError` → it propagates, because the gate catches `RuntimeException` only.

  The `clientCode()` of the `LIMITER_UNAVAILABLE` rows is `LINK_CHECK_UNAVAILABLE`; that of the `OPEN_FAILED` rows is `LINK_INVALID`, and each still audits its own `REFUSED:LINK_ATTEMPTS_<CAUSE>`; `LOCKED`, `OVER_LIMIT` and `CEILING` give `LINK_ATTEMPTS_EXHAUSTED`. No limiter or opener message text appears in any decision, `auditValue()`, `toString()` or exception (sentinel). Mutants: let a null `Admission` through as `WITHIN_LIMIT`; let the opener's exception propagate; map every `Exhausted` to one cause; answer `OPEN_FAILED` with `LINK_CHECK_UNAVAILABLE` or `LIMITER_UNAVAILABLE` with `LINK_INVALID`.
- [ ] **Ceiling (OD-269-3).**
  - With `maxEntries = 4`, four subjects have one failure each. A fifth subject's counted failure returns `CEILING`, `size()` stays 4, and the gate answers `Exhausted(CEILING)`. A valid token for the fifth subject gets `Proceed`, and so does one for a sixth.
  - A subject locked before the map filled stays locked while new subjects keep arriving.
  - A test named for the OD-269-3 consequence shows the fifth subject making 100 counted failures and still getting `Proceed` for a valid token.
  - Mutants: evict the oldest entry (a lock lifts); refuse every subject at the ceiling (a valid token is refused); store the fifth entry anyway (`size()` is 5); retry the insert without a bound. The last is killed without hanging: the fifth subject's call runs inside `assertTimeoutPreemptively` (5 s), and the insert-attempt counter for that call must be exactly 2.
- [ ] **Ceiling drain is reached.** The setup puts a live head in front of ended entries when the drain runs, so that only the drain can free a slot and a drain that stops at a live head fails. The window is 10 s and `maxEntries = 5`. Every failure in this test calls the limiter's `recordFailure` directly, never the gate: the gate's `lockState` call would run its own pre-check and shift the counters.
  1. At t = 0 s, subjects A and B fail. At t = 5 s, L fails (live until 15 s). The clock then steps back to t = 1 s, and C and D fail (live until 11 s). Each of those pre-checks stops at head A, which is live with a start at or before now, so nothing rotates. The queue holds A(0), B(0), L(5), C(1), D(1), in that order.
  2. At t = 10.5 s, a dedicated holder thread takes the examination lock through `examinationLock()` and waits on a latch. A and B fail again from the test thread. Each pre-check skips (the `tryLock` fails), and `compute` re-opens A and B in place, starting at 10.5 s, keeping their ids and their old markers. The holder then releases the lock and is joined.
  3. Before the call under test, `queueSnapshot()` returns exactly A(0), B(0), L(5), C(1), D(1).
  4. At t = 11.5 s, C and D have ended, and A, B and L are live. A sixth subject E fails:
     - the two-head pre-check spends both examinations re-appending A and B with start 10.5, so the queue is L(5), C(1), D(1), A(10.5), B(10.5), and nothing is freed;
     - `compute` finds the map full;
     - the drain meets L first, a live head whose start matches, and rotates it; it then removes C, which frees one slot, and stops, so D stays queued;
     - the retry stores E: `WITHIN_LIMIT`, and `size()` is 5 (A, B, L, D, E).

  The per-call counters assert that the pre-check removed 0 entries, the drain removed 1, and there were 2 insert attempts. **This test kills the "drain stops at a live head" mutant**: that drain stops at L, frees nothing, and the answer is `CEILING`. It also kills "no drain" (`CEILING`) and "drain continues past the first freed slot" (it also removes D, so the counter shows 2 removals, not 1).
- [ ] **Ceiling race, deterministic, nothing blocks inside `compute`.** `maxEntries = 1`. The `RESERVED` probe blocks on a latch only for thread A's call, identified by the thread; it runs only when `tryReserve()` returned `true`, so thread B's call never reaches it.
  - Thread A records subject 1. Its reservation succeeds and its probe waits.
  - Thread B records subject 2. B's `tryReserve()` fails, no probe runs, and B returns `CEILING`. The test reads B's result through `Future.get` with a 5 s timeout.
  - The latch is released, and A's result, also read with a 5 s timeout, is `WITHIN_LIMIT`.

  Afterwards `size()` is 1 and the count is 1. Executors are shut down in `finally`. Mutant: replace `tryReserve()` with a check of `entries.size()`. Then B stores subject 2, A stores subject 1, `size()` is 2, and the test fails on every run without hanging.
- [ ] **Capacity restored after a throw.** With `maxEntries = 1`, the test constructor's reservation probe throws an `IllegalStateException` on its first run. That `recordFailure` returns `UNAVAILABLE`, does not throw, and stores nothing; the count is 0 afterwards. A second subject's failure then returns `WITHIN_LIMIT` and is stored. A marker-append failure, injected through the test constructor's queue factory with a queue that refuses one `offer`, leaves no entry and a count of 0. The test runs twice, once with a refusal that returns `false` and once with one that throws, and the results are identical. Mutants: release the reservation only on the present-key path (the second subject gets `CEILING`); keep the entry when its marker append fails (an entry without a marker, never removed).
- [ ] **Rollback by id under interleaving.** The test uses the probe seam and a queue factory whose queue refuses the first `offer`.
  1. Thread A records subject S. A's `STORED` probe waits on a latch, so A has stored S's new entry but not yet appended its marker.
  2. Thread B records another failure for S. That updates the entry in place: same id, new value, count 2. B appends no marker, because it created no entry.
  3. A is released, its `offer` is refused, and A rolls back by id.

  Afterwards S has no entry, `size()` and the count are 0, the queue is empty, and A returns `UNAVAILABLE`. A further failure for S creates a fresh entry with its marker. Mutant: roll back with `remove(mapKey, entryJustCreated)`. Then S's updated entry stays with no marker, the invariant check finds it, and the count stays 1.
- [ ] **A refused re-append keeps the marker.** Through the queue factory, the queue refuses the next `offer` (once returning `false`, once throwing; the results must be identical). A re-opened entry's head is then examined. The examination stops, the old marker is still at the head, and the invariant check finds exactly one marker for that entry. After the queue accepts offers again, a later examination re-appends it, and once its window ends the entry is removed. Mutant: poll before offering (the entry is left without a marker, and the invariant check fails).
- [ ] **A throw during examination releases the lock.** Through the queue factory, `peek` throws once. That call returns `UNAVAILABLE` and does not throw. Afterwards a dedicated other thread's `tryLock` on the examination lock succeeds, and the next call examines normally. Mutant: unlock outside `finally` (the other thread's `tryLock` fails, and every later examination is skipped).
- [ ] **Drain under contention.** `maxEntries = 2`, with two entries whose windows have ended. A dedicated holder thread takes the examination lock through `examinationLock()`, then waits on a latch; a `ReentrantLock` re-enters on its own thread, so the holder must never be the thread under test.
  - On a second thread, from an executor, a third subject's failure (a direct `recordFailure`) is submitted. Its pre-check and its drain both skip, because `tryLock` fails. `Future.get` with a 5 s timeout returns `CEILING` while the holder still holds the lock, and the per-call counters show 0 removals.
  - The holder is released and joined. The same subject's next failure, from the test thread, is `WITHIN_LIMIT`. Its **pre-check** frees an ended entry, so no drain is needed, and the counters show the pre-check removed at least 1 and the drain removed 0.
  - Every executor is shut down in `finally`.

  Mutants: wait on the lock (`Future.get` times out); drain or pre-check without the lock (the call made while the holder holds the lock frees the ended entries and returns `WITHIN_LIMIT`, not `CEILING`, and the 0-removals assertion fails).
- [ ] **Sweep, exclusive and leak-free under concurrency.** 16 threads, released by a latch, record failures for subjects drawn from a pool of 2,000, with `maxEntries = 1,000` and a shared test clock that one thread advances one second per 100 operations. The window is 5 s. Afterwards:
  - after every thread has joined, the clock moves past every window, and one thread calls `lockState` on an absent subject until the queue is empty, capped at `queueSize()` calls (measured when the drain starts). The cap holds when entries re-opened: each marker needs at most two examinations, one to re-append a re-opened entry's marker with its stored start and one to remove it, a stale marker needs one, and each call makes two;
  - `size()` is 0, the queue is empty, and the count is 0;
  - the package-private invariant check runs only at the end, single-threaded, after all threads have joined and before the drain. It finds exactly one marker for every stored entry's id.

  The test is repeated 20 times. It is a leak and consistency test, not the kill for any named mutant. Dropping the `tryLock` is killed by "Drain under contention" (deterministic), and decrementing without checking removal is killed by "Stale markers" (deterministic).
- [ ] **Stale markers, deterministic (single thread).** The test keeps a reference to the queue built by the queue factory. It stores subject S (one entry, one marker). It then appends two stale markers: one for a map key with no entry, and one for S's key with an id different from S's entry. Both have a start at or before now. The test moves S's own marker behind them (poll it, append the two stale markers, offer it back), so the stale markers are the two heads. A `lockState` call on an absent subject examines the heads: each stale marker is polled and dropped, S's entry and live marker stay, and `size()` and the count both stay 1. After S's window ends, further calls remove S, and `size()` and the count are 0. Mutant: decrement the count without checking that the lambda removed (the count goes to 0 or negative while `size()` is 1, and the assertion fails on the first stale marker).
- [ ] **Sweep, bounded and complete (single thread).**
  - A package-private counter records each call's queue operations, counting every peek, poll and append. Over 10,000 calls against 10,000 live entries it is at most 7 per call below the ceiling and at most 55 with the drain.
  - With N = 1,000 entries whose windows have all ended and no live ones, after k calls to `lockState` on an absent subject, `size()` equals `max(0, N − 2k)`.
  - A window re-opened in place (fail, pass the window, fail again) leaves one entry and one marker, and the entry is removed once its second window ends.
  - Mutants: the pre-check examines more than two heads (the per-call counters exceed the bound, and `size()` after k calls is below `max(0, N - 2k)`).
  - After the clock steps back an hour, entries whose windows end are removed and no call exceeds its cap.
  - The class contains no `removeIf` over the map and no public `sweep()`, and makes no queue or lock call inside a map lambda (a review item named in the Outcome).
  - Mutants: restore the every-256 full sweep; drop the marker of a re-opened entry; remove without comparing the window start; stop at a future-start head.
- [ ] **Counter identity (OD-269-2).**
  - `AttemptSubjectTest`: `canonical()` of `("ab","c")` differs from that of `("a","bc")`, and `canonical()` begins with the length-prefixed label `dataprism/link/v1/attempt`.
  - `canonicalText()` is lowercase hex, twice the length of `canonical()`, and decodes back to it. A subject whose canonical bytes are not valid UTF-8 as a whole (because of the length prefixes) shows that UTF-8 decoding would lose bytes and the hex does not.
  - `vectors.properties` pins `attempt.subject.canonical` and `attempt.subject.canonical.text` for a fixed subject; the `counter.key` vector is gone.
  - `LinkKeys` has no public method that takes a principal id (reflection).
- [ ] **Follow-ups from the review of PR #154, moved here from PLAN.md:**
  - `LinkKeysTest`'s two leak assertions that cannot fail are replaced with ones that can. Line ~64 checks a message for `"shared-"`, a string the message never contains; assert instead that it contains no key id or reference the test actually supplied. Line ~177 checks a constant `toString()`; assert against a sentinel key id that the class could leak. A mutation that puts the id into the message, or into `toString()`, makes each new assertion fail.
  - The `InMemoryLinkAttemptLimiter` constructor's refusals (limit, window, `maxEntries`) throw `LinkConfigurationException` with code `INVALID_LINK_LIMIT`, not a bare `IllegalArgumentException`, and a test asserts the type and code. This applies to the limiter's constructor only: `AttemptSubject`'s constructor deliberately keeps throwing `IllegalArgumentException` for bad input.
  - Every thread pool in `InMemoryLinkAttemptLimiterTest`, `LinkAttemptGateTest` and the sweep tests is shut down in a `finally` (or try-with-resources), so a failing assertion cannot leak threads.
  - The outage stub row in the ported gate tests (`Refused(KEY_UNAVAILABLE, false)`) asserts the decision's reason `KEY_UNAVAILABLE` and its `auditValue()` `REFUSED:LINK_KEY_UNAVAILABLE`, as well as `counted()` and the untouched counter.
- [ ] **LinkCodec and LinkKeys follow-ups (moved here from the reviews of PR #154 and #160, 2026-10-23):**
  - **The `&& knownKey` guard** in `LinkCodec.open`, the line `authenticated = plain != null && knownKey`, is defence in depth behind the `UNKNOWN_KEY` return, and no test can kill its mutant. Extract it into a private static method (for example `authenticatedOnlyIfHeld`). Give it a comment saying the redundancy is deliberate and why. Add that one method to the `mutation` profile's PIT `<excludedMethods>` in `pom.xml`. The Outcome names the exclusion.
  - **Exact scope end and token end on open.** Two new `LinkCodecTest` cases: a valid link opened at `now == scopeExpiry`, and at `now == tokenExpiry`, is refused `EXPIRED`; at one second before each, it opens. Mutant: `>` for `>=` in either comparison.
  - **Unused import.** `java.time.Duration` is removed from `LinkCodec.java` if it is unused there; otherwise from `LinkCodecTest.java`. The build has no unused-import warning for either file.
  - **Held key ids checked against the key-id grammar.** `LinkKeys` treats a held id from the supplier that fails `LinkSettings.validKeyId` (1 to 16 characters of `[A-Za-z0-9_-]`) as absent, as it already treats a null entry. It never appears in `heldKeyIds()`, and `open` treats it as `UNKNOWN_KEY`. A `LinkKeysTest` case supplies `"bad.id"` and a 17-character id beside a valid one, and asserts that only the valid id is held. Mutant: drop the check.
- [ ] **Old uses removed.** No main or test source in the reactor names `attemptCounterKey` or `LinkKeys.ATTEMPT_LABEL`. At the base there are 15 test references: 6 in `InMemoryLinkAttemptLimiterTest` and 9 in `LinkKeysTest`. Each is removed, or ported to `AttemptSubject` or `LinkAttemptGate`, and the Outcome lists each.
- [ ] **No key dependency (ArchUnit).** `LinkArchitectureTest` fails when `LinkAttemptLimiter`, `InMemoryLinkAttemptLimiter`, `AttemptSubject`, `LinkOpener` or `LinkAttemptGate` depends directly on `LinkKeys` or `SecretKeyProvider`. A nested fixture proves the rule can fail.
- [ ] **No bypass (ArchUnit).** `LinkArchitectureTest` fails when a main class outside `core.link`, by a call **or a method reference** (`codec::open`, `limiter::recordFailure`):
  - uses `LinkCodec.open`; or
  - uses `LinkAttemptLimiter.lockState` or `recordFailure`, on the interface or on any implementation. The one exception is a class in `io.github.aindriub.dataprism.hazelcast..` that itself implements `LinkAttemptLimiter`, and it is exempt from this bullet only: it must still not call or reference `LinkCodec.open` (coordinator decision, 2026-10-23). The fixture `hazelcast.archfixture.HzLimiterOpensTokens` proves it.

  Nested fixtures that call or reference each method make the rule fail. So does one that implements the SPI outside the Hazelcast package. `LinkLimiterFloorFixture` (see Owns) passes.
- [ ] **Only sanctioned implementations (ArchUnit).** `LinkArchitectureTest` fails when a class in this repository outside `io.github.aindriub.dataprism.core.link..` and `io.github.aindriub.dataprism.hazelcast..` implements `LinkAttemptLimiter`, directly or through a superclass. That way no class in the repository can ship a no-op limiter. A nested fixture in the architecture test package that implements `LinkAttemptLimiter` makes the rule fail, and `LinkLimiterFloorFixture` passes. The rule covers this repository's classes. An application-supplied limiter bean is still allowed by design (255, 264), and the gate treats its `null` or throwing answers as refusals.
- [ ] **No split package (ArchUnit).** `LinkArchitectureTest` fails when any imported class in `io.github.aindriub.dataprism.core.link..` (main classes and explicitly imported fixtures alike) is loaded from anywhere other than `data-prism-core`'s main classes directory. The rule is applied by location to every class it is given, never filtered to main classes first. The test uses the class's source location, compared with `ModuleGraph.mainClassesDirectories` for `data-prism-core`. `SplitPackageFixture` (see Owns), packaged in `core.link` and compiled into data-prism-architecture's test classes, proves the rule can fail.
- [ ] **No leak.** The in-memory map's keys are 32-character hex strings that contain neither id, in plain or hex form. `AttemptSubject.toString()`, `GateDecision.toString()`, and the limiter's and the gate's `toString()`, contain neither id; nor does any exception message (sentinel ids). `canonicalText()` is used only as an HMAC input and is never logged.
- [ ] `clusterWide()` is `false` for `InMemoryLinkAttemptLimiter` and for an implementation that does not override it.
- [ ] `sealed-links.md` and `batch-and-bulk-follow.md` carry the amendments below, word for word in substance.
- [ ] A whole-component bug hunt over the limiter, the gate and the sweep runs before merge: races, the ceiling, the clock stepping back, the exclusive examination, and null returns. Each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-architecture -am verify` passes. Report the counts.

## Design amendments (recorded by this task)

**sealed-links.md:**
- **Section 3, "Fingerprinting" row:** drop "and for the attempt-counter key (section 4)".
- **Section 3, "MAC input labels" table (242 round 4):** change the row "Attempt counter key (16 bytes, hex)" to "Attempt subject input (`AttemptSubject.canonical()`; MACed by the limiter under its own key, never under the fingerprint key)", keeping the first item `dataprism/link/v1/attempt` and the rest "the scope id, the principal id". Replace the sentence after the table with "Task 269 removed `LinkKeys.attemptCounterKey`; the label stays as the first item of `AttemptSubject.canonical()`, so a counter input can never equal a fingerprint or catalog-tag input."
- **Section 4, the `link` disposition row and the closed set:**
  - Append to the closed-set cell: "and, for `LINK_ATTEMPTS_EXHAUSTED`, the cause from a second closed set: `LOCKED`, `OVER_LIMIT`, `CEILING`, `LIMITER_UNAVAILABLE`, `OPEN_FAILED` (task 269)". Append to the shape cell: "a cause is `REFUSED:LINK_ATTEMPTS_<CAUSE>`".
  - Replace "The closed set has eleven reasons." with "The closed set has sixteen reasons, `KEY_UNAVAILABLE` included, and the exhausted causes are a separate closed set of five. The table above lists twelve; the other four, `WRONG_TOOL`, `FILTER_MISMATCH`, `LABELS_CHANGED` and `CATALOG_CHANGED`, are decided after authentication and are never counted. `DEPTH_EXCEEDED` existed only for rejected option (b) and is withdrawn (OD-200-8); it is not in the set." (Coordinator correction, 2026-10-23: the first text said twelve, but `LinkReason` has 16.)
  - In section 9's table, change the `LINK_<REASON>` row to "sixteen audit-only reason codes and five `LINK_ATTEMPTS_<CAUSE>` codes" (coordinator correction, 2026-10-23).
- **Section 4, the client codes (OD-269-5 (b), 2026-10-23):** add `LINK_CHECK_UNAVAILABLE` to the closed set of client codes that section 4 states beside `LINK_INVALID` and `LINK_ATTEMPTS_EXHAUSTED`, and state: "`LINK_CHECK_UNAVAILABLE` is retryable. It answers a limiter outage (`LIMITER_UNAVAILABLE`), which does not depend on the token and so reveals nothing. A failure to open the token (`OPEN_FAILED`) is answered `LINK_INVALID`, because it depends on the token. The audit records both causes." The T7 row and the "Oracle analysis" need no change.
- **Section 9, the closing table:** add the row `| `LINK_CHECK_UNAVAILABLE` | refusal code (retryable) | 0.8 |` after `LINK_ATTEMPTS_EXHAUSTED`.
- **contract-register.md, section 3.1 (call refusals), after the `LINK_ATTEMPTS_EXHAUSTED` row:** add `| `LINK_CHECK_UNAVAILABLE` | proposed | 0.8 | design/sealed-links.md section 9 | OD-269-5 (b), owner, 2026-10-23 | refusal code, retryable. A limiter outage; independent of the token. `OPEN_FAILED` is not a code: it is answered `LINK_INVALID`. |`. Change only this row.
- **Section 4, the round-2 "Binding bounds" bullet:** replace its last sentence, "`attemptCounterKey` still throws for an id over the limit, so the caller checks the bounds before it (briefs 251, 252; 269 replaces `attemptCounterKey`).", with "`LinkAttemptGate.open` checks the same bounds, and an empty id or token, before anything else and answers `MALFORMED`, uncounted, without calling the limiter or the codec; `attemptCounterKey` is removed (task 269)."
- **Section 4, the post-authentication `MALFORMED` bullet:** replace "Callers gate the limiter on `refused.counted()`." with "`LinkAttemptGate.open` records a failure only when `refused.counted()` is true; no other caller touches the limiter (task 269)."
- **Section 4, "Attempt limiter", replace the counter-key bullet (`HMAC(fingerprintKey, scopeId | principalId)`) with:** "It counts per principal within a scope. The limiter receives the subject: the scope id and principal id, each 1 to 65,535 UTF-8 bytes; outside that bound, or empty, the call is `MALFORMED`, uncounted. Its input is `lengthPrefixed("dataprism/link/v1/attempt", scopeId, principalId)`, and `canonicalText()` is that input as lowercase hex for `String`-keyed APIs. The input is keyed under a key that does not rotate with the link key: `HMAC-SHA256(k, input)` truncated to 16 bytes, where `k` is a per-instance `SecureRandom` key in memory, and `KeyedNames.mapKey` under the cluster name key in the shared limiter (task 264). Every member therefore derives the same key, and a link-key rotation neither lifts a lock nor splits a count. Changing the name key is a planned full restart that resets every count at once (cluster-security 4.6). No map holds a scope id or principal id in the clear (boundary 6) (task 269, 2026-10-22)."
- **Section 4, add:** "Code outside `core.link` opens a token only through `LinkAttemptGate.open`. That one call:
  - checks the inputs and the binding bounds (`MALFORMED`, uncounted);
  - checks the lock, refusing a locked principal before the token is opened, valid links included;
  - opens the token;
  - on a counted refusal, makes one atomic `recordFailure`, which returns whether the new count is within the limit.

  An attempt over the limit is answered `LINK_ATTEMPTS_EXHAUSTED`. A limiter outage is answered with the retryable `LINK_CHECK_UNAVAILABLE`, which does not depend on the token and so reveals nothing. A failure to open is answered `LINK_INVALID`, because it depends on the token and a distinct code would tell an attacker which input trips it (OD-269-5 (b), owner, 2026-10-23). The audit's `link` value records the distinct cause in both cases.

  Guarantee: at most `invalid-attempt-limit` counted failures per window are answered `LINK_INVALID`. The number of guesses that can reveal a success is at most that limit plus the attempts already past the lock check when the lock engaged.

  Each instance holds at most a fixed number of entries (100,000 by default), reserved atomically. At that ceiling a counted failure for a subject not yet held is answered `LINK_ATTEMPTS_EXHAUSTED` and is not stored. No live entry is evicted, so a flood can neither lift a lock nor lock out a principal with a valid link. The cost, accepted by the owner under OD-269-3: while the ceiling holds, a subject that is not stored is never locked, so it can keep guessing against the 128-bit tag.

  In memory, every entry has exactly one expiry marker. One examiner at a time (a non-blocking try-lock) looks at a bounded number of markers per call, and decides inside the map's atomic update whether the entry's window has ended. There is never a full sweep on the request thread. Any limiter failure, `null` answer or failure to open is refused, never passed (task 269, 2026-10-22; revised 2026-10-23)."
- **Section 6, boundary 3:** replace "with a time-to-live" with "whose time-to-live, at least the window plus the clock-skew tolerance from the last write, only bounds memory; the window is decided by the stored start and the clock".
- **Section 7, obligation 22, append:** "Concurrency: for N > `invalid-attempt-limit` parallel forged attempts against a fresh subject, each a counted failure that passes the lock check, exactly `invalid-attempt-limit` are answered `LINK_INVALID` and the other N − `invalid-attempt-limit` `LINK_ATTEMPTS_EXHAUSTED`. Ceiling: a full map answers new subjects `CEILING`, keeps existing locks, lets valid links proceed, and does not lock a subject it cannot store (OD-269-3). Gate: a locked principal's valid link is refused before the token is opened, and every `Exhausted` carries an audit-only cause. Architecture: nothing outside `core.link` calls or references `LinkCodec.open` or the limiter, except a Hazelcast implementation of the limiter, and `core.link` lives only in `data-prism-core`."

**batch-and-bulk-follow.md, Part A "What happens in a call" (0.9 design; these amendments keep it consistent with the gate):**
- **Item 1:** after the sentence about the limiter pre-check, add "(0.9: through a lock-only method on `LinkAttemptGate`, to be added with bulk follow; task 269 provides only `open`)".
- **Item 2:** replace "(the count key is `HMAC(fingerprintKey, scopeId | principalId)` hex-encoded, as there; this design adds no map)" with "(each link is opened through `LinkAttemptGate.open`, keyed by `AttemptSubject` as task 269 specifies; this design adds no map)".
- **Item 2:** replace "When a failure brings the counter to the limit, verification stops, the call is refused as a whole with `LINK_ATTEMPTS_EXHAUSTED`, and nothing is read or returned, including for the links already verified." with "When the gate answers `Exhausted` for a link, verification stops, the call is refused as a whole with that decision's client code, and nothing is read or returned, including for the links already verified. The failure that brings the counter to the limit is still answered `LINK_INVALID`. The next link is refused at the gate's lock check."
- **Item 2:** replace "a call with many forged links ends at the twentieth counted failure, and a call with fewer counted failures than the limit goes on." with "a call with many forged links ends at the link after the twentieth counted failure in the window (the twentieth locks the principal, and the next link is refused at the lock check), and a call with fewer counted failures than the limit goes on."

## Out of scope
- The Hazelcast limiter, its map, its entry processor and its ceiling (264, which implements this contract within "may relax / must keep").
- Wiring the gate into `follow` (252), `list_sources` page links (250) and Spring (255); issuing links (251); and documenting the audit values in `docs/audit.md` (261).
- Documenting `LINK_CHECK_UNAVAILABLE` in `docs/tools.md` and `docs/audit.md` (261). OD-269-5 itself is decided.
- The lock-only gate method for bulk follow (0.9).
- Any Spring property or bean, including a capacity property (OD-269-3 (a): none in 0.8).
- A metric for saturation or attempts: `Metric.java` belongs to 241 in this wave. Expose `size()` package-private only.
- Any other change to `LinkCodec` (only the four follow-ups above), `LinkBinding`, `LinkReason` and `LinkConfigurationException`; and the rest of `LinkKeys` (`forScope`, the pseudonymisation digest, the fingerprint and catalog-tag MACs; `heldKeyIds` only gains the grammar check).
- Any other `pom.xml` change.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Use neutral, invented identifiers in tests, and generated test key bytes.
- No sleeps. Windows use an injected clock, and ordering uses latches. Nothing a test controls blocks inside a map lambda.
