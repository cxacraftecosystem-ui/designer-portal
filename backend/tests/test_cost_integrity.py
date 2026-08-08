"""Stage 17's cost sheets checked against their own line items — and the refusals.

The check exists because a cost sheet can contradict ITSELF: the six material lines add up to
₹1,650.00 and the designer typed ₹1,560.00 into the header, and that header is what the report
prints into a document a government office reads. Nothing in the registry could catch it —
`derive_value` sees one row and its own fields, never a sibling collection — so the arithmetic
lives here.

MOST OF THIS FILE IS ABOUT WHAT THE MODULE DECLINES TO SAY, for the same reason
`test_market_analysis.py` is. A sheet with no line items is not a contradiction, it is an
un-itemised sheet; a sheet with one unreadable line cannot be totalled at all. Reporting either as
a mismatch would put a false accusation next to a correct figure, and a check that cries wolf on
correct sheets gets switched off — taking the real findings with it.

The tolerance is pinned BY VALUE rather than by property, because a warning that fires at ₹0.01
and a warning that fires at ₹100 are different products, and `frontend/` and the handset have to
agree with this file about which one they are shipping.
"""

import math

import pytest

from app.services.cost_integrity import (
    COST_HEADS,
    MARGIN_TOLERANCE_POINTS,
    TOLERANCE_RUPEES,
    analyse_cost_integrity,
    cost_findings_payload,
)

# --------------------------------------------------------------------------------------
# Builders — a sheet and its lines, in the shape the stage actually stores
# --------------------------------------------------------------------------------------


def sheet(entry_id="sheet-1", **overrides):
    """One `costSheet` row. MONEY arrives as a fixed-2 STRING, which is how it is stored."""
    row = {
        "_entryId": entry_id,
        "productRef": "product-1",
        "materialCost": "1650.00",
        "labourCost": "900.00",
        "expectedPrice": "3500.00",
    }
    row.update(overrides)
    return row


def material(sheet_id="sheet-1", item="Tussar yarn", quantity="2", rate="825.00", **overrides):
    row = {
        "costSheetRef": sheet_id,
        "item": item,
        "quantity": quantity,
        "unit": "kg",
        "rate": rate,
        # `amount` is the registry's PRODUCT derivation, written on save as a fixed-2 string.
        "amount": f"{float(quantity) * float(rate):.2f}",
    }
    row.update(overrides)
    return row


def labour(sheet_id="sheet-1", task="Weaving", persons="2", days="3", rate="150.00", **overrides):
    row = {
        "costSheetRef": sheet_id,
        "task": task,
        "persons": persons,
        "days": days,
        "rate": rate,
        "amount": f"{float(persons) * float(days) * float(rate):.2f}",
    }
    row.update(overrides)
    return row


def analyse(sheets=None, materials=None, labours=None, labels=None):
    return analyse_cost_integrity(
        sheets=sheets if sheets is not None else [],
        material_lines=materials if materials is not None else [],
        labour_lines=labours if labours is not None else [],
        labels=labels,
    )


def check_of(findings, key, index=0):
    """The named check off one sheet, so a test reads as the thing it is asserting."""
    sheet_findings = findings.sheets[index]
    found = next((c for c in sheet_findings.checks if c.key == key), None)
    assert found is not None, f"no {key} check was produced"
    return found


# --------------------------------------------------------------------------------------
# The roll-up agrees, or names the gap
# --------------------------------------------------------------------------------------


def test_material_lines_that_add_up_to_the_declared_subtotal_agree():
    findings = analyse(
        [sheet(materialCost="1650.00")],
        [material(rate="825.00", quantity="2")],
    )
    check = check_of(findings, "materialCost")
    assert check.verdict == "AGREES"
    assert check.computed == 1650.0
    assert findings.warnings == []


def test_a_mistyped_subtotal_is_reported_with_both_numbers_and_the_gap():
    """The transposed-digit case: 1650 typed as 1560. The message must name all three figures."""
    findings = analyse(
        [sheet(materialCost="1560.00")],
        [material(rate="825.00", quantity="2")],
    )
    check = check_of(findings, "materialCost")
    assert check.verdict == "MISMATCH"
    assert check.declared == 1560.0
    assert check.computed == 1650.0
    assert check.difference == pytest.approx(-90.0)
    assert "1,560.00" in check.message
    assert "1,650.00" in check.message
    assert "90.00" in check.message
    assert findings.warnings


