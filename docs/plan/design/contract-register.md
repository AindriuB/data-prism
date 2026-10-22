# Contract register: every tool, code and key planned up to the 0.9 freeze

Coordinator scope extension 2026-10-21: OD-200-8 rows applied with the cluster rows so the register counts are consistent. Task 218 amended the cluster rows on 2026-10-21 (change note: cluster-security.md status list).

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
| Call, admission, re-identification, operator, startup, violation and audit-runtime codes | 214 (34 call, 10 admission, 12 re-identification, 10 operator, 131 startup, 3 violation, 14 audit runtime; literals, enum constants and the two single-word operator codes) | 82 startup/call/audit codes in tasks and designs | 3 |
| Audit record fields / `policyDecision` forms | 25 / 6 | 0 changes to either; new disposition keys and values | 4 |
| Closed vocabularies (enums, any visibility, whose constants reach output) | 25 | 0 | 7.4 |
| Metrics | 17 | 20 | 5 |
| `dataprism.*` configuration keys | 94 | 58 (plus 10 descriptor and `json-sources` entry keys) | 6 |
| SPI interfaces and annotations | 30 | 24 types | 7 |
| Closing-table rows / distinct names in the five design documents | | 187 rows / 268 names (267 backticked, one plain-text health field; the cluster design's closing table is 69 rows after tasks 218 and 222, counted), every one appearing in this register | 1 to 8 |

Collisions found: 21 (section 11). Document disagreements recorded: 13 (section 12). Open owner decisions: 15 (section 14). Successor tasks named: 6 (section 16).

The findings that matter most before 0.8 starts:

1. **`CLUSTER_OPERATION_REFUSED` (task 216) could not be seen by any caller. Resolved by task 233** (OD-200-2 (b)): it reaches callers as its own code on the admission, read, re-identification and operator paths, with the cause chained and logged (3.2). `dataprism.approval.release.skipped` (task 232) is registered in section 5.
2. **The unknown-status rule is recorded two ways** (D1, OD-200-1). The decision record, the code and the shipped page say "not answered"; only the later `PLAN.md` summary says "an error".
3. **Length caps disagree between the link layout, the catalog schemas and the resolver grammar** (D3, OD-200-4), and catalog OD-C18 (c) lets a long source name through in 0.8 while the link layout says it is refused at startup.
4. **A capability for `follow` is assumed by the batch design and defined nowhere** (OD-200-5).
5. **Literal-only extraction misses shipped codes in two forms**: enum constants (`UNSAFE_HAZELCAST_DISCOVERY`, `INVALID_CLUSTER_PORT`) and codes written as the start of a message (`SPECIAL_CATEGORY_EXPOSED`, `AUDIT_CHECKPOINT_UNAVAILABLE`, `AUDIT_LISTENER_DROPPED`, `AUDIT_LISTENER_FAILED`). The freeze test must run E1, E1c and E2 and a scan of `src/main/resources` (E1r).
6. **68 of 214 shipped codes are not named in any shipped page** (15).
7. **Several closing-table rows are stale** against the decisions in the same documents: the audit keys that shape C withdrew, the cluster `security.lock-timeout` that task 202 superseded, `KeyNamespace` and the eleven link reasons missing from their tables (D4 to D8).
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

The shipped inventory below comes from four extractions (commands and counts in the Outcome of task 200): E1, every upper-case string literal containing an underscore in `*/src/main/**/*.java`; E1c, every literal that begins with a code followed by `: ` or other text (the form `"CODE: message"`); E2, the constants of the enum `Code` types that declare codes (`PrivacyClusterRefusal.Code`, `ApprovalRefusedException.Code`); and E1r, a scan of `*/src/main/resources` for code-shaped tokens, which finds no further code (the tokens there are enum values, section 7.4, a code already listed, and one example environment variable). "Docs" says whether the exact string appears in a shipped document under `docs/` outside `docs/plan/`; "no" is a freeze-checklist item (section 15).

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
| `SOURCE_MODEL_GENERIC` | shipped | 0.7.1 | core:SourceModels.java:61 | yes |  |
| `SOURCE_MODEL_MAP_OF_RECORD` | shipped | 0.7.1 | core:SourceModels.java:64 | yes |  |
| `SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD` | shipped | 0.7.1 | core:SourceModels.java:67 | yes |  |
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
| `CLUSTER_OPERATION_REFUSED` | shipped* | 0.7.0* | core:ClusterOperationRefusedException.java:12 | yes | Hazelcast rejected a lock, lease, index or entry-processor call during approval creation or another lock-taking cluster call (t216:125); surfaced to callers on every path by task 233 (OD-200-2); see 3.2 |
| `OVERSIGHT_UNAVAILABLE` | shipped | 0.4.0 | mcp:ToolCalls.java:32 | yes | admission could not be evaluated; refused, never let through |
| `APPROVAL_REQUIRED` | shipped | 0.4.0 | security:ToolAdmission.java:106 | yes | a second person must approve; text carries `approvalId=` |
| `APPROVAL_PENDING` | shipped | 0.4.0 | security:ToolAdmission.java:100 | yes | approval requested, not yet decided; text carries `approvalId=` |
| `TOO_MANY_PENDING` | shipped | 0.4.0 | security:ToolAdmission.java:81 | yes | the requester holds the maximum live pending approvals |
| `DATAPRISM_PAUSED` | shipped | 0.4.0 | security:ToolAdmission.java:61 | yes | an operator paused every call |
| `TOOL_PAUSED` | shipped | 0.4.0 | security:ToolAdmission.java:64 | yes | an operator paused this tool |
| `SCOPE_PAUSED` | shipped | 0.4.0 | security:ToolAdmission.java:67 | yes | an operator paused the caller's scope |
| `CALLER_RATE_LIMITED` | shipped | 0.4.0 | security:ToolAdmission.java:73 | yes | the caller used its request allowance |

**`CLUSTER_OPERATION_REFUSED`, as task 216 introduced it and task 233 surfaced it.** Meaning: Hazelcast rejected a lock or its lease (for example a configured `hazelcast.lock.max.lease.time.seconds` below the lease the store asks for), or an index or entry-processor call failed. Nothing is reported as a duplicate id and nothing names a key, scope, principal or approval id (t216:125). It is a `PrivacyRefusedException` subclass and so carries the stable code. Task 233 moved the class into core (core:ClusterOperationRefusedException.java:12), beside its sibling `ClusterLockTimeoutException` (core:ClusterLockTimeoutException.java:13), with no alias, because it was never released. Every throw site now chains the Hazelcast failure as the cause and writes one `WARN` line holding the code and the cause's simple class name, never its message or stack trace (hazelcast:ClusterRefusals.java).

Where it surfaces, after task 233 (OD-200-2 (b)). `CLUSTER_LOCK_TIMEOUT` joined the re-identification and operator paths in the same task, so the two cluster codes behave the same on every path (D-0.7-HZ-3):

| Path | What the caller sees | Source |
|---|---|---|
| Tool call, approval creation, `consumeApproved` or caller rate limiter | The code itself, audited `DENY:CLUSTER_OPERATION_REFUSED`. `ToolAdmission.admit` catches it beside `ClusterLockTimeoutException`. | security:ToolAdmission.java |
| Tool call, cluster read budget | `refused: CLUSTER_OPERATION_REFUSED at $`, audited `DENY:CLUSTER_OPERATION_REFUSED` with `merged:<refused>` = `REFUSED`, and no source called. A rejected lock propagates; any other budget failure still refuses the read as `SCOPE_READ_BUDGET`. | hazelcast:HazelcastScopeBudget.java |
| Re-identification request, approve or collect | The code itself as the refusal, audited `DENY:<code>`; any other store failure is still `REIDENTIFICATION_UNAVAILABLE`. If the denial's audit write fails the refusal stands with the same code. | reidentification:ReidentificationService.java |
| Operator approve, reject, pause, resume, reads of state and approvals | HTTP 503 with `{"code":"<code>"}`, and the denial audited with that code; any other store or state failure is still 503 `OVERSIGHT_UNAVAILABLE`. | server:OversightOperatorController.java |
| Operator re-identification endpoints | HTTP 503 with `{"code":"<code>"}` for either cluster code. | server:ReidentificationOperatorController.java |

The code is listed in `docs/tools.md`, `docs/reidentification.md` and `docs/multiple-instances.md`. Task 233 also made `CLUSTER_LOCK_TIMEOUT` visible on the re-identification and operator paths, which changes responses that `docs/multiple-instances.md` documented as `503 OVERSIGHT_UNAVAILABLE` (task 201 records it as a changed line).

**The sealed cluster adds no client-visible code (cluster-security OD-215-10 (a)).** The existing codes carry the new conditions on the paths in the table above. A pause document that is absent, unauthentic or not fresh means admission cannot be evaluated, so the caller sees `OVERSIGHT_UNAVAILABLE` (and the operator reads and the resume write see HTTP 503 `OVERSIGHT_UNAVAILABLE`). A member that is not serving (an unverified member under `unverified-member.action: refuse`, a sentinel mismatch or a key check value mismatch) and `dataprism.cluster.rotation.blocked` (no held key accepted by every verified member) are refused with `CLUSTER_OPERATION_REFUSED`, so the task 233 path carries them everywhere. Operator writes while the pause document is not fresh or the member is not serving follow cluster-security OD-215-16 (a): a pause is allowed and a resume is refused (HTTP 503 with the same code as above) until the member serves again; the serving gate never blocks the pause-document map, and an operator pause re-creates an absent document. `CLUSTER_NOT_SERVING` and `PAUSE_STATE_STALE` were considered and are not added. Health and the `dataprism.cluster.*` metrics (section 5) give the operator the reason.

Related shipped cluster names that task 216 and task 232 added (all internal to the hazelcast module unless stated): maps `dataprism.approval.id` and `dataprism.approval.pending` (hazelcast:PrivacyCluster.java:81), the metric `dataprism.approval.release.skipped` (section 5), the Spring property `dataprism.hazelcast.approval-sweep-interval` (section 6.1; it replaced the unreleased Hazelcast config property named in section 10), and public constructors `HazelcastApprovalStore(cluster, timeout, PrivacyMetrics)` and `ScopeIdentityIndex(cluster, metrics, Duration)` (t232:253).

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
| `HMAC_KEY_UNRESOLVED` | shipped | 0.1.0 | spring-boot-autoconfigure:PropertiesValidation.java:132 | no | also raised during cluster-key resolution (task 222 review); the resolver's array is never mutated or cleared |
| `HMAC_KEY_WEAK` | shipped | 0.1.0 | spring-boot-autoconfigure:PropertiesValidation.java:129 | no |  |
| `INVALID_APPROVAL_QUORUM` | shipped* | 0.7.0* | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:325 | yes |  |
| `INVALID_APPROVAL_SWEEP_INTERVAL` | shipped* | 0.7.0* | spring-boot-autoconfigure:DataPrismPropertiesValidator.java:563 | yes | `dataprism.hazelcast.approval-sweep-interval` outside `PT1S` to `P1D`, zero or negative (t233; OD-200-14) |
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
| `MISSING_KEY_PROVIDER` | shipped | 0.1.0 | server:ServerIntegrationsConfiguration.java:54 | no | also raised by the packaged server's environment resolver for a `provider-reference` key (cluster, name or privacy HMAC), naming the property, via `HmacKeyReferenceResolver.resolveProviderReference` (task 222 review round 2, 2026-10-23) |
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
| `SPECIAL_CATEGORY_EXPOSED` | shipped | 0.4.0 | core:PrivacyProfiles.java:95 | yes | a privacy profile maps a special-category classification to anything weaker than REDACT or REMOVE; the profile is refused when loaded (found by E1c) |
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
| `INVALID_LINK_LIMIT` | proposed | 0.8 | design/sealed-links.md:482 | sealed-links OD-1..17 (delegated) | startup refusal code; also the `LinkCodec` constructor's key id mismatch refusal (task 242 review round 2, 2026-10-23) |
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
| `MISSING_CLUSTER_KEY` | proposed | 0.8 | design/cluster-security.md:858 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | a joining member without the cluster key or the name key, or either without exactly one source; on `join.mode: none`, only one of the two supplied, or `next` or `previous` without `current` (OD-215-3 (a), OD-215-8 (a)); a keyless `PrivacyCluster.using()` when the cluster has more than one member or any join is enabled, including `hazelcast.discovery.enabled` and a `DiscoveryServiceProvider` (task 222 review); a keyless member is otherwise always isolated (loopback, `bind.any=false`, a fresh `-solo-<uuid>` name suffix each time, the `Config` passed in modified); a keyless `using()` whose listening configuration is not loopback-only (`bind.any` or `server.bind.any` true, interfaces disabled or not all IPv4 loopback, a public address); and a keyless wrapper's `InitialMembershipListener` finding another member, at `init()` or later, which is detection and not prevention (task 222 review round 2, 2026-10-23) |
| `CLUSTER_KEY_UNRESOLVED` | proposed | 0.8 | design/cluster-security.md:859 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | a cluster key (`current`, `next`, `previous`) whose reference does not resolve, or whose id contains `.` (OD-215-3 (a), OD-215-9 (a)). Family of OD-200-10 |
| `CLUSTER_KEY_WEAK` | proposed | 0.8 | design/cluster-security.md:860 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | a cluster key below the strength rule of `dataprism.privacy.hmac-key` (OD-215-3 (a)). Family of OD-200-10 |
| `CLUSTER_KEY_SHARED` | proposed | 0.8 | design/cluster-security.md:861 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | a cluster key equal to the privacy HMAC key, the audit key or a link key (OD-215-3 (a), cluster-security OD-9). As built (task 222): the same key id or the same material, and only the privacy HMAC key is compared until 211, 254 and 255 extend `ClusterKeyResolution`. Family of OD-200-10 |
| `NAME_KEY_UNRESOLVED` | proposed | 0.8 | design/cluster-security.md:862 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | the name key's reference does not resolve, or its id contains `.` (OD-215-3 (a)). Family of OD-200-10 |
| `NAME_KEY_WEAK` | proposed | 0.8 | design/cluster-security.md:863 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | the name key is below the strength rule (OD-215-3 (a)). Family of OD-200-10 |
| `NAME_KEY_SHARED` | proposed | 0.8 | design/cluster-security.md:864 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | the name key equals another purpose key or any cluster key (OD-215-3 (a)). As built (task 222): the same key id or the same material, against the privacy HMAC key and the cluster keys until 211, 254 and 255 extend `ClusterKeyResolution`. Family of OD-200-10 |
| `DUPLICATE_CLUSTER_KEY_ID` | proposed | 0.8 | design/cluster-security.md:865 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | the ids of `current`, `next` and `previous` are not pairwise distinct, or the name key's id equals any of them (OD-215-3 (a); task 222) |
| `MISSING_ISOLATION_ATTESTATION` | proposed | 0.8 | design/cluster-security.md:866 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | `isolation.attestation` absent on a joining member; never raised on `join.mode: none` |
| `INVALID_ISOLATION_ATTESTATION` | proposed | 0.8 | design/cluster-security.md:867 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | an unknown attestation value |
| `MISSING_MEMBER_INTERFACE` | proposed | 0.8 | design/cluster-security.md:868 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | `tcp-ip` or `kubernetes` without `member.interface` (cluster-security OD-10) |
| `UNBOUNDED_MEMBER_INTERFACE` | proposed | 0.8 | design/cluster-security.md:869 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | `member.interface` matches every address (cluster-security OD-10) |
| `PUBLIC_MEMBER_INTERFACE` | proposed | 0.8 | design/cluster-security.md:870 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | the resolved bind address is globally routable and the attestation is not `mesh-mtls`, `wireguard` or `ipsec`; not built (cluster-security OD-10 (ii)); the name stays reserved |
| `INVALID_PAUSE_LEASE` | proposed | 0.8 | design/cluster-security.md:871 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | `pause-lease` or `clock-skew-tolerance` outside its range, or skew above half the lease (OD-200-14). The lock timeout is not here: it is the shipped `INVALID_LOCK_TIMEOUT` |
| `INVALID_UNVERIFIED_MEMBER_POLICY` | proposed | 0.8 | design/cluster-security.md:872 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | `unverified-member.action` or `.grace` outside its range (OD-200-14) |
| `UNSAFE_HAZELCAST_SERIALIZATION` | proposed | 0.8 | design/cluster-security.md:873 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | a supplied `Config` or instance with no allowlisting `java-serialization-filter`, or with user-code deployment, Jet or SQL enabled |
| `CLUSTER_KEY_MISMATCH` | proposed | 0.8 | design/cluster-security.md:874 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | the sentinel in the cluster does not authenticate under any key this member holds, or is sealed under a key id it does not hold; may be a join-time refusal (cluster-security 4.2, 4.6) |
| `UNSEALED_CLUSTER_STATE` | proposed | 0.8 | design/cluster-security.md:875 | cluster-security OD-1..16 (delegated; sealed design confirmed); amended by task 218 | a 0.8 member starts with no verified 0.8 member present and finds an entry in a pre-0.8 map other than the fence entry: the refusal that enforces the non-rolling cutover; there is no compatibility mode (OD-215-1 (a)). Never raised once a verified 0.8 member is present: then entries are counted by `dataprism.cluster.legacy.entries` and logged (owner decision 2026-10-21, extends OD-215-1). Implemented by task 221 (startup check, `.s1` maps) and 227 (verified-member test) |
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
| `AUDIT_CHECKPOINT_UNAVAILABLE` | shipped | 0.4.0 | core:AuditRecorder.java:156 | yes | the last checkpoint write failed, so the recorder refuses to record until `checkpoint()` succeeds; `AuditCheckpointUnavailableException`; docs/configuration.md and docs/eu-ai-act.md say audited calls are refused with it (found by E1c) |
| `AUDIT_LISTENER_DROPPED` | shipped | 0.6.0 | core:AuditEventListeners.java:190 | yes | WARN log line: events not delivered to audit listeners because the queue was full; the audit log is unaffected (found by E1c) |
| `AUDIT_LISTENER_FAILED` | shipped | 0.6.0 | core:AuditEventListeners.java:221 | yes | WARN log line: a listener threw; names the listener class, event id, sequence and exception class only (found by E1c) |

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
| `LINK_WRONG_TOOL` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). the eleven `LINK_<REASON>` codes of task 191 (siblings; `LINK_ORIGIN_DRIFT` and `LINK_EXPIRED` are reused from them) |
| `LINK_FILTER_MISMATCH` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). same closing-table row as `LINK_WRONG_TOOL` |
| `LINK_LABELS_CHANGED` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). same closing-table row as `LINK_WRONG_TOOL` |
| `LINK_CATALOG_CHANGED` | proposed | 0.8 | design/batch-and-bulk-follow.md:1118 | batch OD-B1..B24 (delegated) | audit-only reason codes (shape C). same closing-table row as `LINK_WRONG_TOOL` |
| `BATCH_DEFERRED` | proposed | 0.9 | design/batch-and-bulk-follow.md:1133 | batch OD-B1..B24 (delegated) | audit `DENY:` code for an item not run. none |
| `BULK_DEFERRED` | proposed | 0.9 | design/batch-and-bulk-follow.md:1146 | batch OD-B1..B24 (delegated) | audit `DENY:` code for a link not run. `BATCH_DEFERRED` (sibling) |
| `LINK_MALFORMED` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_TOO_LONG` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_VERSION` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_UNKNOWN_KEY` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_AUTH_FAILED` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_EXPIRED` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_ISSUED_IN_FUTURE` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_TARGET_NOT_PERMITTED` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_RELATION_UNDECLARED` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_ORIGIN_DRIFT` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |
| `LINK_PAYLOAD_MISMATCH` | proposed | 0.8 | design/sealed-links.md:250 (the eleven reasons) | sealed-links OD-13 (C), OD-3 | expansion of the closing-table pattern `LINK_<REASON>` |

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
| `DATAPRISM_EXAMPLE_HMAC_KEY` | integration-tests:application.yaml:45 | environment variable name in the example application's configuration (found by E1r) |
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
| `resolver:<name>:ms` | proposed; withdrawn by sealed-links OD-13 (C) | 0.8 | design/key-resolvers.md:240 | sealed-links OD-13; key-resolvers OD-R1..R10 (delegated) | audit disposition key (or metric, OD-13). The body of the document records nothing for this key under shape C (sealed-links.md:251, key-resolvers.md:111); the closing-table row is stale (section 12, D5) |
| `catalog` | proposed | 0.8 | design/catalog.md:839 | catalog OD-C1..C19 (delegated) | audit disposition key (values `REFUSED:CATALOG_<REASON>`). `link` (task 191, sibling) |
| `names` | proposed | 0.8 (OD-C2 (a), decided) | design/catalog.md:841 | catalog OD-C2 (a) (owner, 2026-10-09); reopens sealed-links OD-13 shape C for this key | reserved audit disposition key (values `REAL`, `ALIAS`). none |
| `CLUSTER_STATE_TAMPER` | proposed; withdrawn by cluster-security OD-215-6 (b) | 0.8 | design/cluster-security.md (withdrawn in the status list and in 4.4) | OD-215-6 (b), owner, 2026-10-20 | audit event. Tamper is the metric `dataprism.cluster.seal.tamper` plus a rate-bounded WARN log line; no audit record. The audit trail records data-access decisions and a tamper burst is attacker-driven. Section 10 |
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

Every metric is one constant of `Metric`, with no free-form tag or value (core:Metric.java:4); the set is pinned by `PrivacyMetricsTest`. A new metric is a visible diff to that enum and test. **The API does carry one closed tag, `source`**: `increment(Metric, sourceName)` and `record(Metric, sourceName, Duration)` (core:PrivacyMetrics.java:21, core:PrivacyMetrics.java:26) become the registry tag `source` (server:ServerPrivacyMetrics.java:28, server:ServerPrivacyMetrics.java:33). Its value is the configured source name for `dataprism.source.latency` and `dataprism.source.errors` (orchestration:SourceFanOut.java:130) and the `PrivacyNamespace` constant name for `dataprism.identity.cache.hit`, `.miss`, `dataprism.identity.collision` and `dataprism.reidentification` (hazelcast:CachingSyntheticValueSource.java:85, hazelcast:ScopeIdentityIndex.java:68). So a real source name is a metric label, while the audit and the tool result use aliases unless `EXPOSE_SOURCE_NAMES` is held; the parameter is named `sourceName` although it also carries a namespace.

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
| `dataprism.approval.decision.audit_unavailable` | shipped* | 0.7.0* | core:Metric.java:25 (`APPROVAL_DECISION_AUDIT_UNAVAILABLE`) | counts approves refused because their `ALLOW:APPROVED` could not be written; nothing was committed (t235) |
| `dataprism.approval.decision.unconfirmed` | shipped* | 0.7.0* | core:Metric.java:26 (`APPROVAL_DECISION_UNCONFIRMED`) | counts approves whose `ALLOW:APPROVED` was written and whose outcome is unknown or whose linked `DENY` could not be written (t235) |
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
| `dataprism.cluster.seal.tamper` | proposed | 0.8 | design/cluster-security.md:876 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (counter), one `Metric` constant `CLUSTER_SEAL_TAMPER`, `_` inside a segment. |
| `dataprism.cluster.seal.unsupported_format` | proposed | 0.8 | design/cluster-security.md:877 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (counter), one `Metric` constant `CLUSTER_SEAL_UNSUPPORTED_FORMAT`, `_` inside a segment. |
| `dataprism.cluster.seal.salt_rejected` | proposed | 0.8 | design/cluster-security.md:878 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (counter), one `Metric` constant `CLUSTER_SEAL_SALT_REJECTED`, `_` inside a segment. |
| `dataprism.cluster.members.unverified` | proposed | 0.8 | design/cluster-security.md:879 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (gauge), one `Metric` constant `CLUSTER_MEMBERS_UNVERIFIED`, `_` inside a segment. Emitted through the default no-op `PrivacyMetrics.gauge` (OD-215-5 (a)). |
| `dataprism.cluster.pause.fresh` | proposed | 0.8 | design/cluster-security.md:880 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (gauge), one `Metric` constant `CLUSTER_PAUSE_FRESH`, `_` inside a segment. Emitted through the default no-op `PrivacyMetrics.gauge` (OD-215-5 (a)). |
| `dataprism.cluster.pause.age_seconds` | proposed | 0.8 | design/cluster-security.md:881 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (gauge), one `Metric` constant `CLUSTER_PAUSE_AGE_SECONDS`, `_` inside a segment. Emitted through the default no-op `PrivacyMetrics.gauge` (OD-215-5 (a)). |
| `dataprism.cluster.pause.conflict` | proposed | 0.8 | design/cluster-security.md:882 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (counter), one `Metric` constant `CLUSTER_PAUSE_CONFLICT`, `_` inside a segment. |
| `dataprism.cluster.rotation.accepting_next` | proposed | 0.8 | design/cluster-security.md:883 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (gauge), one `Metric` constant `CLUSTER_ROTATION_ACCEPTING_NEXT`, `_` inside a segment. Emitted through the default no-op `PrivacyMetrics.gauge` (OD-215-5 (a)). |
| `dataprism.cluster.rotation.blocked` | proposed | 0.8 | design/cluster-security.md:884 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (counter), one `Metric` constant `CLUSTER_ROTATION_BLOCKED`, `_` inside a segment. |
| `dataprism.cluster.rotation.behind` | proposed | 0.8 | design/cluster-security.md:885 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (gauge), one `Metric` constant `CLUSTER_ROTATION_BEHIND`, `_` inside a segment. Emitted through the default no-op `PrivacyMetrics.gauge` (OD-215-5 (a)). |
| `dataprism.cluster.seal.entries_under_previous` | proposed | 0.8 | design/cluster-security.md:886 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (gauge), one `Metric` constant `CLUSTER_SEAL_ENTRIES_UNDER_PREVIOUS`, `_` inside a segment. Emitted through the default no-op `PrivacyMetrics.gauge` (OD-215-5 (a)). |
| `dataprism.cluster.lock.timeout` | proposed | 0.8 | design/cluster-security.md:887 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (counter), one `Metric` constant `CLUSTER_LOCK_TIMEOUT`, `_` inside a segment. |
| `dataprism.cluster.floor.refusal` | proposed | 0.8 | design/cluster-security.md:888 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (counter), one `Metric` constant `CLUSTER_FLOOR_REFUSAL`, `_` inside a segment. |
| `dataprism.cluster.legacy.entries` | proposed | 0.8 | design/cluster-security.md:889 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-7, OD-200-15 | metric (gauge), one `Metric` constant `CLUSTER_LEGACY_ENTRIES`, `_` inside a segment. It reports a level, the latest membership check's count (task 222; changed from counter). |

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
| `dataprism.hazelcast.approval-sweep-interval` | shipped* | 0.7.0* | FZ:35 | Duration | PT5M | full |
| `dataprism.hazelcast.cluster-name` | shipped | <=0.6.0 | FZ:36 | String | null | full |
| `dataprism.hazelcast.identity-cache-ttl` | shipped | <=0.6.0 | FZ:37 | Duration | null | full |
| `dataprism.hazelcast.join.kubernetes.namespace` | shipped | <=0.6.0 | FZ:40 | String | null | full |
| `dataprism.hazelcast.join.kubernetes.service-dns` | shipped | <=0.6.0 | FZ:41 | String | null | full |
| `dataprism.hazelcast.join.kubernetes.service-name` | shipped | <=0.6.0 | FZ:42 | String | null | full |
| `dataprism.hazelcast.join.members` | shipped | <=0.6.0 | FZ:43 | List<String> | [] | full |
| `dataprism.hazelcast.join.mode` | shipped | <=0.6.0 | FZ:44 | String | null | full |
| `dataprism.hazelcast.lock-timeout` | shipped* | 0.7.0* | FZ:45 | Duration | PT2S | full |
| `dataprism.hazelcast.map-store-enabled` | shipped | <=0.6.0 | FZ:46 | boolean | false | full |
| `dataprism.hazelcast.member.interface` | shipped | <=0.6.0 | FZ:48 | String | null | full |
| `dataprism.hazelcast.member.port` | shipped | <=0.6.0 | FZ:49 | Integer | null | full |
| `dataprism.hazelcast.persistence-enabled` | shipped | <=0.6.0 | FZ:50 | boolean | false | full |
| `dataprism.hazelcast.reidentification-controls-reference` | shipped | 0.4.0 | FZ:51 | String | null | full |
| `dataprism.hazelcast.reidentification-enabled` | shipped | 0.4.0 | FZ:52 | boolean | false | full |
| `dataprism.hazelcast.tls-key-reference` | shipped | <=0.6.0 | FZ:53 | String | null | full |
| `dataprism.hazelcast.tls-trust-reference` | shipped | <=0.6.0 | FZ:54 | String | null | full |
| `dataprism.hazelcast.topology` | shipped | 0.4.0 | FZ:55 | String | null | full |
| `dataprism.identity.resolver` | shipped | 0.3.0 | FZ:57 | String | null | full |
| `dataprism.limits.max-concurrency` | shipped* | 0.7.0* | FZ:59 | int | 4 | full |
| `dataprism.limits.max-records` | shipped* | 0.7.0* | FZ:60 | int | 500 | full |
| `dataprism.limits.max-response-size` | shipped* | 0.7.0* | FZ:61 | org.springframework.util.unit.DataSize | 524288B | full |
| `dataprism.limits.max-sources` | shipped* | 0.7.0* | FZ:62 | int | 8 | full |
| `dataprism.limits.per-source-timeout` | shipped* | 0.7.0* | FZ:63 | Duration | PT3S | full |
| `dataprism.limits.scope-read-budget` | shipped* | 0.7.0* | FZ:64 | int | 100 | full |
| `dataprism.metrics.registry-reference` | shipped | <=0.6.0 | FZ:66 | String | null | full |
| `dataprism.metrics.sink` | shipped | <=0.6.0 | FZ:67 | String | null | full |
| `dataprism.operator.address` | shipped | <=0.6.0 | FZ:69 | String | null | full |
| `dataprism.operator.enabled` | shipped | 0.4.0 | FZ:70 | boolean | false | full |
| `dataprism.operator.port` | shipped | <=0.6.0 | FZ:71 | Integer | null | full |
| `dataprism.operator.required-audience` | shipped | <=0.6.0 | FZ:72 | String | null | full |
| `dataprism.operator.required-scope` | shipped | <=0.6.0 | FZ:73 | String | null | name only |
| `dataprism.oversight.approval-required-tools` | shipped | <=0.6.0 | FZ:75 | List<String> | [] | full |
| `dataprism.oversight.approval-ttl` | shipped | <=0.6.0 | FZ:76 | Duration | PT15M | name only |
| `dataprism.oversight.caller-rate-limit.requests` | shipped | <=0.6.0 | FZ:78 | Integer | null | name only |
| `dataprism.oversight.caller-rate-limit.window` | shipped | <=0.6.0 | FZ:79 | Duration | PT1M | name only |
| `dataprism.oversight.max-pending-per-requester` | shipped | <=0.6.0 | FZ:80 | int | 5 | name only |
| `dataprism.privacy.descriptor-file` | shipped | <=0.6.0 | FZ:82 | String | null | full |
| `dataprism.privacy.hmac-key.environment-variable` | shipped | <=0.6.0 | FZ:84 | String | null | full |
| `dataprism.privacy.hmac-key.key-id` | shipped | <=0.6.0 | FZ:85 | String | null | full |
| `dataprism.privacy.hmac-key.provider-reference` | shipped | <=0.6.0 | FZ:86 | String | null | full |
| `dataprism.privacy.hmac-key.value` | shipped | <=0.6.0 | FZ:87 | String | null | full |
| `dataprism.privacy.locale` | shipped | <=0.6.0 | FZ:88 | String | neutral | full |
| `dataprism.privacy.profile` | shipped | <=0.6.0 | FZ:89 | String | null | full |
| `dataprism.privacy.scope-lifetime` | shipped | <=0.6.0 | FZ:90 | Duration | null | full |
| `dataprism.reidentification.approval-ttl` | shipped | <=0.6.0 | FZ:92 | Duration | PT15M | name only |
| `dataprism.reidentification.enabled` | shipped | 0.4.0 | FZ:93 | boolean | false | full |
| `dataprism.reidentification.four-eyes` | shipped | <=0.6.0 | FZ:94 | boolean | true | name only |
| `dataprism.reidentification.max-pending-per-requester` | shipped | <=0.6.0 | FZ:95 | int | 5 | name only |
| `dataprism.reidentification.purposes` | shipped | <=0.6.0 | FZ:96 | List<String> | [] | name only |
| `dataprism.reidentification.roles` | shipped | <=0.6.0 | FZ:97 | Map<String,Set<enum[REQUEST, APPROVE]>> | {} | full |
| `dataprism.security.caller-claims.investigation` | shipped | <=0.6.0 | FZ:100 | String | null | full |
| `dataprism.security.caller-claims.principal` | shipped | <=0.6.0 | FZ:101 | String | null | full |
| `dataprism.security.caller-claims.roles` | shipped | <=0.6.0 | FZ:102 | String | null | full |
| `dataprism.security.jwt.audience` | shipped | 0.4.0 | FZ:104 | String | null | full |
| `dataprism.security.jwt.issuer` | shipped | <=0.6.0 | FZ:105 | String | null | full |
| `dataprism.security.jwt.issuer-discovery-uri` | shipped | <=0.6.0 | FZ:106 | String | null | full |
| `dataprism.security.jwt.jwk-set-uri` | shipped | 0.1.0 | FZ:107 | String | null | full |
| `dataprism.security-policy.purposes` | shipped | <=0.6.0 | FZ:109 | List<String> | [] | full |
| `dataprism.security-policy.roles` | shipped | <=0.6.0 | FZ:110 | Map<String,List<String>> | {} | full |
| `dataprism.sources` | shipped | 0.1.0 | FZ:111 | Map<String,bean> | {} | full |
| `dataprism.sources.*.base-url` | shipped | <=0.6.0 | FZ:112 | String | null | full |
| `dataprism.sources.*.mtls.key-reference` | shipped | <=0.6.0 | FZ:114 | String | null | full |
| `dataprism.sources.*.mtls.trust-reference` | shipped | <=0.6.0 | FZ:115 | String | null | full |
| `dataprism.sources.*.service-credential-reference` | shipped | <=0.6.0 | FZ:116 | String | null | full |
| `dataprism.sources.*.timeout` | shipped | <=0.6.0 | FZ:117 | Duration | null | full |
| `dataprism.transport.fixture-development` | shipped | 0.3.0 | FZ:119 | boolean | false | full |
| `dataprism.transport.http.path` | shipped | <=0.6.0 | FZ:121 | String | /mcp | full |
| `dataprism.transport.mode` | shipped | <=0.6.0 | FZ:122 | enum[HTTP, STDIO] | HTTP | full |
| `dataprism.json-sources.config-location` | shipped | 0.1.0 | connectors-rest:ConfiguredJsonSourcesAutoConfiguration.java:98 | String | null | full |

The release column is a lower bound: `<=0.6.0` means the pinned list or the configuration page first names it in 0.6.0. The keys added in 0.7.0* are `dataprism.limits.*` (six, D-070-4 (a), t189) and `dataprism.hazelcast.lock-timeout` and `dataprism.hazelcast.approval-minimum-members` (t202; D-070-15, D-070-17), and `dataprism.hazelcast.approval-sweep-interval` (t233; OD-200-14). `dataprism.sources.*.…` is written with `*` in the pinned list and `<name>` in `docs/configuration.md`; the register treats them as one pattern. Spelled out, the documents' forms are `dataprism.sources.<name>` with `dataprism.sources.<name>.base-url`, `dataprism.sources.<name>.mtls.key-reference`, `dataprism.sources.<name>.mtls.trust-reference`, `dataprism.sources.<name>.service-credential-reference` and `dataprism.sources.<name>.timeout`; `dataprism.audit.output.field-names.<field>` is one entry of the map `dataprism.audit.output.field-names`; `dataprism.audit.output.*` and `dataprism.security-policy.*` are the groups' wildcard spellings.

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
| `dataprism.hazelcast.security.isolation.attestation` | `network-policy`, `private-network`, `mesh-mtls`, `wireguard`, `ipsec`, `none-acknowledged` | proposed | 0.8 | design/cluster-security.md:579 | an attestation, not a verification; reported on health and in one startup log line, not audited (OD-215-6 (c)); required on a joining member, refused with `CLUSTER_SETTINGS_IGNORED` and not required on `join.mode: none` (OD-215-8 (a)) |
| `dataprism.hazelcast.security.unverified-member.action` | `refuse`, `alert` | proposed | 0.8 | design/cluster-security.md:582 | cluster-security OD-8; `INVALID_UNVERIFIED_MEMBER_POLICY` otherwise; refused with `CLUSTER_SETTINGS_IGNORED` on `join.mode: none` |

### 6.3 Other shipped property-like names

| Name | State | Release | Source | Note |
|---|---|---|---|---|
| *(replaced)* the unreleased Hazelcast config property for the approval sweep interval | withdrawn | n/a | t233 | Now the Spring-bound `dataprism.hazelcast.approval-sweep-interval` (section 6.1; OD-200-14). A `Config` that still carries the old name is neither read nor refused; the old name is in section 10 |
| `hazelcast.lock.max.lease.time.seconds` (Hazelcast's own) | not ours | n/a | docs/multiple-instances.md; t216 | If set below the lease a lock-taking call asks for (five times `lock-timeout`), the cluster rejects the lock and the call is refused with `CLUSTER_OPERATION_REFUSED`: approval creation, `consumeApproved`, the caller rate limiter and the read budget (t233 surfaces it on the admission, read, re-identification and operator paths) |
| YAML file `sources:` (root keys `sources`, `tls`; entry keys `base-url`, `path`, `timeout`, `correlation-header`; tls keys `key-store`, `key-store-password-env`, `trust-store`, `trust-store-password-env`, `store-type`) | shipped | <=0.6.0 | connectors-rest:RestSources.java:34 | unknown keys refuse (`UNKNOWN_CONFIG_KEY`); kebab case |
| YAML file `json-sources:` (root `json-sources`, `tls`; source keys `base-url`, `path`, `timeout`, `model-version`, `subject-json-path`, `fields`, `nested-catalogues`, `correlation-header`; field keys `identifier`, `nonSensitive`, `classifications`, `namespace`, `action`, `nested`) | shipped | <=0.6.0 | connectors-rest:ConfiguredJsonSources.java:54 | kebab case for source keys, camelCase for field keys (`nonSensitive`): section 13 |
| model descriptor file (root `models`; keys `exposed`, `descendable`, `undeclaredFields`, `fields`; field keys `classifications`, `namespace`, `action`, `subject`, `nonSensitive`, `identifier`) | shipped | <=0.6.0 | core:ModelDescriptors.java:29 | camelCase; `dataprism.privacy.descriptor-file` points at it |

### 6.4 Proposed keys

From the closing tables, grouped by the group they join. `dataprism.limits.*` and `dataprism.audit.*` gain keys inside groups that already ship (the group is pinned, so each new key is a diff to the pinned list). Defaults and validation are in the named document; they are not repeated here, except for the `dataprism.hazelcast.security.*` rows, which carry type, default, bounds, refusal code and the `join.mode: none` rule one for one with `cluster-security.md` 4.1.

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
| `dataprism.hazelcast.security.cluster-key.key-id` | proposed | 0.8 | design/cluster-security.md:836 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; required on a joining member, with exactly one of the two source keys; must resolve, meet the strength rule, differ from every other purpose key, and contain no `.`; refusal `MISSING_CLUSTER_KEY`, `CLUSTER_KEY_UNRESOLVED`, `CLUSTER_KEY_WEAK`, `CLUSTER_KEY_SHARED`; on `join.mode: none`: optional, with the name key (both or neither); same codes when supplied. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.environment-variable` | proposed | 0.8 | design/cluster-security.md:837 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; name of the variable holding the key; exactly one of this and `provider-reference`; refusal `MISSING_CLUSTER_KEY` (neither source, or both); on `join.mode: none`: as `cluster-key.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.provider-reference` | proposed | 0.8 | design/cluster-security.md:838 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; a reference to a key provider; exactly one of this and `environment-variable`; refusal `MISSING_CLUSTER_KEY` (neither source, or both); on `join.mode: none`: as `cluster-key.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.value` | proposed | 0.8 | design/cluster-security.md:839 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; refused if set: a literal key is never accepted; refusal `LITERAL_SECRET_FORBIDDEN`; on `join.mode: none`: refused the same way. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.next.key-id` | proposed | 0.8 | design/cluster-security.md:840 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; optional, phase 1 of a rotation; differs from `current` and `previous`; the resolution, strength and sharing rules of `current`; refusal `DUPLICATE_CLUSTER_KEY_ID`, `CLUSTER_KEY_UNRESOLVED`, `CLUSTER_KEY_WEAK`, `CLUSTER_KEY_SHARED`, `MISSING_CLUSTER_KEY` (a source without a `key-id`, or the reverse); on `join.mode: none`: allowed with a `current` key (`MISSING_CLUSTER_KEY` without one); same codes. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.next.environment-variable` | proposed | 0.8 | design/cluster-security.md:841 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; as `cluster-key.next.key-id`, for the next key; refusal as `cluster-key.next.key-id`; on `join.mode: none`: as `cluster-key.next.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.next.provider-reference` | proposed | 0.8 | design/cluster-security.md:842 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; as `cluster-key.next.key-id`, for the next key; refusal as `cluster-key.next.key-id`; on `join.mode: none`: as `cluster-key.next.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.next.value` | proposed | 0.8 | design/cluster-security.md:843 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; refused if set: a literal key is never accepted; bound only so it can be refused by name; refusal `LITERAL_SECRET_FORBIDDEN`; on `join.mode: none`: refused the same way. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.previous.key-id` | proposed | 0.8 | design/cluster-security.md:843 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; optional, phase 2 of a rotation; differs from `current` and `next`; the rules of `current`; refusal `DUPLICATE_CLUSTER_KEY_ID`, `CLUSTER_KEY_UNRESOLVED`, `CLUSTER_KEY_WEAK`, `CLUSTER_KEY_SHARED`, `MISSING_CLUSTER_KEY` (as for `next`); on `join.mode: none`: allowed with a `current` key; same codes. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.previous.environment-variable` | proposed | 0.8 | design/cluster-security.md:844 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; as `cluster-key.previous.key-id`, for the previous key; refusal as `cluster-key.previous.key-id`; on `join.mode: none`: as `cluster-key.previous.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.previous.provider-reference` | proposed | 0.8 | design/cluster-security.md:846 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; as `cluster-key.previous.key-id`, for the previous key; refusal as `cluster-key.previous.key-id`; on `join.mode: none`: as `cluster-key.previous.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.cluster-key.previous.value` | proposed | 0.8 | design/cluster-security.md:847 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; refused if set: a literal key is never accepted; bound only so it can be refused by name; refusal `LITERAL_SECRET_FORBIDDEN`; on `join.mode: none`: refused the same way. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.name-key.key-id` | proposed | 0.8 | design/cluster-security.md:847 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; required on a joining member, with exactly one of the two source keys; must resolve, meet the strength rule, differ from every cluster key and every other purpose key, and contain no `.`; refusal `MISSING_CLUSTER_KEY`, `NAME_KEY_UNRESOLVED`, `NAME_KEY_WEAK`, `NAME_KEY_SHARED`; on `join.mode: none`: optional, with the cluster key (both or neither); same codes when supplied. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.name-key.environment-variable` | proposed | 0.8 | design/cluster-security.md:848 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; name of the variable holding the name key; refusal `MISSING_CLUSTER_KEY` (neither source, or both); on `join.mode: none`: as `name-key.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.name-key.provider-reference` | proposed | 0.8 | design/cluster-security.md:849 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; a reference to a key provider; refusal `MISSING_CLUSTER_KEY` (neither source, or both); on `join.mode: none`: as `name-key.key-id`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.name-key.value` | proposed | 0.8 | design/cluster-security.md:851 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type String, default unset; refused if set: a literal key is never accepted; refusal `LITERAL_SECRET_FORBIDDEN`; on `join.mode: none`: refused the same way. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.isolation.attestation` | proposed | 0.8 | design/cluster-security.md:852 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type enum, default unset; required; one of the six values in section 6.2; refusal `MISSING_ISOLATION_ATTESTATION`, `INVALID_ISOLATION_ATTESTATION`; on `join.mode: none`: refused with `CLUSTER_SETTINGS_IGNORED`; not required. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.pause-lease` | proposed | 0.8 | design/cluster-security.md:853 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type `Duration`, default `PT30S`; `PT5S` to `PT5M`; refusal `INVALID_PAUSE_LEASE`; on `join.mode: none`: allowed. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.clock-skew-tolerance` | proposed | 0.8 | design/cluster-security.md:854 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type `Duration`, default `PT5S`; `PT0S` to half of `pause-lease`; refusal `INVALID_PAUSE_LEASE`; on `join.mode: none`: allowed. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.unverified-member.action` | proposed | 0.8 | design/cluster-security.md:855 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type enum, default `refuse`; `refuse` or `alert`; refusal `INVALID_UNVERIFIED_MEMBER_POLICY`; on `join.mode: none`: refused with `CLUSTER_SETTINGS_IGNORED`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.unverified-member.grace` | proposed | 0.8 | design/cluster-security.md:856 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type `Duration`, default `PT30S`; `PT0S` to `PT10M`; refusal `INVALID_UNVERIFIED_MEMBER_POLICY`; on `join.mode: none`: refused with `CLUSTER_SETTINGS_IGNORED`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
| `dataprism.hazelcast.security.local-floor.enabled` | proposed | 0.8 | design/cluster-security.md:857 | cluster-security OD-1..16 (delegated; sealed design confirmed); OD-200-14, OD-215-8 | key. Type `boolean`, default `true`; a non-boolean fails binding; refusal none beyond binding; on `join.mode: none`: refused with `CLUSTER_SETTINGS_IGNORED`. Pinned in the frozen list. `single-node` and a supplied `PrivacyCluster` bean refuse it with `CLUSTER_SETTINGS_IGNORED` |
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
| `HmacKeyReferenceResolver` | interface | shipped | 0.1.0 | spring-boot-autoconfigure:HmacKeyReferenceResolver.java:10 | spring/boot; gains the default method `resolveProviderReference` (default: `resolve`), 0.8, used for every `provider-reference` HMAC, cluster and name key; existing resolvers are unchanged (task 222 review round 2, 2026-10-23) |
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

