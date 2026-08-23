from datetime import datetime
from decimal import Decimal
from typing import Any

from pydantic import Field, field_validator, model_validator

from app.schemas.common import (
    APIModel,
    LocationInput,
    forbid_clearing_location,
    require_location,
)
from app.services.artisan_identity import (
    is_masked_aadhaar,
    require_aadhaar,
    validate_aadhaar,
    validate_pehchan,
)

# The two regulated identity numbers on Artisan, and the only two columns a caller can be shown a
# MASK of. Both are masked identically on the way out (``records.mask_identity_number``), so both
# have to be un-masked identically on the way back in — see ``drop_masked_identity_numbers``.
IDENTITY_NUMBER_FIELDS = ("aadhaarNumber", "pehchanCardNumber")


def drop_masked_identity_numbers(data: dict[str, Any]) -> dict[str, Any]:
    """Remove any identity number that came back as the MASK the caller was shown.

    ``XXXX XXXX 9012`` posted back unchanged means "I was not shown the real value and did not
    change it" — never "set the column to that literal string". Dropping the key is what makes it
    safe to mask an EDIT surface at all.

    This is the write-side half of the masking, and it has to cover both numbers or masking one of
    them destroys it: ``normalize_pehchan("XXXX XXXX 1234")`` is ``"XXXXXXXX1234"``, twelve
    alphanumerics inside the 4-32 window with no checksum to fail, so the mask of a Pehchan card
    validated cleanly and REPLACED the real number — 200 OK, revision recorded, regulated identifier
    gone. (An Aadhaar mask fails validation instead, which is why only that one was noticed.)

    Mutates and returns *data*. Called by every route that writes an artisan from a client payload:
    PATCH /artisans/{id} and the review queue's edit action.
    """
    for field in IDENTITY_NUMBER_FIELDS:
        if is_masked_aadhaar(data.get(field)):
            data.pop(field, None)
    return data


