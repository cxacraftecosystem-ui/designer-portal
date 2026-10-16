"""Aadhaar OFFLINE verification: the Secure QR and the Paperless Offline e-KYC ZIP.

No database. Every document is built by ``tests/aadhaar_support.py`` and signed with a TEST key that
the ``trust`` fixture adds to the trusted list through AADHAAR_EXTRA_TRUSTED_CERTS — the same door
an operator uses to rotate in UIDAI's next certificate — so the production pinning is exercised
alongside. The one official artefact used is the V1 sample number from UIDAI's 2019 Secure QR
specification, which decodes but is signed by no published certificate.
"""

from __future__ import annotations

import io
import json
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from aadhaar_support import (
    TEST_LAST4,
    TEST_NAME,
    aes_zip,
    build_secure_qr,
    make_identity,
    offline_xml,
    qr_text,
    reference_id,
    secure_qr_payload,
    sign_qr,
    zipcrypto_zip,
)

from app.core.config import get_settings
from app.services.aadhaar import certs, xmldsig
from app.services.aadhaar.identity import AadhaarError
from app.services.aadhaar.offline_xml import extract_xml, verify_offline_xml, verify_offline_zip
from app.services.aadhaar.secure_qr import decompress, verify_secure_qr

NOW = datetime.now(UTC)
#: UIDAI's own sample from "Secure QR Code Specification, Version 0, March 2019" (archived).
UIDAI_SAMPLE_V1 = Path(__file__).parent / "data" / "uidai_secure_qr_sample_v1_2019.txt"


@pytest.fixture(scope="module")
def uidai_test():
    return make_identity("TEST UIDAI offline signer")


@pytest.fixture
def trust(uidai_test, tmp_path, monkeypatch):
    path = uidai_test.write_pem(tmp_path, "test-uidai")
    monkeypatch.setattr(get_settings(), "aadhaar_extra_trusted_certs", path)
    monkeypatch.setattr(get_settings(), "aadhaar_offline_xml_max_age_days", 3.0)
    monkeypatch.setattr(get_settings(), "aadhaar_secure_qr_max_age_days", 0.0)
    return uidai_test


# ── the pinned certificates ──────────────────────────────────────────────────────────────────


def test_every_pinned_certificate_matches_its_manifest_fingerprint_and_names_its_source():
    entries = certs.manifest_entries()
    assert len(entries) >= 8
    for entry in entries:
        certificate = certs.load_certificate((certs.DATA_DIR / entry["file"]).read_bytes())
        assert certs.fingerprint(certificate) == entry["sha256"]
        assert entry["source_url"].startswith("https://backend.uidai.gov.in/")
    purposes = {entry["purpose"] for entry in entries}
    assert certs.PURPOSE_OFFLINE_SIGNATURE in purposes
    assert {certs.PURPOSE_AUTH_ENCRYPTION_PROD, certs.PURPOSE_AUTH_ENCRYPTION_PREPROD} <= purposes


def test_a_certificate_swapped_on_disk_is_refused(tmp_path, monkeypatch):
    other = make_identity("not UIDAI")
    (tmp_path / "x.pem").write_bytes(other.pem())
    manifest = {"certificates": [{"file": "x.pem", "purpose": "offline-signature", "sha256": "00" * 32}]}
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))
    monkeypatch.setattr(certs, "DATA_DIR", tmp_path)
    monkeypatch.setattr(certs, "MANIFEST", tmp_path / "manifest.json")
    certs.clear_cache()
    try:
        with pytest.raises(certs.CertificateError):
            certs.trusted_certificates(certs.PURPOSE_OFFLINE_SIGNATURE)
    finally:
        certs.clear_cache()


# ── Secure QR ────────────────────────────────────────────────────────────────────────────────


