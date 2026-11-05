from __future__ import annotations

import functools
import json
import re
from collections.abc import Iterable
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
LOCALES = ("en", "hi")
DEFAULT_LOCALE = "en"
_SAFE = re.compile(r"^[a-z]{2}$")


@functools.cache
def strings(locale: str) -> dict[str, str]:
    if not _SAFE.match(locale or ""):
        return {}
    path = HERE / f"i18n_{locale}.json"
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return {}
    if not isinstance(data, dict):
        return {}
    return {
        str(k): v
        for k, v in data.items()
        if isinstance(v, str) and v.strip() and not str(k).startswith("_")
    }


def _generic(key: str) -> str | None:
    parts = key.split(".")
    if parts[0] == "column":
        parts = ["col", *parts[1:]]
    if parts[0] == "col" and len(parts) >= 2:
        return f"col.{parts[-1]}"
    if parts[0] in ("describe", "dict") and len(parts) >= 3:
        return f"describe.{parts[-1]}"
    if parts[0] == "sheet" and len(parts) >= 3:
        return f"sheet.{parts[1]}"
    return None


def lookup(locale: str, key: str, default: str) -> str | None:
    table = strings(locale)
    found = table.get(key)
    if found:
        return found
    if default:
        found = table.get(f"text.{default}")
        if found:
            return found
    generic = _generic(key)
    if generic and generic != key:
        english = strings(DEFAULT_LOCALE).get(generic)
        if not default or english == default or key.startswith("sheet."):
            return table.get(generic) or None
    return None


class Translator:
    def __init__(self, locale: str = DEFAULT_LOCALE) -> None:
        self.locale = locale if locale in LOCALES else DEFAULT_LOCALE

    def text(self, key: str, default: str) -> str:
        if self.locale == DEFAULT_LOCALE:
            return default
        return lookup(self.locale, key, default) or default

    def __call__(self, key: str, default: str, **values: Any) -> str:
        text = self.text(key, default)
        if not values:
            return text
        try:
            return text.format(**values)
        except KeyError, IndexError, ValueError:
            return default.format(**values)


def translator(locale: str | None) -> Translator:
    return Translator(locale or DEFAULT_LOCALE)


t = translator


def missing(locale: str, pairs: Iterable[tuple[str, str]]) -> list[str]:
    return sorted({key for key, default in pairs if lookup(locale, key, default) is None})


def missing_keys(locale: str) -> list[str]:
    table = strings(locale)
    return sorted(key for key in strings(DEFAULT_LOCALE) if not table.get(key))


def extra_keys(locale: str) -> list[str]:
    canon = strings(DEFAULT_LOCALE)
    return sorted(key for key in strings(locale) if key not in canon)
