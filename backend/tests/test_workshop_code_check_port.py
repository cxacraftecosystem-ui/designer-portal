"""The server's copy of the workshop-code check agrees with the browser's, character for character.

WHY THIS IS ITS OWN MODULE AND NOT A TEST BESIDE THE FEATURE THAT USES IT. Everything else about the
access queue needs Postgres and skips itself when ``DATABASE_URL`` is not local, which is always the
case in CI — deliberately, because the deployed database is not a scratch pad. This question needs no
database at all, and it is the one most likely to break silently: a port whose arithmetic drifts by a
bit refuses every real scan as damaged, and the feature reads as "the cards do not work" with nothing
saying why. Gated behind a database it would never be asked in CI, so it is asked here instead.

THE VECTORS ARE NOT DERIVED FROM THE PYTHON UNDER TEST. They were produced by running
``workshopCodeCheck``'s body — copied verbatim out of ``frontend/lib/workshopCodes.ts``, the same
four lines that print every card — under Node, and pasted here. So this compares two independent
implementations rather than a function with itself. The second vector is also the worked example in
that file's own header, ``DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD``, which is what checks that the
extraction was faithful in the first place.

WHY THE TWO SPELLINGS DIFFER AND MUST STILL AGREE. The JavaScript writes the FNV-1a multiply as five
shifts and a sum because its ``*`` produces a double whose low bits stop being exact past 2^53; the
Python writes it as a multiply and a mask, because Python's integers are exact. ``1 + 2 + 16 + 128 +
256 + 16777216 = 16777619`` is the FNV prime, and the signed/unsigned mixture in the original changes
intermediate values only by whole multiples of 2^32. That argument is what these vectors hold to
account. If somebody "optimises" either side, this is the test that fails.

⚠ IF THE BROWSER'S IMPLEMENTATION EVER CHANGES, THESE VECTORS ARE STALE AND THIS TEST IS WORSE THAN
NOTHING — it would pin the server to a check no card carries. The check is deliberately frozen (the
file it lives in says a card printed today must scan in five years), so that is a change nobody
should be making; if it happens, regenerate these six lines from the new implementation rather than
adjusting the Python until they pass.
"""

from app.services.design_workshop_access import code_check

#: prefix -> the four characters ``workshopCodeCheck`` returns for it. See the module docstring for
#: how these were obtained.
BROWSER_VECTORS: tuple[tuple[str, str], ...] = (
    # A design-workshop code: the letter this repository gave `designWorkshop`.
    ("DPW1:G:CMSIK2JG8000EH8XC1LCY661A", "0PK3"),
    # THE SAME ID UNDER THE ARTISAN LETTER, and a completely different check — which is the property
    # that makes the check worth computing over the type letter as well as the id. It is also the
    # worked example in `workshopCodes.ts`'s own header.
    ("DPW1:A:CMSIK2JG8000EH8XC1LCY661A", "NEWD"),
    # A second cuid, so a port that happened to be right for one input is not mistaken for correct.
    ("DPW1:G:CLXQ9WZ7T0001PQ4R2VN8K3JD", "JSVH"),
    # A UUID client key, hyphens and all — the form a row created offline carries until it syncs,
    # and the longest payload the encoder produces.
    ("DPW1:G:3F2504E0-4F89-11D3-9A0C-0305E82C3301", "R0R5"),
    # The shortest id `ID_PATTERN` admits: eight characters.
    ("DPW1:G:AAAAAAAA", "30KG"),
    # THE EMPTY PREFIX — the bare FNV offset basis folded to twenty bits, and the one input that
    # runs no loop iteration at all. A port that silently dropped or duplicated the first character
    # would pass every vector above and fail this one.
    ("", "S7E5"),
)


def test_the_check_matches_the_browsers_own_vectors():
    for prefix, expected in BROWSER_VECTORS:
        assert code_check(prefix) == expected, prefix


def test_the_check_is_four_crockford_characters_for_anything_it_is_handed():
    """A shape assertion over inputs the vectors do not cover.

    Four characters, always, out of Crockford base32 — no I, L, O or U, because those are what
    people get wrong reading a code off a card. A port that returned a short string for a small hash
    (a hand-rolled base32 that dropped leading zeros, say) would produce a code the browser's own
    decoder refuses on length, and only for the small minority of ids that hash low — the worst
    possible failure distribution to debug.
    """
    alphabet = set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    for prefix in ("", "A", "DPW1:G:AAAAAAAA", "DPW1:G:" + "Z" * 64, "DPW1:G:0"):
        answer = code_check(prefix)
        assert len(answer) == 4, prefix
        assert set(answer) <= alphabet, (prefix, answer)
