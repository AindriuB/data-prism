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

# mermaid-cli's output carries a hard white background with no margin, so an
# embed on a dark page shows as an abrupt white strip. PADDING_PX is a small,
# uniform margin added to every SVG's viewBox after rendering, framed with a
# thin border, so each diagram reads as a deliberate panel instead. This is
# pure post-processing (arithmetic on the existing viewBox numbers plus one
# inserted <rect>) — mermaid-cli itself has no padding option.
PADDING_PX=16
BORDER_COLOUR="#8b949e"

mkdir -p "$OUT_DIR"

shopt -s nullglob
mmd_files=("$SCRIPT_DIR"/*.mmd)
shopt -u nullglob

if [ "${#mmd_files[@]}" -eq 0 ]; then
    echo "render.sh: no .mmd files found in $SCRIPT_DIR" >&2
    exit 1
fi

# add_num A B -> A+B, printed with just enough decimal places to match
# mermaid-cli's own viewBox precision (deterministic: no locale, no rounding
# beyond the fixed 6-decimal working precision).
add_num() {
    awk -v a="$1" -v b="$2" 'BEGIN {
        s = sprintf("%.6f", a + b)
        sub(/0+$/, "", s)
        sub(/\.$/, "", s)
        if (s == "" || s == "-") s = "0"
        print s
    }'
}

# frame_svg FILE -> pads FILE's viewBox by PADDING_PX on every side and draws
# a thin border rect inset from the new edge, so the diagram sits inside a
# framed panel rather than touching the image's own boundary.
frame_svg() {
    local svg="$1"
    local content
    content="$(cat "$svg")"

    local vb minx miny w h
    vb="$(printf '%s' "$content" | grep -oE 'viewBox="[-0-9.]+ [-0-9.]+ [-0-9.]+ [-0-9.]+"' | head -1)"
    vb="${vb#viewBox=\"}"
    vb="${vb%\"}"
    read -r minx miny w h <<<"$vb"

    local new_minx new_miny new_w new_h
    new_minx="$(add_num "$minx" "-$PADDING_PX")"
    new_miny="$(add_num "$miny" "-$PADDING_PX")"
    new_w="$(add_num "$w" "$((PADDING_PX * 2))")"
    new_h="$(add_num "$h" "$((PADDING_PX * 2))")"

    local border_x border_y border_w border_h
    border_x="$(add_num "$new_minx" "1")"
    border_y="$(add_num "$new_miny" "1")"
    border_w="$(add_num "$new_w" "-2")"
    border_h="$(add_num "$new_h" "-2")"

    local border_rect
    border_rect="<rect x=\"$border_x\" y=\"$border_y\" width=\"$border_w\" height=\"$border_h\" rx=\"6\" ry=\"6\" fill=\"none\" stroke=\"$BORDER_COLOUR\" stroke-width=\"1\"/>"

    content="$(printf '%s' "$content" | sed -E \
        -e "s/viewBox=\"[-0-9.]+ [-0-9.]+ [-0-9.]+ [-0-9.]+\"/viewBox=\"$new_minx $new_miny $new_w $new_h\"/" \
        -e "s/max-width: [0-9.]+px/max-width: ${new_w}px/")"
    content="${content/<style>/${border_rect}<style>}"

    printf '%s' "$content" > "$svg"
}

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
    frame_svg "$OUT_DIR/$name.svg"
done
