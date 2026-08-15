"""The design-workshop API: the 22-stage record, its stages, and the reports generated from it.

Three families of endpoint:

* ``/design-workshops`` — the workshop header. List, create, read, update, soft-delete.
* ``/design-workshops/{id}/stages*`` — read and write one stage at a time. A stage is saved
  whole, never field by field; see :class:`StageSaveIn` for why.
* ``/design-workshops/{id}/report*`` — generate a .docx or .pdf, and record an export the
  phone generated offline.
* ``/design-workshops/{id}/ai-layers*`` — what a model produced from this workshop's material,
  what it was produced from, and who accepted it. **No route in that family calls a provider or
  writes a word of model text**; see ``app/services/ai_layers.py`` for the layering law it serves
  and for which step of the plan adds the writing.
* ``/design-workshops/{id}/dictation-consent`` — may this workshop's recordings leave the device for
  a third-party provider, who said so, and when. It is the gate on
  ``POST /design-workshops/{id}/dictate``, and the reason it is not a field on the stage-1 form is
  argued in ``app/services/dictation_consent.py``.

Plus one that belongs to none of them and is the most important of the four:
``GET /design-workshops/schema`` serves the field registry itself. Every client — the web form,
the Android capture screens, the on-device report builder — renders from that payload rather
than from its own copy of the field list, which is what keeps three surfaces describing one
workshop the same way. It is cached by ``version``; a changed version is the signal an Android
draft store uses to run its migration.

**Nothing here hard-deletes.** ``DELETE`` sets ``deletedAt``. This is a research data set, the
requirement is explicit that data is retained, and a designer's two weeks of fieldwork is not
something a mis-tap should end. Every read filters ``deletedAt: null``; an admin can restore.

**Permissions**, as this file actually enforces them — read the four clauses, not the ladder,
because one of them is not a rank threshold:

* CREATING one needs BOTH ``assert_can_create_records`` (Researcher and above, the repository-wide
  rule for making any record) AND ``_require_designer``, which is ``can_run_design_workshops`` — a
  SET, ``{DESIGNER, ADMIN, MASTER_ADMIN}``, not a floor. A PROFESSOR outranks a designer on every
  other surface in this codebase and still cannot start a workshop. ``PATCH``, every stage write and
  the two capture aids (OCR and dictation) call ``_require_designer`` ALONE:
  ``assert_can_create_records`` appears on the create route and nowhere else in this file. No caller
  gets in that way who would not get in anyway — the designer set sits above Researcher on the
  ladder, so whatever ``_require_designer`` admits ``can_create_records`` admits too — but which gate
  is written where is exactly what this header is read for, so it says which.
* OPENING someone else's is decided entirely by ``load_workshop_or_404``: the creator, an admin, or
  an account an admin has given a ``DesignWorkshopViewer`` row. A grant carries read AND the stage
  writes that go through that helper — see ``services/design_workshop_viewers.py`` for what it
  deliberately does not carry.
* DELETING is ``assert_can_delete``; restoring is ``require_admin``.
* AI LAYERS follow the stage-write rule and not the delete one: LISTING them needs whatever
  ``load_workshop_or_404`` needs, and registering, accepting, withdrawing or declining one needs
  ``_require_designer`` plus ``for_edit=True``. ``assert_can_delete`` is deliberately NOT on the
  layer delete — that gate guards a workshop, which is a fortnight of fieldwork; declining a
  model's suggestion is the ordinary work of the designer reading it, the row is soft-deleted, and
  the material it was derived from is untouched by construction.
  READING A LAYER'S TEXT IS THE FOURTH CLAUSE, not the first: the text is a stored copy of a
  transcript, so it is gated per recording by ``owned_or_granted_where`` like every other transcript
  surface, and a layer standing on a recording the caller may not read is listed with its provenance
  and ``textWithheld``. Opening a workshop has never conferred the right to read the media inside
  it, and a copy of a transcript is still the transcript.
* GENERATING A REPORT is open to anyone who can READ the workshop — a report is a view of data the
  caller can already see, and refusing it would only push people to screenshot the screen. The
  photographs and recordings it EMBEDS are a different question, gated per file by
  ``owned_or_granted_where`` in ``media_resolver``/``load_transcript_items``.
* RECORDING TIER 3 CONSENT follows the stage-write rule and invents nothing: ``_require_designer``
  plus ``for_edit=True``. Taking the artisan's answer down is the ordinary work of the designer sitting
  with them, and scoping the fact to ONE workshop is what means no new permission concept was needed.
  The DAILY DICTATION CAP is not a permission at all — it is a spend ceiling on an account, enforced
  the same way for every role in the designer set, and configured by the master admin on the settings
  row beside the off-peak batch window.

``require_workshop_manager`` is NOT used here. It belongs to ``api/routes/workshops.py``, a
different router over a different model.
"""

import asyncio
import hashlib
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Response,
    UploadFile,
    status,
)

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    assert_can_create_records,
    assert_can_delete,
    can_run_design_workshops,
    get_current_user,
    is_admin,
    require_admin,
)
from app.schemas.design_workshops import (
    DESIGN_WORKSHOP_STATUSES,
    AiExpandIn,
    AiLayerDecisionIn,
    AiLayerRegisterIn,
    AiMediaVerbIn,
    AiProofreadIn,
    AiTranslateIn,
    CustomSectionsIn,
    DesignWorkshopCreate,
    DesignWorkshopUpdate,
    DictationConsentIn,
    ExportRecordIn,
    ReportGenerateIn,
    StageSaveIn,
)
from app.services import ai, ai_layers, ai_verb_cap, ai_verbs, dictation_cap, dictation_consent
from app.services.ai import transcribe_audio_bytes
from app.services.concurrency import gather_reads
from app.services.cost_integrity import analyse_cost_integrity, cost_findings_payload
from app.services.custom_sections import (
    CUSTOM_ENTITY_KEY,
    V1_FIELD_TYPES,
    CustomFieldSpec,
    CustomOption,
    CustomSectionEditError,
    CustomSectionSpec,
    answered_keys,
    apply_definition_plan,
    definition_payload,
    load_definition,
    load_definition_or_empty as load_custom_definition_or_empty,
    plan_definition,
    validate_definition,
)
from app.services.design_workshop_viewers import visible_to_clause
from app.services.design_workshops import (
    REFERENCE_LIMIT_DEFAULT,
    REFERENCE_LIMIT_MAX,
    assemble_workshop_data,
    attach_district_anchors,
    attach_report_ai_layers,
    attach_report_custom_sections,
    attach_report_questionnaires,
    attach_report_references,
    attach_report_transcripts,
    entry_rows,
    load_workshop_or_404,
    media_resolver,
    reference_options,
    render_report,
    resolve_template_id,
    save_stage,
    seed_designer_prefill,
    workshop_completeness,
    workshop_media_ids,
    workshop_summary,
)
from app.services.identity_ocr import (
    SUPPORTED_MIME_TYPES,
    IdentityOcrUnavailable,
    get_identity_ocr_settings,
    read_identity_card,
)
from app.services.market_analysis import analyse, market_findings_payload
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains, enum_filter_or_422, owned_or_granted_where, plain
from app.services.report_docx import DOCX_MIME
from app.services.report_pdf import PDF_MIME
from app.services.report_templates import SpecialSection, template as get_template, template_choices
from app.services.s3 import get_object_bytes
from app.services.stage_schema import (
    REF_SCOPE_ALL,
    registry_to_dict,
    registry_version,
    stages,
)
from app.services.workshop_transcripts import load_transcript_items

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])

# A generated report is CPU-bound and can take seconds on a 26-page workshop with forty photos.
# Every render therefore goes through asyncio.to_thread, exactly as xlsx_report does, so one
# export cannot stall the event loop for every other request on a single-worker deployment.
_MIME = {"DOCX": DOCX_MIME, "PDF": PDF_MIME}
_EXTENSION = {"DOCX": "docx", "PDF": "pdf"}

#: The tier every verb run by THIS PROCESS records. A constant here and an argument everywhere else.
#:
#: **NOT A DEFAULT INSIDE THE VERB SERVICE, WHICH IS THE WHOLE POINT.** Plan §2.1 makes the tier the
#: safeguard that lets a fleet run different tiers on different handsets without producing two
#: indistinguishable classes of record, so ``ai_verbs`` takes it as a required argument with no
#: default and every planner would serve an on-device run unchanged. What this constant says is
#: narrower and is a fact rather than a choice: a verb that ran inside this process ran on the
#: server, against a hosted model, and no request body may claim otherwise. ``AiLayerRegisterIn``
#: makes the same refusal for the same reason — a body that could claim TIER_1 for cloud output would
#: make the tier column worthless to the reviewer it exists for.
_SERVER_TIER = ai_layers.AiTier.TIER_3


# --------------------------------------------------------------------------------------
# The registry
# --------------------------------------------------------------------------------------


@router.get("/schema")
async def get_stage_schema(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The field registry every client renders its forms from.

    Served rather than duplicated, for the same reason ``/reference/address`` is: a field list
    that lives in three codebases is three field lists, and they drift. Whatever a form offers
    is by construction exactly what this API accepts and exactly what the report prints.

    A pure constant — no database read — so a client should cache it and re-fetch only when
    ``version`` changes.
    """
    return registry_to_dict()


@router.get("/templates")
async def get_report_templates(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    """The report templates a designer may choose between at stage 20."""
    return template_choices()


# --------------------------------------------------------------------------------------
# Speech and scanning
#
# Two capabilities that belong to the capture screens rather than to a workshop record: a designer
# scans a card and dictates a paragraph before the stage they are filling has ever been saved.
#
# **THAT IS WHY NEITHER TOOK A ``workshop_id`` — AND WHY DICTATION NOW REQUIRES ONE.** Plan §6 answer 3
# makes consent a fact about ONE WORKSHOP that gates the server dictation rung, and a gate cannot be
# enforced against a request that does not say which workshop it belongs to. The original
# ``POST /dictate`` took exactly ``file`` and ``languageHint``, made no database call of any kind, and
# handed the clip to the provider chain — so for as long as it accepted recordings it was a door beside
# the gate: one artisan's refusal stopped a clip on one URL and not on the other, and a gate with a door
# beside it is not a gate.
#
# **BOTH CLIENTS HAVE MOVED, SO THE DOOR IS SHUT RATHER THAN DOCUMENTED.** Android declares
# ``@POST("design-workshops/{id}/dictate")`` with a required id (``data/WorkshopRepositoryApi.kt``) and
# the browser's ``dictateAudio`` takes a required ``workshopId`` and posts to ``dwDictatePathFor``
# (``frontend/lib/designWorkshops.ts``); nothing in this repository sends a clip to the id-less URL any
# more. It answers 410 rather than disappearing, for the one caller nobody can recall from the field —
# a build already installed on a handset — which then gets a sentence naming the URL that replaced it
# instead of FastAPI's bare "Method Not Allowed".
#
# **THE ID-LESS URL WAS NOT THE ONLY DOOR, AND SHUTTING IT ALONE WOULD HAVE LEFT THE WIDEST ONE OPEN.**
# ``transcribe_audio_bytes`` is reachable from three places in this backend, and a gate is only as narrow
# as the widest route onto the thing it gates — so all three are named here rather than one per file:
#
#   1. ``POST /{workshop_id}/dictate`` below — the gate. Consent is read here and nowhere else.
#   2. ``POST /dictate`` — this address, now 410.
#   3. ``POST /media/transcribe`` — a clip nobody has uploaded, transcribed and returned, storing no row
#      and leaving no record that it ran. It carries no workshop either, so no consent could be consulted
#      on it, AND IT WAS ``get_current_user``: every signed-in account down to CROWDSOURCE_VOLUNTEER,
#      four ranks below the designer this gate exists to refuse. Measured by driving the mounted router
#      with the database replaced by a tripwire — a volunteer's POST ran the whole way into the provider
#      chain. A designer refused by the 409 below could have re-posted the identical clip there and read
#      the words back. It is now ``require_admin``, matching ``POST /media/{id}/transcribe-now``, the
#      stored-clip twin it duplicates; the argument, and the honest account of what that gate does NOT
#      achieve, is written above the route in ``api/routes/media.py``.
#
# WHAT THE CONSENT STILL DOES NOT GOVERN, said here so nobody has to discover it: a recording ATTACHED to
# a workshop as media goes through the transcription queue, which calls the same provider chain and reads
# no consent column. That is a deliberate boundary rather than an oversight — plan §6 answer 3 makes the
# answer govern the dictation rung — and it is stated to the artisan on the screen where the decision is
# taken (``DwDictationConsent.kt``'s ``DW_CONSENT_MEDIA_NOT_GATED``), because a consent screen that
# implied otherwise would be promising something no code enforces.
#
# THE CAP WAS NEVER SPLIT THAT WAY, which is worth stating because it is the half that was never open:
# the daily allowance is per DESIGNER and needs no workshop, so the id-less route enforced it in full
# for as long as it transcribed anything — both routes shared the body below, and the ceiling is its
# first act. Consent was the bypass. Provider spend was bounded the whole time.
#
# ROUTE ORDERING, AND THE MICROPHONE IT HAS ALREADY COST. The literal ``/dictate`` and
# ``/dictation-allowance`` resolve only because they are declared ABOVE ``GET /{workshop_id}``;
# ``app/api/router.py`` records the same collision one router over, where ``GET /{workshop_id}``
# swallowed ``/design-workshops/eligible-viewers``, answered 404 "Record not found", and left the
# admin's designer picker empty on a server that had the route. Nothing may be declared above them. **That ordering rule protects a PATH and never a METHOD, and the
# difference silently deleted a feature in the browser.** ``{workshop_id}`` matches ``[^/]+``, so with
# no literal GET declared here, ``GET /design-workshops/dictate`` full-matched ``GET /{workshop_id}``
# — a POST route cannot answer a GET, it only makes the request a 405 candidate — looked up a workshop
# whose id is "dictate", and answered 404. The browser's capability probe reads 404 as "this deployment
# does not offer dictation" and renders no microphone at all. ``dictation_probe`` below is the literal
# that was missing; ``/ocr/identity`` was never affected because a path with a slash in it cannot match
# a single ``{workshop_id}`` segment, which is why only one of the two probes was broken.
# --------------------------------------------------------------------------------------


@router.post("/ocr/identity")
async def scan_identity_card(
    file: UploadFile = File(...),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Read the Aadhaar / Pehchan numbers off a photograph of an artisan's card.

    THE RESPONSE FILLS A FORM FIELD. IT NEVER WRITES ANYTHING. No artisan is created, no number is
    stored, nothing is matched against an existing record — the candidates come back, the designer
    reads them against the card still in their hand and presses save. That is not caution for its
    own sake: ``Artisan.aadhaarNumber`` is the deduplication key for the entire repository, and a
    number that arrives from an OCR read and commits itself is a wrong national identity number
    entering a research database with nobody in the loop to catch it. The one human who can compare
    the digits to the card is standing right there; this endpoint's whole job is to save them the
    typing, not the checking.

    Every 12-digit candidate has already been through the UIDAI Verhoeff checksum in
    :mod:`app.services.identity_ocr` — the read is the failure mode, and the checksum is what
    catches it. Pehchan numbers are normalised through ``artisan_identity.normalize_pehchan`` so an
    OCR read and a typed entry of the same card cannot be stored as two different strings.

    503 when no vision provider is configured, naming the setting, because the alternative — a 200
    with an empty candidate list — is indistinguishable from "the card was unreadable" and would
    have a designer re-photographing a card in better light forever.
    """
    _require_designer(current_user)
    settings = get_identity_ocr_settings()
    content = await file.read()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No image was uploaded."
        )
    if len(content) > settings.max_image_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"The image is larger than the {settings.max_image_bytes // (1024 * 1024)} MB "
                "limit. Photograph the card alone rather than the whole page."
            ),
        )
    mime_type = (file.content_type or "image/jpeg").split(";")[0].strip().lower()
    if mime_type not in SUPPORTED_MIME_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"{mime_type or 'That file type'} cannot be read; send a JPEG, PNG or WebP.",
        )
    try:
        result = await read_identity_card(content, mime_type)
    except IdentityOcrUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)
        ) from exc
    # ``content`` goes out of scope here and is never written anywhere. A photograph of a national
    # identity document is retained only when a designer deliberately uploads it through the media
    # flow, which is a visible act with a record; this endpoint has no storage path at all.
    return result.payload()


# A dictated sentence is seconds of speech. The cap is what stops this synchronous endpoint from
# becoming a back door into the transcription queue: a designer who wants a 40-minute interview
# transcribed uploads it as media and it is queued off-peak with retries and rate-limit backoff,
# whereas this holds a worker for the whole provider round trip. Two minutes of Opus is well under
# a megabyte; 6 MB covers a long dictation in an uncompressed format and refuses a recording that
# was never a dictation.
DICTATION_MAX_BYTES = 6 * 1024 * 1024


async def _transcribe_one_dictation(
    file: UploadFile, languageHint: str | None, current_user: Any
) -> dict[str, Any]:
    """Everything a dictation spends: the cap, the size checks, the provider, the allowance.

    ONE CALLER SINCE THE ID-LESS DOOR WAS RETIRED, and it stays a function anyway. What the route above
    it owns is the GATE — the workshop load and the 409 — and what this owns is the SPEND, whose four
    checks are in an order that four separate tests assert and that a reader must not rearrange. Folded
    into the handler, that order would sit underneath a paragraph about consent and be read as part of
    it; kept here it is the subject of its own explanation, which is what it is.

    **THE ORDER OF THE FOUR CHECKS IS LOAD-BEARING and each one is placed against a failure:**

    1. **The cap first, before the body is even read.** It is the only refusal here that no amount of
       re-recording can clear, so telling a designer their clip is too large when their allowance is
       gone would send them off to record a shorter one for nothing. It also means the refusal costs
       one primary-key lookup rather than a provider round trip.
    2. **Empty, then over-size** — both 4xx, both before any provider is touched, and NEITHER of them
       spends an allowance. A clip refused for size never reached a provider; charging for it makes
       the ceiling arrive early for a reason the designer cannot see.
    3. **The provider, and its 503 spends nothing either.** "No transcription is configured" is the
       server's own misconfiguration and the one refusal a designer can do nothing whatever about; a
       deployment with no API key must not silently exhaust every designer's day.
    4. **The counter last**, so what is counted is what actually reached a provider — including an
       empty or failed transcription, because the credit is spent by the call and counting only
       successes would leave the ceiling uncapped for exactly the failure mode that produces the most
       retries.

    THE ALLOWANCE COMES BACK ON THE 200 AS WELL AS IN THE REFUSAL, which is the whole reason the cap
    is not merely a 429. A phone that can learn the ceiling only by being refused has to spend a
    six-megabyte upload to learn it, and then another one tomorrow; with the four keys on every
    successful dictation the handset knows from the last one whether the next is worth attempting, and
    can fall back to its own recogniser with zero bytes uploaded. Both clients decode leniently — every
    property of the Android DTO is defaulted and the web types the response as an all-optional object —
    so a build that predates these keys simply does not see them.
    """
    # THE COUNT IS THE SERVER'S. A client-side counter is a client-side counter: process memory clears
    # on a swipe-away, and a spend ceiling defeated by a swipe-away is the one direction that costs
    # money rather than a retry. The handset's mirror of these numbers exists so the dictation control
    # can drop this rung BEFORE recording; it enforces nothing and is not consulted here.
    allowance = await dictation_cap.load_allowance(current_user.id)
    refusal = dictation_cap.cap_refusal(allowance)
    if refusal is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            # A 429 AND NOT A 403, because this clears itself: the allowance returns at midnight India
            # time and the sentence says so. A 403 would tell a client that this account may never do
            # this, which is what the consent gate means and this does not.
            #
            # THE DETAIL IS THE COPY A DESIGNER READS IN A COURTYARD, not a log line — the Android
            # control prints the server's own sentence verbatim for every answer that is not the
            # route's own 503 — so it names the limit and when the allowance returns.
            #
            # A STRING AND NOT A DICT, deliberately, even though the allowance numbers would be useful
            # here: a client that shows `detail` verbatim would print a dictionary's repr at somebody
            # in a village. The machine-readable copy of the same facts is on the 200 path and on
            # GET /dictation-allowance, which is where a phone learns the ceiling without being
            # refused at all.
            detail=refusal,
        )

    content = await file.read()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No audio was uploaded."
        )
    if len(content) > DICTATION_MAX_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"A dictated clip may be at most {DICTATION_MAX_BYTES // (1024 * 1024)} MB. "
                "Upload a longer recording as workshop audio instead — it is transcribed in the "
                "background and the transcript comes back onto the stage."
            ),
        )
    result = await transcribe_audio_bytes(
        content,
        file.filename or "dictation.webm",
        (file.content_type or "audio/webm").split(";")[0].strip(),
        get_settings(),
    )
    if str(result.get("status") or "").upper() == "UNAVAILABLE":
        # The same reasoning as the OCR route: an empty 200 reads as "you said nothing". And nothing is
        # counted: no provider was called, so no credit was spent, and the designer's allowance is not
        # the place to record that an administrator has not added an API key.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(result.get("message") or "Transcription is not configured."),
        )

    # Reached a provider, so it counts — whatever the provider then said. `spend` returns None when the
    # increment itself failed, and the response then reports the count this server can stand behind
    # rather than an optimistic one: the words are already produced and a designer must not be handed a
    # 500 for text they can see.
    counted = await dictation_cap.spend(current_user.id, allowance.day)
    return {
        "status": result.get("status"),
        # The plain text, never the speaker-labelled Markdown: this is going straight into a form
        # field, and "**Speaker 1:**" is not something a designer wants to delete by hand.
        "text": result.get("text") or "",
        "provider": result.get("provider"),
        "languageHint": languageHint,
        "message": result.get("message"),
        **dictation_cap.allowance_payload(
            dictation_cap.Allowance(
                day=allowance.day,
                limit=allowance.limit,
                used=allowance.used if counted is None else counted,
            )
        ),
    }


