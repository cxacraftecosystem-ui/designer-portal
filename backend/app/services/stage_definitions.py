"""The 22 stages of a Design & Prototype Workshop, declared once.

This file is the machine-readable form of *Design Prototype Workshop App-ed.docx*, the source
requirements document kept at the repository root. That document is a table: twenty-two stages
down the side, three capture tiers across the top —

    Basic capture     minimum required
    Standard capture  desirable for most workshops
    Advanced capture  where facilities/expertise permit

— and every semicolon-separated item in every cell becomes a :class:`FieldSpec` below, tagged
with the tier it came from. Nothing in the source is dropped. Where an item is vague ("dates",
"material + labour cost") it becomes the several typed fields it actually means, because a
report cannot print, and a researcher cannot filter, a field whose type is "it depends".

Two categories of source item are handled differently, and deliberately:

**Advanced items that name a capability rather than a datum** — "AI-supported survey
summarization", "trend clustering", "LiDAR measurement", "vectorization", "embeddings/search
index" — are NOT invented as fields. What is captured instead is the data such a feature would
produce or consume: a transcript, an uploaded vector file, a 360-degree image list, a computed
quality flag. The schema is then ready for the feature without pretending the feature exists,
which matters because the reviewer's own comments on those cells say "AI-driven work for AI
team — later to be discussed not now" and "Phase 3".

**Advanced items that are deterministic and offline** are real fields, because they can be
built today: geo-location, uploaded sanction documents, multi-view photo slots, product codes,
version and iteration history, structured change logs, cost-calculator inputs, quality
checklists, completeness flags, and blur/resolution warnings (both computable on device from
the variance of the Laplacian and the pixel dimensions).

The reviewer's marginal comments are preserved verbatim on the fields they apply to, in
``phase_note``. They are the record of what was deferred and why, and they are the reason
several Standard-tier items here are optional rather than required.

``phase_note`` IS NOT SERIALISED, AND ``notes`` MUST NOT CARRY THE SAME MATERIAL. ``phase_note``
is deliberately absent from :func:`~app.services.stage_schema.field_to_dict`, so it never leaves
this repository: it is a code comment that happens to be attached to the field it is about.
``StageSpec.notes`` is the opposite. :func:`~app.services.stage_schema.stage_to_dict` publishes
it, the web stage page prints it under the stage header and the handset's ``StageScreen`` prints
it under the sync line, so every character of it is read by a designer in the field — including
from the bundled asset, on a phone that has never had a signal.

It did not used to be written that way, and the owner of this repository found out by using the
app: seventeen of the twenty-two stages quoted the source document's reviewer at each other —
which tier was "Phase 2 work", what was referred to "Kumarjit plug in", what "we may consider
deleting", whose app might do the image processing "for now". 3,971 characters of margin notes
from a planning meeting, shipped as on-screen guidance. None of it was actionable in a workshop,
and several entries named people with no connection to the person reading the screen.

THE RULE NOW: ``notes`` says what a designer needs in order to DO this stage, in the app's own
voice, or it is absent. Never who asked for what, which phase something was deferred to, whose
plug-in might do it later, what might be deleted, or any sentence whose subject is the
specification rather than the work. Where such a remark explained why a stage is SHAPED as it
is, it is kept as a ``#`` comment on the spec below rather than deleted — the provenance is
worth keeping; it is simply not app content. Pinned by
``test_no_client_facing_registry_string_carries_build_time_commentary``.

Adding a field: append it, never renumber. Removing one: set ``deprecated=True`` and name its
successor in ``replaced_by``. A key is what two weeks of a designer's fieldwork is stored
under — see the rules in :mod:`app.services.stage_schema`.
"""

from __future__ import annotations

# The record path's own ceiling for a Pehchan card number, named rather than re-typed as `32` at
# `participant.artisanCardNo` — two surfaces bounding one fact must not be able to drift apart by a
# digit. `artisan_identity` is pure (it imports `re` and nothing else), so this keeps the promise
# `stage_schema` makes about this module's dependencies.
from app.services.artisan_identity import PEHCHAN_MAX_LENGTH
from app.services.stage_schema import (
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    StageSpec,
    TextFormat,
    Tier,
    _install,
)

# Shorthand. These files are read far more often than they are written, and the noise of
# `FieldSpec(key=..., label=..., type=FieldType.TEXT, tier=Tier.BASIC)` repeated eight hundred
# times hides the one thing a reader is looking for: what the field is.
T, LT = FieldType.TEXT, FieldType.LONG_TEXT
# RICH is LT's formatted sibling — the same prose, plus the headings, lists, bold and italics a
# designer applies in the editor. It is used for exactly one role, NARRATIVE, and the reason is
# in the renderer rather than in taste: `_render_fields` gives a rich NARRATIVE field its own
# path through `rich_text.to_report_blocks`, which prints the structure the designer wrote. The
# other roles have no such path — BULLETS re-splits flattened text on newlines, and a
# TABLE_COLUMN or KEY_VALUE cell holds runs and loses paragraph breaks in `_cell_runs` — so
# promoting those would cost structure and buy nothing.
#
# Promotion from LT is a supported migration and needs no data backfill: `coerce_value` reads a
# plain string as unformatted prose (`rich_text.from_json`), so every narrative field already
# written keeps every word. The same is true on the phone (`RichText.kt`) and in the browser
# (`FieldInput.tsx`), both of which document the promotion they were built to absorb.
RICH = FieldType.RICH_TEXT
INT, DEC, MONEY, PCT = FieldType.INT, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT
DATE, TIME, BOOL = FieldType.DATE, FieldType.TIME, FieldType.BOOL
ENUM, MENUM, TAGS = FieldType.ENUM, FieldType.MULTI_ENUM, FieldType.TAGS
IMG, IMGS, FILE = FieldType.IMAGE, FieldType.IMAGE_LIST, FieldType.FILE
AUDIO, VIDEO, GEO, REF = FieldType.AUDIO, FieldType.VIDEO, FieldType.GEO, FieldType.REF
B, S, A = Tier.BASIC, Tier.STANDARD, Tier.ADVANCED
NARR, KV, COL, CAP = (
    ReportRole.NARRATIVE,
    ReportRole.KEY_VALUE,
    ReportRole.TABLE_COLUMN,
    ReportRole.CAPTION,
)
COVER, METRIC, GALLERY, BULLETS = (
    ReportRole.COVER_FIELD,
    ReportRole.METRIC,
    ReportRole.GALLERY,
    ReportRole.BULLETS,
)
HIDDEN = ReportRole.HIDDEN
W_SCOPE, ALL_SCOPE = REF_SCOPE_WORKSHOP, REF_SCOPE_ALL

# The sentence appended to every text field that a chosen reference fills in for the designer.
#
# THE TEXT FIELD IS NOT REDUNDANT AND MUST NOT BE REMOVED. It is what the report prints. A
# workshop report is a historical document submitted to a ministry: the artisan record it was
# built from may be corrected, merged into a duplicate or deleted entirely in the two years
# between the workshop and somebody re-opening the file, and a report that renders "Made by
# [record not found]" because the join could not be followed is a report that has lost the one
# fact it most needed to carry. The id beside it stays the join key for the research queries;
# this text is the copy that survives the id.
FROM_REF = (
    "Filled in from the linked record when one is chosen. It is stored on this entry "
    "as well as the link, so the report still prints it if that record is later "
    "changed or removed."
)


def _with_ref_note(help_text: str) -> str:
    """``help`` for a field the picker populates, keeping whatever guidance it already had."""
    return f"{help_text} {FROM_REF}" if help_text else FROM_REF


def f(key: str, label: str, type: FieldType = T, tier: Tier = S, **kw) -> FieldSpec:
    return FieldSpec(key=key, label=label, type=type, tier=tier, **kw)


def fromref(key: str, label: str, type: FieldType = T, tier: Tier = S, **kw) -> FieldSpec:
    """A field the reference picker hydrates. Identical to :func:`f` but for the help text."""
    kw["help"] = _with_ref_note(str(kw.get("help", "")))
    return FieldSpec(key=key, label=label, type=type, tier=tier, **kw)


def single(
    key: str, name: str, title: str, fields: tuple[FieldSpec, ...], description: str = ""
) -> EntitySpec:
    return EntitySpec(
        key=key,
        name=name,
        cardinality=Cardinality.SINGLETON,
        title=title,
        fields=fields,
        description=description,
    )


def many(
    key: str,
    name: str,
    title: str,
    fields: tuple[FieldSpec, ...],
    label_field: str = "",
    parent: str = "",
    description: str = "",
) -> EntitySpec:
    return EntitySpec(
        key=key,
        name=name,
        cardinality=Cardinality.COLLECTION,
        title=title,
        fields=fields,
        label_field=label_field,
        parent=parent,
        description=description,
    )


# Photograph + caption is the single most repeated shape in the whole registry: the source
# document asks for photographs at fifteen of the twenty-two stages and the report needs a
# caption under every one of them.
def photos(
    key: str = "photos",
    label: str = "Photographs",
    tier: Tier = B,
    caption_label: str = "Photograph caption",
    max_items: int = 0,
    min_items: int = 0,
    help: str = "",
) -> tuple[FieldSpec, FieldSpec]:
    """A gallery and the caption printed under it.

    ``max_items`` DEFAULTS TO 0, WHICH MEANS ``DEFAULT_MAX_ITEMS``, and that default is deliberate
    rather than lazy: widening or narrowing every one of the eighteen galleries in this registry
    from one keyword here would be a silent change to seventeen stages nobody asked about. A
    gallery that has been given a stated ceiling by the owner declares it, and the number then
    reaches BOTH clients as ``maxItems`` — where it is enforced before the picker rather than
    only by the save — because ``coerce_value`` REFUSES an over-long array rather than trimming
    it, and a refusal a designer meets after attaching twenty-five photographs is a refusal they
    meet too late.

    ``min_items`` DEFAULTS TO 0 FOR THE SAME REASON AND WITH A SHARPER EDGE. A ceiling that leaked
    out of this helper refuses a designer's twenty-first photograph on a stage nobody asked about;
    a FLOOR that leaked would make fifteen stages permanently incomplete — the readiness screen of
    every workshop in the country listing photographs nobody ever asked for, with no way out of it
    from the app. It is scored and never validated (``FieldSpec.min_items``), so nothing refuses,
    logs or 422s: the symptom is simply that no workshop can ever be finished. Two galleries
    declare one. ``tests/test_stage_schema.py`` asserts the set by equality, in both directions.

    ``help`` lands on the gallery and not on the caption. The caption's own guidance is its label.
    """
    return (
        f(
            key,
            label,
            IMGS,
            tier,
            report_role=GALLERY,
            max_items=max_items,
            min_items=min_items,
            help=help,
        ),
        f(f"{key}Caption", caption_label, T, tier, caption_for=key, report_role=CAP),
    )


# --------------------------------------------------------------------------------------
# 1. Workshop Setup / Cover Information
# --------------------------------------------------------------------------------------

