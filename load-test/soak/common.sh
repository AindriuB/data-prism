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

# Images (amendment 7): build-images.sh builds every data-prism image from one snapshot of the checkout and writes
# the stamp. Nothing here builds or pulls. compose.soak.yaml has no build: and pull_policy: never; `dc` takes the
# tag from the stamp (never from the caller's shell), refuses when the stamp is missing, a tag's image ID differs
# from it, a running container is not on the stamp's image, or the running-container lookup fails, and passes
# --no-build --pull never to `up`, so a recreate runs exactly the bring-up image.
stamp_file="$results_dir/soak-images.stamp"
compose_files=(-f "$repo_root/docker/multi-instance/compose.yaml" -f "$soak_dir/compose.soak.yaml")
soak_images=(server issuer fixtures certs-init)
project="${COMPOSE_PROJECT_NAME:-data-prism-multi-instance}"
label_key=io.github.aindriub.dataprism.soak

# stamp_get <key>: a value from the stamp, empty when absent.
stamp_get() { grep -m1 "^$1=" "$stamp_file" 2>/dev/null | cut -d= -f2- || true; }

# stamp_check: 0 when the stamp exists and every tag it names still resolves to the image ID it holds.
stamp_check() {
  local img tag id now
  [ -s "$stamp_file" ] || { echo "no image stamp: run load-test/soak/build-images.sh first" >&2; return 1; }
  tag="$(stamp_get tag)"; [ -n "$tag" ] || { echo "image stamp has no tag" >&2; return 1; }
  for img in "${soak_images[@]}"; do
    id="$(stamp_get "image.$img")"
    now="$(docker image inspect --format '{{.Id}}' "data-prism-soak-$img:$tag" 2>/dev/null)" || now=""
    [ -n "$id" ] && [ "$id" = "$now" ] || { echo "image data-prism-soak-$img:$tag is missing or differs from the stamp: run build-images.sh" >&2; return 1; }
  done
}

# running_match_check: 0 when every RUNNING data-prism container's image ID is the stamp's for its service (and when
# nothing runs). A rebuild with different content while the overlay runs makes it fail, so a recreate cannot swap a
# member's build inside a measured window; nothing is touched.
running_match_check() {
  local m img id cid run bad=0
  for m in "${containers[@]}"; do
    case "$m" in server-*) img=server ;; cert-init) img=certs-init ;; *) img="$m" ;; esac
    if ! cid="$(docker ps -q --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$m" 2>/dev/null)"; then
      echo "refused: cannot tell whether $m is running (docker ps failed); not assuming it is not" >&2
      bad=1
      continue
    fi
    cid="${cid%%$'\n'*}"
    [ -n "$cid" ] || continue
    run="$(docker inspect --format '{{.Image}}' "$cid" 2>/dev/null)" || run=""
    id="$(stamp_get "image.$img")"
    if [ -z "$run" ] || [ "$run" != "$id" ]; then
      echo "refused: running $m is not the stamp's image ($img); the stamp changed since bring-up. Rebuild the original content, or bring the overlay down first" >&2
      bad=1
    fi
  done
  return "$bad"
}

dc() {
  stamp_check || return 1
  local args=() a
  for a in "$@"; do
    args+=("$a")
    if [ "$a" = up ]; then
      running_match_check || return 1
      args+=(--no-build --pull never)
    fi
  done
  SOAK_IMAGE_TAG="$(stamp_get tag)" docker compose "${compose_files[@]}" "${args[@]}"
}

members=(server-a server-b server-c)
containers=(server-a server-b server-c cert-init issuer fixtures)

# summary_file <name>: where a script's counts-and-findings summary goes.
summary_file() { printf '%s/%s-summary.txt' "$results_dir" "$1"; }

# note <summary-file> <text>: one finding line, to the terminal and the summary.
note() { printf '%s\n' "$2" | tee -a "$1"; }

# container_identity <service>: what one compose container runs, as "<12 hex of the image ID> source=<hash12>
# commit=<sha> dirty=<yes|no>" from the image's own build labels. "absent" when the service has no container;
# "unknown" when docker fails or a label is unreadable. Looked up by compose project and service labels with
# docker ps: no compose interpolation, so it needs neither keys nor the stamp.
container_identity() {
  local cid line id
  cid="$(docker ps -aq --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$1" 2>/dev/null)" || { printf unknown; return 0; }
  [ -n "$cid" ] || { printf absent; return 0; }
  cid="${cid%%$'\n'*}"
  line="$(docker inspect --format "{{.Image}} {{index .Config.Labels \"$label_key.source-hash\"}} {{index .Config.Labels \"$label_key.commit\"}} {{index .Config.Labels \"$label_key.dirty\"}}" "$cid" 2>/dev/null)" || { printf unknown; return 0; }
  set -- $line
  id="${1#sha256:}"
  if [ -z "$id" ] || [ $# -ne 4 ]; then printf unknown; return 0; fi
  printf '%s source=%s commit=%s dirty=%s' "${id:0:12}" "${2:0:12}" "$3" "$4"
}

# running_image_id <service>: the full image ID of the container, "" when absent or unreadable.
running_image_id() {
  local cid
  cid="$(docker ps -aq --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$1" 2>/dev/null | head -n 1)" || return 0
  [ -z "$cid" ] || docker inspect --format '{{.Image}}' "$cid" 2>/dev/null || true
}

# provenance_lines: one line per container, in the form of container_identity.
provenance_lines() { local m; for m in "${containers[@]}"; do printf 'image %s: %s\n' "$m" "$(container_identity "$m")"; done; }

# provenance_begin <summary-file>: record what runs now, and arrange for provenance_end to run when the script
# exits. Every script calls it once, right after it opens its summary. A script's own cleanup goes through
# on_exit, so that it runs first and the end identity is read after it (a recreate has then happened).
soak_summary=""; soak_start_lines=""; soak_exit_cmds=()
on_exit() { soak_exit_cmds+=("$1"); }
soak_run_exit() {
  local c rc=$?
  trap - EXIT
  for c in "${soak_exit_cmds[@]+"${soak_exit_cmds[@]}"}"; do eval "$c" || true; done
  provenance_end
  exit "$rc"
}
provenance_begin() {
  soak_summary="$1"
  soak_start_lines="$(provenance_lines)"
  printf '%s\n' "$soak_start_lines" | sed 's/^image /start image /' | tee -a "$soak_summary"
  provenance_stamp_check start
  trap soak_run_exit EXIT
}
# provenance_stamp_check <when>: a finding for every container whose running image ID differs from the stamp.
provenance_stamp_check() {
  local m img id run
  for m in "${containers[@]}"; do
    case "$m" in server-*) img=server ;; cert-init) img=certs-init ;; *) img="$m" ;; esac
    run="$(running_image_id "$m")"
    [ -n "$run" ] || continue
    id="$(stamp_get "image.$img")"
    [ -n "$id" ] && [ "$run" = "$id" ] || note "$soak_summary" "FINDING ($1): $m runs an image that is not the stamp's ($img)"
  done
}
provenance_end() {
  [ -n "$soak_summary" ] || return 0
  local end; end="$(provenance_lines)"
  printf '%s\n' "$end" | sed 's/^image /end image /' | tee -a "$soak_summary"
  [ "$end" = "$soak_start_lines" ] || note "$soak_summary" "FINDING: the identities at the end differ from those at the start"
  provenance_stamp_check end
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
