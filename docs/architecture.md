# Architecture

The shape of the system: what the pieces are, what talks to what, and where the
boundaries are. `explorer` and `architect` read this before inferring structure
from the filesystem, so keeping it honest saves every session a search.

Modules marked **planned** in the table below do not exist on disk yet; they are
the target shape agreed in `design-review.md`. Everything else is built and
tested. Move a row out of *planned* when its module lands, and keep this file
honest — an architecture document that describes something other than the code
costs more than having none.

## What the system does

An MCP client asks for context about an entity. Data Prism authenticates the
caller, resolves a privacy scope from the authenticated session, fans out to the
enterprise APIs that hold that entity, normalises what comes back, correlates it,
replaces sensitive values with deterministic pseudonyms, checks the result for
leaks, audits the whole thing, and only then answers.

Two things stay separate throughout, and confusing them is the most likely way to
get this wrong:

- **Identity is made consistent.** The same subject gets the same pseudonym in
  every source, within one scope.
- **Data is not.** If three systems disagree about a name, the answer says so.
  The platform never makes the data look cleaner than it is.

## Components

Modules, in dependency order. Arrows point at what a module is allowed to depend
on; anything not listed is forbidden and is enforced by ArchUnit rather than by
review.

| Module | Depends on | Owns |
|---|---|---|
| `annotations` | — | `@InternalIdentifier`, `@SubjectIdentifier`, `@SensitiveData`, `@NonSensitive`, `@SensitiveObject`, `@LlmExposedModel`, the classification/action/namespace enums |
| `processor` | `annotations` | `LlmExposedModelProcessor`, the annotation processor that fails the build on a field of an `@LlmExposedModel` carrying neither `@SensitiveData` nor `@NonSensitive(reason=...)` (§B2) |
| `core` | `annotations` | Privacy model, `FieldMetadataResolver`, `PrivacyPolicyResolver`, canonical envelope, provenance, `InvestigationContext`, `SourceTree`, and every SPI interface the other modules implement |
| `pseudonymisation` | `core` | HMAC generator, per-namespace synthetic generators, `PseudonymRenderer`, key and algorithm versioning |
| `hazelcast` | `core` | Embedded member, identity cache, re-identification index, shared read budget, scope purge, `FailSafeMetrics` |
| `validation` | `core` | `SensitiveDataScanner`, `LlmResponseValidator`, scope-aware pseudonym allowlist |
| `security` | `core` | `AuthenticatedCaller`, `AuthorizationService`, `PurposeValidator`, `ScopeResolver`, `PrivacySession`, `SecurityPolicy`, `ReservedArguments`, `ToolInvocation` |
| `audit` | `core` | `AuditEvent`, `AuditSink`, per-writer hash chain |
| `orchestration` | `core` + the above | `ContextOrchestrator`, parallel fan-out, circuit breaker, request and cost limits, correlation and consistency findings |
| `mcp` | `orchestration`, `security` | Tool definitions, schemas, transport, `DataPrismObjectMapper` |
| `connectors-rest` | `core` | `RestDataSource`, source configuration, resilience |
| `connectors-search` *(planned)* | `core` | Elasticsearch adapter with index and field allowlists |
| `reidentification` *(planned)* | `hazelcast`, `security`, `audit` | The controlled reverse-lookup surface. Separate application, separate port. The index it reads already exists in `hazelcast`, off by default |
| `spring-boot-starter` *(planned)* | everything | Auto-configuration and wiring |
| `example` | everything, and declares `security` directly | Three stub sources with divergent representations, the runnable server, `MicrometerPrivacyMetrics`, `JwtCallerContextExtractor` |

Two directions matter and are easy to get backwards:

- **`mcp` and `orchestration` must not depend on `connectors-*`.** They see
  `DataSourceAdapter` from `core`; the starter supplies implementations at
  runtime. The pack's §9 dependency chain contradicts its own §72 ArchUnit rule
  on this point — the rule is right.
