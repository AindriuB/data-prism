# Soak scaffolding (task 263, Part A)

What is here, and what is not. Running the soaks is the owner's job on a dedicated host (Part B of
`docs/plan/tasks/263-*.md`); nothing here is evidence by itself.

| File | What it is |
|---|---|
| `soak-single-node.json` | 100 sources, 4 calls a second for 8 hours, links enabled, the `mix` below (direct, translated and invalid-link follows), two slow sources against a 1 s per-source timeout, a 15,000-subject pool against a read budget of 300 (the budget counts source reads, so 3 calls of 100 sources per subject per scope) |
| `soak-three-member.json` | The context, `list_sources` and `describe_source` mix for 4 hours, without any `follow` (a joining member refuses links), through the overlay's load balancer: its `target` names the endpoint, so the driver starts no server, no sources and sets no limits; see "The three-member run" |
| `compose.soak.yaml` | Overlay on `docker/multi-instance/compose.yaml`: three members, a load balancer, trusted network, private member interface, links off, no link key |
| `common.sh` | Helpers the scripts source |
| `fault-*.sh`, `probe-startup-refusals.sh` | The Part B faults and the AC3 startup probes |
| `capture-member-port.sh` | Member-port capture for one transport profile |
| `link-timing.sh` | `LinkTimingSoakTest` with `-Ddataprism.timing=true` |

Every script writes counts and findings only, to `load-test/results/<name>-summary.txt`
(git-ignored), never an address, a payload byte or a key.

## The call mix and the target

A scenario's `mix` sets the relative weight of each call the driver makes: `entityContext`,
`targetedEntityContext` (sources taken from the worker's last `list_sources`), `listSources` (a walk through the
pages), `describeSource`, `follow` (of a direct link from a result's `_links`, each used once), `followTranslated` (of a held link that
carries a `toNamespace`) and `invalidLink` (a held link with its last byte changed, which the server reads, finds
the key for and fails at the AEAD tag, so the costly refusal path is the one measured; it is refused as
`LINK_INVALID`, and the held link is still followed later). A call that needs what the worker does not
hold yet is replaced by the call that gets it. An unknown name in `mix` is rejected when the scenario loads.
The result file then also holds `perOperation` counts and percentiles, one entry per call kind (`entityContext`,
`targetedEntityContext`, `listSources`, `describeSource`, `follow`, `followTranslated`, `invalidLink`), and
`intervals`.

Run through `run.sh`, a mix with `follow` or `invalidLink` starts the server with links enabled, and a mix with
`invalidLink` raises `dataprism.links.invalid-attempt-limit` so the one caller is not locked out within seconds:
the soak does not test the lockout. The synthetic sources then declare the key namespace `parcel-id` on their
`id` field, so each context result carries direct links to the others.

