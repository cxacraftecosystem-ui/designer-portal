"""Designer profile -> workshop prefill, field by field, plus the per-workshop read. No database.

Covers the 2026-10-21 request: every profile column a stage asks for again reaches its box, the
profile's Location card answers the address boxes when the columns are blank, the workshop read
picks the right designer's profile, and ``GET /design-workshops/{id}/designer-prefill`` answers the
shape both clients read.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import design_workshops as routes
from app.services import design_workshop_oversight as oversight, designers
from app.services.designers import PREFILL_MAP, PROFILE_FIELDS

#: One answer per mapped column, and what the prefill must hand the stage box for it.
_ANSWERS: dict[str, tuple[Any, Any]] = {
    "displayName": ("Latha Nayak", "Latha Nayak"),
    "institution": ("NIFT Bhubaneswar", "NIFT Bhubaneswar"),
    "biography": (
        "Fourteen years on Odisha's ikat clusters.",
        "Fourteen years on Odisha's ikat clusters.",
    ),
    "experienceYears": (14, 14),
    "localName": ("ଲତା ନାୟକ", "ଲତା ନାୟକ"),
    "designation": ("Assistant Professor", "Assistant Professor"),
    "department": ("Textile Design", "Textile Design"),
    "qualification": ("M.Des, NID Ahmedabad", "M.Des, NID Ahmedabad"),
    "specialisation": ("Natural dyeing", "Natural dyeing"),
    "phone": ("9876500001", "+91 9876500001"),
    "email": ("latha.nayak@nift.ac.in", "latha.nayak@nift.ac.in"),
    "website": ("https://nift.ac.in", "https://nift.ac.in"),
    "addressLine": ("Plot 3,\nChandaka", "Plot 3, Chandaka"),
    "city": ("Bhubaneswar", "Bhubaneswar"),
    "state": ("Odisha", "Odisha"),
    "pincode": ("751024", "751024"),
    "empanelmentNo": ("DCH/EMP/2024/0117", "DCH/EMP/2024/0117"),
    "empanelmentDate": (datetime(2026, 3, 14, 18, 45, tzinfo=UTC), "2026-03-14"),
    "photoMediaId": ("cm-photo", "cm-photo"),
    "signatureMediaId": ("cm-sig", "cm-sig"),
    "cvMediaId": ("cm-cv", "cm-cv"),
}


class _Profiles:
    def __init__(self, by_user: dict[str, object]) -> None:
        self.by_user = by_user
        self.includes: list[dict | None] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> object | None:
        self.includes.append(include)
        return self.by_user.get(where["userId"])


def _profile(
    location: object | None = None, account_name: str = "", **columns: Any
) -> SimpleNamespace:
    row = dict.fromkeys(PROFILE_FIELDS)
    row["phoneCountryCode"] = "+91"
    row.update(columns)
    return SimpleNamespace(**row, location=location, user=SimpleNamespace(name=account_name))


def _use(monkeypatch: pytest.MonkeyPatch, by_user: dict[str, object]) -> _Profiles:
    table = _Profiles(by_user)
    monkeypatch.setattr(designers, "db", SimpleNamespace(designerprofile=table))
    return table


def test_every_mapped_column_has_an_answer_here():
    assert sorted(_ANSWERS) == sorted(column for column, _ in PREFILL_MAP)


@pytest.mark.parametrize(("column", "field_key"), PREFILL_MAP, ids=[c for c, _ in PREFILL_MAP])
async def test_each_profile_column_reaches_its_stage_field(monkeypatch, column, field_key):
    stored, expected = _ANSWERS[column]
    _use(monkeypatch, {"u1": _profile(**{column: stored})})
    values = await designers.prefill_from_profile("u1")
    assert values.get(field_key) == expected


async def test_the_lookup_includes_the_location(monkeypatch):
    table = _use(monkeypatch, {"u1": _profile()})
    await designers.prefill_from_profile("u1")
    assert table.includes[-1] == {"user": True, "location": True}


_LOCATION = SimpleNamespace(
    address="12 Weavers Lane\nBagru",
    district="Jaipur",
    placeName="Bagru",
    state="Rajasthan",
    pincode="303007",
)


@pytest.mark.parametrize(
    ("field_key", "expected"),
    [
        ("designerAddress", "12 Weavers Lane Bagru"),
        ("designerCity", "Jaipur"),
        ("designerState", "Rajasthan"),
        ("designerPincode", "303007"),
    ],
)
async def test_the_location_answers_a_blank_address_column(monkeypatch, field_key, expected):
    _use(monkeypatch, {"u1": _profile(location=_LOCATION)})
    values = await designers.prefill_from_profile("u1")
    assert values[field_key] == expected


async def test_the_place_name_answers_the_city_when_there_is_no_district(monkeypatch):
    location = SimpleNamespace(
        address=None, district=" ", placeName="Bagru", state=None, pincode=None
    )
    _use(monkeypatch, {"u1": _profile(location=location)})
    values = await designers.prefill_from_profile("u1")
    assert values["designerCity"] == "Bagru"
    assert "designerState" not in values


async def test_a_typed_column_wins_over_the_location(monkeypatch):
    _use(
        monkeypatch,
        {
            "u1": _profile(
                location=_LOCATION,
                addressLine="Plot 3",
                city="Bhubaneswar",
                state="Odisha",
                pincode="751024",
            )
        },
    )
    values = await designers.prefill_from_profile("u1")
    assert (
        values["designerAddress"],
        values["designerCity"],
        values["designerState"],
        values["designerPincode"],
    ) == (
        "Plot 3",
        "Bhubaneswar",
        "Odisha",
        "751024",
    )


# --------------------------------------------------------------------------------------
# Whose profile a workshop reads
# --------------------------------------------------------------------------------------


def _workshop(designer_name: str = "", creator: str = "admin") -> SimpleNamespace:
    return SimpleNamespace(id="w1", designerName=designer_name, createdById=creator)


def _viewers(monkeypatch: pytest.MonkeyPatch, user_ids: list[str]) -> None:
    async def viewer_rows(_workshop_id: str) -> list[dict[str, Any]]:
        return [{"userId": uid, "name": uid} for uid in user_ids]

    monkeypatch.setattr(oversight.design_workshop_viewers, "viewer_rows", viewer_rows)


async def test_the_lead_designer_is_the_source(monkeypatch):
    _use(
        monkeypatch,
        {
            "lead": _profile(displayName="Rashmi Mohanty", institution="NID"),
            "other": _profile(displayName="Somebody Else"),
            "admin": _profile(displayName="The Admin"),
        },
    )
    _viewers(monkeypatch, ["lead", "other"])
    designer_id, values = await oversight.designer_prefill_for_workshop(_workshop("Rashmi Mohanty"))
    assert designer_id == "lead"
    assert values["designerInstitution"] == "NID"


async def test_the_one_named_designer_is_the_source_when_no_lead_is_known(monkeypatch):
    _use(monkeypatch, {"solo": _profile(institution="NIFT"), "admin": _profile(institution="IIT")})
    _viewers(monkeypatch, ["solo", "admin"])
    designer_id, values = await oversight.designer_prefill_for_workshop(_workshop(""))
    assert designer_id == "solo"
    assert values["designerInstitution"] == "NIFT"


async def test_the_creator_is_the_source_otherwise(monkeypatch):
    _use(monkeypatch, {"admin": _profile(institution="IIT Kharagpur")})
    _viewers(monkeypatch, ["a", "b"])
    designer_id, values = await oversight.designer_prefill_for_workshop(
        _workshop("", creator="admin")
    )
    assert designer_id == "admin"
    assert values["designerInstitution"] == "IIT Kharagpur"


async def test_a_source_with_no_profile_answers_nothing(monkeypatch):
    _use(monkeypatch, {})
    _viewers(monkeypatch, [])
    assert await oversight.designer_prefill_for_workshop(_workshop("", creator="ghost")) == (
        "ghost",
        {},
    )


# --------------------------------------------------------------------------------------
# The route
# --------------------------------------------------------------------------------------


async def test_the_route_answers_the_designer_and_the_non_empty_values(monkeypatch):
    seen: dict[str, Any] = {}

    async def load(workshop_id: str, user: Any) -> object:
        seen["load"] = (workshop_id, user)
        return SimpleNamespace(id=workshop_id)

    async def prefill(record: Any) -> tuple[str, dict[str, Any]]:
        seen["record"] = record.id
        return "u1", {"designerName": "Latha", "designerCity": "", "designerExperience": 0}

    monkeypatch.setattr(routes, "load_workshop_or_404", load)
    monkeypatch.setattr(oversight, "designer_prefill_for_workshop", prefill)
    answer = await routes.get_designer_prefill("w1", current_user="me")
    assert seen == {"load": ("w1", "me"), "record": "w1"}
    assert answer == {
        "designerId": "u1",
        "values": {"designerName": "Latha", "designerExperience": 0},
    }


async def test_the_route_refuses_what_the_workshop_loader_refuses(monkeypatch):
    from fastapi import HTTPException

    async def load(_workshop_id: str, _user: Any) -> object:
        raise HTTPException(status_code=404, detail="Not found")

    monkeypatch.setattr(routes, "load_workshop_or_404", load)
    with pytest.raises(HTTPException) as caught:
        await routes.get_designer_prefill("w1", current_user="me")
    assert caught.value.status_code == 404
