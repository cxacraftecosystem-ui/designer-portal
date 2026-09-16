"""THE COLD-START DEADLOCK, PINNED SO IT CANNOT COME BACK. Read off disk: no database, no browser.

══ WHAT THE DEFECT WAS ══════════════════════════════════════════════════════════════════════════

`/annual-plan` was 100% unusable on every fresh environment, and one `null` caused all three of its
symptoms. `GET /annual-plan/years` answers ``200 []`` over an empty ``AnnualPlanEntry`` table, so
``planYear`` stayed null; the ``query`` memo was therefore null; ``refreshRows`` returned at its
guard ``if (!query) return;`` BEFORE the generation counter and before any setter, so ``rows`` was
null for ever and the list said "Loading…" permanently — directly beneath a select already reading
"No plan uploaded yet". The same null disabled "Upload the plan" and unmounted ``UploadPlanDialog``
altogether. The page needed a plan before it could be used, and a plan could only arrive through the
control it disabled while none existed.

**No test covered any of it.** ``test_annual_plan_web_surface.py`` reaches the same page and asserts
only that it starts with ``"use client"``; nothing in ``tests/`` reads a ``disabled`` attribute, and
a browser spec would need a session and an empty database to see it at all. So this file pins the
repair instead — every line of it, including the two lines that deliberately did NOT change.

══ WHY A PYTHON TEST READS TYPESCRIPT ═══════════════════════════════════════════════════════════

The same reason its sibling gives, verbatim: ``frontend/e2e/`` is owned by other work in this wave,
``backend/tests/test_role_ladder_parity.py`` already reads both client trees off disk, and the
assertions are worth more than their filename. **When it moves, it is a move and not a rewrite.**

══ WHY THE PROMOTE PICKER IS ALSO IN A FILE CALLED "COLD START" ══════════════════════════════════

Because the parcel that landed both owns exactly one new test file, and a second one would be a
second file to argue about. The designer picker on ``PromoteDialog`` shipped in the same change and
has the same shape of risk — a constraint that moved, recorded in prose that a later reader could
undo. Its section is marked below.

══ THE ONE THING THIS FILE HAS TO GET RIGHT ═════════════════════════════════════════════════════

**Comments are stripped before anything is asserted about the CODE.** These files quote the defect
they replaced — ``{planYear != null ? … : null}`` and ``yearLabel || String(planYear)`` both appear
verbatim in the comments that explain why they are gone — which is exactly the house style and
exactly what makes a naïve substring test read a prose warning as the bug's return. :func:`_code`
removes block comments and whole-line ``//`` comments; the handful of assertions that are genuinely
ABOUT the prose read the raw source and say so.
"""

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

#: Every file the parcel owns that renders something, for the sweeps at the end.
RENDERERS = [PAGE, UPLOAD_DIALOG, PROMOTE_DIALOG, UPLOAD_REPORT]


def _read(path: Path) -> str:
    assert path.exists(), f"{path} is missing"
    return path.read_text(encoding="utf-8")


def _code(path: Path) -> str:
    """The file with its comments removed — see this module's last section for why that matters.

    Block comments first (which also disposes of the JSX ``{/* … */}`` form, leaving an inert
    ``{}``), then any line whose first non-space characters are ``//``. Trailing ``//`` is left
    alone deliberately: stripping it would need a string-aware scanner, and every comment this
    parcel writes in attribute position is already on a line of its own.
    """
    source = _read(path)
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return "\n".join(line for line in source.split("\n") if not line.lstrip().startswith("//"))


def _jsx(source: str, text: str, opener: str = "<button") -> str:
    """The element that encloses ``text``, from its opening tag up to ``text`` itself."""
    end = source.index(text)
    return source[source.rindex(opener, 0, end) : end]


# ══════════════════════════════════════════════════════════════════════════════════════════════
# FIX A — the deadlock
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_upload_control_is_not_gated_on_the_plan_year():
    """THE DEADLOCK ITSELF. ``disabled={planYear == null}`` on this button is the whole bug.

    Nothing is ungated by its absence. Who may upload is ``canManageAnnualPlan``, enforced above the
    page by ``AppShell``'s ``ROUTE_GUARDS`` row and again on the route by
    ``require_annual_plan_manager`` — the page rendering at all is the proof that both passed. And
    the year is not needed on the wire either: ``upload_annual_plan`` resolves it as
    ``typed if typed is not None else parsed.planYear``, off the workbook's own Details sheet, and
    the blank pro-forma ships that cell empty for the administrator to fill in.
    """
    button = _jsx(_code(PAGE), "Upload the plan")
    assert "disabled" not in button, button


