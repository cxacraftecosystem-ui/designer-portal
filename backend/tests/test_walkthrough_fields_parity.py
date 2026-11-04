from __future__ import annotations

import pathlib
import re

_ROOT = pathlib.Path(__file__).resolve().parents[2]
WEB_STEPS = _ROOT / "frontend" / "components" / "guide" / "steps.ts"
WEB_INSPECTOR_STEPS = _ROOT / "frontend" / "components" / "guide" / "inspectorSteps.ts"
ANDROID_STEPS = (
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
    / "WalkthroughSteps.kt"
)
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

WEB_ANCHOR = "export const GUIDE_STEPS"
ANDROID_ANCHOR = "private val WALKTHROUGH_FIELDS"

WEB_INSPECTOR_ANCHOR = "export const INSPECTOR_STEPS"
ANDROID_INSPECTOR_ANCHOR = "private val WALKTHROUGH_INSPECTOR_FIELDS"
ANDROID_OMISSIONS_ANCHOR = "internal val walkthroughInspectorOmissions"



def _strip_ts_comments(source: str) -> str:
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



def web_fields() -> list[tuple[str, list[str]]]:
    return _web_fields_from(WEB_STEPS, WEB_ANCHOR)


def _web_fields_from(path: pathlib.Path, anchor: str) -> list[tuple[str, list[str]]]:
    source = _strip_ts_comments(path.read_text(encoding="utf-8"))
    at = source.find(anchor)
    assert at >= 0, f"{anchor} is no longer declared in {path.name}"
    body = source[at:]

    marks = [(m.group(1), m.start()) for m in re.finditer(r'\bid:\s*"([^"]+)"', body)]
    out: list[tuple[str, list[str]]] = []
    for index, (step_id, pos) in enumerate(marks):
        end = marks[index + 1][1] if index + 1 < len(marks) else len(body)
        chunk = body[pos:end]
        opener = re.search(r"\bfields:\s*\[", chunk)
        assert opener, (
            f"the web's “{step_id}” card no longer declares a fields[] array. Every card has "
            "one — see the GuideStep type — so this is a refactor of the guide and this parser has "
            "to learn about it."
        )
        out.append((step_id, _literals(_balanced(chunk, opener.end() - 1, "[", "]"))))
    return out


def _android_fields_from(path: pathlib.Path, anchor: str) -> list[tuple[str, list[str]]]:
    source = path.read_text(encoding="utf-8")
    at = source.find(anchor)
    assert at >= 0, (
        f"{anchor} is no longer declared in {path.name}. If the handset's copy of the web's field "
        "lists has moved, this file has to move with it — do not delete this test to make a rename "
        "pass."
    )
    opener = source.index("mapOf(", at) + len("mapOf(") - 1
    table = _balanced(source, opener, "(", ")")

    out: list[tuple[str, list[str]]] = []
    for entry in re.finditer(r'"([a-z0-9-]+)"\s*to\s*listOf\(', table):
        block = _balanced(table, entry.end() - 1, "(", ")")
        out.append((entry.group(1), _literals(block)))
    return out


def web_inspector_fields() -> list[tuple[str, list[str]]]:
    return _web_fields_from(WEB_INSPECTOR_STEPS, WEB_INSPECTOR_ANCHOR)


def android_inspector_fields() -> list[tuple[str, list[str]]]:
    return _android_fields_from(ANDROID_JOURNEY, ANDROID_INSPECTOR_ANCHOR)


def android_inspector_omissions() -> set[str]:
    source = ANDROID_STEPS.read_text(encoding="utf-8")
    at = source.find(ANDROID_OMISSIONS_ANCHOR)
    assert at >= 0, (
        f"{ANDROID_OMISSIONS_ANCHOR} is no longer declared in {ANDROID_STEPS.name}. It is the one "
        "register of which web inspector cards this handset leaves out; if it has moved, this file "
        "moves with it."
    )
    declaration = source[at : source.index("\n", at)]
    if "emptySet()" in declaration:
        return set()
    assert "setOf(" in declaration, (
        f"{ANDROID_OMISSIONS_ANCHOR} is neither `emptySet()` nor `setOf(` on its own line, so this "
        "parser cannot read it"
    )
    opener = source.index("setOf(", at) + len("setOf(") - 1
    block = _balanced(source, opener, "(", ")")
    kept = [line for line in block.split("\n") if not line.lstrip().startswith("//")]
    return set(_literals("\n".join(kept)))


