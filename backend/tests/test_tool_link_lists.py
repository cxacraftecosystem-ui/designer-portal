"""``craftIds`` and ``artisanIds`` on the tool write path, and ``craftIds`` on ``GET /artisans``.

A tool used to name one craft. It now names several — ``ToolCraft``, migration
``20260915100000_tool_craft_links`` — while ``ToolDocumentation.craftId`` goes on holding the FIRST
of them so that every filter, index, report and carry-forward that reads that column reads the same
value it always did. ``ToolArtisan`` already existed and is used for the artisan half rather than a
second mechanism being invented beside it.

**THE THREE-STATE CONTRACT IS THE SUBJECT OF THIS MODULE.** Absent means "leave the links alone",
``[]`` means "there are none", and an explicit ``null`` is refused at the door. The middle two are
what a record form actually sends — un-ticking the last craft and never touching the picker are
different intentions — and the third exists because ``clean_data`` drops ``None`` for anything
outside its clearable set and neither key is a column, so a null would arrive at the route
INDISTINGUISHABLE from an absent key. That is the one distinction a partial update cannot afford to
lose, and it is the reason ``ToolCreate``/``ToolUpdate`` carry a validator rather than a plain
``list[str] | None``.

NO DATABASE, and for the reason ``tests/test_record_patch_clearing`` gives at length: every
collaborator that would touch Postgres is replaced with a recording stub, while the clean, the
provenance merge, the pop, the derivation and the ordering are the real ones. What is asserted here
is what the ROUTE does with a body — which delegate it writes through, in which order, and what it
refuses — and none of that needs rows. The schema half (``ToolCreate``/``ToolUpdate``) is driven
through the real pydantic models, because the null refusal IS a schema rule.

THE MIGRATION IS READ OFF DISK, and section 8 says what that can and cannot prove. It can prove the
file says what the model says and that it is additive and re-runnable, which is the half a schema
regeneration would silently undo — ``tests/test_design_workshop_review_loop`` reads its own SQL for
exactly that reason. It cannot prove Postgres accepts it; a test that stood up a database to watch
``CREATE TABLE`` succeed would be testing Postgres.
"""

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

# --------------------------------------------------------------------------------------
# The stubs. Shaped after tests/test_record_patch_clearing.py, which drives the same routes.
# --------------------------------------------------------------------------------------


class _Row:
    """A stored record that answers ``None`` for every column a test did not set."""

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):  # only reached for names __init__ did not set
        return None


class _Writes:
    """One Prisma model delegate, recording every write and read aimed at it."""

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
    """The fake Prisma client, whose ``tx()`` hands back a DIFFERENT object.

    ``test_record_patch_clearing``'s version returns ``self``, which is enough for a module about
    ``clearable``. It is NOT enough here: the whole point of ``_write_links(tx, …)`` is that the link
    writes go through the TRANSACTION's client rather than the module singleton, and a stub that
    returns itself makes the correct and the incorrect code indistinguishable. So this one hands back
    a second client with its own delegates, and the tests assert which of the two was written to.
    """

    def __init__(self, **delegates):
        super().__init__(**delegates)
        # THE ROWS TRAVEL TOO, because the PATCH now READS inside the transaction as well as
        # writing: ``update_tool`` asks the two join delegates what this tool is linked to before it
        # replaces the set, and a ``tx`` whose delegates always answered "no rows" would make a
        # populated relation indistinguishable from an empty one — which is exactly the state the
        # permission check turns on.
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
    """A stand-in for the pydantic model, recording how the route dumped it."""

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
    """What ``guard_record_edit`` returns for an ordinary contributor: it did not refuse the FIELDS
    (they were empty, or all of them were empty on the record), and the caller is not privileged.

    That verdict is the whole subject of section 5b: the link lists never reach that function, so a
    body of nothing but ``craftIds: []`` passes it trivially and the relation is left to be guarded
    on its own."""
    return False


async def _no_status_policy(*_args, **_kwargs):
    return None


def _craft(craft_id: str, name: str) -> _Row:
    return _Row(id=craft_id, name=name)


def _artisan(artisan_id: str, created_by: str = "usr_7") -> _Row:
    return _Row(id=artisan_id, name=f"Artisan {artisan_id}", createdById=created_by)


def _drive_create(
    monkeypatch,
    fields: dict[str, Any],
    *,
    crafts: list[_Row] | None = None,
    artisans: list[_Row] | None = None,
    user: Any = None,
) -> tuple[_Client, dict[str, Any], list[Any]]:
    """POST /tools with the real clean/derive/pop, everything that touches Postgres stubbed."""
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
    """One stored ``ToolCraft`` / ``ToolArtisan`` row, as the two delegates hand it back."""
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
    """PATCH /tools/{id} with the real clean/derive/pop, everything that touches Postgres stubbed.

    ``craft_links``/``artisan_links`` are the rows the tool ALREADY holds. They are what decides
    whether the relation is populated, and therefore whether a non-privileged caller may replace it.
    """
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


# --------------------------------------------------------------------------------------
# 1. The schema: absent, [], null
# --------------------------------------------------------------------------------------


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
    """The distinction the whole contract rests on, at the layer that creates it.

    ``None`` here is what the route reads as "the caller said nothing about links". An empty list
    default would have made every save that never opened the picker delete every link on the record.
    """
    created = ToolCreate.model_validate(MINIMAL_TOOL)
    assert getattr(created, key) is None
    updated = ToolUpdate.model_validate({"remarks": "typo fixed"})
    assert getattr(updated, key) is None
    assert key not in updated.model_fields_set


@pytest.mark.parametrize("key", ["craftIds", "artisanIds"])
def test_an_empty_list_survives_the_parse_and_is_marked_as_sent(key):
    updated = ToolUpdate.model_validate({key: []})
    assert getattr(updated, key) == []
    # ``exclude_unset=True`` is how the route tells ``[]`` from absent, so the key must be in the
    # set — an empty list that arrived as a default would be indistinguishable from one a client sent.
    assert key in updated.model_fields_set
    assert updated.model_dump(exclude_unset=True) == {key: []}


