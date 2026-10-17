"""Enveloped XML signatures — verified for UIDAI's offline e-KYC XML, created for the AUA's requests.

DELIBERATELY NARROW. A general XMLDSig implementation verifies whatever a document says it signed,
which is how signature-wrapping attacks work: a valid signature over one element, and the reader
takes its data from another. This one accepts exactly the shape UIDAI uses and refuses the rest:

* exactly ONE ``ds:Signature`` in the whole document, and it is a direct child of the root;
* exactly ONE ``ds:Reference``, with ``URI=""`` — the whole document — so whatever the caller reads
  from the root afterwards is what was signed;
* transforms limited to the enveloped-signature transform and a canonicalization;
* RSA PKCS#1 v1.5 with SHA-1 or SHA-256 (UIDAI's offline XML has used both over the years);
* the key is NEVER taken from the document's own ``KeyInfo``: the signature must verify under one of
  the certificates the caller trusts (``certs.trusted_certificates``).

The parser resolves no entities, loads no DTD and touches no network, so a hostile upload cannot
read a file off the server or fetch a URL through it.
"""

from __future__ import annotations

import base64
import copy
import hashlib
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from lxml import etree

DS_NS = "http://www.w3.org/2000/09/xmldsig#"
_DS = f"{{{DS_NS}}}"

C14N_INCLUSIVE = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315"
C14N_INCLUSIVE_COMMENTS = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315#WithComments"
C14N_EXCLUSIVE = "http://www.w3.org/2001/10/xml-exc-c14n#"
C14N_EXCLUSIVE_COMMENTS = "http://www.w3.org/2001/10/xml-exc-c14n#WithComments"
ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"

RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1"
RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
DIGEST_SHA1 = "http://www.w3.org/2000/09/xmldsig#sha1"
DIGEST_SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"

_C14N = {
    C14N_INCLUSIVE: (False, False),
    C14N_INCLUSIVE_COMMENTS: (False, True),
    C14N_EXCLUSIVE: (True, False),
    C14N_EXCLUSIVE_COMMENTS: (True, True),
}
#: UIDAI's own Auth 2.5 response example spells SHA-256 with two non-standard URIs; accepted on the
#: way in, never written.
RSA_SHA256_UIDAI_SPELLING = "http://www.w3.org/2000/09/xmldsig#rsa-sha256"
DIGEST_SHA256_UIDAI_SPELLING = "http://www.w3.org/2000/09/xmldsig#sha256"

_SIGNATURE_HASH = {
    RSA_SHA1: hashes.SHA1,
    RSA_SHA256: hashes.SHA256,
    RSA_SHA256_UIDAI_SPELLING: hashes.SHA256,
}
_DIGEST = {
    DIGEST_SHA1: hashlib.sha1,
    DIGEST_SHA256: hashlib.sha256,
    DIGEST_SHA256_UIDAI_SPELLING: hashlib.sha256,
}


class SignatureError(Exception):
    """The document is unsigned, signed in a shape this module refuses, or the signature fails."""

    def __init__(self, code: str, detail: str) -> None:
        super().__init__(detail)
        self.code = code
        self.detail = detail


def safe_parser() -> etree.XMLParser:
    return etree.XMLParser(
        resolve_entities=False,
        no_network=True,
        load_dtd=False,
        dtd_validation=False,
        huge_tree=False,
        remove_blank_text=False,
    )


def parse(data: bytes) -> etree._Element:
    """Parse untrusted XML. A DOCTYPE is refused outright: UIDAI's documents carry none."""
    if b"<!DOCTYPE" in data[:4096].upper() or b"<!ENTITY" in data.upper():
        raise SignatureError("doctype", "the XML declares a DOCTYPE or an entity")
    try:
        return etree.fromstring(data, parser=safe_parser())
    except etree.XMLSyntaxError as exc:
        raise SignatureError("malformed", "the XML is not well formed") from exc


def canonicalize(element: etree._Element, algorithm: str) -> bytes:
    """C14N of ``element`` as a document subset.

    A DEEP COPY IS CANONICALIZED, NEVER THE ELEMENT IN PLACE. libxml2 (measured with lxml 6.1.3 on
    2026-10-16) canonicalizes an in-document ``SignedInfo`` with a spurious ``xmlns=""`` on
    ``<Transforms>``, so genuine UIDAI files fail to verify — the defect that makes ``signxml``
    reject them. A copy carries its in-scope namespace declarations with it and canonicalizes to
    the bytes UIDAI signed.
    """
    if algorithm not in _C14N:
        raise SignatureError("c14n", f"unsupported canonicalization {algorithm}")
    exclusive, comments = _C14N[algorithm]
    return etree.tostring(
        copy.deepcopy(element), method="c14n", exclusive=exclusive, with_comments=comments
    )


def _one(parent: etree._Element, tag: str) -> etree._Element:
    found = parent.findall(f"{_DS}{tag}")
    if len(found) != 1:
        raise SignatureError("shape", f"expected exactly one {tag}")
    return found[0]


@dataclass(frozen=True)
class VerifiedSignature:
    certificate: x509.Certificate
    signature_method: str
    digest_method: str


