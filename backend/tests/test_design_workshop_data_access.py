"""Who may READ design-workshop stage data through the research surfaces, and who may TAKE IT OUT.

TWO RULES LIVE IN THIS FILE AND THE WHOLE POINT IS THAT THEY ARE DIFFERENT SIZES. Reading them as
one is the mistake these tests exist to make impossible.

1. VIEWING it — the ``by-design-workshop`` taxonomy and the design-workshop sheets in View Data, and
   the ``designWorkshops`` bucket of Search — is ``can_view_design_workshop_data``, the SET
   {PROFESSOR, ADMIN, MASTER_ADMIN}.

2. DOWNLOADING it — the .xlsx workbook and the manifest the browser zips — is
   ``can_export_design_workshop_data``, {ADMIN, MASTER_ADMIN}. STRICTLY NARROWER, and a professor is
   the population that proves it: they read a table on screen and may not export the same rows.

Owner ruling, 2026-08-30, verbatim: "professor can view data for design workshops as well, admins
and master admins can download and view it too." The argument is written up in
``docs/DECISION-design-workshop-data-in-view-data.md``.

THREE FAILURE MODES ARE PINNED HERE BECAUSE EACH IS A CHANGE SOMEBODY WOULD MAKE FOR GOOD REASONS:

* **Collapsing the two predicates into one.** It would either hand every professor a file of stage
  answers or take the seven legacy tables away from them; the tests below assert both directions.
* **Folding the new capability into ``DESIGN_WORKSHOP_ROLES``** because the names look alike. That
  set is "who may WRITE inside a workshop" and excludes PROFESSOR on purpose; widening it would hand
  every professor a designer's write capability in a diff whose only visible change is a role string.
* **Reading ``can_download_dataset`` as the answer**, because ``/data`` is already mounted behind it.
  It admits a RESEARCHER holding the grantable ``canDownloadDataset`` flag, and that account must not
  reach stage data.

Unit-level on the predicates, on the module constants and on the frontend source, so no database.
"""

from pathlib import Path

import pytest
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes.data_browser import (
    _DESIGN_WORKSHOP_TAGS,
    _MISC_WHERE_LEGACY,
    _TYPED_TAGS,
    _USER_TYPE_WHERE,
    Scope,
    _dw_withheld_sheet,
    _taxonomies_for,
    _user_type_where,
)
from app.core.deps import (
    DESIGN_WORKSHOP_DATA_EXPORT_ROLES,
    DESIGN_WORKSHOP_DATA_VIEW_ROLES,
    DESIGN_WORKSHOP_ROLES,
    can_download_dataset,
    can_export_design_workshop_data,
    can_run_design_workshops,
    can_view_design_workshop_data,
)
from app.services import design_workshop_data as dw
from app.services.record_filters import (
    DESIGN_WORKSHOP_STAGE_RELATION,
    DESIGN_WORKSHOP_TEXT_COLUMNS,
    DESIGN_WORKSHOP_TYPE,
    RECORD_TYPES,
    SEARCH_TYPES,
    design_workshop_stage_text_clause,
    design_workshop_where,
    resolve_types,
)


class _User:
    """The two attributes every predicate in ``deps`` reads off an account."""

    def __init__(self, role: str, *, can_download_dataset: bool = False) -> None:
        self.role = role
        self.id = "u1"
        self.canDownloadDataset = can_download_dataset


EVERY_ROLE = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "DESIGNER",
    "INSPECTOR",
    "PROFESSOR",
    "ADMIN",
    "MASTER_ADMIN",
)

MAY_VIEW = ("PROFESSOR", "ADMIN", "MASTER_ADMIN")
MAY_EXPORT = ("ADMIN", "MASTER_ADMIN")


def test_this_file_still_covers_every_tier_that_exists() -> None:
    """``EVERY_ROLE`` is a hand-kept tuple and this is what stops it going quietly short.

    ``tests/test_role_ladder_parity.py`` sweeps the FRONTEND and ANDROID trees for stray copies of
    the ladder and does not reach ``backend/tests``, so nothing outside this file watches this tuple.
    A tier added to ``ROLE_RANK`` and forgotten here would not fail anything: both parametrised tests
    above would simply stop asking about it, and a suite that iterates seven of eight tiers reports
    exactly the same green as one that iterates eight. That is the failure ``test_permission_matrix``
    guards the same way, in the same words, for the same reason.
    """
    from app.core.deps import ROLE_RANK

    assert set(ROLE_RANK) == set(EVERY_ROLE), (
        "the ladder moved and this file's EVERY_ROLE did not — every assertion below is now silent "
        "about the difference"
    )


