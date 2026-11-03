import os
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.design_ratings import POOL_OPENS_WHEN_FIELD, RATEABLE_ENTITIES, RATING_DELEGATE
from prisma import Json

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

HAS_LEDGER = hasattr(db, RATING_DELEGATE)

needs_ledger = pytest.mark.skipif(
    not HAS_LEDGER,
    reason=(
        f"db.{RATING_DELEGATE} is not in this build's Prisma client. RUN `prisma generate` — the "
        f"table itself is already there: prisma/migrations/20260822120000_dw_review_rating_ledger "
        f"is applied on the local database, and only the generated client is behind. (If a "
        f"deployment is missing the TABLE too, `prisma migrate deploy` first.) The ACCESS rules in "
        f"this module still run — they are decided before the ledger is read."
    ),
)

PASSWORD = "rating-test-password"

PROTOTYPE_STAGE = "PROTOTYPE_DEVELOPMENT"
PROTOTYPE_ENTITY = "prototype"
SKETCH_STAGE = "SKETCH_DEVELOPMENT"
SKETCH_ENTITY = "sketch"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Rating Admin"),
    ("creator", "ADMIN", "Workshop Creator"),
    ("author", "DESIGNER", "Prototype Author"),
    ("peer", "DESIGNER", "Workshop Peer"),
    ("outsider", "DESIGNER", "Unrelated Designer"),
    ("researcher", "RESEARCHER", "Granted Researcher"),
)

PROTOTYPES: tuple[tuple[int, str, bool], ...] = (
    (2, "Kansa bowl", False),
    (0, "Brass lamp", True),
    (1, "Bell-metal tray", False),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    prototypes: dict[int, str] = {}

    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": f"dwrating-{slug}-{stamp}@example.org",
                "name": f"{name} {stamp}",
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        workshop = await db.designworkshop.create(data={
            "title": f"Dhokra ratings {stamp}",
            "status": "IN_PROGRESS",
            "createdById": people["creator"].id,
        })
        for index, (ordinal, name, opened) in enumerate(PROTOTYPES):
            data: dict[str, Any] = {"prototypeCode": f"P{index + 1}", "name": name}
            if opened:
                data[POOL_OPENS_WHEN_FIELD] = "2026-08-10"
            row = await db.dwstageentry.create(data={
                "designWorkshopId": workshop.id,
                "stageKey": PROTOTYPE_STAGE,
                "entityKey": PROTOTYPE_ENTITY,
                "ordinal": ordinal,
                "data": Json(data),
                "createdById": people["author"].id,
            })
            prototypes[ordinal] = row.id
        sketch = await db.dwstageentry.create(data={
            "designWorkshopId": workshop.id,
            "stageKey": SKETCH_STAGE,
            "entityKey": SKETCH_ENTITY,
            "ordinal": 0,
            "data": Json({"sketchNo": "S1", "name": "Rim study"}),
            "createdById": people["author"].id,
        })
        opened_sketch = await db.dwstageentry.create(data={
            "designWorkshopId": workshop.id,
            "stageKey": SKETCH_STAGE,
            "entityKey": SKETCH_ENTITY,
            "ordinal": 1,
            "data": Json({
                "sketchNo": "S2",
                "name": "Lamp elevation",
                POOL_OPENS_WHEN_FIELD: "2026-08-12",
            }),
            "createdById": people["author"].id,
        })
        for slug in ("author", "peer", "researcher"):
            await db.designworkshopviewer.create(data={
                "designWorkshopId": workshop.id,
                "userId": people[slug].id,
                "grantedById": people["admin"].id,
            })
        quiet_workshop = await db.designworkshop.create(data={
            "title": f"Dhokra ratings quiet {stamp}",
            "status": "IN_PROGRESS",
            "createdById": people["creator"].id,
        })
        for ordinal, name in ((0, "Undated study"), (1, "Second undated study")):
            await db.dwstageentry.create(data={
                "designWorkshopId": quiet_workshop.id,
                "stageKey": SKETCH_STAGE,
                "entityKey": SKETCH_ENTITY,
                "ordinal": ordinal,
                "data": Json({"sketchNo": f"Q{ordinal + 1}", "name": name}),
                "createdById": people["author"].id,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "workshop": workshop.id,
            "prototypes": prototypes,
            "sketch": sketch.id,
            "openedSketch": opened_sketch.id,
            "quietWorkshop": quiet_workshop.id,
            "stamp": stamp,
        }


@pytest.fixture
def client(world):
    return world["client"]


def headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


OPENED = 0
STILL_IN_PEER_REVIEW = 1
ALSO_IN_PEER_REVIEW = 2


def prototype(world: dict[str, Any], ordinal: int = OPENED) -> str:
    return world["prototypes"][ordinal]


def refused(response) -> None:
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"


def admitted(response) -> None:
    expected = 200 if HAS_LEDGER else 503
    assert response.status_code == expected, response.text



def test_the_registry_still_declares_what_this_api_assumes_about_it():
    from app.services.stage_schema import all_entities

    declared = {entity.key: {f.key for f in entity.fields} for _stage, entity in all_entities()}
    assert set(declared) >= RATEABLE_ENTITIES, (
        f"the registry no longer declares {RATEABLE_ENTITIES - set(declared)}"
    )
    carries_the_switch = {
        key for key, fields in declared.items() if POOL_OPENS_WHEN_FIELD in fields
    }
    assert carries_the_switch == set(RATEABLE_ENTITIES), (
        f"{POOL_OPENS_WHEN_FIELD} is declared on {sorted(carries_the_switch)}, but the rateable "
        f"entities are {sorted(RATEABLE_ENTITIES)}. A rateable entity without the switch can "
        f"never open its pool round; a non-rateable one with it is a row nobody meant to publish"
    )



def test_an_unrelated_designer_cannot_read_a_peer_rounds_ledger(world, client):
    refused(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world)}?round=PEER",
            headers=headers(world, "outsider"),
        )
    )


