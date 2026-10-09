# Catalog: `list_sources`, `describe_source`, `describe_entity_model` and source targeting

Status: design for 0.8 (`list_sources`, `describe_source`, the adapter description, targeting) and 0.9 (`describe_entity_model`; the surface freezes at the 0.9.0 release). Internal (`docs/plan/design/`, D-070-12 (a)); not published.
Task 197. Companions: `sealed-links.md` and `key-resolvers.md` (task 191), whose names this document reuses and does not redefine. Nothing here is code, an SPI stub or a configuration property; every name is a proposal until the 0.8 or 0.9 task that builds it. Paging, batch and bulk follow are task 198's; where this design needs paging it says so and does not invent the mechanism.

Every product choice in this text is a recommendation, not a decision. Each is an open decision (OD-C1 and following, section 9) with options and a recommendation, and none is marked decided.

All examples use an invented neutral domain: a logistics estate with a parcel service, a depot service and a courier-roster service (the domain of the two companion documents). No real system, organisation or data model is meant.

## Scope of this document

The agent-led design (roadmap, "Design") starts with a catalog. An agent that has no application knowledge must be able to ask three questions without seeing data:

1. Which sources exist for me, and what do they hold? (`list_sources`)
2. What will one source give me, and how is each field protected? (`describe_source`)
3. For an entity type, how do the sources relate through the keys they accept and the fields that can start a link? (`describe_entity_model`)

A fourth need follows from them: once the estate is in the hundreds, `get_entity_context` cannot ask every source by default, so the caller must be able to choose sources, and the server must refuse to guess when it has not been told (D-RM-9).

Decisions this design inherits and does not reopen:

- D-RM-8 (a): a separate alias description, checked at startup, with a generated fallback.
- D-RM-9 (a): refuse with a clear code when no source is chosen and the estate is too large.
- D-RM-10 (a): real names only when the scope setting and the caller permission both allow; the audit always records real names plus the effective setting.
- D-RM-11 (a): role-to-source patterns in the security policy; unmatched sources denied (built in 0.9).
- D-RM-13 (a): `compare_entity_sources` is kept.
- D-RM-1, D-RM-4, D-RM-5, D-RM-14: generic tools only, links only for `chainable: true` fields, exact namespace match, one-step links built from declarations.
- D-070-1 to D-070-3 and D-070-13: per-source `{status, entity}` result, `REFUSED` omission, seven status codes, `REFUSED:<code>` audit values.
- From task 191, as recorded in its documents: the audit disposition grammar stays closed under shape C (`REFUSED:<code>`-shaped values only; OD-13 of `sealed-links.md`); `dataprism.links.enabled` is `false` by default; a source's input keys are declared on the adapter and are what this design specifies; `KEY_UNRESOLVED` is a per-source status; namespaces are free-form validated labels (`[a-z][a-z0-9-]{0,63}`).

Facts about the current code that this design rests on (checked 2026-10-09 on branch `plan/0.7.0`):

- `DataSourceAdapter<T>` has three methods: `sourceName()`, `responseType()`, `fetch(DataRequest)`. `DataRequest` carries `entityType`, `subjectId`, `parameters` and a call context. Nothing says which entity types an adapter serves or which key kinds it accepts.
- `SourceAliasing.nameFor` returns the real name when the caller (not the scope) holds `EXPOSE_SOURCE_NAMES`, and otherwise a scope-local token from `ValueTokenSource`, in the `ORGANISATION_IDENTITY` namespace, stable for the life of the scope. There is no scope-level setting today; D-RM-10 adds one (OD-C3).
- `Capability` already defines `DESCRIBE_ENTITY_MODEL` (in `Capability.KNOWN`) although no such tool exists. Tool access is a capability granted per role (`TOOL_NOT_PERMITTED`, `docs/tools.md` "Grant before call"), and a tool's presence in `tools/list` does not make it callable.
- `dataprism.sources.<name>` carries transport only (`base-url`, `timeout`, credential references; `docs/configuration.md`). A configured JSON source is declared in a separate `json-sources:` file with a strict-keys parser and a `fields:` catalogue; the source-name aliasing is the adapter's concern.
- Model protection is read from `@LlmExposedModel` types (`@SensitiveData`, `@NonSensitive`, `@InternalIdentifier`, `@SubjectIdentifier`) and from model descriptors, which may only tighten. `PrivacyAction` has seven values, of which three are implemented.
- `dataprism.limits.max-sources` is `8` by default and today silently skips a source beyond it (`SKIPPED_OVER_LIMIT`). The 0.8 fan-out rewrite removes the silent cut-off (roadmap, 0.8).
- `AuditEvent` has 25 fields; a source is audited through `sourceSystems` (real names) and `fieldDispositions` (`<source>:<refused>`-style keys with a closed value grammar).

## 1. Adapter description SPI

### What an adapter declares

An adapter describes itself in one immutable value, read once at startup and held in a startup registry (`SourceCatalog`). Nothing in the description is computed per request; no call to a source is made to answer `list_sources` or `describe_source`. That keeps the catalog free of I/O, free of timing differences between sources, and free of runtime state such as circuit-breaker status.

| Part | Meaning | Reaches the client as |
|---|---|---|
| `entityTypes` | The entity types the source can answer, opaque labels matching `[A-Za-z][A-Za-z0-9_-]{0,63}` (the same grammar as `dataprism.audit.entity-types`). Empty means "not declared": the source is treated as serving any entity type, as every source does today | `entityTypes` |
| `aliasDescription` | Neutral text shown when the source is shown under an alias. Must not identify the real system (section 2) | `description` when labels are aliases |
| `realDescription` | Text that may name the real system. Shown only when real names are in effect (section 3). Absent means the alias description is used | `description` when labels are real names |
| `inputKeys` | The key namespaces (`KeyNamespace` of `key-resolvers.md`) the source accepts as its subject key. This is the declaration the link builder reads (`key-resolvers.md` section 7) | `inputKeys` |
| `cost` | Advisory hint about the cost of one read: `LOW`, `MEDIUM`, `HIGH`. It does not change how the read budget is charged (OD-C12) | `cost` |
| `freshness` | Advisory hint about how current the data is: `LIVE`, `NEAR_LIVE`, `HOURLY`, `DAILY`, `PERIODIC` | `freshness` |
| Output model | The model the source returns. Not a new declaration: it is `responseType()`, and `describe_source` reads its protection from the same classification the scrubber applies (section 4) | `fields` |

Both hints are closed enums, not free text, so they cannot carry a system name or an instruction to the model. Only the two description texts are free text, and both are checked (section 2).

### Compatibility for existing adapters

Existing adapters must keep compiling and working. The proposal (OD-C1 compares it with two others) adds one default method to the SPI, in `data-prism-core`, package `...core.spi`:

```java
public interface DataSourceAdapter<T> {

    String sourceName();

    Class<T> responseType();

    T fetch(DataRequest request);

    /**
     * What this source holds, for the catalog. The default declares nothing, and the catalog
     * then generates a neutral description (section 2). Called once, at startup.
     */
    default SourceDescription description() {
        return SourceDescription.undeclared();
    }
}

public record SourceDescription(
        Set<String> entityTypes,
        String aliasDescription,       // nullable: absent means "generate"
        String realDescription,        // nullable: absent means "use the alias description"
        List<KeyNamespace> inputKeys,
        CostHint cost,                 // nullable
        FreshnessHint freshness) {     // nullable

    public static SourceDescription undeclared() { /* empty sets, null texts and hints */ }
}

public enum CostHint { LOW, MEDIUM, HIGH }

public enum FreshnessHint { LIVE, NEAR_LIVE, HOURLY, DAILY, PERIODIC }
```

