# 162 — Write the 0.6.0 CHANGELOG Breaking section and the old → new FQCN migration page

**Repo:** .
**Base:** branch from `origin/main` (0.5.0 released at v0.5.0 / c850c3e2) after 154-161 have merged into it.
**Depends on:** 154, 155, 156, 157, 158, 159, 160, 161, 163, 164, 165, 166, 167, 168, 169, 170
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

- [ ] (Task 170, owner decisions D-166-1, D-170-1, D-170-2.) The `[Unreleased]` `### Breaking` section and `docs/migration-0.6.md` record that the five YAML readers (and `ConfiguredJsonSources`) now refuse, at startup, a duplicate key (`DUPLICATE_CONFIG_KEY`), an unknown key (`UNKNOWN_CONFIG_KEY`) and a non-string scalar in a string-typed field (`NON_STRING_CONFIG_SCALAR`). They state that a configuration which loaded on 0.5.x may now refuse to start, and the quoting rule: values such as `010`, `yes` or `1e3` in string-typed fields must be quoted. Messages name the key and its path (key truncated to 64 characters), never the value. Take the exact list of changed files and fields from 170's hand-back.

- [ ] (Task 167, owner decision D-167-1 (b), 2026-10-08.) `docs/migration-0.6.md` notes that octal-looking band bounds and vocabulary versions, and `yes`/`no`/`on`/`off` values, changed meaning under YAML 1.2 (Jackson 3) and are now refused at startup by task 170. It also lists these public signature changes from 167, completed by 168's inventory: `DataPrismObjectMapper.create()` returning `JsonMapper` (then narrowed by 168); `SourceTree.text` returning `StringNode`; the `com.fasterxml.jackson.databind` to `tools.jackson.databind` identity moves on `SourceTree`, `ScrubResult`, `Generalizer`, `ContextResponse`, `ComparisonResponse.identity`, the `ObjectMapper` parameter of the two tool constructors, `LlmResponseValidator.validate`, `RawValueLeakValidator.validate`, `SensitivePatternValidator.validate` and `SensitiveDataScanner.scan`. It also records the YAML 1.2 change, the native serialisation of `java.time` and `Optional` in `SourceTree`, and the new tool message "the response could not be serialised".

## Out of scope
- The 0.6.0 release cut: version bump, `server.json`, image tags, tagging and publishing. A separate task, filed when the owner calls the cut.
- Rewriting historical CHANGELOG entries or anything under `docs/plan/`.
- Any source change.