def test_an_unfinished_prototype_has_no_pool_round_for_a_stranger(world, client):
    for ordinal in (STILL_IN_PEER_REVIEW, ALSO_IN_PEER_REVIEW):
        refused(
            client.get(
                f"/api/design-ratings/subjects/{prototype(world, ordinal)}?round=POOL",
                headers=headers(world, "outsider"),
            )
        )


def test_an_undated_sketch_has_no_pool_round_even_beside_an_opened_prototype(world, client):
    refused(
        client.get(
            f"/api/design-ratings/subjects/{world['sketch']}?round=POOL",
            headers=headers(world, "outsider"),
        )
    )


def test_a_researchers_viewer_grant_does_not_admit_them_to_this_surface(world, client):
    for ordinal in (OPENED, STILL_IN_PEER_REVIEW):
        for round_ in ("PEER", "POOL"):
            refused(
                client.get(
                    f"/api/design-ratings/subjects/{prototype(world, ordinal)}?round={round_}",
                    headers=headers(world, "researcher"),
                )
            )


def test_an_unknown_subject_is_the_same_refusal_as_a_forbidden_one(world, client):
    refused(
        client.get(
            "/api/design-ratings/subjects/ckmissingmissingmissing?round=PEER",
            headers=headers(world, "admin"),
        )
    )


def test_a_designer_cannot_rate_their_own_prototype(world, client):
    response = client.post(
        "/api/design-ratings",
        json={"subjectId": prototype(world), "round": "PEER", "score": 5},
        headers=headers(world, "author"),
    )
    assert response.status_code == 403, response.text


def test_the_workshop_creator_may_rate_a_piece_they_did_not_draw(world, client):
    admitted(
        client.post(
            "/api/design-ratings",
            json={"subjectId": prototype(world), "round": "PEER", "score": 4},
            headers=headers(world, "creator"),
        )
    )


