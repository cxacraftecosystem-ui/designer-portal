"""The one letter table three trees hold, read off all three of them and compared.

``frontend/lib/workshopCodes.ts`` and ``android/.../data/DwWorkshopCodes.kt`` each declare, by hand,
which single letter a QR payload uses for which kind of record. The letter is stamped on cards and
tags that outlive the build that printed them, and both files say the same thing in their headers:
a retired letter is NEVER reused and a live one is NEVER moved, because handing ``A`` to something
that is not an artisan makes every card already in a workshop resolve, confidently, to the wrong
kind of record.

WHY THIS TEST EXISTS, AND WHY IT IS HERE RATHER THAN ON EITHER CLIENT. Both clients already had a
letter test — ``DwWorkshopCodesTest`` on the handset, ``e2e/workshop-codes.spec.ts`` in the browser —
and both hold their expected letters AS A HAND-WRITTEN LITERAL beside the table they are checking.
Each therefore only ever agrees with itself. That is not a hypothetical weakness: the browser added
``designWorkshop`` / ``G`` and the handset did not, the two surfaces disagreed about what the
grammar contained for as long as it took somebody to notice, and BOTH suites stayed green the whole
time. A test that cannot see the other file cannot catch the only failure that matters here.

So this reads THE FILES AS TEXT and compares what they say. Reading the source rather than running it
is the same blunt instrument ``test_report_parity.py`` uses on the Kotlin report writers, chosen for
the same reason: running the two clients would need a JVM and a browser in one process, and the
failure being defended against is not a logic bug — it is somebody adding a row to one table and not
the other. Reading the declarations catches exactly that, in the place it happens, naming the file it
disagrees with.

THE PYTHON COPY IS READ AS TEXT TOO, AND THAT ONE IS A CHOICE, because it could have been imported.
Importing it would be stronger — an actual value cannot be misparsed — and it costs too much to be
worth it here: ``app.services.design_workshop_access`` pulls in the FastAPI app graph and the Prisma
client, and importing just the eight constants off it was timed at 7m59s on this machine. A module
that takes eight minutes to answer a question about a string literal is a module that gets excluded
from the run somebody actually waits for, and a pin nobody runs is not a pin. Text keeps it at well
under a second, and the anti-rot assertions below are what stand in for the parser being right.

AND THE BACKEND IS NOT A BYSTANDER — IT HOLDS THE THIRD COPY. An earlier version of this docstring
said "the backend owns no letter of its own", which was true when it was typed and stopped being true
hours later: ``app/services/design_workshop_access.py`` declares ``_DESIGN_WORKSHOP_LETTER = "G"``
along with its own namespace, check alphabet, id pattern and device-local prefixes — a narrow port of
the same grammar, and the one that decides whether a scanned join request is accepted. Its own header
asks for "a test that pins it against the TypeScript one". So there are THREE hand-kept copies, this
compares all three, and the reason the pin lives on the backend is not neutrality but reach: this is
the only one of the three trees whose test runner can open the other two.

NOT SKIPPED WHEN A FILE IS MISSING — FAILED. A source-reading pin that turns green the moment it
stops finding its subject is the failure mode every anti-rot assertion below exists against, and a
``skipif`` on exact paths is that failure mode wearing a green tick: rename either client file and
the pin reports success for ever. Nothing here runs anywhere but a full checkout (CI does a plain
``actions/checkout``), so the skip bought nothing and cost the one property that matters.
"""

import re
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]

_KOTLIN = _ROOT / "android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCodes.kt"
_WEB = _ROOT / "frontend/lib/workshopCodes.ts"
_SERVER = _ROOT / "backend/app/services/design_workshop_access.py"


def _source(path: Path) -> str:
    """One of the three files, read, with a missing file reported as a FAILURE and not a skip.

    See the header for why. The message names a MOVE rather than an absence, because a file gone
    from a full checkout has almost certainly been renamed, and the fix is to repoint the constant
    above rather than to let this module quietly stop comparing anything.
    """
    assert path.is_file(), (
        f"{path.relative_to(_ROOT).as_posix()} is not in this checkout. If it was renamed, repoint "
        "the constant in tests/test_workshop_code_letters.py — do NOT make this test skip itself, "
        "which is how a pin comes to report success for ever after it stops reading anything."
    )
    return path.read_text(encoding="utf-8")


