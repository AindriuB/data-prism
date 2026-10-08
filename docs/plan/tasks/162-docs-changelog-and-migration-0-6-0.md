# 162 — Write the 0.6.0 CHANGELOG Breaking section and the old → new FQCN migration page

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2) after 154-161 have merged into it.
**Depends on:** 154, 155, 156, 157, 158, 159, 160, 161, 163, 164, 165, 166, 167, 168, 169
**Owns:**
- CHANGELOG.md (the `[Unreleased]` section only)
- docs/migration-0.6.md (new)
- mkdocs.yml (one nav entry under "Reference")
- docs/architecture.md (the components table, the boundary/ArchitectureTest prose, and one new entry in "Decisions worth knowing")
- docs/conventions.md (the "Deliberate, reviewed exception" paragraph: class names only)
- docs-site/diagrams/README.md (lines ~275-276 only: the two source-path links to `core/DataSourceAdapter.java` and `core/PassThroughIdentityResolver.java` must name `core/spi/`; found stale after 156 by the reviewer)

## Goal
Record 0.6.0's breaking changes in one place an upgrader can act on. The
release cut itself (version bump, tag, publishing) is a later task and is
not planned here. Besides the Breaking, Fixed and Changed entries, the
`[Unreleased]` section gets an `### Added` entry for 163 (the read-only
`AuditEventListener` SPI) and one for 164 (Spring configuration metadata for
`dataprism.*` keys), plus a `### Changed` entry for 165 (the single `ci-gate`
check). Each is copied from that task's hand-back.

## Context
- The last commit body of 155, 156, 157, 158 and 159 each carries an old → new table. Cross-check them against `git diff -M --name-status 438ef802..HEAD -- '*/src/main/java/**'`, which is the authority if they disagree.
- CHANGELOG.md:1-20 — the Keep a Changelog shape and the 0.5.0 Breaking section to match.
- docs/conventions.md, "Deliberate, reviewed exception" — names `DataPrismAutoConfiguration` as the home of `dataPrismHashChainedAuditSink`; 159's hand-back says where it went.
- docs/architecture.md — the components table and the decision list format (one dated entry, the alternative rejected, what it cost).

## Acceptance
- [ ] `docs-site/diagrams/README.md` lines 275-276 link `core/spi/DataSourceAdapter.java` and `core/spi/PassThroughIdentityResolver.java`; the `grep -rnE 'dataprism/core/[A-Z]'` sweep over `docs-site/` returns nothing. (Follow-up from 156.)
- [ ] `CHANGELOG.md` `[Unreleased]` has a `### Breaking` section listing: the `core` package split (with `core.spi` called out as the extension-facing package); the `audit` package split; **the verifier command changing to `io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli`**, stated as an operator-visible change; the removal of the MCP tool, `DataPrismMcpServer`, `ContextRequest` and `SourceFanOut` overloads in favour of options records; the nested `DataPrismProperties` types becoming top-level types; and `JwtDecoderSupport`/`JwtCallerContextExtractor` moving to `spring.boot.jwt`. It states that property names, the `DataPrismAutoConfiguration` FQCN, refusal codes, the audit record format and the `dataprism.audit` logger name are unchanged. A `### Fixed` section lists 160's two fixes and a `### Changed` section 161.
- [ ] `docs/migration-0.6.md` has one table of every moved public type, old FQCN → new FQCN, and one table of removed constructors and factories → their replacement. Its row count equals the number of renamed public types under `*/src/main/java` in `git diff -M --name-status 438ef802..HEAD` (the hand-back gives both numbers).
- [ ] `mkdocs.yml` lists the page under "Reference", and the top-level nav still has exactly eight sections.
- [ ] `docs/architecture.md` describes the new `core` and `audit` layout, and has a dated 2026-10-08 decision entry for D-0.6-1 to D-0.6-5 (clean break, no deprecation cycle, because there are no external users), naming the rejected alternative (forwarding types and deprecated overloads) and its cost (every consumer recompiles and changes imports).
- [ ] `docs/conventions.md` names the class that now declares `dataPrismHashChainedAuditSink`, and nothing else in that file changes.
- [ ] `grep -rnE 'dataprism\.(core\.[A-Z]|audit\.[A-Z]|spring\.boot\.Jwt)' docs README.md` (excluding `docs/plan/`, `docs/pack.md`, `docs/design-review.md`) finds only new-location names.
- [ ] `mkdocs build --strict` exits 0 (or, if the local environment refuses it as in the 0.5.0 cut, the CI docs job on the PR is green, linked in the hand-back).
- [ ] `mvn -B verify` over the full reactor exits 0.

## Out of scope
- The 0.6.0 release cut: version bump, `server.json`, image tags, tagging and publishing. A separate task, filed when the owner calls the cut.
- Rewriting historical CHANGELOG entries or anything under `docs/plan/`.
- Any source change.
