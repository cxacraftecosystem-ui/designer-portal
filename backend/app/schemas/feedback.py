from pydantic import Field

from app.schemas.common import APIModel


class FeedbackUpsertRequest(APIModel):
    rating: int | None = Field(default=None, ge=1, le=5)
    easeOfUse: int | None = Field(default=None, ge=1, le=5)
    reliability: int | None = Field(default=None, ge=1, le=5)
    performance: int | None = Field(default=None, ge=1, le=5)
    design: int | None = Field(default=None, ge=1, le=5)
    features: int | None = Field(default=None, ge=1, le=5)
    recommend: int | None = Field(default=None, ge=1, le=5)
    comment: str | None = Field(default=None, max_length=5000)
    likeMost: str | None = Field(default=None, max_length=5000)
    improve: str | None = Field(default=None, max_length=5000)
    bugs: str | None = Field(default=None, max_length=5000)
    featureRequests: str | None = Field(default=None, max_length=5000)
    role: str | None = Field(default=None, max_length=200)


class FeedbackReportCreate(APIModel):
    kind: str
    severity: str | None = None
    area: str | None = None

    subject: str = Field(min_length=1, max_length=200)
    details: str = Field(min_length=1, max_length=5000)

    client: str | None = None
    clientVersion: str | None = Field(default=None, max_length=100)
    platform: str | None = Field(default=None, max_length=300)
    pagePath: str | None = Field(default=None, max_length=300)


class FeedbackReportDecision(APIModel):
    note: str | None = Field(default=None, max_length=5000)
