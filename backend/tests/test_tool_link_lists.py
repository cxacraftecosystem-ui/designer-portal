import asyncio
import inspect
import pathlib
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.schemas.records import ToolCreate, ToolUpdate
from app.services.record_filters import resolve_craft_ids


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _Writes:
    def __init__(self, row: Any = None, rows: list[Any] | None = None):
        self.row = row
        self.rows = rows if rows is not None else []
        self.created: list[Any] = []
        self.created_many: list[list[dict[str, Any]]] = []
        self.deleted_many: list[dict[str, Any]] = []
        self.updated: list[tuple[Any, Any]] = []
        self.queried: list[Any] = []

    async def create(self, data, **_kwargs):
        self.created.append(data)
        return self.row if self.row is not None else _Row(id="tol_1", **data)

    async def update(self, where, data, **_kwargs):
        self.updated.append((where, data))
        return self.row if self.row is not None else _Row(id=where["id"], **data)

    async def create_many(self, data, **_kwargs):
        self.created_many.append(list(data))
        return len(data)

    async def delete_many(self, where, **_kwargs):
        self.deleted_many.append(where)
        return 0

    async def find_many(self, where=None, **_kwargs):
        self.queried.append(where)
        return list(self.rows)

    async def find_unique(self, where, **_kwargs):
        return self.row if self.row is not None else _Row(id=where["id"])


class _Client(SimpleNamespace):
    def __init__(self, **delegates):
        super().__init__(**delegates)
        self.tx_client = SimpleNamespace(
            recordrevision=_Writes(),
            **{
                name: _Writes(row=value.row, rows=list(value.rows))
                for name, value in delegates.items()
            },
        )
        self.tx_entered = 0

    def tx(self, *_args, **_kwargs) -> Any:
        outer = self

        class _Tx:
            async def __aenter__(self) -> Any:
                outer.tx_entered += 1
                return outer.tx_client

            async def __aexit__(self, *_exc: object) -> bool:
                return False

        return _Tx()


class _Payload:
    def __init__(self, fields: dict[str, Any], **attributes):
        self._fields = dict(fields)
        self.dumped_with: dict[str, Any] = {}
        self.model_fields_set = set(fields)
        for name, value in attributes.items():
            setattr(self, name, value)

    def __getattr__(self, name):
        return self._fields.get(name)

    def model_dump(self, **kwargs):
        self.dumped_with = kwargs
        excluded = kwargs.get("exclude") or set()
        return {k: v for k, v in self._fields.items() if k not in excluded}


def _editor(user_id: str = "usr_7"):
    return _Row(id=user_id, name="R. Menon", role="RESEARCHER")


async def _privileged(_record, _user, _data, _kind, *, client=None, derived=()):
    return True


async def _unprivileged(_record, _user, _data, _kind, *, client=None, derived=()):
    return False


async def _no_status_policy(*_args, **_kwargs):
    return None


def _craft(craft_id: str, name: str) -> _Row:
    return _Row(id=craft_id, name=name)


def _artisan(artisan_id: str, created_by: str = "usr_7") -> _Row:
    return _Row(id=artisan_id, name=f"Artisan {artisan_id}", createdById=created_by)


@pytest.fixture(autouse=True)
def _no_merged_artisans(monkeypatch):
    from app.api.routes import tools

    async def _same_payload(data, **_kw):
        return data

    async def _same_ids(ids, **_kw):
        return ids

    monkeypatch.setattr(tools, "redirect_payload", _same_payload)
    monkeypatch.setattr(tools, "redirect_ids", _same_ids)


def _drive_create(
    monkeypatch,
    fields: dict[str, Any],
    *,
    crafts: list[_Row] | None = None,
    artisans: list[_Row] | None = None,
    user: Any = None,
) -> tuple[_Client, dict[str, Any], list[Any]]:
    from app.api.routes import tools

    stored = _Row(id="tol_1")
    client = _Client(
        tooldocumentation=_Writes(row=stored),
        craft=_Writes(rows=crafts or []),
        artisan=_Writes(rows=artisans or []),
        toolcraft=_Writes(),
        toolartisan=_Writes(),
    )
    hydrated: list[Any] = []

    async def _no_replay(*_args, **_kwargs):
        return None

    async def _attach(data):
        return data

    async def _hydrate(rows, relations):
        hydrated.append(tuple(rel.field for rel in relations))

    async def _no_workshop(*_args, **_kwargs):
        return None

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "client_key_replay", _no_replay)
    monkeypatch.setattr(tools, "attach_location", _attach)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop)
    monkeypatch.setattr(tools, "assert_payload_workshop", _no_workshop)
    monkeypatch.setattr(tools, "stamp_workshop_submission", lambda *a, **k: None)
    monkeypatch.setattr(tools, "apply_status_policy_create", lambda *a, **k: None)
    monkeypatch.setattr(tools, "pin_pending_if_late", lambda *a, **k: None)
    monkeypatch.setattr(tools, "hydrate_relations", _hydrate)
    monkeypatch.setattr(tools, "public_encode", lambda row, _viewer=None, **_kw: row)

    payload = _Payload(fields, clientKey=None)
    asyncio.run(tools.create_tool(payload, user or _editor()))
    return client, client.tooldocumentation.created[0], hydrated


def _link(**columns) -> _Row:
    return _Row(toolId="tol_1", **columns)


def _drive_patch(
    monkeypatch,
    fields: dict[str, Any],
    *,
    stored: _Row | None = None,
    crafts: list[_Row] | None = None,
    artisans: list[_Row] | None = None,
    craft_links: list[_Row] | None = None,
    artisan_links: list[_Row] | None = None,
    user: Any = None,
    guard: Any = None,
) -> tuple[_Client, dict[str, Any]]:
    from app.api.routes import tools

    record = stored if stored is not None else _Row(id="tol_1", createdById="usr_7")
    client = _Client(
        tooldocumentation=_Writes(row=record),
        craft=_Writes(rows=crafts or []),
        artisan=_Writes(rows=artisans or []),
        toolcraft=_Writes(rows=craft_links or []),
        toolartisan=_Writes(rows=artisan_links or []),
    )

    async def _require_record(_delegate, _record_id):
        return record

    async def _attach(data):
        return data

    async def _no_media_urls(_viewer):
        return set()

    async def _no_workshop(*_args, **_kwargs):
        return None

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "require_record", _require_record)
    monkeypatch.setattr(tools, "attach_location", _attach)
    monkeypatch.setattr(tools, "guard_record_edit", guard or _privileged)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "assert_payload_workshop", _no_workshop)
    monkeypatch.setattr(tools, "stamp_workshop_submission", lambda *a, **k: None)
    monkeypatch.setattr(tools, "pin_pending_if_late", lambda *a, **k: None)
    monkeypatch.setattr(tools, "media_url_owners", _no_media_urls)
    monkeypatch.setattr(tools, "public_encode", lambda row, _viewer=None, **_kw: row)

    payload = _Payload(fields)
    asyncio.run(tools.update_tool("tol_1", payload, user or _editor()))
    written = client.tx_client.tooldocumentation.updated
    return client, (written[0][1] if written else {})



