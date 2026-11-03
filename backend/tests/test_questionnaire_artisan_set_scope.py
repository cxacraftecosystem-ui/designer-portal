import os
import re
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from app.api.routes.questionnaire import (
    _SET_KEY_ID_SEPARATOR,
    _SET_KEY_SCOPE_SEPARATOR,
    artisan_set_key,
)
from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

_REPO = Path(__file__).resolve().parents[2]

needs_db = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

pytestmark = pytest.mark.anyio

STAMP = uuid.uuid4().hex[:8]

LOCATION = {
    "latitude": 22.3145,
    "longitude": 87.3101,
    "state": "Rajasthan",
    "district": "Jaipur",
    "village": "Bagru",
}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"



async def test_the_scope_is_part_of_the_key_so_two_workshops_are_two_sittings():
    ids = ["a2", "a1"]
    at_w1 = artisan_set_key(ids, workshop_id="w1", design_workshop_id=None)
    at_w2 = artisan_set_key(ids, workshop_id="w2", design_workshop_id=None)

    assert at_w1 == "w1||a1,a2"
    assert at_w2 == "w2||a1,a2"
    assert at_w1 != at_w2, "the same people at two workshops must not share one unique key"

    assert artisan_set_key(["a3", "a1", "a2"], workshop_id=None, design_workshop_id=None) == (
        artisan_set_key(["a2", "a3", "a1"], workshop_id=None, design_workshop_id=None)
    )
    assert artisan_set_key(["a1", "", "a1"], workshop_id=None, design_workshop_id=None) == "||a1"


async def test_the_two_workshop_tables_get_their_own_slot():
    ids = ["a1"]
    ordinary = artisan_set_key(ids, workshop_id="w1", design_workshop_id=None)
    design = artisan_set_key(ids, workshop_id=None, design_workshop_id="w1")
    assert ordinary == "w1||a1"
    assert design == "|w1|a1"
    assert ordinary != design


async def test_none_and_empty_string_mean_the_same_unfiled_scope():
    spellings = {
        artisan_set_key(["a1"], workshop_id=None, design_workshop_id=None),
        artisan_set_key(["a1"], workshop_id="", design_workshop_id=""),
        artisan_set_key(["a1"], workshop_id="", design_workshop_id=None),
    }
    assert spellings == {"||a1"}


async def test_an_artisan_less_interview_still_has_no_key_at_all():
    assert artisan_set_key([], workshop_id="w1", design_workshop_id=None) is None
    assert artisan_set_key(["", ""], workshop_id=None, design_workshop_id=None) is None


async def test_the_scope_arguments_have_no_defaults():
    with pytest.raises(TypeError):
        artisan_set_key(["a1"])  # type: ignore[call-arg]


async def test_the_migrations_rule_is_a_fixed_point_of_the_key_the_code_writes():
    def recompute(key: str, workshop_id: str | None, design_workshop_id: str | None) -> str:
        artisans = key.rsplit(_SET_KEY_SCOPE_SEPARATOR, 1)[-1]
        return _SET_KEY_SCOPE_SEPARATOR.join(
            [workshop_id or "", design_workshop_id or "", artisans]
        )

    ids = ["cartisanaaa", "cartisanbbb"]
    for workshop_id, design_workshop_id in [
        (None, None), ("cworkshop1", None), (None, "cdesignwk1"), ("cworkshop1", "cdesignwk1"),
    ]:
        written = artisan_set_key(
            ids, workshop_id=workshop_id, design_workshop_id=design_workshop_id
        )
        assert written is not None
        assert recompute(written, workshop_id, design_workshop_id) == written, (
            "the migration's rule does not reproduce the key the code writes. One of the two has "
            "drifted, and a deploy would rewrite every stored key into something no lookup finds."
        )
        assert recompute(recompute(written, workshop_id, design_workshop_id),
                         workshop_id, design_workshop_id) == written
        moved = recompute(written, "cworkshop9", None)
        assert moved == artisan_set_key(ids, workshop_id="cworkshop9", design_workshop_id=None)



@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    rows: dict[str, Any] = {}
    await db.connect()
    try:
        author = await db.user.create(
            data={
                "email": f"qscope-author-{STAMP}@example.org",
                "name": f"Scope Admin {STAMP}",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            }
        )
        rows["author"] = author
        rows["workshopA"] = await db.workshop.create(
            data={
                "title": f"Toolkit workshop 2 {STAMP}",
                "place": f"Bagru {STAMP}",
                "date": datetime(2026, 4, 1, tzinfo=UTC),
                "createdById": author.id,
            }
        )
        rows["workshopB"] = await db.workshop.create(
            data={
                "title": f"Toolkit workshop 3 {STAMP}",
                "place": f"Sanganer {STAMP}",
                "date": datetime(2026, 4, 2, tzinfo=UTC),
                "createdById": author.id,
            }
        )

        async def artisan(tag: str) -> Any:
            return await db.artisan.create(
                data={
                    "name": f"Scope artisan {tag} {STAMP}",
                    "place": f"Bhuj {STAMP}",
                    "createdById": author.id,
                }
            )

        rows["setIds"] = [(await artisan("x")).id, (await artisan("y")).id]
        rows["moverIds"] = [(await artisan("m")).id]

        rows["storedKeys"] = [
            (iv.artisanSetKey, iv.workshopId, iv.designWorkshopId)
            for iv in await db.questionnaireinterview.find_many(
                where={"artisanSetKey": {"not": None}}, take=1000
            )
        ]
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        rows["client"] = client
        yield rows


