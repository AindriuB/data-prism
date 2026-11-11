#!/usr/bin/env bash
# AC6: change the cluster key or the name key as a PLANNED FULL RESTART (rolling rotation is
# deferred with 229). Every member goes down, one key is replaced, and every member comes up.
# Expected: state loss. Approvals pending, the re-identification index and the counters start
# empty, and a pause is re-issued.
#
# The new key is the owner's input, never generated here: a key generated and discarded would leave every later
# script recreating members with the old key (CLUSTER_KEY_MISMATCH). Export the new value as
# NEW_CLUSTER_KEY_MATERIAL or NEW_NAME_KEY_MATERIAL (64 hex characters, `openssl rand -hex 32`); the script
# refuses to run without it, and never prints it. Afterwards the owner exports the new value as
# DATAPRISM_CLUSTER_KEY_MATERIAL or DATAPRISM_NAME_KEY_MATERIAL in the shell that runs the later scripts.
# Usage: NEW_CLUSTER_KEY_MATERIAL=... fault-full-restart-key-change.sh cluster
#        NEW_NAME_KEY_MATERIAL=...    fault-full-restart-key-change.sh name
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_keys
which="${1:-}"
case "$which" in
  cluster) new="${NEW_CLUSTER_KEY_MATERIAL:-}" ;;
  name)    new="${NEW_NAME_KEY_MATERIAL:-}" ;;
  *) echo "usage: $0 cluster|name" >&2; exit 2 ;;
esac
out="$(summary_file "fault-key-change-$which")"; : > "$out"
if [ "${#new}" -lt 32 ]; then
  note "$out" "key change: not run (export NEW_$(printf '%s' "$which" | tr a-z A-Z)_KEY_MATERIAL, 32 or more characters)"
  exit 3
fi
# A recreate discards a container's log, so the 'Resolving class' lines are counted before it, and again after.
for m in "${members[@]}"; do note "$out" "$m 'Resolving class ... is not allowed' lines before the restart: $(resolving_class_count "$m")"; done
dc stop "${members[@]}" >/dev/null
case "$which" in
  cluster) DATAPRISM_CLUSTER_KEY_MATERIAL="$new"; export DATAPRISM_CLUSTER_KEY_MATERIAL ;;
  name)    DATAPRISM_NAME_KEY_MATERIAL="$new";    export DATAPRISM_NAME_KEY_MATERIAL ;;
esac
# Recreate (not just start): the containers must read the new value from this environment.
dc up -d --force-recreate "${members[@]}" >/dev/null
for m in "${members[@]}"; do
  if wait_joined "$m" 300; then note "$out" "$m answering health and in a cluster of 3 within 300 s: yes"
  else note "$out" "$m answering health and in a cluster of 3 within 300 s: no"; fi
done
note "$out" "key changed: $which; members answering health: $(member_count)"
for m in "${members[@]}"; do note "$out" "$m 'Resolving class ... is not allowed' lines after the restart: $(resolving_class_count "$m")"; done
note "$out" "record by hand from the load result: pending approvals after restart, re-identification index entries, counters, and that a pause was re-issued"
