import re
from dataclasses import dataclass
from typing import Any

from app.core.db import db

MIN_PHONE_DIGITS = 6

PHONE_KEY_DIGITS = 10

_NON_DIGITS = re.compile(r"[^0-9]")
_NON_ALNUM = re.compile(r"[^A-Za-z0-9]")


def looks_like_email(value: Any) -> bool:
    return "@" in str(value or "")


def normalise_email(value: Any) -> str:
    return str(value or "").strip().lower()


def normalise_phone(value: Any) -> str | None:
    digits = _NON_DIGITS.sub("", str(value or ""))
    if len(digits) < MIN_PHONE_DIGITS:
        return None
    if len(digits) > PHONE_KEY_DIGITS:
        return digits[-PHONE_KEY_DIGITS:]
    return digits


def normalise_empanelment_no(value: Any) -> str | None:
    cleaned = _NON_ALNUM.sub("", str(value or "")).upper()
    return cleaned or None



@dataclass(frozen=True)
class ProfileKeys:
    phone_key: str | None
    empanelment_key: str | None
    phone_taken: bool = False
    empanelment_taken: bool = False


async def resolve_profile_keys(*, user_id: str, phone: Any, empanelment_no: Any) -> ProfileKeys:
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


FOUND = "FOUND"
NOT_FOUND = "NOT_FOUND"
AMBIGUOUS = "AMBIGUOUS"


@dataclass(frozen=True)
class IdentityLookup:
    outcome: str
    user: Any | None = None


def identifier_key(raw: Any) -> str:
    identifier = str(raw or "").strip()
    if looks_like_email(identifier):
        return "email:" + normalise_email(identifier)
    phone_key = normalise_phone(identifier)
    if phone_key is not None:
        return "phone:" + phone_key
    empanelment_key = normalise_empanelment_no(identifier)
    if empanelment_key is not None:
        return "empanelment:" + empanelment_key
    return "raw:" + identifier.lower()


async def resolve_identifier(raw: Any) -> IdentityLookup:
    identifier = str(raw or "").strip()
    if not identifier:
        return IdentityLookup(NOT_FOUND)

    if looks_like_email(identifier):
        user = await db.user.find_unique(where={"email": normalise_email(identifier)})
        return IdentityLookup(FOUND, user) if user else IdentityLookup(NOT_FOUND)

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
