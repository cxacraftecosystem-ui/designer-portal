"""``GET /export/dataset`` — where each record lands in the zip, and that nothing is left out.

THE MODULE'S HEADER PROMISES "an _Unlinked area so nothing is dropped" AND ARTISANS WERE DROPPED.
They are emitted in exactly one place — inside ``for ws in workshops:``, under the artisans that
workshop reaches — and the ``_Unlinked`` fallbacks covered products, tools and interviews and not
artisans. ``workshop_reaches_artisan`` evaluates three OR terms (``Artisan.workshopId``, a
``WorkshopArtisan`` row, and the artisan's craft being on the workshop's declared list); an artisan
matching none of them was reached by no workshop and therefore appeared nowhere in the tree: no
folder, no ``details.txt``, and none of their own photographs.

Both of the columns that would have saved them are optional on create (``ArtisanCreate``: "The
workshop this artisan was documented at. Optional everywhere") and nullable in the schema, so this is
an ordinary record rather than an exotic one. And their media was not merely absent — it was FETCHED:
``media_or`` includes ``{"artisanId": {"in": ids}}`` over ALL artisans, ``_media_slot`` keys it at
``("artisan", id)``, and ``add_media`` was never called with that id. The rows were read out of
Postgres and thrown away, uncounted by ``totalMedia`` and unflagged by ``truncated``, so the client
presented the download as complete.

NOTHING HERE TOUCHES A DATABASE. ``export.db`` is replaced with delegates over fixed rows and the
route function is awaited directly, because the behaviour under test is the SHAPE OF THE TREE — a
pure function of the rows once they are loaded. That makes it a second-long test rather than a
minute-long one, and it lets a case be built (an artisan with no workshop, no craft, and a
photograph) that would otherwise need five inserts and a media upload.

The one thing this cannot assert is the query: that ``media_or`` really fetches an unlinked artisan's
media is taken from the route's own ``for fk, tags, rows in (...)`` loop over ALL artisans, which is
read here rather than exercised.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the stage registry
from app.api.routes import export
from app.services import design_workshop_data as dwd
from app.services.rich_text import Mark, RichBlock, RichDoc, RichSpan, to_stored_text


class Row(SimpleNamespace):
    """A record row that answers ``None`` for any column the test did not set.

    The field registry reads its columns by plain attribute access (``a.localName``, ``a.address``,
    ``a.dos``), because on a real Prisma row they always exist. A bare ``SimpleNamespace`` would
    raise ``AttributeError`` on the first unset one and the test would be about the fixture instead
    of about the tree.
    """

    def __getattr__(self, name: str) -> Any:  # only reached when the attribute is not set
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _Delegate:
    """One Prisma model delegate, answering a fixed list however it is queried."""

    def __init__(self, rows: list[Any]) -> None:
        self._rows = rows

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return list(self._rows)

    async def count(self, **_kwargs: Any) -> int:
        """How many rows there are, for the professor's ``NOT INCLUDED.txt``.

        The only ``count`` this route issues, and only on the refusal path — see ``DW_WITHHELD_FILE``
        in ``export.py``. A delegate that could not answer it would make that branch untestable.
        """
        return len(self._rows)


class _DB:
    def __init__(self, **tables: list[Any]) -> None:
        for name in (
            "workshop",
            "artisan",
            "productdocumentation",
            "tooldocumentation",
            "questionnaireinterview",
            "process",
            "mediafile",
            # THE DESIGN-WORKSHOP PAIR, ADDED 2026-08-31 WITH THE HALF OF THE ARCHIVE THAT READS
            # THEM. They are listed here rather than left to ``tables.get`` on an unknown attribute
            # because ``_DB`` answers by ATTRIBUTE and a missing one is an ``AttributeError`` inside
            # the route, which reads as the route being broken rather than as the fixture being
            # short.
            "designworkshop",
            "dwstageentry",
        ):
            setattr(self, name, _Delegate(tables.get(name, [])))


ADMIN = Row(id="user-1", role="ADMIN", email="admin@example.test", name="Admin")


async def _manifest(monkeypatch, **tables: list[Any]) -> dict[str, Any]:
    async def _open_where(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(export, "db", _DB(**tables))
    monkeypatch.setattr(export, "owned_or_granted_where", _open_where)
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)
    return await export.dataset_manifest(current_user=ADMIN)


def _paths(manifest: dict[str, Any]) -> list[str]:
    return [f["path"] for f in manifest["files"]]


# --------------------------------------------------------------------------------------
# Fixtures: one artisan no workshop reaches, and one it does
# --------------------------------------------------------------------------------------


def _stranger() -> Row:
    """The ordinary record the tree had no place for: no workshop, no craft."""
    return Row(id="a-stranger", name="Kamla Devi", place="Bagru", workshopId=None, craftId=None)


def _reached() -> Row:
    return Row(id="a-reached", name="Sita Bai", place="Sanganer", workshopId="w-1", craftId=None)


def _workshop() -> Row:
    return Row(id="w-1", title="Block printing workshop", place="Bagru", crafts=[], artisans=[])


def _photo(artisan_id: str, filename: str = "portrait.jpg") -> Row:
    return Row(
        id=f"m-{artisan_id}",
        artisanId=artisan_id,
        originalFilename=filename,
        url=f"https://objects.test/{artisan_id}/{filename}",
        linkedRecordType=None,
        linkedRecordId=None,
    )


# --------------------------------------------------------------------------------------
# 1. The artisan nobody reaches
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("with_workshop", [False, True])
def test_an_artisan_no_workshop_reaches_still_gets_a_folder(monkeypatch, with_workshop):
    """THE DEFECT. Parametrised over an empty repository AND one with a workshop in it, because the
    fallback must not be an accident of there being no workshop loop to run."""
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            workshop=[_workshop()] if with_workshop else [],
            artisan=[_stranger()],
        )
    )
    assert "_Unlinked/_Artisans/Kamla Devi/details.txt" in _paths(manifest)


def test_their_own_photographs_are_emitted_and_counted(monkeypatch):
    """The rows were FETCHED and discarded: keyed into ``media_by`` at ``("artisan", id)`` and never
    asked for. ``totalMedia`` counted only what was emitted, so the client had no way to know a file
    had been left out — the download presented itself as complete."""
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            artisan=[_stranger()],
            mediafile=[_photo("a-stranger"), _photo("a-stranger", "loom.jpg")],
        )
    )
    paths = _paths(manifest)
    assert "_Unlinked/_Artisans/Kamla Devi/portrait.jpg" in paths
    assert "_Unlinked/_Artisans/Kamla Devi/loom.jpg" in paths
    assert manifest["totalMedia"] == 2


def test_an_artisan_a_workshop_does_reach_is_filed_under_it_and_not_twice(monkeypatch):
    """The other half of the fallback, and the one a careless fix breaks: ``placed_artisans`` must be
    written where the workshop tree emits, or every artisan is emitted a second time under
    ``_Unlinked`` and the zip carries two copies of everybody's details.txt."""
    manifest = asyncio.run(
        _manifest(monkeypatch, workshop=[_workshop()], artisan=[_reached(), _stranger()])
    )
    paths = _paths(manifest)
    assert "Workshops/Block printing workshop/No craft/Sita Bai/details.txt" in paths
    # Neither spelling: not the section an unlinked artisan would land in, and not the flat grouping
    # bucket either. A second details.txt for the same person is the failure, wherever it sits.
    assert "_Unlinked/_Artisans/Sita Bai/details.txt" not in paths
    assert "_Unlinked/Sita Bai/details.txt" not in paths
    assert "_Unlinked/_Artisans/Kamla Devi/details.txt" in paths