@router.get("/dictation-allowance")
async def get_dictation_allowance(
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """How many server dictations this designer has left today, and where the day ends.

    **THIS ROUTE IS WHY THE CAP IS NOT JUST A 429.** Plan §6.1 requires the ceiling be "named in words
    when it is hit", and a phone can only name a number it has been told. Without this, a handset that
    has been swiped away — or opened for the first time this morning — knows nothing about the ceiling
    until it has spent a six-megabyte upload to be refused, which is precisely the failure
    ``DwDictationUpload.kt`` already records for the 503: *"a six-megabyte upload per field, each one
    spending mobile data to be told the same thing."* Two primary-key reads and no upload.

    IT ANSWERS FOR THE CALLER AND FOR NOBODY ELSE. There is no ``userId`` parameter and there must not
    be one: the allowance is a fact about the signed-in account, and a route that could be asked about
    somebody else's spend would be a report on a colleague's working day.

    ``dictationDay`` is the load-bearing key. It is the SERVER's India-time date, so a phone can tell a
    cached "spent" that is still true from one that belongs to yesterday — and a mirror whose day no
    longer matches must resolve to *not spent*, so the phone tries once and learns the truth here
    rather than silently withholding a capability at the wrong midnight.

    Gated on ``_require_designer`` and nothing else, matching the dictation route it belongs to: there
    is no workshop involved, and the number is this account's own.
    """
    _require_designer(current_user)
    return dictation_cap.allowance_payload(
        await dictation_cap.load_allowance(current_user.id)
    )


@router.get("/dictate")
async def dictation_probe(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Does this deployment offer server-side dictation, and where does a clip go?

    **THIS ROUTE HAS ONE CALLER — THE BROWSER'S CAPABILITY PROBE — AND IT ANSWERS THE ONE QUESTION
    THE PER-WORKSHOP URL CANNOT BE ASKED.** ``serverOffersRoute`` in ``frontend/lib/designWorkshops.ts``
    sends a GET to this exact path and reads ONLY THE STATUS: 404 means "this deployment predates
    dictation", anything else means "it is here", and a network failure is deliberately not cached
    because out here the connection drops for a minute at a time. It has to answer BEFORE any workshop
    is known — the control it decides about is drawn on fields a designer dictates into before the stage
    has ever been saved — so it cannot ask ``/{workshop_id}/dictate``, and it will not POST, because a
    POST probe would reach a handler and do work.

    **WHY IT EXISTS AT ALL: WITHOUT IT THE PROBE ANSWERED 404 ON EVERY DEPLOYMENT.** ``{workshop_id}``
    matches any single segment, and a POST route cannot answer a GET — so with only ``POST /dictate``
    declared here, this GET full-matched ``GET /{workshop_id}``, looked up a workshop whose id is
    "dictate", and 404'd out of ``load_workshop_or_404``. The browser read that as "no dictation on this
    server" and rendered NO MICROPHONE AT ALL for every browser without ``SpeechRecognition`` — Firefox
    ships none, which is the exact case the server fallback was built for. Measured against this
    router's own table rather than remembered, and pinned by a test that drives the GET with the
    database replaced by a tripwire: reaching the database at all is now the failure.

    **200 ALWAYS — IT DOES NOT REPORT WHETHER A PROVIDER IS CONFIGURED, AND THAT IS A DECISION.** The
    probe result is cached per path for the life of the tab, so a "no" here would survive an
    administrator pasting an API key: the microphone would stay hidden until every designer reloaded,
    with nothing on screen to say why. The honest, current answer to "is a provider configured" is the
    503 the upload path already returns with the setting named in it, decided at the moment it matters.
    What this status can stand behind is narrower and always true: this build offers server dictation.

    Gated on ``get_current_user`` alone and NOT on ``_require_designer``, unlike everything else in this
    family. The answer is a fact about the BUILD rather than about the account, and a 403 is read by the
    probe as "offered" exactly as a 200 is — so a rank check here would change no client's behaviour
    while putting a permission on a static string. ``/schema`` and ``/templates`` are gated the same way
    for the same reason.
    """
    return {
        # WHERE A CLIP GOES — a template and not a URL, because it cannot be one: the id does not exist
        # yet at the moment this answer is needed. Named so that anyone who curls this route, or reads
        # it in the OpenAPI schema, is told the move rather than left to find the sibling below.
        "dictatePath": "/design-workshops/{workshop_id}/dictate",
        # WHERE THE CEILING IS READ WITHOUT SPENDING ONE. A phone that can learn its allowance only by
        # being refused spends a six-megabyte upload to learn it; this pairing is what stops that.
        "allowancePath": "/design-workshops/dictation-allowance",
        # The size ceiling, served rather than duplicated. Both clients carry their own copy of this
        # number today, and two copies of a limit are two limits that drift.
        "maxBytes": DICTATION_MAX_BYTES,
        # THE GATE IS PART OF THE CAPABILITY. A client that knows a clip needs the artisan's recorded
        # consent can ask for it before recording, instead of discovering it in a 409 with the recording
        # already made and the artisan already spoken.
        "consentRequired": True,
    }


@router.post("/dictate")
async def dictate(
    file: UploadFile = File(...),
    languageHint: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """RETIRED. This URL transcribed clips without consulting any artisan's consent; it now refuses.

    **WHAT IT WAS.** Speech to text for one dictated passage, taking ``file`` and ``languageHint`` and
    no workshop id — so Plan §6 answer 3's consent, which is a fact about ONE WORKSHOP, could not be
    consulted on it. The gate went on the sibling ``POST /{workshop_id}/dictate`` and this one was left
    open so that handsets and browsers already in the field kept working. Both clients have since moved
    (see the block comment above, which names the two declarations), so what is left here is a URL with
    no caller and a provider chain behind it — an authenticated account in the designer set could post
    an artisan's recorded voice to ElevenLabs, Deepgram or Whisper against a workshop whose artisan had
    refused, simply by choosing the shorter URL. **A gate with a door beside it is not a gate.**

    **410 AND NOT A DELETION**, which is the whole argument for this handler continuing to exist. Delete
    the route and an old build's POST falls through to a 405 whose entire body is "Method Not Allowed" —
    Android prints the server's ``detail`` verbatim to whoever is holding the phone, so a designer in a
    courtyard would read those three words and have no next move. 410 GONE is also the accurate code:
    404 would say "this server has never had dictation", which is false and would send an operator
    looking for a deployment problem, and 403 would say "not you", which is false in the other direction
    — every caller is refused, and updating the app is the only thing that changes it.

    **THE REFUSAL COSTS NOTHING.** No allowance is read, no counter moves, no workshop is loaded and
    ``transcribe_audio_bytes`` is not reachable from here — a retired route that still spent a
    designer's daily allowance would be charging for a capability it no longer provides.

    **THE TWO PARTS ARE STILL DECLARED AND NEVER READ**, which is deliberate: FastAPI parses the
    multipart body before the handler runs, so the upload is consumed and the refusal is written to a
    client that has finished sending. Whether a given ASGI server would deliver a mid-upload refusal to
    a client still writing is unmeasured here, and this removes the question. It is no more expensive
    than the route it replaces — that one parsed the same body and then read every byte of it into
    memory, and this one does not read it at all.
    """
    raise HTTPException(
        status_code=status.HTTP_410_GONE,
        # THE SENTENCE IS FOR TWO READERS AT ONCE, because both meet it: the developer of a build that
        # still posts here, who needs the URL and the reason; and the designer holding that build in a
        # courtyard, who needs a next move that works this afternoon. It never says "try again" — no
        # retry of this request can ever succeed.
        detail=(
            "Dictation has moved: this address no longer accepts a recording. Send the same two parts "
            "to POST /design-workshops/{workshop_id}/dictate, which checks that the artisan agreed to "
            "their recordings being sent for transcription before it sends anything. An app that still "
            "posts here needs updating; type the words in meanwhile."
        ),
    )


@router.post("/{workshop_id}/dictate")
async def dictate_for_workshop(
    workshop_id: str,
    file: UploadFile = File(...),
    languageHint: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The same dictation, for a named workshop — and the only route where consent can be enforced.

    **WHAT THE CLIENTS SEND, AND THE ONLY ROUTE THAT TRANSCRIBES ANYTHING.** ``file``, and
    ``languageHint`` when it is known — the same two parts the retired ``POST /design-workshops/dictate``
    took, unchanged, with the workshop id in the path where every other per-workshop route in this file
    already carries it. Android declares ``@POST("design-workshops/{id}/dictate")`` and the browser's
    ``dictateAudio`` takes a required ``workshopId``; the id-less URL now answers 410, so there is no
    longer a second way in for either of them to fall back to by accident.

    **THE GATE.** ``DesignWorkshop.dictationConsent`` must be GRANTED. Until it is, this refuses with a
    409 and a sentence naming the next move — and the next move is a person deciding, never a retry,
    so neither refusal says "try again". The two states that refuse are NOT_RECORDED (nobody has asked
    the artisan) and REFUSED (the artisan said no), and they get different sentences because they have
    different next moves: see ``services/dictation_consent.gate_refusal``.

    **A 409 AND NOT A 403.** A 403 is about the CALLER — this account may not do this — and would be
    wrong in both directions here: the designer is entitled to dictate, and a colleague with the same
    rank would be refused identically. What is not in a state to permit the send is the WORKSHOP, which
    is the distinction the AI-layer routes beside this one already draw between "this body describes
    something that cannot exist" and "that row is not in a state where this is possible".

    **CONSENT IS CHECKED BEFORE THE CAP, and the order is deliberate.** A workshop with no consent is
    refused whatever the allowance says, and telling a designer their daily allowance is spent when the
    real blocker is a question nobody has asked the artisan would send them off to wait for midnight
    for nothing. The cap's refusal clears by itself; this one clears only when somebody records an
    answer.

    **THE GATE IS RUNG 2's AND NEVER RUNG 1's.** A phone with an installed on-device pack keeps
    dictating with no consent question at all — offline, free, and *because nothing leaves the device*.
    Nothing here is reachable from that path.

    THE SAME TWO GATES AS EVERY OTHER WRITE IN THIS FAMILY: ``_require_designer``, then
    ``load_workshop_or_404(..., for_edit=True)``. ``for_edit`` because sending an artisan's recording to
    a third party is not something to permit against a deleted workshop, and because it is the pair the
    stage writes already use.
    """
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    refusal = dictation_consent.gate_refusal(dictation_consent.consent_of(record))
    if refusal is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=refusal)
    return await _transcribe_one_dictation(file, languageHint, current_user)


@router.post("/{workshop_id}/dictation-consent")
async def record_dictation_consent(
    workshop_id: str,
    payload: DictationConsentIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Record whether this workshop's recordings may be sent to the transcription service.

    Plan §6 answer 3: *"per workshop, recorded, and it gates rung 2 … with who set it and when."*

    **ITS OWN ROUTE, AND NEVER PATCH, which is the most important line in this docstring.**
    ``PATCH /{workshop_id}``'s writable set is a hand-written tuple of key names copied in a loop that
    records neither the actor nor the moment, and its schema documents itself as the route for "an
    admin correcting a list entry without opening the stage". A value whose entire point is who set it
    and when cannot ride a generic field-copy loop: it would arrive with no name against it and no
    moment, which is the same defect that rules a registry field out (see
    ``services/dictation_consent.py``).

    **TWO WRITES, AND BOTH COME BACK.** The three columns on the workshop are the current answer the
    gate reads; the ``DwWorkshopConsentDecision`` row is the history, which is what keeps "who cleared
    this workshop's recordings on the 3rd" answerable after a withdrawal on the 9th — by which time
    transcripts made under the grant are already in the record. Returning the log as well as the
    answer is what stops a client rendering this as a checkbox, exactly as ``accept_ai_layer`` argues
    one route over.

    **``recordedAt`` IS THE COURTYARD MOMENT.** A consent recorded offline reaches this server on the
    next sync, which on this fleet can be a fortnight later, so a client that answered in a village
    sends the moment it happened and that is what lands on the workshop. When the server heard it is
    the log row's own ``createdAt``, and both are kept because they are two different questions. A time
    in the future is refused rather than corrected — see ``MAX_DEVICE_CLOCK_SKEW``.

    **REFUSED IS HOW A CONSENT IS WITHDRAWN**, and it is not a special case: it is another decision,
    another log row, and the gate closes on the next dictation. There is no route that un-records an
    answer, because ``NOT_RECORDED`` means "nobody has asked" and a gate cannot tell a withdrawn
    consent from an unopened workshop if the two are stored the same way.

    A 422 CARRIES A REFUSED WRITE and a 409 is deliberately not used here: everything this route can
    refuse is a statement about the BODY — a decision nobody can record, an actor with no id, a clock
    in the future — rather than about the workshop's state. The workshop's own state is what
    ``load_workshop_or_404(for_edit=True)`` answers for.

    THE SAME TWO GATES AS EVERY OTHER WRITE IN THIS FAMILY, and no new permission concept: recording
    the artisan's answer is the ordinary work of the designer sitting with them.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    try:
        plans = dictation_consent.decision_plans(
            workshop_id=workshop_id,
            decision=dictation_consent.DictationConsent(payload.decision),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            # Parsed with the module's own lenient helper, which is safe HERE and nowhere else in this
            # body: `DictationConsentIn` has already refused an unparseable value with a sentence, so
            # this cannot silently fall back to None for a moment the client actually stated.
            recorded_at=_parse_datetime(payload.recordedAt),
            note=payload.note,
        )
    except dictation_consent.ConsentRuleViolation as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    updated = await dictation_consent.apply_decision(plans)
    # A WITHDRAWAL REACHES THE RECORDINGS ALREADY IN THE QUEUE, which is the difference between a
    # consent and a preference. Nine clips queued under a grant given on the 3rd are rows waiting for
    # the off-peak window; a REFUSED recorded on the 9th has to stop them, or the artisan's change of
    # mind arrives after the sends it was about. The queue drain re-reads consent as well, so this is
    # the fast, visible half of two defences — see `dictation_consent.cancel_pending_transcriptions`.
    #
    # AFTER the decision is applied, never before: the recorded answer is the artisan's and must land
    # even if the cancellation fails. It never raises, and the count is deliberately not returned to the
    # client — a number of cancelled jobs is not an answer to "what is this workshop's consent", and a
    # client that rendered it would be reporting the queue's internals on a consent screen.
    #
    # ON A **REFUSED** DECISION AND ONLY ON ONE, and the guard is the whole correctness of this line.
    # `cancel_pending_transcriptions` is unconditional about what it writes: every QUEUED/PROCESSING
    # transcription of this workshop's recordings is marked FAILED and every clip gets
    # `gate_refusal(REFUSED, MEDIA)` — "This workshop's recordings may not be sent to the transcription
    # service — that is the answer on record" — put into its `transcriptError`. Run on a GRANTED
    # decision that destroys the queue the grant was recorded in order to fill, and stamps the
    # workshop's own recordings with a sentence saying the artisan refused, which is the opposite of
    # what just happened and is a false statement on a designer's screen. Re-affirming a grant after
    # the recordings are uploaded is an ordinary act, so this is reachable rather than theoretical.
    if (
        dictation_consent.DictationConsent(payload.decision)
        is dictation_consent.DictationConsent.REFUSED
    ):
        await dictation_consent.cancel_pending_transcriptions(workshop_id)
    summary = workshop_summary(updated)
    # The NAME, resolved once, in the single-record answer only — the same rule `get_design_workshop`
    # follows and for its reason: the paged list serialises `workshop_summary` per row and a name
    # lookup there would be a query per workshop to print something the list does not show.
    summary["dictationConsentByName"] = await dictation_consent.actor_name(
        getattr(updated, "dictationConsentById", None)
    )
    return {
        "workshop": summary,
        "decisions": [
            dictation_consent.decision_payload(row)
            for row in await dictation_consent.workshop_decisions(workshop_id)
        ],
    }


# --------------------------------------------------------------------------------------
# The workshop header
# --------------------------------------------------------------------------------------


@router.get("")
async def list_design_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1),
    search: str | None = None,
    statusFilter: str | None = None,
    craftName: str | None = None,
    state: str | None = None,
    mineOnly: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """List workshops, newest first.

    The filters read the promoted columns rather than the JSON, which is the entire reason
    those columns exist — see the note above ``DesignWorkshop`` in schema.prisma.
    """
    where: dict[str, Any] = {"deletedAt": None}
    if statusFilter:
        # THROUGH THE ENUM CHECK, because the raw string went into a Postgres enum column and
        # anything not in it — a lowercase "draft", an "ALL" from a client whose dropdown labels
        # its empty option, a stale bookmarked URL — came back as a bare 500 with
        # {"error": "FieldNotFoundError"} rather than a 422 naming the values a client can send.
        where["status"] = enum_filter_or_422(statusFilter, DESIGN_WORKSHOP_STATUSES)
    if craftName:
        where["craftName"] = contains(craftName)
    if state:
        # `plain`, not the raw value: a NUL byte cannot live in a Postgres text column, so
        # ?state=%00 raised a DataError inside the driver and surfaced as a 500 — a logged server
        # error with a stack trace, which the web then shows the designer as "you are offline".
        where["state"] = plain(state)
    if search:
        # `contains` rather than the hand-rolled dict this used to build: the helper is where the
        # bytes Postgres cannot store are stripped, and building the filter by hand here was
        # exactly how this one search box opted out of that (?search=%00 -> 500).
        where["OR"] = [
            {"title": contains(search)},
            {"craftName": contains(search)},
            {"clusterName": contains(search)},
            {"workshopCode": contains(search)},
        ]
    # A non-admin sees the workshops they created OR were let into by an admin. An explicit
    # mineOnly narrows to their own without changing anyone else's — and it means OWN, so it
    # deliberately excludes the granted ones rather than reusing the scope clause below.
    #
    # THE LIST IS HALF THE FEATURE. `load_workshop_or_404` admitting a grant-holder is invisible on
    # its own, because nothing in either client navigates to a design workshop by typed id: a
    # colleague whose grant the list does not honour is simultaneously told the workshop exists and
    # that it does not. See app/services/design_workshop_viewers.py.
    #
    # AND-composed, NOT assigned to where["OR"], which the search box above has already taken. Two
    # assignments to that one key and the later silently wins — either the search stops narrowing
    # or the grant vanishes the moment somebody types. Same warning, same reason, as
    # `services/records.owned_or_granted_where`.
    if mineOnly:
        where["createdById"] = current_user.id
    elif not is_admin(current_user):
        where.setdefault("AND", []).append(visible_to_clause(current_user.id))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await asyncio.gather(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([workshop_summary(r) for r in rows], total, clean_page, clean_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_design_workshop(
    payload: DesignWorkshopCreate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Start a workshop.

    Only the title is required. A workshop is created in a room on day one, before the sanction
    order number is to hand; the Basic-tier fields of stage 1 are enforced when a report is
    generated, not here.
    """
    # BOTH gates, and the designer one is the point.
    #
    # `assert_can_create_records` is Researcher-and-above — the repository-wide rule for making any
    # record. On its own it left this endpoint open to a RESEARCHER and a PROFESSOR while
    # `frontend/lib/permissions.ts` hid the pages from exactly those two, which is a UI guard over
    # an open route: the link disappears and the URL, the API and the Android client do not. A
    # design workshop is a fortnight of a named designer's work that ends in a document submitted
    # under their name, so the capability that decides who may start one is
    # `can_run_design_workshops`, and it has to be enforced HERE rather than in the browser.
    assert_can_create_records(current_user)
    _require_designer(current_user)
    data: dict[str, Any] = {
        "title": payload.title.strip(),
        "templateId": payload.templateId,
        "createdById": current_user.id,
        "schemaVersion": registry_version(),
        "status": "DRAFT",
    }
    for key in ("craftName", "clusterName", "state", "district", "notes", "workshopId"):
        value = getattr(payload, key)
        if value:
            data[key] = value
    for key in ("startDate", "endDate"):
        parsed = _parse_date(getattr(payload, key))
        if parsed:
            data[key] = parsed

    record = await db.designworkshop.create(data=data)
    # The designer's own details, copied out of their profile into stage 1 and stage 3 before the
    # form is ever opened, so nobody retypes their institution and biography twenty-two stages
    # into their fifth workshop of the year. Written as ordinary stage entries — the report reads
    # them with no special case at all — and copied rather than referenced, because a report is a
    # historical document. See ``seed_designer_prefill``.
    record = await seed_designer_prefill(record, current_user)
    return workshop_summary(record)


@router.get("/{workshop_id}")
async def get_design_workshop(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """One workshop with every stage's data and its completeness scores."""
    record = await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_custom_definition_or_empty(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["transcripts"] = await _transcripts_payload(entries, current_user)
    summary["schemaVersion"] = registry_version()
    # CARRIED BESIDE THE SCORE, and that pairing is the point rather than a convenience. The Android
    # stage index adopts the SERVER's score for any stage the device has never touched, so a handset
    # holding an older definition — or none — would show the server's higher `requiredTotal` for
    # untouched stages and its own lower one for touched stages: two arithmetics in one list, with
    # nothing on screen to say why. With the digest beside the score a client can refuse a score
    # computed under a definition it does not hold and fall back to its own number.
    summary["customSchemaVersion"] = definition.version
    # THE ACCEPTOR'S DISPLAY NAME, RESOLVED IN THE SINGLE-RECORD READ ONLY. `workshop_summary` already
    # carries the three consent keys including the id; this is one extra read to turn that id into a
    # name, and it is here rather than in the summary because the paged LIST serialises the summary once
    # per row — a name lookup there would be a query per workshop to print something the list does not
    # show. None when nobody has recorded an answer, and None when the account has since been deleted:
    # `dictationConsentById` is SetNull, so a workshop can legitimately carry an answer with no name
    # against it, and the honest rendering of that is "cleared by somebody no longer on record" rather
    # than a guess at the workshop's owner.
    summary["dictationConsentByName"] = await dictation_consent.actor_name(
        getattr(record, "dictationConsentById", None)
    )
    return summary


@router.patch("/{workshop_id}")
async def update_design_workshop(
    workshop_id: str, payload: DesignWorkshopUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    data: dict[str, Any] = {}
    for key in ("title", "templateId", "craftName", "clusterName", "state", "district",
                "notes", "workshopId", "status"):
        value = getattr(payload, key)
        if value is not None:
            data[key] = value.strip() if isinstance(value, str) else value
    for key in ("startDate", "endDate"):
        raw = getattr(payload, key)
        if raw is not None:
            data[key] = _parse_date(raw)
    if not data:
        return workshop_summary(record)
    updated = await db.designworkshop.update(where={"id": workshop_id}, data=data)
    return workshop_summary(updated)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_design_workshop(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> None:
    """Soft-delete. The row and every stage entry stay; only ``deletedAt`` is set.

    Deliberately not a hard delete, and deliberately unlike the rest of this codebase, which
    has no soft delete anywhere. The requirement that data is retained for research, and the
    fact that one row here represents weeks of fieldwork by someone who is no longer in the
    village, both point the same way.
    """
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    assert_can_delete(current_user)
    await db.designworkshop.update(
        where={"id": workshop_id},
        data={"deletedAt": datetime.now(UTC), "deletedById": current_user.id},
    )


@router.post("/{workshop_id}/restore")
async def restore_design_workshop(
    workshop_id: str, _: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Undo a soft delete. Admin only — the point of the safety net is that it is not per-user."""
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    restored = await db.designworkshop.update(
        where={"id": workshop_id}, data={"deletedAt": None, "deletedById": None}
    )
    return workshop_summary(restored)


# --------------------------------------------------------------------------------------
# Stages
# --------------------------------------------------------------------------------------


@router.get("/{workshop_id}/stages")
async def list_stages(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_custom_definition_or_empty(workshop_id)
    return {
        "stages": _stages_payload(entries),
        "completeness": workshop_completeness(entries, definition=definition),
        "transcripts": await _transcripts_payload(entries, current_user),
        "schemaVersion": registry_version(),
        "customSchemaVersion": definition.version,
    }


@router.get("/{workshop_id}/stages/{stage_key}")
async def get_stage(
    workshop_id: str, stage_key: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")
    entries = await entry_rows(workshop_id, stage_key=stage_key)
    definition = await load_custom_definition_or_empty(workshop_id)
    payload = _stages_payload(entries).get(stage_key) or {
        "singleton": {}, "collections": {}, "custom": {}
    }
    payload["completeness"] = workshop_completeness(entries, definition=definition).get(stage_key)
    payload["transcripts"] = await _transcripts_payload(entries, current_user)
    payload["customSchemaVersion"] = definition.version
    return payload


@router.put("/{workshop_id}/stages/{stage_key}")
async def save_stage_data(
    workshop_id: str, stage_key: str, payload: StageSaveIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Save a whole stage in one write.

    Returns the cleaned data as stored, any per-field validation errors, and the field keys
    that were DROPPED because the registry does not know them — the last of which is how a
    server notices that a phone is running ahead of it, rather than by rejecting the sync.

    ``submit=true`` enforces the Basic-tier required fields and 422s if any is missing; the
    default leaves the stage a draft, because a stage half-filled overnight is the normal state
    of this app, not an error.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")

    result = await save_stage(workshop_id, spec, payload, current_user)
    if result["errors"] and payload.submit:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"message": "Some required fields are missing", "errors": result["errors"]},
        )
    return result


# --------------------------------------------------------------------------------------
# Designer-defined sections
#
# Step 6 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5. The rules these two routes serve — what a
# key may look like, what may collide with what, and what happens to a question that is reworded
# after somebody has answered it — are written out in full in ``app/services/custom_sections.py``.
# This file holds the thin half: who may call, and how a refused rule becomes a status code.
#
# **THERE IS NO NEW PERMISSION CONCEPT HERE, AND THAT IS THE DIVIDEND OF SCOPING A DEFINITION TO ONE
# WORKSHOP** (plan §6, answer 2). Reading is whatever ``load_workshop_or_404`` allows — the creator,
# an admin, or an account an admin has given a ``DesignWorkshopViewer`` row — and writing is the same
# pair of gates ``save_stage_data`` uses: ``_require_designer`` plus ``for_edit=True``. A definition
# is a stage write in every sense that matters: it decides what a stage asks.
#
# HOW A REFUSED RULE BECOMES A STATUS CODE: a broken definition rule is a 422 carrying EVERY
# violation rather than the first, because a designer fixing a form one round trip at a time gives
# up on the third. A ``CustomSectionEditError`` is a 409 — it means the definition is fine and the
# STATE will not allow this particular change, which is the same distinction the AI-layer routes
# draw between "this body describes something that cannot exist" and "that row is not in a state
# where this is possible".
# --------------------------------------------------------------------------------------


def _spec_from_body(section: Any) -> CustomSectionSpec:
    """One request body section as the pure spec the service validates and plans against.

    AN UNKNOWN TYPE OR TIER TOKEN IS NAMED IN A 422 AND NEVER QUIETLY TURNED INTO TEXT.
    ``DwFieldType.of`` on the handset degrades an unknown token to TEXT and the web's ``switch`` has
    no ``default`` at all, so the same mistake shows as a silent wrong answer on one client and a
    blank on the other — which is exactly why the server has to be the one place it is named out
    loud. It is refused HERE rather than by ``validate_definition`` only because the pure spec holds
    a ``FieldType``, which cannot carry a token that is not one; a type this build knows but v1 does
    not allow — IMAGE, REF, RICH_TEXT — is refused there instead, and both messages name the twelve.
    """
    from app.services.stage_schema import FieldType, Tier

    fields: list[CustomFieldSpec] = []
    for index, f in enumerate(section.fields):
        try:
            field_type = FieldType(f.type)
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "message": "This section cannot be saved yet",
                    "problems": [
                        # THE TWELVE, AND NOT EVERY `FieldType` THIS SERVER HAS. Listing the whole
                        # enum offered the designer IMAGE, REF and RICH_TEXT as the way out of this
                        # refusal, and `validate_definition` then refuses all three on the next
                        # round trip — an error message that names a next move the next error takes
                        # away is worse than one that names none.
                        f"Field {f.key!r} is a {f.type!r}, which is not a field type a custom "
                        f"question may be. Choose one of: "
                        + ", ".join(sorted(t.value for t in V1_FIELD_TYPES))
                    ],
                },
            ) from None
        try:
            tier = Tier(f.tier)
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "message": "This section cannot be saved yet",
                    "problems": [
                        f"Field {f.key!r} names the tier {f.tier!r}. Choose BASIC, STANDARD or "
                        f"ADVANCED — only a BASIC field may be required."
                    ],
                },
            ) from None
        fields.append(CustomFieldSpec(
            key=f.key,
            label=f.label,
            type=field_type,
            tier=tier,
            required=f.required,
            help=f.help,
            unit=f.unit,
            options=tuple(CustomOption(value=o.value, label=o.label) for o in f.options),
            max_length=f.maxLength,
            min_value=f.minValue,
            max_value=f.maxValue,
            # The order the designer put them in, defaulted to the order they arrived in. A
            # definition that left every sortOrder at 0 would otherwise print and score in whatever
            # order the database handed the rows back, which is stable until it is not.
            sort_order=f.sortOrder or index,
        ))
    return CustomSectionSpec(
        key=section.key,
        title=section.title,
        stage_key=section.stageKey,
        description=section.description,
        sort_order=section.sortOrder,
        fields=tuple(fields),
    )


@router.get("/{workshop_id}/custom-sections")
async def get_custom_sections(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """The questions this workshop's designer added to it, and the digest of them.

    **RETIRED SECTIONS AND FIELDS ARE ALWAYS RETURNED**, and it is not a debugging convenience.
    ``DwQuestionnaireStore`` refuses a payload fetched without ``includeRetired`` for exactly this
    reason: a copy missing every answer given under a superseded wording makes two copies of one
    report disagree about the fieldwork, with nothing in either saying so. A client OFFERS the live
    ones and PRINTS the retired ones that hold an answer.

    Readable by anyone who can read the workshop, which is the whole of the access rule — see the
    section header above for why no new one was needed.
    """
    await load_workshop_or_404(workshop_id, current_user)
    return definition_payload(await load_definition(workshop_id))


@router.put("/{workshop_id}/custom-sections")
async def save_custom_sections(
    workshop_id: str, payload: CustomSectionsIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Replace this workshop's whole custom definition in one write.

    A WHOLE-SET PUT AND NOT A PER-FIELD PATCH, matching the viewer-roster idiom and keeping "one
    definition, one digest" atomic: ``customSchemaVersion`` is what every client compares its cached
    copy against, and a definition assembled from six independent PATCHes has six intermediate
    digests, each of which some handset can fetch and cache as though it were the definition.

    **A REPLACE IS NOT A DELETE**, and this is the paragraph to read before changing anything here.
    What is absent from the body is RETIRED if it has answers and removed only if it does not — the
    rule ``services/questionnaire_forms.py`` states in full, obeyed here for its reason: *"How many
    looms?" answered "12", reworded to "How many weavers?", and a ministry report now states there
    are twelve weavers.* Rewriting the label of an answered field therefore supersedes it rather than
    editing it, and the answers stay readable under the wording they were given.

    THE ONE PLACE THIS IS WEAKER THAN THE QUESTIONNAIRE IT COPIES: ``QuestionnaireFormAnswer`` has a
    RESTRICT foreign key underneath it, so that module's rule survives a ``delete_many`` written by
    somebody who never read the docstring. Custom answers are JSON keyed by a string, so no foreign
    key exists and none can. ``plan_definition`` is the only thing that can express a delete here,
    it refuses to express one for an answered field, and a test asserts the refusal.

    THE SAME TWO GATES ``save_stage_data`` USES, deliberately and with nothing added: a definition
    decides what a stage asks, which makes it a stage write.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)

    incoming = [_spec_from_body(section) for section in payload.sections]
    problems = validate_definition(incoming)
    if problems:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            # EVERY violation, not the first. The definition editor shows them against the rows they
            # name, and a designer who has to make six round trips to learn six things stops using
            # the feature after the third.
            detail={"message": "This definition cannot be saved yet", "problems": problems},
        )

    stored = await load_definition(workshop_id)

    # THE STALE-TAB GATE. A whole-set replace is last-write-wins by construction, and this is the
    # only thing standing between that and two designers deleting each other's questions under a
    # 200. Measured on the wire before it was written: designer 1 saves ['dye'], designer 2 adds
    # ['dye','looms'], designer 1's tab — open since before the second save — presses Save and the
    # `looms` section and both its fields are REMOVED, correctly by `plan_definition`'s own rule,
    # because nothing had answered them yet. No 409, no warning, nothing on either screen.
    #
    # CHECKED AFTER `validate_definition` AND NOT BEFORE, deliberately. Those problems are the
    # BODY's own — the function is pure and never looks at what is stored — so they are true no
    # matter who else wrote in the meantime, and a designer whose definition is malformed should be
    # told that rather than told to reload and then told it again.
    #
    # ABSENT IS NOT STALE. A body that omits the field is a client that predates this check, and it
    # keeps the old behaviour rather than being refused; see `CustomSectionsIn.customSchemaVersion`
    # for why that is the trade and not an oversight.
    if (
        payload.customSchemaVersion is not None
        and payload.customSchemaVersion != stored.version
    ):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            # BOTH DIGESTS, NAMED. "Someone else changed this" with no handles leaves the editor
            # unable to tell a genuine conflict from its own stale cache, and leaves a bug report
            # with nothing in it. `expected` is what this client held; `actual` is what is stored.
            detail={
                "message": (
                    "These questions were changed by someone else while this page was open. "
                    "Reload to see the current version before saving."
                ),
                "code": "custom_sections_conflict",
                "expected": payload.customSchemaVersion,
                "actual": stored.version,
            },
        )

    entries = await entry_rows(workshop_id)
    # WHAT COUNTS AS ANSWERED, ASKED OF THE STORED ANSWERS AND OF NOTHING ELSE. It is the
    # completeness scorer's own `_is_filled`, so "answered" means one thing across this system — a
    # field the readiness screen counts as filled is a field whose wording is now evidence.
    answered = {
        row.stageKey: answered_keys(
            stored.fields_for(row.stageKey), dict(row.data or {})
        )
        for row in entries
        if row.entityKey == CUSTOM_ENTITY_KEY
    }

    try:
        plan = plan_definition(stored.sections, incoming, answered)
    except CustomSectionEditError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    await apply_definition_plan(plan, workshop_id, actor_id=current_user.id)

    saved = await load_definition(workshop_id)
    return definition_payload(saved) | {
        # What the write actually did, so the editor can say "two questions were kept under their
        # old wording" rather than leaving a designer to notice it on the next report.
        "created": plan.created,
        "superseded": plan.superseded,
        "retired": plan.retired,
        "removed": plan.deleted,
    }


# --------------------------------------------------------------------------------------
# Reference pickers
# --------------------------------------------------------------------------------------


@router.get("/{workshop_id}/references")
async def list_references(
    workshop_id: str,
    model: str = Query(min_length=1, max_length=64),
    scope: str = Query(REF_SCOPE_ALL, max_length=16),
    filterBy: str | None = Query(None, max_length=64),
    search: str | None = Query(None, max_length=120),
    limit: int = Query(REFERENCE_LIMIT_DEFAULT, ge=1, le=REFERENCE_LIMIT_MAX),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The options one REF field's dropdown shows.

    This is the endpoint that lets a designer STOP RETYPING records the database already holds.
    Every REF field in the registry carries a ``refModel``, a ``refScope`` and sometimes a
    ``refFilterBy``; a client renders a picker from those three and asks here for its contents,
    which is why all three arrive back as query parameters rather than being re-derived on this
    side. Deriving them here would mean the form and the server each held their own idea of how
    wide the artisan list should be, and the day they disagreed the picker would quietly widen
    rather than fail.

    ``filterBy`` is the cascade: the artisan chosen on the row, so the product dropdown holds
    that artisan's products and nothing else. It accepts either an ``Artisan`` id or a roster
    entry id, because stage 6 and stage 13 hold different kinds of id in the same-named field
    and no client should have to know that.

    Readable by anyone who can read the workshop. The options are records they can already list
    through ``/records``; refusing them here would only mean the designer opens a second tab and
    copies the name across by hand, which is the behaviour being replaced.
    """
    record = await load_workshop_or_404(workshop_id, current_user)
    return await reference_options(
        record, model, scope=scope.upper(), filter_by=filterBy, search=search, limit=limit
    )


@router.get("/{workshop_id}/transcripts")
async def list_workshop_transcripts(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Every recording this workshop collected, and what its transcript currently looks like.

    This is what the ``includeTranscripts`` toggle shows BEFORE it is committed to. A designer
    about to append transcripts to a report submitted to a ministry needs to see what they are
    appending — which stage each recording came from, how long it is, how many voices are in it and
    its opening line — because a transcript annexure is the one part of the report whose contents
    nobody has read. Generating a 60-page document to find out is not a preview.

    Recordings with no transcript yet are LISTED, with their status. Hiding them would mean a
    designer who made six recordings and sees four concludes that two were lost, when in fact two
    are still in the queue; ``includedInReport`` says plainly which ones the annexure would carry.
    """
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    items = await load_transcript_items(entries, viewer=current_user)
    payloads = []
    for item in items:
        payload = item.payload()
        payload["includedInReport"] = item.has_text
        payloads.append(payload)
    return {
        "items": payloads,
        "total": len(payloads),
        "withTranscript": sum(1 for item in items if item.has_text),
        "totalDurationSeconds": sum(item.duration_seconds or 0 for item in items) or None,
    }


# --------------------------------------------------------------------------------------
# AI layers
#
# Step 2 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5: the provenance model, with no AI writing.
# The law these five routes serve — every layer is a row, carries its provenance, is inert until a
# person accepts it, is printed only when accepted, and never touches its source when deleted — is
# written out in full in ``app/services/ai_layers.py``. This file holds the thin half: who may call,
# which stored rows the ids are allowed to reach, and how a refused rule becomes a status code.
#
# **NOT ONE OF THESE ROUTES CALLS A PROVIDER.** ``services/ai.py`` is reachable from this module
# (``transcribe_audio_bytes``, for the dictation endpoint above) and no route below touches it. The
# only text that becomes a layer here is text this server already produced and already stores, and
# the request bodies have no field that could carry any other — see ``AiLayerRegisterIn``.
#
# HOW A REFUSED RULE BECOMES A STATUS CODE, decided once here rather than per route:
# ``LayerRuleViolation`` on the REGISTER path is a 422, because there it means "the body describes a
# layer that cannot exist"; on accept, withdraw and delete it is a 409, because there it means "the
# layer is not in a state where this is possible" (already accepted, never accepted, still has
# layers derived from it). Both carry the service's sentence unchanged — it names the next move, and
# rewording it here would give the same rule two voices.
# --------------------------------------------------------------------------------------


async def _layer_or_404(workshop_id: str, layer_id: str) -> Any:
    """One live layer of THIS workshop, or a 404 that says nothing about other workshops.

    The workshop id has already been through ``load_workshop_or_404``; this is the second half of
    the check and it is not decoration. Without the ownership comparison inside
    ``ai_layers.layer_in_workshop``, a layer id belonging to a workshop the caller cannot open could
    be accepted, withdrawn or deleted through one they can. A cuid is unguessable, which makes that
    unlikely rather than impossible, and "unlikely" is not an access rule.

    A deleted layer is a 404 too, which is what makes a repeated DELETE settle instead of resurrect:
    the second call cannot find the row and says so, rather than writing a second deletion stamp over
    the first and losing when it was actually declined.

    THE SENTENCE COVERS THREE CAUSES AND NAMES ONLY THE ACTIONABLE ONE. A bare "Layer not found"
    was the first draft and it is a code with spaces in it: the likeliest way a designer meets this
    is a colleague declining a layer while their own screen still lists it, and "not found" tells
    them nothing to do about that. The three causes — no such id, an id from a workshop they cannot
    open, a layer already declined — are deliberately not distinguished, for
    ``load_workshop_or_404``'s reason: separating them would confirm to a stranger that an id exists.
    Note that this is also why ``ai_layers._live_layer_id``'s own refusal for a deleted layer never
    reaches anybody through the API — it guards the service for callers other than these routes, and
    this is the sentence the routes actually produce.
    """
    row = await ai_layers.layer_in_workshop(workshop_id, layer_id)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That layer is not in this workshop's list any more — it may have been declined by "
                "somebody else while this screen was open. Reload the workshop's layers; a declined "
                "layer stays on record and can be registered again if the material is still wanted."
            ),
        )
    return row


@router.get("/{workshop_id}/ai-layers")
async def list_ai_layers(
    workshop_id: str,
    kind: str | None = Query(None, max_length=32),
    includeText: bool = Query(
        False,
        description=(
            "Carry each layer's full text. Off by default: a workshop can hold twenty-five "
            "interviews and an hour of speech is tens of kilobytes, so the list would be megabytes "
            "on one bar of signal — and unread, because a list shows titles. A row whose text you "
            "may read carries `preview` and `textChars` either way, which is what a list is scanned "
            "by; ask for the text when showing ONE layer to the person about to put their name to "
            "it. A row marked `textWithheld` carries neither, and this flag does not change that: "
            "the recording it stands on is not one this account may read."
        ),
    ),
    includeDeleted: bool = Query(
        False,
        description=(
            "Include layers a designer declined. They are soft-deleted and kept, so 'the model "
            "proposed this and a person said no' stays answerable — but they are out of the default "
            "list, because a declined suggestion re-offered is the same suggestion."
        ),
    ),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Every layer this workshop's material has produced, newest first.

    LISTABLE BY ANYONE WHO CAN READ THE WORKSHOP; READABLE PER RECORDING, WHICH IS NOT THE SAME GATE
    AND THE FIRST DRAFT CONFLATED THEM. The provenance — which tier, which model, accepted by whom —
    is what the plan says a reviewer opens this screen for, and none of it is anybody's recording. A
    layer's TEXT is a different thing: it is a stored COPY of a transcript, and a transcript is the
    content of a media file, gated per file by ``owned_or_granted_where(user,
    owner_field="uploadedById")`` on every other surface in this repository — the stage read, the
    transcripts endpoint, the annexure. A ``DesignWorkshopViewer`` grant carries read and stage
    writes and nothing about media (see ``services/design_workshop_viewers.py``), so those two sets
    genuinely differ, and serving the copy on the workshop gate alone would have handed a granted
    colleague the full text of a recording ``GET /design-workshops/{id}/transcripts`` refuses them —
    out of a table that exists precisely to keep a copy of it. Every row is therefore listed with its
    provenance, and the ones standing on a recording this caller may not read come back with
    ``textWithheld`` true and no text, preview, payload or character count.

    ``accepted`` in the response is the count a client needs to answer "is anything here going into
    the report", without walking the list itself. It counts what ``ai_layers.accepted_layers``
    would return and not what this list happens to contain, which is the same number only while
    ``includeDeleted`` is off: an accepted layer that was afterwards declined still carries its
    ``acceptedAt`` — deletion clears no acceptance, and the acceptance a document already printed is
    not rewritten by a later decline — but the report will not print it, because that read filters
    ``deletedAt: null``. Counting the rows on screen would have answered a different question from
    the one the label asks. Nothing else in this payload is computed from the layers: their content
    is annexure material and a suggestion, and this endpoint deliberately does not summarise, score
    or roll it up into anything a stage might compare against.
    """
    await load_workshop_or_404(workshop_id, current_user)
    wanted: ai_layers.LayerKind | None = None
    if kind:
        try:
            wanted = ai_layers.LayerKind(kind.strip().upper())
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "That is not a layer kind. Ask for one of: "
                    f"{', '.join(k.value for k in ai_layers.LayerKind)}."
                ),
            ) from None
    # THE WHOLE WORKSHOP'S LAYERS, DELETED ONES INCLUDED, AND NARROWED AFTERWARDS IN PYTHON. The
    # narrowing is cheap and the chain walk is not optional: a SUMMARY names the CLEANED_TRANSCRIPT
    # it stands on, which names the RAW_TRANSCRIPT, which names the recording — and it is the
    # recording that decides who may read the text. Asking the database for `kind=SUMMARY` would hand
    # back rows whose parents are missing, and a chain that cannot be walked is withheld, so a
    # narrowed query would silently blank the text of rows the caller is entitled to.
    everything = await ai_layers.workshop_layers(workshop_id, include_deleted=True)
    # `chain_roots` AND NOT `media_roots`, since the verbs landed. A layer rooted in words the caller
    # supplied — a proofread or an expansion of a designer's own field note — reaches no recording,
    # which the older function reports as None and the gate reads as "withhold". Read that way, a
    # designer would be refused the text of their own note in their own workshop. `ChainRoot` is
    # three-valued for exactly this, and it still fails closed on a chain that genuinely cannot be
    # walked.
    roots = ai_layers.chain_roots(everything)
    readable = await _readable_media_ids(ai_layers.media_ids_to_check(roots), current_user)
    rows = [
        row
        for row in everything
        if (includeDeleted or row.deletedAt is None)
        and (wanted is None or ai_layers.is_kind(row, wanted))
    ]
    return {
        "items": [
            ai_layers.layer_payload(
                row,
                include_text=includeText,
                text_withheld=_root_withheld(roots.get(row.id), readable),
            )
            for row in rows
        ],
        "total": len(rows),
        "accepted": sum(
            1 for row in rows if row.acceptedAt is not None and row.deletedAt is None
        ),
    }


def _root_withheld(root: Any, readable: set[str]) -> bool:
    """Whether this caller must not see a layer's content, given what its chain stands on.

    A layer whose root is not in the map at all — which cannot happen while ``chain_roots`` is given
    every row of the workshop, and would happen at once if somebody narrowed that read — is withheld.
    Fails closed in the one direction that matters, and the decision itself is
    ``ChainRoot.withheld_from``'s so that this route and the report loader cannot answer differently.
    """
    return True if root is None else root.withheld_from(readable)


async def _readable_media_ids(media_ids: set[str], viewer: Any) -> set[str]:
    """Which of these recordings this account may read the CONTENT of.

    The predicate is the one every download surface already uses and is not invented here —
    ``owned_or_granted_where(viewer, owner_field="uploadedById")``, AND-composed under the id list
    rather than merged into it because it is an ``OR`` of its own, exactly as
    ``load_transcript_items`` composes it. Professor and above get an empty filter and therefore
    everything, which is that function's rule and not a new one.

    One query for the whole list rather than one per layer: twenty-five interviews at two rungs each
    is fifty layers, and fifty round trips on a link this repository measured at 756ms is a screen
    that never finishes opening.
    """
    if not media_ids:
        return set()
    where: dict[str, Any] = {"id": {"in": sorted(media_ids)}}
    entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
    if entitled:
        where = {"AND": [where, entitled]}
    return {row.id for row in await db.mediafile.find_many(where=where)}


@router.post("/{workshop_id}/ai-layers", status_code=status.HTTP_201_CREATED)
async def register_ai_layer(
    workshop_id: str, payload: AiLayerRegisterIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Record a transcript this server already produced as a layer, with its provenance.

    **THIS ROUTE PRODUCES NOTHING.** It reaches no provider, spends no credit and writes no text of
    its own: it takes a recording the media queue has already transcribed and registers that existing
    text as Tier 3 layers, so the fact acquires a provenance row. The plan is explicit that this is
    the point of doing step 2 before any of the writing — provenance goes in "before there is a
    backlog of unattributed AI text", and the archive of transcripts with no recorded model IS that
    backlog, one row deep.

    **IT REGISTERS ONE RECORDING AND MAY WRITE TWO ROWS, BECAUSE A DEFAULT DEPLOYMENT ALREADY HAS
    TWO RUNGS.** ``AppSetting.transcriptionMode`` defaults to REFINED_TRANSLATED, under which
    ``media_queue`` passes the provider's text through ``ai.refine_transcript_text`` — a rewrite, and
    a translation into English — and stores the rewritten form in ``transcriptText`` while the
    provider's own text goes to ``transcriptSummary``. Registering the column an annexure prints as a
    "raw transcript" would therefore label model prose, in a language the artisan may not have
    spoken, as the artisan's words. ``ai_layers.transcript_rungs`` splits the two apart on the
    evidence in the row itself, and the cleaned rung is written as a CLEANED_TRANSCRIPT derived from
    the raw one — which is the chain the plan draws, discovered in data that already existed.

    WHAT THE SECOND RUNG CLAIMS IS NARROWER THAN "A MODEL WROTE THIS", and the difference is why it
    is registered with no provider, no model, no language and no time. All the row can prove is that
    its text
    is not the provider's own words; a transcript a PERSON corrected through
    ``POST /media/{id}/transcript`` arrives in the identical shape, because that route rewrites
    ``transcriptText`` and leaves ``transcriptSummary`` where it was. Nothing records which happened,
    so nothing here says.

    THE COPY IS THE POINT, NOT AN OPTIMISATION. The layer holds its own copy of the text rather than
    pointing at ``MediaFile.transcriptText``, for the reason ``REFERENCE_HYDRATION`` copies display
    fields at save time and the report never re-resolves them: ``POST /media/{id}/transcript``
    overwrites that column, and a re-transcription would otherwise silently change what an ALREADY
    ACCEPTED layer says — in a document that has already gone to a directorate under somebody's name.

    THE TWO THINGS IT CANNOT BE TOLD, both deliberately absent from ``AiLayerRegisterIn``: the text
    (it is read from the ``MediaFile``, never from the body) and the kind and tier (both are facts
    about a transcript this server's own provider chain produced, not choices — a body that could
    claim TIER_1 for cloud output would make the tier column worthless to the reviewer it exists
    for).

    THE RECORDING IS REACHED THROUGH ``load_transcript_items``, WHICH IS NOT AN ACCIDENT OF REUSE. A
    media id on a stage entry is whatever a client wrote there, and ``GET /api/media`` hands every
    signed-in account the id of every file in the repository — so a direct ``find_unique`` on the id
    in this body would hand back a stranger's recording, which is the leak that function's docstring
    was written about. Going through it applies both halves of the existing gate at once: the clip
    must be attached to an audio field of THIS workshop, and it must be one this caller is entitled
    to. No new gate is invented here.

    PROVENANCE THAT NOBODY RECORDED IS RECORDED AS UNRECORDED, in that word, once, here.
    ``media_queue._transcript_write`` stores the transcript, its summary, its status and its error —
    and not which of the four providers in ``services/ai.py`` produced it. That is unknowable after
    the fact, so it is stated rather than guessed; the body accepts a provider and a model id for the
    one caller who genuinely knows (an operator backfilling a period when a single provider was
    configured), and passing neither is honest rather than lazy.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)

    entries = await entry_rows(workshop_id)
    items = await load_transcript_items(entries, viewer=current_user)
    item = next((i for i in items if i.media_id == payload.sourceMediaId), None)
    if item is None:
        # ONE ANSWER FOR TWO CAUSES, exactly as `load_workshop_or_404` gives one answer for "no such
        # workshop" and "not yours": the clip may not be attached to this workshop, or it may belong
        # to somebody this caller may not read from. Distinguishing them here would confirm that a
        # media id exists to an account that cannot see it.
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That recording is not one of this workshop's. Attach it to an audio field in a "
                "stage first, and check it was uploaded by somebody whose media you can read."
            ),
        )
    if not item.has_text:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"This recording has no transcript yet — its status is "
                f"{item.status or 'not recorded'}. There is nothing to register until the "
                f"transcription finishes; the transcripts screen shows where it is up to."
            ),
        )

    # The media row for its `transcriptSummary`, which `TranscriptItem` does not carry — it exists to
    # be printed, and the annexure prints one text. Read by id AFTER `load_transcript_items` has
    # already admitted this caller to this clip, so it is a second READ of an entitled row and not a
    # second opinion about entitlement; nothing below decides access from it.
    media = await db.mediafile.find_unique(where={"id": item.media_id})
    try:
        raw_text, cleaned_text = ai_layers.transcript_rungs(
            transcript_text=item.text,
            transcript_summary=getattr(media, "transcriptSummary", None),
        )
    except ai_layers.LayerRuleViolation as exc:
        # Unreachable while the `has_text` check above stands, and caught anyway: the two conditions
        # are written in different modules and a later change to either would otherwise turn "there
        # is nothing here yet" into a 500 that reads as "the server is broken".
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    # Stated once, in one place, so that no future caller can arrive at UNRECORDED by forgetting an
    # argument. `ai_layers.layer_create_plan` refuses a blank provider or model id outright.
    provider = (payload.provider or "").strip() or ai_layers.UNRECORDED
    model_id = (payload.modelId or "").strip() or ai_layers.UNRECORDED
    source = ai_layers.LayerSource.media(item.media_id)

    existing = await ai_layers.layers_from_media(workshop_id, item.media_id)
    duplicate = ai_layers.duplicate_of(
        existing,
        source=source,
        kind=ai_layers.LayerKind.RAW_TRANSCRIPT,
        tier=ai_layers.AiTier.TIER_3,
        provider=provider,
        model_id=model_id,
    )
    if duplicate is not None:
        # NOT A UNIQUE INDEX, and the difference matters: two layers over the same recording with
        # different tiers or different models are exactly what the plan wants kept (a phone-produced
        # and a cloud-produced transcript of one clip must both exist and be tellable apart). What is
        # refused is the identical registration twice, which would put the same text into one
        # annexure under two headings. It catches a REPEAT and not a RACE — the read above and the
        # write below are separate round trips, so two overlapping requests both find nothing — and
        # `ai_layers.duplicate_of` records why that gap is left open and what stands in its place.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"This recording is already registered as a Tier 3 raw transcript by the same model "
                f"(layer {duplicate.id}). Delete that layer if it is wrong — a second identical "
                f"registration would put the same text in the annexure twice."
            ),
        )

    produced_at = _parse_datetime(payload.producedAt)
    try:
        raw_plan = ai_layers.layer_create_plan(
            workshop_id=workshop_id,
            kind=ai_layers.LayerKind.RAW_TRANSCRIPT,
            tier=ai_layers.AiTier.TIER_3,
            source=source,
            provider=provider,
            model_id=model_id,
            model_version=payload.modelVersion,
            language=payload.language,
            produced_at=produced_at,
            text=raw_text,
            created_by_id=current_user.id,
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    # THE RAW RUNG FIRST, AND NOT ONLY BECAUSE THE SECOND NEEDS ITS ID. If the second write fails,
    # what is left is the artisan's own words with their provenance — the half worth keeping. The
    # other order would leave a rewritten transcript in the table with the evidence rung missing.
    raw_row = await ai_layers.apply_plan(raw_plan)
    rows = [raw_row]

    if cleaned_text is not None:
        # NOT ONE FIELD OF THE BODY'S PROVENANCE IS CARRIED ONTO THIS RUNG, and each omission is a
        # separate refusal to state something nobody recorded.
        #
        # THE PROVIDER AND THE MODEL, because whatever rewrote this text is not what the body is
        # describing. On a REFINED_TRANSLATED deployment it is `ai.refine_transcript_text` posting to
        # OpenAI chat while the text underneath may have come from Deepgram or ElevenLabs — and it
        # may not be a model at all: `POST /media/{id}/transcript` overwrites `transcriptText` and
        # leaves `transcriptSummary` alone (api/routes/media.py:641), so a transcript a PERSON
        # corrected arrives here in exactly the same shape. The row records no author either way.
        # All the evidence supports is "this is not the provider's own words", so the provenance says
        # UNRECORDED and the annexure can print that instead of naming a model that may never have
        # run.
        #
        # THE LANGUAGE, and this is the sharpest case in the route: under REFINED_TRANSLATED the
        # rewrite is IN ENGLISH, so carrying the raw rung's language onto it would state that an
        # English paraphrase is Odia.
        #
        # AND THE TIME. `producedAt` means when the model produced THIS content; the body's value is
        # a statement about the transcription run, and attaching it here would date a second,
        # different run — one that may have happened seconds later in the same job, or days later
        # when a person opened the transcript and fixed it — from the timestamp of the first. That is
        # a fabricated fact of the same kind as the language, and left null it is honestly unknown.
        cleaned_plan = ai_layers.layer_create_plan(
            workshop_id=workshop_id,
            kind=ai_layers.LayerKind.CLEANED_TRANSCRIPT,
            tier=ai_layers.AiTier.TIER_3,
            source=ai_layers.LayerSource.layer(raw_row.id, ai_layers.LayerKind.RAW_TRANSCRIPT),
            provider=ai_layers.UNRECORDED,
            model_id=ai_layers.UNRECORDED,
            text=cleaned_text,
            created_by_id=current_user.id,
        )
        rows.append(await ai_layers.apply_plan(cleaned_plan))

    # No `text_withheld` here, and it is the one payload in this family that needs none: this caller
    # reached the recording through `load_transcript_items`, which applies the media gate itself, so
    # they are entitled to the text by the same act that produced the layer.
    return {
        "items": [ai_layers.layer_payload(row) for row in rows],
        "total": len(rows),
    }


# --------------------------------------------------------------------------------------
# The AI verbs: proofread, expand, translate, caption, subtitle
#
# Steps 7 and 8 of docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §5 — the first routes in this file that
# DO call a provider, and the reason the block above says in capitals that its five do not.
#
# **EVERY ONE OF THEM PASSES THROUGH THE SAME FOUR GATES, IN THE SAME ORDER, AND THE ORDER IS
# LOAD-BEARING.** It is `_transcribe_one_dictation`'s order, extended by one, and each position is
# chosen against a specific way a designer's afternoon gets wasted:
#
#   1. **The designer set and the workshop** — `_require_designer`, then
#      `load_workshop_or_404(for_edit=True)`. The same pair every write in this family uses; no new
#      permission concept is invented for verbs.
#   2. **Consent.** The artisan's recorded answer to "may this workshop's material be sent to a
#      third-party provider". Checked BEFORE the ceiling, exactly as it is for dictation, because a
#      workshop with no consent is refused whatever the allowance says and telling somebody their
#      daily allowance is spent when the real blocker is a question nobody has asked the artisan
#      sends them off to wait for midnight for nothing.
#   3. **The daily ceiling**, before any body is read and before any provider is touched, so the
#      refusal costs an index lookup rather than a round trip.
#   4. **The provider**, whose 503 spends nothing — a deployment with no key must not silently
#      exhaust every designer's day — and only then the counter.
#
# **WHY CONSENT GATES ALL FIVE, INCLUDING THE ONES THAT SEND ONLY TEXT.** The consent question is
# about material leaving the device for a third party. A transcript is the artisan's words with the
# audio compressed out of them, so posting one to OpenAI is the same export in a smaller shape, and a
# gate that covered the recording but not its transcript would be a gate with a door beside it —
# which is the exact defect `POST /design-workshops/dictate` was retired for. EXPAND is the one where
# this is arguably conservative: the material is the designer's own note, and the designer is not the
# person whose consent this protects. It is gated anyway, for two reasons worth recording rather than
# assuming: a field note routinely quotes the artisan verbatim, and this server cannot tell which
# ones do; and one gate over everything that leaves the device is a rule a designer can hold in their
# head, where "everything except expansion" is a rule somebody will get wrong at the point where
# getting it wrong sends an artisan's words abroad.
#
# THE ROUTES ARE DECLARED ABOVE THE `{layer_id}` FAMILY. They cannot collide — `/ai-layers/proofread`
# is three segments where `/ai-layers/{layer_id}/accept` is four — but this file has already paid for
# a routing collision once (`GET /design-workshops/dictate` full-matching `GET /{workshop_id}` and
# taking the microphone off every browser without SpeechRecognition), so literals go above patterns
# here as a habit rather than as a case-by-case judgement.
# --------------------------------------------------------------------------------------


async def _verb_gate(workshop_id: str, current_user: Any, verb: ai_verbs.Verb) -> Any:
    """Gates 1 to 3, in order, for every verb. Returns the allowance the run will be counted against.

    Returning the allowance rather than re-reading it after the provider call is deliberate: the day
    it names is the day the increment must be written against, and re-deriving it afterwards would
    put a run that started at 23:59:58 IST onto the wrong day's meter — one of the two shapes
    `dictation_cap`'s docstring warns produces "two allowances or none".

    **THE CONSENT REFUSAL IS COMPOSED FOR THIS VERB, WHICH IT WAS NOT.** One gate and one function —
    that part was always right and is untouched — but one hardcoded noun, so a designer pressing
    "describe this photograph" on a workshop nobody had asked the consent question about was told, in
    the field: *"…so this dictation cannot be written down there. Type the words in instead."* There
    is no dictation; a caption goes to Gemini; the material is a photograph; and there is nothing to
    type. `dictation_consent.send_for` supplies the material, the destination and the alternative for
    the verb actually being run, and every one of those was written against what the route below it
    actually sends. See `dictation_consent.SENDS`.
    """
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    refusal = dictation_consent.gate_refusal(
        dictation_consent.consent_of(record), dictation_consent.send_for(verb.value)
    )
    if refusal is not None:
        # A 409 AND NOT A 403, for the reason `dictate_for_workshop` states: a 403 is about the
        # CALLER, and would be wrong in both directions here — this designer is entitled to run the
        # verb, and a colleague of the same rank would be refused identically. What is not in a state
        # to permit the send is the WORKSHOP.
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=refusal)
    allowance = await ai_verb_cap.load_allowance(current_user.id)
    spent = ai_verb_cap.cap_refusal(allowance)
    if spent is not None:
        raise HTTPException(
            # A 429 and not a 403: this clears itself at midnight India time and the sentence says
            # so. A 403 would tell a client this account may never do this, which is what the consent
            # gate means and this does not.
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=spent,
        )
    return allowance


async def _verb_source_layer(workshop_id: str, layer_id: str, current_user: Any) -> Any:
    """One live layer of this workshop whose TEXT this caller may read, or a refusal.

    **THE MEDIA GATE IS APPLIED HERE AND NOT ONLY ON THE WAY OUT, WHICH IS THE WHOLE POINT.** Running
    a verb over a layer means sending its text to a provider and then handing the result back — so a
    caller who may not read a recording could otherwise obtain its contents, lightly rewritten, from
    a route that never showed it to them. That is the same reasoning `accept_ai_layer` uses to refuse
    an acceptance from an account that cannot open the recording, applied one step earlier: there,
    the harm is a signature on a page the signer cannot read; here it is the page itself.
    """
    row = await _layer_or_404(workshop_id, layer_id)
    if await _layer_text_withheld(workshop_id, row, current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "You cannot read the recording this layer was made from, so this server will not "
                "run a model over it for you — the result would put its words in front of you by "
                "another route. Ask whoever uploaded the recording for access to their media."
            ),
        )
    text = str(getattr(row, "text", "") or "").strip()
    if not text:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That layer holds structured data rather than prose, so there is no passage to work "
                "on. Choose a transcript, a summary or another text layer."
            ),
        )
    return row