Translated links: the harness's own server gets a `KeyResolver` (`parcel-id` to `parcel-ref`), built into
`target/data-prism-load-test-key-resolver.jar` by this module's `package` and put on the server's `loader.path`
beside the REST connector when the mix has a `followTranslated` weight. Every fifth synthetic source then declares
`parcel-ref` as its input key, so a context result carries a translated link to it. The module compiles the
resolver against `data-prism-core` from the reactor build (`../data-prism-core/target`, built by `run.sh`; the
`dataprism.version` property of `load-test/pom.xml` must match the reactor's version), so build the reactor first.
The overlay's members run with links disabled, so no resolver and no translated follow exists in the three-member
run (pending the owner: it needs decision (b) reversed or task 264).

## Interval rows

Every `load.intervalSeconds` (default 3600) the driver closes an interval and the result file's `intervals` gets
a row: per operation p50, p95, p99, calls, refused and failed counts; refusals by code; and, for the harness's own
server, heap after GC and live threads read from its JVM through the attach API (null where unreadable, and always
null for a target, whose members the driver cannot read). A final partial row closes the run. The per-hour tables
of `docs/plan/perf/soak-0.8.md` are filled from these rows.

A scenario with a `target` (`baseUrl`, `tokenUrl`, and optionally `entityType`, `subjects`,
`tokenLifetimeMinutes`, `scopeLifetimeHours`) drives that already-running endpoint instead: no synthetic sources
and no server are started, and tokens are minted from the target's
issuer (`POST /token`, the quickstart issuer's shape) and replaced before they end. `soak-three-member.json`
targets the overlay's load balancer on loopback port 8080 and the quickstart issuer on 8544; the issuer uses a
self-signed certificate, so start the driver with `JAVA_TOOL_OPTIONS` naming the quickstart truststore
(`-Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStoreType=PKCS12`). The quickstart members serve their own
fixture source, not 100 synthetic ones: what the target serves is what is measured, and `subjects` should list
ids that fixture holds.

A target-mode scenario that sets `sources`, `slowSources`, `slowLatency` or `limits` is refused when it loads,
naming the field, because the harness cannot apply them to a target, and its result records `mode: target` with
no `effectiveLimits` and no source counts. The limits belong to the members: the overlay's `member-env` sets the
scope read budget and the per-source timeout, and the investigator role's `LIST_SOURCES` and `DESCRIBE_SOURCE`.

## The three-member run

The subjects are the fixture's two ids, each served by the one fixture source, so a context call is one source
read. At 4 calls a second for 4 hours the mix makes about 57,600 calls, of which 7 in 12 are context calls
(about 33,600 reads, about 16,800 per subject). The overlay's budget is 12,000 reads per subject per scope
(`DATAPRISM_LIMITS_SCOPEREADBUDGET`) and the scope outlives the run, so the expected share of `SCOPE_READ_BUDGET`
refusals is about 29% of the context calls (about 17% of all calls), and the first refusal comes about 2 hours 50
minutes in. This is an estimate to be compared with the measured `refusalCodes`, not a pass criterion. Source
timeouts are single-node only; the overlay's per-source timeout is the 3 s default.

## Inputs the owner supplies

- `fault-hostile-member.sh` takes `TAMPER_FLOOD_CMD`, the command that floods the member port with tampered
  frames, and refuses to run without it. This repository ships no such tool.
- `fault-clock-skew.sh` takes `FAKETIME_LIB`, the path of a libfaketime build for the member image, and
  refuses to run without it.
- `fault-hostile-member.sh` also needs `HEALTH_PATH`, naming a readiness path (for example
  `/actuator/health/readiness`), and `HEALTH_BEARER`, a token it accepts, and refuses to run without either: the
  default `/health` is liveness and always answers 200, so it could never show the DOWN the run looks for.
- `fault-full-restart-key-change.sh` takes the new key as `NEW_CLUSTER_KEY_MATERIAL` or `NEW_NAME_KEY_MATERIAL`
  and refuses without it. Afterwards, export the new value as `DATAPRISM_CLUSTER_KEY_MATERIAL` or
  `DATAPRISM_NAME_KEY_MATERIAL` before any later script, or its recreate will fail with `CLUSTER_KEY_MISMATCH`.
- `capture-member-port.sh` takes `SUBJECT_IDS`, the exact ids the scenario calls (for the three-member scenario,
  `1001,1002`), searches the capture for exactly those strings, and refuses to report unless its own positive
  control finds a planted id. Its sidecar image is pinned by digest, has only `NET_RAW`, and the analysis runs with
  no network.
- The partition's pause is issued by the owner, as the header of `fault-network-partition.sh` says.

The scripts that wait for a member (`kill-restart`, `roll-all`, `kill-owner-and-backup`, `key-change`,
`network-partition`) wait until it answers `/health` **and** its own log's latest member list holds three members;
the summaries say "answering health" for the liveness probe alone. A roll stops at the first member that does not
rejoin.

`load-test/README.md` is outside task 263's files, so it does not describe the `mix` or `target` fields;
this file does.

## Other limits

- The workflow's job timeout (540 minutes) covers the 8-hour soak, but a GitHub-hosted runner stops a job at 6
  hours; the soaks are run by the owner on a dedicated host, not on a shared runner.
