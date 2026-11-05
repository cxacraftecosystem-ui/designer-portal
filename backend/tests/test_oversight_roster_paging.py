from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import design_workshop_oversight as oversight


class _Artisans:
    def __init__(self, count: int) -> None:
        start = datetime(2026, 1, 1, tzinfo=UTC)
        self.rows = [
            SimpleNamespace(
                id=f"a{index:03d}",
                name=f"Artisan {index}",
                place="Bagru",
                craft=None,
                status="APPROVED",
                createdAt=start + timedelta(minutes=index),
                designWorkshopId="dw1",
            )
            for index in range(1, count + 1)
        ]

    async def find_many(self, *, where: dict[str, Any], skip: int = 0, take: int, **_: Any) -> list[Any]:
        ordered = sorted(
            (row for row in self.rows if row.designWorkshopId == where["designWorkshopId"]),
            key=lambda row: (-row.createdAt.timestamp(), row.id),
        )
        return ordered[skip : skip + take]

    async def count(self, *, where: dict[str, Any]) -> int:
        return sum(1 for row in self.rows if row.designWorkshopId == where["designWorkshopId"])


@pytest.mark.asyncio
async def test_the_roster_pages_to_its_last_artisan(monkeypatch):
    fake = SimpleNamespace(artisan=_Artisans(oversight.ROSTER_TAKE * 2 + 31))
    monkeypatch.setattr(oversight, "db", fake)
    seen: list[str] = []
    offset: int | None = 0
    pages = 0
    while offset is not None:
        page = await oversight.linked_artisan_rows("dw1", offset)
        assert page["total"] == oversight.ROSTER_TAKE * 2 + 31
        seen.extend(row["id"] for row in page["artisans"])
        offset = page["nextOffset"]
        pages += 1
    assert pages == 3
    assert len(seen) == len(set(seen)) == oversight.ROSTER_TAKE * 2 + 31
    assert "a001" in seen


@pytest.mark.asyncio
async def test_without_an_offset_the_first_page_keeps_its_old_shape(monkeypatch):
    monkeypatch.setattr(oversight, "db", SimpleNamespace(artisan=_Artisans(3)))
    page = await oversight.linked_artisan_rows("dw1")
    assert page["truncated"] is False and page["nextOffset"] is None
    assert [row["name"] for row in page["artisans"]] == ["Artisan 3", "Artisan 2", "Artisan 1"]
