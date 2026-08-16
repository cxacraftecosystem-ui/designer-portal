"""Reference QR symbols for `frontend/e2e/qr-decode-unit.spec.ts`, from a SECOND encoder.

    backend/.venv/Scripts/python.exe scripts/qr_decode_oracle.py > frontend/e2e/fixtures/qr-decode-reference.json

WHY THIS EXISTS SEPARATELY FROM ``qr_oracle.py``. That script proves the hand-written ENCODER in
`frontend/lib/qrEncode.ts` draws the same modules as reportlab. This one proves the hand-written
DECODER in `frontend/lib/qrDecode.ts` reads symbols *it did not draw* — which is a different claim,
and the only one that matters for the upload path, because the pictures a designer uploads were
overwhelmingly produced by some other encoder.

A decoder tested only against our own encoder is tested against a mirror: any shared misreading of
ISO 18004 — a mode indicator off by one, a character count width taken from the wrong version band,
an interleaving walked in the wrong order — cancels exactly, and the round trip passes while every
real-world symbol fails. Reading reportlab's modules removes that whole class of agreement.

WHAT IS COVERED HERE THAT `qrEncode.ts` CANNOT PRODUCE AT ALL. The encoder is alphanumeric-only, on
purpose (see its header). So the byte and numeric segment parsers in the decoder have no round trip
available to them, and without this fixture they would be entirely untested — which matters more
than it sounds, because those are the modes a FOREIGN code arrives in. A payment QR or a shipping
label is byte mode, and the difference between reading it and refusing it honestly ("this is a QR
code, but not one this app printed") versus reporting "no QR code was found in that picture" is the
difference between a designer stopping and a designer photographing the same label six more times.

ALL EIGHT MASKS ARE DUMPED PER CASE. Unlike the encoder oracle — which cannot compare mask
*selection* because reportlab's penalty scorer departs from the standard — the decoder does not
choose a mask, it READS the one the symbol declares. So all eight are legitimate inputs, and
decoding all eight of every case is what proves the format-information read and the unmasking are
right for every mask rather than for the one that happened to win.
"""
import json
import sys

from reportlab.graphics.barcode import qrencoder

# reportlab's own constants, which are the two-bit format values and NOT an L→H ordering.
LEVELS = {"L": 1, "M": 0, "Q": 3, "H": 2}

# (text, version, level, why this case is here)
CASES = [
    # ── BYTE MODE. Lower case forces reportlab out of alphanumeric mode, which is the whole point:
    # `qrEncode.ts` cannot emit this at all, so nothing else in the suite exercises the byte
    # segment parser or its 8-bit character count.
    ("hello world", 1, "L", "byte mode, the shortest useful case"),
    (
        "https://example.org/artisans/abc",
        3,
        "M",
        "a URL: the commonest foreign QR a designer will point this at, and the one whose honest "
        "refusal ('that is not a workshop card or tag') depends on it being DECODED first",
    ),
    # ── NUMERIC MODE, which is its own segment parser with 10-bit triples and a ragged tail. The
    # length is deliberately 3n+1 so the 4-bit single-digit tail is exercised; the second is 3n+2
    # for the 7-bit pair.
    ("0123456789012345", 1, "L", "numeric mode with a one-digit tail"),
    ("40063813339318", 1, "M", "numeric mode with a two-digit tail (an EAN-13 with a digit added)"),
    # ── ALPHANUMERIC, so the mode the application actually prints is also read off a foreign
    # encoder rather than only off our own.
    ("DPW1:P:ABCDEFGH:0000", 1, "M", "a real workshop payload, drawn by the other implementation"),
    ("HELLO WORLD", 1, "Q", "the standard's own worked example"),
    # ── THE BIG END of what this build reads. Version 6 at level L is the largest symbol
    # `qrDecode.ts` accepts, and a decoder that mis-handles multi-block interleaving passes every
    # small case and fails here — version 6 at L is cut into two blocks, and at H into four.
    ("the quick brown fox jumps over the lazy dog " * 3, 6, "L", "version 6, two Reed-Solomon blocks"),
    ("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", 4, "H", "four blocks at the heaviest error correction"),
]


def build(text: str, version: int, level: str, mask: int) -> str:
    qr = qrencoder.QRCode(version, LEVELS[level])
    qr.addData(text)
    # makeImpl rather than make: make() picks its own mask, and the point here is to pin all eight.
    # `False` is "not a scoring pass", which is what writes the real format information — a scoring
    # pass writes a placeholder, and a decoder handed that would read the wrong level and mask.
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
