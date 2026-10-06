"""Generates assets/galactica/textures/item/star_chart.png (16x16) from a hand-placed pixel grid.

No image library needed: the PNG is written with zlib. Run from the repo root:
    python tools/gen_star_chart.py
"""
import struct
import zlib
from pathlib import Path

# Palette: parchment frame with a 1 px darker outline, a night-blue chart, a face-on spiral,
# and an amethyst marker (the recipe's amethyst shard).
P = {
    '.': (0, 0, 0, 0),
    'o': (58, 38, 20, 255),     # outline
    'p': (222, 196, 142, 255),  # parchment
    's': (184, 150, 94, 255),   # parchment shade
    'r': (240, 222, 178, 255),  # rolled-edge highlight
    'n': (16, 28, 62, 255),     # night sky
    'm': (30, 48, 96, 255),     # sky, lighter
    'w': (250, 244, 220, 255),  # bulge / bright star
    'y': (226, 196, 120, 255),  # spiral arm
    'b': (130, 170, 230, 255),  # blue star
    'a': (181, 126, 220, 255),  # amethyst
    'A': (120, 70, 170, 255),   # amethyst shade
}

GRID = [
    "................",
    ".oooooooooooooo.",
    "orrrrrrrrrrrrrro",
    "opsssssssssssspo",
    "opnnnnnnnnnnnbpo",
    "opnnmyyyynnnnnpo",
    "opnmynnnnyynnnso",
    "opnynnwwnnymnnso",
    "opnynwwwwnyannso",
    "opnmynwwnnyAnnso",
    "opnnyynnnymnnnso",
    "opbnnnyyyynnnnpo",
    "opnnnnnnnnnnnnpo",
    "opsssssssssssspo",
    "orrrrrrrrrrrrrro",
    ".oooooooooooooo.",
]


def write_png(path: Path, rows):
    h, w = len(rows), len(rows[0])
    raw = b''.join(b'\x00' + b''.join(bytes(P[c]) for c in row) for row in rows)
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


if __name__ == '__main__':
    assert all(len(r) == 16 for r in GRID) and len(GRID) == 16
    out = Path('src/main/resources/assets/galactica/textures/item/star_chart.png')
    write_png(out, GRID)
    print('wrote', out)
