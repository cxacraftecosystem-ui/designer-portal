"""The shared filter language, which had no tests at all while it lived inside the search route.

These are the rules the Search page, View Data and the map all depend on agreeing about. The two
that matter most are the ones a refactor would break silently: that row visibility survives a
free-text query, and that every active filter narrows rather than widens.
"""

import asyncio
from datetime import UTC, datetime

import pytest
from fastapi import HTTPException

from app.services.record_filters import (
    PLACED_TYPES,
    RECORD_TYPES,
    build_record_wheres,
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


def test_the_download_predicate_still_narrows_below_professor():
    # Reading opened up; TAKING DATA OUT did not. This is the predicate every /export and /data query
    # still rides, asserted directly because no read-side clause carries it any more.
    from app.services.records import owned_or_granted_where

    records = asyncio.run(owned_or_granted_where(VOLUNTEER))
    assert records["OR"][0] == {"createdById": "u1"}
    assert records["OR"][1]["createdBy"]["is"]["dataAccessAsOwner"]["some"] == {
        "granteeId": "u1",
        "status": "GRANTED",
    }

    media = asyncio.run(owned_or_granted_where(VOLUNTEER, owner_field="uploadedById"))
    assert media["OR"][0] == {"uploadedById": "u1"}
    assert "uploadedBy" in media["OR"][1]

    # Professor and above take everything, as before.
    assert asyncio.run(owned_or_granted_where(ADMIN)) == {}


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
