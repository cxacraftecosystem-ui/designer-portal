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
    designWorkshopId: str | None = None
    sections: list[CustomSectionCreate] = Field(default_factory=list)


class QuestionnaireUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    description: str | None = None
    # The attach-to-a-workshop dropdown. Sent as null to detach; omitted to leave alone — the
    # APIModel/clean_data convention used by every other record in this repo.
    designWorkshopId: str | None = None
    isActive: bool | None = None


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
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    artisanIds: list[str] | None = None
    responses: list[QuestionnaireResponseInput] | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    # Omit to keep, send to replace, never null. See forbid_clearing_location.
    _location_kept = model_validator(mode="after")(forbid_clearing_location)
