"""MkDocs build hook that collapses the rendered changelog page.

`docs/changelog.md` is nothing but `{% include-markdown "../CHANGELOG.md" %}`
(plus front matter mkdocs strips before any hook sees the page). Hooks run
after plugins, so by the time `on_page_markdown` fires here, `markdown` is
CHANGELOG.md's own text, verbatim — confirmed by a scratch build that wrote
what this hook receives to disk and diffed it against CHANGELOG.md.

`CHANGELOG.md` is never edited (task 87's whole point): this hook only
changes how `changelog.md` renders. Each `## [x.y.z]` release becomes one
`pymdownx.details` collapsible block (`???`/`???+`), its content indented
four spaces so fenced code, nested lists and tables inside it keep rendering
exactly as before — indenting is the only transform applied to a release's
body text. The newest release (first in file order) starts open; every
other starts closed. `[Unreleased]` is included, and can start open, only
when it has actual content — CHANGELOG.md keeps it present but empty today,
so it is dropped from the page. The trailing link-reference definitions
(`[0.3.0]: https://...` and the rest) are left untouched, unindented, after
every details block, so `[x.y.z]` used inside a details title still
resolves to a real link (pymdownx.details resolves Markdown reference links
inside a quoted title, also confirmed by a scratch render).

The exact text this hook hands mkdocs is also written to
`docs-site/.changelog-rendered.md` (gitignored, alongside
`docs-site/.manifest.json`), so `docs-site/hooks/check_changelog.py` can
read what was actually rendered instead of re-deriving it.
"""

from __future__ import annotations

import re
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent
DOCS_SITE_DIR = HOOKS_DIR.parent
RENDERED_PATH = DOCS_SITE_DIR / ".changelog-rendered.md"

# A release heading: "## [x.y.z] - yyyy-mm-dd" or "## [Unreleased]" (no date).
RELEASE_RE = re.compile(r"^## \[(?P<version>[^\]]+)\](?:\s*-\s*(?P<date>.+?))?\s*$")
SUBSECTION_RE = re.compile(r"^### (?P<name>.+?)\s*$")
# A trailing link-reference-definition line, e.g. "[0.3.0]: https://...".
LINKREF_RE = re.compile(r"^\[[^\]]+\]:\s*\S+\s*$")

# Counting rule (stated once, here, for the guard and the report to cite):
# for each `###` subsection in a release, count its top-level `- ` list
# items (CHANGELOG.md never nests a list item under another inside a
# release) and render "N <label>" for every subsection that has at least
# one item, in the order the subsections appear, joined with " · ". A
# subsection with zero top-level `- ` items — e.g. a prose-only `### Notes`
# — is omitted from this summary line; its heading and text are still
# rendered in full inside the collapsible block, only the summary line ever
# leaves it out. A subsection name this map has never seen falls back to its
# own lowercased heading, both in the summary line and here.
SUBSECTION_LABELS = {
    "Added": "added",
    "Changed": "changed",
    "Fixed": "fixed",
    "Behavioural change for API consumers": "behavioural changes",
    "Not changed": "not changed",
    "Not included in this release": "not included",
}

# Singular form for a count of 1, for the noun-style labels above ("1
# behavioural change", not "1 behavioural changes"). The other known labels
# are verb-style (added, changed, fixed, not changed, not included) and read
# correctly for any count, so they are left out of this map; an unknown
# heading keeps its own lowercased form as-is, for any count.
SUBSECTION_SINGULAR = {
    "behavioural changes": "behavioural change",
}


def _split_trailing_link_refs(lines: list[str]) -> tuple[list[str], list[str]]:
    """Split off the trailing run of link-reference-definition lines
    (optionally preceded, and followed, by blank lines) so they can be
    re-emitted verbatim, unindented, at the very end — pymdownx.details
    would otherwise happily swallow them into whichever release block
    precedes them. Trailing blank lines are skipped first, unconditionally
    (mkdocs' include-markdown plugin adds one after CHANGELOG.md's own final
    newline), so a blank end-of-file line never masks the link-ref block
    that precedes it."""
    idx = len(lines)
    while idx > 0 and lines[idx - 1].strip() == "":
        idx -= 1
    link_end = idx
    while idx > 0 and LINKREF_RE.match(lines[idx - 1]):
        idx -= 1
    if idx == link_end:
        return lines, []
    link_lines = lines[idx:link_end]
    body = lines[:idx]
    while body and body[-1].strip() == "":
        body.pop()
    return body, link_lines


