# 313 — Document case authorisation, recognisers and FREE_TEXT

Split from 213 on 2026-11-12 (owner agreement of that day: 213 becomes four parallel docs tasks, 310 to 313, and one integrating task, 314). 213 is retired at `tasks/retired/213-document-key-rotation-and-1-0-controls.md`, which maps each of its criteria to its new home.

**Repo:** `.`
**Release:** 0.8.0
**Executor:** `scribe` (docs only; no code)
**Base branch:** `origin/main`
**Depends on:** none. Prerequisites, all merged: 203, 208, 209, 210, 247.
**Wave:** 14a (parallel with 310, 311, 312; 314 follows)
**Owns:**
- docs/extending.md
- docs/tools.md
- docs/faq.md

## Goal
An integrator can load a `CaseAuthorizer` and supply a recogniser from the docs alone, with examples that compile against the merged APIs. A client author can read every case-authorisation, allowlist and FREE_TEXT outcome, and what the scanner does and does not cover.

## Rules for every claim
- Check every claim and example against current code on `origin/main` and the merged Outcomes (HISTORY.md entries via `HISTORY-INDEX.md`, the retired briefs), never against the October 213 brief. 228, 255, 260, 261, 263 (Part A), 304, 307, 309 and 309-F1 merged after it was written. Nothing proposed or deferred (224, 226, 229, 230, 231, 264, 295) is presented as shipped.
- Write "supports", never "compliant" or "compliance". Make no claim that a deployment meets a legal duty.
- Never call a cluster "secured" or "protected" by the trusted-network guardrail.
- Link only to pages and anchors on `origin/main` or in this task's Owns. Do not link to anything 310, 311 or 312 adds.