@pytest.mark.parametrize("model", [ToolCreate, ToolUpdate], ids=["create", "update"])
@pytest.mark.parametrize("key", ["craftIds", "artisanIds"])
def test_an_explicit_null_is_refused_rather_than_read_as_absent(model, key):
    """422, naming the two things the caller might have meant.

    A null CANNOT be allowed to fall through to the route: ``clean_data`` would drop it (neither key
    is in ``CLEARABLE_KEYS`` and neither is a column, so ``_CLEARABLE_COLUMNS`` cannot carry it) and
    "clear every link" would arrive looking exactly like "leave them alone". Refusing at the door is
    the only place the two can still be told apart.
    """
    body = dict(MINIMAL_TOOL) if model is ToolCreate else {}
    body[key] = None
    with pytest.raises(ValidationError) as excinfo:
        model.model_validate(body)
    message = str(excinfo.value)
    assert key in message
    assert "send [] to clear the links" in message


def test_the_validator_does_not_fire_for_a_field_nobody_sent():
    """The precondition of the test above, stated so a pydantic upgrade cannot break it silently.

    Pydantic does not run a field validator over a default, so the ``None`` the validator refuses can
    only have come from a caller. If that ever changed, EVERY tool create in the fleet would 422.
    """
    assert ToolCreate.model_validate(MINIMAL_TOOL).craftIds is None
    assert ToolUpdate.model_validate({}).artisanIds is None


# --------------------------------------------------------------------------------------
# 2. The pop: ordering, duplicates, blanks
# --------------------------------------------------------------------------------------


def test_the_two_keys_never_reach_prisma():
    """Neither names a column, so either one left in ``data`` is an unknown-argument error on write.

    This is the assertion that makes the pop load-bearing rather than tidy: it is exactly the failure
    ``measurementMethods`` had before ``merge_field_provenance`` began popping it.
    """
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
    """A multi-select that sends one id twice has said one thing twice, not two things.

    FIRST occurrence and not last, because element 0 becomes ``tool.craftId`` and the names are
    joined in this order — "de-duplicate" without "in order" would silently re-pick which craft the
    row is filed under.
    """
    from app.api.routes import tools

    assert tools._pop_link_ids({"craftIds": ["c2", "c1", "c2", "c3", "c1"]}, "craftIds") == [
        "c2",
        "c1",
        "c3",
    ]


def test_blank_ids_are_dropped_and_a_list_of_only_blanks_clears():
    from app.api.routes import tools

    assert tools._pop_link_ids({"craftIds": ["c1", "  ", "", "c2"]}, "craftIds") == ["c1", "c2"]
    # Nothing but blanks is the same statement as ``[]``: there are no links. Inventing a third
    # reading for it would mean a client that sent [""] got behaviour no other client could ask for.
    assert tools._pop_link_ids({"craftIds": ["", "   "]}, "craftIds") == []


def test_surrounding_whitespace_does_not_make_a_second_id():
    from app.api.routes import tools

    assert tools._pop_link_ids({"artisanIds": [" a1", "a1 ", "a2"]}, "artisanIds") == ["a1", "a2"]


# --------------------------------------------------------------------------------------
# 3. The create path
# --------------------------------------------------------------------------------------


def test_a_create_derives_the_singular_columns_from_the_first_of_each_list(monkeypatch):
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=["c2", "c1"], artisanIds=["a9", "a3"], location=None),
        crafts=[_craft("c1", "Block Printing"), _craft("c2", "Bandhani")],
        artisans=[_artisan("a3"), _artisan("a9")],
    )
    assert data["craftId"] == "c2"
    assert data["artisanId"] == "a9"
    # In craftIds order, not in the order the lookup happened to return the rows.
    assert data["craftName"] == "Bandhani, Block Printing"


def test_the_joined_name_overrides_the_craft_name_in_the_same_body(monkeypatch):
    """``craftName`` must be DERIVABLE by the server from ``craftIds`` alone.

    A queued body replayed a fortnight later has to produce a ``craftName`` that agrees with its
    links, so the body's own value cannot be allowed to win. The accepted cost — a hand correction
    typed into the Craft name box is lost while crafts are linked — is recorded at
    ``_resolve_craft_links``, and the clients mitigate it by writing the joined value into the box on
    every selection change.
    """
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftName="Something Else", craftIds=["c1"], location=None),
        crafts=[_craft("c1", "Bandhani")],
    )
    assert data["craftName"] == "Bandhani"


def test_the_joined_name_is_not_re_title_cased(monkeypatch):
    """Assigned AFTER ``clean_data``, so each already-canonical ``Craft.name`` survives verbatim.

    A craft named with a lower-case particle — the register's own spelling — must not be "tidied" a
    second time on its way onto a tool, or the tool's craft box stops matching the craft register.
    """
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=["c1", "c2"], location=None),
        crafts=[_craft("c1", "Bandhani of Kutch"), _craft("c2", "ikat")],
    )
    assert data["craftName"] == "Bandhani of Kutch, ikat"


def test_artisan_name_and_place_are_left_to_the_body(monkeypatch):
    """Both are NOT NULL, both are already populated, and both are hand-correctable boxes.

    The client fills them from ``artisanIds[0]``'s record on selection, exactly as the single-select
    already does. Deriving them here would overwrite a researcher's correction on every save.
    """
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
    assert data["artisanName"] == "L. Devi (Corrected)"  # title-cased by clean_data, not replaced
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
    # The include on the create read both arrays a moment before those rows existed, so the 201 would
    # otherwise say this tool has no crafts while answering the request that gave it two.
    assert hydrated == [("artisanLinks", "craftLinks")]


def test_a_create_that_mentions_no_list_writes_no_link_row_and_re_reads_nothing(monkeypatch):
    client, data, hydrated = _drive_create(monkeypatch, dict(MINIMAL_TOOL, location=None))
    assert client.toolcraft.created_many == []
    assert client.toolcraft.deleted_many == []
    assert client.toolartisan.created_many == []
    assert client.toolartisan.deleted_many == []
    assert hydrated == []
    # And the body's own singular columns are untouched by a feature it did not use.
    assert "craftId" not in data and "artisanId" not in data


