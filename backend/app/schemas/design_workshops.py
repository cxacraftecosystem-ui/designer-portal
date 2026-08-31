"""Request bodies for the design-workshop API.

Everything here is thin on purpose. The interesting validation — what a field is, what it may
contain, whether a stage is complete — lives in :mod:`app.services.stage_schema` and is driven
by the registry, because the registry is what all three clients read. A pydantic model that
restated it would be a fourth copy to keep in step.

One deliberate departure from the house rule. ``APIModel`` sets ``extra="forbid"``, so an
unknown key is a 422 — which is right for every other endpoint here, where the client and the
server ship together. It is wrong for a *stage payload*: an Android draft written two weeks ago
in a village, by a build one release ahead of the server, carries field keys this build has
never heard of, and refusing the whole submission would lose the fieldwork rather than the
field. So ``StageEntryIn.data`` is an open ``dict`` and the registry drops what it does not
recognise, returning the dropped keys for the caller to log. The forbidding still applies to
the envelope around it.
"""

from datetime import datetime
from typing import Annotated, Any

from pydantic import Field, model_validator

from app.schemas.common import APIModel
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.services.ai_layers import MAX_SOURCE_TEXT_CHARS as _MAX_SOURCE_TEXT_CHARS
from app.services.custom_sections import (
    MAX_CUSTOM_DESCRIPTION_CHARS,
    MAX_CUSTOM_FIELDS_PER_SECTION,
    MAX_CUSTOM_HELP_CHARS,
    MAX_CUSTOM_KEY_CHARS,
    MAX_CUSTOM_LABEL_CHARS,
    MAX_CUSTOM_OPTIONS,
    MAX_CUSTOM_SECTIONS,
    MAX_CUSTOM_TITLE_CHARS,
    MAX_CUSTOM_UNIT_CHARS,
)
from app.services.report_templates import TEMPLATES
from app.services.stage_schema import ENUMS

# The DesignWorkshopStatus enum, mirrored from schema.prisma. Kept as a frozenset here
# rather than imported from the generated Prisma client so validating a request body does
# not require the client to have been generated.
DESIGN_WORKSHOP_STATUSES = frozenset({"DRAFT", "IN_PROGRESS", "COMPLETE", "SUBMITTED", "ARCHIVED"})
REPORT_TEMPLATE_IDS = frozenset(t.id for t in TEMPLATES)

#: The kinds a design workshop may be, READ OFF THE REGISTRY rather than restated here.
#:
#: The two lists above are frozen sets built from their own sources for the same reason, and this
#: one has a sharper edge than either: the vocabulary is what both clients DRAW, straight out of
#: ``registry_to_dict()``. A second copy in this module would be a list the API validates against
#: that the dropdown does not offer, and the failure is silent in the worst direction — a designer
#: picks a perfectly ordinary option and the save is refused with a 422 naming values they were
#: never shown. One list, in ``stage_schema.ENUMS``, and everything else reads it.
WORKSHOP_KINDS = frozenset(ENUMS["WORKSHOP_KIND"])

# A single stage submission is bounded so one malformed client cannot post an unbounded blob
# into a JSON column. Twenty-two stages of prose, at these limits, is comfortably under the
# row size Postgres will inline.
MAX_STAGE_ROWS = 500
MAX_FIELD_KEYS = 400

#: How long the workshop header's free-text ``notes`` may be.
#:
#: IT HAD NO CAP AT ALL while every sibling field carried one (title 220, craftName 160, state 80,
#: workshopId 64), and the cost of that is not paid by the writer: ``workshop_summary`` returns
#: ``notes`` in full and the LIST endpoint serialises it, so one 20 MB note is 20 MB added to
#: every page of workshops anybody loads, for ever. With ``pageSize`` capped at 100, a hundred
#: such rows is a ~2 GB response the server assembles in memory — and nothing upstream stops the
#: write: there is no body-size middleware and nginx allows 200M bodies.
#:
#: 20,000 characters is roughly ten typed pages, an order of magnitude more than a header note has
#: ever needed and ten times under ``rich_text.MAX_DOCUMENT_CHARS``, which bounds the stage prose
#: that carries the actual narrative.
MAX_NOTES_CHARS = 20_000

#: The warnings a phone reports against an export it generated offline. Uncapped for the same
#: reason and read back the same way — ``GET /{id}/exports`` returns up to 100 rows of them.
MAX_EXPORT_WARNINGS = 40
MAX_EXPORT_WARNING_CHARS = 400


