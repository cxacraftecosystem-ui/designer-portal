import json
from types import SimpleNamespace

from app.api.routes import data_browser
from app.services.record_fields import cell
from app.services.records import contains, prose_contains
from app.services.rich_text import (
    Align,
    BlockKind,
    Mark,
    RichBlock,
    RichDoc,
    RichSpan,
    from_json,
    from_stored_text,
    is_stored_rich_text,
    plain_from_stored,
    search_needles,
    to_json,
    to_plain,
    to_stored_text,
)

FORMATTED = RichDoc(blocks=(
    RichBlock(spans=(
        RichSpan("The warp is dressed with "),
        RichSpan("handspun", frozenset({Mark.BOLD})),
        RichSpan(" cotton."),
    )),
    RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("Dyed with indigo"),)),
    RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("Woven in Kutch"),)),
))

FORMATTED_PLAIN = "The warp is dressed with handspun cotton.\n• Dyed with indigo\n• Woven in Kutch"


class TestPlainStaysPlain:
    def test_none_stays_none(self):
        assert plain_from_stored(None) is None

    def test_empty_string_stays_empty(self):
        assert plain_from_stored("") == ""

    def test_plain_prose_is_returned_by_identity(self):
        value = "Weaves on a pit loom.\n\n  Trained by his father.  \n"
        assert plain_from_stored(value) is value

    def test_prose_that_merely_looks_structural_is_left_alone(self):
        for value in (
            "{}",
            "{not json at all}",
            "[see photograph 4]",
            '{"remarks": "he charges 400 per metre"}',
            '{"blocks": "not a list"}',
            "Cut into blocks, then spans of 3 feet.",
        ):
            assert plain_from_stored(value) is value, value
            assert is_stored_rich_text(value) is False, value

    def test_a_truncated_document_is_read_as_prose_rather_than_blanked(self):
        value = '{"blocks":[{"kind":"PARA'
        assert plain_from_stored(value) is value

    def test_non_strings_are_not_touched(self):
        marker = object()
        assert plain_from_stored(marker) is marker
        assert plain_from_stored(7) == 7


class TestFormattedFlattens:
    def test_stored_document_reads_back_as_prose(self):
        stored = to_stored_text(FORMATTED)
        assert plain_from_stored(stored) == FORMATTED_PLAIN
        assert "{" not in plain_from_stored(stored)

    def test_what_the_web_editor_actually_writes_flattens(self):
        for bare in (json.dumps(to_json(FORMATTED)),
                     json.dumps(to_json(FORMATTED), separators=(",", ":"))):
            assert plain_from_stored(bare) == FORMATTED_PLAIN
            assert is_stored_rich_text(bare) is True

    def test_list_markers_survive_the_flattening(self):
        assert "• Dyed with indigo" in plain_from_stored(to_stored_text(FORMATTED))

    def test_a_json_column_value_flattens_too(self):
        assert plain_from_stored(to_json(FORMATTED)) == FORMATTED_PLAIN


class TestTheTrap:
    def test_from_json_would_have_returned_the_json_as_prose(self):
        bare = json.dumps(to_json(FORMATTED))
        assert to_plain(from_json(bare)) != FORMATTED_PLAIN
        assert '"blocks"' in to_plain(from_json(bare))

    def test_from_stored_text_parses_it_instead(self):
        bare = json.dumps(to_json(FORMATTED))
        assert to_plain(from_stored_text(bare)) == FORMATTED_PLAIN

    def test_from_stored_text_still_reads_real_prose_as_prose(self):
        assert from_stored_text("Two looms, one shared.").blocks[0].text == "Two looms, one shared."

    def test_from_stored_text_on_none_is_the_empty_document(self):
        assert from_stored_text(None).is_empty


