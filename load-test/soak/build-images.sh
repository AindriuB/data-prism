#!/usr/bin/env bash
# Builds the four data-prism images the three-member overlay runs (server, issuer, fixtures, certs-init) from ONE
# snapshot of this checkout, tags them by the snapshot's content hash, and writes the stamp the other scripts read
# (task 263, amendment 7). Run it before `up` and before any driver starts: nothing else in this directory builds.
#
#   - The snapshot is `git ls-files -co --exclude-standard` (tracked and untracked-not-ignored files; a tracked
#     file deleted from the tree is skipped), copied to a temporary directory. Ignored paths (target/, results/,
#     captures) never enter the build context.
#   - The hash is over every file's path, contents and file mode. The tag is data-prism-soak-<image>:<first 12 hex>.
#   - A tag that already exists is reused (same hash, same content); its source-hash label is checked, and its
#     commit and dirty labels are rewritten (a label-only layer, no content change) when they are not this run's.
#   - Commit and dirty go on every image as labels. Dirty is derived from the COPIED snapshot compared with HEAD
#     (not from `git status` beforehand), so an edit made after a status read, or a --skip-worktree file, counts.
#     A dirty snapshot is refused
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
full_sha="$(git_ok rev-parse HEAD)" || { echo "refused: git could not name HEAD" >&2; exit 1; }
commit="$(git_ok rev-parse --short "$full_sha")" || { echo "refused: git could not name HEAD" >&2; exit 1; }
files="$(mktemp)"; snap="$(mktemp -d)"
trap 'rm -rf "$files" "$snap"' EXIT
git_ok ls-files -z -co --exclude-standard > "$files.all" || { echo "refused: git ls-files failed" >&2; exit 1; }
: > "$files"
while IFS= read -r -d '' f; do
  [ -e "$repo_root/$f" ] || [ -L "$repo_root/$f" ] && printf '%s\0' "$f" >> "$files"
done < "$files.all"
rm -f "$files.all"
tar -C "$repo_root" --null -T "$files" -cf - | tar -C "$snap" -xf -
if stat -c %a / >/dev/null 2>&1; then modefmt=(-c '%a %n'); else modefmt=(-f '%Lp %N'); fi
hash="$(cd "$snap" && {
  find . \( -type f -o -type l \) -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 || exit 1
  find . \( -type f -o -type l \) -print0 | LC_ALL=C sort -z | xargs -0 stat "${modefmt[@]}" || exit 1
} | shasum -a 256 | cut -d' ' -f1)" || { echo "refused: could not hash the snapshot" >&2; exit 1; }
[ "${#hash}" -eq 64 ] || { echo "refused: could not hash the snapshot" >&2; exit 1; }
tag="${hash:0:12}"
# Dirty: the snapshot as copied, staged into a throwaway index (objects go to a throwaway directory that borrows the
# repository's), compared with the tree of the HEAD read before the copy
# (a commit landing mid-build cannot hide an edit). 0 = same, 1 = differs, anything else = refuse.
git_dir="$(git_ok rev-parse --absolute-git-dir)" && common_dir="$(cd "$repo_root" && cd "$(git rev-parse --git-common-dir)" && pwd)" \
  || { echo "refused: git could not locate the repository" >&2; exit 1; }
scratch="$(mktemp -d)"
trap 'rm -rf "$files" "$snap" "$scratch"' EXIT
snap_git() {
  GIT_DIR="$git_dir" GIT_WORK_TREE="$snap" GIT_INDEX_FILE="$scratch/index" GIT_OBJECT_DIRECTORY="$scratch/objects" \
    GIT_ALTERNATE_OBJECT_DIRECTORIES="$common_dir/objects" git "$@"
}
mkdir -p "$scratch/objects"
snap_git -c core.fileMode=true -c core.autocrlf=false add -A -f . >/dev/null 2>&1 || { echo "refused: could not stage the snapshot to compare it with HEAD" >&2; exit 1; }
rc=0; snap_git diff-index --cached --quiet "$full_sha" -- || rc=$?
case "$rc" in 0) dirty=no ;; 1) dirty=yes ;; *) echo "refused: could not compare the snapshot with HEAD" >&2; exit 1 ;; esac
if [ "$dirty" = yes ] && [ "${SOAK_ALLOW_DIRTY:-}" != 1 ]; then
  echo "refused: the snapshot differs from HEAD (commit first, or set SOAK_ALLOW_DIRTY=1; the images are then labelled dirty=yes)" >&2
  exit 1
fi
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
    have_commit="$(docker image inspect --format "{{index .Config.Labels \"$label_key.commit\"}}" "$ref" 2>/dev/null || true)"
    have_dirty="$(docker image inspect --format "{{index .Config.Labels \"$label_key.dirty\"}}" "$ref" 2>/dev/null || true)"
    if [ "$have_commit" = "$commit" ] && [ "$have_dirty" = "$dirty" ]; then
      echo "reuse $ref"
    else
      echo "reuse $ref (labels rewritten: commit and dirty are this snapshot's)"
      printf 'FROM %s\n' "$ref" | docker build -q -f - \
        --label "$label_key.source-hash=$hash" --label "$label_key.commit=$commit" --label "$label_key.dirty=$dirty" \
        -t "$ref" "$scratch" >/dev/null || { echo "refused: could not relabel $ref" >&2; exit 1; }
    fi
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
