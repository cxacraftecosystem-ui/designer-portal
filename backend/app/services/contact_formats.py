"""Email address and phone number formats: the two contact rules, written down server-side once.

WHY THIS MODULE HAD TO BE WRITTEN RATHER THAN REUSED
----------------------------------------------------
Every other format this repository enforces already had one authoritative implementation to point
at: :mod:`app.services.artisan_identity` owns Aadhaar and Pehchan, :mod:`app.services.address` owns
the state list and the PIN code. Email and phone had **none**. Measured on 2026-08-24, on the whole
tree:

* ``ArtisanCreate`` and ``ArtisanUpdate`` declare ``phone: str | None`` and ``email: str | None``
  with no validator at all, so the RECORD path — the one every other rule was told to copy — was
  itself accepting anything a client sent.
* The web's email rule was a private ``EMAIL_RE`` inside ``ArtisanForm.tsx``, duplicated a second
  time as the ``pattern`` attribute on the same input.
* The web's phone rule was inline and unexported in ``PhoneField.tsx``.
* Android had a third answer for email — the Kotlin port of ``coerce_value`` refused an address
  with no ``@`` — so the web accepted what the handset refused, and the server accepted what the
  handset had refused. Three implementations, three answers, and nobody noticed for as long as it
  took to write this sentence.

So "validate the way the record pages do" could not be satisfied by reuse for these two. The rules
below are the record pages' rules, character for character, moved to the one path both clients and
every direct API caller share.

WHY HERE AND NOT IN ONE OF THE MODULES THAT ALREADY EXISTED
-----------------------------------------------------------
Not in ``artisan_identity``: that module's header commits it to "Aadhaar and Artisan Pehchan Card
handling" and to being the only place governed identity data is formatted. An email address is not
identity data, and widening that module is how a module stops meaning anything.

Not inside ``stage_schema``: the record path needs the same two functions, and ``stage_schema`` must
not become a dependency of ``schemas/records.py``.

PURE, AND THAT IS A REQUIREMENT AND NOT A HAPPY ACCIDENT. Like ``address``, this module imports
nothing but ``re``. ``stage_schema`` is imported by the DB-free registry tests and by the on-device
report path, so anything it reaches for has to stay clear of ``app.core.db``.

THE MESSAGES ARE COPIED VERBATIM from the record pages (``PhoneField.tsx`` / ``PhoneField.kt`` for
the two phone sentences, ``ArtisanForm.tsx`` for the email one). The rule stated across three files
in this repository — "the same checks, in the same order, with the same sentences" — is a UX
contract: a researcher who corrects a number on the handset must read the same instruction on the
laptop, and now on the server too.
"""

from __future__ import annotations

import re

#: The longest address any RFC-conformant mailbox can be (64-octet local part, an ``@``, a
#: 254-octet domain, bounded by the 254 the SMTP path allows for a reverse-path). Declared here
#: rather than at the field so the registry and the record schemas cannot disagree about it.
EMAIL_MAX_LENGTH = 254

#: THE ONE REGEX, and it is deliberately the web record form's own — "something, an @, something, a
#: dot, something", with no whitespace anywhere. It does not attempt RFC 5322: an address is
#: verified by sending mail to it, and every stricter pattern this repository could write would
#: start refusing real addresses typed by real researchers while still accepting undeliverable
#: ones. What it does catch is the whole population of actual mistakes seen in the artisan table —
#: a name with no domain, a domain with no dot, two addresses pasted into one box.
_EMAIL = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")

#: India's dial code, the default on both clients' pickers, and the one code with an exact length
#: rule. Named rather than inlined because it appears in three places below and a typo in one of
#: them would silently move every Indian number onto the loose 4–14 arm.
INDIA_DIAL_CODE = "+91"

_WHITESPACE = re.compile(r"\s+")
_ASCII_DIGITS_ONLY = re.compile(r"[^0-9]")
#: A dial code as either client can write one: a ``+`` and one to four digits.
_DIAL_CODE = re.compile(r"\+[0-9]{1,4}")