def test_the_export_control_is_still_gated_on_the_plan_year():
    """THE COUNTER-ASSERTION, and it is the more likely thing to be got wrong.

    The two buttons sit side by side and look identical, so "finishing the job" by deleting both
    ``disabled`` attributes is the obvious next move and it is wrong. ``exportQuery`` is null while
    there is no year and ``download("export")`` already no-ops on a null query, so an ungated Export
    is a control that silently does nothing over a directory that genuinely holds nothing to export.
    """
    button = _jsx(_code(PAGE), "Export this list")
    assert "disabled={planYear == null}" in button, button


def test_the_upload_dialog_is_mounted_whether_or_not_a_year_is_known():
    """The second half of the deadlock: with no year the dialog did not exist at all.

    ``setUploadOpen(true)`` then flipped a state nothing read, so re-enabling the button on its own
    would have produced a control that looked broken in a new way. ``FieldDialog`` renders nothing
    while ``open`` is false, so an always-mounted dialog costs a closed portal and no more.
    """
    source = _code(PAGE)
    assert "{planYear != null ? (" not in source
    assert "<UploadPlanDialog" in source


def test_the_dialog_is_never_handed_the_string_null_as_a_year_label():
    """``yearLabel || String(planYear)`` is ``"" || "null"`` — literally ``"null"``.

    Harmless only for as long as the dialog could not mount without a year. The moment it can, that
    expression puts **"Upload the null plan"** in the title of a ministry administrator's dialog,
    because the title is built from this prop. ``yearLabel`` is already ``""`` in exactly that case,
    and the dialog branches on ``planYear`` for its title rather than on the label being falsy.
    """
    source = _code(PAGE)
    assert "planYearLabel={yearLabel}" in source
    assert "yearLabel || String(planYear)" not in source


def test_the_upload_dialog_accepts_a_missing_year():
    """``planYear: number | null`` — null is the cold start, not a broken caller."""
    assert "planYear: number | null;" in _code(UPLOAD_DIALOG)


def test_the_upload_call_turns_a_missing_year_into_absence_and_not_into_null():
    """⚠ THE ONE LINE THAT DOES NOT COMPILE IF IT IS WRITTEN THE OBVIOUS WAY.

    ``uploadAnnualPlan``'s option is ``planYear?: number`` and its body already skips the append when
    the value is nullish, so at RUNTIME ``null`` would have been fine. The TYPE refuses it:
    ``frontend/tsconfig.json`` is ``"strict": true`` with **no** ``exactOptionalPropertyTypes``, and
    under plain ``strict`` an optional property accepts ``number | undefined`` — ``null`` is not
    assignable to it. Verified by reverting this one expression and running ``tsc``:

        app/(protected)/annual-plan/UploadPlanDialog.tsx(121,55): error TS2322:
        Type 'number | null' is not assignable to type 'number | undefined'.

    So widening the prop above without this line does not build. ``?? 0`` is not the alternative
    either: ``_plan_year_or_422`` reads ``0`` as a year and refuses it with a bounds message about a
    number the administrator never typed.
    """
    source = _code(UPLOAD_DIALOG)
    assert "planYear: planYear ?? undefined" in source
    assert "{ planYear, withdrawAbsent }" not in source


def test_the_dialog_title_does_not_name_a_year_it_does_not_have():
    """ "Upload the annual plan" with no year, and only then."""
    source = _code(UPLOAD_DIALOG)
    assert 'planYear == null ? "Upload the annual plan"' in source


