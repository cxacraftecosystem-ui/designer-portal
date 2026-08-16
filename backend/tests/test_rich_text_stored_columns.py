"""Rich text inside a plain ``String?`` column: the read boundary that keeps existing data intact.

The record forms (artisan notes, product remarks and materials, tool remarks and usage, process
notes) accept formatted prose from this release on, and they keep the columns they have always had:
``String?`` in ``prisma/schema.prisma``, no migration, no second column, no type change. That
decision is only safe because every reader can tell a formatted value from the plain prose sitting
in every other row, and flatten the first while leaving the second **untouched**.

These tests are the guarantee. They are grouped by the specific way the feature could damage data
that already exists:

* :class:`TestPlainStaysPlain` — the identity guarantee. Every row in production today is plain and
  older clients will keep writing plain forever; the flattener has to be a no-op on all of it.
* :class:`TestFormattedFlattens` — the corruption this whole lane exists to prevent: a ministry
  opening a CSV full of ``{"blocks":[…]}``.
* :class:`TestTheTrap` — ``from_json`` reads a ``str`` as PROSE, never as JSON. That rule is
  load-bearing elsewhere and is exactly what makes the string column dangerous; the pinned
  assertion is that the string front door does NOT inherit it.
* :class:`TestStoredShape` — the encoder writes prose unless the researcher actually formatted
  something, and the shape it writes when they did is byte-compatible with the web editor's
  ``encodeStoredRichText``. Those two properties are what confine the feature's cost to the rows
  somebody formatted, and what stops the two platforms inventing separate envelopes.
* :class:`TestSearchNeedles` — what a search box has to try, the one recall gap a ``contains`` can
  close, and the two it cannot.
* :class:`TestCellChokepoint` — the four export surfaces, asserted through the single function they
  all pass every value through.
"""

import json

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

#: A document a researcher could actually produce in one of these boxes: a sentence with a bolded
#: term in the middle of it, then a two-item bulleted list.
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
    """A value that is plain text must keep working forever, and must not be REWRITTEN.

    Not "must still be readable" — must come back the same bytes. These columns hold data this
    app's users are custodians of rather than authors of, and a flattener that quietly re-wrapped
    every note in the repository (``clean_text``, per-line strip, blank-line collapsing — all of
    which ``to_plain`` does) would show up as a diff in every export, attributed to nobody.
    """

    def test_none_stays_none(self):
        assert plain_from_stored(None) is None

    def test_empty_string_stays_empty(self):
        assert plain_from_stored("") == ""

    def test_plain_prose_is_returned_by_identity(self):
        value = "Weaves on a pit loom.\n\n  Trained by his father.  \n"
        assert plain_from_stored(value) is value

    def test_prose_that_merely_looks_structural_is_left_alone(self):
        # A researcher's note is allowed to contain braces, brackets and the word "blocks". None of
        # these is a document, and guessing that they are turns a real note into an empty cell.
        for value in (
            "{}",
            "{not json at all}",
            "[see photograph 4]",
            '{"remarks": "he charges 400 per metre"}',  # JSON, but not OUR document
            '{"blocks": "not a list"}',
            "Cut into blocks, then spans of 3 feet.",
        ):
            assert plain_from_stored(value) is value, value
            assert is_stored_rich_text(value) is False, value

    def test_a_truncated_document_is_read_as_prose_rather_than_blanked(self):
        # A clipped or hand-edited value is not parseable. Showing the researcher the broken string
        # is recoverable; showing them an empty field is how the content gets overwritten with
        # nothing on the next save.
        value = '{"blocks":[{"kind":"PARA'
        assert plain_from_stored(value) is value

    def test_non_strings_are_not_touched(self):
        # cell() coerces Decimals, dates and enums itself and has for as long as it has existed.
        # This function has no opinion about them and must not acquire one.
        marker = object()
        assert plain_from_stored(marker) is marker
        assert plain_from_stored(7) == 7


class TestFormattedFlattens:
    """The failure mode in one sentence: a ministry receives a CSV column full of JSON."""

    def test_stored_document_reads_back_as_prose(self):
        stored = to_stored_text(FORMATTED)
        assert plain_from_stored(stored) == FORMATTED_PLAIN
        assert "{" not in plain_from_stored(stored)

    def test_what_the_web_editor_actually_writes_flattens(self):
        # ``encodeStoredRichText`` emits ``JSON.stringify(toStored(doc))`` — no separators argument,
        # so with the spaces Python's default ``json.dumps`` also produces. Both spellings have to
        # read, or the flattening works only for values this file wrote.
        for bare in (json.dumps(to_json(FORMATTED)),
                     json.dumps(to_json(FORMATTED), separators=(",", ":"))):
            assert plain_from_stored(bare) == FORMATTED_PLAIN
            assert is_stored_rich_text(bare) is True

    def test_list_markers_survive_the_flattening(self):
        # Without the markers a bulleted list reads as one run-on sentence in a spreadsheet cell.
        assert "• Dyed with indigo" in plain_from_stored(to_stored_text(FORMATTED))

    def test_a_json_column_value_flattens_too(self):
        # The design workshop's shape: already-parsed dicts and lists arrive from a Json column.
        assert plain_from_stored(to_json(FORMATTED)) == FORMATTED_PLAIN


