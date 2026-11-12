# 305 — Refuse a malformed HTTP `tools/call` cleanly without ending the connection

**Repo:** .
**Depends on:** none
**Blocks:** the 0.8.0 release.
**Decisions:** owner, 2026-11-10: filed. Owner, 2026-11-12, verbatim: "Yes, amend 305". Outcome (b), a
product defect: 305 now owns the product fix in `data-prism-mcp`. Reason: the defect breaks task 304's goal
that a malformed message is refused without ending the session, and it leaks internal stack traces to
clients, which fails the fail-closed rule.
**Review:** this changes fail-closed behaviour on the request path. It is not a small change: full review
(code-reviewer, adversarial and regression passes), whatever the line count.
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java (the streamable HTTP
  `build` at `:260-280` and the `HttpTransport` record at `:355`; the record's component types do not change)
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapper.java (the server
  mapper on the failing `convertValue` path)
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/HttpInboundGuard.java *(new, only if the guard
  route is taken)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/HttpInboundGuardTest.java *(new, as above)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServerTest.java
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapperTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java
- pom.xml — the `mcp.version` property line (`:72`) only, and only if the upgrade route is taken

## Goal
A `tools/call` whose params have the wrong type (case E: `arguments` is a string) gets HTTP 500, a serialised
`McpError` with a full `stackTrace`, and a closed keep-alive connection (see Attempt 1). Make it, and any other
param-type error on the same path, a clean JSON-RPC refusal that leaves the connection and session usable.

## Approach, in order
1. **Upgrade if it fixes it.** Read the MCP Java SDK release notes and changelog after 2.1.0 for
   `HttpServletStreamableServerTransportProvider` (error handling on the request stream, `asyncComplete`,
   `responseError`). If a release fixes this and the upgrade is low-risk (no API change at our call sites, no
   new transitive dependency outside what the enforcer allows), take it: change `mcp.version` only.
2. **Otherwise guard.** Validate or catch in `data-prism-mcp` so the SDK never reaches the failing path.
   `StdioInboundGuard` is the pattern (content-free checks, one constant log line, no exception logged).
- The fix sits behind `HttpTransport.transportProvider()`. Every mount point keeps working unchanged: the
  Spring `ServletRegistrationBean` at `McpTransportWiring.java:121-128` and the test Tomcats that call
  `Tomcat.addServlet(..., transport.transportProvider())`. If that is impossible without changing the record's
  types or a mount point, stop and report; those files are not owned.
- **Fail closed.** A guard failure, or a guard that cannot decide, refuses. It never forwards the message.
- **No bypass of the privacy engine.** The guard only refuses. It never builds a tool result, and a valid
  `tools/call` still reaches `ToolCalls` and the orchestrator unchanged.

## Agreed refusal (consistent with 304, where A to D and F get 400)
| Case | Status | Body | Connection | Session |
|---|---|---|---|---|
| E, `tools/call` params type error | 400 | `application/json` JSON-RPC error, `"jsonrpc":"2.0"`, the request's `id`, `"code":-32602` | no `Connection: close`; the socket stays open | answers the next request |
| E, `initialize` capabilities string | 4xx, asserted exactly as observed after the fix | JSON-RPC error, `"code":-32...` | as above | as above |
| A to D, F (transport refusals) | 400, unchanged | JSON-RPC error, `"code":-32...` | as above | as above |
No body in any row holds `stackTrace`, `"cause"`, a Java class name (`io.modelcontextprotocol.`, `org.apache.`,
`java.`, `jdk.`) or the sentinel. If a row other than the first already carries a stack trace on `origin/main`,
the same fix removes it; record that in the PR body.

## Context
- `HttpParseErrorLeakTest.java:116-128` (the two E cases), `:138-174` (`refused`), `:176-187` (`post`),
  `:189-223` (in-process Tomcat). `refused` asserts no status for E cases and passes on any body with `-32`.
- `DataPrismMcpServer.java:247-280` — the streamable HTTP build; `StdioInboundGuard.java:19-37` — the guard
  pattern; `ContentFreeMcpJsonMapper.java:84-100` — `convertValue`.
- `docs/plan/tasks/retired/304-no-client-token-in-sdk-stdio-parse-errors.md:16-28` (Goal, the SDK table) and
  `:49` (AC3). These are the leak guarantees the fix keeps.
- `docs/workflow.md:68` — the full build.

## Acceptance
- [ ] AC1 The PR body states outcome (b) and carries the Attempt 1 evidence below, updated with anything new.
- [ ] AC2 The reproduction is AC10's test, which fails on the base commit and passes after the fix; both runs
  are recorded in the PR body.
- [ ] AC3 No assertion is weakened. No assertion in `refused` is removed or loosened; no catch or retry of a
  failed post, no test retry, no `@Disabled`, no timeout above 30 s, no sleep in place of a stated condition.
  The follow-up `tools/list` must still answer 200 with `get_entity_context`.
- [ ] AC4 *Void under (b).*
- [ ] AC5 The proposed known-flakes row (below) is in the PR body, with the PR number filled in. The scribe
  adds it at /record; the implementer does not edit `known-flakes.md`.
- [ ] AC6 `HttpParseErrorLeakTest` passes in full and passes 20 consecutive runs of the whole class.
- [ ] AC7 The integration-tests module's suite passes.
- [ ] AC8 *Superseded by AC9 to AC15.*
- [ ] AC9 Case E (`tools/call`) returns the first row of the refusal table: 400, the JSON-RPC body, no
  `stackTrace`, no Java class name, no sentinel. The captured log for the case holds no sentinel, no
  `asyncComplete()` line and no stack trace.
- [ ] AC10 A test in `HttpParseErrorLeakTest` sends `initialize`, `notifications/initialized`, case E and
  `tools/list` over one raw `Socket` to `127.0.0.1` (one keep-alive HTTP/1.1 connection), and asserts the
  `tools/list` answer is 200 with `get_entity_context`. It fails on `origin/main` (EOF) and passes with the fix.
- [ ] AC11 `refused` asserts the status and body shape of every case per the refusal table, including the
  absence of `stackTrace` and Java class names.
- [ ] AC12 The PR body shows AC10's test and `toolCallArgumentsString` failing without the fix (the failure
  message from a run on the base, or with the fix reverted) and passing with it.