VALID_LOCATION = {
    "latitude": 26.9124,
    "longitude": 75.7873,
    "state": "Rajasthan",
    "district": "Jaipur",
    "village": "Bagru",
}
MINIMAL_TOOL = {
    "craftName": "Bandhani",
    "place": "Bagru",
    "artisanName": "L. Devi",
    "toolkitName": "Pit Loom",
    "location": VALID_LOCATION,
}


@pytest.mark.parametrize("key", ["craftIds", "artisanIds"])
def test_an_absent_list_is_none_and_not_an_empty_list(key):
    created = ToolCreate.model_validate(MINIMAL_TOOL)
    assert getattr(created, key) is None
    updated = ToolUpdate.model_validate({"remarks": "typo fixed"})
    assert getattr(updated, key) is None
    assert key not in updated.model_fields_set


@pytest.mark.parametrize("key", ["craftIds", "artisanIds"])
def test_an_empty_list_survives_the_parse_and_is_marked_as_sent(key):
    updated = ToolUpdate.model_validate({key: []})
    assert getattr(updated, key) == []
    assert key in updated.model_fields_set
    assert updated.model_dump(exclude_unset=True) == {key: []}


@pytest.mark.parametrize("model", [ToolCreate, ToolUpdate], ids=["create", "update"])
@pytest.mark.parametrize("key", ["craftIds", "artisanIds"])
def test_an_explicit_null_is_refused_rather_than_read_as_absent(model, key):
    body = dict(MINIMAL_TOOL) if model is ToolCreate else {}
    body[key] = None
    with pytest.raises(ValidationError) as excinfo:
        model.model_validate(body)
    message = str(excinfo.value)
    assert key in message
    assert "send [] to clear the links" in message


def test_the_validator_does_not_fire_for_a_field_nobody_sent():
    assert ToolCreate.model_validate(MINIMAL_TOOL).craftIds is None
    assert ToolUpdate.model_validate({}).artisanIds is None



def test_the_two_keys_never_reach_prisma():
    from app.api.routes import tools

    data = {"toolkitName": "Pit Loom", "craftIds": ["c1"], "artisanIds": ["a1"]}
    assert tools._pop_link_ids(data, "craftIds") == ["c1"]
    assert tools._pop_link_ids(data, "artisanIds") == ["a1"]
    assert data == {"toolkitName": "Pit Loom"}


def test_an_absent_key_pops_to_none_and_an_empty_list_pops_to_empty():
    from app.api.routes import tools

    assert tools._pop_link_ids({}, "craftIds") is None
    assert tools._pop_link_ids({"craftIds": []}, "craftIds") == []


def test_duplicates_collapse_keeping_the_first_occurrence():
    from app.api.routes import tools

    assert tools._pop_link_ids({"craftIds": ["c2", "c1", "c2", "c3", "c1"]}, "craftIds") == [
        "c2",
        "c1",
        "c3",
    ]


def test_blank_ids_are_dropped_and_a_list_of_only_blanks_clears():
    from app.api.routes import tools

    assert tools._pop_link_ids({"craftIds": ["c1", "  ", "", "c2"]}, "craftIds") == ["c1", "c2"]
    assert tools._pop_link_ids({"craftIds": ["", "   "]}, "craftIds") == []


def test_surrounding_whitespace_does_not_make_a_second_id():
    from app.api.routes import tools

    assert tools._pop_link_ids({"artisanIds": [" a1", "a1 ", "a2"]}, "artisanIds") == ["a1", "a2"]



def test_a_create_derives_the_singular_columns_from_the_first_of_each_list(monkeypatch):
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=["c2", "c1"], artisanIds=["a9", "a3"], location=None),
        crafts=[_craft("c1", "Block Printing"), _craft("c2", "Bandhani")],
        artisans=[_artisan("a3"), _artisan("a9")],
    )
    assert data["craftId"] == "c2"
    assert data["artisanId"] == "a9"
    assert data["craftName"] == "Bandhani, Block Printing"


def test_the_joined_name_overrides_the_craft_name_in_the_same_body(monkeypatch):
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftName="Something Else", craftIds=["c1"], location=None),
        crafts=[_craft("c1", "Bandhani")],
    )
    assert data["craftName"] == "Bandhani"


def test_the_joined_name_is_not_re_title_cased(monkeypatch):
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=["c1", "c2"], location=None),
        crafts=[_craft("c1", "Bandhani of Kutch"), _craft("c2", "ikat")],
    )
    assert data["craftName"] == "Bandhani of Kutch, ikat"


def test_artisan_name_and_place_are_left_to_the_body(monkeypatch):
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(
            MINIMAL_TOOL,
            artisanName="L. Devi (corrected)",
            place="Bagru",
            artisanIds=["a1"],
            location=None,
        ),
        artisans=[_artisan("a1")],
    )
    assert data["artisanId"] == "a1"
    assert data["artisanName"] == "L. Devi (Corrected)"
    assert data["place"] == "Bagru"


def test_a_create_writes_one_link_row_per_id_in_order(monkeypatch):
    client, _data, hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=["c2", "c1"], artisanIds=["a1"], location=None),
        crafts=[_craft("c1", "Block Printing"), _craft("c2", "Bandhani")],
        artisans=[_artisan("a1")],
    )
    assert client.toolcraft.created_many == [
        [{"toolId": "tol_1", "craftId": "c2"}, {"toolId": "tol_1", "craftId": "c1"}]
    ]
    assert client.toolartisan.created_many == [[{"toolId": "tol_1", "artisanId": "a1"}]]
    assert hydrated == [("artisanLinks", "craftLinks")]