def test_a_create_that_states_an_empty_selection_deletes_nothing(monkeypatch):
    """``fresh=True``: a row created one statement ago has no links to delete.

    BOTH RECORD FORMS STATE THEIR FULL SELECTION ON EVERY SAVE, ``[]`` included — the web client's
    payload comment says so in as many words — so without the flag every create of a tool with no
    crafts and no artisans would pay two cross-region round trips to delete nothing.
    """
    client, _data, hydrated = _drive_create(
        monkeypatch, dict(MINIMAL_TOOL, craftIds=[], artisanIds=[], location=None)
    )
    assert client.toolcraft.deleted_many == []
    assert client.toolartisan.deleted_many == []
    assert client.toolcraft.created_many == []
    assert client.toolartisan.created_many == []
    # Nothing moved, so nothing is re-read either.
    assert hydrated == []


# --------------------------------------------------------------------------------------
# 4. The refusals: an id nobody can see, and an artisan somebody else created
# --------------------------------------------------------------------------------------


def test_a_craft_id_the_caller_cannot_see_is_a_404_before_any_write(monkeypatch):
    """The WHOLE request, not a silently dropped element.

    Answering 201 while storing two of the three crafts a researcher ticked is the shape of failure
    nobody finds for a season.

    "NOT VISIBLE" AND "DOES NOT EXIST" ARE THE SAME ANSWER on this path, which is what makes ONE
    stubbed lookup able to stand for both: ``records.viewable_where`` is empty for every signed-in
    account, and a by-id lookup in this repository does not compose it in any case — ``require_record``
    and ``assign_tool_artisans`` are both bare ``where={"id": …}``. A craft the caller cannot see is
    therefore a craft the lookup does not return, which is exactly the row this stub withholds.
    """
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
    """The batch is validated before anything is written, so a refusal is a clean refusal."""
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
    """The same refusal ``assign_tool_artisans`` gives, reached through a different door.

    The message is that route's, verbatim: two wordings of one rule is how the two doors come to be
    described differently in a support thread.
    """
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "is_admin", lambda _user: False)

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
    """``tool=None`` on the create path means "the caller is about to be the owner".

    Spelled as an explicit branch rather than left to fall out of a ``getattr`` on ``None`` — which
    would have opened the gate for the same reason but by accident.
    """
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, artisanIds=["a_theirs"], location=None),
        artisans=[_artisan("a_theirs", created_by="somebody_else")],
    )
    assert data["artisanId"] == "a_theirs"


# --------------------------------------------------------------------------------------
# 4b. THE DERIVED CRAFT NAME AND THE BOUND ITS OWN UPDATE SCHEMA WILL TAKE BACK
# --------------------------------------------------------------------------------------
#
# ``_resolve_craft_links`` OVERWRITES ``craftName`` with every linked craft's name joined ", ", and
# it does so AFTER pydantic has validated the body — so for as long as the join was unmeasured, the
# route could store a value ``ToolUpdate`` refuses. That is not a cosmetic 422. Both record forms
# seed the Craft-name box from the STORED value and echo it back on every save (``ToolForm`` from
# ``initial.craftName``, the handset from ``editing?.craftName``), so the next edit of that tool —
# from either client, about any field — is refused on a box the designer never typed in, for ever;
# and ``saveOrQueue`` will not queue a 4xx, so offline the only remaining control is Discard, which
# destroys the record and the photographs staged against it.
#
# THE PROPERTY UNDER TEST IS THEREFORE A ROUND TRIP AND NOT A NUMBER: whatever this route stores in
# ``craftName``, its own ``ToolUpdate`` must accept back. The first test below pins the two spellings
# of the bound to one value so they cannot drift apart again; the last one drives the round trip
# itself, because that is the invariant and the constant is only how it is kept.


def _crafts_joining_to(total: int, count: int = 6) -> list[_Row]:
    """*count* crafts whose names join, ", "-separated, to exactly *total* characters.

    Built rather than written out because the threshold is a LENGTH and not a craft count: two
    registered crafts of ninety characters cross it (``Craft.name`` is itself bounded at 180), and a
    fixture of a dozen hand-typed names would pin the wrong quantity and drift the day the bound
    moves.
    """
    body = total - 2 * (count - 1)
    base, extra = divmod(body, count)
    return [
        _craft(f"c{index}", f"Craft{index}".ljust(base + (1 if index < extra else 0), "x"))
        for index in range(count)
    ]


def test_the_bound_the_route_enforces_is_the_one_both_tool_schemas_declare():
    """One number, three places, and the test exists so it stays one number.

    The route imports the bound from ``schemas/records`` rather than repeating the literal, and both
    tool models take their ``max_length`` from the same name. A second spelling is how the server
    comes to store a value its own update schema refuses — which is the whole defect this section is
    about, reintroduced by a tidy-up.
    """
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
    """A 422 naming the SELECTION, which is the thing the caller can change.

    Not a length on ``craftName``: the caller did not write that column, the server did, and a
    refusal that names the box rather than the ticks reads as the form being broken.
    """
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
    """The same batch-before-write rule as the 404 arm, and for the same reason."""
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
    """The check sits ABOVE ``data["craftId"] = craft_ids[0]``, and this is what that buys.

    ``_resolve_craft_links`` MUTATES its argument and the caller goes on to write that same dict, so
    a raise that had already assigned half the derivation would leave a live object nobody re-reads
    carrying a ``craftId`` from a selection the request refused. Driven directly rather than through
    the route, because the route's copy of the body is not the dict the helper was handed.
    """
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
    """A tool that already HAS links must not lose them to a request that is about to be refused.

    ``_write_links`` is replacement-never-a-diff, so a refusal raised after the transaction opened
    would still have to roll back; raised before it, there is nothing to roll back. The assertion is
    that the transaction was never entered at all.
    """
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
    """The bound is inclusive, the same way ``max_length`` is.

    An off-by-one here would refuse a selection the schema accepts, which is the mirror image of the
    defect and just as invisible from the outside.
    """
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH)
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=[craft.id for craft in crafts], location=None),
        crafts=crafts,
    )
    assert len(data["craftName"]) == TOOL_CRAFT_NAME_MAX_LENGTH


