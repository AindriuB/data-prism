#!/usr/bin/env bash
# Prints the build identity the three members run NOW, read from their containers (the driver's target-mode
# result runs this when it writes, so the result records what ran then):
#   <12 hex of the image ID> source=<hash12> commit=<short SHA> dirty=<yes|no>
# One identity when all three agree, otherwise the three joined with " + " (a mixed-build cluster is a finding).
# Anything unreadable (docker unavailable or failing, a member with no container, a missing build label) prints
# `unknown`, says why on stderr, and exits 1: it never prints a plausible value for a lookup that failed.
# It reads containers by compose project and service labels, so it needs neither the keys nor the stamp.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
ids=()
for m in "${members[@]}"; do
  id="$(container_identity "$m")"
  case "$id" in
    unknown|absent) echo "$m: $id" >&2; echo unknown; exit 1 ;;
  esac
  ids+=("$id")
done
if [ "${ids[0]}" = "${ids[1]}" ] && [ "${ids[1]}" = "${ids[2]}" ]; then printf '%s\n' "${ids[0]}"
else printf '%s + %s + %s\n' "${ids[0]}" "${ids[1]}" "${ids[2]}"; fi
