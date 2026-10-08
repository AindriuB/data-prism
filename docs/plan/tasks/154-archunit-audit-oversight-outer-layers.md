# 154 — Bring `audit` and `oversight` under the core outer-layer ArchUnit rule

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2).
**Depends on:** none
**Owns:**
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/*Fixture*.java (new fixture files only)
- docs/architecture.md (boundary 8 paragraph and the `ArchitectureTest` paragraph after the boundary list, only)

## Goal
Owner decision D-0.6-2. `audit` and `oversight` live in `data-prism-core` but
sit outside `coreDoesNotDependOnOuterLayers`, which matches only
`..dataprism.core..`. Extend that rule so the two packages are held to the
same inward-only direction before the 0.6.0 package moves start, so the moves
are made under the guard rather than before it.

## Context
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java:135-145 — the current rule.
- ArchitectureTest.java:337-375 — `noToolSideClassDependsOnReidentification` and `subjectForRuleCatchesMethodReferences`, the existing pattern for a non-vacuity check against a fixture.
- Source scan at 438ef802 (planner, imports only, not bytecode): `audit` imports only `annotations.PrivacyAction` from the project; `oversight` imports nothing outside itself. Confirm this from bytecode; do not rely on the import scan.
- docs/architecture.md, boundary 8 — the prose that names the rule.

## Acceptance
- [ ] First step, recorded in the hand-back: the rule's target list run against `..dataprism.audit..` and `..dataprism.oversight..` as subjects at the base commit, with the violation count. If it is non-zero, the rule carries an explicit, named allow-list of the violating dependencies (class-to-class, not package-wide), each with a one-line reason in the rule's javadoc, and the hand-back lists them. A package-wide exemption is not acceptable.
- [ ] `CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS` (rename is allowed; the test method name `coreDoesNotDependOnOuterLayers` is kept) has subjects `..dataprism.core..`, `..dataprism.audit..` and `..dataprism.oversight..`, and its forbidden targets are unchanged: `mcp`, `orchestration`, `example`, `pseudonymisation`.
- [ ] A new test proves the widened rule non-vacuous: it checks the rule against a fixture class that resides in a `..dataprism.audit..` package and depends on a `..dataprism.mcp..` type, and asserts the check fails. The fixture is excluded from `CLASSES` so the main rule still passes. Same for `..dataprism.oversight..`.
- [ ] `docs/architecture.md` boundary 8 names `audit` and `oversight` as covered by the rule.
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- Widening the forbidden target list (for example adding `security`, `hazelcast`, `spring.boot`). Raise it in the hand-back if the scan suggests it.
- Any source change in `data-prism-core`. If the scan finds a violation, allow-list it; do not fix it here.
- Package moves (tasks 156, 157).
