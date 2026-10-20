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

`scenarios/sources-8.json`, `-32`, `-100` and `-300` are all-success baselines.
Three more drive a failure path on purpose; what each should produce is in the
table, and `FailureScenarioTest` asserts it over recorded responses:

| Scenario | What it drives | Expected in the result file |
|---|---|---|
| `failure-source-timeouts` | Eight sources, four of them (`slowSources`) answering in 2.5 to 3.5 s (`slowLatency`) against a `perSourceTimeout` of `1s` | `TIMED_OUT` for four sources of every call and `ANSWERED` for the rest; no transport failure. Were every source slow the call would be refused `NO_SOURCE_DATA` and report no per-source status |
| `failure-read-budget` | Five subjects read 40 times each against a `scopeReadBudget` of 10 (`load.subjectPool`) | `SCOPE_READ_BUDGET` in `refusalCodes` once a subject is past its budget |
| `failure-over-limit` | 16 sources against a `maxSources` of 8 | on a 0.7 server `SKIPPED_OVER_LIMIT` per source; after the fan-out rewrite the `SOURCE_SELECTION_TOO_LARGE` refusal |

The format also takes a 500-source scenario and a soak run of up to 24 hours:

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
- `slowSources` and `slowLatency`: the last `slowSources` sources answer within
  `slowLatency` instead of `sourceLatency`. Absent, all sources use `sourceLatency`.
- `load.subjectPool`: 0 (the default) asks for a different subject on every
  call. Above 0 the calls cycle through that many subjects, so the same subject
  is read again and the per-subject `scope-read-budget` is reached.
- `load.calls` and `load.durationSeconds` (at most 86400, 24 hours): a run ends at whichever is set and
  reached first; at least one is required. `warmupCalls` run first and are
  discarded. `ratePerSecond` of 0 means as fast as the workers go; above 0,
  call *i* is due at `start + i / rate` and its latency is measured from that
  due time, so a stalled server shows as latency rather than as missing calls.
  A call that times out (reaches `callTimeoutSeconds`) is counted in
  `calls.transportFailures` and is also recorded in the latency figures, at
  the time from its due time to the timeout (a lower bound on what it would
  have taken), so a stall raises p99 and max. Any other transport failure
  (for example connection refused after the server dies) is only counted in
  `calls.transportFailures`, so a dead server cannot look fast.
  The deadline is the real clock: workers that have fallen behind stop
  dispatching when `durationSeconds` passes rather than working through the
  backlog, and the scheduled calls they never made are reported as
  `calls.notDispatched`.
- `limits` are the six `dataprism.limits.*` keys. An omitted key takes the
  framework default (`docs/configuration.md`), except `maxSources`, which takes
  `sources` so the fan-out is not silently skipped. Pass a smaller `maxSources`
  to measure `SKIPPED_OVER_LIMIT`, or a small `maxRecords` or `maxResponseSize`
  to measure `TOO_MANY_RECORDS` and `RESPONSE_TOO_LARGE`.
- Unless `load.subjectPool` is set, every call asks for a different subject, so
  the per-subject `scope-read-budget` is not what a run measures.

## Soak runs

A run of up to 24 hours outlives the 12 hour token and the 8 hour privacy
scope. The harness therefore presents its token through a `CredentialRotation`:
the token is read on every request, and once three quarters of its lifetime
(9 h) has passed a new one is minted, and once three quarters of the scope's
lifetime (6 h) has passed the scope is rotated, by minting a token with a new
case id (`CASE-LOADTEST-2`, ...). Both are counted in the result file under
`credentials`. Throughput divides the calls finished by the deadline by the
configured duration (`measuredSeconds`); the calls still in flight at the
deadline are counted (`calls.completedAfterDeadline`) and timed, but do not
raise it. A failure that is not a timeout but took one second or more (for
example a connect timeout under high concurrency) is timed too, so it cannot
hide from the tail; a quick failure is only counted.

