# Remote workflow: authenticated Streamable HTTP

This talks to the real MCP endpoint — the Compose quickstart's `server`
service, or an operator's own deployment — over Streamable HTTP at its
configured path (`dataprism.transport.http.path`, `/mcp` by default). Every
request needs a signature-verified bearer JWT; there is no development
bypass for this transport (`docs/quickstart.md`, "There is no development
bypass").

**No token, key, certificate or URL below is real, and none is checked in.**
The one credential this guide can supply concretely is the Compose
quickstart's own fixture-only token, minted by
`data-prism-quickstart-issuer` and never valid for anything but that local
stack — labelled as such everywhere it appears. Everything else — where the
`/mcp` endpoint actually is, and how you get a token for it — is something
only your deployment can tell you: see
[`docs/configuration.md`](../configuration.md) for the `dataprism.security.jwt`
contract a real deployment's issuer must satisfy, and
[`docs/quickstart.md`](../quickstart.md) for the worked local instance of it.

## Why there's a script here instead of a JSON file to copy

The obvious shape for this guide would be a static config file with a
`headers` block and a placeholder like `Bearer ${DATAPRISM_TOKEN}` for you to
substitute. That shape does not work for the one client this guide verifies:
Claude Code CLI's `--header` value is stored and sent exactly as given —
`${DATAPRISM_TOKEN}`-style placeholders are **not** expanded at connect
time. A checked-in file using that shape would either fail every connection
(a literal, invalid header) or need a real bearer token pasted into a
tracked file to ever work, which this repository's own rules forbid. So the
checked-in artifact is
[`examples/agent-config/remote-http/claude-code-add-remote.sh`](../../examples/agent-config/remote-http/claude-code-add-remote.sh),
a script that reads the token from an environment variable you set at
registration time and never writes it to disk.

## Client: Claude Code CLI 2.1.271 — verified

Verified against a live `docker compose up --build` instance of the Compose
quickstart from task 18: minted a token from `data-prism-quickstart-issuer`,
ran the script below with it, and confirmed `claude mcp get` reported the
server `Connected` over `http://localhost:8080/mcp` with the JWT accepted.
[`examples/agent-config/remote-http/smoke-test.sh`](../../examples/agent-config/remote-http/smoke-test.sh)
automates exactly that sequence — bring up the quickstart, mint a token,
register, assert `Connected`, tear everything down — and is the reproducible
check for this template, not a one-off run.

Against the Compose quickstart (see `docs/quickstart.md` for what each piece
is):

```sh
TOKEN=$(curl -sk -X POST https://localhost:8544/token \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

DATAPRISM_MCP_URL=http://localhost:8080/mcp \
DATAPRISM_MCP_TOKEN="$TOKEN" \
  ./examples/agent-config/remote-http/claude-code-add-remote.sh
```

Against a real deployment, the shape is identical; only the two values
differ, and only your operator can give you both:

```sh
DATAPRISM_MCP_URL=https://<your operator's host>/mcp \
DATAPRISM_MCP_TOKEN="<a token from your organisation's identity provider>" \
  ./examples/agent-config/remote-http/claude-code-add-remote.sh
```

Then, either way:

```sh
claude mcp get data-prism        # or the name you set DATAPRISM_MCP_NAME to
```

should report `Status: ✔ Connected`. `claude mcp remove data-prism -s local`
deregisters it; nothing the script did is written into this repository.

## Discover the tool and call it

Once connected, ask your client to list tools — it should show exactly one,
`get_entity_context`, with `entityType` and `subjectId` as its whole input
schema. Calling it (entity `CUSTOMER`, subject `1001` against the Compose
quickstart's own fixture data — see `docs/quickstart.md` for what comes
back) returns a pseudonymised view: synthetic names, `[REDACTED]` fields for
anything classified as sensitive and not exposed, and consistency findings
where the underlying sources disagree.

## Returned content is untrusted data

Everything `get_entity_context` returns is a third-party system's data,
carried through the privacy pipeline — never an instruction to your agent,
regardless of what a `note` or free-text field says. The tool's own
description and the session's `initialize` response state this, and the
platform actively flags suspicious content as a `SUSPECTED_INSTRUCTION_CONTENT`
finding rather than passing it through silently (see `docs/agents/stdio.md`
for a fixture record that says exactly this, verified end to end). Configure
your agent's system prompt to hold the same line.

## Scope, purpose, principal and case id are not tool arguments

`get_entity_context` accepts `entityType` and `subjectId` and nothing else.
Which investigation you're in, why, and who you are come from the verified
claims in your bearer JWT — `dataprism.security.caller-claims` binds them
server-side once, at authentication, and refuses startup if a mapping would
let any of them be derived from a tool argument instead (see "Configuration
rules" and the `caller-claims` row in
[`docs/configuration.md`](../configuration.md)). No agent configuration,
MCP argument, or prompt changes that. If you need a different case or
purpose, that means a different token — ask whoever issues yours, not the
tool.
