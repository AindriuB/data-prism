#!/usr/bin/env bash
# Shared MCP Streamable HTTP helpers: mint a fixture token from the local
# issuer, run the initialize / notifications/initialized handshake, and call
# get_entity_context. Meant to be sourced, not executed — both run.sh (the
# one-command quickstart demo) and
# examples/agent-config/remote-http/smoke-test.sh source this file so the
# handshake logic exists in exactly one place.
#
# Every curl call here mirrors docs/quickstart.md's own worked example by
# hand; keep the two in step if either changes.

# mcp_mint_token <issuer_url>
# Prints the minted access token on stdout, or an empty string if the issuer
# did not return one (including if it is unreachable). Never writes the
# token anywhere but stdout.
mcp_mint_token() {
  local issuer_url="$1"
  curl -sk -X POST "$issuer_url" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null
}

# mcp_initialize <mcp_url> <token>
# Runs the MCP "initialize" request and sets two globals for the caller to
# read immediately after calling this function:
#   MCP_SESSION    the Mcp-Session-Id header from the response (empty if absent)
#   MCP_INIT_BODY  the response body, for error reporting if MCP_SESSION is empty
mcp_initialize() {
  local mcp_url="$1" token="$2" headers
  headers="$(mktemp)"
  MCP_INIT_BODY="$(curl -sD "$headers" -X POST "$mcp_url" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Authorization: Bearer $token" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"quickstart-demo","version":"1.0.0"}}}')"
  MCP_SESSION="$(grep -i '^Mcp-Session-Id:' "$headers" | tr -d '\r' | cut -d' ' -f2)"
  rm -f "$headers"
}

# mcp_notify_initialized <mcp_url> <token> <session>
# Sends the "notifications/initialized" notification that must follow a
# successful initialize before any other request in the session.
mcp_notify_initialized() {
  local mcp_url="$1" token="$2" session="$3"
  curl -s -X POST "$mcp_url" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Authorization: Bearer $token" \
    -H "Mcp-Session-Id: $session" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

# mcp_call_get_entity_context <mcp_url> <token> <session> <entity_type> <subject_id>
# Prints the raw tools/call JSON-RPC response body on stdout.
mcp_call_get_entity_context() {
  local mcp_url="$1" token="$2" session="$3" entity_type="$4" subject_id="$5"
  curl -s -X POST "$mcp_url" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Authorization: Bearer $token" \
    -H "Mcp-Session-Id: $session" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_entity_context\",\"arguments\":{\"entityType\":\"$entity_type\",\"subjectId\":\"$subject_id\"}}}"
}
