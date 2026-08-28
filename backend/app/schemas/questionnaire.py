from datetime import datetime
from typing import Any

from pydantic import Field, model_validator

from app.schemas.common import (
    APIModel,
    LocationInput,
    forbid_clearing_location,
    require_location,
)


class QuestionnaireSectionCreate(APIModel):
    code: str = Field(min_length=1, max_length=24)
    title: str = Field(min_length=1, max_length=220)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool = True


class QuestionnaireSectionUpdate(APIModel):
    code: str | None = Field(default=None, min_length=1, max_length=24)
    title: str | None = Field(default=None, min_length=1, max_length=220)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None


class QuestionnaireSectionReorder(APIModel):
    sectionIds: list[str] = Field(min_length=1)


class QuestionnaireQuestionCreate(APIModel):
    sectionId: str = Field(min_length=1)
    prompt: str = Field(min_length=1)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool = True


class QuestionnaireQuestionUpdate(APIModel):
    sectionId: str | None = Field(default=None, min_length=1)
    prompt: str | None = Field(default=None, min_length=1)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None


class QuestionnaireQuestionReorder(APIModel):
    sectionId: str = Field(min_length=1)
    questionIds: list[str] = Field(min_length=1)


class QuestionnaireResponseInput(APIModel):
    questionId: str = Field(min_length=1)
    answerText: str | None = None
    notes: str | None = None


class QuestionnaireInterviewCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    interviewDate: datetime | None = None
    place: str | None = None
    language: str | None = None
    notes: str | None = None
    # The workshop this interview was conducted at. Optional: an omitted workshopId behaves exactly as
    # before, while a supplied one is subject to the workshop's assignment + submission-window rules.
    workshopId: str | None = None
    # The design & prototype workshop this interview is filed under. Optional; an explicit null
    # unfiles it. Gated on write by ``record_design_workshop.assert_payload_workshop`` — see
    # ``ArtisanCreate.designWorkshopId`` in schemas/records.py for the whole argument.
    designWorkshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    artisanIds: list[str] = Field(default_factory=list)
    responses: list[QuestionnaireResponseInput] = Field(default_factory=list)
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Mandatory on create, like every other record type that carries a location.
    _location_required = model_validator(mode="after")(require_location)


class CompletionCellUpdate(APIModel):
    """Admin-set status for one (artisan, section) cell on the completion matrix. ``status=None``
    clears the manual override (falling back to data-derived completion)."""

    artisanId: str = Field(min_length=1)
    sectionId: str = Field(min_length=1)
    status: str | None = None


# ------------------------------------------------------------------------------------------------
# Custom questionnaires — the form a designer authored themselves from the .xlsx pro-forma.
#
# Deliberately NOT reusing the models above. Those describe the ONE global artisan questionnaire and
# the interviews recorded against it; these describe a form somebody built last Tuesday. Sharing a
# payload between the two would mean every future field added for one silently appearing on the
# other's API, which is the drift the separate tables exist to prevent.
# ------------------------------------------------------------------------------------------------


class CustomQuestionCreate(APIModel):
    prompt: str = Field(min_length=1, max_length=2000)
    helpText: str | None = None
    isRequired: bool = False
    sortOrder: int | None = Field(default=None, ge=1)


class CustomQuestionUpdate(APIModel):
    """A single-question edit from the editor.

    ``prompt`` on a question that already has answers does NOT overwrite it — it supersedes it, and
    the response says so. See the edit-after-answers rule in services/questionnaire_forms.py.
    """

    prompt: str | None = Field(default=None, min_length=1, max_length=2000)
    helpText: str | None = None
    isRequired: bool | None = None
    sortOrder: int | None = Field(default=None, ge=1)
    sectionId: str | None = Field(default=None, min_length=1)


class CustomSectionCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    # Optional: left blank, a stable code is derived from the title. See derive_section_code and why
    # it is derived from the title rather than the position.
    code: str | None = Field(default=None, max_length=24)
    sortOrder: int | None = Field(default=None, ge=1)
    questions: list[CustomQuestionCreate] = Field(default_factory=list)


class CustomSectionUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None


class QuestionnaireCreate(APIModel):
    """Start a questionnaire by hand, without a spreadsheet. The upload path is the other door."""

    title: str = Field(min_length=1, max_length=220)
    description: str | None = None
    # ``min_length=1`` because "" IS NOT A WORKSHOP ID and used to be read as one. Every route that
    # takes this field guards it with a plain truthiness test (``if payload.designWorkshopId:``) and
    # so SKIPS the workshop authorization check for "", then hands the empty string to Prisma as a
    # foreign key: measured, that was ``500 {"error":"ForeignKeyViolationError"}`` where a 422
    # belongs. Nothing was ever authorized by it — the FK refuses and the create is the first write,
    # so no orphan survives — but a 500 tells a client to retry something that cannot succeed.
    # ``None`` still means "not attached"; only the empty string is refused.
    designWorkshopId: str | None = Field(default=None, min_length=1)
    sections: list[CustomSectionCreate] = Field(default_factory=list)


class QuestionnaireUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    description: str | None = None
    # The attach-to-a-workshop dropdown. Sent as null to detach; omitted to leave alone — the
    # APIModel/clean_data convention used by every other record in this repo. DETACHING IS ``null``
    # AND NEVER "", which is what makes ``min_length=1`` safe here: see ``QuestionnaireCreate`` for
    # the 500 the empty string produced on all three of the routes that take this field.
    designWorkshopId: str | None = Field(default=None, min_length=1)
    isActive: bool | None = None
    #: PUBLISH THIS FORM TO EVERY DESIGNER — the "default questionnaire". **Admin only**, enforced in
    #: :func:`update_questionnaire` and not here, because a schema cannot see who is calling.
    #:
    #: Separate from ``isActive`` even though the two are read together, because they answer
    #: different questions: ``isActive`` is "is this instrument still in use at all", ``isShared`` is
    #: "is it everybody's". A retired shared form is a real state — it disappears from every
    #: designer's picker and keeps its recorded sittings — and one boolean could not express it.
    isShared: bool | None = None


class QuestionnaireReuse(APIModel):
    """Reuse an existing questionnaire as a template at another design workshop.

    THE BODY OF ``POST /questionnaires/{id}/reuse``. Every field is optional, and the endpoint with
    an EMPTY body is meaningful: it makes an unattached copy — a template the caller owns, visible
    only under ``ownerId = me`` — which is what a designer wants when they are lifting an instrument
    now and will decide which workshop it serves later.

    ``designWorkshopId`` NULL/OMITTED MEANS "DO NOT ATTACH IT YET", NOT "KEEP THE SOURCE'S
    WORKSHOP". That is the one place this model deliberately departs from ``QuestionnaireUpdate``'s
    ``clean_data`` convention, where an omitted key leaves the existing attachment alone: there is no
    existing attachment on a row that does not exist yet, and inheriting the SOURCE's workshop would
    make the default outcome "a second copy of this form at the workshop that already has one" —
    the single least likely thing anybody pressing "Reuse at another workshop" wants.

    ``title`` left unset is filled in by ``reuse_title`` — "X (reused)", counted up to
    "X (reused 2)" — rather than defaulting to the source's exact title, so two rows at one workshop
    are tellable apart in a list. 220 is ``Questionnaire.title``'s own ceiling, matching
    ``QuestionnaireCreate``.

    ``description`` behaves as a PATCH-style tri-state and the service reads it that way: unset means
    "carry the source's description across", an explicit ``null`` or ``""`` means "leave it empty".

    AN EMPTY-STRING ``designWorkshopId`` IS A 422, NOT AN ATTACHMENT AND NOT A 500. The route guards
    the workshop check with ``if payload.designWorkshopId:``, so "" skipped the check and travelled on
    to Prisma as a foreign key — measured as ``500 ForeignKeyViolationError``. No authorization was
    bypassed (the FK refuses, and the ``Questionnaire`` create is the first write, so no orphan
    survives) but a 500 invites a retry that cannot work. ``min_length=1`` is on the same field in
    ``QuestionnaireCreate`` and ``QuestionnaireUpdate`` for the same measured reason: this is a third
    door onto one defect, and fixing one door would leave the other two answering 500.
    """

    designWorkshopId: str | None = Field(default=None, min_length=1)
    title: str | None = Field(default=None, min_length=1, max_length=220)
    description: str | None = None


class QuestionnaireEntryCreate(APIModel):
    """One sitting: a filled-in copy of the questionnaire."""

    title: str | None = Field(default=None, max_length=220)
    respondentName: str | None = Field(default=None, max_length=220)
    notes: str | None = None


class QuestionnaireEntryUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    respondentName: str | None = Field(default=None, max_length=220)
    notes: str | None = None


class CustomAnswerInput(APIModel):
    questionId: str = Field(min_length=1)
    answerText: str | None = None
    notes: str | None = None


class CustomAnswerBatch(APIModel):
    """Answers for one sitting, saved a section at a time. Idempotent — re-sending is a no-op."""

    answers: list[CustomAnswerInput] = Field(default_factory=list)


class QuestionnaireInterviewUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    interviewDate: datetime | None = None
    place: str | None = None
    language: str | None = None
    notes: str | None = None
    workshopId: str | None = None
    # The design & prototype workshop this interview is filed under. Optional; an explicit null
    # unfiles it. Gated on write by ``record_design_workshop.assert_payload_workshop`` — see
    # ``ArtisanCreate.designWorkshopId`` in schemas/records.py for the whole argument.
    designWorkshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    artisanIds: list[str] | None = None
    responses: list[QuestionnaireResponseInput] | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit to keep, send to replace, never null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)