An adapter that does not override `description()` behaves exactly as today: it is listed with a generated description, no declared entity types (so it serves any), no input keys (so it is never the target of a link and can only be chosen by a human-supplied subject key), and no hints. `KeyNamespace` is declared as a nested record of `KeyResolver` in `key-resolvers.md`; it is used here by an SPI that is not a resolver, so 0.8 should hoist it to a top-level type in `...core.spi` (a note for the 0.8 build, not a decision).

How an adapter that declares several input namespaces learns which one it was handed is not defined by task 191 either. `DataRequest` has only `subjectId`. OD-C13 proposes an additive field.

### The same declaration for configured JSON sources

A configured JSON source gains an optional `description:` block inside its entry in the `json-sources:` file. It has the strict-keys parsing the file already has, an unknown key refuses startup with the existing refusal for an unknown key, and the keys use the kebab-case of the transport keys beside them (`base-url`, `subject-json-path`). Input keys are a plain list of namespace labels.

```yaml
json-sources:
  parcel-service:
    base-url: https://parcel.example.internal
    path: /parcels/{subject}
    timeout: 2s
    model-version: parcel-v1
    subject-json-path: parcel
    description:
      entity-types: [PARCEL]
      alias-description: "Records of items in transit, one per item, with their current state."
      real-description: "The parcel tracking service. One record per parcel reference."
      input-keys: [parcel-ref]
      cost: LOW
      freshness: NEAR_LIVE
    fields:
      parcel:
        identifier: true
        chainable: true
        keyNamespace: parcel-ref
      recipientName:
        classifications: PII
        namespace: PERSON_NAME
        action: SYNTHESIZE
      state:
        nonSensitive: "Enumerated lifecycle state; no free text"
```

The `fields:` catalogue is the output model of a configured source, so `describe_source` reads it directly. `chainable` and `keyNamespace` in a catalogue entry are the spellings task 191 proposed for model descriptors, and the same rules apply: valid only on a field already classified as an identifier, with a namespace; startup refuses otherwise with `INVALID_CHAINABLE_FIELD`. An entry under `description:` for a source with no `description:` block is simply the undeclared case.

A Java-first source has no YAML equivalent. `dataprism.sources.<name>` stays transport-only (`docs/configuration.md`: "not a generic request template"); its description comes from the adapter's `description()` and nowhere else. Moving a description into configuration would let a file change what a reviewed adapter says about itself, which is the opposite of the project's reviewed-extension rule.

### Startup registry

At startup `SourceCatalog` is built once from every `DataSourceAdapter` bean and every configured JSON source: description validated (section 2), model classification read (section 4), labels not yet computed (they are per scope; section 3). It is immutable, holds no personal data, and is not stored in Hazelcast (boundary 6: nothing about it varies by scope or needs to be shared). A duplicate `sourceName()` already refuses startup today; the catalog relies on that and adds no second check.

## 2. Startup checks on description text, and the generated fallback

### Why the text is a risk

Both descriptions are written by the operator and read by a model. An alias description is shown in place of a name that was withheld on purpose (`docs/pack.md` §32, `SourceAliasing`), so a description that says "the central billing database of the northern region" defeats the alias. The roadmap names this risk ("Description text can leak real system names. The startup check is a guard rail; the operator is the reviewer"). The checks below reduce accidents. They do not prove a text is safe, and the documentation says so in the same words.

### What is checked

Checks run in this order at startup, on the alias description (all checks) and the real description (structure only, rules 1 to 3). The first failure refuses startup. The message names the source and the rule, never the text.

| # | Check | Applies to | Refusal |
|---|---|---|---|
| 1 | Present text is not blank, is at most 240 characters, and is a single line | both | `INVALID_SOURCE_DESCRIPTION` |
| 2 | Only printable characters: letters, digits, space and `. , ; : ( ) - _ / ' "`. No control characters, no angle brackets, braces, square brackets, backticks or backslashes, no `@` | both | `INVALID_SOURCE_DESCRIPTION` |
| 3 | Passes the same key-shape scan that response validation runs (D-070-7), so a description cannot carry an identifier-shaped value, a URL or a host name | both | `INVALID_SOURCE_DESCRIPTION` |
| 4 | Entity types and input namespaces match their grammars; no duplicates; `inputKeys` has at most 8 entries | the whole description | `INVALID_SOURCE_DESCRIPTION` |
| 5 | Does not contain, after lower-casing and removing every character that is not a letter or digit: the source's real name, the simple name of its adapter class, the host of its configured `base-url`, or the real name of any other source in the estate | alias description | `SOURCE_DESCRIPTION_LEAKS_NAME` |
| 6 | The alias description is not identical to the real description | alias description, when both are present | `INVALID_SOURCE_DESCRIPTION` |

Notes on the rules:

- Rule 2 exists because descriptions are model-visible text. Text that looks like markup or an instruction is an injection surface that the operator-authored channel should not widen. Letters, digits and plain punctuation are enough for a neutral sentence.
- Rule 5 is on whole normalised names, not on their parts. Splitting `parcel-service` into `parcel` and `service` would refuse every description that says "service". The cost is that a text naming only half of a name passes. This is accepted for a guard rail and listed in the residual risk; the operator reviews the text.
- Rule 6 catches the accident of copying one text into both fields.
- Two sources with the same alias description are allowed (a regional estate may have a hundred depots that read alike) and counted in the metric of section 3.

The real description is checked for structure only because it is allowed to name the system. It is shown only under real names (section 3), and the audit already carries the real name.

### Generated fallback

A source with no alias description gets a text generated from what it declared, never from its name, class or location:

- with entity types and input keys: `Holds <T1>, <T2> records; accepts <K1>, <K2> keys.` for example `Holds PARCEL records; accepts parcel-ref keys.`;
- with entity types only: `Holds <T1>, <T2> records.`;
- with neither (an undeclared adapter): `A data source.`

The fallback is built from values that rules 4 and the namespace grammar already constrain, so it needs no further check. A source whose `realDescription` is absent is shown with its alias description (or the fallback) under real names, since a name in the label already identifies it.

An undeclared or fallback-described source works but is hard for an agent to choose among, in the same way that 300 sources called by random aliases are. The number of sources using the fallback is a startup log line (the count and the real names, which are operator-side data) and a gauge metric `dataprism.catalog.descriptions.generated`. It is not a refusal: D-RM-8 chose a fallback. OD-C14 asks whether a deployment can opt into refusing it.

### Residual risk

A description can still identify a system by an indirect phrase, by a vendor-neutral but unique detail, or by a half name. The check cannot know which phrases are unique in an estate. The operator, who knows the real systems, is the reviewer, and the pilot deployment is where this is evaluated.

## 3. `list_sources`

### Purpose and access

`list_sources` is the root resource of the agent-led design. It returns the sources the caller may use, each with a label, a neutral description, the entity types it serves and the key namespaces it accepts. It returns no data and calls no source.

It requires the capability `LIST_SOURCES`, granted per role like every tool (`TOOL_NOT_PERMITTED` otherwise). Adding the tool does not grant it; an upgrade note belongs in the migration guide, as for `compare_entity_sources` in 0.2.0.

### Labels: alias or real name (D-RM-10)

A label is what the caller sees and passes back. Whether it is an alias or a real name is decided per request, once, by the effective setting:

> effective setting = REAL when the scope setting permits real names AND the caller holds `EXPOSE_SOURCE_NAMES`; otherwise ALIAS.

Both conditions are needed, and either alone is not enough. Today only the caller condition exists (`SourceAliasing`). The scope setting is new; where it lives is OD-C3. The effective setting is a property of the request, computed from the session, never from an argument (rule 4), and it applies to the whole response: a list never mixes alias and real labels, and the description shown is the one that matches the label kind (section 1), so a real description is never shown beside an alias.

