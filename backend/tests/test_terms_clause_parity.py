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
    return re.sub(r"\s+", " ", text).strip()


def web_clauses() -> dict[int, tuple[str, str]]:
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
    source = ANDROID_TERMS.read_text(encoding="utf-8")
    block = source.split("val TERMS_CLAUSES", 1)
    assert len(block) == 2, "TERMS_CLAUSES is no longer declared in TermsScreen.kt"
    body_text = block[1]

    out: dict[int, tuple[str, str]] = {}
    for entry in re.finditer(r"TermsClause\(\s*(\d+)\s*,(.*?)\n    \)", body_text, re.DOTALL):
        number = int(entry.group(1))
        runs = re.findall(r'"((?:[^"\\]|\\.)*)"', entry.group(2), re.DOTALL)
        assert len(runs) >= 2, f"clause {number} has no body segments"
        title = runs[0]
        joined = "".join(runs[1:])
        out[number] = (_tidy(title), _tidy(joined))
    return out


def test_both_files_still_declare_clauses_in_the_shape_this_test_reads():
    web = web_clauses()
    android = android_clauses()
    assert len(web) >= 9, f"only {len(web)} clauses parsed out of {WEB_TERMS.name}"
    assert len(android) >= 9, f"only {len(android)} clauses parsed out of {ANDROID_TERMS.name}"


def test_the_two_copies_of_the_agreement_carry_the_same_clause_numbers():
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
    for path in (WEB_TERMS, ANDROID_TERMS):
        source = path.read_text(encoding="utf-8")
        assert "What is never recorded" not in source, (
            f"{path.name} appears to hard-code the recording notice. It is served — see "
            "UsageConsentNoticeBody and usage.NOTICE_VERSION."
        )
