---
title: Migrating to 0.8.0
description: What changes when upgrading from 0.7.x to 0.8.0, from limits and source models to adapters, configured JSON sources and the new tools.
---

# Migrating from 0.7.x to 0.8.0

This page lists the changes in 0.8.0 that can stop something working, and the new behaviour you will meet when you
upgrade. A section marked Breaking needs action from some deployments; the others say what is different. The
[CHANGELOG](changelog.md) lists the same changes in release order.

## Breaking: a Hazelcast cluster needs a full restart from 0.7

The cutover is non-rolling. Stop every 0.7 member before you start any 0.8 member. 0.8 keeps its cluster state in new
maps (every name ends in `.s1`, every key is a keyed name, and identity and re-identification values are sealed
envelopes) and never reads the 0.7 maps for data. A 0.8 member asks only whether a pre-0.8 map holds entries. If one
does, it refuses to start with `UNSEALED_CLUSTER_STATE`. The compatibility mode that would have let the two generations
serve side by side is withdrawn.

A scope pause written by 0.7 is a case of the same refusal. 0.7 keyed a pause as `all`, `tool:<name>` or the scope id,
a NUL byte and `paused`, in `dataprism.oversight`. A 0.8 member that finds one when its oversight state is built refuses
with `LEGACY_OVERSIGHT_STATE`, because it could not see that pause and the paused scope would resume without anyone
asking. The message names no scope. A pause written afterwards by a 0.7 member still joined to the cluster is treated as
paused when that scope is checked. Raw NUL-keyed entries in the other 0.7 maps are neither detected nor migrated, which
is why the restart must be full and must wipe cluster state: a rolling upgrade leaves 0.7 state in the cluster and the
0.8 member refuses, and it would also reset each subject's read count on each side, because the two generations count in
different maps.

What the full restart costs. The cluster state is cached data that is fetched again, and nothing in it is the system of
record:

- **Pending approvals are lost** and must be requested again.
- **The re-identification index starts empty.** A pseudonym issued before the restart can no longer be re-identified.
- **Read counts and pauses are gone.** Re-issue any pause you still need after the restart.

**The 0.7 fence.** Each serving 0.8 member writes one entry, `all` = `TRUE`, in the old `dataprism.oversight` map, so
that a 0.7 member that joins anyway reads everything as paused. It is the only entry in a pre-0.8 map that 0.8 tolerates.
It is a best-effort fence, not a barrier: it does not stop a 0.7 member joining, and anyone who can reach the member port
can delete the entry until a member writes it again. Do not rely on it to make a rolling upgrade safe.