Aliases come from `SourceAliasing.nameFor`, unchanged, so the label for a source in `list_sources`, `describe_source`, `get_entity_context` and `_links.target` is the same string for the life of a scope. Resolving a label back to a source is done per request by labelling every registered source for the scope and matching, which is stateless and needs no Hazelcast entry. Two consequences:

- A label of the wrong kind never resolves. If aliases are in effect, a real name passed as a label fails like any unknown label. Otherwise a caller could test whether a real name exists by whether the call succeeded.
- If two sources produce the same label in one scope (an alias collision, improbable but possible) the call is refused with `SOURCE_LABEL_COLLISION` rather than listing an ambiguous label. The audit carries both real names in `sourceSystems`; the client learns only the code.

The audit always records real names plus the effective setting (D-RM-10). Where the setting is recorded is a conflict with the closed disposition grammar and is OD-C2.

### Authorisation (D-RM-11)

Which sources a caller may see is decided by a single predicate, `SourceVisibility`: `permitted(caller, source)`. In 0.8 there is no per-source policy yet, so its behaviour before 0.9 is OD-C5. From 0.9 it implements D-RM-11: role-to-source patterns in `dataprism.security-policy`, and an unmatched source is denied. `list_sources`, `describe_source`, `describe_entity_model`, `get_entity_context` targeting and `follow` all call the same predicate, so a source that is not listed can be neither described, targeted nor linked to. Whether a not-permitted source is hidden from the list or listed as not permitted is OD-C4 (recommendation: hidden).

### Input schema

Scope, principal, purpose and case come from the session (rule 4); the reserved argument names are stripped and audited as today (`ReservedArguments`). Paging arguments are task 198's and are optional, so adding them later is additive. At the 0.9 freeze they belong in this schema; until then they are absent and the schema below is complete for 0.8 (OD-C16 asks whether 0.8 needs them).

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "list_sources input",
  "type": "object",
  "properties": {
    "entityType": {
      "description": "Only sources that serve this entity type, plus sources that declare none.",
      "type": "string",
      "pattern": "^[A-Za-z][A-Za-z0-9_-]{0,63}$"
    },
    "keyNamespace": {
      "description": "Only sources that accept a key in this namespace.",
      "type": "string",
      "pattern": "^[a-z][a-z0-9-]{0,63}$"
    }
  },
  "additionalProperties": false
}
```

Both filters match exactly (no case folding, no prefix), as namespaces do everywhere (D-RM-5). A filter that matches nothing returns an empty `sources` array, not a refusal: the filter values are the caller's own strings, and an empty answer discloses nothing.

### Output schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "list_sources output",
  "type": "object",
  "properties": {
    "correlationId": { "type": "string" },
    "labels": {
      "description": "Whether the source labels below are aliases or real names (the effective setting).",
      "enum": ["ALIAS", "REAL"]
    },
    "sources": {
      "type": "array",
      "items": { "$ref": "#/$defs/entry" }
    }
  },
  "required": ["correlationId", "labels", "sources"],
  "additionalProperties": false,
  "$defs": {
    "keyNamespace": { "type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$" },
    "entry": {
      "type": "object",
      "properties": {
        "source": { "type": "string", "minLength": 1, "maxLength": 128 },
        "description": { "type": "string", "minLength": 1, "maxLength": 240 },
        "entityTypes": {
          "type": "array",
          "items": { "type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_-]{0,63}$" },
          "uniqueItems": true
        },
        "inputKeys": {
          "type": "array",
          "items": { "$ref": "#/$defs/keyNamespace" },
          "uniqueItems": true,
          "maxItems": 8
        },
        "cost": { "enum": ["LOW", "MEDIUM", "HIGH"] },
        "freshness": { "enum": ["LIVE", "NEAR_LIVE", "HOURLY", "DAILY", "PERIODIC"] }
      },
      "required": ["source", "description", "inputKeys"],
      "additionalProperties": false
    }
  }
}
```

Rules:

