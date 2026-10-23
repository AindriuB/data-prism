# Paging, batch and status-only bulk follow: `list_sources` pages, `get_entity_contexts`, collection paging, truncation markers and `follow_bulk`

Status: design. Part A (the `list_sources` page) is for 0.8, as decided in catalog OD-C16. Everything else (batch, collection paging, truncation markers, bulk follow) is for 0.9, and the surface freezes at the 0.9.0 release. Internal (`docs/plan/design/`, D-070-12 (a)); not published.
Task 198. Companions: `catalog.md` (task 197), `sealed-links.md` and `key-resolvers.md` (task 191), whose names and mechanisms this document reuses and does not redefine. Nothing here is code, an SPI stub or a configuration property; every name and every number is a proposal until the 0.8 or 0.9 task that builds it.

Change note, 2026-10-21 (task 238, successor S1 of the contract register): this document is brought into line with decisions already recorded in `contract-register.md`: OD-200-3 (the status first drafted as `KEY_UNRESOLVED` is `RESOLVER_FAILED`), OD-200-4 (source labels at most 64 from 0.9, with `INVALID_SOURCE_NAME`: the source-label schemas say `maxLength` 128 in 0.8 because a source name over 64 still starts then (OD-C18 (c)), while the caps enforced in 0.8 are the key namespace at 32 (grammar `[a-z][a-z0-9-]{0,31}`), the entity type at 64 and the field path at 64) and OD-200-5 (the `FOLLOW` capability is defined in `sealed-links.md`). The worst-case length figures of Part A and 3.2 are recomputed with those caps. No decision is changed here. Each edit cites its id on the line it changes.

Every product choice in this text was an open decision (OD-B1 to OD-B24, section 9) with options and a recommendation. On 2026-10-19 the owner decided all of them; section 9 records each decision. Three were decided explicitly by the owner (OD-B12 in part, OD-B18, OD-B24 option (b)); the rest were delegated to the recommendations. Passages marked "(proposal, OD-Bn)" now follow the decision recorded for that OD. The text remains a design, not code, and the surface still freezes at 0.9.0. What was already decided (D-RM-7, D-RM-12, D-RM-15, OD-C16 and the earlier design decisions listed below) is cited and not reopened.

All examples use an invented neutral domain: a logistics estate with a parcel service, a depot service and a courier-roster service (the domain of the three companion documents). No real system, organisation or data model is meant.

## Reading order and numbering

The seven numbered items of the task brief are sections 1 to 7. The 0.8 `list_sources` page is specified first, as the brief requires, in Part A, which comes before section 1 and is the first thing to read. Section 3 ("Paging") points to Part A for `list_sources` and then specifies the 0.9 collection paging.

| Where | What | Release |
|---|---|---|
| Part A | Paged `list_sources` (the first page contract) | 0.8 |
| 1 | `get_entity_contexts`: input, output, per-item status, batch size | 0.9 |
| 2 | Budget and limits per item, partial batches | 0.9 |
| 3 | Paging: 3.1 `list_sources` (Part A, 0.8); 3.2 collection paging | 0.8; 0.9 |
| 4 | Truncation markers and the hard overall cap | 0.9 |
| 5 | `follow_bulk`: status-only bulk follow | 0.9 |
| 6 | Audit and an estimate for 5,000 items | 0.8 (Part A); 0.9 |
| 7 | Boundaries | both |
| 8 | Test obligations for 0.8 and 0.9 (part of brief item 7) | both |
| 9 | Open decisions for the owner (part of brief item 7) | |
| 10 | Proposed names (closing table) | |

Decisions this design inherits and does not reopen:

- D-RM-7 (c): batch size configurable per scope, default 16, with a configured ceiling. The owner expects thousands of items per investigation.
- D-RM-12 (a): per-source truncation at record boundaries with markers and next-page links; a hard overall cap as backstop.
- D-RM-15 (b): paging plus status-only bulk follow with drill-in links for the exceptions.
- OD-C16 (a), `catalog.md`: `list_sources` is paged in 0.8, and this task specifies that page first. OD-C4 (hidden sources stay hidden), OD-C11 (real names of listed sources in `sourceSystems`), OD-C2 (the audit key `names`) and the label rules of `catalog.md` section 3 hold for every page.
- D-RM-10, D-RM-11, D-RM-4, D-RM-5, D-RM-14, and OD-1 (a'), OD-2, OD-10, OD-13 shape C, OD-14 of `sealed-links.md`: sealed links bound to scope and caller, real chained key sealed under a dedicated key, one link per collection element, audit values of the shape `REFUSED:<code>`.
- D-070-1 to D-070-5 and D-070-13: the per-source result `{status, entity}`, `REFUSED` omission, the status codes, the limit configuration keys, the record definition, `REFUSED:<code>` audit values.

Facts about the current code that this design rests on (checked 2026-10-19 on `main` at `f960f2a9`, after task 193 merged):

- The read budget is keyed by scope and subject: `budget.tryRead(scopeId, subjectId, scopeReadBudget)` at `DefaultContextOrchestrator.java:178`, once per request. The 0.8 roadmap changes the charge to once per source called plus a per-scope total cap.
- `TOO_MANY_RECORDS` and `RESPONSE_TOO_LARGE` refuse the whole call after the response is assembled (`DefaultContextOrchestrator.java` after the final check). `docs/tools.md` lists both. Truncation is not built; D-070-5 defers it to 0.9.
- `max-sources` marks every source past it `SKIPPED_OVER_LIMIT`; the 0.8 fan-out rewrite removes that silent cut-off (roadmap; `catalog.md` section 6).
- The audit record has exactly one `subjectPseudonym`, one `correlationId`, a `sourceSystems` list and a `fieldDispositions` map. The disposition value grammar is closed (`docs/audit.md`).
- `docs/tools.md` tells clients to treat an unknown status code as "not answered". PLAN.md D-070-3 words the same rule as "an error". This document follows `docs/tools.md` and notes the difference for task 200.

---

## Part A (0.8): paged `list_sources`

### A.1 What is fixed and what is open

`list_sources` returns the sources the caller may use, in ascending byte order of label, with the two exact-match filters (`catalog.md` section 3). At a hundred sources that is about 35 KB and at five hundred about 200 KB. Paging makes the response size independent of the estate. What follows adds paging and changes nothing else in the catalog: the filters, the label rule (alias or real name, decided once per request), the hidden-source rule, the ordering, the empty-result rule and the authorisation predicate are those of `catalog.md`.

The page is stateless. The server keeps no cursor, no list snapshot and nothing in Hazelcast. The `next` value is a sealed link made with the mechanism of `sealed-links.md` (AES-256-GCM, a key derived per scope, the caller bound, an expiry), with its own relation code so that a page link can never be used as a `follow` link and a `follow` link can never be used as a page.

### A.2 Input schema

The schema replaces the one in `catalog.md` section 3 (the two filters are unchanged; the two paging arguments are additive and optional, so a 0.8 client that sends neither gets the first page). Like every input schema in the catalog it does not close the object (a reserved argument is ignored and audited, never read for its value) and it carries no `maximum` on `pageSize`: the server enforces the ceiling itself, so that an over-large request gets the documented behaviour (OD-B6) and not a generic invalid-argument error.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "list_sources input (0.8 with paging)",
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
      "pattern": "^[a-z][a-z0-9-]{0,31}$"
    },
    "pageSize": {
      "description": "The most entries to return. Above the configured ceiling the ceiling applies and the response says so (OD-B6). Absent: the configured default, or the size recorded in the page link.",
      "type": "integer",
      "minimum": 1
    },
    "page": {
      "description": "The next value of the previous page of this same listing. Absent for the first page.",
      "type": "string",
      "minLength": 1,
      "maxLength": 1024,
      "pattern": "^[A-Za-z0-9_-]+$"
    }
  }
}
```

Rules for a call that carries `page`:

1. `entityType`, `keyNamespace` and `pageSize` may be absent, or present and equal to the values the link was issued for. Present and different is refused as an invalid link (the filters are in the sealed payload, section A.4). A client that wants a different listing starts again without `page`.
2. The maximum length follows `dataprism.links.max-length`; the served schema is generated from the configured value, as for `follow`. A client that ignores the schema gets the same refusal.

### A.3 Output schema

The schema replaces the one in `catalog.md` section 3 by adding one required property, `page`. `entry` and `keyNamespace` are unchanged. A response always carries `page`, also for an estate that fits on one page, so a client never has to guess whether a list is complete.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "list_sources output (0.8 with paging)",
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
    },
    "page": { "$ref": "#/$defs/page" }
  },
  "required": ["correlationId", "labels", "sources", "page"],
  "additionalProperties": false,
  "$defs": {
    "keyNamespace": { "type": "string", "pattern": "^[a-z][a-z0-9-]{0,31}$" },
    "entry": {
      "type": "object",
      "properties": {
        "source": { "type": "string", "minLength": 1, "maxLength": 128, "$comment": "64 from 0.9, with INVALID_SOURCE_NAME; OD-200-4, OD-C18 (c)" },
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
    },
    "page": {
      "type": "object",
      "properties": {
        "size": {
          "description": "The page size that applied (after the ceiling). Not the number of entries returned.",
          "type": "integer",
          "minimum": 1
        },
        "last": {
          "description": "True when no entry follows this page. The end-of-collection marker.",
          "type": "boolean"
        },
        "next": {
          "description": "Sealed link to the next page. Present exactly when last is false.",
          "type": "string",
          "minLength": 1,
          "maxLength": 1024,
          "pattern": "^[A-Za-z0-9_-]+$"
        },
        "expiresAt": {
          "description": "When next stops being accepted. Present exactly when next is present.",
          "type": "string",
          "format": "date-time"
        }
      },
      "required": ["size", "last"],
      "additionalProperties": false,
      "if": { "properties": { "last": { "const": false } } },
      "then": { "required": ["next", "expiresAt"] },
      "else": { "not": { "anyOf": [ { "required": ["next"] }, { "required": ["expiresAt"] } ] } }
    }
  }
}
```

Rules:

1. Ordering is the ascending byte order of the label, as in `catalog.md` rule 2. A page is a slice of that one total order. Page *k+1* begins at the first visible, filtered entry whose label is greater than the last label of page *k* (keyset, not offset), so a walk that sees an unchanged catalog returns every entry exactly once.
2. `page.last` is true when no visible, filtered entry follows the last returned one. The server looks one entry past the page to decide it, so an estate that is exactly a multiple of the page size ends with `last: true` on the final full page and never with an empty extra page.
3. An empty listing (a filter that matches nothing, or a caller who may use nothing) returns `sources: []` and `page: {size, last: true}`. It is not a refusal, as before.
4. A page contains only entries the caller may see. The page boundary, `last` and the page size reveal nothing about sources the caller cannot see: the slice is taken from the already-filtered, already-permitted list (`catalog.md` rule 3 still holds: no count of hidden sources, no "N more").
5. No total is returned (OD-B5).
6. `labels` is the effective setting for this call and is the same on every page of one walk (A.6 refuses a walk whose setting changed).

Examples, with aliases in effect, a page size of 2 and a three-source estate (the same three sources as `catalog.md`):

<!-- example-of: list_sources output (0.8 with paging) -->
```json
{
  "correlationId": "c-91b07e",
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
      "description": "Holds DEPOT, PARCEL records; accepts depot-code, parcel-ref keys.",
      "entityTypes": ["DEPOT", "PARCEL"],
      "inputKeys": ["depot-code", "parcel-ref"]
    }
  ],
  "page": {
    "size": 2,
    "last": false,
    "next": "AQpkZW1vLWxpc3QtMQAAAABn9m0QAmxzAAAA2k3q1x5mFpC0aVn3sH8eXc2pQwTt5uKy0Lr6Zb9N4oGv",
    "expiresAt": "2026-10-19T14:45:00Z"
  }
}
```

And the last page, from the call `list_sources` with `{"page": "<the value above>"}`:

<!-- example-of: list_sources output (0.8 with paging) -->
```json
{
  "correlationId": "c-91b07f",
  "labels": "ALIAS",
  "sources": [
    {
      "source": "ORG-J3T7LP5C",
      "description": "Planned delivery runs, with the stops of each run.",
      "entityTypes": ["PARCEL"],
      "inputKeys": ["route-code"],
      "freshness": "DAILY"
    }
  ],
  "page": { "size": 2, "last": true }
}
```

The `next` strings in this document are shaped like links and are not real ones.

### A.4 The page link: contents and binding

