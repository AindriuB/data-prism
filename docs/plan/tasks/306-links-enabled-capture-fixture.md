# 306 — A links-enabled capture fixture, and captured `follow` and `_links` examples

**Repo:** `.`
**Release:** 0.9.0 (decided at 0.8.0 planning, 2026-11-13: not in 0.8.0. It is a docs-only gap that `docs/tools.md` already states honestly; no behaviour depends on it; and its Owns is still unfixed and needs an implementer and a scribe, so it is not cheap)
**Executor:** `implementer` for the fixture, then `scribe` for the captures
**Depends on:** 255 (links and `follow` wired), 261 (the tool reference that carries the uncaptured shapes)
**Wave:** unscheduled (filed 2026-11-10 at /record for 260 and 261)
**Owns:** to be fixed when the brief is finalised; expected `data-prism-integration-tests` launcher or fixture configuration, and the `follow` and `_links` examples in `docs/tools.md`.

## Goal
Task 261 captured every tool example from the real launcher except `follow` and `_links`: the fixture does not enable links and does not register `follow`, and no shipped entry point enables them against a fixture (owner amendment 2026-11-10, 239-1). `docs/tools.md` says those shapes come from the served schemas and the code. Add a fixture that enables links with a test key, run the launcher the way 261's Outcome describes (PR #239: the `ExampleApplication` main class with JSON-RPC lines on stdin), and replace the derived `follow` and `_links` shapes in `docs/tools.md` with captured output.

## Context
- PR #239 Outcome, "Capture command" and "Not captured, deferred".
- The 261 review round 3 finding 239-1 and amendment 2026-11-10 in `tasks/retired/261-document-agent-led-surface-operations-and-shipped-codes.md`.
- Task 260's `ToolsListGoldenTest` builds a links-enabled server and pins the `follow` schema; reuse its configuration, not a new one.

## Acceptance
- [ ] A fixture or launcher configuration enables links with a test-only key and registers `follow`; it cannot be selected in a production profile and contains no real data or secret.
- [ ] Captured, from that launcher: a `_links` block on a `get_entity_context` result, a `follow` call with `items`, a truncated `follow` (`itemsTruncated`), and `LINK_INVALID`.
- [ ] `docs/tools.md` carries those captures and the "not captured" note is removed; `DP_REQUIRE_NO_STUBS=true mkdocs build --strict` and the `docs-site/hooks/check_*.py` checks pass.
- [ ] The capture command is recorded in the PR body.

## Out of scope
- Changing the shape of any tool result.
