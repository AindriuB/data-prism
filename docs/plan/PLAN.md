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

### Task 09 — done

Closed the last two real defects and cleared four pieces of debt. Task 09
merged 2026-09-09; `docs/plan/tasks/` has no open task file left. See
`docs/plan/HISTORY.md` — grep `Task 09` — for what landed and what it cost.

### Task 10 — done

Made `main`'s red-on-Linux mTLS refusal assertion hold on both platforms it
runs on, without weakening it. Task 10 merged 2026-09-09, verified green on
Actions run 34386674901; `docs/plan/tasks/` has no open task file. See
`docs/plan/HISTORY.md` — grep `Task 10` — for what landed and what it cost.

## Recommended stopping point — reached 2026-09-09

The plan is now at the point recorded below: every claim the README makes is
true of a deployment, not only of the library. Nothing is open in
`docs/plan/tasks/`. The remaining slices stay ordered under "Someday" but are
not scheduled — picking any of them back up is a new planning decision, not a
continuation of this run.

S10 was deferred past V1 by decision and the index it needs already exists. S11
is the Elasticsearch connector and Docker Compose — real work, no new guarantees,
and the three divergent stub sources it was going to build landed in S6. S12's
mutation and load testing is for a system with users.

### Small open items, unscheduled

Left by task 09's close-out. Neither blocks anything; pick either up only if a
future task already owns the file.

- `PiiLogScanTest.java:191-193` — the sum assertion (`auditCount + nonAuditCount
  == total`) is tautological: the second count is defined as the complement of
  the first, so the assertion cannot fail. The two non-empty assertions either
  side of it are the load-bearing checks and do work. Harmless, but the sixth
  instance in this repository of an assertion that cannot fail — this one came
  from the task brief itself rather than from the implementer.
- `PiiLogScanTest.java:104-112` — `AUDIT_KEYS` holds 19 names while the javadoc
  says "twenty placeholders": the sink's `seq={}/{}` is two placeholders folded
  into one field. `docs/conventions.md` forbids a comment asserting a state
  nobody established; this one should be corrected to 19, or the javadoc
  reworded to explain the fold, next time this file is touched.

Found by the 2026-09-09 documentation audit, not fixed there because none of
it is a doc fix:

- `ArchitectureTest.pseudonymisationIsDeterministic` checks `Random`,
  `SecureRandom`, `UUID.randomUUID` and `System.currentTimeMillis` but not
  `Instant.now()`, which `docs/conventions.md:54` bans in the same sentence.
  Add it to the rule.
- Boundaries 2, 3, 5, 6 in `docs/architecture.md` have no enforcing test — see
  that file's boundaries section for the full enforcement audit. Boundary 6,
  Hazelcast never holding a raw sensitive value, is the one worth a test
  soonest: a violation there is silent and durable, not a build failure or a
  request-time refusal.
- No `SECURITY.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, or issue/PR
  templates. Security reporting is folded into `CONTRIBUTING.md`, which works
  but is non-standard for a public repository.

Found by task 10, not fixed there because neither is that task's work:

- ~~Repository auto-merge is enabled with no branch protection requiring the
  build to pass.~~ **Resolved 2026-09-09.** Branch protection on `main` now
  requires the `build` status check (GitHub Actions), up to date with the
  branch being merged, enforced for admins, with force-push and deletion
  blocked. Auto-merge now has a required check to wait on, so the failure
  mode that let PR #9 merge while its own Actions run was failing (run
  34385475478) is closed. Consequence for the loop: `/record` can no longer
  merge a task branch locally and push straight to `main` — that push is
  itself rejected, since the merged commit has no check run against it yet.
  Every task branch now reaches `main` through a PR whose head commit goes
  green. See `docs/workflow.md`'s Phase 4 for the updated flow.
- The kit's own `/record` skill definition (outside this repository, in
  `~/.claude/commands/`) still describes the old flow — merge locally, push
  straight to `main`. This repository cannot fix it; it belongs to the
  repository owner to update in the kit itself, or the next `/record`
  invocation will attempt a push that branch protection now rejects.
- A seventh cannot-fail assertion:
  `data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/RestDataSourceAdapterHttpTest.java:97`
  is a bare `isInstanceOf(RuntimeException.class)`, the same vacuous form task
  08 removed from its neighbour. Listed in `docs/conventions.md`'s enumerated
  list. A survey of every test module found no other test asserting on a
  platform-specific exception type or message, so the cross-platform problem
  task 10 fixed appears confined to the one test it fixed.

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
