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
    start with TLS settings it cannot honour. Isolating the member port is the
    deployer's job, covered in [Network isolation](#network-isolation-is-your-responsibility).

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
`single-node` they are refused (`CLUSTER_SETTINGS_IGNORED`). They are also
refused when the application supplies its own `PrivacyCluster` bean, because they
would have no effect. Relaxed binding drops hyphens in environment variables:
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

`join.mode: none` is the explicit single-member choice: the member binds
`127.0.0.1` only and accepts no others. It refuses `member.interface`.

Without `member.interface`, a `tcp-ip` or `kubernetes` member binds **every**
network interface of the host, which is Hazelcast's default. Set the interface
to the address on your private member network so the member does not listen
anywhere else.

Auto-detection, multicast and phone-home are always off, so a member only ever
joins what you named.

### Refusals

Each refusal stops startup before a member starts. The code is on the
configuration failure.

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
| `HAZELCAST_TLS_UNSUPPORTED` | `tls-key-reference` or `tls-trust-reference` set, or a supplied Hazelcast `Config` that enables TLS or Hazelcast security |
| `UNSAFE_HAZELCAST_DISCOVERY` | A supplied instance or `Config` with auto-detection, multicast or the advanced network config enabled |

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
subnet. It publishes 8080 for one server on loopback and does not publish 5701.
It needs Docker Compose 2.24.4 or later. It has been checked statically; a live
two-member run is not yet part of this documentation.

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
subject ids in the keys, and can write to them, for example to clear a pause
flag.

Data Prism supports a deployment that provides isolation from outside. It does
not provide it:

- Use a private network, such as a Docker `internal` network, for the members.
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

## Failure behaviour

An entry lives on an owner and one backup. Losing one member loses nothing.
Losing the owner and the backup of an entry together loses that entry:

- A **pause flag** is gone, which reopens the paused path. After losing more
  than one member at once, assert the pause again and check that it holds.
- **Budgets** and **caller-rate windows** reset when their holders leave.
- **Approvals** pending on the lost members are gone and must be requested again.
- The **re-identification index** cannot be recomputed. It is a store, and its
  durability is the cluster's durability.
- Identity entries are recomputed, with no change to any answer.

This page makes no claim beyond that. Scaling to zero loses everything held only
in memory.
