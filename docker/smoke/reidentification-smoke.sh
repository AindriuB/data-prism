#!/usr/bin/env bash
# Re-identification smoke test (task 262). Needs Docker with Compose v2 (2.24.4
# or later), curl and python3. Runs the same steps docs/quickstart.md shows,
# against the quickstart Compose stack, and exits 0 only when:
#   - the resolved identifier equals the fixture's subject id, and
#   - the audit holds the expected decisions in order, and
#   - the requester's own approval attempt was refused with SELF_APPROVAL.
# Run from anywhere:  bash docker/smoke/reidentification-smoke.sh
#
# By default it builds the images from this working tree (compose.build.yaml);
# set SMOKE_IMAGES=published to use the published ${QUICKSTART_IMAGE_TAG:-0.8.0}
# images instead. Every wait is bounded; the stack runs under its own project
# name on ephemeral loopback ports, so it never touches a stack you have running.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT="data-prism-reid-smoke-$$"
WAIT_SECONDS=240
CASE_ID="CASE-SMOKE-1"
SUBJECT_ID="1001"
WORK="$(mktemp -d)"

files=(-f "${ROOT}/compose.yaml")
if [ "${SMOKE_IMAGES:-build}" != "published" ]; then
  files+=(-f "${ROOT}/compose.build.yaml")
fi
# Ephemeral loopback host ports, so a running quickstart on 8080, 8081 or 8544 is
# untouched. `!override` needs Compose 2.24.4.
cat > "${WORK}/ports.yaml" <<'YAML'
services:
  issuer:
    ports: !override
      - "127.0.0.1::8544"
  server:
    ports: !override
      - "127.0.0.1::8080"
      - "127.0.0.1::8081"
YAML
COMPOSE=(docker compose -p "$PROJECT" "${files[@]}" -f "${WORK}/ports.yaml")

cleanup() {
  status=$?
  echo "== tearing down"
  "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

if [ "${SMOKE_IMAGES:-build}" = "published" ]; then
  # Always run what is published: a 0.8.0 tag built locally must not stand in for it.
  echo "== pulling the published images"
  "${COMPOSE[@]}" pull --quiet >/dev/null 2>&1 || fail "could not pull the published images"
fi
echo "== starting the stack (project ${PROJECT})"
"${COMPOSE[@]}" up -d --build >/dev/null 2>&1 || fail "docker compose up did not succeed"

hostport() { "${COMPOSE[@]}" port "$1" "$2" | sed 's/^0\.0\.0\.0:/127.0.0.1:/'; }
ISSUER="https://$(hostport issuer 8544)"
MCP="http://$(hostport server 8080)"
OPERATOR="http://$(hostport server 8081)"

echo "== waiting (at most ${WAIT_SECONDS}s) for the server"
deadline=$((SECONDS + WAIT_SECONDS))
until curl -sf --max-time 3 -o /dev/null "${MCP}/health"; do
  [ "$SECONDS" -lt "$deadline" ] || fail "the server did not become healthy within ${WAIT_SECONDS}s"
  sleep 2
done
until curl -sk --max-time 3 -o /dev/null "${ISSUER}/jwks"; do
  [ "$SECONDS" -lt "$deadline" ] || fail "the issuer did not answer within ${WAIT_SECONDS}s"
  sleep 2
done

json_get() { python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1],""))' "$1"; }

mint() { # <subject> <roles-json> <audience> <scope>
  local body
  body="{\"subject\":\"$1\",\"roles\":$2,\"purpose\":\"investigation\",\"caseId\":\"${CASE_ID}\""
  [ -n "$3" ] && body="${body},\"audience\":\"$3\""
  [ -n "$4" ] && body="${body},\"scope\":\"$4\""
  curl -sk --max-time 10 -X POST "${ISSUER}/token" -H 'Content-Type: application/json' -d "${body}}" \
    | json_get access_token
}

OP_AUD="data-prism-quickstart-operator"
MCP_TOKEN="$(mint smoke-requester '["investigator"]' "" "")"
# The requester also holds the approver role, so refusing their own approval is
# the four-eyes rule (SELF_APPROVAL) and not merely a missing permission.
REQ_TOKEN="$(mint smoke-requester '["investigator","approver"]' "$OP_AUD" operator)"
APP_TOKEN="$(mint smoke-approver '["approver"]' "$OP_AUD" operator)"
[ -n "$MCP_TOKEN" ] && [ -n "$REQ_TOKEN" ] && [ -n "$APP_TOKEN" ] || fail "could not mint the three tokens"

