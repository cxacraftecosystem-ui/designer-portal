"""Aadhaar verification: offline (Secure QR, Paperless Offline e-KYC) and online (OTP via an ASA).

Read docs/AADHAAR.md first. The layout:

* ``flags``       — the two server-side switches, the 404 they put in front of every route, and the
                    stripping of the result columns from payloads while both are off.
* ``identity``    — the one shape every method produces (``VerifiedIdentity``) and the one error
                    every method raises (``AadhaarError``).
* ``certs``       — UIDAI's published signing certificates, pinned in ``app/data/uidai/``.
* ``secure_qr``   — parse and verify the Secure QR on the card or e-Aadhaar.
* ``offline_xml`` — open the share-code ZIP, verify the XMLDSig, check the optional hashes.
* ``xmldsig``     — the enveloped XML signature, verified (offline XML) and created (online auth).
* ``names``       — the tolerant name comparison.
* ``records``     — writing the minimal result onto a User or Artisan, the admin decision, the audit.
* ``online``      — the AUA client: PID encryption, Auth/OTP/e-KYC XML, the ASA call, error codes.

NOTHING IN THIS PACKAGE PERSISTS A PAYLOAD. Every function takes bytes or text in memory and returns
a ``VerifiedIdentity``; the caller writes only that dataclass's fields.
"""