def test_a_create_that_mentions_no_list_writes_no_link_row_and_re_reads_nothing(monkeypatch):
    client, data, hydrated = _drive_create(monkeypatch, dict(MINIMAL_TOOL, location=None))
    assert client.toolcraft.created_many == []
    assert client.toolcraft.deleted_many == []
    assert client.toolartisan.created_many == []
    assert client.toolartisan.deleted_many == []
    assert hydrated == []
    assert "craftId" not in data and "artisanId" not in data


def test_a_create_that_states_an_empty_selection_deletes_nothing(monkeypatch):
    client, _data, hydrated = _drive_create(
        monkeypatch, dict(MINIMAL_TOOL, craftIds=[], artisanIds=[], location=None)
    )
    assert client.toolcraft.deleted_many == []
    assert client.toolartisan.deleted_many == []
    assert client.toolcraft.created_many == []
    assert client.toolartisan.created_many == []
    assert hydrated == []



def test_a_craft_id_the_caller_cannot_see_is_a_404_before_any_write(monkeypatch):
    with pytest.raises(HTTPException) as excinfo:
        _drive_create(
            monkeypatch,
            dict(MINIMAL_TOOL, craftIds=["c1", "c_gone"], location=None),
            crafts=[_craft("c1", "Bandhani")],
        )
    assert excinfo.value.status_code == 404
    assert excinfo.value.detail == "Record not found"


def test_an_artisan_id_the_caller_cannot_see_is_a_404_before_any_write(monkeypatch):
    with pytest.raises(HTTPException) as excinfo:
        _drive_create(
            monkeypatch,
            dict(MINIMAL_TOOL, artisanIds=["a1", "a_gone"], location=None),
            artisans=[_artisan("a1")],
        )
    assert excinfo.value.status_code == 404


def test_the_404_leaves_no_row_and_no_link_behind(monkeypatch):
    from app.api.routes import tools

    client = _Client(
        tooldocumentation=_Writes(row=_Row(id="tol_1")),
        craft=_Writes(rows=[]),
        artisan=_Writes(rows=[]),
        toolcraft=_Writes(),
        toolartisan=_Writes(),
    )

    async def _no_replay(*_args, **_kwargs):
        return None

    async def _attach(data):
        return data

    async def _no_workshop(*_args, **_kwargs):
        return None

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "client_key_replay", _no_replay)
    monkeypatch.setattr(tools, "attach_location", _attach)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop)
    monkeypatch.setattr(tools, "assert_payload_workshop", _no_workshop)

    with pytest.raises(HTTPException):
        asyncio.run(
            tools.create_tool(
                _Payload(dict(MINIMAL_TOOL, craftIds=["c_gone"], location=None), clientKey=None),
                _editor(),
            )
        )
    assert client.tooldocumentation.created == []
    assert client.toolcraft.created_many == []


def test_a_non_owner_may_only_link_artisans_they_created(monkeypatch):
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "is_content_admin", lambda _user: False)

    with pytest.raises(HTTPException) as excinfo:
        _drive_patch(
            monkeypatch,
            {"artisanIds": ["a_theirs"]},
            stored=_Row(id="tol_1", createdById="somebody_else"),
            artisans=[_artisan("a_theirs", created_by="somebody_else")],
            user=_editor("usr_7"),
        )
    assert excinfo.value.status_code == 403
    assert "you may only assign it to your own artisans" in excinfo.value.detail


def test_the_creator_of_a_tool_may_link_any_artisan(monkeypatch):
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, artisanIds=["a_theirs"], location=None),
        artisans=[_artisan("a_theirs", created_by="somebody_else")],
    )
    assert data["artisanId"] == "a_theirs"



def _crafts_joining_to(total: int, count: int = 6) -> list[_Row]:
    body = total - 2 * (count - 1)
    base, extra = divmod(body, count)
    return [
        _craft(f"c{index}", f"Craft{index}".ljust(base + (1 if index < extra else 0), "x"))
        for index in range(count)
    ]


def test_the_bound_the_route_enforces_is_the_one_both_tool_schemas_declare():
    from app.api.routes import tools
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    assert tools.TOOL_CRAFT_NAME_MAX_LENGTH == TOOL_CRAFT_NAME_MAX_LENGTH
    for model in (ToolCreate, ToolUpdate):
        declared = [
            getattr(rule, "max_length", None)
            for rule in model.model_fields["craftName"].metadata
            if getattr(rule, "max_length", None) is not None
        ]
        assert declared == [TOOL_CRAFT_NAME_MAX_LENGTH], model.__name__


def test_a_selection_whose_joined_name_crosses_the_bound_is_refused_before_any_write(monkeypatch):
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH + 1)
    with pytest.raises(HTTPException) as excinfo:
        _drive_create(
            monkeypatch,
            dict(MINIMAL_TOOL, craftIds=[craft.id for craft in crafts], location=None),
            crafts=crafts,
        )
    assert excinfo.value.status_code == 422
    assert str(TOOL_CRAFT_NAME_MAX_LENGTH) in excinfo.value.detail
    assert str(len(crafts)) in excinfo.value.detail


def test_the_over_long_selection_leaves_no_row_and_no_link_behind(monkeypatch):
    from app.api.routes import tools
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH + 40)
    client = _Client(
        tooldocumentation=_Writes(row=_Row(id="tol_1")),
        craft=_Writes(rows=crafts),
        artisan=_Writes(rows=[]),
        toolcraft=_Writes(),
        toolartisan=_Writes(),
    )

    async def _no_replay(*_args, **_kwargs):
        return None

    async def _attach(data):
        return data

    async def _no_workshop(*_args, **_kwargs):
        return None

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "client_key_replay", _no_replay)
    monkeypatch.setattr(tools, "attach_location", _attach)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop)
    monkeypatch.setattr(tools, "assert_payload_workshop", _no_workshop)

    body = dict(MINIMAL_TOOL, craftIds=[craft.id for craft in crafts], location=None)
    with pytest.raises(HTTPException):
        asyncio.run(tools.create_tool(_Payload(body, clientKey=None), _editor()))
    assert client.tooldocumentation.created == []
    assert client.toolcraft.created_many == []


