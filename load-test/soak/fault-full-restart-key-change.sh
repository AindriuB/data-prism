#!/usr/bin/env bash
# AC6: change the cluster key or the name key as a PLANNED FULL RESTART (rolling rotation is
# deferred with 229). Every member goes down, one key is replaced by a new random one, and every member comes up.
# Expected: state loss. Approvals pending, the re-identification index and the counters start
# empty, and a pause is re-issued. This script does not print or keep either key.
# Usage: fault-full-restart-key-change.sh cluster|name
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_keys
which="${1:-}"
case "$which" in
  cluster) DATAPRISM_CLUSTER_KEY_MATERIAL="$(new_key)"; export DATAPRISM_CLUSTER_KEY_MATERIAL ;;
  name)    DATAPRISM_NAME_KEY_MATERIAL="$(new_key)";    export DATAPRISM_NAME_KEY_MATERIAL ;;
  *) echo "usage: $0 cluster|name" >&2; exit 2 ;;
esac
out="$(summary_file "fault-key-change-$which")"; : > "$out"
dc stop "${members[@]}" >/dev/null
# Recreate (not just start): the containers must read the new value from this environment.
dc up -d --force-recreate "${members[@]}" >/dev/null
for m in "${members[@]}"; do
  if wait_ready "$m" 300; then note "$out" "$m ready within 300 s: yes"; else note "$out" "$m ready within 300 s: no"; fi
done
note "$out" "key changed: $which; ready members: $(member_count)"
note "$out" "record by hand from the load result: pending approvals after restart, re-identification index entries, counters, and that a pause was re-issued"
