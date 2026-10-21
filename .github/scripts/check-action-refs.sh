#!/usr/bin/env bash
# Check every `uses:` reference in the workflow files.
#
# A reference must name a full version tag (vX.Y.Z) or a 40-character commit
# SHA, never a floating major or minor tag, and it must resolve on GitHub. The
# 0.7.0 release tag failed on `sigstore/cosign-installer@v4`, which did not
# exist; nothing caught it before the real tag push.
#
# usage: check-action-refs.sh [--self-test] [workflow-dir]
#   workflow-dir  defaults to .github/workflows
#   --self-test   run against a fixture holding one good and one bad reference
#                 and expect the check to fail, naming only the bad one
# Exits non-zero, naming each reference that is malformed or does not resolve.
set -euo pipefail

# Limitation: only block-style `uses:` lines (`uses: x` or `- uses: x`, with an
# optional quote) are read. A flow mapping such as `- { uses: x@v1 }` is not
# seen; the repository does not use that form.
#
# Prints "file:line ref" for every `uses:` in the directory, local ones
# (./path) excluded.
list_refs() {
  grep -HnE "^[[:space:]-]*uses:[[:space:]]*[^[:space:]]+" "$1"/*.yml "$1"/*.yaml 2>/dev/null |
    sed -E "s/^([^:]+):([0-9]+):[[:space:]-]*uses:[[:space:]]*[\"']?([^[:space:]#\"']+).*/\1:\2 \3/" |
    grep -vE ' \./' || true
}

check_dir() {
  local dir="$1" bad=0 where ref repo name tag
  while read -r where ref; do
    [ -n "${ref:-}" ] || continue
    if [[ "$ref" == docker://* ]]; then
      # A docker:// action must be pinned to an immutable digest.
      [[ "$ref" =~ @sha256:[0-9a-f]{64}$ ]] || { echo "UNPINNED DOCKER   $where  $ref (pin to @sha256:<digest>)" >&2; bad=1; }
      continue
    fi
    repo="${ref%@*}"
    name="${ref##*@}"
    if [ "$repo" = "$ref" ]; then
      echo "NO REF   $where  $ref" >&2; bad=1; continue
    fi
    repo="$(echo "$repo" | cut -d/ -f1-2)"
    if [[ "$name" =~ ^[0-9a-f]{40}$ ]]; then
      # A SHA cannot be listed by name; it must appear among the remote refs.
      if ! git ls-remote "https://github.com/$repo.git" 2>/dev/null | grep -q "^$name"; then
        echo "NO SUCH SHA  $where  $ref" >&2; bad=1
      fi
    elif [[ "$name" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      tag="$(git ls-remote --tags "https://github.com/$repo.git" "refs/tags/$name" 2>/dev/null || true)"
      if [ -z "$tag" ]; then
        echo "UNRESOLVED   $where  $ref" >&2; bad=1
      fi
    else
      echo "FLOATING   $where  $ref (use vX.Y.Z or a 40-character SHA)" >&2; bad=1
    fi
  done < <(list_refs "$dir")
  return "$bad"
}

if [ "${1:-}" = "--self-test" ]; then
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  cat > "$fixture/fixture.yml" <<'YML'
jobs:
  j:
    steps:
      - uses: actions/checkout@v7.0.0
      - uses: actions/checkout@v999.0.0
      - uses: actions/setup-java@v6
      - uses: ./local-action
      - uses: docker://alpine:3.20
YML
  if out="$(check_dir "$fixture" 2>&1)"; then
    echo "self-test FAILED: bad references were accepted" >&2; exit 1
  fi
  for want in 'UNRESOLVED.*v999.0.0' 'FLOATING.*setup-java@v6' 'UNPINNED DOCKER.*alpine'; do
    echo "$out" | grep -qE "$want" || { echo "self-test FAILED: no report for $want" >&2; echo "$out" >&2; exit 1; }
  done
  if echo "$out" | grep -qE 'v7.0.0|local-action'; then
    echo "self-test FAILED: a good reference was reported" >&2; echo "$out" >&2; exit 1
  fi
  echo "self-test ok"
  exit 0
fi

dir="${1:-.github/workflows}"
check_dir "$dir" && echo "all action references resolve"