class DesignWorkshopCreate(APIModel):
    """Only the title is required to start.

    A workshop is created on day one, in a room, before the sanction order number is to hand.
    Requiring more here would make the app unusable at exactly the moment it is opened; the
    Basic-tier fields of stage 1 are enforced when the report is generated, not before.
    """

    title: str = Field(min_length=1, max_length=220)
    templateId: str = Field(default="DCH_STANDARD", max_length=48)
    # THE TYPE OF WORKSHOP, AND IT IS OPTIONAL HERE ON PURPOSE — read this class's own docstring:
    # "a workshop is created on day one, in a room, before the sanction order number is to hand.
    # Requiring more here would make the app unusable at exactly the moment it is opened." The stage
    # field is `required=True` and `validate_entry` enforces that at SUBMISSION, which is where the
    # answer is actually needed. Making the create body demand it would refuse the one action the
    # whole app exists to make easy, and would do it for a field a designer can fill in ten seconds
    # later from a six-option dropdown.
    workshopKind: str | None = Field(default=None, max_length=48)
    # ── THE DESIGNER THIS WORKSHOP IS FOR ────────────────────────────────────────────────────────
    #
    # THE DEFECT THIS FIELD ENDS, and it is the requirement it ends, not a convenience. Requirement
    # 3: "All information entered on the Designer Page should be treated as the designer's master
    # profile information and should be automatically pre-filled in every report that the designer
    # creates." The mechanism that serves it — ``seed_designer_prefill`` — copied the profile of the
    # account that POSTED this body, and the route's own gate
    # (``assert_can_create_design_workshops``) guarantees that account is an ADMIN, because a
    # DESIGNER may not open a workshop at all. So a designer's profile pre-filled nothing, ever, and
    # every report named the admin who opened the workshop: on the cover, in the sign-off block, and
    # in the .docx's own ``dc:creator`` — while the completeness score read 100% and no warning
    # fired, because ``designerName`` was not MISSING, it was FILLED WITH THE WRONG PERSON. The only
    # detector in the whole product was a human reading a stranger's name in a box labelled
    # "Designer". ``tests/test_designer_roster.py`` pinned that outcome by name and its docstring
    # named this field as the fix.
    #
    # WHY IT IS ON THE CREATE AND NOT DERIVED FROM THE VIEWER GRANTS. Designers are attached AFTER
    # creation, through ``PUT /design-workshops/{id}/viewers``, and a workshop can carry SEVERAL —
    # "a real workshop is run by two designers alongside a master craftsperson and a reviewing
    # officer" (``services/design_workshop_viewers``). ``DesignWorkshopViewer`` has no ``role``, no
    # lead flag and no ordering that survives a multi-name PUT (one ``create_many`` gives every
    # grantee the same ``createdAt``), so "the designer" is NOT DECIDABLE from that table. Naming
    # one account here is the only answer that does not require the server to GUESS which of three
    # grantees to print on a ministry document — and a guess is worse than the defect it replaces,
    # because the defect is at least consistent.
    #
    # SINGULAR, DELIBERATELY, AND IT STAYED SINGULAR WHEN THE WORKSHOP GAINED MANY DESIGNERS —
    # see ``designerUserIds`` below, which is the plural one. Stage 1 and stage 3 declare exactly
    # ONE designer block — one ``designerName``, one ``designerProfile``, one signature — and
    # ``report_meta`` feeds ``record.designerName`` into the .docx's ``dc:creator``, a field the
    # file format cannot express as a list. So THIS field answers "whose profile is copied and
    # whose name is on the cover", which has exactly one answer, and the new one answers "who may
    # open this workshop", which has several. Making the stage-1 block repeatable is a registry
    # change — it moves ``registry_version()``, which stales every handset's bundled schema asset
    # and moves every existing workshop's completeness — and it is the owner's call, not this
    # field's.
    #
    # OPTIONAL, AND ABSENT MEANS "UNCHANGED". A workshop is opened in a room on day one and the
    # admin may genuinely not know yet who will run it; the offline create path cannot reach the
    # eligibility picker at all. When this is absent the seed behaves exactly as it did before this
    # field existed, which is what makes the field additive for every client that has not adopted
    # it yet.
    #
    # NAMING SOMEBODY HERE ALSO PUTS THEM ON THE WORKSHOP — the create route grants them a
    # ``DesignWorkshopViewer`` row in the same call, under the same eligibility rule the viewers
    # screen applies, so an ineligible id refuses the whole create with a 422 naming the account
    # BEFORE any row is written. The two admin steps this replaces were "create, then remember to
    # add the designer", and forgetting the second one is how a designer ends up unable to open the
    # workshop whose stage 1 already carries their name.
    designerUserId: str | None = Field(
        default=None,
        max_length=64,
        description=(
            "The designer this workshop is FOR. Their DesignerProfile is copied into stage 1 and "
            "stage 3, and they are granted access in the same call. Omit if not yet known."
        ),
    )
    # ── EVERY DESIGNER WHO MAY OPEN THIS WORKSHOP ────────────────────────────────────────────────
    #
    # THE ASK THIS ANSWERS, verbatim: "Designer this workshop is for should be a multi-select
    # dropdown with searchable functionality … the design workshop would only be visible to those
    # particular designers, admins and master admins would be able to see all the design workshops."
    #
    # THE SECOND HALF OF THAT SENTENCE IS ALREADY THE SHIPPED RULE AND NOTHING HERE CHANGES IT. A
    # design workshop is already visible only to its creator, to admins, and to whoever holds a
    # ``DesignWorkshopViewer`` row — enforced IN THE QUERY on the list
    # (``visible_to_clause``) and IN THE LOAD on the single read (``load_workshop_or_404``), with the
    # refusal spelled 404 "Record not found" so it cannot be told apart from an id that does not
    # exist. A DESIGNER cannot create a workshop at all (``assert_can_create_design_workshops`` is
    # ``is_admin``), so ``createdById`` never matches for them and a designer sees EXACTLY the
    # workshops they hold a row on. This field is therefore not a new access rule; it is the missing
    # way to write the rows at the moment the workshop is opened, instead of the admin having to
    # remember the viewers panel afterwards.
    #
    # WHY NO NEW TABLE, WHICH IS THE CHANGE THAT WAS DESIGNED AND REJECTED. A
    # ``DesignWorkshopDesigner`` join table mirroring ``DesignWorkshopViewer`` would be a SECOND
    # SOURCE OF ACCESS — the thing ``DesignWorkshopViewer``'s own schema comment forbids by name
    # ("Do not add a predicate that reads it: that is the 'two places to look when somebody has
    # access they should not'"). Viewer membership is consulted from at least six places, and one of
    # them — ``questionnaire_forms._visible_questionnaire_where`` — spells ``viewers: {some: …}`` BY
    # HAND rather than importing the clause. A second table needs a second arm in every one of them,
    # and the hand-written one is the arm somebody misses: the symptom would be a co-designer who
    # can open the workshop and finds its questionnaire list empty. So the multi-select writes the
    # table that already decides this question.
    #
    # RELATIONSHIP TO ``designerUserId`` ABOVE, WHICH IS UNCHANGED IN MEANING: that one names the
    # LEAD — whose ``DesignerProfile`` is copied into stage 1 and stage 3 and whose name reaches the
    # report cover and the .docx ``dc:creator``. This one names the whole team, LEAD INCLUDED, and
    # decides nothing about the report. Sending both is the normal case. Sending only this one is
    # legal: the first id becomes the lead, because a client that ticked names and named no lead
    # still has to get a profile seeded and the alternative is seeding the ADMIN's, which is the
    # defect ``designerUserId`` exists to end.
    #
    # OPTIONAL, AND IT MUST STAY OPTIONAL FOREVER. An APK a fortnight behind sends ``designerUserId``
    # alone, or neither; ``APIModel`` forbids unknown keys but says nothing about absent ones, so
    # both bodies behave exactly as they did before this field existed. That is not politeness — on
    # Android ``saveOrQueue`` will NOT re-queue a 4xx, so a create the server refuses is a create
    # whose record is LOST. Narrowing this body (making either field required, or dropping the
    # singular one) would destroy fieldwork on every handset that had not updated.
    #
    # CAPPED BY THE SAME CONSTANT THE VIEWERS PUT USES, imported rather than restated. The two
    # writes land in one table; a create that accepted a set the viewers screen would refuse — or
    # the reverse — is the same list with two rules. The cap also bounds real work rather than
    # merely tidying the wire: the route reads every named account out of the user table, the
    # designer roster and the access roster BEFORE it writes anything, so an uncapped list would let
    # one caller choose how much work the server does.
    designerUserIds: list[Annotated[str, Field(max_length=64)]] | None = Field(
        default=None,
        max_length=MAX_DESIGN_WORKSHOP_VIEWERS,
        description=(
            "Every designer this workshop is for, the lead included. Each is granted access in "
            "this same call, and the workshop is visible to them and to admins and nobody else. "
            "Omit if not yet known."
        ),
    )
    craftName: str | None = Field(default=None, max_length=160)
    clusterName: str | None = Field(default=None, max_length=160)
    state: str | None = Field(default=None, max_length=80)
    district: str | None = Field(default=None, max_length=80)
    startDate: str | None = None
    endDate: str | None = None
    workshopId: str | None = Field(
        default=None, max_length=64, description="Links this design workshop to a Workshop record."
    )
    notes: str | None = Field(default=None, max_length=MAX_NOTES_CHARS)

    @model_validator(mode="after")
    def _known_template(self) -> "DesignWorkshopCreate":
        if self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}")
        # `workshopKind` reaches a plain TEXT column, so Prisma would accept a typo and store it —
        # the workshop would then be filed under a kind no dropdown offers and no filter matches,
        # silently. Same check, same wording, as the PATCH body's.
        if self.workshopKind is not None and self.workshopKind not in WORKSHOP_KINDS:
            raise ValueError(f"workshopKind must be one of {', '.join(sorted(WORKSHOP_KINDS))}")
        return self


