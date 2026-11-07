from __future__ import annotations

import asyncio
import threading
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.api.routes import aadhaar as route
from app.services.aadhaar import offline_xml, xmldsig
from app.services.aadhaar.identity import AadhaarError

NOW = datetime.now(UTC)


def test_the_offline_zip_is_verified_off_the_event_loop(monkeypatch):
    seen: dict[str, int] = {}

    def fake_verify(archive, share_code, **_kwargs):
        seen["worker"] = threading.get_ident()
        return "verified"

    monkeypatch.setattr(route, "verify_offline_zip", fake_verify)
    body = SimpleNamespace(zipBase64="UEsDBA==", shareCode="1234", mobile=None, email=None, consent=True)

    async def go():
        seen["loop"] = threading.get_ident()
        return await route._offline_xml(body)()

    assert asyncio.run(go()) == "verified"
    assert seen["worker"] != seen["loop"]


def test_a_flood_of_elements_is_refused_before_any_signature_work(monkeypatch):
    calls: list[str] = []

    def no_signature_work(*_args, **_kwargs):
        calls.append("verify_enveloped")
        raise xmldsig.SignatureError("unsigned", "the document carries no signature")

    monkeypatch.setattr(xmldsig, "verify_enveloped", no_signature_work)
    flood = b"<OfflinePaperlessKyc referenceId='123420261107000000000'>" + b"<a/>" * 300_000 + b"</OfflinePaperlessKyc>"
    assert len(flood) < offline_xml.MAX_XML_BYTES

    with pytest.raises(AadhaarError) as refused:
        offline_xml.verify_offline_xml(flood, "1234", now=NOW)

    assert refused.value.code == "xml_format"
    assert calls == []