def every_declared_field_list() -> list[tuple[str, list[str]]]:
    return (
        web_fields() + android_fields() + web_inspector_fields() + android_inspector_fields()
    )


def android_fields() -> list[tuple[str, list[str]]]:
    return _android_fields_from(ANDROID_JOURNEY, ANDROID_ANCHOR)



def test_both_declarations_still_parse_to_something():
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

    web_inspector = web_inspector_fields()
    android_inspector = android_inspector_fields()
    assert len(web_inspector) >= 3, (
        f"only {len(web_inspector)} steps parsed out of {WEB_INSPECTOR_STEPS.name}"
    )
    assert len(android_inspector) >= 2, (
        f"only {len(android_inspector)} inspector entries parsed out of {ANDROID_JOURNEY.name}"
    )
    assert all(fields for _, fields in web_inspector), (
        "a web inspector card parsed to an EMPTY fields[]: "
        f"{[i for i, f in web_inspector if not f]}"
    )
    assert all(fields for _, fields in android_inspector), (
        "a handset inspector entry parsed to an EMPTY listOf(): "
        f"{[i for i, f in android_inspector if not f]}"
    )
    source = ANDROID_STEPS.read_text(encoding="utf-8")
    at = source.find(ANDROID_OMISSIONS_ANCHOR)
    declaration = source[at : source.index("\n", at)]
    assert "emptySet()" in declaration or android_inspector_omissions(), (
        "the handset's omission register is a `setOf(…)` that parsed to nothing, so the inspector "
        "join below has stopped asking whether a card was dropped"
    )


def test_neither_declaration_uses_an_escape_this_parser_invents():
    for step_id, fields in every_declared_field_list():
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


def test_the_handset_carries_the_inspector_cards_it_says_it_carries():
    web = [step_id for step_id, _ in web_inspector_fields()]
    android = [step_id for step_id, _ in android_inspector_fields()]
    omitted = android_inspector_omissions()
    assert android == [step_id for step_id in web if step_id not in omitted], (
        f"the web's inspector deck declares fields for {web},\n"
        f"the handset declares them for {android},\n"
        f"and `walkthroughInspectorOmissions` says the handset deliberately omits {sorted(omitted)}.\n"
        "Those three do not add up. The web is the register: teach the step there and copy the "
        "field list here in one commit, or record the omission where the deck is declared — never "
        "edit one of the three alone to turn this green."
    )
    assert not (omitted - set(web)), (
        f"{sorted(omitted - set(web))} is registered as an omission and the web's inspector deck "
        "does not teach it at all, so it is a typo rather than a decision"
    )


def test_every_inspector_field_matches_the_web_word_for_word_and_in_screen_order():
    web = dict(web_inspector_fields())
    android = dict(android_inspector_fields())
    drifted = {
        step_id: {"web": fields, "android": android[step_id]}
        for step_id, fields in web.items()
        if step_id in android and android[step_id] != fields
    }
    assert drifted == {}, "the two copies of the inspector field lists have drifted:\n" + "\n".join(
        _report(step_id, pair["web"], pair["android"]) for step_id, pair in drifted.items()
    ) + (
        "\nThe web is the register: fix inspectorSteps.ts and WALKTHROUGH_INSPECTOR_FIELDS together, "
        "in one commit. Never edit the Kotlin alone to turn this green — that makes the two "
        "walkthroughs describe two different products with one test saying they agree."
    )


def test_every_field_matches_the_web_word_for_word_and_in_screen_order():
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
    for step_id, fields in every_declared_field_list():
        assert all(field.strip() for field in fields), (
            f"“{step_id}” declares a blank field, which draws an empty chip: {fields!r}"
        )
        assert len(fields) == len(set(fields)), (
            f"“{step_id}” lists the same field twice: "
            f"{sorted({f for f in fields if fields.count(f) > 1})}"
        )


WALKTHROUGH_DOC = _ROOT / "docs" / "WALKTHROUGH.md"

PROSE_MARKER = "**What the screen asks for:**"

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
    return " ".join(text.split())


def _loose(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def prose_fields() -> dict[str, str]:
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