**The scope read budget.** `dataprism.limits.scope-read-budget` is still 100, but it now counts source reads, not calls.
A subject therefore gets about 12 calls that ask eight sources, not 100. See
[Breaking: limits, timeouts and read budgets](#breaking-limits-timeouts-and-read-budgets).

See [Running multiple instances](multiple-instances.md#refusals) and [Rotating keys](key-rotation.md).

## Breaking: a `tcp-ip` or `kubernetes` member needs `trusted-network` and a private interface

A member that joins other members (`tcp-ip` or `kubernetes`) is refused at startup unless you declare the network it
runs on. Three things are now required. Approvals and counters on the member port are not sealed, so anyone who can
reach the port can read and forge them.

- **`dataprism.hazelcast.trusted-network: true`** (`MISSING_TRUSTED_NETWORK` without it). This is your assertion that
  only Data Prism members can reach the member port. It is not inferred from a private address, and Data Prism cannot
  check it: it checks that you made the assertion and that the addresses below are private or in your declared ranges.
  It does not stop an untrusted host that can reach the port from joining. You must isolate the port yourself with a
  firewall, a `NetworkPolicy` or a private network, because member traffic is not encrypted.
- **`dataprism.hazelcast.member.interface`**, required on `tcp-ip` and `kubernetes`, bounded and valid. Unset used to
  bind every interface. Now unset is `MISSING_MEMBER_INTERFACE`, a value that matches every address is
  `UNBOUNDED_MEMBER_INTERFACE`, and a value that is not an IPv4 literal or a wildcard such as `10.0.*.*` is
  `INVALID_CLUSTER_INTERFACE`. The interface must be private, or inside a `member.trusted-ranges` entry
  (`PUBLIC_MEMBER_INTERFACE`). It is refused with `none`.
- **`dataprism.hazelcast.member.trusted-ranges`**, optional: IPv4 CIDR blocks you trust beyond the private ones. A
  malformed or too wide entry is `INVALID_TRUSTED_RANGE`.

```yaml
# 0.7.1
dataprism:
  hazelcast:
    topology: embedded
    cluster-name: prod
    join:
      mode: tcp-ip
      members: ["10.0.1.11", "10.0.1.12"]

# 0.8.0
dataprism:
  hazelcast:
    topology: embedded
    trusted-network: true
    member:
      interface: "10.0.*.*"
    cluster-name: prod
    join:
      mode: tcp-ip
      members: ["10.0.1.11", "10.0.1.12"]
```

The cluster and name keys (next section) are also required and are left out of this example; see
[the configuration reference](configuration.md) for each property and [Running multiple instances](multiple-instances.md)
for what the declaration does and does not do.

**Plain Java.** The same rule holds on the entry points that start a member, not only in the starter. The declaration is
the `trustedNetwork` and `trustedRanges` components of `ClusterSecuritySettings`, set with `withTrustedNetwork`:

```java
// 0.7.1
PrivacyCluster.embedded(ClusterMembership.tcpIp("prod", members), true);

// 0.8.0
PrivacyCluster.embedded(
        ClusterMembership.tcpIp("prod", members).withInterface("10.0.*.*"),
        true, OptionalInt.empty(), Duration.ofMinutes(5), keys,
        ClusterSecuritySettings.defaults().withTrustedNetwork(true, List.of(), false));
```

`keys` is a `ClusterKeys` (see the next section). The last argument of `withTrustedNetwork` says whether the host has
already logged the assertion; pass `false` unless it has.

## Breaking: a cluster key and a name key are required, and the plain-Java entry points check more

On `tcp-ip` and `kubernetes`, `dataprism.hazelcast.security.cluster-key` and `dataprism.hazelcast.security.name-key` are
required: each takes a `key-id` and exactly one of `environment-variable` or `provider-reference`. A member whose key
differs from the cluster's refuses to start with `CLUSTER_KEY_MISMATCH`. A key given as `provider-reference` is refused
with `MISSING_KEY_PROVIDER` unless a reviewed resolver extension is supplied (see the next section); the packaged server
resolves `environment-variable` only. In plain Java, a joining membership without `ClusterKeys` is refused with
`MISSING_CLUSTER_KEY`.

A cluster-key or name-key change is a planned full restart that empties the identity and re-identification maps. See
[Rotating keys](key-rotation.md) for each procedure and its cost.

`PrivacyCluster.using(instance, ...)` now checks the instance you supply and refuses it with
`UNSAFE_HAZELCAST_SERIALIZATION` unless all of these hold. An integrator must supply them:

- the serialization filter `PrivacyCluster.requiredSerializationFilter()`; a hand-built filter must allow everything it
  allows, including `PartitionStateVersionMismatchException`;
- a non-empty Management Center `trustedInterfaces` list, because an empty list is unrestricted;
- phone-home off, no memcache protocol, and REST enabled for `HEALTH_CHECK` only;
- no Jet and no user-code deployment.

`PrivacyCluster.close()` now stops only an instance the wrapper started. An instance you passed to `using()` is left
running, so shut it down yourself.

## Breaking: provider references reach `resolveProviderReference`

`HmacKeyReferenceResolver` has a second method, `resolveProviderReference(keyId, reference)`. A key configured as a
`provider-reference` (the privacy HMAC key, the cluster key and its `next` and `previous`, and the name key) is read
through it. A key configured as an `environment-variable` is still read through `resolve`.

The default of `resolveProviderReference` refuses with `MISSING_KEY_PROVIDER` and names no reference, so a resolver
that reads only environment variables can never treat a provider reference as the name of a variable.

If you wrote a custom `HmacKeyReferenceResolver` that handled provider references inside `resolve()`, implement
`resolveProviderReference` as well. Until you do, a `provider-reference` fails at startup with
`MISSING_KEY_PROVIDER`. A resolver that only ever gets `environment-variable` configuration needs no change.

The packaged server reads environment variables only, so it refuses a `provider-reference` for any of these keys.

## Breaking: a schema-invalid tool call returns `TOOL_INPUT_INVALID`

Before 0.8.0 the MCP SDK checked a call's arguments against the tool's input schema before any Data Prism code
ran, and answered `Tool (<name>) input validation failed: ...`. Such a call was not authenticated, audited or
counted. Each tool now checks its own input, after authentication and authorisation, so:

- a schema-invalid call returns the one refusal `TOOL_INPUT_INVALID`, with the call's correlation id. It names no
  field and no value, and it is the same for every violation;
- on `list_sources`, a call that carries a `page` key and breaks the schema anywhere (a `page` that is empty,
  longer than `dataprism.links.max-length`, not a string or `null`, or any other argument) is answered as a forged
  link, `LINK_INVALID` ("The link is not valid."), and counts once toward the link-attempt limiter;
- every such call is audited once (`DENY:TOOL_INPUT_INVALID`, or the link refusal) and counted as denied;
- a role without the tool's capability still gets `TOOL_NOT_PERMITTED`, valid input or not.

A client that matches the old `input validation failed` text must match `TOOL_INPUT_INVALID` instead. A blank
`entityType` or `subjectId` on `get_entity_context` or `compare_entity_sources` no longer answers "entityType and
subjectId are both required"; it is `TOOL_INPUT_INVALID`. The served input schemas are unchanged. A request that is
not a valid `tools/call` (for example `arguments` that is not an object, or an unknown tool name) is still refused
by the SDK before any handler, and is not audited.

## Breaking: source models are read by the engine

Before 0.8.0 Jackson wrote each source record, so annotations such as `@JsonProperty` and `@JsonSerialize` changed
what the scrubber saw. From 0.8.0 Data Prism reads a source record itself: each component is written under its own
name with its own value, and the privacy decision is made on that tree. This closed a known gap in 0.7.x and earlier:
a `@JsonProperty` rename that swapped two components' names, or a class-level serializer that wrote one component's
value under another component's name, could release a sensitive value under a name classified as harmless.

What now refuses, and when:

- **Startup, `SOURCE_MODEL_JACKSON_ANNOTATION`.** Any Jackson annotation on a source model, other than the five that
  affect only how an adapter reads the upstream response: `@JsonAlias`, `@JsonCreator`, `@JsonDeserialize`,
  `@JsonSetter` and `@JsonIgnoreProperties(ignoreUnknown = true)` with no names. A Jackson annotation is one in
  `com.fasterxml.jackson.annotation` or `tools.jackson.databind.annotation`, or one meta-annotated
  `@JacksonAnnotationsInside`. It is looked for on the record and its interfaces, each component (and its field,
  accessor and canonical-constructor parameter), every other declared method, and the enums the model reaches.
- **The earlier allowance is withdrawn.** `@JsonSerialize`, `@JsonAnyGetter`, `@JsonValue`, `@JsonUnwrapped`,
  `@JsonNaming` and `@JsonFormat` were allowed on source models before 0.8.0. They are refused now, and so is a
  `@JsonIgnoreProperties` that names properties.
- **`SOURCE_MODEL_RECORD_NOT_DECLARED`.** A record value at a position that is not declared as exactly that record
  (`Object`, an interface, `Record`, a type variable, an unbounded wildcard or a raw collection) is refused, at startup
  and again at runtime when a record turns up in such a position.
- **`SOURCE_MODEL_NOT_READABLE`.** The engine reads record fields itself. On the module path, a model in a named module
  must open its package with an unqualified `opens`. On the class path nothing changes.
- **`SOURCE_MODEL_FOREIGN_NODE` and `SOURCE_TREE_NOT_COMPONENT_EXACT`.** A `JsonNode` that is not made of Jackson's own
  node classes, and (defence in depth) a built record node whose property names are not exactly its component names.
- **`SOURCE_VALUE_READ_FAILED`.** A read of a source value that throws now refuses that source alone. A caller that
  saw `REQUEST_FAILED` (Java) or "the request could not be completed" (MCP) now gets a response with that source
  marked `REFUSED`, and sees `SOURCE_VALUE_READ_FAILED` (Java) or `refused: SOURCE_VALUE_READ_FAILED at <path>` (MCP)
  as the call's refusal only when no other source answered. Errors and checked exceptions that used to escape
  unaudited are now refused and audited.

A plain-Java embedder that builds the engine without Spring must call `SourceModels.require` on every response type
before serving, because that is where an unreadable record is refused.

**The fix list.** The same text is in [Extending Data Prism](extending.md) and the CHANGELOG.

> To fix a model refused with `SOURCE_MODEL_JACKSON_ANNOTATION`, remove the annotation the message names. From 0.8, Data Prism reads a source record itself: each component is written under its own name with its own value, and Jackson annotations on the record, its components, its methods and the enums it holds change nothing. If the annotation renamed a component in the output, rename the component. If `@JsonProperty` mapped an upstream response's name onto the component, use `@JsonAlias` for that name instead. If it changed how a value is written (`@JsonValue`, `@JsonSerialize`, `@JsonFormat`), give the component the type you want written: for a single sensitive value, a `String` with `@SensitiveData` instead of a record that serialises as one; for a formatted date, a `String` holding the text. If it hid a component (`@JsonIgnore`), remove the component or classify it. If it added keys to the object (`@JsonAnyGetter`), the keys can no longer sit beside the components: hold them in a named `Map<String, Object>` or `JsonNode` component. That component is never descended into. Classify it as a whole: unannotated or marked `@NonSensitive`, the profile's setting for unclassified data decides the whole value, and the bundled DEFAULT profile refuses it. If the keys must be handled one by one, serve that payload as a configured JSON source instead. `@JsonAlias`, `@JsonCreator`, `@JsonDeserialize`, `@JsonSetter` and `@JsonIgnoreProperties(ignoreUnknown = true)` stay allowed, because they affect only how an adapter reads the upstream response. To fix `SOURCE_MODEL_RECORD_NOT_DECLARED`, declare the position as the record type itself, not `Object`, an interface, `Record`, a type variable, an unbounded wildcard or a raw collection. To fix `SOURCE_MODEL_NOT_READABLE`, which can happen only on the module path, add an unqualified `opens` for the model's package to its `module-info.java`. To fix `SOURCE_MODEL_NOT_A_RECORD` on an `AtomicReference` or another reference type, hold the value itself, or an `Optional` of it. On a `Stream`, an `Iterator` or another single-use type, collect it into a `List` first. To fix `SOURCE_MODEL_FOREIGN_NODE`, build the tree from Jackson's own node types and do not put a Java object into it (`putPOJO`).

## Breaking: a descriptor file needs Data Prism's own resolver

With `dataprism.privacy.descriptor-file` set, startup refuses with `MODEL_DESCRIPTOR_RESOLVER_REPLACED` unless the
only `FieldMetadataResolver` bean, and every `JsonTreeScrubbingEngine` bean in the context and its ancestors, uses the
resolver Data Prism builds from that file. The scrubber now takes its model descriptors from that resolver, so a
described chainable field and its key namespace reach it. If you have a custom resolver, or a scrubber built with the
default one, unset the property or remove the replacement: a custom resolver needs the property unset.

## Breaking: limits, timeouts and read budgets

The fan-out was rewritten. What changes:

- **The per-source timeout starts with the call.** `dataprism.limits.per-source-timeout` (default `3s`) is measured from
  the moment a source's own call starts, after it holds a concurrency permit. Before, every source was timed from the
  start of the whole fan-out, so a source called late had less time. A request that arrives while a trial call to an
  open-breaker source is still running now gets `TIMED_OUT`, not `CIRCUIT_OPEN`.
- **A new overall deadline.** `dataprism.limits.request-timeout` (default `PT10S`) bounds the whole request, including
  the wait for a permit. A source not started by then is never called and reads `TIMED_OUT`. It may not be shorter than
  `per-source-timeout`: a deployment that raises `per-source-timeout` above `PT10S` must raise this too, or startup is
  refused with `INVALID_REQUEST_LIMITS`.
- **Reads are charged per source.** One read is charged for each source a call will ask, after the circuit-breaker
  filter and before any source is called. A source with an open circuit is not charged, and reads reserved for a source
  that never started are given back. `dataprism.limits.scope-read-budget` (default 100) now counts source reads, not
  calls: a call that asks eight sources costs eight, so the same default allows about an eighth as many such calls per
  subject. Raise it if you relied on the old count.
- **A new scope total.** `dataprism.limits.scope-total-read-budget` (default 50,000) caps the reads across all subjects
  of one scope. See [Sizing the read budget](operations.md#sizing-the-read-budget).
- **`SKIPPED_OVER_LIMIT` is gone.** A call that would ask more than `dataprism.limits.max-sources` sources (default 8)
  is refused as a whole with `SOURCE_SELECTION_TOO_LARGE` before any source is called, instead of marking the extras
  `SKIPPED_OVER_LIMIT`. The status is removed, so there are eight status codes (see
  [the tool reference](tools.md#get_entity_context)); a client that matches `SKIPPED_OVER_LIMIT` should match the
  refusal. The count is taken after the circuit-breaker filter.
- **An unselected call over the limit is refused.** A call with no `targetSources`, when more permitted sources serve
  the entity type than `dataprism.catalog.unselected-source-limit` (default 8), is refused with
  `SOURCE_SELECTION_REQUIRED`. A deployment with more adapters than `max-sources` and no selection refuses every call
  until the agent selects sources.
- **Plain Java.** `RequestLimits` has two more components, `requestTimeout` and `scopeTotalReadBudget`, and no
  forwarding constructor: update every `new RequestLimits(...)`.

## Breaking: real source names need a scope as well as a capability

`EXPOSE_SOURCE_NAMES` alone no longer shows real source names. A source is shown under its real name only when the
caller holds `EXPOSE_SOURCE_NAMES` **and** the privacy scope id matches `dataprism.catalog.real-names-scopes`. The list
is empty by default, so with the default every caller sees aliases, whatever its capabilities. To opt a scope in, list
its id, or a prefix ending in one `*`:

```yaml
dataprism:
  catalog:
    real-names-scopes:
      - "case-1042"   # one scope, by its exact id
      - "ops-*"       # every scope whose id starts with ops-
```

The audit key `names` records which setting a call ran under (`REAL` or `ALIAS`).

## Breaking: the 14-argument `DefaultContextOrchestrator` constructor is removed

The public 14-argument `DefaultContextOrchestrator` constructor, which shipped in 0.7.1, is gone, with no deprecated
overload. Its replacement takes an `OrchestrationOptions` as a fifteenth, last argument, so that a later collaborator
is added to the options and no call site moves again. Starter users are unaffected.

```java
// 0.7.1
new DefaultContextOrchestrator(adapters, scrubber, resolver, validators, synthetics, fingerprinter, audit,
        identities, fanOut, budget, limits, correlation, aliasing, metrics);

// 0.8.0
new DefaultContextOrchestrator(adapters, scrubber, resolver, validators, synthetics, fingerprinter, audit,
        identities, fanOut, budget, limits, correlation, aliasing, metrics, OrchestrationOptions.defaults());
```

`OrchestrationOptions.defaults()` gives the 0.8 defaults: a catalog built from the adapters, every source visible
to every caller, no real names, no links. Two differ from 0.7.1: a caller holding `EXPOSE_SOURCE_NAMES` now sees
aliases (`RealNamesPolicy.none()`), and an embedder with more than 8 adapters has `get_entity_context` calls without
`targetSources` refused with `SOURCE_SELECTION_REQUIRED` (`unselectedSourceLimit` 8). Use its `with...` methods to change one setting. The eight-argument
convenience constructor keeps its signature, but it now builds with `OrchestrationOptions.defaults()`, so an embedder
calling it gets the aliases and the `SOURCE_SELECTION_REQUIRED` limit described above.

## Breaking: adapters run on their own threads

Source calls run on dedicated platform daemon threads named `dataprism-source-<n>`, one fresh thread per call, no
longer on virtual threads. For an adapter this means:

- No `ThreadLocal` or `InheritableThreadLocal` carries into a call: each call has a new thread that inherits none. The
  MDC and the context class loader are those of the call's own request.
- At most 32 calls are in flight for one source, and 128 across all sources. A call that finds no free slot is not
  made: the source reads `FAILED` and the metric `dataprism.source.calls_saturated` counts it. Four sources hung at
  their ceiling fill the pool, and then every source reads `FAILED` until a hung call returns. The circuit breaker
  would stop a hung source reaching its ceiling, and it is disabled in every shipped wiring.
- An interrupt does not stop an adapter blocked on a plain `java.net.Socket`. An adapter built on a blocking-socket
  client must set a read timeout; without one a stalled peer holds that call's slot until it closes. The default
  `RestClient` is not affected, and the quickstart already sets a read timeout. This is an accepted residual.

## Breaking: a source value over 1,000,000 nodes is refused

One source value that holds more than 1,000,000 nodes (records, elements, map entries, `JsonNode`s and leaves, each
counted once whatever its size) is refused with `SOURCE_TREE_TOO_LARGE`. That source alone is refused and the others
still answer. The cap counts nodes, not bytes, so it is not a memory limit.

## Breaking: configured JSON sources

- **A structure at a root leaf is `NESTED_LEAF_NOT_SCALAR`.** A response that carries an object or an array at a root
  leaf field (one with no `nested:` of its own) is refused with `NESTED_LEAF_NOT_SCALAR`. In 0.7 that case was refused
  as `UNCLASSIFIED_STRUCTURE`. A root leaf that the profile removes (action `REMOVE`) is exempt and dropped as before.
- **`config-location` is never split at commas.** A single value is one location. The property is also a list now, and
  several locations are joined, with a source name that appears in two files refused as `DUPLICATE_JSON_SOURCE`.
- **New optional blocks.** `description:`, and `chainable` with `keyNamespace` on an identifier entry. A source with no
  `description:` is listed as `A data source.`. See
  [the configuration reference](configuration.md#dataprismjson-sourcesconfig-location).

## Breaking: `ValueTokenSource.available()` defaults to `false`

A custom `ValueTokenSource` that hashes and tokenizes must override `available()` to return `true`. Until it does, a
model describing HASH or TOKENIZE fields omits them and says the source cannot tokenize. A decorator must delegate it
to the source it wraps.

## Breaking: `ClusterMembership` takes a `String` interface

The last component of the `ClusterMembership` record changed from `Optional<String> interfaceAddress` to
`String interfaceAddressOrNull`, which is `null` when no interface is set. A call to the canonical constructor and a
record deconstruction pattern both stop compiling. The factories (`tcpIp`, `kubernetes`, `none`), `withPort` and
`withInterface` are unchanged, and the accessor `interfaceAddress()` still returns an `Optional<String>`, so code that
only reads it needs no change. Because `member.interface` is now required on a joining membership, an unset interface
is refused when the member starts.

```java
// 0.7.1
new ClusterMembership("prod", new ClusterMembership.TcpIp(members), 5701, Optional.of("10.0.*.*"));
new ClusterMembership("prod", new ClusterMembership.TcpIp(members), 5701, Optional.empty());

// 0.8.0
new ClusterMembership("prod", new ClusterMembership.TcpIp(members), 5701, "10.0.*.*");
new ClusterMembership("prod", new ClusterMembership.TcpIp(members), 5701, null);
```

## Breaking: the security wiring is explicit

The case and client controls no longer have a permissive default.

- **Startup.** With neither a bean nor the setting, startup refuses with `MISSING_CASE_AUTHORIZER` or
  `MISSING_CLIENT_ALLOWLIST`. Two beans of one type fail with Spring's `NoUniqueBeanDefinitionException` unless one is
  `@Primary`. The three `dataprism.security-policy` properties are in [the configuration reference](configuration.md).
- **Plain Java.** `AuthorizationService` and `ScopeResolver` each gain a required fourth constructor argument. The
  permissive values are `ClientAllowlist.unrestricted()` and `CaseAuthorizer.tokenTrusted()`. `ClientAllowlist.of(Set)`
  restricts clients, and `CaseAuthorizer` (`isAuthorised(CaseAccessRequest)`) is the new SPI for a case check.

```java
// 0.7.1
new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
new ScopeResolver(version, Duration.ofHours(8), purposeValidator);

// 0.8.0
new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION, ClientAllowlist.unrestricted());
new ScopeResolver(version, Duration.ofHours(8), purposeValidator, CaseAuthorizer.tokenTrusted());
```

## Added: `PrivacyCluster.servingInstance()`

Data Prism's own cluster-backed stores now reach Hazelcast through `PrivacyCluster.servingInstance()`. It returns the
same instance as `instance()` while the member is serving, and refuses with `CLUSTER_OPERATION_REFUSED` while it is not
(a member without a valid card past the grace period, a sentinel that does not open, a key check value that differs, or
a pause document that is not fresh). `instance()` is unchanged and ungated, because card refresh and pause state need it
while the member is closed. If you wrote your own cluster-backed store, move it to `servingInstance()`; a store that
calls `instance()` is not refused with the others.

## Changed: `ClusterSecurityStatus` has two more components

`ClusterSecurityStatus` gains `pauseFresh` and `pauseAgeSeconds`, so a deconstruction pattern over the four old
components no longer compiles. The four-argument constructor is kept (fresh, age unknown). `ClusterSecurityStatus.Reason`
gains `PAUSE_NOT_FRESH`: an exhaustive `switch` over it no longer compiles, and a classic `switch` without a `default`
skips the new value silently, so add a `default`. This type was added after 0.7.1 and has not been released, so this does
not break a released API.

## Changed: how a cluster keeps its state

- **One sealed, leased pause document.** The pause is one document in `dataprism.oversight.s1`. An absent, unauthentic or
  stale document means everything is paused. `dataprism.oversight` now holds only the best-effort fence entry `all`.
- **`endScope` waits for the first document.** It is refused with `OVERSIGHT_UNAVAILABLE` until the first pause document
  exists, about one pause lease (30 seconds by default) after a full start.
- **A pause has a size cap.** A pause past 8,176 elements other than `all` is refused with `CLUSTER_OPERATION_REFUSED`.
  Recovery is a planned full restart.
- **A short lock wait.** A member that starts or rejoins can answer `CLUSTER_LOCK_TIMEOUT` for about one tick interval
  after a peer's write. A start-up card write waits up to Hazelcast's 5-second backup timeout.
- **A member without a card stops serving.** Under the default `refuse`, a joined member without a valid card after a
  30-second grace stops every instance serving and sets readiness `DOWN`. Data Prism cannot evict it: this is
  detection and failing closed.
- **Pause log lines.** The WARN prefixes `PAUSE_DOCUMENT_FRESH`, `PAUSE_DOCUMENT_LAPSED`,
  `PAUSE_DOCUMENT_MAINTENANCE_FAILED` and `PAUSE_DOCUMENT_WRITE_DID_NOT_LAND` are log text, not client codes.
- **Links on a joining member.** A joining member refuses links and page links without a
  `ClusterWideLinkAttemptLimiter` (`MISSING_SHARED_LINK_LIMITER`), and none ships in 0.8, so `list_sources` answers on one
  page there. Sharing the limiter across members (task 264) is deferred and is not part of this release.

## Added: free-text recognisers

A `FreeTextRecogniser` bean (`Set<DataClassification> recognise(String)`) lets you add your own recognition of free
text. The Spring bean collects them in `@Order`. In plain Java, `new SensitivePatternValidator()` still compiles and has
no recognisers; pass them with `new SensitivePatternValidator(new SensitiveDataScanner(), List.of(recogniser))`. A match
is the violation `FREE_TEXT_RECOGNISED`, a recogniser that throws is `FREE_TEXT_RECOGNISER_FAILED`, and the client sees
`VALIDATION_FAILED` at `$`. `DataClassification.FREE_TEXT` is new: the `DEFAULT` profile redacts it and `STRICT` removes
it.

## Added: keyed audit chain and segment size

All of this is off by default; configuration is in [the configuration reference](configuration.md) and
[Auditing](audit.md).

- **A keyed chain.** `dataprism.audit.hmac-key` (`key-id` plus exactly one of `environment-variable` or
  `provider-reference`) signs the chain with HMAC-SHA256. `dataprism.audit.retired-hmac-keys.<key-id>` holds
  verification-only keys, and the recorder never signs with one. The key must differ from the pseudonymisation key, and
  `CLUSTER_KEY_SHARED` and `NAME_KEY_SHARED` now also cover the audit keys.
- **`dataprism.audit.segment-max-size`** (at least 1MB, needs `directory`) caps a day's segment; files are named
  `audit-YYYY-MM-DD.NNNN.log` up to `.9999`.
- **Startup refusals.** `AUDIT_HMAC_REQUIRES_HASH_CHAINED`, `AUDIT_RETIRED_KEYS_REQUIRE_HMAC_KEY`,
  `MISSING_AUDIT_HMAC_KEY_REFERENCE`, `DUPLICATE_AUDIT_KEY_ID`, `AUDIT_HMAC_KEY_UNRESOLVED`, `AUDIT_HMAC_KEY_TOO_SHORT`,
  `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY`, `AUDIT_RECORDER_REPLACED_WITH_HMAC_KEY`, `AUDIT_SEGMENT_SIZE_REQUIRES_DIRECTORY`
  and `INVALID_AUDIT_SEGMENT_SIZE`.
- **The verifier.** `AuditChainVerifierCli` takes `--hmac-key-file <path>` (repeatable; a two-line file, the key id then
  the base64 key of at least 32 bytes) and `--allow-unkeyed-writers`, and exits with code 6 for a refused
  `accept-crash-line`. The packaged server's environment resolver reads a key as the UTF-8 bytes of the variable's text,
  so the key file must hold the base64 of those bytes.
- **Retention changes once a key is set.** Retention then verifies with the current and retired keys and
  `allowUnkeyedWriters=false`, so segments that were written unkeyed stop the purge with
  `AUDIT_RETENTION_CHAIN_UNVERIFIED`, and health goes `DOWN` once they expire. Switching a directory that holds unkeyed
  segments to a keyed chain leaves them unpurgeable by built-in retention. With no key set, purge is unchanged. With
  size-capped segments, a purge deletes a day whole, and finishes a partly deleted day only after its remainder verifies
  on its own. Each process start begins a new writer chain at GENESIS.

## Changed: key changes

[Rotating keys](key-rotation.md) has one procedure per key. What differs from what 0.7 operators may assume:

- **Link keys.** Every key in `dataprism.links.keys` verifies and only `key-id` seals. Rotation is two phases, then
  retirement.
- **Pseudonymisation key.** A change takes effect on the first call after restart for every scope. In a cluster the
  identity cache is not keyed by key id, so a rolling restart mixes pseudonyms for one subject.
- **The audit event does not record the pseudonymisation key id.** Tracing an old exposure needs your own dated record of
  key-id changes.
- **Cluster and name keys.** `next` and `previous` are read-only: nothing re-seals data or advances the sentinel, so a
  change is a planned full restart that loses the re-identification index, pending approvals, pauses and read counts.

## New: tools and capabilities

0.8.0 adds `list_sources`, `describe_source` and, with `dataprism.links.enabled: true`, `follow`, with the
capabilities `LIST_SOURCES`, `DESCRIBE_SOURCE` and `FOLLOW`, and an optional `targetSources` on `get_entity_context`
and `compare_entity_sources`. **Adding a tool grants nothing.** A role that held only its 0.7 capabilities is refused
the new tools with `TOOL_NOT_PERMITTED` until you add the capability to the role in
`dataprism.security-policy.roles`. See [the tool reference](tools.md#list_sources).

`list_sources` allows every source to every caller in 0.8. From 0.9 a source that no rule matches will be denied
(catalog OD-C5), and a source name that does not match `[a-z][a-z0-9-]{0,63}` will be refused. Startup warns once, with
a count, about names that would be refused.

A client must read a source `status` it does not recognise as "not answered". 0.8 adds `RESOLVER_FAILED`, only for a
translated `follow`.

## What else is different

- **Human approval is per page.** With approval on for `list_sources`, each page of a listing is its own call and
  needs its own approval.
- **The audit sink writes on an internal thread.** An interrupt no longer closes the channel and poisons the sink. A
  hung disk now holds the request that is writing to it, uninterruptibly. That is fail closed and accepted; monitor
  the audit storage.
- **A denial whose audit write fails answers `AUDIT_UNAVAILABLE`** on every denial path, with the metric
  `dataprism.audit.denial_unavailable`.
- **Trusted-network rule on the plain-Java entry points.** `PUBLIC_MEMBER_INTERFACE`, `MISSING_TRUSTED_NETWORK` and
  `INVALID_TRUSTED_RANGE` now also refuse there, not only in the starter. The rule is reachability beyond the host. See
  [Running multiple instances](multiple-instances.md#refusals).
- **New codes.** `PAGING_UNAVAILABLE` (a `list_sources` page whose next link could not be issued), `REQUEST_FAILED`, the
  three `CATALOG_*` audit reasons `CATALOG_LABELS_FAILED`, `CATALOG_DESCRIPTION_FAILED` and
  `CATALOG_DESCRIPTION_REFUSED`, and every code in the [startup refusal index](configuration.md#startup-refusal-index).
