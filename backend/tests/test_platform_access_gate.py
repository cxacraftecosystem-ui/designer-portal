import os
import re
import uuid
from pathlib import Path
from typing import Any

import pytest

from app.api.routes import auth as auth_routes
from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster, signin_role

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "access-gate-test-password"

MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "prisma" / "migrations" / "20260816090000_platform_access_roster" / "migration.sql"
)

PENDING_DETAIL = (
    "Your access request is awaiting administrator approval. This is not a password problem — an "
    "administrator has to approve this address before you can sign in."
)
REJECTED_DETAIL = (
    "Your access request was reviewed and not approved. Contact the administrator if you believe "
    "this is a mistake."
)
BARRED_DETAIL = "Your access to this application has been suspended. Contact the administrator."
NOT_RECORDED_DETAIL = (
    "Access requests are temporarily closed because the approval queue is full, so this request "
    "could not be recorded. Contact the administrator directly."
)
WRONG_CREDENTIAL_DETAIL = "Invalid email or password"
DESIGNER_SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."

ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("master", "MASTER_ADMIN"),
    ("admin", "ADMIN"),
    ("professor", "PROFESSOR"),
    ("designer", "DESIGNER"),
    ("researcher", "RESEARCHER"),
    ("contributor", "FIELD_CONTRIBUTOR"),
    ("volunteer", "CROWDSOURCE_VOLUNTEER"),
    ("pending", "RESEARCHER"),
    ("rejected", "RESEARCHER"),
    ("barred", "RESEARCHER"),
    ("masterNoRow", "MASTER_ADMIN"),
    ("designerSuspended", "DESIGNER"),
    ("adminBarred", "ADMIN"),
    ("adminRejected", "ADMIN"),
    ("masterBarred", "MASTER_ADMIN"),
    ("designerUnempanelled", "DESIGNER"),
    ("adminFlagged", "ADMIN"),
    ("masterFlagged", "MASTER_ADMIN"),
)

FLAGGED: tuple[str, ...] = ("adminFlagged", "masterFlagged")

