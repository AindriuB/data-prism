#!/usr/bin/env python3
"""Guards the diagrams embedded in `docs/architecture.md`, `docs/tools.md`,
`docs/configuration.md` and `docs/audit.md` (task 88; task 90 adds a sixth
diagram later, to the developer guide, using the same convention).

Run after `mkdocs build --strict` from the repo root:

    python3 docs-site/hooks/check_diagrams.py

Fails, naming the offending file, unless all of these hold:

- every `docs-site/diagrams/*.mmd` has a matching `docs/assets/diagrams/*.svg`
  with the same stem, and vice versa;
- every SVG under `docs/assets/diagrams/` is referenced by at least one
  built page under `site/`;
- every diagram `<img>` in built HTML carries a non-empty `alt`;
- no SVG under `docs/assets/diagrams/` contains a URL outside w3.org, a
  `<script`, or an `@import`.

See `docs-site/diagrams/README.md` for the render recipe and the trace table
each diagram's nodes and edges must show up in.
"""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DIAGRAMS_SRC_DIR = REPO_ROOT / "docs-site" / "diagrams"
DIAGRAMS_SVG_DIR = REPO_ROOT / "docs" / "assets" / "diagrams"
SITE_DIR = REPO_ROOT / "site"


class CheckError(Exception):
    pass


def fail(message: str) -> None:
    raise CheckError(message)


def check_mmd_svg_pairs() -> None:
    mmd_stems = {p.stem for p in DIAGRAMS_SRC_DIR.glob("*.mmd")}
    svg_stems = {p.stem for p in DIAGRAMS_SVG_DIR.glob("*.svg")} if DIAGRAMS_SVG_DIR.exists() else set()

    if not mmd_stems:
        fail(f"no .mmd files found in {DIAGRAMS_SRC_DIR}")

    missing_svg = mmd_stems - svg_stems
    if missing_svg:
        fail(
            f"missing rendered SVG for: {sorted(missing_svg)} "
            "(run docs-site/diagrams/render.sh)"
        )

    orphan_svg = svg_stems - mmd_stems
    if orphan_svg:
        fail(f"SVG(s) under {DIAGRAMS_SVG_DIR} with no matching .mmd source: {sorted(orphan_svg)}")


# A `<img ...>` opening tag, its attribute body captured whole so both `src`
# and `alt` inside it can be found independently of attribute order.
_IMG_TAG_RE = re.compile(r"<img\b([^>]*)>", re.IGNORECASE)


def _attr_re(attr_name: str) -> re.Pattern[str]:
    return re.compile(
        rf"""\b{attr_name}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
        re.IGNORECASE,
    )


_SRC_ATTR_RE = _attr_re("src")
_ALT_ATTR_RE = _attr_re("alt")


def _first_group(match: re.Match[str] | None) -> str | None:
    if match is None:
        return None
    for group in match.groups():
        if group is not None:
            return group
    return None


def _is_diagram_svg_reference(src: str) -> bool:
    """Whether an `<img src="...">` value points at one of our own rendered
    diagrams, regardless of how many `../` the page's own depth added — the
    parent two path segments have to be exactly `assets/diagrams/`, checked
    by path parts rather than a substring, so it isn't fooled by an
    unrelated path that merely contains that text somewhere else."""
    parts = Path(urlsplit(src).path).parts
    return len(parts) >= 3 and parts[-3] == "assets" and parts[-2] == "diagrams"


def check_diagrams_referenced_and_have_alt() -> None:
    if not SITE_DIR.exists():
        fail(f"{SITE_DIR} does not exist — run `mkdocs build --strict` first")

    referenced: set[str] = set()
    for path in sorted(SITE_DIR.rglob("*.html")):
        rel = path.relative_to(SITE_DIR).as_posix()
        text = path.read_text(encoding="utf-8")
        for tag_match in _IMG_TAG_RE.finditer(text):
            attrs = tag_match.group(1)
            raw_src = _first_group(_SRC_ATTR_RE.search(attrs))
            if raw_src is None:
                continue
            src = html.unescape(raw_src)
            if not _is_diagram_svg_reference(src):
                continue
            referenced.add(Path(urlsplit(src).path).name)

            raw_alt = _first_group(_ALT_ATTR_RE.search(attrs))
            alt = html.unescape(raw_alt).strip() if raw_alt is not None else ""
            if not alt:
                fail(f'{rel}: diagram <img src="{src}"> has no non-empty alt text')

    svg_names = {p.name for p in DIAGRAMS_SVG_DIR.glob("*.svg")}
    unreferenced = svg_names - referenced
    if unreferenced:
        fail(f"SVG(s) under {DIAGRAMS_SVG_DIR} not referenced by any built page: {sorted(unreferenced)}")


_URL_RE = re.compile(r"https?://[^\"'\s)]+")
_ALLOWED_URL_PREFIXES = ("http://www.w3.org/", "https://www.w3.org/")


def check_svgs_are_clean() -> None:
    if not DIAGRAMS_SVG_DIR.exists():
        fail(f"{DIAGRAMS_SVG_DIR} does not exist — run docs-site/diagrams/render.sh first")

    for path in sorted(DIAGRAMS_SVG_DIR.glob("*.svg")):
        text = path.read_text(encoding="utf-8")
        if "<script" in text.lower():
            fail(f"{path.name}: contains <script")
        if "@import" in text:
            fail(f"{path.name}: contains @import")
        for url in _URL_RE.findall(text):
            if not url.startswith(_ALLOWED_URL_PREFIXES):
                fail(f"{path.name}: non-w3.org URL {url!r}")


def main() -> int:
    checks = [
        ("every .mmd has a matching .svg, and vice versa", check_mmd_svg_pairs),
        ("every diagram SVG is referenced and has alt text", check_diagrams_referenced_and_have_alt),
        ("no off-w3.org URL, <script> or @import in a diagram SVG", check_svgs_are_clean),
    ]
    for name, check in checks:
        try:
            check()
        except CheckError as exc:
            print(f"FAIL [{name}]: {exc}", file=sys.stderr)
            return 1
        print(f"OK   [{name}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
