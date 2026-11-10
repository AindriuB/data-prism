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

### Strict keys in YAML configuration files

Six readers parse a YAML file by hand, not through Spring's property binding.
Each refuses a mistake at startup rather than loading a configuration that
protects less than the file says.

| Reader | Where the file comes from |
|---|---|
| `ModelDescriptors` | `dataprism.privacy.descriptor-file` |
| `ConfiguredJsonSources` | `dataprism.json-sources.config-location` |
| `PrivacyProfiles` | the bundled `privacy-profiles-default.yaml` (Spring), or `PrivacyProfiles.fromYaml(InputStream)` |
| `VocabularyRegistry` | the seven bundled vocabularies (Spring), or `VocabularyRegistry.builder().load(InputStream)` |
| `SecurityPolicy` | `SecurityPolicy.fromYaml(InputStream)`; the Spring path builds it from `dataprism.security-policy.*` properties instead |
| `RestSources` | `RestSources.fromYaml(InputStream)` |

Every refusal in the table below is an `IllegalArgumentException` whose
message starts with the stable code and `: `. The message names the kind of
file, the path to the mapping and the offending key (each path segment cut to
64 characters, control characters replaced), and never a value. Other
refusals from these readers keep their older messages, and some of those
(an unknown enum name, an unknown capability, a non-numeric band bound) do
repeat the configured schema value, which is useful to the operator; treat
those messages as operator-visible only.

| Code | Triggered by |
|---|---|
| `DUPLICATE_CONFIG_KEY` | a mapping, at any depth, with the same key twice. Before this, the last one silently won. This includes user-chosen names: two models, fields, profiles, sources or roles with the same name. |
| `UNKNOWN_CONFIG_KEY` | a key outside the allowed set of a fixed-schema mapping, at any depth: a root, a model, a field, a profile, a rule, a generalization rule, a source, `tls`, a vocabulary file, or `pools` (an unknown pool name is an unknown key). Before this, most readers ignored it. |
| `NON_STRING_CONFIG_SCALAR` | a string-typed field (a purpose, a path, a `base-url`, a `timeout`, a `subject`, `identifier`, `nonSensitive`, a `unit`, a vocabulary `id`, `locale`, `script` or pool entry, an enum name) written as a number, a boolean or an empty value. Quote it. |
| `INVALID_CONFIG_BOOLEAN` | a boolean-typed field (`exposed`, `descendable`, `override`, `identifier`) that is not exactly `true` or `false`. `yes`, `no`, `on`, `off`, `True` and `1` are refused: YAML 1.2 reads the first four as text, and 0.5.x read them as booleans. |
| `LEADING_ZERO_CONFIG_NUMBER` | a numeric-typed field (a band bound, a vocabulary `version`) written with a leading zero, such as `010`, `0777` or `-01`. 0.5.x read these as octal. A plain `0` and a decimal such as `0.5` are fine. The parser cannot tell a quoted `"010"` from `010`, so quoting does not help; write `10`. |
| `UNSUPPORTED_CONFIG_YAML` | an alias (`*name`), an anchor (`&name`) or an explicit tag (`!custom`, `!!int`). Only plain YAML is accepted: an alias would be read as the text of its anchor name, and a tag changes how a scalar resolves behind the readers' checks. An anchor on a key is refused too. A tag on a key cannot be seen by the parser; the key is still read as its plain text, so it has no effect. |
| `INVALID_CONFIG_SHAPE` | a section that is present but not the required shape: `classifications`, `generalization`, `fields`, `pools`, `roles`, `tls` and `nested-catalogues` must be mappings, and each pool must be a list. An empty value (`fields:` with nothing after it) is also refused; omit the key instead. Before this they were silently ignored. |
| `NULL_LIKE_CONFIG_SCALAR` | a string field whose text is `~`, `Null` or `NULL`. YAML 1.2 does not make these null here, and 0.5.x read `~` as absent. Quoting does not help (the parser cannot tell the two apart); write the text you mean. An empty value or lower-case `null` in a string field is `NON_STRING_CONFIG_SCALAR`. |
| `TRAILING_CONFIG_CONTENT` | a second YAML document (`---` followed by more), or anything after the first. A bare trailing `---` counts as a second, empty document and is refused; a `...` document-end marker is accepted. |

A file whose text is not YAML, or whose root is not a mapping, is still
reported as "could not be read".

A configuration that loaded on 0.5.x may now refuse to start. This is
intended: each of these used to change what the file meant without any error.

The Spring path reports a refusal from the descriptor file as
`INVALID_MODEL_DESCRIPTOR_FILE` and does not repeat the file's content. The
reader's inner code (for example `UNKNOWN_CONFIG_KEY` or `DUPLICATE_CONFIG_KEY`)
is chained as the cause, so the operator learns which rule the file broke. The
cause carries the code, the document path and the key name only, never a value;
a reader message that has no code is not chained.

**Quoting rule.** A value that must be text and could be read as something
else has to be quoted. `timeout: PT2S` and `model-version: customer-v1` are
fine as written, but `model-version: 1.0` is a number, so write
`model-version: "1.0"`. A value that starts a YAML number (`1`, `1.5`, `true`)
in a string field is `NON_STRING_CONFIG_SCALAR`.

