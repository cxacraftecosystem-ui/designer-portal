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

DESIGN_WORKSHOP_STATUSES = frozenset(
    {
        "DRAFT",
        "IN_PROGRESS",
        "COMPLETE",
        "PRE_SUBMISSION",
        "NEEDS_REVISION",
        "SUBMITTED",
        "APPROVED",
        "ARCHIVED",
    }
)
REPORT_TEMPLATE_IDS = frozenset(t.id for t in TEMPLATES)

WORKSHOP_KINDS = frozenset(ENUMS["WORKSHOP_KIND"])

MAX_STAGE_ROWS = 500
MAX_FIELD_KEYS = 400

MAX_NOTES_CHARS = 20_000

MAX_EXPORT_WARNINGS = 40
MAX_EXPORT_WARNING_CHARS = 400


class DesignWorkshopCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    templateId: str = Field(default="DCH_STANDARD", max_length=48)
    workshopKind: str | None = Field(default=None, max_length=48)
    designerUserId: str | None = Field(
        default=None,
        max_length=64,
        description=(
            "The designer this workshop is FOR. Their DesignerProfile is copied into stage 1 and "
            "stage 3, and they are granted access in the same call. Omit if not yet known."
        ),
    )
    designerUserIds: list[Annotated[str, Field(max_length=64)]] | None = Field(
        default=None,
        max_length=MAX_DESIGN_WORKSHOP_VIEWERS,
        description=(
            "Every designer this workshop is for, the lead included. Each is granted access in "
            "this same call, and the workshop is visible to them and to admins and nobody else. "
            "Omit if not yet known."
        ),
    )
    craftId: str | None = Field(default=None, max_length=64)
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
    def _known_template(self) -> DesignWorkshopCreate:
        if self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}")
        if self.workshopKind is not None and self.workshopKind not in WORKSHOP_KINDS:
            raise ValueError(f"workshopKind must be one of {', '.join(sorted(WORKSHOP_KINDS))}")
        return self


class DesignWorkshopUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    templateId: str | None = Field(default=None, max_length=48)
    workshopKind: str | None = Field(default=None, max_length=48)
    craftId: str | None = Field(default=None, max_length=64)
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
    def _known_status_and_template(self) -> DesignWorkshopUpdate:
        if self.status is not None and self.status not in DESIGN_WORKSHOP_STATUSES:
            raise ValueError(f"status must be one of {', '.join(sorted(DESIGN_WORKSHOP_STATUSES))}")
        if self.templateId is not None and self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}")
        if self.workshopKind is not None and self.workshopKind not in WORKSHOP_KINDS:
            raise ValueError(f"workshopKind must be one of {', '.join(sorted(WORKSHOP_KINDS))}")
        return self


class StageEntryIn(APIModel):
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
    def _bound_payload(self) -> StageEntryIn:
        if len(self.data) > MAX_FIELD_KEYS:
            raise ValueError(f"a stage entry may carry at most {MAX_FIELD_KEYS} field keys")
        return self


class StageSaveIn(APIModel):
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
    copiedFrom: dict[str, str] = Field(
        default_factory=dict,
        max_length=64,
        description=(
            "C9: singleton field key -> the id of the caller's own earlier workshop the text was "
            "copied from. Verified against the source; a copied field counts toward completeness "
            "only once it is edited or confirmed. Optional and additive."
        ),
    )
    confirmCopied: list[str] = Field(
        default_factory=list,
        max_length=64,
        description="C9: copied fields the designer has checked and keeps as they are.",
    )

    @model_validator(mode="after")
    def _bound_rows(self) -> StageSaveIn:
        if len(self.entries) > MAX_STAGE_ROWS:
            raise ValueError(f"a stage may carry at most {MAX_STAGE_ROWS} entries per request")
        if len(self.emptiedEntities) > MAX_STAGE_ROWS:
            raise ValueError(f"a stage may name at most {MAX_STAGE_ROWS} emptied entities")
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
    fresh: bool = Field(
        default=False,
        description=(
            "Build the file again even when an identical one is stored. Without it the approving "
            "authority's door serves the stored file of an unchanged report."
        ),
    )

    @model_validator(mode="after")
    def _known_formats(self) -> ReportGenerateIn:
        allowed = {"DOCX", "PDF"}
        wanted = {f.upper() for f in self.formats}
        if not wanted:
            raise ValueError("choose at least one format")
        unknown = wanted - allowed
        if unknown:
            raise ValueError(f"unknown format(s): {', '.join(sorted(unknown))}")
        if len(wanted) > 1:
            raise ValueError(
                "Choose one format at a time: the Word document and the PDF are downloaded "
                "separately."
            )
        self.formats = sorted(wanted)
        return self


