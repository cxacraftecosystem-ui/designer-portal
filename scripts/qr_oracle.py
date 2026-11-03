import json
import sys

from reportlab.graphics.barcode import qrencoder

CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def check_chars(prefix: str) -> str:
    h = 0x811C9DC5
    for byte in prefix.encode("ascii"):
        h ^= byte
        h = (h * 0x01000193) & 0xFFFFFFFF
    v = h & 0xFFFFF
    return "".join(CHECK_ALPHABET[(v >> shift) & 31] for shift in (15, 10, 5, 0))


def payload(record_type: str, record_id: str) -> str:
    prefix = f"DPW1:{record_type}:{record_id.upper()}"
    return f"{prefix}:{check_chars(prefix)}"


LEVELS = {"L": 1, "M": 0, "Q": 3, "H": 2}

CASES = [
    ("HELLO WORLD", 1, "Q"),
    (payload("A", "cmsik2jg8000eh8xc1lcy661a"), 3, "Q"),
    (payload("P", "cmt0prototype0001abc"), 3, "Q"),
    (payload("A", "3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b"), 4, "Q"),
    ("DPW1:P:ABCDEFGH:0000", 1, "M"),
    ("DPW1:P:ABCDEFGH:0000", 2, "H"),
    (payload("A", "cmsik2jg8000eh8xc1lcy661a") + ":" + "X" * 120, 6, "L"),
]

CAPACITY = {
    (1, "L"): 25, (1, "M"): 20, (1, "Q"): 16, (1, "H"): 10,
    (2, "L"): 47, (2, "M"): 38, (2, "Q"): 29, (2, "H"): 20,
    (3, "L"): 77, (3, "M"): 61, (3, "Q"): 47, (3, "H"): 35,
    (4, "L"): 114, (4, "M"): 90, (4, "Q"): 67, (4, "H"): 50,
    (5, "L"): 154, (5, "M"): 122, (5, "Q"): 87, (5, "H"): 64,
    (6, "L"): 195, (6, "M"): 154, (6, "Q"): 108, (6, "H"): 84,
}

for text, version, level in CASES:
    assert len(text) <= CAPACITY[(version, level)], ("does not fit", text, version, level)
    if version > 1:
        assert len(text) > CAPACITY[(version - 1, level)], ("not the smallest that fits", text, version, level)

out = []
for text, version, level in CASES:
    masks = []
    for mask in range(8):
        qr = qrencoder.QRCode(version, LEVELS[level])
        qr.addData(text)
        qr.makeImpl(False, mask)
        n = qr.getModuleCount()
        masks.append("/".join("".join("1" if qr.isDark(r, c) else "0" for c in range(n)) for r in range(n)))
    out.append({"text": text, "version": version, "level": level, "masks": masks})

json.dump(out, sys.stdout)
