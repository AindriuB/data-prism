#!/usr/bin/env bash
# Registers Data Prism with Claude Code using a STATIC correlation header.
# `claude mcp add --header` fixes the value at registration time, so every call
# from this registration carries the same id. Use it to tag a session or a
# workstation, not to give each call its own id; for a per-call id use the
# Python or TypeScript hook.
#
# For a static value, give each registration its own id, for example a
# workstation or project label that matches the configured pattern (a UUID).
set -euo pipefail

: "${DATAPRISM_MCP_TOKEN:?set DATAPRISM_MCP_TOKEN}"
url="${DATAPRISM_MCP_URL:-https://data-prism.example.invalid/mcp}"
id="${CORRELATION_ID:-$(uuidgen | tr 'A-Z' 'a-z')}"

claude mcp add --scope local --transport http data-prism "$url" \
  --header "Authorization: Bearer ${DATAPRISM_MCP_TOKEN}" \
  --header "X-Correlation-ID: ${id}"

# traceparent variant: replace the last header with
#   --header "traceparent: 00-$(openssl rand -hex 16)-$(openssl rand -hex 8)-01"
