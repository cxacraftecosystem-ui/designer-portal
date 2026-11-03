import asyncio
from types import SimpleNamespace
from typing import Any

from fastapi import HTTPException

from app.services.records import (
    CLEARABLE_KEYS,
    clean_data,
    merge_field_provenance,
    with_id_tiebreak,
)


def _saver():
    return SimpleNamespace(id="usr_7", name="R. Menon")


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


def _stored_extra(new_data: dict[str, Any]) -> dict[str, Any]:
    wrapper = new_data["extraMetadata"]
    return getattr(wrapper, "data", wrapper)



def test_a_patch_of_one_field_keeps_every_other_extra_metadata_key():
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
    assert stored["fieldProvenance"]["phone"]["by"] == "usr_7"


def test_the_server_owned_workshop_stamp_is_not_resurrected_from_the_stored_row():
    previous = _Row(
        id="prod_1",
        extraMetadata={
            "specialisation": "Block printing",
            "workshopSubmission": {"workshopId": "wsh_1", "needsAdminApproval": True},
        },
    )
    new_data: dict[str, Any] = {"remarks": "corrected"}

    merge_field_provenance(new_data, _saver(), previous=previous)

    stored = _stored_extra(new_data)
    assert "workshopSubmission" not in stored, "the single writer must stay the single writer"
    assert stored["specialisation"] == "Block printing", "the rest of the column still carries"


def test_the_authoritative_workshop_stamp_in_the_payload_is_kept():
    previous = _Row(id="prod_1", extraMetadata={"workshopSubmission": {"workshopId": "wsh_OLD"}})
    new_data: dict[str, Any] = {
        "remarks": "corrected",
        "extraMetadata": {"workshopSubmission": {"workshopId": "wsh_NEW"}},
    }

    merge_field_provenance(new_data, _saver(), previous=previous)

    assert _stored_extra(new_data)["workshopSubmission"] == {"workshopId": "wsh_NEW"}


def test_a_client_cannot_write_its_own_field_provenance():
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
    assert stored["pincode"] == "302013"


def test_a_create_is_unchanged_by_the_seed():
    new_data: dict[str, Any] = {"name": "Ramesh", "extraMetadata": {"pincode": "302012"}}

    merge_field_provenance(new_data, _saver(), previous=None)

    stored = _stored_extra(new_data)
    assert stored["pincode"] == "302012"
    assert stored["fieldProvenance"]["name"]["by"] == "usr_7"


def test_a_previous_row_with_no_metadata_at_all_still_works():
    previous = _Row(id="art_1", name="Ramesh")
    new_data: dict[str, Any] = {"name": "Ramesh Kumar"}

    merge_field_provenance(new_data, _saver(), previous=previous)

    assert list(_stored_extra(new_data)) == ["fieldProvenance"]



def test_a_nullable_scalar_named_by_the_caller_survives_the_clean():
    payload = {"phone": None, "email": None, "notes": None, "place": "Bagru"}

    cleaned = clean_data(payload, clearable=("phone", "email", "notes"))

    assert cleaned["phone"] is None
    assert cleaned["email"] is None
    assert cleaned["notes"] is None
    assert cleaned["place"] == "Bagru"


def test_a_null_the_caller_did_not_declare_clearable_is_still_stripped():
    cleaned = clean_data({"phone": None, "name": "Ramesh"}, clearable=("notes",))

    assert "phone" not in cleaned
    assert cleaned["name"] == "Ramesh"


def test_the_global_set_still_applies_when_a_caller_names_its_own():
    cleaned = clean_data({"workshopId": None, "notes": None}, clearable=("notes",))

    assert cleaned["workshopId"] is None
    assert cleaned["notes"] is None


def test_the_global_clearable_set_stays_small_and_holds_no_email():
    assert "email" not in CLEARABLE_KEYS
    assert "phone" not in CLEARABLE_KEYS
    assert "notes" not in CLEARABLE_KEYS


def test_clearing_and_title_casing_compose():
    cleaned = clean_data({"place": None, "village": "bagru"}, clearable=("place",))

    assert cleaned["place"] is None
    assert cleaned["village"] == "Bagru"



def test_the_id_tiebreaker_is_appended_in_the_direction_of_the_sort():
    assert with_id_tiebreak({"createdAt": "desc"}) == [{"createdAt": "desc"}, {"id": "desc"}]
    assert with_id_tiebreak({"name": "asc"}) == [{"name": "asc"}, {"id": "asc"}]