def test_two_unlinked_artisans_sharing_a_name_do_not_share_a_folder(monkeypatch):
    """The folder is a RECORD here, not a grouping label, so it goes through ``_uniq``. Merging them
    would put one person's details.txt and photographs in the other's folder — and ``details.txt``
    would silently win once and be renamed once, which is worse than either."""
    twin = Row(id="a-twin", name="Kamla Devi", place="Chittorgarh", workshopId=None, craftId=None)
    manifest = asyncio.run(_manifest(monkeypatch, artisan=[_stranger(), twin]))
    paths = _paths(manifest)
    assert "_Unlinked/_Artisans/Kamla Devi/details.txt" in paths
    assert "_Unlinked/_Artisans/Kamla Devi (2)/details.txt" in paths


# --------------------------------------------------------------------------------------
# 2. …and their records come with them
# --------------------------------------------------------------------------------------


def test_an_unlinked_artisans_records_nest_under_them_rather_than_scattering(monkeypatch):
    """Their products, tools and interviews used to land in the FLAT ``_Unlinked/<artisanName>``
    bucket keyed off a denormalised string column, beside an artisan folder that did not exist. They
    now hang off the artisan's own folder through the very same ``emit_*`` helpers, so an unlinked
    artisan's subtree is shaped exactly like a linked one's.

    The ordering is what does it: this pass runs BEFORE the three flat fallbacks and records each id
    in ``placed_*``, so nothing is written twice."""
    product = Row(
        id="p-1",
        productName="Block-printed stole",
        artisanId="a-stranger",
        artisanName="Kamla Devi",
        workshopId=None,
    )
    tool = Row(
        id="t-1",
        toolkitName="Carved block set",
        artisanId="a-stranger",
        artisanName="Kamla Devi",
        workshopId=None,
    )
    interview = Row(
        id="i-1",
        title="Dyeing the ground",
        artisans=[Row(artisanId="a-stranger", artisan=Row(name="Kamla Devi"))],
        responses=[],
    )
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            artisan=[_stranger()],
            productdocumentation=[product],
            tooldocumentation=[tool],
            questionnaireinterview=[interview],
        )
    )
    paths = _paths(manifest)
    assert "_Unlinked/_Artisans/Kamla Devi/Products/Block-printed stole/details.txt" in paths
    assert "_Unlinked/_Artisans/Kamla Devi/Tools/Carved block set/details.txt" in paths
    assert any(p.startswith("_Unlinked/_Artisans/Kamla Devi/Questionnaires/") for p in paths)
    # Exactly one copy of each: the flat fallbacks below must have skipped what was just nested.
    assert sum(1 for p in paths if p.endswith("/Block-printed stole/details.txt")) == 1
    assert sum(1 for p in paths if p.endswith("/Carved block set/details.txt")) == 1


