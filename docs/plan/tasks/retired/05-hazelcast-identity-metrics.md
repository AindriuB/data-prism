# 05 — Report identity-cache and collision metrics from the cluster

**Repo:** `.`
**Depends on:** 01
**Owns:**
- data-prism-hazelcast/**

## Goal
`CachingSyntheticValueSource` already counts hits, misses, failures and conflicts
in `AtomicLong`s that nothing outside the class can read. Publish them through the
`PrivacyMetrics` SPI so a deployment can see cache behaviour and, more importantly,
see a collision — the one counter whose non-zero value means an answer may have
changed, which is the property the whole cache design exists to protect.

## Context
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSource.java:47-60 —
  the existing `hits`, `misses`, `failures`, `conflicts` counters
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSource.java:36-45 —
  why a stored value that differs is a key or vocabulary change rather than a race, and is warned about
  and overwritten
- docs/design-review.md:290-292 — §E asks for `dataprism.identity.collision` by name
- docs/architecture.md — "the read budget fails closed; the identity cache fails open": a cache failure
  is counted and degraded, never propagated
- data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/ScopeIdentityIndex.java —
  the reverse index; `dataprism.reidentification` counts resolutions through it

## Acceptance
- [ ] `CachingSyntheticValueSource` takes a `PrivacyMetrics`, defaulting to `PrivacyMetrics.none()` on
      the existing two-argument constructor so no current call site changes meaning.
- [ ] `IDENTITY_CACHE_HIT` and `IDENTITY_CACHE_MISS` are incremented on their respective paths, and
      `IDENTITY_COLLISION` on the conflicting-stored-value path that currently only warns.
- [ ] A cluster failure increments no cache-hit or cache-miss counter incorrectly and still returns the
      generator's value; a test with the cluster made unavailable asserts the returned value equals the
      generator's and that the metrics recorded describe a miss, not a hit.
- [ ] `ScopeIdentityIndex` increments `REIDENTIFICATION` once per successful reverse resolution and
      never on a lookup that finds nothing. If the reverse index is disabled, nothing is counted.
- [ ] A test asserts that no metric call anywhere in this module passes a scope id, subject id or
      pseudonym as the `sourceName` argument — the only permitted argument is a configured name.
- [ ] Existing behaviour is unchanged: `CachingSyntheticValueSourceTest` and
      `HazelcastScopeBudgetTest` pass without edits to their assertions.
- [ ] `mvn -B verify` from the repo root passes; all 224 tests at `da0bbdf` still pass.

## Out of scope
- Micrometer, or any metrics backend. This task emits through the SPI only; task 07 binds it.
- Any change to what the cache decides, to TTL, to the budget's fail-closed behaviour, or to
  `endScope()`.
- Exposing a re-identification operation to any caller. `REIDENTIFICATION` counts an existing internal
  path; it does not create one.
- Hazelcast's own JMX or diagnostics configuration.

## Attempt 1 — failed

Build passed (230 tests, no flakiness across two runs) and every other acceptance
criterion was met: scope was clean, and metric labels carry only `namespace.name()`
at all five emit sites. Two defects in
`data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/CachingSyntheticValueSource.java:96-101`:

1. **A published metric that disagrees with the counter it publishes.** The
   `catch (RuntimeException)` handler emits a miss whenever `generated == null`.
   But `generated` is also null when the *generator* threw rather than the
   cluster — the real case being `HmacSyntheticGenerator` refusing a scope pinned
   to a different vocabulary. On that path the miss at line 86 already ran, so one
   lookup emits `dataprism.identity.cache.miss` twice while `misses()` reads 1.
   Decide the emit from what actually happened, not from whether `generated` is
   null.

2. **A comment asserting a state nobody established.** "The failure happened
   before either counter above ran" is false on the generator-throws path and on a
   metrics-throw at line 81. `docs/conventions.md` ("Code comments") forbids this;
   the rule exists because a previous slice shipped three such comments and all
   three were wrong.

Also fix while here, because task 07 makes it live: the emit inside the `catch`
handler is the one metrics call in the class with nothing above it to absorb a
throw. A `PrivacyMetrics` whose `increment` throws — Micrometer throws on a
conflicting meter registration, and task 07 binds Micrometer — would escape
`syntheticValue` and break the fail-open guarantee stated in this class's own
javadoc. Metrics must never be able to fail a lookup.

Baseline correction: `main` at `da0bbdf` carries **224** tests (216 `@Test` +
8 `@ArchTest`), not the 212 written in the acceptance list above.

## Attempt 2 — failed

Both attempt-1 defects are fixed and stay fixed: the miss is now decided by a
`lookupOutcomeRecorded` flag set before each emit rather than by `generated ==
null`, and the false ordering comment is gone. Both new regression tests were
confirmed non-vacuous — reverting the guard fails one, removing the inner
try/catch fails the other. 232 tests pass. Scope clean.

Rejected because the guard the previous attempt asked for "on every path" was
applied to one emit site of three. Metrics can still fail — or silently change —
a lookup:

1. **`CachingSyntheticValueSource.java:83`, the hit path.** A `PrivacyMetrics`
   whose `increment` throws sends a *cache hit* into the catch block, which
   re-derives. If the generator then refuses the scope
   (`HmacSyntheticGenerator.java:67`) the `IllegalStateException` escapes
   `syntheticValue`. And when the generator does not throw, the caller receives
   the fresh derivation while the map still holds the old value — so enabling
   metrics changes the returned answer. The class javadoc at :31-36 forbids
   exactly this.

2. **`CachingSyntheticValueSource.java:125`, the collision path.** If the
   collision emit throws, `store()` exits before the corrective `identities.set`
   at :129 and before the reidentification write at :132. The disagreeing cached
   value survives, and every later lookup in that scope returns it. The collision
   counter exists because a non-zero value means an answer may have changed;
   this makes the counter's own failure cause that outcome.

No metrics call anywhere in this class may be able to fail, skip or alter a
lookup. Fix it once, structurally, rather than adding a third hand-placed
try/catch — a guard that has to be remembered at each new call site is a guard
that will be missed again.

Also address, both smaller:

- `CachingSyntheticValueSource.java:99` — "A miss was already emitted above" is
  not what the guard tests (on the hit path a hit was emitted), and "(the cluster
  call itself threw)" excludes `ScopeKeys.identity`. State the weaker claim the
  flag actually establishes. Same conventions rule as attempt 1's defect 2.
- `IdentityMetricsTest.java:253` — the label test never reaches the collision or
  fallback emits, so an identifier introduced at :105 or :125 would pass it.
  Cover every emit site the assertion claims to cover.