class TestStoredShape:
    def test_the_encoder_and_the_detector_agree(self):
        assert is_stored_rich_text(to_stored_text(FORMATTED)) is True
        assert plain_from_stored(to_stored_text(FORMATTED)) == FORMATTED_PLAIN

    def test_a_formatted_document_is_stored_as_the_shape_the_web_writes(self):
        stored = to_stored_text(FORMATTED)
        assert stored.startswith("{") and stored.endswith("}")
        assert json.loads(stored) == to_json(FORMATTED)

    def test_non_latin_prose_is_not_escaped_into_unreadability(self):
        doc = RichDoc(blocks=(
            RichBlock(spans=(RichSpan("कच्छ", frozenset({Mark.BOLD})), RichSpan(" में बुना"))),
        ))
        assert "कच्छ" in to_stored_text(doc)

    def test_an_unformatted_document_is_stored_as_bare_prose(self):
        plain_doc = RichDoc(blocks=(RichBlock(spans=(RichSpan("Just a note."),)),))
        assert to_stored_text(plain_doc) == "Just a note."
        assert is_stored_rich_text(to_stored_text(plain_doc)) is False

    def test_alignment_alone_counts_as_formatting(self):
        centred = RichDoc(blocks=(
            RichBlock(spans=(RichSpan("Centred."),), align=Align.CENTER),
        ))
        assert to_stored_text(centred).startswith("{")
        assert to_plain(from_stored_text(to_stored_text(centred))) == "Centred."

    def test_an_empty_document_stores_as_null_not_empty_string(self):
        assert to_stored_text(RichDoc()) is None
        assert to_stored_text(RichDoc(blocks=(RichBlock(spans=(RichSpan("  "),)),))) is None

    def test_round_trip_through_the_column_preserves_the_marks(self):
        restored = from_stored_text(to_stored_text(FORMATTED))
        assert to_json(restored) == to_json(FORMATTED)
        assert Mark.BOLD in restored.blocks[0].spans[1].marks

    def test_a_document_that_is_only_a_photograph_still_stores(self):
        picture = RichDoc(blocks=(RichBlock(kind=BlockKind.IMAGE, media="med_1"),))
        stored = to_stored_text(picture)
        assert stored is not None and stored.startswith("{")
        assert from_stored_text(stored).blocks[0].media == "med_1"
        assert plain_from_stored(stored) == ""


class TestSearchNeedles:
    def test_an_ordinary_word_needs_one_needle(self):
        assert search_needles("indigo") == ("indigo",)
        assert prose_contains("notes", "indigo") == {"notes": contains("indigo")}

    def test_a_quote_needs_the_escaped_needle_too(self):
        assert search_needles('said "no"') == ('said "no"', 'said \\"no\\"')
        clause = prose_contains("remarks", 'said "no"')
        assert clause == {"OR": [
            {"remarks": contains('said "no"')},
            {"remarks": contains('said \\"no\\"')},
        ]}

    def test_a_newline_needs_the_escaped_needle_too(self):
        assert search_needles("one\ntwo") == ("one\ntwo", "one\\ntwo")

    def test_the_escaped_needle_still_gets_like_escaping(self):
        clause = prose_contains("notes", 'a "b"')
        assert clause["OR"][1]["notes"]["contains"] == 'a \\\\"b\\\\"'

    def test_a_word_inside_a_span_is_still_found_by_the_plain_needle(self):
        stored = to_stored_text(FORMATTED)
        assert search_needles("handspun")[0] in stored
        assert search_needles("Woven in Kutch")[0] in stored

    def test_the_two_recall_gaps_that_stay_open_are_pinned_here(self):
        stored = to_stored_text(FORMATTED)
        assert "dressed with handspun cotton" not in stored
        assert "Dyed with indigo\n• Woven" not in stored
        assert "dressed with handspun cotton" in plain_from_stored(stored)


class TestCellChokepoint:
    def test_cell_flattens_a_stored_document(self):
        assert cell(to_stored_text(FORMATTED)) == FORMATTED_PLAIN

    def test_cell_flattens_bare_json_too(self):
        assert cell(json.dumps(to_json(FORMATTED))) == FORMATTED_PLAIN

    def test_cell_is_unchanged_for_everything_it_handled_before(self):
        assert cell(None) == ""
        assert cell("  Woven in Kutch  ") == "Woven in Kutch"
        assert cell(1500) == "1500"
        assert cell("{not a document}") == "{not a document}"


def _interview(answer):
    return SimpleNamespace(
        responses=[
            SimpleNamespace(
                questionId="q1",
                answerText=answer,
                question=SimpleNamespace(prompt="How is the warp dressed?", sectionCode="A", sortOrder=1),
            )
        ]
    )


class TestInterviewAnswersTxt:
    def test_a_formatted_answer_is_written_as_prose(self):
        body = data_browser._interview_answers(_interview(to_stored_text(FORMATTED)), {})
        assert FORMATTED_PLAIN in body

    def test_neither_spelling_of_the_envelope_reaches_the_file(self):
        body = data_browser._interview_answers(_interview(to_stored_text(FORMATTED)), {})
        assert '{"blocks"' not in body
        assert "{'blocks'" not in body
        assert "PARAGRAPH" not in body
        assert "BULLET_ITEM" not in body

    def test_plain_prose_is_written_exactly_as_before(self):
        body = data_browser._interview_answers(_interview("Twelve looms"), {})
        assert "[A] How is the warp dressed?\n  -> Twelve looms" in body

    def test_an_unanswered_question_still_prints_an_empty_answer(self):
        body = data_browser._interview_answers(_interview(None), {})
        assert "[A] How is the warp dressed?\n  -> \n" in body
        assert "None" not in body