def test_a_record_whose_artisan_is_filed_under_a_workshop_still_uses_the_flat_bucket(monkeypatch):
    """The three flat fallbacks are NOT superseded and must not be deleted while tidying: a product
    attached to no workshop whose artisan IS filed under one has no folder of its own to hang from,
    and dropping this loop would lose it exactly as artisans were being lost."""
    product = Row(
        id="p-2",
        productName="Indigo yardage",
        artisanId="a-reached",
        artisanName="Sita Bai",
        workshopId=None,
    )
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            workshop=[_workshop()],
            artisan=[_reached()],
            productdocumentation=[product],
        )
    )
    assert "_Unlinked/Sita Bai/Products/Indigo yardage/details.txt" in _paths(manifest)


def test_a_stray_record_is_never_absorbed_into_an_unlinked_artisans_folder(monkeypatch):
    """THE COLLISION BETWEEN THE TWO KINDS OF ``_Unlinked`` FOLDER, and it misattributes work.

    ``_Unlinked/<name>`` is a GROUPING LABEL built from the denormalised ``artisanName`` STRING on a
    product or tool — never resolved to an artisan row, and deliberately not uniqued so that every
    orphan record naming that string shares one folder. The unlinked-artisan folder is a RECORD: one
    person, uniqued, carrying their details.txt and their photographs. While both were spelled
    ``_Unlinked/<name>`` the first took the path and the second walked into it, because the flat
    buckets are emitted after the artisan pass and never go through ``_uniq``.

    Two rows here reach that bucket and NEITHER belongs to the unlinked woman:
      * a product of a DIFFERENT Kamla Devi — the one filed under a workshop — whose product is
        attached to no workshop and so has no folder of its own to hang from;
      * a product carrying the string with no ``artisanId`` at all, which is ordinary: the column is
        a typed-in name and predates the artisan row it may or may not have.

    THE ASSERTION IS DELIBERATELY WRITTEN AGAINST THE FOLDER THE MANIFEST ACTUALLY CHOSE, found by
    the details.txt that names her place, rather than against a hard-coded path. A test that spelled
    the new section name would fail on the old code for the wrong reason — because the folder moved,
    not because a stranger's product was inside it — and would stop meaning anything the next time
    somebody renames the section. This way it says the one thing that must stay true: nothing that is
    not hers is under her folder.
    """
    twin = Row(id="a-twin", name="Kamla Devi", place="Sanganer", workshopId="w-1", craftId=None)
    hers_not = Row(
        id="p-twin",
        productName="Indigo yardage",
        artisanId="a-twin",
        artisanName="Kamla Devi",
        workshopId=None,
    )
    nobodys = Row(
        id="p-loose",
        productName="Mud-resist scarf",
        artisanId=None,
        artisanName="Kamla Devi",
        workshopId=None,
    )
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            workshop=[_workshop()],
            artisan=[_stranger(), twin],
            productdocumentation=[hers_not, nobodys],
        )
    )
    paths = _paths(manifest)

    # Her own folder, wherever the manifest put it: the only ``_Unlinked`` details.txt that carries
    # her place. The twin is filed under the workshop, so hers is the only one down here.
    stranger_dir = next(
        f["path"][: -len("/details.txt")]
        for f in manifest["files"]
        if f["path"].startswith("_Unlinked")
        and f["path"].endswith("/details.txt")
        and "Bagru" in (f.get("content") or "")
    )
    intruders = [
        p
        for p in paths
        if p.startswith(f"{stranger_dir}/")
        and ("Indigo yardage" in p or "Mud-resist scarf" in p)
    ]
    assert not intruders, f"another artisan's records were filed inside {stranger_dir}: {intruders}"

    # …and nothing was lost on the way out of her folder: both still land in the flat bucket, which
    # is the only home a record with no artisan folder of its own has.
    assert "_Unlinked/Kamla Devi/Products/Indigo yardage/details.txt" in paths
    assert "_Unlinked/Kamla Devi/Products/Mud-resist scarf/details.txt" in paths
    # And she still has her own subtree — the finding this collision was introduced by fixing.
    assert f"{stranger_dir}/details.txt" in paths


# --------------------------------------------------------------------------------------
# 4. Media rows the manifest could not address, and the order the caps cut on
# --------------------------------------------------------------------------------------
#
# ``add_media`` emitted a file only ``if m.url``, and ``MediaFile.url`` is nullable: ``url`` is set
# from ``s3.public_url_for_key``, which returns None whenever neither ``AWS_S3_PUBLIC_BASE_URL`` nor
# ``AWS_S3_ENDPOINT`` is configured, and ``complete_media_upload`` stores that None verbatim. Any row
# written while the public base URL was momentarily unset, or by a backfill, carries a null url —
# rows ``/data/media/{id}/download`` streams correctly one at a time from ``objectKey``, proving the
# bytes are there. The manifest dropped them with ``totalMedia`` counting only the survivors and
# ``truncated: false``, so "the zip is complete" and "N files could not be addressed" arrived as the
# same sentence.


def _keyed_photo(artisan_id: str, filename: str, *, url: str | None, key: str | None) -> Row:
    return Row(
        id=f"m-{filename}",
        artisanId=artisan_id,
        originalFilename=filename,
        mimeType="image/jpeg",
        url=url,
        objectKey=key,
        linkedRecordType=None,
        linkedRecordId=None,
    )


def _signing(monkeypatch) -> None:
    """``presign_get_url`` replaced by a recognisable stand-in — no AWS credentials in a unit test."""
    monkeypatch.setattr(
        export,
        "presign_get_url",
        lambda key, *, filename, mime_type, expires_in: f"https://signed.test/{key}?exp={expires_in}",
    )


