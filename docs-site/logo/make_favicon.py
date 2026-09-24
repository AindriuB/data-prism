#!/usr/bin/env python3
"""Generate docs/assets/favicon.svg and docs/assets/favicon.png.

Derives a simplified favicon from the owner-supplied prism mark
(`docs-site/logo/supplied/mark-dark.svg` — see `docs-site/logo/README.md`
for provenance). Simplified for legibility at 16x16, per
docs/plan/tasks/86-site-look.md:

- the incoming ray (`M4 32H16.6`) is dropped;
- the three thin (3-unit-thick) outgoing bars become two thicker
  (5-unit-thick) ones — same parallelogram construction, same slope, just
  fewer and bigger;
- the prism outline and side face keep their supplied `d` path data and
  group transform verbatim;
- strokes switch between the supplied light-surface colour (`#1E293B`,
  `mark-light.svg`) and dark-surface colour (`#E2E8F0`, `mark-dark.svg`)
  with an embedded `<style>` `@media (prefers-color-scheme: dark)` rule,
  since a favicon rendered by the browser chrome has no access to the
  page's own colour-scheme toggle.

`docs/assets/favicon.png` is a 32x32 PNG rendering of the SVG's default
(light-surface) appearance. Every shape here is a straight-edged polygon —
no curves — so it's rendered directly from the same point data as the SVG,
with Pillow's own polygon/line drawing, rather than through a separate SVG
rasteriser dependency. Anti-aliased by supersampling 16x, then downsampled
with Pillow's Lanczos filter.

Regenerate with:
    python3 -m venv .venv
    .venv/bin/pip install -r docs-site/logo/requirements.txt
    .venv/bin/python3 docs-site/logo/make_favicon.py

Run from the repository root. Running it twice leaves `git status
--porcelain docs/assets/` empty: both outputs are generated deterministically
from the constants below, and Pillow is pinned in requirements.txt.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
SVG_OUT = REPO_ROOT / "docs" / "assets" / "favicon.svg"
PNG_OUT = REPO_ROOT / "docs" / "assets" / "favicon.png"
PNG_SIZE = 32

# --- Verbatim from docs-site/logo/supplied/mark-dark.svg. Do not alter. ---
GROUP_TRANSFORM = "translate(2.56 2.56) scale(0.92)"
SIDE_FACE_D = "M24 14 34 8 48 42 38 48Z"
SIDE_FACE_POINTS = [(24, 14), (34, 8), (48, 42), (38, 48)]
OUTLINE_D = "M10 48 24 14 34 8 48 42 38 48Z M24 14 38 48"
OUTLINE_POLYGON_POINTS = [(10, 48), (24, 14), (34, 8), (48, 42), (38, 48)]
OUTLINE_LINE_POINTS = [(24, 14), (38, 48)]
OUTLINE_STROKE_WIDTH = 3

STROKE_LIGHT = "#1E293B"  # default (light-surface) — supplied mark-light.svg
STROKE_DARK = "#E2E8F0"  # prefers-color-scheme: dark — supplied mark-dark.svg
INDIGO = "#6366F1"

# Two replacement bars, each 5 units thick (supplied bars are 3), following
# the same slope and general placement as the supplied fan of three. Built
# the same way as the supplied bars: a horizontal top edge, a diagonal step
# down-and-right, a horizontal bottom edge, then close.
BAR_1_D = "M44 20.5H54L56 25.5H46Z"
BAR_1_POINTS = [(44, 20.5), (54, 20.5), (56, 25.5), (46, 25.5)]
BAR_2_D = "M48 32.5H58L60 37.5H50Z"
BAR_2_POINTS = [(48, 32.5), (58, 32.5), (60, 37.5), (50, 37.5)]

SVG_TEXT = f"""<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-labelledby="title">
  <title id="title">Data Prism</title>
  <style>
    .dp-fill {{ fill: {STROKE_LIGHT}; }}
    .dp-stroke {{ stroke: {STROKE_LIGHT}; }}
    @media (prefers-color-scheme: dark) {{
      .dp-fill {{ fill: {STROKE_DARK}; }}
      .dp-stroke {{ stroke: {STROKE_DARK}; }}
    }}
  </style>
  <g stroke-linejoin="miter" transform="{GROUP_TRANSFORM}">
    <path class="dp-fill" d="{SIDE_FACE_D}" fill-opacity="0.16"/>
    <path class="dp-stroke" d="{OUTLINE_D}" fill="none" stroke-width="{OUTLINE_STROKE_WIDTH}"/>
    <path d="{BAR_1_D}" fill="{INDIGO}"/>
    <path d="{BAR_2_D}" fill="{INDIGO}"/>
  </g>
</svg>
"""


def _hex_to_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


# Rendering constants for the PNG. The group transform (`translate(2.56
# 2.56) scale(0.92)`) is applied to every point before supersampling: SVG
# applies the rightmost transform first, so a point is scaled by 0.92 and
# then offset by (2.56, 2.56) — exactly `render.py`'s `_place` below.
SUPERSAMPLE = 16
CANVAS = 64 * SUPERSAMPLE


def _place(x: float, y: float) -> tuple[float, float]:
    """Map a local (pre-transform) mark coordinate to supersampled canvas
    pixels, applying the mark's own group transform then the supersample
    factor."""
    tx = 0.92 * x + 2.56
    ty = 0.92 * y + 2.56
    return (tx * SUPERSAMPLE, ty * SUPERSAMPLE)


def _placed(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    return [_place(x, y) for x, y in points]


def render_png() -> Image.Image:
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    stroke_light_rgb = _hex_to_rgb(STROKE_LIGHT)
    indigo_rgb = _hex_to_rgb(INDIGO)

    # Side face, fill-opacity 0.16.
    side_face_alpha = round(0.16 * 255)
    draw.polygon(_placed(SIDE_FACE_POINTS), fill=(*stroke_light_rgb, side_face_alpha))

    # Prism outline: a closed polygon plus one open diagonal line, both
    # stroked, no fill — reproduced as connected stroked line segments.
    stroke_px = round(OUTLINE_STROKE_WIDTH * 0.92 * SUPERSAMPLE)
    outline_polygon = _placed(OUTLINE_POLYGON_POINTS)
    draw.line(outline_polygon + [outline_polygon[0]], fill=(*stroke_light_rgb, 255), width=stroke_px, joint="curve")
    outline_line = _placed(OUTLINE_LINE_POINTS)
    draw.line(outline_line, fill=(*stroke_light_rgb, 255), width=stroke_px, joint="curve")

    # The two indigo bars, fully opaque.
    draw.polygon(_placed(BAR_1_POINTS), fill=(*indigo_rgb, 255))
    draw.polygon(_placed(BAR_2_POINTS), fill=(*indigo_rgb, 255))

    return canvas.resize((PNG_SIZE, PNG_SIZE), resample=Image.LANCZOS)


def main() -> None:
    SVG_OUT.parent.mkdir(parents=True, exist_ok=True)
    SVG_OUT.write_text(SVG_TEXT, encoding="utf-8")
    print(f"wrote {SVG_OUT} (64x64 viewBox)")

    image = render_png()
    PNG_OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(PNG_OUT, format="PNG")
    print(f"wrote {PNG_OUT} ({image.size[0]}x{image.size[1]})")


if __name__ == "__main__":
    main()