#: The characters that may sit BETWEEN the digits of a phone number: the space both clients
#: compose, the tabs and newlines a paste can carry, the no-break space an IME produces, the ASCII
#: hyphen, and the six dashes U+2010 to U+2015.
#:
#: SPELLED OUT RATHER THAN ``\s``, and that is the whole reason this constant exists instead of a
#: one-line ``\s`` class. ``\s`` means three different sets in the three languages this rule is
#: written in: Python's matches the no-break space, JavaScript's matches it and U+FEFF as well, and
#: Kotlin's (Java's, without ``UNICODE_CHARACTER_CLASS``) matches neither. A shape rule built on
#: ``\s`` would therefore be STRICTER ON THE HANDSET than on the server — the one direction this
#: whole feature refuses, because it paints a red line under a value the repository accepts on a
#: stage a designer cannot get past. Enumerated, the three agree by construction.
#:
#: WRITTEN WITH ESCAPES AND NOT WITH THE CHARACTERS THEMSELVES, for the reason
#: ``stageFieldFormats.ts`` gives about the copy in ``address.py``: three visually
#: indistinguishable dashes in a character class where getting the order wrong silently
#: widens the set is not something a reviewer can check in a diff.
_PHONE_SEPARATORS = re.compile("[ \t\r\n\u00a0\u2010-\u2015-]+")
#: What is left of a phone number once those come off: an optional ``+`` and then digits, nothing
#: else. See :func:`phone_error` for what this refuses and why the length window could not.
_PHONE_COMPACT = re.compile(r"\+?[0-9]+")


def phone_is_number_shaped(value: str | None) -> bool:
    """True when ``value`` holds nothing but a dial code, digits and separators.

    THE HOLE THE 4–14 WINDOW LEAVES OPEN, AND IT IS THE ONE A ROSTER PRINTS.
    :func:`phone_digits` throws away every character that is not an ASCII digit, so the window
    counts DIGITS and says nothing whatever about the string. Measured on this tree:
    ``phone_error("+91 9876543210 call his son Ramesh on the landline instead")`` and
    ``phone_error("abc9876543210def")`` both answered ``None`` — ten digits each, accepted, stored,
    and printed verbatim in the participant roster of a document submitted to a ministry.

    A bound alone does not close it (``"abc9876543210def"`` is sixteen characters) and a bound is
    still worth having for the other half of the same defect, so the field carries both.

    WHY THIS CANNOT REFUSE ANYTHING A CLIENT COMPOSES. Both pickers write exactly
    ``f"{dial_code} {digits}"`` and nothing else, and the legacy shape is a bare run of digits, so
    every value either client can produce is number-shaped by construction. What it refuses is what
    arrived through the API or was typed into a box that had no rule — which is the population this
    format was declared for.
    """
    if value is None:
        return False
    compact = _PHONE_SEPARATORS.sub("", str(value))
    return bool(compact) and _PHONE_COMPACT.fullmatch(compact) is not None


def normalize_email(value: str | None) -> str | None:
    """Trim an address; ``None``/blank collapses to ``None``.

    Case is left ALONE, unlike :func:`app.services.artisan_identity.normalize_pehchan`. The domain
    is case-insensitive but the local part is not, per RFC 5321, and lower-casing "R.Nayak@..." is
    a silent edit to somebody's address made by a validator that was only asked whether it was
    well-formed. Nothing here deduplicates on an email, so there is no index to protect.
    """
    if value is None:
        return None
    cleaned = _WHITESPACE.sub(" ", str(value)).strip()
    return cleaned or None


def email_error(value: str | None) -> str | None:
    """The reason ``value`` is not a usable email address, or ``None`` when it is fine.

    Blank is not this function's question — the same convention as every other ``*_error`` in this
    repository. Whether an empty box is allowed is ``required``'s business.
    """
    if value is None:
        return None
    if not _EMAIL.fullmatch(value):
        return "Enter a valid email address (name@example.com)."
    if len(value) > EMAIL_MAX_LENGTH:
        # A bound as well as a shape, because the shape is unbounded on both sides of the ``@``:
        # "a...a@b...b.c" of any length satisfies the regex above, and the registry field carrying
        # this format had NO ``max_length`` at all when this was written.
        return f"An email address cannot be longer than {EMAIL_MAX_LENGTH} characters."
    return None


