#!/usr/bin/env bash
# AC5, fault 3: roll all members, one at a time. The next member is restarted only after the previous one
# answers health AND its own log shows a cluster of three; if one does not rejoin within the bound, the roll
# stops there (a roll that went on would kill a second member of a cluster that has lost one).
# Usage: fault-roll-all-members.sh
# Writes counts and findings only.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
out="$(summary_file fault-roll-all-members)"; : > "$out"
provenance_begin "$out"
note "$out" "members answering health before: $(member_count)"
for m in "${members[@]}"; do
  dc restart "$m" >/dev/null
  if wait_joined "$m" 300; then note "$out" "$m restarted, answering health and in a cluster of 3 within 300 s: yes"
  else
    note "$out" "$m restarted, answering health and in a cluster of 3 within 300 s: no; roll stopped here"
    break
  fi
  note "$out" "members answering health after $m: $(member_count)"
done
for m in "${members[@]}"; do note "$out" "$m 'Resolving class ... is not allowed' lines: $(resolving_class_count "$m")"; done
