# Configuration contract

This is the deployment contract for Data Prism. It applies to both supported
surfaces:

- **Standalone server (primary product).** An operator runs the Streamable HTTP
  MCP server in front of existing APIs.
- **Spring Boot starter (embedded option).** An application supplies reviewed
  Java components and receives the same protected HTTP surface.

The two surfaces consume one `dataprism.*` vocabulary and construct one privacy
pipeline. They are not two implementations with subtly different security
rules. Runtime binding and validation of this contract is delivered by the
planned auto-configuration module; until then, the example application's
manual wiring is an implementation detail, not a production configuration API.

## Supported modes

| Mode | Supported use | Required inbound transport | Source integration |
|---|---|---|---|
| Standalone server | Protect existing APIs | Authenticated Streamable HTTP | Reviewed adapters supplied by the server distribution; initially Java-first REST adapters only |
| Spring Boot starter | Embed Data Prism in a Spring application | Authenticated Streamable HTTP | Application-owned `DataSourceAdapter` and `IdentityResolver` beans, with annotated model types |
| Fixture development | Local examples and tests only | stdio, one fixed development principal | Fixture adapters only |

Stdio is never enabled for a protected API, never accepts production caller
credentials, and never represents multiple principals. A profile, environment
variable, Compose file, or agent configuration cannot turn it into a production
transport. Protected APIs use authenticated Streamable HTTP behind an OAuth2
resource server.

A non-servlet Spring application (`spring.main.web-application-type=none` or
WebFlux) using the starter at the default or explicit `mode: http` also
refuses startup rather than running with no MCP transport registered at all:
the Spring auto-configuration's `dataPrismMcpTransportPreflight` bean refuses
with `MCP_TRANSPORT_UNAVAILABLE` before any DataPrism singleton is
constructed. Four codes now mean "this deployment has no usable MCP
transport", each at a different layer: `STDIO_DEVELOPMENT_ONLY` (stdio
requested without fixture development), `STDIO_TRANSPORT_UNSUPPORTED` (stdio
requested inside a Spring context), `STANDALONE_HTTP_ONLY` (the standalone
server only supports protected HTTP), and `MCP_TRANSPORT_UNAVAILABLE` (HTTP
requested or defaulted, but the application is not a servlet web
application). A consumer keying on "no usable MCP transport" must match all
four.

## Configuration rules

- Configuration is server/operator controlled. MCP arguments cannot select a
  host, path, source, schema, field classification, caller, scope, purpose, or
  case identifier.
- A secret is supplied only by a reference to an approved secret provider or by
  the *name* of an environment variable. It is never a literal YAML value. A
  reference is sensitive operational metadata, but it is not secret material
  and may be logged only after review.
- A production deployment fails before accepting traffic when required
  configuration is absent, malformed, unsafe, or cannot be resolved. It must
  not start a server that silently protects no source.
- Names in this document use Spring's relaxed binding spelling. For example,
  `dataprism.security.jwt.jwk-set-uri` may bind to `jwkSetUri` in Java.

## `dataprism.*` vocabulary

The table is the complete V1 vocabulary. `Required` means required in every
protected HTTP deployment unless a row says otherwise. Future implementations
may add keys only with a contract update; unknown keys are configuration errors
for the relevant group.

