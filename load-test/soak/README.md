# Soak scaffolding (task 263, Part A)

What is here, and what is not. Running the soaks is the owner's job on a dedicated host (Part B of
`docs/plan/tasks/263-*.md`); nothing here is evidence by itself.

| File | What it is |
|---|---|
| `soak-single-node.json` | 100 sources, 4 calls a second for 8 hours, two slow sources against a 1 s per-source timeout, a 15,000-subject pool against a read budget of 300 (the budget counts source reads, so 3 calls of 100 sources per subject per scope) |
| `soak-three-member.json` | The same mix for 4 hours |
| `compose.soak.yaml` | Overlay on `docker/multi-instance/compose.yaml`: three members, a load balancer, trusted network, private member interface, links off, no link key |
| `common.sh` | Helpers the scripts source |
| `fault-*.sh`, `probe-startup-refusals.sh` | The Part B faults and the AC3 startup probes |
| `capture-member-port.sh` | Member-port capture for one transport profile |
| `link-timing.sh` | `LinkTimingSoakTest` with `-Ddataprism.timing=true` |

Every script writes counts and findings only, to `load-test/results/<name>-summary.txt`
(git-ignored), never an address, a payload byte or a key.

## Known limits of the scenarios

- The driver calls `get_entity_context` only, and the scenario format has no field for a mix. The scenarios
  therefore provoke timeouts (slow sources) and budget exhaustion (subject pool against a small
  budget) but do not drive `list_sources` walks, `describe_source`, `follow`, or invalid links.
  Driving those needs a change under `load-test/src`, which task 263 does not own.
- `run.sh` starts its own single local server. The three-member scenario file therefore runs against
  that one server when started through `run.sh`; pointing the driver at the load balancer of the
  overlay needs a driver option that does not exist yet.
- The hostile-member flood, the clock skew and the partition's pause are inputs the owner supplies:
  see the header of each script.
- The workflow's artifact name embeds the scenario path, so the `../soak/` choices cannot upload a
  result, and its 60 minute job timeout is shorter than either soak.
