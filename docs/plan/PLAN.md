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

### S8 — Security

The slice that makes the rest mean what it says. `PrivacyContext` is currently a
constant, and `scopeId` is half the pseudonymisation key: scope isolation is real
in the code and vacuous in the deployment, because there is only ever one scope.
Audit attributes every event to a principal named "system" that does not exist.

Scope, principal, purpose and case must derive from an authenticated session.
That forces the streamable HTTP transport too, because stdio cannot carry an
identity — the two are one piece of work, not two.

The plumbing is understood and supported: the MCP SDK's transport builder takes a
`contextExtractor`, and the tool handler reads `exchange.transportContext()`. No
filter-and-ThreadLocal workaround. Stdio stays as an explicitly single-principal
development mode.

Already in place: `ScopeIdentityIndex.endScope()` purges a scope's identities,
reverse index and budget, so revocation is largely done; `SourceAliasing`'s
expose-real-names flag is capability-shaped and becomes a real capability.

**Four questions to settle before starting:**

1. Token issuer — a specific IdP, or any JWT against a configured JWKS?
2. Where a case comes from — does an investigator arrive with a `case_id` claim,
   or does Data Prism own scope lifecycle as a user-facing surface? This is the
   difference between a resolver and another whole slice.
3. The purpose taxonomy — even three or four values. Without a list,
   `PurposeValidator` compares strings against nothing.
4. Whether stdio survives as a dev-only mode, refused in production profiles.

**Blocked by:** those four answers. S5, S6 and S7 merged.

### S9a — The cheap half of observability

Worth folding into S8 while audit is already being touched: the real principal in
audit events, metrics per docs/pack.md §89, and a log-scanning test that fails on
any PII in a full integration run. The append-only sink and the chain verifier can
wait for a deployment that needs them.
**Blocked by:** S8.

## Recommended stopping point

After S8 and S9a. That is where the README stops needing an asterisk: every claim
it makes is then true of a deployment rather than only of the library.

S10 was deferred past V1 by decision and the index it needs already exists. S11
is the Elasticsearch connector and Docker Compose — real work, no new guarantees,
and the three divergent stub sources it was going to build landed in S6. S12's
mutation and load testing is for a system with users.

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
