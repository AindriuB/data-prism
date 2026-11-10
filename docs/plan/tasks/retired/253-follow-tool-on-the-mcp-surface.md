# 253 — Expose follow on the MCP surface

**Repo:** `.`
**Release:** 0.8.0 (registered only when links are enabled)
**Base branch:** `origin/main` once 250, 252 and 254 have merged
**Depends on:** 252 (`ContextOrchestrator.follow`), 250 (both edit `DataPrismMcpServer` and `ExampleApplication`), 254 (both edit the `stdio(` call in `ExampleApplication`; added 2026-11-05), 300 (shared `ToolInput` validation path; added 2026-11-05)
**Wave:** 11 (moved from 10 on 2026-11-05, behind 252 and 254)
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/FollowTool.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java *(the `follow` registration and its factory parameter only)*
- data-prism-mcp/src/main/resources/io/github/aindriub/dataprism/mcp/schema/follow.input.json *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/FollowToolTest.java, FollowToolSchemaTest.java, FollowToolRefusalTest.java *(new)*
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java *(the `DataPrismMcpServer.stdio(` call only)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/LinkArchitectureTest.java *(the rule's sanity check only; added 2026-10-23)*

## Goal
Give the agent the `follow` tool: it takes nothing but a link string, authenticates and authorises like every tool under its own `FOLLOW` capability (OD-200-5), and returns what 252 returns. When links are disabled the tool is not registered at all, so the server behaves as option (c) of the link design.

## Context
- docs/plan/design/sealed-links.md section 5, "`follow`" (input and output schemas) and "Configuration keys" (`max-length` drives the schema).
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java: the authentication, authorisation, scope, admission and audit sequence and reserved-argument stripping.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ListSourcesTool.java (250): a sibling tool's registration and schema test.
- From the external review of PR #154, round 5 (2026-10-23): `LinkArchitectureTest`'s sanity check counts `mcp` and `connectors` classes together, so it still passes if one of the two packages is not imported at all. Assert each package separately. This task is the first to add an `mcp` class near `core.link`, so it is the natural editor.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] Input schema: one required string `link`, `minLength` 1, `maxLength` equal to the configured `max-length` (generated from configuration, compared with the checked-in file for the default 1024), pattern `^[A-Za-z0-9_-]+$`, `additionalProperties: false`.
- [ ] With links disabled, `tools/list` does not contain `follow`; with links enabled it does.
- [ ] A role without `FOLLOW` gets `TOOL_NOT_PERMITTED`; admission (pause, approval, rate limit) applies under the tool name `follow`; reserved arguments are stripped and audited as for every tool.
- [ ] Every invalid link, including one longer than the schema allows sent by a client that ignores the schema, gives the identical `LINK_INVALID` result; the link string appears in no result, log line or audit record (sentinel).
- [ ] A valid link returns the 252 envelope through the shared mapper; the existing one-mapper test covers `FollowTool`.
- [ ] A denial whose audit fails answers `AUDIT_UNAVAILABLE` as 244 established.
- [ ] `LinkArchitectureTest`'s sanity check asserts a non-zero imported class count for `mcp` and for `connectors` separately; with either package left out of the import the check fails (the Outcome shows it).
- [ ] `./mvnw -B verify` (full reactor) passes.
- [ ] `FollowTool` uses the shared `ToolInput` validation path from task 300.

## Out of scope
- Spring registration and `dataprism.links.*` (255).
- `follow_bulk` (0.9).
- `docs/tools.md` (261).

## Outcome

Landed in PR #225 (merged into `main`); verified PASS (full `verify`, 4,920 tests, 0 failures) and APPROVE at 548e756e984538fbe496b020700900d191ef4efc, the PR head.

`FollowTool` (`follow`) is registered only when links are enabled, through new `linksEnabled` overloads on `DataPrismMcpServer.stdio` and `streamableHttp`; the old overloads keep it off, so Spring wiring is untouched until 255. It uses the shared `ToolInput` path. The 252 `ContextResponse` is returned as is, so it carries `entityType` and the synthetic `subject`; no output schema is served. Any `link` member that is not schema-valid gives `LINK_INVALID`; no `link` member gives `TOOL_INPUT_INVALID`. Approval binds the actual link through the keyed `ParameterFingerprinter`. `LinkFollower.Authorised#key` is never touched from mcp. `LinkArchitectureTest` now counts `mcp` and `connectors` separately.

Review rounds: reserved argument names (names only) travel in `FollowRequest.rejectedArguments` and are audited on every orchestrator path; `follow`, `list_sources` and `describe_source` joined `OVERSIGHT_TOOLS`; `FollowRequest` moved from `orchestration.links` to `orchestration`, because `KeyResolverArchitectureTest` forbids mcp importing `orchestration.links`.

Edits outside `Owns`, accepted by the owner as review-round scope: the `FollowRequest` move with `rejectedArguments`, `FollowToolAuditTest`, the three names in `OVERSIGHT_TOOLS`, `docs/configuration.md`, the 255 task note (follow must be approval-required when any link-issuing tool is, design P5), and `4cc04ea0` (a stale import in 301's `ConfiguredJsonLinksTest` after the move, found when #225 was updated from `main`). Also `EveryToolValidatesInputTest` and `DataPrismMcpServerTest`, as the notes asked.

Residual: the success path in the tool tests uses a spy orchestrator; 252's tests cover the real read, and `FollowToolAuditTest` covers a genuine link through the real orchestrator.
