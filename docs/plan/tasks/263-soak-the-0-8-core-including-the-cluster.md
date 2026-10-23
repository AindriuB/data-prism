# 263 — Soak the 0.8 core on one node and on a three-member cluster, including the cluster-security soak

Re-planned 2026-10-23 for owner decision (b): 1.0 supports multiple instances on a trusted network only. The sealed stores (224 to 226), rotation (229), the settings and tamper wiring (230), the adversarial suite (231) and the shared link limiter (264) are deferred past 1.0. The three-member run is therefore a trusted-network cluster in which only the pause document, the member cards and the sentinel are sealed (227, 228). It runs with links disabled and no link key, and rotation under load is not run.

**Repo:** `.`
**Release:** 0.8.0 (evidence)
**Executor:** the owner, or an agent on a dedicated host; not a shared CI runner.
**Base branch:** the 0.8 integration branch once every dependency has merged
**Depends on:** 258 (soak mode), 252 (`LinkTimingSoakTest`), 255 (links and catalog wired, and the `MISSING_SHARED_LINK_LIMITER` refusal on joining members), 257 (257 owns `.github/workflows/*.yml`), 227 and 228 (member cards and the pause document), 284 and 287 (a joining member needs `trusted-network: true` and a private interface), 286 (the `clusterSecurity` readiness contributor the hostile-member run watches). Re-wired 2026-10-23: the edges to 231 and 264 are removed (owner decision (b)).
**Wave:** 12 (re-planned 2026-10-23 for owner decision (b): 231 and 264 deferred past 1.0)
**Owns:**
- load-test/soak/** *(new: scenario files, a Compose overlay for the three-member run, and the fault and capture scripts of design 5.2)*
- docs/plan/perf/soak-0.8.md *(new)*
- .github/workflows/load-test.yml *(the scenario choice list only)*

## Goal
The roadmap's 0.8 readiness includes a soak, including the cluster. Run the 0.8 core for hours, on a single node and on a three-member trusted-network cluster, with the agent-led surface in use (catalog pages, `_links`, `follow`, targeted and untargeted calls) and the failure paths of 258 mixed in, and record whether latency, memory, budget accounting and audit volume stay stable. Run the link timing test that sealed-links obligation 10 asks for. This task also runs the parts of the cluster soak of `cluster-security.md` 5.2 that apply to 1.0 (215's Outcome assigned it here): the fault and hostile-member runs, and the packet capture on the member port. Key rotation under load is deferred with 229.

## Context
- docs/plan/design/sealed-links.md section 7 item 10 (the statistical timing test) and docs/plan/design/batch-and-bulk-follow.md section 8 item 28 (scale evidence belongs to 0.9; this is the 0.8 soak).
- load-test/README.md after 258: soak mode, token and scope rotation.
- docs/plan/perf/baseline-0.7-quiet.md (259): the figures to compare against.
- docs/plan/design/cluster-security.md 5.2 (as amended by 218): every step, negative case and pass criterion of the cluster soak, and the environment verification. The rotation steps (1) to (5) and negatives (a) to (c) are deferred with 229. 4.6 (rotation phases), 4.4 (the `rotation.*` and `seal.*` metrics).
- docs/plan/tasks/retired/215-plan-sealed-cluster-implementation.md, Outcome: the soak (design 5.2), rotation under load and the packet capture are this task's.
- Deferred past 1.0 (owner decision (b), 2026-10-23), for reference only: 229 (`RotationPhasesTest`), 231 (`HostileMemberEndToEndTest`) and 264 (the shared link limiter), in `docs/plan/tasks/deferred/`.
- docs/multiple-instances.md: the isolation claims the capture tests.
- From 221 (2026-10-21): fail if any member logs "Resolving class ... is not allowed" during the soak runs.

## Acceptance
- [ ] Single node: a run of at least 8 hours at a fixed rate, 100 sources, links enabled, mixing `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links, invalid links, timeouts and budget exhaustion. Three members: the same mix for at least 4 hours through a load balancer, using the overlay under `load-test/soak/` and not editing `docker/multi-instance/**`, except that links are disabled and no link key is configured, so there is no `follow` and no page link (a joining member refuses both with `MISSING_SHARED_LINK_LIMITER`). The overlay sets `trusted-network: true` and a private `member.interface`.
- [ ] `soak-0.8.md` records per hour: p50, p95 and p99 per tool, throughput, every FAILED cause, heap after GC, live threads, open file handles, audit bytes written, and the budget counters against the calls made. It states pass or fail against criteria written before the run: no upward trend in heap after GC or threads over the last half of the run, no FAILED without a cause, budget counters equal to reads made, and no privacy refusal other than those the scenario provoked.
- [ ] A member of the three-member cluster started with `dataprism.links.enabled=true`, and one started with a link key configured, each refuse at startup with `MISSING_SHARED_LINK_LIMITER`; `soak-0.8.md` records both. The link and paging mix runs on the single node only.
- [ ] Cluster cost: latency and CPU of the three-member run against the 0.7 baseline (259) and against the single-node run, at 100 and at 500 sources. The 500-source run is a short measurement run, not scale evidence. The sealed-entry size and the design 2.3 cost estimate are not measured; they are deferred with 224 to 226.
- [ ] Faults under load: kill and restart a member, kill a partition owner and its backup together, and roll all members. Each is recorded with its effect. No stale pause is accepted outside the lease, and re-identification index loss behaves as `docs/multiple-instances.md` documents.
- [ ] Key change: changing the cluster key and the name key, each as a planned full restart (rolling rotation is deferred with 229), is recorded as state loss: approvals pending, the re-identification index and counters start empty, and a pause is re-issued.
- [ ] Hostile member under load (tamper flood): CPU stays bounded, the `clusterSecurity` readiness contributor (286) goes `DOWN` under the default `unverified-member.action` (`refuse`; not a property in 1.0), and the cluster recovers after the member leaves. Network partition with a pause issued on one side: lease behaviour, split-brain approval consumption and rejoin are recorded. Clock skew injected on one member: the pause lease and `expiresAt` behave within the tolerance.
- [ ] Environment verification, once per supported platform. A packet capture on the member port is taken in each transport profile the deployment guide names. `soak-0.8.md` records, per profile, whether plaintext appears. In 1.0, approvals, identity entries, re-identification entries and counters cross the member port unsealed, so plaintext is expected in every profile except E3; only the pause document, the member cards and the sentinel are envelopes. Each isolation claim in `docs/multiple-instances.md` is recorded as tested, with its result. Capture files are not committed. The record holds counts and findings only, never an address, a payload byte or a key.
- [ ] Each failed cluster-soak criterion is filed as a successor task named in the Outcome. None is waived in `soak-0.8.md`.
- [ ] `LinkTimingSoakTest` is run on the soak host and its result, with the tolerance, is in `soak-0.8.md`.
- [ ] The new soak scenarios and 258's failure-path scenarios are in the `load-test.yml` choice list.
- [ ] No hostname, address, user name or workplace detail is recorded; the machine is described by its specification only.

## Out of scope
- Changing the stores or `docs/multiple-instances.md` (213); this task measures and records. Rotation under load and the sealing cost (deferred with 229 and 224 to 226).
- Editing `docker/multi-instance/**`; the overlay lives under `load-test/soak/`.
- 100, 300 and 500-source scale evidence for 1.0 (0.9).
- Fixing defects the soak finds: each becomes a successor task named in the Outcome.
