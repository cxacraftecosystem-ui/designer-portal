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
