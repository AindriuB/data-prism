# Contract register: every tool, code and key planned up to the 0.9 freeze

Task 200. Internal planning document (D-070-12 (a), PLAN.md:139), under `docs/plan/design/`, which mkdocs excludes. Written on 2026-10-20 against `plan/0.7.0` at `840c291b`. No code and no shipped document changes with it.

The public surface freezes at the 0.9.0 release: public API, configuration properties, refusal codes, status codes, MCP tool schemas and the audit format. This register lists every name that surface will contain or that a design or task brief proposes for it, says which release introduces each, finds the collisions, and ends with the freeze checklist. A proposal here is not a shipped API (`reconciliation-2026-10-19.md`).

## How to read this register

- **State.** `shipped` is in a released tag (the Release column then names the first tag whose source contains the name; `<=0.6.0` means the pinned list that proves it first appeared in 0.6.0 and earlier existence was not traced). `shipped*` is on `plan/0.7.0` and unreleased (Release `0.7.0*`). `proposed` is named by a design document or a task brief and has no code. `withdrawn` was proposed and superseded (section 10). Nothing proposed is built because it is listed here.
- **Source.** `module:File.java:line` is `data-prism-<module>/src/main/java/...`. `docs/x.md:line` is a shipped document. `design/x.md:line` is `docs/plan/design/x.md`. `tN:line` is `docs/plan/tasks/N-*.md` (`retired/` for landed tasks). `PLAN.md:line` is `docs/plan/PLAN.md`. `FZ:line` is the pinned list `frozen-property-names.txt` in the autoconfigure module test resources.
- **Decision.** Open decision ids restart in every design document (`OD-1` exists in both `sealed-links.md` and `cluster-security.md`), so a reference names its document: `sealed-links OD-12`, `cluster-security OD-5`, `catalog OD-C2`, `batch OD-B12`, `key-resolvers OD-R1`. `D-RM-n`, `D-070-n`, `D-1.0-n` and `D-0.7-HZ-n` are recorded in `PLAN.md` and `reconciliation-2026-10-19.md`. This register's own open decisions are `OD-200-n` (section 14).
- **"(delegated)".** On 2026-10-09 the owner delegated every open decision in `sealed-links.md`, `key-resolvers.md`, `catalog.md` and `cluster-security.md` to its recommendation (PLAN.md:105, :114). Explicit owner decisions: catalog OD-C2 (a) (2026-10-09); batch OD-B12 (part), OD-B18 and OD-B24 (b), with the rest of OD-B1 to OD-B24 delegated (2026-10-19, PLAN.md:89); the sealed cluster design confirmed (2026-10-19, reconciliation-2026-10-19.md).
- **Sources of proposals.** The five design documents' closing tables (`design/sealed-links.md`, `key-resolvers.md`, `catalog.md`, `cluster-security.md`, `batch-and-bulk-follow.md`), the open and retired task briefs (203, 206 to 208, 210 to 214, 209 merged, 216, 232, 202), and the shipped code and documents. Where a document and the code disagree, section 12 records both and says which one governs today; none is silently picked.
- **Model of the surface.** A name belongs to exactly one *kind*. Section 11 lists names that look alike across kinds. Three kinds share the `dataprism.` string prefix and are independent namespaces: configuration keys, metric names and Hazelcast map names.

## 0. What is in this register

| Kind | Shipped (released or `shipped*`) | Proposed | Section |
|---|---|---|---|
| MCP tools | 2 | 6 (one more name reserved) | 1.1 |
| Capabilities | 4 | 4 + 1 undefined (`FOLLOW`) | 1.2 |
| Per-source status codes | 7 | 1 | 2 |
| Call, admission, re-identification, operator, startup, violation and audit-runtime codes | 208 (34 call, 10 admission, 12 re-identification, 8 operator, 130 startup, 3 violation, 11 audit runtime) | 80 startup/call/audit codes in tasks and designs | 3 |
| Audit record fields / `policyDecision` forms | 25 / 6 | 0 changes to either; new disposition keys and values | 4 |
| Metrics | 17 | 19 | 5 |
| `dataprism.*` configuration keys | 94 | 55 (plus 10 descriptor and `json-sources` entry keys) | 6 |
| SPI interfaces and annotations | 30 | 24 types | 7 |
| Closing-table rows / distinct names in the five design documents | | 170 rows / 251 names, every one appearing in this register | 1 to 8 |

Collisions found: 21 (section 11). Document disagreements recorded: 13 (section 12). Open owner decisions: 14 (section 14). Successor tasks named: 6 (section 16).

The findings that matter most before 0.8 starts:

1. **`CLUSTER_OPERATION_REFUSED` (task 216) cannot be seen by any caller.** Tool calls turn it into `OVERSIGHT_UNAVAILABLE`, reads into `SCOPE_READ_BUDGET`, and nothing logs it (3.2, OD-200-2). `dataprism.approval.release.skipped` (task 232) is registered in section 5.
2. **The unknown-status rule is recorded two ways** (D1, OD-200-1). The decision record, the code and the shipped page say "not answered"; only the later `PLAN.md` summary says "an error".
3. **Length caps disagree between the link layout, the catalog schemas and the resolver grammar** (D3, OD-200-4), and catalog OD-C18 (c) lets a long source name through in 0.8 while the link layout says it is refused at startup.
4. **A capability for `follow` is assumed by the batch design and defined nowhere** (OD-200-5).
5. **The literal-only extraction misses codes declared as enum constants** (`UNSAFE_HAZELCAST_DISCOVERY`, `INVALID_CLUSTER_PORT`); the freeze test must read both (E1, E2).
6. **68 of 206 shipped call, admission, re-identification, operator, startup, violation and audit-runtime codes are not named in any shipped page** (15).
7. **Several closing-table rows are stale** against the decisions in the same documents: the audit keys that shape C withdrew, the cluster `security.lock-timeout` that task 202 superseded, `KeyNamespace` and the twelve link reasons missing from their tables (D4 to D8).
8. **Relation bytes `0x02` to `0x04`** extend 191's header table; the link `max-length` default is 1024 (OD-B12); the catalog's 128-character labels against 64 are OD-200-4 (all in 8.2 and section 12).
9. **The 0.9 per-source authorisation (D-RM-11) has no name in any document**, so the freeze cannot pin it (6.5, S6).

## 1. Tools, capabilities, arguments and result vocabulary

### 1.1 MCP tools

| Tool | State | Release | Source | Arguments, capability, decision |
|---|---|---|---|---|
| `get_entity_context` | shipped | 0.1.0 | mcp:GetEntityContextTool.java:74; docs/tools.md:101 | Arguments `entityType`, `subjectId` (both required strings). Capability `GET_ENTITY_CONTEXT`. Result changes in 0.7.0* (D-070-1 (b), t190:7): `sources.<key>.{status, entity}`, no top-level `entity`. |
| `compare_entity_sources` | shipped | 0.2.0 | mcp:CompareEntitySourcesTool.java:78; docs/tools.md:273 | Same two arguments. Capability `COMPARE_ENTITY_SOURCES`. D-RM-13 (a) (PLAN.md:34): kept, usage reviewed before the freeze. |
| `list_sources` | proposed | 0.8 | design/catalog.md:809 | Arguments `entityType`, `keyNamespace` (catalog), `pageSize`, `page` (batch closing table). Capability `LIST_SOURCES`. Allows all callers in 0.8 only (catalog OD-C5, PLAN.md:118). Decision: catalog OD-C1..C19 (delegated). |
| `describe_source` | proposed | 0.8 | design/catalog.md:810 | Argument `source`. Capability `DESCRIBE_SOURCE`. Decision: catalog OD-C1..C19 (delegated). |
| `describe_entity_model` | proposed | 0.9 | design/catalog.md:811 | Named in the specification and in `Capability.DESCRIBE_ENTITY_MODEL`; never built (docs/tools.md:612). Decision: catalog OD-C1..C19 (delegated). |
| `follow` | proposed | 0.8 | design/sealed-links.md:475 | Argument `link`. No capability is defined for it in `sealed-links.md`; `batch-and-bulk-follow.md` assumes one named `FOLLOW` (OD-200-5). Decision: sealed-links §5; capability FOLLOW open: OD-200-5. |
| `get_entity_contexts` | proposed | 0.9 | design/batch-and-bulk-follow.md:1125 | Batch form of `get_entity_context` (batch OD-B17 (a)). Capability `GET_ENTITY_CONTEXTS` (batch OD-B7). Decision: batch OD-B1..B24 (delegated). |
| `follow_bulk` | proposed | 0.9 | design/batch-and-bulk-follow.md:1126 | Status-only bulk follow with drill-in links (D-RM-15 (b)). Capability `FOLLOW_BULK`. Decision: batch OD-B1..B24 (delegated). |
| `search_entity_data` | not planned before 1.0 | after 1.0 | docs/tools.md:612; D-RM-2 (a), PLAN.md:23 | Named in the original specification only. The name is reserved here so no later design reuses it. |

### 1.2 Capabilities

| Capability | State | Release | Source | Note, decision |
|---|---|---|---|---|
| `EXPOSE_SOURCE_NAMES` | shipped | 0.1.0 | core:Capability.java:14 | Pinned by `CapabilityKnownSetTest`; an unknown name in a role refuses startup (`UNKNOWN_CAPABILITY`). |
| `GET_ENTITY_CONTEXT` | shipped | 0.1.0 | core:Capability.java:15 |  |
| `COMPARE_ENTITY_SOURCES` | shipped | 0.1.0 | core:Capability.java:16 |  |
| `DESCRIBE_ENTITY_MODEL` | shipped | 0.1.0 | core:Capability.java:17 | No tool is built for it. |
| `LIST_SOURCES` | proposed | 0.8 | design/catalog.md:812 | catalog OD-C1..C19 (delegated); none |
| `DESCRIBE_SOURCE` | proposed | 0.8 | design/catalog.md:813 | catalog OD-C1..C19 (delegated); `DESCRIBE_ENTITY_MODEL` (exists, different tool) |
| `GET_ENTITY_CONTEXTS` | proposed | 0.9 | design/batch-and-bulk-follow.md:1127 | batch OD-B1..B24 (delegated); `GET_ENTITY_CONTEXT` (exists), `FOLLOW` (task 191) |
| `FOLLOW_BULK` | proposed | 0.9 | design/batch-and-bulk-follow.md:1127 | batch OD-B1..B24 (delegated); same closing-table row as `GET_ENTITY_CONTEXTS` |
| `FOLLOW` | proposed (referenced, not defined) | 0.8 | design/batch-and-bulk-follow.md:1091 (cited as "`FOLLOW` (task 191)"); absent from `sealed-links.md` | OD-200-5 |

### 1.3 Tool arguments

Today both tools accept exactly `entityType` and `subjectId`; the schema does not close the object (`additionalProperties` absent), so an unknown or reserved argument is ignored and audited (`rejectedArguments`), never read (docs/tools.md:112). Every proposed schema keeps that rule (catalog.md:214).

| Argument | State | Release | Source | Kind, decision, note |
|---|---|---|---|---|
| `entityType` | shipped | 0.1.0 | mcp:GetEntityContextTool.java:159 | string, required, both tools |
| `subjectId` | shipped | 0.1.0 | mcp:GetEntityContextTool.java:162 | string, required, both tools |
| `principalId` | shipped (reserved) | 0.1.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `scopeId` | shipped (reserved) | 0.1.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `scopeType` | shipped (reserved) | 0.1.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `purpose` | shipped (reserved) | 0.1.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `caseId` | shipped (reserved) | 0.1.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `profile` | shipped (reserved) | 0.1.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `capabilities` | shipped (reserved) | 0.1.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `correlationId` | shipped (reserved) | 0.5.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. Pinned by `ReservedArgumentsTest`. `correlationId` and `externalCorrelationId` are reserved *argument* names and also *result* and *audit* field names with the same meaning. |
| `externalCorrelationId` | shipped (reserved) | 0.5.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `traceparent` | shipped (reserved) | 0.5.0 | security:ReservedArguments.java:20 | Never read from a tool call; stripped and audited. |
| `targetSources` | proposed | 0.8 | design/catalog.md:814 | tool argument. catalog OD-C1..C19 (delegated). `sources` (a response field; different spelling on purpose) |
| `entityType` | proposed | 0.8 | design/catalog.md:844 | `list_sources` input arguments (`entityType` is the existing argument name, same meaning). catalog OD-C1..C19 (delegated). `entityType` of `get_entity_context`; `keyNamespace` from task 191 |
| `keyNamespace` | proposed | 0.8 | design/catalog.md:844 | `list_sources` input arguments (`entityType` is the existing argument name, same meaning). catalog OD-C1..C19 (delegated). same closing-table row as `entityType` |
| `source` | proposed | 0.8 | design/catalog.md:845 | `describe_source` input argument. catalog OD-C1..C19 (delegated). none |
| `pageSize` | proposed | 0.8 | design/batch-and-bulk-follow.md:1116 | `list_sources` input arguments. batch OD-B1..B24 (delegated). `page` the object in the output has the same name as the input argument, with a different shape (a string in, an object out); the same pattern as `entityType` |
| `page` | proposed | 0.8 | design/batch-and-bulk-follow.md:1116 | `list_sources` input arguments. batch OD-B1..B24 (delegated). same closing-table row as `pageSize` |
| `items` | proposed | 0.9 | design/batch-and-bulk-follow.md:1128 | input fields. batch OD-B1..B24 (delegated). `targetSources` of `catalog.md` (same name, same meaning); `items` is also the field of a one-to-many `follow` result, a different object in a different tool |
| `targetSources` | proposed | 0.9 | design/batch-and-bulk-follow.md:1128 | input fields. batch OD-B1..B24 (delegated). same closing-table row as `items` |
| `links` | proposed | 0.9 | design/batch-and-bulk-follow.md:1141 | `follow_bulk` input fields. batch OD-B1..B24 (delegated). `link` of `follow` (singular); `links` is also the `dataprism.links` configuration group, a different thing |
| `group` | proposed | 0.9 | design/batch-and-bulk-follow.md:1141 | `follow_bulk` input fields. batch OD-B1..B24 (delegated). same closing-table row as `links` |
| `exceptionStatuses` | proposed | 0.9 | design/batch-and-bulk-follow.md:1141 | `follow_bulk` input fields. batch OD-B1..B24 (delegated). same closing-table row as `links` |

Every proposed argument name is absent from `ReservedArguments.NAMES` (checked for 10 names: `entityType`, `exceptionStatuses`, `group`, `items`, `keyNamespace`, `links`, `page`, `pageSize`, `source`, `targetSources`).

### 1.4 Tool results: fields and enumerated values

There is no schema test for tool results today (section 15). The shipped result vocabulary, from `docs/tools.md` and the records that serialise it:

| Object | Fields or values | State | Release | Source | Note |
|---|---|---|---|---|---|
| get_entity_context result | `entityType`, `subject`, `sources`, `findings` | shipped | <=0.6.0 (shape changed 0.7.0*) | docs/tools.md:122 | `sources` is keyed by scope-local alias, or real name with `EXPOSE_SOURCE_NAMES` |
| per-source entry | `status`, `entity` | shipped* | 0.7.0* | orchestration:SourceResult.java:20; t190:7 | `entity` only when `status` is `ANSWERED`; `status` is a String so a later code does not break deserialisation |
| compare_entity_sources result | `entityType`, `subject`, `identity`, `findings` (each with `consistent`) | shipped | 0.2.0 | docs/tools.md:292 |  |
| finding | `field`, `namespace`, `kind`, `agreementGroups`, `distinctValues`, `detail` | shipped | <=0.6.0 | docs/tools.md:370 | never a value |
| finding `kind` | `INCONSISTENT`, `FORMATTING_ONLY`, `ABBREVIATION`, `MISSING_IN_SOME_SOURCES`, `SUSPECTED_INSTRUCTION_CONTENT`, `CONSISTENT` | shipped | <=0.6.0 | core:ConsistencyFinding.java:51 | six kinds; `disagreement()` treats only the last two as agreement |
| refusal result | `isError: true`, one text item holding the bare code (`APPROVAL_REQUIRED approvalId=<id>` for the two approval codes) | shipped | <=0.6.0 | mcp:ToolCalls.java:100 | codes in section 3 |
| audit-visible `PrivacyAction` names | `PASS_THROUGH`, `REDACT`, `REMOVE`, `HASH`, `SYNTHESIZE`, `TOKENIZE`, `GENERALIZE` | shipped | <=0.6.0 | annotations:PrivacyAction.java:13 | appear as disposition values in the audit record (section 4.3), not in tool results |

Proposed result fields and values from the closing tables (each row is the closing-table row, with its line):

| Name | Kind (as the document words it) | State | Release | Source | Decision | Closest existing name, or note |
|---|---|---|---|---|---|---|
| `_links` | result field | proposed | 0.8 | design/sealed-links.md:488 | sealed-links OD-1..17 (delegated) |  |
| `_linksOmitted` | result field | proposed | 0.8 | design/sealed-links.md:489 | sealed-links OD-1..17 (delegated) |  |
| `items` | result fields (one-to-many follow) | proposed | 0.8 | design/sealed-links.md:490 | sealed-links OD-1..17 (delegated) |  |
| `itemsTruncated` | result fields (one-to-many follow) | proposed | 0.8 | design/sealed-links.md:490 | sealed-links OD-1..17 (delegated) | same closing-table row as `items` |
| `labels` | result field (`ALIAS`, `REAL`) | proposed | 0.8 | design/catalog.md:822 | catalog OD-C1..C19 (delegated) | none |
| `source` | result fields of `list_sources` and `describe_source` | proposed | 0.8 | design/catalog.md:823 | catalog OD-C1..C19 (delegated) | `entityType` (singular, the existing request field); `entityTypes()` on the MCP tool options and `dataprism.audit.entity-types` mean the audited entity types, a different list on the server side, never a result field; `sources` in `get_entity_context` is an object, `sources` here is an array in another tool |
| `entityTypes` | result fields of `list_sources` and `describe_source` | proposed | 0.8 | design/catalog.md:823 | catalog OD-C1..C19 (delegated) | same closing-table row as `source` |
| `inputKeys` | result fields of `list_sources` and `describe_source` | proposed | 0.8 | design/catalog.md:823 | catalog OD-C1..C19 (delegated) | same closing-table row as `source` |
| `cost` | result fields of `list_sources` and `describe_source` | proposed | 0.8 | design/catalog.md:823 | catalog OD-C1..C19 (delegated) | same closing-table row as `source` |
| `freshness` | result fields of `list_sources` and `describe_source` | proposed | 0.8 | design/catalog.md:823 | catalog OD-C1..C19 (delegated) | same closing-table row as `source` |
| `undeclared` | result fields of `list_sources` and `describe_source` | proposed | 0.8 | design/catalog.md:823 | catalog OD-C1..C19 (delegated) | same closing-table row as `source` |
| `fields` | result fields of `list_sources` and `describe_source` | proposed | 0.8 | design/catalog.md:823 | catalog OD-C1..C19 (delegated) | same closing-table row as `source` |
| `path` | `describe_source` field entry keys | proposed | 0.8 | design/catalog.md:824 | catalog OD-C1..C19 (delegated) | `namespace` in descriptors (means `PrivacyNamespace`; `valueNamespace` is the same meaning, `keyNamespace` is the link meaning) |
| `protection` | `describe_source` field entry keys | proposed | 0.8 | design/catalog.md:824 | catalog OD-C1..C19 (delegated) | same closing-table row as `path` |
| `valueNamespace` | `describe_source` field entry keys | proposed | 0.8 | design/catalog.md:824 | catalog OD-C1..C19 (delegated) | same closing-table row as `path` |
| `VERBATIM` | protection vocabulary | proposed | 0.8 | design/catalog.md:825 | catalog OD-C1..C19 (delegated) | `PrivacyAction` names (`SYNTHESIZE`, `REDACT` and so on); the vocabulary describes the effect and is intentionally different |
| `SYNTHETIC` | protection vocabulary | proposed | 0.8 | design/catalog.md:825 | catalog OD-C1..C19 (delegated) | same closing-table row as `VERBATIM` |
| `TOKEN` | protection vocabulary | proposed | 0.8 | design/catalog.md:825 | catalog OD-C1..C19 (delegated) | same closing-table row as `VERBATIM` |
| `MARKER` | protection vocabulary | proposed | 0.8 | design/catalog.md:825 | catalog OD-C1..C19 (delegated) | same closing-table row as `VERBATIM` |
| `BUCKET` | protection vocabulary | proposed | 0.8 | design/catalog.md:825 | catalog OD-C1..C19 (delegated) | same closing-table row as `VERBATIM` |
| `ABSENT` | protection vocabulary | proposed | 0.8 | design/catalog.md:825 | catalog OD-C1..C19 (delegated) | same closing-table row as `VERBATIM` |
| `REFUSE` | `undeclared` values | proposed | 0.8 | design/catalog.md:826 | catalog OD-C1..C19 (delegated) | `UndeclaredFields.DROP` (annotation enum, `PROFILE_DEFAULT`, `NON_SENSITIVE`, `REDACT`, `DROP`): the same word with the same effect (the property is dropped), but a different enum in a different place; the response values are strings and share no type |
| `RENAME` | `undeclared` values | proposed | 0.8 | design/catalog.md:826 | catalog OD-C1..C19 (delegated) | same closing-table row as `REFUSE` |
| `DROP` | `undeclared` values | proposed | 0.8 | design/catalog.md:826 | catalog OD-C1..C19 (delegated) | same closing-table row as `REFUSE` |
| `ALIAS` | `labels` values | proposed | 0.8 | design/catalog.md:827 | catalog OD-C1..C19 (delegated) | none |
| `REAL` | `labels` values | proposed | 0.8 | design/catalog.md:827 | catalog OD-C1..C19 (delegated) | same closing-table row as `ALIAS` |
| `keyNamespaces` | `describe_entity_model` result fields | proposed | 0.9 | design/catalog.md:828 | catalog OD-C1..C19 (delegated) | `keyNamespace` (singular, from task 191) |
| `producedBy` | `describe_entity_model` result fields | proposed | 0.9 | design/catalog.md:828 | catalog OD-C1..C19 (delegated) | same closing-table row as `keyNamespaces` |
| `acceptedBy` | `describe_entity_model` result fields | proposed | 0.9 | design/catalog.md:828 | catalog OD-C1..C19 (delegated) | same closing-table row as `keyNamespaces` |
| `reachesAs` | `describe_entity_model` result fields | proposed | 0.9 | design/catalog.md:828 | catalog OD-C1..C19 (delegated) | same closing-table row as `keyNamespaces` |
| `comparableValues` | `describe_entity_model` result fields | proposed | 0.9 | design/catalog.md:828 | catalog OD-C1..C19 (delegated) | same closing-table row as `keyNamespaces` |
| `sourceField` | `describe_entity_model` result fields | proposed | 0.9 | design/catalog.md:828 | catalog OD-C1..C19 (delegated) | same closing-table row as `keyNamespaces` |
| `page` | `list_sources` result object | proposed | 0.8 | design/batch-and-bulk-follow.md:1117 | batch OD-B1..B24 (delegated) | `expiresAt` is the field of a `_links` entry, same meaning |
| `size` | `list_sources` result object | proposed | 0.8 | design/batch-and-bulk-follow.md:1117 | batch OD-B1..B24 (delegated) | same closing-table row as `page` |
| `last` | `list_sources` result object | proposed | 0.8 | design/batch-and-bulk-follow.md:1117 | batch OD-B1..B24 (delegated) | same closing-table row as `page` |
| `next` | `list_sources` result object | proposed | 0.8 | design/batch-and-bulk-follow.md:1117 | batch OD-B1..B24 (delegated) | same closing-table row as `page` |
| `expiresAt` | `list_sources` result object | proposed | 0.8 | design/batch-and-bulk-follow.md:1117 | batch OD-B1..B24 (delegated) | same closing-table row as `page` |
| `limits` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | `RequestLimits` (Java, internal) and `dataprism.limits.*` (configuration): `limits` in a result is a different thing; `outcome` is also a class name in the orchestration module (`SourceOutcome`), never a result field there |
| `batchSize` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `summary` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `done` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `refused` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `deferred` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `results` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `index` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `outcome` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `code` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `deferredBy` | batch result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1129 | batch OD-B1..B24 (delegated) | same closing-table row as `limits` |
| `DONE` | batch `outcome` values | proposed | 0.9 | design/batch-and-bulk-follow.md:1130 | batch OD-B1..B24 (delegated) | `REFUSED` is reused from the per-source codes with a related meaning at the item level, under a different field name (`outcome` against `status`) so the two never share a code space |
| `DEFERRED` | batch `outcome` values | proposed | 0.9 | design/batch-and-bulk-follow.md:1130 | batch OD-B1..B24 (delegated) | same closing-table row as `DONE` |
| `DEADLINE` | `deferredBy` values (batch: both; bulk: `DEADLINE`) | proposed | 0.9 | design/batch-and-bulk-follow.md:1131 | batch OD-B1..B24 (delegated) | none |
| `RESPONSE_CAP` | `deferredBy` values (batch: both; bulk: `DEADLINE`) | proposed | 0.9 | design/batch-and-bulk-follow.md:1131 | batch OD-B1..B24 (delegated) | same closing-table row as `DEADLINE` |
| `_truncated` | source result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1137 | batch OD-B1..B24 (delegated) | `_linksOmitted`, `_links` (siblings; `reason` and `path` are also generic words used in the catalog result: `path` in `describe_source`, same meaning, a field path) |
| `path` | source result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1137 | batch OD-B1..B24 (delegated) | same closing-table row as `_truncated` |
| `kept` | source result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1137 | batch OD-B1..B24 (delegated) | same closing-table row as `_truncated` |
| `omitted` | source result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1137 | batch OD-B1..B24 (delegated) | same closing-table row as `_truncated` |
| `reason` | source result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1137 | batch OD-B1..B24 (delegated) | same closing-table row as `_truncated` |
| `next` | source result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1137 | batch OD-B1..B24 (delegated) | same closing-table row as `_truncated` |
| `RECORD_CAP` | `_truncated.reason` values | proposed | 0.9 | design/batch-and-bulk-follow.md:1138 | batch OD-B1..B24 (delegated) | `TOO_MANY_RECORDS`, `RESPONSE_TOO_LARGE` (refusal codes; these are reasons for truncation) |
| `SIZE_CAP` | `_truncated.reason` values | proposed | 0.9 | design/batch-and-bulk-follow.md:1138 | batch OD-B1..B24 (delegated) | same closing-table row as `RECORD_CAP` |
| `_page` | page result field | proposed | 0.9 | design/batch-and-bulk-follow.md:1139 | batch OD-B1..B24 (delegated) | `page` of `list_sources` (a different tool and level; this one is under a source) |
| `path` | page result field | proposed | 0.9 | design/batch-and-bulk-follow.md:1139 | batch OD-B1..B24 (delegated) | same closing-table row as `_page` |
| `offset` | page result field | proposed | 0.9 | design/batch-and-bulk-follow.md:1139 | batch OD-B1..B24 (delegated) | same closing-table row as `_page` |
| `kept` | page result field | proposed | 0.9 | design/batch-and-bulk-follow.md:1139 | batch OD-B1..B24 (delegated) | same closing-table row as `_page` |
| `last` | page result field | proposed | 0.9 | design/batch-and-bulk-follow.md:1139 | batch OD-B1..B24 (delegated) | same closing-table row as `_page` |
| `requested` | `follow_bulk` result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1142 | batch OD-B1..B24 (delegated) | none; `counts` and `exceptionsOmitted` sit per target under `targets` |
| `targets` | `follow_bulk` result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1142 | batch OD-B1..B24 (delegated) | same closing-table row as `requested` |
| `counts` | `follow_bulk` result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1142 | batch OD-B1..B24 (delegated) | same closing-table row as `requested` |
| `exceptionsOmitted` | `follow_bulk` result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1142 | batch OD-B1..B24 (delegated) | same closing-table row as `requested` |
| `invalidLinks` | `follow_bulk` result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1142 | batch OD-B1..B24 (delegated) | same closing-table row as `requested` |
| `exceptions` | `follow_bulk` result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1142 | batch OD-B1..B24 (delegated) | same closing-table row as `requested` |
| `deferredBy` | `follow_bulk` result fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1142 | batch OD-B1..B24 (delegated) | same closing-table row as `requested` |
| `index` | `follow_bulk` exception entry fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1143 | batch OD-B1..B24 (delegated) | `status` (the per-source status of `get_entity_context`, same codes), `target` and `expiresAt` (`_links` entry fields, same meaning), `item` (the position within the `items` of a one-to-many `follow`), `index` (as in the batch result) |
| `item` | `follow_bulk` exception entry fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1143 | batch OD-B1..B24 (delegated) | same closing-table row as `index` |
| `status` | `follow_bulk` exception entry fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1143 | batch OD-B1..B24 (delegated) | same closing-table row as `index` |
| `target` | `follow_bulk` exception entry fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1143 | batch OD-B1..B24 (delegated) | same closing-table row as `index` |
| `link` | `follow_bulk` exception entry fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1143 | batch OD-B1..B24 (delegated) | same closing-table row as `index` |
| `expiresAt` | `follow_bulk` exception entry fields | proposed | 0.9 | design/batch-and-bulk-follow.md:1143 | batch OD-B1..B24 (delegated) | same closing-table row as `index` |
| `SCOPE_READ_BUDGET` | count keys | proposed | 0.9 | design/batch-and-bulk-follow.md:1144 | batch OD-B1..B24 (delegated) | `SCOPE_READ_BUDGET` is an existing refusal code used as a key; `DEFERRED` as in the batch |
| `DEFERRED` | count keys | proposed | 0.9 | design/batch-and-bulk-follow.md:1144 | batch OD-B1..B24 (delegated) | same closing-table row as `SCOPE_READ_BUDGET` |

