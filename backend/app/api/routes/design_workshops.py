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
workshop the same way. It is also the biggest body this API serves to a cold client — 149,465
bytes of JSON, 23,618 on the wire once gzipped, MEASURED on 2026-08-22 — so it answers a
conditional GET, which turns a revalidation into 664 bytes: see
:func:`get_stage_schema` for the ETag, why the validator is derived from the response bytes rather
than from ``registry_version()``, and why the freshness lifetime is zero.

It does NOT drive an Android draft migration, whatever a reader of ``version`` may assume. That
claim stood here for months and ``stage_schema.registry_to_dict`` refutes it at length from a
measurement on a real handset: the browser keys its IndexedDB registry store by the version, the
server stamps it on the workshop row, and Android merely rewrites a cache file.

**Nothing here hard-deletes.** ``DELETE`` sets ``deletedAt``. This is a research data set, the
requirement is explicit that data is retained, and a designer's two weeks of fieldwork is not
something a mis-tap should end. Every read filters ``deletedAt: null`` — **with one exception, and
it is the exception that makes the safety net usable**: ``GET /design-workshops`` takes
``deletedOnly`` (the trash) and ``includeDeleted``, both ADMIN ONLY and both refused with a 403
rather than quietly ignored. Without them nothing on any surface would name a deleted workshop, so
an admin could restore only one whose id they had written down before deleting it — while the web's
delete confirmation promised otherwise in so many words. (Android does not enter into it: its
``WorkshopRepository.deleteDesignWorkshop`` has no caller in any screen, so the handset has neither a
delete control for a workshop nor a restore — checked 2026-08-27 with a search for that identifier
across ``android/app/src``, which finds it only in the repository and its API twin.)

**Permissions**, as this file actually enforces them — read the four clauses, not the ladder,
because one of them is not a rank threshold:

* CREATING one is ``assert_can_create_design_workshops`` — ADMIN or MASTER_ADMIN, and NOTHING
  ELSE. It is the narrowest gate in this file and the only one that is narrower than
  ``_require_designer``: **a DESIGNER may not start a workshop.** A workshop is not a record, it is
  the container a fortnight of records lives in and the unit the ministry indexes and funds, so
  opening one is an administrative act performed by whoever holds the sanction order.
  ``assert_can_create_records`` used to sit on this route beside ``_require_designer`` and both are
  gone from it: each was implied by the gate that replaced them, and a predicate that can never
  fire is how a rule quietly comes back.
* EVERYTHING ELSE A DESIGNER DOES IS UNCHANGED, and that is a load-bearing sentence rather than
  reassurance. ``PATCH``, every stage write, the custom sections, the two capture aids (OCR and
  dictation), the AI layers and the five AI verbs all call ``_require_designer`` — eighteen routes,
  counted out in that function's own docstring. NOT the report: generating one is open to anyone who
  can READ the workshop, as the clause four bullets down says, and this sentence claimed the
  opposite of it for as long as the two have sat here. ``can_run_design_workshops``, a SET,
  ``{DESIGNER, ADMIN, MASTER_ADMIN}``, not a floor, so a PROFESSOR outranks a designer everywhere
  else in this codebase and still cannot touch a workshop. A designer opens a workshop an admin
  created (or granted them), fills all 22 stages, creates records inside it and submits the
  report. ``tests/test_design_workshop_gate.py`` asserts that explicitly, because a permission
  change that quietly cost a designer their stage edits would be far worse than this rule is worth.
* OPENING someone else's is decided entirely by ``load_workshop_or_404``: the creator, an admin, or
  an account an admin has given a ``DesignWorkshopViewer`` row. A grant carries read AND the stage
  writes that go through that helper — see ``services/design_workshop_viewers.py`` for the two it
  deliberately does not carry, DELETE and RE-GRANTING. IT ALSO CARRIES THIS WORKSHOP'S OWN MEDIA,
  which that file's "what a grant confers" banner does not mention at all (true as of 2026-08-27;
  check ``grep -n media backend/app/services/design_workshop_viewers.py``, which finds nothing):
  ``records.owned_or_granted_where`` has a third arm keyed on the media TAG, so every ``MediaFile``
  tagged ``linkedRecordType="designWorkshop"`` with THIS workshop's id is readable by a grantee —
  its bytes, its transcript, and since 2026-08-27 its ``url`` on ``GET /media`` too. The full
  statement of that half is ``docs/PERMISSIONS.md`` §4.4.1.
* DELETING is ``assert_can_delete``; restoring is ``require_admin``; and LISTING THE DELETED ones is
  the same admin test as the restore, stated inline in ``list_design_workshops`` because it gates two
  query parameters rather than a route. The three have to agree: a trash a designer could read would
  show them rows ``load_workshop_or_404`` then 404s, and a restore looser than the trash would let
  somebody act on a list they were shown.
* AI LAYERS follow the stage-write rule and not the delete one: LISTING them needs whatever
  ``load_workshop_or_404`` needs, and registering, accepting, withdrawing or declining one needs
  ``_require_designer`` plus ``for_edit=True``. ``assert_can_delete`` is deliberately NOT on the
  layer delete — that gate guards a workshop, which is a fortnight of fieldwork; declining a
  model's suggestion is the ordinary work of the designer reading it, the row is soft-deleted, and
  the material it was derived from is untouched by construction.
  READING A LAYER'S TEXT IS THE FOURTH CLAUSE, not the first: the text is a stored copy of a
  transcript, so it is gated per recording by ``owned_or_granted_where`` like every other transcript
  surface, and a layer standing on a recording the caller may not read is listed with its provenance
  and ``textWithheld``. This bullet used to end "Opening a workshop has never conferred the right to
  read the media inside it" — CORRECTED 2026-08-27, because it had stopped being true the day that
  predicate grew its tag-keyed third arm. Opening a workshop DOES confer the media tagged TO it.
  What it does not confer is a recording tagged to a different workshop or to none — including one
  whose id somebody typed onto THIS workshop's stage, which is the case ``textWithheld`` is actually
  guarding. A copy of a transcript is still the transcript.
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
import logging
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote

from fastapi import (
    APIRouter,
    Body,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
    status,
)
from fastapi.responses import JSONResponse
from pydantic import model_validator

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    assert_can_create_design_workshops,
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
from app.services.entry_provenance import (
    canonical_divergence,
    resolve_display_names,
    resolve_entry_provenance,
)
from app.services.design_workshops import (
    REFERENCE_LIMIT_DEFAULT,
    REFERENCE_LIMIT_MAX,
    assemble_workshop_data,
    assert_every_designer_may_be_named,
    attach_district_anchors,
    attach_report_ai_layers,
    attach_report_custom_sections,
    attach_report_questionnaires,
    attach_report_references,
    attach_report_transcripts,
    attach_the_named_designers,
    entry_rows,
    load_workshop_or_404,
    media_resolver,
    named_designer_team,
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
    DISCARD as RETENTION_DISCARD,
    STORE as RETENTION_STORE,
    SUPPORTED_MIME_TYPES,
    IdentityOcrUnavailable,
    get_identity_ocr_settings,
    parse_retention,
    read_identity_card,
    retention_stamp,
    with_retention,
)
from app.services.market_analysis import analyse, market_findings_payload
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    contains,
    enum_filter_or_422,
    owned_or_granted_where,
    plain,
    with_id_tiebreak,
)
from app.services.report_docx import DOCX_MIME
from app.services.report_pdf import PDF_MIME
from app.services.report_templates import (
    SpecialSection,
    inert_section_toggles,
    template as get_template,
    template_choices,
)
from app.services.memory_budget import budget_bytes
from app.services.s3 import (
    ObjectTooLarge,
    delete_object,
    discard_temp,
    download_to_temp,
    get_object_bytes,
    head_object,
)
from app.services.stage_schema import (
    REF_SCOPE_ALL,
    registry_to_dict,
    registry_version,
    stages,
)
from app.services.workshop_transcripts import load_transcript_items

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])

# Used by exactly one caller — the identity-photograph discard, when object storage refuses to
# delete an identity document. That failure is invisible from the API's side (the caller gets a 502
# and retries or gives up) and it leaves a regulated file in the bucket, so it is the one thing in
# this file an operator has to be able to find afterwards. It logs the exception TYPE and never the
# object key: the key embeds the uploader's user id and the original filename.
logger = logging.getLogger(__name__)

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
# The workshop header: which of its columns a write may reach, and which it may never
# --------------------------------------------------------------------------------------
#
# ONE TABLE READ BY THE CREATE AND BY THE EDIT, because two hand-kept tuples in one file is how the
# two halves of "the workshop's own fields" come to disagree. ``DesignWorkshop`` declares thirty-odd
# columns. Eleven of them are the header a person fills in; the rest answer to something else — to
# stage 1, to the consent route, to DELETE and restore, or to the database itself — and every one of
# those twenty is a column an edit form will be TEMPTED to render, because ``workshop_summary``
# serialises most of them on the way out.
#
# THE RULE, AND IT IS DELIBERATELY THE WHOLE RULE:
#
#     PATCH writes exactly the columns POST writes, less the two stamps POST writes once.
#
# ``createdById`` and ``schemaVersion`` are facts about the create. Everything else the create body
# can reach, an edit can correct. Written that way the rule is CHECKABLE rather than remembered, and
# ``tests/test_design_workshops.py::test_the_edit_reaches_exactly_what_the_create_reaches`` reads
# both tables and asserts it — because both ways of getting this wrong are silent:
#
# * A column CREATABLE AND NOT PATCHABLE is write-once with no screen anywhere saying so. ``notes``,
#   ``templateId`` and ``workshopId`` were exactly that for the whole life of this product: the
#   create form collected them, no stage-1 field mirrors them (see the create route's own
#   "``notes`` and ``workshopId``, neither of which is a promoted column at all"), and so the only
#   way to fix a typo in a workshop's notes was to delete the workshop and start the fortnight
#   again.
# * A column PATCHABLE AND NOT CREATABLE is a SECOND WRITER for a column that already has one,
#   which for the promoted columns is the defect written up at length in ``seed_designer_prefill``.
#
# WHAT THIS TABLE IS NOT. It is not a permission and it is not a validation. Who may send a body at
# all is ``_require_designer`` plus ``load_workshop_or_404``; what a value may CONTAIN is
# ``DesignWorkshopUpdate`` and the registry. This is the narrower question of which COLUMNS the two
# header endpoints own, asked once so that the answer cannot differ between them.


#: The workshop's own non-date columns, in the order a refusal lists them.
#:
#: ``status`` is here because the create writes it (hard-coded ``"DRAFT"``) and the record page's
#: SubmissionCard patches it — Mark complete / Submit / Reopen is the one workshop-entity write the
#: web client has ever had. It is deliberately NOT something a details form should offer beside a
#: title box: it has its own confirmation, its own readiness count and its own online-only failure
#: sentence, and a second writer for it would let somebody archive a workshop while renaming it.
#: The route accepts it; a details form should not send it.
_HEADER_TEXT_COLUMNS: tuple[str, ...] = (
    "title",
    "templateId",
    "craftName",
    "clusterName",
    "state",
    "district",
    "notes",
    "workshopId",
    "status",
)

#: The two that go through :func:`_parse_date` instead of being copied as text.
_HEADER_DATE_COLUMNS: tuple[str, ...] = ("startDate", "endDate")

#: Of the columns above, the ones ``prisma/schema.prisma`` declares NOT NULL — ``title`` bare,
#: ``templateId`` with a default, ``status`` as an enum with a default.
#:
#: THEY ARE THE ONES AN EXPLICIT ``null`` MUST BE REFUSED FOR, and the refusal is the point of
#: naming them. Prisma answers ``{"title": null}`` with ``MissingRequiredValueError``, which this
#: API renders as a bare 500 — "the server is broken" to a client that in fact sent something the
#: server can name. Everything not in this set is a nullable column and may genuinely be cleared;
#: see :func:`_header_patch_data` for why those two cases have to stay distinguishable.
_HEADER_REQUIRED_COLUMNS = frozenset({"title", "templateId", "status"})

#: What ``create_design_workshop`` copies off its body when the value is truthy.
#:
#: DERIVED RATHER THAN RETYPED, and it evaluates to exactly the tuple that used to be written out
#: at that loop — ``("craftName", "clusterName", "state", "district", "notes", "workshopId")``, in
#: that order. The create's SEMANTICS are untouched and deliberately still differ from the edit's:
#: it drops a falsy value silently (a blank box on a create means "not known yet", and there is no
#: stored value for it to fail to overwrite) and it does not strip. Only the LIST OF COLUMNS is
#: shared, which is the half that must never differ.
_CREATE_OPTIONAL_COLUMNS: tuple[str, ...] = tuple(
    key for key in _HEADER_TEXT_COLUMNS if key not in _HEADER_REQUIRED_COLUMNS
)

#: The stamps the create writes once and no edit may rewrite. Named so the rule at the head of this
#: section can be asserted as arithmetic rather than kept in somebody's head.
_CREATE_ONLY_STAMPS = frozenset({"createdById", "schemaVersion"})


_STAGE_ONE_OWNS = (
    "this is filled in by saving stage 1 (Workshop Setup & Cover Information), which is the only "
    "writer of the workshop's cover columns. Open the stage and correct it there"
)

_CONSENT_HAS_ITS_OWN_ROUTE = (
    "consent is recorded through POST /design-workshops/{id}/dictation-consent, which also writes "
    "the append-only decision log. A consent that could be set from a header edit would be a "
    "consent that could be manufactured"
)


