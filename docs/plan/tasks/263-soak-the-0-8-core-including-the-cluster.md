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
- .github/workflows/load-test.yml *(whole file; widened from the scenario choice list by the second 2026-11-10 amendment)*
- load-test/src/** *(the harness driver; added by the second 2026-11-10 amendment)*
- load-test/pom.xml *(provided-scope dependencies and packaging for the harness `KeyResolver` only; added by the third amendment, 2026-11-11)*

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

## Amendment 2026-11-10 (owner, second): widen Owns for Part A
**Decision.** Owns gains `load-test/src/**` and the whole of `.github/workflows/load-test.yml`. The first amendment's "Owns is unchanged" no longer holds.

**Reason.** The Part A implementer's PARTIAL report showed AC13, AC15 and AC19 cannot close under the old Owns: the driver exercises `get_entity_context` only, `run.sh` always starts its own server, and the workflow's artifact name and timeout break `../soak/` scenarios.

**What Part A now delivers with them.**
- A1 The driver can issue `list_sources` walks, `describe_source`, `follow` and invalid links as well as `get_entity_context`. Scenario fields set the mix; an unknown scenario field or tool is still rejected at load. The soak scenarios use the new mix.
- A2 The driver can target an already-running endpoint (the overlay's load balancer) instead of starting its own server, selected by a scenario field so that `load-test/run.sh` (not in Owns) needs no change. The three-member scenario uses it.
- A3 `load-test.yml`: the uploaded artifact name does not embed the scenario path, so `../soak/` choices upload; the job timeout covers the longest soak scenario plus a margin.
- A4 If Docker is available, the overlay is brought up and the fault, probe and capture scripts are smoked for real. If not, the Outcome records that they had `bash -n` only, and the real run is left to the Part B owner run.
- A5 `fault-hostile-member.sh` (`TAMPER_FLOOD_CMD`) and `fault-clock-skew.sh` (`FAKETIME_LIB`) stay owner-supplied inputs; `load-test/README.md` is not in Owns, so `load-test/soak/README.md` says so.

**Overlap.** `load-test/src/**` intersects 273 (`ServerProcess.java`, configuration arguments only) and 275 (`SourceGenerator.java`, generated `json-sources:` text only). Both are 0.9 and have no edge to 263; left for the orchestrator to resolve.

## Amendment 2026-11-11 (owner, third)
Settles review round 1 items CR P2 (`soak-three-member.json:8,22`), 243-3, 243-13, 243-14 and the per-hour metrics part of CR P3. The findings are in the review-round section at the end.

**D1 Target mode applies nothing it cannot apply.** In target mode the read budget (`dataprism.limits.scope-read-budget`) and the per-source timeout are set on the cluster members through the overlay's `member-env`, not by the scenario. The driver refuses at load a target-mode scenario that sets `sources`, `slowSources`, `slowLatency` or `limits`, and a target-mode result records `mode: target` with no `effectiveLimits` and no source counts. Budget refusals are exercised in the cluster: the subject set is sized so that the expected `SCOPE_READ_BUDGET` share is stated (in the scenario's README entry) and is well short of the whole run. Source timeouts are single-node only. *Reason:* a result that reports limits the target never applied is false evidence (243-3), and two subjects against the default budget turn the cluster soak into a refusal measurement (243-14).

**D2 Translated links on the harness server.** The harness's own server gets a `KeyResolver`, and the single-node scenario follows translated links as well as direct ones. Recon: a `KeyResolver` is supplied only as a Spring bean (`LinkWiring.java:243-256` collects `ObjectProvider<KeyResolver>`; `dataprism.key-resolvers.*` sets bounds only, and no packaged module declares one). The server is a ZIP-layout jar that loads extension jars from `-Dloader.path` (`ServerProcess.java:57-69`), so the resolver is a harness-only auto-configuration under `load-test/src/**`, put on the harness server's `loader.path` beside the REST connector jar, with `SourceGenerator` declaring a second key namespace for it to translate into. `load-test/pom.xml` gains provided-scope `data-prism-core` and Spring dependencies for it. `KeyResolver` is in no published release, so the core jar comes from the reactor build `run.sh` already performs (`-pl data-prism-server -am` builds `data-prism-core`); `mvn install` is not used. No production code under `data-prism-*/src/main` changes, and the packaged server and images are unchanged. *Reason:* AC13 and AC1 require translated-link follows, and the round found none (243-13).
*Not applied, pending owner:* the overlay members' resolver and translated-link follows in the three-member scenario. Overlay members run with links disabled because a joining member refuses links and a link key with `MISSING_SHARED_LINK_LIMITER` (AC1, AC3, AC14; decision (b), 2026-10-23), so a member `KeyResolver` would never be called and the three-member run can follow no link at all. Applying that half means reversing decision (b) or landing 264, which is deferred past 1.0.

**D3 Interval metrics from the driver.** The driver writes interval rows, hourly by default and set by a scenario field: per operation p50, p95 and p99, call count, refusals by code, and heap after GC and live threads where the driver can read them (the harness server's JVM; not the cluster members). The per-hour tables of `soak-0.8.md` are filled from these rows. *Reason:* AC2's per-hour record had no source in the harness (CR P3).

## Acceptance
IDs AC1 to AC12 are the pre-split items in their original order. AC13 onwards are new in Part A.

### Part A (agent)
- [ ] AC11 The new soak scenarios and 258's failure-path scenarios are in the `load-test.yml` choice list. Soak entries that live under `load-test/soak/` are listed as paths relative to `load-test/scenarios/` (for example `../soak/<file>.json`), because the workflow runs `load-test/run.sh "load-test/scenarios/${SCENARIO}"`.
- [ ] AC13 Scenario files under `load-test/soak/`: a single-node mix (100 sources, links enabled, a fixed rate, at least 8 hours, with the AC1 mix of `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links (D2), invalid links, timeouts and budget exhaustion) and a three-member mix (the same mix for at least 4 hours through a load balancer, targeting the overlay's load balancer per A2, with no `follow`, no page link and no source timeouts: timeouts are single-node only; budget exhaustion comes from the read budget the overlay sets on the members, D1). The mix is set by scenario fields the driver executes (A1), not by comments. Each run writes the D3 interval rows: per operation p50, p95 and p99 and call count, refusals by code, and heap after GC and live threads where available, at a scenario-set interval defaulting to one hour. Each one loads and runs through `load-test/run.sh`.
- [ ] AC14 A Compose overlay under `load-test/soak/`, applied on top of `docker/multi-instance/compose.yaml`, brings up three members behind a load balancer with `trusted-network: true`, a private `member.interface`, links disabled and no link key. `docker/multi-instance/**` is unchanged.
- [ ] AC15 Fault-injection scripts under `load-test/soak/` cover every Part B fault: kill and restart a member, kill a partition owner and its backup together, roll all members, a planned full restart for the cluster-key and name-key change, a hostile member (tamper flood), a network partition and clock skew on one member. They also cover the AC3 startup probes (`dataprism.links.enabled=true`, and a link key configured). Each script writes counts and findings only, never an address, a payload byte or a key. The hostile-member and clock-skew scripts take `TAMPER_FLOOD_CMD` and `FAKETIME_LIB` from the owner and refuse to run without them; `load-test/soak/README.md` says so (A5).
- [ ] AC16 A packet-capture script under `load-test/soak/` captures the member port for a named transport profile. It writes raw captures only under `load-test/results/` (git-ignored) or outside the repository, and its summary holds counts and findings only: whether plaintext appears and whether a subject id appears in the clear. It never holds an address, a payload byte or a key.
- [ ] AC17 `docs/plan/perf/soak-0.8.md` exists as a template. The pass criteria of AC2, AC5, AC6, AC7 and AC8 are written out in advance, and the 221 "Resolving class ... is not allowed" criterion is included. Every Part B measurement has an empty result table: per-hour metrics, the AC3 refusals, the cluster cost, the faults, the key change, hostile member, partition and skew, captures per profile and platform, isolation claims, and `LinkTimingSoakTest`. A machine-specification section holds no hostname, address, user name or workplace detail. No result cell is filled in.
- [ ] AC18 A script under `load-test/soak/` runs `LinkTimingSoakTest` with `-Ddataprism.timing=true` and prints its result and tolerance in the form the AC10 table takes.
- [ ] AC19 Each scenario (AC11, AC13) is run once as a smoke run of a few minutes. The overlay (AC14) and each script (AC15, AC16, AC18) are smoked for real if Docker is available; otherwise the Outcome says they had `bash -n` only and are left to Part B (A4). The Outcome records what was run, how long it ran and whether it worked, under the AC12 rule, and labels it "harness smoke, not soak evidence". No smoke figure goes into `soak-0.8.md`.
- [ ] AC20 No file outside Owns is changed.
- [ ] AC21 `load-test.yml` meets A3: the artifact name has no scenario path in it, and `timeout-minutes` exceeds the longest soak scenario's duration.
- [ ] AC22 (D1) A target-mode scenario that sets `sources`, `slowSources`, `slowLatency` or `limits` is refused at load, naming the field; a target-mode result has `mode: target` and no `effectiveLimits` or source counts. The overlay's `member-env` sets the scope read budget and per-source timeout, and the three-member scenario's subject count and the expected `SCOPE_READ_BUDGET` share are stated in `load-test/soak/README.md`, the share being below the whole run.
- [ ] AC23 (D2) With the harness's own server, a `follow` of a translated link reaches the resolver's target namespace: a load-test module test shows the generated sources declare both namespaces, the resolver is on the server's `loader.path`, and the single-node scenario's mix includes translated follows. Nothing under `data-prism-*/src/main` or `docker/**` changes.

### Part B (owner-run, release cut)
Executed on a dedicated host, never on a shared CI runner. Carried in PLAN.md's 0.8.0 release-cut obligations once Part A lands.
- [ ] AC1 Single node: a run of at least 8 hours at a fixed rate, 100 sources, links enabled, mixing `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links, invalid links, timeouts and budget exhaustion. Three members: the same mix for at least 4 hours through a load balancer, using the overlay under `load-test/soak/` and not editing `docker/multi-instance/**`, except that links are disabled and no link key is configured, so there is no `follow` and no page link (a joining member refuses both with `MISSING_SHARED_LINK_LIMITER`). The overlay sets `trusted-network: true` and a private `member.interface`.
- [ ] AC2 `soak-0.8.md` records per hour: p50, p95 and p99 per tool, throughput, every FAILED cause, heap after GC, live threads, open file handles, audit bytes written, and the budget counters against the calls made. These rows come from the driver's interval rows (D3, AC13), plus the host's own measurements where the driver cannot read a figure. It states pass or fail against criteria written before the run: no upward trend in heap after GC or threads over the last half of the run, no FAILED without a cause, budget counters equal to reads made, and no privacy refusal other than those the scenario provoked.
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
- `harness-build` command — AC11, AC13, AC22, AC23
  run: `./mvnw -B -q -pl data-prism-server,data-prism-connectors-rest -am -DskipTests package && ./mvnw -B -q -f load-test/pom.xml package`
  (this runs the load-test module's own tests; new driver behaviour for A1 and A2 gets tests there)
- `scripts-parse` command — AC15, AC16, AC18
  run: `for f in load-test/soak/*.sh; do bash -n "$f" || exit 1; done`
- `overlay-config` command — AC14
  run: `docker compose -f docker/multi-instance/compose.yaml -f load-test/soak/<overlay> config -q`
- `multi-instance-untouched` command — AC14, AC20, AC23
  run: `git diff --exit-code origin/main...HEAD -- docker data-prism-*/src/main docs/plan/PLAN.md`
- `link-timing` test — AC18
  run: `./mvnw -B -q -pl data-prism-orchestration -am -Dtest=LinkTimingSoakTest -Dsurefire.failIfNoSpecifiedTests=false -Ddataprism.timing=true test`
  report: `data-prism-orchestration/target/surefire-reports/TEST-*LinkTimingSoakTest.xml`
  min-passing: 1  max-skipped: 0
- `scaffold-shape` read — AC11, AC13, AC14, AC15, AC16, AC17, AC19, AC21, AC22
  how: the choice-list entries resolve to existing files; the driver issues each A1 tool and rejects unknown fields; the artifact name and timeout meet AC21; each scenario has the AC13 mix and duration; the overlay sets the AC14 properties; no script or template line writes an address, payload or key; the template's result tables are empty; the Outcome's smoke record is labelled and has no host detail.

## Out of scope
- In Part A: running the 8-hour and 4-hour soaks, any fault or capture run beyond the AC19 smoke, and entering any figure in `soak-0.8.md`. Those are Part B.
- Editing `docs/plan/PLAN.md`; the scribe adds Part B at `/record`.
- Changing the stores or `docs/multiple-instances.md` (213); this task measures and records. Rotation under load and the sealing cost (rotation deferred with 229; sealing cost measured only for 225's maps, if at all, as a short note).
- Editing `docker/multi-instance/**`; the overlay lives under `load-test/soak/`.
- 100, 300 and 500-source scale evidence for 1.0 (0.9).
- Fixing defects the soak finds: each becomes a successor task named in the Outcome.

## Review round 1 — findings

Reviewed HEAD: 0f6aeb69058445f32ac9e310bc868df49030afb7. Sources: code-reviewer (CR-n) and the owner's pasted PR #243 review (243-n). Lows and Nits are follow-ups, not this round.

### From the code-reviewer
- CR-1 [P2] load-test/soak/compose.soak.yaml:23 — The overlay never gives the `investigator` role LIST_SOURCES or DESCRIBE_SOURCE, so about two thirds of the three-member mix is denied. Fix: set DATAPRISM_SECURITYPOLICY_ROLES_INVESTIGATOR_0..2 (GET_ENTITY_CONTEXT, LIST_SOURCES, DESCRIBE_SOURCE) in member-env; index 0 too, because the env list replaces the whole YAML list.
- CR-2 [P2] load-test/src/.../ResponseClassifier.java:44 — A follow refusal ("The link is not valid.", FollowTool.java:76, 276) is recorded as UNCLASSIFIED_ERROR, and soak-0.8.md criterion 5 lists SCOPE_READ_BUDGET as the only provoked refusal, so the single-node soak fails by construction. Fix: map FollowTool's fixed text to LINK_INVALID in classifyOther, and add the invalid-link refusal to soak-0.8.md:34–35.
- CR-3 [P2] load-test/soak/fault-hostile-member.sh:30 — Default /health is liveness and always 200, so "readiness went DOWN (503) during the flood" always says no. Fix: exit 3 "not run" unless HEALTH_PATH names readiness and HEALTH_BEARER is set. (Same as 243-11.)
- CR-4 [P2] load-test/soak/capture-member-port.sh:41-46 — Subject check only matches `<prefix>-NNNNNNNN`; the three-member run sends 1001/1002, shorter than strings -n 6, so AC8 is vacuous. (Same as 243-1.)

### From the owner's PR review
| ID | Sev | Sec | Where | Issue | Fix |
|---|---|---|---|---|---|
| 243-1 | High | sec | capture-member-port.sh:46 | Subject-id check greps subject-NNNNNNNN; runs send m-/warmup- ids or 1001/1002, so "no subject id in the clear" is vacuous | Grep the run's exact subject ids (grep -aF, no strings -n 6); add a positive control; void the PR's smoke claim |
| 243-2 | Medium | | LoadDriver.java:246 | New mixed dispatch and target-mode driving (classifier choice, absorb on throw, subject cycling) untested | Extract call body behind the ToolCall seam and test the three cases |
| 243-3 | Medium | | LoadTestMain.java:124 | Target-mode results record effectiveLimits and source counts never applied to the target | Write mode target and omit limits, or reject those fields with a target |
| 243-4 | Medium | | MixedCalls.java:124 | Invalid link starts AAAA, so version byte 0 is refused before key lookup and AEAD; costly path never exercised | Tamper a held link's ciphertext; test decoded raw[0] is VERSION |
| 243-5 | Medium | | ResultAggregator.java:87 | perTool keyed by MCP tool name: invalid-link refusals mix into follow, targeted into untargeted context | Key by operation (invalidLink, targetedEntityContext) |
| 243-6 | Medium | sec | capture-member-port.sh:34 | Unpinned third-party netshoot image runs with NET_ADMIN on the trusted cluster network | Pin by digest, drop NET_ADMIN, analyse with --network none |
| 243-7 | Medium | | common.sh:38 | Ready, rejoined and recovered judged by liveness; roll can kill next member before previous rejoins | Wait on cluster size or readiness before the next step; reword summaries |
| 243-8 | Medium | | compose.soak.yaml:100 | HAProxy servers have no check, resolvers or redispatch; dead or recreated members keep getting traffic | Add httpchk, Docker resolvers, redispatch |
| 243-9 | Medium | | fault-full-restart-key-change.sh:12 | New key generated and discarded; later scripts recreate members with the old key and hit CLUSTER_KEY_MISMATCH | Take NEW_*_KEY_MATERIAL from the owner and refuse without it |
| 243-10 | Medium | | fault-full-restart-key-change.sh:19 | Recreates discard member logs before the Resolving class count is taken | Count per member before each recreate, and after the run |
| 243-11 | Medium | | fault-hostile-member.sh:30 | DOWN is never seen under default /health; HEALTH_PATH and HEALTH_BEARER not required | Refuse without readiness path and bearer; record a 200 baseline; name both in README |
| 243-12 | Medium | | fault-network-partition.sh:19 | Reconnect drops the fixed IP that JOIN_MEMBERS lists; no trap on interrupt | Reconnect with --ip; restore in an EXIT trap |
| 243-13 | Medium | | soak-single-node.json:11 | AC13 open: no translated-link follow; three-member mix lacks timeouts, 100 sources, list_sources (refused) | Settled by Amendment 3 (owner decisions D1, D2) |
| 243-14 | Medium | | soak-three-member.json:41 | Two subjects in one 8 h case exhaust the default read budget (100) in minutes; soak measures refusals | More subjects or shorter scope lifetime; state expected budget-refusal share |