### 7.4 Closed vocabularies whose constant names leave the process

Enums of any visibility whose constant names reach output: a tool result, the audit, a metric tag, a token or alias, a file the verifier reads, an operator HTTP body or a configuration or descriptor value. They are contract the way a code is: renaming a constant is a breaking change. Found with E11, which lists every `enum` in `*/src/main` (30; 27 public) and every `.name()` call site (E11b), because the earlier public-only scan missed a private enum that reaches the operator API. The Release column is the first tag containing the enum's longest constant name. "Not traced" means no exit was found in the time of this task; the freeze test must classify those too (section 15).

| Enum | Constants | Leaves the process via | Source | Release | Pinned by |
|---|---|---|---|---|---|
| `PrivacyNamespace` | `NONE`, `PERSON_IDENTITY`, `PERSON_NAME`, `PERSON_FIRST_NAME`, `PERSON_LAST_NAME`, `ORGANISATION_IDENTITY`, `ORGANISATION_NAME`, `ACCOUNT_IDENTITY`, `ADDRESS`, `EMAIL`, `PHONE`, `GOVERNMENT_IDENTIFIER`, `FINANCIAL_VALUE`, `BANK_ACCOUNT`, `EMPLOYMENT_IDENTITY`, `CUSTOM` | `namespace` of every finding (orchestration:NamespaceCorrelationService.java:101); the prefix of every token (pseudonymisation:HmacValueTokenSource.java:50) and of every scope-local source alias (pseudonymisation:HmacSyntheticGenerator.java:101); the metric tag `source` (section 5); the `namespace` key of a descriptor, `json-sources` field or annotation | annotations:PrivacyNamespace.java:14 | 0.1.0 | none |
| `DataClassification` | `PII`, `PHI`, `FINANCIAL`, `BANKING`, `TAX_IDENTIFIER`, `GOVERNMENT_IDENTIFIER`, `ADDRESS`, `CONTACT`, `CREDENTIAL`, `SECURITY`, `CONFIDENTIAL`, `BIOMETRIC`, `GENETIC`, `ETHNIC_ORIGIN`, `POLITICAL_OPINION`, `RELIGIOUS_BELIEF`, `TRADE_UNION`, `SEX_LIFE_ORIENTATION` | the `classifications` lists and rules of the profile YAML (`privacy-profiles-default.yaml`) and descriptor files; annotation values; the name of the matched classification in a sensitive-pattern violation (validation:SensitivePatternValidator.java:64, operator-facing); `SPECIAL_CATEGORIES` is part of the contract | annotations:DataClassification.java:7 | 0.1.0 | none; proposed `FREE_TEXT` (task 208) |
| `SensitivityLevel` | `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED` | annotation attribute values; not traced to a file or result | annotations:SensitivityLevel.java:4 | 0.1.0 | none |
| `UndeclaredFields` | `PROFILE_DEFAULT`, `NON_SENSITIVE`, `REDACT`, `DROP` | annotation attribute and the `undeclaredFields` descriptor key (core:ModelDescriptors.java:29) | annotations:UndeclaredFields.java:16 | 0.1.0 | none |
| `PrivacyAction` | `PASS_THROUGH`, `REDACT`, `REMOVE`, `HASH`, `SYNTHESIZE`, `TOKENIZE`, `GENERALIZE` | disposition values in the audit (section 4.3); profile YAML `action` | annotations:PrivacyAction.java:10 | 0.1.0 | none |
| `PrivacyProfile.UnclassifiedBehaviour` | `FAIL_REQUEST`, `REDACT_AND_WARN`, `DROP_AND_WARN`, `PASS_THROUGH_UNSAFE` | the `unclassified` key of a profile YAML: refuse, redact or drop unclassified fields, or release them (`PASS_THROUGH_UNSAFE`, spelled UNSAFE so it cannot be enabled unread) | core:PrivacyProfile.java:46 | 0.1.0 | none |
| `GeneralizationRule.Kind` | `NUMERIC_BAND`, `DATE_TRUNCATION` | `type:` of a profile generalisation rule (`privacy-profiles-default.yaml:49`) | core:GeneralizationRule.java:28 | 0.1.0 | none |
| `GeneralizationRule.Precision` | `YEAR`, `MONTH` | `precision:` of a date-truncation rule | core:GeneralizationRule.java:35 | 0.1.0 | none |
| `ConsistencyFinding.Kind` | `INCONSISTENT`, `FORMATTING_ONLY`, `ABBREVIATION`, `MISSING_IN_SOME_SOURCES`, `SUSPECTED_INSTRUCTION_CONTENT`, `CONSISTENT` | `kind` of every finding (section 1.4) | core:ConsistencyFinding.java:46 | 0.1.0 | `ConsistencyFinding.Kind` is documented in docs/tools.md |
| `SourceOutcome.Status` | `ANSWERED`, `NO_DATA`, `TIMED_OUT`, `FAILED`, `CIRCUIT_OPEN`, `SKIPPED_OVER_LIMIT`, `REFUSED` | `status` of every per-source result (section 2) | orchestration:SourceOutcome.java:19 | 0.1.0 | `SourceStatusCodesTest` |
| `Metric` | `MCP_REQUESTS`, `MCP_DENIED`, `PRIVACY_TRANSFORMATIONS`, `PRIVACY_VALIDATION_FAILURES`, `IDENTITY_CACHE_HIT`, `IDENTITY_CACHE_MISS`, `SOURCE_LATENCY`, `SOURCE_ERRORS`, `IDENTITY_COLLISION`, `PRIVACY_FAILCLOSED`, `PRIVACY_SOURCE_WITHHELD`, `REIDENTIFICATION`, `APPROVAL_RELEASE_SKIPPED`, `APPROVAL_DECISION_AUDIT_UNAVAILABLE`, `APPROVAL_DECISION_UNCONFIRMED`, `AUDIT_RETENTION_CHAIN_UNVERIFIED`, `AUDIT_RETENTION_ANCHOR_FAILED`, `AUDIT_RETENTION_DELETE_FAILED`, `AUDIT_RETENTION_FAILED` | metric names (section 5) | core:Metric.java:10 | 0.4.0 | `PrivacyMetricsTest` |
| `AuditCheckpoint.Kind` | `BOOT`, `PERIODIC`, `SHUTDOWN`, `RETENTION_ANCHOR` | `kind` of a checkpoint line (section 4.4) | core:AuditCheckpoint.java:36 | 0.4.0 | not pinned |
| `AuditChainVerifier.AnomalyType` | `INTERRUPTED_WRITE_FRAGMENT`, `DUPLICATE_SEQUENCE`, `UNPARSEABLE_RECORD`, `RETENTION_ANCHOR_REJECTED`, `VERSION_REGRESSION`, `FIELD_COUNT_MISMATCH`, `TORN_CHECKPOINT_LINE` | verifier output (section 4.4) | core:AuditChainVerifier.java:702 | 0.3.0 | not pinned |
| `AuditChainVerifier.CheckpointFindingType` | `TRUNCATED_BEFORE_CHECKPOINT`, `MISSING_WRITER` | verifier output (section 4.4) | core:AuditChainVerifier.java:771 | 0.4.0 | not pinned |
| `ApprovalRequest.Kind` | `TOOL_CALL`, `REIDENTIFICATION` | **client-visible**: `kind` of every entry of `GET /operator/approvals` and `/{id}` (server:OversightOperatorController.java:81; docs/reidentification.md:133); also in the cluster map value (hazelcast:HazelcastApprovalStore.java:726) | core:ApprovalRequest.java:30 | 0.1.0 | not pinned |
| `ApprovalRequest.Status` | `PENDING`, `APPROVED`, `REJECTED`, `CONSUMED`, `EXPIRED` | **client-visible**: `status` of every entry of `GET /operator/approvals` and `/{id}`, with a stored `PENDING` past its expiry shown as `EXPIRED` (server:OversightOperatorController.java:74; docs/reidentification.md); also the cluster map value (hazelcast:HazelcastApprovalStore.java:729) | core:ApprovalRequest.java:32 | 0.4.0 | not pinned |
| `OversightOperatorController.TargetType` | `ALL`, `TOOL`, `SCOPE` | **client-visible**: `target` of `POST /operator/pause` and `/resume`, accepted (server:OversightOperatorController.java:263) and returned (server:OversightOperatorController.java:89, server:OversightOperatorController.java:106); docs/reidentification.md:130. A private enum | server:OversightOperatorController.java:278 | 0.4.0 | not pinned |
| `PrivacyScopeType` | `REQUEST`, `SESSION`, `INVESTIGATION`, `CASE` | scope types; not traced to a file or result | core:PrivacyScopeType.java:10 | 0.1.0 | not pinned |
| `EffectivePrivacyPolicy.Decided` | `PROFILE_RULE`, `SPECIAL_CATEGORY_DEFAULT`, `ANNOTATION`, `SAFE_DEFAULT`, `DECLARED_NON_SENSITIVE`, `IDENTIFIER`, `UNCLASSIFIED` | not traced (policy provenance) | core:EffectivePrivacyPolicy.java:28 | 0.4.0 | not pinned |
| `Permission` | `REQUEST`, `APPROVE` | `dataprism.reidentification.roles` values (FZ:97); the autoconfigure module has a second `ReidentificationProperties.Permission` with the same constants (spring-boot-autoconfigure:ReidentificationProperties.java:13) | reidentification:Permission.java:4 | 0.1.0 | not pinned |
| `TransportProperties.Mode` | `HTTP`, `STDIO` | `dataprism.transport.mode` (section 6.2) | spring-boot-autoconfigure:TransportProperties.java:4 | 0.1.0 | `PropertyNamesFrozenTest` shows the enum |
| `CorrelationRequirement` | `OPTIONAL`, `REQUIRED` | tool options only; not traced to a file or result | mcp:CorrelationRequirement.java:9 | 0.5.0 | not pinned |
| `PoolKind` | `FIRST_NAME`, `LAST_NAME`, `STREET`, `TOWN`, `ORGANISATION` | synthetic-value pools; not traced to a result | pseudonymisation:PoolKind.java:4 | 0.1.0 | not pinned |
| `PrivacyExtensionPoints.Classification` | `REPLACEABLE`, `PRIVACY_CRITICAL` | bean classification; internal | spring-boot-autoconfigure:PrivacyExtensionPoints.java:31 | 0.1.0 | `AutoConfiguredBeanClassificationTest` |
| `PrivacyExtensionPoints.Guard` | `NONE`, `PRIMARY`, `COMPETING_BEAN_REFUSAL` | bean guard; internal | spring-boot-autoconfigure:PrivacyExtensionPoints.java:33 | 0.1.0 | `AutoConfiguredBeanClassificationTest` |

