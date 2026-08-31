"""The walkthrough card's "What the screen asks for" block exists TWICE, and this is the join.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY THERE ARE TWO COPIES AT ALL
══════════════════════════════════════════════════════════════════════════════════════════════

The owner's report on 2026-08-31 was that the handset walkthrough *"has same number of cards, but
not exactly the same content, the name of the fields in bubble are missing from there"*. They were
right: the two decks already matched card for card, in one order, under one set of ids — and the
web's card carried a third panel section the handset's did not, the one listing *"the real form
labels, in screen order, with (required) marked"* (`GuideStep.fields` in
`frontend/components/guide/steps.ts`).

`WalkthroughJourney.kt` had REFUSED to grow that block, in writing, and the refusal was not lazy.
Its argument was that *"enumerating twenty-three Android forms from this file would be copy written
from copy, which is the failure both walkthrough files exist to prevent"* — and about the
implementation it was aimed at (transcribe the Android forms by hand into a Kotlin literal and hope)
that is simply correct.

What it did not settle is the version that actually shipped: the handset carries the WEB'S register,
verbatim, in `WALKTHROUGH_FIELDS`, and does not re-derive anything from its own forms. There is one
list of labels in this product and it lives in `steps.ts`. This file is what makes the second COPY of
it safe — the objection was to an *unguarded* duplicate, and the answer is the guard rather than the
omission.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY A PYTHON TEST OVER TWO FILES IT DOES NOT OWN
══════════════════════════════════════════════════════════════════════════════════════════════

The same shape, for the same reason, as the two parity tests already in this directory:

* `tests/test_role_ladder_parity.py` holds twenty-three hand-kept copies of the role ladder — across
  `lib/types.ts`, six frontend e2e tuples, seven Kotlin literals, Android tests and README.md — to
  the one `ROLE_RANK` in `deps.py`.
* `tests/test_terms_clause_parity.py` holds the nine terms clauses across `frontend/app/terms/
  page.tsx` and `android/.../ui/TermsScreen.kt`.

Neither client's own suite can see the other client, and the backend suite is the only gate that
runs with both trees on disk. `WalkthroughStepsTest.kt` already reaches sideways into `steps.ts` to
hold the step IDS and their ORDER — but it runs in the Android job, whose `pull_request` filter is
the `android` tree, so it is the wrong place for the half of the register a frontend-only change is
most likely to move.

It parses SOURCE TEXT rather than importing anything, so it cannot be defeated by either build. That
makes it brittle to a *refactor* of either declaration, which is the intended trade: a rewrite of how
either list is written down should make somebody read this file, and the failure names exactly what
to do.

══════════════════════════════════════════════════════════════════════════════════════════════
WHAT THIS DOES *NOT* CHECK, DELIBERATELY
══════════════════════════════════════════════════════════════════════════════════════════════

Whether a string is *true*. Four of the web's cards carry section descriptions rather than form
labels — `design-workshop-codes` and `design-workshop-readiness` render no labelled field at all,
`design-workshop-stages` is built from a registry the server publishes, and
`design-workshop-inspection` reads a workshop it did not author — and `steps.ts` names all four and
says why. This file does not care what a string MEANS. It cares that there is one register and that
both clients are reading it.
"""

from __future__ import annotations

import pathlib
import re

_ROOT = pathlib.Path(__file__).resolve().parents[2]
WEB_STEPS = _ROOT / "frontend" / "components" / "guide" / "steps.ts"
ANDROID_JOURNEY = (
    _ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "designprototype"
    / "workshop"
    / "ui"
    / "WalkthroughJourney.kt"
)

# The declaration each side is read from. Named once, quoted in every failure message, so a rename
# reports itself instead of quietly parsing to nothing.
WEB_ANCHOR = "export const GUIDE_STEPS"
ANDROID_ANCHOR = "private val WALKTHROUGH_FIELDS"


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Two small scanners, because a regex cannot do this safely
# ──────────────────────────────────────────────────────────────────────────────────────────────
#
# Both files put PROSE around the code being read — `steps.ts` runs to twelve hundred lines of which
# most are comments, and the Kotlin table has a fifty-line KDoc over it discussing the very strings
# below. A regex over the raw text would happily match a bracket inside a paragraph, and
# `DashboardTileParityTest` records what that costs: getting the nesting rule wrong "does not fail
# loudly: it silently hands the parser a slab of prose or a slab of code".