## 2. Per-source status codes

The contract is seven codes (D-070-3 (a), PLAN.md:130; t190:13). The set is pinned by `SourceStatusCodesTest` against `source-status-codes.txt`. How a client must treat a code it does not recognise is recorded two different ways: section 12 (D1) and OD-200-1. Future codes are registered in this table (t190:14).

| Code | State | Release | Source | Meaning, decision |
|---|---|---|---|---|
| `ANSWERED` | shipped* | 0.7.0* (enum names older; first wire contract D-070-1/3) | orchestration:SourceOutcome.java:21 | the source answered with a record. Docs: docs/tools.md:133. |
| `NO_DATA` | shipped* | 0.7.0* (enum names older; first wire contract D-070-1/3) | orchestration:SourceOutcome.java:23 | the source answered and has nothing for this subject. Docs: docs/tools.md:134. |
| `TIMED_OUT` | shipped* | 0.7.0* (enum names older; first wire contract D-070-1/3) | orchestration:SourceOutcome.java:25 | the source did not answer inside its timeout. Docs: docs/tools.md:135. |
| `FAILED` | shipped* | 0.7.0* (enum names older; first wire contract D-070-1/3) | orchestration:SourceOutcome.java:27 | the source answered with an error. Docs: docs/tools.md:136. |
| `CIRCUIT_OPEN` | shipped* | 0.7.0* (enum names older; first wire contract D-070-1/3) | orchestration:SourceOutcome.java:29 | not called: the source is failing and its breaker is open. Docs: docs/tools.md:137. |
| `SKIPPED_OVER_LIMIT` | shipped* | 0.7.0* (enum names older; first wire contract D-070-1/3) | orchestration:SourceOutcome.java:31 | not called: the request had already reached its source limit. Docs: docs/tools.md:138. |
| `REFUSED` | shipped* | 0.7.0* (enum names older; first wire contract D-070-1/3) | orchestration:SourceOutcome.java:36 | the source answered and scrubbing or validating its record refused; nothing of it is returned (D-070-2 (b)). Docs: docs/tools.md:139. |
| `KEY_UNRESOLVED` | proposed | 0.8 (register in task 200) | design/key-resolvers.md:235 | A key resolver failed or returned nothing usable for a translated link target (key-resolvers). key-resolvers; renaming open: OD-200-3 |
| `DONE`, `DEFERRED` | proposed (a different field) | 0.9 | design/batch-and-bulk-follow.md:1130 | Values of the batch item field `outcome`, kept apart from `status` so the two never share a code space (batch row note). Not status codes. |

Where a status appears in the audit: `sourceSystems` holds `<source>:<STATUS>` entries (for example `<source>:REFUSED`), and a refused source is the disposition `<source>:<refused>` = `REFUSED:<code>` (docs/audit.md:235).

## 3. Codes

**Format rule.** A code that reaches the audit file or a tool result must match `[A-Z][A-Z0-9_]{0,63}`; anything else becomes `INVALID_REFUSAL_CODE` (`RefusalCodes.sanitise`, core:RefusalCodes.java:19). Every proposed code in this register was checked against the same pattern (section 13). Codes belong to *families* by where they surface; the same spelling in two families is a collision unless section 11 says otherwise.

The shipped inventory below comes from two extractions (commands and counts in the Outcome of task 200): every upper-case string literal containing an underscore in `*/src/main/**/*.java`, and the constants of the enum `Code` types that declare codes (`PrivacyClusterRefusal.Code`, `ApprovalRefusedException.Code`). "Docs" says whether the exact string appears in a shipped document under `docs/` outside `docs/plan/`; "no" is a freeze-checklist item (section 15).

### 3.1 Call refusals: scrub, orchestration, security and caller

Surface: the tool result text and the audit `policyDecision` `DENY:<code>` (docs/audit.md:235). A refusal raised while scrubbing one source is shown for that source as `REFUSED:<code>` in the disposition map and does not refuse the call unless every answered source was refused.

| Code | State | Release | Source | Docs | Meaning, note |
|---|---|---|---|---|---|
| `NESTED_LEAF_NOT_SCALAR` | shipped | 0.3.0 | connectors-rest:ConfiguredJsonNestedLeafShapeGuard.java:31 | yes |  |
| `NESTED_FIELD_NOT_STRUCTURED` | shipped | 0.3.0 | connectors-rest:ConfiguredJsonNestedLeafShapeGuard.java:45 | yes |  |
| `UNKNOWN_CONFIGURED_SOURCE` | shipped | 0.1.0 | connectors-rest:ConfiguredJsonScrubbingEngine.java:91 | no |  |
| `MODEL_NOT_EXPOSED` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:93 | no |  |
| `NOT_AN_OBJECT` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:99 | no |  |
| `TOO_DEEP` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:156 | no |  |
| `UNKNOWN_FIELD` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:188 | yes |  |
| `UNDECLARED_FIELD` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:188 | no |  |
| `UNCLASSIFIED_STRUCTURE` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:297 | yes |  |
| `NO_SUBJECT` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:327 | no |  |
| `UNKNOWN_SUBJECT_FIELD` | shipped | 0.1.0 | core:JsonTreeScrubbingEngine.java:363 | no |  |
| `SOURCE_MODEL_NOT_A_RECORD` | shipped | 0.6.0 | core:SourceModels.java:58 | yes |  |
| `GENERALIZE_TYPE_MISMATCH` | shipped | 0.1.0 | core:Generalizer.java:36 | no |  |
| `INVALID_REFUSAL_CODE` | shipped | 0.4.0 | core:RefusalCodes.java:17 | yes |  |
| `NO_TOKEN_SOURCE` | shipped | 0.1.0 | core:ValueTokenSource.java:49 | no |  |
| `AUDIT_UNAVAILABLE` | shipped | 0.3.0 | mcp:AuditUnavailableException.java:29 | yes | the audit could not be written, so the call is refused |
| `EXTERNAL_CORRELATION_ID_REQUIRED` | shipped | 0.5.0 | mcp:DataPrismMcpServer.java:61 | yes |  |
| `EXTERNAL_CORRELATION_ID_INVALID` | shipped | 0.5.0 | mcp:DataPrismMcpServer.java:64 | yes |  |
| `NO_AUTHENTICATED_CALLER` | shipped | 0.1.0 | mcp:GetEntityContextTool.java:86 | yes | the transport supplied no authenticated caller |
| `REQUEST_FAILED` | shipped | 0.4.0 | orchestration:AuditedRefusalException.java:21 | yes | a failure that is not a privacy refusal; the client sees only that the request could not be completed |
| `SCOPE_READ_BUDGET` | shipped | 0.1.0 | orchestration:DefaultContextOrchestrator.java:182 | yes | the subject has been read the scope budget number of times; also what a refused cluster budget read becomes when the cluster call fails |
| `NO_SOURCE_DATA` | shipped | 0.1.0 | orchestration:DefaultContextOrchestrator.java:221 | yes | every source failed, was skipped or had no data |
| `VALIDATION_FAILED` | shipped | 0.1.0 | orchestration:DefaultContextOrchestrator.java:243 | yes | a validator refused a source (per source: `REFUSED:VALIDATION_FAILED`) or the final response; the validator's own code (section 3.6) appears only in the exception message |
| `TOO_MANY_RECORDS` | shipped* | 0.7.0* | orchestration:DefaultContextOrchestrator.java:251 | yes | the call exceeds `dataprism.limits.max-records` (t193); after batch OD-B18 a per-source form is planned |
| `RESPONSE_TOO_LARGE` | shipped* | 0.7.0* | orchestration:DefaultContextOrchestrator.java:255 | yes | the call exceeds `dataprism.limits.max-response-size` (t193); after batch OD-B18 also a per-source `REFUSED:RESPONSE_TOO_LARGE` |
| `MISSING_PRINCIPAL` | shipped | 0.1.0 | security:AuthenticatedCaller.java:50 | no |  |
| `MISSING_CLIENT` | shipped | 0.1.0 | security:AuthenticatedCaller.java:55 | no |  |
| `MISSING_PURPOSE` | shipped | 0.1.0 | security:AuthenticatedCaller.java:60 | no |  |
| `MISSING_CASE` | shipped | 0.1.0 | security:AuthenticatedCaller.java:65 | no |  |
| `MALFORMED_EXPIRY` | shipped | 0.1.0 | security:AuthenticatedCaller.java:130 | no |  |
| `NO_CAPABILITIES` | shipped | 0.1.0 | security:AuthorizationService.java:35 | no | the caller holds no capability |
| `TOOL_NOT_PERMITTED` | shipped | 0.1.0 | security:AuthorizationService.java:38 | yes | the role lacks the tool's capability |
| `UNKNOWN_PURPOSE` | shipped | 0.1.0 | security:PurposeValidator.java:15 | no |  |
| `TOKEN_EXPIRED` | shipped | 0.1.0 | security:ScopeResolver.java:60 | no |  |

Proposed call refusals (refusal codes in the design closing tables, and the task briefs):

| Code | State | Release | Source | Decision | Kind, note |
|---|---|---|---|---|---|
| `CASE_NOT_AUTHORISED` | proposed | 0.8 | t203:49 | 203 (0.8); decision D-1.0-1 | `CaseAuthorizer` returned false. `SecurityRefusedException` from `ScopeResolver.resolve`; audited `DENY:CASE_NOT_AUTHORISED`. |
| `CASE_AUTHORISATION_UNAVAILABLE` | proposed | 0.8 | t203:50 | 203 (0.8); D-1.0-1 | The authorizer threw. The message never carries the exception message. |
| `CLIENT_NOT_ALLOWED` | proposed | 0.8 | t203:52 | 203 (0.8); D-1.0-1 | `AuthorizationDecision.denied`, before capabilities are looked up. Exact, case-sensitive match. |
| `LINK_INVALID` | proposed | 0.8 | design/sealed-links.md:476 | sealed-links OD-1..17 (delegated) | refusal code |
| `LINK_ATTEMPTS_EXHAUSTED` | proposed | 0.8 | design/sealed-links.md:477 | sealed-links OD-1..17 (delegated) | refusal code |
| `SOURCE_SELECTION_REQUIRED` | proposed | 0.8 | design/catalog.md:829 | catalog OD-C1..C19 (delegated) | refusal code. none |
| `SOURCE_SELECTION_TOO_LARGE` | proposed | 0.8 | design/catalog.md:830 | catalog OD-C1..C19 (delegated) | refusal code. `SKIPPED_OVER_LIMIT` (a status, not a refusal) |
| `SOURCE_NOT_AVAILABLE` | proposed | 0.8 | design/catalog.md:831 | catalog OD-C1..C19 (delegated) | refusal code. none |
| `ENTITY_MODEL_NOT_AVAILABLE` | proposed | 0.9 | design/catalog.md:832 | catalog OD-C1..C19 (delegated) | refusal code. none |
| `SOURCE_LABEL_COLLISION` | proposed | 0.8 | design/catalog.md:833 | catalog OD-C1..C19 (delegated) | refusal code. none |
| `BATCH_TOO_LARGE` | proposed | 0.9 | design/batch-and-bulk-follow.md:1132 | batch OD-B1..B24 (delegated) | refusal code. `SOURCE_SELECTION_TOO_LARGE` (catalog; sources, not items) |
| `BULK_TOO_LARGE` | proposed | 0.9 | design/batch-and-bulk-follow.md:1145 | batch OD-B1..B24 (delegated) | refusal code. `BATCH_TOO_LARGE` (sibling) |

### 3.2 Admission codes (oversight) and the cluster codes

Surface: the tool result text and `DENY:<code>`, checked after authorisation and before any source is touched (docs/tools.md:495). Admission fails closed: `ToolCalls.admit` turns any `RuntimeException` into `OVERSIGHT_UNAVAILABLE` (mcp:ToolCalls.java:83).

| Code | State | Release | Source | Docs | Meaning, note |
|---|---|---|---|---|---|
| `CLUSTER_LOCK_TIMEOUT` | shipped* | 0.7.0* | core:ClusterLockTimeoutException.java:13 | yes | a cluster lock was held longer than `dataprism.hazelcast.lock-timeout` (t202) |
| `CLUSTER_OPERATION_REFUSED` | shipped* | 0.7.0* | hazelcast:ClusterOperationRefusedException.java:16 | yes | Hazelcast rejected a lock, lease, index or entry-processor call during approval creation or another lock-taking cluster call (t216:125); see 3.2 |
| `OVERSIGHT_UNAVAILABLE` | shipped | 0.4.0 | mcp:ToolCalls.java:32 | yes | admission could not be evaluated; refused, never let through |
| `APPROVAL_REQUIRED` | shipped | 0.4.0 | security:ToolAdmission.java:106 | yes | a second person must approve; text carries `approvalId=` |
| `APPROVAL_PENDING` | shipped | 0.4.0 | security:ToolAdmission.java:100 | yes | approval requested, not yet decided; text carries `approvalId=` |
| `TOO_MANY_PENDING` | shipped | 0.4.0 | security:ToolAdmission.java:81 | yes | the requester holds the maximum live pending approvals |
| `DATAPRISM_PAUSED` | shipped | 0.4.0 | security:ToolAdmission.java:61 | yes | an operator paused every call |
| `TOOL_PAUSED` | shipped | 0.4.0 | security:ToolAdmission.java:64 | yes | an operator paused this tool |
| `SCOPE_PAUSED` | shipped | 0.4.0 | security:ToolAdmission.java:67 | yes | an operator paused the caller's scope |
| `CALLER_RATE_LIMITED` | shipped | 0.4.0 | security:ToolAdmission.java:73 | yes | the caller used its request allowance |

**`CLUSTER_OPERATION_REFUSED`, as task 216 introduced it.** Meaning: Hazelcast rejected a lock or its lease (for example a configured `hazelcast.lock.max.lease.time.seconds` below the lease the store asks for), or an index or entry-processor call failed, during approval creation. Nothing is reported as a duplicate id and nothing names a key, scope, principal or approval id (t216:125; class hazelcast:ClusterOperationRefusedException.java:16). It is a `PrivacyRefusedException` subclass and so carries the stable code; it lives in the hazelcast module, while its sibling `ClusterLockTimeoutException` lives in core (core:ClusterLockTimeoutException.java:13).

Where it surfaces today, read from the code, not from the brief:

| Path | What the caller sees | Source |
|---|---|---|
| Tool call, approval creation or caller rate limiter | `OVERSIGHT_UNAVAILABLE` (audit `DENY:OVERSIGHT_UNAVAILABLE`). `ToolAdmission.admit` has a catch for `ClusterLockTimeoutException` only; this exception falls into the generic `RuntimeException` catch. | security:ToolAdmission.java:82 and the next catch |
| Tool call, cluster read budget | `SCOPE_READ_BUDGET`. `HazelcastScopeBudget` catches `RuntimeException` around the lock and refuses the read. | hazelcast:HazelcastScopeBudget.java:68 |
| Re-identification request or approval | `REIDENTIFICATION_UNAVAILABLE`. | reidentification:ReidentificationService.java:79 |
| Operator approve or reject | HTTP 503 `OVERSIGHT_UNAVAILABLE`, audited as a denial. | server:OversightOperatorController.java:164 |

So the code is registered, tested and documented only in `docs/multiple-instances.md:341`, but no caller can observe it as a code today. `docs/tools.md` does not list it. The throw sites build the exception without a cause (hazelcast:HazelcastApprovalStore.java:311, hazelcast:ClusterLocks.java:63) and neither they nor `ToolAdmission` log anything, so the original Hazelcast failure is not recoverable from a log. Task 216 recorded the mapping as a migration item for task 201 (t216:174), but that task owns documents only, so the mapping is code that no open task owns. This is OD-200-2 (surface it, and where the class belongs).

Related shipped cluster names that task 216 and task 232 added (all internal to the hazelcast module unless stated): maps `dataprism.approval.id` and `dataprism.approval.pending` (hazelcast:PrivacyCluster.java:81), the metric `dataprism.approval.release.skipped` (section 5), the Hazelcast config property `dataprism.approval.pending.sweep-seconds` (section 6.3), and public constructors `HazelcastApprovalStore(cluster, timeout, PrivacyMetrics)` and `ScopeIdentityIndex(cluster, metrics, Duration)` (t232:253).

### 3.3 Re-identification and approval codes

Surface: the re-identification outcome and the operator HTTP response, audited as `DENY:<code>` by `ReidentificationService` (docs/audit.md `policyDecision` table). The internal `ApprovalRefusedException.Code` constants are mapped onto them and never leave the process.

| Code | State | Release | Source | Docs | Meaning, note |
|---|---|---|---|---|---|
| `REIDENTIFICATION_NOT_PERMITTED` | shipped | 0.4.0 | reidentification:ReidentificationService.java:65 | yes |  |
| `PURPOSE_REQUIRED` | shipped | 0.4.0 | reidentification:ReidentificationService.java:68 | yes |  |
| `PURPOSE_NOT_ALLOWED` | shipped | 0.4.0 | reidentification:ReidentificationService.java:71 | yes |  |
| `REIDENTIFICATION_UNAVAILABLE` | shipped | 0.4.0 | reidentification:ReidentificationService.java:79 | yes |  |
| `APPROVAL_NOT_FOUND` | shipped | 0.4.0 | reidentification:ReidentificationService.java:94 | yes |  |
| `APPROVAL_NOT_PENDING` | shipped | 0.4.0 | reidentification:ReidentificationService.java:102 | yes |  |
| `APPROVAL_EXPIRED` | shipped | 0.4.0 | reidentification:ReidentificationService.java:103 | yes |  |
| `SELF_APPROVAL` | shipped | 0.4.0 | reidentification:ReidentificationService.java:104 | yes |  |
| `NOT_REQUESTER` | shipped | 0.4.0 | reidentification:ReidentificationService.java:131 | yes |  |
| `APPROVAL_NOT_APPROVED` | shipped | 0.4.0 | reidentification:ReidentificationService.java:141 | yes |  |
| `REIDENTIFICATION_NOT_FOUND` | shipped | 0.4.0 | reidentification:ReidentificationService.java:207 | yes |  |
| `REIDENTIFICATION_DISABLED` | shipped | 0.4.0 | server:ReidentificationOperatorController.java:84 | yes |  |

Internal enum `ApprovalRefusedException.Code` (not client-visible), mapped by `ReidentificationService` (reidentification:ReidentificationService.java:102):

| Internal name | State | Release | Source | Maps to |
|---|---|---|---|---|
| `UNKNOWN_APPROVAL` | shipped | 0.4.0 | core:ApprovalRefusedException.java:6 | `APPROVAL_NOT_FOUND` |
| `NOT_PENDING` | shipped | 0.4.0 | core:ApprovalRefusedException.java:6 | `APPROVAL_NOT_PENDING` |
| `EXPIRED` | shipped | 0.4.0 | core:ApprovalRefusedException.java:6 | `APPROVAL_EXPIRED` |
| `SELF_APPROVAL` | shipped | 0.4.0 | core:ApprovalRefusedException.java:6 | `SELF_APPROVAL` |
| `TOO_MANY_PENDING` | shipped | 0.4.0 | core:ApprovalRefusedException.java:6 | `TOO_MANY_PENDING` for tools; `REIDENTIFICATION_UNAVAILABLE` for re-identification (reidentification:ReidentificationService.java:105) |

### 3.4 Operator HTTP surface

