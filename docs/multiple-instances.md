---
title: Running multiple Data Prism instances
description: Which state Data Prism shares across instances, how to join them into one embedded cluster, and the network isolation you must supply.
---

# Running multiple instances

One Data Prism instance needs no cluster. When you run several behind a load
balancer, some state is only correct if it is shared. This page says which, how
to join the instances, and what Data Prism does not do for you.

!!! warning "Member traffic is not encrypted or authenticated"
    Hazelcast member traffic is **not** encrypted and **not** authenticated in
    the open-source distribution Data Prism uses (Hazelcast 5.7.0). Member TLS
    and member authentication are Enterprise features. Data Prism refuses to
    start with TLS settings it cannot honour. Anyone who can reach the member
    port can read and write cluster state, including forging an approval,
    lifting a pause and resetting read budgets and rate limits. Isolating the
    member port is the deployer's job, covered in [Network isolation](#network-isolation-is-your-responsibility).

## When you need it

`dataprism.hazelcast.topology` is `single-node` or `embedded`, never a default.
With `single-node` each process keeps its own state. With N instances that means:

- The per-subject read budget is enforced per process, so a configured budget
  of 100 allows up to N times 100 reads.
- A pause on one instance does not stop the others. A paused tool, scope or
  deployment keeps being served by every instance you did not pause.
- An approval is usable only on the instance where it was granted. The retry
  has to reach the same instance.
- The caller rate limit counts per instance, so a caller's effective limit is N
  times larger.
- The re-identification index is not available. `dataprism.reidentification.enabled`
  requires `topology: embedded` (`REIDENTIFICATION_REQUIRES_CLUSTER`).

Each of these fails closed in the sense that no raw value is released. They
weaken the controls, not the pseudonymisation. The identity cache is the
exception: a synthetic value is a pure function of scope, subject, namespace and
key, so each instance computes the same answer without sharing.

With `topology: embedded` and a join mode, each instance runs an embedded
Hazelcast member and the members that have joined one cluster share the state
below. An instance that has not joined, for example because it was given a
different cluster name or cannot reach the others, keeps its own state.

## Shared state

Backup count is 1 for every map: each entry lives on an owner member and one
backup. Maps are not written to disk and have no `MapStore`.

| Map | Holds | Keys hold raw subject ids | Backups |
|---|---|---|---|
| `dataprism.identity` | Cached synthetic values. Recomputable, so losing it costs CPU only | Yes | 1 |
| `dataprism.reidentification` | Pseudonym to subject, only with re-identification enabled. A store, not a cache | Yes | 1 |
| `dataprism.budget` | Per-subject read counts | Yes | 1 |
| `dataprism.oversight` | Pause flags: global, per tool, per scope. No eviction | No | 1 |
| `dataprism.approval` | Approval requests, each until its expiry, each with the random token of the creation that wrote it | No | 1 |
| `dataprism.approval.id` | One claim per approval id, which keeps an id unique across scopes. It names the approval entry and the token of the creation that made it. A claim has no TTL and is valid while the approval entry it names exists; a swept or taken-over claim goes once that entry is gone. No eviction | No | 1 |
| `dataprism.approval.pending` | Per (kind, requester): the ids of their pending approvals with their expiries and the owner of each (the scope and the token of the creation that reserved the slot), which the pending cap counts. Changed only by an entry processor. No TTL and no eviction; a periodic sweep removes ids long past their expiry | No | 1 |
| `dataprism.callerrate` | Per-caller fixed-window request counts. No eviction | No | 1 |

The other maps evict least-recently-used entries beyond 100,000 per member.
`dataprism.oversight` and `dataprism.callerrate` are never evicted, because an
evicted pause flag reopens a paused path and an evicted counter resets a
caller's window. `dataprism.approval.id` and `dataprism.approval.pending` are
never evicted either, because an evicted claim lets an id be used twice and an
evicted pending set lets a requester pass the cap.

Anyone who can reach the member port can read what is in these maps, including
the raw subject ids in the keys of the first three.

## Configuration