class ExportRecordIn(APIModel):
    format: str = Field(max_length=8)
    templateId: str = Field(max_length=48)
    fileName: str = Field(min_length=1, max_length=220)
    generatedAt: str = Field(max_length=32)
    fileSizeBytes: int | None = Field(default=None, ge=0)
    pageCount: int | None = Field(default=None, ge=0)
    checksumSha256: str | None = Field(default=None, max_length=64)
    warnings: str | None = Field(
        default=None, max_length=MAX_EXPORT_WARNINGS * MAX_EXPORT_WARNING_CHARS
    )


MAX_LAYER_NOTE_CHARS = 500


class AiLayerRegisterIn(APIModel):
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
    def _producedAt_is_a_real_moment(self) -> AiLayerRegisterIn:
        if self.producedAt is None:
            return self
        try:
            datetime.fromisoformat(str(self.producedAt))
        except ValueError:
            raise ValueError(
                "producedAt must be a date and time such as 2026-03-04T11:20:00Z. Leave it out if "
                "the time is not known."
            ) from None
        return self



class CustomOptionIn(APIModel):
    value: str = Field(min_length=1, max_length=64)
    label: str = Field(default="", max_length=MAX_CUSTOM_LABEL_CHARS)


class CustomFieldIn(APIModel):
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


MAX_VERB_TEXT_CHARS = _MAX_SOURCE_TEXT_CHARS



class AiProofreadIn(APIModel):
    text: str | None = Field(default=None, max_length=MAX_VERB_TEXT_CHARS)
    language: str | None = Field(default=None, max_length=40)
    sourceLayerId: str | None = Field(default=None, max_length=64)

    @model_validator(mode="after")
    def _exactly_one_source(self) -> AiProofreadIn:
        return _require_exactly_one_source(self, verb="proofread")


class AiExpandIn(APIModel):
    text: str = Field(min_length=1, max_length=MAX_VERB_TEXT_CHARS)
    language: str | None = Field(default=None, max_length=40)


class AiTranslateIn(APIModel):
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
    def _exactly_one_source(self) -> AiTranslateIn:
        return _require_exactly_one_source(self, verb="translate")


class AiOnDeviceLayerIn(APIModel):
    kind: Annotated[str, Field(pattern="^(PROOFREAD|TRANSLATION)$")]
    text: str = Field(min_length=1, max_length=MAX_VERB_TEXT_CHARS)
    provider: Annotated[str, Field(pattern="^litert-lm$")]
    modelId: str = Field(min_length=1, max_length=120)
    modelVersion: str = Field(min_length=1, max_length=120)
    language: str = Field(min_length=1, max_length=40)
    producedAt: str = Field(min_length=1, max_length=40)
    sourceText: str = Field(min_length=1, max_length=MAX_VERB_TEXT_CHARS)

    @model_validator(mode="after")
    def _a_real_moment_and_a_pinned_model(self) -> AiOnDeviceLayerIn:
        try:
            datetime.fromisoformat(str(self.producedAt))
        except ValueError:
            raise ValueError(
                "producedAt must be an ISO-8601 moment such as 2026-03-04T11:20:00+05:30."
            ) from None
        if not self.text.strip() or not self.sourceText.strip():
            raise ValueError("A layer needs words, and the passage it was made from.")
        return self


class AiMediaVerbIn(APIModel):
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
    note: str | None = Field(default=None, max_length=MAX_LAYER_NOTE_CHARS)



class DictationConsentIn(APIModel):
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
    def _a_decision_somebody_can_record(self) -> DictationConsentIn:
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
    def _recordedAt_is_a_real_moment(self) -> DictationConsentIn:
        if self.recordedAt is None:
            return self
        try:
            datetime.fromisoformat(str(self.recordedAt))
        except ValueError:
            raise ValueError(
                "recordedAt must be a date and time such as 2026-08-11T16:40:00+05:30. Leave it "
                "out if the answer is being recorded now."
            ) from None
        return self
