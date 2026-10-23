# 250 — Serve paged list_sources and describe_source

**Repo:** `.`
**Release:** 0.8.0
**Base branch:** `origin/main` once 241, 242, 269, 247, 248 and 203 have merged
**Depends on:** 241 (the `PAGING_PAGES` constant), 242 (page links, limiter), 269 (the limiter contract: `AttemptSubject`, atomic `recordFailure`, `LinkAttemptGate`), 247 (labels, visibility, `names`), 248 (`describe`), 203 (203 owns `CapabilityKnownSetTest` and `ExampleApplication.java`)
**Wave:** 8 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/CatalogQueries.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/CatalogPage.java *(new)*
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/catalog/PagingSettings.java *(new)*
- data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/catalog/CatalogQueriesTest.java, CatalogPagingTest.java, CatalogPageLinkTest.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/ListSourcesTool.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DescribeSourceTool.java *(new)*
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java *(the `Wiring` record, the tool registration and the factory parameters only)*
- data-prism-mcp/src/main/resources/io/github/aindriub/dataprism/mcp/schema/list_sources.input.json, list_sources.output.json, describe_source.input.json, describe_source.output.json *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ListSourcesToolTest.java, DescribeSourceToolTest.java, CatalogToolSchemaTest.java *(new)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/model/Capability.java
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/CapabilityKnownSetTest.java *(the expected set only)*
- data-prism-integration-tests/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java *(the `DataPrismMcpServer.stdio(` call only)*

## Goal
`list_sources` is the root resource of the agent-led design and `describe_source` tells the agent how a source's fields are protected. Both return metadata only and call no source. Serve them through the privacy engine's mapper, behind their own capabilities, with one uniform refusal for anything that does not resolve, the same labels every other tool uses, and the 0.8 page contract of `batch-and-bulk-follow.md` Part A: keyset pages, a sealed `next` link bound to scope, caller, filters and label kind, refusal of a walk whose catalog changed, and a page link `follow` will never accept.

## Context
- docs/plan/design/catalog.md sections 3 and 4 (purpose, labels, authorisation, schemas, rules, audit) and section 8 items 3, 6 to 10, 13, 15 and 17.
- docs/plan/design/batch-and-bulk-follow.md Part A (A.1 to A.10) and section 8 items 1 to 13; OD-B1 to OD-B6 as decided; register OD-200-5 (`FOLLOW`), OD-200-15.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/GetEntityContextTool.java: the authorisation, scope, admission and audit sequence a tool follows; reserved-argument stripping.
- data-prism-mcp/src/main/java/io/github/aindriub/dataprism/mcp/DataPrismMcpServer.java:222-249: the `Wiring` record and the one-mapper invariant.
- From 269 (OD-269-1 to 3 decided (a) by the owner 2026-10-22; brief revised 2026-10-23, after review 4): open every page link through the gate. `LinkAttemptGate.open(token, binding, accepted)` is the one call. It checks the inputs and the binding bounds (`Invalid(MALFORMED)`, uncounted), then `lockState` (a locked principal gets `Exhausted(LOCKED)` before the token is opened, valid links included). It opens the token and records a counted refusal atomically. It returns `Proceed(opened)`, `Invalid(reason)` or `Exhausted(cause)`, where the cause is `LOCKED`, `OVER_LIMIT`, `CEILING`, `LIMITER_UNAVAILABLE` or `OPEN_FAILED`, and it never throws. The client sees `LINK_INVALID` or `LINK_ATTEMPTS_EXHAUSTED` only. Write `decision.auditValue()` (`REFUSED:LINK_<REASON>` or `REFUSED:LINK_ATTEMPTS_<CAUSE>`) under the call's `link` disposition key. An architecture rule forbids calling or referencing `LinkCodec.open` or the limiter outside `core.link`. Catalog-tag and label checks after `Proceed` are uncounted and never touch the limiter. `LinkKeys.attemptCounterKey` no longer exists.

## Acceptance
- [ ] Tests are written first and fail on the base commit; the Outcome names them.
- [ ] `Capability` gains `LIST_SOURCES`, `DESCRIBE_SOURCE` and `FOLLOW` (OD-200-5) in `KNOWN`, and `CapabilityKnownSetTest` pins them. Each new tool refuses with `TOOL_NOT_PERMITTED` for a role without its capability, and runs admission under its own tool name.
- [ ] `list_sources` returns only permitted sources, in ascending byte order of label, with one label kind per response and the description of that kind; `entityTypes`, `cost` and `freshness` are omitted when undeclared; nothing is `null`; no count of hidden sources appears. Filters match exactly and apply before the slice; a filter matching nothing returns `sources: []` and `page: {size, last: true}`.
- [ ] Paging, Part A items 1 to 10: every entry exactly once over estates of 0, 1, n, n+1 and 2n at page size n; no empty final page; `last` decided by looking one entry past; a `pageSize` above the ceiling applies the ceiling and reports it in `page.size`; the default applies when absent. Mutants killed: offset instead of keyset, `>=` for `>`, filtering after slicing.
- [ ] A page link is sealed with relation `0x02` and an empty target. It is refused, with the uniform `LINK_INVALID` text, in another scope, for another caller, after expiry, with different filters or `pageSize`, after the label kind changed, and after the catalog tag changed (a source added, removed, or the visible set narrowed); the audit has `link` = `REFUSED:LINK_<REASON>` for each. Two catalogs with the same entries give the same tag on two instances. A `follow` link passed as `page` is refused (`LINK_WRONG_TOOL`, not counted).
- [ ] Pre-authentication failures of page links count in the shared limiter; a locked principal gets `LINK_ATTEMPTS_EXHAUSTED` for a call with `page` and never for a call without it.
- [ ] With no link codec configured, a call returns the whole visible list on one page with `last: true`, whatever `pageSize` says (batch OD-B2 (a), call-time rule); the startup rule is 254's.
- [ ] `describe_source` returns the schema of `catalog.md` section 4 from the scrub plan (248), with `undeclared`, `valueNamespace` only for `SYNTHETIC` and `TOKEN`, and `chainable` with `keyNamespace` only while links are enabled. An unknown label, a label of the wrong kind, a source the caller may not use and a source whose description cannot be computed all return the identical `SOURCE_NOT_AVAILABLE` result; the audit reasons differ. The response contains no value, classification name, base-url, model class name or credential reference (scanned).
- [ ] Both outputs pass the response validators (the key-shape and pattern scans) before they leave, as every tool response does.
- [ ] One audit event per call: `tool` is the tool name, `sourceSystems` the real names of the sources on the page (or of the described source), `names` the effective setting, `DENY:<code>` on refusal. No client-sent label, filter, page token or description text is in any audit record, log line, metric or exception. `PAGING_PAGES` is incremented once per page served.
- [ ] The four schema files are valid draft 2020-12; the served schemas are generated from the same definitions and `CatalogToolSchemaTest` compares them byte for byte with the checked-in files; the `page` `maxLength` is the configured link `max-length`.
- [ ] `DataPrismMcpServer`'s existing test that every tool and the transport share one mapper covers the two new tools.
- [ ] Before merge, a whole-component hunt for privacy-boundary and oracle defects covers both tools and the page link; each finding is fixed or filed and named in the Outcome.
- [ ] `./mvnw -B verify` (full reactor) passes.

## Out of scope
- Spring registration and the `dataprism.paging.*` and `dataprism.catalog.*` properties (254).
- `follow` and `_links` (251, 252, 253).
- `describe_entity_model` and per-source role authorisation (0.9).
- `docs/tools.md` (261).