Endpoints (`dataprism.operator.enabled`, a separate port and audience): `POST /operator/pause`, `POST /operator/resume`, `GET /operator/state`, `GET /operator/approvals`, `GET /operator/approvals/{id}`, `POST /operator/approvals/{id}/approve`, `POST /operator/approvals/{id}/reject`, `POST /operator/reidentifications`, `POST /operator/reidentifications/{id}/approve`, `GET /operator/reidentifications/{id}` (server:OversightOperatorController.java:74, server:ReidentificationOperatorController.java:50). Error body: `{"code": "<CODE>"}`. Success bodies carry a `status` field with the values below.

| Code | State | Release | Source | Docs | Meaning, note |
|---|---|---|---|---|---|
| `INVALID_REQUEST` | shipped | 0.4.0 | server:ServerSecurityConfiguration.java:106 | yes |  |
| `METHOD_NOT_ALLOWED` | shipped | 0.4.0 | server:OperatorErrorAdvice.java:37 | yes |  |
| `UNSUPPORTED_MEDIA_TYPE` | shipped | 0.4.0 | server:OperatorErrorAdvice.java:42 | no |  |
| `NOT_FOUND` | shipped | 0.4.0 | server:OperatorErrorAdvice.java:47 | yes |  |
| `OPERATOR_ERROR` | shipped | 0.4.0 | server:OperatorErrorAdvice.java:58 | yes |  |
| `PAYLOAD_TOO_LARGE` | shipped | 0.4.0 | server:OperatorErrorController.java:89 | yes |  |
| `APPLIED_AUDIT_UNAVAILABLE` | shipped | 0.4.0 | server:OversightOperatorController.java:202 | yes |  |
| `OPERATOR_IDENTITY_UNAVAILABLE` | shipped | 0.4.0 | server:OversightOperatorController.java:210 | yes |  |
| `UNAUTHENTICATED` | shipped | <=0.6.0 | server:ServerSecurityConfiguration.java:86 | yes | HTTP 401 on the operator and MCP ports |
| `FORBIDDEN` | shipped | <=0.6.0 | server:ServerSecurityConfiguration.java:91 | yes | HTTP 403 on the operator and MCP ports |
| `PAUSED` | shipped (response `status` value) | <=0.6.0 | server:OversightOperatorController.java:89 | yes | success body field `status`; a different vocabulary from the per-source `status` of section 2 |
| `RESUMED` | shipped (response `status` value) | <=0.6.0 | server:OversightOperatorController.java:106 | yes | success body field `status`; a different vocabulary from the per-source `status` of section 2 |
| `APPROVED` | shipped (response `status` value) | <=0.6.0 | server:OversightOperatorController.java:174 | yes | success body field `status`; a different vocabulary from the per-source `status` of section 2 |
| `REJECTED` | shipped (response `status` value) | <=0.6.0 | server:OversightOperatorController.java:191 | yes | success body field `status`; a different vocabulary from the per-source `status` of section 2 |
| `RESOLVED` | shipped (response `status` value) | <=0.6.0 | server:ReidentificationOperatorController.java:108 | yes | success body field `status`; a different vocabulary from the per-source `status` of section 2 |
| `PENDING_APPROVAL` | shipped (response `status` value) | <=0.6.0 | server:ReidentificationOperatorController.java:110 | yes | success body field `status`; a different vocabulary from the per-source `status` of section 2 |

### 3.5 Startup refusals

Surface: `DataPrismConfigurationException` / `PrivacyClusterRefusal` at context start, or a constructor of a plain-Java type; the application does not start. These are not tool results and never reach the audit trail. Docs: `docs/configuration.md` and `docs/multiple-instances.md`.

