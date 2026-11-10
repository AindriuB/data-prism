#!/usr/bin/env bash
# AC5, fault 3: roll all members, one at a time, waiting for each to be ready before the next.
# Usage: fault-roll-all-members.sh
# Writes counts and findings only.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
out="$(summary_file fault-roll-all-members)"; : > "$out"
note "$out" "ready members before: $(member_count)"
for m in "${members[@]}"; do
  dc restart "$m" >/dev/null
  if wait_ready "$m" 300; then note "$out" "$m restarted and ready within 300 s: yes"
  else note "$out" "$m restarted and ready within 300 s: no"; fi
  note "$out" "ready members after $m: $(member_count)"
done
for m in "${members[@]}"; do note "$out" "$m 'Resolving class ... is not allowed' lines: $(resolving_class_count "$m")"; done