- **Nothing depends on `spring-boot-starter`** except the example. It is the
  wiring leaf.

## Repository topology

One repository, one Maven reactor. There are no sibling project repositories.

`.claude/` is untracked on purpose: it is a plain directory (verified with
`fsutil reparsepoint query .claude` — it is not a reparse point), populated by
installing an agent kit whose single clone lives outside every project it
serves and is refreshed with `git -C <kit> pull`. `~/.claude/agents` and
`~/.claude/commands`, not anything under this repository's `.claude/`, are the
symlinks into that kit, shared by every project. Tracking any of it here would
fork the harness from its upstream, and committing its `settings.json` would
hand every contributor a tool-permission allowlist they never reviewed.
`CONTRIBUTING.md` says how to install it.

`.worktrees/` is untracked and holds one checkout per running task.

`docs/pack.md` is the original specification, copied in verbatim so agents do not
have to reach outside the repository for it. It is a historical document and is
not edited. `docs/design-review.md` amends it and wins wherever the two disagree.

## How they talk

**Inbound.** MCP over stdio in development, streamable HTTP in production, behind
an OAuth2 resource server. The rule is a closed tool set, not an open one: only
`get_entity_context` exists today; `compare_entity_sources`, `search_entity_data`
and `describe_entity_model` are designed (§B5) but not built, and none of the
four is a ceiling that gets relaxed by adding a tool nobody reviewed. Backend
endpoints are never exposed one-to-one — that would hand privacy and correlation
decisions to the caller.

**Outbound.** `RestClient` over mTLS to enterprise APIs, one adapter per source,
endpoints configured server-side only. Elasticsearch through an adapter that
translates a controlled query grammar; raw DSL never reaches it.

**Sideways.** An embedded Hazelcast member holding the identity cache, the shared
read budget and — only where a deployment enables it — the re-identification
index. Never raw source records, never business caching. The identity cache is an
optimisation and losing it changes no answer; the read budget and the
re-identification index are not, and are treated differently for that reason.

**Concurrency.** Virtual threads for the blocking fan-out, with per-source
timeouts, circuit breakers, bulkheads and a bounded pool. An LLM in a retry loop
is the realistic overload, not an attacker.

## Boundaries that must not be crossed

Violating any of these is a defect regardless of how the code reads or whether
tests pass. Each is marked with what actually enforces it — the audited state
as of 2026-09-09. Where the mark is *prose only*, nothing fails the build if
the boundary is crossed; catching a violation depends on review.

1. **No source data reaches `mcp` without passing the privacy engine.** The
   engine is installed as a Jackson module on the `ObjectMapper` the MCP layer
   uses, so bypassing it means constructing a different mapper — which is the
   thing to look for in review. **Enforced** — `ArchitectureTest
   .onlyDesignatedClassesCreateMappers` forbids any class other than
   `DataPrismObjectMapper` and `SourceTree` from constructing an `ObjectMapper`.
2. **The privacy engine operates on a data tree, not on the Java object graph.**
   Records are immutable and their constructors validate; reflective field
   mutation is not an option and `Unsafe` is not acceptable in a security
   component. **Prose only.**
3. **A response that fails validation is not returned.** There is no
   log-and-continue path. **Prose only.**
4. **The caller never supplies its own scope, principal, purpose or case id.**
   All four derive from the authenticated session. A tool argument claiming any
   of them is ignored and the attempt is audited. **Enforced** — `security`'s
   `ReservedArguments` strips the named keys before a tool call is built, and
   `EndToEndTest` asserts content-equal behaviour between a plain call and one
   carrying the four rejected argument names, plus a single audited refusal.
5. **Re-identification is never an MCP tool.** Separate application, separate
   port, separate authorisation scope, mandatory purpose, mandatory audit.
   **Prose only** — the module does not exist yet (S10, deferred).
