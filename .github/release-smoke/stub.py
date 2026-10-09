#!/usr/bin/env python3
"""A stand-in for the design-workshop API, for the TEMPORARY release smoke job only.

The R8-shrunk release build is pointed at http://127.0.0.1:8765/api/ (an `apiBaseUrl` written into
local.properties by the job) and `adb reverse` carries the emulator's loopback to this process. Every
body below is shaped like what the FastAPI backend sends for the same route — the whole camelCase
row, including columns the handset's DTOs do not declare — so the release build's Retrofit 3, its
kotlinx-serialization converter and OkHttp 5 have to really parse it, unknown keys and all.

Every request is appended to STUB_LOG as one JSON line, which is what the driver asserts on. Link
tokens are logged by NAME ("valid", "expired", "other"), never by value.

Nothing here is production data and nothing here talks to production.
"""

from __future__ import annotations

import json
import os
import struct
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

PORT = int(os.environ.get("STUB_PORT", "8765"))
LOG_PATH = os.environ.get("STUB_LOG", "stub-requests.jsonl")
EMAIL = os.environ["SMOKE_EMAIL"]
PASSWORD = os.environ["SMOKE_PASSWORD"]
SESSION = os.environ["SMOKE_SESSION"]
LINK_VALID = os.environ["SMOKE_LINK_VALID"]
LINK_EXPIRED = os.environ["SMOKE_LINK_EXPIRED"]
HTTPS_IMAGE = os.environ["SMOKE_HTTPS_IMAGE"]
BASE = f"http://127.0.0.1:{PORT}"

NOW = "2026-10-09T09:30:00.000Z"
USER_ID = "cmsmokedesigner000000001"

GATE = {
    "state": "GRANTED",
    "required": False,
    "reason": "",
    "noticeVersion": "2026-08-30.1",
    "agreedVersion": "2026-08-30.1",
    "agreedAt": "2026-09-30T10:05:11.000Z",
    "basis": "REQUIRED_AT_SIGN_IN",
    "answerAt": "POST /api/usage/consent",
    "noticeAt": "GET /api/usage/consent/notice",
}

# `auth.serialize_user`: the whole User row minus the hash, plus the consent gate.
USER = {
    "id": USER_ID,
    "email": EMAIL,
    "name": "Smoke Designer",
    "avatarUrl": None,
    "role": "DESIGNER",
    "authProvider": "LOCAL",
    "canManageQuestionnaire": False,
    "canManageCrafts": False,
    "canManageWorkshops": False,
    "canReview": False,
    "canViewProvenance": False,
    "canDownloadDataset": False,
    "passwordSetAt": "2026-09-30T10:00:00.000Z",
    "mustChangePassword": False,
    "firstLoginAt": "2026-09-30T10:05:00.000Z",
    "sessionsValidFrom": None,
    "usageConsent": "GRANTED",
    "usageConsentAt": "2026-09-30T10:05:11.000Z",
    "usageConsentBasis": "REQUIRED_AT_SIGN_IN",
    "usageConsentVersion": "2026-08-30.1",
    "usageConsentGate": GATE,
    "createdAt": "2026-09-30T09:58:00.000Z",
    "updatedAt": NOW,
}

NOTICE = {
    "version": "2026-08-30.1",
    "title": "How this app records its own use",
    "required": True,
    "requiredSentence": "Using the repository requires agreeing to usage recording.",
    "collects": ["Which screens are opened, and when", "Which features are used"],
    "doesNotCollect": ["The content of any record", "Location", "Audio"],
    "durationCaveat": "Kept for as long as the account exists.",
    "readableBy": {"MASTER_ADMIN": "Aggregates", "MINISTRY_ADMIN": "Aggregates"},
    "withdrawal": {
        "where": "Settings > Usage recording",
        "costsNothing": "Withdrawing costs nothing.",
        "does": ["Stops new recording"],
        "doesNot": ["Delete what was recorded before"],
    },
    "retention": "Twelve months.",
    "document": "docs/USAGE_RECORDING.md",
}

