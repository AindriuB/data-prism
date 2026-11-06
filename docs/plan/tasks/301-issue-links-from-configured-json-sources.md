# 301 — Issue links from configured JSON sources, and let YAML map a namespace to its type

**Repo:** `.`
**Release:** 0.8.0. **Release obligation:** must land before 255 enables links and before the 0.8.0 cut.
**Base branch:** `origin/main` once 252 and 254 have merged
**Depends on:** 252 (`SourceDescription.namespaceTypes`, `LinkTargets`), 254 (settled the question in its PR body)
**Decisions:** filed 2026-11-06 from PR #221; the decisions below are made here.
**Wave:** 11. **Serialised with:** 286 on `contract-register.md`: different rows, so the second to merge rebases.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/ScrubbingEngine.java *(insertions only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/engine/ChainableView.java *(new)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java *(the override only)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java *(the `description:` block only)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDescriptionTest.java, ConfiguredJsonLinksTest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/LinkBuilder.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(the `LinkBuilder` construction only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/SourceCatalog.java *(the WARN only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/links/LinkBuilderTest.java, catalog/SourceCatalogTest.java *(insertions only)*
- docs/plan/design/catalog.md *(the `description:` key row, about line 825)*, docs/plan/design/contract-register.md *(one new `namespace-types` row)*

## Goal
Every configured source shares `ConfiguredJsonPayload`, so `LinkBuilder`, which keys on the record class, issues them no links while `describe_source` can advertise a `keyNamespace`. Make links follow what the source declares, and let a multi-type configured source become a link target.

## Decisions
- **The scrubbing engine is the authority, not the adapter.** It already holds the reviewed `ConfiguredJsonSource`, and 299 made it the by-name authority for `describe`. An adapter method would let adapter code choose which raw field is sealed into a token, and would widen the SPI (rule 1 of `CLAUDE.md`).
- **The mapping is needed; configured sources get no special case.** The type is inert on the read: `RestDataSourceAdapter.fetch` uses only `subjectId`, so a mapping cannot change what is fetched. It is not inert on the call: `DefaultContextOrchestrator.follow` puts it on the response envelope (`followEnvelope`) and passes it down the read and budget path. A special case would show an invented or arbitrary type there, because `SourceDescription` copies `entityTypes` into an unordered set, and would put a connector branch in `LinkTargets`. No type and one type still need no mapping.
- **The key is `description.namespace-types`,** not a `fields:` key: it describes input namespaces, not response fields, and `SourceDescription` already validates it.

## Required shape
- `ChainableView(List<ChainableField> fields, JsonNode tree)`: redacted `toString`.
- `ScrubbingEngine` gains `default Optional<ChainableView> chainableView(String sourceName, Object record)`, returning empty: "the class decides".
- `ConfiguredJsonScrubbingEngine` overrides it. For a `ConfiguredJsonPayload` it gives the named source's `chainableFields()` over `payload.body()`. `SourceTree.of(payload)` nests the response under `body`, so the declared path would never match it. A payload whose `sourceName` differs from the argument, or an unknown source, gives a view with no fields. Any other record gives empty.
- `LinkBuilder.collect` asks the view first, through a function `DefaultContextOrchestrator` passes from its own `scrubber`, and falls back to today's class path on empty. The per-class cache is never used for a view. A throw means no link. The existing constructor stays, with a function that always answers empty.
- `namespace-types:` is a mapping of input key to entity type, added to `DESCRIPTION_KEYS` and passed to the seven-argument `SourceDescription` constructor.
- `SourceCatalog.build` logs one WARN per source, naming it, when the source declares several entity types and at least one input key has no mapping. An incomplete mapping counts: `LinkTargets` checks per namespace. It covers Java-first adapters too. It never refuses: such a source starts today.

## Context
- data-prism-orchestration/.../links/LinkBuilder.java:192,233-240 — the class key; 198 — `SourceTree.of`.
- data-prism-orchestration/.../links/LinkTargets.java — `target(...)`, the eligibility rule; leave it unchanged.
- data-prism-connectors-rest/.../ConfiguredJsonScrubbingEngine.java — `describe`, the routing to mirror; `scrub`, which reads `payload.body()`.
- data-prism-connectors-rest/.../ConfiguredJsonSources.java:267-318 — the `description:` parser and its message rule.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `LinkBuilderTest`: two sources sharing one record class each issue links from their own declared field. Mutant killed: caching the view by class.
- [ ] `LinkBuilderTest`: a throwing view gives no link and no exception, and the existing rows pass unchanged.
- [ ] `ConfiguredJsonLinksTest`, through `DefaultContextOrchestrator` with `LinkIssuing` and a stub HTTP source: a configured source with `chainable: true` issues a link to a second configured source that declares the namespace, whose `field` equals the path `describe` gives that `keyNamespace`.
- [ ] Same test: following that link reads the target, and the envelope carries the type from `namespace-types`.
- [ ] Same test: a mismatched payload name and an unknown source each give no link.
- [ ] `git diff` shows no change to `DataSourceAdapter`, `ConfiguredJsonDataSourceAdapter`, `LinkTargets` or `SourceDescription`, and `LinkBuilder` imports no adapter type.
- [ ] `ConfiguredJsonDescriptionTest`: a valid `namespace-types` reaches `description().namespaceTypes()`. A non-mapping refuses with `INVALID_CONFIG_SHAPE`. A key outside `input-keys`, a type outside `entity-types` and a key that is not a namespace each refuse with `INVALID_SOURCE_DESCRIPTION`. No message carries the value (sentinel).
- [ ] `SourceCatalogTest`: the WARN appears once, naming the source, with no mapping and with an incomplete one. It does not appear for no type, one type, no input keys or a complete mapping.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- Wiring `dataprism.links.*` or the real-HTTP leak scans (255).
- The user docs for `description:` in `docs/configuration.md` (261 owns that block; the Outcome hands it the key).
- More than one chainable field per configured source, or nested ones: `identifier: true` is still single and top-level.
- Using `entityType` or the key namespace on a configured read.
