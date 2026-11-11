#!/usr/bin/env bash
# Builds the four data-prism images the three-member overlay runs (server, issuer, fixtures, certs-init) from ONE
# snapshot of this checkout, tags them by the snapshot's content hash, and writes the stamp the other scripts read
# (task 263, amendment 7). Run it before `up` and before any driver starts: nothing else in this directory builds.
#
#   - The snapshot is `git ls-files -co --exclude-standard` (tracked and untracked-not-ignored files; a tracked
#     file deleted from the tree is skipped), copied to a temporary directory. Ignored paths (target/, results/,
#     captures) never enter the build context.
#   - The hash is over every file's path and contents. The tag is data-prism-soak-<image>:<first 12 hex>.
#   - A tag that already exists is reused (same hash, same content); its source-hash label is checked.
#   - Commit and dirty (the snapshot differs from HEAD) go on every image as labels. A dirty snapshot is refused
#     unless SOAK_ALLOW_DIRTY=1; the labels then still say dirty=yes. A git failure refuses.
#   - The stamp (git-ignored results dir) holds the tag, the four image IDs, the hash, the commit and dirty.
# Prints counts and identities only, no path of the host.
set -euo pipefail
soak_dir="$(CDPATH= cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(CDPATH= cd "$soak_dir/../.." && pwd)"
results_dir="${SOAK_RESULTS_DIR:-$repo_root/load-test/results}"
mkdir -p "$results_dir"
stamp="$results_dir/soak-images.stamp"

git_ok() { git -C "$repo_root" "$@"; }
commit="$(git_ok rev-parse --short HEAD)" || { echo "refused: git could not name HEAD" >&2; exit 1; }
status="$(git_ok status --porcelain --untracked-files=all)" || { echo "refused: git status failed" >&2; exit 1; }
dirty=no; [ -z "$status" ] || dirty=yes
if [ "$dirty" = yes ] && [ "${SOAK_ALLOW_DIRTY:-}" != 1 ]; then
  echo "refused: the working tree differs from HEAD (commit first, or set SOAK_ALLOW_DIRTY=1; the images are then labelled dirty=yes)" >&2
  exit 1
fi
files="$(mktemp)"; snap="$(mktemp -d)"
trap 'rm -rf "$files" "$snap"' EXIT
git_ok ls-files -z -co --exclude-standard > "$files.all" || { echo "refused: git ls-files failed" >&2; exit 1; }
: > "$files"
while IFS= read -r -d '' f; do
  [ -e "$repo_root/$f" ] || [ -L "$repo_root/$f" ] && printf '%s\0' "$f" >> "$files"
done < "$files.all"
rm -f "$files.all"
tar -C "$repo_root" --null -T "$files" -cf - | tar -C "$snap" -xf -
hash="$(cd "$snap" && find . \( -type f -o -type l \) -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 | shasum -a 256 | cut -d' ' -f1)"
[ "${#hash}" -eq 64 ] || { echo "refused: could not hash the snapshot" >&2; exit 1; }
tag="${hash:0:12}"
echo "snapshot: source=$tag commit=$commit dirty=$dirty files=$(tr -cd '\0' < "$files" | wc -c | tr -d ' ')"

images=(server issuer fixtures certs-init)
label_key=io.github.aindriub.dataprism.soak
{
  echo "tag=$tag"; echo "hash=$hash"; echo "commit=$commit"; echo "dirty=$dirty"
} > "$stamp.new"
for img in "${images[@]}"; do
  ref="data-prism-soak-$img:$tag"
  have="$(docker image inspect --format "{{index .Config.Labels \"$label_key.source-hash\"}}" "$ref" 2>/dev/null || true)"
  if [ -n "$have" ]; then
    [ "$have" = "$hash" ] || { echo "refused: $ref exists with a different source hash" >&2; exit 1; }
    echo "reuse $ref"
  else
    echo "build $ref"
    docker build -q -f "$snap/docker/$img/Dockerfile" \
      --label "$label_key.source-hash=$hash" --label "$label_key.commit=$commit" --label "$label_key.dirty=$dirty" \
      -t "$ref" "$snap" >/dev/null
  fi
  id="$(docker image inspect --format '{{.Id}}' "$ref")" || { echo "refused: $ref cannot be read after the build" >&2; exit 1; }
  echo "image.$img=$id" >> "$stamp.new"
done
mv "$stamp.new" "$stamp"
echo "stamp written: tag=$tag images=${#images[@]}"