def test_the_refusal_leaves_the_dict_it_was_handed_untouched(monkeypatch):
    from app.api.routes import tools
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH + 1)
    monkeypatch.setattr(tools, "db", _Client(craft=_Writes(rows=crafts)))
    data = {"craftName": "Bandhani", "toolkitName": "Pit Loom"}
    with pytest.raises(HTTPException) as excinfo:
        asyncio.run(tools._resolve_craft_links(data, [craft.id for craft in crafts]))
    assert excinfo.value.status_code == 422
    assert data == {"craftName": "Bandhani", "toolkitName": "Pit Loom"}


def test_the_patch_is_refused_above_the_transaction_so_nothing_is_replaced(monkeypatch):
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH + 1)
    with pytest.raises(HTTPException) as excinfo:
        _drive_patch(
            monkeypatch,
            {"craftIds": [craft.id for craft in crafts]},
            crafts=crafts,
            craft_links=[_link(id="lnk_1", craftId="c0")],
        )
    assert excinfo.value.status_code == 422


def test_a_join_exactly_at_the_bound_is_stored(monkeypatch):
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH)
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=[craft.id for craft in crafts], location=None),
        crafts=crafts,
    )
    assert len(data["craftName"]) == TOOL_CRAFT_NAME_MAX_LENGTH


def test_the_name_the_route_stores_is_one_its_own_update_schema_takes_back(monkeypatch):
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH)
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=[craft.id for craft in crafts], location=None),
        crafts=crafts,
    )
    echoed = ToolUpdate(craftName=data["craftName"], craftIds=[craft.id for craft in crafts])
    assert echoed.craftName == data["craftName"]



def test_a_patch_that_mentions_neither_list_leaves_both_link_sets_alone(monkeypatch):
    client, data = _drive_patch(monkeypatch, {"remarks": "Corrected a typo."})
    assert client.tx_client.toolcraft.deleted_many == []
    assert client.tx_client.toolcraft.created_many == []
    assert client.tx_client.toolartisan.deleted_many == []
    assert client.tx_client.toolartisan.created_many == []
    assert "craftId" not in data and "craftName" not in data and "artisanId" not in data
    assert client.tx_client.toolcraft.queried == []
    assert client.tx_client.toolartisan.queried == []


def test_a_patch_that_mentions_neither_list_never_reaches_for_a_join_delegate(monkeypatch):
    from app.api.routes import tools

    record = _Row(id="tol_1", createdById="usr_7")
    writes = _Writes(row=record)

    class _OnlyTheRecord(SimpleNamespace):
        def tx(self, *_args, **_kwargs) -> Any:
            client = self

            class _Tx:
                async def __aenter__(self) -> Any:
                    return client

                async def __aexit__(self, *_exc: object) -> bool:
                    return False

            return _Tx()

    client = _OnlyTheRecord(tooldocumentation=writes)

    async def _require_record(_delegate, _record_id):
        return record

    async def _attach(data):
        return data

    async def _no_media_urls(_viewer):
        return set()

    async def _no_workshop(*_args, **_kwargs):
        return None

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "require_record", _require_record)
    monkeypatch.setattr(tools, "attach_location", _attach)
    monkeypatch.setattr(tools, "guard_record_edit", _privileged)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "assert_payload_workshop", _no_workshop)
    monkeypatch.setattr(tools, "stamp_workshop_submission", lambda *a, **k: None)
    monkeypatch.setattr(tools, "pin_pending_if_late", lambda *a, **k: None)
    monkeypatch.setattr(tools, "media_url_owners", _no_media_urls)
    monkeypatch.setattr(tools, "public_encode", lambda row, _viewer=None, **_kw: row)

    asyncio.run(tools.update_tool("tol_1", _Payload({"remarks": "Corrected a typo."}), _editor()))
    assert writes.updated and writes.updated[0][1]["remarks"] == "Corrected a typo."


def test_a_patch_may_send_one_list_and_leave_the_other_alone(monkeypatch):
    client, data = _drive_patch(
        monkeypatch, {"craftIds": ["c1"]}, crafts=[_craft("c1", "Bandhani")]
    )
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolcraft.created_many == [[{"toolId": "tol_1", "craftId": "c1"}]]
    assert client.tx_client.toolartisan.deleted_many == []
    assert client.tx_client.toolartisan.created_many == []
    assert data["craftId"] == "c1"
    assert data["craftName"] == "Bandhani"


def test_an_empty_list_deletes_every_link_row_and_writes_none(monkeypatch):
    client, _data = _drive_patch(monkeypatch, {"craftIds": [], "artisanIds": []})
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolcraft.created_many == []
    assert client.tx_client.toolartisan.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolartisan.created_many == []


def test_clearing_the_links_does_not_touch_the_denormalised_columns(monkeypatch):
    client, data = _drive_patch(monkeypatch, {"craftIds": [], "artisanIds": []})
    assert "craftName" not in data
    assert "artisanName" not in data
    assert "place" not in data
    assert "craftId" not in data
    assert "artisanId" not in data
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]


def test_a_patch_replaces_the_set_rather_than_adding_to_it(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c3", "c1"]},
        crafts=[_craft("c1", "Block Printing"), _craft("c3", "Kalamkari")],
    )
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolcraft.created_many == [
        [{"toolId": "tol_1", "craftId": "c3"}, {"toolId": "tol_1", "craftId": "c1"}]
    ]


def test_the_link_writes_go_through_the_transaction_and_not_the_module_singleton(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch, {"craftIds": ["c1"]}, crafts=[_craft("c1", "Bandhani")]
    )
    assert client.tx_entered == 1
    assert client.tx_client.toolcraft.created_many
    assert client.toolcraft.created_many == []


def test_a_patch_writes_the_links_before_the_row_so_the_response_describes_them(monkeypatch):
    from app.api.routes import tools

    client, _data = _drive_patch(
        monkeypatch, {"craftIds": ["c1"]}, crafts=[_craft("c1", "Bandhani")]
    )
    assert client.tx_client.toolcraft.created_many
    body = inspect.getsource(tools.update_tool)
    assert body.index("_write_links(tx") < body.index("tx.tooldocumentation.update")


def test_the_pop_happens_before_the_guard_so_the_ledger_sees_the_derived_columns(monkeypatch):
    seen: dict[str, Any] = {}

    async def _record(record, user, data, kind, *, client=None, derived=()):
        seen.update(data)
        return True

    _drive_patch(
        monkeypatch, {"craftIds": ["c1"]}, crafts=[_craft("c1", "Bandhani")], guard=_record
    )
    assert "craftIds" not in seen and "artisanIds" not in seen
    assert seen["craftId"] == "c1"
    assert seen["craftName"] == "Bandhani"



