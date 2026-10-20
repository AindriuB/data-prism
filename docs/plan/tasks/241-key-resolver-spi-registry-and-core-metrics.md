# 241 — Add the KeyResolver SPI, its registry and a bounded invoker, and register the 0.8 core metrics

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 239, 244 and 220 have merged
**Depends on:** 239 (`KeyNamespace`), 244 (both insert into `Metric.java` and `PrivacyMetricsTest`), 220 (sealed-cluster plan; 220 inserts every `dataprism.cluster.*` constant into the same `Metric.java` and `PrivacyMetricsTest.java`, so the three insertions land in order)
**Wave:** 4 (combined 0.8 graph of 213, 218 to 231 and 238 to 263; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/KeyResolver.java *(new; `Resolution` is a nested record)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/ResolutionContext.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/KeyResolutionException.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/IdentityResolverKeyResolvers.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/metrics/PrivacyMetricsTest.java *(insertions only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/spi/KeyResolverContractTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/spi/IdentityResolverKeyResolversTest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/KeyResolvers.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/ResolverInvoker.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/links/ResolverOutcome.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/links/KeyResolversTest.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/links/ResolverInvokerTest.java *(new)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/KeyResolverArchitectureTest.java *(new)*

## Goal
Translation between key namespaces happens only through a declared resolver (D-RM-5), resolved at follow time (key-resolvers OD-R2) and capped at 5 keys (OD-R1). Add the SPI, a registry that refuses bad declarations, and an invoker that bounds every resolver call in time and turns every contract violation into a closed reason, so `follow` (252) never waits unbounded and never sees a resolver's exception text. Because several 0.8 core tasks need metric constants, this task also adds every 0.8 core constant at once, so 250, 251, 252 and 255 never edit `Metric.java`.

## Context
- docs/plan/design/key-resolvers.md section 6 (SPI, declarations, cardinality, failure table, budget, audit, `IdentityResolver` adapter) and the test obligations 1, 2, 6, 8 to 12; amended by register OD-200-3 (`RESOLVER_FAILED`), OD-200-4 (resolver name at most 32) and OD-200-15 (one `Metric` constant per outcome).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/IdentityResolver.java: unchanged and undeprecated (OD-R8).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/metrics/Metric.java:27: "One constant per refusal code, because PrivacyMetrics takes no free-form tag."
- Task 220 on `plan/0.8-sealed-cluster` adds the cluster constants to the same enum; this task depends on 220 so the insertions are serialised.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `KeyResolver` has exactly `name()`, `from()`, `to()` and `resolve(String key, ResolutionContext context, int maxResults)`, returning `Resolution(List<String> keys, boolean more)`. `Resolution` copies `keys`; its constructor does not reject `more` with no keys, so the invoker can classify that as `INVALID_OUTPUT`.
- [ ] `ResolutionContext` carries only `scopeId`, `principalId`, `purpose` and a deadline `Instant`. `KeyResolutionException` carries only a reason from `UNAVAILABLE`, `TIMEOUT`, `REJECTED`, `TOO_MANY`; its message is a constant.
- [ ] `KeyResolvers` refuses, with a message starting with the code: two resolvers for one `(from, to)` pair (`DUPLICATE_KEY_RESOLVER`); `from` equal to `to`, or a name outside `[a-z][a-z0-9-]{0,31}` (`INVALID_KEY_RESOLVER`). `unusedPairs(Set<KeyNamespace> used)` returns the declared pairs no field or input key uses (OD-R4: a warning, not a refusal). `from(KeyNamespace)` returns the resolvers in ascending name order.
- [ ] `ResolverInvoker.invoke(resolver, key, context, maxResults, timeout)` returns a `ResolverOutcome`: keys plus `more`, `EMPTY`, or `FAILED` with one of `UNAVAILABLE`, `TIMEOUT`, `REJECTED`, `TOO_MANY`, `INVALID_OUTPUT`. More than `maxResults` keys is `TOO_MANY`; `more` with no keys, a `null` result, a blank key or a key over 256 bytes is `INVALID_OUTPUT`; any other `RuntimeException` is `UNAVAILABLE`.
- [ ] Every wait is bounded: a resolver that blocks forever returns `TIMEOUT` once the timeout passes, and its thread is interrupted. The test uses a latch and an injected deadline, not a sleep.
- [ ] A resolver that throws with the key in its message: the key appears in no outcome, exception, log line or metric (sentinel test). A mutant that continues with the untranslated key or truncates an over-long list fails a test.
- [ ] `IdentityResolverKeyResolvers` for sources `S1`, `S2` returns exactly `expand(resolve(new SourceRef(S1, key)), List.of(S2))`'s keys. The existing `IdentityResolver` test suites run unchanged.
- [ ] `Metric` gains these constants and `PrivacyMetricsTest` pins them; no existing constant changes: `RESOLVER_UNAVAILABLE` `dataprism.resolver.unavailable`, `RESOLVER_TIMEOUT` `dataprism.resolver.timeout`, `RESOLVER_REJECTED` `dataprism.resolver.rejected`, `RESOLVER_TOO_MANY` `dataprism.resolver.too_many`, `RESOLVER_INVALID_OUTPUT` `dataprism.resolver.invalid_output`, `RESOLVER_LATENCY` `dataprism.resolver.latency`, `LINKS_ISSUED` `dataprism.links.issued`, `LINKS_OMITTED` `dataprism.links.omitted`, `LINKS_REFUSED` `dataprism.links.refused`, `LINKS_ATTEMPTS_EXHAUSTED` `dataprism.links.attempts_exhausted`, `LINK_KEYS_LOADED` `dataprism.links.keys.loaded`, `PAGING_PAGES` `dataprism.paging.pages`, `CATALOG_DESCRIPTIONS_GENERATED` `dataprism.catalog.descriptions.generated`, `CATALOG_SOURCE_NAMES_NONCONFORMING` `dataprism.catalog.source_names.nonconforming`. The invoker increments the resolver ones.
- [ ] `KeyResolverArchitectureTest` (new file) fails when a class in `mcp` or `connectors` depends on `KeyResolver` or on `orchestration.links`, and when a `KeyResolver` implementation or `orchestration.links` depends on `data-prism-reidentification`. Nested fixtures prove each rule can fail.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-orchestration,data-prism-architecture -am verify` passes.

## Out of scope
- Calling resolvers from `follow`, charging the budget for them, and sorting returned keys by keyed fingerprint (252).
- Declaring translated targets when issuing links (251).
- Spring bean collection and the `dataprism.key-resolvers.*` properties (255).
- Any metric beyond the list above, and any metric label API.