# ──────────────────────────────────────────────────────────────────────────────────────────
# Reading the declarations
# ──────────────────────────────────────────────────────────────────────────────────────────


def _without_comments(source: str) -> str:
    """Block comments and whole-line ``//`` comments removed.

    Whole-line only, deliberately: a trailing ``//`` cannot be told from one inside a string
    literal without a real lexer, and both regions this is applied to are tables of short string
    literals where a stray ``//`` in a value would be far likelier than a trailing comment. Every
    comment either table actually carries sits on its own line.
    """
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("//"))


def _braced_block(source: str, declaration: str) -> str:
    """The ``{ … }`` that follows ``declaration``, brace-matched.

    Brace-matched rather than sliced to the next ``};`` so that a nested object or a doc comment
    containing a brace cannot end the block early and quietly hide half the table.
    """
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
    """One ``Record<WorkshopRecordType, string>`` in the web module, in declaration order."""
    body = _without_comments(_braced_block(_source(_WEB), f"const {name}"))
    pairs = re.findall(r'([A-Za-z_$][\w$]*)\s*:\s*"([^"]*)"', body)
    # A PARSER THAT FOUND NOTHING WOULD MAKE EVERY ASSERTION BELOW VACUOUSLY TRUE, which is the way
    # a source-reading test rots: somebody reformats the table, the regex stops matching, and the
    # pin reports success for ever. The floor is the nine types that existed before design workshops.
    assert len(pairs) >= 9, f"{name} in workshopCodes.ts parsed to {len(pairs)} entries"
    table = dict(pairs)
    assert len(table) == len(pairs), f"{name} declares a key twice"
    return table


def _kotlin_enum() -> dict[str, tuple[str, str, str]]:
    """``DwWorkshopRecordType``'s entries as ``wire -> (letter, label, plural)``, in order."""
    source = _source(_KOTLIN)
    start = source.index("enum class DwWorkshopRecordType")
    # The entries end at the companion object; everything past it is lookup functions whose bodies
    # must not be mistaken for declarations.
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


# ──────────────────────────────────────────────────────────────────────────────────────────
# The pin
# ──────────────────────────────────────────────────────────────────────────────────────────


def test_the_two_clients_agree_about_every_letter_in_both_directions() -> None:
    """No letter may mean one thing on the handset and another in the browser, or exist on one only.

    THE FAILURE THIS PREVENTS is a code that scans cleanly and opens the WRONG record, with the
    check digit agreeing all the way — and its quieter sibling, a code one client can print that the
    other cannot read, which is the same card being useless in the hands of the colleague it was
    printed for.

    BOTH DIRECTIONS, separately reported, because the two cost different things. A type in the
    browser and not on the handset is the ``designWorkshop`` case: the handset refuses honestly (an
    unknown letter is answered "a kind of record this version of the app does not open"), so nothing
    opens wrongly, but a designer holding a printed card simply cannot use it. A type on the handset
    and not in the browser is worse: the handset PRINTS a card the browser will not read, so the
    tag is dead on the surface most likely to be used at a desk.
    """
    web = _web_table("TYPE_LETTER")
    kotlin = _kotlin_enum()

    # The anchor. If both parsers drifted onto the wrong region of their file, everything below
    # would compare two empty-ish tables and agree; this is the one hand-held fact that says the
    # tables being compared are the tables in question.
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

    # ORDER, not only content. Both clients build the "codes exist for…" refusal by reading their
    # own table top to bottom, so a type inserted in a different position on each surface leaves two
    # clients explaining one refusal in two different orders — which is user-visible, and is exactly
    # the state the design-workshop letter left the two suites in while both stayed green.
    assert list(web) == list(kotlin), (
        "the two tables are declared in different orders, so the refusal sentence reads out "
        f"differently: browser {list(web)} vs handset {list(kotlin)}"
    )


