#!/usr/bin/env python3
"""Fails the build if a placeholder stub page is still live.

Only checks anything when `DP_REQUIRE_NO_STUBS` (set by
`.github/workflows/pages.yml` for a pull request into `main`, or a push to
`main`) is truthy. Wave branches build and preview their stub pages freely;
this only stops one reaching `main` unfinished. See CONTRIBUTING.md's
"## Docs site" section.

Run after `mkdocs build --strict` from the repo root:

    DP_REQUIRE_NO_STUBS=true python3 docs-site/hooks/check_no_stub_pages.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
SITE_DIR = REPO_ROOT / "site"
STUB_BODY = "This page is being written."


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in ("1", "true", "yes")


def main() -> int:
    name = "no stub pages"
    if not _truthy(os.environ.get("DP_REQUIRE_NO_STUBS")):
        print(f"SKIP [{name}]: DP_REQUIRE_NO_STUBS is unset/false")
        return 0

    if not SITE_DIR.exists():
        print(f"FAIL [{name}]: {SITE_DIR} does not exist — run `mkdocs build --strict` first", file=sys.stderr)
        return 1

    offenders = [
        path.relative_to(SITE_DIR).as_posix()
        for path in sorted(SITE_DIR.rglob("*.html"))
        if STUB_BODY in path.read_text(encoding="utf-8")
    ]
    if offenders:
        print(f"FAIL [{name}]: stub body {STUB_BODY!r} still live on: {offenders}", file=sys.stderr)
        return 1

    print(f"OK   [{name}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
