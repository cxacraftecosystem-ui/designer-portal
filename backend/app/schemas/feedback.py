from pydantic import Field

from app.schemas.common import APIModel


class FeedbackUpsertRequest(APIModel):
    """A user's own app feedback, in detail: an overall 1–5 rating plus per-aspect 1–5 sub-ratings
    (quantitative) and several targeted free-text prompts (qualitative). Every field is optional
    individually, but at least one is expected in practice."""

    # Quantitative (each 1–5).
    rating: int | None = Field(default=None, ge=1, le=5)
    easeOfUse: int | None = Field(default=None, ge=1, le=5)
    reliability: int | None = Field(default=None, ge=1, le=5)
    performance: int | None = Field(default=None, ge=1, le=5)
    design: int | None = Field(default=None, ge=1, le=5)
    features: int | None = Field(default=None, ge=1, le=5)
    recommend: int | None = Field(default=None, ge=1, le=5)
    # Qualitative free text.
    comment: str | None = Field(default=None, max_length=5000)
    likeMost: str | None = Field(default=None, max_length=5000)
    improve: str | None = Field(default=None, max_length=5000)
    bugs: str | None = Field(default=None, max_length=5000)
    featureRequests: str | None = Field(default=None, max_length=5000)
    role: str | None = Field(default=None, max_length=200)


class FeedbackReportCreate(APIModel):
    """One grievance, suggestion, recommendation or bug report.

    ── WHAT IS ASKED AND WHAT IS TAKEN ────────────────────────────────────────────────────────────

    ``subject`` and ``details`` are the only things a person types that they must type. Everything
    below the divider — client, version, platform, page — is filled in by the CLIENT from what it
    already holds, and is never a question on the form. The rule behind that split is the brief's:
    *"the client/version/platform captured automatically rather than asked for."* Asking a
    researcher standing in a courtyard for their app's version code is asking them to go and find a
    number they have no reason to know, and the answer they invent is worse than no answer.

    ── EVERY CAPTURED FIELD IS OPTIONAL, INCLUDING THE ONES THE CLIENTS ALWAYS SEND ───────────────

    A report refused because a browser would not report its platform is a report that never gets
    written, and the thing that matters here is that the grievance is recorded at all. So the
    validation on this half is a LENGTH CEILING and nothing else — the columns hold whatever the
    client could say about itself, and a reader treats them as hints for reproducing a fault rather
    than as fields anybody should GROUP BY.

    ── THE VOCABULARY FIELDS ARE PLAIN STRINGS HERE, ON PURPOSE ───────────────────────────────────

    ``kind``, ``severity`` and ``area`` are not typed as Literals or enums in this model. They are
    checked by ``services.feedback_vocabulary.validate_choice`` inside the route, so that ONE module
    decides membership for the API, the vocabulary endpoint both clients render from, and the export.
    A Literal here would be a second copy of the list that pydantic enforces and nobody updates, and
    its 422 would name the field without naming the members a client could have sent.
    """

    kind: str
    severity: str | None = None
    area: str | None = None

    #: The line an inbox lists. Short on purpose: a subject that runs to a paragraph is a subject
    #: that cannot be scanned, and the paragraph belongs in ``details`` where it will be read.
    subject: str = Field(min_length=1, max_length=200)
    #: The account itself. 5000 matches the free-text ceiling every column on ``Feedback`` already
    #: carries, so the two feedback surfaces cannot disagree about how much a person may write.
    details: str = Field(min_length=1, max_length=5000)

    # ── Captured by the client, never asked. ──────────────────────────────────────────────────
    client: str | None = None
    clientVersion: str | None = Field(default=None, max_length=100)
    platform: str | None = Field(default=None, max_length=300)
    pagePath: str | None = Field(default=None, max_length=300)


class FeedbackReportDecision(APIModel):
    """An administrator acknowledging or resolving a report.

    ``note`` is what the person who filed the report will read back. It is OPTIONAL on the model and
    conditionally REQUIRED in the route — required to resolve, optional to acknowledge — because the
    two acts promise different things: acknowledging says only "a named person has read this", which
    is true without further words, while resolving says "this is finished", which is not a thing an
    institution may say to a grievance without saying how. The route carries that argument in full.
    """

    note: str | None = Field(default=None, max_length=5000)