def test_a_line_added_after_the_subtotal_was_typed_is_caught():
    """The commonest form of the bug: the sheet was right, then a line was appended."""
    findings = analyse(
        [sheet(materialCost="1650.00")],
        [material(rate="825.00", quantity="2"), material(item="Dye", quantity="1", rate="200.00")],
    )
    check = check_of(findings, "materialCost")
    assert check.verdict == "MISMATCH"
    assert check.computed == 1850.0
    assert check.line_count == 2


def test_labour_lines_are_rolled_up_against_their_own_subtotal():
    findings = analyse(
        [sheet(labourCost="800.00")],
        [],
        [labour(persons="2", days="3", rate="150.00")],   # 900.00
    )
    check = check_of(findings, "labourCost")
    assert check.verdict == "MISMATCH"
    assert check.computed == 900.0
    assert "900.00" in check.message


def test_material_and_labour_are_checked_independently():
    """A correct labour subtotal must not be dragged into a material mismatch, or vice versa."""
    findings = analyse(
        [sheet(materialCost="1.00", labourCost="900.00")],
        [material(rate="825.00", quantity="2")],
        [labour(persons="2", days="3", rate="150.00")],
    )
    assert check_of(findings, "materialCost").verdict == "MISMATCH"
    assert check_of(findings, "labourCost").verdict == "AGREES"


def test_lines_are_matched_to_their_own_sheet_by_entry_id():
    """Two sheets, and each one's lines belong to it alone."""
    findings = analyse(
        [sheet("a", materialCost="1650.00"), sheet("b", materialCost="200.00")],
        [material("a", rate="825.00", quantity="2"), material("b", item="Dye", quantity="1",
                                                              rate="200.00")],
    )
    assert check_of(findings, "materialCost", 0).verdict == "AGREES"
    assert check_of(findings, "materialCost", 1).verdict == "AGREES"
    assert findings.sheets[0].material.total == 1650.0
    assert findings.sheets[1].material.total == 200.0


# --------------------------------------------------------------------------------------
# The tolerance, pinned by value
# --------------------------------------------------------------------------------------


def test_the_tolerance_is_one_rupee():
    """Pinned for the ports. Changing it is a product decision, not a refactor."""
    assert TOLERANCE_RUPEES == 1.00


def test_a_subtotal_rounded_to_the_whole_rupee_is_not_a_contradiction():
    findings = analyse(
        [sheet(materialCost="1650.00")],
        [material(quantity="1", rate="1649.50")],
    )
    assert check_of(findings, "materialCost").verdict == "AGREES"


def test_a_difference_exactly_at_the_tolerance_still_agrees():
    findings = analyse(
        [sheet(materialCost="1650.00")],
        [material(quantity="1", rate="1649.00")],
    )
    assert check_of(findings, "materialCost").verdict == "AGREES"


def test_a_difference_just_beyond_the_tolerance_is_a_mismatch():
    findings = analyse(
        [sheet(materialCost="1650.00")],
        [material(quantity="1", rate="1648.99")],
    )
    assert check_of(findings, "materialCost").verdict == "MISMATCH"


# --------------------------------------------------------------------------------------
# The refusals — the reason this module can be trusted at all
# --------------------------------------------------------------------------------------


def test_a_sheet_with_no_line_items_is_not_reported_as_a_contradiction():
    """AN UN-ITEMISED SHEET IS NOT A WRONG SHEET. Many are typed as totals and never itemised."""
    findings = analyse([sheet(materialCost="1650.00", labourCost="900.00")], [], [])
    assert check_of(findings, "materialCost").verdict == "NOT_ITEMISED"
    assert check_of(findings, "labourCost").verdict == "NOT_ITEMISED"
    assert findings.warnings == []
    assert findings.mismatches == []


def test_an_un_itemised_sheet_says_so_rather_than_saying_nothing():
    findings = analyse([sheet(materialCost="1650.00")], [], [])
    check = check_of(findings, "materialCost")
    assert check.computed is None
    assert "no material lines" in check.message.lower()


def test_lines_with_no_declared_subtotal_report_the_total_rather_than_a_mismatch():
    """Nothing was typed to contradict. Naming the total is useful; calling it wrong is not."""
    findings = analyse(
        [sheet(materialCost=None)],
        [material(rate="825.00", quantity="2")],
    )
    check = check_of(findings, "materialCost")
    assert check.verdict == "NOT_DECLARED"
    assert check.computed == 1650.0
    assert check.declared is None
    assert "1,650.00" in check.message
    assert findings.warnings == []


