# Load-test harness

Drives `get_entity_context` through the real `data-prism-server` against N
generated synthetic sources, over streamable HTTP with authentication, and
writes one machine-readable result file. It is a standalone Maven project, not a
module of the root reactor: the root `pom.xml` does not list it, so the release
build and the architecture coverage test are unchanged.

## Run a scenario

From the repository root, with JDK 21 or later (25 matches the images):

    load-test/run.sh load-test/scenarios/sources-8.json

That one command

1. builds `data-prism-server` and `data-prism-connectors-rest` from this checkout
   (`./mvnw -pl data-prism-server,data-prism-connectors-rest -am -DskipTests package`),
2. builds the harness and runs its unit tests (`./mvnw -f load-test/pom.xml package`),
3. starts the stack, drives the scenario and writes
   `load-test/results/<scenario>-<timestamp>.json` (git-ignored).

The same GitHub Actions run is the `load-test` workflow (`workflow_dispatch`
only): pick a scenario and download the result artifact.

## What the stack is

Everything runs on loopback in one run, and is stopped when the run ends:

| Part | What it is |
|---|---|
| Synthetic sources | One HTTPS listener serving `/<source>/parcels/<subject>` for every generated source. The invented domain is parcels moving between depots. Every value is synthetic (`example.invalid` addresses, numbered names); nothing identifier-shaped is generated. Each source answers after a latency sampled from the scenario's stated band. |
| Token issuer | An RSA key generated at run time, its JWKS served over HTTPS, and RS256 tokens minted for one caller (`investigator`, purpose `investigation`). A throwaway certificate comes from `keytool`; the server trusts only that certificate. No key, token or certificate is committed. |
| Server | The packaged `data-prism-server` jar with the configured-JSON-sources extension on `-Dloader.path`, `dataprism.json-sources.config-location` naming the generated catalogue, and the scenario's `dataprism.limits.*`. The HMAC key is random per run and reaches the server through its environment only. |
| Driver | The MCP Java SDK client (the path agents use) with one session per worker, and HdrHistogram for latency. |

## Scenario files

`scenarios/sources-8.json`, `-32`, `-100` and `-300`. The format also takes a
500-source scenario and a soak run (0.8/0.9 work):

```json
{
  "name": "sources-8",
  "sources": 8,
  "sourceLatency": { "minMs": 5, "maxMs": 25 },
  "load": { "concurrency": 4, "calls": 300, "warmupCalls": 30,
            "ratePerSecond": 0, "durationSeconds": 0, "callTimeoutSeconds": 60 },
  "limits": { "maxSources": 8, "maxRecords": 500, "maxResponseSize": "512KB",
              "perSourceTimeout": "3s", "maxConcurrency": 8, "scopeReadBudget": 100 },
  "seed": 196
}
```

- `name` must match the file name; it prefixes the result file.
- `sourceLatency` is the uniform band every generated source responds within;
  the sampling is seeded by `seed`, so the same scenario behaves the same way.
- `load.calls` and `load.durationSeconds`: a run ends at whichever is set and
  reached first; at least one is required. `warmupCalls` run first and are
  discarded. `ratePerSecond` of 0 means as fast as the workers go; above 0,
  call *i* is due at `start + i / rate` and its latency is measured from that
  due time, so a stalled server shows as latency rather than as missing calls.
- `limits` are the six `dataprism.limits.*` keys. An omitted key takes the
  framework default (`docs/configuration.md`), except `maxSources`, which takes
  `sources` so the fan-out is not silently skipped. Pass a smaller `maxSources`
  to measure `SKIPPED_OVER_LIMIT`, or a small `maxRecords` or `maxResponseSize`
  to measure `TOO_MANY_RECORDS` and `RESPONSE_TOO_LARGE`.
- Every call asks for a different subject, so the per-subject
  `scope-read-budget` is not what a run measures.

## The result file

`calls` (attempted, completed, transport failures by exception class),
`latencyMs` (p50, p95, p99, max, mean; p50 to max are exact up to about 131 ms
and within 0.002 % above), `throughputPerSecond` (completed calls over the
measured wall time), `refusalCodes` (count by code of calls the server
refused or denied), `sourceStatusTotals` and `perSourceStatus` (all seven
statuses, `ANSWERED`, `NO_DATA`, `TIMED_OUT`, `FAILED`, `CIRCUIT_OPEN`,
`SKIPPED_OVER_LIMIT`, `REFUSED`, always present and zero when they did not
occur), plus `commit` (and `workingTreeDirty`), `jdk`, `host`, the scenario
`parameters` and the `effectiveLimits` the server was started with.

Source keys in `perSourceStatus` are the names the server returns, which are
aliases that are stable within the run's one privacy scope and unrelated to
the generated source names. Error text from a response is never recorded: a
refusal is tallied by its code, and anything unrecognised is the fixed code
`UNCLASSIFIED_ERROR`.

The host described is the one the driver ran on. The synthetic sources, the
driver and the server share it, so a result measures that combination; treat a
number as meaningful only against another run on the same host.

## Tests

`./mvnw -f load-test/pom.xml test` runs the harness's own unit tests: source
generation, exact percentiles of a known sample, status and refusal-code
tallies from canned responses, and scenario loading (including every shipped
scenario file).
