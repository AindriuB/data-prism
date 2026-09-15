#!/usr/bin/env bash
# Registers a remote Data Prism MCP server (Streamable HTTP, authenticated)
# with the Claude Code CLI client. See docs/agents/remote-http.md.
#
# There is no checked-in file with a working credential in it: Claude Code's
# `claude mcp add --header ...` stores the header value it is given verbatim
# (verified against this build — `${VAR}`-style placeholders are NOT expanded
# by the client at connect time, they are sent as the literal header value
# and the server correctly refuses them). The only safe way to configure a
# bearer token is to supply it at registration time, from an environment
# variable this script reads and never writes to disk.
#
# DATAPRISM_MCP_URL   Required. The deployment's Streamable HTTP endpoint,
#                      e.g. http://localhost:8080/mcp for the Compose
#                      quickstart, or your operator's real HTTPS URL.
# DATAPRISM_MCP_TOKEN  Required. A bearer JWT, acquired the way the
#                      deployment documents: the quickstart's own
#                      `data-prism-quickstart-issuer` (POST /token, see
#                      docs/quickstart.md) for local testing, or your
#                      organisation's OIDC/OAuth2 identity provider for a
#                      real deployment (docs/configuration.md,
#                      `dataprism.security.jwt`) — never a value invented or
#                      stored here.
# DATAPRISM_MCP_NAME   Optional. Server name Claude Code registers under.
#                      Defaults to "data-prism".
set -euo pipefail

: "${DATAPRISM_MCP_URL:?set DATAPRISM_MCP_URL to the target server /mcp endpoint}"
: "${DATAPRISM_MCP_TOKEN:?set DATAPRISM_MCP_TOKEN to a bearer JWT acquired from the documented issuer}"
name="${DATAPRISM_MCP_NAME:-data-prism}"

claude mcp add --scope local --transport http "$name" "$DATAPRISM_MCP_URL" \
  --header "Authorization: Bearer ${DATAPRISM_MCP_TOKEN}"

echo "Registered '$name'. Verify with: claude mcp get $name" >&2
