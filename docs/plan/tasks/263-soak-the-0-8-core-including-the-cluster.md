# 263 — Soak the 0.8 core on one node and on a three-member cluster

**Repo:** `.`
**Release:** 0.8.0 (evidence)
**Executor:** the owner, or an agent on a dedicated host; not a shared CI runner.
**Base branch:** the 0.8 integration branch once every dependency has merged
**Depends on:** 258 (soak mode), 252 (`LinkTimingSoakTest`), 255 (links and catalog wired), 257 (257 owns `.github/workflows/*.yml`), and the sealed-cluster tasks that make a three-member sealed cluster run (ids to be named by the coordinator)
**Owns:**
- load-test/soak/** *(new: scenario files and a Compose overlay for the three-member run)*
- docs/plan/perf/soak-0.8.md *(new)*
- .github/workflows/load-test.yml *(the scenario choice list only)*

## Goal
The roadmap's 0.8 readiness includes a soak, including the cluster. Run the 0.8 core for hours, on a single node and on a sealed three-member cluster, with the agent-led surface in use (catalog pages, `_links`, `follow`, targeted and untargeted calls) and the failure paths of 258 mixed in, and record whether latency, memory, budget accounting and audit volume stay stable. Run the link timing test that sealed-links obligation 10 asks for.

## Context
- docs/plan/design/sealed-links.md section 7 item 10 (the statistical timing test) and docs/plan/design/batch-and-bulk-follow.md section 8 item 28 (scale evidence belongs to 0.9; this is the 0.8 soak).
- load-test/README.md after 258: soak mode, token and scope rotation.
- docs/plan/perf/baseline-0.7-quiet.md (259): the figures to compare against.
- The sealed-cluster soak in `cluster-security.md` 5.2 (rotation under load, pause propagation) belongs to the cluster plan; this task does not repeat it.

## Acceptance
- [ ] Single node: a run of at least 8 hours at a fixed rate, 100 sources, links enabled, mixing `list_sources` walks, `describe_source`, targeted and untargeted `get_entity_context`, `follow` of direct and translated links, invalid links, timeouts and budget exhaustion. Three members: the same mix for at least 4 hours through a load balancer, using the overlay under `load-test/soak/` and not editing `docker/multi-instance/**`.
- [ ] `soak-0.8.md` records per hour: p50, p95 and p99 per tool, throughput, every FAILED cause, heap after GC, live threads, open file handles, audit bytes written, and the budget counters against the calls made. It states pass or fail against criteria written before the run: no upward trend in heap after GC or threads over the last half of the run, no FAILED without a cause, budget counters equal to reads made, and no privacy refusal other than those the scenario provoked.
- [ ] `LinkTimingSoakTest` is run on the soak host and its result, with the tolerance, is in `soak-0.8.md`.
- [ ] The new soak scenarios and 258's failure-path scenarios are in the `load-test.yml` choice list.
- [ ] No hostname, address, user name or workplace detail is recorded; the machine is described by its specification only.

## Out of scope
- Key rotation under load and pause propagation (sealed-cluster soak).
- 100, 300 and 500-source scale evidence for 1.0 (0.9).
- Fixing defects the soak finds: each becomes a successor task named in the Outcome.
