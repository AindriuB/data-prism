#!/usr/bin/env bash
# One tools/call over Streamable HTTP with a correlation header.
# The endpoint host is a placeholder; set DATAPRISM_MCP_URL, DATAPRISM_MCP_TOKEN
# and MCP_SESSION (the Mcp-Session-Id from a prior initialize) at run time.
#
# CORRELATION_VARIANT=opaque       sends X-Correlation-ID (the default)
# CORRELATION_VARIANT=traceparent  sends a W3C traceparent value
set -euo pipefail

url="${DATAPRISM_MCP_URL:-https://data-prism.example.invalid/mcp}"
: "${DATAPRISM_MCP_TOKEN:?set DATAPRISM_MCP_TOKEN}"
MCP_PROTOCOL_VERSION="${MCP_PROTOCOL_VERSION:-2025-06-18}"  # the version negotiated by initialize
: "${MCP_SESSION:?set MCP_SESSION to the Mcp-Session-Id from initialize}"

if [ "${CORRELATION_VARIANT:-opaque}" = "traceparent" ]; then
  header="traceparent: 00-$(openssl rand -hex 16)-$(openssl rand -hex 8)-01"
else
  header="X-Correlation-ID: $(uuidgen | tr '[:upper:]' '[:lower:]')"
fi

curl -s -X POST "$url" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer ${DATAPRISM_MCP_TOKEN}" \
  -H "Mcp-Session-Id: ${MCP_SESSION}" \
  -H "MCP-Protocol-Version: ${MCP_PROTOCOL_VERSION}" \
  -H "$header" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_entity_context","arguments":{"entityType":"CUSTOMER","subjectId":"example-subject"}}}'