#: Every key this endpoint refuses BY NAME, with the sentence the client is told.
#:
#: WHY NAMED REFUSALS RATHER THAN LEAVING IT TO ``extra="forbid"``. Both are a 422 and nothing is
#: written either way, so this is not a safety fix — it is a legibility one, and the difference is
#: whether the client can act on the answer. "Extra inputs are not permitted" cannot tell a TYPO
#: from a field the product deliberately does not hand out, and the callers that will hit this are
#: not typing: a web form that reads ``workshop_summary`` and posts the object back sends all
#: twenty-four of its keys, and an Android build a release ahead sends whatever its create body
#: carries. For half of the keys below the true answer is "yes, but somewhere else" — stage 1, or
#: ``PUT /{id}/viewers``, or the consent route — and a client told only "not permitted" retries the
#: same body.
#:
#: AND THE ALTERNATIVE TO REFUSING IS WORSE THAN EITHER. Accepting the key and quietly not writing
#: it answers 200 to a request that changed nothing: the form clears its dirty flag, the designer
#: watches the box go back to the old value on the next load, and there is nothing anywhere to
#: read. That is the shape this whole table exists to refuse.
#:
#: THE ORDER IS THE ORDER A REFUSAL LISTS THEM IN, and the four groups are the argument.
_NEVER_PATCHABLE: dict[str, str] = {
    # ── NAMING THE DESIGNER IS A CREATE-TIME ACT ─────────────────────────────────────────────────
    # It decides whose ``DesignerProfile`` is copied into stage 1 and stage 3 BEFORE stage 1
    # exists, and the copy is the whole design — see ``seed_designer_prefill``. There is nothing
    # here for an edit to re-decide: the values belong to this workshop now, the designer may have
    # corrected them by hand for this workshop, and re-seeding would overwrite that with whatever
    # the profile says today. The PATCH handler's docstring states that in full.
    "designerUserId": (
        "the lead designer is named when the workshop is created, because that is the moment their "
        "profile is copied into stages 1 and 3. Correct the designer's details in stage 1 and "
        "stage 3, or change who may open this workshop with PUT /design-workshops/{id}/viewers"
    ),
    "designerUserIds": (
        "the designers a workshop is for are granted with PUT /design-workshops/{id}/viewers, "
        "which writes the DesignWorkshopViewer rows, and never through this body"
    ),
    # ── THE SIX PROMOTED COLUMNS NOTHING BUT STAGE 1 COLLECTS ────────────────────────────────────
    # ``promoted_values`` in stage_schema.py is the single writer of the denormalised columns;
    # nothing else may set them, or the two readings drift.
    #
    # SIX OF THE THIRTEEN ARE ACCEPTED ALL THE SAME — craft, cluster, state, district and the two
    # dates — and that is the EXISTING contract rather than something this change invented. The
    # create body already collects those six, so the rule at the head of this section admits them,
    # and an admin has always been able to correct a list entry without opening the stage. Their
    # cost is real and belongs on screen rather than in a refusal: a value corrected here is
    # overwritten the next time stage 1 is saved with that box empty, because ``_coerce_promoted``
    # nulls a promoted column of a touched entity whose value is blank. The record page already
    # prints the honest sentence; an edit form has to carry it too.
    #
    # THE SIX BELOW ARE COLLECTED NOWHERE BUT STAGE 1, so accepting them would mint a second writer
    # for a column that has exactly one, with no create-form history to point at as precedent.
    "venue": _STAGE_ONE_OWNS,
    "scheme": _STAGE_ONE_OWNS,
    "designerName": (
        "the designer's name is stage 1's, copied from their profile when the workshop was opened "
        "and correctable in the stage. It is the authorship line a ministry document prints — on "
        "the cover, in the certification block and in the .docx's own dc:creator — and a wrong "
        "name in it passes every automatic check this product has: completeness scores it 100%, "
        "readiness shows green and the report emits no warning, because the field is not missing, "
        "it is filled with the wrong person"
    ),
    "implementingAgency": _STAGE_ONE_OWNS,
    "sponsor": _STAGE_ONE_OWNS,
    "workshopCode": (
        "the workshop code is stage 1's, it prints on the report cover, and it is what a scanned "
        "card resolves to. A code that has been printed and stuck on a card is not a field"
    ),
    # ── PROVENANCE STAMPS: WHO, WHEN, AND AGAINST WHICH REGISTRY ─────────────────────────────────
    "id": "a workshop's id is its identity, not one of its fields",
    "createdById": (
        "who opened this workshop is the first thing load_workshop_or_404 tests, so a creator that "
        "could be patched would make access grantable by the person being granted it"
    ),
    "createdAt": "when the workshop was opened is set by the database and is not an opinion",
    "updatedAt": "the database sets this on every write",
    "deletedAt": (
        "deletion is DELETE /design-workshops/{id} and restoring is POST "
        "/design-workshops/{id}/restore. deletedAt and deletedById are one fact in two columns — "
        "deleted, by whom — and nothing else may write half of it"
    ),
    "deletedById": (
        "deletion is DELETE /design-workshops/{id} and restoring is POST "
        "/design-workshops/{id}/restore"
    ),
    "schemaVersion": (
        "the digest of the field registry this workshop was last written against. It is what lets "
        "a draft written by a phone under an older registry be detected rather than guessed at, "
        "and a value a client can set is a detector that lies"
    ),
    # ── THE CONSENT RECORD ───────────────────────────────────────────────────────────────────────
    "dictationConsent": _CONSENT_HAS_ITS_OWN_ROUTE,
    "dictationConsentAt": _CONSENT_HAS_ITS_OWN_ROUTE,
    "dictationConsentById": _CONSENT_HAS_ITS_OWN_ROUTE,
    "dictationConsentByName": (
        "a display name resolved for the single-record read, not a stored column. See "
        "dictationConsentById"
    ),
}


def _immutable_field_refusal(offending: list[str]) -> str:
    """One clause per refused key, in one message, for the whole body.

    EVERY OFFENDING KEY AND NOT THE FIRST ONE, for the reason the create route already states about
    ineligible designers: a caller who ticked four boxes and is told about one has been sent on the
    first of two trips. A form that posted a whole ``workshop_summary`` back is sending fourteen of
    these at once and needs to hear about all fourteen.
    """
    reasons = "; ".join(f"{key} — {_NEVER_PATCHABLE[key]}" for key in offending)
    subject = (
        "This field is not editable here"
        if len(offending) == 1
        else "These fields are not editable here"
    )
    return (
        f"{subject}: {reasons}. The whole request was refused and nothing was written, rather than "
        "the fields being dropped and a 200 returned for a change that did not happen."
    )


class DesignWorkshopPatch(DesignWorkshopUpdate):
    """``DesignWorkshopUpdate`` plus a named refusal for the keys this endpoint does not hand out.

    WHY A SUBCLASS, AND WHY IT LIVES IN THE ROUTER. Everything about WHAT A VALUE MAY CONTAIN is
    inherited unchanged — the lengths, ``title``'s ``min_length=1``, and the
    ``status``/``templateId`` model validator that 422s an unknown enum token or an unregistered
    template naming the allowed values. Not one line of that is restated here, which is the point:
    the edit's bounds and the create's bounds are the same bounds, and a second copy of them would
    be a second thing to keep in step. What is ADDED is a statement about this ROUTE rather than
    about the shape of a workshop — which of the workshop's columns this endpoint owns — so it sits
    beside the route, next to ``_NEVER_PATCHABLE``, where the reason for each refusal is written
    down.

    THE VALIDATOR IS ``mode="before"`` BECAUSE ``extra="forbid"`` WOULD OTHERWISE GET THERE FIRST.
    ``APIModel`` forbids unknown keys, so ``designerName`` is already a 422 today — with the message
    "Extra inputs are not permitted", which is true and useless. Running before field validation is
    what lets the answer name the field and say where the value actually lives.

    A KEY THAT IS NEITHER DECLARED NOR NAMED IN THAT TABLE IS STILL ``extra_forbidden``, and that is
    the right split: a genuine typo should read as a typo. The one cost is that a body carrying an
    immutable key AND a typo is refused for the immutable key alone, so the typo is reported on the
    next attempt rather than beside it.
    """

    @model_validator(mode="before")
    @classmethod
    def _refuse_the_fields_this_endpoint_does_not_own(cls, data: Any) -> Any:
        # Not a Mapping when FastAPI hands validation something else entirely — a list, a scalar,
        # a body that is not JSON at all. There is nothing to inspect and pydantic's own error for
        # that shape is the correct one.
        if not isinstance(data, Mapping):
            return data
        offending = [key for key in _NEVER_PATCHABLE if key in data]
        if offending:
            raise ValueError(_immutable_field_refusal(offending))
        return data


def _header_patch_data(sent: Mapping[str, Any]) -> dict[str, Any]:
    """Turn a validated PATCH body into the ``data`` Prisma is handed.

    ``sent`` IS ``model_dump(exclude_unset=True)`` AND IT HAS TO BE. That is the whole of the
    partial-update contract, and it is the difference between two states pydantic otherwise renders
    identically:

    * **absent** — the client did not mention this field. Leave the stored value alone.
    * **present and ``null``** — the client means CLEAR IT. Write NULL.

    Read with ``getattr(payload, key) is not None``, which is how this route read its body until
    now, those two collapse into one and the SECOND one is the one that is lost: a designer who
    emptied the notes box and pressed Save was answered 200, told the workshop was saved, and found
    the old note still there on the next load with nothing anywhere to read. There is no ``""`` to
    send instead, because ``notes`` is a nullable column and an empty string is a different stored
    value from NULL — one that "has a note" is true of on every screen that asks.

    A BLANK STRING IS A CLEAR TOO, and that is a separate decision from the one above. ``TextInput``
    and ``TextArea`` send ``""`` for an emptied box and never ``null``, so a client that cannot send
    JSON null would otherwise be unable to clear anything at all. It also keeps this route agreeing
    with the OTHER writer of the six promoted columns it shares: ``_coerce_promoted`` sets a
    promoted column back to NULL when its stage value is blank rather than storing "", so a craft
    cleared here and a craft cleared in stage 1 have to end as the same stored value, or the list's
    "no craft recorded" filter answers differently depending on which screen did the clearing.

    AND A ``null`` ON A NOT NULL COLUMN IS A 422 NAMING IT, never a write. Prisma answers
    ``{"title": null}`` with ``MissingRequiredValueError`` — a bare 500, which reads as "the server
    is broken" to a client that sent something this server can perfectly well name. ``title`` is the
    only one of the three that can arrive blank rather than null, because ``min_length=1`` catches
    ``""`` but not ``"   "``, and a workshop titled with three spaces is a row that renders as a
    blank heading on every screen that lists it and can then only be found by its id.
    """
    data: dict[str, Any] = {}
    for key in _HEADER_TEXT_COLUMNS:
        if key not in sent:
            continue
        value = sent[key]
        if isinstance(value, str):
            # Stripped BEFORE the emptiness test, so "   " and "" are one answer. The create route
            # deliberately does not strip and is left alone; the asymmetry is stated at
            # _CREATE_OPTIONAL_COLUMNS.
            value = value.strip() or None
        if value is None and key in _HEADER_REQUIRED_COLUMNS:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"{key} cannot be emptied. Every workshop has one, the column is NOT NULL, and "
                    "an empty value would have been refused by the database rather than by this "
                    "sentence."
                ),
            )
        data[key] = value
    for key in _HEADER_DATE_COLUMNS:
        if key not in sent:
            continue
        raw = sent[key]
        if raw is None or (isinstance(raw, str) and not raw.strip()):
            data[key] = None
            continue
        parsed = _parse_date(raw)
        if parsed is None:
            # A DATE THIS SERVER CANNOT READ IS A REFUSAL AND NOT A CLEAR, which is the one place
            # this handler deliberately parts company with ``_parse_date``'s own contract. That
            # helper answers None both for "nothing was sent" and for "that is not a date", which
            # is right on the CREATE — a malformed date there is a box the admin has not filled in
            # properly yet and there is no stored value to lose. Here the same answer would
            # silently NULL a column that held a real date, under a 200, on a workshop whose dates
            # print on the report cover and decide which list filters can find it.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"{key} is not a date this server can read. Send it as yyyy-mm-dd, or send "
                    "null to clear it."
                ),
            )
        data[key] = parsed
    return data


async def _assert_linked_workshop_exists(workshop_record_id: str) -> None:
    """Refuse a ``workshopId`` naming no ``Workshop`` row, rather than letting Prisma 500 on it.

    ``DesignWorkshop.workshopId`` is a real foreign key, so an id that names nothing is a constraint
    violation and a bare 500 — an answer no form can render and one indistinguishable from the
    server being down. The create route carries the same hazard and is deliberately not changed
    here; this is the surface a re-pointing control drives, and the one where the value comes out of
    a picker whose list may be a fortnight stale on a handset.

    WHAT THIS DOES NOT DO is warn about the cost of re-pointing a workshop that already has records
    filed under it. Five registry REF fields are narrowed server-side by the linked workshop, so a
    record created from one of those pickers and then filed against a different sitting is a record
    that picker can never show again. That is a sentence for the screen beside the control, not a
    refusal: moving a workshop onto the right link is a legitimate correction, and often the reason
    somebody opened the edit form at all.
    """
    if await db.workshop.find_unique(where={"id": workshop_record_id}) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "No workshop record exists with that id, so this design workshop cannot be linked "
                "to it. Choose one from the list, or send null to remove the link."
            ),
        )


# --------------------------------------------------------------------------------------
# The registry
# --------------------------------------------------------------------------------------


#: What a client may assume about a registry it already holds: **nothing, without asking**.
#:
#: `max-age=0, must-revalidate` is not timidity, it is the only honest number available. The registry
#: changes when the server is deployed and at no other moment, and there is no signal a deployment
#: emits that a handset in a village could consult — so any positive lifetime is a window in which a
#: phone renders a form the server will no longer accept, silently dropping the keys it does not know
#: (`droppedKeys` on the save response is what a designer would eventually see, if they looked). This
#: repository has already paid for a stale registry once: `registry_version`'s docstring records a
#: bundled Android asset that carried three fewer derived fields than the server and reported
#: agreement anyway.
#:
#: What conditional GET buys is therefore NOT the round trip — that still happens on every cold start
#: — it is the bytes the round trip used to carry. MEASURED end to end through the real middleware
#: stack on 2026-08-22: the 200 is 23,618 bytes on the wire (149,465 of JSON, gzipped to 22,875 by
#: `SelectiveGZipMiddleware`, plus headers) and the 304 is 664, of which 382 is the Permissions-Policy
#: and CSP block `SecurityHeadersMiddleware` puts on every response. **35.6x**, and on the 40 kB/s
#: link §1 of docs/SCALABILITY.md sizes this deployment against, 0.59 s down to 0.017 s per client
#: per cold start.
#:
#: NOTE FOR ANYONE COMPARING THIS AGAINST THE 118 KB IN THE SCALE AUDIT: that figure is the
#: UNCOMPRESSED payload, and no client has received an uncompressed one since `SelectiveGZipMiddleware`
#: landed. The saving is real and it is 22.9 KB, not 118.
#:
#: `private` because the route is behind `get_current_user`. The body does not vary per caller — that
#: is what makes the ETag well-defined at all — but a shared cache holding an authenticated response
#: is a habit worth not starting.
_SCHEMA_CACHE_CONTROL = "private, max-age=0, must-revalidate"


