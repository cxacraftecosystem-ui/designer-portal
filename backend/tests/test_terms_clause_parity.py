"""The terms and conditions exist TWICE, and nothing could tell you when the two drifted.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY THERE ARE TWO COPIES AT ALL
══════════════════════════════════════════════════════════════════════════════════════════════

The owner's instruction on 2026-08-30 was to cut the sign-in screen's usage-recording block to one
line — *"I agree to terms and conditions"*, the phrase linked. The long text had to go somewhere, so
it became a terms page: `frontend/app/terms/page.tsx` on the web, and
`android/.../ui/TermsScreen.kt` on the handset.

**The handset's copy is not a link to the web's, deliberately.** This product is used in villages
with no signal; a link to a page the device cannot load is worse than no link, because it is a legal
agreement a person is being asked to accept and cannot read. So the clauses are compiled into the
APK, and there are two copies of one agreement.

That is a real cost and it is accepted with its eyes open. What is NOT acceptable is the two copies
drifting: a person would have agreed to one set of terms on their phone and a different set in the
browser, with the same version stamped on both. `TermsScreen.kt`'s own header asks that they be
changed in one commit — but an instruction in a comment is not a mechanism, and the Android lane that
wrote it said so in its own report: *"a drift in clauses 1, 2, 3, 5, 7 or 8 would be silent."*

This file is the mechanism.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY A PYTHON TEST OVER TWO FILES IT DOES NOT OWN
══════════════════════════════════════════════════════════════════════════════════════════════

Exactly the shape `tests/test_role_ladder_parity.py` already uses: it holds twenty-three hand-kept
copies of the role ladder — across `lib/types.ts`, six frontend e2e tuples, seven Kotlin literals,
Android tests and README.md — to the one `ROLE_RANK` in `deps.py`. Neither client's own suite can see
the other client, and the backend suite is the only gate that runs with both trees on disk.

It parses SOURCE TEXT rather than importing anything, so it cannot be defeated by either build. That
makes it brittle to a *refactor* of either file, which is the intended trade: a rewrite of how the
clauses are declared should make somebody read this file, and the failure names exactly what to do.
"""

from __future__ import annotations

import pathlib
import re

_ROOT = pathlib.Path(__file__).resolve().parents[2]
WEB_TERMS = _ROOT / "frontend" / "app" / "terms" / "page.tsx"
ANDROID_TERMS = (
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
    / "TermsScreen.kt"
)


def _tidy(text: str) -> str:
    """One line, single-spaced — the comparison both files must survive.

    Whitespace is NOT part of the agreement: the web wraps its prose to the editor's column inside a
    ``<p>``, and the Kotlin wraps at its own column with ``+`` between string literals. Two identical
    sentences therefore arrive here with different line breaks and different indentation, and
    comparing them raw would fail on every clause forever, which is the same as having no test.
    """
    return re.sub(r"\s+", " ", text).strip()


def web_clauses() -> dict[int, tuple[str, str]]:
    """``{number: (title, body)}`` as the web page declares them.

    Reads `<Clause n={N} title="…">…</Clause>` and strips the markup inside. `<p>` is the only tag
    the bodies carry today; if a clause ever gains an inline element the strip below keeps its TEXT,
    which is the right answer — a `<strong>` around a word does not change what was agreed.
    """
    source = WEB_TERMS.read_text(encoding="utf-8")
    out: dict[int, tuple[str, str]] = {}
    pattern = re.compile(
        r'<Clause\s+n=\{(\d+)\}\s+title="([^"]+)"\s*>(.*?)</Clause>',
        re.DOTALL,
    )
    for match in pattern.finditer(source):
        number = int(match.group(1))
        title = match.group(2)
        body = re.sub(r"<[^>]+>", " ", match.group(3))
        out[number] = (_tidy(title), _tidy(body))
    return out


