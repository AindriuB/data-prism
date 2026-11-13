# History index

One row per `## YYYY-MM-DD` entry in `HISTORY.md`, newest first. Only `scribe`
edits this file, and it writes the row in the same commit as the entry.

`HISTORY.md` grows without bound and is the single largest file a session can
accidentally load. This index exists so `planner` can answer "has this been
built before, and where do I read about it?" without opening it. A row is the date,
the task ids and the entry's own heading, which carries its title. There is no
summary column: the entry says it, and the heading is usually enough to decide
whether to open it. Where a heading names no subject (a wave number or a review
fix), the row adds a few-word topic in parentheses after the heading's closing
backtick. That parenthetical is not part of the heading; do not grep it.

Read a full entry by grepping the exact string in the **Heading** column:

```
grep -n '<heading text>' docs/plan/HISTORY.md
```

Then read that section only. A stale index is worse than none — an entry with
no row is one `planner` cannot find, and will re-plan.

| Date | Task IDs | Heading (grep this exact string) |
|---|---|---|
| 2026-11-13 | 315, 316, 317, 318, 319, 320 | `## 2026-11-13 — 315 to 320: 0.8.0 pre-cut wave 1, the HTTP guard documented and its contract stated, review items closed, Dependabot triaged` (0.8.0 wave 1) |
| 2026-11-13 | 303, 305, 308 | `## 2026-11-13 — 303, 305 and 308: three CI flakes closed, one of them a product fix` |
| 2026-11-12 | 314 | `## 2026-11-12 — 314: the 0.8 migration notes and changelog` |
| 2026-11-12 | 310, 311, 312, 313 | `## 2026-11-12 — 310, 311, 312 and 313: the 0.8 docs for key rotation, the keyed audit chain, the cluster's seals and trust, and case authorisation` |
| 2026-11-12 | 307 | `## 2026-11-12 — 307: soak provenance records the truth or says unknown and refuses` |
| 2026-11-12 | 309-F1 | `## 2026-11-12 — 309-F1: stdio leak tests require end of stream and the child's own exit` |
| 2026-11-11 | 263 | `## 2026-11-11 — 263 Part A: soak scaffolding for the 0.8 core, single node and three-member cluster` |
| 2026-11-10 | 260, 261 | `## 2026-11-10 — 260 and 261: freeze tests for the public surface, and the 0.8 agent-led surface, operations guide and shipped codes documented` |
| 2026-11-11 | 309 | `## 2026-11-11 — 309: stdio leak-test teardown reads child stderr to the end or fails` |
| 2026-11-10 | 304 | `## 2026-11-10 — 304: client content kept out of the SDK's inbound parse-error logs, on stdio and HTTP` |
| 2026-11-10 | 255 | `## 2026-11-10 — 255: links, follow and key resolvers wired into Spring, with the link surface scanned for leaks` |
| 2026-11-09 | 253, 286, 301, 302 | `## 2026-11-09 — 253, 286, 301 and 302: the follow tool, cluster security on readiness, links from configured JSON sources, the pinned SDK logger` |
| 2026-11-06 | 252, 254, 300 | `## 2026-11-06 — 252, 254 and 300: following one link, the limits and catalog in Spring, tool input validated in the handlers` |
| 2026-11-06 | 293, 299 | `## 2026-11-06 — 293 and 299: accepting a confirmed crash line for retention, describing a source by name` |
| 2026-11-06 | 249, 250, 251, 272, 287, 291, 296, 297, 298 | `## 2026-11-06 — 249, 250, 251, 272, 287, 291, 296, 297 and 298: configured-source descriptions, the catalog tools, sealed links on results, bounded source-call threads, the trusted-network rule on plain-Java entry points, the node cap, audit-sink writes that survive an interrupt` |
| 2026-11-04 | 211, 228, 247, 256, 292, 294 | `## 2026-11-04 — 211, 228, 247, 256, 292 and 294: keyed audit chain configuration, the sealed leased pause document, labels per scope and targetSources, cross-validator key redaction, retention anchors from hashed records, harness flake fixes` |
| 2026-10-29 | 208, 227, 246, 262, 284, 289, 290 | `## 2026-10-29 — 208, 227, 246, 262, 284, 289 and 290: free-text recognisers, member cards and the sentinel, the per-source fan-out, the quickstart re-identification, trusted-network guardrails, declared-type description, Jackson annotations refused` |
| 2026-10-28 | 207, 210, 225, 271, 285, 288 | `## 2026-10-28 — 207, 210, 225, 271, 285 and 288: size-capped audit segments, explicit security wiring, sealed identity maps, descriptors to the scrubber, unsealed budget reservations, the engine builds the source tree` |
| 2026-10-23 | 203, 206, 223, 241, 248, 269 | `## 2026-10-23 — 203, 206, 223, 241, 248 and 269: case authorisation, keyed audit chain, keyed cluster names, key resolver SPI, field-protection description, settled link limiter` |
| 2026-10-22 | 222, 242 | `## 2026-10-22 — 222 and 242: cluster keys configured and required; sealed-link codec and attempt limiter` |
| 2026-10-22 | 266 | `## 2026-10-22 — 266: 0.7.1 released` |
| 2026-10-21 | 245, 268 | `## 2026-10-21 — 245 and 268: operator and re-identification denials answer AUDIT_UNAVAILABLE; embedded Tomcat managed at 11.0.27` |
| 2026-10-21 | 265, 267 | `## 2026-10-21 — 265 and 267: type arguments followed only for JDK collections; records inside nested containers refused` |
| 2026-10-21 | 244, 257 | `## 2026-10-21 — 244 and 257: tool denials answer AUDIT_UNAVAILABLE; CI and release supply chain` |
| 2026-10-21 | 259, 265, 266 | `## 2026-10-21 — 259: quiet-host 0.7 baseline and the 0.7.1 plan` |
| 2026-10-21 | 220, 221, 240 | `## 2026-10-21 — 0.8 wave 2: sealing primitives, hardened cluster configuration, chainable fields` |
| 2026-10-21 | 218, 219 | `## 2026-10-21 — 0.8 wave 1b: cluster design aligned, Hazelcast capability spike` |
| 2026-10-21 | 238, 239, 243, 258 | `## 2026-10-21 — 0.8 wave 1a: design documents, source catalog, read reservations, soak harness` |
| 2026-10-20 | 213, 215, 218-231, 238-264 | `## 2026-10-20 — 0.8 planning recorded: 42 briefs and 34 decisions` |
| 2026-10-20 | 237, 188 | `## 2026-10-20 — 0.7.0 released` |
| 2026-10-20 | 236 | `## 2026-10-20 — 236: operator approval view carries status` |
| 2026-10-20 | 235 | `## 2026-10-20 — 235: approval decisions are audit-first` |
| 2026-10-20 | PR 137 review | `## 2026-10-20 — Cluster-refusal retry guidance corrected` |
| 2026-10-20 | 234 | `## 2026-10-20 — 234: Jackson 3 managed at 3.1.8` |
| 2026-10-20 | recapture | `## 2026-10-20 — 0.7 HTTP examples re-captured` |
| 2026-10-20 | 201 | `## 2026-10-20 — 0.7 CHANGELOG, migration page and architecture entries (201)` |
| 2026-10-20 | 233 | `## 2026-10-20 — Cluster refusals reach callers and the approval sweep interval is bound (233)` |
| 2026-10-20 | 200 | `## 2026-10-20 — Contract register for 0.7 to 0.9 (200)` |
| 2026-10-20 | 199 | `## 2026-10-20 — First load-test baseline for 0.7 (199)` |
| 2026-10-20 | 209 | `## 2026-10-20 — Sensitive-pattern validator wired into every Spring-built orchestrator (209)` |
| 2026-10-20 | 232 | `## 2026-10-20 — Approval creation tokens and bounded cleanup in Hazelcast (232)` |
| 2026-10-20 | 216 | `## 2026-10-20 — Atomic approval creation in Hazelcast (216)` |
| 2026-10-19 | 194, 196 | `## 2026-10-19 — PR #132 review fixes` (key-scan cap bypass, load-test cleanup race) |
| 2026-10-19 | 194 | `## 2026-10-19 — Key-shape scan in the leak validators (194)` |
| 2026-10-19 | 196 | `## 2026-10-19 — Load-test harness (196)` |
| 2026-10-19 | 198 | `## 2026-10-19 — Batch, paging and bulk-follow design (198)` |
| 2026-10-19 | 217 | `## 2026-10-19 — Response-size Javadoc follow-ups (217)` |
| 2026-10-19 | 193 | `## 2026-10-19 — Record and response-size limits enforced (193)` |
| 2026-10-19 | none (main commits) | `## 2026-10-19 — Hazelcast commits made on main without a task file` |
| 2026-10-19 | none (98fd4248) | `## 2026-10-19 — Review of the reconciliation commit 98fd4248` |
| 2026-10-19 | 198, 203–215 | `## 2026-10-19 — PR 130 planning reconciliation and docs guard repair` |
| 2026-10-09 | 202 | ## 2026-10-09 — Hazelcast approval compare-and-set, split-brain protection, bounded lock waits (202) |
| 2026-10-09 | 190 | ## 2026-10-09 — Per-source results with stable status codes (190) |
| 2026-10-09 | 197 | ## 2026-10-09 — Catalog design: list_sources, describe_source, describe_entity_model (197) |
| 2026-10-09 | 195 | ## 2026-10-09 — Mutation testing on the privacy modules (195) |
| 2026-10-09 | 191, 192 | ## 2026-10-09 — Sealed-link, key-resolver and cluster-security designs (191, 192) |
| 2026-10-09 | 188, 189 | ## 2026-10-09 — Supply chain attestations and configurable request limits (188, 189) |
| 2026-10-09 | roadmap | ## 2026-10-09 — Road to 1.0 agreed |
| 2026-10-09 | 185, 186 | ## 2026-10-09 — Maven 3.10 release guards and Central plugin investigation (185, 186) |
| 2026-10-09 | 187 | ## 2026-10-09 — 0.6.0 publication complete and repository housekeeping (187) |
| 2026-10-09 | 184 | ## 2026-10-09 — 0.6.0 released after a Central rejection: Maven Wrapper pin and tag move (184) |
| 2026-10-09 | 183 | ## 2026-10-09 — 0.6.0 cut locally on release/0.6.0-cut (183) |
| 2026-10-09 | 179 | ## 2026-10-09 — 0.6.0 wave 12: deterministic sources order, shared ToolAdmission.none() and interrupted-close drain (179, 182) |
| 2026-10-09 | 182 | ## 2026-10-09 — 0.6.0 wave 12: deterministic sources order, shared ToolAdmission.none() and interrupted-close drain (179, 182) |
| 2026-10-09 | 180 | ## 2026-10-09 — 0.6.0 wave 12: audit follow-ups and architecture fixture move (180, 181) |
| 2026-10-09 | 181 | ## 2026-10-09 — 0.6.0 wave 12: audit follow-ups and architecture fixture move (180, 181) |
| 2026-10-09 | 162 | ## 2026-10-09 — 0.6.0 wave 11: CHANGELOG, migration page and architecture records (162) |
| 2026-10-09 | 178 | ## 2026-10-09 — 0.6.0 unplanned fix: bounded close() and json-directory Condition (178) |
| 2026-10-09 | 177 | ## 2026-10-09 — 0.6.0 unplanned fix: canonical annotation rendering in the bean inventory (177) |
| 2026-10-09 | 163 | ## 2026-10-09 — 0.6.0 wave 10: read-only AuditEventListener SPI (163) |
| 2026-10-09 | 164 | ## 2026-10-09 — 0.6.0 wave 9: Spring configuration metadata (164) |
| 2026-10-09 | 161 | ## 2026-10-09 — 0.6.0 wave 9: JSON audit projection as its own classified bean (161) |
| 2026-10-08 | 159 | ## 2026-10-08 — 0.6.0 wave 8: DataPrismAutoConfiguration split by concern, JWT into spring.boot.jwt (159) |
| 2026-10-08 | 158 | ## 2026-10-08 — 0.6.0 wave 7: DataPrismProperties split by concern (158) |
| 2026-10-08 | 175 | ## 2026-10-08 — 0.6.0 wave 6 addendum: interface and abstract map key types (175) |
| 2026-10-08 | 174 | ## 2026-10-08 — 0.6.0 wave 6 addendum: record properties must match components (174) |
| 2026-10-08 | 173 | ## 2026-10-08 — 0.6.0 wave 6 addendum: SourceTree Jackson 2 parity and record-only source models (173) |
| 2026-10-08 | 168, 169, 170, 171, 172 | `## 2026-10-08 — 0.6.0 wave 6: Jackson mapper hidden and guarded (168), docs (169), strict YAML readers (170), Javadoc gate (171)` |
| 2026-10-08 | 167 | `## 2026-10-08 — 0.6.0 wave 5: reactor ported to Jackson 3 (task 167) and D-167-1 decided` |
| 2026-10-08 | 166, 170 | `## 2026-10-08 — 0.6.0 wave 4: Jackson 2 characterisation tests (task 166) and task 170 planned` |
| 2026-10-08 | 157 | `## 2026-10-08 — 0.6.0 wave 3: audit package split into format, sink, checkpoint, retention, verify; verifier CLI moved (task 157)` |
| 2026-10-08 | 156 | `## 2026-10-08 — 0.6.0 wave 2: core root package split into spi, model, engine, refusal, limits, metrics (task 156)` |
| 2026-10-08 | 154, 155, 160, 165 | `## 2026-10-08 — 0.6.0 wave 1: ArchUnit audit/oversight, tool options records, tee/checkpoint hardening, ci-gate` |
| 2026-10-08 | 152, 153 | `## 2026-10-08 — 0.5.0 released in git: PR #117 merged, v0.5.0 tagged at c850c3e2` |
| 2026-10-08 | 152, 153 | `## 2026-10-08 — External review of PR #117: correlation header stripped unconditionally, torn audit tail terminated on resume` |
| 2026-10-07 | 151 | `## 2026-10-07 — 0.5.0 cut done locally: version 0.5.0, changelog, cut-time docs fixes` |
| 2026-10-07 | 116, 150 | `## 2026-10-07 — 0.5.0 wave 6: audit and log-shipping docs, entityType audited only when registered or shaped` |
| 2026-10-07 | 115, 148 | `## 2026-10-07 — 0.5.0 wave 5: correlation examples and MDC logging` |
| 2026-10-07 | 113, 114, 147 | `## 2026-10-07 — 0.5.0 wave 4: correlation wired into configuration, PII scans extended, platform documented` |
| 2026-10-07 | 149 | `## 2026-10-07 — Task 149: test-suite port races removed` |
| 2026-10-07 | none | `## 2026-10-07 — Baseline after 0.5.0 wave 3, and the owner's pause` |
| 2026-10-07 | 144 | `## 2026-10-07 — 0.5.0 wave 3, part 2: task 144` (JDK 21/25 CI matrix, class-version gate) |
| 2026-10-07 | 110, 111, 112 | `## 2026-10-07 — 0.5.0 wave 3, part 1: tasks 110, 111, 112` (correlation id through tools and REST sources) |
| 2026-10-07 | 109, 142, 146 | `## 2026-10-07 — 0.5.0 wave 2: tasks 109, 142, 146` (audit record v3, correlation id in the hash) |
| 2026-10-07 | 108, 141, 143, 145 | `## 2026-10-07 — 0.5.0 wave 1: tasks 108, 141, 143, 145` (correlation id types, Spring Boot 4.1.1) |
| 2026-10-07 | 130 | `## 2026-10-07 — Task 130: clear the dependency backlog` |
| 2026-10-07 | 138 | `## 2026-10-07 — Task 138: join.mode none refuses incoming joins` |
| 2026-10-07 | 136 | `## 2026-10-07 — Task 136: cut 0.4.1` |
| 2026-10-06 | 134, 135, 137 | `## 2026-10-06 — Tasks 134, 135 and 137: multi-member test, multiple-instances docs, Hazelcast 5.7.0 pin` |
| 2026-10-06 | 133 | `## 2026-10-06 — Task 133: multi-instance Compose and Kubernetes examples` |
| 2026-10-06 | 132 | `## 2026-10-06 — Task 132: cluster properties and startup refusals` |
| 2026-10-06 | 131 | `## 2026-10-06 — Task 131: explicit PrivacyCluster membership` |
| 2026-10-06 | 129 | `## 2026-10-06 — Task 129: 0.4.0 release candidate cut` |
| 2026-10-06 | 127 | `## 2026-10-06 — Task 127: re-identification index wired over the application value source` |
| 2026-10-06 | 106 | `## 2026-10-06 — Task 106: EU AI Act support mapping` |
| 2026-10-06 | 105 | `## 2026-10-06 — Task 105: operator surface served on a separate port` |
| 2026-10-06 | 128 | `## 2026-10-06 — Task 128: refusal codes validated before reaching the MCP client` |
| 2026-10-06 | 126 | `## 2026-10-06 — Task 126: task 104's test reconciled with 123's DENY:<code> form` |
| 2026-10-06 | 123 | `## 2026-10-06 — Task 123: refused calls are audited as DENY:<code> from the orchestrator and the MCP tools` |
| 2026-10-06 | 104 | `## 2026-10-06 — Task 104: oversight, re-identification and operator-surface configuration wired` |
| 2026-10-06 | 124 | `## 2026-10-06 — Task 124: undeclared property names never reach the model` |
| 2026-10-06 | 120 | `## 2026-10-06 — Task 120: live pending approvals capped per requester` |
| 2026-10-06 | 125 | `## 2026-10-06 — Task 125: integration tests derive the artifact version from the build` |
| 2026-10-06 | 121 | `## 2026-10-06 — Task 121: REFUSED wording corrected and every `policyDecision` form documented` |
| 2026-10-06 | 117 | `## 2026-10-06 — Task 117: audit hash version 2 over an unambiguous, length-prefixed encoding` |
| 2026-10-06 | 118 | `## 2026-10-06 — Task 118: undeclared payload keys never leave on refusal and warning paths` |
| 2026-10-06 | 103 | `## 2026-10-06 — Task 103: audit directory, checkpoint and retention wired into configuration` |
| 2026-10-06 | 122 | `## 2026-10-06 — Task 122: `ServerStartupTest` binds 127.0.0.1 to stop the `/health` 404 flake` |
| 2026-10-06 | 119 | `## 2026-10-06 — Task 119: `InMemoryApprovalStore` matches `HazelcastApprovalStore` under one contract test` |
| 2026-10-06 | 101 | `## 2026-10-06 — Task 101: MCP tools enforce admission and return correlationId` |
| 2026-10-06 | 102 | `## 2026-10-06 — Task 102: segmented audit sink and retention purge with anchors` |
| 2026-10-06 | 100 | `## 2026-10-06 — Task 100: `data-prism-reidentification` module` |
| 2026-10-06 | 96 | `## 2026-10-06 — Task 96: orchestrator audits field dispositions, exposes correlationId` |
| 2026-10-06 | 97 | `## 2026-10-06 — Task 97: external audit checkpoints, verifier exit 5` |
| 2026-10-06 | 99 | `## 2026-10-06 — Task 99: Hazelcast-backed oversight state, failing closed` |
| 2026-10-06 | 92, 93, 94, 98 | `## 2026-10-06 — Tasks 92, 93, 94, 98: dispositions, special categories and tool admission (EU AI Act wave 1 completed)` |
| 2026-10-06 | 95 | `## 2026-10-06 — Task 95: oversight SPIs in core (EU AI Act wave 1, first task to land)` |
| 2026-09-24 | — | `## 2026-09-24 — 0.3.1 release completed; mcp-publisher pinned; Glama claimed; awesome-mcp-servers PR open` |
| 2026-09-24 | 91 | `## 2026-09-24 — Task 91: 0.3.1 cut for GHCR and the MCP Registry, Maven Central held at 0.3.0` |
| 2026-09-24 | 85-90 | `## 2026-09-24 — Site polish merged to main (PR #101); Search Console, Bing, About and first traffic snapshot done` |
| 2026-09-24 | 90 | `## 2026-09-24 — Task 90: the custom identity-resolver tutorial, and the site-polish plan is done` |
| 2026-09-24 | — | `## 2026-09-24 — Corrects canonical description D's "redacts or refuses" to "refuses"` |
| 2026-09-24 | — | `## 2026-09-24 — Corrects the released 0.3.0 \`AuditChainVerifier\` CHANGELOG wording` |
| 2026-09-24 | — | `## 2026-09-24 — Corrects \`docs/tools.md:120\`'s "unclassified values dropped" claim` |
| 2026-09-24 | 88 | `## 2026-09-24 — Task 88: draw five concept diagrams and embed each in context` |
| 2026-09-24 | 89 | `## 2026-09-24 — Task 89: start the developer guide with a tested adapter tutorial` |
| 2026-09-24 | 86 | `## 2026-09-24 — Task 86: give the docs site an identity` |
| 2026-09-24 | 87 | `## 2026-09-24 — Task 87: collapse the changelog page per release` |
| 2026-09-24 | 85 | `## 2026-09-24 — Site polish merged to main (PR #101); Search Console, Bing, About and first traffic snapshot done` (no entry of its own; covered here as tasks 85-90) |
| 2026-09-24 | 83 | `## 2026-09-24 — Task 83: README and \`server.json\` wired to the live docs site` |
| 2026-09-23 | 82 | `## 2026-09-23 — Task 82: MkDocs Material docs site built and wired to deploy` |
| 2026-09-23 | 81 | `## 2026-09-23 — Task 81: outreach drafts for the owner to post by hand` |
| 2026-09-23 | 84 | `## 2026-09-23 — Task 84: the \`dataprism.privacy\` row's two false startup-refusal claims corrected` |
| 2026-09-23 | 77 | `## 2026-09-23 — Task 77: an FAQ and a fair, sourced comparison page` |
| 2026-09-23 | 76 | `## 2026-09-23 — Task 76: one canonical identity across README, poms, registry and image metadata` |
| 2026-09-23 | 78 | `## 2026-09-23 — Task 78: three use-case pages for the searches this audience runs` |
| 2026-09-23 | 80 | `## 2026-09-23 — Task 80: discoverability gets a tracker-free measurement setup` |
| 2026-09-23 | 79 | `## 2026-09-23 — Task 79: the site gets a reproducible social card` |
| 2026-09-23 | 59 | `## 2026-09-23 — Task 59: the quickstart gets an exit ramp, and the reference docs catch up to v0.3.0` |
| 2026-09-23 | 70 | `## 2026-09-23 — Task 70: version 0.3.0 cut across the reactor, onto the integration branch` |
| 2026-09-23 | 62 | `## 2026-09-23 — Task 62: nested catalogues and the durable audit chain are documented` |
| 2026-09-23 | 75 | `## 2026-09-23 — Task 75: each process boot gets its own audit chain identity` |
| 2026-09-23 | 69 | Task 69: the reviewed-adapter allow-list is restored, via a `Set<String>` bean instead of a connector-owned type |
| 2026-09-23 | 55 | Task 55: the quickstart images publish, and Compose pulls them by default |
| 2026-09-23 | 67 | Task 67: `dataprism.audit.sink=hash-chained` wired to `FileAuditSink` |
| 2026-09-23 | 71 | `## 2026-09-23 — Task 71: pseudonym discriminator widens from 20 to 40 bits, and \`ADDRESS\` gains one` |
| 2026-09-23 | 74 | `## 2026-09-23 — Task 74: a sink's exception no longer reaches the MCP client verbatim` |
| 2026-09-23 | 73 | `## 2026-09-23 — Task 73: \`AUDIT_SINK_BEAN_REQUIRED\` names the cause instead of the symptom` |
| 2026-09-23 | 72 | `## 2026-09-23 — Task 72: \`timestamp\` and \`sourceSystems\` brought inside the audit hash` |
| 2026-09-23 | 66 | `## 2026-09-23 — Task 66: the offline audit-chain verifier CLI, after four attempts each relocating the same defect` |
| 2026-09-22 | 61, 65 | `## 2026-09-22 — v0.3.0 wave 2 (tasks 61, 65): nested JSON proven through the real MCP transport, and the file sink's PII scan closed after an empty-derivation hole` |
| 2026-09-22 | 60 | `## 2026-09-22 — Task 60: one level of named nested JSON catalogues, after three attempts and a task-file amendment` |
| 2026-09-22 | 63, 64, 68 | `## 2026-09-22 — v0.3.0 wave 1 (tasks 63, 64, 68): the audit chain's write ordering fixed, a durable file sink shipped after three attempts, and a bean-classification escape hatch closed` |
| 2026-09-22 | 58 | `## 2026-09-22 — Task 58: a walkthrough for a stranger's own API, and six attempts to make it both work and not write key material into the repo` |
| 2026-09-21 | 53, 54, 56, 57 | `## 2026-09-21 — Wave 1 (tasks 53, 54, 56, 57): the no-code path made real, and the demo's transport bug that a stub had hidden` |
| 2026-09-17 | 52 | (no entry of its own; only mentioned as pending in the `## 2026-09-17 — Tasks 49, 50, 51` entry) |
| 2026-09-17 | 49, 50, 51 | `## 2026-09-17 — Tasks 49, 50, 51: \`data-prism-example\` renamed to \`data-prism-integration-tests\`, and the first two consumer guides ship` |
| 2026-09-17 | 48 | `## 2026-09-17 — 0.2.0 release complete: GitHub Release, Maven Central, GHCR multi-arch, and the first successful MCP registry publish` |
| 2026-09-17 | 47 | `## 2026-09-17 — Task 47: the PII scan's reflection-depth and word-boundary holes closed` |
| 2026-09-17 | 45 | `## 2026-09-17 — Task 45: version 0.2.0 cut, and a sweep that had been running blind to \`.github/\`` |
| 2026-09-17 | 46 | `## 2026-09-17 — Task 46: PiiLogScanTest's banned values derived from the stub fixtures` |
| 2026-09-17 | 43 | `## 2026-09-17 — Task 43: compare_entity_sources wired into the example, and the identity assumption task 42 could not prove is now proven` |
| 2026-09-17 | 42, 44 | `## 2026-09-17 — Tasks 42 and 44: the second MCP tool, and the registry namespace corrected` |
| 2026-09-17 | 41 | `## 2026-09-17 — Task 41: cut version 0.1.1, and a planning false premise caught at review` |
| 2026-09-16 | 40 | `## 2026-09-16 — Task 40: publish the server image for linux/amd64 and linux/arm64` |
| 2026-09-16 | 38 | `## 2026-09-16 — Task 38: the MCP registry entry, and the release plan is complete` |
| 2026-09-16 | 36, 37, 39 | `## 2026-09-16 — Tasks 36, 37, 39: Maven Central publishing, distributable server image, default-mode transport fail-open closed` |
| 2026-09-16 | 33, 34, 35 | `## 2026-09-16 — Tasks 33, 34, 35: 0.1.0 cut, release hygiene files, stdio refuses instead of serving nothing` |
| 2026-09-15 | 31, 32 | `## 2026-09-15 — Simplification wave 2 (tasks 31-32): descriptor resolver wired, data-prism-audit merged into core` |
| 2026-09-15 | 26, 27, 28, 29, 30 | `## 2026-09-15 — Simplification wave 1 (tasks 26-30): shared JWT decoder, orchestrator cleanup, StrictYaml helper, OwnerScope record, properties formatting` |
| 2026-09-15 | 19, 20 | `## 2026-09-15 — Tasks 19 and 20: tested agent connection guides, and a configuration-driven JSON REST mode` |
| 2026-09-14 | 18 | `## 2026-09-14 — Task 18: a reproducible local quickstart` |
| 2026-09-14 | 25 | `## 2026-09-14 — Task 25: wire the shared read budget, make the topology an explicit choice` |
| 2026-09-14 | 21, 22, 23, 24 | `## 2026-09-14 — Tasks 21, 22, 23 and 24: close the reviewed defects in tasks 13-17` |
| 2026-09-14 | 17 | `## 2026-09-14 — Task 17: package the standalone Data Prism server` |
| 2026-09-14 | 16 | `## 2026-09-14 — Task 16: ship the Spring Boot starter and embedded protected-API example` |
| 2026-09-14 | 15 | `## 2026-09-14 — Task 15: build the validated Spring Boot configuration core` |
| 2026-09-13 | 14 | `## 2026-09-13 — Task 14: define the shared deployment configuration contract` |
| 2026-09-13 | 11, 12, 13 | `## 2026-09-13 — Tasks 11, 12 and 13: close the remaining safety-test gaps` |
| 2026-09-09 | 10 | `## 2026-09-09 — Task 10: the mTLS refusal assertion made cross-platform` |
| 2026-09-09 | 09 | `## 2026-09-09 — Task 09: shipped defaults, and closing the last gap in the log scan` |
| 2026-09-09 | 07 | `## 2026-09-09 — Task 07: OAuth2 resource server, Micrometer, PII log scan` |
| 2026-09-09 | 06 | `## 2026-09-09 — Task 06: streamable HTTP transport, per-request caller context, authorisation at the tool` |
| 2026-09-09 | 08 | `## 2026-09-09 — S8: three inert controls made to run` |
| 2026-09-09 | 03, 04, 05 | `## 2026-09-09 — S8 wave 2: security module, real principal, and identity metrics` |
| 2026-09-09 | 01, 02 | `## 2026-09-09 — S8 wave 1: session types, metrics SPI, and mTLS to sources` |
| 2026-09-09 | S7 | `## 2026-09-09 — S7 embedded Hazelcast for distributed scope state` |
| 2026-09-09 | S6 | `## 2026-09-09 — S6 correlation and consistency findings` |
| 2026-09-09 | S5 | `## 2026-09-09 — S5 parallel connectors and request limits` |
| 2026-09-08 | S4 | `## 2026-09-08 — S4 pattern detection with a scope-aware allowlist` |
| 2026-09-08 | S2a | `## 2026-09-08 — S2a key rotation` |
| 2026-09-08 | S3 | `## 2026-09-08 — S3 scrubbing engine, and the nesting hole it closed` |
| 2026-09-08 | S2 | `## 2026-09-08 — S2 configurable multi-locale name pools` |
| 2026-09-08 | S1 | `## 2026-09-08 — S1 policy layer and build-time fail-closed` |
| 2026-09-08 | S0 | `## 2026-09-08 — S0 walking skeleton` |