def _etag_opaque(tag: str) -> str:
    """One entity-tag stripped to the part a comparison may look at.

    The weakness prefix is dropped because ``If-None-Match`` is defined to use the WEAK comparison
    function (RFC 9110 §13.1.2) — ``W/"x"`` and ``"x"`` match — and this endpoint issues a weak tag
    anyway.
    """
    tag = tag.strip()
    return tag[2:] if tag[:2] in ("W/", "w/") else tag


def _if_none_match_matches(header: str, etag: str) -> bool:
    """Does an ``If-None-Match`` header value name ``etag``?

    A DELIBERATELY NAIVE SPLIT ON COMMAS, and the direction of its error is the reason it is
    acceptable. A conforming entity-tag may not contain a comma, but a malformed one sent by some
    client we have never seen could — and that tag would then fail to match, so the caller receives
    the full 200 they would have received before this function existed. Every way this parser can
    MISREAD A TAG costs bytes and nothing else.

    ``*`` IS THE ONE INPUT THAT CAN BE ANSWERED WITH A STALE REPRESENTATION, and it is not a parse
    error — it is the client asserting that any stored response it holds will do (RFC 9110 §13.1.2).
    A caller sending it keeps whatever it has indefinitely, because ``max-age=0, must-revalidate``
    makes it revalidate every time and this branch answers 304 every time without comparing
    anything. That is the defined meaning of the header, and no client in this repository sends it —
    so the honest statement of the guarantee is that a MANGLED TAG can only cost bytes, not that no
    input at all can be met with a stale body. ``test_a_star_matches_anything`` pins the branch.
    """
    tags = [t for t in (part.strip() for part in header.split(",")) if t]
    if not tags:
        return False
    if "*" in tags:
        return True
    wanted = _etag_opaque(etag)
    return any(_etag_opaque(t) == wanted for t in tags)


@router.get("/schema")
async def get_stage_schema(request: Request, _: Any = Depends(get_current_user)) -> Response:
    """The field registry every client renders its forms from.

    Served rather than duplicated, for the same reason ``/reference/address`` is: a field list
    that lives in three codebases is three field lists, and they drift. Whatever a form offers
    is by construction exactly what this API accepts and exactly what the report prints.

    A pure constant — no database read — and the largest body this API serves TO A COLD CLIENT (the
    qualifier matters: ``SelectiveGZipMiddleware``'s own docstring records an 839 KB report preview,
    which is larger and is not something every client fetches once per start): **162,717 bytes of
    JSON, 25,112 after the gzip middleware, 25,855 on the wire with headers** (MEASURED 2026-08-28;
    ``docs/SCALABILITY.md`` §9.1 has the command). Every cold start of every client pays for it, so
    it answers a conditional GET, which brings a revalidation down to a 664-byte 304 — **38.9x**.

    **READ THOSE AS A DATED FLOOR AND NOT AS THE SIZE OF THIS PAYLOAD.** The line above said
    149,465 / 22,875 / 23,618 and cited 2026-08-22, and six days later it was 8.9% short, because
    the registry gains fields continuously and never loses them. The drift is not even monthly: the
    same command run twice during the session that re-measured it returned 162,178 and then 162,717,
    with ``services/stage_definitions.py`` and ``services/stage_schema.py`` both carrying
    modification times inside that session — another workstream was writing the registry while it
    was being measured. Nothing in the
    argument depends on the exact figure — what the endpoint rests on is the RATIO, which has moved
    from 35.6x to 38.9x in the direction that makes it more worth having, and the only number this
    repository actually enforces is the order-of-magnitude band in
    ``tests/test_schema_conditional_get.py::test_the_measured_sizes_are_still_in_the_range_the_docs_claim``.
    Re-run §9.1's command before you quote a byte count, and date what you write.

    **THE VALIDATOR IS A DIGEST OF THE RESPONSE BODY, NOT ``registry_version()``, AND THAT IS THE
    WHOLE CORRECTNESS ARGUMENT.** Binding the ETag to the version digest is the obvious move — it
    already exists, it is already published inside the payload, and it is already the thing clients
    compare. It is also wrong, because that digest deliberately covers LESS than this body carries.
    ``registry_version`` hashes key, type, tier, required, enum name, deprecation, derivation and
    hydration, and it says in its own docstring that it is "deliberately insensitive to labels and
    help text" so that retitling a field does not invalidate every cached draft on every phone. Seven
    kinds of change were tried against the live registry on 2026-08-22 and each moved the body while
    leaving the version character-for-character identical: a field label, a field's help text, a
    stage title, an ENUM option's label, ``columnWidthPct``, ``maxLength`` and ``minValue``. Bind the
    ETag to the version and a client that has revalidated once holds all seven wrong for ever — a
    handset showing an option labelled with the wording a ministry asked us to correct, with the
    server reporting agreement. So the tag is ``sha256`` of the exact bytes below, which cannot
    disagree with them. ``tests/test_schema_conditional_get.py`` reruns all seven and asserts BOTH
    halves: the tag moved, and the version did not.

    **WEAK, not strong.** ``SelectiveGZipMiddleware`` may re-encode these bytes on the way out, so
    one validator ends up describing two content-codings. That is precisely what a weak tag declares
    and what a strong one would misstate (RFC 9110 §8.8.1). Nothing here serves a Range, which is the
    only thing weakness costs.

    Deliberately NOT memoised. Building and hashing the payload costs 6.05 ms + 0.67 ms (MEASURED,
    same run), on a route hit once per cold start and never in a loop; a module-level cache of a
    constant that only tests ever mutate is a stale-schema trap for a saving that small.
    """
    payload = JSONResponse(registry_to_dict())
    etag = f'W/"{hashlib.sha256(payload.body).hexdigest()[:32]}"'
    headers = {"ETag": etag, "Cache-Control": _SCHEMA_CACHE_CONTROL}

    if _if_none_match_matches(request.headers.get("if-none-match", ""), etag):
        # RFC 9110 §15.4.5: a 304 carries the header fields a 200 would have sent that are needed to
        # keep the stored response usable, and no body. ETag, Cache-Control and Vary all qualify —
        # without the Cache-Control the client's next revalidation would be governed by a heuristic
        # instead of by this endpoint's own answer.
        #
        # VARY IS SET HERE AND NOT ON THE 200, WHICH LOOKS LIKE AN OVERSIGHT AND IS NOT. The 200
        # receives it from `SelectiveGZipMiddleware`, which appends `Vary: Accept-Encoding` at the
        # moment it compresses; setting it here as well would emit the header twice on every
        # compressed response, for a list whose duplicate entry means nothing. The 304 never reaches
        # that branch — the middleware passes 204 and 304 straight through, precisely because they
        # carry no body — so this is the only place it can be added to a 304 at all.
        #
        # "AT THE MOMENT IT COMPRESSES" IS A CONDITION AND NOT A FIGURE OF SPEECH. The middleware
        # returns before it captures anything when the request does not offer gzip, and appends
        # `vary` only inside the compression branch, which a body under `minimum_size` also skips —
        # so `Accept-Encoding: identity` gets a 200 with ETag, Cache-Control and NO Vary at all
        # (MEASURED through `create_app()`, and
        # `test_the_200_carries_no_vary_when_the_client_refuses_gzip` pins it). Harmless as
        # deployed: both clients send gzip, and `private` keeps this body out of a shared cache
        # regardless. Named here because the alternative — setting Vary on the 200 too — trades a
        # duplicated header on every compressed response for one that is always present, and that
        # is a trade to make deliberately rather than to stumble into on the belief that the 200
        # already had it.
        return Response(
            status_code=status.HTTP_304_NOT_MODIFIED,
            headers={**headers, "Vary": "Accept-Encoding"},
        )

    for name, value in headers.items():
        payload.headers[name] = value
    return payload


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
    retention: str = Form(RETENTION_DISCARD),
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

    ── ``retention``, AND WHY IT IS IN THE ANSWER RATHER THAN ONLY IN A COMMENT ────────────────

    The bytes this route receives are never stored, and that has always been true. What was not true
    is that a CLIENT could know it: the fact lived in the comment at the bottom of this function, so
    the panel a designer looks at could only ever *assert* that the photograph was not kept, on the
    word of whoever wrote the panel. ``photograph`` in the response is that same fact as data —
    ``stored: false`` on every single reply from this route, without exception and with no branch
    that can produce anything else — so a screen saying "this photograph was not kept" is reading it
    rather than claiming it, and a client that ever stops seeing it has a server that changed.

    ``retention`` itself is the designer's DECLARED intention for the picture, echoed back. It
    changes nothing about what this route does, and that is the point of accepting it: a caller that
    sends ``store`` is telling the server what it is about to do with its own copy through
    ``/media/complete``, and the reply tells it plainly that this route did not do it. The decision
    is only real once it lands on a stored row, which is ``decide_identity_photograph`` below.
    Anything unrecognised — including a client that sends nothing, which is every build shipped
    before this field existed — resolves to DISCARD in ``parse_retention``; see its docstring for why
    an unparseable answer must be the same as no answer rather than a 422.
    """
    _require_designer(current_user)
    declared = parse_retention(retention)
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
    payload = result.payload()
    payload["photograph"] = {
        # NOT COMPUTED FROM ANYTHING. There is no code path in this function that stores the image,
        # so there is no expression here that could evaluate to True — it is a literal precisely so
        # that adding a storage path would have to come here and change it, in the response the
        # clients read, rather than quietly making the comment above obsolete.
        "stored": False,
        "retention": declared,
        # Named so a client does not have to know the route from a document. This is where a
        # photograph that WAS uploaded through the media flow gets its decision recorded.
        "decisionRoute": "/design-workshops/ocr/identity/retention",
    }
    return payload


@router.post("/ocr/identity/retention")
async def decide_identity_photograph(
    mediaId: str = Body(..., embed=True),
    decision: str = Body(RETENTION_DISCARD, embed=True),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Keep this identity-card photograph, or delete it. The designer's answer, not the app's.

    ── THE DEFECT THIS ROUTE EXISTS TO CLOSE ─────────────────────────────────────────────────────

    ``scan_identity_card`` above stores nothing, and its own docstring is where that promise has
    always been written. But the design workshop's stage form does not reach it with a loose file:
    ``IdentityCardReader`` is rendered UNDER a media field, it reads the number off photographs the
    designer has already attached there, and attaching there means the ordinary media flow has
    already run — presigned PUT to S3, a ``MediaFile`` row from ``/media/complete``, an entry in the
    workshop's media list. By the time anybody is offered the chance to think about an unmasked
    identity document sitting in the repository, it has been sitting in the repository for a minute.
    Nobody chose that. It is what the form does.

    So this route is the choice, offered after the fact because after the fact is where the
    photograph already is, and it is a REAL choice in both directions:

    * ``DISCARD`` deletes the S3 object and then deletes the row. Not ``deletedAt``, not a flag, not
      a filter. ``design_workshops.py``'s own header opens with **"Nothing here hard-deletes"** and
      gives the reason — this is a research data set and a designer's fortnight of fieldwork must
      survive a mis-tap. That reasoning is sound for a workshop and is precisely inverted for this:
      a soft-deleted photograph of somebody's Aadhaar card is a retained photograph of somebody's
      Aadhaar card, and the person who pressed "delete this" would be entitled to believe otherwise.
      This is the one route in this file that means it.
    * ``STORE`` writes the decision onto the file — who, by what name, and when — so a retained
      identity document in this repository can always be traced to the person who decided it should
      be. Without the stamp the row is indistinguishable from every other photograph, which is how
      it got here.

    ── ORDER OF DELETION, AND WHY THE OBJECT GOES FIRST ──────────────────────────────────────────

    ``media.delete_media`` deletes the row and then makes a BEST-EFFORT attempt at the object,
    swallowing storage failures because "the database row (the user-visible record) is gone". That
    is a defensible trade for a photograph of a loom. It is the wrong one here: it can end with the
    JPEG still in the bucket and nothing left in the database that knows it is there, which is the
    exact definition of merely hiding. So this route deletes the OBJECT first and refuses the whole
    request if that fails — the row survives, it still points at the bytes, and the designer can
    press the button again. The other ordering fails silently in the direction that keeps the data;
    this one fails loudly in the direction that keeps the record of it.

    ── WHO MAY ──────────────────────────────────────────────────────────────────────────────────

    ``_require_designer`` (the SET {Designer, Admin, Master Admin}) exactly as the read route above,
    plus uploader-or-admin on the row itself, which is ``media.delete_media``'s rule. Both, not
    either: the designer set is who is offered the card reader at all, and the uploader check is
    what stops one designer deleting another's attachment off a shared workshop.
    """
    _require_designer(current_user)
    choice = parse_retention(decision)
    media = await db.mediafile.find_unique(where={"id": mediaId})
    if media is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That photograph is no longer here — it may already have been deleted. Nothing was "
                "changed. Reload the stage to see what is still attached."
            ),
        )
    if not is_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This photograph was attached by somebody else, so only they or an admin can decide "
                "whether it is kept. Ask them, or ask an admin to remove it."
            ),
        )
    # An identity decision is a decision about a PICTURE. Refusing anything else is not pedantry:
    # this is the one route in the file that hard-deletes, and a client bug that sent an audio id
    # would destroy an interview recording with no soft-delete to recover it from.
    if getattr(media, "mediaType", None) != "IMAGE":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "Only a photograph can be kept or deleted here, and that attachment is not one. "
                "Remove it from the field instead if it does not belong."
            ),
        )

    if choice == RETENTION_STORE:
        stamp = retention_stamp(choice, user=current_user, at=datetime.now(UTC))
        await db.mediafile.update(
            where={"id": mediaId},
            data={"extraMetadata": with_retention(getattr(media, "extraMetadata", None), stamp)},
        )
        return {
            "mediaId": mediaId,
            "decision": RETENTION_STORE,
            "deleted": False,
            "retention": stamp,
        }

    object_key = getattr(media, "objectKey", None)
    if object_key:
        # The same guard ``delete_media`` applies, minus this row: one object can legitimately back
        # several MediaFile rows (the same photograph attached to two stages), and deleting the bytes
        # out from under the other row would break an attachment nobody decided anything about.
        shared = await db.mediafile.find_first(
            where={"objectKey": object_key, "id": {"not": mediaId}}
        )
        if shared is None:
            try:
                await asyncio.to_thread(delete_object, object_key)
            except Exception as exc:  # noqa: BLE001 - the storage message is not for the caller
                # Deliberately NOT swallowed — see the docstring. Nothing has been deleted at this
                # point, so the sentence can honestly say the photograph is still there.
                logger.warning(
                    "Identity photograph object could not be deleted (%s)", type(exc).__name__
                )
                raise HTTPException(
                    status_code=status.HTTP_502_BAD_GATEWAY,
                    detail=(
                        "The photograph could not be deleted from storage, so nothing was removed "
                        "and it is still attached. Try again in a moment; if it keeps failing, tell "
                        "an admin that this file needs deleting by hand."
                    ),
                ) from exc
    await db.mediafile.delete(where={"id": mediaId})
    return {
        "mediaId": mediaId,
        "decision": RETENTION_DISCARD,
        # Both halves, stated separately, because "deleted" alone would be true of a soft delete too.
        "deleted": True,
        "objectDeleted": bool(object_key),
        "retention": None,
    }


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
        # Live dictation: the person at the microphone is the person asking, so their own key
        # runs it when they have supplied one.
        user_id=current_user.id,
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
    return dictation_cap.allowance_payload(await dictation_cap.load_allowance(current_user.id))


