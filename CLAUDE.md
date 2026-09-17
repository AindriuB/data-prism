# data-prism

<!-- HARD CAP: 50 lines. This file is a router, not a manual.
     If you are adding detail here, it belongs in docs/ instead. -->

A privacy layer between MCP/LLM clients and enterprise APIs. It gives one entity
the same synthetic identity everywhere inside a privacy scope, while making the
underlying source-data inconsistencies *more* visible, never less. Java 21,
Spring Boot 3, Maven multi-module. The core carries no business domain.

## Read on demand, not up front

| Need | File |
|---|---|
| How we work — the loop, roles, parallelism | `docs/workflow.md` |
| Code style, naming, commits, privacy rules a diff must satisfy | `docs/conventions.md` |
| Module map, dependency direction, boundaries, decisions | `docs/architecture.md` |
| What is open, in priority order | `docs/plan/PLAN.md` |
| What was built — scan, never open `HISTORY.md` whole | `docs/plan/HISTORY-INDEX.md` |
| One task's full contract | `docs/plan/tasks/<id>.md` |
| The original specification | `docs/pack.md` |
| Amendments to it — **these win** where the two disagree | `docs/design-review.md` |
| Slice order, sizing, decisions that block Slice 0 | `docs/development-plan.md` |

Load exactly one when the task needs it. `docs/pack.md` is 3,181 lines: grep the
numbered section you need and read that alone.

## Roles

Delegate rather than working inline; each agent returns a conclusion, not a
transcript. Definitions in `~/.claude/agents/`, contracts in `docs/workflow.md`.

## The loop

`/plan <goal>` → `/fanout` → `/verify` → `/record`.
Out of band, any time: `/recon <question>`, `/design <question>`.

## Rules that hold everywhere

The workspace rules in `../CLAUDE.md` apply here too and are not repeated.
These three are specific to this repository.

1. **No path from a source adapter to the MCP layer may bypass the privacy
   engine.** If a change makes one possible, it is wrong regardless of tests.
2. **Fail closed.** Unclassified, unresolvable or erroring means redact or
   refuse, never pass through.
3. Real personal data, credentials and tokens never enter this repository —
   not in fixtures, not in tests, not in a log pasted into a doc.
