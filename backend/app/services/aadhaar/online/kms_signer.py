"""The AUA's request signature made inside AWS KMS, so the private key never leaves an HSM.

UIDAI requires the key that signs authentication requests to live in a FIPS 140-2 hardware security
module dedicated to the entity (Blocker B4 in docs/aadhaar/GO_LIVE_CHECKLIST.md). An AWS KMS
asymmetric key (key spec RSA_2048, key usage SIGN_VERIFY) is generated and used only inside KMS's
validated HSMs and cannot be exported. Whether UIDAI accepts a cloud HSM is Question 7 for UIDAI;
AWS CloudHSM (a dedicated, single-tenant HSM) is the fallback if it does not, and would slot in
behind the same :class:`KmsRsaSigner` shape.

HOW IT PLUGS IN. ``xmldsig.sign_enveloped`` calls ``key.sign(data, padding, hash)`` exactly as the
``cryptography`` library's RSA key does. :class:`KmsRsaSigner` answers that call by hashing the
canonical SignedInfo locally and asking KMS to sign the DIGEST (``MessageType=DIGEST``), so the
4 KB raw-message limit never applies and only 32 bytes leave the box.

Selected by ``AADHAAR_SIGNING_KMS_KEY_ID`` in place of the PFX, with the certificate a CA issued for
that key's public half in ``AADHAAR_AUA_SIGNING_CERT_PATH``. Behind ``AADHAAR_ONLINE_AUTH_ENABLED``
like everything else online. :func:`check` runs at boot: the key must be RSA_2048 / SIGN_VERIFY,
its public key must be the certificate's, and a test signature must verify.
"""

from __future__ import annotations

from typing import Any

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

#: KMS signing algorithms by the hash ``sign_enveloped`` asks for. PKCS#1 v1.5 is what XMLDSig's
#: rsa-sha256 means; UIDAI refuses SHA-1 since 30 June 2026 (Circular 4 of 2026).
_ALGORITHMS = {
    "sha256": "RSASSA_PKCS1_V1_5_SHA_256",
    "sha384": "RSASSA_PKCS1_V1_5_SHA_384",
    "sha512": "RSASSA_PKCS1_V1_5_SHA_512",
}


class KmsSigningError(RuntimeError):
    pass


def kms_client(settings: Any) -> Any:
    import boto3

    return boto3.client(
        "kms",
        region_name=settings.aws_region,
        aws_access_key_id=settings.aws_access_key_id,
        aws_secret_access_key=settings.aws_secret_access_key,
    )


class KmsRsaSigner:
    """Duck-types ``rsa.RSAPrivateKey.sign`` for ``xmldsig.sign_enveloped``. Holds no key material."""

    def __init__(self, key_id: str, client: Any) -> None:
        self.key_id = key_id
        self._client = client

    @property
    def key_size(self) -> int:
        return self.public_key().key_size

    def public_key(self) -> rsa.RSAPublicKey:
        der = self._client.get_public_key(KeyId=self.key_id)["PublicKey"]
        key = serialization.load_der_public_key(der)
        if not isinstance(key, rsa.RSAPublicKey):
            raise KmsSigningError("the KMS signing key is not an RSA key")
        return key

    def sign(self, data: bytes, pad: Any, algorithm: hashes.HashAlgorithm) -> bytes:
        if not isinstance(pad, padding.PKCS1v15):
            raise KmsSigningError("the AUA signature is RSA PKCS#1 v1.5")
        name = algorithm.name
        if name not in _ALGORITHMS:
            raise KmsSigningError(f"no KMS signing algorithm for {name}")
        digest = hashes.Hash(algorithm)
        digest.update(data)
        response = self._client.sign(
            KeyId=self.key_id,
            Message=digest.finalize(),
            MessageType="DIGEST",
            SigningAlgorithm=_ALGORITHMS[name],
        )
        return response["Signature"]


def check(signer: KmsRsaSigner, certificate: x509.Certificate) -> None:
    """Refuse to start unless the KMS key is the right kind, matches the certificate, and signs."""
    meta = signer._client.describe_key(KeyId=signer.key_id)["KeyMetadata"]
    if meta.get("KeyState") != "Enabled":
        raise KmsSigningError(f"the KMS signing key is {meta.get('KeyState')}, not Enabled")
    if meta.get("KeyUsage") != "SIGN_VERIFY" or meta.get("KeySpec") != "RSA_2048":
        raise KmsSigningError(
            "AADHAAR_SIGNING_KMS_KEY_ID must be an RSA_2048 key with key usage SIGN_VERIFY"
        )
    public = signer.public_key()
    cert_key = certificate.public_key()
    if not isinstance(cert_key, rsa.RSAPublicKey) or cert_key.public_numbers() != public.public_numbers():
        raise KmsSigningError(
            "AADHAAR_AUA_SIGNING_CERT_PATH is not the certificate for the KMS signing key"
        )
    probe = b"aadhaar-aua-kms-signing-check"
    signature = signer.sign(probe, padding.PKCS1v15(), hashes.SHA256())
    try:
        public.verify(signature, probe, padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature as exc:
        raise KmsSigningError("the KMS signing key produced a signature that does not verify") from exc
