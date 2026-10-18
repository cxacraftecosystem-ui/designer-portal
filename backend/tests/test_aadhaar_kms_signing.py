"""The AUA request signature made by an AWS KMS key (Blocker B4), against a mocked KMS.

The fake below answers the four KMS calls the signer makes from a local RSA key, the way KMS would:
``describe_key``, ``get_public_key`` (DER SubjectPublicKeyInfo), and ``sign`` over a DIGEST with
RSASSA_PKCS1_V1_5_SHA_256. What is pinned: the signature ``xmldsig.sign_enveloped`` writes with
the KMS signer verifies under the AUA certificate exactly as a PFX-signed one does; only a digest
reaches KMS; the boot check refuses the wrong key kind, a disabled key and a certificate for some
other key; and the settings ask for the certificate instead of the PFX when the KMS key is named.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest
from aadhaar_support import make_identity
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, utils
from lxml import etree
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.services.aadhaar import xmldsig
from app.services.aadhaar.online import config as online_config
from app.services.aadhaar.online.kms_signer import KmsRsaSigner, KmsSigningError, check

KEY_ID = "arn:aws:kms:ap-south-1:000000000000:key/test-aua-signing"


class FakeKms:
    def __init__(self, identity: Any, *, spec: str = "RSA_2048", usage: str = "SIGN_VERIFY",
                 state: str = "Enabled") -> None:
        self.identity = identity
        self.meta = {"KeySpec": spec, "KeyUsage": usage, "KeyState": state}
        self.sign_calls: list[dict[str, Any]] = []
        self.public_key_calls = 0

    def describe_key(self, KeyId: str) -> dict[str, Any]:
        assert KeyId == KEY_ID
        return {"KeyMetadata": {"KeyId": KeyId, **self.meta}}

    def get_public_key(self, KeyId: str) -> dict[str, Any]:
        assert KeyId == KEY_ID
        self.public_key_calls += 1
        der = self.identity.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        return {"PublicKey": der, "KeySpec": self.meta["KeySpec"]}

    def sign(self, KeyId: str, Message: bytes, MessageType: str, SigningAlgorithm: str) -> dict:
        assert KeyId == KEY_ID
        self.sign_calls.append({"len": len(Message), "type": MessageType, "alg": SigningAlgorithm})
        assert MessageType == "DIGEST" and SigningAlgorithm == "RSASSA_PKCS1_V1_5_SHA_256"
        signature = self.identity.key.sign(
            Message, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256())
        )
        return {"KeyId": KeyId, "Signature": signature, "SigningAlgorithm": SigningAlgorithm}


@pytest.fixture(scope="module")
def aua():
    return make_identity("Test AUA (KMS)")


def test_an_enveloped_signature_made_in_kms_verifies_under_the_aua_certificate(aua) -> None:
    kms = FakeKms(aua)
    signer = KmsRsaSigner(KEY_ID, kms)
    root = etree.fromstring(b'<Otp uid="999999990019" ver="2.5" txn="T1"><Opts ch="00"/></Otp>')
    xmldsig.sign_enveloped(root, signer, aua.certificate)
    verified = xmldsig.verify_enveloped(etree.fromstring(etree.tostring(root)), [aua.certificate])
    assert verified.certificate == aua.certificate
    # Only the 32-byte digest of SignedInfo went to KMS, never the document.
    assert kms.sign_calls == [{"len": 32, "type": "DIGEST", "alg": "RSASSA_PKCS1_V1_5_SHA_256"}]


def test_the_boot_check_accepts_the_matching_key_and_certificate(aua) -> None:
    check(KmsRsaSigner(KEY_ID, FakeKms(aua)), aua.certificate)


@pytest.mark.parametrize(
    "overrides",
    [{"spec": "RSA_4096"}, {"spec": "ECC_NIST_P256"}, {"usage": "ENCRYPT_DECRYPT"}, {"state": "Disabled"}],
)
def test_the_boot_check_refuses_the_wrong_kind_of_key(aua, overrides) -> None:
    with pytest.raises(KmsSigningError):
        check(KmsRsaSigner(KEY_ID, FakeKms(aua, **overrides)), aua.certificate)


def test_the_boot_check_refuses_a_certificate_for_another_key(aua) -> None:
    other = make_identity("Someone else")
    with pytest.raises(KmsSigningError, match="not the certificate"):
        check(KmsRsaSigner(KEY_ID, FakeKms(aua)), other.certificate)


def test_the_public_key_is_fetched_from_kms_once_and_then_kept(aua) -> None:
    """GetPublicKey is a KMS round trip (and a billed request). The boot check fetches the public
    half; every later signature and ``key_size`` read reuses it."""
    kms = FakeKms(aua)
    signer = KmsRsaSigner(KEY_ID, kms)
    check(signer, aua.certificate)
    assert kms.public_key_calls == 1
    for txn in ("T1", "T2", "T3"):
        root = etree.fromstring(f'<Otp uid="999999990019" ver="2.5" txn="{txn}"><Opts ch="00"/></Otp>'.encode())
        xmldsig.sign_enveloped(root, signer, aua.certificate)
        assert signer.key_size == 2048
    assert signer.public_key() is signer.public_key()
    assert kms.public_key_calls == 1
    assert len(kms.sign_calls) == 4  # the boot check's probe, then one per request


def test_only_pkcs1_v15_is_signed(aua) -> None:
    signer = KmsRsaSigner(KEY_ID, FakeKms(aua))
    with pytest.raises(KmsSigningError):
        signer.sign(b"x", padding.PSS(padding.MGF1(hashes.SHA256()), 32), hashes.SHA256())


def test_the_loader_takes_the_kms_key_and_certificate_instead_of_the_pfx(aua, tmp_path) -> None:
    cert_path = tmp_path / "aua.pem"
    cert_path.write_bytes(aua.pem())
    settings = SimpleNamespace(
        aadhaar_signing_kms_key_id=KEY_ID,
        aadhaar_aua_signing_cert_path=str(cert_path),
        aadhaar_aua_signing_pfx_path=None,
        aadhaar_aua_signing_pfx_password=None,
    )
    key, certificate = online_config._signing_key(settings, kms=FakeKms(aua))
    assert isinstance(key, KmsRsaSigner)
    assert certificate == aua.certificate
    with pytest.raises(online_config.OnlineConfigError):
        online_config._signing_key(settings, kms=FakeKms(aua, state="PendingDeletion"))


def _complete(**overrides: Any) -> dict[str, Any]:
    values = {
        "AADHAAR_ONLINE_AUTH_ENABLED": True,
        "AADHAAR_AUA_CODE": "TESTAUA",
        "AADHAAR_SUB_AUA_CODE": "TESTAUA",
        "AADHAAR_AUA_LICENSE_KEY": "aua",
        "AADHAAR_ASA_LICENSE_KEY": "asa",
        "AADHAAR_ASA_OTP_URL": "https://asa.test/otp/2.5",
        "AADHAAR_ASA_AUTH_URL": "https://asa.test/auth/2.5",
        "AADHAAR_THROTTLE_SALT": "salt",
    }
    values.update(overrides)
    base = get_settings().model_dump(by_alias=True)
    base.update(values)
    return base


def test_with_a_kms_key_the_settings_want_its_certificate_and_not_a_pfx() -> None:
    with pytest.raises(ValidationError) as refused:
        Settings(**_complete(AADHAAR_SIGNING_KMS_KEY_ID=KEY_ID))
    assert "AADHAAR_AUA_SIGNING_CERT_PATH" in str(refused.value)
    assert "AADHAAR_AUA_SIGNING_PFX_PATH" not in str(refused.value)
    Settings(**_complete(AADHAAR_SIGNING_KMS_KEY_ID=KEY_ID, AADHAAR_AUA_SIGNING_CERT_PATH="/etc/aua.pem"))
    with pytest.raises(ValidationError) as refused:
        Settings(**_complete())
    assert "AADHAAR_AUA_SIGNING_PFX_PATH" in str(refused.value)
