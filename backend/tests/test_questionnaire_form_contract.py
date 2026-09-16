"""The questionnaire capture form is declared once in `shared/questionnaire-form-contract.json`.

This file is what makes that declaration true of the product.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY IT EXISTS WHEN `test_walkthrough_fields_parity.py` ALREADY DOES
══════════════════════════════════════════════════════════════════════════════════════════════

That file is 870 lines and it is good: it parses TypeScript on one side and Kotlin on the other,
holds `GUIDE_STEPS` equal to `WALKTHROUGH_FIELDS` field for field, and holds both against the printed
guide. Its failure message — the first differing index, both strings, named — is the difference
between reporting a drift and reporting the existence of one, and it is ported wholesale below.

WHAT IS NOT PORTED IS ITS SHAPE, for one reason: every edge it draws is register-to-register or
register-to-document, and not one of them touches the SCREEN those documents describe. So on
2026-09-16 it was green over three registers that all three omit the "Type of workshop" box the
capture form has drawn since the design-workshop cascade landed — the very control the owner was
asking about that week. Two copies agreeing is not evidence about a third thing.

The missing edge is register-to-FORM, and the contract is what gives it a fixed point: both readings
are measured against a declaration rather than against each other, so a failure names the file and
the line that disagrees with a decision instead of naming two files that disagree.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY IT READS SOURCE TEXT
══════════════════════════════════════════════════════════════════════════════════════════════

Neither client can be executed here. There is no browser and no emulator in this suite, and the
backend job is the only one that runs with `frontend/`, `android/` and `docs/` all on disk.

Parsing source cannot be defeated by either build, and it is brittle to a REFACTOR of either
declaration, which is the intended trade: a rewrite of how a form declares its labels should make
somebody read this file. Every assertion therefore names the file and, where a regex is the
extractor, the LINE — because a parity test that says only "the lists differ" is a test people mute.

It also imports nothing but the standard library, which is worth more than it sounds: the rest of
this directory needs fastapi, prisma and openpyxl to so much as collect, so on a frontend
developer's machine or an Android developer's this file is one of the few here that still runs. The
two people most likely to break this contract are the two least likely to have a backend environment.

══════════════════════════════════════════════════════════════════════════════════════════════
WHAT IS HELD, AND WHAT IS DECLARED AND NOT YET HELD
══════════════════════════════════════════════════════════════════════════════════════════════

HELD HARD: BOTH capture forms — their fields, their order, their labels and the component or
composable drawing each one; the artisan control's structural pin, on both clients; the two
workshop-scope defects on the web; the registers' ORDER, their FLOOR and the `absent` ceiling; and
the contract's own internal consistency.

THE HANDSET JOINED THAT LIST ON 2026-09-16, and it was red four times over on the way in — the
workshop boxes opened the form where the browser opens with the title, the artisan field was a wall
of checkboxes labelled "Linked artisans", the notes box took its composable's default "Notes", and
the artisan roster was the whole deployment. The first three became ordinary assertions that day.

THE FOURTH CLOSED ON 2026-09-17 AND NOTHING IS DECLARED-AND-NOT-HELD ANY MORE.
`handset-artisan-register-is-unscoped` needed a `designWorkshopId` parameter this client's
`GET /artisans` did not have and a workshop-scoped read of its own, which is why it outlived the
other three. `test_the_handset_sends_the_workshop_scope_on_its_artisan_read` and
`test_the_handset_keeps_the_four_empty_roster_sentences_apart` hold it now, and `openDrifts.rows` is
empty. A contract that keeps its own exceptions after they are fixed teaches the next reader that
exceptions are permanent, so the rows are gone rather than marked done.

THE RATCHET IS KEPT THOUGH THERE IS NOTHING IN IT, and `test_every_open_drift_is_still_open` still
runs: it checks that each named drift is STILL THERE, so the moment somebody lands a fix the
assertion fails and tells them to delete the row and turn the real check on. Over an empty list it
asserts nothing and costs nothing, and the vocabulary its `stillTrue` keys define is what the next
row will be written in.

AND IT DID NOT FIRE FOR THE ROW IT WAS WRITTEN FOR — twice. The fact a row records must be the one
its own fix MOVES. This one first recorded a fact its own `edit` forbade touching, so no correct fix
could have made it red; a second fact was added beside it, and the correct fix left that one true as
well, because it was a guess at which LINE the fix would occupy and the fix was factored elsewhere.
`openDrifts.why` in the contract carries the whole account, and the lesson for the next row: record a
fact about the SURFACE the defect is on, never a fact about a diff.

══════════════════════════════════════════════════════════════════════════════════════════════
WHAT THIS DOES NOT CHECK, DELIBERATELY
══════════════════════════════════════════════════════════════════════════════════════════════

Whether a label is a GOOD name for a box, whether the two clients lay their fields out identically,
and anything about the location group, the media capture, the capture preferences or the instrument's
own questions — all of which are shared components with their own registers and their own tests. The
contract's `maintenance` section names them and says so.
"""

from __future__ import annotations

import json
import pathlib
import re

_ROOT = pathlib.Path(__file__).resolve().parents[2]

CONTRACT_PATH = _ROOT / "shared" / "questionnaire-form-contract.json"

WEB_FORM = _ROOT / "frontend" / "app" / "(protected)" / "questionnaire" / "page.tsx"
ANDROID_MAIN = (
    _ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "designprototype"
    / "workshop"
    / "MainActivity.kt"
)

# The declaration each side is read from, named once and quoted in every failure message, so a rename
# reports itself instead of quietly parsing to nothing.
WEB_FORM_ANCHOR = "<form onSubmit={submit}"
ANDROID_FORM_ANCHOR = "private fun QuestionnaireForm("


def contract() -> dict:
    """The one declaration. Read fresh rather than cached at import.

    Nothing here memoises, anywhere in this file. These are small files and a test suite that reads
    them once at import is a test suite that reports a stale answer when somebody edits a form
    between two runs of `pytest --looponfail` — which is exactly when this file is being read.
    """
    assert CONTRACT_PATH.exists(), (
        f"{CONTRACT_PATH} is missing. It is the contract every assertion in this file is about; "
        "without it there is nothing to measure either client against, and deleting it is not a way "
        "to make a drift pass."
    )
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def contract_labels() -> list[str]:
    """The field labels, in the contract's order."""
    return [field["label"] for field in contract()["fields"]]


def controls(client: str) -> dict[str, dict]:
    """``{component or composable: its registry row}`` for one client.

    THE PARSING RULES LIVE IN THE CONTRACT, NOT IN THIS FILE, and that is not tidiness. A registry
    here would be a second declaration of which components draw form fields, sitting in a test, held
    to nothing — which is the shape of the defect this whole file exists to close, one level down.
    """
    key = "component" if client == "web" else "composable"
    return {row[key]: row for row in contract()["controls"][client]}


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Two comment strippers, because a regex cannot do this safely
# ──────────────────────────────────────────────────────────────────────────────────────────────
#
# Both source files put PROSE around the code being read, and a great deal of it: the web form's
# field grid is two hundred lines of which well over half are comments, and the argument for the
# artisan picker's four empty-state sentences is written out at length on both clients, quoting the
# very labels compared below. A regex over raw text matches inside a paragraph and reports a label
# the screen has never drawn.
#
# NEWLINES ARE PRESERVED FOR EVERYTHING REMOVED, in both strippers, and that is an invariant rather
# than a detail: every failure message below carries a line number, and a stripper that swallowed a
# fifty-line comment would report line numbers off by fifty in a file of three thousand — which is
# worse than reporting none, because a reader trusts a number.


def _strip_ts_comments(source: str) -> str:
    """`//` and `/* … */` removed, string literals left alone.

    Character by character rather than by regex, because both comment forms appear INSIDE the strings
    this file reads: the questionnaire page's own comments quote paths like
    `app/(protected)/questionnaire/page.tsx` and `backend/app/api/routes/questionnaire.py`, and a
    regex that does not know where a string starts will cut one in half.

    Block comments do NOT nest in TypeScript — `/* /* */` ends at the first terminator — so unlike
    the Kotlin twin this arm stays a flat scan. That is a real difference between the two languages
    and not an oversight.
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
            # The newline itself is deliberately NOT consumed — the loop stops on it and the ordinary
            # path below emits it, so a line comment already preserves its own line.
            while i < n and source[i] != "\n":
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "*":
            start = i
            i += 2
            while i + 1 < n and not (source[i] == "*" and source[i + 1] == "/"):
                i += 1
            i += 2
            out.append("\n" * source.count("\n", start, i))
            continue
        out.append(char)
        i += 1
    return "".join(out)


def _strip_kotlin_comments(source: str) -> str:
    """The same, for Kotlin, and it is NOT the same function.

    Three differences, each of which would be a silent wrong answer if the TypeScript stripper were
    pointed at this file instead:

    * KDoc `/** … */` nests in Kotlin. A `/*` inside a doc comment does not end at the first `*/`,
      and this repository's KDoc routinely carries snippets.
    * Raw strings `\"\"\" … \"\"\"` have no escapes at all, so the backslash rule the TS stripper uses
      would walk straight past a terminator.
    * A single quote is a CHARACTER literal here, not a string delimiter. Treating `'` as a quote
      would swallow everything from the first apostrophe in a comment to the next one — and the
      comments in `MainActivity.kt` are English prose full of apostrophes.
    """
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        three = source[i : i + 3]
        if three == '"""':
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end])
            i = end
            continue
        char = source[i]
        if char == '"':
            out.append(char)
            i += 1
            while i < n:
                if source[i] == "\\":
                    out.append(source[i : i + 2])
                    i += 2
                    continue
                out.append(source[i])
                if source[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        if char == "/" and source[i + 1 : i + 2] == "*":
            depth, start = 0, i
            while i < n:
                if source[i : i + 2] == "/*":
                    depth += 1
                    i += 2
                    continue
                if source[i : i + 2] == "*/":
                    depth -= 1
                    i += 2
                    if depth == 0:
                        break
                    continue
                i += 1
            out.append("\n" * source.count("\n", start, i))
            continue
        out.append(char)
        i += 1
    return "".join(out)


def _balanced(source: str, start: int, opener: str, closer: str) -> str:
    """The text from ``source[start]`` (an opener) to its matching closer, inclusive.

    Skips string literals, so a bracket inside a label cannot close the block early — "Location (GPS
    fix or map pin)" in the walkthrough registers is exactly that entry, and it is one of the strings
    this file reads.
    """
    assert source[start] == opener, (
        f"internal: _balanced was handed {source[start]!r} where {opener!r} was expected"
    )
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


def _line_of(source: str, offset: int) -> int:
    """1-indexed line number of ``offset``, for a failure message somebody has to act on.

    `page.tsx` is three thousand lines and the capture form is five hundred of them; "the web draws
    the wrong control" sends a reader to a file, and "page.tsx:1580 draws <Select>" sends them to the
    edit.
    """
    return source.count("\n", 0, offset) + 1


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The web form
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _web_form_region() -> tuple[str, int]:
    """The capture `<form>`'s own source, comment-stripped, and its offset in the stripped file.

    SLICED TO THE FORM AND NOT READ WHOLE, because `page.tsx` is three thousand lines and most of
    what is not this form is the questionnaire BUILDER further down it — which draws
    `<Field label="Section code">`, `<Field label="Code">`, `label="Section title"` and more. Read
    whole, this parser would report those as fields of the capture form: not a crash, not an empty
    list, just a confident wrong answer about a screen nobody looked at.
    """
    source = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))
    at = source.find(WEB_FORM_ANCHOR)
    assert at >= 0, (
        f"{WEB_FORM_ANCHOR!r} is no longer in {WEB_FORM.name}. That string is how this file finds "
        "the capture form at all; if the form has been rewritten or moved, this parser moves with "
        "it — do not delete this test to make a refactor pass."
    )
    end = source.find("</form>", at)
    assert end > at, (
        f"{WEB_FORM.name} has no </form> after {WEB_FORM_ANCHOR!r}. Without a closing tag this "
        "parser would read the rest of the page, including the questionnaire builder's own fields."
    )
    return source[at:end], at


