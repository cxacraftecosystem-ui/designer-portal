"""Request bodies for the designer roster and the designer profile.

TWO TABLES, TWO BODIES, AND THEY ARE NOT THE SAME FACT — which is the whole reason both exist.
``DesignerRoster`` is the ADMIN's statement that an individual is empanelled and may sign in;
``DesignerProfile`` is the DESIGNER's own statement of who they are, and it is the thing that
gets printed in a report. Collapsing them would mean an admin editing a biography, or a designer
editing their own permission to log in. Neither is a thing that should be possible, so the two
bodies never share a field even where they name the same idea: ``DesignerRosterCreate`` has an
``institution`` an admin typed to remember whom they empanelled, and ``DesignerProfileUpdate``
has an ``institution`` the designer types and the report prints.

Every update body is applied with ``exclude_unset``: a key that is absent leaves the stored value
alone, and a key that is present and null clears it. That distinction is load-bearing on
``DesignerProfileUpdate`` — but NOT
because a client sends a subset of it, because neither client does any more. Both profile editors
render all twenty-one fields and put every key on the wire on every save
(``designerProfileUpdateJson`` in ``ApiModels.kt``, ``fullDesignerProfileBody`` in
``frontend/lib/designers.ts``), which is exactly why an explicit null has to be the thing that
clears a column.

The partial PUT is the ADMIN's. An admin maintaining the empanelment identifiers a government
report has to carry — which the designer often does not have to hand — sends those two keys and
nothing else, which is what ``test_an_admin_may_write_a_designers_profile`` sends. A body applied
without ``exclude_unset`` would read the other nineteen absent keys as "clear" and blank the
institution, the biography and the signature the designer typed on the web the week before — the
sort of loss nobody notices until a report prints without them.

FOUR PROFILE COLUMNS MAY NOT BE CLEARED, WHICH IS A DIFFERENT STATEMENT FROM "REQUIRED". Since
2026-08-27 the name, qualification, phone and e-mail are mandatory on a designer profile — see
:data:`REQUIRED_PROFILE_COLUMNS` — and that is enforced by field validators, which pydantic runs
only for a key that was actually SUPPLIED. So the ``exclude_unset`` contract above is untouched in
both directions: an absent key still leaves the stored value alone (the admin's two-key PUT never
meets the rule at all), and what is refused is only a body that explicitly asks to blank one of the
four. Nothing here makes a column non-nullable — rows created before the rule, including the empty
one ``GET`` mints on first read, are still perfectly readable; they simply cannot be re-saved with
those boxes empty.
"""

from typing import Any

from pydantic import EmailStr, Field, ValidationInfo, field_validator

from app.schemas.common import APIModel

#: The profile columns that may not be CLEARED, and the words a designer knows them by.
#:
#: ── WHY THE API HAS THIS RULE AND NOT ONLY THE FORMS ────────────────────────────────────────────
#:
#: The owner's instruction of 2026-08-27 — "Name, qualification, email, and phone number should be
#: mandatory fields as well" — is a statement about the RECORD, and a rule only the browser enforces
#: is a rule the API does not have. Two clients write this table (``fullDesignerProfileBody`` on the
#: web, ``designerProfileUpdateJson`` on the handset), both send all twenty-one keys on every save,
#: and anything else holding a bearer token can PUT whatever it likes. The web form marks these four
#: with `required` so the refusal is met in the box rather than as a round trip; this is what makes
#: the refusal true of the repository.
#:
#: ── PRESENT-AND-EMPTY IS REFUSED; ABSENT IS UNTOUCHED, AND THAT DISTINCTION IS PRESERVED EXACTLY ──
#:
#: These are ``field_validator``s, which pydantic runs only for a key that is actually SUPPLIED — a
#: field left to its default is not validated. So the module docstring's contract is unchanged: an
#: absent key still leaves the stored value alone, and the admin's two-key partial PUT
#: (``test_an_admin_may_write_a_designers_profile``) never reaches these at all. What is now refused
#: is the one thing that used to be allowed and should not have been: a body that explicitly says
#: "clear the name on the cover of every report this person generates".
#:
#: ── THE LABEL, NOT THE COLUMN NAME, IN THE SENTENCE ─────────────────────────────────────────────
#:
#: ``loc`` already carries the column for whoever is reading a log; the message is read by a designer
#: on a screen, and it has to name the box they are looking at. The strings are
#: ``DESIGNER_PROFILE_LABELS`` in ``frontend/components/designers/profileCopy.ts``, and the web
#: client's ``describeApiDetail`` prints them as "displayName: Name is required…".
REQUIRED_PROFILE_COLUMNS: dict[str, str] = {
    "displayName": "Name",
    "qualification": "Qualification",
    "phone": "Phone",
    "email": "Email",
}


class DesignerRosterCreate(APIModel):
    """Empanel somebody. The email is the only fact required, and the only one that matters.

    The row may — and usually does — exist BEFORE the user account does: that is exactly how an
    admin empanels a designer who has never logged in, and it is why this body carries a
    ``fullName`` rather than pointing at a user id. The name is what the roster screen shows
    while the invitation is outstanding.
    """

    email: EmailStr
    fullName: str | None = Field(default=None, max_length=180)
    institution: str | None = Field(default=None, max_length=180)
    notes: str | None = Field(default=None, max_length=4000)
    isActive: bool = True