def test_an_empty_list_from_a_non_owner_is_refused_and_deletes_nothing(monkeypatch):
    with pytest.raises(HTTPException) as excinfo:
        _drive_patch(
            monkeypatch,
            {"craftIds": [], "artisanIds": []},
            stored=_Row(id="tol_1", createdById="somebody_else"),
            craft_links=[_link(craftId="c1"), _link(craftId="c2")],
            artisan_links=[_link(artisanId="a1")],
            user=_editor("usr_7"),
            guard=_unprivileged,
        )
    assert excinfo.value.status_code == 403
    assert "craftIds" in excinfo.value.detail


def test_the_refused_wipe_leaves_the_link_rows_where_they_were(monkeypatch):
    captured: list[_Client] = []
    original = _Client.__init__

    def _remember(self, **delegates):
        original(self, **delegates)
        captured.append(self)

    monkeypatch.setattr(_Client, "__init__", _remember)
    with pytest.raises(HTTPException):
        _drive_patch(
            monkeypatch,
            {"artisanIds": []},
            stored=_Row(id="tol_1", createdById="somebody_else"),
            artisan_links=[_link(artisanId="a1")],
            user=_editor("usr_7"),
            guard=_unprivileged,
        )
    client = captured[0]
    assert client.tx_client.toolartisan.deleted_many == []
    assert client.toolartisan.deleted_many == []


def test_the_second_door_on_the_craft_half_is_shut_too(monkeypatch):
    with pytest.raises(HTTPException) as excinfo:
        _drive_patch(
            monkeypatch,
            {"craftIds": ["c1"]},
            stored=_Row(id="tol_1", createdById="somebody_else", craftId="c1", craftName="Bandhani"),
            crafts=[_craft("c1", "Bandhani")],
            craft_links=[_link(craftId="c1"), _link(craftId="c2")],
            user=_editor("usr_7"),
            guard=_unprivileged,
        )
    assert excinfo.value.status_code == 403
    assert "craftIds" in excinfo.value.detail


def test_a_non_owner_restating_the_stored_selection_is_not_refused(monkeypatch):
    client, data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c1"], "remarks": "Added the missing measurement."},
        stored=_Row(id="tol_1", createdById="somebody_else", craftId="c1", craftName="Bandhani"),
        crafts=[_craft("c1", "Bandhani")],
        craft_links=[_link(craftId="c1")],
        user=_editor("usr_7"),
        guard=_unprivileged,
    )
    assert data["remarks"] == "Added the missing measurement."
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolcraft.created_many == [[{"toolId": "tol_1", "craftId": "c1"}]]


def test_tick_order_alone_is_not_a_change_of_the_relation(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c2", "c1"]},
        stored=_Row(id="tol_1", createdById="somebody_else"),
        crafts=[_craft("c1", "Block Printing"), _craft("c2", "Bandhani")],
        craft_links=[_link(craftId="c1"), _link(craftId="c2")],
        user=_editor("usr_7"),
        guard=_unprivileged,
    )
    assert client.tx_client.toolcraft.created_many == [
        [{"toolId": "tol_1", "craftId": "c2"}, {"toolId": "tol_1", "craftId": "c1"}]
    ]


def test_a_non_owner_may_fill_an_empty_relation(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c1"]},
        stored=_Row(id="tol_1", createdById="somebody_else"),
        crafts=[_craft("c1", "Bandhani")],
        craft_links=[],
        user=_editor("usr_7"),
        guard=_unprivileged,
    )
    assert client.tx_client.toolcraft.created_many == [[{"toolId": "tol_1", "craftId": "c1"}]]


def test_a_populated_craft_name_over_an_empty_join_is_a_populated_relation(monkeypatch):
    with pytest.raises(HTTPException) as excinfo:
        _drive_patch(
            monkeypatch,
            {"craftIds": ["c2"]},
            stored=_Row(id="tol_1", createdById="somebody_else", craftName="Bandhani"),
            crafts=[_craft("c2", "Block Printing")],
            craft_links=[],
            user=_editor("usr_7"),
            guard=_unprivileged,
        )
    assert excinfo.value.status_code == 403
    assert "craftIds" in excinfo.value.detail


def test_the_refused_craft_rewrite_leaves_the_name_column_alone(monkeypatch):
    captured: list[_Client] = []
    original = _Client.__init__

    def _remember(self, **delegates):
        original(self, **delegates)
        captured.append(self)

    monkeypatch.setattr(_Client, "__init__", _remember)
    with pytest.raises(HTTPException):
        _drive_patch(
            monkeypatch,
            {"craftIds": ["c2"]},
            stored=_Row(id="tol_1", createdById="somebody_else", craftName="Bandhani"),
            crafts=[_craft("c2", "Block Printing")],
            craft_links=[],
            user=_editor("usr_7"),
            guard=_unprivileged,
        )
    client = captured[0]
    assert client.tx_client.tooldocumentation.updated == []
    assert client.tx_client.toolcraft.created_many == []


def test_an_empty_craft_name_over_an_empty_join_is_still_an_empty_relation(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c1"]},
        stored=_Row(id="tol_1", createdById="somebody_else", craftName="   "),
        crafts=[_craft("c1", "Bandhani")],
        craft_links=[],
        user=_editor("usr_7"),
        guard=_unprivileged,
    )
    assert client.tx_client.toolcraft.created_many == [[{"toolId": "tol_1", "craftId": "c1"}]]


def test_a_populated_artisan_name_does_not_lock_the_artisan_relation(monkeypatch):
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_content_admin", lambda _user: False)

    client, _data = _drive_patch(
        monkeypatch,
        {"artisanIds": ["a1"]},
        stored=_Row(id="tol_1", createdById="somebody_else", artisanName="L. Devi", place="Bagru"),
        artisans=[_artisan("a1", created_by="usr_7")],
        artisan_links=[],
        user=_editor("usr_7"),
        guard=_unprivileged,
    )
    assert client.tx_client.toolartisan.created_many == [[{"toolId": "tol_1", "artisanId": "a1"}]]