# ── The two sets ──────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("role", EVERY_ROLE)
def test_the_view_set_is_exactly_the_three_roles_the_owner_named(role: str) -> None:
    assert can_view_design_workshop_data(_User(role)) is (role in MAY_VIEW)


@pytest.mark.parametrize("role", EVERY_ROLE)
def test_the_export_set_is_admin_and_master_admin(role: str) -> None:
    assert can_export_design_workshop_data(_User(role)) is (role in MAY_EXPORT)


def test_exporting_is_strictly_narrower_than_viewing() -> None:
    """The split is the point: there must be a population that reads and may not export.

    A change that made these two sets equal would pass every other test in this file while removing
    the entire reason the second predicate exists.
    """
    assert DESIGN_WORKSHOP_DATA_EXPORT_ROLES < DESIGN_WORKSHOP_DATA_VIEW_ROLES
    professor = _User("PROFESSOR")
    assert can_view_design_workshop_data(professor)
    assert not can_export_design_workshop_data(professor)


def test_a_granted_researcher_reaches_data_and_not_design_workshops() -> None:
    """The account this narrowing exists for.

    ``canDownloadDataset`` is a per-account boolean an admin hands to a researcher who needs the
    seven legacy tables. It carries no seniority, so it must not reach stage data — while everything
    it has always opened stays open.
    """
    granted = _User("RESEARCHER", can_download_dataset=True)
    assert can_download_dataset(granted), "the grant must still open /data itself"
    assert not can_view_design_workshop_data(granted)
    assert not can_export_design_workshop_data(granted)


def test_the_new_capability_does_not_widen_the_designer_set() -> None:
    """``DESIGN_WORKSHOP_ROLES`` is who may WRITE inside a workshop, and it is untouched.

    The two sets are almost opposites — that one holds DESIGNER and refuses PROFESSOR, this one the
    reverse — and that is not a contradiction: reading a table of what a corpus recorded is not
    writing inside somebody's fortnight of work.
    """
    assert frozenset({"DESIGNER", "ADMIN", "MASTER_ADMIN"}) == DESIGN_WORKSHOP_ROLES
    assert "PROFESSOR" not in DESIGN_WORKSHOP_ROLES
    assert not can_run_design_workshops(_User("PROFESSOR")), (
        "a professor must gain READ of research data and nothing at all inside a workshop"
    )
    assert not can_view_design_workshop_data(_User("DESIGNER")), (
        "a designer reaches their OWN workshops by grant; this predicate opens every workshop in "
        "the repository and is a different door"
    )


def test_inspector_sits_below_the_view_set() -> None:
    """The tier the SET (rather than a rank floor) is defending against.

    An inspector inspects ONE workshop under a grant. A ``has_rank(user, "PROFESSOR")`` floor would
    give the same answer today and would hand them every workshop in the repository the day a rank
    is renumbered.
    """
    assert not can_view_design_workshop_data(_User("INSPECTOR"))
    assert "INSPECTOR" not in DESIGN_WORKSHOP_DATA_VIEW_ROLES


# ── The web mirrors both, or the UI offers what the API refuses ────────────────────────────────


def _permissions_ts() -> str:
    web = Path(__file__).resolve().parents[2] / "frontend/lib/permissions.ts"
    if not web.is_file():
        pytest.skip("the frontend is not present in this checkout")
    return web.read_text(encoding="utf-8")


def _declared_roles(text: str, name: str) -> str:
    """The array literal of one exported role list.

    Matched by regex rather than by splitting on the name, for the reason
    ``test_design_workshop_gate`` records: splitting takes the LAST occurrence, which is the
    reference inside the predicate function and contains no role at all.
    """
    import re

    found = re.search(rf"{name}\s*:[^=]*=\s*\[([^\]]*)\]", text)
    assert found, f"frontend/lib/permissions.ts has no `{name} = [...]` declaration"
    return found.group(1)