class ArtisanCreate(APIModel):
    name: str = Field(min_length=1, max_length=180)
    localName: str | None = None
    gender: str | None = None
    phone: str | None = None
    email: str | None = None
    place: str = Field(min_length=1, max_length=180)
    address: str | None = None
    notes: str | None = None
    # Identity. aadhaarNumber is the dedup key (unique in the DB) and is REQUIRED to create an
    # artisan: an artisan entered without one can never be deduplicated against, which is the whole
    # job of the column. Typed as a bare `str` so it is required in the OpenAPI schema too, while
    # `require_aadhaar` handles the blank-string case with a message worth reading — and normalises
    # the typed spacing away and rejects a mistyped number outright, because a bad number is worse
    # here than no number at all. The COLUMN stays nullable; see services/artisan_identity.py for
    # why the existing artisans with no Aadhaar cannot and must not be forced to have one.
    aadhaarNumber: str
    # Deliberately Optional rather than `bool = True`. "Yes by default" is the FORM's default, and
    # both current clients send the answer explicitly. Defaulting it to True server-side would make
    # an OMITTED flag mean "has a card", and the validator below would then demand a number every
    # older client (Android <= 1.1.14, any script) has no way to send — breaking artisan creation for
    # them the moment this ships. None means "not answered" and is resolved below.
    pehchanCardAvailable: bool | None = None
    pehchanCardNumber: str | None = None
    # Required on create: newline-separated Do's (positive prompt) and Don'ts (negative prompt).
    dos: str = Field(min_length=1)
    donts: str = Field(min_length=1)
    craftId: str | None = None
    craftName: str | None = None
    # The workshop this artisan was documented at. Optional everywhere: an omitted workshopId behaves
    # exactly as before, while a supplied one both sets the explicit column and adds the
    # WorkshopArtisan join row, and subjects the submission to the workshop's assignment/window rules.
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    # ── THE TWO FACTS THE DESIGN WORKSHOP ASKS EVERY ARTISAN FOR ────────────────────────────
    #
    # `participant.age` and `participant.experienceYears` are `fromref()` fields on the workshop's
    # participant table — their help text promises the designer that picking an artisan fills them
    # in — and until these two existed the Artisan table had no column behind either. Importing an
    # artisan left both blank and adding one from inside the workshop had nowhere to put them, so
    # the designer typed them back in from a printout on a row that already named the record. That
    # is the behaviour the reference picker exists to end, and `experienceYears` is a TABLE_COLUMN,
    # so the blank printed in the participant table of every submitted report.
    #
    # A DATE, NOT AN AGE, and the workshop's own `age` field is derived from it. See the column
    # comment in schema.prisma: an age written down is wrong within a year and nothing notices.
    dateOfBirth: datetime | None = None
    # THE FEEDER `experienceYears` IS DERIVED FROM, and the same argument one column further on: a
    # stated NUMBER of years is right on the day it is typed and silently wrong from then on, while
    # a stated DATE is right every time it is printed. See `derive_experience_years` in
    # services/records, and the precedence written out where the derivation happens in
    # `REFERENCE_MODELS["Artisan"].data`.
    #
    # NO BOUND, deliberately, unlike `experienceYears` below. A date is not a count, so there is no
    # ceiling to mirror; a date that derives to something outside 0..90 (a typo'd century, a date in
    # the future) is dropped by the derivation rather than refused here, which leaves the stated
    # number and the legacy metadata behind it still readable. Refusing the whole PATCH would lose
    # an edit to the phone number that happened to travel beside a mistyped year.
    craftStartDate: datetime | None = None
    # 0..90 matches `fromref("experienceYears", …, min_value=0, max_value=90)` in the stage
    # registry EXACTLY. Two different ceilings would mean a number the artisan form accepts and the
    # workshop then refuses on a row it filled in itself.
    #
    # STILL COLLECTED, AND STILL THE ANSWER FOR MOST ROWS. `craftStartDate` above outranks it where
    # both exist, but an artisan who says "about thirty years" and cannot name a year has to stay
    # recordable — see the column comment in schema.prisma, which keeps that half of the original
    # argument verbatim.
    experienceYears: int | None = Field(default=None, ge=0, le=90)
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)

    _clean_aadhaar = field_validator("aadhaarNumber")(lambda cls, v: require_aadhaar(v))
    _clean_pehchan = field_validator("pehchanCardNumber")(lambda cls, v: validate_pehchan(v))

    @model_validator(mode="after")
    def require_craft(self) -> "ArtisanCreate":
        if not self.craftId and not self.craftName:
            raise ValueError("Artisan must be assigned to a craft")
        return self

    @model_validator(mode="after")
    def reconcile_pehchan(self) -> "ArtisanCreate":
        """Keep the "card available?" answer and the card number consistent, three ways.

        - **Yes** must come with a number. Answering yes and leaving the number blank is the one
          combination that puts the row in a state nothing downstream can interpret.
        - **No** clears any number that came with it. The form disables the number box when the
          answer is No, so a number arriving alongside No is leftover UI state rather than an
          instruction, and storing it would orphan a card number on a record that says it has none.
        - **Unanswered** (an older client that predates these fields) resolves from what it did send:
          a number implies Yes, no number means No. That keeps every row satisfying
          "Yes implies a number" without rejecting a request the client could not have made correctly.
        """
        if self.pehchanCardAvailable is None:
            self.pehchanCardAvailable = bool(self.pehchanCardNumber)
        elif self.pehchanCardAvailable:
            if not self.pehchanCardNumber:
                raise ValueError(
                    "Enter the Artisan Pehchan Card number, or set the card to 'No' if the "
                    "artisan does not hold one."
                )
        else:
            self.pehchanCardNumber = None
        return self


