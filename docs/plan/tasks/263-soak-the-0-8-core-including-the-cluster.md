# 263 — Soak the 0.8 core on one node and on a three-member cluster, including the cluster-security soak

**Repo:** `.`
**Release:** 0.8.0 (evidence)
**Executor:** the owner, or an agent on a dedicated host; not a shared CI runner.
**Base branch:** the 0.8 integration branch once every dependency has merged
**Depends on:** 258 (soak mode), 252 (`LinkTimingSoakTest`), 255 (links and catalog wired), 257 (257 owns `.github/workflows/*.yml`), 231 (sealed-cluster plan; 231 follows 230 and through it every sealed-cluster task 218 to 229, so a three-member sealed cluster runs), and 264 (sealed-cluster plan; the shared link limiter, without which a joined member refuses `dataprism.links.enabled=true` and a configured link key (page links) with `MISSING_SHARED_LINK_LIMITER`. If 264 is cut from 0.8, drop this edge and run the three-member mix with links disabled and no link key, and the link and paging mix on a single node only)
**Wave:** 14 (combined 0.8 graph of 213, 218 to 231 and 238 to 264; 203 and 206 to 211 counted as landed)
**Owns:**
- load-test/soak/** *(new: scenario files, a Compose overlay for the three-member run, and the fault, rotation and capture scripts of design 5.2)*
- docs/plan/perf/soak-0.8.md *(new)*
- .github/workflows/load-test.yml *(the scenario choice list only)*

## Goal
The roadmap's 0.8 readiness includes a soak, including the cluster. Run the 0.8 core for hours, on a single node and on a sealed three-member cluster, with the agent-led surface in use (catalog pages, `_links`, `follow`, targeted and untargeted calls) and the failure paths of 258 mixed in, and record whether latency, memory, budget accounting and audit volume stay stable. Run the link timing test that sealed-links obligation 10 asks for. This task also runs the cluster-security soak of `cluster-security.md` 5.2, which the sealed-cluster plan assigns here (215's Outcome): key rotation under load, the fault and hostile-member runs, and the packet capture on the member port. Sealed-cluster task 229 tests rotation in process only.

## Context
- docs/plan/design/sealed-links.md section 7 item 10 (the statistical timing test) and docs/plan/design/batch-and-bulk-follow.md section 8 item 28 (scale evidence belongs to 0.9; this is the 0.8 soak).
- load-test/README.md after 258: soak mode, token and scope rotation.
- docs/plan/perf/baseline-0.7-quiet.md (259): the figures to compare against.
- docs/plan/design/cluster-security.md 5.2 (as amended by 218): every step, negative case and pass criterion of the cluster soak, including the rotation steps (1) to (5) and negatives (a) to (c), and the environment verification. 4.6 (rotation phases), 4.4 (the `rotation.*` and `seal.*` metrics).
- docs/plan/tasks/215-plan-sealed-cluster-implementation.md, Outcome: the soak (design 5.2), rotation under load and the packet capture are this task's.
- Sealed-cluster tasks 229 (`RotationPhasesTest`, the in-process version of the rotation steps), 231 (`HostileMemberEndToEndTest`) and 264 (the shared link limiter). Read with `git show plan/0.8-sealed-cluster:docs/plan/tasks/<id>-*.md`.
- docs/multiple-instances.md: the isolation claims the capture tests.

## Acceptance
- [ ] Single node: a run of at least 8 hours at a fixed rate, 100 sources, links enabled, mixing `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links, invalid links, timeouts and budget exhaustion. Three members: the same mix for at least 4 hours through a load balancer, using the overlay under `load-test/soak/` and not editing `docker/multi-instance/**`.
- [ ] `soak-0.8.md` records per hour: p50, p95 and p99 per tool, throughput, every FAILED cause, heap after GC, live threads, open file handles, audit bytes written, and the budget counters against the calls made. It states pass or fail against criteria written before the run: no upward trend in heap after GC or threads over the last half of the run, no FAILED without a cause, budget counters equal to reads made, and no privacy refusal other than those the scenario provoked.
- [ ] Three members with links enabled use the shared limiter (264). Invalid `follow` calls spread over the members lock a principal at `invalid-attempt-limit` in total, not per member, and so do invalid `list_sources` page links spread over the members.
- [ ] Sealing cost (design 5.2): latency and CPU of the sealed three-member run against the 0.7 baseline (259) and against the single-node run, at 100 and at 500 sources. The 500-source run is a short measurement run, not scale evidence. Also the sealed entry size against the 100,000-entries-per-member cap. `soak-0.8.md` states whether the cost estimate in design 2.3 holds.
- [ ] Faults under load: kill and restart a member, kill a partition owner and its backup together, and roll all members. Each is recorded with its effect. No unsealed value leaks, no stale pause is accepted outside the lease, and re-identification index loss behaves as `docs/multiple-instances.md` documents.
- [ ] Key rotation under load, the design 5.2 procedure. Three members, at a fixed rate mixing budget reads, identity lookups, approval create, approve and consume, and re-identification lookups over 10,000 pre-created pseudonyms, with a pause toggled every 5 seconds.
  - Steps (1) to (5) run in order, and each step's assertion is recorded with its observed metric values: `rotation.accepting_next`, `rotation.behind`, `seal.entries_under_previous` and `seal.tamper`.
  - Negative cases (a), (b) and (c) each run and record their outcome: `CLUSTER_KEY_MISMATCH`, the fallback to K1, and `seal.unsupported_format` with nothing served.
  - Pass criteria: pause propagation never exceeds the configured lease, all 10,000 pseudonyms still resolve, and an approval created before phase 2 and consumed after it succeeds exactly once.
  - The name-key change runs separately as a full restart and is recorded as state loss.
- [ ] Hostile member under load (tamper flood): CPU stays bounded, health goes `DOWN` per `unverified-member.action`, and the cluster recovers after the member leaves. Network partition with a pause issued on one side: lease behaviour, split-brain approval consumption and rejoin are recorded. Clock skew injected on one member: the pause lease and `expiresAt` behave within the tolerance.
- [ ] Environment verification, once per supported platform. A packet capture on the member port is taken in each transport profile the deployment guide names. `soak-0.8.md` records, per profile, whether plaintext appears: none is expected only in the E3 profile. Each isolation claim in `docs/multiple-instances.md` is recorded as tested, with its result. Capture files are not committed. The record holds counts and findings only, never an address, a payload byte or a key.
- [ ] Each failed cluster-soak criterion is filed as a successor task named in the Outcome. None is waived in `soak-0.8.md`.
- [ ] `LinkTimingSoakTest` is run on the soak host and its result, with the tolerance, is in `soak-0.8.md`.
- [ ] The new soak scenarios and 258's failure-path scenarios are in the `load-test.yml` choice list.
- [ ] No hostname, address, user name or workplace detail is recorded; the machine is described by its specification only.

## Out of scope
- Changing the rotation protocol, the stores or `docs/multiple-instances.md` (sealed-cluster tasks 229, 213); this task measures and records.
- Editing `docker/multi-instance/**`; the overlay lives under `load-test/soak/`.
- 100, 300 and 500-source scale evidence for 1.0 (0.9).
- Fixing defects the soak finds: each becomes a successor task named in the Outcome.
