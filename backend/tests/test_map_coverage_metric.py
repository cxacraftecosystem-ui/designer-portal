"""The coverage metric has one vocabulary in both portals: ``workshops``, ``artisans`` and ``all``.

``records`` was this portal's name for ``all``. Saved links still carry it, so the route accepts it
and always answers ``all``. Pure: no database.
"""

from __future__ import annotations

import pytest

from app.api.routes.map_coverage import COVERAGE_METRICS, normalise_metric


def test_the_metric_names_are_the_shared_ones() -> None:
    assert COVERAGE_METRICS == ("workshops", "artisans", "all")


@pytest.mark.parametrize(
    ("asked", "answered"),
    [
        ("records", "all"),
        ("RECORDS", "all"),
        ("all", "all"),
        ("artisans", "artisans"),
        ("workshops", "workshops"),
        (None, "workshops"),
        ("", "workshops"),
        ("crafts", "workshops"),
    ],
)
def test_records_is_read_as_all_and_nothing_else_is_guessed(asked: str | None, answered: str) -> None:
    assert normalise_metric(asked) == answered
