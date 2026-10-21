"""Rows written by one ``create_many`` share a ``createdAt``, so every read of them ends on ``id``.

A stage save inserts all its new ``DwStageEntry`` rows in one statement (and so one ``now()``), and a
single-row entity's rows all sit at ordinal 0; a custom-section plan inserts its new fields the same
way. An ``ORDER BY ordinal, "createdAt"`` therefore leaves those rows in whatever order Postgres
happens to return them, which can differ between two reads of the same workshop. These tests run the
readers over a fake table that answers ties in a different physical order each time and require the
same answer both times.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

SAME_INSTANT = datetime(2026, 10, 21, 9, 30, tzinfo=UTC)


def _clauses(order: Any) -> list[tuple[str, str]]:
    items = order if isinstance(order, list) else [order]
    return [next(iter(clause.items())) for clause in items]


class _Table:
    """``find_many`` that honours ``order`` and breaks anything it leaves open by physical order."""

    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.orders: list[Any] = []

    async def find_many(self, where: Any = None, order: Any = None, **_: Any) -> list[Any]:
        self.orders.append(order)
        rows = list(self.rows)
        for column, direction in reversed(_clauses(order or [])):
            rows.sort(key=lambda row, c=column: getattr(row, c), reverse=direction == "desc")
        return rows


def _entries() -> list[Any]:
    # Three rows of one save: same ordinal, same createdAt — only the id tells them apart.
    return [
        SimpleNamespace(id=f"ent_{n}", ordinal=0, createdAt=SAME_INSTANT, designWorkshopId="dw_1")
        for n in ("b", "c", "a")
    ]


def _read_twice(monkeypatch: Any, module: Any, delegate: str, read: Any) -> tuple[list[str], list[str], Any]:
    answers = []
    table = None
    for physical in (_entries(), list(reversed(_entries()))):
        table = _Table(physical)
        monkeypatch.setattr(module, "db", SimpleNamespace(**{delegate: table}))
        answers.append([row.id for row in asyncio.run(read())])
    return answers[0], answers[1], table.orders[-1]


def test_entry_rows_answers_one_save_s_rows_in_the_same_order_every_time(monkeypatch):
    from app.services import design_workshops as dw

    first, second, order = _read_twice(monkeypatch, dw, "dwstageentry", lambda: dw.entry_rows("dw_1"))
    assert first == second == ["ent_a", "ent_b", "ent_c"]
    assert _clauses(order)[-1] == ("id", "asc")


def test_the_data_browser_reads_one_save_s_rows_in_the_same_order_every_time(monkeypatch):
    from app.api.routes import data_browser

    first, second, order = _read_twice(
        monkeypatch, data_browser, "dwstageentry", lambda: data_browser._dw_entries("dw_1")
    )
    assert first == second == ["ent_a", "ent_b", "ent_c"]
    assert _clauses(order) == [("ordinal", "asc"), ("createdAt", "asc"), ("id", "asc")]


def test_custom_fields_of_one_plan_are_read_in_a_total_order(monkeypatch):
    from app.services import custom_sections

    section = SimpleNamespace(id="sec_1", sortOrder=0)
    fields = _Table([])
    monkeypatch.setattr(
        custom_sections,
        "db",
        SimpleNamespace(dwcustomsection=_Table([section]), dwcustomfield=fields),
    )
    monkeypatch.setattr(custom_sections, "_section_from_row", lambda row, specs: row)
    monkeypatch.setattr(custom_sections, "custom_schema_version", lambda specs: "v")
    asyncio.run(custom_sections.load_definition("dw_1"))
    assert _clauses(fields.orders[-1])[-1] == ("id", "asc")
