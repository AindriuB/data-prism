#!/usr/bin/env bash
# Capture a point-in-time snapshot of Data Prism's public GitHub
# discoverability signals into snapshots/YYYY-MM-DD.json (UTC date).
#
# GitHub's traffic endpoints (`views`, `clones`, `popular/referrers`,
# `popular/paths`) only cover a trailing 14-day window and are not
# retained beyond it, so this must run at least every 14 days or a
# period's traffic is lost permanently. See README.md in this directory.
#
# Read-only: every call below is a `gh api` GET. Nothing here writes to
# the API, and nothing here should ever be changed to do so.
#
# Requires: bash, gh (authenticated with repo read access), jq. Uses
# nothing else.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out_dir="$here/snapshots"
repo="${DATA_PRISM_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
date_utc="$(date -u +%F)"
out_file="$out_dir/${date_utc}.json"

mkdir -p "$out_dir"

tmp_file="$(mktemp "$out_dir/.snapshot.XXXXXX")"
trap 'rm -f "$tmp_file"' EXIT

repo_json="$(gh api "repos/${repo}" --jq '{stargazers_count,forks_count,subscribers_count,open_issues_count}')"
views_json="$(gh api "repos/${repo}/traffic/views")"
clones_json="$(gh api "repos/${repo}/traffic/clones")"
referrers_json="$(gh api "repos/${repo}/traffic/popular/referrers")"
paths_json="$(gh api "repos/${repo}/traffic/popular/paths")"
captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --argjson repo "$repo_json" \
  --argjson views "$views_json" \
  --argjson clones "$clones_json" \
  --argjson referrers "$referrers_json" \
  --argjson paths "$paths_json" \
  --arg captured_at "$captured_at" \
  '{
    repo: $repo,
    views: $views,
    clones: $clones,
    referrers: $referrers,
    paths: $paths,
    captured_at: $captured_at
  }' > "$tmp_file"

mv "$tmp_file" "$out_file"
trap - EXIT

echo "Wrote $out_file" >&2
