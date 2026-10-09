# 181 — Move the architecture test fixtures into package-matching directories; sweep `docs/` and README.md for stale counts

**Repo:** `.`
**Depends on:** none (wave 12)
**Owns:**
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/AuditDependsOnMcpFixture.java → data-prism-architecture/src/test/java/io/github/aindriub/dataprism/audit/fixture/AuditDependsOnMcpFixture.java
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/OversightDependsOnMcpFixture.java → data-prism-architecture/src/test/java/io/github/aindriub/dataprism/oversight/fixture/OversightDependsOnMcpFixture.java
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/{BuildOnlyMapperFixture,BuilderMapperFixture,ConstructorMapperFixture,ContextLookupObtainedMapperFixture,InjectedObtainedMapperFixture,JsonFactoryFixture,ProviderInjectedObtainedMapperFixture,PublicMapperFixture,RebuildMapperFixture,SharedObtainedMapperFixture,YamlReaderWritesFixture}.java → data-prism-architecture/src/test/java/io/github/aindriub/dataprism/mapper/fixture/
- README.md
- docs/** except docs/tools.md, docs/migration-0.6.md (task 179) and docs/plan/** (planning files)

## Goal
Thirteen negative fixtures in `data-prism-architecture` declare packages `..dataprism.audit.fixture`, `..oversight.fixture` and `..mapper.fixture`, but sit in the `architecture` directory (the 154 follow-up). Move them so that each directory matches its package declaration. Packages, class names and contents stay unchanged. Separately, re-check every counted claim in user-facing docs against the code before the release, as the 162 follow-up asks (162 found six YAML readers where the docs said five).

## Context
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java:25,38: imports the audit and oversight fixtures by their packages. These are unchanged by the move.
- ArchitectureTest.java:106-117: the designated-mapper allowlist, which has three classes.
- ModuleGraph.java:90-100: scans `src/main/java` only, so moving test files cannot change its result.
- docs/plan/tasks/retired/154-archunit-audit-oversight-outer-layers.md: Outcome.
- Count claims found on 2026-10-09. Verify each, and do not assume this list is complete:
  - docs/architecture.md:154 "three designated classes" (ArchitectureTest.java:108-111)
  - docs/architecture.md:162 "the two YAML readers" (`designatedYamlReadersDoNotWrite`)
  - docs/configuration.md:62 "Six readers"
  - docs/configuration.md:71 "seven bundled vocabularies"
  - docs/audit.md:224 "Five forms"
  - docs/audit.md:666 "five seconds"
  - docs/developer-guide/write-an-adapter.md:9 "seven steps"
  - docs/conventions.md:35 "designated classes"
- Task 179 checks the counts in docs/migration-0.6.md. Do not edit that file.

## Acceptance
Fixtures:
- [ ] The mismatch check below prints exactly one line, for `examples/json-sources/NestedCatalogueWalkthrough.java`:
  `git ls-files '*.java' | while read f; do p=$(grep -m1 '^package ' "$f" | sed 's/package //;s/;//;s/\./\//g'); case "$(dirname "$f")" in *"/$p") ;; *) echo "$f";; esac; done`
- [ ] All 13 moves are pure renames: `git diff --cached -M --summary` (before commit), or `git show -M --summary HEAD` (after commit), lists 13 `rename ... (100%)` lines and no content change to those files.
- [ ] `mvn -q -pl data-prism-architecture -am verify` passes. The number of tests run in `ArchitectureTest` is the same before and after the move. Quote both numbers in the hand-back.

Docs:
- [ ] `grep -rniE "(five|5) (yaml )?readers" docs README.md --exclude-dir=plan` prints nothing.
- [ ] For every match of `grep -rniE "\b(two|three|four|five|six|seven|eight|nine|ten|[0-9]+) (yaml readers|readers|designated classes|@Bean|bean methods|beans|preflights|configuration classes|vocabularies|forms|steps)\b" docs README.md --exclude-dir=plan` outside docs/pack.md, docs/design-review.md, docs/development-plan.md, docs/tools.md and docs/migration-0.6.md, the hand-back lists `file:line → fixed` or `file:line → confirmed against <source file:line>`.
- [ ] Every corrected count has its source-of-truth `file:line` in the hand-back. No count is changed without one.
- [ ] `mkdocs build --strict` passes if mkdocs is installed. If it is not installed, say so in the hand-back.

## Out of scope
- docs/pack.md, docs/design-review.md and docs/development-plan.md. These are historical specification documents, and counts in them are not corrected.
- Counts in Javadoc or in code comments, and CHANGELOG.md (owned by 179). Report any stale count you find there.
- `examples/json-sources/NestedCatalogueWalkthrough.java`. It is a standalone script, and its directory is intentionally not a package path.
- Renaming fixture packages or classes, or changing any ArchUnit rule.
- Version bump, tag, release workflow and publishing (paused by the owner).