#: The sentences a design workshop's code produces that exist on BOTH clients word for word.
#:
#: They are duplicated rather than shared because there is nothing to share them through — one is
#: TypeScript in a browser bundle, the other Kotlin on a handset — and each is the answer a designer
#: reads when a scan does not go through. Two clients explaining one refusal two ways is a
#: user-visible disagreement on a feature whose whole promise is that a card means the same thing
#: wherever it is scanned, so the duplication is pinned rather than trusted. Matched by their
#: opening clause and compared whole, so a sentence reworded on one side alone fails here.
_SHARED_SENTENCES = (
    "This workshop exists only on this device, so there is nothing for anybody else to scan yet",
    "That code names a workshop that had not been shared yet when it was written down",
    "No design workshop you can open matches that code. If a colleague has just shared it with you",
)


def test_the_two_clients_say_the_same_sentence_about_a_design_workshop_code() -> None:
    """The refusals a `G` code can produce are one text, held twice.

    The Kotlin splits a long literal across concatenated lines where the TypeScript does not, which
    is a difference in source formatting and not in what a designer reads, so the concatenations are
    joined before comparing — the same normalisation ``test_report_parity.py`` applies to the Kotlin
    report writers, for the same reason.
    """
    # COMMENTS STRIPPED FROM BOTH SIDES. A sentence retained in a comment after being removed from
    # the code would otherwise satisfy the substring search below, which is the same "still there,
    # no longer said" failure this whole module is written against. In practice a comment wraps at
    # ninety-odd characters and cannot hold one of these contiguously — but that is a property of
    # today's formatting and not an argument.
    web = _without_comments(_source(_WEB))
    # `"a " +\n    "b"` is one sentence; join the halves.
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
    """The label and the plural are read side by side in one refusal, so they are one vocabulary.

    ``TYPE_LABEL`` in the browser is commented "Android's ``workshopRecordTypeLabel``, verbatim", and
    both clients assemble "codes exist for artisans, crafts, …" out of their own plurals. A word
    changed on one side alone does not open the wrong record, so this is the softer of the two
    pins — but it is the one that catches a surface being told about a "Workshop" when the other
    surface would have said "Design workshop", and those are two different tables here.
    """
    labels = _web_table("TYPE_LABEL")
    plurals = _web_table("TYPE_PLURAL")
    kotlin = _kotlin_enum()

    assert labels.get("artisan") == "Artisan", "TYPE_LABEL is not the table this test thinks it is"
    assert plurals.get("artisan") == "artisans", "TYPE_PLURAL is not the table this test thinks it is"
    # The letter test above owns the membership failure and names it far better than a KeyError
    # would; here the shared keys are what is compared, so a missing type fails once, not three times.
    shared = sorted(set(kotlin) & set(labels) & set(plurals))
    assert len(shared) >= 9, f"only {len(shared)} record types are common to both clients"

    wrong_labels = {w: (labels[w], kotlin[w][1]) for w in shared if labels[w] != kotlin[w][1]}
    wrong_plurals = {w: (plurals[w], kotlin[w][2]) for w in shared if plurals[w] != kotlin[w][2]}
    assert not wrong_labels, f"label disagreement (browser, handset): {wrong_labels}"
    assert not wrong_plurals, f"plural disagreement (browser, handset): {wrong_plurals}"


# ──────────────────────────────────────────────────────────────────────────────────────────
# The third copy: the server's own narrow port
# ──────────────────────────────────────────────────────────────────────────────────────────


def _named_string(source: str, pattern: str, name: str) -> str:
    """The single string literal ``name`` is declared with, by a per-language pattern.

    ``pattern`` is formatted with ``name`` and must capture the literal in group 1. Exactly one
    match is required in both directions: none means the declaration moved or was renamed and this
    comparison has quietly stopped happening, and two means the file declares it twice and there is
    no telling which one the code uses.
    """
    # MULTILINE, because the Python patterns anchor on ``^`` — which is what keeps a constant's NAME
    # written inside a ``#:`` comment from being read as its declaration.
    found = re.findall(
        pattern.format(name=re.escape(name)), _without_comments(source), flags=re.MULTILINE
    )
    assert len(found) == 1, f"{name} is declared {len(found)} times where one was expected"
    return found[0]


#: ``NAME = "value"`` in each of the three languages. Kotlin's ``const val``/``val`` and Python's bare
#: assignment differ only in the keywords; the web's ``export`` is optional on the constants compared
#: here, so it is not required.
_TS_STRING = r'(?:export\s+)?const\s+{name}\s*=\s*"([^"]*)"'
_KT_STRING = r'(?:private\s+)?(?:const\s+)?val\s+{name}\s*(?::\s*String\s*)?=\s*"([^"]*)"'
_PY_STRING = r'^{name}\s*=\s*"([^"]*)"'


