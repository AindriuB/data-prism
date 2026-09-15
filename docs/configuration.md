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
| `dataprism.transport` | `mode` is `http` for protected deployments; `http.path` defaults to `/mcp`; `stdio` is fixture-development only and requires an explicit development profile plus fixture mode | No | Refuse startup for an unknown mode, a non-rooted/invalid path, or `stdio` outside fixture development |
| `dataprism.security.jwt` | `issuer`, `audience`, and exactly one trusted JWKS/issuer-discovery location are required for HTTP | Location is not a secret; any client secret is out of scope and forbidden here | Refuse startup for a missing issuer/audience/location, invalid HTTPS URI (except explicitly local test fixtures), or an unreachable/mismatched issuer at validation time |
| `dataprism.security.caller-claims` | Required mappings for principal, roles, and the trusted investigation/case attribute; purpose remains server policy, not a caller-selected mapping | No | Refuse startup for blank, duplicate, or reserved mappings, or mappings that would derive scope/purpose/case from tool arguments |
| `dataprism.security-policy` | At least one permitted purpose and one role-to-known-capability mapping are required | No | Refuse startup for an empty purpose list, an unknown capability, blank role/purpose, or a role with no capabilities |
| `dataprism.privacy` | `profile` is required; `locale` defaults to the locale-neutral vocabulary; unclassified behaviour is fail-closed; a production scope lifetime is required; `descriptor-file` is optional and, when unset, no descriptor file is ever read and classification comes from annotations alone | No | Refuse startup for an unknown profile, a production profile that relaxes fail-closed behaviour, unsupported locale, non-positive scope lifetime, or a profile that lacks a rule required by exposed models. A configured `descriptor-file` also refuses startup — never falling back to the annotation-only resolver — if the path does not exist, does not name a readable file, has no `models` section, or declares `undeclaredFields: NON_SENSITIVE` for a type; each failure names the property, never a line of the file's contents |
| `dataprism.privacy.hmac-key` | `key-id` and one provider reference/environment-variable name are required; the selected key is pinned for each scope | **Yes, by reference** | Refuse startup if a literal key is configured, the reference is blank/unresolvable, the key is too weak, or `key-id` cannot be resolved; never fall back to a generated key |
| `dataprism.audit` | `sink` is required in production; writer/instance identity is required for a hash-chained sink | Sink credentials are **yes, by reference** | Refuse startup for an unknown sink, missing required sink reference, or missing writer identity; do not downgrade to no-op auditing |
| `dataprism.metrics` | Defaults to the framework's no-op implementation only for fixture development; production requires an approved sink/registry binding | Sink credentials are **yes, by reference** when applicable | Refuse startup in production for an unknown or absent required sink; metrics failures after startup remain fail-safe and cannot change a privacy decision |
| `dataprism.hazelcast` | `topology` is required for a protected deployment and is one of two honest choices, never a default: `embedded` shares the read budget across every member of the cluster, and is the one a multi-instance deployment must choose; `single-node` is a real, supported choice too, but the budget it produces is enforced once per process, so a configured budget of 100 becomes 100 times the number of running processes — identity-cache TTL follows the privacy scope regardless of topology; re-identification index defaults to `false`; persistence/MapStore defaults to disabled | Cluster/TLS credentials are **yes, by reference** when configured | Refuse startup for a missing topology (`MISSING_CLUSTER_TOPOLOGY`), an unknown topology (`UNSUPPORTED_HAZELCAST_TOPOLOGY`), `embedded` with the optional Hazelcast dependency absent from the classpath (`MISSING_SHARED_BUDGET`, never a silent fall back to the per-process budget), persistence/MapStore enablement without an explicit reviewed configuration, invalid member/TLS settings, non-positive TTL, or an enabled index without its required controls |
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
    topology: embedded
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
  `nonSensitive: <reason>`, or `classifications` (with optional `namespace` and
  `action`). A property present in a response but absent from this map is
  refused before it reaches the scrubbing engine, the same UNKNOWN_FIELD
  refusal a Java-first model's own undeclared property gets.

An optional top-level `tls:` block, identical in shape to the one `RestSource`
already supports, requires every source in the file to use `https`.

Startup fails closed and names the offending source for: a missing or
malformed required key, an unknown top-level key, a `subject-json-path`
outside the bounded grammar or not matching a declared identifier field, a
field stating more than one (or none) of its three allowed shapes, and an
unknown classification, namespace or action value. A response that is not a
JSON object, or that is missing at request time, is a source failure or
NO_DATA respectively, not a configuration error.

`dataprism.sources` still governs which adapter names the base distribution's
own contract validator expects; a configured JSON source's name must currently
also appear there (with a `base-url`/`timeout` pair) for that unrelated
cross-check to pass, even though the `json-sources:` catalogue is the
adapter's real, authoritative transport configuration — it is what
`ConfiguredJsonDataSourceAdapter` actually dials. The two `base-url` values
are therefore stated twice, and **startup refuses if they disagree**, naming
both: the `json-sources:` value is authoritative for what gets dialled, so a
`dataprism.sources` entry that silently won the validated-but-unused half of
that check is exactly the hazard closed by refusing rather than picking a
winner. Resolving the duplication itself — so only one statement of a
configured source's transport exists at all — belongs to whichever task next
revisits `DataPrismContractValidator`.

## Ownership boundary

| Concern | Configuration may provide | Application/distribution code must provide |
|---|---|---|
| Inbound security | Issuer, audience, trusted claim names, role-to-capability policy | JWT-to-`AuthenticatedCaller` integration and HTTP resource-server wiring |
| Privacy pipeline | Selected profile, neutral locale, scope lifetime, key reference | Reviewed profile implementation, key-provider integration, and the single scrubbed mapper |
| Sources | Named adapter selection, base URL, timeout, mTLS/credential references | `DataSourceAdapter`, `IdentityResolver`, annotated model types, endpoint paths and response conversion |
| Operations | Audit/metric sink selection and references; embedded Hazelcast settings | Approved sink/registry/provider implementations and lifecycle wiring |

Neither configuration nor application code may make a concrete connector a
dependency of `mcp` or `orchestration`. Those modules see only the core SPI;
the configuration core is the wiring leaf that supplies implementations.
