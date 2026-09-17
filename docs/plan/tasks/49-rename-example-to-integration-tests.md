# 49 — Rename `data-prism-example` to `data-prism-integration-tests`

**Executor:** `implementer` (code and build only — this task touches no file
under `docs/` and not `README.md`; task 52 owns every documentation reference)
**Repo:** `.` (`/Users/Andrew/workspace/data-prism`)
**Depends on:** none
**Baseline:** measured on `e5d1c22` (`main` at planning time). `mvn -B clean
verify` over the full reactor is green at 492 tests, 0 failures, 0 errors —
the figure task 47 closed on, recorded in `docs/plan/PLAN.md`. Re-measure
before starting if `main` has moved; a rename must not change the count.

**Owns:**
- `data-prism-example/**` (the whole directory, moved — see below)
- `data-prism-integration-tests/**` (its destination)
- `pom.xml` (root — the `<modules>` entry only)
- `data-prism-architecture/**`
- `data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerPackagingIT.java`
- `data-prism-quickstart-fixtures/pom.xml`
- `data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java`
- `data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismAutoConfiguration.java` (the javadoc naming the module, nothing else)
- `examples/agent-config/stdio-fixture/run-fixture-server.sh`

## Goal

`data-prism-example` is not an example. It is this repository's integration
test suite: 11 test classes with no duplicate anywhere else in the reactor,
including `PiiLogScanTest`, which `docs/architecture.md` names as the sole
enforcement of privacy rule 7. A module called "example" invites deletion.
Rename it to `data-prism-integration-tests` so the name states what it is.

The name is **decided, not open**: `data-prism-integration-tests`, plural,
because the module's value is the suite and not any one demonstration. The
`<name>` element becomes `Data Prism :: Integration tests` and the
`<description>` is rewritten to lead with "the reactor's cross-module
integration test suite" rather than "a stub source and a runnable server".
Record in that `<description>` that the module still holds the only permitted
business-domain types, since that remains true and is what
`ArchitectureTest` keys on.

The rename is safe against published artifacts: `data-prism-example/pom.xml`
sets `skipPublishing`/`maven.deploy.skip`, so no Maven Central coordinate
exists under the old artifactId and none is abandoned by the change.

## Context

- `pom.xml:57` — the `<module>` entry.
- `data-prism-architecture/pom.xml:18` (description prose), `:45-53` (the
  hand-written `<dependency>` block with an inline `<version>`, because this
  artifact is deliberately absent from the parent's `dependencyManagement`).
- `data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureCoverageTest.java:31-69`
  — **the trap.** It derives its module list from the root `pom.xml` at
  runtime and then asserts each module with main code is a declared
  dependency here. A rename that updates the root pom but misses
  `data-prism-architecture/pom.xml` goes RED (good); a rename that updates
  both but drops the module from the root pom silently **narrows** every
  whole-graph rule in `ArchitectureTest` with nothing red to say so. The
  class's own javadoc records `data-prism-connectors-rest` once dropping off
  exactly this way.
- `data-prism-server/src/test/java/.../ServerPackagingIT.java:91-94` — asserts
  the packaged server jar contains no entry whose name contains
  `data-prism-example`. After the rename that substring can never occur, so
  the guard becomes vacuous unless it is updated. Lines `:48`, `:51`, `:411`
  and `:440` also carry the literal (comments and two planted-marker
  fixtures).
- `examples/agent-config/stdio-fixture/run-fixture-server.sh:26` —
  `module="data-prism-example"`, used by a documented agent journey. If this
  is wrong the stdio guide stops working at the reader's shell.
- `data-prism-quickstart-fixtures/pom.xml:19-20`,
  `data-prism-quickstart-extension/src/test/.../QuickstartSmokeIT.java:41,348`,
  `data-prism-spring-boot-autoconfigure/src/main/java/.../DataPrismAutoConfiguration.java:300`
  — prose references in comments and javadoc.
- `docs/conventions.md#concurrent-maven-verification` — this task will be
  verified alongside two others; use an isolated `-Dmaven.repo.local` under
  the scratchpad and do not share a `target/` with a reviewer.

Line numbers above were derived on 2026-09-17 against `e5d1c22`. Re-derive
each with `rg -n --hidden` before editing. A mismatch means the file moved,
not that the edit should be made blindly — this repository has twice had a
false factual premise reach an implementer through a task file.

## Acceptance

- [ ] The directory move is done with `git mv` so `git log --follow` on a
      moved file still reaches its pre-rename history.
- [ ] `data-prism-integration-tests/pom.xml` declares
      `<artifactId>data-prism-integration-tests</artifactId>`, `<name>Data
      Prism :: Integration tests</name>`, and a `<description>` that opens by
      calling the module the reactor's cross-module integration test suite.
- [ ] `rg --hidden -n 'data-prism-example' -g '!docs/**' -g '!README.md'`
      over the whole repository returns **no** match. (`--hidden` is
      mandatory: task 45 found that every prior version sweep in this
      repository had been silently skipping `.github/`.)
- [ ] `ServerPackagingIT`'s "no fixture runtime in the packaged jar" guard
      names the new artifactId, and is proven non-vacuous: mutate the
      assertion (or plant an entry with the new name) so it goes RED, capture
      the failure message, then revert byte-identical.
- [ ] `ArchitectureCoverageTest` still sees the renamed module, proven by
      mutation rather than by a green run alone: temporarily delete the
      `data-prism-integration-tests` `<dependency>` from
      `data-prism-architecture/pom.xml`, show the test goes RED with a message
      naming `data-prism-integration-tests`, then restore byte-identical.
      Both the green run and the red run are reported.
- [ ] `bash examples/agent-config/stdio-fixture/run-fixture-server.sh` is
      actually executed and reaches a live stdio MCP server, not merely read.
      Report the command and what it printed.
- [ ] Full reactor `mvn -B clean verify` is green at the same test count as
      the re-measured baseline (492 on `e5d1c22`), 0 failures, 0 errors.

## Out of scope

- **The Java package `io.github.aindriub.dataprism.example` does not change**,
  and neither does the class `ExampleApplication`. `ArchitectureTest` has
  seven rules keyed on the string `..dataprism.example..`, `docs/agents/`
  documents the launcher by fully-qualified class name, and `ServerPackagingIT`
  greps for `ExampleApplication`. Renaming the package is a separate,
  larger change; if you think it should happen, report it, do not do it.
- The literal `data-prism-example` used as a **configuration value** —
  `application.yaml`'s `issuer:` and `writer-id:`, and the matching constants
  in `McpHttpEndToEndTest` — stays. Those are an OAuth issuer and an audit
  writer id, not module names; changing them changes observable audit output.
  The sweep criterion above excludes them by leaving them inside the moved
  module, so state explicitly in your report that you left them and why.
- Every documentation reference: `README.md`, `docs/architecture.md`,
  `docs/configuration.md`, `docs/agents/README.md`, `docs/agents/stdio.md`,
  `docs/quickstart.md`, `CHANGELOG.md`. Task 52 owns all of them. Per
  `CLAUDE.md` rule 4, only `scribe` writes docs. `main` will briefly carry
  docs naming a module that no longer exists; that is why 52 depends on this
  task and merges after it.
- `docs/pack.md`, `docs/plan/HISTORY.md` and `docs/plan/tasks/retired/**` are
  historical records and are never rewritten.
- The four stale "only one MCP tool" claims, and the `ArchitectureTest`
  location drift in `docs/architecture.md`. Task 52.
