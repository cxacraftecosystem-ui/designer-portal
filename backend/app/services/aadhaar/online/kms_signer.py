from __future__ import annotations

from typing import Any

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

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
    def __init__(self, key_id: str, client: Any) -> None:
        self.key_id = key_id
        self._client = client
        self._public: rsa.RSAPublicKey | None = None

    @property
    def key_size(self) -> int:
        return self.public_key().key_size

    def public_key(self) -> rsa.RSAPublicKey:
        if self._public is not None:
            return self._public
        der = self._client.get_public_key(KeyId=self.key_id)["PublicKey"]
        key = serialization.load_der_public_key(der)
        if not isinstance(key, rsa.RSAPublicKey):
            raise KmsSigningError("the KMS signing key is not an RSA key")
        self._public = key
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