1. `entityTypes` is omitted for a source that declared none (it serves any). `cost` and `freshness` are omitted when not declared. Nothing is returned as `null`.
2. Entries are in ascending order of `source` (byte order of the label), so the order is the same on every run (task 179's rule) and, for aliases, reveals nothing about the order in which sources were registered.
3. The response contains no count of sources the caller cannot see, no "N more" marker for hidden sources, and nothing about sources outside the entity-type filter beyond their absence.
4. Size: a typical entry is 250 to 400 bytes. A hundred sources are about 35 KB and five hundred about 200 KB, which is a poor fit for a model context. Paging (task 198) or filters are needed at that size; see OD-C16.

An example, as an investigator whose role holds `LIST_SOURCES`, with aliases in effect:

<!-- example-of: list_sources output -->
```json
{
  "correlationId": "c-4f2a9d",
  "labels": "ALIAS",
  "sources": [
    {
      "source": "ORG-6M1ZQ0AB",
      "description": "Records of items in transit, one per item, with their current state.",
      "entityTypes": ["PARCEL"],
      "inputKeys": ["parcel-ref"],
      "cost": "LOW",
      "freshness": "NEAR_LIVE"
    },
    {
      "source": "ORG-8RK2W9TE",
      "description": "Holds PARCEL, DEPOT records; accepts depot-code, parcel-ref keys.",
      "entityTypes": ["DEPOT", "PARCEL"],
      "inputKeys": ["depot-code", "parcel-ref"]
    },
    {
      "source": "ORG-J3T7LP5C",
      "description": "Planned delivery runs, with the stops of each run.",
      "entityTypes": ["PARCEL"],
      "inputKeys": ["route-code"],
      "freshness": "DAILY"
    }
  ]
}
```

The second entry is a source with no alias description, shown with the generated fallback.

### Audit

One event per call, as every tool:

| Field | Value |
|---|---|
| `tool` | `list_sources` |
| `entityType` | the filter value, or empty |
| `subjectPseudonym` | empty (there is no subject) |
| `policyDecision` | `ALLOW`, or `DENY:<code>` (for example `DENY:TOOL_NOT_PERMITTED`) |
| `sourceSystems` | the real names of the sources returned (size handling in OD-C11) |
| `fieldDispositions` | none on success. On refusal, the audit-only reason under shape C (section 6) |
| effective setting | see OD-C2 |

The `keyNamespace` filter is a label chosen by the caller and carries no personal data, but it is not audited as a field of its own, since the record has no field for it and shape C adds none; it is recoverable from the tool-call log of the client, not from the audit.

## 4. `describe_source`

### Purpose

`describe_source` returns the output shape of one source and how each field is protected, without data. It exists so the agent does not guess at redacted or removed fields (`docs/design-review.md` §B5), and so it can see which fields start links.

It requires `DESCRIBE_SOURCE`. It takes a label exactly as returned by `list_sources`.

### The single source of truth

The protection shown is computed from the same classification and the same profile the scrubber applies, not from a second reading of the annotations. If it were a second reading, a descriptor that tightens a field would leave the description saying the field is passed through. The 0.8 build derives the description from the scrub plan for the model (annotations, descriptors, profile) and a conformance test (section 8, item 5) proves that every path a scrubbed response can contain is described, with the protection it was scrubbed under.

### Per-field protection vocabulary

A closed set, mapped from the effective `PrivacyAction` after profile and descriptor tightening:

| `protection` | Effective action | What the agent sees |
|---|---|---|
| `VERBATIM` | `PASS_THROUGH` | the value, unchanged (only a field asserted non-sensitive, or admitted by server policy) |
| `SYNTHETIC` | `SYNTHESIZE` | a deterministic synthetic value, the same for the same input across sources in the scope when the value namespaces agree |
| `TOKEN` | `TOKENIZE`, `HASH` | an opaque, stable, scope-local value; comparable within the scope, meaningless outside it |
| `MARKER` | `REDACT` | a fixed marker; the field is present, the value is not |
| `BUCKET` | `GENERALIZE` | a coarser bucket of the value |
| `ABSENT` | `REMOVE` | no value and no field in responses |

Fields whose protection is `ABSENT` are not listed, with one exception: a chainable field is always listed, with `ABSENT` when it is removed, because a link names it (`field` in `_links`) and the agent must be able to find it here. OD-C9 compares listing every removed field with omitting them.

### Chainable markers

A field is described with `chainable: true` and its `keyNamespace` exactly as task 191 declared them (`key-resolvers.md` section 7). They are shown only when `dataprism.links.enabled` is `true`; with links off the flag is inert and is omitted, so the description never advertises a capability the server does not offer. `chainable` is metadata only; it changes no disposition (`key-resolvers.md`, "What `chainable: true` means").

### Value namespace

Where `protection` is `SYNTHETIC` or `TOKEN`, the field also carries `valueNamespace`, the `PrivacyNamespace` name (for example `PERSON_NAME`). It tells the agent that two fields with the same `valueNamespace` in two sources are comparable, which is what the pseudonym collapse makes useful (`docs/tools.md`). It is a different thing from `keyNamespace` and the two names are never mixed (`key-resolvers.md`, "Terms"). OD-C10 asks whether to show it.

### What is never returned

- the data classification (the sensitive-category names), because they describe what kind of personal data a field would have held and add nothing the agent can act on;
- any value, example, sample, count, range or record statistic;
- the real source name, unless real labels are in effect; the adapter class name; the package of the model; the model class name;
- `base-url`, `path`, timeouts, credential or key references, descriptor or configuration file locations, `model-version`;
- the resolver names, and whether a resolver is configured for a namespace (that is relation data, section 5);
- runtime state: circuit status, last error, last call, latency;
- anything about a source the caller may not see. An unknown label and a label of a source the caller is not permitted to use are the same refusal.

### Input schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "describe_source input",
  "type": "object",
  "properties": {
    "source": {
      "description": "A source label exactly as returned by list_sources.",
      "type": "string",
      "minLength": 1,
      "maxLength": 128
    }
  },
  "required": ["source"],
  "additionalProperties": false
}
```

### Output schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "describe_source output",
  "type": "object",
  "properties": {
    "correlationId": { "type": "string" },
    "labels": { "enum": ["ALIAS", "REAL"] },
    "source": { "type": "string", "minLength": 1, "maxLength": 128 },
    "description": { "type": "string", "minLength": 1, "maxLength": 240 },
    "entityTypes": {
      "type": "array",
      "items": { "type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_-]{0,63}$" },
      "uniqueItems": true
    },
    "inputKeys": {
      "type": "array",
      "items": { "$ref": "#/$defs/keyNamespace" },
      "uniqueItems": true,
      "maxItems": 8
    },
    "cost": { "enum": ["LOW", "MEDIUM", "HIGH"] },
    "freshness": { "enum": ["LIVE", "NEAR_LIVE", "HOURLY", "DAILY", "PERIODIC"] },
    "undeclared": {
      "description": "What happens to a property the model does not declare: the response is refused, the property is renamed to a placeholder, or it is dropped.",
      "enum": ["REFUSE", "RENAME", "DROP"]
    },
    "fields": {
      "type": "array",
      "items": { "$ref": "#/$defs/field" }
    }
  },
  "required": ["correlationId", "labels", "source", "description", "inputKeys", "undeclared", "fields"],
  "additionalProperties": false,
  "$defs": {
    "keyNamespace": { "type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$" },
    "field": {
      "type": "object",
      "properties": {
        "path": {
          "description": "The field as it appears in a response; an array element is written with [].",
          "type": "string",
          "pattern": "^[A-Za-z_][A-Za-z0-9_]*(\\[\\])?(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\])?)*$"
        },
        "protection": { "enum": ["VERBATIM", "SYNTHETIC", "TOKEN", "MARKER", "BUCKET", "ABSENT"] },
        "valueNamespace": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
        "chainable": { "const": true },
        "keyNamespace": { "$ref": "#/$defs/keyNamespace" }
      },
      "required": ["path", "protection"],
      "dependentRequired": { "chainable": ["keyNamespace"], "keyNamespace": ["chainable"] },
      "additionalProperties": false
    }
  }
}
```

Rules:

1. `fields` is in ascending order of `path`. `entityTypes`, `cost` and `freshness` are omitted when undeclared, as in `list_sources`.
2. `valueNamespace` appears only with `SYNTHETIC` or `TOKEN`. `chainable` and `keyNamespace` appear together or not at all.
3. A source with undeclared properties under a profile that renames them (`<undeclared-N>`) reports `undeclared: RENAME`; under the shipped profiles, which refuse an unclassified field, `REFUSE`.
4. Unknown label, label of the wrong kind, a source the caller is not permitted to use, and any failure to compute the description are one refusal: `SOURCE_NOT_AVAILABLE`, with no detail (fail closed, rule 2). The reasons go to the audit only (section 6).

Example:

<!-- example-of: describe_source output -->
```json
{
  "correlationId": "c-7be012",
  "labels": "ALIAS",
  "source": "ORG-6M1ZQ0AB",
  "description": "Records of items in transit, one per item, with their current state.",
  "entityTypes": ["PARCEL"],
  "inputKeys": ["parcel-ref"],
  "cost": "LOW",
  "freshness": "NEAR_LIVE",
  "undeclared": "REFUSE",
  "fields": [
    { "path": "parcel", "protection": "ABSENT", "chainable": true, "keyNamespace": "parcel-ref" },
    { "path": "recipientEmail", "protection": "MARKER" },
    { "path": "recipientName", "protection": "SYNTHETIC", "valueNamespace": "PERSON_NAME" },
    { "path": "state", "protection": "VERBATIM" }
  ]
}
```

### Audit

`tool` is `describe_source`; `entityType` and `subjectPseudonym` empty; `sourceSystems` holds the real name of the described source (empty on a refusal before it was resolved); `policyDecision` is `ALLOW` or `DENY:<code>`. The requested label is a client string and is not recorded. Failure reasons are in section 6.

## 5. `describe_entity_model` (0.9)

### Purpose

`describe_entity_model` answers a question neither other tool can: given an entity type, which key namespaces tie the sources together? `describe_source` is per source and `list_sources` is a flat list; neither shows that a field of one source is the key another source accepts. The tool shows that and nothing about values.

It is also where `docs/design-review.md` §B5 ends up. B5 asked for the shape of what the model can expect, "field names, namespaces, which fields are pseudonymised". Per-source fields are `describe_source`. A merged field list across sources is not offered, because results per source are never merged (D-RM-1, roadmap). The tool offers what is genuinely entity-level: key relations, and which value namespaces are comparable across sources (OD-C15).

It requires the capability `DESCRIBE_ENTITY_MODEL`, which already exists in `Capability.KNOWN`. It is built in 0.9, after links and key resolvers exist in 0.8, because relations are derived from them.

### How relations are derived

Only from declarations, by the rule the link builder uses (`key-resolvers.md` section 7), with no call to a source or a resolver, no data and no scope-dependent state other than the effective label kind and the caller's permitted sources:

1. Take the entity type `E`. The sources are those that serve `E` (or declare none) and that the caller may use.
2. For each such source `S` and each chainable field `F` of `S` with key namespace `N`: `F` is a producer of `N`.
3. A source `T` (of any entity type the caller may use; a parcel record can lead to a depot) that declares an input key `N` accepts `N`: a direct relation.
4. For each resolver `R` with `from() = N`, a source `T` that accepts `R.to() = M` is reached as `M`: a translated relation. Never two resolvers in a row; never a guess (D-RM-5).
5. When both 3 and 4 hold for the same `T`, only the direct relation is shown, as for links (OD-R7 of `key-resolvers.md`).

