# Soak of the 0.8 core, single node and three-member cluster (task 263, Part B)

**Status: results recorded for the two load runs (AC2 and the AC4 100-source rows). Not passed as written.**
Single node: AC2.5 fails, AC2.4 and AC2.6 are not verifiable. Three members: AC2.1, AC2.3 and AC2.4 are not
verifiable. AC3, AC5, AC6, AC7, AC8 and AC10 were not run: each needs an input the owner supplies (the tamper-flood
tool, the libfaketime build, the health bearer, new key material, a packet capture, an owner-issued pause). The
500-source rows of AC4 were not run. Part A of task 263 built the scaffolding in `load-test/soak/`. A criterion that
fails or cannot be verified is filed as a successor task, never waived in this file; the list is at the end.

The record holds counts and findings only: no hostname, address, user name, workplace detail, payload
byte or key. The machine is described by its specification below and nothing else.

## Machine specification

| Item | Value |
|---|---|
| CPU model and core count | Intel Core i7-5820K, 6 cores, 12 threads |
| Memory | 31 GiB |
| Disk type | SATA SSD |
| Operating system and version | Ubuntu 26.04.1 LTS |
| Container engine and version | Docker 29.8.0 (Compose 5.5.1) |
| JDK | OpenJDK 21.0.13 |
| Commit run (`commit` of the result file) | `78f326f1` (`workingTreeDirty` false in both result files); images built from one snapshot, `source=2c51f26764b5 commit=78f326f1 dirty=no` |
| Date | Single node 2026-11-14, 16:10 to 00:10 UTC (8 h). Three members 2026-11-15, 01:06 to 05:07 UTC (4 h). |

Caveat on the latency and CPU figures: the host also ran other, unrelated services during both runs. The owner
accepted it as the soak host, so it is not a quiet machine and the figures are not quiet-host figures.

## Run notes

