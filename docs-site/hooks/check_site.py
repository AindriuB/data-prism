#!/usr/bin/env python3
"""Guard checks over a built `site/` directory.

Run after `mkdocs build --strict` from the repo root:

    python3 docs-site/hooks/check_site.py

Exits non-zero, with a message naming the failure, on the first check that
fails. Reads `docs-site/.manifest.json`, written by `docs-site/hooks/site.py`
(`on_nav` / `on_post_page` / `on_post_build`):

- `manifest["pages"]`: every built page's title and description as mkdocs
  itself resolved them — a nav label can override a page's own front-matter
  title, so this is the only reliable source for what mkdocs decided a
  page is called.
- `manifest["nav_urls"]`: the canonical URL of every page actually reachable
  from the `nav:` config in mkdocs.yml (`nav.pages`). This is deliberately
  not the same list as `manifest["pages"]`'s URLs: mkdocs builds — and
  `on_post_page` fires for — every non-excluded doc whether or not it's in
  the nav, which is also exactly what mkdocs' own sitemap.xml template
  iterates. Comparing the sitemap against `manifest["pages"]` can therefore
  never fail, even when a page is missing from the nav; only comparing it
  against `manifest["nav_urls"]` can.
"""

from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

import yaml

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


def load_manifest() -> dict:
    if not MANIFEST_PATH.exists():
        fail(f"manifest not found at {MANIFEST_PATH} — did the build run the site.py hook?")
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    if "pages" not in manifest or "nav_urls" not in manifest:
        fail(
            f"{MANIFEST_PATH} is missing 'pages' or 'nav_urls' — "
            "is docs-site/hooks/site.py's on_nav hook running?"
        )
    return manifest


def sitemap_urls() -> set[str]:
    text = (SITE_DIR / "sitemap.xml").read_text(encoding="utf-8")
    return set(re.findall(r"<loc>(.*?)</loc>", text))


