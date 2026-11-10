---
title: Migrating to 0.8.0
description: What changes when upgrading from 0.7.x to 0.8.0, from limits and source models to adapters, configured JSON sources and the new tools.
---

# Migrating from 0.7.x to 0.8.0

This page lists the changes in 0.8.0 that can stop something working, and the new behaviour you will meet when you
upgrade. A section marked Breaking needs action from some deployments; the others say what is different. The
[CHANGELOG](changelog.md) lists the same changes in release order.

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

`OrchestrationOptions.defaults()` keeps the 0.7.1 behaviour: a catalog built from the adapters, every source visible
to every caller, no real names, no links. Use its `with...` methods to change one setting. The eight-argument
convenience constructor is unchanged.

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