- [ ] AC13 The full build passes: `./mvnw -B verify`.
- [ ] AC14 The PR body lists what `McpHttpEndToEndTest`, `ToolInputHttpTest` and
  `AuditSinkFailureAbortsResponseTest` send as a malformed `tools/call` (file:line, or "none"), and whether
  each still passes. They are read, not changed.
- [ ] AC15 If the SDK is upgraded, the PR body names the version, links its release notes and quotes the entry
  that fixes this. If not, it names the versions checked and why each was rejected.

## Checks
- `leak-class` test — AC3, AC6, AC9, AC10, AC11
  run: `mvn -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*HttpParseErrorLeakTest.xml`
  min-passing: 10  max-skipped: 0
- `class-repeat` command — AC6
  run: `for i in $(seq 20); do mvn -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false || exit 1; done`
- `mcp-module` test — AC9 (guard route)
  run: `mvn -q -pl data-prism-mcp -am test`
  report: `data-prism-mcp/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main` plus any new tests  max-skipped: 0
- `integration-module` test — AC7, AC14
  run: `mvn -q -pl data-prism-integration-tests -am test`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main` plus any new tests  max-skipped: 0
- `full-build` command — AC13
  run: `./mvnw -B verify`
- `scope` command — Owns
  run: `git diff --name-only origin/main...HEAD | grep -v -e '^data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/\(DataPrismMcpServer\|ContentFreeMcpJsonMapper\|HttpInboundGuard\)\.java$' -e '^data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/\(DataPrismMcpServerTest\|ContentFreeMcpJsonMapperTest\|HttpInboundGuardTest\)\.java$' -e '^data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest\.java$' -e '^pom\.xml$' | (! grep .)`
- `fail-closed-and-evidence` read — AC1, AC2, AC3, AC5, AC9, AC12, AC14, AC15
  how: reviewer reads the product diff for any path where a guard failure forwards the message, any path that
  builds a tool result outside `ToolCalls`, and any log line carrying client content or an exception; reads a
  `pom.xml` diff for a change other than `mcp.version`; reads the test diff against AC3 and 304's AC3; reads the
  PR body for the before and after runs, the AC14 list, the AC15 notes and the known-flakes row.

## Out of scope
- The 404 at `refused:152` on the first `initialize` (the `localhost` versus `127.0.0.1` lead). Attempt 1 does
  not explain it. It is a follow-up; the existing tests' `localhost` URI stays as it is.
- `McpTransportWiring`, the `HttpTransport` record's component types, and any other mount point.
- PLAN's 237-5 live-capture half for `initializeCapabilitiesString` (its status assertion is now AC11's).
- The SDK DEBUG residual (304, `:78`) and the WARN lines PLAN lists as 304 residuals.
- Changing `McpHttpEndToEndTest`, `ToolInputHttpTest` or `AuditSinkFailureAbortsResponseTest`.
- Editing `known-flakes.md`; running two `mvn` processes at once in the same worktree.

## Proposed known-flakes row (for the scribe at /record)
Replace the status cell of the `HttpParseErrorLeakTest.toolCallArgumentsString` row with:
"`no bytes` variant at `refused:157`: `fixed` by task 305 (PR #<n>). Cause, a product defect: a `tools/call`
whose `arguments` is a string made the SDK's streamable HTTP transport answer 500 with a serialised `McpError`
and stack trace, send `Connection: close`, and close the keep-alive socket; the JDK client's reuse of that
socket was the only racy part. Reproduced deterministically over one raw socket (EOF on the next request).
Fix: <upgrade to SDK x.y.z | guard in data-prism-mcp>, answering 400 with a `-32602` JSON-RPC error and keeping
the connection open; the raw-socket test fails on the base and passes after. The 404 variant at `refused:152`
stays `open` and is not explained by this."

## Attempt 1 — outcome (b), stopped for owner
- Probe: a raw socket, one keep-alive HTTP/1.1 connection to the in-process Tomcat on 127.0.0.1, with the same
  server setup as the test.
- Case E is a `tools/call` whose `arguments` is a string. On the wire:
  - The response is 500, not 400.
  - The server logs at ERROR "Failed to handle request stream: Failed to convert value", then "Error handling
    message: Calling [asyncComplete()] is not valid for a request with Async state [MUST_COMPLETE]".
  - The response carries both `Connection: keep-alive` and `Connection: close`.
  - The body is a chunked, serialised `McpError` with `jsonRpcError.code=-32603`, a full `stackTrace` array
    (SDK, Tomcat and JDK class names, files, lines) and `cause:null`. No sentinel leaked.
  - Tomcat closes the socket. The next `tools/list` on that socket gets EOF with zero bytes, which is the CI
    symptom at `refused:157`.
- Control: a valid `ping` returns 200 as an SSE event, and the connection stays open.
- The close is deterministic on the server. Only the JDK HttpClient's choice to reuse the connection is racy.
- The root cause appears to be inside the SDK's `HttpServletStreamableServerTransportProvider`, wired at
  `data-prism-mcp/.../DataPrismMcpServer.java:247-280`.
- The test does not assert case E's HTTP status, and it passes on any body containing `-32`.
- The local 404 variant at `refused:152` (the first `initialize`) is NOT explained by this. Keep it out of
  305's scope; it is a follow-up.

## Appendix: superseded
Agents: do not read this section.

Original goal (2026-11-10): `HttpParseErrorLeakTest.toolCallArgumentsString` failed once in CI and passed on
rerun. Find out, with evidence, whether the test or the streamable HTTP transport is wrong, and fix the test.
If the product is wrong, stop and report. Original failure record: CI run 42854583742, `main` at 0af366c5, job
`build (25)`, `toolCallArgumentsString:119->refused:157->post:186 » IO HTTP/1.1 header parser received no
bytes`; hypotheses (a) keep-alive close race, (b) SDK aborts the async response. Original decision table:
(a) test defect, test-only fix; (b) product defect, stop and report. Original AC4: under (a), only the test file
changes. Original AC8: under (b), keep 304's leak guarantees and assert status and `"code":-32...` for
`toolCallArgumentsString`. Original scope check: the diff is exactly the test file.