Not listed: `ApprovalRefusedException.Code` (internal, mapped in section 3.3), `PrivacyClusterRefusal.Code` (section 3.5), the second `Permission` enum in the autoconfigure module (same constants as the first), and two enums checked and found not to reach output: the private `InboundCorrelation.State` (compared, never written) and the package-private `SensitiveDataScanner.Detector` (its `classification` is written, not its name; the `shapeMethod` strings are separate text).

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
| `dataprism.identity.s1` | map | proposed | 0.8 | design/cluster-security.md:890 | sealed successor of the 0.7 map `dataprism.identity`: identity cache. Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.reidentification.s1` | map | proposed | 0.8 | design/cluster-security.md:891 | sealed successor of the 0.7 map `dataprism.reidentification`: re-identification index. Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.budget.s1` | map | proposed | 0.8 | design/cluster-security.md:892 | sealed successor of the 0.7 map `dataprism.budget`: read budget counts. Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.oversight.s1` | map | proposed | 0.8 | design/cluster-security.md:893 | sealed successor of the 0.7 map `dataprism.oversight`: the sealed, sequenced, leased pause document. Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.approval.s1` | map | proposed | 0.8 | design/cluster-security.md:894 | sealed successor of the 0.7 map `dataprism.approval`: approval entries, key `scoped(approval, scope, id)` (OD-215-2 (a)). Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.approval.id.s1` | map | proposed | 0.8 | design/cluster-security.md:895 | sealed successor of the 0.7 map `dataprism.approval.id`: approval id claims, key `mapKey(claims, id)` with no scope token (OD-215-2 (a)). Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.approval.pending.s1` | map | proposed | 0.8 | design/cluster-security.md:896 | sealed successor of the 0.7 map `dataprism.approval.pending`: pending approvals per requester. Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.callerrate.s1` | map | proposed | 0.8 | design/cluster-security.md:897 | sealed successor of the 0.7 map `dataprism.callerrate`: caller rate counters. Generation suffix `.s1` (OD-215-1 (a)); 0.7 values are never read: 0.8 only checks the pre-0.8 map for entries and, for `dataprism.oversight`, writes the fence |
| `dataprism.approval.consumed.s1` | map | proposed | 0.8 | design/cluster-security.md:898 | consumed-approval tombstones, key `mapKey(consumed, approval id)`. Entry TTL is `expiresAt` plus `clock-skew-tolerance` (a memory bound only), no size-based eviction, union merge policy, and the approval map's quorum and backups (cluster-security 2.3.4). No 0.7 predecessor |
| `auditIntegrity` | health indicator (bean `auditIntegrityHealthIndicator`) | shipped | <=0.6.0 | spring-boot-autoconfigure:PrivacyExtensionPoints.java:76 | reports retention failure codes (section 3.7) |
| `dataprism/link/v1/seal` | key-derivation labels | proposed | 0.8 | design/sealed-links.md:502 | sealed-links OD-1..17 (delegated) |
| `/fingerprint` | key-derivation labels | proposed | 0.8 | design/sealed-links.md:502 | sealed-links OD-1..17 (delegated); same closing-table row as `dataprism/link/v1/seal` |
| `/caller` | key-derivation labels | proposed | 0.8 | design/sealed-links.md:502 | sealed-links OD-1..17 (delegated); same closing-table row as `dataprism/link/v1/seal` |
| `dataprism/cluster/v1/aead/<map>` | key-derivation labels | proposed | 0.8 | design/cluster-security.md:900 | cluster-security 4.7 (OD-215-4 (a)): the AES-GCM subkey of one map. Same helper and prefix style as `dataprism/link/v1/seal` |
| `dataprism/cluster/v1/kcv` | key-derivation labels | proposed | 0.8 | design/cluster-security.md:901 | cluster-security 4.7 (OD-215-4 (a)): the key check value of a purpose key (OD-215-7 (a)). Same helper and prefix style as `dataprism/link/v1/seal` |
| `dataprism/cluster/v1/name/<map>` | key-derivation labels | proposed | 0.8 | design/cluster-security.md:902 | cluster-security 4.7 (OD-215-4 (a)): the HMAC subkey for the map keys of one map. Same helper and prefix style as `dataprism/link/v1/seal` |
| `dataprism/cluster/v1/scope` | key-derivation labels | proposed | 0.8 | design/cluster-security.md:903 | cluster-security 4.7 (OD-215-4 (a)): the HMAC subkey for the scope token. Same helper and prefix style as `dataprism/link/v1/seal` |
| `link key ids held (list)` | health-endpoint field | proposed | 0.8 | design/sealed-links.md:504 | sealed-links OD-1..17 (delegated) |
| `dataprism.membercard.s1` | map | proposed | 0.8 | design/cluster-security.md:899 | member cards (key: keyed hash of the member UUID) and the sentinel. **The sentinel is stored under the reserved key `sentinel`**, which no card key can equal (card keys are 43-character keyed hashes); card readers skip it. Generation suffix `.s1` |
| `clusterSecurity` | health contributor | proposed | 0.8 | design/cluster-security.md:904 | cluster-security OD-1..16 (delegated; sealed design confirmed); readiness group, not liveness; reports the attestation on a joining member and the member count |

The values stored in these maps (field order, NUL-joined strings, claim and owner formats with creation tokens; t232:348) are internal. They are not part of the freeze: cluster-security replaces them with sealed values in 0.8. The cutover is non-rolling (cluster-security OD-215-1 (a)): a 0.8 member that starts with no verified 0.8 member present and finds an entry in one of the eight pre-0.8 maps above (other than the fence) refuses with `UNSEALED_CLUSTER_STATE`, so the 0.7 rows above stay registered as names whose values no 0.8 member reads: 0.8 only checks the pre-0.8 maps for entries and writes the oversight fence. The one exception is `dataprism.oversight` (0.7), where every serving 0.8 member keeps the entry key `all` = `Boolean.TRUE` as a fence that makes a 0.7 member read the cluster as paused (owner decision 2026-10-21, extends OD-215-1; implemented by task 228, which owns the pause document). Map and key names are the exception that does matter: sealing moves names under a name key, and each map carries a generation suffix, so a map name is a migration contract.

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
| cluster design (192) | built in 0.8 under 215's briefs | proposed | Sealed cluster state, as amended by task 218 and built by task 222: 22 `dataprism.hazelcast.security.*` keys (the two refused literals `cluster-key.next.value` and `cluster-key.previous.value` added), 18 codes (the `CLUSTER_KEY_*` and `NAME_KEY_*` family replaces `INVALID_CLUSTER_KEY`), 14 metrics (each one `Metric` constant), ten `.s1` maps (the eight sealed successors, the consumed-approval tombstones and `dataprism.membercard.s1`), four key-derivation labels, health `clusterSecurity`. The audit event `CLUSTER_STATE_TAMPER`, the key `security.mode` and its two codes, and the `security.lock-timeout` row are withdrawn (section 10) | rows in sections 3.5, 4.3, 5, 6.2, 6.4 and 8.1 | sealed design governs (reconciliation:7); D-1.0-3, D-1.0-7; cluster-security OD-1..OD-16 |
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
| `dataprism.approval.pending.sweep-seconds` | withdrawn | t216; t232 | unreleased Hazelcast config property (integer seconds, 1 to 86400, default 300), replaced by `dataprism.hazelcast.approval-sweep-interval` under OD-200-14 and implemented by t233; it was never released, so there is no migration entry |
| `dataprism.hazelcast.security.lock-timeout` | withdrawn | design/cluster-security.md (removed by task 218; the status list names it) | superseded by the shipped `dataprism.hazelcast.lock-timeout` (t202:349); D7. The cluster design no longer has the row |
| `dataprism.hazelcast.security.mode` | withdrawn | design/cluster-security.md (removed by task 218) | OD-200-9 (b): a joining member is sealed by definition; D8 |
| `MISSING_CLUSTER_SECURITY_MODE`, `UNSUPPORTED_CLUSTER_SECURITY_MODE` | withdrawn | design/cluster-security.md (removed by task 218) | OD-200-9 (b): the key they checked is dropped; `MISSING_CLUSTER_KEY` already refuses a joining member without a key |
| `INVALID_CLUSTER_KEY` | withdrawn | design/cluster-security.md (removed by task 218) | OD-215-3 (a): replaced by `CLUSTER_KEY_UNRESOLVED`, `CLUSTER_KEY_WEAK`, `CLUSTER_KEY_SHARED`, `NAME_KEY_UNRESOLVED`, `NAME_KEY_WEAK`, `NAME_KEY_SHARED` and `DUPLICATE_CLUSTER_KEY_ID` (the family of OD-200-10) |
| `CLUSTER_STATE_TAMPER` (audit event) | withdrawn | design/cluster-security.md (removed by task 218) | OD-215-6 (b): tamper is the metric `dataprism.cluster.seal.tamper` plus a rate-bounded WARN log line, with no audit record |
| `isolation-only` value of the withdrawn `security.mode` key | withdrawn | design/cluster-security.md (removed by task 218) | cluster-security OD-2 (i): not allowed |
| compatibility mode that reads the old `oversight` map and dual-writes pauses (no name) | withdrawn | design/cluster-security.md 4.8 | OD-215-1 (a): the cutover is non-rolling; `UNSEALED_CLUSTER_STATE` enforces it |
| key-derivation labels with the prefix `dp/cluster/v1/` | withdrawn | design/cluster-security.md (removed by task 218) | OD-215-4 (a): the prefix is `dataprism/cluster/v1/`, as the link labels were always `dataprism/link/v1/`. Also withdrawn: the `dataprism/cluster/v1/lock/<map>` label and the lock subkey (coordinator decision 2026-10-21: a lock key is the keyed map key of the entry it guards) |
| link:id, link:attempts, links:issued, links:omitted, resolver:<name>, resolver:<name>:ms (audit disposition keys) | withdrawn | design/sealed-links.md:484; design/key-resolvers.md:239 | sealed-links OD-13 (C): counts, ids and timings are metrics, not dispositions |
| INVALID:<reason> (disposition value) | withdrawn | design/sealed-links.md:246 | replaced by `REFUSED:LINK_<REASON>` under OD-13 (C) |
| dataprism.catalog.real-names-purposes, dataprism.catalog.real-names-scope-types | withdrawn | design/catalog.md:785 | rejected options of catalog OD-C3; (c) `real-names-scopes` decided |
| `dataprism.links.max-chain-depth`, `LINK_DEPTH_EXCEEDED` | withdrawn | design/sealed-links.md:500 and :250 | OD-200-8 (a): reachable only under the rejected link option (b) |
| dataprism.paging.key-id, PAGE_INVALID | withdrawn | design/batch-and-bulk-follow.md:1086, :1087 | rejected options of batch OD-B2 and OD-B3: page links use `dataprism.links.key-id` and `LINK_INVALID` |

## 11. Collisions and near-collisions

Found by comparing every name in sections 1 to 8 against every other, exactly and by shared stem (command E9 in the Outcome finds the exact repeats inside the closing tables; the rest was read). "Accepted" means the two meanings live in different kinds or objects and neither can be mistaken for the other in a single field; the rule that makes it safe is stated. "Decision" is a product or naming choice and is in section 14.

| Id | Names | Kinds | Where | Resolution |
|---|---|---|---|---|
| C1 | `dataprism.reidentification` | metric, Hazelcast map, configuration group | core:Metric.java:23; hazelcast:PrivacyCluster.REIDENTIFICATION_MAP (the 0.7 name; the 0.8 map is `dataprism.reidentification.s1`, which no longer equals the metric or the group); FZ `dataprism.reidentification.*` | Accepted. Three independent namespaces share the string prefix; a name must be unique within its kind only. Pin each kind separately (metrics: `PrivacyMetricsTest`; keys: the frozen list; map names: no pin today, section 15). |
| C2 | `dataprism.oversight`, `dataprism.identity`, `dataprism.approval`, `dataprism.audit.retention`, `dataprism.approval.pending` | map or metric prefix against configuration group or leaf key | maps in 8.1; metrics in 5; `dataprism.audit.retention` is a leaf key (Period) and the parent of four metrics; `dataprism.approval.pending` is a map. The Hazelcast property that once sat under it was replaced by `dataprism.hazelcast.approval-sweep-interval` (OD-200-14, t233), so the key no longer lies under a map name | Accepted as C1. Resolved by t233: the sweep knob no longer shares a name with a map. |
| C3 | `CLUSTER_LOCK_TIMEOUT`, `dataprism.hazelcast.lock-timeout`, `dataprism.hazelcast.security.lock-timeout`, `dataprism.cluster.lock.timeout` | refusal code, key, withdrawn key, proposed metric | section 3.2, 6.1, 10, 5 | The shipped key governs. The `security.lock-timeout` rows are withdrawn and removed from the cluster design (t202 superseded it; D7; task 218). The code and the metric are different kinds with one meaning on purpose; the metric's `Metric` constant is also spelled `CLUSTER_LOCK_TIMEOUT`, which cannot clash with the string code. |
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
| D1 | Unknown status code: "not answered" or "an error" | `docs/tools.md:141` ("Treat any code you do not recognise as 'not answered'"), `SourceResult.java:14`, `t190:13` (the owner decision as first recorded, commit f0fb5663), `design/batch-and-bulk-follow.md:43`, `:499`; against `PLAN.md:130` (D-070-3 summary: "as an error", commit 523fd730, later the same day) | The recorded decision, the shipped Javadoc and the shipped page all say "not answered"; the PLAN.md summary is the outlier. The register cannot tell from the record which the owner meant, so it is OD-200-1. Governs today: "not answered". Decided 2026-10-20 (OD-200-1): "not answered"; `PLAN.md` corrected. |
| D2 | Link `max-length` 768 or 1024 | `sealed-links.md:226` (narrative still names 768 as the earlier decision), `:467` (OD-12 row, amended), `batch-and-bulk-follow.md:609`; `PLAN.md:89` | No remaining conflict: the configuration table (`sealed-links.md:396`) already says 1024, with the 768 history kept. OD-B12 (owner, 2026-10-19) governs: default 1024, maximum 4096, floor 616 for option (a'). Registered as 1024 in 8.2. The batch worst-case collection link is 576 bytes, 768 characters, which fits 1024 with a margin of 256 characters. |
| D3 | Label and identifier length caps differ between documents | see table below | OD-200-4. Governs today: nothing is built; the sealed layout (`sealed-links.md`) is the binding wire constraint. |
| D4 | Relation bytes `0x02` to `0x04` | `sealed-links.md:185` documents only `0x00` and `0x01`; `batch-and-bulk-follow.md:617` to `621` has the table of five | No conflict, an extension: the combined table in 8.2 governs once the successor task S1 updates `sealed-links.md`. `0x05` to `0xFF` are reserved and invalid. |
| D5 | Audit disposition rows that shape C no longer records | `sealed-links.md:484` to `:487` and `key-resolvers.md:239` (closing tables) and the failure table of `key-resolvers.md:87` (`FAILED:<REASON>`, `EMPTY`) against `sealed-links.md:251` to `:256` (OD-13 shape C) and `key-resolvers.md:105` | The body of each document is the decision; the closing-table rows, and the audit values in the key-resolvers failure table (`FAILED:<REASON>`, `EMPTY`, `INVALID_OUTPUT`), are stale and are withdrawn here (section 10). Governs: shape C, where a resolver failure appears only as the target's `KEY_UNRESOLVED` status. |
| D6 | `KeyNamespace` is missing from the key-resolvers closing table | `key-resolvers.md:49` (the SPI) against the closing table at `:222` | Registered in 7.3. Also absent from that table: the eleven `LINK_<REASON>` codes (expanded in 3.8; `LINK_DEPTH_EXCEEDED` is withdrawn by OD-200-8, section 10). |
| D7 | `dataprism.hazelcast.security.lock-timeout` against the shipped `dataprism.hazelcast.lock-timeout` | the cluster design's former rows (4.1 and the closing table) against `t202:349` and `PLAN.md:144` (D-070-17) | The shipped key governs (same default `PT2S`, same range). Resolved by task 218: the design rows and the closing-table row are removed and the name is in section 10. |
| D8 | Cluster `security.mode` allowed `isolation-only` only if OD-2 allowed it; OD-2 (i) does not | the cluster design's former 4.1 row against OD-2 | Resolved by OD-200-9 (b) and task 218: the key and its two codes are removed from the cluster design and are in section 10. Governs: sealed only, with no key to say so. |
| D9 | `CLUSTER_OPERATION_REFUSED` was registered and documented in one page but could not be seen by any caller | `t216:174` (Migration note: "Map the new code in `ToolAdmission`…") against `security:ToolAdmission.java:84` and `docs/tools.md` | Section 3.2. Resolved by t233: the code is surfaced on every path and listed in `docs/tools.md`. OD-200-2. |
| D10 | `UNSAFE_HAZELCAST_DISCOVERY` and `INVALID_CLUSTER_PORT` are shipped but not string literals | `PrivacyClusterRefusal.java:14`, `:17` | Registered in 3.5. A literal-only extraction misses them (E2). E1c finds four more that E1 misses because the code starts a message: `SPECIAL_CATEGORY_EXPOSED`, `AUDIT_CHECKPOINT_UNAVAILABLE`, `AUDIT_LISTENER_DROPPED`, `AUDIT_LISTENER_FAILED`. |
| D11 | The `FOLLOW` capability is cited, not defined | `batch-and-bulk-follow.md:1091` against all of `sealed-links.md` | OD-200-5. |
| D12 | Option-(b)-only names remained in the 0.8 tables although option (a') was adopted | `sealed-links.md:500` (`dataprism.links.max-chain-depth`, "option b only"), `:250` (`DEPTH_EXCEEDED`) | Resolved by OD-200-8 (a): both names are dropped from sections 3.8 and 6.4 and listed in section 10. The `sealed-links.md` rows are stale (successor S1). |
| D13 | Shipped documents do not list 68 of the 214 shipped codes | section 3, "Docs" column. `CLUSTER_OPERATION_REFUSED` is now listed in `docs/tools.md` (t233) | Successor task S3. Not a design disagreement; a freeze-checklist gap. |

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

**N1. Code format.** `[A-Z][A-Z0-9_]{0,63}` for every refusal, startup, violation, status and audit-reason code. Checked for all 82 proposed code names: none breaks it; the longest is `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` (37 characters).

**N2. Startup refusals.** Shape `<CLASS>_<NOUN>` with CLASS one of INVALID, MISSING, UNKNOWN, UNSUPPORTED, UNSAFE, DUPLICATE, EMPTY, BLANK, RESERVED, FORBIDDEN, or an area-prefixed condition (`AUDIT_JSON_DIRECTORY_UNUSABLE`, `CORRELATION_REQUIRED_WITHOUT_HEADER`, `REIDENTIFICATION_REQUIRES_CLUSTER`). Of 129 shipped startup codes, 34 `INVALID`, 22 `MISSING`, 7 `UNKNOWN`, 5 `UNSUPPORTED`, 3 `EMPTY`, 3 `BLANK`, 2 `DUPLICATE`, 2 `UNSAFE`, 2 `RESERVED`, 1 `FORBIDDEN` use a class prefix and 48 are area-prefixed.

| Entry | Rule it breaks | Note |
|---|---|---|
| `PAGING_KEY_REQUIRED` | N2: a missing setting is `MISSING_<NOUN>` | `MISSING_PAGING_KEY` would match `MISSING_CLUSTER_KEY`, `MISSING_HMAC_KEY_REFERENCE` (design/batch-and-bulk-follow.md:1121) |
| `LINK_KEY_WEAK`, `LINK_KEY_UNRESOLVED`, `LINK_KEY_SHARED` | N2 area-prefix form, but see N3 | three more spellings of the shipped `HMAC_KEY_WEAK`, `HMAC_KEY_UNRESOLVED`; OD-200-10 |
| `AUDIT_HMAC_KEY_TOO_SHORT`, `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY` | N3 | `TOO_SHORT` against shipped `WEAK`; `REUSES_PSEUDONYMISATION_KEY` against `LINK_KEY_SHARED`; OD-200-10 |
| `SOURCE_DESCRIPTION_LEAKS_NAME`, `PUBLIC_MEMBER_INTERFACE`, `UNBOUNDED_MEMBER_INTERFACE`, `UNSEALED_CLUSTER_STATE`, `CLUSTER_KEY_MISMATCH` | N2 (condition-named, not class-prefixed) | Accepted: each names the condition, as the shipped `CLUSTER_PORT_SHARED`, `OPERATOR_AUDIENCE_SHARED` do. `CLUSTER_KEY_MISMATCH` and `UNSEALED_CLUSTER_STATE` may be join-time refusals rather than start-up ones (cluster-security 4.2); that is for the 0.8 build. `UNSEALED_CLUSTER_STATE` is the refusal that enforces the non-rolling cutover (OD-215-1 (a)). |

**N3. Key failures.** The shipped pair is `<AREA>_KEY_WEAK` and `<AREA>_KEY_UNRESOLVED` (`HMAC_KEY_WEAK`, `HMAC_KEY_UNRESOLVED`, spring-boot-autoconfigure:PropertiesValidation.java:129). Proposed names that do not follow it are in the table above (OD-200-10). The cluster codes follow it since task 218 (OD-215-3 (a)): `CLUSTER_KEY_UNRESOLVED`, `CLUSTER_KEY_WEAK`, `CLUSTER_KEY_SHARED` and the three `NAME_KEY_*` codes, one family with two areas; `DUPLICATE_CLUSTER_KEY_ID` takes the N2 `DUPLICATE` class, and an absent key keeps `MISSING_CLUSTER_KEY`.

**N4. Configuration keys.** `dataprism.<group>.<kebab-case-name>`; a duration or size is a typed value (`Duration`, `DataSize`) with no unit in the name; a secret is only a reference (`key-id` plus exactly one of `environment-variable` or `provider-reference`), and a literal `value` property exists only to be refused (`LITERAL_SECRET_FORBIDDEN` for `dataprism.privacy.hmac-key.value`; proposed `cluster-key.value`, `cluster-key.next.value`, `cluster-key.previous.value` and `name-key.value`, refused with the shipped `LITERAL_SECRET_FORBIDDEN`). Every remaining `dataprism.hazelcast.security.*` duration is a Spring-bound `Duration` with no unit in the name, bounds refused at startup with its own code and a place in the frozen list (OD-200-14). Checked: all 58 proposed `dataprism.*` keys are kebab case. Breaks:

| Entry | Breach | Note |
|---|---|---|
| the Hazelcast config property for the approval sweep interval (withdrawn) | unit in the name; not a Spring property; under a map name; not pinned | Replaced by `dataprism.hazelcast.approval-sweep-interval` (OD-200-14, t233); section 10 |
| `dataprism.audit.hmac-key` (proposed) | no refusing `value` property, unlike `dataprism.privacy.hmac-key` and the cluster keys | A literal under it would be an unknown key. Accepted: unknown keys already refuse; no decision. |
| `dataprism.security` and `dataprism.security-policy` (shipped) | two groups differing only by a hyphen | Pre-existing; renaming now would be a breaking change to a pinned name. Accepted; the new `allowed-clients` and `case-authorisation` join `security-policy`. |
| `dataprism.transport.mode` values `HTTP`, `STDIO` | upper case where every other enumerated value is lower kebab (`hash-chained`, `tcp-ip`, `single-node`) | Pre-existing; the binding is by enum. Accepted; recorded so no new key copies it. |

**N5. Metrics.** `dataprism.<area>.<noun>[.<noun>]`, all lower case, no tag except the closed `source` tag of section 5 (its value is a configured source name or a `PrivacyNamespace` name). The proposed `dataprism.paging.pages`, `dataprism.batch.items` (label `outcome`) and `dataprism.bulk.links` (label `status`) assume labels that `PrivacyMetrics` cannot carry, and the cluster design makes each signal its own constant instead: a metrics API decision the 0.8 briefs must take (OD-200-15). Word separator inside a segment: four shipped names use `_` (`anchor_failed`, `delete_failed`), the rest use none (`failclosed`, `withheld`). The cluster proposals use `_` (`age_seconds`, `unsupported_format`, `salt_rejected`, `accepting_next`); the catalog proposals use `-` (`source-names.nonconforming`). Rule proposed: `_` inside a segment. Breaks: `dataprism.catalog.source-names.nonconforming`, and `dataprism.cluster.pause.age_seconds` carries a unit in the name, which OD-200-7 (a) allows on a gauge. After task 218 every `dataprism.cluster.*` signal is one `Metric` constant (OD-200-15 (b)), uses `_` inside a segment (OD-200-7 (a)), and is a counter or a gauge emitted through the default no-op `PrivacyMetrics.gauge` (OD-215-5 (a)). OD-200-7.

**N6. Tools and capabilities.** Tool: lower snake case, verb first, object after (`get_entity_context`). Capability: the tool name upper-cased (`GET_ENTITY_CONTEXT`); `EXPOSE_SOURCE_NAMES` is a non-tool capability. Proposed tools follow it except `follow` (no object) and `follow_bulk` (qualifier after the verb); both are named by owner-delegated decisions (sealed-links §5; batch OD-B17 (a)) and are not reopened here.

**N7. Fields.** Tool arguments and result fields are camelCase (`entityType`, `agreementGroups`). Proposed server-added meta fields are underscore-prefixed (`_links`, `_linksOmitted`, `_truncated`, `_page`) and structural fields are not (`items`, `itemsTruncated`, `page`). Accepted; the prefix marks "added beside the source's own data", and the schema reference must say so. YAML files: `sources:` and `json-sources:` use kebab case for source keys and camelCase for field keys (`nonSensitive`, `undeclaredFields`); the descriptor file uses camelCase only. Pre-existing, accepted; `chainable` and `keyNamespace` (camelCase) follow the descriptor file.

**N8. Spelling.** British in identifiers. Breaks: the shipped `AuthorizationService`, `AuthorizationDecision`, `authorize`; the proposed `CaseAuthorizer`, `CaseAccessRequest` follow the American type names while the keys and codes are British (`case-authorisation`, `CASE_NOT_AUTHORISED`); `UNSAFE_HAZELCAST_SERIALIZATION` (the Hazelcast option is spelled `java-serialization-filter`, so platform-forced). OD-200-11.

**N9. Audit disposition keys.** A fixed key is colon-free or ends in `:<refused>` (section 4.3).

**N10. Decision ids.** Qualified by document (top of this register).

## 14. Open owner decisions

All fifteen were decided on 2026-10-20: OD-200-1, -2 and -14 explicitly by the owner, the rest delegated to the owner's recommendation. The text of each decision below is the record; the options and recommendations are kept as written for the reasoning. Before the decisions each had options and a recommendation. "Needed by" says what is blocked until it is chosen. Fifteen decisions; five (1, 2, 4, 5, 8) change what 0.8 builds and should be taken before the 0.8 briefs are cut.

### OD-200-1. How a client treats an unrecognised per-source status

- **Context.** D1 (section 12). Recorded as "not answered" in the original decision record (21:04 on 2026-10-09), `SourceResult.java:14`, `docs/tools.md:141`, `key-resolvers.md` and `batch-and-bulk-follow.md`; recorded as "an error" only in the later `PLAN.md` summary (21:25). Task 200's brief repeats "not answered".
- **Options.** (a) "not answered": a client reads it as a gap; the recorder corrects `PLAN.md:130`. (b) "an error": a client fails on it; `SourceResult` Javadoc, `docs/tools.md:141`, the two design documents and t190 change.
- **Recommendation.** (a). `status` is a `String` exactly so that a later code does not break an old client; an unknown status can only mean the source contributed no `entity` (only `ANSWERED` carries one), so reading it as a gap is the fail-closed reading. Under (b) every status added in 0.8 or 0.9 (`KEY_UNRESOLVED`) would break clients written before it, which defeats registering future codes.
- **Needed by.** before 0.8 adds a status
- **Decided (owner, explicit), 2026-10-20.** (a): "not answered" governs. A client reads an unrecognised status as a gap. The stale `PLAN.md` summary of D-070-3 ("as an error") is corrected to "not answered", citing OD-200-1.

### OD-200-2. Whether `CLUSTER_OPERATION_REFUSED` is visible to callers, and where its class lives

- **Context.** Section 3.2, D9. Today no caller can see it: tool calls get `OVERSIGHT_UNAVAILABLE`, reads get `SCOPE_READ_BUDGET`, re-identification gets `REIDENTIFICATION_UNAVAILABLE`; the throw sites build the exception without a cause and neither they nor `ToolAdmission` log anything, so a rejected lease (a configured `hazelcast.lock.max.lease.time.seconds` below the lease) makes every approval-gated call fail with a generic code and no log line. The exception sits in the hazelcast module, its sibling `ClusterLockTimeoutException` in core.
- **Options.** (a) Keep it internal: register it as an exception code only, chain the cause and add a WARN log with the exception class and no identifiers, and do not list it in `docs/tools.md`. (b) Surface it like `CLUSTER_LOCK_TIMEOUT`: catch it in `ToolAdmission` (`DENY:CLUSTER_OPERATION_REFUSED`), `ReidentificationService` and `OversightOperatorController`, list it in `docs/tools.md` and `docs/multiple-instances.md`, and move `ClusterOperationRefusedException` into `core.refusal` beside the lock exception. (c) As (b) for tools only.
- **Recommendation.** (b). The message names no key, scope or id, the sibling code is already client-visible, and an operator needs to tell a cluster fault from a paused or unavailable oversight state. The move is cheap now (the class is unreleased) and breaking after the freeze. Needs a code task no open task owns (S2).
- **Needed by.** before 0.8; at the latest before the 0.9 freeze
- **Decided (owner, explicit), 2026-10-20.** (b): `CLUSTER_OPERATION_REFUSED` is surfaced to callers in 0.7, like `CLUSTER_LOCK_TIMEOUT`. The cause is chained and logged, the message carries no data (no key, scope or id), and `ClusterOperationRefusedException` moves into `core.refusal`. A new 0.7 task implements it together with OD-200-14; task 201 waits for it.

### OD-200-3. Spelling of the new per-source status `KEY_UNRESOLVED`

- **Context.** C4. It is one edit away from the startup codes `HMAC_KEY_UNRESOLVED`, `LINK_KEY_UNRESOLVED` and `AUDIT_HMAC_KEY_UNRESOLVED`; `sealed-links.md` and `key-resolvers.md` ask this register to record them together or rename the status.
- **Options.** (a) Keep `KEY_UNRESOLVED` and rely on the kinds being different. (b) Rename it `RESOLVER_FAILED`, which says what happened, matches the past-tense statuses (`TIMED_OUT`, `FAILED`) and shares no stem with the startup codes.
- **Recommendation.** (b). A status is read next to `TIMED_OUT` and `CIRCUIT_OPEN`; a log search for `*_KEY_UNRESOLVED` would otherwise mix a runtime outcome with start-up failures. A status spelling is permanent at the freeze, a rename now is free.
- **Needed by.** before key-resolvers is built (0.8)
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (b) `RESOLVER_FAILED`.

### OD-200-4. One set of length caps for labels and identifiers

- **Context.** D3 table (section 12). The sealed-link layout says namespaces and resolver names at most 32 bytes and source labels at most 64, all "enforced at startup"; the catalog schemas allow 128-character source labels; the `KeyNamespace` and resolver-name grammars allow 64; the entity type is 32 in one sentence and 64 in the sizing beside it; the field path is 64 in the link and 128 in the schemas. Separately, catalog OD-C18 (c) makes a non-conforming source name (possibly over 64) only a warning in 0.8, so "enforced at startup" is not true in 0.8.
- **Options.** (a) The link layout governs: source labels 64 (schemas `maxLength` 64), namespace and resolver name 32 (grammar `{0,31}`), entity type 64 (fix the prose to 1 + 64, the sizing already uses 65), field path 64. A source name over 64 simply gets no links in 0.8 (counted as omitted), and `INVALID_SOURCE_NAME` refuses from 0.9. (b) Widen the layout to 128, 64, 64, 128: the worst-case direct link grows by about 160 bytes, to about 830 characters, and the collection-page link to about 985 characters against the 1024 default. (c) Keep each document's own cap and refuse at link issue only.
- **Recommendation.** (a). It is the cheapest and matches the grammar the owner already chose for source names (catalog OD-C18); (b) spends most of the margin that OD-B12 (owner) bought for a future header field; (c) leaves a schema that promises more than a link can carry.
- **Needed by.** before the 0.8 briefs for links and the catalog
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) the link layout governs.

### OD-200-5. The capability that authorises `follow`

- **Context.** D11, section 1.2. `batch-and-bulk-follow.md` names a `FOLLOW` capability "of task 191"; `sealed-links.md` defines none. Every tool needs a capability in `AuthorizationService` and a role must be granted it (`docs/tools.md:440`).
- **Options.** (a) A new capability `FOLLOW`, granted per role and off until granted, with an upgrade note as for `COMPARE_ENTITY_SOURCES` in 0.2.0; `FOLLOW_BULK` likewise. (b) No new capability: `follow` needs `GET_ENTITY_CONTEXT`. (c) The capability of the tool that issued the link, carried in the link.
- **Recommendation.** (a). Least privilege and the project's own rule that adding a tool grants nothing; the batch design already assumes it. It costs one frozen name and a documented upgrade step.
- **Needed by.** before the 0.8 brief for `follow`
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) new capabilities `FOLLOW` and `FOLLOW_BULK`.

### OD-200-6. The attribute name of `@Chainable`

- **Context.** C14. `@Chainable(namespace = "parcel-ref")` reuses `namespace`, which on `@SensitiveData`, `@SensitiveObject` and in the descriptor file means a `PrivacyNamespace` enum value; the descriptor key for the same fact is `keyNamespace`.
- **Options.** (a) `@Chainable(keyNamespace = "parcel-ref")`, matching the descriptor key. (b) Keep `namespace`.
- **Recommendation.** (a). One word per meaning across annotation and descriptor; a different type under the same attribute name invites a wrong value that compiles.
- **Needed by.** before the annotation is built (0.8)
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) `keyNamespace`.

### OD-200-7. Metric word separators and units

- **Context.** N5. Four shipped names use `_` inside a segment, others none; cluster proposals use `_`; the catalog proposal uses `-`; one cluster gauge carries `_seconds`; the metric-label question is OD-200-15.
- **Options.** (a) Rule: `_` inside a segment, a unit suffix allowed on a gauge; rename `dataprism.catalog.source-names.nonconforming` to `dataprism.catalog.source_names.nonconforming`. (b) `-` inside segments everywhere (renames four shipped metrics). (c) Leave mixed.
- **Recommendation.** (a). It matches the shipped retention metrics and the cluster table, changes only an unbuilt name, and is the form a Prometheus registry produces anyway.
- **Needed by.** before the catalog is built
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) `_` inside a segment.

### OD-200-8. Names that exist only for the rejected link option (b)

- **Context.** D12. The owner adopted option (a') for links. `dataprism.links.max-chain-depth` ("option b only") and the reason `LINK_DEPTH_EXCEEDED` are reachable only under (b).
- **Options.** (a) Drop both from the register and the 0.8 build; a depth or origin option, if ever wanted, is additive later. (b) Keep them as reserved names.
- **Recommendation.** (a). A pinned key or audit reason that nothing can set or emit is a permanent promise with no behaviour. `LINK_ORIGIN_DRIFT` stays: task 198 reuses it for collection pages.
- **Needed by.** before the links brief
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) drop both names.

### OD-200-9. Whether `dataprism.hazelcast.security.mode` exists

- **Context.** D8. With isolation-only not allowed (cluster-security OD-2 (i)) the key has one legal value, `sealed`, and carries two startup codes (`MISSING_CLUSTER_SECURITY_MODE`, `UNSUPPORTED_CLUSTER_SECURITY_MODE`).
- **Options.** (a) Keep it as an explicit, required opt-in. (b) Drop the key and both codes: a multi-member join is sealed by definition and `MISSING_CLUSTER_KEY` already refuses it without a key.
- **Recommendation.** (b), with the security reviewer told, because the approved design changes by one key. One key and two codes fewer to freeze, and no ceremony that can only be answered one way. If the owner wants the explicit switch for readability, (a) is harmless.
- **Needed by.** before the cluster briefs (215)
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (b) drop the key and both codes.

### OD-200-10. The vocabulary for key failures in new startup codes

- **Context.** N3. Shipped: `HMAC_KEY_WEAK`, `HMAC_KEY_UNRESOLVED`. Proposed: `LINK_KEY_WEAK`, `LINK_KEY_UNRESOLVED`, `LINK_KEY_SHARED`, but `AUDIT_HMAC_KEY_TOO_SHORT` and `AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY`.
- **Options.** (a) One family: `<AREA>_KEY_WEAK`, `<AREA>_KEY_UNRESOLVED`, `<AREA>_KEY_SHARED`; task 211 uses `AUDIT_HMAC_KEY_WEAK` and `AUDIT_HMAC_KEY_SHARED`. (b) Keep the briefed names.
- **Recommendation.** (a). Operators grep these; three spellings of one failure is avoidable, and 211 is not built.
- **Needed by.** before 211 starts
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) one family.

### OD-200-11. The `-izer` / `-ization` spelling of new security types

- **Context.** N8. Shipped `AuthorizationService`, `AuthorizationDecision`; proposed `CaseAuthorizer`, `CaseAccessRequest`; keys and codes are British.
- **Options.** (a) Accept the shipped spelling for the `Authoriz*` type family and name new types alike; record it as an exception to `docs/conventions.md`. (b) British for new types only (`CaseAuthoriser`), leaving a mixed family. (c) Rename the shipped types before the freeze.
- **Recommendation.** (a). The shipped names echo Spring Security's `AuthorizationDecision`; (c) is a pure rename of public API for spelling; (b) is the worst of both.
- **Needed by.** before 203 is built
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) accept the shipped spelling.

### OD-200-12. One word for the naming setting: audit key `names` or `labels`

- **Context.** C13. Catalog OD-C2 (a) (owner, 2026-10-09) fixed the audit key as `names`; the result field of `list_sources` and `describe_source` is `labels`.
- **Options.** (a) Keep both: they are different surfaces. (b) Rename the audit key to `labels`.
- **Recommendation.** (a). The owner chose the audit key explicitly; the two meanings are the same setting on different surfaces and the documents can say so. (b) is fine if the owner prefers one word.
- **Needed by.** before the audit grammar change for `names`
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) keep both words.

### OD-200-13. Shipped near-synonym codes on different surfaces

- **Context.** C19. `MISSING_PURPOSE` (MCP caller) and `PURPOSE_REQUIRED` (re-identification); `UNKNOWN_PURPOSE` and `PURPOSE_NOT_ALLOWED`; `APPROVAL_NOT_FOUND` and `REIDENTIFICATION_NOT_FOUND`.
- **Options.** (a) Accept and document which surface emits which. (b) Unify before the freeze (a breaking change for re-identification clients, in 0.8).
- **Recommendation.** (a). The surfaces are different audiences and the audit already distinguishes them by tool; unifying changes shipped client-visible text for no new information.
- **Needed by.** before the 0.9 freeze
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (a) accept and document.

### OD-200-14. Where the approval sweep interval lives

- **Context.** Sections 6.3, N4. `dataprism.approval.pending.sweep-seconds` is a Hazelcast config property (integer seconds), outside Spring binding, the frozen list and `ConfigurationMetadataDocumentedTest`, under a map name. It is unreleased (0.7.0*).
- **Options.** (a) Keep it, pin it in a new list and document it. (b) Replace it with a Spring property `dataprism.hazelcast.pending-sweep-interval` (`Duration`, default `PT5M`, bounds `PT1S` to `P1D`), pinned in the frozen list; no migration, nothing shipped. (c) Remove the knob; fix the interval.
- **Recommendation.** (b). It follows N4, is visible to the existing frozen-name and metadata tests, and costs nothing to rename while unreleased. Needs a code task (S2 or its own).
- **Needed by.** before the 0.7.0 release is cut
- **Decided (owner, explicit), 2026-10-20.** Replace `dataprism.approval.pending.sweep-seconds` with a Spring-bound `Duration` property, `dataprism.hazelcast.approval-sweep-interval`, default `PT5M`, validated at startup and pinned in the frozen property list. This differs from option (b) as written in the name (`approval-sweep-interval`, not `pending-sweep-interval`). The bounds are set by the implementing task and refused at startup with a new code. The same 0.7 task implements it.

### OD-200-15. How a metric carries a closed label

- **Context.** N5, section 5. `PrivacyMetrics` takes no label except the `source` tag. The proposals disagree: `dataprism.batch.items` is described with a closed label `outcome`, `dataprism.bulk.links` with `status`, `dataprism.paging.pages` with "closed labels" (design/batch-and-bulk-follow.md), while the cluster design makes every signal its own `Metric` constant because the API takes no tag (design/cluster-security.md, 4.4).
- **Options.** (a) Add a closed-label overload to `PrivacyMetrics`: `increment(Metric, Label)` where each metric declares an enum of allowed values, so free text stays unrepresentable. (b) One `Metric` constant per outcome, as the cluster design does: `dataprism.batch.items.done`, `.deferred`, `.refused` and a constant per bulk status. (c) Totals only, no breakdown.
- **Recommendation.** (b). It needs no API change, keeps `PrivacyMetricsTest` as the single pin, and the sets are small (three batch outcomes, seven statuses, one paging counter). (a) is justified only if the sets are expected to grow. Separate question, not decided here: the existing `source` tag carries a real source name (section 5); aliasing it is a distinct change.
- **Needed by.** before the paging metric is built (0.8)
- **Decided (owner delegated), 2026-10-20.** Following the recommendation: (b) one `Metric` constant per outcome.

## 15. Freeze checklist for 0.9.0

For each kind: what must exist, what must be documented, what must pin it with a test, where it stands now (from sections 1 to 8) and the gap. "Pinned" means a failing test when a name is added, removed or renamed without editing the pinned list.

| Kind | Must exist | Must be documented | Must be pinned by | Today | Gap |
|---|---|---|---|---|---|
| MCP tools and their input and output schemas | every tool in section 1.1 registered or removed from the register; each schema frozen | `docs/tools.md`: arguments, result fields, errors, one worked example per tool | a golden `tools/list` JSON (names, `inputSchema`, `outputSchema` if any) compared byte for byte; the `follow` schema's `maxLength` shown as the configured value | 2 tools. `CompareEntitySourcesToolTest` reads the input schema; no golden file; no result-schema test | golden schema test; tool reference for the 0.8 and 0.9 tools |
| Capabilities | `Capability.KNOWN` equals the tools' needs; `FOLLOW` decided (OD-200-5) | `docs/tools.md` "Grant before call" and `docs/configuration.md` role table | `CapabilityKnownSetTest` | pinned; `DESCRIBE_ENTITY_MODEL` exists with no tool | extend the pinned set with each new capability |
| Reserved argument names | `ReservedArguments.NAMES` | `docs/tools.md` (arguments) | `ReservedArgumentsTest` | pinned | none |
| Per-source status codes | section 2 table; OD-200-1 and -3 decided | `docs/tools.md` status table, with the unknown-status rule | `SourceStatusCodesTest` against `source-status-codes.txt` | pinned (7) | add the 0.8 codes in the same commit as the code |
| Call, admission, re-identification and operator codes | section 3.1 to 3.4 | every code in `docs/tools.md` or `docs/reidentification.md`; "Docs: no" in section 3 is the gap list | a frozen resource listing every code, and a test that extracts string literals, message-prefix literals (`"CODE: …"`) and `Code` enum constants from `src/main`, and code-shaped tokens from `src/main/resources` (commands E1, E1c, E2, E1r), and compares them with the list; the same test checks each code against `RefusalCodes` format | format is tested (`RefusalCodesTest`); the set is not. Undocumented: 17 of 34 call, 0 of 10 admission, 0 of 12 re-identification, 1 of 10 operator | frozen list and extraction test; document the gaps (S3, S4) |
| Closed vocabularies (enums of any visibility whose constant names reach output) | section 7.4: every enum classified as reaching output or internal, each constant listed | `docs/` pages for the descriptor, profile and annotation values (`docs/configuration.md`, `docs/tools.md`) | an enum-constant scan (commands E11 and E11b) over enums of any visibility in `src/main` whose `name()` reaches output, compared with a frozen list that records, per enum, its constants and whether it leaves the process; a new enum or constant fails the test until classified | not pinned except `Metric` (`PrivacyMetricsTest`), `SourceOutcome.Status` (`SourceStatusCodesTest`), `Capability` and the profile enum shown in the pinned property list. 7.4 has 25 rows, and several exits are "not traced" | frozen list and scan (S4); trace the untraced exits |
| Startup refusal codes | section 3.5 | `docs/configuration.md` or `docs/multiple-instances.md`, each with its cause | the same frozen resource and test, a second list | set not pinned. Undocumented: 40 of 131 | as above |
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
| Decisions and register | OD-200-1 to -15 chosen; the stale rows in section 12 corrected (S1); every `proposed` name built or removed | this register updated in the same change as each addition | `docs/plan/design/contract-register.md` re-run of the extraction commands (E1 to E10, with E1c, E1r and E8b) shows no unexplained difference | this task | re-run at each 0.8 task, and at 0.9.0-rc |

The 0.8 tasks each add their names to the pinned lists in the same commit; the freeze checklist is satisfied when every row above has a test and every "Docs: no" row in sections 3 and 6 is "yes" or removed.

## 16. Successor tasks this register names

Not allocated or numbered here (the orchestrator allocates ids). Each is outside this task's `Owns`.

| Id | Task | Owns (suggested) | Depends on |
|---|---|---|---|
| S1 | Correct the design documents' stale rows: `sealed-links.md` closing table (D5) and relation table (D4), `key-resolvers.md` closing table and failure table (D5, D6), the stale option-(b) rows (D12), the label caps after OD-200-4 (D3) | docs/plan/design/*.md except the cluster rows, which task 218 corrected (D7, D8) | owner decisions 4, 8, 9 |
| S2 | Surface or log `CLUSTER_OPERATION_REFUSED` and move its class (OD-200-2); replace the sweep property (OD-200-14) | hazelcast, security, reidentification, server, autoconfigure | owner decisions 2, 14 |
| S3 | Document the shipped codes the shipped pages lack ("Docs: no" in section 3) (`CLUSTER_OPERATION_REFUSED` is already in `docs/tools.md`, t233) | docs/tools.md, docs/configuration.md, docs/multiple-instances.md | OD-200-2 |
| S4 | Add the freeze tests of section 15 that do not need a decision: code extraction and frozen lists, golden `tools/list`, map names, operator endpoints, SPI signature snapshot | test sources, test resources | none |
| S5 | Correct the D-070-3 line in `PLAN.md` once OD-200-1 is chosen | docs/plan/PLAN.md | owner decision 1 |
| S6 | Design the 0.9 contract that has no name yet (section 6.5): per-source role authorisation (D-RM-11), audit-entry size handling, and the `compare_entity_sources` review | docs/plan/design/ (new document) | none; before the 0.9 briefs |

