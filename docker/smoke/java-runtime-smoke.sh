#!/usr/bin/env bash
# Java runtime smoke test (task 143). Needs only Docker with Compose v2.
# Run from anywhere: bash docker/smoke/java-runtime-smoke.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${ROOT}/docker/smoke/target"
WARNINGS="${OUT_DIR}/unsafe-warnings.txt"
TIMEOUT=180
SMOKE_PREFIX="data-prism-smoke/"
export SMOKE_RUN_ID="$$-$(date +%s)"
DIST_IMAGE="${SMOKE_PREFIX}distribution:${SMOKE_RUN_ID}"
SERVER_IMAGE="${SMOKE_PREFIX}server:${SMOKE_RUN_ID}"
# Unique project name: -p overrides the fixed name in compose.yaml, so teardown
# can only ever touch the stack this run created.
PROJECT="data-prism-smoke-$$"
COMPOSE=(docker compose -p "$PROJECT" -f "${ROOT}/docker/multi-instance/compose.yaml"
         -f "${ROOT}/docker/multi-instance/compose.build.yaml"
         -f "${ROOT}/docker/smoke/compose.smoke.yaml")

cleanup() {
  status=$?
  echo "== tearing down"
  # Only reached once the guard below passed, so every image here is smoke-named.
  "${COMPOSE[@]}" down --volumes --remove-orphans --rmi local >/dev/null 2>&1 || true
  docker image rm -f "$DIST_IMAGE" "$SERVER_IMAGE" >/dev/null 2>&1 || true
  exit "$status"
}

# Fail closed: refuse to build or tear down unless every image name the stack
# resolves to carries the smoke prefix. Teardown is armed only after this passes,
# so an unexpected name can never be removed.
echo "== checking image names"
images="$("${COMPOSE[@]}" config --images)"
if [ -z "$images" ]; then
  echo "FAIL: compose resolved no image names" >&2; exit 1
fi
while IFS= read -r name; do
  case "$name" in
    "${SMOKE_PREFIX}"*) ;;
    *) echo "FAIL: image '${name}' is not under ${SMOKE_PREFIX}; refusing to build" >&2; exit 1 ;;
  esac
done <<< "$images"
trap cleanup EXIT

mkdir -p "$OUT_DIR"

echo "== building distribution and server images"
docker build -q -f "${ROOT}/docker/distribution/Dockerfile" -t "$DIST_IMAGE" "$ROOT" >/dev/null
docker build -q -f "${ROOT}/docker/server/Dockerfile" -t "$SERVER_IMAGE" "$ROOT" >/dev/null

echo "== java -version"
for image in "$DIST_IMAGE" "$SERVER_IMAGE"; do
  version="$(docker run --rm --entrypoint java "$image" -version 2>&1 | head -n1)"
  echo "${image}: ${version}"
  case "$version" in
    *'"25.'*) ;;
    *) echo "FAIL: ${image} does not report Java 25" >&2; exit 1 ;;
  esac
done

echo "== distribution image with no configuration must refuse"
set +e
output="$(docker run --rm "$DIST_IMAGE" 2>&1)"
status=$?
set -e
if [ "$status" -eq 0 ]; then
  echo "FAIL: distribution image started with no configuration" >&2; exit 1
fi
code="$(printf '%s\n' "$output" \
  | grep -oE 'DataPrismConfigurationException: MISSING_[A-Z_]+' | head -n1 || true)"
if [ -z "$code" ]; then
  echo "FAIL: exit ${status} but no named dataprism refusal" >&2; exit 1
fi
echo "exit status ${status}, refusal: ${code}"

echo "== bringing up docker/multi-instance from source"
"${COMPOSE[@]}" up --build -d

echo "== waiting up to ${TIMEOUT}s for both members to see Members {size:2"
deadline=$((SECONDS + TIMEOUT))
while :; do
  a="$("${COMPOSE[@]}" logs --no-color server-a 2>&1 | grep -c 'Members {size:2' || true)"
  b="$("${COMPOSE[@]}" logs --no-color server-b 2>&1 | grep -c 'Members {size:2' || true)"
  if [ "$a" -gt 0 ] && [ "$b" -gt 0 ]; then break; fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "FAIL: cluster of 2 not seen within ${TIMEOUT}s (server-a=${a}, server-b=${b})" >&2
    exit 1
  fi
  sleep 3
done

: > "$WARNINGS"
for svc in server-a server-b; do
  "${COMPOSE[@]}" logs --no-color "$svc" 2>&1 | grep 'sun\.misc\.Unsafe' >> "$WARNINGS" || true
done
count="$(wc -l < "$WARNINGS" | tr -d ' ')"

echo "== summary"
echo "java: $(docker run --rm --entrypoint java "$SERVER_IMAGE" -version 2>&1 | head -n1)"
echo "project: ${PROJECT}"
echo "server-a: ${a} log lines with Members {size:2"
echo "server-b: ${b} log lines with Members {size:2"
echo "sun.misc.Unsafe warning lines: ${count} (recorded in ${WARNINGS})"
if [ "$count" -eq 0 ]; then
  echo "A count of 0 is allowed: the warning is recorded when present, never suppressed."
else
  cat "$WARNINGS"
fi
echo "PASS"
