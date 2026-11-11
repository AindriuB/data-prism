#!/usr/bin/env bash
# AC5, fault 2: kill a partition owner and its backup together. With three members and one
# backup, any two members together hold the owner and the backup of some partitions, so killing
# two members at once loses both copies of those partitions. Re-identification index loss must then
# behave as docs/multiple-instances.md documents; the survivor must not accept a stale pause
# outside the lease. Usage: fault-kill-owner-and-backup.sh [serviceA] [serviceB]
# Writes counts and findings only. "Answering health" is liveness (/health 200).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
a="${1:-server-b}"; b="${2:-server-c}"
out="$(summary_file fault-kill-owner-and-backup)"; : > "$out"
note "$out" "members answering health before: $(member_count)"
dc kill "$a" "$b" >/dev/null
sleep 15
note "$out" "members answering health with both killed: $(member_count)"
note "$out" "survivor health code: $(health_code server-a)"
dc start "$a" "$b" >/dev/null
for s in "$a" "$b"; do
  if wait_joined "$s" 300; then note "$out" "$s answering health and in a cluster of 3 within 300 s: yes"
  else note "$out" "$s answering health and in a cluster of 3 within 300 s: no"; fi
done
note "$out" "members answering health after: $(member_count)"
for m in "${members[@]}"; do note "$out" "$m 'Resolving class ... is not allowed' lines: $(resolving_class_count "$m")"; done
note "$out" "check by hand against the load result: calls refused or failed during the window, and whether a pause issued before the kill was honoured only inside its lease"
