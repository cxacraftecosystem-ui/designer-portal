import pathlib

import pytest

from app.services import report_map

BACKEND = pathlib.Path(__file__).resolve().parents[1]
ROOT = BACKEND.parent
PACKAGE = BACKEND / "app" / "data" / "boundaries"
WEB = ROOT / "frontend" / "public" / "boundaries"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "res" / "raw"

SHIPPED = (
    ("india_outline.bin", ANDROID / "india_outline.bin"),
    ("state-borders.txt", WEB / "state-borders.txt"),
    ("district-borders.txt", WEB / "district-borders.txt"),
)


@pytest.mark.parametrize(("name", "_twin"), SHIPPED, ids=[n for n, _ in SHIPPED])
def test_the_geometry_is_inside_the_python_package(name, _twin):
    asset = PACKAGE / name
    assert asset.is_file(), (
        f"{asset} is missing: the deployed image would print the locator map's section empty"
    )
    assert asset.stat().st_size > 1024, "a truncated asset decodes to nothing and draws nothing"


@pytest.mark.parametrize(("name", "twin"), SHIPPED, ids=[n for n, _ in SHIPPED])
def test_the_packaged_copy_is_the_clients_copy_byte_for_byte(name, twin):
    if not twin.is_file():
        pytest.skip(f"{twin} is not in this checkout")
    assert (PACKAGE / name).read_bytes() == twin.read_bytes(), (
        f"{name} has drifted from {twin}; regenerate all three from scripts/build_boundaries.py"
    )


def test_the_map_reader_finds_its_assets_with_only_the_package_directory(monkeypatch):
    monkeypatch.setattr(report_map, "_repo_root", lambda: pathlib.Path("/nonexistent-checkout"))
    monkeypatch.delenv("REPORT_MAP_ASSET_DIR", raising=False)
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
    monkeypatch.setattr(report_map, "_asset_dirs", lambda: [tmp_path])
    report_map._CACHE.clear()
    try:
        assert report_map.india_rings() == []
    finally:
        report_map._CACHE.clear()