The three-member run did not start at the first two attempts. At 00:12 UTC the overlay's fixed cluster subnet
overlapped an existing network on the host. At 01:04 UTC the digest-pinned load-balancer image was absent on the
host and `up` runs with `--pull never`, so the image had to be pulled beforehand. The run that is recorded here
used a host-local compose override that moved the cluster network to another private /24; the images and every
other setting were the same as the overlay's. Both are filed as candidate successors below: make the overlay's
cluster subnet configurable, and add the pre-pull step to the soak README. The single-node run had an earlier
attempt (a console log of it exists); only the completed 8-hour run is recorded.

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
| 1 | entityContext | 2.489 | 1548.551 | 1677.343 | 1.82 | 37.2 MiB | 44 | n/m | n/m | n/m | n/m |
| 1 | targetedEntityContext | 2.557 | 38.904 | 76.323 | 0.72 |  |  | n/m |  |  |  |
| 1 | listSources | 4.131 | 6.362 | 8.021 | 0.70 |  |  | n/m |  |  |  |
| 1 | describeSource | 2.919 | 4.847 | 6.363 | 0.47 |  |  | n/m |  |  |  |
| 1 | follow | 3.517 | 63.52 | 72.577 | 0.16 |  |  | n/m |  |  |  |
| 1 | followTranslated | 22.721 | 71.831 | 1010.943 | 0.06 |  |  | n/m |  |  |  |
| 1 | invalidLink | 3.092 | 4.954 | 6.017 | 0.08 |  |  | n/m |  |  |  |
| 2 | entityContext | 2.18 | 2.521 | 2.84 | 2.13 | 37.3 MiB | 45 | n/m | n/m | n/m | n/m |
| 2 | targetedEntityContext | 2.161 | 2.507 | 2.947 | 0.69 |  |  | n/m |  |  |  |
| 2 | listSources | 3.762 | 4.191 | 5.021 | 0.69 |  |  | n/m |  |  |  |
| 2 | describeSource | 2.528 | 2.9 | 3.287 | 0.48 |  |  | n/m |  |  |  |
| 3 | entityContext | 2.145 | 2.49 | 2.807 | 2.08 | 37.2 MiB | 44 | n/m | n/m | n/m | n/m |
| 3 | targetedEntityContext | 2.133 | 2.479 | 2.792 | 0.73 |  |  | n/m |  |  |  |
| 3 | listSources | 3.696 | 4.094 | 4.876 | 0.72 |  |  | n/m |  |  |  |
| 3 | describeSource | 2.473 | 2.826 | 3.434 | 0.47 |  |  | n/m |  |  |  |
| 4 | entityContext | 2.137 | 2.478 | 2.834 | 2.12 | 37.2 MiB | 45 | n/m | n/m | n/m | n/m |
| 4 | targetedEntityContext | 2.123 | 2.45 | 2.711 | 0.71 |  |  | n/m |  |  |  |
| 4 | listSources | 3.651 | 4.053 | 4.885 | 0.69 |  |  | n/m |  |  |  |
| 4 | describeSource | 2.462 | 2.797 | 3.087 | 0.48 |  |  | n/m |  |  |  |
| 5 | entityContext | 2.14 | 2.48 | 2.761 | 2.12 | 37.2 MiB | 45 | n/m | n/m | n/m | n/m |
| 5 | targetedEntityContext | 2.123 | 2.447 | 2.797 | 0.69 |  |  | n/m |  |  |  |
| 5 | listSources | 3.645 | 4.036 | 4.934 | 0.71 |  |  | n/m |  |  |  |
| 5 | describeSource | 2.464 | 2.803 | 3.158 | 0.48 |  |  | n/m |  |  |  |
| 6 | entityContext | 2.119 | 2.476 | 3.055 | 2.14 | 39.8 MiB | 84 | n/m | n/m | n/m | n/m |
| 6 | targetedEntityContext | 2.105 | 2.461 | 2.711 | 0.70 |  |  | n/m |  |  |  |
| 6 | listSources | 3.625 | 4.035 | 5.003 | 0.69 |  |  | n/m |  |  |  |
| 6 | describeSource | 2.421 | 2.768 | 3.101 | 0.46 |  |  | n/m |  |  |  |
| 6 | follow | 50.737 | 69.721 | 69.721 | 0.00 |  |  | n/m |  |  |  |
| 6 | invalidLink | 2.285 | 2.333 | 2.333 | 0.00 |  |  | n/m |  |  |  |
| 7 | entityContext | 2.142 | 1561.503 | 1678.495 | 1.81 | 37.2 MiB | 44 | n/m | n/m | n/m | n/m |
| 7 | targetedEntityContext | 2.153 | 57.756 | 74.97 | 0.70 |  |  | n/m |  |  |  |
| 7 | listSources | 3.675 | 4.076 | 4.963 | 0.70 |  |  | n/m |  |  |  |
| 7 | describeSource | 2.466 | 2.809 | 3.177 | 0.48 |  |  | n/m |  |  |  |
| 7 | follow | 2.481 | 63.414 | 71.523 | 0.16 |  |  | n/m |  |  |  |
| 7 | followTranslated | 21.965 | 66.448 | 70.646 | 0.07 |  |  | n/m |  |  |  |
| 7 | invalidLink | 1.981 | 2.28 | 3.396 | 0.08 |  |  | n/m |  |  |  |
| 8 | entityContext | 2.124 | 2.467 | 2.717 | 2.14 | 37.2 MiB | 44 | n/m | n/m | n/m | n/m |
| 8 | targetedEntityContext | 2.107 | 2.455 | 2.736 | 0.71 |  |  | n/m |  |  |  |
| 8 | listSources | 3.642 | 4.042 | 5.088 | 0.70 |  |  | n/m |  |  |  |
| 8 | describeSource | 2.432 | 2.779 | 2.999 | 0.45 |  |  | n/m |  |  |  |

Throughput per s is the operation's calls in the hour divided by 3,600. Whole run: 4.00 calls per second
(115,200 calls in 28,799.8 s); latency over all calls p50 2.31, p95 4.02, p99 67.6, max 1,742.3 ms. Heap after GC is
the interval's figure in MiB (1 MiB = 1,048,576 bytes). `n/m` means not measured: the harness server's JVM is the only
one the driver can read, so it has no open-file-handle, audit-byte, budget-counter or reads-made series. That is the
reason AC2.4 is not verifiable (see below). Rows are omitted where an operation made no call in the hour
(`follow`, `followTranslated` and `invalidLink` appear only in hours 1, 6 and 7, the hours with a live scope that
could answer a context call).

Reading the latencies. Context calls (`entityContext`, `targetedEntityContext`) were refused for
`SCOPE_READ_BUDGET` almost without exception outside hours 1 and 7: 77,756 of 79,310 context calls were refused, so
only 1,554 made real reads (737 in hour 1, 25 in hour 6, 792 in hour 7; hour 7 is the fresh scope after the run's
one rotation). The context-call latencies of the other hours are the latencies of a refusal, not of a read, and the
real-read figures rest on hours 1, 6 (25 calls) and 7. The p95 and p99 of about 1.5 to 1.7 s for `entityContext` in
hours 1 and 7 are the two deliberately slow sources reaching the 1 s per-source timeout, as designed. The
`follow`, `followTranslated` and `invalidLink` operations exist only in those hours for the same reason.

