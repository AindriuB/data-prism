# 254 — Wire the new limits, the catalog, list paging and the link key into Spring, and close the raw FactoryBean preflight gap

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** the 0.8 integration branch once 211, 222, 242, 246, 247, 249 and 250 have merged
**Depends on:** 211 (serialised with 210, 208 and 211 on the shared Spring files below), 222 (sealed-cluster plan; 222 edits `DataPrismPropertiesValidator.java`, `frozen-property-names.txt`, `additional-spring-configuration-metadata.json` and `docs/configuration.md` first), 242 (link codec), 246 (new limits), 247 (labels, real names, `OrchestrationOptions`), 249 (configured-source descriptions and hosts), 250 (catalog tools)
**Decisions:** OD-08-3 (`PT10S`, 50,000, `scope-read-budget` 100), OD-08-9 (a, `MISSING_PAGING_KEY`) and OD-08-18 (a, `dataprism.links.keys.<key-id>.environment-variable` or `.provider-reference`) are decided 2026-10-20.
**Serialised with:** 222 (before this task, by the edge above), 255 (after), and 230 (after 255, because 230 depends on 255; no edge from 222 or 230 to this task, which would close the cycle 254, 246, 226, 223, 222), on `DataPrismPropertiesValidator.java`, `PrivacyExtensionPoints.java`, `additional-spring-configuration-metadata.json`, `frozen-property-names.txt`, `bean-inventory/*.txt`, `docs/configuration.md`
**Wave:** 9 (re-planned 2026-10-23 on the open 0.8 graph: 203, 206, 207, 208, 210 and 211 have not landed and are placed by their edges; 209 has landed)
**Owns:**
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/LimitsProperties.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/CatalogProperties.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PagingProperties.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/LinksProperties.java *(new: the key fields only; 255 adds the rest)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismProperties.java *(the three new groups only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/CatalogWiring.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/LinkKeyResolution.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/OrchestrationWiring.java
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/McpTransportWiring.java *(the catalog arguments of `dataPrismHttpTransport` only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/Preflights.java *(`dataPrismPrivacyPolicyResolverPreflight` only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(the limits checks and new `validateCatalog` and `validatePaging` methods only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/PrivacyExtensionPoints.java *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertions only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/configuration-metadata-gaps.txt *(insertions only, for map sub-keys)*
- data-prism-spring-boot-autoconfigure/src/test/resources/bean-inventory/*.txt *(regenerated; only lines for beans this task adds)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/LimitsConfigurationTest.java, CatalogConfigurationTest.java, PagingConfigurationTest.java, LinkKeyConfigurationTest.java, FactoryBeanPreflightTest.java *(new)*
- docs/configuration.md *(the rows for the keys this task adds and their startup codes, and the `CLUSTER_KEY_SHARED` / `NAME_KEY_SHARED` rows of the cluster-key refusal-order table, only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterKeyResolution.java *(the shared-key check only: also compare the cluster keys and the name key against the link key this task resolves (page links), by key id and by material; added 2026-10-23 from 222's Outcome item (d))*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ClusterKeyResolutionTest.java *(insertions only: the rows for the link key this task resolves (page links))*
- docs/plan/design/contract-register.md *(the `CLUSTER_KEY_SHARED` and `NAME_KEY_SHARED` rows only)*
- docs/plan/design/cluster-security.md *(the `CLUSTER_KEY_SHARED` and `NAME_KEY_SHARED` rows of the code table only)*

## Goal
Expose the 0.8 core's new configuration through `dataprism.*` and start the catalog: the request deadline and per-scope total of 246, `dataprism.catalog.unselected-source-limit` and `real-names-scopes` (OD-C7, OD-C3), `dataprism.paging.*` (OD-B1), and the link key that page links need even while links are off (OD-B2 (a)). Build the `SourceCatalog` at startup and refuse to start on a description that breaks the catalog rules. Register `list_sources` and `describe_source` on the HTTP transport. Close the 0.7 review finding that a raw `FactoryBean` with `allowEagerInit=false` escapes both the `RequestLimits` and the `PrivacyPolicyResolver` guards.

## Context
- docs/plan/design/catalog.md section 6 ("The estate-size limit") and section 8 item 16; docs/plan/design/batch-and-bulk-follow.md A.8 and OD-B2 (a); docs/plan/design/sealed-links.md "Configuration keys" (`key-id` rules) and register OD-200-10 (the `LINK_KEY_*` family).
- docs/plan/PLAN.md, "0.7.0 follow-ups found in review": the raw `FactoryBean` finding.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/OrchestrationWiring.java:40-74 (`dataPrismRequestLimitsPreflight`) and Preflights.java (`dataPrismPrivacyPolicyResolverPreflight`): the two guards.
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HmacKeyReferenceResolver.java and PropertiesValidation.java:129-132: the key-reference resolver to reuse and the 32-byte rule.
- ConfigurationMetadataDocumentedTest, PropertyNamesFrozenTest, AutoConfiguredBeanClassificationTest, AutoConfiguredBeanInventoryTest: the guards every new key and bean must satisfy.
- From 222 and 242 (2026-10-22): link keys must be added to `ClusterKeyResolution`'s shared-key check (`CLUSTER_KEY_SHARED`, `NAME_KEY_SHARED`), which today compares only the privacy HMAC key. Add `ClusterKeyResolution.java` and those two register rows to this brief's Owns, or leave the extension to 255 and say so in the Outcome. Done 2026-10-23 (planner audit): both are in the Owns, with the test and the matching rows of `cluster-security.md` and `docs/configuration.md`. 211 extends the check for the audit key first; this task adds the link key it resolves, and 255 any link key it adds.
- From the external review of PR #154 (2026-10-22, 242's Outcome): `LinkKeys` construction needs the pseudonymisation key resolved through the same provider as the link keys. A null id refuses `LINK_KEY_SHARED` and an unresolvable one `LINK_KEY_UNRESOLVED`, so wire both ids.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] The two limit keys OD-08-3 names bind, with the decided defaults, and reach `RequestLimits`. A non-positive value, or a deadline below `per-source-timeout`, refuses startup with `INVALID_REQUEST_LIMITS`; the message names the property, not the value.
- [ ] `dataprism.catalog.unselected-source-limit` (default 8) and `dataprism.catalog.real-names-scopes` (default empty) bind and reach `OrchestrationOptions`. A non-positive limit, a limit above `dataprism.limits.max-sources`, and a malformed scope pattern refuse with `INVALID_CATALOG_SETTING`.
- [ ] `dataprism.paging.list-sources.page-size` (25), `.max-page-size` (100) and `dataprism.paging.lifetime` (`PT15M`) bind. A non-positive value or a page size above the maximum refuses with `INVALID_PAGING_SETTING`.
- [ ] The link key binds as OD-08-18 decides. An unresolvable reference refuses with `LINK_KEY_UNRESOLVED`, material under 32 bytes with `LINK_KEY_WEAK`, and the same id or the same bytes (compared in constant time) as the pseudonymisation key with `LINK_KEY_SHARED`. No message contains a reference value or key byte.
- [ ] When the registered estate is larger than the default page size and no link key is configured, startup refuses with the code OD-08-9 decides; at exactly the default page size it starts. Both edges are tested.
- [ ] `dataPrismSourceCatalog` is built once from every `DataSourceAdapter` bean and every configured source's base-url host. A description that breaks a rule refuses startup with `INVALID_SOURCE_DESCRIPTION` or `SOURCE_DESCRIPTION_LEAKS_NAME`; a bad chainable declaration with `INVALID_CHAINABLE_FIELD`. One INFO line gives the count and the real names of sources using the generated fallback; one WARN line gives only the count of non-conforming source names. Neither line contains description text (scanned).
- [ ] `list_sources` and `describe_source` are in the HTTP transport's `tools/list`. The new beans have `PrivacyExtensionPoints` rows; the catalog, catalog-query and link-codec beans are `PRIVACY_CRITICAL` with `COMPETING_BEAN_REFUSAL`, and `PrivacyExtensionPointsTest` proves the guard.
- [ ] The `RequestLimits` and `PrivacyPolicyResolver` guards refuse a competing bean registered as a raw `FactoryBean` (no generic type) with `allowEagerInit=false`; `FactoryBeanPreflightTest` shows both escaping on the base commit and refused after.
- [ ] `docs/configuration.md` has a row for each new key with its default and every refusal code above.
- [ ] `ConfigurationMetadataDocumentedTest`, `PropertyNamesFrozenTest`, `AutoConfiguredBeanClassificationTest`, `AutoConfiguredBeanInventoryTest` and `PrivacyExtensionPointsTest` pass, and the diffs to their checked-in lists contain only this task's lines.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- `dataprism.links.enabled` and the other link, follow and key-resolver keys (255).
- The `LinkAttemptLimiter` bean, its argument to `list_sources` and the `MISSING_SHARED_LINK_LIMITER` refusal, which applies when a link key is configured even with links off (255).
- Catalog and link gauges (255).
- Every `docs/` file other than `docs/configuration.md` (261).