6. **Hazelcast never holds raw sensitive values** — pseudonyms and subject ids
   only. The identity cache never decides a value: every path through it returns
   what the generator would have returned, including the path where the cluster
   is gone. **Prose only.** No test scans what `hazelcast` writes to its maps;
   this is the boundary worth a test soonest, because a violation here is
   silent and durable rather than a build failure or a request-time refusal.
7. **No sensitive value in a log line, metric label, trace attribute, exception
   message or audit record.** Search parameters are fingerprinted with an HMAC
   under the scope key, not hashed. **Partially enforced** — the log half is
   covered by `PiiLogScanTest`, which scans captured log output for stub
   fixture identifying values. Metric labels, trace attributes and audit
   records are not scanned by any test.
8. **The core carries no business domain.** No `Customer`, `Taxpayer`,
   `Employee` or `Account` type outside `example/`. **Partially enforced** —
   `ArchitectureTest.coreDoesNotDependOnOuterLayers` checks the dependency
   direction (core cannot depend on `mcp`, `orchestration`, `example` or
   `pseudonymisation`), but nothing scans `core` for a business-domain type
   directly; a domain type added to `core` that no outer module happened to
   import would pass this rule.

`ArchitectureTest` (in `example`, the only module that sees the whole graph)
also enforces two rules not tied to a numbered boundary above:
`securityDoesNotDependOnOuterLayers` (`security` must work the same from any
transport, so it cannot depend on `mcp`, `orchestration`, a connector or
`example`) and `onlyTheExampleDependsOnSpringSecurity` (Spring Security is the
example's own choice for turning a verified JWT into an `AuthenticatedCaller`;
`data-prism-security` itself must stay framework-agnostic).

## Decisions worth knowing

One line each, with the date and the alternative rejected. Longer reasoning for
all of these is in `design-review.md` under the section named.

- **2026-09-08 — Pseudonymisation is keyed on an explicit subject, not the
  record's own identifier** (§A1). Rejected: one `@InternalIdentifier` per
  record, which merges two people named in one record into a single pseudonym.
- **2026-09-08 — Pseudonyms carry a deterministic discriminator** (§A2).
  Rejected: bare generated names, which collide by the birthday bound at roughly
  6,000 subjects in a scope.
- **2026-09-08 — Correlation requires a resolvable key, behind an
  `IdentityResolver` SPI** (§A3). Rejected: probabilistic entity matching, which
  is a different product and would change the orchestrator's shape if bolted on.
- **2026-09-08 — Scrubbing operates on a Jackson tree** (§A4). Rejected:
  reflective object-graph scrubbing, which cannot write to records.
- **2026-09-08 — The output validator allowlists this scope's own pseudonyms**
  (§A5). Rejected: naive pattern detection, which rejects every synthesised
  email and deadlocks the fail-closed path.
- **2026-09-08 — The audit hash chain is per writer, not global** (§A6).
  Rejected: a single chain, which stateless horizontally-scaled instances fork
  into something indistinguishable from tampering.
- **2026-09-08 — Fail-closed requires `@NonSensitive(reason=...)` and an
  annotation processor** (§B2). Rejected: runtime-only fail-closed, which
  developers defeat by marking everything `PASS_THROUGH`.
- **2026-09-09 — Hazelcast runs embedded, reversing the earlier decision** (§C4).
  The original objection was that members in an autoscaled deployment rebalance
  partitions on every scale event, which sounded ruinous for the map that decides
  what a subject is called. It is not, and the reason is a property this codebase
  now actually has: a synthetic value is a pure function of scope, subject,
  namespace and key, so a lost partition costs a recomputed HMAC and never a
  different answer. Rebalancing produces cache misses, not renamed people.
  Embedded removes a cluster to operate and a hop from every lookup.
  Accepted consequence: the re-identification index is a store rather than a
  cache — nothing can recompute a subject id from a pseudonym — so its durability
  is the cluster's durability, and an embedded cluster scaled to zero loses it.
  That index is therefore off unless a deployment enables it deliberately.