def test_an_outsider_writing_a_rating_is_refused_before_anything_is_written(world, client):
    refused(
        client.post(
            "/api/design-ratings",
            json={
                "subjectId": prototype(world, STILL_IN_PEER_REVIEW),
                "round": "POOL",
                "score": 5,
            },
            headers=headers(world, "outsider"),
        )
    )


def test_an_outsider_cannot_rank_a_peer_round_they_are_not_in(world, client):
    refused(
        client.get(
            f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}"
            f"&entityKey={PROTOTYPE_ENTITY}",
            headers=headers(world, "outsider"),
        )
    )


def test_a_pool_ranking_with_nothing_opened_is_the_same_404_as_a_missing_workshop(world, client):
    refused(
        client.get(
            f"/api/design-ratings/rounds/POOL?workshopId={world['quietWorkshop']}"
            f"&entityKey={SKETCH_ENTITY}",
            headers=headers(world, "outsider"),
        )
    )


def test_an_unknown_round_is_a_422_and_names_the_two_that_exist(world, client):
    response = client.get(
        f"/api/design-ratings/subjects/{prototype(world)}?round=FINAL",
        headers=headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert "PEER" in response.json()["detail"]
    assert "POOL" in response.json()["detail"]


def test_a_child_row_of_a_prototype_is_not_a_thing_that_gets_ranked(world, client):
    response = client.get(
        f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}"
        f"&entityKey=prototypeStageLog",
        headers=headers(world, "admin"),
    )
    assert response.status_code == 422, response.text


def test_an_anonymous_caller_reaches_nothing(world, client):
    for path in (
        f"/api/design-ratings/subjects/{prototype(world)}",
        f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}",
    ):
        assert client.get(path).status_code in (401, 403), path



def test_a_granted_peer_reaches_their_workshops_peer_round(world, client):
    admitted(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world)}?round=PEER",
            headers=headers(world, "peer"),
        )
    )


def test_an_unrelated_designer_reaches_an_OPENED_prototypes_pool_round(world, client):
    admitted(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world, OPENED)}?round=POOL",
            headers=headers(world, "outsider"),
        )
    )


def test_a_finished_sketch_opens_to_the_pool_exactly_as_a_prototype_does(world, client):
    admitted(
        client.get(
            f"/api/design-ratings/subjects/{world['openedSketch']}?round=POOL",
            headers=headers(world, "outsider"),
        )
    )


def test_the_pool_being_open_on_a_piece_does_not_open_the_workshop(world, client):
    refused(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world, OPENED)}?round=PEER",
            headers=headers(world, "outsider"),
        )
    )
    refused(
        client.get(
            f"/api/design-workshops/{world['workshop']}",
            headers=headers(world, "outsider"),
        )
    )


def test_a_pool_ranking_shows_a_stranger_the_opened_piece_only(world, client):
    body = client.get(
        f"/api/design-ratings/rounds/POOL?workshopId={world['workshop']}"
        f"&entityKey={PROTOTYPE_ENTITY}",
        headers=headers(world, "outsider"),
    )
    admitted(body)
    if not HAS_LEDGER:
        return
    assert [row["subjectId"] for row in body.json()["items"]] == [prototype(world, OPENED)]


def test_an_admin_reaches_every_round_of_every_piece(world, client):
    for ordinal in (OPENED, STILL_IN_PEER_REVIEW, ALSO_IN_PEER_REVIEW):
        for round_ in ("PEER", "POOL"):
            admitted(
                client.get(
                    f"/api/design-ratings/subjects/{prototype(world, ordinal)}?round={round_}",
                    headers=headers(world, "admin"),
                )
            )



def _rate(world, slug: str, subject: str, score: int, *, rated_at=None, round_: str = "PEER"):
    body: dict[str, Any] = {
        "subjectId": subject,
        "round": round_,
        "score": score,
        "comment": "Rim is heavy",
        "suggestion": "Thin the rim by 2 mm",
    }
    if rated_at is not None:
        body["ratedAt"] = rated_at.isoformat()
    return world["client"].post(
        "/api/design-ratings", json=body, headers=headers(world, slug)
    )


