from __future__ import annotations

import json
import pathlib
import re

import pytest

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

WEB_FORM_ANCHOR = re.compile(r"<form\b[^>]*onSubmit=\{submit\}")


def _web_form_text() -> str:
    import pytest

    text = WEB_FORM.read_text(encoding="utf-8") if WEB_FORM.is_file() else ""
    if not text or ("<SessionsList" in text and not WEB_FORM_ANCHOR.search(text)):
        pytest.skip("the web's global interview form is retired (UNIFY 4.4); the shared questionnaire replaces it")
    return text
ANDROID_FORM_ANCHOR = "private fun QuestionnaireForm("
ANDROID_START_SHEET = ANDROID_MAIN.parent / "questionnaire" / "ui" / "SessionStartSheet.kt"


def contract() -> dict:
    assert CONTRACT_PATH.exists(), (
        f"{CONTRACT_PATH} is missing. It is the contract every assertion in this file is about; "
        "without it there is nothing to measure either client against, and deleting it is not a way "
        "to make a drift pass."
    )
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def contract_labels() -> list[str]:
    return [field["label"] for field in contract()["fields"]]


def controls(client: str) -> dict[str, dict]:
    key = "component" if client == "web" else "composable"
    return {row[key]: row for row in contract()["controls"][client]}



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
    return source.count("\n", 0, offset) + 1



def _web_form_region() -> tuple[str, int]:
    source = _strip_ts_comments(_web_form_text())
    match = WEB_FORM_ANCHOR.search(source)
    assert match is not None, (
        f"{WEB_FORM_ANCHOR.pattern!r} matches nothing in {WEB_FORM.name}. That pattern is how this "
        "file finds the capture form at all; if the form has been rewritten or moved, this parser "
        "moves with it — do not delete this test to make a refactor pass."
    )
    at = match.start()
    end = source.find("</form>", at)
    assert end > at, (
        f"{WEB_FORM.name} has no </form> after {WEB_FORM_ANCHOR.pattern!r}. Without a closing tag "
        "this parser would read the rest of the page, including the questionnaire builder's own "
        "fields."
    )
    return source[at:end], at


def _component_default_label(default: dict) -> str:
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
    region, base = _web_form_region()
    stripped = _strip_ts_comments(_web_form_text())

    marks: list[tuple[int, int, str]] = []
    for match in re.finditer(r'\blabel="([^"]*)"', region):
        marks.append((match.start(), 0, match.group(1)))
    for component, row in controls("web").items():
        if row["labelFrom"] != "componentDefault":
            continue
        for match in re.finditer(rf"<{component}\b", region):
            tag = region[match.start() : region.find(">", match.start()) + 1]
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
    return [(label, _line_of(stripped, base + offset)) for offset, _, label in marks]


def web_control_for(field: dict) -> tuple[str, int] | None:
    region, base = _web_form_region()
    stripped = _strip_ts_comments(_web_form_text())

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



def _android_form_region() -> tuple[str, int]:
    source = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    at = source.find(ANDROID_FORM_ANCHOR)
    if at < 0 and ANDROID_START_SHEET.is_file():
        pytest.skip("the global interview form is retired on Android (UNIFY 4.4); sessions start on the shared start sheet")
    assert at >= 0, (
        f"{ANDROID_FORM_ANCHOR!r} is no longer in {ANDROID_MAIN.name}. That is how this file finds "
        "the handset's capture form; if it has been renamed or moved out of MainActivity.kt, this "
        "parser moves with it."
    )
    body_at = source.index("{", source.index(")", at))
    return _balanced(source, body_at, "{", "}"), body_at


def _kotlin_declaration(path: pathlib.Path, symbol: str) -> str:
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
    return [(label, line) for _, label, line in android_form_controls()]


def android_control_for(field: dict) -> tuple[str, int] | None:
    for composable, label, line in android_form_controls():
        if label == field["label"]:
            return composable, line
    return None



def _report(
    client: str, path: pathlib.Path, expected: list[str], actual: list[tuple[str, int]]
) -> str:
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