def test_a_media_row_with_no_url_is_signed_from_its_object_key(monkeypatch):
    """THE DEFECT. The row was fetched, keyed into ``media_by`` and dropped by an ``if`` with no else.

    ``download_media``, ``datasets._presign_media_row`` and ``MediaIndex.prefetch`` all fall back to
    ``objectKey``; the manifest was the one reader that did not.
    """
    _signing(monkeypatch)
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            artisan=[_stranger()],
            mediafile=[_keyed_photo("a-stranger", "portrait.jpg", url=None, key="media/u/1/p.jpg")],
        )
    )
    emitted = {f["path"]: f.get("url") for f in manifest["files"] if "url" in f}
    assert "_Unlinked/_Artisans/Kamla Devi/portrait.jpg" in emitted
    assert emitted["_Unlinked/_Artisans/Kamla Devi/portrait.jpg"].startswith("https://signed.test/media/u/1/p.jpg")
    assert manifest["totalMedia"] == 1
    assert manifest["skippedMedia"] == 0


def test_a_signing_failure_is_counted_and_does_not_500_the_whole_archive(monkeypatch):
    """THE REACHABLE VERSION OF THE RESIDUAL CASE. ``objectKey`` is NOT NULL on MediaFile, so the
    row that cannot be addressed is almost always one whose key could not be SIGNED — a deployment
    with no object storage configured, where ``presign_get_url`` builds a boto3 client and raises.

    Trading a manifest that came back quietly short for a 500 on the whole archive would not be a
    fix, so the failure is caught and counted exactly like a missing key."""

    def _explode(*_a, **_k):
        raise RuntimeError("no AWS credentials configured")

    monkeypatch.setattr(export, "presign_get_url", _explode)
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            artisan=[_stranger()],
            mediafile=[_keyed_photo("a-stranger", "portrait.jpg", url=None, key="media/u/1/p.jpg")],
        )
    )
    assert "_Unlinked/_Artisans/Kamla Devi/portrait.jpg" not in _paths(manifest)
    assert manifest["skippedMedia"] == 1
    assert manifest["truncated"] is True


def test_a_row_with_neither_a_url_nor_a_key_is_counted_rather_than_dropped(monkeypatch):
    """The residual case the fallback cannot rescue. It must move ``truncated``, because that flag's
    documented job is "so the client can say so instead of quietly handing over a partial dataset"
    and a zip short by an unaddressable file is exactly as partial as one short by a capped row."""
    _signing(monkeypatch)
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            artisan=[_stranger()],
            mediafile=[_keyed_photo("a-stranger", "lost.jpg", url=None, key=None)],
        )
    )
    assert "_Unlinked/_Artisans/Kamla Devi/lost.jpg" not in _paths(manifest)
    assert manifest["skippedMedia"] == 1
    assert manifest["truncated"] is True


def test_a_skipped_row_does_not_push_the_next_file_to_a_numbered_name(monkeypatch):
    """``_uniq`` RESERVES the name it returns. Calling it before deciding to skip would leave
    "portrait.jpg" taken, so the next genuine file of that name lands as "portrait (2).jpg" — a
    numbered duplicate with no original beside it, which reads as a lost file."""
    _signing(monkeypatch)
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            artisan=[_stranger()],
            mediafile=[
                _keyed_photo("a-stranger", "portrait.jpg", url=None, key=None),
                _keyed_photo("a-stranger", "portrait.jpg", url="https://objects.test/p.jpg", key="k"),
            ],
        )
    )
    paths = _paths(manifest)
    assert "_Unlinked/_Artisans/Kamla Devi/portrait.jpg" in paths
    assert "_Unlinked/_Artisans/Kamla Devi/portrait (2).jpg" not in paths


class _OrderRecordingDelegate(_Delegate):
    """A delegate that remembers the ``order`` every read asked for."""

    def __init__(self, rows: list[Any], seen: list[Any]) -> None:
        super().__init__(rows)
        self._seen = seen

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self._seen.append(kwargs.get("order"))
        return await super().find_many(**kwargs)


def test_every_capped_read_carries_a_total_order(monkeypatch):
    """A LIMIT with no ORDER BY has no defined row set in Postgres: which 5000 rows survive can
    change with statistics or a concurrent vacuum, so two downloads of the same truncated archive can
    hold two different populations while both report ``truncated: true``. A reviewer diffing them
    concludes records were deleted.

    The ``id`` tiebreaker is asserted too, not just the presence of an order: ``createdAt`` is not
    unique (the closed viewer-picker finding measured 204 accounts sharing one sort key), so a
    timestamp-only order leaves the cut arbitrary inside a tie.
    """
    seen: list[Any] = []

    async def _open_where(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
        return {}

    db = _DB(artisan=[_stranger()], mediafile=[_photo("a-stranger")])
    for name in (
        "workshop",
        "artisan",
        "productdocumentation",
        "tooldocumentation",
        "questionnaireinterview",
        "process",
        "mediafile",
    ):
        setattr(db, name, _OrderRecordingDelegate(getattr(db, name)._rows, seen))
    monkeypatch.setattr(export, "db", db)
    monkeypatch.setattr(export, "owned_or_granted_where", _open_where)
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)
    asyncio.run(export.dataset_manifest(current_user=ADMIN))

    assert len(seen) == 7, f"expected the six record reads plus the media read, got {len(seen)}"
    for order in seen:
        assert order == [{"createdAt": "asc"}, {"id": "asc"}], order


