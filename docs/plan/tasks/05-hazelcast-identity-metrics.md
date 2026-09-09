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
- [ ] `mvn -B verify` from the repo root passes; all 212 tests at `7db0491` still pass.

## Out of scope
- Micrometer, or any metrics backend. This task emits through the SPI only; task 07 binds it.
- Any change to what the cache decides, to TTL, to the budget's fail-closed behaviour, or to
  `endScope()`.
- Exposing a re-identification operation to any caller. `REIDENTIFICATION` counts an existing internal
  path; it does not create one.
- Hazelcast's own JMX or diagnostics configuration.
