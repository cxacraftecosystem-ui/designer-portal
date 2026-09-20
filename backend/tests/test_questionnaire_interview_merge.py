"""Folding one questionnaire interview into another — the refusal that names the holder, the move
itself, the three refusals that stop it destroying words, and the gate.

WHAT THIS IS FOR (owner's ruling, 2026-09-20). Two researchers recorded ONE artisan set as TWO
interviews titled by the sections each covered — "D Black Pottery" and an "F" one, the practice
``questionnaire.section_codes_from_title`` documents. The F sitting omitted an artisan. Adding that
artisan makes F's artisan-set key EQUAL D's, ``replace_interview_artisans`` hits the unique index on
``artisanSetKey``, and the correction dies as a 409 a client can do nothing with. A CREATE for a
taken set has always folded into the canonical row (``merge_into_interview``); an EDIT could not fold
at all. The ruling: **let an edit fold, as a create already does — explicitly, never silently.**

So there are two halves and this file pins both:

  * ``PATCH /questionnaire/interviews/{id}`` still REFUSES, but the 409 now carries the holding
    interview's id and title under stable keys, because a client that can only read prose cannot
    offer "D Black Pottery already covers this set. Move this interview's sections and recordings
    into it?" — and cannot call anything afterwards;
  * ``POST /questionnaire/interviews/{id}/merge-into/{targetId}`` is what the researcher's *yes*
    calls. It moves the answers AND the recordings, carries the source's section coverage into the
    surviving title, removes the source, and REFUSES — naming every question — when the two rows
    answer one question differently, rather than picking a winner under a 200.

THE THIRD THING PINNED HERE IS NOT ABOUT MERGING AT ALL. Every questionnaire route encoded with
``public_encode(interview)`` and NO VIEWER, which strips ``url``/``publicUrl``/``objectKey`` and the
transcript columns from every media node — so a client could be told a recording exists and never be
able to play it. On this instrument the clips ARE the interview, so a merge that moved them into a
payload nobody can draw would have moved nothing a person can see. ``test_detail_read_carries_media_
urls`` and the URL assertions in the merge test are that half, including the control that the URL is
still withheld from an account that may not take the file.

Needs Postgres: every assertion here is about which rows exist afterwards and which columns point
where. The module skips itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_questionnaire_write_path_access`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import os
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

from app.api.routes.questionnaire import artisan_set_key
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

STAMP = uuid.uuid4().hex[:8]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _section(code: str, title: str) -> Any:
    """A questionnaire section with this single-letter code, created only if it is not already here.

    ``QuestionnaireSection.code`` is ``@unique`` and ``sortOrder`` is ``@@unique`` GLOBALLY in this
    repository — there is one instrument — so a test cannot simply create "D" and "F": on a seeded
    database both already exist, and on a re-run its own rows would collide with themselves. The
    codes must be SINGLE LETTERS because that is all ``section_codes_from_title`` accepts (it matches
    one-character tokens against real section codes), and single letters are exactly what the
    researchers' titles carry.
    """
    found = await db.questionnairesection.find_first(where={"code": code})
    if found:
        return found
    highest = await db.questionnairesection.find_many(order={"sortOrder": "desc"}, take=1)
    return await db.questionnairesection.create(
        data={
            "code": code,
            "title": title,
            "sortOrder": (highest[0].sortOrder if highest else 0) + 1,
        }
    )


@pytest.fixture(scope="module")
async def env():
    """Five interview PAIRS, one per test, each over its own artisans — plus the people and the clip.

    A PAIR PER TEST AND ITS OWN ARTISANS, WHICH IS NOT TIDINESS. ``artisanSetKey`` is ``@unique``, so
    exactly one interview may hold a given set AT A GIVEN WORKSHOP at a time — and every pair but the
    fourth is filed under no workshop at all, which is one scope and not an absent one. Two tests
    sharing artisans would therefore make the second one's fixture a 500 in the first one's clean-up,
    and the failure would read as a bug in the route. Each pair gets fresh artisans and is used once.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly. Every assertion below is
    therefore made through the API, which is also the only thing a client has.

    THE STRANGER IS A RESEARCHER AND NOT A SECOND DESIGNER, deliberately. The gate under test is
    ``access.guard_record_edit``'s privileged tier, one arm of which is "a professor may edit the
    data of anyone ranked below them". An equal-ranked account would leave the refusal explicable by
    that arm rather than by ownership; RESEARCHER (30) is strictly below DESIGNER (35), so the only
    reason it can be refused is that it neither owns the row nor outranks its author nor holds a
    grant.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    rows: dict[str, Any] = {}
    await db.connect()
    try:
        people = {}
        for slug, role, name in (
            ("author", "DESIGNER", "Recording Designer"),
            ("stranger", "RESEARCHER", "Unrelated Researcher"),
            ("admin", "ADMIN", "Questionnaire Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"qmerge-{slug}-{STAMP}@example.org",
                    "name": f"{name} {STAMP}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                }
            )
        rows["people"] = people

        section_d = await _section("D", "Black Pottery")
        section_f = await _section("F", "Tools and Materials")
        question_d = await db.questionnairequestion.create(
            data={
                "sectionCode": section_d.code,
                "sectionTitle": section_d.title,
                "prompt": f"How many looms are in the workshop? ({STAMP})",
                "sortOrder": 9001,
                "sectionId": section_d.id,
            }
        )
        question_f = await db.questionnairequestion.create(
            data={
                "sectionCode": section_f.code,
                "sectionTitle": section_f.title,
                "prompt": f"Which tools are shared between households? ({STAMP})",
                "sortOrder": 9002,
                "sectionId": section_f.id,
            }
        )
        rows["questionD"] = question_d
        rows["questionF"] = question_f

        here = await db.workshop.create(
            data={
                "title": f"Bagru workshop {STAMP}",
                "place": f"Bagru {STAMP}",
                "date": datetime(2026, 4, 1, tzinfo=UTC),
                "createdById": people["author"].id,
            }
        )
        there = await db.workshop.create(
            data={
                "title": f"Sanganer workshop {STAMP}",
                "place": f"Sanganer {STAMP}",
                "date": datetime(2026, 4, 2, tzinfo=UTC),
                "createdById": people["author"].id,
            }
        )

        async def artisan(tag: str) -> Any:
            return await db.artisan.create(
                data={
                    "name": f"Artisan {tag} {STAMP}",
                    "place": f"Bhuj {STAMP}",
                    "createdById": people["author"].id,
                }
            )

        async def interview(title: str, artisan_ids: list[str], **extra: Any) -> Any:
            # THROUGH THE SERVER'S OWN FUNCTION, not a hand-spelled join. The key carries the workshop
            # scope since 2026-09-20 (``"<workshopId>|<designWorkshopId>|<ids>"``), and a fixture that
            # kept spelling the old artisans-only form would seed rows the routes cannot find: the
            # 409 this file's first test exists to pin would simply not fire, and the test would go
            # green having proved nothing. ``extra`` is where ``workshopId`` arrives for pair 4.
            key = artisan_set_key(
                artisan_ids,
                workshop_id=extra.get("workshopId"),
                design_workshop_id=extra.get("designWorkshopId"),
            )
            made = await db.questionnaireinterview.create(
                data={
                    "title": title,
                    "createdById": people["author"].id,
                    "artisanSetKey": key,
                    **extra,
                }
            )
            for aid in artisan_ids:
                await db.questionnaireinterviewartisan.create(
                    data={"interviewId": made.id, "artisanId": aid}
                )
            return made

        # ── Pair 1: the named 409 on PATCH ──────────────────────────────────────────────────────
        a1, a2 = await artisan("1a"), await artisan("1b")
        rows["namedHolder"] = await interview("D Black Pottery", [a1.id, a2.id])
        rows["namedShort"] = await interview("Sitting section F", [a1.id])
        rows["namedIds"] = [a1.id, a2.id]

        # ── Pair 2: the clean merge, with answers on both sides and a clip on the source ─────────
        b1, b2 = await artisan("2a"), await artisan("2b")
        rows["cleanTarget"] = await interview("D Black Pottery", [b1.id, b2.id])
        rows["cleanSource"] = await interview("Sitting section F notes", [b1.id])
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["cleanTarget"].id,
                "questionId": question_d.id,
                "answerText": "Twelve looms",
                "answeredById": people["author"].id,
            }
        )
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["cleanSource"].id,
                "questionId": question_f.id,
                "answerText": "The dyeing vats are shared",
                "answeredById": people["author"].id,
            }
        )
        rows["clip"] = await db.mediafile.create(
            data={
                "originalFilename": f"F_Q1_{STAMP}_0312_20260920.m4a",
                "mediaType": "AUDIO",
                "mimeType": "audio/mp4",
                "sizeBytes": 481_233,
                "bucket": "design-workshop-local",
                "objectKey": f"questionnaire/{STAMP}/f-sitting.m4a",
                "url": f"https://example.invalid/{STAMP}/f-sitting.m4a",
                "uploadedById": people["author"].id,
                "questionnaireInterviewId": rows["cleanSource"].id,
            }
        )

        # ── Pair 3: the same question answered differently on both sides ─────────────────────────
        c1, c2 = await artisan("3a"), await artisan("3b")
        rows["clashTarget"] = await interview("D Black Pottery", [c1.id, c2.id])
        rows["clashSource"] = await interview("Sitting section F", [c1.id])
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["clashTarget"].id,
                "questionId": question_d.id,
                "answerText": "Twelve looms",
                "answeredById": people["author"].id,
            }
        )
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["clashSource"].id,
                "questionId": question_d.id,
                "answerText": "Twenty looms",
                "answeredById": people["author"].id,
            }
        )

        # ── Pair 4: filed under two different workshops ──────────────────────────────────────────
        d1, d2 = await artisan("4a"), await artisan("4b")
        rows["scopeTarget"] = await interview(
            "D Black Pottery", [d1.id, d2.id], workshopId=here.id
        )
        rows["scopeSource"] = await interview(
            "Sitting section F", [d1.id], workshopId=there.id
        )

        # ── Pair 5: the gate ─────────────────────────────────────────────────────────────────────
        e1, e2 = await artisan("5a"), await artisan("5b")
        rows["gateTarget"] = await interview("D Black Pottery", [e1.id, e2.id])
        rows["gateSource"] = await interview("Sitting section F", [e1.id])
    finally:
        await db.disconnect()

    # ONE TestClient, three tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
    with TestClient(app) as client:
        rows["client"] = client
        yield rows


