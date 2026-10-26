# 249 — Configured JSON sources: description block, chainable fields, describe, and several files

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 239, 240, 246, 247 and 248 have merged
**Depends on:** 239, 240, 248 (the SPI pieces it implements), 246 and 247 (both edit `ConfiguredJsonSourcesAutoConfiguration`), 271 (the `DescriptorFieldMetadataResolver.descriptors()` accessor; added 2026-10-23)
**Decisions:** OD-08-8 decided 2026-10-20 (a): `config-location` takes a list; a source name in two files refuses with `DUPLICATE_JSON_SOURCE`.
**Wave:** 8 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSource.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDataSourceAdapter.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java *(the `describe` override only)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesInitializer.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java *(location binding, description plumbing, and the descriptor map passed to the `javaFirst` engine at :177 only; the last added 2026-10-23)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDescriptionTest.java *(new)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonChainableTest.java *(new)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDescribeConformanceTest.java *(new)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceFilesTest.java *(new)*
- data-prism-connectors-rest/src/test/resources/** *(new fixture files only)*

## Goal
A configured JSON source is declared in a `json-sources:` file, not in Java, so it needs the same three things a Java adapter gets in 0.8: a `description:` block (catalog section 1), `chainable` and `keyNamespace` on identifier fields in `fields:` (key-resolvers section 7), and a `describe` computed from its `fields:` catalogue. The roadmap also asks for source configuration split across files with startup validation; for configured sources that means more than one `json-sources` file, validated together, as OD-08-8 decides.

## Context
- docs/plan/design/catalog.md section 1 ("The same declaration for configured JSON sources", the YAML example) and section 2 (the checks run by `SourceCatalog`, 239).
- docs/configuration.md, `dataprism.json-sources.config-location` (about :660-700): strict keys, the catalogue rules, the location-describing rule.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java:110-380: the strict parser and its messages (`StrictYaml.shown`).
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesInitializer.java:47-120: how the location is read and shown in messages.
- From 240 (2026-10-21): `ChainableField` is `io.github.aindriub.dataprism.core.engine.ChainableField`; use `ChainableFields.of(Class, Map<String, ModelDescriptor>)`.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] A `description:` block takes exactly `entity-types`, `alias-description`, `real-description`, `input-keys`, `cost`, `freshness`; any other key refuses with the parser's existing unknown-key refusal. The adapter's `description()` returns it; without the block it returns `SourceDescription.undeclared()`. Description text never appears in a parser message (sentinel).
- [ ] In `fields:`, `chainable: true` with `keyNamespace` is accepted only on an entry with `identifier: true`, and on any other entry, or without `keyNamespace`, refuses with a message starting `INVALID_CHAINABLE_FIELD:` naming the source and field. The chainable fields are exposed in the `ChainableField` shape of 240. Scrubbed output and dispositions are unchanged with the flag set (parity test).
- [ ] `ConfiguredJsonScrubbingEngine.describe` describes every `fields:` entry and nested catalogue as 248 specifies. `ConfiguredJsonDescribeConformanceTest` proves, over the existing fixtures, that every path a scrubbed response contains is described with the protection it was scrubbed under.
- [ ] Several files, as OD-08-8 decides. Under its option (a): `dataprism.json-sources.config-location` accepts a list of locations (a single value still works unchanged); every file is parsed strictly; a source name declared in two files refuses startup with the code OD-08-8 names, and the message names the source and neither location; the resulting source order does not depend on file order; an unreadable file refuses as today, with the location shown only by the existing `describe(location)` rule.
- [ ] The base-url host of each configured source is available to the catalog's rule 5 check (an accessor read by 254).
- [ ] The `javaFirst` `JsonTreeScrubbingEngine` in `ConfiguredJsonSourcesAutoConfiguration` is built with 248's five-argument constructor, passing the descriptor map of the injected resolver after unwrapping AOP proxies (`AopProxyUtils.getSingletonTarget`, repeated until there is no further target), and `Map.of()` if the target is not a `DescriptorFieldMetadataResolver`. With a descriptor file set, 271's identity check guarantees the target is the one built from the file. A JDK-proxy test asserts the described `keyNamespace` is present. A test shows that a descriptor-declared chainable field on a Java-first model is described with its `keyNamespace` (added 2026-10-23).
- [ ] Every existing `data-prism-connectors-rest` test passes unmodified.
- [ ] `./mvnw -B -pl data-prism-connectors-rest -am verify` passes.

## Out of scope
- `dataprism.sources.<name>` (transport only, unchanged) and any Spring autoconfigure-module change (254).
- `docs/configuration.md` (261 documents the `description:` block and the file list).
- Wildcard or directory scanning of locations, unless OD-08-8 chooses it.