def _parse_releases(body_lines: list[str]) -> tuple[list[str], list[dict]]:
    heading_indices = [i for i, line in enumerate(body_lines) if RELEASE_RE.match(line)]
    if not heading_indices:
        return body_lines, []

    preamble = body_lines[: heading_indices[0]]
    releases = []
    for n, start in enumerate(heading_indices):
        end = heading_indices[n + 1] if n + 1 < len(heading_indices) else len(body_lines)
        match = RELEASE_RE.match(body_lines[start])
        assert match is not None
        content = body_lines[start + 1 : end]
        while content and content[0].strip() == "":
            content.pop(0)
        while content and content[-1].strip() == "":
            content.pop()
        releases.append(
            {
                "version": match.group("version"),
                "date": match.group("date"),
                "content": content,
            }
        )
    return preamble, releases


def _count_subsections(content_lines: list[str]) -> list[tuple[str, int]]:
    counts: list[tuple[str, int]] = []
    label: str | None = None
    count = 0
    for line in content_lines:
        match = SUBSECTION_RE.match(line)
        if match:
            if label is not None and count:
                counts.append((label, count))
            label = SUBSECTION_LABELS.get(match.group("name"), match.group("name").lower())
            count = 0
            continue
        if line.startswith("- "):
            count += 1
    if label is not None and count:
        counts.append((label, count))
    return counts


def _build_title(version: str, date: str | None, counts: list[tuple[str, int]]) -> str:
    head = f"[{version}]"
    if date:
        head = f"{head} — {date}"
    if counts:
        counts_text = " · ".join(
            f"{n} {label if n != 1 else SUBSECTION_SINGULAR.get(label, label)}"
            for label, n in counts
        )
        return f"{head} · {counts_text}"
    return head


def _indent(lines: list[str]) -> list[str]:
    return [f"    {line}" if line.strip() else "" for line in lines]


def render_changelog(markdown: str) -> str:
    """The pure transform, kept separate from `on_page_markdown` so
    `check_changelog.py` and a scratch "drop one release" test can call it
    directly."""
    lines = markdown.splitlines()
    body, link_ref_lines = _split_trailing_link_refs(lines)
    preamble, releases = _parse_releases(body)

    rendered_blocks: list[str] = []
    is_first_rendered = True
    for release in releases:
        content = release["content"]
        if not any(line.strip() for line in content):
            # Empty release (CHANGELOG.md's own [Unreleased], today) is
            # dropped rather than shown as an empty collapsible block.
            continue
        counts = _count_subsections(content)
        title = _build_title(release["version"], release["date"], counts)
        marker = "???+" if is_first_rendered else "???"
        is_first_rendered = False
        block_lines = [f'{marker} "{title}"', "", *_indent(content)]
        rendered_blocks.append("\n".join(block_lines).rstrip())

    preamble_text_lines = list(preamble)
    while preamble_text_lines and preamble_text_lines[-1].strip() == "":
        preamble_text_lines.pop()

    parts: list[str] = []
    if preamble_text_lines:
        parts.append("\n".join(preamble_text_lines))
    parts.extend(rendered_blocks)
    if link_ref_lines:
        parts.append("\n".join(link_ref_lines))

    return "\n\n".join(parts) + "\n"


def on_page_markdown(markdown, page, **kwargs):
    if page.file.src_uri != "changelog.md":
        return markdown
    # Every `###` subsection inside a release becomes a details block's own
    # heading, so the right-hand table of contents would otherwise be a flat,
    # unlabelled list of a dozen "Added / Changed / ..." entries repeated
    # once per release — the collapsible blocks' own summary lines already do
    # that job. Hide it only for this page, without touching page-meta.yml.
    hide = page.meta.setdefault("hide", [])
    if "toc" not in hide:
        hide.append("toc")
    rendered = render_changelog(markdown)
    RENDERED_PATH.write_text(rendered, encoding="utf-8")
    return rendered