class DesignerRosterUpdate(APIModel):
    """Correct a roster row, or restore a suspended one.

    ``isActive`` is the whole gate: setting it false ends the designer's sessions at their next
    request and setting it true gives them back, both without touching ``User.role``. It is
    accepted here as well as through ``DELETE /designers/roster/{id}`` because restoring is not
    an act a DELETE can express.
    """

    email: EmailStr | None = None
    fullName: str | None = Field(default=None, max_length=180)
    institution: str | None = Field(default=None, max_length=180)
    notes: str | None = Field(default=None, max_length=4000)
    isActive: bool | None = None


class DesignerProfileUpdate(APIModel):
    """Every column of ``DesignerProfile`` a person may write — which is all of them but the
    identifiers and the timestamps.

    These twenty-one values are typed once and copied into every report the designer generates, so
    the field lengths here are the ones the report layout was measured against rather than
    arbitrary caps: a designation that runs to three hundred characters does not fail, it prints
    over the next line of the cover page.

    ``empanelmentDate`` is an ISO-8601 date string rather than a ``date``, matching every other
    date this API accepts (see ``DesignWorkshopCreate.startDate``). The clients send strings, and
    a body that accepted a ``date`` here and a ``str`` there would 422 exactly one of the two
    screens for a reason its author could not see.
    """

    displayName: str | None = Field(default=None, max_length=180)
    localName: str | None = Field(default=None, max_length=180)
    designation: str | None = Field(default=None, max_length=180)
    institution: str | None = Field(default=None, max_length=180)
    department: str | None = Field(default=None, max_length=180)
    qualification: str | None = Field(default=None, max_length=220)
    specialisation: str | None = Field(default=None, max_length=220)
    # Bounded exactly as the registry's ``designerExperience`` field is (min 0, max 70), because
    # this value is COPIED into that field when a workshop is created. A profile that accepted
    # 400 years would prefill a stage the stage's own validator then rejects, and the designer
    # would be told their workshop has an error in a box they never typed in.
    experienceYears: int | None = Field(default=None, ge=0, le=70)
    biography: str | None = Field(default=None, max_length=20000)
    phone: str | None = Field(default=None, max_length=40)
    email: EmailStr | None = None
    website: str | None = Field(default=None, max_length=300)
    addressLine: str | None = Field(default=None, max_length=300)
    city: str | None = Field(default=None, max_length=120)
    state: str | None = Field(default=None, max_length=80)
    pincode: str | None = Field(default=None, max_length=12)
    photoMediaId: str | None = Field(default=None, max_length=64)
    signatureMediaId: str | None = Field(default=None, max_length=64)
    #: The designer's CV, as a media id. Bounded at 64 like its two siblings — a cuid is 25
    #: characters and the ceiling is the same one every media id on this API is given.
    #:
    #: NO CONTENT-TYPE CONSTRAINT LIVES HERE, and that is deliberate rather than missing. What was
    #: uploaded is a fact about the `MediaFile` row, which this column only points at; a body that
    #: re-declared "must be a PDF" would be a second opinion about a file it cannot see, and the
    #: one that mattered — is it renderable inline — is answered at render time from the stored
    #: mime type. The Designer Page previews a PDF and offers a download for anything else.
    cvMediaId: str | None = Field(default=None, max_length=64)
    empanelmentNo: str | None = Field(default=None, max_length=120)
    empanelmentDate: str | None = Field(default=None, max_length=32)

    @field_validator("displayName", "qualification", "phone", "email")
    @classmethod
    def _mandatory_columns_may_not_be_cleared(
        cls, value: Any, info: ValidationInfo
    ) -> Any:
        """Refuse a SUPPLIED name, qualification, phone or e-mail that is null or all whitespace.

        See :data:`REQUIRED_PROFILE_COLUMNS` for why this rule is on the API rather than only in the
        two forms, and for why it is a field validator: pydantic runs one only for a key that was
        actually sent, so ``exclude_unset``'s "absent means leave it alone" is untouched and an
        admin's two-key partial PUT never meets it.

        The blank test matches ``update_profile``'s own fold — it stores ``value.strip() or None``,
        so a single space and a null are the same instruction to the column, and refusing one while
        accepting the other would be a rule that a trailing space defeats.

        ``email`` reaches this already validated as an ``EmailStr``, so the only value that can get
        this far and be empty is an explicit ``null``. The ``@`` rule stays where it is; this adds
        nothing to it and must not, or the client's ``type="email"`` and the server would be two
        opinions about one address.
        """
        if value is None or (isinstance(value, str) and not value.strip()):
            label = REQUIRED_PROFILE_COLUMNS[info.field_name or ""]
            raise ValueError(
                f"{label} is required on a designer profile — it is printed on every report "
                "generated under this name, so it cannot be left blank."
            )
        return value