PROFILE = {
    "id": "cmsmokeprofile0000000001",
    "userId": USER_ID,
    "displayName": "Smoke Designer",
    "localName": None,
    "designation": "Textile designer",
    "institution": "National Institute of Design",
    "department": "Textile and Apparel Design",
    "qualification": "M.Des",
    "specialisation": "Handloom",
    "experienceYears": 7,
    "experienceMonths": 3,
    "biography": "Works with weaving clusters in Kutch.",
    "phone": "+91 79 2662 3462",
    "email": EMAIL,
    "website": None,
    "addressLine": "Paldi",
    "city": "Ahmedabad",
    "state": "Gujarat",
    "pincode": "380007",
    "photoMediaId": "cmsmokephoto",
    "signatureMediaId": "cmsmokesignature",
    "cvMediaId": None,
    "empanelmentNo": "DC-H/2026/0042",
    "empanelmentDate": "2026-04-01",
    "empanelmentNoMissing": False,
    "locationId": None,
    "location": None,
    "createdAt": "2026-09-30T10:06:00.000Z",
    "updatedAt": NOW,
}


def media_row(media_id: str, url: str, filename: str, mime: str) -> dict:
    return {
        "id": media_id,
        "originalFilename": filename,
        "objectKey": f"media/{USER_ID}/{media_id}",
        "mediaType": "IMAGE",
        "mimeType": mime,
        "sizeBytes": 2171,
        "url": url,
        "caption": None,
        "transcriptStatus": None,
        "transcriptText": None,
        "transcriptError": None,
        "transcriptEditedAt": None,
        "uploadedById": USER_ID,
        "uploadedBy": {k: USER[k] for k in ("id", "email", "name", "role")},
        "createdAt": "2026-09-30T10:07:00.000Z",
        "linkedRecordType": None,
        "linkedRecordId": None,
    }


MEDIA = {
    "cmsmokephoto": media_row("cmsmokephoto", HTTPS_IMAGE, "photograph.png", "image/png"),
    "cmsmokesignature": media_row(
        "cmsmokesignature", f"{BASE}/smoke-media/signature.png", "signature.png", "image/png"
    ),
}

STATS = {
    "totalArtisans": 4217,
    "totalWorkshops": 38,
    "totalProductRecords": 912,
    "totalToolRecords": 377,
    "totalMediaFiles": 16044,
    "pendingSubmissions": 7,
    "mine": {
        "totalArtisans": 12,
        "totalWorkshops": 1,
        "totalProductRecords": 3,
        "totalToolRecords": 2,
        "totalMediaFiles": 40,
        "pendingSubmissions": 0,
    },
    "recentSubmissions": [
        {
            "id": "cmsmokeartisan000000001",
            "type": "artisan",
            "status": "SUBMITTED",
            "createdAt": "2026-10-08T12:00:00.000Z",
            "title": "Ramesh Vankar",
            "place": "Bhujodi, Kutch",
            "createdByName": "Smoke Designer",
        }
    ],
}

ADDRESS = {
    "version": 1,
    "states": ["Gujarat", "Maharashtra", "Rajasthan"],
    "unionTerritories": ["Delhi", "Ladakh"],
    "statesAndUnionTerritories": ["Delhi", "Gujarat", "Ladakh", "Maharashtra", "Rajasthan"],
    "districts": None,
}

PREFERENCES = {
    "id": "cmsmokeprefs00000000001",
    "userId": USER_ID,
    "updatedAt": NOW,
    "theme": "light",
    "reducedMotion": True,
    "largerText": False,
    "highContrast": False,
}

# The update check must find nothing newer than what is installed, or a prompt that cannot be
# dismissed covers the screens under test.
RELEASE = {
    "versionCode": 1,
    "versionName": "0.0.1",
    "url": None,
    "notes": None,
    "objectKey": None,
    "sizeBytes": None,
}


