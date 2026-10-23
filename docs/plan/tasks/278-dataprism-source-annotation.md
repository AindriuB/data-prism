# 278 — Generate source adapters from `@DataPrismSource` on a record or a Spring HTTP interface

**Repo:** `.`
**Release:** 0.9.0 (adds public API; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once 275 and 277 have merged
**Depends on:** 277 (owns the connector's `META-INF` files and proves that configured sources and Java adapters coexist, which the generated adapters must too), 275 (both edit `freeze/codes.txt` and the contract register; 275's grammar is what this task's `docs/extending.md` line 46 correction states), 248, 270 and 271 (0.8 and PR #161: a generated adapter's record is described and checked by the same model rules as a hand-written one)
**Wave:** 17 (0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** the two routes, "the base URL and timeout stay in `dataprism.sources.<name>` YAML", "the adapter is generated through `RestClient`" and "a hand-written `DataSourceAdapter` is the advanced route" are owner decisions already made in the spec, section 2. Decided at filing (low stakes) are discovery, the interface method shape and the package name (below).
**Owns:**
- data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/DataPrismSource.java *(new)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/declared/** *(new)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/declared/** *(new)*
- data-prism-connectors-rest/src/test/resources/declared/** *(new)*
- data-prism-connectors-rest/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports, META-INF/spring.factories *(insertions only)*
- data-prism-connectors-rest/pom.xml *(only if a dependency is missing, for example for `HttpServiceProxyFactory`)*
- data-prism-architecture/src/test/resources/freeze/codes.txt *(only the codes this task adds)*
- data-prism-architecture/src/test/java/io/github/aindriub/dataprism/architecture/DeclaredSourcesArchitectureTest.java *(new)*
- docs/plan/design/contract-register.md *(insertions only: rows for the codes this task adds, and the annotation's row in the public-API section)*
- docs/extending.md *(a new "`@DataPrismSource`" section first; the hand-written `DataSourceAdapter` sections reframed as the advanced route; the grammar statement at :46 corrected to 275's grammar)*

## Goal
A Java developer should get a source adapter from one annotation and write no adapter class. On an `@LlmExposedModel` record, `@DataPrismSource(name = "loans", path = "/loans/{subject}")` means "GET this path and read this record". On a Spring HTTP interface (`@GetExchange` or `@PostExchange` returning the record), it means "call through this interface", which covers POST lookups, headers and query parameters. Either way the adapter is generated over Spring's `RestClient` from `dataprism.sources.<name>` (base URL, timeout and the existing transport settings), and it passes through the privacy engine like any other adapter.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 2, "Second route: Java".
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapter.java: the behaviour every generated adapter must match. The subject is one encoded URI variable (no traversal, no query or fragment injection); a 404 is "no data" and does not count against the breaker; other errors propagate to the fan-out; the optional outbound correlation header. Reuse it for the record route rather than copying it.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/SourceProperties.java and `DataPrismPropertiesValidator.validateSources` (:692-710): `dataprism.sources.<name>` already binds and validates `base-url`, `timeout`, `service-credential-reference` and `mtls`. Every one of these settings must either take effect on the generated adapter or refuse startup. None may be silently ignored. `MutualTlsRestClients.java` builds the mTLS client.
- DataPrismContractValidator.java:55-75: the check that configured source names and supplied adapters match (`MISSING_SOURCE_ADAPTER`). Generated adapters are supplied adapters.
- Registration timing: `DataPrismAutoConfiguration` has `@ConditionalOnBean(DataSourceAdapter.class)` beans (see `ConfiguredJsonSourcesAutoConfiguration.java:58-71`). Generated adapters must be visible to that condition. A test with only generated adapters, and no other adapter, proves it.
- 270: a record held by a generated adapter goes through `SourceModels.require` like any other (`DataPrismContractValidator.java:51`), so its shape refusals apply unchanged.
- `data-prism-annotations` has no Spring dependency and must keep none. The annotation's attributes are plain `String`s.
- Decided at filing (low stakes):
  - **Discovery:** scan the application's auto-configuration packages (the `@SpringBootApplication` package and below), as Spring Data does for repositories. There is no annotation processor index and no new scan annotation.
  - **Record route:** `name` is required. `path` is required on a record, must contain exactly one `{subject}` segment, and must not set a scheme, host, query or fragment. The record must be an `@LlmExposedModel` record.
  - **Interface route:** `name` is required and `path` is not allowed. The interface declares exactly one HTTP exchange method. That method has exactly one parameter, which receives the subject id (as a path variable, query parameter, header or body), and it returns the `@LlmExposedModel` record. `ResponseEntity`, `Optional` and reactive return types are refused, and so are default methods that call the exchange method.
  - **Package:** `connectors.rest.declared`.
- Refusals: coded, at startup, naming the class and the source name and never a configured value. Examples: a missing `dataprism.sources.<name>`, a bad `path`, an interface of the wrong shape, two declarations with the same name, a declared name that clashes with a configured JSON source or a hand-written adapter. Use an existing code where the meaning matches (`INVALID_SOURCE_URL`, `INVALID_SOURCE_TIMEOUT`, `MISSING_SOURCE_ADAPTER`). Add at most two new codes. 279 adds the hint and changes the exception type.
- docs/extending.md:40-50 still says field keys are bare names. Correct it from 275's Outcome.
- Fixture values are invented. Repository rule 3, and the owner's rule to use invented neutral domains.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] Record route: an application with one annotated record and `dataprism.sources.loans.base-url` and `timeout` set calls `GET <base-url>/loans/<subject>` against an in-test HTTP server, and `get_entity_context` returns the record scrubbed under its annotations. A subject of `../../admin?x=1#f` reaches the server as one encoded path segment. A 404 gives the source a no-data status and no breaker failure.
- [ ] Interface route: an annotated interface with `@PostExchange("/loans/lookup")` taking `@RequestBody LoanQuery` (the subject id wrapped in a record), and one with `@GetExchange("/loans")` taking `@RequestParam("id") String`, each produce the request the interface declares, including a static header from the exchange annotation. Each returns the scrubbed record.
- [ ] Settings: one test per `dataprism.sources.<name>` setting (`timeout`, `mtls`, `service-credential-reference`) shows that it takes effect on the generated adapter, or that startup refuses with a code naming it. A slow server past `timeout` gives a timed-out source status.
- [ ] Each refusal in the Context's list refuses at startup with its code, and the message contains no configured value. The new codes are in the register and `freeze/codes.txt`.
- [ ] Generated adapters satisfy `@ConditionalOnBean(DataSourceAdapter.class)` with no other adapter present. They coexist with a configured JSON source and a hand-written adapter in one application (277's mixed harness), and each answers.
- [ ] Repository rule 1: `DeclaredSourcesArchitectureTest` shows that `connectors.rest.declared` has no dependency on `data-prism-mcp`, so a generated adapter can reach the MCP layer only through the orchestrator and the privacy engine.
- [ ] `data-prism-annotations` still has no non-JDK dependency (its existing guard passes).
- [ ] `docs/extending.md` opens with the `@DataPrismSource` section, giving both routes with a complete example each and every refusal code. It presents a hand-written `DataSourceAdapter` as the advanced route, and :46 states 275's grammar.
- [ ] `./mvnw -B -pl data-prism-annotations,data-prism-connectors-rest,data-prism-architecture -am verify` passes, then `./mvnw -B verify`.
- [ ] The Outcome lists, for 281's CHANGELOG: the annotation, its two routes and its refusal codes.

## Out of scope
- A compile-time check of `@DataPrismSource` in `data-prism-processor`. Name it in the Outcome as a follow-up.
- Reactive (`WebClient`) interfaces, and more than one exchange method per interface.
- Changes to `RestDataSourceAdapter`'s behaviour, or to `dataprism.sources.<name>` property names.
- The failure analyser's rendering (279), and the examples (280).
