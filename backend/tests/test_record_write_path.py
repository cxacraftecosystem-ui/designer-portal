"""The four defects the record write path shares, all provable without a database.

Every one of them was a 200 that lied — the API reported a save that did not happen, or reported a
save that quietly destroyed something the caller never mentioned:

* ``merge_field_provenance`` rebuilt ``extraMetadata`` from the request body, so a PATCH of one
  column deleted every other key stored on it. ``extraMetadata`` is the FIRST entry in
  ``access.REVISION_SKIP_FIELDS``, so no ``RecordRevision`` recorded the loss either: no undo, and
  no trace.
* ``clean_data`` stripped every ``None`` that was not one of ten global keys, so clearing any other
  nullable scalar was a no-op that answered 200.
* Offset paging over ``createdAt`` alone is a non-total order, so rows are handed over twice or not
  at all — and a page with the right number of rows on it looks complete.
* A body naming one question twice built two INSERTs against a unique constraint: a 500 out of a
  schema-valid request.

The provenance, clean and paging helpers are pure dict work, and the two answer-batch savers are
driven here with a recording stub in place of ``db`` — so this whole module runs on a laptop with no
Postgres, which is where these regressions would otherwise be caught only in production.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

from app.services.records import (
    CLEARABLE_KEYS,
    clean_data,
    merge_field_provenance,
    with_id_tiebreak,
)


def _saver():
    return SimpleNamespace(id="usr_7", name="R. Menon")


class _Row:
    """A stored record. Answers ``None`` for every column a test did not set, because the provenance
    loop asks a record for whichever columns the payload carries and a ``SimpleNamespace`` would
    raise on the ones a test does not care about."""

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):  # only reached for names __init__ did not set
        return None


def _stored_extra(new_data: dict[str, Any]) -> dict[str, Any]:
    """The ``extraMetadata`` value bound for Prisma, unwrapped from its ``Json`` wrapper.

    Unwrapped deliberately rather than indexed through: the column is a Json column and
    ``merge_field_provenance`` assigns ``Json(base_extra)``, so indexing the wrapper would assert on
    prisma's ``__getitem__`` instead of on what is stored.
    """
    wrapper = new_data["extraMetadata"]
    return getattr(wrapper, "data", wrapper)


# --------------------------------------------------------------------------------------
# B2 — every record update silently destroyed the rest of extraMetadata
# --------------------------------------------------------------------------------------


def test_a_patch_of_one_field_keeps_every_other_extra_metadata_key():
    """**THE DEFECT, PINNED AS FIXED.**

    The three keys below are the real ones: ``design_workshops.REFERENCE_MODELS``'s Artisan ``data``
    lambda reads the legacy ``specialisation`` / ``experienceYears`` / ``age`` spellings off
    ``extraMetadata`` to fill the report's participant table, and says that read "must not be
    deleted" because it is the only remaining record for the artisans the column migration refused
    to guess at. Editing the phone number used to write back an ``extraMetadata`` holding the
    provenance blob and nothing else, and nothing on any screen or in any revision log said so.
    """
    previous = _Row(
        id="art_1",
        phone="9000000000",
        extraMetadata={
            "specialisation": "Block printing",
            "experienceYears": "30+",
            "age": "about 55",
            "fieldProvenance": {"phone": {"by": "usr_1", "byName": "A. Rao", "at": "2026-01-01"}},
        },
    )
    new_data: dict[str, Any] = {"phone": "9111111111"}

    merge_field_provenance(new_data, _saver(), previous=previous)

    stored = _stored_extra(new_data)
    assert stored["specialisation"] == "Block printing"
    assert stored["experienceYears"] == "30+"
    assert stored["age"] == "about 55"
    # And the provenance still moves, which is the thing this function is FOR.
    assert stored["fieldProvenance"]["phone"]["by"] == "usr_7"


def test_the_server_owned_workshop_stamp_is_not_resurrected_from_the_stored_row():
    """``workshop_access.stamp_workshop_submission`` is the SINGLE WRITER of ``workshopSubmission``
    and it runs immediately before this function. When it deliberately drops the stamp — a record
    being unlinked from its workshop — seeding the stored value back in here would undo that
    decision, and an unapproved late submission would stop needing an admin."""
    previous = _Row(
        id="prod_1",
        extraMetadata={
            "specialisation": "Block printing",
            "workshopSubmission": {"workshopId": "wsh_1", "needsAdminApproval": True},
        },
    )
    # What the single writer left behind: it removed the key from the payload entirely.
    new_data: dict[str, Any] = {"remarks": "corrected"}

    merge_field_provenance(new_data, _saver(), previous=previous)

    stored = _stored_extra(new_data)
    assert "workshopSubmission" not in stored, "the single writer must stay the single writer"
    assert stored["specialisation"] == "Block printing", "the rest of the column still carries"


def test_the_authoritative_workshop_stamp_in_the_payload_is_kept():
    """The other half of the same rule: the stamp the single writer DID put in the payload is the
    authoritative one, and it travels to the database untouched."""
    previous = _Row(id="prod_1", extraMetadata={"workshopSubmission": {"workshopId": "wsh_OLD"}})
    new_data: dict[str, Any] = {
        "remarks": "corrected",
        "extraMetadata": {"workshopSubmission": {"workshopId": "wsh_NEW"}},
    }

    merge_field_provenance(new_data, _saver(), previous=previous)

    assert _stored_extra(new_data)["workshopSubmission"] == {"workshopId": "wsh_NEW"}


def test_a_client_cannot_write_its_own_field_provenance():
    """``fieldProvenance`` is dropped from BOTH sides — the stored seed and the incoming body.

    Who filled in each field is this function's answer to give. A caller able to send its own blob
    could put a colleague's name against every column on the record, and the provenance panel this
    repository built to answer "who recorded this" would report it as fact.
    """
    previous = _Row(id="art_1", name="Ramesh", extraMetadata={"pincode": "302012"})
    new_data: dict[str, Any] = {
        "name": "Ramesh Kumar",
        "extraMetadata": {
            "fieldProvenance": {"name": {"by": "usr_victim", "byName": "Somebody Else"}},
            "pincode": "302013",
        },
    }

    merge_field_provenance(new_data, _saver(), previous=previous)

    stored = _stored_extra(new_data)
    assert stored["fieldProvenance"]["name"]["by"] == "usr_7"
    assert stored["fieldProvenance"]["name"]["byName"] == "R. Menon"
    # An ordinary key the client DID send still wins over the stored one — only the provenance blob
    # and the workshop stamp are refused.
    assert stored["pincode"] == "302013"


def test_a_create_is_unchanged_by_the_seed():
    """``previous`` is None on a create, so there is nothing to carry and nothing to exclude."""
    new_data: dict[str, Any] = {"name": "Ramesh", "extraMetadata": {"pincode": "302012"}}

    merge_field_provenance(new_data, _saver(), previous=None)

    stored = _stored_extra(new_data)
    assert stored["pincode"] == "302012"
    assert stored["fieldProvenance"]["name"]["by"] == "usr_7"


def test_a_previous_row_with_no_metadata_at_all_still_works():
    """The overwhelmingly common shape, and the one a seeding bug would break loudly."""
    previous = _Row(id="art_1", name="Ramesh")
    new_data: dict[str, Any] = {"name": "Ramesh Kumar"}

    merge_field_provenance(new_data, _saver(), previous=previous)

    assert list(_stored_extra(new_data)) == ["fieldProvenance"]


# --------------------------------------------------------------------------------------
# B11 — a field could not be cleared, and the API reported success
# --------------------------------------------------------------------------------------


def test_a_nullable_scalar_named_by_the_caller_survives_the_clean():
    """RETRACTING PII IS THE CASE WITH NO WORKAROUND. There is no empty string to send instead when
    the column is a nullable ``String?`` and the subject has asked for the number to be removed."""
    payload = {"phone": None, "email": None, "notes": None, "place": "Bagru"}

    cleaned = clean_data(payload, clearable=("phone", "email", "notes"))

    assert cleaned["phone"] is None
    assert cleaned["email"] is None
    assert cleaned["notes"] is None
    assert cleaned["place"] == "Bagru"


def test_a_null_the_caller_did_not_declare_clearable_is_still_stripped():
    """The default is unchanged, which is what keeps CREATE paths safe: they dump every unset
    optional as ``None`` and must not start writing explicit NULLs for boxes nobody filled in."""
    cleaned = clean_data({"phone": None, "name": "Ramesh"}, clearable=("notes",))

    assert "phone" not in cleaned
    assert cleaned["name"] == "Ramesh"


def test_the_global_set_still_applies_when_a_caller_names_its_own():
    """Union, never replacement: a route naming its own scalars must not lose ``workshopId``."""
    cleaned = clean_data({"workshopId": None, "notes": None}, clearable=("notes",))

    assert cleaned["workshopId"] is None
    assert cleaned["notes"] is None


def test_the_global_clearable_set_stays_small_and_holds_no_email():
    """THE TRAP THIS GUARDS. ``clean_data`` does not know which model a payload is bound for, so a
    name added to the global set is clearable on ALL of them: ``email`` is NOT NULL on User,
    DesignerRoster and AccessRoster, and a global entry would trade one silent no-op for a
    constraint violation on three tables. Per-model scalars go through ``clearable`` instead."""
    assert "email" not in CLEARABLE_KEYS
    assert "phone" not in CLEARABLE_KEYS
    assert "notes" not in CLEARABLE_KEYS


def test_clearing_and_title_casing_compose():
    """The clean still title-cases, and a cleared name-like column is not turned into a string."""
    cleaned = clean_data({"place": None, "village": "bagru"}, clearable=("place",))

    assert cleaned["place"] is None
    assert cleaned["village"] == "Bagru"


# --------------------------------------------------------------------------------------
# Item 8 — offset pagination over a non-total order
# --------------------------------------------------------------------------------------


def test_the_id_tiebreaker_is_appended_in_the_direction_of_the_sort():
    assert with_id_tiebreak({"createdAt": "desc"}) == [{"createdAt": "desc"}, {"id": "desc"}]
    assert with_id_tiebreak({"name": "asc"}) == [{"name": "asc"}, {"id": "asc"}]


def test_a_multi_clause_order_keeps_its_clauses_and_their_order():
    """The tiebreak goes LAST or it is not a tiebreak — first, it would be the primary sort."""
    assert with_id_tiebreak([{"sortOrder": "asc"}, {"createdAt": "asc"}]) == [
        {"sortOrder": "asc"},
        {"createdAt": "asc"},
        {"id": "asc"},
    ]


def test_an_order_that_already_names_id_is_returned_unchanged():
    """``feedback.list_feedback`` and the designer directory already spell this out by hand.
    Appending a second ``id`` clause is at best noise and at worst an error from the query
    builder."""
    assert with_id_tiebreak([{"updatedAt": "desc"}, {"id": "desc"}]) == [
        {"updatedAt": "desc"},
        {"id": "desc"},
    ]
    assert with_id_tiebreak({"id": "asc"}) == [{"id": "asc"}]


def test_the_helper_does_not_mutate_the_order_it_was_given():
    """Several routes hold their order in a module-level constant — ``search._ORDER`` is one — and a
    helper that mutated its argument would compound a clause per request."""
    order = {"createdAt": "desc"}
    with_id_tiebreak(order)
    assert order == {"createdAt": "desc"}


def test_the_search_route_pages_five_buckets_over_a_total_order():
    """``search`` pages all five buckets from one offset and bypasses ``count_and_page``, so it
    carries the tiebreak in its own module constant."""
    from app.api.routes import search

    assert search._ORDER == [{"createdAt": "desc"}, {"id": "desc"}]


# --------------------------------------------------------------------------------------
# Item 13 — a duplicate question id in one body was a 500
# --------------------------------------------------------------------------------------


class _Delegate:
    """One Prisma model delegate, recording what was written to it."""

    def __init__(self, rows: list[Any] | None = None):
        self.rows = rows or []
        self.created: list[list[dict[str, Any]]] = []
        self.updated: list[tuple[Any, Any]] = []

    async def find_many(self, **_kwargs):
        return self.rows

    async def create_many(self, data):
        self.created.append(list(data))

    async def update(self, where, data):
        self.updated.append((where, data))


def test_a_duplicate_question_in_one_body_is_saved_once_last_wins(monkeypatch):
    """AN AUTHENTICATED CALLER MUST NOT BE ABLE TO TURN A SCHEMA-VALID BODY INTO A 500.

    ``QuestionnaireResponse`` is UNIQUE on ``(interviewId, questionId)``. The validation pass already
    collapsed the ids with ``sorted({...})`` while the build loop did not, so two INSERTs went out
    for one question and the unique violation escaped as a server error.

    LAST WINS, matching the update branch — where two entries for one stored row simply run two
    updates and the later one stands. ``skip_duplicates`` would have dropped an answer somebody
    typed; first-wins would have made the two branches disagree about the same body.
    """
    from app.api.routes import questionnaire

    responses = _Delegate(rows=[])
    questions = _Delegate(rows=[SimpleNamespace(id="q1")])
    monkeypatch.setattr(
        questionnaire,
        "db",
        SimpleNamespace(questionnaireresponse=responses, questionnairequestion=questions),
    )

    body = [
        SimpleNamespace(questionId="q1", answerText="first", notes=None),
        SimpleNamespace(questionId="q1", answerText="second", notes=None),
    ]
    asyncio.run(questionnaire.upsert_responses("int_1", body, _saver()))

    assert len(responses.created) == 1
    written = responses.created[0]
    assert len(written) == 1, "two INSERTs for one unique key is the 500"
    assert written[0]["answerText"] == "second", "last wins"


def test_a_duplicate_over_an_answer_that_already_exists_updates_rather_than_inserting(monkeypatch):
    """The other half: with a stored row present both entries take the update branch and the later
    value stands — the behaviour the create branch now matches."""
    from app.api.routes import questionnaire

    stored = SimpleNamespace(
        id="resp_1", questionId="q1", answerText="old", notes=None, answeredById="usr_7"
    )
    responses = _Delegate(rows=[stored])
    questions = _Delegate(rows=[SimpleNamespace(id="q1")])
    monkeypatch.setattr(
        questionnaire,
        "db",
        SimpleNamespace(questionnaireresponse=responses, questionnairequestion=questions),
    )

    body = [
        SimpleNamespace(questionId="q1", answerText="first", notes=None),
        SimpleNamespace(questionId="q1", answerText="second", notes=None),
    ]
    asyncio.run(questionnaire.upsert_responses("int_1", body, _saver()))

    assert responses.created == []
    assert len(responses.updated) == 1
    assert responses.updated[0][1]["answerText"] == "second"


def test_the_custom_form_answer_batch_dedupes_the_same_way(monkeypatch):
    """``QuestionnaireFormAnswer`` carries the identical ``@@unique([entryId, questionId])`` and
    ``save_answers`` is modelled on ``upsert_responses``, so it had the identical defect."""
    from app.services import questionnaire_forms

    answers_delegate = _Delegate(rows=[])
    question_delegate = _Delegate(
        rows=[
            SimpleNamespace(
                id="q1",
                prompt="How long have you worked at this?",
                isActive=True,
                section=SimpleNamespace(questionnaireId="qn_1"),
            )
        ]
    )
    monkeypatch.setattr(
        questionnaire_forms,
        "db",
        SimpleNamespace(
            questionnaireformanswer=answers_delegate,
            questionnaireformquestion=question_delegate,
        ),
    )

    entry = SimpleNamespace(id="ent_1", questionnaireId="qn_1")
    body = [
        SimpleNamespace(questionId="q1", answerText="first", notes=None),
        SimpleNamespace(questionId="q1", answerText="second", notes=None),
    ]
    outcome = asyncio.run(questionnaire_forms.save_answers(entry, body, user_id="usr_7"))

    assert outcome["created"] == 1
    assert len(answers_delegate.created[0]) == 1
    assert answers_delegate.created[0][0]["answerText"] == "second"


# --------------------------------------------------------------------------------------
# B2, the second doorway — the fold path, which reaches the same column by another route
# --------------------------------------------------------------------------------------


def test_folding_a_create_into_an_existing_interview_keeps_its_stored_metadata(monkeypatch):
    """THE SEED IN ``merge_into_interview`` IS NOT A DUPLICATE OF THE ONE IN PROVENANCE.

    The B2 repair put a seed inside ``merge_field_provenance``, which made the hand-rolled seed in
    the interview PATCH route redundant — but NOT this one. ``merge_into_interview`` never calls
    ``merge_field_provenance`` at all: it builds ``fill`` fresh from the incoming body, hands it to
    ``stamp_workshop_submission``, and writes it. That path reaches the column through
    ``workshop_access.merge_extra``, which bases its result on ``data["extraMetadata"]`` — the
    PAYLOAD — and never reads the record. Delete the seed as a duplicate and the update writes
    ``{"workshopSubmission": ...}`` over a live row's whole metadata column: the same unrevisioned
    deletion B2 was, reached by posting a create for an artisan set that already has an interview
    rather than by editing one.

    It is a real doorway, not a theoretical one. ``POST /questionnaire/interviews`` folds into the
    canonical row for EVERY client, and this branch fires exactly when the fold is what first names
    the workshop — which is the same request that stamps the submission.
    """
    from app.api.routes import questionnaire
    from app.services.workshop_access import WorkshopSubmissionCheck

    interviews = _Delegate()

    async def _update(where, data):
        interviews.updated.append((where, data))
        return _Row(id="int_1", **data)

    async def _no_relations(_rows, _relations):
        return None

    interviews.update = _update
    monkeypatch.setattr(questionnaire, "db", SimpleNamespace(questionnaireinterview=interviews))
    # The fold's last two steps read relations and shape a response; neither touches the column
    # under test, and both would need a database.
    monkeypatch.setattr(questionnaire, "hydrate_relations", _no_relations)
    monkeypatch.setattr(questionnaire, "public_encode", lambda row: row)

    existing = _Row(
        id="int_1",
        title="Bagru dyers, group sitting",
        workshopId=None,
        status="PENDING",
        extraMetadata={
            "specialisation": "Block printing",
            "fieldProvenance": {"title": {"by": "usr_1", "byName": "A. Rao", "at": "2026-01-01"}},
        },
    )
    payload = SimpleNamespace(
        responses=None,
        model_dump=lambda: {
            "title": "Bagru dyers, group sitting",
            "place": None,
            "language": None,
            "notes": None,
            "interviewDate": None,
            "workshopId": "ws_1",
        },
    )
    check = WorkshopSubmissionCheck(workshopId="ws_1", submittedAt="2026-08-22T09:00:00+00:00")

    asyncio.run(questionnaire.merge_into_interview(existing, payload, _saver(), check))

    assert len(interviews.updated) == 1
    written = interviews.updated[0][1]
    stored = getattr(written["extraMetadata"], "data", written["extraMetadata"])
    # The stamp is written — that is what this branch is for...
    assert stored["workshopSubmission"]["workshopId"] == "ws_1"
    # ...and it did not arrive by flattening everything else that was on the row.
    assert stored["specialisation"] == "Block printing"
    assert stored["fieldProvenance"]["title"]["byName"] == "A. Rao"


# --------------------------------------------------------------------------------------
# B11, at the route — the argument that does the work, which nothing above asserts
# --------------------------------------------------------------------------------------


def test_the_interview_patch_route_actually_declares_its_nullable_scalars(monkeypatch):
    """THE HELPER TESTS ABOVE ALL PASS WITH THE ROUTE'S ``clearable=`` DELETED.

    Every other test in the B11 block drives the helper directly and hands it its own ``clearable``,
    so the argument could be dropped from ``update_interview`` in a refactor and the suite would stay
    green while every nullable scalar on ``QuestionnaireInterview`` went back to a 200 that changed
    nothing. That is the exact silent shape B11 was, so the route is driven here instead of the
    helper, with only the collaborators that need a database replaced.

    This was the FIRST production call site and, for one wave, the only one — the four record PATCH
    routes and the four questionnaire-form PATCHes belonged to other agents that hour and shipped
    without it. They are covered the same way now, in ``tests/test_record_patch_clearing``; this test
    stays here because ``update_interview`` lives in a different module and is nobody else's to
    assert.

    All four columns, one drive each, because the failure is per-name: dropping ``notes`` from the
    tuple and leaving the other three would pass a test that only checked one.
    """
    from app.api.routes import questionnaire

    editor = _Row(id="usr_7", name="R. Menon", role="RESEARCHER")

    for column in ("interviewDate", "place", "language", "notes"):
        stored = _Row(
            id="int_1",
            title="Bagru dyers, group sitting",
            status="PENDING",
            createdById="usr_7",
            workshopId=None,
            extraMetadata={},
            **{column: "the value the researcher is retracting"},
        )
        interviews = _Delegate()

        async def _update(where, data, _sink=interviews):
            _sink.updated.append((where, data))
            return _Row(id="int_1")

        async def _find_unique(where):
            return _Row(id="int_1")

        async def _require_record(_delegate, _record_id, _stored=stored):
            return _stored

        async def _guard(_record, _user, _data, _kind):
            return False

        async def _status_policy(_user, _record, _data):
            return None

        async def _no_relations(_rows, _relations):
            return None

        interviews.update = _update
        interviews.find_unique = _find_unique
        monkeypatch.setattr(questionnaire, "db", SimpleNamespace(questionnaireinterview=interviews))
        monkeypatch.setattr(questionnaire, "require_record", _require_record)
        monkeypatch.setattr(questionnaire, "guard_record_edit", _guard)
        monkeypatch.setattr(questionnaire, "apply_status_policy_update", _status_policy)
        monkeypatch.setattr(questionnaire, "hydrate_relations", _no_relations)
        monkeypatch.setattr(questionnaire, "public_encode", lambda row: row)

        payload = SimpleNamespace(
            artisanIds=None,
            responses=None,
            model_dump=lambda _name=column, **_kwargs: {_name: None},
        )

        asyncio.run(questionnaire.update_interview("int_1", payload, editor))

        assert len(interviews.updated) == 1, f"{column}: the PATCH wrote nothing at all"
        written = interviews.updated[0][1]
        assert column in written, (
            f"the null for {column!r} was stripped before the update — `update_interview` no longer "
            "names it in `clean_data(..., clearable=...)`, so clearing it is a 200 that does nothing"
        )
        assert written[column] is None


def test_folding_a_create_onto_a_flagged_row_does_not_launder_the_late_flag(monkeypatch):
    """A RE-LINK BY FOLD IS STILL A RE-LINK, AND BEING MOVED DOES NOT MAKE LATE WORK ON-TIME.

    ``stamp_workshop_submission`` carries an unapproved late flag across a re-link on purpose — its
    docstring says so — but only when it is handed the ``record``. This fold path used to call it
    with ``check=`` alone, so the record-aware branch could not fire and the flag was rebuilt from
    the fresh check as ``False``.

    The reachable route: PATCH ``workshopId: null`` produces an empty check, so the flagged stamp is
    CARRIED onto a row that is now unlinked. ``POST /questionnaire/interviews`` for the same artisan
    set then folds into that row, ``existing.workshopId`` is empty so ``workshopId`` enters ``fill``,
    and an in-window workshop would have cleared ``needsAdminApproval`` — which is the flag
    ``review.record_needs_admin_approval(record) and not is_admin(reviewer)`` consults, so the late
    interview would have become approvable by a reviewer who is not an admin.
    """
    from app.api.routes import questionnaire
    from app.services.workshop_access import WorkshopSubmissionCheck

    interviews = _Delegate()

    async def _update(where, data):
        interviews.updated.append((where, data))
        return _Row(id="int_1", **data)

    async def _no_relations(_rows, _relations):
        return None

    interviews.update = _update
    monkeypatch.setattr(questionnaire, "db", SimpleNamespace(questionnaireinterview=interviews))
    monkeypatch.setattr(questionnaire, "hydrate_relations", _no_relations)
    monkeypatch.setattr(questionnaire, "public_encode", lambda row: row)

    existing = _Row(
        id="int_1",
        title="Bagru dyers, group sitting",
        workshopId=None,
        status="PENDING",
        extraMetadata={
            "workshopSubmission": {
                "workshopId": "ws_late",
                "needsAdminApproval": True,
                "outOfWindow": True,
            }
        },
    )
    payload = SimpleNamespace(
        responses=None,
        model_dump=lambda: {
            "title": "Bagru dyers, group sitting",
            "place": None,
            "language": None,
            "notes": None,
            "interviewDate": None,
            "workshopId": "ws_open",
        },
    )
    # The fresh check says "in window, nothing to approve" — which is exactly what must NOT win.
    check = WorkshopSubmissionCheck(workshopId="ws_open", submittedAt="2026-08-22T09:00:00+00:00")
    assert check.metadata["workshopSubmission"]["needsAdminApproval"] is False

    asyncio.run(questionnaire.merge_into_interview(existing, payload, _saver(), check))

    written = interviews.updated[0][1]
    stamp = getattr(written["extraMetadata"], "data", written["extraMetadata"])["workshopSubmission"]
    assert stamp["workshopId"] == "ws_open", "the fold still records the workshop it linked to"
    assert stamp["needsAdminApproval"] is True, "the fold laundered an unapproved late submission"
    assert stamp["relinkedFrom"] == "ws_late", "the flag carried without saying where it came from"