def _auth(env, slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


# ── 1. The refusal names the holder ─────────────────────────────────────────────────────────────


async def test_patch_409_names_the_interview_that_holds_the_set(env):
    """The edit still refuses — and now says WHICH interview is in the way, in machine-readable form.

    Both halves are asserted. The sentence has to survive because two shipped clients read it
    verbatim (``frontend/lib/api.describeApiDetail`` takes ``detail.message``; Android's
    ``outboxConflictSentence`` embeds it between its own clauses), and the ids have to arrive because
    without them no client can render the owner's confirmation or call the merge route afterwards.
    """
    response = env["client"].patch(
        f"/api/questionnaire/interviews/{env['namedShort'].id}",
        json={"artisanIds": env["namedIds"]},
        headers=_auth(env, "author"),
    )
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "duplicate_artisan_set"
    # The prose half, unchanged.
    assert "already exists for this exact set of artisans" in detail["message"]
    assert "single shared entry per artisan set" in detail["message"]
    # The machine-readable half, which is the whole point.
    assert detail["existingInterviewId"] == env["namedHolder"].id
    assert detail["existingInterviewTitle"] == "D Black Pottery"

    # AND THE EDIT DID NOT LAND. The 409 is raised from inside the PATCH's transaction; a refusal
    # standing beside a committed artisan-set rewrite is the failure that transaction exists to stop.
    after = env["client"].get(
        f"/api/questionnaire/interviews/{env['namedShort'].id}", headers=_auth(env, "author")
    )
    assert after.status_code == 200, after.text
    assert [link["artisanId"] for link in after.json()["artisans"]] == [env["namedIds"][0]]


# ── 2. The move itself ──────────────────────────────────────────────────────────────────────────


async def test_merge_moves_responses_and_media_then_removes_the_source(env):
    """Responses move, the recording moves WITH ITS URL, the sections in the title move, source gone.

    The media assertion is not decoration. ``MediaFile.questionnaireInterviewId`` is
    ``onDelete: SetNull``, so a merge that deleted the source without repointing would not fail — it
    would silently detach every clip from every interview. And the ``url`` assertion is the second
    defect this file pins: with no viewer named, ``public_encode`` pops ``url``/``publicUrl``/
    ``objectKey`` off every media node, so the merge would answer with a recording nobody can play.
    """
    source_id, target_id = env["cleanSource"].id, env["cleanTarget"].id
    response = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "author"),
    )
    assert response.status_code == 200, response.text
    body = response.json()

    # The SURVIVOR is what comes back, hydrated, so the client redraws from one payload.
    assert body["id"] == target_id
    answers = {row["questionId"]: row["answerText"] for row in body["responses"]}
    assert answers[env["questionD"].id] == "Twelve looms"
    assert answers[env["questionF"].id] == "The dyeing vats are shared"

    media = {row["id"]: row for row in body["media"]}
    assert env["clip"].id in media, "the recording did not move onto the surviving interview"
    assert media[env["clip"].id]["url"] == env["clip"].url
    assert media[env["clip"].id]["objectKey"] == env["clip"].objectKey

    # THE SECTIONS MOVED TOO. ``_derived_completed_sections`` reads section coverage off the
    # interview TITLE — the only signal pre-nomenclature recordings carry — so deleting a row titled
    # "section F" would take section F off the completion matrix even though every answer moved.
    assert "D Black Pottery" in body["title"]
    assert "section F" in body["title"]

    # And the provenance says where it came from, since the source row is gone.
    merged_from = body["extraMetadata"]["mergedFrom"]
    assert merged_from[-1]["interviewId"] == source_id
    assert merged_from[-1]["sectionCodes"] == ["F"]

    gone = env["client"].get(
        f"/api/questionnaire/interviews/{source_id}", headers=_auth(env, "author")
    )
    assert gone.status_code == 404, gone.text


