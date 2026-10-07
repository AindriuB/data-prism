# 133 — Ship multi-instance Compose and Kubernetes examples, and declare the cluster and operator ports

**Repo:** `.`
**Release:** 0.4.1
**Base branch:** `claude/release-0.4.1` (not `main`)
**Depends on:** none (property names are fixed in task 132's "Shape")
**Owns:**
- docker/multi-instance/** *(new: `compose.yaml`, `kubernetes.yaml`, `README.md` optional)*
- docker/distribution/Dockerfile *(`EXPOSE` lines and their comments only; not `ARG VERSION`, which is task 136's, and not base-image tags, which are task 130's)*
- server.json *(`environmentVariables` entries only; not the version fields, task 136)*

## Goal

Give deployers a working starting point for two or more data-prism instances
sharing one embedded cluster. The examples use Docker Compose for local
proving and Kubernetes for production shape. Each shows the member port and the
network isolation the deployment must supply. This task also closes the 0.4.x
follow-up "Exposing the operator port in Docker Compose, `server.json` and the
image" for the image and `server.json`. The planner folded that in because it
touches the same files.

## Context

- task 132's "Shape" gives the exact `dataprism.hazelcast.*` property names.
  Use the Spring relaxed environment-variable forms, and verify each against
  the binder. compose.yaml:62-68 explains why a wrong env name fails silently.
- compose.yaml and compose.build.yaml: the quickstart's `cert-init`, `issuer`
  and `fixtures` services, and the `server` image. Reuse them through
  Compose `include:` or `extends:`. Do not copy them, and do not edit them.
- docker/server/application.yaml:45-49: the quickstart pins `single-node`.
  Environment variables override it in the multi-instance file.
- docker/distribution/Dockerfile:77: `EXPOSE 8080`.
- server.json:150-157: the shape of an `environmentVariables` entry.
- mkdocs.yml `pymdownx.snippets` has `base_path` `docker`, so task 135 includes
  sections of these files by marker. Run
  `docs-site/hooks/check_snippet_markers.py`.

## Acceptance

- [ ] `docker/multi-instance/compose.yaml` runs two server services,
      `server-a` and `server-b`. Each sets:
      - `DATAPRISM_HAZELCAST_TOPOLOGY=embedded`;
      - a cluster name that is not `dev`;
      - join mode `tcp-ip`, with each member listing both services by
        Compose DNS name;
      - member port 5701.

      It also meets all of these:
      - The member port is **not** published to the host.
      - Both servers sit on an `internal: true` network named `cluster`, plus
        the quickstart network they need for the issuer and fixtures.
      - Only `server-a`'s MCP port is published, on `127.0.0.1`.
      - A comment states that member traffic is unencrypted and
        unauthenticated, and must stay on a private network.
- [ ] `docker compose -f docker/multi-instance/compose.yaml config -q` exits 0.
      If a Docker daemon is available, bring it up with locally built images
      (`compose.build.yaml`) and record in the return whether both server logs
      show a 2-member cluster (`Members {size:2`). If no daemon is available,
      say so; do not claim the run.
- [ ] `docker/multi-instance/kubernetes.yaml` contains:
      - a headless `Service` exposing port 5701, named `hz-member`;
      - a `Deployment` or `StatefulSet` of 2 replicas with join mode
        `kubernetes`, the namespace from the downward API, and `service-dns`
        pointing at the headless service, which needs no Kubernetes API RBAC;
      - a `NetworkPolicy` that admits ingress on 5701 only from pods with the
        same app label;
      - a commented alternative for `service-name` API mode, naming the RBAC
        it needs (get and list on endpoints and pods);
      - no `Secret` values, only references with placeholder names.

      If `kubectl` is present, `kubectl apply --dry-run=client -f` exits 0.
      Otherwise a YAML parse such as `python3 -c 'import yaml,sys;
      list(yaml.safe_load_all(open(sys.argv[1])))'` exits 0, and the return
      says which check was run.
- [ ] Both files carry `--8<-- [start:x]`/`[end:x]` markers for at least these
      sections:
      - `cluster-env` (the cluster settings of one Compose service);
      - `k8s-service`;
      - `k8s-networkpolicy`;
      - `k8s-join` (the container env block).

      `python3 docs-site/hooks/check_snippet_markers.py` exits 0.
- [ ] `docker/distribution/Dockerfile` adds `EXPOSE 5701` with a comment that
      it is the member port, for private networks only. It also gets one
      comment line saying the operator port is set by
      `dataprism.operator.port` and has no fixed default, so it is not
      `EXPOSE`d; publish it explicitly. `ARG VERSION` is unchanged.
- [ ] `server.json` `environmentVariables` gains optional entries for:
      - `DATAPRISM_HAZELCAST_CLUSTERNAME`, `DATAPRISM_HAZELCAST_JOIN_MODE`,
        `DATAPRISM_HAZELCAST_JOIN_MEMBERS`, `DATAPRISM_HAZELCAST_MEMBER_PORT`;
      - `DATAPRISM_OPERATOR_ENABLED`, `DATAPRISM_OPERATOR_PORT`,
        `DATAPRISM_OPERATOR_REQUIREDAUDIENCE`, `DATAPRISM_OPERATOR_REQUIREDSCOPE`.

      Use the exact names the binder accepts. Each description says when the
      variable is required. The `DATAPRISM_HAZELCAST_TOPOLOGY` description
      changes from "shared across the cluster" to "shared across members that
      have joined one cluster (set cluster name and join mode)".
      `python3 -m json.tool server.json` exits 0.
- [ ] No real hostnames, credentials or tokens appear. Addresses are Compose
      service names, `*.svc.cluster.local`, or documentation ranges.

## Out of scope

- The quickstart's own `compose.yaml`, `compose.build.yaml` and
  `docker/server/application.yaml`.
- Making the quickstart issuer mint operator-audience tokens, so the operator
  surface is usable end to end in Compose. That stays a 0.4.x follow-up, and
  this task's return says so.
- Docs pages, including the `docs/reidentification.md:178-179` sentence. That
  is task 135.
- A Helm chart.

## Attempt 1 — failed

Tester: PASS. Reviewer: CHANGES (head 9059feb). The env names, structure and
scope are all right. Deferring the live 2-member run is accepted; it moves to task 136.
Required for attempt 2:
1. **The Compose isolation claim is false.** Both servers are also on the non-internal
   `default` network and Hazelcast listens on every interface, so `issuer`,
   `fixtures` and the Linux host can reach `server-a:5701`. Give the `cluster`
   network a fixed ipam subnet and set `DATAPRISM_HAZELCAST_MEMBER_INTERFACE` to it
   (task 132 defines `member.interface`). The join members must resolve to cluster-network
   addresses: use fixed `ipv4_address`es on `cluster` in `join.members`, or
   network aliases that exist only on `cluster`. Then make the SECURITY comment
   say exactly what is isolated.
2. **The kubernetes.yaml NetworkPolicy comment (:112-113) is false.** Once the policy
   selects the pods, 8080 is denied too. Say that 8080 stays closed until the
   deployer adds an allow rule for their ingress, and include a commented
   example rule.
3. Set `automountServiceAccountToken: false` in the pod spec for DNS mode. The
   commented API mode must use a dedicated ServiceAccount (commented) bound to the
   Role, and tell the reader to flip automount back.
4. Note in the compose.yaml header that `!override`/`!reset` need Docker Compose ≥ 2.24.0.
5. server.json `DATAPRISM_OPERATOR_PORT`: also mention that it must differ from the
   management port (`OPERATOR_PORT_SHARED`).

## Attempt 2 — failed

Tester: PASS (the subnet does not clash locally). Reviewer: CHANGES (head 842ee27). Items 2, 3 and 5 are met.
- The SECURITY claim "does not listen on the `default` network" becomes true
  once `ClusterMembership` sets `hazelcast.socket.bind.any=false` whenever an
  interface is given. That fix goes into task 131 attempt 2, not here; keep the
  claim. Do not weaken it.
Required for attempt 3:
1. compose.yaml:11: `!override` needs Docker Compose **>= 2.24.4** (`!reset` 2.18.0). Fix the note.
2. One comment line at the subnet: `172.28.57.0/24` may collide with an existing
   Docker or VPN network ("Pool overlaps"). Say to change it in all the places it appears,
   and list them.
3. SECURITY block, under "does not protect against": add processes on the Linux host,
   which can usually route to container addresses on bridge networks, internal ones included.
