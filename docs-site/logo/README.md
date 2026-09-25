# Data Prism logo

## Provenance

`supplied/` is a byte-identical copy of the owner-supplied logo delivery
from 2026-09-24 (`/srv/dev/scratch/data-prism-logo/` at the time): the
`mark-{light,dark}.svg` / `.png` marks, the `wordmark-{light,dark}.svg` /
`.png` lockups, the `favicon-{light,dark}-{16,32}.png` comparison exports,
`preview.svg` / `preview.png`, and the owner's own `README.md` describing
the geometry. See `supplied/README.md` for the design rationale.

**Do not edit anything under `supplied/`.** It is a copy of record, not a
working file — `diff -r <the original delivery> supplied/` must stay empty.
If the mark ever needs to change, get a new delivery from the owner and
replace the whole directory, rather than hand-editing a path here.

The site uses both marks, copied byte-for-byte, not redrawn:
`supplied/mark-dark.svg` (light strokes) as `docs/assets/logo.svg`, for the
dark header in the slate scheme, and `supplied/mark-light.svg` (dark
strokes) as `docs/assets/logo-light.svg`, for the white header in the
light scheme. `docs-site/overrides/partials/logo.html` renders both and
`docs/stylesheets/extra.css` hides whichever doesn't match the scheme. The wordmarks are not used on the site: the
theme already renders "Data Prism" as live text beside the logo.

## Regenerating the favicon

`docs/assets/favicon.svg` and `docs/assets/favicon.png` are *not* copies of
anything under `supplied/`: they're a simplified derivative (dropped
incoming ray, two thicker bars instead of three thin ones, a
`prefers-color-scheme` stroke switch, since a favicon has no access to the
page's own light/dark toggle), built by `make_favicon.py` from the same
prism-outline and side-face geometry.

```sh
python3 -m venv .venv
.venv/bin/pip install -r docs-site/logo/requirements.txt
.venv/bin/python3 docs-site/logo/make_favicon.py
```

Run from the repository root. Pillow is pinned in `requirements.txt`, so a
fresh venv reproduces both committed files byte for byte — running the
script twice leaves `git status --porcelain docs/assets/` empty. The PNG is
rendered directly from the same point data as the SVG (every shape here is
a straight-edged polygon, no curves), supersampled and downsampled with
Pillow, rather than by feeding the SVG through a separate rasteriser.
