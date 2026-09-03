"""The whole-repository completion matrix must not read the whole repository's answers.

``GET /api/questionnaire/completion`` renders artisans against sections and fills each cell with a
BOOLEAN: was anything recorded here. ``_derived_completed_sections`` computes those booleans, and it
takes two optional narrowings — one artisan, or a set of workshops. The View Data screen's own first
load supplies NEITHER, so the unscoped branch is the one that runs most and it was the one nothing
protected:

    db.questionnaireinterview.find_many(
        where={}, include={"artisans": True, "responses": True, "media": True}
    )

Every interview in the repository, with no ``take``, carrying every answer and every media row — and
the columns it dragged across the region are the widest in the schema. ``QuestionnaireResponse.answerText``
is a whole spoken answer; ``MediaFile.extraMetadata`` carries an EXIF summary per photograph. All of
it to compute a grid of ticks. It is the FIRST read that screen issues, so its cost is the page's
time-to-first-paint, and it grows with how much fieldwork the ministry has done — which is the one
number that only goes up.

Since 2026-09-03 that branch asks two narrow statements instead: DISTINCT ``(interview, section)``
pairs for the answers, and the three section SIGNALS per media row with nothing else off it. The
scoped branches are deliberately untouched — they are already bounded, and moving them is a change
to what the most-used view reports rather than a performance fix.

SO THIS FILE MEASURES TWO THINGS, and the second is the one that would actually hurt:

1. THE SHAPE OF THE READ. Asserted by watching what ``include`` the interview scan is issued with —
   the only honest way to say "it no longer loads the answers", since a fast query and a slow one
   return the same JSON.
2. THAT NOTHING MOVED. The narrow statements have to reproduce, in SQL, the rules the Python walk
   applies: a non-empty answer counts, a blank one does not, and a media row counts through its
   ``questionId`` metadata, its ``sectionCode`` metadata, or the section token its filename leads
   with. Each of those is pinned separately, because each is a place the two implementations can
   drift apart — and the equivalence test at the end asserts the scoped and unscoped branches agree
   cell for cell, which is the property a reader of the screen actually depends on.

These need Postgres: what is under test is a SQL statement. The module skips itself through the
shared gate when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import uuid

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from prisma import Json

pytestmark = [needs_db, pytest.mark.anyio]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    """Four sections, one artisan, and one interview carrying a recorded example of each signal.

    THE SECTION CODES ARE STAMPED AND MULTI-CHARACTER, which is a deliberate departure from the real
    instrument's single letters. ``QuestionnaireSection.code`` is ``@unique`` and its ``sortOrder`` is
    ``@unique`` too, so a fixture that reused "A" or sortOrder 1 would collide with the seeded
    instrument on any developer machine that has one — and this module is repository-wide by
    construction, so it cannot simply avoid looking at those rows. ``sortOrder`` is taken from the
    top of whatever is already there for the same reason.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8].upper()
    await db.connect()
    try:
        author = await db.user.create(
            data={
                "email": f"matrix-{stamp}@example.org",
                "name": "Matrix Author",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            }
        )
        highest = await db.questionnairesection.find_first(order={"sortOrder": "desc"})
        base = (highest.sortOrder if highest else 0) + 1

        sections: dict[str, str] = {}
        questions: dict[str, str] = {}
        for offset, slug in enumerate(("ANSWER", "BLANK", "META", "FILE")):
            code = f"T{stamp}{slug}"
            section = await db.questionnairesection.create(
                data={
                    "code": code,
                    "title": f"Matrix section {slug}",
                    "sortOrder": base + offset,
                    "isActive": True,
                }
            )
            question = await db.questionnairequestion.create(
                data={
                    "sectionCode": code,
                    "sectionTitle": section.title,
                    "prompt": f"Matrix prompt {slug}",
                    "sortOrder": offset + 1,
                    "sectionId": section.id,
                }
            )
            sections[slug] = section.id
            questions[slug] = question.id
            sections[f"{slug}_code"] = code

        artisan = await db.artisan.create(
            data={
                "name": f"Matrix Artisan {stamp}",
                "place": "Bagru",
                "createdById": author.id,
            }
        )
        interview = await db.questionnaireinterview.create(
            data={
                "title": f"Matrix sitting {stamp}",
                "createdById": author.id,
            }
        )
        await db.questionnaireinterviewartisan.create(
            data={"interviewId": interview.id, "artisanId": artisan.id}
        )

        # ANSWER: a real answer. BLANK: whitespace only, which ``is_empty_value`` calls empty and
        # which the SQL must therefore NOT count — the single most likely place for the two
        # implementations to disagree, so it is a fixture rather than an afterthought.
        await db.questionnaireresponse.create(
            data={
                "interviewId": interview.id,
                "questionId": questions["ANSWER"],
                "answerText": "Twelve looms, two of them idle.",
                "answeredById": author.id,
            }
        )
        await db.questionnaireresponse.create(
            data={
                "interviewId": interview.id,
                "questionId": questions["BLANK"],
                "answerText": "   \t  ",
                "answeredById": author.id,
            }
        )

        # META: a photograph tagged with the question it belongs to. FILE: an audio clip carrying its
        # section only in the filename's leading token, which is the sole signal the app's recorded
        # questionnaire clips have.
        await db.mediafile.create(
            data={
                "originalFilename": "loom-detail.jpg",
                "mediaType": "IMAGE",
                "mimeType": "image/jpeg",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"matrix/{stamp}/meta.jpg",
                "uploadedById": author.id,
                "questionnaireInterviewId": interview.id,
                "extraMetadata": Json({"questionId": questions["META"]}),
            }
        )
        await db.mediafile.create(
            data={
                "originalFilename": f"{sections['FILE_code']}_Q1_INT_45_20260903.m4a",
                "mediaType": "AUDIO",
                "mimeType": "audio/mp4",
                "sizeBytes": 4096,
                "bucket": "test-bucket",
                "objectKey": f"matrix/{stamp}/clip.m4a",
                "uploadedById": author.id,
                "questionnaireInterviewId": interview.id,
            }
        )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "auth": {"Authorization": f"Bearer {create_access_token(subject=author.id)}"},
            "artisan_id": artisan.id,
            "interview_id": interview.id,
            "sections": sections,
        }