class DesignWorkshopUpdate(APIModel):
    """The all-optional mirror of :class:`DesignWorkshopCreate`.

    Note that the promoted columns (craft, cluster, dates …) are normally written by saving
    stage 1, not through this body — see ``stage_schema.promoted_values``. They are accepted
    here as well so an admin can correct a list entry without opening the stage.
    """

    title: str | None = Field(default=None, min_length=1, max_length=220)
    templateId: str | None = Field(default=None, max_length=48)
    # See the validators at the foot of this class: both of these reach a typed column or a
    # template lookup, and neither may be a free string.
    # THE TYPE OF WORKSHOP. Accepted here for the same stated reason as `craftName` and the dates —
    # so an admin can correct a list entry without opening stage 1 — and validated below, because it
    # reaches a promoted column that a list filters on. A free string here would produce a workshop
    # filed under a kind no dropdown offers and no filter can find.
    workshopKind: str | None = Field(default=None, max_length=48)
    craftName: str | None = Field(default=None, max_length=160)
    clusterName: str | None = Field(default=None, max_length=160)
    state: str | None = Field(default=None, max_length=80)
    district: str | None = Field(default=None, max_length=80)
    startDate: str | None = None
    endDate: str | None = None
    workshopId: str | None = Field(default=None, max_length=64)
    notes: str | None = Field(default=None, max_length=MAX_NOTES_CHARS)
    status: str | None = Field(default=None, max_length=24)

    @model_validator(mode="after")
    def _known_status_and_template(self) -> "DesignWorkshopUpdate":
        """Reject a status or a template the system does not have.

        ``status`` reaches a Postgres enum column, so an unknown value was not merely stored
        wrong — Prisma refused it and the route answered a bare 500, which reads to a client as
        "the server is broken" rather than "that is not a status". ``templateId`` is looked up
        with a deliberate fall-back to the DCH standard, so a typo there was silently accepted
        and then quietly produced a different report from the one the designer chose, with the
        substitution buried in an export warning nobody reads. Both are a 422 naming the
        allowed values, which is what the client can actually act on.
        """
        if self.status is not None and self.status not in DESIGN_WORKSHOP_STATUSES:
            raise ValueError(f"status must be one of {', '.join(sorted(DESIGN_WORKSHOP_STATUSES))}")
        if self.templateId is not None and self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}")
        # THE SAME TREATMENT, AND THE THIRD REASON IN THAT LIST APPLIES TO IT PARTICULARLY.
        # `workshopKind` reaches a plain TEXT column, so unlike `status` Prisma would ACCEPT a typo
        # and store it — the workshop would then be filed under a kind that no dropdown offers and
        # no filter matches, and nothing anywhere would say so. A 422 naming the six is the only
        # answer a client can act on.
        if self.workshopKind is not None and self.workshopKind not in WORKSHOP_KINDS:
            raise ValueError(f"workshopKind must be one of {', '.join(sorted(WORKSHOP_KINDS))}")
        return self


class StageEntryIn(APIModel):
    """One record of one entity of one stage.

    ``ordinal`` orders a collection's rows and is what a client sends after a drag-to-reorder.
    ``entryId`` identifies an existing row; omitting it creates one. ``data`` is open — see the
    module docstring.
    """

    entityKey: str = Field(min_length=1, max_length=64)
    entryId: str | None = Field(default=None, max_length=64)
    ordinal: int | None = Field(default=None, ge=0)
    data: dict[str, Any] = Field(default_factory=dict)
    merge: bool = Field(
        default=False,
        description=(
            "'I am sending every key I HAVE, not every key there IS.' When true, keys already "
            "stored under this row and absent from `data` are KEPT instead of deleted. The "
            "default is false, which is the wholesale replace every client relied on before this "
            "existed.\n\n"
            "IT EXISTS BECAUSE A CLIENT CAN HOLD A STAGE IT HAS NEVER READ. Open a stage in a "
            "village, let the download fail, and the form comes up blank — blank because unread, "
            "not because empty. Both clients say so on screen, and both promised that what you "
            "leave blank will not overwrite an answer recorded elsewhere. They could not keep "
            "that promise: a singleton row's `data` is replaced wholesale, so typing one field "
            "into an unread stage deleted every other field the office had written, in place, "
            "with no RecordRevision to recover it — and the promoted columns (craftName, state, "
            "district, dates) were nulled with it, so the workshop dropped out of every filter "
            "and the report cover printed blank.\n\n"
            "Set it when, and only when, the client knows it has not seen the server's copy: "
            "`serverLoadedAt === null` on the web, `!isAuthoritative(...)` on Android. A client "
            "that HAS read the row must leave it false, because for that client an absent key is "
            "a real deletion and must still delete."
        ),
    )

    @model_validator(mode="after")
    def _bound_payload(self) -> "StageEntryIn":
        if len(self.data) > MAX_FIELD_KEYS:
            raise ValueError(f"a stage entry may carry at most {MAX_FIELD_KEYS} field keys")
        return self


