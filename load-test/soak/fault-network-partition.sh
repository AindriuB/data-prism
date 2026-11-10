#!/usr/bin/env bash
# AC7: a network partition with a pause issued on one side. Disconnects one member from the member
# network for PARTITION_SECONDS (default 120), then reconnects it. Issue the pause on the isolated
# side or the majority side by hand during the window (the pause is an operator action, not done
# here); lease behaviour, split-brain approval consumption and rejoin are read from the load
# result and the logs afterwards.
# Usage: fault-network-partition.sh [service]   (default server-c)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
svc="${1:-server-c}"; secs="${PARTITION_SECONDS:-120}"
out="$(summary_file fault-network-partition)"; : > "$out"
net="$(dc ps -q "$svc" | xargs docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' | tr ' ' '\n' | grep -m1 'cluster$' || true)"
if [ -z "$net" ]; then note "$out" "partition: not run (member network not found)"; exit 3; fi
cid="$(dc ps -q "$svc")"
docker network disconnect "$net" "$cid"
note "$out" "$svc cut from the member network for $secs s; issue the pause now"
sleep "$secs"
note "$out" "ready members during the partition (default network still up): $(member_count)"
docker network connect "$net" "$cid"
if wait_ready "$svc" 300; then note "$out" "$svc rejoined and ready within 300 s: yes"; else note "$out" "$svc rejoined and ready within 300 s: no"; fi
note "$out" "ready members after: $(member_count)"
note "$out" "note: reconnecting a container may not restore its fixed member-network address; if the member does not rejoin, that is a finding, not a pass"