def _component_default_label(default: dict) -> str:
    """A label a web component supplies for itself, read out of that component's own signature.

    WHY THIS EXISTS: `<WorkshopSelect state={workshop} saving={saving} />` carries no `label` prop —
    the string lives in `WorkshopSelect`'s own signature as `label = "Workshop"`. A scan of the form
    for quoted labels alone therefore reports the workshop field as MISSING FROM THE WEB, which is
    the parser lying about the reference implementation. That failure reads as "the contract is
    wrong" and gets the field deleted from the contract to make it green.

    `param` rather than a hard-coded `label`, because a component may keep two boxes' strings in two
    differently-named places. `nth` is the other half of that, and it is what the two-dropdown
    ruling needed: `WorkshopPicker` draws BOTH of its boxes' labels through the same spelling —
    `<Field label="Type of workshop">` and then `label="Workshop"` on whichever of the two workshop
    boxes the chosen type routes to — so the parameter alone cannot tell them apart and the
    occurrence index is what does. It is optional and defaults to 0, which is every other registered
    default's answer.

    THE OCCURRENCE IS READ IN SOURCE ORDER, WHICH IS DRAW ORDER HERE, and that is worth saying
    because it is an assumption rather than a law. It holds for `WorkshopPicker` because its two
    boxes are written in the order they are painted, one after the other inside one `return`. A
    component that declared its labels away from the elements using them would need a differently
    NAMED parameter for each, which is how this was written when `DesignWorkshopCascade` (retired)
    kept its first box's string in `kindLabel` and forwarded `label` to the picker underneath it.
    """
    path = _ROOT / default["source"]
    symbol, param = default["symbol"], default["param"]
    nth = int(default.get("nth", 0))
    source = _strip_ts_comments(path.read_text(encoding="utf-8"))
    at = source.find(f"export function {symbol}")
    assert at >= 0, (
        f"the contract says {symbol}'s {param} supplies a field label, and {path.name} no longer "
        f"declares `export function {symbol}`."
    )
    found = re.findall(rf'\b{param}\s*=\s*"([^"]*)"', source[at:])
    assert len(found) > nth, (
        f"{symbol} in {path.name} declares {len(found)} `{param} = \"…\"` string literal(s) after "
        f"its declaration and the contract asks for number {nth + 1}. The contract reads a field's "
        "label from there; if the component now computes its label, or draws fewer boxes than the "
        "contract says, the contract has to say so instead."
    )
    return found[nth]


def web_form_labels() -> list[tuple[str, int]]:
    """``[(label, line)]`` for every labelled control the capture form draws, in screen order.

    A list of pairs and not a set, because ORDER IS HALF OF THE CONTRACT — see the contract's
    `howOrderIsPartOfIt`. This list read top to bottom is the form read top to bottom.

    TWO KINDS OF LABEL, INTERLEAVED BY OFFSET. Most are written at the call site, either directly
    (`<DictatedTextInput label="Interview title" …>`) or on the `<Field>` / `<FieldBlock>` wrapper
    that holds the control; three come from a component's own default. Matching both patterns in one
    ordered pass is what keeps the three workshop boxes in their true POSITIONS rather than appended
    at the end, and the position is the thing being asserted.

    Labels written as EXPRESSIONS are invisible here and that is correct rather than a gap:
    `<UploadProgress label={INTERVIEW_SECTION_LABEL}>` and the per-question
    ``<UploadProgress label={`Q${code}${order} audio`}>`` are not form fields, they name progress
    cards. The day one of them becomes a field it will have to be written as a literal or declared in
    the contract's `alsoDrawn`, and either way somebody will have read this paragraph.
    """
    region, base = _web_form_region()
    stripped = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))

    # (offset, sub-index, label). The sub-index keeps a two-box component's own order stable without
    # inventing a fake offset for its second label.
    marks: list[tuple[int, int, str]] = []
    for match in re.finditer(r'\blabel="([^"]*)"', region):
        marks.append((match.start(), 0, match.group(1)))
    for component, row in controls("web").items():
        if row["labelFrom"] != "componentDefault":
            continue
        for match in re.finditer(rf"<{component}\b", region):
            tag = region[match.start() : region.find(">", match.start()) + 1]
            # An explicit prop WINS over the default and is reported at the element's own position.
            # Without this arm a call site that overrode the label would be read as using the default
            # AND as carrying a literal, and the field would appear twice.
            override = re.search(r'\blabel="([^"]*)"', tag)
            if override:
                marks = [
                    mark for mark in marks if mark[0] != match.start() + override.start()
                ]
                marks.append((match.start(), 0, override.group(1)))
                continue
            for index, default in enumerate(row["defaults"]):
                marks.append((match.start(), index, _component_default_label(default)))

    marks.sort()
    # `stripped` and `region` share an origin, so an offset inside the region maps to the stripped
    # file by adding `base` — and the stripped file preserves newlines, so the line number is the
    # line number in the file on disk.
    return [(label, _line_of(stripped, base + offset)) for offset, _, label in marks]


def web_control_for(field: dict) -> tuple[str, int] | None:
    """The component that draws *field* on the web form, and the line it is drawn on.

    STRUCTURAL, WHICH IS THE ONLY KIND OF CHECK THAT CATCHES A WIDGET SWAP. A label assertion goes
    green over a single-select restored under a multi-select's name; this reads what is rendered.

    THREE SHAPES, because the form uses three, and the third is why this takes the whole contract
    entry rather than just a label:

    * A control that takes its own `label` prop IS the control (`<DictatedTextInput label="Place">`).
    * A `<Field label="…">` or `<FieldBlock label="…">` wrapper is followed by the control it wraps,
      so the answer is the first capitalised element after the label.
    * A `componentDefault` field has NO label at its call site at all — `<WorkshopSelect state={…} />`
      keeps its string in its own signature — so there is no label to search back from and the
      element itself is what has to be found. Searching for the label first, which is what the other
      two do, returns nothing here and reports the reference client as missing a field it draws.
    """
    region, base = _web_form_region()
    stripped = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))

    # The componentDefault arm searches by COMPONENT and then checks the label, which is the reverse
    # of the other two — and it searches every registered self-labelling component rather than only
    # the one this field declares, so that a field drawn by the WRONG one is reported as the swap it
    # is instead of as a field nobody could find.
    for component, row in controls("web").items():
        if row["labelFrom"] != "componentDefault":
            continue
        at = region.find(f"<{component}")
        if at < 0:
            continue
        tag = region[at : region.find(">", at) + 1]
        override = re.search(r'\blabel="([^"]*)"', tag)
        drawn = (
            [override.group(1)]
            if override
            else [_component_default_label(default) for default in row["defaults"]]
        )
        if field["label"] in drawn:
            return component, _line_of(stripped, base + at)

    at = region.find(f'label="{field["label"]}"')
    if at < 0:
        return None
    opener = region.rfind("<", 0, at)
    element = re.match(r"<([A-Za-z][A-Za-z0-9_]*)", region[opener:])
    if not element:
        return None
    name = element.group(1)
    if name in ("Field", "FieldBlock"):
        inner = re.search(r"<([A-Z][A-Za-z0-9_]*)", region[region.find(">", at) :])
        if not inner:
            return None
        return inner.group(1), _line_of(stripped, base + region.find(">", at))
    return name, _line_of(stripped, base + opener)


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The Android form
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _android_form_region() -> tuple[str, int]:
    """`QuestionnaireForm`'s body, comment-stripped, and its offset in the stripped file.

    BRACE-BALANCED FROM THE FUNCTION'S OWN `{` AND NOT "UP TO THE NEXT @Composable". The obvious
    slice is wrong here and wrong quietly: the next `@Composable` at column zero after
    `QuestionnaireForm` is past the end of it, so that slice swallows the next composable whole and
    reports its controls as fields of this form.
    """
    source = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    at = source.find(ANDROID_FORM_ANCHOR)
    assert at >= 0, (
        f"{ANDROID_FORM_ANCHOR!r} is no longer in {ANDROID_MAIN.name}. That is how this file finds "
        "the handset's capture form; if it has been renamed or moved out of MainActivity.kt, this "
        "parser moves with it."
    )
    body_at = source.index("{", source.index(")", at))
    return _balanced(source, body_at, "{", "}"), body_at