`ConfiguredJsonSources` shares its tls and key handling with `RestSources`, so
it is subject to the same rules; unlike 0.5.x it also refuses an unknown root
key other than `json-sources` and `tls`.

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
| `dataprism.security-policy` | At least one permitted purpose and one role-to-known-capability mapping are required. Case authorisation and the client allowlist are each an explicit decision with no default: see [`dataprism.security-policy`: case authorisation and client allowlist](#dataprismsecurity-policy-case-authorisation-and-client-allowlist) | No | Refuse startup for an empty purpose list, an unknown capability, blank role/purpose, or a role with no capabilities; and for an undecided case authorisation or client allowlist, or an inconsistent decision (ten codes, listed in that section) |
| `dataprism.privacy` | `profile` is required; `locale` defaults to the locale-neutral vocabulary; no `dataprism.*` property loads a custom profile file; the server and starter load the bundled `privacy-profiles-default.yaml`, whose profiles `DEFAULT` and `STRICT` both set `unclassified: FAIL_REQUEST`, refusing the source whose record holds a field nobody classified (that source is withheld and marked `REFUSED`; the call is refused only when every source that answered is refused); a production scope lifetime is required; `descriptor-file` is optional and, when unset, no descriptor file is ever read and classification comes from annotations alone | No | Refuse startup for an unknown profile, an application `PrivacyPolicyResolver` bean that would replace the framework's profile-backed resolver, unsupported locale, or non-positive scope lifetime. A configured `descriptor-file` also refuses startup — never falling back to the annotation-only resolver — if the path does not exist, does not name a readable file, has no `models` section, or declares `undeclaredFields: NON_SENSITIVE` for a type; each failure names the property, never a line of the file's contents |
| `dataprism.privacy.hmac-key` | `key-id` and one provider reference/environment-variable name are required; the selected key is pinned for each scope | **Yes, by reference** | Refuse startup if a literal key is configured, the reference is blank/unresolvable, the key is too weak, or `key-id` cannot be resolved; never fall back to a generated key |
| `dataprism.audit` | `sink` is required in production, one of `approved-sink`, `slf4j`, `hash-chained`; `writer-id` is required for every sink, not only a hash-chained one; `file-path` is required only when `sink: hash-chained` | Sink credentials are **yes, by reference** | Refuse startup for an unknown sink, missing required sink reference, a hash-chained sink's file path missing or unusable, or missing/invalid writer identity; do not downgrade to no or `slf4j` auditing; `entity-types` is optional, empty by default (see [`dataprism.audit`](#dataprismaudit)); an entry that does not match `[A-Za-z][A-Za-z0-9_-]{0,63}` refuses with `INVALID_AUDIT_ENTITY_TYPE` |
| `dataprism.correlation` | All optional. `inbound.header` is unset, which disables the feature; `inbound.format` `opaque`; `inbound.pattern` the strict default; `inbound.required` `false`; `outbound.header` unset. See [`dataprism.correlation`](#dataprismcorrelation) | No | Refuse startup for the refusals listed under [`dataprism.correlation`](#dataprismcorrelation) |
| `dataprism.limits` | All optional; each default is the framework default. See [`dataprism.limits`](#dataprismlimits) | No | Refuse startup for a zero or negative value, a response size that does not fit in an `int`, or a `request-timeout` shorter than `per-source-timeout`, with `INVALID_REQUEST_LIMITS`; and for an application `RequestLimits` bean, including one registered as a raw `FactoryBean` (`FORBIDDEN_PRIVACY_OVERRIDE`) |
| `dataprism.catalog` | All optional. `unselected-source-limit` is `8`; `real-names-scopes` is empty. See [`dataprism.catalog`](#dataprismcatalog) | No | Refuse startup for a non-positive limit, a limit above `dataprism.limits.max-sources`, or a malformed scope pattern (`INVALID_CATALOG_SETTING`); for a source description that breaks a catalog rule (`INVALID_SOURCE_DESCRIPTION`, `SOURCE_DESCRIPTION_LEAKS_NAME`); for a bad chainable declaration (`INVALID_CHAINABLE_FIELD`); and for an application catalog, options or query bean (`FORBIDDEN_PRIVACY_OVERRIDE`) |
| `dataprism.paging` | All optional. `list-sources.page-size` is `25`, `list-sources.max-page-size` is `100`, `lifetime` is `PT15M`. See [`dataprism.paging`](#dataprismpaging) | No | Refuse startup for a non-positive value, or a page size above the maximum (`INVALID_PAGING_SETTING`), and for more registered sources than one default page with no `dataprism.links.key-id` (`MISSING_PAGING_KEY`) |
| `dataprism.links` | `enabled` is `false`: no result carries `_links` and `follow` is not registered. `key-id` and `keys.<key-id>.*` are optional while links are off: the link key that page links need. The other keys have the defaults in [`dataprism.links`](#dataprismlinks) | **Yes, by reference** | Refuse startup for a key that does not resolve, or no key with links enabled (`LINK_KEY_UNRESOLVED`), is under 32 bytes (`LINK_KEY_WEAK`), or has the id or the material of the pseudonymisation key (`LINK_KEY_SHARED`), a lifetime that is not a duration of at least one second or `scope` (`INVALID_LINK_LIFETIME`), a limit out of range or a key id outside 1 to 16 characters of `A-Z`, `a-z`, `0-9`, `_` and `-` (`INVALID_LINK_LIMIT`), a `dataprism.paging.lifetime` above `dataprism.links.lifetime` with links enabled (`INVALID_PAGING_SETTING`), a link-issuing tool in `dataprism.oversight.approval-required-tools` without `follow` (`FOLLOW_NOT_APPROVAL_GATED`), a member that joins a cluster through `tcp-ip` or `kubernetes`, or supplies its own `PrivacyCluster` (even of one member), with sealed links in use and no limiter declared as `ClusterWideLinkAttemptLimiter` (`MISSING_SHARED_LINK_LIMITER`), and, for a cluster or name key with the id or material of a link key, `CLUSTER_KEY_SHARED` or `NAME_KEY_SHARED` |
| `dataprism.key-resolvers` | All optional. `max-targets` is `5`, `timeout` is `PT2S`, `budget-cost` is `1`. See [`dataprism.key-resolvers`](#dataprismkey-resolvers) | No | Refuse startup for a `max-targets` outside 1 to 16, a non-positive `timeout` or `budget-cost` (`INVALID_KEY_RESOLVER_SETTING`), two `KeyResolver` beans for one pair or one name (`DUPLICATE_KEY_RESOLVER`) and a malformed one (`INVALID_KEY_RESOLVER`) |
| `dataprism.metrics` | Defaults to the framework's no-op implementation only for fixture development; production requires an approved sink/registry binding | Sink credentials are **yes, by reference** when applicable | Refuse startup in production for an unknown or absent required sink; metrics failures after startup remain fail-safe and cannot change a privacy decision |
| `dataprism.hazelcast` | `topology` is required for a protected deployment and is one of two honest choices, never a default: `embedded` shares the read budget, pause state, approvals and rate limits across members that have joined one cluster, and is the one a multi-instance deployment must choose; `single-node` is a real, supported choice too, but its state is per process, so a configured budget of 100 becomes 100 times the number of running processes. With `embedded`, `cluster-name` (never `dev`), `join.mode` (`tcp-ip`, `kubernetes` or `none`), `join.members`, `join.kubernetes.namespace`, `join.kubernetes.service-name` or `service-dns`, `member.port` (default 5701), `member.interface` and `approval-minimum-members` are the cluster properties, `lock-timeout` (default `PT2S`) bounds every cluster lock wait, and `approval-sweep-interval` (default `PT5M`) sets how often each member sweeps pending approvals; see [`dataprism.hazelcast`](#dataprismhazelcast). Identity-cache TTL follows the privacy scope regardless of topology; re-identification index defaults to `false`; persistence/MapStore defaults to disabled | No | Refuse startup for a missing topology (`MISSING_CLUSTER_TOPOLOGY`), an unknown topology (`UNSUPPORTED_HAZELCAST_TOPOLOGY`), `embedded` with the optional Hazelcast dependency absent from the classpath (`MISSING_SHARED_BUDGET`, never a silent fall back to the per-process budget), persistence/MapStore enablement without an explicit reviewed configuration (`UNSAFE_HAZELCAST_PERSISTENCE`), TLS references (`HAZELCAST_TLS_UNSUPPORTED`: member TLS is not available in the open-source distribution), a missing or reserved cluster name (`MISSING_CLUSTER_NAME`, `RESERVED_CLUSTER_NAME`), a missing or unsupported join mode (`MISSING_CLUSTER_JOIN`, `UNSUPPORTED_CLUSTER_JOIN`), invalid members, Kubernetes join, port or interface (an invalid `member.interface`, or a `hazelcast.*` address property that makes a single member reachable; `INVALID_CLUSTER_MEMBERS`, `INVALID_KUBERNETES_JOIN`, `INVALID_CLUSTER_PORT`, `INVALID_CLUSTER_INTERFACE`), a member port shared with another listener (`CLUSTER_PORT_SHARED`), cluster settings that would be ignored (`CLUSTER_SETTINGS_IGNORED`), a non-positive TTL (`INVALID_HAZELCAST_TTL`), a lock timeout outside `PT100MS` to `PT30S` (`INVALID_LOCK_TIMEOUT`), an approval sweep interval outside `PT1S` to `P1D` (`INVALID_APPROVAL_SWEEP_INTERVAL`), an approval minimum that is below 1, above the listed members or set with `none`, or, with approvals in use, a `tcp-ip` member that cannot be counted (`INVALID_APPROVAL_QUORUM`), or, with approvals in use (approval-required tools set or re-identification enabled), `kubernetes` or a `tcp-ip` hostname member and no minimum (`MISSING_APPROVAL_QUORUM`), or an enabled index without its required controls (`MISSING_REIDENTIFICATION_CONTROLS`) |
| `dataprism.oversight` | All optional. `approval-required-tools` defaults to empty; `approval-ttl` `PT15M`; `caller-rate-limit.requests` unset (no limit); `caller-rate-limit.window` `PT1M`; `max-pending-per-requester` `5` | No | Refuse startup for an unknown tool name (`UNKNOWN_OVERSIGHT_TOOL`), a non-positive limit, window, TTL or cap (`INVALID_OVERSIGHT_LIMIT`), or approval-required tools or a rate limit without `dataprism.operator.enabled` (`OVERSIGHT_REQUIRES_OPERATOR_SURFACE`) |
| `dataprism.reidentification` | `enabled` defaults to `false`; `four-eyes` defaults to `true`; `approval-ttl` `PT15M`; `max-pending-per-requester` `5`; `purposes` and `roles` are required once enabled | No | Refuse startup for the refusals listed under [`dataprism.reidentification`](#dataprismreidentification) |
| `dataprism.operator` | `enabled` defaults to `false`; `port`, `required-audience` and `required-scope` are required once enabled; `address` is optional and follows `server.address` when unset | No | Refuse startup for an enabled surface missing any of the required three (`MISSING_OPERATOR_SECURITY`), sharing `server.port` (`OPERATOR_PORT_SHARED`), using the MCP audience (`OPERATOR_AUDIENCE_SHARED`), or an `address` that does not resolve (`INVALID_OPERATOR_ADDRESS`); and `dataprism.reidentification.enabled=true` without the `data-prism-reidentification` module on the classpath (`REIDENTIFICATION_MODULE_MISSING`) |
| `dataprism.sources` | One named source entry per configured Java-first REST adapter; each entry declares a server-controlled HTTPS base URL and positive timeout | mTLS key, trust material, and service credentials are **yes, by reference** | Refuse startup for duplicate names, an unapproved/non-HTTPS URL (local fixture exception only), user-info/query/fragment in a base URL, invalid timeout, unresolved mTLS reference, or a configured source without its explicit adapter bean |

`dataprism.sources.<name>` is intentionally limited to transport parameters
owned by a reviewed adapter: `base-url`, `timeout`, and approved mTLS/service
credential references. It is not a generic request template. An adapter owns
the endpoint path, response type, source-name aliasing, and conversion to an
annotated LLM-exposed model.

### Property reference for the keys the group table summarises

The jar ships Spring configuration metadata (`META-INF/spring-configuration-metadata.json`), so
an IDE completes and describes `dataprism.*` keys in YAML and properties files. This only
concerns the keys above: your own `@ConfigurationProperties` classes are unaffected. Their binding
happens at runtime and needs no processor; add `spring-boot-configuration-processor` to your own
build only if you want IDE metadata for your own properties. A build test keeps this
document and that metadata in step in both directions.

Each key below is also described in the group table above. Defaults here are the values in code.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.transport.mode` | `http` | The inbound transport. `stdio` is refused |
| `dataprism.transport.http.path` | `/mcp` | The rooted path of the MCP endpoint |
| `dataprism.transport.fixture-development` | `false` | Local fixture development only; never for a protected deployment |
| `dataprism.security.jwt.issuer` | unset | The trusted JWT issuer. Required for HTTP |
| `dataprism.security.jwt.issuer-discovery-uri` | unset | Issuer-discovery location; exactly one of this and `jwk-set-uri` |
| `dataprism.security.caller-claims.principal` | unset | The claim carrying the caller principal |
| `dataprism.security.caller-claims.roles` | unset | The claim carrying the caller roles |
| `dataprism.security.caller-claims.investigation` | unset | The trusted claim carrying the investigation or case attribute |
| `dataprism.security-policy.purposes` | unset | The permitted purposes; at least one |
| `dataprism.security-policy.roles` | unset | Map of role name to the capabilities it holds; at least one role |
| `dataprism.security-policy.case-authorisation` | unset (no default) | Only `token-trusted`: trust the case claim in the token without assignment checks. Unset, supply a `CaseAuthorizer` bean |
| `dataprism.security-policy.allowed-clients` | unset (no default) | The clients allowed to call, each matched exactly. Unset, supply a `ClientAllowlist` bean or set `client-allowlist` |
| `dataprism.security-policy.client-allowlist` | unset (no default) | Only `unrestricted`: allow every client. Cannot be combined with `allowed-clients` |
| `dataprism.privacy.profile` | unset | The privacy profile, `DEFAULT` or `STRICT`. Required |
| `dataprism.privacy.locale` | `neutral` | The vocabulary locale; `neutral` selects the locale-neutral vocabulary |
| `dataprism.privacy.scope-lifetime` | unset | The privacy scope lifetime. Required in production; positive |
| `dataprism.privacy.hmac-key.key-id` | unset | The HMAC key pinned for each scope. Required |
| `dataprism.privacy.hmac-key.environment-variable` | unset | The name of the environment variable holding the key |
| `dataprism.privacy.hmac-key.provider-reference` | unset | A reference to a key provider |
| `dataprism.privacy.hmac-key.value` | unset | Refused if set: a literal key is never accepted |
| `dataprism.audit.sink` | unset | `approved-sink`, `slf4j` or `hash-chained`. Required in production |
| `dataprism.audit.writer-id` | unset | The writer identity in the audit trail. Required for every sink |
| `dataprism.audit.entity-types` | empty | Optional entity type names; each matches `[A-Za-z][A-Za-z0-9_-]{0,63}` |
| `dataprism.metrics.sink` | unset | `micrometer`. Required in production |
| `dataprism.metrics.registry-reference` | unset | A reference to the approved registry binding |
| `dataprism.hazelcast.topology` | unset | `embedded` or `single-node`. Required for a protected deployment |
| `dataprism.hazelcast.cluster-name` | unset | The cluster name for `embedded`; never `dev` |
| `dataprism.hazelcast.identity-cache-ttl` | unset | The identity cache TTL; follows the privacy scope lifetime when unset |
| `dataprism.hazelcast.join.mode` | unset | `tcp-ip`, `kubernetes` or `none` |
| `dataprism.hazelcast.join.members` | empty | `host` or `host:port` entries for `tcp-ip` |
| `dataprism.hazelcast.join.kubernetes.namespace` | unset | The Kubernetes namespace to join in |
| `dataprism.hazelcast.join.kubernetes.service-name` | unset | The service name (API mode); exactly one of this and `service-dns` |
| `dataprism.hazelcast.join.kubernetes.service-dns` | unset | The service DNS name (DNS mode) |
| `dataprism.hazelcast.member.port` | unset | The member port; 5701 when unset |
| `dataprism.hazelcast.member.interface` | unset | The network interface the member binds. Required for `tcp-ip` and `kubernetes` (`MISSING_MEMBER_INTERFACE`); a value that matches every address is refused (`UNBOUNDED_MEMBER_INTERFACE`). Refused with `none` |
| `dataprism.hazelcast.trusted-network` | `false` | Your assertion that only Data Prism members can reach the member port (`dataprism.hazelcast.member.port`, 5701 by default). Required for `tcp-ip` and `kubernetes` (`MISSING_TRUSTED_NETWORK`). Approvals and counters on that port are unsealed, so anyone else who can reach it can read and forge them; a private address is not a trusted network. On Kubernetes the shipped NetworkPolicy admits every pod labelled `app: data-prism`, so there "only Data Prism members" means anyone who can create pods in the namespace: run the members in a dedicated namespace. The policy protects only where the cluster's network plugin enforces NetworkPolicy (some, such as flannel and kind's default, do not) and no pod uses `hostNetwork`. Setting it, to either value, is refused (`CLUSTER_SETTINGS_IGNORED`) with `single-node`, with `none` and beside your own `PrivacyCluster` |
| `dataprism.hazelcast.member.trusted-ranges` | empty | IPv4 CIDR blocks you trust beyond the private ones (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`), for the member interface and the advertised addresses. A block outside the private ones must be `/24` or longer; an entry that does not parse or is wider is refused (`INVALID_TRUSTED_RANGE`, naming the entry's index). Read only where `trusted-network` is `true`; refused (`CLUSTER_SETTINGS_IGNORED`) where that property is. A member started with one outside the private blocks logs one WARN line with the count |
| `dataprism.hazelcast.security.cluster-key.key-id` | unset | The identifier of the current cluster key. Required for `tcp-ip` and `kubernetes`, with exactly one of the two sources below; optional with `none`, but then the name key is required too. No dot |
| `dataprism.hazelcast.security.cluster-key.environment-variable` | unset | The environment variable that holds the current cluster key, resolved by the same reviewed resolver as the privacy HMAC key. Exactly one of this and `provider-reference` |
| `dataprism.hazelcast.security.cluster-key.provider-reference` | unset | A reference to the current cluster key in the key provider. Exactly one of this and `environment-variable` |
| `dataprism.hazelcast.security.cluster-key.value` | unset | Refused if set (`LITERAL_SECRET_FORBIDDEN`): a literal key is never accepted |
| `dataprism.hazelcast.security.cluster-key.next.key-id` | unset | Optional, for a rotation: a cluster key every member reads and none writes. Needs a current key; must differ from every other key id |
| `dataprism.hazelcast.security.cluster-key.next.environment-variable` | unset | The environment variable that holds the `next` key. Exactly one of this and `next.provider-reference`, with a `next.key-id` |
| `dataprism.hazelcast.security.cluster-key.next.provider-reference` | unset | A reference to the `next` key in the key provider. Exactly one of this and `next.environment-variable`, with a `next.key-id` |
| `dataprism.hazelcast.security.cluster-key.previous.key-id` | unset | Optional, for a rotation: the cluster key being retired. Needs a current key; must differ from every other key id |
| `dataprism.hazelcast.security.cluster-key.previous.environment-variable` | unset | The environment variable that holds the `previous` key. Exactly one of this and `previous.provider-reference`, with a `previous.key-id` |
| `dataprism.hazelcast.security.cluster-key.previous.provider-reference` | unset | A reference to the `previous` key in the key provider. Exactly one of this and `previous.environment-variable`, with a `previous.key-id` |
| `dataprism.hazelcast.security.cluster-key.next.value` | unset | Refused if set (`LITERAL_SECRET_FORBIDDEN`): a literal key is never accepted |
| `dataprism.hazelcast.security.cluster-key.previous.value` | unset | Refused if set (`LITERAL_SECRET_FORBIDDEN`): a literal key is never accepted |
| `dataprism.hazelcast.security.name-key.key-id` | unset | The identifier of the name key, a separate key from every cluster key. Required for `tcp-ip` and `kubernetes`; optional with `none`, but then the cluster key is required too. No dot |
| `dataprism.hazelcast.security.name-key.environment-variable` | unset | The environment variable that holds the name key. Exactly one of this and `provider-reference` |
| `dataprism.hazelcast.security.name-key.provider-reference` | unset | A reference to the name key in the key provider. Exactly one of this and `environment-variable` |
| `dataprism.hazelcast.security.name-key.value` | unset | Refused if set (`LITERAL_SECRET_FORBIDDEN`): a literal key is never accepted |
| `dataprism.hazelcast.lock-timeout` | `PT2S` | How long a call waits for a cluster lock before it is refused with `CLUSTER_LOCK_TIMEOUT`; from `PT100MS` to `PT30S`. Every lock is leased for five times this, so none is held for ever; locks bound waits only. A lock or lease Hazelcast rejects is refused with `CLUSTER_OPERATION_REFUSED` |
| `dataprism.hazelcast.approval-sweep-interval` | `PT5M` | How often each member sweeps the pending-approval entries and approval-id claims it owns; from `PT1S` to `P1D`, a `Duration`. A shorter interval clears leftovers sooner at the cost of more cluster calls. Fixed when the cluster is built, so it is refused with `single-node` and beside an application-supplied `PrivacyCluster` (`CLUSTER_SETTINGS_IGNORED`) |
| `dataprism.hazelcast.approval-minimum-members` | unset | The fewest members that must see each other for approvals to change. Unset: a majority of the distinct `join.members` with `tcp-ip`, 1 with `none`; required with `kubernetes`, and with a hostname among `tcp-ip` members, when approvals are in use (`dataprism.oversight.approval-required-tools` set or `dataprism.reidentification.enabled`); the quorum is not computed or required otherwise |
| `dataprism.hazelcast.reidentification-enabled` | `false` | Keeps the re-identification index in the cluster |
| `dataprism.hazelcast.persistence-enabled` | `false` | Refused without an explicit reviewed configuration |
| `dataprism.hazelcast.map-store-enabled` | `false` | Refused without an explicit reviewed configuration |
| `dataprism.hazelcast.reidentification-controls-reference` | unset | A reference to the reviewed re-identification controls |
| `dataprism.hazelcast.tls-key-reference` | unset | Refused if set: member TLS is not available in the open-source distribution |
| `dataprism.hazelcast.tls-trust-reference` | unset | Refused if set: member TLS is not available in the open-source distribution |
| `dataprism.sources.<name>.base-url` | unset | The server-controlled HTTPS base URL of one source |
| `dataprism.sources.<name>.timeout` | unset | The positive request timeout of one source |
| `dataprism.sources.<name>.service-credential-reference` | unset | A reference to the service credential of one source |
| `dataprism.sources.<name>.mtls.key-reference` | unset | A reference to the mTLS client key material of one source |
| `dataprism.sources.<name>.mtls.trust-reference` | unset | A reference to the mTLS trust material of one source |

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
    # A real deployment supplies a CaseAuthorizer bean and lists its allowed clients here
    # (allowed-clients: [client-a, client-b]); these two settings are the explicit opt-out.
    case-authorisation: token-trusted
    client-allowlist: unrestricted
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

### `dataprism.security-policy`: case authorisation and client allowlist

Two decisions have no default, and a deployment that has not made them refuses to start.

- **Case authorisation.** Who may open which case. Supply a `CaseAuthorizer` bean, or set
  `dataprism.security-policy.case-authorisation: token-trusted`, which trusts the case claim in the token
  without assignment checks and logs one `WARN` at startup naming the setting (never a claim value). `token-trusted`
  is the only accepted value.
- **Client allowlist.** Which clients may call. Supply a `ClientAllowlist` bean, or list the clients in
  `dataprism.security-policy.allowed-clients`, or set `dataprism.security-policy.client-allowlist: unrestricted`,
  which allows every client and logs one `WARN` naming the setting. `unrestricted` is the only accepted value of
  `client-allowlist`.

None of the three properties has a default. A setting together with an application bean of the same type is
refused rather than one silently winning. The client allowlist does not control which model endpoint a client
sends output to, or where the data is transferred; enforcing those belongs to an API gateway or egress control
outside Data Prism.

The default `ScopeResolver` and `AuthorizationService` take the bean through Spring's ordinary injection, so Spring
refuses the cases it refuses for any dependency: two beans of either type fail with Spring's own
`NoUniqueBeanDefinitionException` unless one is `@Primary`. Once the context is built, Data Prism checks the outcome:
every `ScopeResolver` and `AuthorizationService` in the context and its ancestors must hold the bean Spring injects,
so an application resolver or service built around another authoriser or allowlist is refused while one built with
the injected bean starts. The bean must be a singleton (a request-scoped `CaseAuthorizer` behind a scoped proxy
qualifies, because the proxy is a singleton). The check sees beans only: an `HttpTransport` built by hand around its
own resolver or service is not inspected.

A standalone-server deployment has no Java to write in the image. It supplies a `CaseAuthorizer` the way it supplies
any reviewed extension: an extension jar on `loader.path`, as `data-prism-quickstart-extension` does for its
identity resolver. The jar's `@Bean CaseAuthorizer` is then the only one, and the `case-authorisation` setting stays
unset. The published server image bakes `case-authorisation: token-trusted` and `client-allowlist: unrestricted`
into `/app/config/application.yaml`, so with that image an extension jar or an `allowed-clients` list is refused as a
conflict (`CASE_AUTHORISATION_CONFLICT`, `CLIENT_ALLOWLIST_CONFLICT`): override `/app/config/application.yaml`, or
build your own image, so that only your decision is set.

Data Prism checks that a decision was made, not what it decides. A bean that is itself
`CaseAuthorizer.tokenTrusted()`, always answers `true`, or a `ClientAllowlist` that admits everyone, passes. A parent
context that opts out explicitly while the deployer's bean lives only in a child where the auto-configuration does not
run does not consult that bean, by the parent's own explicit setting.

| Code | Condition |
|---|---|
| `UNSUPPORTED_CASE_AUTHORISATION` | `case-authorisation` is set to anything but `token-trusted` |
| `CASE_AUTHORISATION_CONFLICT` | `case-authorisation: token-trusted` together with an application `CaseAuthorizer` bean |
| `MISSING_CASE_AUTHORIZER` | No `CaseAuthorizer` bean Spring can inject and no `case-authorisation` setting, including when an application `ScopeResolver` skips the default. The message names the property and the bean type |
| `UNSUPPORTED_CLIENT_ALLOWLIST` | `client-allowlist` is set to anything but `unrestricted` |
| `CLIENT_ALLOWLIST_CONFLICT` | Both `allowed-clients` and `client-allowlist` set, or either together with an application `ClientAllowlist` bean |
| `MISSING_CLIENT_ALLOWLIST` | No `ClientAllowlist` bean Spring can inject and neither setting, including when an application `AuthorizationService` skips the default. The message names both properties and the bean type |
| `EMPTY_CLIENT_ALLOWLIST` | `allowed-clients` is set and empty |
| `BLANK_ALLOWED_CLIENT` | An `allowed-clients` entry is blank |
| `CASE_AUTHORIZER_BYPASSED` | A `ScopeResolver` bean does not hold the injected `CaseAuthorizer`, or that bean is not a singleton (or scoped proxy) |
| `CLIENT_ALLOWLIST_BYPASSED` | An `AuthorizationService` bean does not hold the injected `ClientAllowlist`, or that bean is not a singleton |

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

`entity-types` is an optional list of entity type names. It decides what an
audit record's `entityType` field may hold, because the tool argument is free
text and can carry personal data. The caller's response is not affected: it
still echoes the `entityType` that was sent.

- With a list set, the field holds the argument only when it is exactly one of
  the names (case-sensitive). Anything else is recorded as the fixed value
  `<unregistered>`. Each name must match `[A-Za-z][A-Za-z0-9_-]{0,63}`; any other
  entry, including `<unregistered>` itself, refuses startup with
  `INVALID_AUDIT_ENTITY_TYPE`. The message never repeats the entry.
- With the list unset or empty (the default), a shape fallback applies: the
  field holds the argument only when it matches `[A-Z][A-Z0-9_]{0,63}`, an
  upper-case identifier with no hyphen, space, `@`, `.` or lower case. Anything
  else is recorded as `<unregistered>`. Startup logs one INFO line saying so.
- Residual risk of the fallback: an upper-case token such as `MURPHY` or
  `ACC123` passes the shape and is recorded. Set `entity-types` to the exact
  types in use to close that gap.

#### Audit event listeners

| Property | Default | Meaning |
|---|---|---|
| `dataprism.audit.listeners.queue-capacity` | `1024` | How many audit events may wait for the listener thread. When the queue is full, further events are dropped for listeners only and logged as `AUDIT_LISTENER_DROPPED`; the audit log is unaffected. Used only when an `AuditEventListener` bean exists. See [audit.md](audit.md#audit-event-listeners). |

Refusal code, at startup:

- `INVALID_AUDIT_LISTENER_QUEUE_CAPACITY` -- `listeners.queue-capacity` is zero or negative.
- `AUDIT_EVENT_LISTENERS_NOT_REPLACEABLE` -- an application declares a second dispatcher bean (`AuditEventListeners`). Add `AuditEventListener` beans instead.

#### Segmented files, checkpoints and retention

| Property | Default | Meaning |
|---|---|---|
| `dataprism.audit.directory` | unset | With `hash-chained`: write one `audit-YYYY-MM-DD.log` segment per UTC day into this directory, instead of one file. Mutually exclusive with `file-path`. |
| `dataprism.audit.checkpoint.file-path` | unset | A separate file receiving `BOOT`, `PERIODIC`, `SHUTDOWN` and `RETENTION_ANCHOR` checkpoints. Required with `directory`. Put it behind different access controls from the audit files, or it protects nothing. |
| `dataprism.audit.checkpoint.interval` | `PT5M` | How often a `PERIODIC` checkpoint is written, the first soon after boot (within ten seconds). A `SHUTDOWN` checkpoint is written when the context closes. |
| `dataprism.audit.retention` | `P6M` | With `directory`: segments dated before today minus this period are deleted, after a `RETENTION_ANCHOR` is recorded for each writer's last record in them. Run once at startup, then every 24 hours. |
| `dataprism.audit.retention-override` | `false` | Must be `true` to accept a `retention` shorter than six months. Inert with a value of six months or more. |
| `dataprism.audit.segment-max-size` | unset | With `directory`: a day's segment is closed and the next one opened (`audit-YYYY-MM-DD.0001.log`, then `.0002.log`) before a write that would take it past this size. A Spring `DataSize` such as `64MB`; at least `1MB`. A record larger than the cap is written whole into a fresh segment, never split. The hash chain is unaffected. Unset means one segment per day. |

Refusal codes, each at startup:

- `AMBIGUOUS_AUDIT_LOCATION` -- `directory` and `file-path` are both set.
- `RETENTION_REQUIRES_CHECKPOINT` -- `directory` is set without `checkpoint.file-path`, whatever `sink` is.
- `AUDIT_RETENTION_BELOW_MINIMUM` -- `retention` is under six months and `retention-override` is not `true`.
- `INVALID_AUDIT_CHECKPOINT_INTERVAL` -- `checkpoint.interval` is zero or negative.
- `AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE` -- the checkpoint path is the audit `file-path`, or is the audit `directory` or any path inside it. Paths are compared normalised, with symbolic links resolved where they exist.
- `AUDIT_CHECKPOINT_FILE_UNUSABLE` -- the checkpoint path cannot be opened. The path is logged server-side only, never in the message.
- `AUDIT_SEGMENT_SIZE_REQUIRES_DIRECTORY` -- `segment-max-size` is set without `directory`.
- `INVALID_AUDIT_SEGMENT_SIZE` -- `segment-max-size` is under 1MB.

#### Keyed audit chain

The hash chain is plain SHA-256 by default. An operator can switch it to HMAC-SHA256 under a key of their own.
This is opt-in and off by default. The keyed chain resists a writer who does not have the key: such a writer
cannot recompute the hashes, so a changed or inserted record is detected in the hashed fields. Deleting a writer's most recent records
(including whole trailing segments) still verifies; catching that needs the checkpoint file (`--checkpoints`). It does not resist anyone who holds the key,
the operator and the process that writes the file included. It is not tamper-proof, immutable, or evidence against
the operator. Nothing written to the audit file records whether it is keyed; the verifier is told by its caller.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.audit.hmac-key.key-id` | unset | The audit key's id. Unset means the chain is unkeyed. Required when the group is set. |
| `dataprism.audit.hmac-key.environment-variable` | unset | Name of the environment variable holding the key. A name only. Exactly one of this and `provider-reference`. |
| `dataprism.audit.hmac-key.provider-reference` | unset | Reference resolved by the application's `HmacKeyReferenceResolver`. Exactly one of this and `environment-variable`. |
| `dataprism.audit.retired-hmac-keys` | none | A map from a retired key's id to its reference, set below as `retired-hmac-keys.<key-id>.environment-variable` or `.provider-reference`. Verification only; the recorder never signs with one. |
| `dataprism.audit.retired-hmac-keys.<key-id>.environment-variable` | none | A verification-only key of an earlier chain, by its key id. Exactly one of this and `provider-reference`. |
| `dataprism.audit.retired-hmac-keys.<key-id>.provider-reference` | none | As above, resolved by the `HmacKeyReferenceResolver`. |

There is no property that takes key material: a key is only ever a reference, and the material must be at least
32 bytes. The audit key must be a different key from the pseudonymisation key (`dataprism.privacy.hmac-key`) and
from the cluster and name keys. The recorder signs with the current key only, and it is the built-in one: an application `AuditRecorder` bean with `hmac-key` set is refused. Retention verifies expiring segments
with the current key and every retired key, and does not delete a segment written under any other key (or none),
so keep a retired key configured until every segment written under it has been purged; otherwise the purge stops
and `auditIntegrity` health reports DOWN with `AUDIT_RETENTION_CHAIN_UNVERIFIED`. Rotation is a procedure, not code.

Refusal codes, each at startup, none of which repeats a reference or any key byte:

- `AUDIT_HMAC_REQUIRES_HASH_CHAINED` -- `hmac-key` or `retired-hmac-keys` is set with a `sink` other than `hash-chained`.
- `AUDIT_RECORDER_REPLACED_WITH_HMAC_KEY` -- `hmac-key` is set and the application supplies its own `AuditRecorder` bean, so the built-in recorder backs off and the key would sign nothing. The message names the property and the bean type only. An application recorder with no key starts as before.
- `AUDIT_RETIRED_KEYS_REQUIRE_HMAC_KEY` -- `retired-hmac-keys` is set without `hmac-key`.
- `MISSING_AUDIT_HMAC_KEY_REFERENCE` -- `key-id` is missing, or not exactly one of `environment-variable` and `provider-reference` is set, for `hmac-key` or a retired key.
- `DUPLICATE_AUDIT_KEY_ID` -- a retired key has the same id as the current key.
- `AUDIT_HMAC_KEY_UNRESOLVED` -- a reference resolves to nothing, or the resolver fails.
- `AUDIT_HMAC_KEY_TOO_SHORT` -- the key material is shorter than 32 bytes.
- `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` -- an audit key (current or retired) has the same reference string or the same material as `dataprism.privacy.hmac-key`. Material is compared in constant time.

The six-month default follows EU AI Act Art. 19, which sets a floor of six
months for automatically generated logs "unless provided otherwise in
applicable Union or national law". Other periods may be lawful under Union or
national law; setting `retention-override` is the operator's own legal
responsibility, and Data Prism does not judge whether such a law applies.

While a checkpoint cannot be written, audited calls are refused until the next checkpoint succeeds. An allowed
`get_entity_context`, `compare_entity_sources` or `follow` call answers with a generic error that carries no code,
not `AUDIT_UNAVAILABLE` (`AUDIT_CHECKPOINT_UNAVAILABLE` names the exception inside the server, not a code a client
receives). A purge that
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

#### Output: field names, routing and a JSON projection

`dataprism.audit.output.*` shapes the JSON rendering of an audit event. It
supports a deployer who ships audit events to a log platform that expects
particular field names; it does not change what is audited, it adds no value
that is not in the event, and it never touches the hash-chained `.log`
segments, which stay authoritative.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.audit.output.field-preset` | `canonical` | `canonical` renders each field under its own name. `ecs` renders Elastic Common Schema names (`@timestamp`, `event.id`, `event.action`, `user.id`, `trace.id`), the remaining fields under `dataprism.*`, and a derived `event.outcome`. |
| `dataprism.audit.output.field-names.<field>` | none | Overrides the output path of one canonical field, for example `field-names.tool=custom.action`. A dot nests. A path matches `[A-Za-z_@][A-Za-z0-9_@]*(\.[A-Za-z0-9_@]+)*`. |
| `dataprism.audit.output.routing.event-dataset` | unset | A constant written as `event.dataset`: 1 to 100 characters of `[a-z0-9_.]`. |
| `dataprism.audit.output.routing.data-stream-type` | unset | A constant written as `data_stream.type`: `logs`. |
| `dataprism.audit.output.routing.data-stream-dataset` | unset | A constant written as `data_stream.dataset`: `[a-z0-9_.]`, 1 to 100 characters. |
| `dataprism.audit.output.routing.data-stream-namespace` | unset | A constant written as `data_stream.namespace`: `[a-z0-9_]`, 1 to 100 characters. |
| `dataprism.audit.output.json-directory` | unset | Also write each event, rendered with the mapping and routing above, to `audit-YYYY-MM-DD.ndjson` segments in this directory. Needs `sink: hash-chained` with `directory`. |

With `sink: slf4j`, a configured `field-preset`, `field-names` or `routing`
adds the mapped values to each log event as key-value pairs. With none of them
set, the message is unchanged and carries no key-value pairs. With
`approved-sink`, these properties have no effect, because the deployment's own
sink renders its own events.

With `json-directory`, the `AuditSink` bean writes the native segment first and
then the `.ndjson` line, with the same `eventHash` in both. If the projection
write fails, the native event is already on disk, so the sink then refuses
every later audited call with `AUDIT_PROJECTION_FAILED` until the process
restarts, rather than write a second event under a sequence number already
used. The `.ndjson` segments are not chained and are verified by nothing; verify
the native directory with the offline verifier. Expired `.ndjson` segments are
deleted on the same schedule as the native ones, once at startup and then every
24 hours, with the same `retention` and `retention-override`. Today's segment is
never deleted. A failed projection purge is logged and does not stop the native
purge.

Refusal codes, each at startup:

- `INVALID_AUDIT_FIELD_PRESET` -- `field-preset` is neither `canonical` nor `ecs`.
- `UNKNOWN_AUDIT_FIELD` -- a `field-names` key is not an audit field.
- `INVALID_AUDIT_FIELD_PATH` -- an output path does not match the pattern above.
- `AUDIT_FIELD_MAPPING_CONFLICT` -- two output paths are equal, or one is a prefix segment of another, including a routing path and the derived `event.outcome`.
- `INVALID_AUDIT_ROUTING_VALUE` -- a `routing` value does not follow its naming rule.
- `AUDIT_JSON_REQUIRES_SEGMENTED_SINK` -- `json-directory` is set without `sink: hash-chained` and `directory`.
- `AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT` -- `json-directory` is the audit `directory`, or inside it, or contains it.
- `AUDIT_JSON_DIRECTORY_UNUSABLE` -- `json-directory` cannot be opened for writing. The path is logged server-side only, never in the message.

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

[![Fail-closed field decisions: a classified field is pseudonymised, redacted or removed according to its classification; an unclassified field refuses the source whose record holds it (FAIL_REQUEST), and the call only when every source that answered is refused; and a response where something looks like a sensitive identifier shape is also refused.](assets/diagrams/fail-closed-decisions.svg)](assets/diagrams/fail-closed-decisions.svg)
Select the diagram to open it full size.

### `dataprism.correlation`

A caller's own correlation id, such as the id a gateway puts on every request,
can be recorded on the audit event as `externalCorrelationId` and passed to
sources, so that an event in one system can be matched to a request in another.
This supports that matching. It does not establish who the caller is, and it
adds no claim about the audit trail's legal standing.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.correlation.inbound.header` | unset | The HTTP request header carrying the id. Unset disables the feature. Must be an RFC 9110 token, and not `Authorization`, `Cookie` or `Proxy-Authorization`. |
| `dataprism.correlation.inbound.format` | `opaque` | `opaque` accepts a value matching `pattern`. `traceparent` accepts a W3C `traceparent` value and records its trace id. |
| `dataprism.correlation.inbound.pattern` | the strict default | A regular expression the whole value must match, with `format: opaque`. The default accepts only a UUID, 16 to 128 hex characters containing at least one letter a to f, or a W3C `traceparent`. |
| `dataprism.correlation.inbound.required` | `false` | If `true`, a call without a valid id is refused and audited before any source is called: `EXTERNAL_CORRELATION_ID_REQUIRED` when the header is absent, `EXTERNAL_CORRELATION_ID_INVALID` when it is present but rejected. |
| `dataprism.correlation.outbound.header` | unset | The header name that sends the id to a source. A per-source `correlation-header` overrides it. Setting it sends the id to every configured source. |
| `dataprism.correlation.mdc-key` | unset (off) | The SLF4J MDC key under which the validated id is put for the duration of a tool call. Needs `inbound.header`. Must match `[A-Za-z][A-Za-z0-9_.-]{0,63}` and must not be a reserved name. Unset means MDC is never touched. |

A value is accepted only if it passes a fixed ceiling (at most 256 characters,
each in `[A-Za-z0-9._:/+=-]`) and then the pattern. A repeated header is
rejected. A rejected value is dropped, so the call proceeds as if no id had
been sent unless `required` is `true`. It is logged at WARN as the code
`EXTERNAL_CORRELATION_ID_DROPPED`, and the rejected text is never logged.

The pattern limits an id's shape and cannot limit its meaning. A pattern broad
enough to admit a name-like token such as `jane.doe` lets personal data be
supplied as an id, and the id is then written to the audit trail and sent to
sources. A deployer should choose a pattern that admits only generated
identifiers. The strict default is that choice; widening it is the deployer's
decision.

Refusal codes, each at startup:

- `INVALID_CORRELATION_HEADER` -- `inbound.header` or `outbound.header` is not an RFC 9110 token, or is `Authorization`, `Cookie` or `Proxy-Authorization`.
- `INVALID_CORRELATION_FORMAT` -- `inbound.format` is neither `opaque` nor `traceparent`.
- `INVALID_CORRELATION_PATTERN` -- `inbound.pattern` does not compile.
- `CORRELATION_PATTERN_NOT_APPLICABLE` -- `inbound.pattern` is set with `format: traceparent`.
- `CORRELATION_REQUIRED_WITHOUT_HEADER` -- `inbound.required` is `true` without `inbound.header`.
- `CORRELATION_REQUIRES_HTTP_TRANSPORT` -- `inbound.required` is `true` and the transport is not HTTP.

#### `mdc-key`: correlated logging

Organisations commonly put a transaction id into the SLF4J MDC, propagate it
between services in a header, and filter on it in Kibana. With `mdc-key` set,
Data Prism supports that: its own log lines on a tool call's threads,
including the parallel source-fetch threads and library logs on those threads,
carry the call's validated external id under that key. A recipe is in
`examples/log-shipping/mdc/README.md`.

Only the validated id is ever placed in the MDC. A rejected or absent id
places nothing, and nothing is removed from a key the call did not set: when
the call ends the key returns to the value it had before, or is removed if it
had none. Whatever the inbound pattern admits appears in every log line on
those threads, the `slf4j` audit sink's lines included, so the pattern should
admit generated ids only.

The reserved names, compared case-insensitively, are `traceId`, `spanId`,
`trace_id`, `span_id`, `trace_flags`, `trace.id`, `span.id`, `transaction.id`
and `message`, and any name starting `ecs.`, `log.`, `process.`, `service.`,
`error.` or `event.`. They are the keys that tracing integrations and Spring
Boot's ECS structured logging already write. `transaction_id` and
`x_correlation_id` are accepted.

MDC codes, each at startup:

- `INVALID_CORRELATION_MDC_KEY` -- `mdc-key` does not match `[A-Za-z][A-Za-z0-9_.-]{0,63}` (blank, a leading digit, a space, `@timestamp` and 65 characters are all refused).
- `CORRELATION_MDC_KEY_RESERVED` -- `mdc-key` is one of the reserved names or starts with a reserved prefix.
- `CORRELATION_MDC_KEY_WITHOUT_HEADER` -- `mdc-key` is set while `inbound.header` is unset.

### `dataprism.limits`

The limits one request runs under. Each key sets one field of the framework's
`RequestLimits`, and the defaults are the framework's own, so a deployment that
sets none behaves as before. Only a positive value is accepted. The same limits
apply wherever the orchestrator is built, including with
`dataprism.json-sources.config-location`.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.limits.max-sources` | `8` | The most sources one request may call. A selection over it is refused as `SOURCE_SELECTION_TOO_LARGE` before any source is called; no source is silently skipped. |
| `dataprism.limits.max-records` | `500` | The most records one request may take from sources. |
| `dataprism.limits.max-response-size` | `512KB` | The largest compact body returned to the client for one request, as a size such as `512KB` or `1MB`. It bounds what the client receives (every returned source's tree, the source statuses and the findings, and for `compare_entity_sources` its projection too), not one source's response and not the MCP envelope. A larger body refuses the whole call with `RESPONSE_TOO_LARGE`; equality is allowed. It must fit in an `int` number of bytes. |
| `dataprism.limits.per-source-timeout` | `3s` | The time allowed to one source's call, measured from the moment that call starts, after it holds a concurrency permit. A source that waits for a permit does not spend its timeout waiting; the whole request is bounded by `request-timeout`. |
| `dataprism.limits.max-concurrency` | `4` | The most downstream calls in flight at once across one request. |
| `dataprism.limits.scope-read-budget` | `100` | The source reads of one subject allowed within one privacy scope. The next read is refused with `SCOPE_READ_BUDGET`. With `dataprism.hazelcast.topology: single-node` the count is per process. |
| `dataprism.limits.request-timeout` | `PT10S` | The deadline for the whole of one request, from its start. It may not be shorter than `per-source-timeout`: a deployment that raises `per-source-timeout` above `PT10S` must raise this too, or startup is refused. |
| `dataprism.limits.scope-total-read-budget` | `50000` | The source reads allowed within one privacy scope across all subjects. The next read is refused. |

A zero or negative value, a response size that does not fit in an `int`, or a
`request-timeout` shorter than `per-source-timeout` refuses startup with
`INVALID_REQUEST_LIMITS`. The message names the property and not the value. The limits are one bean, `dataPrismRequestLimits`, built from
these keys. It carries the read budget, so an application cannot supply its own
`RequestLimits` bean: startup is refused with `FORBIDDEN_PRIVACY_OVERRIDE`, also when
the bean is registered as a raw `FactoryBean` (one with no generic type) whose
product is a `RequestLimits`. The `PrivacyPolicyResolver` guard refuses a raw
`FactoryBean` the same way.

### `dataprism.catalog`

What a call that names no sources may reach, and which scopes may be shown real
source names. The catalog behind `list_sources` and `describe_source` is built
once at startup from every `DataSourceAdapter` bean and the base-url host of
every configured source (`dataprism.json-sources.config-location`), and the
configured orchestrator uses the same catalog and the same settings.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.catalog.unselected-source-limit` | `8` | The most permitted sources a call without `targetSources` may ask. Positive, and at most `dataprism.limits.max-sources`. |
| `dataprism.catalog.real-names-scopes` | empty | Privacy scope ids that may be shown real source names. An entry is an exact scope id, or a prefix ending in one `*`. A caller also needs `EXPOSE_SOURCE_NAMES`; with the list empty no scope shows real names. |

A non-positive limit, a limit above `dataprism.limits.max-sources`, or a malformed
scope pattern refuses startup with `INVALID_CATALOG_SETTING`. A description that
breaks a catalog rule refuses startup with `INVALID_SOURCE_DESCRIPTION`, or with
`SOURCE_DESCRIPTION_LEAKS_NAME` when an alias description names a source, an adapter
class or a configured base-url host. A bad chainable declaration refuses with
`INVALID_CHAINABLE_FIELD`. No message carries description text. Startup logs one
INFO line giving how many sources use the generated fallback description, with their
real names, and one WARN line giving only how many source names do not follow
`[a-z][a-z0-9-]{0,63}`. The catalog, the options built from these keys and the
queries are not replaceable: an application bean of those types is refused with
`FORBIDDEN_PRIVACY_OVERRIDE`.

### `dataprism.paging`

How `list_sources` pages.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.paging.list-sources.page-size` | `25` | Entries on a page when the caller asks for no size. Positive, and at most `max-page-size`. |
| `dataprism.paging.list-sources.max-page-size` | `100` | The largest page a caller may ask for. 1 to 65535. |
| `dataprism.paging.lifetime` | `PT15M` | How long a page link stays valid. Positive. |

A non-positive value, or a page size above the maximum, refuses startup with
`INVALID_PAGING_SETTING`. When more sources are registered than the default page size
and `dataprism.links.key-id` is not set, startup is refused with `MISSING_PAGING_KEY`;
at exactly the page size it starts.

### `dataprism.links`

The agent-led surface: `_links` on results, the `follow` tool and the page links of `list_sources`. A link is a sealed
token that names no real value. Everything below defaults to off or to a conservative bound.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.links.enabled` | `false` | Whether results carry `_links` and `follow` is registered. With `false` the `tools/list` answer has no `follow`. Requires `key-id`. |
| `dataprism.links.key-id` | unset | The id of the key that seals new links: 1 to 16 characters of `A-Z`, `a-z`, `0-9`, `_` and `-`. It must be a key of `keys` below. Page links need it even while links are off. |
| `dataprism.links.keys.<key-id>.environment-variable` | none | The environment variable holding that key. Exactly one of this and `provider-reference`. |
| `dataprism.links.keys.<key-id>.provider-reference` | none | As above, resolved by the `HmacKeyReferenceResolver` (`resolveProviderReference`). |
| `dataprism.links.lifetime` | `PT30M` | How long a link stays valid: a duration of at least one second, or `scope` (up to the scope's expiry). `INVALID_LINK_LIFETIME` otherwise. |
| `dataprism.links.max-per-response` | `50` | The most links across one response. 1 to 500. |
| `dataprism.links.max-length` | `1024` | The longest link, in characters, accepted or issued. 616 to 4096. It is also the length the `page` and `link` arguments allow. |
| `dataprism.links.invalid-attempt-limit` | `20` | Counted failed attempts a caller may make within the window before it is locked, valid links included. Positive. |
| `dataprism.links.invalid-attempt-window` | `PT10M` | The window of that count. A positive duration. |
| `dataprism.links.clock-skew` | `PT5S` | The tolerance for link expiry and issue time. 0 to `PT30S`. |
| `dataprism.links.bind-caller` | `true` | Whether the caller is bound into the link, so that another principal in the scope cannot use it. |

`keys` holds the current key and any key being retired: each is held for
verification, and only `key-id` seals. The `linkKeys` health contributor lists the
held key ids (never a reference or a key byte), so that a member can be confirmed to
hold a new key before it signs; the gauge dataprism.links.keys.loaded is the count.
A reference that does not resolve refuses startup with `LINK_KEY_UNRESOLVED` (so does
`enabled: true` with no key), material under 32 bytes with `LINK_KEY_WEAK`, and the
pseudonymisation key's id or bytes (compared in constant time, for every key in
`keys`) with `LINK_KEY_SHARED`. A limit out of its range, or a key id outside the
allowed shape, is `INVALID_LINK_LIMIT`. With links enabled, `dataprism.paging.lifetime`
above `dataprism.links.lifetime` is `INVALID_PAGING_SETTING`. No message contains a
reference, a value or a key byte. The link keys, the codec built from them and the
attempt gate are not replaceable (`FORBIDDEN_PRIVACY_OVERRIDE`).

With links enabled, a tool that issues a link (`get_entity_context`, `list_sources`)
listed in `dataprism.oversight.approval-required-tools` needs `follow` listed too, or
startup is refused with `FOLLOW_NOT_APPROVAL_GATED`: a link is not an authorisation, and
`follow` would otherwise reach what the gated tool does not.

**The failed-attempt limiter.** A sealed-token consumer is active when
`dataprism.links.enabled` is `true` or a link key is configured: a page link on
`list_sources` is a sealed link too. While one is active, every link is opened through
one gate that counts failed attempts per caller. On `single-node`, and on `embedded` with
`join.mode: none`, the count is kept in memory from `invalid-attempt-limit` and
`invalid-attempt-window`; an application may supply its own `LinkAttemptLimiter` bean
instead. On `embedded` with `join.mode` `tcp-ip` or `kubernetes` the count must be shared
by every member, or a caller could try the limit again on each one. The same holds on
`embedded` with an application-supplied `PrivacyCluster`, even when it has one member. No
cluster-wide limiter ships in 1.0, so on such a member startup is refused with
`MISSING_SHARED_LINK_LIMITER` unless the application supplies a limiter declared as
`ClusterWideLinkAttemptLimiter`, by its `@Bean` return type or by its class; a bean declared
only as `LinkAttemptLimiter`, or whose type cannot be read without building it, is refused.
The message names the condition and never a value. With no
consumer active (links off and no link key) there is no refusal on any topology. The
code covers page links as well as follow links.

### `dataprism.key-resolvers`

How `follow` translates a key from one namespace into another through an application
`KeyResolver`. Resolvers are registered as beans only; no property names a class.

| Property | Default | Meaning |
|---|---|---|
| `dataprism.key-resolvers.max-targets` | `5` | The most keys one resolver may return for one key. 1 to 16. |
| `dataprism.key-resolvers.timeout` | `PT2S` | The longest a resolver call may take. A positive duration. |
| `dataprism.key-resolvers.budget-cost` | `1` | The reads one resolver call is charged against the scope budget. Positive. |

A value out of range refuses startup with `INVALID_KEY_RESOLVER_SETTING`, naming the
property and never the value. Every `KeyResolver` bean is collected while links are enabled
and reaches `follow()` ordered by name. Two beans for one `(from, to)` pair, or with one name, refuse
startup with `DUPLICATE_KEY_RESOLVER`; a name outside `[a-z][a-z0-9-]{0,31}`, a resolver
into its own namespace or one that cannot be read refuses it with
`INVALID_KEY_RESOLVER`. A pair that joins a namespace no source or chainable field uses
is logged once at WARN, with the count and the pair's namespace names only.

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
- A `tcp-ip` or `kubernetes` member requires `member.interface` and listens only
  there; one that matches every address is refused. Member traffic is not
  encrypted or authenticated, so isolate it on a private network.
- A `tcp-ip` or `kubernetes` member also requires `trusted-network: true` and
  starts only if everything it listens on and advertises is private. Every
  address `member.interface` can match, the `hazelcast.local.localAddress` and
  `hazelcast.local.publicAddress` system properties, and the network config's
  public address must lie in `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` or
  `127.0.0.0/8`, or in a `member.trusted-ranges` entry; a pattern that spans two
  blocks without lying inside one (`172.15-16.*.*`) is refused. Either of
  `hazelcast.socket.bind.any` and `hazelcast.socket.server.bind.any` set to
  `true` is refused. All of these are `PUBLIC_MEMBER_INTERFACE`. Multi-member
  is supported on a trusted network only: approvals and counters are unsealed,
  and `trusted-network: true` is your assertion that nobody else can reach the
  member port. It does not make a network safe. A joining member that starts
  logs the assertion and the member port once at INFO, with no address.

Every cluster that joins other members needs a cluster key and a separate name
key, each given as a key id and a reference. They are checked at startup, before a
member starts. The message names the property and never a value. A solo member
(`join.mode: none`) may leave both out. When it gives either, all of
this applies to it as well, and it must give both. With `topology: single-node`, or
beside your own `PrivacyCluster` bean, any `dataprism.hazelcast.security.*` key is
refused (`CLUSTER_SETTINGS_IGNORED`) because nothing would read it.

A solo member listens on loopback only. A `hazelcast.*` address property that would make a single member reachable
elsewhere is refused: `INVALID_CLUSTER_INTERFACE` for a solo member that holds keys, and `MISSING_CLUSTER_KEY` for a
keyless one. A keyless member's `hazelcast.local.localAddress` and `hazelcast.local.publicAddress` must each be a
literal loopback address; `localhost` is refused.

A member without keys is always solo: it listens on `127.0.0.1` only, under a
cluster name with a random suffix, so no keyed member can join it. This holds for
a `Config` passed to `PrivacyCluster.embedded` too. A `PrivacyCluster` or Hazelcast
instance you supply without keys must be isolated the same way by you. One that is
reachable from other hosts is refused (`MISSING_CLUSTER_KEY`): it must have `bind.any` false (both
`hazelcast.socket.bind.any` and `hazelcast.socket.server.bind.any`), interfaces enabled and listing
only loopback entries, no public address, and a loopback member address. The same code refuses an
instance that already has more than one member.

A late join is detected, not prevented. If a member joins a keyless wrapper anyway (or was already
present when the wrapper registered its listener), the wrapper logs `MISSING_CLUSTER_KEY`, refuses
every later operation, and shuts the instance down only if `PrivacyCluster` started it. Partition
replicas reach the joiner before any listener runs, and a supplied instance keeps running, so this
limits what the wrapper goes on serving; it does not undo any exposure. Isolation at start is the
control.

The packaged server reads cluster and name keys from environment variables only. A key
given as `provider-reference` there is refused (`MISSING_KEY_PROVIDER`, naming the
property) and is never looked up as the name of an environment variable. A custom
`HmacKeyReferenceResolver` serves provider references by overriding
`resolveProviderReference`; by default that refuses with `MISSING_KEY_PROVIDER`, so a resolver
that has not opted in never treats a provider reference as a variable name. The privacy HMAC key follows the same
rule: a `dataprism.privacy.hmac-key.provider-reference` is read through `resolveProviderReference`
and an `environment-variable` through `resolve`.

The first failing check is the one reported, and the checks run in this order:

| Step | Code | When |
|---|---|---|
| 1 | `MISSING_CLUSTER_KEY` | A key id or its source is missing, both sources are set, one of the two keys is set without the other, or `next` or `previous` is set without a current key. Checked for the cluster key, the name key, `next`, then `previous` |
| 2 | `LITERAL_SECRET_FORBIDDEN` | A `value` is set on the cluster key, the name key, `next` or `previous` |
| 3 | `MISSING_MEMBER_INTERFACE`, `UNBOUNDED_MEMBER_INTERFACE`, `INVALID_CLUSTER_INTERFACE` | `member.interface` is missing for `tcp-ip` or `kubernetes`, matches every address, or is not an IPv4 literal or wildcard pattern |
| 4 | `INVALID_TRUSTED_RANGE`, `PUBLIC_MEMBER_INTERFACE` | A `trusted-ranges` entry does not parse or is wider than a `/24` outside the private blocks; then the interface, an advertised address or a bind-any setting reaches beyond the private blocks and the ranges |
| 5 | `MISSING_TRUSTED_NETWORK` | `trusted-network` is not `true` for `tcp-ip` or `kubernetes` |
| 6 | `CLUSTER_KEY_UNRESOLVED`, `NAME_KEY_UNRESOLVED` | A key id contains a dot (a blank key id is step 1). Checked for the cluster key, `next`, `previous`, then the name key |
| 7 | `CLUSTER_KEY_UNRESOLVED`, `CLUSTER_KEY_WEAK`, `NAME_KEY_UNRESOLVED`, `NAME_KEY_WEAK` | A reference does not resolve, or resolves to material shorter than 32 bytes. Each key is resolved and measured in turn: the cluster key, `next`, `previous`, then the name key |
| 8 | `HMAC_KEY_UNRESOLVED` | The privacy HMAC key cannot be resolved again to compare the cluster keys with it |
| 9 | `CLUSTER_KEY_SHARED`, `NAME_KEY_SHARED` | A cluster key (current, `next`, `previous`), then the name key, has the key id or the material of the privacy HMAC key |
| 10 | `AUDIT_HMAC_KEY_UNRESOLVED`, `AUDIT_HMAC_KEY_TOO_SHORT`, `CLUSTER_KEY_SHARED`, `NAME_KEY_SHARED` | Only with `dataprism.audit.hmac-key` set. An audit key (current or retired) does not resolve or is shorter than 32 bytes; then a cluster key (current, `next`, `previous`), then the name key, has the key id or the material of an audit key (current or retired) |
| 11 | `CLUSTER_KEY_SHARED`, `NAME_KEY_SHARED` | Only with `dataprism.links.keys` set. A cluster key (current, `next`, `previous`), then the name key, has the key id or the material of a link key (current or retiring). A link key that does not resolve here is left to `LINK_KEY_UNRESOLVED` |
| 12 | `NAME_KEY_SHARED` | The name key has the material of a cluster key |
| 13 | `DUPLICATE_CLUSTER_KEY_ID` | The `current`, `next`, `previous` and name key ids are not pairwise distinct |

The full property list, every refusal code, the Compose and Kubernetes examples
and the network isolation you must supply are on
[Running multiple instances](multiple-instances.md).

### Logging

The MCP SDK logs the raw JSON-RPC message at DEBUG, including client-supplied
arguments such as `subjectId`, and `Client initialize request` at INFO with the
client-supplied `clientInfo`. Data Prism pins the whole `io.modelcontextprotocol`
logger prefix to **WARN**, so neither line is written, on stdio and on
streamable HTTP, and any line a later SDK version adds is covered too.

- **Spring applications** (the server, the images and every starter
  application): `McpSdkLogPin`, an `EnvironmentPostProcessor` registered in
  `spring.factories`, adds `logging.level.io.modelcontextprotocol=WARN` as the
  lowest-precedence property source. Turning on `logging.level.root=DEBUG` does
  not undo it, and neither does a custom `logback-spring.xml`.
- **Plain-Java stdio** (`ExampleApplication`, on slf4j-simple):
  `org.slf4j.simpleLogger.log.io.modelcontextprotocol=warn` in
  `simplelogger.properties`.
- **A plain-Java embedder of `DataPrismMcpServer`** that uses its own SLF4J
  binding must set the same level itself.

Naming `io.modelcontextprotocol` (or one of its loggers) at DEBUG or INFO
yourself overrides the pin. That is unsupported in production, because it logs
client arguments.

## Oversight, re-identification and the operator surface

Three groups, all off or empty by default. Together they turn the oversight and
re-identification libraries into deployed behaviour; the HTTP endpoints that
operate them exist on a second port and are described in
[Re-identification](reidentification.md#the-operator-http-surface).

### `dataprism.oversight`

| Property | Default | Meaning |
|---|---|---|
| `approval-required-tools` | empty | Tools whose calls need a human approval first. Only `get_entity_context`, `compare_entity_sources`, `follow`, `list_sources` and `describe_source` are valid |
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
| `UNKNOWN_OVERSIGHT_TOOL` | An approval-required tool name that is not one of `get_entity_context`, `compare_entity_sources`, `follow`, `list_sources` and `describe_source` |
| `FOLLOW_NOT_APPROVAL_GATED` | `dataprism.links.enabled` is `true` and `get_entity_context` or `list_sources` is in `approval-required-tools` without `follow` |
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
unrelated to `dataprism.sources` and never merged with it. From 0.8 it can also
be a list of such locations, so that several teams can each keep their own file:

```yaml
dataprism:
  json-sources:
    config-location:
      - file:/etc/dataprism/depot-sources.yaml
      - file:/etc/dataprism/carrier-sources.yaml
```

The property binds as one list with ordinary property-source precedence, so a
later source of configuration replaces the whole list. **A single value is never
split at commas**: a URL with a comma in it is one location. Every file is parsed
strictly and the sources are joined. A source name that appears in two files
refuses startup with `DUPLICATE_JSON_SOURCE`, naming the source and neither
location. A `tls:` block stated in two files refuses startup, and so does a
`tls:` block in one file beside a plaintext source in another.
Each named entry under `json-sources:` states, and only states:

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
- `description:` (optional) — what the source holds, as `list_sources` and
  `describe_source` show it. Without it the source is listed as `A data source.`
  with no entity types, and startup logs how many sources use that generated
  text. Its keys:

  | Key | Meaning |
  |---|---|
  | `entity-types` | a list of entity types the source answers, each `[A-Za-z][A-Za-z0-9_-]{0,63}`; no duplicates |
  | `input-keys` | a list of key namespaces (`[a-z][a-z0-9-]{0,31}`) the source accepts as a subject key; at most eight, no duplicates |
  | `namespace-types` | a map from an `input-keys` namespace to one of the `entity-types`. Needed for a link target when there are several entity types and any input key |
  | `alias-description` | the neutral text shown under an alias. Written for the model, so it must not name the system |
  | `real-description` | the text shown under real names; the alias text is used when absent |
  | `cost`, `freshness` | advisory hints: `cost` is `LOW`, `MEDIUM` or `HIGH`; `freshness` is `LIVE`, `NEAR_LIVE`, `HOURLY`, `DAILY` or `PERIODIC` |

  Description text must be one line of at most 240 characters made of letters,
  digits, spaces and the punctuation `. , ; : ( ) - _ / ' "`, with no key, URL,
  host name, address, or sensitive-looking value in it. A dotted run such as
  `e.g.` or `i.e.` looks like a host name and is refused, so write "for
  example". The alias text may not contain the source's name, and may not equal
  the real text. A text that breaks one of these refuses startup with
  `INVALID_SOURCE_DESCRIPTION`, or `SOURCE_DESCRIPTION_LEAKS_NAME`; the message
  names the source and the rule and never the text. See
  [Describing a source](extending.md#describing-a-source).
- On an `identifier: true` entry of `fields:` (not inside a nested catalogue),
  `chainable: true` with a `keyNamespace` says that the value is a key another
  source accepts, so a link can be issued from it. The two are stated together
  or not at all; anything else is `INVALID_CHAINABLE_FIELD`. Links are issued
  only when `dataprism.links.enabled` is `true`.

A response whose shape no longer matches a source's declared nesting is a
request-time privacy refusal, not a startup refusal — the catalogue was valid
at load time; the wire shape drifted from what it declared. `data-prism-connectors-rest`
raises `NESTED_LEAF_NOT_SCALAR` when a leaf field (one with no `nested:` of
its own, in a nested catalogue or at the root) turns up as a structure in the
response, and
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

## Startup refusal index

A startup refusal stops the application before it serves anything. The code is the first token of the failure's
message, and the message names the property or the source and never a value. This index lists the codes that no
other section of this page lists with its property, so that a code seen in a log can be looked up. A code that has
its own table elsewhere (for example the case-authorisation codes under
[`dataprism.security-policy`](#dataprismsecurity-policy-case-authorisation-and-client-allowlist), the
cluster codes under [`dataprism.hazelcast`](#dataprismhazelcast) and in
[Running multiple instances](multiple-instances.md#refusals), or the link, paging and limit codes under their own
headings) is listed there, not here.

**Transport, security and caller claims**

| Code | Condition |
|---|---|
| `INVALID_TRANSPORT` | `dataprism.transport.mode` is not `http` or `stdio` |
| `FIXTURE_DEVELOPMENT_STDIO_ONLY` | `dataprism.transport.fixture-development` is set with a mode other than `stdio` |
| `INVALID_HTTP_PATH` | a configured HTTP path does not start with `/`, contains `//`, or contains `?` |
| `MISSING_CALLER_CONTEXT_EXTRACTOR` | the HTTP transport is on and no `McpTransportContextExtractor<HttpServletRequest>` bean exists |
| `MISSING_JWT_ISSUER`, `MISSING_JWT_AUDIENCE` | a protected deployment without `dataprism.security.jwt.issuer` or `audience` |
| `INVALID_JWT_LOCATION` | not exactly one of the JWKS URI and the issuer-discovery URI, or one that is not an approved HTTPS URI |
| `JWT_DISCOVERY_FAILED` | issuer discovery did not return usable metadata |
| `MISSING_CALLER_CLAIM` | the principal or roles claim mapping under `dataprism.security.caller-claims` is blank |
| `DUPLICATE_CALLER_CLAIM` | the principal, roles and investigation claim names are not three distinct names |
| `RESERVED_CALLER_CLAIM` | a caller-claim mapping uses a reserved tool-argument name |
| `MISSING_ROLE_POLICY` | `dataprism.security-policy.roles` is empty |
| `EMPTY_PURPOSE_LIST`, `BLANK_PURPOSE` | `dataprism.security-policy.purposes` is empty, or one entry is blank |
| `BLANK_ROLE`, `EMPTY_ROLE_CAPABILITIES` | a role name is blank, or a role grants no capability |
| `UNKNOWN_CAPABILITY` | a role grants a capability Data Prism does not know |

**Privacy, keys and models**

| Code | Condition |
|---|---|
| `MISSING_PRIVACY_PROFILE`, `UNKNOWN_PRIVACY_PROFILE` | `dataprism.privacy.profile` is blank, or names no bundled profile |
| `INVALID_SCOPE_LIFETIME` | `dataprism.privacy.scope-lifetime` is missing, zero or negative |
| `UNSUPPORTED_LOCALE` | `dataprism.privacy.locale` is not a locale with a built-in vocabulary (`neutral` is accepted) |
| `MISSING_HMAC_KEY_REFERENCE` | `dataprism.privacy.hmac-key` has no `key-id`, or does not state exactly one of `environment-variable` and `provider-reference` |
| `LITERAL_SECRET_FORBIDDEN` | a `value` is set on the HMAC key or on a cluster or name key. Keys are always referenced, never written in configuration |
| `HMAC_KEY_UNRESOLVED`, `HMAC_KEY_WEAK` | the HMAC key reference resolves to nothing (also raised while the cluster keys are checked against it), or to fewer than 32 bytes. The resolver's array is never changed or cleared |
| `MISSING_KEY_PROVIDER` | a `provider-reference` key and no `HmacKeyReferenceResolver` that handles it. The packaged server reads environment variables only |
| `MODEL_DESCRIPTOR_FILE_NOT_FOUND`, `MODEL_DESCRIPTOR_FILE_UNREADABLE` | `dataprism.privacy.descriptor-file` names no file, or one that is not a readable regular file |
| `UNSAFE_MODEL_DESCRIPTOR_UNDECLARED_FIELDS` | the descriptor file sets `undeclaredFields: NON_SENSITIVE` for a model |
| `MODEL_DESCRIPTOR_RESOLVER_REPLACED` | `dataprism.privacy.descriptor-file` is set and either a `FieldMetadataResolver` bean other than Data Prism's own from that file exists, or a `JsonTreeScrubbingEngine` bean uses another resolver. With the property set only Data Prism's own resolver is accepted. A custom resolver needs the property unset |
| `SOURCE_MODEL_NOT_A_RECORD`, `SOURCE_MODEL_GENERIC`, `SOURCE_MODEL_MAP_OF_RECORD`, `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` | a source model, or a type inside it, is not a record, is generic, or puts a record where the engine will not read it |
| `SOURCE_MODEL_JACKSON_ANNOTATION` | a Jackson annotation on a source model, other than the five that affect only reading: `@JsonAlias`, `@JsonCreator`, `@JsonDeserialize`, `@JsonSetter` and `@JsonIgnoreProperties(ignoreUnknown = true)` |
| `SOURCE_MODEL_NOT_READABLE` | a record in a named module that does not open its package to Data Prism with an unqualified `opens` |
| `SOURCE_MODEL_RECORD_NOT_DECLARED` | a record value at a position declared as anything but that record |

**Sources and the catalog**

| Code | Condition |
|---|---|
| `BLANK_SOURCE_NAME`, `INVALID_SOURCE` | a `dataprism.sources` key is blank, or its entry is null |
| `INVALID_SOURCE_URL` | a source `base-url` is not an HTTPS URI with a host and no user info, query or fragment. Plain `http` is accepted only for localhost under `fixture-development` |
| `INVALID_SOURCE_TIMEOUT` | a source's `timeout` is missing, zero or negative |
| `INVALID_SOURCE_MTLS` | a source states a key reference or a trust reference of its mutual TLS, but not both |
| `INVALID_SOURCE_CREDENTIAL_REFERENCE` | a source's service credential reference contains a space |
| `UNREVIEWED_SOURCE_ADAPTER` | a `DataSourceAdapter` bean is named neither under `dataprism.sources` nor in a configured JSON source's catalogue |
| `INVALID_SOURCE_DESCRIPTION`, `SOURCE_DESCRIPTION_LEAKS_NAME`, `INVALID_CHAINABLE_FIELD`, `DUPLICATE_JSON_SOURCE` | see [`dataprism.catalog`](#dataprismcatalog) and [`dataprism.json-sources.config-location`](#dataprismjson-sourcesconfig-location). A message that ends "description failed" means the description could not be built; its text is never shown |

**Metrics, audit and plain-Java entry points**

| Code | Condition |
|---|---|
| `MISSING_METRICS_SINK`, `UNKNOWN_METRICS_SINK` | `dataprism.metrics.sink` is absent, or is not `micrometer` |
| `INVALID_METRICS_REFERENCE` | `dataprism.metrics.registry-reference` is blank |
| `MISSING_METRICS_BINDING` | no metrics binding bean is available |
| `AUDIT_JSON_PROJECTION_WITHOUT_BUILT_IN_SINK` | `dataprism.audit.output.json-directory` is set with `sink: hash-chained` while an application `AuditSink` bean replaces the built-in sink, so nothing would write to the directory. Remove the application sink or the `json-directory`. See [Migrating from 0.5.x to 0.6.0](migration-0.6.md) |
| `AUDIT_JSON_PROJECTION_MISSING` | `json-directory` is set but the framework's JSON projection bean is not registered, for example because it was overridden. See [Migrating from 0.5.x to 0.6.0](migration-0.6.md) |
| `LEGACY_OVERSIGHT_STATE` | a Hazelcast member starts and the cluster's oversight map still holds a scope pause written by 0.7.x, which 0.8 does not read. The message names no scope. The fix is a full restart of every member, which clears the cluster state; a rolling upgrade is not supported. See [Migrating from 0.7.x to 0.8.0](migration-0.8.md#breaking-a-hazelcast-cluster-needs-a-full-restart-from-07) |
| `PUBLIC_MEMBER_INTERFACE`, `MISSING_TRUSTED_NETWORK`, `INVALID_TRUSTED_RANGE` | the trusted-network rule, which also holds on the plain-Java entry points that start a member, not only in the starter. The rule is whether the member is reachable beyond its own host: see [Running multiple instances](multiple-instances.md#refusals) |

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
