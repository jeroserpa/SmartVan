#!/usr/bin/env python3
"""make_icon.py - generate ui/icon.png, the home-screen icon.

Regenerating beats committing a binary blob nobody can edit: the palette here
is the same one ui/index.html uses, so the icon cannot drift away from the page
it launches. Deterministic - identical input gives a byte-identical PNG, so a
rebuild with no change is not a diff.

Stdlib only (zlib + struct). Run after changing the palette:

    python tools/make_icon.py && python tools/pack_ui.py
"""

import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "ui" / "icon.png"

SIZE = 192          # Android manifest wants 192; iOS scales it down happily.
SS = 3              # supersampling factor - cheap antialiasing on the bolt
BG = (0x0b, 0x0f, 0x14)    # --bg   from ui/index.html
FG = (0xff, 0xb0, 0x20)    # --warn from ui/index.html - amber, "AC is live"

# A lightning bolt in normalised coordinates, y down. Kept inside the middle
# ~80% so Android's maskable circle crop cannot clip it.
BOLT = [
    (0.60, 0.12), (0.29, 0.55), (0.47, 0.55),
    (0.41, 0.88), (0.71, 0.46), (0.53, 0.46),
]


def inside(px, py, poly):
    """Even-odd point-in-polygon. Six vertices; no need for anything clever."""
    hit = False
    n = len(poly)
    for i in range(n):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % n]
        if (y0 > py) != (y1 > py):
            if px < x0 + (py - y0) * (x1 - x0) / (y1 - y0):
                hit = not hit
    return hit


def main() -> int:
    # Coverage per pixel from an SS x SS grid of subsamples, then one lerp
    # between background and foreground. Two colours, so no palette needed.
    rows = bytearray()
    for y in range(SIZE):
        rows.append(0)  # PNG filter type 0 (None) for this scanline
        for x in range(SIZE):
            hits = 0
            for sy in range(SS):
                py = (y + (sy + 0.5) / SS) / SIZE
                for sx in range(SS):
                    px = (x + (sx + 0.5) / SS) / SIZE
                    if inside(px, py, BOLT):
                        hits += 1
            a = hits / (SS * SS)
            for c in range(3):
                rows.append(round(BG[c] + (FG[c] - BG[c]) * a))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
           + chunk(b"IEND", b""))
    OUT.write_bytes(png)
    print(f"{OUT.relative_to(ROOT)}: {SIZE}x{SIZE}, {len(png)} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