def test_the_name_the_route_stores_is_one_its_own_update_schema_takes_back(monkeypatch):
    """THE INVARIANT, driven end to end: derive, then re-send.

    A record form's next save carries the stored ``craftName`` back in the body, so a value this
    route writes and ``ToolUpdate`` refuses is a record no client can ever edit again. Driving the
    REAL model rather than re-measuring the string is the point — the bound is pydantic's to apply,
    and this asserts that it applies to what the route actually produced.
    """
    from app.schemas.records import TOOL_CRAFT_NAME_MAX_LENGTH

    crafts = _crafts_joining_to(TOOL_CRAFT_NAME_MAX_LENGTH)
    _client, data, _hydrated = _drive_create(
        monkeypatch,
        dict(MINIMAL_TOOL, craftIds=[craft.id for craft in crafts], location=None),
        crafts=crafts,
    )
    echoed = ToolUpdate(craftName=data["craftName"], craftIds=[craft.id for craft in crafts])
    assert echoed.craftName == data["craftName"]


# --------------------------------------------------------------------------------------
# 5. The partial-update path
# --------------------------------------------------------------------------------------


def test_a_patch_that_mentions_neither_list_leaves_both_link_sets_alone(monkeypatch):
    """THE CASE EVERY EXISTING CLIENT IS, and the one a wrong default would silently destroy.

    A handset built before this feature saves a tool by sending the boxes it knows about. Neither key
    is in the body, so no ``delete_many`` may be issued — an installed APK correcting a typo in
    ``remarks`` must not wipe the crafts somebody chose on the web.
    """
    client, data = _drive_patch(monkeypatch, {"remarks": "Corrected a typo."})
    assert client.tx_client.toolcraft.deleted_many == []
    assert client.tx_client.toolcraft.created_many == []
    assert client.tx_client.toolartisan.deleted_many == []
    assert client.tx_client.toolartisan.created_many == []
    assert "craftId" not in data and "craftName" not in data and "artisanId" not in data
    # NOT EVEN READ. ``deleted_many``/``created_many`` prove no WRITE went out; this proves no
    # question was asked either, which is the half the relation guard could have broken quietly —
    # two cross-region round trips on every save of every tool, for a verdict about a list nobody
    # sent. The next test says why the attribute access matters as much as the query.
    assert client.tx_client.toolcraft.queried == []
    assert client.tx_client.toolartisan.queried == []


def test_a_patch_that_mentions_neither_list_never_reaches_for_a_join_delegate(monkeypatch):
    """The regression: the relation guard used to resolve ``tx.toolcraft`` BUILDING ITS LOOP TUPLE.

    ``for key, delegate, column, sent in (("craftIds", tx.toolcraft, …), …)`` evaluates both
    attributes before ``if sent is None`` can say the request mentioned no link list, so a PATCH of
    nothing but ``remarks`` reached into both join delegates on its way past. Against a real Prisma
    client that is invisible; against anything standing in for one it is an ``AttributeError``, and
    it turned every tool case in ``tests/test_record_patch_clearing`` red — a module about
    ``clearable`` that has no reason to know this route has join tables at all.

    So the client here deliberately offers ONLY ``tooldocumentation``, which is exactly the shape
    that broke. A fake carrying the two join delegates — every other driver in this file — cannot
    tell the eager form from the lazy one, which is why this test builds its own instead of reusing
    ``_drive_patch``.
    """
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
    """Each key is independent. Changing the crafts must not disturb the artisan links."""
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
    """``[]`` unlinks; it does not blank the record's craft, artisan or place.

    ``craftName``/``artisanName``/``place`` are NOT NULL and hold the last thing a person stated. A
    tool whose links were cleared still says which craft it was documented against, which is the same
    property ``ToolArtisan`` has had since it shipped — and the reason ``artisanName`` is NOT NULL.
    """
    client, data = _drive_patch(monkeypatch, {"craftIds": [], "artisanIds": []})
    assert "craftName" not in data
    assert "artisanName" not in data
    assert "place" not in data
    assert "craftId" not in data
    assert "artisanId" not in data
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]


def test_a_patch_replaces_the_set_rather_than_adding_to_it(monkeypatch):
    """REPLACEMENT, NEVER A DIFF — un-ticking a craft is what a record form's multi-select means.

    ``POST /tools/{id}/artisans`` remains the ADDITIVE door and is untouched by this; the two are
    different verbs and the route comment says which is which.
    """
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
    """``db.tx()`` hands back a DIFFERENT client.

    A write through the module singleton would sit OUTSIDE the block however it reads, so a tool
    whose links were replaced by an edit the transaction then rolled back would keep the new links.
    This is the same trap ``guard_record_edit``'s ``client=tx`` note sets out, and a stub whose
    ``tx()`` returned ``self`` could not see it — which is why this module's fake client does not.
    """
    client, _data = _drive_patch(
        monkeypatch, {"craftIds": ["c1"]}, crafts=[_craft("c1", "Bandhani")]
    )
    assert client.tx_entered == 1
    assert client.tx_client.toolcraft.created_many  # inside
    assert client.toolcraft.created_many == []  # and never outside


def test_a_patch_writes_the_links_before_the_row_so_the_response_describes_them(monkeypatch):
    """The update carries ``include=INCLUDE``, which reads both link arrays back.

    Issued after the update, the ``delete_many``/``create_many`` would leave the PATCH response
    describing the links this very request replaced — a save that appears not to have saved.
    """
    from app.api.routes import tools

    client, _data = _drive_patch(
        monkeypatch, {"craftIds": ["c1"]}, crafts=[_craft("c1", "Bandhani")]
    )
    assert client.tx_client.toolcraft.created_many  # the write happened at all
    # The recording stubs cannot see each other's ordering, so the property is asserted on the SOURCE
    # of the route: the link write must appear above the row update inside the `async with`. Reading
    # the source is the same device `tests/test_anonymous_route_sweep` uses, and for the same reason
    # — the alternative is a stub that records a global sequence number, which is a second mechanism
    # to keep correct.
    body = inspect.getsource(tools.update_tool)
    assert body.index("_write_links(tx") < body.index("tx.tooldocumentation.update")


def test_the_pop_happens_before_the_guard_so_the_ledger_sees_the_derived_columns(monkeypatch):
    """Neither key is in ``REVISION_SKIP_FIELDS``, and neither ever reaches it.

    A change of linked crafts IS an edit somebody made and belongs in the revision ledger — it gets
    there through the DERIVED ``craftId``/``craftName``, which is the honest summary. What must never
    happen is the raw list reaching ``guard_record_edit``, because the ledger would then hold a JSON
    array nobody can read as a change.
    """
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


