"""The duplicate-artisan scorer against ``shared/duplicate-score-vectors.json``, plus its pieces.

No database: the scorer is pure. The vector file is identical in both portals, so a change to the
weights, caps, honorifics or phonetic rules is a change to both or to neither.
"""

from __future__ import annotations

import json
import pathlib

import pytest

from app.services import artisan_duplicates as d

_ROOT = pathlib.Path(__file__).resolve().parents[2]
VECTORS = json.loads((_ROOT / "shared" / "duplicate-score-vectors.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("vector", VECTORS["vectors"], ids=[v["name"] for v in VECTORS["vectors"]])
def test_vector(vector):
    a, b = d.profile_from_dict(vector["a"]), d.profile_from_dict(vector["b"])
    expect = vector["expect"]
    for left, right in ((a, b), (b, a)):  # symmetric: a pair is stored once, in id order
        explained = d.explain_pair(left, right)
        if expect["score"] is None:
            assert explained is None
            assert d.score_pair(left, right) is None
            continue
        score, reasons = explained
        assert score == pytest.approx(expect["score"], abs=1e-9)
        assert sorted(code for code, _ in reasons) == sorted(expect["reasons"])
        stored = d.score_pair(left, right)
        if expect["band"] is None:
            assert stored is None
        else:
            assert stored is not None and stored.band == expect["band"]


def test_vectors_cover_the_cases_the_contract_names():
    names = " ".join(v["name"] for v in VECTORS["vectors"]).lower()
    for needle in ("honorific", "lakshmi", "mohd", "initial", "different districts", "different aadhaar",
                   "different gender", "far apart", "0.90", "0.75", "0.60"):
        assert needle in names, needle


# The sections the Field Repository wrote first. The file is one file in both portals, so both
# scorers answer every section.


def test_thresholds_match_the_vector_file():
    assert VECTORS["thresholds"] == {
        "show": d.SHOW_THRESHOLD,
        "hold": d.HOLD_THRESHOLD,
        "veryLikely": d.VERY_LIKELY_THRESHOLD,
    }


@pytest.mark.parametrize(("token", "expected"), VECTORS["skeletons"])
def test_shared_skeletons(token, expected):
    assert d.skeleton(token) == expected


@pytest.mark.parametrize("vector", VECTORS["names"], ids=lambda v: v["label"])
def test_shared_name_similarity(vector):
    sim = d.name_sim(vector["a"], vector["b"])
    if "nameSim" in vector:
        assert sim == pytest.approx(vector["nameSim"], abs=1e-6)
    else:
        assert sim < vector["nameSimBelow"]


@pytest.mark.parametrize(
    "vector",
    [v for v in VECTORS["pairs"] if v.get("portal") in (None, "designer")],
    ids=lambda v: v["label"],
)
def test_shared_pairs(vector):
    a, b = d.profile_from_dict(vector["a"]), d.profile_from_dict(vector["b"])
    for left, right in ((a, b), (b, a)):
        result = d.score_pair(left, right)
        if vector["score"] is None:
            assert result is None
            continue
        assert result is not None
        assert result.score == pytest.approx(vector["score"], abs=1e-9)
        assert result.band == vector["band"]
        for code in vector.get("reasons", []):
            assert code in result.codes
        for code in vector.get("absentReasons", []):
            assert code not in result.codes


def test_skeletons():
    assert d.skeleton("lakshmi") == d.skeleton("laxmi") == "lksm"
    assert d.skeleton("ramesh") == "rms"


def test_name_normalisation_drops_honorifics_and_aliases():
    assert d.normalise_name("Shri. Ramesh  Kumar") == "ramesh kumar"
    assert d.normalise_name("Mohd Yusuf") == "mohammad yusuf"
    assert d.normalise_name("Late Smt Kamla Devi") == "kamla devi"


def test_an_initial_matches_a_token_with_its_letter():
    assert d.phonetic_overlap("r kumar", "ramesh kumar") == 1.0
    assert d.phonetic_overlap("s kumar", "ramesh kumar") == 0.5


def test_reasons_never_carry_a_value():
    a = d.profile_from_dict({"name": "Giriraj Prasad", "phone": "9829012345", "pehchanCardNumber": "RJ77",
                             "aadhaarFingerprint": "c" * 64, "state": "Rajasthan", "district": "Jaipur"})
    b = d.profile_from_dict({"name": "Giriraj Prasaad", "phone": "9829012345", "pehchanCardNumber": "RJ77",
                             "aadhaarFingerprint": "c" * 64, "state": "Rajasthan", "district": "Jaipur"})
    result = d.score_pair(a, b)
    assert result is not None
    text = json.dumps(result.reasons_payload())
    for value in ("9829012345", "RJ77", "c" * 64, "Jaipur", "Giriraj"):
        assert value not in text
    assert set(result.codes) <= set(d.REASON_CODES)


def test_bands():
    assert d.band_for(0.9) == "VERY_LIKELY"
    assert d.band_for(0.89) == "LIKELY"
    assert d.band_for(0.75) == "LIKELY"
    assert d.band_for(0.74) == "POSSIBLE"
    assert d.band_for(0.6) == "POSSIBLE"
    assert d.band_for(0.59) is None


def test_scan_finds_pairs_through_blocks_and_skips_strangers():
    profiles = [
        d.profile_from_dict({"id": "a1", "name": "Lakshmi Devi", "state": "Rajasthan", "district": "Jaipur", "phone": "9829000001"}),
        d.profile_from_dict({"id": "a2", "name": "Laxmi Devi", "state": "Rajasthan", "district": "Jaipur", "phone": "9829000001"}),
        d.profile_from_dict({"id": "a3", "name": "Meena Bai", "state": "Gujarat", "district": "Kachchh"}),
        d.profile_from_dict({"id": "a4", "name": "Totally Other", "pehchanCardNumber": "P1"}),
        d.profile_from_dict({"id": "a5", "name": "Someone Else", "pehchanCardNumber": "P1"}),
    ]
    found = d.scan_pairs(profiles)
    assert ("a1", "a2") in found
    assert ("a4", "a5") in found and found[("a4", "a5")].score == 1.0
    assert not any("a3" in key for key in found)


def test_candidate_payload_hides_the_aadhaar_reason_unless_typed():
    other = d.profile_from_dict({"id": "x", "name": "A B", "aadhaarFingerprint": "d" * 64, "dateOfBirth": "1971-04-02"})
    me = d.profile_from_dict({"name": "A B", "aadhaarFingerprint": "d" * 64})
    result = d.score_pair(me, other)
    shown = d.candidate_payload(other, result, typed_aadhaar=False)
    assert "AADHAAR" not in [r["code"] for r in shown["reasons"]]
    assert shown["artisan"]["birthYear"] == 1971
    assert "dateOfBirth" not in shown["artisan"]
    assert "AADHAAR" in [r["code"] for r in d.candidate_payload(other, result, typed_aadhaar=True)["reasons"]]