class StageSaveIn(APIModel):
    """A whole stage, saved in one request.

    Saving the stage rather than the field is what makes the write atomic: the phone reconnects
    after two days offline and posts everything it has for a stage, and either all of it lands
    or none of it does. A per-field endpoint would leave a stage half-written whenever the
    connection dropped mid-sync, which in a field with one bar of signal is most of the time.
    """

    entries: list[StageEntryIn] = Field(default_factory=list)
    replaceCollections: bool = Field(
        default=True,
        description=(
            "When true (the default) the entities named in `entries` are replaced wholesale by "
            "what is sent, so a row deleted on the phone is deleted here. When false, entries "
            "are merged by entryId and nothing is removed — used by the web form, which edits "
            "one row at a time and must not delete rows another editor added. An entity the "
            "payload never mentions is NEVER swept — say so with `emptiedEntities`."
        ),
    )
    emptiedEntities: list[str] = Field(
        default_factory=list,
        description=(
            "Collections the client has emptied: 'I now hold zero rows of this entity, delete "
            "what you still have.' It exists because an emptied collection is INVISIBLE in "
            "`entries` — the web builds entries from `collections[key] ?? []` and the phone from "
            "`.orEmpty()`, so deleting the LAST row of a collection sends nothing at all and the "
            "sweep could not see it. Naming it here is the only way that deletion reaches the "
            "server, since there is no per-row delete endpoint. Read only when "
            "`replaceCollections` is true, and ignored for singletons, which are never deleted "
            "by omission."
        ),
    )
    submit: bool = Field(
        default=False,
        description=(
            "Enforce the Basic-tier required fields. Left false while a stage is a draft: the "
            "whole point of the app is that a stage can be left half-filled overnight."
        ),
    )

    @model_validator(mode="after")
    def _bound_rows(self) -> "StageSaveIn":
        if len(self.entries) > MAX_STAGE_ROWS:
            raise ValueError(f"a stage may carry at most {MAX_STAGE_ROWS} entries per request")
        # Bounded like every other list on the wire, and bounded by the same number the entries
        # are: a stage cannot declare more entities than it can hold rows, so anything larger is
        # not a client this server has to serve.
        if len(self.emptiedEntities) > MAX_STAGE_ROWS:
            raise ValueError(f"a stage may name at most {MAX_STAGE_ROWS} emptied entities")
        # THE RESERVED CONTAINER CAN NEVER BE EMPTIED THIS WAY, refused here rather than trusted to
        # stay unreachable. The sweep is `(touched | emptiedEntities) & collection_keys` and
        # `collection_keys` comes from the registry's own entities, so `_custom` cannot enter it
        # TODAY — but the sweep's own comment records what a widening of that set costs: the flagship
        # workshop lost four cost sheets, two buyer links and six prototypes to a sweep that reached
        # entities the payload never named. A workshop's whole custom record is one row, so the same
        # mistake here would soft-delete every designer-defined answer on a stage in one statement.
        # A client with no business naming it is told so instead of being quietly ignored.
        reserved = [key for key in self.emptiedEntities if key.startswith("_")]
        if reserved:
            raise ValueError(
                f"{', '.join(sorted(reserved))} is not a collection this stage can empty — keys "
                f"beginning with an underscore belong to the sync protocol itself. A workshop's "
                f"own custom answers are cleared by sending an empty container for them, never by "
                f"naming them here."
            )
        return self


class ReportGenerateIn(APIModel):
    """Ask the server to render a report. **ONE FILE PER REQUEST.**

    ``formats`` IS A LIST THAT MUST HOLD EXACTLY ONE TOKEN, and the shape is kept — rather than
    narrowed to a plain string — only because both shipped clients and every recorded export already
    send an array. This docstring used to say the opposite, in the field's old name: that the list
    existed so "give me both, I am about to submit them" was one request and one consistent
    snapshot. **The route never honoured a word of it.** ``POST /{id}/report`` reads
    ``payload.formats[0]``, renders once, sets one content-disposition, writes one ``DwReportExport``
    row and returns one body — and because ``_known_formats`` sorts the set, ``["PDF", "DOCX"]``
    normalised to ``["DOCX", "PDF"]`` and the PDF was ALWAYS the casualty. A caller following this
    docstring got HTTP 200, a .docx indistinguishable from a single-format request, no mention of
    the dropped format in the body or in ``x-report-warnings``, and an export ledger recording one
    file for a request that asked for two.

    So the contract is narrowed to what the code does, rather than half-supported: a second token is
    a 422 naming the two requests to make. Honouring the old promise instead would mean a container
    response (a zip, or two base64 blobs) and a second export row, which changes the content-type
    contract for every existing caller — not something to do to rescue a sentence. Nothing shipped
    is affected: the web sends ``formats: [format]``, Android renders on device, and every test in
    this repository sends one element.
    """

    templateId: str | None = Field(default=None, max_length=48)
    formats: list[str] = Field(default_factory=lambda: ["DOCX"])
    pageSize: str | None = Field(default=None, max_length=16)
    themeAccent: str | None = Field(
        default=None,
        max_length=9,
        description=(
            "The report's accent colour as #RRGGBB, for this file only. Every other colour in "
            "the document — the soft accent, the rules, the zebra fill and the table header's "
            "text — is derived from it, so one value recolours the whole report coherently. "
            "Omitted means 'whatever stage 20 says', and a stage that says nothing leaves the "
            "template's own colour alone. A malformed value is IGNORED rather than rejected: a "
            "designer waiting on a report must not be handed a 422 because a colour string was "
            "wrong, and see ``report_theme.theme_from_accent`` for what it falls back to."
        ),
    )
    fontPreset: str | None = Field(
        default=None,
        max_length=32,
        description=(
            "The report's typeface, for this file only, as one of REPORT_FONT's keys. Omitted "
            "means 'whatever stage 20 says'. It reaches the .docx, which is the file that is "
            "submitted; the PDF must embed a face that can draw Odia, Devanagari and the rupee "
            "sign, so a choice this server cannot honour there comes back as a warning rather "
            "than as a silently different-looking document. An unknown token is IGNORED rather "
            "than rejected, for the same reason a malformed themeAccent is."
        ),
    )
    headerText: str | None = Field(default=None, max_length=180)
    footerText: str | None = Field(default=None, max_length=180)
    includePhotographs: bool | None = None
    includeTranscripts: bool | None = Field(
        default=None,
        description=(
            "Append an annexure with every transcript the workshop's recordings produced. "
            "Omitted means 'whatever stage 20 says'; sending it explicitly overrides that for "
            "this one file, so a designer can produce a short copy for the meeting and a full "
            "copy for the file without editing their saved settings in between."
        ),
    )
    includeAiLayers: bool = Field(
        default=False,
        description=(
            "Append an annexure carrying every AI layer a person has ACCEPTED for this workshop, "
            "each named as machine-assisted text and each carrying the tier, the model and the "
            "person who accepted it.\n\n"
            "DEFAULTS TO FALSE, and it is the one report option that is not tri-state. Every other "
            "toggle reads 'absent means leave the template alone', because a template that has "
            "always printed a table of contents must go on printing one for a workshop saved "
            "before the toggle existed. There is no such history here: no template carries this "
            "section, so absent and false are the same instruction and there is nothing to "
            "preserve. It is also the honest default — an annexure of model prose is not something "
            "that should happen TO a report.\n\n"
            "Nothing unaccepted is ever printed, whatever this says: acceptance is a person putting "
            "their name to text that will enter a government document, and it is checked again at "
            "the point of rendering. A report asked for the annexure with nothing accepted comes "
            "back unchanged, with a warning saying why."
        ),
    )
    record: bool = Field(
        default=True,
        description="Record the export against stage 20, so every generated file is traceable.",
    )

    @model_validator(mode="after")
    def _known_formats(self) -> "ReportGenerateIn":
        allowed = {"DOCX", "PDF"}
        wanted = {f.upper() for f in self.formats}
        if not wanted:
            raise ValueError("choose at least one format")
        unknown = wanted - allowed
        if unknown:
            raise ValueError(f"unknown format(s): {', '.join(sorted(unknown))}")
        if len(wanted) > 1:
            # THE REFUSAL THAT REPLACES A SILENT DROP. The handler renders `formats[0]` and returns
            # one body; before this line a two-format request 200'd with half the answer and nothing
            # anywhere — not the response, not the warnings header, not the export ledger — recording
            # that the second file had never been made. A 422 a client can act on is the honest
            # answer to a request this endpoint cannot serve, and it names the remedy because a
            # designer's integration script is the caller that will hit it.
            raise ValueError(
                "ask for one format per request: this endpoint returns a single file. Send "
                '{"formats": ["DOCX"]} and {"formats": ["PDF"]} as two requests.'
            )
        # Still `sorted(...)` over the set, on a one-element list, so the stored value is normalised
        # (upper-cased, de-duplicated) exactly as it always was.
        self.formats = sorted(wanted)
        return self