# --------------------------------------------------------------------------------------
# 5b. WHO MAY REPLACE A POPULATED LINK SET  —  the gate the empty list used to walk past
# --------------------------------------------------------------------------------------
#
# ``craftIds: []`` / ``artisanIds: []`` derive NOTHING: both resolvers return at their ``if not ...``
# line. So ``data`` reached ``guard_record_edit`` empty, ``assert_can_contribute_fields`` iterated an
# empty dict and could lock no field, and ``_write_links``'s two ``delete_many`` calls then ran for
# ANY signed-in account — every ``ToolCraft`` and ``ToolArtisan`` row on any tool in the repository,
# deleted, answered 200, and (``data`` being empty) with no ``RecordRevision`` naming who did it.
# The PATCH is ``get_current_user`` and not ``require_record_creator``, so "any signed-in account"
# reached below the create floor as well.
#
# THE RULE IS A DIFF AND NOT A COUNT, and that is where it parts company with
# ``workshops.update_workshop``'s ``link_count > 0``. A workshop's rosters are edited on their own
# panel; a tool's links ride the RECORD FORM, which states its whole selection on every save. A count
# test would refuse every non-privileged save of every tool that has a craft link — which, after the
# backfill in 20260915100000, is every tool that names a craft — and would revoke "fill an empty
# field on someone else's record" repository-wide. So the question asked is the one
# ``assert_can_contribute_fields`` asks: did the CALLER change a populated thing.


def test_an_empty_list_from_a_non_owner_is_refused_and_deletes_nothing(monkeypatch):
    """THE CRITICAL ONE. Two words of JSON used to wipe every link row on any tool.

    The refusal is raised INSIDE the transaction, so the row update goes back with it — the ordering
    ``workshops.update_workshop`` settled and states at length.
    """
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
    """A rejected request must leave no partial state behind, and the delete is the state."""
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
    """``craftIds: [<the tool's own craftId>]`` derived scalars that MATCHED, so the field guard passed.

    ``_write_links`` is replacement-never-a-diff, so every craft link after the first was deleted by a
    body whose every derived column agreed with the stored row. A count of the rows cannot see this
    and a diff of the ids can.
    """
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
    """THE CASE A COUNT WOULD HAVE BROKEN, and it is the ordinary one.

    Both record forms send their entire selection on every save, so a contributor filling an empty
    ``remarks`` box on somebody else's tool arrives here carrying every link the tool already has.
    Restating a set is not changing it.
    """
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
    # Replacement still happens — the route does not diff the WRITE, only the permission question.
    assert client.tx_client.toolcraft.deleted_many == [{"toolId": "tol_1"}]
    assert client.tx_client.toolcraft.created_many == [[{"toolId": "tol_1", "craftId": "c1"}]]


def test_tick_order_alone_is_not_a_change_of_the_relation(monkeypatch):
    """Membership is all the join table holds — it has no ordinal column.

    Which craft is FIRST is observable only through ``craftId``/``craftName``, and those are ordinary
    columns guarded by ``assert_can_contribute_fields`` on the call above. Refusing here as well would
    be one rule enforced twice with two different messages.
    """
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
    """The relation guard is ``assert_can_contribute_relation``, which refuses only a POPULATED one.

    "Fill an empty field on someone else's record" is a permission every role has, and a relation
    nobody has linked yet is an empty field.
    """
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
    """⚠ THE HOLE THE FIRST VERSION OF THE RELATION GUARD LEFT, AND IT WAS NOT AN EXOTIC STATE.

    ``craftName`` is reported to ``guard_record_edit`` as ``derived=``, which withholds it from
    ``assert_can_contribute_fields``; the compensating check is THIS loop, and it used to key on
    ``bool(stored)`` — the rows in ``ToolCraft``. But ``ToolDocumentation.craftName`` is NOT NULL
    while ``craftId`` is nullable, migration 20260915100000 backfills a join row only ``WHERE
    t."craftId" IS NOT NULL``, and ``ToolDocumentation.craft`` is ``onDelete: SetNull`` while
    ``ToolCraft.craft`` is ``onDelete: Cascade``. So every tool whose craft was TYPED rather than
    picked, and every tool whose craft was later deleted from the register, holds a populated name
    over an empty join — and a stranger could send ``craftIds: [<any craft>]``, pass the field guard
    (which never saw ``craftName``) and the relation guard (which saw no rows), and have the
    server-derived name written over the owner's, answered 200.
    """
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
    """The column is the thing being protected, so the column is what is asserted."""
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
    """"Fill an empty field on someone else's record" survives, which is the half that had to.

    The widened question is ``bool(stored) or the name is populated``, not "the craft half is always
    locked". A tool with neither a join row nor a name is genuinely unlinked and anybody may link it.
    """
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
    """THE ASYMMETRY, ASSERTED SO NOBODY "TIDIES" THE TWO HALVES INTO ONE.

    ``artisanName``/``place`` are NOT derived — they arrive from the body and face
    ``assert_can_contribute_fields`` like any other typed column — so there is no exemption on the
    artisan half for a denormalised name to compensate for. And ``ToolArtisan`` has always
    legitimately held rows beyond ``artisanId`` (``POST /tools/{id}/artisans`` predates this
    release), so an empty join under a populated name says nothing about that relation.
    """
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_admin", lambda _user: False)

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
    """Owner, admin, outranking professor, EDIT-grantee — ``guard_record_edit``'s own verdict.

    Asking any other way here would be a second authorization rule to keep in step with the first,
    which is the reason ``processes.update_process`` reuses that return value rather than recomputing.
    """
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
    """The ledger entry the empty list could never produce, and the reason it could not.

    ``data`` is empty on a ``craftIds: []`` PATCH, so ``record_revision`` returned at its own
    ``if not changes`` line and the rows were hard-deleted with nothing naming who did it and no way
    to reconstruct the set — in the one table whose whole purpose is that a change cannot be made
    invisibly. The ids are what an admin needs to put the links back, so the ids are what is recorded.
    """
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": [], "artisanIds": []},
        craft_links=[_link(craftId="c2"), _link(craftId="c1")],
        artisan_links=[_link(artisanId="a1")],
        guard=_privileged,
    )
    written = client.tx_client.recordrevision.created
    assert len(written) == 1
    # ``Json`` wraps the dict on its way to Prisma; ``.data`` is what it was built from.
    payload = getattr(written[0]["changes"], "data", written[0]["changes"])
    assert payload["craftIds"] == {"old": ["c1", "c2"], "new": []}
    assert payload["artisanIds"] == {"old": ["a1"], "new": []}
    assert written[0]["recordId"] == "tol_1"
    assert written[0]["editedById"] == "usr_7"


