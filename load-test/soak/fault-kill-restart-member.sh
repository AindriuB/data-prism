#!/usr/bin/env bash
# AC5, fault 1: kill one member under load and restart it. Run while a scenario drives the
# load balancer. Usage: fault-kill-restart-member.sh [service]   (default server-b)
# Writes counts and findings only.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
svc="${1:-server-b}"
out="$(summary_file fault-kill-restart-member)"; : > "$out"
note "$out" "ready members before: $(member_count)"
dc kill "$svc" >/dev/null
sleep 10
note "$out" "ready members with $svc killed: $(member_count)"
dc start "$svc" >/dev/null
if wait_ready "$svc" 240; then note "$out" "$svc rejoined and ready within 240 s: yes"
else note "$out" "$svc rejoined and ready within 240 s: no"; fi
note "$out" "ready members after: $(member_count)"
for m in "${members[@]}"; do note "$out" "$m 'Resolving class ... is not allowed' lines: $(resolving_class_count "$m")"; done