class ExportRecordIn(APIModel):
    """Record a report the PHONE generated, so an offline export is traceable too.

    The device produced the file with no network; this is how the fact reaches the server when
    it next has one. The bytes are not uploaded — only the fact, the checksum and the size — so
    a designer on a metered connection is not charged for a thirty-megabyte report to prove one
    was made.
    """

    format: str = Field(max_length=8)
    templateId: str = Field(max_length=48)
    fileName: str = Field(min_length=1, max_length=220)
    generatedAt: str = Field(max_length=32)
    fileSizeBytes: int | None = Field(default=None, ge=0)
    pageCount: int | None = Field(default=None, ge=0)
    checksumSha256: str | None = Field(default=None, max_length=64)
    #: Capped for the same reason `notes` is, and read back the same way: `GET /{id}/exports`
    #: returns up to 100 rows and every one of them carries this string.
    warnings: str | None = Field(
        default=None, max_length=MAX_EXPORT_WARNINGS * MAX_EXPORT_WARNING_CHARS
    )


# --------------------------------------------------------------------------------------
# AI layers
#
# Step 2 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5. The rules these bodies serve are in
# app/services/ai_layers.py; what matters HERE is what they deliberately do not accept.
# --------------------------------------------------------------------------------------

#: How long a note on an acceptance or a withdrawal may be.
#:
#: Capped for the reason `notes` and `warnings` above are: the note is read back on every listing of
#: a layer's history, so an uncapped one is paid for by every later reader rather than by the writer.
#: 500 characters is a full paragraph — "the model invented a village name in the third answer" and
#: then some — and the acceptance itself is the record; this is the margin note beside it.
MAX_LAYER_NOTE_CHARS = 500


class AiLayerRegisterIn(APIModel):
    """Record an existing transcript as a Tier 3 raw layer, with whatever provenance is known.

    **THERE IS NO ``text`` FIELD HERE, AND THAT IS THE MOST IMPORTANT THING ABOUT THIS BODY.** The
    content is read by the server from ``MediaFile.transcriptText`` — the transcript its own provider
    chain produced — and never from the request. A ``text`` field would turn this endpoint into a
    way for any client to post model prose into a workshop record under a provenance of its own
    choosing, which is the exact opposite of what the layering law is for. Generation arrives in
    steps 7 and 8 of the plan, behind acceptance and gated on measured memory; until then no AI text
    enters this system through an HTTP body.

    ``kind`` and ``tier`` are absent for the same reason and are fixed by the route: what is being
    registered is a transcript this server produced in the cloud, so RAW_TRANSCRIPT and TIER_3 are
    facts about it rather than choices a client should get to state. A body that could claim
    ``TIER_1`` for cloud output would make the tier column — the one thing that lets a reviewer tell
    a phone-produced transcript from a cloud-produced one — worth nothing.

    ``provider`` and ``modelId`` are optional HERE and required in the service, which is not a
    contradiction: the route passes the literal ``UNRECORDED`` when they are absent, deliberately and
    in one place, because ``media_queue._transcript_write`` stores no provider and nobody can supply
    one after the fact without guessing. They are accepted at all for the one caller who does know —
    an operator backfilling a period when a single provider was configured.
    """

    sourceMediaId: str = Field(
        min_length=1,
        max_length=64,
        description=(
            "The recording whose transcript this layer records. It must be attached to an audio "
            "field of this workshop and readable by the caller; both are checked."
        ),
    )
    provider: str | None = Field(default=None, max_length=64)
    modelId: str | None = Field(default=None, max_length=120)
    modelVersion: str | None = Field(default=None, max_length=120)
    language: str | None = Field(
        default=None,
        max_length=32,
        description=(
            "The language of the transcript. 'multi' is a real answer, not a placeholder: Deepgram "
            "Nova-3 is called with language=multi because a workshop is Hindi code-switched with "
            "English mid-sentence. Omitted means nobody recorded it."
        ),
    )
    producedAt: str | None = Field(
        default=None,
        max_length=32,
        description=(
            "When the MODEL ran, if it is known — not when this row is being written, which the "
            "server records itself. Omitted leaves it null rather than guessing at today."
        ),
    )

    @model_validator(mode="after")
    def _producedAt_is_a_real_moment(self) -> "AiLayerRegisterIn":
        """Refuse a timestamp that cannot be parsed instead of quietly dropping it.

        The sibling ``ExportRecordIn.generatedAt`` is parsed with a helper that returns None on a
        malformed value and falls back to now(), which is right there — an export's time is
        approximately now by definition. It is wrong here. This column is provenance: a client that
        SAYS when the model ran and is silently recorded as having said nothing produces a layer
        that claims its production time is unknown when it was in fact stated and mistyped. That is
        a fabricated unknown, which is the same defect as a fabricated fact.
        """
        if self.producedAt is None:
            return self
        try:
            # A trailing "Z" needs no substitution: fromisoformat has accepted it since 3.11, and
            # `requires-python` is >=3.11. The route's older `_parse_datetime` still rewrites it
            # because it predates that and is shared with paths this validator does not cover.
            datetime.fromisoformat(str(self.producedAt))
        except ValueError:
            raise ValueError(
                "producedAt must be an ISO-8601 moment such as 2026-03-04T11:20:00Z. Leave it out "
                "entirely if the time the model ran is not known — that is recorded honestly as "
                "unknown."
            ) from None
        return self