`FAILED` and `RESOLVER_FAILED` source statuses by source, and transport failures by cause (`failureCauses`), with counts:

| Cause | Count |
|---|---|
| Transport failures, every cause in `calls.failureCauses` (15 causes) | 0 each, 0 in total |
| `FAILED` source status (`sourceStatusTotals`, any source) | 0 |
| `RESOLVER_FAILED` source status (`sourceStatusTotals`, any source) | 0 |

Source statuses over the run (`sourceStatusTotals`): ANSWERED 91,944; TIMED_OUT 1,866; every other status 0. The
1,866 timeouts come from the two deliberately slow sources (2.5 to 3.5 s latency) against the 1 s per-source
timeout; they are the scenario's design, not a failure criterion. Refusals (`refusalCodes`): SCOPE_READ_BUDGET
78,582; LINK_INVALID 583; SOURCE_NOT_AVAILABLE 19 (9 in hour 6, 10 in hour 7). 115,200 calls were attempted and
completed; `notDispatched` 0, `completedAfterDeadline` 0, the server did not die. Credentials: one token reissue,
one scope rotation.

Pass or fail against the AC2 criteria:

| Criterion | Result |
|---|---|
| AC2.1 heap after GC, no upward trend over the last half | Pass. Hours 5 to 8: 37.2, 39.8 (hour 6), 37.2, 37.2 MiB. Hours 1 to 5 are 37.2 to 37.3 MiB. The hour-6 value is 2.7 MB above the rest and fell back in hour 7. |
| AC2.2 live threads, no upward trend over the last half | Pass. Hours 5 to 8: 45, 84 (hour 6), 44, 44. The hour-6 spike returned to 44 in hour 7. It falls in the window of the scope rotation, which is an inference from timing, not an observation of cause. |
| AC2.3 no `FAILED` or `RESOLVER_FAILED` | Pass. Both 0 in `sourceStatusTotals`; no transport failure. |
| AC2.4 budget counters equal the reads made | Not verifiable from the harness output. Neither the result file nor the console log carries a budget-counter or reads-made figure. This is not a pass; a successor is filed below. |
| AC2.5 no refusal other than the scenario's | Fail as written. `SOURCE_NOT_AVAILABLE` appeared 19 times (9 in hour 6, 10 in hour 7), and the criterion allows only `SCOPE_READ_BUDGET` and `LINK_INVALID`. No `UNCLASSIFIED_ERROR` occurred. The 19 fall in the hours of the one scope rotation and the thread spike; the cause is not established here. |
| AC2.6 no "Resolving class ... is not allowed" | Not recorded for this run. The soak chain log counts it per cluster member only; no log line or file records a count for the single-node harness server. This is not a pass; a successor is filed below. |

Run verdict: not passed. AC2.5 failed; AC2.4 and AC2.6 are not verifiable from what was recorded.

One inconsistency in the interval rows, unexplained: hour 6 counts 9 `LINK_INVALID` refusals under `refusalsByCode` against 3 `invalidLink` calls refused, and the per-operation `refused` counts (describeSource 4, listSources 6) sum to 10 against 9 `SOURCE_NOT_AVAILABLE`. The totals in `refusalCodes` are used above.


### Three members (at least 4 hours)