@router.get("/ai-verb-allowance")
async def get_ai_verb_allowance(
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """How many AI-verb runs this designer has left today, and where the day ends.

    **THE ONE BACKEND CHANGE THE AI-VERB CLIENTS WERE WRITTEN AROUND, AND BOTH SAID SO.** The five
    verb routes return ``allowance_payload`` on their 201, and ``ai_verb_cap.allowance_payload``
    argues in its own docstring that this is not enough — *"a client that can learn the ceiling only
    by being refused has to spend a run to learn it"* — and a run is a provider call somebody pays
    for. The web's ``dwAiVerbAllowance`` has been calling this path since the verbs shipped, with a
    comment recording in capitals that it DOES NOT EXIST YET and that every deployment therefore
    answers 404; the Android data layer reached the same conclusion independently and wrote the same
    caveat. Both degrade honestly — no countdown, nothing disabled on a ceiling nobody can see — and
    both were waiting for this.

    IT IS THE SIBLING OF ``/dictation-allowance`` AND IS DELIBERATELY IDENTICAL IN SHAPE: two
    primary-key reads, no provider call, nothing spent. That symmetry is the point, because the two
    caps are the same idea over different verbs and a client that reads one should not have to learn
    a second protocol to read the other.

    IT ANSWERS FOR THE CALLER AND FOR NOBODY ELSE. There is no ``userId`` parameter and there must
    not be one: the allowance is a fact about the signed-in account, and a route that could be asked
    about somebody else's spend would be a report on a colleague's working day.

    ``aiVerbDay`` is the load-bearing key — the SERVER's India-time date — so a phone can tell a
    cached spend that is still true from one that belongs to yesterday, and a mirror whose day no
    longer matches resolves to *not spent* rather than silently withholding a capability at the
    wrong midnight.

    DECLARED ABOVE ``GET /{workshop_id}``, with the rest of the literals, and that placement is not
    cosmetic: the banner above ``/ocr/identity`` records what happened when a literal GET was
    missing here — ``{workshop_id}`` matches ``[^/]+``, so the request full-matched the by-id route,
    looked up a workshop whose id was the literal word, answered 404, and the browser read that as
    "this deployment does not offer the feature" and drew no control at all.

    Gated on ``_require_designer`` and nothing else, matching both the verb routes it serves and the
    dictation allowance beside it: no workshop is involved and the number is this account's own.
    """
    _require_designer(current_user)
    return ai_verb_cap.allowance_payload(await ai_verb_cap.load_allowance(current_user.id))


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


async def _attach_deleted_by(rows: list[Any], items: list[dict[str, Any]]) -> None:
    """Put ``deletedById`` and ``deletedByName`` on rows that carry a deletion, in ONE query.

    HERE AND NOT IN ``workshop_summary``, which serves every list and every single read: the pointer
    is null on every live workshop, so carrying it there would add a key that is null on all but the
    one listing that can show a deleted row, and resolving the NAME there would be an account lookup
    per row on the paged list every designer loads. This is the same rule ``consent_keys`` follows in
    the other direction — see ``dictationConsentByName``, which the single read adds for exactly this
    reason. One ``find_many`` covers the whole page.

    A NAME CAN BE ABSENT WITH THE ID PRESENT and that is not an error: ``deletedById`` is
    ``onDelete: SetNull`` against ``User``, so an account closed since the deletion leaves the
    pointer behind — but the row it pointed at may also simply have been renamed away. Clients must
    render "an account no longer on record" rather than guessing at the workshop's creator; the same
    decision, argued at length, is in ``entry_provenance.resolve_display_names``.
    """
    wanted = {actor for r in rows if (actor := getattr(r, "deletedById", None))}
    names: dict[str, Any] = {}
    if wanted:
        accounts = await db.user.find_many(where={"id": {"in": sorted(wanted)}})
        names = {account.id: getattr(account, "name", None) for account in accounts}
    for row, item in zip(rows, items, strict=True):
        actor = getattr(row, "deletedById", None)
        item["deletedById"] = actor
        item["deletedByName"] = names.get(actor) if actor else None


@router.get("")
async def list_design_workshops(
    page: int = Query(1, ge=1),
    # REFUSED PAST 100, NOT CLAMPED TO IT — matching ``workshops.py``'s own list route (``:229``)
    # rather than inventing a second ceiling for the same shape of endpoint. Before this bound,
    # ``pageSize=5000`` was accepted by FastAPI and only clamped deep inside ``normalize_pagination``,
    # so a caller asking for one giant page silently got a 100-row page instead — the same number of
    # rows every time, however large the ask, with nothing on the wire saying the request was not
    # honoured. A 422 here is the caller finding that out from the response instead of from a
    # ``pages`` count that no longer matches the ``pageSize`` it thinks it sent.
    pageSize: int = Query(20, ge=1, le=100),
    search: str | None = None,
    statusFilter: str | None = None,
    craftName: str | None = None,
    state: str | None = None,
    mineOnly: bool = False,
    includeDeleted: bool = False,
    deletedOnly: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """List workshops, newest first — newest DELETED first under ``deletedOnly``.

    The filters read the promoted columns rather than the JSON, which is the entire reason
    those columns exist — see the note above ``DesignWorkshop`` in schema.prisma.

    ``deletedOnly`` IS THE TRASH, AND IT IS THE HALF THAT MAKES THE SOFT DELETE REAL. ``DELETE``
    sets ``deletedAt`` and ``POST /{id}/restore`` clears it, and between the two there was no
    endpoint on this API that would name a deleted row: every read filtered ``deletedAt: null``, so
    an admin could only restore a workshop whose id they already had written down. A safety net
    nobody can find is not a safety net, and the web's delete confirmation promises one.

    ADMIN ONLY, AND THE SAME GATE AS THE RESTORE IT EXISTS TO FEED — ``is_admin``, refused with the
    sentence ``require_admin`` uses. Anything looser would widen a read: a deleted workshop is
    invisible to its own creator today (``load_workshop_or_404`` answers 404 to a non-admin READING a
    deleted row, and 409 "Restore it before editing" to anyone at all who asked to EDIT one), so
    letting a designer list the trash would show them rows the single read then denies. The two
    flags are refused rather than ignored, because a client that asked for the trash and silently
    got the live list would render an ordinary workshop under a "Deleted" heading with a Restore
    button beside it.

    ``deletedOnly`` WINS OVER ``includeDeleted`` when both are sent — it is the narrower of the two
    and a caller that asked for both meant the specific one.
    """
    if (includeDeleted or deletedOnly) and not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    # THREE STATES, NOT A BOOLEAN. The default filters deleted rows out, `deletedOnly` filters
    # everything else out, and `includeDeleted` drops the key entirely so the two sets arrive
    # interleaved by `createdAt` — which is the only one of the three that a client must be able to
    # tell apart per row, and it can: `deletedAt` is on every summary.
    where: dict[str, Any] = {}
    if deletedOnly:
        where["deletedAt"] = {"not": None}
    elif not includeDeleted:
        where["deletedAt"] = None
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
    # NEWEST DELETED FIRST IN THE TRASH, newest created everywhere else. The row an admin is looking
    # for in a trash view is almost always the one they just deleted by mistake, and a workshop
    # opened in March and deleted this morning sorts to the bottom of a `createdAt` ordering — under
    # rows nobody is looking for, on a page they may never reach.
    order = {"deletedAt": "desc"} if deletedOnly else {"createdAt": "desc"}
    total, rows = await asyncio.gather(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            # TOTAL ORDER, NOT JUST A SORT — neither ``createdAt`` nor ``deletedAt`` is unique, and
            # ``LIMIT/OFFSET`` re-runs the whole sort from scratch on every page, so Postgres is free
            # to break a tie between two rows differently on the request for page 2 than it did on
            # page 1. A row that changes side of the cut between those two requests is handed over
            # TWICE (sent on page 1, slides back above the cut on page 2) or NEVER (the mirror case,
            # sliding below the cut both times) — and either way the response looks perfectly healthy:
            # the count matches, the page is full, there is no gap to notice. The only way anyone
            # finds out is by going looking for a workshop they know exists and not finding it on any
            # page of the walk. This read bypasses ``count_and_page`` — it also needs the raw ``rows``
            # for ``_attach_deleted_by``, which that helper has nowhere to hand back — so it appends
            # the tiebreak itself, exactly as ``access.py`` and ``designers.py`` already do for their
            # own hand-rolled ``asyncio.gather`` reads. See ``records.with_id_tiebreak`` for the full
            # argument, including why the ties here are not a theoretical risk.
            order=with_id_tiebreak(order),
        ),
    )
    items = [workshop_summary(r) for r in rows]
    if includeDeleted or deletedOnly:
        await _attach_deleted_by(rows, items)
    return page_payload(items, total, clean_page, clean_size)


@router.get("/default-for-me")
async def default_design_workshop_for_me(
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The design workshop this account was most recently given access to — the smart default.

    ONE ENDPOINT BECAUSE THERE IS ONE QUESTION, asked from six or seven places. The owner's
    instruction of 2026-08-28 has two halves and they are the same query:

    * *"When a designer selects Start a new workshop, provide a dropdown containing the workshops
      that the designer is already part of or has been given access to. By default, select the
      Design and Prototype Workshop that the designer was most recently given access to."*
    * *"Whenever a designer goes to create/record any particular record type, the most recently
      allocated Design and Prototype Workshop should be populated by default."*

    Two clients times seven record forms is fourteen places that would otherwise each decide what
    "most recently allocated" means, and they would not agree — which is the failure
    ``workshopOccurrenceDate``'s comment in the web client already records for the ORDINARY workshop
    default ("getting 'which workshop is most recent' wrong picks the wrong default silently"). So
    the server answers it once and both clients read the answer.

    ── WHAT "MOST RECENTLY GIVEN ACCESS TO" MEANS, EXACTLY ──────────────────────────────────────

    Access to a design workshop arrives by exactly two doors and this reads both, taking whichever
    is later:

    * **A grant.** ``DesignWorkshopViewer.createdAt`` is the moment an admin ticked the box, or the
      moment a join card was redeemed — ``design_workshop_grants._write_the_viewer_row`` sets it in
      the same statement that creates the row. This is the door the owner's phrase names: a workshop
      "created by the Ministry and allocated to particular designers".
    * **Authorship.** A workshop this account created is one it has had access to since
      ``DesignWorkshop.createdAt``. Reading only grants would leave a designer who opened their own
      workshop this morning defaulted to somebody else's from last month.

    ``has_viewer_grant`` reads the EXISTENCE of a viewer row and nothing on it, so ordering by
    ``createdAt`` here adds no new meaning to that column — it reads a timestamp the row already
    keeps, and no access decision is made from it.

    ── IT IS A SUGGESTION AND NEVER A SCOPE ─────────────────────────────────────────────────────

    The answer is a DEFAULT for a dropdown. Nothing may gate on it: the caller still picks, the
    picker still lists everything ``visible_to_clause`` admits, and every write is still checked by
    ``load_workshop_or_404``. A client that treated this as "the workshop I am allowed to use" would
    be inventing a scope the API does not have.

    ── A SOFT-DELETED WORKSHOP IS NEVER THE ANSWER ─────────────────────────────────────────────

    ``deletedAt: null`` on both branches. A deleted workshop is invisible to its own creator
    (``load_workshop_or_404`` answers 404 to a non-admin reading one), so defaulting a form to it
    would populate a dropdown with a row the very next request denies.

    ── AND "NONE" IS AN ANSWER, NOT AN ERROR ───────────────────────────────────────────────────

    A newly onboarded designer is on no workshop, which is ordinary. This returns
    ``{"workshopId": None, ...}`` with a 200 rather than a 404, because the callers are dropdowns
    filling in a default: a 404 would arrive at seven forms as a failure to report, and every one of
    them would have to learn that this particular failure means "nothing to prefill", which is how
    an empty answer comes to be drawn as a broken screen.
    """
    grant, own = await asyncio.gather(
        db.designworkshopviewer.find_first(
            where={"userId": current_user.id, "designWorkshop": {"is": {"deletedAt": None}}},
            order={"createdAt": "desc"},
            include={"designWorkshop": True},
        ),
        db.designworkshop.find_first(
            where={"createdById": current_user.id, "deletedAt": None},
            order={"createdAt": "desc"},
        ),
    )

    # (when access began, the workshop row, which door it came through) for whichever exists.
    candidates: list[tuple[datetime, Any, str]] = []
    if grant is not None and getattr(grant, "designWorkshop", None) is not None:
        candidates.append((grant.createdAt, grant.designWorkshop, "GRANTED"))
    if own is not None:
        candidates.append((own.createdAt, own, "CREATED"))
    if not candidates:
        # ANSWERED, AND THE ANSWER IS NONE. `reason` is null rather than a word, so a client cannot
        # print "granted" over an empty dropdown.
        return {"workshopId": None, "title": None, "accessAt": None, "reason": None}

    accessed_at, workshop, reason = max(candidates, key=lambda row: row[0])
    return {
        "workshopId": workshop.id,
        "title": workshop.title,
        # ISO on the wire, as every other timestamp this API publishes is. The clients show it as
        # "you were added on …" beside the prefilled row so the default is legible rather than
        # mysterious — a dropdown that fills itself in and cannot say why reads as a bug.
        "accessAt": accessed_at.isoformat() if accessed_at is not None else None,
        # WHICH DOOR, because the two need different sentences: "the workshop you opened most
        # recently" and "the workshop you were most recently added to" are different facts, and a
        # designer who is told the wrong one goes looking for an allocation that never happened.
        "reason": reason,
    }


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_design_workshop(
    payload: DesignWorkshopCreate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Start a workshop — ADMINS AND THE MASTER ADMIN ONLY.

    Only the title is required. A workshop is created in a room on day one, before the sanction
    order number is to hand; the Basic-tier fields of stage 1 are enforced when a report is
    generated, not here.

    ``designerUserIds`` NAMES EVERY DESIGNER THE WORKSHOP IS FOR, and each of them gets a
    ``DesignWorkshopViewer`` row in this same call. That row is what makes the workshop visible to
    them and — because the list query and the single read both consult that one table — what keeps
    it invisible to every other designer. Admins and the master admin see all of them; a designer
    who is not on this list is answered 404 "Record not found", byte-identical to an id that does
    not exist, on both the list and the read.

    ``designerUserId`` NAMES THE LEAD and is UNCHANGED in meaning. It is the one field here that
    changes what the finished report SAYS rather than what it is filed under: their
    ``DesignerProfile`` is what gets copied into stage 1 and stage 3, their name reaches the cover,
    the certification signature and the .docx's own ``dc:creator``. A body that sends only
    ``designerUserIds`` makes the first of them the lead; a body that sends only ``designerUserId``
    behaves exactly as it did before the plural field existed, which is what an APK a fortnight
    behind sends. Omitting both is legal and leaves the behaviour older still — the CREATOR's
    profile is copied, which for an admin opening a workshop on somebody else's behalf is the wrong
    person's name on a ministry document. See ``seed_designer_prefill`` for the whole argument and
    for why there is no fallback between the two.
    """
    # ONE GATE, AND IT IS NARROWER THAN THE REST OF THIS FILE. Read this before widening it back.
    #
    # WHAT IT USED TO BE. `assert_can_create_records` (Researcher and above — the repository-wide
    # rule for making any record) followed by the designer gate every other write route in this
    # module still calls (`can_run_design_workshops`, the set {DESIGNER, ADMIN, MASTER_ADMIN}). The
    # first of those was already implied by the second and both are implied by the line below, so
    # nothing that could get in through them can be refused by it — dropping them loses no check,
    # and keeping a predicate that can never fire is how a rule comes back: it reads as
    # authoritative to whoever finds it first and nothing fails when it drifts.
    #
    # (Neither name is spelled as a CALL anywhere in this function, deliberately:
    # `tests/test_design_workshop_gate.py` reads this route's source to prove the wider gate is off
    # it, and a call written inside a comment would read to that test as a call.)
    #
    # WHY IT MOVED. A design workshop is not a record, it is the CONTAINER a fortnight of records
    # lives in, and it is the unit the ministry indexes and funds. Opening one is an administrative
    # act — the admin holding the sanction order is the person who knows the workshop exists — so
    # the capability is `can_create_design_workshops` and NOT the one that decides who may work
    # inside it. Everything else on this surface still asks the designer gate: PATCH, every stage
    # write, the capture aids, the report. A designer therefore loses exactly one thing, and it is
    # the one the requirement named.
    #
    # ENFORCED HERE AND NOT ONLY IN THE BROWSER, for the reason this whole module already carries a
    # gate at all: a UI guard over an open route hides the link and leaves the URL, the API and the
    # Android client. The web ALSO enforces it, in two places — the create control and the offline
    # draft store (`frontend/lib/designWorkshopStore.ts`) — because a designer who only found out at
    # sync time would have already filled 22 stages into a workshop that can never be accepted.
    # Those are for the designer's benefit; THIS is the one that is load-bearing.
    assert_can_create_design_workshops(current_user)
    # ── WHO THIS WORKSHOP IS FOR, DECIDED BEFORE A SINGLE ROW EXISTS ────────────────────────────
    #
    # TWO FIELDS, TWO QUESTIONS, ONE HELPER. `named_designer_team` reads the body's `designerUserId`
    # (the LEAD, whose profile is seeded and whose name reaches the report) and `designerUserIds`
    # (everybody who gets a viewer row) into exactly those two answers, and it is a PURE function so
    # that every branch of it — only the singular field, only the plural one, both, neither, blanks,
    # duplicates, a lead who is not in the list — is pinned without a database. Blanks are treated
    # as absent there: a client that sends `""` — an empty picker, a cleared field, an offline draft
    # that carried the key but never got an answer — means "nobody named", not "an account whose id
    # is the empty string", which would 422 the create with "No account exists with this id: " and
    # name nothing, on a form where the field is optional.
    #
    # AND THE ELIGIBILITY QUESTION IS ASKED HERE, ABOVE THE CREATE, NOT AFTER IT. Naming somebody
    # who may not hold a viewer row (a PROFESSOR, a designer whose empanelment has lapsed, an
    # account the platform allow-list has suspended) has to refuse the WHOLE call. Asked after the
    # `db.designworkshop.create` below, the same 422 would leave a committed, untitled-looking
    # orphan draft behind on every retry — the failure the seed's blanket `except` exists to prevent,
    # reached from the other end, and this route has no equivalent guard because a create that
    # cannot honour the body it was given must not half-succeed.
    #
    # ONE CALL FOR THE WHOLE SET, NEVER ONE PER ID. The rule refuses the whole set and names every
    # account it objected to; asked in a loop it would raise on the first and say nothing about the
    # second, so an admin who ticked four designers and is told about one has been sent on the first
    # of two trips — the exact round trip those refusals are worded to save.
    #
    # AND THE CREATOR IS SUBTRACTED FROM THAT SET, exactly as `_deduplicate` subtracts them on the
    # viewers PUT and for the reason stated there: "their standing is simply not this list's
    # business". No viewer row is ever written for them — their access comes from `createdById`, and
    # `attach_the_named_designer` refuses to mint a second, redundant source of truth for it — so
    # validating them can only produce a refusal about a row that was never going to exist. It is a
    # reachable refusal, not a theoretical one: the ordinary door does NOT re-read the platform
    # allow-list (only the dataset door does), so an admin suspended after their token was minted
    # still reaches this route, and ticking their own name in the picker would 422 their own create.
    # The two writes land in one table and must not disagree about who is in the set.
    designer_id, designer_ids = named_designer_team(payload.designerUserId, payload.designerUserIds)
    wanted = set(designer_ids) - {current_user.id}
    if wanted:
        await assert_every_designer_may_be_named(wanted)
    data: dict[str, Any] = {
        "title": payload.title.strip(),
        "templateId": payload.templateId,
        "createdById": current_user.id,
        "schemaVersion": registry_version(),
        "status": "DRAFT",
    }
    # THE COLUMN LIST IS SHARED WITH THE EDIT AND THE SEMANTICS ARE NOT; see
    # `_CREATE_OPTIONAL_COLUMNS`. Nothing about this loop changes: a falsy value is still dropped
    # (a blank box on a create means "not known yet", and there is no stored value it could
    # overwrite) and the value is still copied unstripped. What is no longer possible is the two
    # routes disagreeing about WHICH columns the header consists of, which is how `notes`,
    # `templateId` and `workshopId` came to be creatable and, in the web client, uneditable for ever.
    for key in _CREATE_OPTIONAL_COLUMNS:
        value = getattr(payload, key)
        if value:
            data[key] = value
    for key in ("startDate", "endDate"):
        parsed = _parse_date(getattr(payload, key))
        if parsed:
            data[key] = parsed

    record = await db.designworkshop.create(data=data)
    # NAMING A DESIGNER PUTS THEM ON THE WORKSHOP, AND THAT IS ONE ACT RATHER THAN TWO. Without it
    # an admin creates the workshop and then has to remember to open the viewers panel and tick the
    # same people they have just named; forgetting leaves a designer who cannot open the workshop
    # whose stage 1 already carries their name, and the only symptom is a 404 indistinguishable from
    # a workshop that does not exist. Eligibility was settled above the create, so this cannot be
    # the call that refuses. The creator naming THEMSELVES writes no row — their access comes from
    # `createdById` — and `attach_the_named_designers` carries that rule and the reason for it.
    #
    # THIS IS THE WHOLE OF THE VISIBILITY CHANGE. The list query and the single read already scope
    # on `DesignWorkshopViewer` (`visible_to_clause` and `load_workshop_or_404`), so writing N rows
    # here instead of one widens every scoped surface at once — the list, the read, the media URLs,
    # the questionnaire scope, the ratings — and narrows none of them, because none of them were
    # ever reading anything else.
    if designer_ids:
        await attach_the_named_designers(
            record.id,
            designer_ids,
            granted_by_id=current_user.id,
            creator_id=record.createdById,
        )
    # The designer's own details, copied out of their profile into stage 1 and stage 3 before the
    # form is ever opened, so nobody retypes their institution and biography twenty-two stages
    # into their fifth workshop of the year. WHOSE profile that is, is the LEAD's — `designerUserId`
    # when the body named one, the first ticked name when it named only a team, and the CREATOR's
    # when it named nobody at all. `named_designer_team` above settles which of the three, and the
    # whole of the argument — including why there is no fallback from a named designer with no
    # profile to the actor's — is in ``seed_designer_prefill``. ONE profile whatever the team's size:
    # stage 1 declares one designer block and `report_meta` feeds `designerName` into the .docx's
    # `dc:creator`, which is not a list. Written as ordinary stage entries — the report reads them
    # with no special case at all — and copied rather than referenced, because a report is a
    # historical document.
    #
    # AND THIS FORM'S OWN ANSWERS GO WITH THEM, WHICH IS NOT A CONVENIENCE. Every key below is
    # declared in `PROMOTED_COLUMNS` under `workshopSetup.*`, so the loop above wrote COLUMNS
    # whose single writer is supposed to be the stage entry — and with no entry behind them, the
    # first stage-1 save nulled all six (and the four beside them) under a 200 saying "Stage
    # saved". `seed_designer_prefill` writes them as one `workshopSetup` singleton alongside the
    # profile keys, which is why they are handed to it rather than created here: two creates for
    # one singleton entity would be two rows where every matcher in `save_stage` expects one.
    #
    # DATES ARE PASSED AS THE RAW REQUEST STRINGS, not the `_parse_date` datetimes above: the
    # registry's DATE type coerces and stores an ISO string, and `_coerce_promoted` is what turns
    # it back into a column value on the stage-save path. A malformed date is dropped by
    # `validate_entry` exactly as `_parse_date` drops it here, so the two halves agree.
    #
    # `title`/`workshopTitle` IS DELIBERATELY NOT SEEDED. It is the one promoted column
    # `DesignWorkshop` declares NOT NULL and the one `_coerce_promoted` refuses to blank, so it
    # was never at risk; seeding it would instead freeze the create-form title into stage 1 where
    # a later PATCH of the workshop title could not reach it. Same for `notes` and `workshopId`,
    # neither of which is a promoted column at all.
    seeded = {
        key: value
        for key, value in (
            ("craftName", payload.craftName),
            ("clusterName", payload.clusterName),
            ("state", payload.state),
            ("district", payload.district),
            ("startDate", payload.startDate),
            ("endDate", payload.endDate),
        )
        if value
    }
    record = await seed_designer_prefill(
        record, current_user, designer_id=designer_id, extra=seeded
    )
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
    # RESOLVED HERE AND NOT INSIDE `_stages_payload`, which is synchronous and shared by three
    # routes. Each route does its own await so that a reader added later cannot pick up the
    # serialiser and quietly ship stamps with no names on them — the failure would look like "this
    # field has no author" rather than like a missing call. See `test_entry_provenance_readers.py`,
    # which asserts per route rather than once.
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
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
    workshop_id: str,
    payload: DesignWorkshopPatch,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Correct the workshop's own header — its title, its template, its link, its notes, the six
    cover values the create form also collects, and its status.

    ``PATCH`` WRITES EXACTLY THE COLUMNS ``POST`` WRITES, less ``createdById`` and
    ``schemaVersion``, which are facts about the create rather than fields. The whole list, what a
    key means, and what happens to a key that is not on it are at ``_HEADER_TEXT_COLUMNS`` and
    ``_NEVER_PATCHABLE`` above; this docstring is about the four things a caller has to know that
    the tables cannot say.

    ── 1. PARTIAL MEANS PARTIAL, AND ``null`` IS NOT ``absent`` ──────────────────────────────────

    The body is dumped with ``exclude_unset=True``, so a key is in it because THE CLIENT SENT IT.
    A field the client did not mention is not touched. A field sent as ``null`` — or as ``""``, or
    as whitespace, because a text box sends ``""`` for "emptied" and never JSON null — is CLEARED
    to NULL. Those are different requests and this route used to answer both of them identically:
    it read ``getattr(payload, key) is not None`` and dropped everything falsy, so a designer who
    emptied the notes box and pressed Save got a 200 saying the workshop was saved and the old note
    back on the next load, with nothing on any screen to read. ``_header_patch_data`` carries the
    full argument, including why a ``null`` on ``title``, ``templateId`` or ``status`` is a 422
    naming the field rather than the ``MissingRequiredValueError`` Prisma would have answered.

    A body with no writable key at all is a 200 and the unchanged summary, not an error. An empty
    save is what a form does when the user pressed Save without typing, and refusing it would only
    teach them to distrust the button.

    ── 2. **THE DESIGNER PREFILL DOES NOT RUN HERE. NOT ONCE, NOT CONDITIONALLY, NOT "ONLY WHEN THE
    FIELD IS BLANK".** ─────────────────────────────────────────────────────────────────────────

    ``seed_designer_prefill`` — and ``designers.prefill_from_profile`` underneath it — is called by
    the CREATE route and by nothing else, and that is a contract rather than an accident of where
    the call happens to sit. Its own docstring states it: *"a report is a HISTORICAL DOCUMENT …
    every value below is read once, at creation, and never consulted again."*

    WHAT A SECOND RUN WOULD DO, concretely. It reads TODAY's ``DesignerProfile`` and writes it over
    ``workshopSetup.designerName``, ``designerInstitution`` and nineteen stage-3 fields. So a
    designer who moved from NIFT to NID in 2027 would, by somebody merely renaming a 2026 workshop,
    have that workshop's report re-attributed to an institution that had nothing to do with it and
    had never sponsored it — on the cover, in the certification block and in the .docx's own
    ``dc:creator``. Any correction the designer typed by hand for THIS workshop would revert under
    a 200, on every save, and go on reverting. And the stamp would compound it: the seed stamps the
    ACTOR, so an admin's routine title fix would put the admin's name and today's date under
    twenty-one fields on a ministry document the admin has never read.

    THE WIRE ENFORCES IT AS WELL AS THIS PARAGRAPH. ``designerUserId`` and ``designerUserIds`` are
    in ``_NEVER_PATCHABLE``, so there is no way to ask this route to reconsider whose profile was
    copied; changing WHO MAY OPEN the workshop is ``PUT /{workshop_id}/viewers``, a different route
    that writes viewer rows and re-seeds nothing. If the designer's details are wrong for this
    workshop, they are stage data now and stage 1 and stage 3 are where they are corrected — per
    workshop, which is the entire point of them being copies.

    ── 3. TWO GATES, IN THIS ORDER, AND THE ORDER IS THE RULE ───────────────────────────────────

    ``_require_designer`` first — the SET ``{DESIGNER, ADMIN, MASTER_ADMIN}``, not a rank floor —
    and ``load_workshop_or_404(..., for_edit=True)`` second. Neither is new and neither may move.

    THE ORDER IS WHAT KEEPS A VIEWER FROM WRITING. ``load_workshop_or_404`` performs NO role check
    at all: it admits the creator, an admin, or the holder of any ``DesignWorkshopViewer`` row, and
    a grant can be held by a RESEARCHER or a PROFESSOR. Run second, the role gate refuses them
    before the row test can admit them. Swap the two lines and every viewer-grantee becomes an
    editor of the header — silently, because the swap does not break a single test that exists
    about reading. An INSPECTOR is refused by the same clause and has never been anything else: an
    inspection grant is a READ relation, and ``prisma/schema.prisma`` says in so many words that no
    read which decides a WRITE may consult it.

    A designer's access to a workshop is always a GRANT, never ``createdById``: the create gate is
    strictly narrower than this one and admits admins alone, so ``createdById`` can never match a
    designer and "their own workshop" means "a workshop an admin put them on". ``for_edit=True`` is
    also what turns a soft-deleted workshop into 409 "This workshop is deleted. Restore it before
    editing." rather than a 404 — a sentence the clients rethrow rather than swallow, so do not
    catch it here.

    TWO NAMES ARE DELIBERATELY NOT SPELLED IN THE THREE PARAGRAPHS ABOVE — the inspector relation and
    the create-only gate — and their absence is load-bearing rather than stylistic. Two tests read
    this module AS TEXT: ``tests/test_dw_inspector_scope_gate.py`` sweeps every file under ``app/``
    for the inspection scope's identifiers, on the argument that "the drift being defended against is
    somebody reaching for an autocompleted symbol, and text is where that happens", and
    ``tests/test_design_workshop_gate.py`` reads THIS handler's source to prove the admin-only create
    rule has not crept onto it. A name written in a docstring reads to both of them exactly like a
    call. The create route carries the same warning above its own gate for the same reason.

    ── 4. WHAT COMES BACK ───────────────────────────────────────────────────────────────────────

    ``workshop_summary`` — the same header dict ``GET /design-workshops`` serialises per row and
    ``GET /design-workshops/{id}`` returns before it adds stages, completeness and transcripts.
    Unchanged, and deliberately so: a client can replace its stored header with this response
    wholesale. It carries the six read-only cover columns (``venue``, ``scheme``, ``designerName``,
    ``implementingAgency``, ``sponsor``, ``workshopCode``) that this route refuses on the way IN,
    which is not a contradiction — they are the workshop's cover, a form should show them, and the
    place to change them is the stage that owns them.

    ONE THING THE RESPONSE CANNOT TELL YOU, which belongs on the screen instead: six of the columns
    this route writes are also stage 1's. A craft corrected here stands until stage 1 is saved with
    that box empty, at which point ``_coerce_promoted`` sets it back to NULL under a 200 reading
    "Stage saved". ``title`` is the mirror image — the create deliberately does not seed
    ``workshopSetup.workshopTitle``, so a title set here stands until somebody types a different one
    into stage 1, and then stage 1 wins. Two writers, no arbitration, and the honest thing to do
    with that is print it beside the boxes rather than hide it.
    """
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    data = _header_patch_data(payload.model_dump(exclude_unset=True))
    if not data:
        return workshop_summary(record)
    # AFTER the emptiness check and before the write, so a body that never mentions the link costs
    # nothing. A link being CLEARED needs no lookup either — there is no row to find.
    if data.get("workshopId") is not None:
        await _assert_linked_workshop_exists(data["workshopId"])
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
    """Undo a soft delete. Admin only — the point of the safety net is that it is not per-user.

    THE PAIR IS CLEARED TOGETHER, AND THAT IS DELIBERATE. ``deletedAt`` and ``deletedById`` are one
    fact in two columns — *deleted, by whom* — and a restored workshop is not deleted, so leaving
    the pointer behind would put "deleted by Priya" on a live row that Priya restored ten seconds
    later. Nothing reads ``deletedById`` except the trash listing (``deletedOnly`` above), which by
    construction only sees rows whose ``deletedAt`` is set, so the surviving half would be legible
    to nobody and would answer ``deletedById IS NOT NULL`` — the obvious way to count deletions —
    with every row ever restored.

    IT DOES COST THE HISTORY, and the honest statement of that is that the history was never here to
    keep: clearing ``deletedAt`` is what a restore IS, so a scheme that kept "who" would keep it
    without the "when" it belongs to. This table has no revision log (``RecordRevision`` covers the
    other record families, not this one), so who deleted a workshop and who restored it is
    unanswerable after a restore either way. That gap is worth closing with an audit row, not with a
    stale pointer.

    NOTHING CASCADES, EITHER WAY. ``delete_design_workshop`` writes to this row and no other — stage
    entries, custom sections, AI layers, exports and viewer grants are all left exactly as they were,
    so there is nothing for a restore to un-cascade and a restored workshop comes back whole. (Of
    those five only ``DwStageEntry`` and ``DwAiLayer`` carry a ``deletedAt`` of their own at all, and
    both are set by their own routes, never by this one.) It is also idempotent on a live workshop:
    restoring one that was never deleted writes the two nulls it already holds and answers 200, which
    is the right answer to a double-clicked Restore button.
    """
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
    payload = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(payload))
    return {
        "stages": payload,
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
        "singleton": {},
        "collections": {},
        "custom": {},
        # THE EMPTY FALLBACK CARRIES THE BUCKET TOO. A stage nobody has saved yet answers this
        # route, and a client that reads `provenance` unconditionally would have crashed on the
        # one shape that is guaranteed to exist for every workshop on its first day.
        "provenance": {"singleton": {}, "collections": {}, "custom": {}},
    }
    # `_provenance_maps` walks a `{stageKey: bucket}` map; this route holds one bucket, so it is
    # wrapped rather than given a second walker.
    await resolve_display_names(_provenance_maps({stage_key: payload}))
    payload["completeness"] = workshop_completeness(entries, definition=definition).get(stage_key)
    payload["transcripts"] = await _transcripts_payload(entries, current_user)
    payload["customSchemaVersion"] = definition.version
    return payload


@router.get("/{workshop_id}/provenance")
async def workshop_provenance(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """The whole authorship picture for one workshop. Admins and master admins only.

    "Admins and master admins have access to all of it" is two things, and only one of them is on
    the ordinary stage reads. Every designer on the workshop already sees the per-field stamps —
    they are on ``GET /design-workshops/{id}/stages``, because knowing that a colleague changed the
    price is part of working on the record. THIS route adds the half nobody else can see: for every
    field whose value was COPIED from a shared canonical record, what that record says TODAY,
    beside what this workshop stored.

    WHY THAT COMPARISON NEEDS AN ENDPOINT OF ITS OWN. Once a value is hydrated onto a stage entry
    it is an ordinary string in ``data``; a hydrated village and a typed village are the same bytes
    (see the note above ``REFERENCE_HYDRATION``, which is why the copy exists). So "this workshop
    says Barpali and the artisan record now says Bargarh" is not derivable from anything the other
    readers return — it takes the ``reference`` stamp, which names the record and the column, and a
    live read of that record. Divergence is not an error and the endpoint never says it is: a
    workshop is a dated observation and is SUPPOSED to keep what the designer saw. What an admin
    needs is to be able to SEE it, which before this was impossible.

    ADMIN, NOT PROFESSOR, and not the workshop's own designers. This crosses out of the workshop
    into the shared record tables and reports one account's data next to another's, which is the
    line ``is_admin`` draws everywhere else in this file. The stage reads are unaffected: a
    designer loses nothing by not having this.

    A deleted canonical record answers ``canonical: null`` with ``recordDeleted: true`` rather than
    being omitted — that IS the interesting state, and it is exactly the case reference hydration
    was built for. Omitting it would make "the record is gone" look identical to "the field was
    never hydrated".
    """
    if not is_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Field-by-field provenance across the shared records is an admin view. "
                "Ask a master admin if you need it for an audit."
            ),
        )
    # `load_workshop_or_404` admits admins to soft-deleted workshops, which is deliberate here: an
    # audit of who wrote what is most needed on a record somebody has deleted.
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    stamps = resolve_entry_provenance(entries)
    await resolve_display_names(stamps.values())
    divergence = await canonical_divergence(entries)
    return {
        "entries": [
            {
                "entryId": row.id,
                "stageKey": row.stageKey,
                "entityKey": row.entityKey,
                "ordinal": row.ordinal,
                # WHO CREATED THE ROW IS REPORTED BESIDE THE PER-FIELD ANSWER, NOT INSTEAD OF IT.
                # It is still a true and useful fact — somebody started this participant row — and
                # showing both is what makes visible the thing this feature exists for: a row
                # created by one designer whose fields are now attributed to three other people.
                "createdById": row.createdById,
                "fields": stamps.get(row.id, {}),
                "canonical": divergence.get(row.id, {}),
            }
            for row in entries
        ],
    }


def _submit_refusal_message(errors: Mapping[str, Any]) -> str:
    """The sentence a ``submit=true`` refusal leads with, chosen from what actually failed.

    ``errors`` mixes two kinds of refusal that the strict pass used to report with one hard-coded
    sentence, "Some required fields are missing". ``validate_entry`` puts a missing Basic-tier
    answer there — "Venue is required", or the conditional form "End date is required once Start
    date is filled in." — and ``coerce_value`` puts an UNREADABLE one there too: "Cost per unit is
    not a valid number", "Craft: 'IKKAT' is not a valid option", "Notes is longer than 400
    characters". A submit whose only fault was a fat-fingered decimal was therefore reported as a
    missing required field, and the designer went looking at the empty boxes rather than the
    wrong one.

    MATCHED ON "is required" RATHER THAN ON A FLAG, because there is no flag: `errors` is
    `{scope: {field: message}}` and the message is the only thing that distinguishes the two.
    That is a string test and it is admittedly fragile, so the two writers of the required form
    are named above and both live in `stage_schema`/`custom_sections`; if either is reworded,
    reword it here. It degrades safely in the direction that matters — an unrecognised message
    reads as "could not be read", which sends the designer to the marked boxes, and `errors`
    itself is what marks them.

    A MIXED SAVE GETS BOTH HALVES rather than the first one found. Both are true and the remedies
    differ, and picking one would put the designer back where the hard-coded sentence did.
    """
    messages = [
        str(message)
        for fields in errors.values()
        for message in (fields.values() if isinstance(fields, Mapping) else [fields])
    ]
    required = any(" is required" in m for m in messages)
    unreadable = any(" is required" not in m for m in messages)
    if required and unreadable:
        return "Some required fields are missing, and some answers could not be read"
    if unreadable:
        return "Some answers could not be read"
    return "Some required fields are missing"


@router.put("/{workshop_id}/stages/{stage_key}")
async def save_stage_data(
    workshop_id: str,
    stage_key: str,
    payload: StageSaveIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Save a whole stage in one write.

    Returns HOW MUCH was written (``saved``/``created``/``updated``/``removed``), any per-field
    validation errors, and the field keys that were DROPPED because the registry does not know
    them — the last of which is how a server notices that a phone is running ahead of it, rather
    than by rejecting the sync. It does NOT return the cleaned values themselves; this docstring
    said it did for as long as ``save_stage`` built an echo block it never returned. See that
    function's docstring for what was deleted and what a client should read instead.

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
        # THE WHOLE RESULT TRAVELS UNDER THE 422, AND NOT ONLY THE ERRORS.
        #
        # This refusal is raised AFTER `save_stage` has committed. Its transaction has already
        # applied every update, every create, the `update_many` that soft-deletes the swept rows
        # and the DRAFT→IN_PROGRESS header write — so a request answered 422 has mutated the
        # record in every way a successful save would have. Returning `{message, errors}` alone
        # threw away `removed`, `created`, `updated`, `droppedKeys`, `droppedCustomKeys`,
        # `completeness`, `refusedAnswers` and `customSchemaVersion`, which left every client
        # with the reasonable and WRONG reading that nothing was written: the deleted duplicate
        # cost line had gone, the workshop had left DRAFT, and the designer was told only "Some
        # required fields are missing". Spreading `result` costs nothing — it is the same dict
        # the 200 path returns, all primitives, so it serialises identically — and lets a client
        # say "the stage was written and 1 row removed, but it cannot be submitted yet".
        #
        # `message` is spread LAST so it wins if a future key of that name is ever added to the
        # result, rather than the result silently overwriting the sentence a client renders.
        #
        # MOVING THE GATE IN FRONT OF THE TRANSACTION IS NOT THE FIX. It would cost the
        # deliberate behaviour that a stage with one bad number still saves its other twenty
        # fields, which is the rule `save_stage`'s validation loop is built around.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={**result, "message": _submit_refusal_message(result["errors"])},
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
        fields.append(
            CustomFieldSpec(
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
            )
        )
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
    workshop_id: str,
    payload: CustomSectionsIn,
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
    if payload.customSchemaVersion is not None and payload.customSchemaVersion != stored.version:
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
        row.stageKey: answered_keys(stored.fields_for(row.stageKey), dict(row.data or {}))
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
    recordId: str | None = Query(None, max_length=64),
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

    ``recordId`` is the SCANNED half of the same feature, and it is additive: absent, this endpoint
    answers exactly as it always did. Present, it appends an ``id`` clause, which is the only way a
    printed code can become an option at all — every other clause searches prose columns and ``id``
    is in none of them, so a scan of a colleague's record card used to come back as an empty list.
    The response's new ``outOfScope`` flag is the answer to the case a WORKSHOP-scoped field makes
    unavoidable: the record is real, it is readable, and this field's scope excludes it. Saying so
    is not widening the scope — that row arrives under its own ``outOfScopeOption`` key with
    ``options`` left EMPTY, so a client that renders ``options`` and has never heard of the flag
    cannot offer it as an ordinary choice.

    Readable by anyone who can read the workshop. The options are records they can already list
    through ``/records``; refusing them here would only mean the designer opens a second tab and
    copies the name across by hand, which is the behaviour being replaced. ``current_user`` now
    travels into the service for the by-id path, which composes the same read predicate the record
    list routes compose so that a row the caller could not list cannot be confirmed to exist here.
    """
    record = await load_workshop_or_404(workshop_id, current_user)
    return await reference_options(
        record,
        model,
        scope=scope.upper(),
        filter_by=filterBy,
        search=search,
        limit=limit,
        record_id=recordId,
        viewer=current_user,
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
    transcripts endpoint, the annexure.

    WHAT THAT PER-FILE GATE ACTUALLY ADMITS WAS RESTATED HERE ON 2026-08-27, BECAUSE THIS PARAGRAPH
    HAD GONE STALE IN THE DIRECTION THAT MAKES A REAL WITHHOLDING LOOK ARBITRARY. It used to say: "A
    ``DesignWorkshopViewer`` grant carries read and stage writes and nothing about media (see
    ``services/design_workshop_viewers.py``), so those two sets genuinely differ, and serving the
    copy on the workshop gate alone would have handed a granted colleague the full text of a
    recording ``GET /design-workshops/{id}/transcripts`` refuses them." The first clause is
    false, and the example resting on it names the one case that does NOT happen.
    ``owned_or_granted_where`` carries a THIRD arm, keyed on the media TAG rather than on the
    uploader — ``services/records._design_workshop_media_branches``, which admits every
    ``MediaFile`` whose ``linkedRecordType`` is ``designWorkshop`` and whose ``linkedRecordId`` is a
    workshop this account may open. So ``GET /design-workshops/{id}/transcripts`` does NOT refuse
    a granted colleague their own workshop's recordings, and has not since that arm was added;
    ``backend/tests/test_media_entitlement.py`` pins it in both directions, in
    ``test_a_granted_co_designer_is_shown_the_workshops_own_recordings`` and
    ``test_a_designer_with_no_grant_is_still_refused_the_same_recording``. On the same date
    ``GET /media``'s ``url`` was brought into line through ``records.media_url_scope``, so the
    question "may this account have these bytes" no longer has two answers in this repository
    depending on which route is asked.

    THE TWO SETS STILL DIFFER, IN THE OTHER DIRECTION, AND THAT IS WHAT ``textWithheld`` GUARDS. A
    grant admits THIS WORKSHOP'S TAGGED FILES AND NOTHING ELSE. A stage field stores a media id, and
    nothing obliges that id to name a file tagged to this workshop — so a layer registered here can
    stand on a recording an uploader filed under another workshop, or under none, named on a stage
    by anybody who may edit one. Those are gated on uploader identity exactly as they were, and the
    only key to them is a ``DataAccessGrant`` from that uploader — the grant that means "may take
    that account's data at large", which the 2026-08-27 change did not touch and which a workshop
    grant is not and never becomes. Serving the stored copy on the workshop gate alone would hand
    one of those over out of a table that exists precisely to keep a copy of it. Every row is
    therefore listed with its provenance, and the ones standing on a recording this caller may not
    read come back with ``textWithheld`` true and no text, preview, payload or character count.

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
        "accepted": sum(1 for row in rows if row.acceptedAt is not None and row.deletedAt is None),
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
    workshop_id: str,
    payload: AiLayerRegisterIn,
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

# ── HOW BIG A FILE EACH MEDIA VERB WILL ACCEPT ─────────────────────────────────────────────────
#
# `_verb_source_media` below checks entitlement and media TYPE and has never checked SIZE, so both
# verbs read whatever the row points at straight into the heap of the single-worker web process.
# Nothing caps what may be uploaded as workshop media, and the largest live object in this
# deployment is 668.44 MiB against a 1 GiB box (MEASURED, docs/SCALABILITY.md §5.1).
#
# CAPTION is memory-derived and small, because it genuinely needs every byte at once: a vision model
# is sent base64 of the whole image inside a JSON body, so there is nothing to stream into. A
# photograph is a phone photograph — p90 across this repository's media is 14.28 MiB.
#
# SUBTITLES is a disk bound rather than a memory one, because it now streams the object to a temp
# file and hands the provider that path (see `s3.download_to_temp`). It is the larger of the two
# ceilings on purpose: SUBTITLES accepts VIDEO, so this is the verb most likely to be pointed at
# the biggest object in the archive.
MAX_CAPTION_BYTES = 32 * 1024 * 1024
MAX_SUBTITLE_FETCH_BYTES = 1024 * 1024 * 1024


async def _refuse_oversize_verb_source(media: Any, ceiling: int, *, what: str) -> None:
    """Refuse with a 413 naming both numbers when this file is too big for *what*. Never truncates.

    The declared column first because it costs nothing, then ``s3.head_object`` for the real
    ``ContentLength`` — ``MediaFile.sizeBytes`` is a client's claim that nothing reconciles, so it
    can only ever be the cheap half of this check. ``head_object`` answering ``None`` means storage
    would not say, and that is not read as "small": the SUBTITLES path passes the same ceiling to
    ``download_to_temp``, which counts what actually lands.
    """
    declared = int(getattr(media, "sizeBytes", 0) or 0)
    real = None
    if declared <= ceiling:
        head = await asyncio.to_thread(head_object, media.objectKey)
        real = head.size_bytes if head is not None else None
        if real is None or real <= ceiling:
            return
    size = real if real is not None else declared
    raise HTTPException(
        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        detail=(
            f"That file is {size} bytes, over the {ceiling}-byte limit this server will {what}. "
            f"Nothing was sent anywhere and nothing was spent."
        ),
    )


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
        answer = await ai.proofread_text(text, get_settings(), user_id=current_user.id)
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
        answer = await ai.expand_text(payload.text, get_settings(), user_id=current_user.id)
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
            source_language = str(getattr(row, "language", "") or "").strip() or stated_source
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
            user_id=current_user.id,
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
    # SIZE, WHICH `_verb_source_media` DOES NOT CHECK — see `MAX_CAPTION_BYTES`. Before the try, so
    # a refusal never reaches `_count_refused_run`: nothing was sent, so nothing was spent, and
    # counting it against the workshop's allowance would charge for a request that never left.
    await _refuse_oversize_verb_source(
        media, budget_bytes(MAX_CAPTION_BYTES), what="caption in one piece"
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
            user_id=current_user.id,
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
    # SIZE, WHICH `_verb_source_media` DOES NOT CHECK — see `MAX_SUBTITLE_FETCH_BYTES`. This verb
    # takes VIDEO as well as AUDIO, so it is the one most likely to be pointed at the 668 MiB object.
    await _refuse_oversize_verb_source(media, MAX_SUBTITLE_FETCH_BYTES, what="spool for subtitling")
    answer: Mapping[str, Any] | None = None
    source_path: str | None = None
    try:
        # STREAMED TO DISK, NOT READ INTO THE HEAP — this is the single-worker web process, and a
        # whole-object read here competes with every request in flight. `transcribe_timed_bytes`
        # takes the path and hands it to the provider rung that answers.
        source_path = await asyncio.to_thread(
            download_to_temp, media.objectKey, max_bytes=MAX_SUBTITLE_FETCH_BYTES
        )
        answer = await ai.transcribe_timed_bytes(
            None,
            str(getattr(media, "originalFilename", "") or "recording.webm"),
            str(getattr(media, "mimeType", "") or "audio/webm"),
            get_settings(),
            source_path=source_path,
        )
        plan = ai_verbs.subtitle(
            workshop_id=workshop_id,
            media_id=media.id,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            created_by_id=current_user.id,
        )
    except ObjectTooLarge as exc:
        # `head_object` could not size it and the transfer hit the bound. Not a `VerbError`, so it
        # deliberately does not reach `_count_refused_run`: nothing was sent to a provider.
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"That file is {exc.size_bytes} bytes, over the {exc.limit_bytes}-byte limit this "
                f"server will spool for subtitling. Nothing was sent anywhere and nothing was spent."
            ),
        ) from exc
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.SUBTITLES, current_user=current_user
        )
        raise _verb_http(exc) from exc
    finally:
        # Every exit, refusal and success alike. A verb that left a copy of every video it subtitled
        # on the web box's disk would fill it long before anybody noticed why.
        discard_temp(source_path)
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
    wanted = {root.media_id} if root.kind is ai_layers.RootKind.MEDIA and root.media_id else set()
    return root.withheld_from(await _readable_media_ids(wanted, viewer))


@router.post("/{workshop_id}/ai-layers/{layer_id}/accept")
async def accept_ai_layer(
    workshop_id: str,
    layer_id: str,
    payload: AiLayerDecisionIn | None = None,
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
    permissions in this repository, and this paragraph was CORRECTED 2026-08-27 because it had the
    difference the wrong way round. It used to read "a ``DesignWorkshopViewer`` grant carries the
    first and says nothing about the second"; the grant DOES carry the second for this workshop's own
    tagged files, through ``records.owned_or_granted_where``'s tag-keyed third arm, so the colleague
    that sentence pictured is shown those recordings and is refused nothing. The gap this check
    actually guards is the other one: a layer can stand on a recording tagged to a DIFFERENT workshop
    or to none at all — a stage field stores a media id and nothing obliges that id to be this
    workshop's — and those stay gated on uploader identity, openable only with a ``DataAccessGrant``
    from the uploader. Without this check a colleague could put their name to one of THOSE, a
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
    workshop_id: str,
    layer_id: str,
    payload: AiLayerDecisionIn | None = None,
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

    The calculation is PURE so that it can run in the browser and on the handset, and TRUE AS OF
    2026-08-22 IT RUNS ON BOTH, unflagged. Android: `DwFindingsPanel.CostFindingsCard` calls
    `DwCostIntegrity.analyse`, through the `DwStageFindings` mount `StageScreen` puts on every stage
    form. Web: `frontend/components/designworkshop/CostFindingsPanel.tsx` is mounted at
    `COSTING_STAGE` and computes over `frontend/lib/costIntegrity.ts`, which
    `frontend/e2e/cost-integrity-port-unit.spec.ts` holds equal to `app/services/cost_integrity.py`
    case for case. Check the claim rather than trusting it:

        grep -rn "DwCostIntegrity.analyse" android/app/src/main
        grep -rn "CostFindingsPanel" frontend/app frontend/components

    The date and the two greps replace what used to stand here: a paragraph asserting no designer
    saw a cost-integrity finding on any surface, ending "delete this paragraph when the ports and a
    panel land, not before". Both ports landed and nobody deleted it, so for a fortnight the
    sentence was not merely stale but ARMED — an instruction only a reader who already knew the
    answer could act on, sitting under a claim that would stop them looking. `docs/COMPUTED_FINDINGS
    .md` §7 names that shape as the pattern to avoid, and this is the replacement it asks for: a
    dated statement with the command that re-checks it.

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
        return [
            dict(row.data or {}, _entryId=row.id) for row in entries if row.entityKey == entity_key
        ]

    # A cost sheet is labelled by `productRef`, which points at a stage-13 final product. The
    # service is pure and cannot resolve a reference, so the names are looked up here and passed
    # in — a finding headed by a raw cuid is one a designer cannot trace back to a row.
    labels = {
        row.id: str((row.data or {}).get("name") or (row.data or {}).get("productCode") or "")
        for row in entries
        if row.entityKey == "finalProduct"
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
    workshop_id: str,
    templateId: str | None = None,
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
        _build_only,
        data,
        template_id,
        resolver,
        record,
    )
    warnings = list(warnings) + load_warnings
    return {
        "meta": _preview_meta(document),
        "blocks": [_block_payload(b) for b in document.blocks],
        "warnings": list(warnings),
    }


@router.post("/{workshop_id}/report")
async def generate_report(
    workshop_id: str,
    payload: ReportGenerateIn,
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
    # `[0]` IS THE WHOLE LIST, and that is now enforced rather than assumed: `ReportGenerateIn`
    # refuses a body naming two formats with a 422 naming the two requests to make. It used to
    # accept one — its docstring advertised "give me both" as the common case — and this line
    # quietly threw the second away, always the PDF, because the validator returns the set sorted.
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
        render_report,
        data,
        template_id,
        resolver,
        record,
        fmt,
        payload,
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
        await db.dwreportexport.create(
            data={
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
            }
        )

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
            "id": r.id,
            "format": r.format,
            "templateId": r.templateId,
            "fileName": r.fileName,
            "fileSizeBytes": r.fileSizeBytes,
            "pageCount": r.pageCount,
            "checksumSha256": r.checksumSha256,
            "generatedOnDevice": r.generatedOnDevice,
            "generatedAt": r.generatedAt.isoformat() if r.generatedAt else None,
            "warnings": r.warnings,
        }
        for r in rows
    ]


@router.post("/{workshop_id}/exports", status_code=status.HTTP_201_CREATED)
async def record_device_export(
    workshop_id: str,
    payload: ExportRecordIn,
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
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Unknown export format"
        )
    row = await db.dwreportexport.create(
        data={
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
        }
    )
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

    A third fact was added later, after the client had been caught deriving it: **which generation
    each file is.** ``generation`` is the file's one-based place in the workshop's whole export
    record, oldest first, and it is computed here because it cannot be computed there — the export
    list is capped at the newest hundred, so a browser numbering the files it was sent restarts at 1
    on whichever file happened to survive the cut and every "Generation N" on the screen is off by
    the number of files dropped. See the count beside the query below for why it is anchored on the
    oldest row returned rather than taken as a count of the whole table.

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

    # The export record and the stage-row timestamps are two independent tables — the diff is built
    # from both on the device, not from one against the other — so they go out together rather than
    # one after the other.
    exports, entries = await gather_reads(
        db.dwreportexport.find_many(
            where={"designWorkshopId": workshop_id},
            order={"generatedAt": "desc"},
            take=_HISTORY_EXPORT_LIMIT + 1,
            include={"generatedBy": True},
        ),
        # Deliberately unfiltered on `deletedAt`: a removed row IS a change between two files, and
        # it is the only kind of change that leaves nothing behind to notice.
        db.dwstageentry.find_many(
            where={"designWorkshopId": workshop_id},
            order={"updatedAt": "desc"},
            take=_HISTORY_ENTRY_LIMIT + 1,
        ),
    )
    entries_truncated = len(entries) > _HISTORY_ENTRY_LIMIT
    window = exports[:_HISTORY_EXPORT_LIMIT]
    # THE GENERATION NUMBER IS THE FILE'S POSITION IN THE WHOLE RECORD, AND IT HAS TO BE COMPUTED
    # HERE BECAUSE THE CLIENT CANNOT. `reportDiff.inGenerationOrder` used to derive it by sorting the
    # window oldest-first and indexing into it, which is right up to the moment the hundred-file cap
    # bites and then wrong by exactly the number of files cut: the query above is `generatedAt desc`
    # with `take = limit + 1`, so what is dropped is always the OLDEST end. A workshop that
    # regenerates on every edit passes a hundred exports, and from then on the card that says
    # "Generation 3" and the diff header that says "generation 3 → generation 7" are naming
    # positions inside a sliding window — numbers that are not the record's own, that change as more
    # files are generated, and that a designer quotes into a covering email to a ministry.
    #
    # Counted from the OLD end (`lt` the oldest row we are actually returning) rather than as a
    # count of the whole table, because this endpoint holds no transaction and the one mutation this
    # table gets is an append: a report generated on another device between the find_many and the
    # count would inflate a whole-table total and shift every number on the screen by one, whereas
    # rows strictly older than our oldest row cannot appear after the fact. Skipped entirely when
    # the window is empty — a workshop with no exports needs no query to know nobody generated one.
    #
    # `generatedAt` is written by the server on every path (`_parse_datetime(...) or now`), including
    # the device-import route above, so the anchor is never None. Ties on the exact same timestamp
    # are numbered in whatever order the sort returned them, which is the same arbitrary order the
    # list itself is in — the numbering agrees with the screen, which is what it is for.
    generations_below = 0
    if window:
        generations_below = await db.dwreportexport.count(
            where={
                "designWorkshopId": workshop_id,
                "generatedAt": {"lt": window[-1].generatedAt},
            }
        )

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
            {}
            if entries_truncated
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
        "exports": [
            # `window` is newest-first, so the newest row is the highest number: the last of the
            # `generations_below` files nobody can see, plus its own place in what we are sending.
            _export_payload(row, generation=generations_below + len(window) - index)
            for index, row in enumerate(window)
        ],
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


def _export_payload(row: Any, *, generation: int) -> dict[str, Any]:
    """One recorded export, as the history screen reads it.

    A superset of what ``list_exports`` returns, and additive to it rather than a replacement: the
    generator, the registry version in force at generation, and the storage key where one exists.
    ``generatedBy`` is ``SetNull``, so an export made by an account that has since been deleted
    names NOBODY — not the workshop's owner, who is the tempting default and would put a name
    against a file they never produced.

    ``generation`` IS A REQUIRED ARGUMENT AND NOT A DEFAULT, deliberately. It is the file's place in
    the workshop's whole export record, one-based and oldest-first, and it can only be known by the
    caller that also knows how many older files the hundred-file cap left out — see the count in
    ``report_history``. A default here would let a future second caller emit a payload whose
    numbering silently restarted at 1, which is the exact defect the field was added to close: the
    browser used to number the files by their position inside the truncated window, so once a
    workshop passed a hundred exports every "Generation N" on the screen was off by the number of
    files cut, and off by a different amount each time somebody generated another one.
    """
    author = getattr(row, "generatedBy", None)
    return {
        "id": row.id,
        "generation": generation,
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
    """The designer set — ``{DESIGNER, ADMIN, MASTER_ADMIN}`` — in front of sixteen of this
    router's twenty-two non-GET routes. **This is not a gate on "the two capture aids", which is
    what this docstring said while fourteen call sites in this file were reading it.**

    THE COUNTS BELOW ARE ASSERTED, NOT REMEMBERED.
    ``tests/test_design_workshop_gate.py::test_the_designer_gate_still_stands_where_this_docstring_says_it_does``
    walks this module's own source, counts the direct calls and the ungated non-GET routes, and
    fails naming this paragraph if either moves. A hand-kept count in a comment is the shape this
    repository has a rot detector for; the test is the version of it that cannot quietly go stale.

    Counted from the source, the fourteen direct calls are:

    * the two CAPTURE AIDS the old sentence named — ``POST /ocr/identity`` and
      ``POST /ocr/identity/retention`` — plus the two allowance probes beside them,
      ``GET /dictation-allowance`` and ``GET /ai-verb-allowance``;
    * ``POST /{workshop_id}/dictate`` and ``POST /{workshop_id}/dictation-consent``;
    * **``PATCH /{workshop_id}``**, which renames a workshop and rewrites its promoted columns, and
      **``PUT /{workshop_id}/stages/{stage_key}``**, which is every one of the 22 stages — the whole
      fortnight of fieldwork — together with ``PUT /{workshop_id}/custom-sections``;
    * the AI layer writes: ``POST /{workshop_id}/ai-layers`` and the accept / unaccept / delete trio
      on ``/{workshop_id}/ai-layers/{layer_id}``;
    * ``_verb_gate``, one call standing in front of five more routes — proofread, expand,
      translate, caption and subtitles.

    So: **eighteen routes, and the stage save is one of them.** The count is written down because a
    gate whose docstring understates its reach by an order of magnitude is how somebody later lifts
    it off a route they have been told is unimportant. If you add a call, add it here.

    WHAT IT IS NOT. It is not ownership and not visibility — ``load_workshop_or_404`` decides who
    may open THIS workshop, and the workshop-scoped routes above pair the two. It is not the create
    gate either: ``assert_can_create_design_workshops`` is strictly narrower and a DESIGNER is
    refused by it. And it is a SET rather than a floor, so a PROFESSOR outranks a designer
    everywhere else in this codebase and is refused here — see ``deps.can_run_design_workshops``.

    AND IT IS NOT EVERY WRITE. Six non-GET routes do not call it, five of them deliberately and
    argued elsewhere: ``POST /`` (the create, gated narrower), ``DELETE /{workshop_id}`` and
    ``POST /{workshop_id}/restore`` (``assert_can_delete`` / ``require_admin``),
    ``POST /{workshop_id}/report`` (open to anyone who may READ the workshop — the module header's
    own clause), and the retired id-less ``POST /dictate``, which answers 410 to everyone.

    **The sixth is ``POST /{workshop_id}/exports`` and it is not argued anywhere.** It writes a
    ``DwReportExport`` ledger row behind ``load_workshop_or_404(for_edit=True)`` alone, and
    ``for_edit`` performs no role check whatsoever (``services/design_workshops.py`` admits the
    creator, an admin, or ANY ``DesignWorkshopViewer`` grantee regardless of tier) — so a RESEARCHER
    or a PROFESSOR holding a viewer grant can append to the export ledger of a workshop they may
    only read. This predates the docstring that now names it and is left standing rather than
    tightened here: it is a permission decision, the row is an attestation about a file the caller
    could already generate, and narrowing it is the owner's call, not a docstring's. It is written
    down so the enumeration above is honest about its own edge.

    Stated inside each handler rather than as a dependency because every one of these routes already
    takes ``current_user`` for other reasons, and because the question it asks is not about a row:
    there is nothing to own yet at ``/ocr/identity``, only whether this account is one the app
    invites to do a designer's work at all. A card scan sends a photograph of somebody's Aadhaar to
    a third-party model and a dictation spends provider credit per press, so neither is something to
    leave open to every signed-in account — and a stage write is a fortnight of somebody else's
    fieldwork, which is a stronger reason of the same kind.
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
    """Group stage entry rows into ``{stage: {singleton, collections, custom, provenance}}``.

    ``provenance`` MIRRORS THE THREE DATA BUCKETS RATHER THAN RIDING INSIDE THEM, and it is keyed
    by ENTRY ID for collections rather than by position. Both of those are deliberate:

    * Inside ``data`` it would be a key the registry does not declare, so it would be echoed back
      on the next save and reported in ``droppedKeys`` — the one drift signal this repository has,
      which exists to say "this phone is running a newer field registry than the server". Firing it
      on every save of every workshop would train whoever reads it to ignore it.
    * By position it would be silently misaligned the moment a reader re-sorts. The collection rows
      below are sorted by ``_ordinal`` AFTER grouping, ``assemble_workshop_data`` sorts BEFORE, and
      the phone sorts its own draft; a positional map would attribute one participant's edits to
      another on whichever of the three disagreed, and nothing would raise. An id-keyed map cannot
      be misaligned by a re-sort.

    See ``services/entry_provenance`` for what a stamp means and for the boundary between this and
    reference hydration.
    """
    from app.services.entry_provenance import entry_provenance
    from app.services.stage_schema import Cardinality

    entity_cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}
    out: dict[str, Any] = {}
    for row in entries:
        bucket = out.setdefault(
            row.stageKey,
            {
                "singleton": {},
                "collections": {},
                "custom": {},
                "provenance": {"singleton": {}, "collections": {}, "custom": {}},
            },
        )
        stamps = entry_provenance(row)
        data = dict(row.data or {})
        if row.entityKey == CUSTOM_ENTITY_KEY:
            # THE RESERVED ROW GETS ITS OWN KEY IN THE STAGE PAYLOAD, and this is the second of the
            # four places the design's price is paid. Without this branch it falls to the collection
            # arm below and comes back as `collections["_custom"]` with `_entryId` and `_ordinal`
            # injected into it — a phantom repeating entity on every stage that has custom answers,
            # which both clients would render as a table of one row they cannot delete, and whose
            # `_ordinal` key would then be echoed back into the container on the next save.
            bucket["custom"] = data
            bucket["provenance"]["custom"] = stamps
        elif entity_cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            bucket["singleton"] = data
            bucket["provenance"]["singleton"] = stamps
        else:
            data["_entryId"] = row.id
            data["_ordinal"] = row.ordinal
            if row.clientKey:
                data["_clientKey"] = row.clientKey
            bucket["collections"].setdefault(row.entityKey, []).append(data)
            bucket["provenance"]["collections"].setdefault(row.entityKey, {})[row.id] = stamps
    for bucket in out.values():
        for rows in bucket["collections"].values():
            rows.sort(key=lambda r: r.get("_ordinal", 0))
    return out


def _provenance_maps(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Every stamp map inside a ``_stages_payload`` result, for one bulk name lookup.

    Walked rather than collected during the build so that a bucket added to the payload later is
    covered by adding it in ONE place. The maps are the live dicts, not copies —
    ``resolve_display_names`` mutates them in place and the payload is what goes out on the wire.
    """
    maps: list[dict[str, Any]] = []
    for bucket in payload.values():
        prov = bucket.get("provenance") or {}
        for key in ("singleton", "custom"):
            stamps = prov.get(key)
            if isinstance(stamps, dict):
                maps.append(stamps)
        for by_entry in (prov.get("collections") or {}).values():
            maps.extend(s for s in by_entry.values() if isinstance(s, dict))
    return maps


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
    # THE REPORT IS A READER TOO, and it resolves the overlay like every other one. It costs at
    # most one query for the whole document (none at all for a workshop with no attributed field),
    # and it is awaited HERE, before the three waves below, rather than inside them: the waves are
    # gathered, and a load that mutates `data.field_provenance` alongside three that mutate other
    # attributes is the one shape the comment below says cannot be gathered safely.
    await resolve_display_names(data.field_provenance.values())
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
    # Bound to a name rather than re-resolved, because the inert-toggle warning at the bottom of
    # this function asks the SAME base template the same kind of question, and two `get_template`
    # calls are two chances for a later edit to hand one of them a different id.
    base_template = get_template(template_id)
    specials = {section.special for section in base_template.sections}
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
    # AND THE THREE STAGE-20 SWITCHES THIS TEMPLATE CANNOT HONOUR. `apply_report_settings` can only
    # REMOVE the table of contents, the photographic annexure and the completeness annexure — that is
    # written down as the rule on both surfaces (ReportSettings.kt says it three times: "An explicit
    # false removes the section; absent leaves the template alone") and the Android port implements
    # the same one. What was missing was any way for a designer to learn it: only DETAILED_TECHNICAL
    # declares ANNEXURE_MEDIA and COMPLETENESS, so switching "Include the completeness annexure" on
    # while sitting on the default DCH_STANDARD produced a file without one and not a word anywhere
    # about why. This is read off the SAME base template `specials` above is read off, and for the
    # same reason — the shaped template cannot gain a section these toggles could have added.
    #
    # Here rather than inside `apply_report_settings` because that function returns a template and
    # nothing else, and it is pinned by value against the Kotlin port; a warning is a sentence for a
    # designer, and this is the function that already assembles every other one.
    warnings.extend(inert_section_toggles(base_template, data.singleton("REPORT_GENERATION")))
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
        data,
        template_id,
        resolver.ref,
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
    ascii_name = (
        "".join(
            c if (c.isascii() and (c.isalnum() or c in " -_.")) else "_" for c in file_name
        ).strip("_ ")
        or "workshop-report"
    )
    quoted = quote(file_name, safe="")
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quoted}"


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


def _preview_meta(document: Any) -> dict[str, Any]:
    """The preview's ``meta``: what the document IS, and the geometry it is laid out on.

    A module-level function rather than a dict literal inside the route for one reason — the route
    cannot be called without a database, and this is the half of the payload that has been wrong.

    ── THE PAPER IS ONLY HALF THE GEOMETRY, AND THE OTHER HALF WAS MISSING ──────────────────────

    ``report_pdf.PdfRenderer`` sizes its text column as ``page_w - 2 * margin`` and
    ``report_docx`` writes the same number into the section properties, so the MARGIN is what
    decides where every line wraps and therefore where every page breaks. This payload used to
    carry ``pageSize`` and nothing else about the geometry, which left the one surface a designer
    approves the document on guessing at the other half: ``previewModel.pageGeometry`` fell back to
    25 and ``ReportSheet`` printed "25 mm margins assumed (the preview payload does not carry the
    margin)" on every sheet — an apology, on screen, for a number the server had in its hand.

    ``margin_mm`` IS 25.0 ON EVERY DOCUMENT THIS DEPLOYMENT PRODUCES, and sending it is still the
    point. Checked rather than assumed: no ``ReportTemplate`` field sets it (``report_templates``
    declares ``page_size`` and no margin), ``design_workshops.report_meta`` reads no stage-20
    answer for it, and ``render_report`` overrides only ``page_size``, ``header_text`` and
    ``footer_text``. So today this reports the dataclass default — but it reports it as the
    DOCUMENT'S OWN value rather than as the preview's assumption, which is the difference between
    a screen that is right by coincidence and one that stays right: the first template or setting
    to move the margin moves the preview with it, with no second change here and no client edit.

    A new key on a JSON payload both clients ignore-unknown, so nothing older breaks; the web's
    ``PreviewMeta`` was already widened for it and keeps its fallback for a cached response.
    """
    return {
        "title": document.meta.title,
        "subtitle": document.meta.subtitle,
        "templateId": document.meta.template_id,
        "templateName": document.meta.template_name,
        "pageSize": document.meta.page_size.value,
        "marginMm": document.meta.margin_mm,
    }


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