def test_v2_secure_qr_verifies_and_yields_only_storable_fields(trust):
    now = datetime.now(UTC)
    identity = verify_secure_qr(build_secure_qr(trust.key), now=now)
    assert identity.method == "SECURE_QR"
    assert identity.last4 == TEST_LAST4
    assert identity.name == TEST_NAME
    assert identity.dob.isoformat() == "1987-05-07"
    assert identity.birth_year == 1987
    assert identity.gender == "F"
    assert abs((identity.signed_at - now).total_seconds()) < 5
    # The dataclass has no field that could carry an address, a photograph or the number.
    assert set(vars(identity)) == {
        "method", "last4", "name", "dob", "birth_year", "gender", "signed_at", "txn",
        "uidai_name_match",
    }


def test_unversioned_v1_layout_verifies_too(trust):
    identity = verify_secure_qr(build_secure_qr(trust.key, version=None), now=NOW)
    assert identity.name == TEST_NAME and identity.last4 == TEST_LAST4


def test_a_year_only_date_of_birth_is_kept_as_a_year(trust):
    identity = verify_secure_qr(build_secure_qr(trust.key, dob="1987"), now=NOW)
    assert identity.dob is None and identity.birth_year == 1987


def test_tampered_qr_is_refused(trust):
    signed = bytearray(sign_qr(secure_qr_payload(), trust.key))
    index = signed.find(TEST_NAME.encode())
    signed[index] = ord("X")  # "Xamla Devi…": one byte changed after signing
    with pytest.raises(AadhaarError) as refused:
        verify_secure_qr(qr_text(bytes(signed)), now=NOW)
    assert refused.value.code == "signature_invalid"


def test_qr_signed_by_an_untrusted_key_is_refused(trust):
    stranger = make_identity("somebody else")
    with pytest.raises(AadhaarError) as refused:
        verify_secure_qr(build_secure_qr(stranger.key), now=NOW)
    assert refused.value.code == "signature_invalid"


@pytest.mark.parametrize("text", ["", "hello", "12345", "0", "9" * 50])
def test_anything_that_is_not_a_secure_qr_is_refused_as_such(trust, text):
    with pytest.raises(AadhaarError) as refused:
        verify_secure_qr(text or " ", now=NOW)
    assert refused.value.code == "qr_format"


def test_a_qr_longer_than_pythons_int_parsing_cap_still_decodes(trust):
    # A real photograph does not compress, which is what makes a real QR's number this long.
    text = build_secure_qr(trust.key, photo=__import__("os").urandom(3000))
    assert len(text) > 4300
    assert verify_secure_qr(text, now=NOW).name == TEST_NAME


def test_qr_age_limit_applies_only_when_configured(trust, monkeypatch):
    old = build_secure_qr(trust.key, ref=reference_id(generated=NOW - timedelta(days=900)))
    assert verify_secure_qr(old, now=NOW).signed_at < NOW - timedelta(days=800)
    monkeypatch.setattr(get_settings(), "aadhaar_secure_qr_max_age_days", 30.0)
    with pytest.raises(AadhaarError) as refused:
        verify_secure_qr(old, now=NOW)
    assert refused.value.code == "expired"


def test_uidais_official_2019_sample_decodes_but_is_signed_by_no_published_certificate():
    text = UIDAI_SAMPLE_V1.read_text()
    data = decompress(text)
    assert data[:2] == b"2\xff"  # unversioned layout, indicator 2
    with pytest.raises(AadhaarError) as refused:
        verify_secure_qr(text, now=NOW)
    assert refused.value.code == "signature_invalid"


# ── Offline e-KYC ZIP ────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("packer", [zipcrypto_zip, aes_zip], ids=["zipcrypto", "winzip-aes"])
def test_offline_zip_verifies_with_either_zip_encryption(trust, packer):
    archive = packer("offlineaadhaar20261015.xml", offline_xml(trust, share_code="4821"), "4821")
    identity = verify_offline_zip(archive, "4821", now=NOW)
    assert identity.method == "OFFLINE_XML"
    assert identity.last4 == TEST_LAST4
    assert identity.name == TEST_NAME
    assert identity.gender == "F"
    assert identity.dob.isoformat() == "1987-05-07"


