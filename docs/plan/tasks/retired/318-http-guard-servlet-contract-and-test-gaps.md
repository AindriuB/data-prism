# 318 — State the `HttpTransport.servlet()` contract and close the guard's test gaps

**Repo:** `.`
**Release:** 0.8.0 (before the cut, 321)
**Executor:** `implementer`
**Depends on:** none
**Wave:** 1 (0.8.0 pre-cut, filed 2026-11-13)
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java *(the `HttpTransport` record's `servlet()` Javadoc and exception message only)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/HttpInboundGuardTest.java

## Goal
`HttpTransport.servlet()` is new public API in 0.8.0 and `HttpInboundGuard` is a new fail-closed boundary.
Its Javadoc credits the guard with Tomcat's close-after-400, its failure message does not tell an integrator
what to do, and the tests would not catch a regression in the size cap. Fix the two texts and add the tests.
No behaviour change.

## Context
- `docs/plan/tasks/305-http-parse-error-leak-flake.md:336-346` ("Follow-ups (from PR #267 review)": 267-3,
  267-7, 267-11); it moves to `tasks/retired/` with PR #268.
- PLAN.md (branch `origin/record-flakes`), "Flake fixes 303/305/308 follow-ups", the Content-Length bullet:
  `post()` never stubs `getContentLengthLong`, so mutating `>` to `>=` at `HttpInboundGuard.java:122`, or
  refusing on `-1`, passes every test.
- `DataPrismMcpServer.java:362-379` (`servlet()`), `:384-385` (`GUARD_MAPPERS`).
- `HttpInboundGuard.java:33-47` (contract, `SDK_REQUEST_MAX_SIZE`), `:116-130` (the bounded read),
  `:233-245` (`ReplayedRequest`).
- `HttpInboundGuardTest.java:130-146` (`run`, `assertRefusal`), `:189-202` (`declaredLengthOverLimitIsRefusedUnread`),
  `:227` (`limit`). 10 tests at filing.
- `docs/conventions.md` — test naming and one behaviour per test.

## Acceptance
- [ ] AC1 (267-11) The `servlet()` Javadoc says the 400 is the guard's and the `Connection: close` comes from
  the servlet container closing after a 400, not from the guard.
- [ ] AC2 (267-3) The `servlet()` Javadoc says it works only on a transport built by
  `DataPrismMcpServer.streamableHttp`, and the `IllegalStateException` message names
  `DataPrismMcpServer.streamableHttp` and says a directly mounted `transportProvider()` is unguarded. The
  message holds no client content.
- [ ] AC3 A test proves a declared `Content-Length` equal to the limit is not refused before the read.
- [ ] AC4 A test proves a declared `Content-Length` of `-1` (unknown) with a small valid body is forwarded.
- [ ] AC5 A test proves a declared length of limit + 1 is refused with 400/-32600 and `getInputStream` is never
  called (keep or extend `declaredLengthOverLimitIsRefusedUnread`).
- [ ] AC6 (267-7) Tests cover: a body over the limit that arrives in several reads is refused; a request with
  an unsupported charset follows the guard's documented path; `ReplayedRequest.getReader()` returns the
  buffered body; and `servlet()` on an `HttpTransport` not built by `streamableHttp` throws AC2's message.
- [ ] AC7 Each new test asserts one behaviour; mutating `>` to `>=` at `HttpInboundGuard.java:122` makes at
  least one test fail (show the run in the PR body, then revert the mutation).
- [ ] AC8 No file under `src/main` changes except the two texts in AC1 and AC2.

## Checks
- `guard-unit` test — AC3 to AC7
  run: `./mvnw -q -pl data-prism-mcp -am test -Dtest=HttpInboundGuardTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-mcp/target/surefire-reports/TEST-*HttpInboundGuardTest.xml`
  min-passing: 15  max-skipped: 0
- `mcp-module` test — AC8
  run: `./mvnw -q -pl data-prism-mcp -am test`
  report: `data-prism-mcp/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main` plus the new tests  max-skipped: 0
- `full-build` command — AC8
  run: `./mvnw -B verify`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'data-prism-mcp/src/(main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer|test/java/io/github/aindriub/dataprism/mcp/HttpInboundGuardTest)\.java')"`
- `texts` read — AC1, AC2, AC8
  how: reviewer reads the `DataPrismMcpServer.java` hunk: Javadoc and one string literal only, no logic.

## Out of scope
- Moving `GUARD_MAPPERS`, `classify`'s `Object[]` (267-10), check order (267-4), double parse (267-5),
  `HttpParseErrorLeakTest` (267-6, 267-8), splitting existing tests (267-9): deferred to 0.9 (322).
- Any change to what the guard refuses or forwards.

## Outcome

Landed 2026-11-13, PR #275, merge `c2d99ddc`, verified at `fd362c28`. `HttpTransport.servlet()` Javadoc no longer credits the guard with Tomcat's close-after-400 and its failure message tells an integrator what to do; `HttpInboundGuardTest` gained tests that fail if the size cap regresses. No behaviour change. Tester PASS, reviewer APPROVE. Follow-ups are in `PLAN.md`.
