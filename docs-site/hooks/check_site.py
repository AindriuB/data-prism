#!/usr/bin/env python3
"""Guard checks over a built `site/` directory.

Run after `mkdocs build --strict` from the repo root:

    python3 docs-site/hooks/check_site.py

Exits non-zero, with a message naming the failure, on the first check that
fails. Reads `docs-site/.manifest.json`, written by `docs-site/hooks/site.py`
during the build (`on_post_page` / `on_post_build`), for each page's title
and description as mkdocs itself resolved them — a nav label can override a
page's own front-matter title, so this is the only reliable source for what
mkdocs decided a page is called.
"""

from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
SITE_DIR = REPO_ROOT / "site"
MANIFEST_PATH = REPO_ROOT / "docs-site" / ".manifest.json"
SITE_URL = "https://aindriub.github.io/data-prism/"
SOCIAL_CARD_URL = SITE_URL + "assets/social-card.png"
MAX_DESCRIPTION_LENGTH = 155
EXCLUDED_SEGMENTS = (
    "plan",
    "adr",
    "pack",
    "conventions",
    "workflow",
    "development-plan",
    "design-review",
)


class CheckError(Exception):
    pass


def fail(message: str) -> None:
    raise CheckError(message)


def check_no_excluded_paths() -> None:
    for path in SITE_DIR.rglob("*"):
        rel = path.relative_to(SITE_DIR)
        parts = rel.parts
        for segment in EXCLUDED_SEGMENTS:
            if any(part == segment or part.startswith(f"{segment}.") for part in parts):
                fail(f"excluded path leaked into site/: {rel.as_posix()}")

    sitemap = (SITE_DIR / "sitemap.xml").read_text(encoding="utf-8")
    for segment in EXCLUDED_SEGMENTS:
        if segment in sitemap:
            fail(f"sitemap.xml mentions excluded segment '{segment}'")


def load_manifest() -> list[dict]:
    if not MANIFEST_PATH.exists():
        fail(f"manifest not found at {MANIFEST_PATH} — did the build run the site.py hook?")
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def sitemap_urls() -> set[str]:
    text = (SITE_DIR / "sitemap.xml").read_text(encoding="utf-8")
    return set(re.findall(r"<loc>(.*?)</loc>", text))


def check_sitemap_matches_nav(manifest: list[dict]) -> None:
    nav_urls = {entry["url"] for entry in manifest}
    site_urls = sitemap_urls()
    only_in_sitemap = site_urls - nav_urls
    only_in_nav = nav_urls - site_urls
    if only_in_sitemap or only_in_nav:
        fail(
            "sitemap.xml and the nav-page manifest disagree — "
            f"only in sitemap: {sorted(only_in_sitemap)}, "
            f"only in nav: {sorted(only_in_nav)}"
        )


def _html_path_for_url(url: str) -> Path:
    path = urlsplit(url).path
    rel = path[len(urlsplit(SITE_URL).path):] if path.startswith(urlsplit(SITE_URL).path) else path.lstrip("/")
    rel = rel.rstrip("/")
    candidate = SITE_DIR / rel / "index.html" if rel else SITE_DIR / "index.html"
    if not candidate.exists():
        candidate = SITE_DIR / f"{rel}.html"
    return candidate


def check_meta_and_social(manifest: list[dict]) -> None:
    seen_descriptions: dict[str, str] = {}
    for entry in manifest:
        url = entry["url"]
        html_path = _html_path_for_url(url)
        if not html_path.exists():
            fail(f"no HTML file found on disk for sitemap URL {url} (looked at {html_path})")
        page_html = html_path.read_text(encoding="utf-8")

        descriptions = re.findall(
            r'<meta\s+name="description"\s+content="([^"]*)"', page_html
        )
        if len(descriptions) != 1 or not descriptions[0].strip():
            fail(f"{url}: expected exactly one non-empty meta description, found {descriptions}")
        description = html.unescape(descriptions[0])
        if len(description) > MAX_DESCRIPTION_LENGTH:
            fail(f"{url}: meta description is {len(description)} characters, over {MAX_DESCRIPTION_LENGTH}")
        if description in seen_descriptions:
            fail(f"{url} and {seen_descriptions[description]} share one meta description")
        seen_descriptions[description] = url

        canonical = re.findall(r'<link\s+rel="canonical"\s+href="([^"]*)"', page_html)
        if not canonical or not canonical[0].startswith(SITE_URL):
            fail(f"{url}: canonical link missing or not under {SITE_URL}: {canonical}")

        og_image = re.findall(r'<meta\s+property="og:image"\s+content="([^"]*)"', page_html)
        twitter_image = re.findall(r'<meta\s+name="twitter:image"\s+content="([^"]*)"', page_html)
        if og_image != [SOCIAL_CARD_URL]:
            fail(f"{url}: og:image is {og_image}, expected {[SOCIAL_CARD_URL]}")
        if twitter_image != [SOCIAL_CARD_URL]:
            fail(f"{url}: twitter:image is {twitter_image}, expected {[SOCIAL_CARD_URL]}")

        twitter_card = re.findall(r'<meta\s+name="twitter:card"\s+content="([^"]*)"', page_html)
        if twitter_card != ["summary_large_image"]:
            fail(f"{url}: twitter:card is {twitter_card}, expected ['summary_large_image']")

        og_title = re.findall(r'<meta\s+property="og:title"\s+content="([^"]*)"', page_html)
        if not og_title or html.unescape(og_title[0]) != entry["title"]:
            fail(f"{url}: og:title {og_title} does not match page title {entry['title']!r}")

        og_description = re.findall(r'<meta\s+property="og:description"\s+content="([^"]*)"', page_html)
        if not og_description or html.unescape(og_description[0]) != description:
            fail(f"{url}: og:description {og_description} does not match meta description")


