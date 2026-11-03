from datetime import date
from decimal import Decimal
from typing import Literal

from pydantic import EmailStr, Field

from app.schemas.common import APIModel
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.services.sanction_orders_xlsx import MAX_SANCTION_ROWS

MAX_SANCTION_DESIGNERS = MAX_DESIGN_WORKSHOP_VIEWERS


class SanctionOrderCoDesigner(APIModel):
    name: str = Field(min_length=1, max_length=160)
    email: EmailStr


class SanctionOrderCreate(APIModel):
    sanctionOrderNo: str = Field(min_length=1, max_length=120)
    sanctionOrderDate: date
    sanctionAmount: Decimal = Field(gt=0, max_digits=14, decimal_places=2)
    designerName: str = Field(min_length=1, max_length=160)
    designerEmail: EmailStr
    notes: str | None = Field(default=None, max_length=2000)
    coDesigners: list[SanctionOrderCoDesigner] = Field(
        default_factory=list, max_length=MAX_SANCTION_DESIGNERS - 1
    )
    annualPlanEntryId: str | None = Field(default=None, min_length=1, max_length=64)


class SanctionOrderUpdate(APIModel):
    notes: str | None = Field(default=None, max_length=2000)



class SanctionOrderImportRow(SanctionOrderCreate):
    sourceFilename: str | None = Field(default=None, max_length=260)
    sheetRow: int | None = Field(default=None, ge=1)


class SanctionImportDesignerIn(APIModel):
    name: str = Field(min_length=1, max_length=160)
    email: EmailStr


class SanctionImportRowIn(APIModel):
    sheetRow: int = Field(ge=1)
    action: Literal["record", "skip"]
    sanctionOrderNo: str = Field(min_length=1, max_length=120)
    sanctionOrderDate: date
    sanctionAmount: Decimal = Field(gt=0, max_digits=14, decimal_places=2)
    notes: str | None = Field(default=None, max_length=2000)
    designers: list[SanctionImportDesignerIn] = Field(
        default_factory=list, max_length=MAX_SANCTION_DESIGNERS
    )


class SanctionImportConfirm(APIModel):
    sheet: str | None = Field(default=None, max_length=120)
    sourceFilename: str | None = Field(default=None, max_length=260)
    rowsRead: int = Field(default=0, ge=0)
    refusedBeforeConfirm: int = Field(default=0, ge=0)
    rows: list[SanctionImportRowIn] = Field(
        default_factory=list, max_length=MAX_SANCTION_ROWS
    )