def png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """A solid-colour PNG, written without any imaging library."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


SIGNATURE_PNG = png(240, 120, (255, 0, 255))
LOCK = threading.Lock()


def link_name(token: str | None) -> str:
    if token is None:
        return "absent"
    if token == LINK_VALID:
        return "valid"
    if token == LINK_EXPIRED:
        return "expired"
    return "other"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "uvicorn"
    sys_version = ""

    def log_message(self, fmt: str, *args) -> None:  # the JSONL log replaces the default one
        return

    def _send(self, status: int, payload, content_type: str = "application/json") -> None:
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        self._status = status

    def _record(self, extra: dict) -> None:
        entry = {
            "at": round(time.time(), 3),
            "method": self.command,
            "path": urlsplit(self.path).path,
            "status": getattr(self, "_status", None),
            "userAgent": self.headers.get("User-Agent", ""),
            "bearer": (self.headers.get("Authorization") == f"Bearer {SESSION}"),
        }
        entry.update(extra)
        with LOCK, open(LOG_PATH, "a", encoding="utf-8") as log:
            log.write(json.dumps(entry) + "\n")
        print(json.dumps(entry), flush=True)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            parsed = json.loads(raw.decode("utf-8"))
            return parsed if isinstance(parsed, dict) else {"_": parsed}
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {"_unparsed": len(raw)}

    def _authorised(self) -> bool:
        return self.headers.get("Authorization") == f"Bearer {SESSION}"

    def _route(self) -> None:
        parts = urlsplit(self.path)
        path, query = parts.path, parse_qs(parts.query)
        body = self._body() if self.command in ("POST", "PUT", "PATCH") else {}
        extra: dict = {}
        try:
            if path == "/healthz":
                self._send(200, {"ok": True})
            elif path == "/smoke-media/signature.png" and self.command in ("GET", "HEAD"):
                self._send(200, SIGNATURE_PNG, "image/png")
            elif path == "/api/auth/set-password/check" and self.command == "POST":
                # The route this backend does not have yet: FastAPI's own answer for a missing route.
                extra["link"] = link_name(body.get("token"))
                extra["queryHasToken"] = "token" in query
                self._send(404, {"detail": "Not Found"})
            elif path == "/api/auth/set-password" and self.command == "GET":
                token = (query.get("token") or [None])[0]
                extra["link"] = link_name(token)
                if token == LINK_VALID:
                    self._send(200, {"valid": True, "reason": None, "purpose": "INVITE"})
                elif token == LINK_EXPIRED:
                    self._send(200, {"valid": False, "reason": "expired", "purpose": "RESET"})
                else:
                    self._send(200, {"valid": False, "reason": "malformed", "purpose": None})
            elif path == "/api/auth/login" and self.command == "POST":
                extra["identifierMatched"] = body.get("email") == EMAIL
                if body.get("email") == EMAIL and body.get("password") == PASSWORD:
                    self._send(200, {"accessToken": SESSION, "tokenType": "bearer", "user": USER})
                else:
                    self._send(401, {"detail": "Incorrect email or password."})
            elif path == "/api/usage/consent/notice" and self.command == "GET":
                self._send(200, NOTICE)
            elif path == "/api/app/release/latest" and self.command == "GET":
                self._send(200, RELEASE)
            elif not self._authorised():
                self._send(401, {"detail": "Not authenticated"})
            elif path in ("/api/me", "/api/auth/me") and self.command == "GET":
                self._send(200, USER)
            elif path == "/api/dashboard/stats" and self.command == "GET":
                self._send(200, STATS)
            elif path == "/api/preferences/me" and self.command in ("GET", "PUT"):
                self._send(200, {**PREFERENCES, **{k: v for k, v in body.items() if k in PREFERENCES}})
            elif path == "/api/designers/me/profile" and self.command == "GET":
                self._send(200, PROFILE)
            elif path.startswith("/api/media/") and self.command == "GET" and path[len("/api/media/"):] in MEDIA:
                self._send(200, MEDIA[path[len("/api/media/"):]])
            elif path == "/api/reference/address" and self.command == "GET":
                self._send(200, ADDRESS)
            else:
                extra["unhandled"] = True
                self._send(404, {"detail": "Not Found"})
        except Exception as error:  # a stub bug must show up as a 500 in the log, not a hang
            extra["stubError"] = repr(error)
            self._send(500, {"detail": "stub error"})
        self._record(extra)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = _route


def main() -> None:
    open(LOG_PATH, "w", encoding="utf-8").close()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"stub listening on {BASE}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    sys.exit(0)


if __name__ == "__main__":
    main()