def _kotlin_declaration(path: pathlib.Path, symbol: str) -> str:
    """One composable's own parameter list AND brace-balanced body, comment-stripped.

    BOTH HALVES, BECAUSE KOTLIN PUTS THE STRING IN EITHER. `MultiNoteInput` declares its label as a
    DEFAULT PARAMETER — `label: String = "Notes"` — which is in the signature and not in the body;
    `StatusControl` and `WorkshopTypeField` write theirs inside the body. A resolver that read only
    the body reported the notes box as unlabelled, which is not a small mis-read: it is the exact
    shape of the drift this file had just closed, reported as a parser failure on a form that was
    correct. That is how a contract test teaches people to delete it.

    BOUNDED, WHICH IS THE WHOLE DIFFERENCE FROM THE WEB'S TWIN. `_component_default_label` searches
    from a component's declaration to the END OF THE FILE, which is survivable in a two-hundred-line
    `WorkshopPicker.tsx` and is not survivable here: `MainActivity.kt` is twenty thousand lines, and
    an unbounded search for `label = "Workshop"` after `fun WorkshopField(` would happily match a
    string belonging to a composable four screens further down and report it as this one's.

    THE PARAMETER LIST IS PAREN-BALANCED AND NOT "UP TO THE FIRST `)`". Kotlin signatures carry
    parentheses inside them — `onSelect: (String) -> Unit` is in three of the four composables this
    resolves — so the first `)` after the name is routinely the wrong one. It happens to land on the
    right `{` for every signature in this form today, which is exactly the kind of accident that
    holds until somebody reorders a parameter list.
    """
    source = _strip_kotlin_comments(path.read_text(encoding="utf-8"))
    at = re.search(rf"\bfun\s+{re.escape(symbol)}\s*\(", source)
    assert at is not None, (
        f"{CONTRACT_PATH.name} says {symbol} supplies a field label, and {path.name} no longer "
        f"declares `fun {symbol}(`. If it was renamed, rename it in the contract's "
        "`controls.android` defaults too — that is the register the handset's scanner reads."
    )
    open_at = source.index("(", at.start())
    args = _balanced(source, open_at, "(", ")")
    body_at = source.index("{", open_at + len(args))
    return args + _balanced(source, body_at, "{", "}")


def _composable_default_label(default: dict) -> str:
    """A label a composable supplies for itself, CHECKED against that composable's own body.

    WHY IT IS SHAPED DIFFERENTLY FROM `_component_default_label`, which is the same job on the web
    and reads a `param` out of a signature. Kotlin writes "this control's own label" three ways and
    this one form meets all three:

    * a default parameter — `label: String = "Notes"` (`MultiNoteInput`);
    * a named argument handed down to the control underneath — `label = "Type of workshop"`
      (`WorkshopTypeField`, which passes it to a `SearchableSelectField`);
    * a bare positional `Text("Status", …)` in one branch of the body (`StatusControl`).

    One `param`-anchored regex reads the first, misses the second and cannot express the third. A
    regex that silently matched none of them would resolve to nothing and report a field the handset
    DOES draw as missing — a failure that reads as "the contract is wrong" and gets the field deleted
    from the contract to make it green, which is the single most expensive way this file can fail.

    SO THE CONTRACT NAMES THE LITERAL AND THIS ASSERTS IT IS THERE. That is a stronger claim than the
    web's rather than a weaker one: the web reads back whatever string it finds and lets the field
    comparison do the work, so a renamed component label surfaces as a mismatched field several
    assertions later; this names the composable and the file to open. The contract already declares
    every label once in `fields`, so nothing new is being restated — what is added is WHICH
    composable is on the hook for each one.
    """
    path = _ROOT / default["source"]
    literal = default["literal"]
    assert path.exists(), (
        f"{CONTRACT_PATH.name} reads a label out of {default['source']}, which does not exist."
    )
    declaration = _kotlin_declaration(path, default["symbol"])
    assert f'"{literal}"' in declaration, (
        f"{CONTRACT_PATH.name} says `{default['symbol']}` in {path.name} draws the label "
        f"“{literal}”, and that string literal is nowhere in its signature or its body.\n"
        "  Either the control was renamed on screen — in which case the contract, the other client "
        "and all three registers move with it — or the label moved out of this composable and the "
        "`defaults` entry has to name wherever it went. Do not delete the entry: a self-labelling "
        "control with no `defaults` reports every field it draws as missing from the handset."
    )
    return literal


def android_form_controls() -> list[tuple[str, str, int]]:
    """``[(composable, label, line)]`` for every registered control the handset's form draws.

    ONE SCAN FEEDS BOTH ANDROID ASSERTIONS — the field ORDER and the COMPOSABLE each field is drawn
    with — where the web has two functions that walk the form separately. That is deliberate: the web
    pair has to be kept in step by hand, and the one time they disagree is the one time the two
    assertions describe two different readings of one file. Here they cannot.

    IT IS DRIVEN BY THE CONTROL REGISTRY AND NOT BY A STRING SCAN, and that is the difference from
    the web arm rather than a shortcut. `page.tsx` writes every form label as `label="…"`, so
    scanning for that spelling finds the fields and almost nothing else. Kotlin has no such tell:
    this form's body holds a hundred-odd string literals — section headings, hints, a switch caption,
    error sentences, the dictation refusals — and two of its seven controls carry their label as a
    bare first positional argument indistinguishable from any of them. A string scan here would
    report dozens of fields this form does not have, and the person who met that failure would delete
    the assertion rather than read it.

    WHAT THAT COSTS, SAID PLAINLY: a control drawn here whose composable is in NEITHER `controls`
    NOR `alsoDrawn` is invisible to this scan, so the `alsoDrawn` equality catches a new box only
    once the composable drawing it is registered. The web arm has the same hole one shape along — it
    sees `label="…"` and nothing else, so a control labelled by an expression is equally invisible,
    and its own docstring says so. Registering a composable is a one-line edit made by the person
    adding the control, and the contract's internal-consistency check refuses a field that names an
    unregistered one; what is not caught is a NEW control that is nobody's field and nobody's
    `alsoDrawn` row. That is the honest boundary of a parser over source text, and the reason the
    contract's `maintenance` section asks for the declaration to move in the same commit as the form.

    A CALL-SITE `label = "…"` BEATS A COMPOSABLE DEFAULT, exactly as on the web. The notes box is the
    live case: `MultiNoteInput` defaults to "Notes" for four other record forms and this one passes
    "Interview notes", so reading the default here would report a drift that was closed and would
    report it at the wrong position.
    """
    region, base = _android_form_region()
    stripped = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    registry = controls("android")

    found: list[tuple[int, int, str, str]] = []
    for composable, row in registry.items():
        for match in re.finditer(rf"\b{composable}\s*\(", region):
            args = _balanced(region, match.end() - 1, "(", ")")
            line = _line_of(stripped, base + match.start())
            override = re.search(r'\blabel\s*=\s*"([^"]*)"', args)
            if override:
                found.append((match.start(), 0, composable, override.group(1)))
                continue
            if row["labelFrom"] == "positional":
                positional = re.match(r'\(\s*"([^"]*)"', args)
                assert positional, (
                    f"{ANDROID_MAIN.name}:{line} calls `{composable}(` and the contract says it "
                    "takes its label as a first positional string. This call's first argument is "
                    f"not a string literal: {args[:80]!r}."
                )
                found.append((match.start(), 0, composable, positional.group(1)))
                continue
            if row["labelFrom"] == "named":
                raise AssertionError(
                    f"{ANDROID_MAIN.name}:{line} calls `{composable}(` with no `label = \"…\"`, and "
                    f"{CONTRACT_PATH.name} registers it as labelling itself at the call site. A "
                    "control whose label cannot be read is a field this parser would silently drop."
                )
            for index, default in enumerate(row["defaults"]):
                found.append((match.start(), index, composable, _composable_default_label(default)))

    found.sort()
    return [
        (composable, label, _line_of(stripped, base + offset))
        for offset, _, composable, label in found
    ]


def android_form_labels() -> list[tuple[str, int]]:
    """``[(label, line)]`` in screen order — the handset's form read top to bottom."""
    return [(label, line) for _, label, line in android_form_controls()]


def android_control_for(field: dict) -> tuple[str, int] | None:
    """The composable that draws *field* on the handset, and the line it is drawn on.

    STRUCTURAL, WHICH IS THE ONLY KIND OF CHECK THAT CATCHES A WIDGET SWAP — and on this client that
    is not a hypothetical. The artisan field was a wall of checkboxes under its own true-looking
    label until 2026-09-16; every label assertion in this file was green over it.

    IT SEARCHES BY LABEL AND REPORTS WHATEVER DREW IT, never the reverse. Asking "is there a
    `SearchableMultiSelectField` drawing this?" answers "no" identically for a field that is missing
    and for a field drawn by the wrong control, and those need different fixes.
    """
    for composable, label, line in android_form_controls():
        if label == field["label"]:
            return composable, line
    return None


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The failure message
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _report(
    client: str, path: pathlib.Path, expected: list[str], actual: list[tuple[str, int]]
) -> str:
    """The first line that actually differs, named, with a file and a line number.

    Printing both lists whole is what comparing two nine-element lists already does badly: the reader
    diffs them by eye and stops at the first thing that looks similar. Naming the index, both strings
    and the source line is the difference between a test that reports a drift and one that reports
    the existence of a drift.
    """
    got = [label for label, _ in actual]
    lines = [
        f"  {client} ({path.name}) draws {len(got)} of the contract's {len(expected)} fields.",
        f"    contract: {expected}",
        f"    {client:<8}: {got}",
    ]
    for index in range(max(len(expected), len(got))):
        here = expected[index] if index < len(expected) else "<missing>"
        there = got[index] if index < len(got) else "<missing>"
        if here != there:
            where = f" at {path.name}:{actual[index][1]}" if index < len(actual) else ""
            lines.append(f"    first difference at #{index + 1}{where}:")
            lines.append(f"      contract: {here!r}")
            lines.append(f"      {client:<8}: {there!r}")
            break
    return "\n".join(lines)


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 0. The guard that stops every assertion below from being vacuously green
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_contract_and_both_forms_still_parse_to_something():
    """Green below means the comparison was MADE, not that three parsers came back empty.

    THE FAILURE A CONTRACT TEST IS MOST PRONE TO, and the one it would report as success: a form is
    reformatted or an anchor renamed, the scanner returns `[]`, `[] == []` passes, and the suite
    reports agreement about a comparison it never performed.

    THE FLOORS ARE FLOORS AND NOT THE CURRENT COUNTS, deliberately. Pinning the exact number would
    put a second copy of the register in this file, and it would fail on the day the form
    legitimately grows a field — the one event this suite exists to welcome, because that is the day
    somebody opens the contract.
    """
    spec = contract()
    assert len(spec["fields"]) >= 6, (
        f"{CONTRACT_PATH.name} declares only {len(spec['fields'])} fields. This form has never had "
        "fewer than six; a contract this short is a contract that was emptied rather than edited."
    )

    web = web_form_labels()
    assert len(web) >= 6, (
        f"only {len(web)} labelled controls parsed out of {WEB_FORM.name}'s capture form. The "
        f"anchor is {WEB_FORM_ANCHOR!r} and the labels are read as `label=\"…\"` plus the components "
        "that default their own — if the form now writes labels some third way, this parser has to "
        "learn about it before anything below means anything."
    )
    assert all(label.strip() for label, _ in web), (
        f"a control in {WEB_FORM.name}'s capture form parsed to a BLANK label, which draws a field "
        f"with no name: {[label for label, _ in web if not label.strip()]!r}"
    )
    region, _ = _android_form_region()
    assert len(region) > 2000, (
        f"{ANDROID_MAIN.name}'s {ANDROID_FORM_ANCHOR!r} body parsed to {len(region)} characters, "
        "which is too short to be the handset's capture form. Every assertion about the handset is "
        "vacuous until this is real."
    )
    # THE SAME FLOOR THE WEB HAS, AND IT WAS NOT HERE WHILE THE HANDSET WAS ONLY "LOCATED". A
    # character count says the region is a form; it says nothing about whether the SCANNER found
    # anything in it, and the Android scanner is registry-driven — so emptying `controls.android`,
    # or renaming the composables out from under it, returns `[]` and every equality below passes by
    # comparing nothing to nothing. This is the one failure mode that reports itself as agreement.
    android = android_form_labels()
    assert len(android) >= 6, (
        f"only {len(android)} labelled controls parsed out of {ANDROID_MAIN.name}'s capture form. "
        f"The anchor is {ANDROID_FORM_ANCHOR!r} and the labels are read through "
        "`controls.android` — a positional first argument, a `label = \"…\"`, or the literal a "
        "composable holds for itself. A control drawn some fourth way has to be registered there "
        "before anything below means anything."
    )
    assert all(label.strip() for label, _ in android), (
        f"a control in {ANDROID_MAIN.name}'s capture form parsed to a BLANK label, which draws a "
        f"field with no name: {[label for label, _ in android if not label.strip()]!r}"
    )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 1. The WEB form renders exactly the contract fields, in order, with those labels
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_web_form_draws_exactly_the_contract_fields_in_order():
    """The reference implementation, held to the declaration derived from it.

    IT IS NOT CIRCULAR AND IT IS NOT DECORATION. The contract was read off this form, so this
    assertion is green the day it is written — and that is the point: from that day on, a change to
    the web form has to be a change to the contract, in the same commit, or the build says so. Every
    other assertion in this file measures something against the contract, and all of them are worth
    nothing if the contract has quietly stopped describing the screen the owner ruled correct.

    EQUALITY AND NOT CONTAINMENT, and the two directions fail for genuinely different reasons:

    * A contract field MISSING here means the declaration has outlived the form — somebody removed a
      box and this file is the only thing that noticed.
    * A label here the contract does not declare is a NEW field on the reference client, which is the
      original defect running forwards instead of backwards: the handset is now behind by one and
      nothing else in either build can see it. Add it to the contract, then to the handset, then to
      the three registers.
    """
    expected = contract_labels()
    extras = {extra["label"] for extra in contract()["alsoDrawn"]["web"]}
    drawn = web_form_labels()
    actual = [(label, line) for label, line in drawn if label not in extras]
    assert [label for label, _ in actual] == expected, (
        "the web capture form no longer draws the contract's fields:\n"
        + _report("web", WEB_FORM, expected, actual)
        + "\n\n  Declared but not drawn here: "
        + repr([label for label in expected if label not in {a for a, _ in actual}])
        + "\n  Drawn here and not declared: "
        + repr([label for label, _ in actual if label not in expected])
        + f"\n\n  The web is the reference — the owner ruled it correct — so a difference here is "
        f"either a field added to the form and not to {CONTRACT_PATH.name}, or one removed from the "
        "form and left in the contract. Fix the contract in the same commit as the form, and then "
        "carry it to the handset and to all three registers. Never edit the contract alone to turn "
        "this green: the contract is what every other surface is measured against."
    )