Every wait is bounded: opening a connection (10 s), one call (`callTimeoutSeconds`),
each `keytool` call (60 s), the server's launch and health check (180 s), the
closing of the sessions (30 s in total) and the closing of each started thing
at cleanup (30 s each). Something that does not finish is abandoned, not waited for.

## The result file

`calls` (attempted, completed, transport failures by exception class, the
`failureCauses` of those failures, `completedAfterDeadline`, and `notDispatched`: paced calls scheduled within the duration but never made
because the driver could not keep up; a non-zero value means the offered rate
was not reached),
`latencyMs` (p50, p95, p99, max, mean, over completed and timed-out calls; p50 to
max are exact up to about 262 ms and within 0.002 % above; values over six
hours are recorded as six hours and counted in `clamped`, which is 0 in any
run that is not badly overloaded), `throughputPerSecond` (calls completed within the
window, over `measuredSeconds`; the run's full wall time is `elapsedSeconds`), `refusalCodes` (count by code of calls the server
refused or denied), `sourceStatusTotals` and `perSourceStatus` (all eight
statuses, `ANSWERED`, `NO_DATA`, `TIMED_OUT`, `FAILED`, `CIRCUIT_OPEN`,
`SKIPPED_OVER_LIMIT`, `REFUSED`, `RESOLVER_FAILED`, always present and zero when
they did not occur; a status this list does not know is tallied under its own
name), `sourcesNotAnswered` (source readings whose status is neither
`ANSWERED` nor `NO_DATA`, an unknown status included), `credentials` (`tokenReissues`
and `scopeRotations`), plus `commit` (and `workingTreeDirty`), `jdk`, `host`, the scenario
`parameters` and the `effectiveLimits` the server was started with.

Source keys in `perSourceStatus` are the names the server returns, which are
aliases that are stable within the run's one privacy scope and unrelated to
the generated source names. Error text from a response is never recorded: a
refusal is tallied by its code, and anything unrecognised is the fixed code
`UNCLASSIFIED_ERROR`.

The host described is the one the driver ran on. The synthetic sources, the
driver and the server share it, so a result measures that combination; treat a
number as meaningful only against another run on the same host.

### Why a call failed

A call that never reached a tool result is a failed call (`calls.transportFailures`).
Each one carries exactly one cause from this closed set, and the counts in
`calls.failureCauses` (every cause present, zero when it did not occur) add up to
`transportFailures`; the writer refuses to write a file where they do not.
Each cause is counted from its own branch of the classifier, in this order:

| Cause | When |
|---|---|
| `CONNECT_TIMEOUT` | the connection was not established within 10 s |
| `READ_TIMEOUT` | the request timed out, or the call waited `callTimeoutSeconds` with no better cause |
| `MCP_PARSE_ERROR`, `MCP_INVALID_REQUEST`, `MCP_METHOD_NOT_FOUND`, `MCP_INVALID_PARAMS`, `MCP_INTERNAL_ERROR` | the JSON-RPC error code `-32700`, `-32600`, `-32601`, `-32602`, `-32603` |
| `MCP_ERROR_OTHER` | any other JSON-RPC error code |
| `HTTP_STATUS_4XX`, `HTTP_STATUS_5XX`, `HTTP_STATUS_OTHER` | the HTTP status the transport reported |
| `UNPARSEABLE_BODY` | the response could not be parsed, or held no `sources` object |
| `CONNECTION_FAILED` | connection refused, reset or closed |
| `OTHER` | none of the above |

No exception text is recorded; only the status or code is read from it. This is
the cause of a failed call. The cause of a source's `FAILED` status inside a
response is not in the response, so it cannot be recorded here.

## Tests

`./mvnw -f load-test/pom.xml test` runs the harness's own unit tests: source
generation, exact percentiles of a known sample, status and refusal-code
tallies from canned responses, and scenario loading (including every shipped
scenario file), failure-cause classification, credential rotation, the failure
scenarios' expected classification, and a bound for each wait.