def _verb_layer_kind(row: Any) -> Any:
    """A stored layer's kind as this build's vocabulary, or a refusal a client can act on.

    **A NEWER SERVER CAN WRITE A KIND THIS BUILD HAS NEVER HEARD OF.** ``DwAiLayerKind`` is a Postgres
    type and a deployment can be a release behind, so ``LayerKind(...)`` over a stored value raises a
    plain ``ValueError`` — which is not a ``LayerRuleViolation`` and would therefore reach the
    designer as "Something went wrong on the server". Named here instead, because the next move is a
    real one: work on a layer this build understands, or update the server.
    """
    raw = getattr(row, "kind", None)
    token = str(getattr(raw, "value", raw) or "")
    try:
        return ai_layers.LayerKind(token)
    except ValueError as exc:
        raise ai_verbs.VerbError(
            f"That layer is of a kind this server does not recognise ({token or 'unnamed'}), so it "
            f"cannot be worked on here. It was probably written by a newer build; choose another "
            f"layer, or ask whoever administers the server to update it."
        ) from exc


#: ``MediaFile.mediaType`` tokens each media verb will accept, and the words its refusal uses.
#:
#: **CHECKED BEFORE ANY BYTES ARE SENT, because the failure otherwise is expensive and unreadable.**
#: A caption run over an audio file uploads a recording to a vision model, which answers with a
#: parse error after the credit is spent; subtitles over a photograph asks a speech engine to find
#: words in a JPEG. Both come back as "FAILED (HTTP 400)", which tells a designer nothing about the
#: file they picked. The token is compared with ``endswith`` for ``workshop_transcripts``' reason:
#: the column's stored form has varied and a prefixed enum spelling must not silently match nothing.
_VERB_MEDIA_TYPES: dict[str, tuple[tuple[str, ...], str]] = {
    "CAPTION": (("IMAGE", "VIDEO"), "a photograph or a video"),
    "SUBTITLES": (("AUDIO", "VIDEO"), "a recording or a video"),
}


