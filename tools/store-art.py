# The store icon (512x512) and Voxel Shop header (2100x280) for Punishments,
# in the style of Dialog Engine's: a pixel-art mark on the brand colour.
#   python tools/store-art.py <out dir>
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1]
RED = (216, 64, 47)
INK = (17, 17, 17)

def mark():
    """A ban hammer on a 32x32 grid."""
    g = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    # handle
    d.rectangle([14, 13, 18, 27], fill=INK)
    d.rectangle([15, 14, 17, 26], fill=(139, 90, 43))
    d.line([17, 14, 17, 26], fill=(100, 62, 28))
    d.line([15, 14, 15, 26], fill=(176, 120, 64))
    # head
    d.rectangle([6, 6, 26, 14], fill=INK)
    d.rectangle([7, 7, 25, 13], fill=(160, 160, 166))
    d.line([7, 7, 25, 7], fill=(226, 226, 230))
    d.line([7, 7, 7, 13], fill=(226, 226, 230))
    d.line([7, 13, 25, 13], fill=(92, 92, 98))
    d.line([25, 7, 25, 13], fill=(92, 92, 98))
    # bands
    for x in (11, 21):
        d.line([x, 7, x, 13], fill=(240, 190, 60))
        d.line([x + 1, 7, x + 1, 13], fill=(196, 140, 30))
    return g

def icon(size):
    img = Image.new("RGB", (size, size), RED)
    m = mark().resize((size * 3 // 4, size * 3 // 4), Image.NEAREST)
    img.paste(m, ((size - m.width) // 2, (size - m.height) // 2), m)
    return img

icon(512).save(f"{OUT}/icon.png")

W, H = 2100, 280
head = Image.new("RGB", (W, H))
px = head.load()
for x in range(W):
    for y in range(H):
        t = x / W
        px[x, y] = (int(222 - 40 * t), int(70 - 22 * t), int(52 - 14 * t))
# Voxel Shop shows only the middle 700 pixels on a phone: the icon and the
# words together stay inside them.
tile = 150
size = 100
while True:
    font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", size)
    words = ImageDraw.Draw(head).textlength("Punishments", font=font)
    if tile + 36 + words <= 680:
        break
    size -= 2
tx = int((W - (tile + 36 + words)) / 2)
ty = (H - tile) // 2
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle([tx + 3, ty + 6, tx + 3 + tile, ty + 6 + tile], 30, fill=(0, 0, 0, 90))
shadow = shadow.filter(ImageFilter.GaussianBlur(10))
head.paste(shadow, (0, 0), shadow)
mask = Image.new("L", (tile, tile), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, tile - 1, tile - 1], 30, fill=255)
head.paste(icon(tile), (tx, ty), mask)
ImageDraw.Draw(head).text((tx + tile + 36, H // 2), "Punishments", font=font, fill="white", anchor="lm")
head.save(f"{OUT}/header.png")
