#!/usr/bin/env bash
# AC7: clock skew on one member. A container shares the host kernel clock, so skew is injected
# with libfaketime inside the member: the script restarts it with FAKETIME set (an offset such as
# "+90s") and LD_PRELOAD pointing at FAKETIME_LIB, a libfaketime shared object that the owner has
# made available inside the image or a mounted volume. Without FAKETIME_LIB the script writes
# "not run" and stops with status 3. The pause lease and expiresAt behaviour is read from the load
# result afterwards. Usage: FAKETIME_LIB=<path in the container> fault-clock-skew.sh [offset] [service]
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
offset="${1:-+90s}"; svc="${2:-server-c}"
out="$(summary_file fault-clock-skew)"; : > "$out"
if [ -z "${FAKETIME_LIB:-}" ]; then note "$out" "clock skew: not run (FAKETIME_LIB not set)"; exit 3; fi
# Stage the skew in a throwaway override so the repository files stay untouched.
override="$(mktemp)"; trap 'rm -f "$override"' EXIT
cat > "$override" <<YAML
services:
  $svc:
    environment:
      LD_PRELOAD: $FAKETIME_LIB
      FAKETIME: "$offset"
YAML
dc -f "$override" up -d --force-recreate --no-deps "$svc" >/dev/null
if wait_ready "$svc" 300; then note "$out" "$svc ready with skew $offset within 300 s: yes"; else note "$out" "$svc ready with skew $offset within 300 s: no"; fi
note "$out" "ready members with skew applied: $(member_count)"
note "$out" "undo: dc up -d --force-recreate $svc without the override; record the pause lease and expiresAt result by hand"
