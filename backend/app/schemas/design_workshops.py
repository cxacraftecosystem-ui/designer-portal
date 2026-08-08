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

from typing import Any

from pydantic import Field, model_validator

from app.schemas.common import APIModel
from app.services.report_templates import TEMPLATES

# The DesignWorkshopStatus enum, mirrored from schema.prisma. Kept as a frozenset here
# rather than imported from the generated Prisma client so validating a request body does
# not require the client to have been generated.
DESIGN_WORKSHOP_STATUSES = frozenset(
    {"DRAFT", "IN_PROGRESS", "COMPLETE", "SUBMITTED", "ARCHIVED"}
)
REPORT_TEMPLATE_IDS = frozenset(t.id for t in TEMPLATES)

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
    craftName: str | None = Field(default=None, max_length=160)
    clusterName: str | None = Field(default=None, max_length=160)
    state: str | None = Field(default=None, max_length=80)
    district: str | None = Field(default=None, max_length=80)
    startDate: str | None = None
    endDate: str | None = None
    workshopId: str | None = Field(default=None, max_length=64,
                                   description="Links this design workshop to a Workshop record.")
    notes: str | None = Field(default=None, max_length=MAX_NOTES_CHARS)

    @model_validator(mode="after")
    def _known_template(self) -> "DesignWorkshopCreate":
        if self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(
                f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}"
            )
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
            raise ValueError(
                f"status must be one of {', '.join(sorted(DESIGN_WORKSHOP_STATUSES))}"
            )
        if self.templateId is not None and self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(
                f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}"
            )
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
        return self


class ReportGenerateIn(APIModel):
    """Ask the server to render a report.

    ``format`` is a list so the common case — "give me both, I am about to submit them" — is one
    request and one consistent snapshot of the data, rather than two requests that could
    straddle an edit.
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
