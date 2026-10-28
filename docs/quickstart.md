# Local Compose quickstart

One command brings up the standalone server, a synthetic fixture API and a
local token issuer, and proves an agent-compatible MCP request returns a
pseudonymised response — no source reading required. It then takes one
pseudonym through re-identification: a request, a second person approving, the
real identifier released to the operator only, and the audit lines each step
left ([Re-identify a pseudonym](#re-identify-a-pseudonym)).

**This is a local demonstration, not a production deployment.** Every host
name, port, certificate and secret below is fixture-only, and `compose.yaml`
labels each one. For what a real deployment configures instead — its own
identity provider, its own reviewed source adapters, its own secret
provider — see `docs/configuration.md`, the deployment contract this
quickstart is a worked instance of.

**There is no development bypass.** Some platforms let a local demo skip
authentication entirely. This one cannot: the standalone server refuses
`dataprism.transport.fixture-development=true` (task 21's own decision), so
every request below — including the very first one — carries a real,
signature-verified JWT. `data-prism-quickstart-issuer` exists to make getting
one a single command, at `POST /token`, not to be a shortcut around needing
one.

## Prerequisites

- Docker with Compose v2 (`docker compose version`).
- Nothing else. `docker compose up` pulls all four quickstart images —
  `ghcr.io/aindriub/data-prism-quickstart-{server,fixtures,issuer,certs-init}`,
  tagged `${QUICKSTART_IMAGE_TAG:-0.8.0}` — pre-built; no local JDK or Maven
  install is required to run them (`data-prism-quickstart-extension`'s own
  Docker-free integration test, `QuickstartSmokeIT`, is what proves the same
  chain works without Docker too, for contributors who do have one). To build
  every image from source instead — for local development, or before a
  version's images have been published — run `docker compose -f compose.yaml
  -f compose.build.yaml up --build`, which layers each service's `build:`
  block back on top of `compose.yaml`.

## Start it

```sh
docker compose up
```

pulls the four published images. To build them from source instead:

```sh
docker compose -f compose.yaml -f compose.build.yaml up --build
```

The images default to the version this quickstart was written for, `0.8.0`,
never `latest`: an older image ignores the cluster-key, name-key and operator
settings the re-identification walkthrough needs. To pin another released
version, set `QUICKSTART_IMAGE_TAG` in the environment (or a `.env` file)
before either command.

The first run (either command) takes a few minutes — pulling four images, or
building four (a Maven reactor build inside each of three) — later runs are
fast. When it settles you have:

| Service | What it is | Reachable at |
|---|---|---|
| `cert-init` | Generates this run's throwaway TLS material, then exits | — |
| `issuer` | The local JWT issuer (`data-prism-quickstart-issuer`) | `https://localhost:8544` |
| `fixtures` | The synthetic customer API (`data-prism-quickstart-fixtures`) | Compose network only |
| `server` | The standalone server, with the quickstart's reviewed adapter loaded via `-Dloader.path` | MCP at `http://localhost:8080`; the operator surface at `http://localhost:8081`, on loopback only |

`fixtures` is deliberately not published to the host: nothing outside the
server is meant to call a source adapter's own API directly, which is the
same reason a real deployment would put its own source APIs behind the
server rather than expose them to a caller.

The server's own startup does not wait on `issuer` or `fixtures` being fully
ready — JWKS and adapter calls happen lazily, on the first real request —
so if the very first command below fails, wait a few seconds and retry it.

## Get a token

The issuer's TLS certificate is the same self-signed, locally generated one
`server` and `fixtures` trust (see "How the pieces trust each other"
below); `curl` does not trust it by default, so every command against
`issuer` needs `-k`. That is specific to this local demonstration — see
`docs/configuration.md` for how a production deployment's JWKS location is
reached over a certificate that is trusted for real.

With no request body, `POST /token` mints a token for a fixture development
principal, in the `investigator` role, for purpose `investigation` and case
`CASE-QUICKSTART-1` — enough to call `get_entity_context`. To mint one for a
different role, purpose or case, POST a JSON body instead:

```sh
curl -sk -X POST https://localhost:8544/token \
  -H 'Content-Type: application/json' \
  -d '{"roles":["investigator"],"purpose":"investigation","caseId":"CASE-DEMO-1"}'
```

The demo command below mints and uses its own token, always for case
`CASE-QUICKSTART-1` (`run.sh` has no way to take a token minted for a
different case); to see a call made under a different case, drive the
JSON-RPC exchange yourself with the token above, following
[`examples/quickstart-demo/mcp-handshake.sh`](../examples/quickstart-demo/mcp-handshake.sh)'s
calls. You do not need to run the curl above first to run the demo command
below.

## Run the demo

MCP's Streamable HTTP transport is a JSON-RPC exchange, not a plain REST
call: the first response carries an `Mcp-Session-Id` header every later
request in the session must echo back, and the `initialize` /
`notifications/initialized` handshake must happen before a tool can be
called. An MCP-aware client (an agent, or the
[MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector)
pointed at `http://localhost:8080/mcp` with an `Authorization: Bearer`
header) handles this for you.
[`examples/quickstart-demo/run.sh`](../examples/quickstart-demo/run.sh) is
that same exchange as one runnable command: it mints a token, runs the
handshake, calls `get_entity_context` for the fixture customer, and prints
the fixture's real values beside the pseudonymised response the server
actually returned. See
[`examples/quickstart-demo/mcp-handshake.sh`](../examples/quickstart-demo/mcp-handshake.sh)
for the underlying JSON-RPC requests if you want to see the exchange itself.

```sh
examples/quickstart-demo/run.sh
```

prints (this is real output from an actual run against this stack):

```
PASS: get_entity_context for CUSTOMER 1001 returned a pseudonymised response.

  field         real fixture value                     pseudonymised response
  ------------  -------------------------------------  --------------------------
  subjectId     1001                                   SUBJ-KNSYWNZ9
  customerName  Fixture Person One                     Rowan Okafor (D1B5CR19)
  email         fixture.person.one@example.invalid     [REDACTED]
```

With the defaults above, these pseudonyms are the same every time you run
this: `customerName` and `subjectId` are derived by a keyed HMAC over the
case id and the subject id (`ScopeResolver`, `HmacSyntheticGenerator`), and
`run.sh` always mints a token for the same default case,
`CASE-QUICKSTART-1`. They change for a different case id, a different HMAC
key, or a different pseudonymisation version or vocabulary
(`HmacSyntheticGenerator`) — never merely from running the demo again.

The fixture customer API holds a second record, for subject `1002`:

```sh
examples/quickstart-demo/run.sh CUSTOMER 1002
```

```
PASS: get_entity_context for CUSTOMER 1002 returned a pseudonymised response.

  field         real fixture value                     pseudonymised response
  ------------  -------------------------------------  --------------------------
  subjectId     1002                                   SUBJ-F08KVXP6
  customerName  Fixture Person Two                     Oakley Castellano (KKHAGCFX)
  email         fixture.person.two@example.invalid     [REDACTED]
```

Neither subject's real name nor real email — `Fixture Person One` /
`fixture.person.one@example.invalid` for `1001`, `Fixture Person Two` /
`fixture.person.two@example.invalid` for `1002`, per
`data-prism-quickstart-fixtures`' own `CustomerController` — appears in
either response: `customerName` is a synthetic value, not the fixture's own;
`email` is redacted outright; `status`, not shown above but present in the
raw MCP response, passes through because it was classified `@NonSensitive`,
a decision `data-prism-quickstart-extension`'s `CustomerModel` states
explicitly (see `docs/configuration.md` and pack.md §30 on why a field
nobody classified is never exposed at all, rather than being disclosed by
default). `run.sh` checks this itself — it fails loudly if a response ever
carries the subject it was asked about's own raw name or email — and that
check is real: forcing the script to treat 1001's or 1002's own raw values
as the "pseudonymised" result (simulating a leak) makes it print `FAIL:
get_entity_context returned the fixture's raw value, unpseudonymised` and
exit non-zero for each, in place of the `PASS` lines above.

Both tools the platform ships today are reachable this way — this demo calls
`get_entity_context`; `compare_entity_sources` takes the same input schema
(`entityType` and `subjectId`, both required, nothing else — an MCP argument
can never choose a host, a path, a source or a caller identity, see
`docs/configuration.md`) but this quickstart's `investigator` role
(`docker/server/application.yaml`) is granted `GET_ENTITY_CONTEXT` only. See
[`docs/tools.md`](tools.md) for what `compare_entity_sources` returns,
worked against this repository's own fixture data.

Two requests worth trying, to see the boundary itself rather than take it on
faith:

```sh
# No token: refused with 401 before the request reaches the privacy engine.
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d '{}'

# A syntactically invalid token: refused the same way, not treated as anonymous.
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer not-a-real-token' -d '{}'
```

Both print `401`.

## Re-identify a pseudonym

An investigation ends with a person needing to know who a pseudonym stands for.
Re-identification is the one controlled way back, and it is never an MCP tool:
it is served on the **operator port** (`8081`), under its own audience and
scope, so a token an agent holds cannot reach it. This stack runs it with
four-eyes on (`dataprism.reidentification.four-eyes`), so the person who asks is
never the person who approves. The settings are in `docker/server/application.yaml`
(`dataprism.reidentification`, `dataprism.operator`, `dataprism.hazelcast`).
The full flow, every endpoint and every refusal code are in
[Re-identification](reidentification.md); this section walks one request
through it.

The server runs one embedded member that joins nothing (`topology: embedded`,
`join.mode: none`). Re-identification refuses `single-node`, because its index
lives in the cluster member. The member's cluster key and name key are
generated by the server container each time it starts, from the container's
own random source, and passed by reference (`environment-variable`): nothing is
committed, written to a volume or printed. Restarting makes new ones, which is
harmless for a member that is alone.

The re-identification index and every pending approval live in the server's
memory, so any restart of the server, `docker compose restart` as well as
`docker compose down`, clears them. After a restart, run step 2 again before
you request: a pseudonym can be re-identified only if the index was running
when it was handed out. The pseudonyms themselves do not change, because the
HMAC key (`QUICKSTART_HMAC_KEY`) is fixed.

Every command below was run against this stack, and the output is what it
printed. The generated identifiers (the approval id and the timestamps) will
differ on your run. The pseudonym `SUBJ-KNSYWNZ9` is the one the demo above
shows for subject `1001`, and stays the same for case `CASE-QUICKSTART-1`.

**1. Get three tokens.** The same issuer mints them. An operator token names the
operator audience and scope in the request body; without them you get the MCP
token from earlier. The requester also holds the `approver` role, so that
approving their own request is refused by the four-eyes rule and not merely
for want of a permission. One principal holding both roles is a demo
arrangement only: a real deployment gives `investigator` and `approver` to
different people, and the four-eyes rule then holds because they are different
people.

```sh
field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
mint() { curl -sk -X POST https://localhost:8544/token -H 'Content-Type: application/json' -d "$1" | field access_token; }

MCP_TOKEN=$(mint '{"subject":"requester-1","roles":["investigator"],"caseId":"CASE-QUICKSTART-1"}')
REQUESTER=$(mint '{"subject":"requester-1","roles":["investigator","approver"],"caseId":"CASE-QUICKSTART-1","audience":"data-prism-quickstart-operator","scope":"operator"}')
APPROVER=$(mint '{"subject":"approver-1","roles":["approver"],"caseId":"CASE-QUICKSTART-1","audience":"data-prism-quickstart-operator","scope":"operator"}')
```

The issuer mints only two shapes: the MCP token (no `audience`, no `scope`) and
the operator token (the operator audience with exactly the scope `operator`).
Any other combination is refused with `400 {"code":"INVALID_TOKEN_REQUEST"}`,
without echoing the input. An MCP token on the operator port is refused (`401`), and an
operator token on the MCP port is refused the same way.

**2. Ask for the entity, as an agent would.** This registers the pseudonym in
the re-identification index; a pseudonym handed out before the index existed
cannot be re-identified.

```sh
. examples/quickstart-demo/mcp-handshake.sh
mcp_initialize http://localhost:8080/mcp "$MCP_TOKEN"
mcp_notify_initialized http://localhost:8080/mcp "$MCP_TOKEN" "$MCP_SESSION"
mcp_call_get_entity_context http://localhost:8080/mcp "$MCP_TOKEN" "$MCP_SESSION" CUSTOMER 1001 \
  | grep -o '"subject":"[^"]*"' | head -1
```

```
"subject":"SUBJ-KNSYWNZ9"
```

**3. Request re-identification.** The scope id of a case is `case:` followed by
the case id (it is also the `scope=` field of the audit line for step 2). The
pseudonym of a subject id is in namespace `NONE`.

```sh
REQUEST=$(curl -s -X POST http://localhost:8081/operator/reidentifications \
  -H "Authorization: Bearer $REQUESTER" -H 'Content-Type: application/json' \
  -d '{"scopeId":"case:CASE-QUICKSTART-1","namespace":"NONE","syntheticValue":"SUBJ-KNSYWNZ9","purpose":"investigation","caseId":"CASE-QUICKSTART-1"}')
echo "$REQUEST"
APPROVAL_ID=$(echo "$REQUEST" | field approvalId)
```

```
{"approvalId":"00b97563-f965-45e0-a660-07532dbd1eef","status":"PENDING_APPROVAL"}
```

Nothing has been released: four-eyes returns a pending approval and does not
touch the index.

**4. A refusal: the requester cannot approve their own request.**

```sh
curl -s -w '\nHTTP %{http_code}\n' -X POST "http://localhost:8081/operator/reidentifications/$APPROVAL_ID/approve" \
  -H "Authorization: Bearer $REQUESTER"
```

```
{"code":"SELF_APPROVAL"}
HTTP 409
```

The refusal is audited as `DENY:SELF_APPROVAL` (step 7). Every other code is in
[the refusal table](reidentification.md#refusal-codes).

**5. The second person sees what is being asked for, and approves.** The approver's
list shows the pseudonym, its namespace, the requester, the purpose and the
expiry. It never shows the subject id, which an approval has no field for.

```sh
curl -s -w '\nHTTP %{http_code}\n' http://localhost:8081/operator/approvals -H "Authorization: Bearer $APPROVER"
```

```
{"approvals":[{"approvalId":"00b97563-f965-45e0-a660-07532dbd1eef","kind":"REIDENTIFICATION","status":"PENDING","requesterPrincipalId":"requester-1","requesterClientId":"quickstart-cli","scopeId":"case:CASE-QUICKSTART-1","tool":"reidentify","purpose":"investigation","caseId":"CASE-QUICKSTART-1","createdAt":"2026-10-28T18:05:13.516108530Z","expiresAt":"2026-10-28T18:20:13.516108530Z","namespace":"NONE","syntheticValue":"SUBJ-KNSYWNZ9"}]}
HTTP 200
```

```sh
curl -s -w '\nHTTP %{http_code}\n' -X POST "http://localhost:8081/operator/reidentifications/$APPROVAL_ID/approve" \
  -H "Authorization: Bearer $APPROVER"
```

```
{"status":"APPROVED"}
HTTP 200
```

**6. The requester collects the real identifier.** Only the requester can, and only
once, before the approval expires.

```sh
curl -s -w '\nHTTP %{http_code}\n' "http://localhost:8081/operator/reidentifications/$APPROVAL_ID" \
  -H "Authorization: Bearer $REQUESTER"
```

```
{"subjectId":"1001","status":"RESOLVED"}
HTTP 200
```

`1001` is the fixture's subject id. It appears in this one response, to the
requester on the operator port. It is in no error, no log line and no audit
event. Asking again is refused, because the approval was used:

```sh
curl -s -w '\nHTTP %{http_code}\n' "http://localhost:8081/operator/reidentifications/$APPROVAL_ID" \
  -H "Authorization: Bearer $REQUESTER"
```

```
{"code":"APPROVAL_NOT_APPROVED"}
HTTP 409
```

**7. Read the audit.** The audit sink here is the server's log
(`dataprism.audit.sink: slf4j`). Each step wrote an `operator:` event saying the
operator asked, and the re-identification service wrote its own `reidentify`
event with the outcome. This prints the principal, the tool and the decision of
each, in order:

```sh
docker compose logs --no-color server \
  | grep -E 'dataprism.audit' | grep -E ' tool=(reidentify|operator:)' \
  | sed -E 's/.* principal=([^ ]*) .* tool=([^ ]*) .* decision=([^ ]*) .*/\1  \2  \3/'
```

```
requester-1  operator:reidentify-request  ALLOW:FORWARDED
requester-1  reidentify  ALLOW:REQUESTED
requester-1  operator:reidentify-approve  ALLOW:FORWARDED
requester-1  reidentify  DENY:SELF_APPROVAL
approver-1  operator:approvals  ALLOW:READ
approver-1  operator:reidentify-approve  ALLOW:FORWARDED
requester-1  reidentify  ALLOW:APPROVED
requester-1  operator:reidentify-collect  ALLOW:FORWARDED
requester-1  reidentify  ALLOW:RESOLVED
requester-1  operator:reidentify-collect  ALLOW:FORWARDED
requester-1  reidentify  DENY:APPROVAL_NOT_APPROVED
```

The `reidentify` lines are the decisions: requested, refused for self-approval,
approved, resolved, then refused on the second collect. A `reidentify` event is
written under the requester's principal; the `operator:reidentify-approve` line
just before `ALLOW:APPROVED` is the one that names the approver. The full lines
also carry the scope, purpose, case id, correlation id and the pseudonym, never
the subject id.

[`docker/smoke/reidentification-smoke.sh`](../docker/smoke/reidentification-smoke.sh)
runs these same steps against a throwaway copy of the stack and exits 0 only if
the resolved identifier matches the fixture and the audit holds these
decisions in order; see [`docker/smoke/README.md`](../docker/smoke/README.md).

The operator port is plain HTTP and bound here to loopback by `compose.yaml`
(`127.0.0.1:8081`). Never publish it beyond a machine you trust; see
[What this does not do](reidentification.md#what-this-does-not-do).

## Stop and reset

```sh
docker compose down
```

stops every service and removes the containers, but leaves the `certs`
volume (and its generated keystore) in place, so the next `docker compose up`
reuses the same certificate rather than minting a new one. To reset
completely — a fresh certificate, and a clean slate for anything a future
version of this quickstart might persist (the server's cluster and name keys
and every pending approval live in the server process only, so even a plain
`docker compose down` ends them):

```sh
docker compose down --volumes
```

## How the pieces trust each other

`cert-init` (`docker/certs-init/`) generates one self-signed PKCS12 keystore
and a matching truststore at first run, into the `certs` Docker volume —
never into this repository, and never reused as a real certificate for
anything. `issuer` and `fixtures` serve HTTPS from that keystore, because
task 21 refuses a plaintext `http://` JWKS location and a plaintext
`http://` source `base-url` outright, fixture exception or not. `server`
trusts that same certificate for its own outbound calls (JWKS discovery,
the fixture API) via the standard Java `javax.net.ssl.trustStore` system
properties, set through `JAVA_TOOL_OPTIONS` in `compose.yaml` — the same
per-JVM mechanism a production deployment would use to trust its own
organisation's CA, not something this quickstart invents.

None of this material — the keystore, the truststore, the signing key
`data-prism-quickstart-issuer` generates in memory at its own startup, or
any token minted from it — is ever written into this repository. See
CLAUDE.md rule 7.

## Docker-free alternative

`data-prism-quickstart-extension`'s `QuickstartSmokeIT` proves the same
chain — fixture API, issuer, the packaged standalone server with this
extension loaded via `-Dloader.path` — end to end without a Docker daemon,
as three real JVM subprocesses driven by the MCP SDK's own client transport.
It runs as part of `mvn -B verify` from the repository root and is the
reference for anyone extending this quickstart who cannot rely on Docker
being available.

## What next

This quickstart's adapter, models and fixture data are already written for
you, purely so the one command above has something real to answer with. The
next step is protecting your own API instead of the fixture one:
[`docs/protect-your-own-api.md`](protect-your-own-api.md) walks a flat or
one-level-nested JSON REST API from nothing to a working
`get_entity_context` call, writing only YAML — no Java, no rebuild of
`data-prism-server` itself. If your API needs custom fetch logic or a model
that walkthrough's configuration-driven mode cannot express,
[`docs/extending.md`](extending.md) covers the general, Java-adapter path
instead. Either way, [`docs/configuration.md`](configuration.md) is the
deployment contract both paths sit on top of.
