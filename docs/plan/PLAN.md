# Plan

What is open, in priority order. Only `scribe` edits this file.

Each item: one line of what, one line of why it matters, and its blocker if it
has one. When an item is in flight, mark it with its task ids. Delete items you
no longer want rather than letting them rot — a plan nobody trusts is worse than
no plan.

Slice numbering (`S0`–`S12`) matches `docs/development-plan.md`, which carries
the sizing and the exit criteria. This file carries the order and the state.

---

## Now

### D — Five decisions that block everything

Group id and package root; MCP SDK and transport; secret-management platform for
the HMAC key; audit sink of record; whether re-identification is in V1. Each one
is cheap now and expensive later: the package root renames every consuming
application, and a re-identification reverse map added after the fact invalidates
every scope that already exists. `docs/development-plan.md` states each choice
and what it determines.
**Blocked by:** nothing — these need a human answer, not a task.

### R — Make the repository a git repository

`git init` plus a first commit. The whole `/fanout` model is worktree-based and
cannot run without it, so nothing below can start.
**Blocked by:** nothing.

### S0 — Walking skeleton

One vertical thread through every layer with everything stubbed that can be:
parent POM, module skeleton, CI, the four annotations, a hardcoded scope, one
stub adapter, HMAC generation with a dev key, a trivial validator, one MCP tool,
a file audit sink. Exit: an MCP client gets a pseudonymised answer and an audit
line, proven by one integration test.

This is first because it settles the MCP SDK choice, the `ObjectMapper` boundary
and the `PrivacyContext` plumbing while they are still cheap to change. The
pack's own Phase 1–9 order defers all three to the end.
**Blocked by:** D, R.

## Next

### S1 — Metadata and policy

`FieldMetadataResolver` over records, classes and accessors; the subject graph
rather than one identifier per record; YAML profiles with the §29 precedence
chain; fail-closed with `@NonSensitive(reason=...)` enforced by an annotation
processor at build time.
**Blocked by:** S0.

### S2 — Pseudonymisation, properly

Keyed HMAC over `(scopeId, subjectId, namespace, algVersion, keyId)`, per-namespace
generators, the discriminator suffix, key rotation, and the checked-in golden
vectors that make an accidental algorithm change break the build.
**Blocked by:** S0.

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
  separate authorisation scope, mandatory purpose and audit. Only if decision D
  puts it in V1 — but the reverse map is built in S7 either way.
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
