# 275 — Key configured JSON source fields by restricted JSONPath

**Repo:** `.`
**Release:** 0.9.0 (changes the `json-sources:` grammar; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once 273 and 274 have merged
**Depends on:** 274 (the parser, path set and walker), 273 (both edit `freeze/codes.txt` and the contract register), 249 (configured-source descriptions, chainable fields and several files), 251 (link issuance from chainable fields), 290 and 271 (PR #161: the source-model rule and the descriptor map; 249 depends on 271. 290 replaces the retired 270 on 2026-10-27 and depends on 288; configured JSON trees pass through 288's walker unchanged, so this is an ordering edge only)
**Wave:** 16 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** OD-275-1 is open (below). The grammar itself, and "JSONPath only, no nested-YAML alternative", are owner decisions already made in the spec, section 2.
**Owns:**
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSource.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonFieldMetadataResolver.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonScrubbingEngine.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonNestedCatalogueSlots.java, ConfiguredJsonNestedCatalogueTokens.java, ConfiguredJsonNestedLeafShapeGuard.java *(rewritten, renamed or removed as the design needs)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDataSourceAdapter.java, ConfiguredJsonPayload.java
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/jsonpath/** *(fixes found while wiring; the Outcome names each)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonPath*.java *(new, if the design needs classes outside `jsonpath`)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDataSourceAdapterHttpTest.java, ConfiguredJsonDispositionsTest.java, ConfiguredJsonNestedCatalogueOrdinalDeterminismTest.java, ConfiguredJsonNestedCatalogueScrubbingTest.java, ConfiguredJsonNestedCatalogueTokensTest.java, ConfiguredJsonSourceEndToEndTest.java, ConfiguredJsonSourcesLimitsTest.java, ConfiguredJsonSourcesPatternValidatorTest.java, ConfiguredJsonSourcesTest.java, ConfiguredJsonSpecialCategoryTest.java, ConfiguredJsonUndeclaredKeyRefusalTest.java *(existing; changed only as OD-275-1 decides)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonDescriptionTest.java, ConfiguredJsonChainableTest.java, ConfiguredJsonDescribeConformanceTest.java, ConfiguredJsonSourceFilesTest.java *(249's; added cases, and changes only as OD-275-1 decides)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonPath*Test.java *(new)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/jsonpath/** *(additions)*
- data-prism-connectors-rest/src/test/resources/task20-json-sources*.yaml *(only as OD-275-1 decides)*
- data-prism-connectors-rest/src/test/resources/jsonpath-sources/** *(new fixtures)*
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/ConfiguredJsonNestedHttpTest.java *(only as OD-275-1 decides)*
- data-prism-server/src/test/resources/configured-json/** *(only as OD-275-1 decides)*
- load-test/src/main/java/io/github/aindriub/dataprism/loadtest/SourceGenerator.java *(the generated `json-sources:` text only, and only as OD-275-1 decides)*
- examples/json-sources/**
- docs/protect-your-own-api.md *(including removing the stale note at :627-630, since `dataprism.identity.resolver` is documented at `docs/configuration.md:281`)*
- data-prism-architecture/src/test/resources/freeze/codes.txt, freeze/enums.txt *(only the codes and enums this task adds or removes)*
- docs/plan/design/contract-register.md *(the `json-sources:` row at :941 and insertions only: rows for the codes this task adds)*

## Goal
Replace the flat and one-level field names of a `json-sources:` catalogue with restricted JSONPath, so that a developer can describe any JSON response shape in YAML, at any depth and through arrays. Use the same syntax for `subject-json-path` and for chainable link fields. Every value in a response stays covered by exactly one path, and anything uncovered is still refused. Turn every refusal this grammar raises into a coded refusal, including the raw `IllegalArgumentException`s in `ConfiguredJsonSources` such as a missing source timeout (`:158`).

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 2 (grammar, binding) and section 3 (coded refusals; never echo a secret value).
- 274's `connectors.rest.jsonpath` package: parse, path set, walker. Map each failure reason to a stable refusal code here. A reason enum whose `name()` now leaves the process goes into `freeze/enums.txt`.
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java:40-60 (the `FIELD_NAME` grammar), :110-240 (source and field parsing, every raw `IllegalArgumentException`), :300-380 (nested catalogues).
- `ConfiguredJsonNestedCatalogueTokens` (class Javadoc): core's `FieldMetadataResolver` is keyed on `Class`, so nested catalogues use a bounded pool of named marker classes. Arbitrary-depth paths need either one token per distinct object position in the catalogue, which a pool bounded by the catalogue's declared paths can still serve, or a path-keyed scrub inside `ConfiguredJsonScrubbingEngine`. Take this to `/design` before writing code. Core's `FieldMetadataResolver` SPI must not change. If the design needs a core change, stop and report.
- The marker-token rationale still applies to any new design: no hidden classes, deterministic names across runs, and a fail-closed bound named in the refusal.
- `ConfiguredJsonNestedLeafShapeGuard` and its code (`:30`): the shape refusals that 274's walker now reports. Keep the codes where the meaning is the same.
- 249 (0.8): `description:` blocks, `chainable: true` on fields and `ConfiguredJsonDescribeConformanceTest`. After this task a chainable field is keyed by a path. A chainable path without `[*]` works exactly as a top-level chainable field did. For a path with `[*]`: if 251's link issuance supports a link per array element, it works; otherwise it refuses at startup with a coded refusal (fail closed), and the Outcome says which and names the follow-up.
- `subject-json-path` must name a path with no `[*]`, declared in `fields:` with `identifier: true`. Today it must be a bare name (`:199`).
- docs/configuration.md:723-795 documents today's grammar. 281 rewrites that section from this task's Outcome. Do not edit `docs/configuration.md` here.
- docs/extending.md:46 also states today's grammar. 278 owns that page and corrects the line.
- Repository rule 1: a configured source still reaches the MCP layer only through the privacy engine. Rule 2: an uncovered value, a shape mismatch or an unparseable path refuses; nothing passes through.
- Fixture values are invented. Repository rule 3, and the owner's rule to use invented neutral domains.
- `ConfiguredJsonSourcesAutoConfigurationTest.java`, `ConfiguredJsonSourcesAutoConfiguration.java` and `ConfiguredJsonSourcesInitializer.java` belong to 277, which runs in the same wave. If the grammar change breaks one of them, stop and report it rather than editing it.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## OD-275-1 (open, owner): what happens to existing catalogues
The spec allows either "existing flat catalogues keep working" or "a migration note".
- **(a), recommended:**
  - A key that matches today's bare-name grammar (`^[A-Za-z_][A-Za-z0-9_]*$`) is read as `$.<name>`, so every flat catalogue keeps working unchanged. That form cannot be confused with a path, because it has no `$`, `.` or `[`.
  - `nested:` and `nested-catalogues:` are removed. A catalogue that uses them refuses at startup with a new code (proposed: `JSON_SOURCE_NESTED_CATALOGUE_RETIRED`), naming the source and pointing to the migration note. The note shows the same catalogue in path form.
  - Why: the common case needs no migration, and two grammars for nesting would both be frozen at 0.9.
- **(b):** bare names keep working, and nested catalogues also keep working, translated internally to paths. There is no break, but two nesting grammars are frozen.
- **(c):** everything must be rewritten in path form. Bare names refuse with a migration code. This is the cleanest grammar and breaks every existing catalogue.
- The Owns entries marked "only as OD-275-1 decides" cover the existing nested fixtures and tests that (a) or (c) must rewrite.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] A catalogue keyed by paths scrubs a response with a two-level nested object, an array of objects, an array of arrays and a null, and every value takes the policy of its one covering path. One test per shape, under the bundled `DEFAULT` profile.
- [ ] An uncovered value refuses the source with `UNKNOWN_FIELD`, as today. A declared leaf that receives an object or array, or a declared container that receives a scalar, refuses with the shape code. Neither the message nor the path in either refusal contains a value; a sentinel test shows this.
- [ ] Each refused path form (recursive descent, filter, union, index, slice) and each refused path set (duplicate after normalisation, leaf crossing) refuses at startup with its own stable code. The message names the source and the key, never a configured value other than the key itself. The codes are in the register and `freeze/codes.txt`.
- [ ] Every raw `IllegalArgumentException` in `ConfiguredJsonSources`, the missing-timeout one at `:158` included, is now a coded refusal. `git grep -n 'new IllegalArgumentException(' -- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJson*.java` returns only sites whose message starts with a registered code. 279 later changes the exception type and adds the hint; this task fixes the codes.
- [ ] `subject-json-path` accepts a path such as `$.customer.id` and refuses one with `[*]`, one not declared in `fields:` and one not marked `identifier: true`, each with a coded refusal.
- [ ] A chainable field keyed by `$.account.ref` is described with its `keyNamespace` and issues a link exactly as a top-level chainable field does (249's and 251's tests, extended). The `[*]` case behaves as the Context bullet says, and the Outcome says which.
- [ ] OD-275-1 is implemented as decided. Under (a): every existing flat fixture and `examples/json-sources/customer-api.yaml` pass unchanged; each existing nested fixture, rewritten in path form, scrubs every existing test response to byte-identical output (the pseudonyms included), shown by running the old and new fixture against the same inputs; a nested catalogue refuses with the retirement code.
- [ ] `ConfiguredJsonDescribeConformanceTest` (249) passes over the path-keyed fixtures: every path a scrubbed response contains is described with the protection it was scrubbed under.
- [ ] `examples/json-sources/` holds a path-form example with nesting and an array, and the header comments no longer say "no dotted path, no JSONPath". `docs/protect-your-own-api.md` teaches the path form and no longer has the note at :627-630.
- [ ] The Outcome contains, under "For 281", the grammar text for `docs/configuration.md:723-795`, the migration-note text and the CHANGELOG entries.
- [ ] Every existing test in `data-prism-connectors-rest`, `data-prism-server` and `data-prism-integration-tests` passes, changed only where OD-275-1 requires and the Outcome lists.
- [ ] `./mvnw -B -pl data-prism-connectors-rest,data-prism-server,data-prism-integration-tests -am verify` passes, then `./mvnw -B verify`.

## Out of scope
- `ConfiguredJsonSourcesAutoConfiguration`, `ConfiguredJsonSourcesInitializer` and embedding in a starter application (277).
- The exception type, the failure analyser and the hints (279).
- `docs/configuration.md`, `docs/extending.md`, `CHANGELOG.md` and the migration page (281 and 278).
- Any change to core's `FieldMetadataResolver`, `JsonTreeScrubbingEngine` or `@LlmExposedModel` records.
- A nested-YAML alternative syntax (rejected by the owner), and public-API catalogues (a future idea, recorded in PLAN.md "Someday").