async def test_detail_read_carries_media_urls_and_still_withholds_them_from_others(env):
    """``GET /questionnaire/interviews/{id}`` hands the uploader its clip's URL — and nobody else.

    Runs after the merge above, against the surviving interview, so it measures the ordinary read
    path rather than anything the merge does. The second half is the control: the fix is "name the
    caller", not "hand every URL to everyone", and ``media_url_owners`` is what keeps the two apart.
    """
    target_id = env["cleanTarget"].id
    mine = env["client"].get(
        f"/api/questionnaire/interviews/{target_id}", headers=_auth(env, "author")
    )
    assert mine.status_code == 200, mine.text
    uploader_view = {row["id"]: row for row in mine.json()["media"]}[env["clip"].id]
    assert uploader_view["url"] == env["clip"].url

    theirs = env["client"].get(
        f"/api/questionnaire/interviews/{target_id}", headers=_auth(env, "stranger")
    )
    assert theirs.status_code == 200, theirs.text
    other_view = {row["id"]: row for row in theirs.json()["media"]}[env["clip"].id]
    assert other_view.get("url") is None
    assert "objectKey" not in other_view


# ── 3. The refusals ─────────────────────────────────────────────────────────────────────────────


async def test_conflicting_answers_are_refused_and_every_question_is_named(env):
    """Both rows answer one question differently: refuse, name it, and move NOTHING.

    Picking a winner would destroy a researcher's words under a 200, and unrecoverably — the losing
    row goes with the source. The post-conditions are half the test: the source must still be there
    and the target's answer must be untouched, or "refused" would only mean "answered 409 on the way
    out of a half-applied merge".
    """
    source_id, target_id = env["clashSource"].id, env["clashTarget"].id
    response = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "author"),
    )
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "merge_answer_conflict"
    assert [c["questionId"] for c in detail["conflicts"]] == [env["questionD"].id]
    assert detail["conflicts"][0]["fields"] == ["answerText"]
    assert detail["conflicts"][0]["thisInterview"]["answerText"] == "Twenty looms"
    assert detail["conflicts"][0]["targetInterview"]["answerText"] == "Twelve looms"
    # NAMED IN THE SENTENCE, not only in the structure — the message is what a researcher reads.
    assert env["questionD"].prompt in detail["message"]
    assert "D" in detail["conflicts"][0]["sectionCode"]

    still_there = env["client"].get(
        f"/api/questionnaire/interviews/{source_id}", headers=_auth(env, "author")
    )
    assert still_there.status_code == 200, still_there.text
    survivor = env["client"].get(
        f"/api/questionnaire/interviews/{target_id}", headers=_auth(env, "author")
    )
    kept = {row["questionId"]: row["answerText"] for row in survivor.json()["responses"]}
    assert kept[env["questionD"].id] == "Twelve looms"


