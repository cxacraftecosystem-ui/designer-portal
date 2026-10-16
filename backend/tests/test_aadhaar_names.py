"""The tolerant name comparison between the name UIDAI signed and the name on a record."""

from __future__ import annotations

import pytest

from app.services.aadhaar.names import match_names, tokens


@pytest.mark.parametrize(
    ("signed", "recorded"),
    [
        ("Kamla Devi", "Kamla Devi"),
        ("KAMLA DEVI", "kamla devi"),
        ("Smt. Kamla Devi", "Kamla Devi"),
        ("Devi Kamla", "Kamla Devi"),
        ("R. Meena", "Rajesh Meena"),
        ("Rajesh Kumar Meena", "Rajesh Meena"),
        ("Lakshmi Bai", "Laxmi Bai"),
        ("Mohd. Rafiq", "Rafiq"),
        ("Ramesh Chandra", "Ramesh  Chandra"),
        ("Gita Devi Prajapati", "Gita Devi"),
        ("Zoë Fernandes", "Zoe Fernandes"),
        ("Ram Prasad", "Ramprasad"),
    ],
)
def test_names_that_are_the_same_person_match(signed, recorded):
    assert match_names(signed, recorded).matched


@pytest.mark.parametrize(
    ("signed", "recorded"),
    [
        ("Kamla Devi", "Sunita Devi"),
        ("Rajesh Meena", "Suresh Meena"),
        ("Devi", "Kamla Devi Prajapati"),
        ("Kamla Devi", ""),
        ("", "Kamla Devi"),
        ("Arjun Singh", "Ayesha Khan"),
    ],
)
def test_names_that_are_different_people_do_not(signed, recorded):
    assert not match_names(signed, recorded).matched


def test_the_rule_never_repeats_a_name():
    result = match_names("Kamla Devi", "Sunita Devi")
    assert "Kamla" not in result.rule and "Sunita" not in result.rule


def test_tokens_drop_honorifics_but_never_everything():
    assert tokens("Shri Ram") == ["ram"]
    assert tokens("Mr") == ["mr"]
