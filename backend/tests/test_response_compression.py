"""Compression: what is compressed, what is deliberately not, and what must never be.

WHY THIS EXISTS. The API served no compressed responses of any kind — it ignored `Accept-Encoding`,
and the production nginx has no `gzip` directive covering JSON. Measured over ten real responses,
2,548 KB became 412 KB: **6.2x**. The number that matters is not the ratio but the link, because
this application is used in villages on a mobile connection: at 40 kB/s those ten responses take
63.7 s uncompressed and 10.3 s compressed. No query tuning reaches that — the database was measured
at 2-25 ms per request and is not the bottleneck.

The risk in a middleware that touches EVERY response is that it corrupts one. So the tests below are
mostly about restraint: the allowlist, the already-encoded body, the bodiless status codes, and the
`Vary` header without which a shared cache serves a gzipped body to a client that never asked for
one.
"""

import gzip
import json

import pytest
from fastapi import FastAPI
from fastapi.responses import JSONResponse, Response
from fastapi.testclient import TestClient

from app.main import SelectiveGZipMiddleware

#: Comfortably over the 1 KiB floor, and incompressible-looking enough that a passing test cannot be
#: an accident of a tiny body.
_BIG = "Sambalpuri bandha, Barpali. " * 200


@pytest.fixture
def client() -> TestClient:
    app = FastAPI()
    app.add_middleware(SelectiveGZipMiddleware)

    @app.get("/json")
    async def json_route() -> JSONResponse:
        return JSONResponse({"prose": _BIG})

    @app.get("/docx")
    async def docx_route() -> Response:
        return Response(
            content=_BIG.encode(),
            media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

    @app.get("/tiny")
    async def tiny_route() -> JSONResponse:
        return JSONResponse({"ok": True})

    @app.get("/preencoded")
    async def preencoded_route() -> Response:
        return Response(
            content=gzip.compress(_BIG.encode()),
            media_type="application/json",
            headers={"content-encoding": "gzip"},
        )

    @app.get("/nocontent", status_code=204)
    async def nocontent_route() -> Response:
        return Response(status_code=204)

    @app.get("/varied")
    async def varied_route() -> JSONResponse:
        return JSONResponse({"prose": _BIG}, headers={"Vary": "Origin"})

    # `raise_server_exceptions=False` is not needed; nothing here raises.
    return TestClient(app)


def test_a_json_response_is_compressed_and_survives_the_round_trip(client):
    response = client.get("/json", headers={"Accept-Encoding": "gzip"})
    assert response.status_code == 200
    assert response.headers["content-encoding"] == "gzip"
    # httpx decodes transparently, so this asserts the bytes were VALID gzip of the right payload —
    # a corrupted body would raise here rather than merely differ.
    assert json.loads(response.text)["prose"] == _BIG
    # And it actually saved something; a "compressed" response larger than the original would mean
    # the floor is doing nothing.
    assert int(response.headers["content-length"]) < len(_BIG)


def test_a_client_that_did_not_ask_for_gzip_gets_none(client):
    response = client.get("/json", headers={"Accept-Encoding": "identity"})
    assert "content-encoding" not in response.headers
    assert json.loads(response.text)["prose"] == _BIG


def test_a_docx_is_left_alone(client):
    """The allowlist's whole purpose.

    A .docx is already a ZIP container, so re-compressing it yields nothing — and it is produced by
    the one endpoint measured as ALREADY CPU-bound (~780 ms in the report builder against ~12 ms of
    query time). Spending more CPU there to save no bytes is the worst possible trade.
    """
    response = client.get("/docx", headers={"Accept-Encoding": "gzip"})
    assert response.status_code == 200
    assert "content-encoding" not in response.headers
    assert response.content == _BIG.encode()


def test_a_body_below_the_floor_is_left_alone(client):
    """The gzip header alone is 18 bytes; a small JSON body comes out LARGER."""
    response = client.get("/tiny", headers={"Accept-Encoding": "gzip"})
    assert "content-encoding" not in response.headers
    assert response.json() == {"ok": True}


def test_an_already_encoded_body_is_never_encoded_twice(client):
    """Double-encoding produces a body no client can read, and the failure is silent until a user
    sees mojibake. The upstream `content-encoding` is the signal, and it must be honoured."""
    response = client.get("/preencoded", headers={"Accept-Encoding": "gzip"})
    assert response.headers["content-encoding"] == "gzip"
    # One layer of gzip, not two: httpx strips exactly one, leaving the original text.
    assert response.text == _BIG


def test_a_bodiless_response_is_untouched(client):
    response = client.get("/nocontent", headers={"Accept-Encoding": "gzip"})
    assert response.status_code == 204
    assert "content-encoding" not in response.headers


def test_vary_is_added_so_a_shared_cache_cannot_cross_the_variants(client):
    response = client.get("/json", headers={"Accept-Encoding": "gzip"})
    assert "accept-encoding" in response.headers["vary"].lower()


def test_an_existing_vary_is_appended_to_rather_than_replaced(client):
    """CORS sets `Vary: Origin`. Overwriting it would let a cache serve one origin's response to
    another, which is a correctness bug far worse than the bytes this middleware saves."""
    response = client.get("/varied", headers={"Accept-Encoding": "gzip"})
    vary = response.headers["vary"].lower()
    assert "origin" in vary
    assert "accept-encoding" in vary
