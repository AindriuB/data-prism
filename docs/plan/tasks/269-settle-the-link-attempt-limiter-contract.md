# 269 — Settle the sealed-link attempt limiter's contract: atomic record-and-decide, a bounded map, and a counter key that survives rotation

**Repo:** `.`
**Release:** 0.8.0 (links stay off by default through 0.9, sealed-links OD-7)
**Base branch:** the 0.8 integration branch, at a commit that contains 242 and both rounds of its external-review fixes (`record/0.8-wave3` at 7cf7c1a3 or later, PR #154).
**Depends on:** 242 (`core.link`: `LinkAttemptLimiter`, `InMemoryLinkAttemptLimiter`, `LinkKeys`, `LinkCodec.open`, `LinkBinding.withinBounds`, `LinkOpenResult.Refused.counted()`)
**Decisions:** owner decision 2026-10-22: settle the limiter contract in this separate task, before 251 and 264 build on it. **Decided by the owner 2026-10-22:** OD-269-1 (a), OD-269-2 (a), OD-269-3 (a) and OD-269-4 (coordinator: 269 added to 250's Depends on). "Required shape" is written to these decisions. Revised 2026-10-23 after the external review of PR #155. The review did not change any decision. It replaced the two-step gate with one call, stated the real bounds and the ceiling's consequence, and fixed the sweep and the binding bounds. The owner accepted the ceiling consequence under OD-269-3 on 2026-10-23.
**Wave:** 4 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed). It runs alongside 203, 206, 223, 241 and 248; it owns none of their files.
**Serialised with:** none in wave 4. 250, 251, 252, 255 and 264 depend on this task, directly or through 251.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptLimiter.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiter.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/AttemptSubject.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkAttemptGate.java *(new; `GateDecision` is nested)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/link/LinkKeys.java *(removing `attemptCounterKey` and the Javadoc that mentions it only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiterTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkAttemptGateTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/AttemptSubjectTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkKeysTest.java *(the counter-key test and the vector-writer line only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkVectors.java *(the counter-key entry only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/LinkTestSupport.java *(insertions only)*
- data-prism-core/src/test/resources/link/vectors.properties *(replace `counter.key` with `attempt.subject.canonical`)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/LinkArchitectureTest.java *(insertions only: the three rules below and their fixtures)*
- docs/plan/design/sealed-links.md *(section 3 "Fingerprinting" row, section 4 "Attempt limiter", section 6 boundary 3 and section 7 obligation 22 only)*

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
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiterTest.java: the existing cases, including the window, isolation, sixteen threads, the `Long.MAX_VALUE` window, and the test gates `aLockedPrincipalIsRefusedEvenWithAValidLinkAtTheCallersGate` and `uncountedRefusalsLeaveTheCounterUntouchedAtTheGate`. Port them to the new SPI, and the gate cases to `LinkAttemptGate`; none may be weakened.
- docs/plan/design/cluster-security.md 2.3.2 and 4.6: the name key is separate from the cluster key, is the same on every member, and does not rotate in the rolling procedure. Each map gets its own name subkey (`dataprism/cluster/v1/name/<map>`).
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

## Required shape (later tasks read these; matches the owner's decisions above)
- **`AttemptSubject`.**
  - A record `AttemptSubject(String scopeId, String principalId)`. Each id must be non-null, non-empty and at most `LinkBinding.MAX_ID_BYTES` (65,535) UTF-8 bytes; otherwise the constructor throws `IllegalArgumentException` with a message that names no value.
  - `static Optional<AttemptSubject> of(LinkBinding binding)` is empty when the binding is outside those bounds. It never throws.
  - `canonical()` is `LabelledHkdf.lengthPrefixed` of three items: the ASCII label `dataprism/link/v1/attempt`, the scope id and the principal id. The label separates counter inputs from fingerprint inputs and from other keyed names, and the length prefixes make `("ab","c")` differ from `("a","bc")`.
  - `toString()` is `AttemptSubject[redacted]`.
- **`LinkAttemptLimiter`** (the SPI):
  - `boolean locked(AttemptSubject subject)` is `count >= limit` within the current window.
  - `Admission recordFailure(AttemptSubject subject)` is one atomic operation. It opens a window if none is live (absent, or ended), adds one (saturating), and returns `WITHIN_LIMIT` when the new count is at most `limit`, otherwise `OVER_LIMIT`.
  - `default boolean clusterWide()` returns `false`.
  - `enum Admission { WITHIN_LIMIT, OVER_LIMIT }` is nested.
  - The Javadoc lists what a shared implementation (264) must also meet:
    - atomic per subject;
    - the window is decided by the stored start and the clock, never by an entry expiring;
    - no live entry is evicted;
    - a ceiling at which new subjects get `OVER_LIMIT` and are not stored;
    - fail closed: on any internal failure `locked` is `true` and `recordFailure` is `OVER_LIMIT`.
- **`InMemoryLinkAttemptLimiter`.**
  - Constructors: `(int limit, Duration window, Clock clock)` and `(int limit, Duration window, Clock clock, int maxEntries)`. `maxEntries < 1` is `INVALID_LINK_LIMIT`, and the default is `DEFAULT_MAX_ENTRIES = 100_000`.
  - The HMAC key is 32 bytes from `SecureRandom`, drawn once at construction. A package-private constructor takes the key, for tests only.
  - The map key is the 16-byte truncated `HMAC-SHA256(k, subject.canonical())`, as 32 hex characters.
  - **Entries and the count.** Only `recordFailure` creates or re-opens entries. The entry count is an `AtomicInteger`, changed inside the map's `compute` for that key. A new key increments it first, and if the result exceeds `maxEntries` it decrements again, stores nothing and returns `OVER_LIMIT`. Every removal decrements it. So the ceiling holds exactly under concurrency, and re-opening a window in place does not change the count.
  - **Sweep.** Every window opening, whether a new key or a window re-opened in place, appends `(mapKey, windowStart)` to a FIFO expiry queue. Every `locked` and `recordFailure` call first polls at most **two** queue heads whose window has ended. For each, it removes the map entry only if the entry's stored window start equals the queued one, through `computeIfPresent` returning `null`. A stale marker whose entry was re-opened is dropped without touching the entry. At the ceiling, before answering `OVER_LIMIT`, a call polls at most **sixteen** more ended heads. Every call therefore does at most 18 queue operations. There is no full-map sweep, no `removeIf` over the map, and no background thread.
- **`LinkAttemptGate`**, the only way code outside `core.link` opens a token:
  - The constructor is `LinkAttemptGate(LinkCodec codec, LinkAttemptLimiter limiter)`.
  - `GateDecision open(String token, LinkBinding binding, Set<LinkRelation> accepted)` runs, in this order:
    1. `AttemptSubject.of(binding)` is empty → `Invalid(MALFORMED)`, uncounted, and the codec is not called. A null token or binding is `Invalid(MALFORMED)` too.
    2. `limiter.locked(subject)` is `true` or throws → `Exhausted`, and the codec is not called. This is where a locked principal's valid link is refused.
    3. `codec.open(token, binding, accepted)`.
    4. `Opened` → `Proceed(opened)`. `Refused` with `counted() == false` → `Invalid(reason)`, with nothing recorded.
    5. `Refused` with `counted() == true` → `limiter.recordFailure(subject)`: `WITHIN_LIMIT` → `Invalid(reason)`; `OVER_LIMIT` or a throw → `Exhausted`.
  - `GateDecision` is sealed: `Proceed(LinkOpenResult.Opened opened)`, `Invalid(LinkReason auditReason)`, `Exhausted()`. The caller answers `LINK_INVALID` for `Invalid` and records `REFUSED:LINK_<reason>`. It answers `LINK_ATTEMPTS_EXHAUSTED` for `Exhausted`. Refusals the caller decides after `Proceed` are uncounted and do not touch the limiter: target permission, relation declared, payload checks, catalog tag.
  - The gate never reads `LinkReason.counted()`, never throws, and no limiter or codec exception text reaches the caller.
- **Removed:** `LinkKeys.attemptCounterKey`. The `fingerprint` label keeps its other uses.

## Acceptance
- [ ] Tests are written first and fail on the base commit (they do not compile against 242's SPI); the Outcome names them. Each failing-input test below names the mutant it kills.
- [ ] **One call, no bypass.** In `LinkAttemptGateTest`:
  - After 20 counted failures for a subject, `open` with a **valid** token returns `Exhausted`, and the codec is not called (a counting codec spy). So does a genuine token that would be refused uncounted (`EXPIRED`).
  - Another principal in the same scope, and the same principal in another scope, get `Proceed`.
  - Mutants killed: skip the pre-check; pre-check after `open` but return `Proceed` anyway.
- [ ] **Race, failures (OD-269-1).** With `limit = 20`, 64 threads are released together by a latch. Each calls `open` with a distinct forged token for one subject.
  - Each thread's result counts as follows: `Invalid(AUTH_FAILED)` if its failure was recorded `WITHIN_LIMIT`; `Exhausted` if it was refused at the pre-check (nothing recorded) or recorded `OVER_LIMIT`.
  - The test asserts exactly 20 `Invalid`, `Invalid + Exhausted == 64`, and a stored count between 20 and 64. It is repeated 50 times.
  - A deterministic interleaving: two attempts are latched after the pre-check at count 19, and both fail. Exactly one is `Invalid` and one `Exhausted`.
  - Mutants: `recordFailure` deciding from the count before the increment; the gate deciding from `locked` instead of the `Admission`.
- [ ] **Race, the stated bound.** A valid token whose pre-check passes at count 19, latched before `codec.open`, while one forged attempt takes the count to 20, gets `Proceed`. The test is named for the "limit plus in-flight" bound and documents it; it does not assert a stronger property.
- [ ] **Lock semantics kept.** The window ends at the stored start plus the window, and increments do not extend it. The `Long.MAX_VALUE`-second window still constructs and locks. Sixteen threads recording concurrently lose no count. Mutants: extend the window on increment; key by scope only; count every reason.
- [ ] **Gate on `counted()`.** A `Refused(MALFORMED, false)` (post-authentication) and a `Refused(KEY_UNAVAILABLE, false)`, from a stub codec, give `Invalid` and record nothing over ten calls each. A `Refused(AUTH_FAILED, true)` records once. Mutant: gating on `reason().counted()`.
- [ ] **Binding bounds.**
  - A binding whose scope id or principal id is 65,536 UTF-8 bytes gives `Invalid(MALFORMED)`, with no limiter call and no codec call (spies). At 65,535 bytes the call proceeds normally.
  - An empty id is `Invalid(MALFORMED)`. Nothing throws.
  - `new AttemptSubject` with either bad input throws `IllegalArgumentException`, and the message contains no part of the id.
- [ ] **Fail closed.** A limiter whose `locked` throws gives `Exhausted`, and the codec is not called. One whose `recordFailure` throws gives `Exhausted`. A null token or binding gives `Invalid(MALFORMED)`. No exception escapes the gate, and no limiter or codec message text appears in a result or exception (sentinel).
- [ ] **Ceiling (OD-269-3).**
  - With `maxEntries = 4`, four subjects have one failure each. A fifth subject's counted failure returns `OVER_LIMIT`, and `size()` stays 4. A valid token for the fifth subject gets `Proceed`, and so does one for a sixth subject.
  - A subject locked before the map filled stays locked while new subjects keep arriving.
  - Once the first entries' window has ended, the fifth subject's next failure is `WITHIN_LIMIT`, with no explicit sweep call.
  - A test named for the OD-269-3 consequence shows the fifth subject making 100 counted failures and still getting `Proceed` for a valid token.
  - Mutants: evict the oldest entry (a lock lifts); refuse every subject at the ceiling (a valid token is refused); store the fifth entry anyway (`size()` is 5).
- [ ] **Ceiling, concurrent.** With `maxEntries = 100`, 64 threads record failures for 1,000 distinct subjects, released by a latch. Afterwards `size()` is exactly 100, the entry count equals `size()`, and exactly 100 distinct subjects got a `WITHIN_LIMIT`. Mutant: check the size with `entries.size()` outside `compute` (the test sees more than 100).
- [ ] **Sweep, bounded and complete.**
  - A package-private counter records the queue operations of each call. It is at most 2 on every call below the ceiling, and at most 18 at the ceiling, over 10,000 calls against 10,000 live entries.
  - With N = 1,000 entries whose windows have all ended and no live ones, after k calls to `locked` on an absent subject, `size()` equals `max(0, N − 2k)`.
  - A window re-opened in place (fail, wait past the window, fail again) leaves exactly one entry. That entry is removed by the sweep once its second window ends; the stale first marker never removes the live entry.
  - The class contains no `removeIf` over the map and has no public `sweep()`.
  - Mutants: restore the every-256 full sweep (operation count over 2); queue only new keys (the re-opened entry is never removed); remove without comparing the window start (the live re-opened entry is removed).
- [ ] **Counter identity (OD-269-2).**
  - `AttemptSubjectTest`: `canonical()` of `("ab","c")` differs from that of `("a","bc")`. `canonical()` begins with the length-prefixed label `dataprism/link/v1/attempt`.
  - `vectors.properties` pins `attempt.subject.canonical` for a fixed subject, so 264 can reproduce it, and the `counter.key` vector is gone.
  - `LinkKeys` has no public method that takes a principal id (reflection).
- [ ] **No key dependency (ArchUnit, replaces by-construction tests).** `LinkArchitectureTest` fails when `LinkAttemptLimiter`, `InMemoryLinkAttemptLimiter`, `AttemptSubject` or `LinkAttemptGate` depends directly on `LinkKeys` or `SecretKeyProvider`. A nested fixture proves the rule can fail.
- [ ] **No bypass (ArchUnit).** `LinkArchitectureTest` fails when:
  - a main class outside `core.link` calls `LinkCodec.open`;
  - a main class outside `core.link` calls `LinkAttemptLimiter.locked` or `recordFailure`, on the interface or on any implementation. The one exception is a class in `io.github.aindriub.dataprism.hazelcast..` that itself implements `LinkAttemptLimiter`, for 264's local floor.
  
  Nested fixtures prove each rule can fail: one calls `open`, one calls `recordFailure` directly, and one implements the SPI outside the Hazelcast package. A fixture in the Hazelcast package proves the exception.
- [ ] **No leak.** The in-memory map's keys are 32-character hex strings that contain neither id, in plain or hex form. `AttemptSubject.toString()`, `GateDecision.toString()`, and the limiter's and the gate's `toString()`, contain neither id, nor does any exception message (sentinel ids).
- [ ] `clusterWide()` is `false` for `InMemoryLinkAttemptLimiter` and for an implementation that does not override it.
- [ ] `sealed-links.md` carries the amendment below, word for word in substance.
- [ ] A whole-component bug hunt over the limiter, the gate and the sweep (races, the ceiling, the clock moving backwards) runs before merge; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-architecture -am verify` passes. Report the counts.

## Design amendment (sealed-links.md, recorded by this task)
- **Section 3, "Fingerprinting" row:** drop "and for the attempt-counter key (section 4)". The counter key is no longer derived from the link key.
- **Section 4, "Attempt limiter", replace the counter-key bullet with:** "It counts per principal within a scope. The limiter receives the subject (scope id, principal id, each 1 to 65,535 UTF-8 bytes; outside that bound the call is `MALFORMED`, uncounted). Its input is the length-prefixed label `dataprism/link/v1/attempt`, then the two ids. It keys that input under a key that does not rotate with the link key: a per-instance `SecureRandom` key in memory, and the cluster name key (`KeyedNames`) in the shared limiter (task 264). Every member therefore derives the same key, and a link-key rotation neither lifts a lock nor splits a count. Changing the name key is a planned full restart that resets every count at once (cluster-security 4.6). No map holds a scope id or principal id in the clear (boundary 6) (task 269, 2026-10-22)."
- **Section 4, add:** "Code outside `core.link` opens a token only through `LinkAttemptGate.open`. That one call checks the binding bounds, checks `locked` (a locked principal is refused before the token is opened, valid links included), opens the token, and on a counted refusal makes one atomic `recordFailure`. That call returns whether the new count is within the limit, and an attempt over the limit is answered `LINK_ATTEMPTS_EXHAUSTED`. Guarantee: at most `invalid-attempt-limit` counted failures per window are answered `LINK_INVALID`. The number of guesses that can reveal a success is at most that limit plus the attempts already past the `locked` check when the lock engaged. Each instance holds at most a fixed number of entries (100,000 by default), and the count is checked atomically. At that ceiling, a counted failure for a subject not yet held is answered `LINK_ATTEMPTS_EXHAUSTED` and is not stored. No live entry is evicted, so a flood can neither lift a lock nor lock out a principal with a valid link. The cost, accepted under OD-269-3: while the ceiling holds, a subject that is not stored is never locked, so it can keep guessing against the 128-bit tag. Expired entries, including windows re-opened in place, are removed through an expiry queue, a bounded number per call and only when the stored window start matches; there is never a full sweep on the request thread. Any limiter failure is treated as locked (task 269, 2026-10-22; revised 2026-10-23)."
- **Section 6, boundary 3:** replace "with a time-to-live" with "whose time-to-live only bounds memory; the window is decided by the stored start and the clock".
- **Section 7, obligation 22, append:** "Concurrency: N parallel forged attempts against a fresh subject yield exactly `invalid-attempt-limit` `LINK_INVALID` and the rest `LINK_ATTEMPTS_EXHAUSTED`. Ceiling: a full map answers new subjects over the limit, keeps existing locks, lets valid links proceed, and does not lock a subject it cannot store (OD-269-3). Gate: a locked principal's valid link is refused before the token is opened. Architecture: nothing outside `core.link` calls `LinkCodec.open` or the limiter, apart from a Hazelcast implementation of the limiter."

## Out of scope
- The Hazelcast limiter, its map, its entry processor and its ceiling (264, which implements this contract).
- Wiring the gate into `follow` (252), `list_sources` page links (250) and Spring (255), and issuing links (251).
- Any Spring property or bean, including a capacity property (OD-269-3 (a): none in 0.8).
- A metric for saturation or attempts: `Metric.java` belongs to 241 in this wave. Expose `size()` package-private only.
- Changes to `LinkCodec`, `LinkBinding`, `LinkReason` and the rest of `LinkKeys` (`heldKeyIds`, `forScope`, the pseudonymisation digest).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Use neutral, invented identifiers in tests, and generated test key bytes.
- No sleeps. Windows use an injected clock, and ordering uses latches.
