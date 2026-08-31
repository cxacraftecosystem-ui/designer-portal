"""What kind of questionnaire a `Questionnaire` is, and which report stage its answers belong to.

THE REQUIREMENT, VERBATIM. Owner, 2026-08-30: *"the designer can have multiple questionnaires for
the same workshop as well, they also do market survey interviews, so create that differentiation as
well, so that we can map the questionnaires and the transcripts to the correct stage in the
report."*

Two halves, and only the second one needed building:

* **Several questionnaires per workshop already worked.** `Questionnaire.designWorkshopId` is a
  single nullable column and the far side of the relation is `Questionnaire[]`, so any number of
  forms may point at one `DesignWorkshop`. `report_items` selects them all with
  `find_many({"designWorkshopId": ..., "isActive": True})` and the annexure prints every one.
  Nothing in this module widens that, and nothing needed to.
* **Telling the two KINDS apart did not exist at all**, so the annexure printed a baseline interview
  and a market survey one after another under one heading with nothing to say which stage of the
  workshop either belonged to. That is what this module adds.

WHY THE VOCABULARY LIVES HERE AND NOT IN ``stage_schema.ENUMS``
--------------------------------------------------------------

`DesignWorkshop.workshopKind` — added in this same session — put its vocabulary in the stage
registry, and this one deliberately does not. The difference is not taste:

* `workshopKind` is a STAGE FIELD. It is answered inside stage 1's document, ``coerce_value``
  validates it against the registry on the way in, and the column is only a promoted copy so the
  workshop list can filter without opening every stage document.
* A `Questionnaire` is **not part of any stage document**. It is its own table with its own routes.
  Publishing its vocabulary through ``stage_schema.ENUMS`` would put a token into the registry that
  no ``FieldSpec`` will ever reference, and — the concrete cost — would move ``registry_version()``,
  which is what every handset in the field compares to decide whether to re-download the whole
  registry. A fortnight of courtyard fieldwork would re-sync the registry for a value no stage uses.

So this is the other pattern the lane brief names: a plain nullable TEXT column validated against a
frozenset that belongs to the table. ``schema.prisma``'s comment on `Questionnaire.kind` carries the
same argument from the database's side.

MIRRORED BY HAND INTO TWO OTHER TREES, AND A TEST IS WHAT KEEPS THEM HONEST
---------------------------------------------------------------------------

Both clients draw this picker and the owner asked for identical wording, so :data:`KIND_LABELS` is
copied into ``frontend/lib/questionnaireForms.ts`` and
``android/.../ui/questionnaires/QuestionnaireKinds.kt``. Nothing compiles the three against each
other, which is exactly the drift ``tests/test_role_ladder_parity.py`` exists for; the copies are
held to this file by ``tests/test_questionnaire_kinds.py``, which reads the other trees as text.
**When that test fails, this file is the expectation** — find the mirror that lagged.
"""

from __future__ import annotations

#: The vocabulary: token -> the label BOTH CLIENTS SHOW. Ordered as the pickers order it.
#:
#: TWO MEMBERS, AND NO ``OTHER``. ``WORKSHOP_KIND`` carries an OTHER and argues for it on the grounds
#: that schemes keep being announced and a designer must be able to file truthfully rather than pick
#: the nearest wrong one. That argument does not transfer, because this value is not a description —
#: it is a ROUTING INSTRUCTION. Every member here has to name a stage the material can land in, and
#: an OTHER would name none, so a form filed under it would be one the designer had answered a
#: question about and that the report still could not place. NULL already says "not stated", and
#: says it without pretending a decision was made. If a third genuine kind appears it arrives here
#: WITH its stage — see :data:`KIND_STAGE_KEYS`, whose completeness is asserted by the tests.
KIND_LABELS: dict[str, str] = {
    "WORKSHOP_INTERVIEW": "Workshop interview",
    "MARKET_SURVEY": "Market survey",
}

#: The tokens alone, for validation. A frozenset because this is a membership test on every write.
KIND_TOKENS: frozenset[str] = frozenset(KIND_LABELS)

