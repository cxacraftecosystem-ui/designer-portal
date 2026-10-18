"""``s3.object_exists`` tells a removed object from storage that could not answer.

The Ministry pack link route turns ``False`` into 410 ("files removed") and ``None`` into 503, so a
permission error or a timeout must never read as "missing".
"""

from __future__ import annotations

from typing import Any

import pytest
from botocore.exceptions import ClientError, EndpointConnectionError

from app.services import s3


class _Client:
    def __init__(self, error: Exception | None) -> None:
        self.error = error

    def head_object(self, **kwargs: Any) -> dict[str, Any]:
        if self.error is not None:
            raise self.error
        return {"ContentLength": 4}


def _client_error(code: str, status: int) -> ClientError:
    return ClientError(
        {"Error": {"Code": code}, "ResponseMetadata": {"HTTPStatusCode": status}}, "HeadObject"
    )


@pytest.mark.parametrize(
    ("error", "expected"),
    [
        (None, True),
        (_client_error("404", 404), False),
        (_client_error("NoSuchKey", 404), False),
        (_client_error("403", 403), None),
        (_client_error("AccessDenied", 403), None),
        (EndpointConnectionError(endpoint_url="https://s3.example"), None),
        (TimeoutError("slow"), None),
    ],
)
def test_object_exists_separates_missing_from_unknown(
    monkeypatch: pytest.MonkeyPatch, error: Exception | None, expected: bool | None
) -> None:
    monkeypatch.setattr(s3, "_client", lambda: _Client(error))
    assert s3.object_exists("exports/ministry/2026-09/OTHER/r1/pack.pdf") is expected
