from pydantic import Field, field_validator

from app.schemas.common import APIModel
from app.services.design_ratings import MAX_SCORE, MIN_SCORE

MAX_COMMENT_CHARS = 4000

MAX_SUGGESTION_CHARS = 4000


class DesignRatingIn(APIModel):
    subjectId: str = Field(min_length=1, max_length=64)
    round: str = Field(min_length=1, max_length=16)
    score: int = Field(ge=MIN_SCORE, le=MAX_SCORE)
    comment: str | None = Field(default=None, max_length=MAX_COMMENT_CHARS)
    suggestion: str | None = Field(default=None, max_length=MAX_SUGGESTION_CHARS)
    ratedAt: str | None = Field(default=None, max_length=64)

    @field_validator("comment", "suggestion")
    @classmethod
    def _blank_is_absent(cls, value: str | None) -> str | None:
        if value is None:
            return None
        trimmed = value.strip()
        return trimmed or None
