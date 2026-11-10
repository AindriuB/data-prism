#!/usr/bin/env bash
# AC5, fault 2: kill a partition owner and its backup together. With three members and one
# backup, any two members together hold the owner and the backup of some partitions, so killing
# two members at once loses both copies of those partitions. Re-identification index loss must then
# behave as docs/multiple-instances.md documents; the survivor must not accept a stale pause
# outside the lease. Usage: fault-kill-owner-and-backup.sh [serviceA] [serviceB]
# Writes counts and findings only.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
a="${1:-server-b}"; b="${2:-server-c}"
out="$(summary_file fault-kill-owner-and-backup)"; : > "$out"
note "$out" "ready members before: $(member_count)"
dc kill "$a" "$b" >/dev/null
sleep 15
note "$out" "ready members with both killed: $(member_count)"
survivor_health="$(health_code server-a)"
note "$out" "survivor readiness code: $survivor_health"
dc start "$a" "$b" >/dev/null
for s in "$a" "$b"; do
  if wait_ready "$s" 300; then note "$out" "$s ready again within 300 s: yes"; else note "$out" "$s ready again within 300 s: no"; fi
done
note "$out" "ready members after: $(member_count)"
note "$out" "check by hand against the load result: calls refused or failed during the window, and whether a pause issued before the kill was honoured only inside its lease"