class ArtisanUpdate(APIModel):
    name: str | None = Field(default=None, min_length=1, max_length=180)
    localName: str | None = None
    gender: str | None = None
    phone: str | None = None
    email: str | None = None
    place: str | None = Field(default=None, min_length=1, max_length=180)
    address: str | None = None
    notes: str | None = None
    aadhaarNumber: str | None = None
    pehchanCardAvailable: bool | None = None
    pehchanCardNumber: str | None = None
    dateOfBirth: datetime | None = None
    # See `ArtisanCreate` for both of these, and `_CLEARABLE_COLUMNS` in api/routes/artisans for why
    # an explicit null on either one clears the stored value rather than being ignored: a join date
    # entered by mistake has to be retractable from the form that entered it.
    craftStartDate: datetime | None = None
    experienceYears: int | None = Field(default=None, ge=0, le=90)
    dos: str | None = None
    donts: str | None = None
    craftId: str | None = None
    craftName: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    # Deliberately still optional and still clearable, even though creating an artisan now demands an
    # Aadhaar. Two edits would otherwise become impossible: correcting the phone number of an artisan
    # recorded before the field existed (their number is NULL and the researcher cannot invent one),
    # and retracting a number typed against the wrong artisan — which has to be removable, or the
    # person who really holds it can never be created past the unique index.
    #
    # A masked number is passed through untouched rather than validated: it means "I was not shown
    # the real value and did not change it", and the route drops the key before the write
    # (``drop_masked_identity_numbers``). Validating it would 422 a caller who edited some unrelated
    # field.
    #
    # BOTH numbers take the same route. The Pehchan card used to be validated here like a real
    # entry, and its mask PASSES that validation — so the mask was stored over the card number
    # whenever an editor who could not read it saved the form.
    _clean_aadhaar = field_validator("aadhaarNumber")(
        lambda cls, v: v if is_masked_aadhaar(v) else validate_aadhaar(v)
    )
    _clean_pehchan = field_validator("pehchanCardNumber")(
        lambda cls, v: v if is_masked_aadhaar(v) else validate_pehchan(v)
    )

    @model_validator(mode="after")
    def reconcile_pehchan(self) -> "ArtisanUpdate":
        """Answering "No" on an edit clears the stored card number in the same request.

        Unlike the create path this cannot demand a number when the answer is Yes: a PATCH carrying
        only ``pehchanCardAvailable=true`` is legitimate when the record already holds one. The route
        does that check against the stored row, where the existing number is actually visible.
        """
        if self.pehchanCardAvailable is False:
            self.pehchanCardNumber = None
        return self


class CraftCreate(APIModel):
    name: str = Field(min_length=1, max_length=180)
    localName: str | None = None
    category: str | None = None
    description: str | None = None
    place: str | None = None
    # See ArtisanCreate.workshopId — the same optional link, mirrored into the WorkshopCraft join.
    workshopId: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    extraMetadata: dict[str, Any] | None = None


class CraftUpdate(APIModel):
    name: str | None = Field(default=None, min_length=1, max_length=180)
    localName: str | None = None
    category: str | None = None
    description: str | None = None
    place: str | None = None
    workshopId: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    extraMetadata: dict[str, Any] | None = None


#: Mirrored from the `WorkshopType` enum in schema.prisma. Validated here rather than left to the
#: database because an unknown value reaches a Postgres enum column and Prisma answers with a bare
#: 500 — which reads to a client as "the server is broken" rather than "that is not a kind". The
#: same reasoning as DESIGN_WORKSHOP_STATUSES in schemas/design_workshops.py.
WORKSHOP_TYPES = frozenset({"DESIGN_PROTOTYPE", "OTHER"})


class WorkshopCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    #: Which kind of workshop this is. A design & prototype workshop and an ordinary
    #: documentation visit are both `Workshop` rows and nothing used to tell them apart, so the
    #: design-workshop picker had to offer every workshop ever recorded. Defaults to OTHER, which
    #: is what every existing row implicitly was.
    workshopType: str = "OTHER"

    date: datetime | None = None
    startDate: datetime | None = None
    endDate: datetime | None = None
    place: str = Field(min_length=1, max_length=180)
    description: str | None = None
    notes: str | None = None
    artisanIds: list[str] = Field(default_factory=list)
    craftIds: list[str] = Field(default_factory=list)
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)

    @model_validator(mode="after")
    def _known_workshop_type(self) -> "WorkshopCreate":
        """Reject a kind the database does not have.

        `workshopType` reaches a Postgres enum column, so an unknown value is not merely stored
        wrong — Prisma refuses it and the route answers a bare 500, which reads to a client as
        "the server is broken" rather than "that is not a kind of workshop". Same reasoning as
        DESIGN_WORKSHOP_STATUSES in schemas/design_workshops.py.
        """
        if self.workshopType not in WORKSHOP_TYPES:
            raise ValueError(f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}")
        return self


class WorkshopUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    #: Omit to leave the stored kind alone. See WorkshopCreate.
    workshopType: str | None = None
    date: datetime | None = None
    startDate: datetime | None = None
    endDate: datetime | None = None
    place: str | None = Field(default=None, min_length=1, max_length=180)
    description: str | None = None
    notes: str | None = None
    artisanIds: list[str] | None = None
    craftIds: list[str] | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    @model_validator(mode="after")
    def _known_workshop_type(self) -> "WorkshopUpdate":
        """Omitted keeps the stored kind; a value must be one the database has."""
        if self.workshopType is not None and self.workshopType not in WORKSHOP_TYPES:
            raise ValueError(f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}")
        return self


# ---------------------------------------------------------------------------------------------
# NON_NEGATIVE_MEASURES — why every dimension and every money column on Product and Tool carries
# ``ge=0``, on BOTH the create and the update model.
#
# A length, a weight, a radius, a cost of making, a replacement cost: none of them has a negative
# value that means anything. The design-workshop registry already says so — the fields these
# columns are carried into (``product.lengthCm``, ``product.costOfMaking``, ``tool.lengthCm``,
# ``tool.cost``) are declared ``min_value=0`` in ``services/stage_definitions.py``. Without the
# bound here a researcher could type "-40", have it stored, have it hydrated into a workshop stage,
# and only THEN meet the refusal — on a row the repository itself had filled in.
#
# THE BOUND IS A PAIR, AND ONE HALF ALONE IS WORSE THAN NEITHER. The browser half (``min={0}`` on
# the inputs in ``frontend/components/forms/ProductForm.tsx`` and ``ToolForm.tsx``) is what names
# the offending box to the researcher — the forms set no ``noValidate``, so constraint validation
# runs, blocks the submit and focuses the input. This half is what holds for Android, for the
# outbox replaying a queued body, and for anything speaking to the API directly. Only ``yearsInUse``
# (and ``experienceYears`` on Artisan) had both halves before; the rest had neither.
#
# THE UPDATE MODELS ARE BOUNDED TOO, DELIBERATELY, AND IT IS NOT FREE. The web forms PATCH the WHOLE
# payload, not a delta, so a row already holding a negative would post that negative back on the
# next edit and 422 the record as a whole. That is why the browser half matters: on the web the
# refusal arrives as "Value must be greater than or equal to 0" against the named box, before any
# request, and correcting it is one keystroke in the edit the researcher was already making.
# Leaving update unbounded was the alternative, and it would have left the hole the pair exists to
# close — every non-browser client could still write a negative into a column create refuses.
# ``yearsInUse`` and ``experienceYears`` already made this trade the same way.
#
# NOT MEASURED: whether any stored row is negative today. Postgres is not reachable on this machine
# (Docker down), so the count is unknown rather than zero. If one exists, its next edit is refused
# at the box until the number is corrected.
# ---------------------------------------------------------------------------------------------


class ProductCreate(APIModel):
    craftName: str = Field(min_length=1, max_length=180)
    place: str = Field(min_length=1, max_length=180)
    artisanName: str = Field(min_length=1, max_length=180)
    productName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    productType: str = "OTHER"
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    # QUANTITIES THAT CANNOT BE NEGATIVE, BOUNDED HERE AND IN THE BOX. See NON_NEGATIVE_MEASURES
    # above for why both halves are needed and what the update half costs.
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    costOfMaking: Decimal | None = Field(default=None, ge=0)
    sellingPrice: Decimal | None = Field(default=None, ge=0)
    marketDemand: str = "UNKNOWN"
    rawMaterialsUsed: str | None = None
    mainToolsUsed: str | None = None
    productFunctionUse: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)


class ProductUpdate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=180)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)
    productName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    productType: str | None = None
    timeTakenToCompleteProduct: str | None = None
    size: str | None = None
    # Bounded on update as well as create — see NON_NEGATIVE_MEASURES above.
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    heightInches: Decimal | None = Field(default=None, ge=0)
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    costOfMaking: Decimal | None = Field(default=None, ge=0)
    sellingPrice: Decimal | None = Field(default=None, ge=0)
    marketDemand: str | None = None
    rawMaterialsUsed: str | None = None
    mainToolsUsed: str | None = None
    productFunctionUse: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)