# --------------------------------------------------------------------------------------
# 5. Media whose slot nothing emitted — the fetched-and-discarded rows
# --------------------------------------------------------------------------------------
#
# Every emit loop asks ``media_by`` for the slot of a record it is writing. Nothing asked which slots
# were never claimed, and the answer was not empty. ``_media_slot`` keys a row at the id its tag or FK
# NAMES, not at an id that still exists, so a photograph whose parent has died is fetched and then
# discarded because ``add_media`` is only ever called with live ids.
#
# THE ORDINARY TRIGGER IS AN EDIT, NOT A DELETE. ``processes._sync_steps`` hard-deletes every
# ProcessStep the form did not re-send, so a researcher who drops one duplicated step and saves has
# detached that step's photographs. The rows keep the ``workshopId`` they inherited at upload, which
# is exactly why the ``{"workshopId": {"in": ids}}`` arm of ``media_or`` goes on fetching them — they
# arrived in memory and left in silence, uncounted by ``totalMedia`` and unflagged by ``truncated``.
#
# It is the same defect as the unplaced artisan above, one level down: a loop that emits only what a
# parent reaches, under a header sentence promising "an _Unlinked area so nothing is dropped".


def _step_photo(step_id: str, filename: str, *, workshop_id: str | None = "w-1") -> Row:
    """A photograph attached to a process step by the tag pair alone — no FK exists for it."""
    return Row(
        id=f"m-{filename}",
        originalFilename=filename,
        mimeType="image/jpeg",
        url=f"https://objects.test/{filename}",
        objectKey=f"media/{filename}",
        workshopId=workshop_id,
        linkedRecordType="processstep",
        linkedRecordId=step_id,
    )


def test_a_photograph_whose_process_step_was_deleted_is_filed_rather_than_discarded(monkeypatch):
    """THE DEFECT. ``s-dead`` is on no process this export loaded, so ``add_media`` is never called
    with that slot and the row used to fall out of the manifest between the fetch and the emit."""
    manifest = asyncio.run(
        _manifest(monkeypatch, workshop=[_workshop()], mediafile=[_step_photo("s-dead", "step.jpg")])
    )
    assert "_Unlinked/_Detached files/step.jpg" in _paths(manifest)
    assert manifest["totalMedia"] == 1
    assert manifest["skippedMedia"] == 0


def test_an_upload_attached_to_nothing_at_all_is_filed_too(monkeypatch):
    """``_media_slot`` returns None for a row with neither a typed FK nor a tag pair, and the grouping
    loop dropped it before ``media_by`` was ever consulted.

    IT IS DRIVEN THROUGH THE ``ownerId`` PATH BECAUSE THAT IS THE ONLY PATH THAT FETCHES SUCH A ROW.
    Everywhere else ``media_or`` is built from the ids of records that will be emitted, so a file
    attached to nothing is never read in the first place; the ``{"uploadedById": ownerId}`` arm is
    the one that widens the read to EVERY file that owner uploaded. An owner-scoped export is defined
    as "everything that owner uploaded", and an upload they never attached is exactly that. Writing
    this case with a ``workshopId`` on the row would prove nothing — the FK gives it a live workshop
    slot and it is emitted under the workshop, which is correct and is not what is under test.
    """
    loose = Row(
        id="m-loose",
        originalFilename="notebook.jpg",
        mimeType="image/jpeg",
        url="https://objects.test/notebook.jpg",
        objectKey="media/notebook.jpg",
        linkedRecordType=None,
        linkedRecordId=None,
    )
    manifest = asyncio.run(_owner_manifest(monkeypatch, None, mediafile=[loose]))
    assert "_Unlinked/_Detached files/notebook.jpg" in _paths(manifest)
    assert manifest["totalMedia"] == 1


def test_a_photograph_whose_step_is_alive_stays_under_its_process(monkeypatch):
    """THE CONTROL, and the one that would catch a sweep that swallowed the whole tree: a live
    step's media must be emitted under the step and must NOT also appear in the detached folder."""
    step = Row(id="s-live", stepNumber=1, stepName="Carving", processId="proc-1")
    process = Row(
        id="proc-1",
        processName="Block carving",
        productId="p-1",
        steps=[step],
        workshopId="w-1",
    )
    product = Row(
        id="p-1",
        productName="Block-printed stole",
        artisanId="a-reached",
        artisanName="Sita Bai",
        workshopId="w-1",
    )
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            workshop=[_workshop()],
            artisan=[_reached()],
            productdocumentation=[product],
            process=[process],
            mediafile=[_step_photo("s-live", "carving.jpg")],
        )
    )
    paths = _paths(manifest)
    assert not any(p.startswith("_Unlinked/_Detached files/") for p in paths), paths
    assert sum(1 for p in paths if p.endswith("carving.jpg")) == 1
    assert manifest["totalMedia"] == 1


def test_a_detached_row_with_no_url_still_goes_through_the_signing_fallback(monkeypatch):
    """The sweep reuses ``add_media`` through a sentinel slot rather than re-spelling how a row
    becomes a manifest entry. A second copy of the url/presign/skip rule here is exactly how the
    null-url drop that rule was written to close got written in the first place."""
    _signing(monkeypatch)
    row = _step_photo("s-dead", "step.jpg")
    row.url = None
    manifest = asyncio.run(_manifest(monkeypatch, workshop=[_workshop()], mediafile=[row]))
    emitted = {f["path"]: f.get("url") for f in manifest["files"] if "url" in f}
    assert emitted["_Unlinked/_Detached files/step.jpg"].startswith("https://signed.test/media/step.jpg")