def _document_digest(root: etree._Element, signature: etree._Element, reference: etree._Element) -> bytes:
    transforms_parent = reference.find(f"{_DS}Transforms")
    transforms = (
        [t.get("Algorithm") for t in transforms_parent.findall(f"{_DS}Transform")]
        if transforms_parent is not None
        else []
    )
    if ENVELOPED not in transforms:
        raise SignatureError("shape", "the signature is not an enveloped signature")
    others = [t for t in transforms if t != ENVELOPED]
    if any(t not in _C14N for t in others) or len(others) > 1:
        raise SignatureError("shape", "unsupported transform")
    c14n = others[0] if others else C14N_INCLUSIVE
    digest_method = _one(reference, "DigestMethod").get("Algorithm")
    if digest_method not in _DIGEST:
        raise SignatureError("digest", f"unsupported digest {digest_method}")
    # A COPY of the document with the signature removed — the enveloped-signature transform.
    copy = etree.fromstring(etree.tostring(root), parser=safe_parser())
    for sig in copy.findall(f"{_DS}Signature"):
        copy.remove(sig)
    return _DIGEST[digest_method](canonicalize(copy, c14n)).digest()


def verify_enveloped(
    root: etree._Element, trusted: Iterable[x509.Certificate]
) -> VerifiedSignature:
    """Verify the document's enveloped signature under one of ``trusted``. Raises SignatureError."""
    all_signatures = list(root.iter(f"{_DS}Signature"))
    if not all_signatures:
        raise SignatureError("unsigned", "the document carries no signature")
    if len(all_signatures) != 1 or all_signatures[0].getparent() is not root:
        raise SignatureError("shape", "the signature is not a single child of the root")
    signature = all_signatures[0]
    signed_info = _one(signature, "SignedInfo")
    c14n_method = _one(signed_info, "CanonicalizationMethod").get("Algorithm")
    signature_method = _one(signed_info, "SignatureMethod").get("Algorithm")
    if signature_method not in _SIGNATURE_HASH:
        raise SignatureError("algorithm", f"unsupported signature method {signature_method}")
    reference = _one(signed_info, "Reference")
    if reference.get("URI", None) != "":
        raise SignatureError("shape", "the signature does not cover the whole document")
    expected_digest = (_one(reference, "DigestValue").text or "").strip()
    try:
        expected = base64.b64decode(expected_digest, validate=False)
        signature_value = base64.b64decode(
            "".join((_one(signature, "SignatureValue").text or "").split())
        )
    except ValueError as exc:
        raise SignatureError("encoding", "the signature is not valid base64") from exc
    if _document_digest(root, signature, reference) != expected:
        raise SignatureError("digest_mismatch", "the document was changed after it was signed")
    signed_bytes = canonicalize(signed_info, c14n_method or C14N_INCLUSIVE)
    hash_cls = _SIGNATURE_HASH[signature_method]
    for certificate in trusted:
        key = certificate.public_key()
        if not isinstance(key, rsa.RSAPublicKey):
            continue
        try:
            key.verify(signature_value, signed_bytes, padding.PKCS1v15(), hash_cls())
        except InvalidSignature:
            continue
        digest_method = _one(reference, "DigestMethod").get("Algorithm") or ""
        return VerifiedSignature(certificate, signature_method, digest_method)
    raise SignatureError("signature_invalid", "the signature does not verify under a trusted certificate")


def sign_enveloped(
    root: etree._Element,
    private_key: rsa.RSAPrivateKey | Any,
    certificate: x509.Certificate,
    *,
    signature_method: str = RSA_SHA256,
    digest_method: str = DIGEST_SHA256,
    c14n_method: str = C14N_INCLUSIVE,
    include_subject_name: bool = True,
) -> etree._Element:
    """Append an enveloped signature over the whole document to ``root`` and return it.

    ``Signature`` is written with the XMLDSig namespace as its DEFAULT namespace and no prefix, the
    form UIDAI's own samples and its reference AUA client use.
    """
    for existing in root.findall(f"{_DS}Signature"):
        root.remove(existing)
    digest = _DIGEST[digest_method](canonicalize(root, c14n_method)).digest()

    nsmap = {None: DS_NS}
    signature = etree.SubElement(root, f"{_DS}Signature", nsmap=nsmap)
    signed_info = etree.SubElement(signature, f"{_DS}SignedInfo")
    etree.SubElement(signed_info, f"{_DS}CanonicalizationMethod", Algorithm=c14n_method)
    etree.SubElement(signed_info, f"{_DS}SignatureMethod", Algorithm=signature_method)
    reference = etree.SubElement(signed_info, f"{_DS}Reference", URI="")
    transforms = etree.SubElement(reference, f"{_DS}Transforms")
    etree.SubElement(transforms, f"{_DS}Transform", Algorithm=ENVELOPED)
    etree.SubElement(reference, f"{_DS}DigestMethod", Algorithm=digest_method)
    etree.SubElement(reference, f"{_DS}DigestValue").text = base64.b64encode(digest).decode()

    signed_bytes = canonicalize(signed_info, c14n_method)
    value = private_key.sign(signed_bytes, padding.PKCS1v15(), _SIGNATURE_HASH[signature_method]())
    etree.SubElement(signature, f"{_DS}SignatureValue").text = base64.b64encode(value).decode()

    key_info = etree.SubElement(signature, f"{_DS}KeyInfo")
    x509_data = etree.SubElement(key_info, f"{_DS}X509Data")
    if include_subject_name:
        etree.SubElement(x509_data, f"{_DS}X509SubjectName").text = certificate.subject.rfc4514_string()
    etree.SubElement(x509_data, f"{_DS}X509Certificate").text = base64.b64encode(
        certificate.public_bytes(serialization.Encoding.DER)
    ).decode()
    return root
