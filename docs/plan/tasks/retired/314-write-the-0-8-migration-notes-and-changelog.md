# 314 — Write the 0.8 migration notes and changelog

Split from 213 on 2026-11-12 (owner agreement of that day: 213 becomes four parallel docs tasks, 310 to 313, and this integrating task). 213 is retired at `tasks/retired/213-document-key-rotation-and-1-0-controls.md`, which maps each of its criteria to its new home.

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** `origin/main` after 310, 311, 312 and 313 have merged
**Depends on:** 310, 311, 312, 313
**Wave:** 14b (alone, after 14a)
**Owns:**
- docs/migration-0.8.md (extend; do not recreate)
- CHANGELOG.md *(`[Unreleased]` only)*
- mkdocs.yml *(any change beyond 310's `key-rotation.md` entries; none is expected)*

## Goal
One migration page and one `[Unreleased]` changelog section tell an operator and an integrator everything that changes from 0.7 to 0.8 because of the 1.0 controls: what breaks, what is added, and what to do. This task alone writes both, from the facts 310 to 313 hand over and from the decisions below.

## Rules for every claim
- Check every claim and example against current code on `origin/main` and the merged Outcomes (HISTORY.md entries via `HISTORY-INDEX.md`, the retired briefs), never against the October 213 brief. 228, 255, 260, 261, 263 (Part A), 304, 307, 309 and 309-F1 merged after it was written. A fact handed over by 310 to 313 is checked too, not copied. Nothing proposed or deferred (224, 226, 229, 230, 231, 264, 295) is presented as shipped.
- Write "supports", never "compliant" or "compliance". Make no claim that a deployment meets a legal duty.
- Never call a cluster "secured" or "protected" by the trusted-network guardrail.
- Use `[Unreleased]`; no version bump, tag or publication.

## Hand-off format
310 to 313 each end their PR description's Outcome with this list; `/record` keeps it in the retired brief. Read it there, or in the merged PR description if the record has not landed.
```
## Facts for 314
- [Breaking|Added|Changed|Removed] <one sentence> — source: <path:line or task Outcome> — before/after: <for a constructor, property or configuration, else "n/a">
```
"none" is a valid list. A fact this task cannot verify in code is reported in the Outcome, not published.

## Context
- `docs/migration-0.8.md` and its two `mkdocs.yml` entries exist (222's review round; extended by 261). Keep the Breaking note on `HmacKeyReferenceResolver.resolveProviderReference` (:24) and the existing sections; the full-restart section is at :12, limits at :106.
- Owner decisions that belong on the page (PLAN.md:107): the non-rolling cutover and `UNSEALED_CLUSTER_STATE`; the 0.7 fence (`all` = TRUE in `dataprism.oversight`) and its residuals; `scope-read-budget` still 100 but counting source reads; new defaults `request-timeout` `PT10S` and `scope-total-read-budget` 50,000; `MISSING_SHARED_LINK_LIMITER` on joining members (264 deferred); `trusted-network` and a private `member.interface` (284), and the plain-Java declaration (287). `SKIPPED_OVER_LIMIT` is Breaking only if its removal has been built; check the code.
- PLAN.md:258: upgrading from 0.7 or any pre-223 build needs a full restart that wipes cluster state (raw NUL-keyed entries are neither detected nor migrated); a leftover pause refuses with `LEGACY_OVERSIGHT_STATE`. Confirm the page says so; add it if not.
- PLAN.md:98 and :255: the changelog says startup refuses with `MISSING_CASE_AUTHORIZER` or `MISSING_CLIENT_ALLOWLIST` unless configured; it lists the codes from 210's Outcome, not the `AMBIGUOUS_*` codes PLAN.md:256 names (they no longer exist).
- PLAN.md:256: `PrivacyCluster.close()` now stops only an instance it started.
- PLAN.md:234 (wave 2): the `PrivacyCluster.using()` breaking change and what integrators must supply (`requiredSerializationFilter()`, non-empty `trustedInterfaces`, phone-home off, no memcache, REST `HEALTH_CHECK` only).
- `tasks/retired/228-signed-leased-pause-document.md:732`: `ClusterSecurityStatus` gains two record components (pause freshness and age), so a deconstruction pattern over the old four no longer compiles while the 4-argument constructor is kept; `ClusterSecurityStatus.Reason` gains `PAUSE_NOT_FRESH`, which breaks an exhaustive `switch` and is silently skipped by a classic `switch` without a default. 227 is unreleased, so this is not a break of a released API; say so.
- From 222 (round 5): the `ClusterMembership` record's interface component changed from `Optional<String>` to `String` (null when unset). From 227: `PrivacyCluster.servingInstance()`.

## Acceptance
- [ ] AC1 (213 AC4) `docs/migration-0.8.md` states the OD-215-1 (a) cutover: non-rolling, every 0.7 member stopped before any 0.8 member starts, a 0.8 member that finds an entry in a pre-0.8 map refuses with `UNSEALED_CLUSTER_STATE`, and the compatibility mode is withdrawn. It says the cluster state is cached data that is fetched again; pending approvals are lost and must be requested again; the re-identification index starts empty. It records that `dataprism.limits.scope-read-budget` (still 100) now counts source reads, so a subject gets about 12 eight-source calls rather than 100.
- [ ] AC2 (213 AC11) Migration and changelog cover all changed public constructors, map formats, startup refusals, properties, audit behaviour and recogniser functionality, including every verified fact in 310 to 313's `Facts for 314` lists and the Context items above.
- [ ] AC3 (213 AC13, second half) `docs/migration-0.8.md` has a `member.interface` entry: required on `tcp-ip` and `kubernetes`, bounded, valid.
- [ ] AC4 (213 AC14) `docs/migration-0.8.md` and `CHANGELOG.md` (`[Unreleased]`, Breaking) state that `ClusterMembership`'s interface component changed from `Optional<String>` to `String` (null when unset), with the before and after of a plain-Java constructor call.
- [ ] AC5 (213 AC17) `docs/migration-0.8.md` and `CHANGELOG.md` (`[Unreleased]`, Added) describe `PrivacyCluster.servingInstance()` (227): Data Prism's stores use it, it refuses with `CLUSTER_OPERATION_REFUSED` while the member is not serving, `instance()` is unchanged and ungated, and an embedder's own cluster-backed store should move to `servingInstance()` to be refused with them.
- [ ] AC6 (213 AC18, second half) Where `docs/migration-0.8.md` documents `trusted-network`, it is the operator's isolation assertion, in 284's words: only Data Prism members can reach the member port; it is not inferred from a private address.
- [ ] AC7 (213 AC20) `docs/migration-0.8.md` and `CHANGELOG.md` (`[Unreleased]`, Breaking) state that a `tcp-ip` or `kubernetes` member now needs `dataprism.hazelcast.trusted-network: true` and a private interface, with a before-and-after configuration, and the plain-Java declaration (287's Outcome) with a before-and-after call.
- [ ] AC8 Each Context item above is on the page or in `[Unreleased]`, or the Outcome says why not (for example, not built).
- [ ] AC9 `CHANGELOG.md` changes only inside `[Unreleased]`; no version heading, tag or link is added.
- [ ] AC10 (213 AC22) `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`. Build success alone is insufficient.

## Checks
- `docs-site` command — AC10
  run: `mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/migration-0\.8\.md|CHANGELOG\.md|mkdocs\.yml')"`
- `named-items` command — AC1, AC3, AC4, AC5, AC7
  run: `for s in UNSEALED_CLUSTER_STATE member.interface ClusterMembership servingInstance trusted-network scope-read-budget; do grep -q -F "$s" docs/migration-0.8.md || exit 1; done; for s in ClusterMembership servingInstance trusted-network; do grep -q -F "$s" CHANGELOG.md || exit 1; done`
- `no-compliance-wording` command — rules
  run: `test -z "$(git diff -U0 origin/main...HEAD -- docs CHANGELOG.md | grep -E '^\+[^+]' | grep -i -E 'complian')"`
- `unreleased-only` read — AC9
  how: every changed line of `CHANGELOG.md` sits between `## [Unreleased]` and the next `## [` heading.
- `coverage` read — AC2, AC6, AC8
  how: reviewer walks the four `Facts for 314` lists and the Context items and finds each on the page, in `[Unreleased]`, or explained in the Outcome; before/after examples match the merged signatures.

## Out of scope
- Every page owned by 310 to 313; `docs/data-protection.md` (214), code, `docs/configuration.md`.
- 239-11 to 239-13 and 239-21 (PLAN.md, 261's review leftovers in these files): not earmarked for 213; untasked, except where AC2 already requires the same content.
- The 0.8.0 release-cut obligations in PLAN.md (advisories, published-image reruns, 263 Part B); version cuts and publication.

## Review round 1 — findings

Reviewed HEAD: 3c9348c288835baad874269473c8f1eb4b239e9c

- [P2] docs/migration-0.8.md:403-406 and CHANGELOG.md:103-104 — Both files leave out that the public constructor `SensitivePatternValidator(SensitiveDataScanner)` was removed. They file the recogniser change under Added only, and say just the no-arg constructor "still compiles".
  route: implementer — AC2 requires coverage of "all changed public constructors". Documenting this break changes no specified behaviour. 313's `Facts for 314` list (PR #257) missed it too, and the task says handed-over facts are to be checked, not copied.
  input: integrator code that compiled against v0.7.1, `new SensitivePatternValidator(myScanner)`. The constructor exists at v0.7.1 `data-prism-validation/.../SensitivePatternValidator.java:43`. Built against 0.8 it no longer compiles: at 3c9348c2 `SensitivePatternValidator.java:51,59` has only `()` and `(SensitiveDataScanner, List<FreeTextRecogniser>)`. Task 208 (commit 6e1f9217, after v0.7.1) removed it. The migration page has no Breaking entry and no before/after for it. Suggested before/after: `new SensitivePatternValidator(scanner)` becomes `new SensitivePatternValidator(scanner, List.of())`.

## Outcome

Landed on `main` as PR #262 (`703528ae`), verified PASS and APPROVE at `70c4ac8e12d607d5fc4e07a82d13373ea0379973` (2026-11-12). `docs/migration-0.8.md` now carries the 0.7.1 to 0.8 migration (breaking changes with before and after, additions, startup refusals) and `CHANGELOG.md` `[Unreleased]` lists the 0.8.0 changes. Review round 1 found one P2, the removed `SensitivePatternValidator(SensitiveDataScanner)` constructor, which the round fixed. The tester's scope check flagged this task file only because the orchestrator appended the round-1 findings to it; the owned files were clean, and the owner treats such edits as review-round scope (as with 253). Follow-ups (a) to (e) are in `PLAN.md` under "Wave 14b follow-ups".