# --------------------------------------------------------------------------------------
# Designer-defined sections
#
# Step 6 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5. Every rule these bodies serve is in
# app/services/custom_sections.py; what these do is bound the request and refuse a typo.
# --------------------------------------------------------------------------------------


class CustomOptionIn(APIModel):
    """One option of a designer's own choice list.

    ``label`` is optional and defaults to the value, which is the honest behaviour rather than a
    convenience: an option a designer typed as ``COTTON`` with no label prints as ``COTTON``, and
    the alternative — inventing "Cotton" from the token — would be guessing at a word that goes
    into a document submitted to a ministry.
    """

    value: str = Field(min_length=1, max_length=64)
    label: str = Field(default="", max_length=MAX_CUSTOM_LABEL_CHARS)


class CustomFieldIn(APIModel):
    """One designer-defined field, as the definition editor sends it.

    **THE ENVELOPE IS STRICT HERE AND THE STAGE PAYLOAD IS NOT, AND THAT IS THE WHOLE DIFFERENCE
    BETWEEN THE TWO HALVES OF THIS FEATURE.** ``APIModel`` sets ``extra="forbid"``, so a typo in a
    definition key is a 422 — which is right, because a definition is written at a keyboard by
    somebody who can see the response, and a silently ignored ``requred: true`` is a required field
    that never becomes required. The ANSWERS travel on ``StageEntryIn.data``, which stays an open
    dict for the reason this module's docstring gives: a village, a draft two weeks old, and a
    submission that must not be lost over one key.

    Nothing here re-states a rule ``services/custom_sections.validate_definition`` enforces. The
    lengths and counts below are the same constants that module declares, imported rather than
    repeated, because a bound the schema and the service disagreed about would be a 422 from one of
    them saying something the other's message contradicts.
    """

    key: str = Field(min_length=1, max_length=MAX_CUSTOM_KEY_CHARS)
    label: str = Field(min_length=1, max_length=MAX_CUSTOM_LABEL_CHARS)
    type: str = Field(max_length=24)
    tier: str = Field(default="STANDARD", max_length=16)
    required: bool = False
    help: str = Field(default="", max_length=MAX_CUSTOM_HELP_CHARS)
    unit: str = Field(default="", max_length=MAX_CUSTOM_UNIT_CHARS)
    options: list[CustomOptionIn] = Field(default_factory=list, max_length=MAX_CUSTOM_OPTIONS)
    maxLength: int = Field(default=0, ge=0, le=100_000)
    minValue: float | None = None
    maxValue: float | None = None
    sortOrder: int = Field(default=0, ge=0, le=10_000)


class CustomSectionIn(APIModel):
    """One block of designer-defined questions, attached to one stage of one workshop."""

    key: str = Field(min_length=1, max_length=MAX_CUSTOM_KEY_CHARS)
    title: str = Field(min_length=1, max_length=MAX_CUSTOM_TITLE_CHARS)
    stageKey: str = Field(
        min_length=1,
        max_length=64,
        description=(
            "The stage these questions are asked at. Required, and it is where the answers are "
            "stored — a section with no stage would have no row to put them in. Where the section "
            "PRINTS is a separate question the report template answers: a template that does not "
            "print that stage prints the section as its own annexure."
        ),
    )
    description: str = Field(default="", max_length=MAX_CUSTOM_DESCRIPTION_CHARS)
    sortOrder: int = Field(default=0, ge=0, le=10_000)
    fields: list[CustomFieldIn] = Field(
        default_factory=list, max_length=MAX_CUSTOM_FIELDS_PER_SECTION
    )


class CustomSectionsIn(APIModel):
    """A workshop's WHOLE custom definition, replaced in one request.

    A whole-set PUT and not a per-field PATCH, matching the viewer-roster idiom, and for a reason
    beyond consistency: "one definition, one digest" has to be atomic. ``customSchemaVersion`` is
    what every client compares its cached copy against, and a definition assembled from six
    independent PATCHes has six intermediate digests, each of which some handset can fetch and cache
    as though it were the definition.

    A REPLACE IS NOT A DELETE. What is absent from this body is retired if it has answers and
    removed if it does not — see ``services/custom_sections`` for the rule and for the failure it
    prevents. Sending an empty list is therefore a legitimate way to stop asking every custom
    question, and it destroys no recorded answer.
    """

    sections: list[CustomSectionIn] = Field(default_factory=list, max_length=MAX_CUSTOM_SECTIONS)
    customSchemaVersion: str | None = Field(
        default=None,
        max_length=64,
        description=(
            "The digest this client loaded, sent back so a whole-set PUT cannot silently overwrite "
            "a definition it never saw. When it is present and does not match the stored digest, "
            "the write is refused with 409 and both digests are named.\n\n"
            "OPTIONAL, AND THAT IS THE POINT. A whole-set replace is last-write-wins by "
            "construction: two designers editing one workshop's questions, or one designer with a "
            "tab open from before lunch, otherwise delete each other's work under a 200 with no "
            "warning on either screen — measured on the wire, and the second designer's section "
            "was REMOVED rather than retired, correctly by the service's own rule, because nothing "
            "had answered it yet. Making the field REQUIRED would have been the stronger guarantee "
            "and is deliberately not what this does: every already-shipped handset and browser "
            "would start failing ``extra='forbid'``-clean requests the day it landed. Optional "
            "means a client that knows about the check gets it, and a client that does not is "
            "exactly as safe as it was yesterday.\n\n"
            "Send back what ``GET`` (or the previous ``PUT``) returned under this same name. Do "
            "not compute it: it is a digest of the stored definition including retired fields, and "
            "a client that derives its own will disagree with the server the first time a field is "
            "superseded."
        ),
    )


