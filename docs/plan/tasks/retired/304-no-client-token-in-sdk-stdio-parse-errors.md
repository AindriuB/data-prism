# 304 — Keep client content out of the SDK's inbound parse-error logs

**Repo:** `.`
**Depends on:** none (302 merged in PR #227; SDK 2.1.0 since PR #228)
**Decisions:** owner, 2026-11-09: fix, not a residual (CLAUDE.md rules 2 and 3). Design below is decided here.
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java *(both `syncServer` overloads and the two transport constructions; the class Javadoc paragraph about the mapper; the update to `serverUsesTheSharedMapper` in `DataPrismMcpServerTest.java` that AC5 needs)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/StdioInboundGuard.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapper.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/StdioInboundGuardTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapperTest.java *(new; added 2026-11-10 for finding 237-1, owner-approved in review round 3)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServerTest.java *(insertions only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioParseErrorLeakTest.java *(new)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java *(new)*

## Goal
A malformed inbound message must put no client-supplied content in any SDK log line at WARN or above, or in any stack trace, on stdio or streamable HTTP, and must be refused without ending the session.

## What the SDK does (mcp-core 2.1.0, read from the bytecode)
| Path | Failure | Logged | Then |
|---|---|---|---|
| stdio | any exception from `McpSchema.deserializeJsonRpcMessage` | ERROR `Error processing inbound message` + stack (Jackson's `Unrecognized token '...'`) | **session closed**; later lines unanswered |
| both | `McpSchema.deserializeJsonRpcMessage` itself | DEBUG `Received JSON message: {}` with the raw line, *before* it parses | none; plain-Java embedders have no WARN pin (302's pin is `McpSdkLogPin`, Spring path only), so DEBUG is not safely off |
| server | `convertValue` of request params on the server's own mapper (`tools/call` arguments, `initialize` capabilities) | WARN with a stack trace that can quote client content on a type mismatch | error reply |
| both | valid JSON with no `method`, `result` or `error` | `IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + <whole line>)` | per transport |
| HTTP | `IOException` or `IllegalArgumentException` | ERROR `Failed to deserialize message: {}` with `e.getMessage()` | 400; session kept |

302 measured only malformed JSON on HTTP. Row two means **HTTP logs the whole body** for valid non-JSON-RPC input; this task fixes that too.

## Design (decided)
- **stdio: `StdioInboundGuard`** wraps stdin and stdout and is passed to `new StdioServerTransportProvider(json, in, out, limit)`. It frames lines exactly as the SDK's reader does (`BufferedReader.readLine`: LF, CRLF and lone CR all end a line), or else re-emits every forwarded line with LF only. It never classifies through `McpSchema.deserializeJsonRpcMessage`, which DEBUG-logs the raw line before parsing; it uses the content-free mapper directly, in three passes. Pass one, syntax: `readValue(line, Object.class)`; failure means not JSON, `-32700` `Parse error`. Pass two, shape: the value must be a JSON object with `method`, `result` or `error`; otherwise `-32600` `Invalid Request` (so `"x"` and `[1]` are `-32600`). Pass three, type (amendment 2026-11-10): the parsed tree must convert, on the content-free mapper, to the SDK's `JSONRPCRequest`, `JSONRPCNotification` or `JSONRPCResponse`, whichever its keys select; otherwise `-32600`, so a wrongly typed `method`, `result` or `error` never reaches the SDK's `convertValue`. The content-free mapper collapses every failure to one message, so the passes, not the exception, tell the codes apart. A line that passes all three goes to the SDK unchanged; one that fails is never forwarded, and the guard answers it itself with `"id":null`. It writes holding the monitor of the same `OutputStream` instance that is passed to `StdioServerTransportProvider`, the SDK writer's lock, so lines never interleave, then logs one constant WARN line, `stdio: refused a malformed inbound message (<code>)` (no content, exception or length). A blank line is dropped. A line over `limit` is discarded up to its newline and refused as `-32600`. The guard never buffers more than `limit`, which is set explicitly to the SDK's default.
- **`ContentFreeMcpJsonMapper`** is a decorator over `wiring.json()` given to both transport providers **and** to `McpServer.sync(..).jsonMapper`, so request-param conversion fails with no client content too. Writes delegate unchanged, so every outbound byte still goes through the engine's mapper (architecture boundary 1). If `readValue` or `convertValue` fails, it throws the same exception type with a fixed message and no cause. If `readValue` targets `HashMap<String,Object>` and the result has none of `method`, `result` or `error`, it throws `IOException` with a fixed message, mirroring the SDK's own check, before the SDK can build its message.
- **Rejected: logger level OFF** on the two providers. It hides real faults (`Error reading from stdin`, `Error writing message`, outbound errors) and stdio would still close the session on a typo.
- **Rejected: an HTTP servlet filter.** It needs a Spring bean in `McpTransportWiring` (255's), an inventory row, and a manual step for plain-Java embedders; the mapper covers all.
- **Server-mapper failures** surface as the SDK's own error replies, now with a fixed message and no cause, so the SDK's WARN carries no stack that quotes the client.
- **Not audited.** A malformed line has no tool, subject or scope; audit records tool calls, and HTTP's SDK refusal is not audited either. The client sees the JSON-RPC error; stdio also logs the WARN line.

## Context
- `StdioSdkLogPinTest` (integration-tests) — the child-JVM harness to copy.
- `ToolInputHttpTest.java:200-215` — in-process Tomcat, plain-Java HTTP factory.
- `DataPrismMcpServer.java:150-160, 257-271` — the two transport constructions.
- `DataPrismMcpServerTest.java:130-148` — the shared-mapper invariant.
- docs/conventions.md#tests — leak tests need a mutation

## Acceptance
Synthetic sentinel `SENTINEL-SUBJECT-7f3a91c2`. Cases: **A** unterminated object, sentinel in a string; **B** bare alphanumeric token `SENTINELSUBJECT7f3a91c2`; **C** bare token with punctuation `SENTINEL-SUBJECT-7f3a91c2`; **D** valid JSON `{"jsonrpc":"2.0","subjectId":"<sentinel>"}`; **E** (stdio and HTTP) `tools/call` whose `arguments` is a string holding the sentinel, and an `initialize` whose `capabilities` is a string holding it; **F** (stdio and HTTP, amendment 2026-11-10) a wrongly typed `method`, `result` and `error`, each carrying the sentinel (for example a `method` that is an object, an `error` that is a string).
- [ ] AC1 Tests are written first; the Outcome names the cases that failed on the base commit (expected: B to E on stdio, D and E on HTTP).
- [ ] AC2 `StdioParseErrorLeakTest`, one test per case: child `ExampleApplication` with `-Dorg.slf4j.simpleLogger.defaultLogLevel=trace` gets `initialize`, the case line and `ping` id 99. Two configurations: for A to D and F the child also gets `-Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol=debug`, so SDK DEBUG output is live; for E it keeps the `simplelogger.properties` WARN pin on `io.modelcontextprotocol`, because E lines are forwarded and the SDK's own DEBUG line for them is the named residual. Stdout has the error line (`-32700` for A to C, `-32600` for D and F) and the `ping` reply; for E the reply is the SDK's error, and the sentinel is absent. Stderr has the guard's WARN line. Neither stdout nor stderr contains the sentinel, its first eight characters, or `Error processing inbound message`.
- [ ] AC3 `HttpParseErrorLeakTest`, one test per case: POST the case body to an initialised session on in-process Tomcat. Assert a 400 with a JSON-RPC error body (E: the SDK's error reply), then a `tools/list` on the same session that succeeds. Captured slf4j-simple output (`System.err` swapped for the test) and every response body contain no sentinel. The capture is shown live by asserting it holds `Failed to deserialize message`.
- [ ] AC4 `StdioInboundGuardTest` (unit, piped streams): valid lines pass byte-for-byte in order; A to D are not forwarded and get the stated error; a blank line is dropped; an over-limit line is refused and the next valid line still passes; an error line written while another thread writes under the monitor of that same `OutputStream` instance is never split; line framing: a CR-terminated, a CRLF-terminated, and a valid line followed by a lone CR then a malformed line each behave as `readLine` would, with no malformed text forwarded; `"x"` and `[1]` get `-32600`.
- [ ] AC5 `DataPrismMcpServerTest` gains an assertion that each transport provider's mapper and the server's mapper are a `ContentFreeMcpJsonMapper` delegating to `wiring.json()`. It obtains the providers and the server from the same factories production uses, never by building providers itself, and carries a mutation case that fails when the decoration is removed. The existing shared-mapper invariant (`DataPrismMcpServerTest.java:130-148`) is updated to this: every write still goes through `wiring.json()`.
- [ ] AC6 Mutation, reported in the Outcome: removing the guard from `stdio(..)` fails AC2 B to D and F; removing the guard's pass three fails AC2 F; making the guard classify through `McpSchema.deserializeJsonRpcMessage` fails AC2 A to D and F, because their child runs with `io.modelcontextprotocol` at DEBUG (that run covers the guard's DEBUG bypass for A to D and F only, not E); passing `wiring.json()` straight to the HTTP builder fails AC3 D, and to `McpServer.sync(..).jsonMapper` fails the AC5 unit test only, not AC2 or AC3 (the SDK converts inbound messages with the transport's mapper, so no leak case reaches the server-level mapper; that decoration is defence in depth).
- [ ] AC7 The Outcome carries a line for 261 (CHANGELOG and migration): malformed inbound messages are refused with JSON-RPC `-32700` or `-32600`, never logged with their content at WARN or above, and no longer end a stdio session; not audited.
- [ ] AC8 `./mvnw -B verify` passes once, at the end.

## Checks
- `stdio-leak` test — AC1, AC2, AC6
  run: `./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest=StdioParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*StdioParseErrorLeakTest.xml`
  min-passing: 7  max-skipped: 0
- `http-leak` test — AC1, AC3, AC6
  run: `./mvnw -B -q -pl data-prism-integration-tests -am test -Dtest=HttpParseErrorLeakTest -Dsurefire.failIfNoSpecifiedTests=false`
  report: `data-prism-integration-tests/target/surefire-reports/TEST-*HttpParseErrorLeakTest.xml`
  min-passing: 7  max-skipped: 0
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
- **Residual: SDK DEBUG.** The SDK logs the raw HTTP request body at DEBUG before any mapper runs, and other SDK DEBUG lines may carry the raw message; this task does not close them (owner, 2026-11-10: narrow, not a pre-SDK HTTP fix). On the Spring path `McpSdkLogPin` holds `io.modelcontextprotocol` at WARN. Plain-Java embedders get no pin and must keep that logger at WARN, which 261's migration notes must say.
- The SDK's `roots/list_changed` WARN and ERROR lines that log client-returned roots. Named residual.
- Other SDK WARN lines that echo client-supplied `method`, `id` and `protocolVersion`. Named residual; PLAN.md carries a follow-up.
- Upgrading or patching the SDK.

## Amendment 2026-11-10 (owner, after review round 1)
- **Decision 1: a third pass in `StdioInboundGuard`.** Finding: pass two checks only that a `method`, `result` or `error` key exists, so `{"jsonrpc":"2.0","id":2,"method":{"a":1}}` or `{"jsonrpc":"2.0","id":2,"error":"x"}` passed the guard; the SDK's `convertValue` then threw, logged ERROR `Error processing inbound message` and closed the session. No content leaked, but the Goal's "without ending the session" broke. Chosen: pass three (Design) converts the tree to the SDK's message type on the content-free mapper and refuses a failure as `-32600` with the same constant WARN, not forwarded, session kept. Reason: the guard's job is that nothing the SDK cannot convert reaches it; converting with the same types the SDK uses is the only check that matches the SDK exactly. It must not call `McpSchema.deserializeJsonRpcMessage`, which DEBUG-logs the raw line. Case F is added to AC2 and AC3; HTTP already refuses F through its provider, so AC3 keeps its existing expectation for it.
- **Decision 2: SDK DEBUG live for A to D and F.** Finding: the child's `defaultLogLevel=trace` is overridden by `simplelogger.properties` (integration-tests `src/main/resources`), which pins `io.modelcontextprotocol` at WARN, so SDK DEBUG never appeared and AC6's DEBUG-bypass claim was untested. Chosen: those runs add `-Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol=debug`; E keeps the pin. Reason: A to D and F never reach the SDK, so a DEBUG line with the sentinel can only mean the guard leaked it; E is forwarded by design, and the SDK's DEBUG line for it is the residual already named in Out of scope. `simplelogger.properties` stays unchanged.
- **Remaining findings of review round 1, known to re-review:**
  - P2, implementer, fix in the next round: `DataPrismMcpServerTest.java:172-176` checks only that the delegate is a `JacksonMcpJsonMapper`; AC5 requires it to be `wiring.json()` itself (identity).
  - P3, follow-up, not this task: `StdioInboundGuard`'s hand-off queue (64 lines x `limit`) can hold about 1 GiB, against the Design's "never buffers more than `limit`".
- **Decision 3 (owner, 2026-11-10): the `McpServer.sync(..).jsonMapper` mutation is caught by AC5 alone.** Reason: it survived every integration test in the attempt-2 run (PR #237 Outcome); the SDK converts inbound messages with the transport's mapper and no leak case reaches the server-level mapper, whose decoration is defence in depth on a path no inbound message is known to reach.

## Review round 1 — findings

Reviewed HEAD 095bb6087a7814c6713c6685244776318f1a5950 (full branch, after the 2026-11-10 amendment).

- [P2] data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/StdioInboundGuard.java:152-165 — `classify` still has only two passes. The Design (Amendment 2026-11-10, Decision 1) requires a third: convert the tree to the SDK's message type on the content-free mapper. Without it, a line with a wrongly typed `method`, `result` or `error` is passed through to the SDK. The class Javadoc at line 23 still says "two passes".
  route: implementer — brings the code in line with Decision 1 as written.
  input: on stdio, `initialize`, then `{"jsonrpc":"2.0","id":2,"method":{"a":1}}`, then `{"jsonrpc":"2.0","id":99,"method":"ping"}`  →  the guard forwards line 2. The SDK's `convertValue` throws, it logs ERROR `Error processing inbound message`, and it closes the session, so ping 99 is never answered. The same happens with `"error":"x"`. This breaks the Goal's "without ending the session".
- [P2] data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/StdioParseErrorLeakTest.java:109 — the child JVM gets only `defaultLogLevel=trace`. `simplelogger.properties` still pins `io.modelcontextprotocol` at WARN, so SDK DEBUG output is never live. AC6 says that making the guard classify through `McpSchema.deserializeJsonRpcMessage` fails A to D and F. With this setup that mutation would still pass, because the raw-line DEBUG output is suppressed.
  route: implementer — Decision 2 already says how: add `-Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol=debug` for A to D and F, and keep the WARN pin for E.
  input: change `classify` to call `McpSchema.deserializeJsonRpcMessage(mapper, line)`, then run case C  →  the sentinel goes to SDK DEBUG, which is suppressed, so the test stays green.
- [P2] StdioParseErrorLeakTest.java (no case F) and data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java (last case at :107) — case F (a wrongly typed `method`, `result` and `error`, each carrying the sentinel) is missing on both transports. Nothing guards finding 1, and AC6's "removing pass three fails AC2 F" cannot be shown.
  route: implementer — AC2 and AC3, as amended.
  input: the input from finding 1 passes every existing test.
- [P2] data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServerTest.java:174 — `assertContentFreeOverSharedMapper` only checks that the delegate is a `JacksonMcpJsonMapper`. AC5 requires the delegate to be `wiring.json()` itself (the same instance). This was recorded in review round 1 as a fix for the next round, and it is still open at HEAD.
  route: implementer — recorded in the task file's amendment as an implementer fix.
  input: change `DataPrismMcpServer.stdio(..)` to wrap a freshly built `JacksonMcpJsonMapper` instead of `wiring.json()`  →  the test passes, although boundary 1's single-mapper invariant is broken.

## Review round 2 — findings

Reviewed the fix since a6cacea0, at HEAD 3cc3902719d05d9c32d2f56191e908b31e066071.

- [STYLE] data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/StdioInboundGuard.java:25-26 — The fix edited this Javadoc and introduced two problems. Line 25 is one unwrapped 179-character line, where the rest of the paragraph wraps at about 105 characters. Line 26 still says "A line that passes both goes to the SDK unchanged", but `classify` now has three passes.
  route: implementer — wording and wrapping only. Wrap line 25 like lines 22-24 and 27-31, and change "both" to "all three".
  rule: the neighbouring lines in the same Javadoc (StdioInboundGuard.java:22-24, 27-31) set the wrapping. docs/conventions.md "Code comments": a comment must not assert something that is not true.

## Attempt 1 — failed

/verify at 86fda39333be (2026-11-10): tester PASS (stdio-leak 9, http-leak 9, guard-unit 29, full `./mvnw -B verify` exit 0); reviewer REQUEST CHANGES. The code meets every test-checked criterion; what is missing is the Outcome. No PR exists for the branch and no commit carries one, so these read checks cannot pass:
- AC1: the Outcome must name the cases that failed on the base commit (the tests-first commit 7bff206c exists; the failing cases were reported by the implementer but are not recorded).
- AC6: the mutation results must be in the Outcome, including the two added by the 2026-11-10 amendment (remove pass three → F fails; classify through `McpSchema.deserializeJsonRpcMessage` with SDK DEBUG live → A–D and F fail), which have not been run.
- AC7: the line for 261.
Next attempt: run the AC6 mutations (revert each), then open the PR against main with the Outcome in its body. No code change is needed unless a mutation survives.
Reviewer suggestions (not blocking): AC5 proves the delegates are one instance, not that it is `wiring.json()`; the two HTTP E cases assert no status code and no live capture.

## Review round 3 — findings (external review of PR #237 at 20b0b050)

Owner decision 2026-11-10: fix the three Mediums; the Lows (237-4 to 237-9) and Nits (237-10, 237-11) are follow-ups.

- [Medium] 237-1 — data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ContentFreeMcpJsonMapper.java:48 — the decorator has no unit test of its own. Nothing pins that the rewritten exception carries no cause, that the `byte[]` overloads are decorated, or that reads of a non-map TypeRef pass through untouched. route: implementer. Fix: add `ContentFreeMcpJsonMapperTest` asserting each.
- [Medium] 237-2 — data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServerTest.java:143 — AC5's test proves the server and the providers share one mapper, not that it is `wiring.json()`. route: implementer. Fix: also assert that the decorated delegate's underlying mapper is the same instance as a tool handler's mapper (`mapperOf(..)`, as at :123-127), which comes from the wiring.
- [Medium] 237-3 — data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/StdioInboundGuard.java:129 — an `IOException` is swallowed, so a stdin or stdout fault ends the session as a clean EOF with nothing logged. route: implementer. Fix: log one content-free line (WARN or ERROR) naming only the exception class, never its message, before finishing; add a unit test with a failing input stream that asserts the line and that no message text appears.

## Outcome

Landed on `main` as PR #237 (af2fb46c), verified PASS and APPROVE at 159ec3fbc30a5e888eb949c2c2124811794fe797, the merged head, which already included `main`. The Outcome below is the one in PR #237's body.

**Owner decisions of 2026-11-10.**
1. A third pass in `StdioInboundGuard`: a line with a wrongly typed `method`, `result` or `error` is converted to the SDK's message type on the content-free mapper and refused as `-32600`, so it no longer ends the stdio session.
2. SDK DEBUG is live for cases A to D and F (`-Dorg.slf4j.simpleLogger.log.io.modelcontextprotocol=debug`); E keeps the WARN pin, because E lines are forwarded and the SDK's DEBUG line for them is the named residual.
3. AC6 mutation 5 (`wiring.json()` straight to `McpServer.sync(..).jsonMapper`) is covered by the AC5 unit test alone; it survives every integration test.

**Files.** `ContentFreeMcpJsonMapperTest.java` was added to Owns in review round 3 (finding 237-1, owner-approved). Round 3 also fixed 237-2 (the AC5 test now checks the delegate is the wiring's mapper) and 237-3 (a stdin or stdout fault is logged as one content-free line naming only the exception class). The Lows and Nits (237-4 to 237-11) are follow-ups in `PLAN.md`.

**AC1, base commit 7bff206c (tests only, no fix; case F not yet written there).**
- Stdio failed: A, B, C, D and E `initialize` capabilities (6 ran, 5 failed). E `tools/call` passed.
- HTTP failed: D and E `initialize` capabilities (6 ran, 2 failed).
- This differs from the expected "B to E stdio, D and E HTTP": stdio A also failed, and E `tools/call` passed on the base.

**AC6 mutations, run at head, each reverted afterwards.**
1. Guard removed from `stdio(..)`: all 9 stdio tests fail (no response to id 1, 60 s timeout). This breaks `initialize` (the guard's reader probably kept consuming stdin) rather than demonstrating a leak, so it shows the guard is wired in, not that the sentinel is caught.
2. Pass three removed: the 3 F tests fail (no `ping` 99 reply, session closed); the other 6 pass.
3. `classify` through `McpSchema.deserializeJsonRpcMessage` with SDK DEBUG live: 7 of 9 fail (A to D, F x3). E passes by design (WARN pin).
4. `wiring.json()` straight to the HTTP builder: 4 of 9 HTTP tests fail (D, E initialize, 2 F).
5. `wiring.json()` straight to `McpServer.sync(..).jsonMapper`: survives all 9 stdio and 9 HTTP tests. The SDK converts inbound messages with the transport's mapper, and no leak case reaches the server-level mapper. Only the AC5 unit test pins its decoration (decision 3).

**AC7 line for 261 (CHANGELOG and migration):** malformed inbound messages are refused with JSON-RPC `-32700` or `-32600`, never logged with their content at WARN or above, and no longer end a stdio session; not audited. The migration notes must also say that plain-Java embedders get no `McpSdkLogPin` and must keep `io.modelcontextprotocol` at WARN (the named SDK DEBUG residual).

**Verification at 86fda393 (attempt 1):** stdio-leak 9, http-leak 9, guard-unit 29, `./mvnw -B verify` exit 0.

**Noted, not defects.** The AC5 test builds its own `StdioServerTransportProvider` to get a server from `syncServer`, where AC5 says "never by building providers itself". The Javadoc at `DataPrismMcpServer.java:76` says the limit is "in bytes: the SDK's own default", but the SDK counts UTF-16 chars; the guard is the stricter of the two and only the wording is wrong.
