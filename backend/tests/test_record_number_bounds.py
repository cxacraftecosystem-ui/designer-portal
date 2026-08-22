"""Negative dimensions and negative money are refused by the record schemas, by name.

THE DEFECT THIS PINS. Every measurement and every money column on Product and Tool was a bare
``Decimal | None``. "-40" was a schema-valid length and a schema-valid selling price: stored on
create, stored on update, hydrated into a design-workshop stage — and only THEN refused, because the
registry fields these columns are carried into (``product.lengthCm``, ``product.costOfMaking``,
``tool.lengthCm``, ``tool.cost``) all declare ``min_value=0``. The repository was accepting a
quantity the workshop would later reject on a row the repository itself had filled in.

``yearsInUse`` was the single exception on Tool and had carried ``ge=0`` all along, which is why the
gap read as deliberate rather than missed.

WHY THIS IS A SCHEMA TEST AND NOT AN API TEST. The bound lives entirely in the Pydantic models, so
it is decidable with ``model_validate`` and nothing else — no Postgres, no app, no fixtures. That
matters here: Docker is routinely down on the machines this repository is developed on, and a bound
that can only be checked against a live database is a bound nobody checks.

WHAT THIS DOES NOT COVER. The browser half of the pair — ``min={0}`` on the inputs in ProductForm
and ToolForm — is asserted by ``frontend/e2e/record-number-bounds-unit.spec.ts``. Both halves are
required; see the ``NON_NEGATIVE_MEASURES`` note in ``app/schemas/records.py`` for why.
"""

import pytest
from pydantic import ValidationError

from app.schemas.records import (
    ProductCreate,
    ProductUpdate,
    ToolCreate,
    ToolUpdate,
)

# The columns on each model that may not go below zero. Listed here rather than derived from the
# model so that dropping a bound is a test failure and not a silently smaller loop.
PRODUCT_MEASURES = ("lengthInches", "breadthInches", "heightInches", "costOfMaking", "sellingPrice")
TOOL_MEASURES = (
    "yearsInUse",
    "height",
    "width",
    "lengthInches",
    "breadthInches",
    "thickness",
    "weight",
    "radius",
    "replacementCost",
)

# The identity columns every create model demands. A create body has to clear these before it can
# reach the number under test, or the test would pass on the wrong refusal.
PRODUCT_IDENTITY = {
    "craftName": "Ajrakh",
    "place": "Ajrakhpur",
    "artisanName": "R. Khatri",
    "productName": "Stole",
}
TOOL_IDENTITY = {
    "craftName": "Ajrakh",
    "place": "Ajrakhpur",
    "artisanName": "R. Khatri",
    "toolkitName": "Block set",
}
# ``require_location`` makes this mandatory on create, and it is not what these tests are about.
LOCATION = {"latitude": 23.24, "longitude": 69.67}


def _errors_for(exc: ValidationError, field: str) -> list[dict]:
    return [error for error in exc.errors() if error["loc"] == (field,)]


@pytest.mark.parametrize("field", PRODUCT_MEASURES)
def test_product_refuses_a_negative_on_create_and_names_the_column(field: str) -> None:
    with pytest.raises(ValidationError) as caught:
        ProductCreate.model_validate({**PRODUCT_IDENTITY, "location": LOCATION, field: "-40"})
    # The column has to be in ``loc``: a 422 that does not say WHICH box is the "silently refused"
    # experience this bound exists to avoid, and the clients render the field path.
    assert _errors_for(caught.value, field), caught.value.errors()


@pytest.mark.parametrize("field", TOOL_MEASURES)
def test_tool_refuses_a_negative_on_create_and_names_the_column(field: str) -> None:
    with pytest.raises(ValidationError) as caught:
        ToolCreate.model_validate({**TOOL_IDENTITY, "location": LOCATION, field: "-40"})
    assert _errors_for(caught.value, field), caught.value.errors()


@pytest.mark.parametrize("field", PRODUCT_MEASURES)
def test_product_refuses_a_negative_on_update_too(field: str) -> None:
    # The update half is the one that costs something — the web forms PATCH the whole payload, so a
    # row already holding a negative posts it back — and it is bounded anyway, because every client
    # that is not a browser reaches the column through this model alone.
    with pytest.raises(ValidationError) as caught:
        ProductUpdate.model_validate({field: "-0.01"})
    assert _errors_for(caught.value, field), caught.value.errors()


@pytest.mark.parametrize("field", TOOL_MEASURES)
def test_tool_refuses_a_negative_on_update_too(field: str) -> None:
    with pytest.raises(ValidationError) as caught:
        ToolUpdate.model_validate({field: "-0.01"})
    assert _errors_for(caught.value, field), caught.value.errors()


@pytest.mark.parametrize("field", PRODUCT_MEASURES)
def test_zero_and_omission_are_still_accepted_on_product(field: str) -> None:
    # Zero is a real answer — a cost of making that has not been worked out yet is entered as 0 on
    # this form — and ``ge`` and not ``gt`` is what keeps it one. Omission has to stay the "leave the
    # stored value alone" signal that ``forbid_clearing_location``'s neighbours all rely on.
    assert ProductUpdate.model_validate({field: "0"}) is not None
    assert getattr(ProductUpdate.model_validate({}), field) is None


@pytest.mark.parametrize("field", TOOL_MEASURES)
def test_zero_and_omission_are_still_accepted_on_tool(field: str) -> None:
    assert ToolUpdate.model_validate({field: "0"}) is not None
    assert getattr(ToolUpdate.model_validate({}), field) is None


def test_a_positive_measurement_is_unchanged_by_the_bound() -> None:
    # The bound must not be a coercion: the value that comes out is the value that went in.
    product = ProductUpdate.model_validate({"lengthInches": "12.75", "sellingPrice": "1400"})
    assert str(product.lengthInches) == "12.75"
    assert str(product.sellingPrice) == "1400"
    tool = ToolUpdate.model_validate({"radius": "0.5", "replacementCost": "250.00"})
    assert str(tool.radius) == "0.5"
    assert str(tool.replacementCost) == "250.00"
