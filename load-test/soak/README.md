# Soak scaffolding (task 263, Part A)

What is here, and what is not. Running the soaks is the owner's job on a dedicated host (Part B of
`docs/plan/tasks/263-*.md`); nothing here is evidence by itself.

| File | What it is |
|---|---|
| `soak-single-node.json` | 100 sources, 4 calls a second for 8 hours, links enabled, the `mix` below, two slow sources against a 1 s per-source timeout, a 15,000-subject pool against a read budget of 300 (the budget counts source reads, so 3 calls of 100 sources per subject per scope) |
| `soak-three-member.json` | The same mix for 4 hours, without `follow` or invalid links (a joining member refuses links), through the overlay's load balancer: its `target` names the endpoint, so the driver starts no server of its own |
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
pages), `describeSource`, `follow` (of a link from a result's `_links`, each used once) and `invalidLink` (a
well-formed link the server never issued, which must be refused). A call that needs what the worker does not
hold yet is replaced by the call that gets it. An unknown name in `mix` is rejected when the scenario loads.
The result file then also holds `perTool` counts and percentiles.

Run through `run.sh`, a mix with `follow` or `invalidLink` starts the server with links enabled, and a mix with
`invalidLink` raises `dataprism.links.invalid-attempt-limit` so the one caller is not locked out within seconds:
the soak does not test the lockout. The synthetic sources then declare the key namespace `parcel-id` on their
`id` field, so each context result carries direct links to the others.

Known gap: every link the harness's own server issues is **direct**. A translated link needs a `KeyResolver`
bean, which the packaged server does not have, so no scenario here follows one. A Part B run that must follow
translated links needs a server with a resolver and a `target`.

A scenario with a `target` (`baseUrl`, `tokenUrl`, and optionally `entityType`, `subjects`,
`tokenLifetimeMinutes`, `scopeLifetimeHours`) drives that already-running endpoint instead: no synthetic sources
and no server are started, `limits` and `sources` are not applied, and tokens are minted from the target's
issuer (`POST /token`, the quickstart issuer's shape) and replaced before they end. `soak-three-member.json`
targets the overlay's load balancer on loopback port 8080 and the quickstart issuer on 8544; the issuer uses a
self-signed certificate, so start the driver with `JAVA_TOOL_OPTIONS` naming the quickstart truststore
(`-Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStoreType=PKCS12`). The quickstart members serve their own
fixture source, not 100 synthetic ones: what the target serves is what is measured, and `subjects` should list
ids that fixture holds. The caller's roles on the target decide whether `list_sources` and `describe_source`
are allowed.

## Inputs the owner supplies

- `fault-hostile-member.sh` takes `TAMPER_FLOOD_CMD`, the command that floods the member port with tampered
  frames, and refuses to run without it. This repository ships no such tool.
- `fault-clock-skew.sh` takes `FAKETIME_LIB`, the path of a libfaketime build for the member image, and
  refuses to run without it.
- The partition's pause is issued by the owner, as the header of `fault-network-partition.sh` says.

`load-test/README.md` is outside task 263's files, so it does not describe the `mix` or `target` fields;
this file does.

## Other limits

- The workflow's job timeout (540 minutes) covers the 8-hour soak, but a GitHub-hosted runner stops a job at 6
  hours; the soaks are run by the owner on a dedicated host, not on a shared runner.
