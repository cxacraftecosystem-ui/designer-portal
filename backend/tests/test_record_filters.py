"""The shared filter language, which had no tests at all while it lived inside the search route.

These are the rules the Search page, View Data and the map all depend on agreeing about. The two
that matter most are the ones a refactor would break silently: that row visibility survives a
free-text query, and that every active filter narrows rather than widens.
"""

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services.record_filters import (
    PLACED_TYPES,
    RECORD_TYPES,
    build_record_wheres,
    enum_filter_list_or_422,
    resolve_types,
)


class FakeUser:
    """A user below professor — the rank the two visibility policies actually differ on."""

    def __init__(self, role: str = "CROWDSOURCE", user_id: str = "u1"):
        self.role = role
        self.id = user_id


ADMIN = FakeUser(role="ADMIN", user_id="admin")
VOLUNTEER = FakeUser()


def test_resolve_types_accepts_both_spellings_clients_use():
    assert resolve_types(None) == set(RECORD_TYPES)
    assert resolve_types([]) == set(RECORD_TYPES)
    assert resolve_types(["   "]) == set(RECORD_TYPES)
    assert resolve_types(["artisans", "media"]) == {"artisans", "media"}
    assert resolve_types(["artisans,media"]) == {"artisans", "media"}


def test_resolve_types_refuses_a_name_it_does_not_know():
    # A plausible typo must not come back as a well-formed empty result.
    with pytest.raises(HTTPException) as error:
        resolve_types(["artisan"])
    assert error.value.status_code == 422
    assert "artisan" in str(error.value.detail)


def test_every_bucket_is_returned_even_when_no_filter_touches_it():
    wheres = asyncio.run(build_record_wheres(ADMIN))
    assert set(wheres) == set(RECORD_TYPES)


def test_reading_the_repository_is_open_to_every_rank():
    # The policy this module now composes: a signed-in account may READ every row, whatever its rank.
    # A volunteer's clauses must therefore carry NO owner predicate — the bug this replaced was a
    # researcher's search, map and dashboard all silently narrowing to their own uploads.
    for user in (VOLUNTEER, ADMIN):
        wheres = asyncio.run(build_record_wheres(user))
        for bucket in RECORD_TYPES:
            assert wheres[bucket] == {}, f"{bucket} narrowed a plain read for {user.role}"


def test_a_free_text_query_is_the_only_thing_a_plain_search_adds():
    wheres = asyncio.run(build_record_wheres(VOLUNTEER, q="bagru"))
    for bucket in RECORD_TYPES:
        where = wheres[bucket]
        assert "OR" in where, f"{bucket} lost its text search"
        # No leftover empty AND: composing an empty predicate must not litter the clause with `[{}]`,
        # which Prisma accepts but which makes every query log unreadable.
        assert "AND" not in where, f"{bucket} carries an empty AND"


def test_the_download_predicate_still_narrows_below_professor(monkeypatch):
    """Reading opened up; TAKING DATA OUT did not. This is the predicate every /export and /data query
    still rides, asserted directly because no read-side clause carries it any more.

    WHY THIS TEST NOW TAKES A DOUBLE, AND WHY IT MUST NOT SIMPLY STUB THE WHOLE FUNCTION.
    ``owned_or_granted_where`` used to be a pure predicate builder — dictionary work over the user
    object, no I/O — and this test called it with no connected Prisma client for exactly that reason.
    A later fix widened the MEDIA variant with a third arm ("the recordings of a design workshop this
    account may open"), and that arm HAS to read the workshop table first: ``MediaFile`` carries no
    column pointing at ``DesignWorkshop``, only the ``linkedRecordType``/``linkedRecordId`` tag pair,
    so there is no relation for Postgres to walk. The media variant is therefore a QUERY now, and this
    test — untouched since the day it was written — started raising ``ClientNotConnectedError`` from
    inside a filter builder, which is a confusing place to be handed a database error.

    So the tag lookup is replaced with a double HERE, at the seam ``_design_workshop_media_branches``
    already provides, and exercised for real in the test below. Stubbing at that seam is what lets
    this test assert the two things the collision actually put at risk, neither of which a
    "it did not raise" test would catch:

      * the RECORD variant is still pure — it must not have grown a query, because it is called on
        every /export CSV and every /data page for every account below professor, and a per-request
        round trip bought nothing there (the ``calls`` spy is the assertion, not decoration);
      * the media variant STILL CARRIES THE THIRD ARM. That arm is the whole of a closed defect: a
        granted co-designer asking for a workshop's transcript preview got ``{"items": [], "total": 0}``
        over six interviews sitting in the database, while the generator beside it said "6
        recording(s) could not be included". Deleting the arm to make this file pure again would
        reopen that, silently, and this assertion is what refuses.
    """
    from app.services import records

    calls: list[str] = []
    WORKSHOP_ARM = {"linkedRecordType": "designWorkshop", "linkedRecordId": {"in": ["dw-1"]}}

    async def _fake_branches(user_id: str) -> list[dict[str, Any]]:
        calls.append(user_id)
        return [WORKSHOP_ARM]

    monkeypatch.setattr(records, "_design_workshop_media_branches", _fake_branches)

    record_where = asyncio.run(records.owned_or_granted_where(VOLUNTEER))
    assert record_where["OR"][0] == {"createdById": "u1"}
    assert record_where["OR"][1]["createdBy"]["is"]["dataAccessAsOwner"]["some"] == {
        "granteeId": "u1",
        "status": "GRANTED",
    }
    # Two arms and no query: the record variant is the pure path and stays pure.
    assert len(record_where["OR"]) == 2
    assert calls == [], "the record variant paid for a workshop lookup it has no use for"

    media = asyncio.run(records.owned_or_granted_where(VOLUNTEER, owner_field="uploadedById"))
    assert media["OR"][0] == {"uploadedById": "u1"}
    assert "uploadedBy" in media["OR"][1]
    assert media["OR"][2] == WORKSHOP_ARM, "the co-designer's recordings arm is gone"
    assert calls == ["u1"]

    # Professor and above take everything, as before — and pay for no lookup on the way there, which
    # is the reason the rank test comes FIRST in the function rather than after the branch list.
    assert asyncio.run(records.owned_or_granted_where(ADMIN)) == {}
    assert asyncio.run(records.owned_or_granted_where(ADMIN, owner_field="uploadedById")) == {}
    assert calls == ["u1"]