| Group | Required/default | Secret-bearing | Invalid or absent value |
|---|---|---|---|
| `dataprism.transport` | `mode` is `http` for protected deployments; `http.path` defaults to `/mcp`; `mode: stdio` is refused unconditionally by the Spring auto-configuration, so it is not a reachable value for either the standalone server or the Spring Boot starter — the fixture-development stdio path is `data-prism-integration-tests`'s hand-built entry point, which never goes through this property | No | Refuse startup for an unknown mode, a non-rooted/invalid path, `mode: stdio` (`STDIO_TRANSPORT_UNSUPPORTED`), or `mode: http`/default on a non-servlet application, which has no MCP transport bean at all (`MCP_TRANSPORT_UNAVAILABLE`) |
| `dataprism.security.jwt` | `issuer`, `audience`, and exactly one trusted JWKS/issuer-discovery location are required for HTTP | Location is not a secret; any client secret is out of scope and forbidden here | Refuse startup for a missing issuer/audience/location, invalid HTTPS URI (except explicitly local test fixtures), or an unreachable/mismatched issuer at validation time |
| `dataprism.security.caller-claims` | Required mappings for principal, roles, and the trusted investigation/case attribute; purpose remains server policy, not a caller-selected mapping | No | Refuse startup for blank, duplicate, or reserved mappings, or mappings that would derive scope/purpose/case from tool arguments |
| `dataprism.security-policy` | At least one permitted purpose and one role-to-known-capability mapping are required | No | Refuse startup for an empty purpose list, an unknown capability, blank role/purpose, or a role with no capabilities |
| `dataprism.privacy` | `profile` is required; `locale` defaults to the locale-neutral vocabulary; no `dataprism.*` property loads a custom profile file; the server and starter load the bundled `privacy-profiles-default.yaml`, whose profiles `DEFAULT` and `STRICT` both set `unclassified: FAIL_REQUEST`, refusing the whole response for a field nobody classified; a production scope lifetime is required; `descriptor-file` is optional and, when unset, no descriptor file is ever read and classification comes from annotations alone | No | Refuse startup for an unknown profile, an application `PrivacyPolicyResolver` bean that would replace the framework's profile-backed resolver, unsupported locale, or non-positive scope lifetime. A configured `descriptor-file` also refuses startup — never falling back to the annotation-only resolver — if the path does not exist, does not name a readable file, has no `models` section, or declares `undeclaredFields: NON_SENSITIVE` for a type; each failure names the property, never a line of the file's contents |
| `dataprism.privacy.hmac-key` | `key-id` and one provider reference/environment-variable name are required; the selected key is pinned for each scope | **Yes, by reference** | Refuse startup if a literal key is configured, the reference is blank/unresolvable, the key is too weak, or `key-id` cannot be resolved; never fall back to a generated key |
| `dataprism.audit` | `sink` is required in production, one of `approved-sink`, `slf4j`, `hash-chained`; `writer-id` is required for every sink, not only a hash-chained one; `file-path` is required only when `sink: hash-chained` | Sink credentials are **yes, by reference** | Refuse startup for an unknown sink, missing required sink reference, a hash-chained sink's file path missing or unusable, or missing/invalid writer identity; do not downgrade to no or `slf4j` auditing |
| `dataprism.metrics` | Defaults to the framework's no-op implementation only for fixture development; production requires an approved sink/registry binding | Sink credentials are **yes, by reference** when applicable | Refuse startup in production for an unknown or absent required sink; metrics failures after startup remain fail-safe and cannot change a privacy decision |
| `dataprism.hazelcast` | `topology` is required for a protected deployment and is one of two honest choices, never a default: `embedded` shares the read budget, pause state, approvals and rate limits across members that have joined one cluster, and is the one a multi-instance deployment must choose; `single-node` is a real, supported choice too, but its state is per process, so a configured budget of 100 becomes 100 times the number of running processes. With `embedded`, `cluster-name` (never `dev`), `join.mode` (`tcp-ip`, `kubernetes` or `none`), `join.members`, `join.kubernetes.namespace`, `join.kubernetes.service-name` or `service-dns`, `member.port` (default 5701) and `member.interface` are the cluster properties; see [`dataprism.hazelcast`](#dataprismhazelcast). Identity-cache TTL follows the privacy scope regardless of topology; re-identification index defaults to `false`; persistence/MapStore defaults to disabled | No | Refuse startup for a missing topology (`MISSING_CLUSTER_TOPOLOGY`), an unknown topology (`UNSUPPORTED_HAZELCAST_TOPOLOGY`), `embedded` with the optional Hazelcast dependency absent from the classpath (`MISSING_SHARED_BUDGET`, never a silent fall back to the per-process budget), persistence/MapStore enablement without an explicit reviewed configuration (`UNSAFE_HAZELCAST_PERSISTENCE`), TLS references (`HAZELCAST_TLS_UNSUPPORTED`: member TLS is not available in the open-source distribution), a missing or reserved cluster name (`MISSING_CLUSTER_NAME`, `RESERVED_CLUSTER_NAME`), a missing or unsupported join mode (`MISSING_CLUSTER_JOIN`, `UNSUPPORTED_CLUSTER_JOIN`), invalid members, Kubernetes join, port or interface (`INVALID_CLUSTER_MEMBERS`, `INVALID_KUBERNETES_JOIN`, `INVALID_CLUSTER_PORT`, `INVALID_CLUSTER_INTERFACE`), a member port shared with another listener (`CLUSTER_PORT_SHARED`), cluster settings that would be ignored (`CLUSTER_SETTINGS_IGNORED`), a non-positive TTL (`INVALID_HAZELCAST_TTL`), or an enabled index without its required controls (`MISSING_REIDENTIFICATION_CONTROLS`) |
| `dataprism.oversight` | All optional. `approval-required-tools` defaults to empty; `approval-ttl` `PT15M`; `caller-rate-limit.requests` unset (no limit); `caller-rate-limit.window` `PT1M`; `max-pending-per-requester` `5` | No | Refuse startup for an unknown tool name (`UNKNOWN_OVERSIGHT_TOOL`), a non-positive limit, window, TTL or cap (`INVALID_OVERSIGHT_LIMIT`), or approval-required tools or a rate limit without `dataprism.operator.enabled` (`OVERSIGHT_REQUIRES_OPERATOR_SURFACE`) |
| `dataprism.reidentification` | `enabled` defaults to `false`; `four-eyes` defaults to `true`; `approval-ttl` `PT15M`; `max-pending-per-requester` `5`; `purposes` and `roles` are required once enabled | No | Refuse startup for the refusals listed under [`dataprism.reidentification`](#dataprismreidentification) |
| `dataprism.operator` | `enabled` defaults to `false`; `port`, `required-audience` and `required-scope` are required once enabled; `address` is optional and follows `server.address` when unset | No | Refuse startup for an enabled surface missing any of the required three (`MISSING_OPERATOR_SECURITY`), sharing `server.port` (`OPERATOR_PORT_SHARED`), using the MCP audience (`OPERATOR_AUDIENCE_SHARED`), or an `address` that does not resolve (`INVALID_OPERATOR_ADDRESS`); and `dataprism.reidentification.enabled=true` without the `data-prism-reidentification` module on the classpath (`REIDENTIFICATION_MODULE_MISSING`) |
| `dataprism.sources` | One named source entry per configured Java-first REST adapter; each entry declares a server-controlled HTTPS base URL and positive timeout | mTLS key, trust material, and service credentials are **yes, by reference** | Refuse startup for duplicate names, an unapproved/non-HTTPS URL (local fixture exception only), user-info/query/fragment in a base URL, invalid timeout, unresolved mTLS reference, or a configured source without its explicit adapter bean |

`dataprism.sources.<name>` is intentionally limited to transport parameters
owned by a reviewed adapter: `base-url`, `timeout`, and approved mTLS/service
credential references. It is not a generic request template. An adapter owns
the endpoint path, response type, source-name aliasing, and conversion to an
annotated LLM-exposed model.

### Representative protected deployment

This illustrates names and shape only. The values named with `*_REF` are
references, not secrets, and the configuration binder must reject a literal
key, password, token, or private-key value.

```yaml
dataprism:
  transport:
    mode: http
    http:
      path: /mcp
  security:
    jwt:
      issuer: https://issuer.example.internal
      audience: data-prism-mcp
      jwk-set-uri: https://issuer.example.internal/.well-known/jwks.json
    caller-claims:
      principal: sub
      roles: roles
      investigation: investigation_id
  security-policy:
    purposes: [investigation]
    roles:
      investigator: [GET_ENTITY_CONTEXT]
  privacy:
    profile: DEFAULT
    locale: neutral
    scope-lifetime: 8h
    hmac-key:
      key-id: v1
      environment-variable: DATAPRISM_HMAC_KEY_REF
  audit:
    sink: approved-sink
    writer-id: ${HOSTNAME}
  metrics:
    sink: micrometer
  hazelcast:
    topology: single-node   # see multiple-instances.md for embedded and its cluster settings
    reidentification-enabled: false
  sources:
    customer:
      base-url: https://customer-api.internal
      timeout: 2s
      mtls:
        key-reference: CUSTOMER_MTLS_KEY_REF
        trust-reference: CUSTOMER_MTLS_TRUST_REF
```

The Spring Boot starter additionally requires application beans for every
configured source and for `IdentityResolver`. The standalone server uses the
same validation, but can only expose adapters packaged and reviewed with that
distribution. In either mode, a missing adapter or resolver is a startup
refusal.

### `dataprism.identity.resolver`

Selects a built-in `IdentityResolver` bean for an operator with no Java to
write. `pass-through` is the one accepted value — it selects
`PassThroughIdentityResolver`, correct only when every configured source
genuinely shares the same identifier already; an application-supplied
`IdentityResolver` bean always wins over it, never producing two. Any other
non-blank value refuses startup with `UNSUPPORTED_IDENTITY_RESOLVER`. Leaving
the property unset (or blank) selects nothing: with no `IdentityResolver`
bean present either way — neither the built-in one nor an application-
supplied one — startup refuses with `MISSING_IDENTITY_RESOLVER`.

### `dataprism.audit`

This section describes the protected-deployment path: `mode: http`, in both
the standalone server and the Spring Boot starter. It is not describing
`mode: stdio`. Requested without `fixture-development=true`, that is itself
refused before any of this section's checks run, with `STDIO_DEVELOPMENT_ONLY`
(see "Supported modes" above). Requested with `fixture-development=true`
inside a Spring application, the Spring auto-configuration always refuses
startup, normally with `STDIO_TRANSPORT_UNSUPPORTED` — that combination
never reaches a usable, protected deployment either.

`sink` is required; a missing value refuses startup with `MISSING_AUDIT_SINK`.
It accepts exactly three values:

- `approved-sink` — the deployment must supply its own `AuditSink` bean;
  absent one, startup refuses with `AUDIT_SINK_BEAN_REQUIRED`.
- `slf4j` — the built-in `Slf4jAuditSink`, safe to ship to ordinary log
  infrastructure because nothing read from a source payload reaches it.
- `hash-chained` — the built-in hash-chained `FileAuditSink`. Requires
  `dataprism.audit.file-path`; a missing path refuses startup with
  `MISSING_AUDIT_FILE_PATH`, and a path that cannot be opened (a parent
  directory that does not exist, or one this process cannot write to)
  refuses with `AUDIT_SINK_FILE_UNUSABLE` — startup never falls back to no or
  `slf4j` auditing either way. See [`docs/audit.md`](audit.md) for the chain
  itself, `instanceId`, and the offline verifier.

An unrecognised `sink` value refuses startup with `UNKNOWN_AUDIT_SINK`.

#### Segmented files, checkpoints and retention

| Property | Default | Meaning |
|---|---|---|
| `dataprism.audit.directory` | unset | With `hash-chained`: write one `audit-YYYY-MM-DD.log` segment per UTC day into this directory, instead of one file. Mutually exclusive with `file-path`. |
| `dataprism.audit.checkpoint.file-path` | unset | A separate file receiving `BOOT`, `PERIODIC`, `SHUTDOWN` and `RETENTION_ANCHOR` checkpoints. Required with `directory`. Put it behind different access controls from the audit files, or it protects nothing. |
| `dataprism.audit.checkpoint.interval` | `PT5M` | How often a `PERIODIC` checkpoint is written, the first soon after boot (within ten seconds). A `SHUTDOWN` checkpoint is written when the context closes. |
| `dataprism.audit.retention` | `P6M` | With `directory`: segments dated before today minus this period are deleted, after a `RETENTION_ANCHOR` is recorded for each writer's last record in them. Run once at startup, then every 24 hours. |
| `dataprism.audit.retention-override` | `false` | Must be `true` to accept a `retention` shorter than six months. Inert with a value of six months or more. |

Refusal codes, each at startup:

- `AMBIGUOUS_AUDIT_LOCATION` -- `directory` and `file-path` are both set.
- `RETENTION_REQUIRES_CHECKPOINT` -- `directory` is set without `checkpoint.file-path`, whatever `sink` is.
- `AUDIT_RETENTION_BELOW_MINIMUM` -- `retention` is under six months and `retention-override` is not `true`.
- `INVALID_AUDIT_CHECKPOINT_INTERVAL` -- `checkpoint.interval` is zero or negative.
- `AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE` -- the checkpoint path is the audit `file-path`, or is the audit `directory` or any path inside it. Paths are compared normalised, with symbolic links resolved where they exist.
- `AUDIT_CHECKPOINT_FILE_UNUSABLE` -- the checkpoint path cannot be opened. The path is logged server-side only, never in the message.

The six-month default follows EU AI Act Art. 19, which sets a floor of six
months for automatically generated logs "unless provided otherwise in
applicable Union or national law". Other periods may be lawful under Union or
national law; setting `retention-override` is the operator's own legal
responsibility, and Data Prism does not judge whether such a law applies.

While a checkpoint cannot be written, audited calls are refused with
`AUDIT_CHECKPOINT_UNAVAILABLE` until the next checkpoint succeeds. A purge that
cannot anchor, or whose chain does not verify, deletes nothing and logs the
error.

A purge failure keeps the server running and deletes nothing, and is made
visible two ways. A counter named for the refusal code is incremented:
`dataprism.audit.retention.unverified` (`AUDIT_RETENTION_CHAIN_UNVERIFIED`, a
chain in an expiring segment does not verify, which can be evidence of
tampering), `dataprism.audit.retention.anchor_failed`,
`dataprism.audit.retention.delete_failed` or `dataprism.audit.retention.failed`.
With Spring Boot Actuator present, the `auditIntegrity` health contributor
reports `DOWN` with only `code` and, where the failure names one, `segmentDate`
as details (no paths, no writer ids), until a later purge succeeds, when it
returns to `UP`. Purge runs at startup and then every 24 hours, so a failure
persists at least until the next run.

`auditIntegrity` is part of the aggregate `/actuator/health`, so a tamper
finding turns that aggregate `DOWN`. Do not point a liveness probe at the
aggregate: an orchestrator would restart the process in a loop while the
finding, which a restart does not clear, persists. Use Spring Boot's
liveness and readiness health groups (`/actuator/health/liveness`,
`/actuator/health/readiness`), or the server's own `/health`, for probes, and
alert on `auditIntegrity` or the counter instead. The shipped server exposes
no actuator endpoints, so there the contributor is visible only to an
application that adds Actuator and exposes the health endpoint itself.

With `file-path` (a single file), retention is not enforced in-process: it is
an operator task.

`writer-id` is required for **every** sink, not only `hash-chained` — a
missing one refuses startup with `MISSING_AUDIT_WRITER` regardless of which
sink is configured. It need not be unique per boot: each boot mints its own
`instanceId` as `<writer-id>/<uuid>` (see `docs/audit.md`), so the same
`writer-id` across restarts is expected, not a collision. It must not itself
contain `/` — `AuditRecorder` splits `instanceId` on that character, so a
`writer-id` containing one would make the split ambiguous — and one that does
refuses startup with `INVALID_AUDIT_WRITER`. `${HOSTNAME}` in the example
above is one convenient, non-unique-per-boot choice; it is not a requirement.

A `dataprism.audit.credential-reference`, when configured, must not be
blank; a blank one refuses startup with `INVALID_AUDIT_REFERENCE`.

The diagram below shows the three outcomes a field's classification can lead
to.

[![Fail-closed field decisions: a classified field is pseudonymised, redacted or removed according to its classification; an unclassified field refuses the whole response (FAIL_REQUEST); and a response where something looks like a sensitive identifier shape is also refused.](assets/diagrams/fail-closed-decisions.svg)](assets/diagrams/fail-closed-decisions.svg)
Select the diagram to open it full size.

### `dataprism.hazelcast`

With `topology: embedded`, each instance starts an embedded Hazelcast member, and
the state the members share (identity cache, read budget, pause flags,
approvals, caller-rate windows and, if enabled, the re-identification index) is
shared across members that have joined one cluster. Membership is explicit:
`cluster-name` and `join.mode` are required, and auto-detection, multicast and
phone-home are always off.

- `join.mode: tcp-ip` takes `join.members`, a list of `host` or `host:port`.
- `join.mode: kubernetes` takes `join.kubernetes.namespace` and exactly one of
  `service-name` (API mode) or `service-dns` (DNS mode).
- `join.mode: none` is an explicit single member, bound to `127.0.0.1`, that
  neither discovers nor accepts other members. Its internal cluster name adds a
  a new random suffix each time the member starts, which appears in logs.
- `members` with a mode other than `tcp-ip` is refused (`INVALID_CLUSTER_MEMBERS`),
  and `kubernetes.*` with a mode other than `kubernetes` is refused
  (`INVALID_KUBERNETES_JOIN`).
- Without `member.interface`, a `tcp-ip` or `kubernetes` member binds every
  network interface. Member traffic is not encrypted or authenticated, so isolate
  it on a private network.

The full property list, every refusal code, the Compose and Kubernetes examples
and the network isolation you must supply are on
[Running multiple instances](multiple-instances.md).

## Oversight, re-identification and the operator surface

Three groups, all off or empty by default. Together they turn the oversight and
re-identification libraries into deployed behaviour; the HTTP endpoints that
operate them exist on a second port and are described in
[Re-identification](reidentification.md#the-operator-http-surface).

### `dataprism.oversight`

| Property | Default | Meaning |
|---|---|---|
| `approval-required-tools` | empty | Tools whose calls need a human approval first. Only `get_entity_context` and `compare_entity_sources` are valid |
| `approval-ttl` | `PT15M` | How long an approval request stays usable |
| `caller-rate-limit.requests` | unset | Requests one caller may make per window; unset means no limit |
| `caller-rate-limit.window` | `PT1M` | The rate-limit window |
| `max-pending-per-requester` | `5` | Live pending approvals one requester may hold. A further request is refused with `TOO_MANY_PENDING` and audited as a denial |

The MCP server is always built with an admission check, whatever these
properties say: a paused tool, scope or deployment is refused even when
nothing here is configured. Approval-required calls count against the caller
rate limit, as a call that is refused with `APPROVAL_REQUIRED` or
`APPROVAL_PENDING` has still been made; set a limit with that in mind.

With `dataprism.hazelcast.topology=embedded` and a join mode, the pause state,
the approval store and the rate limiter are shared across members that have
joined one cluster. With `single-node` they are per process. See
[Running multiple instances](multiple-instances.md).

| Code | Condition |
|---|---|
| `UNKNOWN_OVERSIGHT_TOOL` | An approval-required tool name that is neither `get_entity_context` nor `compare_entity_sources` |
| `INVALID_OVERSIGHT_LIMIT` | A non-positive `requests`, `window`, `approval-ttl` or `max-pending-per-requester`, here or under `dataprism.reidentification` |
| `OVERSIGHT_REQUIRES_OPERATOR_SURFACE` | Approval-required tools or a rate limit configured without `dataprism.operator.enabled=true` |

### `dataprism.reidentification`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Builds the `ReidentificationService` bean. It is never an MCP tool: the tool list is the same with it on or off |
| `purposes` | none | The re-identification-only purposes a request may name |
| `roles` | none | Map of role name to a list of `REQUEST` and/or `APPROVE` |
| `four-eyes` | `true` | A second, distinct principal must approve before anything resolves |
| `approval-ttl` | `PT15M` | How long a pending or approved request stays usable |
| `max-pending-per-requester` | `5` | Live pending requests one requester may hold; the cap is enforced in the approval store |

| Code | Condition |
|---|---|
| `REIDENTIFICATION_INDEX_DISABLED` | Enabled with `dataprism.hazelcast.reidentification-enabled=false` |
| `REIDENTIFICATION_REQUIRES_CLUSTER` | Enabled with a topology other than `embedded`: the index lives in the cluster |
| `EMPTY_REIDENTIFICATION_PURPOSES` | Enabled with no non-blank purpose |
| `NO_REIDENTIFICATION_APPROVER` | Enabled with `four-eyes=true` and no role holding `APPROVE` |
| `REIDENTIFICATION_REQUIRES_OPERATOR_SURFACE` | Enabled without `dataprism.operator.enabled=true` |

`MISSING_REIDENTIFICATION_CONTROLS` and `dataprism.hazelcast.reidentification-controls-reference`
keep their existing meaning and are checked first.

### `dataprism.operator`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Turns the operator surface on |
| `port` | none | The operator port; must differ from `server.port` (default `8080`) |
| `required-audience` | none | The JWT audience an operator token must carry |
| `required-scope` | none | The scope an operator token must carry |
| `address` | follows `server.address` | The address the operator connector binds to, for example `127.0.0.1`. Set independently of `server.address` so the operator port can stay on an internal interface |

| Code | Condition |
|---|---|
| `MISSING_OPERATOR_SECURITY` | Enabled without a valid `port`, `required-audience` or `required-scope` |
| `OPERATOR_PORT_SHARED` | `port` equal to `server.port` |
| `OPERATOR_AUDIENCE_SHARED` | `required-audience` equal to `dataprism.security.jwt.audience`, which would let one token serve both surfaces |
| `INVALID_OPERATOR_ADDRESS` | `address` is set and cannot be resolved to an address |
| `REIDENTIFICATION_MODULE_MISSING` | `dataprism.reidentification.enabled=true` with `data-prism-reidentification` absent from the classpath. The standalone server carries it; an embedded application using the starter must add it |

## Java-first now; generic JSON as a separately reviewed extension

V1 is **Java-first**. A source adapter is application/distribution code with an
annotated response model. Configuration selects and parameterises that reviewed
adapter; it does not classify a DTO from its name or infer safety from an API
endpoint.

A configuration-driven JSON REST mode exists, but it is never a hidden
interpretation of `dataprism.sources`, and it is never part of the base
standalone server distribution. It ships in `data-prism-connectors-rest`, a
separate artefact an operator opts into the same way as any other reviewed
adapter: `-Dloader.path=<data-prism-connectors-rest jar>`. Absent that jar and
its one property, a deployment is unaffected and this section does not apply.

### `dataprism.json-sources.config-location`

The one property this mode reads: a Spring resource location (`classpath:` or
`file:`) naming a YAML file with its own `json-sources:` root key, structurally
unrelated to `dataprism.sources` and never merged with it. Each named entry
under `json-sources:` states, and only states:

- `base-url`, `path` (a fixed template with exactly one `{subject}` segment)
  and `timeout` — server-owned transport, identical in kind to a Java-first
  source's, and this mode never defaults the timeout the way the Java-first
  loader does; it must be stated explicitly.
- `model-version` — an explicit, non-blank tag an operator commits to,
  reviewed alongside the catalogue below. It is a deployment-time contract, not
  a runtime check against the response body: a REST API rarely states its own
  schema version on every payload.
- `subject-json-path` — a bare property name (`^[A-Za-z_][A-Za-z0-9_]*$`; no
  dots, no scheme, no query, no second segment) naming the response field that
  carries this source's own correlation identifier. It must name an entry in
  `fields:` marked `identifier: true`, and nothing else about the grammar lets
  it reach anywhere outside the object the response already is.
- `fields:` — the allowlisted field classification catalogue, keyed by exact
  JSON property name. Every field this source may ever emit, including its
  subject field, must be named here with exactly one of `identifier: true`,
  `nonSensitive: <reason>`, `classifications` (with optional `namespace` and
  `action`), or `nested: <name>`. A property present in a response but absent
  from this map is refused before it reaches the scrubbing engine, the same
  UNKNOWN_FIELD refusal a Java-first model's own undeclared property gets.
- `nested-catalogues:` — a top-level map, keyed by catalogue name, of the
  named catalogues a root field's `nested: <name>` refers to. Nesting is
  exactly one level: a nested catalogue's own entries may be `nonSensitive` or
  classified only — stating `identifier:` or a further `nested:` inside one is
  refused at load time, naming the offending catalogue and field, since a
  nested catalogue carries no identifier of its own and this mode never
  descends a second level.

A response whose shape no longer matches a source's declared nesting is a
request-time privacy refusal, not a startup refusal — the catalogue was valid
at load time; the wire shape drifted from what it declared. `data-prism-connectors-rest`
raises `NESTED_LEAF_NOT_SCALAR` when a nested catalogue's own leaf field (one
with no `nested:` of its own) turns up as a structure in the response, and
`NESTED_FIELD_NOT_STRUCTURED` when a root field declared `nested:` turns up as
a scalar instead of the structure the catalogue expects. See
[`docs/protect-your-own-api.md`](protect-your-own-api.md)'s "A nested
response" section, which works `NESTED_LEAF_NOT_SCALAR` through a direct scrub
call (`NestedCatalogueWalkthrough.java`) rather than a running adapter, and
names `NESTED_FIELD_NOT_STRUCTURED` without a worked example of it.

An optional top-level `tls:` block, identical in shape to the one `RestSource`
already supports, requires every source in the file to use `https`.

Startup fails closed and names the offending source for: a missing or
malformed required key, an unknown top-level key, a `subject-json-path`
outside the bounded grammar or not matching a declared identifier field, a
field stating more than one (or none) of its four allowed shapes, an unknown
classification, namespace or action value, a `nested:` field naming an
undeclared catalogue, a declared catalogue referenced by no field, and
`identifier:` or `nested:` stated inside a nested catalogue's own entries. A
response that is not a JSON object, or that is missing at request time, is a
source failure or NO_DATA respectively, not a configuration error, and
neither is the nesting-shape mismatch described above.

A configured JSON source's transport is stated exactly once, in
`json-sources:`; it does not also need a `dataprism.sources` entry, and
`DataPrismContractValidator` does not require one — the base distribution's
contract validator recognises the `DataSourceAdapter` bean this mode
registers as reviewed without it appearing under `dataprism.sources` at all.
Naming it there too is allowed but never required; if you do, its `base-url`
must agree with the `json-sources:` value, and **startup refuses, naming
both, if they disagree** — the `json-sources:` value is always the
authoritative one actually dialled, so a `dataprism.sources` entry that
silently won an unused, disagreeing check would be exactly the hazard this
refusal closes.

## The Compose quickstart's evaluation issuer

`data-prism-quickstart-issuer` (see `docs/quickstart.md`) is a fixture-only
evaluation JWT issuer, published as
`ghcr.io/aindriub/data-prism-quickstart-issuer`. It mints tokens for one
synthetic issuer/audience pair only, and is never a general-purpose identity
provider. Pointing a deployment's `dataprism.security.jwt.jwk-set-uri` at
it — its public signing key is served at `/jwks`, not
`/.well-known/jwks.json` — is only ever useful for local evaluation: doing so
still does not permit an unauthenticated call, since every request must carry
a token this issuer minted, signature-verified against that JWKS location
like any other. It does not, and cannot, substitute for
`dataprism.transport.fixture-development=true`, which the standalone server
refuses unconditionally regardless of which issuer a deployment trusts —
authenticating against this evaluation issuer is a real, if fixture-scoped,
authenticated HTTP deployment, never the stdio fixture-development path.

## Ownership boundary

| Concern | Configuration may provide | Application/distribution code must provide |
|---|---|---|
| Inbound security | Issuer, audience, trusted claim names, role-to-capability policy | JWT-to-`AuthenticatedCaller` integration and HTTP resource-server wiring |
| Privacy pipeline | Selected profile, neutral locale, scope lifetime, key reference | Reviewed profile implementation, key-provider integration, and the single scrubbed mapper |
| Sources | Named adapter selection, base URL, timeout, mTLS/credential references | `DataSourceAdapter`, annotated model types, endpoint paths and response conversion. `IdentityResolver` too, unless `dataprism.identity.resolver: pass-through` selects the built-in `PassThroughIdentityResolver` instead |
| Operations | Audit/metric sink selection and references; embedded Hazelcast settings | Approved sink/registry/provider implementations and lifecycle wiring |

Neither configuration nor application code may make a concrete connector a
dependency of `mcp` or `orchestration`. Those modules see only the core SPI;
the configuration core is the wiring leaf that supplies implementations.
