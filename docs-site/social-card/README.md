# Social card

Generates `docs/assets/social-card.png`, a 1280x640 PNG carrying the project
tagline. It is used as the site's `og:image` and Twitter card, and can be
uploaded by the repo owner as the GitHub social preview image.

The card is drawn with Pillow's bundled scalable default font
(`ImageFont.load_default(size=...)`), so no font file is committed here.

## Regenerate

```sh
python3 -m venv .venv
.venv/bin/pip install -r docs-site/social-card/requirements.txt
.venv/bin/python3 docs-site/social-card/make_card.py
```

Run from the repository root. The Pillow version is pinned in
`requirements.txt`, so a fresh venv reproduces the committed PNG byte for
byte.
