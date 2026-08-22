"""The one body the rating ledger accepts, and the bounds it accepts it within.

There is no delete body and no withdraw body, and that is deliberate rather than unfinished: a
rating is amended, never retracted. ``app/services/design_ratings.py`` carries the reasoning for
that and for everything else this file bounds.

WHY A POST AND NOT A PATCH, since the endpoint both creates and amends. The client does not know
which of the two it is doing: a phone that captured a rating in a courtyard has no idea whether the
server already holds one from the tablet the same designer used yesterday, and asking it to find
out first would put a round trip in front of every offline capture. One submission that resolves to
a create, an amendment or a no-op on the server is the shape an outbox can actually send. See
``design_ratings.rating_plan``.
"""

from pydantic import Field, field_validator

from app.schemas.common import APIModel
from app.services.design_ratings import MAX_SCORE, MIN_SCORE

#: How long a written judgement may be.
#:
#: Generous, because this is the qualitative half of the owner's "qualitatively and
#: quantitatively" and a reviewer with something to say about a prototype's joinery should not be
#: counting characters. It is bounded at all for the reason every other list and string on this
#: wire is: an unbounded field is a free way to make the server store arbitrary work, and a
#: megabyte of comment on a row that is read on every ranking page is a cost paid forever.
MAX_COMMENT_CHARS = 4000

#: The suggestion — "what would you change" — kept separate from the comment on purpose, and the
#: model keeps them in two columns for the same reason its own docstring gives: an assessment and a
#: proposed change are different speech acts with different readers, and collapsed into one box the
#: suggestions are unfindable inside the prose.
MAX_SUGGESTION_CHARS = 4000


class DesignRatingIn(APIModel):
    """One designer's judgement of one sketch or prototype, submitted or amended.

    ``ratedAt`` IS THE COURTYARD MOMENT and it is what orders two deliveries of the same rating. A
    client that captured this offline must send the moment the PERSON moved the control, not the
    moment the outbox got a connection: ``design_ratings.rating_plan`` refuses a delivery whose
    ``ratedAt`` is not newer than the stored row's, and that is the whole of what stops a queued
    original from undoing an amendment after a tunnel. Stamping it at send time defeats it exactly.
    Send the moment at whatever precision the device has: the ledger keeps it to the MILLISECOND and
    the comparison is made there (``design_ratings.LEDGER_CLOCK_RESOLUTION``), so a redelivery of
    one capture is recognised however many digits it carries.

    Omit it when the rating is typed straight against the server, where the row's ``createdAt`` is
    the same moment and repeating it would add nothing. A time in the future beyond the tolerated
    skew is REFUSED rather than corrected; see ``design_ratings.MAX_DEVICE_CLOCK_SKEW``.
    """

    subjectId: str = Field(min_length=1, max_length=64)
    round: str = Field(min_length=1, max_length=16)
    score: int = Field(ge=MIN_SCORE, le=MAX_SCORE)
    comment: str | None = Field(default=None, max_length=MAX_COMMENT_CHARS)
    suggestion: str | None = Field(default=None, max_length=MAX_SUGGESTION_CHARS)
    ratedAt: str | None = Field(default=None, max_length=64)

    @field_validator("comment", "suggestion")
    @classmethod
    def _blank_is_absent(cls, value: str | None) -> str | None:
        """A box the designer opened and left empty is not a comment.

        Normalised here rather than in the service so that "" and None cannot produce two rows that
        differ only in a way no screen can show — the same tidying every other text field on this
        wire gets, done at the edge where the client's habits arrive.
        """
        if value is None:
            return None
        trimmed = value.strip()
        return trimmed or None