| Hour | Operation | p50 ms | p95 ms | p99 ms | Throughput per s | Heap after GC | Live threads | Open file handles | Audit bytes written | Budget counter | Reads made |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | entityContext | 8.701 | 15.764 | 20.472 | 1.31 | n/m | a 132-141, b 119-125, c 119-126 | a 37-39, b 37-40, c 37-41 | n/m | n/m | n/m |
| 1 | targetedEntityContext | 8.735 | 15.677 | 20.927 | 1.00 |  |  |  |  |  |  |
| 1 | listSources | 4.269 | 7.815 | 10.444 | 1.01 |  |  |  |  |  |  |
| 1 | describeSource | 4.266 | 7.517 | 10.183 | 0.68 |  |  |  |  |  |  |
| 2 | entityContext | 7.057 | 8.13 | 13.954 | 1.34 | n/m | a 137-140, b 125-126, c 126 | a 37-38, b 37-38, c 37-38 | n/m | n/m | n/m |
| 2 | targetedEntityContext | 7.092 | 8.123 | 13.819 | 1.00 |  |  |  |  |  |  |
| 2 | listSources | 3.471 | 3.887 | 4.986 | 1.01 |  |  |  |  |  |  |
| 2 | describeSource | 3.502 | 3.926 | 5.091 | 0.66 |  |  |  |  |  |  |
| 3 | entityContext | 6.569 | 7.339 | 12.522 | 1.30 | n/m | a 134-140, b 121-126, c 122-126 | a 36-38, b 36-38, c 36-37 | n/m | n/m | n/m |
| 3 | targetedEntityContext | 6.586 | 7.359 | 12.956 | 1.02 |  |  |  |  |  |  |
| 3 | listSources | 3.267 | 3.628 | 4.516 | 1.01 |  |  |  |  |  |  |
| 3 | describeSource | 3.308 | 3.668 | 4.127 | 0.67 |  |  |  |  |  |  |
| 4 | entityContext | 4.788 | 5.409 | 6.381 | 1.32 | n/m | a 135-138, b 122, c 123 | a 37-38, b 37, c 37 | n/m | n/m | n/m |
| 4 | targetedEntityContext | 4.821 | 5.411 | 5.822 | 0.98 |  |  |  |  |  |  |
| 4 | listSources | 3.221 | 3.593 | 4.236 | 1.02 |  |  |  |  |  |  |
| 4 | describeSource | 3.241 | 3.626 | 4.807 | 0.67 |  |  |  |  |  |  |

Throughput per s is the operation's calls in the hour divided by 3,600. Whole run: 4.00 calls per second
(57,600 calls in 14,399.8 s); latency over all calls p50 4.98, p95 10.17, p99 15.34, max 50.1 ms. Mix: no
`follow`, `followTranslated` or `invalidLink` (a joining member refuses links), so those rows do not exist. Heap after GC is `n/m` (not measured) for
every hour: the member image has no `jcmd`, and the driver cannot read a member's JVM. Live threads and open file
handles are the minimum to maximum of the host's 5-minute samples in that hour for each member (a, b, c; 11 to 12
samples per member per hour). Audit bytes, budget counter and reads made are `n/m`. Context calls hit
`SCOPE_READ_BUDGET` from hour 3, so the hour-1 and hour-2 latencies are real reads and the hour-4 context latencies
(4.8 ms) are refusals only.

Host figures from the same samples, for reference (container figures, not JVM heap): CPU 0.75 to 8.08 percent of
one core per member across the run (peak 8.08 on one member in hour 3); container memory rose from 433-478 MiB at
the start to 675-681 MiB (members a, b) and 618 MiB (c) by hour 4, rising in hours 1 to 3 and flat in hour 4 (a
675-681, b 674-675, c 617-619 MiB). That is not a heap series and does not stand in for AC2.1. Threads (119 to 141)
and open file handles (36 to 41) stayed flat.

`FAILED` and `RESOLVER_FAILED` source statuses by source, and transport failures by cause (`failureCauses`), with counts:

| Cause | Count |
|---|---|
| Transport failures, every cause in `calls.failureCauses` (15 causes) | 0 each, 0 in total |
| `FAILED` / `RESOLVER_FAILED` source status | Not observable: target mode writes no `sourceStatusTotals` or `perSourceStatus` |

57,600 calls were attempted and completed; `notDispatched` 0, `completedAfterDeadline` 0. Refusals
(`refusalCodes`): SCOPE_READ_BUDGET 9,457 (hour 1: 0, hour 2: 0, hour 3: 1,167, hour 4: 8,290) and nothing else.
Credentials: 5 token reissues, no scope rotation. Member build identity at the start and the end of the run: one
image, `source=2c51f26764b5 commit=78f326f1 dirty=no`, the same on all three members.

Against the README's estimate (about 17 percent of all calls refused, the first refusal about 2 h 50 min in): the
measured share is 9,457 of 57,600 calls, 16.4 percent, and 9,457 of 33,353 context calls, 28.4 percent (estimate:
about 29 percent of context calls). The refusals began in hour 3; the interval rows carry no timestamp of the first
one. At about 2.6 context calls per second, 1,167 refusals in hour 3 put the first about 7.5 minutes before the end
of that hour, near 2 h 52 min, which agrees with the estimate but is an inference from counts, not a measurement.

Pass or fail against the AC2 criteria:

