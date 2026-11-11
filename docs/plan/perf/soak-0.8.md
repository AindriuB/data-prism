# Soak of the 0.8 core, single node and three-member cluster (task 263, Part B)

**Status: template. No result has been recorded.** Part A of task 263 built the scaffolding in
`load-test/soak/`; the owner fills this file in on a dedicated host at the 0.8.0 release cut. No
figure from a short smoke run belongs here, and a criterion that fails is filed as a successor task,
never waived in this file.

The record holds counts and findings only: no hostname, address, user name, workplace detail, payload
byte or key. The machine is described by its specification below and nothing else.

## Machine specification

| Item | Value |
|---|---|
| CPU model and core count | |
| Memory | |
| Disk type | |
| Operating system and version | |
| Container engine and version | |
| JDK | |
| Commit run (`commit` of the result file) | |
| Date | |

## Pass criteria, written before the run

A run passes only if every line holds.

**AC2, per run (single node, three members):**
1. No upward trend in heap after GC over the last half of the run.
2. No upward trend in live threads over the last half of the run.
3. No `FAILED` or `RESOLVER_FAILED` source status in any call. The run fails on any one. Its cause is recorded
   as the source statuses by source (the result's `perSourceStatus`) and, for transport failures, the entries of
   `calls.failureCauses`.
4. The budget counters equal the reads made.
5. No privacy refusal other than those the scenario provoked: `SCOPE_READ_BUDGET` (the read budget, in both
   runs) and `LINK_INVALID` (the single-node run's invalid-link calls, whose refusal text the driver counts
   under that code); none other. An `UNCLASSIFIED_ERROR` refusal fails the run.
6. No member logs "Resolving class ... is not allowed" at any point in any run (task 221). The count is
   zero.

**AC5, faults under load:** each fault is recorded with its effect; no stale pause is accepted outside
the lease; re-identification index loss behaves as `docs/multiple-instances.md` documents.

**AC6, key change:** each of the cluster key and the name key, changed as a planned full restart,
shows state loss: pending approvals, the re-identification index and the counters start empty, and a
pause is re-issued. Anything else fails.

**AC7, hostile member, partition, skew:** CPU stays bounded during the tamper flood; the
`clusterSecurity` readiness contributor goes DOWN under the default `unverified-member.action`
(`refuse`); the cluster recovers after the member leaves. For the partition: lease behaviour,
split-brain approval consumption and rejoin are recorded, and a pause is honoured only inside its
lease. Under clock skew the pause lease and `expiresAt` stay within the tolerance of
`docs/plan/design/cluster-security.md`.

**AC8, environment verification:** in 1.0 approvals and counters cross the member port unsealed, so
plaintext is expected in every profile except E3; the pause document, the member cards, the sentinel
and the identity and re-identification entries are envelopes, so no subject id appears in the clear in
any profile. A subject id in the clear in any profile fails.

## Per-hour metrics (AC2)

One table per run. One row per hour. The rows come from the result file's `intervals` (the driver's interval
rows: per operation p50, p95, p99, calls and failures, refusals by code, and, for the single-node run only,
heap after GC and live threads of the harness server's JVM). For the three-member run those two columns, the
open file handles and the audit bytes come from the host's own measurements of the members, because the driver
cannot read a member's JVM.

### Single node (at least 8 hours)

| Hour | Operation | p50 ms | p95 ms | p99 ms | Throughput per s | Heap after GC | Live threads | Open file handles | Audit bytes written | Budget counter | Reads made |
|---|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | | |

`FAILED` and `RESOLVER_FAILED` source statuses by source, and transport failures by cause (`failureCauses`), with counts:

| Cause | Count |
|---|---|
| | |

Pass or fail against the AC2 criteria:

| Criterion | Result |
|---|---|
| | |

### Three members (at least 4 hours)

| Hour | Operation | p50 ms | p95 ms | p99 ms | Throughput per s | Heap after GC | Live threads | Open file handles | Audit bytes written | Budget counter | Reads made |
|---|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | | |

`FAILED` and `RESOLVER_FAILED` source statuses by source, and transport failures by cause (`failureCauses`), with counts:

| Cause | Count |
|---|---|
| | |

Pass or fail against the AC2 criteria:

| Criterion | Result |
|---|---|
| | |

## Startup refusals (AC3)

| Member started with | Refused at startup with `MISSING_SHARED_LINK_LIMITER` |
|---|---|
| `dataprism.links.enabled=true` | |
| a link key configured | |

## Cluster cost (AC4)

Against the 0.7 baseline (`baseline-0.7-quiet.md`) and the single-node run. The 500-source run is a short
measurement run, not scale evidence.

| Sources | Setup | p50 ms | p95 ms | p99 ms | CPU | Throughput per s |
|---|---|---|---|---|---|---|
| 100 | 0.7 baseline | | | | | |
| 100 | single node | | | | | |
| 100 | three members | | | | | |
| 500 | single node | | | | | |
| 500 | three members | | | | | |

## Faults under load (AC5)

| Fault | Effect on calls | Pause accepted outside the lease | Re-identification index loss as documented | Result |
|---|---|---|---|---|
| Kill and restart a member | | | | |
| Kill a partition owner and its backup together | | | | |
| Roll all members | | | | |

## Key change (AC6)

| Key changed | Approvals pending afterwards | Re-identification index afterwards | Counters afterwards | Pause re-issued | Result |
|---|---|---|---|---|---|
| Cluster key (planned full restart) | | | | | |
| Name key (planned full restart) | | | | | |

## Hostile member, partition and clock skew (AC7)

| Run | Observation | Result |
|---|---|---|
| Hostile member: peak member CPU | | |
| Hostile member: `clusterSecurity` readiness | | |
| Hostile member: recovery after it leaves | | |
| Partition: lease behaviour | | |
| Partition: split-brain approval consumption | | |
| Partition: rejoin | | |
| Clock skew: pause lease | | |
| Clock skew: `expiresAt` | | |

## Packet captures on the member port (AC8)

One row per transport profile and platform. Capture files are not committed.

| Profile | Platform | Packets | Plaintext appears | Subject id in the clear | Result |
|---|---|---|---|---|---|
| E1 | | | | | |
| E2 | | | | | |
| E3 | | | | | |

### Isolation claims in `docs/multiple-instances.md` (AC8)

| Claim | Tested | Result |
|---|---|---|
| | | |

## `LinkTimingSoakTest` (AC10)

From `load-test/soak/link-timing.sh`.

| Result | Tolerance | Test seconds |
|---|---|---|
| | | |

## Successor tasks (AC9)

| Failed criterion | Successor task |
|---|---|
| | |