def test_an_unaddressable_detached_row_is_counted_by_skippedMedia(monkeypatch):
    """Filing a detached row must not quietly re-open the null-url hole: a row the sweep cannot
    address is counted and moves ``truncated``, exactly as it would under a live parent."""
    _signing(monkeypatch)
    row = _step_photo("s-dead", "step.jpg")
    row.url = None
    row.objectKey = None
    manifest = asyncio.run(_manifest(monkeypatch, workshop=[_workshop()], mediafile=[row]))
    assert "_Unlinked/_Detached files/step.jpg" not in _paths(manifest)
    assert manifest["skippedMedia"] == 1
    assert manifest["truncated"] is True


async def _owner_manifest(monkeypatch, scope: Any, **tables: list[Any]) -> dict[str, Any]:
    """The ``ownerId`` path, where ``media_vis`` is empty and the media read is widened to EVERY file
    that owner uploaded. ``owner_download_scope`` returns None for an all-data grant (and for an
    admin or the owner themselves) and a dict of granted record ids for a subset grant."""

    async def _scope(*_args: Any, **_kwargs: Any) -> Any:
        return scope

    async def _open_where(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(export, "db", _DB(**tables))
    monkeypatch.setattr(export, "owner_download_scope", _scope)
    monkeypatch.setattr(export, "owned_or_granted_where", _open_where)
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)
    return await export.dataset_manifest(ownerId="owner-1", current_user=ADMIN)


def test_a_subset_grantee_is_not_handed_the_files_of_records_outside_their_grant(monkeypatch):
    """THE CONTROL THAT KEEPS THE SWEEP FROM BECOMING A HOLE, and the reason it is gated on ``scope``.

    On the ``ownerId`` path ``media_vis`` is empty and ``media_or`` is widened by
    ``{"uploadedById": ownerId}`` — every file that owner uploaded, whatever it hangs off. What
    narrows it back down for a subset grantee is the very mechanism this sweep undoes: the files of
    records outside the grant land in slots no emit loop claims. Under a subset grant an unclaimed
    slot means "the parent is not yours", not "the parent is gone", and sweeping it up would hand a
    grantee holding two of a researcher's fifty artisans the photography of the other forty-eight."""
    manifest = asyncio.run(
        _owner_manifest(
            monkeypatch,
            {"artisan": {"a-reached"}},
            workshop=[_workshop()],
            artisan=[_reached()],
            mediafile=[_step_photo("s-dead", "step.jpg")],
        )
    )
    assert not any(p.startswith("_Unlinked/_Detached files/") for p in _paths(manifest))
    assert manifest["totalMedia"] == 0


def test_an_all_data_owner_export_does_get_the_detached_file(monkeypatch):
    """The other half of that condition. ``owner_download_scope`` answers None for an all-data
    DOWNLOAD+ grant, for an admin and for the owner themselves — and for those callers every file the
    owner uploaded belongs in the dataset by definition, detached parent or not."""
    manifest = asyncio.run(
        _owner_manifest(
            monkeypatch,
            None,
            workshop=[_workshop()],
            mediafile=[_step_photo("s-dead", "step.jpg")],
        )
    )
    assert "_Unlinked/_Detached files/step.jpg" in _paths(manifest)


# --------------------------------------------------------------------------------------
# The design-workshop half, and the rich-text answer beside it
# --------------------------------------------------------------------------------------
#
# THE ARCHIVE WAS CALLED ``design-workshop-dataset.zip`` AND CONTAINED NO DESIGN WORKSHOP.
# ``grep -c designWorkshop backend/app/api/routes/export.py`` answered ZERO, so the twenty-two-stage
# record this product is named after — around 523 field specs across 44 entities — reached no export
# anywhere. That is worse than an omission: a researcher archived a file whose NAME promised the
# workshops, opened it a year later, found artisans and products, and could not tell whether they had
# been left out or never recorded. The web renamed the download to stop the filename asserting
# something false; this is the other fix, so the name went back.
#
# NOTHING HERE TOUCHES A DATABASE either — same fixture machinery as everything above, and the same
# reason: the behaviour under test is the SHAPE OF THE TREE once the rows are loaded.

# The two stages these fixtures use, named from the registry rather than typed, so a retitled stage
# renames the folder here as well and this test cannot pin a title the product has stopped using.
SETUP_STAGE = next(s for s in dwd.stages() if s.key == "WORKSHOP_SETUP")
SKETCH_STAGE = next(s for s in dwd.stages() if s.key == "SKETCH_DEVELOPMENT")
SETUP_FOLDER = f"{SETUP_STAGE.number:02d} {SETUP_STAGE.title}"
SKETCH_FOLDER = f"{SKETCH_STAGE.number:02d} {SKETCH_STAGE.title}"


def _content(manifest: dict[str, Any], path: str) -> str:
    """The text written at one manifest path. Raises if nothing is written there."""
    return next(f["content"] for f in manifest["files"] if f["path"] == path)


def _dw(**kw: Any) -> Row:
    base = {
        "id": "dw-1",
        "title": "Bagru indigo fortnight",
        "workshopCode": "DW-01",
        "craftName": "Block printing",
        "state": "Rajasthan",
        "deletedAt": None,
    }
    base.update(kw)
    return Row(**base)


