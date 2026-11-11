#!/usr/bin/env bash
# Shared helpers for the fault and capture scripts (task 263). Source it; do not run it.
#
# Output rule: a script prints and writes counts and findings only. No address, no payload
# byte, no key and no host detail is ever echoed, logged or written. Containers are named by
# service (server-a, server-b, server-c), never by address.
#
# The stack is the three-member overlay already brought up (see compose.soak.yaml). The
# cluster key and the name key must be exported in the environment of the caller, as for `up`.

soak_dir="$(CDPATH= cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(CDPATH= cd "$soak_dir/../.." && pwd)"
results_dir="${SOAK_RESULTS_DIR:-$repo_root/load-test/results}"   # git-ignored
mkdir -p "$results_dir"

compose_files=(-f "$repo_root/docker/multi-instance/compose.yaml" -f "$soak_dir/compose.soak.yaml")
if [ "${SOAK_BUILD:-0}" = "1" ]; then
  compose_files=(-f "$repo_root/docker/multi-instance/compose.yaml" -f "$repo_root/docker/multi-instance/compose.build.yaml" \
                 -f "$soak_dir/compose.soak.yaml")
fi
dc() { docker compose "${compose_files[@]}" "$@"; }

members=(server-a server-b server-c)

# summary_file <name>: where a script's counts-and-findings summary goes.
summary_file() { printf '%s/%s-summary.txt' "$results_dir" "$1"; }

# note <summary-file> <text>: one finding line, to the terminal and the summary.
note() { printf '%s\n' "$2" | tee -a "$1"; }

# health_code <service>: the HTTP status code of the member's health probe, or "none".
# The default path is the server's unauthenticated /health, which answers 200 while the process is up
# (liveness only: it does not reflect the clusterSecurity contributor). The actuator readiness path is
# behind the caller's authentication on the packaged server; to read it (the hostile-member run must),
# set HEALTH_PATH=/actuator/health/readiness and HEALTH_BEARER to a token. The token goes to the member
# on stdin of the probe and is never printed. HEALTH_PORT overrides the port. Only the status code is read.
health_code() {
  local svc="$1" port="${HEALTH_PORT:-8080}" path="${HEALTH_PATH:-/health}"
  printf '%s' "${HEALTH_BEARER:-}" | dc exec -T "$svc" bash -c '
    IFS= read -r bearer
    exec 3<>"/dev/tcp/127.0.0.1/$0" || exit 1
    auth=""; [ -n "$bearer" ] && auth="Authorization: Bearer $bearer\r\n"
    printf "GET %s HTTP/1.0\r\nHost: localhost\r\n%b\r\n" "$1" "$auth" >&3
    read -r _ code _ <&3
    printf "%s" "$code"' "$port" "$path" 2>/dev/null || printf 'none'
}

# wait_ready <service> <seconds>: 0 when the member answers 200 within the time; the wait is bounded.
wait_ready() {
  local svc="$1" limit="$2" waited=0
  while [ "$waited" -lt "$limit" ]; do
    [ "$(health_code "$svc")" = "200" ] && return 0
    sleep 5; waited=$((waited + 5))
  done
  return 1
}

# cpu_percent <service>: the container's CPU percentage at this moment (a number, no host detail).
cpu_percent() { docker stats --no-stream --format '{{.CPUPerc}}' "$(dc ps -q "$1")" 2>/dev/null || printf 'none'; }

# member_count: how many of the three members answer ready right now.
member_count() {
  local n=0 m
  for m in "${members[@]}"; do [ "$(health_code "$m")" = "200" ] && n=$((n + 1)); done
  printf '%s' "$n"
}

# require_keys: refuse to run without the two keys, without printing either.
require_keys() {
  if [ -z "${DATAPRISM_CLUSTER_KEY_MATERIAL:-}" ] || [ -z "${DATAPRISM_NAME_KEY_MATERIAL:-}" ]; then
    echo "export DATAPRISM_CLUSTER_KEY_MATERIAL and DATAPRISM_NAME_KEY_MATERIAL first" >&2
    exit 2
  fi
}

# new_key: a fresh 64-hex-character key on stdout; the caller must not print it.
new_key() { openssl rand -hex 32; }

# Fail-closed marker for the 221 criterion: count log lines of one member that say a class is not
# allowed to resolve. Prints a number only.
resolving_class_count() { dc logs --no-log-prefix "$1" 2>/dev/null | grep -c 'Resolving class .* is not allowed' || true; }