def test_a_multi_clause_order_keeps_its_clauses_and_their_order():
    assert with_id_tiebreak([{"sortOrder": "asc"}, {"createdAt": "asc"}]) == [
        {"sortOrder": "asc"},
        {"createdAt": "asc"},
        {"id": "asc"},
    ]


def test_an_order_that_already_names_id_is_returned_unchanged():
    assert with_id_tiebreak([{"updatedAt": "desc"}, {"id": "desc"}]) == [
        {"updatedAt": "desc"},
        {"id": "desc"},
    ]
    assert with_id_tiebreak({"id": "asc"}) == [{"id": "asc"}]


def test_the_helper_does_not_mutate_the_order_it_was_given():
    order = {"createdAt": "desc"}
    with_id_tiebreak(order)
    assert order == {"createdAt": "desc"}


def test_the_search_route_pages_five_buckets_over_a_total_order():
    from app.api.routes import search

    assert search._ORDER == [{"createdAt": "desc"}, {"id": "desc"}]



class _Delegate:
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


class _Client(SimpleNamespace):
    def tx(self) -> Any:
        client = self

        class _Tx:
            async def __aenter__(self) -> Any:
                return client

            async def __aexit__(self, *_exc: object) -> bool:
                return False

        return _Tx()



class _CreateBody(SimpleNamespace):
    def __init__(self, *, artisan_ids, responses):
        super().__init__(
            artisanIds=artisan_ids,
            responses=responses,
            workshopId=None,
            designWorkshopId=None,
        )

    def model_dump(self, **_kwargs):
        return {"title": "Refused body"}