async def _verb_source_media(
    workshop_id: str, media_id: str, current_user: Any, *, verb: ai_verbs.Verb
) -> Any:
    """One media file attached to this workshop that this caller may read, or a 404 covering both.

    REACHED THROUGH THE WORKSHOP'S OWN ENTRIES AND THE MEDIA ENTITLEMENT, never by a bare
    `find_unique` on the id in the body. `GET /api/media` hands every signed-in account the id of
    every file in the repository, so an id on a request body is a claim; without both halves of this
    check a designer could spend this deployment's provider credit captioning a stranger's photograph
    and read the description back.

    ONE ANSWER FOR TWO CAUSES — not attached to this workshop, or not readable by this account —
    exactly as `register_ai_layer` gives one answer for the same pair, because distinguishing them
    would confirm to a caller that a media id exists.
    """
    entries = await entry_rows(workshop_id)
    attached = workshop_media_ids(entries)
    row = None
    if media_id in attached:
        readable = await _readable_media_ids({media_id}, current_user)
        if media_id in readable:
            row = await db.mediafile.find_unique(where={"id": media_id})
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That file is not one of this workshop's. Attach it to a field in a stage first, "
                "and check it was uploaded by somebody whose media you can read."
            ),
        )
    wanted, in_words = _VERB_MEDIA_TYPES[verb.value]
    stored = str(getattr(row, "mediaType", "") or "").upper()
    if not any(stored.endswith(token) for token in wanted):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{verb.human.capitalize()} needs {in_words}, and that file is "
                f"{stored.lower() or 'of a kind this server cannot identify'}. Choose another file "
                f"— nothing was sent anywhere and nothing was spent."
            ),
        )
    return row


