# Local Compose quickstart

One command brings up the standalone server, a synthetic fixture API and a
local token issuer, and proves an agent-compatible MCP request returns a
pseudonymised response — no source reading required.

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
- Nothing else. The three quickstart services and the standalone server are
  all built from source by `docker compose up --build`; no local JDK or
  Maven install is required to run them (`data-prism-quickstart-extension`'s
  own Docker-free integration test, `QuickstartSmokeIT`, is what proves the
  same chain works without Docker too, for contributors who do have one).

## Start it

```sh
docker compose up --build
```

The first run builds four images (a Maven reactor build inside each of
three), so it takes a few minutes; later runs are fast. When it settles you
have:

| Service | What it is | Reachable at |
|---|---|---|
| `cert-init` | Generates this run's throwaway TLS material, then exits | — |
| `issuer` | The local JWT issuer (`data-prism-quickstart-issuer`) | `https://localhost:8544` |
| `fixtures` | The synthetic customer API (`data-prism-quickstart-fixtures`) | Compose network only |
| `server` | The standalone server, with the quickstart's reviewed adapter loaded via `-Dloader.path` | `http://localhost:8080` |

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

```sh
TOKEN=$(curl -sk -X POST https://localhost:8544/token | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
```

With no request body, `/token` mints a token for a fixture development
principal, in the `investigator` role, for purpose `investigation` — enough
to call `get_entity_context`. To mint one for a different role, purpose or
case, POST a JSON body instead:

```sh
curl -sk -X POST https://localhost:8544/token \
  -H 'Content-Type: application/json' \
  -d '{"roles":["investigator"],"purpose":"investigation","caseId":"CASE-DEMO-1"}'
```

## Discover the tools

MCP's Streamable HTTP transport is a JSON-RPC exchange, not a plain REST
call: the first response carries an `Mcp-Session-Id` header every later
request in the session must echo back. An MCP-aware client (an agent, or the
[MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector)
pointed at `http://localhost:8080/mcp` with an `Authorization: Bearer`
header) handles this for you. To see the exchange itself:

```sh
SESSION=$(curl -sD - -o /tmp/init-response.json -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"quickstart-curl","version":"1.0.0"}}}' \
  | grep -i '^Mcp-Session-Id:' | tr -d '\r' | cut -d' ' -f2)

curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Both tools the platform ships today come back — `get_entity_context` and
`compare_entity_sources` — each with the same input schema: `entityType` and
`subjectId`, both required, nothing else — an MCP argument can never choose a
host, a path, a source or a caller identity (see `docs/configuration.md`).
Listing a tool is not the same as being allowed to call it: this quickstart's
`investigator` role (`docker/server/application.yaml`) is granted
`GET_ENTITY_CONTEXT` only, so the worked call below uses `get_entity_context`.
See [`docs/tools.md`](tools.md) for what `compare_entity_sources` returns,
worked against this repository's own fixture data.

## Invoke it

```sh
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_entity_context","arguments":{"entityType":"CUSTOMER","subjectId":"1001"}}}'
```

The fixture customer API holds a record for subject `1001` (also try
`1002`) whose real name is `Fixture Person One` and whose real email is
`fixture.person.one@example.invalid` — see
`data-prism-quickstart-fixtures`' own `CustomerController`. Neither value
appears in the response. What comes back instead looks like this (the
pseudonym and subject token are stable within a case but change if you mint
a token with a different `caseId`, and will differ from this exact example
on your own run):

```json
{
  "entityType": "CUSTOMER",
  "subject": "SUBJ-AE9Y",
  "sources": {"ORGANISATION_IDENTITY-SH48CYDX": "ANSWERED"},
  "findings": [],
  "entity": {
    "customerName": "Rowan Okafor (2TV5)",
    "email": "[REDACTED]",
    "status": "ACTIVE"
  }
}
```

`customerName` is a synthetic value, not the fixture's own; `email` is
redacted outright; `status` passes through because it was classified
`@NonSensitive`, a decision `data-prism-quickstart-extension`'s
`CustomerModel` states explicitly (see `docs/configuration.md` and
pack.md §30 on why a field nobody classified is never exposed at all,
rather than being disclosed by default).

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

## Stop and reset

```sh
docker compose down
```

stops every service and removes the containers, but leaves the `certs`
volume (and its generated keystore) in place, so the next `docker compose up`
reuses the same certificate rather than minting a new one. To reset
completely — a fresh certificate, and a clean slate for anything a future
version of this quickstart might persist:

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
