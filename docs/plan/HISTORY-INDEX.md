# History index

One row per `## YYYY-MM-DD` entry in `HISTORY.md`, newest first. Only `scribe`
edits this file, and it writes the row in the same commit as the entry.

`HISTORY.md` grows without bound and is the single largest file a session can
accidentally load. This index exists so `planner` can answer "has this been
built before, and where do I read about it?" without opening it. Rows are taken
from `HISTORY.md`'s own headings rather than re-summarized, so the index can be
checked against its source by eye.

Read a full entry by grepping the exact string in the **Heading** column:

```
grep -n '<heading text>' docs/plan/HISTORY.md
```

Then read that section only. A stale index is worse than none — an entry with
no row is one `planner` cannot find, and will re-plan.

| Date | Task IDs | Summary | Heading (grep this exact string) |
|---|---|---|---|
| 2026-09-09 | 01, 02 | S8 wave 1: InvestigationContext/Capability/metrics SPI, mTLS to sources | `## 2026-09-09 — S8 wave 1: session types, metrics SPI, and mTLS to sources` |
| 2026-09-09 | S7 | Embedded Hazelcast: identity cache fails open, read budget fails closed, scope purge | `## 2026-09-09 — S7 embedded Hazelcast for distributed scope state` |
| 2026-09-09 | S6 | Correlation before scrubbing; findings name which sources agree, never what they hold | `## 2026-09-09 — S6 correlation and consistency findings` |
| 2026-09-09 | S5 | Virtual-thread fan-out with bulkhead, timeout, breaker; REST connectors; request limits | `## 2026-09-09 — S5 parallel connectors and request limits` |
| 2026-09-08 | S4 | Pattern detection plus the scope-aware allowlist that stops it refusing our own output | `## 2026-09-08 — S4 pattern detection with a scope-aware allowlist` |
| 2026-09-08 | S2a | Multi-key resolution so a key rotation does not invalidate running scopes | `## 2026-09-08 — S2a key rotation` |
| 2026-09-08 | S3 | Nested descent (closed a fail-open hole), class-level defaults, descriptors, full action set | `## 2026-09-08 — S3 scrubbing engine, and the nesting hole it closed` |
| 2026-09-08 | S2 | Configurable multi-locale name pools, content-pinned; Unicode canonicalisation | `## 2026-09-08 — S2 configurable multi-locale name pools` |
| 2026-09-08 | S1 | Policy profiles decide actions; class/accessor metadata; processor fails build on unclassified fields | `## 2026-09-08 — S1 policy layer and build-time fail-closed` |
| 2026-09-08 | S0 | Walking skeleton: 8 modules, MCP stdio, HMAC pseudonyms, fail-closed, 31 tests | `## 2026-09-08 — S0 walking skeleton` |
