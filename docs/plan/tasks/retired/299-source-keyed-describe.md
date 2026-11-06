# 299 — Describe a source by name, so configured sources can be described

**Repo:** `.`
**Release:** 0.8.0. **Release obligation:** 0.8.0 must not ship with `describe_source` refusing configured sources.
**Base branch:** `origin/main` once 249, 250 and 251 have merged
**Depends on:** 249 (owns `ConfiguredJsonScrubbingEngine.describe`), 250 (creates `CatalogQueries`), 251 (edits `DefaultContextOrchestrator.java`)
**Decisions:** filed 2026-11-05 from the wave 8 review; coordinator call, owner-delegated.
**Wave:** 9. Before 254, which builds the `CatalogQueries` bean from the orchestrator; before 252, which edits the same two orchestrator files.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/ScrubbingEngine.java *(insertions only: the default `describe`)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java *(`describe` only)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDescribeConformanceTest.java *(call sites of `describe`, and the routing rows)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextOrchestrator.java *(insertions only: the default by-name `describe`)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(the `describe` override only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/CatalogQueries.java *(the constructor and the describe path only)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestratorDescribeTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/catalog/CatalogQueriesTest.java, CatalogPageLinkTest.java *(constructor call sites and their stubs only)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ListSourcesToolTest.java *(the constructor call site and its stub only)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ConfiguredSourceDescribeTest.java *(new)*

## Goal
Every configured source shares one model class, `ConfiguredJsonPayload`, so 250's `describe_source`, which describes by class through a scrubber, cannot describe one. Describe by source name, through the scrubber that really scrubs the source: the orchestrator's own. That engine is not the `dataPrismScrubber` bean on a configured deployment.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/ScrubbingEngine.java: the `Class` form of `describe`, whose empty result callers treat as a refusal.
- `ConfiguredJsonScrubbingEngine.scrub`: routes on `ConfiguredJsonPayload` first; `describe` routes the same way. 249 leaves a public by-name `describe(String, PrivacyContext, boolean)` there.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java: builds the configured orchestrator around its own `ConfiguredJsonScrubbingEngine`.
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ConfiguredJsonNestedHttpTest.java: a real auto-configured context with configured sources.

## Required shape
- `ScrubbingEngine` gains `default Optional<ModelDescription> describe(String sourceName, Class<?> model, PrivacyContext context, boolean linksEnabled)`, delegating to the `Class` form.
- `ConfiguredJsonScrubbingEngine` overrides it, routing on model type first, as `scrub` does: `ConfiguredJsonPayload` describes the named configured source; any other model goes to the delegate. An unknown name stays empty.
- `ContextOrchestrator` gains a default by-name `describe(String sourceName, PrivacyContext context, boolean linksEnabled)` returning empty, so test doubles do not change.
- `DefaultContextOrchestrator` overrides it with its own `scrubber` and the named adapter's response type. An unknown name is empty.
- `CatalogQueries` takes the orchestrator instead of `scrubber` and `adapters`. Its refusals and audit reasons do not change.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] The default `ScrubbingEngine.describe` gives the `Class` form's answer for a Java-first engine (test).
- [ ] `ConfiguredJsonScrubbingEngine`: a configured name with `ConfiguredJsonPayload` describes non-empty; an unknown name with `ConfiguredJsonPayload` is empty; a Java-first model named like a configured source goes to the delegate. Mutant killed: routing on the name first.
- [ ] `ContextOrchestrator`'s default returns empty, and `DefaultContextOrchestrator` returns its scrubber's answer for a known name and empty for an unknown one.
- [ ] `git diff` shows `CatalogQueries` holds no `ScrubbingEngine` and no adapter list, and the existing 250 tests pass with only their constructor call sites and stubs changed.
- [ ] `ConfiguredSourceDescribeTest`, through the real auto-configured context: a configured source describes non-empty from the `ContextOrchestrator` bean, and every path of a real scrubbed response from that source is described with the protection it was scrubbed under. A mutant describing from `dataPrismScrubber` dies; the Outcome shows it.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- Building the `CatalogQueries` bean, the stdio tools, and catalog settings on the configured orchestrator (254).
- Whether configured sources get `_links` (254's Outcome, before 255).
- Any change to `describe_source`'s schema, refusals or audit (250).

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg`, `.env` files or any other credential file.
- No `mvn install`, no `git stash`, no force-push, no `pkill` or `killall` by pattern.
- Never route around a hook or a permission denial. Stop and report it.

## Outcome (2026-11-06, 0.8; PR #216)

- `ScrubbingEngine` gains a default `describe(sourceName, model, ctx, links)` delegating to the `Class` form. `ConfiguredJsonScrubbingEngine` overrides it and routes on model type first: `ConfiguredJsonPayload` describes the named source (unknown name is empty), any other model goes to the delegate.
- `ContextOrchestrator` gains a default by-name `describe` (empty). `DefaultContextOrchestrator` overrides it with its own scrubber and the named adapter's response type; unknown name is empty. Adapters supply no description; the engine stays the only authority.
- `CatalogQueries` now takes the orchestrator instead of the scrubber and adapter list. Refusals and audit reasons are unchanged. Existing 250 tests changed only at constructor call sites and stubs.

### Tests first (red on the base commit, commit e64b2eb3)
Compile failures on the missing API in `DefaultContextOrchestratorDescribeTest` (new), `ConfiguredJsonDescribeConformanceTest` routing rows, `CatalogQueriesTest`, `CatalogPageLinkTest`, `ListSourcesToolTest`. `ConfiguredSourceDescribeTest` (new, real auto-configured context over a TLS upstream) covers: a configured source describes non-empty from the `ContextOrchestrator` bean; `dataPrismScrubber` cannot describe it; every path of a real scrubbed response is described with the protection it was scrubbed under.

### Mutants (both killed)
- Routing on the name first (`sources.containsKey(name)` in place of the model-type check): `byNameRoutesPayloadClassToTheNamedSource` and `javaFirstModelNamedLikeAConfiguredSourceGoesToTheDelegate` fail.
- Describing from the Java-first engine (what `dataPrismScrubber` is) instead of the configured one: `configuredSourceDescribesFromTheOrchestratorBean` fails and `everyScrubbedPathIsDescribedWithItsProtection` errors (no description).

### Verified locally
Targeted classes in core/orchestration/connectors-rest/mcp plus the integration test, with `-pl <module> -am`: all green. The full `verify` is left to CI.

### Noted
`ConfiguredJsonPayload` is package-private, so the integration test reaches its class through the adapter's `responseType()`.
