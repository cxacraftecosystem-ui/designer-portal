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

from app.api.routes import export


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
