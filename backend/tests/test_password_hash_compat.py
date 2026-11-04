import pytest

from app.core.security import (
    BCRYPT_ROUNDS,
    MAX_CHECKED_PASSWORD_BYTES,
    hash_password,
    verify_password,
)

UNICODE = "P\xe4ssw\xf6rd-\xfcn\xefc\xf6d\xe9-密码-पासवर्ड"

PASSLIB_HASHES = {
    "the-real-one": "$2b$12$Yapd550X/2v8KIcVgQ87WeJVVvplam8.DqAqLnoll7oljx72ydek6",
    UNICODE: "$2b$12$Em0.y5Qw/ggkfFBMH7DGeOow6gWwk4sqNiBBUZQmxpDOQoTr73ZWS",
    "a" * 72: "$2b$12$SFTNxvAoY5Jigw6HvQYSdugxrmoWXk.4tmWfLTek3NHpqEXwmVYWm",
    "a" * 71 + "\xe9": "$2b$12$quda1/MSf4rNriKkyYJF7uqHIhJSjoTONfPW95lOFv8jY5EUqU2ZG",
}


@pytest.mark.parametrize(
    ("stored_for", "typed", "expected"),
    [
        ("the-real-one", "the-real-one", True),
        ("the-real-one", "the-real-onE", False),
        ("the-real-one", "the-real-one ", False),
        (UNICODE, UNICODE, True),
        (UNICODE, UNICODE[:-1], False),
        ("a" * 72, "a" * 72, True),
        ("a" * 72, "a" * 73, True),
        ("a" * 72, "a" * 71, False),
        ("a" * 71 + "\xe9", "a" * 71 + "\xe9", True),
        ("a" * 71 + "\xe9", "a" * 71 + "\xe8", True),
        ("a" * 71 + "\xe9", "a" * 72, False),
        ("the-real-one", "the-real-one\x00", False),
        ("a" * 72, "a" * (MAX_CHECKED_PASSWORD_BYTES + 1), False),
        ("a" * 72, "a" * 72 + "\xe9" * 2012, True),
        ("a" * 72, "a" * 73 + "\xe9" * 2012, False),
        ("a" * 72, "a" * 72 + "\xe9" * 2100, False),
    ],
)
def test_a_hash_passlib_wrote_answers_as_it_did(stored_for, typed, expected):
    assert verify_password(typed, PASSLIB_HASHES[stored_for]) is expected


def test_a_new_hash_has_the_format_and_cost_passlib_wrote():
    stored = hash_password("the-real-one")
    assert stored.startswith(f"$2b${BCRYPT_ROUNDS:02d}$")
    assert len(stored) == 60
    assert verify_password("the-real-one", stored) is True
    assert verify_password("not-the-real-one", stored) is False


@pytest.mark.parametrize(
    "password",
    [
        "has\x00a NUL",
        "x" * (MAX_CHECKED_PASSWORD_BYTES + 1),
        "\xe9" * 2049,
    ],
)
def test_what_passlib_refused_to_hash_is_still_refused(password):
    with pytest.raises(ValueError, match="password may not"):
        hash_password(password)


@pytest.mark.parametrize("password", ["x" * MAX_CHECKED_PASSWORD_BYTES, "\xe9" * 2048])
def test_the_longest_checkable_password_still_hashes(password):
    assert verify_password(password, hash_password(password))


def test_a_malformed_stored_hash_raises_rather_than_answering():
    with pytest.raises(ValueError):
        verify_password("anything", "not-a-hash")
    assert verify_password("anything", "") is False
    assert verify_password("anything", None) is False


@pytest.mark.parametrize(
    "broken",
    [
        PASSLIB_HASHES["the-real-one"] + " ",
        PASSLIB_HASHES["the-real-one"] + "\n",
        PASSLIB_HASHES["the-real-one"] + "\r\n",
        " " + PASSLIB_HASHES["the-real-one"],
        PASSLIB_HASHES["the-real-one"][:-1],
    ],
)
def test_a_stored_hash_of_the_wrong_length_raises_as_it_did(broken):
    with pytest.raises(ValueError):
        verify_password("the-real-one", broken)
