#!/usr/bin/env python3
"""Generate the Data Prism social card.

The card is used as the site's og:image and Twitter card, and as the
repo's social preview image. It is a fixed 1280x640 PNG carrying the
project tagline, generated from a pinned Pillow version so the output is
reproducible byte-for-byte.

Regenerate with:
    python3 -m venv .venv
    .venv/bin/pip install -r requirements.txt
    .venv/bin/python3 make_card.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH = 1280
HEIGHT = 640

SCRIPT_DIR = Path(__file__).resolve().parent
OUTPUT = SCRIPT_DIR.parent.parent / "docs" / "assets" / "social-card.png"

BACKGROUND = (13, 17, 23)
FOREGROUND = (230, 236, 241)
ACCENT = (88, 166, 255)

TITLE = "Data Prism"
TAGLINE = "Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients."

MARGIN = 96


def wrap_text(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    """Word-wrap text to fit max_width, never splitting a word."""
    words = text.split(" ")
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if draw.textlength(candidate, font=font) <= max_width or not current:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def main() -> None:
    image = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw = ImageDraw.Draw(image)

    max_text_width = WIDTH - 2 * MARGIN

    title_font = ImageFont.load_default(size=72)
    tagline_font = ImageFont.load_default(size=44)

    draw.text((MARGIN, 120), TITLE, font=title_font, fill=ACCENT)

    tagline_lines = wrap_text(draw, TAGLINE, tagline_font, max_text_width)
    line_height = 62
    tagline_top = 280
    for index, line in enumerate(tagline_lines):
        draw.text((MARGIN, tagline_top + index * line_height), line, font=tagline_font, fill=FOREGROUND)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT, format="PNG")
    print(f"wrote {OUTPUT} ({image.size[0]}x{image.size[1]})")


if __name__ == "__main__":
    main()
