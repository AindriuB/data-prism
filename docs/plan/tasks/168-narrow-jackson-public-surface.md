# 168 — Stop exposing data-prism's `ObjectMapper` in public API, guard it with ArchUnit, and hand 162 the signature inventory

**Repo:** .
**Base:** branch from `origin/main` after 167 has merged into it. Owner decision D-J3-1 is
decided (option (a), 2026-10-08), so nothing blocks this task but 167.
**Depends on:** 167
**Owns:**
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/{DataPrismObjectMapper,DataPrismMcpServer,GetEntityContextTool,CompareEntitySourcesTool}.java
- data-prism-mcp/src/test/** (tool construction and `DataPrismObjectMapper` call sites only)
- data-prism-integration-tests/src/test/** (tool construction and `DataPrismObjectMapper` call sites only, including the 166 characterisation tests' tool construction. Their expected bytes do not change.)
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/CorrelationConfigurationTest.java (the `DataPrismObjectMapper.create()` call at :291 only)
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ArchitectureTest.java (insertions only: one rule, its test and its negative test)
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/*PublicMapperFixture.java (new)

## Goal
Owner decision J3-3 (C). Jackson 3 tree types stay public where they carry real data; 167 already
made that change. Data-prism's own `ObjectMapper` must stop being reachable from public API:
`DataPrismObjectMapper.create()` and the public tool constructors that accept a mapper. This
reinforces J3-2, because an adapter author can bring their own mapper but can never obtain or
substitute data-prism's. An ArchUnit rule keeps the surface closed. The task also checks and
completes the public-signature inventory that 162's migration page lists.

## Context
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismObjectMapper.java:21-33. `public final class` with `public static ObjectMapper create()`.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java:115-122 and CompareEntitySourcesTool.java:114-121. Public constructors take `ObjectMapper mapper` as their fourth parameter.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java:100-125,160-170. The only production caller. It builds the mapper once and passes the same instance to both tools and to `JacksonMcpJsonMapper`.
- Callers outside `mcp`: 10 test files in data-prism-integration-tests (for example EndToEndTest.java:81 and McpHttpEndToEndTest.java:315, which use it to `readTree` a response body) and CorrelationConfigurationTest.java:291 (`createObjectNode()`). Test code may build its own test-local `JsonMapper`. ArchitectureTest imports with `DO_NOT_INCLUDE_TESTS`, so the mapper rule does not apply to tests.
- data-prism-architecture/.../ArchitectureTest.java:166-178. The negative-test pattern.

## D-J3-1 — how the tool constructors stop accepting data-prism's mapper (DECIDED 2026-10-08: option (a))
- (a) Remove the `ObjectMapper mapper` parameter from both public constructors. Each tool takes
  its mapper from package-private `DataPrismObjectMapper.create()`. `DataPrismMcpServer` passes
  one instance to `JacksonMcpJsonMapper` and to both tools through a package-private constructor,
  so tools and transport still share one mapper. Tests keep constructing tools directly.
- (b) Make both constructors package-private and add no replacement, so tools are built only
  through `DataPrismMcpServer`. The ~10 integration tests that construct tools directly must be
  rewritten to go through the server factory or a test-only helper in `mcp`'s test sources,
  which they cannot reach from another module.
- (c) Keep the public constructors but change the parameter to a data-prism-owned opaque type,
  such as a `ToolSerialiser` with no Jackson in its signature.
- **Decision: (a)**, chosen by the owner on 2026-10-08, matching the planner's recommendation. The reasons follow. It meets J3-3's intent: no public method accepts the mapper, and the
  shared-instance invariant stays enforced inside the package. It keeps test churn to deleting
  one argument, and adds no new public type. (b) is the literal reading of "stop being public"
  but costs the most rewriting and gives no extra protection. (c) adds a type only to carry one
  that is already hidden.

## Acceptance (written for D-J3-1 (a), which the owner chose)
- [ ] `DataPrismObjectMapper` is a package-private `final class` and `create()` is package-private. `git grep -n 'DataPrismObjectMapper' -- '*.java' ':!data-prism-mcp/src/main/*'` shows only the ArchitectureTest allowlist string.
- [ ] Neither `GetEntityContextTool` nor `CompareEntitySourcesTool` has a public or protected constructor or method with a `tools.jackson.databind.ObjectMapper` parameter. `DataPrismMcpServer` still hands one mapper instance to `JacksonMcpJsonMapper` and both tools. A test in `data-prism-mcp/src/test` asserts that sharing by reflection or by a package-private accessor.
- [ ] New ArchUnit rule `noPublicApiExposesAnObjectMapper`: no public or protected method, constructor or field of a public class in `io.github.aindriub.dataprism..` has a parameter, return or field type assignable to `tools.jackson.databind.ObjectMapper`, `tools.jackson.databind.cfg.MapperBuilder` or `tools.jackson.databind.ObjectWriter`. A fixture `*PublicMapperFixture.java` with a public method returning a `JsonMapper` makes the rule's negative test report a violation naming the fixture.
- [ ] `mvn -B --no-transfer-progress verify` is green, and the 166 characterisation tests pass with unchanged expected bytes.
- [ ] Hand-back: the inventory below checked against `git diff <pre-167 sha>..HEAD` over `data-prism-*/src/main`, restricted to public and protected declarations. It has one row per change (old → new, final FQCN after 156/157), corrected and completed where the planner's list is wrong. 162 copies it into docs/migration-0.6.md.

## Public signature inventory for 162 (planner's list from 9a00c04e. 168 verifies it.)
Jackson type moves made by 167 (`com.fasterxml.jackson.databind…` → `tools.jackson.databind…`; packages for `core` types are wherever 156 put them):
1. `ScrubResult`: record component and accessor `tree()` of type `ObjectNode`; canonical constructor; constructor `ScrubResult(ObjectNode, Set<String>)`.
2. `SourceTree.of(Object)` returns `JsonNode`; `SourceTree.newObject()` returns `ObjectNode`; `SourceTree.newArray()` returns `ArrayNode`.
3. `core.policy.Generalizer.generalise(JsonNode, GeneralizationRule, String)`.
4. `validation.LlmResponseValidator.validate(JsonNode, …)`, an interface method, so every implementor must change; `RawValueLeakValidator.validate(JsonNode, …)`; `SensitivePatternValidator.validate(JsonNode, …)`; `SensitiveDataScanner.scan(JsonNode)` and `scan(JsonNode, Predicate<String>)`.
5. `orchestration.ContextResponse`: record component and accessor `entity()` of type `ObjectNode`; the canonical constructor and the two public convenience constructors (ContextResponse.java:51-89).
6. `mcp.CompareEntitySourcesTool.ComparisonResponse`: record component and accessor `identity()` of type `ObjectNode`.
7. Any `throws JsonProcessingException` that 167 removed. None was found at 9a00c04e; confirm.

Narrowing made by this task:
8. `mcp.DataPrismObjectMapper`: no longer public, and neither is `create()`.
9. `mcp.GetEntityContextTool` and `mcp.CompareEntitySourcesTool` public constructors: the `ObjectMapper mapper` parameter is removed (per D-J3-1).

Dependency changes for consumers (from 167): `mcp-json-jackson2` → `mcp-json-jackson3`, and `spring-boot-jackson2` → `spring-boot-starter-jackson`. Jackson 2 is banned except `jackson-annotations`.

## Out of scope
- Any further Jackson port work, or changing tree types back to non-Jackson types. J3-3 keeps them public.
- docs/migration-0.6.md and CHANGELOG.md (162 writes them from this hand-back).
- docs/architecture.md and docs/conventions.md (169).
- An `ObjectMapper` that an adapter author creates in their own code. J3-2 permits it, and this rule covers only `io.github.aindriub.dataprism..`.
