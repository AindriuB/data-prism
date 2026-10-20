# 239 — Add the adapter description SPI and the startup source catalog

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main`
**Depends on:** none
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/DataSourceAdapter.java *(the `description()` default method and its Javadoc only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/DataRequest.java *(the `keyNamespace` component, its default and its wither only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/SourceDescription.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/CostHint.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/FreshnessHint.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/KeyNamespace.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/spi/DataRequestTest.java *(constructor call sites and new cases only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/spi/SourceDescriptionTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/spi/KeyNamespaceTest.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/SourceCatalog.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/SourceDescriptionChecks.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/GeneratedDescriptions.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/SourceNames.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/SourceVisibility.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/catalog/** *(new)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/CatalogArchitectureTest.java *(new)*

## Goal
An agent that knows nothing about the estate needs each source to say what it holds, which entity types it serves and which key namespaces it accepts, without a call to the source. Add the adapter description to the SPI as a default method (catalog OD-C1 (a)), the key-namespace type every later 0.8 task uses, and an immutable startup registry that validates every description against the leak rules of `catalog.md` section 2 and generates the neutral fallback. No tool, label or Spring wiring changes here.

## Context
- docs/plan/design/catalog.md sections 1 and 2 (the SPI, the six startup rules, the generated fallback, the residual risk), section 7 (boundaries) and section 8 items 1, 2, 18 and 19.
- docs/plan/design/contract-register.md OD-200-4 (key namespace at most 32 characters), OD-200-7 (metric spelling, used by 255).
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/spi/DataSourceAdapter.java and DataRequest.java: the SPI as shipped.
- data-prism-validation/src/main/java/io/github/aindriub/dataprism/validation/SensitiveDataScanner.java:65-75: the key-shape scan that rule 3 reuses (D-070-7).
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java:525 (`mcpDoesNotReachSources`) and :235 (task 235's fixture pattern): the rule and fixture shapes to follow in a new file.

## Required shape (later tasks read these; state them all now)
- `KeyNamespace(String value)`: grammar `[a-z][a-z0-9-]{0,31}`, exact `equals`, no trimming or case folding; `toString()` returns the value. Read by 240, 241, 242, 249, 251.
- `SourceDescription(Set<String> entityTypes, String aliasDescription, String realDescription, List<KeyNamespace> inputKeys, CostHint cost, FreshnessHint freshness)`, with `undeclared()`. The three nullable parts are `aliasDescription`, `realDescription`, `cost` and `freshness`. Read by 247, 249, 250, 251.
- `DataRequest.keyNamespace()`: `Optional<KeyNamespace>`, empty from every existing factory, set by `withKeyNamespace` (catalog OD-C13 (a)). Set by 252.
- `SourceCatalog`: `build(List<DataSourceAdapter<?>> adapters, Map<String, String> baseUrlHostBySource)`; `entries()` in ascending byte order of real name; `entry(String sourceName)`; `generatedDescriptionCount()`; `generatedDescriptionSources()` (real names, operator-side); `nonconformingNameCount()`. Each entry exposes the real name, the validated `SourceDescription`, and the description text to show for each label kind. Read by 247, 250, 251, 254, 255.
- `SourceVisibility`: `boolean permitted(InvestigationContext caller, String sourceName)`, with `allowAll()`. Read by 247, 250, 251, 252.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] An adapter compiled against the 0.7 SPI (a fixture with no `description()`) builds into the catalog with the fallback `A data source.`, no entity types and no input keys. A mutant that makes a missing description throw or drop the source fails a test.
- [ ] `SourceDescription`'s constructor copies its collections, rejects an entity type outside `[A-Za-z][A-Za-z0-9_-]{0,63}`, a duplicate, and more than 8 input keys, with an `IllegalArgumentException` whose message contains no description text.
- [ ] `SourceCatalog.build` applies rules 1 to 6 of `catalog.md` section 2 in order and stops at the first failure. Rules 1 to 4 and 6 throw with a message that starts `INVALID_SOURCE_DESCRIPTION:` and rule 5 with `SOURCE_DESCRIPTION_LEAKS_NAME:`. The message names the source and the rule number and never the text: a test with a sentinel in the text asserts its absence from the message and from every log line.
- [ ] Boundary and mutant tests from `catalog.md` section 8 item 2 pass: 240 characters pass and 241 refuse; multi-line, control-character, markup, `@`-bearing, key-shaped, URL-shaped and host-shaped texts refuse; the real name, the adapter's simple class name, the base-url host and another source's real name, each in mixed case with punctuation, refuse under rule 5; identical alias and real texts refuse. Mutants killed: case-sensitive comparison, skipped normalisation, rule 5 applied to the real description.
- [ ] The fallback texts are exactly `Holds <T1>, <T2> records; accepts <K1>, <K2> keys.`, `Holds <T1>, <T2> records.` and `A data source.`, with entity types and keys each in ascending byte order whatever order they were declared in.
- [ ] Source-name grammar (catalog OD-C18 (c)): a name outside `[a-z][a-z0-9-]{0,63}` starts and is counted by `nonconformingNameCount()`. Boundary tests: 64 characters conform, 65 do not; an upper-case letter, a leading digit and an underscore do not. Nothing refuses in 0.8.
- [ ] The catalog is built once, holds no personal data, performs no I/O after `build`, and returns entries in the same order however the adapters were registered (a test permutes registration order).
- [ ] `SourceVisibility.allowAll()` permits every source. Its Javadoc says that 0.8 allows every source to a caller holding the tool capability (catalog OD-C5 (a)) and that from 0.9 unmatched sources are denied (D-RM-11).
- [ ] `CatalogArchitectureTest` (new file) fails when a class in `mcp` or `connectors` depends on a `DataSourceAdapter` implementation class, and when a class in a `core` package has a simple name containing one of `Parcel`, `Depot`, `Courier`, `Route`, `Customer`, `Patient`, `Invoice`. Nested fixture classes prove both rules can fail.
- [ ] `git diff --stat origin/main` lists no file under `data-prism-mcp`, `data-prism-spring-boot-autoconfigure` or `data-prism-hazelcast`.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-orchestration,data-prism-architecture -am verify` passes.

## Out of scope
- The `json-sources` `description:` block (249) and every Spring property, log line and metric (254, 255).
- Labels, aliases, real names and `targetSources` (247); the `list_sources` and `describe_source` tools (250).
- `@Chainable` (240) and `KeyResolver` (241).
- Any per-source authorisation pattern: that is 0.9 (D-RM-11, register successor S6).
