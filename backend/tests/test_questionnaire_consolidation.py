"""The consolidated per-artisan questionnaire: attribution, conflicts, ordering and provenance.

WHY THESE ARE UNIT TESTS AND NOT A LIVE CHECK. The live repository currently holds ZERO
``QuestionnaireResponse`` rows — every answer in it is an audio clip attached to an interview — so
the two behaviours that matter most here cannot be demonstrated against it at all:

* a question answered DIFFERENTLY in two interviews must keep both, newest first, flagged;
* an answer given in a group sitting must not be attributed to one artisan.

Both are the reason the view exists, so they are pinned here with a fake database instead of left
to be discovered the first time a researcher types answers into two sittings.
"""

from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.services import questionnaire_consolidation as qc
from app.services.questionnaire_consolidation import (
    CSV_COLUMNS,
    GROUP,
    RECORDED,
    SOLE,
    consolidated_rows,
)

ARTISAN_ID = "artisan-subject"
OTHER_ID = "artisan-other"


def _dt(day: int) -> datetime:
    return datetime(2026, 6, day, 9, 0, tzinfo=UTC)


class _Delegate:
    """One Prisma model delegate, returning a canned list however it is queried."""

    def __init__(self, rows):
        self._rows = rows

    async def find_many(self, **_kwargs):
        return list(self._rows)

    async def find_unique(self, **_kwargs):
        return self._rows[0] if self._rows else None


class _Db:
    def __init__(self, **delegates):
        for name, rows in delegates.items():
            setattr(self, name, _Delegate(rows))


def _question(qid, section_id, order, prompt):
    return SimpleNamespace(id=qid, sectionId=section_id, sortOrder=order, prompt=prompt)


def _interview(iid, title, day):
    return SimpleNamespace(
        id=iid,
        title=title,
        interviewDate=None,
        recordedAt=_dt(day),
        createdAt=_dt(day),
        status="APPROVED",
        workshop=SimpleNamespace(title="Almora workshop"),
    )


def _response(rid, iid, qid, text, notes=None, by="Field worker"):
    return SimpleNamespace(
        id=rid,
        interviewId=iid,
        questionId=qid,
        answerText=text,
        notes=notes,
        answeredBy=SimpleNamespace(name=by),
    )


def _link(iid, aid, name):
    return SimpleNamespace(interviewId=iid, artisanId=aid, artisan=SimpleNamespace(name=name))


def _media(mid, iid, filename, day, extra=None, transcript="spoken answer"):
    return SimpleNamespace(
        id=mid,
        questionnaireInterviewId=iid,
        originalFilename=filename,
        mediaType="AUDIO",
        url=f"https://example.invalid/{mid}",
        transcriptText=transcript,
        transcriptStatus="COMPLETED",
        extraMetadata=extra,
        recordedAt=_dt(day),
        createdAt=_dt(day),
    )


def _build_db():
    """Two interviews: a SOLO sitting on the 20th, a THREE-artisan sitting on the 24th.

    Question q1 is answered in both, with different text — the conflict case. q2 is answered only in
    the group sitting. One clip names its section by filename token, one names a question outright,
    and one names nothing.
    """
    sections = [
        SimpleNamespace(id="sec-a", code="A", title="Origin", sortOrder=1, isActive=True),
        SimpleNamespace(id="sec-b", code="B", title="Family", sortOrder=2, isActive=True),
    ]
    questions = [
        _question("q1", "sec-a", 1, "How did you learn the craft?"),
        _question("q2", "sec-a", 2, "Who taught you?"),
        _question("q3", "sec-b", 1, "Does your family practise it?"),
    ]
    interviews = [_interview("iv-solo", "Vikram alone", 20), _interview("iv-group", "Group of three", 24)]
    links = [
        _link("iv-solo", ARTISAN_ID, "Subject Artisan"),
        _link("iv-group", ARTISAN_ID, "Subject Artisan"),
        _link("iv-group", OTHER_ID, "Other Artisan"),
        _link("iv-group", "artisan-third", "Third Artisan"),
    ]
    responses = [
        _response("r1", "iv-solo", "q1", "From my father, at eleven."),
        _response("r2", "iv-group", "q1", "From my uncle, after school."),
        _response("r3", "iv-group", "q2", "My uncle.", notes="hesitant"),
    ]
    media = [
        _media("m1", "iv-solo", "B_SEC_SUBJECT_000102_2006.mp3", 20),
        _media("m2", "iv-group", "clip.mp3", 24, extra={"questionId": "q3"}),
        _media("m3", "iv-group", "QUESTIONNAIRE_nosection_AUD_1.mp3", 24),
    ]
    return _Db(
        artisan=[SimpleNamespace(id=ARTISAN_ID, name="Subject Artisan", place="Almora",
                                craft=SimpleNamespace(name="Cane and Bamboo"))],
        questionnairesection=sections,
        questionnairequestion=questions,
        questionnaireinterview=interviews,
        questionnaireinterviewartisan=links,
        questionnaireresponse=responses,
        mediafile=media,
    )


