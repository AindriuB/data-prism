# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse than
no plan.

Slice numbering (`S0`–`S12`) matches `docs/development-plan.md`, which carries
the sizing and the exit criteria. This file carries the order and the state.

---

## Decided

The five choices that blocked S0 were settled on 2026-09-08. Recorded here in
brief; the reasoning and the rejected alternatives are in
`docs/architecture.md#decisions-worth-knowing`.

| | Decision |
|---|---|
| Coordinates | Group `io.github.aindriub`, artifacts `data-prism-*`, package root `io.github.aindriub.dataprism` |
| MCP | Official MCP Java SDK directly, with Spring wiring written here. stdio in development, streamable HTTP in production |
| HMAC key | Local key supplied at startup through a `SecretKeyProvider` SPI. No vendor client in core |
| Audit sink | `AuditSink` SPI with a file/SLF4J implementation. No vendor client in core |
| Re-identification | Reverse map built in S7. The operator surface (S10) is deferred past V1 |

## Now

### S1 — Metadata and policy

Resolve metadata from classes and accessors as well as record components; add
`@SubjectIdentifier` for records describing more than one subject; put a real
policy layer between the annotation and the action, with YAML profiles and the
precedence chain from pack.md §29; and enforce fail-closed at build time with an
annotation processor rather than only at runtime.

S0 honours `suggestedAction` directly, which the specification explicitly warns
against — the annotation is the model author's suggestion and the server-side
policy is meant to have final authority. Until this lands, changing a privacy
decision means editing and redeploying application code.
**Blocked by:** nothing. S0 merged 2026-09-08.

### S2 — Pseudonymisation, properly

Per-namespace generators beyond the S0 four, key rotation with `keyId` pinned at
scope creation, multi-key resolution, and a properly sized and licensed name
pool. The golden vectors and the discriminator already exist.
**Blocked by:** nothing; independent of S1.

## Next

### S3 — Scrubbing engine

The core deliverable: tree-based traversal driven by type metadata, all seven
privacy actions, generalisation strategies, nesting, collections, maps, cycles
and unknown properties, installed as a Jackson module on the MCP mapper.
**Blocked by:** S1, S2.

### S4 — Validation and scanning

Structural and pattern detection, scope-aware allowlisting of the platform's own
pseudonyms, fail-closed refusal, violations recorded without the offending value.
Without the allowlist the fail-closed path deadlocks on synthesised emails, so
this cannot be deferred past the first synthesising profile.
**Blocked by:** S3.

## Someday

Ordered, not scheduled. S5, S6 and S7 are independent of one another once S3
lands and are the natural parallelisation point.

- **S5 — Connectors and orchestration.** `RestClient` sources, virtual-thread
  fan-out with timeouts and circuit breakers, `IdentityResolver` SPI, request and
  cost limits.
- **S6 — Canonical model and correlation.** Canonical entity, provenance,
  per-scope pseudonymisation of source names, normalisation-aware consistency
  findings, the injection-heuristic finding type.
- **S7 — Hazelcast.** Client–server topology, forward and reverse maps, TTL,
  scope purge, and the test proving output is identical with the cluster killed.
- **S8 — Security.** OAuth2 resource server, mTLS, RBAC and ABAC, purpose
  validation, scope lifecycle held entirely outside the MCP surface.
- **S9 — Audit and observability.** Per-writer hash chain, append-only sink,
  pseudonymised subject ids in audit, metrics, and a log-scanning test that fails
  on any PII in a full integration run.
- **S10 — Re-identification surface.** Separate application, separate port,
  separate authorisation scope, mandatory purpose and audit. Deferred past V1 by
  decision; the reverse map it will read is still built in S7, because one added
  after the fact cannot resolve any pseudonym issued before it existed.
- **S11 — Example application and search.** Three divergent stub APIs, the
  Elasticsearch connector with allowlists and caps, `search_entity_data` and
  `describe_entity_model`, Docker Compose.
- **S12 — Hardening.** Threat model T1–T10 as tests, mutation testing over the
  privacy and validation modules, `docs/data-protection.md`,
  `docs/threat-model.md`, ADRs, load testing.

## Not doing

Recorded so they are not re-proposed. Each is a deliberate scope boundary from
`docs/design-review.md`, not an oversight.

- Probabilistic entity matching. It sits behind `IdentityResolver` and is a
  different product.
- Any MCP tool that opens, selects or extends a privacy scope. That is the one
  lever that breaks scope isolation.
- Any MCP tool that re-identifies a pseudonym.
- Caching raw source responses.
