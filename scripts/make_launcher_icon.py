#!/usr/bin/env python3
"""Build the adaptive launcher icon's foreground from art/icon-source-2048.jpg.

The source is a flat square: brand green background + white bus glyph (with a tap hand
that runs off the bottom edge). The launcher icon is that same art split into layers:
  background = solid #2E7D32 (drawable/ic_launcher_bg.xml)
  foreground = the white glyph on transparency (drawable-nodpi/ic_launcher_fg.png, written here)

Adaptive icons are 108dp with the visible mask covering roughly the centre 72dp, so the
square art is scaled to that 72dp and centred. The hand's wrist is extended straight down
to the canvas edge so no launcher mask ever shows it cut off.

    python3 scripts/make_launcher_icon.py        # needs Pillow
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "icon-source-2048.jpg"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "ic_launcher_fg.png"
CANVAS = 432                    # 108dp at xxxhdpi (4 px/dp)
ART = CANVAS * 72 // 108        # 288 px = the visible 72dp
OFF = (CANVAS - ART) // 2

src = Image.open(SRC).convert("RGB")
bg_r = src.getpixel((8, 8))[0]  # background red channel (~50); white glyph is 255
art = src.resize((ART, ART), Image.LANCZOS)

# alpha = how white each pixel is (red channel separates green bg from white best)
alpha = art.getchannel("R").point(lambda r: 0 if r <= bg_r + 8 else min(255, round((r - bg_r) * 255 / (255 - bg_r))))
glyph = Image.new("RGBA", (ART, ART), (255, 255, 255, 0))
glyph.putalpha(alpha)

fg = Image.new("RGBA", (CANVAS, CANVAS), (255, 255, 255, 0))
fg.paste(glyph, (OFF, OFF))
# extend the wrist: repeat the art's bottom row of alpha down to the canvas edge
row = alpha.crop((0, ART - 1, ART, ART))
for y in range(OFF + ART, CANVAS):
    fg.paste(Image.new("RGBA", (ART, 1), (255, 255, 255, 255)), (OFF, y), row)

OUT.parent.mkdir(parents=True, exist_ok=True)
fg.save(OUT, optimize=True)
print("wrote", OUT, fg.size)
