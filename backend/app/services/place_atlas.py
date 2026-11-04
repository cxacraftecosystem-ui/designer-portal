from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

from app.services.address import _LOOKUP as _STATE_LOOKUP, _fold


class Precision(str, Enum):
    TOWN = "TOWN"
    DISTRICT = "DISTRICT"
    STATE = "STATE"


@dataclass(frozen=True)
class Place:
    key: str
    label: str
    region: str
    state: str
    latitude: float
    longitude: float
    precision: Precision
    aliases: tuple[str, ...]
    district: str | None = None


_PLACES: tuple[Place, ...] = (
    Place(
        "bagru",
        "Bagru",
        "Jaipur district, Rajasthan",
        "Rajasthan",
        26.8149,
        75.5449,
        Precision.TOWN,
        ("bagru",),
        district="Jaipur",
    ),
    Place(
        "sanganer",
        "Sanganer",
        "Jaipur district, Rajasthan",
        "Rajasthan",
        26.8168,
        75.7889,
        Precision.TOWN,
        ("sanganer", "sanganeri"),
        district="Jaipur",
    ),
    Place(
        "balotra",
        "Balotra",
        "Barmer district, Rajasthan",
        "Rajasthan",
        25.8318,
        72.2400,
        Precision.TOWN,
        ("balotra", "balotara"),
        district="Barmer",
    ),
    Place(
        "akola-chittorgarh",
        "Akola",
        "Chittorgarh district, Rajasthan",
        "Rajasthan",
        24.8887,
        74.6269,
        Precision.DISTRICT,
        ("akolachittorgarh", "akolarajasthan", "akola"),
        district="Chittorgarh",
    ),
    Place(
        "kachchh",
        "Kachchh",
        "Kachchh district, Gujarat (shown at Bhuj)",
        "Gujarat",
        23.2419,
        69.6669,
        Precision.DISTRICT,
        ("kachchh", "kutch", "kachchhbhuj", "bhuj"),
        district="Kachchh",
    ),
    Place(
        "bareilly",
        "Bareilly",
        "Bareilly district, Uttar Pradesh",
        "Uttar Pradesh",
        28.3670,
        79.4304,
        Precision.TOWN,
        ("bareilly", "barreilly", "bareily"),
        district="Bareilly",
    ),
    Place(
        "barpali",
        "Barpali",
        "Bargarh district, Odisha",
        "Odisha",
        21.1918,
        83.5906,
        Precision.TOWN,
        ("barpali",),
        district="Bargarh",
    ),
    Place(
        "bargarh",
        "Bargarh",
        "Bargarh district, Odisha",
        "Odisha",
        21.3335,
        83.6190,
        Precision.TOWN,
        ("bargarh", "baragarh"),
        district="Bargarh",
    ),
    Place(
        "bhatli",
        "Bhatli",
        "Bargarh district, Odisha",
        "Odisha",
        21.2833,
        83.4500,
        Precision.TOWN,
        ("bhatli",),
        district="Bargarh",
    ),
    Place(
        "sohela",
        "Sohela",
        "Bargarh district, Odisha",
        "Odisha",
        21.2333,
        83.4667,
        Precision.TOWN,
        ("sohela",),
        district="Bargarh",
    ),
    Place(
        "remunda",
        "Remunda",
        "Bargarh district, Odisha",
        "Odisha",
        21.2167,
        83.6833,
        Precision.TOWN,
        ("remunda",),
        district="Bargarh",
    ),
    Place(
        "sambalpur",
        "Sambalpur",
        "Sambalpur district, Odisha",
        "Odisha",
        21.4669,
        83.9812,
        Precision.TOWN,
        ("sambalpur", "sambhalpur"),
        district="Sambalpur",
    ),
    Place(
        "sonepur",
        "Sonepur",
        "Subarnapur district, Odisha",
        "Odisha",
        20.8333,
        83.9167,
        Precision.TOWN,
        ("sonepur", "subarnapur", "sonpur"),
        district="Subarnapur",
    ),
    Place(
        "boudh",
        "Boudh",
        "Boudh district, Odisha",
        "Odisha",
        20.8379,
        84.3264,
        Precision.TOWN,
        ("boudh", "baudh"),
        district="Boudh",
    ),
    Place(
        "nuapatna",
        "Nuapatna",
        "Cuttack district, Odisha",
        "Odisha",
        20.4167,
        85.9333,
        Precision.TOWN,
        ("nuapatna", "maniabandha"),
        district="Cuttack",
    ),
    Place(
        "pipili",
        "Pipili",
        "Puri district, Odisha",
        "Odisha",
        20.1132,
        85.8318,
        Precision.TOWN,
        ("pipili", "pipli"),
        district="Puri",
    ),
    Place(
        "raghurajpur",
        "Raghurajpur",
        "Puri district, Odisha (Pattachitra)",
        "Odisha",
        19.8833,
        85.8000,
        Precision.TOWN,
        ("raghurajpur",),
        district="Puri",
    ),
    Place(
        "kotpad",
        "Kotpad",
        "Koraput district, Odisha",
        "Odisha",
        19.1500,
        82.3167,
        Precision.TOWN,
        ("kotpad",),
        district="Koraput",
    ),
    Place(
        "almora",
        "Almora",
        "Almora district, Uttarakhand",
        "Uttarakhand",
        29.5892,
        79.6467,
        Precision.TOWN,
        ("almora", "basaralmora", "basar"),
        district="Almora",
    ),
    Place(
        "bageshwar",
        "Bageshwar",
        "Bageshwar district, Uttarakhand",
        "Uttarakhand",
        29.8373,
        79.7710,
        Precision.TOWN,
        ("bageshwar", "bageswar", "bagheswar", "bagheshwar"),
        district="Bageshwar",
    ),
    Place(
        "rudraprayag",
        "Rudraprayag",
        "Rudraprayag district, Uttarakhand",
        "Uttarakhand",
        30.2844,
        78.9811,
        Precision.TOWN,
        ("rudraprayag",),
        district="Rudraprayag",
    ),
    Place(
        "dehradun",
        "Dehradun",
        "Dehradun district, Uttarakhand",
        "Uttarakhand",
        30.3165,
        78.0322,
        Precision.TOWN,
        ("dehradun", "ballupur", "ballupurdehradun"),
        district="Dehradun",
    ),
    Place(
        "jammu",
        "Jammu",
        "Jammu district, Jammu and Kashmir",
        "Jammu and Kashmir",
        32.7266,
        74.8570,
        Precision.TOWN,
        ("jammu", "satwari", "oldsatwari"),
        district="Jammu",
    ),
    Place(
        "kappaladoddi",
        "Kappaladoddi",
        "Krishna district, Andhra Pradesh",
        "Andhra Pradesh",
        16.1875,
        81.1389,
        Precision.DISTRICT,
        ("kappaladoddi", "kappladoddi"),
        district="Krishna",
    ),
    Place(
        "kharagpur",
        "Kharagpur",
        "Paschim Medinipur district, West Bengal",
        "West Bengal",
        22.3149,
        87.3105,
        Precision.TOWN,
        ("kharagpur", "iitkharagpur"),
        district="Paschim Medinipur",
    ),
)