class ProcessStepInput(APIModel):
    id: str | None = None
    name: str = Field(min_length=1, max_length=220)
    stepType: str = "SEQUENTIAL"
    sortOrder: int = Field(default=0, ge=0)
    notes: str | None = None


class ProcessCreate(APIModel):
    name: str = Field(min_length=1, max_length=220)
    productId: str = Field(min_length=1)
    preProcessAvailable: bool = False
    notes: str | None = None
    # The workshop this process was documented at. Omitted, the process still inherits its parent
    # product's workshop exactly as before; supplied, it names its own and is gated on that workshop.
    workshopId: str | None = None
    status: str = "PENDING"
    steps: list[ProcessStepInput] = Field(default_factory=list)
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    extraMetadata: dict[str, Any] | None = None


class ProcessUpdate(APIModel):
    name: str | None = Field(default=None, min_length=1, max_length=220)
    productId: str | None = None
    preProcessAvailable: bool | None = None
    notes: str | None = None
    workshopId: str | None = None
    status: str | None = None
    steps: list[ProcessStepInput] | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    extraMetadata: dict[str, Any] | None = None


class ToolCreate(APIModel):
    craftName: str = Field(min_length=1, max_length=180)
    place: str = Field(min_length=1, max_length=180)
    artisanName: str = Field(min_length=1, max_length=180)
    toolkitName: str = Field(min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    # `yearsInUse` has carried this bound since it was added; every measurement beside it and the
    # replacement cost below now do too. See NON_NEGATIVE_MEASURES above.
    yearsInUse: int | None = Field(default=None, ge=0)
    height: Decimal | None = Field(default=None, ge=0)
    width: Decimal | None = Field(default=None, ge=0)
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    thickness: Decimal | None = Field(default=None, ge=0)
    weight: Decimal | None = Field(default=None, ge=0)
    radius: Decimal | None = Field(default=None, ge=0)
    maker: str = "UNKNOWN"
    traditionType: str = "UNKNOWN"
    replacementCost: Decimal | None = Field(default=None, ge=0)
    suggestionsForToolImprovement: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create. See services/common.require_location for what that does and does not
    # mean — and note it is the ONLY half of the pair the clients cannot omit, because create is
    # the one moment the researcher is standing at the place.
    _location_required = model_validator(mode="after")(require_location)


class ToolUpdate(APIModel):
    craftName: str | None = Field(default=None, min_length=1, max_length=180)
    place: str | None = Field(default=None, min_length=1, max_length=180)
    artisanName: str | None = Field(default=None, min_length=1, max_length=180)
    toolkitName: str | None = Field(default=None, min_length=1, max_length=220)
    localName: str | None = None
    englishName: str | None = None
    processUsedIn: str | None = None
    material: str | None = None
    # Bounded on update as well as create — see NON_NEGATIVE_MEASURES above.
    yearsInUse: int | None = Field(default=None, ge=0)
    height: Decimal | None = Field(default=None, ge=0)
    width: Decimal | None = Field(default=None, ge=0)
    lengthInches: Decimal | None = Field(default=None, ge=0)
    breadthInches: Decimal | None = Field(default=None, ge=0)
    measurementImageId: str | None = None
    measurementAnalysis: dict[str, Any] | None = None
    measurementAnalysisStatus: str | None = None
    thickness: Decimal | None = Field(default=None, ge=0)
    weight: Decimal | None = Field(default=None, ge=0)
    radius: Decimal | None = Field(default=None, ge=0)
    maker: str | None = None
    traditionType: str | None = None
    replacementCost: Decimal | None = Field(default=None, ge=0)
    suggestionsForToolImprovement: str | None = None
    remarks: str | None = None
    artisanId: str | None = None
    craftId: str | None = None
    workshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit it to keep the stored one (which is how a record that predates the rule stays
    # editable); send one to replace it; you may not send null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)


class ToolArtisanAssign(APIModel):
    """Assign one documented tool to several artisans (same or different crafts)."""

    artisanIds: list[str] = Field(default_factory=list)
