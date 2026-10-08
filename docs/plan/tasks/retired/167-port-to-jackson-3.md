# 167 — Port the reactor to Jackson 3 in one step: code, mappers, MCP JSON binding, Spring converters and the enforcer

**Repo:** .
**Base:** branch from `origin/main` after 155, 156, 157 and 166 have merged into it.
**Depends on:** 155, 156, 157, 166
**Owns:**
- pom.xml (the `enforce-jackson-major` execution and any Jackson or MCP entries in `dependencyManagement`)
- data-prism-{core,security,mcp,quickstart-extension,server,spring-boot-starter,quickstart-fixtures,quickstart-issuer}/pom.xml (Jackson, `mcp-json-*` and `spring-boot-*jackson*` entries only)
- data-prism-*/src/main/java/**/*.java: every file that imports `com.fasterxml.jackson` other than `com.fasterxml.jackson.annotation`. At 9a00c04e that is 31 files in core (including the files 156 and 157 move, at their post-move paths), connectors-rest, mcp, orchestration, pseudonymisation, security, server, spring-boot-autoconfigure (`JwtDecoderSupport.java`, at its pre-159 path) and validation.
- data-prism-*/src/test/java/**/*.java: every file that imports `com.fasterxml.jackson` or calls an `ObjectMapper` constructor. That is roughly 45 files across 12 modules.
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java (the two mapper rules and their helpers only; plus dropping the redundant `.allowEmptyShould(true)` on `CORE_ROOT_PACKAGE_IS_EMPTY`, follow-up (at) from 156)
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/*MapperFixture.java (new negative-test fixtures. They sit next to `AuditDependsOnMcpFixture.java` and, like it, declare a package under `io.github.aindriub.dataprism..` outside the allowlist.)
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/boot/Boot4RegressionGuardsTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/characterisation/** (import lines only; every assertion change is a listed difference, see Acceptance)
- examples/json-sources/NestedCatalogueWalkthrough.java (not compiled by the reactor)

## Goal
Owner decisions J3-1, J3-2 and J3-5. Move every Jackson use in the reactor from Jackson 2
(`com.fasterxml.jackson`) to Jackson 3 (`tools.jackson`) in one atomic change. Partial ports do
not work here. Tree types cross module boundaries (`ScrubResult`, `ContextResponse`), and Spring
Framework 7 picks a Jackson 3 HTTP converter as soon as `tools.jackson` is on the classpath. So
code, MCP binding, Spring converters and the enforcer flip together.

Data-prism keeps its own fixed, private mappers built with Jackson 3 builders: `DataPrismObjectMapper`, the `SourceTree` reader and the five YAML readers. None of them is a Spring bean. The tool constructors keep their `ObjectMapper` parameter in this task. Narrowing it is 168.

## Context
- pom.xml:263-290. The `enforce-jackson-major` execution. J3-5 sets the new ban list.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismObjectMapper.java:26-33 and DataPrismMcpServer.java:112-113,164-165. The output mapper, and `JacksonMcpJsonMapper` from `mcp-json-jackson2`, which becomes the `mcp-json-jackson3` equivalent.
- data-prism-architecture/.../ArchitectureTest.java:56-130. `ONLY_DESIGNATED_CLASSES_CREATE_MAPPERS` matches `ObjectMapper` constructor calls. Under Jackson 3 a mapper is built with `JsonMapper.builder()`, `YAMLMapper.builder()`, `MapperBuilder#build()` or `ObjectMapper#rebuild()`, so a constructor-only rule stops seeing most of them. The companion rule forbids `write*` on the five YAML readers.
- data-prism-server/.../boot/Boot4RegressionGuardsTest.java:57-79. Today it asserts a Jackson 2 converter and no `tools.jackson`. J3-5 inverts it.
- data-prism-{server,spring-boot-starter,quickstart-fixtures,quickstart-issuer}/pom.xml. Each excludes `spring-boot-starter-jackson` and adds `spring-boot-jackson2` (D-139-A), and J3-5 reverses both.
- data-prism-quickstart-extension/pom.xml:91-95. Test-scope `mcp-json-jackson2` for `QuickstartSmokeIT`.
- data-prism-server/.../operator/OversightOperatorController.java:3. It uses only `com.fasterxml.jackson.annotation.JsonInclude`, which Jackson 3 still reads, so this file needs no change.
- data-prism-core/.../audit/AuditJsonRenderer.java:30-40 (post-157 location under `audit.format` or wherever 157 put it). A streaming generator with `ESCAPE_NON_ASCII`. Under Jackson 3 it is configured on the factory builder.
- Jackson 3 changed defaults and renamed API. The relevant points to check: `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`, `WRITE_DATES_AS_TIMESTAMPS`, `FAIL_ON_TRAILING_TOKENS`, `FAIL_ON_NULL_FOR_PRIMITIVES`; `JsonProcessingException` is replaced by the unchecked `JacksonException`; `TextNode`/`asText()`/`fields()` become `StringNode`/`asString()`/`properties()`; YAML moves to SnakeYAML Engine (YAML 1.2). Where a changed default alters bytes, prefer setting the old value explicitly on data-prism's builder over accepting the difference.
- docs/conventions.md#privacy-rules-a-diff-must-satisfy and CLAUDE.md rules 1 and 2. A mapper the rule cannot see is a bypass.

## Acceptance
- [ ] `mvn -B --no-transfer-progress verify` is green on JDK 21 and JDK 25, including the enforcer, ArchUnit and `container-smoke` (CI `ci-gate`).
- [ ] Enforcer (J3-5): `bannedDependencies` excludes `com.fasterxml.jackson.core:jackson-databind`, `com.fasterxml.jackson.core:jackson-core`, `com.fasterxml.jackson.dataformat:*`, `com.fasterxml.jackson.datatype:*`, `io.modelcontextprotocol.sdk:mcp-json-jackson2` and `org.springframework.boot:spring-boot-jackson2`. It explicitly includes or allows `com.fasterxml.jackson.core:jackson-annotations`. The former bans on `tools.jackson.core:*`, `io.modelcontextprotocol.sdk:mcp` and `mcp-json-jackson3` are gone, and the rule's `<message>` and comment describe the new rule.
- [ ] Proof the ban works: adding `com.fasterxml.jackson.core:jackson-databind` to any module's pom makes `mvn -pl <module> validate` fail with the enforcer message. The hand-back quotes the run; the edit is not committed.
- [ ] `git grep -n -E 'com\.fasterxml\.jackson\.(databind|core\.|dataformat|datatype)' -- '*.java' '*.xml'` prints nothing. `com.fasterxml.jackson.annotation` imports may remain.
- [ ] `mvn -q dependency:tree -Dincludes=com.fasterxml.jackson.core,com.fasterxml.jackson.dataformat,com.fasterxml.jackson.datatype` over the reactor shows only `jackson-annotations`.
- [ ] The server, starter, quickstart-fixtures and quickstart-issuer poms no longer exclude `spring-boot-starter-jackson` and no longer declare `spring-boot-jackson2`.
- [ ] `Boot4RegressionGuardsTest` asserts that the MVC converters include Spring's Jackson 3 JSON converter and that no `MappingJackson2HttpMessageConverter` is present. It also asserts that `Class.forName("com.fasterxml.jackson.databind.ObjectMapper")` throws `ClassNotFoundException`. Its class Javadoc names J3-5 instead of D-139-A.
- [ ] J3-2: data-prism's mappers are built only by `DataPrismObjectMapper`, `SourceTree`, `RestSources`, `SecurityPolicy`, `PrivacyProfiles`, `ModelDescriptors` and `VocabularyRegistry`, each with a Jackson 3 builder held in a `private static final` (or package-private for `RestSources.YAML`, as today) field. `git grep -n -E 'tools\.jackson\.databind\.(ObjectMapper|json\.JsonMapper)' -- '*/src/main/*.java'` shows no `@Bean` method returning or accepting one.
- [ ] ArchUnit, rewritten: `onlyDesignatedClassesCreateMappers` (the name may change) fails for any class in `io.github.aindriub.dataprism..` outside the seven-class allowlist that does any of the following: calls a constructor of `tools.jackson.databind.ObjectMapper` or a subtype; calls a static `builder(..)` on `ObjectMapper` or a subtype; calls `build()` on `tools.jackson.databind.cfg.MapperBuilder` or a subtype; calls `ObjectMapper#rebuild()`. The YAML-reader no-write rule is ported to the Jackson 3 types and also covers `writer*` methods.
- [ ] Negative test: a fixture class outside the allowlist builds a mapper with `JsonMapper.builder().build()`, and a second fixture calls `rebuild().build()` on an existing mapper. A test evaluates the rule against each fixture and asserts it reports a violation, following the existing `EvaluationResult` pattern in `ArchitectureTest.assertViolationNaming` (ArchitectureTest.java:166-178). Deleting the builder clause from the rule makes that test fail; the hand-back quotes the run, and the edit is not committed.
- [ ] Every 166 characterisation test passes unchanged, with only import lines edited. Otherwise the hand-back has a "Behaviour differences for owner acceptance" table with one row per changed assertion: test, Jackson 2 observed, Jackson 3 observed, and whether data-prism's builder could pin the old behaviour. The commit changes that test's expected value and names the row. No difference is accepted silently.
- [ ] Every public signature in `data-prism-*/src/main` that changes type only from `com.fasterxml.jackson.*` to `tools.jackson.*` is listed in the hand-back, file:line, as old → new, and the list is checked against the inventory in 168. No other public signature changes in this task.
- [ ] A Jackson 3 checked-exception change does not widen a catch: no `catch (Exception` or `catch (RuntimeException` is introduced where a `JsonProcessingException` catch was, so refusal paths keep their codes. Grep for the diff's added `catch` lines; the hand-back lists each one.

## D-J3-2 — should the mapper rule also cover streaming JSON factories? (owner to decide; does not block this task)
`AuditJsonRenderer`, the checkpoint writer and `JwtDecoderSupport` use a Jackson
`JsonFactory` or generator directly, with no `ObjectMapper`. The current rule does not see them,
and 154 deliberately left `audit` without a databind ban.
- (a) Keep the rule about mappers only, as today. The port stays behaviour-neutral.
- (b) Add a second allowlist rule: only named classes may build a `tools.jackson.core.TokenStreamFactory` (`JsonFactory` and so on), so a hand-rolled generator that writes model-facing output is caught too. This would be a new small task after 167, owning ArchitectureTest.java (insertions only).
- **Recommendation: (a) now, and (b) as a 0.6.x follow-up.** None of these writers reaches the MCP transport, and widening a guard inside the port mixes two reviews.

## Out of scope
- Making `DataPrismObjectMapper.create()` or the tool constructors non-public, or any other J3-3 narrowing (168).
- Changing `docs/**`, `README.md` or the conventions or architecture wording (169).
- The Spring property and auto-configuration splits (158, 159), even where a file here will move later.
- Adding a rule for streaming `JsonFactory` or `JsonGenerator` construction (D-J3-2 was raised with this plan. If the owner picks the wider rule, it is a separate task). The audit renderer's generator stays allowed, as decided under 154.
- Upgrading any other dependency, or touching `.github/workflows/**` (its "single Jackson major" comment stays true).

## Outcome (2026-10-08, wave 5)
Merged onto `release/0.6.0-jackson3` (task branch head bfab230e, base 3dbabc4b). Tester PASS on JDK 21 (full reactor, 1455 tests, 0 failed, 0 skipped; `dependency:tree` shows only `jackson-annotations` left under `com.fasterxml.jackson`). The first review asked for changes because the `JwtDecoderSupport` catch missed `JacksonException`; fixed with tests, and the re-review approved. JDK 25 and container-smoke run in CI.

What landed:
- Jackson 3.1.5 throughout, built with builders, behind private mappers (J3-2).
- The enforcer follows J3-5, with a ban proof. `Boot4RegressionGuardsTest` is inverted.
- The ArchUnit mapper rule covers constructor, `builder()`, `build()` and `rebuild()`, with four negative fixtures.
- Jackson 3 defaults are pinned back to Jackson 2 behaviour, each with a test: `SORT_PROPERTIES_ALPHABETICALLY` off, `FAIL_ON_EMPTY_BEANS` on, `WRITE_ENUMS_USING_TO_STRING` off and `WRITE_DATES_AS_TIMESTAMPS` off on `DataPrismObjectMapper`; `STRIP_TRAILING_BIGDECIMAL_ZEROES` on in `SourceTree`; `FAIL_ON_TRAILING_TOKENS` and `FAIL_ON_NULL_FOR_PRIMITIVES` off in the YAML readers.
- The JSON goldens from 166 are unchanged.

Behaviour changes accepted or noted:
- YAML is now parsed as YAML 1.2 (D-167-1): `yes`/`no`/`on`/`off` are text and leading-zero numbers are decimal. Task 170 refuses these where they matter.
- `java.time` and `Optional` values are now serialised natively in `SourceTree`. The engine still classifies or refuses them.
- The tool `convertValue` failure message is now "the response could not be serialised".

Public signature changes (for 162): `DataPrismObjectMapper.create()` returns `JsonMapper`; `SourceTree.text` returns `StringNode`; the rest are `com.fasterxml` to `tools.jackson` identity moves on `SourceTree`, `ScrubResult`, `Generalizer`, `ContextResponse`, `ComparisonResponse.identity`, the `ObjectMapper` parameter of the two tool constructors, `LlmResponseValidator`/`RawValueLeakValidator`/`SensitivePatternValidator.validate` and `SensitiveDataScanner.scan`.

Owns WAIVER: seven test files were edited outside Owns, `asText` to `asString` only: `ConfiguredJsonDataSourceAdapterHttpTest`, `ConfiguredJsonSourceEndToEndTest`, `EmittedValuesTest`, `HazelcastStoredValueBoundaryTest`, `WorkedExampleTest`, `ConfiguredJsonNestedHttpTest`, `McpHttpEndToEndTest`.