@pytest.fixture()
def payload(monkeypatch):
    monkeypatch.setattr(qc, "db", _build_db())
    user = SimpleNamespace(id="viewer", role="MASTER_ADMIN")
    import asyncio

    return asyncio.run(qc.consolidate_for_artisan(ARTISAN_ID, user))


def _section(payload, code):
    return next(s for s in payload["sections"] if s["code"] == code)


def _question_row(payload, code, qid):
    return next(q for q in _section(payload, code)["questions"] if q["id"] == qid)


# --- Requirement: consolidate by artisan, in the questionnaire's order ---------------------------


def test_sections_and_questions_read_in_questionnaire_order_not_by_interview(payload):
    assert [s["code"] for s in payload["sections"]] == ["A", "B"]
    assert [q["sortOrder"] for q in _section(payload, "A")["questions"]] == [1, 2]


def test_answers_from_every_interview_the_artisan_belongs_to_are_present(payload):
    assert payload["summary"]["interviewCount"] == 2
    assert payload["summary"]["typedAnswerCount"] == 3


# --- Requirement: conflicts are shown, never silently resolved -----------------------------------


def test_same_question_answered_differently_keeps_both_and_flags_the_conflict(payload):
    q1 = _question_row(payload, "A", "q1")
    assert q1["conflict"] is True
    assert len(q1["answers"]) == 2
    assert {a["answerText"] for a in q1["answers"]} == {
        "From my father, at eleven.",
        "From my uncle, after school.",
    }
    assert payload["summary"]["conflictCount"] == 1


def test_conflicting_answers_are_ordered_most_recent_first(payload):
    q1 = _question_row(payload, "A", "q1")
    assert [a["interviewId"] for a in q1["answers"]] == ["iv-group", "iv-solo"]


def test_one_answer_repeated_verbatim_is_not_a_conflict(monkeypatch):
    db = _build_db()
    db.questionnaireresponse = _Delegate(
        [
            _response("r1", "iv-solo", "q1", "From my father."),
            # Same answer, different whitespace and case — the same account given twice, not a change.
            _response("r2", "iv-group", "q1", "  from my  FATHER. "),
        ]
    )
    monkeypatch.setattr(qc, "db", db)
    import asyncio

    result = asyncio.run(
        qc.consolidate_for_artisan(ARTISAN_ID, SimpleNamespace(id="v", role="MASTER_ADMIN"))
    )
    assert _question_row(result, "A", "q1")["conflict"] is False
    assert len(_question_row(result, "A", "q1")["answers"]) == 2


# --- Requirement: never misattribute a group answer ----------------------------------------------


def test_solo_sitting_answer_is_attributed_to_the_artisan(payload):
    solo = next(a for a in _question_row(payload, "A", "q1")["answers"] if a["interviewId"] == "iv-solo")
    assert solo["attribution"] == SOLE
    assert solo["artisanCount"] == 1
    assert solo["coParticipants"] == []


def test_group_sitting_answer_is_not_attributed_to_the_artisan(payload):
    group = next(a for a in _question_row(payload, "A", "q1")["answers"] if a["interviewId"] == "iv-group")
    assert group["attribution"] == GROUP
    assert group["artisanCount"] == 3
    assert group["coParticipants"] == ["Other Artisan", "Third Artisan"]


def test_attribution_is_a_function_of_the_sitting_size_only():
    # The models carry no per-artisan response link, so sitting size is the ONLY thing that can
    # decide this. Pinned so a later "improvement" that guesses a speaker fails loudly here.
    assert qc._attribution(0) == SOLE
    assert qc._attribution(1) == SOLE
    assert qc._attribution(2) == GROUP
    assert qc._attribution(5) == GROUP


# --- Requirement: provenance on every answer -----------------------------------------------------


