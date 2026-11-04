from __future__ import annotations

import re

EMAIL_MAX_LENGTH = 254

_EMAIL = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")

INDIA_DIAL_CODE = "+91"

_WHITESPACE = re.compile(r"\s+")
_ASCII_DIGITS_ONLY = re.compile(r"[^0-9]")
_DIAL_CODE = re.compile(r"\+[0-9]{1,4}")

_PHONE_SEPARATORS = re.compile("[ \t\r\n\u00a0\u2010-\u2015-]+")
_PHONE_COMPACT = re.compile(r"\+?[0-9]+")


def phone_is_number_shaped(value: str | None) -> bool:
    if value is None:
        return False
    compact = _PHONE_SEPARATORS.sub("", str(value))
    return bool(compact) and _PHONE_COMPACT.fullmatch(compact) is not None


def normalize_email(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = _WHITESPACE.sub(" ", str(value)).strip()
    return cleaned or None


def email_error(value: str | None) -> str | None:
    if value is None:
        return None
    if not _EMAIL.fullmatch(value):
        return "Enter a valid email address (name@example.com)."
    if len(value) > EMAIL_MAX_LENGTH:
        return f"An email address cannot be longer than {EMAIL_MAX_LENGTH} characters."
    return None


def phone_digits(value: str) -> str:
    return _ASCII_DIGITS_ONLY.sub("", value)


def normalize_phone(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = _WHITESPACE.sub(" ", str(value)).strip()
    return cleaned or None


def split_phone(stored: str) -> tuple[str, str]:
    text = normalize_phone(stored) or ""
    if not text:
        return INDIA_DIAL_CODE, ""
    compact = text.replace(" ", "")
    if compact.startswith(INDIA_DIAL_CODE):
        return INDIA_DIAL_CODE, phone_digits(compact[len(INDIA_DIAL_CODE) :])
    if compact.startswith("+"):
        head, separator, tail = text.partition(" ")
        if separator and _DIAL_CODE.fullmatch(head):
            return head, phone_digits(tail)
        match = _DIAL_CODE.match(compact)
        if match:
            return match.group(0), phone_digits(compact[match.end() :])
        return INDIA_DIAL_CODE, phone_digits(compact)
    return INDIA_DIAL_CODE, phone_digits(compact)


def phone_error(value: str | None) -> str | None:
    text = normalize_phone(value)
    if text is None:
        return None
    code, digits = split_phone(text)
    shaped = phone_is_number_shaped(text)
    if code == INDIA_DIAL_CODE:
        return None if shaped and len(digits) == 10 else "Enter a 10-digit number for +91."
    return (
        None if shaped and 4 <= len(digits) <= 14 else "Enter a valid phone number (4–14 digits)."
    )


def validate_email(value: str | None) -> str | None:
    normalized = normalize_email(value)
    error = email_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def validate_phone(value: str | None) -> str | None:
    normalized = normalize_phone(value)
    error = phone_error(normalized)
    if error:
        raise ValueError(error)
    return normalized