def test_an_empty_string_subtotal_counts_as_not_declared():
    findings = analyse([sheet(materialCost="")], [material(rate="825.00", quantity="2")])
    assert check_of(findings, "materialCost").verdict == "NOT_DECLARED"


def test_a_line_whose_amount_cannot_be_read_stops_the_check_concluding():
    """A PARTIAL TOTAL IS A FABRICATED ACCUSATION. Refuse, and say how many lines were unreadable."""
    findings = analyse(
        [sheet(materialCost="1650.00")],
        [material(rate="825.00", quantity="2"), material(item="Dye", amount=None)],
    )
    check = check_of(findings, "materialCost")
    assert check.verdict == "INCOMPLETE"
    assert check.computed is None
    assert "1 of 2" in check.message
    assert "Dye" in check.message


@pytest.mark.parametrize("bad", ["NaN", "Infinity", "-Infinity", "abc", "", None])
def test_non_finite_and_unreadable_amounts_are_refused_rather_than_propagated(bad):
    """A NaN reaching a total prints "₹ nan" into a submitted document before anybody notices."""
    findings = analyse(
        [sheet(materialCost="1650.00")],
        [material(rate="825.00", quantity="2"), material(item="Dye", amount=bad)],
    )
    check = check_of(findings, "materialCost")
    assert check.verdict == "INCOMPLETE"
    assert check.computed is None


def test_comma_formatted_money_is_read_as_the_number_a_designer_meant():
    """The web stores MONEY through a plain text input; grouping commas survive into the row."""
    findings = analyse(
        [sheet(materialCost="1,650.00")],
        [material(amount="1,650.00", quantity="2", rate="825.00")],
    )
    assert check_of(findings, "materialCost").verdict == "AGREES"


def test_a_sheet_with_neither_lines_nor_subtotals_produces_no_warning():
    findings = analyse([sheet(materialCost=None, labourCost=None, expectedPrice=None)], [], [])
    assert findings.warnings == []
    assert findings.mismatches == []


def test_an_empty_stage_says_nothing_rather_than_failing():
    """Stage 17 is opened long before it is filled in."""
    findings = analyse([], [], [])
    assert findings.sheets == ()
    assert findings.warnings == []
    assert findings.orphans == ()
    assert findings.cautions == []


# --------------------------------------------------------------------------------------
# totalCost — a DERIVED field, so a mismatch means a stale stored value
# --------------------------------------------------------------------------------------


def test_the_six_cost_heads_match_the_registry_declaration():
    """DRIFT PIN. `totalCost` is declared SUM over six heads in `stage_definitions.py`; this
    module re-implements that sum locally so it stays pure and portable. If the registry ever
    gains or loses a head, this test fails rather than the roll-up quietly ignoring it."""
    from app.services.stage_schema import stages

    entity = next(e for s in stages() if s.key == "COSTING_MARKET_LINKAGE"
                  for e in s.entities if e.key == "costSheet")
    spec = entity.field("totalCost")
    assert spec.derived_kind == "SUM"
    assert tuple(spec.derived_from) == COST_HEADS


def test_a_stale_total_cost_is_caught_against_the_six_heads():
    findings = analyse([sheet(materialCost="1650.00", labourCost="900.00",
                              packagingCost="100.00", totalCost="2550.00")])
    check = check_of(findings, "totalCost")
    assert check.verdict == "MISMATCH"
    assert check.computed == 2650.0
    assert "2,650.00" in check.message


def test_a_current_total_cost_agrees():
    findings = analyse([sheet(materialCost="1650.00", labourCost="900.00", totalCost="2550.00")])
    assert check_of(findings, "totalCost").verdict == "AGREES"


def test_a_blank_optional_head_counts_as_zero_exactly_as_the_registry_sums_it():
    """`derive_value`'s SUM treats a blank as zero — four of the six heads are optional."""
    findings = analyse([sheet(materialCost="1650.00", labourCost="900.00",
                              packagingCost="", transportCost=None, totalCost="2550.00")])
    assert check_of(findings, "totalCost").verdict == "AGREES"


def test_a_sheet_with_no_heads_at_all_has_no_total_to_check():
    """"₹ 0.00" in a cost sheet a ministry reads is a claim, not a blank — the registry's words."""
    findings = analyse([sheet(materialCost=None, labourCost=None, totalCost=None)])
    check = check_of(findings, "totalCost")
    assert check.verdict == "NOT_ITEMISED"
    assert check.computed is None


