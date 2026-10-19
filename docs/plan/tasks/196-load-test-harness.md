# 196 — Build a load-test harness that drives many sources through the real server

**Repo:** .
**Branch:** from `plan/0.7.0` after every task in **Depends on** has merged there; use `main` once the integration branch has merged.
**Depends on:** 189 (limits raised through configuration), 190 (the per-source response shape it tallies)
**Decided (owner, 2026-10-09):** D-070-10 (a): a Java driver on the MCP Java SDK
client with HdrHistogram, in a standalone `load-test/` outside the reactor.
**Owns:**
- load-test/** (new, outside the Maven reactor; not listed in the root `pom.xml`)
- .github/workflows/load-test.yml (new, `workflow_dispatch` only)
- docs/plan/tasks/196-load-test-harness.md (Outcome section only)

## Goal
1.0 needs load and soak evidence at 100, 300 and 500 sources and thousands of
items per investigation. Nothing measures throughput or latency today. This
task builds the harness: it generates N synthetic sources, starts the real
server against them over streamable HTTP with authentication, drives
`get_entity_context` at a configured rate and concurrency, and writes a
machine-readable result. Task 199 takes the first baseline with it.

## Context
- `compose.yaml` and `docker/**` — the quickstart stack (issuer, fixtures,
  server) shows how a real server runs with a token issuer and HTTP sources.
- `data-prism-quickstart-fixtures` and `data-prism-quickstart-issuer` — the
  fixture source and token issuer to reuse or copy the pattern of.
- `docs/configuration.md` (configured JSON sources, `dataprism.json-sources.config-location`)
  and the `dataprism.limits.*` keys from task 189.
- `data-prism-architecture`'s `ArchitectureCoverageTest` requires every reactor
  module with main code to be on its dependency list; keeping the harness
  outside the reactor avoids that and keeps the release build unchanged.
- D-070-10 (a): the driver uses the MCP Java SDK client (the real protocol
  path agents use) and HdrHistogram, built by its own `load-test/pom.xml`
  against the reactor's installed snapshot.
- Fixtures use an invented neutral domain (for example parcels moving between
  depots); every value is obviously synthetic and identifier-shaped values use
  documented invalid check digits (`docs/conventions.md`, privacy rules).

## Acceptance
Write the harness's own unit tests first.
- [ ] Unit tests: the source generator produces exactly N source definitions
      with distinct names and a stated per-source latency distribution; the
      result aggregator computes p50/p95/p99/max from a known sample exactly;
      per-source status counts (all seven codes, including `REFUSED`) and refusal
      codes are tallied from canned responses.
- [ ] One documented command (in `load-test/README.md`) builds the server from
      the checkout, starts the stack, runs a scenario file and writes
      `results/<scenario>-<timestamp>.json` with: commit, JDK, host description,
      scenario parameters, effective `dataprism.limits.*`, calls attempted and
      completed, latency percentiles, throughput, refusal codes by count, and
      per-source status counts.
- [ ] Scenario files exist for 8, 32, 100 and 300 sources (the 500-source and
      soak scenarios are 0.8/0.9 work but the format must allow them).
- [ ] A smoke run of the 8-source scenario completes on the implementer's
      machine; its result file is shown in the Outcome (not committed).
- [ ] `load-test.yml` runs a chosen scenario on `workflow_dispatch` and uploads
      the result as an artifact; `permissions: contents: read`; `actionlint` exits 0.
- [ ] `./mvnw -B verify` of the reactor is unaffected (root `pom.xml` unchanged).
- [ ] No credential, token or key is committed; the issuer keys are generated
      at run time.

## Out of scope
- Taking or recording the baseline (task 199).
- Soak runs, cluster runs (0.8).
- Any change to server, orchestrator or limits code.
