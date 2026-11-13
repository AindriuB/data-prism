# 322 — Flake-fix and HTTP guard follow-ups deferred to 0.9

**Repo:** `.`
**Release:** 0.9.0 (deferred at 0.8.0 planning, 2026-11-13)
**Executor:** `implementer`; the 0.9 planner splits this into tasks before pickup
**Depends on:** 318 (it owns `DataPrismMcpServer.java` and `HttpInboundGuardTest.java` first)
**Wave:** unscheduled (0.9)
**Owns:** fixed when split; expected:
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/{HttpInboundGuard,DataPrismMcpServer}.java
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/HttpInboundGuardTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/HttpParseErrorLeakTest.java
- data-prism-hazelcast/src/test/java/io/github/aindriub/dataprism/hazelcast/MemberCardTest.java
- load-test/src/**/SourceStatuses.java and its tests

## Goal
Record, with a reason each, the review follow-ups from 303, 305 and wave 14b that were judged not to block
0.8.0, so the 0.9 planner can file them without re-deriving the triage.

## Deferred items
| Item | Source | Why not 0.8.0 |
|---|---|---|
| 264-4 / 267-4: guard refuses before the SDK's 503/415/404 checks; an id-less `tools/call` gets 400, not 202 | 305 review | Only malformed requests are affected, and the answer is still a refusal (fail closed); reordering means re-implementing SDK checks. |
| 264-5: other handlers that convert params synchronously keep the 500 | 305 review | The mapper hardening already strips every stack trace; what remains is a status code and a connection close that the session survives. |
| 267-5: every POST parsed twice, peak heap about 2x near the cap | 305 review | Performance only, bounded by the 16 MiB cap. |
| 267-6: the forced-reuse test neither forces nor detects reuse | 305 review | Test strength; the raw-socket test already proves the defect deterministically. |
| 267-8: inline `java.net`, `java.io`, `Locale` names in `HttpParseErrorLeakTest` | 305 review | Style nit. |
| 267-9: multi-behaviour tests in `HttpInboundGuardTest` | 305 review | Style; 318 writes its new tests one behaviour each. |
| 267-10: `classify` returns `Object[]`, should be a `Verdict` record | 305 review | Internal refactor, no behaviour. |
| Move `GUARD_MAPPERS` inside the `HttpTransport` record | 305 review | Placement of a private field; no API or behaviour change. |
| 404 at `refused:152` on the first `initialize` | 305, `known-flakes.md` row (open) | Seen only in local full builds on a shared machine, never in CI; the `localhost` versus `127.0.0.1` lead points at the test harness, not a product path. The row stays `open`; a rerun during the cut is recorded, not blocking. |
| 303: the deterministic test never asserts the card was stale before release (`MemberCardTest` ~:1841) | 303 review | Test strength; no product code involved. |
| 303: `aSecondWrapperUsesTheSameMainDocument...` (:1901) waits on `pauseDigest() != null` | 303 review | Same weak wait 303 fixed elsewhere, but not seen to flake; if it does during the cut, it gets a `known-flakes.md` row. |
| 14b (d): load-test `SourceStatuses.java` and tests still list `SKIPPED_OVER_LIMIT` | PR #262 review | The load test is not published and product code never emits the status; no effect on the 263 soak. |

## Acceptance (for the split tasks)
- [ ] AC1 Each row above is either filed as a task with its own Owns and Checks, or dropped in PLAN.md with a
  reason.
- [ ] AC2 The 404 investigation reproduces or rules out a second listener on `::1` before any fix.

## Checks
- `split` read — AC1, AC2
  how: the 0.9 planner's PR lists each row with its new task id or the reason for dropping it.

## Out of scope
- Anything 318 or 319 owns for 0.8.0.
- Direct `transportProvider()` mounts in the four residual tests (accepted residual, 305).