def test_a_privileged_caller_is_not_asked_the_relation_question(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": [], "artisanIds": []},
        craft_links=[_link(craftId="c1")],
        artisan_links=[_link(artisanId="a1")],
        guard=_privileged,
    )
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolartisan.deleted_many == [{"toolId": "tol_1"}]


def test_clearing_the_links_writes_an_audit_row_naming_what_was_deleted(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": [], "artisanIds": []},
        craft_links=[_link(craftId="c2"), _link(craftId="c1")],
        artisan_links=[_link(artisanId="a1")],
        guard=_privileged,
    )
    written = client.tx_client.recordrevision.created
    assert len(written) == 1
    payload = getattr(written[0]["changes"], "data", written[0]["changes"])
    assert payload["craftIds"] == {"old": ["c1", "c2"], "new": []}
    assert payload["artisanIds"] == {"old": ["a1"], "new": []}
    assert written[0]["recordId"] == "tol_1"
    assert written[0]["editedById"] == "usr_7"


def test_an_unchanged_set_writes_no_audit_row(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c2", "c1"]},
        crafts=[_craft("c1", "Block Printing"), _craft("c2", "Bandhani")],
        craft_links=[_link(craftId="c1"), _link(craftId="c2")],
        guard=_privileged,
    )
    assert client.tx_client.recordrevision.created == []


def test_the_relation_guard_reads_through_the_transaction(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c1"]},
        crafts=[_craft("c1", "Bandhani")],
        craft_links=[_link(craftId="c1")],
        guard=_privileged,
    )
    assert client.tx_client.toolcraft.queried == [{"toolId": "tol_1"}]
    assert client.toolcraft.queried == []



def test_the_route_reports_the_derived_craft_name_to_the_edit_guard(monkeypatch):
    seen: dict[str, Any] = {}

    async def _capture(_record, _user, data, _kind, *, client=None, derived=()):
        seen["data"] = dict(data)
        seen["derived"] = set(derived)
        return True

    _drive_patch(
        monkeypatch,
        {"craftIds": ["c1"], "artisanIds": ["a1"]},
        crafts=[_craft("c1", "Bandhani")],
        artisans=[_artisan("a1")],
        guard=_capture,
    )
    assert seen["derived"] == {"craftName"}
    assert seen["data"]["craftName"] == "Bandhani"
    assert seen["data"]["craftId"] == "c1"
    assert seen["data"]["artisanId"] == "a1"


def test_nothing_is_reported_as_derived_when_no_list_was_sent(monkeypatch):
    seen: dict[str, Any] = {}

    async def _capture(_record, _user, _data, _kind, *, client=None, derived=()):
        seen["derived"] = set(derived)
        return True

    _drive_patch(monkeypatch, {"craftName": "Typed By Hand"}, guard=_capture)
    assert seen["derived"] == set()


def test_the_derived_name_is_withheld_from_the_contributor_guard_and_from_nothing_else(monkeypatch):
    from app.core import deps
    from app.services import access

    ledger: dict[str, Any] = {}

    async def _no_rank(*_args, **_kwargs):
        return False

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _ledger(_record, _user, data, _kind, *, client=None):
        ledger.update(data)

    monkeypatch.setattr(deps, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(access, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(access, "record_revision", _ledger)

    tool = _Row(
        id="tol_1",
        createdById="somebody_else",
        craftId="c1",
        craftName="Bandhani Tie-Dye (Kutch)",
        remarks=None,
    )
    body = {"craftName": "Bandhani", "craftId": "c1", "remarks": "The missing measurement."}

    with pytest.raises(HTTPException) as excinfo:
        asyncio.run(access.guard_record_edit(tool, _editor(), dict(body), "tool"))
    assert excinfo.value.status_code == 403
    assert "craftName" in excinfo.value.detail

    privileged = asyncio.run(
        access.guard_record_edit(tool, _editor(), dict(body), "tool", derived={"craftName"})
    )
    assert privileged is False
    assert ledger["craftName"] == "Bandhani"



def test_a_non_owner_may_resave_a_tool_linked_to_somebody_elses_artisan(monkeypatch):
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_content_admin", lambda _user: False)

    _client, data = _drive_patch(
        monkeypatch,
        {"artisanIds": ["a_theirs"], "remarks": "Filled in the empty box."},
        stored=_Row(id="tol_1", createdById="somebody_else"),
        artisans=[_artisan("a_theirs", created_by="somebody_else")],
        artisan_links=[_link(artisanId="a_theirs")],
        user=_editor("usr_7"),
        guard=_unprivileged,
    )
    assert data["artisanId"] == "a_theirs"


def test_adding_somebody_elses_artisan_is_still_refused(monkeypatch):
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_content_admin", lambda _user: False)

    with pytest.raises(HTTPException) as excinfo:
        _drive_patch(
            monkeypatch,
            {"artisanIds": ["a_theirs", "a_new"]},
            stored=_Row(id="tol_1", createdById="somebody_else"),
            artisans=[
                _artisan("a_theirs", created_by="somebody_else"),
                _artisan("a_new", created_by="somebody_else"),
            ],
            artisan_links=[_link(artisanId="a_theirs")],
            user=_editor("usr_7"),
            guard=_unprivileged,
        )
    assert excinfo.value.status_code == 403
    assert "you may only assign it to your own artisans" in excinfo.value.detail


def test_an_id_that_names_no_visible_artisan_is_a_404_even_when_it_is_already_linked(monkeypatch):
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_content_admin", lambda _user: False)

    with pytest.raises(HTTPException) as excinfo:
        _drive_patch(
            monkeypatch,
            {"artisanIds": ["a_gone"]},
            stored=_Row(id="tol_1", createdById="somebody_else"),
            artisans=[],
            artisan_links=[_link(artisanId="a_gone")],
            user=_editor("usr_7"),
            guard=_unprivileged,
        )
    assert excinfo.value.status_code == 404


def test_a_privileged_caller_pays_no_query_for_the_already_linked_set(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"artisanIds": ["a1"]},
        artisans=[_artisan("a1")],
        artisan_links=[_link(artisanId="a1")],
        guard=_privileged,
    )
    assert client.toolartisan.queried == []


def test_the_link_manager_gate_grants_an_outranking_professor(monkeypatch):
    from app.api.routes import tools

    asked: list[Any] = []

    async def _outranks(_user, creator_id):
        asked.append(creator_id)
        return True

    async def _never(*_args, **_kwargs):
        raise AssertionError("the grant lookup must not be reached once the rank clause answers yes")

    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _outranks)
    monkeypatch.setattr(tools, "effective_tier_for_record", _never)
    monkeypatch.setattr(tools, "is_content_admin", lambda _user: False)

    allowed = asyncio.run(
        tools._may_manage_tool_links(
            _Row(id="tol_1", createdById="somebody_else"), "tol_1", _editor("usr_7")
        )
    )
    assert allowed is True
    assert asked == ["somebody_else"]



