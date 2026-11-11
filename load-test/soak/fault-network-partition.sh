#!/usr/bin/env bash
# AC7: a network partition with a pause issued on one side. Disconnects one member from the member
# network for PARTITION_SECONDS (default 120), then reconnects it at its own fixed member-network address (the
# one JOIN_MEMBERS lists). Issue the pause on the isolated side or the majority side by hand during the window
# (the pause is an operator action, not done here); lease behaviour, split-brain approval consumption and
# rejoin are read from the load result and the logs afterwards. An EXIT trap reconnects the member if the script
# is interrupted.
# Usage: fault-network-partition.sh [service]   (default server-c)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
svc="${1:-server-c}"; secs="${PARTITION_SECONDS:-120}"
out="$(summary_file fault-network-partition)"; : > "$out"
provenance "$out"
cid="$(dc ps -q "$svc")"
net="$(printf '%s' "$cid" | xargs docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' | tr ' ' '\n' | grep -m1 'cluster$' || true)"
if [ -z "$net" ]; then note "$out" "partition: not run (member network not found)"; exit 3; fi
# The address stays in this variable: it is used to reconnect, and never printed or written.
ip="$(docker inspect --format "{{(index .NetworkSettings.Networks \"$net\").IPAddress}}" "$cid")"
cut=0
restore() {
  if [ "$cut" = 1 ]; then
    docker network connect --ip "$ip" "$net" "$cid" >/dev/null 2>&1 || true
    cut=0
  fi
}
trap restore EXIT INT TERM
docker network disconnect "$net" "$cid"; cut=1
note "$out" "$svc cut from the member network for $secs s; issue the pause now"
sleep "$secs"
note "$out" "members answering health during the partition (default network still up): $(member_count)"
restore
if wait_joined "$svc" 300; then note "$out" "$svc answering health and back in a cluster of 3 within 300 s: yes"
else note "$out" "$svc answering health and back in a cluster of 3 within 300 s: no"; fi
note "$out" "members answering health after: $(member_count)"