def test_the_web_form_draws_each_field_with_the_control_the_contract_names():
    """The labels being right is not the same claim as the controls being right.

    The whole reason `controlPins` exists is that a label assertion passes over a widget swap. This
    holds the reference client to its own declared controls so that the pin below is measured against
    something that is itself checked.
    """
    for field in contract()["fields"]:
        found = web_control_for(field)
        assert found is not None, (
            f"the contract's “{field['label']}” could not be located in {WEB_FORM.name}'s capture "
            "form at all, so its control cannot be read. The assertion above says which fields are "
            "present; fix that one first."
        )
        component, line = found
        assert component == field["web"]["component"], (
            f"{WEB_FORM.name}:{line} draws the contract's “{field['label']}” with <{component}>, "
            f"and {CONTRACT_PATH.name} declares <{field['web']['component']}> "
            f"(control kind: {field['control']}).\n"
            "  A control swap is not a styling change: it changes what the researcher can express. "
            "If the web genuinely moved to a different control, the contract and both clients move "
            "with it in one commit."
        )


def test_the_android_form_draws_exactly_the_contract_fields_in_order():
    """THE EDGE THIS FILE WAS WRITTEN FOR, and it was declared and held off until 2026-09-16.

    It is the same assertion as the web's twin above and it is NOT the same claim. That one holds the
    reference client to a declaration derived FROM it, so it is green on the day it is written and
    earns its place from then on. This one holds a second client to a declaration derived from
    somewhere else — so it was red the day it was written, four times over, and every one of those
    four was a real difference a researcher would have met:

      * the handset opened with the two workshop boxes and the browser opens with the title;
      * its artisan field was `ArtisanMultiSelectField`, a wall of checkboxes, labelled
        “Linked artisans” against the browser's “Artisans interviewed”;
      * its notes box took `MultiNoteInput`'s own default, “Notes”, against the browser's
        “Interview notes”;
      * and its artisan roster was the whole deployment rather than the workshop's — the one of the
        four that is not a field-order question, and the one still recorded in `openDrifts`.

    EQUALITY AND NOT CONTAINMENT, for the reason the web's twin gives: a contract field missing here
    is a box the handset does not draw, and a label drawn here that the contract does not declare is
    a box the browser does not — and the second is the direction nothing else in either build can
    see. `alsoDrawn.android` is where a control that is legitimately not a field goes, with its
    reason, so that the answer to "this control is fine, it is just not a field" has to be written
    down once rather than argued each time.
    """
    expected = contract_labels()
    extras = {extra["label"] for extra in contract()["alsoDrawn"]["android"]}
    actual = [(label, line) for label, line in android_form_labels() if label not in extras]
    assert [label for label, _ in actual] == expected, (
        "the handset's capture form no longer draws the contract's fields:\n"
        + _report("android", ANDROID_MAIN, expected, actual)
        + "\n\n  Declared but not drawn here: "
        + repr([label for label in expected if label not in {a for a, _ in actual}])
        + "\n  Drawn here and not declared: "
        + repr([label for label, _ in actual if label not in expected])
        + f"\n\n  The WEB is the reference — the owner ruled it correct — so the handset moves to "
        f"match {CONTRACT_PATH.name}, not the other way round. If the difference is a control the "
        "handset draws deliberately and the browser does not, it belongs in `alsoDrawn.android` "
        "with the reason; if it is a field, it belongs on the web and in all three registers first."
    )


def test_the_android_form_draws_each_field_with_the_composable_the_contract_names():
    """A label being right is not the same claim as a widget being right, and here it never was.

    This is the assertion that would have failed over the wall of checkboxes even if somebody had
    renamed it to “Artisans interviewed” first — which is the cheapest possible way to make a label
    check go green while changing nothing a researcher would notice as fixed.
    """
    for field in contract()["fields"]:
        found = android_control_for(field)
        assert found is not None, (
            f"the contract's “{field['label']}” could not be located in {ANDROID_MAIN.name}'s "
            "capture form at all, so the composable drawing it cannot be read. The assertion above "
            "says which fields are present; fix that one first."
        )
        composable, line = found
        assert composable == field["android"]["composable"], (
            f"{ANDROID_MAIN.name}:{line} draws the contract's “{field['label']}” with "
            f"`{composable}`, and {CONTRACT_PATH.name} declares `{field['android']['composable']}` "
            f"(control kind: {field['control']}).\n"
            "  A control swap is not a styling change: it changes what the researcher can express. "
            "If the handset genuinely needs a different control, say so in the contract and on the "
            "web in the same commit — one client quietly drawing a different widget is the whole "
            "class of defect this file exists to end."
        )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 2. The artisan control is ONE searchable multi-select, and was two
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_artisan_control_is_one_searchable_multiselect_and_never_two():
    """The third of the three defects, pinned so that a re-split fails rather than merely reappears.

    THREE ASSERTIONS AND NOT ONE, because each alone goes green over the defect:

    * A label check goes green over a re-split — add a second box called anything and both halves
      still carry their own true label.
    * A "is it a MultiSelectDropdown" check goes green with "Primary artisan" restored beside it.
    * So the third is the STATE: `selectedArtisanId` and `additionalArtisanIds` were two pieces of
      React state before they were two boxes, and a re-split starts there. `QuestionnaireInterview-
      Artisan` has no rank column and `artisan_set_key` sorts the ids on the server, so there is
      nothing for a second variable to mean.

    `searchable` is asserted with them: this is the picker in the app where it matters most, because
    the SET chosen here is what decides `artisanSetKey` — WHICH INTERVIEW a submission folds into —
    and the labels are `name - craft - place` triples that differ by one word.
    """
    spec = contract()
    fields = {field["key"]: field for field in spec["fields"]}
    region, base = _web_form_region()
    stripped = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))
    raw = WEB_FORM.read_text(encoding="utf-8")

    for pin in spec["controlPins"]:
        field = fields[pin["field"]]
        assert field["control"] == pin["pin"], (
            f"{CONTRACT_PATH.name} pins “{field['label']}” to {pin['pin']!r} and declares its "
            f"control as {field['control']!r}. The contract disagrees with itself: one of the two "
            "was edited alone."
        )

        for label in pin["forbiddenLabels"]:
            at = region.find(f'label="{label}"')
            assert at < 0, (
                f"{WEB_FORM.name}:{_line_of(stripped, base + at)} draws a control labelled "
                f"“{label}” inside the capture form, and {CONTRACT_PATH.name} forbids it for the "
                f"“{field['label']}” field.\n"
                "  That is the split this change removed. `QuestionnaireInterviewArtisan` is "
                "`@@id([interviewId, artisanId])` plus `createdAt` — no rank column — and "
                "`artisan_set_key` sorts the ids before they reach the @unique index, so nothing "
                "downstream can tell a 'primary' artisan from any other. One control, one array; "
                "anything needing a single artisan takes element 0 (`primaryInterviewArtisanId`)."
            )

        for name in pin["forbiddenWebState"]:
            found = re.search(rf"\b(?:const|let|var)\s*\[\s*{name}\b", _strip_ts_comments(raw))
            assert found is None, (
                f"{WEB_FORM.name}:{_line_of(_strip_ts_comments(raw), found.start())} declares "
                f"`{name}` state, and {CONTRACT_PATH.name} forbids it.\n"
                "  Half a selection in its own variable is where the two controls came from. The "
                "whole set is one ordered array, `selectedArtisanIds`."
            )

        found = web_control_for(field)
        assert found is not None and found[0] == field["web"]["component"], (
            f"the web no longer draws “{field['label']}” with <{field['web']['component']}>. "
            "Found: "
            + (f"<{found[0]}> at {WEB_FORM.name}:{found[1]}" if found else "nothing")
            + f"\n  {CONTRACT_PATH.name} pins this field to {pin['pin']!r} on both clients."
        )

        # READ THE CONTROL'S OWN TAG, not a window of characters after the label. A window is a
        # guess about layout: it was 1200 characters and went red the moment the field grew a `hint`
        # slot between its label and its control, which is a true change to nothing this assertion
        # is about. The element is what carries the prop, so the element is what is read.
        at = region.find(f'label="{field["label"]}"')
        tag_at = region.find(f'<{field["web"]["component"]}', at)
        assert tag_at > at, (
            f"{WEB_FORM.name}:{_line_of(stripped, base + at)} labels “{field['label']}” and no "
            f"<{field['web']['component']}> follows it. The assertion above says which control "
            "draws this field; fix that one first."
        )
        tag = region[tag_at : region.find("/>", tag_at) + 2]
        assert re.search(r"\bsearchable\b", tag), (
            f"{WEB_FORM.name}:{_line_of(stripped, base + tag_at)} draws “{field['label']}” without "
            "`searchable`.\n"
            "  It is forced here rather than left to an option count on purpose: one workshop on "
            "this deployment, forty on the next, and a control that changes shape between two "
            "workshops for reasons the researcher cannot see is a second thing to learn."
        )