def test_the_workshop_arm_is_the_tag_pair_and_is_absent_when_there_is_nothing_to_add(monkeypatch):
    """The arm the test above doubles, exercised for real against a fake delegate.

    This is the half that cannot be asserted through a stub, and it is where the widening either is
    or is not correct: WHICH workshops (only ones this account may open, and not soft-deleted ones)
    and WHAT clause (the tag pair both clients file design-workshop uploads under — not a FK, which
    ``MediaFile`` does not have).

    NO DATABASE, DELIBERATELY: this module's tests are dictionary work over rows, which is what makes
    them second-long. ``records.db`` is replaced with a delegate that records the ``where`` it was
    handed, so the query is asserted rather than executed.
    """
    from app.services import records
    from app.services.design_workshop_viewers import visible_to_clause
    from app.services.dictation_consent import MEDIA_TAG

    class _DB:
        def __init__(self, rows: list[Any]) -> None:
            self.calls: list[dict[str, Any]] = []
            self.designworkshop = self  # one delegate, standing in for db.designworkshop
            self._rows = rows

        async def find_many(self, **kwargs: Any) -> list[Any]:
            self.calls.append(kwargs)
            return self._rows

    fake = _DB([SimpleNamespace(id="dw-1"), SimpleNamespace(id="dw-2")])
    monkeypatch.setattr(records, "db", fake)

    branches = asyncio.run(records._design_workshop_media_branches("u1"))
    assert branches == [{"linkedRecordType": MEDIA_TAG, "linkedRecordId": {"in": ["dw-1", "dw-2"]}}]
    # The scope is the LIST endpoint's own clause, imported rather than re-spelled, plus the
    # soft-delete guard: a grant on a deleted workshop must not keep handing over its recordings.
    assert fake.calls[0]["where"] == {"deletedAt": None, **visible_to_clause("u1")}
    assert "viewers" in str(fake.calls[0]["where"]), "a co-designer is a viewer row, not the creator"

    # An account on no workshop contributes NO arm. `{"linkedRecordId": {"in": []}}` would be a
    # permanently false predicate shipped on every export query for every researcher in the
    # repository, which is a cost with no case behind it.
    empty = _DB([])
    monkeypatch.setattr(records, "db", empty)
    assert asyncio.run(records._design_workshop_media_branches("u1")) == []

    # An anonymous/unidentified caller must not sweep the workshop table on the way to being refused.
    unused = _DB([SimpleNamespace(id="dw-1")])
    monkeypatch.setattr(records, "db", unused)
    assert asyncio.run(records._design_workshop_media_branches("")) == []
    assert unused.calls == []


def test_mine_means_mine_at_every_rank():
    # The dashboard's "your contribution" figure. It used to fall out of the read filter by accident,
    # which is exactly why it was wrong for a professor — who saw the repository total labelled as
    # their own work.
    from app.services.records import own_rows_where

    assert asyncio.run(own_rows_where(VOLUNTEER)) == {"createdById": "u1"}
    assert asyncio.run(own_rows_where(ADMIN)) == {"createdById": "admin"}
    assert asyncio.run(own_rows_where(ADMIN, owner_field="uploadedById")) == {"uploadedById": "admin"}


def test_place_reaches_the_buckets_that_have_the_column_and_no_others():
    wheres = asyncio.run(build_record_wheres(ADMIN, place="Bagru"))
    for bucket in PLACED_TYPES:
        assert wheres[bucket]["place"] == {"contains": "Bagru", "mode": "insensitive"}
    # A photograph has no place of its own; filtering it by one would silently empty the bucket.
    assert "place" not in wheres["media"]