The output is grouped by namespace, not listed as source-to-source pairs. With `n` sources a pair list can grow as `n` squared (a hundred sources that all accept one namespace make ten thousand pairs); a namespace-centric view grows as `n`. The agent joins producer and acceptor through the namespace, which is how the link builder joins them.

With `dataprism.links.enabled` set to `false` the tool omits `producedBy` and `reachesAs` and reports only `acceptedBy`, since nothing can start or follow a link; OD-C17 asks whether the tool should then exist at all.

### How it avoids leaking estate shape beyond `list_sources`

1. **Only permitted sources.** Every source named is one `list_sources` would list for the same caller. The same predicate (`SourceVisibility`) is used; there is no separate list to drift.
2. **The same labels.** A source is named by its label in the current effective setting, never by a real name under alias, and never by a resolver name. A resolver is internal: the client sees that a namespace is reachable as another one, never which bean does it.
3. **Nothing beyond declarations.** No counts, no data, no "also reachable by sources you may not use", no number of omitted sources or relations. A relation to a source the caller cannot use is absent, not marked.
4. **Field names only as `describe_source` already shows them.** `producedBy.field` is the path of a chainable field, which `describe_source` lists for the same source and caller.
5. **One refusal for an unknown or empty model.** An entity type that no permitted source serves and an unknown entity type are both `ENTITY_MODEL_NOT_AVAILABLE`. A client cannot learn from the refusal whether the entity type exists in the estate.
6. **Translated relations are the one new fact.** That a resolver connects namespace `N` to `M` is not shown by `list_sources` or `describe_source`. It is shown today to a holder of `_links` (`toNamespace`), so the new information is bounded by what a link already tells, and only for permitted sources. The cost is stated in the threat note below, and OD-C15 and OD-C17 let the owner narrow it.

Threat note: an agent steered by injected text that calls `describe_entity_model` learns the namespaces and which of the sources it already sees are connected. It learns no key, no value and no source it was not shown, and the sealed link remains the only thing that carries authority (`sealed-links.md`).

### Input schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "describe_entity_model input",
  "type": "object",
  "properties": {
    "entityType": {
      "type": "string",
      "pattern": "^[A-Za-z][A-Za-z0-9_-]{0,63}$"
    }
  },
  "required": ["entityType"],
  "additionalProperties": false
}
```

### Output schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "describe_entity_model output",
  "type": "object",
  "properties": {
    "correlationId": { "type": "string" },
    "labels": { "enum": ["ALIAS", "REAL"] },
    "entityType": { "type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_-]{0,63}$" },
    "sources": {
      "description": "Labels of the permitted sources that serve this entity type.",
      "type": "array",
      "items": { "$ref": "#/$defs/label" },
      "uniqueItems": true
    },
    "keyNamespaces": {
      "type": "array",
      "items": { "$ref": "#/$defs/keyRelation" }
    },
    "comparableValues": {
      "type": "array",
      "items": { "$ref": "#/$defs/comparable" }
    }
  },
  "required": ["correlationId", "labels", "entityType", "sources", "keyNamespaces"],
  "additionalProperties": false,
  "$defs": {
    "label": { "type": "string", "minLength": 1, "maxLength": 128 },
    "keyNamespace": { "type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$" },
    "sourceField": {
      "type": "object",
      "properties": {
        "source": { "$ref": "#/$defs/label" },
        "field": { "type": "string", "minLength": 1, "maxLength": 256 }
      },
      "required": ["source", "field"],
      "additionalProperties": false
    },
    "keyRelation": {
      "type": "object",
      "properties": {
        "keyNamespace": { "$ref": "#/$defs/keyNamespace" },
        "producedBy": {
          "description": "Chainable fields, of sources serving the entity type, whose value is a key in this namespace.",
          "type": "array",
          "items": { "$ref": "#/$defs/sourceField" }
        },
        "acceptedBy": {
          "description": "Sources that accept a key in this namespace directly.",
          "type": "array",
          "items": { "$ref": "#/$defs/label" },
          "uniqueItems": true
        },
        "reachesAs": {
          "description": "Namespaces this one can be translated into, and the sources that accept each.",
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "keyNamespace": { "$ref": "#/$defs/keyNamespace" },
              "acceptedBy": {
                "type": "array",
                "items": { "$ref": "#/$defs/label" },
                "uniqueItems": true,
                "minItems": 1
              }
            },
            "required": ["keyNamespace", "acceptedBy"],
            "additionalProperties": false
          }
        }
      },
      "required": ["keyNamespace", "acceptedBy"],
      "additionalProperties": false
    },
    "comparable": {
      "type": "object",
      "properties": {
        "valueNamespace": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
        "fields": {
          "type": "array",
          "items": { "$ref": "#/$defs/sourceField" },
          "minItems": 2
        }
      },
      "required": ["valueNamespace", "fields"],
      "additionalProperties": false
    }
  }
}
```

Rules:

1. `keyNamespaces` is ascending by `keyNamespace`; `producedBy` ascending by `source` then `field`; label arrays ascending. Nothing is ordered by registration, so order discloses nothing.
2. `producedBy` and `reachesAs` are omitted when links are disabled, and a namespace with no producer, no acceptor and no translation is not listed.
3. `comparableValues` lists a value namespace only when at least two permitted sources serving the entity type have a `SYNTHETIC` or `TOKEN` field in it, and only the fields themselves. It is optional (OD-C15).
4. Response size is bounded by the number of permitted sources and chainable fields, not by their product. Paging of `producedBy` and `acceptedBy` for very large estates is task 198's to specify if it is needed at the 500-source target.

Example, for the three-service estate:

<!-- example-of: describe_entity_model output -->
```json
{
  "correlationId": "c-91a3d0",
  "labels": "ALIAS",
  "entityType": "PARCEL",
  "sources": ["ORG-6M1ZQ0AB", "ORG-8RK2W9TE", "ORG-J3T7LP5C"],
  "keyNamespaces": [
    {
      "keyNamespace": "parcel-ref",
      "producedBy": [{ "source": "ORG-6M1ZQ0AB", "field": "parcel" }],
      "acceptedBy": ["ORG-6M1ZQ0AB", "ORG-8RK2W9TE"],
      "reachesAs": [{ "keyNamespace": "route-code", "acceptedBy": ["ORG-J3T7LP5C"] }]
    },
    {
      "keyNamespace": "route-code",
      "acceptedBy": ["ORG-J3T7LP5C"]
    }
  ]
}
```

### Audit

`tool` is `describe_entity_model`; `entityType` is the requested type; `sourceSystems` holds the real names of the sources named in the response; `policyDecision` is `ALLOW` or `DENY:<code>`.

## 6. Targeting chosen sources on `get_entity_context`, and the D-RM-9 refusal

### The argument

`get_entity_context` and `compare_entity_sources` each gain one optional argument, `targetSources` (name proposed; it avoids the word `sources`, which is already the name of the response object of `get_entity_context`). It is an array of labels exactly as `list_sources` returned them, in the current effective setting. OD-C6 compares it with other spellings and asks whether `compare_entity_sources` takes it too (recommendation: yes, because D-RM-13 keeps that tool and an unselected comparison across hundreds of sources has the same problem).