def test_every_answer_row_names_its_source_and_date(payload):
    rows = [
        answer
        for section in payload["sections"]
        for question in section["questions"]
        for answer in question["answers"]
    ] + [r for section in payload["sections"] for r in section["recordings"]] + payload["unfiled"]
    assert rows
    for row in rows:
        assert row["interviewId"]
        assert row["interviewTitle"]
        assert row["interviewDate"]
        assert row["attribution"] in {SOLE, GROUP}
        assert isinstance(row["artisanCount"], int)


def test_date_basis_is_reported_when_interview_date_is_absent(payload):
    # Every interview in the live repository has a null interviewDate, so the fallback is the normal
    # path — and the row has to say it is a fallback rather than pass recordedAt off as the sitting's
    # own date.
    assert {i["dateBasis"] for i in payload["interviews"]} == {"recordedAt"}


def test_interview_date_wins_when_present():
    interview = SimpleNamespace(interviewDate=_dt(1), recordedAt=_dt(9), createdAt=_dt(9))
    assert qc._interview_date(interview) == (_dt(1), "interviewDate")
    assert qc._interview_date(SimpleNamespace(interviewDate=None, recordedAt=_dt(9), createdAt=_dt(2))) == (
        _dt(9),
        "recordedAt",
    )


# --- Recordings: placed where the data actually says, never further ------------------------------


def test_clip_naming_a_question_lands_on_that_question(payload):
    q3 = _question_row(payload, "B", "q3")
    assert [a["kind"] for a in q3["answers"]] == [RECORDED]
    assert q3["answers"][0]["mediaId"] == "m2"


def test_clip_naming_only_a_section_lands_on_the_section_not_a_question(payload):
    section_b = _section(payload, "B")
    assert [r["mediaId"] for r in section_b["recordings"]] == ["m1"]


def test_clip_naming_nothing_is_listed_as_unfiled_rather_than_dropped(payload):
    assert [r["mediaId"] for r in payload["unfiled"]] == ["m3"]
    assert payload["summary"]["unfiledRecordingCount"] == 1


# --- Export --------------------------------------------------------------------------------------


def test_csv_has_one_row_per_answer_so_a_conflict_stays_legible(payload):
    rows = consolidated_rows(payload)
    prompt_index = CSV_COLUMNS.index("Question")
    learned = [r for r in rows if r[prompt_index] == "How did you learn the craft?"]
    assert len(learned) == 2
    conflict_index = CSV_COLUMNS.index("Conflicting answers on this question")
    assert all(r[conflict_index] == "Yes" for r in learned)


def test_csv_spells_out_unattributable_answers(payload):
    rows = consolidated_rows(payload)
    column = CSV_COLUMNS.index("Attributable to this artisan")
    values = {r[column] for r in rows}
    assert "No - group sitting, speaker not recorded" in values
    assert "Yes" in values


def test_csv_row_width_matches_the_header(payload):
    rows = consolidated_rows(payload)
    assert rows
    assert all(len(row) == len(CSV_COLUMNS) for row in rows)


def test_csv_carries_recordings_and_their_transcripts(payload):
    rows = consolidated_rows(payload)
    kind = CSV_COLUMNS.index("Answer kind")
    answer = CSV_COLUMNS.index("Answer")
    recorded = [r for r in rows if r[kind] == "Recording"]
    assert len(recorded) == 3
    assert all(r[answer] == "spoken answer" for r in recorded)


# --- Identity numbers never reach this surface ----------------------------------------------------


def test_payload_carries_no_identity_numbers(payload):
    import json

    blob = json.dumps(payload).lower()
    assert "aadhaar" not in blob
    assert "pehchan" not in blob


def test_unknown_artisan_yields_none(monkeypatch):
    db = _build_db()
    db.artisan = _Delegate([])
    monkeypatch.setattr(qc, "db", db)
    import asyncio

    assert (
        asyncio.run(qc.consolidate_for_artisan("nope", SimpleNamespace(id="v", role="MASTER_ADMIN")))
        is None
    )


