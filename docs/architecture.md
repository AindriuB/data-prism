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
| `core` | `annotations` | Privacy model, `FieldMetadataResolver`, `PrivacyPolicyResolver`, canonical envelope, provenance, `InvestigationContext`, `SourceTree`, every SPI interface the other modules implement (the SPI interfaces live in `core.spi`), and the audit contract (`AuditEvent`, `AuditSink`, per-writer hash chain) |
| `pseudonymisation` | `core` | HMAC generator, per-namespace synthetic generators, `PseudonymRenderer`, key and algorithm versioning |
| `hazelcast` | `core` | Embedded member, identity cache, re-identification index, read budget (shared only across joined members), scope purge, `FailSafeMetrics` |
| `validation` | `core` | `SensitiveDataScanner`, `LlmResponseValidator`, scope-aware pseudonym allowlist |
| `security` | `core` | `AuthenticatedCaller`, `AuthorizationService`, `PurposeValidator`, `ScopeResolver`, `PrivacySession`, `SecurityPolicy`, `ReservedArguments`, `ToolInvocation` |
| `orchestration` | `core` + the above | `ContextOrchestrator`, parallel fan-out, circuit breaker, request and cost limits, correlation and consistency findings |
| `mcp` | `orchestration`, `security` | Tool definitions, schemas, transport, `DataPrismObjectMapper` |
| `connectors-rest` | `core` | `RestDataSource`, source configuration, resilience |
| `connectors-search` *(planned)* | `core` | Elasticsearch adapter with index and field allowlists |
| `reidentification` | `core`, `hazelcast`, `security` | The controlled reverse-lookup library: authenticated, purpose-bound, audited, optional four-eyes. No transport; its HTTP surface is served by the standalone server on a separate port, in the same process. The index it reads lives in `hazelcast`, off by default |
| `spring-boot-autoconfigure` | the privacy/runtime modules | Shared `dataprism.*` binding, validation, privacy-pipeline wiring, MCP lifecycle and servlet registration |
| `spring-boot-starter` | `spring-boot-autoconfigure` | Dependency-only embedded integration entry point |
| `server` | `spring-boot-autoconfigure` | Primary executable Streamable HTTP MCP server, JWT boundary, health endpoint, production integrations and privacy metrics |
| `integration-tests` | everything, and declares `security` directly | The reactor's cross-module integration test suite: 11 test classes exercising three stub sources with divergent representations end to end, including `PiiLogScanTest`, the sole enforcement of boundary 7 below |

Two directions matter and are easy to get backwards:

- **`mcp` and `orchestration` must not depend on `connectors-*`.** They see
  `DataSourceAdapter` from `core`; the shared auto-configuration core supplies
  implementations at runtime. The pack's §9 dependency chain contradicts its
  own §72 ArchUnit rule on this point — the rule is right.
- **No core, privacy, MCP or orchestration module depends on a wiring or
  distribution module.** `spring-boot-autoconfigure` is the wiring leaf; the
  starter and server intentionally consume that shared core rather than
  implementing separate privacy paths. Nothing else may depend on the starter
  or server except their launchers/examples.

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

**Inbound.** Protected APIs use Streamable HTTP behind an OAuth2 resource
server. Stdio is fixture-only, single-principal development transport; it is
never a route to a protected API. The rule is a closed tool set, not an open
one: `get_entity_context` and `compare_entity_sources` exist today;
`search_entity_data` and `describe_entity_model` are designed (§B5) but not
built, and none of the four is a ceiling that gets relaxed by adding a tool
nobody reviewed. Backend endpoints are never exposed one-to-one — that would
hand privacy and correlation decisions to the caller.

**Outbound.** `RestClient` over mTLS to enterprise APIs, one adapter per source,
endpoints configured server-side only. Elasticsearch through an adapter that
translates a controlled query grammar; raw DSL never reaches it.