def test_the_contract_and_both_forms_still_parse_to_something():
    spec = contract()
    assert len(spec["fields"]) >= 6, (
        f"{CONTRACT_PATH.name} declares only {len(spec['fields'])} fields. This form has never had "
        "fewer than six; a contract this short is a contract that was emptied rather than edited."
    )

    web = web_form_labels()
    assert len(web) >= 6, (
        f"only {len(web)} labelled controls parsed out of {WEB_FORM.name}'s capture form. The "
        f"anchor is {WEB_FORM_ANCHOR.pattern!r} and the labels are read as `label=\"…\"` plus the components "
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



def test_the_web_form_draws_exactly_the_contract_fields_in_order():
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



def test_the_artisan_control_is_one_searchable_multiselect_and_never_two():
    spec = contract()
    fields = {field["key"]: field for field in spec["fields"]}
    region, base = _web_form_region()
    stripped = _strip_ts_comments(_web_form_text())
    raw = _web_form_text()

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



def test_the_artisan_roster_is_fetched_with_a_workshop_scope():
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

    page = _strip_ts_comments(_web_form_text())
    hit = re.search(r'["\']/artisans["\']', page)
    assert hit is None, (
        f"{WEB_FORM.name}:{_line_of(page, hit.start())} fetches `/artisans` directly.\n"
        f"  Every artisan this screen offers comes through `useWorkshopArtisans` in {spec['rules']}, "
        "which is keyed on the workshop. A second call beside it is the original defect: one "
        "unscoped page of the whole table, offered under whichever workshop the boxes above happen "
        "to show."
    )


def _kotlin_function_source(source: str, symbol: str) -> str | None:
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
    path = _ROOT / relative
    assert path.exists(), (
        f"{CONTRACT_PATH.name} reads the handset's artisan scope out of {relative}, which does not "
        "exist. If the file moved, move the contract with it in the same commit."
    )
    return _strip_kotlin_comments(path.read_text(encoding="utf-8"))


def test_the_handset_sends_the_workshop_scope_on_its_artisan_read():
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
    spec = contract()["interviewListScope"]
    if (_ROOT / spec["file"]) == WEB_FORM:
        _web_form_text()
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
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def _names(haystack: str, label: str) -> bool:
    return f" {_loose(label)} " in f" {_loose(haystack)} "


def _web_register_fields() -> list[str]:
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
    return {
        "web walkthrough": (WEB_REGISTER, " · ".join(_web_register_fields())),
        "android walkthrough": (ANDROID_REGISTER, " · ".join(_android_register_fields())),
        "printed guide": (PRINTED_GUIDE, _printed_guide_sentence()),
    }


def test_all_three_registers_still_parse_to_something():
    for name, (path, text) in _registers().items():
        assert len(text) > 30, (
            f"the {name} register ({path.name}) parsed to {text!r}, which is too short to be a "
            "description of this form's boxes. The parser is matching the wrong thing, and until it "
            "is fixed the ceiling assertion below passes over anything."
        )


def test_no_register_names_a_field_this_form_deliberately_does_not_have():
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
    expected = contract_labels()
    for name, (path, text) in _registers().items():
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



def test_every_open_drift_is_still_open():
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
            spelling = still["androidFormSendsNoWorkshopScope"]
            at = android_region.find(spelling)
            assert at < 0, (
                f"the handset's capture form now mentions `{spelling}`, so it is asking for a "
                "workshop-scoped artisan roster." + closed
            )



def test_every_deliberately_different_label_still_exists_and_still_says_it():
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


def test_the_handset_retired_the_global_form_for_the_start_sheet():
    source = _strip_kotlin_comments(ANDROID_MAIN.read_text(encoding="utf-8"))
    if ANDROID_FORM_ANCHOR in source:
        pytest.skip("the global interview form is still on this handset")
    assert "fun QuestionnaireBuilder(" not in source, "the global form's builder outlived the form"
    sheet = ANDROID_START_SHEET.read_text(encoding="utf-8")
    keys = ["start.workshop", "start.instrument", "start.date", "start.mode", "start.artisans",
            "start.purpose", "start.place", "start.language", "start.note"]
    at = [sheet.find(f'QuestionnaireCopy.s("{key}")') for key in keys]
    assert all(i >= 0 for i in at), dict(zip(keys, at))
    assert at == sorted(at), f"the start sheet draws its fields out of the design's order: {dict(zip(keys, at))}"
    assert "title =" not in sheet.split("fun SessionStartSheet(", 1)[1].split("ModalBottomSheet", 1)[0]
