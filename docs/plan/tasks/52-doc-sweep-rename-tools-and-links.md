# 52 — Reconcile the shipped docs: module rename, two tools, and the guide links

**Executor:** `scribe` (per `CLAUDE.md` rule 4, only `scribe` writes docs).
**Repo:** `.` (`/Users/Andrew/workspace/data-prism`)
**Depends on:** 49, 50, 51 — all three, and all for the same reason: this task
asserts things that are only true once they have merged. 49 makes the new
module name true; 50 and 51 make the link targets exist.
**Owns:**
- `README.md`
- `docs/architecture.md`
- `docs/quickstart.md`
- `docs/configuration.md`
- `docs/agents/README.md`
- `docs/agents/stdio.md`
- `CHANGELOG.md`

## Goal

Four documents contradict the shipped software, and six more will contradict
it the moment task 49 merges. This is the single owner of every cross-cutting
documentation file, which is why four separate concerns are one task: they
all edit `README.md` and `docs/architecture.md`, so splitting them would
produce a guaranteed collision rather than parallelism.

Do all four:

1. **The module rename.** Task 49 renames `data-prism-example` to
   `data-prism-integration-tests` in the build and the code and deliberately
   touches no document. Carry the rename into every document, and while you
   are in each passage, correct the *characterisation* too — the module is
   the reactor's cross-module integration test suite, not a demo. It hosts 11
   test classes with no duplicate anywhere, including `PiiLogScanTest`, which
   `docs/architecture.md` itself names as the sole enforcement of privacy
   rule 7. The old name invited deletion; do not carry the old framing
   forward under a new name.
2. **Four stale "only one tool" claims**, all written before 0.2.0 shipped
   the second tool.
3. **One drift the architect found independently:**
   `docs/architecture.md` says `ArchitectureTest` lives in the example
   module. It does not; it lives in `data-prism-architecture`.
4. **Link the two new guides** — `docs/extending.md` (task 50) and
   `docs/tools.md` (task 51) — from the places a reader is actually standing
   when they need them.

## Context

Line numbers below were derived on 2026-09-17 against `e5d1c22` and the
coordinating brief's own figures were already one to four lines stale in
places. **Re-derive every one with `rg -n --hidden` before editing.** A
mismatch means the file moved, not that the edit should be made blindly.

Stale one-tool claims:

- `README.md:20` — "Only one MCP tool exists today, `get_entity_context` —
  the other three named in the design review are not yet built."
- `docs/architecture.md:91-92` — "only `get_entity_context` exists today;
  `compare_entity_sources`, `search_entity_data` and `describe_entity_model`
  are designed (§B5) but not built".
- `docs/quickstart.md:113` — "The one tool the platform ships today,
  `get_entity_context`, comes back with its input schema".
- `docs/agents/README.md:33` — "one tool (`get_entity_context`) with a
  two-field input schema".

The drift:

- `docs/architecture.md:176-181` — "`ArchitectureTest` (in `example`, the
  only module that sees the whole graph)". It is at
  `data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java`.
  The claim in the same sentence that it is the only module seeing the whole
  graph is *true of `data-prism-architecture`* — re-point it, do not delete
  it. Task 23 moved these rules into their own scanning module; the document
  never caught up.
- `docs/architecture.md:52` — the `example` row of the module table, and its
  description "Fixture-only demo application, three stub sources...".
- `docs/architecture.md:167-174` — boundary 7 and boundary 8 both reference
  `example/`.

Rename references in files this task owns:

- `README.md:174`; `docs/configuration.md:69`; `docs/agents/README.md:7,43`;
  `docs/agents/stdio.md:3,32,79,112`.
- `docs/agents/stdio.md:79` carries a runnable command,
  `mvn -pl data-prism-example -am compile dependency:build-classpath`.

Where the guide links belong (judgement, not a list to obey blindly):
`README.md`'s extension passage at `:160-176` is where a reader meets
`DataSourceAdapter` for the first time; `docs/quickstart.md:113` is where a
reader first sees `tools/list`; `docs/agents/README.md`'s table at `:7` is the
index every agent-connection reader starts from.

Not owned and never rewritten: `docs/pack.md`, `docs/plan/HISTORY.md`,
`docs/plan/tasks/retired/**`. They are historical records.

## Acceptance

- [ ] `rg --hidden -n 'data-prism-example'` over the whole repository returns
      matches only in `docs/pack.md`, `docs/plan/HISTORY.md`,
      `docs/plan/tasks/retired/**` and `CHANGELOG.md`'s pre-existing
      entries — all historical — and nowhere else. (`--hidden` is mandatory:
      task 45 found every prior sweep in this repository had been silently
      skipping `.github/`.)
- [ ] Every passage that named the module now names it
      `data-prism-integration-tests` **and** describes it as the reactor's
      cross-module integration test suite. Checkable: the `example` row of
      `docs/architecture.md`'s module table no longer contains the word
      "demo".
- [ ] `docs/agents/stdio.md`'s `mvn -pl ... dependency:build-classpath`
      command is **actually run** after editing and reported as succeeding.
      A wrong module name here fails at the reader's shell.
- [ ] All four one-tool claims are corrected, and the tool names in the
      replacement prose are taken from a real `tools/list` response captured
      against a running server — not from this task file, not from
      `Capability.java`. Show the command and the response.
- [ ] `docs/quickstart.md`'s `tools/list` passage describes what the captured
      response actually contains, including both tools' input schemas.
- [ ] `docs/architecture.md` names `data-prism-architecture` as
      `ArchitectureTest`'s home, verified with `rg -l 'class ArchitectureTest'`,
      and the surrounding claims about what it enforces are re-checked against
      that file rather than carried over.
- [ ] `README.md`, `docs/quickstart.md` and `docs/agents/README.md` each link
      to `docs/extending.md` and/or `docs/tools.md` from the passage where the
      reader needs it, with one sentence saying what the guide is for.
- [ ] Every relative link this task adds resolves to a file that exists on the
      branch. Checkable: extract each link target and `test -f` it; report the
      loop you ran.
- [ ] `docs/configuration.md` gains no new property rows and loses none: the
      only change permitted to it is the module name at `:69`. It remains the
      single configuration reference — tasks 50 and 51 link to it precisely
      so it is not duplicated, and this task must not start duplicating it
      either.
- [ ] `CHANGELOG.md` gains one unreleased entry covering the rename and the
      documentation reconciliation, written from the merged diffs of 49, 50
      and 51 rather than from this task file. Task 45's precedent: check the
      entry claim by claim against the actual diffs before writing it.

## Out of scope

- Any file outside the `Owns` list. In particular: `docs/extending.md` (task
  50) and `docs/tools.md` (task 51) are finished and merged before this task
  starts — link to them, do not edit them. If one is wrong, report it; do not
  fix it here.
- The build, the code, and the module directory. Task 49 owns all of that and
  merges first. If the rename is incomplete on `main` when you start, stop
  and report rather than finishing someone else's task in a doc file.
- `docs/pack.md` and `docs/design-review.md`. `pack.md` is the original
  specification and `design-review.md` is the amendment record; both are
  historical and both are deliberately allowed to disagree with the shipped
  software. Do not "fix" either.
- `search_entity_data` and `describe_entity_model`. The corrected one-tool
  sentences should say what is shipped and what is not; they should not
  acquire a roadmap.
- The Java package `io.github.aindriub.dataprism.example`, which task 49
  deliberately left unchanged. Documents that cite a fully-qualified class
  name are correct as they stand — do not "correct" a package to match the
  new module name.
