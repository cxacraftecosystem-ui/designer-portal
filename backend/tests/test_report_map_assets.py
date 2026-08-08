"""The boundary geometry the server draws its locator map from, checked where the server looks.

`test_boundary_assets.py` checks that the CLIENTS' assets decode. This file checks the one thing
that made a correctly-encoded asset useless: the deployed API image carries neither ``frontend/``
nor ``android/``, so every path the map reader knew about resolved to nothing inside the
container. Section 6, "Where the workshop was held and where its artisans live", printed as a
numbered heading followed immediately by section 7 — in both the .docx and the PDF, on a report
whose MAP block was fully populated and whose web preview showed the map the designer signed off
on. The only signal was "1 photograph(s) could not be included in the file", on workshops with no
photographs at all.

The fix is three committed files under ``backend/app/data/boundaries``, which is inside the image
because the Dockerfile copies ``backend/app`` wholesale. Three copies of one generated file is a
drift risk, so the copies are compared here rather than hoped about.
"""

import pathlib

import pytest

from app.services import report_map

BACKEND = pathlib.Path(__file__).resolve().parents[1]
ROOT = BACKEND.parent
PACKAGE = BACKEND / "app" / "data" / "boundaries"
WEB = ROOT / "frontend" / "public" / "boundaries"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "res" / "raw"

#: What the reader asks for, and where the same bytes live in the two client trees.
SHIPPED = (
    ("india_outline.bin", ANDROID / "india_outline.bin"),
    ("state-borders.txt", WEB / "state-borders.txt"),
    ("district-borders.txt", WEB / "district-borders.txt"),
)


@pytest.mark.parametrize(("name", "_twin"), SHIPPED, ids=[n for n, _ in SHIPPED])
def test_the_geometry_is_inside_the_python_package(name, _twin):
    """THE REGRESSION. ``backend/app`` is the only tree the runtime image carries, so an asset
    anywhere else is an asset the deployed server does not have."""
    asset = PACKAGE / name
    assert asset.is_file(), (
        f"{asset} is missing: the deployed image would print the locator map's section empty"
    )
    assert asset.stat().st_size > 1024, "a truncated asset decodes to nothing and draws nothing"


@pytest.mark.parametrize(("name", "twin"), SHIPPED, ids=[n for n, _ in SHIPPED])
def test_the_packaged_copy_is_the_clients_copy_byte_for_byte(name, twin):
    """One generated file in three places. A regeneration that updates the web and the phone and
    leaves the server behind would draw a DIFFERENT India in the submitted report than in the
    preview the designer approved — which is worse than the empty section, because it looks
    right. ``scripts/build_boundaries.py`` writes the two client copies and must write this one.
    """
    if not twin.is_file():
        pytest.skip(f"{twin} is not in this checkout")
    assert (PACKAGE / name).read_bytes() == twin.read_bytes(), (
        f"{name} has drifted from {twin}; regenerate all three from scripts/build_boundaries.py"
    )


def test_the_map_reader_finds_its_assets_with_only_the_package_directory(monkeypatch):
    """The container, simulated: the two repository paths do not exist there.

    Asserted through ``_asset_dirs`` and the readers rather than by reading the files directly,
    because the failure was never that the bytes were unreadable — it was that nothing looked
    where they were.
    """
    monkeypatch.setattr(report_map, "_repo_root", lambda: pathlib.Path("/nonexistent-checkout"))
    monkeypatch.delenv("REPORT_MAP_ASSET_DIR", raising=False)
    # The decoded geometry is memoised process-wide, so a cached copy read from the checkout would
    # answer for the container this test is pretending to be.
    report_map._CACHE.clear()
    try:
        outline = report_map.india_rings()
        assert outline, "the coastline is what makes the figure a map of India rather than dots"
        assert sum(len(line) for line in outline) > 1000
        assert report_map.state_borders(), "state borders resolved to nothing"
        assert report_map.district_borders(), "district borders resolved to nothing"
    finally:
        report_map._CACHE.clear()


def test_a_map_still_renders_when_the_geometry_is_genuinely_absent(monkeypatch, tmp_path):
    """The other half, unchanged and load-bearing: a report must never fail to generate because a
    decorative map is unavailable. A designer in a field waiting on a submission deadline needs
    the document far more than they need the figure."""
    monkeypatch.setattr(report_map, "_asset_dirs", lambda: [tmp_path])
    report_map._CACHE.clear()
    try:
        assert report_map.india_rings() == []
    finally:
        # The decoded geometry is memoised process-wide, so a test that poisoned the cache would
        # fail every later test in the session with an empty map rather than with anything about
        # itself.
        report_map._CACHE.clear()
