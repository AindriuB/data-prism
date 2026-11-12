# 305 — Refuse a malformed HTTP `tools/call` cleanly without ending the connection

**Repo:** .
**Depends on:** none
**Blocks:** the 0.8.0 release.
**Decisions:**
- Owner, 2026-11-10: filed.
- Owner, 2026-11-12, verbatim: "Yes, amend 305". Outcome (b), a product defect: 305 owns the product fix in
  `data-prism-mcp`. Reason: the defect breaks task 304's goal that a malformed message is refused without
  ending the session, and it leaks internal stack traces to clients, which fails the fail-closed rule.
- Owner, 2026-11-12, verbatim: "Yes, that route". This adopts the architect's Route B2 (a servlet guard that
  wraps the SDK servlet) plus mapper hardening; no SDK upgrade. Reason: under SDK 2.1.0, `McpAsyncServer`'s
  `tools/call` handler calls `jsonMapper.convertValue(params, CallToolRequest)` synchronously, before
  `onErrorResume` is attached. The exception escapes `stream.handle(req).block()` in `doPost`. `doPost` calls
  `asyncContext.complete()` twice; the second call throws an ISE, which reaches the outer catch and a
  hard-coded `responseError(res, 500, McpError(-32603))`. So the mapper cannot change the status or the
  connection. No newer SDK fixes it: the only newer release is v2.2.0-M1, a pre-release whose notes do not
  touch this. Separately, `responseError` serialises the `McpError` Throwable through
  `jsonMapper.writeValueAsString`, which leaks a `stackTrace` for cases A to D and F (400/-32600) and for
  `initialize` (500/-32603) too. That is inferred from the shared path and has not been run.
- PR #264 review round 1: 264-1 (Medium) settled by the guard route below, since the guard cannot sit behind
  `transportProvider()` (the record component and the registration bean are typed to the SDK class, whose
  constructor is private). 264-2 (Low) settled by bounding the goal to named cases.

**Review:** this changes fail-closed behaviour on the request path. It is not a small change: full review
(code-reviewer, adversarial and regression passes), whatever the line count.
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java (the streamable HTTP
  `build` at `:247-280` and the `HttpTransport` record at `:355`; its two components do not change)
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/HttpInboundGuard.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapper.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/McpTransportWiring.java
  (`dataPrismMcpServlet` at `:121-128` only)
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/HttpInboundGuardTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServerTest.java
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapperTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ToolInputHttpTest.java
  (the mount at `:208` only)

## Goal
A `tools/call` whose params have the wrong type (case E: `arguments` is a string) gets HTTP 500, a serialised
`McpError` with a full `stackTrace`, and a closed keep-alive connection (see Attempt 1). Make exactly these
cases clean JSON-RPC refusals that leave the connection and session usable: `tools/call` params that do not
convert to `CallToolRequest`, and `initialize` params that do not convert to `InitializeRequest`. Also remove
the serialised stack trace from the SDK's transport refusals (cases A to D and F).

## Approach
- **`HttpInboundGuard`**, package-private, `extends HttpServlet`, in `data-prism-mcp`. It wraps the SDK servlet.
  - It buffers the request body, capped at the SDK's own request-size limit (find where the SDK sets it and
    cite it in the PR body; if the SDK has none, stop and report). It replays the body to the SDK servlet
    through an `HttpServletRequestWrapper`. A body over the cap is refused (400/-32600), never forwarded.
  - For `tools/call` it checks that params convert to `McpSchema.CallToolRequest`; for `initialize`, to
    `McpSchema.InitializeRequest`. It uses the SDK's own conversions through the same content-free mapper the
    SDK is given, not a re-implementation.
  - On a failed conversion it answers 400 `application/json` with
    `{"jsonrpc":"2.0","id":<echoed id>,"error":{"code":-32602,"message":<constant>}}`, logs one constant line
    (no client content, no exception), and sets no `Connection` header.
  - Everything else is forwarded unchanged, including bodies it cannot parse (the SDK refuses those).
  - Any exception inside the guard refuses with 400/-32600 and never forwards.
  - `init`, `destroy` and the servlet config delegate to the SDK servlet.
  - `StdioInboundGuard` is the pattern for content-free checks and logging.
- **`HttpTransport`** keeps both components and gains a method, e.g. `servlet()`, returning the guarded servlet.
  `transportProvider()` stays.
- **`McpTransportWiring`** registers `servlet()`, with registration bean type `HttpServlet`, and keeps
  `asyncSupported` true. `DataPrismAutoConfigurationTest:97-100` must pass unchanged.