def test_the_handset_draws_the_pinned_artisan_control_and_not_the_wall():
    """THE SAME PIN, ON THE CLIENT IT WAS WRITTEN ABOUT — held as of 2026-09-16.

    TWO ASSERTIONS AND NEITHER IS REDUNDANT, which is the same argument the web arm makes one level
    along.

    * The POSITIVE one — this field is drawn by the composable the contract names — is green over a
      wall of checkboxes RENAMED to `SearchableMultiSelectField`. It has to be here anyway, because
      without it a forbidden widget swapped in would be reported as a field nobody could find.
    * The NEGATIVE one names the composables that must not appear in this form's body at all, so a
      call restored under any label, at any position, fails as the widget change it is. That is what
      makes the pin structural: the label is the thing an author changes to make a check pass, and
      the widget is the thing the researcher actually meets.

    IT IS SCOPED TO THE FORM'S BODY AND NOT TO THE FILE. `ArtisanMultiSelectField` still has two
    callers in `MainActivity.kt` — the workshop record form's own “Linked artisans” box and the
    browse screen's “Involved artisan(s)” filter — and both are deliberate; `controls.android`
    carries that argument. A file-wide grep would be red over a decision somebody took on purpose,
    which is how a check gets muted rather than fixed.
    """
    spec = contract()
    fields = {field["key"]: field for field in spec["fields"]}
    region, base = _android_form_region()
    stripped = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))

    for pin in spec["controlPins"]:
        field = fields[pin["field"]]
        found = android_control_for(field)
        assert found is not None and found[0] == field["android"]["composable"], (
            f"the handset no longer draws “{field['label']}” with "
            f"`{field['android']['composable']}`. Found: "
            + (f"`{found[0]}` at {ANDROID_MAIN.name}:{found[1]}" if found else "nothing")
            + f"\n  {CONTRACT_PATH.name} pins this field to {pin['pin']!r} on BOTH clients, and "
            "`ui/SearchableSelect.kt` already holds the control it names. There is no third control "
            "to write and writing one would be this whole problem again."
        )

        for name in pin["forbiddenAndroidComposables"]:
            call = re.search(rf"\b{name}\s*\(", region)
            assert call is None, (
                f"{ANDROID_MAIN.name}:{_line_of(stripped, base + call.start())} calls `{name}(` "
                f"inside the capture form, and {CONTRACT_PATH.name} forbids it for the "
                f"“{field['label']}” field.\n"
                "  That composable paints a Column of checkboxes — one row per artisan in the whole "
                "register, straight into a form a thousand lines long, with no summary line, so the "
                "only way to see what is ticked is to scroll the form back over it. The owner asked "
                "for the multi-select by name. `SearchableMultiSelectField` is what this field uses; "
                "the wall's other two callers are other screens and are not in question here."
            )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 3. The artisan roster is fetched WITH A WORKSHOP SCOPE, and by nothing but the shared rules
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_artisan_roster_is_fetched_with_a_workshop_scope():
    """The first of the three defects: the picker offered artisans from every workshop.

    THE PAGE MUST NOT FETCH ARTISANS AT ALL. Not "should not" — the defect WAS one unscoped
    `/artisans` call inside `loadMeta()`, and a second one added beside the hook would restore it in
    a form nobody reviewing the hook would see. So the assertion is about the page (no `/artisans`
    anywhere in it) and about the rules file (every named symbol still exported), which together say
    "there is exactly one way this screen learns who the artisans are".

    THE SPELLING IS ASSERTED TOO, and it is not pedantry. `GET /artisans` accepts `workshopId` and
    `workshopIds` and they are NOT the same filter: the singular narrows on the column OR the
    `WorkshopArtisan` roster, the plural goes through `artisan_workshop_clause` and also counts
    having SAT IN an interview taken at the workshop. `list_artisans` ANDs everything it is given, so
    sending both would silently intersect down to the singular's narrower answer — and the handset
    sends the plural, so that is the one spelling that breaks parity while looking careful.
    """
    spec = contract()["artisanScope"]
    rules_path = _ROOT / spec["rules"]
    assert rules_path.exists(), (
        f"{spec['rules']} is missing. It is where every rule this screen owes the handset is "
        "written; the page is not allowed to re-derive them inline."
    )
    rules = _strip_ts_comments(rules_path.read_text(encoding="utf-8"))

    for symbol in spec["requiredSymbols"]:
        assert re.search(rf"\bexport\s+(?:function|const)\s+{symbol}\b", rules), (
            f"{spec['rules']} no longer exports `{symbol}`, which {CONTRACT_PATH.name} names as "
            "part of the artisan-scope contract.\n"
            "  These are exported rather than inlined because a rule the handset cannot read is a "
            "rule the handset will not match. If one has genuinely been folded into another, say so "
            "in the contract in the same commit."
        )

    params = spec["singular"]
    body = rules[rules.find("export function workshopArtisanParams") :]
    body = body[: body.find("\n}\n") + 2]
    for name in params["mustSend"]:
        assert re.search(rf"\b{name}\s*:", body), (
            f"`workshopArtisanParams` in {spec['rules']} no longer sends `{name}`.\n"
            "  Without a workshop on the wire this picker is the whole repository under a named "
            "workshop, which is the defect this file exists to close."
        )
    for name in params["mustNotSend"]:
        assert not re.search(rf"^\s*{name}\s*:", body, re.MULTILINE), (
            f"`workshopArtisanParams` in {spec['rules']} sends `{name}`, which "
            f"{CONTRACT_PATH.name} forbids.\n"
            "  It is a NARROWER filter than `workshopIds` on this route, not an equivalent one, and "
            "`list_artisans` ANDs them — so 'belt and braces' here is a wrong narrowing, and it is "
            "the spelling the handset does not send."
        )

    page = _strip_ts_comments(WEB_FORM.read_text(encoding="utf-8"))
    hit = re.search(r'["\']/artisans["\']', page)
    assert hit is None, (
        f"{WEB_FORM.name}:{_line_of(page, hit.start())} fetches `/artisans` directly.\n"
        f"  Every artisan this screen offers comes through `useWorkshopArtisans` in {spec['rules']}, "
        "which is keyed on the workshop. A second call beside it is the original defect: one "
        "unscoped page of the whole table, offered under whichever workshop the boxes above happen "
        "to show."
    )


def _kotlin_function_source(source: str, symbol: str) -> str | None:
    """One top-level function's signature AND body, however that body is written.

    NOT BRACE-BALANCED FROM THE FIRST `{`, which is what `_kotlin_declaration` above does and what it
    should keep doing: that one resolves LABELS out of composables, which all have block bodies, and
    it is bounded for a reason worth keeping. This reads RULES, and a rule is routinely an expression
    body — `= if (routesToDesignWorkshop) { … } else { … }` — where the first `{` is the *then* arm
    and the condition that decides everything is in front of it. A balanced slice there returns half
    a rule and reports the other half as missing, which reads as "the fix was undone" over a fix that
    is there.

    So it runs to the start of the NEXT TOP-LEVEL DECLARATION: a newline followed immediately, at
    column zero, by a declaration keyword or an annotation. `} else {` and a closing brace do not
    start with one, so an expression body survives; comments are already stripped to blank lines by
    the caller, so a KDoc between two functions cannot end the slice early.
    """
    at = re.search(rf"\bfun\s+{re.escape(symbol)}\s*\(", source)
    if at is None:
        return None
    tail = source[at.start() :]
    stop = re.search(
        r"\n(?:@|(?:public |private |internal |protected )?"
        r"(?:suspend |inline |tailrec |operator )*"
        r"(?:fun|class|object|interface|val|var|const|enum|data|sealed)\b)",
        tail[1:],
    )
    return tail[: stop.start() + 1] if stop else tail


def _android_source(relative: str) -> str:
    """One handset file, comment-stripped, or a failure that names it.

    Comment-stripped for the reason the two strippers exist at all: every assertion below looks for
    a parameter name or a call, and this repository writes paragraphs around both — the argument for
    `designWorkshopId` quotes the parameter it is about a dozen times, and a raw scan would find the
    prose and report a wire that does not exist.
    """
    path = _ROOT / relative
    assert path.exists(), (
        f"{CONTRACT_PATH.name} reads the handset's artisan scope out of {relative}, which does not "
        "exist. If the file moved, move the contract with it in the same commit."
    )
    return _strip_kotlin_comments(path.read_text(encoding="utf-8"))