def test_the_web_declares_the_same_view_set() -> None:
    declared = _declared_roles(_permissions_ts(), "DESIGN_WORKSHOP_DATA_VIEW_ROLES")
    for role in DESIGN_WORKSHOP_DATA_VIEW_ROLES:
        assert f'"{role}"' in declared, f"{role} may view on the server but not on the web"
    assert '"RESEARCHER"' not in declared, (
        "the web admits a researcher where the server does not — they would be shown a folder the "
        "API answers 404 for"
    )
    assert '"DESIGNER"' not in declared


def test_the_web_declares_the_same_export_set() -> None:
    declared = _declared_roles(_permissions_ts(), "DESIGN_WORKSHOP_DATA_EXPORT_ROLES")
    for role in DESIGN_WORKSHOP_DATA_EXPORT_ROLES:
        assert f'"{role}"' in declared, f"{role} may export on the server but not on the web"
    assert '"PROFESSOR"' not in declared, (
        "the web offers a professor a download the API refuses — the failure mode the whole split "
        "is written to avoid"
    )


# ── The scope the four /data routes consult ───────────────────────────────────────────────────


def test_for_download_collapses_viewing_into_exporting() -> None:
    """On a download route, viewing IS exporting: everything listed leaves the building."""
    professor = Scope(records={}, media={}, design_workshops=True, design_workshop_downloads=False)
    assert professor.for_download().design_workshops is False
    admin = Scope(records={}, media={}, design_workshops=True, design_workshop_downloads=True)
    assert admin.for_download().design_workshops is True


def test_the_capability_flags_do_not_make_a_scope_restricted() -> None:
    """``restricted`` means "are the ROW filters narrowing anything", and nothing else.

    Folding a capability flag into it would make a professor — whose row filters are empty and always
    have been — take the restricted branch of six queries, changing their query plans to protect data
    the flags already keep out of the result.
    """
    assert not Scope(records={}, media={}, design_workshops=True).restricted
    assert not Scope(records={}, media={}, design_workshop_downloads=True).restricted
    assert Scope(records={"createdById": "u1"}, media={}).restricted


def test_the_taxonomy_is_hidden_from_an_account_that_may_not_read_it() -> None:
    ids = [t["id"] for t in _taxonomies_for(Scope(records={}, media={}, design_workshops=False))]
    assert "by-design-workshop" not in ids
    assert ids == ["by-workshop", "by-uploader", "by-type"], (
        "the other three taxonomies must be untouched for everybody"
    )
    with_access = [
        t["id"] for t in _taxonomies_for(Scope(records={}, media={}, design_workshops=True))
    ]
    assert "by-design-workshop" in with_access


# ── The media-identity fix ────────────────────────────────────────────────────────────────────


def test_both_spellings_of_the_workshop_tag_are_typed() -> None:
    """The clients send camelCase; ``POST /media/{id}/relink`` lower-cases what it stores.

    Listing one and not the other leaves half the files in Miscellaneous — the defect half-fixed,
    which is worse than not fixing it because it looks fixed.
    """
    assert "designWorkshop" in _TYPED_TAGS
    assert "designworkshop" in _TYPED_TAGS
    assert set(_DESIGN_WORKSHOP_TAGS) == {"designWorkshop", "designworkshop"}


def test_misc_keeps_its_old_reading_for_an_account_without_the_capability() -> None:
    """NOBODY SEES LESS THAN BEFORE.

    Moving design-workshop files out of Miscellaneous is right for a reader who has the
    ``designworkshops`` branch to find them in. An account that may not open it would simply have
    lost the files — listed yesterday, gone today, with nothing on screen to say why.
    """
    without = Scope(records={}, media={}, design_workshops=False)
    with_access = Scope(records={}, media={}, design_workshops=True)
    assert _user_type_where("misc", without) == _MISC_WHERE_LEGACY
    assert _user_type_where("misc", with_access) == _USER_TYPE_WHERE["misc"]
    # Every other branch is capability-independent, so a reader without access sees them unchanged.
    for slug in ("artisans", "products", "tools", "workshops", "questionnaire"):
        assert _user_type_where(slug, without) == _USER_TYPE_WHERE[slug]


