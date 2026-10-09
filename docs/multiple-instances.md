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
| `dataprism.approval` | Approval requests, each until its expiry | No | 1 |
| `dataprism.callerrate` | Per-caller fixed-window request counts. No eviction | No | 1 |

The other maps evict least-recently-used entries beyond 100,000 per member.
`dataprism.oversight` and `dataprism.callerrate` are never evicted, because an
evicted pause flag reopens a paused path and an evicted counter resets a
caller's window.

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
| `dataprism.hazelcast.approval-minimum-members` | Optional integer, at least 1. The fewest members that must see each other for approvals to be created, decided or consumed. `tcp-ip`: unset means a majority of the distinct `join.members` entries, so the list must name every member, this one included. Members are counted after expanding entries separated by commas, semicolons or spaces and last-octet ranges such as `10.0.0.1-3`; an entry without a port is the configured `member.port`, so `10.0.0.1` and `10.0.0.1:5701` are one member only when `member.port` is 5701. `none`: 1, and the key is refused. The checks below apply only when approvals are in use, that is `dataprism.oversight.approval-required-tools` is not empty or `dataprism.reidentification.enabled` is true; otherwise no quorum is computed or required. A form that cannot be counted reliably (a wildcard, a range in another octet) is then refused with `INVALID_APPROVAL_QUORUM`. If any entry is a hostname, the count is unknown, because Hazelcast resolves a hostname to every address it has, so you must set the minimum (`MISSING_APPROVAL_QUORUM` otherwise). `kubernetes`: required when approvals are in use. See [Failure behaviour](#failure-behaviour) |

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
hangs the request thread. The call waits at most `dataprism.hazelcast.lock-timeout`
(default two seconds) and then is refused with `CLUSTER_LOCK_TIMEOUT`, without
changing any counter or approval:

- A tool call refused at admission, or a read refused by the budget, returns
  `CLUSTER_LOCK_TIMEOUT` and is audited as `DENY:CLUSTER_LOCK_TIMEOUT`. A held lock
  is therefore not reported as an exhausted budget. A budget failure that is not a
  lock timeout, such as an unreachable cluster, still refuses the read as before.
- The operator API keeps answering `503 OVERSIGHT_UNAVAILABLE`.

Each lock is leased for five times the lock timeout, so a lock whose holder died
is released on its own and the longest it can stall a key is known (ten seconds
with the default). Writes to the identity and re-identification caches and to the
pause flags take no lock of their own, but a lock someone else holds on one of
their keys still blocks them; those are not bounded yet.

### Approvals under a partition

An approval is consumed, approved or rejected by a compare-and-set on its entry:
of any number of callers racing on any members, exactly one succeeds. Hazelcast's
locks give no such guarantee under split brain, so correctness does not rest on
them; they only bound the wait.

A partition still leaves each side with its own copy of the entry. Two protections
narrow what that allows.

- **After the heal, a used approval stays used.** The approval map merges the two
  copies by taking the one furthest along: consumed, then rejected, then expired,
  then approved, then pending. Hazelcast's default would have kept the larger
  side's copy, so an approval that one side had consumed could have become usable
  again. Budgets and rate windows are not merged this way: a merge can lose them
  or lower a count, so after a partition a caller may get more reads or requests
  than the limit.
- **The smaller side refuses.** Writes to the approval map (create, approve,
  reject, consume) throw on a member that sees fewer members than
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

Consuming, approving or rejecting changes the value and then restores the entry's
expiry in a second step. A crash between the two leaves the entry without an
expiry until it is evicted (least recently used), but the stored `expiresAt` is
still checked on every use, so an expired approval is refused: it fails closed.

A `PrivacyCluster` you build yourself with `PrivacyCluster.using(instance)` gets
none of these map settings: not the merge policy and not the quorum. Configure
them on the instance, or accept the defaults above. `PrivacyCluster.embedded(Config)`
adds the merge policy but, unless you pass a minimum, not the quorum.