def test_an_unchanged_set_writes_no_audit_row(monkeypatch):
    """``record_revision``'s contract is "no-op when nothing meaningful changed", and a restated
    selection is nothing meaningful. Sorted on both sides, so a different tick order is not logged as
    a change either."""
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c2", "c1"]},
        crafts=[_craft("c1", "Block Printing"), _craft("c2", "Bandhani")],
        craft_links=[_link(craftId="c1"), _link(craftId="c2")],
        guard=_privileged,
    )
    assert client.tx_client.recordrevision.created == []


def test_the_relation_guard_reads_through_the_transaction(monkeypatch):
    """On the module client the reads would answer from outside the block, which is the same trap
    ``_write_links`` and ``guard_record_edit``'s ``client=tx`` both set out."""
    client, _data = _drive_patch(
        monkeypatch,
        {"craftIds": ["c1"]},
        crafts=[_craft("c1", "Bandhani")],
        craft_links=[_link(craftId="c1")],
        guard=_privileged,
    )
    assert client.tx_client.toolcraft.queried == [{"toolId": "tol_1"}]
    assert client.toolcraft.queried == []


# --------------------------------------------------------------------------------------
# 5c. THE DERIVED NAME, AND THE 403 IT USED TO CAUSE ON A FIELD NOBODY TOUCHED
# --------------------------------------------------------------------------------------


def test_the_route_reports_the_derived_craft_name_to_the_edit_guard(monkeypatch):
    """``craftName`` is the one column here no caller can state while crafts are linked.

    ``_resolve_craft_links`` replaces whatever the body said with the joined ``Craft.name``s — the
    accepted cost, asserted on the create path above — so a contributor refused over it is refused
    over the server's own work. ``craftId``/``artisanId`` are NOT reported: they are element 0 of the
    caller's own list and keep the ordinary guard.
    """
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
    """A ``craftName`` in a body that carries no ``craftIds`` is the CALLER's and faces the guard.

    The empty set is load-bearing: report a name the server did not write and a contributor could
    retype somebody else's craft name with no refusal at all.
    """
    seen: dict[str, Any] = {}

    async def _capture(_record, _user, _data, _kind, *, client=None, derived=()):
        seen["derived"] = set(derived)
        return True

    _drive_patch(monkeypatch, {"craftName": "Typed By Hand"}, guard=_capture)
    assert seen["derived"] == set()


def test_the_derived_name_is_withheld_from_the_contributor_guard_and_from_nothing_else(monkeypatch):
    """The live defect, driven through the REAL guard rather than a stub.

    A tool whose stored ``craftName`` has drifted from its craft's current name is reachable two ways
    — ``crafts.update_craft`` cascades a rename to no tool, and the box is independently editable.
    Every non-privileged editor was then refused ``403 ... populated field(s): craftName`` on a save
    that only filled an empty box, for that record permanently.
    """
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
    # AND FROM NOTHING ELSE: the ledger still sees the change, under the name of whoever asked for it.
    assert ledger["craftName"] == "Bandhani"


# --------------------------------------------------------------------------------------
# 5d. THE ASSIGN-ANY GATE APPLIES TO THE IDS BEING ADDED, NOT TO THE WHOLE RESTATED LIST
# --------------------------------------------------------------------------------------


def test_a_non_owner_may_resave_a_tool_linked_to_somebody_elses_artisan(monkeypatch):
    """A record form states its ENTIRE selection on every save, so every stored link arrives here.

    Ownership-checking that list refused the save outright: a tool documented by A and linked to A's
    artisan X could not be saved by anybody who was not A, an admin or an EDIT-grantee — every
    professor, every directorate tier, every inspector, every ordinary contributor — over a link
    they were not proposing to make. ``assign_tool_artisans`` has never had the defect: it narrows to
    ``aid not in have`` first. This now does the same.
    """
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_admin", lambda _user: False)

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
    """The narrowing is to the ids being ADDED and to nothing else: the rule itself is unchanged."""
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_admin", lambda _user: False)

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
    """The EXISTENCE arm deliberately does not narrow.

    The whole list is about to be rewritten as link rows, so an id naming nothing is refused whether
    it is new or stored — a 404 here is the batch-before-write rule, not a permission.
    """
    from app.api.routes import tools

    async def _no_tier(*_args, **_kwargs):
        return "VIEW"

    async def _no_rank(*_args, **_kwargs):
        return False

    monkeypatch.setattr(tools, "effective_tier_for_record", _no_tier)
    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _no_rank)
    monkeypatch.setattr(tools, "is_admin", lambda _user: False)

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
    """The read happens only on the branch that can refuse.

    Same shape as ``guard_record_edit`` deferring its grant lookup behind the rank test: nobody pays
    a cross-region round trip for a set whose answer cannot change theirs.
    """
    client, _data = _drive_patch(
        monkeypatch,
        {"artisanIds": ["a1"]},
        artisans=[_artisan("a1")],
        artisan_links=[_link(artisanId="a1")],
        guard=_privileged,
    )
    # The owner is ``usr_7`` on the default stored row, so ``_may_manage_tool_links`` short-circuits
    # and ``_resolve_artisan_links`` never reads ``ToolArtisan`` on the module client.
    assert client.toolartisan.queried == []


def test_the_link_manager_gate_grants_an_outranking_professor(monkeypatch):
    """Its docstring has always claimed to be ``guard_record_edit``'s ``privileged``, and it was not.

    That function's verdict is admin OR owner OR ``may_edit_lower_ranked_record`` OR an EDIT grant.
    This helper had three of the four, so a professor was privileged for every column on the row and
    unprivileged for its artisan links — passing ``workshops.update_workshop``, which gates the same
    whole-roster replacement on that very verdict, and refused here.
    """
    from app.api.routes import tools

    asked: list[Any] = []

    async def _outranks(_user, creator_id):
        asked.append(creator_id)
        return True

    async def _never(*_args, **_kwargs):
        raise AssertionError("the grant lookup must not be reached once the rank clause answers yes")

    monkeypatch.setattr(tools, "may_edit_lower_ranked_record", _outranks)
    monkeypatch.setattr(tools, "effective_tier_for_record", _never)
    monkeypatch.setattr(tools, "is_admin", lambda _user: False)

    allowed = asyncio.run(
        tools._may_manage_tool_links(
            _Row(id="tol_1", createdById="somebody_else"), "tol_1", _editor("usr_7")
        )
    )
    assert allowed is True
    assert asked == ["somebody_else"]


