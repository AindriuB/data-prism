# 280 — Add three small runnable starter examples, built and tested in the reactor

**Repo:** `.`
**Release:** 0.9.0
**Base branch:** `origin/main` once 276, 277, 278 and 279 have merged
**Depends on:** 279 (each example's refusal test shows the new refusal text), 278 (`@DataPrismSource`, both routes), 277 (configured JSON sources in an embedded application; OD-277-1 decides the examples' dependencies), 276 (auto-configured chain, extractor and metrics, so the examples declare no such beans), 275 (the path grammar the YAML example uses), 273 (the defaults and derived settings the examples rely on). If the module-merge brief (PLAN.md:62) is filed, this task depends on it: both edit the root `pom.xml` and `data-prism-architecture/pom.xml`.
**Wave:** 19 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** three apps, built and tested in CI on every PR against the reactor and never published, are owner decisions already made in the spec, section 4 (owner choice "a"). Reactor modules with `skipPublishing`, the directory names and the package root are decided at filing (below).
**Owns:**
- examples/starter-yaml/** *(new)*
- examples/starter-record/** *(new)*
- examples/starter-http-interface/** *(new)*
- pom.xml *(the `<modules>` list, three insertions only)*
- data-prism-architecture/pom.xml *(three test-scoped dependency insertions only)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/ModuleGraph.java, ArchitectureCoverageTest.java *(the module-path-to-artifact-id mapping only, if a module path under `examples/` needs one)*
- .github/workflows/publish-central.yml *(the comment at about :199-202 that counts the non-deployable modules, only)*

## Goal
Give a developer three tiny applications to copy, each one protected endpoint and nothing else: one that describes its API in YAML only, one with a `@DataPrismSource` record, and one with a `@DataPrismSource` Spring HTTP interface. Each runs from its own directory, depends only on the starter, and has a test that the normal reactor build (`./mvnw -B verify`, as `build.yml` runs it on every PR) executes. None is ever published to Central.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 4 ("`examples/`") and section 5 (the Central file-count limit: examples are never published).
- Decided at filing (low stakes):
  - Each example is a reactor module listed in the root `pom.xml`, with the root as parent. It sets `maven.deploy.skip`, `skipPublishing`, `maven.source.skip`, `maven.javadoc.skip` and `gpg.skip` to `true`, as `data-prism-quickstart-fixtures/pom.xml:23-31` does.
  - Directories `examples/starter-yaml`, `examples/starter-record` and `examples/starter-http-interface`. Artifact ids `data-prism-example-starter-yaml`, `data-prism-example-starter-record` and `data-prism-example-starter-http-interface`.
  - Packages under `io.github.aindriub.dataprism.example..`, which `ArchitectureTest` (:469, :521-564) already treats as application code that nothing may depend on.
- `ArchitectureCoverageTest` and `ModuleGraph.declaredModules` (`ModuleGraph.java:57-65`) require every module with main code to be a dependency of `data-prism-architecture`. They compare module entries with artifact ids, so a module path under `examples/` needs a mapping. This is a shared exhaustiveness guard; extend it, do not exclude the examples from it.
- `.github/workflows/build.yml:35` runs `./mvnw -B verify` over the whole reactor, so no workflow change is needed for CI.
- What each example shows (one source and one endpoint each; invented neutral domain such as library loans; no owner domain; repository rule 3):
  - `starter-yaml`: a `json-sources:` file using 275's path grammar with one nested object and one array, plus `application.yaml`. No Java beyond the `@SpringBootApplication` class.
  - `starter-record`: one `@LlmExposedModel` record with `@DataPrismSource(name, path)`.
  - `starter-http-interface`: one interface with `@DataPrismSource` and a `@PostExchange` lookup.
  - Each `application.yaml` sets only the still-required settings (spec section 1) plus the source's base URL and timeout. It sets no defaulted property, and it marks every setting `# required` or `# optional` in a comment.
- Each example's test starts the application against an in-test upstream HTTP server and an in-test JWT issuer (JWKS over loopback, as the existing local fixtures allow). It calls `get_entity_context` over the real MCP HTTP transport, and asserts that the response is scrubbed and that no raw upstream value appears in the response or the captured log. The test may not replace any Data Prism bean: the point is that the auto-configuration does the work.
- Key material (found in the external review of PR #162): the HMAC key and the JWT signing key the examples' tests use are generated at test start (`SecureRandom`, and an in-memory RSA or EC key pair). They are handed to the application through the environment-variable reference or a temporary directory that the test deletes. No key, keystore, PEM or JWK is committed. 282's template shows key references only, never key material.
- 282 writes the getting-started guide around `starter-yaml`, and 283 generates the public template repository from it. Keep it free of anything that only works inside the reactor, apart from the parent pom.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file. Do not run `mvn install`.

## Acceptance
- [ ] Each example has `README.md` (run it, what it shows, which settings are required), `pom.xml`, `application.yaml`, the main class, the source declaration and one test class. The main code of each is under 60 lines, excluding comments.
- [ ] Each example's test passes in `./mvnw -B verify` from the repository root. Each also asserts one startup refusal: with the HMAC key reference removed, the refusal text names `dataprism.privacy.hmac-key.key-id`, an example and the docs URL (279).
- [ ] No example declares a `SecurityFilterChain`, `JwtDecoder`, `McpTransportContextExtractor`, `PrivacyMetrics`, `IdentityResolver` or `DataSourceAdapter` bean. A grep in the Outcome shows this.
- [ ] `./mvnw -B -Prelease -DskipTests -Dgpg.skip package` stages no example artifact. The Outcome shows the staged artifact list, and the published count is unchanged from before this task.
- [ ] `ArchitectureCoverageTest` and `ArchitectureTest` pass with the three modules included in the imported graph.
- [ ] Each example's dependencies are the starter (plus the connector only if OD-277-1 is (b)) and test-scoped test libraries. `./mvnw -B -pl examples/starter-yaml dependency:tree` in the Outcome shows this.
- [ ] No key material is committed. One shared `KeyMaterialNotCommittedTest`, in `examples/starter-yaml`'s tests only (not copied into the other two), walks the whole `examples/` directory from the repository root and fails on any file with a `.pem`, `.key`, `.p12`, `.jks` or `.jwk` extension, or whose content matches `-----BEGIN` or a JWK `"d":` private member. That scope covers `examples/starter-application-template.yaml` once 282 adds it. The test is shown to fail once with a planted PEM header in a recorded run.
- [ ] `./mvnw -B verify` passes.

## Out of scope
- The getting-started guide and the `application.yaml` template (282).
- The public template repository (283).
- Publishing any example, and any GitHub workflow beyond the one comment.
- A Docker image or Compose file for the examples.
- Public-API catalogues. The owner wants them later as separate repositories; this is recorded in PLAN.md "Someday".
