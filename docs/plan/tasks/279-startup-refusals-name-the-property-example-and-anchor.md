# 279 — Make every startup refusal name the property to set, an example value and the doc anchor

**Repo:** `.`
**Release:** 0.9.0 (changes the operator-facing refusal text; must land before the 0.9.0 freeze)
**Base branch:** `origin/main` once 273, 275, 276, 277 and 278 have merged
**Depends on:** 278 (its refusal sites in `connectors.rest.declared`; both edit `freeze/codes.txt` and the register), 275 (its coded refusals in `ConfiguredJsonSources`), 273 and 276 (both edit `docs/configuration.md`; their codes and derived properties are among the ones this task hints), 277 (owns `ConfiguredJsonSourcesInitializer`, which may need to wrap a coded refusal), 261 (0.8: the startup-refusal index section of `docs/configuration.md` that the anchors point into), 260 (0.8: `freeze/codes.txt` and its "startup" surface, which the coverage test reads)
**Wave:** 19 (moved from 18 on 2026-10-24, with 276 and 278; 0.9 starter onboarding; filed 2026-10-23 from `docs/plan/specs/2026-10-23-starter-onboarding.md`)
**Decisions:** what a refusal must say, and that it never echoes a secret, are owner decisions already made in the spec, section 3. The link base is decided at filing (below).
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismConfigurationException.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismConfigurationFailureAnalyzer.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/RefusalGuide.java *(new; name may differ: code to property, example value and anchor)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/spring.factories *(insertions only: a second analyser, if one is needed)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/DataPrismConfigurationFailureAnalyzerTest.java
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/RefusalGuideCoverageTest.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/refusal/StartupRefusalException.java *(new; name may differ: a coded startup refusal that modules without the auto-configuration can throw)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/RestSource.java, RestSources.java, OutboundCorrelationHeader.java *(refusal sites only)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSources.java, ConfiguredJsonSourcesInitializer.java *(refusal sites and the exception type only)*
- data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/declared/** *(refusal sites and the exception type only)*
- data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/**Test.java *(assertions on the refusal exception type only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ConfigurationRefusalMessageIT.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/StarterStartupFailureTest.java *(assertions on the rendered text only)*
- data-prism-architecture/src/test/resources/freeze/codes.txt *(only codes this task adds by converting a raw refusal)*
- docs/configuration.md *(the startup-refusal index section only: one anchor per startup code, with the property and an example)*
- docs/plan/design/contract-register.md *(insertions only: rows for codes this task adds, and a note on the rendered refusal format in the startup section)*
- .github/workflows/publish-image.yml *(the refusal grep at :182-190, only if the rendered line it matches changes)*

## Goal
Today a refused startup prints the code and "see docs/configuration.md" (`DataPrismConfigurationFailureAnalyzer.java`). A developer of any level should instead read which property to set, an example value, and a link to the exact doc section, for every startup refusal of the server and the starter, with no secret ever echoed. Raw `IllegalArgumentException` refusals left in the connector become coded refusals that reach the same analyser.

## Context
- docs/plan/specs/2026-10-23-starter-onboarding.md, section 3 (binding).
- DataPrismConfigurationFailureAnalyzer.java (whole file) and DataPrismConfigurationException.java: only `code()` is rendered, because `detail` can carry a configured value. Keep that rule. The new text comes from the code, plus at most a property name that the throw site passes as a separate, non-secret field. It never comes from `detail`.
- `freeze/codes.txt` (260) classifies every code by surface. The coverage test takes the "startup" surface as the set of codes that need a hint, so a startup code added later fails the build until it has one. This test is a shared exhaustiveness guard. Every later task that adds a startup code must own `RefusalGuide.java` (insertions only) and add its hint.
- Hints:
  - Property: the full key, with `<name>` for map entries.
  - Example: a value that would pass. For a secret-bearing property, show the reference form and never a literal secret.
  - Anchor: a URL under the published site.
  - A code that several properties can raise (for example `MISSING_CALLER_CLAIM`) names the property the throw site passed, or else lists them all.
- Decided at filing (low stakes): links use `site_url` from `mkdocs.yml:7` (`https://aindriub.github.io/data-prism/`) plus `configuration/#<anchor>`. The site is not versioned, so a link can show a newer page than the running version. The Outcome names this as a follow-up for versioned docs.
- Raw refusals still left after 275 and 278: `RestSource.java:45-66`, `RestSources`, `OutboundCorrelationHeader.java:50`, and any in `ConfiguredJsonSourcesInitializer`. `git grep -n 'new IllegalArgumentException(' -- data-prism-connectors-rest/src/main` lists them. A refusal there must reach the analyser as coded. The connector has no dependency on the auto-configuration, which is why a core type exists. Adding a public type to `core.refusal` is allowed; adding a module is not.
- 273 and 276 introduced derived settings, for example the JWT settings from `spring.security.oauth2.resourceserver.jwt.*`. Their hints name the Spring key.
- `.github/workflows/publish-image.yml:167-190` greps the image's no-configuration output for `DataPrismConfigurationException: MISSING_`. Keep that literal, or update the grep in the same commit.
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `RefusalGuideCoverageTest`: every code on the startup surface of `freeze/codes.txt` has a hint with a property, an example and an anchor. Each anchor exists as a heading or explicit anchor in `docs/configuration.md`. Each property is in the configuration metadata, or is a `json-sources:` key, a `spring.*` key or a bean type, and the hint says which. Shown to fail once by adding a made-up startup code.
- [ ] Analyser output for `MISSING_HMAC_KEY_REFERENCE`, `MISSING_AUDIT_SINK`, a 273 derived-setting refusal and a 275 grammar refusal each contain the code, the property, the example and the full URL. A table-driven test covers every startup code.
- [ ] Never echo: for each code whose throw site passes a configured value in `detail`, a test sets a sentinel value and asserts that the sentinel is absent from the analyser output and from the logged failure.
- [ ] `git grep -n 'new IllegalArgumentException(' -- data-prism-connectors-rest/src/main` returns only argument checks that cannot be reached from configuration, each listed in the Outcome with the reason. A missing source timeout in a `json-sources:` file is rendered by the analyser like any other refusal; `ConfigurationRefusalMessageIT` shows it on the server.
- [ ] `docs/configuration.md`'s startup-refusal index has one anchor per startup code, giving its property and example and matching the hint.
- [ ] The publish-image grep still matches the image's no-configuration output, or is updated in the same commit.
- [ ] `./mvnw -B -pl data-prism-core,data-prism-connectors-rest,data-prism-spring-boot-autoconfigure,data-prism-server,data-prism-integration-tests,data-prism-architecture -am verify` passes, then `./mvnw -B verify`.
- [ ] The Outcome gives, for 281's CHANGELOG, the new refusal format and the exception-type change for anyone catching `IllegalArgumentException` from the connector.

## Out of scope
- Changing any refusal code's name or meaning, other than giving a raw refusal its first code.
- Runtime (call-time) refusals sent to MCP clients. This task covers startup only.
- Versioned documentation.
- Splitting the Required column (281).