STAGE_1 = StageSpec(
    number=1,
    key="WORKSHOP_SETUP",
    title="Workshop Setup & Cover Information",
    purpose=(
        "Identify the workshop: what it is called, under which scheme and sanction, for which "
        "craft and cluster, where and when it runs, and who is responsible for it. These "
        "fields become the cover page of every report generated from this record."
    ),
    entities=(
        single(
            "workshopSetup",
            "DwWorkshopSetup",
            "Workshop details",
            (
                f(
                    "workshopTitle",
                    "Workshop title",
                    T,
                    B,
                    required=True,
                    report_role=COVER,
                    max_length=220,
                    help="As written on the sanction order, e.g. “Design & Prototype Development "
                    "Workshop — Sambalpuri Ikat”.",
                ),
                f(
                    "schemeName",
                    "Scheme",
                    T,
                    B,
                    required=True,
                    report_role=COVER,
                    max_length=180,
                    help="The scheme funding the workshop, e.g. National Handicrafts Development "
                    "Programme.",
                ),
                fromref(
                    "craftName", "Craft", T, B, required=True, report_role=COVER, max_length=160
                ),
                fromref(
                    "craftLocalName",
                    "Craft name in the local language",
                    T,
                    S,
                    report_role=COVER,
                    help="Written in the local script. It is printed in the report exactly as "
                    "typed.",
                ),
                f(
                    "craftRef",
                    "Linked craft record",
                    REF,
                    S,
                    ref_model="Craft",
                    ref_scope=ALL_SCOPE,
                    report_role=HIDDEN,
                    help="Choose the craft from the crafts already documented. The cover fields above "
                    "are filled in from it.",
                ),
                # ── WHAT ELSE THE CRAFT RECORD HOLDS, EACH IN A BOX OF ITS OWN ────────────────────
                #
                # The crafts page collects five things and two of them used to cross. These are the
                # other three plus the provenance date and the photograph, and every one of them is a
                # SEPARATE box rather than a value folded into a cover field — which is the whole
                # reason the old refusal was wrong rather than merely narrow. `craftPlace` must not
                # answer the four REQUIRED cover fields above (state/district/block/village) and does
                # not try to; it is the record's own free-text place, exactly as `tool.place` and
                # `existingProduct.place` already carry theirs, so a reader can see the craft was
                # documented somewhere other than this cluster.
                #
                # KEY_VALUE, not COVER_FIELD. The cover page is the sanction order's own facts about
                # THIS workshop; a taxonomy string and a researcher's note from another survey belong
                # in the per-stage block beneath it, where `report_builder` prints KEY_VALUE fields.
                # Adding a seventh COVER_FIELD would also change the cover table's shape in documents
                # already submitted.
                fromref("craftCategory", "Category on the craft record", T, S, max_length=120),
                fromref("craftPlace", "Place on the craft record", T, S, max_length=160),
                # `documentedCraftNotes` and NOT stage 4's `craftIntroduction`: that one is the REQUIRED
                # narrative the DESIGNER writes about the craft as they found it, and this is what a
                # researcher wrote months earlier. Two authors must not share one box — the report
                # prints both and a reader has to be able to tell which is which. Same rule, same
                # wording, as `documentedProcessNotes`.
                fromref("documentedCraftNotes", "Notes from the craft record", LT, S),
                # Provenance of the SOURCE record, so a reader can tell a cover filled from a survey
                # three years ago from one filled last week. Every other reference model carries it.
                fromref("craftDocumentedOn", "Craft documented on", DATE, S),
                # AND UNDER WHOSE STUDY, which `craftDocumentedOn` cannot answer. `craftRef` is
                # ALL_SCOPE, so the linked craft may have been documented in a different cluster by a
                # different study years earlier — legitimate reuse, and only visible if it is printed.
                # The crafts page asks this first, through `WorkshopSelect`, and the workshop discarded
                # it. KEY_VALUE and not a seventh COVER_FIELD, for the reason written above
                # `craftCategory`: the cover page is the sanction order's own facts about THIS workshop.
                fromref(
                    "craftDocumentedAtWorkshop",
                    "Workshop the craft was documented at",
                    T,
                    S,
                    max_length=220,
                ),
                # EVERYTHING ATTACHED THAT IS NOT THE ONE STILL IMAGE BELOW. The crafts page's media card
                # takes images, video, audio notes and documents with no limit; `_reference_photos`
                # resolves one IMAGE. So a craft documented with fifteen loom photographs, a recorded
                # elder's account and a scanned gazetteer page contributed one picture to the report whose
                # cover names it, and nothing said the rest existed. A sentence and never the ids — see
                # `_media_note`. Same type, tier and bound as `traditionalProcess.recordMediaNote`.
                fromref("craftMediaNote", "Media on the craft record", T, S, max_length=200),
                # THE PHOTOGRAPH THAT WAS ALWAYS RESOLVED AND ALWAYS THROWN AWAY. `media_field="craftId"`
                # has been declared on the Craft model since it was written, so the server has always
                # looked a craft's picture up and handed it to the data lambda — which named the
                # parameter `_photo` and dropped it. It is a single IMAGE and not a gallery, so the
                # gallery rule does not apply: there is no designer's own set of craft photographs for
                # a seeded one to overwrite.
                fromref(
                    "craftPhoto", "Photograph on the craft record", IMG, S, report_role=GALLERY
                ),
                fromref(
                    "craftPhotoCaption",
                    "Craft photograph caption",
                    T,
                    S,
                    caption_for="craftPhoto",
                    report_role=CAP,
                ),
                f("clusterName", "Cluster", T, B, required=True, report_role=COVER, max_length=160),
                f("state", "State", T, B, required=True, report_role=COVER, max_length=80),
                f("district", "District", T, B, required=True, report_role=COVER, max_length=80),
                f("block", "Block / Tehsil", T, S, report_role=COVER, max_length=80),
                f("village", "Village / Town", T, S, report_role=COVER, max_length=120),
                f("venue", "Venue", T, B, required=True, report_role=COVER, max_length=220),
                f("startDate", "Start date", DATE, B, required=True, report_role=COVER),
                f("endDate", "End date", DATE, B, required=True, report_role=COVER),
                f(
                    "durationDays",
                    "Duration",
                    INT,
                    S,
                    unit="days",
                    min_value=1,
                    max_value=365,
                    report_role=COVER,
                    help="Leave blank to derive it from the start and end dates.",
                    derived_kind="DAYS_BETWEEN",
                    derived_from=("startDate", "endDate"),
                ),
                f(
                    "designerName",
                    "Designer",
                    T,
                    B,
                    required=True,
                    report_role=COVER,
                    max_length=180,
                ),
                f(
                    "designerInstitution",
                    "Designer’s institution",
                    T,
                    S,
                    report_role=COVER,
                    max_length=180,
                ),
                f(
                    "implementingAgency",
                    "Implementing agency",
                    T,
                    B,
                    required=True,
                    report_role=COVER,
                    max_length=220,
                ),
                f(
                    "sponsor",
                    "Sponsoring body",
                    T,
                    B,
                    required=True,
                    report_role=COVER,
                    max_length=220,
                ),
                f(
                    "sanctionOrderNo",
                    "Sanction order number",
                    T,
                    S,
                    report_role=COVER,
                    max_length=120,
                ),
                f("sanctionOrderDate", "Sanction order date", DATE, S, report_role=COVER),
                f(
                    "workshopCode",
                    "Workshop ID",
                    T,
                    S,
                    report_role=COVER,
                    max_length=60,
                    help="The implementing agency’s own reference for this workshop.",
                ),
                f(
                    "sanctionDocument",
                    "Sanction order document",
                    FILE,
                    A,
                    phase_note="Reviewer: “Phase 2 work”.",
                ),
                f(
                    "venueLocation",
                    "Venue location",
                    GEO,
                    A,
                    phase_note="Reviewer: “Phase 2 work”.",
                    help="Captured from the device once, at the venue.",
                ),
                f(
                    "coverPhoto",
                    "Cover photograph",
                    IMG,
                    S,
                    report_role=GALLERY,
                    help="Printed large on the cover page of the report.",
                ),
                f(
                    "coverPhotoCaption",
                    "Cover photograph caption",
                    T,
                    S,
                    caption_for="coverPhoto",
                    report_role=CAP,
                ),
            ),
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 2. Introductory / Administrative Documentation
# --------------------------------------------------------------------------------------

STAGE_2 = StageSpec(
    number=2,
    key="INTRODUCTORY_ADMIN_DOCUMENTATION",
    title="Introduction & Administrative Documentation",
    purpose=(
        "The prose that opens the report: why the workshop was held, who supported it, what it "
        "set out to deliver. Written once and reused across the agency's reports."
    ),
    # Source document: the Advanced tier of this stage was marked “Phase 2 work”. That is a
    # build-scheduling decision — it shaped nothing here, so there is no stage note to carry it.
    entities=(
        single(
            "introduction",
            "DwIntroduction",
            "Introduction",
            (
                f(
                    "acknowledgement",
                    "Acknowledgement",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                    help="Whom the workshop thanks: the sanctioning office, the cluster, the artisans.",
                ),
                f(
                    "briefIntroduction",
                    "Brief introduction",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                ),
                f("purpose", "Purpose of the workshop", RICH, B, required=True, report_role=NARR),
                f("projectBackground", "Project background", RICH, S, report_role=NARR),
                f("agencyDetails", "DCH / implementing-agency details", RICH, S, report_role=NARR),
                f(
                    "programmeObjectives",
                    "Programme objectives",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One objective per line.",
                ),
                f(
                    "expectedDeliverables",
                    "Expected deliverables",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One deliverable per line.",
                ),
                f(
                    "institutionalText",
                    "Reusable institutional text",
                    RICH,
                    A,
                    report_role=NARR,
                    phase_note="Reviewer: “Phase 2 work”. Standing text an agency repeats across "
                    "workshops; kept here so it can be carried forward.",
                ),
            ),
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 3. Workshop Plan, Participants & Opening
# --------------------------------------------------------------------------------------

STAGE_3 = StageSpec(
    number=3,
    key="WORKSHOP_PLAN_PARTICIPANTS_OPENING",
    title="Workshop Plan, Participants & Opening",
    purpose=(
        "The day-by-day plan, the artisans who took part, the designer's own profile, and the "
        "record of the opening session."
    ),
    # Source document: the Standard tier here was marked “Important for us” — an emphasis on the
    # priority of building it, which changed no field and tells a designer nothing.
    entities=(
        single(
            "workshopPlan",
            "DwWorkshopPlan",
            "Plan & opening",
            (
                f(
                    "designerProfile",
                    "Designer’s profile",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                ),
                f(
                    "designerExperience",
                    "Designer’s experience",
                    INT,
                    S,
                    unit="years",
                    min_value=0,
                    max_value=70,
                ),
                # ── THE REST OF THE DESIGNER'S OWN DETAILS ──────────────────────────────────────────
                #
                # The owner's instruction on 2026-08-25: everything a designer types on the Designer
                # Page is master data and is pre-filled into EVERY report they generate, while staying
                # editable per report. `designers.PREFILL_MAP` is the one place that copying is
                # declared; these are the boxes it copies INTO, and the two lists are checked against
                # each other by `test_every_prefilled_profile_column_has_a_receiving_field`.
                #
                # WHY BOXES AT ALL, RATHER THAN THE REPORT READING THE PROFILE. Because a report is a
                # historical document and the profile is a live row. The full argument is in
                # `designers.prefill_from_profile`'s docstring and it is not repeated here, but the
                # short form is the reason this block exists as twelve ordinary fields: a designer who
                # moves from NIFT to NID in 2027 must not retroactively re-institution the 2026 report,
                # and the only structure that cannot do that is a copy the stage owns.
                #
                # WHY STAGE 3 AND NOT STAGE 1. Stage 1 is the COVER, and its `report_role=COVER` fields
                # are rows of one small table on one page — `designerName` and `designerInstitution` are
                # there because a cover names who ran the workshop and for whom. A designation, a
                # department, a qualification, a specialisation and four lines of postal address are
                # not cover material; they are the designer's profile, which is this stage's stated
                # purpose ("the designer's own profile"). Putting them on the cover would push the hero
                # photograph off page one, which is the exact failure the report preview exists to catch.
                #
                # KEY_VALUE, which is `f`'s default and is therefore not spelled: a label and a value is
                # what every one of these is. NONE is required — a designer who has filled in nothing
                # but their name must still be able to submit, and `validate_registry` refuses a
                # required field above BASIC anyway.
                f(
                    "designerLocalName",
                    "Designer’s name in the local language",
                    T,
                    S,
                    max_length=180,
                ),
                f("designerDesignation", "Designer’s designation", T, S, max_length=180),
                f("designerDepartment", "Designer’s department", T, S, max_length=180),
                f("designerQualification", "Designer’s qualification", T, S, max_length=220),
                f("designerSpecialisation", "Designer’s specialisation", T, S, max_length=220),
                # THE DECLARED SHAPES, for the reason `TextFormat`'s own docstring gives: the report
                # prints the STAGE copy, so a box the record page validates and the stage does not is a
                # malformed value in a ministry document. `max_length` beside the format because the
                # format bounds the DIGITS and not the string — see `participant.phone`.
                f(
                    "designerPhone",
                    "Designer’s phone",
                    FieldType.PHONE,
                    S,
                    max_length=20,
                    text_format=TextFormat.PHONE_IN,
                ),
                f(
                    "designerEmail",
                    "Designer’s email",
                    FieldType.EMAIL,
                    S,
                    max_length=180,
                    text_format=TextFormat.EMAIL,
                ),
                f("designerWebsite", "Designer’s website", FieldType.URL, S, max_length=300),
                f("designerAddress", "Designer’s address", T, S, max_length=300),
                f("designerCity", "Designer’s city", T, S, max_length=120),
                f("designerState", "Designer’s state", T, S, max_length=80),
                f(
                    "designerPincode",
                    "Designer’s PIN code",
                    T,
                    S,
                    max_length=12,
                    text_format=TextFormat.PINCODE,
                ),
                f("designerEmpanelmentNo", "Designer’s empanelment number", T, S, max_length=120),
                f("designerEmpanelmentDate", "Designer’s empanelment date", DATE, S),
                # THE THREE MEDIA BOXES, AND THE ONE CAVEAT THAT COMES WITH THEM.
                #
                # These carry a media id copied from the profile, which is the one thing
                # `design_workshops`' reference hydration explicitly refuses to do ("copying ids onto
                # the entry would either freeze ids the report is not entitled to render, or overwrite
                # the designer's own photographs"). Neither half of that refusal applies here and the
                # difference is worth stating, because the rule is right and a later reader will meet it:
                #
                #   * THE GALLERY HALF DOES NOT APPLY. That rule protects galleries the DESIGNER filled
                #     with workshop photographs from being overwritten by a referenced record's picture.
                #     These three boxes have no other author — the profile is the only thing that ever
                #     writes them — and a designer who wants a different signature on one report
                #     replaces it in the box, which is the per-report editability the instruction asks
                #     for.
                #   * THE ENTITLEMENT HALF IS REAL AND IS HANDLED BY DEGRADING. A referenced record's
                #     files are gated per file, and so are these: a `DesignWorkshopViewer` generating
                #     this report may not be entitled to the bytes of the designer's own photograph.
                #     `MediaResolver` answers None for exactly that case, `report_builder` treats None
                #     as normal, and the file is generated with a warning naming the figure rather than
                #     failing. That is the same outcome as any other unresolvable media id in the
                #     registry, so it needs no new machinery — only this paragraph, so nobody reads the
                #     missing image as a bug in the copy.
                f("designerPhoto", "Designer’s photograph", IMG, S, report_role=GALLERY),
                # ⚠ THIS HELP TEXT ONCE PROMISED THE SIGNATURE BLOCK, AND NOTHING DELIVERED IT.
                #
                # It read "Printed in the signature block of the report." on the day it was added, which
                # was measured false the same week: `report_model.SignatureBlock` carries
                # `signatories: tuple[tuple[str, str], ...]` — a name and a role, two strings — and has no
                # image slot at all, on either side of the wire. So this photograph printed where every
                # other GALLERY field prints, in the stage's own plate, and the sentence under the box
                # described a document nobody could produce.
                #
                # That is the precise failure this module's docstring exists to forbid, arriving through
                # `help` instead of through `notes`: a client-facing string asserting behaviour that no
                # branch of this codebase implements. `report_builder`'s own annexure comment records the
                # same shape of defect — "three surfaces telling a designer the office's copy would carry
                # something no code produced".
                #
                # WHETHER IT *SHOULD* REACH THE SIGNATURE BLOCK IS AN OPEN OWNER DECISION, not a bug to
                # quietly close, and it is a decision about a ministry document. The cheap version is a
                # builder-only move; the honest version adds an image to `SignatureBlock`, which is a
                # `report_model.py` change plus `ReportModel.kt` plus four writers plus a re-pin of the
                # bundled Kotlin asset. Until somebody takes that decision the box says what is TRUE.
                f(
                    "designerSignature",
                    "Designer’s signature",
                    IMG,
                    S,
                    report_role=GALLERY,
                    help="Printed with the report’s photographs, under its own heading. Attach a scan or "
                    "a photograph of your signature.",
                ),
                # ⚠ AND THIS ONE PROMISED AN ANNEXURE. Same defect, same day, same correction.
                #
                # It read "Attached to the report as an annexure", and no annexure in this product carries
                # a FILE. `report_annexures` is transcripts only — an AUDIO field resolved to
                # `MediaFile.transcriptText` — and `_render_media_annexure` gathers through `_images`,
                # which admits IMAGE and IMAGE_LIST and nothing else. `report_templates` records the
                # refusal as a DELIBERATE decision with its two reasons written out, so this was not a
                # gap waiting to be filled; it was a sentence contradicting a settled design.
                #
                # WHAT ACTUALLY HAPPENS, which is what the box now says: `format_value`'s media branch
                # prints the field's label and a count — "1 document attached" — because a `FILE` field
                # declares no `report_role` and therefore defaults to KEY_VALUE. The bytes do not travel,
                # and `build_report` now says so out loud in `warnings`: "attached file(s) are named in
                # this report but the files themselves are not inside it … send them alongside it."
                #
                # So the designer is told the truth twice: here, before they upload, and in the warnings
                # beside the generated file. Neither sentence claims the ministry's copy contains the CV.
                #
                # ⚠ AND THE PREVIEW CLAUSE OVER-PROMISED FOR TWO OF THE THREE FORMATS IT NAMES.
                #
                # It read "PDF, .docx or .odt. Previewable in the app." Both previews draw a PDF and
                # NOTHING ELSE, decided by a literal mime test with one filename fallback:
                # `DocumentPreview.tsx`'s `isRenderablePdf` on the web, `looksLikePdf` in
                # `DwDocumentPreview.kt` on the handset. Both return false for a .docx or an .odt, and
                # both then render a download card whose own words are "Stored and downloadable. Only a
                # PDF can be shown inside the app." So a designer who uploaded a .docx CV was promised a
                # preview by the box and contradicted by the card directly under it — one capability
                # describing itself two ways on one screen.
                #
                # THE PREVIEW CLAUSE IS NOW WORD FOR WORD `surveyDocument`'S AT STAGE 8, deliberately.
                # That field is the same FILE capability with the same two previews behind it and it
                # already said this correctly; the rest of this string differs because the CV carries the
                # report-naming clause too. Two wordings for one capability is how they drift: the next
                # correction has to be made twice, and the one nobody remembers is the one a designer
                # reads. If the preview ever admits a second format, both clauses change together.
                f(
                    "designerCv",
                    "Designer’s CV",
                    FILE,
                    S,
                    help="PDF, .docx or .odt. A PDF is previewable in the app; other formats are stored "
                    "and downloadable. The report NAMES it rather than carrying it, so send the "
                    "file alongside the report.",
                ),
                f("openingNote", "Opening note", RICH, S, report_role=NARR),
                f(
                    "officialsPresent",
                    "Officials present at the opening",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One official per line, with designation.",
                ),
                f(
                    "totalArtisans",
                    "Artisans enrolled",
                    INT,
                    S,
                    min_value=0,
                    report_role=METRIC,
                    help="Leave blank to count the participant list below.",
                ),
                f(
                    "womenParticipants",
                    "Women participants",
                    INT,
                    S,
                    min_value=0,
                    report_role=METRIC,
                ),
                *photos("openingPhotos", "Opening photographs", B, "Opening photograph caption"),
                *photos("eventPhotos", "Event photographs", S, "Event photograph caption"),
            ),
        ),
        many(
            "workshopScheduleDay",
            "DwWorkshopScheduleDay",
            "Workshop schedule",
            (
                f(
                    "dayNumber",
                    "Day",
                    INT,
                    B,
                    required=True,
                    min_value=1,
                    report_role=COL,
                    column_width_pct=8.0,
                ),
                f("date", "Date", DATE, B, report_role=COL, column_width_pct=14.0),
                f(
                    "session",
                    "Session",
                    T,
                    S,
                    report_role=COL,
                    column_width_pct=18.0,
                    help="Forenoon, afternoon, full day.",
                ),
                f(
                    "activity",
                    "Activity",
                    LT,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=45.0,
                ),
                f("conductedBy", "Conducted by", T, S, report_role=COL, column_width_pct=15.0),
            ),
            label_field="activity",
        ),
        many(
            "participant",
            "DwParticipant",
            "Participating artisans",
            (
                # THE ROSTER IS SELECTED, NOT TYPED. This is the picker the whole cascade hangs off:
                # every artisan-shaped dropdown later in the workshop (a prototype's maker, a
                # certificate's recipient) reads the roster rather than the artisan table, and the
                # product pickers at stages 6 and 13 are narrowed by whichever artisan is chosen
                # here. Typing thirty names in by hand produced thirty rows with no join key, which
                # is why a cluster's second workshop could never be compared with its first.
                #
                # ALL, deliberately, and the ONE artisan field in the registry that is not scoped to
                # the workshop: this is where the roster is built. Scoping the roster picker to the
                # roster would be circular, and would make the artisan who walks in on day two
                # unaddable.
                f(
                    "artisanRef",
                    "Artisan record",
                    REF,
                    B,
                    ref_model="Artisan",
                    ref_scope=ALL_SCOPE,
                    report_role=HIDDEN,
                    help="Search the documented artisans and choose one. The details below are "
                    "filled in from that record; anything the record does not have can still be "
                    "typed in.",
                    phase_note="Reviewer: “Phase 3 — may help for networking artisans”.",
                ),
                f("serialNo", "S. No.", INT, B, min_value=1, report_role=COL, column_width_pct=7.0),
                fromref(
                    "name",
                    "Artisan name",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=24.0,
                ),
                fromref("localName", "Name in the local language", T, S),
                fromref("gender", "Gender", T, S, max_length=20),
                # AGE IS DERIVED FROM A DATE OF BIRTH, NEVER STORED AS A NUMBER — which is the fix the
                # paragraph that used to stand here was asking for, and it has since landed. That
                # paragraph said "`Artisan` has no age column … the fix is a `dateOfBirth` column on
                # Artisan and a derivation": `Artisan.dateOfBirth` and `Artisan.experienceYears` both
                # exist now, the artisan form collects both, and `REFERENCE_MODELS["Artisan"].data`
                # calls `derive_age(r.dateOfBirth)`. It is left recorded rather than deleted because a
                # comment describing a gap that has been closed is worse than no comment: the next
                # reader plans work around it, which is precisely the rot this file's own citation rules
                # were written to keep out.
                #
                # An age is stored NOWHERE because an age written down in 2024 is wrong in 2026 and
                # nothing would ever say so. The date is the fact; the number is a view of it.
                #
                # THE LEGACY `extraMetadata` READ SURVIVES BEHIND THE DERIVATION and is not dead code —
                # see the note on the `age` key in `REFERENCE_MODELS["Artisan"].data` for why the
                # migration deliberately refused to guess at "30+" and "about 30", and why those rows
                # are the oldest and most thoroughly documented ones.
                fromref(
                    "age",
                    "Age",
                    INT,
                    S,
                    unit="years",
                    min_value=10,
                    max_value=110,
                    help="Recorded against the artisan when they were documented.",
                ),
                # THE PM VISHWAKARMA CARD, MASKED. See the note beside `pehchanCardNumber` in
                # `REFERENCE_MODELS["Artisan"]`: the number arrives as "XXXX XXXX 3456" because a
                # design workshop's stage reads do not pass through the API's identity masking and a
                # workshop viewer is a grantee — the exact hole `record_fields.py` records having
                # closed once already. A designer entitled to the full number can still type it over
                # the mask; only-fill-blanks then leaves their answer alone.
                #
                # NO `store_masked` HERE, WHICH IS A DECISION AND NOT AN OVERSIGHT — and it is stated at
                # this field because `aadhaarNumber` below DOES declare it, so the two boxes on one row
                # keep different amounts of what a designer types and a reader is entitled to know which
                # is which. What is masked here is the CARRY: the value hydration copies in. What a
                # designer types stays whole, because this is the field
                # `IdentityCardCapture kind="PEHCHAN"` was built to fill — the never-stored camera route
                # on both clients, whose whole purpose is to write the number off the card in the
                # designer's hand. Masking it would un-ship that control. The Aadhaar's masking follows
                # an explicit owner decision about the Aadhaar (2026-08-24, below); no such decision has
                # been made about the Pehchan card number, and this line must not be read as one.
                #
                # `max_length=32` AND STILL NO `text_format`, WHICH IS TWO DECISIONS AND NOT ONE.
                # The format stays off for the reason argued at `_pehchan_format_error`: `pehchan_error`
                # ACCEPTS "XXXX XXXX 3456" — fourteen alphanumerics, inside 4-32, no checksum to fail —
                # so `PEHCHAN` on this box would be a no-op that looks like protection, and attaching it
                # needs a mask-aware predicate and an owner call about `IdentityCardCapture
                # kind="PEHCHAN"`. NONE OF THAT IS A REASON TO LEAVE THE BOUND OFF. `records.py` holds
                # this same fact to 4-32 characters through `validate_pehchan` (a named 422), and this
                # copy — a TABLE_COLUMN at 16% of a printed roster — took any length at all. 32 is the
                # record path's own ceiling, `PEHCHAN_MAX_LENGTH`, so the two surfaces now bound one
                # fact identically; the hydrated mask is fourteen characters and sits well inside it.
                fromref(
                    "artisanCardNo",
                    "Artisan ID / card number",
                    T,
                    S,
                    report_role=COL,
                    column_width_pct=16.0,
                    max_length=PEHCHAN_MAX_LENGTH,
                    help="The Artisan Pehchan Card number. Shown masked to its last four digits "
                    "when it is filled in from the linked record.",
                ),
                fromref(
                    "pehchanCardAvailable",
                    "Holds an Artisan Pehchan Card",
                    BOOL,
                    S,
                    help="Whether the artisan is enrolled under PM Vishwakarma.",
                ),
                # ── THE AADHAAR, MASKED — A REVERSAL, RECORDED RATHER THAN A DELETION ───────
                #
                # DECIDED BY THE OWNER ON 2026-08-24, and this paragraph exists because the decision
                # REVERSES one this repository had already argued out and written down. The note beside
                # `pehchanCardNumber` in `REFERENCE_MODELS["Artisan"]` read, in full: "`aadhaarNumber`
                # is NOT carried at any masking. It is the deduplication key, it is governed, and
                # 'XXXX XXXX 9012' in a design report's participant table answers no question the
                # report asks — the artisan is identified by name, Pehchan card and phone." THAT
                # REASONING IS KEPT — at its own site and here — because a decision whose earlier
                # argument has been deleted reads as though it was never in doubt, and the next reader
                # cannot then tell a considered reversal from a widening nobody thought about.
                #
                # WHAT THE OWNER WAS SHOWN BEFORE DECIDING, written down so that "they were not told"
                # is not available to anybody re-opening this:
                #   * a design workshop's stage reads do NOT pass through `records._redact_sensitive`,
                #     so nothing downstream re-masks this value on the way out;
                #   * a `DesignWorkshopViewer` is a grantee, so the audience is wider than the designer
                #     who typed the row;
                #   * hydration copies at SAVE time and the report never re-resolves, so the entry is a
                #     PERMANENT copy — clearing `Artisan.aadhaarNumber` afterwards does not retract it
                #     from one `DwStageEntry.data` or one generated document.
                # They chose to carry it anyway, masked to its last four digits. Both identity numbers
                # now cross on the same terms, which is the one thing the previous split could not
                # claim: one artisan's identity reads identically on every surface.
                #
                # WHAT WOULD REVERSE IT AGAIN, so a future owner has a procedure and not an argument:
                # delete this FieldSpec, the `"aadhaarNumber"` key from
                # `REFERENCE_MODELS["Artisan"].data`, the pair from
                # `stage_schema.REFERENCE_HYDRATION["participant.artisanRef"]` and the same pair from
                # `frontend/lib/designWorkshops.ts`'s `DW_REFERENCE_HYDRATION`, then regenerate the
                # Android bundled asset. THAT DOES NOT RETRACT WHAT IS ALREADY WRITTEN: hydration
                # copied at save time, so every entry saved while this field existed keeps its masked
                # number and every report built from one still prints it. A reversal stops the next
                # copy; it does not undo the copies already made.
                #
                # KEY_VALUE, EXPLICITLY, AND IT MUST NEVER BECOME A TABLE_COLUMN. The same hard rule as
                # `craftStartDate` above and the address boxes below: the six TABLE_COLUMN widths on
                # this entity already sum to exactly 100. Read the paragraph at `craftStartDate` for
                # what a seventh actually does — `_table_columns` slices `columns[:6]` in DECLARATION
                # order and the proportional fallback fires only when those six miss 100±0.5, so an
                # appended seventh is DROPPED from the table while one declared before an existing
                # column pushes the sixth out and re-lays-out a participant table that is already
                # inside submitted documents. This field is declared before `specialisation`, so it is
                # the second of the two. Pinned
                # by `test_no_new_table_column_was_added_to_a_table_whose_widths_are_already_full`
                # (which sums ALL of the entity's TABLE_COLUMNs and so catches either position), and
                # named again AT THE FIELD by
                # `test_the_carried_aadhaar_is_a_mask_a_key_value_and_never_a_column` — a generic width
                # sweep would let a later reader think the role here was incidental.
                #
                # THE KEY IS `aadhaarNumber`, AND THAT SPELLING IS A SAFETY PROPERTY rather than
                # symmetry for its own sake. `records._IDENTITY_KEYS` is `("aadhaarNumber",
                # "pehchanCardNumber")` and `_redact_sensitive` walks nested dicts BY KEY NAME, so if a
                # stage entry ever reaches `public_encode` the value is masked again. That second pass
                # is a no-op and not a corruption because `mask_identity_number` is idempotent on its
                # own output — `normalize_aadhaar` strips only separators, so "XXXX XXXX 9012" becomes
                # "XXXXXXXX9012" becomes "XXXX XXXX 9012". Any other name sails straight through the
                # walk, which is why the obvious-looking `aadhaarMasked` would have been worse.
                #
                # LABELLED SO THE TWO MASKED ROWS CANNOT BE CONFUSED, which the owner raised as the
                # readability risk in the same breath as the decision: two rows reading
                # "XXXX XXXX ####" told apart only by their labels is how the wrong one gets checked
                # against the wrong card. This label shares NO WORD with `artisanCardNo`'s "Artisan ID
                # / card number", the help text names that other box explicitly, and the two are not
                # even in the same region of the printed page — the Pehchan is a TABLE_COLUMN in the
                # roster table and this is a KEY_VALUE line in the per-row block beneath it. There is
                # deliberately NO "(last four)" in the label: the mask says that itself, and the label
                # would become a lie the moment a designer types the full number in over it.
                #
                # ADVANCED, NOT STANDARD, and it is the one tier decision here worth a sentence.
                # `COMPACT_SUMMARY` is the one report template whose `max_tier` is not ADVANCED, so a
                # national identity number stays out of the short review-meeting document — and
                # `report_builder._omitted_by_tier` TELLS the designer it was left out, so nothing goes
                # silently missing. Both clients still offer the box regardless of tier: the web's
                # mirrored group is deliberately not tier-split, and Android puts it behind
                # "More detail".
                #
                # max_length=20 AND NOT 14. The mask is 14 characters; a full number typed spaced or
                # hyphenated is 14; bare digits are 12. The slack is chosen NOW, while nothing is
                # stored, for the reason written out above `pincode`: `validate_entry` re-coerces EVERY
                # field on EVERY save, so a bound that looks tight-and-tidy today becomes a refused
                # answer on a box the designer never touched, the first time somebody types a spelling
                # it did not expect.
                #
                # DECLARED AFTER `artisanCardNo`, AND THE ORDER IS LOAD-BEARING ON THE WEB.
                # `stageFieldRoles.identityNumberField` returns the FIRST non-deprecated TEXT field
                # whose key or label matches its identity pattern, and `FieldInput` mounts
                # `IdentityCardCapture kind="PEHCHAN"` on exactly that one. Declaring this field BEFORE
                # `artisanCardNo` would silently move the Pehchan card reader onto the Aadhaar box.
                # ANDROID, BY CONTRAST, MATCHES PER FIELD: `DwIdentityOcr.isIdentityNumberField` will
                # offer the camera on this box too and `identityKindFor` returns AADHAAR for it, so the
                # handset's on-device Verhoeff-checked recogniser can offer a FULL twelve digits here in
                # one tap. That is the registry-as-data design working as intended, and it is written
                # down here rather than left to be discovered by whoever next reads an entry.
                #
                # `store_masked=True`, AND IT IS WHAT MAKES THE OWNER'S DECISION TRUE OF THE COLUMN
                # RATHER THAN OF ONE CODE PATH. This paragraph used to end "it is not a new class of
                # exposure — the box is hand-typeable by design", and that sentence was doing work it
                # could not do. The decision was "both identity numbers cross MASKED"; what the code
                # guaranteed was that the value the server's own hydration wrote was masked, while
                # anything a client wrote afterwards was kept verbatim — a bare twelve digits from the
                # handset's card reader, or from the help text's own invitation to type it in, landing
                # permanently in `DwStageEntry.data` on a surface whose stage reads never pass through
                # `records._redact_sensitive`, readable by every `DesignWorkshopViewer` grantee, in an
                # entry nothing re-resolves. "It was already hand-typeable" is an argument about how the
                # digits get there, not about whether they are kept. `coerce_value` now masks this
                # field's value on every save (see `FieldSpec.store_masked`), so the four digits the
                # report was ever going to print are the whole of what is stored — and the designer can
                # still fill the box, which is the other half of the same instruction.
                #
                # `artisanCardNo` IS DELIBERATELY NOT DECLARED THIS WAY. The Pehchan card number has a
                # capture control built for it on both clients (`IdentityCardCapture kind="PEHCHAN"`,
                # and the same reader on Android) whose entire purpose is to write the full number off
                # the card a designer is holding; masking it would un-ship that control rather than
                # enforce a decision, and no decision has been made about it. So the two boxes on this
                # row genuinely behave differently — the Pehchan keeps what is typed, the Aadhaar keeps
                # four digits of it — and that asymmetry is stated at both fields rather than left to
                # look like one of them was forgotten.
                #
                # REPOINTING `artisanRef` DISCARDS A TYPED NUMBER, and this is the one path that does.
                # `hydrate_entries` fills blanks only, so a full number a designer typed survives every
                # ordinary save — but when the REF is pointed at a DIFFERENT artisan every mapped target
                # is cleared first, this one included, and refilled from the new record's mask. That is
                # correct and identical for all 26 pairs (a name kept from the previous artisan would be
                # far worse), and it is written down because this is the only field where the value being
                # discarded may have been supplied by hand from a card rather than copied by the server.
                # -- `text_format=AADHAAR`: THE OTHER HALF OF THE MASK, AND WITHOUT IT THE MASK WAS
                #    THE DEFECT -----------------------------------------------------------------------
                #
                # `store_masked` alone is `mask(anything)`. `mask_aadhaar` strips separators and keeps
                # the last four characters of WHATEVER it is handed, so for one revision a typed
                # "hello world 1234" — fourteen characters, comfortably inside the bound above — was
                # stored as "XXXX XXXX 1234" and printed as a national identity number in a document
                # submitted to a ministry. Nothing downstream could tell it from a real one, and the
                # masking is what made it undetectable: the value LOOKS like the thing the decision
                # promised. `validate_registry` now refuses `store_masked` without this format for
                # exactly that reason, so the pairing cannot come apart again.
                #
                # THE PREDICATE IS MASK-AWARE, WHICH IS THE WHOLE DIFFICULTY. `hydrate_entries` writes
                # the mask into this box and `validate_entry` re-coerces every field on every save, so
                # a check that knew only `aadhaar_error` would answer "must be 12 digits" on this box
                # on EVERY save, for ever, on a row nobody touched — naming a fault the designer
                # cannot fix because the digits are not theirs to see. `aadhaar_or_mask_error` accepts
                # the mask BY SHAPE ("XXXXXXXX" plus four digits, or twelve X's) and not by the
                # presence of an X: `is_masked_aadhaar`'s "an X anywhere" is right where a masked
                # value is DROPPED before the write (`ArtisanUpdate`) and catastrophic here, where
                # whatever passes is STORED and then masked.
                #
                # THE DESIGNER CAN STILL FILL THE BOX, which is the second half of the owner's
                # instruction and the reason this is a format and not a refusal of full numbers: a
                # real twelve digits is accepted and then masked, exactly as before. What is refused
                # is only what was never an Aadhaar number.
                fromref(
                    "aadhaarNumber",
                    "Aadhaar number",
                    T,
                    A,
                    report_role=KV,
                    max_length=20,
                    store_masked=True,
                    text_format=TextFormat.AADHAAR,
                    help="The artisan’s Aadhaar number — NOT the Artisan Pehchan Card "
                    "number, which is the “Artisan ID / card number” box above. "
                    "Only the last four digits are ever stored here, as “XXXX XXXX 9012”: "
                    "that is what is filled in from the linked record, and if you type the "
                    "full number in yourself it is masked the same way when the stage is "
                    "saved. The report prints those four digits, and nothing else of it.",
                ),
                fromref(
                    "specialisation", "Specialisation", T, S, report_role=COL, column_width_pct=23.0
                ),
                # ── EXPERIENCE, AND THE DATE IT IS NOW DERIVED FROM ───────────────────────────────
                #
                # THE SAME MOVE AS `age` ABOVE, ONE ANSWER LATER, and made for the same reason on the
                # owner's own instruction: "experience should also be a derived field, with a field to
                # enter the date of joining the craft serving as the feeder." `Artisan.craftStartDate`
                # exists as of 2026-08-23 and `REFERENCE_MODELS["Artisan"].data` derives this number
                # from it in preference to the stated `Artisan.experienceYears` column — see the
                # three-step precedence written out at that key, and `derive_experience_years`.
                #
                # THE NUMBER IS STILL AN INT AND STILL TYPEABLE, and that is not an oversight in a field
                # the owner asked to have derived. Two facts decide it. The first is that this box is a
                # TABLE_COLUMN in a submitted report: every row written before the column existed
                # carries a stated number or a legacy "30+" and no date at all, and a box that refused
                # to hold their answer would print blank in the participant table for exactly the oldest
                # and best-documented artisans — the regression 20260816170000 was written to end. The
                # second is that the derivation is not a registry derivation and cannot be one — and the
                # reason for THAT changed with this very change, so it is restated rather than left as it
                # stood. It used to read "this value comes from a COLUMN ON ANOTHER MODEL, not from a
                # sibling field on this entry", which was true while `age` was the only derived carry.
                # `craftStartDate` IS a sibling field on this entry as of 2026-08-23 (declared below), so
                # that sentence no longer decides anything. Two facts that do:
                #   * ORDERING. `save_stage` evaluates `derived_kind`/`derived_from` BEFORE it hydrates,
                #     so on the save where the designer first picks the artisan the sibling date is not in
                #     the entry yet, the derivation computes None, and anybody who submitted immediately
                #     prints a blank in the participant table. The reference hydration has no such
                #     ordering problem, which is why `age` has worked this way since 20260816170000.
                #   * THERE IS NO SUCH DERIVATION KIND. `stage_schema` offers DAYS_BETWEEN, PRODUCT and
                #     SUM; whole years between a date and today is none of them, and inventing YEARS_SINCE
                #     means a new kind in the server, in `lib/derivedFields`, in the handset's `DwDerived`
                #     and in the bundled asset — four ports of a rule whose answer changes with the
                #     calendar, to describe a value the hydration already copies correctly.
                #
                # WHAT THAT LEAVES, STATED BECAUSE IT IS A REAL HAZARD AND NOT A THEORETICAL ONE: this
                # entity now carries TWO EDITABLE BOXES over one fact — the number here and the date
                # below — and nothing keeps them consistent. Both are ordinary mirrored inputs
                # (`splitMirroredFields`), hydration only ever fills BLANKS, and `validate_entry` coerces
                # field by field with no cross-field rule, so a designer who corrects one box leaves the
                # other standing and the report prints "Experience 32" above "Practising since 12 March
                # 1994". `age`/`dateOfBirth` cannot fail this way for one reason only — there is no
                # birthday box on `participant` — so the asymmetry that made carrying the date right here
                # is the same asymmetry that makes this possible. The help text on both boxes says which
                # number is worked out from what, which is the only defence a form can offer against it
                # short of a cross-field validator neither client has.
                #
                # THE BOUNDS ARE UNCHANGED AND ARE MIRRORED IN THREE PLACES ON PURPOSE — here,
                # `ArtisanCreate.experienceYears` (ge=0, le=90) and the artisan form's number box.
                # `derive_experience_years` returns None outside the same 0..90 rather than a number,
                # because `validate_entry` re-coerces EVERY field on EVERY save and an out-of-range
                # hydrated value would become a refused answer on a box nobody typed in.
                fromref(
                    "experienceYears",
                    "Experience",
                    INT,
                    S,
                    unit="years",
                    min_value=0,
                    max_value=90,
                    report_role=COL,
                    column_width_pct=12.0,
                    help="Worked out from the artisan record's “practising since” date when it has "
                    "one, and read from the number stated on that record when it does not. "
                    "Both are printed in this report, so if you change this by hand, correct "
                    "the date beside it too.",
                ),
                # THE FEEDER, CARRIED SO THE REPORT CAN SAY WHAT THE NUMBER BESIDE IT IS DERIVED FROM.
                # A derived figure in a document a ministry officer reads whose basis is stated nowhere
                # is the shape this repository keeps arguing against — the same job `documentedOn` does
                # for a roster row, one column over.
                #
                # KEY_VALUE, EXPLICITLY, AND IT MUST NEVER BECOME A TABLE_COLUMN. The six TABLE_COLUMN
                # widths on this entity already sum to exactly 100.
                #
                # WHAT A SEVENTH ACTUALLY DOES, MEASURED IN `report_builder` RATHER THAN ASSUMED — this
                # paragraph used to say "pushes past the six-column cap onto the proportional fallback",
                # which describes only one of the two outcomes and not the one an appended field gets.
                # `_table_columns` takes the FIRST SIX non-media TABLE_COLUMN fields in DECLARATION
                # order (`columns[:6]`) and the fallback fires only when THOSE SIX fail to sum to
                # 100±0.5. So where the seventh is declared decides which silent failure you get:
                # declared AFTER `isMasterCraftsperson` it is sliced off and printed NOWHERE — captured,
                # counted towards completeness, absent from the document — and declared BEFORE any of
                # the existing six it pushes the sixth out of the slice, leaves the survivors summing to
                # less than 100, and re-lays-out a participant table that is already inside submitted
                # documents. This field is declared mid-list, so it is the second. `report_builder`
                # prints KEY_VALUE fields in the per-row block beneath the table, so nothing declared
                # here is lost either way. Pinned by
                # `test_no_new_table_column_was_added_to_a_table_whose_widths_are_already_full`, which
                # sums ALL of the entity's TABLE_COLUMNs and therefore catches both positions.
                #
                # A DATE AND SO NO `unit`, which is the fourth of this file's carry invariants rather
                # than a detail: a unit-less source column must not land in a box that declares one, and
                # the reverse — "1994-03-12 years" — is the same error read aloud.
                # AND THE HELP TEXT DOES NOT PROMISE A SELF-UPDATING REPORT, because this one did and
                # the architecture two lines above forbids it. It read "so it stays right in next year's
                # reading of this report", which is true of the RECORD pages — `record_fields` re-derives
                # on every draw — and false of the document: hydration copies at SAVE time and the report
                # never re-resolves (`report_builder`, `ReferencedRecord`), so `participant.experienceYears`
                # is frozen on the day the artisan was picked. A workshop saved in 2026 and read in 2030
                # prints "Experience 32" beside "Practising since 12 March 1994", which is 36 years — a
                # self-contradicting row this table could not produce before the date crossed at all.
                # The real gain is narrower and is worth stating exactly: the frozen number is now correct
                # as of the SAVE, where before it was whatever was typed on the artisan's `recordedAt`.
                fromref(
                    "craftStartDate",
                    "Practising since",
                    DATE,
                    S,
                    report_role=KV,
                    help="The date the artisan began practising the craft. The experience above is "
                    "worked out from it: on the artisan's own record every time it is read, and "
                    "here on the day the artisan was picked — a report already handed in never "
                    "changes afterwards.",
                ),
                f(
                    "isMasterCraftsperson",
                    "Master craftsperson",
                    BOOL,
                    S,
                    report_role=COL,
                    column_width_pct=18.0,
                    help="MCP status as recognised by the implementing agency.",
                ),
                fromref("village", "Village", T, S),
                # ── THE REST OF THE STATED ADDRESS ────────────────────────────────────────────────
                #
                # KEY_VALUE, every one of them, and that is not a preference. The six TABLE_COLUMN
                # widths declared above this point already sum to exactly 100; a seventh would push
                # `report_builder._table_columns` past its six-column cap onto the proportional
                # fallback and silently re-lay-out a participant table that is already in submitted
                # documents. `report_builder` prints KEY_VALUE fields in the per-row block beneath the
                # table (:1194-1213), so nothing declared here is lost — see the same argument written
                # out at `processStep.documentedFor` and `tool.toolFamily`.
                #
                # STATED, not provenance. `_subject_point` explains why the device's own GPS fix never
                # crosses: on this database every one of those fixes is the desk the record was typed
                # at, 1,500 km from the village on the same row.
                fromref("district", "District", T, S, max_length=80),
                fromref("state", "State", T, S, max_length=80),
                # ── WHY THESE THREE ARE STILL FREE TEXT, AND WHY max_length IS STILL 10 ───────────────
                #
                # Written here once for all nine address boxes in the registry (this entity's three,
                # `workshopSetup.state`/`district`, and the `record…` boxes on `tool` and
                # `existingProduct`), because the next reader will look at the record page's closed
                # dropdowns and its six-digit clamp and ask why the workshop does not match them.
                #
                # THE ANSWER IS `validate_entry`, WHICH RE-COERCES EVERY FIELD ON EVERY SAVE — not only
                # the ones the client just changed. It walks `entity.fields` and calls `coerce_value` on
                # `data.get(spec.key)` for each, so tightening a declaration does not merely constrain
                # NEW answers: on the next save of a row that already holds a value the new declaration
                # refuses, `coerce_value` returns an error, the field is dropped from `cleaned`, and the
                # designer is shown a refused answer on a box they never touched. (`save_stage` does
                # restore the stored value from `previous`, so nothing is destroyed — but the refusal is
                # reported and counted in `refusedAnswers`.)
                #
                # So both "improvements" are regressions on data that already exists:
                #  * ENUM. `coerce_value`'s ENUM branch refuses a token that is not a member, and
                #    hydration has been writing `Artisan.gender` and the canonical state names into these
                #    TEXT boxes verbatim. A designer's "Rajastan" — or, for gender, the "Female" that
                #    every hydrated roster row in the database holds — becomes a refused answer, and both
                #    clients draw an EMPTY dropdown over a filled-in value they cannot match. That is the
                #    `toolType` lesson, written out above `tool.toolFamily`, and it is why the countable
                #    answer gets a box of its own there rather than replacing the sentence.
                #  * max_length. An Indian PIN is six digits and this says ten, which is laxer than the
                #    column it copies from — but a value already stored as "768 029" (seven characters,
                #    and typed exactly that way by somebody reading a card aloud) would start being
                #    refused on a stage the designer is trying to submit.
                #
                # WHAT WOULD MAKE EITHER SAFE, so this is a deferral and not a verdict: a grandfathering
                # clause in `coerce_value` (a promoted field accepts a value that unambiguously names one
                # member's LABEL, exactly as the RICH_TEXT branch accepts a plain string) landing in the
                # SAME change as the client half that keeps a stored value visible in its own dropdown.
                # Until then the honest box is the loose one. I could not check what is actually stored in
                # these boxes today: the compose stack is down, so there is no Postgres here to run the
                # DISTINCT over `DwStageEntry.data` that would settle it.
                # `text_format=PINCODE` REUSES `address.pincode_error` — six ASCII digits, never a
                # leading zero, and the same three sentences a researcher reads on the record page.
                # It is checked against the NORMALISED value, which is what makes it safe to declare
                # on a box the paragraph above says already holds "768 029": separators come off
                # before the check and the stored string is left exactly as typed, so nothing already
                # in this column changes shape or starts being refused. `max_length=10` stays for the
                # same reason it was chosen.
                fromref("pincode", "PIN code", T, S, max_length=10, text_format=TextFormat.PINCODE),
                fromref("address", "Address", LT, S),
                fromref(
                    "subjectLocation",
                    "Location of the artisan’s place",
                    GEO,
                    A,
                    help="The pin a researcher dropped on the artisan’s own place, not the "
                    "device’s position when the record was typed.",
                ),
                # -- THE TWO CONTACT BOXES, WHICH UNTIL NOW WERE CHECKED BY NOBODY ----------------
                #
                # `FieldType.EMAIL` and `FieldType.PHONE` were TYPES WITHOUT RULES. Both fell into
                # `coerce_value`'s one scalar-text arm beside TEXT — clean, strip, `max_length` — so
                # the server checked nothing, on this path or on the record path, and the record
                # page's own validators (a regex in `ArtisanForm.tsx`, an inline rule in
                # `PhoneField.tsx`) governed only the record page. The mirrored stage box is rendered
                # OUTSIDE the record form, so even the browser's `type="email"` never fired: no
                # submit event ever crosses it. What the REPORT prints is this copy.
                #
                # `max_length=254` ON THE EMAIL, AND IT IS NEW. This field declared no bound at all,
                # so it was unbounded — a format is a shape and not a length. 254 is the longest an
                # RFC-conformant address can be. Chosen NOW, while nothing is stored, for the reason
                # written out above `pincode` and at `aadhaarNumber`: `validate_entry` re-coerces
                # EVERY field on EVERY save, so a bound added later becomes a refusal on a box the
                # designer never touched.
                #
                # `PHONE_IN` NAMES THE COUNTRY BECAUSE THE RULE IS ABOUT THE COUNTRY: +91 takes
                # exactly ten digits, any other dial code four to fourteen — the record page's rule
                # and the handset's, verbatim, sentences included. Its port on the server
                # (`contact_formats.split_phone`) keeps the clients' legacy arm, "bare numbers are
                # Indian nationals": `Artisan.phone` has never had a server-side rule, this box is
                # hydrated from it, and a rule that read a bare ten digits as malformed would refuse
                # every legacy roster row on its next save.
                #
                # `max_length=20` ON THE PHONE, AND IT IS THE SAME ARGUMENT THE EMAIL'S 254 IS. A
                # format is a SHAPE and not a length, and `phone_error` measures the DIGITS: it strips
                # every non-digit before counting, so "+91 9876543210 call his son Ramesh on the
                # landline instead" is ten digits and was accepted, unbounded, into a column the
                # participant roster prints. 20 is the longest thing either picker can compose —
                # `\+\d{1,4} \d{4,14}`, which is the mirror input's own pattern on the record page — so
                # a value that passes the format can never fail this bound, and what the bound catches
                # is only what arrived from somewhere with no control in front of it. The other half of
                # that hole is closed by `contact_formats.phone_is_number_shaped`, because a bound
                # alone does not refuse "abc9876543210def".
                fromref(
                    "email",
                    "Email",
                    FieldType.EMAIL,
                    S,
                    max_length=254,
                    text_format=TextFormat.EMAIL,
                ),
                fromref(
                    "phone",
                    "Phone",
                    FieldType.PHONE,
                    S,
                    max_length=20,
                    text_format=TextFormat.PHONE_IN,
                ),
                # THE MOST USEFUL THING ON THE ARTISAN RECORD TO SOMEBODY STANDING IN THE ROOM, and it
                # reached nothing. `dos`/`donts` are newline-separated, numbered guidance a researcher
                # wrote about how to work with THIS artisan — a positive prompt and a negative one.
                # BULLETS rather than KEY_VALUE so the renderer prints them as the list they were
                # typed as instead of one paragraph with the numbers still inside it.
                fromref(
                    "dos",
                    "Do’s",
                    LT,
                    S,
                    report_role=BULLETS,
                    help="Guidance recorded against this artisan. One point per line.",
                ),
                fromref("donts", "Don’ts", LT, S, report_role=BULLETS, help="One point per line."),
                # `recordNotes` and not `notes`: this is what the RESEARCHER wrote on the artisan
                # record, and a box called "Notes" on a workshop roster row would be read as somewhere
                # for the DESIGNER to write. Two different authors must not share one box — the report
                # prints both and a reader has to be able to tell which is which.
                fromref("recordNotes", "Notes on the artisan record", LT, S),
                # Provenance of the SOURCE record, so a reader of the printed roster can tell a row
                # filled from a survey three years ago from one filled last week.
                fromref("documentedOn", "Artisan documented on", DATE, S),
                # THE OTHER HALF OF THAT SENTENCE, WHICH HAD NO BOX. `artisanRef` is the one artisan
                # picker declared ALL_SCOPE — "this is where the roster is built" — so a roster
                # legitimately holds artisans documented at a different cluster's workshop years earlier,
                # and a reader of the printed roster could not tell such a row from one filled in the
                # room. The record page asks this FIRST, through `WorkshopSelect`, on the argument that
                # the workshop "is the context every other answer belongs to".
                #
                # KEY_VALUE by omission, and that is not a preference: the six TABLE_COLUMN widths
                # declared above already sum to exactly 100, so a seventh would push
                # `report_builder._table_columns` onto its proportional fallback and silently re-lay-out a
                # participant table that is already in submitted documents. KEY_VALUE fields print in the
                # per-row block beneath the table, so nothing is lost.
                fromref(
                    "documentedAtWorkshop",
                    "Documented at workshop",
                    T,
                    S,
                    max_length=160,
                    help="The workshop this artisan was documented at, from their record.",
                ),
                # WHAT ELSE IS ON THE ARTISAN RECORD, WHICH ONE PHOTOGRAPH CANNOT SAY. The record form's
                # media card asks for "images, audio introductions, videos, and documents" by name, and
                # `_reference_photos` resolves exactly one IMAGE — so a researcher who recorded an
                # artisan's spoken introduction produced material no box on this row could mention, and a
                # reader could not know to ask for it. A sentence counting the files and never the ids:
                # `_media_note` gives both reasons (a stage gallery holds the DESIGNER's own photographs,
                # and a referenced record's files are entitlement-gated per file). Same shape and same
                # bound as `traditionalProcess.recordMediaNote`.
                fromref("recordMediaNote", "Media on the artisan record", T, S, max_length=200),
                f("attendedDays", "Days attended", INT, S, min_value=0),
                fromref("photo", "Photograph", IMG, S, report_role=GALLERY),
                # WHATEVER THE REPOSITORY HOLDS AGAINST THE CHOSEN PHOTOGRAPH, which is not always a
                # sentence somebody wrote about that picture. `MediaFile.caption` is typed once per
                # UPLOAD on the /media page and machine-composed by the record forms ("Field media for
                # <artisan>"), so it may caption a whole batch rather than one image. Carrying it is
                # still right — it used to stop at the join and the designer was asked to retype a
                # caption that already existed one row away, for a photograph they had never seen
                # taken (see `ReferencePhoto` in `design_workshops`) — and only-fill-blanks means a
                # designer can overtype it here when the batch caption does not fit this picture.
                #
                # Do NOT answer the batch-caption problem by adding a caption box to one record form:
                # every gallery in this registry pairs its images with a `caption_for` field, and the
                # fix belongs where the caption is stored, not on one of the forms that stores it.
                fromref(
                    "photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP
                ),
            ),
            label_field="name",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 4. Cluster / Area / Craft Background
# --------------------------------------------------------------------------------------

STAGE_4 = StageSpec(
    number=4,
    key="CLUSTER_CRAFT_BACKGROUND",
    title="Cluster, Area & Craft Background",
    purpose=(
        "The setting: where the cluster is, how the craft is practised there, what it has "
        "traditionally made, and what the community's dependence on it looks like."
    ),
    # The split this note describes was asked for on the source document: traditional and
    # contemporary DESIGNS belong to the design brief, not to the cluster background. Stage 10
    # carries the matching sentence, so a designer meets the boundary from either side.
    #
    # THE BOUNDARY MOVED ON 2026-08-25, AND IT MOVED ALONG THE SAME SEAM RATHER THAN ACROSS IT.
    # The owner asked for a Traditional Motif AND a Contemporary Motif section here, each with its
    # own bullet list and its own gallery. That does not overturn the sentence above, because the
    # sentence was never about the word "contemporary" — it was about VOCABULARY versus DIRECTION.
    # What a cluster CURRENTLY makes, including the contemporary motifs its younger artisans have
    # already adopted from the market, is as much an observation of the setting as the motifs their
    # grandparents used, and observing it is what stage 4 is for. Which motifs THIS WORKSHOP will
    # carry forward remains a decision, and a decision is stage 10. So the note below now names the
    # two vocabularies explicitly and keeps the direction where it was, which is the only reading
    # under which a designer meeting the boundary from either side is told the same thing twice.
    notes=(
        "Motifs, forms and colours are recorded here as the cluster's existing craft vocabulary — "
        "the traditional motifs, and the contemporary ones the cluster has already taken up. "
        "Design direction — which of them this work will follow — belongs to the design brief at "
        "stage 10, not here."
    ),
    entities=(
        single(
            "clusterBackground",
            "DwClusterBackground",
            "Cluster & craft background",
            (
                f(
                    "clusterIntroduction",
                    "Cluster introduction",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                ),
                f(
                    "craftIntroduction",
                    "Craft introduction",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                ),
                f("history", "History of the craft", RICH, B, required=True, report_role=NARR),
                f(
                    "traditionalProducts",
                    "Traditional products",
                    RICH,
                    B,
                    required=True,
                    report_role=BULLETS,
                    help="One product per line.",
                ),
                f("geography", "Geography", RICH, S, report_role=NARR),
                f(
                    "accessAndConnectivity",
                    "Access & connectivity",
                    RICH,
                    S,
                    report_role=NARR,
                    help="Nearest road, rail and town; how the cluster is reached.",
                ),
                f("communityContext", "Community context", RICH, S, report_role=NARR),
                f("artisanHouseholds", "Artisan households in the cluster", INT, S, min_value=0),
                f(
                    "livelihoodDependence",
                    "Livelihood dependence on the craft",
                    RICH,
                    S,
                    report_role=NARR,
                    help="What share of household income the craft provides, and for how many.",
                ),
                f("culturalSignificance", "Cultural significance", RICH, S, report_role=NARR),
                f("giStatus", "GI status", ENUM, S, enum="GI_STATUS"),
                f("giDetails", "GI registration details", T, S, max_length=220),
                f(
                    "localTerminology",
                    "Local terminology",
                    RICH,
                    S,
                    report_role=NARR,
                    help="Craft terms in the local language, with their meaning.",
                ),
                # ── THE TWO MOTIF SECTIONS ──────────────────────────────────────────────────────────
                #
                # Asked for by the owner on 2026-08-25: a Traditional Motif section and a Contemporary
                # Motif section, each a bullet list with a gallery of up to twenty photographs beside
                # it, both browsable as a carousel. See this stage's `notes` for why they belong here
                # and what stays at stage 10.
                #
                # `traditionalMotifs` ALREADY EXISTED AND KEEPS ITS KEY. It is what the cluster's motif
                # vocabulary has been recorded under since the registry was written, and renaming or
                # re-keying it to sit symmetrically beside its new sibling would strand every answer
                # already given — the rule at the top of this module ("append, never renumber") applies
                # to a field's key just as much as to its position. What it gains is the help sentence
                # its neighbour `traditionalProducts` has always had, because "one per line" is the
                # whole convention of a BULLETS field and this box never said so.
                f(
                    "traditionalMotifs",
                    "Traditional motifs",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One motif per line.",
                ),
                f(
                    "traditionalForms",
                    "Traditional forms",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One form per line.",
                ),
                f(
                    "traditionalColours",
                    "Traditional colours",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One colour per line.",
                ),
                # THE CONTEMPORARY HALF. Deliberately the same three shapes as the traditional half
                # rather than a single "contemporary notes" box: the pair is what makes the question
                # "which motifs has this cluster gained, and which has it lost" answerable across two
                # workshops, and a free-text box on one side of a comparison answers nothing.
                f(
                    "contemporaryMotifs",
                    "Contemporary motifs",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One motif per line. The motifs the cluster works today that are not part of "
                    "its traditional vocabulary.",
                ),
                f(
                    "contemporaryForms",
                    "Contemporary forms",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One form per line.",
                ),
                f(
                    "contemporaryColours",
                    "Contemporary colours",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One colour per line.",
                ),
                f(
                    "motifSourcesAndInfluences",
                    "Sources & influences",
                    RICH,
                    S,
                    report_role=NARR,
                    help="Where the contemporary motifs came from — a buyer's specification, a design "
                    "intervention, a neighbouring craft, the market.",
                ),
                f(
                    "regionalTerminology",
                    "Regional / alternate terminology",
                    RICH,
                    A,
                    report_role=NARR,
                    phase_note="Reviewer: “Phase 3 work”.",
                ),
                f(
                    "clusterLocation",
                    "Cluster location",
                    GEO,
                    A,
                    phase_note="Reviewer: “Phase 3 work” (GIS integration).",
                ),
                *photos("clusterPhotos", "Cluster photographs", S, "Cluster photograph caption"),
                # TWENTY-FIVE, EXACTLY, AND THE TWO HALVES OF THAT NUMBER TRAVEL BY DIFFERENT ROADS.
                #
                # `motifPhotos` keeps its key and is the TRADITIONAL gallery, which is what it has
                # always held — every answer already in it is a photograph of a traditional motif, so
                # relabelling is honest and re-keying would not be.
                #
                # ── THE CEILING (25, WAS 20) ────────────────────────────────────────────────────────
                #
                # A WIDENING, AND A WIDENING IS SAFE FOR EVERY SHIPPED CLIENT. `coerce_value` refuses an
                # array longer than the cap rather than trimming it, so raising the number can only turn
                # a refusal into an acceptance: nothing that saved before stops saving. A client still
                # reading `maxItems: 20` off a stale registry simply stops its picker five short — it
                # under-offers, it never over-posts — and the honest statement of that cost is that such
                # a designer cannot fill the gallery until their app refetches. Both clients read
                # `maxItems` off the published registry and stop the picker at it, saying so on screen.
                #
                # ── THE FLOOR (25, NEW) ─────────────────────────────────────────────────────────────
                #
                # The owner's instruction is that all twenty-five are REQUIRED, in both galleries. It is
                # declared as `min_items` rather than `required=True` for a reason that is not a
                # nicety: `required` is enforced by `validate_entry`, which runs on EVERY save, and
                # `save_stage` restores a rejected key from `previous` — so a floor on that path would
                # answer 422 to a designer submitting twenty photographs AND revert the gallery to what
                # it held yesterday. `min_items` is scored in `stage_completeness` and nowhere else. A
                # partial save is always accepted; what a short gallery costs is that the stage is not
                # COMPLETE and the workshop is not ready to submit. See `FieldSpec.min_items`.
                #
                # ── WHAT THE HELP TEXT MAY AND MAY NOT PROMISE ──────────────────────────────────────
                #
                # The quality gate is a REFUSAL at capture, before the eager pre-upload starts, so a
                # poor photograph never reaches the server. What it can honestly refuse ON is what the
                # detector actually MEASURES: `DwImageQuality.findQualityIssues` emits BLUR (variance of
                # the Laplacian against `BLUR_VARIANCE_FLOOR`), LOW_RESOLUTION (long edge against
                # `MIN_LONG_EDGE_PX`) and DUPLICATE. OVEREXPOSED, UNDEREXPOSED and WRONG_SUBJECT are
                # tokens in the stage-21 enum that NO MEASUREMENT IN THIS PRODUCT MAKES — see the note
                # on STAGE_21, which is the sentence this help text is kept consistent with. So the help
                # names the three that are checked and names the two that are not, in the same breath,
                # rather than letting a designer read "quality checked" and assume a dark photograph was
                # judged. The numbers themselves are deliberately NOT printed here: the floors are client
                # constants, and a help string repeating them goes stale silently the day they move. The
                # refusal on screen prints the reading and the floor it was measured against.
                *photos(
                    "motifPhotos",
                    "Traditional motif photographs",
                    S,
                    "Traditional motif caption",
                    max_items=25,
                    min_items=25,
                    help="All 25 are required. Photographs of the cluster's traditional motifs — "
                    "the contemporary ones have their own gallery. Each photograph is checked "
                    "on this device before it uploads, for blur, low resolution and "
                    "duplicates, and one that fails is not sent. Exposure and subject are not "
                    "checked — judge those by eye.",
                ),
                *photos(
                    "contemporaryMotifPhotos",
                    "Contemporary motif photographs",
                    S,
                    "Contemporary motif caption",
                    max_items=25,
                    min_items=25,
                    help="All 25 are required. Photographs of the contemporary motifs the cluster "
                    "works today — the traditional ones have their own gallery. Each "
                    "photograph is checked on this device before it uploads, for blur, low "
                    "resolution and duplicates, and one that fails is not sent. Exposure and "
                    "subject are not checked — judge those by eye.",
                ),
            ),
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 5. Traditional Process, Tools & Raw Materials Baseline
# --------------------------------------------------------------------------------------

STAGE_5 = StageSpec(
    number=5,
    key="TRADITIONAL_PROCESS_BASELINE",
    title="Traditional Process, Tools & Raw Materials",
    purpose=(
        "The craft as it is practised before the workshop intervenes: the sequence of making, "
        "the tools, the materials and where they come from, and the problems the artisans "
        "already name."
    ),
    # Out of order is a real capture rule, not a convenience: the source document expected stages
    # 5 and 6 to “come later when they actually go to the field after market and consumer survey”.
    # Stage 6 says the same thing, because a designer who reaches either one first needs to know.
    notes=(
        "This stage can be completed out of order — recording the traditional process after the "
        "market and consumer survey, alongside stage 6, is a normal way to run a workshop."
    ),
    entities=(
        single(
            "traditionalProcess",
            "DwTraditionalProcess",
            "Process overview",
            (
                # ── THE DOCUMENTED PROCESS, AS A WHOLE, ONCE ────────────────────────────────────
                #
                # The first ref field on a SINGLETON that hydrates from an external record, and the
                # reason it exists is written above `REFERENCE_HYDRATION["processStep.processRef"]`:
                # that mapping refuses to copy a `Process`'s own sub-steps and its pre-process flag
                # onto a per-step row, correctly, because a whole sequence printed inside one of its
                # own steps would repeat on every row naming the same process. The note ends by
                # naming the right home and observing that the home had no door — "a singleton has no
                # ref field to hydrate from". This is the door. Until it existed, `ProcessStep` — the
                # ordered sub-steps a researcher actually documented — reached nothing anywhere in a
                # design workshop.
                #
                # WORKSHOP-scoped for exactly the reason `processStep.processRef` is: a `Process`
                # hangs off a product at one cluster, so "Tie and dye" at Bagru and "Tie and dye" at
                # Bhuj are two different sequences under one name and the picker's sublabel is the
                # only thing on screen that separates them.
                # ── THE PARENT OF THE PICKER BELOW, AND THE FIELD THAT LOOKED LIKE ONE IS A TRAP ────
                #
                # `Process.productId` is NON-NULLABLE and its schema comment says a process "reaches a
                # workshop only through its parent product": one product, many processes, so the
                # cascade is the natural one and the process picker was the last unnarrowed child
                # picker in the registry. Pick the product and the process list holds that product's
                # documented processes and nothing else.
                #
                # WHY A NEW REF FIELD RATHER THAN `ref_filter_by="documentedFor"`. `documentedFor`
                # below is a `fromref` TEXT box holding the product's NAME, and `validate_registry`
                # only requires that the field named by `ref_filter_by` EXIST on the entity — it does
                # not require it to be a REF. So `ref_filter_by="documentedFor"` would pass validation
                # and then send a product NAME to the endpoint as `filterBy`, where it is treated as an
                # id and matches nothing: the silent-emptying failure that whole validation block was
                # written to prevent, arriving through the one door it leaves open.
                #
                # THE PICKER NARROWING IS NOT A SAVE-TIME INVARIANT, and this repository's habit is to
                # assume it is. `reference_options` refuses to OFFER a mismatched pair; nothing refuses
                # to STORE one — `coerce_value` checks type and length only — so a stale form or a
                # direct API caller can post `productRef=A` with a process belonging to B.
                #
                # HIDDEN, and the alternative is not available: `documentedFor` already prints the
                # product name, so a printing `productRef` would be a second, possibly-disagreeing
                # statement of one fact. Tier S and never BASIC — `EntityForm.tsx` picks the
                # collection's bulk multi-select as the field that is `REF && refModel && !refFilterBy
                # && tier === "BASIC"`, and a BASIC one here would turn a picker into a "tick thirty
                # records and make thirty rows" control that means nothing on a singleton either.
                f(
                    "productRef",
                    "Documented product",
                    REF,
                    S,
                    ref_model="ProductDocumentation",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="Which product’s documented process this is. Choosing one narrows the "
                    "process list below to that product’s documented processes.",
                ),
                f(
                    "processRef",
                    "Documented process",
                    REF,
                    S,
                    ref_model="Process",
                    ref_filter_by="productRef",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="Choose a process already documented for this workshop. Pick the product "
                    "first to narrow this list. Its name, notes, sub-steps and pre-process "
                    "answer are filled in below.",
                ),
                f(
                    "processOverview",
                    "Broad process steps",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                    help="The making sequence in outline. Individual steps are recorded below.",
                ),
                # NOT `processOverview`, AND THE DISTINCTION IS THE WHOLE POINT. `processOverview`
                # above is a REQUIRED narrative the DESIGNER writes about what they observed at the
                # workshop. These four are what a RESEARCHER recorded, somewhere else, earlier.
                # Only-fill-blanks would have dropped the second into the first on every workshop
                # whose designer had not typed yet, and no reader of the .docx could have told which
                # of the two they were reading — which is the one thing a report of this kind must
                # never make ambiguous.
                fromref("documentedProcessName", "Documented process", T, S),
                fromref(
                    "documentedFor",
                    "Documented for",
                    T,
                    S,
                    help="The product whose documented process this is.",
                ),
                # WHAT THE PROCESS RECORD HAS ON FILE. A researcher who filmed every step of a dye
                # sequence produced a workshop row that said the sequence existed and showed none of
                # it. This is a sentence naming how much footage the record carries, not the files —
                # see `_process_media_note` for why a count is the honest carry here and a list of ids
                # would either freeze ids the report cannot fetch or bypass the per-file entitlement
                # gate. It tells a reader the footage exists so they can ask for it.
                fromref("recordMediaNote", "Media on the process record", T, S, max_length=200),
                # RICH, AND THE PROMOTION BUYS SOMETHING HERE THAT IT WOULD NOT BUY IN A CELL. The record
                # page gives this column the full editor — headings, ordered and bulleted lists, an
                # inline picture — and the workshop gave the same fact a bare textarea, inches from
                # `processOverview`, which is RICH: two narratives about the same sequence offering
                # different editors. NARRATIVE is the one role the RICH alias note above says a promotion
                # is worth anything for, because `_render_narrative` gives a rich NARRATIVE its own path
                # through `rich_text.to_report_blocks` — and this field already declares it, so nothing
                # about WHERE it prints changes.
                #
                # Nothing is blanked and no backfill is needed: `coerce_value` reads a plain string as
                # unformatted prose, which is exactly what hydration writes here (`_reference_data`
                # flattens the researcher's marks on the way across), and both clients document absorbing
                # the same promotion. The mapping pair is unchanged.
                fromref(
                    "documentedProcessNotes",
                    "Notes from the process record",
                    RICH,
                    S,
                    report_role=NARR,
                ),
                fromref(
                    "documentedSteps",
                    "Sub-steps on the process record",
                    LT,
                    S,
                    report_role=BULLETS,
                    help="The ordered sub-steps as they were documented, one per line.",
                ),
                # THE PRE-PROCESS FLAG, IN THE ONE PLACE IT ANSWERS A QUESTION SOMEBODY ASKED. The
                # registry's objection to it was never to the value — it was to "Pre-process
                # available: Yes" appearing under step 3 of 7. It is a property of the whole process,
                # so it belongs on the whole-process record, which is this one.
                fromref("preProcessAvailable", "Pre-process required", BOOL, S),
                # ── THE EVIDENCE FOR THE ANSWER ABOVE, WHICH THE WORKSHOP ASKED FOR AND COULD NOT HOLD ─
                #
                # The record page makes pre-process media MANDATORY the moment its own checkbox is
                # ticked — `submit()` refuses the save with "Attach the pre-process media or uncheck the
                # box" — and it says video is the preferred format because the point is the action as it
                # happens. This entity had no IMAGE, IMAGE_LIST, VIDEO or FILE field of any kind, so the
                # workshop asked the question one line above and gave the designer nowhere to put the
                # answer's evidence: footage of the pre-processing shot at the cluster had to be left out
                # of the workshop entirely.
                #
                # `f()` AND NOT `fromref()`, AND IT MUST STAY THAT WAY. `recordMediaNote` above counts the
                # SOURCE record's files deliberately; these two hold the DESIGNER's own footage, of which
                # there is no second copy anywhere. Seeding them from the record would break the rule
                # `hydrate_entries` states — a gallery is seeded when empty and never overwritten — and
                # `MediaFile` has no `processId` to seed them from in any case (see
                # `REFERENCE_MODELS["Process"]`, which spells out what a migration would cost).
                f(
                    "preProcessVideo",
                    "Pre-process footage",
                    VIDEO,
                    A,
                    help="Video of the pre-processing, if it was observed at the workshop.",
                ),
                *photos(
                    "preProcessPhotos",
                    "Pre-process photographs",
                    A,
                    "Pre-process photograph caption",
                ),
                fromref("documentedOn", "Process documented on", DATE, S),
                f("totalMakingTime", "Total making time", DEC, S, unit="days", min_value=0),
                f(
                    "currentProblems",
                    "Current problems",
                    RICH,
                    S,
                    report_role=BULLETS,
                    help="One problem per line.",
                ),
                f(
                    "qualityIndicators",
                    "Quality indicators",
                    RICH,
                    A,
                    report_role=BULLETS,
                    phase_note="Reviewer: “Based on workshop app — Ankit”.",
                    help="How the artisans themselves judge a good piece.",
                ),
                f("failurePoints", "Failure points", RICH, A, report_role=BULLETS),
                f("artisanAudio", "Artisan’s spoken explanation", AUDIO, A),
                f(
                    "artisanAudioTranscript",
                    "Transcript of the recording",
                    RICH,
                    A,
                    report_role=NARR,
                ),
            ),
        ),
        many(
            "processStep",
            "DwProcessStep",
            "Process steps",
            (
                f(
                    "stepNumber",
                    "Step",
                    INT,
                    B,
                    required=True,
                    min_value=1,
                    report_role=COL,
                    column_width_pct=8.0,
                ),
                # Scoped to the workshop: a process is documented against the product it belongs to
                # at a particular workshop, and offering every process in the database would put a
                # different cluster's dyeing sequence one tap away from this one's.
                #
                # RE-EXAMINED WHEN `tool.toolRef` BELOW WAS WIDENED TO ALL, AND DELIBERATELY LEFT
                # NARROW. The volume argument for widening applies here too — every Process row in
                # this database belongs to a different Workshop, so the picker shows one option — but
                # correctness does not follow it. A `Process` hangs off a PRODUCT at one cluster, so
                # "Tie and dye" at Bagru and "Tie and dye" at Bhuj are two different sequences under
                # one name, and `reference_options` builds this picker's sublabel from the product
                # name alone: no place, no cluster, no artisan, nothing on screen that would let a
                # designer tell the two apart. The tool picker's sublabel carries the artisan and the
                # place, which is what makes the wider net readable there and unreadable here. The
                # stored ref is also a join key research follows, so a mis-pick is not just a wrong
                # word in a report — it links this workshop's step to another cluster's record.
                # THE PARENT OF THE PICKER BELOW. Same field, same key and same reasoning as
                # `traditionalProcess.productRef` — see the long note there for why the existing
                # `documentedFor` TEXT box cannot be the cascade's parent (it holds a NAME, and
                # `validate_registry` would accept it and then send that name as an id).
                #
                # HIDDEN IS THE ONLY ROLE WITH NO LAYOUT CONSEQUENCE HERE. The five declared
                # `column_width_pct` values on this entity — stepNumber 8 + name 24 + localName 18 +
                # description 34 + timeTaken 16 — already total EXACTLY 100, so a sixth TABLE_COLUMN
                # would push the renderer onto its proportional fallback and silently re-lay-out the
                # process-step tables of documents that are already submitted.
                f(
                    "productRef",
                    "Documented product",
                    REF,
                    S,
                    ref_model="ProductDocumentation",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="Which product’s documented process this step came from. Choosing one "
                    "narrows the process list below to that product’s documented processes.",
                ),
                f(
                    "processRef",
                    "Documented process",
                    REF,
                    S,
                    ref_model="Process",
                    ref_filter_by="productRef",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="Choose a process already documented for this workshop instead of "
                    "re-describing it. Pick the product first to narrow this list.",
                ),
                fromref(
                    "name", "Step name", T, B, required=True, report_role=COL, column_width_pct=24.0
                ),
                f("localName", "Local name", T, S, report_role=COL, column_width_pct=18.0),
                # WHICH DOCUMENTED PROCESS THIS STEP CAME FROM, copied so the printed report can make
                # the distinction the designer made at the picker.
                #
                # The scope note above says a `Process` is an INSTANCE: "Tie and dye" at Bagru and
                # "Tie and dye" at Bhuj are two different sequences under one name, and the only thing
                # separating them on screen is the product name in the picker's sublabel. Until this
                # box existed the sublabel was where that distinction died — the row stored the id and
                # printed the bare name, so a reader of the document could not tell which sequence had
                # been documented, and neither could the designer re-opening the stage a fortnight
                # later. KEY_VALUE rather than a sixth TABLE_COLUMN because the five declared widths
                # below already sum to 100, and a sixth would push the renderer onto its proportional
                # fallback and silently re-lay-out a table that is already in submitted documents.
                fromref(
                    "documentedFor",
                    "Documented for",
                    T,
                    S,
                    help="The product whose documented process this step was taken from.",
                ),
                fromref(
                    "description", "What happens", LT, S, report_role=COL, column_width_pct=34.0
                ),
                f(
                    "timeTaken",
                    "Time taken",
                    DEC,
                    S,
                    unit="hours",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("performedBy", "Performed by", T, S),
                # ── ONE STEP, OR A BRACKET AROUND SEVERAL ────────────────────────────────────────────
                #
                # The record page fixes this at the moment a step is added — two buttons, "Sequential" and
                # "Group of activities" — and prints it in every step header for the life of the record.
                # A GROUP is not a step in the sequence, which is why `_step_lines` marks one, and until
                # this box existed the distinction reached the workshop ONLY inside the flattened
                # `documentedSteps` string on the hydrated singleton: a designer who watched three
                # parallel activities bracketed together had no way to say so, and nothing queryable held
                # it either.
                #
                # KEY_VALUE and NOT a sixth TABLE_COLUMN: the five declared widths on
                # stepNumber/name/localName/description/timeTaken already sum to 100, and a sixth pushes
                # the renderer onto its proportional fallback, re-laying-out a table that is already in
                # submitted documents. `_render_rows` prints KEY_VALUE in the per-row block beneath.
                #
                # `f()` and not `fromref()`: a Process has MANY steps and hydration cannot choose which
                # source step a row corresponds to — the same reason `name` above receives the PROCESS's
                # name. See the note on `ENUMS["PROCESS_STEP_TYPE"]`.
                f("stepType", "Step type", ENUM, S, enum="PROCESS_STEP_TYPE", report_role=KV),
                f("toolsUsed", "Tools used", TAGS, S),
                f("problems", "Problems at this step", LT, S),
                *photos("stepPhotos", "Step photographs", A, "Step photograph caption"),
                f("stepVideo", "Process video", VIDEO, A),
                # ── THE ARTISAN EXPLAINING THIS STEP, WHICH ONLY THE WHOLE PROCESS COULD HOLD ─────────
                #
                # The record page mounts a media card per step with no `allowedTypes`, so it takes audio,
                # and it carries its own in-browser recorder; the uploaded audio is then queued for
                # transcription. This collection offered `stepPhotos` and `stepVideo` and nothing else, so
                # a designer standing at one step with an artisan explaining THAT step could not record
                # them: `traditionalProcess.artisanAudio` covers the process AS A WHOLE, which is a
                # different scope and a different recording.
                #
                # The same pair, the same types and the same tier as that singleton's, so the affordance a
                # designer already knows exists per step. NARRATIVE is safe on a many-row: `_render_rows`'
                # per-row block renders NARRATIVE, KEY_VALUE, COVER_FIELD and BULLETS, so it prints.
                f("stepAudio", "Spoken explanation of this step", AUDIO, A),
                f("stepAudioTranscript", "Transcript of the recording", RICH, A, report_role=NARR),
            ),
            label_field="name",
        ),
        many(
            "tool",
            "DwTool",
            "Tools",
            (
                # The tool picker comes FIRST in the entity so the form draws it above the fields it
                # fills in. Field order here is the order every client renders, and a picker sitting
                # under seven boxes a designer has already typed into is a picker that never gets
                # used.
                # ALL, and the reason is what a tool IS. A pit loom is a TYPE of object, not an
                # instance tied to a place: the same loom stands at every weaving cluster, and the
                # source document's Advanced row for this stage asks in as many words for a "linked
                # craft knowledge repository" and a "material/tool taxonomy" — both of which mean
                # reuse ACROSS clusters or they mean nothing. Scoping it to the workshop was not a
                # narrowing, it was an emptying: every ToolDocumentation row in this database belongs
                # to a different Workshop, so the picker offered a designer exactly one record, and a
                # dropdown with one entry is a dropdown they type around — which is the behaviour
                # this whole feature exists to end.
                #
                # The neighbouring `processStep.processRef` is deliberately NOT widened with it; the
                # note there says why, and the difference is the same one: a process is an instance,
                # a tool is a type.
                f(
                    "toolRef",
                    "Documented tool",
                    REF,
                    S,
                    ref_model="ToolDocumentation",
                    ref_scope=ALL_SCOPE,
                    report_role=HIDDEN,
                    help="Choose a tool already documented anywhere in the repository. Its details are "
                    "filled in below and can be corrected on this row.",
                ),
                fromref(
                    "name", "Tool", T, B, required=True, report_role=COL, column_width_pct=24.0
                ),
                fromref("localName", "Local name", T, S, report_role=COL, column_width_pct=18.0),
                fromref("englishName", "English name", T, S),
                f(
                    "toolType",
                    "Type",
                    T,
                    S,
                    report_role=COL,
                    column_width_pct=16.0,
                    help="What kind of tool this is, in the words the artisans use. Choose the family "
                    "below as well, so the same tool can be counted across clusters.",
                ),
                # ADDED BESIDE `toolType`, NOT INSTEAD OF IT, and the difference is data.
                #
                # Retyping `toolType` as an ENUM was the obvious move and it is the wrong one. The
                # answers already stored in that box are sentences — "Vessel over a firewood hearth",
                # "Hand-turned warping frame" — not tokens, and `coerce_value` refuses a token it does
                # not know. `save_stage` does protect the stored value on a rejected field, so nothing
                # would have been lost on the server; what would have been lost is the designer's
                # sight of it. `SearchableSelect` matches the stored value against the option list and
                # falls back to the "Select" placeholder when it finds nothing, so a converted field
                # would have drawn an EMPTY dropdown over a filled-in answer, and a submit would have
                # 422'd on a box the designer never touched. Blocking fieldwork over last season's
                # phrasing is the one thing this app must not do.
                #
                # So the sentence keeps its box and the countable answer gets its own. It is the same
                # pairing as `rawMaterial.name` and `rawMaterial.family` two entities down, for the
                # same reason: the name is what the report prints, the family is what a query can
                # group by. KEY_VALUE rather than a sixth TABLE_COLUMN on purpose — the tool table's
                # five declared widths already sum to 100, and a sixth would push the renderer onto
                # its proportional fallback and silently re-lay-out a table that is already in
                # submitted documents.
                f(
                    "toolFamily",
                    "Tool family",
                    ENUM,
                    S,
                    enum="TOOL_TYPE",
                    help="The broad category, for counting tools across workshops. Describe the tool "
                    "itself in “Type” above.",
                ),
                fromref("usedFor", "Used for", LT, S, report_role=COL, column_width_pct=26.0),
                fromref("material", "Made of", T, S),
                # `f()` AND NOT `fromref()`, AND IT WAS THE OTHER WAY ROUND UNTIL THIS LANE.
                #
                # This is the defect the whole "not all fields are being carried faithfully" complaint
                # is a symptom of, seen from the opposite side: the field CLAIMED a carry it did not
                # have. `fromref` appends the FROM_REF sentence — "Filled in from the linked record
                # when one is chosen" — to the help text, so the form told the designer this box would
                # fill itself in. No mapping ever wrote it, `validate_registry` cannot see a help-text
                # marker, and no test compared the two. The sentence shipped, in the browser and in the
                # bundled Android asset, and every designer who believed it left the box empty.
                #
                # It is demoted rather than mapped because `ToolDocumentation` HAS NO COLUMN for where
                # a tool was obtained. `maker` is a different question and now has its own box below;
                # answering "where obtained: carpenter" from it would be a plausible wrong sentence in
                # a submitted report, which is the failure every translation table in
                # `design_workshops` is written to refuse. `test_reference_carry.py` now asserts
                # fromref ⟺ mapping in both directions, so neither half of this can drift again.
                f(
                    "source",
                    "Where obtained",
                    T,
                    S,
                    report_role=COL,
                    column_width_pct=16.0,
                    help="Bought, made by the artisan, inherited. The documented tool record does not "
                    "record this, so it is not filled in from a linked tool.",
                ),
                fromref(
                    "maker",
                    "Made by",
                    ENUM,
                    S,
                    enum="MAKER_TYPE",
                    help="Who made the tool — a different question from where it was obtained.",
                ),
                fromref(
                    "traditionType", "Traditional or contemporary", ENUM, S, enum="TRADITION_TYPE"
                ),
                fromref("cost", "Cost", MONEY, S, unit="INR", min_value=0),
                fromref("yearsInUse", "Years in use", INT, S, unit="years", min_value=0),
                # ── PROVENANCE OF THE TOOL RECORD ITSELF ─────────────────────────────────────────
                # The tool picker is deliberately ALL-scoped (see the note on `toolRef` above): a pit
                # loom is a TYPE and the same loom stands at every weaving cluster. That is what makes
                # these three worth printing — a reader of the report can see that this workshop's
                # loom row was filled from a record documented for a different craft in a different
                # place, which is legitimate reuse and not a mistake, but only if it is visible.
                fromref("craftName", "Craft on the tool record", T, S),
                fromref("place", "Place on the tool record", T, S),
                fromref("artisanName", "Documented for", T, S),
                # ── WHO USES IT, WHICH IS A DIFFERENT QUESTION FROM WHO IT WAS DOCUMENTED FOR ────────
                #
                # `artisanName` above is ONE denormalised string on the tool record naming whoever it
                # was first documented against. `ToolArtisan` is the real many-to-many that the tool
                # page's `ToolAssignmentSection` exists to populate, and a pit loom assigned to nine
                # weavers used to cross into a workshop as one of those nine names with the other eight
                # unreachable from the report. Both boxes, because both facts are real and a reader
                # comparing them can see that a shared tool is shared.
                #
                # BULLETS, so `report_builder` splits the newline-separated list into one line per
                # artisan instead of printing nine names as one run-on sentence — the same shape and the
                # same reason as `documentedSteps`.
                # ── THE RECORD'S OWN STATED ADDRESS, IN BOXES OF ITS OWN ────────────────────────────
                #
                # The record page collects a full location and none of it used to cross: this row had one
                # free-text `place` and nothing else, so a thing documented in Barpali, Bargarh, Odisha
                # arrived as whatever somebody had typed into a single box. `place` above is unchanged —
                # it is the denormalised column — and these four are what the page actually asks.
                #
                # KEY_VALUE, and NOT extra table columns: this entity's declared widths already govern
                # its table and four more columns would push it onto the proportional fallback, silently
                # re-laying-out a table that is in submitted documents. `report_builder` prints
                # KEY_VALUE in the per-row block beneath, so nothing is lost.
                #
                # STATED, never the device's fix. See the model's `include` in `design_workshops`.
                fromref("recordState", "State on the record", T, S, max_length=80),
                fromref("recordDistrict", "District on the record", T, S, max_length=80),
                fromref("recordVillage", "Village on the record", T, S, max_length=120),
                # Same rule as `participant.pincode`, checked against the normalised value. See there.
                fromref(
                    "recordPincode",
                    "PIN code on the record",
                    T,
                    S,
                    max_length=10,
                    text_format=TextFormat.PINCODE,
                ),
                # THE PIN, WHICH IS THE HALF OF THE ADDRESS THAT IS NOT A STRING. The four boxes above
                # carry what the record page's stated-address fields say; the record page ALSO lets a
                # researcher drop a pin on the tool's own place with the map picker, and that coordinate
                # is about the place rather than about the desk — which is the whole of invariant 4 and
                # the distinction `_subject_point` exists to keep. GEO already renders as the map picker
                # on the web and as the full location card on the handset, so the affordance the record
                # page gives is the affordance this box gives.
                fromref(
                    "recordSubjectLocation",
                    "Pinned place on the tool record",
                    GEO,
                    A,
                    help="The pin a researcher dropped on the place the tool was documented, not "
                    "the device’s position when the record was typed.",
                ),
                fromref(
                    "usedByArtisans",
                    "Also used by",
                    LT,
                    S,
                    report_role=BULLETS,
                    help="Every artisan this tool is assigned to on the tool record. One per line.",
                ),
                # HOW MUCH FOOTAGE THE TOOL RECORD CARRIES. The record page mounts its media card TWICE —
                # once for the ordered "Process stages" sequence, whose captures are archived as
                # STAGE_STEP_1, STAGE_STEP_2, …, and once for general video and audio — and `photo` below
                # is a single IMAGE, so a tool whose making was documented as a nine-photograph sequence
                # reached this row, and a ministry report, as one still with nothing admitting the rest.
                # A sentence and never the ids; see `_media_note`. KEY_VALUE by omission — never a sixth
                # TABLE_COLUMN, because this table's five declared widths already sum to exactly 100.
                fromref(
                    "recordMediaNote",
                    "Media on the tool record",
                    T,
                    S,
                    max_length=200,
                    help="How much footage the tool record carries. The files themselves stay on "
                    "the record.",
                ),
                # ── RICH, BECAUSE THESE ARE THE TWO NARRATIVE BOXES ON THE RECORD PAGE ───────────────
                #
                # `ToolForm` calls them exactly that and gives both the full editor, on the argument that
                # they "hold prose a researcher would rather speak than thumb in"; the workshop gave the
                # same two facts a plain textarea whose dictation button can only append a flat string to
                # the end, while the RICH branch inserts at the caret. The promotion is the supported
                # migration and blanks nothing: `coerce_value` reads a plain string as unformatted prose,
                # which is what hydration writes here.
                #
                # WHAT THIS DOES NOT FIX, so nobody looks for it twice: `_reference_data` flattens every
                # string in the payload, so a numbered improvement list a researcher wrote on the record
                # page still ARRIVES as one paragraph. That flattening is load-bearing (it is what stopped
                # `{"blocks":…}` printing into a ministry table) and making it target-type-aware is a
                # separate change to a function with a long argued docstring. What this closes is the
                # designer's own editor, on both surfaces, which is the requirement-(b) defect.
                fromref("improvements", "Improvements suggested", RICH, S),
                fromref("remarks", "Remarks on the tool record", RICH, S),
                # ── EIGHT MEASUREMENT BOXES, TWO DIFFERENT STATES OF KNOWLEDGE ────────────────
                #
                # THIS HEADING SAID SEVEN. Count the boxes where they are declared below — `lengthCm`,
                # `breadthCm`, `heightCm`, and the five "(as recorded)" ones — and the answer is eight,
                # and has been since `heightCm` was added; the heading was not rewritten with it.
                #
                # `lengthCm`/`breadthCm`/`heightCm` are converted from
                # `lengthInches`/`breadthInches`/`heightInches`, which declare their unit in the column
                # name and in the record form's labels. See `_inches_to_cm` for why a straight copy
                # into a box labelled "cm" would put a wrong measurement into a ministry report that
                # the only-fill-blanks rule then makes permanent. BREADTH keeps its own word here —
                # unlike the product's, which maps breadth onto width — because `ToolDocumentation`
                # also has a separate unitless `width` column and collapsing the two would merge two
                # different measurements. `heightInches` joined its two siblings on 2026-08-27;
                # `heightCm` stays declared below `measurementMethodNote` rather than beside them
                # because that is where the box was first written, and moving a field reorders it on
                # both clients.
                #
                # The five "(as recorded)" fields DECLARE NO UNIT, and that is the honest declaration.
                # Their source columns carry no unit suffix, the tool form labels them with the bare
                # words "Height", "Weight", "Radius", and the record sheet prints them bare. Nobody
                # knows whether a 12 is inches, centimetres or kilograms. Giving them `unit="cm"` here
                # would convert an unknown into a stated wrong answer, which is worse than the blank
                # they replace. If a unit column is ever added to `ToolDocumentation`, these become
                # ordinary converted fields and these five can be deprecated with `replaced_by`.
                fromref("lengthCm", "Length", DEC, S, unit="cm", min_value=0),
                fromref("breadthCm", "Breadth", DEC, S, unit="cm", min_value=0),
                # ── WHO OR WHAT MEASURED THE THREE CONVERTED FIGURES ──────────────────────
                #
                # The same box as `existingProduct.measurementMethodNote`, for the same reason and with
                # the same wording rule — read that field's note and
                # `design_workshops._measurement_method_note` before changing either.
                #
                # IT COVERS THE THREE CONVERTED FIGURES AND NOTHING ELSE — `lengthCm` and `breadthCm`
                # above, `heightCm` below — which is a fact about the record rather than a decision
                # made here: `measurement_provenance.DIMENSION_FIELDS` is
                # `{lengthInches, breadthInches, heightInches}`, so no stamp is ever written for the
                # `height`, `width`, `thickness`, `weight` and `radius` columns behind the five
                # "(as recorded)" boxes below. Those five state neither their unit nor their method,
                # and both silences are the tool record's.
                #
                # THIS SAID TWO, AND SAID `heightCm` IS "measured AT the workshop by the designer, so
                # it needs no clause from the record at all", UNTIL 2026-08-27.
                # `ToolDocumentation.heightInches` landed that day, the tool half of
                # `design_workshops._METHOD_CARRIED_DIMENSIONS` gained its third pair, and the sentence
                # this box hydrates can now name height. The wording had to follow: this help text is
                # what a designer reads while deciding whether the sentence is about the number in
                # front of them, and a note naming a dimension the help says it does not cover is worse
                # than no note. Both clients render this string — the browser off the wire, the handset
                # off the bundled asset — so it is corrected here and the asset regenerated with it.
                fromref(
                    "measurementMethodNote",
                    "How the record's measurements were taken",
                    T,
                    S,
                    max_length=200,
                    help="How the tool record's own length, breadth and height were arrived at — a "
                    "tape reading, marks on a photograph, or a vision model's estimate. It "
                    "describes the record, not a number you type here. The five "
                    "“(as recorded)” measurements below carry no method: the record does not "
                    "store one for them.",
                ),
                # ── THE THIRD DIMENSION A PHOTOGRAPH CAN READ, WHICH ONCE HAD NOWHERE TO LAND ─────
                #
                # `measurableLengthFields` qualifies a field off its DECLARED length unit. Before this
                # box existed the photo-measure panel on `photo` below could propose into `lengthCm` and
                # `breadthCm` and could not see `heightAsRecorded` — correctly, because that box says
                # only what the record said and the record states no unit. But the record page's grid
                # capture DOES measure a height ("Side-on photo of the object against the grid — fills
                # height"), so the affordance existed for two of the tool's dimensions and was missing
                # for the third one a camera can read: at the workshop, height had to be eyeballed and
                # typed.
                #
                # A UNIT-DECLARED TWIN AND NOT A RETYPE, which is invariant 6 read the right way round:
                # giving `heightAsRecorded` a unit would turn an unknown into a stated wrong answer.
                # Same pairing, same argument, as `toolType` beside `toolFamily`.
                #
                # IT WAS `f()` AND NOT `fromref()` UNTIL 2026-08-27, and the reason recorded here was
                # "nothing may map into it, because the source column's unit is unknown and a mapping
                # would invent one". That reason was about the plain `height` column, which is still the
                # only source `heightAsRecorded` below is copied from and still states no unit. It
                # stopped being the whole story when `ToolDocumentation.heightInches` landed: that
                # column states its unit in its own name exactly as `lengthInches` and `breadthInches`
                # do, so a mapping converts rather than invents, and `design_workshops`'s tool carry
                # produces `heightCm` from it. Without this `fromref()` and the pair in
                # `stage_schema.REFERENCE_HYDRATION["tool.toolRef"]` the converted height was computed
                # on every picker call and landed nowhere, while `measurementMethodNote` above hydrated
                # a sentence naming a height the entry did not show.
                #
                # NOT extended to width/thickness/radius: the record page's grid offers no reading for
                # those three, so twins there would be three new boxes buying nothing — and
                # `weightAsRecorded` must never get one, because a photograph cannot weigh anything.
                fromref(
                    "heightCm",
                    "Height (measured)",
                    DEC,
                    S,
                    unit="cm",
                    min_value=0,
                    help="The tool's height with its unit stated — measured at the workshop, or "
                    "converted from the height in inches on the tool record. The record's "
                    "other height, the one whose unit it does not state, is below.",
                ),
                fromref(
                    "heightAsRecorded",
                    "Height (as recorded)",
                    DEC,
                    S,
                    min_value=0,
                    help="Copied from the tool record, which does not state the unit it was "
                    "measured in.",
                ),
                fromref(
                    "widthAsRecorded",
                    "Width (as recorded)",
                    DEC,
                    S,
                    min_value=0,
                    help="Unit not stated on the tool record.",
                ),
                fromref(
                    "thicknessAsRecorded",
                    "Thickness (as recorded)",
                    DEC,
                    S,
                    min_value=0,
                    help="Unit not stated on the tool record.",
                ),
                fromref(
                    "weightAsRecorded",
                    "Weight (as recorded)",
                    DEC,
                    S,
                    min_value=0,
                    help="Unit not stated on the tool record.",
                ),
                fromref(
                    "radiusAsRecorded",
                    "Radius (as recorded)",
                    DEC,
                    S,
                    min_value=0,
                    help="Unit not stated on the tool record.",
                ),
                fromref("documentedOn", "Tool documented on", DATE, S),
                fromref("photo", "Photograph", IMG, S, report_role=GALLERY),
                fromref(
                    "photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP
                ),
            ),
            label_field="name",
        ),
        many(
            "rawMaterial",
            "DwRawMaterial",
            "Raw materials",
            (
                f("name", "Material", T, B, required=True, report_role=COL, column_width_pct=22.0),
                f("localName", "Local name", T, S, report_role=COL, column_width_pct=16.0),
                f(
                    "family",
                    "Material family",
                    ENUM,
                    S,
                    enum="MATERIAL_FAMILY",
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "source",
                    "Source",
                    T,
                    S,
                    report_role=COL,
                    column_width_pct=18.0,
                    help="Where the material is bought or gathered.",
                ),
                f(
                    "cost",
                    "Cost",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f("costUnit", "Cost per", T, S, max_length=24, help="kg, metre, piece."),
                f(
                    "availability",
                    "Availability",
                    ENUM,
                    S,
                    enum="DEMAND_LEVEL",
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f(
                    "technicalProperties",
                    "Technical properties",
                    RICH,
                    A,
                    report_role=NARR,
                    phase_note="Reviewer: “Based on workshop app — Ankit”.",
                ),
                f("photo", "Photograph", IMG, S, report_role=GALLERY),
                f("photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP),
            ),
            label_field="name",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 6. Existing Products & Artisan Baseline
# --------------------------------------------------------------------------------------

STAGE_6 = StageSpec(
    number=6,
    key="EXISTING_PRODUCTS_BASELINE",
    title="Existing Products & Artisan Baseline",
    purpose=(
        "What the cluster already makes and sells, recorded before any new design work, so the "
        "workshop's effect can be measured against it."
    ),
    # The 360-degree capture and automated quality assessment in the Advanced tier were referred
    # to a later plug-in on the source document. What is declared here is the DATA such a feature
    # would consume — `turntablePhotos`, and the named `viewFront`/`viewBack`/`viewDetail` slots —
    # so the schema is ready for it without claiming it. Those three are also the ONLY named view
    # slots anywhere in the registry, which is why `DwImageQuality.findMissingViews` reports on
    # this entity and no other.
    #
    # The note says "once you have started the set" rather than "when one is empty" because that
    # is what the handset actually does: `findMissingViews` stays silent when none of the three is
    # filled — a designer who wanted no multi-view record is not nagged three times — and speaks
    # only once at least one, but not all, are present.
    notes=(
        "This stage can be completed out of order, with stage 5. Front, back and detail are "
        "separate photograph slots; once you have started the set, the app points out which of "
        "them is still missing."
    ),
    entities=(
        # ── THE ARTISAN HALF OF THIS STAGE, WHICH THE TITLE HAS PROMISED AND THE REGISTRY LACKED ──
        #
        # This stage is called "Existing Products & Artisan Baseline" and its only entity was the
        # `existingProduct` collection. The artisan half was a hole, and the thing that belongs in it
        # is a CITATION OF THE BASELINE INSTRUMENT: the stage's own purpose reads "recorded before any
        # new design work, so the workshop's effect can be measured against it", which is a
        # description of a baseline.
        #
        # ── WHY A NEW ENTITY AND NOT ONE OF THE 22 THAT EXIST ────────────────────────────────────
        # Each rejection below is a live trap rather than a preference:
        #
        #  * `participant` (stage 3) is the obvious answer and the worst one. An interview belongs to
        #    a SET of artisans (`artisanSetKey` is the sorted, comma-joined ids under a unique
        #    constraint), so one sitting would be cited on six roster rows and its summary printed six
        #    times — the defect `REFERENCE_HYDRATION["processStep.processRef"]` refuses `steps` for.
        #    The roster row is also about the PERSON while the interview is about the SITTING. And
        #    participant's six TABLE_COLUMN widths already total exactly 100, so nothing there can be
        #    a column at all.
        #  * `workshopPlan` (stage 3) is the right SHAPE — a singleton above a collection, where
        #    `traditionalProcess.processRef` was put so a citation prints once instead of per row —
        #    but it is "Plan & opening": designer's profile, opening note, officials present. A
        #    baseline interview filed under the opening ceremony is a category error a reader has to
        #    unlearn, and its METRIC row is already two-fifths spent against a hard cap of four.
        #  * `clusterBackground` (stage 4) is about the cluster and the CRAFT — geography, history,
        #    motifs, GI status. The questionnaire is about individual artisans.
        #  * `surveySummary`/`surveyPlan` (stages 7–8) are the MARKET survey: consumers, retailers,
        #    wholesalers, exporters. A different instrument and a different population — the exact
        #    two-unrelated-things-sharing-one-word confusion stage 7's `placeType` note warns about.
        #  * `archive` (stage 21) is about files preserved and checksums, not sources cited. Stages
        #    9–20 and 22 are the workshop's own output; nothing there cites a baseline.
        #
        # ── AND IT IS CHEAP AND SAFE, FOR FOUR REASONS THAT ARE WORTH CHECKING BEFORE COPYING IT ──
        #  1. NO MIGRATION. Entities are not Prisma models — `EntitySpec.name` is a display/model
        #     name and every value is stored as JSON in `DwStageEntry`. There are ten `Dw…` models in
        #     the schema and not one of them is a per-entity table.
        #  2. NOTHING ALREADY SUBMITTED CHANGES. `report_builder` renders singleton content only
        #     under `if single is not None and singleton_data:`, and a brand-new `entityKey` has zero
        #     stored rows — so `singleton_data` is empty for every existing workshop, `has_any` is
        #     unchanged, and every already-filed document renders byte-identical.
        #  3. STAGE 6 HAD NO SINGLETON, so `StageSpec.singleton` — which returns the FIRST one —
        #     stays unambiguous. Adding a second singleton to a stage that already has one would not,
        #     and `WorkshopData.singletons` is keyed by STAGE and not by entity.
        #  4. It gives the artisan baseline a home for whatever else it legitimately gains later,
        #     instead of wedging a citation into a stage about the opening ceremony.
        #
        # DECLARED FIRST, matching every one of the nine other stages that has a singleton and
        # matching the order `_render_stage` prints in (singleton, then collections). The form and the
        # report then agree about what comes first on this stage.
        single(
            "artisanBaseline",
            "DwArtisanBaseline",
            "Artisan baseline",
            (
                # ── THE SITTING, NOT THE FORM, AND THE LABEL HAS TO SAY SO ────────────────────────────
                #
                # There is exactly ONE global artisan questionnaire — `QuestionnaireSection.code` is
                # `@unique` and its `sortOrder` is `@@unique` globally — so "choose the questionnaire" is
                # not a choice. What a designer picks is a sitting: a title, a date, a place, a language,
                # a named set of artisans and a review status. If this said "questionnaire" the designer
                # would think they were picking a form. The five reasons this is the GLOBAL instrument's
                # interview and not the per-workshop custom `Questionnaire` are on
                # `REFERENCE_MODELS["QuestionnaireInterview"]`; the short version is that the custom form
                # is the workshop's own METHOD, is already attached through `Questionnaire
                # .designWorkshopId`, has no `workshopId` for the scope machinery to read, is private by
                # design, and carries no reviewer's verdict.
                #
                # WORKSHOP-SCOPED, because an interview is an EVENT and not an entity. `artisanRef`,
                # `craftRef` and `toolRef` are ALL because an artisan, a craft and a tool exist
                # independently of any one study and are legitimately reused across them. There is no
                # reuse story for a sitting: you cannot use somebody else's afternoon as evidence about
                # your cluster.
                #
                # NO `ref_filter_by`, and it could not have one: `QuestionnaireInterview` has no
                # `artisanId` column — the link is a many-to-many — and the filter arm applies a scalar
                # column name. The picker is not on a per-artisan row in any case.
                f(
                    "interviewRef",
                    "Linked questionnaire interview",
                    REF,
                    S,
                    ref_model="QuestionnaireInterview",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="Choose an artisan questionnaire interview conducted for this workshop. The "
                    "citation below is filled in from it.",
                ),
                # ── EVERY ONE OF THESE IS KEY_VALUE, AND ON A SINGLETON THAT IS THE ONLY ANSWER ───────
                #
                # `ReportRole.TABLE_COLUMN` is defined as "a column when the entity is a collection". A
                # singleton is rendered through `_render_narrative` plus a `MetricRowBlock` plus a
                # gallery — there is no table for a column to be in — so this is not a concession to a
                # width budget, it is the only available role. (KEY_VALUE is `FieldSpec`'s default and is
                # left implicit, as everywhere else in this file.)
                #
                # AND ZERO METRICs, DELIBERATELY. `report_builder` takes `metrics[:4]` and SILENTLY drops
                # a fifth, so the METRIC row is the singleton analogue of the width budget.
                # `interviewQuestionsAnswered` and `interviewArtisanCount` read as headline numbers and
                # are still KEY_VALUE: the block is a citation, not a scoreboard, and keeping all four
                # slots free means a later, genuinely headline baseline figure does not have to evict a
                # count to get printed.
                # THE TITLE IS FREE TEXT AND CROSSES VERBATIM, WHICH IS A NARROWER GUARANTEE THAN THE
                # ONE BELOW READS AS. `QuestionnaireInterview.title` is typed by a researcher, unbounded
                # in Postgres, and printed into a submitted document exactly as typed. So the refusal
                # further down covers the STRUCTURED roster columns — the artisan rows and
                # `artisanSetKey` — and cannot cover a name somebody typed into the title instead:
                # "Sita Devi & Ramesh Meher, 12 Mar" defeats it with nothing in the path to notice.
                # `section_codes_from_title` exists BECAUSE researchers title interviews by content, and
                # the interview search reads `title` alongside `notes`, so this is a habit rather than a
                # hypothetical.
                #
                # STILL CARRIED, AND NOT REDACTED, ON TWO GROUNDS. It is the model's LABEL COLUMN — the
                # picker's own trigger text and `referenceDisplayHint`'s probe — so it is what the
                # designer read on screen before choosing, and a citation with no identifier is not a
                # citation. And it is an ORDINARY EDITABLE BOX: hydration fills it, the designer can
                # correct it for this workshop, and the help below asks them to. A machine redaction
                # could only mangle the legitimate titles, which are the overwhelming majority.
                #
                # `interviewPlace` is the same kind of value and the same argument; it is the sitting's
                # own free-text place, which is a fact about an EVENT rather than about a person.
                fromref(
                    "interviewTitle",
                    "Artisan questionnaire interview",
                    T,
                    S,
                    max_length=220,
                    help="The interview record's own title, as the researcher typed it. It prints "
                    "in the report exactly as it stands here — if it names the artisans, "
                    "shorten it to the sitting.",
                ),
                # TWO DIFFERENT FACTS, and the pair is the point — when the sitting HAPPENED, and (near
                # the bottom) when somebody TYPED IT IN. `documentedOn` carries the same distinction on
                # every other model.
                fromref("interviewDate", "Interview date", DATE, S),
                fromref(
                    "interviewPlace",
                    "Place of the interview",
                    T,
                    S,
                    max_length=220,
                    help="The sitting’s own free-text place, as the interview record states it.",
                ),
                fromref("interviewLanguage", "Language of the interview", T, S, max_length=80),
                # THE COUNT AND NEVER THE NAMES. A sitting may cover artisans who are NOT on this
                # workshop's roster — `record_filters.artisan_workshop_clause` treats "sat in an
                # interview taken at this workshop" as one of three ways an artisan reaches a workshop —
                # so naming them would have a submitted report disclose that a particular person from
                # another cluster was interviewed. The number is load-bearing rather than decorative:
                # `questionnaire_consolidation` argues that "a quote from a five-person sitting is
                # different evidence from the same sentence said alone".
                # `unit=` AND NOT A BARE NUMBER, WHICH IS A REPORT RULE AND NOT A STYLE CHOICE.
                # `format_value` appends the unit and nothing else does — a table header carries the
                # label alone and `help` is printed by no part of the report pipeline — so a hydrated
                # numeric either states its unit or prints as a figure the reader completes for
                # themselves. The registry's other 28 bare counts are not hydration targets and are
                # never checked; these three are the first counts to become one, and
                # `test_a_carried_measurement_says_what_it_is_measured_in` is what said so.
                fromref(
                    "interviewArtisanCount",
                    "Artisans in the sitting",
                    INT,
                    S,
                    min_value=0,
                    unit="artisans",
                ),
                # "ANSWERED" AND NOT "REACHED", IN BOTH PLACES. The label is the one that prints, and it
                # used to sit over a help line saying "reached" and over a count that measured neither —
                # it counted response rows, blank ones included, so this box could read 9 beside
                # "Questions answered: 0". `_interview_sections_covered` now counts a section the way
                # `questionnaire._derived_completed_sections` counts it for the View Data matrix: content
                # recorded, whether as a non-blank answer or as media filed against the section. That
                # second arm is what makes the number right for an interview captured as one audio clip
                # per section, which is the app's ordinary way of taking one.
                fromref(
                    "interviewSectionsCovered",
                    "Sections answered",
                    INT,
                    S,
                    min_value=0,
                    unit="sections",
                    help="Sections of the questionnaire with something recorded against them — an "
                    "answer typed, or an audio clip or file filed under the section. Blank "
                    "left by tabbing through a section does not count.",
                ),
                # NO DENOMINATOR AND NO PERCENTAGE COMPLETE. "84 of 112" is refused twice over: the
                # global instrument is not a relation on the interview so it is not reachable, and the
                # instrument is EDITABLE (`isActive`, `retiredAt`, `supersededById` on both question
                # tables) so a denominator frozen at save time silently means "of the 112 active on the
                # day this was picked" while the report never re-resolves. A frozen ratio against a live
                # instrument becomes wrong without anybody touching the report.
                fromref(
                    "interviewQuestionsAnswered",
                    "Questions answered",
                    INT,
                    S,
                    min_value=0,
                    unit="questions",
                    help="Questions with an answer recorded against them. A saved but empty "
                    "answer is not counted.",
                ),
                fromref("interviewLastAnsweredOn", "Last answered on", DATE, S),
                # 202 AND NOT 200, AND THE TWO CHARACTERS ARE THE FAMILY FORMULA'S ANSWER FOR A
                # NINE-LETTER SUBJECT — WHICH IS NOT THE SAME CLAIM AS "the worst case is 202", AND THIS
                # COMMENT USED TO MAKE THE SECOND ONE. Both halves matter, so both are written down.
                #
                # THE FORMULA. `test_the_media_note_cannot_overrun_the_bound_its_field_declares` measures
                # the WIDEST sentence `_media_note` can build for a subject — every type word present AND
                # the numbered-making clause present, which is the only shape printing all six of its
                # integers — and gets `6 * digits + intercept`, the intercept varying with the subject
                # word because the subject sits inside the sentence. At twelve digits per count that is
                # 197 for "tool", 198 for "craft", 200 for "artisan" and "product", and 202 here.
                #
                # THE OTHER FOUR DECLARE A FLAT 200, which is the formula's number for the LONGEST of
                # their four subject words — so "tool" and "craft" ride on it with a few characters to
                # spare. "interview" is two letters longer than the longest of them, which is the whole of
                # why this is the one field that could not simply reuse 200. That much of the previous
                # comment was right; what it got wrong is below.
                #
                # WHAT THE FORMULA DELIBERATELY OVER-COUNTS, because the previous version of this comment
                # read as though it did not. Only ONE of the five call sites passes a `numbered_prefix`
                # (`tool.recordMediaNote`, for `ToolForm`'s `STAGE_STEP_<n>` sequence). The other four —
                # this one included — cannot produce the numbered clause at all, so they cannot reach the
                # bound: the clause is 40 characters plus a sixth count. This subject's REACHABLE worst
                # case at twelve digits is 150 exactly —
                #     prefix 34 ("Attached to the " 16 + "interview" 9 + " record: " 9)
                #   + words 47 (" photographs" 12, " videos" 7, " audio notes" 12, " documents" 10,
                #               " files" 6, each with its leading space)
                #   + separators 8 + digits 60 (5 x 12) + "." 1
                # — leaving 52 characters of headroom, and the same asymmetry leaves the artisan and
                # product notes 52 of theirs and the craft note 54.
                # `test_the_media_note_headroom_is_measured_rather_than_implied` pins all three numbers
                # for all five fields, so the next reader gets the split from an assertion rather than
                # from this paragraph — and finds out immediately if a subject word or a call site moves.
                #
                # WHY THE CONSERVATIVE NUMBER IS THE ONE DECLARED. `coerce_value` REFUSES an over-length
                # value instead of truncating it, so a bound one character short leaves the box blank and
                # paints an error on a field the designer never touched. A bound tight to 150 would also
                # break the day this call site gains a `numbered_prefix` or `_MEDIA_NOTE_WORDS` gains a
                # sixth row — either of which is a one-line change nobody would think to re-derive five
                # bounds for.
                fromref(
                    "interviewMediaNote",
                    "Media on the interview record",
                    T,
                    S,
                    max_length=202,
                    help="What the interview record carries — the audio recording of the sitting "
                    "is the usual one. A sentence counting the files, never the files.",
                ),
                fromref("interviewDocumentedOn", "Interview record entered on", DATE, S),
                # WHERE THE SITTING CAME FROM, and it is needed even though the picker is
                # WORKSHOP-scoped: on a design workshop with no linked `Workshop` the picker falls back
                # to the whole table and reports `scoped: false`, so an out-of-cluster sitting can
                # legitimately be picked and the printed row must then say so.
                fromref("interviewDocumentedAtWorkshop", "Documented under", T, S, max_length=220),
            ),
        ),
        many(
            "existingProduct",
            "DwExistingProduct",
            "Existing products",
            (
                # THE CASCADE. Pick the artisan, and the product dropdown below holds that artisan's
                # documented products and nothing else.
                #
                # Both halves are load-bearing. Without the artisan the product list is every
                # product in the cluster — several hundred in a mature one — and a designer
                # scrolling that list types the product name in by hand instead, which is how the
                # baseline ends up as thirty free-text rows that cannot be joined to the product
                # records they were measured from. Without the product ref the price, material and
                # photographs are all retyped from a record that already holds them, and the two
                # copies disagree the first time either is corrected.
                f(
                    "artisanRef",
                    "Artisan",
                    REF,
                    S,
                    ref_model="Artisan",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="Whose product this is. Choosing one narrows the product list below to that "
                    "artisan’s documented products.",
                ),
                f(
                    "productRef",
                    "Documented product",
                    REF,
                    S,
                    ref_model="ProductDocumentation",
                    ref_filter_by="artisanRef",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="Choose the product record instead of re-entering it. Pick the artisan "
                    "first to narrow this list.",
                ),
                fromref("artisanName", "Made by", T, S, report_role=COL, column_width_pct=16.0),
                f("productCode", "Product code", T, S, report_role=COL, column_width_pct=12.0),
                fromref(
                    "name", "Product", T, B, required=True, report_role=COL, column_width_pct=22.0
                ),
                fromref("localName", "Name in the local language", T, S),
                # `fromref`, WHICH IT SHOULD ALWAYS HAVE BEEN. This box has been hydrated since the
                # mapping was written and carried no help text at all — the exact mirror of
                # `tool.source`, which promised a carry it never had. Both directions of that drift
                # are now asserted by `test_reference_carry.py`.
                fromref(
                    "category",
                    "Category",
                    ENUM,
                    S,
                    enum="PRODUCT_CATEGORY",
                    report_role=COL,
                    column_width_pct=16.0,
                    help="What the product IS. Only filled in from the linked record when the "
                    "record’s own type answers this question — see “Type on the record”.",
                ),
                # THE SOURCE RECORD'S OWN ANSWER, IN ITS OWN WORDS. `ProductType` has six members and
                # only two of them mean anything to `PRODUCT_CATEGORY` above: a FINISHED_GOOD may be a
                # saree or a bag, and a SAMPLE is a saree that happens not to be for sale. Before this
                # box existed those four answers were simply lost at the picker, because the only place
                # they could have landed was a column that would have printed them as a wrong category.
                fromref(
                    "recordType",
                    "Type on the record",
                    ENUM,
                    S,
                    enum="PRODUCT_TYPE",
                    help="What kind of record this is on the product documentation — a different "
                    "question from the category above.",
                ),
                # ── MEASUREMENTS: THE SOURCE IS IN INCHES AND THESE BOXES SAY CM ────────────────
                # `_inches_to_cm` in `design_workshops` carries the argument. The short version: the
                # source columns are `lengthInches`/`breadthInches`/`heightInches`, these three declare
                # `unit="cm"`, and a mapping pair without the ×2.54 would have written 12 into a box
                # printed as "12 cm" for a saree 30.48 cm long — permanently, because only-fill-blanks
                # never lets the row go back to unanswered. `breadthInches` lands on `widthCm` because
                # the two words are one measurement and neither model has both. `weightG` has no source
                # column at all and stays a workshop-only answer.
                fromref("lengthCm", "Length", DEC, B, unit="cm", min_value=0),
                fromref("widthCm", "Width", DEC, B, unit="cm", min_value=0),
                fromref("heightCm", "Height", DEC, S, unit="cm", min_value=0),
                # ── WHO OR WHAT MEASURED THE THREE NUMBERS ABOVE ─────────────────────────────────
                #
                # Some of them are a vision model's reading of a photograph of the product on graph
                # paper. `records.merge_field_provenance` has stamped that on the record's own inch
                # columns since the record half landed, and `record_fields.dims_with_method` prints it on
                # the record sheet — but hydration copied only the NUMBER, so a designer, a reviewer and
                # a ministry officer read a machine's estimate as a measurement somebody took, under the
                # NAME of whoever saved the record. `design_workshops._measurement_method_note` carries
                # the answer; read it before changing this field.
                #
                # ONE BOX AND NOT ONE PER DIMENSION, AND THE WORDING IS WHY. Hydration only fills BLANKS,
                # so a designer who measured the saree themselves keeps their own length beside a
                # hydrated width — and a per-dimension label would then sit over the designer's own
                # figure and call it a model's estimate. This sentence is about the RECORD's columns, so
                # it stays true whatever the designer typed into the boxes above.
                #
                # It says BREADTH where the box above says Width because the product record's column is
                # `breadthInches`; the two words are one measurement and the record's word is the honest
                # one in a sentence about the record. TEXT and never a TABLE_COLUMN — this table's
                # declared widths already sum to 100, and invariant 7 forbids rebalancing them.
                fromref(
                    "measurementMethodNote",
                    "How the record's measurements were taken",
                    T,
                    S,
                    max_length=200,
                    help="How the product record's own length, breadth and height were arrived "
                    "at — a tape reading, marks on a photograph, or a vision model's "
                    "estimate. It describes the record, not a number you type here.",
                ),
                f("weightG", "Weight", DEC, S, unit="g", min_value=0),
                fromref(
                    "dimensionsNote",
                    "Dimensions (as described)",
                    T,
                    B,
                    report_role=COL,
                    column_width_pct=18.0,
                    help="Free text, for a product the measured fields do not suit. Filled in "
                    "from the linked record’s “size”.",
                ),
                fromref(
                    "price",
                    "Selling price",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                # The other half of the cost question, and the product record has held it all along:
                # the BASELINE cost of a product the cluster already sells, printed beside the selling
                # price above it as the comparison figure a reader needs to judge the workshop's own.
                #
                # NOT AN INPUT TO STAGE 17'S COST SHEET, which this comment used to claim ("the one
                # stage 17's cost sheet actually needs"). That sheet's `productRef` points at
                # `DwFinalProduct` — the thing the workshop MADE — and no mapping hydrates it from
                # here, so a reader who follows that sentence goes looking for wiring that does not
                # exist. Nothing to add: KEY_VALUE is the default role and the field already prints.
                fromref("costOfMaking", "Cost of making", MONEY, S, unit="INR", min_value=0),
                # ── THE FOUR NARRATIVE BOXES OF THE RECORD PAGE, WHICH ARRIVED AS ONE-LINE INPUTS ────
                #
                # The product form calls `material` / `mainToolsUsed` / `use` / `remarks` its narrative
                # fields and gives all four the full editor, noting that raw materials "are lists as often
                # as they are sentences, which is exactly what the editor's bullet button is for". The
                # workshop gave `material` a SINGLE-LINE text box for the same fact and the other three a
                # textarea whose dictation can only append to the end. RICH is the same control the record
                # page uses, on both surfaces, by reuse rather than reimplementation.
                #
                # REPORT ROLES AND WIDTHS ARE UNTOUCHED, deliberately: changing where a value prints in
                # documents already submitted is a different decision from changing the input affordance
                # and must not ride along inside it. `material` stays a 16%-wide TABLE_COLUMN and is the
                # first RICH field in a table cell anywhere in the registry — `_cell_runs` is called from
                # the table path as well as the key-value path and holds runs either way, so it keeps its
                # bold and loses its paragraph breaks to single spaces, which is what a cell can carry.
                #
                # The promotion blanks nothing: `coerce_value` reads a plain string as unformatted prose,
                # and hydration writes exactly that (`_reference_data` flattens the source's own marks).
                # Recovering the researcher's formatting through the carry is a separate change to
                # `_reference_data` and is deliberately not attempted here.
                fromref("material", "Material", RICH, S, report_role=COL, column_width_pct=16.0),
                f("materialFamily", "Material family", ENUM, S, enum="MATERIAL_FAMILY"),
                fromref("mainToolsUsed", "Main tools used", RICH, S),
                f("technique", "Technique", T, S),
                fromref("use", "Use", RICH, S),
                f("traditionType", "Traditional or contemporary", ENUM, S, enum="TRADITION_TYPE"),
                f("productionTimeDays", "Production time", DEC, S, unit="days", min_value=0),
                # BESIDE `productionTimeDays`, NOT INSTEAD OF IT, and not parsed into it. The source
                # column is free text — "about three days", "2 weeks", "one season" — and a parser
                # that reads "2 weeks" as 2 puts a wrong number into the cost sheet above while one
                # that gives up quietly leaves the blank this lane exists to end. Same pairing, same
                # reason, as `tool.toolType` beside `tool.toolFamily`: the words are what the report
                # prints, the number is what a calculation can use.
                fromref(
                    "productionTimeNote",
                    "Time to make (as recorded)",
                    T,
                    S,
                    help="As written on the product record, in the researcher’s own words.",
                ),
                f("monthlyCapacity", "Monthly capacity", INT, S, unit="pieces", min_value=0),
                # FOUR OF THE FIVE MarketDemand TOKENS CROSS. `UNKNOWN` is the source column's
                # `@default` with no blank option on either form, so an untouched product record says
                # UNKNOWN about a market nobody was asked about — it translates to None rather than to
                # this field's "Not known" label, which used to PRINT under every imported product. See
                # the second rule above the translation tables in `design_workshops`.
                #
                # The remaining four are written out as an explicit total table there rather than a
                # passthrough, because the two lists are versioned separately and "they match today" is
                # not a thing a mapping may rely on silently. NOT the same question as `marketChannel`
                # below.
                fromref("marketDemand", "Market demand", ENUM, S, enum="DEMAND_LEVEL"),
                f("marketChannel", "Market channel", MENUM, S, enum="MARKET_CHANNEL"),
                f("problems", "Problems reported", RICH, S, report_role=NARR),
                fromref("craftName", "Craft on the product record", T, S),
                fromref("place", "Place on the product record", T, S),
                # ── THE RECORD'S OWN STATED ADDRESS, IN BOXES OF ITS OWN ────────────────────────────
                #
                # The record page collects a full location and none of it used to cross: this row had one
                # free-text `place` and nothing else, so a thing documented in Barpali, Bargarh, Odisha
                # arrived as whatever somebody had typed into a single box. `place` above is unchanged —
                # it is the denormalised column — and these four are what the page actually asks.
                #
                # KEY_VALUE, and NOT extra table columns: this entity's declared widths already govern
                # its table and four more columns would push it onto the proportional fallback, silently
                # re-laying-out a table that is in submitted documents. `report_builder` prints
                # KEY_VALUE in the per-row block beneath, so nothing is lost.
                #
                # STATED, never the device's fix. See the model's `include` in `design_workshops`.
                fromref("recordState", "State on the record", T, S, max_length=80),
                fromref("recordDistrict", "District on the record", T, S, max_length=80),
                fromref("recordVillage", "Village on the record", T, S, max_length=120),
                # Same rule as `participant.pincode`, checked against the normalised value. See there.
                fromref(
                    "recordPincode",
                    "PIN code on the record",
                    T,
                    S,
                    max_length=10,
                    text_format=TextFormat.PINCODE,
                ),
                # THE PIN, WHICH IS THE HALF OF THE ADDRESS THAT IS NOT A STRING. The four boxes above
                # are the record's stated address; this is the pin a researcher dropped on the product's
                # own place with the map picker, which is about the place and not about the desk — the
                # distinction invariant 4 turns on and `_subject_point` enforces. `participant` has
                # carried its equivalent since it was written and this entity declared no GEO field at
                # all, so the four strings landed and the one coordinate that is genuinely about the
                # village reached nothing. GEO renders as the map picker on the web and as the location
                # card on the handset, so the record page's affordance carries with the type.
                fromref(
                    "recordSubjectLocation",
                    "Pinned place on the product record",
                    GEO,
                    A,
                    help="The pin a researcher dropped on the place the product was documented, "
                    "not the device’s position when the record was typed.",
                ),
                # See the note on `material` above for the promotion; this is the fourth of the record
                # page's narrative boxes, and the one its EXIF summary is appended INTO as a paragraph.
                fromref("remarks", "Remarks on the product record", RICH, S),
                # WHAT THE RECORD HAS ATTACHED BEYOND THE PHOTOGRAPH SEEDED BELOW. `_reference_photos`
                # resolves one IMAGE, so an audio note in which the artisan explains the piece, or a video
                # of it being finished, existed on the record and was invisible to the workshop and to the
                # report. A sentence and never the ids — see `_media_note`, and note that this is not the
                # gallery rule: nothing here proposes overwriting the designer's own photographs.
                fromref("recordMediaNote", "Media on the product record", T, S, max_length=200),
                fromref("documentedOn", "Product documented on", DATE, S),
                # Written out rather than built by ``photos()`` for the sake of the one extra
                # sentence: the documented product's own photograph is seeded into this gallery, and
                # a designer who is not told that will add it a second time.
                fromref("productPhotos", "Photographs", IMGS, B, report_role=GALLERY),
                fromref(
                    "productPhotosCaption",
                    "Photograph caption",
                    T,
                    B,
                    caption_for="productPhotos",
                    report_role=CAP,
                ),
                f("viewFront", "Front view", IMG, A),
                f("viewBack", "Back view", IMG, A),
                f("viewDetail", "Detail view", IMG, A),
                f(
                    "turntablePhotos",
                    "360° capture",
                    IMGS,
                    A,
                    phase_note="Reviewer: “Kumarjit and Rishi plug in”; “AI-driven work for AI team "
                    "— may be as plug in — later to be discussed not now”.",
                ),
            ),
            label_field="name",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 7. Survey / Market Survey Planning
# --------------------------------------------------------------------------------------

STAGE_7 = StageSpec(
    number=7,
    key="SURVEY_PLANNING",
    title="Market Survey Planning",
    purpose=(
        "What the survey is meant to find out, who will be asked, where, and with which questions."
    ),
    # Source document: the Advanced tier here was to be “a simple upload tool and phase 2 work”.
    # The upload slot exists (`questionnaireFile`, with the remark kept on its `phase_note`); the
    # phasing is not a designer's business.
    entities=(
        single(
            "surveyPlan",
            "DwSurveyPlan",
            "Survey plan",
            (
                f(
                    "objectives",
                    "Survey objectives",
                    RICH,
                    B,
                    required=True,
                    report_role=BULLETS,
                    help="One objective per line.",
                ),
                f(
                    "questionnaire",
                    "Questionnaire",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                    help="The questions to be asked. One per line.",
                ),
                f(
                    "questionnaireFile",
                    "Questionnaire document",
                    FILE,
                    A,
                    phase_note="Reviewer: “May be a simple upload tool and phase 2 work”.",
                ),
                f("respondentGroups", "Respondent groups", MENUM, S, enum="RESPONDENT_GROUP"),
                f("sampleSize", "Intended sample size", INT, S, min_value=0),
                f("surveyStartDate", "Survey start date", DATE, S),
                f("surveyEndDate", "Survey end date", DATE, S),
                f(
                    "languages",
                    "Questionnaire languages",
                    TAGS,
                    A,
                    help="Languages the questionnaire was translated into.",
                ),
            ),
        ),
        many(
            "surveyPlace",
            "DwSurveyPlace",
            "Places to visit",
            (
                f("name", "Place", T, B, required=True, report_role=COL, column_width_pct=26.0),
                f(
                    "placeType",
                    "Type",
                    T,
                    S,
                    report_role=COL,
                    column_width_pct=20.0,
                    help="What the place is, as it is known locally. Choose the channel below as well, "
                    "so places can be compared across surveys.",
                ),
                # MARKET_CHANNEL, NOT A NEW "PLACE_TYPE" LIST, and that is the point of rule 2.
                #
                # The obvious reading of the source document's stage-7 row — "consumers; retailers;
                # wholesalers; exporters; artisans; fairs/emporia" — is a list of PLACE types, and it
                # is not: it is that stage's enumeration of RESPONDENT GROUPS, five of the six are
                # already members of RESPONDENT_GROUP, and `respondentGroup` on this very entity
                # already asks the question. Minting a parallel PLACE_TYPE from it would have given
                # this registry two unrelated tokens both spelt EMPORIUM — one here, one on
                # `existingProduct.marketChannel` and `buyerLink.buyerType` — and then "every emporium
                # we surveyed" could not be joined to "every product sold through an emporium", which
                # is the entire reason enums are shared across stages rather than declared per stage.
                #
                # MARKET_CHANNEL already holds every category this box has been used for. The five
                # values stored in `placeType` today map onto it without residue: the weekly town
                # market is LOCAL_HAAT, the permanent craft haat and exhibition ground is EXHIBITION,
                # the state handloom emporium is EMPORIUM, the apex co-operative showroom is
                # COOPERATIVE and the online buyer group is ONLINE. Kept beside the text field for the
                # reason given at `tool.toolFamily`.
                f(
                    "placeChannel",
                    "Market channel",
                    ENUM,
                    S,
                    enum="MARKET_CHANNEL",
                    help="Which kind of outlet this place is. The same list the products and the buyer "
                    "links use, so a survey can be compared with them.",
                ),
                f("cityDistrict", "City / District", T, S, report_role=COL, column_width_pct=20.0),
                f("plannedDate", "Planned date", DATE, S, report_role=COL, column_width_pct=16.0),
                f(
                    "respondentGroup",
                    "Respondents expected",
                    ENUM,
                    S,
                    enum="RESPONDENT_GROUP",
                    report_role=COL,
                    column_width_pct=18.0,
                ),
                f("notes", "Notes", LT, S),
            ),
            label_field="name",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 8. Market Survey / Field Data Capture
# --------------------------------------------------------------------------------------

STAGE_8 = StageSpec(
    number=8,
    key="MARKET_SURVEY_CAPTURE",
    title="Market Survey & Field Data",
    purpose=(
        "What the survey actually found: responses from each group, photographs of the market, "
        "prices seen, and the competing products on the shelf."
    ),
    # WHY THIS STAGE IS AS PLAIN AS IT IS. The source document asked for “a basic structure with
    # text and image fields at the beginning. Later we can refine.”, and deferred voice
    # transcription and competitor image matching to “Phase 3 and only 1 or 2 features”. That is
    # the reason the Advanced tier below is thin: it holds the data such a feature would produce
    # (a transcript, a competitor photo) without claiming the feature. A designer does not need to
    # know any of that to fill the stage in, so nothing here is a stage note.
    entities=(
        single(
            "surveySummary",
            "DwSurveySummary",
            "Survey summary",
            (
                f("notes", "Field notes", RICH, B, required=True, report_role=NARR),
                f(
                    "responsesCollected",
                    "Responses collected",
                    INT,
                    S,
                    min_value=0,
                    report_role=METRIC,
                ),
                f("consumerPreferences", "Consumer preferences", RICH, S, report_role=NARR),
                f(
                    "preferredCategories",
                    "Product categories in demand",
                    MENUM,
                    S,
                    enum="PRODUCT_CATEGORY",
                ),
                f("aestheticVsFunction", "Aesthetics versus function", RICH, S, report_role=NARR),
                f(
                    "materialColourPreferences",
                    "Material & colour preferences",
                    RICH,
                    S,
                    report_role=NARR,
                ),
                f("priceSensitivity", "Price sensitivity", RICH, S, report_role=NARR),
                f("retailerFeedback", "Retailer & buyer feedback", RICH, S, report_role=NARR),
                f("bestSellers", "Best sellers observed", RICH, S, report_role=BULLETS),
                f("marketGaps", "Market gaps", RICH, S, report_role=BULLETS),
                # ── THE MARKET SURVEY DOCUMENT ──────────────────────────────────────────────────────
                #
                # Asked for by the owner on 2026-08-25: the survey as it was written up, uploaded whole,
                # in PDF or in a word-processor format, and previewed in the app where it is a PDF.
                #
                # STAGE 8 AND NOT STAGE 7, which is the one judgement here. Stage 7 already carries
                # `questionnaireFile`, and that is the BLANK instrument — the questions before anybody
                # was asked them. This is the FINDINGS: the document a designer or an agency compiles
                # after the market visits, which is why it sits beside the field notes it summarises
                # rather than beside the questions it answers. Filing both under one key would make
                # "show me the questionnaire we used" and "show me what the survey found" the same
                # request with two different answers depending on who uploaded last.
                #
                # STANDARD, NOT ADVANCED, unlike `questionnaireFile` beside it. That field is Advanced
                # because the source document deferred the upload tool itself; the tool exists now, and
                # the web form collapses ADVANCED behind a disclosure — a document nobody finds is a
                # document nobody attaches. No `report_role`: an attachment is not a printable value,
                # and NO PATH CARRIES A FILE INTO THE DELIVERED DOCUMENT — this line used to name
                # `report_annexures` as that path and it is not one. That module is transcripts only, and
                # `_render_media_annexure` gathers through the image path (IMAGE/IMAGE_LIST), so a FILE
                # reaches the report as its label plus a count and `build_report` warns that the bytes
                # are not inside it. Corrected here because the identical false claim was made three
                # times in one wave — see `designerCv` above, which records the measurement in full.
                f(
                    "surveyDocument",
                    "Market survey document",
                    FILE,
                    S,
                    help="The written-up survey — PDF, .docx or .odt. A PDF is previewable in the app; "
                    "other formats are stored and downloadable.",
                ),
                *photos("marketPhotos", "Market photographs", B, "Market photograph caption"),
            ),
        ),
        many(
            "surveyResponse",
            "DwSurveyResponse",
            "Survey responses",
            (
                f("respondentName", "Respondent", T, B, report_role=COL, column_width_pct=20.0),
                f(
                    "respondentGroup",
                    "Group",
                    ENUM,
                    B,
                    required=True,
                    enum="RESPONDENT_GROUP",
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("place", "Place", T, S, report_role=COL, column_width_pct=18.0),
                f("surveyDate", "Date", DATE, S, report_role=COL, column_width_pct=14.0),
                f(
                    "response",
                    "Response",
                    LT,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=32.0,
                ),
                f("productsDiscussed", "Products discussed", TAGS, S),
                f("priceExpectation", "Price expectation", MONEY, S, unit="INR", min_value=0),
                # The other `FieldType.PHONE` in the registry, and it gets the same rule as
                # `participant.phone` — a typed type with no check is the same hole wherever it sits.
                # This one is a respondent met in a market rather than a hydrated roster row, so
                # nothing legacy is stored behind it; the format is declared for consistency and
                # because `PhoneField` is mounted on it with `mirror={false}`, whose advisory message
                # would otherwise be the only thing between a nine-digit number and a report.
                # `max_length=20` for the reason written at `participant.phone`: the format bounds the
                # digits and not the string, so without it this box takes a paragraph with a number in
                # it. 20 is the longest value either picker composes.
                f(
                    "contact",
                    "Contact",
                    FieldType.PHONE,
                    S,
                    max_length=20,
                    text_format=TextFormat.PHONE_IN,
                ),
                f(
                    "location",
                    "Location",
                    GEO,
                    A,
                    phase_note="Reviewer: “Phase 3 and only 1 or 2 features” (geo/date tagging).",
                ),
                f("voiceNote", "Voice recording", AUDIO, A),
                f("voiceTranscript", "Transcript", RICH, A, report_role=NARR),
                *photos("responsePhotos", "Photographs", S, "Photograph caption"),
            ),
            label_field="respondentName",
        ),
        many(
            "competitorProduct",
            "DwCompetitorProduct",
            "Competitor products",
            (
                f("name", "Product", T, B, required=True, report_role=COL, column_width_pct=24.0),
                f("seller", "Seller / brand", T, S, report_role=COL, column_width_pct=20.0),
                f(
                    "category",
                    "Category",
                    ENUM,
                    S,
                    enum="PRODUCT_CATEGORY",
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("material", "Material", T, S, report_role=COL, column_width_pct=16.0),
                f(
                    "price",
                    "Price",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f("origin", "Origin", T, S, report_role=COL, column_width_pct=10.0),
                f("observation", "Observation", RICH, S, report_role=NARR),
                f("photo", "Photograph", IMG, S, report_role=GALLERY),
                f("photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP),
            ),
            label_field="name",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 9. Market Analysis & Design Direction
# --------------------------------------------------------------------------------------

STAGE_9 = StageSpec(
    number=9,
    key="MARKET_ANALYSIS_DIRECTION",
    title="Market Analysis & Design Direction",
    purpose=(
        "What the survey means: the SWOT, the price bands the market will bear, and the design "
        "opportunities that follow from the evidence."
    ),
    # Source document: most Standard-tier items here were marked optional — which is why almost
    # nothing below is `required=True` — and the AI-assisted Advanced tier was deferred to
    # “Phase 3 and some of them by the AI team”.
    entities=(
        single(
            "marketAnalysis",
            "DwMarketAnalysis",
            "Analysis",
            (
                f("surveyFindings", "Survey findings", RICH, B, required=True, report_role=NARR),
                f(
                    "mainOpportunities",
                    "Main design opportunities",
                    RICH,
                    B,
                    required=True,
                    report_role=BULLETS,
                    help="One opportunity per line.",
                ),
                f("demandLevel", "Overall demand", ENUM, S, enum="DEMAND_LEVEL"),
                f("targetConsumers", "Target consumers", RICH, S, report_role=NARR),
                f(
                    "rawMaterialImplications",
                    "Raw-material implications",
                    RICH,
                    S,
                    report_role=NARR,
                ),
                f("competitorAnalysis", "Competitor analysis", RICH, S, report_role=NARR),
                f("supplyChainIssues", "Supply-chain issues", RICH, S, report_role=NARR),
                f("artisanSkillMapping", "Artisan skill mapping", RICH, S, report_role=NARR),
                f("trendObservations", "Trend observations", RICH, S, report_role=NARR),
                f("colourObservations", "Colour observations", RICH, S, report_role=NARR),
                f("materialObservations", "Material observations", RICH, S, report_role=NARR),
            ),
        ),
        many(
            "swotPoint",
            "DwSwotPoint",
            "SWOT analysis",
            (
                f(
                    "kind",
                    "Kind",
                    ENUM,
                    B,
                    required=True,
                    enum="SWOT_KIND",
                    report_role=COL,
                    column_width_pct=18.0,
                ),
                f("point", "Point", LT, B, required=True, report_role=COL, column_width_pct=52.0),
                f(
                    "evidence",
                    "Evidence",
                    LT,
                    S,
                    report_role=COL,
                    column_width_pct=30.0,
                    help="Which survey response or observation supports this.",
                ),
            ),
            label_field="point",
        ),
        many(
            "priceBand",
            "DwPriceBand",
            "Price bands",
            (
                f(
                    "category",
                    "Category",
                    ENUM,
                    B,
                    required=True,
                    enum="PRODUCT_CATEGORY",
                    report_role=COL,
                    column_width_pct=26.0,
                ),
                f(
                    "lowPrice",
                    "From",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=18.0,
                ),
                f(
                    "highPrice",
                    "To",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=18.0,
                ),
                f(
                    "demand",
                    "Demand",
                    ENUM,
                    S,
                    enum="DEMAND_LEVEL",
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("notes", "Notes", LT, S, report_role=COL, column_width_pct=22.0),
            ),
            label_field="category",
        ),
        many(
            "designOpportunity",
            "DwDesignOpportunity",
            "Design opportunities",
            (
                f(
                    "title",
                    "Opportunity",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=28.0,
                ),
                f(
                    "description",
                    "Description",
                    LT,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=38.0,
                ),
                f(
                    "targetCategory",
                    "Target category",
                    ENUM,
                    S,
                    enum="PRODUCT_CATEGORY",
                    report_role=COL,
                    column_width_pct=18.0,
                ),
                f(
                    "priority",
                    "Priority",
                    ENUM,
                    S,
                    enum="SEVERITY",
                    report_role=COL,
                    column_width_pct=16.0,
                ),
            ),
            label_field="title",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 10. Design Brief / Concept Development
# --------------------------------------------------------------------------------------

STAGE_10 = StageSpec(
    number=10,
    key="DESIGN_BRIEF",
    title="Design Brief & Concept",
    purpose=(
        "The designer's own statement of what will be made and why: the concept, the market it "
        "is for, and the material, colour and motif direction it will follow."
    ),
    # Source document: the Standard tier was “some of these and only as optional fields”, and the
    # structured, evidence-generated brief was deferred to “phase 4” — which is why the brief here
    # is prose the designer writes rather than something assembled from stages 8 and 9. The
    # boundary in the note is the other half of stage 4's.
    notes=(
        "Traditional and contemporary design direction belongs here, in the brief — not in the "
        "cluster's craft background at stage 4, which records what the cluster already makes."
    ),
    entities=(
        single(
            "designBrief",
            "DwDesignBrief",
            "Design brief",
            (
                f(
                    "concept",
                    "Designer’s concept and vision",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                ),
                f(
                    "targetCategories",
                    "Target product categories",
                    MENUM,
                    B,
                    required=True,
                    enum="PRODUCT_CATEGORY",
                ),
                f("targetMarket", "Target market", RICH, S, report_role=NARR),
                f("userNeeds", "User needs", RICH, S, report_role=NARR),
                f("intendedPriceLow", "Intended price from", MONEY, S, unit="INR", min_value=0),
                f("intendedPriceHigh", "Intended price to", MONEY, S, unit="INR", min_value=0),
                f("materialDirection", "Material direction", RICH, S, report_role=NARR),
                f("colourDirection", "Colour direction", RICH, S, report_role=NARR),
                f("motifFormDirection", "Motif & form direction", RICH, S, report_role=NARR),
                # This field and its contemporary sibling were moved here from stage 4's cluster
                # background on the source document's instruction. `help` is a client-facing string
                # like `notes` is, and it used to end "…at the reviewer's request" — build-time
                # provenance on a designer's screen. The move itself is worth stating (someone who
                # knows the older shape will look in stage 4); who asked for it is not.
                f(
                    "traditionalDesignReference",
                    "Traditional design reference",
                    RICH,
                    S,
                    report_role=NARR,
                    help="The traditional designs this work draws on. Recorded here in the brief, not "
                    "in the cluster background at stage 4.",
                ),
                f(
                    "contemporaryDesignReference",
                    "Contemporary design reference",
                    RICH,
                    S,
                    report_role=NARR,
                ),
                f("sustainability", "Sustainability", RICH, S, report_role=NARR),
                f("functionality", "Functionality", RICH, S, report_role=NARR),
                f(
                    "craftIntegrityBaseline",
                    "Craft-integrity baseline",
                    RICH,
                    A,
                    report_role=NARR,
                    phase_note="Reviewer: “May be considered in phase 4 !!!”",
                    help="What must not change if the piece is still to be this craft.",
                ),
                *photos("moodBoard", "Mood & reference board", A, "Reference caption"),
            ),
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 11. Sketch Development
# --------------------------------------------------------------------------------------

STAGE_11 = StageSpec(
    number=11,
    key="SKETCH_DEVELOPMENT",
    title="Sketch Development",
    purpose="The design sketches produced during the workshop, each with its intent.",
    # Source document: the Standard tier was marked “optional fields”, and the Advanced
    # image-processing tier was pointed at another team's existing app. That remark survives
    # verbatim on `lineArtFile`'s `phase_note`, which is where deferred provenance belongs — and it
    # is now provenance ONLY, because the processing landed in this repository instead.
    #
    # THE NOTE BELOW USED TO DENY THE FEATURE, AND THAT WAS THE DEFECT. It read "Line-art and
    # vector files can be attached to a sketch here. The app stores them; it does not produce them
    # from the sketch itself" — true when it was written, and shipped unchanged past the releases
    # that gave BOTH clients a tracer. `notes` is serialised by `stage_to_dict`, printed under the
    # stage header on the web and under the sync line on the handset, and carried in the bundled
    # asset a phone renders from with no signal. So the one sentence a designer reads before
    # filling this stage in told them the app could not do the thing whose button was on the
    # screen in front of them, in the field, where there is nothing else to check it against.
    #
    # WHAT THE CLIENTS ACTUALLY DO, checked on 2026-08-28 rather than assumed:
    #   web       `components/designworkshop/FieldInput.tsx` mounts `SketchTraceField` — and
    #             `SketchRectifyField` beside it — inside the stage form's media block, gated on
    #             `stageFieldRoles.offersSketchRectify`: a FILE field whose key reads like a
    #             plate's, on an entity that also has an image field. In this registry that is
    #             `sketch.lineArtFile` and nothing else. It runs in a Web Worker, on the device,
    #             and uploads nothing.
    #   handset   the same panel, mounted from the same field:
    #             `ui/designworkshop/DwSketchTracePanel.kt`, beside `DwSketchTraceEngine.kt`,
    #             `DwSketchTraceParams.kt` and the rest of that family. `FieldInput.tsx`'s own
    #             comment records where the arrangement came from — "`dwOffersSketchTrace` IS
    #             `dwOffersSketchRectify`", one predicate for two panels — and says the web copied
    #             it from the handset rather than the other way round.
    #   re-check  `grep -rn "SketchTraceField" frontend/components` and
    #             `ls android/.../ui/designworkshop/ | grep Trace`.
    #
    # THE NOTE NAMES NO ENGINE, DELIBERATELY, AND THAT DECISION HAS NOW PAID FOR ITSELF.
    # When this comment was written the handset's JavaScript-bundle path was staged for deletion in
    # favour of the native `DwTraceKotlin*` runtime, and the note was written to name neither. On
    # 2026-08-28 the deletion happened: `assets/dw-trace-engine.js`, the `frontend/lib/trace/android/`
    # bridge, `frontend/scripts/build-trace-bundle.mjs` and `ANDROID-BUNDLE.md` are gone, and the
    # handset runs the Kotlin engine. The note below did not change a word. WHICH engine runs is not
    # a fact a designer in a courtyard can use, and it is the half that moves; where the plate goes,
    # and what is never overwritten, is the half that does not.
    #
    # (`docs/SKETCHES-PROTOTYPES-PARITY.md` still records the tracer as absent from the handset and
    # quotes the old note as its evidence. It is dated 2026-08-27 and it was already wrong before the
    # engine swap — the whole `DwSketchTrace*` family was in the tree. That document is not this
    # lane's to edit; it is named here so the next reader does not take it as the newer fact.)
    #
    # WHAT THE REPLACEMENT SAYS, AND WHY THOSE THINGS. Not "the app can trace" on its own, which a
    # designer can see: where the two artefacts go, and that one never overwrites the other.
    # `sketch.image` is a single IMAGE field and a single IMAGE field REPLACES its value when a
    # file is attached, which is the whole reason the plate has a field of its own — the one fact
    # here that cannot be deduced from the screen, and the one that costs a photograph when it is
    # got wrong.
    notes=(
        "“Sketch image” holds the photograph of the sheet and “Line art / vector file” holds the "
        "drawing made from it — two fields, so tracing never replaces the photograph. On the "
        "line-art field this device can straighten a photographed sheet and trace it into line "
        "art with no connection, and shows the result before anything is attached, so keeping the "
        "sheet exactly as photographed costs one press. A vector file made elsewhere goes in the "
        "same field."
    ),
    entities=(
        many(
            "sketch",
            "DwSketch",
            "Sketches",
            (
                f(
                    "sketchNo",
                    "Sketch number",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "name",
                    "Sketch name",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=24.0,
                ),
                f(
                    "version",
                    "Version",
                    INT,
                    A,
                    min_value=1,
                    help="Increment when a sketch is redrawn rather than replaced, so the iteration "
                    "history survives.",
                ),
                f(
                    "supersedesSketch",
                    "Supersedes sketch",
                    REF,
                    A,
                    ref_model="DwSketch",
                    report_role=HIDDEN,
                ),
                f("image", "Sketch image", IMG, B, required=True, report_role=GALLERY),
                f("imageCaption", "Sketch caption", T, B, caption_for="image", report_role=CAP),
                f(
                    "category",
                    "Product category",
                    ENUM,
                    S,
                    enum="PRODUCT_CATEGORY",
                    report_role=COL,
                    column_width_pct=18.0,
                ),
                f("intendedUse", "Intended use", LT, S),
                f("materials", "Materials", TAGS, S, report_role=COL, column_width_pct=18.0),
                f("lengthCm", "Length", DEC, S, unit="cm", min_value=0),
                f("widthCm", "Width", DEC, S, unit="cm", min_value=0),
                f("heightCm", "Height", DEC, S, unit="cm", min_value=0),
                f("dimensionsNote", "Dimensions (as described)", T, S),
                f("colours", "Colours", TAGS, S),
                f("motifs", "Motifs", TAGS, S),
                f("targetMarket", "Target market", T, S),
                f(
                    "expectedPrice",
                    "Expected price",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("designerNotes", "Designer’s notes", RICH, S, report_role=NARR),
                # THE FIELD THE TRACING AND STRAIGHTENING PANELS MOUNT ON, on both clients — see the
                # stage note above. `phase_note` is history and is not serialised; the `help` is what a
                # designer reads, so it has to say that this device can fill the field as well as
                # accept a file. Its first sentence is unchanged deliberately:
                # `frontend/components/designworkshop/stageFieldRoles.ts` quotes it verbatim to explain
                # which pairing `offersSketchRectify` is looking for.
                f(
                    "lineArtFile",
                    "Line art / vector file",
                    FILE,
                    A,
                    phase_note="Reviewer: “May be Deepika app for now”.",
                    help="An SVG or vector export, if one was produced. The panel on this field can "
                    "trace one from the sketch photograph, on this device.",
                ),
                f("annotations", "Annotations", RICH, A, report_role=NARR),
                # ── THE OVERRIDE MARKER, AND WHY THE ROW ORDER ALONE CANNOT BE IT ────────────────
                #
                # Sketches are ranked by dragging them, and the rank IS `DwStageEntry.ordinal` — both
                # clients already derive it from array order and both already have up/down arrows, so
                # ranking needed no new mechanism. What it had no way to record is WHO DECIDED and
                # WHEN, because `_ordinal` is one of the sync protocol's `_`-prefixed keys that
                # `entry_provenance.stamp` skips by name (“stamping it would put three phantom rows in
                # every provenance panel”). A reorder therefore has no author and no timestamp, and a
                # list of ten sketches in score order is byte-for-byte the same thing as a list of ten
                # sketches a designer deliberately arranged.
                #
                # THESE TWO FIELDS ARE THAT DIFFERENCE AND ONLY THAT: blank means the default sort
                # still stands. They are not written by a drag — a drag is a save of the ordinal like
                # any other — but by the act of FIXING an order, which is the moment somebody takes
                # responsibility for it over the computed score.
                #
                # A NAME, NOT AN ACCOUNT ID, and TEXT rather than REF for a checked reason: REF
                # resolves against the five `REFERENCE_MODELS` (Artisan, ProductDocumentation,
                # ToolDocumentation, Process, Craft) or an entity of this workshop, and `User` is not
                # among them, so a REF here would be a picker with nothing behind it. The precedent is
                # `sketchReview.reviewedBy` and `prototypeValidation.approvedBy`, both plain TEXT names
                # for the same reason — and a name is also what the report can print, where a cuid is
                # not. The ACCOUNT that saved it is recorded anyway, by `fieldProvenance`, which stamps
                # this key like any other designer-typed field.
                f(
                    "rankFixedBy",
                    "Rank fixed by",
                    T,
                    A,
                    help="Who settled this order, when it was set deliberately rather than left in "
                    "score order.",
                ),
                f(
                    "rankFixedAt",
                    "Rank fixed on",
                    DATE,
                    A,
                    help="Leave blank while the order is still the default one.",
                ),
                # ── THE SKETCH'S OWN END OF PEER REVIEW ──────────────────────────────────────────
                #
                # The sibling of `prototype.peerRoundClosedAt`, and it exists because the registry
                # already says twice over that a sketch has two review rounds. `sketchReview` — the
                # entity whose whole subject is a sketch — declares `reviewRound` over the two-token
                # REVIEW_ROUND list, and its own note reads "a sketch is reviewed by the people who
                # were in the room, and again … by the whole pool of designers". `design_ratings`
                # then names `sketch` alongside `prototype` in its rateable set, and the ledger's
                # `round` column carries the same two tokens for either. Three declarations assume a
                # sketch can be in a POOL round; before this field, the ONE thing that decides whether
                # a round is open — a finalisation date on the row — was declared on `prototype`
                # alone, so POOL was a value a designer could pick on a sketch review and a column the
                # ledger could never write. The omission was the outlier, not the rule.
                #
                # THE GATE IS THE SKETCH'S OWN DATE AND NOT ITS PROTOTYPES'. The obvious alternative
                # — open a sketch to the pool once some prototype carrying its `sketchRef` is
                # finalised — was rejected on three counts. It would never open the sketches stage 12
                # exists to record, the ones set aside and never prototyped, which are exactly the
                # designs a wider pool might pick up. It would make the gate a join computed at read
                # time in an architecture where every other gate is one column on the row being read.
                # And it would put the switch on a DIFFERENT row from the one being published: editing
                # or deleting a prototype would un-open the sketch above it, by a hand that never
                # touched the sketch. UN-OPENING IS STILL POSSIBLE HERE AND IS ACCEPTED — clearing this
                # date, or soft-deleting the row (`design_ratings.load_subject` returns None on
                # `deletedAt`), withdraws a sketch the pool has already seen — but it takes an edit to
                # the sketch itself, which is the workshop's own hand on its own row. An earlier draft
                # of this note claimed backwards was "the one direction this gate must never run",
                # which overstated what the chosen design buys.
                #
                # BLANK MEANS CLOSED, so appending it widens nothing on its own: every sketch already
                # in the database carries no value here, `design_ratings.pool_is_open` fails closed on
                # an absent key, and a sketch reaches the pool only when somebody in the workshop
                # deliberately dates it. That is what makes this the conservative direction as well as
                # the symmetric one — the field grants the ABILITY to open a sketch, not the fact.
                f(
                    "peerRoundClosedAt",
                    "Peer review closed on",
                    DATE,
                    A,
                    help="The day this sketch was declared finished and opened to designers outside "
                    "the workshop. Blank means peer review is still running.",
                ),
            ),
            label_field="name",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 12. Sketch Review / Shortlisting
# --------------------------------------------------------------------------------------

STAGE_12 = StageSpec(
    number=12,
    key="SKETCH_REVIEW",
    title="Sketch Review & Shortlisting",
    purpose=(
        "Which sketches go forward to prototyping, and on whose judgement — the artisans' and "
        "the master craftsperson's as much as the designer's."
    ),
    optional_stage=True,
    # WHY THIS STAGE STILL EXISTS. The source document proposed “we may consider deleting this
    # entire section for now”. It was kept and marked `optional_stage=True` instead, because the
    # selection reason recorded here is the only place in twenty-two stages that says why a design
    # was DROPPED — and a shortlisting funnel with no recorded rejections cannot be analysed.
    #
    # The note below does not repeat "this stage is optional", because `optional_stage` is a
    # structured boolean and BOTH clients render it on the screen this note appears on — checked,
    # not assumed, and it was not true until it was checked. `StageScreen` prints "Stage 12 of 22 ·
    # optional" in its header; the web's stage page printed nothing, because its "Optional stage"
    # pill lives on the stage LIST at `design-workshops/[id]`, a different page, and the form's own
    # previous/next controls walk from stage 11 straight to 12 without passing through it. Removing
    # the sentence from this note therefore took the fact off the web entirely until the stage page
    # was given its own pill. If a third client appears, it renders `optionalStage` or this note has
    # to carry the words again.
    notes=(
        "The reason a sketch is taken forward or set aside is recorded only here — no other "
        "stage captures why a design was dropped."
    ),
    entities=(
        many(
            "sketchReview",
            "DwSketchReview",
            "Sketch reviews",
            (
                f(
                    "sketchRef",
                    "Sketch",
                    REF,
                    B,
                    required=True,
                    ref_model="DwSketch",
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f(
                    "decision",
                    "Decision",
                    ENUM,
                    B,
                    required=True,
                    enum="REVIEW_DECISION",
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f("reason", "Reason", LT, S, report_role=COL, column_width_pct=30.0),
                f(
                    "artisanComments",
                    "Artisan / MCP comments",
                    LT,
                    S,
                    report_role=COL,
                    column_width_pct=24.0,
                ),
                f("reviewedBy", "Reviewed by", T, S, report_role=COL, column_width_pct=12.0),
                f("reviewDate", "Review date", DATE, S),
                f("technicalFeasibility", "Technical feasibility", ENUM, S, enum="QUALITY_RATING"),
                f("materialFeasibility", "Material feasibility", ENUM, S, enum="QUALITY_RATING"),
                f("estimatedCost", "Estimated cost", MONEY, S, unit="INR", min_value=0),
                f("estimatedTimeDays", "Estimated time", DEC, S, unit="days", min_value=0),
                f("skillRequired", "Skill required", LT, S),
                # RETIRED BECAUSE IT NEVER ORDERED ANYTHING, WHICH IS NOT THE SAME AS NEVER BEING
                # SEEN. This INT has been in the registry since it was written and nothing anywhere
                # SORTS by it: no client list, no validator, no query — a designer could type “3” into
                # it and no screen would move. Ranking is `DwStageEntry.ordinal`, the row order both
                # clients already drag and arrow, so a second hand-typed rank beside it is two answers
                # to one question with nothing to reconcile them. `replaced_by` names the sync
                # protocol's own wire key for that ordinal rather than a field of this entity, because
                # the successor genuinely is not a field.
                #
                # IT WAS, HOWEVER, PRINTED, and an earlier draft of this note wrongly said it was not.
                # `FieldSpec.report_role` defaults to `KEY_VALUE` and this declaration passes no role,
                # so a filled `rank` came out as a “Rank: 3” pair under its review's sub-heading in
                # every template — `ReportTemplate.max_tier` defaults to ADVANCED and only
                # COMPACT_SUMMARY drops to BASIC. Deprecating it therefore takes a printed line out of
                # regenerated reports, and takes it out SILENTLY: `report_builder._visible` excludes a
                # deprecated field, and `fields_hidden_by_tier` — the “these filled fields were left
                # out” warning — skips deprecated fields too, so nothing tells the reader.
                #
                # AND IT COSTS THE STORED VALUE. `entity_to_dict` omits a deprecated field from the
                # wire, so the box disappears from both forms, and `validate_entry` rebuilds `cleaned`
                # from the specs and skips it, so a value already stored under the key is dropped the
                # next time its row is saved.
                #
                # THE TEN ROWS ARE STILL NOT MIGRATED, and the corrected facts make the case stronger
                # rather than weaker. This repository's development database holds ten sketchReview
                # rows carrying ranks 1‑10 (checked 2026‑08‑22, one workshop, ordinals 0‑9) — and NOT
                # ONE OF THEM CARRIES A `sketchRef`: the JSON has no such key on any of the ten. A rank
                # that does not name the sketch it ranks cannot be turned into that sketch's position,
                # because there is no sketch on the other end of it. There is nothing here to
                # translate, only ten integers with no subject. The printed “Rank: N” line for those
                # ten rows is given up deliberately; the honest translation of “this sketch placed
                # third” is a row in third position, which is a judgement for whoever next opens that
                # workshop and not something to synthesise from an unlinked column.
                f(
                    "rank",
                    "Rank",
                    INT,
                    A,
                    min_value=1,
                    help="Where this sketch placed in a ranked review.",
                    deprecated=True,
                    replaced_by="_ordinal",
                ),
                f("voiceFeedback", "Voice feedback", AUDIO, A),
                f("voiceFeedbackTranscript", "Transcript", RICH, A, report_role=NARR),
                # WHOSE REVIEW THIS IS. A sketch is reviewed by the people who were in the room, and
                # again — once the sketch itself is finalised, which is `sketch.peerRoundClosedAt` and
                # nothing else — by the whole pool of designers, and both write a row into this entity.
                # (This note used to say "once prototypes are finalised", which was the owner's phrase
                # for when the second level generally begins and not a gate anything read. The gate is
                # per piece: the sketch's own date, exactly as a prototype's is its own.)
                # Without this token the two rounds are one
                # undifferentiated pile in the one place a stage entry is ever read back by a human:
                # a report cannot say whether a rejection was the cluster's own verdict or a
                # stranger's. That is the whole of the reason, and it is a REPORT reason — the field
                # is descriptive, and its reader is the report builder's generic KEY_VALUE path, the
                # same reader every other descriptive key on this entity has.
                #
                # IT IS NOT AN INPUT TO ANYTHING, AND THE LEDGER IS AUTHORITATIVE. The round a rating
                # actually counts under is `DwReviewRating.round`, set server-side from
                # `design_ratings.RatingRound` after `pool_is_open` has decided it; this key is a
                # dropdown a designer picks. Nothing copies one into the other, so the two CAN
                # disagree — a sketchReview row saying POOL beside ledger rows all saying PEER — and
                # when they do, the ledger is right and this key is a designer's description of their
                # own row. Nothing in `app/` reads it, and `test_the_review_round_key_is_descriptive`
                # fails the day something starts to: at that point it has to be DERIVED at save the
                # way every other mirrored field is, not read as a second source of truth.
                #
                # BASIC BUT NOT REQUIRED, and the distinction is what the tier rule is for. It belongs
                # to the minimum a review has to say about itself; making it required would put every
                # review row already in the database into a permanently incomplete stage, over a
                # question nobody was asked when they filled it in.
                f(
                    "reviewRound",
                    "Review round",
                    ENUM,
                    B,
                    enum="REVIEW_ROUND",
                    help="Peers from this workshop, or the whole pool of designers.",
                ),
            ),
            label_field="sketchRef",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 13. Detailed Design & Prototype Development
# --------------------------------------------------------------------------------------

STAGE_13 = StageSpec(
    number=13,
    key="PROTOTYPE_DEVELOPMENT",
    title="Prototype Development",
    purpose=(
        "The making of each prototype: who made it, from what, how long it took and what it "
        "cost, with the record of problems met along the way."
    ),
    # Source document: “let's simplify” against the Basic tier, which is why the required set
    # below is short. The Advanced measurement tier was referred to another team, with the remark
    # “the system we used in the workshop app… we can supply a PDF of measurements to be printed”
    # — kept verbatim on `prototype.measurementSheet`'s `phase_note`, which is a FILE slot for
    # exactly that PDF rather than a measurement feature this app claims to have.
    entities=(
        many(
            "prototype",
            "DwPrototype",
            "Prototypes",
            (
                f(
                    "prototypeCode",
                    "Prototype ID",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "name",
                    "Prototype name",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=24.0,
                ),
                f(
                    "sketchRef",
                    "From sketch",
                    REF,
                    B,
                    required=True,
                    ref_model="DwSketch",
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                # The maker is chosen from the ROSTER, not from the artisan table: the person who
                # made this prototype was in the room, and stage 3 is the list of who was. That is
                # also what makes the count of prototypes per participant answerable.
                f(
                    "artisanRef",
                    "Artisan assigned",
                    REF,
                    B,
                    required=True,
                    ref_model="DwParticipant",
                    ref_scope=ALL_SCOPE,
                    report_role=COL,
                    column_width_pct=20.0,
                    help="Chosen from the artisans enrolled at stage 3.",
                ),
                # Filtered BY THE ROSTER ENTRY above, which holds a participant id rather than an
                # artisan id. The resolver follows the participant back to the artisan record it was
                # built from, so the same cascade works here as at stage 6 without the form having
                # to know that the two pickers hold different kinds of id. A hand-typed participant
                # — one with no artisan record behind them — yields an empty product list, which is
                # the honest answer: there are no documented products to attribute to a person the
                # database has never seen.
                f(
                    "productRef",
                    "Existing product developed from",
                    REF,
                    S,
                    ref_model="ProductDocumentation",
                    ref_filter_by="artisanRef",
                    ref_scope=W_SCOPE,
                    report_role=HIDDEN,
                    help="If this prototype reworks a product the artisan already makes, choose it "
                    "here. The link is what lets the report show the before and the after.",
                ),
                fromref("productName", "Developed from", T, S),
                f(
                    "materials",
                    "Materials",
                    TAGS,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=28.0,
                ),
                *photos("prototypePhotos", "Prototype photographs", B, "Photograph caption"),
                f("lengthCm", "Length", DEC, S, unit="cm", min_value=0),
                f("widthCm", "Width", DEC, S, unit="cm", min_value=0),
                f("heightCm", "Height", DEC, S, unit="cm", min_value=0),
                f("diameterCm", "Diameter", DEC, S, unit="cm", min_value=0),
                f("weightG", "Weight", DEC, S, unit="g", min_value=0),
                f("dimensionsNote", "Dimensions (as described)", T, S),
                f("toolsUsed", "Tools used", TAGS, S),
                f("processSummary", "Process followed", RICH, S, report_role=NARR),
                f(
                    "makingTimeDays",
                    "Making time",
                    DEC,
                    S,
                    unit="days",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f("materialCost", "Material cost", MONEY, S, unit="INR", min_value=0),
                f("labourCost", "Labour cost", MONEY, S, unit="INR", min_value=0),
                f(
                    "problemsAndModifications",
                    "Problems & modifications",
                    RICH,
                    S,
                    report_role=NARR,
                ),
                f("startDate", "Started", DATE, S),
                f("completedDate", "Completed", DATE, S),
                f(
                    "measurementSheet",
                    "Measurement sheet",
                    FILE,
                    A,
                    phase_note="Reviewer: “The system we used in the workshop app… we can supply a "
                    "PDF of measurements to be printed” — Kumarjit da and team.",
                    help="A printable measured drawing, if one was produced.",
                ),
                f("processVideo", "Process video", VIDEO, A),
                f("audioNarration", "Audio narration", AUDIO, A),
                f("audioTranscript", "Narration transcript", RICH, A, report_role=NARR),
                f(
                    "turntablePhotos",
                    "360° capture",
                    IMGS,
                    A,
                    phase_note="Reviewer: “Kumar da team”.",
                ),
                f("modelFile", "3D model", FILE, A, phase_note="Reviewer: “Kumar da team”."),
                # The same override marker `sketch` carries, for the same reason and under the same
                # rule: blank means the prototypes are still in their computed order. See the long note
                # on `sketch.rankFixedBy` for why this is TEXT and not a REF to an account.
                f(
                    "rankFixedBy",
                    "Rank fixed by",
                    T,
                    A,
                    help="Who settled this order, when it was set deliberately rather than left in "
                    "score order.",
                ),
                f(
                    "rankFixedAt",
                    "Rank fixed on",
                    DATE,
                    A,
                    help="Leave blank while the order is still the default one.",
                ),
                # ── THE EVENT THAT CLOSES ONE ROUND AND OPENS THE OTHER ──────────────────────
                #
                # Peer review runs while the workshop does; the pool round is what the rest of the
                # platform's designers do to a prototype that has been declared finished. “Finished” is
                # a moment somebody chooses and not a state that can be derived — a prototype with
                # every field filled in may still be a week away from being shown to anyone — so it is
                # recorded here rather than computed from completeness.
                #
                # ON THE PROTOTYPE AND NOT ON THE WORKSHOP, because prototypes finish one at a time. A
                # workshop-level flag would open the pool round on nine unfinished prototypes the day
                # the tenth was done.
                #
                # `sketch` CARRIES THE SAME KEY, for the same reason and read by the same code — see
                # the long note at its declaration in stage 11 for why a sketch has a pool round at
                # all. The two are siblings and must stay in step: `design_ratings` names one field
                # (`POOL_OPENS_WHEN_FIELD`) and reads it off whichever row it was handed, so removing
                # it from either entity silently closes that entity's second round for ever.
                f(
                    "peerRoundClosedAt",
                    "Peer review closed on",
                    DATE,
                    A,
                    help="The day this prototype was declared finished and opened to designers outside "
                    "the workshop. Blank means peer review is still running.",
                ),
            ),
            label_field="name",
        ),
        many(
            "prototypeStageLog",
            "DwPrototypeStageLog",
            "Stage logs",
            (
                f(
                    "prototypeRef",
                    "Prototype",
                    REF,
                    B,
                    required=True,
                    ref_model="DwPrototype",
                    report_role=COL,
                    column_width_pct=18.0,
                ),
                f(
                    "logDate",
                    "Date",
                    DATE,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "stageName",
                    "Stage",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=22.0,
                ),
                f(
                    "notes",
                    "What was done",
                    LT,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=46.0,
                ),
                f("hoursSpent", "Hours", DEC, S, unit="hours", min_value=0),
                *photos("logPhotos", "Photographs", S, "Photograph caption"),
            ),
            label_field="stageName",
            parent="prototype",
        ),
        many(
            "materialUsage",
            "DwMaterialUsage",
            "Material usage",
            (
                f(
                    "prototypeRef",
                    "Prototype",
                    REF,
                    B,
                    required=True,
                    ref_model="DwPrototype",
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f(
                    "material",
                    "Material",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=26.0,
                ),
                f(
                    "quantity",
                    "Quantity",
                    DEC,
                    B,
                    required=True,
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "unit",
                    "Unit",
                    T,
                    B,
                    required=True,
                    max_length=24,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "rate",
                    "Rate",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "amount",
                    "Amount",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                    help="Leave blank to compute it as quantity × rate.",
                    derived_kind="PRODUCT",
                    derived_from=("quantity", "rate"),
                ),
            ),
            label_field="material",
            parent="prototype",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 14. Prototype Iteration / Testing
# --------------------------------------------------------------------------------------

STAGE_14 = StageSpec(
    number=14,
    key="PROTOTYPE_ITERATION",
    title="Prototype Iteration & Testing",
    purpose=(
        "Every change made to a prototype after its first making, why it was made, and what it "
        "cost in time and money."
    ),
    # Source document: the Standard tier here was marked “optional fields”, which is why only the
    # iteration's identity and its change are required below.
    entities=(
        many(
            "prototypeIteration",
            "DwPrototypeIteration",
            "Iterations",
            (
                f(
                    "prototypeRef",
                    "Prototype",
                    REF,
                    B,
                    required=True,
                    ref_model="DwPrototype",
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f(
                    "versionNo",
                    "Version",
                    INT,
                    B,
                    required=True,
                    min_value=1,
                    report_role=COL,
                    column_width_pct=10.0,
                ),
                f(
                    "changesMade",
                    "Changes made",
                    LT,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=30.0,
                ),
                f(
                    "designerComments",
                    "Designer’s comments",
                    LT,
                    B,
                    report_role=COL,
                    column_width_pct=22.0,
                ),
                f(
                    "artisanComments",
                    "Artisan’s comments",
                    LT,
                    B,
                    report_role=COL,
                    column_width_pct=22.0,
                ),
                f("reasonForChange", "Reason for the change", RICH, S, report_role=NARR),
                f(
                    "problemType",
                    "Problem type",
                    T,
                    S,
                    max_length=80,
                    help="What went wrong, in your own words. Tick the areas below as well.",
                ),
                # MULTI_ENUM AND NOT ENUM, decided by the answers already in the box rather than by
                # the source document's phrasing. It says "material/process/design problem", which
                # reads as one choice; two of the six values this database holds — "Design and
                # material — motif density and weft composition" and "Material and process — padding
                # construction" — name two areas each. A single select would have made a designer drop
                # half of every third answer, and a record that is half of what happened is worse than
                # the free text it was meant to improve on.
                #
                # Beside `problemType` rather than replacing it, for the reason at `tool.toolFamily`:
                # those six stored values are sentences, and a converted field would have shown the
                # designer an empty dropdown where their sentence was.
                f(
                    "problemAreas",
                    "Problem areas",
                    MENUM,
                    S,
                    enum="PROBLEM_TYPE",
                    help="Tick every area the problem touched. More than one is normal.",
                ),
                f("costEffect", "Effect on cost", MONEY, S, unit="INR"),
                f("timeEffectDays", "Effect on time", DEC, S, unit="days"),
                f("usability", "Usability", ENUM, S, enum="QUALITY_RATING"),
                f("quality", "Quality", ENUM, S, enum="QUALITY_RATING"),
                f("finishing", "Finishing", ENUM, S, enum="QUALITY_RATING"),
                f("iterationDate", "Date", DATE, S),
                f("beforePhoto", "Before", IMG, A, report_role=GALLERY),
                f(
                    "beforePhotoCaption",
                    "Before caption",
                    T,
                    A,
                    caption_for="beforePhoto",
                    report_role=CAP,
                ),
                f("afterPhoto", "After", IMG, A, report_role=GALLERY),
                f(
                    "afterPhotoCaption",
                    "After caption",
                    T,
                    A,
                    caption_for="afterPhoto",
                    report_role=CAP,
                ),
                f("failureNote", "Failure log", RICH, A, report_role=NARR),
            ),
            label_field="changesMade",
            parent="prototype",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 15. Prototype Selection / Validation
# --------------------------------------------------------------------------------------

STAGE_15 = StageSpec(
    number=15,
    key="PROTOTYPE_VALIDATION",
    title="Prototype Selection & Validation",
    purpose=("Which prototypes were accepted, on what assessment, and with whose approval."),
    # Source document: the Standard tier here was marked “optional fields”; of the Advanced tier,
    # “at the beginning — just crowdsourced designer feedback mechanism. Reset for Phase 4.” The
    # remark survives on `prototypeValidation.buyerFeedback`'s `phase_note`, which is the free-text
    # slot such a mechanism would eventually fill.
    entities=(
        many(
            "prototypeValidation",
            "DwPrototypeValidation",
            "Validation",
            (
                f(
                    "prototypeRef",
                    "Prototype",
                    REF,
                    B,
                    required=True,
                    ref_model="DwPrototype",
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f(
                    "decision",
                    "Decision",
                    ENUM,
                    B,
                    required=True,
                    enum="REVIEW_DECISION",
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "technicalQuality",
                    "Technical quality",
                    ENUM,
                    S,
                    enum="QUALITY_RATING",
                    report_role=COL,
                    column_width_pct=13.0,
                ),
                f(
                    "functionality",
                    "Functionality",
                    ENUM,
                    S,
                    enum="QUALITY_RATING",
                    report_role=COL,
                    column_width_pct=13.0,
                ),
                f(
                    "aesthetics",
                    "Aesthetics",
                    ENUM,
                    S,
                    enum="QUALITY_RATING",
                    report_role=COL,
                    column_width_pct=13.0,
                ),
                f(
                    "craftIntegrity",
                    "Craft integrity",
                    ENUM,
                    S,
                    enum="QUALITY_RATING",
                    report_role=COL,
                    column_width_pct=13.0,
                ),
                f(
                    "marketSuitability",
                    "Market suitability",
                    ENUM,
                    S,
                    enum="QUALITY_RATING",
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f("reason", "Reason", RICH, S, report_role=NARR),
                f("designerApproval", "Designer’s approval", BOOL, S),
                f("mcpApproval", "MCP approval", BOOL, S),
                f("approvedBy", "Approved by", T, S),
                f("approvalDate", "Approval date", DATE, S),
                f("finalLengthCm", "Final length", DEC, S, unit="cm", min_value=0),
                f("finalWidthCm", "Final width", DEC, S, unit="cm", min_value=0),
                f("finalHeightCm", "Final height", DEC, S, unit="cm", min_value=0),
                f(
                    "buyerFeedback",
                    "Buyer / retailer / exporter feedback",
                    RICH,
                    A,
                    report_role=NARR,
                    phase_note="Reviewer: “At the beginning — just crowdsourced designer feedback "
                    "mechanism. Reset for Phase 4”.",
                ),
                f("userTestingNotes", "User testing", RICH, A, report_role=NARR),
                f(
                    "qualityChecklist",
                    "Quality checklist",
                    RICH,
                    A,
                    report_role=BULLETS,
                    help="One check per line; prefix with ✓ or ✗.",
                ),
                # The sibling of `sketchReview.reviewRound`, for the same reason and with the same
                # standing: it says which audience filed THIS validation row, so a reader of the
                # report can tell the cluster's own verdict from a stranger's. Same tier, same reason
                # for not being required, same authority — `DwReviewRating.round` decides what a
                # rating counts as, and this key only describes the row it sits on. See that field.
                #
                # AN EARLIER DRAFT OF THIS NOTE CLAIMED THE FIVE QUALITY_RATING SCORES ABOVE ARE WHAT
                # A RANKING IS COMPUTED FROM. They are not, and nothing else is either: the ranking is
                # `design_ratings.rank`, which averages `DwReviewRating.score` and reads no registry
                # field at all. `technicalQuality`, `functionality`, `aesthetics`, `craftIntegrity`
                # and `marketSuitability` are report table columns with no aggregate reader anywhere
                # under `app/`. The mixed-audience averaging hazard is real but it lives on the
                # ledger's `score` column, which partitions on its own `round` — not here.
                f(
                    "reviewRound",
                    "Review round",
                    ENUM,
                    B,
                    enum="REVIEW_ROUND",
                    help="Peers from this workshop, or the whole pool of designers.",
                ),
            ),
            label_field="prototypeRef",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 16. Final Prototype Documentation
# --------------------------------------------------------------------------------------

STAGE_16 = StageSpec(
    number=16,
    key="FINAL_PROTOTYPE_DOCUMENTATION",
    title="Final Product Documentation",
    purpose=(
        "The catalogue record of each accepted product: its name and code, its final "
        "photographs, dimensions, materials, technique and description."
    ),
    # Source document: the Advanced catalogue-asset tier was referred to another team's plug-in.
    # The slots that plug-in would fill are declared (`catalogPhotos`, `turntablePhotos`, with the
    # remark on their `phase_note`); nothing here generates them.
    entities=(
        many(
            "finalProduct",
            "DwFinalProduct",
            "Final products",
            (
                f(
                    "productCode",
                    "Product code",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "name",
                    "Product name",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=24.0,
                ),
                f(
                    "prototypeRef",
                    "From prototype",
                    REF,
                    S,
                    ref_model="DwPrototype",
                    report_role=HIDDEN,
                ),
                *photos("finalPhotos", "Final photographs", B, "Photograph caption"),
                f("lengthCm", "Length", DEC, B, unit="cm", min_value=0),
                f("widthCm", "Width", DEC, B, unit="cm", min_value=0),
                f("heightCm", "Height", DEC, B, unit="cm", min_value=0),
                f("weightG", "Weight", DEC, S, unit="g", min_value=0),
                f("dimensionsNote", "Dimensions", T, B, report_role=COL, column_width_pct=18.0),
                f(
                    "materials",
                    "Materials",
                    TAGS,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=22.0,
                ),
                f("technique", "Technique", T, S, report_role=COL, column_width_pct=24.0),
                f("makingProcess", "Making process", RICH, S, report_role=NARR),
                f("makingTimeDays", "Making time", DEC, S, unit="days", min_value=0),
                f("costPrice", "Cost price", MONEY, S, unit="INR", min_value=0),
                f(
                    "sellingPrice",
                    "Selling price",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f("artisanRef", "Made by", REF, S, ref_model="DwParticipant"),
                f("designerName", "Designed by", T, S),
                f(
                    "description",
                    "Product description",
                    RICH,
                    S,
                    report_role=NARR,
                    help="The paragraph that would appear beside this piece in a catalogue.",
                ),
                f("category", "Category", ENUM, S, enum="PRODUCT_CATEGORY"),
                f(
                    "catalogPhotos",
                    "Catalogue photographs",
                    IMGS,
                    A,
                    phase_note="Reviewer: “Refer to Kumarjit plug in”.",
                ),
                f("lineDrawing", "Vector / line drawing", FILE, A),
                f("turntablePhotos", "360° asset", IMGS, A),
                f("technicalSpecification", "Technical specification", RICH, A, report_role=NARR),
            ),
            label_field="name",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 17. Costing, Packaging & Market Linkage
# --------------------------------------------------------------------------------------

STAGE_17 = StageSpec(
    number=17,
    key="COSTING_MARKET_LINKAGE",
    title="Costing, Packaging & Market Linkage",
    purpose=(
        "What each product costs to make and what it should sell for, built from the line "
        "items rather than asserted, plus how it will reach a buyer."
    ),
    # Source document: several Standard-tier items here were marked optional. Storing the cost
    # calculator's INPUTS rather than only its totals was not asked for and is the reason the
    # cost-head rows below exist at all — a ministry report that prints a selling price has to be
    # able to show the arithmetic when someone asks.
    notes=(
        "The cost sheet stores the quantities and rates you enter, not only the totals, so any "
        "figure printed in the report can be traced back to the line items it came from."
    ),
    entities=(
        many(
            "costSheet",
            "DwCostSheet",
            "Cost sheets",
            (
                f(
                    "productRef",
                    "Product",
                    REF,
                    B,
                    required=True,
                    ref_model="DwFinalProduct",
                    report_role=COL,
                    column_width_pct=22.0,
                ),
                # THE HELP USED TO SAY "Leave blank to total the material lines below." on a field
                # that is required and BASIC, and nothing anywhere totalled anything: the value
                # stayed null, the report's cost-sheet row printed ['PT-01 table runner', '', '',
                # '', '₹ 1,650.00', ''] with no material, labour or total cost, and pressing Submit
                # answered 422 "Material cost is required" for the exact field the form had told the
                # designer to leave empty. A designer following the stage's own instructions could
                # never complete it. The sum across CHILD ROWS is the one derivation this registry
                # cannot express — `derive_value` sees one row and its own fields, never a
                # collection — so the help now says what a designer can actually do, and the line
                # tables below compute their own Amount column to add up.
                f(
                    "materialCost",
                    "Material cost",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                    help="The material total for this product — the Amount column of its material "
                    "lines below, added up.",
                ),
                f(
                    "labourCost",
                    "Labour cost",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                    help="The labour total for this product — the Amount column of its labour lines "
                    "below, added up.",
                ),
                f("packagingCost", "Packaging", MONEY, S, unit="INR", min_value=0),
                f("finishingCost", "Finishing", MONEY, S, unit="INR", min_value=0),
                f("transportCost", "Transport", MONEY, S, unit="INR", min_value=0),
                f("overheadCost", "Overhead", MONEY, S, unit="INR", min_value=0),
                # "Derived from the costs above" is now true. Six heads, of which four are optional,
                # so SUM treats a blank as zero — and a row with none of them filled derives nothing
                # at all, because "₹ 0.00" in a cost sheet a ministry reads is a claim and not a
                # blank.
                f(
                    "totalCost",
                    "Total cost",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                    help="Derived from the costs above.",
                    derived_kind="SUM",
                    derived_from=(
                        "materialCost",
                        "labourCost",
                        "packagingCost",
                        "finishingCost",
                        "transportCost",
                        "overheadCost",
                    ),
                ),
                f("marginPercent", "Margin", PCT, S, min_value=0, max_value=500),
                f(
                    "expectedPrice",
                    "Expected price",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("wholesalePrice", "Wholesale price", MONEY, S, unit="INR", min_value=0),
                f(
                    "retailPrice",
                    "Retail price",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f("targetBuyer", "Target buyer", T, S),
                f("marketingStrategy", "Marketing strategy", RICH, S, report_role=NARR),
                f("packagingDescription", "Packaging", RICH, S, report_role=NARR),
                f("packagingPhoto", "Packaging photograph", IMG, A, report_role=GALLERY),
                f(
                    "packagingPhotoCaption",
                    "Packaging caption",
                    T,
                    A,
                    caption_for="packagingPhoto",
                    report_role=CAP,
                ),
            ),
            label_field="productRef",
        ),
        many(
            "costMaterialLine",
            "DwCostMaterialLine",
            "Material cost lines",
            (
                f(
                    "costSheetRef",
                    "Cost sheet",
                    REF,
                    B,
                    required=True,
                    ref_model="DwCostSheet",
                    report_role=HIDDEN,
                ),
                f("item", "Item", T, B, required=True, report_role=COL, column_width_pct=34.0),
                f(
                    "quantity",
                    "Quantity",
                    DEC,
                    B,
                    required=True,
                    min_value=0,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "unit",
                    "Unit",
                    T,
                    B,
                    required=True,
                    max_length=24,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "rate",
                    "Rate",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                # "Derived as quantity × rate" was a promise nothing kept: every Amount cell in the
                # generated report's material-lines table was empty, on 28 lines across four
                # products. PRODUCT is the kind `derive_value` and `derivedFields.ts` already
                # implement, so declaring it here is what makes the column appear as the designer
                # types on all three clients.
                f(
                    "amount",
                    "Amount",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=20.0,
                    help="Derived as quantity × rate.",
                    derived_kind="PRODUCT",
                    derived_from=("quantity", "rate"),
                ),
            ),
            label_field="item",
            parent="costSheet",
        ),
        many(
            "costLabourLine",
            "DwCostLabourLine",
            "Labour cost lines",
            (
                f(
                    "costSheetRef",
                    "Cost sheet",
                    REF,
                    B,
                    required=True,
                    ref_model="DwCostSheet",
                    report_role=HIDDEN,
                ),
                f("task", "Task", T, B, required=True, report_role=COL, column_width_pct=32.0),
                f(
                    "persons",
                    "Persons",
                    INT,
                    B,
                    required=True,
                    min_value=0,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "days",
                    "Days",
                    DEC,
                    B,
                    required=True,
                    min_value=0,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "rate",
                    "Rate per day",
                    MONEY,
                    B,
                    required=True,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=22.0,
                ),
                # Three factors, and `derive_value`'s PRODUCT multiplies every key in derived_from,
                # so the same kind covers it.
                f(
                    "amount",
                    "Amount",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=22.0,
                    help="Derived as persons × days × rate.",
                    derived_kind="PRODUCT",
                    derived_from=("persons", "days", "rate"),
                ),
            ),
            label_field="task",
            parent="costSheet",
        ),
        many(
            "buyerLink",
            "DwBuyerLink",
            "Buyer linkages",
            (
                f(
                    "buyerName",
                    "Buyer",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=26.0,
                ),
                f(
                    "buyerType",
                    "Type",
                    ENUM,
                    S,
                    enum="MARKET_CHANNEL",
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f("contact", "Contact", T, S, report_role=COL, column_width_pct=20.0),
                f("interest", "Interest expressed", LT, S, report_role=COL, column_width_pct=34.0),
                f("followUpDate", "Follow-up due", DATE, S),
            ),
            label_field="buyerName",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 18. Workshop Outcomes / Problems / Feedback
# --------------------------------------------------------------------------------------

STAGE_18 = StageSpec(
    number=18,
    key="WORKSHOP_OUTCOMES",
    title="Workshop Outcomes, Problems & Feedback",
    purpose=(
        "What the workshop achieved, what went wrong, and what the artisans and the designer "
        "say about it."
    ),
    # The Advanced tier below is “just option for voice and transcript — if you think it apt”
    # from the source document, kept verbatim on `outcomes.feedbackAudio`'s `phase_note`.
    #
    # The note is about the OVERRIDE fields, which is behaviour a designer meets head-on: the
    # headline counts arrive already filled in, and typing over one silently changes a number the
    # report prints. `countOverrideReason` is what makes that traceable, and its own help text
    # says it is required — the note is the stage-level warning that the counts are derived at all.
    notes=(
        "The design and prototype counts are worked out from the records you have entered. The "
        "override fields exist only to replace a derived number, and an override must carry a "
        "reason, which is printed in the report beneath the figure it changed."
    ),
    entities=(
        single(
            "outcomes",
            "DwOutcomes",
            "Outcomes",
            (
                f("achievements", "Achievements", RICH, B, required=True, report_role=NARR),
                f("problems", "Problems faced", RICH, B, required=True, report_role=NARR),
                f("artisanFeedback", "Artisan feedback", RICH, B, required=True, report_role=NARR),
                f(
                    "designerComments",
                    "Designer’s comments",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                ),
                # HIDDEN, and that is what makes them overrides rather than a second opinion.
                #
                # They were KEY_VALUE (the field default), so the report printed them raw AND derived
                # its own counts from the rows — and then said both. The front-page metric row read
                # "Sketches 10" while stage 18, forty pages later, read "Number of designs (override)
                # 24" with the reason underneath it, in the same submitted document. An officer
                # reading it cannot tell which figure to quote, and the reason never appeared next to
                # the number it explains. The override now WINS wherever the count is printed — the
                # metric row and the output-counts chart, see `report_builder._output_count` — and
                # `countOverrideReason` travels with it as the metric's caption, so the explanation
                # is beside the number rather than forty pages away from it.
                f(
                    "designsCountOverride",
                    "Number of designs (override)",
                    INT,
                    S,
                    min_value=0,
                    report_role=HIDDEN,
                    help="Leave blank to count the sketches recorded.",
                ),
                f(
                    "prototypesCountOverride",
                    "Number of prototypes (override)",
                    INT,
                    S,
                    min_value=0,
                    report_role=HIDDEN,
                    help="Leave blank to count the prototypes recorded.",
                ),
                f(
                    "countOverrideReason",
                    "Reason for the override",
                    T,
                    S,
                    report_role=HIDDEN,
                    help="Required if either count above is filled in. It is printed under the "
                    "headline figure, so it is what an officer reads beside the number.",
                ),
                f("skillsTransferred", "Skills transferred", RICH, S, report_role=BULLETS),
                f("materialsTested", "Materials tested", TAGS, S),
                f("techniquesTested", "Techniques tested", TAGS, S),
                f("recommendations", "Recommendations", RICH, S, report_role=BULLETS),
                f(
                    "performanceVsTarget",
                    "Performance against planned targets",
                    RICH,
                    A,
                    report_role=NARR,
                ),
                f(
                    "feedbackAudio",
                    "Voice feedback",
                    AUDIO,
                    A,
                    phase_note="Reviewer: “Just option for voice and transcript — if you think it apt”.",
                ),
                f("feedbackTranscript", "Feedback transcript", RICH, A, report_role=NARR),
                f("feedbackVideo", "Video feedback", VIDEO, A),
            ),
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 19. Inspection / Closing Ceremony
# --------------------------------------------------------------------------------------

STAGE_19 = StageSpec(
    number=19,
    key="INSPECTION_CLOSING",
    title="Inspection & Closing",
    purpose="The closing session, the certificates issued and the inspecting officer's remarks.",
    entities=(
        single(
            "closing",
            "DwClosing",
            "Closing",
            (
                f(
                    "finalInspectionNote",
                    "Final inspection",
                    RICH,
                    B,
                    required=True,
                    report_role=NARR,
                ),
                f("closingNote", "Closing note", RICH, S, report_role=NARR),
                f("closingDate", "Closing date", DATE, S),
                f("inspectingOfficer", "Inspecting officer", T, S),
                f("inspectingOfficerDesignation", "Designation", T, S),
                f("officerComments", "Officer’s comments", RICH, S, report_role=NARR),
                f(
                    "participantsCompleted",
                    "Participants who completed",
                    INT,
                    S,
                    min_value=0,
                    report_role=METRIC,
                ),
                *photos("closingPhotos", "Closing photographs", B, "Closing photograph caption"),
                f("summaryVideo", "Video summary", VIDEO, A),
            ),
        ),
        many(
            "official",
            "DwOfficial",
            "Officials present",
            (
                f("name", "Name", T, B, required=True, report_role=COL, column_width_pct=30.0),
                f(
                    "designation",
                    "Designation",
                    T,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=34.0,
                ),
                f("organisation", "Organisation", T, S, report_role=COL, column_width_pct=36.0),
                f("comments", "Comments", LT, S),
            ),
            label_field="name",
        ),
        many(
            "certificate",
            "DwCertificate",
            "Certificates & attendance",
            (
                f(
                    "participantRef",
                    "Artisan",
                    REF,
                    B,
                    required=True,
                    ref_model="DwParticipant",
                    report_role=COL,
                    column_width_pct=30.0,
                ),
                f(
                    "certificateNo",
                    "Certificate number",
                    T,
                    S,
                    report_role=COL,
                    column_width_pct=22.0,
                ),
                f(
                    "issued",
                    "Issued",
                    BOOL,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f("issueDate", "Issue date", DATE, S, report_role=COL, column_width_pct=18.0),
                f(
                    "daysAttended",
                    "Days attended",
                    INT,
                    S,
                    min_value=0,
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                # THE SIGNED SHEET, CAPTURED RATHER THAN PHOTOGRAPHED. Attendance was delivered for
                # most of this project's life as a photograph of a paper register, which is a picture
                # of data and not data: it cannot be counted, cannot be reconciled against the roster
                # three fields up, and cannot be printed as a table. The fields above are now the
                # record; this is only the evidence beside it.
                #
                # STANDARD, AND NEVER REQUIRED, WHICH IS THE WHOLE POINT. A signature pad is unusable
                # to a keyboard-only designer and to anyone whose digitiser has given up in the heat,
                # so the attendance datum must be answerable without it — `participantRef`, `issued`
                # and `daysAttended` are all keyboard-answerable and stay BASIC. Standard rather than
                # Advanced only so the pad is visible without expanding a disclosure: the web form
                # collapses ADVANCED behind one, and evidence nobody finds is evidence nobody captures.
                #
                # NO report_role, exactly like `certificateFile` beside it: a TABLE_COLUMN is text in
                # all five renderers, and asking the .docx writer, both PDF writers, the preview and
                # the editor each to invent a way to draw a picture in a table cell would get five
                # different answers. It would also break the 100% width budget the five columns above
                # already spend in full.
                f(
                    "signatureImage",
                    "Signature",
                    IMG,
                    S,
                    help="Sign on the pad, or attach a photograph of the signed sheet. Attendance is "
                    "recorded by the fields above and is never held up by this.",
                ),
                f("certificateFile", "Certificate", FILE, A),
            ),
            label_field="participantRef",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 20. Report Generation & Submission
# --------------------------------------------------------------------------------------

STAGE_20 = StageSpec(
    number=20,
    key="REPORT_GENERATION",
    title="Report Generation & Submission",
    purpose=(
        "Choose the template, decide what the report contains, generate it, and keep a record "
        "of every file that was produced."
    ),
    notes=(
        "This stage configures the report rather than appearing in it, so no template prints "
        "it. Every export ever generated is recorded below, with its checksum, so a file "
        "submitted to an office can later be matched against the record it came from."
    ),
    entities=(
        single(
            "reportSettings",
            "DwReportSettings",
            "Report settings",
            (
                f("templateId", "Report template", ENUM, B, required=True, enum="REPORT_TEMPLATE"),
                f("pageSize", "Page size", ENUM, B, enum="PAGE_SIZE"),
                # ONE COLOUR, NOT EIGHT. A ReportTheme carries eight colours and every renderer reads
                # all of them; ``report_theme.theme_from_accent`` derives seven of the eight from this
                # single accent so that no reachable answer produces an unreadable document — white
                # header text on pale yellow being the one that ships from every form that offers a
                # colour well per colour. The preset is the name a designer can repeat to the officer
                # who asked for it ("the maroon one"); the hex is what actually renders.
                f(
                    "themePreset",
                    "Report colour",
                    ENUM,
                    S,
                    enum="REPORT_ACCENT_PRESET",
                    help="Twelve colours chosen to stay distinguishable when the report is printed on "
                    "a black-and-white office printer. Choose “Custom colour” to use the picker.",
                ),
                f(
                    "themeAccent",
                    "Report colour (hex)",
                    T,
                    S,
                    max_length=7,
                    help="The accent as #RRGGBB. Filled in for you when a named colour is chosen; the "
                    "headings, the table headers, the rules and the figures are all derived from "
                    "it. Left blank, the report keeps the template’s own colour.",
                ),
                f(
                    "fontPreset",
                    "Report typeface",
                    ENUM,
                    S,
                    enum="REPORT_FONT",
                    help="Applies to the Word document, which is the file that is submitted and the one "
                    "an officer edits. The PDF embeds whichever face the server has that can draw "
                    "Odia, Devanagari and the rupee sign, so a typeface chosen here is reported as "
                    "a warning rather than silently substituted there.",
                ),
                f(
                    "reportTitle",
                    "Report title",
                    T,
                    S,
                    max_length=220,
                    help="Leave blank to use the workshop title.",
                ),
                f("reportSubtitle", "Report subtitle", T, S, max_length=220),
                f(
                    "organisationLine",
                    "Organisation line",
                    T,
                    S,
                    max_length=220,
                    help="Printed above the title on the cover.",
                ),
                f("letterheadText", "Letterhead text", LT, S),
                f("logo", "Logo", IMG, S),
                f("includeTableOfContents", "Include a table of contents", BOOL, S),
                f("includePhotographs", "Include photographs", BOOL, S),
                f("photoColumns", "Photographs per row", INT, S, min_value=1, max_value=4),
                f("includeMediaAnnexure", "Include the photographic annexure", BOOL, S),
                # ONE TOGGLE, EVERY RECORDING. A workshop collects spoken explanations, voice notes
                # against sketches and voice feedback on prototypes, all transcribed automatically by
                # the media queue; before this switch existed the only way an officer could read what
                # an artisan actually said was to be sent the audio. Off by default, because a
                # transcript annexure can double a report's length and that has to be a decision
                # somebody made rather than one that happened to them.
                f(
                    "includeTranscripts",
                    "Append the recordings’ transcripts",
                    BOOL,
                    S,
                    help="Adds an annexure containing every transcript the workshop collected.",
                ),
                f("includeCompletenessAnnexure", "Include the completeness annexure", BOOL, S),
                f("numberHeadings", "Number the headings", BOOL, S),
                f(
                    "excludedStages",
                    "Stages to leave out",
                    TAGS,
                    S,
                    help="Stage keys omitted from this report.",
                ),
                f("headerText", "Running header", T, S, max_length=180),
                f("footerText", "Running footer", T, S, max_length=180),
                f("submittedTo", "Submitted to", T, S, max_length=220),
                f("submissionDate", "Submission date", DATE, S),
            ),
        ),
        many(
            "reportExport",
            "DwReportExport",
            "Generated reports",
            (
                f(
                    "format",
                    "Format",
                    ENUM,
                    B,
                    required=True,
                    enum="EXPORT_FORMAT",
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "templateId",
                    "Template",
                    ENUM,
                    B,
                    required=True,
                    enum="REPORT_TEMPLATE",
                    report_role=COL,
                    column_width_pct=24.0,
                ),
                f("fileName", "File", T, B, required=True, report_role=COL, column_width_pct=28.0),
                f(
                    "generatedAt",
                    "Generated",
                    DATE,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("generatedBy", "Generated by", T, S, report_role=COL, column_width_pct=20.0),
                f("fileSizeBytes", "Size", INT, S, unit="bytes", min_value=0),
                f("pageCount", "Pages", INT, S, min_value=0),
                f(
                    "checksumSha256",
                    "SHA-256",
                    T,
                    S,
                    max_length=64,
                    help="Lets a submitted file be matched against this record.",
                ),
                f(
                    "generatedOnDevice",
                    "Generated on device",
                    BOOL,
                    S,
                    help="True when the phone produced the file with no network.",
                ),
                f("warnings", "Warnings at generation", LT, S),
            ),
            label_field="fileName",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 21. Data Quality, Archive & Database Submission
# --------------------------------------------------------------------------------------

STAGE_21 = StageSpec(
    number=21,
    key="DATA_QUALITY_ARCHIVE",
    title="Data Quality & Archive",
    purpose=(
        "Confirm that the record and its media are preserved, and record any quality problem "
        "found in the photographs before the record is archived."
    ),
    # WHAT THE NOTE BELOW MUST STAY TRUE TO, AND WHERE TO CHECK IT.
    #
    # These flags are only half a feature in this file: the ENUM declares seven of them, and the
    # handset computes four. `DwImageQuality.findQualityIssues` emits BLUR (variance of the
    # Laplacian, against `BLUR_VARIANCE_FLOOR`), LOW_RESOLUTION (long edge against
    # `MIN_LONG_EDGE_PX`), DUPLICATE (exact SHA-256 first, then a perceptual hash within
    # `NEAR_DUPLICATE_MAX_DISTANCE`) and MISSING_VIEW (stage 6's named view slots). OVEREXPOSED,
    # UNDEREXPOSED and WRONG_SUBJECT are judgements no measurement here makes.
    #
    # The note used to say duplicate detection was "not claimed", which was true when it was
    # written and stopped being true when the perceptual hash landed. If the flag set in
    # `QualityFlag` changes again, this note is the user-facing thing that goes stale with it.
    #
    # `autoDetected` is a plain BOOL the designer ticks — NOTHING writes it. The app raises its
    # findings on the photo screen at capture time; these rows are entered by hand afterwards, and
    # the note says so rather than implying the stage fills itself in.
    notes=(
        "As photographs are taken, this device checks them for blur, low resolution, duplicates "
        "and missing views. Overexposure, underexposure and wrong subject are not checked — judge "
        "those by eye. Rows here are entered by hand either way; tick “Detected automatically” "
        "for the ones the app raised."
    ),
    entities=(
        single(
            "archive",
            "DwArchive",
            "Archive",
            (
                f("reportSaved", "Final report saved", BOOL, B, required=True),
                f("mediaSaved", "Media saved", BOOL, B, required=True),
                f("submittedToDatabase", "Submitted to the database", BOOL, S),
                f("submissionDate", "Submission date", DATE, S),
                f("archiveLocation", "Archive location", T, S, max_length=220),
                f("retentionClass", "Retention class", ENUM, S, enum="RETENTION_CLASS"),
                f("rawFilesPreserved", "Raw files preserved", BOOL, S),
                f("checksumManifest", "Checksum manifest", FILE, A),
                f("notes", "Notes", RICH, S, report_role=NARR),
            ),
        ),
        many(
            "mediaQualityFlag",
            "DwMediaQualityFlag",
            "Media quality flags",
            (
                f("mediaId", "File", T, B, required=True, report_role=COL, column_width_pct=26.0),
                f(
                    "flag",
                    "Problem",
                    ENUM,
                    B,
                    required=True,
                    enum="MEDIA_QUALITY_FLAG",
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f(
                    "severity",
                    "Severity",
                    ENUM,
                    S,
                    enum="SEVERITY",
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "autoDetected",
                    "Detected automatically",
                    BOOL,
                    S,
                    report_role=COL,
                    column_width_pct=16.0,
                ),
                f("resolved", "Resolved", BOOL, S, report_role=COL, column_width_pct=12.0),
                f("note", "Note", LT, S, report_role=COL, column_width_pct=12.0),
            ),
            label_field="mediaId",
        ),
    ),
)


# --------------------------------------------------------------------------------------
# 22. Post-Workshop Follow-up
# --------------------------------------------------------------------------------------

STAGE_22 = StageSpec(
    number=22,
    key="POST_WORKSHOP_FOLLOWUP",
    title="Post-Workshop Follow-up",
    purpose=(
        "Whether the designs were actually taken up: what is being produced, what has sold, "
        "and what the artisans still need."
    ),
    notes=(
        "One record per product per monitoring interval, which is what makes design-survival "
        "analysis possible across workshops. Nothing here is required of the workshop itself — "
        "follow-up happens months later, and a workshop must be able to close without it."
    ),
    entities=(
        single(
            "followUpSummary",
            "DwFollowUpSummary",
            "Follow-up summary",
            (
                f(
                    "continuedProductionNote",
                    "Note on continued production",
                    RICH,
                    B,
                    report_role=NARR,
                ),
                f("additionalNeeds", "Additional support needed", RICH, S, report_role=NARR),
            ),
        ),
        many(
            "followUp",
            "DwFollowUp",
            "Follow-up records",
            (
                f(
                    "productRef",
                    "Product",
                    REF,
                    B,
                    required=True,
                    ref_model="DwFinalProduct",
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f(
                    "interval",
                    "Interval",
                    ENUM,
                    B,
                    required=True,
                    enum="FOLLOWUP_INTERVAL",
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "followUpDate",
                    "Date",
                    DATE,
                    B,
                    required=True,
                    report_role=COL,
                    column_width_pct=14.0,
                ),
                f(
                    "adoptionStatus",
                    "Adoption",
                    ENUM,
                    B,
                    required=True,
                    enum="ADOPTION_STATUS",
                    report_role=COL,
                    column_width_pct=20.0,
                ),
                f(
                    "unitsProduced",
                    "Units produced",
                    INT,
                    S,
                    min_value=0,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f(
                    "unitsSold",
                    "Units sold",
                    INT,
                    S,
                    min_value=0,
                    report_role=COL,
                    column_width_pct=10.0,
                ),
                f(
                    "revenue",
                    "Revenue",
                    MONEY,
                    S,
                    unit="INR",
                    min_value=0,
                    report_role=COL,
                    column_width_pct=12.0,
                ),
                f("ordersReceived", "Orders received", INT, S, min_value=0),
                f("modifications", "Modifications made", RICH, S, report_role=NARR),
                f("supportNeeded", "Support needed", RICH, S, report_role=NARR),
                f("recordedBy", "Recorded by", T, S),
                *photos("evidencePhotos", "Evidence photographs", S, "Photograph caption"),
            ),
            label_field="productRef",
        ),
    ),
)


ALL_STAGES: tuple[StageSpec, ...] = (
    STAGE_1,
    STAGE_2,
    STAGE_3,
    STAGE_4,
    STAGE_5,
    STAGE_6,
    STAGE_7,
    STAGE_8,
    STAGE_9,
    STAGE_10,
    STAGE_11,
    STAGE_12,
    STAGE_13,
    STAGE_14,
    STAGE_15,
    STAGE_16,
    STAGE_17,
    STAGE_18,
    STAGE_19,
    STAGE_20,
    STAGE_21,
    STAGE_22,
)

_install(ALL_STAGES)
