#!/usr/bin/env python3
"""Guards the changelog page's collapse transform (`docs-site/hooks/changelog.py`).

Run after `mkdocs build --strict` from the repo root:

    python3 docs-site/hooks/check_changelog.py

`CHANGELOG.md` itself is always the source of truth for which versions and
link-reference definitions must survive. Two other inputs are checked
against it, each independently overridable so a scratch copy — with a
release or a link-reference definition deliberately deleted — can be pointed
at instead of the real build output, to prove this guard can fail:

    python3 docs-site/hooks/check_changelog.py --rendered /tmp/scratch.md
    python3 docs-site/hooks/check_changelog.py --html /tmp/scratch.html

- `--rendered` (default `docs-site/.changelog-rendered.md`): the exact
  Markdown text `docs-site/hooks/changelog.py`'s `on_page_markdown` handed
  to mkdocs for `changelog.md` — checked for every `x.y.z` version and every
  link-reference-definition line CHANGELOG.md itself has.
- `--html` (default `site/changelog/index.html`): the built page — checked
  for every `x.y.z` version appearing as visible text, every link-reference
  label resolving to a real `<a href>` pointing at its defined URL, and no
  literal unresolved `[x.y.z]` bracket text left over anywhere.

Exits non-zero, naming what's missing, on the first check that fails.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CHANGELOG_PATH = REPO_ROOT / "CHANGELOG.md"
RENDERED_PATH = REPO_ROOT / "docs-site" / ".changelog-rendered.md"
HTML_PATH = REPO_ROOT / "site" / "changelog" / "index.html"

# Only real `x.y.z` releases — deliberately excludes `[Unreleased]`, which
# `changelog.py` drops from the page whenever it has no content, by design
# (docs/plan/specs/2026-09-24-site-polish-and-developer-guide.md, section B).
VERSION_RE = re.compile(r"^## \[(?P<version>\d+\.\d+\.\d+)\]", re.MULTILINE)
LINKREF_RE = re.compile(r"^\[(?P<label>\d+\.\d+\.\d+)\]:\s*(?P<url>\S+)\s*$", re.MULTILINE)


class CheckError(Exception):
    pass


def fail(message: str) -> None:
    raise CheckError(message)


def _read(path: Path, what: str) -> str:
    if not path.exists():
        fail(f"{what} not found at {path}")
    return path.read_text(encoding="utf-8")


def changelog_versions(changelog_text: str) -> list[str]:
    return VERSION_RE.findall(changelog_text)


def changelog_link_refs(changelog_text: str) -> list[tuple[str, str]]:
    return LINKREF_RE.findall(changelog_text)


def check_versions_in_rendered(changelog_text: str, rendered_text: str) -> None:
    # A bare substring search for the version number is not enough: the
    # trailing link-reference-definition line for a version
    # (`[x.y.z]: https://...`) always contains that same digit string, and
    # survives untouched even if the release block that used to reference it
    # is dropped entirely — so a substring check alone would pass vacuously
    # on exactly the failure this guard exists to catch. Instead, this
    # requires a `pymdownx.details` marker line naming that version as its
    # own collapsible block: `??? "[x.y.z]` or `???+ "[x.y.z]`.
    missing = [
        v
        for v in changelog_versions(changelog_text)
        if not re.search(rf'^\?\?\?\+?\s+"\[{re.escape(v)}\]', rendered_text, re.MULTILINE)
    ]
    if missing:
        fail(f"version(s) missing their own collapsible block in the rendered changelog markdown: {missing}")


def check_link_refs_in_rendered(changelog_text: str, rendered_text: str) -> None:
    # Anchored with `^`/MULTILINE, and not indented, so a definition that
    # pymdownx.details has swallowed into the last block's four-space indent
    # (rather than staying at top level, as CHANGELOG.md's own trailing
    # link-reference definitions must) fails this check instead of passing
    # on a plain substring match.
    missing = [
        label
        for label, url in changelog_link_refs(changelog_text)
        if not re.search(rf"^\[{re.escape(label)}\]:\s*{re.escape(url)}\s*$", rendered_text, re.MULTILINE)
    ]
    if missing:
        fail(f"link-reference definition(s) missing from the rendered changelog markdown: {missing}")


def check_versions_in_html(changelog_text: str, html_text: str) -> None:
    # As in check_versions_in_rendered: a bare substring search is not
    # enough here either. Every release's changelog prose routinely
    # mentions *other* version numbers by name (0.2.0's "Behavioural change
    # for API consumers" entries are full of comparisons to 0.1.1 and
    # 0.3.0), so a dropped release's own version number can still be
    # littered throughout the rest of the built page. This instead requires
    # the version to open a `<summary>` element — i.e. to be the heading of
    # its own collapsible block — same as check_versions_in_rendered checks
    # for the pre-render marker line.
    unescaped = html.unescape(html_text)
    missing = [
        v
        for v in changelog_versions(changelog_text)
        if not re.search(rf'<summary>\s*(?:<a[^>]*>)?\s*{re.escape(v)}\b', unescaped)
    ]
    if missing:
        fail(f"version(s) missing their own <summary> block in the built changelog page: {missing}")


def check_link_refs_resolve_in_html(changelog_text: str, html_text: str) -> None:
    unescaped = html.unescape(html_text)
    unresolved = []
    broken = []
    for label, url in changelog_link_refs(changelog_text):
        anchor_re = re.compile(
            rf'<a\s+href="{re.escape(url)}"[^>]*>\s*{re.escape(label)}\s*</a>'
        )
        if not anchor_re.search(unescaped):
            broken.append(label)
        if f"[{label}]" in unescaped:
            unresolved.append(label)
    if broken:
        fail(f"link-reference label(s) do not resolve to an <a href> in the built page: {broken}")
    if unresolved:
        fail(f"literal unresolved bracket text left in the built page for: {unresolved}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--changelog", type=Path, default=CHANGELOG_PATH)
    parser.add_argument("--rendered", type=Path, default=RENDERED_PATH)
    parser.add_argument("--html", type=Path, default=HTML_PATH)
    args = parser.parse_args()

    changelog_text = _read(
        args.changelog,
        "CHANGELOG.md",
    )
    rendered_text = _read(
        args.rendered,
        "rendered changelog markdown — did the docs-site/hooks/changelog.py build hook run?",
    )
    html_text = _read(
        args.html,
        "built changelog page — run `mkdocs build --strict` first",
    )

    checks = [
        ("every release version appears in the rendered markdown", lambda: check_versions_in_rendered(changelog_text, rendered_text)),
        ("every link-reference definition appears in the rendered markdown", lambda: check_link_refs_in_rendered(changelog_text, rendered_text)),
        ("every release version appears in the built page", lambda: check_versions_in_html(changelog_text, html_text)),
        ("every link-reference label resolves in the built page, none left unresolved", lambda: check_link_refs_resolve_in_html(changelog_text, html_text)),
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
