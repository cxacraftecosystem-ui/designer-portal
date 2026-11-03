from typing import Any

from pydantic import EmailStr, Field, ValidationInfo, field_validator, model_validator

from app.schemas.common import APIModel, LocationInput, forbid_clearing_location
from app.services import rich_text

REQUIRED_PROFILE_COLUMNS: dict[str, str] = {
    "displayName": "Name",
    "qualification": "Qualification",
    "phone": "Phone",
    "email": "Email",
}

ADDRESS_LINE_PLAIN_MAX = 300

ADDRESS_LINE_STORED_MAX = 20_000


class DesignerRosterCreate(APIModel):
    email: EmailStr
    fullName: str | None = Field(default=None, max_length=180)
    institution: str | None = Field(default=None, max_length=180)
    notes: str | None = Field(default=None, max_length=4000)
    isActive: bool = True


class DesignerRosterUpdate(APIModel):
    email: EmailStr | None = None
    fullName: str | None = Field(default=None, max_length=180)
    institution: str | None = Field(default=None, max_length=180)
    notes: str | None = Field(default=None, max_length=4000)
    isActive: bool | None = None


class DesignerProfileUpdate(APIModel):
    displayName: str | None = Field(default=None, max_length=180)
    localName: str | None = Field(default=None, max_length=180)
    designation: str | None = Field(default=None, max_length=180)
    institution: str | None = Field(default=None, max_length=180)
    department: str | None = Field(default=None, max_length=180)
    qualification: str | None = Field(default=None, max_length=220)
    specialisation: str | None = Field(default=None, max_length=220)
    experienceYears: int | None = Field(default=None, ge=0, le=70)
    experienceMonths: int | None = Field(default=None, ge=0, le=11)
    biography: str | None = Field(default=None, max_length=20000)
    phone: str | None = Field(default=None, max_length=40)
    phoneCountryCode: str | None = Field(default=None, max_length=8)
    email: EmailStr | None = None
    website: str | None = Field(default=None, max_length=300)
    addressLine: str | None = Field(default=None, max_length=ADDRESS_LINE_STORED_MAX)
    city: str | None = Field(default=None, max_length=120)
    state: str | None = Field(default=None, max_length=80)
    pincode: str | None = Field(default=None, max_length=12)
    photoMediaId: str | None = Field(default=None, max_length=64)
    signatureMediaId: str | None = Field(default=None, max_length=64)
    cvMediaId: str | None = Field(default=None, max_length=64)
    empanelmentNo: str | None = Field(default=None, max_length=120)
    empanelmentDate: str | None = Field(default=None, max_length=32)
    location: LocationInput | None = None

    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    @field_validator("displayName", "qualification", "phone", "email")
    @classmethod
    def _mandatory_columns_may_not_be_cleared(cls, value: Any, info: ValidationInfo) -> Any:
        if value is None or (isinstance(value, str) and not value.strip()):
            label = REQUIRED_PROFILE_COLUMNS[info.field_name or ""]
            raise ValueError(
                f"{label} is required on a designer profile — it is printed on every report "
                "generated under this name, so it cannot be left blank."
            )
        return value

    @field_validator("addressLine")
    @classmethod
    def _address_line_words_fit_the_report_cover(cls, value: Any) -> Any:
        if not isinstance(value, str):
            return value
        words = str(rich_text.plain_from_stored(value)).strip()
        if len(words) > ADDRESS_LINE_PLAIN_MAX:
            raise ValueError(
                f"The address is {len(words)} characters long, and it can be at most "
                f"{ADDRESS_LINE_PLAIN_MAX} — it is printed on a report cover. Shorten it, or move "
                "part of it into the city, state and pincode boxes beside it."
            )
        return value
