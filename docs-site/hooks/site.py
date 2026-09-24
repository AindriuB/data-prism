"""MkDocs build hook for the Data Prism docs site.

Four jobs, kept in one module because they share the same page-meta lookup
and all run inside the same `mkdocs build`:

1. Inject title/description "front matter" for existing user docs that carry
   none on disk (`docs/extending.md` and PLAN.md cite line numbers inside
   these files, so real front matter would shift them). The metadata comes
   from `page-meta.yml`, sitting next to this file. A missing entry, a
   description over 155 characters, or a description reused by two pages is
   a build failure that names the page, not a warning.
2. Rewrite Markdown links that leave `docs_dir` (or land on an excluded
   path) to the file's `blob` URL, or the directory's `tree` URL, on
   GitHub `main` (a raw-content URL for an image), since mkdocs will not
   serve those paths. Raises if the resolved target does not exist in the
   repo, naming the page and the link, rather than emitting a link to a
   GitHub 404.
3. Register a `tojson` Jinja filter for the JSON-LD block in
   `docs-site/overrides/main.html`.
4. Record, to a manifest file that `docs-site/hooks/check_site.py` and the
   tester both read:
   - every built page's canonical URL, title and description, since neither
     is otherwise able to see what mkdocs decided a page's title actually is
     (an explicit nav label wins over front matter, front matter wins over
     the first Markdown heading);
   - the canonical URL of every page actually reachable from the `nav:`
     config (`on_nav`, from `nav.pages`), which is *not* the same set as
     "every built page" above — mkdocs builds every non-excluded doc
     whether or not it's in the nav, and the sitemap is built from that
     same "every built page" set, so comparing the sitemap against the
     first list can never fail even when a page is missing from the nav.
     Comparing it against this second list can.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import yaml
from markupsafe import Markup
from mkdocs.exceptions import PluginError
from mkdocs.structure.pages import Page
from mkdocs.utils import meta as mkdocs_meta

HOOKS_DIR = Path(__file__).resolve().parent
DOCS_SITE_DIR = HOOKS_DIR.parent
REPO_ROOT = DOCS_SITE_DIR.parent
MANIFEST_PATH = DOCS_SITE_DIR / ".manifest.json"
MAX_DESCRIPTION_LENGTH = 155
GITHUB_REPO = "AindriuB/data-prism"

# Same list as `mkdocs.yml`'s `exclude_docs`, needed here too because the
# hook resolves links against the filesystem, not against mkdocs' already
# filtered `Files` collection.
EXCLUDED_PREFIXES = (
    "plan/",
    "adr/",
    "pack.md",
    "conventions.md",
    "workflow.md",
    "development-plan.md",
    "design-review.md",
)

_page_meta: dict[str, dict[str, str]] | None = None
_manifest: list[dict[str, str]] = []
_nav_urls: list[str] = []


def _load_page_meta() -> dict[str, dict[str, str]]:
    global _page_meta
    if _page_meta is None:
        with open(DOCS_SITE_DIR / "page-meta.yml", encoding="utf-8") as fh:
            _page_meta = yaml.safe_load(fh) or {}
    return _page_meta


def _validate_page_meta(docs_dir: Path) -> None:
    """Fail the build, naming the page, on a missing, duplicate or
    over-length description — checked against every Markdown file that
    will actually be published, not just the entries in page-meta.yml, so
    deleting an entry is caught here rather than surfacing as a mkdocs
    'file not found' error somewhere else."""
    page_meta = _load_page_meta()
    seen_descriptions: dict[str, str] = {}

    def note(src_uri: str, description: str | None) -> None:
        if not description:
            raise PluginError(f"docs-site: page '{src_uri}' has no description")
        if len(description) > MAX_DESCRIPTION_LENGTH:
            raise PluginError(
                f"docs-site: page '{src_uri}' has a {len(description)}-character "
                f"description, over the {MAX_DESCRIPTION_LENGTH}-character limit"
            )
        if description in seen_descriptions:
            raise PluginError(
                f"docs-site: pages '{seen_descriptions[description]}' and "
                f"'{src_uri}' share one description"
            )
        seen_descriptions[description] = src_uri

    for src_uri, entry in page_meta.items():
        note(src_uri, entry.get("description"))
        if not entry.get("title"):
            raise PluginError(f"docs-site: page-meta.yml entry '{src_uri}' has no title")

    for path in sorted(docs_dir.rglob("*.md")):
        src_uri = path.relative_to(docs_dir).as_posix()
        if _is_excluded(src_uri):
            continue
        if src_uri in page_meta:
            continue
        raw = path.read_text(encoding="utf-8")
        _, front_matter = mkdocs_meta.get_data(raw)
        if not front_matter:
            raise PluginError(
                f"docs-site: '{src_uri}' has no front matter and no "
                "page-meta.yml entry — add one or the other"
            )
        note(src_uri, front_matter.get("description"))


def _is_excluded(src_uri: str) -> bool:
    return any(
        src_uri == prefix or src_uri.startswith(prefix) for prefix in EXCLUDED_PREFIXES
    )


def on_config(config, **kwargs):
    _validate_page_meta(Path(config["docs_dir"]))
    return config


def on_page_read_source(page: Page, config, **kwargs):
    """Supply synthesized front matter for pages listed in page-meta.yml
    that have none of their own on disk."""
    page_meta = _load_page_meta()
    entry = page_meta.get(page.file.src_uri)
    if entry is None:
        return None  # let mkdocs read the file itself, unmodified

    raw = Path(page.file.abs_src_path).read_text(encoding="utf-8")
    _, front_matter = mkdocs_meta.get_data(raw)
    if front_matter:
        # Already has its own front matter (shouldn't happen for anything
        # listed in page-meta.yml, but never silently override real
        # front matter).
        return None

    synthesized = "---\n" + yaml.safe_dump(
        {"title": entry["title"], "description": entry["description"]},
        sort_keys=False,
        allow_unicode=True,
    ) + "---\n"
    return synthesized + raw


_LINK_RE = re.compile(r"(!?\[[^\]]*\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")


def _rewrite_target(target: str, is_image: bool, page_src_uri: str, page_dir: Path, docs_dir: Path) -> str | None:
    if target.startswith(("http://", "https://", "mailto:", "#")):
        return None
    path_part, sep, fragment = target.partition("#")
    if not path_part:
        return None
    resolved = (page_dir / path_part).resolve()
    try:
        rel_to_docs = resolved.relative_to(docs_dir.resolve())
    except ValueError:
        rel_to_docs = None

    outside_docs = rel_to_docs is None
    excluded = rel_to_docs is not None and _is_excluded(rel_to_docs.as_posix())
    if not outside_docs and not excluded:
        return None

    try:
        rel_to_repo = resolved.relative_to(REPO_ROOT.resolve())
    except ValueError:
        return None  # points outside the repo entirely; leave it alone

    # A typo here would otherwise silently become a link to a GitHub 404 —
    # the build only ever validates links that stay inside docs_dir, so
    # this is the one place anything checks a link that leaves it.
    if not resolved.exists():
        raise PluginError(
            f"docs-site: '{page_src_uri}' links '{target}', which does not "
            f"exist in the repo (resolved to {rel_to_repo.as_posix()})"
        )

    if is_image:
        # blob/tree URLs serve GitHub's HTML wrapper page, not the image
        # bytes an `<img>` tag needs — raw.githubusercontent.com does.
        url = f"https://raw.githubusercontent.com/{GITHUB_REPO}/main/{rel_to_repo.as_posix()}"
    else:
        kind = "tree" if resolved.is_dir() else "blob"
        url = f"https://github.com/{GITHUB_REPO}/{kind}/main/{rel_to_repo.as_posix()}"
    if sep:
        url += f"#{fragment}"
    return url


def on_page_markdown(markdown: str, page: Page, config, **kwargs):
    docs_dir = Path(config["docs_dir"])
    page_dir = Path(page.file.abs_src_path).parent

    def replace(match: re.Match) -> str:
        prefix, target, suffix = match.group(1), match.group(2), match.group(3)
        is_image = prefix.startswith("!")
        rewritten = _rewrite_target(target, is_image, page.file.src_uri, page_dir, docs_dir)
        if rewritten is None:
            return match.group(0)
        return f"{prefix}{rewritten}{suffix}"

    return _LINK_RE.sub(replace, markdown)


def on_env(env, config, **kwargs):
    env.filters["tojson"] = lambda value: Markup(json.dumps(value))
    return env


def on_nav(nav, config, **kwargs):
    """The set of pages actually reachable from `nav:` in mkdocs.yml — not
    the same as "every page mkdocs builds" (see module docstring)."""
    global _nav_urls
    _nav_urls = [page.canonical_url for page in nav.pages]
    return nav


def on_post_page(output: str, page: Page, config, **kwargs):
    _manifest.append(
        {
            "url": page.canonical_url,
            "title": page.title,
            "description": (page.meta or {}).get("description"),
        }
    )
    return output


def on_post_build(config, **kwargs):
    MANIFEST_PATH.write_text(
        json.dumps({"pages": _manifest, "nav_urls": _nav_urls}, indent=2, sort_keys=True) + "\n"
    )
    _manifest.clear()