def test_the_upload_lands_on_the_year_it_has_just_created():
    """``void refreshRows()`` is a NO-OP on the cold-start path and looks like the repair.

    It closes over the render's ``query``, which is still null, so it returns at the same guard the
    whole defect turns on. The screen does self-heal one commit later — ``refreshYears`` sets the
    year, ``query`` changes, ``refreshRows``' identity changes and its effect refires — but a repair
    that depends on a chain nobody can see is a repair that gets deleted as dead code. The explicit
    ``setPlanYear`` has to come FIRST, and ``current ??`` is what stops a corrected sheet for an
    older year yanking a reader off the year they were looking at.
    """
    source = _code(PAGE)
    handler = source[source.index("<UploadPlanDialog") : source.index("<PromoteDialog")]
    assert "setPlanYear((current) => current ?? uploaded.planYear);" in handler, handler
    assert (
        handler.index("setPlanYear(")
        < handler.index("void refreshYears()")
        < handler.index("void refreshRows()")
    ), handler


# ══════════════════════════════════════════════════════════════════════════════════════════════
# FIX B — the two states that contradicted each other on screen
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_empty_directory_has_a_state_of_its_own_above_the_loading_one():
    """ORDER IS THE WHOLE ASSERTION. Below the ``rows == null`` test it would be shadowed.

    That is not hypothetical: it is precisely what happened to "Nothing in this year's plan yet",
    which was unreachable on a cold table for exactly this reason. ``rows === null`` means BOTH "a
    request is in flight" and "no request was ever made", and only the first reading was ever drawn.
    """
    source = _code(PAGE)
    assert source.index('title="No plan has been uploaded yet"') < source.index("rows == null ?")


def test_the_empty_directory_branch_waits_for_the_years_read_and_for_the_upload():
    """All three clauses, each stopping a different lie.

    ``years != null`` keeps the honest "Loading…" while the years read is genuinely outstanding.
    ``years.length === 0`` is the directory holding no year at all. ``planYear == null`` is what
    stops this branch drawing "No plan has been uploaded yet" for the commit or two after an upload
    lands — directly under the report panel that has just said one was read.
    """
    source = _code(PAGE)
    branch = source[source.index("{planYear == null") : source.index("rows == null ?")]
    for clause in ("planYear == null", "years != null", "years.length === 0"):
        assert clause in branch, branch


# ══════════════════════════════════════════════════════════════════════════════════════════════
# F1 — the designer picker on PromoteDialog, and the constraint that moved
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_promote_dialog_offers_a_designer_picker():
    """One control, three audiences, three doors — not a sibling control with its own copy of four
    pieces of machinery that have each already shipped as a bug once."""
    source = _code(PROMOTE_DIALOG)
    assert "<WorkshopDesignerPicker" in source
    assert "fetchEligible={listAssignableDesigners}" in source


def test_the_promote_dialog_reads_the_door_its_own_audience_may_pass():
    """``GET /design-workshop-oversight/designers``, and not ``/design-workshops/eligible-viewers``.

    The second is ``Depends(require_admin)`` — SET membership ``{ADMIN, MASTER_ADMIN}`` — and a
    MINISTRY_ADMIN, the tier this whole directory exists for, is refused by it. Importing the
    picker's DEFAULT door here would put a picker that 403s on the one screen built for them.
    """
    source = _code(PROMOTE_DIALOG)
    assert "listAssignableDesigners" in source
    assert "listEligibleDesignWorkshopViewers" not in source


def test_the_promote_body_is_built_by_the_rule_the_create_form_uses():
    """``designerCreateFields`` decides which of the two keys goes out — nobody → neither, one
    designer → ``designerUserId`` alone, several → both. Spelled once, in one place, or the two
    doors onto one creation path come to disagree."""
    source = _code(PROMOTE_DIALOG)
    assert "designerCreateFields({ chosen, lead })" in source
    assert "promoteAnnualPlanEntry(entry.id, {})" not in source