async def _finish_verb(
    *,
    plan: Any,
    allowance: Any,
    verb: ai_verbs.Verb,
    current_user: Any,
) -> dict[str, Any]:
    """Write the planned layer, count the run, and answer with both. The tail of all five routes.

    **THE LAYER IS WRITTEN BEFORE THE COUNTER MOVES, and if the counter fails the layer stands.**
    `ai_verb_cap.spend` swallows its own failure for the reason it states: the provider has already
    been paid and the words already exist, so handing the designer a 500 would cost them the result
    and the retry, and the retry would spend the credit again.

    THE ANSWER CARRIES THE ALLOWANCE, on the 201 as well as in the refusal, so a client learns the
    ceiling without having to be refused by it once — the argument `dictation_cap.allowance_payload`
    makes about a six-megabyte upload spent to be told a number.
    """
    row = await ai_layers.apply_plan(plan)
    await ai_verb_cap.spend(current_user.id, allowance.day, verb.value)
    refreshed = await ai_verb_cap.load_allowance(current_user.id)
    return {
        # NOT WITHHELD, and it is worth saying why rather than passing a bare False: this caller
        # reached the source through `_verb_source_layer` or `_verb_source_media`, both of which
        # apply the media gate themselves, so they are entitled to the text by the same act that
        # produced it — the identical argument `register_ai_layer` makes about `load_transcript_items`.
        "layer": ai_layers.layer_payload(row, include_text=True),
        # RULE 3, ON THE WIRE, AT THE MOMENT IT MATTERS MOST. The client that just asked for this has
        # words on screen and is one tap from putting them in a report; the flag says, in a field
        # rather than in documentation, that nothing has happened to any document yet.
        "accepted": False,
        "acceptanceRequired": True,
        **ai_verb_cap.allowance_payload(refreshed),
    }


async def _count_refused_run(
    answer: Any,
    *,
    allowance: Any,
    verb: ai_verbs.Verb,
    current_user: Any,
) -> None:
    """Count a run that reached a provider and was then refused. The other half of the meter.

    **THE RULE IS "REACHED A PROVIDER", NOT "PRODUCED A LAYER", AND THE CODE SAID ONE WHILE THE
    DOCUMENTATION SAID THE OTHER.** ``ai_verb_cap``'s module docstring and the migration's own
    comment on ``DwAiVerbDailyUsage.count`` both state it: *"Everything that did reach a provider
    counts, INCLUDING a failure — the credit is spent by the call, and counting only successes leaves
    the ceiling uncapped for exactly the failure mode that produces the most retries."*
    ``_finish_verb`` is reached only on a 201, so until this existed a provider that answered FAILED
    — a 500, a throttle, a model that returned nothing — cost real credit and moved no counter, and a
    designer retrying a broken provider all afternoon was never refused by a ceiling that exists to
    bound exactly that afternoon's bill. ``POST /dictate`` has always counted this way ("Reached a
    provider, so it counts — whatever the provider then said"); the verbs did not, and the divergence
    was invisible because both meters read from the same-shaped table.

    **WHAT IS DELIBERATELY NOT COUNTED, and it is the same list as the dictation cap's.** A body
    refused before the call spent nothing, and neither did the 503 that means no key is configured —
    charging a designer's allowance for the server's own misconfiguration is the one refusal they can
    do nothing whatever about, and on a deployment with no key at all it would silently exhaust every
    designer's day. Both are recognised the same way ``ai_verbs.text_of_answer`` recognises them: an
    answer that never happened is ``None``, and an unavailable one says so in ``status`` and in
    ``available``.

    THE RESIDUAL AMBIGUITY, STATED RATHER THAN HIDDEN: a verb whose INPUT was empty answers ``EMPTY``
    without calling anybody, which is indistinguishable here from a provider that answered with
    nothing. It cannot arise through these five routes — every body requires a non-blank passage
    (``AiExpandIn.text`` has ``min_length=1``, ``_require_exactly_one_source`` refuses whitespace) and
    ``_verb_source_layer`` refuses a layer with no prose — so this counts the honest case today. A
    sixth verb that can answer EMPTY without spending anything must carry that fact in its answer
    rather than leaving it to be inferred from a status shared by two different events.
    """
    if not isinstance(answer, Mapping):
        return
    if str(answer.get("status") or "").upper() == "UNAVAILABLE" or answer.get("available") is False:
        return
    await ai_verb_cap.spend(current_user.id, allowance.day, verb.value)


def _verb_http(exc: Exception) -> HTTPException:
    """One refusal shape for every way a verb can decline, decided once rather than per route.

    * `VerbUnavailable` -> **503, naming the setting.** The same answer `/dictate` gives, for the
      reason recorded there: a 200 with empty output reads as "the model had nothing to say", which
      sends a designer off to rewrite a perfectly good note, and the person who can fix it is an
      administrator rather than them.
    * `LayerRuleViolation` -> **422.** The body describes a layer that cannot exist.
    * `VerbError` -> **422.** The verb could not be run as asked — an empty answer, an unreadable
      cue list, a language name that is not one.
    """
    if isinstance(exc, ai_verbs.VerbUnavailable):
        return HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc))
    return HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc))


