"""The transcript view says read-only by the same rule every transcript write refuses by.

``transcript_portal.read_only`` (what the view sends as ``readOnly``) and ``guard_write`` (what the
speaker, turn, re-translate, retry and auto-draft routes ask) both read ``_write_refusal``. A holder of
an inspection or oversight post on the file's workshop is told "Read-only: part of a workshop you
oversee." up front, the words the web's held-post note already uses, instead of meeting a 403.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.services import design_workshop_posts, transcript_portal

MEDIA = SimpleNamespace(id="m-1", uploadedById="uploader")
UPLOADER = SimpleNamespace(id="uploader", role="DESIGNER")
STRANGER = SimpleNamespace(id="someone-else", role="DESIGNER")
HOLDER = SimpleNamespace(id="holder", role="ADMIN")


def _held(monkeypatch: pytest.MonkeyPatch, *, held: bool) -> None:
    async def refuse(media, user, **_kw):  # noqa: ANN001
        if held:
            raise HTTPException(status_code=403, detail={"code": "HELD_POST", "message": "held"})

    monkeypatch.setattr(design_workshop_posts, "refuse_a_holders_media_write", refuse)


def test_the_uploader_may_change_it(monkeypatch: pytest.MonkeyPatch) -> None:
    _held(monkeypatch, held=False)
    assert asyncio.run(transcript_portal.read_only(UPLOADER, MEDIA)) is None
    asyncio.run(transcript_portal.guard_write(UPLOADER, MEDIA))


def test_another_reader_is_told_why_and_the_write_refuses(monkeypatch: pytest.MonkeyPatch) -> None:
    _held(monkeypatch, held=False)
    assert asyncio.run(transcript_portal.read_only(STRANGER, MEDIA)) == {
        "code": "NOT_EDITOR",
        "message": "Only the uploader or an admin can change this transcript.",
    }
    with pytest.raises(HTTPException) as refused:
        asyncio.run(transcript_portal.guard_write(STRANGER, MEDIA))
    assert refused.value.status_code == 403


def test_a_post_holder_is_read_only_before_any_write(monkeypatch: pytest.MonkeyPatch) -> None:
    _held(monkeypatch, held=True)
    assert asyncio.run(transcript_portal.read_only(HOLDER, MEDIA)) == {
        "code": "HELD_POST",
        "message": "Read-only: part of a workshop you oversee.",
    }
    with pytest.raises(HTTPException) as refused:
        asyncio.run(transcript_portal.guard_write(HOLDER, MEDIA))
    assert refused.value.status_code == 403
    assert refused.value.detail == {"code": "HELD_POST", "message": "held"}, "the write's own refusal, unchanged"


def test_the_wording_is_the_web_held_post_note() -> None:
    from pathlib import Path

    note = Path(__file__).resolve().parents[2] / "frontend" / "components" / "designworkshop" / "HeldPostNotice.tsx"
    assert f'MEDIA_HELD_REASON = "{transcript_portal.READ_ONLY_MESSAGES["HELD_POST"]}"' in note.read_text(encoding="utf-8")
