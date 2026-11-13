# 317 — Close the 310, 311 and 313 review items on the key-rotation, audit and FAQ pages

**Repo:** `.`
**Release:** 0.8.0 (before the cut, 321)
**Executor:** `scribe`
**Depends on:** none
**Wave:** 1 (0.8.0 pre-cut, filed 2026-11-13)
**Owns:**
- docs/key-rotation.md
- docs/audit.md
- docs/architecture.md
- docs/faq.md *(the lines at about :41 and :52 only)*
- docs/extending.md *(the "## Add a free-text recogniser" section, from :967, and the "## Authorise cases and restrict clients" section, from :885, only)*

## Goal
The wave 14a reviews left wording findings on these pages that the owner did not fix in the round. Close
them before 0.8.0 so the released pages say what the code does. Wording fixes only.

## Context
- PLAN.md, "Wave 14a follow-ups", the "from 310", "from 311" and "from 313" bullets. Each finding's detail is
  in the PR review: #260 (310), #259 (311), #257 (313) (`gh pr view <n> --comments`), and in
  `docs/plan/tasks/retired/310-*.md:72-` and `313-*.md:65-`.
- `docs/audit.md:978` ("available since 0.8").
- `docs/faq.md:41`, `:52`; the 313 round-1 P3 on :52: the client sees `refused: VALIDATION_FAILED at $`
  (`DefaultContextOrchestrator.java:377`, `:593-594`); `RESPONSE_TOO_LARGE_TO_SCAN` is the violation code.
- `docs/extending.md:967` — the recogniser section.

## Acceptance
- [ ] AC1 (310) `docs/key-rotation.md` resolves each of: the id-clash wording; the "change both keys" route;
  which of the oldest and newest segment is meant; the audit id check; the collision-counter signal; and
  "two phases" against "three phases" (one count, used everywhere). The PR body lists the six with old and
  new line.
- [ ] AC2 (311) No sentence in `docs/audit.md` or `docs/architecture.md` says "since 0.8"; each reads
  "in 0.8.0".
- [ ] AC3 (313) `docs/faq.md:52` says which code the client receives and which is the violation code, as the
  FREE_TEXT rows in `docs/tools.md` do; the blank-strings wording matches the code (cite it in the PR body).
- [ ] AC4 (313) `docs/faq.md:41` is wrapped like its neighbours; no line on the page is over 120 characters
  unless it is a table row or a URL.
- [ ] AC5 (313) The recogniser section says a recogniser is handed one scalar value at a time, citing the
  call site in the PR body.
- [ ] AC6 (313) The allowlist-by-value note the 313 review asked for is in the `## Authorise cases and restrict clients`
  section of `docs/extending.md`; the PR body quotes the finding.

## Checks
- `docs-site` command — AC1 to AC6
  run: `/Users/Andrew/workspace/data-prism/.venv-docs/bin/mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/(key-rotation|audit|architecture|faq|extending)\.md')"`
- `no-since` command — AC2
  run: `test -z "$(grep -n 'since 0\.8\b' docs/audit.md docs/architecture.md)"`
- `facts` read — AC1, AC3, AC5, AC6
  how: reviewer checks each changed sentence against the finding and the cited code; and that the
  `docs/faq.md` and `docs/extending.md` hunks stay inside the owned sections.

## Out of scope
- The faq "Is it production-ready?" answer and every version literal (321).
- `docs/tools.md` and the `follow`/`_links` captures (306).
- Any page in 316's Owns.
