"""Aadhaar ONLINE authentication (AUA through an ASA), against a fake ASA and TEST keys.

No database: the OTP throttle is replaced by a no-op here and exercised for real in
``test_aadhaar_api.py``. What is proved:

* the Auth XML is signed by the AUA key and the signature verifies;
* the PID block is encrypted as Auth API 2.5 §4.1 says — the TEST UIDAI private key recovers the
  session key from ``Skey``, and with it the PID (holding the OTP) from ``Data`` and its SHA-256
  from ``Hmac``;
* success, OTP failure, UIDAI error codes mapped to plain words, a timeout, an unsigned or
  wrongly signed answer, e-KYC, and the boot check refusing incomplete configuration.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
from pathlib import Path

import pytest
from aadhaar_support import TEST_LAST4, TEST_NAME, FakeAsa, make_identity
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12
from lxml import etree
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.services.aadhaar import xmldsig
from app.services.aadhaar.identity import AadhaarError
from app.services.aadhaar.online import config as online_config, messages, service, throttle
from app.services.aadhaar.online.errors import OPERATOR_PROBLEM, message_for

#: A syntactically valid Aadhaar number (Verhoeff) made up for the tests, and a valid 16-digit VID.
TEST_UID = "234123412346"
TEST_VID = "9999123412341234"


def _verhoeff_complete(stem: str) -> str:
    from app.services.artisan_identity import verhoeff_ok

    for digit in "0123456789":
        if verhoeff_ok(stem + digit):
            return stem + digit
    raise AssertionError("no check digit")


TEST_UID = _verhoeff_complete("23412341234")
TEST_VID = _verhoeff_complete("999912341234123")


@pytest.fixture(scope="module")
def keys():
    return {
        "aua": make_identity("TEST AUA signer"),
        "enc": make_identity("TEST UIDAI encryption"),
        "sig": make_identity("TEST UIDAI response signer"),
    }


def _config(keys, **overrides) -> online_config.OnlineConfig:
    values = {
        "env": "preprod",
        "aua_code": "TESTAUA",
        "sub_aua_code": "TESTAUA",
        "aua_license_key": "aua-lk",
        "asa_license_key": "asa/lk+=",
        "kua_license_key": None,
        "otp_url": "https://asa.test/otp/2.5",
        "auth_url": "https://asa.test/auth/2.5",
        "kyc_url": "https://asa.test/kyc/2.5",
        "signing_key": keys["aua"].key,
        "signing_certificate": keys["aua"].certificate,
        "encryption_certificate": keys["enc"].certificate,
        "response_certificates": (keys["sig"].certificate,),
        "timeout_seconds": 5.0,
        "ekyc": False,
        "name_check": True,
        "otp_channel": "00",
        "throttle_salt": b"test-salt",
        "limit_per_user": 5,
        "limit_per_uid": 3,
        "window_minutes": 60,
    }
    values.update(overrides)
    return online_config.OnlineConfig(**values)


@pytest.fixture
def asa(keys, monkeypatch):
    fake = FakeAsa(aua=keys["aua"], uidai_encryption=keys["enc"], uidai_signing=keys["sig"])
    monkeypatch.setattr(service, "transport", fake.transport())
    monkeypatch.setattr(service, "load_online_config", lambda: _config(keys))

    async def admit(*_args, **_kwargs):
        return None

    monkeypatch.setattr(throttle, "admit", admit)
    return fake


def _run(coro):
    return asyncio.run(coro)


def _otp(uid=TEST_UID):
    return _run(service.send_otp(uid, actor_id="u1", subject="user:u1"))


def _verify(issued, uid=TEST_UID, otp="123456", **kwargs):
    return _run(
        service.verify_otp(
            uid, otp, issued.txn, issued.token, actor_id=kwargs.get("actor", "u1"),
            subject=kwargs.get("subject", "user:u1"), recorded_name=TEST_NAME,
        )
    )


# ── the request on the wire ──────────────────────────────────────────────────────────────────


def test_auth_request_is_signed_and_its_pid_decrypts_with_the_uidai_key(keys):
    fake = FakeAsa(aua=keys["aua"], uidai_encryption=keys["enc"], uidai_signing=keys["sig"])
    ts = "2026-10-16T16:52:11"
    signed = messages.auth_request(
        TEST_UID, "DPWtxn", "654321", ts, _config(keys), name="Kamla Devi"
    )
    root = xmldsig.parse(signed)
    xmldsig.verify_enveloped(root, [keys["aua"].certificate])
    assert root.tag == "Auth" and root.get("ver") == "2.5" and root.get("rc") == "Y"
    assert root.get("tid") == "" and root.find("Uses").get("otp") == "y"
    assert root.find("Uses").get("pi") == "y"
    assert root.find("Skey").get("ci") == keys["enc"].certificate.not_valid_after_utc.strftime("%Y%m%d")
    data = base64.b64decode(root.find("Data").text)
    assert data[:19] == ts.encode()  # ts travels in front of the ciphertext
    pid, mac = fake.decrypt_pid(root)
    assert mac == hashlib.sha256(pid).digest()
    pid_root = etree.fromstring(pid)
    assert pid_root.get("ts") == ts and pid_root.get("ver") == "2.0"
    assert pid_root.find("Pv").get("otp") == "654321"
    assert pid_root.find("Demo/Pi").get("name") == "Kamla Devi"
    assert pid_root.find("Demo/Pi").get("mv") == "100"
    # Neither the OTP nor the name is anywhere in the clear on the wire.
    assert b"654321" not in signed and b"Kamla" not in signed


def test_otp_request_carries_the_vid_type_and_zero_routing_digits(asa):
    issued = _otp(TEST_VID)
    path, body = asa.seen[-1]
    assert path.endswith("/TESTAUA/0/0/asa%2Flk%2B%3D")
    root = etree.fromstring(body)
    assert root.tag == "Otp" and root.get("type") == "V" and root.find("Opts").get("ch") == "00"
    assert issued.last4 is None and issued.as_payload()["uidType"] == "VID"


def test_otp_request_routes_on_the_first_two_digits_of_an_aadhaar(asa):
    issued = _otp()
    assert asa.seen[-1][0].startswith(f"/otp/2.5/TESTAUA/{TEST_UID[0]}/{TEST_UID[1]}/")
    assert issued.last4 == TEST_UID[-4:]
    assert TEST_UID not in str(issued.as_payload())


# ── outcomes ─────────────────────────────────────────────────────────────────────────────────


def test_correct_otp_authenticates(asa):
    identity = _verify(_otp())
    assert identity.method == "ONLINE_OTP"
    assert identity.last4 == TEST_UID[-4:]
    assert identity.uidai_name_match is True
    assert identity.name is None and identity.txn


def test_wrong_otp_is_refused_in_plain_words(asa):
    with pytest.raises(AadhaarError) as refused:
        _verify(_otp(), otp="000000")
    assert refused.value.code == "uidai_400"
    assert "code is not correct" in refused.value.message


def test_token_binds_the_txn_to_the_caller_and_the_number(asa):
    issued = _otp()
    for kwargs in ({"actor": "someone-else"}, {"subject": "artisan:other"}):
        with pytest.raises(AadhaarError) as refused:
            _verify(issued, **kwargs)
        assert refused.value.code == "otp_session"
    with pytest.raises(AadhaarError) as refused:
        _verify(issued, uid=TEST_VID)
    assert refused.value.code == "otp_session"


@pytest.mark.parametrize(
    ("code", "expected_code", "status"),
    [("100", "uidai_name_mismatch", 422), ("998", "uidai_998", 422), ("565", "uidai_565", 503)],
)
def test_uidai_error_codes_map_to_plain_messages(asa, code, expected_code, status):
    issued = _otp()
    asa.error = code
    with pytest.raises(AadhaarError) as refused:
        _verify(issued)
    assert refused.value.code == expected_code
    assert refused.value.status_code == status
    assert refused.value.message == message_for(code)[0]


def test_an_operator_problem_never_shows_the_resident_a_code():
    message, operator = message_for("569")
    assert operator and message == OPERATOR_PROBLEM and "569" not in message


def test_otp_request_refusal(asa):
    asa.error = "111"
    with pytest.raises(AadhaarError) as refused:
        _otp()
    assert refused.value.code == "uidai_111"
    assert "no mobile number" in refused.value.message


def test_timeout_is_a_503_with_an_alternative(asa):
    asa.timeout = True
    with pytest.raises(AadhaarError) as refused:
        _otp()
    assert refused.value.code == "asa_unreachable" and refused.value.status_code == 503


def test_answer_signed_by_anyone_but_uidai_is_refused(asa, keys, monkeypatch):
    issued = _otp()
    stranger = make_identity("not UIDAI")
    monkeypatch.setattr(asa, "uidai_signing", stranger)
    with pytest.raises(AadhaarError) as refused:
        _verify(issued)
    assert refused.value.code == "response_unverified"


def test_invalid_numbers_never_leave_the_server(asa):
    for bad in ("123412341234", "23412341234", "abcd", "2341234123456"):
        with pytest.raises(AadhaarError) as refused:
            _otp(bad)
        assert refused.value.code == "invalid_uid"
    assert asa.seen == []


def test_ekyc_returns_uidais_name_year_and_gender(asa, keys, monkeypatch):
    monkeypatch.setattr(service, "load_online_config", lambda: _config(keys, ekyc=True))
    issued = _otp()
    assert issued.txn.startswith("UKC:")
    identity = _verify(issued)
    assert identity.method == "ONLINE_EKYC"
    assert identity.name == TEST_NAME and identity.birth_year == 1987 and identity.gender == "F"
    assert identity.last4 == TEST_LAST4  # from UIDAI's masked uid, not from what was typed
    kyc = etree.fromstring(asa.seen[-1][1])
    assert kyc.tag == "Kyc" and kyc.get("de") == "Y" and kyc.get("rc") == "Y"


# ── the boot check ───────────────────────────────────────────────────────────────────────────


def _settings(**overrides) -> Settings:
    base = get_settings().model_dump(by_alias=True)
    base.update(overrides)
    return Settings(**base)


def test_online_auth_refuses_to_enable_with_any_credential_missing():
    with pytest.raises(ValidationError) as refused:
        _settings(AADHAAR_ONLINE_AUTH_ENABLED=True)
    text = str(refused.value)
    for name in ("AADHAAR_AUA_CODE", "AADHAAR_ASA_LICENSE_KEY", "AADHAAR_AUA_SIGNING_PFX_PATH",
                 "AADHAAR_THROTTLE_SALT"):
        assert name in text


def _complete(pfx_path: str, password: str = "pfx-pass", **overrides) -> dict:
    values = {
        "AADHAAR_ONLINE_AUTH_ENABLED": True,
        "AADHAAR_AUA_CODE": "TESTAUA",
        "AADHAAR_SUB_AUA_CODE": "TESTAUA",
        "AADHAAR_AUA_LICENSE_KEY": "aua",
        "AADHAAR_ASA_LICENSE_KEY": "asa",
        "AADHAAR_ASA_OTP_URL": "https://asa.test/otp/2.5",
        "AADHAAR_ASA_AUTH_URL": "https://asa.test/auth/2.5",
        "AADHAAR_AUA_SIGNING_PFX_PATH": pfx_path,
        "AADHAAR_AUA_SIGNING_PFX_PASSWORD": password,
        "AADHAAR_THROTTLE_SALT": "salt",
    }
    values.update(overrides)
    return values


def test_ekyc_needs_its_own_licence_and_url(tmp_path):
    with pytest.raises(ValidationError) as refused:
        _settings(**_complete("x.pfx", AADHAAR_EKYC_ENABLED=True))
    assert "AADHAAR_KUA_LICENSE_KEY" in str(refused.value)
    assert "AADHAAR_ASA_KYC_URL" in str(refused.value)


def test_boot_opens_the_pfx_and_the_pinned_uidai_certificate(keys, tmp_path: Path, monkeypatch):
    pfx = pkcs12.serialize_key_and_certificates(
        b"aua", keys["aua"].key, keys["aua"].certificate, None,
        serialization.BestAvailableEncryption(b"pfx-pass"),
    )
    (tmp_path / "aua.pfx").write_bytes(pfx)
    good = _settings(**_complete(str(tmp_path / "aua.pfx")))
    monkeypatch.setattr(online_config, "get_settings", lambda: good)
    loaded = online_config.build_online_config()
    assert loaded.encryption_certificate.subject.rfc4514_string().startswith("CN=AD_Auth")
    assert loaded.response_certificates

    wrong_password = _settings(**_complete(str(tmp_path / "aua.pfx"), password="nope"))
    monkeypatch.setattr(online_config, "get_settings", lambda: wrong_password)
    with pytest.raises(online_config.OnlineConfigError):
        online_config.build_online_config()

    missing = _settings(**_complete(str(tmp_path / "absent.pfx")))
    monkeypatch.setattr(online_config, "get_settings", lambda: missing)
    with pytest.raises(online_config.OnlineConfigError):
        online_config.build_online_config()
