# 263 — Soak the 0.8 core on one node and on a three-member cluster, including the cluster-security soak

Re-planned 2026-10-23 for owner decision (b): 1.0 supports multiple instances on a trusted network only. The sealed approval and counter stores (224, 226), rotation (229), the settings and tamper wiring (230), the adversarial suite (231) and the shared link limiter (264) are deferred past 1.0. The three-member run is therefore a trusted-network cluster in which the pause document, the member cards, the sentinel (227, 228) and the identity and re-identification entries (225) are sealed, and approvals and counters are not. It runs with links disabled and no link key, and rotation under load is not run.

**Repo:** `.`
**Release:** 0.8.0 (evidence)
**Executor:** the owner, or an agent on a dedicated host; not a shared CI runner.
**Base branch:** the 0.8 integration branch once every dependency has merged
**Depends on:** 258 (soak mode), 252 (`LinkTimingSoakTest`), 255 (links and catalog wired, and the `MISSING_SHARED_LINK_LIMITER` refusal on joining members), 257 (257 owns `.github/workflows/*.yml`), 227 and 228 (member cards and the pause document), 284 and 287 (a joining member needs `trusted-network: true` and a private interface), 286 (the `clusterSecurity` readiness contributor the hostile-member run watches). Re-wired 2026-10-23: the edges to 231 and 264 are removed (owner decision (b)).
**Wave:** 13 (moved from 12 on 2026-11-06; re-planned 2026-10-23 for owner decision (b): 231 and 264 deferred past 1.0)
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

## Amendment 2026-11-10 (owner)
**Decision.** The task is split. Part A, the scaffolding, is agent-built now. Part B, running the soaks and filling in `soak-0.8.md`, is owner-run on a dedicated host as a 0.8.0 release-cut obligation.

**Reason.** An agent cannot run Part B in this environment. It needs an 8-hour single-node soak and a 4-hour three-member soak, fault injection (killing, restarting and rolling members, a network partition, clock skew, a hostile member) and packet captures on the member port in each transport profile and on each platform. Its results must be real measurements, never fabricated, so no agent may fill them in from a short run.

**Retirement.** This task retires when Part A lands. Part B is then carried in `docs/plan/PLAN.md` under the 0.8.0 release-cut obligations. The scribe adds it there at `/record`, and this task does not edit `PLAN.md`. The Part B items below are moved here verbatim from the pre-split list, with their IDs, so that the release-cut entry can cite them. The **Executor** line above applies to Part B. Owns is unchanged.

## Acceptance
IDs AC1 to AC12 are the pre-split items in their original order. AC13 onwards are new in Part A.