def test_the_handset_sends_the_workshop_scope_on_its_artisan_read():
    """DEFECT (1) ON THE HANDSET — the last row of `openDrifts`, and the one that took a wave.

    The capture form offered EVERY ARTISAN IN THE DEPLOYMENT under whichever workshop its own picker
    was showing. `loadArtisanRegister`'s whole fetch is a bare `repository.artisans()` — the shared,
    ALL-scoped, offline-cached register five other record forms read — and nothing re-asked when the
    workshop box moved.

    IT IS ASSERTED AS A REQUEST AND NOT AS A SCREEN, which is the whole reason this test can see the
    defect at all. Every version of it has the right TYPE: the picker holds a `List<ArtisanDto>`
    whether that list is the workshop's roster or the whole table, nothing errors, and nothing is
    missing. So the three properties below are the three places the fix can be undone —

      1. the WIRE declares the parameter. `WorkshopRepositoryApi.artisans()` carried `workshopIds`
         and no `designWorkshopId` until 2026-09-17, though `list_artisans` has accepted one the
         whole time, so the design-routed half of this scope could not be expressed on this client
         at all. That is why closing the ordinary half alone and deleting the drift row would have
         been worse than leaving it open.
      2. the RULE picks exactly one spelling, off the routing the type box already exposes — never
         both (`list_artisans` ANDs its filters and the two rosters are different populations, so an
         AND of them is a handful of rows) and never the singular `workshopId`, which is a NARROWER
         filter on this route than the plural and is not the one `ConsolidatedQuestionnaireScreen`
         sends.
      3. the FORM actually calls the rule. A perfect rule nothing invokes is the defect with a unit
         test in front of it.

    `InterviewArtisanScopeWireTest` on the handset drives the same call path against a canned
    transport and reads the query string off it; this repeats only what a contract can state, which
    is that the parameter exists, is chosen by the routing, and reaches the form.
    """
    spec = contract()["artisanScope"]["android"]

    wire = spec["wire"]
    api = _android_source(wire["file"])
    at = re.search(rf"\bfun\s+{re.escape(wire['symbol'])}\s*\(", api)
    assert at is not None, (
        f"{wire['file']} no longer declares `fun {wire['symbol']}(`. That is the handset's spelling "
        "of `GET /artisans`; if it was renamed, rename it in the contract too."
    )
    declaration = _balanced(api, api.index("(", at.start()), "(", ")")
    for name in wire["mustDeclare"]:
        assert re.search(rf'@Query\("{name}"\)', declaration), (
            f"{wire['file']}:{_line_of(api, at.start())} — `{wire['symbol']}()` declares no "
            f"`@Query(\"{name}\")`.\n"
            "  The route has accepted it the whole time (`backend/app/api/routes/artisans.py`, "
            "`list_artisans`). Without it on this interface the handset cannot express the scope at "
            "all, and the capture form's picker is the whole deployment under a named workshop."
        )
    for name in wire["mustNotDeclare"]:
        assert not re.search(rf'@Query\("{name}"\)', declaration), (
            f"{wire['file']}:{_line_of(api, at.start())} — `{wire['symbol']}()` declares "
            f"`@Query(\"{name}\")`, which {CONTRACT_PATH.name} forbids on this client.\n"
            "  The singular narrows on the artisan's own column OR the `WorkshopArtisan` join; the "
            "plural `workshopIds` goes through `artisan_workshop_clause` and also counts having SAT "
            "IN an interview taken at the workshop. `list_artisans` ANDs them, so a second spelling "
            "here is a wrong narrowing waiting for a caller."
        )

    rule = spec["rule"]
    rules = _android_source(rule["file"])
    body = _kotlin_function_source(rules, rule["symbol"])
    assert body is not None, (
        f"{rule['file']} no longer declares `fun {rule['symbol']}(`.\n"
        "  That function is the one place on this client that knows the plural goes with a "
        "`Workshop` and the singular design id with a `DesignWorkshop`. A form deciding it inline is "
        "the copy-per-caller this contract exists to prevent."
    )

    assert rule["routesOn"] in body, (
        f"`{rule['symbol']}` in {rule['file']} no longer reads `{rule['routesOn']}`.\n"
        "  The ROUTING is what picks the parameter, never 'whichever id is non-blank'. "
        "`RecordWorkshopLink` mounts both halves at once and each defaults itself while the other is "
        "on screen, so a design-routed form is routinely holding a good ordinary workshop id too — "
        "and sending it would scope the roster by a workshop this record is not filed under."
    )
    for name in rule["mustSend"]:
        assert re.search(rf"\b{name}\b", body), (
            f"`{rule['symbol']}` in {rule['file']} no longer produces `{name}`.\n"
            "  Both spellings are needed and neither is optional: the plural for an ordinary "
            "`Workshop`, the singular `designWorkshopId` for a `DesignWorkshop`. Scoping one arm "
            "leaves the other offering the whole deployment with nothing on screen saying so."
        )
    for name in rule["mustNotSend"]:
        assert not re.search(rf"\b{name}\s*=", body), (
            f"`{rule['symbol']}` in {rule['file']} sends `{name}`, which {CONTRACT_PATH.name} "
            "forbids — see the web's `artisanScope.singular`, which refuses the same spelling for "
            "the same reason."
        )

    form = spec["form"]
    region, _ = _android_form_region()
    for name in form["calls"]:
        assert re.search(rf"\b{name}\s*\(", region), (
            f"the handset's capture form does not call `{name}(`.\n"
            "  A scope rule nothing invokes is the defect with a unit test in front of it: the "
            "picker goes on offering `loadArtisanRegister`'s ALL-scoped register, which is exactly "
            f"what `{CONTRACT_PATH.name}` recorded as open until 2026-09-17."
        )
    assert form["passes"] in region, (
        f"the handset's capture form no longer passes `{form['passes']}` into its artisan scope.\n"
        "  That flag is the routing ruling this release established — a type that routes to the "
        "design-workshop table means the chosen workshop is a `designWorkshopId`, any other type "
        "means a `workshopId` — and it is the only thing entitled to choose between them."
    )
    call = re.search(rf"\b{re.escape(form['calls'][0])}\s*\(", region)
    arguments = _balanced(region, call.end() - 1, "(", ")")
    for name in form["mustNotPass"]:
        assert name not in arguments, (
            f"the handset's capture form passes `{name}` to `{form['calls'][0]}(`.\n"
            "  The type key is a ROUTER stored on nothing — no foreign key points at "
            "`WorkshopTypeOption` from any record table and `GET /artisans` has no parameter for "
            "it. FastAPI drops an unknown query parameter silently, so a key sent 'for "
            "completeness' reads as a filter that works right up until somebody relies on it."
        )


def test_the_handset_keeps_the_four_empty_roster_sentences_apart():
    """The fourth sentence, and the reason it may only be said now.

    An empty artisan roster means one of several different things — the request is in flight, it was
    refused, this device has never received it, nobody is recorded at THIS workshop, nobody is
    recorded at all — and only the last two are facts about the repository. Until the list was
    actually fetched per workshop the handset was entitled to say three of them, and its own call
    site said so: *"a `scopedEmptyLine` printed off an unscoped read would be exactly the defect
    above, wearing the fix's clothes."*

    COLLAPSING ANY TWO IS THE FAILURE THIS ASSERTS AGAINST, not their absence. "No artisans are
    recorded yet" printed over a workshop that simply has none sends a researcher off to create a
    duplicate of somebody who already exists — which is what happened in the sibling repository on
    2026-09-17. So the sentences are checked PAIRWISE DISTINCT as well as present: a lazy fix that
    pointed the new state at an existing sentence would pass a presence check and fail this one.
    """
    spec = contract()["artisanScope"]["android"]["emptyState"]
    source = _android_source(spec["file"])
    region, _ = _android_form_region()

    produced: dict[str, str] = {}
    for name in spec["sentences"]:
        at = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
        assert at is not None, (
            f"{spec['file']} no longer declares `fun {name}(`.\n"
            "  These strings are the contract — both clients print them byte for byte — and the "
            "module's own header forbids anything outside it writing another."
        )
        body = source[at.start() : source.find("\n\n", at.start())]
        literal = re.search(r'"((?:[^"\\]|\\.)*)"', body)
        assert literal is not None, (
            f"`{name}` in {spec['file']} no longer builds a sentence from a literal this test can "
            "read. If the wording moved, move this assertion with it rather than deleting it."
        )
        produced[name] = literal.group(1)

        assert re.search(rf"\b{name}\s*\(", region), (
            f"the handset's capture form does not print `{name}(`.\n"
            "  The four states are told apart at the picker or they are not told apart at all: a "
            "control that cannot say which emptiness this is makes a claim about the repository out "
            "of a claim about the network."
        )

    duplicates = [
        (a, b)
        for index, a in enumerate(spec["sentences"])
        for b in spec["sentences"][index + 1 :]
        if produced[a] == produced[b]
    ]
    assert not duplicates, (
        "two of the empty-roster sentences are now the same string: "
        + repr(duplicates)
        + "\n\n  Each names a different next move — wait, connect, retry, ask an administrator, "
        "create a record — and a reader given the wrong one goes looking for the wrong person. The "
        "duplicate-artisan report that closed this drift is what collapsing two of them costs."
    )


def test_the_interview_list_is_fetched_with_the_workshop_on_it():
    """The second of the three defects, already closed on the web here and pinned so it stays closed.

    The dropdown above the interviews table rendered a workshop as the active filter and no byte
    about it left the browser, so the list underneath stayed the whole repository with the pager's
    `total` counting it. Nothing was hidden — the list is a SUPERSET — which is what made it survive:
    no record ever looked deleted. `e2e/questionnaire-workshop-filter-unit.spec.ts` carries the
    argument and asserts both halves; this repeats only the request, because the contract has to be
    able to say what "scoped by the same workshop" means without sending a reader to a spec file.
    """
    spec = contract()["interviewListScope"]
    source = _strip_ts_comments((_ROOT / spec["file"]).read_text(encoding="utf-8"))
    at = source.find(spec["request"])
    assert at >= 0, (
        f"{spec['file']} no longer contains {spec['request']!r}. That call is how this screen lists "
        "interviews; if it moved, this contract moves with it."
    )
    block = source[at : source.find("});", at)]
    for param in spec["mustCarry"]:
        assert param in block, (
            f"{spec['file']}:{_line_of(source, at)} lists interviews without `{param}`.\n"
            "  A filter control whose value never leaves the browser is worse than no control: the "
            "reader takes the rows below it as that workshop's interviews and acts on rows that are "
            "not."
        )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 4. The registers — ORDER and the CEILING. The floor is declared and not yet held; see openDrifts
# ══════════════════════════════════════════════════════════════════════════════════════════════

