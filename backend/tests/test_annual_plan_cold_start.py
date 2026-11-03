from __future__ import annotations

import re
from pathlib import Path

import pytest

FRONTEND = Path(__file__).resolve().parents[2] / "frontend"
PLAN = FRONTEND / "app" / "(protected)" / "annual-plan"

PAGE = PLAN / "page.tsx"
UPLOAD_DIALOG = PLAN / "UploadPlanDialog.tsx"
PROMOTE_DIALOG = PLAN / "PromoteDialog.tsx"
UPLOAD_REPORT = PLAN / "PlanUploadReport.tsx"

RENDERERS = [PAGE, UPLOAD_DIALOG, PROMOTE_DIALOG, UPLOAD_REPORT]


def _read(path: Path) -> str:
    assert path.exists(), f"{path} is missing"
    return path.read_text(encoding="utf-8")


def _code(path: Path) -> str:
    source = _read(path)
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return "\n".join(line for line in source.split("\n") if not line.lstrip().startswith("//"))


def _jsx(source: str, text: str, opener: str = "<button") -> str:
    end = source.index(text)
    return source[source.rindex(opener, 0, end) : end]



def test_the_upload_control_is_not_gated_on_the_plan_year():
    button = _jsx(_code(PAGE), "Upload the plan")
    assert "disabled" not in button, button


def test_the_export_control_is_still_gated_on_the_plan_year():
    button = _jsx(_code(PAGE), "Export this list")
    assert "disabled={planYear == null}" in button, button


def test_the_upload_dialog_is_mounted_whether_or_not_a_year_is_known():
    source = _code(PAGE)
    assert "{planYear != null ? (" not in source
    assert "<UploadPlanDialog" in source


def test_the_dialog_is_never_handed_the_string_null_as_a_year_label():
    source = _code(PAGE)
    assert "planYearLabel={yearLabel}" in source
    assert "yearLabel || String(planYear)" not in source


def test_the_upload_dialog_accepts_a_missing_year():
    assert "planYear: number | null;" in _code(UPLOAD_DIALOG)


def test_the_upload_call_turns_a_missing_year_into_absence_and_not_into_null():
    source = _code(UPLOAD_DIALOG)
    assert "planYear: planYear ?? undefined" in source
    assert "{ planYear, withdrawAbsent }" not in source


def test_the_dialog_title_does_not_name_a_year_it_does_not_have():
    source = _code(UPLOAD_DIALOG)
    assert 'planYear == null ? "Upload the annual plan"' in source


def test_the_upload_lands_on_the_year_it_has_just_created():
    source = _code(PAGE)
    handler = source[source.index("<UploadPlanDialog") : source.index("<PromoteDialog")]
    assert "setPlanYear((current) => current ?? uploaded.planYear);" in handler, handler
    assert (
        handler.index("setPlanYear(")
        < handler.index("void refreshYears()")
        < handler.index("void refreshRows()")
    ), handler



def test_the_empty_directory_has_a_state_of_its_own_above_the_loading_one():
    source = _code(PAGE)
    assert source.index('title="No plan has been uploaded yet"') < source.index("rows == null ?")


def test_the_empty_directory_branch_waits_for_the_years_read_and_for_the_upload():
    source = _code(PAGE)
    branch = source[source.index("{planYear == null") : source.index("rows == null ?")]
    for clause in ("planYear == null", "years != null", "years.length === 0"):
        assert clause in branch, branch



def test_the_promote_dialog_offers_a_designer_picker():
    source = _code(PROMOTE_DIALOG)
    assert "<WorkshopDesignerPicker" in source
    assert "fetchEligible={listAssignableDesigners}" in source


def test_the_promote_dialog_reads_the_door_its_own_audience_may_pass():
    source = _code(PROMOTE_DIALOG)
    assert "listAssignableDesigners" in source
    assert "listEligibleDesignWorkshopViewers" not in source


def test_the_promote_body_is_built_by_the_rule_the_create_form_uses():
    source = _code(PROMOTE_DIALOG)
    assert "designerCreateFields({ chosen, lead })" in source
    assert "promoteAnnualPlanEntry(entry.id, {})" not in source


def test_the_sentence_on_screen_is_decided_by_the_rule_the_wire_is_decided_by():
    source = _code(PROMOTE_DIALOG)
    assert "namedDesignerTeam({ chosen, lead })" in source
    assert "chosen.length === 0" not in source, source


@pytest.mark.parametrize(
    "stale",
    [
        "WHY THERE IS NO DESIGNER PICKER ON THIS DIALOG",
        "this dialog deliberately sends neither",
    ],
)
def test_the_header_no_longer_argues_against_the_control_beneath_it(stale: str):
    assert stale not in _read(PROMOTE_DIALOG)



def _class_strings(path: Path) -> list[str]:
    return re.findall(r'className="([^"]*)"', _code(path))


@pytest.mark.parametrize("path", RENDERERS, ids=lambda p: p.name)
def test_every_ministry_class_carries_its_dark_pair(path: Path):
    for classes in _class_strings(path):
        tokens = classes.split()
        if "text-ministry-700" in tokens:
            assert "dark:text-ministry-300" in tokens, classes
        if "bg-ministry-50" in tokens:
            assert "dark:bg-ministry-950/40" in tokens, classes


def test_the_purple_left_in_this_parcel_is_the_sort_toggle_and_the_switch_stays_purple():
    purple = {
        path.name: [c for c in _class_strings(path) if any("purple" in t for t in c.split())]
        for path in RENDERERS
    }
    assert purple == {
        "page.tsx": [
            "mt-1 justify-self-start text-xs font-medium text-purple-700 underline dark:text-purple-300"
        ],
        "UploadPlanDialog.tsx": [],
        "PromoteDialog.tsx": [],
        "PlanUploadReport.tsx": [],
    }, purple


def test_the_ramp_is_chosen_by_what_the_element_does_not_by_the_file_it_is_in():
    source = _code(PAGE)
    toggle = _jsx(source, "Oldest first")
    link = _jsx(source, "Open the workshop", opener="<Link")

    assert "text-purple-700" in toggle and "dark:text-purple-300" in toggle, toggle
    assert "ministry" not in toggle, toggle

    assert "text-ministry-700" in link and "dark:text-ministry-300" in link, link
    assert "purple" not in link, link
