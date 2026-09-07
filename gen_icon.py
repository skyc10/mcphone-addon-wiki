# -*- coding: utf-8 -*-
"""Generate the wiki app icon (128x128, gradient rounded bg + open book + magnifier glyph)."""
from PIL import Image, ImageDraw
import os

S = 512
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "src/main/resources/assets/mcphone_wiki/textures/ui")
os.makedirs(OUT, exist_ok=True)


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGBA", size)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        d.line([(0, y), (w, y)], fill=c)
    return img


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def save(img, name):
    img = img.resize((128, 128), Image.LANCZOS)
    img.save(os.path.join(OUT, name))
    print("wrote", name)


W = (255, 255, 255, 255)
W2 = (255, 255, 255, 200)
INK = (30, 92, 76, 255)  # 深绿描边

# ---------- wiki：打开的书 + 放大镜 ----------
img = vertical_gradient((S, S), (46, 139, 116), (24, 88, 72))
img.putalpha(rounded_mask((S, S), int(S * 0.22)))
d = ImageDraw.Draw(img)

# 打开的书（两页）
d.polygon([(96, 340), (96, 180), (256, 220), (256, 380)], fill=W)
d.polygon([(416, 340), (416, 180), (256, 220), (256, 380)], fill=(235, 246, 242, 255))
# 书脊与页缘
d.line([(96, 180), (256, 220)], fill=INK, width=14)
d.line([(416, 180), (256, 220)], fill=INK, width=14)
d.line([(96, 340), (256, 380)], fill=INK, width=14)
d.line([(416, 340), (256, 380)], fill=INK, width=14)
d.line([(256, 220), (256, 380)], fill=INK, width=14)
# 文本行（左页）
for y in (250, 285, 320):
    d.line([(126, y), (226, y + 9)], fill=INK, width=12)
# 文本行（右页）
for y in (259, 294, 329):
    d.line([(286, y + 9), (386, y)], fill=INK, width=12)

# 放大镜（右上）
cx, cy, r = 386, 150, 62
d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=W, width=22)
d.line([(cx + r * 0.72, cy + r * 0.72), (cx + r * 0.72 + 78, cy + r * 0.72 + 78)], fill=W, width=34)

save(img, "app_wiki.png")

print("wiki icon done ->", OUT)
