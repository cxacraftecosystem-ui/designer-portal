"""The OTP, Auth and e-KYC request documents, and the parsing of their answers.

Templates are UIDAI's, from OTP Request API 2.5, Aadhaar Authentication API 2.5 Revision 1 and
Aadhaar e-KYC API 2.5. The request documents carry no namespace, as UIDAI's 2.5 templates show.
Every request is signed by the AUA (``xmldsig.sign_enveloped``: enveloped, inclusive C14N,
RSA-SHA256 over a SHA-256 digest — the only algorithms UIDAI accepts after 30 June 2026, Circular
4/2026).
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from lxml import etree

from app.services.aadhaar import xmldsig
from app.services.aadhaar.online.config import OnlineConfig
from app.services.aadhaar.online.crypto import encrypt_pid

IST = timezone(timedelta(hours=5, minutes=30))
API_VERSION = "2.5"
PID_VERSION = "2.0"


def ist_timestamp(moment: datetime) -> str:
    """``YYYY-MM-DDThh:mm:ss`` in Indian Standard Time with no zone, as every 2.5 API wants."""
    return moment.astimezone(IST).strftime("%Y-%m-%dT%H:%M:%S")


def uid_type(uid: str) -> str:
    return "V" if len(uid) == 16 else "A"


def _signed_bytes(root: etree._Element, config: OnlineConfig) -> bytes:
    xmldsig.sign_enveloped(root, config.signing_key, config.signing_certificate)
    return etree.tostring(root, xml_declaration=True, encoding="UTF-8", standalone=True)


def otp_request(uid: str, txn: str, ts: str, config: OnlineConfig) -> bytes:
    root = etree.Element(
        "Otp",
        uid=uid,
        ac=config.aua_code,
        sa=config.sub_aua_code,
        ver=API_VERSION,
        txn=txn,
        ts=ts,
        lk=config.aua_license_key,
        type=uid_type(uid),
    )
    etree.SubElement(root, "Opts", ch=config.otp_channel)
    return _signed_bytes(root, config)


def pid_block(otp: str, ts: str, name: str | None) -> bytes:
    pid = etree.Element("Pid", ts=ts, ver=PID_VERSION, wadh="")
    if name:
        demo = etree.SubElement(pid, "Demo")
        etree.SubElement(demo, "Pi", ms="E", mv="100", name=name[:60])
    etree.SubElement(pid, "Pv", otp=otp)
    return etree.tostring(pid, encoding="UTF-8")


def auth_request(
    uid: str, txn: str, otp: str, ts: str, config: OnlineConfig, *, name: str | None
) -> bytes:
    """The signed Auth XML. ``uses pi`` only when a name is sent for UIDAI to match."""
    pid = pid_block(otp, ts, name)
    encrypted = encrypt_pid(pid, ts, config.encryption_certificate)
    del pid
    root = etree.Element(
        "Auth",
        uid=uid,
        rc="Y",
        tid="",
        ac=config.aua_code,
        sa=config.sub_aua_code,
        ver=API_VERSION,
        txn=txn,
        lk=config.aua_license_key,
    )
    etree.SubElement(
        root, "Uses", pi="y" if name else "n", pa="n", pfa="n", bio="n", pin="n", otp="y"
    )
    etree.SubElement(root, "Device", rdsId="", rdsVer="", dpId="", dc="", mi="", mc="")
    etree.SubElement(root, "Skey", ci=encrypted.ci).text = encrypted.skey
    etree.SubElement(root, "Hmac").text = encrypted.hmac
    etree.SubElement(root, "Data", type="X").text = encrypted.data
    return _signed_bytes(root, config)


def kyc_request(signed_auth: bytes) -> bytes:
    """e-KYC wrapping the signed Auth XML. ``de="Y"``: the ASA decrypts the response — UIDAI has not
    published the byte layout of the KUA-key encryption, so this deployment does not guess it."""
    root = etree.Element("Kyc", ver=API_VERSION, ra="O", rc="Y", lr="N", de="Y", pfr="N")
    etree.SubElement(root, "Rad").text = base64.b64encode(signed_auth).decode()
    return etree.tostring(root, xml_declaration=True, encoding="UTF-8", standalone=True)


# ── answers ──────────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Answer:
    ok: bool
    err: str | None
    txn: str | None
    ts: str | None
    code: str | None
    #: The document UIDAI signed (the AuthRes / OtpRes root, or the decrypted KycRes).
    root: etree._Element


def parse_answer(body: bytes, expected_tag: str) -> Answer:
    root = xmldsig.parse(body)
    if root.tag != expected_tag:
        raise xmldsig.SignatureError("shape", f"expected {expected_tag}, got {root.tag}")
    return Answer(
        ok=(root.get("ret") or "").lower() == "y",
        err=root.get("err") or None,
        txn=root.get("txn"),
        ts=root.get("ts"),
        code=root.get("code"),
        root=root,
    )


def parse_kyc_answer(body: bytes) -> tuple[str, str | None, etree._Element | None]:
    """(status, err, KycRes root or None). The ``Resp`` element's payload is the base64 KycRes,
    either as its text or as a child element (UIDAI's sandbox does the latter)."""
    resp = xmldsig.parse(body)
    if resp.tag != "Resp":
        raise xmldsig.SignatureError("shape", "expected Resp")
    status = resp.get("status") or "-1"
    err = resp.get("err") or None
    payload = (resp.text or "").strip()
    if not payload:
        child = next((c for c in resp if isinstance(c.tag, str)), None)
        payload = (child.text or "").strip() if child is not None else ""
    if not payload:
        return status, err, None
    try:
        decoded = base64.b64decode(payload)
    except ValueError:
        return status, err or "encrypted", None
    if not decoded.lstrip().startswith(b"<"):
        # Still encrypted: the ASA did not decrypt it. See ``kyc_request``.
        return status, err or "encrypted", None
    return status, err, xmldsig.parse(decoded)