def test_the_design_workshop_media_branch_reads_both_columns() -> None:
    """The tag pair and ``designWorkshopId`` answer different questions and a file may carry either."""
    branch = _USER_TYPE_WHERE["designworkshops"]
    assert {"linkedRecordType": {"in": _DESIGN_WORKSHOP_TAGS}} in branch["OR"]
    assert {"designWorkshopId": {"not": None}} in branch["OR"]


# ── The sixth search bucket ───────────────────────────────────────────────────────────────────


def test_the_map_vocabulary_is_still_five_and_search_is_six() -> None:
    assert DESIGN_WORKSHOP_TYPE not in RECORD_TYPES, (
        "a design workshop has no locationId/place column; on the map that is a 500, not an empty "
        "bucket"
    )
    assert (*RECORD_TYPES, DESIGN_WORKSHOP_TYPE) == SEARCH_TYPES


def test_the_default_vocabulary_still_refuses_the_sixth_bucket() -> None:
    """``resolve_types``' default is the map's five, so the wider set has to be asked for by name.

    A 422 NAMING THE VOCABULARY, not a silent omission: dropping the token would answer a request for
    a bucket this screen does not have with a perfectly well-formed result over the other five.
    """
    with pytest.raises(HTTPException) as refusal:
        resolve_types([DESIGN_WORKSHOP_TYPE])
    assert refusal.value.status_code == 422
    assert DESIGN_WORKSHOP_TYPE in str(refusal.value.detail)


@pytest.mark.parametrize("spelling", ["designWorkshops", "designworkshops", "DESIGNWORKSHOPS"])
def test_the_bucket_name_folds_case_and_comes_back_canonical(spelling: str) -> None:
    """Android lower-cases ``types`` before sending them, so a case-sensitive match would drop it.

    The vocabulary's OWN spelling is what is returned, so everything downstream compares with ``==``.
    """
    assert resolve_types([spelling], allowed=SEARCH_TYPES) == {DESIGN_WORKSHOP_TYPE}


def test_the_five_lower_case_buckets_are_unchanged_by_the_fold() -> None:
    assert resolve_types(["artisans,media"]) == {"artisans", "media"}
    assert resolve_types(None) == set(RECORD_TYPES)


def test_a_deleted_design_workshop_can_never_come_back_from_search() -> None:
    """Nothing hard-deletes a workshop; a search box that returned one would resurrect removed work."""
    where = design_workshop_where({})
    assert {"deletedAt": None} in where["AND"]


def test_the_craft_filter_reaches_through_the_link_tables() -> None:
    """``craftName`` is a promoted STRING — testing a cuid against it matches nothing at all."""
    where = design_workshop_where({}, craft_id="craft-1")
    branches = [clause for clause in where["AND"] if "OR" in clause]
    assert branches, "a craft filter must narrow this bucket rather than being dropped"
    reached = {next(iter(option)) for option in branches[0]["OR"]}
    assert reached == {"artisansLinked", "productsLinked", "toolsLinked"}
    assert "craftName" not in str(where)


def test_a_free_text_query_covers_every_promoted_column_and_the_stage_answers() -> None:
    """Every column the module DECLARES searchable is actually in the clause — plus the stage text.

    Held to the declaration rather than to a literal list, because the declaration is what the route
    describes to the client in ``designWorkshopSearchScope``: a column added to one and not the other
    is a sentence on screen that does not match what was searched.

    THE STAGE ARM JOINED THE SAME OR ON 2026-08-31, which is §6.1 of
    ``docs/DECISION-design-workshop-data-in-view-data.md`` being closed. It is one OR and not a
    second filter because the two answer ONE question — "does this workshop have anything to do with
    indigo" — and a researcher who had to tick a box to decide whether the answer might come from
    stage 5 would be being asked about our storage layout. This assertion is what stops the arm being
    dropped while the route goes on printing a sentence that promises it.
    """
    where = design_workshop_where({}, q="indigo")
    assert {next(iter(clause)) for clause in where["OR"]} == {
        *DESIGN_WORKSHOP_TEXT_COLUMNS,
        DESIGN_WORKSHOP_STAGE_RELATION,
    }


