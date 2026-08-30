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
render every column field and put every key on the wire on every save
(``designerProfileUpdateJson`` in ``ApiModels.kt``, ``fullDesignerProfileBody`` in
``frontend/lib/designers.ts``), which is exactly why an explicit null has to be the thing that
clears a column.

THE COUNT IS TWENTY-TWO SINCE 2026-08-29, and it is stated as a count rather than a list because a
prose copy of a field list goes stale the first time the list grows — which it just did, with
``experienceMonths``. Both encoders are HAND-WRITTEN maps, so a column added here is a key that
silently never reaches the server until each of them is edited too: there is no reflection behind
either, and a missing ``put`` reads on screen as a box that saves and then comes back empty.

``location`` IS THE ONE KEY THAT IS LEGITIMATELY OMITTED, and it is not an exception to the rule
above so much as the rule applied to a relation. The clients build it with ``locationFromForm``,
which answers ``undefined`` when either coordinate box is empty, and ``JSON.stringify`` drops an
``undefined`` — so a profile with no coordinate sends no ``location`` key and keeps whatever is
stored. That is the ONLY way to leave it alone: an explicit null is refused outright, because
``attach_location`` writes a brand-new ``Location`` row on every save and never updates one, so
"clear it" would orphan a row and leave the profile with no district rather than with a corrected
address. See :func:`app.schemas.common.forbid_clearing_location`.

The partial PUT is the ADMIN's. An admin maintaining the empanelment identifiers a government
report has to carry — which the designer often does not have to hand — sends those two keys and
nothing else, which is what ``test_an_admin_may_write_a_designers_profile`` sends. A body applied
without ``exclude_unset`` would read every other absent key as "clear" and blank the
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

from pydantic import EmailStr, Field, ValidationInfo, field_validator, model_validator

from app.schemas.common import APIModel, LocationInput, forbid_clearing_location
from app.services import rich_text

#: The profile columns that may not be CLEARED, and the words a designer knows them by.
#:
#: ── WHY THE API HAS THIS RULE AND NOT ONLY THE FORMS ────────────────────────────────────────────
#:
#: The owner's instruction of 2026-08-27 — "Name, qualification, email, and phone number should be
#: mandatory fields as well" — is a statement about the RECORD, and a rule only the browser enforces
#: is a rule the API does not have. Two clients write this table (``fullDesignerProfileBody`` on the
#: web, ``designerProfileUpdateJson`` on the handset), both send every column key on every save,
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

#: How many characters of the designer's actual ADDRESS the report cover can carry.
#:
#: The number the whole system already agrees on: ``designerAddress`` on stage 3 is declared
#: ``max_length=300``, ``MAX.addressLine`` in ``DesignerProfileForm.tsx`` is 300 and
#: ``ADDRESS_LINE_MAX`` in ``DesignerProfileScreen.kt`` is 300. It is measured against the FLATTENED
#: text — see :meth:`DesignerProfileUpdate._address_line_words_fit_the_report_cover` — so that
#: promoting the box to rich text did not quietly shrink what a designer may write by the size of a
#: JSON envelope they never see.
ADDRESS_LINE_PLAIN_MAX = 300