**Deployment.** The standalone server is the primary product for protecting
existing APIs; the Spring Boot starter is the embedded integration option. Both
consume the single validated `dataprism.*` contract in
`configuration.md`. Configuration parameterises reviewed adapters but does not
infer classifications, create arbitrary JSON mappings, or let MCP callers
choose a backend. Java-first adapters and annotated models are the general
case, supported now. A configuration-driven JSON REST mode also ships today —
the separately published `connectors-rest` artefact, opted into via
`-Dloader.path`, no Java required — and its allowlisted `fields:` catalogue
covers one level of named nested sub-catalogues: a root field can declare
`nested: <name>` and point at a `nested-catalogues:` entry that is itself a
flat catalogue of scalar/classified leaves, using the root's `nonSensitive`
and classified leaf shapes — a nested catalogue carries no identifier of its
own and inherits its subject from the enclosing record. It goes no deeper
than that: a nested catalogue's own fields cannot themselves declare
`nested:` or `identifier: true`, so recursion is
refused at load time rather than left to depend on whatever the wire happens
to send. There is still no dotted path, no JSONPath and no expression
anywhere in this grammar — `subject-json-path` and every `fields:` or
`nested-catalogues:` key remain a single bare, exact-match property name — so
this mode still cannot reach a second path segment, an array index
expression, or anything outside the one object (or one level of nested
object) the response already is. See `configuration.md` and
`docs/extending.md`.

**Sideways.** An embedded Hazelcast member holding the identity cache, the read
budget, the pause, approval and rate-limit state and — only where a deployment
enables it — the re-identification index. That state is shared across members
that have joined one cluster through an explicit join mode, and is per process
otherwise. Never raw source records, never business caching. Member traffic is
not encrypted or authenticated, so isolating it is the deployer's job; see
`multiple-instances.md`. The identity cache is an
optimisation and losing it changes no answer; the read budget and the
re-identification index are not, and are treated differently for that reason.

**Concurrency.** Virtual threads for the blocking fan-out, with per-source
timeouts, circuit breakers, bulkheads and a bounded pool. An LLM in a retry loop
is the realistic overload, not an attacker.

The diagram below traces one request end to end through these components.

[![Request path from MCP client through authentication, tool authorisation and scope/purpose derivation, then the orchestrator, reviewed source adapters and enterprise APIs; response path through classification and scrubbing (pseudonymise, redact or remove), the raw-value leak check and audit, before the MCP response — no path from an adapter bypasses the privacy engine.](assets/diagrams/system-overview.svg)](assets/diagrams/system-overview.svg)

## Boundaries that must not be crossed

Violating any of these is a defect regardless of how the code reads or whether
tests pass. Each is marked with what actually enforces it — the audited state
as of 2026-09-09. Where the mark is *prose only*, nothing fails the build if
the boundary is crossed; catching a violation depends on review.

