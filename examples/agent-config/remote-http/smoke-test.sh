#!/usr/bin/env bash
# Automated smoke test for the remote HTTP agent config template
# (claude-code-add-remote.sh) against the real Compose quickstart from
# task 18: docker compose up --build, mint a fixture token from the local
# issuer, register the server with the Claude Code CLI using this
# directory's template, confirm it reports Connected, then issue a real
# get_entity_context call over the same /mcp endpoint and assert on the
# pseudonymisation itself — not just that a connection was accepted. Tears
# everything down itself, including on failure.
#
# The registration step alone would pass against an endpoint that accepted
# the token but returned JSON-RPC errors, or returned the fixture's raw
# data untouched: it only proves TLS and bearer-token auth. The assertions
# below mirror QuickstartSmokeIT
# (data-prism-quickstart-extension/src/test/.../QuickstartSmokeIT.java):
# the two raw fixture values this quickstart ships (`Fixture Person One`,
# `fixture.person.one@example.invalid`) must be absent from the response,
# and a synthetic name plus a literal `[REDACTED]` must be present in their
# place.
#
# Requires: Docker with Compose v2, python3, and the `claude` CLI (Claude
# Code) on PATH — the one agent client this guide's remote workflow is
# verified against (see docs/agents/remote-http.md). If `claude` is not
# installed, this script says so and exits 77 (the autotools "skip"
# convention: distinct from both pass and fail) rather than exiting 0 and
# recording a pass for nothing tested; it is not a substitute for the
# manual procedure documented for any other client in
# docs/agents/remote-http.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
server_name="data-prism-remote-smoke-test"
skip_exit_code=77

if ! command -v claude >/dev/null 2>&1; then
  echo "SKIP: the 'claude' CLI is not on PATH; nothing to smoke test here." >&2
  echo "See docs/agents/remote-http.md for the manual procedure for other clients." >&2
  exit "$skip_exit_code"
fi

cleanup() {
  claude mcp remove "$server_name" -s local >/dev/null 2>&1 || true
  (cd "$repo_root" && docker compose down --volumes) >/dev/null 2>&1 || true
}
trap cleanup EXIT

(cd "$repo_root" && docker compose up --build -d)

health_url="http://localhost:8080/health"
issuer_url="https://localhost:8544/token"
attempts=30
until curl -sf -o /dev/null "$health_url"; do
  attempts=$((attempts - 1))
  if [ "$attempts" -le 0 ]; then
    echo "FAIL: $health_url never became healthy" >&2
    exit 1
  fi
  sleep 2
done

token=""
attempts=15
until [ -n "$token" ]; do
  token="$(curl -sk -X POST "$issuer_url" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null || true)"
  if [ -n "$token" ]; then
    break
  fi
  attempts=$((attempts - 1))
  if [ "$attempts" -le 0 ]; then
    echo "FAIL: could not mint a token from $issuer_url" >&2
    exit 1
  fi
  sleep 2
done

DATAPRISM_MCP_URL="http://localhost:8080/mcp" \
DATAPRISM_MCP_TOKEN="$token" \
DATAPRISM_MCP_NAME="$server_name" \
  "$repo_root/examples/agent-config/remote-http/claude-code-add-remote.sh"

status="$(claude mcp get "$server_name" 2>&1)"
if ! echo "$status" | grep -q '✔ Connected'; then
  echo "FAIL: $server_name did not report Connected:" >&2
  echo "$status" | sed -E 's/(Bearer )[A-Za-z0-9._-]+/\1[REDACTED]/' >&2
  exit 1
fi

# Connected proves TLS and bearer-token auth; it does not prove the tool
# call is actually answered by the privacy pipeline. Drive one real
# get_entity_context call over the same /mcp endpoint the registration just
# used, the same handshake docs/quickstart.md walks through by hand, and
# assert on the pseudonymisation itself.
mcp_url="http://localhost:8080/mcp"
init_headers="$(mktemp)"
init_body="$(curl -sD "$init_headers" -X POST "$mcp_url" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $token" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0.0"}}}')"
session="$(grep -i '^Mcp-Session-Id:' "$init_headers" | tr -d '\r' | cut -d' ' -f2)"
rm -f "$init_headers"

if [ -z "$session" ]; then
  echo "FAIL: no Mcp-Session-Id from initialize; response was:" >&2
  echo "$init_body" >&2
  exit 1
fi

curl -s -X POST "$mcp_url" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $token" \
  -H "Mcp-Session-Id: $session" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null

call_response="$(curl -s -X POST "$mcp_url" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $token" \
  -H "Mcp-Session-Id: $session" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_entity_context","arguments":{"entityType":"CUSTOMER","subjectId":"1001"}}}')"

# The two raw values data-prism-quickstart-fixtures' CustomerController ships
# for subject 1001 (see docs/quickstart.md). A privacy engine doing nothing —
# an adapter returning the fixture's own JSON untouched — fails every check
# below, not just the first.
if echo "$call_response" | grep -q "Fixture Person One"; then
  echo "FAIL: response contains the raw fixture name, unpseudonymised:" >&2
  echo "$call_response" >&2
  exit 1
fi
if echo "$call_response" | grep -q "fixture.person.one@example.invalid"; then
  echo "FAIL: response contains the raw fixture email, unpseudonymised:" >&2
  echo "$call_response" >&2
  exit 1
fi
if ! echo "$call_response" | grep -qE 'SUBJ-[A-Z0-9]+'; then
  echo "FAIL: response has no synthetic SUBJ- subject identifier:" >&2
  echo "$call_response" >&2
  exit 1
fi
if ! echo "$call_response" | grep -q '\[REDACTED\]'; then
  echo "FAIL: response has no [REDACTED] field where the sensitive, unexposed email belongs:" >&2
  echo "$call_response" >&2
  exit 1
fi

echo "PASS: $server_name connected via the remote HTTP template, and its"
echo "get_entity_context call returned a pseudonymised response with both"
echo "raw fixture values absent."
exit 0
