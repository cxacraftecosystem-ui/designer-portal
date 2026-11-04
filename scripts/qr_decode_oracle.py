import json
import sys

from reportlab.graphics.barcode import qrencoder

LEVELS = {"L": 1, "M": 0, "Q": 3, "H": 2}

CASES = [
    ("hello world", 1, "L", "byte mode, the shortest useful case"),
    (
        "https://example.org/artisans/abc",
        3,
        "M",
        "a URL: the commonest foreign QR a designer will point this at, and the one whose honest "
        "refusal ('that is not a workshop card or tag') depends on it being DECODED first",
    ),
    ("0123456789012345", 1, "L", "numeric mode with a one-digit tail"),
    ("40063813339318", 1, "M", "numeric mode with a two-digit tail (an EAN-13 with a digit added)"),
    ("DPW1:P:ABCDEFGH:0000", 1, "M", "a real workshop payload, drawn by the other implementation"),
    ("HELLO WORLD", 1, "Q", "the standard's own worked example"),
    ("the quick brown fox jumps over the lazy dog " * 3, 6, "L", "version 6, two Reed-Solomon blocks"),
    ("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", 4, "H", "four blocks at the heaviest error correction"),
]


def build(text: str, version: int, level: str, mask: int) -> str:
    qr = qrencoder.QRCode(version, LEVELS[level])
    qr.addData(text)
    qr.makeImpl(False, mask)
    n = qr.getModuleCount()
    return "/".join("".join("1" if qr.isDark(r, c) else "0" for c in range(n)) for r in range(n))


out = []
for text, version, level, why in CASES:
    masks = [build(text, version, level, mask) for mask in range(8)]
    size = 17 + 4 * version
    for matrix in masks:
        rows = matrix.split("/")
        assert len(rows) == size, ("unexpected module count", text, version, len(rows), size)
        assert all(len(row) == size for row in rows), ("ragged matrix", text, version)
    out.append({"text": text, "version": version, "level": level, "why": why, "masks": masks})

json.dump(out, sys.stdout)