@needs_ledger
def test_a_rating_round_trips_and_a_repeated_capture_writes_nothing(world, client):
    subject = prototype(world, STILL_IN_PEER_REVIEW)
    moment = datetime.now(UTC) - timedelta(days=2)
    first = _rate(world, "peer", subject, 4, rated_at=moment)
    assert first.status_code == 200, first.text
    assert first.json()["replayed"] is False

    second = _rate(world, "peer", subject, 4, rated_at=moment)
    assert second.status_code == 200, second.text
    assert second.json()["replayed"] is True
    assert second.json()["rating"]["id"] == first.json()["rating"]["id"]

    ledger = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "admin"),
    ).json()
    assert ledger["summary"]["ratingCount"] == 1


@needs_ledger
def test_a_stale_delivery_does_not_undo_an_amendment(world, client):
    subject = prototype(world, ALSO_IN_PEER_REVIEW)
    original = datetime.now(UTC) - timedelta(days=3)
    amendment = datetime.now(UTC) - timedelta(days=2)
    assert _rate(world, "peer", subject, 5, rated_at=original).status_code == 200
    assert _rate(world, "peer", subject, 3, rated_at=amendment).status_code == 200

    late = _rate(world, "peer", subject, 5, rated_at=original)
    assert late.status_code == 200, late.text
    assert late.json()["replayed"] is True

    ledger = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "admin"),
    ).json()
    assert ledger["summary"]["score"] == 3
    assert ledger["summary"]["ratingCount"] == 1


@needs_ledger
def test_a_peer_is_sent_the_average_and_their_own_row_and_no_other_row(world, client):
    subject = prototype(world, OPENED)
    assert _rate(world, "peer", subject, 5).status_code == 200
    assert _rate(world, "creator", subject, 3).status_code == 200

    body = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "peer"),
    ).json()
    assert body["summary"]["ratingCount"] == 2
    assert body["summary"]["score"] == 4
    assert body["canReadLedger"] is False
    assert [row["mine"] for row in body["ratings"]] == [True]


@needs_ledger
def test_the_records_author_reads_the_whole_ledger_and_an_admin_sees_the_names(world, client):
    subject = prototype(world, OPENED)
    assert _rate(world, "peer", subject, 5).status_code == 200
    assert _rate(world, "creator", subject, 3).status_code == 200

    author_view = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "author"),
    ).json()
    assert author_view["canReadLedger"] is True
    assert author_view["namesShown"] is True
    assert len(author_view["ratings"]) == 2

    admin_view = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "admin"),
    ).json()
    assert {row["reviewerId"] for row in admin_view["ratings"]} == {
        world["people"]["peer"].id,
        world["people"]["creator"].id,
    }


@needs_ledger
def test_the_ranked_list_carries_both_orders(world, client):
    items = client.get(
        f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}"
        f"&entityKey={PROTOTYPE_ENTITY}",
        headers=headers(world, "admin"),
    ).json()["items"]

    assert [row["placedPosition"] for row in items] == [1, 2, 3]
    assert [row["ordinal"] for row in items] == [0, 1, 2]

    assert sorted(row["defaultPosition"] for row in items) == [1, 2, 3]
    by_default = [row["score"] for row in sorted(items, key=lambda r: r["defaultPosition"])]
    rated = [score for score in by_default if score is not None]
    assert rated == sorted(rated, reverse=True), by_default
    assert all(score is None for score in by_default[len(rated):]), by_default


@needs_ledger
def test_a_peer_and_a_pool_rating_of_one_prototype_are_two_separate_judgements(world, client):
    subject = prototype(world, OPENED)

    def summary(round_: str, slug: str = "admin"):
        return client.get(
            f"/api/design-ratings/subjects/{subject}?round={round_}",
            headers=headers(world, slug),
        ).json()["summary"]

    peer_before = summary("PEER")
    assert _rate(world, "outsider", subject, 2, round_="POOL").status_code == 200

    assert summary("PEER") == peer_before
    assert summary("POOL")["score"] == 2
    assert summary("POOL")["ratingCount"] == 1