def test_an_unreadable_cost_head_stops_the_total_concluding():
    findings = analyse([sheet(materialCost="1650.00", labourCost="abc", totalCost="2550.00")])
    check = check_of(findings, "totalCost")
    assert check.verdict == "INCOMPLETE"
    assert "Labour cost" in check.message


def test_a_total_cost_never_stored_is_reported_rather_than_flagged():
    findings = analyse([sheet(materialCost="1650.00", labourCost="900.00", totalCost=None)])
    check = check_of(findings, "totalCost")
    assert check.verdict == "NOT_DECLARED"
    assert check.computed == 2550.0


# --------------------------------------------------------------------------------------
# Margin — computed and reported, and the loss-making sheet named
# --------------------------------------------------------------------------------------


def test_a_price_below_cost_is_named_as_selling_at_a_loss():
    findings = analyse([sheet(materialCost="1650.00", labourCost="900.00",
                              expectedPrice="2000.00")])
    margin = findings.sheets[0].margin
    assert margin.verdict == "BELOW_COST"
    assert margin.amount == pytest.approx(-550.0)
    assert "2,000.00" in margin.message
    assert "2,550.00" in margin.message
    assert any("loss" in w.lower() for w in findings.warnings)


def test_the_implied_margin_is_computed_on_cost_and_reported():
    findings = analyse([sheet(materialCost="1000.00", labourCost="0.00",
                              expectedPrice="1250.00")])
    margin = findings.sheets[0].margin
    assert margin.verdict == "COMPUTED"
    assert margin.amount == pytest.approx(250.0)
    assert margin.percent == pytest.approx(25.0)      # on cost, not on price
    assert "25.0%" in margin.message


def test_a_price_equal_to_cost_is_named_as_no_margin_rather_than_a_loss():
    findings = analyse([sheet(materialCost="1000.00", labourCost=None,
                              expectedPrice="1000.00")])
    margin = findings.sheets[0].margin
    assert margin.verdict == "AT_COST"
    assert margin.percent == pytest.approx(0.0)


def test_a_zero_total_cost_yields_no_margin_rather_than_a_division_error():
    findings = analyse([sheet(materialCost="0.00", labourCost="0.00", expectedPrice="500.00")])
    margin = findings.sheets[0].margin
    assert margin.verdict == "NOT_COMPUTABLE"
    assert margin.percent is None


def test_a_missing_expected_price_yields_no_margin():
    findings = analyse([sheet(materialCost="1650.00", expectedPrice=None)])
    assert findings.sheets[0].margin.verdict == "NOT_COMPUTABLE"
    assert findings.sheets[0].margin.percent is None


def test_a_missing_cost_yields_no_margin():
    findings = analyse([sheet(materialCost=None, labourCost=None, expectedPrice="500.00")])
    assert findings.sheets[0].margin.verdict == "NOT_COMPUTABLE"


def test_a_declared_margin_contradicting_the_implied_one_is_reported():
    """`marginPercent` is a field a designer types. Nothing checked it against the sheet before."""
    findings = analyse([sheet(materialCost="1000.00", labourCost="0.00",
                              expectedPrice="1250.00", marginPercent=40)])
    check = check_of(findings, "marginPercent")
    assert check.verdict == "MISMATCH"
    assert check.declared == pytest.approx(40.0)
    assert check.computed == pytest.approx(25.0)
    assert check.unit == "PCT"
    assert "40.0%" in check.message and "25.0%" in check.message


def test_a_declared_margin_within_a_point_of_the_implied_one_agrees():
    findings = analyse([sheet(materialCost="1000.00", labourCost="0.00",
                              expectedPrice="1250.00", marginPercent=25.5)])
    assert check_of(findings, "marginPercent").verdict == "AGREES"


def test_the_margin_tolerance_is_one_percentage_point():
    assert MARGIN_TOLERANCE_POINTS == 1.0


def test_no_declared_margin_is_not_a_finding():
    findings = analyse([sheet(materialCost="1000.00", labourCost="0.00",
                              expectedPrice="1250.00")])
    assert check_of(findings, "marginPercent").verdict == "NOT_DECLARED"
    assert findings.warnings == []


