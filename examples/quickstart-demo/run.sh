#!/usr/bin/env bash
# One-command quickstart demo.
#
# Mints a token from the local issuer, runs the MCP initialize /
# notifications/initialized handshake, calls get_entity_context for the
# fixture customer, and prints the fixture's real values beside the
# pseudonymised response the server actually returned — the same sequence
# docs/quickstart.md walks through by hand across four curl blocks, as one
# runnable command.
#
# Requires a Compose stack that is already up (`docker compose up --build`
# from the repository root; see docs/quickstart.md). This script does not
# start or stop it.
#
# Fails loudly — non-zero exit, one-line reason on stderr — if the server is
# not reachable, a token cannot be minted, or the MCP call returns a
# JSON-RPC or tool error. It never prints a success line it has not verified.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$script_dir/mcp-handshake.sh"

health_url="${DATAPRISM_HEALTH_URL:-http://localhost:8080/health}"
mcp_url="${DATAPRISM_MCP_URL:-http://localhost:8080/mcp}"
issuer_url="${DATAPRISM_ISSUER_URL:-https://localhost:8544/token}"
entity_type="${1:-CUSTOMER}"
subject_id="${2:-1001}"

# The fixture's own real values for subject 1001, per
# data-prism-quickstart-fixtures' own CustomerController — synthetic by
# construction, never a real person (see docs/quickstart.md).
raw_name="Fixture Person One"
raw_email="fixture.person.one@example.invalid"

if ! curl -sf -o /dev/null "$health_url"; then
  echo "FAIL: the standalone server is not reachable at $health_url; run 'docker compose up --build' first." >&2
  exit 1
fi

token="$(mcp_mint_token "$issuer_url")"
if [ -z "$token" ]; then
  echo "FAIL: could not mint a token from $issuer_url" >&2
  exit 1
fi

mcp_initialize "$mcp_url" "$token"
if [ -z "$MCP_SESSION" ]; then
  echo "FAIL: no Mcp-Session-Id from initialize at $mcp_url" >&2
  exit 1
fi

mcp_notify_initialized "$mcp_url" "$token" "$MCP_SESSION"

response="$(mcp_call_get_entity_context "$mcp_url" "$token" "$MCP_SESSION" "$entity_type" "$subject_id")"

parsed="$(printf '%s' "$response" | python3 -c '
import json, sys

raw = sys.stdin.read()

# The server negotiates SSE for tools/call when the Accept header offers
# both application/json and text/event-stream (it always does here), so the
# body usually arrives frame-wrapped ("id: ...\nevent: message\ndata:
# {...}") rather than as bare JSON. Pull the JSON out of the first "data:"
# field if present; fall back to treating the whole body as JSON so this
# still works if the server ever answers with a plain application/json body.
body = raw
for line in raw.splitlines():
    if line.startswith("data:"):
        body = line[len("data:"):].strip()
        break

try:
    doc = json.loads(body)
except Exception:
    print("PARSE_ERROR")
    sys.exit(0)

if "error" in doc:
    print("RPC_ERROR")
    print(doc["error"].get("message", ""))
    sys.exit(0)

result = doc.get("result") or {}
if result.get("isError"):
    print("TOOL_ERROR")
    content = result.get("content") or [{}]
    print(content[0].get("text", ""))
    sys.exit(0)

structured = result.get("structuredContent") or {}
entity = structured.get("entity") or {}
print("OK")
print(structured.get("subject", ""))
print(entity.get("customerName", ""))
print(entity.get("email", ""))
')"

status="$(sed -n '1p' <<<"$parsed")"
case "$status" in
  PARSE_ERROR)
    echo "FAIL: get_entity_context response was not valid JSON: $response" >&2
    exit 1
    ;;
  RPC_ERROR)
    message="$(sed -n '2p' <<<"$parsed")"
    echo "FAIL: get_entity_context returned a JSON-RPC error: $message" >&2
    exit 1
    ;;
  TOOL_ERROR)
    message="$(sed -n '2p' <<<"$parsed")"
    echo "FAIL: get_entity_context reported a tool error: $message" >&2
    exit 1
    ;;
  OK)
    ;;
  *)
    echo "FAIL: could not read get_entity_context's response: $response" >&2
    exit 1
    ;;
esac

pseudo_subject="$(sed -n '2p' <<<"$parsed")"
pseudo_name="$(sed -n '3p' <<<"$parsed")"
pseudo_email="$(sed -n '4p' <<<"$parsed")"

if [ -z "$pseudo_subject" ] || [ -z "$pseudo_name" ]; then
  echo "FAIL: get_entity_context's response was missing an expected field: $response" >&2
  exit 1
fi

if [ "$pseudo_name" = "$raw_name" ] || [ "$pseudo_email" = "$raw_email" ]; then
  echo "FAIL: get_entity_context returned the fixture's raw value, unpseudonymised: $response" >&2
  exit 1
fi

echo "PASS: get_entity_context for $entity_type $subject_id returned a pseudonymised response."
echo
echo "  field         real fixture value                     pseudonymised response"
echo "  ------------  -------------------------------------  --------------------------"
printf '  %-12s  %-37s  %s\n' "subjectId" "$subject_id" "$pseudo_subject"
printf '  %-12s  %-37s  %s\n' "customerName" "$raw_name" "$pseudo_name"
printf '  %-12s  %-37s  %s\n' "email" "$raw_email" "$pseudo_email"
exit 0
