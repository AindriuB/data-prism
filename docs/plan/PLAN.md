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

### S8 and S9a — done

S8 (security: OAuth2 resource server, scope/principal/purpose/case derived from
an authenticated caller, streamable HTTP transport) and S9a (the cheap half of
observability: real principal in audit, Micrometer metrics, a PII log scan that
can actually fail) are both complete, closed by task 07 merging 2026-09-09. See
`docs/plan/HISTORY.md` — grep `Task 07` — for what landed and what it cost.

### Now — Task 09: shipped defaults and hygiene

The last item before the plan's recommended stopping point. Two real defects —
`DataPrismAssembly` mints a context carrying `EXPOSE_SOURCE_NAMES` by
construction, so the worked example prints real source names while the shipped
application masks them; and the shipped `developer` role's capability set is
untested — plus two small pieces of debt (`ScopeResolver`'s stale javadoc,
`data-prism-security` pulled transitively rather than declared). Task file:
`docs/plan/tasks/09-shipped-defaults-and-hygiene.md`.

**Blocked by:** nothing. Its dependency, task 07, merged 2026-09-09.

**Also queued for 09, not yet folded into its acceptance list:** `PiiLogScanTest`
(task 07) matches banned values on word boundaries, so a banned value glued to
word characters is not caught — `subject=SUBJ-123a7f9` and `id_456_x` both pass
today. No current code path emits either shape, but the first is the shape a
pseudonymiser bug concatenating a raw id would produce. Scanning the audit
line's structured fields rather than raw text would close it. `PiiLogScanTest`
is not in task 09's `Owns`, so this needs either a widened `Owns` or a follow-up
task file when 09 is next planned in detail — do not silently fold it into 09's
existing acceptance list without updating `Owns` to match.

## Recommended stopping point

After S8 and S9a, plus task 09's hygiene follow-ups. S8 and S9a are done; task 09
is the only open task and the only thing between here and the stopping point.
That is where the README stops needing an asterisk: every claim it makes is
then true of a deployment rather than only of the library.

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
