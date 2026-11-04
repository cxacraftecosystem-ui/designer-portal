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
    "ASSISTANT_DIRECTOR",
    "REGIONAL_DIRECTOR",
    "MINISTRY_ADMIN",
    "ADMIN",
    "MASTER_ADMIN",
)

MAY_VIEW = ("PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")
MAY_EXPORT = ("ADMIN", "MASTER_ADMIN")


def test_this_file_still_covers_every_tier_that_exists() -> None:
    from app.core.deps import ROLE_RANK

    assert set(ROLE_RANK) == set(EVERY_ROLE), (
        "the ladder moved and this file's EVERY_ROLE did not — every assertion below is now silent "
        "about the difference"
    )



@pytest.mark.parametrize("role", EVERY_ROLE)
def test_the_view_set_is_exactly_the_three_roles_the_owner_named(role: str) -> None:
    assert can_view_design_workshop_data(_User(role)) is (role in MAY_VIEW)


@pytest.mark.parametrize("role", EVERY_ROLE)
def test_the_export_set_is_admin_and_master_admin(role: str) -> None:
    assert can_export_design_workshop_data(_User(role)) is (role in MAY_EXPORT)


def test_exporting_is_strictly_narrower_than_viewing() -> None:
    assert DESIGN_WORKSHOP_DATA_EXPORT_ROLES < DESIGN_WORKSHOP_DATA_VIEW_ROLES
    professor = _User("PROFESSOR")
    assert can_view_design_workshop_data(professor)
    assert not can_export_design_workshop_data(professor)


def test_a_granted_researcher_reaches_data_and_not_design_workshops() -> None:
    granted = _User("RESEARCHER", can_download_dataset=True)
    assert can_download_dataset(granted), "the grant must still open /data itself"
    assert not can_view_design_workshop_data(granted)
    assert not can_export_design_workshop_data(granted)


def test_the_new_capability_does_not_widen_the_designer_set() -> None:
    assert "PROFESSOR" not in DESIGN_WORKSHOP_ROLES
    assert "INSPECTOR" not in DESIGN_WORKSHOP_ROLES, (
        "an inspector may not write inside a workshop it reviews; it reaches one by grant"
    )
    assert DESIGN_WORKSHOP_ROLES - {"DESIGNER"} <= DESIGN_WORKSHOP_DATA_VIEW_ROLES, (
        "a tier may write inside every workshop and cannot read the stage data — DESIGNER is the "
        "one exception and reaches its OWN workshops by grant rather than by this predicate"
    )
    assert not can_run_design_workshops(_User("PROFESSOR")), (
        "a professor must gain READ of research data and nothing at all inside a workshop"
    )
    assert not can_view_design_workshop_data(_User("DESIGNER")), (
        "a designer reaches their OWN workshops by grant; this predicate opens every workshop in "
        "the repository and is a different door"
    )


def test_inspector_sits_below_the_view_set() -> None:
    assert not can_view_design_workshop_data(_User("INSPECTOR"))
    assert "INSPECTOR" not in DESIGN_WORKSHOP_DATA_VIEW_ROLES



def _permissions_ts() -> str:
    web = Path(__file__).resolve().parents[2] / "frontend/lib/permissions.ts"
    if not web.is_file():
        pytest.skip("the frontend is not present in this checkout")
    return web.read_text(encoding="utf-8")


def _declared_roles(text: str, name: str) -> str:
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



def test_for_download_collapses_viewing_into_exporting() -> None:
    professor = Scope(records={}, media={}, design_workshops=True, design_workshop_downloads=False)
    assert professor.for_download().design_workshops is False
    admin = Scope(records={}, media={}, design_workshops=True, design_workshop_downloads=True)
    assert admin.for_download().design_workshops is True


def test_the_capability_flags_do_not_make_a_scope_restricted() -> None:
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



def test_both_spellings_of_the_workshop_tag_are_typed() -> None:
    assert "designWorkshop" in _TYPED_TAGS
    assert "designworkshop" in _TYPED_TAGS
    assert set(_DESIGN_WORKSHOP_TAGS) == {"designWorkshop", "designworkshop"}