def _auth(env) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['author'].id)}"}


def _create(env, title: str, artisan_ids: list[str], *, workshop_id: str | None) -> Any:
    body: dict[str, Any] = {
        "title": title,
        "artisanIds": artisan_ids,
        "location": dict(LOCATION),
    }
    if workshop_id is not None:
        body["workshopId"] = workshop_id
    return env["client"].post("/api/questionnaire/interviews", json=body, headers=_auth(env))


@pytest.fixture(scope="module")
async def sittings(env):
    made = {}
    for slug, title, workshop in (
        ("atA", f"Workshop 2 sitting {STAMP}", env["workshopA"].id),
        ("atB", f"Workshop 3 sitting {STAMP}", env["workshopB"].id),
        ("unfiled", f"Unattached sitting {STAMP}", None),
    ):
        response = _create(env, title, env["setIds"], workshop_id=workshop)
        assert response.status_code == 201, response.text
        made[slug] = response.json()
    return made


@needs_db
async def test_two_workshops_may_both_interview_the_same_artisans(env, sittings):
    assert sittings["atA"]["id"] != sittings["atB"]["id"], "the second workshop folded into the first"
    assert sittings["atA"]["title"] != sittings["atB"]["title"]
    assert sittings["atA"]["workshopId"] == env["workshopA"].id
    assert sittings["atB"]["workshopId"] == env["workshopB"].id
    assert sittings["unfiled"]["id"] not in {sittings["atA"]["id"], sittings["atB"]["id"]}
    assert sittings["unfiled"]["workshopId"] is None


@needs_db
async def test_the_fold_still_folds_inside_one_scope(env, sittings):
    again = _create(
        env, f"Workshop 2, second researcher {STAMP}", env["setIds"], workshop_id=env["workshopA"].id
    )
    assert again.status_code == 201, again.text
    assert again.json()["id"] == sittings["atA"]["id"], "the fold inside one workshop stopped folding"
    assert again.json()["title"] == sittings["atA"]["title"]

    loose_again = _create(env, f"Unattached again {STAMP}", env["setIds"], workshop_id=None)
    assert loose_again.status_code == 201, loose_again.text
    assert loose_again.json()["id"] == sittings["unfiled"]["id"]


@needs_db
async def test_by_artisans_answers_per_scope(env, sittings):
    query = "&".join(f"artisanIds={aid}" for aid in env["setIds"])

    for slug, workshop in (("atA", env["workshopA"].id), ("atB", env["workshopB"].id)):
        answer = env["client"].get(
            f"/api/questionnaire/interviews/by-artisans?{query}&workshopId={workshop}",
            headers=_auth(env),
        )
        assert answer.status_code == 200, answer.text
        assert answer.json()["id"] == sittings[slug]["id"]

    unfiled = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}", headers=_auth(env)
    )
    assert unfiled.status_code == 200, unfiled.text
    assert unfiled.json()["id"] == sittings["unfiled"]["id"]

    empty = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}&designWorkshopId=dw_{STAMP}",
        headers=_auth(env),
    )
    assert empty.status_code == 200, empty.text
    assert empty.json() is None


@needs_db
async def test_re_filing_an_interview_rewrites_its_key_with_no_artisan_change(env):
    query = "&".join(f"artisanIds={aid}" for aid in env["moverIds"])
    opened = _create(env, f"Filed nowhere {STAMP}", env["moverIds"], workshop_id=None)
    assert opened.status_code == 201, opened.text
    interview_id = opened.json()["id"]

    moved = env["client"].patch(
        f"/api/questionnaire/interviews/{interview_id}",
        json={"workshopId": env["workshopB"].id},
        headers=_auth(env),
    )
    assert moved.status_code == 200, moved.text
    assert moved.json()["workshopId"] == env["workshopB"].id

    at_new = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}&workshopId={env['workshopB'].id}",
        headers=_auth(env),
    )
    assert at_new.status_code == 200, at_new.text
    assert at_new.json()["id"] == interview_id

    at_old = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}", headers=_auth(env)
    )
    assert at_old.status_code == 200, at_old.text
    assert at_old.json() is None, "the origin scope still holds a row that has left it"

    reopened = _create(env, f"Filed nowhere, again {STAMP}", env["moverIds"], workshop_id=None)
    assert reopened.status_code == 201, reopened.text
    assert reopened.json()["id"] != interview_id