A page link is a sealed link in the envelope of `sealed-links.md` section 3: base64url, AES-256-GCM, 96-bit nonce, 128-bit tag, the key derived per scope from the link key (labels `dataprism/link/v1/seal` and `/caller`; no new derivation label is needed), the key id in clear and the version byte first. It differs from a `follow` link in these fields only (proposal, to be reconciled with task 191's table in task 200):

| Field | Value for a `list_sources` page |
|---|---|
| relation (cleartext header byte, in the AAD) | `0x02`, "catalog page". `list_sources` accepts only this relation and every other tool refuses it. The table of all relation bytes and the one tool or argument that accepts each is in 3.2 ("Relation bytes") |
| target (cleartext header, in the AAD) | empty (length 0). A page link names no source |
| scope, caller | in the AAD, from the session, never from the token (as for every link). `dataprism.links.bind-caller` applies |
| expiry | in the header and the AAD; `min(now + lifetime, scope expiry, token expiry)` (A.8) |
| sealed payload | link id (8 bytes); issue time (8); depth `0`; `entityType` filter (1 + up to 64, empty when absent); `keyNamespace` filter (1 + up to 32, empty when absent; OD-200-4); effective label kind (1 byte: alias or real); page size (2); the last label returned (1 + up to 64; OD-200-4); the catalog tag (16, below) |

The catalog tag is `HMAC(fingerprintKey, label_1 | ... | label_n)` truncated to 16 bytes, over the labels of the entire visible, filtered list (not only the page), in byte order, length-prefixed. `fingerprintKey` is the per-scope key of `sealed-links.md` section 3. Every page recomputes it from the same list it is slicing, which costs one pass over an in-memory list. Its purpose is to make a catalog that changed between two pages detectable (A.6) without storing anything.

A page link never holds a real source name unless the effective setting is real names, and then only inside the ciphertext, as the last label. With real names in effect, a page that has entries after it always ends at an entry whose label fits the page link (64 bytes or fewer; possible to exceed in 0.8 under OD-C18 (c)). It ends at the last entry within the page size whose label fits; if no entry within the page size fits, the page extends to the next entry whose label fits, or to the end of the list (`last: true`). The page size is a target in this case only; the extension is bounded by the number of over-long names, which the OD-C18 (c) startup warning already reports. From 0.9 `INVALID_SOURCE_NAME` refuses such names and the rule no longer applies. Example 1: page size 2, real names `a-depot`, a 70-character `b…`, `c-depot`. The window is `a-depot`, `b…`; the last entry in it that fits is `a-depot`, so page 1 is [`a-depot`] with `last: false` and a link carrying `a-depot`. Page 2 begins at `b…`, its window is `b…`, `c-depot`, no entry follows, so it is [`b…`, `c-depot`] with `last: true` and no link. Example 2: page size 3, real names `a-depot`, a 70-character `b…`, a 70-character `c…`, `d-depot`. Page 1's window is `a-depot`, `b…`, `c…` with `d-depot` after it; the last entry in the window that fits is `a-depot`, so page 1 is [`a-depot`], `last: false`, link carrying `a-depot`. Page 2 begins at `b…`; its window is `b…`, `c…`, `d-depot` and no entry follows, so it is [`b…`, `c…`, `d-depot`] with `last: true`. If another entry followed `d-depot`, page 2 would still end at `d-depot`, which fits. If the window held only over-long names with entries after it, the page would extend to the next entry that fits. Each page is a contiguous slice of the one total order (rule 1), and each ends at `last: true` or at a label the link can carry, so the schema's requirement that a page with `last: false` carries `next` and `expiresAt` holds. Rule 2 of the ordering list still holds: `last` is true exactly when no visible, filtered entry follows the last one returned, and the extension to the end of the list gives `last: true` with no entry left over. Worst case size: header 56 bytes (1 + 17 + 8 + 1 + 1 + 12 + 16, no target) plus payload 8 + 8 + 1 + 65 + 33 + 1 + 2 + 65 + 16 = 199 (entity type 1 + 64, key namespace 1 + 32, last label 1 + 64; OD-200-4), so 255 bytes and 340 characters, inside the default `max-length` of 1024 (OD-B12, decided 2026-10-19) by a margin of 684 characters. The arithmetic is to be rechecked against whatever task 191's build fixes for the header.

Why a sealed link and not a bare `after=<label>` value. This is OD-B2. A bare label would be simpler and would leak nothing, because a listing is a filter over the caller's own visible set. The brief and D-RM-12 require sealed next links, and sealing gives three properties a bare label does not: the continuation is bound to the filters, label kind, scope and caller it was issued for (so a link pasted into another investigation or another caller's session is refused); it expires, which bounds how long a walk can be replayed; and it is one mechanism with the 0.9 collection links, which do need the sealer. The cost is that paging then needs a link key in 0.8 even where `dataprism.links.enabled` is `false`. What happens when there is no key, including a small `pageSize` that makes a small estate multi-page, is OD-B2.

### A.5 Verification and refusal

The verification order and the oracle discipline are those of `sealed-links.md` sections 2 and 4, unchanged: a length check first; then the structural parse; then the AEAD verification always performed (against the named key, or a dummy key if it is unknown); then expiry, relation, filters, label kind and catalog tag evaluated as booleans and combined, so no early exit separates the reasons.

The client sees the one uniform refusal for every invalid page link. Which code is used is OD-B3:

- Under the recommendation, the code is `LINK_INVALID` with the same fixed text ("The link is not valid."). A forged, expired, wrong-scope, wrong-caller, wrong-tool, filter-mismatch, label-kind-changed and catalog-changed page link are one answer, so a prober learns nothing and the client's remedy is the same for all of them: start the listing again without `page`.
- The failed-attempt limiter of `sealed-links.md` section 4 counts the pre-authentication failures (`MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY`, `AUTH_FAILED`) of page links in the same per-principal, per-scope counter as `follow`. After the limit, `list_sources` calls that carry `page` get `LINK_ATTEMPTS_EXHAUSTED`, as `follow` does. A `list_sources` call with no `page` is never affected, so an honest agent can always start again. (Locking `follow` because someone forged page links is the intended cross-effect: the counter is about the principal's forged tokens, not about the tool.)

Audit reasons (shape C, disposition key `link`, value `REFUSED:LINK_<REASON>`, audit-only, as in `sealed-links.md` section 4). The reasons already in that document are reused (`MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY`, `AUTH_FAILED`, `EXPIRED`, `ISSUED_IN_FUTURE`). This document adds four, all classified after authentication succeeds and so never counted by the limiter and reachable only with a genuine page link:

| Audit-only reason | Meaning |
|---|---|
| `LINK_WRONG_TOOL` | the relation byte is not the one this tool accepts (a `follow` link passed to `list_sources`, or the reverse) |
| `LINK_FILTER_MISMATCH` | the call carried a filter or `pageSize` that differs from the link's |
| `LINK_LABELS_CHANGED` | the effective label kind (alias or real names) differs from the one the link was issued under |
| `LINK_CATALOG_CHANGED` | the catalog tag no longer matches (A.6) |

### A.6 Expiry mid-walk and a changing catalog

Expiry. Each page link is issued with its own expiry, `min(now + lifetime, scope expiry, caller token expiry)`. A walk is therefore as long as the agent keeps asking within one lifetime of the previous page. If a link expires between two pages the next call is refused with the uniform refusal and the audit reason `LINK_EXPIRED` (the `EXPIRED` reason of task 191); the client starts again from the first page. Skew is the `dataprism.links.clock-skew` of task 191. Nothing is partially returned.

What can change a catalog during a walk. The catalog is immutable per process (`catalog.md` boundary 6), so within one instance it changes only on a restart. It can differ between instances during a rolling upgrade (a source added or removed on some members first). The visible set can change because the caller's permissions changed (from 0.9, `SourceVisibility` follows the role-to-source patterns), or because the scope setting for real names changed, which changes every label.

Behaviour (the recommendation of OD-B4; the alternatives are in section 9): the catalog tag and the label kind in the link are compared with the recomputed ones; if either differs the call is refused with the uniform refusal and the audit reasons `LINK_CATALOG_CHANGED` or `LINK_LABELS_CHANGED`, and the client starts again. A walk therefore either returns every entry once, or is refused: it is never a silent mix of two catalogs. The cost is a restart of the walk after a deployment or a permission change; at a few hundred sources that is a handful of pages.

### A.7 Name visibility and filters

- The label kind is decided once per request by the effective setting (`catalog.md` section 3) and is recorded in the link. A page never mixes alias and real labels, and the description shown matches the label kind.
- With aliases in effect the sealed last label is an alias, not a real name. With real names in effect it is a real name, inside the ciphertext. In neither case does any cleartext header field carry a label.
- The filters are exact-match and are applied before the slice, not after it, so a page is full whenever enough matching sources remain.
- Hidden sources do not change the paging: the list that is sliced is the permitted, filtered list (OD-C4).

### A.8 Configuration (proposals; defaults are OD-B1)

All under a new group `dataprism.paging`. Unknown keys under it are startup errors, like every group. A zero, negative or non-integer value, or a violated relation below, refuses startup with `INVALID_PAGING_SETTING`; the message names the property, not the value (the style of `INVALID_REQUEST_LIMITS`).

| Key | Proposed default | Validation |
|---|---|---|
| `dataprism.paging.list-sources.page-size` | `25` | positive; at most `dataprism.paging.list-sources.max-page-size` |
| `dataprism.paging.list-sources.max-page-size` | `100` | positive. The ceiling for `pageSize` |
| `dataprism.paging.lifetime` | `PT15M` | positive duration. The effective expiry is also capped by scope and token expiry, and never exceeds `dataprism.links.lifetime` when links are on |

At 250 to 400 bytes an entry, 25 entries are 6 to 10 KB and 100 entries are 25 to 40 KB. The page links need the link key. A client can send any `pageSize`, so any estate with more entries than that size is multi-page whatever the estate size; the rule for a deployment with no link key (startup check, call-time behaviour) is therefore OD-B2, which sets out its options, and is not fixed here.

### A.9 Audit for a `list_sources` page

One event per call, as `catalog.md` section 3 says, so one event per page. Fields as `catalog.md`, with these notes:

| Field | Value |
|---|---|
| `tool` | `list_sources` |
| `sourceSystems` | the real names of the sources on this page only. A page is bounded by the page size, so this resolves the audit-size concern of OD-C11 for `list_sources` without any new rule |
| `policyDecision` | `ALLOW`, or `DENY:<code>`, for example `DENY:LINK_INVALID`, `DENY:LINK_ATTEMPTS_EXHAUSTED` |
| `fieldDispositions` | none on success. On an invalid page link: `link` = `REFUSED:LINK_<REASON>` |
| `names` | the effective setting (`REAL` or `ALIAS`), as OD-C2 decided, on every page |

Nothing is added to the audit record. The page token is never audited, logged, put in a metric label or put in an exception (boundary 7). The page number is not recorded: the sequence of pages of one walk is reconstructable from principal, scope, tool and time, and counted by the metric `dataprism.paging.pages` (closed labels only). The `pageSize` argument is not audited as a field of its own, like the `keyNamespace` filter in the catalog.

### A.10 What Part A leaves room for in 0.9

- The `page` object (`size`, `last`, `next`, `expiresAt`) is the shape the 0.9 collection pages use (3.2), so a client has one idea of "page" for both.
- `pageSize` and `page` are the whole of the paging surface of `list_sources`; `describe_source` is one source and is not paged.
- Adding a `total` later is additive (OD-B5). The relation byte `0x02` and `0x03` (3.2) are reserved here so task 191's table does not reuse them.
- The `list_sources` page uses no source adapter and no read budget. It is limited by the caller rate limit like any call.

---

## 1. `get_entity_contexts`: the batch tool (0.9)

### Purpose

An investigation names hundreds or thousands of items (parcels, depots, routes). Calling `get_entity_context` once per item costs a model round trip each. `get_entity_contexts` asks for up to a configured number of items in one call and returns one result per item, in the order asked. Nothing is merged across items (D-RM-1), and nothing is merged across sources within an item (D-070-1).

The tool is generic: it adds no knowledge about what the items are. It requires its own capability (OD-B7). It does not change `get_entity_context` or `compare_entity_sources`.

### Input schema

Scope, principal, purpose and case come from the session (rule 4). The reserved argument names are ignored and audited as today, at the envelope and inside each item (`rejectedArguments` of the envelope event). The schema does not close the object, and has no `maxItems`, for the reason in `catalog.md` section 6: a schema ceiling would turn an over-long batch into a generic invalid-argument error and hide `BATCH_TOO_LARGE`, the documented refusal. The server enforces the size itself.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "get_entity_contexts input",
  "type": "object",
  "properties": {
    "items": {
      "description": "The entities to ask about. At most the batch size of the scope; more is refused with BATCH_TOO_LARGE and nothing is read.",
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/$defs/item" }
    },
    "targetSources": {
      "description": "Source labels as returned by list_sources, applied to every item that does not carry its own targetSources.",
      "$ref": "#/$defs/targetSources"
    }
  },
  "required": ["items"],
  "$defs": {
    "targetSources": {
      "type": "array",
      "items": { "type": "string", "minLength": 1, "maxLength": 128, "$comment": "64 from 0.9, with INVALID_SOURCE_NAME; OD-200-4, OD-C18 (c)" },
      "minItems": 1,
      "uniqueItems": true
    },
    "item": {
      "type": "object",
      "properties": {
        "entityType": { "type": "string" },
        "subjectId": { "type": "string" },
        "targetSources": { "$ref": "#/$defs/targetSources" }
      },
      "required": ["entityType", "subjectId"]
    }
  }
}
```

An item is exactly the arguments of one `get_entity_context` call, and is handled exactly as that call is, including the D-RM-9 estate check (`SOURCE_SELECTION_REQUIRED`) and the label check (`SOURCE_NOT_AVAILABLE`, `catalog.md` section 6) per item. The same entity may appear twice; both are read and both are charged. A batch has no cross-item state.

### Output schema

The result for item *i* is at `results[i]`; the array has exactly one element per input item, in input order (the order is a function of the request alone, never of completion order, per task 179's rule). Each element reuses the per-source shape of task 190 for its `sources`, and adds three things: an `index` that repeats the position, an `outcome` for the item, and the refusal code when the item was refused.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "get_entity_contexts output",
  "type": "object",
  "properties": {
    "correlationId": { "type": "string" },
    "limits": {
      "description": "The batch size in force for this scope (OD-B9).",
      "type": "object",
      "properties": { "batchSize": { "type": "integer", "minimum": 1 } },
      "required": ["batchSize"],
      "additionalProperties": false
    },
    "summary": {
      "type": "object",
      "properties": {
        "done": { "type": "integer", "minimum": 0 },
        "refused": { "type": "integer", "minimum": 0 },
        "deferred": { "type": "integer", "minimum": 0 }
      },
      "required": ["done", "refused", "deferred"],
      "additionalProperties": false
    },
    "results": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/$defs/itemResult" }
    }
  },
  "required": ["correlationId", "limits", "summary", "results"],
  "additionalProperties": false,
  "$defs": {
    "code": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
    "link": {
      "type": "object",
      "properties": {
        "rel": { "enum": ["direct", "translated", "page"] },
        "target": { "type": "string" },
        "field": { "type": "string", "$comment": "the concrete indexed path (for example depots[2]), not the declared path capped by OD-200-4, so no maxLength" },
        "namespace": { "type": "string" },
        "toNamespace": { "type": "string" },
        "link": { "type": "string", "maxLength": 1024 },
        "expiresAt": { "type": "string", "format": "date-time" }
      },
      "required": ["rel", "target", "link", "expiresAt"]
    },
    "truncation": {
      "type": "object",
      "properties": {
        "path": { "type": "string", "minLength": 1, "maxLength": 64 },
        "kept": { "type": "integer", "minimum": 0 },
        "omitted": { "type": "integer", "minimum": 1 },
        "reason": { "enum": ["RECORD_CAP", "SIZE_CAP"] },
        "next": { "$ref": "#/$defs/link" }
      },
      "required": ["path", "kept", "omitted", "reason", "next"],
      "additionalProperties": false
    },
    "sourceResult": {
      "type": "object",
      "properties": {
        "status": { "$ref": "#/$defs/code" },
        "entity": { "type": "object" },
        "_links": { "type": "array", "items": { "$ref": "#/$defs/link" } },
        "_linksOmitted": { "type": "integer", "minimum": 1 },
        "_truncated": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/truncation" } }
      },
      "required": ["status"]
    },
    "itemResult": {
      "type": "object",
      "properties": {
        "index": { "type": "integer", "minimum": 0 },
        "outcome": { "enum": ["DONE", "REFUSED", "DEFERRED"] },
        "code": { "$ref": "#/$defs/code" },
        "deferredBy": { "enum": ["DEADLINE", "RESPONSE_CAP"] },
        "entityType": { "type": "string" },
        "subject": { "type": "string" },
        "sources": {
          "type": "object",
          "additionalProperties": { "$ref": "#/$defs/sourceResult" }
        },
        "findings": { "type": "array", "items": { "type": "object" } }
      },
      "required": ["index", "outcome"],
      "additionalProperties": false,
      "allOf": [
        {
          "if": { "properties": { "outcome": { "const": "DONE" } } },
          "then": { "required": ["entityType", "subject", "sources", "findings"], "not": { "anyOf": [ { "required": ["code"] }, { "required": ["deferredBy"] } ] } }
        },
        {
          "if": { "properties": { "outcome": { "const": "REFUSED" } } },
          "then": { "required": ["code"], "not": { "anyOf": [ { "required": ["sources"] }, { "required": ["deferredBy"] } ] } }
        },
        {
          "if": { "properties": { "outcome": { "const": "DEFERRED" } } },
          "then": { "required": ["deferredBy"], "not": { "anyOf": [ { "required": ["sources"] }, { "required": ["code"] } ] } }
        }
      ]
    }
  }
}
```

