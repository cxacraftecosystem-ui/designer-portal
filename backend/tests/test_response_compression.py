import gzip
import json

import pytest
from fastapi import FastAPI
from fastapi.responses import JSONResponse, Response
from fastapi.testclient import TestClient

from app.main import SelectiveGZipMiddleware

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

    @app.get("/partial", status_code=206)
    async def partial_route() -> Response:
        body = _BIG.encode()[:2048]
        return Response(
            content=body,
            status_code=206,
            media_type="text/plain",
            headers={"Content-Range": f"bytes 0-{len(body) - 1}/{len(_BIG.encode())}"},
        )

    return TestClient(app)


def test_a_json_response_is_compressed_and_survives_the_round_trip(client):
    response = client.get("/json", headers={"Accept-Encoding": "gzip"})
    assert response.status_code == 200
    assert response.headers["content-encoding"] == "gzip"
    assert json.loads(response.text)["prose"] == _BIG
    assert int(response.headers["content-length"]) < len(_BIG)


def test_a_client_that_did_not_ask_for_gzip_gets_none(client):
    response = client.get("/json", headers={"Accept-Encoding": "identity"})
    assert "content-encoding" not in response.headers
    assert json.loads(response.text)["prose"] == _BIG


def test_a_docx_is_left_alone(client):
    response = client.get("/docx", headers={"Accept-Encoding": "gzip"})
    assert response.status_code == 200
    assert "content-encoding" not in response.headers
    assert response.content == _BIG.encode()


def test_a_body_below_the_floor_is_left_alone(client):
    response = client.get("/tiny", headers={"Accept-Encoding": "gzip"})
    assert "content-encoding" not in response.headers
    assert response.json() == {"ok": True}


def test_an_already_encoded_body_is_never_encoded_twice(client):
    response = client.get("/preencoded", headers={"Accept-Encoding": "gzip"})
    assert response.headers["content-encoding"] == "gzip"
    assert response.text == _BIG


def test_a_bodiless_response_is_untouched(client):
    response = client.get("/nocontent", headers={"Accept-Encoding": "gzip"})
    assert response.status_code == 204
    assert "content-encoding" not in response.headers


def test_a_partial_response_keeps_its_byte_offsets(client):
    response = client.get("/partial", headers={"Accept-Encoding": "gzip"})
    assert response.status_code == 206
    assert "content-encoding" not in response.headers
    assert response.content == _BIG.encode()[:2048]
    start, _, total = response.headers["content-range"].removeprefix("bytes ").partition("/")
    assert start == f"0-{len(response.content) - 1}"
    assert int(response.headers["content-length"]) == len(response.content)
    assert int(total) == len(_BIG.encode())


def test_vary_is_added_so_a_shared_cache_cannot_cross_the_variants(client):
    response = client.get("/json", headers={"Accept-Encoding": "gzip"})
    assert "accept-encoding" in response.headers["vary"].lower()


def test_an_existing_vary_is_appended_to_rather_than_replaced(client):
    response = client.get("/varied", headers={"Accept-Encoding": "gzip"})
    vary = response.headers["vary"].lower()
    assert "origin" in vary
    assert "accept-encoding" in vary
