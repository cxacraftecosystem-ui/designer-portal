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

Adding a field: append it, never renumber. Removing one: set ``deprecated=True`` and name its
successor in ``replaced_by``. A key is what two weeks of a designer's fieldwork is stored
under — see the rules in :mod:`app.services.stage_schema`.
"""

from __future__ import annotations

from app.services.stage_schema import (
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    StageSpec,
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
NARR, KV, COL, CAP = (ReportRole.NARRATIVE, ReportRole.KEY_VALUE,
                      ReportRole.TABLE_COLUMN, ReportRole.CAPTION)
COVER, METRIC, GALLERY, BULLETS = (ReportRole.COVER_FIELD, ReportRole.METRIC,
                                   ReportRole.GALLERY, ReportRole.BULLETS)
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
FROM_REF = ("Filled in from the linked record when one is chosen. It is stored on this entry "
            "as well as the link, so the report still prints it if that record is later "
            "changed or removed.")


def _with_ref_note(help_text: str) -> str:
    """``help`` for a field the picker populates, keeping whatever guidance it already had."""
    return f"{help_text} {FROM_REF}" if help_text else FROM_REF


def f(key: str, label: str, type: FieldType = T, tier: Tier = S, **kw) -> FieldSpec:
    return FieldSpec(key=key, label=label, type=type, tier=tier, **kw)


def fromref(key: str, label: str, type: FieldType = T, tier: Tier = S, **kw) -> FieldSpec:
    """A field the reference picker hydrates. Identical to :func:`f` but for the help text."""
    kw["help"] = _with_ref_note(str(kw.get("help", "")))
    return FieldSpec(key=key, label=label, type=type, tier=tier, **kw)


def single(key: str, name: str, title: str, fields: tuple[FieldSpec, ...],
           description: str = "") -> EntitySpec:
    return EntitySpec(key=key, name=name, cardinality=Cardinality.SINGLETON, title=title,
                      fields=fields, description=description)


def many(key: str, name: str, title: str, fields: tuple[FieldSpec, ...],
         label_field: str = "", parent: str = "", description: str = "") -> EntitySpec:
    return EntitySpec(key=key, name=name, cardinality=Cardinality.COLLECTION, title=title,
                      fields=fields, label_field=label_field, parent=parent,
                      description=description)


# Photograph + caption is the single most repeated shape in the whole registry: the source
# document asks for photographs at fifteen of the twenty-two stages and the report needs a
# caption under every one of them.
def photos(key: str = "photos", label: str = "Photographs", tier: Tier = B,
           caption_label: str = "Photograph caption") -> tuple[FieldSpec, FieldSpec]:
    return (
        f(key, label, IMGS, tier, report_role=GALLERY),
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
        single("workshopSetup", "DwWorkshopSetup", "Workshop details", (
            f("workshopTitle", "Workshop title", T, B, required=True, report_role=COVER,
              max_length=220,
              help="As written on the sanction order, e.g. “Design & Prototype Development "
                   "Workshop — Sambalpuri Ikat”."),
            f("schemeName", "Scheme", T, B, required=True, report_role=COVER, max_length=180,
              help="The scheme funding the workshop, e.g. National Handicrafts Development "
                   "Programme."),
            fromref("craftName", "Craft", T, B, required=True, report_role=COVER,
                    max_length=160),
            fromref("craftLocalName", "Craft name in the local language", T, S,
                    report_role=COVER,
                    help="Written in the local script. It is printed in the report exactly as "
                         "typed."),
            f("craftRef", "Linked craft record", REF, S, ref_model="Craft",
              ref_scope=ALL_SCOPE, report_role=HIDDEN,
              help="Choose the craft from the crafts already documented. The cover fields above "
                   "are filled in from it."),
            f("clusterName", "Cluster", T, B, required=True, report_role=COVER, max_length=160),
            f("state", "State", T, B, required=True, report_role=COVER, max_length=80),
            f("district", "District", T, B, required=True, report_role=COVER, max_length=80),
            f("block", "Block / Tehsil", T, S, report_role=COVER, max_length=80),
            f("village", "Village / Town", T, S, report_role=COVER, max_length=120),
            f("venue", "Venue", T, B, required=True, report_role=COVER, max_length=220),
            f("startDate", "Start date", DATE, B, required=True, report_role=COVER),
            f("endDate", "End date", DATE, B, required=True, report_role=COVER),
            f("durationDays", "Duration", INT, S, unit="days", min_value=1, max_value=365,
              report_role=COVER,
              help="Leave blank to derive it from the start and end dates.",
              derived_kind="DAYS_BETWEEN", derived_from=("startDate", "endDate")),
            f("designerName", "Designer", T, B, required=True, report_role=COVER, max_length=180),
            f("designerInstitution", "Designer’s institution", T, S, report_role=COVER,
              max_length=180),
            f("implementingAgency", "Implementing agency", T, B, required=True,
              report_role=COVER, max_length=220),
            f("sponsor", "Sponsoring body", T, B, required=True, report_role=COVER,
              max_length=220),
            f("sanctionOrderNo", "Sanction order number", T, S, report_role=COVER,
              max_length=120),
            f("sanctionOrderDate", "Sanction order date", DATE, S, report_role=COVER),
            f("workshopCode", "Workshop ID", T, S, report_role=COVER, max_length=60,
              help="The implementing agency’s own reference for this workshop."),
            f("sanctionDocument", "Sanction order document", FILE, A,
              phase_note="Reviewer: “Phase 2 work”."),
            f("venueLocation", "Venue location", GEO, A,
              phase_note="Reviewer: “Phase 2 work”.",
              help="Captured from the device once, at the venue."),
            f("coverPhoto", "Cover photograph", IMG, S, report_role=GALLERY,
              help="Printed large on the cover page of the report."),
            f("coverPhotoCaption", "Cover photograph caption", T, S, caption_for="coverPhoto",
              report_role=CAP),
        )),
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
    notes="Reviewer marked the Advanced tier of this stage as “Phase 2 work”.",
    entities=(
        single("introduction", "DwIntroduction", "Introduction", (
            f("acknowledgement", "Acknowledgement", RICH, B, required=True, report_role=NARR,
              help="Whom the workshop thanks: the sanctioning office, the cluster, the artisans."),
            f("briefIntroduction", "Brief introduction", RICH, B, required=True, report_role=NARR),
            f("purpose", "Purpose of the workshop", RICH, B, required=True, report_role=NARR),
            f("projectBackground", "Project background", RICH, S, report_role=NARR),
            f("agencyDetails", "DCH / implementing-agency details", RICH, S, report_role=NARR),
            f("programmeObjectives", "Programme objectives", RICH, S, report_role=BULLETS,
              help="One objective per line."),
            f("expectedDeliverables", "Expected deliverables", RICH, S, report_role=BULLETS,
              help="One deliverable per line."),
            f("institutionalText", "Reusable institutional text", RICH, A, report_role=NARR,
              phase_note="Reviewer: “Phase 2 work”. Standing text an agency repeats across "
                         "workshops; kept here so it can be carried forward."),
        )),
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
    notes="The reviewer marked the Standard tier of this stage “Important for us”.",
    entities=(
        single("workshopPlan", "DwWorkshopPlan", "Plan & opening", (
            f("designerProfile", "Designer’s profile", RICH, B, required=True, report_role=NARR),
            f("designerExperience", "Designer’s experience", INT, S, unit="years",
              min_value=0, max_value=70),
            f("openingNote", "Opening note", RICH, S, report_role=NARR),
            f("officialsPresent", "Officials present at the opening", RICH, S,
              report_role=BULLETS, help="One official per line, with designation."),
            f("totalArtisans", "Artisans enrolled", INT, S, min_value=0, report_role=METRIC,
              help="Leave blank to count the participant list below."),
            f("womenParticipants", "Women participants", INT, S, min_value=0,
              report_role=METRIC),
            *photos("openingPhotos", "Opening photographs", B, "Opening photograph caption"),
            *photos("eventPhotos", "Event photographs", S, "Event photograph caption"),
        )),
        many("workshopScheduleDay", "DwWorkshopScheduleDay", "Workshop schedule", (
            f("dayNumber", "Day", INT, B, required=True, min_value=1, report_role=COL,
              column_width_pct=8.0),
            f("date", "Date", DATE, B, report_role=COL, column_width_pct=14.0),
            f("session", "Session", T, S, report_role=COL, column_width_pct=18.0,
              help="Forenoon, afternoon, full day."),
            f("activity", "Activity", LT, B, required=True, report_role=COL,
              column_width_pct=45.0),
            f("conductedBy", "Conducted by", T, S, report_role=COL, column_width_pct=15.0),
        ), label_field="activity"),
        many("participant", "DwParticipant", "Participating artisans", (
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
            f("artisanRef", "Artisan record", REF, B, ref_model="Artisan",
              ref_scope=ALL_SCOPE, report_role=HIDDEN,
              help="Search the documented artisans and choose one. The details below are "
                   "filled in from that record; anything the record does not have can still be "
                   "typed in.",
              phase_note="Reviewer: “Phase 3 — may help for networking artisans”."),
            f("serialNo", "S. No.", INT, B, min_value=1, report_role=COL, column_width_pct=7.0),
            fromref("name", "Artisan name", T, B, required=True, report_role=COL,
                    column_width_pct=24.0),
            fromref("localName", "Name in the local language", T, S),
            fromref("gender", "Gender", T, S, max_length=20),
            f("age", "Age", INT, S, unit="years", min_value=10, max_value=110),
            f("artisanCardNo", "Artisan ID / card number", T, S, report_role=COL,
              column_width_pct=16.0),
            fromref("specialisation", "Specialisation", T, S, report_role=COL,
                    column_width_pct=23.0),
            fromref("experienceYears", "Experience", INT, S, unit="years", min_value=0,
                    max_value=90, report_role=COL, column_width_pct=12.0),
            f("isMasterCraftsperson", "Master craftsperson", BOOL, S, report_role=COL,
              column_width_pct=18.0, help="MCP status as recognised by the implementing agency."),
            fromref("village", "Village", T, S),
            fromref("phone", "Phone", FieldType.PHONE, S),
            f("attendedDays", "Days attended", INT, S, min_value=0),
            fromref("photo", "Photograph", IMG, S, report_role=GALLERY),
            f("photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP),
        ), label_field="name"),
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
    notes=(
        "The reviewer noted that traditional and contemporary DESIGNS belong to the next "
        "section, not here, and that several Standard-tier items should be optional. Both are "
        "reflected: this stage carries motifs, forms and colours as craft vocabulary, while "
        "design direction sits in stage 10."
    ),
    entities=(
        single("clusterBackground", "DwClusterBackground", "Cluster & craft background", (
            f("clusterIntroduction", "Cluster introduction", RICH, B, required=True,
              report_role=NARR),
            f("craftIntroduction", "Craft introduction", RICH, B, required=True,
              report_role=NARR),
            f("history", "History of the craft", RICH, B, required=True, report_role=NARR),
            f("traditionalProducts", "Traditional products", RICH, B, required=True,
              report_role=BULLETS, help="One product per line."),
            f("geography", "Geography", RICH, S, report_role=NARR),
            f("accessAndConnectivity", "Access & connectivity", RICH, S, report_role=NARR,
              help="Nearest road, rail and town; how the cluster is reached."),
            f("communityContext", "Community context", RICH, S, report_role=NARR),
            f("artisanHouseholds", "Artisan households in the cluster", INT, S, min_value=0),
            f("livelihoodDependence", "Livelihood dependence on the craft", RICH, S,
              report_role=NARR,
              help="What share of household income the craft provides, and for how many."),
            f("culturalSignificance", "Cultural significance", RICH, S, report_role=NARR),
            f("giStatus", "GI status", ENUM, S, enum="GI_STATUS"),
            f("giDetails", "GI registration details", T, S, max_length=220),
            f("localTerminology", "Local terminology", RICH, S, report_role=NARR,
              help="Craft terms in the local language, with their meaning."),
            f("traditionalMotifs", "Traditional motifs", RICH, S, report_role=BULLETS),
            f("traditionalForms", "Traditional forms", RICH, S, report_role=BULLETS),
            f("traditionalColours", "Traditional colours", RICH, S, report_role=BULLETS),
            f("regionalTerminology", "Regional / alternate terminology", RICH, A,
              report_role=NARR, phase_note="Reviewer: “Phase 3 work”."),
            f("clusterLocation", "Cluster location", GEO, A,
              phase_note="Reviewer: “Phase 3 work” (GIS integration)."),
            *photos("clusterPhotos", "Cluster photographs", S, "Cluster photograph caption"),
            *photos("motifPhotos", "Motif photographs", S, "Motif caption"),
        )),
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
    notes=(
        "The reviewer noted that stages 5 and 6 “may come later when they actually go to the "
        "field after market and consumer survey”, so this stage is completable out of order."
    ),
    entities=(
        single("traditionalProcess", "DwTraditionalProcess", "Process overview", (
            f("processOverview", "Broad process steps", RICH, B, required=True, report_role=NARR,
              help="The making sequence in outline. Individual steps are recorded below."),
            f("totalMakingTime", "Total making time", DEC, S, unit="days", min_value=0),
            f("currentProblems", "Current problems", RICH, S, report_role=BULLETS,
              help="One problem per line."),
            f("qualityIndicators", "Quality indicators", RICH, A, report_role=BULLETS,
              phase_note="Reviewer: “Based on workshop app — Ankit”.",
              help="How the artisans themselves judge a good piece."),
            f("failurePoints", "Failure points", RICH, A, report_role=BULLETS),
            f("artisanAudio", "Artisan’s spoken explanation", AUDIO, A),
            f("artisanAudioTranscript", "Transcript of the recording", RICH, A, report_role=NARR),
        )),
        many("processStep", "DwProcessStep", "Process steps", (
            f("stepNumber", "Step", INT, B, required=True, min_value=1, report_role=COL,
              column_width_pct=8.0),
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
            f("processRef", "Documented process", REF, S, ref_model="Process",
              ref_scope=W_SCOPE, report_role=HIDDEN,
              help="Choose a process already documented for this workshop instead of "
                   "re-describing it."),
            fromref("name", "Step name", T, B, required=True, report_role=COL,
                    column_width_pct=24.0),
            f("localName", "Local name", T, S, report_role=COL, column_width_pct=18.0),
            f("description", "What happens", LT, S, report_role=COL, column_width_pct=34.0),
            f("timeTaken", "Time taken", DEC, S, unit="hours", min_value=0, report_role=COL,
              column_width_pct=16.0),
            f("performedBy", "Performed by", T, S),
            f("toolsUsed", "Tools used", TAGS, S),
            f("problems", "Problems at this step", LT, S),
            *photos("stepPhotos", "Step photographs", A, "Step photograph caption"),
            f("stepVideo", "Process video", VIDEO, A),
        ), label_field="name"),
        many("tool", "DwTool", "Tools", (
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
            f("toolRef", "Documented tool", REF, S, ref_model="ToolDocumentation",
              ref_scope=ALL_SCOPE, report_role=HIDDEN,
              help="Choose a tool already documented anywhere in the repository. Its details are "
                   "filled in below and can be corrected on this row."),
            fromref("name", "Tool", T, B, required=True, report_role=COL,
                    column_width_pct=24.0),
            fromref("localName", "Local name", T, S, report_role=COL, column_width_pct=18.0),
            f("toolType", "Type", T, S, report_role=COL, column_width_pct=16.0,
              help="What kind of tool this is, in the words the artisans use. Choose the family "
                   "below as well, so the same tool can be counted across clusters."),
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
            f("toolFamily", "Tool family", ENUM, S, enum="TOOL_TYPE",
              help="The broad category, for counting tools across workshops. Describe the tool "
                   "itself in “Type” above."),
            fromref("usedFor", "Used for", LT, S, report_role=COL, column_width_pct=26.0),
            fromref("material", "Made of", T, S),
            fromref("source", "Where obtained", T, S, report_role=COL, column_width_pct=16.0),
            fromref("cost", "Cost", MONEY, S, unit="INR", min_value=0),
            fromref("photo", "Photograph", IMG, S, report_role=GALLERY),
            f("photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP),
        ), label_field="name"),
        many("rawMaterial", "DwRawMaterial", "Raw materials", (
            f("name", "Material", T, B, required=True, report_role=COL, column_width_pct=22.0),
            f("localName", "Local name", T, S, report_role=COL, column_width_pct=16.0),
            f("family", "Material family", ENUM, S, enum="MATERIAL_FAMILY", report_role=COL,
              column_width_pct=14.0),
            f("source", "Source", T, S, report_role=COL, column_width_pct=18.0,
              help="Where the material is bought or gathered."),
            f("cost", "Cost", MONEY, S, unit="INR", min_value=0, report_role=COL,
              column_width_pct=14.0),
            f("costUnit", "Cost per", T, S, max_length=24, help="kg, metre, piece."),
            f("availability", "Availability", ENUM, S, enum="DEMAND_LEVEL", report_role=COL,
              column_width_pct=16.0),
            f("technicalProperties", "Technical properties", RICH, A, report_role=NARR,
              phase_note="Reviewer: “Based on workshop app — Ankit”."),
            f("photo", "Photograph", IMG, S, report_role=GALLERY),
            f("photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP),
        ), label_field="name"),
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
    notes=(
        "Completable out of order, with stage 5. The Advanced tier's 360-degree capture and "
        "automated quality assessment were marked by the reviewer as a later plug-in; the "
        "multi-view photograph slots that such a feature would consume are captured here."
    ),
    entities=(
        many("existingProduct", "DwExistingProduct", "Existing products", (
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
            f("artisanRef", "Artisan", REF, S, ref_model="Artisan", ref_scope=W_SCOPE,
              report_role=HIDDEN,
              help="Whose product this is. Choosing one narrows the product list below to that "
                   "artisan’s documented products."),
            f("productRef", "Documented product", REF, S, ref_model="ProductDocumentation",
              ref_filter_by="artisanRef", ref_scope=W_SCOPE, report_role=HIDDEN,
              help="Choose the product record instead of re-entering it. Pick the artisan "
                   "first to narrow this list."),
            fromref("artisanName", "Made by", T, S, report_role=COL, column_width_pct=16.0),
            f("productCode", "Product code", T, S, report_role=COL, column_width_pct=12.0),
            fromref("name", "Product", T, B, required=True, report_role=COL,
                    column_width_pct=22.0),
            f("category", "Category", ENUM, S, enum="PRODUCT_CATEGORY", report_role=COL,
              column_width_pct=16.0),
            f("lengthCm", "Length", DEC, B, unit="cm", min_value=0),
            f("widthCm", "Width", DEC, B, unit="cm", min_value=0),
            f("heightCm", "Height", DEC, S, unit="cm", min_value=0),
            f("weightG", "Weight", DEC, S, unit="g", min_value=0),
            f("dimensionsNote", "Dimensions (as described)", T, B, report_role=COL,
              column_width_pct=18.0,
              help="Free text, for a product the measured fields do not suit."),
            fromref("price", "Selling price", MONEY, B, required=True, unit="INR", min_value=0,
                    report_role=COL, column_width_pct=16.0),
            fromref("material", "Material", T, S, report_role=COL, column_width_pct=16.0),
            f("materialFamily", "Material family", ENUM, S, enum="MATERIAL_FAMILY"),
            f("technique", "Technique", T, S),
            fromref("use", "Use", LT, S),
            f("traditionType", "Traditional or contemporary", ENUM, S, enum="TRADITION_TYPE"),
            f("productionTimeDays", "Production time", DEC, S, unit="days", min_value=0),
            f("monthlyCapacity", "Monthly capacity", INT, S, unit="pieces", min_value=0),
            f("marketChannel", "Market channel", MENUM, S, enum="MARKET_CHANNEL"),
            f("problems", "Problems reported", RICH, S, report_role=NARR),
            # Written out rather than built by ``photos()`` for the sake of the one extra
            # sentence: the documented product's own photograph is seeded into this gallery, and
            # a designer who is not told that will add it a second time.
            fromref("productPhotos", "Photographs", IMGS, B, report_role=GALLERY),
            f("productPhotosCaption", "Photograph caption", T, B, caption_for="productPhotos",
              report_role=CAP),
            f("viewFront", "Front view", IMG, A),
            f("viewBack", "Back view", IMG, A),
            f("viewDetail", "Detail view", IMG, A),
            f("turntablePhotos", "360° capture", IMGS, A,
              phase_note="Reviewer: “Kumarjit and Rishi plug in”; “AI-driven work for AI team "
                         "— may be as plug in — later to be discussed not now”."),
        ), label_field="name"),
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
        "What the survey is meant to find out, who will be asked, where, and with which "
        "questions."
    ),
    notes="The reviewer suggested the Advanced tier here be “a simple upload tool and phase 2 work”.",
    entities=(
        single("surveyPlan", "DwSurveyPlan", "Survey plan", (
            f("objectives", "Survey objectives", RICH, B, required=True, report_role=BULLETS,
              help="One objective per line."),
            f("questionnaire", "Questionnaire", RICH, B, required=True, report_role=NARR,
              help="The questions to be asked. One per line."),
            f("questionnaireFile", "Questionnaire document", FILE, A,
              phase_note="Reviewer: “May be a simple upload tool and phase 2 work”."),
            f("respondentGroups", "Respondent groups", MENUM, S, enum="RESPONDENT_GROUP"),
            f("sampleSize", "Intended sample size", INT, S, min_value=0),
            f("surveyStartDate", "Survey start date", DATE, S),
            f("surveyEndDate", "Survey end date", DATE, S),
            f("languages", "Questionnaire languages", TAGS, A,
              help="Languages the questionnaire was translated into."),
        )),
        many("surveyPlace", "DwSurveyPlace", "Places to visit", (
            f("name", "Place", T, B, required=True, report_role=COL, column_width_pct=26.0),
            f("placeType", "Type", T, S, report_role=COL, column_width_pct=20.0,
              help="What the place is, as it is known locally. Choose the channel below as well, "
                   "so places can be compared across surveys."),
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
            f("placeChannel", "Market channel", ENUM, S, enum="MARKET_CHANNEL",
              help="Which kind of outlet this place is. The same list the products and the buyer "
                   "links use, so a survey can be compared with them."),
            f("cityDistrict", "City / District", T, S, report_role=COL, column_width_pct=20.0),
            f("plannedDate", "Planned date", DATE, S, report_role=COL, column_width_pct=16.0),
            f("respondentGroup", "Respondents expected", ENUM, S, enum="RESPONDENT_GROUP",
              report_role=COL, column_width_pct=18.0),
            f("notes", "Notes", LT, S),
        ), label_field="name"),
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
    notes=(
        "The reviewer asked for “a basic structure with text and image fields at the "
        "beginning. Later we can refine.” — which is what this stage is. Voice transcription "
        "and competitor image matching were deferred to “Phase 3 and only 1 or 2 features”."
    ),
    entities=(
        single("surveySummary", "DwSurveySummary", "Survey summary", (
            f("notes", "Field notes", RICH, B, required=True, report_role=NARR),
            f("responsesCollected", "Responses collected", INT, S, min_value=0,
              report_role=METRIC),
            f("consumerPreferences", "Consumer preferences", RICH, S, report_role=NARR),
            f("preferredCategories", "Product categories in demand", MENUM, S,
              enum="PRODUCT_CATEGORY"),
            f("aestheticVsFunction", "Aesthetics versus function", RICH, S, report_role=NARR),
            f("materialColourPreferences", "Material & colour preferences", RICH, S,
              report_role=NARR),
            f("priceSensitivity", "Price sensitivity", RICH, S, report_role=NARR),
            f("retailerFeedback", "Retailer & buyer feedback", RICH, S, report_role=NARR),
            f("bestSellers", "Best sellers observed", RICH, S, report_role=BULLETS),
            f("marketGaps", "Market gaps", RICH, S, report_role=BULLETS),
            *photos("marketPhotos", "Market photographs", B, "Market photograph caption"),
        )),
        many("surveyResponse", "DwSurveyResponse", "Survey responses", (
            f("respondentName", "Respondent", T, B, report_role=COL, column_width_pct=20.0),
            f("respondentGroup", "Group", ENUM, B, required=True, enum="RESPONDENT_GROUP",
              report_role=COL, column_width_pct=16.0),
            f("place", "Place", T, S, report_role=COL, column_width_pct=18.0),
            f("surveyDate", "Date", DATE, S, report_role=COL, column_width_pct=14.0),
            f("response", "Response", LT, B, required=True, report_role=COL,
              column_width_pct=32.0),
            f("productsDiscussed", "Products discussed", TAGS, S),
            f("priceExpectation", "Price expectation", MONEY, S, unit="INR", min_value=0),
            f("contact", "Contact", FieldType.PHONE, S),
            f("location", "Location", GEO, A,
              phase_note="Reviewer: “Phase 3 and only 1 or 2 features” (geo/date tagging)."),
            f("voiceNote", "Voice recording", AUDIO, A),
            f("voiceTranscript", "Transcript", RICH, A, report_role=NARR),
            *photos("responsePhotos", "Photographs", S, "Photograph caption"),
        ), label_field="respondentName"),
        many("competitorProduct", "DwCompetitorProduct", "Competitor products", (
            f("name", "Product", T, B, required=True, report_role=COL, column_width_pct=24.0),
            f("seller", "Seller / brand", T, S, report_role=COL, column_width_pct=20.0),
            f("category", "Category", ENUM, S, enum="PRODUCT_CATEGORY", report_role=COL,
              column_width_pct=16.0),
            f("material", "Material", T, S, report_role=COL, column_width_pct=16.0),
            f("price", "Price", MONEY, B, required=True, unit="INR", min_value=0,
              report_role=COL, column_width_pct=14.0),
            f("origin", "Origin", T, S, report_role=COL, column_width_pct=10.0),
            f("observation", "Observation", RICH, S, report_role=NARR),
            f("photo", "Photograph", IMG, S, report_role=GALLERY),
            f("photoCaption", "Photograph caption", T, S, caption_for="photo", report_role=CAP),
        ), label_field="name"),
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
    notes=(
        "The reviewer marked most Standard-tier items optional and deferred the AI-assisted "
        "Advanced tier to “Phase 3 and some of them by the AI team”."
    ),
    entities=(
        single("marketAnalysis", "DwMarketAnalysis", "Analysis", (
            f("surveyFindings", "Survey findings", RICH, B, required=True, report_role=NARR),
            f("mainOpportunities", "Main design opportunities", RICH, B, required=True,
              report_role=BULLETS, help="One opportunity per line."),
            f("demandLevel", "Overall demand", ENUM, S, enum="DEMAND_LEVEL"),
            f("targetConsumers", "Target consumers", RICH, S, report_role=NARR),
            f("rawMaterialImplications", "Raw-material implications", RICH, S, report_role=NARR),
            f("competitorAnalysis", "Competitor analysis", RICH, S, report_role=NARR),
            f("supplyChainIssues", "Supply-chain issues", RICH, S, report_role=NARR),
            f("artisanSkillMapping", "Artisan skill mapping", RICH, S, report_role=NARR),
            f("trendObservations", "Trend observations", RICH, S, report_role=NARR),
            f("colourObservations", "Colour observations", RICH, S, report_role=NARR),
            f("materialObservations", "Material observations", RICH, S, report_role=NARR),
        )),
        many("swotPoint", "DwSwotPoint", "SWOT analysis", (
            f("kind", "Kind", ENUM, B, required=True, enum="SWOT_KIND", report_role=COL,
              column_width_pct=18.0),
            f("point", "Point", LT, B, required=True, report_role=COL, column_width_pct=52.0),
            f("evidence", "Evidence", LT, S, report_role=COL, column_width_pct=30.0,
              help="Which survey response or observation supports this."),
        ), label_field="point"),
        many("priceBand", "DwPriceBand", "Price bands", (
            f("category", "Category", ENUM, B, required=True, enum="PRODUCT_CATEGORY",
              report_role=COL, column_width_pct=26.0),
            f("lowPrice", "From", MONEY, B, required=True, unit="INR", min_value=0,
              report_role=COL, column_width_pct=18.0),
            f("highPrice", "To", MONEY, B, required=True, unit="INR", min_value=0,
              report_role=COL, column_width_pct=18.0),
            f("demand", "Demand", ENUM, S, enum="DEMAND_LEVEL", report_role=COL,
              column_width_pct=16.0),
            f("notes", "Notes", LT, S, report_role=COL, column_width_pct=22.0),
        ), label_field="category"),
        many("designOpportunity", "DwDesignOpportunity", "Design opportunities", (
            f("title", "Opportunity", T, B, required=True, report_role=COL,
              column_width_pct=28.0),
            f("description", "Description", LT, B, required=True, report_role=COL,
              column_width_pct=38.0),
            f("targetCategory", "Target category", ENUM, S, enum="PRODUCT_CATEGORY",
              report_role=COL, column_width_pct=18.0),
            f("priority", "Priority", ENUM, S, enum="SEVERITY", report_role=COL,
              column_width_pct=16.0),
        ), label_field="title"),
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
    notes=(
        "The reviewer marked the Standard tier “some of these and only as optional fields” and "
        "deferred the structured, evidence-generated brief to “phase 4”. This is also where "
        "traditional and contemporary DESIGN direction belongs, per the reviewer's note on "
        "stage 4."
    ),
    entities=(
        single("designBrief", "DwDesignBrief", "Design brief", (
            f("concept", "Designer’s concept and vision", RICH, B, required=True,
              report_role=NARR),
            f("targetCategories", "Target product categories", MENUM, B, required=True,
              enum="PRODUCT_CATEGORY"),
            f("targetMarket", "Target market", RICH, S, report_role=NARR),
            f("userNeeds", "User needs", RICH, S, report_role=NARR),
            f("intendedPriceLow", "Intended price from", MONEY, S, unit="INR", min_value=0),
            f("intendedPriceHigh", "Intended price to", MONEY, S, unit="INR", min_value=0),
            f("materialDirection", "Material direction", RICH, S, report_role=NARR),
            f("colourDirection", "Colour direction", RICH, S, report_role=NARR),
            f("motifFormDirection", "Motif & form direction", RICH, S, report_role=NARR),
            f("traditionalDesignReference", "Traditional design reference", RICH, S,
              report_role=NARR,
              help="The traditional designs this work draws on. Moved here from the cluster "
                   "background at the reviewer’s request."),
            f("contemporaryDesignReference", "Contemporary design reference", RICH, S,
              report_role=NARR),
            f("sustainability", "Sustainability", RICH, S, report_role=NARR),
            f("functionality", "Functionality", RICH, S, report_role=NARR),
            f("craftIntegrityBaseline", "Craft-integrity baseline", RICH, A, report_role=NARR,
              phase_note="Reviewer: “May be considered in phase 4 !!!”",
              help="What must not change if the piece is still to be this craft."),
            *photos("moodBoard", "Mood & reference board", A, "Reference caption"),
        )),
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
    notes=(
        "The reviewer marked the Standard tier “optional fields” and noted the Advanced "
        "image-processing tier as “may be Deepika app for now”. The vector/line-art slots such "
        "a tool would fill are present; the processing itself is not claimed here."
    ),
    entities=(
        many("sketch", "DwSketch", "Sketches", (
            f("sketchNo", "Sketch number", T, B, required=True, report_role=COL,
              column_width_pct=12.0),
            f("name", "Sketch name", T, B, required=True, report_role=COL,
              column_width_pct=24.0),
            f("version", "Version", INT, A, min_value=1,
              help="Increment when a sketch is redrawn rather than replaced, so the iteration "
                   "history survives."),
            f("supersedesSketch", "Supersedes sketch", REF, A, ref_model="DwSketch",
              report_role=HIDDEN),
            f("image", "Sketch image", IMG, B, required=True, report_role=GALLERY),
            f("imageCaption", "Sketch caption", T, B, caption_for="image", report_role=CAP),
            f("category", "Product category", ENUM, S, enum="PRODUCT_CATEGORY",
              report_role=COL, column_width_pct=18.0),
            f("intendedUse", "Intended use", LT, S),
            f("materials", "Materials", TAGS, S, report_role=COL, column_width_pct=18.0),
            f("lengthCm", "Length", DEC, S, unit="cm", min_value=0),
            f("widthCm", "Width", DEC, S, unit="cm", min_value=0),
            f("heightCm", "Height", DEC, S, unit="cm", min_value=0),
            f("dimensionsNote", "Dimensions (as described)", T, S),
            f("colours", "Colours", TAGS, S),
            f("motifs", "Motifs", TAGS, S),
            f("targetMarket", "Target market", T, S),
            f("expectedPrice", "Expected price", MONEY, S, unit="INR", min_value=0,
              report_role=COL, column_width_pct=16.0),
            f("designerNotes", "Designer’s notes", RICH, S, report_role=NARR),
            f("lineArtFile", "Line art / vector file", FILE, A,
              phase_note="Reviewer: “May be Deepika app for now”.",
              help="An SVG or vector export, if one was produced."),
            f("annotations", "Annotations", RICH, A, report_role=NARR),
        ), label_field="name"),
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
    notes=(
        "The reviewer wrote “we may consider deleting this entire section for now”. It is kept "
        "but marked optional: a workshop that skips it is still complete, and no field here is "
        "required of the workshop as a whole. It is retained because the selection reason is "
        "the only record of WHY a design was dropped, which the research use of this data needs."
    ),
    entities=(
        many("sketchReview", "DwSketchReview", "Sketch reviews", (
            f("sketchRef", "Sketch", REF, B, required=True, ref_model="DwSketch",
              report_role=COL, column_width_pct=20.0),
            f("decision", "Decision", ENUM, B, required=True, enum="REVIEW_DECISION",
              report_role=COL, column_width_pct=14.0),
            f("reason", "Reason", LT, S, report_role=COL, column_width_pct=30.0),
            f("artisanComments", "Artisan / MCP comments", LT, S, report_role=COL,
              column_width_pct=24.0),
            f("reviewedBy", "Reviewed by", T, S, report_role=COL, column_width_pct=12.0),
            f("reviewDate", "Review date", DATE, S),
            f("technicalFeasibility", "Technical feasibility", ENUM, S, enum="QUALITY_RATING"),
            f("materialFeasibility", "Material feasibility", ENUM, S, enum="QUALITY_RATING"),
            f("estimatedCost", "Estimated cost", MONEY, S, unit="INR", min_value=0),
            f("estimatedTimeDays", "Estimated time", DEC, S, unit="days", min_value=0),
            f("skillRequired", "Skill required", LT, S),
            f("rank", "Rank", INT, A, min_value=1,
              help="Where this sketch placed in a ranked review."),
            f("voiceFeedback", "Voice feedback", AUDIO, A),
            f("voiceFeedbackTranscript", "Transcript", RICH, A, report_role=NARR),
        ), label_field="sketchRef"),
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
    notes=(
        "The reviewer wrote “let's simplify” on the Basic tier, and referred the Advanced "
        "measurement tier to “Kumarjit da and team”, noting “the system we used in the "
        "workshop app… we can supply a PDF of measurements to be printed”."
    ),
    entities=(
        many("prototype", "DwPrototype", "Prototypes", (
            f("prototypeCode", "Prototype ID", T, B, required=True, report_role=COL,
              column_width_pct=12.0),
            f("name", "Prototype name", T, B, required=True, report_role=COL,
              column_width_pct=24.0),
            f("sketchRef", "From sketch", REF, B, required=True, ref_model="DwSketch",
              report_role=COL, column_width_pct=16.0),
            # The maker is chosen from the ROSTER, not from the artisan table: the person who
            # made this prototype was in the room, and stage 3 is the list of who was. That is
            # also what makes the count of prototypes per participant answerable.
            f("artisanRef", "Artisan assigned", REF, B, required=True, ref_model="DwParticipant",
              ref_scope=ALL_SCOPE, report_role=COL, column_width_pct=20.0,
              help="Chosen from the artisans enrolled at stage 3."),
            # Filtered BY THE ROSTER ENTRY above, which holds a participant id rather than an
            # artisan id. The resolver follows the participant back to the artisan record it was
            # built from, so the same cascade works here as at stage 6 without the form having
            # to know that the two pickers hold different kinds of id. A hand-typed participant
            # — one with no artisan record behind them — yields an empty product list, which is
            # the honest answer: there are no documented products to attribute to a person the
            # database has never seen.
            f("productRef", "Existing product developed from", REF, S,
              ref_model="ProductDocumentation", ref_filter_by="artisanRef",
              ref_scope=W_SCOPE, report_role=HIDDEN,
              help="If this prototype reworks a product the artisan already makes, choose it "
                   "here. The link is what lets the report show the before and the after."),
            fromref("productName", "Developed from", T, S),
            f("materials", "Materials", TAGS, B, required=True, report_role=COL,
              column_width_pct=28.0),
            *photos("prototypePhotos", "Prototype photographs", B, "Photograph caption"),
            f("lengthCm", "Length", DEC, S, unit="cm", min_value=0),
            f("widthCm", "Width", DEC, S, unit="cm", min_value=0),
            f("heightCm", "Height", DEC, S, unit="cm", min_value=0),
            f("diameterCm", "Diameter", DEC, S, unit="cm", min_value=0),
            f("weightG", "Weight", DEC, S, unit="g", min_value=0),
            f("dimensionsNote", "Dimensions (as described)", T, S),
            f("toolsUsed", "Tools used", TAGS, S),
            f("processSummary", "Process followed", RICH, S, report_role=NARR),
            f("makingTimeDays", "Making time", DEC, S, unit="days", min_value=0,
              report_role=COL, column_width_pct=12.0),
            f("materialCost", "Material cost", MONEY, S, unit="INR", min_value=0),
            f("labourCost", "Labour cost", MONEY, S, unit="INR", min_value=0),
            f("problemsAndModifications", "Problems & modifications", RICH, S, report_role=NARR),
            f("startDate", "Started", DATE, S),
            f("completedDate", "Completed", DATE, S),
            f("measurementSheet", "Measurement sheet", FILE, A,
              phase_note="Reviewer: “The system we used in the workshop app… we can supply a "
                         "PDF of measurements to be printed” — Kumarjit da and team.",
              help="A printable measured drawing, if one was produced."),
            f("processVideo", "Process video", VIDEO, A),
            f("audioNarration", "Audio narration", AUDIO, A),
            f("audioTranscript", "Narration transcript", RICH, A, report_role=NARR),
            f("turntablePhotos", "360° capture", IMGS, A,
              phase_note="Reviewer: “Kumar da team”."),
            f("modelFile", "3D model", FILE, A, phase_note="Reviewer: “Kumar da team”."),
        ), label_field="name"),
        many("prototypeStageLog", "DwPrototypeStageLog", "Stage logs", (
            f("prototypeRef", "Prototype", REF, B, required=True, ref_model="DwPrototype",
              report_role=COL, column_width_pct=18.0),
            f("logDate", "Date", DATE, B, required=True, report_role=COL,
              column_width_pct=14.0),
            f("stageName", "Stage", T, B, required=True, report_role=COL,
              column_width_pct=22.0),
            f("notes", "What was done", LT, B, required=True, report_role=COL,
              column_width_pct=46.0),
            f("hoursSpent", "Hours", DEC, S, unit="hours", min_value=0),
            *photos("logPhotos", "Photographs", S, "Photograph caption"),
        ), label_field="stageName", parent="prototype"),
        many("materialUsage", "DwMaterialUsage", "Material usage", (
            f("prototypeRef", "Prototype", REF, B, required=True, ref_model="DwPrototype",
              report_role=COL, column_width_pct=20.0),
            f("material", "Material", T, B, required=True, report_role=COL,
              column_width_pct=26.0),
            f("quantity", "Quantity", DEC, B, required=True, min_value=0, report_role=COL,
              column_width_pct=14.0),
            f("unit", "Unit", T, B, required=True, max_length=24, report_role=COL,
              column_width_pct=12.0),
            f("rate", "Rate", MONEY, S, unit="INR", min_value=0, report_role=COL,
              column_width_pct=14.0),
            f("amount", "Amount", MONEY, S, unit="INR", min_value=0, report_role=COL,
              column_width_pct=14.0,
              help="Leave blank to compute it as quantity × rate.",
              derived_kind="PRODUCT", derived_from=("quantity", "rate")),
        ), label_field="material", parent="prototype"),
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
    notes="The reviewer marked the Standard tier “optional fields”.",
    entities=(
        many("prototypeIteration", "DwPrototypeIteration", "Iterations", (
            f("prototypeRef", "Prototype", REF, B, required=True, ref_model="DwPrototype",
              report_role=COL, column_width_pct=16.0),
            f("versionNo", "Version", INT, B, required=True, min_value=1, report_role=COL,
              column_width_pct=10.0),
            f("changesMade", "Changes made", LT, B, required=True, report_role=COL,
              column_width_pct=30.0),
            f("designerComments", "Designer’s comments", LT, B, report_role=COL,
              column_width_pct=22.0),
            f("artisanComments", "Artisan’s comments", LT, B, report_role=COL,
              column_width_pct=22.0),
            f("reasonForChange", "Reason for the change", RICH, S, report_role=NARR),
            f("problemType", "Problem type", T, S, max_length=80,
              help="What went wrong, in your own words. Tick the areas below as well."),
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
            f("problemAreas", "Problem areas", MENUM, S, enum="PROBLEM_TYPE",
              help="Tick every area the problem touched. More than one is normal."),
            f("costEffect", "Effect on cost", MONEY, S, unit="INR"),
            f("timeEffectDays", "Effect on time", DEC, S, unit="days"),
            f("usability", "Usability", ENUM, S, enum="QUALITY_RATING"),
            f("quality", "Quality", ENUM, S, enum="QUALITY_RATING"),
            f("finishing", "Finishing", ENUM, S, enum="QUALITY_RATING"),
            f("iterationDate", "Date", DATE, S),
            f("beforePhoto", "Before", IMG, A, report_role=GALLERY),
            f("beforePhotoCaption", "Before caption", T, A, caption_for="beforePhoto",
              report_role=CAP),
            f("afterPhoto", "After", IMG, A, report_role=GALLERY),
            f("afterPhotoCaption", "After caption", T, A, caption_for="afterPhoto",
              report_role=CAP),
            f("failureNote", "Failure log", RICH, A, report_role=NARR),
        ), label_field="changesMade", parent="prototype"),
    ),
)


# --------------------------------------------------------------------------------------
# 15. Prototype Selection / Validation
# --------------------------------------------------------------------------------------

STAGE_15 = StageSpec(
    number=15,
    key="PROTOTYPE_VALIDATION",
    title="Prototype Selection & Validation",
    purpose=(
        "Which prototypes were accepted, on what assessment, and with whose approval."
    ),
    notes=(
        "The reviewer marked the Standard tier “optional fields” and said of the Advanced tier "
        "“at the beginning — just crowdsourced designer feedback mechanism. Reset for Phase 4.”"
    ),
    entities=(
        many("prototypeValidation", "DwPrototypeValidation", "Validation", (
            f("prototypeRef", "Prototype", REF, B, required=True, ref_model="DwPrototype",
              report_role=COL, column_width_pct=20.0),
            f("decision", "Decision", ENUM, B, required=True, enum="REVIEW_DECISION",
              report_role=COL, column_width_pct=14.0),
            f("technicalQuality", "Technical quality", ENUM, S, enum="QUALITY_RATING",
              report_role=COL, column_width_pct=13.0),
            f("functionality", "Functionality", ENUM, S, enum="QUALITY_RATING",
              report_role=COL, column_width_pct=13.0),
            f("aesthetics", "Aesthetics", ENUM, S, enum="QUALITY_RATING", report_role=COL,
              column_width_pct=13.0),
            f("craftIntegrity", "Craft integrity", ENUM, S, enum="QUALITY_RATING",
              report_role=COL, column_width_pct=13.0),
            f("marketSuitability", "Market suitability", ENUM, S, enum="QUALITY_RATING",
              report_role=COL, column_width_pct=14.0),
            f("reason", "Reason", RICH, S, report_role=NARR),
            f("designerApproval", "Designer’s approval", BOOL, S),
            f("mcpApproval", "MCP approval", BOOL, S),
            f("approvedBy", "Approved by", T, S),
            f("approvalDate", "Approval date", DATE, S),
            f("finalLengthCm", "Final length", DEC, S, unit="cm", min_value=0),
            f("finalWidthCm", "Final width", DEC, S, unit="cm", min_value=0),
            f("finalHeightCm", "Final height", DEC, S, unit="cm", min_value=0),
            f("buyerFeedback", "Buyer / retailer / exporter feedback", RICH, A,
              report_role=NARR,
              phase_note="Reviewer: “At the beginning — just crowdsourced designer feedback "
                         "mechanism. Reset for Phase 4”."),
            f("userTestingNotes", "User testing", RICH, A, report_role=NARR),
            f("qualityChecklist", "Quality checklist", RICH, A, report_role=BULLETS,
              help="One check per line; prefix with ✓ or ✗."),
        ), label_field="prototypeRef"),
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
    notes="The reviewer referred the Advanced catalogue-asset tier to “Kumarjit plug in”.",
    entities=(
        many("finalProduct", "DwFinalProduct", "Final products", (
            f("productCode", "Product code", T, B, required=True, report_role=COL,
              column_width_pct=12.0),
            f("name", "Product name", T, B, required=True, report_role=COL,
              column_width_pct=24.0),
            f("prototypeRef", "From prototype", REF, S, ref_model="DwPrototype",
              report_role=HIDDEN),
            *photos("finalPhotos", "Final photographs", B, "Photograph caption"),
            f("lengthCm", "Length", DEC, B, unit="cm", min_value=0),
            f("widthCm", "Width", DEC, B, unit="cm", min_value=0),
            f("heightCm", "Height", DEC, B, unit="cm", min_value=0),
            f("weightG", "Weight", DEC, S, unit="g", min_value=0),
            f("dimensionsNote", "Dimensions", T, B, report_role=COL, column_width_pct=18.0),
            f("materials", "Materials", TAGS, B, required=True, report_role=COL,
              column_width_pct=22.0),
            f("technique", "Technique", T, S, report_role=COL, column_width_pct=24.0),
            f("makingProcess", "Making process", RICH, S, report_role=NARR),
            f("makingTimeDays", "Making time", DEC, S, unit="days", min_value=0),
            f("costPrice", "Cost price", MONEY, S, unit="INR", min_value=0),
            f("sellingPrice", "Selling price", MONEY, S, unit="INR", min_value=0,
              report_role=COL, column_width_pct=20.0),
            f("artisanRef", "Made by", REF, S, ref_model="DwParticipant"),
            f("designerName", "Designed by", T, S),
            f("description", "Product description", RICH, S, report_role=NARR,
              help="The paragraph that would appear beside this piece in a catalogue."),
            f("category", "Category", ENUM, S, enum="PRODUCT_CATEGORY"),
            f("catalogPhotos", "Catalogue photographs", IMGS, A,
              phase_note="Reviewer: “Refer to Kumarjit plug in”."),
            f("lineDrawing", "Vector / line drawing", FILE, A),
            f("turntablePhotos", "360° asset", IMGS, A),
            f("technicalSpecification", "Technical specification", RICH, A, report_role=NARR),
        ), label_field="name"),
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
    notes=(
        "The reviewer marked several Standard-tier items optional. The cost-calculator inputs "
        "are stored, not just the totals, so a figure in the report can always be traced to "
        "the quantities and rates it came from."
    ),
    entities=(
        many("costSheet", "DwCostSheet", "Cost sheets", (
            f("productRef", "Product", REF, B, required=True, ref_model="DwFinalProduct",
              report_role=COL, column_width_pct=22.0),
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
            f("materialCost", "Material cost", MONEY, B, required=True, unit="INR",
              min_value=0, report_role=COL, column_width_pct=14.0,
              help="The material total for this product — the Amount column of its material "
                   "lines below, added up."),
            f("labourCost", "Labour cost", MONEY, B, required=True, unit="INR", min_value=0,
              report_role=COL, column_width_pct=14.0,
              help="The labour total for this product — the Amount column of its labour lines "
                   "below, added up."),
            f("packagingCost", "Packaging", MONEY, S, unit="INR", min_value=0),
            f("finishingCost", "Finishing", MONEY, S, unit="INR", min_value=0),
            f("transportCost", "Transport", MONEY, S, unit="INR", min_value=0),
            f("overheadCost", "Overhead", MONEY, S, unit="INR", min_value=0),
            # "Derived from the costs above" is now true. Six heads, of which four are optional,
            # so SUM treats a blank as zero — and a row with none of them filled derives nothing
            # at all, because "₹ 0.00" in a cost sheet a ministry reads is a claim and not a
            # blank.
            f("totalCost", "Total cost", MONEY, S, unit="INR", min_value=0, report_role=COL,
              column_width_pct=14.0, help="Derived from the costs above.",
              derived_kind="SUM",
              derived_from=("materialCost", "labourCost", "packagingCost", "finishingCost",
                            "transportCost", "overheadCost")),
            f("marginPercent", "Margin", PCT, S, min_value=0, max_value=500),
            f("expectedPrice", "Expected price", MONEY, B, required=True, unit="INR",
              min_value=0, report_role=COL, column_width_pct=16.0),
            f("wholesalePrice", "Wholesale price", MONEY, S, unit="INR", min_value=0),
            f("retailPrice", "Retail price", MONEY, S, unit="INR", min_value=0,
              report_role=COL, column_width_pct=20.0),
            f("targetBuyer", "Target buyer", T, S),
            f("marketingStrategy", "Marketing strategy", RICH, S, report_role=NARR),
            f("packagingDescription", "Packaging", RICH, S, report_role=NARR),
            f("packagingPhoto", "Packaging photograph", IMG, A, report_role=GALLERY),
            f("packagingPhotoCaption", "Packaging caption", T, A, caption_for="packagingPhoto",
              report_role=CAP),
        ), label_field="productRef"),
        many("costMaterialLine", "DwCostMaterialLine", "Material cost lines", (
            f("costSheetRef", "Cost sheet", REF, B, required=True, ref_model="DwCostSheet",
              report_role=HIDDEN),
            f("item", "Item", T, B, required=True, report_role=COL, column_width_pct=34.0),
            f("quantity", "Quantity", DEC, B, required=True, min_value=0, report_role=COL,
              column_width_pct=14.0),
            f("unit", "Unit", T, B, required=True, max_length=24, report_role=COL,
              column_width_pct=12.0),
            f("rate", "Rate", MONEY, B, required=True, unit="INR", min_value=0,
              report_role=COL, column_width_pct=20.0),
            # "Derived as quantity × rate" was a promise nothing kept: every Amount cell in the
            # generated report's material-lines table was empty, on 28 lines across four
            # products. PRODUCT is the kind `derive_value` and `derivedFields.ts` already
            # implement, so declaring it here is what makes the column appear as the designer
            # types on all three clients.
            f("amount", "Amount", MONEY, S, unit="INR", min_value=0, report_role=COL,
              column_width_pct=20.0, help="Derived as quantity × rate.",
              derived_kind="PRODUCT", derived_from=("quantity", "rate")),
        ), label_field="item", parent="costSheet"),
        many("costLabourLine", "DwCostLabourLine", "Labour cost lines", (
            f("costSheetRef", "Cost sheet", REF, B, required=True, ref_model="DwCostSheet",
              report_role=HIDDEN),
            f("task", "Task", T, B, required=True, report_role=COL, column_width_pct=32.0),
            f("persons", "Persons", INT, B, required=True, min_value=0, report_role=COL,
              column_width_pct=12.0),
            f("days", "Days", DEC, B, required=True, min_value=0, report_role=COL,
              column_width_pct=12.0),
            f("rate", "Rate per day", MONEY, B, required=True, unit="INR", min_value=0,
              report_role=COL, column_width_pct=22.0),
            # Three factors, and `derive_value`'s PRODUCT multiplies every key in derived_from,
            # so the same kind covers it.
            f("amount", "Amount", MONEY, S, unit="INR", min_value=0, report_role=COL,
              column_width_pct=22.0, help="Derived as persons × days × rate.",
              derived_kind="PRODUCT", derived_from=("persons", "days", "rate")),
        ), label_field="task", parent="costSheet"),
        many("buyerLink", "DwBuyerLink", "Buyer linkages", (
            f("buyerName", "Buyer", T, B, required=True, report_role=COL,
              column_width_pct=26.0),
            f("buyerType", "Type", ENUM, S, enum="MARKET_CHANNEL", report_role=COL,
              column_width_pct=20.0),
            f("contact", "Contact", T, S, report_role=COL, column_width_pct=20.0),
            f("interest", "Interest expressed", LT, S, report_role=COL, column_width_pct=34.0),
            f("followUpDate", "Follow-up due", DATE, S),
        ), label_field="buyerName"),
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
    notes=(
        "The counts here are derived from the records by default; the manual fields exist only "
        "to override a derived number, and an override carries a note explaining itself. The "
        "reviewer asked for “just option for voice and transcript — if you think it apt”, which "
        "is what the Advanced tier below is."
    ),
    entities=(
        single("outcomes", "DwOutcomes", "Outcomes", (
            f("achievements", "Achievements", RICH, B, required=True, report_role=NARR),
            f("problems", "Problems faced", RICH, B, required=True, report_role=NARR),
            f("artisanFeedback", "Artisan feedback", RICH, B, required=True, report_role=NARR),
            f("designerComments", "Designer’s comments", RICH, B, required=True,
              report_role=NARR),
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
            f("designsCountOverride", "Number of designs (override)", INT, S, min_value=0,
              report_role=HIDDEN,
              help="Leave blank to count the sketches recorded."),
            f("prototypesCountOverride", "Number of prototypes (override)", INT, S,
              min_value=0, report_role=HIDDEN,
              help="Leave blank to count the prototypes recorded."),
            f("countOverrideReason", "Reason for the override", T, S, report_role=HIDDEN,
              help="Required if either count above is filled in. It is printed under the "
                   "headline figure, so it is what an officer reads beside the number."),
            f("skillsTransferred", "Skills transferred", RICH, S, report_role=BULLETS),
            f("materialsTested", "Materials tested", TAGS, S),
            f("techniquesTested", "Techniques tested", TAGS, S),
            f("recommendations", "Recommendations", RICH, S, report_role=BULLETS),
            f("performanceVsTarget", "Performance against planned targets", RICH, A,
              report_role=NARR),
            f("feedbackAudio", "Voice feedback", AUDIO, A,
              phase_note="Reviewer: “Just option for voice and transcript — if you think it apt”."),
            f("feedbackTranscript", "Feedback transcript", RICH, A, report_role=NARR),
            f("feedbackVideo", "Video feedback", VIDEO, A),
        )),
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
        single("closing", "DwClosing", "Closing", (
            f("finalInspectionNote", "Final inspection", RICH, B, required=True,
              report_role=NARR),
            f("closingNote", "Closing note", RICH, S, report_role=NARR),
            f("closingDate", "Closing date", DATE, S),
            f("inspectingOfficer", "Inspecting officer", T, S),
            f("inspectingOfficerDesignation", "Designation", T, S),
            f("officerComments", "Officer’s comments", RICH, S, report_role=NARR),
            f("participantsCompleted", "Participants who completed", INT, S, min_value=0,
              report_role=METRIC),
            *photos("closingPhotos", "Closing photographs", B, "Closing photograph caption"),
            f("summaryVideo", "Video summary", VIDEO, A),
        )),
        many("official", "DwOfficial", "Officials present", (
            f("name", "Name", T, B, required=True, report_role=COL, column_width_pct=30.0),
            f("designation", "Designation", T, B, required=True, report_role=COL,
              column_width_pct=34.0),
            f("organisation", "Organisation", T, S, report_role=COL, column_width_pct=36.0),
            f("comments", "Comments", LT, S),
        ), label_field="name"),
        many("certificate", "DwCertificate", "Certificates & attendance", (
            f("participantRef", "Artisan", REF, B, required=True, ref_model="DwParticipant",
              report_role=COL, column_width_pct=30.0),
            f("certificateNo", "Certificate number", T, S, report_role=COL,
              column_width_pct=22.0),
            f("issued", "Issued", BOOL, B, required=True, report_role=COL,
              column_width_pct=14.0),
            f("issueDate", "Issue date", DATE, S, report_role=COL, column_width_pct=18.0),
            f("daysAttended", "Days attended", INT, S, min_value=0, report_role=COL,
              column_width_pct=16.0),
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
            f("signatureImage", "Signature", IMG, S,
              help="Sign on the pad, or attach a photograph of the signed sheet. Attendance is "
                   "recorded by the fields above and is never held up by this."),
            f("certificateFile", "Certificate", FILE, A),
        ), label_field="participantRef"),
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
        single("reportSettings", "DwReportSettings", "Report settings", (
            f("templateId", "Report template", ENUM, B, required=True, enum="REPORT_TEMPLATE"),
            f("pageSize", "Page size", ENUM, B, enum="PAGE_SIZE"),
            # ONE COLOUR, NOT EIGHT. A ReportTheme carries eight colours and every renderer reads
            # all of them; ``report_theme.theme_from_accent`` derives seven of the eight from this
            # single accent so that no reachable answer produces an unreadable document — white
            # header text on pale yellow being the one that ships from every form that offers a
            # colour well per colour. The preset is the name a designer can repeat to the officer
            # who asked for it ("the maroon one"); the hex is what actually renders.
            f("themePreset", "Report colour", ENUM, S, enum="REPORT_ACCENT_PRESET",
              help="Twelve colours chosen to stay distinguishable when the report is printed on "
                   "a black-and-white office printer. Choose “Custom colour” to use the picker."),
            f("themeAccent", "Report colour (hex)", T, S, max_length=7,
              help="The accent as #RRGGBB. Filled in for you when a named colour is chosen; the "
                   "headings, the table headers, the rules and the figures are all derived from "
                   "it. Left blank, the report keeps the template’s own colour."),
            f("fontPreset", "Report typeface", ENUM, S, enum="REPORT_FONT",
              help="Applies to the Word document, which is the file that is submitted and the one "
                   "an officer edits. The PDF embeds whichever face the server has that can draw "
                   "Odia, Devanagari and the rupee sign, so a typeface chosen here is reported as "
                   "a warning rather than silently substituted there."),
            f("reportTitle", "Report title", T, S, max_length=220,
              help="Leave blank to use the workshop title."),
            f("reportSubtitle", "Report subtitle", T, S, max_length=220),
            f("organisationLine", "Organisation line", T, S, max_length=220,
              help="Printed above the title on the cover."),
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
            f("includeTranscripts", "Append the recordings’ transcripts", BOOL, S,
              help="Adds an annexure containing every transcript the workshop collected."),
            f("includeCompletenessAnnexure", "Include the completeness annexure", BOOL, S),
            f("numberHeadings", "Number the headings", BOOL, S),
            f("excludedStages", "Stages to leave out", TAGS, S,
              help="Stage keys omitted from this report."),
            f("headerText", "Running header", T, S, max_length=180),
            f("footerText", "Running footer", T, S, max_length=180),
            f("submittedTo", "Submitted to", T, S, max_length=220),
            f("submissionDate", "Submission date", DATE, S),
        )),
        many("reportExport", "DwReportExport", "Generated reports", (
            f("format", "Format", ENUM, B, required=True, enum="EXPORT_FORMAT",
              report_role=COL, column_width_pct=12.0),
            f("templateId", "Template", ENUM, B, required=True, enum="REPORT_TEMPLATE",
              report_role=COL, column_width_pct=24.0),
            f("fileName", "File", T, B, required=True, report_role=COL,
              column_width_pct=28.0),
            f("generatedAt", "Generated", DATE, B, required=True, report_role=COL,
              column_width_pct=16.0),
            f("generatedBy", "Generated by", T, S, report_role=COL, column_width_pct=20.0),
            f("fileSizeBytes", "Size", INT, S, unit="bytes", min_value=0),
            f("pageCount", "Pages", INT, S, min_value=0),
            f("checksumSha256", "SHA-256", T, S, max_length=64,
              help="Lets a submitted file be matched against this record."),
            f("generatedOnDevice", "Generated on device", BOOL, S,
              help="True when the phone produced the file with no network."),
            f("warnings", "Warnings at generation", LT, S),
        ), label_field="fileName"),
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
    notes=(
        "Blur and resolution ARE computable on device — the variance of the Laplacian and the "
        "pixel dimensions — so those flags are real fields with an ``autoDetected`` marker "
        "rather than a deferred feature. Duplicate detection and search indexing are not "
        "claimed; the fields that would record their output are present."
    ),
    entities=(
        single("archive", "DwArchive", "Archive", (
            f("reportSaved", "Final report saved", BOOL, B, required=True),
            f("mediaSaved", "Media saved", BOOL, B, required=True),
            f("submittedToDatabase", "Submitted to the database", BOOL, S),
            f("submissionDate", "Submission date", DATE, S),
            f("archiveLocation", "Archive location", T, S, max_length=220),
            f("retentionClass", "Retention class", ENUM, S, enum="RETENTION_CLASS"),
            f("rawFilesPreserved", "Raw files preserved", BOOL, S),
            f("checksumManifest", "Checksum manifest", FILE, A),
            f("notes", "Notes", RICH, S, report_role=NARR),
        )),
        many("mediaQualityFlag", "DwMediaQualityFlag", "Media quality flags", (
            f("mediaId", "File", T, B, required=True, report_role=COL, column_width_pct=26.0),
            f("flag", "Problem", ENUM, B, required=True, enum="MEDIA_QUALITY_FLAG",
              report_role=COL, column_width_pct=20.0),
            f("severity", "Severity", ENUM, S, enum="SEVERITY", report_role=COL,
              column_width_pct=14.0),
            f("autoDetected", "Detected automatically", BOOL, S, report_role=COL,
              column_width_pct=16.0),
            f("resolved", "Resolved", BOOL, S, report_role=COL, column_width_pct=12.0),
            f("note", "Note", LT, S, report_role=COL, column_width_pct=12.0),
        ), label_field="mediaId"),
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
        single("followUpSummary", "DwFollowUpSummary", "Follow-up summary", (
            f("continuedProductionNote", "Note on continued production", RICH, B,
              report_role=NARR),
            f("additionalNeeds", "Additional support needed", RICH, S, report_role=NARR),
        )),
        many("followUp", "DwFollowUp", "Follow-up records", (
            f("productRef", "Product", REF, B, required=True, ref_model="DwFinalProduct",
              report_role=COL, column_width_pct=20.0),
            f("interval", "Interval", ENUM, B, required=True, enum="FOLLOWUP_INTERVAL",
              report_role=COL, column_width_pct=12.0),
            f("followUpDate", "Date", DATE, B, required=True, report_role=COL,
              column_width_pct=14.0),
            f("adoptionStatus", "Adoption", ENUM, B, required=True, enum="ADOPTION_STATUS",
              report_role=COL, column_width_pct=20.0),
            f("unitsProduced", "Units produced", INT, S, min_value=0, report_role=COL,
              column_width_pct=12.0),
            f("unitsSold", "Units sold", INT, S, min_value=0, report_role=COL,
              column_width_pct=10.0),
            f("revenue", "Revenue", MONEY, S, unit="INR", min_value=0, report_role=COL,
              column_width_pct=12.0),
            f("ordersReceived", "Orders received", INT, S, min_value=0),
            f("modifications", "Modifications made", RICH, S, report_role=NARR),
            f("supportNeeded", "Support needed", RICH, S, report_role=NARR),
            f("recordedBy", "Recorded by", T, S),
            *photos("evidencePhotos", "Evidence photographs", S, "Photograph caption"),
        ), label_field="productRef"),
    ),
)


ALL_STAGES: tuple[StageSpec, ...] = (
    STAGE_1, STAGE_2, STAGE_3, STAGE_4, STAGE_5, STAGE_6, STAGE_7, STAGE_8,
    STAGE_9, STAGE_10, STAGE_11, STAGE_12, STAGE_13, STAGE_14, STAGE_15, STAGE_16,
    STAGE_17, STAGE_18, STAGE_19, STAGE_20, STAGE_21, STAGE_22,
)

_install(ALL_STAGES)
