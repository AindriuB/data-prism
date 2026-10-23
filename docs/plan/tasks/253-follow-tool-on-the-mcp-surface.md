# 253 — Expose follow on the MCP surface

**Repo:** `.`
**Release:** 0.8.0 (registered only when links are enabled)
**Base branch:** `origin/main` once 250 and 252 have merged
**Depends on:** 252 (`ContextOrchestrator.follow`), 250 (both edit `DataPrismMcpServer` and `ExampleApplication`)
**Wave:** 10 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
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
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- Spring registration and `dataprism.links.*` (255).
- `follow_bulk` (0.9).
- `docs/tools.md` (261).