def test_filters_compose_rather_than_replace_one_another():
    when = datetime(2026, 7, 1, tzinfo=UTC)
    wheres = asyncio.run(build_record_wheres(
        ADMIN, q="cane", craft_id="c1", place="Bareilly", date_from=when
    ))
    tools = wheres["tools"]
    assert "OR" in tools and tools["craftId"] == "c1"
    assert tools["place"] == {"contains": "Bareilly", "mode": "insensitive"}
    assert tools["createdAt"] == {"gte": when}


def test_the_workshop_date_range_falls_back_to_the_legacy_column():
    when = datetime(2026, 7, 1, tzinfo=UTC)
    wheres = asyncio.run(build_record_wheres(ADMIN, date_from=when))
    clause = wheres["workshops"]["AND"][-1]
    assert clause == {"OR": [{"startDate": {"gte": when}}, {"startDate": None, "date": {"gte": when}}]}


def test_artisans_are_inside_the_date_range_too():
    # Artisans were the one bucket the range never reached, which read as the filter being broken
    # rather than as artisans being exempt.
    when = datetime(2026, 7, 1, tzinfo=UTC)
    wheres = asyncio.run(build_record_wheres(ADMIN, date_from=when))
    assert wheres["artisans"]["createdAt"] == {"gte": when}


def test_a_nul_byte_in_the_query_is_stripped_not_rejected():
    # Postgres cannot store a NUL; `contains` strips it so a pasted name still searches.
    wheres = asyncio.run(build_record_wheres(ADMIN, q="bag\x00ru"))
    assert wheres["artisans"]["OR"][0]["name"]["contains"] == "bagru"


# --------------------------------------------------------------------------------------
# The filter values a client can actually send
# --------------------------------------------------------------------------------------


def test_an_unknown_status_is_a_422_that_names_the_values_rather_than_a_500():
    """Nine list endpoints put the raw query string straight into a Prisma ENUM column.

    Anything not in the enum — a lowercase "draft", an "ALL" from a client whose dropdown labels
    its empty option, a stale bookmarked URL, a value from a build where the enum was spelled
    differently — came back as {"error": "FieldNotFoundError"} with a stack trace in the log.
    Reproduced on /api/design-workshops, /api/artisans, /api/workshops, /api/products,
    /api/tools, /api/processes, /api/media and /api/questionnaires alike, while the SAME file
    family already answered it correctly twice (tasks.assert_status_value,
    workshops._status_or_422). The fix existed in-repo and had not been applied to the rest.
    """
    from app.services.records import RECORD_STATUSES, enum_filter_or_422

    assert enum_filter_or_422("APPROVED", RECORD_STATUSES) == "APPROVED"
    for bad in ("draft", "in_progress", "ALL", "NOPE", ""):
        with pytest.raises(HTTPException) as raised:
            enum_filter_or_422(bad, RECORD_STATUSES)
        assert raised.value.status_code == 422
        # The message has to carry the vocabulary: a client cannot act on "invalid".
        assert "APPROVED" in raised.value.detail
        assert "status must be one of" in raised.value.detail


def test_the_field_name_travels_into_the_message():
    """`mediaType` and `status` are different query parameters on the same route, and a message
    naming the wrong one sends a developer to the wrong box."""
    from app.services.records import MEDIA_TYPES, enum_filter_or_422

    with pytest.raises(HTTPException) as raised:
        enum_filter_or_422("image", MEDIA_TYPES, field="mediaType")
    assert raised.value.detail.startswith("mediaType must be one of")


# --------------------------------------------------------------------------------------
# The plural of that filter — a multi-select on the wire
#
# `enum_filter_list_or_422` is the grammar `resolve_types` and `resolve_workshop_ids` already
# speak, applied to an enum column: two spellings, three ways to say "everything", and a 422 that
# names the vocabulary. It is a WAVE-1 PRIMITIVE — the roster filters and every other multi-select
# that reaches an enum column are written against exactly this behaviour, at around twenty call
# sites — so what is pinned below is the CONTRACT rather than the implementation, and a test here
# going red means those callers' assumptions have moved, not that a line needs updating.
# --------------------------------------------------------------------------------------

#: The shape the access roster passes it: an upper-case ladder beside one lower-case reserved token.
#: Spelled out here rather than imported from the route that owns the real constant, because that
#: route is a separate parcel and what this file has to pin is that a MIXED-CASE vocabulary — the
#: thing that rules out `resolve_types`' literal `.lower()` — survives the fold intact.
MIXED_VOCABULARY = frozenset({"ADMIN", "DESIGNER", "MASTER_ADMIN", "default"})


