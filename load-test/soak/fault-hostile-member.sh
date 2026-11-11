#!/usr/bin/env bash
# AC7: a hostile member under load (tamper flood). Watches the three things the pass criteria name:
# member CPU stays bounded, the clusterSecurity readiness contributor goes DOWN under the default
# unverified-member.action (refuse), and the cluster recovers after the hostile member leaves.
#
# The flood itself is not shipped here: it needs a Hazelcast client that writes garbage into the
# sealed maps, and none is part of this repository's harness. The owner supplies it as
# TAMPER_FLOOD_CMD, a command run for FLOOD_SECONDS (default 300) on the member network; it is
# started in the background and killed at the end. Without it the script stops with status 3 and
# writes "not run".
#
# The DOWN to watch for is the readiness probe's, not the liveness probe's (the default /health answers 200
# whatever the cluster's state). The script therefore also refuses without HEALTH_PATH naming a readiness path
# (for example /actuator/health/readiness) and HEALTH_BEARER, a token the readiness path accepts (it is sent to
# the member and never printed). Before the flood every member must answer that path 200: that is the baseline,
# and a member that does not makes the run "not run".
# Usage: TAMPER_FLOOD_CMD='<command>' HEALTH_PATH=/actuator/health/readiness HEALTH_BEARER=<token> \
#        fault-hostile-member.sh
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
out="$(summary_file fault-hostile-member)"; : > "$out"
provenance_begin "$out"
if [ -z "${TAMPER_FLOOD_CMD:-}" ]; then
  note "$out" "hostile member: not run (TAMPER_FLOOD_CMD not set)"
  exit 3
fi
case "${HEALTH_PATH:-}" in
  *readiness*) ;;
  *) note "$out" "hostile member: not run (HEALTH_PATH must name the readiness path)"; exit 3 ;;
esac
if [ -z "${HEALTH_BEARER:-}" ]; then
  note "$out" "hostile member: not run (HEALTH_BEARER not set)"
  exit 3
fi
flood="${FLOOD_SECONDS:-300}"
for m in "${members[@]}"; do
  if [ "$(health_code "$m")" != "200" ]; then
    note "$out" "hostile member: not run (a member's readiness baseline is not 200)"
    exit 3
  fi
done
note "$out" "readiness baseline: every member answered 200 before the flood"
note "$out" "cpu before: a=$(cpu_percent server-a) b=$(cpu_percent server-b) c=$(cpu_percent server-c)"
bash -c "$TAMPER_FLOOD_CMD" >/dev/null 2>&1 &
pid=$!
peak=0; down_seen=no; elapsed=0
while [ "$elapsed" -lt "$flood" ]; do
  sleep 10; elapsed=$((elapsed + 10))
  for m in "${members[@]}"; do
    c="$(cpu_percent "$m" | tr -d '%')"
    case "$c" in ''|*[!0-9.]*) ;; *) peak="$(awk -v a="$peak" -v b="$c" 'BEGIN{print (b>a)?b:a}')" ;; esac
    [ "$(health_code "$m")" = "503" ] && down_seen=yes
  done
done
kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
note "$out" "peak member cpu percent during the flood: $peak"
note "$out" "a member's readiness went DOWN (503) during the flood: $down_seen"
waited=0; recovered=no
while [ "$waited" -lt 300 ]; do
  [ "$(member_count)" = "3" ] && { recovered=yes; break; }
  sleep 10; waited=$((waited + 10))
done
note "$out" "all three members' readiness back to 200 within 300 s of the flood stopping: $recovered"