def _entry(entity_key: str, data: dict[str, Any], entry_id: str = "e-1", ordinal: int = 0) -> Row:
    return Row(
        id=entry_id,
        designWorkshopId="dw-1",
        entityKey=entity_key,
        data=data,
        ordinal=ordinal,
        deletedAt=None,
    )


async def _dw_manifest(
    monkeypatch,
    *,
    may_export: bool = True,
    may_view: bool = True,
    owner_id: str | None = None,
    **tables: list[Any],
) -> dict[str, Any]:
    """The route, with the two design-workshop predicates pinned rather than derived from a role.

    PINNED, BECAUSE THE SPLIT IS THE THING UNDER TEST. ``can_view_design_workshop_data`` and
    ``can_export_design_workshop_data`` are exercised as a pair in
    ``test_design_workshop_data_access``; what matters here is that this route does the right thing
    for each of the three populations they define, which a role fixture would express only indirectly.
    """

    async def _open_where(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
        return {}

    async def _owner_scope(*_args: Any, **_kwargs: Any) -> None:
        # ``None`` is the all-data answer: every record the owner created, unfiltered. The narrow
        # subset-grant shape is exercised by the tests above; what this one asks is whether design
        # workshops appear on the owner path AT ALL, and they must not.
        return None

    monkeypatch.setattr(export, "db", _DB(**tables))
    monkeypatch.setattr(export, "owned_or_granted_where", _open_where)
    monkeypatch.setattr(export, "owner_download_scope", _owner_scope)
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)
    monkeypatch.setattr(export, "can_export_design_workshop_data", lambda _user: may_export)
    monkeypatch.setattr(export, "can_view_design_workshop_data", lambda _user: may_view)
    return await export.dataset_manifest(ownerId=owner_id, current_user=ADMIN)


class TestDesignWorkshopHalf:
    def test_the_workshop_gets_a_folder_with_its_own_details(self, monkeypatch):
        manifest = asyncio.run(_dw_manifest(monkeypatch, designworkshop=[_dw()]))
        details = _content(manifest, "Design workshops/Bagru indigo fortnight/details.txt")
        # The promoted columns — the axes a researcher filters on — and the coverage counts, from the
        # SAME ``_dw_info`` panel the tree draws, so the archive and the browser describe one
        # workshop one way.
        assert "DW-01" in details
        assert "Block printing" in details
        assert f"0 of {len(dwd.stages())}" in details

    def test_stage_rows_are_written_under_the_stage_that_owns_them(self, monkeypatch):
        manifest = asyncio.run(
            _dw_manifest(
                monkeypatch,
                designworkshop=[_dw()],
                dwstageentry=[_entry("workshopSetup", {"workshopTitle": "Indigo fortnight"})],
            )
        )
        body = _content(
            manifest,
            f"Design workshops/Bagru indigo fortnight/Stages/{SETUP_FOLDER}/Workshop details.txt",
        )
        assert "Workshop title: Indigo fortnight" in body

    def test_only_stages_that_hold_something_get_a_folder(self, monkeypatch):
        """Twenty-two folders of which most open on nothing is not navigable — the tree lists only
        the answered ones and says "N of 22" on the panel, and the archive must agree with it."""
        manifest = asyncio.run(
            _dw_manifest(
                monkeypatch,
                designworkshop=[_dw()],
                dwstageentry=[_entry("workshopSetup", {"workshopTitle": "Indigo fortnight"})],
            )
        )
        stage_paths = [p for p in _paths(manifest) if "/Stages/" in p]
        assert len(stage_paths) == 1
        details = _content(manifest, "Design workshops/Bagru indigo fortnight/details.txt")
        assert f"1 of {len(dwd.stages())}" in details

    def test_a_stage_photograph_is_filed_under_the_stage_that_cites_it(self, monkeypatch):
        """THE MEDIA-IDENTITY HALF. ``_media_slot`` reads columns, and the only record that a given
        photograph answers stage 11's "Sketch image" is the stage row itself — so left to it, a
        stage photograph carrying an inherited legacy ``workshopId`` was filed under an unrelated
        workshop's folder."""
        photo = Row(
            id="m-sketch",
            linkedRecordType="designWorkshop",
            linkedRecordId="dw-1",
            originalFilename="sketch-3.jpg",
            url="https://objects.test/sketch-3.jpg",
        )
        manifest = asyncio.run(
            _dw_manifest(
                monkeypatch,
                designworkshop=[_dw()],
                dwstageentry=[_entry("sketch", {"name": "Sketch 3", "image": "m-sketch"})],
                mediafile=[photo],
            )
        )
        paths = _paths(manifest)
        assert (
            f"Design workshops/Bagru indigo fortnight/Stages/{SKETCH_FOLDER}/sketch-3.jpg" in paths
        )
        assert manifest["totalMedia"] == 1

    def test_a_loose_upload_lands_in_the_workshop_folder_and_not_in_detached(self, monkeypatch):
        """``designWorkshopId`` is not in ``_MEDIA_FK_SLOTS``, so a workshop's miscellaneous upload
        matched no slot at all and was swept into ``_Unlinked/_Detached files`` — a folder that
        asserts the file's parent record no longer exists, about a workshop sitting in the same zip."""
        loose = Row(
            id="m-loose",
            designWorkshopId="dw-1",
            linkedRecordType=None,
            linkedRecordId=None,
            originalFilename="courtyard.jpg",
            url="https://objects.test/courtyard.jpg",
        )
        manifest = asyncio.run(
            _dw_manifest(monkeypatch, designworkshop=[_dw()], mediafile=[loose])
        )
        paths = _paths(manifest)
        assert "Design workshops/Bagru indigo fortnight/courtyard.jpg" in paths
        assert not any(p.startswith(export.DETACHED_FOLDER) for p in paths)

    def test_rows_this_build_cannot_describe_are_named_rather_than_dropped(self, monkeypatch):
        """A handset one release ahead syncs rows written against a newer registry. Refusing to
        mention them would under-report the corpus while looking complete."""
        manifest = asyncio.run(
            _dw_manifest(
                monkeypatch,
                designworkshop=[_dw()],
                dwstageentry=[_entry("somethingNewer", {"x": 1})],
            )
        )
        note = _content(manifest, "Design workshops/Bagru indigo fortnight/not-shown.txt")
        assert "somethingNewer" in note

    def test_the_designers_own_questions_are_named_as_absent(self, monkeypatch):
        """A DELIBERATE LIMITATION, STATED. Naming those columns needs that workshop's own
        definition, which is two queries PER WORKSHOP — so a repository export of four hundred
        workshops would pay eight hundred cross-region round trips to label them. The note sends the
        reader to the per-workshop folder in the tree, which does carry them."""
        manifest = asyncio.run(
            _dw_manifest(
                monkeypatch,
                designworkshop=[_dw()],
                dwstageentry=[_entry(dwd.CUSTOM_ENTITY_KEY, {"f-1": "indigo"})],
            )
        )
        note = _content(manifest, "Design workshops/Bagru indigo fortnight/not-shown.txt")
        assert "designer-written questions" in note
        assert "By design workshop" in note

    def test_a_professor_gets_a_sentence_rather_than_a_silently_smaller_archive(self, monkeypatch):
        """A professor may READ these workshops and may not download them, so their archive really
        does lack the section — and a file outlives the page it came from, so an archive that simply
        lacked it would read a year later as a repository with no design workshops in it."""
        manifest = asyncio.run(
            _dw_manifest(
                monkeypatch,
                may_export=False,
                may_view=True,
                designworkshop=[_dw(), _dw(id="dw-2", title="Kutch weaving fortnight")],
            )
        )
        paths = _paths(manifest)
        note = _content(manifest, f"Design workshops/{export.DW_WITHHELD_FILE}")
        assert "2 design workshop(s)" in note
        assert "By design workshop" in note
        assert "Design workshops/Bagru indigo fortnight/details.txt" not in paths

    def test_nothing_is_said_to_an_account_that_cannot_see_this_data_at_all(self, monkeypatch):
        """A researcher holding ``canDownloadDataset`` without the rank meets no design-workshop
        folder, sheet or search bucket anywhere in this product. Telling them in a zip about data no
        screen offers them would be this route inventing a disclosure."""
        manifest = asyncio.run(
            _dw_manifest(monkeypatch, may_export=False, may_view=False, designworkshop=[_dw()])
        )
        assert not any(p.startswith("Design workshops") for p in _paths(manifest))

    def test_an_owner_scoped_export_carries_no_design_workshops(self, monkeypatch):
        """An owner export is authorised by ``owner_download_scope`` — tiered grants over the seven
        legacy tables — and a design workshop is on a different ladder entirely
        (``load_workshop_or_404``). Emitting them there would hand a grantee data no grant they hold
        names."""
        manifest = asyncio.run(
            _dw_manifest(monkeypatch, owner_id="user-9", designworkshop=[_dw()])
        )
        assert not any(p.startswith("Design workshops") for p in _paths(manifest))


