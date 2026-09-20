"""The workshop scope lives INSIDE ``QuestionnaireInterview.artisanSetKey``, and four copies agree.

WHAT THIS FILE STANDS IN FRONT OF (2026-09-20). ``artisanSetKey`` is a single-column ``@unique``, so
ONE ARTISAN SET HELD ONE INTERVIEW ACROSS THE WHOLE REPOSITORY. ``create_interview`` looks that key
up and FOLDS a create into whatever row it finds, so two workshops interviewing the same artisans
could not both record their sitting: the second workshop's answers were written onto the first
workshop's row and the second interview never existed — under a 201 saying it had been saved. The
field repository (documentation-portal) hit the identical defect from the instrument side and
recorded it in its migration ``20260913100000``, where the 3rd Craft Toolkit Workshop's answers
folded onto the 2nd workshop's interview.

ITS FIX COULD NOT BE COPIED. That repository moved the index to ``@@unique([questionnaireId,
artisanSetKey])``; this ``QuestionnaireInterview`` has no ``questionnaireId``. Its scope columns are
``workshopId`` and ``designWorkshopId`` and both are NULLABLE, and a composite unique over two
nullable columns would have been worse than the defect — NULLs are DISTINCT in a Postgres unique
index, so the interviews naming no workshop (31 of 44 on this corpus) would have stopped deduping
altogether, silently. The scope therefore went INSIDE the key:

    "<workshopId>|<designWorkshopId>|<sorted, de-duplicated, comma-joined artisan ids>"

── THE FOUR COPIES ─────────────────────────────────────────────────────────────────────────────────

Four places spell this key, and if any one of them drifts NOTHING FAILS LOUDLY — a client's "is there
already an entry for this set?" check simply stops matching, the researcher is shown no existing
entry, and their save folds into a row they were never shown:

  * ``backend/app/api/routes/questionnaire.artisan_set_key``     — the definition
  * ``backend/prisma/migrations/20260920120000_…/migration.sql`` — the recompute
  * ``frontend/components/questionnaires/interviewArtisans.ts``  — the web client
  * ``android/.../MainActivity.kt::interviewGroupKey``           — the handset

Part 3 holds all four. The handset's is held by SHAPE rather than by string (it prefixes ``set:`` and
never sends the value anywhere), so what is asserted of it is that it reads the two workshop columns
at all — the failure there is a HIDDEN ROW, not a crash.

Part 2 needs Postgres: every assertion in it is about which rows exist afterwards. Those tests skip
themselves when ``DATABASE_URL`` does not point at a local database, exactly as
``test_questionnaire_interview_merge`` does. Parts 1 and 3 are pure and run anywhere.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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

#: Repository root, for the four source files part 3 reads.
_REPO = Path(__file__).resolve().parents[2]

#: Skips the route tests, and ONLY those. Parts 1 and 3 are pure functions and source text; gating
#: them on a database would hide the copies-agree assertions on every machine without Postgres, which
#: is the CI shape this repository actually runs.
needs_db = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

pytestmark = pytest.mark.anyio

STAMP = uuid.uuid4().hex[:8]

#: The smallest location ``require_location`` accepts: a device coordinate plus a stated state and
#: district, both from the closed lists ``services/address.py`` holds.
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


# ════════════════════════════════════════════════════════════════════════════════════════════════
# 1. THE KEY ITSELF — pure, no database
# ════════════════════════════════════════════════════════════════════════════════════════════════


async def test_the_scope_is_part_of_the_key_so_two_workshops_are_two_sittings():
    """The same artisans at two workshops produce two DIFFERENT keys. This is the whole change."""
    ids = ["a2", "a1"]
    at_w1 = artisan_set_key(ids, workshop_id="w1", design_workshop_id=None)
    at_w2 = artisan_set_key(ids, workshop_id="w2", design_workshop_id=None)

    assert at_w1 == "w1||a1,a2"
    assert at_w2 == "w2||a1,a2"
    assert at_w1 != at_w2, "the same people at two workshops must not share one unique key"

    # The artisan half is still sorted and de-duplicated: ticking A then B is the same sitting as
    # ticking B then A, which is the property the whole dedupe rests on and predates this change.
    assert artisan_set_key(["a3", "a1", "a2"], workshop_id=None, design_workshop_id=None) == (
        artisan_set_key(["a2", "a3", "a1"], workshop_id=None, design_workshop_id=None)
    )
    assert artisan_set_key(["a1", "", "a1"], workshop_id=None, design_workshop_id=None) == "||a1"


async def test_the_two_workshop_tables_get_their_own_slot():
    """A ``Workshop`` id and a ``DesignWorkshop`` id are different populations, never interchangeable.

    Two separate slots rather than one "whichever workshop" field, because an id from one table
    happening to equal an id from the other would otherwise silently merge two unrelated sittings.
    """
    ids = ["a1"]
    ordinary = artisan_set_key(ids, workshop_id="w1", design_workshop_id=None)
    design = artisan_set_key(ids, workshop_id=None, design_workshop_id="w1")
    assert ordinary == "w1||a1"
    assert design == "|w1|a1"
    assert ordinary != design


async def test_none_and_empty_string_mean_the_same_unfiled_scope():
    """"No workshop" has two spellings on the wire and they must not produce two keys.

    The web form's pickers hold ``""`` and ``lib/api.ts`` sends ``workshop.workshopId || null``, so
    both spellings reach the server for the same sitting. Two keys for one sitting would split the
    fold in the majority case — most interviews in this corpus name no workshop at all.
    """
    spellings = {
        artisan_set_key(["a1"], workshop_id=None, design_workshop_id=None),
        artisan_set_key(["a1"], workshop_id="", design_workshop_id=""),
        artisan_set_key(["a1"], workshop_id="", design_workshop_id=None),
    }
    assert spellings == {"||a1"}


async def test_an_artisan_less_interview_still_has_no_key_at_all():
    """``None``, not ``"||"``. Artisan-less interviews are NOT deduped and never were.

    Postgres treats NULLs as distinct under a unique index, which is the behaviour six of this
    corpus's 44 rows depend on. Returning the bare scope prefix instead would make every artisan-less
    interview at one workshop collide with every other one there — a 409 on a perfectly ordinary save.
    """
    assert artisan_set_key([], workshop_id="w1", design_workshop_id=None) is None
    assert artisan_set_key(["", ""], workshop_id=None, design_workshop_id=None) is None


async def test_the_scope_arguments_have_no_defaults():
    """Calling without a scope is a ``TypeError``, not a quietly unattached key.

    A defaulted scope would make "I forgot" and "this interview is unattached" the same call, and the
    cost of that confusion is a workshop sitting folded into an unattached row — exactly the silent
    wrong-row failure this change exists to prevent. Keyword-only and REQUIRED means the mistake is a
    failure at the call site the moment the suite imports the module, not a wrong row in production.
    """
    with pytest.raises(TypeError):
        artisan_set_key(["a1"])  # type: ignore[call-arg]


# ════════════════════════════════════════════════════════════════════════════════════════════════
# 2. THE ROUTES — needs Postgres
# ════════════════════════════════════════════════════════════════════════════════════════════════


@pytest.fixture(scope="module")
async def env():
    """Two workshops, two artisan pairs, one account — and NO interviews.

    THE INTERVIEWS ARE MADE THROUGH THE API, not seeded, because what is under test IS the create
    path: whether a second workshop's sitting opens a second row or folds into the first. Seeding
    them would assume the answer this file exists to check.

    TWO PAIRS OF ARTISANS. The first is shared by the scope tests, which need the same people at both
    workshops. The second belongs to the re-filing test alone, so that its destination scope is free
    and it can assert the SUCCESSFUL move rather than only the refusal — a test that can only observe
    a 409 cannot tell a key that was recomputed from one that was never written at all.

    THE ACCOUNT IS ADMIN, and that is about a different gate: ``enforce_workshop_submission`` needs a
    ``WorkshopAssignment`` for an ordinary account and passes an admin through untouched. That gate is
    ``test_questionnaire_write_path_access``'s subject; an account that could not file under a
    workshop would refuse before reaching anything measured here.

    Rows are created on this module's own Prisma connection BEFORE the TestClient opens, exactly as
    ``test_questionnaire_interview_merge`` does and for the same reason: the client is shared with the
    running app and bound to its event loop, so touching it from a test's own loop is the kind of
    cross-loop use that fails intermittently rather than honestly. That is also why the keyed rows
    already in the database are READ HERE for the idempotence test rather than in the test itself.
    """
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
        # ``Workshop.date`` is a NON-NULL ``DateTime`` with no default, so it is not optional in a
        # fixture however little this file cares about it — omitting it makes every test in part 2 an
        # ERROR in setup rather than a failure, which reads as a broken suite instead of a broken
        # route. Both dates are in the past so no submission-window rule has anything to say.
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

        # THE CORPUS AS THE MIGRATION LEFT IT, captured as plain tuples so the idempotence test needs
        # no database of its own. Read before the TestClient takes the connection, for the cross-loop
        # reason in this docstring.
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
    """The three creates every route test below reads: workshop A, workshop B, and unfiled.

    ``async def`` to match ``env``: an async fixture is what the anyio plugin resolves, and a sync one
    depending on it would be handed the generator rather than the rows.

    A FIXTURE AND NOT THE FIRST TEST'S SIDE EFFECT, so any one test in this part can be run on its
    own (``-k by_artisans``) and still have the rows it asserts about. The creates are the thing under
    test, so the fixture asserts only that each request was ACCEPTED; which row came back is what the
    tests themselves measure.
    """
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
    """THE DEFECT, DIRECTLY. Two creates, same artisans, two workshops — two rows, not one fold.

    Before the scope entered the key the second create returned the FIRST workshop's interview, with
    its title and its answers, and the second workshop's sitting was unrecoverable: no row, no error,
    and a 201 saying it had been saved.
    """
    assert sittings["atA"]["id"] != sittings["atB"]["id"], "the second workshop folded into the first"
    # Each kept its OWN title, which is the visible half of the same fact: a fold fills only the empty
    # fields of the row it folds into and hands that row back, so a fold would have answered with the
    # first sitting's title both times.
    assert sittings["atA"]["title"] != sittings["atB"]["title"]
    assert sittings["atA"]["workshopId"] == env["workshopA"].id
    assert sittings["atB"]["workshopId"] == env["workshopB"].id
    # And the unfiled sitting is a third row: "no workshop" is a scope, not the absence of one.
    assert sittings["unfiled"]["id"] not in {sittings["atA"]["id"], sittings["atB"]["id"]}
    assert sittings["unfiled"]["workshopId"] is None


@needs_db
async def test_the_fold_still_folds_inside_one_scope(env, sittings):
    """The dedupe is NARROWED, not removed — a second create for workshop A returns A's row.

    THE CONTROL ON THE TEST ABOVE, and the assertion that catches the obvious wrong fix: making the
    key merely unique enough to stop colliding (a row id, a timestamp, a random suffix) also stops it
    DEDUPING, and every tap and 504-retry starts producing a fresh interview again — the
    133-rows-for-18-real-sets explosion migration ``20260622120000`` was written to clean up.
    """
    again = _create(
        env, f"Workshop 2, second researcher {STAMP}", env["setIds"], workshop_id=env["workshopA"].id
    )
    assert again.status_code == 201, again.text
    assert again.json()["id"] == sittings["atA"]["id"], "the fold inside one workshop stopped folding"
    # The fold never overwrites a populated field, so the title handed back is the FIRST one.
    assert again.json()["title"] == sittings["atA"]["title"]

    # AND THE UNFILED SCOPE DEDUPES WITHIN ITSELF TOO. This is the half a composite unique index over
    # the two nullable columns would have lost outright, and it is the majority of this corpus.
    loose_again = _create(env, f"Unattached again {STAMP}", env["setIds"], workshop_id=None)
    assert loose_again.status_code == 201, loose_again.text
    assert loose_again.json()["id"] == sittings["unfiled"]["id"]


@needs_db
async def test_by_artisans_answers_per_scope(env, sittings):
    """``by-artisans`` returns the row for the workshop it was ASKED about — three scopes, three rows.

    This route exists so a client can show "this sitting has already been started" BEFORE offering to
    create, so its answer and ``create_interview``'s have to agree or the banner lies. Asked with the
    artisans alone it can only ever answer about the unattached row, so a researcher filing under
    workshop B would be shown some other sitting's recordings and then have their save open a
    different row than the one they were looking at.
    """
    query = "&".join(f"artisanIds={aid}" for aid in env["setIds"])

    for slug, workshop in (("atA", env["workshopA"].id), ("atB", env["workshopB"].id)):
        answer = env["client"].get(
            f"/api/questionnaire/interviews/by-artisans?{query}&workshopId={workshop}",
            headers=_auth(env),
        )
        assert answer.status_code == 200, answer.text
        assert answer.json()["id"] == sittings[slug]["id"]

    # No workshop named: the unattached row. A REAL answer, not a fallback.
    unfiled = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}", headers=_auth(env)
    )
    assert unfiled.status_code == 200, unfiled.text
    assert unfiled.json()["id"] == sittings["unfiled"]["id"]

    # A scope nobody has interviewed these people under: null, and NOT somebody else's row. Null is
    # what makes the client offer to create; the wrong row is what makes it fold into a stranger's.
    empty = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}&designWorkshopId=dw_{STAMP}",
        headers=_auth(env),
    )
    assert empty.status_code == 200, empty.text
    assert empty.json() is None


@needs_db
async def test_re_filing_an_interview_rewrites_its_key_with_no_artisan_change(env):
    """A PATCH that only moves the workshop still moves the key, and ``by-artisans`` follows the row.

    ``update_interview`` recomputes the key through ``replace_interview_artisans`` ONLY when the
    payload carries ``artisanIds`` — and "this sitting was logged against the wrong workshop" carries
    nothing else. Left alone, that row keeps the OLD workshop's prefix and BOTH halves of the guard go
    wrong at once: the destination workshop is left unguarded (a second interview for the same people
    could be opened there), while the origin's slot stays occupied by a row that has left it, refusing
    a legitimate new sitting with a 409 naming an interview that is no longer there.

    Its own artisan, so the destination scope is free and the SUCCESS is observable. A test that could
    only watch a 409 cannot tell a key that was recomputed from one that was never written.
    """
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

    # THE LOOKUP FOLLOWED IT. Without the recompute the new workshop would answer null and the old
    # scope would keep answering with this row — the two failures named in the docstring, made visible.
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

    # AND THE ORIGIN IS GENUINELY FREE AGAIN: a new sitting for the same person filed nowhere is a
    # create, not a 409 against a stale key.
    reopened = _create(env, f"Filed nowhere, again {STAMP}", env["moverIds"], workshop_id=None)
    assert reopened.status_code == 201, reopened.text
    assert reopened.json()["id"] != interview_id


@needs_db
async def test_a_re_file_onto_a_taken_scope_is_the_named_409_and_does_not_land(env, sittings):
    """Moving a sitting onto a set another interview already covers THERE refuses, and rolls back.

    The other half of the recompute: it can now collide, so it must refuse in the same words as every
    other route to the same conflict — ``duplicate_artisan_set`` plus the holder's id and title, which
    is what lets a client offer "workshop 3's sitting already covers these people; move this one into
    it?" and then call the merge route. A bare 500 here would be a dead end.
    """
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

    # THE MOVE DID NOT LAND. The 409 is raised inside the PATCH's transaction, so the workshop column
    # goes back with it — a refusal standing beside a committed re-file is exactly the split that
    # transaction exists to prevent.
    after = env["client"].get(
        f"/api/questionnaire/interviews/{sittings['unfiled']['id']}", headers=_auth(env)
    )
    assert after.status_code == 200, after.text
    assert after.json()["workshopId"] is None

    # And the origin scope still resolves to it, so the refusal cost the row nothing at all.
    query = "&".join(f"artisanIds={aid}" for aid in env["setIds"])
    still = env["client"].get(
        f"/api/questionnaire/interviews/by-artisans?{query}", headers=_auth(env)
    )
    assert still.status_code == 200, still.text
    assert still.json()["id"] == sittings["unfiled"]["id"]


@needs_db
async def test_the_recompute_is_idempotent_over_the_stored_rows(env):
    """The migration's rule, applied again to what it already wrote, changes nothing.

    It takes the artisan half as "everything after the LAST ``|``" — which is the whole value when
    there is no ``|`` at all — so it strips a prefix it already wrote before putting the same one
    back. A plain ``'…' || "artisanSetKey"`` prepend would look identical on a first run and DOUBLE
    the prefix on a second, and this deployment's migrations are applied by a runner that can be
    interrupted and re-run.

    The rule is reproduced here in Python rather than by executing the SQL, because these rows reach
    the test as plain tuples captured by the fixture (the Prisma client belongs to the TestClient's
    event loop by the time a test runs). What is being asserted is the RULE's idempotence and that the
    stored corpus is already its fixed point — i.e. that the migration ran and nothing has drifted.
    """

    def recompute(key: str, workshop_id: str | None, design_workshop_id: str | None) -> str:
        artisans = key.rsplit(_SET_KEY_SCOPE_SEPARATOR, 1)[-1]
        return _SET_KEY_SCOPE_SEPARATOR.join(
            [workshop_id or "", design_workshop_id or "", artisans]
        )

    # ── THE RULE ITSELF, ON VECTORS THIS TEST OWNS ──────────────────────────────────────────────
    #
    # These run on ANY database, including an empty one, and they are the half that actually pins
    # the hazard the docstring names. This assertion used to be made only against whatever rows the
    # database happened to hold, under `assert stored, "the corpus should hold ~38"` — which asserts
    # a fact about ONE DEVELOPER'S MACHINE. CI applies the migrations to a FRESH database with no
    # interviews in it, so the corpus is legitimately empty there and the test failed for three
    # commits on a property of the environment rather than of the code.
    #
    # `a` and `b` are shaped like cuids (no `|`, no `,`), which is the premise the whole key format
    # rests on — see `artisan_set_key`'s note on separators.
    OLD_FORM = "cartisan1,cartisan2"
    for workshop_id, design_workshop_id in [
        (None, None), ("cworkshop1", None), (None, "cdesignwk1"), ("cworkshop1", "cdesignwk1"),
    ]:
        once = recompute(OLD_FORM, workshop_id, design_workshop_id)
        assert once.endswith(OLD_FORM) and once.count(_SET_KEY_SCOPE_SEPARATOR) == 2, (
            "the rule did not put exactly one scope pair in front of the artisan half"
        )
        twice = recompute(once, workshop_id, design_workshop_id)
        assert twice == once, (
            "the rule DOUBLED its prefix on a second application. A migration runner that is "
            "interrupted and re-run would then write keys nothing can look up — which is why the "
            "rule takes the artisan half as everything after the LAST separator rather than "
            "prepending to whatever it finds."
        )
        # And re-applying it under a DIFFERENT scope re-files rather than accumulating, which is
        # what `resync_interview_set_key` depends on when a sitting moves between workshops.
        moved = recompute(once, "cworkshop9", None)
        assert moved == _SET_KEY_SCOPE_SEPARATOR.join(["cworkshop9", "", OLD_FORM])

    # ── AND THE STORED CORPUS, WHERE THERE IS ONE ───────────────────────────────────────────────
    #
    # A drift check for databases that carry real rows — a developer's machine, staging, production
    # after a deploy. An EMPTY list is a legitimate state (a freshly migrated database has no
    # interviews) and is NOT failed on; what is failed on is a row whose stored key is not what the
    # rule computes, which means the migration did not run here or has drifted from the code.
    stored = env["storedKeys"]
    for key, workshop_id, design_workshop_id in stored:
        once = recompute(key, workshop_id, design_workshop_id)
        assert once == key, (
            f"stored key {key!r} is not what the migration computes: it did not run on this "
            "database, or the rule has drifted from it"
        )
        assert recompute(once, workshop_id, design_workshop_id) == once
        # Not `all(... for ...)` over the list, which would pass vacuously on the empty corpus the
        # paragraph above allows. Inside the loop it only ever runs on a row that exists.
        assert _SET_KEY_SCOPE_SEPARATOR in key, (
            f"stored key {key!r} is still the old artisans-only form, so the migration has not run "
            "on this database"
        )


# ════════════════════════════════════════════════════════════════════════════════════════════════
# 3. THE OTHER THREE COPIES — pure, reads source files
# ════════════════════════════════════════════════════════════════════════════════════════════════


def _source(relative: str) -> str:
    return (_REPO / relative).read_text(encoding="utf-8")


async def test_the_web_client_spells_the_key_the_way_python_does():
    """``interviewArtisans.ts::artisanSetKey`` builds scope + ``|`` + sorted ids, checked as SOURCE.

    THE WHOLE POINT IS THAT A DRIFT HERE IS SILENT. The browser uses its copy to decide "is there
    already an entry for this set?"; a client still computing the artisans-only form does not error —
    it is simply shown no existing entry, starts what looks like a fresh sitting, and the save folds
    into a row the researcher was never shown. Nothing on either side reports anything.

    Held as SOURCE TEXT rather than by running the TypeScript, because pytest has no JS runtime and a
    Python re-implementation of the TS would only prove the re-implementation agrees with itself. The
    assertions are deliberately about the SEPARATORS and the ORDER of the three parts — the two things
    that must match character for character — and not about formatting a linter may move.
    """
    ts = _source("frontend/components/questionnaires/interviewArtisans.ts")
    halves = ts.split("export function artisanSetKey(", 1)
    assert len(halves) == 2, "artisanSetKey has been renamed or removed from the web client"
    body = halves[1].split("\n}", 1)[0]

    # The scope is an ARGUMENT, so a caller cannot forget it and silently get the unattached key.
    assert "scope: InterviewWorkshopScope" in body
    # Sorted, de-duplicated, blank-free, joined by the SAME separator Python uses.
    assert "new Set(selectedIds.filter(Boolean))" in body
    assert f'.sort().join("{_SET_KEY_ID_SEPARATOR}")' in body
    # Scope first, artisans last, one scope separator between — built out of `interviewScopeKey`, so
    # "which workshop is this" has one spelling in that file rather than two that can drift.
    assert f"`${{interviewScopeKey(scope)}}{_SET_KEY_SCOPE_SEPARATOR}${{ids}}`" in body
    # An empty selection is `""` — the TypeScript spelling of the `None` Python returns — and never
    # the bare prefix, which would collide every artisan-less interview at one workshop.
    assert 'ids ? `' in body and '` : ""' in body

    # And `interviewScopeKey` is ordinary-workshop id, separator, design-workshop id: the same two
    # slots in the same order as Python's, which is what makes the line above equivalent.
    scope_fn = ts.split("export function interviewScopeKey(", 1)[1].split("\n}", 1)[0]
    assert (
        f"`${{scope.workshopId}}{_SET_KEY_SCOPE_SEPARATOR}${{scope.designWorkshopId}}`" in scope_fn
    )


async def test_the_web_client_asks_by_artisans_for_a_scope():
    """The page sends the workshop WITH the lookup, or the server answers about the unattached row.

    ``artisanSetKey`` agreeing with Python is not enough on its own: the page could still call
    ``by-artisans`` with artisans alone, which since the scope entered the key always resolves to the
    unattached interview whatever workshop is named on screen.
    """
    page = _source("frontend/app/(protected)/questionnaire/page.tsx")
    # ANCHORED ON THE FETCH, NOT ON THE WORDS "by-artisans". The route is NAMED in three comments on
    # this page, all of them above the call, so splitting on the bare phrase lands ~800 lines early
    # and the assertions below would then be measuring prose — passing or failing for reasons that
    # have nothing to do with the request.
    halves = page.split("/questionnaire/interviews/by-artisans?", 1)
    assert len(halves) == 2, "the shared-entry lookup has moved out of the questionnaire page"
    before = halves[0][-1500:]
    assert "workshopId=${encodeURIComponent(artisanWorkshopScope.workshopId)}" in before
    assert (
        "designWorkshopId=${encodeURIComponent(artisanWorkshopScope.designWorkshopId)}" in before
    )


async def test_the_handset_groups_interviews_by_workshop_too():
    """``interviewGroupKey`` reads both workshop columns, or the handset HIDES one of the two rows.

    The handset's key is its own shape and is never sent anywhere — what must match is the QUESTION it
    answers, "are these the same sitting?". With the workshop left out it answers yes for two sittings
    the server now keeps apart, collapses them in the browse and update dropdowns, and shows whichever
    was created last. The other workshop's interview, its answers and its recordings simply do not
    appear, and nothing on screen says a record was left out.
    """
    kotlin = _source("android/app/src/main/java/com/designprototype/workshop/MainActivity.kt")
    halves = kotlin.split("private fun interviewGroupKey(", 1)
    assert len(halves) == 2, "interviewGroupKey has been renamed or removed from the handset"
    body = halves[1].split("\n}", 1)[0]
    assert "iv.workshopId" in body and "iv.designWorkshopId" in body
    # A null column is the UNATTACHED scope and not an absent one — the same `or ""` the server does.
    # Without it "null" and "" would be two groups for one sitting.
    assert body.count(".orEmpty()") >= 2
    # Still sorted, so tick order never decides grouping.
    assert "toSortedSet()" in body


async def test_the_reconcile_script_recomputes_with_each_rows_own_scope():
    """``scripts/reconcile_interview_set_keys`` reads the row's workshop columns, or it MERGES ROWS.

    THE WORST FAILURE IN THIS WHOLE CHANGE LIVES IN THAT SCRIPT, and it is a data-loss one rather than
    a wrong-answer one. The script NULLs every key, groups interviews by recomputed key, picks one
    survivor per group, re-points its media and responses, and DELETES the rest. Recompute without the
    scope and every workshop's sitting for one artisan set lands in a single group — so the script
    consolidates two legitimate interviews into one and deletes the other, which is precisely the
    repair it was written to perform turned into the damage it was written to undo.

    A source assertion because the script is a `python -m` entry point with no test harness and it
    writes irreversibly; what has to hold is that the call names the row's own columns.
    """
    script = _source("backend/scripts/reconcile_interview_set_keys.py")
    halves = script.split("artisan_set_key(", 1)
    assert len(halves) == 2, "the reconcile script no longer computes the key at all"
    # A CHARACTER WINDOW AND NOT "UP TO THE FIRST `)`": the first argument is a list comprehension,
    # whose own closing paren would end the slice two lines before the two keywords being asserted —
    # a matcher that silently measures the wrong text and fails for the wrong reason.
    call = halves[1][:400]
    assert "workshop_id=iv.workshopId" in call
    assert "design_workshop_id=iv.designWorkshopId" in call


async def test_the_migration_recomputes_every_keyed_row_and_is_safe_to_re_run():
    """The migration exists, rewrites the key from the STORED key, and a re-run writes nothing.

    A prefix that only the application writes leaves every EXISTING row on the old spelling, which is
    the quiet half of this change: ``by-artisans`` and the fold would stop finding all 38 keyed
    interviews at once, and each of them would be silently duplicated by the next save.
    """
    sql = _source(
        "backend/prisma/migrations/20260920120000_questionnaire_artisan_set_key_scoped/migration.sql"
    )
    halves = sql.split('UPDATE "QuestionnaireInterview"', 1)
    assert len(halves) == 2, "the recompute statement is gone"
    statement = halves[1]

    # Scope, scope, artisans — the same three parts in the same order as Python's.
    assert "coalesce(\"workshopId\", '')" in statement
    assert "coalesce(\"designWorkshopId\", '')" in statement
    # "everything after the LAST |", which is the whole value when there is none. This is what makes a
    # second run compute what is already stored instead of doubling the prefix.
    assert "reverse(split_part(reverse(\"artisanSetKey\"), '|', 1))" in statement
    assert re.search(r'WHERE\s+"artisanSetKey" IS NOT NULL', statement), (
        "artisan-less rows carry no key and must be left alone"
    )
    # A guard that makes a re-run touch zero rows, not merely write the same bytes back.
    assert '"artisanSetKey" <>' in statement

    # NOT A BARE PREPEND: `'…' || "artisanSetKey"` looks identical on a first run and doubles the
    # prefix on the second, on a deployment whose migration runner can be interrupted and re-run.
    assert '|| "artisanSetKey"' not in statement