@router.post("/{workshop_id}/ai-layers/proofread", status_code=status.HTTP_201_CREATED)
async def proofread_ai_layer(
    workshop_id: str,
    payload: AiProofreadIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Correct spelling, grammar and punctuation in a passage. **The corrected text is a new row.**

    NOTHING IS WRITTEN BACK. Not into the stage field the words came from, not over the layer they
    were read from — `ai_layers` cannot express either write, because a plan may only name a table in
    `WRITABLE_TABLES` and `DwStageEntry` is not in it. The corrected passage is a layer standing
    beside its source, inert until somebody accepts it.

    IT IS A DIFFERENT VERB FROM THE REFINEMENT THIS SERVER ALREADY DOES, and the difference is the
    whole reason it has its own kind: `ai.refine_transcript_text` restructures a conversation into
    speaker turns and, on this deployment's default, translates it into English. Proofreading
    promises the same words, in the same language, in the same order, with the spelling fixed. Two
    promises, two headings, and one may not be printed under the other's.

    THE CRAFT VOCABULARY IS PASSED TO THE MODEL AS A DO-NOT-TOUCH LIST. `ai.craft_keyterms()` exists
    because a general model writes "dabu" as "double", and a proofreader is precisely the process
    most likely to "correct" a craft term into a common word.
    """
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.PROOFREAD)
    answer: Mapping[str, Any] | None = None
    try:
        language = ai_verbs.clean_language(payload.language, what="the passage")
        if payload.sourceLayerId:
            row = await _verb_source_layer(workshop_id, payload.sourceLayerId, current_user)
            text = str(getattr(row, "text", "") or "")
            source = ai_layers.LayerSource.layer(row.id, _verb_layer_kind(row))
            # The parent's own language, when it recorded one, rather than the body's: the verb does
            # not change the language and the source row is better evidence than a client's guess.
            language = str(getattr(row, "language", "") or "").strip() or language
        else:
            text = payload.text or ""
            source = ai_layers.LayerSource.supplied_text(text)
        # BEFORE THE SEND, NOT AFTER IT. A proofread of a proofread, or of an expansion, is refused
        # by the layer law either way; asking now means the words are not sent to a third party and
        # the designer is not charged for a run that could never have been recorded. See
        # `ai_layers.check_placement`, which runs the identical checks `layer_create_plan` runs.
        ai_layers.check_placement(ai_layers.LayerKind.PROOFREAD, source)
        answer = await ai.proofread_text(text, get_settings())
        plan = ai_verbs.proofread(
            workshop_id=workshop_id,
            source=source,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            language=language,
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer,
            allowance=allowance,
            verb=ai_verbs.Verb.PROOFREAD,
            current_user=current_user,
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.PROOFREAD,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/expand", status_code=status.HTTP_201_CREATED)
async def expand_ai_layer(
    workshop_id: str,
    payload: AiExpandIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Write a designer's terse note out into prose. **The riskiest thing this API does.**

    ================================================================================================
    WHAT THIS ROUTE WILL NOT DO, AND WHY THE CLIENT MUST NOT EITHER
    ================================================================================================

    It will not put the prose in a field. It cannot: the only write it can express is a layer. But
    the obvious client for this verb is a button that drops the result into the textarea, and **that
    button must not be built** — plan §3: no AI-produced value may feed a field that is compared
    across surfaces or any derived or computed field, because the same note through a phone and
    through the cloud legitimately differs for ever, and the first cross-surface divergence test to
    fail would be blamed on a bug that is actually the design.

    So the expansion appears BESIDE the note, named as machine-written. It reaches a document only
    through the annexure, only after a person accepts it by name, and only with
    `report_ai_layers.EXPANDED_NOTE` printed above it saying that anything in it which is not in the
    note was supplied by the model. A designer who wants those words in the field types them, and
    they are then that designer's sentences under that designer's name — which is a true statement
    that no paste button could produce.

    IT TAKES A NOTE AND NEVER A LAYER, enforced in three independent places so that removing any one
    of them is a visible act: this body has no `sourceLayerId`; `ai_verbs.expand` constructs its own
    supplied-text source and cannot be handed another; and `ai_layers.TEXT_ROOTED_KINDS` refuses an
    EXPANDED over anything else. Expanding an artisan's transcript would put invented sentences in a
    named person's mouth in a government document, and no acceptance screen makes that safe because
    the person accepting is not the person being quoted.
    """
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.EXPAND)
    answer: Mapping[str, Any] | None = None
    try:
        language = ai_verbs.clean_language(payload.language, what="the note")
        answer = await ai.expand_text(payload.text, get_settings())
        plan = ai_verbs.expand(
            workshop_id=workshop_id,
            note=payload.text,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            language=language,
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.EXPAND, current_user=current_user
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.EXPAND,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/translate", status_code=status.HTTP_201_CREATED)
async def translate_ai_layer(
    workshop_id: str,
    payload: AiTranslateIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Translate a passage. **The original stays exactly where it is; this is a sibling.**

    RULE 1 IN THE PLACE IT IS MOST LIKELY TO BE BROKEN, and the failure it is written against is
    already in this database rather than hypothetical: `AppSetting.transcriptionMode` defaults to
    REFINED_TRANSLATED, under which the media queue writes an English rewrite into
    `MediaFile.transcriptText` — the column an annexure prints as the artisan's words, and the reason
    `ai_layers.transcript_rungs` had to be written to un-mix them. Nothing here updates, supersedes
    or flags the source layer; both rows stay live and both stay printable, so a reader who wants the
    artisan's own words can have them.

    BOTH LANGUAGES ARE RECORDED ON THE ROW. "In English" is not a provenance record for a translation
    — a reader checking it against what the artisan said has to know what they said it in — so
    `sourceLanguage` and `targetLanguage` are separate columns and `ai_layers._check_languages`
    refuses a translation missing either. `multi` is a real source (these interviews code-switch
    mid-sentence) and never a target.

    THE SOURCE LANGUAGE IS TAKEN FROM THE SOURCE LAYER WHEN IT HAS ONE, and from the body otherwise,
    and is recorded as UNRECORDED in that word when neither knows — never defaulted to English.
    """
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.TRANSLATE)
    answer: Mapping[str, Any] | None = None
    try:
        target = ai_verbs.clean_language(payload.targetLanguage, what="the target language")
        stated_source = ai_verbs.clean_language(payload.sourceLanguage, what="the source language")
        if payload.sourceLayerId:
            row = await _verb_source_layer(workshop_id, payload.sourceLayerId, current_user)
            text = str(getattr(row, "text", "") or "")
            source = ai_layers.LayerSource.layer(row.id, _verb_layer_kind(row))
            # The stored language wins over the body's: the row is a record of what the run detected
            # and the body is a client's assertion about somebody else's row.
            source_language = (
                str(getattr(row, "language", "") or "").strip() or stated_source
            )
        else:
            text = payload.text or ""
            source = ai_layers.LayerSource.supplied_text(text)
            source_language = stated_source
        # BEFORE THE SEND. A translation of a translation (no pivot) and a translation of an
        # EXPANDED (nothing derives from an invention) are both refused by the layer law, and asking
        # now is the difference between refusing the ROW and refusing the SEND: the second is the
        # half of "nothing may be derived from an expansion" that is about the words leaving the
        # building rather than about the table they would have landed in.
        ai_layers.check_placement(ai_layers.LayerKind.TRANSLATION, source)
        answer = await ai.translate_text(
            text,
            target_language=target or "",
            source_language=source_language,
            settings=get_settings(),
        )
        plan = ai_verbs.translate(
            workshop_id=workshop_id,
            source=source,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            # UNRECORDED in that word rather than a null, because a null on this column would read as
            # "no source language" where the truth is "nobody detected one" — the honest-unknown
            # discipline `ai_layers.UNRECORDED` exists for.
            source_language=source_language or ai_layers.UNRECORDED,
            target_language=target or "",
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.TRANSLATE, current_user=current_user
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.TRANSLATE,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/caption", status_code=status.HTTP_201_CREATED)
async def caption_ai_layer(
    workshop_id: str,
    payload: AiMediaVerbIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Describe a photograph or a video in one sentence, for the annexure and for a screen reader.

    THE ACCESSIBILITY HALF IS NOT A SECOND FEATURE. A media annexure of forty photographs with no
    descriptions is unusable to a reader with a screen reader and nearly as unusable to anybody
    reading the .docx a year later without the designer beside them. The same sentence serves both,
    which is why this verb produces prose rather than a tag list.

    THE PROMPT REFUSES TO NAME THINGS IT CANNOT SEE — the craft, the technique, the region, the
    community, a person's identity — and the refusal is in `ai._CAPTION_PROMPT` rather than here
    because it is a property of the request rather than of the route. A caption printed in a
    government record beside somebody's name must describe the photograph and not guess at who is in
    it, and a model asked to describe a craft photograph will otherwise name a technique from the
    look of a fabric.

    THE MODEL'S SELF-REPORTED CONFIDENCE TRAVELS IN THE PAYLOAD, labelled uncalibrated, exactly as
    `measurement_provenance` requires for the grid reader: nothing in this repository has ever
    calibrated a model's confidence against anything, and a client that showed "80%" beside a caption
    would be presenting a self-assessment as a measurement.
    """
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.CAPTION)
    media = await _verb_source_media(
        workshop_id, payload.sourceMediaId, current_user, verb=ai_verbs.Verb.CAPTION
    )
    answer: Mapping[str, Any] | None = None
    try:
        language = ai_verbs.clean_language(payload.language, what="the caption")
        # `multi` IS DROPPED HERE AND NOT REFUSED, and the same token is dropped from what is sent
        # and from what is recorded — which is the point. "Several languages, interleaved" is
        # something a RECORDING can be and not something one sentence can be written in, so the model
        # is not asked for it and the row must not claim it. Dropping rather than refusing, because a
        # caption in the model's own language is a perfectly good answer.
        if language and language.lower() == "multi":
            language = None
        content = await asyncio.to_thread(get_object_bytes, media.objectKey)
        answer = await ai.caption_image_bytes(
            content,
            str(getattr(media, "mimeType", "") or "image/jpeg"),
            get_settings(),
            # ASKED FOR, THEN RECORDED — never recorded without being asked for. The layer's
            # `language` column is provenance under rule 2, and until this argument existed the
            # prompt was English-only while the row carried whatever the client had typed: an English
            # sentence stored as Odia, in the one annexure whose purpose is telling a reader what
            # produced a passage and in what.
            language,
        )
        plan = ai_verbs.caption(
            workshop_id=workshop_id,
            media_id=media.id,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            language=language,
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.CAPTION, current_user=current_user
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.CAPTION,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/subtitles", status_code=status.HTTP_201_CREATED)
async def subtitle_ai_layer(
    workshop_id: str,
    payload: AiMediaVerbIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Produce timed captions for a recording or a video, stored as cues and rendered as SRT or WebVTT.

    **THIS IS THE ONE VERB THAT COSTS A SECOND UPLOAD OF AUDIO THAT HAS ALREADY BEEN TRANSCRIBED, and
    the reason is a defect rather than a design.** Established from the code rather than assumed, and
    written up with the function names in `services/subtitles`: ElevenLabs Scribe v2 is ALREADY asked
    for word timings and Deepgram Nova-3 ALREADY returns sentence and word timings — and both are
    discarded one line after being parsed, with the payload stored as None because "word-level
    payload is huge". OpenAI's rung is asked for `response_format=json`, which carries no timings at
    all, so a deployment with only an OpenAI key cannot subtitle and is told so by name.

    Two consequences, stated where somebody sizing a bill will find them. Nothing already in the
    archive can be subtitled without sending the audio again — every timing this system has ever
    received is gone. And removing that cost means teaching the existing transcription path to keep
    its word arrays, which changes what is written to `MediaFile` for every clip in the fleet: a
    migration and a measurement in another lane's column, not a patch.

    THE CUES ARE THE CONTENT AND THE PROSE IS A RENDERING OF THEM. `payload` holds
    `{start, end, text, speaker}` per cue under a versioned schema; `text` holds a plain reading, for
    the annexure (which prints text), a search (which matches strings) and the acceptance screen
    (which previews). A subtitle file is always built from the cues.
    """
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.SUBTITLES)
    media = await _verb_source_media(
        workshop_id, payload.sourceMediaId, current_user, verb=ai_verbs.Verb.SUBTITLES
    )
    answer: Mapping[str, Any] | None = None
    try:
        content = await asyncio.to_thread(get_object_bytes, media.objectKey)
        answer = await ai.transcribe_timed_bytes(
            content,
            str(getattr(media, "originalFilename", "") or "recording.webm"),
            str(getattr(media, "mimeType", "") or "audio/webm"),
            get_settings(),
        )
        plan = ai_verbs.subtitle(
            workshop_id=workshop_id,
            media_id=media.id,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.SUBTITLES, current_user=current_user
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.SUBTITLES,
        current_user=current_user,
    )


@router.get("/{workshop_id}/ai-layers/{layer_id}/subtitles.{fmt}")
async def download_subtitles(
    workshop_id: str,
    layer_id: str,
    fmt: str,
    speakers: bool = Query(
        False,
        description=(
            "Put the speaker label in front of each line — `Speaker 1: With gum and clay`. **Off by "
            "default**, because a label costs characters out of a hard two-line caption budget and "
            "because every client written before this flag existed expects a file without them. Ask "
            "for it when the recording is a group sitting: this archive's run to five artisans plus "
            "an interviewer, and a subtitle with no labels attributes every voice to whoever happens "
            "to be on camera. **The labels are a MODEL'S GUESS.** Nobody told the engine how many "
            "people were in the room or who they were — it decided both from the audio, and it can "
            "merge two quiet voices or split one person who moved away from the microphone. The "
            ".vtt carries that caution inside the file as a WebVTT `NOTE`; SubRip has no comment "
            "syntax and cannot, so a .srt carries the labels alone. A layer whose cues hold no "
            "labels at all refuses this flag rather than serving the identical unlabelled file."
        ),
    ),
    current_user: Any = Depends(get_current_user),
) -> Response:
    """One SUBTITLES layer as a `.srt` or `.vtt` file a player can open.

    TWO FORMATS BECAUSE TWO PLAYERS, and neither is a superset of the other: WebVTT is what a
    browser's `<track>` element takes, and SubRip is what a phone gallery, VLC and every desktop
    player open and what a designer attaches to an email.

    **`?speakers=` EXISTS BECAUSE EVERY FILE THIS ROUTE HAS EVER SERVED WAS ANONYMISED.** Both
    renderers were wired with `speakers=False` and there was no way to ask for anything else —
    `subtitles.to_srt_with_speakers` was written, exported and tested, and had no caller. So Scribe
    would diarize a sitting of five artisans and an interviewer, every cue would carry its speaker,
    the layer's own `text` would print `**Speaker 1:**` into the report annexure — and the .srt the
    designer downloaded to play against the video attributed every line to nobody. One layer, two
    renderings, opposite answers to whether the speakers are known.

    **AN UNACCEPTED LAYER IS STILL DOWNLOADABLE HERE, AND THAT IS DELIBERATE.** Rule 3 says nothing
    unaccepted may be PRINTED IN A REPORT, and it is not relaxed: the annexure refuses an unaccepted
    layer twice over. This is not a report — it is the designer looking at what the model produced,
    in the only form in which subtitles can actually be judged, which is played against the video.
    Requiring acceptance first would mean accepting subtitles nobody has watched, which is the
    opposite of what acceptance is for.

    THE MEDIA GATE APPLIES, because a cue list is a transcript with times on it.
    """
    await load_workshop_or_404(workshop_id, current_user)
    row = await _layer_or_404(workshop_id, layer_id)
    if await _layer_text_withheld(workshop_id, row, current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "You cannot read the recording these subtitles were made from, and a cue list is "
                "its transcript with times on it. Ask whoever uploaded the recording for access to "
                "their media."
            ),
        )
    try:
        body, mime, extension = ai_verbs.render_subtitles(row, fmt=fmt, speakers=speakers)
    except ai_verbs.VerbError as exc:
        raise _verb_http(exc) from exc
    return Response(
        content=body.encode("utf-8"),
        media_type=mime,
        headers={
            "Content-Disposition": (
                # THE FILENAME SAYS WHICH OF THE TWO FILES THIS IS. A designer who downloads both
                # ends up with two files whose names differ in nothing, in a downloads folder, and
                # the one that attributes an artisan's words to a named speaker is not the one to
                # confuse with the other when attaching it to an email.
                f'attachment; filename="subtitles-{quote(layer_id)}'
                f'{".speakers" if speakers else ""}.{extension}"'
            )
        },
    )


async def _layer_text_withheld(workshop_id: str, row: Any, viewer: Any) -> bool:
    """Whether THIS caller may read the content of the recording this one layer stands on.

    The same gate the list applies, asked about a single row, and it is asked on accept and withdraw
    because those responses carry the layer back — with its preview, which is 280 characters of a
    transcript and therefore the transcript.

    IT IS USED TWO DIFFERENT WAYS BY ITS TWO CALLERS, and that is deliberate. On accept it is a
    REFUSAL: an acceptance is somebody saying they read this text, and the report prints their name
    beside it, so an account that may not open the recording may not sign for it. On withdraw it only
    decides what comes BACK: taking a name off says nothing about the text and must stay reachable
    even after the recording's permissions have changed under it.

    Fails closed: a chain that cannot be walked to a recording withholds, exactly as in the list.
    """
    root = ai_layers.chain_roots(
        await ai_layers.workshop_layers(workshop_id, include_deleted=True)
    ).get(str(getattr(row, "id", "")))
    if root is None:
        return True
    # The media query is asked only where there is a recording to ask about: a supplied-text root has
    # none and an unresolved one is withheld without asking anybody. `ChainRoot.withheld_from` is
    # still what decides, so this and the list route cannot answer differently.
    wanted = (
        {root.media_id}
        if root.kind is ai_layers.RootKind.MEDIA and root.media_id
        else set()
    )
    return root.withheld_from(await _readable_media_ids(wanted, viewer))


@router.post("/{workshop_id}/ai-layers/{layer_id}/accept")
async def accept_ai_layer(
    workshop_id: str, layer_id: str, payload: AiLayerDecisionIn | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """A person puts their name to this layer, and it becomes printable.

    RULE 3 IS THE ONE THIS ROUTE EXISTS FOR: a layer is inert until a person accepts it, and the
    acceptance records who and when. Until this is called the row is a suggestion sitting in a table
    that no report reads — ``ai_layers.accepted_layers`` is the only definition of "accepted" in the
    codebase and step 3's renderer is required to use it rather than writing its own filter.

    TWO WRITES, NOT ONE. The layer gains ``acceptedAt``/``acceptedById`` (the current state, which
    the report builder reads without walking a log per layer per render) and a ``DwAiLayerDecision``
    row is appended (the history, which is what survives a withdrawal). Both come back, because the
    audit being visible in the response is what stops a client rendering acceptance as a checkbox.

    AND IT IS REFUSED WHEN THIS ACCOUNT MAY NOT READ THE RECORDING THE LAYER STANDS ON. Being able
    to edit a workshop and being able to read the CONTENT of a recording inside it are two different
    permissions in this repository — a ``DesignWorkshopViewer`` grant carries the first and says
    nothing about the second — so without this check a colleague could put their name to a
    transcript that ``GET /design-workshops/{id}/transcripts`` and the list beside this route both
    refuse to show them. An acceptance is somebody stating that they read this text and it is right;
    a signature on a page the signer is not allowed to open is worth less than no signature, because
    the report then names them as the person who checked it. WITHDRAWING is deliberately NOT gated
    the same way — see that route — since taking a name off is a safety valve and must never become
    unreachable.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    row = await _layer_or_404(workshop_id, layer_id)
    if await _layer_text_withheld(workshop_id, row, current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "You cannot read the recording this layer was made from, so you cannot accept it: "
                "an acceptance says a person read this text and stands behind it, and the report "
                "prints their name beside it. Ask whoever uploaded the recording for access to "
                "their media, or ask them to accept it themselves."
            ),
        )
    try:
        plans = ai_layers.acceptance_plan(
            row,
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note if payload else None,
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    # Not withheld by definition: the refusal above is what got us here, so this caller may read it.
    withheld = False
    updated = await ai_layers.apply_decision(plans)
    return {
        "layer": ai_layers.layer_payload(updated, text_withheld=withheld),
        "decisions": [
            ai_layers.decision_payload(d) for d in await ai_layers.layer_decisions(layer_id)
        ],
    }


@router.post("/{workshop_id}/ai-layers/{layer_id}/unaccept")
async def unaccept_ai_layer(
    workshop_id: str, layer_id: str, payload: AiLayerDecisionIn | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """A person takes their name off this layer. The layer itself is untouched and stays readable.

    THE ACCEPTANCE IS CLEARED AND THE HISTORY IS NOT, deliberately. A report generated while this
    layer was accepted named it as accepted, and that document does not change because somebody
    changed their mind on the 11th; the ``DwAiLayerDecision`` rows are what still explain it. This is
    the same reasoning as ``REFERENCE_HYDRATION``'s — a document already handed to a ministry officer
    must stay explicable — applied to a decision instead of to a name.

    Withdrawing is NOT deleting: the layer returns to being a suggestion and can be accepted again,
    by the same person or another. Deleting is the separate act of declining it altogether.

    NO MEDIA GATE HERE, UNLIKE ACCEPT, AND THE ASYMMETRY IS THE POINT. Accepting is refused for an
    account that may not read the recording, because an acceptance is a statement that somebody read
    the text. Taking a name back off states nothing about the text and must stay reachable whatever
    has happened to the recording's permissions since — a data-access grant withdrawn between the
    acceptance and the doubt would otherwise trap an accepted layer in a report with nobody able to
    unaccept it. The response still withholds the TEXT if this caller may not read it; what is not
    gated is the act.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    row = await _layer_or_404(workshop_id, layer_id)
    try:
        plans = ai_layers.withdrawal_plan(
            row, actor_id=current_user.id, note=payload.note if payload else None
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    withheld = await _layer_text_withheld(workshop_id, row, current_user)
    updated = await ai_layers.apply_decision(plans)
    return {
        "layer": ai_layers.layer_payload(updated, text_withheld=withheld),
        "decisions": [
            ai_layers.decision_payload(d) for d in await ai_layers.layer_decisions(layer_id)
        ],
    }


@router.delete("/{workshop_id}/ai-layers/{layer_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_ai_layer(
    workshop_id: str, layer_id: str, current_user: Any = Depends(get_current_user)
) -> None:
    """Decline a layer. Soft, and it does not touch what the layer was made from.

    RULE 5, AND THE PROOF IS IN WHAT THE CALL CANNOT DO rather than in this sentence:
    ``ai_layers.deletion_plan`` returns exactly one plan, naming exactly one row id, setting exactly
    ``deletedAt`` and ``deletedById``. It has no branch that reads ``sourceMediaId`` or
    ``sourceLayerId`` and no second plan it could return, and ``tests/test_ai_layers.py`` asserts all
    of that — so a later change that "also tidies up the recording" fails a test instead of deleting
    the evidence a transcript was made from.

    SOFT, matching ``DesignWorkshop`` and ``DwStageEntry`` for the reason both state — a designer's
    fieldwork is not something a mis-tap should end — and here it buys something extra: the row is
    the only record that a model proposed something and a person said no.

    A 409 WHEN LAYERS DERIVE FROM THIS ONE. Deleting a raw transcript out from under a cleaned
    transcript would leave the cleaned one describing something no screen will show, which is the
    opposite of the traceability this table exists for.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    row = await _layer_or_404(workshop_id, layer_id)
    try:
        plan = ai_layers.deletion_plan(
            row,
            actor_id=current_user.id,
            at=datetime.now(UTC),
            derived=await ai_layers.derived_from(layer_id),
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    await ai_layers.apply_plan(plan)


@router.get("/{workshop_id}/market-analysis")
async def workshop_market_analysis(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Stage 9's Advanced tier: what the stage-8 survey actually says about the stage-9 claims.

    THE SERVER COPY OF A CALCULATION THAT ALSO RUNS ON THE CLIENT, and that is deliberate rather
    than duplicated by accident. `app/services/market_analysis.py` is pure arithmetic over rows the
    designer already entered, so the browser and the handset run the same analysis with no network
    at all — which is the only way it is available in the village where the survey was taken. This
    endpoint exists for the two cases the client cannot serve: a report render, which must not
    depend on whichever device happens to be looking; and a designer opening the workshop on a
    machine that has not synced stage 8.

    It is READ-ONLY and writes nothing to stage 9. The designer's declared bands, SWOT and demand
    level stay exactly as typed — this returns findings BESIDE them. They were in the room and the
    arithmetic was not.
    """
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)

    def rows_of(entity_key: str) -> list[dict[str, Any]]:
        return [dict(row.data or {}) for row in entries if row.entityKey == entity_key]

    findings = analyse(
        responses=rows_of("surveyResponse"),
        competitors=rows_of("competitorProduct"),
        bands=rows_of("priceBand"),
        swot=rows_of("swotPoint"),
    )
    return market_findings_payload(findings)


@router.get("/{workshop_id}/cost-integrity")
async def workshop_cost_integrity(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Stage 17's cost sheets checked against the line items underneath them.

    A cost sheet can contradict ITSELF: the material lines add up to ₹1,650.00 and the header
    declares ₹1,560.00, and the header is what the report prints into a document submitted to a
    government office. The registry cannot catch it — `derive_value` reads one row and its own
    fields, never a sibling collection — so `app/services/cost_integrity.py` does the roll-up.

    It is READ-ONLY and writes nothing back to stage 17. The designer's typed subtotal stays
    exactly as typed and this returns a FINDING beside it: a subtotal may legitimately differ from
    its lines, and silently replacing a considered figure with a computed one would be a worse bug
    than the one being fixed.

    The calculation is PURE so that it can run in the browser and on the handset — but as of
    2026-08-08 it does not, and that matters more than the intent. The market analysis beside it has
    a proven-equal TypeScript port and a stage-9 panel; this has neither, and a Kotlin
    `DwCostIntegrity` exists that nothing calls. **So no designer sees a cost-integrity finding on
    any surface today**: this endpoint is the whole of the feature, and nothing consumes it.

    That sentence is here rather than in a tracker because the previous version of this docstring
    asserted the ports existed, and a claim like that is how a gap stops being visible: a reader
    checking whether the handset warns about a self-contradicting cost sheet would have read "yes"
    and stopped. Delete this paragraph when the ports and a panel land, not before.

    It is READ-ONLY and serves the two cases a client could not serve even once ported: a report
    render, which must not depend on whichever device is looking, and a device that has not synced
    the stage.
    """
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)

    def rows_of(entity_key: str) -> list[dict[str, Any]]:
        # `_entryId` TRAVELS WITH THE ROW HERE, unlike the market analysis above, and without it
        # this endpoint returns nothing useful. It is the row's database id rather than part of the
        # stored `data`, and it is the only key a `costSheetRef` can be matched against — omit it
        # and every line becomes an orphan of a sheet that is sitting right there. Same injection,
        # under the same `_`-prefixed name, as `_stages_payload` and `_workshop_data`.
        return [dict(row.data or {}, _entryId=row.id)
                for row in entries if row.entityKey == entity_key]

    # A cost sheet is labelled by `productRef`, which points at a stage-13 final product. The
    # service is pure and cannot resolve a reference, so the names are looked up here and passed
    # in — a finding headed by a raw cuid is one a designer cannot trace back to a row.
    labels = {
        row.id: str((row.data or {}).get("name") or (row.data or {}).get("productCode") or "")
        for row in entries if row.entityKey == "finalProduct"
    }

    findings = analyse_cost_integrity(
        sheets=rows_of("costSheet"),
        material_lines=rows_of("costMaterialLine"),
        labour_lines=rows_of("costLabourLine"),
        labels={k: v for k, v in labels.items() if v},
    )
    return cost_findings_payload(findings)


# --------------------------------------------------------------------------------------
# Reports
# --------------------------------------------------------------------------------------


@router.get("/{workshop_id}/report/preview")
async def preview_report(
    workshop_id: str, templateId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The report as structured blocks, for the web preview to render as HTML.

    The preview reads the SAME :class:`ReportDocument` the .docx and .pdf are rendered from, so
    what a designer approves on screen is what the file contains. A preview built from its own
    traversal of the data would be a fourth renderer, and the first to drift.
    """
    record = await load_workshop_or_404(workshop_id, current_user)
    # THE TEMPLATE COMES BACK FROM THE LOAD, THROUGH THE SAME PRECEDENCE EVERY OTHER STAGE-20
    # SETTING USES. It used to be resolved here as `templateId or record.templateId`, which skipped
    # the stage-20 answer entirely — so the required, Basic-tier "Report template" picker a designer
    # had to fill in to satisfy the completeness gate changed nothing about the document it names.
    # `_report_inputs` now resolves it (see `resolve_template_id`) because it has to know whether
    # the document draws a map before deciding what to load.
    data, resolver, load_warnings, template_id = await _report_inputs(
        workshop_id, record, viewer=current_user, requested_template_id=templateId
    )
    document, warnings = await asyncio.to_thread(
        _build_only, data, template_id, resolver, record,
    )
    warnings = list(warnings) + load_warnings
    return {
        "meta": {
            "title": document.meta.title,
            "subtitle": document.meta.subtitle,
            "templateId": document.meta.template_id,
            "templateName": document.meta.template_name,
            "pageSize": document.meta.page_size.value,
        },
        "blocks": [_block_payload(b) for b in document.blocks],
        "warnings": list(warnings),
    }


@router.post("/{workshop_id}/report")
async def generate_report(
    workshop_id: str, payload: ReportGenerateIn,
    current_user: Any = Depends(get_current_user),
) -> Response:
    """Render and return one report file.

    Returns the bytes directly rather than a link. A designer generating a report is about to
    attach it to an email; an intermediate storage round trip would add a failure mode and a
    retention question for a file that is reproducible from the record at any time.

    Warnings — a missing required field, a photo that could not be embedded — travel in the
    ``X-Report-Warnings`` header rather than in the file, because they describe the act of
    generating rather than the document, and an officer opening the .docx next month should not
    find a note about what was missing on the day.
    """
    record = await load_workshop_or_404(workshop_id, current_user)
    fmt = payload.formats[0]
    # The template the file is actually built from is resolved ONCE, inside `_report_inputs`, and
    # used for the loads, the render AND the export row — a recorded export that names a different
    # template from the one in the file is worse than no record, because the checksum makes it
    # look authoritative.
    data, resolver, load_warnings, template_id = await _report_inputs(
        workshop_id,
        record,
        viewer=current_user,
        requested_template_id=payload.templateId,
        transcripts=payload.includeTranscripts,
        ai_layers=payload.includeAiLayers,
    )
    blob, warnings, page_count = await asyncio.to_thread(
        render_report, data, template_id, resolver, record, fmt, payload,
    )
    warnings = list(warnings) + load_warnings

    file_name = _report_file_name(record, fmt)
    # Built BEFORE the export row is written, so a name this response cannot carry fails the
    # request without first recording a phantom export of a file nobody received.
    headers = {
        "content-disposition": _content_disposition(file_name),
        # `_warnings_header` and NOT `"; ".join(...)[:900]`, which dropped the tail of the list in
        # silence and cut the last surviving sentence mid-word. The load warnings — "your attached
        # questionnaire had no answers and is not in this file" — are appended last and were the
        # first casualties on the default template. See that function.
        "x-report-warnings": _warnings_header(warnings),
        # The TRUE total, never what fitted above.
        "x-report-warning-count": str(len(warnings)),
    }

    if payload.record:
        await db.dwreportexport.create(data={
            "designWorkshopId": workshop_id,
            "format": fmt,
            "templateId": template_id,
            "fileName": file_name,
            "fileSizeBytes": len(blob),
            "pageCount": page_count,
            "checksumSha256": hashlib.sha256(blob).hexdigest(),
            "generatedOnDevice": False,
            "schemaVersion": registry_version(),
            "warnings": "\n".join(warnings) if warnings else None,
            "generatedById": current_user.id,
        })

    return Response(content=blob, media_type=_MIME[fmt], headers=headers)


@router.get("/{workshop_id}/exports")
async def list_exports(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> list[dict[str, Any]]:
    await load_workshop_or_404(workshop_id, current_user)
    rows = await db.dwreportexport.find_many(
        where={"designWorkshopId": workshop_id}, order={"generatedAt": "desc"}, take=100
    )
    return [
        {
            "id": r.id, "format": r.format, "templateId": r.templateId,
            "fileName": r.fileName, "fileSizeBytes": r.fileSizeBytes,
            "pageCount": r.pageCount, "checksumSha256": r.checksumSha256,
            "generatedOnDevice": r.generatedOnDevice,
            "generatedAt": r.generatedAt.isoformat() if r.generatedAt else None,
            "warnings": r.warnings,
        }
        for r in rows
    ]


@router.post("/{workshop_id}/exports", status_code=status.HTTP_201_CREATED)
async def record_device_export(
    workshop_id: str, payload: ExportRecordIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Record a report the phone generated offline.

    The bytes are not uploaded — only the fact, the checksum and the size. A designer on a
    metered field connection should not be charged for a thirty-megabyte report merely to prove
    one was made; the checksum is enough to match the file later.
    """
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    fmt = payload.format.upper()
    if fmt not in _MIME:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                            detail="Unknown export format")
    row = await db.dwreportexport.create(data={
        "designWorkshopId": workshop_id,
        "format": fmt,
        "templateId": payload.templateId,
        "fileName": payload.fileName,
        "fileSizeBytes": payload.fileSizeBytes,
        "pageCount": payload.pageCount,
        "checksumSha256": payload.checksumSha256,
        "generatedOnDevice": True,
        "schemaVersion": registry_version(),
        "warnings": payload.warnings,
        "generatedById": current_user.id,
        "generatedAt": _parse_datetime(payload.generatedAt) or datetime.now(UTC),
    })
    return {"id": row.id}


# How much of each list one history request may carry. The export cap matches ``list_exports``'s
# hundred; the entry cap is an order of magnitude above the 270 rows a fully-populated 22-stage
# workshop holds, so in practice it never bites — but a cap that bites silently would let the
# client claim a stage was "unchanged" when the rows that changed it were simply not sent, which
# is a confident wrong answer to the one question this feature exists to answer. Both truncations
# are reported, and the entry list is ordered by ``updatedAt`` descending so that if the cap ever
# does bite it keeps the rows a diff is about and drops the ones nobody has touched in months.
_HISTORY_EXPORT_LIMIT = 100
_HISTORY_ENTRY_LIMIT = 2000


@router.get("/{workshop_id}/report-history")
async def report_history(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Every report ever generated for this workshop, and the timestamps a diff can be built from.

    A report that goes to a ministry is revised three or four times, and nothing in the system could
    answer "did you update the cost sheet before you resubmitted?". Almost everything that question
    needs was already recorded — ``DwReportExport`` has carried the checksum, size, page count,
    template, registry version and timestamp of every file since the feature shipped, INCLUDING the
    ones a phone produced offline. What no client could reach was two things:

    * **Who generated a file.** ``GET /{id}/exports`` returns ten fields and the generator is not
      among them, though the column has always been populated.
    * **When each stage row was last written.** ``GET /{id}`` returns the data with no timestamps at
      all, and ``entry_rows`` filters ``deletedAt: None`` — so a row DELETED between two exports, a
      struck-out cost line being the obvious case, is invisible in every existing payload. A diff
      built on those payloads would report the cost sheet unchanged on exactly the revision that
      changed it.

    **THIS ENDPOINT SERVES FACTS, NOT A DIFF**, and that is a deliberate split rather than an
    omission. The comparison itself — which stages moved between two files, which are provably
    untouched — is arithmetic over data the caller now holds, so it belongs on the device
    (``frontend/lib/reportDiff.ts``), where a designer can flip between generation 1 and generation
    4 with no further request. What could not be done on the device is knowing the exports and the
    timestamps exist at all: the export table records files made on other devices by other people,
    so unlike a stage form this screen genuinely cannot be served from the local draft.

    **WHAT IS DELIBERATELY NOT HERE.** No snapshot of the stage data as it stood at each export is
    kept anywhere, so no field-level diff is possible from this payload and none is implied by it.
    What a client can honestly say is which stages were WRITTEN TO inside a window and which were
    provably not — and the two halves are not equally strong. ``save_stage`` updates every row a
    payload names without comparing it to what is stored, so "written" means SAVED and never
    "differs"; "not written", by contrast, is a proof that both files carried identical data. Any
    client rendering this must keep that asymmetry (``frontend/lib/reportDiff.ts`` does).

    Stage titles and template names are absent for a different reason — every client already caches
    the field registry and ``/templates``, and a second copy of a stage's title travelling on this
    wire is a second thing to drift.

    ``serverTime`` is here because "edited since the newest export" compares a server-written
    ``updatedAt`` against now, and a field laptop whose clock is a day out would otherwise invent or
    hide a day of edits. Read against this, not against the device's own clock.

    **NOTHING HERE IS WRITTEN.** An export row whose size or checksum could be rewritten afterwards
    would not be evidence of anything; the checksum is the whole point of the record.

    Gated by ``load_workshop_or_404`` like every other read of this workshop — 404 rather than 403,
    so the id is not confirmed to somebody entitled to know nothing about it.
    """
    record = await load_workshop_or_404(workshop_id, current_user)

    exports = await db.dwreportexport.find_many(
        where={"designWorkshopId": workshop_id},
        order={"generatedAt": "desc"},
        take=_HISTORY_EXPORT_LIMIT + 1,
        include={"generatedBy": True},
    )
    # Deliberately unfiltered on `deletedAt`: a removed row IS a change between two files, and it
    # is the only kind of change that leaves nothing behind to notice.
    entries = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id},
        order={"updatedAt": "desc"},
        take=_HISTORY_ENTRY_LIMIT + 1,
    )
    entries_truncated = len(entries) > _HISTORY_ENTRY_LIMIT

    return {
        "workshopId": workshop_id,
        # The cover page's craft, cluster, dates and title live on the workshop row rather than in
        # any stage entry, so a diff without this reports "nothing changed" on a revision whose
        # whole point was a corrected cluster name. One timestamp only knows the LAST write — the
        # client says so rather than counting header edits it cannot see.
        "workshopUpdatedAt": _iso(getattr(record, "updatedAt", None)),
        "serverTime": datetime.now(UTC).isoformat(),
        # TODAY'S SCORES, computed from the rows already in hand rather than left to the client to
        # go and fetch. Without this the screen has to call `GET /{id}` purely to reach
        # `completeness` — which returns every field of all 270 stage rows plus the transcript
        # annexure, a payload measured in hundreds of kilobytes, over the metered rural connection
        # this whole application is written for. Here it costs no extra query and a few hundred
        # bytes. Deleted rows are excluded, exactly as `entry_rows` would: a struck-out cost line
        # belongs in the TIMELINE above, because its removal is a change, and nowhere near a score
        # of what the workshop currently holds.
        #
        # It is today's figure and only today's — nothing stored says what a stage scored when a
        # past report was generated. The client attaches it to an export only where the timeline
        # proves the stage has not been written to since; see `reportDiff.currentReflectsBoth`.
        #
        # WITHHELD ENTIRELY once the entry cap has bitten, rather than scored from the rows that
        # happened to fit. A percentage computed over a truncated set is not a slightly-off
        # percentage, it is a wrong one that looks exactly like a right one — and the client draws
        # nothing for a stage it has no score for, which is the correct outcome.
        "completeness": (
            {} if entries_truncated
            else workshop_completeness(
                [r for r in entries if r.deletedAt is None],
                # The same definition every other score on every other endpoint is computed
                # against. A history screen that scored a stage lower than the readiness screen
                # does — because one of them counted the designer's own required fields and the
                # other did not — would make the one question this endpoint exists to answer
                # ("did anything change before you resubmitted?") unanswerable.
                definition=await load_custom_definition_or_empty(workshop_id),
            )
        ),
        "exports": [_export_payload(row) for row in exports[:_HISTORY_EXPORT_LIMIT]],
        "exportsTruncated": len(exports) > _HISTORY_EXPORT_LIMIT,
        "entries": [
            {
                "id": row.id,
                "stageKey": row.stageKey,
                "entityKey": row.entityKey,
                "ordinal": row.ordinal,
                "createdAt": _iso(row.createdAt),
                "updatedAt": _iso(row.updatedAt),
                "deletedAt": _iso(row.deletedAt),
            }
            for row in entries[:_HISTORY_ENTRY_LIMIT]
        ],
        "entriesTruncated": entries_truncated,
    }


# --------------------------------------------------------------------------------------
# Private helpers
# --------------------------------------------------------------------------------------


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def _export_payload(row: Any) -> dict[str, Any]:
    """One recorded export, as the history screen reads it.

    A superset of what ``list_exports`` returns, and additive to it rather than a replacement: the
    generator, the registry version in force at generation, and the storage key where one exists.
    ``generatedBy`` is ``SetNull``, so an export made by an account that has since been deleted
    names NOBODY — not the workshop's owner, who is the tempting default and would put a name
    against a file they never produced.
    """
    author = getattr(row, "generatedBy", None)
    return {
        "id": row.id,
        "format": row.format,
        "templateId": row.templateId,
        "fileName": row.fileName,
        "fileSizeBytes": row.fileSizeBytes,
        "pageCount": row.pageCount,
        "checksumSha256": row.checksumSha256,
        "generatedOnDevice": row.generatedOnDevice,
        "schemaVersion": row.schemaVersion,
        "warnings": row.warnings,
        "generatedAt": _iso(row.generatedAt),
        "generatedById": row.generatedById,
        "generatedByName": getattr(author, "name", None) if author else None,
    }


async def _transcripts_payload(entries: list[Any], viewer: Any) -> dict[str, Any]:
    """THE TRANSCRIPT COMING BACK ONTO THE STAGE, keyed by the media id the AUDIO field holds.

    A designer records an artisan explaining a technique, the media queue transcribes it minutes or
    hours later, and the text has to appear against the field they recorded it into — otherwise the
    transcription is invisible and the feature may as well not exist. Keying by media id rather
    than by field key is what makes that work for a collection: five prototypes each with a voice
    note are five ids in one payload, and the client matches each to the row holding it.

    A stage with no audio costs one dictionary and no query.

    ``viewer`` gates it: the transcript is the CONTENT of a recording, so an AUDIO id a client wrote
    onto a stage is not on its own permission to read one back. See ``load_transcript_items``.
    """
    items = await load_transcript_items(entries, viewer=viewer)
    return {item.media_id: item.payload() | {"text": item.text} for item in items}


def _require_designer(user: Any) -> None:
    """Gate the two capture aids on the same rank that may run a workshop at all.

    Stated here rather than as a dependency because both routes already take ``current_user`` for
    other reasons, and because these are not record operations: there is no workshop to own and no
    row to check, only a question of whether this account is one the app invites to capture. A card
    scan sends a photograph of somebody's Aadhaar to a third-party model and a dictation spends
    provider credit per press, so neither is something to leave open to every signed-in account.
    """
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Running a design workshop requires Designer access or above.",
        )


def _parse_date(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(str(raw)[:10]).replace(tzinfo=UTC)
    except ValueError:
        return None


def _parse_datetime(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        text = str(raw).replace("Z", "+00:00")
        parsed = datetime.fromisoformat(text)
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)
    except ValueError:
        return None


def _stages_payload(entries: list[Any]) -> dict[str, Any]:
    """Group stage entry rows into ``{stage: {singleton, collections, custom}}``."""
    from app.services.stage_schema import Cardinality

    entity_cardinality = {
        e.key: e.cardinality for s in stages() for e in s.entities
    }
    out: dict[str, Any] = {}
    for row in entries:
        bucket = out.setdefault(
            row.stageKey, {"singleton": {}, "collections": {}, "custom": {}}
        )
        data = dict(row.data or {})
        if row.entityKey == CUSTOM_ENTITY_KEY:
            # THE RESERVED ROW GETS ITS OWN KEY IN THE STAGE PAYLOAD, and this is the second of the
            # four places the design's price is paid. Without this branch it falls to the collection
            # arm below and comes back as `collections["_custom"]` with `_entryId` and `_ordinal`
            # injected into it — a phantom repeating entity on every stage that has custom answers,
            # which both clients would render as a table of one row they cannot delete, and whose
            # `_ordinal` key would then be echoed back into the container on the next save.
            bucket["custom"] = data
        elif entity_cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            bucket["singleton"] = data
        else:
            data["_entryId"] = row.id
            data["_ordinal"] = row.ordinal
            if row.clientKey:
                data["_clientKey"] = row.clientKey
            bucket["collections"].setdefault(row.entityKey, []).append(data)
    for bucket in out.values():
        for rows in bucket["collections"].values():
            rows.sort(key=lambda r: r.get("_ordinal", 0))
    return out


async def _report_inputs(
    workshop_id: str,
    record: Any,
    *,
    viewer: Any,
    requested_template_id: Any = None,
    transcripts: bool | None = None,
    #: Whether this document is to carry the accepted-AI-layer annexure. NOT tri-state, unlike
    #: ``transcripts``: no template declares that section, so there is no template default for an
    #: absent answer to preserve. See ``ReportGenerateIn.includeAiLayers``.
    ai_layers: bool = False,
) -> tuple[Any, Any, list[str], str]:
    """Everything the synchronous render needs, loaded on the event loop before it starts.

    The third element is the warnings this loading produced — a transcript annexure shorter than
    the designer expected, a photograph that could not be resolved — which the caller merges with
    the builder's own. The fourth is the resolved template id, resolved HERE because the loads
    below have to know what the document will contain; the callers use it rather than resolving it
    a second time and risking two answers.

    THREE WAVES, NOT FIVE SEQUENTIAL LOADS, and on this deployment that is the whole cost of the
    endpoint. The database is in another AWS region and one round trip measured 756ms against
    queries that execute in under a millisecond (``services/concurrency``), so a fully-referenced
    workshop paid roughly 6.8s of pure network before the renderer started, and again on Generate.
    The dependency graph only ever demanded three: the entries, then everything that reads the
    entries, then the media resolver — which cannot start until ``attach_report_references`` has
    told it about the photographs hanging off the REFERENCED records rather than off the stages.
    The loads in wave 2 write DIFFERENT attributes of ``data`` (``references``,
    ``district_points``, the transcripts, the questionnaires), so gathering them is safe; anything
    that shared one would have to stay sequential. Two of the four are CONDITIONAL — the map's
    anchors and the questionnaire annexure are appended only when the resolved template draws them —
    which is why the warnings below are indexed from the END of ``results`` rather than by position.

    ``viewer`` is threaded through to the two media reads because a media id on a stage is whatever
    a client wrote there — see ``design_workshops.media_resolver``.
    """
    entries = await entry_rows(workshop_id)
    data = assemble_workshop_data(record, entries)
    # RESOLVED BEFORE THE LOADS, because one of them is only worth paying for on some templates.
    # `resolve_template_id` needs the stage-20 answers, which is why this cannot sit any earlier.
    template_id = resolve_template_id(
        requested_template_id, data.singleton("REPORT_GENERATION"), record
    )

    # `attach_report_references` fills the photographs that are not in the entries at all — an
    # artisan's portrait, the catalogue picture of the product a prototype copied — and the map's
    # artisan pins, whose home district lives on the Artisan's Location and nowhere on the roster
    # row. Its result feeds the resolver, which is what makes it wave 2 and the resolver wave 3.
    loads: list[Any] = [attach_report_references(data, entries)]
    # POSITIONS ONLY WHEN SOMETHING DRAWS THEM. This reads every pinned Location in the repository
    # and folds it across all 795 districts; four of the six templates contain no map at all and
    # threw the whole result away. The cost tracked the size of the archive rather than the size of
    # the workshop, on both Preview and Generate. `apply_report_settings` never ADDS a MAP section
    # and MAP is not one of the toggles it removes, so the base template is the right thing to ask.
    # (It does add exactly one section — ANNEXURE_AI_LAYERS, and only when the request asks — which
    # is why this sentence names MAP rather than claiming the function only ever removes.)
    specials = {section.special for section in get_template(template_id).sections}
    draws_map = SpecialSection.MAP in specials
    if draws_map:
        loads.append(attach_district_anchors(data))
    # THE SAME "ONLY WHEN SOMETHING DRAWS IT" RULE, and it is not a micro-optimisation here either:
    # this is five queries against the questionnaire tables, and `PHOTO_CATALOGUE` — or any template
    # a later release ships without the annexure — would throw every row away. `apply_report_settings`
    # never ADDS an ANNEXURE_QUESTIONNAIRES section, so the base template is the right thing to ask,
    # exactly as the map above asks it.
    draws_questionnaires = SpecialSection.ANNEXURE_QUESTIONNAIRES in specials
    # WHICH SLOT EACH WARNING-PRODUCING LOAD LANDS IN, RECORDED AS IT IS APPENDED.
    #
    # This used to be read from the END of the results — "the last two loads, in the order they were
    # appended" — with a comment explaining that the map load in the middle is conditional. That
    # worked, and it was one append away from not working: adding a load at the bottom silently
    # shifted `results[-1]` onto it, and the symptom would have been the transcript annexure's
    # warnings quietly disappearing from the download while nothing failed and no test noticed. The
    # third annexure below is exactly that append, so the fragility is removed rather than
    # negotiated with.
    warning_slots: dict[str, int] = {}

    def _append(load: Any, *, warns_as: str = "") -> None:
        if warns_as:
            warning_slots[warns_as] = len(loads)
        loads.append(load)

    if draws_questionnaires:
        _append(attach_report_questionnaires(data, workshop_id), warns_as="questionnaires")
    _append(
        attach_report_transcripts(data, entries, viewer=viewer, requested=transcripts),
        warns_as="transcripts",
    )
    # THE SAME "ONLY WHEN SOMETHING DRAWS IT" RULE A THIRD TIME, arrived at from the other side. The
    # two loads above ask the BASE template whether anything draws their section, because
    # `apply_report_settings` can only take those sections AWAY. This one cannot be asked that way:
    # no template carries ANNEXURE_AI_LAYERS at all, and `apply_report_settings` SPLICES it in when —
    # and only when — the request asked for it. So the request flag is the whole of the question,
    # and it is read here rather than inferred from a template that will never mention it.
    _append(
        # `viewer` AND THE MEDIA PREDICATE, both required and neither defaulted — see that function's
        # docstring for the disclosure this closes. `_readable_media_ids` is passed rather than
        # imported by the service because it lives here, beside the ai-layers list route that already
        # applies the identical rule: one definition of "may this account read this recording", two
        # callers, and no import from the API layer into a service.
        attach_report_ai_layers(
            data,
            workshop_id,
            viewer=viewer,
            readable_media=lambda ids: _readable_media_ids(ids, viewer),
            requested=bool(ai_layers),
        ),
        warns_as="aiLayers",
    )
    # THE DESIGNER'S OWN SECTIONS, LOADED UNCONDITIONALLY, and the "only when something draws it"
    # rule three of the loads above follow deliberately does not apply. Those three ask a TEMPLATE
    # whether it carries their section; this one has no template to ask — no template carries a
    # workshop-specific section and none could, because `apply_report_settings` is what splices them
    # in and it can only do that once it has been handed the definition this load produces. It is
    # two flat queries against a table that is empty for every workshop nobody has added a question
    # to, which is most of them, and it returns immediately when there is nothing there.
    _append(
        attach_report_custom_sections(data, entries, workshop_id),
        warns_as="customSections",
    )

    results = await gather_reads(*loads)
    reference_photos = results[0]
    # In the order a designer should read them: what was missing from the questionnaires, then from
    # the transcripts, then from the machine-assisted text. A load that never ran contributes
    # nothing rather than an empty entry, because it was never asked a question.
    warnings: list[str] = []
    for name in ("questionnaires", "transcripts", "aiLayers", "customSections"):
        slot = warning_slots.get(name)
        if slot is not None:
            warnings.extend(results[slot])

    resolver = await media_resolver(entries, viewer=viewer, extra_ids=reference_photos)
    if resolver.withheld:
        # NOT SILENT. A photograph that is missing from a report reads as a photograph nobody took;
        # saying so is what tells a designer to ask the colleague who uploaded it for a data-access
        # grant instead of re-photographing an artisan who has gone home.
        warnings.append(
            f"{len(resolver.withheld)} attached file(s) could not be included: they were "
            "uploaded by another account, or the file is gone."
        )
    return data, resolver, warnings, template_id


def _build_only(data: Any, template_id: str, resolver: Any, record: Any) -> tuple[Any, list[str]]:
    """Build the document without rendering it, for the preview.

    ``resolver.ref`` and not ``resolver`` — the builder wants geometry, not bytes, and the
    preview never needs the bytes at all because the browser fetches each photo by its own
    media URL.

    EVERY STAGE-20 ANSWER IS APPLIED HERE TOO, and that is the whole point of this function
    changing. The preview used to build the bare template: no accent, no cover overrides, none of
    the section toggles. So a designer switched the report to maroon, turned the annexures off,
    looked at a preview that was still indigo with both annexures in it, and either submitted a
    file they had never actually seen or concluded the settings were broken. A preview that does
    not match the file is worse than no preview, because it is trusted.

    The colour is resolved here rather than only in the browser for the same reason. The web page
    mirrors this palette client-side, and two independent derivations of one colour are two
    chances to disagree.
    """
    from dataclasses import replace

    from app.services.design_workshops import report_meta
    from app.services.report_builder import build_report
    from app.services.report_custom_sections import custom_sections_of
    from app.services.report_templates import apply_report_settings, template as get_template
    from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent

    settings = data.singleton("REPORT_GENERATION")
    template = get_template(template_id)
    accent = resolve_accent(None, settings)
    theme = theme_from_accent(accent, base=template.theme) if accent else template.theme
    fonts = resolve_font(None, settings)
    if fonts:
        theme = replace(theme, heading_font=fonts[0], body_font=fonts[1])

    return build_report(
        data, template_id, resolver.ref,
        meta=report_meta(record, template_id, settings),
        theme=theme,
        # The designer's own sections, read off the same data the download reads them off. A preview
        # that did not place them would show a document without the block the designer just added
        # and then hand them a file that has it — and a preview that does not match the file is
        # worse than no preview, because it is trusted.
        template=apply_report_settings(
            template, settings, custom_sections=custom_sections_of(data)
        ),
    )


def _report_file_name(record: Any, fmt: str) -> str:
    """A file name safe on every filesystem the report will land on.

    Windows forbids nine characters outright and a report named after a craft is routinely
    saved onto a departmental share; a name that fails to save is a report that was not
    delivered.

    Unicode letters are kept here — a designer whose workshop is titled in Odia should get a
    file named in Odia — and :func:`_content_disposition` is what makes that safe to put in a
    header. Sanitising to ASCII in this function instead would have been the easy fix and the
    wrong one: it would have named every Devanagari workshop ``workshop_20260807.docx``, and
    a folder of thirty identically-named reports is its own kind of data loss.
    """
    stem = record.workshopCode or record.title or "workshop"
    safe = "".join(c if (c.isalnum() or c in " -_") else "_" for c in str(stem)).strip()
    safe = "_".join(safe.split())[:60] or "workshop"
    stamp = datetime.now(UTC).strftime("%Y%m%d")
    return f"DesignWorkshop_{safe}_{stamp}.{_EXTENSION[fmt]}"


def _content_disposition(file_name: str) -> str:
    """An RFC 6266 Content-Disposition that survives a non-ASCII file name.

    THE BUG THIS EXISTS FOR. Every ASGI header value is encoded latin-1, so a single codepoint
    above U+00FF raises ``UnicodeEncodeError`` inside Starlette — after the handler has already
    returned, as a bare 500 with no indication which header did it. ``str.isalnum()`` is True
    for every Unicode letter, so a workshop titled ``ସମ୍ବଲପୁରୀ ଇକତ କର୍ମଶାଳା`` sailed through the
    sanitiser above and made report generation impossible for that record, permanently, with no
    in-app workaround. On an app built for Indian craft clusters that disabled the product's
    primary deliverable for a large share of workshops.

    The fix is the form the RFC defines for exactly this: an ASCII fallback in ``filename=`` for
    anything that cannot read the extended form, and a percent-encoded UTF-8 ``filename*=`` that
    every browser released this decade prefers. The designer gets the Odia name; the header
    stays latin-1.
    """
    ascii_name = "".join(
        c if (c.isascii() and (c.isalnum() or c in " -_.")) else "_" for c in file_name
    ).strip("_ ") or "workshop-report"
    quoted = quote(file_name, safe="")
    return f'attachment; filename="{ascii_name}"; filename*=UTF-8\'\'{quoted}'


#: How much of ``X-Report-Warnings`` a response may spend. A cap is not optional — every warning is
#: a whole sentence, a fully-referenced DCH_STANDARD workshop raises a dozen of them, and proxies in
#: front of this API refuse a response whose headers exceed 4-8 KB outright, which would turn a
#: successful report into a failed download.
_WARNINGS_HEADER_BUDGET = 900


def _warnings_header(warnings: list[str]) -> str:
    """The warnings as one ``X-Report-Warnings`` value: whole sentences, and never a silent tail.

    THE DEFECT THIS EXISTS FOR, MEASURED RATHER THAN IMAGINED. This header used to be built as
    ``"; ".join(warnings)[:900]``, and on DCH_STANDARD — the DEFAULT template — a workshop with an
    attached questionnaire raised twelve warnings of which the header carried eight. The twelfth was
    ``"1 questionnaire(s) attached to this workshop have no recorded answers and were left out of the
    questionnaire annexure (…)"``: the one sentence that tells a designer WHY the annexure they were
    promised is not in the file. It was cut off, and nothing said so — the designer saw a report with
    no questionnaire annexure and no explanation, which is exactly the complaint that sent this lane
    looking. The load warnings are appended last (see ``generate_report``), so they are always the
    first casualties, and they are the ones that describe a WHOLE ANNEXURE missing from the document
    rather than a field missing from inside it.

    The eighth item was also cut MID-WORD — the header ended ``"Stage 9 (…): 2 required "`` — and
    ``frontend/lib/designWorkshops.ts`` splits this value on ``";"`` and shows each piece to the
    designer, so a half-sentence was rendered as a complete warning.

    So: pack WHOLE warnings until the budget, then say how many did not fit. This is the same rule
    ``report_annexures.MAX_PARAGRAPHS_PER_TRANSCRIPT`` and ``report_questionnaires``' sitting cap
    already apply inside the document — a visible note explaining where it stopped, never a silent
    drop — applied to the transport that carries the warnings about it.

    ``x-report-warning-count`` stays the TRUE total and is not reduced to what fitted: a client that
    compares the two can tell that it is not holding the whole list, and a client that ignores the
    header entirely still gets the count right.

    ONE WARNING IS ONE PIECE, and the packing is only half of what makes that true — the other half
    is :func:`one_piece` below, because a semicolon inside a warning splits it just as effectively
    as truncation did.

    Non-ASCII is replaced rather than dropped for the reason :func:`_content_disposition` exists:
    every ASGI header value is encoded latin-1, so a warning naming a craft in Odia would raise
    inside Starlette after the handler returned and turn a generated report into a bare 500.
    """
    def one_piece(value: str) -> str:
        """One warning as exactly ONE piece of this header, whatever text it carries.

        ``";"`` is this header's item separator and ``frontend/lib/designWorkshops.ts`` splits the
        value on it, so a semicolon INSIDE a warning is indistinguishable from the boundary between
        two — and the packing above, which exists so the designer never reads a fragment, cannot
        help with a fragment the content itself creates.

        MEASURED, NOT IMAGINED. ``report_questionnaires.questionnaire_warnings`` interpolates the
        questionnaire's TITLE, which a designer types. A form called "Loom survey; round two"
        produced ``x-report-warning-count: 2`` and THREE pieces on the screen, the last of them
        ``"round two)."`` — a warning that a whole annexure is missing from the report, delivered as
        two half-sentences, one of which means nothing on its own.

        A comma rather than a deletion because the semicolon is doing work in the sentence a
        designer wrote, and losing the pause reads worse than shifting it. See :func:`_dropped_note`
        for the same constraint stated from the other side.

        Non-ASCII is replaced first, for the reason in this function's own docstring.
        """
        return str(value).encode("ascii", "replace").decode("ascii").replace(";", ",")

    items = [one_piece(w) for w in warnings if str(w).strip()]
    if not items:
        return ""

    joined = "; ".join(items)
    if len(joined) <= _WARNINGS_HEADER_BUDGET:
        return joined

    # The note has to fit inside the same budget, so the room left for real warnings is measured
    # against the WIDEST note this call could end up printing — one naming every item as dropped.
    room = _WARNINGS_HEADER_BUDGET - len(_dropped_note(len(items))) - 2
    kept: list[str] = []
    used = 0
    for item in items:
        cost = len(item) + (2 if kept else 0)
        if used + cost > room:
            break
        kept.append(item)
        used += cost

    # A single warning longer than the whole budget would otherwise produce a header that says only
    # that something was dropped and nothing about what. Truncating that one is the lesser loss, and
    # the ellipsis marks it as truncated rather than passing a fragment off as a sentence.
    if not kept:
        kept = [items[0][: max(1, room - 3)].rstrip() + "..."]

    dropped = len(items) - len(kept)
    return "; ".join(kept + ([_dropped_note(dropped)] if dropped else []))


def _dropped_note(count: int) -> str:
    """What the header says instead of the warnings it could not carry.

    Names the preview because that is where the full list is actually reachable — ``GET
    /report/preview`` returns ``warnings`` as an uncapped JSON array built from the same load — so
    this is an instruction a designer can act on rather than an apology.

    NO SEMICOLON INSIDE IT, and that is a constraint rather than a preference: ``"; "`` is this
    header's item separator and ``frontend/lib/designWorkshops.ts`` splits on ``";"`` and prints
    each piece as its own warning, so a semicolon here would break this one sentence into two
    half-sentences on the designer's screen — the same "a fragment shown as a whole warning" defect
    the packing above exists to stop.
    """
    return (
        f"{count} further warning(s) did not fit in this header. The report preview lists all of "
        "them."
    )


def _block_payload(block: Any) -> dict[str, Any]:
    """One report block as JSON, for the web preview.

    Deliberately a shallow projection rather than a full serialisation: the preview needs to
    draw the block, not to reconstruct the dataclass, and a lossless encoding would tempt a
    client into rendering from its own reassembled model instead of from this one.
    """
    from dataclasses import asdict, is_dataclass

    name = type(block).__name__
    payload: dict[str, Any] = {"type": name.replace("Block", "").upper()}
    if is_dataclass(block):
        for key, value in asdict(block).items():
            payload[key] = value
    return payload