class TestRichTextQuestionnaireAnswers:
    """``answers.txt`` was the fifth call site ``record_fields.cell``'s docstring predicted.

    ``QuestionnaireResponse.answerText`` is a ``String?`` that became a rich-text box on 2026-08-31
    while keeping its type, and this file interpolated the raw column value — so an archive that goes
    to a ministry carried ``{"blocks":[{"kind":"PARAGRAPH",…}]}`` where the artisan's answer should
    have been, with no emptiness check anywhere noticing, because a JSON-shaped string is not empty.
    """

    @staticmethod
    def _interview(answer: str) -> Row:
        return Row(
            id="iv-1",
            title="Loom interview",
            artisans=[],
            responses=[
                Row(
                    questionId="q1",
                    answerText=answer,
                    question=Row(prompt="How many looms?", sectionCode="A"),
                )
            ],
        )

    def test_a_formatted_answer_is_written_as_prose(self, monkeypatch):
        stored = to_stored_text(
            RichDoc(
                blocks=(RichBlock(spans=(RichSpan("Twelve looms", marks=frozenset({Mark.BOLD})),)),)
            )
        )
        manifest = asyncio.run(
            _manifest(monkeypatch, questionnaireinterview=[self._interview(stored)])
        )
        body = _content(manifest, "_Unlinked/Questionnaires/Loom interview/answers.txt")
        assert "Twelve looms" in body
        assert '{"blocks"' not in body
        assert "PARAGRAPH" not in body

    def test_plain_prose_is_written_exactly_as_before(self, monkeypatch):
        manifest = asyncio.run(
            _manifest(monkeypatch, questionnaireinterview=[self._interview("Twelve looms")])
        )
        body = _content(manifest, "_Unlinked/Questionnaires/Loom interview/answers.txt")
        assert "[A] How many looms?\n  -> Twelve looms" in body