- **2026-09-09 — Metrics are guarded at construction, not at each call site**
  (`FailSafeMetrics`). A wrapper applied once when the metrics implementation
  is built, so every emit site added later is guarded by construction rather
  than by a reviewer remembering to wrap it. Rejected: guarding individual
  emit calls, which the S8 wave 2 Hazelcast metrics work tried twice and
  missed a site each time — once leaving a broken fail-open guarantee reachable, once
  leaving a known-bad cached value in place because a throwing metric aborted
  the corrective write that should have followed it. The standing guarantee
  this buys: a conflicting meter registration, or any other metrics failure,
  can never fail a lookup.
- **2026-09-09 — The read budget fails closed; the identity cache fails open.**
  They look alike and are opposites. An unreachable identity cache costs
  computation and changes no answer, so it degrades. An unreachable budget means
  nobody is counting, and continuing would silently remove the only limit on how
  much a caller can extract about one subject.
- **2026-09-09 — Data Prism is an OAuth2 resource server, not a token issuer and
  not a pass-through.** It validates caller JWTs against a configured JWKS; it
  never forwards the caller's token upstream, calling source systems under its
  own service identity via mTLS instead. Rejected: pass-through, because the
  caller is the untrusted party and forwarding its token would recreate the
  `LLM → Enterprise APIs` path that `pack.md` §87 asks network policy to make
  impossible. It would also collapse two different authorisation questions —
  "may this service fetch this record" and "may this caller see a
  pseudonymised view of it" — into the first. This makes Data Prism a confused
  deputy by construction, which is why `AuthorizationService` is a separate
  contract from token verification: verification answers who the caller is,
  authorization answers what the caller may see, and neither is allowed to
  stand in for the other. RFC 8693 token exchange is a deliberate non-goal for
  this slice.
- **2026-09-08 — Coordinates are `io.github.aindriub` / `data-prism-*`, package
  root `io.github.aindriub.dataprism`.** Rejected: a group id under a project
  domain, which reads better but requires owning one — every close spelling of
  `dataprism` is registered, and `data-prism.io` is free but cannot be a Java
  package because hyphens are illegal in package names. `io.github.<user>` is
  verifiable on Maven Central through the GitHub account alone. The cost is that
  moving to an organisation later is a breaking coordinate change.
- **2026-09-08 — The MCP layer uses the official MCP Java SDK directly**, with
  Spring wiring written here; stdio in development, streamable HTTP in
  production. Rejected: the Spring AI MCP server starter, which supplies the
  transport for free but couples the `mcp` module to Spring AI's release train,
  and whose auto-registration of `@Tool` beans is the expose-everything pattern
  the specification forbids. Verify the SDK coordinates and version at build
  time; this ecosystem moves faster than any document about it.
- **2026-09-08 — No vendor client in core, for either secrets or audit.** The
  HMAC key arrives at startup through a `SecretKeyProvider` SPI and lives in
  application memory; `AuditSink` ships a file/SLF4J implementation only.
  Rejected: bundling Vault, a cloud secrets client or Kafka, which would make
  every consumer of a generic library inherit a dependency and a deployment
  assumption they did not choose. Accepted consequence: a memory disclosure
  exposes the key for every scope, where a KMS-derived per-scope subkey would
  have limited it to live scopes. Reference implementations, if any, go in
  optional modules.
- **2026-09-08 — Re-identification: the reverse map is built in S7, the operator
  surface is deferred past V1.** Rejected: deferring both, because a reverse map
  added later cannot resolve any pseudonym issued before it existed, so every
  scope created in the interim would be permanently opaque. The map is cheap; the
  surface is what needs the security review, and that can wait.
- **2026-09-08 — Build the walking skeleton first, then thicken it**
  (`development-plan.md`). Rejected: the pack's layer-by-layer Phase 1–9 order,
  which defers proving the privacy boundary end-to-end until the last phase.