# --------------------------------------------------------------------------------------
# The AI verbs
#
# Steps 7 and 8 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5. Every rule these bodies serve is in
# app/services/ai_verbs.py and app/services/ai_layers.py; what matters HERE is what they accept and,
# more importantly, what they refuse to accept.
# --------------------------------------------------------------------------------------

#: The longest passage a verb may be asked to work on, in characters.
#:
#: THE SAME NUMBER AS ``ai_layers.MAX_SOURCE_TEXT_CHARS`` AND IMPORTED FROM IT rather than repeated,
#: because a bound the schema and the service disagreed about would be a 422 from one of them saying
#: something the other's message contradicts — which is the reasoning ``CustomFieldIn`` already
#: applies to its own limits one class up. The service's refusal is the one with the argument in it
#: (a proofread of the first ten pages of a twelve-page note, recorded as a proofread of the note, is
#: a layer whose source is not what it says); this is the cheap check that stops the body being
#: parsed at all.
MAX_VERB_TEXT_CHARS = _MAX_SOURCE_TEXT_CHARS


# **THE ``text`` ON THESE BODIES IS THE VERB'S INPUT, WHICH IS THE EXACT OPPOSITE OF THE FIELD
# ``AiLayerRegisterIn`` REFUSES**, and the distinction is stated here rather than per body because the
# two look identical on a wire and one of them would be a hole through the whole layering law.
#
# That body has no ``text`` because it REGISTERS a layer: a ``text`` there would let any client post
# model prose into a workshop record under a provenance of its own choosing — a machine's words with a
# tier, a provider and a model id the CLIENT picked. These bodies have ``text`` because they ask the
# SERVER to run a model, and the layer's content is whatever the server's own call returned, with the
# provider and model id the server observed. A client chooses what to have proofread; it cannot choose
# what the proofreading says, and it cannot claim anything about what produced it.
#
# THERE IS DELIBERATELY NO ``tier`` FIELD ON ANY OF THEM, for that body's reason: the tier is a fact
# about where the model ran, which the server observes and a client may not assert. A body that could
# claim TIER_1 for cloud output would make the tier column worthless to the reviewer it exists for.


class AiProofreadIn(APIModel):
    """What to proofread: either a stored layer, or words sent with the request.

    ONE BODY WITH TWO SHAPES RATHER THAN TWO ROUTES, because it is one verb and one meter entry, and
    two routes would be two places for the consent gate and the ceiling to be checked — which is one
    place too many for a gate. Exactly one of the two must be sent and the validator says so.
    """

    text: str | None = Field(default=None, max_length=MAX_VERB_TEXT_CHARS)
    language: str | None = Field(default=None, max_length=40)
    sourceLayerId: str | None = Field(default=None, max_length=64)

    @model_validator(mode="after")
    def _exactly_one_source(self) -> "AiProofreadIn":
        return _require_exactly_one_source(self, verb="proofread")


class AiExpandIn(APIModel):
    """A designer's terse note, to be written out into prose. **The highest-risk body in this file.**

    **IT TAKES ONLY ``text``, AND THE ABSENCE OF ``sourceLayerId`` IS THE POINT.** An expansion
    invents sentences; run over the designer's own shorthand it turns their note into their prose and
    they are standing there to judge it, but run over an artisan's transcript it would put invented
    words in a named person's mouth in a document a ministry officer reads. No acceptance screen can
    make that safe, because the person accepting it is not the person being quoted. The service
    refuses it in ``TEXT_ROOTED_KINDS`` and ``ai_verbs.expand`` cannot express it; this body means a
    client cannot even ask.

    **AND THE RESULT IS NEVER A FIELD VALUE.** It is a layer: it appears beside the note, named as
    machine-written, and reaches a document only through the annexure, only once somebody accepts it,
    and only with a caution printed above it. A designer who wants those words in the field types
    them — at which point they are that designer's sentences under that designer's name, which is a
    true statement that no paste button could produce. See ``ai_verbs.expand`` for the full argument
    and for the two client precedents.
    """

    text: str = Field(min_length=1, max_length=MAX_VERB_TEXT_CHARS)
    language: str | None = Field(default=None, max_length=40)


class AiTranslateIn(APIModel):
    """What to translate, and into what. **The original is never touched.**

    ``targetLanguage`` IS REQUIRED AND ``sourceLanguage`` IS NOT, which is not an inconsistency. The
    target is a CHOICE the caller makes and only they can make it. The source is an OBSERVATION, and
    the run may have detected one — Scribe reports a language code, Deepgram is deliberately called
    with ``language=multi`` — so a caller who does not know may leave it out and the server records
    what the run knew, or ``UNRECORDED`` in that word if it knew nothing. What the server will not do
    is default it to English.
    """

    text: str | None = Field(default=None, max_length=MAX_VERB_TEXT_CHARS)
    sourceLayerId: str | None = Field(default=None, max_length=64)
    targetLanguage: str = Field(
        min_length=1,
        max_length=40,
        description=(
            "The language to translate INTO — a name or a code ('Odia', 'or', 'English'). 'multi' "
            "is refused here: it is something a recording can BE, not something a translation can "
            "be into."
        ),
    )
    sourceLanguage: str | None = Field(
        default=None,
        max_length=40,
        description=(
            "The language the passage is in, if it is known. 'multi' is a real answer. Omitted "
            "means the server records what the run detected, or that nobody recorded it."
        ),
    )

    @model_validator(mode="after")
    def _exactly_one_source(self) -> "AiTranslateIn":
        return _require_exactly_one_source(self, verb="translate")