def check_sitemap_matches_nav(manifest: dict) -> None:
    # `manifest["nav_urls"]` comes from `nav.pages` in an `on_nav` hook — a
    # source genuinely independent of the sitemap, which mkdocs builds from
    # `files.documentation_pages()` (every non-excluded doc, nav or not).
    # `manifest["pages"]` is *not* used for this comparison: `on_post_page`
    # fires for that same "every non-excluded doc" set, so it would always
    # equal the sitemap by construction and could never catch a page
    # missing from the nav.
    nav_urls = set(manifest["nav_urls"])
    site_urls = sitemap_urls()
    only_in_sitemap = site_urls - nav_urls
    only_in_nav = nav_urls - site_urls
    if only_in_sitemap or only_in_nav:
        fail(
            "sitemap.xml and the nav (nav.pages) disagree — "
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


def check_meta_and_social(manifest: dict) -> None:
    seen_descriptions: dict[str, str] = {}
    for entry in manifest["pages"]:
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


# Task 85 (owner decision, 2026-09-24): the spec's literal
# `grep -rE 'fonts.googleapis|…|unpkg|jsdelivr|cdnjs' site/` cannot be
# empty, because Material 9.7.7's own `assets/javascripts/bundle.<hash>.min.js`
# (and its `.map`) contains two `unpkg.com` URLs it uses to lazy-load Mermaid
# and a ResizeObserver polyfill *only if a page ever gets a `.mermaid`
# element* — which this site never produces (no superfences `custom_fences`
# entry for mermaid; diagrams are pre-rendered SVGs). So instead of a bare
# "the pattern must not appear" check, this allows exactly those two known
# strings, confined to Material's own bundle files, and fails on anything
# else: any other hit of the pattern anywhere in `site/`, any built
# `class="mermaid"` (the only thing that makes Material fetch that URL), and
# any off-origin `<script src>` / `<link href>` / `@import` / `url(`.
THIRD_PARTY_PATTERN = re.compile(
    r"fonts\.googleapis|fonts\.gstatic|googletagmanager|google-analytics"
    r"|gtag|unpkg|jsdelivr|cdnjs"
)

# Material's minified runtime bundle and its source map — the only files
# allowed to contain the two known unpkg.com strings below.
BUNDLE_PATH_RE = re.compile(r"^assets/javascripts/bundle\.[0-9a-f]+\.min\.js(\.map)?$")

ALLOWED_BUNDLE_URLS = frozenset(
    {
        "https://unpkg.com/mermaid@11/dist/mermaid.min.js",
        "https://unpkg.com/resize-observer-polyfill",
    }
)

# A scheme-qualified URL token, used to pull out *actual references* from the
# bundle rather than every bare substring match: the bundle's `.map` also
# carries Material's own source comment "...downloaded from unpkg.com...",
# which matches THIRD_PARTY_PATTERN but is prose, not a URL, and not a
# reference the browser ever fetches. Confining the allowlist comparison to
# scheme-qualified tokens lets that harmless mention through while still
# catching a genuine third `https://unpkg.com/...`-shaped string planted in
# the bundle.
URL_TOKEN_RE = re.compile(r"https?://[^\s\"'\\)]+")


def check_no_third_party_scripts() -> None:
    for path in SITE_DIR.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(SITE_DIR).as_posix()
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if not THIRD_PARTY_PATTERN.search(text):
            continue
        if BUNDLE_PATH_RE.match(rel):
            found_urls = {
                token
                for token in (m.group(0) for m in URL_TOKEN_RE.finditer(text))
                if THIRD_PARTY_PATTERN.search(token)
            }
            unexpected = found_urls - ALLOWED_BUNDLE_URLS
            if unexpected:
                fail(
                    f"{rel}: unexpected third-party script reference(s) in "
                    f"Material's own bundle: {sorted(unexpected)}"
                )
            continue
        fail(
            f"{rel}: found a third-party script/font/analytics reference "
            "(fonts.googleapis/gstatic, googletagmanager, google-analytics, "
            "gtag, unpkg, jsdelivr or cdnjs) outside Material's own bundle"
        )


MERMAID_CLASS_RE = re.compile(r'class="[^"]*\bmermaid\b[^"]*"')


def check_no_mermaid_class() -> None:
    # The only thing that makes Material's bundle fetch the mermaid.js URL
    # from unpkg.com at runtime is a `.mermaid` element on the page — which
    # this site must never produce, since diagrams are pre-rendered SVGs.
    for path in SITE_DIR.rglob("*.html"):
        rel = path.relative_to(SITE_DIR).as_posix()
        text = path.read_text(encoding="utf-8")
        if MERMAID_CLASS_RE.search(text):
            fail(f'{rel}: found class="mermaid", which triggers Material\'s runtime unpkg.com fetch')


_ATTR_SRC_HREF_RE = re.compile(
    r'<(?:script[^>]*\ssrc|link[^>]*\shref)\s*=\s*"([^"]*)"', re.IGNORECASE
)
_AT_IMPORT_RE = re.compile(r'@import\s+(?:url\(\s*)?["\']?([^"\'\);]+)', re.IGNORECASE)
_URL_FUNC_RE = re.compile(r'\burl\(\s*["\']?([^"\')]+)["\']?\s*\)', re.IGNORECASE)


def _is_offsite(url: str) -> bool:
    if url.startswith(("data:", "#", "mailto:", "/")):
        return False
    if url.startswith(SITE_URL):
        return False
    return url.startswith(("http://", "https://"))


def check_no_offsite_resources() -> None:
    for path in (*SITE_DIR.rglob("*.html"), *SITE_DIR.rglob("*.css")):
        rel = path.relative_to(SITE_DIR).as_posix()
        text = path.read_text(encoding="utf-8")
        checks: list[tuple[str, re.Pattern[str]]] = [("@import", _AT_IMPORT_RE), ("url()", _URL_FUNC_RE)]
        if path.suffix == ".html":
            checks.insert(0, ("<script src> / <link href>", _ATTR_SRC_HREF_RE))
        for label, pattern in checks:
            for match in pattern.finditer(text):
                url = html.unescape(match.group(1)).strip()
                if _is_offsite(url):
                    fail(f"{rel}: off-origin {label} reference: {url}")


def check_no_robots_txt() -> None:
    if (SITE_DIR / "robots.txt").exists():
        fail("site/robots.txt exists; a project Pages site must not ship one")
    if (REPO_ROOT / "docs" / "robots.txt").exists():
        fail("docs/robots.txt exists; a project Pages site must not ship one")


DOCS_DIR = REPO_ROOT / "docs"
EXCLUDED_DOC_PREFIXES = (
    "plan/",
    "adr/",
    "pack.md",
    "conventions.md",
    "workflow.md",
    "development-plan.md",
    "design-review.md",
)


def _is_excluded_doc(rel_posix: str) -> bool:
    return any(rel_posix == prefix or rel_posix.startswith(prefix) for prefix in EXCLUDED_DOC_PREFIXES)


def _iter_excluded_doc_files() -> list[Path]:
    return [
        path
        for path in sorted(DOCS_DIR.rglob("*.md"))
        if _is_excluded_doc(path.relative_to(DOCS_DIR).as_posix())
    ]


def _distinctive_line(path: Path, min_length: int = 40) -> str | None:
    """A line from `path` long enough that its literal presence elsewhere is
    good evidence of that file's actual content, not a coincidental short
    heading (`docs/pack.md`'s own first `#` heading is just '# Data Prism',
    which legitimately appears elsewhere on every page of the site)."""
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if len(stripped) >= min_length:
            return stripped
    return None


def check_llms_files() -> None:
    llms = SITE_DIR / "llms.txt"
    llms_full = SITE_DIR / "llms-full.txt"
    if not llms.exists():
        fail("site/llms.txt does not exist")
    if not llms_full.exists() or not llms_full.read_text(encoding="utf-8").strip():
        fail("site/llms-full.txt does not exist or is empty")

    llms_text = llms.read_text(encoding="utf-8")
    llms_full_text = llms_full.read_text(encoding="utf-8")
    if not llms_text.splitlines()[0].strip() == "# Data Prism":
        fail(f"site/llms.txt's first line is {llms_text.splitlines()[0]!r}, expected '# Data Prism'")
    # The exact content of the canonical description D is checked separately,
    # in pages.yml and by the tester, with `grep -F` against the spec's own
    # copy of D — not duplicated here as a fourth copy of the string.

    # Content, not a URL, is what actually leaking would look like: a bare
    # substring/URL check on the excluded filenames or paths is not used,
    # because docs/architecture.md and docs/extending.md are published pages
    # that legitimately cite `pack.md` and `design-review.md` by name, in
    # backticks, in their own prose (as the historical spec they amend) —
    # neither is a doc this task may edit, and citing a filename in prose is
    # not the leak this check is for. A whole distinctive line copied
    # verbatim from an excluded file is not something legitimate prose does
    # by accident.
    for excluded_path in _iter_excluded_doc_files():
        marker = _distinctive_line(excluded_path)
        if marker is None:
            continue
        rel = excluded_path.relative_to(REPO_ROOT).as_posix()
        for target_name, text in (("llms.txt", llms_text), ("llms-full.txt", llms_full_text)):
            if marker in text:
                fail(f"{target_name} contains a line from excluded page {rel}: {marker!r}")


def _doc_path_to_site_url(doc_path: str) -> str:
    """The canonical URL mkdocs gives a `docs_dir`-relative path, under
    `use_directory_urls` (the default, unchanged here): `index.md` and
    `README.md` name a directory's own index page; everything else gets a
    same-named directory."""
    parts = doc_path.strip("/").split("/")
    stem = parts[-1]
    if stem.lower() in ("index.md", "readme.md"):
        dir_parts = parts[:-1]
    else:
        dir_parts = [*parts[:-1], stem[: -len(".md")]]
    rel = "/".join(dir_parts)
    return SITE_URL + (f"{rel}/" if rel else "")


def check_llmstxt_sections_match_nav(manifest: dict) -> None:
    """The llmstxt plugin's `sections` in mkdocs.yml is a second, independent
    list of "every page that should be published" — it drives what actually
    ends up embedded in llms.txt/llms-full.txt. If it drifts from the nav
    (a page added to one and not the other), this catches it structurally,
    without having to parse page boundaries out of llms-full.txt's own
    concatenated Markdown, which carries no per-page marker to parse."""
    mkdocs_config = yaml.safe_load((REPO_ROOT / "mkdocs.yml").read_text(encoding="utf-8"))
    llmstxt_config = None
    for entry in mkdocs_config.get("plugins", []):
        if isinstance(entry, dict) and "llmstxt" in entry:
            llmstxt_config = entry["llmstxt"] or {}
            break
    if llmstxt_config is None:
        fail("mkdocs.yml has no 'llmstxt' plugin configured")

    doc_paths: list[str] = []
    for items in (llmstxt_config.get("sections") or {}).values():
        for item in items:
            doc_paths.append(next(iter(item)) if isinstance(item, dict) else item)

    llmstxt_urls = {_doc_path_to_site_url(p) for p in doc_paths}
    nav_urls = set(manifest["nav_urls"])
    if llmstxt_urls != nav_urls:
        fail(
            "mkdocs.yml's llmstxt plugin sections and its nav disagree — "
            f"only in llmstxt sections: {sorted(llmstxt_urls - nav_urls)}, "
            f"only in nav: {sorted(nav_urls - llmstxt_urls)}"
        )


def main() -> int:
    if not SITE_DIR.exists():
        fail(f"{SITE_DIR} does not exist — run `mkdocs build --strict` first")

    manifest = load_manifest()
    checks = [
        ("no excluded paths in site/", check_no_excluded_paths),
        ("sitemap matches nav", lambda: check_sitemap_matches_nav(manifest)),
        ("meta description / canonical / social tags", lambda: check_meta_and_social(manifest)),
        ("exactly one JSON-LD block, on index.html only", check_single_json_ld),
        ("no third-party scripts, fonts or analytics", check_no_third_party_scripts),
        ("no built class=\"mermaid\" element", check_no_mermaid_class),
        ("no off-origin script/link/@import/url() reference", check_no_offsite_resources),
        ("no robots.txt", check_no_robots_txt),
        ("llms.txt / llms-full.txt", check_llms_files),
        ("llmstxt sections match nav", lambda: check_llmstxt_sections_match_nav(manifest)),
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
