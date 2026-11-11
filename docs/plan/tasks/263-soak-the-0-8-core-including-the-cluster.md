# 263 — Soak the 0.8 core on one node and on a three-member cluster (Part B, owner-run)

**Repo:** `.`
**Release:** 0.8.0 (evidence; a release-cut obligation)
**Executor:** the owner, on a dedicated host. Not a shared CI runner, not an agent.
**Wave:** 13. Part A landed 2026-11-11 (PR #243, merge `95cfee34`); this file is the Part B contract only.
**Owns:** `docs/plan/perf/soak-0.8.md` (fill in the template; no other file). Defects the soak finds become successor tasks, not edits here.

Part A's brief, its seven amendments, six review rounds and the failed first verify are kept verbatim in
`docs/plan/archive/TASK-263-2026-11-11-part-a.md`. Read it only if a harness decision needs its reason; the Outcome of PR #243 is the record of what was smoked.

## What the owner runs

Everything lives under `load-test/soak/`; `load-test/soak/README.md` is the manual. In order:

1. Commit everything. Run `load-test/soak/build-images.sh` from a clean committed snapshot. It refuses a dirty tree unless `SOAK_ALLOW_DIRTY=1`; a soak run for evidence never sets it. Every summary and the driver's target-mode result record the image IDs and build labels; keep the stamp file with the results.
2. Export the two keys the overlay needs (`DATAPRISM_CLUSTER_KEY_MATERIAL`, `DATAPRISM_NAME_KEY_MATERIAL`) and use the same two for every script of one stack.
3. Single node: `load-test/run.sh load-test/soak/soak-single-node.json` (at least 8 hours, 100 sources, links enabled, full mix).
4. Three members: bring the overlay up (`compose.soak.yaml` on `docker/multi-instance/compose.yaml`), then `load-test/run.sh load-test/soak/soak-three-member.json` (at least 4 hours, through the load balancer; no `follow`, no page link, no source timeouts). The target needs a role that holds `list_sources` and `describe_source`, and the issuer's truststore passed through `JAVA_TOOL_OPTIONS` (README).
5. Under the three-member load: `fault-kill-restart-member.sh`, `fault-kill-owner-and-backup.sh`, `fault-roll-all-members.sh`, `fault-network-partition.sh`, `fault-full-restart-key-change.sh` (cluster key, then name key), `probe-startup-refusals.sh`, and `capture-member-port.sh` once per transport profile of the deployment guide and per supported platform.
6. `link-timing.sh` (runs `LinkTimingSoakTest` with `-Ddataprism.timing=true`).

## Inputs only the owner can supply

- `fault-hostile-member.sh`: `TAMPER_FLOOD_CMD` (the flood command), `HEALTH_PATH` (a readiness path such as `/actuator/health/readiness`) and `HEALTH_BEARER` (a token it accepts). It exits 3 without them.
- `fault-clock-skew.sh`: `FAKETIME_LIB` (a libfaketime build for the member image). It exits 3 without it.
- `capture-member-port.sh`: `SUBJECT_IDS` (the fixture subject ids to look for in the clear).

## Pass criteria

Written before the run in `docs/plan/perf/soak-0.8.md` ("Pass criteria, written before the run"); do not edit them after a run starts. In summary: no upward trend in heap after GC or live threads over the last half of each run; no `FAILED` or `RESOLVER_FAILED` source status; budget counters equal to reads made; no privacy refusal other than those the scenario provoked; no member logs "Resolving class ... is not allowed"; both startup probes refuse with `MISSING_SHARED_LINK_LIMITER`; faults, key change, hostile member, partition and skew behave as the template's AC5 to AC7 rows state; plaintext on the member port is expected in every profile except E3, and no subject id appears in the clear.

Notes for reading results: G1 "heap after GC" can look flat below the initiating-occupancy threshold, so a flat line is not by itself a pass; a `LINK_INVALID` on `follow` after a scope rotation should be split per operation before it fails criterion 5.

## Acceptance (Part B)
- [ ] AC1 to AC8 and AC10: every table in `soak-0.8.md` filled from the runs above, each with pass or fail against the written criteria.
- [ ] AC9 Each failed criterion is filed as a successor task and named in the Outcome. None is waived in `soak-0.8.md`.
- [ ] AC12 The machine is described by specification only: no hostname, address, user name or workplace detail. Capture files and the stamp are not committed; the record holds counts and findings only, never an address, a payload byte or a key.
- [ ] Every figure comes from a run whose stamp names a clean snapshot (`dirty=no`) and one commit.

## Checks
- `soak-record` read — AC1 to AC12
  how: `docs/plan/perf/soak-0.8.md` has no empty result cell; its machine section has the specification only; the commit in it matches the stamp; each failed criterion names its successor task.

## Out of scope
Rotation under load (deferred with 229); sealed-entry cost (deferred with 224 to 226); 100, 300 and 500-source scale evidence for 1.0 (0.9); fixing defects the soak finds.