def _derived_sections(env, *, artisan_id: str | None = None) -> set[str]:
    """The section ids the matrix reports as DERIVED for this fixture's artisan.

    Filtered to that one artisan on the way out, because the unscoped call is repository-wide by
    definition: every other row on the machine is in its answer and none of it is this test's
    business. An override is deliberately not filtered out — this fixture sets none, so every cell
    it can see is a derived one.
    """
    query = f"?artisanId={artisan_id}" if artisan_id else ""
    response = env["client"].get(f"/api/questionnaire/completion{query}", headers=env["auth"])
    assert response.status_code == 200, response.text
    return {
        cell["sectionId"]
        for cell in response.json()["cells"]
        if cell["artisanId"] == env["artisan_id"] and cell["derived"]
    }


# --------------------------------------------------------------------------------------
# 1. The shape of the read — the fix itself
# --------------------------------------------------------------------------------------


def test_the_unscoped_matrix_does_not_load_answers_or_media(env, monkeypatch):
    """THE DEFECT, measured directly. The interview scan must be issued WITHOUT ``responses`` and
    without ``media``, because those two relations are the whole cost: an answer is a paragraph of
    speech and a media row carries an EXIF summary, and the matrix wants one bit from each.

    Watched by wrapping the generated action rather than by timing anything — a slow query and a
    fast one return identical JSON, so speed is unassertable and the SHAPE is the real claim.
    """
    from prisma.actions import QuestionnaireInterviewActions

    original = QuestionnaireInterviewActions.find_many
    seen: list[dict] = []

    async def _spy(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `find_many`
        seen.append(kwargs.get("include") or {})
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(QuestionnaireInterviewActions, "find_many", _spy)

    response = env["client"].get("/api/questionnaire/completion", headers=env["auth"])
    assert response.status_code == 200, response.text

    assert seen, "the completion matrix issued no interview scan at all"
    for include in seen:
        assert "responses" not in include, (
            "the unscoped matrix is still loading every answer in the repository to compute a tick"
        )
        assert "media" not in include, (
            "the unscoped matrix is still loading every media row in the repository"
        )
    # The artisan links ARE still needed: they are what turns "this interview covered section X"
    # into "this artisan has section X", so a narrowing that dropped them would empty the matrix.
    assert any(include.get("artisans") for include in seen)


def test_the_scoped_matrix_is_deliberately_left_alone(env, monkeypatch):
    """The other half of the decision, pinned so it cannot be "tidied" into agreement by accident.

    The per-artisan view still walks the rows in Python. That is not an oversight — it is already
    bounded to one artisan's interviews, and putting it on the same statements is a change to what
    the most-used surface reports, which does not belong in a performance fix. If somebody makes
    that change deliberately, this test is the one that should be deleted in the same commit.
    """
    from prisma.actions import QuestionnaireInterviewActions

    original = QuestionnaireInterviewActions.find_many
    seen: list[dict] = []

    async def _spy(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `find_many`
        seen.append(kwargs.get("include") or {})
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(QuestionnaireInterviewActions, "find_many", _spy)

    response = env["client"].get(
        f"/api/questionnaire/completion?artisanId={env['artisan_id']}", headers=env["auth"]
    )
    assert response.status_code == 200, response.text
    assert any("responses" in include and "media" in include for include in seen)


# --------------------------------------------------------------------------------------
# 2. That nothing moved — the four rules the SQL had to reproduce
# --------------------------------------------------------------------------------------


def test_a_recorded_answer_still_completes_its_section(env):
    """The ordinary case, and the one the DISTINCT join answers."""
    assert env["sections"]["ANSWER"] in _derived_sections(env)


def test_a_blank_answer_still_completes_nothing(env):
    """``is_empty_value`` trims before it decides, so an answer of nothing but whitespace is not an
    answer. The SQL says the same thing with ``btrim``, and this is the assertion that catches a
    rewrite which forgot to — the section would go green on every interview where somebody opened a
    question and typed a space."""
    assert env["sections"]["BLANK"] not in _derived_sections(env)


def test_a_photograph_tagged_with_its_question_still_completes_that_section(env):
    """The ``extraMetadata.questionId`` signal, now read with ``->>`` instead of by loading the blob.
    ``->>`` on a JSONB value that is not an object returns NULL rather than raising, which is exactly
    what the ``isinstance(meta, dict)`` guard in the Python walk does."""
    assert env["sections"]["META"] in _derived_sections(env)


def test_a_clip_whose_filename_leads_with_the_section_code_still_counts(env):
    """The only section signal the app's recorded questionnaire clips carry, and the one most easily
    lost in a rewrite: ``split_part(filename, '_', 1)`` has to mean the same as
    ``filename.split("_", 1)[0]``, including on a name with no underscore in it at all."""
    assert env["sections"]["FILE"] in _derived_sections(env)


def test_the_scoped_and_unscoped_matrices_agree_cell_for_cell(env):
    """THE PROPERTY A READER OF THE SCREEN ACTUALLY DEPENDS ON. The per-artisan view and the
    whole-repository view now compute the same booleans two different ways, and a person who clicks
    from one to the other must not see the answer change. Any drift between the Python walk and the
    two statements shows up here, whichever rule it is in."""
    assert _derived_sections(env) == _derived_sections(env, artisan_id=env["artisan_id"])
