# Contributing

The walking skeleton and every slice through S9a are built — 12 Maven modules,
a passing test suite, and a working end-to-end path from an MCP call to a
pseudonymised response. See `README.md` for what exists and `docs/plan/PLAN.md`
for what is open. Review of `docs/design-review.md` and `docs/architecture.md`
— particularly anywhere the reasoning is wrong rather than merely different —
is still useful, alongside code contributions against the open plan.

## Before a pull request

Read `docs/conventions.md`. Two of its sections carry more weight than the rest:

- **Privacy rules a diff must satisfy.** These are enforced literally, and a pull
  request that breaks one is rejected regardless of what else it does. Most are
  mechanical — no second `ObjectMapper` below the MCP layer, no source DTO in an
  MCP signature, no `Random` or `Instant.now()` in a pseudonymisation path, no
  value from a source payload interpolated into a log line.
- **Acceptance-criteria discipline.** Every change states what would have to be
  observed for it to be wrong.

Read `docs/architecture.md` for the module dependency rules. They are enforced by
ArchUnit, so a violation fails the build rather than review.

## Test data

**Never use real personal data**, in a fixture, a test, a doc or a commit
message. Test data is obviously synthetic. Leak-detection fixtures use documented
*invalid* check digits — a valid IBAN or national identifier in a test file is
itself the leak the project exists to prevent.

Leak tests assert on absence, which is easy to write vacuously. Every one is
accompanied by a mutation proving it fails when the protection is removed.

## Reporting a security issue

See `SECURITY.md`.

## Development setup

Work is split and run with an external agent kit; it is not vendored here, and
`.claude/` is a plain, untracked, gitignored directory populated by installing
the kit — not a symlink. Committing it would fork this copy from the kit's
upstream, and committing its `settings.json` would hand you a tool-permission
allowlist you never reviewed. Install it yourself and it stays current:

```bash
git clone <kit-repo> ~/.claude-kit
~/.claude-kit/install.sh .
```

On Windows, `install.ps1 -Target .`. Note that the installer's PowerShell script
must be saved as UTF-8 **with** a BOM, or Windows PowerShell 5.1 decodes its
em-dashes as CP1252 and the script will not parse.

None of this is required to contribute. It is how the maintainers split work; a
plain clone, an editor and Maven are enough.

## Docs site

`https://aindriub.github.io/data-prism/` is built with MkDocs Material from
the existing docs under `docs/` (plus `README.md` and `CHANGELOG.md`,
pulled into `docs/index.md` and `docs/changelog.md`). Build it locally:

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r docs-site/requirements.txt
mkdocs serve            # live-reloading local preview
mkdocs build --strict   # what CI runs; fails on any broken link or
                         # missing/duplicate/over-length page description
```

If your `python3` has no `pip` (or you want the exact toolchain CI uses),
build in Docker instead, as your own user so nothing it writes is
root-owned:

```bash
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD:/w" -w /w python:3.12 sh -c '
  python -m venv /tmp/v &&
  /tmp/v/bin/pip -q install -r docs-site/requirements.txt &&
  /tmp/v/bin/mkdocs build --strict &&
  for f in docs-site/hooks/check_*.py; do /tmp/v/bin/python "$f" || exit 1; done
'
```

Rules for adding or changing a page:

- **Existing docs get no front matter.** `docs/extending.md` and `PLAN.md`
  cite line numbers inside these files, and front matter would shift them.
  Give the page a `title` and `description` in `docs-site/page-meta.yml`
  instead — `docs-site/hooks/site.py` injects them at build time, and fails
  the build, naming the page, if the entry is missing, its description is
  reused elsewhere, or it is over 155 characters.
- **New pages carry real front matter** (`title` and `description`, the
  latter unique and at most 155 characters) — see `docs/faq.md` or
  `docs/comparison.md`.
- **Excluded from the site entirely:** anything under `docs/plan/` or
  `docs/adr/`, and `docs/pack.md`, `docs/conventions.md`, `docs/workflow.md`,
  `docs/development-plan.md` and `docs/design-review.md` (`mkdocs.yml`'s
  `exclude_docs`). `docs/pack.md` in particular is the superseded original
  spec; keeping it off the site stops an assistant citing it as current
  behaviour.
- **Tutorial snippets are never hand-copied.** Anything presented as example
  code in the developer guide comes from a marked region (`// --8<--
  [start:name]` / `[end:name]`) in a real, compiled and tested source file
  under `data-prism-quickstart-extension/` or `docker/`, pulled in with
  `pymdownx.snippets`'s `--8<--` syntax. `check_paths: true` fails the build
  if the file or section doesn't exist, so a snippet going stale when the
  source changes is caught, not silently left wrong.
- **`docs-site/hooks/check_*.py` is a convention, not a fixed list.** Every
  file matching that glob runs, in sorted order, both in CI
  (`.github/workflows/pages.yml`) and by the tester, against a built `site/`
  directory. Add a new guard by adding a new `check_*.py` module; nothing
  else needs editing to wire it in.
- **`DP_REQUIRE_NO_STUBS`** is an env var, set true in `pages.yml` only for a
  pull request into `main` or a push to `main`, that makes
  `check_no_stub_pages.py` fail the build if a placeholder stub page is
  still live, so wave branches can keep building their stubs unblocked.

## Licence

Contributions are accepted under the Apache License 2.0. By opening a pull
request you agree your contribution is licensed under it.