## Context
- `docs/plan/tasks/retired/203-*.md` and `210-wire-case-authorisation-and-client-allowlist.md` (Outcomes): the SPI and its Spring wiring. Since 210 (PLAN.md:255): two beans of either type fail with Spring's `NoUniqueBeanDefinitionException` unless one is `@Primary`; with none and no setting, startup refuses `MISSING_CASE_AUTHORIZER` or `MISSING_CLIENT_ALLOWLIST` (PLAN.md:98). The `AMBIGUOUS_*` and `NON_SINGLETON_*` codes named in PLAN.md:256 do not exist; use the codes in 210's Outcome and `data-prism-architecture/src/test/resources/freeze/codes.txt`.
- `docs/plan/tasks/retired/208-free-text-classification-and-recogniser-spi.md` and `209-*.md` (Outcomes): FREE_TEXT, recogniser ordering, fail-closed failures. `SensitivePatternValidator.java:40-43, :128-130`: `FREE_TEXT_RECOGNISED` and `FREE_TEXT_RECOGNISER_FAILED` are violation codes on no user page (PLAN.md:268).
- PLAN.md:256: `docs/tools.md` shows constructors that predate these tasks (the harness snippet, now `:67-75`, was `:69-70` at 213's filing); implementers must keep case data out of the messages of exceptions they throw. The existing case refusal rows are `docs/tools.md:743-768`.
- `docs/extending.md` has no case-authoriser or recogniser section today (checked 2026-11-12); "Register the extension" (:885) and "Load the extension" (:938) are the pattern for how a standalone server loads one.
- `docs/faq.md:35-65` ("Does it detect PII in free text?") omits the scanner's depth cap (PLAN.md, task-77 follow-ups, `faq.md:41`); read the cap from the code, do not assume 16.
- PLAN.md:142: source names over 128 characters do not fit the 0.8 `targetSources` schema (`CatalogQueries.java:86`, `ToolCalls.java:49`: 128 in 0.8, 64 from 0.9). Document the limit in `docs/tools.md`'s `targetSources` section (:584). The startup warning is code and not this task's.

## Acceptance
- [ ] AC1 (213 AC9) The pages document case-authorisation refusals, client allowlisting and its lack of egress control, FREE_TEXT actions, recogniser ordering and fail-closed failures, and the scanner's depth cap. They say recognisers must bound their own runtime, and that the existing Spring pattern scan first runs in 0.7 (209) while recognisers arrive in 0.8 (208).
- [ ] AC2 (213 AC10) `docs/extending.md` shows how a standalone server loads a `CaseAuthorizer` extension and how to supply a recogniser. Constructor examples compile against the merged APIs.
- [ ] AC3 `docs/extending.md` says startup refuses with `MISSING_CASE_AUTHORIZER` or `MISSING_CLIENT_ALLOWLIST` unless configured, and what two beans of one type do; it names no `AMBIGUOUS_*` or `NON_SINGLETON_*` code. It tells implementers to keep case data out of the messages of exceptions they throw.
- [ ] AC4 `docs/tools.md` lists `FREE_TEXT_RECOGNISED` and `FREE_TEXT_RECOGNISER_FAILED` with what the client sees for each, and its constructor examples (:67-75 at filing) match the merged constructors.
- [ ] AC5 `docs/tools.md`'s `targetSources` section states the 128-character source-label limit for 0.8.
- [ ] AC6 `docs/faq.md`'s free-text answer states the scanner's depth cap and that it fails closed beyond it.
- [ ] AC7 The PR description's Outcome has a `Facts for 314` list in the format of `tasks/314-*.md` ("Hand-off format"): every changed public constructor, startup refusal, property and recogniser function documented here, or "none".
- [ ] AC8 (213 AC22) `mkdocs build --strict` and every `docs-site/hooks/check_*.py` pass with `DP_REQUIRE_NO_STUBS=true`. Build success alone is insufficient.

## Checks
- `docs-site` command — AC8
  run: `mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/extending\.md|docs/tools\.md|docs/faq\.md')"`
- `codes-on-pages` command — AC3, AC4
  run: `grep -q -w FREE_TEXT_RECOGNISED docs/tools.md && grep -q -w FREE_TEXT_RECOGNISER_FAILED docs/tools.md && grep -q -w MISSING_CASE_AUTHORIZER docs/extending.md && grep -q -w MISSING_CLIENT_ALLOWLIST docs/extending.md && test -z "$(grep -n -E 'AMBIGUOUS_(CASE_AUTHORIZER|CLIENT_ALLOWLIST)|NON_SINGLETON_' docs/extending.md docs/tools.md docs/faq.md)"`
- `no-compliance-wording` command — rules
  run: `test -z "$(git diff -U0 origin/main...HEAD -- docs | grep -E '^\+[^+]' | grep -i -E 'complian')"`
- `examples-compile` read — AC2, AC4
  how: reviewer checks each constructor and method in the examples against the merged source signatures (`CaseAuthorizer`, the recogniser SPI, `AuthorizationService`, `ScopeResolver`, `AuthenticatedCaller`).
- `controls` read — AC1, AC5, AC6
  how: reviewer checks ordering, failure behaviour, the egress caveat, the depth cap and the 128 limit against 208, 209, 210's Outcomes and the code.
- `hand-off` read — AC7
  how: the PR body has the list, in the format, with a source for each fact.

## Out of scope
- `docs/key-rotation.md`, `docs/log-shipping.md`, `docs/reidentification.md` (310), `docs/audit.md` and `docs/architecture.md` (311), the cluster pages (312), `docs/migration-0.8.md` and `CHANGELOG.md` (314).
- The startup warning for a source name over 128 characters (code; PLAN.md:142).
- 239-10, 239-12 (tools half), 239-17 to 239-20 and 239-22 (PLAN.md, 261's review leftovers in these pages), `tools.md:120` "unclassified values dropped", and `faq.md:68-70, :125-126`: not earmarked for 213; untasked.
- `docs/data-protection.md` (214), code, `docs/configuration.md`, version cuts and publication.

## Review round 1 — findings

Reviewed HEAD 58858e37fd328ba750e4d92449778fb30c222755. Owner decision 2026-11-12: the three P3s below are fixed in this round because each leaves the new docs wrong; the two NOTEs are follow-ups.

- [STYLE] docs/extending.md:1011-1012 — the new recogniser section cites internal plan ids "(task 209)", "(task 208)"; no user page does that (docs/faq.md:47-49 states the same fact without one). Drop the ids, keep the release versions.
- [P3] docs/faq.md:41 — "at any depth up to a size budget" contradicts the new faq.md:50 ("at most 16 levels of nesting"); SensitiveDataScanner MAX_DEPTH=16 refuses deeper responses. Correct line 41.
- [P3] docs/faq.md:52 — "refuses it with `RESPONSE_TOO_LARGE_TO_SCAN`" implies the client gets that code; the client sees `refused: VALIDATION_FAILED at $` (DefaultContextOrchestrator.java:377, :593-594), and `RESPONSE_TOO_LARGE_TO_SCAN` is the violation code only. Say which is which, as the new tools.md FREE_TEXT rows do.
- [P3] docs/extending.md, the `CaseAuthorizerExtensionAutoConfiguration` example — the `@Bean` method takes a `CaseAssignments` parameter that nothing in the jar or the server supplies; dropped on loader.path as shown, startup fails with `NoSuchBeanDefinitionException`. Make the example construct its lookup itself, or show the bean.

## Outcome

Landed on `main` as PR #257 (`52781007`), verified PASS and APPROVE at `277cd6671f89b1e16ade4f68c7c27e1882ff0c62` (2026-11-12). `docs/extending.md`, `docs/tools.md` and `docs/faq.md` now document case authorisation, client allowlisting and its lack of egress control, FREE_TEXT actions, recogniser ordering and fail-closed failures, the scanner's 16-level depth cap, and how a standalone server loads a `CaseAuthorizer`. Round 1 fixed three P3s: the contradictory depth statement, the client-visible refusal against the violation code, and an example bean that needed a `CaseAssignments` nothing supplies. The two Notes are in PLAN.md under "Wave 14a follow-ups".