def test_a_declared_margin_with_no_implied_one_to_compare_against_is_not_a_finding():
    findings = analyse([sheet(materialCost=None, labourCost=None, expectedPrice=None,
                              marginPercent=40)])
    assert check_of(findings, "marginPercent").verdict == "NOT_COMPUTABLE"
    assert findings.warnings == []


# --------------------------------------------------------------------------------------
# Orphans — fieldwork that must never be silently dropped
# --------------------------------------------------------------------------------------


def test_a_line_naming_a_sheet_that_does_not_exist_is_reported_not_dropped():
    """The sheet was deleted after the line cited it. The line cost real money to collect."""
    findings = analyse(
        [sheet("a", materialCost="1650.00")],
        [material("a", rate="825.00", quantity="2"), material("gone", item="Dye", quantity="1",
                                                              rate="200.00")],
    )
    assert check_of(findings, "materialCost").verdict == "AGREES"     # sheet a is untouched
    assert len(findings.orphans) == 1
    orphan = findings.orphans[0]
    assert orphan.kind == "MATERIAL"
    assert orphan.label == "Dye"
    assert orphan.amount == 200.0
    assert any("Dye" in c or "1 material" in c for c in findings.cautions)


def test_a_line_naming_no_sheet_at_all_is_an_orphan_too():
    findings = analyse(
        [sheet("a", materialCost="1650.00")],
        [material("a", rate="825.00", quantity="2"), material("", item="Dye", quantity="1",
                                                              rate="200.00")],
    )
    assert len(findings.orphans) == 1
    assert findings.orphans[0].label == "Dye"


def test_an_unsynced_sheet_does_not_adopt_the_lines_that_name_no_sheet():
    """BLANK MUST NOT MATCH BLANK. A sheet that has not synced has no `_entryId`, and a line with
    no ref names nothing; pairing the two on their shared emptiness would attach fieldwork to a
    sheet at random and make the sheet's subtotal look checked when it was not."""
    findings = analyse(
        [{"materialCost": "1650.00"}],                       # no _entryId yet
        [material("", item="Dye", quantity="1", rate="200.00")],
    )
    assert check_of(findings, "materialCost").verdict == "NOT_ITEMISED"
    assert findings.sheets[0].material.count == 0
    assert len(findings.orphans) == 1
    assert findings.orphans[0].label == "Dye"


def test_orphaned_labour_lines_are_reported_with_their_own_kind():
    findings = analyse([sheet("a")], [], [labour("gone", task="Finishing")])
    assert len(findings.orphans) == 1
    assert findings.orphans[0].kind == "LABOUR"
    assert findings.orphans[0].label == "Finishing"


def test_the_orphan_caution_names_the_money_that_is_in_no_sheet():
    """An orphan's amount is missing from every total, and that may BE the discrepancy."""
    findings = analyse([sheet("a")], [material("gone", item="Dye", quantity="1", rate="200.00")])
    assert any("200.00" in c for c in findings.cautions)


def test_orphans_do_not_change_any_sheet_total():
    findings = analyse(
        [sheet("a", materialCost="1650.00")],
        [material("a", rate="825.00", quantity="2"), material("gone", quantity="9",
                                                              rate="999.00")],
    )
    assert findings.sheets[0].material.total == 1650.0
    assert findings.sheets[0].material.count == 1


def test_an_orphan_with_an_unreadable_amount_is_still_counted():
    """Counted somewhere, always — that is the whole rule about orphaned fieldwork."""
    findings = analyse([sheet("a")], [material("gone", item="Dye", amount="abc")])
    assert len(findings.orphans) == 1
    assert findings.orphans[0].amount is None


# --------------------------------------------------------------------------------------
# Labelling — a finding a designer cannot trace back is one they cannot act on
# --------------------------------------------------------------------------------------


def test_a_sheet_is_named_by_its_product_when_the_label_is_known():
    findings = analyse(
        [sheet("a", productRef="p1", materialCost="1.00")],
        [material("a", rate="825.00", quantity="2")],
        labels={"p1": "Bandha table runner"},
    )
    assert findings.sheets[0].label == "Bandha table runner"
    assert "Bandha table runner" in check_of(findings, "materialCost").message


def test_a_sheet_with_no_resolvable_product_falls_back_to_its_position():
    findings = analyse(
        [sheet("a", productRef=None, materialCost="1.00")],
        [material("a", rate="825.00", quantity="2")],
    )
    assert findings.sheets[0].label == "Cost sheet 1"
    assert "Cost sheet 1" in check_of(findings, "materialCost").message


