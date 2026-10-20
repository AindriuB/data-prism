# 247 — Resolve source labels per scope, show real names only when scope and caller allow, and target chosen sources

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 239, 246 and 206 have merged
**Depends on:** 239 (`SourceCatalog`, `SourceVisibility`), 246 (both edit `DefaultContextOrchestrator` and its call sites), 206 (206 owns new test files under `data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/**`)
**Blocked on:** OD-08-6 (which tools record the `names` key)
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceAliasing.java
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/SourceLabels.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/RealNamesPolicy.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/SourceSelection.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/OrchestrationOptions.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/DefaultContextOrchestrator.java *(constructors, source selection before the reservation, the `names` and `catalog` dispositions)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextRequest.java *(the `targetSources` component and its factories only)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/ContextResponse.java *(label assignment only)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEvent.java *(`isValidAction` and the `names` key rule only)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/NamesDispositionKeyTest.java *(new)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/audit/verify/AuditChainVerifierNamesKeyTest.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java, CompareEntitySourcesTool.java *(the input schema's `targetSources` property and its argument parsing only)*
- Every `new DefaultContextOrchestrator(` call site *(the constructor call only)*: data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/OrchestrationWiring.java; data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourcesAutoConfiguration.java; data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java; in `data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/`: UndeclaredKeyRefusalPathTest, ResponseLimitsTest, PerSourceValidationTest, ExternalCorrelationPropagationTest, DenyDecisionCodeTest, DefaultContextOrchestratorTest, AuditedEntityTypeOrchestratorTest; in `data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/`: UnregisteredEntityTypeAuditTest, UndeclaredKeyToolRefusalTest, ResponseLimitRefusalTest, OrchestratorRefusalCorrelationTest, ExternalCorrelationToolTest, CorrelationMdcToolTest, CompareEntitySourcesToolTest, ClusterOperationRefusalToolTest; data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/boundary/ValidationBoundaryTest.java; data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceEndToEndTest.java
- data-prism-integration-tests/src/test/java/io/github/aindriub/dataprism/example/http/AuditFieldDispositionTest.java *(only assertions that the `names` key changes)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/catalog/LabelsAndTargetingTest.java, RealNamesPolicyTest.java, UnselectedSourceLimitTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/TargetSourcesToolTest.java *(new)*

## Goal
Make a source's label one decision per request: a real name only when the scope matches `dataprism.catalog.real-names-scopes` and the caller holds `EXPOSE_SOURCE_NAMES` (D-RM-10, catalog OD-C3 (c)), otherwise the scope-local alias. Let `get_entity_context` and `compare_entity_sources` take `targetSources` (OD-C6), refuse a selection that does not resolve (OD-C19) or is too large (OD-C8), and refuse an unselected call when the permitted estate for the entity type exceeds the limit (D-RM-9, OD-C7). Record the effective setting under the reserved audit key `names` (OD-C2 (a), owner). Introduce `OrchestrationOptions` so later collaborators are added without moving constructor call sites again.

## Context
- docs/plan/design/catalog.md section 3 ("Labels"), section 6 (targeting, behaviour 1 to 5, the audit-only reason table) and section 8 items 3, 4, 8, 9, 11, 12, 15, 16 and 20; register OD-200-4 (labels at most 64) and OD-200-12.
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceAliasing.java: the caller-only check today.
- data-prism-core/src/main/java/io/github/aindriub/dataprism/audit/AuditEvent.java:85, :145-160: the closed value grammar.
- docs/plan/HISTORY-INDEX.md, the 190 row: `REFUSED:<code>` disposition values (D-070-13).

## Required shape (later tasks read these)
- `OrchestrationOptions`: a builder with defaults (`OrchestrationOptions.defaults()`), passed as the last argument of the full `DefaultContextOrchestrator` constructor. This task adds `catalog`, `visibility`, `realNames` and `unselectedSourceLimit` (default 8). Tasks 251 and 252 add their own fields by insertion.
- `SourceLabels.forRequest(catalog, visibility, aliasing, realNames, caller, context)`: the label of every permitted source, the effective kind (`ALIAS` or `REAL`), and `resolve(String label)` that returns a source or a reason (`CATALOG_UNKNOWN_SOURCE`, `CATALOG_SOURCE_NOT_PERMITTED`). Read by 250, 251, 252.
- `RealNamesPolicy(List<String> scopePatterns)`: exact match or a single trailing `*`; `permits(scopeId)`.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] The effective setting is `REAL` only when both the scope pattern and the capability hold; the four combinations are tested. Mutants killed: `||` for `&&`; ignoring the scope setting. A pattern that is blank, has more than one `*`, or a `*` not at the end throws with a message starting `INVALID_CATALOG_SETTING:`.
- [ ] One response never mixes label kinds. The same source has the same label in one scope in every tool that names it; a different scope gives a different alias. A real name sent while aliases are in effect does not resolve.
- [ ] Two sources with the same label in a scope refuse the call with `SOURCE_LABEL_COLLISION`; the audit carries both real names in `sourceSystems` (forced with a stub token source).
- [ ] With `targetSources`, only the chosen adapters are called (counting adapter). A label that is unknown, of the wrong kind, of a source the caller may not use, or of a source that does not serve the entity type refuses the whole call with `SOURCE_NOT_AVAILABLE` and calls nothing. The client results for the four cases are byte-identical apart from `correlationId`; the audit has `catalog` = `REFUSED:CATALOG_UNKNOWN_SOURCE`, `REFUSED:CATALOG_SOURCE_NOT_PERMITTED` or `REFUSED:CATALOG_ENTITY_TYPE_MISMATCH`. The label the client sent is in no audit record or log line.
- [ ] More labels than `maxSources` refuses with `SOURCE_SELECTION_TOO_LARGE` and calls nothing. A duplicate label is rejected by the schema.
- [ ] Without `targetSources`, the permitted sources serving the entity type (or declaring none) are counted. At the limit every one is asked, as today; one above, the call is refused with `SOURCE_SELECTION_REQUIRED` and no adapter or budget is touched. Boundary tests at the limit and the limit plus one. Mutants killed: `>=` for `>`; counting all sources instead of permitted ones.
- [ ] Selection runs before the budget reservation of 246, so a refused selection charges nothing.
- [ ] `AuditEvent` accepts `REAL` and `ALIAS` for the key `names` and rejects any other value for it; a made-up key with `REAL` is still rejected, and every other key's grammar is unchanged. `AuditChainVerifier` verifies a chain that contains the key, with no change to the verifier. The key is written on the events OD-08-6 decides.
- [ ] Both tool schemas gain `targetSources`: optional array, `minItems` 1, `uniqueItems`, items of length 1 to 64, no `maxItems`. A request without it behaves exactly as before while the estate is at or below the limit (existing tests pass unmodified apart from constructor calls and `names` assertions).
- [ ] The Outcome records, for the 0.8 migration notes (261), that `EXPOSE_SOURCE_NAMES` alone no longer shows real names while `real-names-scopes` is empty (the default).
- [ ] Before merge, a whole-component hunt for privacy-boundary defects covers labelling, selection and the new audit values; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- `list_sources` and `describe_source` (250); `_links` (251).
- Binding `dataprism.catalog.*` from configuration (254).
- Per-source role authorisation (0.9, D-RM-11).
- `docs/audit.md` and `docs/tools.md` (261).