def test_a_stage_answer_of_a_DELETED_row_can_never_match() -> None:
    """The soft-delete flag inside the ``some`` is the ROW's, not the workshop's.

    A stage row is soft-deleted whenever a designer removes a sketch or a cost line, and the row
    stays in the table with its rendered copy intact. Without this clause a search would surface a
    live workshop because of an answer its designer had deleted — the one place in the product that
    resurrects removed work, to the widest audience.
    """
    clause = design_workshop_stage_text_clause("indigo")
    assert clause[DESIGN_WORKSHOP_STAGE_RELATION]["some"]["deletedAt"] is None


# ── The pure half: attributions and the sheet plan ────────────────────────────────────────────


class _Record:
    def __init__(self) -> None:
        self.id = "w1"
        self.title = "Chanderi weaving"
        self.workshopCode = "DW-001"


class _Entry:
    def __init__(self, entity_key: str, data: dict, ordinal: int = 0) -> None:
        self.entityKey = entity_key
        self.data = data
        self.ordinal = ordinal
        self.id = f"e-{entity_key}-{ordinal}"


def _first_media_field() -> tuple[str, str]:
    """An (entityKey, fieldKey) pair the live registry really has, so this test cannot go stale."""
    for stage in dw.stages():
        for entity in stage.entities:
            for spec in entity.fields:
                if spec.type.is_media:
                    return entity.key, spec.key
    pytest.skip("this registry declares no media field")


def test_a_media_file_is_named_by_the_stage_row_that_cites_it() -> None:
    entity_key, field_key = _first_media_field()
    index = dw.media_attributions(_Record(), [_Entry(entity_key, {field_key: ["m1", "m2"]})])
    assert set(index) == {"m1", "m2"}
    attribution = index["m1"]
    assert attribution.workshop_id == "w1"
    assert attribution.entity_key == entity_key
    assert attribution.field_key == field_key
    assert attribution.stage_number >= 1
    assert f"Stage {attribution.stage_number}" in attribution.label


def test_a_row_from_a_newer_registry_is_skipped_rather_than_guessed_at() -> None:
    assert dw.media_attributions(_Record(), [_Entry("_not_a_real_entity", {"x": ["m1"]})]) == {}


def test_the_sheet_plan_names_every_table_it_could_not_show() -> None:
    """Rule 10 for a workbook: what is cut has to be nameable, or the cap hides data silently."""
    counts = {table.entity_key: 5 for table in dw.tables()}
    plan = dw.sheet_plan(counts, limit=3)
    assert len(plan.included) == 3
    assert plan.truncated
    assert len(plan.included) + len(plan.omitted) + len(plan.empty) == len(dw.tables())
    assert not set(plan.included) & set(plan.omitted)


def test_a_withheld_workbook_names_what_it_is_missing() -> None:
    """A file outlives the page it came from, so the omission is written INTO it.

    The screen says it beside the download button, and that sentence is gone the moment the .xlsx is
    archived, mailed on, or opened a year later. A workbook that silently lacked the design-workshop
    block would read as a repository with no design workshops in it — the same failure as a list that
    quietly stops, in the artefact a researcher keeps.
    """
    dropped = [
        {"name": "Design workshops", "rows": [[1], [2]]},
        {"name": "01 Workshop details", "rows": [[1]]},
    ]
    notice = _dw_withheld_sheet(dropped)
    assert [row[0] for row in notice["rows"]] == ["Design workshops", "01 Workshop details"]
    assert [row[1] for row in notice["rows"]] == [2, 1], "the row counts are the coverage answer"
    assert notice["truncated"], "the web viewer must render this as a banner, not as a data row"
    assert "admin" in (notice["truncatedNote"] or "").lower(), (
        "the note has to name who CAN export, or it is a refusal with no next move"
    )


def test_an_entity_with_no_rows_is_reported_as_empty_not_omitted() -> None:
    """"No rows found" and "did not fit" are different facts and a reader acts on them differently."""
    plan = dw.sheet_plan({})
    assert plan.included == ()
    assert plan.omitted == ()
    assert len(plan.empty) == len(dw.tables())
    assert not plan.truncated
