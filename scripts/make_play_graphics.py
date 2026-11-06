#!/usr/bin/env python3
"""Render the Play Store icon (512×512) and feature graphic (1024×500) into play/.
Same look as the launcher icon: brand green + the white Material directions_bus glyph (Apache 2.0)."""
import re
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFont

GREEN, WHITE = (0x2E, 0x7D, 0x32), (255, 255, 255)
BUS = ("M4,16c0,0.88 0.39,1.67 1,2.22V20c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h8v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1"
       "v-1.78c0.61,-0.55 1,-1.34 1,-2.22V6c0,-3.5 -3.58,-4 -8,-4s-8,0.5 -8,4v10zM7.5,17c-0.83,0 -1.5,-0.67 -1.5,-1.5S6.67,14 7.5,14"
       "s1.5,0.67 1.5,1.5S8.33,17 7.5,17zM16.5,17c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"
       "M18,11H6V6h12v5z")
OUT = Path(__file__).resolve().parent.parent / "play"
SS = 4  # supersample for smooth edges
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans{}.ttf"


def subpaths(d):
    """Minimal SVG path → list of point lists (M/L/H/V/C/S/Z, abs+rel; enough for this glyph)."""
    toks = re.findall(r"[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?", d)
    out, pts, i, cmd = [], [], 0, None
    x = y = sx = sy = 0.0
    last_c = None
    num = lambda: float(toks[i])

    def cubic(p0, p1, p2, p3):
        for k in range(1, 17):
            t = k / 16
            pts.append(tuple((1-t)**3*a + 3*(1-t)**2*t*b + 3*(1-t)*t**2*c + t**3*e for a, b, c, e in zip(p0, p1, p2, p3)))

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]; i += 1
            if cmd in "Zz":
                out.append(pts); pts = []; x, y = sx, sy; last_c = None
                continue
        rel = cmd.islower(); C = cmd.upper()
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if C == "M":
            if pts: out.append(pts)
            x, y = ox + float(toks[i]), oy + float(toks[i+1]); i += 2
            sx, sy, pts = x, y, [(x, y)]; cmd = "l" if rel else "L"; last_c = None
        elif C == "L":
            x, y = ox + float(toks[i]), oy + float(toks[i+1]); i += 2; pts.append((x, y)); last_c = None
        elif C == "H":
            x = (x if rel else 0) + float(toks[i]); i += 1; pts.append((x, y)); last_c = None
        elif C == "V":
            y = (y if rel else 0) + float(toks[i]); i += 1; pts.append((x, y)); last_c = None
        elif C == "C":
            v = [float(t) for t in toks[i:i+6]]; i += 6
            p1, p2, p3 = (ox+v[0], oy+v[1]), (ox+v[2], oy+v[3]), (ox+v[4], oy+v[5])
            cubic((x, y), p1, p2, p3); last_c = p2; x, y = p3
        elif C == "S":
            v = [float(t) for t in toks[i:i+4]]; i += 4
            p1 = (2*x - last_c[0], 2*y - last_c[1]) if last_c else (x, y)
            p2, p3 = (ox+v[0], oy+v[1]), (ox+v[2], oy+v[3])
            cubic((x, y), p1, p2, p3); last_c = p2; x, y = p3
        else:
            raise ValueError(cmd)
    if pts: out.append(pts)
    return out


def bus_mask(size, scale, ox, oy):
    """Even-odd fill: each subpath XORed in, so the windows and lights are holes."""
    W, H = size[0]*SS, size[1]*SS
    m = Image.new("L", (W, H), 0)
    for sp in subpaths(BUS):
        layer = Image.new("L", (W, H), 0)
        ImageDraw.Draw(layer).polygon([((ox + px*scale)*SS, (oy + py*scale)*SS) for px, py in sp], fill=255)
        m = ImageChops.logical_xor(m.convert("1"), layer.convert("1")).convert("L")
    return m


def canvas(size, scale, ox, oy):
    img = Image.new("RGB", (size[0]*SS, size[1]*SS), GREEN)
    img.paste(WHITE, mask=bus_mask(size, scale, ox, oy))
    return img


def glyph_box(scale):  # ink of the glyph spans x 4..20, y 2..21 in its 24-unit viewport
    return 16*scale, 19*scale


# 512 icon: full-bleed square (Play rounds the corners itself), glyph ~41% of the height like the PM2.5 icon's dots
s = 11.0
gw, gh = glyph_box(s)
icon = canvas((512, 512), s, (512-gw)/2 - 4*s, (512-gh)/2 - 2*s).resize((512, 512), Image.LANCZOS)
icon.save(OUT / "icon-512.png")

# 1024×500 feature graphic: glyph left, title + tagline right (same layout as sg-pm25-widget)
s = 11.0
gw, gh = glyph_box(s)
fg = canvas((1024, 500), s, 250 - gw/2 - 4*s, 250 - gh/2 - 2*s)
d = ImageDraw.Draw(fg)
d.text((470*SS, 160*SS), "SG Bus", font=ImageFont.truetype(FONT.format("-Bold"), 84*SS), fill=WHITE)
tag = ImageFont.truetype(FONT.format(""), 32*SS)
for n, line in enumerate(["Live arrivals for your nearest", "stop — one tap on your", "home screen"]):
    d.text((474*SS, (275 + n*48)*SS), line, font=tag, fill=WHITE)
fg.resize((1024, 500), Image.LANCZOS).save(OUT / "feature-graphic-1024x500.png")
print("wrote", OUT / "icon-512.png", OUT / "feature-graphic-1024x500.png")
