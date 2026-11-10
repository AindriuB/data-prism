---
title: Operating Data Prism across many sources
description: A draft guide to running Data Prism against many sources, with links on or several instances, covering sizing, deadlines and what to watch.
---

# Operating Data Prism across many sources

!!! warning "Draft"
    This is a first, draft operations guide, written for 0.8. It says what to size, review and watch when one
    deployment reaches many sources, enables links, or runs more than one instance. It is not complete: the
    batch and bulk tools, and the key-rotation procedures, arrive with later releases and pages, and this page will
    change as they do. Where it points to another page for the rule, that page is the rule.

## Running more than one instance

Data Prism 1.0 supports more than one instance on a **trusted network only**. A trusted network is one where only
Data Prism members can reach the member port. You assert that with `dataprism.hazelcast.trusted-network: true`;
a member that joins others and does not make the assertion refuses to start (`MISSING_TRUSTED_NETWORK`), and so
does one that listens beyond the private blocks and the ranges you trust (`PUBLIC_MEMBER_INTERFACE`). Approvals and
counters on the member port are not sealed, so anyone else who can reach it can read and forge them. A private
address is not a trusted network. Clusters across an untrusted network are not supported in 1.0.

Three things to know before you add a second instance:

- **Links and page links are refused on a joining member.** The count of failed link attempts must be shared by every
  member, or a caller could try the limit again on each one. No cluster-wide limiter ships in 1.0, so a member that
  joins others, with links enabled or a link key set, refuses to start with `MISSING_SHARED_LINK_LIMITER` unless the
  application supplies a limiter declared as `ClusterWideLinkAttemptLimiter`. Until then, run links on a single
  instance.
- **Readiness carries `clusterSecurity`.** With Spring Boot Actuator present, the `clusterSecurity` contributor is
  part of the readiness group, not liveness. It goes `DOWN`, with a `reason`, the member count and the unverified
  count, while this instance is not serving because the cluster could not be shown to be a Data Prism cluster, so a
  refusing instance leaves rotation. A restart does not repair a hostile or mismatched cluster, which is why it
  is not in liveness.
- **What is and is not sealed** is on [Running multiple instances](multiple-instances.md), and is not repeated here.
  Read it before you decide which state you rely on.

## Sizing the read budget

Every source a call asks costs one read, charged before any source is called, against two counters: one per subject
(`dataprism.limits.scope-read-budget`, default 100) and one per privacy scope across all subjects
(`dataprism.limits.scope-total-read-budget`, default 50,000). A call that does not fit is refused with
`SCOPE_READ_BUDGET` and calls nothing. A `follow` that translates a key costs
`dataprism.key-resolvers.budget-cost` more per resolver call.

The scope total must be at least the number of subjects an investigation will ask about, times the sources each
call asks. An investigation over 5,000 subjects against 8 sources needs 40,000 reads, which fits the default. The
same 5,000 subjects against a hundred-source estate also needs 40,000 reads if every call targets 8 sources, and
four times that if it does not. The per-subject budget bounds how often one subject is re-read: size it to the number
of calls, times the sources per call, that you want to allow for one subject in one scope.

With `dataprism.hazelcast.topology: single-node` the counts are per process. With more instances they are shared.

Keep the estate readable by an agent. A call without `targetSources` is refused with `SOURCE_SELECTION_REQUIRED` when
more permitted sources serve the entity type than `dataprism.catalog.unselected-source-limit` (default 8), and a
selection over `dataprism.limits.max-sources` is refused with `SOURCE_SELECTION_TOO_LARGE`. Neither is silent. For a
large estate, give each source an honest `entity-types` list, so that an agent can narrow it with `list_sources`.

## The request deadline and the per-source timeout

- `dataprism.limits.per-source-timeout` (default `3s`) is the time one source's own call may take, measured from the
  moment that call starts.
- `dataprism.limits.request-timeout` (default `PT10S`) bounds the whole request from its start. A source still
  waiting for a concurrency permit when it passes is never called and reads `TIMED_OUT`. It may not be shorter than
  `per-source-timeout`, so raising one above `PT10S` means raising the other.
- `dataprism.limits.max-concurrency` (default 4) is how many downstream calls one request has in flight.
- Each source can have at most 32 calls in flight across all requests, and the server 128. A hung source holds its
  slots until its calls return, and four hung sources fill the pool, after which every source reads `FAILED` without
  being called. Watch `dataprism.source.calls_saturated`.
- An adapter that blocks on a plain socket is not stopped by an interrupt. Give its client a read timeout; the
  default `RestClient` is not affected.
- Adapters run on platform threads named `dataprism-source-<n>`, and nothing in a `ThreadLocal` carries into them.
- The audit sink writes on its own thread and the caller waits for it uninterruptibly, so a hung audit disk holds the
  request that is writing to it. That fails closed; it is not bounded by the request deadline, so monitor the audit
  storage.

## Reviewing source descriptions