def test_misc_keeps_its_old_reading_for_an_account_without_the_capability() -> None:
    without = Scope(records={}, media={}, design_workshops=False)
    with_access = Scope(records={}, media={}, design_workshops=True)
    assert _user_type_where("misc", without) == _MISC_WHERE_LEGACY
    assert _user_type_where("misc", with_access) == _USER_TYPE_WHERE["misc"]
    for slug in ("artisans", "products", "tools", "workshops", "questionnaire"):
        assert _user_type_where(slug, without) == _USER_TYPE_WHERE[slug]


def test_the_design_workshop_media_branch_reads_both_columns() -> None:
    branch = _USER_TYPE_WHERE["designworkshops"]
    assert {"linkedRecordType": {"in": _DESIGN_WORKSHOP_TAGS}} in branch["OR"]
    assert {"designWorkshopId": {"not": None}} in branch["OR"]



def test_the_map_vocabulary_is_still_five_and_search_is_six() -> None:
    assert DESIGN_WORKSHOP_TYPE not in RECORD_TYPES, (
        "a design workshop has no locationId/place column; on the map that is a 500, not an empty "
        "bucket"
    )
    assert (*RECORD_TYPES, DESIGN_WORKSHOP_TYPE) == SEARCH_TYPES


def test_the_default_vocabulary_still_refuses_the_sixth_bucket() -> None:
    with pytest.raises(HTTPException) as refusal:
        resolve_types([DESIGN_WORKSHOP_TYPE])
    assert refusal.value.status_code == 422
    assert DESIGN_WORKSHOP_TYPE in str(refusal.value.detail)


@pytest.mark.parametrize("spelling", ["designWorkshops", "designworkshops", "DESIGNWORKSHOPS"])
def test_the_bucket_name_folds_case_and_comes_back_canonical(spelling: str) -> None:
    assert resolve_types([spelling], allowed=SEARCH_TYPES) == {DESIGN_WORKSHOP_TYPE}


def test_the_five_lower_case_buckets_are_unchanged_by_the_fold() -> None:
    assert resolve_types(["artisans,media"]) == {"artisans", "media"}
    assert resolve_types(None) == set(RECORD_TYPES)


def test_a_deleted_design_workshop_can_never_come_back_from_search() -> None:
    where = design_workshop_where({})
    assert {"deletedAt": None} in where["AND"]


def test_the_craft_filter_reaches_through_the_link_tables() -> None:
    where = design_workshop_where({}, craft_id="craft-1")
    branches = [clause for clause in where["AND"] if "OR" in clause]
    assert branches, "a craft filter must narrow this bucket rather than being dropped"
    reached = {next(iter(option)) for option in branches[0]["OR"]}
    assert reached == {"artisansLinked", "productsLinked", "toolsLinked"}
    assert "craftName" not in str(where)


def test_a_free_text_query_covers_every_promoted_column_and_the_stage_answers() -> None:
    where = design_workshop_where({}, q="indigo")
    assert {next(iter(clause)) for clause in where["OR"]} == {
        *DESIGN_WORKSHOP_TEXT_COLUMNS,
        DESIGN_WORKSHOP_STAGE_RELATION,
    }


def test_a_stage_answer_of_a_DELETED_row_can_never_match() -> None:
    clause = design_workshop_stage_text_clause("indigo")
    assert clause[DESIGN_WORKSHOP_STAGE_RELATION]["some"]["deletedAt"] is None



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
    counts = {table.entity_key: 5 for table in dw.tables()}
    plan = dw.sheet_plan(counts, limit=3)
    assert len(plan.included) == 3
    assert plan.truncated
    assert len(plan.included) + len(plan.omitted) + len(plan.empty) == len(dw.tables())
    assert not set(plan.included) & set(plan.omitted)


def test_a_withheld_workbook_names_what_it_is_missing() -> None:
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
    plan = dw.sheet_plan({})
    assert plan.included == ()
    assert plan.omitted == ()
    assert len(plan.empty) == len(dw.tables())
    assert not plan.truncated
