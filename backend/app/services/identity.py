"""THE THREE THINGS A PERSON MAY TYPE INTO THE SIGN-IN BOX, AND HOW EACH BECOMES ONE ACCOUNT.

A designer signs in with their **email**, their **phone number** or their **empanelment number**.
Only the first of those is a column on ``User``; the other two live on ``DesignerProfile`` as free
text a person typed for a report. This module is the whole of the translation, and it is the only
place either normalisation is written on the Python side.

── WHY NORMALISED KEYS AND NOT AN INDEX ON THE TEXT ──────────────────────────────────────────────

``+91 98765 43210``, ``098765 43210`` and ``9876543210`` are one telephone. ``EMP/2026/0042``,
``emp 2026 0042`` and ``EMP-2026-0042`` are one empanelment. An index on the raw column matches none
of those to each other, and a ``LIKE`` that tried to would be a table scan on the sign-in path. So
``DesignerProfile`` carries two extra columns — ``phoneKey`` and ``empanelmentKey`` — holding the
normalised form, both unique, both nullable, and the raw columns are untouched because they are
printed verbatim on a ministry report.

**THE SQL HALF OF THESE TWO FUNCTIONS IS IN THE MIGRATION**
(``20260830170000_auth_identity_and_password_links``), because the rows that already existed had to
be backfilled by the database and there is no way to call Python from there. The two implementations
must agree character for character. Change one, change the other, and say so in both.

── RESOLUTION ORDER, AND WHAT HAPPENS ON A COLLISION ─────────────────────────────────────────────

An ``@`` settles it: an address is looked up on ``User.email`` and nothing else is consulted. That
is not a precedence rule so much as an observation — no phone number and no empanelment number
contains an ``@``.

Everything else is looked up in **BOTH** remaining spaces and the answers are collected before any
of them is used. There is no "phone first, then empanelment" ordering here, deliberately: a
precedence rule silently signs somebody in whenever one person's telephone number is another
person's empanelment number, and it signs in the wrong one. So:

* exactly one distinct account across both spaces → that account;
* none → the generic "invalid credentials" answer, unchanged;
* **more than one → refused as AMBIGUOUS**, with a sentence telling the person to use their email
  address, which is the one identifier that cannot be ambiguous.

The uniqueness columns make the third case rare and cannot make it impossible: a value that is one
designer's phone key and a different designer's empanelment key satisfies both unique indexes.

── AND WHY RESOLUTION IS NOT ADMISSION ───────────────────────────────────────────────────────────

Everything downstream of the sign-in — ``assert_access_admits``, ``access_roster.access_row``,
``roster_allows``, ``ensure_empanelled``, ``mark_roster_seen``, and both rosters' ``@@unique`` on
email — is keyed on the EMAIL and stays that way. This module's entire job is to answer "which
account is this?"; the caller then reads ``user.email`` off that account and every gate runs exactly
as it did when email was the only way in. **No gate is widened, reworded or skipped to make a phone
login work.** If you find yourself passing a phone number to something in ``services/designers.py``,
stop: you have skipped the account.
"""

import re
from dataclasses import dataclass
from typing import Any

from app.core.db import db

#: The fewest digits that can be a telephone number worth indexing. Below this a "phone" is a typo
#: or an extension, and claiming a two-digit key would let one profile own the string "42" for the
#: whole installation.
MIN_PHONE_DIGITS = 6

#: The owner's instruction was "their phone number without the country code", and Indian subscriber
#: numbers are ten digits. Anything longer is assumed to carry a country code or a trunk prefix and
#: is cut from the RIGHT, so a designer who stored "+91 98765 43210" signs in by typing the ten
#: digits they would read out loud — and so does one who stored the bare ten.
#:
#: WHAT THIS DOES TO A STORED VALUE THAT HAS A COUNTRY CODE ON IT: nothing at all to the value. The
#: raw ``phone`` column keeps every character the designer typed, because it is what a report prints
#: and what somebody dials. Only the KEY is cut. The cost is stated rather than hidden — two
#: designers whose numbers differ ONLY in their country code (+91 98765 43210 and +44 98765 43210)
#: produce one key, so the second one saved does not claim it and cannot sign in by phone. That is
#: the ambiguity rule doing its job on a population this product does not have; the alternative,
#: keying on the full international form, would lock out every designer who typed the bare number
#: into a box that has never asked for a country code.
PHONE_KEY_DIGITS = 10

_NON_DIGITS = re.compile(r"[^0-9]")
_NON_ALNUM = re.compile(r"[^A-Za-z0-9]")


def looks_like_email(value: Any) -> bool:
    """Is this an address rather than one of the two numbers?

    An ``@`` and nothing more. This is NOT validation — the address still has to match a row — and
    it must not become validation: a person who mistypes their own email deserves "invalid email or
    password", not a lecture about the shape of an address, and certainly not to have their input
    fall through and be looked up as an empanelment number.
    """
    return "@" in str(value or "")


def normalise_email(value: Any) -> str:
    """Lower-cased and trimmed, matching ``services.designers.normalise_email`` exactly."""
    return str(value or "").strip().lower()


