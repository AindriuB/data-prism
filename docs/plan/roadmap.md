# Road to 1.0

Agreed 2026-10-09. This lives under `docs/plan/`, which mkdocs excludes (`exclude_docs: plan/` in `mkdocs.yml`), so it is internal planning and is not published. It replaces the draft `roadmap-ideas.md`. Owner decisions are recorded in `PLAN.md` under "Road to 1.0".

Reconciled 2026-10-19: see [task allocation and decisions](reconciliation-2026-10-19.md). Imported tasks 203–214 are allocated before the freeze or superseded; they do not add new 1.0 features.

## Purpose of 1.0

1.0 is a privacy layer that an operations or support team can run against many services at once, in the hundreds. Each service has its own identifiers. An LLM agent discovers the sources, follows relationships between them by itself, and compares what each service holds about the same entity, without ever seeing a real identifier. A typical use is a support agent tracing a record that propagated through several services to find where it stopped.

## What 1.0 means

- A stability promise: semver over the public API, configuration properties, refusal codes, MCP tool schemas and the audit format. The surface is frozen at the 0.9.0 release.
- Many sources, agent-led, as described under Design.
- Multiple instances supported and secured on open-source Hazelcast (no Enterprise features).
- Evidence: load and soak runs at 100, 300 and 500 sources and at thousands of items per investigation; mutation testing gating the privacy modules.
- Supply chain: SBOM, signed images and provenance, plus an external security review with its findings closed.
- An operations guide, an upgrade guide and a support policy.
- At least one pilot deployment.

## Design: agent-led, HATEOAS-style

The server holds no application-specific knowledge. It exposes generic tools, and the agent sequences its own calls.

- `list_sources` is the root resource. Each entry gives the alias or real name, the entity types, a neutral description, and the input keys with their namespaces.
- `describe_source` returns the output shape and the per-field protection. It returns no data.
- Results carry `_links`, built by the server from adapter and key-resolver declarations, one step each, and only for fields marked `chainable: true`.
- A link is sealed: authenticated encryption, bound to the scope, the target and an expiry. It is stateless and cluster-safe, is never stored in Hazelcast, and does not use the re-identification index (boundaries 5 and 6).
- Key resolvers generalise `IdentityResolver`: each translates one identifier type into another. A direct link needs an exact namespace match. Translation happens only through a declared resolver.
- `follow` follows one link. `get_entity_contexts` is the batch form.
- Collections are paged, with sealed next links.
- Bulk follow is status-only: it returns counts plus drill-in links for the exceptions.
- Results per source are never merged, and each carries a stable status code.
- Any invalid link gets one uniform refusal. The real reason goes to the audit log only.

## Release plan

### 0.7 Foundations and design

Readiness:

- Supply chain: SBOM, signed images, provenance.
- Limits bound from configuration; the declared-but-unenforced record and response-size caps enforced; a configurable read budget.
- Load-test harness and baseline.
- Mutation testing, report-only.
- Cluster security design on open-source Hazelcast.

Features:

- Stable per-source status codes.
- Fix fields from one source overwriting another's in the merged tree.
- Design set: sealed-link threat model, link and key-resolver contracts, catalog and `describe_source`, batch and bulk-follow schemas, `describe_entity_model` spec.
- Key-shape scan in the leak validators.
- Existing pattern validator wired into both Spring orchestration paths (209).

### 0.8 Build the many-source core

Readiness:

- Cluster security build from the approved sealed-state design (215 plans implementation tasks); 204/205/212 are superseded alternatives.
- Case-authorisation SPI and client allowlist (203/210).
- Optional keyed audit chains and size-capped segments (206/207/211).
- FREE_TEXT classification and recogniser SPI/wiring (208).
- Rotation procedures and migration documentation for these controls (213).
- Fan-out rewrite: separate queue, per-source and overall deadlines, no silent cut-off at `maxSources`.
- Read budget charged per source called, plus a per-scope total cap, on both budget implementations.
- Source configuration split across files, with startup validation.
- Soak, including the cluster.
- Draft operations guide.
- Quickstart with re-identification end to end.

Features:

- Adapter description: entity types, description, input keys, output model, cost, freshness.
- Paged `list_sources` (catalog OD-C16; task 198 specifies its page first) and `describe_source`.
- Sealed links, `_links`, `follow`, `chainable`.
- Key resolvers.
- Real source names when scope and caller both allow.
- Target chosen sources on `get_entity_context`, and refuse when none are chosen and the estate exceeds the limit.

### 0.9 Release candidate

Features are complete during 0.9. The API and configuration freeze on the 0.9.0 release; 0.9.x takes fixes only.

Readiness:

- API and configuration freeze.
- Load and soak at 100, 300 and 500 sources.
- Mutation testing gating, including link checks.
- External security review.
- Pilot.
- Final operations and upgrade guides.
- Data-protection control annex based on merged implementations (214).
- Other MCP clients verified.

Features:

- `get_entity_contexts` batch.
- Paged collections and status-only bulk follow, sized for thousands of items.
- Per-source authorisation: role-to-source patterns, unmatched sources denied.
- Per-source truncation with markers, size caps, and audit-entry size handling.
- Reference example on an invented neutral domain, with an end-to-end test that finds where propagation stopped.
- `describe_entity_model`.

### 1.0

The stability promise and the support policy. No new features.

### After 1.0

- `search_entity_data` and search connectors.
- Application-written audit events in the hash chain (D-0.6-8 D2).
- Spring AOT and native images.

## Risks

- 0.8 is heavy: cluster security, the fan-out rewrite and sealed links land together.
- Sealed links are new cryptography in a privacy path. The threat model is written in 0.7 and reviewed externally in 0.9. Fallback: chain only from keys a human supplies.
- Description text can leak real system names. The startup check is a guard rail; the operator is the reviewer.
- Link volume per record: cap per response, group by relation, and mark truncation.
- Scale per investigation: budgets, batch ceilings and audit volume are sized per scope.
