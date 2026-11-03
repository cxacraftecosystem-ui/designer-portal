from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher

_IGNORED_TOKENS = frozenset(
    {
        "shri", "sri", "shree", "smt", "shrimati", "srimati", "kumari", "km", "kum", "sushri",
        "mr", "mrs", "ms", "miss", "dr", "md", "mohd", "mohammad", "mohammed", "muhammad",
        "late", "sh",
    }
)

SIMILARITY_THRESHOLD = 0.85


@dataclass(frozen=True)
class NameMatch:
    matched: bool
    score: float
    rule: str


def tokens(name: str | None) -> list[str]:
    text = unicodedata.normalize("NFKD", name or "")
    text = "".join(ch for ch in text if not unicodedata.combining(ch)).casefold()
    text = re.sub(r"[^\w\s]", " ", text)
    text = text.replace("_", " ")
    words = [word for word in text.split() if word and not word.isdigit()]
    kept = [word for word in words if word not in _IGNORED_TOKENS]
    return kept or words


def _initials_compatible(left: list[str], right: list[str]) -> bool:
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
    shorter, longer = (left, right) if len(left) <= len(right) else (right, left)
    if len(shorter) >= 2 and set(shorter) <= set(longer):
        return NameMatch(True, 0.9, "one name contains the other")
    if len(left) == len(right):
        scores = [_similar(a, b) for a, b in zip(sorted(left), sorted(right), strict=True)]
        if min(scores) >= SIMILARITY_THRESHOLD:
            return NameMatch(True, round(sum(scores) / len(scores), 3), "spelling variants")
    score = round(_similar(" ".join(sorted(left)), " ".join(sorted(right))), 3)
    if score >= SIMILARITY_THRESHOLD + 0.07:
        return NameMatch(True, score, "close spelling")
    return NameMatch(False, score, "names differ")
