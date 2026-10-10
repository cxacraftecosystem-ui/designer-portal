"""EVERY HASH PASSLIB WROTE STILL VERIFIES, AND ANSWERS EXACTLY WHAT IT ANSWERED BEFORE.

On 2026-10-09 ``app/core/security.py`` stopped hashing through passlib (1.7.4, unmaintained since 2020)
and started calling bcrypt 5 directly. passlib could not run on bcrypt 5 at all, so the swap was the
only way off bcrypt 4.0.1 — and it is only acceptable if nobody's stored password changes meaning.

The hashes below were WRITTEN BY THE OLD CODE: the ``hash_password`` at commit 4b1bc44, under
passlib 1.7.4 with bcrypt 4.0.1, on 2026-10-09. They are data from before the change, not hashes this
module makes and then checks against itself — a round trip through the new code alone would prove
nothing about rows already in the database.

The cases are the ones where the two implementations could plausibly have disagreed:
  * 72 BYTES. bcrypt keys on the first 72 bytes. bcrypt 4 cut longer secrets silently; bcrypt 5
    refuses them, and the new code cuts them itself. A 73rd byte must still not matter.
  * A MULTI-BYTE CHARACTER ACROSS THE 72nd BYTE. The cut is on bytes, so ``é`` and ``è`` — which share
    their first UTF-8 byte — collapse to the same key when that byte is the 72nd. Odd, but it is what
    every existing hash means, so it is what the new code must answer.
  * passlib's REFUSALS: a NUL character, and more than 4096 BYTES of UTF-8 — bytes, because passlib
    encoded the secret before it measured it. Both answered "wrong password" on sign-in and refused
    to hash; both still do. The multi-byte rows are the ones a character count got wrong: 2,085
    characters can be 4,097 bytes, and passlib refused them.

Each bcrypt check at cost 12 is a fraction of a second, so the table is kept to the cases that
discriminate.
"""

import pytest

from app.core.security import (
    BCRYPT_ROUNDS,
    MAX_CHECKED_PASSWORD_BYTES,
    hash_password,
    verify_password,
)

UNICODE = "P\xe4ssw\xf6rd-\xfcn\xefc\xf6d\xe9-密码-पासवर्ड"

#: Written by passlib 1.7.4 + bcrypt 4.0.1 (commit 4b1bc44's hash_password), 2026-10-09.
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
        # A 73rd byte never counted and must not start counting.
        ("a" * 72, "a" * 72, True),
        ("a" * 72, "a" * 73, True),
        ("a" * 72, "a" * 71, False),
        # é and è share the byte bcrypt sees as the 72nd, so they are one password here — as they
        # were in every hash passlib wrote.
        ("a" * 71 + "\xe9", "a" * 71 + "\xe9", True),
        ("a" * 71 + "\xe9", "a" * 71 + "\xe8", True),
        ("a" * 71 + "\xe9", "a" * 72, False),
        # passlib's refusals were "wrong password", never a 500 and never a check of 72 bytes.
        ("the-real-one", "the-real-one\x00", False),
        ("a" * 72, "a" * (MAX_CHECKED_PASSWORD_BYTES + 1), False),
        # THE CAP IS ON UTF-8 BYTES. Each verdict below is passlib 1.7.4's own, measured 2026-10-09:
        # 4,096 bytes are checked (and match, since the first 72 bytes are the password), 4,097 bytes
        # are refused however few characters carry them.
        ("a" * 72, "a" * 72 + "\xe9" * 2012, True),  # 2,084 characters, 4,096 bytes
        ("a" * 72, "a" * 73 + "\xe9" * 2012, False),  # 2,085 characters, 4,097 bytes
        ("a" * 72, "a" * 72 + "\xe9" * 2100, False),  # 2,172 characters, 4,272 bytes
    ],
)
def test_a_hash_passlib_wrote_answers_as_it_did(stored_for, typed, expected):
    assert verify_password(typed, PASSLIB_HASHES[stored_for]) is expected


def test_a_new_hash_has_the_format_and_cost_passlib_wrote():
    """``$2b$`` at cost 12: the next deploy's hashes are the same kind of row as last year's, so a
    rollback to a passlib build reads them too (measured with passlib 1.7.4 on 2026-10-09)."""
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
        # 2,049 characters, 4,098 bytes: passlib raised PasswordSizeError for it (measured 2026-10-09).
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
    """A broken row is an operator's problem to hear about, not a wrong password — as with passlib."""
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
    """A real hash with a stray character, or one short. passlib raised "malformed bcrypt hash" for
    each (measured 2026-10-09); bcrypt 5 alone would answer False with the right password typed,
    which turns a corrupted row into a permanent "wrong password" nobody is told about."""
    with pytest.raises(ValueError):
        verify_password("the-real-one", broken)