def test_a_legacy_body_that_moves_the_craft_scalar_rewrites_the_join(monkeypatch):
    client, data = _drive_patch(
        monkeypatch,
        {"craftId": "c2", "craftName": "Block Printing"},
        stored=_Row(id="tol_1", createdById="usr_7", craftId="c1", craftName="Bandhani"),
        crafts=[_craft("c2", "Block Printing")],
        craft_links=[_link(craftId="c1")],
    )
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolcraft.created_many == [[{"toolId": "tol_1", "craftId": "c2"}]]
    assert data["craftId"] == "c2"
    assert data["craftName"] == "Block Printing"


def test_a_legacy_body_restating_the_stored_craft_touches_no_join_delegate(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"craftId": "c1", "remarks": "Handle re-bound."},
        stored=_Row(id="tol_1", createdById="usr_7", craftId="c1", craftName="Bandhani"),
        crafts=[_craft("c1", "Bandhani")],
        craft_links=[_link(craftId="c1")],
    )
    assert client.tx_client.toolcraft.deleted_many == []
    assert client.tx_client.toolcraft.created_many == []
    assert client.tx_client.toolcraft.queried == []


def test_a_legacy_body_says_nothing_about_the_artisan_set(monkeypatch):
    client, _data = _drive_patch(
        monkeypatch,
        {"artisanId": "a2", "artisanName": "M. Joshi", "place": "Bagru"},
        stored=_Row(id="tol_1", createdById="usr_7", artisanId="a1"),
        artisans=[_artisan("a2")],
        artisan_links=[_link(artisanId="a1"), _link(artisanId="a3")],
    )
    assert client.tx_client.toolartisan.deleted_many == []
    assert client.tx_client.toolartisan.created_many == []


def test_a_modern_body_that_states_both_keys_is_not_second_guessed(monkeypatch):
    client, data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c2", "c3"], "craftId": "c9"},
        stored=_Row(id="tol_1", createdById="usr_7", craftId="c1", craftName="Bandhani"),
        crafts=[_craft("c2", "Block Printing"), _craft("c3", "Warli")],
        craft_links=[_link(craftId="c1")],
    )
    assert client.tx_client.toolcraft.created_many == [
        [{"toolId": "tol_1", "craftId": "c2"}, {"toolId": "tol_1", "craftId": "c3"}]
    ]
    assert data["craftId"] == "c2"
    assert data["craftName"] == "Block Printing, Warli"



def test_the_relations_tuple_serves_both_link_arrays_back():
    from app.api.routes import tools

    fields = [rel.field for rel in tools.RELATIONS]
    assert "craftLinks" in fields and "artisanLinks" in fields
    assert tools.INCLUDE["craftLinks"] == {"include": {"craft": True}}
    assert tools.INCLUDE["artisanLinks"] == {"include": {"artisan": True}}
    assert [rel.field for rel in tools._LINK_RELATIONS] == ["artisanLinks", "craftLinks"]


def test_craft_links_come_back_in_the_order_craft_name_records():
    from app.api.routes import tools

    encoded = {
        "craftName": "Bandhani, Block Printing, Kalamkari",
        "craftLinks": [
            {"id": "l3", "craftId": "c3", "craft": {"name": "Kalamkari"}},
            {"id": "l1", "craftId": "c1", "craft": {"name": "Bandhani"}},
            {"id": "l2", "craftId": "c2", "craft": {"name": "Block Printing"}},
        ],
    }
    tools._order_links(encoded)
    assert [link["craftId"] for link in encoded["craftLinks"]] == ["c1", "c2", "c3"]


def test_a_link_whose_craft_was_renamed_since_the_save_is_appended_not_dropped():
    from app.api.routes import tools

    encoded = {
        "craftName": "Bandhani",
        "craftLinks": [
            {"id": "l9", "craftId": "c9", "createdAt": "2026-02-01", "craft": {"name": "Ikat"}},
            {"id": "l1", "craftId": "c1", "createdAt": "2026-03-01", "craft": {"name": "Bandhani"}},
            {"id": "l8", "craftId": "c8", "createdAt": "2026-01-01", "craft": {"name": "Ajrakh"}},
        ],
    }
    tools._order_links(encoded)
    assert [link["craftId"] for link in encoded["craftLinks"]] == ["c1", "c8", "c9"]


def test_artisan_links_come_back_oldest_first():
    from app.api.routes import tools

    encoded = {
        "artisanLinks": [
            {"id": "z", "artisanId": "a2", "createdAt": "2026-05-02"},
            {"id": "a", "artisanId": "a1", "createdAt": "2026-05-01"},
            {"id": "b", "artisanId": "a3", "createdAt": "2026-05-02"},
        ]
    }
    tools._order_links(encoded)
    assert [link["artisanId"] for link in encoded["artisanLinks"]] == ["a1", "a3", "a2"]


def test_the_ordering_walks_a_whole_page_and_tolerates_a_tool_with_no_links():
    from app.api.routes import tools

    page = [
        {
            "craftName": "B, A",
            "craftLinks": [
                {"id": "2", "craft": {"name": "A"}},
                {"id": "1", "craft": {"name": "B"}},
            ],
        },
        {"craftName": "Solo", "craftLinks": [], "artisanLinks": []},
        {"craftName": None},
    ]
    assert tools._order_links(page) is page
    assert [link["craft"]["name"] for link in page[0]["craftLinks"]] == ["B", "A"]



def test_absent_means_every_craft_not_none_of_them():
    assert resolve_craft_ids(None) is None
    assert resolve_craft_ids([]) is None
    assert resolve_craft_ids([""]) is None
    assert resolve_craft_ids(["   "]) is None
    assert resolve_craft_ids([" , , "]) is None


def test_both_spellings_clients_build_are_accepted():
    assert resolve_craft_ids(["c1", "c2"]) == ["c1", "c2"]
    assert resolve_craft_ids(["c1,c2"]) == ["c1", "c2"]
    assert resolve_craft_ids(["c1, c2", "c3"]) == ["c1", "c2", "c3"]


