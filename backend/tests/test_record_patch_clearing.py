"""B11 at the ROUTE: a subject can have their phone number removed.

``clean_data`` grew a per-call ``clearable`` argument one wave ago and ``tests/test_record_write_path``
proved the helper honours it — but the helper is not the thing that was broken. The four record PATCH
routes and the four questionnaire-form PATCH routes never passed the argument, so on the artisan form
clearing a phone number, an email, an address or a note was a 200 THAT DID NOTHING: no error, no
workaround, and the stored value still there on the next load. The case with no alternative path is
retracting personal information a subject has asked to have removed — a researcher told to delete an
artisan's phone number could not do it, and the API told them it had worked.

ONE MORE ROUTE WAS FOUND BY THE SWEEP THAT CHECKED THIS MODULE FOR COMPLETENESS. ``update_craft`` was
never part of the "four record PATCHes" the wave named, so it kept the defect after the other four
lost it: ``localName``, ``category``, ``description`` and ``place`` on a Craft were still a 200 that
did nothing. It is covered here on the same terms as the rest, and
``test_every_nullable_column_a_client_can_send_is_either_clearable_or_exempt`` now lists it too,
because a route absent from that table is a route the completeness net cannot see.

WHY THE ROUTES ARE DRIVEN HERE AND NOT THE HELPER. Every assertion in this module would still pass
with the ``clearable=`` deleted from the route if it were written against ``clean_data`` directly,
because such a test hands the helper its own tuple. ``tests/test_record_write_path`` says the same
thing about the interview route and drives that one for the same reason. The failure is per-NAME as
well as per-route — dropping ``email`` from the artisan tuple and leaving the other nine would pass a
test that only checked ``phone`` — so every declared column gets its own drive.

THE OTHER HALF, AND THE ONE WAY THIS FIX COULD DO HARM. ``clearable`` is only sound on a route that
dumps with ``exclude_unset=True``: that is what makes a present key mean "the caller sent this". A
route that stopped doing it would start writing an explicit NULL over stored data for every optional
box the client merely left blank — silently, on every save. So each route's dump is asserted here as
well, by intercepting the ``model_dump`` kwargs the route actually passes.

NO DATABASE. Every collaborator that would touch Postgres is replaced with a recording stub; the
guards, the clean, the provenance merge and the workshop stamps are the real ones. Postgres is down
on this machine, so a DB-backed version of this could not be run and therefore could not be trusted.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest


class _Row:
    """A stored record that answers ``None`` for every column a test did not set.

    The provenance merge and the field guard ask a record for whichever columns the payload carries,
    so a ``SimpleNamespace`` would raise on the ones a test does not care about.
    """

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):  # only reached for names __init__ did not set
        return None


class _Writes:
    """One Prisma model delegate, recording the ``data`` of every write aimed at it."""

    def __init__(self, row: Any = None):
        self.updated: list[tuple[Any, Any]] = []
        self.row = row

    async def update(self, where, data, **_kwargs):
        self.updated.append((where, data))
        return self.row if self.row is not None else _Row(id=where["id"], **data)

    async def find_unique(self, where, **_kwargs):
        return self.row if self.row is not None else _Row(id=where["id"])

    async def find_many(self, **_kwargs):
        return []


class _Payload:
    """A stand-in for the pydantic update model, recording how the route dumped it.

    Only what the route reads is provided. ``dumped_with`` is the point of the class: the route's own
    ``exclude_unset`` is the precondition of everything else in this module, so it is observed rather
    than assumed.
    """

    def __init__(self, fields: dict[str, Any], **attributes):
        self._fields = dict(fields)
        self.dumped_with: dict[str, Any] = {}
        self.model_fields_set = set(fields)
        for name, value in attributes.items():
            setattr(self, name, value)

    def __getattr__(self, name):  # payload attributes the route reads directly
        return self._fields.get(name)

    def model_dump(self, **kwargs):
        self.dumped_with = kwargs
        excluded = kwargs.get("exclude") or set()
        return {k: v for k, v in self._fields.items() if k not in excluded}


def _editor():
    """An ordinary researcher who is also the record's author, so the field guard is not the subject
    of these tests — the clean is. Clearing by a NON-author is a 403 and has its own test below."""
    return _Row(id="usr_7", name="R. Menon", role="RESEARCHER")


async def _privileged(_record, _user, _data, _kind):
    return True


async def _no_status_policy(_user, _record, _data):
    return None


async def _no_relations(_rows, _relations):
    return None


# --------------------------------------------------------------------------------------
# The record PATCH routes: the original four, plus the craft PATCH the sweep found
# --------------------------------------------------------------------------------------


def _drive_artisan(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import artisans

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _sync(_previous, _next, _artisan_id):
        return None

    monkeypatch.setattr(artisans, "db", SimpleNamespace(artisan=writes))
    monkeypatch.setattr(artisans, "require_record", _require_record)
    monkeypatch.setattr(artisans, "guard_record_edit", _privileged)
    monkeypatch.setattr(artisans, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(artisans, "sync_workshop_artisan", _sync)
    monkeypatch.setattr(artisans, "public_encode", lambda row, _viewer=None: row)

    payload = _Payload(fields)
    asyncio.run(artisans.update_artisan("art_1", payload, _editor()))
    return writes, payload


def _drive_product(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import products

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _no_media_urls(_viewer):
        return set()

    monkeypatch.setattr(products, "db", SimpleNamespace(productdocumentation=writes))
    monkeypatch.setattr(products, "require_record", _require_record)
    monkeypatch.setattr(products, "guard_record_edit", _privileged)
    monkeypatch.setattr(products, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(products, "media_url_owners", _no_media_urls)
    monkeypatch.setattr(products, "public_encode", lambda row, _viewer=None, **_kw: row)

    payload = _Payload(fields)
    asyncio.run(products.update_product("prd_1", payload, _editor()))
    return writes, payload


def _drive_tool(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import tools

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _no_media_urls(_viewer):
        return set()

    monkeypatch.setattr(tools, "db", SimpleNamespace(tooldocumentation=writes))
    monkeypatch.setattr(tools, "require_record", _require_record)
    monkeypatch.setattr(tools, "guard_record_edit", _privileged)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "media_url_owners", _no_media_urls)
    monkeypatch.setattr(tools, "public_encode", lambda row, _viewer=None, **_kw: row)

    payload = _Payload(fields)
    asyncio.run(tools.update_tool("tol_1", payload, _editor()))
    return writes, payload


def _drive_process(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import processes

    writes = _Writes(row=stored)

    async def _require_record(_delegate, _record_id):
        return stored

    async def _hydrate(row, _viewer):
        return row

    monkeypatch.setattr(processes, "db", SimpleNamespace(process=writes))
    monkeypatch.setattr(processes, "require_record", _require_record)
    monkeypatch.setattr(processes, "guard_record_edit", _privileged)
    monkeypatch.setattr(processes, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(processes, "hydrate_relations", _no_relations)
    monkeypatch.setattr(processes, "_hydrate", _hydrate)

    # ``steps`` is a relation with its own guard and its own audit row; ``None`` is "the client did
    # not send a step list", which is the shape every scalar-only save has.
    payload = _Payload(fields, steps=None)
    asyncio.run(processes.update_process("prc_1", payload, _editor()))
    return writes, payload


def _drive_craft(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    """THE FIFTH ROUTE, WHICH THE ORIGINAL SWEEP MISSED.

    ``update_craft`` was not one of the four PATCHes the ``clearable`` wave wired, so a craft's
    category, description, local name and place were the same 200-that-does-nothing until this
    module grew the driver below. A craft is taxonomy rather than a person, so nothing here is a
    retraction — but ``Craft.place`` is the sharpest illustration in the schema of why the tuple is
    per model: it is ``String?`` here and NOT NULL on the three record models beside it.
    """
    from app.api.routes import crafts

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _sync(_previous, _next, _craft_id):
        return None

    monkeypatch.setattr(crafts, "db", SimpleNamespace(craft=writes))
    monkeypatch.setattr(crafts, "require_record", _require_record)
    monkeypatch.setattr(crafts, "guard_record_edit", _privileged)
    monkeypatch.setattr(crafts, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(crafts, "sync_workshop_craft", _sync)
    monkeypatch.setattr(crafts, "public_encode", lambda row, _viewer=None, **_kw: row)

    payload = _Payload(fields)
    asyncio.run(crafts.update_craft("crf_1", payload, _editor()))
    return writes, payload


#: Every column each record route declares clearable, and the driver that exercises that route.
#: Read off the route modules rather than retyped, so a name added to or removed from a route's
#: tuple is covered (or stops being covered) without this file having to be edited in step — a
#: hand-copied list here would be the same "the test agrees with itself" trap the module docstring
#: describes.
#:
#: THE FOURTH ELEMENT IS AN EDIT THAT TOUCHES NOTHING ON THE CLEARABLE LIST, and it has to be per
#: route rather than one shared body: ``{"status": "PENDING"}`` is the natural "some other edit" for
#: the four record models, and Craft HAS NO ``status`` COLUMN at all (``pin_pending_if_late``'s
#: docstring names it as the model it is a no-op for), so driving that route with one would assert
#: against a key the real schema cannot send.
def _record_routes():
    from app.api.routes import artisans, crafts, processes, products, tools

    return (
        ("artisan", _drive_artisan, artisans._CLEARABLE_COLUMNS, {"status": "PENDING"}),
        ("product", _drive_product, products._CLEARABLE_COLUMNS, {"status": "PENDING"}),
        ("tool", _drive_tool, tools._CLEARABLE_COLUMNS, {"status": "PENDING"}),
        ("process", _drive_process, processes._CLEARABLE_COLUMNS, {"status": "PENDING"}),
        ("craft", _drive_craft, crafts._CLEARABLE_COLUMNS, {"name": "Bandhani"}),
    )


def _record_cases():
    return [
        pytest.param(driver, column, id=f"{name}.{column}")
        for name, driver, columns, _untouched in _record_routes()
        for column in columns
    ]


@pytest.mark.parametrize(("driver", "column"), _record_cases())
def test_an_explicit_null_on_a_record_patch_actually_clears_the_column(monkeypatch, driver, column):
    """**THE SHIP-BLOCKER, PINNED AS FIXED.**

    The web client sends ``null`` for an emptied box — ``lib/forms.textValue`` returns ``null`` for a
    string that trims to nothing — so this is the exact body a researcher produces by selecting a
    phone number and pressing Save.
    """
    stored = _Row(
        id="rec_1",
        createdById="usr_7",
        status="PENDING",
        workshopId=None,
        extraMetadata={},
        **{column: "the value the subject asked to have removed"},
    )

    writes, _payload = driver(monkeypatch, {column: None}, stored)

    assert len(writes.updated) == 1, f"{column}: the PATCH wrote nothing at all"
    written = writes.updated[0][1]
    assert column in written, (
        f"the null for {column!r} was stripped before the update — this route no longer names it in "
        "`clean_data(..., clearable=...)`, so clearing it is a 200 that does nothing"
    )
    assert written[column] is None


@pytest.mark.parametrize(
    ("name", "driver"),
    [(name, driver) for name, driver, _columns, _untouched in _record_routes()],
)
def test_a_key_the_client_never_sent_is_not_nulled(monkeypatch, name, driver):
    """AN ABSENT KEY IS STILL "LEAVE IT ALONE", which is the whole difference ``clearable`` rests on.

    Declaring a column clearable must not make an untouched column clearable too: the body below
    carries one edit and nothing else, and a save that also wrote NULL over every other optional
    would be a far worse defect than the one this wave fixed.
    """
    _name, _driver, columns, untouched = next(
        entry for entry in _record_routes() if entry[0] == name
    )
    stored = _Row(
        id="rec_1",
        createdById="usr_7",
        status="PENDING",
        workshopId=None,
        extraMetadata={},
        **dict.fromkeys(columns, "still on the row"),
    )

    writes, _payload = driver(monkeypatch, dict(untouched), stored)

    written = writes.updated[0][1]
    for column in columns:
        assert column not in written, (
            f"{column!r} reached the update on a PATCH that never mentioned it — an unsent key is "
            "being written as NULL over a stored value"
        )


@pytest.mark.parametrize(
    ("name", "driver", "untouched"),
    [(name, driver, untouched) for name, driver, _columns, untouched in _record_routes()],
)
def test_the_record_patch_dumps_with_exclude_unset(monkeypatch, name, driver, untouched):
    """THE PRECONDITION ``clearable`` IS ONLY SOUND UNDER, ASSERTED RATHER THAN ASSUMED.

    ``clean_data``'s docstring states it: pass ``clearable`` only from a route that dumps with
    ``exclude_unset=True``. Without it, pydantic emits every optional the client did not send as
    ``None``, those nulls now survive the clean, and the save writes an explicit NULL over stored
    data for every box the researcher merely left blank. That is the one way this fix could do harm,
    and it would be silent — hence a test of its own, on every route that passes the argument.
    """
    stored = _Row(id="rec_1", createdById="usr_7", status="PENDING", extraMetadata={})

    _writes, payload = driver(monkeypatch, dict(untouched), stored)

    assert payload.dumped_with.get("exclude_unset") is True, (
        f"{name}: the PATCH no longer dumps with exclude_unset=True, so its `clearable=` tuple has "
        "become a licence to NULL every field the client did not send"
    )


def test_clearing_a_populated_field_is_still_refused_for_a_non_author(monkeypatch):
    """THE GUARD THAT ONLY NOW HAS ANYTHING TO GUARD.

    ``deps.assert_can_contribute_fields`` claims a populated field is locked to non-privileged
    editors whether they try to CHANGE it or CLEAR it. Until this wave the CLEAR half could not fire
    on these routes: ``clean_data`` had already dropped the null, so the guard was handed a payload
    with no such key. Now that the null survives, the refusal is reachable — and it has to actually
    happen, or making these columns clearable would have handed every signed-in account the ability
    to blank another researcher's data.
    """
    from fastapi import HTTPException

    from app.api.routes import artisans
    from app.core.deps import assert_can_contribute_fields

    stored = _Row(id="art_1", createdById="somebody_else", phone="+91 98200 00000")
    stranger = _Row(id="usr_7", name="R. Menon", role="RESEARCHER")

    with pytest.raises(HTTPException) as refusal:
        assert_can_contribute_fields(stored, stranger, {"phone": None})

    assert refusal.value.status_code == 403
    assert "phone" in refusal.value.detail
    # And the null is genuinely what the guard now receives from this route, rather than a shape
    # only this test constructs.
    assert "phone" in artisans._CLEARABLE_COLUMNS


def test_the_record_routes_do_not_share_one_clearable_list():
    """A GLOBAL LIST WOULD CORRUPT THREE MODELS TO FIX ONE, which is why ``clean_data`` takes the
    names per call. The tuples must therefore stay genuinely different: each is that model's
    own nullable columns, and the moment somebody "tidies" them into one shared constant the fix
    starts writing NULL into columns that are NOT NULL on the other tables.
    """
    from app.api.routes import artisans, crafts, products, tools

    # A tool measures height/width/thickness/weight/radius; a product measures heightInches. Neither
    # list is a subset of the other, and neither belongs on Artisan.
    assert "heightInches" in products._CLEARABLE_COLUMNS
    assert "heightInches" not in tools._CLEARABLE_COLUMNS
    assert "thickness" in tools._CLEARABLE_COLUMNS
    assert "thickness" not in products._CLEARABLE_COLUMNS
    assert "phone" in artisans._CLEARABLE_COLUMNS
    assert "phone" not in products._CLEARABLE_COLUMNS

    # ``place`` IS THE WHOLE ARGUMENT IN ONE COLUMN NAME. It is ``String?`` on Craft and NOT NULL on
    # the other three, so it MUST be clearable on the craft route and MUST NOT be on theirs. One
    # shared constant cannot satisfy both, and whichever way it was written it would be wrong
    # somewhere: a silent no-op on the craft form, or a constraint violation on the other three.
    assert "place" in crafts._CLEARABLE_COLUMNS
    for module in (artisans, products, tools):
        assert "place" not in module._CLEARABLE_COLUMNS

    # Nor may a route reach the same end by pushing its own names into the global set.
    from app.services.records import CLEARABLE_KEYS

    for module in (artisans, crafts, products, tools):
        assert not (set(module._CLEARABLE_COLUMNS) & CLEARABLE_KEYS)


# --------------------------------------------------------------------------------------
# The list is COMPLETE, not merely correct — read off the schema, not off the routes
# --------------------------------------------------------------------------------------


def _clearable_argument_of(route) -> tuple[str, ...]:
    """The ``clearable=`` tuple a route hands ``clean_data``, read out of the route's OWN source.

    Every other entry in ``cases`` below reads a module constant, for the reason that test's
    docstring gives: a retyped copy agrees with itself and catches nothing. ``update_interview``
    passes its tuple as a literal at the call site instead, and ``routes/questionnaire.py`` is not
    this unit's file to edit — so the tuple is parsed out of the function's source rather than
    retyped here. Promoting it to a module constant (``_INTERVIEW_CLEARABLE_COLUMNS``, the spelling
    the other nine use) and reading it like the rest is the tidier end state and belongs to whoever
    owns that route; this keeps the completeness net honest in the meantime.

    Asserts it found exactly one, so the day that route grows a second ``clean_data`` call this
    fails loudly instead of silently checking the wrong one.
    """
    import ast
    import inspect
    import textwrap

    tree = ast.parse(textwrap.dedent(inspect.getsource(route)))
    found = [
        tuple(ast.literal_eval(keyword.value))
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and getattr(node.func, "attr", getattr(node.func, "id", None)) == "clean_data"
        for keyword in node.keywords
        if keyword.arg == "clearable"
    ]
    assert len(found) == 1, (
        f"{route.__name__}: expected exactly one `clean_data(..., clearable=...)` call to read, "
        f"found {len(found)}"
    )
    return found[0]


def _nullable_columns(model: str) -> set[str]:
    """The nullable SCALAR columns of one Prisma model, read out of ``schema.prisma``.

    Nullable RELATIONS are dropped: ``craft Craft?`` is not a column a payload can null, and the
    column behind it (``craftId``) is listed separately and is already globally clearable. The test
    telling them apart by "is this type a declared model" rather than by a hand-kept exclusion list
    is what keeps a newly added relation from reading as a newly added clearable column.
    """
    import re
    from pathlib import Path

    text = (Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma").read_text(
        encoding="utf-8"
    )
    models = set(re.findall(r"^model (\w+) \{", text, re.MULTILINE))
    start = text.index(f"model {model} {{")
    body = text[start : text.index("\n}", start)]

    columns = set()
    for line in body.splitlines():
        statement = line.split("///")[0].strip()
        if not statement or statement.startswith(("//", "@@", "model ")):
            continue
        parts = statement.split()
        if len(parts) < 2 or not parts[1].endswith("?"):
            continue
        if parts[1].rstrip("?") in models:  # a nullable relation, not a scalar column
            continue
        columns.add(parts[0])
    return columns


#: A global name that lands on a column which is NOT NULL on ONE model. ``clean_data`` unions
#: :data:`records.CLEARABLE_KEYS` with whatever the route passes and a route cannot subtract from it,
#: so for these the null survives the clean and the ROUTE has to refuse it by hand. Every entry here
#: is a debt, not a blessing, and the assertion that reads this table is the only thing keeping the
#: completeness net from going blind to the whole class: an entry may be added only together with a
#: test below that drives the route and proves the refusal.
#:
#: ``Process.productId`` — ``productId`` belongs in the global set because it is a nullable
#: back-reference on the three models that merely POINT AT a product, and it is ``String`` (NOT NULL)
#: on ``Process`` because a process is documentation OF a product and cannot be orphaned. The right
#: long-term answer is probably a ``forbid_clearing_*`` validator on ``ProcessUpdate``, next to
#: ``forbid_clearing_location`` in schemas/common.py — that would refuse it for every caller of the
#: schema rather than one route — but that is a change to a shared schema and an owner's call. The
#: route-level refusal below is the reversible half of it.
_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN = {
    "Process": frozenset({"productId"}),
}


def test_a_process_patch_refuses_to_clear_the_product_it_belongs_to(monkeypatch):
    """THE ONE ENTRY IN ``_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN``, DRIVEN RATHER THAN ASSERTED.

    ``Process.productId`` is NOT NULL, and ``productId`` is in the GLOBAL ``CLEARABLE_KEYS`` — so
    ``{"productId": null}`` keeps its key through ``clean_data`` on this route however carefully
    ``processes._CLEARABLE_COLUMNS`` leaves the name out. Before ``update_process`` grew the branch
    this test drives, that null fell into ``require_record(db.productdocumentation, None)``: a lookup
    for a product with no id, whose kindest outcome is a 404 blaming a product for not existing when
    what the caller actually asked for was to orphan a process.

    422 is the honest answer, and it has to be raised BEFORE anything is written.
    """
    from fastapi import HTTPException

    stored = _Row(id="prc_1", createdById="usr_7", status="PENDING", productId="prd_1")

    with pytest.raises(HTTPException) as refusal:
        _drive_process(monkeypatch, {"productId": None}, stored)

    assert refusal.value.status_code == 422
    assert "product" in refusal.value.detail.lower()


def test_moving_a_process_to_another_product_still_checks_that_product_exists(monkeypatch):
    """THE OTHER HALF OF THE SAME BRANCH, so the refusal above cannot have been bought by breaking
    the move. A real id must still be looked up before the row is written — the guard is a refusal
    of ``None``, not of the key."""
    from app.api.routes import processes

    looked_up: list[Any] = []
    stored = _Row(id="prc_1", createdById="usr_7", status="PENDING", productId="prd_1")
    writes = _Writes(row=stored)

    async def _require_record(delegate, record_id):
        looked_up.append(record_id)
        return stored

    async def _hydrate(row, _viewer):
        return row

    monkeypatch.setattr(
        processes,
        "db",
        SimpleNamespace(process=writes, productdocumentation=_Writes(row=_Row(id="prd_2"))),
    )
    monkeypatch.setattr(processes, "require_record", _require_record)
    monkeypatch.setattr(processes, "guard_record_edit", _privileged)
    monkeypatch.setattr(processes, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(processes, "hydrate_relations", _no_relations)
    monkeypatch.setattr(processes, "_hydrate", _hydrate)

    payload = _Payload({"productId": "prd_2"}, steps=None)
    asyncio.run(processes.update_process("prc_1", payload, _editor()))

    assert "prd_2" in looked_up, "the new product id was never looked up before the process moved"
    assert writes.updated[0][1]["productId"] == "prd_2"


def test_every_nullable_column_a_client_can_send_is_either_clearable_or_exempt():
    """THE TEST THAT CATCHES THE **NEXT** ``phone``, WHICH NONE OF THE ABOVE DO.

    Everything before this point proves that the names a route DOES declare work. Not one of them
    would notice a nullable column being added to a model, wired into its update schema, and never
    added to the tuple — which is precisely how ``phone``, ``email``, ``address`` and ``notes`` came
    to be a 200 that did nothing in the first place. The gap is silent by construction: the field
    saves, the API answers 200, and only the subject who asked to be forgotten ever finds out.

    So the expected list is DERIVED — nullable scalars from ``schema.prisma``, intersected with what
    the update schema actually accepts, minus the exemptions below — and compared with what the route
    declares. A test that retyped the names would agree with itself and catch nothing.

    THE EXEMPTIONS, EACH ONE VERIFIED RATHER THAN ASSERTED BY THE ROUTE'S OWN COMMENT:

      * ``records.CLEARABLE_KEYS`` **intersected with this model's own nullable columns** — those are
        already clearable here, so naming them again would be a duplicate rather than a fix.
        Imported, not retyped, so the two cannot drift apart. THE INTERSECTION IS LOAD-BEARING, AND
        THIS TEST WAS FIRST WRITTEN WITHOUT IT. Subtracting the global set WHOLESALE also swallowed
        every global name that lands on a column which is NOT NULL on this particular model — which
        is the exact mistake the sibling test above says it exists to catch — and it was hiding a
        live one. See ``_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN`` and the third assertion below.
      * ``extraMetadata`` — naming it would be INERT, not merely redundant. All four routes call
        ``merge_field_provenance`` after the clean, and it ends by either assigning
        ``new_data["extraMetadata"] = Json(...)`` or popping the key, so a null cannot reach Prisma
        through it whatever the tuple says.
      * the measurement trio — written by ``services/media_queue``, never by a form.
        ``records.PROVENANCE_SKIP_FIELDS`` already classes all three as system-managed.

    An exemption is a decision, so adding one has to be a deliberate edit HERE, with a reason, and
    not a name quietly missing from a route.

    EVERY PATCH THAT PASSES ``clearable`` IS COVERED, not only the four record ones. The four
    questionnaire-form PATCHes are the same surface with the same failure mode, and one of them is
    one line away from it: ``Questionnaire.sourceFilename`` is nullable today and stays out of every
    tuple only because ``QuestionnaireUpdate`` does not accept it.

    ``Craft`` IS HERE BECAUSE THIS TEST IS THE THING THAT WOULD HAVE CAUGHT IT. The craft PATCH was
    not in the original four, so ``localName``/``category``/``description``/``place`` were exactly
    the silent no-op described above until the route grew its own tuple — a route missing from the
    list below is a route this net does not cover, which is a different failure from a name missing
    from a tuple and is why every ``clean_data(..., clearable=...)`` caller belongs in ``cases``.

    ``QuestionnaireInterview`` IS THE TENTH, AND IT WAS MISSING FROM THIS LIST WHILE THE SENTENCE
    ABOVE CLAIMED OTHERWISE. ``routes/questionnaire.update_interview`` passes
    ``clearable=("interviewDate", "place", "language", "notes")`` as a literal at the call site, and
    that tuple happens to be complete today — but "complete today" is precisely what this net exists
    not to take anybody's word for. Its tuple is read with :func:`_clearable_argument_of` rather than
    retyped, for the same reason the other nine are read off their modules; see that helper for why
    it is parsed instead of imported.
    """
    from app.api.routes import (
        artisans,
        crafts,
        processes,
        products,
        questionnaire,
        questionnaire_forms,
        tools,
    )
    from app.schemas.questionnaire import (
        CustomQuestionUpdate,
        CustomSectionUpdate,
        QuestionnaireEntryUpdate,
        QuestionnaireInterviewUpdate,
        QuestionnaireUpdate,
    )
    from app.schemas.records import (
        ArtisanUpdate,
        CraftUpdate,
        ProcessUpdate,
        ProductUpdate,
        ToolUpdate,
    )
    from app.services.records import CLEARABLE_KEYS

    system_managed = {
        "extraMetadata",
        "measurementImageId",
        "measurementAnalysis",
        "measurementAnalysisStatus",
    }

    cases = (
        ("Artisan", ArtisanUpdate, artisans._CLEARABLE_COLUMNS),
        ("ProductDocumentation", ProductUpdate, products._CLEARABLE_COLUMNS),
        ("ToolDocumentation", ToolUpdate, tools._CLEARABLE_COLUMNS),
        ("Process", ProcessUpdate, processes._CLEARABLE_COLUMNS),
        ("Craft", CraftUpdate, crafts._CLEARABLE_COLUMNS),
        (
            "Questionnaire",
            QuestionnaireUpdate,
            questionnaire_forms._QUESTIONNAIRE_CLEARABLE_COLUMNS,
        ),
        (
            "QuestionnaireFormQuestion",
            CustomQuestionUpdate,
            questionnaire_forms._QUESTION_CLEARABLE_COLUMNS,
        ),
        (
            "QuestionnaireFormEntry",
            QuestionnaireEntryUpdate,
            questionnaire_forms._ENTRY_CLEARABLE_COLUMNS,
        ),
        (
            "QuestionnaireFormSection",
            CustomSectionUpdate,
            questionnaire_forms._SECTION_CLEARABLE_COLUMNS,
        ),
        (
            "QuestionnaireInterview",
            QuestionnaireInterviewUpdate,
            _clearable_argument_of(questionnaire.update_interview),
        ),
    )
    for model, schema, declared in cases:
        nullable = _nullable_columns(model)
        sendable = {
            field.alias or name for name, field in schema.model_fields.items()
        } | set(schema.model_fields)
        expected = (nullable & sendable) - (CLEARABLE_KEYS & nullable) - system_managed

        missing = sorted(expected - set(declared))
        assert not missing, (
            f"{model}: {missing} are nullable columns the update schema accepts, and no route "
            "declares them clearable — sending null for one is a 200 that does nothing. Add them to "
            "the route's `_CLEARABLE_COLUMNS`, or add an exemption here saying why the null cannot "
            "reach the database anyway."
        )
        # The other direction matters just as much: a name that is NOT nullable on this model would
        # turn an emptied box into a constraint violation, and one the schema does not accept is
        # dead weight a later reader would trust.
        stray = sorted(set(declared) - expected)
        assert not stray, (
            f"{model}: {stray} are declared clearable but are not nullable columns this update "
            "schema can send. Clearing a NOT NULL column is a 500, not a retraction."
        )
        # AND THE THIRD DIRECTION, WHICH THE ROUTE'S OWN TUPLE CANNOT ANSWER FOR. ``clearable`` ADDS
        # to :data:`records.CLEARABLE_KEYS` and can never subtract from it, so a global name that is
        # NOT NULL on THIS model is clearable here no matter how carefully the route's tuple leaves
        # it out. Nothing above would see that, because such a name is not in the model's nullable
        # columns and so never enters ``expected`` at all.
        misrouted = sorted(
            ((sendable & CLEARABLE_KEYS) - nullable)
            - _GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN.get(model, frozenset())
        )
        assert not misrouted, (
            f"{model}: {misrouted} are in the GLOBAL `records.CLEARABLE_KEYS` and this update schema "
            f"accepts them, but they are NOT NULL on {model}, so an explicit null survives "
            "`clean_data` and reaches the write. Either stop the update schema accepting a null for "
            "them, or refuse it in the route and record the refusal in "
            "`_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN` with a test that drives it."
        )


# --------------------------------------------------------------------------------------
# The questionnaire-form PATCH routes — the same gap, four more call sites
# --------------------------------------------------------------------------------------


def _questionnaire_forms_stubs(monkeypatch, module, *, owner="usr_7"):
    """The collaborators every questionnaire-form PATCH reaches before the clean."""
    questionnaire = _Row(id="qn_1", ownerId=owner, title="Bagru dyers")

    async def _require_questionnaire(_questionnaire_id, _user):
        return questionnaire

    async def _load_form(_questionnaire_id, **_kwargs):
        return questionnaire

    monkeypatch.setattr(module, "_require_questionnaire", _require_questionnaire)
    monkeypatch.setattr(module, "_require_owner", lambda _record, _user: None)
    monkeypatch.setattr(module, "load_form", _load_form)
    monkeypatch.setattr(module, "public_encode", lambda row, *_a, **_kw: row)
    return questionnaire


@pytest.mark.parametrize("column", ["respondentName", "notes"])
def test_an_entry_patch_clears_the_respondent_it_was_asked_to_retract(monkeypatch, column):
    """THE PII CASE IN THIS MODULE. ``respondentName`` is the name of the person interviewed and
    ``notes`` is what was written down about them during the sitting — both print into the
    questionnaire annexure of a report that goes to a ministry. Retracting either was a 200 that
    changed nothing.
    """
    from app.api.routes import questionnaire_forms as module

    _questionnaire_forms_stubs(monkeypatch, module)
    entries = _Writes(row=_Row(id="ent_1"))
    stored_entry = _Row(id="ent_1", title="Sitting 1", **{column: "Kamla Devi"})

    async def _entry_in(_questionnaire_id, _entry_id, **_kwargs):
        return stored_entry

    monkeypatch.setattr(module, "_entry_in", _entry_in)
    monkeypatch.setattr(module, "db", SimpleNamespace(questionnaireformentry=entries))

    payload = _Payload({column: None})
    asyncio.run(module.update_entry("qn_1", "ent_1", payload, _editor()))

    assert payload.dumped_with.get("exclude_unset") is True
    assert len(entries.updated) == 1, f"{column}: the PATCH wrote nothing at all"
    written = entries.updated[0][1]
    assert column in written and written[column] is None


def test_a_questionnaire_patch_clears_its_description(monkeypatch):
    """``description`` was the name this route's hand-written null handling missed.

    The comment above it said ``designWorkshopId`` was "the one field here that is meaningfully
    NULLABLE" and put that key back into ``data`` by hand after the clean had dropped it. It was not
    the one: ``Questionnaire.description`` is ``String?`` too, and emptying the description box left
    the old text in the database and in every render of the form.
    """
    from app.api.routes import questionnaire_forms as module

    _questionnaire_forms_stubs(monkeypatch, module)
    forms = _Writes(row=_Row(id="qn_1"))
    monkeypatch.setattr(module, "db", SimpleNamespace(questionnaire=forms))

    payload = _Payload({"description": None})
    asyncio.run(module.update_questionnaire("qn_1", payload, _editor()))

    assert payload.dumped_with.get("exclude_unset") is True
    assert len(forms.updated) == 1, "the PATCH wrote nothing at all"
    written = forms.updated[0][1]
    assert "description" in written and written["description"] is None


def test_detaching_a_questionnaire_from_its_workshop_still_works(monkeypatch):
    """THE BEHAVIOUR THE HAND-WRITTEN BLOCK EXISTED FOR, KEPT WHEN IT WAS REPLACED.

    ``designWorkshopId: null`` detaches the questionnaire from its workshop, and it now travels
    through ``clearable`` like every other nullable column rather than through a second mechanism.
    A detach must also NOT be sent to ``_require_attachable_workshop``: there is no workshop to check
    entitlement against, and the old code's ``if payload.designWorkshopId`` was that exemption.
    """
    from app.api.routes import questionnaire_forms as module

    _questionnaire_forms_stubs(monkeypatch, module)
    forms = _Writes(row=_Row(id="qn_1"))
    monkeypatch.setattr(module, "db", SimpleNamespace(questionnaire=forms))

    checked: list[Any] = []

    async def _require_attachable_workshop(workshop_id, _user):
        checked.append(workshop_id)

    monkeypatch.setattr(module, "_require_attachable_workshop", _require_attachable_workshop)

    payload = _Payload({"designWorkshopId": None})
    asyncio.run(module.update_questionnaire("qn_1", payload, _editor()))

    written = forms.updated[0][1]
    assert "designWorkshopId" in written and written["designWorkshopId"] is None
    assert checked == [], "a detach was sent to the workshop-entitlement check, which has no workshop"


def test_attaching_a_questionnaire_to_a_workshop_is_still_checked(monkeypatch):
    """The other half of the same branch: a real workshop id must still be authorised. Owning the
    FORM has never said anything about the workshop it is being pointed at."""
    from app.api.routes import questionnaire_forms as module

    _questionnaire_forms_stubs(monkeypatch, module)
    forms = _Writes(row=_Row(id="qn_1"))
    monkeypatch.setattr(module, "db", SimpleNamespace(questionnaire=forms))

    checked: list[Any] = []

    async def _require_attachable_workshop(workshop_id, _user):
        checked.append(workshop_id)

    monkeypatch.setattr(module, "_require_attachable_workshop", _require_attachable_workshop)

    payload = _Payload({"designWorkshopId": "dw_9"})
    asyncio.run(module.update_questionnaire("qn_1", payload, _editor()))

    assert checked == ["dw_9"]


def test_a_question_patch_clears_its_help_text(monkeypatch):
    """``helpText`` was already clearable here, through a hand-written put-back after the clean. It
    goes through ``clearable`` now so this module has ONE mechanism rather than two — and this test
    is what stops that swap from having quietly dropped the behaviour."""
    from app.api.routes import questionnaire_forms as module

    _questionnaire_forms_stubs(monkeypatch, module)
    questions = _Writes(row=_Row(id="qst_1"))
    stored_question = _Row(id="qst_1", sectionId="sec_1", prompt="How many looms?", helpText="Count")

    async def _question_in(_questionnaire_id, _question_id):
        return stored_question

    async def _guard_question_edit(_question, new_prompt=None, deleting=False):
        return "update"

    monkeypatch.setattr(module, "_question_in", _question_in)
    monkeypatch.setattr(module, "guard_question_edit", _guard_question_edit)
    monkeypatch.setattr(module, "db", SimpleNamespace(questionnaireformquestion=questions))

    payload = _Payload({"helpText": None})
    asyncio.run(module.update_question("qn_1", "qst_1", payload, _editor()))

    assert payload.dumped_with.get("exclude_unset") is True
    assert len(questions.updated) == 1, "the PATCH wrote nothing at all"
    written = questions.updated[0][1]
    assert "helpText" in written and written["helpText"] is None


def test_the_section_patch_has_no_nullable_column_to_declare():
    """WHY ``update_section`` IS THE ONE PATCH IN THIS MODULE WITH NO ``clearable``.

    Not an oversight and not an exception: ``model QuestionnaireFormSection`` has no nullable column
    at all. Asserted against the schema so that adding one — the moment this stops being true — fails
    here instead of shipping as another silent 200.
    """
    from pathlib import Path

    schema = Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"
    text = schema.read_text(encoding="utf-8")
    start = text.index("model QuestionnaireFormSection {")
    body = text[start : text.index("\n}", start)]

    nullable = [line.strip() for line in body.splitlines() if "?" in line.split("///")[0]]
    assert nullable == [], (
        "QuestionnaireFormSection has grown a nullable column, so PATCH "
        "/questionnaires/{id}/sections/{id} now needs a `clearable=` tuple like its three siblings: "
        f"{nullable}"
    )


# --------------------------------------------------------------------------------------
# The interview patch: the one ``clearable`` caller whose precondition was never asserted
# --------------------------------------------------------------------------------------
#
# ``routes/questionnaire.update_interview`` passes ``clearable=("interviewDate", "place", "language",
# "notes")`` and was the one caller in the tree with no test of its own precondition.
# ``tests/test_record_write_path`` covers the columns — it drives this route and asserts each null
# survives — but every ``exclude_unset`` assertion in the repository was in THIS module, and none of
# them was about this route. That is the asymmetric half: losing the ``clearable=`` tuple makes a
# retraction silently fail, which is bad; losing ``exclude_unset=True`` makes every optional the
# client left blank get written as NULL on every save, which is worse and just as silent. The one
# route whose tuple was verified and whose dump was not is the one worth closing.


def test_the_interview_patch_dumps_with_exclude_unset(monkeypatch):
    """THE PRECONDITION, ON THE LAST ROUTE THAT HAD IT UNASSERTED.

    ``QuestionnaireInterviewUpdate`` carries ``title``, ``status``, ``interviewDate``, ``place``,
    ``language``, ``notes``, ``workshopId``, a location and more — so a dump without
    ``exclude_unset`` would hand ``clean_data`` a ``None`` for every one the researcher did not
    touch, four of which this route has declared clearable and would therefore write as NULL.
    """
    from app.api.routes import questionnaire as module

    stored = _Row(id="int_1", createdById="usr_7", status="PENDING", notes="on the row")
    writes = _Writes(row=stored)

    async def _require_record(_delegate, _record_id):
        return stored

    async def _privileged_edit(_record, _user, _data, _kind):
        return True

    async def _attach_location(data):
        return data

    monkeypatch.setattr(module, "db", SimpleNamespace(questionnaireinterview=writes))
    monkeypatch.setattr(module, "require_record", _require_record)
    monkeypatch.setattr(module, "guard_record_edit", _privileged_edit)
    monkeypatch.setattr(module, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(module, "attach_location", _attach_location)
    monkeypatch.setattr(module, "hydrate_relations", _no_relations)
    monkeypatch.setattr(module, "public_encode", lambda row, _viewer=None, **_kw: row)

    # ``artisanIds``/``responses`` are relations with their own guards, excluded from the dump; None
    # is "the client sent no list", which is the shape of every scalar-only save.
    payload = _Payload({"notes": None}, artisanIds=None, responses=None)
    asyncio.run(module.update_interview("int_1", payload, _editor()))

    assert payload.dumped_with.get("exclude_unset") is True, (
        "PATCH /questionnaires/interviews/{id} no longer dumps with exclude_unset=True, so its "
        "`clearable=` tuple has become a licence to NULL interviewDate, place, language and notes "
        "on every save that does not mention them"
    )
    assert len(writes.updated) == 1, "the PATCH wrote nothing at all"
    written = writes.updated[0][1]
    assert "notes" in written and written["notes"] is None
