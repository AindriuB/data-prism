# 298 — Stop a membership look overlapping the start look in MemberCards

**Repo:** `.`
**Base branch:** `origin/main`. One PR, targeting `main`.
**Depends on:** none
**Owns** (under `data-prism-hazelcast/src/`):
- `main/java/io/github/aindriub/dataprism/hazelcast/MemberCards.java` *(`lookSoon` and one package-private test seam only)*
- `test/java/io/github/aindriub/dataprism/hazelcast/MemberCardsStartLookTest.java` *(new)*

## Goal
A membership event during `start()` can run a look concurrently with the start thread's own look, and `judge` has no ordering check, so an older look can overwrite a newer not-serving status with serving until the next look (one tick, 1 s by default). Severity is Low, but it is a fail-closed gap. `lookSoon` must not look while the wrapper is not `running`; a change during start waits for the next tick.

## Context
- `MemberCards.java:907` registers the listener, `:922` runs the start look, `:923` sets `running`. `lookSoon` is at `:1018-1028`; `judge` writes `status` at `:1315-1317`.
- Put the check inside the executor lambda, as `running && !closed`. The executor is a single FIFO thread and `running` is set only after the start look returns, so a queued event that runs later cannot overlap it, and it still looks.
- **Blast radius, checked at filing: nothing relies on `lookSoon` running during start.**
  - `lookFault` is set in three tests (`MemberCardTest:592`, `:701`, `:1272`), each on a wrapper that has already started.
  - The tests that fire captured listeners (`HazelcastOversightTest:1594`, `:1611`, `:1692`, `KeylessMemberIsolationTest:259`) do so after construction.
  - The two concurrent starts (`MemberCardTest:830`, `:1083`) fire no membership event.
  - Outside `data-prism-hazelcast`, only the architecture tests name the class, and none names `lookSoon`.
  - Test environments tick hourly (`MemberCardTest:149`, `HazelcastOversightTest:661`), so a skipped event gets no automatic tick there. No existing test joins a member during a start; a new one must tick by hand.
- **Installing `lookFault` before start:** `PrivacyCluster` constructs and starts its cards in one call (`:240`, `:978`), and 287 owns that file. So the test builds its own `MemberCards` through the package-private constructor over a keyed `PrivacyCluster.using` wrapper on a listener-capturing instance (`KeylessMemberIsolationTest:241-252` shows the capture). This route was read, not run. If it does not work, stop and report; do not edit `PrivacyCluster.java`.
- **Draining the executor:** add one seam beside `executorStoppedForTest` (`:725`) that lets a test wait for everything queued so far, so "no second look" is an observed fact, not a timeout.
- If 287 merges first and refuses this test's wrapper, add its trusted-network declaration to the new test.

## Acceptance
- [ ] The `lookSoon` lambda looks only when `running && !closed`. The only other production change is the test seam.
- [ ] `MemberCardsStartLookTest`, start held: `start()` runs on its own thread and is held by a latch in `lookFault`. The test fires `memberAdded` through the captured listener and drains the executor, then asserts `lookFault` was entered exactly once, by the start thread. It then releases the latch and `start()` returns. Only the first entry blocks, so the unfixed code fails the assertion rather than hanging.
- [ ] With the `running` check removed, that test fails; the PR says this was checked.
- [ ] After `start()` returns: a fired `memberAdded` runs a look on the executor (`lookFault` count rises), and a hand-called `tick()` runs a look.
- [ ] `@Timeout` on each test and no sleeps (`docs/conventions.md`).
- [ ] `mvn -pl data-prism-hazelcast -am test -Dtest='MemberCardTest,MemberCardsStartLookTest,KeylessMemberIsolationTest' -Dsurefire.failIfNoSpecifiedTests=false` passes.

## Out of scope
- An ordering token on `judge`, or a catch-up look when `running` turns true. The exposure stays within one tick.
- `PrivacyCluster.java`, `ClusterMembership.java`, `ClusterSecuritySettings.java`, `PrivacyClusterRefusal.java` and every existing hazelcast test (287 owns or edits them in wave 8).
- Listener registration order in `start()`, and the flakes in `docs/plan/known-flakes.md`.

## Outcome (2026-11-06, 0.8; PR #213)
`MemberCards.lookSoon` now looks only when `running && !closed`, so a membership event during `start()` cannot run a look beside the start thread's look and let an older look overwrite a newer not-serving status. A package-private `drainExecutorForTest()` sits beside `executorStoppedForTest`. `PrivacyCluster.java` is untouched.

`MemberCardsStartLookTest` (two tests, `@Timeout`, no sleeps; the member is loopback-only, built through the package-private constructor over a listener-capturing wrapper). With the start held in `lookFault` by a latch (only the first entry blocks), the event plus an executor drain leaves exactly one entry, by the start thread. Checked red against `MemberCards.java` as on main (seam only, no `running` check): it failed with "lookFault entered once, expected 1" and fails the assertion rather than hanging. The after-start test (a fired `memberAdded` runs a look, and a hand-called `tick()` runs a look) passes on both. `MemberCardsStartLookTest` and `MemberCardTest` (98/98) passed, one class at a time; `KeylessMemberIsolationTest` was left to CI. Other agents' surefire JVMs were running during the runs. Two test style items are a PLAN follow-up.