@pytest.mark.parametrize("packer", [zipcrypto_zip, aes_zip], ids=["zipcrypto", "winzip-aes"])
def test_wrong_share_code_is_refused_as_such(trust, packer):
    archive = packer("a.xml", offline_xml(trust, share_code="4821"), "4821")
    with pytest.raises(AadhaarError) as refused:
        verify_offline_zip(archive, "1111", now=NOW)
    assert refused.value.code == "share_code_wrong"


def test_rsa_sha256_signed_xml_also_verifies(trust):
    xml = offline_xml(
        trust, signature_method=xmldsig.RSA_SHA256, digest_method=xmldsig.DIGEST_SHA256
    )
    assert verify_offline_xml(xml, "1234", now=NOW).name == TEST_NAME


def test_xml_changed_after_signing_is_refused(trust):
    xml = offline_xml(trust).replace(TEST_NAME.encode(), b"Somebody Else Entirely")
    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(xml, "1234", now=NOW)
    assert refused.value.code == "signature_invalid"


def test_unsigned_xml_is_refused(trust):
    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(offline_xml(trust, sign=False), "1234", now=NOW)
    assert refused.value.code == "unsigned"


def test_xml_signed_by_an_untrusted_key_is_refused(trust):
    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(offline_xml(make_identity("not UIDAI")), "1234", now=NOW)
    assert refused.value.code == "signature_invalid"


def test_xml_older_than_the_configured_age_is_refused(trust, monkeypatch):
    stale = offline_xml(trust, ref=reference_id(generated=NOW - timedelta(days=4)))
    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(stale, "1234", now=NOW)
    assert refused.value.code == "expired"
    assert "3 days" in refused.value.message
    monkeypatch.setattr(get_settings(), "aadhaar_offline_xml_max_age_days", 7.0)
    assert verify_offline_xml(stale, "1234", now=NOW).last4 == TEST_LAST4


def test_mobile_and_email_are_checked_only_when_typed(trust):
    xml = offline_xml(trust, share_code="1234", mobile="9876543210", email="kamla@example.org")
    assert verify_offline_xml(xml, "1234", now=NOW, mobile="+91 98765 43210").name == TEST_NAME
    assert verify_offline_xml(xml, "1234", now=NOW, email="Kamla@example.org").name == TEST_NAME
    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(xml, "1234", now=NOW, mobile="9000000000")
    assert refused.value.code == "mobile_mismatch"
    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(xml, "1234", now=NOW, email="other@example.org")
    assert refused.value.code == "email_mismatch"


def test_a_second_signature_wrapped_inside_the_document_is_refused(trust):
    genuine = xmldsig.parse(offline_xml(trust))
    signature = genuine.find(f"{{{xmldsig.DS_NS}}}Signature")
    genuine.find("UidData").append(__import__("copy").deepcopy(signature))
    from lxml import etree

    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(etree.tostring(genuine), "1234", now=NOW)
    assert refused.value.code == "signature_invalid"


def test_doctype_and_entities_are_refused_before_parsing(trust):
    hostile = b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><OfflinePaperlessKyc/>'
    with pytest.raises(AadhaarError) as refused:
        verify_offline_xml(hostile, "1234", now=NOW)
    assert refused.value.code == "xml_format"


def test_unencrypted_or_odd_zips_are_refused(trust):
    plain = io.BytesIO()
    with zipfile.ZipFile(plain, "w") as bundle:
        bundle.writestr("a.xml", offline_xml(trust))
    for archive in (plain.getvalue(), b"not a zip at all"):
        with pytest.raises(AadhaarError) as refused:
            extract_xml(archive, "1234")
        assert refused.value.code == "zip_unreadable"
