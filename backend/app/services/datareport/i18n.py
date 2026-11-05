from __future__ import annotations

import functools
import json
from collections.abc import Callable, Iterable
from pathlib import Path

LOCALES = ("en", "hi")
DEFAULT_LOCALE = "en"
_HERE = Path(__file__).resolve().parent


@functools.cache
def strings(locale: str) -> dict[str, str]:
    path = _HERE / f"i18n_{locale}.json"
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {str(k): str(v) for k, v in data.items() if not str(k).startswith("_")}


def _candidates(key: str, default: str) -> list[str]:
    found = [key]
    parts = key.split(".")
    if parts[0] in ("col", "dict") and len(parts) >= 3:
        found.append(f"{parts[0]}.{parts[-1]}")
    if parts[0] == "sheet" and len(parts) >= 3:
        found.append(f"sheet.{parts[1]}")
    if default:
        found.append(f"text.{default}")
    return found


def lookup(locale: str, key: str, default: str) -> str | None:
    table = strings(locale)
    for candidate in _candidates(key, default):
        value = table.get(candidate)
        if value:
            return value
    return None


def translator(locale: str | None) -> Callable[[str, str], str]:
    chosen = locale if locale in LOCALES else DEFAULT_LOCALE
    if chosen == DEFAULT_LOCALE:
        return lambda key, default: default

    def translate(key: str, default: str) -> str:
        return lookup(chosen, key, default) or default

    return translate


t = translator


def missing(locale: str, pairs: Iterable[tuple[str, str]]) -> list[str]:
    return sorted({key for key, default in pairs if lookup(locale, key, default) is None})


def missing_keys(locale: str) -> list[str]:
    table = strings(locale)
    return sorted(key for key in strings(DEFAULT_LOCALE) if not table.get(key))


def extra_keys(locale: str) -> list[str]:
    canon = strings(DEFAULT_LOCALE)
    return sorted(key for key in strings(locale) if key not in canon)
