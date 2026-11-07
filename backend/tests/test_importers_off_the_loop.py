from __future__ import annotations

import asyncio
import inspect
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import (
    annual_plan,
    design_workshop_oversight,
    design_workshop_roster,
    sanction_orders,
)


@pytest.fixture
def worker(monkeypatch):
    seen = SimpleNamespace(names=[], inside=False)

    async def to_thread(func: Any, /, *args: Any, **kwargs: Any) -> Any:
        seen.names.append(getattr(func, "__name__", repr(func)))
        seen.inside = True
        try:
            return func(*args, **kwargs)
        finally:
            seen.inside = False

    def builder(name: str):
        def build(*_args: Any, **_kwargs: Any) -> bytes:
            assert seen.inside, f"{name} ran on the event loop"
            return b"workbook"

        build.__name__ = name
        return build

    monkeypatch.setattr(asyncio, "to_thread", to_thread)
    seen.builder = builder
    return seen


def test_the_annual_plan_pro_forma_is_built_off_the_event_loop(worker, monkeypatch):
    monkeypatch.setattr(annual_plan, "build_annual_plan_pro_forma", worker.builder("build_annual_plan_pro_forma"))
    answer = asyncio.run(annual_plan.download_pro_forma(None))
    assert answer.body == b"workbook"
    assert worker.names == ["build_annual_plan_pro_forma"]


def test_the_sanction_pro_forma_is_built_off_the_event_loop(worker, monkeypatch):
    monkeypatch.setattr(sanction_orders, "build_sanction_pro_forma", worker.builder("build_sanction_pro_forma"))
    answer = asyncio.run(sanction_orders.download_sanction_pro_forma(None))
    assert answer.body == b"workbook"
    assert worker.names == ["build_sanction_pro_forma"]


def test_the_artisan_pro_forma_is_built_off_the_event_loop_from_both_doors(worker, monkeypatch):
    workshop = SimpleNamespace(id="dw1", title="Weaving")

    async def loaded(*_args: Any, **_kwargs: Any) -> Any:
        return workshop

    monkeypatch.setattr(design_workshop_oversight, "build_artisan_pro_forma", worker.builder("build_artisan_pro_forma"))
    monkeypatch.setattr(design_workshop_oversight, "pro_forma_filename", lambda _w: "artisans.xlsx")
    monkeypatch.setattr(design_workshop_roster, "build_artisan_pro_forma", worker.builder("build_artisan_pro_forma"))
    monkeypatch.setattr(design_workshop_roster, "pro_forma_filename", lambda _w: "artisans.xlsx")
    monkeypatch.setattr(design_workshop_roster, "_require_designer", lambda _user: None)
    monkeypatch.setattr(design_workshop_roster, "load_workshop_or_404", loaded)
    assert asyncio.run(design_workshop_oversight.download_artisan_pro_forma(None, None)).body == b"workbook"
    assert asyncio.run(design_workshop_roster.download_roster_pro_forma("dw1", SimpleNamespace(id="u"))).body == b"workbook"
    assert worker.names == ["build_artisan_pro_forma", "build_artisan_pro_forma"]


@pytest.mark.parametrize(
    ("module", "function", "parser"),
    [
        (annual_plan, "upload_annual_plan", "parse_annual_plan_workbook"),
        (sanction_orders, "upload_sanction_orders", "parse_sanction_workbook"),
        (design_workshop_oversight, "run_artisan_import", "parse_artisan_workbook"),
    ],
)
def test_every_importer_parses_its_workbook_off_the_event_loop(module, function, parser):
    source = " ".join(inspect.getsource(getattr(module, function)).split())
    assert f"asyncio.to_thread( {parser}," in source
    assert source.count(f"{parser}(") == 0
