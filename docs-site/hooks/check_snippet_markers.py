#!/usr/bin/env python3
"""Fails the build if a `--8<--` section marker is missing, duplicated or
out of order.

`pymdownx.snippets` 12.1's own extraction (`SnippetPreprocessor.extract_section`
in `pymdownx/snippets.py`) reads to end of file when a `[end:x]` marker is
missing, rather than failing: it just keeps appending lines until either a
real `[end:x]` turns up somewhere later in the file or the file runs out,
silently pulling unrelated trailing content into the snippet. `mkdocs build
--strict` never catches this — the include still resolves to *something*.
This guard reads the same files pymdownx.snippets would, using its own
section regex (`RE_SNIPPET_SECTION`, copied below so both the `--8<--` and
`-8<-` forms count, since XML comments cannot contain a literal `--`), and
walks every `[start:x]` / `[end:x]` pair itself, failing on:

- a `[start:x]` with no `[end:x]` before the file ends ("missing end"),
- an `[end:x]` with no `[start:x]` open for it, whether because `x` was
  never opened at all ("out-of-order end") or was already closed by an
  earlier `[end:x]` ("duplicate end"),
- a second `[start:x]` before the first one's `[end:x]` ("unmatched start" —
  pymdownx.snippets ignores the repeat rather than failing, so this guard
  is what stops it landing).

Run after (or independently of) `mkdocs build --strict`, from the repo root:

    python3 docs-site/hooks/check_snippet_markers.py

To prove this guard actually fails on a planted fault, point it at a scratch
copy of the tree instead of the real one:

    python3 docs-site/hooks/check_snippet_markers.py --repo-root /tmp/scratch

`base_path` itself is always read from this repository's own `mkdocs.yml`
(`--mkdocs-config` to point at a different one), since a scratch copy's own
`mkdocs.yml` is the same file, just relocated under `--repo-root`.

Exits non-zero, naming the file and section, on the first section that
fails.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MKDOCS_CONFIG = REPO_ROOT / "mkdocs.yml"

# Copied verbatim from pymdownx.snippets.SnippetPreprocessor.RE_SNIPPET_SECTION
# (pymdown-extensions==12.1) so both `--8<--` and `-8<-` count, exactly as
# pymdownx.snippets itself would recognise them.
RE_SNIPPET_SECTION = re.compile(
    r'''(?xi)
    ^(?P<pre>.*?)
    (?P<escape>;*)
    (?P<inline_marker>-{1,}8<-{1,}[ \t]+)
    (?P<section>\[[ \t]*(?P<type>start|end)[ \t]*:[ \t]*(?P<name>[a-z][-_0-9a-z]*)[ \t]*\])
    (?P<post>.*?)$
    '''
)


class CheckError(Exception):
    pass


def fail(message: str) -> None:
    raise CheckError(message)


def base_paths(mkdocs_config: Path) -> list[Path]:
    config = yaml.safe_load(mkdocs_config.read_text(encoding="utf-8"))
    for entry in config.get("markdown_extensions", []):
        if isinstance(entry, dict) and "pymdownx.snippets" in entry:
            base = (entry["pymdownx.snippets"] or {}).get("base_path", [])
            if isinstance(base, str):
                base = [base]
            root = mkdocs_config.parent
            return [root / b for b in base]
    fail(f"no pymdownx.snippets entry with a base_path found in {mkdocs_config}")
    return []  # unreachable, keeps type-checkers happy


def iter_files(base: Path):
    if base.is_file():
        yield base
        return
    for path in sorted(base.rglob("*")):
        if path.is_file():
            yield path


def check_file(path: Path) -> None:
    # State per section name: 'open' (a [start:name] is waiting for its
    # [end:name]) or 'closed' (its [end:name] has already been seen). A name
    # absent from this dict has never been opened.
    state: dict[str, str] = {}
    start_line: dict[str, int] = {}

    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (UnicodeDecodeError, OSError):
        return

    for lineno, line in enumerate(lines, start=1):
        match = RE_SNIPPET_SECTION.match(line)
        if match is None:
            continue
        name = match.group("name")
        kind = match.group("type")

        if kind == "start":
            if state.get(name) == "open":
                fail(
                    f"{path}: unmatched [start:{name}] at line {lineno} — "
                    f"the [start:{name}] at line {start_line[name]} is not yet "
                    f"closed by an [end:{name}]"
                )
            state[name] = "open"
            start_line[name] = lineno
        else:  # kind == "end"
            if name not in state:
                fail(
                    f"{path}: out-of-order [end:{name}] at line {lineno} — "
                    f"no [start:{name}] precedes it"
                )
            if state[name] == "closed":
                fail(
                    f"{path}: duplicate [end:{name}] at line {lineno} — "
                    f"[start:{name}] at line {start_line[name]} was already "
                    f"closed"
                )
            state[name] = "closed"

    still_open = sorted(name for name, status in state.items() if status == "open")
    if still_open:
        for name in still_open:
            fail(
                f"{path}: missing [end:{name}] for [start:{name}] at line "
                f"{start_line[name]} — pymdownx.snippets would silently read "
                f"to end of file instead of failing"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--mkdocs-config", type=Path, default=None)
    args = parser.parse_args()

    mkdocs_config = args.mkdocs_config or (args.repo_root / "mkdocs.yml")
    if not mkdocs_config.exists():
        print(f"FAIL [snippet markers]: {mkdocs_config} not found", file=sys.stderr)
        return 1

    name = "snippet section markers well-formed"
    try:
        for base in base_paths(mkdocs_config):
            for path in iter_files(base):
                check_file(path)
    except CheckError as exc:
        print(f"FAIL [{name}]: {exc}", file=sys.stderr)
        return 1

    print(f"OK   [{name}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
