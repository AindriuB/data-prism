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

# The members are always built from this checkout: compose.soak.yaml gives each a build: with pull_policy: build,
# so `up` never pulls the server image and never reuses a tag. There is no switch to turn that off.
compose_files=(-f "$repo_root/docker/multi-instance/compose.yaml" -f "$soak_dir/compose.soak.yaml")
dc() { docker compose "${compose_files[@]}" "$@"; }

members=(server-a server-b server-c)

# summary_file <name>: where a script's counts-and-findings summary goes.
summary_file() { printf '%s/%s-summary.txt' "$results_dir" "$1"; }

# note <summary-file> <text>: one finding line, to the terminal and the summary.
note() { printf '%s\n' "$2" | tee -a "$1"; }

# member_image_id <service>: the ID (12 hex characters, no registry or tag) of the image the member's container
# runs, or "none" when the member has no container.
member_image_id() {
  local cid id=""
  cid="$(dc ps -aq "$1" 2>/dev/null | head -n 1 || true)"
  [ -z "$cid" ] || id="$(docker inspect --format '{{.Image}}' "$cid" 2>/dev/null || true)"
  id="${id#sha256:}"
  printf '%s' "${id:0:12}"
  [ -n "$id" ] || printf 'none'
}

# git_commit: the short SHA of the checkout and whether its working tree is dirty, as "abc1234 dirty=no".
git_commit() {
  local sha dirty=no
  sha="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo none)"
  [ -z "$(git -C "$repo_root" status --porcelain 2>/dev/null)" ] || dirty=yes
  printf '%s dirty=%s' "$sha" "$dirty"
}

# provenance <summary-file>: which build ran. One line per member with its image ID, then the commit. Every
# script's summary starts with it.
provenance() {
  local m
  for m in "${members[@]}"; do note "$1" "image $m: $(member_image_id "$m")"; done
  note "$1" "commit: $(git_commit)"
}

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

# wait_ready <service> <seconds>: 0 when the member's /health answers 200 within the time (liveness only).
wait_ready() {
  local svc="$1" limit="$2" waited=0
  while [ "$waited" -lt "$limit" ]; do
    [ "$(health_code "$svc")" = "200" ] && return 0
    sleep 5; waited=$((waited + 5))
  done
  return 1
}

# cluster_size <service>: the member count in the latest "Members {size:N" line of the member's own log, or
# "none". A number only; the log's addresses are never read out.
cluster_size() {
  local n
  n="$(dc logs --no-log-prefix "$1" 2>/dev/null | grep -o 'Members {size:[0-9]*' | tail -1 | grep -o '[0-9]*$' || true)"
  printf '%s' "${n:-none}"
}

# wait_joined <service> <seconds>: 0 when the member answers /health 200 AND its log's latest member list holds
# all three members. A step that waits on this, not on wait_ready, does not start the next fault before the
# member has rejoined the cluster. A log line from before a restart can satisfy it only if the member rejoined
# since (the latest line is the one read).
wait_joined() {
  local svc="$1" limit="$2" waited=0
  while [ "$waited" -lt "$limit" ]; do
    [ "$(health_code "$svc")" = "200" ] && [ "$(cluster_size "$svc")" = "3" ] && return 0
    sleep 5; waited=$((waited + 5))
  done
  return 1
}

# cpu_percent <service>: the container's CPU percentage at this moment (a number, no host detail).
cpu_percent() { docker stats --no-stream --format '{{.CPUPerc}}' "$(dc ps -q "$1")" 2>/dev/null || printf 'none'; }

# member_count: how many of the three members answer /health 200 right now (liveness, not membership).
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

# Fail-closed marker for the 221 criterion: count log lines of one member that say a class is not
# allowed to resolve. Prints a number only.
resolving_class_count() { dc logs --no-log-prefix "$1" 2>/dev/null | grep -c 'Resolving class .* is not allowed' || true; }
