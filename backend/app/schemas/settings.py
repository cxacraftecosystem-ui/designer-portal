from datetime import datetime

from pydantic import Field

from app.schemas.common import APIModel

#: What a master admin may type into the daily dictation cap. Bounded so a slipped keystroke cannot
#: become a ceiling nobody would ever reach — 10,000 dictations is nearly twenty times the 510 field
#: specs the registry holds today (counted from ``stages()``; the "496" repeated elsewhere in this
#: repository is stale), so anything above it is a typo rather than a policy. It is a BOUND ON THE
#: TYPING and
#: not a recommendation: what the right number is remains unmeasured, in that word, and the column
#: ships NULL. 0 is deliberately in range — it means "send nothing to the transcription service today"
#: — while a negative is refused here, which is what lets ``dictation_cap.configured_cap`` treat one as
#: a hand-edited row rather than as a setting anybody chose.
MAX_DICTATION_DAILY_CAP = 10_000


class AppSettingDto(APIModel):
    transcriptionMode: str
    batchWindowEnabled: bool
    batchWindowStart: str
    batchWindowEnd: str
    batchTimezone: str
    sttProviderOrder: list[str]
    #: How many server dictations ONE DESIGNER may spend per India-time day. **Null is UNCAPPED and 0
    #: refuses every one**; both are real settings and neither is a "not set" sentinel. See
    #: ``services/dictation_cap.py`` for the boundary the day is measured against.
    dwDictationDailyCap: int | None = None
    #: How many AI-VERB runs — proofread, expand, translate, caption, subtitle — ONE DESIGNER may
    #: spend per India-time day, across all five together. **Null is UNCAPPED and 0 refuses every
    #: run**, exactly as its sibling above. A SECOND ceiling and not a second use of the first: see
    #: ``services/ai_verb_cap.py`` for why the dictation cap may not be made to cover these.
    dwAiVerbDailyCap: int | None = None


class AppSettingUpdate(APIModel):
    transcriptionMode: str | None = None
    batchWindowEnabled: bool | None = None
    batchWindowStart: str | None = None
    batchWindowEnd: str | None = None
    batchTimezone: str | None = None
    sttProviderOrder: list[str] | None = None
    #: **THE ONE FIELD ON THIS BODY WHERE AN EXPLICIT NULL MEANS SOMETHING.** Every other field here is
    #: read through ``model_dump(exclude_none=True)``, where absent and null are the same instruction —
    #: "leave it alone" — which is correct for a mode or a time that always has a value. It is wrong
    #: here: null is the setting that means uncapped, so a master admin who capped at 40 in the morning
    #: could never go back to uncapped in the afternoon. The route therefore reads
    #: ``model_fields_set`` for this field alone, and says so where it does it.
    dwDictationDailyCap: int | None = Field(default=None, ge=0, le=MAX_DICTATION_DAILY_CAP)
    #: The AI-verb ceiling, read through ``model_fields_set`` the same way and for the same reason —
    #: an explicit null is the instruction "uncapped", and under ``exclude_none`` a master admin who
    #: capped in the morning could never lift it again.
    #:
    #: BOUNDED BY THE SAME NUMBER, and the bound is on the TYPING rather than a recommendation: what
    #: the right number is remains unmeasured, in that word, and the column ships NULL. 0 is
    #: deliberately in range — "run none of these today" — while a negative is refused here, which is
    #: what lets ``ai_verb_cap.configured_cap`` treat one as a hand-edited row rather than a setting
    #: anybody chose.
    dwAiVerbDailyCap: int | None = Field(default=None, ge=0, le=MAX_DICTATION_DAILY_CAP)


class TranscriptionProviderDto(APIModel):
    """One speech-to-text engine as the ranking screen sees it.

    WHAT AN ADMIN IS ALLOWED TO KNOW ABOUT A KEY, AND WHAT THEY ARE NOT
    -------------------------------------------------------------------
    This resource is open to every admin, while the key's VALUE and its last-four hint stay behind
    ``/secrets`` and the master admin. So the fields below carry exactly two things: whether an
    engine can run at all, and what the provider said the last time we asked. Neither is a secret —
    they are the difference between a ranking that works and one that silently does nothing — and
    neither can be walked back into a credential. There is deliberately no ``hint``, no ``source``
    and no prefix here; adding one would quietly widen who can see the deployment's keys.

    ``testError`` is safe for the same reason the master-admin list is: probe errors are built from
    the HTTP status alone and never from the provider's response body, which sometimes echoes the
    offending key back (see ``managed_secrets._probe_http``).
    """

    id: str
    name: str
    keyName: str
    keyLabel: str
    # True when a key resolves at all — i.e. the transcription chain WILL call this engine. Kept
    # separate from ``rankable`` on purpose: a present-but-rejected key is configured and unusable
    # at the same time, and collapsing the two is what let the panel lie in the first place.
    configured: bool
    # NO_KEY / UNTESTED / FAILING / PASSING — see app_settings.STT_KEY_*.
    keyState: str
    # May the admin rank this engine freely? Only a PASSING test earns that.
    rankable: bool
    # Why not, in a sentence the admin can act on. Null when the engine is rankable.
    frozenReason: str | None = None
    testedAt: datetime | None = None
    testError: str | None = None


class TranscriptionProviderOrderDto(APIModel):
    providers: list[TranscriptionProviderDto]
    # What the pipeline would ACTUALLY do with this ranking right now: every engine whose key
    # resolves, in stored order. NOT the same list as the proven one below — an engine with a
    # rejected key is still attempted and still falls through, wasting a round trip per job — and
    # showing both is what makes the cost of leaving a broken key in place legible.
    effectiveChain: list[str]
    # The engines that have actually answered a test, in stored order.
    verifiedChain: list[str]
    # Set when the server had to move something to keep the freeze true. The ranking in this response
    # is the stored one either way; ``normalisedNote`` says what changed and why.
    normalised: bool = False
    normalisedNote: str | None = None


class TranscriptionProviderOrderUpdate(APIModel):
    order: list[str]