The text of a source's description is shown to the model by `list_sources`. Startup applies six rules and refuses
what it can name: a URL, a host name, an address, a key, a source name in the alias text. That check is a guard rail.
It cannot tell that a sentence reveals a system in words no rule matches, so **the operator is the reviewer**.

Before you ship a description, read it as a person who has never seen your estate and ask:

- does it say what the data is, not which product, team or network holds it?
- would it let the model tell two sources apart without learning how they are connected?
- does it avoid names of people, customers and incidents?

Startup logs one INFO line giving how many sources use the generated text `A data source.` and which they are, by
real name; treat that number as a to-do list, because an agent cannot choose between sources that all read the same.
It also logs one WARN line giving how many source names do not follow `[a-z][a-z0-9-]{0,63}`: from 0.9 those are
refused.

## Enabling links

Links are off by default (`dataprism.links.enabled: false`). Switching them on lets an agent follow one source's
identifier into another source, one audited, budgeted call at a time. Before you do, check each of these:

1. **Instances.** If you run more than one, see the first section: links are refused on a joining member in 1.0.
2. **Keys.** Set `dataprism.links.key-id` and give it a key, 32 bytes at least, that is not the pseudonymisation key
   and not the cluster key (`LINK_KEY_SHARED` refuses a shared one). A page link of `list_sources` also needs the
   key once the estate is larger than the page size (`MISSING_PAGING_KEY`).
3. **Capabilities.** `follow` needs the `FOLLOW` capability in a role, and `list_sources` and `describe_source` need
   theirs. Adding a tool grants nothing. If an approval-gated tool can issue a link, `follow` must be in
   `dataprism.oversight.approval-required-tools` too (`FOLLOW_NOT_APPROVAL_GATED`).
4. **What can be followed.** Review which fields are declared chainable, and which `KeyResolver` beans exist. A link
   reaches only a source that declares the namespace as an input key, but every source it reaches is read with the
   caller's own permissions and budget.
5. **Lifetime and limits.** Choose `dataprism.links.lifetime`, `max-per-response`, `invalid-attempt-limit` and
   `invalid-attempt-window` for your agents, and check `dataprism.paging.lifetime` is not longer than the link
   lifetime.
6. **Audit and alerts.** Know where `REFUSED:LINK_*` appears in the audit ([the disposition keys](audit.md#disposition-keys-added-in-08)),
   and what you will do when the failed-attempt limit trips for a caller.
7. **Budget.** Add the reads that following costs to the sizing above.

## Retiring a link key

A link key that is no longer sealing is still held for verification until you remove it. When you retire one, remove
it from `dataprism.links.keys` **and destroy it in the key store** (the secret, the environment variable, the
provider entry). Removing it from memory only is not retirement: any copy that survives in a store, a backup or a
deployment file still verifies, and can still be used to forge a link that names the retired key id. A key id that
this member no longer holds is refused as `LINK_UNKNOWN_KEY` and counted by the failed-attempt limiter. The rotation
procedure, in two phases so that a rolling restart never produces a link that a member cannot verify, is written
up with the 1.0 controls in the key-rotation guide, which this page will link when it is published; do not retire a
key by improvisation in the meantime.

## What to watch

Health, with Actuator present: `auditIntegrity` (the audit chain and its retention), `linkKeys` (the key ids this
member holds for verification, never a reference or a key byte), and, with `topology: embedded`, `clusterSecurity` in
readiness.

Metrics worth a dashboard and an alert:

| Metric | Why |
|---|---|
| `dataprism.mcp.denied`, `dataprism.audit.denial_unavailable` | rising denials, and denials whose audit write failed |
| `dataprism.source.latency`, `dataprism.source.errors`, `dataprism.source.calls_saturated` | slow and failing sources, and the call slots running out |
| `dataprism.privacy.source.withheld`, `dataprism.privacy.failclosed` | a source's output refused by the scrubber or a validator |
| `dataprism.links.issued`, `dataprism.links.omitted`, `dataprism.links.keys.loaded` | links in use, links lost to the cap, and how many keys the member holds |
| `dataprism.resolver.unavailable`, `.timeout`, `.rejected`, `.too_many`, `.invalid_output`, `.latency` | key resolvers failing or slow |
| `dataprism.paging.pages` | `list_sources` page use |
| `dataprism.catalog.descriptions.generated`, `dataprism.catalog.source_names.nonconforming` | descriptions still on the generated text, and names that will be refused from 0.9 |
| `dataprism.cluster.seal.tamper`, `dataprism.cluster.members.unverified`, `dataprism.cluster.lock.timeout` | a hostile or mismatched cluster, and lock contention |
| `dataprism.audit.retention.unverified`, `.anchor_failed`, `.delete_failed`, `.failed` | the retention purge stopping |

Link ids, attempt counts and resolver timings are metrics and never audit fields. To see who failed a link and why,
read the `link` key in the audit.