# --------------------------------------------------------------------------------------
# 5e. THE BODY THAT PREDATES BOTH KEYS — an outbox entry queued by the build before this one
# --------------------------------------------------------------------------------------
#
# "Absent means leave the links alone" is right for a client that KNOWS these keys and chose not to
# mention them. It is wrong for one that cannot: at HEAD both clients sent ``craftId`` as the WHOLE
# statement about a tool's craft, and ``lib/offline.ts`` keeps a queued body byte-for-byte across
# deploys on purpose, so those bodies replay against this API after the upgrade. Migration
# 20260915100000 has meanwhile backfilled one ``ToolCraft`` row per tool from the old scalar, so the
# stale link is not empty — it names the craft the designer removed.


def test_a_legacy_body_that_moves_the_craft_scalar_rewrites_the_join(monkeypatch):
    """THE DRAIN AFTER THE UPGRADE. ``craftId`` moved, no ``craftIds`` anywhere in the body.

    Without this arm the row was updated and ``ToolCraft`` was left holding the OLD craft, so the
    record read ``craftId = B`` / ``craftLinks = [A]``. The next person to open it on the new form
    got both crafts ticked and the mount reconciliation rewrote the required Craft name box to
    "Block Printing, Bandhani" — which their next save then stored, in the denormalised column every
    export and exact-match craft lookup reads, with nothing on screen having said so.
    """
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
    """ONLY A CHANGE IS A STATEMENT. A body echoing the craft the tool already has says nothing.

    Acting on it would delete-and-recreate a link row that was already right, and would turn a
    remarks-only save into a 404 the day that craft is deleted from the register.
    """
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
    """THE HALF THAT MUST NOT GET THE SAME TREATMENT.

    ``ToolArtisan`` has always legitimately held rows beyond ``artisanId`` — ``POST
    /tools/{id}/artisans`` predates this release — so reading a legacy ``artisanId`` as the whole set
    would delete assignments nobody touched.
    """
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
    """A client that sends ``craftIds`` has said everything, and the scalar in the same body is
    already overridden by ``_resolve_craft_links``. The legacy arm must not fire for it."""
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


# --------------------------------------------------------------------------------------
# 6. The read contract: both arrays, always present, in a stated order
# --------------------------------------------------------------------------------------


def test_the_relations_tuple_serves_both_link_arrays_back():
    from app.api.routes import tools

    fields = [rel.field for rel in tools.RELATIONS]
    assert "craftLinks" in fields and "artisanLinks" in fields
    # ``INCLUDE`` is DERIVED from ``RELATIONS``, so the write paths cannot describe a different tool
    # from the read paths. Nesting the parent row is what makes the names available without a second
    # request.
    assert tools.INCLUDE["craftLinks"] == {"include": {"craft": True}}
    assert tools.INCLUDE["artisanLinks"] == {"include": {"artisan": True}}
    # And the re-hydration tuple is exactly the two, so a create pays for two queries and not seven.
    assert [rel.field for rel in tools._LINK_RELATIONS] == ["artisanLinks", "craftLinks"]


def test_craft_links_come_back_in_the_order_craft_name_records():
    """The join table has no ordinal column; ``craftName`` is the ordinal, and ``Craft.name`` is unique.

    ``hydrate_relations`` issues one batched ``find_many`` per to-many relation with NO ``order``, so
    without this the array arrives in whatever order Postgres found the rows — stable enough on a
    quiet table to look deliberate, and free to change under a vacuum.
    """
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
    """Stated rather than random, and never lost.

    A craft renamed after the tool was saved leaves ``craftName`` holding a name no link carries. The
    link is still a true fact about the row, so it goes last in ``createdAt asc, id asc`` — the same
    total order the artisan array uses — rather than being sorted to the front by a missing key.
    """
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
    """The order ``_assigned_artisans`` already produces and ``GET /tools/{id}/artisans`` promises.

    NOT re-sorted by name: ``design_workshops._linked_artisan_names`` does that for the report and its
    docstring is the record of that decision. Two surfaces sorting the same rows for two different
    reasons is how one of them silently stops being changeable.
    """
    from app.api.routes import tools

    encoded = {
        "artisanLinks": [
            {"id": "z", "artisanId": "a2", "createdAt": "2026-05-02"},
            {"id": "a", "artisanId": "a1", "createdAt": "2026-05-01"},
            {"id": "b", "artisanId": "a3", "createdAt": "2026-05-02"},
        ]
    }
    tools._order_links(encoded)
    # a1 is oldest; a3 and a2 share an instant and are broken by ``id`` — "b" before "z". The
    # tiebreak is the point rather than an implementation detail: ``createdAt`` alone is not a TOTAL
    # order, and the whole reason this function exists is that an order Postgres is free to break
    # differently each time is indistinguishable from a deliberate one. Same argument, same fix, as
    # ``records.with_id_tiebreak``.
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


# --------------------------------------------------------------------------------------
# 7. GET /artisans?craftIds= — the plural scope
# --------------------------------------------------------------------------------------


def test_absent_means_every_craft_not_none_of_them():
    # The same distinction ``resolve_workshop_ids`` exists for: "the control is at its default" and
    # "the user asked for nothing" must not be spelled the same way.
    assert resolve_craft_ids(None) is None
    assert resolve_craft_ids([]) is None
    assert resolve_craft_ids([""]) is None
    assert resolve_craft_ids(["   "]) is None
    assert resolve_craft_ids([" , , "]) is None


def test_both_spellings_clients_build_are_accepted():
    # The web joins with commas; Android repeats the parameter.
    assert resolve_craft_ids(["c1", "c2"]) == ["c1", "c2"]
    assert resolve_craft_ids(["c1,c2"]) == ["c1", "c2"]
    assert resolve_craft_ids(["c1, c2", "c3"]) == ["c1", "c2", "c3"]


