"""MkDocs build hook that collapses the rendered changelog page.

Currently a no-op stub. Task 87 owns this file and replaces it with the
real `on_page_markdown` transform that turns each `CHANGELOG.md` release
into a collapsible block.
"""

from __future__ import annotations


def on_page_markdown(markdown, **kwargs):
    return markdown