def check_single_json_ld() -> None:
    for path in SITE_DIR.rglob("*.html"):
        text = path.read_text(encoding="utf-8")
        count = text.count('<script type="application/ld+json">')
        rel = path.relative_to(SITE_DIR).as_posix()
        if rel == "index.html":
            if count != 1:
                fail(f"index.html should carry exactly one JSON-LD block, found {count}")
        elif count != 0:
            fail(f"{rel} carries a JSON-LD block; only site/index.html should")


def check_no_fonts_or_analytics() -> None:
    pattern = re.compile(
        r"fonts\.googleapis|fonts\.gstatic|googletagmanager|google-analytics"
    )
    for path in SITE_DIR.rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if pattern.search(text):
            fail(f"{path.relative_to(SITE_DIR)}: found a Google Fonts/Analytics reference")


def check_no_robots_txt() -> None:
    if (SITE_DIR / "robots.txt").exists():
        fail("site/robots.txt exists; a project Pages site must not ship one")
    if (REPO_ROOT / "docs" / "robots.txt").exists():
        fail("docs/robots.txt exists; a project Pages site must not ship one")


def check_llms_files() -> None:
    llms = SITE_DIR / "llms.txt"
    llms_full = SITE_DIR / "llms-full.txt"
    if not llms.exists():
        fail("site/llms.txt does not exist")
    if not llms_full.exists() or not llms_full.read_text(encoding="utf-8").strip():
        fail("site/llms-full.txt does not exist or is empty")

    llms_text = llms.read_text(encoding="utf-8")
    if not llms_text.splitlines()[0].strip() == "# Data Prism":
        fail(f"site/llms.txt's first line is {llms_text.splitlines()[0]!r}, expected '# Data Prism'")
    # The exact content of the canonical description D is checked separately,
    # in pages.yml and by the tester, with `grep -F` against the spec's own
    # copy of D — not duplicated here as a fourth copy of the string.

    # An excluded page is never built, so mkdocs-llmstxt could only embed one
    # if `sections` in mkdocs.yml named it directly (which would itself make
    # `mkdocs build --strict` fail, since the file isn't in `Files`). What's
    # checked here is that no such page is ever *linked* from either file,
    # by looking for the site's own URL prefix followed by the excluded
    # segment — i.e. an actual link into the excluded page, not a mention.
    #
    # A bare substring check on the excluded filenames (e.g. 'pack.md') is
    # deliberately not used: docs/architecture.md and docs/extending.md are
    # published pages that legitimately cite `pack.md` and
    # `design-review.md` by name, in backticks, in their own prose (as the
    # historical spec they amend) — neither is a doc this task may edit,
    # and citing a filename in prose is not the leak this check is for.
    excluded_segments = ("plan", "adr", "pack", "conventions", "workflow", "development-plan", "design-review")
    for target in (llms, llms_full):
        text = target.read_text(encoding="utf-8")
        for segment in excluded_segments:
            if f"{SITE_URL}{segment}/" in text:
                fail(f"{target.name} links the excluded page '{segment}'")


def main() -> int:
    if not SITE_DIR.exists():
        fail(f"{SITE_DIR} does not exist — run `mkdocs build --strict` first")

    manifest = load_manifest()
    checks = [
        ("no excluded paths in site/", check_no_excluded_paths),
        ("sitemap matches nav", lambda: check_sitemap_matches_nav(manifest)),
        ("meta description / canonical / social tags", lambda: check_meta_and_social(manifest)),
        ("exactly one JSON-LD block, on index.html only", check_single_json_ld),
        ("no Google Fonts or analytics references", check_no_fonts_or_analytics),
        ("no robots.txt", check_no_robots_txt),
        ("llms.txt / llms-full.txt", check_llms_files),
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