WEB_REGISTER = _ROOT / "frontend" / "components" / "guide" / "steps.ts"
ANDROID_REGISTER = (
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
PRINTED_GUIDE = _ROOT / "docs" / "WALKTHROUGH.md"


def _loose(text: str) -> str:
    """Lowercased, with every run of non-alphanumerics collapsed to one space.

    The registers are decorated and the prose is markdown: `steps.ts` writes "Interview title
    (required)" and `docs/WALKTHROUGH.md` writes "**Interview title** *(required)*", and that
    emphasis is typography rather than a different claim about the form.
    """
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def _names(haystack: str, label: str) -> bool:
    """Does *haystack* name *label*, allowing decoration but not a coincidental substring.

    Padded on both sides rather than a bare `in`: without the padding "Place" is named by the word
    "Placement" and "Status" by "Statuses", and a check that can be satisfied by an unrelated word is
    a check that reports a guide as complete because of a typo in it.
    """
    return f" {_loose(label)} " in f" {_loose(haystack)} "


def _web_register_fields() -> list[str]:
    """The questionnaire card's `fields[]` out of `steps.ts`, in order."""
    source = _strip_ts_comments(WEB_REGISTER.read_text(encoding="utf-8"))
    spec = contract()["registers"]["web"]
    at = source.find(spec["anchor"])
    assert at >= 0, (
        f"{WEB_REGISTER.name} no longer declares a card with `{spec['anchor']}`. That card is the "
        "web walkthrough's description of this form; if the step was renamed, this file and "
        "`test_walkthrough_fields_parity.py` both have to learn the new id."
    )
    opener = re.search(r"\bfields:\s*\[", source[at:])
    assert opener, (
        f"{WEB_REGISTER.name}'s questionnaire card no longer declares a fields[] array."
    )
    block = _balanced(source, at + opener.end() - 1, "[", "]")
    return re.findall(r'"((?:[^"\\]|\\.)*)"', block)


def _android_register_fields() -> list[str]:
    """The same list out of `WalkthroughJourney.kt`, in order.

    Kotlin-stripped first, and that is not belt-and-braces: the register's own KDoc quotes
    `GuideStep.fields`, names `test_walkthrough_fields_parity.py` and discusses the very strings
    below. Read raw, this would pick up quoted runs out of a paragraph about the register and report
    them as entries in it.
    """
    source = _strip_kotlin_comments(ANDROID_REGISTER.read_text(encoding="utf-8"))
    spec = contract()["registers"]["android"]
    at = source.find(spec["anchor"])
    assert at >= 0, (
        f"{ANDROID_REGISTER.name} no longer declares `{spec['anchor']}`. The step ids are the join "
        "between the two walkthroughs; a step whose id this file cannot find is a step that has "
        "been renamed on one client alone."
    )
    block = _balanced(source, at + len(spec["anchor"]) - 1, "(", ")")
    return re.findall(r'"((?:[^"\\]|\\.)*)"', block)


def _printed_guide_sentence() -> str:
    """The "What the screen asks for" line under the printed guide's Questionnaire heading.

    Attached to the numbered HEADING above it rather than to a position in the file, so that
    inserting a section does not shift the list quietly onto the next step. That failure would not be
    loud in a useful way: the reader would be sent to the wrong step and told a true thing about a
    screen they were not looking at.

    WHITESPACE COLLAPSED, because the document is hard-wrapped and its field list is split across
    lines. The wrap column is an editor setting, not a claim about the product.
    """
    spec = contract()["registers"]["printed"]
    marker = spec["marker"]
    raw = PRINTED_GUIDE.read_text(encoding="utf-8")
    heading = None
    for paragraph in re.split(r"(?:\r?\n){2,}", raw):
        found = re.match(r"^## \d+\. (.+?) [-—]", paragraph)
        if found:
            heading = found.group(1).strip()
        stripped = paragraph.lstrip()
        if not stripped.startswith(marker):
            continue
        if heading == spec["heading"]:
            return " ".join(stripped[len(marker) :].split())
    raise AssertionError(
        f"{PRINTED_GUIDE.name} carries no {marker!r} line under a “{spec['heading']}” heading. "
        "This is the copy that gets printed and carried into a village with no signal — it is the "
        "one rendering whose reader cannot check it against the screen — so a missing list here is "
        "not a documentation nit. If the heading or the marker was reworded, update the contract's "
        "`registers.printed` in the same commit."
    )


def _registers() -> dict[str, tuple[pathlib.Path, str]]:
    """``{name: (file, the text that register uses to describe this form)}``."""
    return {
        "web walkthrough": (WEB_REGISTER, " · ".join(_web_register_fields())),
        "android walkthrough": (ANDROID_REGISTER, " · ".join(_android_register_fields())),
        "printed guide": (PRINTED_GUIDE, _printed_guide_sentence()),
    }


def test_all_three_registers_still_parse_to_something():
    """Again the vacuous-green guard, and again because the checks below are containments.

    The CEILING check is a containment that PASSES over an empty haystack: "no register names a field
    the form does not have" is trivially true of a register that parsed to nothing. That is the
    assertion most likely to be silently switched off by a reformat.
    """
    for name, (path, text) in _registers().items():
        assert len(text) > 30, (
            f"the {name} register ({path.name}) parsed to {text!r}, which is too short to be a "
            "description of this form's boxes. The parser is matching the wrong thing, and until it "
            "is fixed the ceiling assertion below passes over anything."
        )


def test_no_register_names_a_field_this_form_deliberately_does_not_have():
    """THE CEILING, and the assertion a two-way parity test structurally cannot make.

    A floor check looks for what SHOULD be there and is blind by construction to what should not. All
    three registers named a "Date" here until 2026-08-31 — the form has never had one, because the
    server derives `interviewDate` from `recordedAt` — and the printed guide still records that it
    did. A guide naming a field that was deliberately removed sends the reader looking for it, and
    the printed guide does that to somebody with no signal to check against.
    """
    offenders: list[str] = []
    for entry in contract()["absent"]:
        for label in entry["labelsThatMustNotAppear"]:
            for name, (path, text) in _registers().items():
                if _names(text, label):
                    offenders.append(f"  {name} ({path.name}) names {label!r}")
    assert not offenders, (
        "a register names a field this form deliberately does not have:\n"
        + "\n".join(offenders)
        + "\n\n  See `absent` in "
        + CONTRACT_PATH.name
        + " for the decision and where it is recorded in the source. Remove the entry from the "
        "register — do NOT add the field to the form to make this green, and do not delete the "
        "`absent` row: it is the only thing in this repository that can notice a guide describing a "
        "box that is not there."
    )


def test_the_registers_name_the_contract_fields_in_the_contract_order():
    """Order again, one level out: a guide that lists a form's boxes out of sequence.

    The registers are documented as SCREEN ORDER on both clients, and the printed guide is read with
    the form open beside it — so a list in the wrong sequence sends the reader to the box two fields
    further down and, finding a different one, filling it in.

    RESTRICTED TO THE FIELDS BOTH THE CONTRACT AND THE REGISTER HAVE, because the registers
    legitimately carry more (the capture preferences, the audio card, the location group, the
    per-question line) and, today, one fewer — see `openDrifts`. What is compared is the order of the
    fields they share, which is the only sequence the two can disagree about.
    """
    expected = contract_labels()
    for name, (path, text) in _registers().items():
        # PADDED, exactly as `_names` is, and for the same reason: an unpadded find would place
        # "Place" at whatever offset the word "Placement" happens to sit at and then report an
        # ordering defect that is really a coincidence between two words.
        loose = f" {_loose(text)} "
        positions = sorted(
            (loose.find(f" {_loose(label)} "), label)
            for label in expected
            if loose.find(f" {_loose(label)} ") >= 0
        )
        seen = [label for _, label in positions]
        wanted = [label for label in expected if label in seen]
        assert seen == wanted, (
            f"the {name} register ({path.name}) lists this form's fields in a different order from "
            f"{CONTRACT_PATH.name}:\n"
            f"    contract: {wanted}\n"
            f"    register: {seen}\n"
            "  The contract's order is the WEB's screen order and it is contractual — a guide read "
            "with the form open beside it sends the reader to the wrong box."
        )


def test_every_contract_field_is_named_in_all_three_registers():
    """THE FLOOR. A field nobody was told about is a field nobody fills in.

    OFF BY DEFAULT, AND THE CONTRACT SAYS SO IN ONE BOOLEAN. `registers.floorEnforced` is `false`
    while `openDrifts` holds the two rows that would make it red — the registers omit "Type of
    workshop" and still name the two artisan controls that became one. Both are one-line edits in
    three files that belong to another lane, and both are asserted as OPEN below, so neither can be
    quietly forgotten: the day somebody lands them, `test_every_open_drift_is_still_open` fails and
    says to flip this boolean.

    THIS IS NOT THE SAME THING AS SKIPPING THE CHECK. A skip is invisible and says nothing; this is a
    declaration in the contract with the measurement, the reason and the edit written beside it, and
    a second assertion keeping that declaration honest.
    """
    spec = contract()
    if not spec["registers"]["floorEnforced"]:
        rows = [row["id"] for row in spec["openDrifts"]["rows"] if "registers" in row["surface"]]
        assert rows, (
            f"{CONTRACT_PATH.name} has `registers.floorEnforced: false` and no `openDrifts` row "
            "explaining it. Either turn the floor on, or say in the contract which register gap is "
            "holding it off — an unexplained switch is how a check stays off for ever."
        )
        return

    missing: list[str] = []
    for name, (path, text) in _registers().items():
        for field in spec["fields"]:
            if not _names(text, field["label"]):
                missing.append(f"  {name} ({path.name}) · “{field['label']}”")
    assert not missing, (
        "a register no longer names every field the contract declares:\n"
        + "\n".join(missing)
        + "\n\n  Each of these is a box a researcher has not been told to fill in. Add it to that "
        "step's field list — READ THE FORM FIRST and put it in the contract's order, because that "
        "list gets read with the form open beside it."
    )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 5. The ratchet: every drift this contract knows about is STILL a drift
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_every_open_drift_is_still_open():
    """THE ASSERTION THAT FIRES WHEN SOMEBODY FIXES SOMETHING, and that is the intended behaviour.

    Read the failure before you read anything else: if this test is red, a drift the contract lists
    as OPEN has been CLOSED. That is good news. The edit is to delete that row from
    `openDrifts.rows` — and, if it was the last register row, to set `registers.floorEnforced` to
    true and let the real check take over.

    WHY A LIST OF KNOWN PROBLEMS IS ASSERTED AT ALL. Because one that is not becomes the third
    register: it keeps reading as a considered account of where the product is wrong long after the
    product has moved, and the next person to widen this file treats it as accurate. `distinctLabels`
    is held for the same reason in the other direction — a licence nobody checks is a licence for
    nothing.

    EVERY ROW NAMES A FACT A PARSER CAN SEE, not a prose description. The `stillTrue` object is the
    whole of what is checked; the `found` paragraphs are for the person who has to act on it.
    """
    spec = contract()
    android_region, _ = _android_form_region()
    registers = _registers()
    register_text = " · ".join(text for _, text in registers.values())

    for row in spec["openDrifts"]["rows"]:
        still = row["stillTrue"]
        closed = (
            f"\n\n  GOOD NEWS: the drift `{row['id']}` recorded in {CONTRACT_PATH.name} appears to "
            "be CLOSED. This test asserts that the contract's own list of known-open problems is "
            "still accurate, so a fix makes it red on purpose.\n"
            f"  What the row said to do: {row['edit']}\n"
            "  Delete the row from `openDrifts.rows`. If it was the last `registers` row, set "
            "`registers.floorEnforced` to true in the same commit and let "
            "`test_every_contract_field_is_named_in_all_three_registers` take over."
        )

        if "registersDoNotName" in still:
            label = still["registersDoNotName"]
            assert not _names(register_text, label), (
                f"a register now names “{label}”." + closed
            )
        if "registersName" in still:
            label = still["registersName"]
            assert _names(register_text, label), (
                f"no register names “{label}” any more." + closed
            )
        if "androidFormCalls" in still:
            name = still["androidFormCalls"]
            assert re.search(rf"\b{name}\s*\(", android_region), (
                f"the handset's capture form no longer calls `{name}()`." + closed
            )
        if "androidFormCallsWithoutLabel" in still:
            name = still["androidFormCallsWithoutLabel"]
            call = re.search(rf"\b{name}\s*\(", android_region)
            assert call is not None, (
                f"the handset's capture form no longer calls `{name}()`." + closed
            )
            args = _balanced(android_region, call.end() - 1, "(", ")")
            assert not re.search(r'\blabel\s*=\s*"', args), (
                f"the handset's `{name}(` call now passes a label." + closed
            )
        if "androidDrawsBefore" in still:
            first, second = still["androidDrawsBefore"]
            at_first = android_region.find(f'"{first}"')
            at_second = android_region.find(f'"{second}"')
            # The workshop label is not a literal in the form body — it lives in `WorkshopField`'s
            # own dropdown call — so the FIELD is what is located, not the string.
            if at_first < 0:
                at_first = android_region.find("WorkshopField(")
            assert at_first >= 0 and at_second >= 0, (
                f"cannot locate both “{first}” and “{second}” in the handset's capture form; the "
                "parser needs updating before this row means anything."
            )
            assert at_first < at_second, (
                f"the handset no longer draws “{first}” before “{second}”." + closed
            )
        if "androidFetchesArtisansVia" in still:
            name = still["androidFetchesArtisansVia"]
            source = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
            at = source.find(ANDROID_FORM_ANCHOR)
            caller = source.rfind(f"{name}(context, repository)", 0, at)
            assert caller >= 0 or f"{name}(" in source, (
                f"`{name}` is gone from {ANDROID_MAIN.name}." + closed
            )
            fetch = re.search(rf"fun {name}\(", source)
            assert fetch is not None, (
                f"`{name}` is no longer declared in {ANDROID_MAIN.name}." + closed
            )
            body = source[fetch.start() : fetch.start() + 1200]
            assert "repository.artisans()" in body, (
                f"`{name}` no longer fetches with a bare `repository.artisans()`." + closed
            )
        if "androidFormSendsNoWorkshopScope" in still:
            # THE ARM THE ROW ABOVE CANNOT SUPPLY ON ITS OWN, and the reason is written out in that
            # row's `whyStillTrueSaysTwoThings`. `androidFetchesArtisansVia` asserts about the SHARED
            # register loader, which the row's own `edit` forbids narrowing — five other forms read
            # it — so a correct fix leaves that fact true and the ratchet never fires. This is the
            # fact the fix does move: the capture form's own body asks for no workshop scope at all.
            spelling = still["androidFormSendsNoWorkshopScope"]
            at = android_region.find(spelling)
            assert at < 0, (
                f"the handset's capture form now mentions `{spelling}`, so it is asking for a "
                "workshop-scoped artisan roster." + closed
            )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 6. The deliberately different label, and the contract's own hygiene
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_every_deliberately_different_label_still_exists_and_still_says_it():
    """`distinctLabels` — the sites that use another word on purpose, held so they cannot go stale.

    ONE ENTRY TODAY: the browse filter over the interviews table says "Filter by artisan" while the
    capture form says "Artisans interviewed". That is a decision with a reason written out in the
    contract — the filter narrows a list of interviews that already exist and choosing somebody there
    asserts nothing about any record, whereas the capture form's label is a claim about the interview
    being written.

    Both halves are checked — the site must still exist, and it must still use this word — so a
    rename in either direction reports itself instead of leaving a sentence behind that is no longer
    about anything.
    """
    for entry in contract()["distinctLabels"]:
        path = _ROOT / entry["site"]
        assert path.exists(), (
            f"{CONTRACT_PATH.name} records a deliberately different label at {entry['site']}, and "
            "that file no longer exists. Either move the row, or delete it and say in the commit "
            "why the distinction stopped applying."
        )
        source = _strip_ts_comments(path.read_text(encoding="utf-8"))
        counterparts = [
            field["label"]
            for field in contract()["fields"]
            if field["control"] == entry["control"]
        ]
        assert f'"{entry["label"]}"' in source, (
            f"{entry['site']} no longer uses the label {entry['label']!r}, which "
            f"{CONTRACT_PATH.name} records as a DELIBERATE difference from the capture form's "
            f"{counterparts}.\n"
            "  If the two controls have been unified, delete the row — the reason it gives no "
            "longer holds and leaving it makes the table a worse description of the product than "
            "no table. If it was merely renamed, update the row and keep the argument."
        )


def test_the_contract_is_internally_consistent():
    """The declaration held to itself, because everything above trusts it completely.

    A contract with two fields of the same key, or a control kind nobody has ever drawn, or a
    duplicate label, is a contract that will make one of the assertions above report something
    confusing rather than something wrong. The vocabulary is closed on purpose: a typo like
    "multiselect" for "searchable-multiselect" would otherwise pass into the file and read, for ever
    after, as a control kind somebody chose.
    """
    spec = contract()
    kinds = {"text", "textarea", "select", "searchable-multiselect", "status", "richtext"}
    keys = [field["key"] for field in spec["fields"]]
    labels = [field["label"] for field in spec["fields"]]

    assert len(keys) == len(set(keys)), (
        f"{CONTRACT_PATH.name} declares the same key twice: "
        f"{sorted({key for key in keys if keys.count(key) > 1})}"
    )
    assert len(labels) == len(set(labels)), (
        f"{CONTRACT_PATH.name} declares the same label twice: "
        f"{sorted({label for label in labels if labels.count(label) > 1})}. Two boxes with one name "
        "on one form is a defect on the screen, not only in this file."
    )
    for field in spec["fields"]:
        assert field["control"] in kinds, (
            f"{CONTRACT_PATH.name} gives “{field['label']}” the control kind {field['control']!r}, "
            f"which is not one of {sorted(kinds)}. The vocabulary is closed: a new kind is a change "
            "both clients have to be able to draw, so add it here and say what draws it on each."
        )
        assert field["label"].strip() == field["label"] and field["label"], (
            f"{CONTRACT_PATH.name} declares the label {field['label']!r}, which is blank or padded. "
            "Labels are compared character for character between the two clients."
        )
        # THE JOIN THE CONTROL REGISTRY EXISTS FOR. A field names a control KIND and, separately, the
        # component each client draws it with; nothing but this assertion says those two agree.
        for client, key in (("web", "component"), ("android", "composable")):
            named = field[client][key]
            row = controls(client).get(named)
            assert row is not None, (
                f"{CONTRACT_PATH.name}'s “{field['label']}” says the {client} client draws it with "
                f"{named}, which is not in `controls.{client}`. Register it there with the kinds it "
                "draws and how it names itself, or the scanner cannot find this field at all."
            )
            assert field["control"] in row["kinds"], (
                f"{CONTRACT_PATH.name} declares “{field['label']}” as a {field['control']!r} "
                f"control and says the {client} client draws it with {named}, which "
                f"`controls.{client}` registers as {row['kinds']}. The contract contradicts itself: "
                "one of the two was edited alone."
            )

    for row in spec["controls"]["web"]:
        if row["labelFrom"] == "componentDefault":
            assert row.get("defaults"), (
                f"{CONTRACT_PATH.name} registers <{row['component']}> as labelling itself and gives "
                "no `defaults` to read the string out of. The scanner would report every field it "
                "draws as missing from the reference client."
            )

    # THE SAME HYGIENE ON THE OTHER CLIENT, and it is the one that bites hardest. The handset's
    # scanner is registry-driven — it looks at nothing the contract has not declared — so a
    # self-labelling composable with no `defaults` does not fail loudly, it draws a field that simply
    # does not appear in the scan, and the order assertion then blames whichever field follows it.
    for row in spec["controls"]["android"]:
        if row["labelFrom"] != "composableDefault":
            continue
        assert row.get("defaults"), (
            f"{CONTRACT_PATH.name} registers `{row['composable']}` as labelling itself and gives no "
            "`defaults` to find the string in. Name the file, the composable whose body holds the "
            "literal, and the literal — one entry per labelled box, in draw order."
        )
        for default in row["defaults"]:
            missing = [key for key in ("source", "symbol", "literal") if not default.get(key)]
            assert not missing, (
                f"{CONTRACT_PATH.name}: a `{row['composable']}` default is missing {missing}. All "
                "three are needed — the file to open, the composable on the hook, and the string a "
                "researcher reads on screen."
            )

    forbidden = {name for pin in spec["controlPins"] for name in pin["forbiddenAndroidComposables"]}
    for name in forbidden:
        row = controls("android").get(name)
        assert row is not None, (
            f"{CONTRACT_PATH.name} forbids {name} without registering it in `controls.android`. A "
            "forbidden widget that is not in the registry could be swapped in and reported as a "
            "MISSING FIELD rather than as the wall it is."
        )
        assert not (set(row["kinds"]) & kinds), (
            f"{CONTRACT_PATH.name} forbids {name} and registers it as drawing {row['kinds']}, which "
            "is a kind a field is allowed to declare. A forbidden control must not be reachable as a "
            "legitimate one — that is how it gets drawn back in under a contract that appears to "
            "permit it."
        )

    for extra in spec["alsoDrawn"]["android"] + spec["alsoDrawn"]["web"]:
        assert extra["label"] not in labels, (
            f"{CONTRACT_PATH.name} lists {extra['label']!r} both as a contract field and in "
            "`alsoDrawn`. The second is the register of controls that are deliberately NOT fields "
            "of this form; a label in both is how a field gets quietly excused from every assertion "
            "above."
        )
        assert extra.get("why"), (
            f"{CONTRACT_PATH.name} excuses the control {extra['label']!r} from the field list with "
            "no reason given. Every row in `alsoDrawn` has to say why it is not a field — that is "
            "the whole cost of the register, and it is meant to be paid when the control is added."
        )

    for row in spec["openDrifts"]["rows"]:
        assert row.get("edit") and row.get("found") and row.get("stillTrue"), (
            f"the `openDrifts` row {row.get('id')!r} is missing one of `stillTrue`, `found` or "
            "`edit`. A known-open problem with no measurement behind it cannot be asserted, and one "
            "with no edit beside it is a complaint rather than a handoff."
        )
