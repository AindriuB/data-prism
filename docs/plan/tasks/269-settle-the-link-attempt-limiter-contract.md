# 269 — Settle the sealed-link attempt limiter's contract: atomic record-and-decide, a bounded map, and a counter key that survives rotation

**Repo:** `.`
**Release:** 0.8.0 (links stay off by default through 0.9, sealed-links OD-7)
**Base branch:** the 0.8 integration branch, at a commit that contains 242 and its external-review fixes (`record/0.8-wave3`, PR #154).
**Depends on:** 242 (`core.link`: `LinkAttemptLimiter`, `InMemoryLinkAttemptLimiter`, `LinkKeys`, `LinkOpenResult.Refused.counted()`)
**Decisions:** owner decision 2026-10-22: settle the limiter contract in this separate task, before 251 and 264 build on it. **Decided by the owner 2026-10-22:** OD-269-1 (a), OD-269-2 (a), OD-269-3 (a) and OD-269-4 (coordinator: 269 added to 250's Depends on). "Required shape" is written to these decisions.
**Wave:** 4 (combined 0.8 graph of 213, 218 to 231 and 238 to 269; 203 and 206 to 211 counted as landed). It runs alongside 223, 241 and 248; it owns none of their files.
**Serialised with:** none in wave 4. 251 and 264 depend on this task. 250 and 252 read the contract: 252 reaches it through 251, and 250 should get an edge too (see OD-269-4).
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptLimiter.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiter.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/AttemptSubject.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptGate.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkKeys.java *(removing `attemptCounterKey` and the Javadoc that mentions it only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiterTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkAttemptGateTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/AttemptSubjectTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkKeysTest.java *(the counter-key test and the vector-writer line only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkVectors.java *(the counter-key entry only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkTestSupport.java *(insertions only)*
- data-prism-core/src/test/resources/link/vectors.properties *(replace `counter.key` with `attempt.subject.canonical`)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/LinkArchitectureTest.java *(insertions only: the gate rule and its fixtures)*
- docs/plan/design/sealed-links.md *(section 3 "Fingerprinting" row, section 4 "Attempt limiter", section 6 boundary 3 and section 7 obligation 22 only)*

## Goal
Task 242 built the limiter as "check `locked`, then `recordFailure`". That lets concurrent forged attempts get past the limit. Its map has no ceiling and is swept on the request thread. Its counter key is derived from the current link key, so a link-key rotation lifts every lock, a shared limiter splits counts during the roll, and `attemptCounterKey` throws when the current key cannot be read. This task replaces that with an atomic contract that a Hazelcast entry processor can also honour (264). It adds a fixed ceiling that fails closed without letting an attacker lock out honest users, a sweep that costs O(1) per request, and a counter identity that does not depend on the link key. The gate that callers 250 and 252 must use lives in `core.link`, so the order of steps is written and tested once.

## Context
- docs/plan/design/sealed-links.md section 4 "Attempt limiter" (counted reasons, per principal within a scope, fixed window decided by the stored start and the clock, a lock that refuses valid links too), section 3 "Key derivation" (the `fingerprint` label is used today for the counter key) and "Rotation" (phase 2: members issue under different key ids), section 6 boundary 3, and section 7 obligation 22.
- docs/plan/tasks/retired/242-sealed-link-codec-and-attempt-limiter.md, Outcome, "Notes for later tasks" and both "External review of PR #154" sections. In particular, gate on `Refused.counted()` and never on `LinkReason.counted()`. The four limiter findings are what this task settles.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiter.java: the window logic (`ended`, saturating `LinkLayout.plus`) to keep. The `SWEEP_EVERY` full-map sweep and the public `sweep()` go.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkKeys.java:159-170: `attemptCounterKey` calls `forScope(scopeId)`, which throws `LINK_KEY_UNRESOLVED` when the current key is unreadable.
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiterTest.java: the existing cases (window, isolation, sixteen threads, `Long.MAX_VALUE` window, the test gate at line ~200). Port them to the new SPI; none may be weakened.
- docs/plan/design/cluster-security.md 2.3.2 and 4.6: the name key is separate from the cluster key, is the same on every member, and **does not rotate in the rolling procedure**. Changing it is a planned full restart with loss of state. Every shared map key is `KeyedNames` under it.
- docs/plan/tasks/264-shared-hazelcast-link-attempt-limiter.md "Required shape": today it keys the map on `LinkKeys.attemptCounterKey(...)`. This task removes that method, and 264 keys on `AttemptSubject.canonical()` instead (see the dated bullet in 264).
- docs/conventions.md "Tests": an injected `Clock`, no sleeps. Use latches for ordering.

## Owner decisions (decided 2026-10-22)

**OD-269-1: the atomic contract. Decided (a), 2026-10-22, owner.**
- (a) **Verify, then one atomic record-and-decide (recommended).** The gate checks `locked` first, as a cheap pre-check. It then opens the token, which is pure CPU and no I/O. On a counted refusal it calls `recordFailure`. That call atomically opens or extends the window, increments the count, and returns whether the attempt is still within the limit (`count <= limit` after the increment). An attempt over the limit is answered `LINK_ATTEMPTS_EXHAUSTED`, not `LINK_INVALID`, so at most `limit` counted attempts per window ever learn their verification result, however many run at once. There is one write per failed attempt, with no reservation and no undo. Concurrent valid links are never penalised, and a crash cannot leak a reservation. In Hazelcast this is one entry processor that returns the new count.
- (b) Reserve, then commit or release (`tryAttempt`). This is exact, but every attempt writes twice. Honest concurrent follows use up reservations, so a principal making 20 concurrent valid follows is refused. A member that dies while holding a reservation leaks it, which needs its own expiry in 264.
- (c) Record first, then undo on success. Every valid follow writes twice. Honest concurrency is penalised as in (b). The undo has to find the same window, or it decrements a new one.

**OD-269-2: the counter key. Decided (a), 2026-10-22, owner.**
- (a) **The SPI takes the subject; each implementation keys it under its own non-rotating key (recommended).** `LinkAttemptLimiter` takes `AttemptSubject(scopeId, principalId)`. The in-memory limiter keys its map by `HMAC-SHA256(k, subject.canonical())`, truncated to 16 bytes, where `k` is 32 random bytes drawn per instance at construction. 264 keys its map by `KeyedNames` under the cluster **name key**, as every other shared map is keyed. `LinkKeys.attemptCounterKey` is deleted, so the limiter never reads link-key material. Why the name key's rotation is acceptable: it never rotates in the rolling procedure, so every member derives the same key throughout a link-key or cluster-key rotation. Changing it is a planned full restart that drops all cluster state on every member at once, so counts reset together and never split. The reset is worth at most `limit` further guesses per principal against a 128-bit tag. The per-instance random key in memory is acceptable because in-memory counts already die with the process. This also removes the throw: a link-key outage cannot affect the limiter.
- (b) A dedicated HKDF label, `dataprism/link/v1/attempt`, over the pseudonymisation key. That key is shared by every member and rotates only with a full re-pseudonymisation. This reverses 242's review decision that `LinkKeys` keeps only a digest of the pseudonymisation key, and couples links to the identity key.
- (c) A separate, non-rotating "attempt key" property. That is one more secret for operators to provision, for no gain over the name key.

**OD-269-3: the ceiling, and what happens at it. Decided (a), 2026-10-22, owner.**
- (a) **Recommended.** The default is 100,000 entries per instance, roughly 20 MB at most. It is a constructor parameter, with no `dataprism.*` property in 0.8. At the ceiling, a counted failure for a subject **not already in the map** is answered over the limit (`LINK_ATTEMPTS_EXHAUSTED`) and is not stored. Subjects already in the map keep counting, and **no live entry is ever evicted**. An attacker who fills the map therefore cannot lift anyone's lock (eviction would) or lock anyone out. `locked` for an absent subject is `false`, so honest users with valid links proceed. Only new forgers get no verification answer, and that is the fail-closed direction.
- (b) As (a), but with a property `dataprism.links.invalid-attempt-capacity` bound by 255.
- (c) Evict the oldest entry. Rejected: an attacker can lift a locked principal's lock by flooding the map.

**OD-269-4: an edge from 250. Decided 2026-10-22, coordinator: done.** 250 counts page-link failures in the same limiter but does not reach this task through 251. `269` is added to 250's Depends on, with a dated Context bullet in 250 (the coordinator's edit, since this task may not edit that file).

## Required shape (later tasks read these; matches the owner's decisions above)
- `AttemptSubject(String scopeId, String principalId)`, a record. Both parts must be non-null and non-empty; otherwise the constructor throws `IllegalArgumentException` with a message that names no value. `canonical()` returns the length-prefixed bytes of scope then principal, through `LabelledHkdf.lengthPrefixed`, so `("ab","c")` and `("a","bc")` differ. `toString()` is `AttemptSubject[redacted]`.
- `LinkAttemptLimiter`:
  - `boolean locked(AttemptSubject subject)`: `count >= limit` within the current window.
  - `Admission recordFailure(AttemptSubject subject)`: one atomic operation that opens a window if none is live, adds one (saturating), and returns `WITHIN_LIMIT` when the new count is at most `limit`, otherwise `OVER_LIMIT`.
  - `default boolean clusterWide()` returning `false`.
  - `enum Admission { WITHIN_LIMIT, OVER_LIMIT }` is nested.
  - The Javadoc states the obligations a shared implementation (264) must meet: atomic per subject, window decided by the stored start and the clock and never by an entry expiring, no live entry evicted, a ceiling that answers `OVER_LIMIT` for new subjects, and fail closed (`locked` is `true` and `recordFailure` returns `OVER_LIMIT` on any internal failure).
- `InMemoryLinkAttemptLimiter(int limit, Duration window, Clock clock)`, plus `(int limit, Duration window, Clock clock, int maxEntries)`. `maxEntries < 1` is `INVALID_LINK_LIMIT`, and the default is `DEFAULT_MAX_ENTRIES = 100_000`. A package-private constructor takes the HMAC key for tests. Entries are created only by `recordFailure`.
- **Sweep.** Each entry created appends `(mapKey, windowStart)` to a FIFO expiry queue. Every `locked` and `recordFailure` call first removes at most two queue heads whose window has ended, and removes the map entry only if its stored window start still equals the queued one. Each entry is queued once and dequeued at most once, so the amortised cost per call is O(1). At the ceiling, before answering `OVER_LIMIT`, the call drains ended heads, which is still O(1) amortised. There is no full-map sweep, no `removeIf` over the map, and no background thread.
- `LinkAttemptGate(LinkAttemptLimiter)`, the only sanctioned caller of the limiter outside `core.link` and the SPI's implementations:
  - `boolean admit(AttemptSubject subject)` is `false` when the subject is locked, the subject is null, or the limiter throws.
  - `GateDecision afterOpen(AttemptSubject subject, LinkOpenResult result)` returns `PROCEED` for `Opened`. For `Refused` with `counted() == false` it returns `LINK_INVALID`. For `Refused` with `counted() == true` it calls `recordFailure` and returns `LINK_INVALID` on `WITHIN_LIMIT`, and `ATTEMPTS_EXHAUSTED` on `OVER_LIMIT` or when the limiter throws.
  - `enum GateDecision { PROCEED, LINK_INVALID, ATTEMPTS_EXHAUSTED }`.
  - It never reads `LinkReason.counted()`. No exception text from the limiter reaches the caller.
- `LinkKeys.attemptCounterKey` is removed. The `fingerprint` label keeps its other uses.

## Acceptance
- [ ] Tests are written first and fail on the base commit (they do not compile against 242's SPI); the Outcome names them. Each failing-input test below names the mutant it kills.
- [ ] **Race (OD-269-1).** In `LinkAttemptGateTest`, with `limit = 20` and 64 threads released together by a latch, each thread runs `admit`, opens a forged token and calls `afterOpen` for one subject. Exactly 20 results are `LINK_INVALID` and 44 are `ATTEMPTS_EXHAUSTED`, repeated 50 times. A deterministic interleaving: two attempts both pass `admit` at count 19 (latched), and both fail; exactly one gets `LINK_INVALID` and one `ATTEMPTS_EXHAUSTED`. Mutants killed: `recordFailure` deciding from a count read before the increment; the gate deciding from `locked` instead of the `Admission`.
- [ ] **Lock semantics kept.** After 20 counted failures, a valid link is `ATTEMPTS_EXHAUSTED` at `admit`, and so is a genuine link that would be refused uncounted (`EXPIRED`). Another principal in the same scope, and the same principal in another scope, are unaffected. The window ends at stored start plus window, increments do not extend it, and the `Long.MAX_VALUE`-second window still constructs and locks. Sixteen threads recording concurrently lose no count. Mutants: extend the window on increment; key by scope only; count every reason.
- [ ] **Gate on `counted()`.** A `Refused(MALFORMED, false)` (post-authentication) and a `Refused(KEY_UNAVAILABLE, false)` are `LINK_INVALID` and record nothing. A `Refused(AUTH_FAILED, true)` records once. Mutant: gating on `reason().counted()`.
- [ ] **Fail closed.** A limiter whose `locked` throws makes `admit` `false`. One whose `recordFailure` throws gives `ATTEMPTS_EXHAUSTED`. `admit(null)` is `false`. No exception escapes the gate, and no limiter message text appears in any result or exception (sentinel).
- [ ] **Ceiling (OD-269-3).** With `maxEntries = 4`, four subjects have one failure each. A fifth subject's counted failure returns `OVER_LIMIT`, and `size()` stays 4. The fifth subject's valid link gets `PROCEED`. A sixth subject's valid link also proceeds, so nobody is locked out by the flood. A subject locked before the map filled stays locked while new subjects keep arriving. Mutants: evict the oldest entry (a lock lifts); refuse every subject at the ceiling (an honest valid link is refused); store the fifth entry anyway (`size()` is 5). Once the first entries' window has ended, the fifth subject's next failure is admitted (`WITHIN_LIMIT`), with no explicit sweep call.
- [ ] **O(1) sweep.** A package-private counter of entries examined per call is at most 2 on every `locked` and `recordFailure` call, over 10,000 calls against 10,000 live entries. After the clock passes several windows with 100,000 failures over distinct subjects, `size()` is at most the entries created in the last window plus 2. The class contains no call to `entrySet().removeIf`, and has no public `sweep()`. Mutant: restore the every-256 full sweep (examined count over 2).
- [ ] **Counter identity (OD-269-2).** In `AttemptSubjectTest`, `canonical()` of `("ab","c")` differs from that of `("a","bc")`, and `vectors.properties` pins `attempt.subject.canonical` for a fixed subject, so 264 can reproduce it. A subject locked under one `LinkKeys` stays locked after the gate is used with a `LinkKeys` whose current key id has rotated. With a provider that throws for every key id, `admit` still answers from the limiter, and a locked subject stays `ATTEMPTS_EXHAUSTED` with no exception. (On the base commit, `attemptCounterKey` throws `LINK_KEY_UNRESOLVED` here.) `LinkKeys` has no public method that takes a principal id (reflection). The `counter.key` vector is gone.
- [ ] **No leak.** The in-memory map's keys are 32-character hex strings that contain neither the scope id nor the principal id, in plain or hex form. `AttemptSubject.toString()`, the limiter's `toString()`, the gate's `toString()` and every exception message contain neither id (sentinel ids).
- [ ] `clusterWide()` is `false` for `InMemoryLinkAttemptLimiter` and for an implementation that does not override it.
- [ ] `LinkArchitectureTest` gains a rule. A class outside `core.link` calling `LinkAttemptLimiter.locked` or `recordFailure` (on the interface or any implementation) fails, unless that class itself implements `LinkAttemptLimiter`; that exception is for 264's local floor. A nested fixture that calls `recordFailure` directly proves the rule can fail, and a fixture implementing the SPI that delegates proves the exception.
- [ ] `sealed-links.md` carries the amendment below, word for word in substance.
- [ ] A whole-component bug hunt over the limiter, the gate and the sweep (races, ceiling, clock moving backwards) runs before merge; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-architecture -am verify` passes. Report the counts.

## Design amendment (sealed-links.md, recorded by this task)
- **Section 3, "Fingerprinting" row:** drop "and for the attempt-counter key (section 4)". The counter key is no longer derived from the link key.
- **Section 4, "Attempt limiter", replace the counter-key bullet with:** "It counts per principal within a scope. The limiter receives the subject (scope id, principal id) and keys it under a key that does not rotate with the link key: a per-instance random key in memory, and the cluster name key (`KeyedNames`) in the shared limiter (task 264), so every member derives the same key and a link-key rotation neither lifts a lock nor splits a count. Changing the name key is a planned full restart that resets every count at once (cluster-security 4.6). No map holds a scope id or principal id in the clear (boundary 6) (task 269, 2026-10-22)."
- **Section 4, add:** "The contract is atomic. The caller checks `locked`, opens the token, and on a counted refusal makes one atomic `recordFailure` that returns whether the new count is within the limit. An attempt over the limit is answered `LINK_ATTEMPTS_EXHAUSTED`, so at most `invalid-attempt-limit` counted attempts per window learn their verification result, whatever the concurrency. Callers use `LinkAttemptGate`, never the limiter directly. Each instance holds at most a fixed number of entries (100,000 by default). At that ceiling a counted failure for a subject not yet held is answered `LINK_ATTEMPTS_EXHAUSTED` and not stored. No live entry is evicted, so a flood can neither lift a lock nor lock out a principal with a valid link. Expired entries are removed at O(1) amortised cost per call, never by a full sweep on the request thread. Any limiter failure is treated as locked (task 269, 2026-10-22)."
- **Section 6, boundary 3:** replace "with a time-to-live" with "whose time-to-live only bounds memory; the window is decided by the stored start and the clock".
- **Section 7, obligation 22, append:** "Concurrency: N parallel forged attempts against a fresh subject yield exactly `invalid-attempt-limit` `LINK_INVALID` and the rest `LINK_ATTEMPTS_EXHAUSTED`. Rotation: a lock survives a link-key rotation. Ceiling: a full map answers new subjects over the limit, keeps existing locks, and lets valid links proceed."

## Out of scope
- The Hazelcast limiter, its map, its entry processor and its ceiling (264, which implements this contract).
- Wiring the gate into `follow` (252) and `list_sources` page links (250), and issuing links (251).
- Any Spring property or bean, including a capacity property (255, and only if OD-269-3 (b) is chosen).
- A metric for saturation or attempts: `Metric.java` belongs to 241 in this wave. Expose `size()` package-private only.
- Other `LinkKeys` behaviour (`heldKeyIds`, `forScope`, the pseudonymisation digest); the codec; `LinkReason`.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Use neutral, invented identifiers in tests, and generated test key bytes.
- No sleeps. Windows use an injected clock, and ordering uses latches.