def test_a_sheet_with_no_entry_id_still_produces_findings():
    """A row that has not synced yet has no id. Its own header is still checkable."""
    findings = analyse([{"materialCost": "1650.00", "labourCost": "900.00",
                         "totalCost": "2000.00"}])
    assert check_of(findings, "totalCost").verdict == "MISMATCH"


# --------------------------------------------------------------------------------------
# The whole thing, and the wire form
# --------------------------------------------------------------------------------------


def test_analyse_reports_every_sheet_and_gathers_the_warnings():
    findings = analyse(
        [sheet("a", materialCost="1560.00"), sheet("b", materialCost="200.00",
                                                   labourCost="0.00", expectedPrice="100.00")],
        [material("a", rate="825.00", quantity="2"), material("b", quantity="1", rate="200.00")],
    )
    assert len(findings.sheets) == 2
    assert len(findings.mismatches) >= 1
    assert findings.warnings
    assert findings.sheet_count == 2


def test_a_correct_workshop_produces_no_warnings_at_all():
    findings = analyse(
        [sheet("a", materialCost="1650.00", labourCost="900.00", totalCost="2550.00",
               expectedPrice="3500.00")],
        [material("a", rate="825.00", quantity="2")],
        [labour("a", persons="2", days="3", rate="150.00")],
    )
    assert findings.warnings == []
    assert findings.mismatches == []
    assert findings.sheets[0].margin.verdict == "COMPUTED"


def test_the_payload_is_camel_cased_and_json_safe():
    import json

    findings = analyse(
        [sheet("a", materialCost="1560.00", marginPercent=40)],
        [material("a", rate="825.00", quantity="2")],
        [labour("gone", task="Finishing")],
        labels={"product-1": "Bandha table runner"},
    )
    payload = cost_findings_payload(findings)
    text = json.dumps(payload)                      # raises on a NaN or a dataclass
    assert "sheetCount" in payload
    assert payload["warnings"]
    assert payload["sheets"][0]["label"] == "Bandha table runner"
    assert payload["sheets"][0]["checks"][0]["verdict"]
    assert payload["orphans"][0]["kind"] == "LABOUR"
    assert "NaN" not in text and "Infinity" not in text


def test_a_line_links_to_its_sheet_through_the_real_stage_payload_shape():
    """THE CONVENTION THE WHOLE MODULE RESTS ON, pinned against the code that produces it.

    `costSheetRef` holds the parent's `_entryId`, and `_entryId` is the row's DATABASE ID injected
    by the caller — it is not part of the stored `data` at all. A caller that forgets it, or a
    rename of that key, turns every line into an orphan of a sheet sitting directly above it and
    the endpoint quietly reports nothing wrong with a sheet that is wrong. This fails instead.
    """
    from types import SimpleNamespace

    from app.api.routes.design_workshops import _stages_payload

    rows = [
        SimpleNamespace(id="sheet-db-1", ordinal=1, clientKey=None,
                        stageKey="COSTING_MARKET_LINKAGE", entityKey="costSheet",
                        data={"productRef": "p1", "materialCost": "1560.00"}),
        SimpleNamespace(id="line-db-1", ordinal=1, clientKey=None,
                        stageKey="COSTING_MARKET_LINKAGE", entityKey="costMaterialLine",
                        data={"costSheetRef": "sheet-db-1", "item": "Tussar yarn",
                              "amount": "1650.00"}),
    ]
    collections = _stages_payload(rows)["COSTING_MARKET_LINKAGE"]["collections"]

    findings = analyse_cost_integrity(
        sheets=collections["costSheet"],
        material_lines=collections["costMaterialLine"],
        labour_lines=[],
        labels={"p1": "Bandha table runner"},
    )
    check = check_of(findings, "materialCost")
    assert check.verdict == "MISMATCH"
    assert check.computed == 1650.0
    assert findings.orphans == ()


def test_every_reported_number_is_finite():
    """A non-finite reaching the payload is `₹ nan` in a submitted document."""
    findings = analyse(
        [sheet("a", materialCost="1e400", expectedPrice="NaN")],
        [material("a", rate="825.00", quantity="2")],
    )
    payload = cost_findings_payload(findings)

    def walk(node):
        if isinstance(node, dict):
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
        elif isinstance(node, float):
            assert math.isfinite(node)

    walk(payload)