def test_the_list_filter_accepts_both_spellings_clients_use():
    """The web and Android build query strings differently and neither may be the wrong one.

    A filter that quietly covered everything because it was spelled the other way would look
    exactly like the filter not working — `resolve_types`' sentence, one column over.
    """
    assert enum_filter_list_or_422(["ADMIN", "DESIGNER"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN",
        "DESIGNER",
    }
    assert enum_filter_list_or_422(["ADMIN,DESIGNER"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN",
        "DESIGNER",
    }
    # And both at once, which is what a URL assembled by two pieces of code looks like.
    assert enum_filter_list_or_422(
        ["ADMIN", "DESIGNER,default"], MIXED_VOCABULARY, field="roles"
    ) == {"ADMIN", "DESIGNER", "default"}


def test_absent_empty_and_all_blank_are_all_do_not_filter():
    """Empty means everything BY ABSENCE, and never by an all-ticked state.

    `None` is not `set()`. A caller that means "no roles at all" has nothing to ask for, while the
    resting state of a multi-select is "everything" — and if a cleared control and a fully ticked
    one were both spelled as the whole vocabulary, the filter would have two states that cannot be
    told apart on the wire and no reader of a request log could tell a default from a deliberate
    choice. The forms below are the ways a client actually sends nothing: the parameter omitted
    (`buildQuery` drops undefined, null AND "" — frontend/lib/api.ts:323-330), the parameter present
    and empty (`?roles=`), and a comma-joined value that is nothing but separators (`?roles=,,`).
    """
    for absent in (None, [], [""], ["   "], [","], [",,"], ["", " , "]):
        assert enum_filter_list_or_422(absent, MIXED_VOCABULARY, field="roles") is None, absent

    # `None` and `set()` are both falsy, so a caller writing `if roles:` would treat them alike and
    # never notice. The contract is `is not None`, and what makes that safe to write is that an
    # empty set is a value this function CANNOT return: every path is either None or non-empty.
    for raw in (None, [], [",,"], ["ADMIN"], ["ADMIN,ADMIN"], ["default"]):
        resolved = enum_filter_list_or_422(raw, MIXED_VOCABULARY, field="roles")
        assert resolved is None or resolved, raw


def test_an_unknown_token_is_a_422_that_names_the_valid_values():
    """A plausible typo must not come back as a well-formed result over the tokens beside it.

    `?roles=ADMIN,DESIGNR` with the second token dropped answers with every admin and no designers
    and looks exactly like a correct answer; dropping the only token widens the request to the whole
    table. Both are `resolve_types`' finding — a wrong answer dressed as a correct one — and the
    422 is what makes a client typo answerable instead.
    """
    with pytest.raises(HTTPException) as raised:
        enum_filter_list_or_422(["ADMIN", "DESIGNR"], MIXED_VOCABULARY, field="roles")
    assert raised.value.status_code == 422
    # The token they typed, so they can go and find it in their own query string...
    assert "DESIGNR" in raised.value.detail
    # ...and the vocabulary, which is the part a client can act on. Deliberately the SAME phrase as
    # the single-value sibling's message, so a developer who has read one has read both.
    assert "roles must be one of" in raised.value.detail
    for member in MIXED_VOCABULARY:
        assert member in raised.value.detail, member

    # A good token beside a bad one does not survive: the check is over the whole list.
    assert "Unknown roles value: DESIGNR." in raised.value.detail

    # Two bad ones are reported together and pluralised, in a fixed order — a message that follows
    # set iteration order would read differently on identical requests.
    with pytest.raises(HTTPException) as raised:
        enum_filter_list_or_422(["ZZZ,AAA"], MIXED_VOCABULARY, field="roles")
    assert "Unknown roles values: AAA, ZZZ." in raised.value.detail


def test_the_field_name_travels_into_the_plural_message_too():
    """`status`, `roles` and `institutions` are three parameters on ONE route, which is why `field`
    is required here where the single-value sibling may default it to "status": a message naming the
    wrong box sends a developer to the wrong control.

    "ALL" is not a hypothetical bad value. It is the one `enum_filter_or_422`'s docstring names —
    a client whose dropdown labels its empty option — and it is exactly the state this grammar
    spells as absence instead.
    """
    from app.services.records import RECORD_STATUSES

    with pytest.raises(HTTPException) as raised:
        enum_filter_list_or_422(["ALL"], RECORD_STATUSES, field="status")
    assert raised.value.detail.startswith("Unknown status value: ALL.")
    assert "status must be one of" in raised.value.detail


def test_a_token_repeated_across_the_two_spellings_narrows_once():
    """A hand-edited URL repeats things, and `?roles=ADMIN&roles=ADMIN,admin` is one filter rather
    than three. It must not reach Prisma as `{"in": ["ADMIN", "ADMIN", "ADMIN"]}` — the same query,
    an unreadable log, and a `total` that invites the reader to wonder whether it double-counted."""
    assert enum_filter_list_or_422(["ADMIN", "ADMIN,admin"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN"
    }
    assert enum_filter_list_or_422(
        ["ADMIN,DESIGNER", "DESIGNER,ADMIN"], MIXED_VOCABULARY, field="roles"
    ) == {"ADMIN", "DESIGNER"}


def test_surrounding_whitespace_does_not_make_a_second_token():
    """`?roles=ADMIN, DESIGNER` is what a person types, and the space after the comma belongs to the
    separator rather than to the value. Without the strip it is an unknown token, so a URL that
    reads correctly to everyone who looks at it is a 422 — and the message would name a token
    differing from a real one only by a character nobody can see."""
    assert enum_filter_list_or_422([" ADMIN "], MIXED_VOCABULARY, field="roles") == {"ADMIN"}
    assert enum_filter_list_or_422(["ADMIN, DESIGNER"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN",
        "DESIGNER",
    }
    # A token differing from its twin ONLY by the whitespace around it collapses onto it rather than
    # arriving as a second member of the set.
    assert enum_filter_list_or_422(["ADMIN", " ADMIN"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN"
    }
    # Whitespace INSIDE a token is not a separator and is not stripped: only the ends are trimmed,
    # so a vocabulary whose members hold spaces (an institution name, §4.5) still matches exactly.
    assert enum_filter_list_or_422(["  MASTER_ADMIN  "], MIXED_VOCABULARY, field="roles") == {
        "MASTER_ADMIN"
    }
    assert enum_filter_list_or_422(
        [" National Institute of Design "], frozenset({"National Institute of Design"}),
        field="institutions",
    ) == {"National Institute of Design"}


def test_the_case_handling_is_resolve_types_case_handling():
    """Stated as an equality against `resolve_types` itself, because "the same grammar" is a claim
    that rots the moment either function is edited alone.

    Over a vocabulary that is entirely lower-case the two accept and reject exactly the same
    strings, which is all `resolve_types`' `part.strip().lower()` amounts to: a fold that decides
    membership. The three absent forms are the one deliberate difference and they are asserted here
    rather than skipped — `resolve_types` returns all five buckets because its caller then ITERATES
    the buckets, while this returns `None` because its caller must write no `where` key at all.
    Same rule, opposite shape, and both mean "everything".
    """
    types = frozenset(RECORD_TYPES)
    for raw in (
        ["artisans", "media"],
        ["artisans,media"],
        ["ARTISANS"],
        ["Artisans", "MEDIA"],
        [" artisans , MEDIA "],
        ["artisans", "artisans"],
        ["artisans,media,tools,products,workshops"],
    ):
        assert enum_filter_list_or_422(raw, types, field="types") == resolve_types(raw), raw

    # Both refuse the same unknown token. Only the message differs, because this one is told which
    # parameter it is guarding and `resolve_types` is only ever guarding `types`.
    for bad in (["artisan"], ["artisans", "artisan"]):
        with pytest.raises(HTTPException):
            resolve_types(bad)
        with pytest.raises(HTTPException):
            enum_filter_list_or_422(bad, types, field="types")

    for absent in (None, [], ["   "]):
        assert resolve_types(absent) == set(RECORD_TYPES)
        assert enum_filter_list_or_422(absent, types, field="types") is None


def test_the_fold_returns_the_vocabularys_spelling_and_never_the_clients():
    """Why this cannot simply call `.lower()` the way `resolve_types` literally does.

    The live vocabulary is `frozenset(ROLE_RANK) | {"default"}` — an upper-case ladder beside one
    lower-case reserved token — so there is no single case to normalise TO. Lowering would put an
    `admitRole` of "admin" into the Prisma `where`, which is a value the enum does not have and
    therefore the FieldNotFoundError 500 that `records.enum_filter_or_422` exists to prevent;
    upper-casing would turn the reserved `default` into a tier nobody holds and silently return an
    empty page. So the fold decides membership ONLY, and what comes back is always a member of
    `allowed` — the guarantee `{"in": sorted(...)}` at every call site rides on.
    """
    from app.core.deps import ROLE_RANK

    vocabulary = frozenset(ROLE_RANK) | {"default"}
    assert enum_filter_list_or_422(["admin"], vocabulary, field="roles") == {"ADMIN"}
    assert enum_filter_list_or_422(["DEFAULT"], vocabulary, field="roles") == {"default"}
    assert enum_filter_list_or_422(["Master_Admin"], vocabulary, field="roles") == {"MASTER_ADMIN"}

    # The property, over every spelling of every member: whatever the client sent, the result is a
    # SUBSET of the vocabulary. Nothing a caller writes into a Prisma enum column can be a string
    # the enum has never heard of, which is the whole reason both siblings exist.
    for member in vocabulary:
        for spelling in (member, member.lower(), member.upper(), f"  {member}  "):
            resolved = enum_filter_list_or_422([spelling], vocabulary, field="roles")
            assert resolved == {member}, spelling
            assert resolved <= vocabulary, spelling


def test_the_plural_accepts_a_lower_case_enum_value_where_the_singular_refuses_it():
    """The one deliberate difference between the siblings, pinned so it stays a decision.

    `enum_filter_or_422("draft", …)` is a 422 whose message tells a developer their casing is
    wrong, and it can afford to be: a single value goes straight into `where["status"] = value`,
    where the column's spelling IS the enum's and there is nothing else to canonicalise against.
    The plural cannot afford it — its vocabularies are mixed inside one set, see the test above —
    so it decides membership by a fold, and once it has, the honest answer for "draft" is the member
    it matched. What must never happen is the string "draft" reaching Prisma, and it cannot: what
    comes back is `DRAFT`.
    """
    from app.services.records import RECORD_STATUSES, enum_filter_or_422

    with pytest.raises(HTTPException):
        enum_filter_or_422("draft", RECORD_STATUSES)
    assert enum_filter_list_or_422(["draft"], RECORD_STATUSES, field="status") == {"DRAFT"}
    assert enum_filter_list_or_422(["draft,PENDING"], RECORD_STATUSES, field="status") == {
        "DRAFT",
        "PENDING",
    }


def test_a_vocabulary_that_folds_onto_itself_is_a_server_error_not_a_silent_winner():
    """The one way the fold can lie, and it is one commit away from the live vocabulary.

    A `DEFAULT` tier added to `ROLE_RANK` would collide with the reserved `default` in
    `frozenset(ROLE_RANK) | {"default"}`. A frozenset has no order, so which member won would
    depend on the hash seed: one picker row would silently become unreachable, or `?roles=default`
    would start filtering `admitRole = "DEFAULT"` instead of `admitRole IS NULL` — a control
    answering a different question than the one printed on its label.

    A `ValueError` and not a 422, because nothing the client sent is wrong; it is the server's own
    token list that cannot be matched. The second half is the assertion that goes red on the day
    somebody adds the colliding tier, which is the only moment anyone can still cheaply rename it.
    """
    from app.core.deps import ROLE_RANK

    broken = frozenset({"DEFAULT", "default", "ADMIN"})
    with pytest.raises(ValueError, match="differ only by case"):
        enum_filter_list_or_422(["ADMIN"], broken, field="roles")

    assert enum_filter_list_or_422(
        ["default"], frozenset(ROLE_RANK) | {"default"}, field="roles"
    ) == {"default"}


def test_the_two_enum_filter_siblings_name_each_other():
    """The cross-reference is not tidiness. These are the only two functions in the backend that
    guard an enum filter, they live in different modules, and a reader who lands on one has no way
    to learn the other exists. The cost of not knowing is specific: a multi-select written against
    the single-value helper in a loop turns a CLEARED control into `{"in": []}`, which narrows the
    page to nothing, where the plural's `None` means "everything" and writes no key at all.
    """
    from app.services import record_filters, records

    assert "enum_filter_list_or_422" in (records.enum_filter_or_422.__doc__ or "")
    assert "enum_filter_or_422" in (record_filters.enum_filter_list_or_422.__doc__ or "")


def test_a_nul_byte_never_reaches_an_equality_filter():
    """A `text` column cannot hold 0x00, so the driver raises and — because this is a query
    PARAMETER — it surfaces as a 500 rather than as a validation error. `contains` had covered
    the search boxes since the last time this happened and left every `where["state"] = state`
    and `where["craftId"] = craftId` beside them unguarded, so ?state=%00 was still a logged
    server error with a stack trace, which the web then shows the operator as "you are offline".
    """
    from app.services.records import plain

    assert plain("\x00") == ""
    assert plain("Odi\x00sha") == "Odisha"
    assert plain("\x01\x02Bargarh\x1f") == "Bargarh"
    # Tab, newline and carriage return survive, exactly as `contains` keeps them: Postgres stores
    # them happily and they appear in pasted multi-line values.
    assert plain("a\tb\nc\rd") == "a\tb\nc\rd"
    assert plain("ସମ୍ବଲପୁରୀ") == "ସମ୍ବଲପୁରୀ", "a stripper that ate real text would be worse"


def test_no_route_still_puts_a_raw_filter_string_into_an_enum_column():
    """The audit that keeps the fix applied as routes are added.

    Eleven sites needed this and two already had it, which is the whole shape of the finding: the
    correct answer was in the repository and nine list endpoints had not been brought along. A
    tenth added next season would 500 the same way, and nothing would notice until a client sent
    a lowercase status.

    An assignment counts as guarded when one of the three validators appears in the same
    function — ``tasks.py`` calls ``assert_status_value`` a few lines above the assignment rather
    than inline, which is just as correct and must not be reported as a defect.
    """
    import pathlib
    import re

    routes = pathlib.Path(__file__).resolve().parents[1] / "app" / "api" / "routes"
    raw = re.compile(r'where\[\s*"(status|mediaType)"\s*\]\s*=\s*(statusFilter|mediaType)\b')
    guards = ("assert_status_value", "_status_or_422", "enum_filter_or_422")

    offenders = []
    for path in sorted(routes.glob("*.py")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if not raw.search(line):
                continue
            # Back to the enclosing `def`, which is the scope the validation has to be in.
            start = i
            while start > 0 and not lines[start].lstrip().startswith(("def ", "async def ")):
                start -= 1
            scope = "\n".join(lines[start:i])
            if not any(guard in scope for guard in guards):
                offenders.append(f"{path.name}:{i + 1}")

    assert not offenders, (
        "these assign an unvalidated query string to an enum column, which is a 500:\n"
        + "\n".join(offenders)
    )


# --------------------------------------------------------------------------------------
# The pattern syntax that leaked out of every search box in the application
#
# Prisma's `contains` compiles to `ILIKE '%' || term || '%'` and the term was interpolated
# unescaped, so `%` and `_` were HONOURED as wildcards rather than matched as characters.
# Measured live against the running API before the escape was added:
#
#   eligible-viewers?search=zzzznomatch ->    0 rows   correct
#   eligible-viewers?search=_           -> 2000 rows   should be 0 — no name or email holds one
#   eligible-viewers?search=%           -> 2000 rows   should be 0
#   eligible-viewers?search=_designer   ->  635 rows   should be 0 — `_` matched any character
#   artisans?search=zzzznomatch         ->    0 rows   correct
#   artisans?search=_                   ->  731 rows   every artisan
#   artisans?search=%                   ->  731 rows   every artisan
#
# NOT SQL INJECTION: Prisma parameterises and the values arrive bound. What leaked was pattern
# syntax. What it COST is the opposite of what a search box is for — an admin pasting a colleague's
# full address `first_last@org` to narrow a list they had just been told was truncated got a WIDER
# result than they typed, because `_` matched any character.
# --------------------------------------------------------------------------------------


def test_a_wildcard_typed_into_a_search_box_is_matched_rather_than_honoured():
    from app.services.records import contains

    # The two metacharacters, escaped so Postgres compares them as characters.
    # RAW STRINGS THROUGHOUT: `"\_"` is not an escape Python knows, so it silently keeps the
    # backslash AND warns — an assertion that happens to be right for a reason that will stop being
    # true. `r"\_"` says the two characters out loud.
    assert contains("_")["contains"] == r"\_"
    assert contains("%")["contains"] == r"\%"
    assert contains("_designer")["contains"] == r"\_designer"
    assert contains("first_last@org")["contains"] == r"first\_last@org"
    assert contains("100%")["contains"] == r"100\%"

    # An ordinary term is untouched, which is the case that must not regress: every one of the 67
    # call sites is overwhelmingly this.
    assert contains("bagru")["contains"] == "bagru"
    assert contains("Ramesh Kumar")["contains"] == "Ramesh Kumar"


def test_the_backslash_is_escaped_first_or_the_escape_escapes_itself():
    r"""THE ORDERING BUG THIS GUARDS AGAINST, which is the classic one in every escaping routine.

    Escape `%` before `\` and a typed backslash becomes an escape for the escape. A designer typing
    the two characters `\%` would be sent `\` + `\%` under the WRONG order — that is a literal
    backslash followed by an UNESCAPED `%`, so the wildcard is back, by way of the fix.
    """
    from app.services.records import contains

    # One typed backslash -> one escaped backslash.
    assert contains("\\")["contains"] == r"\\"
    # A typed backslash AND a typed percent -> both escaped, backslash first: `\\` then `\%`.
    assert contains(r"\%")["contains"] == r"\\\%"
    # Spelled out once without any escaping at all, so the expectation cannot be read two ways.
    backslash = chr(92)
    assert contains(backslash + "%")["contains"] == backslash * 2 + backslash + "%"


def test_the_control_byte_strip_still_runs_and_composes_with_the_escape():
    """Both treatments, on one value. The NUL goes, and what is left is escaped."""
    from app.services.records import contains

    assert contains("bag\x00ru")["contains"] == "bagru"
    assert contains("first_last\x00@org")["contains"] == r"first\_last@org"
    # Tab/newline/CR are deliberately kept and are not LIKE metacharacters, so they pass through.
    assert contains("two\nlines")["contains"] == "two\nlines"


def test_an_equality_filter_is_deliberately_NOT_escaped():
    """`plain` compares EQUAL, and an `=` comparison has no pattern syntax in it.

    Escaping here would be a new defect wearing the fix's clothes: `?state=A_P` would stop matching
    the row that literally IS `A_P`. The two helpers sit next to each other and treat the same
    character differently ON PURPOSE, which is why this asserts it rather than leaving it to be
    "tidied up" by the next reader.
    """
    from app.services.records import plain

    assert plain("A_P") == "A_P"
    assert plain("100%") == "100%"
    assert plain("back" + chr(92) + "slash") == "back" + chr(92) + "slash"
    # It still strips the byte Postgres cannot store, which is the job it does have.
    assert plain("A\x00P") == "AP"


def test_the_search_route_carries_the_escape_all_the_way_into_the_where():
    """The funnel is only a funnel if the routes actually go through it."""
    wheres = asyncio.run(build_record_wheres(ADMIN, q="first_last@org"))
    assert wheres["artisans"]["OR"][0]["name"]["contains"] == r"first\_last@org"


def test_no_route_still_hand_rolls_a_contains_filter():
    r"""THE SWEEP, and the sweep IS the fix — five per-endpoint cases would not have stopped a sixth.

    ``contains`` does two things no route can be relied on to remember: it strips the C0 control
    bytes Postgres cannot store in a ``text`` column (a pasted NUL was a bare 500 from every search
    box in the product) and it escapes the LIKE metacharacters, so a term holding ``_`` or ``%``
    is MATCHED rather than honoured as a wildcard. Its docstring has claimed for a while that every
    text search funnels through it. Five did not, and no count of the funnel's call sites could ever
    have found them — a route that bypasses the funnel is, by construction, absent from the count:

        access.py            the platform allow-list roster (email / fullName / notes)
        designers.py         the designer roster (email / fullName / institution)
        designers.py         the designer directory (name / email)
        questionnaire_forms.py   the questionnaire list (title / description)
        services/design_workshops.py   the REF picker's search box (spec.search_fields)

    So this asserts the property instead of the five instances: a literal ``"contains"`` KEY in a
    filter dict, anywhere under ``app/api/routes`` or ``app/services``, is a search box that skipped
    the sanitiser. ``records.contains`` itself is the one legitimate writer of that key.

    ``prose_contains`` is not an exception — it composes ``contains`` and returns its dict — so a
    rich-text column is served by calling that, never by writing the key out by hand.
    """
    import ast
    import pathlib

    backend = pathlib.Path(__file__).resolve().parents[1] / "app"

    # PARSED, NOT GREPPED, AND THE DIFFERENCE IS NOT PEDANTRY. This scan began as a regex over raw
    # lines, and the first thing it did once the last real offender was fixed was fail on the
    # COMMENT that records the fix: ``design_workshops.py`` explains at the top of the file that the
    # REF picker "composed ``{"contains": …, "mode": "insensitive"}`` by hand", and to a text search
    # a sentence quoting the defect is indistinguishable from the defect. That is a bad failure in
    # the specific way a sweep cannot afford: it fires on the prose a fix is obliged to leave
    # behind, so the cheapest way to make the suite green is to stop describing the bug — this
    # repository's comments are load-bearing, and a test that taxes them will be obeyed.
    #
    # Walking the AST asserts the actual property instead: a ``contains`` key WRITTEN OUT IN CODE,
    # which is what reaches Prisma. Comments and docstrings are not in the tree at all, so they cost
    # nothing and no exemption has to be invented for them.
    #
    # ALL THREE SPELLINGS, because narrowing to the dict literal would have narrowed the sweep as
    # well as sharpening it — the raw-line scan this replaced did catch ``d["contains"] = term``,
    # and the sixth offender somebody writes is as likely to be built up key by key as declared in
    # one literal. None of the three can occur inside a comment or a docstring, so widening this way
    # does not reintroduce the failure the AST walk exists to prevent.
    def _hand_rolled_lines(source: str) -> list[int]:
        found: set[int] = set()
        for node in ast.walk(ast.parse(source)):
            # 1. the literal: {"email": {"contains": term, "mode": "insensitive"}}
            if isinstance(node, ast.Dict):
                if any(isinstance(k, ast.Constant) and k.value == "contains" for k in node.keys):
                    found.add(node.lineno)
            # 2. built up by subscript: clause["contains"] = term (also a read, which is just as
            #    much a sign that a route is handling the raw filter itself).
            elif isinstance(node, ast.Subscript):
                index = node.slice
                if isinstance(index, ast.Constant) and index.value == "contains":
                    found.add(node.lineno)
            # 3. the dict() constructor: dict(contains=term, mode="insensitive"). Only ``dict``,
            #    because ``contains=`` is an ordinary keyword on anything else.
            elif isinstance(node, ast.Call):
                func = node.func
                name = func.id if isinstance(func, ast.Name) else None
                if name == "dict" and any(kw.arg == "contains" for kw in node.keywords):
                    found.add(node.lineno)
        return sorted(found)

    # THERE IS NO ALLOW-LIST ANY MORE, AND THAT IS THE POINT OF THIS LINE STILL BEING HERE.
    # ``services/design_workshops.py`` held the last exemption — the REF picker composed the raw
    # filter for ``spec.search_fields`` — and it was carved out only because another change was in
    # flight in that file. It now calls ``records.contains`` like the other five, so the set is
    # empty. Do not reintroduce it: an allowance that outlives its reason is how a sweep stops
    # sweeping, and the whole argument of this test is that the property beats the instances.
    ALLOWED: set[str] = set()

    offenders = []
    for path in sorted([*(backend / "api" / "routes").glob("*.py"), *(backend / "services").glob("*.py")]):
        # The helper's own definition is where the key is supposed to be written.
        if path.name == "records.py" and path.parent.name == "services":
            continue
        if f"{path.parent.name}/{path.name}" in ALLOWED:
            continue
        for lineno in _hand_rolled_lines(path.read_text(encoding="utf-8")):
            offenders.append(f"{path.parent.name}/{path.name}:{lineno}")

    assert not offenders, (
        "these compose a Prisma `contains` filter by hand, so the NUL strip and the LIKE escape in "
        "`records.contains` never run for them — a pasted control byte is a 500 and a typed `_` "
        "silently widens the result:\n" + "\n".join(offenders)
    )