def normalise_phone(value: Any) -> str | None:
    """The indexable form of a telephone number, or None when there is not one here.

    Digits only; the last :data:`PHONE_KEY_DIGITS` when there are more than that. See the constant
    for what that does and does not do to a value carrying a country code.
    """
    digits = _NON_DIGITS.sub("", str(value or ""))
    if len(digits) < MIN_PHONE_DIGITS:
        return None
    if len(digits) > PHONE_KEY_DIGITS:
        return digits[-PHONE_KEY_DIGITS:]
    return digits


def normalise_empanelment_no(value: Any) -> str | None:
    """The indexable form of an empanelment number, or None when the field is blank.

    Upper-cased with every separator removed, so ``EMP/2026/0042`` and ``emp 2026 0042`` are one
    key. No length floor: an institution's numbering scheme is not this module's to second-guess,
    and unlike a phone number there is no short string a person would type by accident.
    """
    cleaned = _NON_ALNUM.sub("", str(value or "")).upper()
    return cleaned or None


# --------------------------------------------------------------------------------------
# Claiming a key on save
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ProfileKeys:
    """The two keys a profile save should write, and whether either was refused.

    ``phone_taken`` / ``empanelment_taken`` are what the profile screen prints. They are NOT an
    error: the save goes through, the raw number is stored, and only the sign-in key is withheld.
    """

    phone_key: str | None
    empanelment_key: str | None
    phone_taken: bool = False
    empanelment_taken: bool = False


async def resolve_profile_keys(*, user_id: str, phone: Any, empanelment_no: Any) -> ProfileKeys:
    """Work out what ``phoneKey``/``empanelmentKey`` should be for one profile save.

    **A KEY ALREADY HELD BY ANOTHER PROFILE IS NOT CLAIMED, AND THE SAVE IS NOT REFUSED.** That is
    the rule the migration establishes and this is the other half of it. Refusing the save would be
    defensible on a clean table and is not defensible on this one: 44 profiles here share one
    empanelment number, so a 409 would leave 43 designers unable to save their own biography until
    an administrator resolved a data problem none of them caused — and the field is now required, so
    "leave it blank" is not a way out either.

    Withholding the key costs them exactly one thing: they cannot sign in by typing that number.
    They can still sign in with their email, the number still prints on their report, and the screen
    says so in one line.
    """
    phone_key = normalise_phone(phone)
    empanelment_key = normalise_empanelment_no(empanelment_no)

    phone_taken = False
    empanelment_taken = False

    if phone_key is not None:
        holder = await db.designerprofile.find_unique(where={"phoneKey": phone_key})
        if holder is not None and holder.userId != user_id:
            phone_key, phone_taken = None, True

    if empanelment_key is not None:
        holder = await db.designerprofile.find_unique(where={"empanelmentKey": empanelment_key})
        if holder is not None and holder.userId != user_id:
            empanelment_key, empanelment_taken = None, True

    return ProfileKeys(
        phone_key=phone_key,
        empanelment_key=empanelment_key,
        phone_taken=phone_taken,
        empanelment_taken=empanelment_taken,
    )


# --------------------------------------------------------------------------------------
# Resolving a typed identifier to an account
# --------------------------------------------------------------------------------------

#: The three outcomes of a lookup. ``AMBIGUOUS`` is the one worth naming: it is not "not found" and
#: it is not "wrong password", and the person reading it has a remedy that neither of those offers.
FOUND = "FOUND"
NOT_FOUND = "NOT_FOUND"
AMBIGUOUS = "AMBIGUOUS"


@dataclass(frozen=True)
class IdentityLookup:
    outcome: str
    user: Any | None = None


async def resolve_identifier(raw: Any) -> IdentityLookup:
    """Turn whatever was typed into the sign-in box into at most one ``User`` row.

    NO PASSWORD IS CHECKED HERE and nothing is written. The caller verifies the credential against
    the row this returns, and answers ``NOT_FOUND`` and a wrong password identically — see
    ``routes/auth.login``, whose comment explains why that one answer is deliberately unchanged.
    """
    identifier = str(raw or "").strip()
    if not identifier:
        return IdentityLookup(NOT_FOUND)

    if looks_like_email(identifier):
        user = await db.user.find_unique(where={"email": normalise_email(identifier)})
        return IdentityLookup(FOUND, user) if user else IdentityLookup(NOT_FOUND)

    # BOTH SPACES, ALWAYS, AND THE ANSWERS COMPARED BEFORE ANY OF THEM IS USED. See the module
    # docstring: an ordering here is a silent wrong-account sign-in the day the two spaces overlap.
    profiles: list[Any] = []
    phone_key = normalise_phone(identifier)
    if phone_key is not None:
        match = await db.designerprofile.find_unique(where={"phoneKey": phone_key})
        if match is not None:
            profiles.append(match)
    empanelment_key = normalise_empanelment_no(identifier)
    if empanelment_key is not None:
        match = await db.designerprofile.find_unique(where={"empanelmentKey": empanelment_key})
        if match is not None:
            profiles.append(match)

    user_ids = {profile.userId for profile in profiles}
    if not user_ids:
        return IdentityLookup(NOT_FOUND)
    if len(user_ids) > 1:
        return IdentityLookup(AMBIGUOUS)

    user = await db.user.find_unique(where={"id": user_ids.pop()})
    return IdentityLookup(FOUND, user) if user else IdentityLookup(NOT_FOUND)