#: WHICH STAGE EACH KIND'S MATERIAL IS FILED UNDER IN THE REPORT.
#:
#: The values are ``StageSpec.key`` strings from ``app/services/stage_definitions.py``, established
#: by reading that file rather than guessed, and ``test_questionnaire_kinds.py`` asserts every one of
#: them is a registered stage so a renamed stage cannot leave this map pointing at nothing.
#:
#: ── WHY ``MARKET_SURVEY`` GOES TO STAGE 8 AND NOT TO STAGE 9 ────────────────────────────────────
#:
#: The lane brief's shorthand was "a market survey belongs to the market-analysis stage", and the
#: brief also said to establish the actual keys from ``stage_definitions`` rather than guess. Doing
#: so splits that shorthand in two, and the two stages are not interchangeable:
#:
#:   * **STAGE 8, ``MARKET_SURVEY_CAPTURE``** — "Market Survey & Field Data", whose stated purpose is
#:     *"What the survey actually found: responses from each group, photographs of the market, prices
#:     seen…"*. Recorded sittings ARE responses from each group. This is the stage.
#:   * **STAGE 9, ``MARKET_ANALYSIS_DIRECTION``** — "Market Analysis & Design Direction", purpose
#:     *"What the survey MEANS: the SWOT, the price bands the market will bear…"*. Filing raw
#:     verbatim answers under the analysis would put evidence where the conclusions go, and a reader
#:     checking a SWOT claim against the responses would find the responses printed as though they
#:     were the claim.
#:
#: So the shorthand is overruled, deliberately and only here — nothing about the intent changes, only
#: which of the two market stages receives the material.
#:
#: ── WHY ``WORKSHOP_INTERVIEW`` GOES TO STAGE 6 ──────────────────────────────────────────────────
#:
#: ``EXISTING_PRODUCTS_BASELINE`` is "Existing Products & Artisan Baseline", and it is already the
#: stage that cites an artisan interview: its ``artisanBaseline.interviewRef`` field exists for
#: exactly that, and the long comment above it in ``stage_definitions.py`` rules out every other
#: candidate by name — ``participant`` (an interview belongs to a SET of artisans, so it would print
#: six times), ``workshopPlan`` (the opening ceremony), ``clusterBackground`` (about the craft, not
#: about people), and stages 7-8 in these words: *"`surveySummary`/`surveyPlan` (stages 7-8) are the
#: MARKET survey: consumers, retailers, wholesalers, exporters. A different instrument and a
#: different population"*. That sentence is this whole module in miniature, written before this
#: column existed.
KIND_STAGE_KEYS: dict[str, str] = {
    "WORKSHOP_INTERVIEW": "EXISTING_PRODUCTS_BASELINE",
    "MARKET_SURVEY": "MARKET_SURVEY_CAPTURE",
}

#: What an unstated kind is CALLED wherever kinds are shown. Never a member of the vocabulary: a
#: questionnaire with no kind has not been filed anywhere, and giving that state a token would make
#: "nobody has said" indistinguishable from "somebody said none of them".
NOT_STATED_LABEL = "Kind not stated"


def is_kind(value: str | None) -> bool:
    """Whether ``value`` is one of the tokens. ``None`` and ``""`` are not kinds — they are silence."""
    return bool(value) and value in KIND_TOKENS


def label_for(value: str | None) -> str:
    """The label a client shows for ``value``, or :data:`NOT_STATED_LABEL`.

    An UNKNOWN token — a row written by a newer deployment and read by an older one — falls back to
    the token itself rather than to "not stated", because silently relabelling a value somebody chose
    as "nobody chose" is the one wrong answer available here.
    """
    if not value:
        return NOT_STATED_LABEL
    return KIND_LABELS.get(value, value)


def stage_key_for(value: str | None) -> str | None:
    """The report stage this kind's material is filed under, or ``None`` for an unstated kind."""
    if not value:
        return None
    return KIND_STAGE_KEYS.get(value)


def coerce_kind(value: str | None) -> str | None:
    """Normalise an inbound kind, or raise ``ValueError`` naming what is allowed.

    ``None`` and ``""`` BOTH normalise to ``None`` — "not stated" — and that is the one place this
    helper is deliberately more forgiving than ``QuestionnaireCreate.designWorkshopId`` beside it,
    which refuses ``""`` with a 422. The difference is what the empty string MEANS on each: for a
    workshop id it is a malformed foreign key that used to reach Prisma and answer 500, so refusing
    it protects the caller; for a kind it is a picker sitting on its blank row, which is a real and
    valid answer ("I have not said"). Refusing it would turn an untouched dropdown into a validation
    error.

    Case is normalised UP because the tokens are upper-case and a client sending ``market_survey``
    has made a spelling mistake rather than a different choice.
    """
    if value is None:
        return None
    token = value.strip().upper()
    if not token:
        return None
    if token not in KIND_TOKENS:
        allowed = ", ".join(sorted(KIND_TOKENS))
        raise ValueError(f"'{value}' is not a questionnaire kind. Use one of: {allowed}.")
    return token