def test_ids_are_deduplicated_in_first_seen_order():
    assert resolve_craft_ids(["c2", "c1", "c2"]) == ["c2", "c1"]
    assert resolve_craft_ids(["c2,c1", "c2"]) == ["c2", "c1"]


def test_there_is_no_reserved_none_token_on_this_filter():
    """"none" is an ID here, not a sentinel, and that is deliberate.

    ``UNASSIGNED_WORKSHOP`` exists because a record legitimately belongs to no workshop and a reader
    wants to find those. This parameter serves the tool form's multi-craft artisan picker, whose
    question is "who practises these crafts"; inventing a second spelling of a sentinel nothing sends
    is how two filters come to disagree about what it means.
    """
    assert resolve_craft_ids(["none"]) == ["none"]


def test_the_plural_scope_narrows_through_and_filters_and_leaves_the_singular_alone(monkeypatch):
    """Into ``and_filters``, never ``where["craftId"]``.

    The singular filter assigns that key DIRECTLY, so a second assignment would silently discard
    whichever was written first — and both are legal on one request. When both are sent both narrow.
    """
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

    # EVERY ``Query(...)``-DEFAULTED PARAMETER IS NAMED, because this calls the coroutine DIRECTLY:
    # FastAPI substitutes a ``Query`` default only while it is serving the route, so one left unnamed
    # arrives inside the body as the marker object itself and the first thing to touch it raises. The
    # four are ``craftIds``, ``workshopIds``, ``page`` and ``pageSize`` — re-check with
    # ``grep -n "Query(" backend/app/api/routes/artisans.py``.
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
    # The route's ordering is NOT the picker's ordering and no client may depend on it being so: the
    # A-Z-by-craft ordering the tool form draws is computed client-side, on the page it received.
    assert captured["order"] == {"createdAt": "desc"}


# --------------------------------------------------------------------------------------
# 8. The migration and the model, read off disk
# --------------------------------------------------------------------------------------
#
# A join table's SQL is the one part of this feature no route exercises and no stub can stand in for,
# and it is the part a ``prisma migrate dev`` regeneration or a re-baseline would quietly rewrite.
# ``tests/test_design_workshop_review_loop.test_the_migration_matches_the_model`` reads its own
# migration for the same reason and says so: what only exists in a SQL file exists nowhere a test can
# see unless a test opens the file.

BACKEND = pathlib.Path(__file__).resolve().parents[1]
MIGRATION = BACKEND / "prisma" / "migrations" / "20260915100000_tool_craft_links" / "migration.sql"
SCHEMA = BACKEND / "prisma" / "schema.prisma"


def _sql() -> str:
    """The migration's statements with every comment line stripped."""
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
    # The names Prisma itself would choose, so `migrate diff` sees no drift to "fix".
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
    """The claim its own header makes, checked rather than taken on trust.

    This is the file's whole safety argument: it runs against a populated production database with no
    automatic rollback behind it, so an ``ALTER``/``DROP`` on a live table that crept in later would
    be discovered by the deploy.
    """
    body = _sql()
    assert "DROP " not in body.upper()
    # The only ALTER is the pair that adds this new table's own foreign keys.
    alters = [line.strip() for line in body.splitlines() if line.strip().startswith("ALTER TABLE")]
    assert alters and all(line.startswith('ALTER TABLE "ToolCraft" ADD CONSTRAINT') for line in alters), alters
    # And the only table written to is the new one; the backfill READS ToolDocumentation.
    assert body.count("INSERT INTO") == 1
    assert 'INSERT INTO "ToolCraft"' in body


def test_the_backfill_gives_every_tool_that_names_a_craft_its_link():
    """Without it, the first save of an existing tool through the multi-select would tick nothing.

    A tool whose craft "disappeared" is indistinguishable from a tool that never had one, and the
    researcher's obvious repair — pick the craft again — is the one action that rewrites the link.
    """
    body = _sql()
    assert 'FROM "ToolDocumentation" t' in body
    assert 'WHERE t."craftId" IS NOT NULL' in body
    # The id is DERIVED so a re-run of a half-applied migration cannot mint a second row for one
    # pair, and it is shaped like a cuid so it reads back like any Prisma-generated id.
    assert '\'c\' || substr(md5(t."id" || \':\' || t."craftId"), 1, 24)' in body
    # `createdAt` is the TOOL'S OWN. CURRENT_TIMESTAMP would stamp every backfilled link with one
    # instant, and "oldest first" over the whole table would then be arbitrary.
    assert 't."createdAt"' in body
    assert 'ON CONFLICT ("toolId", "craftId") DO NOTHING' in body


def test_the_migration_is_re_runnable_and_never_concurrent():
    """Both properties this directory's runner depends on, in one place.

    ``IF NOT EXISTS`` plus the ``pg_constraint`` guards are why an interrupted apply can be re-run.
    ``CREATE INDEX CONCURRENTLY`` is the opposite: Prisma sends a migration as ONE multi-statement
    query inside an implicit transaction, so it fails with PG 25001 / P3018 and leaves a failed
    ``_prisma_migrations`` row blocking every later migration — 20260726200000 sets that out at
    length.
    """
    body = _sql()
    assert "CONCURRENTLY" not in body
    assert body.count("IF NOT EXISTS") >= 3
    assert body.count("FROM pg_constraint WHERE conname") == 2


def test_the_model_mirrors_tool_artisan_column_for_column():
    """Two join tables off one parent that disagree about their own shape is how a later reader comes
    to believe one of them means something the other does not."""
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
    """``craftLinks`` on the tool, ``toolLinks`` on the craft.

    ``toolLinks`` is not a choice: ``Artisan`` already spells its ``ToolArtisan`` back-relation that
    way. And both names are what ``RELATION_LEDGER`` in ``tests/test_reference_carry`` now accounts
    for — a relation this schema declares and that ledger does not name fails the suite.
    """
    assert "craftLinks ToolCraft[]" in _model("ToolDocumentation")
    assert "artisanLinks ToolArtisan[]" in _model("ToolDocumentation")
    assert "toolLinks ToolCraft[]" in _model("Craft")
    assert "toolLinks ToolArtisan[]" in _model("Artisan")

