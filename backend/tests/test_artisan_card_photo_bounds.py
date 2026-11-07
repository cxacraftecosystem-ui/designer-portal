from __future__ import annotations

import io
import struct
import zlib

from app.services import artisan_card_pdf


def _png_header(width: int, height: int) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(b"\0"))
        + chunk(b"IEND", b"")
    )


def test_an_oversized_pixel_count_is_refused_before_it_is_decoded(monkeypatch):
    from PIL import Image

    decoded: list[str] = []
    original = Image.Image.convert

    def spy(self, *args, **kwargs):
        decoded.append(self.mode)
        return original(self, *args, **kwargs)

    monkeypatch.setattr(Image.Image, "convert", spy)
    bomb = _png_header(9_000, 9_000)

    assert artisan_card_pdf.scale_photo(bomb) is None
    assert decoded == []


def test_a_normal_photo_is_still_scaled_for_the_card():
    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (1200, 1600), (180, 120, 90)).save(buffer, format="JPEG")
    scaled = artisan_card_pdf.scale_photo(buffer.getvalue())
    assert scaled is not None
    with Image.open(io.BytesIO(scaled)) as image:
        assert max(image.size) <= artisan_card_pdf.PHOTO_MAX_PX
