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
- load-test/pom.xml *(system-scope dependencies and packaging for the harness `KeyResolver`, and the required `dataprism.version` property, only; added by the third amendment, 2026-11-11; scope corrected by the fourth; default removed by the fifth)*
- load-test/run.sh *(passing the reactor version to the load-test build, the missing-core-jar failure and a `--build-only` option, only; added by the fourth amendment, 2026-11-11; `--build-only` added by the fifth)*
- .github/workflows/supply-chain.yml *(the "Resolve the load-test tree to an SBOM" step only; added by the fifth amendment, 2026-11-11)*
- load-test/README.md *(the build commands at about :19 and :182 only; added by the fifth amendment, 2026-11-11)*

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

**D2 Translated links on the harness server.** The harness's own server gets a `KeyResolver`, and the single-node scenario follows translated links as well as direct ones. Recon: a `KeyResolver` is supplied only as a Spring bean (`LinkWiring.java:243-256` collects `ObjectProvider<KeyResolver>`; `dataprism.key-resolvers.*` sets bounds only, and no packaged module declares one). The server is a ZIP-layout jar that loads extension jars from `-Dloader.path` (`ServerProcess.java:57-69`), so the resolver is a harness-only auto-configuration under `load-test/src/**`, put on the harness server's `loader.path` beside the REST connector jar, with `SourceGenerator` declaring a second key namespace for it to translate into. `load-test/pom.xml` gains system-scope `data-prism-core` and Spring dependencies for it. `KeyResolver` is in no published release, so the core jar comes from the reactor build `run.sh` already performs (`-pl data-prism-server -am` builds `data-prism-core`); `mvn install` is not used. No production code under `data-prism-*/src/main` changes, and the packaged server and images are unchanged. *Reason:* AC13 and AC1 require translated-link follows, and the round found none (243-13).
*Not applied, pending owner:* the overlay members' resolver and translated-link follows in the three-member scenario. Overlay members run with links disabled because a joining member refuses links and a link key with `MISSING_SHARED_LINK_LIMITER` (AC1, AC3, AC14; decision (b), 2026-10-23), so a member `KeyResolver` would never be called and the three-member run can follow no link at all. Applying that half means reversing decision (b) or landing 264, which is deferred past 1.0.