echo "== get_entity_context for CUSTOMER ${SUBJECT_ID}"
headers="${WORK}/headers"
curl -s --max-time 20 -D "$headers" -o /dev/null -X POST "${MCP}/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer ${MCP_TOKEN}" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"reidentification-smoke","version":"1.0.0"}}}'
SESSION="$(grep -i '^Mcp-Session-Id:' "$headers" | tr -d '\r' | cut -d' ' -f2 || true)"
[ -n "$SESSION" ] || fail "no Mcp-Session-Id from initialize"
mcp() { curl -s --max-time 20 -X POST "${MCP}/mcp" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Authorization: Bearer ${MCP_TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION}" -d "$1"; }
mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
reply="$(mcp "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_entity_context\",\"arguments\":{\"entityType\":\"CUSTOMER\",\"subjectId\":\"${SUBJECT_ID}\"}}}")"
PSEUDONYM="$(printf '%s' "$reply" | python3 -c '
import json, sys
raw = sys.stdin.read()
body = next((l[5:].strip() for l in raw.splitlines() if l.startswith("data:")), raw)
print(json.loads(body)["result"]["structuredContent"]["subject"])')"
[ -n "$PSEUDONYM" ] || fail "no pseudonym in the get_entity_context answer"
[ "$PSEUDONYM" != "$SUBJECT_ID" ] || fail "the answer carried the real subject id"

audit() { "${COMPOSE[@]}" logs --no-color server 2>/dev/null; }

# The scope id is not in the answer; it is in the audit line the call wrote (scope=...).
# Read the log into a variable first: a pipe that closes early would SIGPIPE under pipefail.
LOG="$(audit)"
SCOPE_ID="$(grep -m1 ' tool=get_entity_context ' <<<"$LOG" | sed -E 's/.* scope=([^ ]*) .*/\1/' || true)"
[ -n "$SCOPE_ID" ] || fail "no scope id in the audit of the get_entity_context call"

op() { # <token> <method> <path> [body]  -> prints "<body> <http-status>"
  local args=(-s --max-time 20 -w ' %{http_code}' -X "$2" "${OPERATOR}$3"
              -H "Authorization: Bearer $1" -H 'Content-Type: application/json')
  if [ "$#" -ge 4 ]; then args+=(-d "$4"); fi
  curl "${args[@]}"
}
field() { python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get(sys.argv[1],""))' "$1"; }
split() { RESP_BODY="${1% *}"; RESP_CODE="${1##* }"; }

echo "== request re-identification"
# Built outside the command substitution: macOS's bash 3.2 brace-expands a quoted
# {a,b} inside "$( ... )".
REQUEST_BODY="{\"scopeId\":\"${SCOPE_ID}\",\"namespace\":\"NONE\",\"syntheticValue\":\"${PSEUDONYM}\",\"purpose\":\"investigation\",\"caseId\":\"${CASE_ID}\"}"
split "$(op "$REQ_TOKEN" POST /operator/reidentifications "$REQUEST_BODY")"
[ "$RESP_CODE" = 202 ] || fail "request answered ${RESP_CODE}, expected 202 (body: ${RESP_BODY})"
APPROVAL_ID="$(printf '%s' "$RESP_BODY" | field approvalId)"
[ -n "$APPROVAL_ID" ] || fail "no approval id"

echo "== the requester approving their own request must be refused"
split "$(op "$REQ_TOKEN" POST "/operator/reidentifications/${APPROVAL_ID}/approve")"
[ "$RESP_CODE" = 409 ] && [ "$(printf '%s' "$RESP_BODY" | field code)" = SELF_APPROVAL ] \
  || fail "self-approval answered ${RESP_CODE}, expected 409 SELF_APPROVAL"

echo "== the requester collecting before approval must be refused"
split "$(op "$REQ_TOKEN" GET "/operator/reidentifications/${APPROVAL_ID}")"
[ "$RESP_CODE" = 409 ] && [ "$(printf '%s' "$RESP_BODY" | field code)" = APPROVAL_NOT_APPROVED ] \
  || fail "early collect answered ${RESP_CODE}, expected 409 APPROVAL_NOT_APPROVED"

echo "== approve as the second principal"
split "$(op "$APP_TOKEN" POST "/operator/reidentifications/${APPROVAL_ID}/approve")"
[ "$RESP_CODE" = 200 ] || fail "approve answered ${RESP_CODE}, expected 200 (body: ${RESP_BODY})"

echo "== collect"
split "$(op "$REQ_TOKEN" GET "/operator/reidentifications/${APPROVAL_ID}")"
[ "$RESP_CODE" = 200 ] || fail "collect answered ${RESP_CODE}, expected 200 (body: ${RESP_BODY})"
RESOLVED="$(printf '%s' "$RESP_BODY" | field subjectId)"
[ "$RESOLVED" = "$SUBJECT_ID" ] || fail "resolved identifier does not match the fixture subject"

echo "== the audit must hold the decisions in order"
expected="ALLOW:REQUESTED,DENY:SELF_APPROVAL,DENY:APPROVAL_NOT_APPROVED,ALLOW:APPROVED,ALLOW:RESOLVED"
LOG="$(audit)"
actual="$(grep ' tool=reidentify ' <<<"$LOG" | sed -E 's/.* decision=([^ ]*) .*/\1/' | paste -sd, -)"
[ "$actual" = "$expected" ] || fail "audit decisions were '${actual}', expected '${expected}'"
if grep ' tool=reidentify ' <<<"$LOG" | grep -q "subject=${SUBJECT_ID} "; then
  fail "a reidentify audit line carries the real subject id"
fi
echo "PASS: ${PSEUDONYM} resolved to the fixture subject ${SUBJECT_ID}; self-approval refused; audit in order."