def _strip_ts_comments(source: str) -> str:
    """`//` and `/* … */` removed, string literals left alone.

    Character by character rather than by regex, because both comment forms appear INSIDE the
    strings this file compares (`"Or “Start an empty one” — Title, …"` has no comment in it, but the
    watch bullets around it quote paths like `app/(protected)/workshops/page.tsx`) and a regex that
    does not know where a string starts will cut one in half.
    """
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        char = source[i]
        if char in "\"'`":
            quote = char
            out.append(char)
            i += 1
            while i < n:
                if source[i] == "\\":
                    out.append(source[i : i + 2])
                    i += 2
                    continue
                out.append(source[i])
                if source[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "*":
            i += 2
            while i + 1 < n and not (source[i] == "*" and source[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(char)
        i += 1
    return "".join(out)


def _balanced(source: str, start: int, opener: str, closer: str) -> str:
    """The text from ``source[start]`` (an opener) to its matching closer, inclusive.

    Skips over string literals, so a bracket or a parenthesis inside a label — "Location (GPS fix or
    map pin)" is one — cannot close the block early. That entry is why this is a scanner and not
    ``.index("]")``.
    """
    assert source[start] == opener
    depth, i, n = 0, start, len(source)
    while i < n:
        char = source[i]
        if char == '"':
            i += 1
            while i < n:
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == '"':
                    break
                i += 1
        elif char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return source[start : i + 1]
        i += 1
    raise AssertionError(f"unbalanced {opener}{closer} from offset {start}")


def _literals(block: str) -> list[str]:
    r"""Every double-quoted run in ``block``, in order, unescaped.

    The escapes that actually occur are ``\"`` and nothing else — checked, not assumed, and the
    assertion in `test_neither_declaration_uses_an_escape_this_parser_invents` is what will say so if
    that ever stops being true. Anything else has its backslash dropped, which is right for ``\'``
    and wrong for ``\n``; a real newline inside one of these labels would be a chip with a line break
    in it, so the test above refuses it rather than this function guessing.
    """
    out: list[str] = []
    i, n = 0, len(block)
    while i < n:
        if block[i] != '"':
            i += 1
            continue
        i += 1
        buf: list[str] = []
        while i < n:
            if block[i] == "\\":
                buf.append({"n": "\n", "t": "\t", "r": "\r"}.get(block[i + 1], block[i + 1]))
                i += 2
                continue
            if block[i] == '"':
                break
            buf.append(block[i])
            i += 1
        out.append("".join(buf))
        i += 1
    return out


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The two registers
# ──────────────────────────────────────────────────────────────────────────────────────────────


def web_fields() -> list[tuple[str, list[str]]]:
    """``[(id, fields)]`` in the order `GUIDE_STEPS` declares them.

    A list of pairs and not a dict, because ORDER is half of what is being compared: this table read
    top to bottom is the journey read top to bottom, and `WalkthroughStepsTest` already holds the two
    clients to one sequence for the reason it gives — "the order is the actual lesson: a stage's
    reference pickers are empty if the records were never made".
    """
    source = _strip_ts_comments(WEB_STEPS.read_text(encoding="utf-8"))
    at = source.find(WEB_ANCHOR)
    assert at >= 0, f"{WEB_ANCHOR} is no longer declared in {WEB_STEPS.name}"
    body = source[at:]

    marks = [(m.group(1), m.start()) for m in re.finditer(r'\bid:\s*"([^"]+)"', body)]
    out: list[tuple[str, list[str]]] = []
    for index, (step_id, pos) in enumerate(marks):
        end = marks[index + 1][1] if index + 1 < len(marks) else len(body)
        chunk = body[pos:end]
        opener = re.search(r"\bfields:\s*\[", chunk)
        assert opener, (
            f"the web's “{step_id}” card no longer declares a fields[] array. Every card has one — "
            "see the GuideStep type — so this is a refactor of steps.ts and this parser has to "
            "learn about it."
        )
        out.append((step_id, _literals(_balanced(chunk, opener.end() - 1, "[", "]"))))
    return out


def android_fields() -> list[tuple[str, list[str]]]:
    """``[(id, fields)]`` in the order ``WALKTHROUGH_FIELDS`` declares them.

    The table is ``"<id>" to listOf("…", "…")``. The id is itself a quoted string, so the entries are
    read out of the ``listOf(…)`` block alone rather than out of the whole pair — otherwise every
    step would carry its own id as its first chip.
    """
    source = ANDROID_JOURNEY.read_text(encoding="utf-8")
    at = source.find(ANDROID_ANCHOR)
    assert at >= 0, (
        f"{ANDROID_ANCHOR} is no longer declared in {ANDROID_JOURNEY.name}. If the handset's copy "
        "of the web's field lists has moved, this file has to move with it — do not delete this "
        "test to make a rename pass."
    )
    opener = source.index("mapOf(", at) + len("mapOf(") - 1
    table = _balanced(source, opener, "(", ")")

    out: list[tuple[str, list[str]]] = []
    for entry in re.finditer(r'"([a-z0-9-]+)"\s*to\s*listOf\(', table):
        block = _balanced(table, entry.end() - 1, "(", ")")
        out.append((entry.group(1), _literals(block)))
    return out


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The tests
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_both_declarations_still_parse_to_something():
    """Green below means agreement, and not that both parsers came back empty.

    THE FAILURE A PARITY TEST IS MOST PRONE TO: `steps.ts` is reformatted or the Kotlin table is
    renamed, both parsers return nothing, ``[] == []`` passes, and the guard reports agreement about
    a comparison it never made. `WalkthroughStepsTest` opens with the same guard and says what it
    costs — the failure would be read as "the wiring is fine" when the parser is the broken thing.

    The floors are floors and not the current counts, deliberately. Pinning the exact number would
    put a THIRD copy of the register in this file and would fail on the day the web legitimately
    adds a step, which is the one event this suite exists to welcome.
    """
    web = web_fields()
    android = android_fields()
    assert len(web) >= 10, f"only {len(web)} steps parsed out of {WEB_STEPS.name}"
    assert len(android) >= 10, f"only {len(android)} steps parsed out of {ANDROID_JOURNEY.name}"
    assert all(fields for _, fields in web), (
        "a web card parsed to an EMPTY fields[] — the array scanner has stopped finding its "
        f"contents in {WEB_STEPS.name}: {[i for i, f in web if not f]}"
    )
    assert all(fields for _, fields in android), (
        "a handset entry parsed to an EMPTY listOf() — the table scanner has stopped finding its "
        f"contents in {ANDROID_JOURNEY.name}: {[i for i, f in android if not f]}"
    )


def test_neither_declaration_uses_an_escape_this_parser_invents():
    r"""Only ``\"`` appears in either file today, and a label may not contain a newline or a tab.

    `_literals` drops the backslash off anything it does not recognise, which is right for ``\'`` and
    would be a silent lie for ``—``. Rather than teach it every escape in two languages, this
    pins the narrow fact that makes the simple version correct — and a chip whose text carries a line
    break is a layout defect on both clients anyway.
    """
    for step_id, fields in web_fields() + android_fields():
        for field in fields:
            assert "\n" not in field and "\t" not in field, (
                f"“{step_id}” has a field with a line break or a tab in it: {field!r}. A chip is one "
                "run of text on both clients."
            )
            assert "\\" not in field, (
                f"“{step_id}” has a field carrying a backslash: {field!r}. This parser knows about "
                r'\" and nothing else — teach it, in both directions, before shipping this.'
            )


def test_the_handset_carries_a_field_list_for_every_step_the_web_teaches():
    """A step the web teaches and the handset does not name is the owner's report, back again.

    Equality and not containment, and the two directions fail for different reasons:

    * A web step MISSING here is the defect of 2026-08-31 — the handset's card silently loses the
      block, because a step with no key renders no heading at all.
    * An id here that the web does NOT teach is the failure `WalkthroughJourney.kt`'s overruled
      paragraph was written to prevent: a field list invented on this side, with nothing to hold it
      to anything. `offline` is the one subject this handset teaches alone, it has no screen of its
      own (its `destination` is null), and it correctly has no entry.
    """
    web = [step_id for step_id, _ in web_fields()]
    android = [step_id for step_id, _ in android_fields()]
    assert android == web, (
        f"the web's guide declares fields for {web}\n"
        f"and WALKTHROUGH_FIELDS declares them for {android}.\n"
        "Missing on the handset: a designer who read the guide on a laptop opens it in a courtyard "
        "and the card no longer says what the screen will ask them for. Extra on the handset: that "
        "is a second register with nothing holding it to the first — the web is the register, so "
        "teach the step there and copy it here, in one commit."
    )


def test_every_field_matches_the_web_word_for_word_and_in_screen_order():
    """The whole point of the file.

    NOT whitespace-normalised, unlike `test_terms_clause_parity.py`. That file has to normalise
    because the two copies of the terms are paragraphs wrapped to two different editors' columns.
    These are LABELS — one line each, one literal each, no wrapping on either side — so every
    character is compared, punctuation included. "Do's" against "Do’s" is one screen calling a box
    two names, and this is the only gate in either build that would notice.

    The ORDER is compared too, because `GuideStep.fields` is documented as screen order and a guide
    that lists a form's boxes in the wrong sequence is a guide somebody reads with the form open
    beside it.
    """
    web = dict(web_fields())
    android = dict(android_fields())
    drifted = {
        step_id: {"web": fields, "android": android[step_id]}
        for step_id, fields in web.items()
        if step_id in android and android[step_id] != fields
    }
    assert drifted == {}, "the two copies of the field lists have drifted:\n" + "\n".join(
        _report(step_id, pair["web"], pair["android"]) for step_id, pair in drifted.items()
    ) + (
        "\nThe web is the register: fix steps.ts and WALKTHROUGH_FIELDS together, in one commit. "
        "Never edit the Kotlin alone to turn this green — that makes the two walkthroughs describe "
        "two different products with one test saying they agree."
    )


def _report(step_id: str, web: list[str], android: list[str]) -> str:
    """The failing step, and the first line that actually differs.

    Printing both lists whole is what a `assertEqual` on two thirty-element lists already does badly:
    the `tool` card carries thirty labels and the reader has to diff them by eye. Naming the index
    and the two strings is the difference between a test that reports a drift and one that reports
    the existence of a drift.
    """
    lines = [f"  “{step_id}”: web has {len(web)} fields, handset has {len(android)}"]
    for index in range(max(len(web), len(android))):
        here = web[index] if index < len(web) else "<missing>"
        there = android[index] if index < len(android) else "<missing>"
        if here != there:
            lines.append(f"    first difference at #{index + 1}:")
            lines.append(f"      web:     {here!r}")
            lines.append(f"      android: {there!r}")
            break
    return "\n".join(lines)


def test_no_field_is_blank_on_either_client():
    """A blank entry is a chip with nothing in it, and it survives every other assertion here.

    It would survive them because an empty string on both sides compares equal — which is exactly how
    the "heading over an empty block" family of defects gets through. `WalkthroughFacetsTest` guards
    the same shape for the caution bullets, one client over.
    """
    for step_id, fields in web_fields() + android_fields():
        assert all(field.strip() for field in fields), (
            f"“{step_id}” declares a blank field, which draws an empty chip: {fields!r}"
        )
        assert len(fields) == len(set(fields)), (
            f"“{step_id}” lists the same field twice: "
            f"{sorted({f for f in fields if fields.count(f) > 1})}"
        )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# THE THIRD COPY: docs/WALKTHROUGH.md
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# `steps.ts` says of the printed guide that it "carries the same lists in prose", and until
# 2026-08-31 nothing checked that sentence. Two of the three copies were guarded — everything above
# holds the handset to the web — and the PROSE one, the version that gets printed and carried into a
# village with no signal, was held by nothing at all.
#
# It had drifted on SEVEN of its EIGHT record steps. Among what the audit that added this section
# found by reading the form components:
#
#   * `workshop` was missing "Kind of workshop", which is REQUIRED and is the FIRST control on the
#     form — and is the box that marks a workshop as a Design & Prototype one. A researcher working
#     from the printed list would create an ordinary workshop and then find it absent from the
#     picker that starts the entire fortnight arc, with nothing on either screen explaining why.
#   * `questionnaire` listed a "Date" field that the form does not have and deliberately does not
#     have: the server derives the interview date from when the interview was captured, and
#     `app/(protected)/questionnaire/page.tsx` says so in a comment where the box used to be. A
#     guide naming a field that was removed on purpose sends the reader looking for it.
#   * Six of the eight had never gained "Design & prototype workshop", the link field that is what
#     puts a record in front of a stage's reference pickers.
#
# ──────────────────────────────────────────────────────────────────────────────────────────────
# WHY THIS IS A ONE-DIRECTIONAL CHECK, AND WHY THAT IS THE POINT RATHER THAN A WEAKNESS
# ──────────────────────────────────────────────────────────────────────────────────────────────
#
# Every field in the register must be NAMED in the prose. The prose may name MORE. That asymmetry is
# deliberate, and the same audit is the reason for it: THE REGISTER IS ITSELF INCOMPLETE, and on the
# artisan step the prose is the more accurate of the two. `ArtisanForm.tsx` renders an Aadhaar
# number, an Artisan Pehchan Card pair, Date of birth, Practising since and Experience; `steps.ts`
# names none of those five, so neither does the handset — while `docs/WALKTHROUGH.md` has named the
# Aadhaar and Pehchan boxes all along, with four watch-out bullets about the deduplication key.
#
# A two-directional check would therefore report the printed guide as wrong FOR BEING RIGHT, and the
# quickest way to make it green would be to delete true sentences about the one field that stops the
# archive filling with duplicate artisans. So this holds the prose to the register as a FLOOR and
# never as a ceiling. The register's own gaps are recorded in the printed guide's maintenance
# section and belong to whoever owns `steps.ts`; they are not this file's to close, and this file
# must not pretend they are closed by making the check symmetric.
#
# ──────────────────────────────────────────────────────────────────────────────────────────────
# WHAT PROSE IS STILL ALLOWED TO DO
# ──────────────────────────────────────────────────────────────────────────────────────────────
#
# Summarise — in the four places named in `PROSE_PARAPHRASES` and nowhere else. "The form asks for
# the title, the craft and the dates" is not the same kind of claim as a list that must match a form
# field for field, and a document somebody reads on a bus is allowed to make the first kind. But
# every such licence is written down here, and `test_no_licensed_paraphrase_is_dead` asserts that
# the wording each one licences is still in the document and still needed. A paraphrase table nobody
# checked would be this lane's own subject reappearing one level down: a third register that looks
# authoritative and is held to nothing.

WALKTHROUGH_DOC = _ROOT / "docs" / "WALKTHROUGH.md"

PROSE_MARKER = "**What the screen asks for:**"

# The heading of each numbered record step, to the `GUIDE_STEPS` id it describes.
#
# Only the eight steps that carry a field line are here, and the omissions are deliberate rather than
# a backlog: steps 9 (Review) and 10 (View Data) are browse screens with no form, step 11 (Scan a
# code) resolves a code rather than asking for anything, and the workshop arc's Steps A–K describe
# screens built from the registry the server publishes — `design-workshop-stages` cannot have a
# hand-kept label list on either side, which is the whole reason that registry exists.
PROSE_STEPS = {
    "Workshop": "workshop",
    "Craft": "craft",
    "Artisan": "artisan",
    "Product": "product",
    "Process": "process",
    "Tool": "tool",
    "Questionnaire": "questionnaire",
    "Miscellaneous Media": "media",
}

# `(step id, register entry) -> the wording in the printed guide that stands for it`.
#
# FOUR ENTRIES, AND EACH IS A SENTENCE DOING A JOB A CHIP CANNOT. These are not spelling variants; a
# variant belongs in `_loose` below, which already absorbs markdown emphasis and punctuation. These
# are the places where the guide deliberately says something a label list cannot:
#
#   * The artisan step's location line names the TWO THINGS that control collects — the device fix
#     and the stated address — because confusing them corrupted the live dataset once, and the guide
#     carries a table about it. "Location (GPS fix or map pin)" is the chip; the sentence is the
#     lesson, and flattening it back to the chip would delete the correction.
#   * The four "Per step:" / "Per question:" chips are the web card's way of rendering a REPEATING
#     block as flat text. Prose has a grammar for that — "then per step: …" — and reads better for
#     it in the one rendering that has whole sentences available to it.
#
# ⚠ THE PROCESS ROWS WERE RE-KEYED ON 2026-08-31 AND THE RE-KEYING IS THE INTERESTING PART. They read
# `Per step: additional context notes (optional)` and `Per step: attached media` — and the audit that
# owns `steps.ts` found that NEITHER LABEL IS ON THE FORM. `ProcessForm` draws a "Record additional
# information" checkbox, and only once it is ticked does a notes control appear headed "Additional
# context for this step"; the media card under them is titled "Attach media". So this table was
# licensing prose against two chips that were themselves wrong, which is the failure one level down
# that the header above warns about: a paraphrase row makes a claim look CONSIDERED. A licence is
# only ever a licence to say a true thing differently.
PROSE_PARAPHRASES = {
    ("artisan", "Location (GPS fix or map pin)"): "Location (device fix **and** stated address)",
    ("process", "Per step: Record additional information"): (
        "**Record additional information**"
    ),
    ("process", "Per step: Additional context for this step"): (
        "**Additional context for this step**"
    ),
    ("process", "Per step: Attach media"): "**Attach media**",
    ("questionnaire", 'Per question: "Record this question" audio, or typed answer'): (
        'then per question either a **"Record this question"** audio clip or a typed answer'
    ),
}


def _collapse(text: str) -> str:
    """Every run of whitespace to one space.

    The document is hard-wrapped at about column 100, so a field the guide names is routinely split
    across two lines — "additional context notes / (optional)" is one. Comparing without this would
    report a drift for a line break the author never chose: the wrap column is an editor setting,
    not a claim about the product.
    """
    return " ".join(text.split())


def _loose(text: str) -> str:
    """Lowercased, with every run of non-alphanumerics collapsed to one space.

    The prose is MARKDOWN and the register is plain text: the guide writes "**Craft** *(required)*"
    for the register's "Craft (required)", and that emphasis is typography rather than a different
    claim about the form. Dropping punctuation also lets a straight apostrophe match a typographic
    one — which `test_every_field_matches_the_web_word_for_word_and_in_screen_order` deliberately
    refuses to tolerate BETWEEN THE TWO CLIENTS, where "Do's" against "Do’s" really is one screen
    calling one box two names. There is no such defect to catch between a form label and an English
    sentence about it, and enforcing it here would only teach people to paste chips into paragraphs.
    """
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def prose_fields() -> dict[str, str]:
    """``{guide step id: the "What the screen asks for" sentence, whitespace-collapsed}``.

    Attached to the numbered HEADING above each sentence rather than to its position in the file, so
    that inserting a section does not shift every list quietly onto the next step. That failure would
    not be loud in a useful way: the tool's thirty labels compared against the questionnaire's
    sentence would report a drift, the workshop's list against the craft's would report several more,
    and the reader would be sent to six wrong places at once.
    """
    raw = WALKTHROUGH_DOC.read_text(encoding="utf-8")
    out: dict[str, str] = {}
    heading: str | None = None
    for paragraph in re.split(r"(?:\r?\n){2,}", raw):
        found = re.match(r"^## \d+\. (.+?) [-—]", paragraph)
        if found:
            heading = found.group(1).strip()
        stripped = paragraph.lstrip()
        if not stripped.startswith(PROSE_MARKER):
            continue
        assert heading is not None, (
            f"{WALKTHROUGH_DOC.name} has a “{PROSE_MARKER}” line before any numbered step heading. "
            "This parser reads each list off the heading above it, so a list with no heading belongs "
            "to no step and would be silently ignored."
        )
        assert heading in PROSE_STEPS, (
            f"{WALKTHROUGH_DOC.name} step “{heading}” carries a field list and PROSE_STEPS does not "
            "know it. If a step was renamed, rename it here in the same commit; if a NEW form step "
            "was added to the printed guide, add it to steps.ts and to PROSE_STEPS together — an "
            "unmapped list is exactly the unguarded third register this section exists to prevent."
        )
        out[PROSE_STEPS[heading]] = _collapse(stripped[len(PROSE_MARKER) :])
    return out


def test_the_printed_guide_still_carries_a_field_line_for_every_record_step():
    """Green below means the sentences were found and compared, not that the parser returned {}.

    The same guard, for the same reason, as `test_both_declarations_still_parse_to_something` above:
    reword the marker or the heading format and every assertion under this one starts iterating an
    empty dict and passing. The document is prose and gets reformatted — that is precisely why it
    needed a guard, and it is also the thing most likely to break the guard's own reader.
    """
    found = prose_fields()
    assert set(found) == set(PROSE_STEPS.values()), (
        f"{WALKTHROUGH_DOC.name} carries “{PROSE_MARKER}” lists for {sorted(found)}, and this file "
        f"expects {sorted(PROSE_STEPS.values())}. A step that lost its list is a screen the printed "
        "guide no longer says anything about; a step that gained one needs a PROSE_STEPS row."
    )
    for step_id, sentence in found.items():
        assert len(sentence) > 20, (
            f"“{step_id}”'s field list in {WALKTHROUGH_DOC.name} parsed to {sentence!r}, which is "
            "too short to be a list of a form's boxes. The marker is probably being matched inside "
            "something that is not the list."
        )


def test_every_register_field_is_named_in_the_printed_guide():
    """The join this section exists for: the register is a FLOOR under the prose.

    A field that is on the form, in the web's card and in the handset's card, and that the printed
    guide does not name, is a box the researcher in the field has not been told to fill in. That is
    not a cosmetic gap — "Kind of workshop" went unnamed here for months, and it is the control that
    decides whether the whole design-workshop arc can see the record at all.

    Read the header above before making this symmetric. The prose naming MORE than the register is
    the register being wrong, and on the artisan step it currently is.
    """
    register = dict(web_fields())
    prose = prose_fields()
    missing: list[str] = []
    for step_id, sentence in prose.items():
        loose = _loose(sentence)
        collapsed = _collapse(sentence)
        for field in register[step_id]:
            licensed = PROSE_PARAPHRASES.get((step_id, field))
            if licensed is not None:
                if _collapse(licensed) in collapsed:
                    continue
                missing.append(
                    f"  “{step_id}” · {field!r}\n"
                    f"      PROSE_PARAPHRASES licenses the wording {licensed!r},\n"
                    "      and that wording is no longer in the document. Either restore the "
                    "sentence, or delete the row if the guide now names the field plainly."
                )
                continue
            core = re.sub(r"\s*\(required\)\s*$", "", field)
            if _loose(core) not in loose:
                missing.append(f"  “{step_id}” · {field!r}")
    assert not missing, (
        f"{WALKTHROUGH_DOC.name} no longer names every field the guide's register declares:\n"
        + "\n".join(missing)
        + "\n\nThe printed guide is the copy a researcher takes into a village with no signal. Add "
        "the field to that step's “What the screen asks for” line — READ THE FORM COMPONENT FIRST "
        "and put it in screen order, because that list gets read with the form open beside it. If "
        "the guide genuinely needs to say it as a sentence rather than as a label, add a row to "
        "PROSE_PARAPHRASES saying so, and why."
    )


def test_no_licensed_paraphrase_is_dead():
    """A licence for a field that no longer exists, or no longer needs one, is the drift again.

    Both halves fail for a reason worth naming:

    * A key naming a step or a field the register does not declare means this table is quoting a
      register that has moved. It would keep passing — an unused key is never consulted — while
      reading as a considered decision about a field nobody has been offered since.
    * A licence whose field the prose now names PLAINLY is a special case still switched on. The next
      person to widen this check reads the table as the list of places the document is allowed to be
      loose, and every stale row makes that list a worse description of the document than it is.
      Delete it: the plain naming is better than the licence.
    """
    register = dict(web_fields())
    prose = prose_fields()
    for (step_id, field), licensed in PROSE_PARAPHRASES.items():
        assert step_id in register, (
            f"PROSE_PARAPHRASES licenses a wording for step “{step_id}”, which GUIDE_STEPS no longer "
            "declares."
        )
        assert field in register[step_id], (
            f"PROSE_PARAPHRASES licenses a wording for “{step_id}” · {field!r}, which is no longer "
            "one of that card's fields. The register moved and this table did not follow."
        )
        assert step_id in prose, (
            f"PROSE_PARAPHRASES licenses a wording for “{step_id}”, which {WALKTHROUGH_DOC.name} no "
            "longer carries a field list for."
        )
        core = re.sub(r"\s*\(required\)\s*$", "", field)
        assert _loose(core) not in _loose(prose[step_id]), (
            f"PROSE_PARAPHRASES still licenses a paraphrase for “{step_id}” · {field!r}, but "
            f"{WALKTHROUGH_DOC.name} now names that field plainly. Delete the row: it is a special "
            "case that no longer applies, and leaving it makes this table a worse description of "
            "where the document is allowed to summarise."
        )
        assert _collapse(licensed) in _collapse(prose[step_id]), (
            f"PROSE_PARAPHRASES licenses {licensed!r} for “{step_id}” · {field!r}, and "
            f"{WALKTHROUGH_DOC.name} does not contain it."
        )
