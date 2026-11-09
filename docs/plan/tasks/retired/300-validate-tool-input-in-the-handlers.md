# 300 — Validate tool input in the handlers, so every attempt is authenticated, audited and counted

**Repo:** `.`
**Release:** 0.8.0. **Release obligation:** 0.8.0 must not ship with SDK input validation on, or with any tool outside the shared path.
**Base branch:** `origin/main` once 250, 293 and 299 have merged
**Depends on:** 250 (creates the catalog tools), 299 (edits `CatalogQueries.java`, `ListSourcesToolTest.java` and `CatalogPageLinkTest.java`), 293 (CHANGELOG order)
**Blocks:** 253 (both edit `DataPrismMcpServer.java`, and `FollowTool` must use the shared path)
**Decisions:** owner, 2026-11-05: "reshape it if needs be. Correct approach is right here instead of the cheapest option." The code and the sequence below are coordinator calls under that decision.
**Wave:** 10
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ToolInput.java *(new)*, GetEntityContextTool.java, CompareEntitySourcesTool.java, ListSourcesTool.java, DescribeSourceTool.java
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java *(the two `syncServer` builders and `Wiring` only; public factory signatures do not change)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/CatalogQueries.java *(the `UNREADABLE_PAGE` constant only)*; the test-tree CatalogPageLinkTest.java *(insertions only)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ToolInputTest.java, EveryToolValidatesInputTest.java, ToolInputStdioTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/*ToolTest.java *(assertions on refused input only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ToolInputHttpTest.java *(new)*
- CHANGELOG.md *(one `[Unreleased]` Breaking entry)*, docs/migration-0.8.md *(one section)*

## Goal
The MCP SDK validates tool input before our handlers run (`validateToolInputs` defaults to true), so a schema-invalid call gets no authentication, no audit event and no limiter count, on every tool. That breaks "One event per call, as every tool" (catalog.md:332) and "A client that ignores the schema gets the same refusal" (batch-and-bulk-follow.md:96). Turn SDK input validation off and validate in the handler, on one shared path, against the schema the tool serves.

## Context
- SDK 2.0.1 `ToolInputValidator`: treats null arguments as `{}`, and answers `Tool (<name>) input validation failed: <detail>`, which it also logs at WARN. `DefaultJsonSchemaValidator.validate` catches its own failures and returns invalid, so a validator fault cannot be told from bad input. Output validation (`StructuredOutputCallToolHandler`) is separate from this flag.
- Tools: `get_entity_context`, `compare_entity_sources` (main); `list_sources`, `describe_source` (PR #209, whose `CatalogTool` is in `ListSourcesTool.java`). `ListSourcesToolTest`, "an argument that breaks the schema is refused without echoing it", pins an empty audit through a direct handler call; tool tests bypass the SDK wrapper, which is how this was missed.
- `LinkAttemptGate.open` returns `MALFORMED` uncounted for a null or empty token, so `page: ""` or `page: 7` cannot go to the gate as sent. `LinkCodec.open` counts a token that fails to decode. `follow` will serve `additionalProperties: false`, so reserved names (`ToolCalls.rejectedArguments`) must be stripped before validating.

## Required shape
- `.validateToolInputs(false)` on both builders. No other builder change: output validation stays as it is.
- `ToolInput` (package-private) is the one path: it strips reserved and approval names, treats null arguments as `{}`, validates against the tool's served `inputSchema` with one `DefaultJsonSchemaValidator` built on the shared mapper in `Wiring`, and answers valid or invalid. It never returns, logs or audits the validator's message.
- Each tool runs: correlation MDC, caller, correlation requirement, authorisation, scope, **`ToolInput`**, admission, the call, audit. Nothing reads an argument value before `ToolInput` except the unauthenticated row below. A role without the capability gets `TOOL_NOT_PERMITTED` whether or not the input is valid.

| Call | Client sees | Audit | Counts |
|---|---|---|---|
| No authenticated caller, valid or invalid | `NO_AUTHENTICATED_CALLER` | the existing unauthenticated event; `entityType` through `entityTypes.audited` if a string, else `<unregistered>` | `MCP_DENIED` |
| Authenticated, invalid, no `page` key | `TOOL_INPUT_INVALID`, the same text for every violation, with the correlation id | one `DENY:TOOL_INPUT_INVALID`; `entityType` is `<unregistered>` (empty for `describe_source`); `rejectedArguments` kept | `MCP_DENIED`; no admission, so no approval is opened |
| Authenticated, invalid, `page` key present (any value, including null; any violation in the call) | what a forged link gets: `LINK_INVALID`, "The link is not valid.", or `LINK_ATTEMPTS_EXHAUSTED` / `LINK_CHECK_UNAVAILABLE` from the gate | one `DENY:<code>`, `link` = the gate's value | the link limiter, once |
| Validator throws, returns null, or the tool has no schema | treated as invalid, by the two rows above; never passed through | those rows, and an ERROR log naming the exception class only | those rows |
| Passes the schema, fails a check the schema cannot express (blank `entityType` or `subjectId`), or `parse` cannot convert it | `TOOL_INPUT_INVALID` | as the invalid row | `MCP_DENIED` |

- **New code:** `TOOL_INPUT_INVALID`, a tool refusal, not retryable. It names no field, keyword or value. A validator fault shares it because the two cannot be told apart (Context), so a fault on a `page` call counts; accepted, since the schemas are fixed at startup and a fault is a defect, not an operating state.
- **Invalid `page`:** the tool replaces the arguments with `ListRequest(null, null, null, CatalogQueries.UNREADABLE_PAGE)`, a constant that fails to decode, and continues down the normal path, admission included, with a constant binding. It is then a forged link in every respect, and `core.link` does not change. With no link codec, `CatalogQueries` already answers `LINK_INVALID`, uncounted.
- **Dead `parse` code:** delete what repeats the schema (`ENTITY_TYPE`, `KEY_NAMESPACE`, `optionalText`, `INVALID`, the type and length checks in `DescribeSourceTool.parse` and in `targetSources`, and the unaudited `error(...)` answers). Keep conversion, including the `pageSize` saturation, and the blank check. `parse` runs only after `ToolInput`.
- **`follow` (253):** `FollowTool` must use `ToolInput`, and a schema-invalid link must reach its gate as a counted forged link. `EveryToolValidatesInputTest` covers it with no edit.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `ToolInputHttpTest`: `DataPrismMcpServer.streamableHttp` on an embedded servlet container with a real MCP client over HTTP, with the catalog passed directly, so it needs no Spring wiring from 254. For all four tools, a schema-invalid call answers as the table says, with exactly one audit event. `ToolInputStdioTest` makes the same assertions as JSON-RPC over `StdioServerTransportProvider(mapper, in, out)` through `syncServer`. Neither calls a handler directly. A mutant restoring SDK validation dies in both; the Outcome shows it.
- [ ] Over both transports, a `list_sources` call with `page` over `max-length`, with `page: ""`, with `page: 7` and with `page: null` each gives a result identical to a forged link's apart from `correlationId`, and the failure count rises by one each; after the limit, `LINK_ATTEMPTS_EXHAUSTED`. Audit `link` = `REFUSED:LINK_MALFORMED`.
- [ ] An unauthenticated invalid call writes one event identical in shape to an unauthenticated valid call's, and no limiter count (`get_entity_context`, `list_sources`). A role without the capability gets `TOOL_NOT_PERMITTED` for valid and invalid input alike.
- [ ] `EveryToolValidatesInputTest` iterates `Wiring.specifications()` and, for each tool, sends every declared property as a JSON object: refused with `TOOL_INPUT_INVALID` or `LINK_INVALID`, one audit event. A tool added without `ToolInput` fails it.
- [ ] `ToolInputTest`: a throwing validator, a null answer and an empty schema all refuse (a mutant that passes through dies); null arguments are `{}`; a reserved name does not fail a schema with `additionalProperties: false` and is still audited.
- [ ] A sentinel in every argument, on every invalid path, appears in no result, audit record or captured log line, SDK loggers included. `TOOL_INPUT_INVALID` results for two different violations are identical apart from `correlationId`.
- [ ] Valid calls are unchanged: the existing tool tests pass with edits only to assertions on refused input. `tools/list` is unchanged; no served schema changes. An output the served schema rejects is still refused as before. `git diff` shows no pattern or length check left in `ListSourcesTool` or `DescribeSourceTool`, and no unaudited `error(...)` in any tool.
- [ ] `CHANGELOG.md` Breaking and a `docs/migration-0.8.md` section say: a schema-invalid call now returns `TOOL_INPUT_INVALID`, or the link refusal on a call with `page`, instead of `Tool (<name>) input validation failed: ...`; it is audited, and it counts toward the link limiter; a client matching the old text must change; blank `entityType` or `subjectId` no longer answers "entityType and subjectId are both required".
- [ ] `./mvnw -B verify` (full reactor) passes.

## For the scribe at /record
- Register `TOOL_INPUT_INVALID` in `contract-register.md` (254 owns rows of that file in this wave). 261 adds it to `docs/tools.md`. 253 and 261 take this task's edge: 253's brief gains the `Depends on` and an Acceptance line for `ToolInput`.

## Out of scope
- A request that is not a valid `tools/call` envelope (`arguments` not an object, an unknown tool): the SDK refuses it before any handler, and it stays unaudited.
- Output schema validation, served schema changes, and admission for invalid calls (an invalid call costs no rate limit, as with `TOOL_NOT_PERMITTED`). Spring registration of the catalog tools (254); `FollowTool` (253); `docs/tools.md` (261).

## Outcome (2026-11-06, 0.8; PR #220)

`validateToolInputs(false)` is set on both `syncServer` builders, so the MCP SDK no longer validates tool input. The new package-private `ToolInput` is the one path: it strips reserved and approval names, treats null arguments as `{}`, validates against the tool's served `inputSchema` with one `DefaultJsonSchemaValidator` on the shared mapper, and answers valid or invalid. A throw, a null answer and a tool with no schema are all invalid, and the validator's message is never returned, logged or audited. All four tools now run: correlation MDC, caller, correlation requirement, authorisation, scope, `ToolInput`, admission, call, audit. The check is acted on only after authorisation and scope, so `TOOL_NOT_PERMITTED` wins for valid and invalid input alike. The new code `TOOL_INPUT_INVALID` has one text for every violation. The 0.8.0 obligation is met.

Decisions:

- A schema-invalid call always refuses, even with a genuine token. A `page` key that breaks the schema anywhere is treated as a forged link: a string `page` reaches the gate as it is only when its own length or pattern breaks the schema (over `max-length` is `REFUSED:LINK_TOO_LONG`), because a genuine token never does. Every other `page` (schema-valid, non-string or empty) is sent as `CatalogQueries.UNREADABLE_PAGE` (`"!"`, counted, `REFUSED:LINK_MALFORMED`). A genuine token beside `pageSize: 0`, a bad filter or an unknown property alongside one is `LINK_INVALID`, one DENY event, one limiter failure, no page. A schema-valid wrong-version token beside an invalid field is audited `LINK_MALFORMED`, not `LINK_VERSION`.
- Still refused unaudited by the SDK, before any handler: a malformed `tools/call` envelope (`params` or `name` missing, `arguments` not an object) and an unknown tool name. Output-schema validation is unchanged.
- A call that passed the schema and failed conversion is `TOOL_INPUT_INVALID` and counts against no limiter. An integral double `pageSize` (`2.0`) is accepted as 2. The empty-string `page` maps to `UNREADABLE_PAGE` because the gate does not count an empty token. Unauthenticated `list_sources` records the filter in the same shape-mode form as the authenticated path.
- Dead `parse` code in the catalog tools was deleted. Public constructors and factory signatures are unchanged; the package-private tool constructors gained a validator parameter.

Tests first: `ToolInputStdioTest` (8 of 8 red on base: SDK text, no audit event), `EveryToolValidatesInputTest` (1 of 2 red), `ToolInputTest` and a `CatalogPageLinkTest` case (did not compile on base), `ToolInputHttpTest` (6 of 6 red against the mutant restoring SDK validation). Mutants: `validateToolInputs(true)` kills 8 of 8 stdio and 6 of 6 HTTP; pass-through on a throw, a null answer and an empty schema kills 3 of 8 in `ToolInputTest`. The existing refusal assertions in `DescribeSourceToolTest`, `ListSourcesToolTest` and `TargetSourcesToolTest` changed to `TOOL_INPUT_INVALID` and one DENY event; `page: 12` moved to a forged-link assertion. Full verify and Hazelcast suites were left to CI.

SDK facts checked against mcp-core 2.0.1: `validateToolInputs` defaults to true and answers `Tool (<name>) input validation failed: <detail>`, logged at WARN; the SDK logs every inbound message, arguments included, at DEBUG (`McpTransport`, `McpSchema`). That is outside this task and is task 302. Sentinel tests capture INFO and above.

Follow-ups: `EveryToolValidatesInputTest` needs an edit when `follow` lands, and 253's `FollowTool` must use `ToolInput` (the brief gained the edge). A lone unknown property does not invalidate a call. SDK ERROR lines may quote tokens, which 302 measures. 261 adds `TOOL_INPUT_INVALID` to `docs/tools.md`.