def phone_digits(value: str) -> str:
    """The ASCII 0-9 in ``value`` and nothing else — the phone column's one definition of a digit.

    Emphatically not ``str.isdigit()``, which is true of the Devanagari "१" and the fullwidth "２"
    an Indic or CJK IME will happily produce. ``PhoneField.kt`` has the long version of this
    paragraph: a number typed on such a keyboard passed straight through into storage, and the
    web's ``/\\D/g`` then read that artisan as having no phone number at all — the record looked
    complete on the device that captured it and blank in the browser.
    """
    return _ASCII_DIGITS_ONLY.sub("", value)


def normalize_phone(value: str | None) -> str | None:
    """Trim a stored phone and collapse its internal whitespace; blank collapses to ``None``.

    The stored shape is ``"+CC digits"`` — that single space is what both clients compose
    (``PhoneField.tsx``'s ``${dialCode} ${digits}``, ``PhoneField.kt``'s ``composeArtisanPhone``)
    and it is load-bearing for :func:`split_phone`, so it is preserved rather than stripped.
    """
    if value is None:
        return None
    cleaned = _WHITESPACE.sub(" ", str(value)).strip()
    return cleaned or None


def split_phone(stored: str) -> tuple[str, str]:
    """Split a stored phone into its dial code and its national digits.

    A PORT OF ``parsePhone`` (``PhoneField.tsx``) AND ``parseArtisanPhone`` (``PhoneField.kt``),
    including the arm that matters most:

        *Bare numbers (legacy rows) are Indian nationals: 10 digits under +91.*

    That arm is not a nicety. ``Artisan.phone`` has never had a server-side rule, so the table
    holds bare numbers written before the dial-code picker existed, and ``hydrate_entries`` has
    been copying them into ``participant.phone`` ever since. A rule that read a bare number as
    "no dial code, therefore malformed" would refuse **every one of those rows on its next save** —
    and because ``save_stage`` restores a refused key from ``previous``, the designer would get a
    permanent red error on a box they never touched, over a value they cannot correct.

    WHERE THIS DELIBERATELY DIFFERS FROM THE CLIENTS, and it can only ever be looser: they own a
    ~246-row country table (``lib/countries.ts``, ``Countries.dialCodes``) and match the longest
    known code. Duplicating that table here to answer a "4 to 14 digits" question would buy
    precision nobody reads and guarantee a third copy drifting out of step with the other two. So
    a code this module cannot name is split on the space both clients write, and failing that on a
    one-to-four-digit prefix. The consequence is bounded and is in the safe direction: the
    non-India window may be applied to a national part one or two digits longer or shorter than
    the clients would have computed, which can only make this module ACCEPT something a client
    would have queried — never refuse something a client composed.
    """
    text = normalize_phone(stored) or ""
    if not text:
        return INDIA_DIAL_CODE, ""
    compact = text.replace(" ", "")
    if compact.startswith(INDIA_DIAL_CODE):
        return INDIA_DIAL_CODE, phone_digits(compact[len(INDIA_DIAL_CODE):])
    if compact.startswith("+"):
        head, separator, tail = text.partition(" ")
        if separator and _DIAL_CODE.fullmatch(head):
            return head, phone_digits(tail)
        match = _DIAL_CODE.match(compact)
        if match:
            return match.group(0), phone_digits(compact[match.end():])
        return INDIA_DIAL_CODE, phone_digits(compact)
    # Bare numbers (legacy rows) are Indian nationals: 10 digits under +91.
    return INDIA_DIAL_CODE, phone_digits(compact)