# --- Requirement: a rich-text answer is prose everywhere this document is read --------------------
#
# WHAT WENT WRONG. ``QuestionnaireResponse.answerText`` is a ``String?`` that has held plain prose
# since this feature was written, and since the answer box became a ``RichTextField`` on 2026-08-31
# it sometimes holds a serialised document instead (``rich_text``'s "rich text inside a plain String
# column" section carries the encoding). This module passed the raw column value straight through, so
# ONE unflattened value went four ways at once:
#
#   * onto the web consolidated page and the Android consolidated screen, which printed
#     ``{"blocks":[{"kind":"PARAGRAPH",…}]}`` at a reader;
#   * into ``consolidated.csv``, which a researcher archives;
#   * into ``_answer_key``, which decides whether two sittings CONFLICT — so the SAME sentence typed
#     in two sittings, one of them formatted, produced two keys and the screen said the two accounts
#     disagreed. That is the wrong answer dressed as a right one, in the one view built to show a
#     reader where the record genuinely differs.
#
# And the emptiness test above it read a JSON-shaped string as filled, so a box that was focused and
# left alone was consolidated as a real answer.


def _rich(text: str) -> str:
    """What the web editor writes into the column once a mark is applied.

    Built through ``rich_text`` rather than hand-typed so the fixture cannot drift from the encoder;
    ``test_rich_text_stored_columns`` is what pins that the two agree.
    """
    from app.services.rich_text import Mark, RichBlock, RichDoc, RichSpan, to_stored_text

    return to_stored_text(
        RichDoc(blocks=(RichBlock(spans=(RichSpan(text, marks=frozenset({Mark.BOLD})),)),))
    )


def _consolidate(monkeypatch, responses):
    db = _build_db()
    db.questionnaireresponse = _Delegate(responses)
    monkeypatch.setattr(qc, "db", db)
    import asyncio

    return asyncio.run(
        qc.consolidate_for_artisan(ARTISAN_ID, SimpleNamespace(id="v", role="MASTER_ADMIN"))
    )


class TestRichTextAnswers:
    def test_the_answer_key_reads_a_formatted_answer_as_its_prose(self):
        """The pure half, and the one that decides ``conflict``."""
        assert qc._answer_key(_rich("From my father.")) == qc._answer_key("From my father.")

    def test_the_answer_key_still_folds_whitespace_and_case(self):
        """Unchanged behaviour for prose — the flattening returns a plain string by identity."""
        assert qc._answer_key("  from my  FATHER. ") == qc._answer_key("From my father.")

    def test_the_same_answer_typed_once_formatted_is_not_a_conflict(self, monkeypatch):
        """THE DEFECT, end to end. Two sittings, one sentence, one of them bolded."""
        result = _consolidate(
            monkeypatch,
            [
                _response("r1", "iv-solo", "q1", "From my father."),
                _response("r2", "iv-group", "q1", _rich("From my father.")),
            ],
        )
        assert _question_row(result, "A", "q1")["conflict"] is False
        assert result["summary"]["conflictCount"] == 0

    def test_the_payload_carries_prose_and_never_the_documents_json(self, monkeypatch):
        """What the web page, the Android screen and the CSV all read."""
        result = _consolidate(
            monkeypatch, [_response("r1", "iv-solo", "q1", _rich("From my father."))]
        )
        answers = _question_row(result, "A", "q1")["answers"]
        assert [a["answerText"] for a in answers] == ["From my father."]

    def test_the_csv_cell_carries_prose_too(self, monkeypatch):
        """``consolidated_rows`` reads the same key, so this is the archived file's half of it."""
        result = _consolidate(
            monkeypatch, [_response("r1", "iv-solo", "q1", _rich("From my father."))]
        )
        cells = [cell for row in consolidated_rows(result) for cell in row if isinstance(cell, str)]
        assert "From my father." in cells
        assert not any("{\"blocks\"" in cell for cell in cells)

    def test_an_empty_document_is_not_consolidated_as_an_answer(self, monkeypatch):
        """A rich-text box focused and left alone saves ``{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}``.
        Read raw it is a non-empty string, so it was carried as a real answer with an empty cell under
        the artisan's name and counted in ``typedAnswerCount``."""
        result = _consolidate(
            monkeypatch,
            [_response("r1", "iv-solo", "q1", '{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}')],
        )
        assert result["summary"]["typedAnswerCount"] == 0

    def test_an_empty_document_with_a_note_is_still_kept(self, monkeypatch):
        """The emptiness test is an AND over the answer and the note, and flattening must not turn it
        into a drop: an interviewer's note with no typed answer is a real row and always was."""
        result = _consolidate(
            monkeypatch,
            [
                _response(
                    "r1",
                    "iv-solo",
                    "q1",
                    '{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}',
                    notes="would not say",
                )
            ],
        )
        answers = _question_row(result, "A", "q1")["answers"]
        assert [a["notes"] for a in answers] == ["would not say"]
