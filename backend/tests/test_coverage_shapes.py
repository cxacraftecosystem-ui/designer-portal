"""THE COVERAGE MAP'S DISTRICT SHAPES, held to the register and to the official depiction of India.

``scripts/build_boundaries.py --shapes`` refuses to write a file that fails these checks; this runs
the same checks over the COMMITTED file, so a hand edit or a stale file fails the suite too.

* the file decodes, in the outline's own polyline encoding;
* every district code is a register code of the state it claims;
* Gilgit and Aksai Chin fall inside Ladakh or the base fill, Muzaffarabad inside Jammu and Kashmir or
  the base fill, and all three inside the national outline;
* no polygon vertex lies more than 0.03 degrees outside the national outline;
* every centroid lies inside its own polygon, and the server's centroid file agrees with the web's.

No database.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib

import pytest

from app.services import coverage, lgd

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHAPES = ROOT / "frontend" / "public" / "boundaries" / "district-shapes.json"


def _script():
    spec = importlib.util.spec_from_file_location("build_boundaries", ROOT / "scripts" / "build_boundaries.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def shapes() -> dict:
    if not SHAPES.is_file():
        pytest.skip("no district-shapes.json in this checkout")
    return json.loads(SHAPES.read_text(encoding="utf-8"))


def test_the_shapes_file_decodes(shapes):
    script = _script()
    assert shapes["scale"] == script.SCALE
    assert len(shapes["districts"]) >= 700
    for row in shapes["districts"][:50]:
        for ring in row["rings"]:
            points = script.decode_ring(ring)
            assert len(points) >= 4 and points[0] == points[-1]


def test_every_check_the_build_runs_still_passes(shapes):
    assert _script().verify_shapes(shapes["districts"]) == []


def test_codes_match_the_register(shapes):
    reg = lgd.register()
    for row in shapes["districts"]:
        assert reg.district_row[row["lgd"]][0] == row["stateLgd"]


def test_the_server_centroids_are_the_web_centroids(shapes):
    server = coverage.centroids()
    assert server, "backend/app/data/boundaries/district-centroids.json is missing"
    for row in shapes["districts"]:
        assert server[row["lgd"]] == tuple(row["centroid"])


def test_the_shapes_stay_small_and_versioned(shapes):
    assert SHAPES.stat().st_size <= 400_000
    manifest = json.loads((ROOT / "frontend/public/boundaries/manifest.json").read_text(encoding="utf-8"))
    assert manifest["shapesVersion"] == shapes["version"] == coverage.boundaries_info()["shapesVersion"]
    assert len(manifest["shapesSourceSha256"]) == 64