class TestTheTrap:
    """``from_json`` treats a ``str`` as prose. The string front door must not."""

    def test_from_json_would_have_returned_the_json_as_prose(self):
        bare = json.dumps(to_json(FORMATTED))
        # Pinned deliberately: this is the CORRECT behaviour of from_json (it is what makes
        # promoting a LONG_TEXT field to RICH_TEXT non-destructive) and simultaneously the reason
        # a String column needs its own reader. If this assertion ever fails, from_json changed and
        # the workshop promotion path needs re-checking, not this test.
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
    """One contract, two platforms. The web's ``storedRichText.ts`` is the other half of it."""

    def test_the_encoder_and_the_detector_agree(self):
        # The property that matters more than the shape itself: whatever this writes, the read
        # boundary recognises. A divergence here is a researcher's notes turning into braces.
        assert is_stored_rich_text(to_stored_text(FORMATTED)) is True
        assert plain_from_stored(to_stored_text(FORMATTED)) == FORMATTED_PLAIN

    def test_a_formatted_document_is_stored_as_the_shape_the_web_writes(self):
        stored = to_stored_text(FORMATTED)
        assert stored.startswith("{") and stored.endswith("}")
        assert json.loads(stored) == to_json(FORMATTED)

    def test_non_latin_prose_is_not_escaped_into_unreadability(self):
        # ``ensure_ascii=False``: a column full of \\uXXXX is unreadable in psql AND unsearchable by
        # the raw ILIKE clauses, which see the escapes rather than the letters.
        doc = RichDoc(blocks=(
            RichBlock(spans=(RichSpan("कच्छ", frozenset({Mark.BOLD})), RichSpan(" में बुना"))),
        ))
        assert "कच्छ" in to_stored_text(doc)

    def test_an_unformatted_document_is_stored_as_bare_prose(self):
        # Turning the editor on over an existing field must not churn the corpus into JSON.
        plain_doc = RichDoc(blocks=(RichBlock(spans=(RichSpan("Just a note."),)),))
        assert to_stored_text(plain_doc) == "Just a note."
        assert is_stored_rich_text(to_stored_text(plain_doc)) is False

    def test_alignment_alone_counts_as_formatting(self):
        # The "unformatted" test is a whitelist, so anything the plain rendering would lose — an
        # alignment, a heading, a list, a table, a mark — has to fall to the JSON branch.
        centred = RichDoc(blocks=(
            RichBlock(spans=(RichSpan("Centred."),), align=Align.CENTER),
        ))
        assert to_stored_text(centred).startswith("{")
        assert to_plain(from_stored_text(to_stored_text(centred))) == "Centred."

    def test_an_empty_document_stores_as_null_not_empty_string(self):
        # "The researcher left this blank" is NULL in every one of these columns today, and
        # ``field IS NULL`` reporting would go quietly wrong if this started writing "".
        assert to_stored_text(RichDoc()) is None
        assert to_stored_text(RichDoc(blocks=(RichBlock(spans=(RichSpan("  "),)),))) is None

    def test_round_trip_through_the_column_preserves_the_marks(self):
        restored = from_stored_text(to_stored_text(FORMATTED))
        assert to_json(restored) == to_json(FORMATTED)
        assert Mark.BOLD in restored.blocks[0].spans[1].marks

    def test_a_document_that_is_only_a_photograph_still_stores(self):
        # is_empty says an IMAGE with a media id is filled even with no caption, and to_plain says
        # it contributes no text. Those two together are how a picture-only value could round-trip
        # into an empty string and be lost.
        picture = RichDoc(blocks=(RichBlock(kind=BlockKind.IMAGE, media="med_1"),))
        stored = to_stored_text(picture)
        assert stored is not None and stored.startswith("{")
        assert from_stored_text(stored).blocks[0].media == "med_1"
        assert plain_from_stored(stored) == ""


class TestSearchNeedles:
    """Recall for the bare-JSON shape, and no added cost for the ordinary word."""

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
        # Both needles go through ``contains``, so the backslash JSON added is itself escaped for
        # LIKE. Skipping that would make the repair a new instance of the pattern-syntax leak
        # ``contains`` was written to close.
        clause = prose_contains("notes", 'a "b"')
        assert clause["OR"][1]["notes"]["contains"] == 'a \\\\"b\\\\"'

    def test_a_word_inside_a_span_is_still_found_by_the_plain_needle(self):
        # What the database's ILIKE will conclude. This is why the ordinary search is left alone.
        stored = to_stored_text(FORMATTED)
        assert search_needles("handspun")[0] in stored
        assert search_needles("Woven in Kutch")[0] in stored

    def test_the_two_recall_gaps_that_stay_open_are_pinned_here(self):
        # NOT an aspiration — a record of what is knowingly broken, so that anybody who later builds
        # a generated column or a text index can find the cases to check, and so nobody believes the
        # helper does more than it does.
        stored = to_stored_text(FORMATTED)
        # (1) the phrase crosses the bolded span
        assert "dressed with handspun cotton" not in stored
        # (2) the phrase crosses a block boundary
        assert "Dyed with indigo\n• Woven" not in stored
        # both are found once the value is flattened, which is what every EXPORT surface does
        assert "dressed with handspun cotton" in plain_from_stored(stored)


class TestCellChokepoint:
    """``record_fields.cell`` is the one function behind the info card, the workbook, the dataset
    zip's ``details.txt`` and both CSV downloads. Five surfaces, one assertion each way."""

    def test_cell_flattens_a_stored_document(self):
        assert cell(to_stored_text(FORMATTED)) == FORMATTED_PLAIN

    def test_cell_flattens_bare_json_too(self):
        assert cell(json.dumps(to_json(FORMATTED))) == FORMATTED_PLAIN

    def test_cell_is_unchanged_for_everything_it_handled_before(self):
        assert cell(None) == ""
        assert cell("  Woven in Kutch  ") == "Woven in Kutch"
        assert cell(1500) == "1500"
        assert cell("{not a document}") == "{not a document}"