The per-item `outcome` is a separate word from the per-source `status`, on purpose: the same code space is not reused at two levels, so a client that reads `status` for a source can never mistake it for the item's. The meanings:

| `outcome` | Meaning | Present with |
|---|---|---|
| `DONE` | the item ran as a `get_entity_context` call and returned a response; the sources inside carry their own statuses (any of task 190's codes, including `REFUSED` for a refused source, which is omitted from `sources` as D-070-2 says) | `entityType`, `subject`, `sources`, `findings` |
| `REFUSED` | the item was refused as a whole, as a single call would have been, with that call's bare refusal code | `code` (for example `SCOPE_READ_BUDGET`, `SOURCE_SELECTION_REQUIRED`, `SOURCE_NOT_AVAILABLE`, `NO_SOURCE_DATA`, `VALIDATION_FAILED`, `TOO_MANY_RECORDS`, `RESPONSE_TOO_LARGE`). Nothing of the item's sources is returned |
| `DEFERRED` | the item was not returned, and the client may ask for it again in a later call | `deferredBy`: `DEADLINE` (not started before the batch deadline) or `RESPONSE_CAP` (read, but the batch response cap would have been exceeded; section 2) |

`DEFERRED` items always form a suffix of the array (the client re-sends from the first `DEFERRED` index). That is a property the tests check (section 8).

An unknown `outcome`, `code` or `deferredBy` is treated by a client as "not answered", as an unknown status code is today.

Per-item refusal isolation. An item refusal (D-070-2 applied to the item: the item is refused when every source that answered was refused, or by a size or record cap, or by the budget) never stops another item. Only a refusal that happens before any item runs refuses the whole call, and it is the bare code, the same as for the single tools: `TOOL_NOT_PERMITTED`, the admission codes (`TOOL_PAUSED`, `CALLER_RATE_LIMITED`, `APPROVAL_REQUIRED` and the others of `docs/tools.md`), `NO_AUTHENTICATED_CALLER`, and the new `BATCH_TOO_LARGE`.

### Batch size, ceiling and the refusal code (D-RM-7)

D-RM-7 (c) fixes the shape: configurable per scope, default 16, with a configured ceiling. The keys below are proposals; the ceiling's value is OD-B8.

| Key | Proposed default | Validation |
|---|---|---|
| `dataprism.batch.default-size` | `16` (D-RM-7) | positive; at most `dataprism.batch.ceiling` |
| `dataprism.batch.ceiling` | `64` (OD-B8) | positive. No scope can be configured above it |
| `dataprism.batch.scope-sizes` | empty | a list of `<scope pattern>=<size>` strings, matched against the scope id with the grammar of `dataprism.catalog.real-names-scopes` (OD-C3 (c): a single trailing `*` as the only wildcard). Each size is positive and at most the ceiling. The first matching pattern in list order wins |

Resolution is per request from the scope id, never from an argument. A zero, negative or non-integer size, a size above the ceiling or a malformed pattern refuses startup with `INVALID_BATCH_SETTING` (the message names the property, not the value). A batch of more items than the scope's size is refused as a whole with `BATCH_TOO_LARGE`, before any item is resolved, any source called or any budget charged. The bare code is the client's only signal, so the batch size is also advertised in each response as `limits.batchSize` (OD-B9). The audit decision is `DENY:BATCH_TOO_LARGE`.

An example, a batch of three with one refused and one deferred by the response cap. The first item shows a truncated source with its next link (section 4):

<!-- example-of: get_entity_contexts output -->
```json
{
  "correlationId": "c-5d20a1",
  "limits": { "batchSize": 16 },
  "summary": { "done": 1, "refused": 1, "deferred": 1 },
  "results": [
    {
      "index": 0,
      "outcome": "DONE",
      "entityType": "PARCEL",
      "subject": "SUBJ-4Q8M2T1X",
      "sources": {
        "ORG-6M1ZQ0AB": {
          "status": "ANSWERED",
          "entity": { "parcel": "PARCEL-7Q2K4M", "state": "in transit", "scans": [ { "depot": "DEPOT-3R", "at": "2026-10-18T08:12:00Z" }, { "depot": "DEPOT-9K", "at": "2026-10-18T13:40:00Z" } ] },
          "_truncated": [
            {
              "path": "scans",
              "kept": 2,
              "omitted": 3,
              "reason": "RECORD_CAP",
              "next": { "rel": "page", "target": "ORG-6M1ZQ0AB", "field": "scans", "link": "AQ-pa2VkLXBhZ2UtMQAAAAByV1Uk3mRzY2FuczZ5xYQ0lP8mC1uVx2tB9JhTe7dKq", "expiresAt": "2026-10-19T14:30:00Z" }
            }
          ]
        },
        "ORG-8RK2W9TE": { "status": "NO_DATA" }
      },
      "findings": []
    },
    { "index": 1, "outcome": "REFUSED", "code": "SCOPE_READ_BUDGET" },
    { "index": 2, "outcome": "DEFERRED", "deferredBy": "RESPONSE_CAP" }
  ]
}
```

---

## 2. Budget and limits per item, and a partial batch (0.9)

An item is a call. Every limit that applies to one `get_entity_context` call applies to each item on its own, and the batch adds only the limits that a set of items needs.

| Limit | Applies per item | Applies to the batch |
|---|---|---|
| `dataprism.limits.max-sources` | Yes. An item that asks for more sources than the limit, or that does not choose and has too many, is refused as in `catalog.md` section 6 (`SOURCE_SELECTION_TOO_LARGE`, `SOURCE_SELECTION_REQUIRED`) with outcome `REFUSED`. No silent `SKIPPED_OVER_LIMIT` (the 0.8 rewrite removed it) | No separate source-call cap. Batch size times `max-sources` bounds the calls; the budget and the concurrency limit are the real controls |
| Read budget | Reserved per item (below) | The per-scope total cap of 0.8 counts every item's reads |
| `dataprism.limits.max-records`, `max-response-size` | Yes, as task 193 built them, plus per-source truncation (section 4) | A batch response cap (below) |
| `dataprism.limits.max-concurrency` | The downstream calls of all items share the one limit; it is a limit on the request, and a batch is one request | |
| `dataprism.limits.per-source-timeout` | Per source, as in the 0.8 fan-out rewrite | An overall batch deadline (below) |

### Read budget across a batch

The budget unit after 0.8 is one read per source called, charged against the subject's counter and the per-scope total (roadmap, 0.8). In a batch:

1. Before any source is called, the server resolves each item's selection (the same resolution as the single tool) and then reserves the reads for the items in input order. The reservation for item *i* is the number of sources it will call, charged to the counter of its own subject and to the scope total, in one atomic step per item (the existing budget map, boundary 6).
2. An item whose reservation does not fit is `REFUSED` with `SCOPE_READ_BUDGET` and is not run. Later items are still tried: a later item on another subject can still fit its own counter, but once the scope total is exhausted every later item is refused. Because the reservation runs in input order and before dispatch, which items are refused is a function of the request and the budget state, not of timing.
3. An item that is `DEFERRED` by the deadline was never called, so its reservation is released. An item `DEFERRED` by the response cap was read; its reads stay charged (OD-B11 states why).
4. The per-source status for a source that the 0.8 fan-out could not read because the budget ran out part-way is whatever the 0.8 design defines. The batch inherits it and adds nothing. This is a dependency on the 0.8 fan-out and budget work, not a decision of this document.
5. Sizing. The scope total must be at least (items asked over the investigation) times (sources per item). For 5,000 items on 8 sources that is 40,000 reads; for a hundred-source estate with targeting of 8 it is the same. The 0.9 operations guide gives this formula; this document states it so the defaults of the 0.8 budget work are checked against it, not chosen from it.

### Record and size caps across a batch

- Each item's response is capped as a single call's is: `TOO_MANY_RECORDS` and `RESPONSE_TOO_LARGE` from task 193 refuse the item (outcome `REFUSED`, code as named) when the untruncatable part of it exceeds the cap; section 4 describes the per-source truncation that keeps most items under the cap.
- A batch response cap, `dataprism.batch.max-response-size` (proposed default equal to `dataprism.limits.max-response-size`, OD-B11), bounds the whole response body: the `results` array and the envelope (`correlationId`, `limits`, `summary`), measured with the same conservative compact-UTF-8 rule as task 193. A single call's cap is measured on that call's own body, but in a batch an item also carries its wrapper (`index`, `outcome`) and the envelope, so an item that fits `max-response-size` alone need not fit a batch cap that equals it. The server assembles `results` in input order. The first item whose result would carry the total over the cap, and every item after it, becomes `DEFERRED` with `deferredBy: "RESPONSE_CAP"`, with one exception that guarantees progress: if the first item to be returned does not fit in an otherwise empty response, it is `REFUSED` with `RESPONSE_TOO_LARGE` as a per-item refusal, not deferred. So every batch call returns at least one `DONE` or `REFUSED` item at its first returned position, a client never retries an item that can never fit, and an item is never deferred more than once for lack of room (a deferred item that is retried first in its call either fits or is refused). The reads of a `REFUSED` or `DEFERRED(RESPONSE_CAP)` item stay charged. Startup refuses, with `INVALID_BATCH_SETTING`, a batch cap smaller than `dataprism.limits.max-response-size`. The refusal rule above, with OD-B11's alternative of reserving envelope headroom, is OD-B11.

### Deadline and partial batches

- `dataprism.batch.deadline` (proposed default `PT30S`, OD-B11) is the overall time allowed to the whole batch from the start of the first fan-out. Items start in input order, with the downstream concurrency shared; an item not started when the deadline passes becomes `DEFERRED` with `deferredBy: "DEADLINE"`; an item in flight at the deadline completes as the 0.8 overall deadline defines (its unfinished sources are `TIMED_OUT`). Startup refuses, with `INVALID_BATCH_SETTING`, a deadline shorter than `dataprism.limits.per-source-timeout`, so the first item can always finish.
- A partial batch is reported by the per-item `outcome`, the `summary` counts and the suffix property of `DEFERRED`. A partial batch is a successful call (`ALLOW`), not a refusal. The client's remedy for deferred items is to send a new call that contains them. There is no continuation link for a batch: a deferred item is a new request, so its authorisation, admission and budget are decided afresh, which is simpler and safer than a link that would carry a batch's state.
- When every item is `REFUSED`, the call is still a successful call whose results are all refusals. It is not collapsed into a whole-call refusal, because the client needs each item's code. (This differs on purpose from D-070-2, which refuses a single call whose every answered source was refused: there is one code to return there, and many here.)

---

## 3. Paging

### 3.1 `list_sources` (0.8): see Part A

The `list_sources` page is specified first, in Part A above, for 0.8. Part A is the contract; nothing in 3.2 changes it.

### 3.2 Collection paging (0.9)

A collection here is an array inside a source's `entity` whose records exceed the per-source caps of section 4. The server cannot return all of it, and the agent must be able to continue. Paging a collection is not the same as paging the catalog: the continuation has to read the source again, and so it needs a real key and the read budget.

Mechanism (OD-B12 compares the options; this is the recommendation):

1. Truncation happens at record boundaries after scrubbing and validation, so what is returned is exactly the first *kept* records of the array in the source's own order. The order is the order the source returned; the server does not sort it, because the order is evidence (a timeline of scans is the point of an investigation that looks for where a record stopped).
2. The marker for the array carries a `next` link (`rel: "page"`, section 4). Passing it to `follow` makes the server re-read the same source with the same request, scrub and validate the whole response again, take the records from the `offset` recorded in the link on, and return the next slice.
3. Because the source may change between the first read and the page read, the link records a keyed fingerprint of the last kept record (the raw record, canonicalised, under the per-scope fingerprint key of `sealed-links.md` section 3, 12 bytes). On the re-read the server checks that the record at `offset - 1` has that fingerprint. If it does not (the collection changed ahead of the boundary), the follow is refused with the uniform refusal and the audit-only reason `LINK_ORIGIN_DRIFT` (the existing `ORIGIN_DRIFT` of task 191). The client starts again from the original call. A changed record behind the boundary does not matter; a change beyond the boundary appears in the later page. Records are never deduplicated, reordered or interpreted.
4. The link contains the real key it needs, sealed as `sealed-links.md` OD-1 (a') seals a chained key: for a source result from the root call, the root `subjectId`; for a source result reached by `follow`, the sealed chained key. No new class of real identifier enters a link, and the residual risks T10 and T17 are the accepted ones.

The collection page link is a sealed link (task 191 envelope) with relation byte `0x03`, "collection page", target the source label (as for a direct link), and a sealed payload that, beyond task 191's fixed fields, holds: the entity type (1 + 64; OD-200-4); the sealed key (2 + 64k, at most 258); the key namespace (1 + 32); the field path of the collection (1 + up to 64; OD-200-4); the offset (4); the fingerprint of the last kept record (12); and the number of records the page may keep (2).

Size. Header 120 bytes (the target label is present, up to 64 bytes). Payload, worst case: link id 8, issue time 8, depth 1, key namespace 33, entity type 65 (1 + 64, the cap of OD-200-4), sealed key 258 (the 256-byte bucket and its length prefix), path 65, offset 4, record fingerprint 12, page record count 2, which is 456 bytes (unchanged by the OD-200-4 recompute). Total 576 bytes, which is 768 characters in base64url, at most the 1024 default: exactly the 768 that task 191 decided as the default `dataprism.links.max-length` (OD-12 of `sealed-links.md`), a margin of zero. A margin of zero was a defect, since any later header field would make the worst-case link unissuable and the collection unpageable; it is fixed by the owner's decision of 2026-10-19 (OD-B12) to raise the default to 1024, a margin of 256 characters, and task 191's sizing carries this link kind in its arithmetic. A link that cannot be issued is not issued (`sealed-links.md` rule 6) and the consequence for a collection is fail closed (section 4).

### Relation bytes

The relation byte is in the cleartext header and the AAD. Each tool or argument accepts exactly the relations in this table and treats any other relation as an invalid link (the uniform refusal, audit-only reason `LINK_WRONG_TOOL`, which is classified after authentication and is not counted by the limiter).

| Byte | Relation | Issued in | Accepted by | Release |
|---|---|---|---|---|
| `0x00` | direct | `_links` | `follow`; `follow_bulk` `links` | 0.8 |
| `0x01` | translated | `_links` | `follow`; `follow_bulk` `links` | 0.8 |
| `0x02` | catalog page | `list_sources` `page.next` | `list_sources` `page` only | 0.8 |
| `0x03` | collection page | `_truncated[].next` | `follow` only | 0.9 |
| `0x04` | group | the group link of a collection page | `follow_bulk` `group` only | 0.9 |

`follow_bulk` `links` therefore does not take page or group links: a `0x02`, `0x03` or `0x04` entry in `links` is counted in `invalidLinks` (section 5) and is not read. `follow` does not take a group link, and `list_sources` does not take a `follow` link.

The `follow` result of a page link is the same envelope as any `follow` (one entry in `sources`). The `entity` of that entry contains only the collection path (its ancestor objects are present, sibling fields are not), so a page does not repeat the scalars of the first read. The entry carries `_page` always, and `_truncated` with a new `next` when more records remain. It carries `_links` and `_linksOmitted` for the records returned, built as for any result (a record returned by a page is a record like any other, and its chainable fields produce links under the same cap):

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "follow page result (0.9)",
  "type": "object",
  "properties": {
    "correlationId": { "type": "string" },
    "sources": {
      "type": "object",
      "minProperties": 1,
      "maxProperties": 1,
      "additionalProperties": { "$ref": "#/$defs/pageResult" }
    }
  },
  "required": ["correlationId", "sources"],
  "additionalProperties": false,
  "$defs": {
    "code": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
    "link": {
      "type": "object",
      "properties": {
        "rel": { "enum": ["direct", "translated", "page"] },
        "target": { "type": "string" },
        "field": { "type": "string", "$comment": "the concrete indexed path (for example depots[2]), not the declared path capped by OD-200-4, so no maxLength" },
        "link": { "type": "string", "maxLength": 1024 },
        "expiresAt": { "type": "string", "format": "date-time" }
      },
      "required": ["rel", "target", "link", "expiresAt"]
    },
    "pageResult": {
      "type": "object",
      "properties": {
        "status": { "$ref": "#/$defs/code" },
        "entity": { "type": "object" },
        "_page": {
          "type": "object",
          "properties": {
            "path": { "type": "string", "minLength": 1, "maxLength": 64 },
            "offset": { "type": "integer", "minimum": 0 },
            "kept": { "type": "integer", "minimum": 0 },
            "last": { "type": "boolean" }
          },
          "required": ["path", "offset", "kept", "last"],
          "additionalProperties": false
        },
        "_links": { "type": "array", "items": { "$ref": "#/$defs/link" } },
        "_linksOmitted": { "type": "integer", "minimum": 1 },
        "_truncated": {
          "type": "array",
          "minItems": 1,
          "maxItems": 1,
          "items": {
            "type": "object",
            "properties": {
              "path": { "type": "string", "maxLength": 64 },
              "kept": { "type": "integer", "minimum": 0 },
              "omitted": { "type": "integer", "minimum": 1 },
              "reason": { "enum": ["RECORD_CAP", "SIZE_CAP"] },
              "next": { "$ref": "#/$defs/link" }
            },
            "required": ["path", "kept", "omitted", "reason", "next"],
            "additionalProperties": false
          }
        }
      },
      "required": ["status", "_page"]
    }
  }
}
```

An example, the last page of the `scans` collection of the first item in section 1:

<!-- example-of: follow page result (0.9) -->
```json
{
  "correlationId": "c-5d20b7",
  "sources": {
    "ORG-6M1ZQ0AB": {
      "status": "ANSWERED",
      "entity": { "scans": [ { "depot": "DEPOT-2H", "at": "2026-10-18T17:05:00Z" }, { "depot": "DEPOT-5L", "at": "2026-10-18T21:30:00Z" }, { "depot": "DEPOT-5L", "at": "2026-10-19T06:48:00Z" } ] },
      "_page": { "path": "scans", "offset": 2, "kept": 3, "last": true }
    }
  }
}
```

`_page.last` is the end-of-collection marker. It is true exactly when no `_truncated` entry is present. `_page.offset` is the index of the first record returned (its position in the source's order, which is a count and not a value), and `kept` the number returned.

A page link costs one source read, charged to the read budget like any `follow` (D-RM-4). Reading a collection of *n* records in pages of *k* costs *n / k* reads, each of which fetches the whole response; this is a stated cost of the mechanism and the reason OD-B12 lists adapter-native paging as an alternative. An expired page link is `LINK_INVALID`; the client repeats the original call. All the rules of `sealed-links.md` section 4 (uniform refusal, limiter, audit) apply unchanged.

When `dataprism.links.enabled` is `false` there is no link key and no `follow` (OD-7 of `sealed-links.md`, default through 0.9). Then nothing is truncated: a source whose collection exceeds a cap is refused with `TOO_MANY_RECORDS` or `RESPONSE_TOO_LARGE`, at the granularity OD-B18 decides. The contract is fail closed: a truncation is only ever made together with a `next` link that continues it, so `next` is required in every marker, and a response never carries a silently partial collection. This also means that a default installation never truncates, so per-source truncation (D-RM-12) is off unless links are enabled; OD-B12 states this and the options.

---

## 4. Truncation markers and the overall hard cap (D-RM-12, 0.9)

### What is truncated

D-070-5 defines a record: a source's top-level record plus each object in an array. In 0.9 the server may remove whole records from an array of a source's `entity`, never part of a record, never a scalar field, and never an array that is not an array of objects (a list of strings is one field). It does so when a source exceeds a per-source cap, and only after that source has been scrubbed and validated, so what remains is exactly as safe as an untruncated source. Truncation happens only if a `next` link can be issued for every array it truncates (links enabled, key fits `max-length`). A source that is too large because of the part that cannot be truncated (scalars, or the top-level record), or whose arrays cannot be truncated because no link can be issued, is not truncated and not partly returned: it is refused. Whether that is a per-source refusal (the source marked `REFUSED` with the audit value `REFUSED:RESPONSE_TOO_LARGE` or `REFUSED:TOO_MANY_RECORDS`, D-070-2 style, the call refused only if every answered source is refused) or the whole-call refusal that task 193 built is OD-B18; the text in this document that shows a per-source refusal follows OD-B18's recommendation.

### Per-source caps (keys proposed, defaults OD-B13)

| Key | Meaning |
|---|---|
| `dataprism.limits.max-records-per-source` | the most records one source may contribute (its top-level record counts one) |
| `dataprism.limits.max-source-response-size` | the most bytes one source may contribute, by the task 193 rule |

Startup refuses, with `INVALID_REQUEST_LIMITS` (the existing code), a per-source cap larger than the corresponding whole-call cap (`max-records`, `max-response-size`), because a per-source cap above the overall cap could never be the one that applies. They are positive only, like every limit.

### The marker

`_truncated` sits in the source's result next to `entity`, `_links` and `_linksOmitted` (same level), and is present only when something was removed. It is an array with one entry per truncated array, in the ascending order of `path`. Each entry has the shape in the batch output schema above:

| Field | Meaning |
|---|---|
| `path` | where the array is, as the client sees the record (the rule for `field` in `sealed-links.md` section 5: an indexed path for nested arrays, `<undeclared-N>` for a name that must not be shown; an array whose name cannot be shown safely is not given a marker or a link, and its source is `REFUSED` instead: fail closed; likewise an indexed nested-array path over 64 bytes gets no marker and its source is `REFUSED`, OD-200-4) |
| `kept` | how many records of this array are in `entity` |
| `omitted` | how many were removed (a count of records, never a value). Known exactly because the server truncates a response it has fully read |
| `reason` | `RECORD_CAP` (the per-source record cap) or `SIZE_CAP` (the per-source size cap). If both apply, `RECORD_CAP` |
| `next` | a link of `rel: "page"` as in 3.2. Always present: a marker without a `next` is never produced, because the server truncates only when it can issue the link (fail closed) |

Selection, which records are removed: records are kept from the start of each array, in the source's order, so the kept records are always a prefix. When several arrays of one source are over, the cap is shared by taking records round-robin across the arrays in ascending `path` order, so one long array cannot starve the others (the same rule as `sealed-links.md` rule 5). The root record counts first.

Links are only issued for kept records (`_links` is built from what is returned). A chainable field inside an omitted record has no link; it appears when its page is read.

Truncation is visible by design (D-RM-12). A client that reads `entity` without reading `_truncated` has a partial view, so the schema marks the key and the documentation says so; the agent's remedy is the `next` link.

An example of a source result with a marker is in section 1 (the first item). A page continuation is in 3.2.

### The hard overall cap and its code

The overall cap is the one task 193 built: `dataprism.limits.max-records` and `dataprism.limits.max-response-size`, whole-call, refusing with `TOO_MANY_RECORDS` and `RESPONSE_TOO_LARGE`. D-RM-12 calls it "a hard overall cap as backstop", and it stays that, with the same codes and the same meaning. Per-source truncation is the first control; the whole-call cap is the second and applies to the response after truncation, markers and links included. With per-source caps chosen so that (per-source cap) times `max-sources` is at most the whole-call cap, the backstop cannot be reached by well-formed responses and fires only on a misconfiguration or on a combination the per-source caps did not foresee. No new code is needed for the overall cap. For a batch, the whole-call cap applies per item (an item over it is `REFUSED` with the same codes) and the batch cap of section 2 defers items instead of refusing them.

---

## 5. Status-only bulk follow: `follow_bulk` (0.9)

### Purpose

An investigation often asks one question of many things: "for each of these 3,000 parcel records, does the depot service know it?" `follow` answers one link and returns the entity. Doing that 3,000 times costs 3,000 model round trips and puts 3,000 records in the context. `follow_bulk` follows many links and returns **only** how each ended: a count per status code for each target source and, for the exceptions, a drill-in link each. The agent then follows the few exceptions it cares about with the ordinary `follow`.

### Input schema

The form that ships is OD-B14. The schema below has both forms; the first (`links`) is the minimal one and the second (`group`) exists only if OD-B14 chooses option (b) or (c). Exactly one of the two is given.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "follow_bulk input",
  "type": "object",
  "properties": {
    "links": {
      "description": "Links as returned in _links of earlier results. At most the bulk ceiling; more is refused with BULK_TOO_LARGE and nothing is read.",
      "type": "array",
      "minItems": 1,
      "items": { "type": "string", "minLength": 1, "maxLength": 1024, "pattern": "^[A-Za-z0-9_-]+$" }
    },
    "group": {
      "description": "A group link as returned in a result (OD-B14 option b or c only): it stands for the element links of one page of one collection.",
      "type": "string",
      "minLength": 1,
      "maxLength": 1024,
      "pattern": "^[A-Za-z0-9_-]+$"
    },
    "exceptionStatuses": {
      "description": "The statuses for which drill-in links are wanted. Absent: every status except ANSWERED (OD-B15).",
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" }
    }
  },
  "oneOf": [
    { "required": ["links"] },
    { "required": ["group"] }
  ]
}
```

Each link is checked exactly as `follow` checks it (P5 of `sealed-links.md`: scope, caller, target, expiry, authorisation, declaration, all rechecked). Nothing is trusted because it arrived in a bulk call. `links` takes only direct and translated links (relation bytes `0x00` and `0x01`); `group` takes only a group link (`0x04`); see "Relation bytes" in 3.2. The number of elements one call may follow, the links given or the elements the one group link stands for, is bounded by `dataprism.bulk.max-links` (below). Exactly one of `links` and `group` is given, so the bound is the number of links or the element count of the one group link.

### What happens in a call

The call has three phases. Nothing is read until the second phase.

1. **Admission.** Authorisation, the oversight admission checks and the rate-limit charge (OD-B10) as for any tool. Then the limiter of `sealed-links.md` section 4: if the principal is already locked in this scope (the counter has reached `invalid-attempt-limit`), the whole call is refused with `LINK_ATTEMPTS_EXHAUSTED`, before any link is looked at and without reading anything (0.9: through a lock-only method on `LinkAttemptGate`, to be added with bulk follow; task 269 provides only `open`). Then the size check: the number of links, or the element count recorded in the group link (below), above `dataprism.bulk.max-links` is refused as a whole with `BULK_TOO_LARGE`, with nothing read.
2. **Verification.** Every link is verified, in input order, with the uniform checks of `sealed-links.md` section 4 (length, structure, authentication, relation, then the post-authentication checks). A pre-authentication failure (`MALFORMED`, `TOO_LONG`, `VERSION`, `UNKNOWN_KEY`, `AUTH_FAILED`) is counted in the one per-principal, per-scope counter that `follow` and the `list_sources` page links use (each link is opened through `LinkAttemptGate.open`, keyed by `AttemptSubject` as task 269 specifies; this design adds no map). When the gate answers `Exhausted` for a link, verification stops, the call is refused as a whole with that decision's client code, and nothing is read or returned, including for the links already verified. The failure that brings the counter to the limit is still answered `LINK_INVALID`. The next link is refused at the gate's lock check. A post-authentication failure (`EXPIRED`, `LINK_WRONG_TOOL`, `TARGET_NOT_PERMITTED` and the others) is not counted by the limiter. A link that fails any check is counted in `invalidLinks` and is not read. The default `dataprism.bulk.max-links` (100, OD-B14) is deliberately larger than the default `invalid-attempt-limit` (20): a call with many forged links ends at the link after the twentieth counted failure in the window (the twentieth locks the principal, and the next link is refused at the lock check), and a call with fewer counted failures than the limit goes on. Honest links do not reach the limit, because the failures an honest holder produces (expiry, a changed permission) are post-authentication.
3. **Reading.** The authenticated, valid links, in input order. For each: the read budget is reserved (OD-B22), then the target is read through the normal fetch path (authorisation, deadline, scrub, validate). The record goes through the **whole** privacy pipeline, including the final check, even though it will not be returned. This is deliberate and is boundary 1: the status `REFUSED` (a scrub or validation refusal) can only be known by running the pipeline, and a path that read a source and decided its status without the pipeline would be a path around the privacy engine. Pseudonyms are issued in the scope exactly as a `follow` would issue them. The entity is then discarded by the orchestrator. The MCP layer receives a status, never an entity. Nothing about the record (no field, no size, no record count) reaches the response. Links not started when `dataprism.bulk.deadline` passes are counted `DEFERRED`.

The target of a link, for the counts, is the label authenticated inside the verified link, never the cleartext header of a link that failed authentication. A link that failed verification is counted in `invalidLinks` and attributed to no target.

A one-to-many translated link (a link whose follow returns `items`) contributes one status per item. Counting by item, and not by the outer status rule of `sealed-links.md` section 5, is OD-B21.

### Output schema

Results are per target source and are never summed across targets (D-RM-1: per-source results are never merged, and a count over several sources would hide a source in which every link failed behind sources in which none did). Each target label has its own counts and its own omitted-exception count. Only the links that have no target (invalid links) are counted at the top level.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "follow_bulk output",
  "type": "object",
  "properties": {
    "correlationId": { "type": "string" },
    "requested": {
      "description": "The number of elements asked for: the links given, or the elements a group link stands for.",
      "type": "integer",
      "minimum": 1
    },
    "targets": {
      "description": "One entry per target source (its label), in ascending byte order of label. Never summed across targets.",
      "type": "object",
      "additionalProperties": { "$ref": "#/$defs/targetCounts" }
    },
    "invalidLinks": {
      "description": "Elements whose link failed verification and was not read. Attributed to no target.",
      "type": "integer",
      "minimum": 1
    },
    "exceptions": {
      "type": "array",
      "items": { "$ref": "#/$defs/exception" }
    },
    "deferredBy": {
      "description": "Why some links were not followed. Present exactly when some target counts DEFERRED.",
      "const": "DEADLINE"
    }
  },
  "required": ["correlationId", "requested", "targets", "exceptions"],
  "additionalProperties": false,
  "$defs": {
    "targetCounts": {
      "type": "object",
      "properties": {
        "counts": {
          "description": "Statuses and their counts for this target: the per-source status codes of get_entity_context (any code, including REFUSED) plus SCOPE_READ_BUDGET and DEFERRED. A client treats an unknown key as a status it does not know.",
          "type": "object",
          "propertyNames": { "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
          "additionalProperties": { "type": "integer", "minimum": 1 }
        },
        "exceptionsOmitted": {
          "description": "How many exceptions of this target had no drill-in link because the cap was reached. A count only.",
          "type": "integer",
          "minimum": 1
        }
      },
      "required": ["counts"],
      "additionalProperties": false
    },
    "exception": {
      "type": "object",
      "properties": {
        "index": {
          "description": "The position of the input link (or of the group element, in the group's order).",
          "type": "integer",
          "minimum": 0
        },
        "item": {
          "description": "For a one-to-many link: the position of the item among the link's items, in the order of key-resolvers.md.",
          "type": "integer",
          "minimum": 0
        },
        "status": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
        "target": { "type": "string" },
        "link": { "type": "string", "minLength": 1, "maxLength": 1024, "pattern": "^[A-Za-z0-9_-]+$" },
        "expiresAt": { "type": "string", "format": "date-time" }
      },
      "required": ["index", "status", "target", "link", "expiresAt"],
      "additionalProperties": false
    }
  }
}
```

The status keys are the task 190 source status codes as merged (`ANSWERED`, `NO_DATA`, `TIMED_OUT`, `FAILED`, `CIRCUIT_OPEN`, `SKIPPED_OVER_LIMIT`, `REFUSED`) and `RESOLVER_FAILED` of `key-resolvers.md` (OD-200-3), with their meanings unchanged, plus two keys that exist only at this level because no source status can express them:

| Count key | Meaning |
|---|---|
| `SCOPE_READ_BUDGET` | not read: the read budget for the subject or the scope could not cover this link (OD-B22) |
| `DEFERRED` | not started before `dataprism.bulk.deadline` |

A link that failed verification has no target and is counted in `invalidLinks`, not as a status. `REFUSED` is the source status: the source answered and scrubbing or validating refused it. `SCOPE_READ_BUDGET` is the refusal code of a read that never happened. They are counted separately because the remedies differ. The sum of every count of every target, plus `invalidLinks`, equals `requested`, or the number of elements when a one-to-many link expanded: a test checks it.

An example, 40 links followed against two targets:

<!-- example-of: follow_bulk output -->
```json
{
  "correlationId": "c-77e1c4",
  "requested": 40,
  "targets": {
    "ORG-8RK2W9TE": { "counts": { "ANSWERED": 22, "NO_DATA": 3 } },
    "ORG-J3T7LP5C": { "counts": { "ANSWERED": 14, "TIMED_OUT": 1 } }
  },
  "exceptions": [
    { "index": 7, "status": "NO_DATA", "target": "ORG-8RK2W9TE", "link": "AQ-ZHJpbGwtaW4tMQAAAAByV1Uk3mRkZXBvdDZ5xYQ0lP8mC1uVx2tB9JhTe7dKq", "expiresAt": "2026-10-19T14:30:00Z" },
    { "index": 12, "status": "TIMED_OUT", "target": "ORG-J3T7LP5C", "link": "AQ-ZHJpbGwtaW4tNAAAAAByV1Uk3mRyb3V0ZTZ5xYQ0lP8mC1uVx2tB9JhTe7dKq", "expiresAt": "2026-10-19T14:30:00Z" },
    { "index": 19, "status": "NO_DATA", "target": "ORG-8RK2W9TE", "link": "AQ-ZHJpbGwtaW4tMgAAAAByV1Uk3mRkZXBvdDZ5xYQ0lP8mC1uVx2tB9JhTe7dKq", "expiresAt": "2026-10-19T14:30:00Z" },
    { "index": 31, "status": "NO_DATA", "target": "ORG-8RK2W9TE", "link": "AQ-ZHJpbGwtaW4tMwAAAAByV1Uk3mRkZXBvdDZ5xYQ0lP8mC1uVx2tB9JhTe7dKq", "expiresAt": "2026-10-19T14:30:00Z" }
  ]
}
```

### Drill-in links and the cap

An exception is a followed element whose status is in `exceptionStatuses`. By default (OD-B15) that is every status except `ANSWERED`, so the agent sees the `NO_DATA`, `TIMED_OUT`, `FAILED`, `CIRCUIT_OPEN`, `REFUSED` and `RESOLVER_FAILED` cases, which is where an investigation of where a record stopped looks. The caller can name the statuses it wants (including `ANSWERED`, for example to compare two services on the ones both hold). `SCOPE_READ_BUDGET`, `DEFERRED` and invalid links are not exceptions; they are counts only, and the client re-sends the links it still holds.

The drill-in link must not extend a lifetime or mint a link that no declaration produced (D-RM-6, D-RM-14). The recommended rule (OD-B20):

- For an input link, the exception carries the input link itself, echoed verbatim, with the link's own `expiresAt`. Nothing is re-sealed and no expiry moves.
- For an item of a one-to-many translated link, the exception carries the translated link itself, echoed, and `item`, the position of the item. Following it returns the link's items (at most the resolver cap). No link is minted that seals a key a resolver returned.
- For an element of a group link, the exception carries the per-element link as the originating response would have issued it: built from the origin record's declared chainable field, one step, with the expiry `min(the normal rule, the group link's expiry)`.

The cap is `dataprism.bulk.max-exception-links` (proposed default `20`, OD-B15). When there are more exceptions than the cap, links are chosen round-robin across targets in ascending label order, then across statuses (in a fixed status order), and within a status in ascending `index` (and `item`), so one noisy target or status cannot hide the rest and the choice is a function of the request. The entries that are returned are listed in ascending `index` (then `item`). The number left out of each target is that target's `exceptionsOmitted`, a count only, never what was left out, like `_linksOmitted`; the omitted exceptions are therefore attributable to their source. The counts still include them. 20 links at up to 768 characters are 15 KB at the worst, a typical response is a quarter of that.

### Bulk size and cost

| Key (proposal) | Proposed default | Validation |
|---|---|---|
| `dataprism.bulk.max-links` | `100` | positive; the ceiling on the elements one call follows: the links in `links`, or the element count of the one `group` link. More is refused as a whole with `BULK_TOO_LARGE`, before any read, as for `BATCH_TOO_LARGE`. The default is OD-B14 |
| `dataprism.bulk.max-group-elements` | `50` | positive; at most `max-links`. The most elements one group link may stand for (OD-B23) |
| `dataprism.bulk.max-exception-links` | `20` | positive; at most `max-links`; OD-B15 |
| `dataprism.bulk.deadline` | `PT30S` | positive duration, not shorter than `dataprism.limits.per-source-timeout` |

A bad value refuses startup with `INVALID_BULK_SETTING`. Each followed element is one read, charged (a group link's origin re-read is one more read). The rate-limit charge is one token per element, and the budget behaviour when the budget cannot cover a link is OD-B22. Links are followed in input order with the downstream concurrency shared, as in section 2.

### Why it never returns entity data

- Context volume. The only reason to follow thousands of links is a question about outcomes. Returning the entities would put thousands of records into a model context for the sake of counting them.
- A bulk tool that returned entities would be a bulk-extraction channel for a prompt-injected agent: one call, hundreds of records. Without entity data the channel carries one number per status. Reading the entity needs an ordinary `follow`, with its own audit event, budget charge and size cap, per record.
- Nothing needs to pass the response validators beyond the counts, the codes and the links, which the existing leak validators check; there is no new payload to validate.
- Counts disclose whether a link's target has data (`ANSWERED` against `NO_DATA`). That is what an ordinary `follow` of the same link would show, so bulk adds no information. It is not hidden from the caller, who is authorised for it.

### Group links (only if OD-B14 chooses (b) or (c))

A list of thousands of links cannot be sent by a model: each link is hundreds of characters and the agent has to copy them one by one into the call. A group link avoids that. It is a sealed link with relation byte `0x04`, "group". It is issued next to the per-element links of a collection page, for the records of that page only: its sealed payload is a recipe, not a list: the target, the field path, the offset, the element count, the sealed key to re-read the origin, and the fingerprint of the origin's boundary record (as for a collection page link, 3.2). It stands for the element keys at that path, in that range, whose target is the group's target. The element count is at most `dataprism.bulk.max-group-elements` and at most the records kept on the page, so the expansion of one group link is bounded by the per-source record cap and by this key, and a collection of any size is covered by one group link per page, not by one link for the whole collection (OD-10 of `sealed-links.md`, one link per element, is not changed: the per-element links remain).

When given to `follow_bulk` `group`, the call counts the sealed element count (`links` is absent: the two forms are exclusive) against `dataprism.bulk.max-links` at admission (above the limit: `BULK_TOO_LARGE`, nothing read). The server then re-reads the origin (one charged read), checks the boundary fingerprint and that the number of element keys found equals the sealed count (a difference is drift: the uniform refusal, audit-only reason `LINK_ORIGIN_DRIFT`, counted in `invalidLinks`), and follows each element. It carries no per-element key, so its size is that of a page link.

The costs are stated plainly: a group link depends on the origin re-read and on drift detection exactly as a collection page link does (a changed collection is the uniform refusal); it extends the link vocabulary by one relation; and it is a second form of the same question, which the owner may prefer not to freeze at 0.9. The elements are counted per target in the usual way and their exceptions get ordinary per-element drill-in links.

---

## 6. Audit (0.8 for Part A; 0.9 for the rest)

The audit record has one `subjectPseudonym`, one `correlationId` and a closed disposition grammar (`docs/audit.md`; shape C of OD-13: `REFUSED:<code>`-shaped values only, the one deliberate exception being the key `names`). The options for batch and bulk are OD-B16. The text below is the recommendation of OD-B16 (b); it adds no audit field and no disposition value.

### `list_sources` (0.8)

One event per call, as in A.9. Nothing added.

### `get_entity_contexts` (0.9)

One envelope event per call and one event per item.

| Event | `tool` | `subjectPseudonym` | `sourceSystems` and dispositions | `policyDecision` | `correlationId` |
|---|---|---|---|---|---|
| Envelope | `get_entity_contexts` | empty | empty | `ALLOW`, or `DENY:<code>` for a whole-call refusal (for example `DENY:BATCH_TOO_LARGE`, `DENY:TOOL_NOT_PERMITTED`) | the batch's |
| Item that ran | `get_entity_contexts` | the item's pseudonym | exactly the fields of a single call: sources, dispositions, `REFUSED:<code>` marks | `ALLOW`, or `DENY:<code>` for an item refusal (for example `DENY:SCOPE_READ_BUDGET`) | the batch's, the same for every item |
| Item deferred | `get_entity_contexts` | empty (OD-B19) | empty | `DENY:BATCH_DEFERRED` | the batch's |

The shared `correlationId` joins an envelope to its items, and the items to the client's result, as it does for a single call. The position of an item within the batch is not an audit field: items of a batch are identified by their pseudonym and the order of their sequence numbers. A deferred item has an event so that the audit always holds one event per item asked. Its pseudonym is left empty, because deriving it would write the scope's re-identification index for a subject nothing was read for (OD-B19); the event is tied to the batch by the `correlationId`, and the count of such events is the deferred count. `names` is recorded as OD-C2 decided, on the envelope.

The envelope carries the reserved arguments ignored at either level in `rejectedArguments`. It carries no item count (no field exists for one; the number of item events with the batch's `correlationId` is the count). A metric, `dataprism.batch.items` (closed label `outcome`), carries the same numbers for dashboards.

### Collection pages (0.9)

A page `follow` is a `follow`: one event, `tool` `follow`, the target as `name:status` like any source, the pseudonym of the subject, no page number. A drift or any other refusal is `DENY:LINK_INVALID` with `link` = `REFUSED:LINK_ORIGIN_DRIFT`.

### `follow_bulk` (0.9)

The same rule as the batch: one envelope event per call and one event per link asked, whatever became of it. Nothing asked goes unrecorded.

| Event | `tool` | `subjectPseudonym` | Dispositions | `policyDecision` |
|---|---|---|---|---|
| Envelope | `follow_bulk` | empty | none | `ALLOW`, or `DENY:<code>` for a whole-call refusal (`DENY:BULK_TOO_LARGE`, `DENY:LINK_ATTEMPTS_EXHAUSTED`, `DENY:TOOL_NOT_PERMITTED`, the admission codes) |
| Link followed | `follow_bulk` | the subject's pseudonym, as a single `follow` records it | as a `follow`: the target as `name:status` | `ALLOW` when the status is `ANSWERED`, `NO_DATA`, `FAILED` or another non-refusal status. When the target source is `REFUSED`, `DENY:<code>` with the code of the refusal, as a single `follow` records it (OD-B24 (b), decided 2026-10-19); the call still returns normally |
| Link that failed verification | `follow_bulk` | empty | `link` = `REFUSED:LINK_<REASON>` | `DENY:LINK_INVALID` |
| Link whose reservation hit the budget | `follow_bulk` | empty | none | `DENY:SCOPE_READ_BUDGET` |
| Link deferred (deadline) | `follow_bulk` | empty (OD-B19) | none | `DENY:BULK_DEFERRED` |
| Group link | `follow_bulk` | empty | the origin as `name:status` | `ALLOW`, or `DENY:LINK_INVALID` with the `link` reason; its elements each get a "link followed" event |

All events of a call share its `correlationId`. A bulk call of 100 forged links writes at most the events up to the limiter's limit (verification stops there, section 5) and the limiter locks the principal, as for `follow`. The counts and `deferredBy` are also the metric `dataprism.bulk.links` (closed label `status`), since the closed grammar has no place for counts. Nothing in an event is the token, an entity or a count.

### Estimated volume for a 5,000-item investigation

This is an estimate from stated assumptions, not a measurement. The load harness of task 196 should measure the real size of a record and the number of dispositions, and the figure replaces these.

Assumptions. The fixed part of a version 3 record (25 fields: ids, timestamp, the two 64-character hashes, the correlation ids, scope and case) is about 1 KB in the file format. A disposition (`<source>:<json-pointer>` and the action) is about 50 bytes, and every classified field of every answering source writes one. The source names are about 14 bytes each. "Low" is 8 sources of 5 fields each, "typical" 8 sources of 12 fields, "high" 8 sources of 40 fields.

| Quantity | Calculation | Result |
|---|---|---|
| Item events (batch of 5,000 items) | 5,000 events × (1 KB + 8 × 14 B + dispositions × 50 B) | low (40 dispositions) about 3.1 KB each, 15 MB; typical (96) about 5.9 KB each, 29 MB; high (320) about 17 KB each, 85 MB |
| Envelope events at the default batch size 16 | ⌈5,000 / 16⌉ = 313 events × about 1.2 KB | about 0.4 MB |
| Envelope events at a batch size of 64 | ⌈5,000 / 64⌉ = 79 events | about 0.1 MB |
| JSON projection of the same events (`docs/audit.md`) | roughly 1.3 times the line format | 20 to 110 MB |
| `follow_bulk` over 5,000 links, one source each | 5,000 events × (1 KB + 14 B + 12 × 50 B) | about 1.6 KB each, 8 MB |
| The same 5,000 items as 5,000 single `get_entity_context` calls | the same item events, plus no envelope | the same 15 to 85 MB |

Reading it. Batch and bulk do not add audit volume per item; they add the envelope events (under 3 percent). The volume is the volume of the individual reads, which is the volume of the same investigation done one call at a time. It is large because the disposition map holds one entry per classified field of every source. The 0.9 work item "audit-entry size handling" (roadmap) and the size-capped segments (task 207) are what keep it manageable; this design requires no further change for them. If the owner wants less, OD-B16 (a) (one event per call) reduces it to the envelope figure and costs per-subject accountability, and (c) records an item count with a reserved key.

---

## 7. Boundaries

1. **Nothing reaches `mcp` without passing the privacy engine.** Every item of a batch is a full `get_entity_context` pipeline: authorisation, budget, fetch, scrub, validate, the final check over the assembled item, and then the size and record caps. Truncation runs after validation and removes records, so it cannot add a value. The batch tool assembles items and adds structure (`index`, `outcome`, `summary`, `limits`, markers) which carries no source data; the assembled batch response is itself passed to the response validators as one tree. `follow_bulk` runs the whole pipeline for each link and discards the entity in the orchestrator, so `mcp` receives a status and a link, never a record. A page `follow` re-reads and re-validates the source before it slices. A new architecture rule forbids `mcp` classes from depending on the raw-record or raw-key accessors of the bulk and paging code, as `sealed-links.md` boundary 1 does for the link builder.
2. **Boundary 5 (re-identification).** None of these tools can return a real subject or key, and none reaches `ScopeIdentityIndex.subjectFor`. The sealed key in a collection page link is the same class of value as a chained key in a link (OD-1 (a')); the fingerprint of a boundary record is not a pseudonym and is never written to the index.
3. **Boundary 6 (Hazelcast).** No page, batch or bulk state is stored: no cursor, no list snapshot, no batch table, no used-link set. The `list_sources` page and the collection page are stateless sealed links. The only shared state touched is the existing read budget (a reservation of several reads by one atomic step per item, in the same map) and the failed-attempt counter of task 191 (page-link failures counted in it, no new map).
4. **Boundary 7 (no sensitive value in log, metric, exception, audit).** A page link, a group link, a drill-in link or a rejected token is never logged, audited, put in a metric label or an exception message, not even truncated. Markers carry a field path, two counts and a closed reason, never a value; the path follows the `<undeclared-N>` rule of task 191. Batch `index` values are positions. The metrics `dataprism.paging.pages`, `dataprism.batch.items` and `dataprism.bulk.links` use closed labels only. `PiiLogScanTest` and `AuditFilePiiScanTest` are extended to cover them.
5. **Boundary 8 (the core carries no business domain).** The batch, paging and bulk code is generic. The parcel, depot and courier-roster domain exists in this document and in tests, not in `core`.

Also: fail closed (rule 2) is honoured by the uniform refusal for any invalid page, group or drill-in link, by refusing instead of truncating when no link can be issued (3.2), by refusing an untruncatable over-large source (OD-B18), and by counting every non-followed link, in a target's `counts` or in `invalidLinks`. Rule 4 (the caller never supplies scope, principal, purpose or case) holds: scope and caller are in the AAD of every link, and the batch size is resolved from the scope, not from an argument.

## 8. Test obligations for 0.8 and 0.9

Mutation-testable: each line names a mutant that must be killed. The privacy modules are mutation-tested from 0.7 and gated in 0.9 (D-070-9).

0.8, `list_sources` paging:

1. **Completeness and order.** For estates of 0, 1, `n`, `n+1` and `2n` entries at page size `n`, walking the pages returns every entry exactly once in ascending byte order of label; the last page has `last: true` and no `next`; an estate that is an exact multiple of the page size has no empty final page. Mutants: offset instead of keyset; `>=` for `>` on the last label; decide `last` without looking one entry past.
2. **Filters.** Both filters apply before the slice; a continuation call with a different filter or `pageSize` is refused; one with the same values or none succeeds. A filter that matches nothing returns `sources: []` and `last: true`. Mutant: filter after slicing (short pages).
3. **Hidden sources.** A source the caller may not use never appears, does not alter any page boundary, and is not counted anywhere (compare the pages of two callers whose visible sets differ). Mutant: slice the unfiltered list.
4. **Label kind.** A walk never mixes alias and real labels; a link issued under aliases is refused when real names are in effect, and the reverse. The sealed last label is the label of the kind in effect. Mutant: ignore the kind in the link.
5. **Binding.** A page link is refused in another scope, for another principal (when bound), after expiry, and in `follow`; a `follow` link is refused in `list_sources`. Each field of the AAD has its own test (relation, scope, caller, expiry, key id, version). Mutants: drop the relation from the AAD or the check.
6. **Catalog change.** A catalog with a source added, one removed, and the visible set narrowed between two pages: the second call is refused with the uniform refusal and the right audit reason; the same catalog gives the same tag on a second instance. Mutant: compare the tag only on the first page.
7. **Uniformity.** The client-visible refusal for every invalid page link is byte-identical apart from `correlationId`; the audit reason differs; the timing test of `sealed-links.md` item 10 is extended to page links. Mutant: a distinct code or text for expiry.
8. **Limiter.** Page-link failures before authentication count in the `follow` counter, post-authentication reasons do not; a locked principal gets `LINK_ATTEMPTS_EXHAUSTED` for a call with `page` and not for a call without it.
9. **Page size.** The default applies when absent; a value above the ceiling is handled as OD-B6 decides, and `page.size` reports the size applied; zero and negative values fail the schema. Boundary values: ceiling, ceiling plus one.
10. **Size.** The worst-case page link (maximum labels, maximum filters) is no longer than `max-length` and the arithmetic of A.4 is recomputed by a test from the layout constants.
11. **Audit.** One event per call; `sourceSystems` holds only the page's sources; `names` is on every page; no link, label of the client or description text appears in any audit record, log, metric or exception. `docs/audit.md` documents the `link` reasons added in A.5.
12. **Key rule.** The rule chosen in OD-B2 is tested at both edges: an estate of one page needs no key; an estate of one page plus one refuses startup without one (or whatever OD-B2 decides).
13. **Schemas.** The schemas in Part A are valid draft 2020-12 and the examples validate; the served schema is generated from the same definition and compared with the checked-in copy.

0.9, batch, paging, truncation and bulk:

14. **Batch size.** Batch of the scope size is accepted; one more is refused with `BATCH_TOO_LARGE` and no source, budget or identity index is touched (counting adapter and counting budget). A scope pattern override is used; one above the ceiling refuses startup; the first matching pattern wins. Mutants: `>=` for `>`; compare against the ceiling instead of the scope size.
15. **Item isolation.** An item refused for each cause (budget, selection, validation, over-large) leaves every other item `DONE`; a refused source inside a `DONE` item is omitted as D-070-2 says; unknown `outcome`, `code` and `deferredBy` values are treated as "not answered" by the reference client. Mutants: a failure in one item fails the call; a deadline on one item stops the others.
16. **Order and determinism.** `results` is in input order whatever order items finish; the same request gives the same `DEFERRED` set under a pinned clock. `DEFERRED` items form a suffix (a property test over random completion orders). Mutant: order by completion.
17. **Budget.** Reservations are made in input order; an item that does not fit is `REFUSED` with `SCOPE_READ_BUDGET`; later items on another subject still run until the scope total is exhausted; a `DEFERRED(DEADLINE)` item's reservation is released; a `DEFERRED(RESPONSE_CAP)` item's is not. Two instances sharing the budget cannot jointly exceed it. Mutant: reserve after dispatch; charge once per batch.
18. **Caps.** Each item is capped on its own as a single call; the batch cap (envelope and wrappers included) defers from the first item that would overflow and every later one; an item that does not fit an otherwise empty response is `REFUSED` with `RESPONSE_TOO_LARGE` and never `DEFERRED`, so a batch whose cap equals `max-response-size` and whose first item is exactly `max-response-size` makes progress and is not charged on a loop of retries; startup refuses a batch cap below the whole-call cap and a deadline below the per-source timeout. Boundary values at the cap and the cap plus one byte. Mutant: ignore the envelope when measuring; defer the first item.
19. **Truncation.** Only whole records of arrays of objects are removed; the kept records are a prefix in the source's order; `kept + omitted` equals the original count; the marker holds no value; the cap is shared round-robin across arrays in `path` order; an untruncatable over-large source is `REFUSED`, with `REFUSED:RESPONSE_TOO_LARGE` in the audit. Truncation runs after validation: a record that would have been refused is never part of the kept prefix. Mutants: truncate before validating; keep a suffix; truncate a scalar; count the marker out of the size.
20. **Overall cap.** The whole-call `TOO_MANY_RECORDS` and `RESPONSE_TOO_LARGE` still apply after truncation, markers and links included, and per-source caps above them refuse startup.
21. **Collection paging.** Reading every page returns each record of the collection once, in order; a page of a collection that changed ahead of the boundary is refused (`LINK_ORIGIN_DRIFT` in the audit only) and one that changed behind it is not; each page read is charged one read; a page link is refused in `list_sources` and in `follow_bulk` `links`, and a `follow` link is refused as a page (the relation table of 3.2, one test per byte and per tool, audit reason `LINK_WRONG_TOOL`, not counted by the limiter). Fail closed: when no link can be issued (links disabled; a key that does not fit `max-length`; the worst-case link at its exact length and one more) nothing is truncated and no marker without a `next` is ever produced; the source is refused as OD-B18 decides. The sealed key is the only real identifier in the link (the test opens the payload with the test key); the leak tests of `sealed-links.md` item 17 are extended. Mutant: truncate and omit `next`.
22. **Bulk.** Per-link statuses are counted per target and never summed across targets (a test with one target failing every link and one answering every link must show both); the counts of all targets plus `invalidLinks` sum to `requested`; `exceptionsOmitted` is per target and exact; the entity is absent from every response and from every log (a counting validator proves the pipeline ran for each link, including a stubbed refusing validator that yields `REFUSED`); the exception cap is applied round-robin by target, then status, then index; `exceptionStatuses` filters and `ANSWERED` can be asked for; a drill-in link is the input link echoed with an unchanged expiry (no expiry moves, nothing is re-sealed), a one-to-many exception carries the translated link and `item`, and no link sealing a resolver-returned key is ever produced; a one-to-many link counts per item (OD-B21). Mutants: return the entity; skip validation when the entity is discarded; sum across targets; re-seal with a new expiry; drop `REFUSED` from the counts.
23. **Bulk limits.** A list above the ceiling (the links, or the group element count) is refused with `BULK_TOO_LARGE` and nothing is read; a group link whose sealed count exceeds `max-group-elements`, or whose re-read finds a different count, is refused and counted in `invalidLinks`. Limiter: a principal already locked gets `LINK_ATTEMPTS_EXHAUSTED` for the whole call with nothing read; a call whose verification reaches the limit mid-way is refused as a whole with nothing read and nothing returned, and the counter is at the limit; post-authentication failures are never counted; with 100 links of which 19 are forged the call proceeds, with 20 it is refused; the call shares one counter with `follow` and `list_sources` page links (same key, no new map). Budget: behaviour as OD-B22 decides, tested at the edge of the budget and the scope total.
24. **Audit.** For both batch and bulk: an envelope and exactly one event per item or link asked, including deferred ones and the one that hits `SCOPE_READ_BUDGET` (the number of events equals the number asked, tested over mixed outcomes); deferred events have `DENY:BATCH_DEFERRED` or `DENY:BULK_DEFERRED` and an empty `subjectPseudonym`, and no pseudonym is derived for them (a counting identity index proves it); all share the call's `correlationId`; no event carries a token or an entity; volume is measured by the load harness against section 6 and the estimate is replaced by the measurement.
25. **Architecture rules.** `mcp` and `connectors` do not depend on the raw-record or raw-key accessors; no class outside the sealer builds a page, group or drill-in link; `core` has no business-domain type.
26. **Schemas.** Every schema in this document is valid draft 2020-12 and every example validates (command in the Outcome of the task file).
27. **Configuration.** Each setting's defaults and its refusals (`INVALID_PAGING_SETTING`, `INVALID_BATCH_SETTING`, `INVALID_BULK_SETTING`, `INVALID_REQUEST_LIMITS` for the per-source caps) are tested, including every relation between settings.
28. **Scale.** The load harness runs a 5,000-item investigation by batch and by bulk at 100, 300 and 500 sources and records latency, budget use and audit size (roadmap, 1.0 evidence).

## 9. Open decisions for the owner

All are decided as of 2026-10-19. The options and recommendations are kept for the record, and each row ends with its decision. Where a decision depends on another, that is noted. Part A's decisions (OD-B1 to OD-B6) block the 0.8 build of paged `list_sources`; the rest block the 0.9 freeze. Several rows record a choice the text had made quietly in an earlier draft (OD-B18 to OD-B24, and the sub-choices of OD-B2, OD-B11 and OD-B12). The text follows the decision and is marked.

| # | Question | Options | Recommendation |
|---|---|---|---|
| OD-B1 | `list_sources` page defaults (keys in A.8) | (a) default 25, ceiling 100, lifetime `PT15M`; (b) default 50, ceiling 200, lifetime `PT30M` (the link default); (c) default 10, ceiling 50, lifetime `PT10M`. The cost of a page is 250 to 400 bytes an entry: (a) is 6 to 10 KB by default and 25 to 40 KB at the ceiling; (b) is 12 to 20 KB and 50 to 80 KB; (c) is 2.5 to 4 KB and 12 to 20 KB | (a). A page of 25 sources is easy for a model to read and 100 is the largest that still fits comfortably; fifteen minutes is long enough for an agent to walk a long list and short enough to bound a replay. A starting value for the load harness to check **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B2 | The key that seals `list_sources` page links in 0.8, and what happens without one. The question is wider than the estate size: any client can send a small `pageSize`, so any estate with more entries than that size needs a `next` link, whether the estate is large or not. `dataprism.links.enabled` is `false` by default through 0.9 (OD-7 of `sealed-links.md`) | (a) use `dataprism.links.key-id`. At startup it is required (`PAGING_KEY_REQUIRED`) exactly when the registered estate is larger than the default page size; `dataprism.links.enabled` keeps controlling only `_links` and `follow`. At call time, with no key (reachable only for an estate within one default page, and only for a smaller `pageSize`), the server ignores a `pageSize` smaller than the number of entries it would return and returns the whole list in one page with `last: true`, `page.size` reporting the size applied; (a2) as (a) but at call time a request that would need a `next` link without a key is refused with `PAGING_KEY_REQUIRED`: strict, and a harmless `pageSize` becomes an error; (b) a separate `dataprism.paging.key-id`, always independent of the link key: one more key to manage, rotate and destroy; (c) no seal: an unsealed `after=<label>` keyset argument, no key, no binding, no expiry (a forged one only chooses where to start in the caller's own list). Simplest, leaks nothing, never needs a key, but is not "sealed next links" as D-RM-12 and the brief say and cannot be reused for the collection links of 3.2; (d) require `enabled=true` for any paging: a default install of a large estate would have no paging | (a). It keeps one key and one mechanism, makes a large estate without a key a startup error and not a silent unpaged list, and never refuses a harmless `pageSize` (nothing is lost: the whole list fits one default page by the startup rule). (a2) if the owner prefers a strict refusal; (c) if the owner would rather not tie 0.8 paging to the link key. This is a decision about when a key is required, which the owner should make explicitly **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B3 | The refusal for an invalid page link in `list_sources` | (a) the existing `LINK_INVALID`, sharing the limiter of `follow`: no new code, one remedy ("start the listing again"); (b) a new `PAGE_INVALID` code, with its own limiter or the shared one: the client can tell a page link from a `follow` link, and it needs one more frozen name; (c) distinct codes per reason (expired, catalog changed): friendlier, but an oracle | (a). A single uniform refusal is the design's whole defence against probing, the remedy is the same for every reason, and the surface at the freeze stays smaller. (c) is rejected **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B4 | A catalog (or label kind, or visible set) that changed between two pages | (a) refuse with the uniform refusal; the walk starts again (A.6); (b) continue by keyset from the last label regardless: entries added or removed during the walk may be missed or shown late, silently; (c) continue and add `page.changed: true` to the response, an extra field and a weaker end-of-list claim | (a). Fail closed, and a walk is either complete or refused. The cost is a restart after a deployment, a few pages at most. (b) hides a gap from the agent and (c) adds a field to the frozen surface **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B5 | A `total` in the `list_sources` page | (a) none; (b) `page.total`, the number of visible, filtered sources, only on the first page; (c) on every page | (a). The count discloses nothing the walk does not, but it has to be computed and agree with the pages if the catalog moves; it can be added later as an optional field. Not needed to walk a list **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B6 | A `pageSize` above the ceiling | (a) apply the ceiling and report it in `page.size`; (b) refuse with a new code | (a). A listing is metadata, the end marker is explicit, so nothing is silently lost; a refusal would add a code to freeze for no safety gain **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B7 | The capability for `get_entity_contexts` and `follow_bulk` | (a) a new capability for each tool (`GET_ENTITY_CONTEXTS`, `FOLLOW_BULK`), granted per role, off until granted, with an upgrade note as for `compare_entity_sources` in 0.2.0; (b) reuse `GET_ENTITY_CONTEXT` for the batch and `FOLLOW` (OD-200-5, defined in `sealed-links.md`) for bulk, so existing grants carry over | (a). Oversight (pause, per-tool approval, rate limit) and role policy are per tool name; a batch is a bigger operation than a single read and should be granted on purpose. (b) widens existing grants silently **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B8 | The batch ceiling (key `dataprism.batch.ceiling`) and the rule for per-scope sizes | (a) ceiling 64, per-scope sizes by scope patterns (`dataprism.batch.scope-sizes`); (b) ceiling 32; (c) ceiling 128; (d) one global size, no per-scope setting (does not meet D-RM-7). The default 16 is decided in D-RM-7. At 5,000 items a call of 16 is 313 calls, 32 is 157, 64 is 79, 128 is 40 | (a). 64 limits the size of a single response and the time of one call while cutting calls by four against the default; the scope patterns reuse the grammar of OD-C3 (c). A starting value for the load harness **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B9 | How a client learns its batch size | (a) `limits.batchSize` in every batch response (shown in the schema); (b) only through the static tool description (a ceiling, not the scope's size); (c) not at all: the client learns from `BATCH_TOO_LARGE` | (a). Without it an agent has to guess and fail. It discloses a configuration value to a caller who is authorised to use the tool **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B10 | The rate-limit charge and the budget charge for a batch and for `follow_bulk` | Rate limit: (a) one token per item, and per followed element in `follow_bulk` (a batch of 16 uses 16 of the caller's allowance); (b) one per call; (c) one per call plus one per item beyond the first. Budget: both tools charge the read budget per source read, per item or element (a group link adds one read for the origin re-read); a separate bulk read allowance is not proposed | (a) for the rate limit. Otherwise a batch or a bulk call multiplies a caller's allowance by its size, and `follow_bulk` is the cheapest way to make many reads. Needs the oversight limiter to charge a number of tokens, which it may not support yet (`docs/tools.md` says oversight wiring is to be revisited); (b) is the fallback and then operators should read the allowance as per call. The budget rule follows from the 0.8 per-source charge and decides nothing new **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B11 | Batch response cap, deadline, progress when the first item does not fit, and what a cap does to an item that was read | (a) `dataprism.batch.max-response-size` default equal to `max-response-size`; `dataprism.batch.deadline` default `PT30S`; the cap covers the whole body, envelope included; items over the cap are `DEFERRED` with the reads left charged, except that an item which does not fit an otherwise empty response is `REFUSED` with `RESPONSE_TOO_LARGE` (a per-item refusal); (b) as (a) but refund the reads of an item deferred by the cap; (c) refuse the whole batch when it would exceed the cap: no partial batch; (d) reserve a fixed envelope headroom (startup requires the batch cap to be at least `max-response-size` plus the reserve) and measure each item without its wrapper, so an item that fits alone always fits first; an item over the single-call cap is already `REFUSED` | (a). The per-item refusal guarantees progress on any configuration, so no retry loop can charge the same reads again. A refund needs the reads to be re-credited after being spent, a new budget operation, and a re-read is an honest cost of asking for more than fits. (c) wastes the whole batch for one large item and breaks the progress guarantee. (d) is also sound if the owner prefers a configuration rule to a runtime refusal **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B12 | How a collection page obtains the remaining records; what the default installation does; and the link size | Mechanism: (a) re-read and slice, with the boundary fingerprint check: source order kept, drift refused, one whole-response read per page; (b) adapter-native paging: cheaper per page, but a new SPI to freeze at 0.9 and a per-adapter obligation; (c) re-read, sort the records by a keyed fingerprint and page by keyset: stable under change but destroys the source's order; (d) (a) now and (b) as an additive SPI after 1.0. When no link can be issued: (e) refuse, never truncate without a `next` (the contract of section 4: `next` is required in every marker); (f) truncate with markers and no `next`, a partial answer with no way forward. Default installation: links are off by default through 0.9, so under (e) a default install never truncates and D-RM-12 per-source truncation is off unless links are enabled, an oversized collection being refused as in 0.7; the options to change that are (g) enable links by default (reopens OD-7 of `sealed-links.md`), (h) register a page-only `follow` and the key rule of OD-B2 whenever a paging key is configured, independent of `links.enabled`, or (i) accept it and say so in the 0.9 documentation. Link size: the collection page link is 576 bytes, 768 characters, at the worst, a margin of zero against the default `max-length` 768: (j) raise the default `max-length` to 832 (margin 64 characters; task 191's decided default of 768 was sized for chained links only); (k) keep 768 and refuse paging for sources whose entity type or path is longer than 32 characters (worst case 684 characters, margin 84), by a startup check; (l) leave it: a worst-case link can still be issued, at exactly the limit, and it stops being issuable only when a later header field is added, at which point paging breaks for worst-case sources | (d), (e), and (i) unless the owner wants truncation on by default, in which case (h): it keeps the truncation contract fail closed and the default behaviour unchanged. (j) for the size, because a margin of zero would make a future header field break paging; it changes a number 191 decided and the owner should say so. The page-link size is also a reconciliation for task 200 with task 191's sizing **Decided by the owner, 2026-10-19:** truncation option (i): per-source truncation is off by default while links are off, and the server never truncates without a `next` link (e). The default `dataprism.links.max-length` is raised to 1024, not the 832 of option (j); this replaces the 768 default that task 191 decided (OD-12 of `sealed-links.md`), and the worst-case page link of 768 characters now has a margin of 256 characters. **Decided (owner delegated), 2026-10-19:** the mechanism, re-read and slice with a boundary fingerprint (d, then a), follows the recommendation. |
| OD-B13 | The per-source caps (keys in section 4) | (a) `max-records-per-source` 60, `max-source-response-size` 64 KB: with `max-sources` 8 they sum to the whole-call caps (480 of 500 records; 512 KB); (b) a single share rule computed from the whole-call caps and the number of sources asked, no new keys; (c) per source, set by the adapter's description (`cost` hint) | (a). Two explicit keys the operator can reason about, and defaults that make the whole-call cap a backstop only. (b) makes the cap depend on how many sources were asked, which is hard to explain; (c) conflicts with OD-C12 (the hints are advisory) **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B14 | The input form of `follow_bulk`, its element ceiling, and a group link | (a) an explicit `links` list only, ceiling `dataprism.bulk.max-links` 100: the minimum surface, but each link is hundreds of characters the model must copy, and a 5,000-link investigation is 50 calls; (b) a `group` link only, one per collection page (3.2's mechanism, relation `0x04`): many items in one small call, but a new link kind and an origin re-read; (c) both. The ceiling counts the links, or the elements the one group link stands for (OD-B23). Note OD-10 of `sealed-links.md` ruled out one link for a whole collection as the per-element link; the group link is a separate, additional link, bounded to one page of the collection, and does not change that decision | (c). The explicit list is the base. The group form is what makes thousands of items workable for a model; if the owner wants the smallest frozen surface, (a) with a higher ceiling, accepting the copying cost. Decide before the 0.9 freeze: it is hard to add a link kind afterwards **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B15 | Which statuses get drill-in links, and the cap | (a) every status except `ANSWERED` by default, settable per call by `exceptionStatuses`; cap `dataprism.bulk.max-exception-links` default 20, chosen round-robin across targets, then statuses, then by index, with `exceptionsOmitted` per target; (b) a fixed set (`NO_DATA`, `TIMED_OUT`, `FAILED`, `CIRCUIT_OPEN`, `REFUSED`), not settable; (c) only the statuses the caller names, with no default. Counts are per target in every option (D-RM-1) | (a). Which outcome matters depends on the question (`NO_DATA` is the signal when looking for where a record stopped); a settable list is generic and costs one optional argument. A `REFUSED` drill-in will refuse again; it is included because the agent may want the exact refusal path **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B16 | The audit granularity for batch and bulk | (a) one event per call: smallest, 313 events for 5,000 items, but one `subjectPseudonym` per event cannot record sixteen subjects, so per-subject accountability is lost; (b) one envelope event plus one event per item or link asked (section 6), deferred and budget-refused ones included: the full record, about 15 to 85 MB for 5,000 items; (c) one event per call plus a reserved key carrying an item count, like `names` (OD-C2): accountability by count only, and a widening of the grammar for one more key | (b), the same rule for both tools. A read of personal data must be attributable to its subject, and (b) is the only form that needs no grammar change; the volume is the volume of the same investigation done call by call, so the cost is not new. Whether a deferred event carries a pseudonym is OD-B19 **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B17 | The tool and capability names | (a) `get_entity_contexts` (named in the roadmap), `follow_bulk`, `GET_ENTITY_CONTEXTS`, `FOLLOW_BULK`; (b) `follow_many`, or `summarise_follow`; (c) `get_entity_context_batch` | (a). The batch name is the roadmap's. `follow_bulk` reads as the bulk form of `follow`, and "status-only" is explained in its description. Names freeze at 0.9, so they are the owner's to settle **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B18 | An over-large source that cannot be truncated, or whose arrays cannot get a `next` link, in 0.9. Task 193 built a whole-call refusal (`TOO_MANY_RECORDS`, `RESPONSE_TOO_LARGE`); D-070-2 refuses a call only when every answered source is refused | (a) a per-source refusal: the source is marked `REFUSED` (omitted), the audit holds `REFUSED:RESPONSE_TOO_LARGE` or `REFUSED:TOO_MANY_RECORDS` for it, and the call is refused only when every answered source is refused. This changes task 193's behaviour for the case and needs a note in the migration guide; (b) keep 193's whole-call refusal: one huge source refuses the call, however many other sources answered; (c) per-source refusal for a source that cannot be truncated, whole-call for the final backstop only | (a). It matches D-070-2 (one bad source does not take the others), and in a hundred-source estate a whole-call refusal for one source is the worse failure. The whole-call cap stays as the backstop (section 4). This is stated as a product choice because it changes a merged behaviour **Decided by the owner, 2026-10-19:** option (a). A source that cannot be truncated, or whose arrays cannot get a `next` link, is refused per source with `REFUSED:RESPONSE_TOO_LARGE` (or `REFUSED:TOO_MANY_RECORDS`), replacing task 193's whole-call refusal for that case. The hard overall cap stays as the backstop. The migration guide must say so. |
| OD-B19 | The `subjectPseudonym` of an audit event for an item or link that was deferred and never read | (a) leave it empty: the event says an item was asked and not run, nothing in the scope's re-identification index is written for a subject nothing was read for; (b) derive it, so every item event is attributable to a subject, at the cost of writing the index for unread subjects (boundary 5) and of pseudonyms existing for subjects that were never touched; (c) no event for deferred items: the envelope carries the batch and the client re-asks | (a). Accountability for a read does not need a pseudonym when there was no read, and the index should hold only subjects that were processed. (b) is available if the owner wants every asked item to be attributable **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B20 | The drill-in link in `follow_bulk` exceptions: lifetime and one-to-many items. The draft in which the server re-sealed every drill-in link with a new lifetime would let a link live as long as the scope by repeated refresh, and a one-to-many drill-in that seals a resolver-returned key is a link no declaration produced (D-RM-14) | (a) echo the input link verbatim with its own expiry; a one-to-many item carries the translated link and `item`; a group element carries the per-element link as the originating response would have issued it, with the expiry capped by the group link's; no re-sealing, no new lifetime; (b) re-seal with an expiry capped at the input link's expiry (no extension, but a new nonce and a new link for the same authority); (c) re-seal with a fresh lifetime (a refresh), and for a one-to-many item a direct link sealing the resolver-returned key | (a). It respects D-RM-6 (lifetime) and D-RM-14 (one-step links from declarations), and adds no authority object. (c) is not recommended: it extends a lifetime within the scope indefinitely and mints a link from a resolver output **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B21 | Counting a one-to-many translated link in `follow_bulk` | (a) one count per item, each under its own status: no item's status is hidden behind another's, and the exception names the item; (b) one count per link under the outer status rule of OD-9 of `sealed-links.md` (`ANSWERED` if any item answered): consistent with `follow`, but a link with one answered item and four missing counts as answered | (a). OD-9 defines the outer `status` of a source result in `follow`, which is still returned by `follow`; counting items does not merge anything, and it keeps an inconsistency between items visible, which is the product principle. It is a departure from applying OD-9's rule in this place, so it is stated as a choice **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B22 | What a `follow_bulk` run does when the read budget cannot cover a link. The batch continues past a refused item (section 2); an earlier draft of bulk stopped at the first failure | (a) as the batch: reserve in input order; a link whose reservation does not fit is counted `SCOPE_READ_BUDGET` for its target and the run continues, so a later link on another subject can still run until the scope total is exhausted; no `READ_BUDGET` deferral; (b) stop at the first failure and count the rest `DEFERRED` | (a). One rule for both tools, a result that is a function of the request and the budget state, and no reservation after a failure that could differ by timing. (b) wastes the links that would have fitted **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B23 | How far a group link may expand, and how that counts | (a) a group link stands for at most `dataprism.bulk.max-group-elements` (default 50) elements of one collection page, and its sealed element count is checked against `dataprism.bulk.max-links` at admission (`links` and `group` are exclusive), before anything is read; (b) no per-group cap, only `max-links` on the expansion after the re-read (reads would happen before the limit is known); (c) a group link may stand for a whole collection, expanded in chunks | (a). The limit is known before any read, the expansion is bounded by the per-source record cap, and a large collection is covered by one group link per page. (c) needs a continuation state **Decided (owner delegated), 2026-10-19: the recommendation.** |
| OD-B24 | The audit decision of a `follow_bulk` link whose target source is `REFUSED`. A single `follow` to the same target is a whole-call refusal (`DENY:<code>`, D-070-2 applied to a one-source call). The text records the bulk link as `ALLOW` with `name:status` and `REFUSED:<code>`, because the call as a whole succeeded and the status is counted | (a) as written: `ALLOW`, with the target's status and the `REFUSED:<code>` mark; (b) align with `follow`: the link's event is `DENY:<code>` with the code of the refusal, so the audit of a read is the same whichever tool made it; the call still returns normally | (b). The same read of the same target should leave the same decision in the audit whichever tool made it, and an auditor querying for `DENY:<code>` would otherwise miss refusals made through bulk. The table in section 6 follows (a) until the owner decides **Decided by the owner, 2026-10-19:** option (b). A `follow_bulk` link whose target source is `REFUSED` is audited `DENY:<code>`, aligned with `follow`; the call still returns normally. The section 6 table follows this. |

## 10. Proposed names (closing table)

Checked for collisions against `docs/tools.md`, `docs/audit.md`, `docs/configuration.md`, the three existing design documents (`catalog.md`, `sealed-links.md`, `key-resolvers.md`) and `cluster-security.md`, the main and test sources, and the task files for 190 to 200. The commands are in the Outcome of the task file. None collides with an existing name. Where a name is close to one, the third column says so. Reconcile in task 200.

| Name | Kind | Proposed release | Closest existing name and why it is distinct |
|---|---|---|---|
| `pageSize`, `page` | `list_sources` input arguments | 0.8 | `page` the object in the output has the same name as the input argument, with a different shape (a string in, an object out); the same pattern as `entityType` |
| `page` (`size`, `last`, `next`, `expiresAt`) | `list_sources` result object | 0.8 | `expiresAt` is the field of a `_links` entry, same meaning |
| `LINK_WRONG_TOOL`, `LINK_FILTER_MISMATCH`, `LINK_LABELS_CHANGED`, `LINK_CATALOG_CHANGED` | audit-only reason codes (shape C) | 0.8 | the eleven `LINK_<REASON>` codes (`DEPTH_EXCEEDED` withdrawn, OD-200-8) of task 191 (siblings; `LINK_ORIGIN_DRIFT` and `LINK_EXPIRED` are reused from them) |
| `dataprism.paging.list-sources.page-size`, `.max-page-size`, `dataprism.paging.lifetime` | configuration keys | 0.8 | `dataprism.links.lifetime` (the link default; this one caps page links) |
| `INVALID_PAGING_SETTING` | startup refusal code | 0.8 | `INVALID_CATALOG_SETTING`, `INVALID_LINK_LIMIT` (siblings) |
| `PAGING_KEY_REQUIRED` | startup refusal code (OD-B2 (a)) | 0.8 | `LINK_KEY_UNRESOLVED` (siblings) |
| relation byte `0x02` (catalog page), `0x03` (collection page), `0x04` (group) | link relation codes | 0.8 (`0x02`), 0.9 (`0x03`, `0x04`) | `0x00`, `0x01` of task 191 |
| `rel: "page"` | `_links` / `next` relation value | 0.9 | `direct`, `translated` (task 191) |
| `dataprism.paging.pages` | metric (counter, closed labels) | 0.8 | `dataprism.links.keys.loaded` (sibling group) |
| `get_entity_contexts` | MCP tool | 0.9 | `get_entity_context` (the singular tool; plural is the batch) |
| `follow_bulk` | MCP tool | 0.9 | `follow` (task 191) |
| `GET_ENTITY_CONTEXTS`, `FOLLOW_BULK` | capabilities | 0.9 | `GET_ENTITY_CONTEXT` (exists), `FOLLOW` (OD-200-5; defined in `sealed-links.md`) |
| `items`, `targetSources` (batch input) | input fields | 0.9 | `targetSources` of `catalog.md` (same name, same meaning); `items` is also the field of a one-to-many `follow` result, a different object in a different tool |
| `limits`, `batchSize`, `summary`, `done`, `refused`, `deferred`, `results`, `index`, `outcome`, `code`, `deferredBy` | batch result fields | 0.9 | `RequestLimits` (Java, internal) and `dataprism.limits.*` (configuration): `limits` in a result is a different thing; `outcome` is also a class name in the orchestration module (`SourceOutcome`), never a result field there |
| `DONE`, `DEFERRED` | batch `outcome` values | 0.9 | `REFUSED` is reused from the per-source codes with a related meaning at the item level, under a different field name (`outcome` against `status`) so the two never share a code space |
| `DEADLINE`, `RESPONSE_CAP` | `deferredBy` values (batch: both; bulk: `DEADLINE`) | 0.9 | none |
| `BATCH_TOO_LARGE` | refusal code | 0.9 | `SOURCE_SELECTION_TOO_LARGE` (catalog; sources, not items) |
| `BATCH_DEFERRED` | audit `DENY:` code for an item not run | 0.9 | none |
| `dataprism.batch.default-size`, `.ceiling`, `.scope-sizes`, `.max-response-size`, `.deadline` | configuration keys | 0.9 | `dataprism.limits.max-response-size`, `per-source-timeout` (the batch keys are the whole-batch counterparts) |
| `INVALID_BATCH_SETTING` | startup refusal code | 0.9 | `INVALID_REQUEST_LIMITS` (sibling) |
| `dataprism.batch.items` | metric (closed label `outcome`) | 0.9 | none |
| `_truncated`, `path`, `kept`, `omitted`, `reason`, `next` | source result fields | 0.9 | `_linksOmitted`, `_links` (siblings; `reason` and `path` are also generic words used in the catalog result: `path` in `describe_source`, same meaning, a field path) |
| `RECORD_CAP`, `SIZE_CAP` | `_truncated.reason` values | 0.9 | `TOO_MANY_RECORDS`, `RESPONSE_TOO_LARGE` (refusal codes; these are reasons for truncation) |
| `_page` (`path`, `offset`, `kept`, `last`) | page result field | 0.9 | `page` of `list_sources` (a different tool and level; this one is under a source) |
| `dataprism.limits.max-records-per-source`, `dataprism.limits.max-source-response-size` | configuration keys | 0.9 | `max-records`, `max-response-size` (whole-call counterparts); refusals reuse `INVALID_REQUEST_LIMITS` |
| `links`, `group`, `exceptionStatuses` | `follow_bulk` input fields | 0.9 | `link` of `follow` (singular); `links` is also the `dataprism.links` configuration group, a different thing |
| `requested`, `targets`, `counts`, `exceptionsOmitted`, `invalidLinks`, `exceptions`, `deferredBy` | `follow_bulk` result fields | 0.9 | none; `counts` and `exceptionsOmitted` sit per target under `targets` |
| `index`, `item`, `status`, `target`, `link`, `expiresAt` | `follow_bulk` exception entry fields | 0.9 | `status` (the per-source status of `get_entity_context`, same codes), `target` and `expiresAt` (`_links` entry fields, same meaning), `item` (the position within the `items` of a one-to-many `follow`), `index` (as in the batch result) |
| `SCOPE_READ_BUDGET`, `DEFERRED` as per-target count keys | count keys | 0.9 | `SCOPE_READ_BUDGET` is an existing refusal code used as a key; `DEFERRED` as in the batch |
| `BULK_TOO_LARGE` | refusal code | 0.9 | `BATCH_TOO_LARGE` (sibling) |
| `BULK_DEFERRED` | audit `DENY:` code for a link not run | 0.9 | `BATCH_DEFERRED` (sibling) |
| `dataprism.bulk.max-links`, `.max-group-elements`, `.max-exception-links`, `.deadline` | configuration keys | 0.9 | none |
| `INVALID_BULK_SETTING` | startup refusal code | 0.9 | `INVALID_BATCH_SETTING` (sibling) |
| `dataprism.bulk.links` | metric (closed label `status`) | 0.9 | none |