| Code | State | Release | Source | Docs | Meaning, note |
|---|---|---|---|---|---|
| `AMBIGUOUS_AUDIT_LOCATION` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:481 | yes |  |
| `AUDIT_CHECKPOINT_FILE_UNUSABLE` | shipped | 0.4.0 | spring-boot-autoconfigure:AuditWiring.java:85 | yes |  |
| `AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE` | shipped | 0.4.0 | core:FileAuditCheckpointSink.java:29 | yes |  |
| `AUDIT_EVENT_LISTENERS_NOT_REPLACEABLE` | shipped | 0.6.0 | spring-boot-autoconfigure:AuditWiring.java:47 | yes |  |
| `AUDIT_FIELD_MAPPING_CONFLICT` | shipped | 0.5.0 | core:AuditFieldMapping.java:29 | yes |  |
| `AUDIT_JSON_DIRECTORY_SAME_AS_AUDIT` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:186 | yes |  |
| `AUDIT_JSON_DIRECTORY_UNUSABLE` | shipped | 0.5.0 | spring-boot-autoconfigure:AuditSinkSelection.java:192 | yes |  |
| `AUDIT_JSON_PROJECTION_MISSING` | shipped | 0.6.0 | spring-boot-autoconfigure:AuditSinkSelection.java:113 | yes |  |
| `AUDIT_JSON_PROJECTION_WITHOUT_BUILT_IN_SINK` | shipped | 0.6.0 | spring-boot-autoconfigure:AuditSinkSelection.java:141 | yes |  |
| `AUDIT_JSON_REQUIRES_SEGMENTED_SINK` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:180 | yes |  |
| `AUDIT_RETENTION_BELOW_MINIMUM` | shipped | 0.4.0 | core:AuditRetention.java:40 | yes |  |
| `AUDIT_SINK_BEAN_REQUIRED` | shipped | 0.3.0 | spring-boot-autoconfigure:DataPrismContractValidator.java:88 | yes |  |
| `AUDIT_SINK_FILE_UNUSABLE` | shipped | 0.3.0 | spring-boot-autoconfigure:AuditSinkSelection.java:220 | yes |  |
| `BLANK_PURPOSE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:585 | no |  |
| `BLANK_ROLE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:591 | no |  |
| `BLANK_SOURCE_NAME` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:605 | no |  |
| `CLUSTER_PORT_SHARED` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:291 | yes |  |
| `CLUSTER_SETTINGS_IGNORED` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:235 | yes |  |
| `CORRELATION_MDC_KEY_RESERVED` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:144 | yes |  |
| `CORRELATION_MDC_KEY_WITHOUT_HEADER` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:148 | yes |  |
| `CORRELATION_PATTERN_NOT_APPLICABLE` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:111 | yes |  |
| `CORRELATION_REQUIRED_WITHOUT_HEADER` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:123 | yes |  |
| `CORRELATION_REQUIRES_HTTP_TRANSPORT` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:127 | yes |  |
| `DUPLICATE_CALLER_CLAIM` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:451 | no |  |
| `DUPLICATE_CONFIG_KEY` | shipped | 0.6.0 | core:StrictYaml.java:49 | yes |  |
| `EMPTY_PURPOSE_LIST` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:582 | no |  |
| `EMPTY_REIDENTIFICATION_PURPOSES` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:387 | yes |  |
| `EMPTY_ROLE_CAPABILITIES` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:593 | no |  |
| `FIXTURE_DEVELOPMENT_STDIO_ONLY` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:76 | no |  |
| `FORBIDDEN_PRIVACY_OVERRIDE` | shipped | 0.1.0 | spring-boot-autoconfigure:OrchestrationWiring.java:56 | yes |  |
| `HAZELCAST_TLS_UNSUPPORTED` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:563 | yes |  |
| `HMAC_KEY_UNRESOLVED` | shipped | 0.1.0 | spring-boot-autoconfigure:PropertiesValidation.java:132 | no |  |
| `HMAC_KEY_WEAK` | shipped | 0.1.0 | spring-boot-autoconfigure:PropertiesValidation.java:129 | no |  |
| `INVALID_APPROVAL_QUORUM` | shipped* | 0.7.0* | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:325 | yes |  |
| `INVALID_AUDIT_CHECKPOINT_INTERVAL` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:509 | yes |  |
| `INVALID_AUDIT_ENTITY_TYPE` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:158 | yes |  |
| `INVALID_AUDIT_FIELD_PATH` | shipped | 0.5.0 | core:AuditFieldMapping.java:28 | yes |  |
| `INVALID_AUDIT_FIELD_PRESET` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:174 | yes |  |
| `INVALID_AUDIT_LISTENER_QUEUE_CAPACITY` | shipped | 0.6.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:512 | yes |  |
| `INVALID_AUDIT_REFERENCE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:537 | yes |  |
| `INVALID_AUDIT_ROUTING_VALUE` | shipped | 0.5.0 | core:AuditRouting.java:17 | yes |  |
| `INVALID_AUDIT_WRITER` | shipped | 0.3.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:534 | yes |  |
| `INVALID_CLUSTER_INTERFACE` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:281 | yes |  |
| `INVALID_CLUSTER_MEMBERS` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:251 | yes |  |
| `INVALID_CLUSTER_PORT` | shipped | 0.4.1 | hazelcast:PrivacyClusterRefusal.java:14 (enum constant) | yes | declared as an enum constant, not a string literal; the literal extraction alone misses it |
| `INVALID_CONFIG_BOOLEAN` | shipped | 0.6.0 | core:StrictYaml.java:53 | yes |  |
| `INVALID_CONFIG_SHAPE` | shipped | 0.6.0 | core:StrictYaml.java:55 | yes |  |
| `INVALID_CORRELATION_FORMAT` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:107 | yes |  |
| `INVALID_CORRELATION_HEADER` | shipped | 0.5.0 | connectors-rest:OutboundCorrelationHeader.java:23 | yes |  |
| `INVALID_CORRELATION_MDC_KEY` | shipped | 0.5.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:140 | yes |  |
| `INVALID_CORRELATION_PATTERN` | shipped | 0.5.0 | core:CorrelationIdPolicy.java:45 | yes |  |
| `INVALID_HAZELCAST_TTL` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:552 | yes |  |
| `INVALID_HTTP_PATH` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:624 | no |  |
| `INVALID_JWT_LOCATION` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:436 | no |  |
| `INVALID_KUBERNETES_JOIN` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:254 | yes |  |
| `INVALID_LOCK_TIMEOUT` | shipped* | 0.7.0* | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:557 | yes |  |
| `INVALID_METRICS_REFERENCE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:544 | no |  |
| `INVALID_MODEL_DESCRIPTOR_FILE` | shipped | 0.1.0 | spring-boot-autoconfigure:PrivacyEngineWiring.java:99 | yes |  |
| `INVALID_OPERATOR_ADDRESS` | shipped | 0.4.0 | server:OperatorConnectorConfiguration.java:72 | yes |  |
| `INVALID_OVERSIGHT_LIMIT` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:367 | yes |  |
| `INVALID_REQUEST_LIMITS` | shipped* | 0.7.0* | spring-boot-autoconfigure:LimitsProperties.java:13 | yes |  |
| `INVALID_SCOPE_LIFETIME` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:461 | no |  |
| `INVALID_SOURCE_CREDENTIAL_REFERENCE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:617 | no |  |
| `INVALID_SOURCE_MTLS` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:614 | no |  |
| `INVALID_SOURCE_TIMEOUT` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:611 | no |  |
| `INVALID_SOURCE_URL` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:609 | no |  |
| `INVALID_SOURCE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:607 | no |  |
| `INVALID_TRANSPORT` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:68 | no |  |
| `JWT_DISCOVERY_FAILED` | shipped | 0.1.0 | spring-boot-autoconfigure:JwtDecoderSupport.java:159 | no |  |
| `LEADING_ZERO_CONFIG_NUMBER` | shipped | 0.6.0 | core:StrictYaml.java:57 | yes |  |
| `LITERAL_SECRET_FORBIDDEN` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:469 | no |  |
| `MCP_TRANSPORT_UNAVAILABLE` | shipped | 0.1.0 | spring-boot-autoconfigure:Preflights.java:148 | yes |  |
| `MISSING_APPROVAL_QUORUM` | shipped* | 0.7.0* | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:332 | yes |  |
| `MISSING_AUDIT_FILE_PATH` | shipped | 0.3.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:485 | yes |  |
| `MISSING_AUDIT_SINK` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismContractValidator.java:102 | yes |  |
| `MISSING_AUDIT_WRITER` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:528 | yes |  |
| `MISSING_CALLER_CLAIM` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:442 | no |  |
| `MISSING_CALLER_CONTEXT_EXTRACTOR` | shipped | 0.1.0 | spring-boot-autoconfigure:McpTransportWiring.java:66 | no |  |
| `MISSING_CLUSTER_JOIN` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:245 | yes |  |
| `MISSING_CLUSTER_NAME` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:241 | yes |  |
| `MISSING_CLUSTER_TOPOLOGY` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:546 | yes |  |
| `MISSING_HMAC_KEY_REFERENCE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:463 | no |  |
| `MISSING_IDENTITY_RESOLVER` | shipped | 0.1.0 | spring-boot-autoconfigure:Preflights.java:43 | yes |  |
| `MISSING_JWT_AUDIENCE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:434 | no |  |
| `MISSING_JWT_ISSUER` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:433 | no |  |
| `MISSING_KEY_PROVIDER` | shipped | 0.1.0 | server:ServerIntegrationsConfiguration.java:54 | no |  |
| `MISSING_METRICS_BINDING` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismContractValidator.java:104 | no |  |
| `MISSING_METRICS_SINK` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:539 | no |  |
| `MISSING_OPERATOR_SECURITY` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:402 | yes |  |
| `MISSING_PRIVACY_PROFILE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:459 | no |  |
| `MISSING_REIDENTIFICATION_CONTROLS` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:575 | yes |  |
| `MISSING_ROLE_POLICY` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:588 | no |  |
| `MISSING_SHARED_BUDGET` | shipped | 0.1.0 | spring-boot-autoconfigure:Preflights.java:103 | yes |  |
| `MISSING_SOURCE_ADAPTER` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismContractValidator.java:60 | yes |  |
| `MODEL_DESCRIPTOR_FILE_NOT_FOUND` | shipped | 0.1.0 | spring-boot-autoconfigure:PrivacyEngineWiring.java:87 | no |  |
| `MODEL_DESCRIPTOR_FILE_UNREADABLE` | shipped | 0.1.0 | spring-boot-autoconfigure:PrivacyEngineWiring.java:91 | no |  |
| `NON_STRING_CONFIG_SCALAR` | shipped | 0.6.0 | core:StrictYaml.java:52 | yes |  |
| `NO_REIDENTIFICATION_APPROVER` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:392 | yes |  |
| `NULL_LIKE_CONFIG_SCALAR` | shipped | 0.6.0 | core:StrictYaml.java:56 | yes |  |
| `OPERATOR_AUDIENCE_SHARED` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:407 | yes |  |
| `OPERATOR_PORT_SHARED` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:212 | yes |  |
| `OVERSIGHT_REQUIRES_OPERATOR_SURFACE` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:419 | yes |  |
| `REIDENTIFICATION_INDEX_DISABLED` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:379 | yes |  |
| `REIDENTIFICATION_MODULE_MISSING` | shipped | 0.4.0 | spring-boot-autoconfigure:Preflights.java:77 | yes |  |
| `REIDENTIFICATION_REQUIRES_CLUSTER` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:383 | yes |  |
| `REIDENTIFICATION_REQUIRES_OPERATOR_SURFACE` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:396 | yes |  |
| `RESERVED_CALLER_CLAIM` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:456 | no |  |
| `RESERVED_CLUSTER_NAME` | shipped | 0.4.1 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:243 | yes |  |
| `RETENTION_REQUIRES_CHECKPOINT` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:492 | yes |  |
| `STANDALONE_HTTP_ONLY` | shipped | 0.1.0 | server:ServerIntegrationsConfiguration.java:31 | yes |  |
| `STDIO_DEVELOPMENT_ONLY` | shipped | 0.1.0 | mcp:DataPrismMcpServer.java:108 | yes |  |
| `STDIO_TRANSPORT_UNSUPPORTED` | shipped | 0.1.0 | spring-boot-autoconfigure:McpTransportWiring.java:53 | yes |  |
| `TRAILING_CONFIG_CONTENT` | shipped | 0.6.0 | core:StrictYaml.java:51 | yes |  |
| `UNKNOWN_AUDIT_FIELD` | shipped | 0.5.0 | core:AuditFieldMapping.java:27 | yes |  |
| `UNKNOWN_AUDIT_SINK` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:473 | yes |  |
| `UNKNOWN_CAPABILITY` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:597 | no |  |
| `UNKNOWN_CONFIG_KEY` | shipped | 0.6.0 | core:StrictYaml.java:50 | yes |  |
| `UNKNOWN_METRICS_SINK` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:541 | no |  |
| `UNKNOWN_OVERSIGHT_TOOL` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:360 | yes |  |
| `UNKNOWN_PRIVACY_PROFILE` | shipped | 0.1.0 | spring-boot-autoconfigure:PropertiesValidation.java:118 | no |  |
| `UNRESOLVED_SOURCE_ADAPTER` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismContractValidator.java:70 | yes |  |
| `UNREVIEWED_SOURCE_ADAPTER` | shipped | 0.3.0 | spring-boot-autoconfigure:DataPrismContractValidator.java:76 | no |  |
| `UNSAFE_HAZELCAST_DISCOVERY` | shipped | 0.4.1 | hazelcast:PrivacyClusterRefusal.java:17 (enum constant) | yes | declared as an enum constant, not a string literal; the literal extraction alone misses it |
| `UNSAFE_HAZELCAST_PERSISTENCE` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:560 | yes |  |
| `UNSAFE_MODEL_DESCRIPTOR_UNDECLARED_FIELDS` | shipped | 0.1.0 | spring-boot-autoconfigure:PrivacyEngineWiring.java:108 | no |  |
| `UNSUPPORTED_CLUSTER_JOIN` | shipped | 0.4.1 | spring-boot-autoconfigure:ClusterBackedState.java:83 | yes |  |
| `UNSUPPORTED_CONFIG_YAML` | shipped | 0.6.0 | core:StrictYaml.java:54 | yes |  |
| `UNSUPPORTED_HAZELCAST_TOPOLOGY` | shipped | 0.1.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:548 | yes |  |
| `UNSUPPORTED_IDENTITY_RESOLVER` | shipped | 0.3.0 | spring-boot-autoconfigure:Preflights.java:39 | yes |  |
| `UNSUPPORTED_LOCALE` | shipped | 0.1.0 | spring-boot-autoconfigure:PrivacyEngineWiring.java:121 | no |  |

Proposed startup refusals (closing tables, then task briefs):

| Code | State | Release | Source | Decision | Kind, note |
|---|---|---|---|---|---|
| `LINK_KEY_SHARED` | proposed | 0.8 | design/sealed-links.md:478 | sealed-links OD-1..17 (delegated) | startup refusal code |
| `LINK_KEY_UNRESOLVED` | proposed | 0.8 | design/sealed-links.md:479 | sealed-links OD-1..17 (delegated) | startup refusal code |
| `LINK_KEY_WEAK` | proposed | 0.8 | design/sealed-links.md:480 | sealed-links OD-1..17 (delegated) | startup refusal code |
| `INVALID_LINK_LIFETIME` | proposed | 0.8 | design/sealed-links.md:481 | sealed-links OD-1..17 (delegated) | startup refusal code |
| `INVALID_LINK_LIMIT` | proposed | 0.8 | design/sealed-links.md:482 | sealed-links OD-1..17 (delegated) | startup refusal code |
| `INVALID_CHAINABLE_FIELD` | proposed | 0.8 | design/key-resolvers.md:236 | key-resolvers OD-R1..R10 (delegated) | startup refusal code |
| `DUPLICATE_KEY_RESOLVER` | proposed | 0.8 | design/key-resolvers.md:237 | key-resolvers OD-R1..R10 (delegated) | startup refusal code |
| `INVALID_KEY_RESOLVER` | proposed | 0.8 | design/key-resolvers.md:238 | key-resolvers OD-R1..R10 (delegated) | startup refusal code |
| `INVALID_SOURCE_DESCRIPTION` | proposed | 0.8 | design/catalog.md:834 | catalog OD-C1..C19 (delegated) | startup refusal code. none |
| `SOURCE_DESCRIPTION_LEAKS_NAME` | proposed | 0.8 | design/catalog.md:835 | catalog OD-C1..C19 (delegated) | startup refusal code. none |
| `INVALID_CATALOG_SETTING` | proposed | 0.8 | design/catalog.md:836 | catalog OD-C1..C19 (delegated) | startup refusal code. `INVALID_REQUEST_LIMITS`, `INVALID_LINK_LIMIT` (siblings) |
| `INVALID_SOURCE_NAME` | proposed | from the 0.9 freeze (OD-C18 (c)) | design/catalog.md:837 | catalog OD-C18 (c) | startup refusal code. none |
| `INVALID_PAGING_SETTING` | proposed | 0.8 | design/batch-and-bulk-follow.md:1120 | batch OD-B1..B24 (delegated) | startup refusal code. `INVALID_CATALOG_SETTING`, `INVALID_LINK_LIMIT` (siblings) |
| `PAGING_KEY_REQUIRED` | proposed | 0.8 | design/batch-and-bulk-follow.md:1121 | batch OD-B2 (a); batch OD-B1..B24 (delegated) | startup refusal code (OD-B2 (a)). `LINK_KEY_UNRESOLVED` (siblings) |
| `INVALID_BATCH_SETTING` | proposed | 0.9 | design/batch-and-bulk-follow.md:1135 | batch OD-B1..B24 (delegated) | startup refusal code. `INVALID_REQUEST_LIMITS` (sibling) |
| `INVALID_BULK_SETTING` | proposed | 0.9 | design/batch-and-bulk-follow.md:1148 | batch OD-B1..B24 (delegated) | startup refusal code. `INVALID_BATCH_SETTING` (sibling) |
| `MISSING_CLUSTER_SECURITY_MODE` | proposed | 0.8 | design/cluster-security.md:689 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `UNSUPPORTED_CLUSTER_SECURITY_MODE` | proposed | 0.8 | design/cluster-security.md:690 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `MISSING_CLUSTER_KEY` | proposed | 0.8 | design/cluster-security.md:691 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `INVALID_CLUSTER_KEY` | proposed | 0.8 | design/cluster-security.md:692 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `MISSING_ISOLATION_ATTESTATION` | proposed | 0.8 | design/cluster-security.md:693 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `INVALID_ISOLATION_ATTESTATION` | proposed | 0.8 | design/cluster-security.md:694 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `MISSING_MEMBER_INTERFACE` | proposed | 0.8 | design/cluster-security.md:695 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `UNBOUNDED_MEMBER_INTERFACE` | proposed | 0.8 | design/cluster-security.md:696 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `PUBLIC_MEMBER_INTERFACE` | proposed | 0.8 | design/cluster-security.md:697 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `INVALID_PAUSE_LEASE` | proposed | 0.8 | design/cluster-security.md:698 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `INVALID_UNVERIFIED_MEMBER_POLICY` | proposed | 0.8 | design/cluster-security.md:699 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `UNSAFE_HAZELCAST_SERIALIZATION` | proposed | 0.8 | design/cluster-security.md:700 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `CLUSTER_KEY_MISMATCH` | proposed | 0.8 | design/cluster-security.md:701 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `UNSEALED_CLUSTER_STATE` | proposed | 0.8 | design/cluster-security.md:702 | cluster-security OD-1..16 (delegated; sealed design confirmed) | code |
| `UNSUPPORTED_CASE_AUTHORISATION` | proposed | 0.8 | t210:52 | task 210 brief (D-1.0-1) | `dataprism.security-policy.case-authorisation` has a value other than `token-trusted` |
| `MISSING_CASE_AUTHORIZER` | proposed | 0.8 | t210:54 | task 210 brief (D-1.0-1) | no `CaseAuthorizer` bean and the property unset (D-1.0-1) |
| `CASE_AUTHORISATION_CONFLICT` | proposed | 0.8 | t210:54 | task 210 brief (D-1.0-1) | an application `CaseAuthorizer` bean and the property set |
| `EMPTY_CLIENT_ALLOWLIST` | proposed | 0.8 | t210:56 | task 210 brief | `dataprism.security-policy.allowed-clients` set but empty |
| `BLANK_ALLOWED_CLIENT` | proposed | 0.8 | t210:56 | task 210 brief | a blank entry in the allowlist |
| `AUDIT_HMAC_REQUIRES_HASH_CHAINED` | proposed | 0.8 | t211:44 | task 211 brief (D-1.0-4) | `dataprism.audit.hmac-key` set without `sink=hash-chained` |
| `AUDIT_HMAC_KEY_UNRESOLVED` | proposed | 0.8 | t211:44 | task 211 brief (D-1.0-4) | the key reference resolves to nothing |
| `AUDIT_HMAC_KEY_TOO_SHORT` | proposed | 0.8 | t211:44 | task 211 brief (D-1.0-4) | key material under 32 bytes |
| `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` | proposed | 0.8 | t211:45 | task 211 brief (D-1.0-4) | audit key equals the pseudonymisation key (D-1.0-4) |
| `DUPLICATE_AUDIT_KEY_ID` | proposed | 0.8 | t211:46 | task 211 brief (D-1.0-4) | a retired key under the current key's id |
| `AUDIT_SEGMENT_SIZE_REQUIRES_DIRECTORY` | proposed | 0.8 | t211:49 | task 211 brief (D-1.0-4) | `segment-max-size` without `dataprism.audit.directory` |
| `INVALID_AUDIT_SEGMENT_SIZE` | proposed | 0.8 | t211:49 | task 211 brief (D-1.0-4) | `segment-max-size` under 1MB |

### 3.6 Validation violation codes

A `Violation` code is not a client code: a validator refusal reaches the client as `VALIDATION_FAILED`, and the violation code appears only in the exception message, which is operator-facing (orchestration:DefaultContextOrchestrator.java:245). It is listed because a deployer's custom validator emits the same kind of code and the pattern validator's codes are pinned by tests.

| Code | State | Release | Source | Docs | Meaning, note |
|---|---|---|---|---|---|
| `RAW_SOURCE_VALUE` | shipped | 0.1.0 | validation:RawValueLeakValidator.java:65 | no | a response value equals a raw source value |
| `RESPONSE_TOO_LARGE_TO_SCAN` | shipped | 0.1.0 | validation:SensitivePatternValidator.java:33 | no | the shape scan ran out of depth or size budget; fail closed. Near-collision with the call refusal `RESPONSE_TOO_LARGE` (section 11) |
| `SENSITIVE_PATTERN` | shipped | 0.1.0 | validation:SensitivePatternValidator.java:35 | no | a pattern-shaped sensitive value in a field not classified sensitive (wired into both Spring orchestrators by task 209, 0.7.0*; no opt-out property; t209 Outcome) |

Proposed (task 208, 0.8):

| Code | State | Release | Source | Meaning |
|---|---|---|---|---|
| `FREE_TEXT_RECOGNISED` | proposed | 0.8 | t208:43 | a `FreeTextRecogniser` recognised a classification in a string leaf; response refused, never edited |
| `FREE_TEXT_RECOGNISER_FAILED` | proposed | 0.8 | t208:44 | a recogniser threw or returned null; nothing of the exception is kept |

### 3.7 Audit, checkpoint and retention failure codes

Surface: exceptions of the audit sinks, the retention maintenance log line, the `auditIntegrity` health detail and the four retention metrics (section 5). Never a tool result.

| Code | State | Release | Source | Docs | Meaning, note |
|---|---|---|---|---|---|
| `AUDIT_CHECKPOINT_OPEN_FAILED` | shipped | 0.4.0 | core:FileAuditCheckpointSink.java:52 | no |  |
| `AUDIT_CHECKPOINT_POISONED` | shipped | 0.4.0 | core:FileAuditCheckpointSink.java:71 | no |  |
| `AUDIT_CHECKPOINT_READ_FAILED` | shipped | 0.4.0 | core:FileAuditCheckpointSink.java:101 | no |  |
| `AUDIT_RETENTION_ANCHOR_FAILED` | shipped | 0.4.0 | core:AuditRetention.java:41 | no |  |
| `AUDIT_RETENTION_CHAIN_UNVERIFIED` | shipped | 0.4.0 | core:AuditRetention.java:43 | yes |  |
| `AUDIT_SINK_OPEN_FAILED` | shipped | 0.3.0 | core:FileAuditSink.java:87 | yes |  |
| `AUDIT_SINK_POISONED` | shipped | 0.3.0 | core:FileAuditSink.java:152 | no |  |
| `AUDIT_PROJECTION_FAILED` | shipped | 0.5.0 | core:TeeAuditSink.java:77 | yes |  |
| `AUDIT_RETENTION_FAILED` | shipped | 0.4.0 | spring-boot-autoconfigure:AuditMaintenance.java:71 | no |  |
| `AUDIT_RETENTION_DELETE_FAILED` | shipped | 0.4.0 | spring-boot-autoconfigure:AuditMaintenance.java:83 | no |  |
| `EXTERNAL_CORRELATION_ID_DROPPED` | shipped | 0.5.0 | spring-boot-autoconfigure:JwtCallerContextExtractor.java:86 | yes |  |

### 3.8 Audit-only reason codes (proposed)

Shape C of `sealed-links OD-13`: a reason is written as a `REFUSED:<code>` disposition value and never shown to the client, who gets the uniform `LINK_INVALID` or the uniform catalog refusal. They are audit values, not refusal codes, but they are frozen with the audit format.

| Code | State | Release | Source | Decision | Kind, note |
|---|---|---|---|---|---|
| `LINK_<REASON>` | proposed | 0.8 | design/sealed-links.md:501 | sealed-links OD-13; sealed-links OD-1..17 (delegated) | audit value |
| `CATALOG_UNKNOWN_SOURCE` | proposed | 0.8; the last two 0.9 | design/catalog.md:840 | catalog OD-C1..C19 (delegated) | audit-only reason codes (shape C). `LINK_<REASON>` (task 191, sibling) |
| `CATALOG_SOURCE_NOT_PERMITTED` | proposed | 0.8; the last two 0.9 | design/catalog.md:840 | catalog OD-C1..C19 (delegated) | audit-only reason codes (shape C). same closing-table row as `CATALOG_UNKNOWN_SOURCE` |
| `CATALOG_ENTITY_TYPE_MISMATCH` | proposed | 0.8; the last two 0.9 | design/catalog.md:840 | catalog OD-C1..C19 (delegated) | audit-only reason codes (shape C). same closing-table row as `CATALOG_UNKNOWN_SOURCE` |
| `CATALOG_UNKNOWN_ENTITY_TYPE` | proposed | 0.8; the last two 0.9 | design/catalog.md:840 | catalog OD-C1..C19 (delegated) | audit-only reason codes (shape C). same closing-table row as `CATALOG_UNKNOWN_SOURCE` |
| `CATALOG_NO_PERMITTED_SOURCE` | proposed | 0.8; the last two 0.9 | design/catalog.md:840 | catalog OD-C1..C19 (delegated) | audit-only reason codes (shape C). same closing-table row as `CATALOG_UNKNOWN_SOURCE` |
| `LINK_WRONG_TOOL` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). the twelve `LINK_<REASON>` codes of task 191 (siblings; `LINK_ORIGIN_DRIFT` and `LINK_EXPIRED` are reused from them) |
| `LINK_FILTER_MISMATCH` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). same closing-table row as `LINK_WRONG_TOOL` |
| `LINK_LABELS_CHANGED` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). same closing-table row as `LINK_WRONG_TOOL` |
| `LINK_CATALOG_CHANGED` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). same closing-table row as `LINK_WRONG_TOOL` |
| `BATCH_DEFERRED` | proposed | 0.9 | design/batch-and-bulk-follow.md:1133 | batch OD-B1..B24 (delegated) | audit `DENY:` code for an item not run. none |
| `BULK_DEFERRED` | proposed | 0.9 | design/batch-and-bulk-follow.md:1146 | batch OD-B1..B24 (delegated) | audit `DENY:` code for a link not run. `BATCH_DEFERRED` (sibling) |
| `LINK_MALFORMED` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_TOO_LONG` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_VERSION` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_UNKNOWN_KEY` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_AUTH_FAILED` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_EXPIRED` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_ISSUED_IN_FUTURE` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_TARGET_NOT_PERMITTED` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_RELATION_UNDECLARED` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_ORIGIN_DRIFT` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_DEPTH_EXCEEDED` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>`; reachable only under rejected option (b): OD-200-8 |
| `LINK_PAYLOAD_MISMATCH` | proposed | 0.8 | design/sealed-links.md:250 (the twelve reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |

### 3.9 Extracted string literals that are not codes

Kept here so the extraction can be re-run and reconciled to the last literal (Outcome, command E1).

| Literal | Source | Why it is not a code |
|---|---|---|
| `AT_DEPOT` | load-test:SyntheticSources.java:24 | synthetic value in the load harness |
| `COMPARE_ENTITY_SOURCES` | core:Capability.java:16 | capability name (section 1) |
| `DATAPRISM_LOADTEST_HMAC_KEY` | load-test:ServerProcess.java:29 | environment variable name in the load harness |
| `DESCRIBE_ENTITY_MODEL` | core:Capability.java:17 | capability name (section 1), capability exists, tool never built |
| `EXPOSE_SOURCE_NAMES` | core:Capability.java:14 | capability name (section 1) |
| `GET_ENTITY_CONTEXT` | core:Capability.java:15 | capability name (section 1) |
| `IN_TRANSIT` | load-test:SyntheticSources.java:24 | synthetic value in the load harness |
| `OUT_FOR_DELIVERY` | load-test:SyntheticSources.java:24 | synthetic value in the load harness |
| `PENDING_APPROVAL` | server:ReidentificationOperatorController.java:110 | value of the operator HTTP response field `status` (section 3.4) |
| `PERSON_NAME` | mcp:CompareEntitySourcesTool.java:284 | privacy namespace used as an example in Javadoc |
| `SPRING_PROFILES_ACTIVE` | integration-tests:ExampleApplication.java:125 | environment variable name read by an example launcher |
| `UNCLASSIFIED_ERROR` | load-test:ResponseClassifier.java:21 | result bucket of the load harness, not a product code |
| `NO_DATA` | load-test:SourceStatuses.java:8 | status code, listed in section 2 (the literal that matched is the load harness's mirror of the enum, `SourceStatuses.ALL`) |
| `TIMED_OUT` | load-test:SourceStatuses.java:8 | status code, listed in section 2 (the literal that matched is the load harness's mirror of the enum, `SourceStatuses.ALL`) |
| `CIRCUIT_OPEN` | load-test:SourceStatuses.java:8 | status code, listed in section 2 (the literal that matched is the load harness's mirror of the enum, `SourceStatuses.ALL`) |
| `SKIPPED_OVER_LIMIT` | load-test:SourceStatuses.java:9 | status code, listed in section 2 (the literal that matched is the load harness's mirror of the enum, `SourceStatuses.ALL`) |

## 4. Audit format

### 4.1 Record fields

| # | Field | State | First record version | Release | Source |
|---|---|---|---|---|---|
| 1 | `eventId` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:44 |
| 2 | `timestamp` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:45 |
| 3 | `principalId` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:46 |
| 4 | `clientId` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:47 |
| 5 | `tool` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:48 |
| 6 | `entityType` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:49 |
| 7 | `subjectPseudonym` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:50 |
| 8 | `parameterFingerprint` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:51 |
| 9 | `privacyProfile` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:52 |
| 10 | `scopeId` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:53 |
| 11 | `purpose` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:54 |
| 12 | `caseId` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:55 |
| 13 | `policyDecision` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:56 |
| 14 | `sourceSystems` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:57 |
| 15 | `rejectedArguments` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:58 |
| 16 | `correlationId` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:59 |
| 17 | `instanceId` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:60 |
| 18 | `sequence` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:61 |
| 19 | `previousHash` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:62 |
| 20 | `eventHash` | shipped | record v1 | 0.1.0 (field set of version 1) | core:AuditEvent.java:63 |
| 21 | `recordVersion` | shipped | record v2 | 0.4.0 | core:AuditEvent.java:64 |
| 22 | `fieldDispositions` | shipped | record v2 | 0.4.0 | core:AuditEvent.java:65 |
| 23 | `approvalId` | shipped | record v2 | 0.4.0 | core:AuditEvent.java:66 |
| 24 | `approverId` | shipped | record v2 | 0.4.0 | core:AuditEvent.java:67 |
| 25 | `externalCorrelationId` | shipped | record v3 | 0.5.0 | core:AuditEvent.java:68 |

A version-3 record has exactly these 25 fields; the line's field count must agree with the declared version (20, 24, 25; `FIELD_COUNT_MISMATCH` otherwise) (docs/audit.md:418, pinned by `AuditRecordVersionTest`, `AuditFieldMappingTest`). `CURRENT_VERSION` is 3 (core:AuditEvent.java:71). The canonical JSON field names are the component names (core:AuditFieldMapping.java:35). **Tasks 206, 207 and 211 change none of this**: `AuditEvent`, `AuditRecordFormat` and the record versions are unchanged and nothing written says whether the chain is keyed (t206:45).

### 4.2 `policyDecision` forms

| Form | State | Release | Source | Note |
|---|---|---|---|---|
| `ALLOW` | shipped | 0.4.0 | orchestrator; docs/audit.md:234 | the call was answered |
| `DENY:<code>` | shipped | 0.4.0 | orchestrator, tools, re-identification, operator; docs/audit.md:235 | `<code>` passes `RefusalCodes.sanitise`; the code families are sections 3.1 to 3.4. includes `DENY:CLUSTER_LOCK_TIMEOUT` and the other admission codes |
| `ALLOW:<STAGE>` | shipped | 0.4.0 | re-identification; docs/audit.md:237 | `REQUESTED`, `APPROVED`, `RESOLVED`; the operator surface also writes `ALLOW:READ` (server:OversightOperatorController.java:112) |
| `DENY` (plain), bare `<CODE>` | read only | written by <=0.3.x; still verified | docs/audit.md:239 | never written since 0.4.0 |
| empty value | reserved | never written | docs/audit.md:244 | reserved |

A consumer classifies by prefix and treats an unrecognised value as a denial (docs/audit.md:247). Proposed new forms: none. Proposed new `DENY:` codes: `BATCH_DEFERRED`, `BULK_DEFERRED` (section 3.8), and the call refusals of section 3.1.

### 4.3 Field-disposition map: keys and values

`fieldDispositions` is a map from a key to an action. The value grammar is closed: a `PrivacyAction` name, `REFUSED`, or `REFUSED:<code>` (core:AuditEvent.java:145). Every proposed new value that is not of that shape needs the grammar widened; that is why shape C of `sealed-links OD-13` records only `REFUSED:<code>` values.

| Key or value | Kind | State | Release | Source | Note |
|---|---|---|---|---|---|
| `<source>:<pointer>` | key | shipped | 0.4.0 | orchestration:DefaultContextOrchestrator.java:418 | value is a `PrivacyAction` name (`PASS_THROUGH`, `REDACT`, `REMOVE`, `HASH`, `SYNTHESIZE`, `TOKENIZE`, `GENERALIZE`); never a value |
| `<source>:<refused>` | key | shipped* | 0.7.0* | orchestration:DefaultContextOrchestrator.java:380 | value `REFUSED:<code>` (before 0.7 plain `REFUSED`; D-070-13 (a), PLAN.md:140) |
| `merged:<refused>` | key | shipped | 0.4.0 | orchestration:DefaultContextOrchestrator.java:265 | value `REFUSED`; marks every refusal that is not a per-source refusal |
| `REFUSED`, `REFUSED:<code>` | value | shipped / shipped* | 0.4.0 / 0.7.0* | core:AuditEvent.java:74 | `<code>` matches `[A-Z][A-Z0-9_]{0,63}` |

Proposed disposition keys and values, taken from the closing tables:

| Name | State | Release | Source | Decision | Note |
|---|---|---|---|---|---|
| `link` | proposed | 0.8 | design/sealed-links.md:483 | sealed-links OD-1..17 (delegated) | audit disposition key (values `INVALID:<reason>`). Under shape C the value is `REFUSED:LINK_<REASON>`; `INVALID:<reason>` in the closing table is stale (D5) |
| `link:id` | proposed; withdrawn by sealed-links OD-13 (C) | 0.8 | design/sealed-links.md:484 | sealed-links OD-1..17 (delegated) | audit disposition key. The body of the document records nothing for this key under shape C (sealed-links.md:251, key-resolvers.md:111); the closing-table row is stale (section 12, D5) |
| `link:attempts` | proposed; withdrawn by sealed-links OD-13 (C) | 0.8 | design/sealed-links.md:485 | sealed-links OD-1..17 (delegated) | audit disposition key. The body of the document records nothing for this key under shape C (sealed-links.md:251, key-resolvers.md:111); the closing-table row is stale (section 12, D5) |
| `links:issued` | proposed; withdrawn by sealed-links OD-13 (C) | 0.8 | design/sealed-links.md:486 | sealed-links OD-1..17 (delegated) | audit disposition key. The body of the document records nothing for this key under shape C (sealed-links.md:251, key-resolvers.md:111); the closing-table row is stale (section 12, D5) |
| `links:omitted` | proposed; withdrawn by sealed-links OD-13 (C) | 0.8 | design/sealed-links.md:487 | sealed-links OD-1..17 (delegated) | audit disposition key. The body of the document records nothing for this key under shape C (sealed-links.md:251, key-resolvers.md:111); the closing-table row is stale (section 12, D5) |
| `resolver:<name>` | proposed; withdrawn by sealed-links OD-13 (C) | 0.8 | design/key-resolvers.md:239 | key-resolvers OD-R1..R10 (delegated) | audit disposition key. The body of the document records nothing for this key under shape C (sealed-links.md:251, key-resolvers.md:111); the closing-table row is stale (section 12, D5) |
| `resolver:<name>:ms` | proposed; withdrawn by sealed-links OD-13 (C) | 0.8 | design/key-resolvers.md:240 | key-resolvers OD-13; key-resolvers OD-R1..R10 (delegated) | audit disposition key (or metric, OD-13). The body of the document records nothing for this key under shape C (sealed-links.md:251, key-resolvers.md:111); the closing-table row is stale (section 12, D5) |
| `catalog` | proposed | 0.8 | design/catalog.md:839 | catalog OD-C1..C19 (delegated) | audit disposition key (values `REFUSED:CATALOG_<REASON>`). `link` (task 191, sibling) |
| `names` | proposed | 0.8 (OD-C2 (a), decided) | design/catalog.md:841 | catalog OD-C2 (a) (owner, 2026-10-09); reopens sealed-links OD-13 shape C for this key | reserved audit disposition key (values `REAL`, `ALIAS`). none |
| `CLUSTER_STATE_TAMPER` | proposed | 0.8 | design/cluster-security.md:717 | cluster-security OD-1..16 (delegated; sealed design confirmed) | audit event |
| `<target>[<i>]:<refused>` | proposed | 0.8 | design/sealed-links.md:256; design/key-resolvers.md:114 | D-070-13 (a) | A refused one-to-many item; `<i>` is the position in the sorted order. Not in any closing table |

**Rule the freeze must pin.** Every fixed disposition key is either colon-free (`link`, `catalog`, `names`) or ends in `:<refused>`; no fixed key has the shape `<word>:<word>`, because `<source>:<pointer>` is already that shape and a source may be named `link`, `links` or `resolver` (grammar `[a-z][a-z0-9-]{0,63}`, catalog OD-C18). The withdrawn rows `link:id`, `link:attempts`, `links:issued`, `links:omitted` and `resolver:<name>` would have broken the rule; `merged:<refused>` follows it because `<refused>` cannot be a pointer.

### 4.4 Other audit-format names

| Name | State | Release | Source | Note |
|---|---|---|---|---|
| record separator `0x1F`, line format, `GENESIS` head, length-prefixed hash encoding | shipped | 0.4.0 (v2 encoding) | docs/audit.md:427 | unchanged by every proposal |
| checkpoint `Kind`: `BOOT`, `PERIODIC`, `SHUTDOWN`, `RETENTION_ANCHOR` | shipped | 0.4.0 | core:AuditCheckpoint.java:36 | task 206 keeps checkpoint shape; task 207 writes `RETENTION_ANCHOR` per day |
| segment file `audit-YYYY-MM-DD.log` | shipped | 0.4.0 | core:SegmentedFileAuditSink.java:38 | a file name is part of the format: the verifier and retention parse it |
| segment file `audit-YYYY-MM-DD.NNNN.log` (index from `0001`) | proposed | 0.8 | t207:37 | only when `dataprism.audit.segment-max-size` is set; `segmentDate(Path)` accepts both forms |
| verifier exit codes 0 to 5 | shipped | 0.4.0 to 0.5.0 (not traced individually) | docs/audit.md:284 | task 206 adds none: an unreadable key file uses the existing `EXIT_UNREADABLE_INPUT` (t206:53) |
| verifier CLI flags `--checkpoints`, `--min-retention`, `--help`/`-h` | shipped | 0.4.0 (not traced per flag) | core:AuditChainVerifierCli.java:96 |  |
| verifier CLI flags `--hmac-key-file <path>` (repeatable), `--allow-unkeyed-writers`; key file format "key id and base64 key" | proposed | 0.8 | t206:53 | task 206; D-1.0-4. The CLI never prints key material |
| `AuditChainKey`, `AuditChainMode` (`unkeyed()`, `keyed(AuditChainKey)`), `AuditEventHash.compute(AuditEvent, AuditChainKey)`, a new `AuditChainVerifier` entry point `(path, checkpoints, minRetention, keys, allowUnkeyedWriters)` | proposed | 0.8 | t206:46 | public API; `AuditRecorder` full constructor changes in place (no forwarding overload, D-1.0-5) |
| JSON projection field presets `canonical`, `ecs`; `field-names` overrides | shipped | 0.5.0 | spring-boot-autoconfigure:AuditProperties.java:115 | the projection renames and reshapes only; `canonical` names are the component names |
| verifier anomaly type `INTERRUPTED_WRITE_FRAGMENT` | shipped | 0.3.0 | core:AuditChainVerifier.java:704 | printed by the verifier; documented in docs/audit.md |
| verifier anomaly type `DUPLICATE_SEQUENCE` | shipped | 0.3.0 | core:AuditChainVerifier.java:706 | printed by the verifier; not in docs/ |
| verifier anomaly type `UNPARSEABLE_RECORD` | shipped | 0.3.0 | core:AuditChainVerifier.java:708 | printed by the verifier; not in docs/ |
| verifier anomaly type `RETENTION_ANCHOR_REJECTED` | shipped | 0.4.0 | core:AuditChainVerifier.java:710 | printed by the verifier; documented in docs/audit.md |
| verifier anomaly type `VERSION_REGRESSION` | shipped | 0.5.0 | core:AuditChainVerifier.java:712 | printed by the verifier; documented in docs/audit.md |
| verifier anomaly type `FIELD_COUNT_MISMATCH` | shipped | 0.5.0 | core:AuditChainVerifier.java:714 | printed by the verifier; documented in docs/audit.md |
| verifier anomaly type `TORN_CHECKPOINT_LINE` | shipped | 0.6.0 | core:AuditChainVerifier.java:716 | printed by the verifier; documented in docs/audit.md |
| verifier checkpoint finding `TRUNCATED_BEFORE_CHECKPOINT` | shipped | 0.4.0 | core:AuditChainVerifier.java:362 | exit code 5 |
| verifier checkpoint finding `MISSING_WRITER` | shipped | 0.4.0 | core:AuditChainVerifier.java:352 | exit code 5 |

## 5. Metrics

Every metric is one constant of `Metric`, with no free-form tag (core:Metric.java:4); the set is pinned by `PrivacyMetricsTest`. A new metric is a visible diff to that enum and test.

| Metric | State | Release | Source | Note |
|---|---|---|---|---|
| `dataprism.mcp.requests` | shipped | 0.1.0 | core:Metric.java:12 (`MCP_REQUESTS`) |  |
| `dataprism.mcp.denied` | shipped | 0.1.0 | core:Metric.java:13 (`MCP_DENIED`) |  |
| `dataprism.privacy.transformations` | shipped | 0.1.0 | core:Metric.java:14 (`PRIVACY_TRANSFORMATIONS`) |  |
| `dataprism.privacy.validation.failures` | shipped | 0.1.0 | core:Metric.java:15 (`PRIVACY_VALIDATION_FAILURES`) |  |
| `dataprism.identity.cache.hit` | shipped | 0.1.0 | core:Metric.java:16 (`IDENTITY_CACHE_HIT`) |  |
| `dataprism.identity.cache.miss` | shipped | 0.1.0 | core:Metric.java:17 (`IDENTITY_CACHE_MISS`) |  |
| `dataprism.source.latency` | shipped | 0.1.0 | core:Metric.java:18 (`SOURCE_LATENCY`) |  |
| `dataprism.source.errors` | shipped | 0.1.0 | core:Metric.java:19 (`SOURCE_ERRORS`) |  |
| `dataprism.identity.collision` | shipped | 0.1.0 | core:Metric.java:20 (`IDENTITY_COLLISION`) |  |
| `dataprism.privacy.failclosed` | shipped | 0.1.0 | core:Metric.java:21 (`PRIVACY_FAILCLOSED`) |  |
| `dataprism.privacy.source.withheld` | shipped* | 0.7.0* | core:Metric.java:22 (`PRIVACY_SOURCE_WITHHELD`) | counts each source withheld from a returned response (D-070-2); `failclosed` counts refused calls only |
| `dataprism.reidentification` | shipped | 0.1.0 | core:Metric.java:23 (`REIDENTIFICATION`) | same string as the map `dataprism.reidentification` and the configuration group `dataprism.reidentification` (section 11, C1) |
| `dataprism.approval.release.skipped` | shipped* | 0.7.0* | core:Metric.java:24 (`APPROVAL_RELEASE_SKIPPED`) | counts approval pending-slot or id-claim releases skipped after a commit or delete because the creation token no longer matched (t232:186) |
| `dataprism.audit.retention.unverified` | shipped | 0.4.0 | core:Metric.java:26 (`AUDIT_RETENTION_CHAIN_UNVERIFIED`) |  |
| `dataprism.audit.retention.anchor_failed` | shipped | 0.4.0 | core:Metric.java:27 (`AUDIT_RETENTION_ANCHOR_FAILED`) |  |
| `dataprism.audit.retention.delete_failed` | shipped | 0.4.0 | core:Metric.java:28 (`AUDIT_RETENTION_DELETE_FAILED`) |  |
| `dataprism.audit.retention.failed` | shipped | 0.4.0 | core:Metric.java:29 (`AUDIT_RETENTION_FAILED`) | the four retention metrics are one constant per failure code because `PrivacyMetrics` takes no tag; spelled with underscores (`anchor_failed`, `delete_failed`) unlike the rest |

Proposed metrics (closing tables):

| Metric | State | Release | Source | Decision | Kind, note |
|---|---|---|---|---|---|
| `dataprism.links.keys.loaded` | proposed | 0.8 | design/sealed-links.md:503 | sealed-links OD-1..17 (delegated) | metric (gauge, one series per held link key id, value 1) |
| `dataprism.catalog.source-names.nonconforming` | proposed | 0.8 | design/catalog.md:838 | catalog OD-C1..C19 (delegated) | metric (gauge, count of non-conforming source names). `dataprism.catalog.descriptions.generated` (sibling) |
| `dataprism.catalog.descriptions.generated` | proposed | 0.8 | design/catalog.md:846 | catalog OD-C1..C19 (delegated) | metric (gauge). `dataprism.links.keys.loaded` (task 191, sibling) |
| `dataprism.paging.pages` | proposed | 0.8 | design/batch-and-bulk-follow.md:1124 | batch OD-B1..B24 (delegated) | metric (counter, closed labels). `dataprism.links.keys.loaded` (sibling group) |
| `dataprism.batch.items` | proposed | 0.9 | design/batch-and-bulk-follow.md:1136 | batch OD-B1..B24 (delegated) | metric (closed label `outcome`). none |
| `dataprism.bulk.links` | proposed | 0.9 | design/batch-and-bulk-follow.md:1149 | batch OD-B1..B24 (delegated) | metric (closed label `status`). none |
| `dataprism.cluster.seal.tamper` | proposed | 0.8 | design/cluster-security.md:703 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.seal.unsupported_format` | proposed | 0.8 | design/cluster-security.md:704 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.seal.salt_rejected` | proposed | 0.8 | design/cluster-security.md:705 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.members.unverified` | proposed | 0.8 | design/cluster-security.md:706 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.pause.fresh` | proposed | 0.8 | design/cluster-security.md:707 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.pause.age_seconds` | proposed | 0.8 | design/cluster-security.md:708 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.pause.conflict` | proposed | 0.8 | design/cluster-security.md:709 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.rotation.accepting_next` | proposed | 0.8 | design/cluster-security.md:710 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.rotation.blocked` | proposed | 0.8 | design/cluster-security.md:711 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.rotation.behind` | proposed | 0.8 | design/cluster-security.md:712 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.seal.entries_under_previous` | proposed | 0.8 | design/cluster-security.md:714 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.lock.timeout` | proposed | 0.8 | design/cluster-security.md:715 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |
| `dataprism.cluster.floor.refusal` | proposed | 0.8 | design/cluster-security.md:716 | cluster-security OD-1..16 (delegated; sealed design confirmed) | metric |

## 6. Configuration

### 6.1 Shipped `dataprism.*` keys

Source of the list: the pinned names file `frozen-property-names.txt` (`PropertyNamesFrozenTest`), 93 leaf keys, plus the one key bound outside it. "Docs" is `full` when the whole key string appears in a shipped document, `name only` when only the last segment appears in `docs/configuration.md` (the document uses relative names inside a group table), and `no` otherwise. The bound groups themselves (`dataprism.audit`, `dataprism.hazelcast`, and so on) are the "bean" lines of the same file.

| Key | State | Release | Source | Type | Default | Docs |
|---|---|---|---|---|---|---|
| `dataprism.audit.checkpoint.file-path` | shipped | 0.4.0 | FZ:3 | String | null | full |
| `dataprism.audit.checkpoint.interval` | shipped | 0.4.0 | FZ:4 | Duration | PT5M | full |
| `dataprism.audit.credential-reference` | shipped | 0.3.0 | FZ:5 | String | null | full |
| `dataprism.audit.directory` | shipped | 0.4.0 | FZ:6 | String | null | full |
| `dataprism.audit.entity-types` | shipped | <=0.6.0 | FZ:7 | List<String> | [] | full |
| `dataprism.audit.file-path` | shipped | 0.3.0 | FZ:8 | String | null | full |
| `dataprism.audit.listeners.queue-capacity` | shipped | <=0.6.0 | FZ:10 | int | 1024 | full |
| `dataprism.audit.output.field-names` | shipped | 0.5.0 | FZ:12 | Map<String,String> | {} | full |
| `dataprism.audit.output.field-preset` | shipped | 0.5.0 | FZ:13 | String | canonical | full |
| `dataprism.audit.output.json-directory` | shipped | 0.5.0 | FZ:14 | String | null | full |
| `dataprism.audit.output.routing.data-stream-dataset` | shipped | 0.5.0 | FZ:16 | String | null | full |
| `dataprism.audit.output.routing.data-stream-namespace` | shipped | 0.5.0 | FZ:17 | String | null | full |
| `dataprism.audit.output.routing.data-stream-type` | shipped | 0.5.0 | FZ:18 | String | null | full |
| `dataprism.audit.output.routing.event-dataset` | shipped | 0.5.0 | FZ:19 | String | null | full |
| `dataprism.audit.retention` | shipped | 0.4.0 | FZ:20 | Period | P6M | full |
| `dataprism.audit.retention-override` | shipped | 0.4.0 | FZ:21 | boolean | false | full |
| `dataprism.audit.sink` | shipped | <=0.6.0 | FZ:22 | String | null | full |
| `dataprism.audit.writer-id` | shipped | <=0.6.0 | FZ:23 | String | null | full |
| `dataprism.correlation.inbound.format` | shipped | 0.5.0 | FZ:26 | String | opaque | full |
| `dataprism.correlation.inbound.header` | shipped | 0.5.0 | FZ:27 | String | null | full |
| `dataprism.correlation.inbound.pattern` | shipped | 0.5.0 | FZ:28 | String | null | full |
| `dataprism.correlation.inbound.required` | shipped | 0.5.0 | FZ:29 | boolean | false | full |
| `dataprism.correlation.mdc-key` | shipped | 0.5.0 | FZ:30 | String | null | full |
| `dataprism.correlation.outbound.header` | shipped | 0.5.0 | FZ:32 | String | null | full |
| `dataprism.hazelcast.approval-minimum-members` | shipped* | 0.7.0* | FZ:34 | Integer | null | full |
| `dataprism.hazelcast.cluster-name` | shipped | <=0.6.0 | FZ:35 | String | null | full |
| `dataprism.hazelcast.identity-cache-ttl` | shipped | <=0.6.0 | FZ:36 | Duration | null | full |
| `dataprism.hazelcast.join.kubernetes.namespace` | shipped | <=0.6.0 | FZ:39 | String | null | full |
| `dataprism.hazelcast.join.kubernetes.service-dns` | shipped | <=0.6.0 | FZ:40 | String | null | full |
| `dataprism.hazelcast.join.kubernetes.service-name` | shipped | <=0.6.0 | FZ:41 | String | null | full |
| `dataprism.hazelcast.join.members` | shipped | <=0.6.0 | FZ:42 | List<String> | [] | full |
| `dataprism.hazelcast.join.mode` | shipped | <=0.6.0 | FZ:43 | String | null | full |
| `dataprism.hazelcast.lock-timeout` | shipped* | 0.7.0* | FZ:44 | Duration | PT2S | full |
| `dataprism.hazelcast.map-store-enabled` | shipped | <=0.6.0 | FZ:45 | boolean | false | full |
| `dataprism.hazelcast.member.interface` | shipped | <=0.6.0 | FZ:47 | String | null | full |
| `dataprism.hazelcast.member.port` | shipped | <=0.6.0 | FZ:48 | Integer | null | full |
| `dataprism.hazelcast.persistence-enabled` | shipped | <=0.6.0 | FZ:49 | boolean | false | full |
| `dataprism.hazelcast.reidentification-controls-reference` | shipped | 0.4.0 | FZ:50 | String | null | full |
| `dataprism.hazelcast.reidentification-enabled` | shipped | 0.4.0 | FZ:51 | boolean | false | full |
| `dataprism.hazelcast.tls-key-reference` | shipped | <=0.6.0 | FZ:52 | String | null | full |
| `dataprism.hazelcast.tls-trust-reference` | shipped | <=0.6.0 | FZ:53 | String | null | full |
| `dataprism.hazelcast.topology` | shipped | 0.4.0 | FZ:54 | String | null | full |
| `dataprism.identity.resolver` | shipped | 0.3.0 | FZ:56 | String | null | full |
| `dataprism.limits.max-concurrency` | shipped* | 0.7.0* | FZ:58 | int | 4 | full |
| `dataprism.limits.max-records` | shipped* | 0.7.0* | FZ:59 | int | 500 | full |
| `dataprism.limits.max-response-size` | shipped* | 0.7.0* | FZ:60 | org.springframework.util.unit.DataSize | 524288B | full |
| `dataprism.limits.max-sources` | shipped* | 0.7.0* | FZ:61 | int | 8 | full |
| `dataprism.limits.per-source-timeout` | shipped* | 0.7.0* | FZ:62 | Duration | PT3S | full |
| `dataprism.limits.scope-read-budget` | shipped* | 0.7.0* | FZ:63 | int | 100 | full |
| `dataprism.metrics.registry-reference` | shipped | <=0.6.0 | FZ:65 | String | null | full |
| `dataprism.metrics.sink` | shipped | <=0.6.0 | FZ:66 | String | null | full |
| `dataprism.operator.address` | shipped | <=0.6.0 | FZ:68 | String | null | full |
| `dataprism.operator.enabled` | shipped | 0.4.0 | FZ:69 | boolean | false | full |
| `dataprism.operator.port` | shipped | <=0.6.0 | FZ:70 | Integer | null | full |
| `dataprism.operator.required-audience` | shipped | <=0.6.0 | FZ:71 | String | null | full |
| `dataprism.operator.required-scope` | shipped | <=0.6.0 | FZ:72 | String | null | name only |
| `dataprism.oversight.approval-required-tools` | shipped | <=0.6.0 | FZ:74 | List<String> | [] | full |
| `dataprism.oversight.approval-ttl` | shipped | <=0.6.0 | FZ:75 | Duration | PT15M | name only |
| `dataprism.oversight.caller-rate-limit.requests` | shipped | <=0.6.0 | FZ:77 | Integer | null | name only |
| `dataprism.oversight.caller-rate-limit.window` | shipped | <=0.6.0 | FZ:78 | Duration | PT1M | name only |
| `dataprism.oversight.max-pending-per-requester` | shipped | <=0.6.0 | FZ:79 | int | 5 | name only |
| `dataprism.privacy.descriptor-file` | shipped | <=0.6.0 | FZ:81 | String | null | full |
| `dataprism.privacy.hmac-key.environment-variable` | shipped | <=0.6.0 | FZ:83 | String | null | full |
| `dataprism.privacy.hmac-key.key-id` | shipped | <=0.6.0 | FZ:84 | String | null | full |
| `dataprism.privacy.hmac-key.provider-reference` | shipped | <=0.6.0 | FZ:85 | String | null | full |
| `dataprism.privacy.hmac-key.value` | shipped | <=0.6.0 | FZ:86 | String | null | full |
| `dataprism.privacy.locale` | shipped | <=0.6.0 | FZ:87 | String | neutral | full |
| `dataprism.privacy.profile` | shipped | <=0.6.0 | FZ:88 | String | null | full |
| `dataprism.privacy.scope-lifetime` | shipped | <=0.6.0 | FZ:89 | Duration | null | full |
| `dataprism.reidentification.approval-ttl` | shipped | <=0.6.0 | FZ:91 | Duration | PT15M | name only |
| `dataprism.reidentification.enabled` | shipped | 0.4.0 | FZ:92 | boolean | false | full |
| `dataprism.reidentification.four-eyes` | shipped | <=0.6.0 | FZ:93 | boolean | true | name only |
| `dataprism.reidentification.max-pending-per-requester` | shipped | <=0.6.0 | FZ:94 | int | 5 | name only |
| `dataprism.reidentification.purposes` | shipped | <=0.6.0 | FZ:95 | List<String> | [] | name only |
| `dataprism.reidentification.roles` | shipped | <=0.6.0 | FZ:96 | Map<String,Set<enum[REQUEST, APPROVE]>> | {} | full |
| `dataprism.security.caller-claims.investigation` | shipped | <=0.6.0 | FZ:99 | String | null | full |
| `dataprism.security.caller-claims.principal` | shipped | <=0.6.0 | FZ:100 | String | null | full |
| `dataprism.security.caller-claims.roles` | shipped | <=0.6.0 | FZ:101 | String | null | full |
| `dataprism.security.jwt.audience` | shipped | 0.4.0 | FZ:103 | String | null | full |
| `dataprism.security.jwt.issuer` | shipped | <=0.6.0 | FZ:104 | String | null | full |
| `dataprism.security.jwt.issuer-discovery-uri` | shipped | <=0.6.0 | FZ:105 | String | null | full |
| `dataprism.security.jwt.jwk-set-uri` | shipped | 0.1.0 | FZ:106 | String | null | full |
| `dataprism.security-policy.purposes` | shipped | <=0.6.0 | FZ:108 | List<String> | [] | full |
| `dataprism.security-policy.roles` | shipped | <=0.6.0 | FZ:109 | Map<String,List<String>> | {} | full |
| `dataprism.sources` | shipped | 0.1.0 | FZ:110 | Map<String,bean> | {} | full |
| `dataprism.sources.*.base-url` | shipped | <=0.6.0 | FZ:111 | String | null | full |
| `dataprism.sources.*.mtls.key-reference` | shipped | <=0.6.0 | FZ:113 | String | null | full |
| `dataprism.sources.*.mtls.trust-reference` | shipped | <=0.6.0 | FZ:114 | String | null | full |
| `dataprism.sources.*.service-credential-reference` | shipped | <=0.6.0 | FZ:115 | String | null | full |
| `dataprism.sources.*.timeout` | shipped | <=0.6.0 | FZ:116 | Duration | null | full |
| `dataprism.transport.fixture-development` | shipped | 0.3.0 | FZ:118 | boolean | false | full |
| `dataprism.transport.http.path` | shipped | <=0.6.0 | FZ:120 | String | /mcp | full |
| `dataprism.transport.mode` | shipped | <=0.6.0 | FZ:121 | enum[HTTP, STDIO] | HTTP | full |
| `dataprism.json-sources.config-location` | shipped | 0.1.0 | connectors-rest:ConfiguredJsonSourcesAutoConfiguration.java:98 | String | null | full |

The release column is a lower bound: `<=0.6.0` means the pinned list or the configuration page first names it in 0.6.0. The seven keys added in 0.7.0* are `dataprism.limits.*` (six, D-070-4 (a), t189) and `dataprism.hazelcast.lock-timeout` and `dataprism.hazelcast.approval-minimum-members` (t202; D-070-15, D-070-17). `dataprism.sources.*.…` is written with `*` in the pinned list and `<name>` in `docs/configuration.md`; the register treats them as one pattern. Spelled out, the documents' forms are `dataprism.sources.<name>` with `dataprism.sources.<name>.base-url`, `dataprism.sources.<name>.mtls.key-reference`, `dataprism.sources.<name>.mtls.trust-reference`, `dataprism.sources.<name>.service-credential-reference` and `dataprism.sources.<name>.timeout`; `dataprism.audit.output.field-names.<field>` is one entry of the map `dataprism.audit.output.field-names`; `dataprism.audit.output.*` and `dataprism.security-policy.*` are the groups' wildcard spellings.

### 6.2 Enumerated configuration values

| Key | Values | State | Release | Source | Note |
|---|---|---|---|---|---|
| `dataprism.audit.sink` | `slf4j`, `hash-chained`, `approved-sink` | shipped | <=0.6.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:472 | `UNKNOWN_AUDIT_SINK` otherwise |
| `dataprism.audit.output.field-preset` | `canonical`, `ecs` | shipped | 0.5.0 | spring-boot-autoconfigure:AuditProperties.java:115 | `INVALID_AUDIT_FIELD_PRESET` otherwise |
| `dataprism.metrics.sink` | `micrometer` | shipped | <=0.6.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:540 | `UNKNOWN_METRICS_SINK` otherwise |
| `dataprism.hazelcast.topology` | `embedded`, `single-node` | shipped | 0.4.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:547 | `UNSUPPORTED_HAZELCAST_TOPOLOGY` otherwise |
| `dataprism.hazelcast.join.mode` | `tcp-ip`, `kubernetes`, `none` | shipped | <=0.6.0 | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:258 | `UNSUPPORTED_CLUSTER_JOIN` otherwise |
| `dataprism.transport.mode` | `HTTP`, `STDIO` | shipped | <=0.6.0 | spring-boot-autoconfigure:TransportProperties.java:4 | upper case, unlike the lower-case kebab values above (section 13) |
| `dataprism.identity.resolver` | `pass-through` | shipped | 0.3.0 | spring-boot-autoconfigure:Preflights.java:38 | `UNSUPPORTED_IDENTITY_RESOLVER` otherwise |
| `dataprism.correlation.inbound.format` | `opaque`, `traceparent` | shipped | 0.5.0 | spring-boot-autoconfigure:CorrelationProperties.java:19 | `INVALID_CORRELATION_FORMAT` otherwise |
| `dataprism.privacy.profile` | `DEFAULT`, `STRICT` (bundled), plus application-defined | shipped | <=0.6.0 | core:privacy-profiles-default.yaml:17 | `UNKNOWN_PRIVACY_PROFILE` |
| `dataprism.security-policy.case-authorisation` | `token-trusted` | proposed | 0.8 | t210:52 | D-1.0-1; `UNSUPPORTED_CASE_AUTHORISATION` otherwise |
| `dataprism.hazelcast.security.mode` | `sealed` only (`isolation-only` not allowed) | proposed | 0.8 | design/cluster-security.md:480 | cluster-security OD-2 (i) decided; a key with one legal value: OD-200-9 |
| `dataprism.hazelcast.security.isolation.attestation` | `network-policy`, `private-network`, `mesh-mtls`, `wireguard`, `ipsec`, `none-acknowledged` | proposed | 0.8 | design/cluster-security.md:495 | an attestation, not a verification |
| `dataprism.hazelcast.security.unverified-member.action` | `refuse`, `alert` | proposed | 0.8 | design/cluster-security.md:499 | cluster-security OD-8 |

### 6.3 Other shipped property-like names

| Name | State | Release | Source | Note |
|---|---|---|---|---|
| `dataprism.approval.pending.sweep-seconds` | shipped* | 0.7.0* | hazelcast:PendingSweeper.java:35 | A *Hazelcast config property* (`Config.setProperty`), not a Spring property, so `PropertyNamesFrozenTest` does not see it. Integer seconds, 1 to 86400, default 300; documented in docs/multiple-instances.md:424 (t216). Breaks two naming rules: the unit is in the name, and the key lies under a map name (section 13) |
| `hazelcast.lock.max.lease.time.seconds` (Hazelcast's own) | not ours | n/a | docs/multiple-instances.md; t216 | If set below the lease the store asks for (five times `lock-timeout`), approval creation is refused with `CLUSTER_OPERATION_REFUSED` |
| YAML file `sources:` (root keys `sources`, `tls`; entry keys `base-url`, `path`, `timeout`, `correlation-header`; tls keys `key-store`, `key-store-password-env`, `trust-store`, `trust-store-password-env`, `store-type`) | shipped | <=0.6.0 | connectors-rest:RestSources.java:34 | unknown keys refuse (`UNKNOWN_CONFIG_KEY`); kebab case |
| YAML file `json-sources:` (root `json-sources`, `tls`; source keys `base-url`, `path`, `timeout`, `model-version`, `subject-json-path`, `fields`, `nested-catalogues`, `correlation-header`; field keys `identifier`, `nonSensitive`, `classifications`, `namespace`, `action`, `nested`) | shipped | <=0.6.0 | connectors-rest:ConfiguredJsonSources.java:54 | kebab case for source keys, camelCase for field keys (`nonSensitive`): section 13 |
| model descriptor file (root `models`; keys `exposed`, `descendable`, `undeclaredFields`, `fields`; field keys `classifications`, `namespace`, `action`, `subject`, `nonSensitive`, `identifier`) | shipped | <=0.6.0 | core:ModelDescriptors.java:29 | camelCase; `dataprism.privacy.descriptor-file` points at it |

### 6.4 Proposed keys

From the closing tables, grouped by the group they join. `dataprism.limits.*` and `dataprism.audit.*` gain keys inside groups that already ship (the group is pinned, so each new key is a diff to the pinned list). Defaults and validation are in the named document; they are not repeated here.

| Key | State | Release | Source | Decision | Kind, note |
|---|---|---|---|---|---|
| `dataprism.links.enabled` | proposed | 0.8 | design/sealed-links.md:491 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.key-id` | proposed | 0.8 | design/sealed-links.md:492 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.lifetime` | proposed | 0.8 | design/sealed-links.md:493 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.max-per-response` | proposed | 0.8 | design/sealed-links.md:494 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.max-length` | proposed | 0.8 | design/sealed-links.md:495 | batch OD-B12 (owner, 2026-10-19): default 1024, supersedes sealed-links OD-12 (768) | configuration key |
| `dataprism.links.bind-caller` | proposed | 0.8 | design/sealed-links.md:496 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.invalid-attempt-limit` | proposed | 0.8 | design/sealed-links.md:497 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.invalid-attempt-window` | proposed | 0.8 | design/sealed-links.md:498 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.clock-skew` | proposed | 0.8 | design/sealed-links.md:499 | sealed-links OD-1..17 (delegated) | configuration key |
| `dataprism.links.max-chain-depth` | proposed | 0.8 | design/sealed-links.md:500 | sealed-links OD-1..17 (delegated) | configuration key (option b only) |
| `chainable` | proposed | 0.8 | design/key-resolvers.md:233 | key-resolvers OD-R1..R10 (delegated) | model descriptor keys |
| `keyNamespace` | proposed | 0.8 | design/key-resolvers.md:233 | key-resolvers OD-R1..R10 (delegated) | model descriptor keys. same closing-table row as `chainable` |
| `dataprism.key-resolvers.max-targets` | proposed | 0.8 | design/key-resolvers.md:241 | key-resolvers OD-R1..R10 (delegated) | configuration key (default 5) |
| `dataprism.key-resolvers.timeout` | proposed | 0.8 | design/key-resolvers.md:242 | key-resolvers OD-R1..R10 (delegated) | configuration key (default `PT2S`) |
| `dataprism.key-resolvers.budget-cost` | proposed | 0.8 | design/key-resolvers.md:243 | key-resolvers OD-R1..R10 (delegated) | configuration key (default 1) |
| `description` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. `model-version`, `fields` (siblings; no overlap) |
| `entity-types` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. same closing-table row as `description` |
| `description:` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. same closing-table row as `description` |
| `alias-description` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. same closing-table row as `description` |
| `real-description` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. same closing-table row as `description` |
| `input-keys` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. same closing-table row as `description` |
| `cost` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. same closing-table row as `description` |
| `freshness` | proposed | 0.8 | design/catalog.md:821 | catalog OD-C1..C19 (delegated) | `json-sources` entry keys under `description:`. same closing-table row as `description` |
| `dataprism.catalog.unselected-source-limit` | proposed | 0.8 | design/catalog.md:842 | catalog OD-C7 (a) | configuration key (default `8`). `dataprism.limits.max-sources` (OD-C7) |
| `dataprism.catalog.real-names-scopes` | proposed | 0.8 | design/catalog.md:843 | catalog OD-C3 (c) | configuration key (default empty), OD-C3 (c), decided. none |
| `dataprism.paging.list-sources.page-size` | proposed | 0.8 | design/batch-and-bulk-follow.md:1119 | batch OD-B1..B24 (delegated) | configuration keys. `dataprism.links.lifetime` (the link default; this one caps page links) |
| `dataprism.paging.list-sources.max-page-size` | proposed | 0.8 | design/batch-and-bulk-follow.md:1119 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.paging.list-sources.page-size` |
| `dataprism.paging.lifetime` | proposed | 0.8 | design/batch-and-bulk-follow.md:1119 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.paging.list-sources.page-size` |
| `dataprism.batch.default-size` | proposed | 0.9 | design/batch-and-bulk-follow.md:1134 | batch OD-B1..B24 (delegated) | configuration keys. `dataprism.limits.max-response-size`, `per-source-timeout` (the batch keys are the whole-batch counterparts) |
| `dataprism.batch.ceiling` | proposed | 0.9 | design/batch-and-bulk-follow.md:1134 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.batch.default-size` |
| `dataprism.batch.scope-sizes` | proposed | 0.9 | design/batch-and-bulk-follow.md:1134 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.batch.default-size` |
| `dataprism.batch.max-response-size` | proposed | 0.9 | design/batch-and-bulk-follow.md:1134 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.batch.default-size` |
| `dataprism.batch.deadline` | proposed | 0.9 | design/batch-and-bulk-follow.md:1134 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.batch.default-size` |
| `dataprism.limits.max-records-per-source` | proposed | 0.9 | design/batch-and-bulk-follow.md:1140 | batch OD-B1..B24 (delegated) | configuration keys. `max-records`, `max-response-size` (whole-call counterparts); refusals reuse `INVALID_REQUEST_LIMITS` |
| `dataprism.limits.max-source-response-size` | proposed | 0.9 | design/batch-and-bulk-follow.md:1140 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.limits.max-records-per-source` |
| `dataprism.bulk.max-links` | proposed | 0.9 | design/batch-and-bulk-follow.md:1147 | batch OD-B1..B24 (delegated) | configuration keys. none |
| `dataprism.bulk.max-group-elements` | proposed | 0.9 | design/batch-and-bulk-follow.md:1147 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.bulk.max-links` |
| `dataprism.bulk.max-exception-links` | proposed | 0.9 | design/batch-and-bulk-follow.md:1147 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.bulk.max-links` |
| `dataprism.bulk.deadline` | proposed | 0.9 | design/batch-and-bulk-follow.md:1147 | batch OD-B1..B24 (delegated) | configuration keys. same closing-table row as `dataprism.bulk.max-links` |
| `dataprism.hazelcast.security.mode` | proposed | 0.8 | design/cluster-security.md:667 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.key-id` | proposed | 0.8 | design/cluster-security.md:668 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.environment-variable` | proposed | 0.8 | design/cluster-security.md:669 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.provider-reference` | proposed | 0.8 | design/cluster-security.md:670 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.value` | proposed | 0.8 | design/cluster-security.md:671 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.next.key-id` | proposed | 0.8 | design/cluster-security.md:672 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.next.environment-variable` | proposed | 0.8 | design/cluster-security.md:673 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.next.provider-reference` | proposed | 0.8 | design/cluster-security.md:674 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.name-key.key-id` | proposed | 0.8 | design/cluster-security.md:675 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.name-key.environment-variable` | proposed | 0.8 | design/cluster-security.md:676 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.name-key.provider-reference` | proposed | 0.8 | design/cluster-security.md:677 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.name-key.value` | proposed | 0.8 | design/cluster-security.md:678 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.previous.key-id` | proposed | 0.8 | design/cluster-security.md:679 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.previous.environment-variable` | proposed | 0.8 | design/cluster-security.md:680 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.cluster-key.previous.provider-reference` | proposed | 0.8 | design/cluster-security.md:681 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.isolation.attestation` | proposed | 0.8 | design/cluster-security.md:682 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.pause-lease` | proposed | 0.8 | design/cluster-security.md:683 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.clock-skew-tolerance` | proposed | 0.8 | design/cluster-security.md:684 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.lock-timeout` | proposed | 0.8 | design/cluster-security.md:685 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.unverified-member.action` | proposed | 0.8 | design/cluster-security.md:686 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.unverified-member.grace` | proposed | 0.8 | design/cluster-security.md:687 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.hazelcast.security.local-floor.enabled` | proposed | 0.8 | design/cluster-security.md:688 | cluster-security OD-1..16 (delegated; sealed design confirmed) | key |
| `dataprism.security-policy.case-authorisation` | proposed | 0.8 | t210:13 | task 210 brief (D-1.0-1) | String; only value `token-trusted`; no default |
| `dataprism.security-policy.allowed-clients` | proposed | 0.8 | t210:56 | task 210 brief (D-1.0-1) | list of client ids; no default; unset means unrestricted |
| `dataprism.audit.hmac-key.key-id` | proposed | 0.8 | t211:43 | task 211 brief (D-1.0-4) | with `.environment-variable` or `.provider-reference` (exactly one); no inline value; unset means unkeyed |
| `dataprism.audit.hmac-key.environment-variable` | proposed | 0.8 | t211:43 | task 211 brief (D-1.0-4) |  |
| `dataprism.audit.hmac-key.provider-reference` | proposed | 0.8 | t211:43 | task 211 brief (D-1.0-4) |  |
| `dataprism.audit.retired-hmac-keys.<key-id>.environment-variable` | proposed | 0.8 | t211:46 | task 211 brief (D-1.0-4) | verification-only keys; a map keyed by key id |
| `dataprism.audit.retired-hmac-keys.<key-id>.provider-reference` | proposed | 0.8 | t211:46 | task 211 brief (D-1.0-4) |  |
| `dataprism.audit.segment-max-size` | proposed | 0.8 | t211:49 | task 211 brief (D-1.0-4) | Spring `DataSize`; needs `dataprism.audit.directory`; at least 1MB |

### 6.5 Surface the decisions require but no document names yet

These are 0.9 items whose contract does not exist in any closing table. The freeze cannot pin what has no name, so each needs a design before the 0.9 briefs are cut (successor S6).

| Item | Required by | Where it is stated | What is missing |
|---|---|---|---|
| Per-source role authorisation: role-to-source patterns, unmatched sources denied | D-RM-11 (a); built in 0.9; replaces the allow-all `SourceVisibility` of catalog OD-C5 | PLAN.md:32; design/catalog.md:25; docs/plan/roadmap.md:100 | the configuration shape (`dataprism.security-policy` today holds only `purposes` and `roles`, PLAN.md:46), the startup codes, whether `SecurityPolicy` changes, the audit reason for a denial (`CATALOG_SOURCE_NOT_PERMITTED` and `LINK_TARGET_NOT_PERMITTED` exist) |
| Audit-entry size handling | roadmap 0.9 ("Per-source truncation with markers, size caps, and audit-entry size handling") | docs/plan/roadmap.md:101; design/batch-and-bulk-follow.md:1027 | whether it changes the audit record or the disposition map; if it does it changes the format this register says tasks 206, 207 and 211 leave alone |
| Review of `compare_entity_sources` usage | D-RM-13 (a) | PLAN.md:34 | an outcome (keep as is, change the schema, or retire) before the tool schema is frozen |

## 7. SPI types, annotations and public API

### 7.1 Shipped extension interfaces and annotations

Every top-level `public interface` and `public @interface` in `*/src/main` (Outcome, commands E6 and E7). The list is the SPI surface a deployer implements or an annotation a model carries; the freeze pins their method sets, which no test does today (section 15).

| Type | Kind | State | Release | Source | Package |
|---|---|---|---|---|---|
| `PrivacyMetrics` | interface | shipped | 0.1.0 | core:PrivacyMetrics.java:13 | core/metrics |
| `ScopeBudget` | interface | shipped | 0.1.0 | core:ScopeBudget.java:16 | core/limits |
| `IdentityResolver` | interface | shipped | 0.1.0 | core:IdentityResolver.java:22 | core/spi |
| `SyntheticValueSource` | interface | shipped | 0.1.0 | core:SyntheticValueSource.java:17 | core/spi |
| `EntityCorrelationService` | interface | shipped | 0.1.0 | core:EntityCorrelationService.java:24 | core/spi |
| `FieldMetadataResolver` | interface | shipped | 0.1.0 | core:FieldMetadataResolver.java:18 | core/spi |
| `ScrubbingEngine` | interface | shipped | 0.1.0 | core:ScrubbingEngine.java:19 | core/spi |
| `SecretKeyProvider` | interface | shipped | 0.1.0 | core:SecretKeyProvider.java:15 | core/spi |
| `DataSourceAdapter` | interface | shipped | 0.1.0 | core:DataSourceAdapter.java:10 | core/spi |
| `ValueTokenSource` | interface | shipped | 0.1.0 | core:ValueTokenSource.java:33 | core/spi |
| `PrivacyPolicyResolver` | interface | shipped | 0.1.0 | core:PrivacyPolicyResolver.java:14 | core/policy |
| `CallerRateLimiter` | interface | shipped | 0.4.0 | core:CallerRateLimiter.java:7 | core/oversight |
| `ApprovalStore` | interface | shipped | 0.4.0 | core:ApprovalStore.java:13 | core/oversight |
| `OversightState` | interface | shipped | 0.4.0 | core:OversightState.java:9 | core/oversight |
| `AuditEventListener` | interface | shipped | 0.6.0 | core:AuditEventListener.java:27 | audit |
| `AuditCheckpointSink` | interface | shipped | 0.4.0 | core:AuditCheckpointSink.java:10 | audit |
| `AuditSink` | interface | shipped | 0.1.0 | core:AuditSink.java:10 | audit |
| `ContextOrchestrator` | interface | shipped | 0.1.0 | orchestration:ContextOrchestrator.java:7 | orchestration |
| `Vocabulary` | interface | shipped | 0.1.0 | pseudonymisation:Vocabulary.java:19 | pseudonymisation/vocabulary |
| `SubjectLookup` | interface | shipped | 0.4.0 | reidentification:SubjectLookup.java:9 | reidentification |
| `ReidentificationOutcome` | interface | shipped | 0.4.0 | reidentification:ReidentificationOutcome.java:4 | reidentification |
| `OperatorCallers` | interface | shipped | 0.4.0 | server:OperatorCallers.java:13 | server/operator |
| `HmacKeyReferenceResolver` | interface | shipped | 0.1.0 | spring-boot-autoconfigure:HmacKeyReferenceResolver.java:10 | spring/boot |
| `LlmResponseValidator` | interface | shipped | 0.1.0 | validation:LlmResponseValidator.java:22 | validation |
| `@LlmExposedModel` | annotation | shipped | 0.1.0 | annotations:LlmExposedModel.java:21 |  |
| `@SensitiveData` | annotation | shipped | 0.1.0 | annotations:SensitiveData.java:20 |  |
| `@SensitiveObject` | annotation | shipped | 0.1.0 | annotations:SensitiveObject.java:25 |  |
| `@NonSensitive` | annotation | shipped | 0.1.0 | annotations:NonSensitive.java:23 |  |
| `@InternalIdentifier` | annotation | shipped | 0.1.0 | annotations:InternalIdentifier.java:27 |  |
| `@SubjectIdentifier` | annotation | shipped | 0.1.0 | annotations:SubjectIdentifier.java:25 |  |

Beans are the other extension surface: `PrivacyExtensionPoints` classifies 56 bean names as `REPLACEABLE` or `PRIVACY_CRITICAL` (spring-boot-autoconfigure:PrivacyExtensionPoints.java:44), and `AutoConfiguredBeanInventoryTest` pins the list under `bean-inventory/` (56 lines in `bean-methods.txt`). They are not repeated here. Task 209 added one `PRIVACY_CRITICAL` bean on this branch, `dataPrismSensitivePatternValidator` (guard `COMPETING_BEAN_REFUSAL`, spring-boot-autoconfigure:PrivacyExtensionPoints.java:59).

### 7.2 Public constructors and signatures that change before the freeze

These are shipped types whose public signature the proposals change in place, with no deprecated overload left behind (D-1.0-5, "constructors change in place", t203:13). Each is a breaking change in 0.8 that the 0.8 migration page must name.

| Change | From | Source | Decision |
|---|---|---|---|
| `ScopeResolver(PseudonymisationVersion, Duration, PurposeValidator)` becomes `(…, CaseAuthorizer)` | 203 (0.8) | t203:47 | D-1.0-1, D-1.0-5 |
| `AuthorizationService(SecurityPolicy, String, PrivacyScopeType)` becomes `(…, ClientAllowlist)` | 203 (0.8) | t203:52 | D-1.0-1, D-1.0-5 |
| `AuditRecorder` full constructor takes `AuditChainMode`; `AuditRetention` takes a key list and flag; `AuditChainVerifier` gains one entry point | 206 (0.8) | t206:48 | D-1.0-4, D-1.0-5 |
| `SegmentedFileAuditSink` gains a constructor with a maximum segment size | 207 (0.8) | t207:36 | additive |
| `SensitivePatternValidator(SensitiveDataScanner)` is replaced by `(SensitiveDataScanner, List<FreeTextRecogniser>)`; the no-argument constructor stays | 208 (0.8) | t208:42 | breaking; the 209 bean is extended, not duplicated |
| `DataClassification` gains `FREE_TEXT` as its last constant; `privacy-profiles-default.yaml` maps it (`REDACT` in DEFAULT, `REMOVE` in STRICT) | 208 (0.8) | t208:39 | adds an enum constant: a deployer's exhaustive `switch` breaks |
| `HazelcastApprovalStore(cluster, timeout, PrivacyMetrics)`, `ScopeIdentityIndex(cluster, metrics, Duration)`, `PrivacyCluster.embedded(…, OptionalInt)`, `ClusterMembership.defaultApprovalMinimumMembers()` | 202, 232 (0.7.0*) | t232:253 | shipped* in 0.7; old constructors default the new arguments |

### 7.3 Proposed types

| Type | State | Release | Source | Decision | Kind, note |
|---|---|---|---|---|---|
| `KeyResolver` | proposed | 0.8 | design/key-resolvers.md:228 | key-resolvers OD-R1..R10 (delegated) | SPI interface (core) |
| `KeyResolutionException` | proposed | 0.8 | design/key-resolvers.md:229 | key-resolvers OD-R1..R10 (delegated) | SPI exception |
| `ResolutionContext` | proposed | 0.8 | design/key-resolvers.md:230 | key-resolvers OD-R1..R10 (delegated) | SPI record |
| `Resolution` | proposed | 0.8 | design/key-resolvers.md:231 | key-resolvers OD-R1..R10 (delegated) | SPI record (`keys`, `more`) |
| `@Chainable` | proposed | 0.8 | design/key-resolvers.md:232 | key-resolvers OD-R1..R10 (delegated) | annotation |
| `IdentityResolverKeyResolvers` | proposed | 0.8 | design/key-resolvers.md:234 | key-resolvers OD-R1..R10 (delegated) | adapter class |
| `SourceDescription` | proposed | 0.8 | design/catalog.md:815 | catalog OD-C1..C19 (delegated) | SPI record. none |
| `CostHint` | proposed | 0.8 | design/catalog.md:816 | catalog OD-C1..C19 (delegated) | SPI enums. none |
| `FreshnessHint` | proposed | 0.8 | design/catalog.md:816 | catalog OD-C1..C19 (delegated) | SPI enums. same closing-table row as `CostHint` |
| `DataSourceAdapter.description()` | proposed | 0.8 | design/catalog.md:817 | catalog OD-C1..C19 (delegated) | SPI default method. none |
| `SourceCatalog` | proposed | 0.8 | design/catalog.md:818 | catalog OD-C1..C19 (delegated) | startup registry. none |
| `SourceVisibility` | proposed | 0.8 (allow-all until 0.9; OD-C5) | design/catalog.md:819 | catalog OD-C5; catalog OD-C1..C19 (delegated) | permission predicate. none |
| `ListSourcesTool` | proposed | 0.8, 0.8, 0.9 | design/catalog.md:820 | catalog OD-C1..C19 (delegated) | MCP tool classes. `GetEntityContextTool` (pattern, not a clash) |
| `DescribeSourceTool` | proposed | 0.8, 0.8, 0.9 | design/catalog.md:820 | catalog OD-C1..C19 (delegated) | MCP tool classes. same closing-table row as `ListSourcesTool` |
| `DescribeEntityModelTool` | proposed | 0.8, 0.8, 0.9 | design/catalog.md:820 | catalog OD-C1..C19 (delegated) | MCP tool classes. same closing-table row as `ListSourcesTool` |
| `CaseAuthorizer` | proposed | 0.8 | t203:10 | D-1.0-1 | `@FunctionalInterface` in `security`; `boolean` method over `CaseAccessRequest`; `tokenTrusted()` factory |
| `CaseAccessRequest` | proposed | 0.8 | t203:17 | D-1.0-1 | record `principalId`, `clientId`, `caseId`, `purpose`, `roles` |
| `ClientAllowlist` | proposed | 0.8 | t203:17 | D-1.0-1 | final class: `unrestricted()`, `of(Set<String>)` |
| `AuditChainKey` | proposed | 0.8 | t206:19 | D-1.0-4 | final class: key id and at least 32 bytes |
| `AuditChainMode` | proposed | 0.8 | t206:20 | D-1.0-4 | `unkeyed()` and `keyed(AuditChainKey)` |
| `FreeTextRecogniser` | proposed | 0.8 | t208:13 | D-1.0-5 | `@FunctionalInterface` in `validation`; takes one string leaf, returns `Set<DataClassification>` |
| `DataClassification.FREE_TEXT` | proposed | 0.8 | t208:39 | D-1.0-5 | enum constant; not in `SPECIAL_CATEGORIES` |
| `dataPrismCaseAuthorizer` | proposed | 0.8 | t210:53 | D-1.0-1 | bean, `REPLACEABLE` / `NONE`, only for `case-authorisation=token-trusted` |
| `KeyNamespace` | proposed | 0.8 | design/key-resolvers.md:49 | key-resolvers OD-R1..R10 | nested record of `KeyResolver`; used in the SPI body but **absent from the closing table** (section 12, D6); its grammar is `[a-z][a-z0-9-]{0,63}` against 32 bytes in `sealed-links.md` (OD-200-4) |

Superseded tasks 204, 205 and 212 proposed further types and bean behaviour (`SubjectKeyHmac`, `ReidentificationCipher`, a second subject key); none is registered (section 10).

## 8. Cluster artefacts and the link wire format

### 8.1 Hazelcast map names, protection names and health

| Name | Kind | State | Release | Source | Note |
|---|---|---|---|---|---|
| `dataprism.identity` | map | shipped | 0.1.0 | hazelcast:PrivacyCluster.java:63 | scope identity cache (subject to synthetic) |
| `dataprism.reidentification` | map | shipped | 0.1.0 | hazelcast:PrivacyCluster.java:66 | re-identification index |
| `dataprism.budget` | map | shipped | 0.1.0 | hazelcast:PrivacyCluster.java:69 | read budget counts |
| `dataprism.oversight` | map | shipped | 0.4.0 | hazelcast:PrivacyCluster.java:72 | pause state |
| `dataprism.approval` | map | shipped | 0.4.0 | hazelcast:PrivacyCluster.java:75 | approval entries (key `scope NUL id`) |
| `dataprism.approval.id` | map | shipped* | 0.7.0* | hazelcast:PrivacyCluster.java:81 | approval id claims (t216; token added by t232) |
| `dataprism.approval.pending` | map | shipped* | 0.7.0* | hazelcast:PrivacyCluster.java:87 | pending approvals per requester (t216; owner tags by t232) |
| `dataprism.callerrate` | map | shipped | 0.4.0 | hazelcast:PrivacyCluster.java:90 | caller rate counters |
| `dataprism.approval.quorum` | split-brain protection config name | shipped* | 0.7.0* | hazelcast:PrivacyCluster.java:93 | t202; not a map; configured with `approval-minimum-members` |
| `auditIntegrity` | health indicator (bean `auditIntegrityHealthIndicator`) | shipped | <=0.6.0 | spring-boot-autoconfigure:PrivacyExtensionPoints.java:76 | reports retention failure codes (section 3.7) |
| `dataprism/link/v1/seal` | key-derivation labels | proposed | 0.8 | design/sealed-links.md:502 | sealed-links OD-1..17 (delegated) |
| `/fingerprint` | key-derivation labels | proposed | 0.8 | design/sealed-links.md:502 | sealed-links OD-1..17 (delegated); same closing-table row as `dataprism/link/v1/seal` |
| `/caller` | key-derivation labels | proposed | 0.8 | design/sealed-links.md:502 | sealed-links OD-1..17 (delegated); same closing-table row as `dataprism/link/v1/seal` |
| `link key ids held (list)` | health-endpoint field | proposed | 0.8 | design/sealed-links.md:504 | sealed-links OD-1..17 (delegated) |
| `dataprism.membercard` | map | proposed | 0.8 | design/cluster-security.md:713 | cluster-security OD-1..16 (delegated; sealed design confirmed) |
| `clusterSecurity` | health contributor | proposed | 0.8 | design/cluster-security.md:718 | cluster-security OD-1..16 (delegated; sealed design confirmed) |

The values stored in these maps (field order, NUL-joined strings, claim and owner formats with creation tokens; t232:348) are internal. They are not part of the freeze: cluster-security replaces them with sealed values in 0.8, and a mixed old and new cluster is a controlled cut-over (cluster-security OD-5). Map and key names are the exception that does matter: sealed mode moves names under a name key, so a map name is a rolling-upgrade and migration contract.

### 8.2 Sealed-link wire format (proposed, 0.8; extended in 0.9)

Header and envelope fields from `sealed-links.md` section 3 (the layout table is at design/sealed-links.md:175). Task 198 extends the relation byte; this table is the single combined version.

| Field | Value | Source | Note |
|---|---|---|---|
| version byte | `0x01`; any other value is refused before cryptography | design/sealed-links.md:181 |  |
| key id | 1 length byte + up to 16 ASCII `[A-Za-z0-9_-]` | design/sealed-links.md:182 |  |
| expiry | 8 bytes, epoch seconds, in the AAD | design/sealed-links.md:183 |  |
| target label | 1 length byte + up to 64 bytes (alias or real name); empty for a catalog page link (0x02) | design/sealed-links.md:184; design/batch-and-bulk-follow.md:248 | label length caps: OD-200-4 |
| relation byte | 1 byte, cleartext, in the AAD; table below | design/sealed-links.md:185; design/batch-and-bulk-follow.md:611 | 191 documents only `0x00` and `0x01` |
| nonce / tag | 12 bytes / 16 bytes (AES-256-GCM) | design/sealed-links.md:186 |  |
| key-derivation labels | `dataprism/link/v1/seal`, `/fingerprint`, `/caller` | design/sealed-links.md:167, closing table :167 | batch adds no label (batch:243) |
| `dataprism.links.max-length` default | 1024 (was 768); at most 4096; floor 616 (option a/a') | design/sealed-links.md:396 | batch OD-B12 (owner, 2026-10-19), supersedes sealed-links OD-12; `follow` input schema `maxLength` is generated from the configured value (design/sealed-links.md:288) |

| Relation byte | Relation | Issued in | Accepted by | Release | Source |
|---|---|---|---|---|---|
| `0x00` | direct | `_links` | `follow`; `follow_bulk` `links` | 0.8 | design/batch-and-bulk-follow.md:617 (191 also names it) |
| `0x01` | translated | `_links` | `follow`; `follow_bulk` `links` | 0.8 | design/batch-and-bulk-follow.md:618 |
| `0x02` | catalog page | `list_sources` `page.next` | `list_sources` `page` only | 0.8 | design/batch-and-bulk-follow.md:619 |
| `0x03` | collection page | `_truncated[].next` | `follow` only | 0.9 | design/batch-and-bulk-follow.md:620 |
| `0x04` | group | group link of a collection page | `follow_bulk` `group` only | 0.9 | design/batch-and-bulk-follow.md:621 |
| `0x05` to `0xFF` | reserved | none | none: refused as an invalid link | n/a | rule stated here; the design says "any other relation" is invalid (batch:613) |

Closing-table rows for this section (each name appears once in the register):

| Name | Kind | Release | Source | Decision |
|---|---|---|---|---|
| `0x02` | link relation codes | 0.8 (`0x02`), 0.9 (`0x03`, `0x04`) | design/batch-and-bulk-follow.md:1122 | batch OD-B1..B24 (delegated) |
| `0x03` | link relation codes | 0.8 (`0x02`), 0.9 (`0x03`, `0x04`) | design/batch-and-bulk-follow.md:1122 | batch OD-B1..B24 (delegated) |
| `0x04` | link relation codes | 0.8 (`0x02`), 0.9 (`0x03`, `0x04`) | design/batch-and-bulk-follow.md:1122 | batch OD-B1..B24 (delegated) |
| `rel: "page"` | `_links` / `next` relation value | 0.9 | design/batch-and-bulk-follow.md:1123 | batch OD-B1..B24 (delegated) |

## 9. Pre-freeze contracts from tasks 203, 206 to 208, 209 to 214 and the cluster design

Release and status are taken from `docs/plan/reconciliation-2026-10-19.md` (table "Allocation", lines 15 to 22). **These are proposals, not shipped APIs**, except task 209, whose bean behaviour is merged on `plan/0.7.0`. This register does not wait for the 0.8 implementations; each 0.8 task re-checks its names against sections 3 to 8 before it adds them.

| Task | Release | State | Scope | Names it adds or changes | Decision, status source |
|---|---|---|---|---|---|
| 203 | 0.8 | proposed | Case-authorisation SPI and client allowlist | `CaseAuthorizer`, `CaseAccessRequest`, `ClientAllowlist`; codes `CASE_NOT_AUTHORISED`, `CASE_AUTHORISATION_UNAVAILABLE`, `CLIENT_NOT_ALLOWED`; two constructors change (7.2) | D-1.0-1, D-1.0-5 (reconciliation:15) |
| 210 | 0.8 | proposed | Wires 203 | keys `dataprism.security-policy.case-authorisation`, `.allowed-clients`; five startup codes; bean `dataPrismCaseAuthorizer` | D-1.0-1; depends on 203 |
| 206 | 0.8 | proposed | Optional keyed audit chain, verifier and retention | `AuditChainKey`, `AuditChainMode`, keyed `AuditEventHash.compute`, verifier entry point, CLI flags `--hmac-key-file`, `--allow-unkeyed-writers`; **no change to record fields, versions or checkpoint shape** | D-1.0-4; partly reverses the 2026-09-23 decision (t206:11) |
| 207 | 0.8 | proposed | Size-capped audit segments | segment file name `audit-YYYY-MM-DD.NNNN.log`; constructor with maximum size; one `RETENTION_ANCHOR` per day | D-1.0-5 |
| 211 | 0.8 | proposed | Wires 206 and 207 | keys `dataprism.audit.hmac-key.*`, `.retired-hmac-keys.<key-id>.*`, `.segment-max-size`; seven startup codes | D-1.0-4, D-1.0-5 |
| 208 | 0.8 | proposed | `FREE_TEXT` classification and recogniser SPI | `FreeTextRecogniser`, `DataClassification.FREE_TEXT`, violation codes `FREE_TEXT_RECOGNISED`, `FREE_TEXT_RECOGNISER_FAILED`; extends the bean 209 introduced | D-1.0-5 |
| 209 | 0.7 (merged on plan/0.7.0) | shipped* | Wires the existing `SensitivePatternValidator` into both Spring orchestration paths | bean `dataPrismSensitivePatternValidator` (`PRIVACY_CRITICAL`, `COMPETING_BEAN_REFUSAL`); no property; sensitive-shaped values in `@NonSensitive` fields now refuse with `VALIDATION_FAILED` (violation `SENSITIVE_PATTERN`); no new code, key or SPI | t209 Outcome; PLAN.md:57 |
| 213 | 0.8 | docs only | Rotation procedures, migration and controls documentation | no new names; blocked until 215 assigns cluster dependencies | reconciliation:21 |
| 214 | 0.9 | docs only | Data-protection annex from merged controls only | no new names; it states what the controls support and does not close S12 | reconciliation:22 |
| 215 | 0.8 planning | planning | Splits the sealed cluster design into briefs | no new names; every name it implements comes from section 8 and the `cluster-security.md` rows below | reconciliation:17 |
| 204, 205, 212 | superseded | withdrawn | Isolation-only join, plaintext re-identification option, second subject key | names in section 10 | reconciliation:16 (owner, 2026-10-19) |
| cluster design (192) | built in 0.8 under 215's briefs | proposed | Sealed cluster state: 22 `dataprism.hazelcast.security.*` keys, 14 codes, 13 metrics, `dataprism.membercard`, health `clusterSecurity`, audit event `CLUSTER_STATE_TAMPER` | rows in sections 3.5, 4.3, 5, 6.4 and 8.1 | sealed design governs (reconciliation:7); D-1.0-3, D-1.0-7; cluster-security OD-1..OD-16 |
| 202 | 0.7.0* (landed) | shipped* | Approval consume, quorum, lock timeouts | keys `dataprism.hazelcast.lock-timeout`, `.approval-minimum-members`; codes `CLUSTER_LOCK_TIMEOUT`, `INVALID_LOCK_TIMEOUT`, `INVALID_APPROVAL_QUORUM`, `MISSING_APPROVAL_QUORUM`; protection `dataprism.approval.quorum` | D-070-14 to D-070-17 |
| 216 | 0.7.0* (landed) | shipped* | Atomic approval creation | code `CLUSTER_OPERATION_REFUSED`; maps `dataprism.approval.id`, `dataprism.approval.pending` | D-0.7-HZ-1 |
| 232 | 0.7.0* (landed) | shipped* | Creation tokens and bounded cleanup | metric `dataprism.approval.release.skipped`; public constructors with `PrivacyMetrics` / `Duration`; changed internal value formats | D-0.7-HZ-2 |

Tasks 189 (`dataprism.limits.*`), 190 (status codes), 193 (`TOO_MANY_RECORDS`, `RESPONSE_TOO_LARGE`), 194 (key-shape scan) and 197/198 (designs) are covered in the sections above by their names. Task 194 added no name that the extraction finds beyond the existing violation codes.

## 10. Withdrawn and superseded names

Not part of the freeze. Listed so no later brief reuses them with another meaning and so a reviewer can see they were considered.

| Name | State | Source | Why |
|---|---|---|---|
| `dataprism.hazelcast.join.isolated-network` | withdrawn | t212:65 | isolation-only join mode; superseded 2026-10-19 |
| `dataprism.hazelcast.subject-key.*` | withdrawn | t212:19 | a second subject-key configuration; the sealed design uses `dataprism.hazelcast.security.cluster-key.*` and `name-key.*` |
| `ISOLATED_NETWORK_WITHOUT_JOIN` | withdrawn | t212:65 | isolation-only join mode |
| `UNSECURED_CLUSTER_JOIN` | withdrawn | t212:65 | isolation-only join mode |
| `CLUSTER_SUBJECT_KEY_MISMATCH` | withdrawn | t212:73 | second subject key |
| `CLUSTER_SUBJECT_KEY_UNRESOLVED` | withdrawn | t212:68 | second subject key |
| `CLUSTER_KEY_REUSES_PSEUDONYMISATION_KEY` | withdrawn | t212:68 | second subject key; the live variant for the audit key is `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` (211) |
| `MISSING_REIDENTIFICATION_KEY` | withdrawn | t212:71 | plaintext re-identification alternative (task 205) |
| `dataprism.hazelcast.security.lock-timeout` | withdrawn | design/cluster-security.md:498 and :685 | superseded by the shipped `dataprism.hazelcast.lock-timeout` (t202:349); the design rows are stale |
| `isolation-only` value of `dataprism.hazelcast.security.mode` | withdrawn | design/cluster-security.md:480 | cluster-security OD-2 (i): not allowed |
| link:id, link:attempts, links:issued, links:omitted, resolver:<name>, resolver:<name>:ms (audit disposition keys) | withdrawn | design/sealed-links.md:484; design/key-resolvers.md:239 | sealed-links OD-13 (C): counts, ids and timings are metrics, not dispositions |
| INVALID:<reason> (disposition value) | withdrawn | design/sealed-links.md:246 | replaced by `REFUSED:LINK_<REASON>` under OD-13 (C) |
| dataprism.catalog.real-names-purposes, dataprism.catalog.real-names-scope-types | withdrawn | design/catalog.md:785 | rejected options of catalog OD-C3; (c) `real-names-scopes` decided |
| dataprism.paging.key-id, PAGE_INVALID | withdrawn | design/batch-and-bulk-follow.md:1086, :1087 | rejected options of batch OD-B2 and OD-B3: page links use `dataprism.links.key-id` and `LINK_INVALID` |

## 11. Collisions and near-collisions

Found by comparing every name in sections 1 to 8 against every other, exactly and by shared stem (command E9 in the Outcome finds the exact repeats inside the closing tables; the rest was read). "Accepted" means the two meanings live in different kinds or objects and neither can be mistaken for the other in a single field; the rule that makes it safe is stated. "Decision" is a product or naming choice and is in section 14.

| Id | Names | Kinds | Where | Resolution |
|---|---|---|---|---|
| C1 | `dataprism.reidentification` | metric, Hazelcast map, configuration group | core:Metric.java:23; hazelcast:PrivacyCluster.java:66; FZ `dataprism.reidentification.*` | Accepted. Three independent namespaces share the string prefix; a name must be unique within its kind only. Pin each kind separately (metrics: `PrivacyMetricsTest`; keys: the frozen list; map names: no pin today, section 15). |
| C2 | `dataprism.oversight`, `dataprism.identity`, `dataprism.approval`, `dataprism.audit.retention`, `dataprism.approval.pending` | map or metric prefix against configuration group or leaf key | maps in 8.1; metrics in 5; `dataprism.audit.retention` is a leaf key (Period) and the parent of four metrics; `dataprism.approval.pending` is a map and the parent of the Hazelcast property `dataprism.approval.pending.sweep-seconds` | Accepted as C1, except `sweep-seconds`, which is a configuration knob living under a map name and outside the pinned list: OD-200-14. |
| C3 | `CLUSTER_LOCK_TIMEOUT`, `dataprism.hazelcast.lock-timeout`, `dataprism.hazelcast.security.lock-timeout`, `dataprism.cluster.lock.timeout` | refusal code, key, stale key, proposed metric | section 3.2, 6.1, 10, 5 | The shipped key governs; the `security.lock-timeout` rows in the cluster design are stale (t202 superseded it; D7). The code and the metric are different kinds with one meaning on purpose. |
| C4 | `KEY_UNRESOLVED`, `HMAC_KEY_UNRESOLVED`, `LINK_KEY_UNRESOLVED`, `AUDIT_HMAC_KEY_UNRESOLVED` | per-source status; three startup codes | sections 2 and 3.5 | Different fields, near spelling. The 191 and 197 documents ask this register to record both together or rename: OD-200-3 (and the family rule in OD-200-10). |
| C5 | `RESPONSE_TOO_LARGE`, `REFUSED:RESPONSE_TOO_LARGE`, `RESPONSE_TOO_LARGE_TO_SCAN`, `RESPONSE_CAP`, `SIZE_CAP` | call refusal; per-source disposition and result; violation code; two truncation reasons | section 3.1, 3.6, 1.4 | Accepted. After batch OD-B18 (owner, 2026-10-19) the same code is used at call level (`DENY:`) and source level (`REFUSED:`), told apart by field. `TOO_MANY_RECORDS` has the same shape. The change alters what task 193 built (PLAN.md:95) and `docs/tools.md` must say so in 0.9. |
| C6 | `SKIPPED_OVER_LIMIT`, `SOURCE_SELECTION_TOO_LARGE`, `BATCH_TOO_LARGE`, `BULK_TOO_LARGE`, `TOO_MANY_RECORDS`, `TOO_MANY_PENDING` | status; refusal codes | sections 2 and 3 | Accepted. Each limits a different count; the batch and catalog documents say so in their closing tables. |
| C7 | `REFUSED`, `DEFERRED`, `DONE` | status code; batch `outcome` values; bulk count keys | sections 2 and 1.4 | Accepted per the batch document: a different field name (`outcome` against `status`) keeps the code spaces apart. Rule: `DONE` and `DEFERRED` are never `status` values. |
| C8 | `status` | per-source field; `follow_bulk` exception entry field (same codes); operator HTTP body field (other values); the approval record's status | sections 2, 3.4 | Accepted. The operator surface is a separate HTTP API with its own values (`PAUSED`, `RESUMED`, `APPROVED`, `REJECTED`, `RESOLVED`, `PENDING_APPROVAL`). |
| C9 | `link`, `links` | audit key (`link`), `follow` argument, `follow_bulk` result field, configuration group `dataprism.links`, result fields `_links`, `_linksOmitted`; `links` input of `follow_bulk` | sections 1.3, 4.3, 6.4 | Accepted; batch lists it. `link` the audit key is colon-free by the rule of 4.3. |
| C10 | `items`, `itemsTruncated` | request array of `get_entity_contexts`; result field of a one-to-many `follow` | sections 1.3, 1.4 | Accepted per the batch document: different tools, one input and one output. It will read oddly in the schema reference; the tool reference should show both. |
| C11 | `page`, `_page`, `pageSize`, `dataprism.paging.*` | `list_sources` input string, its result object, a per-source result field, a configuration group | sections 1.3, 1.4, 6.4 | Accepted per the batch document. |
| C12 | `limits` | batch result field; configuration group `dataprism.limits`; Java `RequestLimits` | sections 1.4, 6.1 | Accepted. `dataprism.limits.max-records-per-source` and `max-source-response-size` (0.9) extend a group that is pinned, so each is a new diff to the frozen list. |
| C13 | `names` (audit key) and `labels` (result field) | two spellings for one setting (`REAL` or `ALIAS`) | sections 1.4, 4.3 | Decision: OD-200-12. |
| C14 | `namespace` and `keyNamespace` and `valueNamespace` | annotation attribute and descriptor key `namespace` mean `PrivacyNamespace`; `@Chainable(namespace = "parcel-ref")` would mean a key-namespace string | key-resolvers.md:159; annotations `SensitiveData.namespace` | Decision: OD-200-6. |
| C15 | `DROP`, `REFUSE`, `RENAME`, `REDACT` | `UndeclaredFields` enum; the `undeclared` result values of `describe_source` | catalog.md closing table | Accepted per the catalog document: same effect, different type, strings in the response. |
| C16 | `get_entity_context` and `get_entity_contexts` | tool names one letter apart | section 1.1 | Accepted: batch OD-B17 (a) chose it and the roadmap names it. An MCP client that mistypes it gets an unknown-tool error, not a different tool. |
| C17 | `FOLLOW`, `FOLLOW_BULK`, `follow`, `follow_bulk` | capability referenced by 198 but defined by no document | section 1.2 | Decision: OD-200-5. |
| C18 | `OD-1` to `OD-17` in two documents | decision ids | sealed-links.md and cluster-security.md | Accepted with the qualified-reference rule at the top of this register. |
| C19 | `MISSING_PURPOSE` / `PURPOSE_REQUIRED`; `UNKNOWN_PURPOSE` / `PURPOSE_NOT_ALLOWED`; `APPROVAL_NOT_FOUND` / `REIDENTIFICATION_NOT_FOUND`; `NOT_PENDING` / `APPROVAL_NOT_PENDING` (internal) | shipped near-synonyms on different surfaces (MCP versus re-identification) | section 3.1, 3.3 | Decision: OD-200-13. |
| C20 | `SCOPE_READ_BUDGET` as a refusal code and as a batch count key | refusal code; count key | section 3.1 | Accepted per the batch document. |
| C21 | `UNKNOWN_FIELD`, `UNDECLARED_FIELD` and `UNKNOWN_AUDIT_FIELD`, `UNKNOWN_CONFIG_KEY` | scrub refusals against startup refusals | sections 3.1, 3.5 | Accepted: different families by surface. |

No two entries share a name with different meanings except the rows above. The exact-repeat scan inside the closing tables finds 16 repeated names (command E9). Four are different meanings and are rows C7 (`DEFERRED`), C9 (`link`), C10 (`items`) and C11 (`page`); the other twelve carry the same meaning wherever they appear: `cost`, `freshness` (a `json-sources` key and the result field it feeds), `source` (the label, as a result field and as the `describe_source` argument), `keyNamespace`, `targetSources` (catalog and batch agree), `expiresAt`, `kept`, `last`, `next`, `path` (a field path in three results), `index` (a position) and `deferredBy`. The shorthand `.deadline` in the batch table expands to two different keys, `dataprism.batch.deadline` and `dataprism.bulk.deadline`, and the register lists both in full.

## 12. Disagreements between documents, or between a document and the code

Recorded, not resolved by editing: this task may not edit the design documents or the shipped documents. "Governs today" says which side is in force until a decision or a successor task changes it.

| Id | Subject | Where | Resolution, or what governs today |
|---|---|---|---|
| D1 | Unknown status code: "not answered" or "an error" | `docs/tools.md:141` ("Treat any code you do not recognise as 'not answered'"), `SourceResult.java:14`, `t190:13` (the owner decision as first recorded, commit f0fb5663), `design/batch-and-bulk-follow.md:43`, `:499`; against `PLAN.md:130` (D-070-3 summary: "as an error", commit 523fd730, later the same day) | The recorded decision, the shipped Javadoc and the shipped page all say "not answered"; the PLAN.md summary is the outlier. The register cannot tell from the record which the owner meant, so it is OD-200-1. Governs today: "not answered". |
| D2 | Link `max-length` 768 or 1024 | `sealed-links.md:226` (narrative still names 768 as the earlier decision), `:467` (OD-12 row, amended), `batch-and-bulk-follow.md:609`; `PLAN.md:89` | No remaining conflict: the configuration table (`sealed-links.md:396`) already says 1024, with the 768 history kept. OD-B12 (owner, 2026-10-19) governs: default 1024, maximum 4096, floor 616 for option (a'). Registered as 1024 in 8.2. The batch worst-case collection link is 576 bytes, 768 characters, which fits 1024 with a margin of 256 characters. |
| D3 | Label and identifier length caps differ between documents | see table below | OD-200-4. Governs today: nothing is built; the sealed layout (`sealed-links.md`) is the binding wire constraint. |
| D4 | Relation bytes `0x02` to `0x04` | `sealed-links.md:185` documents only `0x00` and `0x01`; `batch-and-bulk-follow.md:617` to `621` has the table of five | No conflict, an extension: the combined table in 8.2 governs once the successor task S1 updates `sealed-links.md`. `0x05` to `0xFF` are reserved and invalid. |
| D5 | Audit disposition rows that shape C no longer records | `sealed-links.md:484` to `:487` and `key-resolvers.md:239` (closing tables) and the failure table of `key-resolvers.md:87` (`FAILED:<REASON>`, `EMPTY`) against `sealed-links.md:251` to `:256` (OD-13 shape C) and `key-resolvers.md:105` | The body of each document is the decision; the closing-table rows, and the audit values in the key-resolvers failure table (`FAILED:<REASON>`, `EMPTY`, `INVALID_OUTPUT`), are stale and are withdrawn here (section 10). Governs: shape C, where a resolver failure appears only as the target's `KEY_UNRESOLVED` status. |
| D6 | `KeyNamespace` is missing from the key-resolvers closing table | `key-resolvers.md:49` (the SPI) against the closing table at `:222` | Registered in 7.3. Also absent from that table: the twelve `LINK_<REASON>` codes (expanded in 3.8). |
| D7 | `dataprism.hazelcast.security.lock-timeout` against the shipped `dataprism.hazelcast.lock-timeout` | `cluster-security.md:498`, `:685` against `t202:349` and `PLAN.md:144` (D-070-17) | The shipped key governs (same default `PT2S`, same range). The design rows and the closing-table row are stale. |
| D8 | Cluster `security.mode` allows `isolation-only` only if OD-2 allows it; OD-2 (i) does not | `cluster-security.md:480` against `:795` | The key would have one legal value (`sealed`); OD-200-9. Governs: sealed only. |
| D9 | `CLUSTER_OPERATION_REFUSED` is registered and documented in one page but cannot be seen by any caller | `t216:174` (Migration note: "Map the new code in `ToolAdmission`…") against `security:ToolAdmission.java:84` and `docs/tools.md` | Section 3.2. Governs: the generic codes. OD-200-2. |
| D10 | `UNSAFE_HAZELCAST_DISCOVERY` and `INVALID_CLUSTER_PORT` are shipped but not string literals | `PrivacyClusterRefusal.java:14`, `:17` | Registered in 3.5. A literal-only extraction misses them, which is why the Outcome runs a second extraction (command E2). |
| D11 | The `FOLLOW` capability is cited, not defined | `batch-and-bulk-follow.md:1091` against all of `sealed-links.md` | OD-200-5. |
| D12 | Option-(b)-only names remain in the 0.8 tables although option (a') was adopted | `sealed-links.md:500` (`dataprism.links.max-chain-depth`, "option b only"), `:250` (`DEPTH_EXCEEDED`) | OD-200-8. |
| D13 | Shipped documents do not list 68 shipped codes, nor `CLUSTER_OPERATION_REFUSED` in `docs/tools.md` | section 3, "Docs" column; `docs/tools.md:512` lists `CLUSTER_LOCK_TIMEOUT` only | Successor task S3. Not a design disagreement; a freeze-checklist gap. |

**D3 detail: identifier length caps.**

| Identifier | Sealed-link layout (191) | Other document | Source of the other |
|---|---|---|---|
| source label | target at most 64 bytes; "all label lengths are enforced at startup" | catalog schemas `maxLength` 128 for `source`, `targetSources` items and the `label` definition; source-name grammar `[a-z][a-z0-9-]{0,63}` (64) that in 0.8 only warns (OD-C18 (c)) | design/catalog.md:263, :774; design/batch-and-bulk-follow.md:124, :360 |
| key namespace | at most 32 bytes (design/sealed-links.md:189) | `KeyNamespace` grammar `[a-z][a-z0-9-]{0,63}` (64) | design/key-resolvers.md:50 |
| resolver name | at most 32 bytes | `name()` grammar `[a-z][a-z0-9-]{0,63}` (64) | design/key-resolvers.md:25 |
| entity type | 1 + 32 in the collection-page link payload prose | sizing in the same section counts 65 (the catalog pattern allows 64); audit pattern `[A-Z][A-Z0-9_]{0,63}` | design/batch-and-bulk-follow.md:607 and :609 |
| field path | 1 + up to 64 in the collection-page link payload | result schemas `path` `maxLength` 128 | design/batch-and-bulk-follow.md:607, :433 |

## 13. Naming rules the register applies, and the entries that break them

The rules are taken from what the shipped surface already does (a rule no shipped name breaks is stated as a rule; where shipped names disagree, the majority is the rule and the exceptions are listed). Conventions: `docs/conventions.md` "Naming" (British spelling in identifiers, no abbreviations beyond `id`, `url`, `uri`, `api`, `dto`, `hmac`, `ttl`, `mcp`).

**N1. Code format.** `[A-Z][A-Z0-9_]{0,63}` for every refusal, startup, violation, status and audit-reason code. Checked for all 80 proposed code names: none breaks it; the longest is `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` (37 characters).

**N2. Startup refusals.** Shape `<CLASS>_<NOUN>` with CLASS one of INVALID, MISSING, UNKNOWN, UNSUPPORTED, UNSAFE, DUPLICATE, EMPTY, BLANK, RESERVED, FORBIDDEN, or an area-prefixed condition (`AUDIT_JSON_DIRECTORY_UNUSABLE`, `CORRELATION_REQUIRED_WITHOUT_HEADER`, `REIDENTIFICATION_REQUIRES_CLUSTER`). Of 128 shipped startup codes, 34 `INVALID`, 22 `MISSING`, 7 `UNKNOWN`, 5 `UNSUPPORTED`, 3 `EMPTY`, 3 `BLANK`, 2 `DUPLICATE`, 2 `UNSAFE`, 2 `RESERVED`, 1 `FORBIDDEN` use a class prefix and 47 are area-prefixed.

| Entry | Rule it breaks | Note |
|---|---|---|
| `PAGING_KEY_REQUIRED` | N2: a missing setting is `MISSING_<NOUN>` | `MISSING_PAGING_KEY` would match `MISSING_CLUSTER_KEY`, `MISSING_HMAC_KEY_REFERENCE` (design/batch-and-bulk-follow.md:1121) |
| `LINK_KEY_WEAK`, `LINK_KEY_UNRESOLVED`, `LINK_KEY_SHARED` | N2 area-prefix form, but see N3 | three more spellings of the shipped `HMAC_KEY_WEAK`, `HMAC_KEY_UNRESOLVED`; OD-200-10 |
| `AUDIT_HMAC_KEY_TOO_SHORT`, `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` | N3 | `TOO_SHORT` against shipped `WEAK`; `REUSES_PSEUDONYMISATION_KEY` against `LINK_KEY_SHARED`; OD-200-10 |
| `SOURCE_DESCRIPTION_LEAKS_NAME`, `PUBLIC_MEMBER_INTERFACE`, `UNBOUNDED_MEMBER_INTERFACE`, `UNSEALED_CLUSTER_STATE`, `CLUSTER_KEY_MISMATCH` | N2 (condition-named, not class-prefixed) | Accepted: each names the condition, as the shipped `CLUSTER_PORT_SHARED`, `OPERATOR_AUDIENCE_SHARED` do. `CLUSTER_KEY_MISMATCH` and `UNSEALED_CLUSTER_STATE` may be join-time refusals rather than start-up ones (cluster-security 4.2); that is for the 0.8 build. |

**N3. Key failures.** The shipped pair is `<AREA>_KEY_WEAK` and `<AREA>_KEY_UNRESOLVED` (`HMAC_KEY_WEAK`, `HMAC_KEY_UNRESOLVED`, spring-boot-autoconfigure:PropertiesValidation.java:129). Proposed names that do not follow it are in the table above (OD-200-10).

**N4. Configuration keys.** `dataprism.<group>.<kebab-case-name>`; a duration or size is a typed value (`Duration`, `DataSize`) with no unit in the name; a secret is only a reference (`key-id` plus exactly one of `environment-variable` or `provider-reference`), and a literal `value` property exists only to be refused (`LITERAL_SECRET_FORBIDDEN` for `dataprism.privacy.hmac-key.value`; proposed `cluster-key.value` and `name-key.value`). Checked: all 55 proposed `dataprism.*` keys are kebab case. Breaks:

| Entry | Breach | Note |
|---|---|---|
| `dataprism.approval.pending.sweep-seconds` (shipped*) | unit in the name; not a Spring property; under a map name; not pinned | OD-200-14 |
| `dataprism.audit.hmac-key` (proposed) | no refusing `value` property, unlike `dataprism.privacy.hmac-key` and the cluster keys | A literal under it would be an unknown key. Accepted: unknown keys already refuse; no decision. |
| `dataprism.security` and `dataprism.security-policy` (shipped) | two groups differing only by a hyphen | Pre-existing; renaming now would be a breaking change to a pinned name. Accepted; the new `allowed-clients` and `case-authorisation` join `security-policy`. |
| `dataprism.transport.mode` values `HTTP`, `STDIO` | upper case where every other enumerated value is lower kebab (`hash-chained`, `tcp-ip`, `single-node`) | Pre-existing; the binding is by enum. Accepted; recorded so no new key copies it. |

**N5. Metrics.** `dataprism.<area>.<noun>[.<noun>]`, all lower case, no tags. Word separator inside a segment: four shipped names use `_` (`anchor_failed`, `delete_failed`), the rest use none (`failclosed`, `withheld`). The cluster proposals use `_` (`age_seconds`, `unsupported_format`, `salt_rejected`, `accepting_next`); the catalog proposals use `-` (`source-names.nonconforming`). Rule proposed: `_` inside a segment. Breaks: `dataprism.catalog.source-names.nonconforming`, and `dataprism.cluster.pause.age_seconds` carries a unit in the name. OD-200-7.

**N6. Tools and capabilities.** Tool: lower snake case, verb first, object after (`get_entity_context`). Capability: the tool name upper-cased (`GET_ENTITY_CONTEXT`); `EXPOSE_SOURCE_NAMES` is a non-tool capability. Proposed tools follow it except `follow` (no object) and `follow_bulk` (qualifier after the verb); both are named by owner-delegated decisions (sealed-links §5; batch OD-B17 (a)) and are not reopened here.

**N7. Fields.** Tool arguments and result fields are camelCase (`entityType`, `agreementGroups`). Proposed server-added meta fields are underscore-prefixed (`_links`, `_linksOmitted`, `_truncated`, `_page`) and structural fields are not (`items`, `itemsTruncated`, `page`). Accepted; the prefix marks "added beside the source's own data", and the schema reference must say so. YAML files: `sources:` and `json-sources:` use kebab case for source keys and camelCase for field keys (`nonSensitive`, `undeclaredFields`); the descriptor file uses camelCase only. Pre-existing, accepted; `chainable` and `keyNamespace` (camelCase) follow the descriptor file.

**N8. Spelling.** British in identifiers. Breaks: the shipped `AuthorizationService`, `AuthorizationDecision`, `authorize`; the proposed `CaseAuthorizer`, `CaseAccessRequest` follow the American type names while the keys and codes are British (`case-authorisation`, `CASE_NOT_AUTHORISED`); `UNSAFE_HAZELCAST_SERIALIZATION` (the Hazelcast option is spelled `java-serialization-filter`, so platform-forced). OD-200-11.

**N9. Audit disposition keys.** A fixed key is colon-free or ends in `:<refused>` (section 4.3).

**N10. Decision ids.** Qualified by document (top of this register).

## 14. Open owner decisions

None of these is decided here. Each has options and a recommendation; the owner chooses. "Needed by" says what is blocked until it is chosen. Fourteen decisions; five (1, 2, 4, 5, 8) change what 0.8 builds and should be taken before the 0.8 briefs are cut.

### OD-200-1. How a client treats an unrecognised per-source status

- **Context.** D1 (section 12). Recorded as "not answered" in the original decision record (21:04 on 2026-10-09), `SourceResult.java:14`, `docs/tools.md:141`, `key-resolvers.md` and `batch-and-bulk-follow.md`; recorded as "an error" only in the later `PLAN.md` summary (21:25). Task 200's brief repeats "not answered".
- **Options.** (a) "not answered": a client reads it as a gap; the recorder corrects `PLAN.md:130`. (b) "an error": a client fails on it; `SourceResult` Javadoc, `docs/tools.md:141`, the two design documents and t190 change.
- **Recommendation.** (a). `status` is a `String` exactly so that a later code does not break an old client; an unknown status can only mean the source contributed no `entity` (only `ANSWERED` carries one), so reading it as a gap is the fail-closed reading. Under (b) every status added in 0.8 or 0.9 (`KEY_UNRESOLVED`) would break clients written before it, which defeats registering future codes.
- **Needed by.** before 0.8 adds a status

### OD-200-2. Whether `CLUSTER_OPERATION_REFUSED` is visible to callers, and where its class lives

- **Context.** Section 3.2, D9. Today no caller can see it: tool calls get `OVERSIGHT_UNAVAILABLE`, reads get `SCOPE_READ_BUDGET`, re-identification gets `REIDENTIFICATION_UNAVAILABLE`; the throw sites build the exception without a cause and neither they nor `ToolAdmission` log anything, so a rejected lease (a configured `hazelcast.lock.max.lease.time.seconds` below the lease) makes every approval-gated call fail with a generic code and no log line. The exception sits in the hazelcast module, its sibling `ClusterLockTimeoutException` in core.
- **Options.** (a) Keep it internal: register it as an exception code only, chain the cause and add a WARN log with the exception class and no identifiers, and do not list it in `docs/tools.md`. (b) Surface it like `CLUSTER_LOCK_TIMEOUT`: catch it in `ToolAdmission` (`DENY:CLUSTER_OPERATION_REFUSED`), `ReidentificationService` and `OversightOperatorController`, list it in `docs/tools.md` and `docs/multiple-instances.md`, and move `ClusterOperationRefusedException` into `core.refusal` beside the lock exception. (c) As (b) for tools only.
- **Recommendation.** (b). The message names no key, scope or id, the sibling code is already client-visible, and an operator needs to tell a cluster fault from a paused or unavailable oversight state. The move is cheap now (the class is unreleased) and breaking after the freeze. Needs a code task no open task owns (S2).
- **Needed by.** before 0.8; at the latest before the 0.9 freeze

### OD-200-3. Spelling of the new per-source status `KEY_UNRESOLVED`

- **Context.** C4. It is one edit away from the startup codes `HMAC_KEY_UNRESOLVED`, `LINK_KEY_UNRESOLVED` and `AUDIT_HMAC_KEY_UNRESOLVED`; `sealed-links.md` and `key-resolvers.md` ask this register to record them together or rename the status.
- **Options.** (a) Keep `KEY_UNRESOLVED` and rely on the kinds being different. (b) Rename it `RESOLVER_FAILED`, which says what happened, matches the past-tense statuses (`TIMED_OUT`, `FAILED`) and shares no stem with the startup codes.
- **Recommendation.** (b). A status is read next to `TIMED_OUT` and `CIRCUIT_OPEN`; a log search for `*_KEY_UNRESOLVED` would otherwise mix a runtime outcome with start-up failures. A status spelling is permanent at the freeze, a rename now is free.
- **Needed by.** before key-resolvers is built (0.8)

### OD-200-4. One set of length caps for labels and identifiers

- **Context.** D3 table (section 12). The sealed-link layout says namespaces and resolver names at most 32 bytes and source labels at most 64, all "enforced at startup"; the catalog schemas allow 128-character source labels; the `KeyNamespace` and resolver-name grammars allow 64; the entity type is 32 in one sentence and 64 in the sizing beside it; the field path is 64 in the link and 128 in the schemas. Separately, catalog OD-C18 (c) makes a non-conforming source name (possibly over 64) only a warning in 0.8, so "enforced at startup" is not true in 0.8.
- **Options.** (a) The link layout governs: source labels 64 (schemas `maxLength` 64), namespace and resolver name 32 (grammar `{0,31}`), entity type 64 (fix the prose to 1 + 64, the sizing already uses 65), field path 64. A source name over 64 simply gets no links in 0.8 (counted as omitted), and `INVALID_SOURCE_NAME` refuses from 0.9. (b) Widen the layout to 128, 64, 64, 128: the worst-case direct link grows by about 160 bytes, to about 830 characters, and the collection-page link to about 985 characters against the 1024 default. (c) Keep each document's own cap and refuse at link issue only.
- **Recommendation.** (a). It is the cheapest and matches the grammar the owner already chose for source names (catalog OD-C18); (b) spends most of the margin that OD-B12 (owner) bought for a future header field; (c) leaves a schema that promises more than a link can carry.
- **Needed by.** before the 0.8 briefs for links and the catalog

### OD-200-5. The capability that authorises `follow`

- **Context.** D11, section 1.2. `batch-and-bulk-follow.md` names a `FOLLOW` capability "of task 191"; `sealed-links.md` defines none. Every tool needs a capability in `AuthorizationService` and a role must be granted it (`docs/tools.md:440`).
- **Options.** (a) A new capability `FOLLOW`, granted per role and off until granted, with an upgrade note as for `COMPARE_ENTITY_SOURCES` in 0.2.0; `FOLLOW_BULK` likewise. (b) No new capability: `follow` needs `GET_ENTITY_CONTEXT`. (c) The capability of the tool that issued the link, carried in the link.
- **Recommendation.** (a). Least privilege and the project's own rule that adding a tool grants nothing; the batch design already assumes it. It costs one frozen name and a documented upgrade step.
- **Needed by.** before the 0.8 brief for `follow`

### OD-200-6. The attribute name of `@Chainable`

- **Context.** C14. `@Chainable(namespace = "parcel-ref")` reuses `namespace`, which on `@SensitiveData`, `@SensitiveObject` and in the descriptor file means a `PrivacyNamespace` enum value; the descriptor key for the same fact is `keyNamespace`.
- **Options.** (a) `@Chainable(keyNamespace = "parcel-ref")`, matching the descriptor key. (b) Keep `namespace`.
- **Recommendation.** (a). One word per meaning across annotation and descriptor; a different type under the same attribute name invites a wrong value that compiles.
- **Needed by.** before the annotation is built (0.8)

### OD-200-7. Metric word separators and units

- **Context.** N5. Four shipped names use `_` inside a segment, others none; cluster proposals use `_`; the catalog proposal uses `-`; one cluster gauge carries `_seconds`.
- **Options.** (a) Rule: `_` inside a segment, a unit suffix allowed on a gauge; rename `dataprism.catalog.source-names.nonconforming` to `dataprism.catalog.source_names.nonconforming`. (b) `-` inside segments everywhere (renames four shipped metrics). (c) Leave mixed.
- **Recommendation.** (a). It matches the shipped retention metrics and the cluster table, changes only an unbuilt name, and is the form a Prometheus registry produces anyway.
- **Needed by.** before the catalog is built

### OD-200-8. Names that exist only for the rejected link option (b)

- **Context.** D12. The owner adopted option (a') for links. `dataprism.links.max-chain-depth` ("option b only") and the reason `LINK_DEPTH_EXCEEDED` are reachable only under (b).
- **Options.** (a) Drop both from the register and the 0.8 build; a depth or origin option, if ever wanted, is additive later. (b) Keep them as reserved names.
- **Recommendation.** (a). A pinned key or audit reason that nothing can set or emit is a permanent promise with no behaviour. `LINK_ORIGIN_DRIFT` stays: task 198 reuses it for collection pages.
- **Needed by.** before the links brief

### OD-200-9. Whether `dataprism.hazelcast.security.mode` exists

- **Context.** D8. With isolation-only not allowed (cluster-security OD-2 (i)) the key has one legal value, `sealed`, and carries two startup codes (`MISSING_CLUSTER_SECURITY_MODE`, `UNSUPPORTED_CLUSTER_SECURITY_MODE`).
- **Options.** (a) Keep it as an explicit, required opt-in. (b) Drop the key and both codes: a multi-member join is sealed by definition and `MISSING_CLUSTER_KEY` already refuses it without a key.
- **Recommendation.** (b), with the security reviewer told, because the approved design changes by one key. One key and two codes fewer to freeze, and no ceremony that can only be answered one way. If the owner wants the explicit switch for readability, (a) is harmless.
- **Needed by.** before the cluster briefs (215)

### OD-200-10. The vocabulary for key failures in new startup codes

- **Context.** N3. Shipped: `HMAC_KEY_WEAK`, `HMAC_KEY_UNRESOLVED`. Proposed: `LINK_KEY_WEAK`, `LINK_KEY_UNRESOLVED`, `LINK_KEY_SHARED`, but `AUDIT_HMAC_KEY_TOO_SHORT` and `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY`.
- **Options.** (a) One family: `<AREA>_KEY_WEAK`, `<AREA>_KEY_UNRESOLVED`, `<AREA>_KEY_SHARED`; task 211 uses `AUDIT_HMAC_KEY_WEAK` and `AUDIT_HMAC_KEY_SHARED`. (b) Keep the briefed names.
- **Recommendation.** (a). Operators grep these; three spellings of one failure is avoidable, and 211 is not built.
- **Needed by.** before 211 starts

### OD-200-11. The `-izer` / `-ization` spelling of new security types

- **Context.** N8. Shipped `AuthorizationService`, `AuthorizationDecision`; proposed `CaseAuthorizer`, `CaseAccessRequest`; keys and codes are British.
- **Options.** (a) Accept the shipped spelling for the `Authoriz*` type family and name new types alike; record it as an exception to `docs/conventions.md`. (b) British for new types only (`CaseAuthoriser`), leaving a mixed family. (c) Rename the shipped types before the freeze.
- **Recommendation.** (a). The shipped names echo Spring Security's `AuthorizationDecision`; (c) is a pure rename of public API for spelling; (b) is the worst of both.
- **Needed by.** before 203 is built

### OD-200-12. One word for the naming setting: audit key `names` or `labels`

- **Context.** C13. Catalog OD-C2 (a) (owner, 2026-10-09) fixed the audit key as `names`; the result field of `list_sources` and `describe_source` is `labels`.
- **Options.** (a) Keep both: they are different surfaces. (b) Rename the audit key to `labels`.
- **Recommendation.** (a). The owner chose the audit key explicitly; the two meanings are the same setting on different surfaces and the documents can say so. (b) is fine if the owner prefers one word.
- **Needed by.** before the audit grammar change for `names`

### OD-200-13. Shipped near-synonym codes on different surfaces

- **Context.** C19. `MISSING_PURPOSE` (MCP caller) and `PURPOSE_REQUIRED` (re-identification); `UNKNOWN_PURPOSE` and `PURPOSE_NOT_ALLOWED`; `APPROVAL_NOT_FOUND` and `REIDENTIFICATION_NOT_FOUND`.
- **Options.** (a) Accept and document which surface emits which. (b) Unify before the freeze (a breaking change for re-identification clients, in 0.8).
- **Recommendation.** (a). The surfaces are different audiences and the audit already distinguishes them by tool; unifying changes shipped client-visible text for no new information.
- **Needed by.** before the 0.9 freeze

### OD-200-14. Where the approval sweep interval lives

- **Context.** Sections 6.3, N4. `dataprism.approval.pending.sweep-seconds` is a Hazelcast config property (integer seconds), outside Spring binding, the frozen list and `ConfigurationMetadataDocumentedTest`, under a map name. It is unreleased (0.7.0*).
- **Options.** (a) Keep it, pin it in a new list and document it. (b) Replace it with a Spring property `dataprism.hazelcast.pending-sweep-interval` (`Duration`, default `PT5M`, bounds `PT1S` to `P1D`), pinned in the frozen list; no migration, nothing shipped. (c) Remove the knob; fix the interval.
- **Recommendation.** (b). It follows N4, is visible to the existing frozen-name and metadata tests, and costs nothing to rename while unreleased. Needs a code task (S2 or its own).
- **Needed by.** before the 0.7.0 release is cut

## 15. Freeze checklist for 0.9.0

For each kind: what must exist, what must be documented, what must pin it with a test, where it stands now (from sections 1 to 8) and the gap. "Pinned" means a failing test when a name is added, removed or renamed without editing the pinned list.

| Kind | Must exist | Must be documented | Must be pinned by | Today | Gap |
|---|---|---|---|---|---|
| MCP tools and their input and output schemas | every tool in section 1.1 registered or removed from the register; each schema frozen | `docs/tools.md`: arguments, result fields, errors, one worked example per tool | a golden `tools/list` JSON (names, `inputSchema`, `outputSchema` if any) compared byte for byte; the `follow` schema's `maxLength` shown as the configured value | 2 tools. `CompareEntitySourcesToolTest` reads the input schema; no golden file; no result-schema test | golden schema test; tool reference for the 0.8 and 0.9 tools |
| Capabilities | `Capability.KNOWN` equals the tools' needs; `FOLLOW` decided (OD-200-5) | `docs/tools.md` "Grant before call" and `docs/configuration.md` role table | `CapabilityKnownSetTest` | pinned; `DESCRIBE_ENTITY_MODEL` exists with no tool | extend the pinned set with each new capability |
| Reserved argument names | `ReservedArguments.NAMES` | `docs/tools.md` (arguments) | `ReservedArgumentsTest` | pinned | none |
| Per-source status codes | section 2 table; OD-200-1 and -3 decided | `docs/tools.md` status table, with the unknown-status rule | `SourceStatusCodesTest` against `source-status-codes.txt` | pinned (7) | add the 0.8 codes in the same commit as the code |
| Call, admission, re-identification and operator codes | section 3.1 to 3.4 | every code in `docs/tools.md` or `docs/reidentification.md`; "Docs: no" in section 3 is the gap list | a frozen resource listing every code, and a test that extracts string literals and `Code` enum constants from `src/main` (commands E1, E2) and compares the two; the same test checks each code against `RefusalCodes` format | format is tested (`RefusalCodesTest`); the set is not. Undocumented: 17 of 34 call, 0 of 10 admission, 0 of 12 re-identification, 1 of 8 operator | frozen list and extraction test; document the gaps (S3, S4) |
| Startup refusal codes | section 3.5 | `docs/configuration.md` or `docs/multiple-instances.md`, each with its cause | the same frozen resource and test, a second list | set not pinned. Undocumented: 40 of 128 | as above |
| Validation violation codes | section 3.6 | `docs/tools.md` (the sensitive-pattern scan), `docs/faq.md` | a test beside `SensitivePatternValidator` | not pinned. Undocumented: 3 of 3 | small test and a paragraph |
| Audit fields, record versions and `policyDecision` forms | 25 fields, version 3; no 0.8 change to either (tasks 206, 207, 211 leave them alone) | `docs/audit.md` (present) | `AuditRecordVersionTest`, `AuditFieldMappingTest`, `AuditEventHashTest` pinned literals | fields and hash pinned; `policyDecision` forms and the disposition grammar only partly (`AuditFieldDispositionTest`) | a test that lists the forms in `docs/audit.md` against code; the `names` grammar change (catalog OD-C2) lands with its test |
| Audit disposition keys and values | section 4.3; the colon rule | `docs/audit.md` for each new key (links, catalog, `names`) | a closed-grammar test over `AuditEvent.isValidAction` and the key shapes | grammar closed and tested for values; key shapes not | add the key-shape rule as a test |
| Verifier, checkpoint and file formats | section 4.4 | `docs/audit.md` (exit codes, flags, segment names) | `AuditChainVerifierCliTest`; a file-name test for both segment forms (207) | partly | segment-name test arrives with 207 |
| Configuration keys and enumerated values | section 6; every proposed key built or removed | `docs/configuration.md`, one row each (9 pinned keys are documented by relative name only, 0 not at all) | `PropertyNamesFrozenTest`, `ConfigurationMetadataDocumentedTest` | 93 leaf keys pinned; enumerated values, the two YAML files, the descriptor file and the Hazelcast property are not | pin enumerated values and YAML key sets; settle OD-200-14 |
| Metrics | section 5 | `docs/` metrics page (names, kind, meaning) | `PrivacyMetricsTest` | 17 pinned | new names land in the same commit; OD-200-7 |
| SPI interfaces and annotations | section 7.1 plus the proposed SPI; method sets final | Javadoc plus `docs/architecture.md` | a public-signature snapshot of the SPI packages (the tool is a 0.9 design choice, not decided here) | bean inventory pinned (56); interface method sets not | signature snapshot test |
| Public constructors that change | section 7.2 | the 0.8 migration page (breaking list) | compile-time use in the integration tests | n/a | the migration page names each |
| Hazelcast map names, protection names, health | section 8.1 | `docs/multiple-instances.md` | a frozen list of map names (they are a migration contract once keys are sealed) | not pinned | frozen list |
| Link wire format | section 8.2: header, relation bytes, labels, version byte | `docs/` operator page (0.8) with key-destruction procedure (PLAN.md:107) | known-answer vectors (sealed-links §7 obligations) and a test that every relation byte is accepted by exactly one tool | not built | arrives with the 0.8 links brief |
| Operator HTTP surface | 11 endpoints, error codes, `status` values (section 3.4) | `docs/reidentification.md` and the operator page | an endpoint-list test (Spring mapping scan) and the code test above | not pinned | endpoint-list test |
| Decisions and register | OD-200-1 to -14 chosen; the stale rows in section 12 corrected (S1); every `proposed` name built or removed | this register updated in the same change as each addition | `docs/plan/design/contract-register.md` re-run of the extraction commands (E1 to E11) shows no unexplained difference | this task | re-run at each 0.8 task, and at 0.9.0-rc |

The 0.8 tasks each add their names to the pinned lists in the same commit; the freeze checklist is satisfied when every row above has a test and every "Docs: no" row in sections 3 and 6 is "yes" or removed.

## 16. Successor tasks this register names

Not allocated or numbered here (the orchestrator allocates ids). Each is outside this task's `Owns`.

| Id | Task | Owns (suggested) | Depends on |
|---|---|---|---|
| S1 | Correct the design documents' stale rows: `sealed-links.md` closing table (D5) and relation table (D4), `key-resolvers.md` closing table and failure table (D5, D6), `cluster-security.md` lock-timeout rows (D7), and the label caps after OD-200-4 (D3) | docs/plan/design/*.md | owner decisions 4, 8, 9 |
| S2 | Surface or log `CLUSTER_OPERATION_REFUSED` and move its class (OD-200-2); replace the sweep property (OD-200-14) | hazelcast, security, reidentification, server, autoconfigure | owner decisions 2, 14 |
| S3 | Document the shipped codes the shipped pages lack ("Docs: no" in section 3) and add `CLUSTER_OPERATION_REFUSED` to `docs/tools.md` | docs/tools.md, docs/configuration.md, docs/multiple-instances.md | OD-200-2 |
| S4 | Add the freeze tests of section 15 that do not need a decision: code extraction and frozen lists, golden `tools/list`, map names, operator endpoints, SPI signature snapshot | test sources, test resources | none |
| S5 | Correct the D-070-3 line in `PLAN.md` once OD-200-1 is chosen | docs/plan/PLAN.md | owner decision 1 |
| S6 | Design the 0.9 contract that has no name yet (section 6.5): per-source role authorisation (D-RM-11), audit-entry size handling, and the `compare_entity_sources` review | docs/plan/design/ (new document) | none; before the 0.9 briefs |