#: How large the STORED value may be, envelope included. See the comment on
#: ``DesignerProfileUpdate.addressLine``; this is a bound on the payload, not a rule about words.
ADDRESS_LINE_STORED_MAX = 20_000


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
    identifiers, the timestamps and ``locationId``.

    These twenty-two values are typed once and copied into every report the designer generates, so
    the field lengths here are the ones the report layout was measured against rather than
    arbitrary caps: a designation that runs to three hundred characters does not fail, it prints
    over the next line of the cover page.

    ``locationId`` IS NOT ON THIS BODY AND MUST NOT BE. A client sends ``location`` — the address
    itself, in the one shape this API already accepts from six other record types — and the server
    creates the row and keeps the id. A body that took a raw id would let any caller point their
    profile at another record's ``Location`` row, and would make "the address" two round trips that
    can disagree.

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
    # ── THE SECOND HALF OF THE SAME ANSWER — 0..11, A REMAINDER AND NEVER A TOTAL ──────────────
    #
    # The form asks for "5 years" beside "6 months" and the read-back hands back exactly what was
    # chosen. Twelve is not a bigger month, it is a year the box above already holds, which is why
    # the ceiling is 11 and not 12; ``DesignerProfile.experienceMonths`` in schema.prisma carries the
    # argument for two columns rather than one derived total.
    #
    # THE BOUND IS DECLARED HERE BECAUSE POSTGRES'S IS THE WRONG KIND OF REFUSAL. The column already
    # carries ``CHECK (experienceMonths BETWEEN 0 AND 11)``, but a CHECK violation reaches this API
    # as a driver error raised from inside the write — a bare 500 that names no field, on a save the
    # designer cannot correct because nothing on the screen says which box was wrong. ``ge``/``le``
    # here is a 422 whose ``loc`` is ``["body", "experienceMonths"]``, which is what the web client's
    # ``describeApiDetail`` turns into a message under that control. The CHECK stays as the backstop
    # for anything that reaches the table another way; it is not the thing a person is meant to meet.
    #
    # NO CEILING IS MIRRORED FROM THE REGISTRY, unlike ``experienceYears`` above, and the difference
    # is worth stating: that column is bounded 0..70 because it is COPIED into a stage field bounded
    # 0..70, and a wider body would prefill a box the stage then silently drops. This column is
    # copied into nothing yet — there is no ``designerExperienceMonths`` field on stage 3, so it is
    # listed in ``PREFILL_EXEMPT`` in tests/test_designer_prefill_contract.py with what it owes — so
    # 0..11 is the column's own rule and the only one it has.
    #
    # ABSENT, NULL AND 0 ARE THREE DIFFERENT ANSWERS, and pydantic's unset-versus-None distinction is
    # what keeps them apart. The route dumps this body with ``exclude_unset=True``: a key the client
    # never sent is not in the dict at all, so ``update_profile``'s loop skips it and the stored value
    # stands; a key sent as ``null`` is present and writes NULL, which is how a designer un-answers
    # the question; and ``0`` is present, is not ``None``, and stores 0 — "no odd months", an answer
    # somebody picked. A ``default=0`` here would collapse the first and the last, and would put "and
    # no months" on record for every profile written before this column existed, as though its owner
    # had chosen it in a dropdown they were never shown.
    experienceMonths: int | None = Field(default=None, ge=0, le=11)
    biography: str | None = Field(default=None, max_length=20000)
    phone: str | None = Field(default=None, max_length=40)
    email: EmailStr | None = None
    website: str | None = Field(default=None, max_length=300)
    # ── THE ONE RICH-TEXT COLUMN ON THIS PROFILE, AND THE ONLY FIELD HERE WITH TWO CEILINGS ─────
    #
    # ``ADDRESS_LINE_PLAIN_MAX`` (300) is the rule and has not moved: it is what the designer's
    # WORDS are bounded by, it is what both clients' "this box is full" notices count, and it is what
    # ``designerAddress`` — the registry ``TEXT`` field this column is copied into at workshop
    # creation — declares. ``ADDRESS_LINE_STORED_MAX`` is not a second rule; it is the size of the
    # ENVELOPE those 300 characters may arrive in.
    #
    # WHY THE ENVELOPE HAD TO GROW. On 2026-08-30 this box became the rich-text editor every other
    # prose column already uses. The storage shape is unchanged and needs no migration — the column
    # is still ``String?`` and still holds the designer's prose whenever nothing is formatted — but
    # the moment a word is bolded, the value stored is ``{"blocks":[{"kind":"PARAGRAPH","spans":
    # [{"text":"12 Nagar Road, ","marks":[]},{"text":"Bagru","marks":["BOLD"]}]}]}``. That is a
    # three-word address well past 300 characters, so a ``max_length=300`` on the RAW string would
    # have made the feature refuse its own saves — and refuse them with a 422 naming a box the
    # designer can see is short. Worse, the refusal would have been intermittent: plain addresses
    # would save and formatted ones would not, which reads as "the app broke when I pressed Bold".
    #
    # 20 000 is ``biography``'s ceiling above, reused deliberately rather than a new number invented:
    # 300 characters of prose with a mark on every other word is a few thousand, and this is a bound
    # on the PAYLOAD so that a client bug cannot post a novel into a profile column, not a number
    # anybody is meant to meet.
    addressLine: str | None = Field(default=None, max_length=ADDRESS_LINE_STORED_MAX)
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
    # ── WHERE THE DESIGNER IS BASED, IN THE SHAPE EVERY OTHER RECORD IN THIS SYSTEM ALREADY USES ─
    #
    # The SAME :class:`app.schemas.common.LocationInput` the six field-record bodies take — unchanged
    # and unsubclassed, so there is exactly ONE way to send an address to this API and a client can
    # reuse the encoder it already has. ``attach_location`` in app/services/records.py turns it into a
    # ``Location`` row and hands the id back to ``update_profile``; the profile is the seventh owner
    # of that table, not a seventh spelling of an address.
    #
    # THE FOUR FLAT COLUMNS ABOVE STAY, AND STAY WRITABLE. Nothing was backfilled by the migration
    # that added the relation and nothing is copied across on save. Which of the two is authoritative
    # for what is decided, once, in ``designers.profile_payload``; read that before writing a client
    # that renders one of them.
    #
    # ── A STATED ADDRESS WITH NO COORDINATE CANNOT BE STORED, AND THAT IS THE ANSWER, NOT A GAP ──
    #
    # ``LocationInput.latitude``/``longitude`` are required floats with NO default, because
    # ``Location.latitude``/``longitude`` are NOT NULL for all seven owners. So a body that names a
    # state and a district and carries no coordinate is a 422 from this field, before any handler
    # runs — and on the web the location card refuses the save in the box instead, saying so in
    # words: "The state and district are stored with the coordinates, so this record needs one before
    # they can be saved."
    #
    # THAT REFUSAL IS THE POINT. The alternative is manufacturing a coordinate, and a fabricated 0,0
    # or a default Kharagpur pin is precisely the failure the two-group split was built to end:
    # fifteen live artisans documented in Rajasthan, Gujarat, Uttarakhand and Andhra Pradesh already
    # carry Kharagpur coordinates because the schema once had nowhere else to put "where the subject
    # is". Relaxing the NOT NULL to spare the profile a click would weaken that invariant on all six
    # field-record owners and retype every reader of a location from ``float`` to ``float | None``.
    #
    # ── AND WHAT ARRIVES IS ALWAYS A POINT A PERSON GAVE. THIS PATH NEVER GEOCODES ─────────────
    #
    # Nothing on the server derives, defaults or looks up a coordinate, a place name or an address:
    # the row that is written holds the numbers the body carried and no others. The profile is the
    # one form in this system whose SUBJECT IS THE PERSON FILLING IT IN, always edited from a desk,
    # so an auto-captured fix here would read as "designer based in Kharagpur" and nobody would
    # question it. The server cannot tell a deliberately pressed GPS from an automatic one — both
    # arrive as two floats — so that check cannot live here, and it lives in the client instead:
    # ``LocationFields`` switches auto-capture off whenever it is handed an ``initial``, and the
    # profile screen, which is always an edit of one's own record, must always hand it one. Whoever
    # writes the next client needs to know that this line is not a second safety net.
    location: LocationInput | None = None

    # Omit it to keep the stored address; send one to replace it; you may not send ``null``. The same
    # rule the six record bodies carry, for the same reason: ``attach_location`` writes a BRAND NEW
    # ``Location`` row on every save and never updates one, so "clear it" has no honest
    # implementation — it would orphan the old row and leave the profile with no district rather
    # than with a corrected address. A designer who moves house REPLACES their location.
    #
    # WHAT IS DELIBERATELY NOT APPLIED IS ``require_location``. That demands a coordinate AND a state
    # AND a district, and it is a CREATE rule written for a field record made while standing at the
    # place. A profile has no create at all — ``get_or_create_profile`` mints the row on first read —
    # and a designer must be able to save their name, qualification, phone and e-mail without having
    # first decided where they live, on a form whose four mandatory columns are none of these.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)

    @field_validator("displayName", "qualification", "phone", "email")
    @classmethod
    def _mandatory_columns_may_not_be_cleared(cls, value: Any, info: ValidationInfo) -> Any:
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

    @field_validator("addressLine")
    @classmethod
    def _address_line_words_fit_the_report_cover(cls, value: Any) -> Any:
        """Bound the address the designer can SEE, not the JSON it may be wrapped in.

        ``Field(max_length=ADDRESS_LINE_STORED_MAX)`` above is the envelope. This is the rule, and it
        is the same 300 the column has always had: what reaches ``designerAddress`` on stage 3 — a
        registry ``TEXT`` field declared ``max_length=300`` and typeset on a report cover — is the
        FLATTENED text, so the flattened text is what has to fit.

        ``rich_text.plain_from_stored`` is the repository's read boundary and is IDENTITY on a plain
        string, so an address written before this column took rich text is measured exactly as it was
        measured before: 300 characters of prose, refused at 301. A formatted address is measured on
        its words, which is the only measurement a designer can act on — telling somebody their
        address is too long when the box in front of them holds four words would be a refusal with no
        available correction, and they would delete a line that was never the problem.

        WHY THIS IS NOT LEFT TO THE CLIENTS. Both of them count too: ``RichTextField`` shows the live
        count against 300 and Android's box clamps at ``ADDRESS_LINE_MAX``. Neither is the rule — the
        rule has to be here or the API does not have it, and this is the column whose overflow is
        discovered on a printed document rather than on a screen.
        """
        if not isinstance(value, str):
            return value
        words = str(rich_text.plain_from_stored(value)).strip()
        if len(words) > ADDRESS_LINE_PLAIN_MAX:
            raise ValueError(
                f"The address is {len(words)} characters long once its formatting is set aside, and "
                f"the column stores {ADDRESS_LINE_PLAIN_MAX} — it is printed on a report cover. "
                "Shorten it, or move part of it into the city, state and pincode boxes beside it."
            )
        return value