class AiMediaVerbIn(APIModel):
    """A recording or photograph for a media verb. Caption and subtitles take this.

    The media id is checked against the workshop's own attached files and against the caller's media
    entitlement before anything is sent anywhere — ``GET /api/media`` hands every signed-in account
    the id of every file in the repository, so an id on a request body is a claim and never an
    authorisation.
    """

    sourceMediaId: str = Field(
        min_length=1,
        max_length=64,
        description=(
            "The photograph, video or recording to work on. It must be attached to a field of this "
            "workshop and must be one the caller is entitled to read."
        ),
    )
    language: str | None = Field(
        default=None,
        max_length=40,
        description=(
            "For a caption: the language to write it in, if not the model's own. It is ASKED OF "
            "the model and then recorded on the layer, never recorded without being asked for — a "
            "row whose language column says Odia over an English sentence is a fabricated "
            "provenance record. 'multi' is ignored here rather than refused: it is something a "
            "RECORDING can be and not something one sentence can be written in. Subtitles ignore "
            "this field entirely — a cue list is in whatever language was spoken."
        ),
    )


def _require_exactly_one_source(body: Any, *, verb: str) -> Any:
    """Refuse a verb body that names two sources or none.

    Shared by the two bodies that accept either shape, so the sentence cannot drift between them —
    and refused HERE as well as in ``LayerSource``, because a 422 from the schema names the two
    fields while the service's refusal names the row shape. Both are worth having; only one of them
    reaches a client that sent neither field.
    """
    has_text = bool((body.text or "").strip())
    has_layer = bool((body.sourceLayerId or "").strip())
    if has_text and has_layer:
        raise ValueError(
            f"Send either the words to {verb} or the id of a layer to {verb}, not both — a result "
            f"whose source nobody can determine cannot be printed or checked."
        )
    if not has_text and not has_layer:
        raise ValueError(
            f"There is nothing to {verb}. Send `text` with the passage, or `sourceLayerId` with the "
            f"layer whose text you want worked on."
        )
    return body


class AiLayerDecisionIn(APIModel):
    """Why a person accepted a layer, or why they took their name off it. Optional either way.

    Accepting needs no explanation — "I read it and it is right" is the whole message — but a
    WITHDRAWAL usually has a reason worth keeping, and the reason is what stops the same layer being
    re-accepted by somebody who was not told.
    """

    note: str | None = Field(default=None, max_length=MAX_LAYER_NOTE_CHARS)


# --------------------------------------------------------------------------------------
# Tier 3 consent
#
# Plan §6 answer 3. Every rule this body serves is in app/services/dictation_consent.py; what it does
# here is bound the request and refuse a decision nobody can record.
# --------------------------------------------------------------------------------------


class DictationConsentIn(APIModel):
    """One workshop's answer to "may its recordings leave the device", as the designer records it.

    **``NOT_RECORDED`` IS NOT AN ACCEPTED VALUE, and the omission is the point.** It is what a workshop
    says before anybody has asked, not something a person can record — "somebody deliberately wrote
    down that nobody has been asked" is not a state anybody is in. Taking a consent back is recording
    REFUSED, which is a decision with a next move and a row in the log; un-recording one would leave a
    gate unable to tell a withdrawn consent from a workshop nobody has opened. The service refuses it
    too, so a caller reaching past this schema gets a sentence rather than a silent write.

    There is deliberately no ``actor`` field. Who recorded the answer is the authenticated caller and
    nothing else: a body that could name somebody else would let one designer file an artisan's consent
    under a colleague's name, which is the one fact about this row that has to be true.
    """

    decision: str = Field(
        max_length=16,
        description="GRANTED if the artisan agreed that recordings may be sent, REFUSED if they did not.",
    )
    note: str | None = Field(
        default=None,
        max_length=MAX_LAYER_NOTE_CHARS,
        description=(
            "Why, or under what circumstances. Optional: 'the artisan agreed' needs no note, but a "
            "refusal often has a reason worth keeping."
        ),
    )
    recordedAt: str | None = Field(
        default=None,
        max_length=32,
        description=(
            "WHEN THE ARTISAN ANSWERED, if that is not now. A consent recorded in a courtyard with no "
            "signal reaches this server on the next sync, which can be a fortnight later, and the "
            "moment that matters is the courtyard one. Omitted means the answer is being recorded "
            "against the server now, and the server's own clock is used. A time in the future is "
            "refused rather than corrected — when somebody consented is not something this server may "
            "guess at."
        ),
    )

    @model_validator(mode="after")
    def _a_decision_somebody_can_record(self) -> "DictationConsentIn":
        """Refuse an unknown token, and refuse NOT_RECORDED by name so the message can say why.

        The token reaches a Postgres enum column, so anything outside the three values was not merely
        stored wrong — Prisma refuses it and the route answered a bare 500, which reads to a client as
        "the server is broken" rather than "that is not an answer". This is the same failure
        ``DesignWorkshopUpdate._known_status_and_template`` was written for, one column over.
        """
        token = (self.decision or "").strip().upper()
        if token == "NOT_RECORDED":
            raise ValueError(
                "NOT_RECORDED is what a workshop says before anybody has asked, not an answer "
                "somebody can record. Send GRANTED if the artisan agreed, or REFUSED if they did "
                "not — REFUSED is also how a consent already given is taken back."
            )
        if token not in {"GRANTED", "REFUSED"}:
            raise ValueError(
                "decision must be GRANTED or REFUSED — whether the artisan agreed that this "
                "workshop's recordings may be sent to the transcription service."
            )
        self.decision = token
        return self

    @model_validator(mode="after")
    def _recordedAt_is_a_real_moment(self) -> "DictationConsentIn":
        """Refuse a timestamp that cannot be parsed instead of quietly dropping it.

        ``ExportRecordIn.generatedAt`` is parsed with a helper that returns None on a malformed value
        and falls back to now(), which is right there — an export's time is approximately now by
        definition. It is wrong here, for ``AiLayerRegisterIn.producedAt``'s reason: a client that SAYS
        when the artisan answered and is silently recorded as having said nothing produces a consent
        dated to the sync. That is a signature dated to the day it was filed.
        """
        if self.recordedAt is None:
            return self
        try:
            datetime.fromisoformat(str(self.recordedAt))
        except ValueError:
            raise ValueError(
                "recordedAt must be an ISO-8601 moment such as 2026-08-11T16:40:00+05:30. Leave it "
                "out entirely if the answer is being recorded now — the server's own clock is used "
                "then, which is the honest reading."
            ) from None
        return self
