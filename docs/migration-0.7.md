---
title: Migrating to 0.7.0
description: What changes when upgrading from 0.6.x to 0.7.0, by audience, covering the new response shape, refusal codes, properties and a stop-all Hazelcast upgrade.
---

# Migrating from 0.6.x to 0.7.0

0.7.0 is a pre-1.0 release with a deliberate break in what `get_entity_context`
returns, one change that refuses responses 0.6.x let through, and a Hazelcast
upgrade that cannot be rolled. This page lists every change that can stop
something working, who has to act, and what to do. The shorter version is the
unreleased 0.7 section of the `CHANGELOG.md` at the repository root (the
[changelog](changelog.md) page shows it once the release is published).

## Who has to do what

| If you are... | Read | The short version |
|---|---|---|
| Writing an agent, or any client of `get_entity_context` or `compare_entity_sources` | [Agent and client authors](#agent-and-client-authors) | Read `sources[alias].status` and `sources[alias].entity.<field>`. The top-level `entity` is gone. Treat any status other than `ANSWERED` as "not answered". Handle four new refusal codes. |
| Running the server or the starter | [Operators](#operators) | Check that fields holding email-shaped values are classified. Expect two caps to be enforced. Do not run 0.6 and 0.7 Hazelcast members together. Old verifiers cannot read new logs. |
| Writing an adapter, a validator, an `ApprovalStore` or other extension | [Extension authors](#extension-authors) | A custom `LlmResponseValidator` receives a JSON array in its final pass. `ContextResponse` changed. Handle a new status. |

## Breaking changes at a glance

1. **The `get_entity_context` response shape.** Each `sources` entry is an
   object, and the top-level `entity` field is removed. Sources are no longer
   merged.
2. **A new per-source status, `REFUSED`.** A source whose record is refused is
   withheld and the other sources are returned. The call is refused only when
   every source that answered is refused.
3. **The audit disposition value `REFUSED:<code>`.** A log written by 0.7 cannot
   be read by a verifier older than 0.7. Logs written before 0.7 still verify
   with the new one.
4. **The custom `LlmResponseValidator` final pass receives a JSON array**, not
   an object. A validator that assumes an object root fails the call closed.
5. **`SensitivePatternValidator` is now always on** in the Spring starter, the
   packaged server and the configured-JSON-sources mode. A value shaped like an
   email address (or another detected identifier) in a `@NonSensitive` field is
   now refused with `VALIDATION_FAILED` / `SENSITIVE_PATTERN`. There is no
   opt-out property.
6. **The record and response-size caps are enforced.** `dataprism.limits.max-records`
   and `dataprism.limits.max-response-size` are new properties for limits that
   0.6.x carried in `RequestLimits` but never applied to a response. A response 0.6.x returned can now be refused with `TOO_MANY_RECORDS`
   or `RESPONSE_TOO_LARGE`.
7. **Property names are scanned.** A map keyed by an email address or other
   identifier shape is refused under every profile.
8. **Cluster refusals reach callers as their own codes.** `CLUSTER_LOCK_TIMEOUT`
   and `CLUSTER_OPERATION_REFUSED` replace `OVERSIGHT_UNAVAILABLE`,
   `REIDENTIFICATION_UNAVAILABLE` and `SCOPE_READ_BUDGET` where the cluster was
   the cause.
9. **Hazelcast approval values changed and cannot be mixed.** Stop every member
   and start 0.7. There is no rolling upgrade.
   (A hand-wired `PrivacyCluster.embedded(ClusterMembership, boolean)` now
   applies the default approval quorum; see
   [Extension authors](#extension-authors).)
10. **A Hazelcast `tcp-ip` cluster now defaults to a majority quorum for
    approvals.** In a two-member cluster, losing either member stops
    approval-gated tools.

What did **not** change: no `dataprism.*` property was renamed or removed, the
audit record format and hash encoding, the tool names, and the package layout
from 0.6.0.

## Agent and client authors

### The response shape

Each source is now returned on its own, under its alias, with a status. Two
sources that use the same field name for different values are both returned;
0.6.x overwrote one with the other in a single merged `entity`.

Before, from `get-entity-context.structured.json` at 0.6.x (shortened):

```json
{
  "entityType": "CUSTOMER",
  "subject": "SUBJ-RVCTG7ZA",
  "sources": {
    "ORGANISATION_IDENTITY-9SAP0WPN": "ANSWERED",
    "ORGANISATION_IDENTITY-J8DXZTGX": "ANSWERED",
    "ORGANISATION_IDENTITY-PXT33N02": "ANSWERED"
  },
  "findings": [ "..." ],
  "entity": {
    "customerName": "Devon Doherty (8QDSD50W)",
    "email": "[REDACTED]",
    "status": "ACTIVE",
    "accountId": "ACC-1",
    "holderName": "Devon Doherty (8QDSD50W)",
    "balance": "[REDACTED]"
  }
}
```

After, from the same file at 0.7.0 (shortened):

```json
{
  "entityType": "CUSTOMER",
  "subject": "SUBJ-RVCTG7ZA",
  "sources": {
    "ORGANISATION_IDENTITY-9SAP0WPN": {
      "status": "ANSWERED",
      "entity": { "orderId": "ORD-9", "...": "..." }
    },
    "ORGANISATION_IDENTITY-J8DXZTGX": {
      "status": "ANSWERED",
      "entity": {
        "customerName": "Devon Doherty (8QDSD50W)",
        "email": "[REDACTED]",
        "status": "ACTIVE"
      }
    },
    "ORGANISATION_IDENTITY-PXT33N02": {
      "status": "ANSWERED",
      "entity": {
        "accountId": "ACC-1",
        "holderName": "Devon Doherty (8QDSD50W)",
        "balance": "[REDACTED]"
      }
    }
  },
  "findings": [ "..." ]
}
```

Read a status as `sources[alias].status` and a field as
`sources[alias].entity.<field>`. `entity` is present only when the status is
`ANSWERED`. The aliases are per-scope and not the source names, as before.

### Refused sources and the status contract

`status` is one of seven stable codes: `ANSWERED`, `NO_DATA`, `TIMED_OUT`,
`FAILED`, `CIRCUIT_OPEN`, `SKIPPED_OVER_LIMIT` and the new `REFUSED`. The full
table is in [the tool reference](tools.md#get_entity_context).

**An unknown status means "not answered".** Later releases may add codes. Read
a code you do not recognise as a gap in the data, not as an error, and never
read an `entity` that is not there.

`REFUSED` means the source answered and scrubbing or validating its record
refused, for example an unclassified field under `FAIL_REQUEST`. That source is
withheld whole: no field, no partial record, no consistency finding. The other
sources are returned as normal. A response with a `REFUSED` source reads like
one in which that source had no data, apart from the status string. If every
source that answered is refused, the whole call is refused with the code of the
first source whose scrub refused, or failing that the first whose validation
refused. A final check over the assembled response can still refuse the whole
call with `VALIDATION_FAILED`.

Be aware that the status itself tells the model, and so the user, that a source
refused. It carries no path, field name or value.

`compare_entity_sources` takes each `identity` field from the first `ANSWERED`
source, in ascending key order, that holds it. A refused source has no entity.

### New refusal codes and what to do

| Code | Meaning | What to do |
|---|---|---|
| `TOO_MANY_RECORDS` | The response would carry more than `dataprism.limits.max-records` records (default 500). The whole call is refused. | Narrow the request. An operator can raise the limit if the larger answer is intended. |
| `RESPONSE_TOO_LARGE` | The compact response body would be larger than `dataprism.limits.max-response-size` (default 512KB). The whole call is refused. | Narrow the request. An operator can raise the limit. |
| `CLUSTER_LOCK_TIMEOUT` | A cluster lock the call needs was held for longer than `dataprism.hazelcast.lock-timeout` (default two seconds). The failed lock wait changes nothing, but earlier steps of the same call are not undone: a rate-limit allowance is already used, and an approval consumed by admission is not restored. | Retry. After a refused read of an approval-gated tool, request a fresh approval first. |
| `CLUSTER_OPERATION_REFUSED` | The cluster rejected an operation the call needs, such as a lock or its lease. The failed step changes nothing, but earlier steps are not undone, and a failed approval creation may leave a pending approval or count until it expires. | Retry later. Tell an operator if it persists. See [what a cluster refusal leaves behind](multiple-instances.md#what-a-cluster-refusal-leaves-behind) before you retry an approval or re-identification call. |

`CLUSTER_LOCK_TIMEOUT` and `CLUSTER_OPERATION_REFUSED` also now reach
re-identification callers (request, approve and collect) as their refusal
code, where 0.6.x answered `REIDENTIFICATION_UNAVAILABLE`. A read that the
shared read budget could not lock reports one of them instead of
`SCOPE_READ_BUDGET`; any other budget failure still refuses the read as
`SCOPE_READ_BUDGET`.

Where the validation violation matters, `VALIDATION_FAILED` with the
sub-code `SENSITIVE_PATTERN` now also appears for a sensitive-shaped value in a
field the deployment marked `@NonSensitive`.

## Operators

### New properties, unchanged unless you set them

Nothing was renamed or removed. Every default below keeps the behaviour of
0.6.x, except where the next section says a cap is now enforced.

| Property | Default | What it does |
|---|---|---|
| `dataprism.limits.max-sources` | `8` | Most sources one request may call. |
| `dataprism.limits.max-records` | `500` | Most records one request may return. Now enforced. |
| `dataprism.limits.max-response-size` | `512KB` | Largest compact body returned to the client. Now enforced. It is not a per-source limit. |
| `dataprism.limits.per-source-timeout` | `3s` | Time allowed to the sources of one request, measured from the start of the fan-out. |
| `dataprism.limits.max-concurrency` | `4` | Most downstream calls in flight at once across one request. |
| `dataprism.limits.scope-read-budget` | `100` | Reads of one subject within one scope. |
| `dataprism.hazelcast.lock-timeout` | `PT2S` | Bound on every cluster lock wait, `PT100MS` to `PT30S`. |
| `dataprism.hazelcast.approval-minimum-members` | unset | Members that must be reachable for approvals to be written. |
| `dataprism.hazelcast.approval-sweep-interval` | `PT5M` | How often each member sweeps pending approvals and approval-id claims, `PT1S` to `P1D`. |

An application that supplies its own `RequestLimits` bean is now refused at
startup with `FORBIDDEN_PRIVACY_OVERRIDE`. Set `dataprism.limits.*` instead.
Startup is refused with `INVALID_REQUEST_LIMITS` for a zero or negative limit or
a response size that does not fit in an `int`. See
[`dataprism.limits`](configuration.md#dataprismlimits) and
[`dataprism.hazelcast`](configuration.md#dataprismhazelcast).

### Caps are now enforced

In 0.6.x `RequestLimits` carried `max-records` and `max-response-size`, but
nothing applied them to a response and they could not be set from configuration.
Both are applied in 0.7.0, after
validation and before the call is audited as allowed. Equality is allowed. The
size cap covers the compact context body, including source statuses and
findings, and for `compare_entity_sources` the comparison projection too. It
does not cover the MCP envelope. A response that is over either cap refuses the
whole call and writes one `DENY:TOO_MANY_RECORDS` or `DENY:RESPONSE_TOO_LARGE`
audit event with the correlation id. If a legitimate larger answer is now
refused, raise the property. Text full of unpaired surrogates may be refused a
little before its exact encoded size reaches the cap.

### Sensitive-shaped values in `@NonSensitive` fields are refused

In 0.6.x only the raw-value check was registered in the starter, the packaged
server and the configured-JSON-sources mode, so the shape scan ran only in
hand-assembled orchestrators. From 0.7.0 the scan runs everywhere, and it reads
property names as well as values. A response with an email-shaped value (or an
IBAN, card number, PPSN, Social Security number, phone number, JWT or API-key shape) in a field
marked `@NonSensitive` is refused with `VALIDATION_FAILED` / `SENSITIVE_PATTERN`.
That source is withheld. If you relied on the gap, classify the field correctly,
for example as sensitive so it is redacted, rather than `@NonSensitive`. There
is no property to turn the scan off. After upgrading, search the audit for
`DENY:VALIDATION_FAILED` and `REFUSED:VALIDATION_FAILED` to find fields that
need classifying. A map keyed by an email address is refused even under a
Java-built `PASS_THROUGH_UNSAFE` profile; a flagged name is reported as
`<undeclared>`.

### Audit logs: new disposition, one-way compatibility

A refused source is recorded as `REFUSED:<code>` under the fixed key
`<source>:<refused>`, and is listed as `<source>:REFUSED` among the source
systems. Before, the value was a plain `REFUSED`. A reader or verifier older
than 0.7 rejects such a record, so **logs written by 0.7 cannot be read by
earlier releases**. Logs written before 0.7 still read and verify with the new
verifier, including a log that mixes both. Upgrade every verifier and every
reader of the audit trail before, or together with, the servers. The record
fields and the hash encoding are unchanged. See [the audit chain](audit.md).

### Metrics

- `dataprism.privacy.source.withheld` counts each source withheld from a
  response that was returned. `dataprism.privacy.failclosed` counts refused
  calls only.
- `dataprism.approval.release.skipped` counts pending-slot or id-claim releases
  that were skipped after a commit or delete because a lock timed out or a call
  failed. The over-count is cleaned by the sweep.

### Hazelcast: stop every member, then start 0.7

Do not roll. Approval entries written by 0.6.x are unreadable by 0.7.0 and are
refused: a creation token was added to the entry, and 0.7.0 accepts only the new
form. The maps are in memory only. Stop every member, start 0.7.0, and
re-request any approval that was pending. 0.6.x had no id-claim or pending maps,
so there is nothing to convert there. A mixed cluster is not supported: an old
member does not know the claims or tokens, so an approval id can be created
twice. Two maps were added, `dataprism.approval.id` and
`dataprism.approval.pending`.

Other changes you will see:

- Consuming an approval is now a compare-and-set, so it cannot happen twice
  across members. After a split cluster heals, an approval that either side
  consumed, rejected or expired stays so.
- A call that waits for a cluster lock now refuses after two seconds by
  default with `CLUSTER_LOCK_TIMEOUT` instead of waiting. Every lock is leased
  for five times `lock-timeout`, so none is held for ever. A cluster that
  rejects an operation, for example because `hazelcast.lock.max.lease.time.seconds`
  is below that lease, now refuses with `CLUSTER_OPERATION_REFUSED`, and the log
  carries one WARN line with the code and the exception class name only.
- With `join.mode: tcp-ip`, `approval-minimum-members` defaults to a majority of
  the distinct `join.members`, so that list must name every member including
  this one, and **a two-member cluster stops approval-gated tools while either
  member is down** (fail closed). Set `approval-minimum-members: 1` to opt out,
  which applies no protection. With the minimum at 1, an approval id created in
  two scopes on either side of a split shares one slot after the heal, and both
  entries are then refused as ambiguous. A protected member refuses approval
  writes for about five seconds after it starts.
- Startup refusals. Some apply only when approvals are in use
  (`dataprism.oversight.approval-required-tools` is not empty, or
  `dataprism.reidentification.enabled` is true): a `tcp-ip` hostname member or
  `kubernetes` without `approval-minimum-members` is refused with
  `MISSING_APPROVAL_QUORUM`, and a wildcard, other-octet range or bad-port `tcp-ip`
  member with `INVALID_APPROVAL_QUORUM`. Others apply always: an explicit
  `approval-minimum-members` below 1, set with `join.mode: none`, or above the
  counted members is refused with `INVALID_APPROVAL_QUORUM`, and a `lock-timeout`
  outside `PT100MS` to `PT30S` or a sweep interval outside `PT1S` to `P1D` is
  refused with `INVALID_LOCK_TIMEOUT` or `INVALID_APPROVAL_SWEEP_INTERVAL`.
  `CLUSTER_SETTINGS_IGNORED` refuses `lock-timeout`, `approval-minimum-members`
  and the sweep interval under `single-node`, and `approval-minimum-members` and
  the sweep interval beside an application-supplied `PrivacyCluster`.
- The operator API answers `503` with `{"code":"CLUSTER_LOCK_TIMEOUT"}` or
  `{"code":"CLUSTER_OPERATION_REFUSED"}` where it answered
  `503 OVERSIGHT_UNAVAILABLE` (approve and reject; pause, resume and the state and
  approval reads can return them only with a custom `OversightState` or `ApprovalStore`) and `REIDENTIFICATION_UNAVAILABLE` (the re-identification
  endpoints). The denial is audited with that code. Any other failure keeps the
  old code. Update alerts and runbooks that match on `OVERSIGHT_UNAVAILABLE`.
- Approving or rejecting no longer waits for ever, or reports failure after it
  committed, when the requester's pending entry is locked. If the response to a
  decision is lost, `approve` or `reject` can throw although the change happened;
  read the status again before treating it as failed.

See [Running more than one instance](multiple-instances.md).

## Extension authors

- **`ContextResponse`.** `sources()` is now `Map<String, SourceResult>`, where
  `SourceResult` is `{status, entity}`. `ContextResponse.entity()` and the
  constructors that took it are removed, with no forwarding shim.
- **`SourceOutcome.Status`** gained `REFUSED`. Switches over it need a case, and
  code that reads a status must treat an unknown one as "not answered".
- **`LlmResponseValidator`.** The final pass now receives one JSON array
  holding the returned sources' trees, in response order, with the union of the
  prohibited and emitted sets. A validator that assumes an object root throws,
  and the call fails closed (`DENY:REQUEST_FAILED`). Handle an array root and
  walk it. Per-source calls still pass one object. A path in the final pass
  starts `$[n]`, and the index is removed before the path reaches a refusal.
  No alias or source name is ever a property key a validator sees.
- **Validators can no longer assume the scan is optional.** The framework's own
  `RawValueLeakValidator` and `SensitivePatternValidator` now both scan property
  names. In a Spring application `SensitivePatternValidator` is the
  `PRIVACY_CRITICAL` bean `dataPrismSensitivePatternValidator`, and a competing
  bean is refused.
- **`RequestLimits`.** The bean is `dataPrismRequestLimits`, bound from
  `dataprism.limits.*` and classified `PRIVACY_CRITICAL`; supply limits through
  properties, not a bean. `data-prism-connectors-rest` used without the
  autoconfigure module falls back to the framework defaults, and refuses startup
  with `INVALID_REQUEST_LIMITS` if any `dataprism.limits.*` property is set with no
  bean to take it.
- **`AuditEvent`.** Disposition values also accept `REFUSED:` followed by a code
  matching `[A-Z][A-Z0-9_]{0,63}`.
- **Cluster code.** If you implement `ApprovalStore`, `CallerRateLimiter` or
  `ScopeBudget` over your own cluster, throw `ClusterLockTimeoutException` or
  `ClusterOperationRefusedException` (both in `core.refusal`) to have the code
  reach the caller. `ClusterOperationRefusedException` was never released under
  the old `hazelcast` package name, so there is no alias.
- **`PrivacyCluster`.** `embedded(...)` and `using(...)` gained overloads that
  take the approval minimum and sweep-interval, and the store constructors take a
  lock timeout. Every 0.6 overload remains, so call sites compile unchanged. **But
  `embedded(ClusterMembership, boolean)` now applies the default approval quorum
  whether or not approvals are in use.** A hand-wired `tcp-ip` membership of
  hostnames that started on 0.6 now throws `PrivacyClusterRefusal`
  `MISSING_APPROVAL_QUORUM`, a wildcard entry gets `INVALID_APPROVAL_QUORUM`, and
  an IP-only list now gets a majority write quorum on the approval map. Pass an
  explicit minimum through the overload that takes one (`1` opts out). A
  `PrivacyCluster.using(instance)` you supply gets neither the merge policy nor
  the quorum.

## Verifying a release

From 0.7.0 the release pipeline signs every pushed image digest keylessly with
cosign, attaches GitHub build-provenance attestations to images, jars and
release assets, and attaches a CycloneDX SBOM to every module published to
Maven Central and to the GitHub Release. Images also carry BuildKit SBOM and
provenance attestations. How to check each is in
[Verifying a release](verifying-releases.md).

## Related

- [`get_entity_context` and its status codes](tools.md#get_entity_context)
- [Configuration reference](configuration.md)
- [The audit chain](audit.md)
- [Running more than one instance](multiple-instances.md)
- [Migrating to 0.6.0](migration-0.6.md)
