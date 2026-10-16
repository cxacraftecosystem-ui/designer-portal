"""Does the name UIDAI signed belong to the person on this record?

A TOLERANT comparison, because the two names were typed by different people years apart: the
enrolment operator who keyed the Aadhaar, and the researcher or account owner who keyed this row.
Exact equality would refuse almost every real artisan ("Smt. Kamla Devi" against "Kamla Devi",
"MOHD. RAFIQ" against "Mohammad Rafiq", "R. Meena" against "Rajesh Meena").

It is still a gate and not a rubber stamp. A mismatch does not refuse the verification — the
signature held, so the document is genuine — it parks the record at NAME_MISMATCH for an admin, who
sees both names side by side (services/aadhaar/records.py). So the cost of a false "mismatch" is an
admin's click, and the cost of a false "match" is a stranger's card accepted silently; the rules
below lean towards asking.

Pure: no I/O, no clock. ``tests/test_aadhaar_names.py`` pins the cases.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher

#: Words that carry no identity: honorifics, and the relational particles enrolment forms keep.
_IGNORED_TOKENS = frozenset(
    {
        "shri", "sri", "shree", "smt", "shrimati", "srimati", "kumari", "km", "kum", "sushri",
        "mr", "mrs", "ms", "miss", "dr", "md", "mohd", "mohammad", "mohammed", "muhammad",
        "late", "sh",
    }
)

#: The similarity a whole-name comparison must reach when the token rules do not settle it.
#: Measured against the cases in the test module: transliteration variants ("Laxmi"/"Lakshmi",
#: "Rafeeq"/"Rafiq") sit at 0.86-0.93; two different people sharing a surname sit below 0.75.
SIMILARITY_THRESHOLD = 0.85


@dataclass(frozen=True)
class NameMatch:
    matched: bool
    #: 0..1, for the admin's screen. Not a probability.
    score: float
    #: A short reason, in words, for the audit line. Never contains either name.
    rule: str


def tokens(name: str | None) -> list[str]:
    """Casefolded, accent-stripped, punctuation-free tokens, honorifics removed."""
    text = unicodedata.normalize("NFKD", name or "")
    text = "".join(ch for ch in text if not unicodedata.combining(ch)).casefold()
    text = re.sub(r"[^\w\s]", " ", text)
    text = text.replace("_", " ")
    words = [word for word in text.split() if word and not word.isdigit()]
    kept = [word for word in words if word not in _IGNORED_TOKENS]
    return kept or words


def _initials_compatible(left: list[str], right: list[str]) -> bool:
    """"R Meena" against "Rajesh Meena": the same number of words, each word equal or an initial."""
    if len(left) != len(right):
        return False
    for a, b in zip(left, right, strict=True):
        if a == b:
            continue
        if len(a) == 1 and b.startswith(a):
            continue
        if len(b) == 1 and a.startswith(b):
            continue
        return False
    return True


#: Romanisation variants of the same Indian name: "Lakshmi"/"Laxmi", "Rafeeq"/"Rafiq",
#: "Bhagwati"/"Bhagvati", "Shanthi"/"Shanti". Applied to BOTH sides before comparing spellings.
_FOLDS = (
    ("ksh", "x"), ("ks", "x"), ("ee", "i"), ("oo", "u"), ("w", "v"), ("ph", "f"),
    ("th", "t"), ("dh", "d"), ("bh", "b"), ("kh", "k"), ("gh", "g"), ("jh", "j"), ("sh", "s"),
    ("z", "j"), ("q", "k"), ("aa", "a"), ("ii", "i"), ("uu", "u"),
)


def fold(word: str) -> str:
    for old, new in _FOLDS:
        word = word.replace(old, new)
    if word.endswith("y"):
        word = word[:-1] + "i"
    return re.sub(r"(.)+", r"", word)


def _similar(a: str, b: str) -> float:
    if fold(a) == fold(b):
        return 1.0
    return SequenceMatcher(None, fold(a), fold(b)).ratio()


def match_names(signed: str | None, recorded: str | None) -> NameMatch:
    """Compare the name UIDAI signed with the name on the record."""
    left, right = tokens(signed), tokens(recorded)
    if not left or not right:
        return NameMatch(False, 0.0, "a name is empty")
    if left == right:
        return NameMatch(True, 1.0, "identical")
    if sorted(left) == sorted(right):
        return NameMatch(True, 1.0, "same words, different order")
    if _initials_compatible(left, right) or _initials_compatible(sorted(left), sorted(right)):
        return NameMatch(True, 0.95, "initials")
    joined_left, joined_right = "".join(left), "".join(right)
    if joined_left == joined_right:
        return NameMatch(True, 0.98, "spacing")
    # One name is the other plus words (a father's name, a surname dropped on one side). Accepted
    # only when the shorter side still has two words, so a lone "Devi" can never match anybody.
    shorter, longer = (left, right) if len(left) <= len(right) else (right, left)
    if len(shorter) >= 2 and set(shorter) <= set(longer):
        return NameMatch(True, 0.9, "one name contains the other")
    # Word-by-word transliteration tolerance, same word count.
    if len(left) == len(right):
        scores = [_similar(a, b) for a, b in zip(sorted(left), sorted(right), strict=True)]
        if min(scores) >= SIMILARITY_THRESHOLD:
            return NameMatch(True, round(sum(scores) / len(scores), 3), "spelling variants")
    score = round(_similar(" ".join(sorted(left)), " ".join(sorted(right))), 3)
    if score >= SIMILARITY_THRESHOLD + 0.07:
        return NameMatch(True, score, "close spelling")
    return NameMatch(False, score, "names differ")
