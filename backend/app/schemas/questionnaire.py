from datetime import datetime
from typing import Any

from pydantic import Field, field_validator, model_validator

from app.schemas.common import (
    APIModel,
    LocationInput,
    forbid_clearing_location,
    require_location,
)
from app.services.questionnaire_kinds import KIND_TOKENS, coerce_kind


def _validate_kind(value: str | None) -> str | None:
    return coerce_kind(value)


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
    workshopId: str | None = None
    designWorkshopId: str | None = None
    status: str = "PENDING"
    recordedAt: datetime | None = None
    recordedTimezone: str = "Asia/Kolkata"
    artisanIds: list[str] = Field(default_factory=list)
    responses: list[QuestionnaireResponseInput] = Field(default_factory=list)
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None

    _location_required = model_validator(mode="after")(require_location)


class CompletionCellUpdate(APIModel):
    artisanId: str = Field(min_length=1)
    sectionId: str = Field(min_length=1)
    status: str | None = None



class CustomQuestionCreate(APIModel):
    prompt: str = Field(min_length=1, max_length=2000)
    helpText: str | None = None
    isRequired: bool = False
    sortOrder: int | None = Field(default=None, ge=1)


class CustomQuestionUpdate(APIModel):
    prompt: str | None = Field(default=None, min_length=1, max_length=2000)
    helpText: str | None = None
    isRequired: bool | None = None
    sortOrder: int | None = Field(default=None, ge=1)
    sectionId: str | None = Field(default=None, min_length=1)


class CustomSectionCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    code: str | None = Field(default=None, max_length=24)
    sortOrder: int | None = Field(default=None, ge=1)
    questions: list[CustomQuestionCreate] = Field(default_factory=list)


class CustomSectionUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    sortOrder: int | None = Field(default=None, ge=1)
    isActive: bool | None = None


class QuestionnaireCreate(APIModel):
    title: str = Field(min_length=1, max_length=220)
    description: str | None = None
    designWorkshopId: str | None = Field(default=None, min_length=1)
    kind: str | None = Field(default=None, examples=sorted(KIND_TOKENS))
    sections: list[CustomSectionCreate] = Field(default_factory=list)

    _kind_is_a_token = field_validator("kind")(_validate_kind)


class QuestionnaireUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    description: str | None = None
    designWorkshopId: str | None = Field(default=None, min_length=1)
    kind: str | None = None
    isActive: bool | None = None
    isShared: bool | None = None

    _kind_is_a_token = field_validator("kind")(_validate_kind)


class QuestionnaireDefaultUpdate(APIModel):
    isDefault: bool


class QuestionnaireReuse(APIModel):
    designWorkshopId: str | None = Field(default=None, min_length=1)
    title: str | None = Field(default=None, min_length=1, max_length=220)
    description: str | None = None
    kind: str | None = None

    _kind_is_a_token = field_validator("kind")(_validate_kind)


class QuestionnaireEntryCreate(APIModel):
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
    answers: list[CustomAnswerInput] = Field(default_factory=list)


class QuestionnaireInterviewUpdate(APIModel):
    title: str | None = Field(default=None, min_length=1, max_length=220)
    interviewDate: datetime | None = None
    place: str | None = None
    language: str | None = None
    notes: str | None = None
    workshopId: str | None = None
    designWorkshopId: str | None = None
    status: str | None = None
    recordedAt: datetime | None = None
    recordedTimezone: str | None = None
    artisanIds: list[str] | None = None
    responses: list[QuestionnaireResponseInput] | None = None
    location: LocationInput | None = None
    extraMetadata: dict[str, Any] | None = None
    expectedUpdatedAt: datetime | None = None

    _location_kept = model_validator(mode="after")(forbid_clearing_location)
