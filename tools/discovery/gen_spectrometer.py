"""Generates assets/galactica/textures/item/spectrometer.png (16x16) procedurally.

A brass spyglass tube (bottom-left to centre) feeding an amethyst prism that fans white light
into a spectrum, matching the recipe (spyglass + amethyst + redstone). Pure Python: the PNG is
written with zlib. Run from the repo root:
    python tools/discovery/gen_spectrometer.py
"""
import struct
import zlib
from pathlib import Path

W = H = 16
img = [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]

OUTLINE = (42, 30, 24, 255)
BRASS = (204, 152, 64, 255)
BRASS_DARK = (138, 92, 36, 255)
BRASS_LIGHT = (246, 214, 128, 255)
LENS = (150, 222, 255, 255)
AMETHYST = (181, 126, 220, 255)
AMETHYST_DARK = (112, 66, 162, 255)
AMETHYST_LIGHT = (226, 196, 250, 255)
REDSTONE = (200, 24, 24, 255)
# Monochromatic sRGB at 450, 480, 530, 570, 600, 650 nm.
SPECTRUM = [(64, 0, 255, 255), (0, 170, 255, 255), (60, 255, 0, 255),
            (225, 255, 0, 255), (255, 140, 0, 255), (255, 0, 0, 255)]


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        img[y][x] = c


def tube():
    # A 3-pixel-thick diagonal from (1, 14) to (8, 7): dark lower edge, brass core, light upper edge.
    for i in range(8):
        x, y = 1 + i, 14 - i
        put(x, y, BRASS)
        put(x - 1, y, BRASS_LIGHT if i % 3 else BRASS)
        put(x, y + 1, BRASS_DARK)
        put(x - 1, y - 1, OUTLINE)
        put(x + 1, y + 1, OUTLINE)
    # Eyepiece ring and objective lens.
    put(0, 15, OUTLINE)
    put(1, 15, OUTLINE)
    put(0, 14, OUTLINE)
    put(8, 6, LENS)
    put(9, 7, LENS)
    put(9, 6, OUTLINE)
    # Brass bands (the redstone sensor).
    put(4, 11, REDSTONE)
    put(3, 11, BRASS_DARK)


def prism():
    # Triangle with its apex at the top, base on row 8, columns 9..15.
    for y in range(2, 9):
        half = (y - 2) // 2
        for x in range(12 - half, 13 + half):
            edge = x == 12 - half or x == 12 + half or y == 8
            c = OUTLINE if edge else (AMETHYST_LIGHT if x < 12 else AMETHYST if x == 12 else AMETHYST_DARK)
            put(x, y, c)


def rays():
    # Dispersed light leaving the prism's right face, fanning out towards the right edge (violet up).
    for x in range(13, 16):
        spread = 0.45 + 0.4 * (x - 13)
        for k, c in enumerate(SPECTRUM):
            put(x, round(5 + (k - 2.5) * spread), c)


tube()
prism()
rays()


def write_png(path: Path):
    raw = b''.join(b'\x00' + b''.join(bytes(px) for px in row) for row in img)

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


if __name__ == '__main__':
    out = Path('src/main/resources/assets/galactica/textures/item/spectrometer.png')
    write_png(out)
    print('wrote', out)