| Criterion | Result |
|---|---|
| AC2.1 heap after GC, no upward trend over the last half | Not measured: the member image has no `jcmd`. This is not a pass; a successor is filed below. Container memory (not heap) was flat in hour 4 after rising in hours 1 to 3. |
| AC2.2 live threads, no upward trend over the last half | Pass. Host samples: threads per member flat, 119 to 141 across the run; the hour 3 and 4 ranges (a 134-140 and 135-138, b 121-126 and 122, c 122-126 and 123) are no higher than hours 1 and 2. |
| AC2.3 no `FAILED` or `RESOLVER_FAILED` | Not verifiable. Target mode writes no `sourceStatusTotals` or `perSourceStatus`, so source statuses are not observed. What is observed: 0 transport failures and refusals of one code only. Absence of a failure status is not shown by either. This is not a pass; a successor is filed below. |
| AC2.4 budget counters equal the reads made | Not verifiable from the harness output: no budget-counter or reads-made figure. The refusal timing (first in hour 3, 28.4 percent of context calls refused against an estimated 29) is consistent with the budget being applied but does not show the counter equals the reads. Not a pass. |
| AC2.5 no refusal other than the scenario's | Pass. `refusalCodes` holds only `SCOPE_READ_BUDGET` (9,457); no `LINK_INVALID` is possible (links off), no other code. |
| AC2.6 no "Resolving class ... is not allowed" | Pass. Count 0 on each of the three members at the end of the run, from the soak chain log. |

Run verdict: not passed as written. AC2.2, AC2.5 and AC2.6 hold; AC2.1, AC2.3 and AC2.4 are not verifiable from what the run recorded.


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
| 100 | 0.7 baseline | 105.0 | 127.1 | 146.6 | not measured | 36.9 |
| 100 | single node | 2.31 | 4.02 | 67.6 | not measured (harness server JVM only has heap and threads) | 4.00 |
| 100 | three members | 4.98 | 10.17 | 15.34 | 0.75 to 8.08 percent of one core per member (5-minute host samples) | 4.00 |
| 500 | single node | not run | not run | not run | not run | not run |
| 500 | three members | not run | not run | not run | not run | not run |

These three rows are not a like-for-like comparison and do not show the cost of clustering. The baseline is a
closed-loop saturation run on an M1 laptop with JDK 25 (median of three, 20,000 reads, no refusals), where throughput
is what the system sustains. The soak runs are paced at 4 calls per second on a different machine with JDK 21, the
mix includes cheap calls (`list_sources`, `describe_source`) and, in most hours, refused context calls, and the
host ran other services. The single-node figures cover 100 sources with two deliberately slow ones; the three-member
run has one fixture source and two subjects. The three-member run's latency is a call through a load balancer and a
member over the member port, with the context calls real reads only in hours 1 and 2 (hour 3 partly). The only
comparison the data supports is the one the two soak runs allow: with the same pacing and mix, a call through the
three-member cluster took about twice the single node's median (4.98 against 2.31 ms), and the same ratio does not
hold for the tail because the single node's p99 includes the slow-source timeouts. Single-node CPU was not measured.
Baseline row: `baseline-0.7-quiet.md`, 100-source median and range, p50 104.8 to 106.0, p95 125.8 to 137.6, p99
133.5 to 177.3, throughput 35.9 to 36.9.

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

Every failed or unverifiable criterion is listed. None is waived. Each row is "to be filed": no successor task
exists yet.

| Failed or unverifiable criterion | Successor task |
|---|---|
| AC2.5, single node: 19 `SOURCE_NOT_AVAILABLE` refusals (hours 6 and 7, around the scope rotation and the thread spike); find the cause and either fix it or amend the criterion's list with a stated reason | To be filed |
| AC2.4, both runs: the harness output carries no budget counter or reads-made figure, so the criterion cannot be checked; expose and record both | To be filed |
| AC2.1, three members: heap after GC not measured (the member image has no `jcmd`); add a way to read member heap | To be filed |
| AC2.3, three members: target mode writes no source statuses, so `FAILED` and `RESOLVER_FAILED` are not observable; record them in target mode | To be filed |
| AC2.6, single node: no record of the "Resolving class ... is not allowed" count for the harness server; record it | To be filed |
| Single-node hour-6 thread spike (44 or 45 to 84, back to 44), heap 2.7 MB above the rest; cause not established | To be filed |
| AC3, AC5, AC6, AC7, AC8, AC10 not run (inputs the owner supplies); AC4 500-source rows not run | To be filed |
| Harness: make the overlay's cluster subnet configurable (a fixed subnet overlapped an existing host network) | To be filed |
| Harness: add the pre-pull of the digest-pinned load-balancer image to the soak README (`up` runs with `--pull never`) | To be filed |