def test_all_three_ports_of_the_grammar_agree_about_the_constants_they_share() -> None:
    """The server holds a third copy of this grammar, and it is the copy that admits people.

    WHY IT MATTERS MORE THAN A CLIENT-TO-CLIENT DISAGREEMENT. ``design_workshop_access.py`` decodes
    the code in a join request so that an admin reading the queue knows the request came from a real
    scanned card and not a guessed id. If its letter, namespace or id shape drifts from the clients',
    every genuine scan is refused as malformed and the feature reads as "the cards do not work" —
    with the queue empty and nothing anywhere saying why. Its own header asks for exactly this test.

    WHAT IS DELIBERATELY NOT COMPARED HERE. The check ALPHABET and LENGTH are, because they are plain
    literals; the confusable map and the supported-version set are not, because each is a container
    literal with three different syntaxes and a regex over three spellings of a map is a parser more
    likely to rot than the thing it guards. The arithmetic those two feed is pinned by value instead,
    against vectors printed by the browser, in ``tests/test_workshop_code_check_port.py``.
    """
    web = _source(_WEB)
    kotlin = _source(_KOTLIN)
    server = _source(_SERVER)

    # THE LETTER, all three ways. The web and the handset are compared as whole tables above; this is
    # the one entry the server also holds, and it holds it alone rather than in a table.
    web_letter = _web_table("TYPE_LETTER")["designWorkshop"]
    kotlin_letter = _kotlin_enum()["designWorkshop"][0]
    server_letter = _named_string(server, _PY_STRING, "_DESIGN_WORKSHOP_LETTER")
    assert web_letter == kotlin_letter == server_letter == "G", (
        f"the design-workshop letter is {web_letter!r} in the browser, {kotlin_letter!r} on the "
        f"handset and {server_letter!r} on the server — a join request scanned off a card is decoded "
        "by the server, so a letter that differs there refuses every real scan as malformed"
    )

    # THE NAMESPACE AND THE CHECK, which every payload carries before the letter is even reached.
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

    # THE ID SHAPE. Three spellings of one regex — a JavaScript literal, a Kotlin `Regex("…")` and a
    # Python `re.compile(r"…")` — and the pattern text inside them has to be the same string, because
    # a port that admits an id the others refuse accepts a card the others cannot have printed.
    patterns = (
        _named_string(web, r"const\s+{name}\s*=\s*/([^/]*)/", "ID_PATTERN"),
        _named_string(kotlin, r'(?:private\s+)?val\s+{name}\s*=\s*Regex\("([^"]*)"\)', "ID_PATTERN"),
        _named_string(server, r'^{name}\s*=\s*re\.compile\(r"([^"]*)"\)', "_ID_PATTERN"),
    )
    assert patterns[0].startswith("^[a-z0-9]"), "ID_PATTERN is not the pattern this test thinks it is"
    assert len(set(patterns)) == 1, f"the three ports admit different id shapes: {patterns}"

    # THE CHECK LENGTH, the one number in the grammar. Four characters on every card ever printed.
    lengths = tuple(
        _named_string(text, pattern.replace('"([^"]*)"', r"(\d+)"), name)
        for text, pattern, name in (
            (web, _TS_STRING, "CHECK_LENGTH"),
            (kotlin, _KT_STRING, "CHECK_LENGTH"),
            (server, _PY_STRING, "_CHECK_LENGTH"),
        )
    )
    assert set(lengths) == {"4"}, f"the three ports disagree about the check length: {lengths}"

    # THE DEVICE-LOCAL PREFIXES, which are the ids that must NEVER resolve anywhere: a workshop that
    # exists on one device only. The server holds both spellings in one tuple because it is refusing
    # them rather than minting them; the browser declares them separately, and the handset's own
    # second prefix is `DW_LOCAL_ID_PREFIX` in `data/StageSchema.kt` — a different file, pinned by
    # value in `DwWorkshopCodesTest` — so the handset is not part of this one comparison.
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
