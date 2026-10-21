"""Every craft picker prints "Category › Sub-category" — checked without a database.

Two halves. ``craft_master.build_catalogue`` is what the shared pickers (``CraftSelect`` on the web,
``CraftPickerField`` on Android, and the Android offline cache that stores the same JSON) read, so
every craft row must carry ``categoryId`` and ``subCategoryId`` and every category its
``subCategories``. And the stage-1 craft reference picker's sublabel must print the same path.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace

from app.services import craft_master
from app.services.design_workshops import REFERENCE_MODELS, _craft_path

STAMP = datetime(2026, 10, 21, tzinfo=UTC)


class _Table:
    def __init__(self, rows, groups=None):
        self.rows = rows
        self.groups = groups or []

    async def find_many(self, **_kwargs):
        return self.rows

    async def group_by(self, **_kwargs):
        return self.groups


def _world():
    embroidery = SimpleNamespace(id="sub-emb", slug="Embroidery", name="Embroidery", sortOrder=1, updatedAt=STAMP)
    block = SimpleNamespace(id="sub-blk", slug="Hand-Block", name="Hand Block Printing", sortOrder=2, updatedAt=STAMP)
    textiles = SimpleNamespace(
        id="cat-tex", slug="Textiles", name="Textiles", sortOrder=1, updatedAt=STAMP, subCategories=[block, embroidery]
    )
    crafts = [
        SimpleNamespace(
            id=f"craft-{n}", slug=f"Craft-{n}", name=name, localName=None, subCategoryId=sub, sourceSerial=n,
            stateOfOrigin=[], stateOfPractice=["Gujarat"], giStatus="GI", isCatchAll=False, craftCode=None,
            extraMetadata=None, status="ACTIVE", updatedAt=STAMP,
        )
        for n, (name, sub) in enumerate([("Ajrakh", "sub-blk"), ("Kantha", "sub-emb")], start=1)
    ]
    return SimpleNamespace(
        craftcategory=_Table([textiles]),
        craft=_Table(crafts, groups=[{"subCategoryId": "sub-blk", "_count": {"_all": 1}}]),
        craftspelling=_Table([]),
    )


def test_every_catalogue_craft_carries_its_category_and_sub_category():
    catalogue = asyncio.run(craft_master.build_catalogue(_world()))
    (category,) = catalogue["categories"]
    assert [s["name"] for s in category["subCategories"]] == ["Embroidery", "Hand Block Printing"]
    by_sub = {s["id"]: s for s in category["subCategories"]}
    assert by_sub["sub-blk"]["craftCount"] == 1
    for craft in catalogue["crafts"]:
        assert craft["categoryId"] == "cat-tex"
        assert craft["subCategoryId"] in by_sub


def _craft(*, sub=None, category=None, legacy=None, place=None):
    sub_row = None
    if sub is not None:
        sub_row = SimpleNamespace(name=sub, category=SimpleNamespace(name=category) if category else None)
    return SimpleNamespace(name="Ajrakh", category=legacy, place=place, subCategory=sub_row)


def test_the_craft_reference_sublabel_is_the_category_path():
    spec = REFERENCE_MODELS["Craft"]
    assert spec.include["subCategory"] == {"include": {"category": True}}
    row = _craft(sub="Hand Block Printing", category="Textiles", legacy="Textiles", place="Kutch")
    assert spec.sublabel(row) == "Textiles › Hand Block Printing · Kutch"


def test_a_craft_off_the_list_falls_back_to_its_category_copy():
    assert _craft_path(_craft(legacy="Metal")) == "Metal"
    assert _craft_path(_craft()) is None
    assert REFERENCE_MODELS["Craft"].sublabel(_craft(place="Bagru")) == "Bagru"