**D3 Interval metrics from the driver.** The driver writes interval rows, hourly by default and set by a scenario field: per operation p50, p95 and p99, call count, refusals by code, and heap after GC and live threads where the driver can read them (the harness server's JVM; not the cluster members). The per-hour tables of `soak-0.8.md` are filled from these rows. *Reason:* AC2's per-hour record had no source in the harness (CR P3).

## Amendment 2026-11-11 (owner, fourth)
Owner decision on review finding R2-2 (review round 2, at the end).

**Decision.** Owns gains `load-test/run.sh`. After its reactor build, `run.sh` reads the reactor version from the root `pom.xml` and passes it to the load-test build (for example `-Ddataprism.version=<version>`). If the matching `data-prism-core` jar is missing, it fails with a clear message naming the expected jar. `.github/workflows/load-test.yml` (already owned) does the same wherever it builds load-test without `run.sh`. `load-test/pom.xml` keeps `system` scope for `data-prism-core`; the third amendment's "provided-scope" wording is corrected to system scope, because provided scope cannot resolve without `mvn install`, which is ruled out. A2's "`load-test/run.sh` (not in Owns)" no longer holds for this change only. *Reason:* a version hard-coded in `load-test/pom.xml` breaks the harness build on every version bump (R2-2).

## Amendment 2026-11-11 (owner, fifth)
Owner decision on R3-1 and R3-2 (review round 3, at the end): fail closed, and widen Owns.

**Decision.**
- `load-test/pom.xml` has no default for `dataprism.version`. A lifecycle build of it (`package`, `test`) without `-Ddataprism.version` stops in `validate` with a message naming the property and pointing at `load-test/run.sh`, for example through `maven-enforcer-plugin` `requireProperty`. It never resolves a stale jar. A goal invoked directly (such as `makeBom`) skips the lifecycle and so the enforcer, which is why every such invocation below passes the version.
- `run.sh` gains `--build-only`: it runs both builds and the missing-jar guard, then exits 0 without starting the driver. The usage line names it. The fourth amendment's guard stays, but is not gated on its own: `run.sh` rebuilds the jar before the guard, so its failure cannot be provoked through `run.sh` (R3-2).
- Owns gains the load-test step of `.github/workflows/supply-chain.yml`. It passes `-Ddataprism.version` from the reactor version, obtained with `help:evaluate -Dexpression=project.version` as the step already obtains the plugin version. 257, the previous owner of the workflows, is retired.
- Owns gains the build commands in `load-test/README.md`. They pass `-Ddataprism.version=<reactor version>`, or say to use `load-test/run.sh --build-only`. A5's "`load-test/README.md` is not in Owns" still holds for everything else in that file.

**Every invocation that builds `load-test/pom.xml`** (repo-wide grep, 2026-11-11, outside `target/` and `HISTORY.md`):
| Where | Passes the version? | Action |
|---|---|---|
| `load-test/run.sh:25` | yes | none (already owned) |
| `.github/workflows/supply-chain.yml:51-56` (`makeBom`) | no | add, newly owned |
| `load-test/README.md:19` (`package`), `:182` (`test`) | no | add, newly owned |
| `.github/workflows/load-test.yml:49` | through `run.sh` | none |
| `load-test/soak/README.md:42` | through `run.sh` | none (already owned) |
| `.github/dependabot.yml:26` (`/load-test`) | parses, does not build | none, not owned |
| this file's `harness-build` check | no | fixed below |

**Overlap.** No open task owns `supply-chain.yml` or `load-test/README.md`. The `load-test/src/**` overlap with 273 and 275 is unchanged (second amendment).

*Reason:* a default lets a build without the version pick up a stale jar, or fail later with an unclear message. Two build paths outside Owns (R3-1) and an unobservable AC24 half (R3-2) prove it. *Size:* this brief is over 30 KB because it carries five amendments and three review rounds. Folding them into one contract is left for the next re-plan.

## Amendment 2026-11-11 (owner, sixth)
Owner decisions on R5-1 and R5-3 (review round 5, at the end).

**R5-1: always build the branch.**
- `load-test/soak/compose.soak.yaml` gives each of `server-a`, `server-b` and `server-c` its own `build:` from the checkout, with `pull_policy: build` (or an equivalent that can never pull or reuse a tag). `docker/multi-instance/**` stays unchanged (AC14).
- `load-test/soak/common.sh` builds by default: `SOAK_BUILD` defaults to 1, or the knob is removed.
- Every fault, probe and capture summary, and the driver's target-mode result, records the member image ID and the git commit (short SHA and a dirty flag).
- `load-test/soak/README.md` documents the build rule and the provenance fields.
- Checkable: bringing the overlay up never pulls the server image, and the summaries carry image ID and commit (AC25).

**R5-3: criterion 3.** `soak-0.8.md` pass criterion 3 fails the run on any `FAILED` or `RESOLVER_FAILED` source status. Its cause wording matches what the harness records: source statuses by source, and transport failures in `failureCauses`. AC2 and AC17 are worded to match.

**Provenance of earlier smokes.** The overlay smokes of rounds 2 to 4 ran images of unknown provenance. Part A's AC19 overlay smoke must be re-run under the new build rule.

*Reason:* a stale local tag was reused silently and nothing recorded which image ran (R5-1); `RESOLVER_FAILED` passed every criterion, and the cause criterion asked for an entry the harness never writes (R5-3).

## Amendment 2026-11-11 (owner, seventh)
Owner adopted the architect's design for R6-1..R6-6 (review round 6, at the end), choosing: a dirty snapshot is refused unless `SOAK_ALLOW_DIRTY=1` (smokes opt in and stay marked dirty). **Supersedes the sixth amendment's R5-1 building bullets** (its R5-3 decision and the AC19 re-run stand). **Owns is unchanged.** AC14 and AC20 hold.

**Invariants.** I1 every data-prism container (server-a/b/c, cert-init, issuer, fixtures) runs an image built from one identified source state. I2 identity is fixed at bring-up. I3 recreates reuse exactly the bring-up image ID: no build, no pull. I4 no build inside a measured window. I5 every summary and result records the identity running when written. I6 unknown is recorded as `unknown`, never a plausible value. I7 third-party images pinned (haproxy by digest; netshoot already is). I8 kill/start/restart/disconnect keep container and image.

1. **`load-test/soak/build-images.sh` (new).** Snapshot `git ls-files -co --exclude-standard` (skip tracked-deleted) into a temp dir; ignored paths never enter; no `.dockerignore` change. Content hash over path+contents; record commit and dirty (snapshot differs from HEAD); git failure = refuse. Dirty = refuse unless `SOAK_ALLOW_DIRTY=1`. Plain `docker build` of server, issuer, fixtures, certs-init with the existing `docker/*/Dockerfile`; tag `data-prism-soak-<image>:<hash12>`; labels for source hash, commit, dirty. Existing tag = reuse. Writes a stamp under the git-ignored results dir: tag, four image IDs, hash, commit, dirty. Runs before any driver starts.
2. **`compose.soak.yaml`.** No `build:`. The six data-prism services use `data-prism-soak-<image>:${SOAK_IMAGE_TAG:?run load-test/soak/build-images.sh}` with `pull_policy: never`, merged onto base services; `docker/multi-instance/**` unchanged. `lb` (haproxy) pinned by digest.
3. **`common.sh`.** `dc` exports `SOAK_IMAGE_TAG` from the stamp, never the caller's shell; refuses if the stamp is missing or a tag's image ID differs from it; recreates pass `--no-build`. `member_image_id` uses `docker ps` by compose project/service labels (no compose interpolation) and distinguishes `absent` (no container) from `unknown` (docker call failed). `provenance` prints each of the six containers' image ID and build labels (not the live checkout commit). Every script calls it at start and on exit (trap); a start/end mismatch, or any running ID differing from the stamp, is printed as a finding.
4. **`member-image-id.sh`.** Prints member build identity from the running members; any lookup failure prints `unknown` and exits non-zero; never prints `none/none/none` as agreement.
5. **Driver (`LoadTestMain`/`ResultWriter`).** In target mode, at result-write time run `member-image-id.sh` via `boundedOutput` (failure → `unknown`) and record it as the member build. Drop the `SOAK_MEMBER_IMAGE_ID` env read and `unrecorded`; update `ResultWriterTest`. Checkout commit/`workingTreeDirty` stay, documented as the driver's own checkout.
6. **README.** Build first, then up via the stamp; provenance fields; `unknown`/`absent`; `SOAK_ALLOW_DIRTY`.

AC25 and the `overlay-builds` check are rewritten in place. The AC19 overlay smoke is re-run under this rule. *Verify by running:* Compose lets the overlay set `image`/`pull_policy` on services the base pulls in via `extends`.

*Reason:* `pull_policy: build` rebuilt inside measured windows and on recreates, provenance was captured before faults, and compose/git failures were recorded as `none` or clean (R6-1..R6-6).

## Acceptance
IDs AC1 to AC12 are the pre-split items in their original order. AC13 onwards are new in Part A.

### Part A (agent)
- [ ] AC11 The new soak scenarios and 258's failure-path scenarios are in the `load-test.yml` choice list. Soak entries that live under `load-test/soak/` are listed as paths relative to `load-test/scenarios/` (for example `../soak/<file>.json`), because the workflow runs `load-test/run.sh "load-test/scenarios/${SCENARIO}"`.
- [ ] AC13 Scenario files under `load-test/soak/`: a single-node mix (100 sources, links enabled, a fixed rate, at least 8 hours, with the AC1 mix of `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links (D2), invalid links, timeouts and budget exhaustion) and a three-member mix (the same mix for at least 4 hours through a load balancer, targeting the overlay's load balancer per A2, with no `follow`, no page link and no source timeouts: timeouts are single-node only; budget exhaustion comes from the read budget the overlay sets on the members, D1). The mix is set by scenario fields the driver executes (A1), not by comments. Each run writes the D3 interval rows: per operation p50, p95 and p99 and call count, refusals by code, and heap after GC and live threads where available, at a scenario-set interval defaulting to one hour. Each one loads and runs through `load-test/run.sh`.
- [ ] AC14 A Compose overlay under `load-test/soak/`, applied on top of `docker/multi-instance/compose.yaml`, brings up three members behind a load balancer with `trusted-network: true`, a private `member.interface`, links disabled and no link key. `docker/multi-instance/**` is unchanged.
- [ ] AC15 Fault-injection scripts under `load-test/soak/` cover every Part B fault: kill and restart a member, kill a partition owner and its backup together, roll all members, a planned full restart for the cluster-key and name-key change, a hostile member (tamper flood), a network partition and clock skew on one member. They also cover the AC3 startup probes (`dataprism.links.enabled=true`, and a link key configured). Each script writes counts and findings only, never an address, a payload byte or a key. The hostile-member and clock-skew scripts take `TAMPER_FLOOD_CMD` and `FAKETIME_LIB` from the owner and refuse to run without them; `load-test/soak/README.md` says so (A5).
- [ ] AC16 A packet-capture script under `load-test/soak/` captures the member port for a named transport profile. It writes raw captures only under `load-test/results/` (git-ignored) or outside the repository, and its summary holds counts and findings only: whether plaintext appears and whether a subject id appears in the clear. It never holds an address, a payload byte or a key.
- [ ] AC17 `docs/plan/perf/soak-0.8.md` exists as a template. The pass criteria of AC2, AC5, AC6, AC7 and AC8 are written out in advance, and the 221 "Resolving class ... is not allowed" criterion is included. Criterion 3 is worded as AC2 states it (sixth amendment). Every Part B measurement has an empty result table: per-hour metrics, the AC3 refusals, the cluster cost, the faults, the key change, hostile member, partition and skew, captures per profile and platform, isolation claims, and `LinkTimingSoakTest`. A machine-specification section holds no hostname, address, user name or workplace detail. No result cell is filled in.
- [ ] AC18 A script under `load-test/soak/` runs `LinkTimingSoakTest` with `-Ddataprism.timing=true` and prints its result and tolerance in the form the AC10 table takes.
- [ ] AC19 Each scenario (AC11, AC13) is run once as a smoke run of a few minutes. The overlay (AC14) and each script (AC15, AC16, AC18) are smoked for real if Docker is available; otherwise the Outcome says they had `bash -n` only and are left to Part B (A4). The Outcome records what was run, how long it ran and whether it worked, under the AC12 rule, and labels it "harness smoke, not soak evidence". No smoke figure goes into `soak-0.8.md`. Overlay smokes before the sixth amendment do not count: the overlay smoke is re-run under the AC25 build rule.
- [ ] AC20 No file outside Owns is changed.
- [ ] AC21 `load-test.yml` meets A3: the artifact name has no scenario path in it, and `timeout-minutes` exceeds the longest soak scenario's duration.
- [ ] AC22 (D1) A target-mode scenario that sets `sources`, `slowSources`, `slowLatency` or `limits` is refused at load, naming the field; a target-mode result has `mode: target` and no `effectiveLimits` or source counts. The overlay's `member-env` sets the scope read budget and per-source timeout, and the three-member scenario's subject count and the expected `SCOPE_READ_BUDGET` share are stated in `load-test/soak/README.md`, the share being below the whole run.
- [ ] AC23 (D2) With the harness's own server, a `follow` of a translated link reaches the resolver's target namespace: a load-test module test shows the generated sources declare both namespaces, the resolver is on the server's `loader.path`, and the single-node scenario's mix includes translated follows. Nothing under `data-prism-*/src/main` or `docker/**` changes.
- [ ] AC25 (amendment 7) Every data-prism image the overlay runs (members and cert-init/issuer/fixtures) comes from one build of a snapshot of the checkout, tagged by the snapshot's content hash; the overlay has no `build:` and uses `pull_policy: never`; no up or recreate builds or pulls, and a missing tag fails; a tag is reused only for identical snapshot content; a dirty snapshot is refused unless `SOAK_ALLOW_DIRTY=1`; the `lb` image is pinned by digest; every summary records each container's image ID and build labels at start and end, and the driver's target-mode result records them as read at write time; anything unreadable is `unknown`.
- [ ] AC24 (amendments 4, 5) (i) After `versions:set` to another version, `load-test/run.sh --build-only` exits 0 with no edit to `load-test/pom.xml`. (ii) `./mvnw -B -f load-test/pom.xml package` without `-Ddataprism.version` exits non-zero, and its output names `dataprism.version` and `run.sh`. (iii) The supply-chain load-test step and the `load-test/README.md` build commands pass the version or use `run.sh`.

### Part B (owner-run, release cut)
Executed on a dedicated host, never on a shared CI runner. Carried in PLAN.md's 0.8.0 release-cut obligations once Part A lands.
- [ ] AC1 Single node: a run of at least 8 hours at a fixed rate, 100 sources, links enabled, mixing `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links, invalid links, timeouts and budget exhaustion. Three members: the same mix for at least 4 hours through a load balancer, using the overlay under `load-test/soak/` and not editing `docker/multi-instance/**`, except that links are disabled and no link key is configured, so there is no `follow` and no page link (a joining member refuses both with `MISSING_SHARED_LINK_LIMITER`). The overlay sets `trusted-network: true` and a private `member.interface`.
- [ ] AC2 `soak-0.8.md` records per hour: p50, p95 and p99 per tool, throughput, every `FAILED` and `RESOLVER_FAILED` source status by source and every transport failure in `failureCauses` (sixth amendment), heap after GC, live threads, open file handles, audit bytes written, and the budget counters against the calls made. These rows come from the driver's interval rows (D3, AC13), plus the host's own measurements where the driver cannot read a figure. It states pass or fail against criteria written before the run: no upward trend in heap after GC or threads over the last half of the run, no `FAILED` or `RESOLVER_FAILED` source status, budget counters equal to reads made, and no privacy refusal other than those the scenario provoked.
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
  run: `./mvnw -B -q -pl data-prism-server,data-prism-connectors-rest -am -DskipTests package && ./mvnw -B -q -f load-test/pom.xml -Ddataprism.version="$(./mvnw -B -q -DforceStdout help:evaluate -Dexpression=project.version)" package`
  (this runs the load-test module's own tests; new driver behaviour for A1 and A2 gets tests there)
- `scripts-parse` command — AC15, AC16, AC18
  run: `for f in load-test/soak/*.sh; do bash -n "$f" || exit 1; done`
- `overlay-config` command — AC14
  run: `export DATAPRISM_CLUSTER_KEY_MATERIAL=$(openssl rand -hex 32) DATAPRISM_NAME_KEY_MATERIAL=$(openssl rand -hex 32); SOAK_IMAGE_TAG=dummy docker compose -f docker/multi-instance/compose.yaml -f load-test/soak/compose.soak.yaml config -q`
- `overlay-builds` command — AC25
  run: `export DATAPRISM_CLUSTER_KEY_MATERIAL=$(openssl rand -hex 32) DATAPRISM_NAME_KEY_MATERIAL=$(openssl rand -hex 32); SOAK_IMAGE_TAG=dummy docker compose -f docker/multi-instance/compose.yaml -f load-test/soak/compose.soak.yaml config --format json | jq -e '([.services["server-a","server-b","server-c","cert-init","issuer","fixtures"] | (.build == null) and (.pull_policy == "never") and (.image | startswith("data-prism-soak-"))] | all) and (.services.lb.image | contains("@sha256:"))'`
- `provenance` read — AC25
  how: `load-test/soak/build-images.sh` builds the four images from a snapshot of the checkout and writes the stamp file (tag, image IDs, hash, commit, dirty); `common.sh` takes `SOAK_IMAGE_TAG` from the stamp, never builds, and refuses on a missing stamp or ID mismatch; each fault, probe and capture summary records each of the six containers' image ID and build labels at start and end; the driver's target-mode result records the member build as read at write time; lookup failure is `unknown`, no container is `absent`; `load-test/soak/README.md` documents the build step, the fields and `SOAK_ALLOW_DIRTY`.
- `multi-instance-untouched` command — AC14, AC20, AC23
  run: `git diff --exit-code origin/main...HEAD -- docker data-prism-*/src/main docs/plan/PLAN.md`
- `link-timing` test — AC18
  run: `./mvnw -B -q -pl data-prism-orchestration -am -Dtest=LinkTimingSoakTest -Dsurefire.failIfNoSpecifiedTests=false -Ddataprism.timing=true test`
  report: `data-prism-orchestration/target/surefire-reports/TEST-*LinkTimingSoakTest.xml`
  min-passing: 1  max-skipped: 0
- `version-bump` command — AC24 (i), (ii)
  run (i), in a disposable worktree, never this branch: `./mvnw -B -q versions:set -DnewVersion=0.0.0-vcheck -DgenerateBackupPoms=false && load-test/run.sh --build-only && git diff --exit-code -- load-test/pom.xml`. The build step's exit is the check; no scenario runs.
  run (ii), on this branch: `if out="$(./mvnw -B -f load-test/pom.xml package 2>&1)"; then exit 1; fi; grep -q 'dataprism.version' <<<"$out" && grep -q 'run.sh' <<<"$out"`
- `version-passed` read — AC24 (iii)
  how: the supply-chain load-test step passes `-Ddataprism.version` from `help:evaluate -Dexpression=project.version`; each `load-test/README.md` build command passes it or uses `run.sh`.
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

## Review round 2 — findings

Reviewed HEAD: ac67b8834aefbb9839c3aa4b575b6d7204c5bcd4 (code-reviewer re-review of round 1). P3s are follow-ups, not this round.

- R2-1 [P2] load-test/soak/capture-member-port.sh:43-47 — With `--cap-drop ALL` and `-Z root` dropped (ac67b883), the capture sidecar cannot write its pcap into the host results directory on a rootful Linux Docker engine (owner-owned load-test/results, mode 755; no CAP_DAC_OVERRIDE). `|| true` hides the tcpdump error; the script exits 3 "capture: no packets recorded" in every profile, so AC8 cannot run on the platform the cluster soak requires. Fix inside the script, keeping 243-6 (pinned digest, no NET_ADMIN): e.g. capture to the container's /tmp and `docker cp` it out, or a dedicated writable capture subdirectory. Surface tcpdump's error instead of `|| true`.
- R2-2 [P2] load-test/pom.xml:34,66-69 — load-test compiles against `../data-prism-core/target/data-prism-core-0.7.0.jar` (system scope, hard-coded version); the release-cut `versions:set` does not touch load-test/pom.xml, so at 0.8.0 every scenario fails to build on a clean checkout, or silently compiles against a stale jar. Settled by Amendment 4 (owner): run.sh passes the reactor version.

## Review round 3 — findings

Reviewed HEAD: 3739198f58e283689cdbcde0458b14131bc54eec (code-reviewer re-review of round 2; R2-1 and R2-2 FIXED).

- R3-1 [P2] .github/workflows/supply-chain.yml:52-56 and load-test/README.md:19,182 — These build load-test without -Ddataprism.version; after versions:set 0.8.0 the supply-chain "Resolve the load-test tree" step (`-f load-test/pom.xml … makeBom`) uses the default 0.7.0, the systemPath does not exist, and the scan fails (or silently builds against a stale 0.7.0 jar). Settled by Amendment 5 (owner).
- R3-2 [P2] task file AC24 and its `version-bump` check — the missing-jar half cannot be observed through run.sh, which rebuilds the jar before its guard. Settled by Amendment 5 (owner).
- R3-3 [STYLE] load-test/pom.xml:31 — rewritten comment line is 186 characters; the rest of the block wraps at ~117. Rewrap.

## Review round 4 — findings

Reviewed HEAD: 162d4571ad4255cc9bef68c2b43563bc22ef99c9. Source: the owner's PR #243 review, round 2. Owner approved a fourth round for these two only; its Lows and Nits are follow-ups.

| ID | Sev | Where | Issue | Fix |
|---|---|---|---|---|
| 243-28 | High | probe-startup-refusals.sh:30 | Calls new_key, which round 1 removed from common.sh; set -e exits after probe 1, server-c left refusing, AC3 key probe never runs | Generate inline (openssl rand -hex 32) or restore new_key; EXIT trap restores server-c |
| 243-29 | Medium | LoadDriver.java:328 | follow and followTranslated go through classifyOther, so a source or resolver failure inside a follow counts as completed | Classify follow ops with classify; test a RESOLVER_FAILED follow |

## Review round 5 — findings

Reviewed HEAD: f46b3df693a195c3ae4e5601589532c7c588ef9d (code-reviewer re-review of round 4; 243-28 and 243-29 FIXED). Owner approved a fifth round.

- R5-1 [P2] load-test/soak/compose.soak.yaml:10,50-53; load-test/soak/common.sh:16-20 — The overlay never ensures members run the branch's build and nothing records which image ran. All three members use ghcr.io/aindriub/data-prism-quickstart-server:${QUICKSTART_IMAGE_TAG:-0.8.0}; compose.build.yaml builds server-a/b only; common.sh defaults SOAK_BUILD=0; SOAK_BUILD and QUICKSTART_IMAGE_TAG are undocumented; 0.8.0 is unpublished. A stale local :0.8.0 tag is reused silently under the default pull policy. Settled by Amendment 6 (owner).
- R5-2 [P3, owner-approved for this round] load-test/soak/probe-startup-refusals.sh:14,43 — On success server-c is force-recreated twice (explicit restore, then the uncleared EXIT trap); the :42 comment is wrong. Fix: `trap - EXIT` after the explicit restore and correct the comment.
- R5-3 [P3] docs/plan/perf/soak-0.8.md:31 — Criterion 3 names only FAILED; RESOLVER_FAILED passes every criterion, and the cause entry it asks for cannot exist for a FAILED source (failureCauses holds transport failures only). Settled by Amendment 6 (owner).

## Review round 6 — findings

Reviewed HEAD: 8e39e4a116dbe8477cf9c3680869fab23b5296b0 (code-reviewer re-review of round 5; R5-2 and R5-3 FIXED). Second freshness/provenance round in the same subsystem triggered a design pass; all five are settled by Amendment 7 (owner).

- R6-1 [P2] compose.soak.yaml:48-66 — pull_policy: build rebuilds on every up, including mid-soak recreates (clock-skew:23, key-change:37, probe:15,22); results/ in the build context busts the cache; full Maven build inside the measured window; recreated member can get a new image ID or run edited code.
- R6-2 [P2] common.sh:50-54 — provenance written before the script recreates members, so summaries name pre-fault images.
- R6-3 [P2] member-image-id.sh:9, common.sh:31-38 — compose interpolation error (keys unset) swallowed, prints `none`, recorded as memberImageId "none".
- R6-4 [P2] LoadTestMain.java:126, README.md:27-35 — stale SOAK_MEMBER_IMAGE_ID export recorded as fact beside a fresh commit.
- R6-5 [P2] README.md:21, compose.soak.yaml:7-11 — cert-init, issuer, fixtures still pulled at unpublished :0.8.0 or reused stale.
- R6-6 [NOTE→fixed by Amendment 7] common.sh:44 — git_commit reports dirty=no when git status fails.

## Attempt 1 (verify) — failed

/verify at 8b4cd1a5161741582eded0a8f360c9344cba0d7a, 2026-11-11: tester PASS (all command checks, full `./mvnw -B test`), reviewer REQUEST CHANGES on AC19 only.

- AC19: the PR #243 Outcome does not mark the round 5 overlay smoke superseded (built under amendment 6's pull_policy: build, replaced by amendment 7). Under amendment 7 only the three-member scenario, probe-startup-refusals.sh, fault-full-restart-key-change.sh and member-image-id.sh have run. kill-restart, kill-owner-and-backup, roll-all, network-partition and capture last ran in superseded rounds 1–4; partition and capture had their cleanup moved onto on_exit in round 6 and have not run since. The Outcome does not say which scripts are left to Part B.
  Fix: re-smoke kill-restart, kill-owner-and-backup, roll-all, network-partition and capture (SUBJECT_IDS=1001,1002) under the amendment 7 build rule; mark the round 5 overlay smoke superseded; state that fault-hostile-member.sh and fault-clock-skew.sh (owner inputs) are left to Part B.
- Check text (orchestrator): `overlay-config` and `overlay-builds` fail as written — they need SOAK_IMAGE_TAG set (amendment 7's `:?` guard) and DATAPRISM_CLUSTER_KEY_MATERIAL / DATAPRISM_NAME_KEY_MATERIAL exported (throwaway values). Reworded in this commit.