1. **No source data reaches `mcp` without passing the privacy engine.** The
   engine walks a data tree (§A4) and is not registered on any mapper. The
   invariant is that only `DataPrismObjectMapper` writes what a model sees, and
   only seven designated classes build a Jackson mapper, so bypassing the engine
   means building a different mapper, which is the thing to look for in review.
   **Enforced, for building** — `ArchitectureTest
   .onlyDesignatedClassesCreateMappers` forbids any class in
   `io.github.aindriub.dataprism..` other than `DataPrismObjectMapper`,
   `SourceTree`, `RestSources`, `SecurityPolicy`, `PrivacyProfiles`,
   `ModelDescriptors` and `VocabularyRegistry` from constructing a mapper, calling
   a static `builder(..)`, calling `build()` on a mapper builder, or calling
   `ObjectMapper#rebuild()`. `ArchitectureTest.designatedYamlReadersDoNotWrite`
   keeps the five YAML readers read-only: they may not call `write*` or
   `writer*`. Negative fixtures prove the first rule still catches a constructor,
   a builder, a `build()` on a passed-in builder and a `rebuild()`. **Not yet
   enforced** — obtaining a mapper rather than building one (for example
   `JsonMapper.shared()`, or injecting Spring Boot's auto-configured mapper) is
   not caught at this point, and neither is the streaming-factory allowlist.
   Task 168 adds both and is not yet merged.
2. **The privacy engine operates on a data tree, not on the Java object graph.**
   Records are immutable and their constructors validate; reflective field
   mutation is not an option and `Unsafe` is not acceptable in a security
   component. **Enforced** — `ArchitectureTest
   .privacyModulesDoNotMutateObjectGraphsReflectively` forbids `Unsafe`,
   reflective field mutation (`Field#set*` and `setAccessible`), and field
   access through `VarHandle` or `MethodHandles` in the privacy modules.
3. **A response that fails validation is not returned.** There is no
   log-and-continue path. **Enforced** — `ValidationBoundaryTest
   .validationFailureIsNotReturned` injects a validator refusal and asserts a
   `PrivacyRefusedException` plus a DENY audit event rather than a response.
4. **The caller never supplies its own scope, principal, purpose or case id.**
   All four derive from the authenticated session. A tool argument claiming any
   of them is ignored and the attempt is audited. **Enforced** — `security`'s
   `ReservedArguments` strips the named keys before a tool call is built, and
   `EndToEndTest` asserts content-equal behaviour between a plain call and one
   carrying the four rejected argument names, plus a single audited refusal.
5. **Re-identification is never an MCP tool.** Separate port, separate
   authorisation scope, mandatory purpose, mandatory audit; the operator surface
   is a second connector in the same process, not a separate application.
   **Enforced** — `ArchitectureTest.noToolSideClassDependsOnReidentification`
   fails the build if `mcp`, `orchestration` or `connectors` depend on the
   `reidentification` module, `ArchitectureTest.onlyReidentificationCallsSubjectFor`
   (with its method-reference check `subjectForRuleCatchesMethodReferences`)
   fails it if any class outside that module calls `ScopeIdentityIndex.subjectFor`,
   and the operator-port tests in `data-prism-server`'s `OperatorSurfaceTest`
   assert that `/operator/**` is served on the operator port only
   (`operatorPathsAreServedOnTheOperatorPortOnly`), that `/mcp` and `/health`
   are not served there (`theMcpEndpointAndHealthAreNotServedOnTheOperatorPort`),
   that an MCP token is refused on the operator port and the reverse
   (`theOperatorPortRefusesAnyTokenWithoutTheOperatorAudienceAndScope`,
   `anOperatorTokenIsNotAcceptedOnTheMcpEndpoint`), and that the tool list
   contains no operator tool (`theOperatorSurfaceIsNotAnMcpTool`).
6. **Hazelcast never holds raw sensitive values** — pseudonyms and subject ids
   only. Subject ids appear in the keys of three maps, and member traffic is
   unencrypted, so the member port must stay on an isolated network. The identity cache never decides a value: every path through it returns
   what the generator would have returned, including the path where the cluster
   is gone. **Enforced** — `HazelcastStoredValueBoundaryTest` drives identity
   caching with re-identification and a read budget, inventories every live map,
   recomputes allowed state from decomposed keys, and rejects both unknown maps
   and the distinctive raw-value fixture in every key and value.
7. **No sensitive value in a log line, metric label, trace attribute, exception
   message or audit record.** Search parameters are fingerprinted with an HMAC
   under the scope key, not hashed. **Partially enforced** — the log half is
   covered by `PiiLogScanTest`, which scans captured log output for stub
   fixture identifying values, and the durable audit file half by
   `AuditFilePiiScanTest` (task 65), which scans `FileAuditSink`'s own output
   the same way. Metric labels and trace attributes are still not scanned by
   any test.
8. **The core carries no business domain.** No `Customer`, `Taxpayer`,
   `Employee` or `Account` type outside the `io.github.aindriub.dataprism.example`
   package (hosted in `data-prism-integration-tests`; task 49 renamed the
   module but deliberately left this package name unchanged).
   **Partially enforced** — `ArchitectureTest.coreDoesNotDependOnOuterLayers`
   checks the dependency direction (`core`, and the `audit` and `oversight`
   packages that live in `data-prism-core` beside it, cannot depend on `mcp`,
   `orchestration`, the `example` package or `pseudonymisation`), but nothing
   scans `core` for a business-domain type directly; a domain type added to
   `core` that no outer module happened to import would pass this rule.

`ArchitectureTest` lives in `data-prism-architecture`, not `example` —
task 23 moved these rules into their own scanning module, built specifically
to see the whole graph: its own javadoc records that `CLASSES` is imported
from every other module's compiled `target/classes` directly, including
`data-prism-connectors-rest`, `data-prism-hazelcast`,
`data-prism-spring-boot-autoconfigure` and `data-prism-server`, none of which
were reachable from the module these rules used to live in.
`ArchitectureCoverageTest`, alongside it, checks that this module list stays
complete. `ArchitectureTest` also enforces two rules not tied to a numbered
boundary above: `securityDoesNotDependOnOuterLayers` (`security` must work
the same from any transport, so it cannot depend on `mcp`, `orchestration`, a
connector or the `example` package) and `onlyTheExampleDependsOnSpringSecurity`
(Spring Security is the example application's own choice for turning a
verified JWT into an `AuthenticatedCaller`; `data-prism-security` itself must
stay framework-agnostic).

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
- **2026-10-06 — Cluster membership is explicit; member transport security is
  the deployer's.** v0.4.0 started a bare member with the cluster name `dev` and
  auto-detection on, so separate instances never clustered and, on Kubernetes,
  could have joined an unrelated cluster. D-0.4.1-A: membership is explicit
  (`cluster-name`, `join.mode`), auto-detection, multicast and phone-home are
  off, the name `dev` is refused, and TLS settings are refused
  (`HAZELCAST_TLS_UNSUPPORTED`) because member TLS and member authentication are
  Hazelcast Enterprise features. D-0.4.1-B: network isolation of the member port
  is the deployer's job, documented in `multiple-instances.md`. Rejected:
  leaving auto-detection on, which costs an unauthenticated join to whatever
  answers; accepting TLS properties that nothing reads, which costs a false
  sense of protection; and a separate Hazelcast cluster, which costs an extra
  system to operate and a hop on every lookup. Accepted consequence: without
  isolation, anyone who can reach the member port can read raw subject ids from
  map keys.
- **2026-10-07 — Jackson 2 stays on Spring Boot 4 (D-139-A); Boot 3 users stay
  on 0.4.x (D-139-B).** Boot 4 defaults to Jackson 3, but the scrubbing engine
  is a Jackson module registered on one `ObjectMapper` and a second Jackson
  major on the classpath would split it, so the classpath is deliberately
  Jackson 2 and the enforcer still bans `tools.jackson.core:*`. Boot marks its
  Jackson 2 support deprecated for removal, so this is a debt to repay, not a
  permanent position. Rejected: moving to Jackson 3 now, which rewrites the
  engine for no privacy gain, and carrying both Boot lines, which doubles the
  support surface; Boot 3 consumers stay on 0.4.x.
  *Superseded by J3-5 (2026-10-08) for D-139-A; D-139-B stands.*
- **2026-10-08 — Jackson 3 throughout (J3-0 to J3-5); supersedes D-139-A.** The
  port is in 0.6.0 and the classpath is Jackson 3 (`tools.jackson`). The engine
  was never a registered module (§A4: it walks a tree), so nothing in it needed a
  second major to be split; the real invariant is that only
  `DataPrismObjectMapper` writes and only the designated classes build mappers.
  Data-prism keeps its own private, fixed mappers (`DataPrismObjectMapper`, the
  `SourceTree` reader and the five YAML readers), built with Jackson 3 builders.
  They are not Spring beans and cannot be customised by application
  configuration, because an adapter author may bring their own `ObjectMapper`
  for their APIs and must never be able to reconfigure data-prism's mapper for
  core behaviour. Spring's own Jackson 3 mapper belongs to the application, not
  to data-prism. The Jackson 3 tree types (`JsonNode`, `ObjectNode`) are public
  where they are the real data (J3-3). J3-3 also decided that the mapper
  is not public: `DataPrismObjectMapper.create()` and any public method that
  hands out or accepts data-prism's mapper are to stop being public, which task
  168 carries out and has not yet merged. The enforcer bans the Jackson
  2 artifacts (`jackson-databind`, `jackson-core`, `jackson-dataformat-*`,
  `jackson-datatype-*`), `mcp-json-jackson2` and `spring-boot-jackson2`, with one
  carve-out: `com.fasterxml.jackson.core:jackson-annotations`, which Jackson 3
  still uses and `mcp-core` needs. Jackson 3 defaults that would change output
  bytes are pinned back to the Jackson 2 values on data-prism's builders. One
  accepted difference: YAML is now parsed as YAML 1.2 (D-167-1), so
  `yes`/`no`/`on`/`off` are text and leading-zero numbers are decimal.
- **2026-10-07 — Images run Java 25; library bytecode stays Java 21.** The build
  uses `--release 21` and a gate checks class major 65, so the jars run on Java
  21 or newer while the published images run Java 25. Rejected: Java 25
  bytecode, which would strand consumers on 21.
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
  surface is deferred past V1.** *Superseded 2026-10-06, see below; the deferral
  is lifted.* Rejected: deferring both, because a reverse map
  added later cannot resolve any pseudonym issued before it existed, so every
  scope created in the interim would be permanently opaque. The map is cheap; the
  surface is what needs the security review, and that can wait.
- **2026-10-06 — Re-identification operator surface is in scope for V1
  (supersedes the 2026-09-08 deferral above).** The owner lifted the deferral so
  the EU AI Act plan (tasks 100, 104, 105, 106) can deliver an audited,
  purpose-bound re-identification path. The reverse map is unchanged; the
  surface needs the security review the original entry said it would. Four-eyes
  for re-identification defaults ON (part of D8). The tool-call approval flow
  is not yet decided. Rejected: keeping the deferral, which would leave the EU
  AI Act plan without an audited, purpose-bound human path to the reverse map;
  and, for where it runs (D4), a separate JVM for the operator surface, in
  favour of a second port in the same process. It costs a new privileged
  surface to harden and review (its own audience, scope and plain-HTTP
  connector, to be bound to an internal address), and the same process now
  serves both ports, so a compromise of the process reaches both.
- **2026-10-06 — The audit file is segmented and purged by Data Prism,
  reversing the v0.3.0 "no rotation" choice** (`CHANGELOG.md` 0.3.0:
  "fsync per record, no rotation"; `audit.md`, "Single file, no rotation").
  The owner (D3, D5, D7) decided that `SegmentedFileAuditSink` writes one
  `audit-YYYY-MM-DD.log` segment per UTC day, that `AuditRetention` deletes
  segments past a retention of six months by default after writing a
  `RETENTION_ANCHOR` checkpoint, and that a shorter period refuses startup
  unless `dataprism.audit.retention-override` is set. `FileAuditSink` is
  unchanged. Rejected: leaving rotation and retention to the operator outside
  Data Prism, because a purge done elsewhere cannot be told apart from
  tampering, and the EU AI Act Arts. 19 and 26(6) retention floor needs a
  stated, checked period. What it costs: Data Prism now deletes audit evidence
  itself, and an anchor is only as trustworthy as the custody of the checkpoint
  file, which must differ from the audit directory's. A checkpoint that cannot
  be written refuses every audited call until one can (D7), which trades
  availability for evidence. A chain that fails to verify in an expiring
  segment deletes nothing. The 2026-09-23 decision below is unchanged: the
  chain is still unkeyed and does not resist an operator.
- **2026-09-13 — One configuration core serves the standalone server and Spring
  Boot starter.** The server is the primary product and the starter an embedded
  option; both bind and validate the same `dataprism.*` vocabulary. Rejected:
  duplicate server/starter wiring, which would let safety rules diverge, and a
  configuration-only arbitrary-JSON adapter, which would make classifications
  and endpoint/schema choices implicit. See ADR 0001 and `configuration.md`.
- **2026-09-08 — Build the walking skeleton first, then thicken it**
  (`development-plan.md`). Rejected: the pack's layer-by-layer Phase 1–9 order,
  which defers proving the privacy boundary end-to-end until the last phase.
- **2026-09-23 — The audit trail's threat model is a user, an operator, AND
  any LLMs: it must be able to trace a sensitive-data exposure back to
  whichever of the three caused it** (§A6, `pack.md` §54). An architect spike
  found that `pack.md` §54, `design-review.md` §A6 and the S9 owner decisions
  all describe the per-writer hash chain mechanism without ever stating whose
  misbehaviour it catches; this closes that gap. Consequence, not a mechanism
  change today: the operator is now explicitly in scope, and the unkeyed
  SHA-256 chain (`AuditEventHash`) does not resist one — anyone with write
  access to the audit file can delete or alter a record and recompute every
  hash after it into a chain that verifies perfectly, as a tester demonstrated
  by injecting a fabricated writer with a self-computed `eventHash`. Rejected:
  keying the chain via `SecretKeyProvider` (the existing precedent being
  `parameterFingerprint`'s HMAC). The key would live in the operator's own
  process, making "the operator cannot have forged this" circular rather than
  a claim this software can make true, and keying would also collapse
  independent third-party verifiability, since only a key-holder could check
  the file. Closing the gap needs external checkpointing (periodic signed
  roots published outside the operator's control) or asymmetric signing with
  the private key held outside the writing process — both real design work
  depending on an operational guarantee this library cannot itself enforce,
  left for the owner to schedule. See `docs/plan/PLAN.md` for the consequences
  and follow-ups this decision opens.
  *Reaffirmed 2026-10-06 (owner decision D2).* The proposal to supersede this
  decision with a keyed chain (task 107) was considered and dropped, for the
  same two reasons: the key would live in the operator's own process, and
  third-party verification would need the key, which also lets its holder
  forge. Tamper evidence rests on the unkeyed hash chain plus external
  checkpoints (task 97), with the checkpoints held under separate custody.