### Part A (agent)
- [ ] AC11 The new soak scenarios and 258's failure-path scenarios are in the `load-test.yml` choice list. Soak entries that live under `load-test/soak/` are listed as paths relative to `load-test/scenarios/` (for example `../soak/<file>.json`), because the workflow runs `load-test/run.sh "load-test/scenarios/${SCENARIO}"`.
- [ ] AC13 Scenario files under `load-test/soak/`: a single-node mix (100 sources, links enabled, a fixed rate, at least 8 hours, with the AC1 mix of `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links, invalid links, timeouts and budget exhaustion) and a three-member mix (the same mix for at least 4 hours through a load balancer, with no `follow` and no page link). Each one loads and runs through `load-test/run.sh`.
- [ ] AC14 A Compose overlay under `load-test/soak/`, applied on top of `docker/multi-instance/compose.yaml`, brings up three members behind a load balancer with `trusted-network: true`, a private `member.interface`, links disabled and no link key. `docker/multi-instance/**` is unchanged.
- [ ] AC15 Fault-injection scripts under `load-test/soak/` cover every Part B fault: kill and restart a member, kill a partition owner and its backup together, roll all members, a planned full restart for the cluster-key and name-key change, a hostile member (tamper flood), a network partition and clock skew on one member. They also cover the AC3 startup probes (`dataprism.links.enabled=true`, and a link key configured). Each script writes counts and findings only, never an address, a payload byte or a key.
- [ ] AC16 A packet-capture script under `load-test/soak/` captures the member port for a named transport profile. It writes raw captures only under `load-test/results/` (git-ignored) or outside the repository, and its summary holds counts and findings only: whether plaintext appears and whether a subject id appears in the clear. It never holds an address, a payload byte or a key.
- [ ] AC17 `docs/plan/perf/soak-0.8.md` exists as a template. The pass criteria of AC2, AC5, AC6, AC7 and AC8 are written out in advance, and the 221 "Resolving class ... is not allowed" criterion is included. Every Part B measurement has an empty result table: per-hour metrics, the AC3 refusals, the cluster cost, the faults, the key change, hostile member, partition and skew, captures per profile and platform, isolation claims, and `LinkTimingSoakTest`. A machine-specification section holds no hostname, address, user name or workplace detail. No result cell is filled in.
- [ ] AC18 A script under `load-test/soak/` runs `LinkTimingSoakTest` with `-Ddataprism.timing=true` and prints its result and tolerance in the form the AC10 table takes.
- [ ] AC19 Each scenario (AC11, AC13), the overlay (AC14) and each script (AC15, AC16, AC18) is run once as a smoke run of a few minutes. The Outcome records what was run, how long it ran and whether it worked, under the AC12 rule, and labels it "harness smoke, not soak evidence". No smoke figure goes into `soak-0.8.md`.
- [ ] AC20 No file outside Owns is changed.

### Part B (owner-run, release cut)
Executed on a dedicated host, never on a shared CI runner. Carried in PLAN.md's 0.8.0 release-cut obligations once Part A lands.
- [ ] AC1 Single node: a run of at least 8 hours at a fixed rate, 100 sources, links enabled, mixing `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links, invalid links, timeouts and budget exhaustion. Three members: the same mix for at least 4 hours through a load balancer, using the overlay under `load-test/soak/` and not editing `docker/multi-instance/**`, except that links are disabled and no link key is configured, so there is no `follow` and no page link (a joining member refuses both with `MISSING_SHARED_LINK_LIMITER`). The overlay sets `trusted-network: true` and a private `member.interface`.
- [ ] AC2 `soak-0.8.md` records per hour: p50, p95 and p99 per tool, throughput, every FAILED cause, heap after GC, live threads, open file handles, audit bytes written, and the budget counters against the calls made. It states pass or fail against criteria written before the run: no upward trend in heap after GC or threads over the last half of the run, no FAILED without a cause, budget counters equal to reads made, and no privacy refusal other than those the scenario provoked.
- [ ] AC3 A member of the three-member cluster started with `dataprism.links.enabled=true`, and one started with a link key configured, each refuse at startup with `MISSING_SHARED_LINK_LIMITER`; `soak-0.8.md` records both. The link and paging mix runs on the single node only.
- [ ] AC4 Cluster cost: latency and CPU of the three-member run against the 0.7 baseline (259) and against the single-node run, at 100 and at 500 sources. The 500-source run is a short measurement run, not scale evidence. The sealed-entry size and the design 2.3 cost estimate are not measured; they are deferred with 224 to 226.
- [ ] AC5 Faults under load: kill and restart a member, kill a partition owner and its backup together, and roll all members. Each is recorded with its effect. No stale pause is accepted outside the lease, and re-identification index loss behaves as `docs/multiple-instances.md` documents.
- [ ] AC6 Key change: changing the cluster key and the name key, each as a planned full restart (rolling rotation is deferred with 229), is recorded as state loss: approvals pending, the re-identification index and counters start empty, and a pause is re-issued.
- [ ] AC7 Hostile member under load (tamper flood): CPU stays bounded, the `clusterSecurity` readiness contributor (286) goes `DOWN` under the default `unverified-member.action` (`refuse`; not a property in 1.0), and the cluster recovers after the member leaves. Network partition with a pause issued on one side: lease behaviour, split-brain approval consumption and rejoin are recorded. Clock skew injected on one member: the pause lease and `expiresAt` behave within the tolerance.
- [ ] AC8 Environment verification, once per supported platform. A packet capture on the member port is taken in each transport profile the deployment guide names. `soak-0.8.md` records, per profile, whether plaintext appears. In 1.0, approvals and counters cross the member port unsealed, so plaintext is expected in every profile except E3; the pause document, the member cards, the sentinel and the identity and re-identification entries are envelopes, and no subject id appears in the clear. Each isolation claim in `docs/multiple-instances.md` is recorded as tested, with its result. Capture files are not committed. The record holds counts and findings only, never an address, a payload byte or a key.
- [ ] AC9 Each failed cluster-soak criterion is filed as a successor task named in the Outcome. None is waived in `soak-0.8.md`.
- [ ] AC10 `LinkTimingSoakTest` is run on the soak host and its result, with the tolerance, is in `soak-0.8.md`.
- [ ] AC12 No hostname, address, user name or workplace detail is recorded; the machine is described by its specification only.

## Checks
Part A only. Part B is proved by the owner's filled-in `soak-0.8.md` at the release cut.
- `harness-build` command — AC11, AC13
  run: `./mvnw -B -q -f load-test/pom.xml package`
- `scripts-parse` command — AC15, AC16, AC18
  run: `for f in load-test/soak/*.sh; do bash -n "$f" || exit 1; done`
- `overlay-config` command — AC14
  run: `docker compose -f docker/multi-instance/compose.yaml -f load-test/soak/<overlay> config -q`
- `multi-instance-untouched` command — AC14, AC20
  run: `git diff --exit-code origin/main...HEAD -- docker/multi-instance docs/plan/PLAN.md`
- `link-timing` test — AC18
  run: `./mvnw -B -q -pl data-prism-orchestration -am -Dtest=LinkTimingSoakTest -Dsurefire.failIfNoSpecifiedTests=false -Ddataprism.timing=true test`
  report: `data-prism-orchestration/target/surefire-reports/TEST-*LinkTimingSoakTest.xml`
  min-passing: 1  max-skipped: 0
- `scaffold-shape` read — AC11, AC13, AC14, AC15, AC16, AC17, AC19
  how: the choice-list entries resolve to existing files; each scenario has the AC13 mix and duration; the overlay sets the AC14 properties; no script or template line writes an address, payload or key; the template's result tables are empty; the Outcome's smoke record is labelled and has no host detail.

## Out of scope
- In Part A: running the 8-hour and 4-hour soaks, any fault or capture run beyond the AC19 smoke, and entering any figure in `soak-0.8.md`. Those are Part B.
- Editing `docs/plan/PLAN.md`; the scribe adds Part B at `/record`.
- Changing the stores or `docs/multiple-instances.md` (213); this task measures and records. Rotation under load and the sealing cost (rotation deferred with 229; sealing cost measured only for 225's maps, if at all, as a short note).
- Editing `docker/multi-instance/**`; the overlay lives under `load-test/soak/`.
- 100, 300 and 500-source scale evidence for 1.0 (0.9).
- Fixing defects the soak finds: each becomes a successor task named in the Outcome.