@needs_db
async def test_a_re_file_onto_a_taken_scope_is_the_named_409_and_does_not_land(env, sittings):
    moved = env["client"].patch(
        f"/api/questionnaire/interviews/{sittings['unfiled']['id']}",
        json={"workshopId": env["workshopB"].id},
        headers=_auth(env),
    )
    assert moved.status_code == 409, moved.text
    detail = moved.json()["detail"]
    assert detail["code"] == "duplicate_artisan_set"
    assert detail["existingInterviewId"] == sittings["atB"]["id"]
    assert detail["existingInterviewTitle"] == sittings["atB"]["title"]

    after = env["client"].get(
        f"/api/questionnaire/interviews/{sittings['unfiled']['id']}", headers=_auth(env)
    )
    assert after.status_code == 200, after.text
    assert after.json()["workshopId"] is None

    query = "&".join(f"artisanIds={aid}" for aid in env["setIds"])
    still = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}", headers=_auth(env)
    )
    assert still.status_code == 200, still.text
    assert still.json()["id"] == sittings["unfiled"]["id"]


@needs_db
async def test_the_recompute_is_idempotent_over_the_stored_rows(env):
    def recompute(key: str, workshop_id: str | None, design_workshop_id: str | None) -> str:
        artisans = key.rsplit(_SET_KEY_SCOPE_SEPARATOR, 1)[-1]
        return _SET_KEY_SCOPE_SEPARATOR.join(
            [workshop_id or "", design_workshop_id or "", artisans]
        )

    stored = env["storedKeys"]
    for key, workshop_id, design_workshop_id in stored:
        once = recompute(key, workshop_id, design_workshop_id)
        assert once == key, (
            f"stored key {key!r} is not what the migration computes: it did not run on this "
            "database, or the rule has drifted from it"
        )
        assert recompute(once, workshop_id, design_workshop_id) == once
        assert _SET_KEY_SCOPE_SEPARATOR in key, (
            f"stored key {key!r} is still the old artisans-only form, so the migration has not run "
            "on this database"
        )



def _source(relative: str) -> str:
    return (_REPO / relative).read_text(encoding="utf-8")


async def test_the_web_client_spells_the_key_the_way_python_does():
    ts = _source("frontend/components/questionnaires/interviewArtisans.ts")
    halves = ts.split("export function artisanSetKey(", 1)
    assert len(halves) == 2, "artisanSetKey has been renamed or removed from the web client"
    body = halves[1].split("\n}", 1)[0]

    assert "scope: InterviewWorkshopScope" in body
    assert "new Set(selectedIds.filter(Boolean))" in body
    assert f'.sort().join("{_SET_KEY_ID_SEPARATOR}")' in body
    assert f"`${{interviewScopeKey(scope)}}{_SET_KEY_SCOPE_SEPARATOR}${{ids}}`" in body
    assert 'ids ? `' in body and '` : ""' in body

    scope_fn = ts.split("export function interviewScopeKey(", 1)[1].split("\n}", 1)[0]
    assert (
        f"`${{scope.workshopId}}{_SET_KEY_SCOPE_SEPARATOR}${{scope.designWorkshopId}}`" in scope_fn
    )


async def test_the_handset_groups_interviews_by_workshop_too():
    kotlin = _source("android/app/src/main/java/com/designprototype/workshop/MainActivity.kt")
    halves = kotlin.split("private fun interviewGroupKey(", 1)
    assert len(halves) == 2, "interviewGroupKey has been renamed or removed from the handset"
    body = halves[1].split("\n}", 1)[0]
    assert "iv.workshopId" in body and "iv.designWorkshopId" in body
    assert body.count(".orEmpty()") >= 2
    assert "toSortedSet()" in body


async def test_the_reconcile_script_recomputes_with_each_rows_own_scope():
    script = _source("backend/scripts/reconcile_interview_set_keys.py")
    halves = script.split("artisan_set_key(", 1)
    assert len(halves) == 2, "the reconcile script no longer computes the key at all"
    call = halves[1][:400]
    assert "workshop_id=iv.workshopId" in call
    assert "design_workshop_id=iv.designWorkshopId" in call


async def test_the_migration_recomputes_every_keyed_row_and_is_safe_to_re_run():
    sql = _source(
        "backend/prisma/migrations/20260920120000_questionnaire_artisan_set_key_scoped/migration.sql"
    )
    halves = sql.split('UPDATE "QuestionnaireInterview"', 1)
    assert len(halves) == 2, "the recompute statement is gone"
    statement = halves[1]

    assert "coalesce(\"workshopId\", '')" in statement
    assert "coalesce(\"designWorkshopId\", '')" in statement
    assert "reverse(split_part(reverse(\"artisanSetKey\"), '|', 1))" in statement
    assert re.search(r'WHERE\s+"artisanSetKey" IS NOT NULL', statement), (
        "artisan-less rows carry no key and must be left alone"
    )
    assert '"artisanSetKey" <>' in statement

    assert '|| "artisanSetKey"' not in statement