def test_the_sentence_on_screen_is_decided_by_the_rule_the_wire_is_decided_by():
    """⚠ ``chosen.length === 0`` IS NOT "NOBODY IS NAMED", AND THE GAP IS REACHABLE IN FOUR PRESSES.

    ``namedDesignerTeam``'s second rule is that a lead standing alone with nothing ticked IS the
    team — deliberately, so a draft written before the picker was a multi-select does not lose the
    designer it was opened for. The picker's lead chooser appears from two ticks upward, so: tick
    two, choose a lead, untick both. ``chosen`` is empty and ``lead`` still holds an id, so
    ``designerCreateFields`` sends ``designerUserId`` for it, the server grants that account a viewer
    row and seeds stage 1 with their profile — while a paragraph branching on ``chosen.length``
    answers "**No designer is named** — the designer block of stage 1 is left empty", directly under
    a panel already printing "Stage 1, stage 3 and the report will carry <their name>".

    ONE RESOLVER, ONE ANSWER. ``WorkshopDesignerPicker`` computes its own lead line through this
    same function and says so where it does; a screen deciding by a different expression from the
    wire can only agree with it by coincidence, about whose name a ministry reads off the report.
    """
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
    """READS THE RAW SOURCE, COMMENTS AND ALL, because the prose IS the subject.

    This block was cited as binding precedent by two other features, so it was rewritten rather than
    deleted: the endpoint fact in it is still true (``eligible-viewers`` really is ``require_admin``)
    and still binds for an Assistant Director, who is outside BOTH doors. What changed is that a
    second door landed on a different prefix and the picker grew ``fetchEligible`` — which is the one
    shape the old comment's own escape hatch ("the day ``eligible-viewers`` grows a gate a ministry
    administrator passes") did not anticipate. A comment left arguing against the code under it is a
    defect in this repository, not a nit.
    """
    assert stale not in _read(PROMOTE_DIALOG)


# ══════════════════════════════════════════════════════════════════════════════════════════════
# THE MINISTRY SURFACE — the swaps this parcel applied, and the one it deliberately did not
# ══════════════════════════════════════════════════════════════════════════════════════════════


def _class_strings(path: Path) -> list[str]:
    """Every ``className="…"`` literal in the file, comments already removed."""
    return re.findall(r'className="([^"]*)"', _code(path))


@pytest.mark.parametrize("path", RENDERERS, ids=lambda p: p.name)
def test_every_ministry_class_carries_its_dark_pair(path: Path):
    """⚠ NOT OPTIONAL POLISH. The ministry ramp is literal and does not invert, exactly as purple
    does not, so a bare ``text-ministry-700`` measures **2.44:1** on a dark card and
    ``bg-ministry-50`` stays a near-white peach in both themes. The pairs take them to 10.06:1.

    Asserted per class STRING rather than per file, because the pair has to travel on the element
    that carries the accent — a ``dark:`` utility somewhere else in the file repaints nothing.
    """
    for classes in _class_strings(path):
        tokens = classes.split()
        if "text-ministry-700" in tokens:
            assert "dark:text-ministry-300" in tokens, classes
        if "bg-ministry-50" in tokens:
            assert "dark:bg-ministry-950/40" in tokens, classes