async def test_merge_across_two_workshops_is_refused(env):
    """Two interviews filed under different workshops are two bodies of fieldwork, and 422 says so.

    This repository's ``QuestionnaireInterview`` has no ``questionnaireId`` — see the module docstring
    of the migration blocker — so the scope this route can enforce is ``workshopId`` /
    ``designWorkshopId``, which is what files an interview under a body of work here. Crossing either
    would re-file one workshop's answers into another's consolidated view and report annexure.
    """
    response = env["client"].post(
        f"/api/questionnaire/interviews/{env['scopeSource'].id}"
        f"/merge-into/{env['scopeTarget'].id}",
        headers=_auth(env, "author"),
    )
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "merge_cross_scope"
    assert detail["thisInterview"]["workshopId"] != detail["targetInterview"]["workshopId"]

    still_there = env["client"].get(
        f"/api/questionnaire/interviews/{env['scopeSource'].id}", headers=_auth(env, "author")
    )
    assert still_there.status_code == 200, still_there.text


# ── 4. The gate ─────────────────────────────────────────────────────────────────────────────────


async def test_the_gate_is_the_same_one_that_guards_an_edit_of_this_interview(env):
    """A signed-in account that could not PATCH this interview cannot merge it away either.

    Both halves in one test, on one pair, so neither can pass by accident of ordering. The stranger
    is a RESEARCHER — strictly below the DESIGNER who recorded the row, so not its owner, not an
    admin, outranking nobody, holding no grant — and is refused. The admin, who is privileged under
    the very same ``guard_record_edit`` call, goes through. No new privilege is introduced: the set
    that may merge is exactly the set that may already rewrite this interview's populated fields.
    """
    source_id, target_id = env["gateSource"].id, env["gateTarget"].id
    refused = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "stranger"),
    )
    assert refused.status_code == 403, refused.text

    # NOTHING WAS TAKEN BEHIND THE REFUSAL. The gate runs inside the transaction alongside the
    # writes, so a 403 has to take the whole move back with it.
    intact = env["client"].get(
        f"/api/questionnaire/interviews/{source_id}", headers=_auth(env, "stranger")
    )
    assert intact.status_code == 200, intact.text

    allowed = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "admin"),
    )
    assert allowed.status_code == 200, allowed.text
    assert allowed.json()["id"] == target_id


async def test_an_interview_cannot_be_merged_into_itself(env):
    """The degenerate call, refused before anything reads a response row.

    Left to run, it would repoint a row's media onto itself and then DELETE the row it had just
    finished merging into — the one shape of this route that loses everything.
    """
    response = env["client"].post(
        f"/api/questionnaire/interviews/{env['namedHolder'].id}"
        f"/merge-into/{env['namedHolder'].id}",
        headers=_auth(env, "author"),
    )
    assert response.status_code == 422, response.text
    assert response.json()["detail"]["code"] == "merge_self"