def _drive_rejected_create(monkeypatch, *, artisans_found, questions_found, payload):
    from app.api.routes import questionnaire

    class _Exploding:
        async def create(self, **_kwargs):
            raise AssertionError(
                "create_interview INSERTED an interview for a body it went on to refuse. That row "
                "outlives the 404 - nothing on this path is in a transaction - and it holds a "
                "client-authored artisanSetKey in a repository-wide unique index."
            )

        async def find_unique(self, **_kwargs):
            return None

        async def find_first(self, **_kwargs):
            return None

    async def _no_workshop_check(_user, _workshop_id):
        return None

    async def _may_file(_design_workshop_id, _user):
        return None

    async def _attach_location(data):
        return data

    monkeypatch.setattr(questionnaire, "default_interview_date", lambda data: data)
    monkeypatch.setattr(questionnaire, "attach_location", _attach_location)
    monkeypatch.setattr(questionnaire, "stamp_workshop_submission", lambda *_a, **_kw: None)
    monkeypatch.setattr(questionnaire, "merge_field_provenance", lambda *_a, **_kw: None)
    monkeypatch.setattr(questionnaire, "jsonify_metadata", lambda *_a, **_kw: None)
    monkeypatch.setattr(questionnaire, "apply_status_policy_create", lambda *_a, **_kw: None)
    monkeypatch.setattr(questionnaire, "pin_pending_if_late", lambda *_a, **_kw: None)
    async def _no_merges(ids, **_kw):
        return ids

    monkeypatch.setattr(questionnaire, "redirect_ids", _no_merges)

    monkeypatch.setattr(
        questionnaire,
        "db",
        _Client(
            questionnaireinterview=_Exploding(),
            artisan=_Delegate(artisans_found),
            questionnairequestion=_Delegate(questions_found),
        ),
    )
    monkeypatch.setattr(questionnaire, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(questionnaire, "assert_may_file_under", _may_file)
    monkeypatch.setattr(questionnaire, "assert_can_create_records", lambda _user: None)

    try:
        asyncio.run(questionnaire.create_interview(payload, _saver()))
    except HTTPException as raised:
        return raised
    raise AssertionError("create_interview accepted a body naming records that do not exist")


def test_a_create_naming_an_unknown_artisan_is_refused_before_anything_is_written(monkeypatch):
    payload = _CreateBody(artisan_ids=["cartisanreal", "does-not-exist"], responses=None)
    raised = _drive_rejected_create(
        monkeypatch,
        artisans_found=[_Row(id="cartisanreal")],
        questions_found=[],
        payload=payload,
    )
    assert raised.status_code == 404


def test_a_create_naming_an_unknown_question_is_refused_before_anything_is_written(monkeypatch):
    payload = _CreateBody(
        artisan_ids=["cartisanreal"], responses=[SimpleNamespace(questionId="no-such-question")]
    )
    raised = _drive_rejected_create(
        monkeypatch,
        artisans_found=[_Row(id="cartisanreal")],
        questions_found=[],
        payload=payload,
    )
    assert raised.status_code == 404


def test_a_duplicate_question_in_one_body_is_saved_once_last_wins(monkeypatch):
    from app.api.routes import questionnaire

    responses = _Delegate(rows=[])
    questions = _Delegate(rows=[SimpleNamespace(id="q1")])
    monkeypatch.setattr(
        questionnaire,
        "db",
        SimpleNamespace(questionnaireresponse=responses, questionnairequestion=questions,
                        questionnaireinterview=_Delegate()),
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
    from app.api.routes import questionnaire

    stored = SimpleNamespace(
        id="resp_1", questionId="q1", answerText="old", notes=None, answeredById="usr_7"
    )
    responses = _Delegate(rows=[stored])
    questions = _Delegate(rows=[SimpleNamespace(id="q1")])
    monkeypatch.setattr(
        questionnaire,
        "db",
        SimpleNamespace(questionnaireresponse=responses, questionnairequestion=questions,
                        questionnaireinterview=_Delegate()),
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



def test_folding_a_create_into_an_existing_interview_keeps_its_stored_metadata(monkeypatch):
    from app.api.routes import questionnaire
    from app.services.workshop_access import WorkshopSubmissionCheck

    interviews = _Delegate()

    async def _update(where, data):
        interviews.updated.append((where, data))
        return _Row(id="int_1", **data)

    async def _no_relations(_rows, _relations):
        return None

    interviews.update = _update
    async def _no_media_urls(_viewer):
        return set()

    monkeypatch.setattr(questionnaire, "db", _Client(questionnaireinterview=interviews))
    monkeypatch.setattr(questionnaire, "hydrate_relations", _no_relations)
    monkeypatch.setattr(questionnaire, "media_url_owners", _no_media_urls)
    monkeypatch.setattr(questionnaire, "public_encode", lambda row, _viewer=None, **_kw: row)

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
    assert stored["workshopSubmission"]["workshopId"] == "ws_1"
    assert stored["specialisation"] == "Block printing"
    assert stored["fieldProvenance"]["title"]["byName"] == "A. Rao"



def test_the_interview_patch_route_actually_declares_its_nullable_scalars(monkeypatch):
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

        async def _guard(_record, _user, _data, _kind, *, client=None):
            return False

        async def _status_policy(_user, _record, _data):
            return None

        async def _no_relations(_rows, _relations):
            return None

        interviews.update = _update
        interviews.find_unique = _find_unique
        monkeypatch.setattr(questionnaire, "db", _Client(questionnaireinterview=interviews))
        monkeypatch.setattr(questionnaire, "require_record", _require_record)
        monkeypatch.setattr(questionnaire, "guard_record_edit", _guard)
        monkeypatch.setattr(questionnaire, "apply_status_policy_update", _status_policy)
        async def _no_media_urls(_viewer):
            return set()

        monkeypatch.setattr(questionnaire, "hydrate_relations", _no_relations)
        monkeypatch.setattr(questionnaire, "media_url_owners", _no_media_urls)
        monkeypatch.setattr(questionnaire, "public_encode", lambda row, _viewer=None, **_kw: row)

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
    from app.api.routes import questionnaire
    from app.services.workshop_access import WorkshopSubmissionCheck

    interviews = _Delegate()

    async def _update(where, data):
        interviews.updated.append((where, data))
        return _Row(id="int_1", **data)

    async def _no_relations(_rows, _relations):
        return None

    interviews.update = _update
    async def _no_media_urls(_viewer):
        return set()

    monkeypatch.setattr(questionnaire, "db", _Client(questionnaireinterview=interviews))
    monkeypatch.setattr(questionnaire, "hydrate_relations", _no_relations)
    monkeypatch.setattr(questionnaire, "media_url_owners", _no_media_urls)
    monkeypatch.setattr(questionnaire, "public_encode", lambda row, _viewer=None, **_kw: row)

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
    check = WorkshopSubmissionCheck(workshopId="ws_open", submittedAt="2026-08-22T09:00:00+00:00")
    assert check.metadata["workshopSubmission"]["needsAdminApproval"] is False

    asyncio.run(questionnaire.merge_into_interview(existing, payload, _saver(), check))

    written = interviews.updated[0][1]
    stamp = getattr(written["extraMetadata"], "data", written["extraMetadata"])["workshopSubmission"]
    assert stamp["workshopId"] == "ws_open", "the fold still records the workshop it linked to"
    assert stamp["needsAdminApproval"] is True, "the fold laundered an unapproved late submission"
    assert stamp["relinkedFrom"] == "ws_late", "the flag carried without saying where it came from"
