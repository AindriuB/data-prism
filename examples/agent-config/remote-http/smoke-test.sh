#!/usr/bin/env bash
# Automated smoke test for the remote HTTP agent config template
# (claude-code-add-remote.sh) against the real Compose quickstart from
# task 18: docker compose up --build, mint a fixture token from the local
# issuer, register the server with the Claude Code CLI using this
# directory's template, and confirm it reports Connected. Tears everything
# down itself, including on failure.
#
# Requires: Docker with Compose v2, and the `claude` CLI (Claude Code) on
# PATH — the one agent client this guide's remote workflow is verified
# against (see docs/agents/remote-http.md). If `claude` is not installed,
# this script says so and exits 0 without pretending to have tested
# anything; it is not a substitute for the manual procedure documented for
# any other client in docs/agents/remote-http.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
server_name="data-prism-remote-smoke-test"

if ! command -v claude >/dev/null 2>&1; then
  echo "SKIP: the 'claude' CLI is not on PATH; nothing to smoke test here." >&2
  echo "See docs/agents/remote-http.md for the manual procedure for other clients." >&2
  exit 0
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
if echo "$status" | grep -q '✔ Connected'; then
  echo "PASS: $server_name connected via the remote HTTP template."
  exit 0
fi

echo "FAIL: $server_name did not report Connected:" >&2
echo "$status" | sed -E 's/(Bearer )[A-Za-z0-9._-]+/\1[REDACTED]/' >&2
exit 1
