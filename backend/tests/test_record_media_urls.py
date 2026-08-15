"""Whether a product, tool or process photograph comes back with a URL anybody can fetch.

THE DEFECT THIS PINS. ``public_encode(obj)`` called with NO viewer takes the branch that sets
``allowed = set()`` — an EMPTY set, not ``None`` — because that is the cheapest safe answer for a
route that has not thought about the question. ``_redact_sensitive`` then drops ``url``,
``publicUrl`` and ``objectKey`` from every node carrying the ``objectKey`` marker whose
``uploadedById`` is not in the set, and an empty set contains nobody. ``MediaFile.objectKey`` is
``String @unique`` and non-nullable, so the marker fires on every media node there is.

products.py, tools.py and processes.py called it that way on every single response. Both
``RELATIONS`` tuples declare ``media``, and ``processes._hydrate`` loads the process's and each
step's media by ``linkedRecordId``, so the rows travelled and the URL was stripped off all of them —
for the designer who had uploaded the file seconds earlier and for a MASTER_ADMIN alike, since the
viewer-less branch is taken BEFORE any rank test. ``products/page.tsx`` and ``tools/page.tsx`` build
their tiles from ``media.url``, and ``ProcessForm`` seeds each step's ``existingMedia`` from
``step.media`` and — unlike every other edit page — never re-fetches through ``GET /media``, which
does pass a viewer. So the UI proved a photograph existed and then refused to render or download it,
which reads as broken storage rather than as a policy.

THE POLICY IS NOT BEING RELAXED, IT IS BEING APPLIED. Reading the repository is open to every
signed-in account; TAKING a file out of it is not, and a ``url`` IS the file. The last two tests are
the ones that keep that true: a stranger with no grant still gets the row and no URL, and
``objectKey`` goes with it, because ``public_url_for_key`` is the CDN host plus the key and handing
over one hands over the other.

These need Postgres: what is under test is which keys a real response carries. The module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_media_entitlement`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

EVERY ACCOUNT HERE IS A DESIGNER EXCEPT THE ADMIN, ON PURPOSE. ``media_url_owners`` returns "all
allowed" without touching the database for PROFESSOR and above, so a test written with the ADMIN
account most of this suite uses would pass against the unfixed code for the wrong reason — and would
also miss the grant path entirely, since a professor never needs a grant. DESIGNER is rank 35, below
PROFESSOR's 40: the rank a real designer actually holds.
"""

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
    """One completed IMAGE upload. ``url`` is populated exactly as ``POST /media/complete`` leaves
    it — ``data["url"] = public_url_for_key(data["objectKey"])`` — so what these tests observe is a
    real column being withheld, not a column that was never filled."""
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
    """A designer with a photographed product, tool and process; a stranger who granted them access;
    an outsider who granted nobody anything; and an admin.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.
    """
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
        # The grant the cheap ``viewer``-derived default cannot see: it allows only the caller's OWN
        # uploads, so without ``media_url_owners`` a grantee is handed the row and refused the file.
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
        # Process media is linked by ``linkedRecordType``/``linkedRecordId``, not by a foreign key —
        # ``process`` for the pre-process clips and ``processstep`` for each step.
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

    # ONE TestClient, four tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
    with TestClient(app) as client:
        yield {
            "client": client,
            # Every list query below narrows by this, not by a word: the database is shared with the
            # rest of the suite and a search for "yardage" would sooner or later return a page of
            # somebody else's rows with ours pushed off the end.
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
    """The TestClient bound to one account's token, so a test reads as "as the designer"."""

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


# --------------------------------------------------------------------------------------
# 1. The uploader's own files, on every surface that shows them
#
# Each of these fails against the old code with `url` absent from the node — which is the exact
# shape the browser saw: the tile rendered, and there was nothing behind it.
# --------------------------------------------------------------------------------------


def test_the_products_list_carries_a_fetchable_url_for_the_uploaders_own_photograph(env, client):
    response = client.get(f"/api/products?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["product_id"]), env["product_photo"])
    assert photo["url"], "products/page.tsx builds its tile from exactly this key"


def test_dropping_the_viewer_again_brings_the_defect_straight_back(env, client, monkeypatch):
    """THE OLD BEHAVIOUR, REPRODUCED IN PROCESS, so this file cannot quietly stop testing anything.

    ``assert photo["url"]`` passes for two very different reasons — because the route names its
    caller, or because somebody later made ``public_encode`` permissive by default. Only one of those
    is the fix. Discarding the viewer and the resolved grant set at the call site, which is precisely
    what ``public_encode(items)`` used to do, must put the defect back: the media row still travels
    and the ``url`` is gone.

    Reproduced by patching the module global rather than by editing the file: the working tree is
    shared with other work, and a route encoding without its caller is not a thing to leave lying
    around for even one test run.
    """
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
    """The PATCH response carries ``media`` (it is in ``INCLUDE``), so a URL present before the save
    and absent in the answer to it reads as the save having destroyed the file."""
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
    """The worst of the three, because there is no second source. Every other edit page mounts
    ``components/media/ExistingMedia.tsx``, which re-fetches through ``GET /media`` and would have
    rescued it; ``ProcessForm`` seeds ``existingPreMedia`` from ``initial.media`` and each step's
    ``existingMedia`` from ``step.media``, so this response is the only place those photographs can
    come from on the web."""
    response = client.get(f"/api/processes/{env['process_id']}")
    assert response.status_code == 200, response.text
    body = response.json()

    assert _photo(body, env["process_photo"])["url"]
    step = [s for s in body["steps"] if s["id"] == env["step_id"]][0]
    assert _photo(step, env["step_photo"])["url"]


def test_an_admin_is_handed_the_url_as_well(env, admin_client):
    """The viewer-less branch was taken BEFORE any rank test, so rank was irrelevant and a
    MASTER_ADMIN saw exactly what a stranger saw: the row, and no way to open it."""
    response = admin_client.get(f"/api/products/{env['product_id']}")
    assert response.status_code == 200, response.text
    assert _photo(response.json(), env["product_photo"])["url"]


# --------------------------------------------------------------------------------------
# 2. Somebody else's files
# --------------------------------------------------------------------------------------


def test_a_grantee_can_open_the_photographs_of_the_researcher_who_shared_with_them(env, client):
    """This is what ``media_urls=await media_url_owners(current_user)`` buys over the cheap default,
    and the reason these routes resolve the grant set instead of passing the viewer alone: the
    viewer-derived default allows only the caller's OWN uploads, so a colleague who has granted this
    designer access to their whole dataset would still hand over a list of unopenable tiles."""
    response = client.get(f"/api/products?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["shared_product_id"]), env["shared_photo"])
    assert photo["url"]


def test_a_caller_with_no_grant_still_gets_the_row_and_still_gets_no_url(env, outsider_client):
    """THE POLICY, ASSERTED SO THE FIX CANNOT DRIFT INTO A LEAK. Reading is open to everyone, so the
    media row travels — name, type, uploader, which record it belongs to. The file does not."""
    response = outsider_client.get(f"/api/products?search={env['stamp']}")
    assert response.status_code == 200, response.text

    photo = _photo(_find(response.json()["items"], env["shared_product_id"]), env["shared_photo"])
    assert photo["originalFilename"] == "shared.jpg", "the ROW must still travel"
    assert "url" not in photo
    assert "publicUrl" not in photo


def test_the_object_key_is_withheld_with_the_url_and_not_instead_of_it(env, outsider_client):
    """``s3.public_url_for_key`` is the CDN host plus the key, and the host is public knowledge, so
    handing over the key hands over the file one string concatenation later. Dropping the URL and
    leaving the key would be a lock on the door beside an open window."""
    response = outsider_client.get(f"/api/processes/{env['process_id']}")
    assert response.status_code == 200, response.text

    photo = _photo(response.json(), env["process_photo"])
    assert "objectKey" not in photo
    assert "url" not in photo