REFUSED: tuple[tuple[str, str], ...] = (
    ("pending", "PENDING"),
    ("rejected", "REJECTED"),
    ("barred", "SUSPENDED"),
    ("adminBarred", "SUSPENDED"),
    ("adminRejected", "REJECTED"),
    ("masterBarred", "SUSPENDED"),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _grandfather_statements() -> list[str]:
    sql = MIGRATION.read_text(encoding="utf-8")
    body = "\n".join(line for line in sql.splitlines() if not line.strip().startswith("--"))
    statements = [s.strip() for s in body.split(";") if s.strip()]
    inserts = [s for s in statements if re.match(r"(?is)^insert\s+into", s)]
    assert len(inserts) == 2, (
        f"expected the two grandfathering INSERTs in {MIGRATION.name}, found {len(inserts)}; if the "
        "migration was restructured, this test is no longer proving that it admits anybody"
    )
    return inserts


async def _grandfather() -> None:
    for statement in _grandfather_statements():
        await db.execute_raw(statement)


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"access-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": f"Access {slug} {stamp}",
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        for slug in ("designer", "designerSuspended"):
            await db.designerroster.create(data={"email": address(slug), "isActive": True})

        await _grandfather()

        for slug, state in REFUSED:
            await db.accessroster.update_many(
                where={"email": address(slug)}, data={"status": state}
            )
        await db.accessroster.delete_many(where={"email": address("masterNoRow")})
        await db.designerroster.update_many(
            where={"email": address("designerSuspended")}, data={"isActive": False}
        )
        for slug in FLAGGED:
            await db.user.update(
                where={"id": people[slug].id}, data={"mustChangePassword": True}
            )
        await db.designerroster.create(data={"email": address("empanelled"), "isActive": True})
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _login(
    client: Any, email: str, password: str = PASSWORD, headers: dict[str, str] | None = None
) -> Any:
    return client.post(
        "/api/auth/login", json={"email": email, "password": password}, headers=headers
    )


def _google(client: Any, monkeypatch: Any, email: str, name: str = "Google Person") -> Any:
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": name},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


def _rows(client: Any, world: dict[str, Any], term: str | None = None) -> dict[str, Any]:
    response = client.get(
        "/api/access/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _empanelments(client: Any, world: dict[str, Any]) -> dict[str, Any]:
    response = client.get(
        "/api/designers/roster",
        params={"search": world["stamp"], "pageSize": 200},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _accounts(client: Any, world: dict[str, Any], term: str) -> dict[str, Any]:
    response = client.get(
        "/api/users/directory", params={"search": term}, headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()}



@pytest.mark.parametrize("slug,role", ACCOUNTS[:7])
async def test_every_existing_role_still_signs_in_after_the_migration(world, client, slug, role):
    response = _login(client, world["address"](slug))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == role


async def test_the_grandfathered_row_carries_the_real_joining_date(world, client):
    row = _rows(client, world)[world["address"]("researcher")]
    assert row["joinedAt"] is not None
    assert row["joinedAt"][:10] == world["people"]["researcher"].createdAt.isoformat()[:10]
    assert row["status"] == "ACTIVE"
    assert row["decidedById"] is None



async def test_pending_and_wrong_password_are_different_answers(world, client):
    waiting = _login(client, world["address"]("pending"))
    assert waiting.status_code == 403, waiting.text
    assert waiting.json()["detail"] == PENDING_DETAIL

    mistyped = _login(client, world["address"]("pending"), password="not-the-password")
    assert mistyped.status_code == 401, mistyped.text
    assert mistyped.json()["detail"] == WRONG_CREDENTIAL_DETAIL

    assert waiting.json()["detail"] != mistyped.json()["detail"]


async def test_the_pending_refusal_leaks_nothing_beyond_the_wait(world, client):
    body = _login(client, world["address"]("pending")).json()
    assert set(body) == {"detail"}
    person = world["people"]["pending"]
    assert person.name not in body["detail"]
    assert person.email not in body["detail"]
    assert person.id not in body["detail"]


async def test_a_rejected_person_is_told_they_were_rejected(world, client):
    response = _login(client, world["address"]("rejected"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == REJECTED_DETAIL


async def test_a_barred_account_is_told_its_access_was_suspended(world, client):
    response = _login(client, world["address"]("barred"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == BARRED_DETAIL
    assert response.json()["detail"] != DESIGNER_SUSPENDED_DETAIL


async def test_every_refusal_is_labelled_for_the_two_sign_in_screens(world, client, monkeypatch):
    header = auth_routes.ACCESS_STATUS_HEADER

    waiting = _login(client, world["address"]("pending"))
    assert waiting.headers.get(header) == "PENDING", waiting.headers

    rejected = _login(client, world["address"]("rejected"))
    assert rejected.headers.get(header) == "REJECTED", rejected.headers

    barred = _login(client, world["address"]("barred"))
    assert barred.headers.get(header) == "SUSPENDED", barred.headers

    empanelment = _login(client, world["address"]("designerSuspended"))
    assert empanelment.headers.get(header) == "DESIGNER_SUSPENDED", empanelment.headers

    mistyped = _login(client, world["address"]("pending"), password="not-the-password")
    assert header not in mistyped.headers, mistyped.headers

    monkeypatch.setattr(access_roster, "pending_cap", lambda: 0)
    full = _google(client, monkeypatch, f"access-labelled-{world['stamp']}@example.org")
    assert full.status_code == 503, full.text
    assert full.headers.get(header) == "NOT_RECORDED", full.headers


async def test_the_label_is_readable_by_a_browser_on_the_other_origin(world, client):
    origin = "http://localhost:3000"
    response = _login(client, world["address"]("pending"), headers={"Origin": origin})
    exposed = response.headers.get("access-control-expose-headers", "")
    assert auth_routes.ACCESS_STATUS_HEADER.lower() in exposed.lower(), (
        "the refusal label must be in expose_headers (app/main.py) or the browser cannot read it, "
        f"and the sign-in page cannot tell a waiting person from a mistyped password; got {exposed!r}"
    )


async def test_a_suspended_empanelment_still_answers_in_the_empanelments_words(world, client):
    response = _login(client, world["address"]("designerSuspended"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == DESIGNER_SUSPENDED_DETAIL



async def test_a_master_admin_with_no_allow_list_row_still_signs_in(world, client):
    response = _login(client, world["address"]("masterNoRow"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "MASTER_ADMIN"
    assert world["address"]("masterNoRow") not in _rows(client, world), (
        "the exemption must not work by quietly writing the row it is exempt from"
    )


async def test_a_master_admin_is_not_gated_on_the_google_path_either(world, client, monkeypatch):
    address = world["address"]("masterNoRow")
    monkeypatch.setattr(
        auth_routes, "role_for_email", lambda email: "MASTER_ADMIN" if email == address else "X"
    )
    response = _google(client, monkeypatch, address)
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "MASTER_ADMIN"


async def test_a_master_admin_with_a_suspended_row_still_signs_in(world, client):
    row_id = _rows(client, world)[world["address"]("master")]["id"]
    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_headers(world, "admin"))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"

    response = _login(client, world["address"]("master"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "MASTER_ADMIN"



async def test_an_unknown_google_address_gets_a_pending_row_and_no_account(
    world, client, monkeypatch
):
    stranger = f"access-stranger-{world['stamp']}@example.org"
    response = _google(client, monkeypatch, stranger, name="Definitely Not Stored")
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == PENDING_DETAIL

    row = _rows(client, world, term=stranger)[stranger]
    assert row["status"] == "PENDING"
    assert row["requestedAt"] is not None
    assert row["attemptCount"] == 1
    assert stranger not in _accounts(client, world, stranger), (
        "a refused Google sign-in must not have created the account it was refused"
    )


async def test_nothing_the_caller_controls_is_stored_beyond_the_address(world, client, monkeypatch):
    stranger = f"access-noname-{world['stamp']}@example.org"
    _google(client, monkeypatch, stranger, name="APPROVE ME — urgent request from IT")
    row = _rows(client, world, term=stranger)[stranger]
    assert row["fullName"] is None
    assert row["notes"] is None


async def test_an_empanelled_designer_with_no_account_is_still_provisioned(
    world, client, monkeypatch
):
    email = world["address"]("empanelled")
    response = _google(client, monkeypatch, email, name="Newly Empanelled")
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _rows(client, world)[email]
    assert row["status"] == "ACTIVE"
    assert row["admitRole"] == "DESIGNER"
    assert row["decidedById"] is None, "no administrator saw this one; the row must not claim so"


async def test_empanelling_somebody_who_is_waiting_answers_their_request(
    world, client, monkeypatch
):
    email = f"access-late-empanel-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403
    assert _rows(client, world, term=email)[email]["status"] == "PENDING"

    empanelled = client.post(
        "/api/designers/roster", json={"email": email}, headers=_headers(world, "admin")
    )
    assert empanelled.status_code == 201, empanelled.text

    admitted = _google(client, monkeypatch, email)
    assert admitted.status_code == 200, admitted.text
    assert admitted.json()["user"]["role"] == "DESIGNER"
    assert _rows(client, world, term=email)[email]["status"] == "ACTIVE"


async def test_an_empanelment_does_not_overturn_a_rejection(world, client, monkeypatch):
    email = f"access-rejected-empanel-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403
    row_id = _rows(client, world, term=email)[email]["id"]
    assert client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "REJECT"},
        headers=_headers(world, "admin"),
    ).status_code == 200

    empanelled = client.post(
        "/api/designers/roster", json={"email": email}, headers=_headers(world, "admin")
    )
    assert empanelled.status_code == 201, empanelled.text

    still_refused = _google(client, monkeypatch, email)
    assert still_refused.status_code == 403, still_refused.text
    assert still_refused.json()["detail"] == REJECTED_DETAIL
    assert email not in _accounts(client, world, email), (
        "an empanelment must not provision an account for somebody an administrator rejected"
    )



async def test_one_address_hammering_the_form_is_one_row(world, client, monkeypatch):
    email = f"access-repeat-{world['stamp']}@example.org"
    for _ in range(3):
        assert _google(client, monkeypatch, email).status_code == 403

    listed = [row for address, row in _rows(client, world, term=email).items() if address == email]
    assert len(listed) == 1, "one address must be one row"
    assert listed[0]["attemptCount"] == 3
    assert listed[0]["lastAttemptAt"] is not None


async def test_a_rejected_person_trying_again_does_not_re_queue(world, client):
    email = world["address"]("rejected")
    before = _rows(client, world)[email]
    assert _login(client, email).status_code == 403
    after = _rows(client, world)[email]

    assert after["status"] == "REJECTED", "a rejected person must not put themselves back in the queue"
    assert after["attemptCount"] == before["attemptCount"] + 1
    assert after["requestedAt"] == before["requestedAt"], "the original request date must not move"


async def test_a_wrong_password_for_an_unknown_address_writes_nothing(world, client):
    email = f"access-nobody-{world['stamp']}@example.org"
    response = _login(client, email, password="whatever-they-guessed")
    assert response.status_code == 401, response.text
    assert response.json()["detail"] == WRONG_CREDENTIAL_DETAIL
    assert email not in _rows(client, world, term=email)


async def test_past_the_cap_the_request_is_refused_and_says_it_was_not_recorded(
    world, client, monkeypatch
):
    monkeypatch.setattr(access_roster, "pending_cap", lambda: 0)
    email = f"access-overflow-{world['stamp']}@example.org"
    response = _google(client, monkeypatch, email)
    assert response.status_code == 503, response.text
    assert response.json()["detail"] == NOT_RECORDED_DETAIL
    assert email not in _rows(client, world, term=email)

    already = world["address"]("pending")
    refused = _login(client, already)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == PENDING_DETAIL



async def test_the_pending_count_is_admin_only_and_counts_the_queue(world, client, monkeypatch):
    before = client.get("/api/access/roster/pending-count", headers=_headers(world, "admin"))
    assert before.status_code == 200, before.text
    assert isinstance(before.json()["pending"], int)

    email = f"access-counted-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403
    after = client.get("/api/access/roster/pending-count", headers=_headers(world, "admin"))
    assert after.json()["pending"] == before.json()["pending"] + 1

    refused = client.get("/api/access/roster/pending-count", headers=_headers(world, "designer"))
    assert refused.status_code == 403, refused.text


async def test_the_queue_is_a_filter_over_the_one_list(world, client, monkeypatch):
    email = f"access-queued-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403

    queued = client.get(
        "/api/access/roster",
        params={"status": "pending", "search": email, "pageSize": 50},
        headers=_headers(world, "admin"),
    )
    assert queued.status_code == 200, queued.text
    assert [row["email"] for row in queued.json()["items"]] == [email]
    assert all(row["status"] == "PENDING" for row in queued.json()["items"])

    active_only = client.get(
        "/api/access/roster",
        params={"status": "ACTIVE", "search": email},
        headers=_headers(world, "admin"),
    )
    assert active_only.json()["items"] == []

    typo = client.get(
        "/api/access/roster", params={"status": "PENDNIG"}, headers=_headers(world, "admin")
    )
    assert typo.status_code == 422, typo.text
    assert "PENDING" in typo.json()["detail"]


async def test_approving_a_request_lets_the_person_in_and_keeps_their_role(world, client):
    email = world["address"]("pending")
    row_id = _rows(client, world)[email]["id"]

    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "PROFESSOR"},
        headers=_headers(world, "admin"),
    )
    assert decided.status_code == 200, decided.text
    assert decided.json()["status"] == "ACTIVE"
    assert decided.json()["joinedAt"] is not None
    assert decided.json()["decidedById"] == world["people"]["admin"].id
    assert decided.json()["accountPromotionHeld"] == signin_role.role_kept_sentence(
        decided.json()["email"], "RESEARCHER", "PROFESSOR"
    )

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "RESEARCHER", "an approval never changes an existing account"


async def test_approving_never_demotes_an_account(world, client):
    email = world["address"]("professor")
    row_id = _rows(client, world)[email]["id"]
    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "RESEARCHER"},
        headers=_headers(world, "admin"),
    )
    assert decided.status_code == 200, decided.text
    assert _login(client, email).json()["user"]["role"] == "PROFESSOR"


async def test_an_admin_cannot_approve_somebody_above_their_own_tier(world, client):
    row_id = _rows(client, world)[world["address"]("volunteer")]["id"]
    refused = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "MASTER_ADMIN"},
        headers=_headers(world, "admin"),
    )
    assert refused.status_code == 403, refused.text


async def test_suspending_an_entry_keeps_the_record(world, client):
    email = world["address"]("contributor")
    row_id = _rows(client, world)[email]["id"]
    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_headers(world, "admin"))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"
    assert suspended.json()["joinedAt"] is not None

    refused = _login(client, email)
    assert refused.status_code == 403
    assert refused.json()["detail"] == BARRED_DETAIL
    assert email in _rows(client, world), "a suspension is not a deletion"


async def test_restoring_keeps_the_original_joining_date(world, client):
    email = world["address"]("contributor")
    row = _rows(client, world)[email]
    assert row["status"] == "SUSPENDED", "the previous test suspends this account"

    restored = client.post(
        f"/api/access/roster/{row['id']}/decision",
        json={"decision": "APPROVE"},
        headers=_headers(world, "admin"),
    )
    assert restored.status_code == 200, restored.text
    assert restored.json()["joinedAt"] == row["joinedAt"]
    assert _login(client, email).status_code == 200



async def test_an_account_an_admin_creates_can_sign_in_immediately(world, client):
    email = f"access-made-{world['stamp']}@example.org"
    created = client.post(
        "/api/users",
        json={"email": email, "name": "Made By An Admin", "password": PASSWORD,
              "role": "RESEARCHER"},
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert _rows(client, world, term=email)[email]["status"] == "ACTIVE"


async def test_correcting_an_address_does_not_lock_the_account_out(world, client):
    old = f"access-typo-{world['stamp']}@example.org"
    new = f"access-fixed-{world['stamp']}@example.org"
    created = client.post(
        "/api/users",
        json={"email": old, "name": "Typo Ridden", "password": PASSWORD, "role": "RESEARCHER"},
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    joined = _rows(client, world, term=old)[old]["joinedAt"]

    changed = client.patch(
        f"/api/users/{created.json()['id']}",
        json={"email": new},
        headers=_headers(world, "admin"),
    )
    assert changed.status_code == 200, changed.text

    signed_in = _login(client, new)
    assert signed_in.status_code == 200, signed_in.text
    moved = _rows(client, world, term=new)[new]
    assert moved["joinedAt"] == joined, "the joining date must follow the person"
    assert old not in _rows(client, world, term=old), "the row is moved, not duplicated"



def _mint(client: Any, email: str, password: str = PASSWORD) -> Any:
    return client.post("/api/datasets/token", json={"email": email, "password": password})


async def test_a_suspended_admin_cannot_mint_a_dataset_token(world, client):
    email = world["address"]("adminBarred")

    minted = _mint(client, email)
    assert minted.status_code == 403, minted.text
    assert minted.json()["detail"] == BARRED_DETAIL
    assert "accessToken" not in minted.json(), "a refusal must not carry the credential"
    assert minted.headers.get("X-Access-Status") == "SUSPENDED"

    signed_in = _login(client, email)
    assert signed_in.status_code == 403, signed_in.text
    assert signed_in.json()["detail"] == minted.json()["detail"], (
        "both doors must give a revoked admin the same answer; one open door revokes nothing"
    )

    guessed = _mint(client, email, password="not-the-password")
    assert guessed.status_code == 401, guessed.text
    assert guessed.json()["detail"] == WRONG_CREDENTIAL_DETAIL
    stranger = _mint(client, f"access-nobody-token-{world['stamp']}@example.org")
    assert stranger.status_code == 401
    assert stranger.json()["detail"] == WRONG_CREDENTIAL_DETAIL


async def test_a_rejected_admin_cannot_mint_a_dataset_token(world, client):
    email = world["address"]("adminRejected")

    minted = _mint(client, email)
    assert minted.status_code == 403, minted.text
    assert minted.json()["detail"] == REJECTED_DETAIL
    assert minted.json()["detail"] != BARRED_DETAIL
    assert minted.headers.get("X-Access-Status") == "REJECTED"
    assert "accessToken" not in minted.json()


async def test_an_admitted_admin_still_gets_a_dataset_token(world, client):
    minted = _mint(client, world["address"]("admin"))
    assert minted.status_code == 200, minted.text
    body = minted.json()
    assert body["accessToken"]
    assert body["scope"] == "dataset:read"
    assert body["account"]["email"] == world["address"]("admin")
    assert body["expiresInMinutes"] > 0


async def test_a_master_admin_with_a_suspended_row_still_mints(world, client):
    email = world["address"]("masterBarred")
    assert _rows(client, world)[email]["status"] == "SUSPENDED", (
        "the fixture must actually have barred this row, or this test proves nothing"
    )

    minted = _mint(client, email)
    assert minted.status_code == 200, minted.text
    assert minted.json()["accessToken"]
    assert minted.json()["account"]["role"] == "MASTER_ADMIN"

    assert _rows(client, world)[email]["status"] == "SUSPENDED"



async def test_an_allow_listed_designer_with_no_empanelment_is_empanelled_on_the_spot(
    world, client
):
    email = world["address"]("designerUnempanelled")
    assert email not in _empanelments(client, world), (
        "the fixture must NOT have empanelled this account, or this test is asserting nothing "
        "about auto-empanelment and would keep passing if the feature were deleted"
    )

    response = _login(client, email)
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _empanelments(client, world)[email]
    assert row["isActive"] is True
    assert row["addedById"] is None, "nobody administered this; naming an admin would be a lie"
    assert "automatically" in (row["notes"] or ""), row["notes"]
    assert row["firstSeenAt"] is not None


async def test_the_allow_list_never_revives_a_suspended_empanelment(world, client):
    email = world["address"]("designerSuspended")
    before = _empanelments(client, world)[email]
    assert before["isActive"] is False, (
        "the fixture must actually have suspended this empanelment, or this test proves nothing"
    )

    response = _login(client, email)
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == DESIGNER_SUSPENDED_DETAIL
    assert response.headers.get(auth_routes.ACCESS_STATUS_HEADER) == "DESIGNER_SUSPENDED", (
        response.headers
    )
    assert set(response.json()) == {"detail"}

    after = _empanelments(client, world)[email]
    assert after["isActive"] is False, (
        "an allow-listed designer's SUSPENDED empanelment was revived by signing in; that is the "
        "silent undoing of an administrator's revocation the create-only rule exists to prevent"
    )
    assert after["revokedAt"] == before["revokedAt"]
    assert after["notes"] == before["notes"]
    assert after["firstSeenAt"] is None


async def test_signing_in_at_any_other_role_puts_nobody_on_the_designer_roster(world, client):
    others = [(slug, role) for slug, role in ACCOUNTS[:7] if role != "DESIGNER"]
    for slug, role in others:
        response = _login(client, world["address"](slug))
        assert response.status_code == 200, response.text
        assert response.json()["user"]["role"] == role

    rows = _empanelments(client, world)
    assert world["address"]("designer") in rows, (
        "this run's own empanelled designer is missing from the read, so the assertions below are "
        "proving that the query found nothing rather than that nothing was created"
    )
    for slug, role in others:
        assert world["address"](slug) not in rows, (
            f"signing in as {role} put the account on the DESIGNER roster; that roster is read as a "
            "statement that somebody is a practising designer, it is what the workshop pickers "
            "offer, and nothing downstream ever removes a row that should not have been written"
        )



async def test_an_admin_holding_a_temporary_password_cannot_mint_a_dataset_token(world, client):
    email = world["address"]("adminFlagged")

    minted = _mint(client, email)
    assert minted.status_code == 403, minted.text
    assert "accessToken" not in minted.json(), "a refusal must not carry the credential"
    assert "choose its own password" in minted.json()["detail"], minted.json()

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is True


async def test_only_the_configured_master_admin_mints_with_a_temporary_password(world, client):
    email = world["address"]("masterFlagged")

    deputy = _mint(client, email)
    assert deputy.status_code == 403, deputy.text
    assert "accessToken" not in deputy.json(), "a refusal must not carry the credential"
    assert "choose its own password" in deputy.json()["detail"], deputy.json()

    settings = get_settings()
    previous = settings.master_admin_email
    settings.master_admin_email = email
    try:
        minted = _mint(client, email)
    finally:
        settings.master_admin_email = previous
    assert minted.status_code == 200, minted.text
    assert minted.json()["accessToken"]
