# 315 — Changelog and migration notes: the HTTP inbound guard, and the 14b fixes

**Repo:** `.`
**Release:** 0.8.0 (before the cut, 321)
**Executor:** `scribe`
**Depends on:** none
**Wave:** 1 (0.8.0 pre-cut, filed 2026-11-13)
**Owns:**
- CHANGELOG.md *(the `## [Unreleased]` section only)*
- docs/migration-0.8.md

## Goal
`[Unreleased]` says nothing about task 305's behaviour change, so an integrator upgrading to 0.8.0 is not told
that malformed MCP HTTP requests now get a different status, or that a direct `transportProvider()` mount is
unguarded. Add those lines, and close the 14b review items that sit in these two files.

## Context
- Task 305 (PR #267): `docs/plan/tasks/305-http-parse-error-leak-flake.md` ("Agreed refusal", "Residuals"),
  moved to `tasks/retired/` by PR #268. The facts to state, each to be confirmed against the merged code:
  - `HttpInboundGuard.java:33-47` (contract and the 16 MiB cap), `:122` (declared length over the cap),
    `:179` (status 400).
  - `DataPrismMcpServer.java:362-379`, `HttpTransport.servlet()`; `McpTransportWiring.java:124`, the
    auto-configuration mounts `servlet()`.
  - `ContentFreeMcpJsonMapper` writes any `Throwable` as a JSON-RPC error with no `stackTrace` or `cause`,
    on every mount, guarded or not.
- PLAN.md (branch `origin/record-flakes`, PR #268), "Flake fixes 303/305/308 follow-ups", last two bullets.
- PLAN.md, "Wave 14b follow-ups" (a), (b) and (c).
- `docs/plan/tasks/retired/314-write-the-0-8-migration-notes-and-changelog.md` — the brief that wrote both
  files; follow its section order and wording rules.
- CHANGELOG.md `[Unreleased]` sections at filing: Security :10, Breaking :14, Added :84, Changed :118,
  Fixed :133. `docs/migration-0.8.md:264` is the `## Breaking:` heading pattern for (a).

## Acceptance
- [ ] AC1 `[Unreleased]` has a line saying that a `tools/call` or `initialize` whose `params` are absent,
  `null` or of the wrong type now gets HTTP 400 with JSON-RPC error `-32602` and the request id, where 0.7.x
  answered 500 with `-32603`, and that the MCP session stays usable on a new connection.
- [ ] AC2 `[Unreleased]` Security has a line saying no MCP HTTP error body carries a stack trace or a `cause`
  any more, on every mount.
- [ ] AC3 `[Unreleased]` says a request body over the SDK's 16 MiB limit is now refused with 400 and
  `-32600` before it is read, where the SDK answered 413. If the code says otherwise, write what the code
  does and note the difference in the PR body.
- [ ] AC4 `[Unreleased]` and `docs/migration-0.8.md` both state the residual: a servlet that mounts
  `HttpTransport.transportProvider()` directly is not guarded and keeps the 500 for these cases (without a
  stack trace); integrators mount `HttpTransport.servlet()`, which the auto-configuration already does; and
  `servlet()` works only on a transport built by `DataPrismMcpServer.streamableHttp`.
- [ ] AC5 (14b a) The `SensitivePatternValidator(SensitiveDataScanner)` removal note (about
  `docs/migration-0.8.md:403`) sits under its own `## Breaking:` heading, not under "## Added: free-text
  recognisers".
- [ ] AC6 (14b b) `docs/migration-0.8.md` says that `DataClassification.FREE_TEXT`, a new constant in an enum
  released in 0.7.1, breaks an exhaustive `switch` with no `default`, and shows the fix.
- [ ] AC7 (14b c) The `ClusterSecurityStatus` change is classed the same way in CHANGELOG and on the migration
  page (both Breaking, or both Changed); the PR body says which and why.
- [ ] AC8 Every changed CHANGELOG line sits between `## [Unreleased]` and the next `## [` heading.

## Checks
- `docs-site` command — AC1 to AC8
  run: `/Users/Andrew/workspace/data-prism/.venv-docs/bin/mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/migration-0\.8\.md|CHANGELOG\.md')"`
- `named-items` command — AC1, AC2, AC4, AC6
  run: `for s in -32602 'HttpTransport.servlet()' 'transportProvider()' stack; do grep -q -F -- "$s" CHANGELOG.md || exit 1; done; for s in 'HttpTransport.servlet()' FREE_TEXT; do grep -q -F -- "$s" docs/migration-0.8.md || exit 1; done`
- `no-compliance-wording` command — conventions
  run: `test -z "$(git diff -U0 origin/main...HEAD -- docs CHANGELOG.md | grep -E '^\+[^+]' | grep -i -E 'complian|tamper-proof')"`
- `facts` read — AC1 to AC7
  how: reviewer checks each new sentence against the cited code lines and finds AC8 by reading the hunk
  positions.

## Out of scope
- Versioning or dating the CHANGELOG (321 does that).
- The `MISSING_CASE_AUTHORIZER` line: it is already at CHANGELOG.md:74; 321 checks it.
- Any code, including the `servlet()` message and Javadoc (318).
- 14b (d), the load-test `SKIPPED_OVER_LIMIT` list (deferred, 322); 14b (e) (316).
