#!/usr/bin/env python3
"""WCAG contrast checks for the Data Prism docs site palette.

Run from the repo root, any time (it only reads source files, not a built
`site/`):

    python3 docs-site/hooks/check_contrast.py

Reads the palette's actual colour values from `docs/stylesheets/extra.css`
(the `[data-md-color-scheme="default"]` / `[data-md-color-scheme="slate"]`
blocks — see that file's own comments for why those are the only place
Material's `primary: custom` / `accent: custom` colours come from), falling
back to Material's own compiled defaults, hardcoded below with a comment
citing the file and variable, where `extra.css` does not override a value.

Checks, at WCAG AA (4.5:1) for text, in both schemes:
- body text on background
- links on background
- header text on the header (primary) colour

Checks the supplied logo mark (`docs/assets/logo.svg`) against the header
colour at WCAG 1.4.11's 3:1 non-text minimum: the stroke colour, and the
fully opaque indigo bar. The 85%/70%-opacity bars are printed for
information only — their geometry is the owner's, not this script's, to
change.

Checks the hero's two buttons (`.md-button` / `.md-button--primary`) in
both schemes, against Material's own compiled defaults for those classes,
overridden per scheme where `extra.css` says so (its
`[data-md-color-scheme="..."] .md-button(--primary)?` rules — currently
slate only):
- plain-button text/border against the page background, at 4.5:1;
- primary-button text against its own fill, at 4.5:1;
- primary-button fill against the page background, at 3:1 (fill and border
  share one colour in this stylesheet, so checking fill covers both).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
EXTRA_CSS = REPO_ROOT / "docs" / "stylesheets" / "extra.css"
LOGO_SVG = REPO_ROOT / "docs" / "assets" / "logo.svg"

AA_TEXT = 4.5
AA_NON_TEXT = 3.0

SCHEMES = ("default", "slate")


class CheckError(Exception):
    pass


def fail(message: str) -> None:
    raise CheckError(message)


# --------------------------------------------------------------------------
# Colour parsing: hex, rgb(a)(), hsl(a)() — everything docs/stylesheets/
# extra.css and Material's own compiled CSS use for these variables.
# --------------------------------------------------------------------------

# mkdocs-material==9.7.7 (pinned in docs-site/requirements.txt) sets this in
# `material/templates/assets/stylesheets/main.ec1eaa64.min.css`:
#   `:root,[data-md-color-scheme=default]{--md-hue:225deg;...}`
# — inherited by the slate scheme too, whose own hsla() values (below)
# reference `var(--md-hue)` rather than a literal number.
MATERIAL_HUE_DEG = 225.0

# Material's own compiled defaults for the variables this script needs,
# where docs/stylesheets/extra.css does not override them. Copied from the
# package's compiled CSS for the pinned mkdocs-material==9.7.7, not
# reproduced from memory:
#   main.ec1eaa64.min.css, `:root,[data-md-color-scheme=default]`:
#     --md-default-fg-color:#000000de; --md-default-bg-color:#fff;
#     --md-primary-fg-color:#4051b5; --md-primary-bg-color:#fff
#   palette.ab4e12ef.min.css, `[data-md-color-scheme=slate]`:
#     --md-default-fg-color:hsla(var(--md-hue),15%,90%,0.82);
#     --md-default-bg-color:hsla(var(--md-hue),15%,14%,1)
#   palette.ab4e12ef.min.css also has no slate default for --md-primary-fg-
#   color/--md-primary-bg-color (only per named `data-md-color-primary`
#   value), so main.ec1eaa64.min.css's `:root` default applies unchanged.
#   `--md-typeset-a-color:var(--md-primary-fg-color)` in both schemes
#   (palette.ab4e12ef.min.css), Material's un-overridden default link colour.
MATERIAL_DEFAULTS: dict[str, dict[str, str]] = {
    "default": {
        "--md-default-fg-color": "#000000de",
        "--md-default-bg-color": "#ffffff",
        "--md-primary-fg-color": "#4051b5",
        "--md-primary-bg-color": "#ffffff",
        "--md-typeset-a-color": "var(--md-primary-fg-color)",
    },
    "slate": {
        "--md-default-fg-color": "hsla(225, 15%, 90%, 0.82)",
        "--md-default-bg-color": "hsla(225, 15%, 14%, 1)",
        "--md-primary-fg-color": "#4051b5",
        "--md-primary-bg-color": "#ffffff",
        "--md-typeset-a-color": "var(--md-primary-fg-color)",
    },
}

# Material's own compiled defaults for the two button classes the hero uses,
# for the pinned mkdocs-material==9.7.7, copied from its compiled CSS, not
# reproduced from memory:
#   main.ec1eaa64.min.css:
#     .md-button{border:.1rem solid;...;color:var(--md-primary-fg-color);...}
#       (no explicit border-color, so the border takes the CSS default of
#       `currentColor` — i.e. the same `color` value above)
#     .md-button--primary{background-color:var(--md-primary-fg-color);
#       border-color:var(--md-primary-fg-color);color:var(--md-primary-bg-color)}
#   palette.ab4e12ef.min.css also has `.md-button`/`.md-button--primary`
#   rules, but only scoped to `[data-md-color-primary=white]` and
#   `[data-md-color-primary=black]`; this site's `primary: custom` sets
#   `data-md-color-primary="custom"`, which neither selector matches, so
#   those rules never apply here and are not modelled below.
MATERIAL_BUTTON_DEFAULTS: dict[str, dict[str, str]] = {
    "plain": {
        "color": "var(--md-primary-fg-color)",
        "border-color": "var(--md-primary-fg-color)",
    },
    "primary": {
        "background-color": "var(--md-primary-fg-color)",
        "border-color": "var(--md-primary-fg-color)",
        "color": "var(--md-primary-bg-color)",
    },
}

RGBA = tuple[float, float, float, float]

_RGBA_RE = re.compile(r"rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)")
_HSLA_RE = re.compile(r"hsla?\(\s*([^,]+?)\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%\s*(?:,\s*([\d.]+)\s*)?\)")


def _hsl_to_rgb(h: float, s: float, l: float) -> tuple[float, float, float]:
    c = (1 - abs(2 * l - 1)) * s
    x = c * (1 - abs((h / 60) % 2 - 1))
    m = l - c / 2
    if 0 <= h < 60:
        r, g, b = c, x, 0.0
    elif 60 <= h < 120:
        r, g, b = x, c, 0.0
    elif 120 <= h < 180:
        r, g, b = 0.0, c, x
    elif 180 <= h < 240:
        r, g, b = 0.0, x, c
    elif 240 <= h < 300:
        r, g, b = x, 0.0, c
    else:
        r, g, b = c, 0.0, x
    return tuple((v + m) * 255 for v in (r, g, b))  # type: ignore[return-value]


def parse_css_color(value: str) -> RGBA:
    value = value.strip()
    match = _RGBA_RE.match(value)
    if match:
        r, g, b = (float(match.group(i)) for i in (1, 2, 3))
        a = float(match.group(4)) if match.group(4) is not None else 1.0
        return (r, g, b, a)
    match = _HSLA_RE.match(value)
    if match:
        hue_raw = match.group(1).strip()
        hue = MATERIAL_HUE_DEG if "var(--md-hue)" in hue_raw else float(hue_raw.rstrip("deg"))
        s = float(match.group(2)) / 100
        l = float(match.group(3)) / 100
        a = float(match.group(4)) if match.group(4) is not None else 1.0
        r, g, b = _hsl_to_rgb(hue, s, l)
        return (r, g, b, a)
    if value.startswith("#"):
        hex_value = value.lstrip("#")
        if len(hex_value) == 3:
            hex_value = "".join(c * 2 for c in hex_value)
        r = int(hex_value[0:2], 16)
        g = int(hex_value[2:4], 16)
        b = int(hex_value[4:6], 16)
        a = int(hex_value[6:8], 16) / 255 if len(hex_value) >= 8 else 1.0
        return (float(r), float(g), float(b), a)
    fail(f"cannot parse colour: {value!r}")
    raise AssertionError("unreachable")  # keeps type-checkers happy


def blend_over(fg: RGBA, bg: RGBA) -> RGBA:
    """Alpha-composite `fg` over the (assumed opaque) `bg`."""
    fr, fgg, fb, fa = fg
    br, bgc, bb, _ba = bg
    return (
        fr * fa + br * (1 - fa),
        fgg * fa + bgc * (1 - fa),
        fb * fa + bb * (1 - fa),
        1.0,
    )


def _linearize(channel: float) -> float:
    c = channel / 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def relative_luminance(rgba: RGBA) -> float:
    r, g, b, _ = rgba
    return 0.2126 * _linearize(r) + 0.7152 * _linearize(g) + 0.0722 * _linearize(b)


def contrast_ratio(c1: RGBA, c2: RGBA) -> float:
    l1, l2 = relative_luminance(c1), relative_luminance(c2)
    l1, l2 = max(l1, l2), min(l1, l2)
    return (l1 + 0.05) / (l2 + 0.05)


# --------------------------------------------------------------------------
# docs/stylesheets/extra.css parsing.
# --------------------------------------------------------------------------

_SCHEME_BLOCK_RE = re.compile(r'\[data-md-color-scheme=(["\'])(default|slate)\1\]\s*\{([^}]*)\}')
_DECL_RE = re.compile(r"(--[\w-]+)\s*:\s*([^;]+);")
_VAR_REF_RE = re.compile(r"^var\(\s*(--[\w-]+)\s*\)$")

# extra.css's per-scheme button overrides are ordinary selectors, not a bare
# `[data-md-color-scheme="..."] { ... }` block, so they need their own
# pattern: `[data-md-color-scheme="slate"] .md-button { ... }` or the same
# with `.md-button--primary`.
_BUTTON_BLOCK_RE = re.compile(
    r'\[data-md-color-scheme=(["\'])(default|slate)\1\]\s+\.md-button(--primary)?\s*\{([^}]*)\}'
)
_PROP_DECL_RE = re.compile(r"([a-zA-Z-]+)\s*:\s*([^;]+);")


_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)


def parse_extra_css(css_text: str) -> dict[str, dict[str, str]]:
    # Comments here quote example CSS declarations in prose (to explain
    # *why* a value was chosen) — stripped first so a quoted example never
    # gets picked up as a real declaration, or swallows a real one inside
    # its own greedy `[^;]+` match.
    css_text = _COMMENT_RE.sub("", css_text)
    schemes: dict[str, dict[str, str]] = {"default": {}, "slate": {}}
    for match in _SCHEME_BLOCK_RE.finditer(css_text):
        scheme = match.group(2)
        for decl in _DECL_RE.finditer(match.group(3)):
            schemes[scheme][decl.group(1)] = decl.group(2).strip()
    return schemes


def resolve(decls: dict[str, str], name: str, _seen: frozenset[str] = frozenset()) -> str:
    if name not in decls:
        fail(f"colour variable {name} is not defined")
    if name in _seen:
        fail(f"circular var() reference resolving {name}")
    raw = decls[name]
    match = _VAR_REF_RE.match(raw)
    if match:
        return resolve(decls, match.group(1), _seen | {name})
    return raw


def effective_color(decls: dict[str, str], name: str) -> RGBA:
    return parse_css_color(resolve(decls, name))


def parse_button_overrides(css_text: str) -> dict[str, dict[str, dict[str, str]]]:
    """This project's own per-scheme button overrides, keyed
    `[scheme]["plain" | "primary"][css-property]`. Empty for a scheme/class
    extra.css does not override — callers fall back to
    `MATERIAL_BUTTON_DEFAULTS`.
    """
    css_text = _COMMENT_RE.sub("", css_text)
    overrides: dict[str, dict[str, dict[str, str]]] = {"default": {}, "slate": {}}
    for match in _BUTTON_BLOCK_RE.finditer(css_text):
        scheme = match.group(2)
        button = "primary" if match.group(3) else "plain"
        decls = {name.strip(): value.strip() for name, value in _PROP_DECL_RE.findall(match.group(4))}
        overrides[scheme][button] = decls
    return overrides


def resolve_value(value: str, decls: dict[str, str]) -> str:
    """Resolve a raw CSS property value (not necessarily itself a `--var`
    name) one `var(--...)` hop, via `resolve`, or return it unchanged if it
    is already a literal colour."""
    match = _VAR_REF_RE.match(value.strip())
    if match:
        return resolve(decls, match.group(1))
    return value.strip()


def effective_button_color(
    button_decls: dict[str, str], decls: dict[str, str], prop: str
) -> RGBA:
    if prop not in button_decls:
        fail(f"button declaration {prop} is not defined")
    return parse_css_color(resolve_value(button_decls[prop], decls))


# --------------------------------------------------------------------------
# Logo colour extraction.
# --------------------------------------------------------------------------

_PATH_TAG_RE = re.compile(r"<path\b([^>]*?)/?>")
_ATTR_RE = re.compile(r'([\w-]+)\s*=\s*"([^"]*)"')


def _path_attrs(svg_text: str) -> list[dict[str, str]]:
    paths = []
    for tag_match in _PATH_TAG_RE.finditer(svg_text):
        attrs = dict(_ATTR_RE.findall(tag_match.group(1)))
        paths.append(attrs)
    return paths


def logo_colors() -> dict[str, RGBA]:
    svg_text = LOGO_SVG.read_text(encoding="utf-8")
    paths = _path_attrs(svg_text)

    stroke_hex = None
    bars: list[tuple[str, float]] = []  # (fill hex, opacity)
    for attrs in paths:
        stroke = attrs.get("stroke")
        if stroke and stroke.lower() != "none" and stroke_hex is None:
            stroke_hex = stroke
        fill = attrs.get("fill")
        if fill and fill.upper() == "#6366F1":
            opacity = float(attrs.get("opacity", "1"))
            bars.append((fill, opacity))

    if stroke_hex is None:
        fail(f"{LOGO_SVG}: no stroked path found (expected the prism outline)")
    if len(bars) != 3:
        fail(f"{LOGO_SVG}: expected 3 indigo (#6366F1) bar paths, found {len(bars)}")

    # Highest opacity first: the supplied mark orders them 100%, 85%, 70%.
    bars.sort(key=lambda item: item[1], reverse=True)

    return {
        "stroke": parse_css_color(stroke_hex),
        "bar_100": parse_css_color(bars[0][0])[:3] + (bars[0][1],),  # type: ignore[index]
        "bar_85": parse_css_color(bars[1][0])[:3] + (bars[1][1],),  # type: ignore[index]
        "bar_70": parse_css_color(bars[2][0])[:3] + (bars[2][1],),  # type: ignore[index]
    }


# --------------------------------------------------------------------------
# The checks themselves.
# --------------------------------------------------------------------------


def check_text_contrast(scheme_vars: dict[str, dict[str, str]]) -> list[str]:
    lines = []
    for scheme in SCHEMES:
        decls = {**MATERIAL_DEFAULTS[scheme], **scheme_vars[scheme]}

        background = effective_color(decls, "--md-default-bg-color")

        body_fg = effective_color(decls, "--md-default-fg-color")
        body_color = blend_over(body_fg, background) if body_fg[3] < 1.0 else body_fg
        body_ratio = contrast_ratio(body_color, background)
        lines.append(f"body text on background ({scheme}): {body_ratio:.2f}:1")
        if body_ratio < AA_TEXT:
            fail(f"body text on background ({scheme}): {body_ratio:.2f}:1, need >= {AA_TEXT}:1")

        link_fg = effective_color(decls, "--md-typeset-a-color")
        link_color = blend_over(link_fg, background) if link_fg[3] < 1.0 else link_fg
        link_ratio = contrast_ratio(link_color, background)
        lines.append(f"links on background ({scheme}): {link_ratio:.2f}:1")
        if link_ratio < AA_TEXT:
            fail(f"links on background ({scheme}): {link_ratio:.2f}:1, need >= {AA_TEXT}:1")

        header_bg = effective_color(decls, "--md-primary-fg-color")
        header_fg = effective_color(decls, "--md-primary-bg-color")
        header_color = blend_over(header_fg, header_bg) if header_fg[3] < 1.0 else header_fg
        header_ratio = contrast_ratio(header_color, header_bg)
        lines.append(f"header text on primary ({scheme}): {header_ratio:.2f}:1")
        if header_ratio < AA_TEXT:
            fail(f"header text on primary ({scheme}): {header_ratio:.2f}:1, need >= {AA_TEXT}:1")

    return lines


def check_logo_contrast(scheme_vars: dict[str, dict[str, str]]) -> list[str]:
    lines = []
    logo = logo_colors()
    for scheme in SCHEMES:
        decls = {**MATERIAL_DEFAULTS[scheme], **scheme_vars[scheme]}
        header_bg = effective_color(decls, "--md-primary-fg-color")

        stroke_ratio = contrast_ratio(logo["stroke"], header_bg)
        lines.append(f"logo stroke on header ({scheme}): {stroke_ratio:.2f}:1")
        if stroke_ratio < AA_NON_TEXT:
            fail(f"logo stroke on header ({scheme}): {stroke_ratio:.2f}:1, need >= {AA_NON_TEXT}:1")

        bar_100 = logo["bar_100"]
        bar_100_ratio = contrast_ratio(bar_100, header_bg)
        lines.append(f"logo bar (100% opacity) on header ({scheme}): {bar_100_ratio:.2f}:1")
        if bar_100_ratio < AA_NON_TEXT:
            fail(
                f"logo bar (100% opacity) on header ({scheme}): "
                f"{bar_100_ratio:.2f}:1, need >= {AA_NON_TEXT}:1"
            )

        # Informational only — this geometry (opacity included) is the
        # owner's supplied design, not something this check may fail on.
        for label in ("bar_85", "bar_70"):
            blended = blend_over(logo[label], header_bg)
            ratio = contrast_ratio(blended, header_bg)
            lines.append(f"logo {label.replace('_', ' ')} on header ({scheme}), informational: {ratio:.2f}:1")

    return lines


def check_button_contrast(
    scheme_vars: dict[str, dict[str, str]],
    button_overrides: dict[str, dict[str, dict[str, str]]],
) -> list[str]:
    lines = []
    for scheme in SCHEMES:
        decls = {**MATERIAL_DEFAULTS[scheme], **scheme_vars[scheme]}
        page_bg = effective_color(decls, "--md-default-bg-color")

        plain = {**MATERIAL_BUTTON_DEFAULTS["plain"], **button_overrides[scheme].get("plain", {})}
        plain_color = effective_button_color(plain, decls, "color")
        plain_ratio = contrast_ratio(plain_color, page_bg)
        lines.append(f"plain button (text/border) on page ({scheme}): {plain_ratio:.2f}:1")
        if plain_ratio < AA_TEXT:
            fail(
                f"plain button text/border on page ({scheme}): "
                f"{plain_ratio:.2f}:1, need >= {AA_TEXT}:1"
            )

        primary = {**MATERIAL_BUTTON_DEFAULTS["primary"], **button_overrides[scheme].get("primary", {})}
        primary_fill = effective_button_color(primary, decls, "background-color")
        primary_text = effective_button_color(primary, decls, "color")

        text_on_fill_ratio = contrast_ratio(primary_text, primary_fill)
        lines.append(f"primary button text on its fill ({scheme}): {text_on_fill_ratio:.2f}:1")
        if text_on_fill_ratio < AA_TEXT:
            fail(
                f"primary button text on fill ({scheme}): "
                f"{text_on_fill_ratio:.2f}:1, need >= {AA_TEXT}:1"
            )

        fill_on_page_ratio = contrast_ratio(primary_fill, page_bg)
        lines.append(f"primary button fill on page ({scheme}): {fill_on_page_ratio:.2f}:1")
        if fill_on_page_ratio < AA_NON_TEXT:
            fail(
                f"primary button fill on page ({scheme}): "
                f"{fill_on_page_ratio:.2f}:1, need >= {AA_NON_TEXT}:1"
            )

    return lines


def main() -> int:
    if not EXTRA_CSS.exists():
        fail(f"{EXTRA_CSS} does not exist")
    if not LOGO_SVG.exists():
        fail(f"{LOGO_SVG} does not exist")

    css_text = EXTRA_CSS.read_text(encoding="utf-8")
    scheme_vars = parse_extra_css(css_text)
    button_overrides = parse_button_overrides(css_text)

    checks = [
        ("text contrast", lambda: check_text_contrast(scheme_vars)),
        ("logo (non-text) contrast", lambda: check_logo_contrast(scheme_vars)),
        ("button contrast", lambda: check_button_contrast(scheme_vars, button_overrides)),
    ]
    for name, check in checks:
        try:
            lines = check()
        except CheckError as exc:
            print(f"FAIL [{name}]: {exc}", file=sys.stderr)
            return 1
        print(f"OK   [{name}]")
        for line in lines:
            print(f"       {line}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
