import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the stage registry
from app.api.routes import export
from app.services import design_workshop_data as dwd
from app.services.rich_text import Mark, RichBlock, RichDoc, RichSpan, to_stored_text


class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _Delegate:
    def __init__(self, rows: list[Any]) -> None:
        self._rows = rows

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return list(self._rows)

    async def count(self, **_kwargs: Any) -> int:
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



def _stranger() -> Row:
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
        objectKey=f"media/{artisan_id}/{filename}",
        linkedRecordType=None,
        linkedRecordId=None,
    )



@pytest.mark.parametrize("with_workshop", [False, True])
def test_an_artisan_no_workshop_reaches_still_gets_a_folder(monkeypatch, with_workshop):
    manifest = asyncio.run(
        _manifest(
            monkeypatch,
            workshop=[_workshop()] if with_workshop else [],
            artisan=[_stranger()],
        )
    )
    assert "_Unlinked/_Artisans/Kamla Devi/details.txt" in _paths(manifest)


def test_their_own_photographs_are_emitted_and_counted(monkeypatch):
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
    manifest = asyncio.run(
        _manifest(monkeypatch, workshop=[_workshop()], artisan=[_reached(), _stranger()])
    )
    paths = _paths(manifest)
    assert "Workshops/Block printing workshop/No craft/Sita Bai/details.txt" in paths
    assert "_Unlinked/_Artisans/Sita Bai/details.txt" not in paths
    assert "_Unlinked/Sita Bai/details.txt" not in paths
    assert "_Unlinked/_Artisans/Kamla Devi/details.txt" in paths


def test_two_unlinked_artisans_sharing_a_name_do_not_share_a_folder(monkeypatch):
    twin = Row(id="a-twin", name="Kamla Devi", place="Chittorgarh", workshopId=None, craftId=None)
    manifest = asyncio.run(_manifest(monkeypatch, artisan=[_stranger(), twin]))
    paths = _paths(manifest)
    assert "_Unlinked/_Artisans/Kamla Devi/details.txt" in paths
    assert "_Unlinked/_Artisans/Kamla Devi (2)/details.txt" in paths



def test_an_unlinked_artisans_records_nest_under_them_rather_than_scattering(monkeypatch):
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
    assert sum(1 for p in paths if p.endswith("/Block-printed stole/details.txt")) == 1
    assert sum(1 for p in paths if p.endswith("/Carved block set/details.txt")) == 1


def test_a_record_whose_artisan_is_filed_under_a_workshop_still_uses_the_flat_bucket(monkeypatch):
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

    assert "_Unlinked/Kamla Devi/Products/Indigo yardage/details.txt" in paths
    assert "_Unlinked/Kamla Devi/Products/Mud-resist scarf/details.txt" in paths
    assert f"{stranger_dir}/details.txt" in paths



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
    monkeypatch.setattr(
        export,
        "presign_get_url",
        lambda key, *, filename, mime_type, expires_in: f"https://signed.test/{key}?exp={expires_in}",
    )


def test_a_media_row_with_no_url_is_signed_from_its_object_key(monkeypatch):
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
    def __init__(self, rows: list[Any], seen: list[Any]) -> None:
        super().__init__(rows)
        self._seen = seen

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self._seen.append(kwargs.get("order"))
        return await super().find_many(**kwargs)


def test_every_capped_read_carries_a_total_order(monkeypatch):
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



def _step_photo(step_id: str, filename: str, *, workshop_id: str | None = "w-1") -> Row:
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
    manifest = asyncio.run(
        _manifest(monkeypatch, workshop=[_workshop()], mediafile=[_step_photo("s-dead", "step.jpg")])
    )
    assert "_Unlinked/_Detached files/step.jpg" in _paths(manifest)
    assert manifest["totalMedia"] == 1
    assert manifest["skippedMedia"] == 0


def test_an_upload_attached_to_nothing_at_all_is_filed_too(monkeypatch):
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
    _signing(monkeypatch)
    row = _step_photo("s-dead", "step.jpg")
    row.url = None
    manifest = asyncio.run(_manifest(monkeypatch, workshop=[_workshop()], mediafile=[row]))
    emitted = {f["path"]: f.get("url") for f in manifest["files"] if "url" in f}
    assert emitted["_Unlinked/_Detached files/step.jpg"].startswith("https://signed.test/media/step.jpg")


