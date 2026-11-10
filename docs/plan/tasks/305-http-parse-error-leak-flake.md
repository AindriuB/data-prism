# 305 — Diagnose and fix the HttpParseErrorLeakTest tools/call flake

**Repo:** .
**Depends on:** none
**Decisions:** owner, 2026-11-10: filed. A product fix needs a separate owner decision (see outcome (b)).
**Owns:**
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java

## Goal
`HttpParseErrorLeakTest.toolCallArgumentsString` failed once in CI and passed on rerun. Find out, with evidence,
whether the test or the streamable HTTP transport is wrong, and fix the test. If the product is wrong, stop and
report: under (b) a malformed `tools/call` ends the connection, which undermines task 304's goal that a malformed
message is refused without ending the session.

## The failure
- 2026-11-10, `main` at 0af366c5, CI run 42854583742, job `build (25)`. A rerun of the failed job passed. The test
  arrived with task 304 (PR #237, merged as af2fb46c) and passed in 304's /verify runs and in #237's CI.
- `HttpParseErrorLeakTest.toolCallArgumentsString:119->refused:157->post:186 » IO HTTP/1.1 header parser received
  no bytes`. The server closed the connection without sending a response.
- Line 157 is the **follow-up `tools/list`**, not the malformed post on line 156. The refusal post returned. Then
  the next request, on the same `HttpClient`, got no bytes back.
- Case E (`tools/call` whose `arguments` is a string holding the sentinel) is forwarded to the server, unlike
  A to D and F, which the transport refuses with a 400. Each case starts its own Tomcat on port 0 and builds its
  own `HttpClient` (`:148-149`), so no connection is shared across cases. One case does share a pooled HTTP/1.1
  connection across its four posts.
- Hypotheses, unproven: (a) the JDK client reuses a keep-alive connection that Tomcat has already closed (an
  idle or close race), and the client does not retry a POST; (b) the SDK's handling of the failed `tools/call`
  ends the async response abnormally, and Tomcat closes the connection or the session.

## Context
- `HttpParseErrorLeakTest.java:116-121` (case E), `:138-174` (`refused`), `:176-187` (`post`), `:189-223`
  (in-process Tomcat).
- `docs/plan/tasks/retired/304-no-client-token-in-sdk-stdio-parse-errors.md:16-28` (Goal and the HTTP rows),
  `:49` (AC3, the HTTP test contract), `:78` (the SDK DEBUG residual). These are the leak guarantees a fix keeps.
- `data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java:247-280` — the
  streamable HTTP build and its `ContentFreeMcpJsonMapper`.
- `McpHttpEndToEndTest`, `ToolInputHttpTest` (same package) — other in-process Tomcat tests that use the same
  client pattern; read them for comparison, do not change them.
- `docs/plan/tasks/303-membercard-digest-flake.md` and `docs/plan/known-flakes.md` (the `ApprovalIdLockTest`
  row) — how a flake is reproduced, and the row form (symptom, cause, how reproduced, change).

## Decide between
| Outcome | Proven when | Change |
|---|---|---|
| (a) test defect | The refusal response is complete and well formed (status, headers, JSON-RPC error body). The dropped request is caused by ordinary connection life (for example keep-alive reuse after a server-side close, or start or stop timing), and the same drop reproduces with a well-formed request, such as a valid `tools/call` or a `ping`, in place of the case body. | Test only. The fix removes the race, for example a fresh connection per request or waiting on a stated condition, and the test still fails if the server drops the session or the connection because of the case body. A server close after a complete response is acceptable HTTP behaviour only if the client is told through `Connection: close`; a silent close of a pooled keep-alive connection after a refusal is a product defect under (b), so a fresh connection per request is not an acceptable fix unless the implementer has first shown which of the two it is. |
| (b) product defect | The drop happens only after a malformed `tools/call`: the SDK or the servlet aborts or errors the async response, closes the connection, or removes the session. The same steps with a well-formed request do not drop. | **Stop and report.** Do not edit product code. The owner decides, and the brief is amended to add the product files to `Owns`. |

## Acceptance
- [ ] AC1 The PR body (or, under (b), the stop report) states the outcome, (a) or (b), and the evidence: the
  refusal response's status, `Content-Type`, `Connection` header and body framing (JSON or SSE, complete or
  cut off); whether Tomcat or the client closed the connection and when; and the same observations for the
  well-formed control request.
