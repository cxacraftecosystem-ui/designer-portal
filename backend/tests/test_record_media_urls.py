import os
import uuid

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _media(uploader_id: str, stamp: str, name: str, **links):
    key = f"media/{uploader_id}/{stamp}-{name}"
    return await db.mediafile.create(data={
        "originalFilename": name,
        "mediaType": "IMAGE",
        "mimeType": "image/jpeg",
        "sizeBytes": 4096,
        "bucket": "test-bucket",
        "objectKey": key,
        "url": f"https://cdn.example.test/{key}",
        "uploadedById": uploader_id,
        **links,
    })


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        designer = await db.user.create(data={
            "email": f"url-designer-{stamp}@example.org", "name": "Url Designer",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        sharer = await db.user.create(data={
            "email": f"url-sharer-{stamp}@example.org", "name": "Url Sharer",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        outsider = await db.user.create(data={
            "email": f"url-outsider-{stamp}@example.org", "name": "Url Outsider",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        admin = await db.user.create(data={
            "email": f"url-admin-{stamp}@example.org", "name": "Url Admin",
            "role": "ADMIN", "passwordHash": hash_password("unused"),
        })
        await db.dataaccessgrant.create(data={
            "ownerId": sharer.id, "granteeId": designer.id,
            "tier": "DOWNLOAD", "status": "GRANTED", "allData": True,
        })

        product = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "productName": f"Printed yardage {stamp}", "createdById": designer.id,
        })
        product_photo = await _media(designer.id, stamp, "yardage.jpg", productId=product.id)

        shared_product = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Sita Devi",
            "productName": f"Shared yardage {stamp}", "createdById": sharer.id,
        })
        shared_photo = await _media(sharer.id, stamp, "shared.jpg", productId=shared_product.id)

        tool = await db.tooldocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "toolkitName": f"Printing blocks {stamp}", "createdById": designer.id,
        })
        tool_photo = await _media(designer.id, stamp, "blocks.jpg", toolId=tool.id)

        process = await db.process.create(data={
            "name": f"Printing sequence {stamp}",
            "productId": product.id,
            "createdById": designer.id,
        })
        step = await db.processstep.create(data={
            "processId": process.id, "name": "Print the border", "sortOrder": 1,
        })
        process_photo = await _media(
            designer.id, stamp, "pre-process.jpg",
            linkedRecordType="process", linkedRecordId=process.id,
        )
        step_photo = await _media(
            designer.id, stamp, "border.jpg",
            linkedRecordType="processstep", linkedRecordId=step.id,
        )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "stamp": stamp,
            "designer": create_access_token(subject=designer.id),
            "outsider": create_access_token(subject=outsider.id),
            "admin": create_access_token(subject=admin.id),
            "product_id": product.id,
            "product_photo": product_photo.id,
            "shared_product_id": shared_product.id,
            "shared_photo": shared_photo.id,
            "tool_id": tool.id,
            "tool_photo": tool_photo.id,
            "process_id": process.id,
            "step_id": step.id,
            "process_photo": process_photo.id,
            "step_photo": step_photo.id,
        }


class _As:
    def __init__(self, client, token: str) -> None:
        self._client = client
        self._headers = {"Authorization": f"Bearer {token}"}

    def get(self, url: str):
        return self._client.get(url, headers=self._headers)

    def patch(self, url: str, json: dict):
        return self._client.patch(url, json=json, headers=self._headers)


@pytest.fixture
def client(env):
    return _As(env["client"], env["designer"])


@pytest.fixture
def outsider_client(env):
    return _As(env["client"], env["outsider"])


@pytest.fixture
def admin_client(env):
    return _As(env["client"], env["admin"])


def _find(items: list[dict], record_id: str) -> dict:
    match = [item for item in items if item["id"] == record_id]
    assert match, f"{record_id} is not on the page"
    return match[0]


def _photo(record: dict, media_id: str) -> dict:
    match = [m for m in record.get("media") or [] if m["id"] == media_id]
    assert match, f"the record carries no media node for {media_id}"
    return match[0]



def test_the_products_list_carries_a_fetchable_url_for_the_uploaders_own_photograph(env, client):
    response = client.get(f"/api/products?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["product_id"]), env["product_photo"])
    assert photo["url"], "products/page.tsx builds its tile from exactly this key"


def test_dropping_the_viewer_again_brings_the_defect_straight_back(env, client, monkeypatch):
    import app.api.routes.products as products_route

    real = products_route.public_encode
    monkeypatch.setattr(
        products_route, "public_encode", lambda obj, *args, **kwargs: real(obj)
    )

    response = client.get(f"/api/products?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["product_id"]), env["product_photo"])
    assert photo["originalFilename"] == "yardage.jpg", "the ROW travelled even before the fix"
    assert "url" not in photo, "the viewer is not what is putting the URL on the wire above"


def test_the_product_detail_carries_it_too(env, client):
    response = client.get(f"/api/products/{env['product_id']}")
    assert response.status_code == 200, response.text
    assert _photo(response.json(), env["product_photo"])["url"]


def test_saving_a_product_does_not_take_the_url_off_the_response(env, client):
    response = client.patch(f"/api/products/{env['product_id']}", json={"remarks": "Re-measured."})
    assert response.status_code == 200, response.text
    assert _photo(response.json(), env["product_photo"])["url"]


def test_the_tools_list_carries_a_fetchable_url_for_the_uploaders_own_photograph(env, client):
    response = client.get(f"/api/tools?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["tool_id"]), env["tool_photo"])
    assert photo["url"], "tools/page.tsx builds its tile from exactly this key"


def test_the_tool_detail_carries_it_too(env, client):
    response = client.get(f"/api/tools/{env['tool_id']}")
    assert response.status_code == 200, response.text
    assert _photo(response.json(), env["tool_photo"])["url"]


def test_the_process_detail_carries_urls_for_the_process_AND_its_steps(env, client):
    response = client.get(f"/api/processes/{env['process_id']}")
    assert response.status_code == 200, response.text
    body = response.json()

    assert _photo(body, env["process_photo"])["url"]
    step = [s for s in body["steps"] if s["id"] == env["step_id"]][0]
    assert _photo(step, env["step_photo"])["url"]


def test_an_admin_is_handed_the_url_as_well(env, admin_client):
    response = admin_client.get(f"/api/products/{env['product_id']}")
    assert response.status_code == 200, response.text
    assert _photo(response.json(), env["product_photo"])["url"]



def test_a_grantee_can_open_the_photographs_of_the_researcher_who_shared_with_them(env, client):
    response = client.get(f"/api/products?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["shared_product_id"]), env["shared_photo"])
    assert photo["url"]


def test_a_caller_with_no_grant_still_gets_the_row_and_still_gets_no_url(env, outsider_client):
    response = outsider_client.get(f"/api/products?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["shared_product_id"]), env["shared_photo"])
    assert photo["originalFilename"] == "shared.jpg", "the ROW must still travel"
    assert "url" not in photo
    assert "publicUrl" not in photo


def test_the_object_key_is_withheld_with_the_url_and_not_instead_of_it(env, outsider_client):
    response = outsider_client.get(f"/api/processes/{env['process_id']}")
    assert response.status_code == 200, response.text

    photo = _photo(response.json(), env["process_photo"])
    assert "objectKey" not in photo
    assert "url" not in photo