def test_the_purple_left_in_this_parcel_is_the_checkbox_accent_and_the_sort_toggle():
    """BOTH ARE THERE ON PURPOSE — the two places the hand-off table was NOT followed.

    ⚠ THIS EXPECTATION WAS WIDENED IN 0.0.12, DELIBERATELY, AND IT IS THE TEST THAT WAS WRONG.
    It read ``"page.tsx": []``, i.e. it required the ministry swap on the one element in that file
    the rule it quotes in its own next paragraph forbids swapping. ``page.tsx`` was never out of
    step with the re-theme: its single purple site is the sort-direction toggle at :364-388, a
    ``<button type="button">`` whose ``onClick`` flips ``dir`` and resets ``page``. That is a
    control, and three separate places in this repository already say a control stays purple:

    * ``frontend/tailwind.config.ts`` annotates the rung itself — ``#923e0d — surface accent, never
      an action colour`` — and says it in words above the ramp: "a ministry page's buttons and
      inputs are the same purple as every other page's".
    * ``frontend/app/globals.css`` heads the scoped ministry block "SURFACE ACCENT ONLY. NO ORANGE
      ON ANY ACTION CONTROL", with ``.field-button``, ``.field-input``, the focus ring and
      ``shadow-cta`` listed as deliberately absent from it.
    * the swap was not MISSED on this element, it was refused ON it. The same change that moved the
      ``<Link>`` further down this file onto ``text-ministry-700 dark:text-ministry-300`` left the
      button purple and ADDED ``dark:text-purple-300`` to it — the dark-mode half of the re-theme,
      kept because ``text-purple-700`` on a dark ``bg-card`` really is 2.32:1, while the hue was
      not. Both elements carry the paragraph that says so, and the ``<Link>``'s names this button as
      the other side of the line it is on.

    So the inventory has two sanctioned entries rather than one, and both are controls:

    ``accent-purple-700`` on the upload dialog's checkbox is a native input's CHECKED FILL, i.e. an
    input's action colour, and it is the most dangerous control on that dialog. An orange one would
    land ΔE 0.004 from the ``amber-800`` warning triangle that appears two lines below the moment it
    is ticked — the same colour to the eye, saying the opposite thing.

    ``text-purple-700`` on the sort toggle is that same arithmetic one table further down.
    ``STANDING.WITHDRAWN`` draws ``bg-amber-100 text-amber-800`` in the standing column of every
    withdrawn row beneath this control, so an orange toggle would have put a thing to PRESS and a
    fact about a planned workshop in indistinguishable ink a few inches apart — and it would have
    been the only orange-inked control in the product.

    WHY WIDEN RATHER THAN DROP THE ASSERTION, which was the other way to make this green. The dict
    stays exact, class STRING for class string, because the inventory IS the value of this test: a
    ``"purple" in c`` subset check, or an expectation that merely allowed page.tsx "some" purple,
    would let purple creep back onto the panel ground, the header chip and the desk tiles — which is
    exactly what the re-theme did swap and what nothing else in this parcel pins. Exactness also
    pins the ``dark:`` pairs for free: delete ``dark:text-purple-300`` from the toggle and the string
    stops matching, which is the guarantee ``test_every_ministry_class_carries_its_dark_pair`` gives
    the ministry sites and the reason both of these purple sites were safe to sanction.

    If this goes red because somebody "finished" the swap on either of the two, the answer is to put
    it back and read the paragraph above the element. If it goes red because a THIRD purple site
    appeared, the bar is the one above: purple only if the reader is meant to act on it.
    """
    # ONE ENTRY PER ELEMENT. This comprehension used to read ``for c in _class_strings(path) for
    # token in c.split() if "purple" in token``, which yields the class string once PER PURPLE
    # TOKEN — so the sort toggle, carrying both ``text-purple-700`` and its ``dark:`` pair, was
    # listed twice and the failure output read as two separate elements to go and fix, which is a
    # false lead about the size of the job. Same filter, same whole-string comparison; the count is
    # now the count of sites.
    purple = {
        path.name: [c for c in _class_strings(path) if any("purple" in t for t in c.split())]
        for path in RENDERERS
    }
    assert purple == {
        "page.tsx": [
            "mt-1 justify-self-start text-xs font-medium text-purple-700 underline dark:text-purple-300"
        ],
        "UploadPlanDialog.tsx": ["mt-1 h-4 w-4 shrink-0 accent-purple-700"],
        "PromoteDialog.tsx": [],
        "PlanUploadReport.tsx": [],
    }, purple


def test_the_ramp_is_chosen_by_what_the_element_does_not_by_the_file_it_is_in():
    """THE COUNTER-ASSERTION, and it is the half a later reader is likelier to undo.

    These two elements sit in one file, are both small underlined text, and are drawn in different
    ramps. That looks like an oversight from either end, so both "tidying" moves are one edit away:
    finish the swap on the toggle, or pull the ``<Link>`` back to purple to match the toggle. Each
    would be wrong, and neither is caught by the inventory above on its own — that test would still
    pass with the ``<Link>`` on purple if somebody updated its expectation to suit.

    THE LINE IS NOT PER FILE, IT IS PER ELEMENT: the ``<Link>`` is body copy inside a table cell
    that navigates to the workshop a row became, so it takes the surface it is printed on; the
    toggle is pressed and changes the list, so it keeps the one action colour. Both carry the
    ``dark:`` pair, for the same reason and to the same rung — the ministry ramp is purple's
    lightness ladder rung for rung, so ``-300`` in dark is the identical correction on both.
    """
    source = _code(PAGE)
    toggle = _jsx(source, "Oldest first")
    link = _jsx(source, "Open the workshop", opener="<Link")

    assert "text-purple-700" in toggle and "dark:text-purple-300" in toggle, toggle
    assert "ministry" not in toggle, toggle

    assert "text-ministry-700" in link and "dark:text-ministry-300" in link, link
    assert "purple" not in link, link
