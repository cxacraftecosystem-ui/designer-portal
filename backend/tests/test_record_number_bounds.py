import pytest
from pydantic import ValidationError

from app.schemas.records import (
    ProductCreate,
    ProductUpdate,
    ToolCreate,
    ToolUpdate,
)

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
LOCATION = {"latitude": 23.24, "longitude": 69.67}


def _errors_for(exc: ValidationError, field: str) -> list[dict]:
    return [error for error in exc.errors() if error["loc"] == (field,)]


@pytest.mark.parametrize("field", PRODUCT_MEASURES)
def test_product_refuses_a_negative_on_create_and_names_the_column(field: str) -> None:
    with pytest.raises(ValidationError) as caught:
        ProductCreate.model_validate({**PRODUCT_IDENTITY, "location": LOCATION, field: "-40"})
    assert _errors_for(caught.value, field), caught.value.errors()


@pytest.mark.parametrize("field", TOOL_MEASURES)
def test_tool_refuses_a_negative_on_create_and_names_the_column(field: str) -> None:
    with pytest.raises(ValidationError) as caught:
        ToolCreate.model_validate({**TOOL_IDENTITY, "location": LOCATION, field: "-40"})
    assert _errors_for(caught.value, field), caught.value.errors()


@pytest.mark.parametrize("field", PRODUCT_MEASURES)
def test_product_refuses_a_negative_on_update_too(field: str) -> None:
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
    assert ProductUpdate.model_validate({field: "0"}) is not None
    assert getattr(ProductUpdate.model_validate({}), field) is None


@pytest.mark.parametrize("field", TOOL_MEASURES)
def test_zero_and_omission_are_still_accepted_on_tool(field: str) -> None:
    assert ToolUpdate.model_validate({field: "0"}) is not None
    assert getattr(ToolUpdate.model_validate({}), field) is None


def test_a_positive_measurement_is_unchanged_by_the_bound() -> None:
    product = ProductUpdate.model_validate({"lengthInches": "12.75", "sellingPrice": "1400"})
    assert str(product.lengthInches) == "12.75"
    assert str(product.sellingPrice) == "1400"
    tool = ToolUpdate.model_validate({"radius": "0.5", "replacementCost": "250.00"})
    assert str(tool.radius) == "0.5"
    assert str(tool.replacementCost) == "250.00"
