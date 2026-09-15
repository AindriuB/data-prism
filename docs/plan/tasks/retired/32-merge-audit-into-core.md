# 32 — Merge data-prism-audit into data-prism-core

**Repo:** `.`
**Depends on:** 26
**Owns:**
- pom.xml *(the `<modules>` list and any `data-prism-audit` dependencyManagement entry only)*
- data-prism-audit/** *(deleted)*
- data-prism-core/pom.xml
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/** *(new, moved)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/** *(new, moved)*
- data-prism-orchestration/pom.xml
- data-prism-spring-boot-autoconfigure/pom.xml
- data-prism-architecture/pom.xml
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureCoverageTest.java
- docs/architecture.md *(the module table's `audit` and `core` rows and the sentences that name the audit module only)*

## Goal

`data-prism-audit` is four main classes — `AuditEvent`, `AuditSink`,
`AuditRecorder`, `Slf4jAuditSink` — in a module of its own whose only
dependency is `core` and which every serious consumer already pulls in. It
buys a boundary that nothing enforces and costs a module in every graph,
table and coverage assertion. Fold it into `data-prism-core` without moving a
single package, so no consumer's imports change.

## Context

- `data-prism-audit/src/main/java/io/github/aindriub/dataprism/audit/` — the
  four classes, plus `AuditRecorderTest` in that module's tests.
- `grep -rl data-prism-audit --include=pom.xml .` — the poms that declare it:
  the root aggregator, the module's own, `data-prism-orchestration`,
  `data-prism-spring-boot-autoconfigure` and `data-prism-architecture`. Every
  other consumer (`mcp`, `example`, `server`, `connectors-rest`,
  `quickstart-extension`) already gets it transitively and needs no pom edit.
- `data-prism-core/pom.xml` — already depends on `slf4j-api`, which is all
  `Slf4jAuditSink` needs.
- `ArchitectureTest.java:149` — `CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS` names
  `..dataprism.audit..` as a package `..dataprism.core..` must not depend on.
  Keeping the package name means this rule keeps holding and keeps meaning
  something: core proper still must not call audit. That file is **not** in
  this task's `Owns` and must not change.
- `ArchitectureCoverageTest.java:44-66` — asserts a set of modules with main
  code and each module's own dependencies; removing a module changes what it
  sees.
- `docs/architecture.md:44` — `| `audit` | `core` | ... |`, the row to remove,
  and `:49`, the planned `reidentification` row that names `audit` as a
  dependency.
- Task 26 edits `data-prism-spring-boot-autoconfigure/pom.xml`; branch from it.

## Acceptance

- [ ] `data-prism-audit/` no longer exists and the root `pom.xml` no longer
      lists it as a module.
- [ ] The four classes live under
      `data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/` with
      their package declaration **unchanged**:
      `rg -c 'package io.github.aindriub.dataprism.audit' data-prism-core/src`
      returns the moved files, and no file anywhere in the reactor changes an
      `import io.github.aindriub.dataprism.audit...` line.
- [ ] `git diff --stat` shows no `.java` file modified outside
      `data-prism-core` and `data-prism-architecture` — only moves, deletions
      and pom edits.
- [ ] `AuditRecorderTest` runs in `data-prism-core` and still passes, including
      whatever it asserts about the per-writer hash chain.
- [ ] `ArchitectureCoverageTest` passes with the module set it now actually
      sees, and its assertion still fails if a module with main code is dropped
      from the graph — state in the task report how that was checked.
- [ ] `ArchitectureTest` passes unmodified, including
      `CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS`, which still forbids
      `..dataprism.core..` from depending on `..dataprism.audit..`.
- [ ] `docs/architecture.md`'s module table has no `audit` row, the `core` row
      says it now carries the audit contract, and the planned
      `reidentification` row no longer names a module that does not exist.
- [ ] `mvn -q verify` is green for the full reactor, and the task report states
      the test total before and after — it should be unchanged.

## Out of scope

- Renaming the package to `..dataprism.core.audit..`. That churns roughly
  thirty files across eight modules for no gain and would require editing
  `ArchitectureTest.java`, which this task does not own.
- Any change to the audit classes themselves: the hash chain, the sink SPI,
  the event record. This is a module move, not a redesign.
- `data-prism-hazelcast`'s equivalent question. It is optional and conditional,
  and task 25 gave it a real caller.
- `PLAN.md` and `HISTORY.md`, and every part of `docs/architecture.md` outside
  the module table and the sentences that name the audit module.