- **Test Tomcats** in `HttpParseErrorLeakTest:219` and `ToolInputHttpTest:208` mount `servlet()`.
- **Mapper hardening.** `ContentFreeMcpJsonMapper.writeValueAsString` and `writeValueAsBytes` serialise any
  `Throwable` (including `McpError`) as `{"jsonrpc":"2.0","id":null,"error":{"code":..,"message":..}}`, with no
  `stackTrace` and no `cause`. Every other write still delegates unchanged (304's boundary).
- **No SDK upgrade.** `pom.xml` does not change.
- **Fail closed; no bypass of the privacy engine.** The guard only refuses. It never builds a tool result, and a
  valid `tools/call` still reaches `ToolCalls` and the orchestrator unchanged.

## Agreed refusal
| Case | Status | Body | Connection | Session |
|---|---|---|---|---|
| E, `tools/call` params type error | 400, by the guard | JSON-RPC envelope, request `id`, `"code":-32602` | no `Connection: close`; socket stays open | answers the next request |
| E, `initialize` capabilities string | 400, by the guard | JSON-RPC envelope, request `id`, `"code":-32602` | as above | as above |
| A to D, F (SDK transport refusals) | 400, unchanged | JSON-RPC envelope, id echoed or null, `"code":-32600` | as above | as above |
No body in any row holds `stackTrace`, `"cause"`, a Java class name (`io.modelcontextprotocol.`, `org.apache.`,
`java.`, `jdk.`) or the sentinel.

## Residuals (accepted with this route)
- Direct `transportProvider()` mounts bypass the guard: `CorrelationConfigurationTest:357` (and `:373`, `:388`),
  `AuditEntityTypesConfigurationTest:106`, `FactoryEntityTypesDeliveryTest:196`, `DataPrismMcpServerTest`
  (`:160-161`, `:299`), and any external integrator. The mapper hardening still removes the `stackTrace` for
  them, but they keep the 500 and the connection close on case E.
- Whether an external demo project mounts `transportProvider()` directly is unchecked.

## Context
- `HttpParseErrorLeakTest.java:116-128` (the two E cases), `:138-174` (`refused`), `:176-187` (`post`),
  `:189-223` (in-process Tomcat). `refused` asserts no status for E cases and passes on any body with `-32`.
- `DataPrismMcpServer.java:247-280` — the streamable HTTP build; `StdioInboundGuard.java:19-37` — the guard
  pattern; `ContentFreeMcpJsonMapper.java:84-100` — `convertValue`.
- `McpTransportWiring.java:121-128` — the registration bean.
- `docs/plan/tasks/retired/304-no-client-token-in-sdk-stdio-parse-errors.md:16-32` (Goal, the SDK table, the
  mapper's write boundary) and `:49` (AC3). These are the leak guarantees the fix keeps.
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
- [ ] AC8 *Superseded by AC9 to AC20.*
- [ ] AC9 Case E (`tools/call`) returns the first row of the refusal table. The captured log for the case holds
  no sentinel, no stack trace and no `asyncComplete()` line, because the guard refuses before the SDK is reached.
- [ ] AC10 A test in `HttpParseErrorLeakTest`, on a Tomcat mounting `servlet()`, sends `initialize`,
  `notifications/initialized`, case E and `tools/list` over one raw `Socket` to `127.0.0.1` (one keep-alive
  HTTP/1.1 connection), and asserts the `tools/list` answer is 200 with `get_entity_context`. It fails on
  `origin/main` (EOF) and passes with the fix. Its SSE answers show `startAsync()` works through the wrapper.
- [ ] AC11 `refused` asserts, for every case (A to D, E `tools/call`, F, E `initialize` capabilities): status
  400; a JSON-RPC envelope (`"jsonrpc":"2.0"`, the id echoed or null, code `-32…`); no `stackTrace`; no
  `"cause"`; no Java class name; no sentinel.
- [ ] AC12 The PR body shows AC10's test and `toolCallArgumentsString` failing without the fix (the failure
  message from a run on the base, or with the fix reverted) and passing with it.
- [ ] AC13 The full build passes: `./mvnw -B verify`.
- [ ] AC14 The PR body lists what `McpHttpEndToEndTest`, `ToolInputHttpTest` and
  `AuditSinkFailureAbortsResponseTest` send as a malformed `tools/call` (file:line, or "none"), and whether
  each still passes. Only `ToolInputHttpTest:208` (the mount) changes.
- [ ] AC15 The PR body names the SDK versions checked (2.1.0 and v2.2.0-M1 at least) and why no upgrade was
  taken; `pom.xml` is unchanged.
- [ ] AC16 `initialize` with a string `capabilities` returns 400 with `"code":-32602` and the request id, from
  the guard (an `HttpInboundGuardTest` case shows the SDK servlet is not invoked).
- [ ] AC17 A valid `tools/call` forwarded through the replaying wrapper behaves identically: `ToolInputHttpTest`
  and `McpHttpEndToEndTest` pass with no assertion changed, and an `HttpInboundGuardTest` case shows a body
  exactly at the size limit is forwarded, byte for byte, and one byte over is refused.
- [ ] AC18 A `ContentFreeMcpJsonMapperTest` case: `writeValueAsString` and `writeValueAsBytes` of an `McpError`
  (with a cause) yield the JSON-RPC envelope with no `stackTrace`, no `cause` and no Java class name.
- [ ] AC19 An `HttpInboundGuardTest` case: an exception inside the guard (e.g. a throwing mapper or a failing
  body read) gives 400/-32600 and the SDK servlet is never invoked.
- [ ] AC20 `McpTransportWiring` registers `transport.servlet()` in a `ServletRegistrationBean<HttpServlet>`
  with `asyncSupported` true; `DataPrismAutoConfigurationTest` passes unchanged.

## Checks
- `leak-class` test — AC3, AC6, AC9, AC10, AC11
  run: `mvn -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*HttpParseErrorLeakTest.xml`
  min-passing: 10  max-skipped: 0
- `class-repeat` command — AC6
  run: `for i in $(seq 20); do mvn -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false || exit 1; done`
- `mcp-module` test — AC16, AC17, AC18, AC19
  run: `mvn -q -pl data-prism-mcp -am test`
  report: `data-prism-mcp/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main` plus the new tests  max-skipped: 0
- `autoconfigure-module` test — AC20
  run: `mvn -q -pl data-prism-spring-boot-autoconfigure -am test`
  report: `data-prism-spring-boot-autoconfigure/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main`  max-skipped: 0
- `integration-module` test — AC7, AC14, AC17
  run: `mvn -q -pl data-prism-integration-tests -am test`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*.xml`
  min-passing: the count on `origin/main` plus the new tests  max-skipped: 0
- `full-build` command — AC13
  run: `./mvnw -B verify`
- `no-pom` command — AC15
  run: `git diff --exit-code origin/main...HEAD -- pom.xml`
- `scope` command — Owns
  run: `git diff --name-only origin/main...HEAD | grep -v -e '^data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/\(DataPrismMcpServer\|ContentFreeMcpJsonMapper\|HttpInboundGuard\)\.java$' -e '^data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/\(DataPrismMcpServerTest\|ContentFreeMcpJsonMapperTest\|HttpInboundGuardTest\)\.java$' -e '^data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/McpTransportWiring\.java$' -e '^data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/\(HttpParseErrorLeakTest\|ToolInputHttpTest\)\.java$' | (! grep .)`
- `fail-closed-and-evidence` read — AC1, AC2, AC3, AC5, AC9, AC12, AC14, AC15, AC20
  how: reviewer reads the product diff for any path where a guard failure, an over-cap body or an
  undecidable message is forwarded; any path that builds a tool result outside `ToolCalls`; any log line
  carrying client content or an exception; any mapper write other than a `Throwable` that no longer delegates;
  the `ToolInputHttpTest` diff for anything beyond the mount line; the test diff against AC3 and 304's AC3; and
  the PR body for the before and after runs, the AC14 list, the AC15 notes and the known-flakes row.

## Out of scope
- The 404 at `refused:152` on the first `initialize` (the `localhost` versus `127.0.0.1` lead). Attempt 1 does
  not explain it. It is a follow-up; the existing tests' `localhost` URI stays as it is.
- The residual mounts listed above: `CorrelationConfigurationTest`, `AuditEntityTypesConfigurationTest`,
  `FactoryEntityTypesDeliveryTest` and `DataPrismAutoConfigurationTest` are not changed.
- The `HttpTransport` record's component types; removing or renaming `transportProvider()`.
- An SDK upgrade or any `pom.xml` change.
- Param-type errors on methods other than `tools/call` and `initialize`.
- PLAN's 237-5 live-capture half for `initializeCapabilitiesString` (its status assertion is now AC11's).
- The SDK DEBUG residual (304, `:78`) and the WARN lines PLAN lists as 304 residuals.
- Changing `McpHttpEndToEndTest` or `AuditSinkFailureAbortsResponseTest`, or `ToolInputHttpTest` beyond `:208`.
- Editing `known-flakes.md`; running two `mvn` processes at once in the same worktree.

## Proposed known-flakes row (for the scribe at /record)
Replace the status cell of the `HttpParseErrorLeakTest.toolCallArgumentsString` row with:
"`no bytes` variant at `refused:157`: `fixed` by task 305 (PR #<n>). Cause, a product defect: under SDK 2.1.0 a
`tools/call` whose `arguments` is a string made the streamable HTTP transport answer 500 with a serialised
`McpError` and stack trace, send `Connection: close`, and close the keep-alive socket; the JDK client's reuse
of that socket was the only racy part. Reproduced deterministically over one raw socket (EOF on the next
request). Fix: `HttpInboundGuard` in data-prism-mcp, mounted via `HttpTransport.servlet()`, answers 400 with a
`-32602` JSON-RPC error before the SDK is reached and keeps the connection open; the content-free mapper no
longer serialises a stack trace in any SDK error body. No SDK upgrade. The raw-socket test fails on the base and
passes after. Direct `transportProvider()` mounts still get the 500 and the close (accepted residual). The 404
variant at `refused:152` stays `open` and is not explained by this."

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

First amendment (2026-11-12, replaced after PR #264 review round 1): approach "upgrade the SDK if a release
fixes it, otherwise guard behind `HttpTransport.transportProvider()` with every mount point unchanged, or stop
and report"; Owns included `pom.xml` (`mcp.version` only) and made `HttpInboundGuard` conditional; the
`initialize` row allowed any 4xx; `McpTransportWiring` and other mount points were out of scope; the goal
covered "any other param-type error on the same path". Replaced because the record component and the
registration bean are typed to the SDK class, whose constructor is private (264-1), and the goal named no
case beyond E (264-2).
