#!/usr/bin/env python3
"""Render the Gapmin launcher icon + splash (neon route on near-black) for Android."""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

RES = Path(__file__).resolve().parent.parent / "web/android/app/src/main/res"
BG = (6, 7, 8, 255)
VOLT = (212, 255, 58, 255)


def route(size, scale=1.0):
    """A smooth closed loop like a park run, normalised to the icon box."""
    pts = []
    for i in range(361):
        a = math.radians(i)
        r = 1 + 0.18 * math.sin(3 * a + 0.6) + 0.08 * math.sin(5 * a)
        x, y = r * math.cos(a) * 1.05, r * math.sin(a) * 0.82
        pts.append((size / 2 + x * size * 0.26 * scale, size / 2 + y * size * 0.26 * scale))
    return pts


def draw(size, scale=1.0, bg=True):
    s = size * 4  # supersample
    img = Image.new("RGBA", (s, s), BG if bg else (0, 0, 0, 0))
    glow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    pts = route(s, scale)
    w = max(2, int(s * 0.035))
    ImageDraw.Draw(glow).line(pts, fill=VOLT[:3] + (150,), width=w * 3, joint="curve")
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.03))
    img.alpha_composite(glow)
    d = ImageDraw.Draw(img)
    d.line(pts, fill=VOLT, width=w, joint="curve")
    x, y = pts[0]
    r = w * 1.3
    d.ellipse((x - r, y - r, x + r, y + r), fill=VOLT)
    return img.resize((size, size), Image.LANCZOS)


dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, f in dens.items():
    d = RES / f"mipmap-{name}"
    draw(int(48 * f)).save(d / "ic_launcher.png")
    # round legacy icon: circular mask
    ic = draw(int(48 * f))
    mask = Image.new("L", ic.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, ic.size[0] - 1, ic.size[1] - 1), fill=255)
    rnd = Image.new("RGBA", ic.size, (0, 0, 0, 0))
    rnd.paste(ic, mask=mask)
    rnd.save(d / "ic_launcher_round.png")
    # adaptive foreground: 108dp canvas, content inside the 66dp safe zone
    draw(int(108 * f), scale=0.62, bg=False).save(d / "ic_launcher_foreground.png")

(RES / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#060708</color>\n</resources>\n'
)
for p in RES.glob("drawable*/splash.png"):
    w, h = Image.open(p).size
    img = Image.new("RGBA", (w, h), BG)
    logo = draw(int(min(w, h) * 0.32), bg=False)
    img.alpha_composite(logo, ((w - logo.width) // 2, (h - logo.height) // 2))
    img.convert("RGB").save(p)
print("icons written")