def android_clauses() -> dict[int, tuple[str, str]]:
    """``{number: (title, body)}`` as ``TERMS_CLAUSES`` declares them.

    Each entry is ``TermsClause(n, "title", "body" + "more body" + …)``. The body's segments are
    joined with ``+`` across lines, so every quoted run after the title is concatenated — which is
    what the Kotlin compiler does too, and the reason this parser can be this small.
    """
    source = ANDROID_TERMS.read_text(encoding="utf-8")
    block = source.split("val TERMS_CLAUSES", 1)
    assert len(block) == 2, "TERMS_CLAUSES is no longer declared in TermsScreen.kt"
    body_text = block[1]

    out: dict[int, tuple[str, str]] = {}
    for entry in re.finditer(r"TermsClause\(\s*(\d+)\s*,(.*?)\n    \)", body_text, re.DOTALL):
        number = int(entry.group(1))
        # Every double-quoted run in the entry, in order: the first is the title, the rest are the
        # body's segments. Kotlin has no escaped quotes in these strings today; if one is ever added
        # this parser must learn about it, and the assertion below is what will say so.
        runs = re.findall(r'"((?:[^"\\]|\\.)*)"', entry.group(2), re.DOTALL)
        assert len(runs) >= 2, f"clause {number} has no body segments"
        title = runs[0]
        joined = "".join(runs[1:])
        out[number] = (_tidy(title), _tidy(joined))
    return out


def test_both_files_still_declare_clauses_in_the_shape_this_test_reads():
    """The parsers found something, so a green result below means agreement and not silence.

    THE FAILURE THIS PREVENTS IS THE ONE A PARITY TEST IS MOST PRONE TO: a refactor renames
    ``TermsClause`` or rewrites the JSX, both parsers return nothing, ``{} == {}`` passes, and the
    guard reports agreement about a comparison it never made. Asserting the count first is what
    turns an empty read into a red test instead of a green one.
    """
    web = web_clauses()
    android = android_clauses()
    assert len(web) >= 9, f"only {len(web)} clauses parsed out of {WEB_TERMS.name}"
    assert len(android) >= 9, f"only {len(android)} clauses parsed out of {ANDROID_TERMS.name}"


def test_the_two_copies_of_the_agreement_carry_the_same_clause_numbers():
    """A clause added to one client and not the other is the drift that matters most.

    It is worse than a reworded sentence: the numbering is what a person quotes back ("clause 6 says
    my offline work is my responsibility"), so an extra clause on one client silently renumbers every
    clause after it on that client alone.
    """
    web = web_clauses()
    android = android_clauses()
    assert sorted(web) == sorted(android), (
        f"the web declares clauses {sorted(web)} and the handset declares {sorted(android)}. "
        "Add it to both, in one commit — see TermsScreen.kt's header."
    )


def test_every_clause_title_matches_word_for_word():
    web = web_clauses()
    android = android_clauses()
    mismatched = {
        number: (web[number][0], android[number][0])
        for number in sorted(web)
        if number in android and web[number][0] != android[number][0]
    }
    assert mismatched == {}, (
        f"clause titles differ between the two clients: {mismatched}. "
        "The terms are one agreement; two wordings are two agreements."
    )


def test_every_clause_body_matches_word_for_word():
    """The whole point of the file.

    Whitespace is normalised (the two files wrap differently and always will); every other character
    must be identical, punctuation included. An em-dash that became a hyphen on one client is a
    trivial difference and is still two documents, and this is the only place in either build that
    would notice.
    """
    web = web_clauses()
    android = android_clauses()
    mismatched = {
        number: {"web": web[number][1], "android": android[number][1]}
        for number in sorted(web)
        if number in android and web[number][1] != android[number][1]
    }
    assert mismatched == {}, (
        "the two copies of the terms have drifted:\n"
        + "\n".join(
            f"  clause {n}:\n    web:     {v['web']}\n    android: {v['android']}"
            for n, v in mismatched.items()
        )
        + "\nChange both in one commit."
    )


def test_the_recording_notice_is_NOT_duplicated_into_either_file():
    """Clause 10 is the server's, and it must never become an eleventh hand-written clause.

    ``UsageConsentNoticeBody``'s own header states the rule this pins: not one word of the recording
    notice is written in a client, because the server computes it from the policy actually in force
    and stamps a version on it, and the answer a person gives is filed against that version. A client
    that hard-coded the notice would be filing agreements against text the server had already
    changed — which is not a smaller kind of consent, it is a different one.

    So the sentinel is the version-bearing phrase: it appears in neither terms file, because both
    render the notice from the fetched payload instead.
    """
    for path in (WEB_TERMS, ANDROID_TERMS):
        source = path.read_text(encoding="utf-8")
        assert "What is never recorded" not in source, (
            f"{path.name} appears to hard-code the recording notice. It is served — see "
            "UsageConsentNoticeBody and usage.NOTICE_VERSION."
        )