def test_ids_are_deduplicated_in_first_seen_order():
    assert resolve_craft_ids(["c2", "c1", "c2"]) == ["c2", "c1"]
    assert resolve_craft_ids(["c2,c1", "c2"]) == ["c2", "c1"]


def test_there_is_no_reserved_none_token_on_this_filter():
    assert resolve_craft_ids(["none"]) == ["none"]


def test_the_plural_scope_narrows_through_and_filters_and_leaves_the_singular_alone(monkeypatch):
    from app.api.routes import artisans

    captured: dict[str, Any] = {}

    async def _count_and_page(_delegate, **kwargs):
        captured.update(kwargs)
        return 0, []

    async def _no_vis(_user, *_args, **_kwargs):
        return {}

    monkeypatch.setattr(artisans, "db", SimpleNamespace(artisan=_Writes()))
    monkeypatch.setattr(artisans, "count_and_page", _count_and_page)
    monkeypatch.setattr(artisans, "viewable_where", _no_vis)
    monkeypatch.setattr(artisans, "public_encode", lambda rows, _viewer=None, **_kw: rows)

    asyncio.run(
        artisans.list_artisans(
            _editor(), craftId="c1", craftIds=["c2,c3"], workshopIds=None, page=1, pageSize=20
        )
    )
    where = captured["where"]
    assert where["craftId"] == "c1"
    assert {"craftId": {"in": ["c2", "c3"]}} in where["AND"]


def test_the_plural_scope_is_absent_from_the_query_when_nobody_sent_it(monkeypatch):
    from app.api.routes import artisans

    captured: dict[str, Any] = {}

    async def _count_and_page(_delegate, **kwargs):
        captured.update(kwargs)
        return 0, []

    async def _no_vis(_user, *_args, **_kwargs):
        return {}

    monkeypatch.setattr(artisans, "db", SimpleNamespace(artisan=_Writes()))
    monkeypatch.setattr(artisans, "count_and_page", _count_and_page)
    monkeypatch.setattr(artisans, "viewable_where", _no_vis)
    monkeypatch.setattr(artisans, "public_encode", lambda rows, _viewer=None, **_kw: rows)

    asyncio.run(
        artisans.list_artisans(_editor(), craftIds=["  "], workshopIds=None, page=1, pageSize=20)
    )
    assert "AND" not in captured["where"]
    assert "craftId" not in captured["where"]
    assert captured["order"] == {"createdAt": "desc"}


BACKEND = pathlib.Path(__file__).resolve().parents[1]
MIGRATION = BACKEND / "prisma" / "migrations" / "20260915100000_tool_craft_links" / "migration.sql"
SCHEMA = BACKEND / "prisma" / "schema.prisma"


def _sql() -> str:
    return "\n".join(
        line
        for line in MIGRATION.read_text(encoding="utf-8").splitlines()
        if not line.strip().startswith("--")
    )


def _model(name: str) -> str:
    text = SCHEMA.read_text(encoding="utf-8")
    start = text.index(f"model {name} {{")
    return text[start : text.index("\n}", start)]


def test_the_migration_creates_the_table_the_model_declares():
    body = _sql()
    assert 'CREATE TABLE IF NOT EXISTS "ToolCraft"' in body
    for column in ('"id" TEXT NOT NULL', '"toolId" TEXT NOT NULL', '"craftId" TEXT NOT NULL'):
        assert column in body, column
    assert '"createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP' in body
    assert 'CONSTRAINT "ToolCraft_pkey" PRIMARY KEY ("id")' in body
    assert 'CREATE UNIQUE INDEX IF NOT EXISTS "ToolCraft_toolId_craftId_key"' in body
    assert 'CREATE INDEX IF NOT EXISTS "ToolCraft_craftId_idx"' in body
    for constraint, parent in (
        ("ToolCraft_toolId_fkey", '"ToolDocumentation"("id")'),
        ("ToolCraft_craftId_fkey", '"Craft"("id")'),
    ):
        assert f'ADD CONSTRAINT "{constraint}"' in body, constraint
        assert parent in body, parent
    assert body.count("ON DELETE CASCADE ON UPDATE CASCADE") == 2


def test_the_migration_is_additive_and_touches_no_existing_table():
    body = _sql()
    assert "DROP " not in body.upper()
    alters = [line.strip() for line in body.splitlines() if line.strip().startswith("ALTER TABLE")]
    assert alters and all(line.startswith('ALTER TABLE "ToolCraft" ADD CONSTRAINT') for line in alters), alters
    assert body.count("INSERT INTO") == 1
    assert 'INSERT INTO "ToolCraft"' in body


def test_the_backfill_gives_every_tool_that_names_a_craft_its_link():
    body = _sql()
    assert 'FROM "ToolDocumentation" t' in body
    assert 'WHERE t."craftId" IS NOT NULL' in body
    assert '\'c\' || substr(md5(t."id" || \':\' || t."craftId"), 1, 24)' in body
    assert 't."createdAt"' in body
    assert 'ON CONFLICT ("toolId", "craftId") DO NOTHING' in body


def test_the_migration_is_re_runnable_and_never_concurrent():
    body = _sql()
    assert "CONCURRENTLY" not in body
    assert body.count("IF NOT EXISTS") >= 3
    assert body.count("FROM pg_constraint WHERE conname") == 2


def test_the_model_mirrors_tool_artisan_column_for_column():
    craft, artisan = _model("ToolCraft"), _model("ToolArtisan")
    for shape in (
        "id        String   @id @default(cuid())",
        "toolId    String",
        "createdAt DateTime @default(now())",
        "onDelete: Cascade",
    ):
        assert shape in craft and shape in artisan, shape
    assert "@@unique([toolId, craftId])" in craft
    assert "@@unique([toolId, artisanId])" in artisan
    assert "@@index([craftId])" in craft
    assert "@@index([artisanId])" in artisan


def test_both_back_relations_are_declared_and_spelled_the_way_their_siblings_are():
    assert "craftLinks ToolCraft[]" in _model("ToolDocumentation")
    assert "artisanLinks ToolArtisan[]" in _model("ToolDocumentation")
    assert "toolLinks ToolCraft[]" in _model("Craft")
    assert "toolLinks ToolArtisan[]" in _model("Artisan")