- [ ] AC2 The drop is reproduced (a temporary delay, latch, loop or connection-level probe that forces the
  interleaving, removed before merge or kept as a deterministic test), and the PR body says how. The forced-interleaving reproduction fails on the
  base commit and passes after the fix, with both runs recorded in the Outcome; the 20 repeat runs are extra
  evidence, not the proof. If it never
  reproduces, the PR body states the attempt count and method, and AC5's row is `open`, not `fixed`.
- [ ] AC3 No assertion is weakened. No assertion in `refused` is removed or loosened; there is no catch or retry
  of a failed post, no retry of the whole test, no `@Disabled`, no timeout above the existing 30 s, and no
  sleep in place of a stated condition. The `tools/list` on the same session must still be required to answer 200
  with `get_entity_context`.
- [ ] AC4 Under (a), only the owned test file changes, and the PR body names how a server that drops the
  connection or session after the case body would still fail the test.
- [ ] AC5 The PR body carries a proposed `docs/plan/known-flakes.md` row for
  `HttpParseErrorLeakTest.toolCallArgumentsString`, in the form of the `ApprovalIdLockTest` row, citing run
  42854583742 and status `fixed` (cause and change named) or `open` (per AC2). The scribe adds it; the
  implementer does not edit `known-flakes.md`.
- [ ] AC6 `HttpParseErrorLeakTest` passes in full, and passes 20 consecutive runs of the whole class with no
  failure.
- [ ] AC7 The integration-tests module's suite passes.
- [ ] AC8 Under (b), after the owner's decision and an amendment adding the product files: the fix keeps 304's
  leak guarantees (no sentinel in any response body or captured log line at WARN or above, the session still
  answers `tools/list`), and `toolCallArgumentsString` asserts an HTTP status and a JSON-RPC error body
  (`"code":-32...`) for the refusal. A test fails on `origin/main` without the fix and passes with it.

## Checks
- `leak-class` test — AC6, AC3
  run: `mvn -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*HttpParseErrorLeakTest.xml`
  min-passing: 9  max-skipped: 0
- `class-repeat` command — AC6
  run: `for i in $(seq 20); do mvn -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false || exit 1; done`
- `integration-module` test — AC7
  run: `mvn -q -pl data-prism-integration-tests -am test`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main` plus any new tests  max-skipped: 0
- `scope` command — AC4 (under (a) only)
  run: `test "$(git diff --name-only origin/main...HEAD)" = "data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java"`
- `evidence-and-assertion` read — AC1, AC2, AC3, AC4, AC5, AC8
  how: reviewer reads the PR body for the outcome, the refusal and control observations, and the reproduction or
  attempt count. The reviewer reads the test diff for any loosened assertion, caught exception, retry or longer
  timeout, checks that a server-side drop would still fail the test, and reads the proposed known-flakes row.
  Under (b) the reviewer reads the amended brief and the product diff against 304's AC3.

## Out of scope
- Any product code, in `data-prism-mcp` or elsewhere, before the owner decides under (b).
- PLAN's 237-5 for the `initializeCapabilitiesString` case (status and live capture). Under (b), AC8 covers the
  status for `toolCallArgumentsString` only.
- The SDK DEBUG residual (304, `:78`) and the WARN lines PLAN lists as 304 residuals.
- Other in-process Tomcat tests (`McpHttpEndToEndTest`, `ToolInputHttpTest`, `AuditSinkFailureAbortsResponseTest`)
  even if they share the client pattern. Name them in the PR body if the cause applies to them.
- Editing `known-flakes.md`; running two `mvn` processes at once in the same worktree.