def atlas_district_anchors() -> tuple[tuple[str, str, float, float], ...]:
    return tuple(
        (place.state, place.district, place.latitude, place.longitude)
        for place in _PLACES
        if place.district
    )


_STATE_SEATS: dict[str, tuple[str, float, float]] = {
    "Andhra Pradesh": ("Amaravati", 16.5062, 80.6480),
    "Gujarat": ("Gandhinagar", 23.2156, 72.6369),
    "Jammu and Kashmir": ("Srinagar", 34.0837, 74.7973),
    "Ladakh": ("Leh", 34.1526, 77.5771),
    "Maharashtra": ("Mumbai", 19.0760, 72.8777),
    "Rajasthan": ("Jaipur", 26.9124, 75.7873),
    "Uttar Pradesh": ("Lucknow", 26.8467, 80.9462),
    "Uttarakhand": ("Dehradun", 30.3165, 78.0322),
    "West Bengal": ("Kolkata", 22.5726, 88.3639),
}

_EXTRA_STATE_ALIASES: dict[str, str] = {
    "gujrat": "Gujarat",
    "uttrakhand": "Uttarakhand",
    "uttaranchal": "Uttarakhand",
    "up": "Uttar Pradesh",
    "jandk": "Jammu and Kashmir",
    "jk": "Jammu and Kashmir",
}

_ALIAS_TO_PLACE: dict[str, Place] = {alias: place for place in _PLACES for alias in place.aliases}

_MAX_ALIAS_WORDS = 6

_SPLIT = re.compile(r"[^A-Za-z0-9&]+")
_TRAILING_DIGITS = re.compile(r"\d+$")


def _tokens(text: str) -> list[str]:
    out: list[str] = []
    for part in _SPLIT.split(text):
        for piece in re.split(r"(&)", part):
            if piece:
                out.append("and" if piece == "&" else piece)
    return out


def _resolve_state(token_run: str) -> str | None:
    folded = _fold(token_run)
    candidates = (folded, _TRAILING_DIGITS.sub("", folded))
    for candidate in candidates:
        if not candidate:
            continue
        if candidate in _EXTRA_STATE_ALIASES:
            return _EXTRA_STATE_ALIASES[candidate]
        resolved = _STATE_LOOKUP.get(candidate)
        if resolved:
            return resolved
    return None


@dataclass(frozen=True)
class Resolution:
    place: Place | None
    seat: str | None = None


def resolve_place(text: str | None) -> Resolution:
    if not text or not text.strip():
        return Resolution(None)

    words = _tokens(text)
    if not words:
        return Resolution(None)

    state: str | None = None
    consumed: range = range(0)
    for width in range(min(_MAX_ALIAS_WORDS, len(words)), 0, -1):
        for start in range(len(words) - width + 1):
            found = _resolve_state("".join(words[start : start + width]))
            if found and found in _STATE_SEATS:
                state, consumed = found, range(start, start + width)
                break
        if state:
            break

    for width in range(min(_MAX_ALIAS_WORDS, len(words)), 0, -1):
        for start in range(len(words) - width + 1):
            if any(index in consumed for index in range(start, start + width)):
                continue
            run = _fold("".join(words[start : start + width]))
            place = _ALIAS_TO_PLACE.get(run) or _ALIAS_TO_PLACE.get(_TRAILING_DIGITS.sub("", run))
            if place:
                return Resolution(place)

    if state:
        name, latitude, longitude = _STATE_SEATS[state]
        return Resolution(
            Place(
                key=f"state-{_fold(state)}",
                label=state,
                region=f"State-level only — drawn at {name}",
                state=state,
                latitude=latitude,
                longitude=longitude,
                precision=Precision.STATE,
                aliases=(),
            ),
            seat=name,
        )

    return Resolution(None)
