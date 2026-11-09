# 304 — Keep client content out of the SDK's inbound parse-error logs

**Repo:** `.`
**Depends on:** none (302 merged in PR #227; SDK 2.1.0 since PR #228)
**Decisions:** owner, 2026-11-09: fix, not a residual (CLAUDE.md rules 2 and 3). Design below is decided here.
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java *(the two transport constructions only)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/StdioInboundGuard.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapper.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/StdioInboundGuardTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServerTest.java *(insertions only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioParseErrorLeakTest.java *(new)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java *(new)*

## Goal
A malformed inbound message must put no client-supplied content in any log line or stack trace, on stdio or streamable HTTP, at any level, and must be refused without ending the session.

## What the SDK does (mcp-core 2.1.0, read from the bytecode)
| Path | Failure | Logged | Then |
|---|---|---|---|
| stdio | any exception from `McpSchema.deserializeJsonRpcMessage` | ERROR `Error processing inbound message` + stack (Jackson's `Unrecognized token '...'`) | **session closed**; later lines unanswered |
| both | valid JSON with no `method`, `result` or `error` | `IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + <whole line>)` | per transport |
| HTTP | `IOException` or `IllegalArgumentException` | ERROR `Failed to deserialize message: {}` with `e.getMessage()` | 400; session kept |

302 measured only malformed JSON on HTTP. Row two means **HTTP logs the whole body** for valid non-JSON-RPC input; this task fixes that too.

## Design (decided)
- **stdio: `StdioInboundGuard`** wraps stdin and stdout and is passed to `new StdioServerTransportProvider(json, in, out, limit)`. It parses each stdin line with `McpSchema.deserializeJsonRpcMessage` on the content-free mapper. A line that parses goes to the SDK unchanged; one that does not is never forwarded, and the guard answers it itself with `"id":null`: `-32700` `Parse error` if the line is not JSON, `-32600` `Invalid Request` if it is. It writes holding the stdout `OutputStream` monitor, the SDK writer's lock, so lines never interleave, then logs one constant WARN line, `stdio: refused a malformed inbound message (<code>)` (no content, exception or length). A blank line is dropped. A line over `limit` is discarded up to its newline and refused as `-32600`. The guard never buffers more than `limit`, which is set explicitly to the SDK's default.
- **HTTP and stdio: `ContentFreeMcpJsonMapper`** is a decorator over `wiring.json()` given to both transport providers, never to `McpServer.sync(..).jsonMapper`. Writes delegate unchanged, so every outbound byte still goes through the engine's mapper (architecture boundary 1). If `readValue` or `convertValue` fails, it throws the same exception type with a fixed message and no cause. If `readValue` targets `HashMap<String,Object>` and the result has none of `method`, `result` or `error`, it throws `IOException` with a fixed message, mirroring the SDK's own check, before the SDK can build its message.
- **Rejected: logger level OFF** on the two providers. It hides real faults (`Error reading from stdin`, `Error writing message`, outbound errors) and stdio would still close the session on a typo.
- **Rejected: an HTTP servlet filter.** It needs a Spring bean in `McpTransportWiring` (255's), an inventory row, and a manual step for plain-Java embedders; the mapper covers all.
- **Not audited.** A malformed line has no tool, subject or scope; audit records tool calls, and HTTP's SDK refusal is not audited either. The client sees the JSON-RPC error; stdio also logs the WARN line.

## Context
- `StdioSdkLogPinTest` (integration-tests) — the child-JVM harness to copy.
- `ToolInputHttpTest.java:200-215` — in-process Tomcat, plain-Java HTTP factory.
- `DataPrismMcpServer.java:150-160, 257-271` — the two transport constructions.
- `DataPrismMcpServerTest.java:130-148` — the shared-mapper invariant.
- docs/conventions.md#tests — leak tests need a mutation

## Acceptance
Synthetic sentinel `SENTINEL-SUBJECT-7f3a91c2`. Cases: **A** unterminated object, sentinel in a string; **B** bare alphanumeric token `SENTINELSUBJECT7f3a91c2`; **C** bare token with punctuation `SENTINEL-SUBJECT-7f3a91c2`; **D** valid JSON `{"jsonrpc":"2.0","subjectId":"<sentinel>"}`.
- [ ] AC1 Tests are written first; the Outcome names the cases that failed on the base commit (expected: B to D on stdio, D on HTTP).
- [ ] AC2 `StdioParseErrorLeakTest`, one test per case: child `ExampleApplication` with `-Dorg.slf4j.simpleLogger.defaultLogLevel=trace` gets `initialize`, the case line and `ping` id 99. Stdout has the error line (`-32700` for A to C, `-32600` for D) and the `ping` reply. Stderr has the guard's WARN line. Neither stdout nor stderr contains the sentinel, its first eight characters, or `Error processing inbound message`.
- [ ] AC3 `HttpParseErrorLeakTest`, one test per case: POST the case body to an initialised session on in-process Tomcat. Assert a 400 with a JSON-RPC error body, then a `tools/list` on the same session that succeeds. Captured slf4j-simple output (`System.err` swapped for the test) and every response body contain no sentinel. The capture is shown live by asserting it holds `Failed to deserialize message`.
- [ ] AC4 `StdioInboundGuardTest` (unit, piped streams): valid lines pass byte-for-byte in order; A to D are not forwarded and get the stated error; a blank line is dropped; an over-limit line is refused and the next valid line still passes; an error line written while another thread writes under the stdout monitor is never split.
- [ ] AC5 `DataPrismMcpServerTest` gains an assertion that each transport provider's mapper is a `ContentFreeMcpJsonMapper` delegating to `wiring.json()`, and the server mapper is still `wiring.json()` itself.
- [ ] AC6 Mutation, reported in the Outcome: removing the guard from `stdio(..)` fails AC2 B to D; passing `wiring.json()` straight to the HTTP builder fails AC3 D.
- [ ] AC7 The Outcome carries a line for 261 (CHANGELOG and migration): malformed inbound messages are refused with JSON-RPC `-32700` or `-32600`, never logged with their content, and no longer end a stdio session; not audited.
- [ ] AC8 `./mvnw -B verify` passes once, at the end.

## Checks
- `stdio-leak` test — AC1, AC2, AC6
  run: `./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest=StdioParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*StdioParseErrorLeakTest.xml`
  min-passing: 4  max-skipped: 0
- `http-leak` test — AC1, AC3, AC6
  run: `./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*HttpParseErrorLeakTest.xml`
  min-passing: 4  max-skipped: 0
- `guard-unit` test — AC4, AC5
  run: `./mvnw -B -q -pl data-prism-mcp -am test -Dtest='StdioInboundGuardTest,DataPrismMcpServerTest' -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-mcp/target/surefire-reports/TEST-*.xml`
  min-passing: 15  max-skipped: 0
- `full` command — AC8
  run: `./mvnw -B verify`
- `outcome` read — AC1, AC6, AC7
  how: the PR body's Outcome holds the base failures, both mutations and the 261 line.

## Out of scope
- `simplelogger.properties`, `McpSdkLogPin` and the WARN pin (302; unchanged).
- `McpTransportWiring`, Spring beans, `docs/configuration.md` (255 owns it), `docs/agents/stdio.md`, `CHANGELOG.md`, migration notes (261 takes the AC7 line).
- Handler-level argument errors on well-formed calls (300's `TOOL_INPUT_INVALID`).
- Upgrading or patching the SDK.
