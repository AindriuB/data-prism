#!/usr/bin/env bash
# Renders every docs-site/diagrams/*.mmd into a committed
# docs/assets/diagrams/<stem>.svg, using a pinned minlag/mermaid-cli image
# run offline (--network none) and as the invoking user (-u), so nothing it
# writes is root-owned. See docs-site/diagrams/README.md for the pin and the
# htmlLabels: false choice (docs-site/diagrams/mermaid-config.json).
#
# Usage: docs-site/diagrams/render.sh
#
# Generic on purpose: it renders every .mmd file it finds, so a diagram added
# later (task 90's extension-points diagram) needs no change here.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/docs/assets/diagrams"

# minlag/mermaid-cli, pinned by tag *and* digest.
IMAGE="minlag/mermaid-cli:10.9.1@sha256:f0e8d29ef5385d797724d78c2a1bb00c8398476e8370f0219c0da86cce07d44c"

mkdir -p "$OUT_DIR"

shopt -s nullglob
mmd_files=("$SCRIPT_DIR"/*.mmd)
shopt -u nullglob

if [ "${#mmd_files[@]}" -eq 0 ]; then
    echo "render.sh: no .mmd files found in $SCRIPT_DIR" >&2
    exit 1
fi

for mmd in "${mmd_files[@]}"; do
    name="$(basename "$mmd" .mmd)"
    echo "rendering $name.mmd -> docs/assets/diagrams/$name.svg"
    docker run --rm --network none -u "$(id -u):$(id -g)" \
        -v "$SCRIPT_DIR:/diagrams:ro" \
        -v "$OUT_DIR:/out" \
        "$IMAGE" \
        -i "/diagrams/$name.mmd" \
        -o "/out/$name.svg" \
        -c "/diagrams/mermaid-config.json"
done