def phone_error(value: str | None) -> str | None:
    """The reason ``value`` is not a usable phone number, or ``None`` when it is fine.

    Takes the STORED STRING — ``"+91 9876543210"`` — and not a (code, digits) pair, because the
    stored string is what every caller has: ``coerce_value`` sees one value out of a JSON object,
    and ``Artisan.phone`` is one column.

    THE RULE, verbatim from both clients: ``+91`` takes exactly ten digits; any other dial code
    takes four to fourteen (loose enough for the range of national number lengths worldwide).

    THE SHAPE IS CHECKED AS WELL AS THE COUNT, AND THE COUNT ALONE WAS NOT ENOUGH.
    :func:`phone_digits` discards everything that is not an ASCII digit, so the 4–14 window bounds
    the DIGITS and nothing about the string: measured on this tree,
    ``"+91 9876543210 call his son Ramesh on the landline instead"`` and ``"abc9876543210def"``
    both answered ``None``, were stored, and printed verbatim in a roster. See
    :func:`phone_is_number_shaped`, which is what closes that and which no client-composed value
    can fail.

    A FAILED SHAPE REUSES THE COUNT'S OWN SENTENCE rather than adding a third one. "Enter a
    10-digit number for +91." is exactly the instruction for ``"abc9876543210def"``, it is already
    the sentence this repository shows for every other way of getting that box wrong, and a new
    third sentence would have to be ported to two clients and a fixture table to buy nothing a
    designer can act on.

    THE ONE PLACE THIS WAS STRICTER THAN THE CLIENTS IS NOW A PLACE THEY AGREE. Both clients used
    to open with ``digits.isEmpty() -> null`` — right for a control whose box only accepts digits,
    where the empty case is an empty box, and this docstring used to say "no client can reach this".
    That was true of the CONTROL and false of the DATA: nothing has ever validated
    ``Artisan.phone``, ``hydrate_entries`` copies it into ``participant.phone``, and a stored
    ``"not a number"`` therefore refused on every save while both previews called it clean — a
    silent revert on the next GET, which is the exact failure this feature was written to end. Both
    clients now refuse a non-blank value with no digits too, and the row has moved out of
    ``server_only`` into the shared vector table.
    """
    text = normalize_phone(value)
    if text is None:
        return None
    code, digits = split_phone(text)
    shaped = phone_is_number_shaped(text)
    if code == INDIA_DIAL_CODE:
        return None if shaped and len(digits) == 10 else "Enter a 10-digit number for +91."
    return None if shaped and 4 <= len(digits) <= 14 else "Enter a valid phone number (4–14 digits)."


def validate_email(value: str | None) -> str | None:
    """Normalise and validate in one step; raises ``ValueError`` for a Pydantic field validator.

    Shaped exactly like :func:`app.services.address.validate_pincode` and
    :func:`app.services.artisan_identity.validate_aadhaar` so that whichever schema wires it up
    next needs no new idiom. NOTHING CALLS IT YET, and that is recorded rather than tidied away:
    ``ArtisanCreate``/``ArtisanUpdate`` still declare no email or phone validator, so the record
    path's own hole is open. Closing it is a separate, deliberate change, because a PATCH that
    re-sends an artisan's stored legacy number would begin to 422 on a field the editor never
    meant to touch — the same trap as the stage boxes, one surface over, and it needs its own
    measurement of what is actually in that column first.

    AND HERE IS WHAT LEAVING IT OPEN NOW COSTS, WHICH WAS NOT WRITTEN DOWN WHEN THE STAGE HALF
    LANDED. ``hydrate_entries`` copies an artisan's address into ``participant.email`` through
    ``coerce_value`` and drops any value that field refuses (``if error or cleaned is None:
    continue``). Before the format was declared, a malformed address was COPIED and then printed;
    now it is DROPPED, so the participant row's contact box comes up blank with nothing said —
    which is this repository's most-repeated defect class (an absence that reads as "there is no
    address" when the record holds one). The direction is right: printing a bad address in a
    ministry document is worse than printing none. But it is silent, and the honest close is at
    THIS end — an address refused on the way into the artisan record can never reach hydration —
    which is one more reason the two functions below exist ready to be wired. Until they are, a
    designer who needs that address has the artisan record two clicks away, and the mirrored box
    beside the stage copy is the record page's own governed field.
    """
    normalized = normalize_email(value)
    error = email_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def validate_phone(value: str | None) -> str | None:
    """Normalise and validate a stored phone; raises ``ValueError`` when malformed.

    See :func:`validate_email` for why neither of these two is wired into the record schemas yet.
    """
    normalized = normalize_phone(value)
    error = phone_error(normalized)
    if error:
        raise ValueError(error)
    return normalized
