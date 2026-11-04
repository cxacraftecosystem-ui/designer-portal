import re
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]

_KOTLIN = _ROOT / "android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCodes.kt"
_WEB = _ROOT / "frontend/lib/workshopCodes.ts"
_SERVER = _ROOT / "backend/app/services/design_workshop_access.py"


def _source(path: Path) -> str:
    assert path.is_file(), (
        f"{path.relative_to(_ROOT).as_posix()} is not in this checkout. If it was renamed, repoint "
        "the constant in tests/test_workshop_code_letters.py — do NOT make this test skip itself, "
        "which is how a pin comes to report success for ever after it stops reading anything."
    )
    return path.read_text(encoding="utf-8")



def _without_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("//"))


def _braced_block(source: str, declaration: str) -> str:
    start = source.index(declaration)
    open_at = source.index("{", start)
    depth = 0
    for index in range(open_at, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[open_at + 1 : index]
    raise AssertionError(f"{declaration} is not brace-balanced")


def _web_table(name: str) -> dict[str, str]:
    body = _without_comments(_braced_block(_source(_WEB), f"const {name}"))
    pairs = re.findall(r'([A-Za-z_$][\w$]*)\s*:\s*"([^"]*)"', body)
    assert len(pairs) >= 9, f"{name} in workshopCodes.ts parsed to {len(pairs)} entries"
    table = dict(pairs)
    assert len(table) == len(pairs), f"{name} declares a key twice"
    return table


def _kotlin_enum() -> dict[str, tuple[str, str, str]]:
    source = _source(_KOTLIN)
    start = source.index("enum class DwWorkshopRecordType")
    end = source.index("companion object", start)
    body = _without_comments(source[start:end])
    entries = re.findall(
        r'^\s*([A-Z][A-Z0-9_]*)\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)',
        body,
        flags=re.MULTILINE,
    )
    assert len(entries) >= 9, f"DwWorkshopRecordType parsed to {len(entries)} entries"
    table = {wire: (letter, label, plural) for _name, wire, letter, label, plural in entries}
    assert len(table) == len(entries), "DwWorkshopRecordType declares a wire name twice"
    return table



def test_the_two_clients_agree_about_every_letter_in_both_directions() -> None:
    web = _web_table("TYPE_LETTER")
    kotlin = _kotlin_enum()

    assert web.get("artisan") == "A", "TYPE_LETTER is not the table this test thinks it is"
    assert kotlin.get("artisan", (None,))[0] == "A", "the Kotlin enum is not the one this test thinks it is"

    missing_on_handset = sorted(set(web) - set(kotlin))
    missing_in_browser = sorted(set(kotlin) - set(web))
    assert not missing_on_handset, (
        f"{missing_on_handset} have a letter in frontend/lib/workshopCodes.ts and no entry in "
        "DwWorkshopRecordType — a card printed in the browser cannot be scanned on a handset"
    )
    assert not missing_in_browser, (
        f"{missing_in_browser} are in DwWorkshopRecordType and absent from TYPE_LETTER in "
        "frontend/lib/workshopCodes.ts — the handset prints a tag the browser cannot read"
    )

    disagreements = {
        wire: (web[wire], kotlin[wire][0]) for wire in web if web[wire] != kotlin[wire][0]
    }
    assert not disagreements, (
        f"the two clients disagree about {disagreements} (browser, handset) — a letter that means "
        "two things is a code that opens the wrong record"
    )

    assert list(web) == list(kotlin), (
        "the two tables are declared in different orders, so the refusal sentence reads out "
        f"differently: browser {list(web)} vs handset {list(kotlin)}"
    )


_SHARED_SENTENCES = (
    "Not uploaded yet — sync, then print its code.",
    "This code was made before the workshop uploaded.",
    "No workshop you can open matches this code.",
)


def test_the_two_clients_say_the_same_sentence_about_a_design_workshop_code() -> None:
    web = _without_comments(_source(_WEB))
    kotlin = re.sub(r'"\s*\+\s*\n?\s*"', "", _without_comments(_source(_KOTLIN)))
    literal = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')

    for opening in _SHARED_SENTENCES:
        found = [text for text in literal.findall(web) if text.startswith(opening)]
        assert len(found) == 1, (
            f"{opening[:48]!r}... appears {len(found)} times in frontend/lib/workshopCodes.ts; the "
            "web side of this pin no longer says what it did"
        )
        assert found[0] in kotlin, (
            f"the handset does not say what the browser says. Browser: {found[0]!r}. Search "
            "DwWorkshopCodes.kt for the same opening clause to see how the two have drifted."
        )


def test_the_two_clients_use_the_same_words_for_a_record_type() -> None:
    labels = _web_table("TYPE_LABEL")
    plurals = _web_table("TYPE_PLURAL")
    kotlin = _kotlin_enum()

    assert labels.get("artisan") == "Artisan", "TYPE_LABEL is not the table this test thinks it is"
    assert plurals.get("artisan") == "artisans", "TYPE_PLURAL is not the table this test thinks it is"
    shared = sorted(set(kotlin) & set(labels) & set(plurals))
    assert len(shared) >= 9, f"only {len(shared)} record types are common to both clients"

    wrong_labels = {w: (labels[w], kotlin[w][1]) for w in shared if labels[w] != kotlin[w][1]}
    wrong_plurals = {w: (plurals[w], kotlin[w][2]) for w in shared if plurals[w] != kotlin[w][2]}
    assert not wrong_labels, f"label disagreement (browser, handset): {wrong_labels}"
    assert not wrong_plurals, f"plural disagreement (browser, handset): {wrong_plurals}"



def _named_string(source: str, pattern: str, name: str) -> str:
    found = re.findall(
        pattern.format(name=re.escape(name)), _without_comments(source), flags=re.MULTILINE
    )
    assert len(found) == 1, f"{name} is declared {len(found)} times where one was expected"
    return found[0]


_TS_STRING = r'(?:export\s+)?const\s+{name}\s*=\s*"([^"]*)"'
_KT_STRING = r'(?:private\s+)?(?:const\s+)?val\s+{name}\s*(?::\s*String\s*)?=\s*"([^"]*)"'
_PY_STRING = r'^{name}\s*=\s*"([^"]*)"'


def test_all_three_ports_of_the_grammar_agree_about_the_constants_they_share() -> None:
    web = _source(_WEB)
    kotlin = _source(_KOTLIN)
    server = _source(_SERVER)

    web_letter = _web_table("TYPE_LETTER")["designWorkshop"]
    kotlin_letter = _kotlin_enum()["designWorkshop"][0]
    server_letter = _named_string(server, _PY_STRING, "_DESIGN_WORKSHOP_LETTER")
    assert web_letter == kotlin_letter == server_letter == "G", (
        f"the design-workshop letter is {web_letter!r} in the browser, {kotlin_letter!r} on the "
        f"handset and {server_letter!r} on the server — a join request scanned off a card is decoded "
        "by the server, so a letter that differs there refuses every real scan as malformed"
    )

    for concept, web_name, kotlin_name, server_name in (
        ("the namespace", "WORKSHOP_CODE_NAMESPACE", "WORKSHOP_CODE_NAMESPACE", "_CODE_NAMESPACE"),
        ("the check alphabet", "CHECK_ALPHABET", "CHECK_ALPHABET", "_CHECK_ALPHABET"),
    ):
        values = (
            _named_string(web, _TS_STRING, web_name),
            _named_string(kotlin, _KT_STRING, kotlin_name),
            _named_string(server, _PY_STRING, server_name),
        )
        assert len(set(values)) == 1, f"{concept} differs across the three ports: {values}"

    patterns = (
        _named_string(web, r"const\s+{name}\s*=\s*/([^/]*)/", "ID_PATTERN"),
        _named_string(kotlin, r'(?:private\s+)?val\s+{name}\s*=\s*Regex\("([^"]*)"\)', "ID_PATTERN"),
        _named_string(server, r'^{name}\s*=\s*re\.compile\(r"([^"]*)"\)', "_ID_PATTERN"),
    )
    assert patterns[0].startswith("^[a-z0-9]"), "ID_PATTERN is not the pattern this test thinks it is"
    assert len(set(patterns)) == 1, f"the three ports admit different id shapes: {patterns}"

    lengths = tuple(
        _named_string(text, pattern.replace('"([^"]*)"', r"(\d+)"), name)
        for text, pattern, name in (
            (web, _TS_STRING, "CHECK_LENGTH"),
            (kotlin, _KT_STRING, "CHECK_LENGTH"),
            (server, _PY_STRING, "_CHECK_LENGTH"),
        )
    )
    assert set(lengths) == {"4"}, f"the three ports disagree about the check length: {lengths}"

    server_prefixes = re.findall(
        r'_DEVICE_LOCAL_ID_PREFIXES\s*=\s*\(([^)]*)\)', _without_comments(server)
    )
    assert len(server_prefixes) == 1, "_DEVICE_LOCAL_ID_PREFIXES is not declared once"
    assert set(re.findall(r'"([^"]*)"', server_prefixes[0])) == {
        _named_string(web, _TS_STRING, "DEVICE_LOCAL_ID_PREFIX"),
        _named_string(web, _TS_STRING, "ANDROID_DEVICE_LOCAL_ID_PREFIX"),
    }, (
        "the server refuses a different set of device-local prefixes than the browser mints: a "
        "prefix missing there lets a code for a one-device workshop be accepted as a join request"
    )