def test_an_unaddressable_detached_row_is_counted_by_skippedMedia(monkeypatch):
    _signing(monkeypatch)
    row = _step_photo("s-dead", "step.jpg")
    row.url = None
    row.objectKey = None
    manifest = asyncio.run(_manifest(monkeypatch, workshop=[_workshop()], mediafile=[row]))
    assert "_Unlinked/_Detached files/step.jpg" not in _paths(manifest)
    assert manifest["skippedMedia"] == 1
    assert manifest["truncated"] is True


async def _owner_manifest(monkeypatch, scope: Any, **tables: list[Any]) -> dict[str, Any]:
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
    manifest = asyncio.run(
        _owner_manifest(
            monkeypatch,
            None,
            workshop=[_workshop()],
            mediafile=[_step_photo("s-dead", "step.jpg")],
        )
    )
    assert "_Unlinked/_Detached files/step.jpg" in _paths(manifest)


SETUP_STAGE = next(s for s in dwd.stages() if s.key == "WORKSHOP_SETUP")
SKETCH_STAGE = next(s for s in dwd.stages() if s.key == "SKETCH_DEVELOPMENT")
SETUP_FOLDER = f"{SETUP_STAGE.number:02d} {SETUP_STAGE.title}"
SKETCH_FOLDER = f"{SKETCH_STAGE.number:02d} {SKETCH_STAGE.title}"


def _content(manifest: dict[str, Any], path: str) -> str:
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
    async def _open_where(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
        return {}

    async def _owner_scope(*_args: Any, **_kwargs: Any) -> None:
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
        folder = f"Design workshops/Bagru indigo fortnight/Stages/{SETUP_FOLDER}/"
        path = next(
            p for p in _paths(manifest) if p.startswith(folder) and p.endswith("_Workshop-Details.txt")
        )
        assert path.removeprefix(folder).startswith("DesignWorkshop_Workshop_")
        assert "Workshop title: Indigo fortnight" in _content(manifest, path)

    def test_only_stages_that_hold_something_get_a_folder(self, monkeypatch):
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
        photo = Row(
            id="m-sketch",
            linkedRecordType="designWorkshop",
            linkedRecordId="dw-1",
            originalFilename="sketch-3.jpg",
            url="https://objects.test/sketch-3.jpg",
            objectKey="media/sketch-3.jpg",
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
        loose = Row(
            id="m-loose",
            designWorkshopId="dw-1",
            linkedRecordType=None,
            linkedRecordId=None,
            originalFilename="courtyard.jpg",
            url="https://objects.test/courtyard.jpg",
            objectKey="media/courtyard.jpg",
        )
        manifest = asyncio.run(
            _dw_manifest(monkeypatch, designworkshop=[_dw()], mediafile=[loose])
        )
        paths = _paths(manifest)
        assert "Design workshops/Bagru indigo fortnight/courtyard.jpg" in paths
        assert not any(p.startswith(export.DETACHED_FOLDER) for p in paths)

    def test_rows_this_build_cannot_describe_are_named_rather_than_dropped(self, monkeypatch):
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
        assert "2 design workshops are" in note
        assert "By design workshop" in note
        assert "Design workshops/Bagru indigo fortnight/details.txt" not in paths

    def test_nothing_is_said_to_an_account_that_cannot_see_this_data_at_all(self, monkeypatch):
        manifest = asyncio.run(
            _dw_manifest(monkeypatch, may_export=False, may_view=False, designworkshop=[_dw()])
        )
        assert not any(p.startswith("Design workshops") for p in _paths(manifest))

    def test_an_owner_scoped_export_carries_no_design_workshops(self, monkeypatch):
        manifest = asyncio.run(
            _dw_manifest(monkeypatch, owner_id="user-9", designworkshop=[_dw()])
        )
        assert not any(p.startswith("Design workshops") for p in _paths(manifest))


class TestRichTextQuestionnaireAnswers:
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
