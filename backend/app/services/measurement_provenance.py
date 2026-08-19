"""How a stored dimension came to be known: typed off a tape, computed from marks, or guessed by a model.

**THE LAW THIS MODULE EXISTS TO MAKE EXPRESSIBLE.**

    EVERY STORED DIMENSION STATES ITS METHOD. A METHOD THAT IS IRREPRODUCIBLE ADDITIONALLY REQUIRES
    ACCEPTANCE BY A NAMED PERSON.

Not "AI needs a layer and arithmetic needs nothing". The line is about REPRODUCIBILITY, because that
is the only property that decides what a later reader can do with the number. Arithmetic over two
pixel distances may write into a documented dimension — but only while a reader can tell that
arithmetic is what wrote it. A vision model's estimate may reach the same field — but only as a
proposal a person accepted, because nobody can re-derive it and nothing can check it.

**THE CONCRETE FAILURE THIS PREVENTS, WHICH IS ALREADY IN THE DATABASE.** ``ProductDocumentation``
and ``ToolDocumentation`` carry ``lengthInches`` / ``breadthInches`` / ``heightInches``, printed as a
documented dimension by ``services/record_fields.py`` and read by somebody costing a production run.
Three different processes write those columns and, until this module, the row recorded no difference
between them:

1. a researcher reading a tape measure and typing the number;
2. ``DwPhotoMeasure`` / ``photoMeasure.ts`` — a ratio of two pixel distances, deterministic, with a
   stated error bar that is thrown away at the screen edge;
3. ``POST /media/analyze-measurement`` — Gemini looking at a photograph of the object on a grid sheet
   and, in the prompt's own verb, ESTIMATING it.

And it was worse than silence. ``records.merge_field_provenance`` stamps every changed non-empty
field with the ``{by, byName, at}`` of the account that pressed Save, and the dimension columns are
not in its ``PROVENANCE_SKIP_FIELDS``. So a Gemini estimate that auto-filled a form field was stored
attributed BY NAME to the designer who saved the form. The record did not merely fail to say a
machine produced the number — it positively asserted that a named human measured it. That is the
defect. A wrong dimension is a costing error; a wrong dimension wearing somebody else's name is a
costing error nobody can trace and that person cannot disown.

**WHY THE HUMAN STAMP BECOMES TRUE AGAIN RATHER THAN BEING REMOVED.** The remedy is not to strip
``by``/``byName`` off a machine-filled dimension — that would delete the most useful fact on the row.
It is to put ``method`` BESIDE them, at which point the same three keys stop claiming authorship and
start claiming what actually happened::

    lengthInches: {by: usr_7, byName: "R. Menon", at: "...", method: "VISION_MODEL",
                   methodProvider: "gemini", methodModelId: "gemini-2.5-flash-lite",
                   methodConfidence: 0.8}

Read aloud: *a vision model estimated this, and R. Menon accepted it into the record at that moment.*
That is a true sentence, it is the sentence an auditor needs, and it needs no acceptance column and
no migration — ``extraMetadata`` is an existing Json column. The acceptance IS the save, once the
method sits next to the signature. That is what ``records.merge_field_provenance`` writes today; see
THE RECORD HALF below for the one call it makes and the one thing still missing in front of it.

**WHY THIS IS NOT A ``DwAiLayer``, THOUGH THAT IS THE OBVIOUS-LOOKING HOME.** ``services/ai_layers``
is this repository's machinery for exactly this shape of problem — model output as a row with
provenance, inert until a person accepts it — and a grid measurement cannot use it today. Three
blockers, all verified rather than assumed, and only the first is a matter of adding a name:

* ``DwAiLayerKind`` has no value meaning "a number read off a photograph", and it is a real Postgres
  enum. Adding one is a migration, and a value Python can produce while Postgres refuses it is a 500
  on the write path (``tests/test_ai_layers.py`` pins the two vocabularies together for that reason).
* ``MEDIA_ROOTED_KINDS`` is ``{RAW_TRANSCRIPT, OCR_TEXT}``. Every other kind needs a TEXT parent, and
  ``ai_layers`` states why in the word: a model that concludes something about a photograph without
  producing text first "leaves an annexure printing conclusions with nothing underneath them to
  check". A bare ``valueInches`` is precisely that.
* **The blocker a migration does not solve.** ``DwAiLayer.designWorkshopId`` is NOT NULL and points at
  ``DesignWorkshop``. The destination of a grid measurement is ``ProductDocumentation`` /
  ``ToolDocumentation``, scoped by ``workshopId`` to ``Workshop`` — a different model. There is no
  ``DesignWorkshop`` to hang the row on. And the table deliberately declares no column that could
  name a target field: ``test_the_layer_table_cannot_name_a_place_in_stage_data`` forbids ``fieldKey``
  and its four siblings, so even with a kind and a scope there would be nowhere to record WHICH field
  the reading filled.

So "register it as a layer" is not an option anybody can take this week, and pretending otherwise
would leave the false human attribution in place while a migration is designed. This module is the
part that can land alone, today, with no schema change. What a layer would add later — a row that
exists before anybody accepts it, and an annexure that prints it — is genuinely better and is
described under THE MIGRATION THIS DOES NOT REPLACE at the end of this docstring.

**WHY AN ABSENT MARKER IS ``UNRECORDED`` AND NOT ``TYPED``, WHICH IS THE ONE DECISION HERE MOST
LIKELY TO BE "SIMPLIFIED" LATER.** It is tempting to read a save with no marker as a typed
measurement, on the reasoning that typing is what every client did before this existed. That would be
the original defect with a new spelling: the rows already in this database include Gemini estimates
that auto-filled a field, so defaulting to TYPED would assert a human measured a number a model
guessed — for exactly the rows where the assertion is false, and with no way left to tell. Nobody
wrote the method down, so the method is unrecorded, and :data:`UNRECORDED` says so in that word. It is
the same honest-unknown discipline ``ai_layers.UNRECORDED`` follows for a transcript whose provider
the media queue never persisted, and the same one ``DwLanguagePacks`` follows on the handset: a fact
nobody recorded is stated as unknown, never guessed, and never left as a null that reads like "none".

**THE HONEST LIMIT, STATED HERE SO NOBODY HAS TO DISCOVER IT.** A marker arriving on a request body is
a CLAIM, not a proof. A client could send ``TYPED`` for a number Gemini produced, and this server
cannot tell — exactly as it cannot tell whether a typed 12 was read off a tape or invented. The
marker is precisely as trustworthy as every other value in the form body it rides on, which is the
level of trust this whole API already runs on. What it is not is *worse* than what came before: the
record asserted the false thing BY CONSTRUCTION, for every machine-filled dimension, with no client
involved at all. Moving from "always wrong" to "as reliable as the rest of the body" is the whole of
the available improvement, and it is worth having.

**NOTHING IN THIS MODULE WRITES ANYTHING.** No ``db`` import, no Prisma, no network — the same
discipline ``ai_layers`` holds and for the same reason: these are the rules, and rules that cannot be
tested without a database do not get tested. Every function here is pure, so the whole of this file's
behaviour is asserted in ``tests/test_measurement_provenance.py`` on a laptop with no Postgres.

--------------------------------------------------------------------------------------------------
THE CLIENT SPECIFICATION — what Android and the web must do, neither of which is implemented here
--------------------------------------------------------------------------------------------------

The server half is done: this module, ``services/ai.py`` and ``api/routes/media.py`` produce the
marker, and ``records.merge_field_provenance`` / ``record_fields.dims_with_method`` store and print
it. The client half is four edits in files this change deliberately does not touch. Written down here
rather than in a planning document because this is the file whose vocabulary they have to speak, so
this is where whoever picks it up will already be looking.

**READ THE ONE BLOCKER FIRST.** ``measurementMethods`` is not declared on ``ProductCreate`` /
``ProductUpdate`` / ``ToolCreate`` / ``ToolUpdate``, and their shared ``APIModel`` is
``ConfigDict(extra="forbid")`` — so a client that starts sending the marker today has its ENTIRE save
rejected with a 422 naming a key the researcher has never heard of, and the web's ``saveOrQueue``
refuses to queue a 4xx ("the server saw it and said no"), so the save does not happen and is not
retried. ``offline.isSchemaRefusal`` at least names that shape of failure honestly — it exists
because a client sent a then-new ``merge`` flag to an API that predated it and every stage save came
back refused — but the work is still not saved. Deploy the schema BEFORE EITHER CLIENT, and never the
other way round. (That sentence used to read "deploy the schema first", full stop, and the next
paragraph is what it cost.)

**THE SCHEMA IS NOT THE FIRST STEP THOUGH, AND LANDING IT FIRST CORRUPTS AN AUDIT TRAIL.**
``access.REVISION_SKIP_FIELDS`` has to gain :data:`MARKER_BODY_KEY` BEFORE this schema declaration,
not after it. The schema is precisely what makes the marker SENDABLE, and a sendable marker with no
skip entry makes ``guard_record_edit`` write a ``RecordRevision`` nobody made on every save that
carries one — the mechanism is in §THE RECORD HALF below, and the row it writes is in an immutable
audit table that cannot be un-written by landing the skip entry afterwards. So the order is
``access.py``, then the four schemas, then the clients. Each step is inert until the one after it
lands — the skip entry alone can skip nothing, because nothing can send the key yet — which is what
makes this order free to obey and the other one expensive.

Declare it on all four as ``dict[str, Any] | None = None`` — not a pydantic model, because
:func:`provenance_of_marker` is the single validator and it deliberately degrades anything unreadable
to :data:`UNRECORDED` rather than raising — and deploy that before either client ships its half.

**1. Stop auto-filling. Propose, then let a person confirm.**

``POST /media/analyze-measurement`` now returns, beside the unchanged ``analysis`` block::

    method: "VISION_MODEL"        provider: "gemini"
    modelId: "gemini-2.5-flash-lite"
    selfReportedConfidence: 0.8 | null      confidenceIsCalibrated: false
    requiresAcceptance: true
    methodMarker: { method, provider, modelId, selfReportedConfidence, technique }

``requiresAcceptance`` is ``true`` on every response this endpoint can produce. A client that honours
it shows the number as a PROPOSAL with a button, and writes nothing into form state until the button
is pressed. The two models to copy already exist in this repository and are the reason this is a
small change rather than a design problem:

* ``IdentityCardReader`` (``frontend/components/designworkshop/FieldInput.tsx``), whose comment states
  the rule for the whole family: the only write in the OCR path "happens because a person read the
  candidate against the card in their hand and pressed Confirm".
* ``DwPhotoMeasurePanel`` (``android/.../ui/designworkshop/DwPhotoMeasureField.kt``), which says of
  itself that it "NEVER WRITES A DIMENSION BY ITSELF. Every path ends at a button the designer
  presses, exactly as DwIdentityOcrControl does" — wired through ``onPropose`` → ``onPatch`` in
  ``FieldRenderer.kt``.

The call sites that must change, which are the four places a model's number currently lands in form
state with nobody's consent:

* web: ``GridMeasurement.tsx`` ``onLengthBreadth`` / ``onHeight`` → ``ProductForm.tsx``
  ``setLength``/``setBreadth``/``setHeight``, and the same in ``ToolForm.tsx``;
* Android: ``GridMeasurementSection`` in ``MainActivity.kt`` → the product and tool form state.

``WorkshopRepository.analyzeMeasurement`` returns ``response.analysis?.valueInches`` and drops
everything else; ``analyzeMeasurementLengthBreadth`` drops it too. Both must return the marker with
the value. ``MeasurementAnalysisDto`` already declares ``confidence`` and no caller has ever read it;
``frontend/lib/media.ts``'s response type does not declare it at all. Both need the new keys.

Adding keys is safe for every build already in the field: ``ApiClient.kt`` builds its ``Json`` with
``ignoreUnknownKeys = true``, so an installed handset that has never heard of ``methodMarker`` ignores
it rather than throwing — which is the difference between a purely additive server change and a fleet
of phones that cannot read a measurement response at all.

**2. Send the marker back when the value is saved.**

On the product / tool request body, a top-level object keyed by column name::

    measurementMethods: {
      "lengthInches":  {"method": "VISION_MODEL", "provider": "gemini",
                        "modelId": "gemini-2.5-flash-lite", "selfReportedConfidence": 0.8},
      "heightInches":  {"method": "TYPED"}
    }

Send ``methodMarker`` verbatim as it arrived for a confirmed model reading. Send ``{"method":
"TYPED"}`` for a number somebody typed. Send ``{"method": "PHOTO_GEOMETRY", "technique": "SCALE"}`` —
or ``"RECTIFIED"`` — for the offline geometry path, which is the only new obligation that path
acquires and it costs it one dictionary literal it already has every fact for. Sending nothing is
legal and means :data:`UNRECORDED`; it must never mean TYPED.

**3. Say that the grid control needs a connection, and offer the thing that does not.**

``POST /media/analyze-measurement`` is network-only: no queue, no outbox, no retry — ``StagedJournal``
covers the photo upload, not the analysis. On failure both clients already say "Analysis failed —
enter it manually", which is honest and actionable. Three things are still wrong and all three are
client-side:

* neither control's hint text warns that it needs a connection, so the failure is a surprise every
  time (``GridMeasurement.tsx``'s hints; ``MainActivity.kt``'s grid section);
* both collapse "no connection" and "the provider refused" into one ``catch``, though the server now
  distinguishes them — a 503 names the missing setting, a 5xx/timeout does not;
* neither points at ``DwPhotoMeasure`` / ``photoMeasure.ts``, which is on the same device, needs no
  network, measures the same dimension and reports an error bar. In a courtyard with no signal the app
  currently offers the failing option and hides the working one.

The photograph is still kept on failure (it goes into the shared media batch), but with no
``MEASUREMENT`` job requested nothing ever re-reads it when signal returns. The evidence survives; the
analysis is simply lost. Re-reading it later is a client decision and this module has no opinion on
it, beyond the note in ``api/routes/media.py`` about why the queued path is refused.

--------------------------------------------------------------------------------------------------
THE RECORD HALF — where the false attribution was written, and what is there now
--------------------------------------------------------------------------------------------------

``records.merge_field_provenance`` is where the record asserted that a named human had measured a
model's guess. It now pops the marker off the save body and merges :func:`method_stamps` into the
stamp it was already writing::

    stamps = method_stamps(new_data.pop(MARKER_BODY_KEY, None), fields=new_data.keys())
    ...
    for field, value in new_data.items():
        ...
        provenance[field] = stamp | stamps.get(field, {})   # inside the existing changed-fields loop

Four properties of those two lines, each of which is load-bearing and each of which has a test:

* the ``pop`` is what keeps a non-column out of the Prisma ``data`` dict. ``clean_data`` only drops
  ``None``, so a marker that is actually sent would otherwise reach
  ``db.productdocumentation.create(data=...)`` as an unknown column — a 500 on the save, not a
  validation error a researcher could act on;
* ``MARKER_BODY_KEY`` is also in ``PROVENANCE_SKIP_FIELDS``, so the marker object can never be
  attributed as though it were a field somebody filled in;
* the merge sits INSIDE the existing changed-fields loop, which never fires for an unchanged value.
  Both web forms re-send every dimension on every save, so a client that blanket-sent ``TYPED`` for
  every box would otherwise launder every accepted machine measurement on a record into an apparent
  human one the next time somebody fixed a typo in ``remarks``;
* ``stamp | stamps.get(...)`` and not the reverse, so the method joins ``{by, byName, at}`` instead of
  replacing them. Stripping the name would delete the most useful fact on the row.

:func:`method_stamps` defaults every dimension column it is asked about to :data:`UNRECORDED`, so a
save from a client that has not implemented its half writes an explicit "nobody recorded a method"
rather than nothing — honest, distinguishable, and never the false human claim. Expect a burst of
those from the installed fleet and read them as the design.

ONE KEY STILL HAS TO BE ADDED IN A FILE THIS MODULE DOES NOT OWN. ``access.REVISION_SKIP_FIELDS``
does not list ``measurementMethods``, and ``guard_record_edit`` runs ``record_revision`` on the raw
``data`` BEFORE ``merge_field_provenance`` pops the key. So every marker-bearing PATCH currently
diffs ``measurementMethods`` from ``None`` and writes a ``RecordRevision`` — which breaks
``record_revision``'s own contract ("No-op when nothing meaningful changed") and fills an admin audit
trail with an edit nobody made. Moving the pop earlier is not available: the merge must still see the
marker. The marker is a hint about how a value was produced, not a value.

THIS IS THE FIRST STEP OF THE ROLLOUT, NOT A FOLLOW-UP TO IT. §THE CLIENT SPECIFICATION says "deploy
the schema first" and means schema-before-client; it does not mean schema-before-this. Land this skip
entry, then the four schema declarations, then the clients — because the schema is what makes the key
sendable, and every marker-bearing save between the two lands a revision row that no later edit can
retract.

``record_fields.dims_with_method`` then prints the method on the "Dimensions (LxBxH in)" cell — and
therefore in the data browser, every .xlsx sheet and the ``/export/products.csv`` /
``/export/tools.csv`` downloads, none of which is permission-gated. That placement is deliberate and
is not decoration: the provenance panel that shows who stamped what is gated on
``adminMode || canViewProvenance``, so a method shown only there would leave the researcher, the
reviewer and the ministry officer all reading an unqualified measurement. Only ``VISION_MODEL`` and
``PHOTO_GEOMETRY`` print a clause, for the same reason ``aiLayers.ts``'s ``layerKindLabel`` prints
"Machine transcript" rather than "Transcript": a reader who cannot see the difference has not been
told it. TYPED and :data:`UNRECORDED` print nothing, because appending "method not recorded" to the
majority of the rows in this database is noise that would train a reader to skip the clause on the
row where it matters.

WHAT THE RECORD HALF STILL CANNOT REACH, stated here so nobody reports it as a half-finished rollout:
``ToolDocumentation`` has no ``heightInches``. The grid control's height reading lands in the plain
``height`` column, which is not in :data:`DIMENSION_FIELDS`, so a marker naming it is dropped — an
accepted vision-model tool height is gated by the button on both clients and recorded as nothing.
Widening :data:`DIMENSION_FIELDS` is not free (``width`` beside it is a plain typed input) and is the
repo owner's call.

--------------------------------------------------------------------------------------------------
THE MIGRATION THIS DOES NOT REPLACE
--------------------------------------------------------------------------------------------------

A method marker is an honest system with a weak gate: the gate is a claim on a request body. A
``DwAiLayer`` would be a strong one — the reading exists as a row BEFORE anybody accepts it, so a
declined proposal is still evidence, and the annexure prints what was accepted and by whom. Getting
there needs, deliberately and not as a patch: a media-rooted ``MEASUREMENT`` kind in the
``DwAiLayerKind`` enum; a widening of ``MEDIA_ROOTED_KINDS`` with its reason written where the
existing narrowing's reason is; a ``payload`` convention for value + unit; and a way to scope a layer
to a ``Workshop`` rather than a ``DesignWorkshop``. Until that exists this is the honest maximum, and
the vocabulary here is deliberately the vocabulary such a layer would carry, so the migration is an
addition rather than a translation.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any

#: What every provenance field holds when nobody recorded it, spelled out in that word.
#:
#: THE SAME STRING AS ``ai_layers.UNRECORDED``, and ``tests/test_measurement_provenance.py`` asserts
#: they are identical. Duplicated rather than imported because that module reaches for the database at
#: import time and this one must stay pure — but two spellings of one discipline is how a report ends
#: up printing "UNRECORDED" in one annexure and "UNKNOWN" in the next, so the drift is pinned instead.
UNRECORDED = "UNRECORDED"

#: The key a client puts its per-field markers under, on a product or tool request body.
MARKER_BODY_KEY = "measurementMethods"


class MeasurementMethod(str, Enum):
    """How a dimension came to be known. Four values, and the fourth is the honest absence of an answer.

    NOT A RANKING, and not a quality score. ``PHOTO_GEOMETRY`` with a well-placed scale reference is
    more accurate than a tape held crooked, and a ``TYPED`` number from somebody with callipers beats
    both. What distinguishes them is what a LATER READER can do: re-derive it, ask the person, or
    neither.
    """

    #: A person read a scale and typed the number. Unverifiable and perfectly ordinary — this is what
    #: the overwhelming majority of dimensions in this repository are, and it needs no ceremony.
    TYPED = "TYPED"

    #: Deterministic arithmetic over marks a person placed on a photograph: ``DwPhotoMeasure`` on the
    #: handset, ``photoMeasure.ts`` on the web. Same marks, same number, on either surface, for ever —
    #: which is why it may fill a field with no acceptance step. See :attr:`technique` for which of
    #: the two geometries produced it.
    PHOTO_GEOMETRY = "PHOTO_GEOMETRY"

    #: A vision model looked at a photograph and estimated. Irreproducible by construction: the same
    #: image through the same endpoint next month may answer differently, legitimately, because the
    #: provider swapped a checkpoint. Requires acceptance.
    VISION_MODEL = "VISION_MODEL"

    #: Nobody wrote down how this number came to be known. Every dimension stored before this module
    #: existed is this, including the ones a model produced. NEVER a synonym for TYPED.
    UNRECORDED = UNRECORDED

    @property
    def requires_acceptance(self) -> bool:
        """Must a named person have accepted this number before it may sit in a documented field?

        True for :attr:`VISION_MODEL` alone, and the asymmetry is the entire point of this module.
        A typed number is a person's own act — there is nothing to accept, they did it. Arithmetic is
        re-runnable from the marks, so a reader who doubts it can check it. A model's estimate is
        neither, so the only thing that can stand behind it is somebody's signature.

        :attr:`UNRECORDED` answers False, which is not permission — it is the truth that a row nobody
        recorded a method for cannot also have recorded an acceptance. Do not turn this into a
        validation gate on the write path: it would reject every legacy row on its next save.
        """
        return self is MeasurementMethod.VISION_MODEL

    @property
    def reproducible(self) -> bool | None:
        """Would repeating the method on the same evidence give the same number? ``None`` where the
        question does not apply.

        Three-valued on purpose. PHOTO_GEOMETRY is True and VISION_MODEL is False, but TYPED is
        neither — a person reading a tape is not a computation, and answering True would claim
        determinism nobody measured while answering False would imply the number is untrustworthy,
        which it is not. ``None`` is "the question does not apply to this method", and a caller that
        wants a two-way branch should ask :attr:`requires_acceptance` instead.
        """
        if self is MeasurementMethod.PHOTO_GEOMETRY:
            return True
        if self is MeasurementMethod.VISION_MODEL:
            return False
        return None


#: The geometries ``DwPhotoMeasure`` / ``photoMeasure.ts`` can use, mirroring their own
#: ``MeasurementMethod = "SCALE" | "RECTIFIED"``. A DIFFERENT AXIS from :class:`MeasurementMethod`
#: here, which is why it is a separate field rather than two more enum members: both are
#: ``PHOTO_GEOMETRY`` as far as a reader of the record is concerned, and the distinction between them
#: matters only to somebody re-deriving the number.
GEOMETRY_TECHNIQUES: frozenset[str] = frozenset({"SCALE", "RECTIFIED"})

#: The columns on ``ProductDocumentation`` / ``ToolDocumentation`` that hold a documented dimension,
#: and therefore the only fields a method marker may name.
#:
#: ENUMERATED, so a marker cannot stamp a method onto an unrelated column. Without this a client could
#: send ``{"materialCost": {"method": "TYPED"}}`` and have it written into the provenance blob, where
#: it would read as though this system had an opinion about how a cost was arrived at. ``heightInches``
#: is absent from ``ToolDocumentation`` and present here anyway: a marker naming a column its table
#: does not have is dropped by the write path along with the value it describes, and one list is
#: better than two that can disagree.
DIMENSION_FIELDS: frozenset[str] = frozenset({"lengthInches", "breadthInches", "heightInches"})

#: The stage-registry dimension fields, named here ONLY so a reader looking for them finds this note.
#: ``lengthCm`` / ``widthCm`` / ``heightCm`` are ``DwStageEntry.data`` keys, not columns, and that Json
#: blob has no per-field provenance structure at all — so the mechanism in this module cannot reach
#: them, and the offline geometry path's ``DwPhotoMeasureField`` comment ("the registry has a column
#: for the dimension and none for the doubt") is still true of them after this change.
STAGE_DIMENSION_KEYS: frozenset[str] = frozenset({"lengthCm", "widthCm", "heightCm"})


#: The key the PROVIDER answers under: the measurement prompt asks Gemini for "confidence from 0 to 1",
#: so that is the word in its JSON.
PROVIDER_CONFIDENCE_KEY = "confidence"

#: The key the same number travels under on the wire and on a marker. Renamed deliberately rather than
#: passed through as ``confidence``: a client reading ``confidence`` beside a dimension will put
#: "confidence: 80%" in front of a designer, and this number has never been calibrated against a tape
#: measure. The longer name is the label, and it is the same reason ``confidenceIsCalibrated`` is on the
#: response — ``photoMeasure.ts``'s ``uncertainty`` IS a propagated error bar and the two must not look
#: alike on a wire that carries both.
MARKER_CONFIDENCE_KEY = "selfReportedConfidence"


def self_reported_confidence(analysis: Any, *, key: str = PROVIDER_CONFIDENCE_KEY) -> float | None:
    """The model's own confidence, if it gave one on the 0–1 scale the prompt asked for. Else None.

    ``key`` selects which spelling to read — :data:`PROVIDER_CONFIDENCE_KEY` for a provider's own JSON,
    :data:`MARKER_CONFIDENCE_KEY` for a marker coming back from a client. ONE VALIDATOR AND TWO KEY
    NAMES, rather than two functions: the checks below are the whole reason a client cannot store a
    confidence of 7, and a second reader written later would not have them.

    **SELF-REPORTED AND UNCALIBRATED, AND THIS FUNCTION CANNOT CHANGE THAT.** The measurement prompt
    asks Gemini for "confidence from 0 to 1", so what comes back is a model's claim about itself.
    Whether it discriminates a confident read from a guess is UNMEASURED — nothing in this repository
    calibrates it, tests it against a tape measure, or thresholds on it. It travels because a designer
    deciding whether to trust a proposal is better off seeing it than not, and it travels labelled
    (``confidenceIsCalibrated: false``) so nobody downstream builds a gate on it by accident.

    A MISSING CONFIDENCE STAYS MISSING. The honest-unknown rule: no default, no "0.5 because it
    answered at all", no inference from whether the value parsed. A number the model did not give is a
    number this system does not have.

    Out-of-range values are DROPPED RATHER THAN CLAMPED. A model answering 1.5 has not told us it is
    completely certain — it has failed to answer the question asked, and clamping to 1.0 would
    manufacture the loudest possible claim out of a malformed one. Rejected too: booleans (``True`` is
    an ``int`` in Python and would arrive as a confidence of 1.0), NaN and infinity.
    """
    if not isinstance(analysis, Mapping):
        return None
    raw = analysis.get(key)
    # A model that wrote its number as a string is still a model that gave a number; reading it is not
    # inventing it. Anything else — a word, a list, None — is no answer.
    if isinstance(raw, str):
        try:
            raw = float(raw.strip())
        except ValueError:
            return None
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return None
    value = float(raw)
    if not math.isfinite(value) or not (0.0 <= value <= 1.0):
        return None
    return value


@dataclass(frozen=True, slots=True)
class MeasurementProvenance:
    """How one measurement came to be known — the value object both halves of the wire speak.

    Frozen because it is a statement about something that has already happened. A mutable provenance
    is one a later line of code can quietly improve, and the improvement nobody notices is a provider
    name corrected to the one that "must have" produced a reading.
    """

    method: MeasurementMethod
    #: Which service produced it, or :data:`UNRECORDED`. ``UNRECORDED`` rather than None so a reader
    #: never has to decide what a null means — the same reason ``ai_layers`` demands the word.
    provider: str = UNRECORDED
    #: The model id, or :data:`UNRECORDED`. Without it, a systematic error found in six months (a
    #: provider silently swapping a checkpoint) cannot be traced to the material it damaged.
    model_id: str = UNRECORDED
    #: See :func:`self_reported_confidence`. None means the model reported none.
    self_reported_confidence: float | None = None
    #: For :attr:`MeasurementMethod.PHOTO_GEOMETRY` only: which geometry. See
    #: :data:`GEOMETRY_TECHNIQUES`.
    technique: str | None = None

    @property
    def requires_acceptance(self) -> bool:
        return self.method.requires_acceptance

    @property
    def reproducible(self) -> bool | None:
        """See :attr:`MeasurementMethod.reproducible`. Proxied so a caller holding a provenance can ask
        without reaching through to the enum — the offline geometry path holds one of these and nothing
        else, and having to know that the answer lives one attribute deeper is how a caller ends up
        testing ``provenance.method == "PHOTO_GEOMETRY"`` by hand instead."""
        return self.method.reproducible

    def payload(self) -> dict[str, Any]:
        """The keys ``POST /media/analyze-measurement`` adds to its response.

        ``requiresAcceptance`` is present on every response including the failures, and that is
        deliberate: it is a statement about what KIND of thing this endpoint produces, not about one
        reading. A client branching on it needs the key to exist before it knows whether a number
        arrived, and a key that appears only on success is one every client will forget to check.

        ``confidenceIsCalibrated`` is hard ``False`` and there is no code path that sets it True. It
        exists so the number beside it can never be mistaken for a measured error bar — which is what
        ``photoMeasure.ts``'s ``uncertainty`` is, and the two must not look alike on a wire that
        carries both.
        """
        return {
            "method": self.method.value,
            "provider": self.provider,
            "modelId": self.model_id,
            MARKER_CONFIDENCE_KEY: self.self_reported_confidence,
            "confidenceIsCalibrated": False,
            "requiresAcceptance": self.requires_acceptance,
            "methodMarker": self.marker(),
        }

    def marker(self) -> dict[str, Any]:
        """What a client echoes back, unchanged, when it saves the value it was given.

        Deliberately the same shape a client composes by hand for a typed or geometry measurement, so
        there is one marker format and not one-per-origin. Keys whose answer is not a known fact are
        OMITTED rather than sent as :data:`UNRECORDED`: a ``provider`` on a typed measurement is a
        question that does not apply, and storing the word on every hand-typed dimension would fill
        the provenance blob with answers to questions nobody asked.
        """
        marker: dict[str, Any] = {"method": self.method.value}
        if self.provider != UNRECORDED:
            marker["provider"] = self.provider
        if self.model_id != UNRECORDED:
            marker["modelId"] = self.model_id
        if self.self_reported_confidence is not None:
            marker[MARKER_CONFIDENCE_KEY] = self.self_reported_confidence
        if self.technique:
            marker["technique"] = self.technique
        return marker

    def stamp(self) -> dict[str, Any]:
        """What is stored beside ``{by, byName, at}`` in ``extraMetadata.fieldProvenance[field]``.

        ``method`` is always present — a stamp whose method had to be inferred from which other keys
        exist is the absence this module was written to end. The rest appear only when they are facts,
        for the reason :meth:`marker` gives. The ``method``-prefixed spelling keeps them from colliding
        with any field name a record already has, and makes the whole set greppable in one search when
        somebody asks which rows carry a machine-produced dimension.
        """
        stamp: dict[str, Any] = {"method": self.method.value}
        if self.provider != UNRECORDED:
            stamp["methodProvider"] = self.provider
        if self.model_id != UNRECORDED:
            stamp["methodModelId"] = self.model_id
        if self.self_reported_confidence is not None:
            stamp["methodConfidence"] = self.self_reported_confidence
        if self.technique:
            stamp["methodTechnique"] = self.technique
        return stamp


#: The provenance of a dimension nobody recorded a method for. Every row written before this module
#: existed, and every save from a client that has not implemented its half yet.
UNRECORDED_PROVENANCE = MeasurementProvenance(method=MeasurementMethod.UNRECORDED)


def vision_model_provenance(analysis: Any, *, provider: str, model_id: str) -> MeasurementProvenance:
    """The provenance of a reading ``POST /media/analyze-measurement`` just produced.

    ``provider`` and ``model_id`` are keyword-only and have no defaults, so a caller with nothing to
    say has to write :data:`UNRECORDED` deliberately — ``ai_layers.layer_create_plan``'s rule, for its
    reason: a defaulted provider is the one that silently becomes wrong when a second provider is
    added to the chain.
    """
    return MeasurementProvenance(
        method=MeasurementMethod.VISION_MODEL,
        provider=(provider or "").strip() or UNRECORDED,
        model_id=(model_id or "").strip() or UNRECORDED,
        self_reported_confidence=self_reported_confidence(analysis),
    )


def provenance_of_marker(marker: Any) -> MeasurementProvenance:
    """Read a client-sent marker. Anything unreadable becomes :data:`UNRECORDED`, never ``TYPED``.

    **THE DIRECTION OF THE FALLBACK IS THE WHOLE SAFETY PROPERTY.** A malformed marker resolving to
    TYPED would let a client turn a model's estimate into an apparent human measurement by sending
    slightly wrong JSON — the defect this module exists to remove, reachable by accident. Resolving to
    UNRECORDED instead loses information the client meant to send, which is recoverable, visible, and
    never a false claim.

    Note that a marker naming a method IS taken at its word, including ``TYPED``, and the module
    docstring says why: it is a claim on a request body, exactly as trustworthy as the number it
    describes. The fallback protects against malformed input, not against a client that lies.

    Case-sensitive on purpose. ``"typed"`` is not a token any part of this system writes, and
    accepting it would be the sort of kindness that turns an unrecognised value into an assertion
    about how somebody measured something — the same reasoning ``dictation_consent.consent_of``
    applies to a lower-case ``"granted"``.
    """
    if not isinstance(marker, Mapping):
        return UNRECORDED_PROVENANCE
    raw_method = marker.get("method")
    if not isinstance(raw_method, str):
        return UNRECORDED_PROVENANCE
    try:
        method = MeasurementMethod(raw_method)
    except ValueError:
        return UNRECORDED_PROVENANCE

    def _text(key: str) -> str:
        value = marker.get(key)
        return value.strip() if isinstance(value, str) and value.strip() else UNRECORDED

    technique = marker.get("technique")
    return MeasurementProvenance(
        method=method,
        provider=_text("provider"),
        model_id=_text("modelId"),
        # Read under the MARKER's spelling, through the same validator the provider's own answer goes
        # through — so a client cannot store a confidence of 7, of "high", or of true by sending it
        # back. Reading ``confidence`` here instead was a real bug caught by the round-trip test: the
        # endpoint hands out ``selfReportedConfidence``, so a client echoing the marker back verbatim
        # had its confidence silently dropped and the stored stamp lost the only number on it.
        self_reported_confidence=self_reported_confidence(marker, key=MARKER_CONFIDENCE_KEY),
        technique=technique if technique in GEOMETRY_TECHNIQUES else None,
    )


def method_stamps(markers: Any, *, fields: Any = None) -> dict[str, dict[str, Any]]:
    """``{column: stamp}`` for the dimension columns a save touches. The record half's one call.

    ``fields`` narrows the result to the columns actually being written — pass the keys of the update
    data. Omit it and every column in :data:`DIMENSION_FIELDS` named by a marker is returned.

    **EVERY DIMENSION COLUMN IN THE RESULT CARRIES A METHOD, INCLUDING THE ONES NOBODY DECLARED.** A
    save that mentions ``lengthInches`` and sends no marker for it gets ``{"method": "UNRECORDED"}``
    rather than nothing, because a reader must be able to see a state machine instead of inferring one
    from an absence — the property ``test_the_consent_key_is_never_null_on_the_wire`` protects one
    table over. An absent stamp and a stamp reading UNRECORDED would otherwise be indistinguishable
    from a row written before any of this existed, and the whole point is to be able to tell.

    Markers naming anything outside :data:`DIMENSION_FIELDS` are dropped in silence rather than
    refused: this runs inside a save, and failing somebody's record edit over a stray key in a
    provenance hint would trade a real loss for a cosmetic one. :data:`DIMENSION_FIELDS` says why the
    list is closed.
    """
    sent = markers if isinstance(markers, Mapping) else {}
    if fields is None:
        touched = {field for field in sent if field in DIMENSION_FIELDS}
    else:
        touched = {field for field in fields if field in DIMENSION_FIELDS}
    return {
        field: provenance_of_marker(sent.get(field)).stamp()
        for field in sorted(touched)
    }
