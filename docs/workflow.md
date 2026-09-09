# The workflow

A four-phase loop designed so that the expensive phase — implementation — runs
wide and in parallel, and the main session's context stays small.

```
/plan <goal>     planner   →  docs/plan/tasks/NN-slug.md  (one file per unit)
/fanout          implementer × N, one git worktree each
/verify          tester + reviewer, one pair per completed task
/record          scribe    →  PLAN.md, HISTORY.md, docs/
```

## Why it is shaped this way

The main session is the scarcest context in the run. Everything that produces
bulk output — directory walks, file reads, build logs, diffs — happens inside a
subagent, whose context is discarded when it returns. The main session only ever
sees conclusions: a list of task ids, a pass/fail, a set of review verdicts.

That is the whole token strategy. Two corollaries:

- **Never read a file in the main session that an agent could read for you.**
  If you are about to open something to answer a question, send `explorer` —
  or `architect`, if the question is about shape rather than location.
- **Never let an agent return raw content.** Every role below has a return
  contract that caps what comes back. Enforce it.
- **Never load `HISTORY.md` whole.** It is append-only and unbounded, so it
  becomes the biggest file in the workspace. `HISTORY-INDEX.md` holds one row
  per entry; scan that, then grep `HISTORY.md` for the exact heading the row
  names and read that section alone.

## Phase 1 — plan

`planner` reads the goal and the current `PLAN.md`, then writes one file per
unit of work into `docs/plan/tasks/`. It returns only the task ids and their
dependency edges.

A task file is parallel-safe when it declares:

- **Owns** — the exact file globs this task may write. Disjoint from every
  sibling task. This is what makes concurrency safe.
- **Depends on** — task ids that must land first, or `none`.
- **Acceptance** — checkable statements. Not "works well"; "GET /accounts
  returns 200 with the seeded fixture".
- **Context** — the two or three files worth reading, with line ranges.

Tasks with `depends on: none` form the first wave. Everything else waits.

## Phase 2 — fanout

For each task in the current wave, in a single message, spawn one `implementer`
with the task id. Each one:

1. runs `.claude/scripts/wt-new.sh <repo> <task-id>` to get its own worktree
   and branch,
2. reads only its task file and the files that task names,
3. implements, commits on its branch,
4. returns the branch name, the files touched and a one-line summary.

Concurrency is bounded by the worktree scripts, not by discipline: two agents
physically cannot be in the same checkout.

## Phase 3 — verify

For each returned branch, spawn `tester` and `reviewer` together — **except**
when `reviewer`'s brief includes a mutation-based non-vacuity proof, which
means it will compile and rebuild inside the worktree. A compiling reviewer and
a tester both write to that worktree's `target/` even though neither touches a
tracked file, and a contended `target/` does not fail loudly — it produces a
wrong test result indistinguishable from a real defect. Task 07 lost roughly a
day to exactly this: a `PiiLogScanTest` failure that looked like a leak was
actually two concurrent `mvn` processes corrupting one shared `target/`. See
`docs/conventions.md`, "Reviewer isolation" and "Concurrent Maven
verification", for the full rule and the incident. When that applies, either
have the reviewer clone the worktree first, or run tester and reviewer in
sequence rather than together.

`tester` runs the build and suite in that worktree and returns `PASS`/`FAIL`
plus, on failure, the first failing test name and its assertion — never the
log. `reviewer` reads the diff against the task's acceptance criteria and
returns a verdict per criterion.

A failing task goes back to phase 2 with the failure appended to its task file.
It does not get "fixed inline" — that is how the main context blows up.

## Phase 4 — record

`scribe` merges the passing branches, removes their worktrees, moves the task
files out of `tasks/`, and updates `PLAN.md` and `HISTORY.md`. It is the only
role that touches those files, which is why they never end up with conflicting
concurrent edits.

Each `HISTORY.md` entry gets its row in `HISTORY-INDEX.md` in the same commit.
That pairing is what keeps the index trustworthy enough for `planner` to rely
on instead of the file itself.

## Out of band

Two roles sit outside the loop and can be sent at any point in it:

- `/recon <question>` → `explorer`. "Where is X", "what calls Y", "does this
  already exist". Returns `path:line` citations, under 25 lines.
- `/design <question>` → `architect`. "One service or two", "what is the blast
  radius of changing this contract". Returns one recommendation with the
  evidence under it, and labels anything it did not verify as `GUESS`.

Both are read-only, and both exist so the searching happens in a context that
gets discarded. Sending one mid-phase is normal — a planner that needs to know
what already exists should have asked before splitting the work.

## When not to use the loop

A one-file change, a typo, a question. The loop costs a planner round-trip and
a worktree; below roughly three files of work it is not worth it. Do it inline
and tell `scribe` afterwards if it changed anything a future session needs.