These properties apply with `dataprism.hazelcast.topology: embedded`. With
`single-node` they are refused (`CLUSTER_SETTINGS_IGNORED`). All but
`lock-timeout` are also refused when the application supplies its own
`PrivacyCluster` bean, because they would have no effect; `lock-timeout` still
applies, because the stores are built over that bean. Relaxed binding drops hyphens in environment variables:
`dataprism.hazelcast.cluster-name` is `DATAPRISM_HAZELCAST_CLUSTERNAME`.

| Property | Meaning |
|---|---|
| `dataprism.hazelcast.cluster-name` | Required. A label, not a secret. Never `dev` |
| `dataprism.hazelcast.join.mode` | Required: `tcp-ip`, `kubernetes` or `none` |
| `dataprism.hazelcast.join.members` | For `tcp-ip` only. At least one `host` or `host:port` |
| `dataprism.hazelcast.join.kubernetes.namespace` | For `kubernetes` only. Required |
| `dataprism.hazelcast.join.kubernetes.service-name` | For `kubernetes` only. API mode. Exactly one of this and `service-dns` |
| `dataprism.hazelcast.join.kubernetes.service-dns` | For `kubernetes` only. DNS mode. Exactly one of this and `service-name` |
| `dataprism.hazelcast.member.port` | Optional. Default 5701, never auto-incremented |
| `dataprism.hazelcast.member.interface` | Optional. An IPv4 literal or wildcard such as `10.0.*.*` |
| `dataprism.hazelcast.lock-timeout` | Optional. Default `PT2S`, from `PT100MS` to `PT30S`. How long a call waits for a cluster lock before it is refused. See [Failure behaviour](#failure-behaviour) |
| `dataprism.hazelcast.approval-minimum-members` | Optional integer, at least 1. The fewest members that must see each other for approvals to be created, decided or consumed. `tcp-ip`: unset means a majority of the distinct `join.members` entries, so the list must name every member, this one included. Members are counted after expanding entries separated by commas, semicolons or spaces and last-octet ranges such as `10.0.0.1-3`; an entry without a port is the configured `member.port`, so `10.0.0.1` and `10.0.0.1:5701` are one member only when `member.port` is 5701. `none`: 1, and the key is refused. The checks below apply only when approvals are in use, that is `dataprism.oversight.approval-required-tools` is not empty or `dataprism.reidentification.enabled` is true; otherwise no quorum is computed or required. A form that cannot be counted reliably (a wildcard, a range in another octet) is then refused with `INVALID_APPROVAL_QUORUM`. If any entry is a hostname, the count is unknown, because Hazelcast resolves a hostname to every address it has, so you must set the minimum (`MISSING_APPROVAL_QUORUM` otherwise). `kubernetes`: required when approvals are in use. Application code that builds its own `ToolAdmission` or `ReidentificationService` over the starter's `ApprovalStore`, without setting the starter properties that put approvals in use, should set `approval-minimum-members` explicitly: the starter applies no protection otherwise. See [Failure behaviour](#failure-behaviour) |

`join.mode: none` is the explicit single-member choice: the member binds
`127.0.0.1` only and neither discovers nor accepts other members. It refuses
`member.interface`. Internally the member runs under your `cluster-name` plus a
a new random suffix each time the member starts, so no other process can match
it. That suffix is internal and appears in logs. A `none` member refuses both
members and clients.

Without `member.interface`, a `tcp-ip` or `kubernetes` member binds **every**
network interface of the host, which is Hazelcast's default. Set the interface
to the address on your private member network so the member does not listen
anywhere else.

Auto-detection, multicast and phone-home are always off, so a member only ever
joins what you named.

### Refusals

Each refusal stops startup before a member starts, with one exception: for
`PrivacyCluster.using(instance)` the instance is already running, and a refusal
leaves it running. The code is on the configuration failure.

| Code | Condition |
|---|---|
| `MISSING_CLUSTER_NAME` | `cluster-name` is absent or blank with `embedded` |
| `RESERVED_CLUSTER_NAME` | `cluster-name` is `dev`, Hazelcast's default, in any case |
| `MISSING_CLUSTER_JOIN` | `join.mode` is absent with `embedded` |
| `UNSUPPORTED_CLUSTER_JOIN` | `join.mode` is not `tcp-ip`, `kubernetes` or `none` |
| `INVALID_CLUSTER_MEMBERS` | `tcp-ip` with no members, or a blank member, no host or a port outside 1-65535. Also **`join.members` set with `kubernetes` or `none`** |
| `INVALID_KUBERNETES_JOIN` | `kubernetes` without a namespace, or with both or neither of `service-name` and `service-dns`. Also **`join.kubernetes.*` set with `tcp-ip` or `none`** |
| `INVALID_CLUSTER_PORT` | `member.port` outside 1-65535 |
| `INVALID_CLUSTER_INTERFACE` | `member.interface` is not an IPv4 literal or wildcard pattern, or is set with `none` |
| `CLUSTER_PORT_SHARED` | `member.port` equals `server.port`, `management.server.port` or an enabled `dataprism.operator.port` |
| `CLUSTER_SETTINGS_IGNORED` | Cluster settings with `single-node`, or with an application-supplied `PrivacyCluster` |
| `INVALID_LOCK_TIMEOUT` | `lock-timeout` below `PT100MS`, above `PT30S`, zero or negative |
| `INVALID_APPROVAL_QUORUM` | `approval-minimum-members` below 1, above the number of distinct `join.members` entries with `tcp-ip`, or set at all with `none`. Also, when approvals are in use, a `tcp-ip` entry that cannot be counted (wildcard, a range outside the last octet) |
| `MISSING_APPROVAL_QUORUM` | Approvals in use (`dataprism.oversight.approval-required-tools` set or `dataprism.reidentification.enabled`) and either `tcp-ip` with a hostname among `join.members`, or `kubernetes`, with no `approval-minimum-members`: the member count is not known to the starter, so you must give it |
| `HAZELCAST_TLS_UNSUPPORTED` | `tls-key-reference` or `tls-trust-reference` set, or a supplied Hazelcast `Config` that enables TLS or Hazelcast security |
| `UNSAFE_HAZELCAST_DISCOVERY` | An enabled advanced network config, in a supplied `Config` or instance. Auto-detection or multicast enabled on a supplied instance passed to `using()`. A supplied `Config` given to `PrivacyCluster.embedded(Config)` has auto-detection and multicast forced off, not refused |

The two bold rows are the cross-mode refusals: a setting for one join mode
never silently does nothing under another.

## Ports

| Port | Use |
|---|---|
| 8080 | The MCP endpoint (`server.port`) |
| 5701 | Hazelcast member traffic (`dataprism.hazelcast.member.port`). Never publish it |
| `dataprism.operator.port` | The operator surface, when enabled. There is no fixed default |

The member port must differ from the other three. The image declares `EXPOSE
5701` so tooling can see it; that is documentation, not publication. Do not run
`docker run -P` or `docker run --publish-all`, which publish every exposed port,
including 5701, on random host ports. Publish 8080 explicitly with `-p`.

## Docker Compose

The example in `docker/multi-instance/` runs two servers. Each lists both
members by fixed address on a private `cluster` network and binds only that
subnet, so a member does not listen on the `default` network. It publishes 8080
for one server on loopback and does not publish 5701. It needs Docker Compose
2.24.4 or later.

!!! warning "A Compose network is not a security boundary"
    Whether a container on another Docker network can route to the `cluster`
    addresses depends on the engine. OrbStack was observed to route across
    Docker networks: a container on the `default` network only could not use
    the members' `default` addresses but did reach the `cluster` addresses on
    port 5701. Docker Desktop may behave the same (not tested). Linux Docker's
    network isolation is expected to block it (not tested here). Treat the example as a
    local proving setup. In production, isolate 5701 with a host firewall, a
    `NetworkPolicy` or a private network.

A live run of this example formed a two-member cluster (`Members {size:2`) on
both servers.

```yaml
--8<-- "multi-instance/compose.yaml:cluster-env"
```

## Kubernetes

Members find each other through a headless service in DNS mode:

```yaml
--8<-- "multi-instance/kubernetes.yaml:k8s-service"
```

The pod environment selects the mode:

```yaml
--8<-- "multi-instance/kubernetes.yaml:k8s-join"
```

DNS mode (`service-dns`) needs no Kubernetes API access and no RBAC. API mode
(`service-name`) calls the Kubernetes API, so the pods need a service account
whose role grants `get` and `list` on `endpoints` and `pods` in the namespace,
and the token must be mounted. The complete ServiceAccount, Role and
RoleBinding are in the commented block at the end of
`docker/multi-instance/kubernetes.yaml`. Set exactly one of the two properties.

## Network isolation is your responsibility

Member traffic is plaintext and unauthenticated in OSS Hazelcast 5.7.0. The
cluster name is a label, not a secret: it does not stop a process that can reach
the port from joining. A process that joins can read every map, including raw
subject ids in the keys, and can write to them. That means anyone who can reach
the member port can **read and write** cluster state: read subject ids, **forge
an APPROVED approval and bypass four-eyes**, **lift a pause by deleting its
flag**, and **reset read budgets and rate limits**. This includes a plain
Hazelcast *client* configured with the cluster name: it can connect to a
`tcp-ip` or `kubernetes` member's port and read or write maps. A `none` member
refuses both members and clients.

Data Prism supports a deployment that provides isolation from outside. It does
not provide it:

- Use a private network for the members, enforced by a host firewall or your
  platform. A Docker `internal` network is not enough on every engine (see
  [Docker Compose](#docker-compose)).
- On Kubernetes, enforce a `NetworkPolicy` on port 5701. It only works if your
  CNI enforces policies.
- Or use a service mesh with mutual TLS between pods.
- Never publish 5701 to a host, a load balancer or the internet.
- Set `member.interface` so the member listens only on the member network.

```yaml
--8<-- "multi-instance/kubernetes.yaml:k8s-networkpolicy"
```

Once that policy selects the pods, every other ingress is denied, port 8080
included, until you add an allow rule for your ingress.

## Using the starter

The starter declares Hazelcast 5.7.0, but Spring Boot's dependency management in
your own build overrides that declaration. An application that embeds the starter
gets 5.7.0 only if its build says so; without that, it can get an older Hazelcast
(0.4.0 shipped 5.5.0).

- With `spring-boot-starter-parent`, set the property:

    ```xml
    <properties>
      <hazelcast.version>5.7.0</hazelcast.version>
    </properties>
    ```

- When you import `spring-boot-dependencies`, add your own entry. The property
  override does nothing for an imported BOM:

    ```xml
    <dependencyManagement>
      <dependencies>
        <dependency>
          <groupId>com.hazelcast</groupId>
          <artifactId>hazelcast</artifactId>
          <version>5.7.0</version>
        </dependency>
      </dependencies>
    </dependencyManagement>
    ```

- With Gradle and the Spring dependency-management plugin:

    ```groovy
    ext['hazelcast.version'] = '5.7.0'
    ```

0.4.0's multi-member behaviour was only ever exercised on 5.5.0 in the server
module.

## Failure behaviour

An entry lives on an owner and one backup. Losing one member loses nothing.
Losing the owner and the backup of an entry together loses that entry:

- A **pause flag** is gone, which reopens the paused path. After losing more
  than one member at once, assert the pause again and check that it holds.
- **Budgets** and **caller-rate windows** reset when their holders leave.
- **Approvals** pending on the lost members are gone and must be requested again.
- The **re-identification index** cannot be recomputed. It is a store, and its
  durability is the cluster's durability. It is also LRU-evicted at 100,000
  entries per member, and an evicted entry is lost for good.
- Identity entries are recomputed, with no change to any answer.

Scaling to zero loses everything held only in memory.

### A lock someone holds

Reading the budget, counting a caller's requests and creating or deciding an
approval each take a short cluster lock on the entry they change. A lock that
another member, a slow member or something on the member port holds no longer
hangs the request thread. Each lock wait is at most `dataprism.hazelcast.lock-timeout`
(default two seconds), after which the call is refused with `CLUSTER_LOCK_TIMEOUT`
without changing any counter or approval. A call that takes several locks waits
at most n times the timeout in total, for n locks. These calls take more than one:
creating an approval (three: the id claim, the requester's pending entry and the
approval entry), deciding an approval (the entry's lock, and after the decision has
committed the pending entry's lock), and ending a scope or forgetting its approvals
(up to three for each approval of the scope, one after another):

- A tool call refused at admission, or a read refused by the budget, returns
  `CLUSTER_LOCK_TIMEOUT` and is audited as `DENY:CLUSTER_LOCK_TIMEOUT`. A held lock
  is therefore not reported as an exhausted budget. A budget failure that is not a
  lock timeout, such as an unreachable cluster, still refuses the read as before.
- The operator API keeps answering `503 OVERSIGHT_UNAVAILABLE`.

Every lock is leased for five times the lock timeout (ten seconds with the
default), so none can be held for ever. A lock only bounds a wait. Every call
that changes an approval, an id claim or a pending entry runs while this thread
holds that key's lock, taken with the timeout, so none of them waits for a lock
another member holds beyond the timeout. None of the checks depends on still
holding the locks: the id is claimed with `putIfAbsent`, the cap is enforced by
one entry processor, and a decision is a compare-and-set. An owner paused past
its lease therefore cannot let a second approval with the same id, or a requester
over the cap, through.

- **Approve and reject never wait unboundedly after they commit.** A decision
  commits its new status and its expiry in one step, under the entry's lock. The
  entry's lock is then released, and only after that does the call take the
  requester's pending lock, for at most the timeout, to release the slot. If that
  lock times out or the release fails, the decision still stands and is
  returned. The slot stays counted until the sweep removes it, which only
  over-counts: the requester may be refused a new approval for a little longer,
  never allowed more. Each release skipped this way is counted in
  `dataprism.approval.release.skipped`.
- **Forget and end scope refuse rather than half-release.** Ending a scope (or
  forgetting its approvals) takes each approval's lock, removes the approval, and
  only then releases its pending slot and its id claim, each under its own bounded
  lock and only if the token still matches. A guard is never released while its
  approval exists. If an approval's lock times out, that approval, its claim and
  its slot are left in place, the others are still handled, and at the end the
  call is refused with `CLUSTER_LOCK_TIMEOUT`. Call it again once the lock is free. A
  slot or claim whose lock times out after its approval was removed is skipped,
  counted in `dataprism.approval.release.skipped`, and removed by the sweep; the call
  does not fail for it.
- The pending sweep and the claim sweep only take locks they get at once, and skip
  the others until the next interval.

Writes to the identity and re-identification caches, the budget and oversight
entries and the pause flags that `endScope` deletes take no lock of their own,
but a lock someone else holds on one of their keys still blocks them; those are
not bounded yet.

If Hazelcast rejects a lock or its lease, or an id-claim or pending-count
operation fails, creation is refused with `CLUSTER_OPERATION_REFUSED`. The
message names no key, scope, principal or approval id. A real duplicate id
still gets `IllegalArgumentException("duplicate approval id")`, and the two are
never confused. One way to see the new code: set
`hazelcast.lock.max.lease.time.seconds` below five times the lock timeout.
A lost write quorum keeps its own `SplitBrainProtectionException`, as
described below.

**Upgrade caution.** Approvals, id claims and pending entries written before
this version are unreadable after it: the approval entry, the claim and the
pending owner each carry a creation token that the old values lack. They are
refused, never guessed. An old approval is unknown (re-request it), an old claim
keeps its id unusable until its entry is gone, when the claim sweep removes it, and an old pending entry refuses new approvals for that
requester, until the maps are emptied. The maps are not written to disk, so
stopping every member and starting the new version empties them. No member of the
old version may run together with members of the new one: an old member would
write values the new members cannot read, and cannot honour the tokens.

### Approvals under a partition

An approval is consumed, approved or rejected by a compare-and-set on its entry:
of any number of callers racing on any members, exactly one succeeds. Hazelcast's
locks give no such guarantee under split brain, so correctness does not rest on
them; they only bound the wait.

An approval id is claimed by `putIfAbsent` in `dataprism.approval.id`, and the pending
cap is one entry processor on the requester's entry in `dataprism.approval.pending`. A
creation claims the id (taking over a claim whose entry is gone), then reserves the cap (each pending id records the owner of the slot), then checks it still holds the claim, inserts the approval and checks again, and undoes in
reverse order when a step fails. An undo that fails can leave an id unusable, or a
requester over-counted, until the approval expires; it never under-counts, with the clock tolerance stated under Retention below. An approval
leaves the pending entry when it is approved, rejected or found expired, or when its scope
ends. Consuming an approval leaves its id claimed while the consumed entry still exists.

**The creation token.** Every creation generates a random token (a random UUID, which
carries no personal data and is derived from nothing). The token is stored in the approval
entry, in the id claim (the entry key and the token) and in the pending slot's owner (the scope
and the token). Every release, removal, withdrawal, undo, takeover, sweep and merge compares the
whole token, so a clean-up that belongs to one creation of an id cannot touch a later creation of
the same id in the same scope, even when the two requests are identical. The principal, the
approval id and the scope stay in clear in these maps, as before.

**When an approval is usable.** An approval is usable by `find`, `findPending`, `approve`,
`reject` and `consumeApproved` only while the claim for its id names its entry key and its token.
Otherwise it is treated as unknown: `approve` and `reject` refuse with `UNKNOWN_APPROVAL`, and the
reads skip it. This covers a withdrawal that failed, a claim lost after the insert, a heal that
kept the entry of one creation and the claim of another, and entries in the old format. The claim
is read with a call that never waits. If the claim cannot be read back after the insert, creation
tries to remove its own entry by token while it still holds the entry's lock and refuses with
`CLUSTER_OPERATION_REFUSED`. If that removal fails too, the entry stays, and it is usable only
while its claim names it. In that case it is a `PENDING` approval that still needs an operator's
decision, it counts against the requester's cap, and it ends on its expiry.

**What the cap counts.** A pending approval stays counted until one of three things happens:
it leaves `PENDING` (the entry that owns the slot releases it); its expiry has passed on the clock
of the caller creating the next approval (the cap compares each id's expiry with that request's
creation time, so the count can differ by the clock difference between the callers that create
approvals); or the sweep removes it once its expiry plus one minute has passed on the sweeping
member's clock. Every other path only counts longer: a failed undo, a creator that lost its id
claim, a slot taken over by a later creator (the slot keeps the later expiry and is owned by the
latest writer), a release by an entry that does not own the slot, and a heal that finds the two
sides disagreeing on the owner (the slot then has no owner and stays until it expires). None of
those removes a slot early.

**Retention.** The id claim and the requester's pending entry carry the principal, the
approval id and the scope in clear, so they must not outlive the approvals they guard by
much, and they must never be dropped early. The two are handled differently.

- A claim has **no TTL and depends on no clock**. It is valid if and only if the approval entry it
  names still exists. A claim whose entry is gone (expired, scope ended, or its creator died before
  inserting) is stale: the next creator of that id takes it over, by marking it, checking the old entry is
  still absent, and then naming itself, all by compare-and-set. A creator that was paused before its
  insert checks, after inserting, that it still holds the claim, and withdraws its entry and refuses if
  not, so a lock lease that ran out during the pause cannot let two approvals share an id. Stale claims
  that nobody re-creates are removed by the sweep below, which uses the same marking steps and never
  removes a claim whose entry exists. A lifetime for claims was tried and dropped: comparing lifetimes
  written by different members breaks under clock skew between members, and a claim that expires while
  its approval lives lets the id be created again.
- A pending entry has **no TTL**. Any TTL could drop an id that is still counted, and the
  requester could then pass the cap, which fails open. Instead every member runs a sweep, by
  default every five minutes, over the pending entries it owns. The sweep removes an id only
  when its expiry plus one minute has passed, and removes the entry when it is empty. The
  sweep is handed the time, so running it twice changes nothing more. Retention is therefore
  bounded by the approval's expiry, plus one minute, plus the sweep interval. Set the interval
  with the Hazelcast config property `dataprism.approval.pending.sweep-seconds` (1 to 86400,
  default 300); anything else is refused at start. An entry the sweep cannot read is left in
  place, so the cap keeps refusing. A member that has lost the write quorum cannot sweep and
  tries again at the next interval.

The one-minute slack is also the tolerance for a caller whose clock runs behind the sweeping
member's. The same sweep removes stale id claims on the same schedule, skipping any id whose lock is held at that moment (its creator is running), so a claim outlives its
approval entry by at most the sweep interval. A creator that happens to be between its claim and its
insert when the sweep passes is refused, and can simply retry.

A partition still leaves each side with its own copy of the entry. Two protections
narrow what that allows.

- **After the heal, a used approval stays used.** If the two copies carry the same
  token, the approval map keeps the one furthest along: consumed, then rejected,
  then expired, then approved, then pending. If the tokens differ, the two are two
  creations of one id, one on each side; the copy already in the surviving cluster
  is kept, whatever the statuses, and the other creation is never resurrected. A
  copy that cannot be read is dropped. Hazelcast's default would have kept the larger
  side's copy, so an approval that one side had consumed could have become usable
  again. Budgets and rate windows are not merged this way: a merge can lose them
  or lower a count, so after a partition a caller may get more reads or requests
  than the limit. The id claims and pending entries merge so that a heal never
  frees a held claim and never lowers a requester's pending count: claims are
  kept (`PutIfAbsentMergePolicy`, which keeps the existing claim when the two values
  differ) and pending ids are united, each with its later expiry; an id whose owner
  (scope and token) differs between the sides keeps the later expiry and has no
  owner, so neither creation can release it. A heal can pair an entry with another
  creation's claim, because each map merges on its own; the entry is then not
  usable, which fails closed. Both maps take the same write quorum as the approval map. During the
  partition each side can still admit up to the cap, so after the heal a requester
  may briefly hold more pending approvals than the cap, and no new one is
  admitted until the extra expire or are decided.
- **The smaller side refuses.** Writes to the approval map (create, approve,
  reject, consume, and the claims and counts behind create) throw on a member that sees fewer members than
  `approval-minimum-members`, and admission turns that into `OVERSIGHT_UNAVAILABLE`.
  Reads still work. This needs no licence: it is the open-source split-brain
  protection, not the CP subsystem.

What can still happen: **one use per side, inside the detection window.** A member
learns it is on the smaller side only when Hazelcast removes the others from its
member list. A member that leaves or whose connection closes is noticed at once;
one that stops answering is noticed after Hazelcast's heartbeat timeout
(`hazelcast.max.no.heartbeat.seconds`, 60 seconds unless you changed it). In that
window both sides can consume the same approval once. After it, the smaller side
refuses and the heal keeps the consumed state.

What it costs: in a two-member `tcp-ip` cluster the default minimum is 2, so
losing either member stops approval-gated tools until it returns. That is
deliberate. Where one member is enough, set `approval-minimum-members: 1`, which
removes the protection and the cost, and accepts one use per side for the whole
partition. A minimum of 1 applies no protection at all. A member also refuses
approvals while it is the only one running of a cluster that needs more.

Consuming, approving or rejecting changes the value and sets the entry's expiry in one
entry-processor step, which compares the stored value with the one it read. It
either commits the new value with its expiry or changes nothing, so no crash or
failure can leave a status changed without its expiry. A decision retried by
Hazelcast after it was already applied is recognised as committed. One residual
remains: if the response to the remote compare-and-set is lost or times out
(`OperationTimeoutException`), `approve` or `reject` throws although the change may
have happened, and the approval is then `APPROVED` or `REJECTED`. A caller that gets a
failure from a decision should re-read the status (`find`) before treating the
failure as final. The stored `expiresAt` is checked on every use as well.

A `PrivacyCluster` you build yourself with `PrivacyCluster.using(instance)` gets
none of these map settings: not the merge policy, not the quorum and not the
no-eviction setting of `dataprism.approval.id` and `dataprism.approval.pending`.
Configure them on the instance, or accept the defaults above. `PrivacyCluster.embedded(Config)`
adds the merge policy but, unless you pass a minimum, not the quorum.