Input schema change (the new property; `entityType` and `subjectId` are unchanged, as are the reserved argument rules):

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "get_entity_context input",
  "type": "object",
  "properties": {
    "entityType": { "type": "string" },
    "subjectId": { "type": "string" },
    "targetSources": {
      "description": "Source labels as returned by list_sources. When absent, every permitted source is asked, and the call is refused if there are too many.",
      "type": "array",
      "items": { "type": "string", "minLength": 1, "maxLength": 128 },
      "minItems": 1,
      "maxItems": 64,
      "uniqueItems": true
    }
  },
  "required": ["entityType", "subjectId"],
  "additionalProperties": false
}
```

The `maxItems` shown, 64, is a schema ceiling to keep the argument bounded; the effective limit is `dataprism.limits.max-sources` (rule 3). The schema ceiling is generated from configuration, as the `follow` schema is in `sealed-links.md`, so the served value tracks the setting.

### Behaviour

1. **No `targetSources`.** The server counts the sources that serve `entityType` (or declare none) and that the caller may use. If the count is at most `dataprism.catalog.unselected-source-limit`, every one is asked, exactly as today: existing clients and small estates see no change. If the count is above it, the call is refused with `SOURCE_SELECTION_REQUIRED` (D-RM-9). The client sees the bare code, as for every refusal, and learns the next step from the name; it does not learn the count or the limit.
2. **With `targetSources`.** Each label is resolved by the section 3 mechanism. Every label must resolve to a permitted source that serves the entity type. If any label does not, the call is refused with `SOURCE_NOT_AVAILABLE` and no source is called. An unknown label, a label of the wrong kind, a source the caller may not use and a source that does not serve the entity type are the same refusal, so the call cannot be used to probe the estate. Refusing the whole call, not dropping the bad label, is the fail-closed reading; a partial answer would hide a typo.
3. **Too many chosen.** More labels than `dataprism.limits.max-sources` is refused with `SOURCE_SELECTION_TOO_LARGE`, not silently cut: the 0.8 fan-out rewrite removes the silent cut-off at `maxSources`. Whether the cap is `max-sources` or something else is OD-C8.
4. The chosen sources then run through the unchanged pipeline: authorisation, read budget, deadlines, scrub, validate, per-source status (D-070-1). A chosen source cannot bypass the privacy engine; targeting selects which adapters are called and changes nothing about what happens to their output (rule 1).
5. Where the call is refused before any source is called, the audit decision is `DENY:<code>`, `sourceSystems` is empty, and, as in `sealed-links.md`, the real reason is an audit-only code under shape C:

| Refusal the client sees | Audit-only reason (disposition `catalog`, value `REFUSED:<reason>`) |
|---|---|
| `SOURCE_SELECTION_REQUIRED` | none needed: the code is already specific, and the decision is `DENY:SOURCE_SELECTION_REQUIRED` |
| `SOURCE_SELECTION_TOO_LARGE` | none needed, as above |
| `SOURCE_NOT_AVAILABLE` | `CATALOG_UNKNOWN_SOURCE`, `CATALOG_SOURCE_NOT_PERMITTED` or `CATALOG_ENTITY_TYPE_MISMATCH` |
| `ENTITY_MODEL_NOT_AVAILABLE` | `CATALOG_UNKNOWN_ENTITY_TYPE` or `CATALOG_NO_PERMITTED_SOURCE` |
| `SOURCE_LABEL_COLLISION` | none needed |

The disposition key is `catalog`, the value has the `REFUSED:<code>` shape that D-070-13 admits, and the code is sanitised as D-070-13 requires. The label the client sent is not recorded (an untrusted client string), so the audit says what kind of failure it was and not which string. Nothing here widens the grammar; that is the point of shape C. Where the effective setting would be recorded is the exception (OD-C2).

### The estate-size limit

A new configuration key, `dataprism.catalog.unselected-source-limit`, a positive integer. Its default and what it counts are OD-C7 (recommendation: default `8`, the current `dataprism.limits.max-sources` default, counting permitted sources that serve the entity type). A zero, negative or non-integer value refuses startup with `INVALID_CATALOG_SETTING`, in the style of `INVALID_REQUEST_LIMITS` (the message names the property, not the value).

### Schema change summary

| Tool | Change | Compatible |
|---|---|---|
| `get_entity_context` | optional `targetSources` | Yes: absent behaves as today while the estate is at or below the limit |
| `compare_entity_sources` | the same optional `targetSources` (OD-C6) | Yes, on the same condition |
| `follow` | none | Targeting does not apply to a link, which names its target |

## 7. Boundaries

1. **Nothing reaches `mcp` without passing the privacy engine.** The catalog is metadata and contains no source data. The operator-authored description texts reach the model without passing the scrubber, so they are held to the same leak validators as any response: the key-shape scan at startup (rule 3 of section 2), and the response validators on `list_sources`, `describe_source` and `describe_entity_model` output at request time. `SourceCatalog` is built in `orchestration` from `core` types; `mcp` receives it through a read-only interface. A new architecture rule forbids `mcp` and `connectors` classes from depending on adapter classes, as today for fetching. Targeting selects adapters but does not call one directly: the call is still the orchestrator's.
2. **Boundary 5 (re-identification).** Label resolution goes label to source and never touches `ScopeIdentityIndex.subjectFor`. An alias is derived from a source name, not from a subject, and is not stored. None of these tools can return a real subject or key.
3. **Boundary 6 (Hazelcast).** The catalog is immutable per process and is not stored in Hazelcast. Labels are recomputed per request. The only shared state is the existing read budget.
4. **Boundary 7 (no sensitive value in log, metric, exception, audit).** Description text is not logged, not put in an exception message and not put in a metric label; a refusal names the source and the rule. The startup fallback log line carries real source names, which are operator-side data and not personal data, and never the description text. Metrics use closed labels only. The labels the client sends in `targetSources` and `source` are not recorded.
5. **Boundary 8 (the core carries no business domain).** `SourceDescription`, `CostHint` and `FreshnessHint` name no domain concept. Entity types, namespaces and descriptions are opaque operator-supplied strings; the generated fallback uses only those strings and fixed words. The parcel, depot and courier-roster examples live in this document and in tests, and never in `core` (`ArchitectureTest` today does not scan `core` for domain names; the 0.8 build adds a check that `core` contains none of a small deny-list of domain words, noted for the build).

Also: fail closed (rule 2) is honoured by one refusal for every unresolvable, unknown, unpermitted or erroring catalog path, and by omitting a field whose description cannot be computed rather than describing it as unprotected.

## 8. Test obligations for 0.8 and 0.9

Mutation-testable; the privacy modules are mutation-tested from 0.7 and gated in 0.9 (D-070-9).

1. **Compatibility.** An adapter written against the 0.7 SPI, with no `description()`, compiles and is listed with the generated fallback, no entity types and no input keys. Mutant: a null description throws or hides the source.
2. **Startup checks.** For each rule in section 2: a blank, over-long (241 characters), multi-line, control-character, markup, `@`-bearing, key-shaped, URL-shaped or host-shaped text refuses with `INVALID_SOURCE_DESCRIPTION`; a text containing the real name, the class name, the base-url host or another source's name (in mixed case and with punctuation removed) refuses with `SOURCE_DESCRIPTION_LEAKS_NAME`; identical alias and real descriptions refuse. Boundary values: 240 passes, 241 refuses. Mutants: case-sensitive compare; skip normalisation; check the real description with rule 5.
3. **Real description never under an alias.** With aliases in effect, no response from any tool contains the real description or the real name, in plaintext or in encoded form. With real names in effect the real description is shown. Mutant: choose the description by capability only.
4. **Effective setting.** The label kind is REAL only when the scope setting and `EXPOSE_SOURCE_NAMES` both hold. The four combinations are tested. Mutant: `||` for `&&`; ignore the scope setting.
5. **Description conforms to the scrubber.** For a set of models (annotated, descriptor-tightened, profile-tightened, nested, array), every path that appears in a scrubbed response is described with the protection it was scrubbed under, and no described `VERBATIM` field is ever scrubbed. A descriptor that tightens a field changes the description. Mutant: describe the annotation only.
6. **`describe_source` leaks nothing.** The response contains none of: a value, the data classification names, the base-url, the model class name, a credential reference. Run the PII, key-shape and audit scans over it.
7. **Chainable markers.** Shown only when links are enabled; always together with `keyNamespace`; a removed chainable field is listed as `ABSENT`; a removed non-chainable field is not listed. Chainable changes no protection (the scrub output is identical with the flag set).
8. **Labels.** The same source has the same label in all four tools and in `_links.target` within one scope; a different scope gives a different alias. A real name passed while aliases are in effect is `SOURCE_NOT_AVAILABLE`. A forced collision gives `SOURCE_LABEL_COLLISION`.
9. **Uniform refusal.** Unknown label, wrong-kind label, unpermitted source and entity-type mismatch return the identical client response (same code, same text, same shape), and differ only in the audit-only reason. Mutant: distinct codes or distinct timing short-cuts.
10. **Order and determinism.** Every array in all three outputs is in the ascending order stated, identical across runs and across the order adapters were registered.
11. **D-RM-9.** At or below the limit, `get_entity_context` without `targetSources` behaves as today; one above it, it is refused with `SOURCE_SELECTION_REQUIRED` and no adapter is called (verified with a counting adapter). Boundary values at limit and limit plus one. Mutants: `>=` for `>`; count all sources instead of permitted ones.
12. **Targeting.** Only the chosen adapters are called; a selection above `max-sources` is refused and nothing is called; a duplicate label is rejected by the schema; `compare_entity_sources` follows the same rules (OD-C6).
13. **Authorisation.** A source the caller may not use is absent from `list_sources`, and `describe_source`, targeting and `describe_entity_model` treat it as unknown. Mutant: filter the list but not the others.
14. **`describe_entity_model` (0.9).** Relations equal those the link builder would issue for the same declarations, by a property test comparing the two on generated estates; direct preferred over translated; no two-resolver relation; resolver names never appear; with links off the tool omits `producedBy` and `reachesAs`; an unknown type and an empty type are indistinguishable; nothing about a source the caller cannot use appears.
15. **Audit.** One event per call with the fields of sections 3 to 6; the effective setting is recorded as OD-C2 decides; no label sent by the client, no description text and no value appears in any audit record, log line, metric or exception (`PiiLogScanTest` and `AuditFilePiiScanTest` extended).
16. **Configuration.** A non-positive or non-integer `unselected-source-limit` and an unknown purpose in `real-names-purposes` refuse startup with `INVALID_CATALOG_SETTING`; defaults match this document.
17. **Schemas.** The three tool schemas in this document are valid JSON Schema draft 2020-12 and each example validates against its schema (the validation command is in the Outcome of the task file). The served schemas are generated from the same definitions, and a build test compares them with the checked-in copies.
18. **Architecture rules.** `mcp` and `connectors` do not depend on adapter classes; `core` contains no business-domain type.

## 9. Open decisions for the owner

None of these is decided. Each has options and a recommendation.

| # | Question | Options | Recommendation |
|---|---|---|---|
| OD-C1 | How an adapter describes itself | (a) a default method `description()` on `DataSourceAdapter`; (b) a separate optional interface that an adapter may also implement; (c) a separate bean (`SourceDescription` registered by source name) | (a). Source-compatible and binary-compatible, one place to look, and the description sits with the code that reviewers already read. (c) separates the text from the adapter and invites drift; (b) is more ceremony for the same result |
| OD-C2 | Where the effective naming setting is recorded in the audit (D-RM-10). It is not a disposition, and shape C allows only `REFUSED:<code>`-shaped values, so it does not fit the grammar | (a) allow one reserved key, `names`, with the two values `REAL` and `ALIAS`: a small widening of OD-13; (b) a new record field in a version 4 audit record; (c) derive it from configuration and the caller's roles on read, recording nothing per event; (d) a metric only | (a), if the owner accepts the small widening. (b) is the cleanest and the most expensive (every verifier and the hash encoding). (c) and (d) do not meet D-RM-10's "audit always records" and are listed only to be rejected. This is a conflict with shape C, not a choice inside it |
| OD-C3 | Where the per-scope real-names setting comes from. A scope is `case:<caseId>` with no stored record | (a) the scope's purpose is in a server-side list, `dataprism.catalog.real-names-purposes`, empty by default; (b) a trusted claim in the token, mapped like the investigation claim; (c) an allow-list of case-id patterns in configuration | (a). Purposes are a closed server-controlled set already in the audit record, the setting needs no new claim or per-case list, and it is off unless the operator names a purpose. (b) lets the issuer set it per token, so two tokens for one case may disagree. (c) puts case ids in configuration |
| OD-C4 | A source the caller may not use, in `list_sources` | (a) hidden: absent from the list; (b) listed with a `permitted: false` marker | (a). It is the only form that adds no estate shape beyond what the caller can use, and it makes every other tool's uniform refusal consistent. (b) helps a human understand why a source is missing but tells an injected agent that more sources exist |
| OD-C5 | Behaviour of per-source permission in 0.8, before the 0.9 policy | (a) allow every source to a caller that holds the tool capability (as today); once any role declares source patterns, unmatched sources are denied; (b) deny every source until patterns are configured; (c) delay the whole catalog to 0.9 | (a). It keeps 0.8 upgrades working and gives D-RM-11 its meaning exactly when an operator adopts it. The one-time change in behaviour at that point needs a line in the 0.9 upgrade guide. (b) breaks every upgrade |
| OD-C6 | Name and reach of the targeting argument | (a) `targetSources`, on both `get_entity_context` and `compare_entity_sources`; (b) `sources`, which collides with the response object of the same tool; (c) `sourceLabels` | (a), on both tools |
| OD-C7 | The estate-size limit: key, default and what it counts | (a) new key `dataprism.catalog.unselected-source-limit`, default `8`, counting permitted sources serving the entity type; (b) reuse `dataprism.limits.max-sources`; (c) a new key counting all registered sources | (a). Reusing `max-sources` couples "how many may I call" with "how many may be asked implicitly", which are different questions once selection exists. Counting only permitted sources avoids disclosing the estate size through the refusal. Default `8` leaves today's small estates unchanged |
| OD-C8 | A selection longer than the call limit | (a) refuse with `SOURCE_SELECTION_TOO_LARGE`, limit `dataprism.limits.max-sources`; (b) allow it and queue the rest; (c) allow up to a separate selection ceiling | (a). The roadmap removes the silent cut-off, and a refusal is the fail-closed form. (b) is the fan-out rewrite's concern, not this design's |
| OD-C9 | Removed fields in `describe_source` | (a) omit them, except chainable ones; (b) list every removed field as `ABSENT`; (c) list them with names generalised for special-category fields | (a). The description then equals the set of fields a response can contain, which is testable, and it does not list the existence of fields that never appear |
| OD-C10 | Show `valueNamespace` | (a) show it for `SYNTHETIC` and `TOKEN` fields; (b) never show it; (c) show it for every field | (a). It is what lets an agent trust that two pseudonyms are comparable, and it is bounded to fields that are replaced anyway |
| OD-C11 | Audit size of `list_sources` | (a) record every returned source's real name in `sourceSystems`; (b) record none | (a) in 0.8, where estates are tens of sources, with the 0.9 audit-entry size handling covering hundreds (roadmap, 0.9). D-RM-10 asks for real names. (b) breaks it |
| OD-C12 | Do the cost and freshness hints change behaviour | (a) advisory text only; (b) cost weights the read-budget charge | (a). The budget is being reworked in 0.8; an advisory hint can be tightened later, and a weighted charge cannot be taken back |
| OD-C13 | How an adapter learns which input namespace it was handed | (a) an additive `keyNamespace` field on `DataRequest`, with a default for existing callers; (b) one adapter per namespace; (c) `parameters` entry | (a), settled with task 191's follow design when 0.8 plans it (an interface between two designs; reconcile in task 200) |
| OD-C14 | Can a deployment refuse the generated fallback | (a) never: fallback is allowed; (b) a switch that refuses startup if any source has none; (c) refuse above a source count | (a) for the 0.8 build as D-RM-8 says, and (b) is a candidate for a later release after the pilot shows whether operators need it |
| OD-C15 | The shape and extent of `describe_entity_model` | (a) namespace-centric with `comparableValues`; (b) namespace-centric only; (c) pairs of source relations; (d) not built (rely on `list_sources`, `describe_source` and `_links`) | (a), because design-review §B5's purpose includes value comparability and the pairs form grows quadratically. If the owner wants the smallest surface to freeze at 0.9, (b) |
| OD-C16 | Does `list_sources` need paging in 0.8 | (a) yes, and task 198 specifies the page for `list_sources` first so 0.8 can build it; (b) no: ship unpaged with the two filters and reconsider at 0.9; (c) refuse above a hard size | (a). At several hundred sources an unpaged list is about 100 to 200 KB, too large for a model context. This is a cross-task point to settle in task 200 |
| OD-C17 | `describe_entity_model` with links disabled | (a) registered, reports `acceptedBy` only; (b) not registered while links are off; (c) always full | (a), because a deployment on option (c) of the link design (human-supplied keys) still benefits from knowing which sources accept which namespace |
| OD-C18 | A grammar for source names | (a) none (today); (b) `[a-z][a-z0-9-]{0,63}` checked at startup | (b), with an `INVALID_SOURCE_NAME` code, because real names become labels and audit disposition keys. It can refuse an existing deployment's names, so it needs the migration note, and it is an owner choice for that reason |

## 10. Proposed names (closing table)

Checked for collisions against `docs/tools.md`, `docs/audit.md`, `docs/configuration.md`, the three existing design documents (`sealed-links.md`, `key-resolvers.md`, `cluster-security.md`), the source code, and the task files for 198 and 200. The commands are in the Outcome of the task file. None collides with an existing name; where a name is close to one, the third column says so.

| Name | Kind | Proposed release | Closest existing name and why it is distinct |
|---|---|---|---|
| `list_sources` | MCP tool | 0.8 | none |
| `describe_source` | MCP tool | 0.8 | none (`describe_entity_model` is a different tool) |
| `describe_entity_model` | MCP tool | 0.9 | named in the specification and `Capability`, never built |
| `LIST_SOURCES` | capability | 0.8 | none |
| `DESCRIBE_SOURCE` | capability | 0.8 | `DESCRIBE_ENTITY_MODEL` (exists, different tool) |
| `targetSources` | tool argument | 0.8 | `sources` (a response field; different spelling on purpose) |
| `SourceDescription` | SPI record | 0.8 | none |
| `CostHint`, `FreshnessHint` | SPI enums | 0.8 | none |
| `DataSourceAdapter.description()` | SPI default method | 0.8 | none |
| `SourceCatalog` | startup registry | 0.8 | none |
| `SourceVisibility` | permission predicate | 0.8 (allow-all until 0.9; OD-C5) | none |
| `ListSourcesTool`, `DescribeSourceTool`, `DescribeEntityModelTool` | MCP tool classes | 0.8, 0.8, 0.9 | `GetEntityContextTool` (pattern, not a clash) |
| `description`, `entity-types` (under `description:` only), `alias-description`, `real-description`, `input-keys`, `cost`, `freshness` | `json-sources` entry keys under `description:` | 0.8 | `model-version`, `fields` (siblings; no overlap) |
| `labels` | result field (`ALIAS`, `REAL`) | 0.8 | none |
| `source`, `entityTypes`, `inputKeys`, `cost`, `freshness`, `undeclared`, `fields` | result fields of `list_sources` and `describe_source` | 0.8 | `entityType` (singular, the existing request field); `entityTypes()` on the MCP tool options and `dataprism.audit.entity-types` mean the audited entity types, a different list on the server side, never a result field; `sources` in `get_entity_context` is an object, `sources` here is an array in another tool |
| `path`, `protection`, `valueNamespace` | `describe_source` field entry keys | 0.8 | `namespace` in descriptors (means `PrivacyNamespace`; `valueNamespace` is the same meaning, `keyNamespace` is the link meaning) |
| `VERBATIM`, `SYNTHETIC`, `TOKEN`, `MARKER`, `BUCKET`, `ABSENT` | protection vocabulary | 0.8 | `PrivacyAction` names (`SYNTHESIZE`, `REDACT` and so on); the vocabulary describes the effect and is intentionally different |
| `REFUSE`, `RENAME`, `DROP` | `undeclared` values | 0.8 | `UndeclaredFields.DROP` (annotation enum, `PROFILE_DEFAULT`, `NON_SENSITIVE`, `REDACT`, `DROP`): the same word with the same effect (the property is dropped), but a different enum in a different place; the response values are strings and share no type |
| `ALIAS`, `REAL` | `labels` values | 0.8 | none |
| `keyNamespaces`, `producedBy`, `acceptedBy`, `reachesAs`, `comparableValues`, `sourceField` | `describe_entity_model` result fields | 0.9 | `keyNamespace` (singular, from task 191) |
| `SOURCE_SELECTION_REQUIRED` | refusal code | 0.8 | none |
| `SOURCE_SELECTION_TOO_LARGE` | refusal code | 0.8 | `SKIPPED_OVER_LIMIT` (a status, not a refusal) |
| `SOURCE_NOT_AVAILABLE` | refusal code | 0.8 | none |
| `ENTITY_MODEL_NOT_AVAILABLE` | refusal code | 0.9 | none |
| `SOURCE_LABEL_COLLISION` | refusal code | 0.8 | none |
| `INVALID_SOURCE_DESCRIPTION` | startup refusal code | 0.8 | none |
| `SOURCE_DESCRIPTION_LEAKS_NAME` | startup refusal code | 0.8 | none |
| `INVALID_CATALOG_SETTING` | startup refusal code | 0.8 | `INVALID_REQUEST_LIMITS`, `INVALID_LINK_LIMIT` (siblings) |
| `INVALID_SOURCE_NAME` | startup refusal code | 0.8, only if OD-C18 (b) | none |
| `catalog` | audit disposition key (values `REFUSED:CATALOG_<REASON>`) | 0.8 | `link` (task 191, sibling) |
| `CATALOG_UNKNOWN_SOURCE`, `CATALOG_SOURCE_NOT_PERMITTED`, `CATALOG_ENTITY_TYPE_MISMATCH`, `CATALOG_UNKNOWN_ENTITY_TYPE`, `CATALOG_NO_PERMITTED_SOURCE` | audit-only reason codes (shape C) | 0.8; the last two 0.9 | `LINK_<REASON>` (task 191, sibling) |
| `names` | reserved audit disposition key (values `REAL`, `ALIAS`) | 0.8, only if OD-C2 (a) | none |
| `dataprism.catalog.unselected-source-limit` | configuration key (default `8`) | 0.8 | `dataprism.limits.max-sources` (OD-C7) |
| `dataprism.catalog.real-names-purposes` | configuration key (default empty) | 0.8 | none |
| `dataprism.catalog.descriptions.generated` | metric (gauge) | 0.8 | `dataprism.links.keys.loaded` (task 191, sibling) |
