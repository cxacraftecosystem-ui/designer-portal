"""The artisan QR card token (services/artisan_card.py): the shared vectors, the tag, rotation, and
what the payload must never carry. No database."""

from __future__ import annotations

import json
import re
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.services import artisan_card as ac, artisan_card_pdf as pdf

ROOT = Path(__file__).resolve().parents[2]
VECTORS = json.loads((ROOT / "shared" / "artisan-card-vectors.json").read_text(encoding="utf-8"))
KEY = VECTORS["tags"]["key"]
PREVIOUS = "test-only-previous-artisan-card-key-not-a-secret-01"


def test_the_alphabet_is_crockford_and_qr_alphanumeric():
    assert ac.ALPHABET == VECTORS["alphabet"] == "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    qr_alphanumeric = set("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:")
    assert set(ac.ALPHABET) <= qr_alphanumeric
    assert not set("ILOU") & set(ac.ALPHABET)


@pytest.mark.parametrize("case", VECTORS["shortCodes"], ids=lambda c: c["shortCode"])
def test_the_check_and_short_code_vectors(case):
    assert ac.check(case["issuer"], case["code"]) == case["check"]
    assert ac.short_code(case["issuer"], case["code"]) == case["shortCode"]


@pytest.mark.parametrize("case", VECTORS["parse"], ids=lambda c: c["input"] or "<empty>")
def test_the_parse_vectors(case):
    if "expect" in case:
        assert ac.parse(case["input"]).as_dict() == case["expect"]
    else:
        with pytest.raises(ac.CardParseError) as caught:
            ac.parse(case["input"])
        assert caught.value.reason == case["error"]


@pytest.mark.parametrize(
    "case", VECTORS["tags"]["cases"], ids=lambda c: f"{c['issuer']}{c['keyVersion']}-{c['code']}"
)
def test_the_tag_vectors_under_the_test_key(case):
    assert ac.tag(KEY, case["issuer"], case["keyVersion"], case["code"]) == case["tag"]
    assert ac.payload(KEY, case["issuer"], case["keyVersion"], case["code"]) == case["payload"]
    assert len(case["payload"]) == 35
    parsed = ac.parse(case["payload"])
    assert (parsed.issuer, parsed.code, parsed.keyVersion, parsed.tag, parsed.typed) == (
        case["issuer"], case["code"], case["keyVersion"], case["tag"], False,
    )


def _settings(version: int, previous: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        artisan_cards_enabled=True,
        artisan_card_key=KEY,
        artisan_card_key_version=version,
        artisan_card_key_previous=previous,
        artisan_card_issuer="D",
    )


def test_a_tag_verifies_only_under_its_own_key_and_version():
    current = ac.parse(ac.payload(KEY, "D", 2, "7K3QM9XA2F"))
    old = ac.parse(ac.payload(PREVIOUS, "D", 1, "7K3QM9XA2F"))
    assert ac.verify(_settings(2), current)
    # The previous version only with KEY_PREVIOUS set.
    assert not ac.verify(_settings(2), old)
    assert ac.verify(_settings(2, PREVIOUS), old)
    # Two versions back, or a version from the future: no.
    assert not ac.verify(_settings(2), ac.parse(ac.payload(KEY, "D", 3, "7K3QM9XA2F")))
    assert not ac.verify(_settings(3, PREVIOUS), ac.parse(ac.payload(PREVIOUS, "D", 1, "7K3QM9XA2F")))
    # A changed code under the same tag fails.
    forged = ac.parse(f"CXA1:D2:7K3QM9XA2G:{current.tag}")
    assert not ac.verify(_settings(2), forged)
    # A typed code carries no tag, so there is nothing to refuse: lookup is by code.
    assert ac.verify(_settings(2), ac.parse(ac.short_code("D", "7K3QM9XA2F")))


def test_new_codes_are_random_crockford_and_unique():
    codes = {ac.new_code() for _ in range(2000)}
    assert len(codes) == 2000
    assert all(len(c) == 10 and set(c) <= set(ac.ALPHABET) for c in codes)


def test_active_needs_the_flag_and_a_long_enough_key():
    assert ac.active(SimpleNamespace(artisan_cards_enabled=True, artisan_card_key=KEY))
    assert not ac.active(SimpleNamespace(artisan_cards_enabled=False, artisan_card_key=KEY))
    assert not ac.active(SimpleNamespace(artisan_cards_enabled=True, artisan_card_key="short"))
    assert not ac.active(SimpleNamespace(artisan_cards_enabled=True, artisan_card_key=None))


def test_the_config_defaults():
    from app.core.config import Settings

    fields = Settings.model_fields
    assert fields["artisan_cards_enabled"].default is True
    assert fields["artisan_card_key"].default is None
    assert fields["artisan_card_key_version"].default == 1
    assert fields["artisan_card_resolve_miss_limit"].default == 20
    assert fields["artisan_card_issuer"].default == "D"


def test_the_payload_carries_no_personal_data_and_no_record_id():
    """Whatever the artisan, the QR is the issuer, a version, a random code and a tag."""
    artisan_id = "clx0abc123def456ghi789jk"
    fingerprint = "a" * 64
    for _ in range(200):
        code = ac.new_code()
        token = ac.payload(KEY, "D", 1, code)
        assert re.fullmatch(r"CXA1:D1:[0-9A-Z]{10}:[0-9A-Z]{16}", token)
        assert artisan_id.upper() not in token and artisan_id not in token
        assert fingerprint not in token.lower()
        assert not re.search(r"\d{12}", token), "no 12-digit run (an Aadhaar) can appear"
        short = ac.short_code("D", code)
        assert not re.search(r"\d{12}", short.replace("-", ""))


def test_the_qr_is_version_3_at_level_q_and_the_svg_is_the_printed_symbol():
    token = ac.payload(KEY, "D", 1, "7K3QM9XA2F")
    matrix = pdf.qr_matrix(token)
    assert len(matrix) == 29 + 2 * pdf.QUIET_ZONE
    import segno

    code = segno.make(token, error="q", mode="alphanumeric", micro=False, boost_error=False)
    assert code.version == 3 and code.error == "Q"
    svg = pdf.qr_svg(token)
    assert svg.startswith("<svg") and 'viewBox="0 0 37 37"' in svg
    # A module is at least 0.6 mm on the ID-1 card.
    assert pdf.QR_BOX_ID1 / len(matrix) >= 0.6 * 72 / 25.4


def test_the_shared_module_is_the_same_bytes_in_both_portals():
    for candidate in (ROOT.parent / ROOT.name.replace("dp-", "doc-", 1), Path("C:/dev/IIT/documentation-portal")):
        other = candidate / "backend/app/services/artisan_card.py"
        if other.is_file():
            mine = (ROOT / "backend/app/services/artisan_card.py").read_text(encoding="utf-8")
            assert mine.replace("\r\n", "\n") == other.read_text(encoding="utf-8").replace("\r\n", "\n")
            vectors = candidate / "shared/artisan-card-vectors.json"
            if vectors.is_file():
                assert vectors.read_text(encoding="utf-8").replace("\r\n", "\n") == (
                    ROOT / "shared/artisan-card-vectors.json"
                ).read_text(encoding="utf-8").replace("\r\n", "\n")
            return
    pytest.skip("no sibling checkout")
