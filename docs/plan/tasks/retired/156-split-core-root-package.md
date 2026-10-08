# 156 — Split the `core` root package into `spi`, `model`, `engine`, `refusal`, `limits` and `metrics` (pure move)

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2).
**Depends on:** 154, 155, 160
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/*.java (moved out; the root is left empty)
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/{spi,model,engine,refusal,limits,metrics}/** (new)
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/*.java and the matching new test subpackages
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/{policy,correlation,descriptor}/*.java (import lines only)
- data-prism-*/src/**/*.java in every other module (import lines and fully qualified name strings only)
- examples/**/*.java (import lines only; not compiled by the reactor)
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java
- docs/**/*.md except docs/plan/**, docs/pack.md and docs/design-review.md (fully qualified names and import lines in code samples only; plus the `core` row of the component table in docs/architecture.md)

## Goal
Owner decision D-0.6-1, a clean break with no forwarding types. The `core`
root holds 34 public types that mix the extension SPI, value model, scrubbing
engine, refusal vocabulary, request limits and metrics. Move them into named
subpackages so the extension-facing SPI is one package, and lock the layout
with ArchUnit. No behaviour, signature, string literal or visibility changes:
this is a move.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/ — the 34 files.
- Planner's proposed grouping, a GUESS from file names and an import scan only; the first acceptance step replaces it with a measured one:
  - `core.spi`: DataSourceAdapter, IdentityResolver, PassThroughIdentityResolver, DataRequest, SourceCallContext, SecretKeyProvider, SyntheticValueSource, ValueTokenSource, EntityCorrelationService, FieldMetadataResolver, ScrubbingEngine
  - `core.model`: FieldMetadata, ConsistencyFinding, SourceProvenance, InvestigationContext, PrivacyContext, PrivacyScopeType, PseudonymisationVersion, ScrubResult, Capability
  - `core.engine`: JsonTreeScrubbingEngine, DefaultFieldMetadataResolver, SourceTree, SourceValues, Text, StrictYaml
  - `core.refusal`: PrivacyRefusedException, RefusalCodes, RefusalPaths
  - `core.limits`: RequestLimits, ScopeBudget, InMemoryScopeBudget
  - `core.metrics`: Metric, PrivacyMetrics
- At base there is already a cycle: the root imports `core.policy.{EffectivePrivacyPolicy, Generalizer, PrivacyPolicyResolver}` and `core.policy` imports `core.{FieldMetadata, PrivacyContext, PrivacyRefusedException, StrictYaml}`. Placement decides whether the cycle survives.
- ArchitectureTest.java:73-125 names `io.github.aindriub.dataprism.core.SourceTree` as a string; ArchitectureTest.java:135-145 is the outer-layer rule task 154 widened.
- Known string references outside Java: `docs/developer-guide/custom-identity-resolver.md:280` (`io.github.aindriub.dataprism.core.IdentityResolver`). Grep for more; do not trust this list.
- No other module declares a class in package `io.github.aindriub.dataprism.core` (checked at 438ef802), so there is no split package to resolve.

## Acceptance
- [ ] First step, before any move: a class-level dependency scan of the `core` root at base (`jdeps -verbose:class` on `data-prism-core/target/classes`, or an ArchUnit dump) and the final grouping derived from it. Both go in the first commit's body: the scan as a table of root class to the root and `core.*` classes it depends on, and the grouping with one line per class saying why it sits where it does. The grouping may differ from the planner's guess; it must not add a package outside the six named.
- [ ] `data-prism-core/src/main/java/io/github/aindriub/dataprism/core/` contains no `.java` file directly; every former root type is in exactly one of the six subpackages, with its simple name unchanged.
- [ ] Pure move: `git diff -M --stat 438ef802..HEAD -- data-prism-core/src/main` shows every former root file as a rename, and `git diff -M` for each shows only `package` and `import` line changes. No change of a modifier, signature, body or string literal. The hand-back states the similarity index of the lowest-scoring rename.
- [ ] Refusal codes unchanged: the sorted list of string constants in `RefusalCodes` and of every string literal matching `"[A-Z][A-Z0-9_]+"` used as a refusal code in `data-prism-core/src/main` is identical at base and at HEAD (the hand-back gives the command and both counts).
- [ ] ArchUnit locks the layout, in `ArchitectureTest`:
  - no class resides directly in `io.github.aindriub.dataprism.core` (exact package, not `..core..`), proven non-vacuous by a temporary mutation that puts one class back in the root, reported in the hand-back;
  - `core.spi` does not depend on `core.engine`;
  - `slices().matching("..dataprism.core.(*)..").should().beFreeOfCycles()` passes, covering the six new packages and `policy`, `correlation`, `descriptor`. If no placement makes the slices acyclic without changing code, stop and report the remaining cycle with the classes on it; do not change code to break it in this task.
  - the `SourceTree` FQCN string in `onlyDesignatedClassesCreateMappers` and `designatedYamlReadersDoNotWrite` names the new location, and both tests still pass.
- [ ] String sweep: `grep -rnE 'dataprism\.core\.[A-Z]' --include='*' .` excluding `target/`, `.claude/`, `site/`, `logs/`, `docs/plan/`, `docs/pack.md`, `docs/design-review.md` and `CHANGELOG.md` returns no line naming a root-level core type. Historical entries in `CHANGELOG.md` and `docs/plan/` are not rewritten.
- [ ] `docs/architecture.md` component table: the `core` row says the SPI interfaces live in `core.spi`.
- [ ] The commit body (last commit) carries the old FQCN → new FQCN table for every moved type; task 162 builds the migration page from it.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- `audit` and `oversight` (task 157; `oversight` stays as it is).
- `core.policy`, `core.correlation`, `core.descriptor`: they keep their names and contents.
- Narrowing visibility. Everything that is public stays public; a follow-up can tighten it once the layout settles.
- Forwarding or deprecated types at the old FQCNs (D-0.6-1 chose a clean break).
- `CHANGELOG.md` and the migration page (task 162).

## Outcome (2026-10-08, wave 2)
Merged (merge commit on `release/0.6.0-moves`, task branch head 5a5d345d). The 34 root types moved into `spi`, `model`, `engine`, `refusal`, `limits` and `metrics` as a pure move; ArchUnit locks the empty core root, `spi` not depending on `engine`, and acyclic `core.*` slices. Tester PASS on JDK 21 (full reactor, 1396 tests, 0 failures; JDK 25 not available locally, CI covers it); reviewer APPROVE. Follow-ups are in PLAN.md (ar), (as), (at).
