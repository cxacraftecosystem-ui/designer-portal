"""Database work for the design-workshop API: loading, saving stages, and rendering reports.

The routes stay thin; everything that touches Prisma or composes the report pipeline lives
here, matching the repository's split between ``api/routes`` and ``services``.

Three things in this module are worth reading before changing it.

**Saving a stage is a diff, not a truncate-and-insert.** The obvious implementation — delete
every row of the entity, then insert what arrived — loses the row ids, which are what a photo's
caption and a prototype's iteration history point at, and it burns a new id every time a
designer fixes a typo. :func:`save_stage` matches incoming entries to existing rows by
``entryId`` and then by ``clientKey``, updates those, inserts what is new, and soft-deletes only
what the client actually removed.

**``clientKey`` is what makes an offline sync idempotent.** The phone creates a row in a village
with no signal and gives it a UUID. Two days later it syncs. Without a stable client-side key
the server has no way to tell "this is the row you already have" from "this is a new row", and
every reconnect duplicates the whole collection — which is the single most common way an
offline-first app corrupts its own data.

**Media is resolved in one pass, not per photo.** A 26-page report references forty images
across twenty stages; resolving each one as the builder reaches it would be forty awaits inside
a synchronous render. Every media id in the record is collected first, looked up in one query,
and handed to the renderer as a plain dict lookup.

**References are SELECTED and then COPIED.** A designer does not retype an artisan who is
already in the database: :func:`reference_options` serves the picker, and :func:`hydrate_entries`
copies the chosen record's display fields onto the stage entry as it is saved. The two halves
are one feature and neither works alone — see the long note above ``REFERENCE_HYDRATION``.
"""

import logging
import re
from collections.abc import Awaitable, Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field as dataclass_field, replace
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status
# The driver's own name for "a unique index refused this INSERT". Imported for the singleton race
# below rather than matched on a message: `save_stage` recovers from exactly this and re-raises
# everything else, and a string match would either miss a driver upgrade's rewording or swallow an
# unrelated failure as if the designer's work had been stored.
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import is_admin
from app.services import custom_sections, dictation_consent, entry_provenance, rich_text
from app.services.address import DISTRICTS_BY_STATE
from app.services.concurrency import gather_reads
from app.services.design_workshop_access import add_one_viewer
from app.services.design_workshop_viewers import (
    _assert_every_id_may_be_granted,
    has_viewer_grant,
)
from app.services.designers import prefill_from_profile
from app.services.report_annexures import annexure_warnings, attach_transcripts
from app.services.report_builder import ReferencedRecord, WorkshopData, build_report
from app.services.report_custom_sections import (
    CustomReportField,
    CustomSectionItem,
    attach_custom_sections,
    custom_sections_of,
)
from app.services.report_docx import render_docx
from app.services.report_model import ImageRef, PageSize, ReportMeta
from app.services.report_pdf import render_pdf
from app.services.report_questionnaires import attach_questionnaires, questionnaire_warnings
# The ONE masking rule this application has, imported rather than reimplemented. See the note on
# `pehchanCardNumber` in the Artisan reference model for why a card number crossing into a stage
# entry has to go through it, and `record_fields.py:270-283` for the defect that settled it.
#
# `viewable_where` rides in on the same import for a different reason: `reference_options` composes
# it so the picker's by-id lookup asks "may this account read this row" with the SAME predicate the
# record list routes ask it with. It is empty today; the point is that it cannot drift.
#
# `contains` rides in for a third: the REF picker's search box was the fifth and last place in this
# repository that composed `{"contains": …, "mode": "insensitive"}` by hand, so neither the C0-byte
# strip nor the LIKE escape ran for it — a designer who typed `_` into the picker got every row of
# the model back, and a pasted NUL was a 500. `test_record_filters.test_no_route_still_hand_rolls_a
# _contains_filter` is the sweep that now holds all six of them to the funnel.
from app.services.records import (
    contains,
    derive_age,
    derive_experience_years,
    mask_identity_number,
    viewable_where,
)
# THE MEASUREMENT-METHOD VOCABULARY, IMPORTED AND NOT RESTATED. `METHOD_CLAUSES` is the two phrases
# the record sheet, every .xlsx sheet and both CSV exports already print for a machine-produced
# dimension; `field_method` is the one reader of the stamp `records.merge_field_provenance` writes.
# `_measurement_method_note` builds the workshop's sentence out of both, because a second spelling of
# "vision model estimate" is how the record sheet and the workshop report come to describe one stamp
# in two vocabularies — which that dict's own comment calls a requirement rather than a nicety.
from app.services.record_fields import METHOD_CLAUSES, field_method
from app.services.report_templates import apply_report_settings, template as get_template
from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent
from app.services.stage_schema import (
    PROMOTED_COLUMNS,
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    REF_SCOPES,
    # THE HYDRATION TABLE MOVED INTO THE REGISTRY and is re-exported here under the name it has
    # always had, because that is where every reader of this feature — the report builder's own
    # docstring, the research note, three test modules — has been told to look. It moved so that
    # `validate_registry` can refuse a mapping whose target field does not exist and
    # `field_to_dict` can publish the mapping to the clients; the note above its declaration says
    # why both matter. Nothing about WHEN it is applied changed: `hydrate_entries` below is still
    # the only thing that writes it, and still writes it only at save time.
    REFERENCE_HYDRATION,
    Cardinality,
    EntitySpec,
    FieldType,
    StageSpec,
    all_entities,
    coerce_value,
    promoted_values,
    registry_version,
    stage_completeness,
    stages,
    validate_entry,
)
from app.services.workshop_transcripts import (
    audio_references,
    enqueue_stage_transcriptions,
    load_transcript_items,
    wants_transcripts,
)

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------------------
# Loading
# --------------------------------------------------------------------------------------


async def load_workshop_or_404(workshop_id: str, user: Any, *, for_edit: bool = False) -> Any:
    """Fetch a workshop the caller may see, or raise.

    A soft-deleted workshop is a 404 to READ for everyone but an admin, who needs to be able to
    find it in order to restore it. To EDIT (``for_edit=True``) it is a 409 for anybody the
    grants above admit — which is the whole point of the ordering below, and is what makes the
    one sentence a designer can act on reachable. Returning 403 instead of 404 for a workshop the
    caller may not see would confirm that the id exists, which for a research data set keyed by
    cuid is a small but free leak.

    THREE WAYS IN, not two. The creator, an admin, and — since the workshop stopped being a
    one-person record — anybody an admin has given a ``DesignWorkshopViewer`` row. A real Design &
    Prototype Development Workshop is run by two designers alongside a master craftsperson and a
    reviewing officer; before the grant existed, this function told every one of them but the
    creator that a fortnight of their own fieldwork did not exist, and a designer leaving mid-season
    took the record with them.

    The grant is checked LAST and only when the two cheap comparisons have both failed, so the
    ordinary read — a designer opening their own workshop — costs exactly what it did before.

    What a grant buys stops here, at the LOAD: read, and the stage writes that go through this same
    helper. It is not delete and it is not re-granting, both of which are gated separately and
    deliberately were not widened — see ``app/services/design_workshop_viewers.py``.
    """
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    admin = is_admin(user)
    if (
        record.createdById != user.id
        and not admin
        and not await has_viewer_grant(workshop_id, user.id)
    ):
        # Still 404 and still the same detail string, deliberately. Widening WHO may enter must not
        # change what a stranger is told: a 403 here would confirm the id exists to exactly the
        # people the clause is turning away.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    # WHO-MAY-ENTER IS ANSWERED BEFORE IS-IT-DELETED, AND THAT ORDER IS THE FIX RATHER THAN THE
    # STYLE. The deleted check used to run FIRST, so a non-admin got 404 before the `for_edit`
    # 409 below could ever be reached — and the only accounts that reach this helper with
    # `for_edit=True` are designers, who are not admins. The 409 was therefore dead code for
    # every caller who could hit the condition.
    #
    # What that cost is on the client. `frontend/lib/designWorkshopStore.ts` rethrows 409 out of
    # the stage arm precisely so the workshop-level catch can print the one correct sentence
    # ("Ask an admin to restore it, then sync again"); a 404 is not rethrown, so an admin
    # soft-deleting a duplicate while a designer's laptop held unsent stages stamped EVERY one of
    # them permanent with "it will keep being refused until the answer that caused it is
    # corrected — this is not a connection problem". One red line per stage, sending the designer
    # to audit answers that nothing had objected to, and the sentence that would have told them
    # what actually happened was unreachable.
    #
    # NOTHING IS DISCLOSED BY THE SWAP. The 409 is only reachable once the caller has already
    # proved they are the creator, an admin, or the holder of a `DesignWorkshopViewer` grant —
    # people who may see that this workshop exists. A stranger still gets the same 404 with the
    # same detail string, and a REVOKED grantee gets it too, because the grant check above fails
    # for them before this line. It costs one extra `has_viewer_grant` round trip in the deleted
    # case only, which is a case nobody is in twice.
    #
    # THE READ PATH IS UNCHANGED: `for_edit` is false there, so a deleted workshop still answers
    # 404 to everyone but an admin, who needs to be able to find it in order to restore it.
    if record.deletedAt is not None:
        # No second authorisation test here: reaching this line already means creator, admin or
        # grantee, because the clause above turned everybody else away with a 404.
        if for_edit:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This workshop is deleted. Restore it before editing.",
            )
        if not admin:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Record not found"
            )
    return record


async def entry_rows(workshop_id: str, *, stage_key: str | None = None) -> list[Any]:
    where: dict[str, Any] = {"designWorkshopId": workshop_id, "deletedAt": None}
    if stage_key:
        where["stageKey"] = stage_key
    return await db.dwstageentry.find_many(where=where, order={"ordinal": "asc"})


def workshop_summary(record: Any) -> dict[str, Any]:
    """The workshop header as the clients read it.

    A HAND-WRITTEN DICT AND NOT A DUMP OF THE ROW, which is why a new column reaches a client only by
    being named here. That is deliberate — the row carries columns no client has any business reading —
    and it is also the trap: a column added to ``schema.prisma`` and not to this dict is invisible on
    every surface, and looks from the outside exactly like a column that is never written.
    """
    return {
        "id": record.id,
        "title": record.title,
        "templateId": record.templateId,
        "status": record.status,
        "workshopCode": record.workshopCode,
        "scheme": record.scheme,
        "craftName": record.craftName,
        "clusterName": record.clusterName,
        "state": record.state,
        "district": record.district,
        "venue": record.venue,
        "startDate": record.startDate.date().isoformat() if record.startDate else None,
        "endDate": record.endDate.date().isoformat() if record.endDate else None,
        "designerName": record.designerName,
        "implementingAgency": record.implementingAgency,
        "sponsor": record.sponsor,
        "notes": record.notes,
        "workshopId": record.workshopId,
        "createdById": record.createdById,
        "createdAt": record.createdAt.isoformat() if record.createdAt else None,
        "updatedAt": record.updatedAt.isoformat() if record.updatedAt else None,
        "deletedAt": record.deletedAt.isoformat() if record.deletedAt else None,
        # Tier 3 consent: may this workshop's recordings leave the device? Three keys — the answer, the
        # moment the ARTISAN gave it, and who took it down. The acceptor's display NAME is deliberately
        # not here: this dict is serialised once per row by the paged list, and resolving a name would
        # put a query per workshop into it to print something the list does not show. The single-record
        # read adds `dictationConsentByName`. See services/dictation_consent.py.
        **dictation_consent.consent_keys(record),
    }


# --------------------------------------------------------------------------------------
# References: the pickers, and the copy that outlives what they point at
# --------------------------------------------------------------------------------------

# How many options one picker call may return, and the ceiling a client may ask for. A mature
# cluster has several hundred documented artisans and more products than that, and serving the
# lot would send a megabyte of JSON to a phone on one bar of signal so a designer could scroll
# past four hundred names. The picker is a SEARCH box, not a list: `search` narrows, and
# `truncated` tells the client to say "keep typing" rather than to present a partial list as if
# it were the whole one.
REFERENCE_LIMIT_DEFAULT = 50
REFERENCE_LIMIT_MAX = 200


def _meta(row: Any) -> dict[str, Any]:
    value = getattr(row, "extraMetadata", None)
    return value if isinstance(value, dict) else {}


def _meta_value(meta: dict[str, Any], *keys: str) -> Any:
    """The first of several spellings a researcher's metadata may have used.

    Age and years of experience were collected as free metadata long before this registry
    existed, under whichever key the form of the day happened to use. Reading one spelling would
    blank the field for every record entered under another, and a blank experience column in a
    participant table reads as "we did not ask" rather than as "we looked in the wrong place".
    """
    for key in keys:
        value = meta.get(key)
        if value not in (None, ""):
            return value
    return None


def _rel(row: Any, relation: str, attribute: str) -> Any:
    """An attribute of an included relation, or None when the relation was not loaded."""
    related = getattr(row, relation, None)
    return getattr(related, attribute, None) if related is not None else None


def _rel_obj(row: Any, relation: str) -> Any:
    """An included relation itself, or None when it was not loaded."""
    return getattr(row, relation, None)


def _subject_point(location: Any) -> dict[str, float] | None:
    """The STATED pin a researcher dropped on the subject's own place, as a registry GEO value.

    `subjectLatitude`/`subjectLongitude` and NEVER `latitude`/`longitude`. The `Location` model's
    docstring is the argument in full and it is worth not re-deriving: every artisan on the live
    database that carries a location sits within two thousandths of a degree of one point in
    Kharagpur, while the places their researchers typed are Bagru, Kutch, Rudraprayag and
    Sanganer. Those coordinates are real, they jitter, they carry honest accuracy values — and
    they are fixes of THE DESK THE RECORD WAS TYPED AT. Copying them into a workshop entry would
    put a map pin on a desk in West Bengal for an artisan in Rajasthan, and the report's map
    section is one of the few things in the document a reader trusts without checking.

    No `accuracy` key: a hand-dropped pin has no error bar, and `coerce_value` treats the key as
    optional precisely so that "somebody pointed at this" and "a device measured this" stay
    distinguishable. Returns None unless BOTH halves are present — half a coordinate is not one.
    """
    if location is None:
        return None
    lat = getattr(location, "subjectLatitude", None)
    lon = getattr(location, "subjectLongitude", None)
    if lat is None or lon is None:
        return None
    try:
        return {"lat": float(lat), "lon": float(lon)}
    except (TypeError, ValueError):
        return None


#: What the pieces of one step's own text are joined with once its interior breaks are flattened
#: out of it. A middot and not a comma or a semicolon: a comma reads as part of the sentence, and a
#: semicolon is one of the two characters the bullet renderer splits on, so joining with one would
#: put the break straight back in.
_LINE_JOIN = " · "


def _one_line(value: Any) -> str:
    """One free-text answer as a SINGLE printed line, with the renderer's split characters gone.

    THE BULLET RENDERER SPLITS ON NEWLINES AND ON SEMICOLONS. `report_builder`'s pre-promotion
    path for a `report_role=BULLETS` LONG_TEXT replaces every semicolon with a newline and then
    splits on newlines, one bullet per piece, and the handset's `ReportScreen` is a port of that
    same line. So anything embedded INSIDE one of `_step_lines`' numbered lines that carries either
    character stops being part of that line and becomes a bullet of its own — unnumbered, and
    reading to a ministry officer as one more step in the sequence.

    That is not hypothetical, and it is not the semicolon that caused it. `ProcessStep.notes` is
    written by `MultiNoteInput` on the web form and by its namesake on the handset, and BOTH join
    a researcher's several notes with a blank line; the handset splits the column back apart on
    blank lines to edit it. So a step carrying two notes printed as two bullets — "1. Tying — Use
    cotton thread" and then a bare "Knots must be tight" — and three documented steps printed as
    five. `DocumentBuilder.bullets` drops the empty item between them, so not even a gap showed.

    Nothing is dropped or truncated: the split characters are turned INTO the separator the reader
    was going to see anyway, and the researcher's second note still prints, on the line of the step
    it belongs to.
    """
    parts = [part.strip() for part in re.split(r"[\n;]+", str(value or ""))]
    return _LINE_JOIN.join(part for part in parts if part)


def _step_lines(process: Any) -> str | None:
    """A documented process's own ordered sub-steps, as one newline-separated bulleted list.

    WHY THE SUB-STEPS LAND ON THE SINGLETON AND NOT ON A ROW. `REFERENCE_HYDRATION`'s note above
    `processStep.processRef` refuses to copy `steps` onto a process-step row, and it is right:
    `processStep` IS the workshop's own ordered list of steps, so putting a whole sequence inside
    one of them would print the sequence again on every row that names the same process. That note
    ends by saying the omission is a decision — and the decision left the source's sub-steps
    reaching nothing at all, which is the gap this function closes from the other side. Stage 5's
    `traditionalProcess` singleton is one-per-workshop, so the sequence prints ONCE, above the
    steps table, which is where a reader wants it.

    All four `ProcessStep` columns travel, one line each: `sortOrder` becomes the number (the list
    is ordered by it, so the number is the POSITION and never an id a reader might mistake for
    one), `name` is the line, `stepType == GROUP` is marked because a group is not a step in the
    sequence but a bracket around several of them, and `notes` follows an em dash.

    ONE NEWLINE-SEPARATED STRING AND NOT A TAGS LIST, which was the first shape and the wrong one.
    A TAGS field reaches `report_builder.format_value` as `", ".join(...)`, so the whole documented
    sequence would have printed as a single run-on line — "1. Tying, 2. Dyeing, 3. Washing" — under
    a heading that promises a list. A LONG_TEXT with `report_role=BULLETS` goes down the renderer's
    pre-promotion path, which splits on newlines and prints one bullet per step.

    AND THAT SPLIT IS WHY EVERY PIECE OF A LINE GOES THROUGH `_one_line` FIRST. This note used to
    end by saying the renderer also splits on semicolons, that a step note containing one therefore
    breaks into two bullets, and that this "is the renderer's documented behaviour for every BULLETS
    field here and is not worth a special case". The behaviour was described correctly and the cost
    was mis-measured, because only the semicolon was considered. The character that actually appears
    in this column is the NEWLINE: `MultiNoteInput` joins a researcher's several notes with a blank
    line on both clients, so a step with two notes printed as an extra unnumbered bullet that a
    reader counts as a step. One documented step is now one bullet whatever its note contains.
    """
    steps = getattr(process, "steps", None) or []
    ordered = sorted(steps, key=lambda s: (getattr(s, "sortOrder", 0) or 0,
                                           str(getattr(s, "name", "") or "")))
    lines: list[str] = []
    for index, step in enumerate(ordered, start=1):
        # The NAME goes through `_one_line` as well as the note, and not out of symmetry: it is
        # free text from the same form, and once the two are one string the renderer cannot tell
        # which half a stray newline came from.
        name = _one_line(getattr(step, "name", None))
        if not name:
            continue
        line = f"{index}. {name}"
        if _enum_token(getattr(step, "stepType", None)) == "GROUP":
            line += " (group)"
        note = _one_line(getattr(step, "notes", None))
        if note:
            line = f"{line} — {note}"
        lines.append(line)
    return "\n".join(lines) or None


def _linked_artisan_names(tool: Any) -> str | None:
    """Every artisan a tool is assigned to, one per line, or ``None`` when it is assigned to none.

    `ToolDocumentation.artisanName` is ONE denormalised string — whoever the record was first
    documented against — while `ToolArtisan` is the real many-to-many that `ToolAssignmentSection`
    on the tool page exists to populate and `GET /tools/{id}/artisans` serves. A pit loom assigned
    to nine weavers therefore reached a workshop as one name, with the other eight unreachable from
    the report. "Documented for" and "used by" are different questions and the tool row already has
    a box for the first.

    ORDERED BY NAME, and deliberately not by the link's own `createdAt`: the printed list is read as
    a roster rather than as a history of who was added when, and a stable alphabetical order means a
    regenerated report does not reshuffle its bullets because somebody assigned a tenth weaver.

    DE-DUPLICATED, because the link table permits the same artisan twice through two different
    routes and a report that names somebody twice reads as a data-entry error.
    """
    links = getattr(tool, "artisanLinks", None) or []
    names: list[str] = []
    for link in links:
        artisan = getattr(link, "artisan", None)
        name = str(getattr(artisan, "name", "") or "").strip()
        if name and name not in names:
            names.append(name)
    return "\n".join(sorted(names)) or None


# ── THE FIVE THINGS A QUESTIONNAIRE SITTING CAN SAY ABOUT ITSELF WITHOUT QUOTING ANYBODY ──────
#
# READ THIS BEFORE ADDING A SIXTH. `QuestionnaireResponse.answerText` and `.notes` are free text
# about a NAMED person, a design workshop's stage reads do NOT pass through
# `records._redact_sensitive`, a `DesignWorkshopViewer` is a grantee, and a hydrated value is a
# PERMANENT copy the report never re-resolves. On top of that the schema cannot say WHO in a
# six-person sitting said any given sentence (`QuestionnaireInterviewArtisan` is a many-to-many and
# `questionnaire_consolidation` refuses to guess), and `QuestionnaireQuestion.supersededById` exists
# because a prompt is REWORDED under answers already given — "How many looms?" answered "12",
# reworded to "How many weavers?", and a ministry report now states there are twelve weavers.
#
# So these five functions COUNT and never quote. Each is total in its keys the way every data lambda
# in this file is: `_ProbeRow.__getattr__` answers None for every attribute, so each takes `... or []`
# and `_interview_last_answered` guards the empty `max()`. Five separate in-memory walks over lists
# Prisma has already loaded, matching the one-helper-per-key precedent (`_media_note`, `_step_lines`,
# `_linked_artisan_names`) — a tuple-returning helper cannot be unpacked inside a lambda.
#
# ZERO IS RETURNED RATHER THAN None, and it is a statement rather than a gap. An interview with
# nothing answered is a sitting that produced no evidence, which is exactly what a citation should
# say; and `hydrate_entries` writes 0 (`value in (None, "")` is False for it) so the box prints it.
# This is the `age`/`experienceYears` rule — zero is a real value and `or` would lose it — and it is
# the opposite of `_interview_questions_answered`'s own non-blank test one function down, where `""`
# is a saved row with nothing in it.
def _interview_artisan_count(interview: Any) -> int:
    """How many artisans sat in one questionnaire interview.

    THE COUNT AND NEVER THE NAMES, and this is deliberately NOT symmetrical with
    `_linked_artisan_names` above. A tool assignment is an administrative fact; who sat in a room
    together is a social one. Decisively, a sitting may cover artisans who are NOT on this
    workshop's roster — `record_filters.artisan_workshop_clause` treats "sat in an interview taken
    at this workshop" as one of the three ways an artisan reaches a workshop, so the set is wider
    than the roster by construction — and naming them would have a submitted report disclose that a
    particular person from another cluster was interviewed, on a page that has no business naming
    them.

    IT IS LOAD-BEARING AND NOT DECORATION. `questionnaire_consolidation`'s opening argument is that
    "a quote from a five-person sitting is different evidence from the same sentence said alone, and
    a view that flattens the two is not citable". This number is the part of that distinction a
    report can carry.
    """
    return len(getattr(interview, "artisans", None) or [])


def _interview_artisan_phrase(interview: Any) -> str | None:
    """"6 artisans" for the picker's sublabel, or None for a sitting with none recorded.

    None rather than "0 artisans": the sublabel is a `_joined` list of identifying facts and a zero
    there reads as a defect in the picker rather than as a fact about the sitting. The DATA key uses
    the number, where a 0 is an answer; a sublabel is a label.
    """
    total = _interview_artisan_count(interview)
    if not total:
        return None
    return f"{total} artisan" if total == 1 else f"{total} artisans"


def _norm_section_code(value: Any) -> str:
    """A section code normalised the way ``questionnaire._norm_code`` normalises it.

    Uppercase, alphanumerics only, and it must STAY that spelling: it is what makes a clip filename's
    leading token comparable with a `QuestionnaireQuestion.sectionCode`, and the route this function
    is written to agree with normalises both ends the same way. Spelled out here rather than imported
    because that helper lives in an API route module a service must not import from.
    """
    return "".join(ch for ch in str(value or "") if ch.isalnum()).upper()


# The app writes a questionnaire recording as
# `SECTION_QUESTION_INTERVIEWNAME_DURATIONHHMMSS_DATETIMEDDMMYYYYHHMM` — see
# `questionnaireClipBaseName` in MainActivity.kt, which uppercases and strips every token and joins
# exactly five of them. The DURATION slot is the discriminator: six digits in the fourth position is
# a shape an uploaded photograph ("IMG_2031.jpg") cannot accidentally satisfy, and it survives the
# repository's filename sanitiser truncating the trailing stamp.
_CLIP_DURATION_SLOT = 3
_CLIP_MIN_TOKENS = 5
# The sentinel that token builder substitutes when it has NO section (and, for a whole-section
# recording, in the QUESTION slot). It is an admission of ignorance rather than a code, and counting
# it would add a phantom section to every sitting whose clips were recorded without one.
_CLIP_UNKNOWN_TOKEN = "SEC"


def _interview_clip_section_code(row: Any) -> str:
    """The section code an app-recorded questionnaire clip names in its filename, or ``""``."""
    name = str(getattr(row, "originalFilename", "") or "")
    tail = name.rsplit("/", 1)[-1]
    stem = name[: len(name) - len(tail.rsplit(".", 1)[-1]) - 1] if "." in tail else name
    parts = stem.split("_")
    if len(parts) < _CLIP_MIN_TOKENS:
        return ""
    duration = parts[_CLIP_DURATION_SLOT]
    if len(duration) != 6 or not duration.isdigit():
        return ""
    code = _norm_section_code(parts[0])
    return "" if code == _CLIP_UNKNOWN_TOKEN else code


def _interview_sections_covered(interview: Any) -> int | None:
    """How many distinct sections of the instrument the sitting has content recorded in.

    WRITTEN TO THE REPOSITORY'S OWN DEFINITION OF A COVERED SECTION, WHICH IT USED TO CONTRADICT
    TWICE OVER. ``questionnaire._derived_completed_sections`` — the function behind the per-artisan
    and per-workshop View Data matrix — counts a section when it has a NON-EMPTY response, or media
    tagged with that section's question or code, or an audio clip whose filename leads with the
    section code. This counted DISTINCT `sectionCode` over response rows, answered or blank, and both
    errors that produced had a reader:

      * A BLANK ROW COUNTED. The app writes one when a researcher opens a section, tabs through it
        and saves, so the printed pair "Sections answered: 9 / Questions answered: 0" was reachable —
        `_interview_questions_answered` one function down has always required a non-blank answer, and
        two boxes side by side in one `KeyValueBlock` disagreeing about whether anything was said is
        worse than either number alone.
      * THE APP'S PRIMARY CAPTURE MODE COUNTED AS ZERO. An interview captured as one AUDIO CLIP PER
        SECTION has its section signal in the clip FILENAME and may have no response rows at all, so
        the report printed "Sections answered: 0" for a sitting the View Data matrix showed as
        covering nine — a number in a ministry document contradicting the screen it was checked
        against.

    ── THREE ARMS, AND WHY THE FOURTH CANNOT BE HERE ────────────────────────────────────────────
    `entry_provenance.canonical_divergence` re-resolves a stamped field by calling
    `spec.data(rec, photo)` with exactly two arguments and re-fetching with `spec.include`, so
    everything this reads must hang off the interview row. That admits:

      1. `responses` -> `question.sectionCode`, non-blank answers only. The DENORMALISED column,
         which is why the include stops at `question` and does not nest `section`.
      2. `media` -> `extraMetadata.sectionCode`, and `extraMetadata.questionId` resolved through the
         questions THIS ROW's own responses carry.
      3. `media` -> the clip filename, via `_interview_clip_section_code`.

    The route has a fourth: `section_codes_from_title`, the best-effort scan of the interview TITLE
    that is the only signal for recordings made before the filename nomenclature existed. It needs
    the set of REAL section codes to reject unrelated words, `QuestionnaireSection` is not reachable
    from this row, and a title scan with no valid-code filter would read "Rudraprayag G,H,I" as
    whatever its letters happen to be. So it is refused rather than approximated, and this count can
    therefore READ LOW against the matrix on pre-nomenclature sittings. That is the safe direction:
    the arm the route itself calls best-effort is the one left out.

    Arm 3 also differs from the route in KIND and not only in reach: the route validates the leading
    token against the real code list, and this validates the FILENAME SHAPE instead (see
    `_interview_clip_section_code`), because the code list is not reachable either. So a clip naming
    a section that has since been deleted counts here and not there.

    ``None`` RATHER THAN AN UNDERSTATEMENT, and only for one narrow case: a media row tagged with a
    `questionId` whose question has no response row on this interview. Nothing on this row can say
    which section that question belongs to, and the box prints a bare number a reader will take as
    the whole truth — so rather than a floor dressed as a count, the field hydrates nothing and
    prints nothing. `hydrate_entries` skips a `None`, and `_has_value`/`isFilled` leave the box
    blank, which is the one honest answer available.

    NO ARCHIVE TO FALSE-FLAG. Changing what a data lambda produces reports every already-stamped
    entry as `diverged` for ever — the failure `_media_note`'s docstring records — and that is only
    survivable here because `QuestionnaireInterview` is new in this same change and no entry carries
    a `refModel="QuestionnaireInterview"` stamp yet. It will not be survivable a second time.
    """
    covered: set[str] = set()
    section_by_question: dict[str, str] = {}
    for row in (getattr(interview, "responses", None) or []):
        question = getattr(row, "question", None)
        code = _norm_section_code(getattr(question, "sectionCode", None))
        if not code:
            continue
        # Filed under BOTH spellings of the join: the response row carries the question ID as a
        # column, the included question carries it as its own id, and the media rows below are
        # tagged with whichever the writing form had in hand.
        for candidate in (getattr(row, "questionId", None), getattr(question, "id", None)):
            if candidate:
                section_by_question[str(candidate)] = code
        if str(getattr(row, "answerText", "") or "").strip():
            covered.add(code)
    unresolved: set[str] = set()
    for row in (getattr(interview, "media", None) or []):
        meta = _meta(row)
        code = _norm_section_code(meta.get("sectionCode"))
        if not code:
            question_id = str(meta.get("questionId") or "")
            code = section_by_question.get(question_id, "")
            if not code and question_id:
                unresolved.add(question_id)
        if not code:
            code = _interview_clip_section_code(row)
        if code:
            covered.add(code)
    if unresolved:
        return None
    return len(covered)


def _interview_questions_answered(interview: Any) -> int:
    """How many questions the sitting actually answered.

    NON-BLANK RATHER THAN ``is not None``, AND THE DIFFERENCE IS THE POINT. An empty string is a
    saved response row with nothing in it — the interviewer opened the question and moved on — and
    counting it would overstate the evidence. Compare `age`/`experienceYears`, where zero is a real
    value and an `or` would lose it: there the falsy value is an answer, here it is the absence of
    one.

    NO DENOMINATOR, AND "84 of 112" IS REFUSED ON TWO INDEPENDENT GROUNDS. It is not reachable — the
    global instrument is not a relation on the interview, and `entry_provenance.canonical_divergence`
    calls `spec.data(rec, photo)` with exactly two arguments, so nothing can be injected for it to
    read. And it would be false if it were: both question tables carry `isActive`, `retiredAt` and
    `supersededById`, so a denominator frozen at save time silently means "of the 112 active on the
    day this was picked" while the report never re-resolves. A frozen ratio against a live, editable
    instrument becomes wrong without anybody touching the report.
    """
    return sum(
        1 for r in (getattr(interview, "responses", None) or [])
        if str(getattr(r, "answerText", "") or "").strip()
    )


def _interview_last_answered(interview: Any) -> str | None:
    """The ISO date the sitting was last ANSWERED, or None when nothing has been.

    NON-BLANK ROWS ONLY, AND THE FIELD IS CALLED "Last answered on". This used to max `updatedAt`
    over EVERY response row, which made it "last TOUCHED": `answerText` is nullable, the app writes a
    blank row when a researcher opens a section and tabs through it, and the route's own answered-set
    (`_answered_question_ids` in `questionnaire.py`) filters `(answerText or "").strip()` for exactly
    that reason. So "Last answered on: 14 Mar 2026" could print in the same `KeyValueBlock` as
    "Questions answered: 0" — the same non-blank/any-row split, and the same self-contradicting pair,
    that `_interview_sections_covered` above was carrying.

    The filter is spelled identically to `_interview_questions_answered`'s on purpose: the two boxes
    are the COUNT and the DATE of one set of rows, and a reader is entitled to assume the date
    belongs to the rows that were counted. As above, this is safe to change only because no entry
    carries a `QuestionnaireInterview` provenance stamp yet.

    THE EMPTY ``max()`` IS THE WHOLE REASON THIS IS A FUNCTION AND NOT AN EXPRESSION.
    `reference_data_keys` calls every data lambda with a `_ProbeRow`, whose `responses` is None, so
    an inline `max(...)` would raise `ValueError` at import of the test that asks the registry what
    it carries — and a lambda that is not total in its keys is the failure `validate_reference_carry`
    exists to catch, arriving as a crash instead of a report.
    """
    stamps = [
        getattr(r, "updatedAt", None)
        for r in (getattr(interview, "responses", None) or [])
        if str(getattr(r, "answerText", "") or "").strip()
    ]
    stamps = [s for s in stamps if s is not None]
    if not stamps:
        return None
    return _iso_date(max(stamps))


def _process_media_note(process: Any) -> str | None:
    """How much footage the process record carries, as a sentence, or ``None`` when it carries none.

    NOT A LIST OF MEDIA IDS, AND THE REASON IS TWO RULES AT ONCE. A stage entry's galleries hold the
    photographs the DESIGNER took at the workshop and hydration may never overwrite them; and a
    referenced record's files are entitlement-gated per file, which ``_reference_photos`` resolves
    for one image and no more. Copying ids onto the entry would either freeze ids the report is not
    entitled to fetch, or quietly bypass the gate that decides. So the carry is the FACT that the
    footage exists, which is what a reader of the printed process needs in order to ask for it.

    DORMANT SINCE 2026-08-23, AND IT RETURNS ``None`` FOR EVERY PROCESS. It counts off a ``media``
    relation, and ``Process``/``ProcessStep`` are the two reference models that have none —
    ``MediaFile`` reaches them only through ``linkedRecordType``/``linkedRecordId``. The include that
    used to claim otherwise made Prisma refuse the whole picker query; see the comment above
    ``REFERENCE_MODELS["Process"]``'s ``include`` for what it broke and what restoring the note
    costs. The ``getattr(..., None) or []`` reads below are what make that degradation safe rather
    than another exception, so keep them even though the attribute cannot currently exist.
    """
    own = len(getattr(process, "media", None) or [])
    steps = getattr(process, "steps", None) or []
    per_step = sum(len(getattr(step, "media", None) or []) for step in steps)
    if not own and not per_step:
        return None
    parts: list[str] = []
    if own:
        parts.append(f"{own} on the process itself")
    if per_step:
        covered = sum(1 for step in steps if getattr(step, "media", None))
        parts.append(f"{per_step} across {covered} step(s)")
    return "Attached to the process record: " + ", ".join(parts) + "."


#: What one attached file is counted as, in the order :func:`_media_note` names them.
#:
#: PDF and DOCUMENT collapse onto one word because the difference is a mime type and not a fact a
#: reader of the printed report can act on — "2 documents" is what they would ask the researcher
#: for either way. ``OTHER`` keeps a word of its own ("file") rather than being folded into
#: documents, because it is the bucket ``media.py`` puts anything it could not classify in, and
#: calling an unclassified upload a document is a claim about it.
#:
#: EVERY MEMBER OF THE PRISMA ``MediaType`` ENUM APPEARS HERE, and ``_media_note`` counts any token
#: it does not know into the ``OTHER`` word rather than dropping it: a member added to the enum must
#: not silently stop being counted, because the symptom is a sentence that says a record carries
#: three files when it carries five, and nothing anywhere would contradict it.
_MEDIA_NOTE_WORDS: tuple[tuple[tuple[str, ...], str, str], ...] = (
    (("IMAGE",), "photograph", "photographs"),
    (("VIDEO",), "video", "videos"),
    (("AUDIO",), "audio note", "audio notes"),
    (("PDF", "DOCUMENT"), "document", "documents"),
    (("OTHER",), "file", "files"),
)


def _media_note(subject: str, rows: Any, *, numbered_prefix: str = "") -> str | None:
    """How much footage ONE record carries, as a sentence, or ``None`` when it carries none.

    ── WHAT THIS CLOSES ─────────────────────────────────────────────────────────────────────────
    ``_reference_photos`` resolves EXACTLY ONE image per record and no non-image row at all, and
    ``photo``/``photoCaption`` are the only media keys the artisan, product, tool and craft lambdas
    produce. So a researcher who recorded an artisan's spoken introduction — which
    ``MediaCaptureField``'s own description asks for by name — or filmed a tool being used, or
    documented a tool's making as a numbered nine-photograph sequence, produced material a designer
    standing in the room would want, and NOTHING on the workshop row could even say it existed. A
    reader of the printed roster or tool table could not know to ask for it.

    ── A SENTENCE AND NEVER THE IDS ─────────────────────────────────────────────────────────────
    The same two rules :func:`_process_media_note` sets out, which is the precedent this generalises:
    a stage entry's galleries hold the photographs the DESIGNER took at the workshop and hydration
    may never overwrite them; and a referenced record's files are entitlement-gated per file, which
    ``_reference_photos`` resolves for one image and no more. Copying ids would either freeze ids the
    report cannot fetch or bypass the gate that decides. The FACT that the footage exists is what a
    reader needs in order to ask for it, and it is the part that is safe to freeze.

    ── COUNTED OFF THE ``media`` RELATION, AND THE ALTERNATIVE WAS TRIED AND REFUSED ─────────────
    The cheaper shape is a grouped ``query_raw`` over ``MediaFile`` — one small result set instead of
    every media row of every row the picker returns — and it cannot be used, for two reasons that are
    both about a caller outside this module:

    * ``entry_provenance.canonical_divergence`` resolves a canonical value by calling
      ``spec.data(rec, photo)`` DIRECTLY, with two arguments. So the counts cannot be a third
      parameter of the lambda (that call would raise), and they cannot be injected by
      ``_reference_data`` either (that function is not on the divergence path).
    * A key the divergence path cannot recompute is reported to an admin as ``diverged`` on every
      audit, for ever. That view's own comment records what this already cost once — a photograph
      the canonical resolution did not load made "EVERY artisan with a photograph" read as diverged
      — and an audit that flags everything flags nothing.

    Counted off the relation, the note is recomputed identically wherever ``spec.data`` is called, so
    a divergence on it means what it says: the record has gained or lost footage since the pick.

    THE COST, STATED AND NOT HIDDEN: ``include={"media": True}`` loads every media row of every row
    the picker returns, bounded by ``REFERENCE_LIMIT_MAX``. It is ONE extra read for the whole page
    and it is indexed — the parent foreign keys on ``MediaFile`` exist for precisely this reverse
    walk, as that model's own schema comment says ("for the reverse walk `include: {media: true}`
    generates, which Prisma issues as a separate `WHERE "<fk>" IN (…)`") — but the ROWS are wide
    (``extraMetadata`` carries an EXIF summary), and a roster of forty long-documented artisans is
    the worst case in the repository. I could not measure it here: the compose stack is down, so
    there is no Postgres on this machine to time it against.

    A MEASUREMENT-GRID FRAME IS NOT FOOTAGE OF THE SUBJECT and is not counted. Same marker,
    same spelling, as the sort key in ``_reference_photos`` — a sheet of ruled paper photographed to
    fill a dimension box is not a picture of the tool, and counting it would overstate by one on
    exactly the records that were measured most carefully. Only the structural marker is read here,
    not that function's transitional caption/filename clauses: those exist to decide which single
    image WINS, and a caption a researcher could also have typed by hand is too weak a signal to
    subtract from a count.

    ── FOUR OF THE FIVE NOTES COME FROM HERE; THE PROCESS KEEPS ITS OWN FUNCTION ──────────────────
    ``_process_media_note`` above fills the fifth (``traditionalProcess.recordMediaNote``) and was
    NOT absorbed into this one, so a reader comparing two boxes a designer sees as the same box has
    the difference written down rather than having to diff two functions:

    * IT COUNTS A TOTAL, NOT A BREAKDOWN, because the question a process asks is *where* the footage
      is and not what type it is: "N on the process itself, N across N step(s)". A process's files are
      pre-process clips and per-step captures, and which step carries how many is the fact a reader
      needs in order to ask for one. Nothing else in the registry has that shape.
    * IT DOES NOT SKIP A MEASUREMENT-GRID FRAME, and today that asymmetry cannot show: the marker is
      written only from the product and tool record forms — ``ProductForm.tsx`` and ``ToolForm.tsx``
      on the web (each on both its online and its offline path), and ``GridMeasurementSection`` on the
      handset, whose value reaches the wire through ``Offline.kt`` and ``WorkshopRepository`` — and
      ``MediaFile`` has no ``processId`` at all, so a process's media arrive through
      ``linkedRecordType``/``linkedRecordId``. A skip there would be a branch nothing can reach,
      which is worse than the asymmetry: it would read as a guard against a live hazard. If a grid
      capture is ever offered on a process record, the skip lands there in the same change.

    NOT DELEGATED, and the reason is the one this file gives everywhere else about hydrated values.
    A stored note is a permanent COPY, and ``entry_provenance.canonical_divergence`` recomputes the
    canonical value by calling ``spec.data`` again — so changing the process's grammar would report
    EVERY process entry that carries a media note as diverged to an admin, for ever, which is the
    exact failure the divergence view's own comment records ("a photograph the canonical resolution
    did not load made 'EVERY artisan with a photograph' read as diverged"). Two grammars written down
    cost less than one migration nobody asked for.
    """
    counts: dict[str, int] = {}
    numbered = 0
    for row in rows or []:
        if str(_meta(row).get("purpose") or "") == MEASUREMENT_GRID_PURPOSE:
            continue
        token = _enum_token(getattr(row, "mediaType", None))
        counts[token] = counts.get(token, 0) + 1
        if numbered_prefix and str(getattr(row, "originalFilename", "") or "").startswith(
            numbered_prefix
        ):
            numbered += 1
    if not counts:
        return None
    known = {token for tokens, _one, _many in _MEDIA_NOTE_WORDS for token in tokens}
    parts: list[str] = []
    for tokens, one, many in _MEDIA_NOTE_WORDS:
        total = sum(counts.get(token, 0) for token in tokens)
        if "OTHER" in tokens:
            # Anything the enum has gained since this table was written, counted rather than lost.
            total += sum(n for token, n in counts.items() if token not in known)
        if total:
            parts.append(f"{total} {one if total == 1 else many}")
    note = f"Attached to the {subject} record: " + ", ".join(parts)
    if numbered:
        # The ordered making sequence, which is a different thing from a batch of photographs and is
        # the reason the tool record's media card exists twice. `ToolForm` renames every capture in
        # that card to `STAGE_STEP_<n>_<name>` on BOTH the online upload loop and the queued offline
        # array, so this reads the same on a handset that has never had a signal.
        verb = "documents" if numbered == 1 else "document"
        note += f", of which {numbered} {verb} the making in order"
    return note + "."


def _money(value: Any) -> str | None:
    """A Prisma Decimal as the two-place string a MONEY field is stored as.

    Money crosses this boundary as a string for the same reason it is stored as one: a float
    round trip turns 1250.10 into 1250.0999999999999, and a cost sheet that prints that has lost
    the argument before anyone reads the total.
    """
    if value is None:
        return None
    try:
        return f"{float(value):.2f}"
    except (TypeError, ValueError):
        return None


def _reference_data(spec: "ReferenceModel", row: Any, photo: Any) -> dict[str, Any]:
    """One record's display payload, with any stored FORMATTING flattened out of it.

    ── THE DEFECT THIS EXISTS FOR ───────────────────────────────────────────────────────────────
    Eight of the record columns these lambdas read accept RICH TEXT and store it, as JSON, inside
    the ``String?`` column that used to hold a paragraph — ``Artisan.notes``,
    ``ProductDocumentation.rawMaterialsUsed`` / ``mainToolsUsed`` / ``productFunctionUse`` /
    ``remarks``, ``ToolDocumentation.suggestionsForToolImprovement`` / ``remarks``, and
    ``Process.notes``. ``rich_text``'s own module banner says so, and ``records.prose_contains``
    exists because the search had to be taught the same thing. The storage decision was explicitly
    "no migration, no new column", so a row somebody formatted holds ``{"blocks":[…]}`` where prose
    used to be.

    Every hydration target opposite those columns USED TO BE a TEXT or LONG_TEXT box, and two still
    are — ``participant.recordNotes`` and ``processStep.description``. The other seven
    (``existingProduct.material`` / ``mainToolsUsed`` / ``use`` / ``remarks``, ``tool.improvements`` /
    ``remarks`` and ``traditionalProcess.documentedProcessNotes``) are declared RICH_TEXT now, so that
    the workshop offers the same editor the record page offers for the same fact — which changes
    nothing about the flattening below and is worth saying so nobody reads a promotion as a fix for
    this: the value still arrives here FLATTENED and ``coerce_value`` re-reads it as unformatted
    prose, so a numbered improvement list a researcher wrote is still one paragraph on the other side.
    Making the flattening target-type-aware — so the source's own structure could survive the carry —
    is a real and separate change, and the ``isinstance(value, str)`` guard at the bottom of this
    docstring must not be widened while doing it.

    For the two remaining plain-text targets ``coerce_value``'s text branch passes a
    string through ``clean_text`` unchanged. So the JSON was copied onto the stage entry verbatim,
    and ``format_value`` only unwraps a document for a RICH_TEXT field, where the value is a dict.
    ``{"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "Tied with cotton thread"…`` therefore
    printed **into a table column of a report submitted to a ministry** — three of those targets
    are TABLE_COLUMNs — and every emptiness check upstream read that JSON-shaped string as a
    filled field, so nothing anywhere reported a problem. ``report_builder.format_value`` carries
    this exact failure written out for the RICH_TEXT case it does guard; this is the same failure
    arriving through the door beside it.

    AND IT WAS PERMANENT. Hydration only ever fills blanks, so once the JSON was on the entry the
    designer could overtype it but nothing could ever un-answer it, and a submitted report never
    re-resolves a copied value by design.

    ── WHY HERE, AND NOT IN EACH LAMBDA ─────────────────────────────────────────────────────────
    ``spec.data`` is the ONE translation between a repository row and what a workshop entry stores
    — it is where ``_inches_to_cm``, ``_money`` and ``mask_identity_number`` already live, and both
    clients read its output rather than the row (see ``describeCreated`` in
    ``StageReferenceField.tsx`` and ``hydratedValues`` in ``DwReferenceField.kt``, whose comments
    each argue at length that a browser- or handset-side copy of this knowledge would drift). One
    wrapper here therefore fixes the picker, the save-time hydration, the web, the handset and both
    on-device report writers at once.

    Applying it to the WHOLE payload rather than to a named list of keys is deliberate: the named
    list is the thing that goes stale the next time a column is promoted to rich text, and that
    promotion touches neither this module nor any test that would notice. ``ToolDocumentation.
    processUsedIn`` is the live example — the form still renders it single-line, the review
    registry already marks it multiline, and ``tool.usedFor`` is a 26%-wide table column either
    way.

    ``rich_text.plain_from_stored`` is the documented read boundary for exactly this and is already
    what ``record_fields`` and ``data_browser`` call. **It is the identity on a plain string** — the
    same object back, not a round trip through ``from_plain``/``to_plain``, which would strip and
    re-wrap every note in the repository, a diff nobody asked for across data this app's users are
    the custodians of rather than the authors of.

    ── ``isinstance(value, str)`` IS LOAD-BEARING AND MUST NOT BE WIDENED ────────────────────────
    ``plain_from_stored`` treats a ``dict`` as a document it does not need to detect, and flattens
    it: ``to_plain({"lat": 20.29, "lon": 85.82})`` finds no ``blocks`` key, answers ``EMPTY``, and
    returns ``""``. ``participant.subjectLocation`` is exactly such a dict — the pin a researcher
    dropped on the artisan's own place, built by ``_subject_point`` — so calling the helper on
    every value regardless of type would silently BLANK it on every pick, which is the shape of
    failure this function was written to end rather than to commit. The formatted prose this is
    about only ever lives in ``String`` columns, so the guard costs nothing and the widening costs
    a field.
    """
    return {
        key: rich_text.plain_from_stored(value) if isinstance(value, str) else value
        for key, value in spec.data(row, photo).items()
    }


def _joined(*parts: Any) -> str:
    return " · ".join(str(p).strip() for p in parts if p not in (None, "") and str(p).strip())


def _decimal(value: Any) -> float | None:
    """A Prisma ``Decimal`` as the plain number a registry DECIMAL field stores.

    Unlike :func:`_money` this returns a float rather than a string, because DECIMAL is stored as a
    number and only MONEY has the two-place round-trip problem. Kept as its own named function so
    that a measurement never accidentally goes through ``_money`` and arrives in the report with a
    rupee sign in front of it.
    """
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _iso_date(value: Any) -> str | None:
    """A Prisma ``DateTime`` as the bare ISO date a registry ``DATE`` field stores.

    ``coerce_value`` reads a DATE as ``str(raw)[:10]``, so a naive copy of a ``datetime`` would
    already work by accident. Doing it here makes it deliberate: the value that crosses into the
    workshop is a DATE, the time of day is dropped on purpose (a report prints "documented on
    12 March 2025", never a timestamp), and nothing downstream has to know that the source column
    happened to carry one.
    """
    if value is None:
        return None
    text = str(value).strip()[:10]
    return text or None


#: 2.54 exactly — the inch has been DEFINED as 25.4 mm since the 1959 international yard and pound
#: agreement, so this is not an approximation and must never be "rounded to 2.5 for readability".
_CM_PER_INCH = 2.54


def _inches_to_cm(value: Any) -> float | None:
    """A source measurement in INCHES as the CENTIMETRES the workshop entity declares.

    THE UNITS ON THE TWO SIDES OF THIS CARRY DO NOT MATCH, AND NOTHING ELSE IN THIS FILE HAD EVER
    HAD TO NOTICE. ``ProductDocumentation.lengthInches`` / ``breadthInches`` / ``heightInches`` and
    ``ToolDocumentation.lengthInches`` / ``breadthInches`` are inches — the column names say so and
    the record forms label the boxes "Length (inches)". ``existingProduct.lengthCm`` and its
    neighbours declare ``unit="cm"``, which every client renders as the suffix beside the number
    and the report prints verbatim. A plain mapping pair would therefore have written 12 into a box
    labelled "cm" for a saree 30.48 cm long, and the document submitted to the ministry would have
    said 12 cm with nothing anywhere to suggest it was wrong.

    It is worse than an ordinary wrong value because of the hydration rule: ``hydrate_entries``
    only ever fills BLANKS, so once the wrong number is stored the designer can correct it but can
    never get back to "nobody measured this" — and a re-point clears and rewrites it with the same
    wrong conversion. There is no state from which the mistake self-heals.

    Rounded to two places to match the source columns, which are ``Decimal(10, 2)``: carrying
    30.479999999999997 would print that many digits in a table cell and read as false precision.
    """
    if value is None:
        return None
    try:
        return round(float(value) * _CM_PER_INCH, 2)
    except (TypeError, ValueError):
        return None


#: The dimensions whose METHOD can be carried onto a workshop entry, as
#: ``(payload key, source column)`` per reference model.
#:
#: EXPLICIT, PER MODEL, AND NOT INFERRED FROM EITHER SIDE, because the payload renames the key and
#: changes its unit in one step: ``widthCm`` <- ``breadthInches`` on the product, ``breadthCm`` <-
#: ``breadthInches`` on the tool. Matching key names is what once wrote an artisan's name into a
#: product column, and here it would attach one dimension's method to another dimension's number.
#:
#: THE FIVE UNIT-LESS TOOL COLUMNS ARE ABSENT AND A PAIR FOR ONE OF THEM WOULD BE A FALSE PROMISE.
#: ``measurement_provenance.DIMENSION_FIELDS`` is ``{lengthInches, breadthInches, heightInches}`` and
#: ``method_stamps`` drops a marker naming anything outside it, so ``height``, ``width``,
#: ``thickness``, ``weight`` and ``radius`` never receive a method to copy — the note directly above
#: ``DIMENSION_FIELDS`` says so itself, under WHAT IS STILL DELIBERATELY OUT. A pair here for one of
#: those five would print a sentence about a stamp nothing writes. When ``DIMENSION_FIELDS`` widens,
#: this table widens with it and the note starts saying so.
#:
#: THIS PARAGRAPH CITED A SECTION NAMED "WHAT THE RECORD HALF STILL CANNOT REACH" AND "the tool's
#: missing ``heightInches`` column" UNTIL 2026-08-27, AND NEITHER SURVIVES. That section is now
#: ``measurement_provenance``'s WHAT THE RECORD HALF CAN NOW REACH, AND THE PARAGRAPH THAT USED TO
#: SAY OTHERWISE, which quotes the sentence this note was leaning on and then retracts it; and the
#: column exists — the third pair under ``ToolDocumentation`` below is it. The exclusion rule itself
#: never changed. Only the reason attached to it did, which is why the citation is corrected here
#: rather than the rule.
_METHOD_CARRIED_DIMENSIONS: dict[str, tuple[tuple[str, str], ...]] = {
    "ProductDocumentation": (
        ("lengthCm", "lengthInches"),
        ("widthCm", "breadthInches"),
        ("heightCm", "heightInches"),
    ),
    "ToolDocumentation": (
        ("lengthCm", "lengthInches"),
        ("breadthCm", "breadthInches"),
        # THE THIRD PAIR, 2026-08-27. `ToolDocumentation.heightInches` did not exist until that day,
        # which is the whole reason this table stopped at two — and the comment below the tool's
        # carry said so, and called widening it the repo owner's call. The owner made it. The column
        # now exists, `DIMENSION_FIELDS` already named it, so the note starts saying so here exactly
        # as the line above this table promised it would.
        ("heightCm", "heightInches"),
    ),
}


def _measurement_method_note(subject: str, model: str, row: Any) -> str | None:
    """How the record's own dimensions were arrived at, as a sentence, or ``None`` when nothing says.

    ── THE FALSE CLAIM THIS ENDS, WHICH IS THE MEASUREMENT DEFECT ONE LAYER OUT ──────────────────
    ``records.merge_field_provenance`` stamps ``method: "VISION_MODEL"`` beside ``{by, byName, at}``
    on a dimension a model estimated off a photograph of the object on a grid sheet, and
    ``record_fields.dims_with_method`` prints that on the record sheet. Hydration copied the NUMBER
    onto a workshop entry and the stamp stayed behind: ``hydrate_entries`` writes
    ``HydrationSource(..., author_id=row.createdById)``, both field-provenance views render that
    account's NAME, and the .docx then attributed a machine's guess to a person. That is exactly the
    defect ``services/measurement_provenance`` was built to end — "a wrong dimension wearing somebody
    else's name is a costing error nobody can trace and that person cannot disown" — arriving through
    the door beside it.

    ── A SENTENCE ABOUT THE RECORD, NOT A LABEL ON THE BOX, AND THAT WORDING IS THE HONEST ONE ────
    The alternative was a method box per dimension per entity — five new fields — and it would have
    made a claim this function cannot support. Hydration only fills BLANKS: a designer who measured
    the saree themselves and typed a length keeps their number, while the neighbouring width is
    hydrated. A per-box label would then sit over a figure the designer produced and call it a model's
    estimate. Stating it about the RECORD's own columns stays true whatever the designer did with the
    boxes, which is why the sentence names the record and the record's words for its dimensions.

    Those words are DERIVED from the source column (``lengthInches`` -> "length"), the same rule
    ``dims_with_method`` derives its "L: " initials by and for the same reason it gives: a fourth
    dimension column added later cannot get the wrong word. It is also why the product's note says
    BREADTH where the box above it says Width — the note describes ``breadthInches`` on the product
    record, and the registry help on the field says so.

    ── ONLY THE TWO METHODS A READER CAN ACT ON, AND THE PHRASES ARE NOT OURS TO CHOOSE ──────────
    ``METHOD_CLAUSES`` is imported, so TYPED and UNRECORDED print nothing here for the reasons that
    dict argues at length: every row written before ``measurement_provenance`` existed is UNRECORDED,
    and appending "method not recorded" to most of the database trains a reader to skip the clause on
    the one row where it matters. A record whose dimensions are all typed or all legacy therefore
    hydrates NOTHING into this box, and the blank is the honest rendering.

    ── COMPUTED HERE, WHICH IS WHAT MAKES IT SAFE ────────────────────────────────────────────────
    The method is read off ``extraMetadata.fieldProvenance`` — a scalar column every row this
    module's ``find_many`` calls already return, so this costs no extra read and needs no new
    ``include`` (see the note on ``ReferenceModel.include`` for why a new one is not free).
    Building it inside ``spec.data`` rather than inside ``hydrate_entries`` also means
    ``entry_provenance.canonical_divergence`` recomputes it identically — the property ``_media_note``
    documents at length, and the reason a key the divergence path cannot reproduce is reported as
    diverged to an admin for ever. And it is copied AT SAVE like every other hydrated value: nothing
    re-resolves it at render, so re-measuring the record next year cannot change a submitted report.

    ── AND THE CENTIMETRE FIGURE'S METHOD IS INHERITED, NEVER "CONVERTED" ────────────────────────
    ``lengthCm`` is ``_inches_to_cm`` of a column whose method was recorded, so the strongest true
    sentence about it is the method of the INCH figure it came from. The conversion is arithmetic on
    somebody else's measurement and is not itself a measurement; a clause reading "converted" would
    describe the multiplication and hide the estimate underneath it.
    """
    grouped: dict[str, list[str]] = {}
    for _payload_key, column in _METHOD_CARRIED_DIMENSIONS.get(model, ()):
        # The same presence test the payload applies, so the note can only ever name a dimension
        # that has a number to qualify. `_inches_to_cm` answers None for an absent or unreadable
        # source, which is exactly when nothing is written into the box the clause would describe.
        if _inches_to_cm(getattr(row, column, None)) is None:
            continue
        clause = METHOD_CLAUSES.get(field_method(row, column) or "")
        if clause:
            grouped.setdefault(clause, []).append(column.removesuffix("Inches").lower())
    if not grouped:
        return None
    # Grouped by clause and joined the way `dims_with_method` joins its own — one parenthesis per
    # method, so a record whose length a model guessed and whose breadth a person measured off marks
    # says both instead of collapsing to whichever is listed first.
    return f"On the {subject} record: " + ", ".join(
        f"{', '.join(words)} ({clause})" for clause, words in grouped.items()
    )


def _enum_token(value: Any) -> str:
    """The bare token of a Prisma enum, which arrives as either a str or an enum member."""
    return str(getattr(value, "value", value) or "")


# ── THE THREE ENUM TRANSLATION TABLES, AND WHY EVERY ONE OF THEM IS TOTAL ───────────────────
#
# A Prisma enum and a registry ENUM are two closed lists written by two different people for two
# different documents, and the only safe way to cross between them is a table that names EVERY
# member of the source list — including the members that have no honest destination, which are
# spelled `None` here rather than left out.
#
# THE DEFECT THAT MADE THAT RULE NECESSARY is `_PRODUCT_TYPE_TO_CATEGORY` below as it stood: it
# held two of ProductType's six members, and `.get()` returns None for the other four. That is the
# right BEHAVIOUR and it was invisible — nothing distinguished "FINISHED_GOOD deliberately has no
# category" from "somebody forgot FINISHED_GOOD", and nothing at all would have distinguished
# either from "a seventh member was added to the Prisma enum last week". A partial map degrades
# silently into a stale map, and a stale map is a blank column in a report nobody re-reads.
#
# So: every table below is exhaustive over its Prisma enum, `None` is an explicit and reasoned
# entry, and `test_reference_carry.py` reads `prisma/schema.prisma` and fails if a member exists
# that these tables do not name. Adding a token to the schema now breaks a test instead of quietly
# hydrating nothing. DO NOT "tidy" these into `.get()` over a short dict again.
#
# ── AND THE SECOND RULE, WHICH THE TABLES BELOW USED TO SPLIT ON ───────────────────────────
#
# A PRISMA ENUM MEMBER THAT IS ALSO THE COLUMN'S @default AND HAS NO BLANK ALTERNATIVE IN ANY
# RECORD FORM IS NOT AN ANSWER. It is what the row holds when nobody was asked, and it is
# INDISTINGUISHABLE from a researcher who deliberately chose it — the two states are the same
# stored token and no query can separate them. Carrying it fills a workshop box, and in two cases a
# printed line, with an assertion nobody made.
#
# `_TRADITION_TYPE_TO_TRADITION` already said this ("an unanswered ENUM is expressed by leaving the
# box empty, not by a token that means 'empty'") while `_MAKER_TYPE_TO_MAKER` argued the opposite
# one table away ("'Not known' is a real answer a researcher chose") — a claim `ToolForm.tsx`
# contradicts, since it renders the select with `defaultValue={initial?.maker ?? "UNKNOWN"}` over a
# list with no blank member and submits `requiredText(form,"maker") || "UNKNOWN"`. The handset does
# the same with `includeNone = false`. All three tables now apply the one rule, and each names the
# schema default and the missing blank option as its reason rather than restating the rule.
#
# THE RULE IS ABOUT THE SOURCE FORM, NOT ABOUT THE WORD. `MakerType.OTHER` and `ProductType.OTHER`
# read alike and are not alike: nothing defaults to OTHER on the tool form, so a maker of "Other"
# WAS chosen and is carried. The question to ask of a member is always "could a row hold this
# without anybody having looked at the question?".

# ProductType and PRODUCT_CATEGORY answer two different questions and only two of their tokens
# mean the same thing. ProductType asks what KIND OF THING a documented record is — a finished
# good, a sample, a raw material — while the workshop registry's category asks what the product
# IS: a saree, a floor covering, a bag. Guessing across them would fill a ministry report's
# category column with plausible, wrong values that nobody would think to check, so only the two
# genuine matches are mapped and everything else arrives blank for the designer to choose.
#
# THE ANSWER TO "then the source's own type is lost" IS `existingProduct.recordType`, not a guess
# here. The four unmappable members are carried across verbatim into a field whose own ENUM is
# ProductType, so the record's answer reaches the workshop and the report intact — it simply
# reaches its own box instead of being mistranslated into somebody else's.
_PRODUCT_TYPE_TO_CATEGORY: dict[str, str | None] = {
    # PACKAGING stays, and it is the only token here that can: it is reachable ONLY by a researcher
    # opening the dropdown and picking it, so "PACKAGING" on a row is somebody's answer.
    "PACKAGING": "PACKAGING",
    # OTHER USED TO BE CARRIED AND IT IS THE COLUMN'S @default. `schema.prisma` declares
    # `productType ProductType @default(OTHER)`, `ProductForm.tsx` renders the select with
    # `defaultValue={initial?.productType ?? "OTHER"}` over a six-member list with no blank option
    # and submits `requiredText(form,"productType") || "OTHER"`, and the Android form does the same
    # with `includeNone = false`. So "the researcher chose Other" and "nobody answered the type
    # question" are the same stored token. `existingProduct.category` is one of the six columns the
    # Existing products table actually prints, so the carry filled a ministry report's Category
    # column with "Other" for every product nobody categorised — a plausible wrong value nobody
    # would think to check, which is the outcome this table's own header says it exists to prevent.
    # Only-fill-blanks then made the box look answered to the designer too.
    #
    # The record's answer is NOT lost by this: `_PRODUCT_TYPE_TO_MEMBER` still carries OTHER into
    # `existingProduct.recordType`, which is the box that exists for exactly that.
    "OTHER": None,
    # No honest counterpart: none of these says what the product IS. A finished good may be a
    # saree or a bag; a sample is a saree that happens not to be for sale.
    "FINISHED_GOOD": None,
    "SAMPLE": None,
    "RAW_MATERIAL": None,
    "COMPONENT": None,
}

#: ProductType -> PRODUCT_TYPE, the registry list added to mirror the Prisma enum member for
#: member. An identity map, and the reason it exists at all: it is what lets the four members
#: above be spelled `None` without losing the record's answer. The source says "this is a sample";
#: the workshop now has a box that can say "this is a sample" instead of a Category column
#: pretending a sample is a kind of saree.
_PRODUCT_TYPE_TO_MEMBER: dict[str, str | None] = {
    "FINISHED_GOOD": "FINISHED_GOOD",
    "SAMPLE": "SAMPLE",
    "RAW_MATERIAL": "RAW_MATERIAL",
    "COMPONENT": "COMPONENT",
    "PACKAGING": "PACKAGING",
    "OTHER": "OTHER",
}

#: MarketDemand -> DEMAND_LEVEL. Four of the five members are identical tokens, and the identity is
#: written out rather than assumed: the two lists live in two repositories that are versioned
#: separately, and "they happen to match today" is not something a mapping may depend on silently.
#: If either side gains a member the test that walks this table says so.
_MARKET_DEMAND_TO_DEMAND_LEVEL: dict[str, str | None] = {
    "LOW": "LOW",
    "MEDIUM": "MEDIUM",
    "HIGH": "HIGH",
    "SEASONAL": "SEASONAL",
    # THE DEFAULT, NOT AN ANSWER — see the second rule in the block header above.
    # `marketDemand MarketDemand @default(UNKNOWN)`, and `ProductForm.tsx` renders
    # `defaultValue={initial?.marketDemand ?? "UNKNOWN"}` over a five-token list with no blank
    # member, submitting `requiredText(form,"marketDemand") || "UNKNOWN"`. An untouched form stores
    # UNKNOWN, so the token says nothing about the market. It used to carry into
    # `existingProduct.marketDemand`, whose registry label for that token is "Not known" — and that
    # field has the DEFAULT report_role, KEY_VALUE, so it PRINTS: "Market demand: Not known" stood
    # in the per-row extras beneath the Existing products table of a submitted report for every
    # imported product, answering a question nobody was asked. Blank is the honest state and the
    # designer can still say it.
    "UNKNOWN": None,
}

#: TraditionType -> TRADITION_TYPE. The names collide and the tokens do not: the source says
#: TRADITIONAL / MODERN / HYBRID / UNKNOWN, the registry says TRADITIONAL / CONTEMPORARY /
#: TRANSITIONAL. Without this table `coerce_value` would refuse three of the four source answers
#: and drop them — silently, because a rejected hydration is indistinguishable from a source
#: column nobody filled in.
#:
#: MODERN -> CONTEMPORARY and HYBRID -> TRANSITIONAL are translations, not guesses: they are the
#: same answer to the same question in two vocabularies, and TRANSITIONAL's own label in the
#: registry reads "Traditional form, contemporary use", which is what a hybrid tool IS. UNKNOWN
#: has no counterpart because the registry deliberately offers none — an unanswered ENUM is
#: expressed by leaving the box empty, not by a token that means "empty".
_TRADITION_TYPE_TO_TRADITION: dict[str, str | None] = {
    "TRADITIONAL": "TRADITIONAL",
    "MODERN": "CONTEMPORARY",
    "HYBRID": "TRANSITIONAL",
    "UNKNOWN": None,
}

#: MakerType -> MAKER_TYPE. An identity map over a registry list that was ADDED to mirror this
#: Prisma enum (see ``ENUMS["MAKER_TYPE"]``), so the seven tokens are the same seven by
#: construction. It is still written out, for the reason above: the mirror is a decision somebody
#: made once, and a table plus a test is how that decision survives the next schema edit.
_MAKER_TYPE_TO_MAKER: dict[str, str | None] = {
    "ARTISAN": "ARTISAN",
    "LOCAL_BLACKSMITH": "LOCAL_BLACKSMITH",
    "CARPENTER": "CARPENTER",
    "WORKSHOP": "WORKSHOP",
    "FACTORY": "FACTORY",
    # Carried, and not in tension with UNKNOWN below: nothing on the tool form defaults to OTHER,
    # so a maker of "Other" is a pick somebody made.
    "OTHER": "OTHER",
    # THIS ENTRY USED TO READ: "Carried, unlike TraditionType's UNKNOWN, because the registry list
    # mirrors this enum and so HAS an UNKNOWN member: 'Not known' is a real answer a researcher
    # chose, and blanking it would turn it into 'not asked'." The premise about the registry list is
    # true; the premise about the researcher is not, and the form is what refutes it.
    # `schema.prisma` declares `maker MakerType @default(UNKNOWN)`, `ToolForm.tsx` renders
    # `defaultValue={initial?.maker ?? "UNKNOWN"}` over a list with no blank option and submits
    # `requiredText(form,"maker") || "UNKNOWN"`, and the handset builds the dropdown with
    # `includeNone = false`. There is no state of that form in which UNKNOWN was chosen rather than
    # left alone — so the token IS "not asked", and `tool.maker` was answering it "Not known" in the
    # designer's form and in the report. The registry list keeps its UNKNOWN member for a designer
    # who wants to state it; what is refused is the CARRY.
    "UNKNOWN": None,
}


def _translated(table: Mapping[str, str | None], value: Any) -> str | None:
    """One enum token through one of the total tables above.

    WHERE "REFUSE LOUDLY" ACTUALLY HAPPENS, and it is not here. A token the table does not name is
    a schema edit that shipped without its mapping, and the place to catch that is
    ``tests/test_reference_carry.py``, which walks ``prisma/schema.prisma`` and fails before the
    build leaves the machine. Raising here instead would 500 the whole picker — fifty rows lost
    because one of them holds a token added last week — which is the trade this module refuses
    everywhere else (see ``_load_one_reference_model``: "one unjoinable model must not lose a
    report that is the end of two weeks of fieldwork").

    So at RUNTIME the unmapped token is logged with the table it was not in, and the field arrives
    blank for the designer to answer. Silent is what this must never be; fatal is what it must not
    be either. An empty or absent source value is a different thing and logs nothing.
    """
    token = _enum_token(value)
    if not token:
        return None
    if token not in table:
        logger.error(
            "Reference carry: %r is not in its translation table, so it hydrates blank. Add it "
            "to the table in design_workshops (spelled None, with a reason, if it has no honest "
            "counterpart).", token,
        )
        return None
    return table[token]


#: RecordStatus -> what the picker says beside the record's name. APPROVED is deliberately absent:
#: an approved record is the ordinary case and a badge on every row would be noise that hides the
#: four rows that are not.
#:
#: LONGER THAN THE RECORD PAGE'S WORDING, ON PURPOSE. ``StatusBadge`` prints the same five tokens as
#: "Draft / Pending / Approved / Rejected / Needs revision", so a designer does see two spellings of
#: one status, and the difference is deliberate rather than drift: the badge is a coloured pill on a
#: row that is ABOUT that record, and colour plus shape carry half its meaning. This is a bare
#: sublabel under a name in a dropdown, with no chip, no colour and no context saying whose verdict
#: it is, so "Pending" alone reads as the designer's own unfinished work rather than as a reviewer
#: not having looked yet. Keep the phrasing self-explanatory here; if the picker ever gains a chip,
#: collapse it onto ``StatusBadge``'s words rather than inventing a third set.
#:
#: NOT ``ENUMS["REVIEW_DECISION"]``, WHICH IS A DIFFERENT QUESTION WITH SIMILAR WORDS. That table
#: ("Selected", "Rejected", "Revise and resubmit", "Pending review") is the WORKSHOP's decision on
#: its own prototype and carries a SELECTED member this vocabulary has no equivalent for. Unifying
#: the two would put a repository reviewer's verdict and a design review's outcome behind one set of
#: labels, and they are not the same fact about the same row.
_REVIEW_FLAGS: dict[str, str] = {
    "DRAFT": "Draft",
    "PENDING": "Awaiting review",
    "REJECTED": "Rejected by a reviewer",
    "NEEDS_REVISION": "Sent back for revision",
}


def _review_flag(row: Any) -> str:
    """A reviewer's verdict on a repository record, FOR THE PICKER'S SUBLABEL AND NOWHERE ELSE.

    THE PROBLEM THIS ANSWERS. `reference_options` builds its `where` from the workshop scope
    clause, the artisan filter and the search term, and from nothing else — there is no `status`
    clause and no `records.viewable_where` call — and `hydrate_entries` re-reads by id with no
    clause at all. That is right for VISIBILITY, and `entry_provenance` documents why: every
    signed-in account may read every row, because the whole point of pooling the fieldwork is that
    everyone can see the pool. A reviewer's VERDICT is a different question, and it had no answer
    anywhere: a `ToolDocumentation` a reviewer rejected as a duplicate, or sent back because its
    measurements cannot be right, sat in the stage-5 picker looking exactly like an approved one,
    and picking it copied all twenty-four fields into a report handed to a ministry officer.

    IT IS SHOWN AND NOT CARRIED, AND THAT IS THE WHOLE DESIGN. `status` is MUTABLE: a tool picked
    while PENDING is approved the following week, and one picked while NEEDS_REVISION is corrected
    and resubmitted. Hydration copies at SAVE time and the copy is permanent by design, so a
    `fromref("recordStatus", …)` would freeze a verdict that has since changed and print a false
    statement about a named reviewer for ever — worse than the silence it replaced, and it would
    cost a `registry_version()` bump, a schema-JSON regeneration and a new ENUM for a label. The
    sublabel is LIVE: it is composed on every `reference_options` call, at the moment of choosing,
    on the web picker and in `DwReferenceField` alike, and nothing about it is written onto an
    entry.

    A model with no `status` column answers None here and gets no suffix — `Craft` is the one,
    and taxonomy has no reviewer.
    """
    return _REVIEW_FLAGS.get(_enum_token(getattr(row, "status", None)), "")


@dataclass(frozen=True, slots=True)
class ReferencePhoto:
    """The one photograph a referenced record contributes, AND THE WORDS UNDER IT.

    ``_reference_photos`` used to return the media id alone, and the caption a researcher typed
    against that photograph in the repository — the one sentence that says what the picture shows
    — stopped at the join. Every gallery in this registry is declared with a ``*Caption`` field
    beside it (``report_role=CAPTION``, printed directly under the picture), so the workshop then
    asked the designer to retype a caption that already existed one row away, for a photograph
    they had never seen taken.

    A dataclass rather than a ``(id, caption)`` tuple because four call sites read this and a
    tuple index is the kind of thing that survives a refactor by silently meaning the other field.
    """

    id: str
    caption: str = ""


@dataclass(frozen=True, slots=True)
class ReferenceModel:
    """One external record type a REF field may point at.

    ``data`` is the important one: it is what hydrates into a stage entry when a designer picks
    this record, and it is the same dict the picker hands the client so the row fills in the
    instant it is chosen rather than after a round trip.
    """

    delegate: str                                   # the attribute on the Prisma client
    label: Callable[[Any], str]
    sublabel: Callable[[Any], str]
    data: Callable[[Any, ReferencePhoto | None], dict[str, Any]]
    order: dict[str, str]
    search_fields: tuple[str, ...]
    # THE RELATIONS `label`, `sublabel`, `data` AND `_reference_place` READ, AND NOTHING ELSE.
    # An include with no reader is a join issued on every picker keystroke, every `hydrate_entries`
    # save and every `load_report_references` for a value nobody looks at, on a link this module
    # measures at ~756 ms a hop. `ProductDocumentation` and `ToolDocumentation` both carried
    # `{"artisan": True}` that way: their lambdas read the DENORMALISED `r.artisanName` column and
    # never `_rel(r, "artisan", …)`, so the row was fetched and discarded. Both are gone.
    #
    # THE MIRROR-IMAGE TRAP IS LIVE AND IS DELIBERATELY LEFT ALONE — see `_reference_place`, which
    # reads `row.location` for every model while only `Artisan` includes it. Turning the location
    # include on for the two documentation models would CHANGE WHAT ALREADY-SUBMITTED REPORTS
    # PRINT, because `_reference_place` prefers `location.village` over the free-text `place` and
    # `load_report_references` runs at RENDER time, not at save time. That is the one thing the
    # never-re-resolve rule exists to forbid. If a district or state is genuinely wanted for a tool
    # or a product it is a deliberate change with a test pinning what the printed place becomes.
    include: dict[str, Any] = dataclass_field(default_factory=dict)
    # None when the model has no notion of a workshop, in which case a WORKSHOP-scoped field
    # falls back to the whole table rather than to nothing.
    workshop_where: Callable[[str], dict[str, Any]] | None = None
    # The column that narrows this model to one artisan, for the cascading pickers.
    #
    # THE VALUE ARRIVING IN `filterBy` MAY NOT BE AN ARTISAN ID AT ALL — at stage 13 the maker is
    # chosen from the ROSTER, so the same-named `artisanRef` holds a `DwParticipant` entry id — and
    # `_artisan_id_behind` is what resolves the two into one. That resolution is the ONLY thing that
    # separates this from `filter_field` below, which is why they are two attributes rather than one
    # with a flag.
    artisan_field: str = ""
    # The column that narrows this model to one parent record of some OTHER kind — `Process.productId`
    # for the process pickers, whose parent is a `ProductDocumentation` and not an artisan.
    #
    # ── WHY THIS IS NOT `artisan_field` REUSED, MEASURED RATHER THAN ASSUMED ─────────────────────
    # `_artisan_id_behind` HAPPENS to pass a product id through unchanged: it does
    # `db.dwstageentry.find_unique(where={"id": candidate})`, misses, and returns the candidate. So
    # the artisan arm would appear to work for a product cascade — while relying on a `DwStageEntry`
    # id never colliding with a `ProductDocumentation` id, and on a lookup whose whole meaning is "is
    # this a roster entry" being asked about a product. Worse, its OTHER branch is a real hazard: a
    # roster entry with no artisan behind it returns None, and `reference_options` answers that with
    # an EMPTY list on purpose — so a product id that ever did collide with a hand-typed roster
    # entry's id would empty a picker with `filtered: true` beside it. The gate is on WHICH COLUMN the
    # cascade names, not on a miss.
    #
    # ONE COLUMN PER MODEL, NOT PER FIELD, and that is a real limit: `ref_filter_by` names a sibling
    # field and the server never learns which model that sibling points at, so a model narrowable two
    # ways cannot be expressed here. Declaring both attributes is therefore refused at import (see
    # the check under `REFERENCE_MODELS`) rather than resolved by a silent precedence rule.
    filter_field: str = ""
    # The MediaFile foreign key naming this model, for the one photograph the picker shows.
    media_field: str = ""


def _artisan_workshop_where(workshop_id: str) -> dict[str, Any]:
    # BOTH READINGS OF "AT THIS WORKSHOP". An artisan reaches a workshop either through the
    # explicit column on their record or through the WorkshopArtisan join, and the two are kept
    # in lock-step but neither is complete on its own for rows written before the column
    # existed. Reading one of them would leave a designer staring at a picker that does not
    # contain the artisan sitting in front of them, and they would type the name in — which is
    # the exact behaviour this whole feature exists to end.
    return {"OR": [{"workshopId": workshop_id},
                   {"workshops": {"some": {"workshopId": workshop_id}}}]}


REFERENCE_MODELS: dict[str, ReferenceModel] = {
    "Artisan": ReferenceModel(
        delegate="artisan",
        # ── `workshop` AND `media`: WHERE THE ARTISAN WAS DOCUMENTED, AND WHAT IS ATTACHED ───────
        #
        # `workshop` IS SAFE TO SWITCH ON, AND THE CHECK IS THE ONE THE `location` INCLUDE HAD TO
        # PASS. `_reference_place` runs at RENDER time and reads `row.location`, which is why adding
        # THAT include could have changed the place printed in already-submitted documents and why it
        # now guards on the model name. Nothing anywhere reads `row.workshop`: that function reads
        # `location` and `place` and nothing else, `label`/`sublabel` read neither, and
        # `ReferencedRecord` carries only label, photo, place, district and state. So this include
        # changes what SAVE-time hydration can offer and nothing about what render time prints.
        #
        # WHY IT IS WORTH A JOIN AT ALL: `participant.artisanRef` is the ONE artisan field declared
        # ALL_SCOPE — "this is where the roster is built" — so a roster legitimately holds artisans
        # documented at other workshops, in other clusters, years earlier. `documentedOn` answers
        # WHEN and nothing answered WHERE, which is half of the job that field's own comment claims
        # for it ("it lets a reader of the printed report tell a roster row filled from a 2023 survey
        # from one filled last week"). `Artisan.workshop` is the explicit column, not the
        # `WorkshopArtisan` join: a many-to-many cannot answer "which one documented it".
        #
        # `media` is the count behind `recordMediaNote` — see `_media_note` for what it costs, why
        # the cheaper grouped query cannot be used, and why the FACT and never the ids cross.
        include={"craft": True, "location": True, "workshop": True, "media": True},
        order={"name": "asc"},
        search_fields=("name", "localName", "place"),
        workshop_where=_artisan_workshop_where,
        media_field="artisanId",
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(_rel(r, "craft", "name"), r.place, _review_flag(r)),
        data=lambda r, photo: {
            "name": r.name,
            "localName": r.localName,
            # The craft is the closest thing the artisan table has to a specialisation, and it
            # is what a researcher typed into the free metadata before the relation existed.
            "specialisation": (_rel(r, "craft", "name")
                               or _meta_value(_meta(r), "specialisation", "specialization")),
            # ── THE TWO ANSWERS THIS TABLE COULD NOT GIVE, AND NOW CAN ───────────────────────
            #
            # `experienceYears` and `age` had NO COLUMN on `Artisan` and were read only from legacy
            # `extraMetadata` spellings — where researchers put them before the record form was
            # structured, and which `ArtisanForm` stopped writing when the raw JSON textarea was
            # removed. So both were blank on every artisan created since, and
            # `participant.experienceYears` is a TABLE_COLUMN: the blank printed in the participant
            # table of every submitted report, and the designer typed it back in from a printout.
            #
            # `Artisan.dateOfBirth` and `Artisan.experienceYears` now exist and the artisan form
            # collects both, so an artisan IMPORTED into a workshop and one ADDED from inside it
            # carry the same facts. AGE IS DERIVED from the date rather than stored, for the reason
            # set out on the column: an age written down is wrong within a year and nothing notices.
            #
            # THE LEGACY READ SURVIVES AS A FALLBACK and must not be deleted. The migration copied
            # every clean numeric value across, but it deliberately refused to guess at the ones it
            # could not parse — "30+", "about 30" — and those artisans are exactly the oldest and
            # most thoroughly documented rows. Dropping the fallback would blank a value that is
            # currently right for them in order to tidy up a line of code.
            #
            # ── EXPERIENCE: THREE SOURCES, IN THIS ORDER, AND WHAT EACH BRANCH IS FOR ─────────
            #
            # 1. `derive_experience_years(r.craftStartDate)` — A DATE A HUMAN TYPED. Added
            #    2026-08-23 with the column, at the owner's request that experience become a
            #    derived field fed by a date of joining the craft. It wins because "years since a
            #    stated start" has exactly one reading and it is right on the day it is printed and
            #    right again next year, where a stated number is right on `recordedAt` and decays
            #    silently from then on — the failure `derive_age`'s own docstring names ("a record
            #    entered as '42' reads 42 for the rest of its life"). Where both exist, the date is
            #    the better fact, so it goes first.
            # 2. `r.experienceYears` — THE STATED NUMBER, and still the answer for almost every row
            #    in the table. It is shadowed ONLY on a row that also carries a `craftStartDate`,
            #    and after 20260823093000 a `craftStartDate` can only arrive one way: somebody typed
            #    it. A human typing a join date onto a record that already holds a number is
            #    correcting that number, which is the same rule that already lets a real date of
            #    birth overwrite the 1-July guess 20260816170000 wrote. THE MIGRATION DOES NOT
            #    BACKFILL, so every row that exists today reaches this branch and prints, character
            #    for character, what it printed before the column existed. That is what makes
            #    "nothing currently right is blanked" structural rather than an argument.
            # 3. The legacy `extraMetadata` spellings — the paragraph above. Untouched, and last.
            #
            # `is not None` at every step and NEVER `or`, for the reason spelled out on `age` below:
            # zero years is a real answer (an apprentice in their first month) and `or` would read it
            # as absent and fall through to a staler value.
            "craftStartDate": _iso_date(r.craftStartDate),
            "experienceYears": (
                derived
                if (derived := derive_experience_years(r.craftStartDate)) is not None
                else r.experienceYears if r.experienceYears is not None
                else _meta_value(_meta(r), "experienceYears", "experience", "yearsOfExperience")
            ),
            # `is not None` and NOT `or`: a derived age of 0 is a real answer (an infant), and
            # `or` would read it as absent and fall through to a stale metadata value. Nobody
            # documents a newborn artisan, which is exactly why this would never be noticed.
            "age": (
                derived if (derived := derive_age(r.dateOfBirth)) is not None
                else _meta_value(_meta(r), "age")
            ),
            "gender": r.gender,
            "phone": r.phone,
            "email": r.email,
            # ── IDENTITY: BOTH NUMBERS, BOTH MASKED — REVERSED BY THE OWNER 2026-08-24 ──────
            #
            # `participant.artisanCardNo` is labelled "Artisan ID / card number", is a TABLE_COLUMN
            # in every participant table, and sat directly opposite `Artisan.pehchanCardNumber`
            # unwired — so designers have been reading the PM Vishwakarma card over the artisan's
            # shoulder and typing it in beside a picker that already knew it.
            #
            # It is carried MASKED, and that is not timidity. `record_fields.py` carries the scar:
            # "The card number used to print verbatim here while the Aadhaar beside it was masked,
            # so a full PM Vishwakarma ID reached every grantee, dataset downloader and reviewer —
            # a rule that held on the API responses and nowhere else." A design workshop is exactly
            # such a surface: its stage reads do NOT pass through `records._redact_sensitive`, and
            # a DesignWorkshopViewer is a grantee. Copying the bare digits onto an entry would
            # re-open that hole through a new door, and the entry is then a permanent copy by
            # design. The last four are what a reader checks against the physical card anyway.
            #
            # THE AADHAAR NOW CROSSES TOO, ON THE SAME TERMS, AND THIS IS A REVERSAL. What stood
            # here until 2026-08-24 is kept verbatim, because a decision whose earlier argument has
            # been deleted reads as though it was never in doubt:
            #
            #   "`aadhaarNumber` is NOT carried at any masking. It is the deduplication key, it is
            #    governed, and "XXXX XXXX 9012" in a design report's participant table answers no
            #    question the report asks — the artisan is identified by name, Pehchan card and
            #    phone. If policy ever grants this report the full card number, ONE line changes:
            #    drop the `mask_identity_number` call. That is the whole reason the masking happens
            #    here rather than being spread across three clients."
            #
            # THE OWNER DECIDED OTHERWISE ON 2026-08-24, having been shown the exposure in full:
            # that a design workshop's stage reads do NOT pass through `records._redact_sensitive`,
            # that a `DesignWorkshopViewer` is a grantee, and that the entry is a PERMANENT copy
            # (hydration copies at SAVE time; the report never re-resolves, so clearing
            # `Artisan.aadhaarNumber` afterwards retracts it from no entry and no document already
            # written). BOTH identity numbers cross, BOTH masked to their last four digits, through
            # the SAME helper — which is the property the old split could not claim: one artisan's
            # identity now reads identically on every surface instead of being absent on one of
            # them and masked on the rest.
            #
            # The sentence above about "ONE line changes" is still true of the CARD and is now true
            # of both: dropping a `mask_identity_number` call is all it would take, which is exactly
            # why the masking lives here and is not spread across three clients.
            #
            # TO REVERSE THE REVERSAL: delete the `"aadhaarNumber"` line below, the pair in
            # `stage_schema.REFERENCE_HYDRATION["participant.artisanRef"]`, the same pair in
            # `frontend/lib/designWorkshops.ts`, the `participant.aadhaarNumber` FieldSpec in
            # `stage_definitions` (which carries the long form of this decision), and regenerate the
            # Android bundled asset. Entries already saved KEEP their masked number — a reversal
            # stops the next copy, it does not undo the ones already made.
            "pehchanCardAvailable": r.pehchanCardAvailable,
            "pehchanCardNumber": mask_identity_number(r.pehchanCardNumber),
            # THE SAME HELPER, DELIBERATELY, and not a second spelling of the rule beside it.
            # `mask_identity_number` IS `mask_aadhaar`: None in, None out; anything shorter than
            # four characters is masked entirely rather than partially revealed. It is also
            # idempotent on its own output, which is what makes the key name `aadhaarNumber` safe
            # rather than destructive — `records._IDENTITY_KEYS` walks nested dicts by key name, so
            # a stage entry that ever reached `public_encode` would be masked a second time and the
            # second pass is a no-op.
            "aadhaarNumber": mask_identity_number(r.aadhaarNumber),
            # The STATED village, never the provenance placeName: see the long note above the
            # Location model for why the two are not the same answer. `place` is the free-text
            # fallback the researchers were using before the stated-address columns existed.
            "village": _rel(r, "location", "village") or r.place,
            # THE REST OF THE STATED ADDRESS, and STATED is the whole point. `Location` holds two
            # groups of columns and the model's own docstring explains at length why reading one as
            # the other is a bug: `latitude`/`longitude`/`altitude`/`accuracy`/`capturedAt` are
            # PROVENANCE — a real GPS fix of the desk the record was typed at, 1,500 km from the
            # village named beside it on every live row — and `placeName` AND `address` are the two
            # strings DERIVED from that fix. NONE OF THOSE SEVEN CROSSES. `state`, `district`,
            # `pincode` and the SUBJECT pin do.
            #
            # `address` IS NAMED HERE BECAUSE IT IS THE ONE THIS NOTE USED TO LEAVE OUT OF BOTH
            # LISTS, and leaving it out was how it got carried. The value read
            # `r.address or _rel(r, "location", "address")`, so an artisan whose own Address box a
            # researcher left empty — while letting the device fill the GPS one, which is what the
            # form does automatically — printed in the participant roster of a submitted report as
            # "Village: Barpali / District: Bargarh / State: Odisha / Address: <a street in
            # Kharagpur, West Bengal>": four adjacent key-value lines contradicting each other under
            # one artisan's name. `LocationFields.tsx` labels that box "GPS address" and files it in
            # the panel captioned "Provenance, not an address. These values say where the device
            # was"; `LocationInput` and the `Location` model docstring both list it in the
            # PROVENANCE group, verbatim, beside `placeName`. It is derived from the same fix
            # `placeName` is, and `placeName` was already refused on exactly that ground three lines
            # from here. Only-fill-blanks made it unrecoverable as well: once the desk's street was
            # written onto the row, no re-pick could clear it — a designer had to notice and overtype
            # a value nothing on the page said was machine-derived.
            #
            # THE ANSWER TO "then the device address reaches nothing" is that it should not: it is a
            # permanent copy on a submitted roster, and a reverse-geocoded street for a laptop
            # answers no question the participant table asks. It stays on the `Location` row, where
            # the provenance view reads it as provenance.
            "state": _rel(r, "location", "state"),
            "district": _rel(r, "location", "district"),
            "pincode": _rel(r, "location", "pincode"),
            "address": r.address,
            "subjectLocation": _subject_point(_rel_obj(r, "location")),
            "notes": r.notes,
            # Newline-separated, numbered guidance for working with THIS artisan — a positive
            # prompt and a negative one. Arguably the most useful thing on the record to a designer
            # standing in the room, and it reached nothing.
            "dos": r.dos,
            "donts": r.donts,
            # Provenance of the SOURCE RECORD, not of the workshop: when the artisan was
            # documented. Same job as `processStep.documentedFor` — it lets a reader of the printed
            # report tell a roster row filled from a 2023 survey from one filled last week.
            "documentedOn": _iso_date(r.recordedAt),
            # THE OTHER HALF OF THAT SENTENCE, WHICH HAD NO FIELD. `documentedOn` answers when; this
            # answers under whose study, which is the reader's next question about a roster row
            # imported from another cluster. `Workshop.title` is the display column, and `_rel`
            # answers None for the common case: `Artisan.workshopId` is nullable and the artisans
            # documented before the column existed carry the `WorkshopArtisan` join instead.
            "documentedAtWorkshop": _rel(r, "workshop", "title"),
            # WHAT THE RECORD HAS ATTACHED BEYOND THE ONE PHOTOGRAPH BELOW. `_reference_photos`
            # resolves a single IMAGE and no other kind of row, so an artisan's recorded spoken
            # introduction — the thing the record form's media card asks for by name — existed on the
            # record and could not be mentioned anywhere on the roster row. A sentence, never the
            # ids: see `_media_note`.
            "recordMediaNote": _media_note("artisan", _rel_obj(r, "media")),
            "photo": photo.id if photo else None,
            "photoCaption": photo.caption if photo else None,
        },
    ),
    "ProductDocumentation": ReferenceModel(
        delegate="productdocumentation",
        order={"productName": "asc"},
        search_fields=("productName", "localName", "artisanName", "craftName"),
        # ── THE RECORD'S OWN STATED ADDRESS, WHICH THE FREE-TEXT `place` CANNOT CARRY ────────────
        #
        # Both record pages collect a full location — state, district, village, pincode — and none of
        # it crossed. The workshop row had one free-text `place` string, so a product documented in
        # Barpali, Bargarh, Odisha reached the report as whatever somebody typed into one box.
        #
        # SAFE TO SWITCH ON ONLY BECAUSE `_reference_place` NOW GUARDS ON THE MODEL. That function
        # runs at RENDER time and used to prefer `location.village` over `place` for any row whose
        # relation happened to be loaded, so adding this include would have changed the place printed
        # in reports already submitted — the trap its own comment warned about in capitals. It now
        # returns the denormalised `place` for everything except an artisan, so this include changes
        # what SAVE-time hydration can offer and nothing about what render time prints.
        #
        # STATED COLUMNS ONLY. `latitude`/`longitude`/`altitude`/`accuracy`/`capturedAt`/`placeName`
        # are the fix of the desk the record was typed at — routinely 1,500 km from the village named
        # beside it — and they never cross as an address. Same rule, same reason, as the artisan.
        #
        # AND THE ONE COORDINATE THAT IS NOT THE DESK. `subjectLatitude`/`subjectLongitude` are the
        # pin a researcher DROPPED on the product's own place with the map picker, which is the other
        # half of the rule the artisan side has always honoured: the device fix never crosses as an
        # address, the STATED columns and the SUBJECT pin do. It needs no additional column here —
        # `_subject_point` reads the same relation this include already loads, and refuses half a
        # coordinate.
        #
        # `media` is the count behind `recordMediaNote`; see `_media_note`.
        include={"location": True, "media": True},
        workshop_where=lambda wid: {"workshopId": wid},
        artisan_field="artisanId",
        media_field="productId",
        label=lambda r: str(r.productName or ""),
        sublabel=lambda r: _joined(r.artisanName, r.craftName, _money(r.sellingPrice),
                                   _review_flag(r)),
        data=lambda r, photo: {
            "name": r.productName,
            "localName": r.localName,
            "category": _translated(_PRODUCT_TYPE_TO_CATEGORY, r.productType),
            # The source's OWN answer, untranslated, beside the workshop's own question above.
            # See `_PRODUCT_TYPE_TO_CATEGORY`: four of ProductType's six members have no honest
            # category, and this is where they land instead of being guessed at.
            "recordType": _translated(_PRODUCT_TYPE_TO_MEMBER, r.productType),
            "material": r.rawMaterialsUsed,
            "mainToolsUsed": r.mainToolsUsed,
            "price": _money(r.sellingPrice),
            "costOfMaking": _money(r.costOfMaking),
            "marketDemand": _translated(_MARKET_DEMAND_TO_DEMAND_LEVEL, r.marketDemand),
            "use": r.productFunctionUse,
            "craftName": r.craftName,
            "place": r.place,
            "artisanName": r.artisanName,
            # ── THE UNIT CONVERSION. Read `_inches_to_cm` before touching any of these three. ──
            #
            # `breadthInches` -> `widthCm` is a NAMING judgement made deliberately: breadth and
            # width are the same measurement under two words, `ProductDocumentation` has no
            # separate width column and `existingProduct` has no separate breadth field, so the
            # pairing is forced and correct. (The tool model DOES have both, and is mapped
            # differently for exactly that reason — see `ToolDocumentation` below.)
            #
            # `weightG` has no source column at all and stays a workshop-only answer.
            #
            # ── AND HOW THE NUMBER WAS ARRIVED AT, WHICH NOW CROSSES BESIDE IT ──────────────
            #
            # THE THREE MEASUREMENT COLUMNS THEMSELVES STILL DO NOT CROSS. `measurementImageId`,
            # `measurementAnalysis` and `measurementAnalysisStatus` are refused for reasons the
            # ledger in `tests/test_reference_carry.py` gives one by one — a working photograph of a
            # ruler, raw machine prose with none of the treatment `report_templates` requires, and a
            # queue state. What was missing was not those columns but the one FACT they imply: some
            # of these numbers were not measured by a hand and a tape. `GridMeasurement.tsx`
            # auto-fills the inches box from a vision model's reading of a photograph of the object
            # on graph paper, and the researcher then saves the form; `measurement_provenance.py`
            # calls that save the acceptance — "The acceptance IS the save, once the method sits next
            # to the signature" — under the law it states at the top of its module docstring, "EVERY
            # STORED DIMENSION STATES ITS METHOD".
            #
            # `hydrate_entries` stamps every value it writes with `HydrationSource(..., author_id=
            # row.createdById)`. THAT ID IS THE PERSON WHO SAVED THE RECORD, and the field-provenance
            # views on web and handset show their name. It is not, and must not be read as, a claim
            # that they held a tape against the saree. That is why the method has to travel with the
            # figure rather than be inferred from the name beside it.
            #
            # BOTH HALVES HAVE NOW LANDED, and this paragraph names them rather than describing a gap
            # because a reader who remembers the gap would otherwise go looking for work that is
            # done. `records.merge_field_provenance` calls `measurement_provenance.method_stamps` and
            # merges `method` — plus `methodProvider` / `methodModelId` / `methodConfidence` /
            # `methodTechnique` when the marker carries them — onto each dimension column BESIDE the
            # `{by, byName, at}` described above; and `_measurement_method_note` below reads that
            # stamp off this very row and carries it into `measurementMethodNote` as one sentence
            # about the RECORD's dimensions. Read that function before touching either: it says why a
            # sentence about the record is the only claim that stays true under the only-fill-blanks
            # rule, why the phrases are imported from `record_fields.METHOD_CLAUSES` rather than
            # written here, and why the centimetre figure's method is INHERITED from the inch figure
            # and never "converted" as though the multiplication were the measurement.
            #
            # `dimensionsNote` (<- `size`) is free text and carries no method; do not invent one.
            "lengthCm": _inches_to_cm(r.lengthInches),
            "widthCm": _inches_to_cm(r.breadthInches),
            "heightCm": _inches_to_cm(r.heightInches),
            # THE SENTENCE THAT STOPS THE THREE NUMBERS ABOVE BEING READ AS A PERSON'S MEASUREMENT.
            # `None` for a record whose dimensions are all typed or all legacy, which leaves the box
            # blank — see `_measurement_method_note` for why silence is the honest rendering there.
            "measurementMethodNote": _measurement_method_note(
                "product", "ProductDocumentation", r),
            # The free-text size, which is what a product the measured boxes do not suit is
            # actually described by ("king size", "9 yards"). It lands on `dimensionsNote`, which
            # is the box that was already sitting opposite it.
            "dimensionsNote": r.size,
            # Free text on the source ("about three days", "2 weeks"), and the workshop's
            # `productionTimeDays` is a DECIMAL. NOT parsed into it: a parser that reads "2 weeks"
            # as 2 puts a wrong number in a cost sheet, and one that gives up silently is the
            # blank this whole lane exists to end. The words are carried as words.
            "productionTimeNote": r.timeTakenToCompleteProduct,
            "remarks": r.remarks,
            # THE RECORD'S OWN STATED ADDRESS. Stated columns only — see this model's `include`
            # for why the device's fix never crosses as an address. `place` above is the
            # denormalised free-text column and stays exactly as it was; these are the four the
            # record page actually collects.
            "recordState": _rel(r, "location", "state"),
            "recordDistrict": _rel(r, "location", "district"),
            "recordVillage": _rel(r, "location", "village"),
            "recordPincode": _rel(r, "location", "pincode"),
            # THE PIN ON THE PRODUCT'S OWN PLACE, which is the half of invariant 4 this model had
            # been missing while the artisan honoured both. See `_subject_point`: the subject pin and
            # the device fix are different columns answering different questions, and only this one
            # is about the village.
            "subjectLocation": _subject_point(_rel_obj(r, "location")),
            # How much footage the product record carries — an audio note in which the artisan
            # explains the piece, a video of it being finished — none of which one IMAGE can say
            # exists. A sentence and never the ids; see `_media_note`.
            "recordMediaNote": _media_note("product", _rel_obj(r, "media")),
            "documentedOn": _iso_date(r.recordedAt),
            "photo": photo.id if photo else None,
            "photoCaption": photo.caption if photo else None,
        },
    ),
    "ToolDocumentation": ReferenceModel(
        delegate="tooldocumentation",
        order={"toolkitName": "asc"},
        search_fields=("toolkitName", "localName", "englishName", "artisanName"),
        workshop_where=lambda wid: {"workshopId": wid},
        artisan_field="artisanId",
        media_field="toolId",
        # ── THE MANY-TO-MANY, WHICH IS THE ANSWER THE DENORMALISED `artisanName` CANNOT GIVE ─────
        #
        # `ToolArtisan` is a real relation with a whole UI behind it — `ToolAssignmentSection` on the
        # tool page exists to assign one tool to SEVERAL artisans, and `GET /tools/{id}/artisans`
        # serves them — while `ToolDocumentation.artisanName` is one denormalised string naming
        # whoever the record was first documented against. So a pit loom assigned to nine weavers
        # crossed into a workshop as one name, and the other eight existed nowhere the report could
        # reach. That is not a thin carry, it is a different fact: "documented for" and "used by"
        # are not the same question, and the tool row already has a box for the first.
        #
        # ONE EXTRA INDEXED READ FOR THE WHOLE PAGE, exactly as `Process`'s `steps` include is: Prisma
        # issues the relation as a single `WHERE toolId IN (…)` against `@@index([toolId])`, and the
        # picker is bounded by `REFERENCE_LIMIT_MAX`, so this is not an N+1.
        # ── THE RECORD'S OWN STATED ADDRESS, WHICH THE FREE-TEXT `place` CANNOT CARRY ────────────
        #
        # Both record pages collect a full location — state, district, village, pincode — and none of
        # it crossed. The workshop row had one free-text `place` string, so a product documented in
        # Barpali, Bargarh, Odisha reached the report as whatever somebody typed into one box.
        #
        # SAFE TO SWITCH ON ONLY BECAUSE `_reference_place` NOW GUARDS ON THE MODEL. That function
        # runs at RENDER time and used to prefer `location.village` over `place` for any row whose
        # relation happened to be loaded, so adding this include would have changed the place printed
        # in reports already submitted — the trap its own comment warned about in capitals. It now
        # returns the denormalised `place` for everything except an artisan, so this include changes
        # what SAVE-time hydration can offer and nothing about what render time prints.
        #
        # STATED COLUMNS ONLY. `latitude`/`longitude`/`altitude`/`accuracy`/`capturedAt`/`placeName`
        # are the fix of the desk the record was typed at — routinely 1,500 km from the village named
        # beside it — and they never cross as an address. Same rule, same reason, as the artisan.
        #
        # AND THE SUBJECT PIN, for the same reason it is now read on the product: the map picker on
        # the record page writes `subjectLatitude`/`subjectLongitude` for a tool exactly as it does
        # for a product, and the pin is about the place, not about the desk. `media` is the count
        # behind `recordMediaNote` — the tool record's media card is mounted TWICE, once for the
        # ordered "Process stages" sequence and once for general footage, and one still image was the
        # whole of what could reach a report. See `_media_note`.
        include={"location": True, "media": True,
                 "artisanLinks": {"include": {"artisan": True}}},
        label=lambda r: str(r.toolkitName or ""),
        sublabel=lambda r: _joined(r.englishName, r.artisanName, r.place, _review_flag(r)),
        data=lambda r, photo: {
            "name": r.toolkitName,
            "localName": r.localName,
            "englishName": r.englishName,
            "material": r.material,
            "usedFor": r.processUsedIn,
            "cost": _money(r.replacementCost),
            "yearsInUse": r.yearsInUse,
            "maker": _translated(_MAKER_TYPE_TO_MAKER, r.maker),
            "traditionType": _translated(_TRADITION_TYPE_TO_TRADITION, r.traditionType),
            "craftName": r.craftName,
            "place": r.place,
            "artisanName": r.artisanName,
            "improvements": r.suggestionsForToolImprovement,
            "remarks": r.remarks,
            # ── EIGHT MEASUREMENTS IN TWO DIFFERENT STATES OF KNOWLEDGE ─────────────────────
            #
            # THIS HEADING SAID SEVEN, AND SO DID ITS TWIN IN `stage_definitions`, until the tool
            # gained `heightInches` on 2026-08-27. Count the keys emitted below: `lengthCm`,
            # `breadthCm`, `heightCm` and the five `*AsRecorded` ones — eight. The twin was
            # corrected the same day; this one was missed in that pass and is corrected here.
            #
            # `lengthInches`, `breadthInches` and `heightInches` DECLARE their unit in the column
            # name and the tool form labels them "Length (inches)" / "Breadth (inches)" /
            # "Height (inches)", so they convert — see `_inches_to_cm`. They keep the word
            # BREADTH here, unlike the product's, because
            # `ToolDocumentation` also has a separate unitless `width` column and collapsing the
            # two into one "width" would silently merge two different measurements.
            #
            # `height`, `width`, `thickness`, `weight` and `radius` DECLARE NOTHING. The Prisma
            # columns carry no unit suffix, the form's labels are the bare words "Height",
            # "Weight", "Radius", and the record sheet prints them bare too. Nobody knows whether
            # a 12 is inches, centimetres or kilograms. So they are carried into fields that make
            # the same claim the source makes — none — rather than into a box labelled "cm" that
            # would turn an unknown unit into a stated wrong one. Inventing a unit is the failure
            # `_inches_to_cm` exists to prevent, and guessing one is the same failure with a
            # shrug in front of it.
            #
            # WHAT CROSSES WITH THE THREE CONVERTED NUMBERS: see the same note on
            # `ProductDocumentation` above. Their METHOD does, now, in `measurementMethodNote` below;
            # the `HydrationSource` author is still the record's `createdById` — the person who saved
            # it, not a claim that they measured it, which is why the method had to travel too.
            #
            # AND THE FIVE UNIT-LESS ONES CARRY NO METHOD, WHICH IS NOT AN OVERSIGHT HERE.
            # `measurement_provenance.DIMENSION_FIELDS` is `{lengthInches, breadthInches,
            # heightInches}`, so `method_stamps` drops a marker naming `height`, `width`,
            # `thickness`, `weight` or `radius` and nothing ever writes a stamp for them. These
            # five therefore state neither their unit nor their method, and the second silence is
            # the record's, not this carry's. They are the five keys carried below — `heightAsRecorded`,
            # `widthAsRecorded`, `thicknessAsRecorded`, `weightAsRecorded`, `radiusAsRecorded` —
            # counted off those five lines. The note above `_METHOD_CARRIED_DIMENSIONS` says FIVE
            # of the same columns, and so does the help text on the tool's `measurementMethodNote`
            # in `stage_definitions`; all three must move together or one of them is lying.
            #
            # WHAT CHANGED ON 2026-08-27, AND WHAT DID NOT. Changed: `ToolDocumentation` gained a
            # `heightInches` column, the pair is in `_METHOD_CARRIED_DIMENSIONS`, and `heightCm`
            # is carried above WITH its method — so an accepted vision-model tool height is no
            # longer forced into a unit-less box and "recorded as nothing", which is what the text
            # here used to say it was, calling the remedy the repo owner's call. The owner made
            # it. NOT changed: how many unit-less columns there are. `heightAsRecorded` below is
            # the OLD plain `height` column, which stays because rows already hold values in it
            # and nothing can say what unit those are in; it is still outside `DIMENSION_FIELDS`
            # and still carries no method. This paragraph was briefly rewritten to say FOUR on
            # the strength of the new column, which was wrong in a way worth naming: the new
            # column is a THIRD UNIT-DECLARED one, not the promotion of one of the five unit-less
            # ones. Corrected the same day.
            "lengthCm": _inches_to_cm(r.lengthInches),
            "breadthCm": _inches_to_cm(r.breadthInches),
            "heightCm": _inches_to_cm(r.heightInches),
            # Only ever about the three converted figures above — see `_METHOD_CARRIED_DIMENSIONS`.
            "measurementMethodNote": _measurement_method_note("tool", "ToolDocumentation", r),
            "heightAsRecorded": _decimal(r.height),
            "widthAsRecorded": _decimal(r.width),
            "thicknessAsRecorded": _decimal(r.thickness),
            "weightAsRecorded": _decimal(r.weight),
            "radiusAsRecorded": _decimal(r.radius),
            # EVERY ARTISAN THE TOOL IS ASSIGNED TO, not just the one it was documented against.
            # See the note on this model's `include`. Newline-separated because the target is a
            # LONG_TEXT with `report_role=BULLETS`, which the renderer splits into one bullet per
            # line — the same shape, for the same reason, as `_step_lines`.
            # THE RECORD'S OWN STATED ADDRESS. Stated columns only — see this model's `include`
            # for why the device's fix never crosses as an address. `place` above is the
            # denormalised free-text column and stays exactly as it was; these are the four the
            # record page actually collects.
            "recordState": _rel(r, "location", "state"),
            "recordDistrict": _rel(r, "location", "district"),
            "recordVillage": _rel(r, "location", "village"),
            "recordPincode": _rel(r, "location", "pincode"),
            # The pin on the tool's own place, never the device's fix — see `_subject_point`, and
            # the same note on `ProductDocumentation` above.
            "subjectLocation": _subject_point(_rel_obj(r, "location")),
            # THE NUMBERED MAKING SEQUENCE AND EVERYTHING ELSE ATTACHED. The tool record's media
            # card is mounted twice — "Process stages", whose captures `ToolForm` renames
            # `STAGE_STEP_<n>_…` so they archive in order, and "Tool media" for video and audio —
            # and `_reference_photos` resolves ONE image, so a tool documented as a nine-photograph
            # sequence reached the report as a single still with nothing admitting the rest existed.
            # `numbered_prefix` is what lets the sentence say the sequence is a sequence.
            "recordMediaNote": _media_note("tool", _rel_obj(r, "media"),
                                           numbered_prefix="STAGE_STEP_"),
            "usedByArtisans": _linked_artisan_names(r),
            "documentedOn": _iso_date(r.recordedAt),
            "photo": photo.id if photo else None,
            "photoCaption": photo.caption if photo else None,
        },
    ),
    "Process": ReferenceModel(
        # NO `media_field`, UNLIKE THE FOUR MODELS AROUND IT, AND IT IS NOT AN OVERSIGHT — it is
        # not currently possible. `_reference_photos` groups by a FOREIGN KEY on `MediaFile` and
        # `MediaFile` has no `processId`: `api/routes/processes.py` says so out loud — "Media is
        # linked purely through `linkedRecordType`/`linkedRecordId` (`process` for the pre-process
        # clips, `processstep` for each step) so no MediaFile foreign keys are needed". So a
        # process's photographs — including the pre-process media the record form requires whenever
        # the box is ticked, and every step's own capture — reach no report: `ReferencedRecord.photo`
        # is "" for a process and `report_builder._images` PASS TWO finds nothing to place.
        #
        # THE COST IS REAL AND THE FIX IS NOT FREE. Giving `MediaFile` a `processId` is a migration
        # plus a backfill from the existing `linkedRecordType='process'` tags, plus `processId` in
        # `_PHOTO_PARENT_COLUMNS`, plus moving `process` from the tag-only list to the FK list in
        # `media.py`'s orphan recovery. If it is done it must stop at ONE record photograph flowing
        # through `ReferencedRecord.photo`, and it must not seed any stage gallery — the designer's
        # own workshop photographs live there and there is no second copy of them. Step-level media
        # stays out either way: `processstep` has no parent row in `REFERENCE_MODELS` and a
        # step-level key would invite the per-row carry the `steps` refusal below already rules out.
        delegate="process",
        # `steps` JOINED HERE, AND IT COSTS ONE EXTRA READ PER PICKER CALL. A `Process` owns an
        # ordered list of `ProcessStep` rows — the sequence a researcher actually documented — and
        # until this include existed there was no way for any of it to reach a workshop at all.
        # Prisma issues the relation as one extra `WHERE processId IN (…)` for the whole page, not
        # one per row, and the picker is bounded at `REFERENCE_LIMIT_MAX`, so this is a second
        # indexed read (`@@index([processId])`) and not an N+1.
        # NO `media` INCLUDE ON EITHER SIDE, AND THE COMMENT AT THE TOP OF THIS BLOCK IS WHY.
        # It says `MediaFile` has no `processId` — correct — and then this include asked Prisma for
        # the very relation that foreign key would have created. There is none on `Process` and none
        # on `ProcessStep`, so Prisma refused the whole query before reading a row:
        #     UnknownRelationalFieldError: Field "media" either does not exist or is not a
        #     relational field on the Process model
        # That 500'd BOTH process pickers — `traditionalProcess.processRef` and
        # `processStep.processRef`, i.e. the whole of stage 5's process linkage — for every designer
        # on every save, and two tests had been failing on it. The comment was right and the include
        # was wrong. Fixed 2026-08-23.
        #
        # `steps` still travels, as a plain relation include: `_step_lines` reads only `sortOrder`,
        # `name`, `stepType` and `notes`, every one a scalar column, so nesting a media include
        # under it never bought anything.
        #
        # `_process_media_note` consequently returns None until `MediaFile` gains a `processId`, and
        # that is NOT a silent loss of a working feature — the note could never be computed, because
        # the query meant to feed it could not run. The price of restoring it is set out at the top
        # of this block. It cannot be done with a media QUERY instead: see the "TRIED AND REFUSED"
        # section of `_reference_media_note`, where `entry_provenance.canonical_divergence` calls
        # `spec.data(rec, photo)` with exactly two arguments, so a key that path cannot recompute is
        # reported to an admin as `diverged` on every audit, for ever.
        include={"product": True, "steps": True},
        order={"name": "asc"},
        search_fields=("name",),
        workshop_where=lambda wid: {"workshopId": wid},
        # ── THE PROCESS PICKER IS NARROWED BY THE PRODUCT, AND THIS IS THE HALF THAT MAKES IT WORK ──
        #
        # `Process.productId` is NON-NULLABLE and its schema comment says a process "reaches a
        # workshop only through its parent product": one product, many processes. Both stage-5
        # process pickers now declare `ref_filter_by="productRef"`, and without this column the
        # filter arm in `reference_options` would raise 422 "Process cannot be filtered by another
        # record" on every open of either picker — i.e. the whole of stage 5's process linkage dead
        # for every designer, which is the same shape of failure as the `media`-include 500 recorded
        # at the top of this block.
        #
        # ONE EXTRA CLAUSE ON AN EXISTING INDEX (`@@index([productId])`), and it is in the WHERE
        # rather than applied to the page after it comes back: `REFERENCE_LIMIT_DEFAULT` is 50, so a
        # client-side narrowing would take 50 rows of the workshop's whole process list and then
        # filter them, showing an EMPTY picker for any product whose processes all sort after row 50
        # — an empty list that reads as "nothing was documented" for records that exist. The cascade
        # therefore makes truncation less likely, not more.
        filter_field="productId",
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(_rel(r, "product", "productName"), _review_flag(r)),
        # THREE KEYS, NOT ONE, and the third is what the sublabel above already shows.
        #
        # This lambda used to return the name alone, which made the traditional-process stage —
        # one of the report's substantive narrative sections — the thinnest of the five reference
        # models by an order of magnitude: eight fields reach a participant row, six a tool row,
        # six an existing-product row, and one reached a process step. The `Process` table holds
        # notes and hangs off a product; both are copied. Which of the model's columns land on
        # which box, and why `steps` and `preProcessAvailable` deliberately land nowhere, is
        # written out above `REFERENCE_HYDRATION["processStep.processRef"]` in `stage_schema`,
        # because that is where the pairing is declared and a reason belongs beside the decision
        # it explains rather than beside the value it reads.
        data=lambda r, _photo: {
            "name": r.name,
            "notes": r.notes,
            "productName": _rel(r, "product", "productName"),
            # ── THE THREE KEYS THAT NO PROCESS-STEP ROW MAY RECEIVE ─────────────────────────
            #
            # These are read by `traditionalProcess.processRef` — the stage-5 SINGLETON — and by
            # nothing else, on purpose. `REFERENCE_HYDRATION["processStep.processRef"]` refuses
            # both `steps` and `preProcessAvailable` and its reasons are still correct: a whole
            # sequence printed inside one of its own steps, repeated on every row naming the same
            # process, and "Pre-process available: Yes" under step 3 of 7 answering a question
            # nobody asked of that row. That note also identified the right home — "the
            # `traditionalProcess` singleton … but a singleton has no ref field to hydrate from" —
            # so the singleton was given one. One copy, above the steps table, where a reader
            # wants the sequence and the pre-process answer.
            #
            # If somebody later adds any of these three to the `processStep.processRef` mapping,
            # the row-level defect comes back exactly as described. Widen the singleton instead.
            "steps": _step_lines(r),
            # ── WHAT THE PROCESS RECORD HAS ATTACHED, WHICH REACHED NO SURFACE ────────────────
            #
            # A process carries pre-process clips on itself and each `ProcessStep` carries its own
            # captures — `ProcessForm` has a MediaCaptureField for the process and one per step, and
            # `describePreProcess`/`describeProcessStep` name the files. None of it crossed, so a
            # researcher who filmed every step of a dye sequence produced a workshop row that said
            # the sequence existed and showed nothing of it.
            #
            # A COUNT AND NOT THE IDS, deliberately, and this is the one place a count is the honest
            # answer rather than a lazy one. A stage entry's galleries hold the DESIGNER's own
            # photographs and hydration must never seed them (the gallery rule); and the referenced
            # record's media are entitlement-gated per file, which `_reference_photos` resolves for
            # exactly one image and no more. Copying a list of ids onto the entry would either
            # bypass that gate or freeze ids the report cannot fetch. What a reader needs, and what
            # is safe, is to know the footage exists and where to ask for it.
            "recordMediaNote": _process_media_note(r),
            "preProcessAvailable": r.preProcessAvailable,
            "documentedOn": _iso_date(r.recordedAt),
        },
    ),
    "Craft": ReferenceModel(
        delegate="craft",
        # ── THE FIRST INCLUDE THIS MODEL HAS EVER DECLARED, AND BOTH HALVES ANSWER A STAGE-1 BOX ──
        #
        # `media`: the crafts page mounts `MediaCaptureField` with no `allowedTypes`, so a craft may
        # carry unlimited images, video, audio notes and documents — and exactly one still image
        # crossed, through `_reference_photos`. A craft documented with fifteen loom photographs, a
        # recorded elder's account of the technique and a scanned gazetteer page contributed one
        # picture to a report whose cover page names that craft, with nothing saying the rest was
        # there. `MediaFile.craftId` is indexed (checked in `prisma/schema.prisma`, whose own comment
        # says those parent keys exist FOR this reverse walk), so this is one indexed read for the
        # page and not a scan.
        #
        # `workshop`: `workshopSetup.craftRef` is ALL_SCOPE, so a designer may legitimately link a
        # craft documented in another cluster by another study years earlier. `craftDocumentedOn`
        # already answers WHEN; nothing answered under whose study, which is the next question a
        # reader asks of a cover page naming a craft this designer never surveyed. `Craft.workshop`
        # is the explicit column and NOT the `WorkshopCraft` join — the picker's `workshop_where`
        # reads both, but a many-to-many cannot answer "which one documented it" and would need the
        # BULLETS treatment `tool.usedByArtisans` got, which is a much larger change for a much
        # weaker fact. Nothing at render time reads `row.workshop` (`_reference_place` reads
        # `location` and `place`), so this include cannot change what an already-submitted report
        # prints — the check the `location` include on the product and the tool had to pass.
        include={"media": True, "workshop": True},
        order={"name": "asc"},
        search_fields=("name", "localName", "category"),
        workshop_where=lambda wid: {
            "OR": [{"workshopId": wid}, {"workshops": {"some": {"workshopId": wid}}}]
        },
        media_field="craftId",
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(r.category, r.place),
        # ── THE CRAFT RECORD, IN FULL — AND THE ARGUMENT THIS REPLACES WAS THE LAST 1:1 GAP ─────
        #
        # This carried two of the five things the crafts page collects. The note that stood here
        # said `category`, `description` and `place` were omitted because "stage 1 already asks all
        # three of its own questions and asks them better", and each clause was answering a
        # question nobody asked:
        #
        #  * `place` was refused because `workshopSetup`'s four REQUIRED cover fields
        #    (state/district/block/village) "cannot be answered by a Craft's single free-text
        #    place, and would disagree with them". True — and the fix for a value that must not
        #    overwrite four others is its OWN box, which is what `craftPlace` is. It is the same
        #    shape as `tool.place` ("Place on the tool record") and `existingProduct.place`, both of
        #    which carry a record's free-text place beside the workshop's own answers precisely so a
        #    reader can see the record was documented somewhere else. Refusing it here while
        #    carrying it for tools and products was an inconsistency, not a policy.
        #  * `description` was refused because it "belongs to stage 4's `craftIntroduction`, a
        #    RICH_TEXT narrative a one-line taxonomy string would sit oddly inside". Also true, and
        #    also an argument for a separate box rather than for silence: `craftIntroduction` is
        #    what the DESIGNER writes about the cluster's craft as they found it, and the record's
        #    description is what a researcher wrote months earlier. Two authors must not share one
        #    box — the rule `documentedProcessNotes` was created under, for exactly this reason.
        #  * `category` was refused because it "has no counterpart on the cover at all", which
        #    describes the absence of a box as though it were a reason not to add one.
        #
        # So all three cross now, into three fields of their own, plus the provenance date every
        # other model carries. Nothing is overwritten and nothing shares a box with the designer.
        #
        # THE PHOTOGRAPH CROSSES TOO, and it is the one that was quietly dropped rather than
        # argued: `media_field="craftId"` has always been declared, so `_reference_photos` has
        # always resolved a craft's picture and handed it to this lambda, which named the parameter
        # `_photo` and threw it away. A craft record's own photograph reached no surface at all.
        data=lambda r, photo: {
            "craftName": r.name,
            "craftLocalName": r.localName,
            "craftCategory": r.category,
            "craftPlace": r.place,
            "craftDescription": r.description,
            "craftDocumentedOn": _iso_date(r.recordedAt),
            # WHERE THE CRAFT WAS DOCUMENTED, beside WHEN. `_rel` answers None for the common case
            # and that is correct rather than a gap: `Craft.workshopId` is nullable and the seeded
            # taxonomy rows belong to no workshop at all.
            "craftDocumentedAtWorkshop": _rel(r, "workshop", "title"),
            # Everything attached that is not the one still image below. A sentence, never the ids —
            # see `_media_note`.
            "craftMediaNote": _media_note("craft", _rel_obj(r, "media")),
            "craftPhoto": photo.id if photo else None,
            "craftPhotoCaption": photo.caption if photo else None,
        },
    ),
    # ── THE SIXTH MODEL: A QUESTIONNAIRE SITTING, WHICH IS THE ONLY CITABLE EVIDENCE ABOUT PEOPLE ──
    #
    # WHICH QUESTIONNAIRE, BECAUSE THERE ARE TWO AND THEY ARE DIFFERENT THINGS. This is the GLOBAL
    # artisan questionnaire, not the per-workshop custom `Questionnaire` a designer authors from the
    # .xlsx pro-forma. Five reasons, each independently sufficient:
    #
    #  1. THE CUSTOM FORM IS THE WORKSHOP'S METHOD, NOT THE CLUSTER'S EVIDENCE, and it is authored by
    #     the same person writing the report. The registry already has a home for it: stage 7's
    #     `surveyPlan.questionnaire` (RICH, required, "The questions to be asked") plus
    #     `questionnaireFile`. A reference exists to stop a designer retyping facts from a record that
    #     ALREADY HOLDS THEM; nobody retypes their own form, they upload it.
    #  2. IT IS ALREADY ATTACHED. `Questionnaire.designWorkshopId` points at `DesignWorkshop` with
    #     `onDelete: SetNull`. A REF field would be a second, competing attachment path with two
    #     writers and no arbitration — and only-fill-blanks would refuse to correct the loser.
    #  3. THE WORKSHOP-SCOPE MACHINERY WOULD SILENTLY LIE. `reference_options` passes
    #     `record.workshopId` — the link to the repository `Workshop`. `Questionnaire` has no
    #     `workshopId`; it has `designWorkshopId`, keyed on `DesignWorkshop.id`. A `workshop_where` on
    #     it would match nothing while `_reference_payload` reported `scoped: true` — a picker that
    #     reads as an empty repository, which is how a designer concludes the record was never made
    #     and types the whole thing in by hand.
    #  4. IT IS PRIVATE. `schema.prisma` records that the whole four-table custom design exists to
    #     stop "a designer's private sections" leaking into screens that are not about them, and a
    #     picker whose ALL fallback serves the whole table is exactly such a screen.
    #  5. IT IS UNREVIEWED — no `status`, no `reviewedById` — so `_review_flag` would have no verdict
    #     to print beside four reviewed records. `QuestionnaireInterview` has all four review columns.
    #
    # AND THE PICKABLE ROW IS THE SITTING, NOT THE FORM. `QuestionnaireSection.code` is `@unique` and
    # `sortOrder` is `@@unique` GLOBALLY, so there is exactly ONE global instrument and "choose the
    # questionnaire" is not a choice a designer can make. What they choose is a sitting: a title, a
    # date, a place, a language, a named set of artisans, a review status and its own `workshopId`.
    # The label must therefore say INTERVIEW and not QUESTIONNAIRE, or the designer thinks they are
    # picking a form.
    #
    # `questionnaire_forms.py` and its routes and schemas belong to the concurrent template-reuse
    # workflow. This model touches none of it.
    "QuestionnaireInterview": ReferenceModel(
        delegate="questionnaireinterview",
        # `title` ASCENDING, AND `interviewDate` DESCENDING IS A TRAP RATHER THAN A PREFERENCE.
        # `interviewDate` is NULLABLE and Postgres sorts NULLs FIRST under DESC, so ordering by
        # recency floats every undated interview to the top of the picker — the rows carrying the
        # least identifying information above the ones carrying the most. Every other model here
        # orders by its label column ascending; the date is in the sublabel, where it is read.
        order={"title": "asc"},
        search_fields=("title", "place", "language"),
        # WORKSHOP-SCOPED, ON THE PLAIN COLUMN, AND THE REPOSITORY ALREADY SETTLED THIS.
        # `record_filters.workshop_clause` serves "the questionnaire's interview scan" through the
        # plain-`workshopId` branch and says so in its own docstring.
        #
        # DO NOT COPY `_artisan_workshop_where`'s TWO-ARMED OR. Its two arms are two SPELLINGS OF ONE
        # FACT ("this artisan was documented at this workshop"), one of them a legacy route.
        # `{"artisans": {"some": {"artisan": {"workshopId": wid}}}}` would be a DIFFERENT FACT — "this
        # sitting covered somebody who is documented here" — and it would put an interview conducted
        # in 2023 in another state into this workshop's picker because one of its six artisans has
        # since been enrolled, inviting a designer to cite another cluster's sitting as their
        # fortnight's evidence. Note the direction the repository itself infers in:
        # `record_filters.artisan_workshop_clause` derives the ARTISAN from the interview's workshop,
        # never the interview's workshop from its artisans.
        #
        # THE LEGACY-NULL GAP IS REAL AND IS REPORTED, NOT HIDDEN. `workshopId` is nullable and every
        # interview recorded before that column has NULL, so those rows do not appear in a scoped
        # picker. The remedy is to set the workshop ON THE INTERVIEW — a column that exists and a form
        # that writes it — not to guess it from the artisan set. On a design workshop with no linked
        # `Workshop`, `reference_options` already falls back to the whole table and reports
        # `scoped: false` so the form can label the list truthfully.
        workshop_where=lambda wid: {"workshopId": wid},
        # FOUR RELATIONS, AND THE COST OF THE FIRST ONE IS STATED RATHER THAN GLOSSED.
        #
        # `responses` -> `question`: two extra indexed reads for the whole page
        # (`@@unique([interviewId, questionId])`, `@@index([questionId])`), issued as one
        # `WHERE interviewId IN (…)` bounded by `REFERENCE_LIMIT_MAX` — not an N+1. `question` is
        # needed only for the DENORMALISED `sectionCode`, so the nesting stops there.
        #
        #   THIS IS THE ONE INCLUDE IN THE REGISTRY WHOSE LOADED ROWS ARE CONFIDENTIAL. To COUNT the
        #   answers, Prisma loads them: `include` has no scalar `select` here, so `answerText` arrives
        #   in the API process and is discarded — a genuinely different cost from the `media` include's
        #   wide `extraMetadata`. The right follow-up is a `count_include`/`_count` facility on
        #   `ReferenceModel` so the answers never leave Postgres. It CANNOT be a third lambda parameter
        #   or an injection by `_reference_data`: `entry_provenance.canonical_divergence` calls
        #   `spec.data(rec, photo)` with exactly two arguments and re-fetches with `spec.include`, and
        #   a key that path cannot recompute is reported to an admin as `diverged` on every audit for
        #   ever — the failure `_media_note`'s docstring records.
        #
        # `artisans`: three columns, the cheapest join in the file, and it is here for a COUNT ONLY.
        # `media`: the count behind `interviewMediaNote`, and this is the model where that note matters
        # most — an interview's characteristic attachment is the AUDIO RECORDING of the sitting, and
        # `_reference_photos` resolves one IMAGE and no non-image row at all, so without the note the
        # recording exists on the record and nothing printed could say so.
        # `workshop`: for `interviewDocumentedAtWorkshop`. Safe by the same check `Craft.workshop`
        # passed — nothing at render time reads `row.workshop` (`_reference_place` reads `location` and
        # `place` and nothing else), so this include cannot change what an already-submitted document
        # prints. It is needed EVEN THOUGH the field is WORKSHOP-scoped, because the `scoped: false`
        # fallback serves the whole table on an unlinked design workshop, so an out-of-cluster sitting
        # CAN legitimately be picked and the printed row must then say where it came from.
        #
        # NO `location` INCLUDE, AND THIS IS THE MODEL WHERE THAT MATTERS MOST. `_reference_place`'s
        # own docstring uses this exact scenario as its example — "a researcher interviews six artisans
        # in one afternoon at a cooperative hall" — so reading the device fix would draw all six on the
        # hall. `place` is the free-text sitting place and is the right answer;
        # `_reference_place` returns `(row.place, "", "")` for every model but `Artisan`. An interview
        # also has no STATED address to carry: it is an event, not a residence, so the
        # artisan/product/tool pattern of four stated columns plus a subject pin has no analogue here.
        include={"responses": {"include": {"question": True}},
                 "artisans": True,
                 "media": True,
                 "workshop": True},
        # NO `media_field`, AND IT IS A DECISION RATHER THAN AN ABSENCE. `MediaFile
        # .questionnaireInterviewId` exists and is indexed, so `"questionnaireInterviewId"` COULD be
        # added to `_PHOTO_PARENT_COLUMNS` — and should not be. An interview's images are photographs
        # of named artisans mid-interview; the roster already carries each participant's portrait
        # through `participant.photo`, and `report_builder._images` dedupes by MEDIA ID so the two
        # would not collapse. Widening a raw-SQL allowlist for a picture the report does not need is
        # the wrong trade. `Process` is the precedent for a reference model with no photograph.
        #
        # NO `artisan_field` AND NO `filter_field`, SO THIS MODEL CANNOT BE CASCADED FROM — and that
        # is enforced rather than hoped for. `QuestionnaireInterview` has no `artisanId` column; the
        # link is `QuestionnaireInterviewArtisan`, a many-to-many, and the filter arm applies
        # `{column: parent_id}` — a scalar column name — so a nested `{"artisans": {"some": …}}` is not
        # expressible in this dataclass as it stands. The field therefore declares no `ref_filter_by`,
        # and a caller that sends one gets the 422 the code already raises rather than an unnarrowed
        # list it believes was narrowed. (Deliberately unlike the process/product cascade below, where
        # the parent column exists and is non-nullable.)
        label=lambda r: str(r.title or ""),
        sublabel=lambda r: _joined(_iso_date(r.interviewDate), r.place, r.language,
                                   _interview_artisan_phrase(r), _review_flag(r)),
        # ── A FLAT CITATION SUMMARY, AND THE HIERARCHY EXPLICITLY DOES NOT CROSS ──────────────────
        #
        # A reference carries a FLAT dict and a questionnaire is sections -> questions -> hundreds of
        # answers. Both ways of faking that shape are refused. Newline-joined lines (the `_step_lines`
        # trick) work for a process because a process has ~10 sub-steps describing a TECHNIQUE;
        # applied here they print an entire interview — every question and every answer — inside a
        # ministry report, on a permanent copy. A TAGS list reaches `format_value` as
        # `", ".join(...)`, the run-on line `_step_lines` was created to avoid. And `_reference_data`
        # flattens every string in the payload anyway, so structure would not survive the crossing
        # even if it were wanted.
        #
        # What crosses is therefore: what the sitting was, when, where, in what language, how many
        # people, how much of the instrument was answered, when it was last answered, what is
        # attached, and where the record came from. `interviewDate` and `interviewDocumentedOn` are
        # TWO DIFFERENT FACTS, exactly as `documentedOn` is elsewhere: when the sitting happened, and
        # when somebody typed it in.
        #
        # WHAT IS NOT HERE, so that each absence is a decision on the record. The five helpers above
        # carry the argument for the answers themselves. Also refused: the artisans' NAMES (a sitting
        # may cover artisans who are not on this roster); `artisanSetKey` (a `String? @unique` that a
        # "carry the scalars" instinct sweeps in without noticing — it is the sorted, comma-joined
        # list of ARTISAN IDS, a group re-identification key in one string, smuggling exactly the
        # roster the names refusal excludes); `notes` (unbounded free prose about a GROUP of which the
        # report may name one, so "the second weaver's daughter" can be neither attributed nor
        # redacted — unlike `Artisan.notes`, whose subject is the one person named on the row it lands
        # on); `status`/`reviewNotes`/`reviewedById`/`reviewedAt` (MUTABLE — live in the sublabel via
        # `_review_flag`, never written onto an entry); `QuestionnaireSectionStatus` (an
        # administrator's verdict on a NAMED artisan's data quality, and not reachable from this row in
        # any case); media ids and URLs (entitlement-gated per file — the FACT of the footage crosses
        # as `interviewMediaNote` and nothing else); the interviewer's identity as a field
        # (`hydrate_entries` already stamps `HydrationSource(author_id=row.createdById)`, which is who
        # SAVED the record and not a claim about who conducted the sitting — the distinction
        # `_measurement_method_note` exists to keep); and `extraMetadata`.
        data=lambda r, _photo: {
            "interviewTitle": r.title,
            "interviewDate": _iso_date(r.interviewDate),
            "interviewPlace": r.place,
            "interviewLanguage": r.language,
            "interviewArtisanCount": _interview_artisan_count(r),
            "interviewSectionsCovered": _interview_sections_covered(r),
            "interviewQuestionsAnswered": _interview_questions_answered(r),
            "interviewLastAnsweredOn": _interview_last_answered(r),
            "interviewMediaNote": _media_note("interview", _rel_obj(r, "media")),
            "interviewDocumentedOn": _iso_date(r.recordedAt),
            "interviewDocumentedAtWorkshop": _rel(r, "workshop", "title"),
        },
    ),
}


# ── A MODEL MAY BE CASCADED FROM ONE PARENT, AND THE SECOND DECLARATION IS REFUSED AT IMPORT ──────
#
# `ref_filter_by` names a SIBLING FIELD and the server never learns which model that sibling points
# at, so the filter column is a property of the MODEL. A model declaring both `artisan_field` and
# `filter_field` would therefore have to be resolved by a precedence rule invisible from the
# registry: the picker would narrow by the wrong parent, `_reference_payload` would report
# `filtered: true`, and the only symptom would be a designer choosing another record's child. At
# import rather than at the first call, for the reason `_FORMATS`' own check gives: every process
# that imports this module runs it, and only the tests run `validate_reference_carry`.
_TWO_PARENTS = sorted(
    model for model, spec in REFERENCE_MODELS.items() if spec.artisan_field and spec.filter_field
)
if _TWO_PARENTS:
    raise RuntimeError(
        "REFERENCE_MODELS " + ", ".join(_TWO_PARENTS) + " declare both artisan_field and "
        "filter_field; a model can be cascaded from ONE parent because ref_filter_by names a "
        "sibling field and never says which model it points at. Split the model or extend "
        "reference_options to take the parent's model from the registry."
    )


class _ProbeRow:
    """A record every column of which is empty, for asking a ``data`` lambda what keys it emits.

    Every lambda above is total in its keys — it returns the same dict shape whatever the row
    holds, with ``None`` for the columns nobody filled in — so calling one with this is a safe way
    to learn the key set without a database. ``__getattr__`` rather than a fixed set of attributes
    on purpose: a lambda that starts reading a new column must not make the probe raise, or the
    guard below would fail for the wrong reason and somebody would delete it.
    """

    def __getattr__(self, _name: str) -> None:
        return None


def reference_data_keys() -> dict[str, frozenset[str]]:
    """The key set each reference model's ``data`` lambda actually produces."""
    return {
        model: frozenset(spec.data(_ProbeRow(), None))
        for model, spec in REFERENCE_MODELS.items()
    }


def validate_reference_carry() -> list[str]:
    """The half of the carry ``stage_schema.validate_registry`` is structurally unable to check.

    THE TWO TABLES ARE ONE FEATURE AND ONLY ONE OF THEM WAS GUARDED. ``validate_registry`` refuses
    a hydration mapping whose TARGET is not a field of the entity, and its own comment explains why
    it stops there: the SOURCE key names a key of a ``REFERENCE_MODELS`` data lambda, which lives in
    this module, and ``stage_schema`` must not import it. So a typo on the source side — or a data
    lambda that stops producing a key, or a mapping written against a key that never existed —
    hydrated NOTHING, silently, on every save, for ever, and the first symptom was a blank column
    in a submitted document.

    This function is the missing half, and it lives here because here is the only place both
    tables are importable. It reports in both directions:

    * a mapping whose source key no reference model produces (the silent-nothing case), and
    * a data-lambda key that no mapping consumes (the ``localName`` case: ``ProductDocumentation``
      has produced a product's local name since the model was written, no mapping ever read it,
      and ``existingProduct`` had no box for it — three separate omissions that each looked like
      somebody else's job).

    The second is reported rather than raised because a produced-but-unmapped key is legitimate for
    a model whose picker shows it in a sublabel. Nothing does that today, so the test asserts an
    empty list; if a future model needs one, add it to ``_CARRY_EXEMPT`` with a reason rather than
    weakening the check.

    Returns a list of human-readable problems; empty means the two tables agree.
    """
    problems: list[str] = []
    produced = reference_data_keys()
    consumed: dict[str, set[str]] = {model: set() for model in REFERENCE_MODELS}

    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, field_key = path.partition(".")
        entity = next((e for _s, e in all_entities() if e.key == entity_key), None)
        spec = entity.field(field_key) if entity else None
        model = spec.ref_model if spec else ""
        if model not in REFERENCE_MODELS:
            # A ref pointing at a Dw… entity inside the same workshop, or a path
            # ``validate_registry`` has already reported. Not this function's business.
            continue
        consumed[model].update(mapping)
        for source_key in mapping:
            if source_key not in produced[model]:
                problems.append(
                    f"hydration {path}[{source_key!r}] reads a key that "
                    f"REFERENCE_MODELS[{model!r}].data never produces, so it copies nothing"
                )

    for model, keys in produced.items():
        orphans = sorted(keys - consumed[model] - _CARRY_EXEMPT.get(model, frozenset()))
        if orphans:
            problems.append(
                f"REFERENCE_MODELS[{model!r}].data produces {orphans}, which no hydration "
                "mapping consumes — the value is computed on every picker call and lands nowhere"
            )
    return problems


#: Data-lambda keys that are deliberately produced and deliberately not hydrated.
#:
#: Empty, and it should stay that way. It exists so that the day a model genuinely needs a key for
#: something other than hydration — a computed sublabel, say — the exemption is written down with a
#: reason instead of the check being loosened for everybody.
_CARRY_EXEMPT: dict[str, frozenset[str]] = {}


def _dw_entity(model: str) -> EntitySpec | None:
    """The registry entity a ``Dw…`` ref_model names, if it is one."""
    return next((e for _s, e in all_entities() if e.name == model), None)


async def reference_options(record: Any, model: str, *, scope: str = REF_SCOPE_ALL,
                            filter_by: str | None = None, search: str | None = None,
                            limit: int = REFERENCE_LIMIT_DEFAULT,
                            record_id: str | None = None,
                            viewer: Any = None) -> dict[str, Any]:
    """The options one REF picker shows, for the workshop ``record``.

    ``scope`` is the field's own :data:`REF_SCOPES` value, sent back by the client so the server
    and the form cannot disagree about how wide the net is. ``filter_by`` is the value of the
    field named by ``ref_filter_by`` — the chosen artisan, for a product picker.

    ── ``record_id``: THE PICKER'S ONLY WAY TO ANSWER A SCANNED CODE ─────────────────────────────

    Every other clause here searches by NAME (``spec.search_fields`` is a ``contains`` over prose
    columns and ``id`` is in none of them), so a record identified by its printed code could not be
    turned into an option at all: the designer scanning a colleague's product card got the same
    empty list as somebody searching for a record that was never made. ``record_id`` appends an
    ``id`` clause and nothing else — it does not replace the scope, the cascade or the search term,
    because a by-id lookup that quietly dropped the artisan filter would offer one artisan's work
    under another's name, which is the defect ``filter_by``'s own refusal above exists to prevent.

    ``viewer`` is the account asking, and it is what keeps the by-id path from becoming an
    existence oracle. See the read predicate below.
    """
    if scope not in REF_SCOPES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"scope must be one of {', '.join(sorted(REF_SCOPES))}",
        )
    take = max(1, min(int(limit or REFERENCE_LIMIT_DEFAULT), REFERENCE_LIMIT_MAX))
    wanted_id = (record_id or "").strip()

    entity = _dw_entity(model)
    if entity is not None:
        if filter_by:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{model} cannot be filtered by another record",
            )
        return await _in_record_options(record, entity, search, take, record_id=wanted_id)

    spec = REFERENCE_MODELS.get(model)
    if spec is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown reference model {model!r}; expected one of "
                f"{', '.join(sorted(REFERENCE_MODELS))} or an entity of this workshop"
            ),
        )

    clauses: list[dict[str, Any]] = []
    # THE READ PREDICATE, AND-COMPOSED THE WAY EVERY LIST ROUTE COMPOSES IT.
    #
    # `viewable_where` is empty today — every signed-in account may read every row, which is the
    # pooling philosophy its own docstring argues for — so this adds nothing to the query and the
    # picker behaves exactly as it did. It is here for the day that changes, and it is here
    # BECAUSE of `record_id`: the by-id probe further down has to ask "may this caller read this
    # row" with the SAME predicate `/artisans`, `/products` and `/tools` ask it with, or the two
    # surfaces would disagree about what exists and the narrower one would be the one lying.
    readable = await viewable_where(viewer)
    if readable:
        clauses.append(readable)

    # SCOPE FALLS BACK RATHER THAN EMPTYING THE PICKER. A design workshop need not be linked to
    # a Workshop record — the link is optional and is frequently made days after the capture
    # starts — and a WORKSHOP-scoped picker on an unlinked workshop would be permanently empty
    # with nothing on screen to explain why. The response says which of the two happened, so the
    # form can label the list "all documented artisans" instead of pretending it narrowed one.
    scoped = False
    workshop_clause: dict[str, Any] | None = None
    if scope == REF_SCOPE_WORKSHOP and spec.workshop_where and record.workshopId:
        workshop_clause = spec.workshop_where(str(record.workshopId))
        clauses.append(workshop_clause)
        scoped = True

    filtered = False
    if filter_by:
        # THE PARENT COLUMN COMES FROM THE MODEL, AND WHICH OF THE TWO IT IS DECIDES WHETHER THE
        # VALUE NEEDS RESOLVING. `artisan_field` is the artisan cascade, whose `filterBy` may be
        # either an `Artisan` id (stage 6) or a `DwParticipant` roster-entry id (stage 13);
        # `filter_field` is any other parent — `Process.productId` — whose id is already the id the
        # column holds. Sending a product id through `_artisan_id_behind` would appear to work (it
        # misses the `DwStageEntry` lookup and returns the candidate) while relying on two ids never
        # colliding, and its None branch would EMPTY a picker that says it is filtered. See the two
        # attributes' own comments on `ReferenceModel`.
        parent_column = spec.filter_field or spec.artisan_field
        if not parent_column:
            # A filter this model cannot honour is reported rather than ignored. Silently
            # dropping it would serve the whole table to a picker the designer believes is
            # narrowed to one artisan, and the wrong product would be chosen without a hint.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{model} cannot be filtered by another record",
            )
        parent_id: str | None = str(filter_by)
        if parent_column == spec.artisan_field:
            parent_id = await _artisan_id_behind(str(record.id), str(filter_by))
        if parent_id is None:
            # The filter names a roster entry that was typed in by hand, so there is no artisan
            # record and therefore no documented products to attribute to them. An empty list is
            # the honest answer; falling back to every product would invite the designer to
            # attach another artisan's work to this one.
            return _reference_payload(model, scope, scoped, True, [], truncated=False)
        clauses.append({parent_column: parent_id})
        filtered = True

    term = (search or "").strip()
    if term:
        # THROUGH `records.contains`, NOT A DICT WRITTEN OUT HERE. The two treatments it applies are
        # exactly the two this picker was missing: a pasted control byte is stripped rather than
        # reaching a `text` column as a 500, and `_` and `%` are ESCAPED rather than honoured as
        # wildcards. Typing an underscore here used to widen the list to the whole model — the
        # opposite of what a designer narrowing a picker is asking for, and it did it silently.
        clauses.append({"OR": [{f: contains(term)} for f in spec.search_fields]})

    if wanted_id:
        clauses.append({"id": wanted_id})

    # ── AND NOTHING ELSE: NO STATUS CLAUSE, AND THAT IS A DECISION RATHER THAN AN OMISSION ────
    #
    # The clauses built above — the read predicate, the workshop scope, the artisan cascade, the
    # search term and the scanned id — are the whole `where`. A REJECTED or NEEDS_REVISION record is
    # still offered, and it is offered because the pooling philosophy `records.viewable_where` is
    # written under applies here too — every signed-in account may read every row — and because a
    # rejected tool's measurements are still the measurements that were recorded. The designer is
    # choosing a row deliberately, in the room, and the verdict is now VISIBLE while they choose it:
    # see `_review_flag`, which appends it to the sublabel on every model that has a `status` column.
    #
    # IF THAT IS EVER REVERSED, THE EXCLUSION MUST BE REPORTED AND NOT SILENT. `_reference_payload`
    # already tells the client about `scoped`, `filtered`, `truncated` and `outOfScope` precisely so
    # that a shortened list never reads as an empty repository, and a status filter belongs in the
    # same payload. A picker that quietly drops rows is how a designer concludes the record was never
    # made and types the whole thing in by hand.
    where: dict[str, Any] = {"AND": clauses} if clauses else {}
    # take + 1, not a second COUNT: every search here is a case-insensitive `contains`, which
    # Postgres runs as ILIKE '%…%' and no index can answer, so counting the matches costs a
    # second scan of the largest table in the database to learn one boolean.
    rows = await getattr(db, spec.delegate).find_many(
        where=where, order=spec.order, take=take + 1, include=spec.include or None
    )
    truncated = len(rows) > take
    rows = rows[:take]

    # ── "NOT IN THIS WORKSHOP" AND "NO SUCH RECORD" ARE DIFFERENT ANSWERS AND USED TO READ ALIKE ─
    #
    # Five REF fields are WORKSHOP-scoped against an external model — `traditionalProcess.processRef`,
    # `processStep.processRef`, `existingProduct.artisanRef`, `existingProduct.productRef` and
    # `prototype.productRef` — and the workshop clause above is ANDed unconditionally. So a designer
    # who scans a product card another designer printed, for a product documented at a different
    # cluster, got an empty list: byte for byte the answer for a code that names nothing at all. The
    # two demand opposite next actions (ask the colleague to link the cluster, versus re-scan) and
    # the picker could not tell them apart, so it could not say either.
    #
    # THE SCOPE IS NOT WIDENED, AND THE PAYLOAD IS SHAPED SO THAT IT CANNOT BE WIDENED BY ACCIDENT.
    # The probe re-runs the SAME clauses with only the workshop clause removed, and the row it finds
    # comes back under its OWN key — `outOfScopeOption` — and NEVER inside `options`.
    #
    # THAT SPLIT IS THE WHOLE SAFETY OF THIS FEATURE, and it was learnt the expensive way: the first
    # cut of this code put the row in `options` and set a flag beside it. Every client in the tree
    # renders `payload.options` and nothing else — `StageReferenceField.tsx` maps it straight to the
    # list, and its "Nothing is documented under this design workshop's linked workshop yet" notice
    # is gated on `!payload.options.length`, so one probe row both APPEARED as an ordinary choice and
    # SILENCED the only sentence that would have questioned it. A designer scanning a colleague's
    # card for a product documented at another cluster would have seen exactly one option, tapped it,
    # and pointed the stage row at a cross-cluster record with nothing on screen having said so.
    # Out of band, the default is silence: a client that has not been taught the new key shows the
    # empty list and the notice it already had, which is the honest answer until a UI wave decides
    # what to offer. `scopedToWorkshop`, `filtered` and `truncated` exist for this family of reasons
    # — a list that is short for a knowable reason must say the reason — and this is one more of them.
    #
    # AND IT IS NOT AN EXISTENCE ORACLE. The probe is a `find_many` over a `where`, never a
    # `find_unique` on the primary key, and the clauses it keeps still include the `viewable_where`
    # predicate composed at the top. A row that predicate excludes produces no rows here, so
    # `outOfScope` stays False and the payload is identical to the one for an id that names nothing —
    # which is `records.require_record`'s 404-never-403 rule expressed as a query instead of a status
    # code (`frontend/lib/workshopCodeLookup.ts` explains what a distinguishable refusal buys an
    # attacker holding a stack of printed cards). Widening the probe to skip that predicate, or
    # answering it from a primary-key read, undoes the whole boundary.
    out_of_scope_row = None
    if wanted_id and not rows and workshop_clause is not None:
        # `truncated` is already False on this branch — the probe only runs when `rows` is empty —
        # so there is nothing to reset here.
        found = await getattr(db, spec.delegate).find_many(
            where={"AND": [c for c in clauses if c is not workshop_clause]},
            order=spec.order, take=1, include=spec.include or None,
        )
        out_of_scope_row = found[0] if found else None

    photos = await _reference_photos(
        spec,
        [r.id for r in rows] + ([out_of_scope_row.id] if out_of_scope_row is not None else []),
    )
    return _reference_payload(
        model, scope, scoped, filtered,
        [_reference_option(spec, row, photos) for row in rows],
        truncated=truncated,
        out_of_scope_option=(None if out_of_scope_row is None
                             else _reference_option(spec, out_of_scope_row, photos)),
    )


def _reference_option(spec: Any, row: Any, photos: dict[str, Any]) -> dict[str, Any]:
    """One row of a reference table as the picker's client reads it."""
    return {
        "id": row.id,
        "label": spec.label(row),
        "sublabel": spec.sublabel(row),
        # `_reference_data` and NOT `spec.data`: see its docstring for the formatted-prose
        # column that otherwise reaches this payload as raw JSON and lands in a table cell.
        "data": {k: v for k, v in _reference_data(spec, row, photos.get(row.id)).items()
                 if v not in (None, "")},
    }


def _reference_payload(model: str, scope: str, scoped: bool, filtered: bool,
                       options: list[dict[str, Any]], *, truncated: bool,
                       out_of_scope_option: dict[str, Any] | None = None) -> dict[str, Any]:
    """The picker's whole answer. Every flag on it exists so a short list can say WHY it is short.

    ``scopedToWorkshop``, ``filtered`` and ``truncated`` all describe the LIST in ``options`` —
    how it was narrowed, and each of them is routinely true with a full list. ``outOfScope`` is a
    different kind of statement and is the only one of the four that describes a row the field's
    own scope would NOT offer: a by-id lookup — a scanned card — found the record with the workshop
    clause lifted, so it is real and readable and this WORKSHOP-scoped field still excludes it.

    THAT ROW IS NOT IN ``options``. It is delivered beside them as ``outOfScopeOption``, because a
    client that renders ``options`` and knows nothing of the flag must not be able to show it as an
    ordinary choice — see the probe in :func:`reference_options` for the screen that produced.
    ``options`` is EMPTY whenever ``outOfScope`` is true, so an empty-list notice still fires.

    False is the ordinary answer, including for every by-id lookup that resolved inside the scope
    and for every id that resolved to nothing — see the same probe for why those last two must
    stay indistinguishable.
    """
    return {
        "model": model,
        "scope": scope,
        "scopedToWorkshop": scoped,
        "filtered": filtered,
        "truncated": truncated,
        # Derived here and nowhere else, so the flag and the row it describes cannot drift apart.
        "outOfScope": out_of_scope_option is not None,
        "outOfScopeOption": out_of_scope_option,
        "options": options,
    }


async def _artisan_id_behind(workshop_id: str, candidate: str) -> str | None:
    """The Artisan id a ``filterBy`` value stands for, following a roster entry if that is what
    it is.

    TWO KINDS OF ID REACH THIS PARAMETER and the form has no business knowing the difference.
    At stage 6 the artisan picker holds an ``Artisan`` id straight from the table. At stage 13
    the maker is chosen from the ROSTER, so the same-named ``artisanRef`` holds a
    ``DwParticipant`` entry id instead — and the product cascade hangs off it either way. Making
    the client resolve that would put a rule about the registry's internals into three
    codebases; resolving it here costs one indexed primary-key read.

    Returns None when the value is a roster entry with no artisan behind it, which is a real and
    ordinary state: an artisan who walked in on day two and was typed in by hand.
    """
    row = await db.dwstageentry.find_unique(where={"id": candidate})
    if row is None:
        return candidate
    if row.designWorkshopId != workshop_id:
        # An id from another workshop is not a mistake worth a 403 — it is a stale form — but it
        # must not be followed, or one workshop's roster would filter another's products.
        return None
    linked = (row.data or {}).get("artisanRef")
    return str(linked) if linked else None


#: THE MEASUREMENT-GRID MARKER, AND IT IS A CONTRACT WITH THREE CLIENTS RATHER THAN A CONSTANT.
#:
#: A grid-measurement capture is a photograph of the product or tool lying on a sheet of graph
#: paper, taken so a dimension can be read off it. It is a WORKING image and never a picture of the
#: subject, and because both record forms upload it before anything else it is the oldest image row
#: on its parent — which is the row :func:`_reference_photos` picks. So the uploading client writes
#: ``extraMetadata.purpose = "MEASUREMENT_GRID"`` on the media row and the server SORTS any
#: candidate carrying it LAST. It does not exclude one, and this line said "excludes" while the
#: statement's own docstring said "NOTHING IS EVER EXCLUDED INTO A BLANK": a product whose ONLY image
#: is a grid shot must still hydrate that picture rather than an empty gallery. (That sentence used to
#: be located here as "fifteen lines further down", which was wrong by sixty — a distance is a line
#: pin with the digits spelled out, and it rots the same way for the same reason. Name the sentence;
#: it is greppable and a count is not.) The web record forms, the handset's grid section and the statement below must
#: spell the marker IDENTICALLY: the symptom of a mismatch is not an error but a report that prints
#: a ruled sheet as the photograph of a tool, which is the failure nobody noticed for a year.
#:
#: IT IS INTERPOLATED INTO RAW SQL, so it is a module constant and must stay one. If it ever has to
#: come from config or from a request, bind it instead — :func:`_reference_photos` vets it on every
#: call for exactly that day, and the guard there says what to do.
MEASUREMENT_GRID_PURPOSE = "MEASUREMENT_GRID"

#: The parent foreign keys :func:`_reference_photos` is allowed to group by.
#:
#: It interpolates the column name into SQL, so the name must come from here and not from the
#: caller. Every value is also a ``media_field`` in :data:`REFERENCE_MODELS`; the guard below is
#: what makes adding a model there fail loudly rather than interpolate an unreviewed name.
_PHOTO_PARENT_COLUMNS = frozenset({"artisanId", "craftId", "productId", "toolId"})


async def _reference_photos(spec: ReferenceModel, ids: list[str]) -> dict[str, ReferencePhoto]:
    """One photograph per record — AND ITS CAPTION — in one query rather than one per row.

    ONE PER PARENT, NOT ONE BUDGET SHARED BETWEEN THEM. This used to be a plain ``find_many`` with
    ``order={"createdAt": "asc"}, take=len(ids) * 4`` — a single ceiling across every id — so a
    roster of forty artisans read at most 160 image rows for all forty, and a handful of
    long-documented artisans carrying twenty or thirty pictures each consumed the lot. The artisans
    whose photographs were taken most recently then hydrated with ``photo`` empty, the report
    printed a roster with faces missing for people whose portraits sat one join away, and re-saving
    the stage never fixed it because the same rows won the budget every time. Nothing warned
    anybody, which is why it survived: the document renders cleanly either way.

    ``DISTINCT ON`` is what Postgres offers for exactly this shape and it is one round trip, which
    matters because all three callers — the reference picker, ``hydrate_entries`` on the save path,
    and the report's ``load_report_references`` — are on a link measured at 756ms a hop. The
    ``createdAt, id`` tiebreak keeps the answer STABLE: two records photographed in the same second
    would otherwise swap portraits between two renders of the same report.

    THE OLDEST IMAGE ON A MEASURED PRODUCT OR TOOL IS A SHEET OF GRAPH PAPER, AND IT WAS WINNING.
    The stability argument above says nothing about SUBJECT MATTER, and for a year nothing else did
    either — this query's only filter was ``mediaType = 'IMAGE'``. Both record forms upload the
    grid-measurement frames FIRST, in their own awaited loop, before the numbered process captures
    and before the batch of field photographs, so the grid frame is deterministically the oldest
    image row on its parent. ``ToolDocumentation.data``'s ``photo``/``photoCaption`` — and through
    ``REFERENCE_HYDRATION`` the ``tool.photo`` gallery and ``existingProduct.productPhotos`` — were
    therefore seeded with the calibration shot, captioned "Length & breadth grid (measurement) for
    Pit loom", while the catalogue photographs the researcher uploaded sat one row later in the same
    table, unused. A ministry officer read a ruled measurement sheet presented as a photograph of
    the tool, and it was the thumbnail every picker showed as well.

    THE MARKER IS STRUCTURAL AND IT IS A THREE-SURFACE CONTRACT: the uploading client writes
    ``extraMetadata.purpose = "MEASUREMENT_GRID"`` on the media row and this statement SORTS any
    candidate carrying it LAST — see "NOTHING IS EVER EXCLUDED INTO A BLANK" below, which this
    paragraph flatly contradicted while saying "excludes any candidate carrying it". A reader who
    took the earlier wording as the design would have written the marker into the ``WHERE``, and a
    record whose only image is a grid shot would then hydrate an empty gallery. The three writing
    surfaces already describe the behaviour correctly — ``GridMeasurement.tsx`` says "the server
    SORTS any candidate carrying it LAST" and ``ProductForm``/``ToolForm`` say "MARKED SO IT SORTS
    LAST AND NEVER OUTRANKS A REAL PHOTOGRAPH" — so it was only the server's own prose that was
    wrong, which is the direction that costs the most, because the server is where a reader goes to
    settle it.

    The two web record forms and the handset's grid section all write the marker; the
    spelling is :data:`MEASUREMENT_GRID_PURPOSE` and it must not be "tidied", because a marker only
    one end writes is not a marker. ``measurementImageId`` is deliberately NOT the signal, which was
    the first proposal: no shipped web path writes that column at all — ``GridMeasurement.tsx``
    analyses the raw file and never creates a ``MediaFile`` — so keying on it would have been inert
    on the very surface the defect lives on.

    THE CAPTION AND FILENAME CLAUSE IS TRANSITIONAL AND IS NOT A SECOND DESIGN. Every grid frame
    already in the table was written before the marker existed and carries no ``purpose``; the only
    things that distinguish it are the caption the form composes and the filename the capture
    control generates. Both are strings a researcher could also type by hand, which is exactly why
    they are the fallback and not the rule. Delete this clause once the pre-marker rows are gone.

    NOTHING IS EVER EXCLUDED INTO A BLANK. The exclusion is a SORT KEY and not a ``WHERE``: grid
    shots sort last, so a record whose ONLY image is a grid shot still gets that picture instead of
    an empty gallery, chosen among the grid shots by the same ``createdAt ASC, id ASC`` as before —
    the stability guarantee above survives intact in that case too.

    THE DIRECTION IS THE WHOLE OF IT AND ``ASC`` IS NOT A TIDY DEFAULT. Postgres sorts ``false``
    before ``true``, so ``is_grid ASC`` is what puts the grid frame last; ``is_grid DESC`` would
    make it win on EVERY measured record deterministically — the original defect, promoted from an
    accident of upload order to a rule.

    THIS DOES CHANGE WHAT AN ALREADY-SUBMITTED REPORT PRINTS, AND AN EARLIER VERSION OF THIS
    PARAGRAPH FLATLY DENIED IT. It said "already-saved entries are not touched", on the reasoning
    that this function runs only at save time and ``report_builder.ReferencedRecord`` is the frozen
    copy. Both halves are wrong. This function has a THIRD caller and it is on the render path:
    ``_load_one_reference_model`` -> ``load_report_references`` -> ``attach_report_references``, so
    ``ReferencedRecord.photo`` is RE-RESOLVED every time a document is generated. ``ReferencedRecord``
    is that re-resolution, not the freeze — what is frozen is the row's OWN gallery, ``tool.photo``
    and ``existingProduct.productPhotos``, which ``hydrate_entries`` filled at save time.

    THE CONSEQUENCE, WHICH IS A SECOND PICTURE AND NOT A REPLACED ONE. For any row hydrated BEFORE
    this change, the gallery holds the frozen GRID media id while this statement now answers the
    CATALOGUE id. ``report_builder.ReportBuilder._images`` collects both — its PASS ONE takes the
    row's media fields and its PASS TWO takes ``reference.photo`` — and dedupes with
    ``wanted.setdefault(reference.photo, …)``, i.e. BY MEDIA ID. Two different ids do not collapse,
    so re-rendering a report already handed to an officer prints the graph-paper sheet AND the
    catalogue photograph on the same row where it used to print the graph paper alone.

    IT IS STILL RIGHT NOT TO BACKFILL. Rewriting the frozen gallery is the prose-level edit that
    ``REFERENCE_HYDRATION``'s note forbids, so those rows keep the grid shot until the designer
    clears the box and re-picks. Whether ``_images`` should SUPPRESS a REF photograph when the row's
    own gallery already holds a picture of the same parent record is a report_builder decision and
    is handed to its owner; until it is answered, the pairing above is the known behaviour of a
    re-render and not a surprise.

    THE CAPTION TRAVELS WITH THE PICTURE and is selected in the same statement rather than in a
    second read — it is a column of the row already being chosen, so it is free. See
    :class:`ReferencePhoto` for why it was missing and what that cost the designer. Nothing else
    about the MediaFile crosses: the URL and the object key are entitlement-gated
    (``records._MEDIA_TAKEABLE_KEYS``) and the workshop resolves its own through ``media_resolver``
    at render time, which is the only path allowed to hand a file out.
    """
    if not spec.media_field or not ids:
        return {}
    column = spec.media_field
    if column not in _PHOTO_PARENT_COLUMNS:
        # Never reached from REFERENCE_MODELS as it stands. It is here so that a model added with a
        # new media_field fails loudly on the first call instead of interpolating an unreviewed
        # name into the statement below.
        raise ValueError(f"Unsupported reference photo column: {column}")
    if not MEASUREMENT_GRID_PURPOSE.isidentifier():
        # THE SECOND INTERPOLATION, HELD TO THE SAME STANDARD AS THE FIRST, and it was not for one
        # revision: the marker went into the statement as an f-string three lines under the guard
        # above, so the file interpolated two things and vetted one. Safe today because the marker
        # is a module constant that nothing else writes — and the moment somebody moves it to
        # config, to a request or to a per-deployment override, "safe today" stops being a property
        # of the code and becomes a property of a comment. `isidentifier()` admits exactly the
        # SCREAMING_SNAKE token the three uploading clients write and refuses anything holding a
        # quote, a space or a semicolon. Binding it as `$2` would be better still and is deliberately
        # not done here: two `query_raw` stubs in `tests/test_entry_provenance.py` pin the
        # two-argument call shape, and turning eighteen unrelated hydration tests red to tidy an
        # interpolation is the wrong order to do it in. Bind it in the edit that widens those stubs.
        raise ValueError(f"Unsafe measurement-grid marker: {MEASUREMENT_GRID_PURPOSE!r}")
    rows = await db.query_raw(
        f'WITH candidate AS ('
        f'SELECT m."{column}" AS parent, m."id" AS id, m."caption" AS caption, '
        f'm."createdAt" AS created_at, ('
        # INTERPOLATED, AND VETTED FIRST — see the marker guard above, which is why. This is the
        # statement's SECOND interpolation and it went in unchecked, three lines under a guard whose
        # whole reason for existing is to stop unvetted strings reaching this SQL.
        f'COALESCE(m."extraMetadata"->>\'purpose\', \'\') = \'{MEASUREMENT_GRID_PURPOSE}\' '
        f'OR COALESCE(m."caption", \'\') LIKE \'% grid (measurement) for %\' '
        f'OR m."originalFilename" LIKE \'grid-%\' '
        f'OR m."originalFilename" LIKE \'measure-grid-%\''
        f') AS is_grid '
        f'FROM "MediaFile" m '
        f'WHERE m."mediaType" = \'IMAGE\'::"MediaType" AND m."{column}" = ANY($1::text[])'
        f') '
        f'SELECT DISTINCT ON (parent) parent, id, caption FROM candidate '
        f'ORDER BY parent, is_grid ASC, created_at ASC, id ASC',
        ids,
    )
    return {
        str(row["parent"]): ReferencePhoto(
            id=str(row["id"]), caption=str(row.get("caption") or "").strip()
        )
        for row in rows
        if row.get("parent")
    }


async def _in_record_options(record: Any, entity: EntitySpec, search: str | None,
                             take: int, *, record_id: str = "") -> dict[str, Any]:
    """Options for a ref that points INSIDE this workshop — a sketch, a prototype, a roster row.

    Always scoped to the workshop whatever the field's declared scope says, because there is no
    other reading of a ``DwSketch`` reference: the sketches of a different workshop are not
    candidates for this one's prototypes, and offering them would produce a report whose
    prototype table cites drawings that appear nowhere in it.

    ``record_id`` narrows to one row and is honoured rather than ignored, because a parameter the
    server accepts and drops is how a caller comes to believe a list was narrowed when it was not.
    It NEVER reports ``outOfScope``: the sentence above is the reason — a row belonging to another
    workshop is not a candidate this field is refusing on a technicality, it is not a candidate at
    all, so there is nothing for the form to offer to do about it.

    IT MATCHES EITHER IDENTIFIER, BECAUSE THE CODE GRAMMAR ISSUES BOTH. ``workshopCodeIdForRow``
    in ``frontend/lib/workshopCodes.ts`` prints the row's ``_clientKey`` when the row has not
    reached the server yet — a prototype tag has to be printable the afternoon the prototype is
    made, and a workshop can go a fortnight without signal — and ``workshopCodeMatchesRow`` beside
    it matches on either. An ``id``-only lookup would therefore answer half the tags ever printed
    with the empty list that is byte-identical to "no such record", which is the exact ambiguity
    ``record_id`` exists to remove. The ``OR`` is ANDed with the workshop, entity and
    not-deleted clauses, and ``(designWorkshopId, entityKey, clientKey)`` is unique, so it can
    match at most the one row either spelling names.
    """
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": record.id, "entityKey": entity.key, "deletedAt": None}
        | ({"OR": [{"id": record_id}, {"clientKey": record_id}]} if record_id else {}),
        order={"ordinal": "asc"},
    )
    label_key = entity.label_field or next(
        (f.key for f in entity.fields if f.is_free_text), ""
    )
    # A second printable field, so two prototypes both called "Bag" are told apart by their code.
    sub_key = next(
        (f.key for f in entity.fields
         if f.key != label_key and f.is_free_text and not f.deprecated), ""
    )
    term = (search or "").strip().casefold()

    options: list[dict[str, Any]] = []
    for row in rows:
        data = dict(row.data or {})
        label = str(data.get(label_key) or "").strip() or entity.title
        sublabel = str(data.get(sub_key) or "").strip() if sub_key else ""
        if term and term not in label.casefold() and term not in sublabel.casefold():
            continue
        options.append({"id": row.id, "label": label, "sublabel": sublabel, "data": {}})

    truncated = len(options) > take
    return _reference_payload(entity.name, REF_SCOPE_WORKSHOP, True, False,
                              options[:take], truncated=truncated)


@dataclass(slots=True)
class PendingEntry:
    """One entry of a stage save, between validation and the write.

    It exists so hydration can happen ONCE for the whole payload — a stage 3 save carries thirty
    participants, and looking each artisan up as its row is reached would be thirty round trips
    inside a request a designer is waiting on with one bar of signal.
    """

    entity: EntitySpec
    data: dict[str, Any]          # the cleaned values, mutated in place by hydration
    previous: dict[str, Any]      # what the row held before this save; empty for a new row
    row_id: str | None
    ordinal: int
    client_key: str | None
    #: THE RESERVED KEY TO WRITE ONTO AN EXISTING ROW THAT DOES NOT CARRY IT YET, or ``None`` to
    #: leave the row's key alone. ``client_key`` above is what a CREATE stores; this is the only
    #: way an already-stored row ever gains one, because the UPDATE branch otherwise writes four
    #: columns and none of them is this. See :func:`_reserved_key_upgrade`.
    adopt_client_key: str | None = None
    #: What the row's ``fieldProvenance`` held before this save; empty for a new row.
    previous_provenance: dict[str, Any] = dataclass_field(default_factory=dict)
    #: WHICH KEYS HYDRATION ACTUALLY WROTE ON THIS SAVE, and where each came from. Filled by
    #: :func:`hydrate_entries` as it writes, and read by ``merge_entry_provenance`` immediately
    #: afterwards to attribute those fields to the CANONICAL RECORD's author rather than to the
    #: designer who picked it. It has to be recorded here rather than inferred later because the
    #: information is gone the instant the value lands: a hydrated name and a typed name are the
    #: same string in ``data``, which is the whole reason field-level provenance was unanswerable
    #: on this table before. Reset per save, never persisted.
    hydrated: dict[str, "entry_provenance.HydrationSource"] = dataclass_field(default_factory=dict)


def _clear_cascade_orphans(entries: list[PendingEntry]) -> None:
    """Drop a cascaded child's copied values when its PARENT was re-pointed and the child is gone.

    ── THE ROW THIS EXISTS TO STOP BEING WRITTEN ────────────────────────────────────────────────
    Stage 5's `processRef` is narrowed by `productRef` (`ref_filter_by`), so changing the product
    clears the process — that is the cascade, and both clients do it. The save that follows carries
    `productRef=B` and no `processRef` at all, and `hydrate_entries` used to skip a blank ref
    entirely (`if not ref_id: continue`), so the clear-and-rewrite that pops a re-pointed ref's
    targets was never reached. What got stored was:

        documentedFor              product B      (rewritten by `traditionalProcess.productRef`)
        documentedProcessName      product A's process
        documentedProcessNotes     product A's process
        documentedSteps            product A's process
        preProcessAvailable        product A's process
        recordMediaNote            product A's process
        documentedOn               product A's process
        processRef                 null

    i.e. stage 5's substantive narrative describing A's process under B's product name, with no ref
    left to re-resolve it by. `processStep` got the same treatment on its `name` — a REQUIRED
    TABLE_COLUMN that prints in the report's step table — and on its `description`.

    THIS IS WORSE THAN THE STALENESS IT REPLACED, WHICH IS WHY IT IS A DEFECT AND NOT A TRADE.
    Before `productRef` existed, `documentedFor` had exactly ONE writer, so all seven boxes went
    stale TOGETHER and the row stayed internally consistent — a true description of A's process,
    merely not of the row's current pick. Adding a second writer to one of the seven made the row
    contradict itself, and nothing can flag it: `entry_provenance.canonical_divergence` only checks
    fields that carry a `reference` stamp, the surviving stamps still name process A and still
    re-resolve to exactly the values stored, and `coerce_value` checks type and length and never
    coherence. An audit that looks at every box and reports nothing is the worst available outcome.

    ── THE CONDITION IS DELIBERATELY NARROW, AND EACH CLAUSE EXCLUDES A CASE THAT MUST NOT FIRE ──
    A child field with a `ref_filter_by`, blank now, naming a record before, whose parent is NOW
    NON-BLANK AND DIFFERENT. That is exactly the state in which a second writer will rewrite one of
    the child's targets in this same save, and:

    * A PLAIN UNLINK IS UNTOUCHED — the parent has not moved, so nothing fires. `StageReferenceField`
      states that rule deliberately ("Only the reference is cleared. The name, village and phone it
      filled in STAY: they are what the designer confirmed in the room"), and it is not weakened
      here: a designer unlinking a duplicate artisan keeps the participant's name.
    * A CLEARED PARENT IS UNTOUCHED. `productRef` blank hydrates nothing, so `documentedFor` is not
      rewritten and the row is uniformly stale rather than self-contradictory — which is the
      pre-existing, documented behaviour and not this function's business.
    * A CLIENT THAT DOES NOT KNOW THE PARENT FIELD CANNOT TRIP IT. `validate_entry` drops blank keys,
      so "cleared" and "never sent" are the same absence in `data` and no guard can tell them apart.
      Requiring the parent to be NON-BLANK now is what makes that undecidable case harmless: a build
      older than `productRef` sends no such key, the parent reads blank, and nothing fires.

    ── WHY IT IS A SEPARATE PASS AND NOT A BRANCH IN THE LOOP BELOW ─────────────────────────────
    Hydration walks fields in DECLARATION order and `productRef` is declared immediately before
    `processRef` in both stage-5 entities. Popping inside that walk would run AFTER the parent had
    already written `documentedFor=B` and would pop it straight back out, leaving the box the
    designer's own pick had just answered EMPTY. Clearing everything first and letting the ordinary
    loop write afterwards is order-independent: the parent fills the box whether it was re-pointed
    (clear-and-rewrite) or merely unchanged (only-fill-blanks, and the box is now blank).

    It also has to run BEFORE `hydrate_entries`' `if not wanted: return`, because the payload that
    clears BOTH refs resolves no records at all and would otherwise leave the whole stale set
    standing.

    ── WHAT IT LEAVES FOR THE DESIGNER, SAID PLAINLY ────────────────────────────────────────────
    A REQUIRED target can end up blank: `processStep.name` is required, and there is no new record to
    refill it from — the designer has to re-pick the process, which is what both clients' cascade
    notice already tells them to do ("the previous choice was cleared — pick one from the new list").
    `validate_entry` has already run by the time hydration writes, so a SUBMIT carrying this shape
    stores the blank rather than refusing it. That is the recoverable direction and the same one
    `coerce_value`'s rejected-hydration rule takes: a blank box is visible on the form, counts
    against the completeness score, and is refused by the next submit, whereas a name belonging to
    another product's process is invisible and prints. The `replaced` branch below has always had
    this property; it simply never showed, because a re-point always had a record to rewrite from.

    Galleries and other multi-valued targets are exempt, matching that branch: they hold the
    photographs the designer took at the workshop and there is no second copy of those anywhere.

    ── IT IS GENERAL, AND THE FOUR CASCADED CHILDREN ARE NOT ALL THE SAME SHAPE ──────────────────
    Written off `ref_filter_by` rather than against stage 5, so the population is whatever the
    registry declares — today `traditionalProcess.processRef`, `processStep.processRef`,
    `existingProduct.productRef` and `prototype.productRef`, pinned as a list by
    `cascade-process-product-unit.spec.ts`. Three of the four are the shape described above: the
    parent is itself a second writer of one of the child's boxes (`documentedFor` twice,
    `existingProduct.artisanName` once), so the half-done cascade produced a row that contradicted
    itself. `prototype.productRef` is the fourth and its parent writes nothing, so its stale
    `productName` was merely stale — and it is popped anyway, because "Developed from: <a product
    documented for the artisan this row no longer names>" is the same wrong attribution one step
    removed, and a rule that fired on three of four cascades would be a rule nobody could state.
    """
    for item in entries:
        for spec in item.entity.fields:
            mapping = REFERENCE_HYDRATION.get(f"{item.entity.key}.{spec.key}")
            if not mapping or not spec.ref_filter_by:
                continue
            if item.data.get(spec.key):
                continue
            if not str(item.previous.get(spec.key) or ""):
                continue
            parent_now = str(item.data.get(spec.ref_filter_by) or "")
            if not parent_now or parent_now == str(item.previous.get(spec.ref_filter_by) or ""):
                continue
            for target_key in mapping.values():
                target = item.entity.field(target_key)
                if target is None or target.type.is_multi:
                    continue
                # Deprecated targets are cleared too, for the reason the `replaced` branch gives:
                # refusing to put NEW data into a retired field is not a reason to keep another
                # record's data there, and the value still travels in `data`.
                item.data.pop(target_key, None)
                # AND ITS PROVENANCE STAMP WITH IT. Leaving the stamp would attribute whatever lands
                # in the box next — the parent's rewrite, or a value the designer types — to the
                # recorder of a record this row no longer names, and would leave
                # `canonical_divergence` re-resolving a field against a record the row dropped.
                item.previous_provenance.pop(target_key, None)


def _has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, dict)):
        return bool(value)
    return True


async def hydrate_entries(entries: list[PendingEntry]) -> None:
    """Copy each chosen reference's display fields onto the entry that names it.

    See the long note above :data:`REFERENCE_HYDRATION` for why the copy exists at all. This
    function is only about WHEN it is written, and there is exactly one case in which it
    overwrites something:

    * A row that already named a DIFFERENT record and now names this one has every mapped field
      rewritten — INCLUDING the fields the new record leaves blank, which are cleared rather than
      left holding the previous record's answer. Leaving the old artisan's name beside the new
      artisan's id is the one outcome worse than either alternative: the report and the research
      data would then name two different people for the same row, and nothing on screen would say
      which was meant. Half-rewriting is that same outcome one field at a time, and it is the
      shape the failure actually took for a year: the id and the name moved, the phone number and
      the photograph did not.
    * EVERYWHERE ELSE ONLY BLANKS ARE FILLED — including on a brand-new row. What the client
      sent is what the designer typed or accepted in the room: a name the artisan prefers, a
      village the master record has wrong, a price agreed on the day. Treating a first save as a
      "change" and overwriting it would mean the picker silently reverted every correction a
      designer made after choosing from it, which is a worse failure than retyping, because the
      designer watches the value change back and has no way to make it stick.

    A gallery is never overwritten, only seeded when empty: the documented product's photograph
    is a starting point, and replacing the photographs a designer took at the workshop with it
    would destroy the only copy of them that exists.

    A reference whose record has been deleted hydrates nothing and is left exactly as it is —
    which is the whole point of having copied the fields in the first place.

    A CASCADED CHILD THAT THE PARENT'S MOVE CLEARED IS THE ONE BLANK REF THAT DOES NOT MEAN "LEAVE
    IT ALONE", and :func:`_clear_cascade_orphans` runs first to deal with it. A blank ref hydrates
    nothing, so without that pass the row kept every value copied from the record it no longer names
    WHILE the parent rewrote one of them — see that function for the row it produced.

    EVERY WRITE IS RECORDED ON ``item.hydrated`` AS IT HAPPENS, naming the record and column the
    value came from and that record's author. This is the only moment at which a hydrated value is
    distinguishable from a typed one — a second later they are the same string in ``data`` — and it
    is what lets ``entry_provenance.merge_entry_provenance`` attribute the field to the canonical
    record's recorder rather than to the designer who chose it from the picker. It records only
    what is actually stored: a value ``coerce_value`` rejects is not written and is not stamped, so
    the provenance map can never claim authorship of a field that stayed blank.
    """
    # FIRST, AND BEFORE THE EARLY RETURN BELOW — see :func:`_clear_cascade_orphans` for why the
    # order and the placement are both load-bearing.
    _clear_cascade_orphans(entries)

    wanted: dict[str, set[str]] = {}
    for item in entries:
        for spec in item.entity.fields:
            if spec.type is not FieldType.REF or spec.ref_model not in REFERENCE_MODELS:
                continue
            if f"{item.entity.key}.{spec.key}" not in REFERENCE_HYDRATION:
                continue
            ref_id = item.data.get(spec.key)
            if ref_id:
                wanted.setdefault(spec.ref_model, set()).add(str(ref_id))
    if not wanted:
        return

    resolved: dict[str, dict[str, dict[str, Any]]] = {}
    # THE CANONICAL RECORD'S AUTHOR, CAPTURED BESIDE ITS DATA. ``model_spec.data`` is a display
    # projection and deliberately does not carry ``createdById`` — nothing printed in a report
    # should — but a hydrated field's provenance IS that column, so it is read off the row here
    # while the row is in hand. Reading it later would mean a second query over records this
    # function has already loaded.
    authors: dict[str, dict[str, str | None]] = {}

    async def _load(model: str, ids: set[str]) -> tuple[str, list[Any], dict[str, Any]]:
        """One model's rows and their photographs. Two round trips, and the second needs the first."""
        model_spec = REFERENCE_MODELS[model]
        rows = await getattr(db, model_spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=model_spec.include or None
        )
        return model, rows, await _reference_photos(model_spec, [r.id for r in rows])

    # THE MODELS ARE INDEPENDENT OF ONE ANOTHER, SO THEY OVERLAP. Each one is a genuine two-step —
    # the photographs are fetched by the ids the first read returns — but nothing in one model's
    # pair depends on another's, and awaiting them model by model made this 2 x (however many
    # reference models a workshop's entries name) sequential cross-region round trips. Run together
    # it is two waits deep whatever that number is. ``REFERENCE_MODELS`` is small and each entry
    # holds at most 2 reads in flight, so the fan-out stays inside ``pool_width()``.
    for model, rows, photos in await gather_reads(
        *(_load(model, ids) for model, ids in wanted.items())
    ):
        model_spec = REFERENCE_MODELS[model]
        # `_reference_data` and NOT `model_spec.data`, matching the picker payload above — the
        # two must resolve a record identically or the boxes that fill in at the keyboard disagree
        # with the ones the server writes at save.
        resolved[model] = {
            row.id: _reference_data(model_spec, row, photos.get(row.id)) for row in rows
        }
        authors[model] = {row.id: getattr(row, "createdById", None) for row in rows}

    for item in entries:
        for spec in item.entity.fields:
            mapping = REFERENCE_HYDRATION.get(f"{item.entity.key}.{spec.key}")
            if not mapping:
                continue
            ref_id = item.data.get(spec.key)
            if not ref_id:
                continue
            source = resolved.get(spec.ref_model, {}).get(str(ref_id))
            if source is None:
                continue
            was = str(item.previous.get(spec.key) or "")
            replaced = bool(was) and was != str(ref_id)
            if replaced:
                # THE PREVIOUS RECORD'S VALUES ARE CLEARED FIRST, BEFORE ANYTHING IS COPIED IN.
                #
                # The overwrite promised by this function's docstring — "a row that already named a
                # DIFFERENT record and now names this one has every mapped field rewritten" — used
                # to be applied field by field inside the loop below, which skips a source value
                # that is blank. So it rewrote only the fields the NEW record happens to have
                # filled in, and every field the new record leaves blank kept the OLD record's
                # answer. Pick a fully documented artisan, notice the phone belongs to a different
                # Sita Devi, re-point the row at a thinly documented one, and the row is stored as
                # artisan B's name and craft beside artisan A's phone, village, gender and
                # PHOTOGRAPH — with `artisanRef` naming B, so nothing can ever re-resolve it. That
                # participant table goes into a .docx submitted to a ministry.
                #
                # Clearing here rather than writing blanks in the loop because "the new record has
                # nothing to say about this field" and "the new record says it is empty" are the
                # same thing on the wire: `REFERENCE_MODELS[...].data` returns None for a column
                # nobody filled in, and `coerce_value` would reject the None anyway.
                #
                # THE MULTI ARM IS DELIBERATELY EXEMPT, matching the gallery rule below: a gallery
                # is seeded when empty and never overwritten, because it holds the photographs the
                # designer took at the workshop and there is no second copy of those anywhere.
                #
                # This is the rule the handset already applies — see `hydrationPatch` in
                # DwReferenceField.kt, whose comment is "Leaving it would attach the old artisan's
                # phone number to the new artisan's name" — so the server is being brought into
                # line with the client that got it right, not inventing a policy.
                for target_key in mapping.values():
                    target = item.entity.field(target_key)
                    if target is None or target.type.is_multi:
                        continue
                    # Deprecated targets are cleared too, even though the loop below refuses to
                    # WRITE to them. Refusing to put new data into a retired field is not a reason
                    # to keep a different person's data there: the value still travels in `data`.
                    item.data.pop(target_key, None)
                    # AND SO IS ITS PROVENANCE. The value being cleared belonged to the PREVIOUS
                    # record, and leaving its stamp behind would attribute the incoming record's
                    # value — or a blank the designer then fills in — to the recorder of a record
                    # this row no longer names. That is the same class of defect as leaving the old
                    # artisan's phone number beside the new artisan's name, one layer down.
                    item.previous_provenance.pop(target_key, None)
            for source_key, target_key in mapping.items():
                value = source.get(source_key)
                if value in (None, ""):
                    continue
                target = item.entity.field(target_key)
                if target is None or target.deprecated:
                    continue
                existing = item.data.get(target_key)
                if _has_value(existing) and (not replaced or target.type.is_multi):
                    continue
                if target.type.is_multi and not isinstance(value, (list, tuple)):
                    value = [value]
                # Through the registry's own coercion, so a money string lands two-place, an
                # integer arrives as an integer, and a value the target field cannot legally
                # hold — a product type that is not one of the workshop's categories — is
                # dropped rather than written. A rejected hydration leaves the field blank for
                # the designer to answer, which is recoverable; a token no client can render is
                # not.
                cleaned, error = coerce_value(target, value)
                if error or cleaned is None:
                    continue
                item.data[target_key] = cleaned
                item.hydrated[target_key] = entry_provenance.HydrationSource(
                    model=spec.ref_model,
                    record_id=str(ref_id),
                    source_key=source_key,
                    author_id=authors.get(spec.ref_model, {}).get(str(ref_id)),
                )


# --------------------------------------------------------------------------------------
# Prefill: the designer's profile, copied into a brand-new workshop
#
# THE ONE FACT THIS SECTION TURNS ON: THE PERSON WHOSE PROFILE IS COPIED AND THE PERSON DOING THE
# COPYING ARE NOT THE SAME PERSON, AND FOR MONTHS THIS CODE ASSUMED THEY WERE.
#
# `assert_can_create_design_workshops` is {ADMIN, MASTER_ADMIN} — a DESIGNER may not open a
# workshop at all, deliberately, because a workshop is the container a fortnight of records lives
# in and the unit the ministry funds. The only caller of the seed passes the account that pressed
# create, so it always passed an ADMIN. Requirement 3 says the Designer Page is master data
# pre-filled into every report; what actually happened is that the Designer Page's twenty-one
# columns, its CV upload, its signature capture and the first-login redirect built to drive
# designers to it fed NOTHING, and every report named the admin who opened the workshop.
#
# `DesignWorkshopCreate.designerUserId` closes it by naming the designer at creation, and the three
# functions below are the halves of that: ASK whether this account may be named, PUT them on the
# workshop, and COPY THEIR profile rather than the caller's.
#
# ── AND SINCE 2026-08-27, A WORKSHOP IS NAMED FOR SEVERAL DESIGNERS AT ONCE ─────────────────────
#
# The owner's ask: "Designer this workshop is for should be a multi-select … the design workshop
# would only be visible to those particular designers, admins and master admins would be able to see
# all the design workshops." The VISIBILITY half of that was already the shipped rule and is not
# touched by any of this — see `load_workshop_or_404` above and `visible_to_clause` in
# `design_workshop_viewers`. What was missing was the writing of the rows: an admin had to create the
# workshop and then remember the viewers panel, and forgetting left a designer facing a 404 they
# could not tell apart from a workshop that does not exist.
#
# TWO QUESTIONS THAT ONLY LOOK LIKE ONE, AND `named_designer_team` IS WHERE THEY ARE SEPARATED:
#
#   WHO MAY OPEN IT   -> several accounts -> one `DesignWorkshopViewer` row each.
#   WHOSE NAME IS ON IT -> exactly one account -> the profile the seed copies, `designerName`,
#                          the certification signature, and the .docx's `dc:creator`.
#
# The second stays singular because the artefacts it feeds are singular: `dc:creator` is not a list
# and `stage_definitions` declares ONE designer block. Making that block repeatable moves
# `registry_version()` — every handset treats its bundled 119 KB schema asset as stale and every
# existing workshop's completeness moves — which is a wave of its own and the owner's call.
# --------------------------------------------------------------------------------------


def named_designer_team(
    lead_id: str | None, everyone_ids: Sequence[str] | None
) -> tuple[str | None, list[str]]:
    """Read a create body's two designer fields into (the LEAD, everyone who gets a row).

    PURE, AND SEPARATED FROM THE ROUTE ON PURPOSE. Every branch below is a body some client
    actually sends, and each of them used to be decided by an inline expression that could only be
    checked by creating a workshop against a live Postgres. ``tests/test_workshop_designer_naming``
    pins them without one.

    BLANK IS ABSENT, EVERYWHERE. ``""`` — an empty picker, a cleared field, an offline draft that
    carried the key but never got an answer — means "nobody named", not "an account whose id is the
    empty string". Without that, an empty string reaches the eligibility check and 422s the create
    with "No account exists with this id: ", which names nothing and is unactionable on a form
    where the field is optional.

    THE LEAD IS ``lead_id`` WHEN THE BODY SENT ONE, AND OTHERWISE THE FIRST TICKED NAME. The
    fallback is not a guess dressed up as a rule: a client that ticked three designers and named no
    lead still has to have SOME profile seeded, and the only other candidate is the ADMIN who
    pressed create — which is precisely the wrong-name-on-a-ministry-document defect
    ``designerUserId`` exists to end. First-ticked is at least a choice a human made, it is what
    both clients show as the lead line, and an admin who wanted a different one sends ``lead_id``.

    ORDER IS FIRST-SEEN AND DUPLICATES COLLAPSE. The lead is always the first element of the
    returned list — a body naming a lead who is not in ``everyone_ids`` (a client that sends the two
    fields from two different pieces of state) grants them anyway, because the workshop is FOR them
    and a lead who cannot open their own workshop is the worst outcome available here.

    THE CREATOR IS NOT DROPPED HERE, and could not be: this function is not told who they are. An
    admin naming THEMSELVES is a legitimate answer to both questions — their profile is still what
    the seed copies — and the two places that do care both know the id. The create route subtracts
    them from the set it hands the ELIGIBILITY rule (their standing is not that list's business,
    exactly as ``_deduplicate`` has it on the viewers PUT), and ``attach_the_named_designer``
    refuses to write a viewer ROW for them (their access comes from ``createdById``, and a second
    source of truth for it is one an admin could "remove" without anything changing).
    """
    ordered: list[str] = []
    seen: set[str] = set()
    lead = (lead_id or "").strip() or None
    if lead:
        ordered.append(lead)
        seen.add(lead)
    for raw in everyone_ids or ():
        candidate = (raw or "").strip()
        if candidate and candidate not in seen:
            ordered.append(candidate)
            seen.add(candidate)
    if lead is None and ordered:
        lead = ordered[0]
    return lead, ordered


async def assert_every_designer_may_be_named(user_ids: set[str]) -> None:
    """Raise the viewers screen's own 422 if any of these accounts may not run this workshop.

    **THE RULE IS IMPORTED AND NEVER COPIED**, exactly as ``design_workshop_grants`` and
    ``design_workshop_access`` import it. ``_assert_every_id_may_be_granted`` reads the DESIGNER
    empanelment roster AND the platform allow-list, exempts the break-glass master through
    ``deps.is_break_glass_master`` itself, and answers with a sentence naming the screen that fixes
    each refusal. A second copy of that here is how somebody comes to be named as a workshop's
    designer while their next sign-in refuses them — one screen saying they are on the workshop and
    another saying they cannot get in, with nothing connecting the two.

    IT IS A PRIVATE NAME IN ANOTHER MODULE, which is a coupling taken on deliberately and for the
    same reason ``design_workshop_grants`` takes it on: this is a two-read security decision with
    four worded refusals in it, not a pattern a file can restate correctly. If ``design_workshop_
    viewers`` ever gives it a public name, use that and delete this wrapper.

    **WHY IT IS THE GRANT RULE AND NOT A NARROWER "MUST BE A DESIGNER" TEST.** ADMIN is inside
    ``DESIGN_WORKSHOP_ROLES`` precisely so that admins can run workshops of their own, so an admin
    who is the practising designer of a cluster is a legitimate answer here and a role test spelled
    ``== "DESIGNER"`` would refuse them. More importantly, a second eligibility rule beside the one
    the viewers screen enforces is a second rule to drift: this call and the grant that follows it
    must agree, and the only way they cannot disagree is to be the same function.

    CALLED BEFORE THE WORKSHOP ROW IS CREATED. A 422 raised afterwards would leave a committed
    orphan draft behind on every retry until somebody noticed the list filling up with untitled
    duplicates — the same failure the seed's blanket ``except`` exists to prevent, arrived at from
    the other end.

    **A SET, AND ONE CALL FOR THE WHOLE OF IT — NEVER ONE CALL PER ID.** This took a single
    ``user_id`` until the workshop gained a multi-select, and the plural spelling is not a
    convenience: the rule refuses the whole set and names EVERY account it objected to, stacking the
    two restorable refusals (an empanelment that lapsed, an allow-list bar) so an admin learns about
    both before walking to another screen. Called in a loop it would raise on the first bad id and
    say nothing about the second, so an admin who ticked four designers and is told about one has
    been sent on the first of two trips — the exact round trip those sentences are worded to save.
    It is also the difference between one query and N: the rule reads the user table, the designer
    roster and the access roster, and the set is what bounds that.

    An empty set is a no-op, so a body that named nobody costs no query at all.
    """
    await _assert_every_id_may_be_granted(user_ids)


async def attach_the_named_designer(
    workshop_id: str, designer_id: str, *, granted_by_id: str, creator_id: str
) -> bool:
    """Put the named designer on the workshop. Answers whether a row was actually written.

    **NAMING A DESIGNER AND GRANTING THEM ACCESS ARE ONE ACT, AND SPLITTING THEM WAS THE COST.**
    Today an admin creates the workshop and then has to remember to open the viewers panel and tick
    the designer; forgetting the second step leaves a designer who cannot open the workshop whose
    stage 1 already carries their name, and the only symptom is a 404 they cannot distinguish from a
    workshop that does not exist. One call, two steps, no second thing to remember.

    ``add_one_viewer`` AND EMPHATICALLY NOT ``replace_viewers``. The whole-set replace deletes
    whatever it did not read, so using it to add one person deletes a viewer row a concurrent
    join-card redemption created and resurrects one an admin has just removed. Both sibling writers
    of viewer rows — a decided access request and a redeemed join card — already funnel through this
    one statement for that reason, and a third hand-written insert into the one table that confers
    access is how the three come to disagree about a column.

    **THE CREATOR IS NOT A VIEWER**, so an admin naming THEMSELVES as the designer writes no row and
    this answers ``False``. Their access comes from ``createdById``; a viewer row for them would be a
    second, redundant source of truth for access they already hold, and one they could "remove" from
    the viewers screen without anything changing. ``_deduplicate`` drops them on the admin PUT path
    and ``decide`` has the same branch on the request path; this is the third place that rule has to
    hold, and it holds by the same test rather than by a comment. Note that their PROFILE is still
    what the seed copies — being the creator does not stop somebody being the designer.

    NOT IN A TRANSACTION WITH THE WORKSHOP CREATE, and the exposure is worth stating rather than
    hiding: the workshop row is already committed by the time this runs, so a driver-level failure
    here answers 500 and leaves a workshop the named designer cannot open. That is recoverable from
    the viewers panel in two clicks and is visible — the admin gets an error, and the panel shows an
    empty team. The alternative, wrapping the create and the grant together, would have to take the
    seed in with it (a dozen writes behind a blanket ``except`` that must never fail a create) or
    leave the ordering harder to read than the failure it prevents.
    """
    if designer_id == creator_id:
        return False
    await add_one_viewer(
        db,
        workshop_id=workshop_id,
        user_id=designer_id,
        granted_by_id=granted_by_id,
        # NO CARD AND NO REQUEST DECIDED THIS. An administrator named them on the create, so
        # ``tokenId`` stays NULL — that column's schema comment promises it is "NULL for every row
        # an admin made by hand", and this is such a row.
    )
    return True


async def attach_the_named_designers(
    workshop_id: str,
    designer_ids: Sequence[str],
    *,
    granted_by_id: str,
    creator_id: str,
) -> list[str]:
    """Put every named designer on the workshop. Answers with the ids a row was written for.

    A LOOP OVER :func:`attach_the_named_designer`, AND EMPHATICALLY NOT ``replace_viewers``, which
    is the plural-shaped function standing right beside it and is the wrong one. The whole-set
    replace DELETES whatever it did not read: used here it would destroy a viewer row a concurrent
    join-card redemption had just created, and resurrect one an admin had just removed. Every
    sibling writer of this table — a decided access request, a redeemed card, the singular naming
    above — funnels through ``add_one_viewer`` for that reason, and a fourth spelling is how the
    four come to disagree about a column. The set here is at most a hundred ids and is written once
    per workshop in its whole life, so the loop costs nothing worth trading the property for.

    NOT IN A TRANSACTION WITH THE WORKSHOP CREATE — the singular version says so and the exposure
    MULTIPLIES here rather than changing shape. The workshop row is already committed when this
    runs, so a driver-level failure on the third of four ids answers 500 and leaves a workshop two
    of its four designers cannot open.

    **AND IT IS DELIBERATELY NOT WRAPPED IN A BLANKET ``except``.** That would turn a create which
    could not honour the body it was given into a silent 201: the admin sees a workshop, the
    designers see nothing, and no screen anywhere connects the two — the same class of failure as
    the wrong name on the report, where every automatic check agreed the outcome was fine. Raising
    is recoverable in two clicks from the viewers panel and is VISIBLE. What is added instead is a
    log line naming what did land, because the admin's error message cannot: without it, an
    operator reading the 500 cannot tell a workshop with no designers from one with three of four.
    """
    granted: list[str] = []
    try:
        for designer_id in designer_ids:
            if await attach_the_named_designer(
                workshop_id,
                designer_id,
                granted_by_id=granted_by_id,
                creator_id=creator_id,
            ):
                granted.append(designer_id)
    except Exception:
        logger.exception(
            "design workshop %s: naming its designers failed part way through. Rows were written "
            "for %s of %s named; the workshop exists and the rest must be added from the viewers "
            "panel. Granted: %s",
            workshop_id,
            len(granted),
            len(designer_ids),
            ", ".join(granted) or "(none)",
        )
        raise
    return granted


async def seed_designer_prefill(
    record: Any,
    actor: Any,
    *,
    designer_id: str | None = None,
    extra: Mapping[str, Any] | None = None,
) -> Any:
    """Start a new workshop with the DESIGNER's profile already in stage 1 and stage 3.

    Returns the workshop header, updated if a promoted column was seeded, so the caller can
    serialise it without a second read.

    ══ TWO ACCOUNTS, AND CONFLATING THEM WAS THE DEFECT ═════════════════════════════════════════

    ``designer_id`` NAMES THE ACCOUNT WHOSE PROFILE IS COPIED. ``actor`` IS THE ACCOUNT DOING THE
    COPYING. This function took ONE account and used it for both, and its only caller passes the
    account that pressed create — which the route's gate guarantees is an ADMIN, because a DESIGNER
    may not open a workshop. So requirement 3's "the designer's master profile, pre-filled into
    every report" pre-filled the ADMIN's details into every report: onto the cover's "Designer" row,
    into the certification block's signatory line, into the .docx's own ``dc:creator`` and
    ``cp:lastModifiedBy``, and — through the promoted ``designerName`` column — into the workshop
    list. **And every automatic check in the product agreed the document was correct**, because
    ``designerName`` was not MISSING, it was FILLED WITH THE WRONG PERSON: completeness scored 100%,
    the readiness screen was green, ``build_report`` emitted no warning, and the only detector left
    anywhere was a human reading a stranger's name in a box labelled "Designer". A designer who
    never opened stage 3 — where nineteen of the twenty-one fields live — submitted somebody else's
    biography, phone number, address, empanelment number, photograph and signature without ever
    seeing the boxes.

    ``designer_id`` IS ``None`` DOWN THREE DIFFERENT ROADS, and then this behaves exactly as it did
    before — the actor's profile, byte for byte. That is what makes the change additive rather than
    a flag day. The three roads are worth naming, because they used to be one:

    * The admin ANSWERED "not decided yet". A workshop is opened in a room on day one and who will
      run it is often not settled; the picker offers that answer rather than forcing a guess.
    * The create happened OFFLINE, where the eligibility picker cannot be reached at all —
      eligibility is two roster reads on the server and no useful part of it is answerable on the
      device.
    * The client PREDATES the field. Both clients send it as of 2026-08-26 (the web create form and
      its offline draft store; Android's create body and its sync arm), but an older APK in the
      field does not, and a handset is updated when its owner next has signal and a reason to.

    Until 2026-08-26 this paragraph also said the gap was "what is NOT fixed yet", because the field
    was server-only and no client could send it — so the wrong-name defect stood on every real
    request. It no longer does; what stands is the three cases above, in all of which copying the
    creator's profile is the intended answer. ``tests/test_designer_roster.py`` pins both halves by
    name — the named-designer path and the names-nobody path — against a real database.

    **THERE IS NO FALLBACK FROM ``designer_id`` TO ``actor``, AND THE MISSING ``or`` IS THE POINT.**
    If the named designer has never filled in a profile, the designer block is written EMPTY.
    ``prefill_from_profile(designer_id or actor.id)`` — one plausible extra word — would restore
    exactly the defect this parameter removes, in the case where it is hardest to notice: an admin
    who picked a designer off a list and got their own name back. An empty ``designerName`` is a
    required Basic-tier stage-1 field, so it is counted by the completeness score and named in
    ``build_report``'s warnings; the admin's name in that box is counted as complete and warned
    about by nothing. A blank a machine can see beats a confident wrong name no machine can — which
    is Rule 10 discharged by machinery that already exists, rather than by a new banner.

    **THE STAMP AND ``createdById`` STAY WITH THE ACTOR, AND THAT IS A DECISION, NOT AN OVERSIGHT.**
    ``entry_provenance`` has exactly two sources — ``reference`` (a value copied off a record
    somebody else recorded) and ``designer`` (a person working on this workshop set it) — and the
    sentence declaring "two" is pinned word for word between ``FieldProvenance.tsx`` and Android's
    ``DwFieldStampDto``. A value copied out of somebody else's profile at an admin's request is
    honestly NEITHER, and minting a third source is a two-client change that is the owner's call.
    Of the two answers available, stamping the ACTOR is the only one that is not a fabrication: the
    admin caused this write, and the designer has never seen these values. Stamping the DESIGNER
    would put their name under twenty-one fields they have not read, on a document going to a
    ministry — the same manufactured audit trail ``merge_entry_provenance`` refuses to create for an
    unstamped legacy field, arrived at from the other end. The consequence a reader should expect is
    that the designer opens stage 1 and sees the ADMIN's name and today's date in small grey type
    under their OWN name. That is true, and it reads as an invitation to check the box.

    **WRITTEN AS ORDINARY STAGE ENTRIES, WHICH IS THE WHOLE DESIGN.** The alternative — teaching
    the report builder to fall back to the profile when ``designerName`` is blank — would put a
    special case into the one component that must stay a pure function of the registry and the
    data, and it would put it in three places, because the phone renders the same report offline
    from its own copy of that builder. Here the values are simply *there*: the form shows them,
    the designer can correct them for this workshop only, the completeness score counts them, the
    .docx prints them, and nothing anywhere knows a profile was involved.

    **AND THEY ARE COPIES.** See ``designers.prefill_from_profile``: a report is a historical
    document, and a designer who moves institution in 2027 must not retroactively change the
    workshop they ran in 2026.

    Prefill NEVER fails the create. A designer who cannot start a workshop because the seeding of
    a convenience value went wrong has lost the whole feature to help with; the workshop is
    already committed by the time this runs, and an empty stage 1 is a minor inconvenience the
    designer can type past.

    ``extra`` IS THE CREATE FORM'S OWN ANSWERS, AND IT IS SEEDED HERE RATHER THAN IN A SECOND
    WRITE BECAUSE THEY LAND IN THE SAME ENTITY — ``workshopSetup`` holds ``designerName`` and
    ``craftName`` alike, and two creates for one singleton would be two rows where the matcher
    expects one.

    ``POST /design-workshops`` accepts craft, cluster, state, district and the two dates, and used
    to copy them straight onto the ``DesignWorkshop`` COLUMNS with no stage entry behind them —
    making the create route a second writer of columns whose single writer is supposed to be
    ``promoted_values``. The FIRST stage-1 save then erased every one of them: ``touched_entities``
    gains ``workshopSetup`` for any entry naming it, answered or not; the web sends a read stage's
    singleton whether or not it holds anything; and ``_coerce_promoted`` nulls a promoted column
    of a touched entity whose value is blank. A designer who typed "Ikat / Barpali / Odisha /
    Bargarh" into the create form, opened stage 1, typed the venue and pressed Save watched
    craftName, clusterName, state, district, startDate, endDate, scheme, implementingAgency,
    sponsor and workshopCode all go to NULL under a 200 reading "Stage saved" — the workshop then
    invisible to every list filter and search on craft, state, district and date, and showing "—"
    in the list's own columns, for the whole fortnight of capture, repairing itself only when
    stage 1 was finally completed.

    Seeding the ENTRY closes both halves and adds no third writer: the columns now have something
    behind them, stage 1 opens with those boxes already filled in (nothing else fills them — the
    stage form does not read the header), and a later save merges with them rather than
    contradicting them. ``extra`` wins over a profile value of the same key, because it is what the
    person opening the workshop typed thirty seconds ago — said as "the designer" here until
    ``designer_id`` made the two different people, and the sentence was then a claim rather than a
    turn of phrase. The two sets do not overlap today.
    """
    try:
        values: dict[str, Any] = {
            # ``designer_id or actor.id`` PICKS WHOSE PROFILE, AND NOTHING BELOW EVER FALLS BACK.
            # Read the docstring's "no fallback" paragraph before adding a second ``or`` further
            # down: a named designer with no profile must leave the designer block EMPTY, because a
            # blank required field is visible to the completeness score and to the report warnings
            # while somebody else's name in it is visible to nobody.
            **(await prefill_from_profile(designer_id or actor.id)), **dict(extra or {})
        }
        if not values:
            return record
        header: dict[str, Any] = {}
        for spec in stages():
            for entity in spec.entities:
                if entity.cardinality is not Cardinality.SINGLETON:
                    continue
                # WHICH STAGE EACH KEY BELONGS TO IS ASKED OF THE REGISTRY, never hard-coded.
                # Writing "WORKSHOP_SETUP"/"workshopSetup" here would mean that the day somebody
                # moves the designer block into its own stage — a plausible edit, the registry is
                # explicitly designed to be reorganised — prefill would silently write entries
                # under a stage key no form reads, and every designer would quietly go back to
                # retyping their biography with nothing in the logs to say why.
                known = {f.key for f in entity.fields if not f.deprecated}
                subset = {k: v for k, v in values.items() if k in known}
                if not subset:
                    continue
                clean, refused = validate_entry(entity, subset, enforce_required=False)
                if refused:
                    # THE ERROR MAP IS LOGGED, NOT DISCARDED, BECAUSE THIS IS THE ONE
                    # `validate_entry` CALLER WITH NOWHERE TO RETURN IT. Everywhere else the map
                    # becomes the response's `errors` and the person who typed the value is told
                    # which box was refused; prefill's "sender" is the designer's own profile, saved
                    # minutes or months ago behind a 200 that has already been answered, so a value
                    # refused here vanished completely — `clean` simply lacked the key, the entry
                    # was written without it, and the designer opened a stage whose phone box was
                    # empty while their Designer Page went on showing them the number.
                    #
                    # THE DIVERGENCE THIS CATCHES IS REAL AND IS ALREADY WRITTEN DOWN, and this log
                    # line is deliberately NOT a fix for it. `DesignerProfileUpdate` is looser than
                    # three of the stage boxes `PREFILL_MAP` seeds — phone (40 characters and no
                    # `PHONE_IN`, seeding a box bounded at 20 that declares it), email (no bound at
                    # all, seeding 180) and pincode (no `PINCODE` check, seeding a box that declares
                    # one) — recorded with its consequence in `KNOWN_PREFILL_GAPS` in
                    # tests/test_designer_prefill_contract.py, pinned in BOTH directions so an entry
                    # cannot outlive its gap. Narrowing those three bodies would 422 the next save
                    # of every stored row already holding a looser value, which is why that comment
                    # calls closing the gap an owner call with the existing rows to answer for.
                    # Making the loss VISIBLE is not an owner call, and until somebody makes the
                    # other one this is the only trace a dropped prefill value leaves anywhere.
                    #
                    # The reasons are safe to log: no `*_error` reachable from `PREFILL_MAP`'s
                    # targets echoes the value it refused, so this names the box and the rule, never
                    # the designer's phone number.
                    logger.warning(
                        "Designer prefill dropped %d value(s) on %s.%s for workshop %s: %s",
                        len(refused), spec.key, entity.key, getattr(record, "id", None),
                        "; ".join(f"{k}: {v}" for k, v in sorted(refused.items())),
                    )
                if not clean:
                    continue
                await db.dwstageentry.create(data={
                    "designWorkshopId": record.id,
                    "stageKey": spec.key,
                    "entityKey": entity.key,
                    "ordinal": 0,
                    "data": _json(clean),
                    # THE RESERVED KEY, BECAUSE THIS IS THE OTHER WRITER OF SINGLETON ROWS AND THE
                    # ONE THAT RUNS FIRST. `save_stage` is where the key is documented, but this
                    # create is what puts `workshopSetup` — the singleton carrying the promoted
                    # columns — into essentially every new workshop, and a row seeded without the
                    # key is a row `@@unique([designWorkshopId, entityKey, clientKey])` cannot see
                    # for the workshop's whole life: Postgres treats NULLs as distinct, and no
                    # later save rewrites a key it did not have to. The invariant the schema
                    # states is "one row per (workshop, entity)", and an invariant with one
                    # unkeyed writer is not an invariant.
                    "clientKey": singleton_client_key(spec.key),
                    # STAMPED TO THE ACTOR AS A DESIGNER VALUE, not as a reference one, and — since
                    # `designer_id` arrived — not to the person whose profile it came from either.
                    # The docstring's provenance paragraph carries the whole argument; the short
                    # version is that `entry_provenance` declares exactly TWO sources, that sentence
                    # is pinned word for word against Android, and a value lifted out of somebody
                    # else's profile at an admin's request is honestly neither of them. Of the two
                    # answers that exist, the ACTOR is the only one that is not a fabrication: they
                    # caused this write, and the designer has not seen these values. `source:
                    # "reference"` is reserved for a value copied off a RECORD somebody else
                    # recorded, which is a different question about a different table. Leaving these
                    # unstamped would instead hand them to whichever co-designer next saves stage 1
                    # without changing them.
                    "fieldProvenance": _json(entry_provenance.merge_entry_provenance(
                        previous={}, previous_data={}, new_data=clean,
                        hydrated={}, user=actor,
                    )),
                    "createdById": actor.id,
                })
                # Only columns this subset actually filled, and only the string-valued ones.
                # `_coerce_promoted` is deliberately NOT reused: it nulls every promoted column of
                # a touched entity, which here would blank the workshop's own title on creation.
                for column, value in promoted_values(entity.key, clean).items():
                    # THE TWO DATE COLUMNS ARE NAMED AND SKIPPED, and they are named rather than
                    # inferred because a DATE field coerces to an ISO *string* ("2026-08-15") —
                    # which sails through `isinstance(value, str)` and lands on a Prisma DateTime
                    # column as text. That is a driver error, not a validation one: it would be
                    # swallowed by the `except` below and leave a workshop whose stage entries
                    # were half seeded and whose header was not written at all. `_coerce_promoted`
                    # parses them for the stage-save path; here there is nothing to parse for,
                    # because the create route wrote both columns from the same request through
                    # `_parse_date` before calling us.
                    #
                    # THE TWO NAMES ARE STILL THE WHOLE LIST, THOUGH NO LONGER FOR THE REASON THIS
                    # COMMENT USED TO GIVE. It said "only `extra` can put a date in reach — the
                    # profile carries none", and the profile now carries one: `PREFILL_MAP` maps
                    # `empanelmentDate` -> `designerEmpanelmentDate` (services/designers.py:244) and
                    # `prefill_from_profile` narrows the Postgres DateTime to an ISO date string on
                    # the way out (designers.py:297, a branch that exists only for it), so a date
                    # does reach `values` above from the profile. What keeps the list complete is
                    # the OTHER end: `designerEmpanelmentDate` is not a promoted column at all, and
                    # all 13 entries of `PROMOTED_COLUMNS` (services/stage_schema.py:841) are keyed
                    # `workshopSetup.*` — so `promoted_values` can never surface it and no ISO
                    # string of it can reach a Prisma DateTime column.
                    #
                    # SO THE RULE TO APPLY BEFORE PROMOTING ANYTHING NEW, whatever its source: any
                    # promoted column whose registry field is DATE must be named here. Adding one to
                    # `PROMOTED_COLUMNS` and not to this tuple is what produces the driver error
                    # above — swallowed, and read by the designer as an empty stage 1.
                    if column in ("startDate", "endDate"):
                        continue
                    if isinstance(value, str) and value:
                        header[column] = value[:220]
        if header:
            return await db.designworkshop.update(where={"id": record.id}, data=header)
        return record
    except Exception:
        # Anything from a Prisma error to a registry key that has been renamed out from under the
        # profile map. The designer gets an empty stage 1 instead of a 500 on a workshop that was
        # already created — which, without this, would leave an orphan draft behind on every
        # retry until somebody noticed the list filling up with untitled duplicates.
        logger.exception("Designer prefill failed for workshop %s", getattr(record, "id", None))
        return record


# --------------------------------------------------------------------------------------
# Saving a stage
# --------------------------------------------------------------------------------------

#: THE RESERVED CLIENT KEY THAT MAKES THE DATABASE ENFORCE "ONE ROW PER (WORKSHOP, ENTITY)".
#:
#: A singleton entity — `designBrief`, `outcomes`, `reportSettings`, eleven more — and the reserved
#: `_custom` container are each supposed to have exactly ONE row per workshop, and until this
#: constant existed nothing but Python enforced it. The `@@unique([designWorkshopId, entityKey,
#: clientKey])` index could not: Postgres treats NULLs as DISTINCT under a unique index, so any
#: number of rows with a null `clientKey` coexist happily, and the web sets no client key at all.
#:
#: SO THE UNIQUENESS WAS A READ-THEN-WRITE, AND THE TWO ARE SECONDS APART. `save_stage` reads the
#: stage's rows, finds no singleton, and inserts one; on a link where a round trip measures 756ms
#: that window is wide enough for a second designer on the same workshop — which
#: `DesignWorkshopViewer` exists to allow — to do exactly the same. Two rows, and after that the
#: damage is not a duplicate but a NONDETERMINISM: `entry_rows` returns them in no guaranteed order,
#: and completeness, `assemble_workshop_data` and the stage payload each take last-write-wins over
#: that order. So which of the two answers is scored, printed in the .docx and shown on the form can
#: differ BETWEEN TWO READS of the same unchanged data, and half the fieldwork lives in a row nothing
#: ever updates.
#:
#: WRITING A NON-NULL SENTINEL IS WHAT HANDS THE PROBLEM TO THE INDEX ALREADY DECLARED. A partial
#: index on `clientKey IS NOT NULL` would have been the textbook answer and is the wrong one here:
#: `test_stage_sync.test_many_rows_without_a_client_key_coexist` requires that many null-keyed rows
#: coexist (that is how the browser creates collection rows), and the duplicate-key recovery path in
#: the entity loop below deliberately writes a null key to save a designer's work after a collision.
#: Both would break. A reserved VALUE costs nothing and breaks neither.
#:
#: COLLECTIONS ARE UNTOUCHED. Only singletons and `_custom` get it; a collection row keeps whatever
#: its client sent, or NULL.
#:
#: IT NEVER LEAVES THE SERVER, which is a stronger guarantee than "the clients tolerate it" and is
#: checkable in one grep: `_stages_payload` in `api/routes/design_workshops.py` injects `_clientKey`
#: only on the COLLECTION arm of its dispatch (`if row.clientKey: data["_clientKey"] = row.clientKey`).
#: The singleton arm and the `_custom` arm both assign `row.data` straight through, and the column is
#: not in `data`. `grep -rn clientKey --include=*.py app/` finds that injection to be the only emitter
#: in the backend. So no build of either client can see this value, echo it back, store it in an
#: outbox or key an offline row on it — counted 2026-08-22, and the day a singleton starts carrying
#: `_clientKey` on the wire is the day that has to be re-argued rather than assumed.
SINGLETON_CLIENT_KEY = "__dw_singleton__"


def singleton_client_key(stage_key: str) -> str:
    """:data:`SINGLETON_CLIENT_KEY` for one stage — and THE STAGE KEY IS NOT DECORATION.

    The unique index is ``(designWorkshopId, entityKey, clientKey)`` and does NOT carry ``stageKey``.
    For a registry singleton that costs nothing, because ``EntitySpec.key`` is unique across the
    whole registry by rule and therefore names its stage implicitly. **The reserved ``_custom``
    container is the exception that makes this function necessary**: every stage of a workshop that
    has a custom section stores its answers under the same literal ``_custom`` entity key, so a bare
    constant would have made stage 3's container and stage 9's container collide inside one
    workshop — the index refusing the second stage's custom answers outright, which is a far worse
    failure than the duplicate this whole change exists to prevent.

    Suffixing with the stage key gives every reserved row a value unique within its (workshop,
    entity) pair while still being the SAME value on every save of that row, which is the entire
    property the index needs. It also stops this depending on the registry's entity-key uniqueness
    rule holding for ever.
    """
    return f"{SINGLETON_CLIENT_KEY}:{stage_key}"


def _reserved_key_upgrade(row, entity_key: str, singleton_key: str, existing) -> str | None:
    """The reserved key an EXISTING singleton or ``_custom`` row should adopt, or ``None``.

    THE INDEX CANNOT SEE A ROW THAT PREDATES THE KEY. Postgres treats NULLs as distinct under a
    unique index, so `@@unique([designWorkshopId, entityKey, clientKey])` enforces nothing at all on
    the rows written before the reserved key existed — and the update branch below writes data,
    ordinal, deletedAt and fieldProvenance, so without this the key never arrives. The backfill
    migration is a one-off and only ever runs against the rows present on the day it is applied; a
    workshop restored from an older dump, or a row a future writer creates unkeyed, would otherwise
    sit outside the guarantee for ever. Adopting on the next ordinary save is what makes the
    invariant hold going forward instead of holding as of one migration.

    IT REFUSES TO ADOPT WHEN ANOTHER ROW OF THE SAME ENTITY ALREADY HOLDS THE KEY, and that clause
    is load-bearing rather than defensive. The unique index does NOT carry ``deletedAt``, so a
    SOFT-DELETED row still occupies its key; a workshop holding a soft-deleted keyed row beside a
    live unkeyed one would have the UPDATE refused by the index — and a refused UPDATE is not what
    `_absorb_key_collisions` recovers, which only turns refused INSERTs into updates. The row keeps
    its null key in that case and the singleton matcher, which prefers the keyed row and then falls
    back to the entity, still finds exactly one row to write.

    ``existing`` is every row of the stage, live and soft-deleted, exactly as the matcher reads it.
    """
    if row is None or row.clientKey == singleton_key:
        return None
    if any(r.entityKey == entity_key and r.clientKey == singleton_key for r in existing):
        return None
    return singleton_key


def refused_answer_count(errors: Mapping[str, Any]) -> int:
    """How many ANSWERS one save refused. The number both surfaces must show, computed once, here.

    ── WHY THE SERVER COUNTS THIS AT ALL ──────────────────────────────────────────────────────────
    ``errors`` is two levels deep — ``{scope: {field: message}}``, where a scope is ``entityKey`` for
    a singleton, ``entityKey[i]`` for a collection row, or the reserved custom container. A nested map
    with no total in it can be counted two ways, and the two clients picked one each:

    * the web read ``Object.keys(saved.errors ?? {}).length`` — the number of SCOPES — and printed it
      as "The server refused N answer(s)";
    * Android built one ``DwStageRefusal`` per (scope, field) pair and printed ``refusals.size`` — the
      number of FIELDS.

    One stage entry with three bad fields is therefore "1 answer" on the web and "3 answers" on the
    handset, off the same response body, and both sentences use the word *answer*. A designer working
    across a laptop and a phone is told two different things about one save, and neither surface is
    lying about what it counted.

    **FIELDS IS THE RIGHT READING AND SCOPES IS NOT A DEFENSIBLE ONE.** An answer is what a designer
    typed into one box; a scope is a row of the form, and a row is not an answer. The remedy the
    sentence sends them to — "open the stage to see which fields are marked" — is per-field too, so
    the count and the instruction disagreed on the web whenever any row held more than one bad value.

    It is returned as ``refusedAnswers`` rather than left to be derived because the shape is what
    invited the disagreement: a client that has to compute a headline number from a nested map will
    compute whichever one its author read first, and no amount of documenting ``errors`` prevents
    that. ``errors`` keeps its exact shape — both clients need it to mark the individual boxes, and
    Android's ``unplaced`` valve depends on being able to see a scope it cannot place.

    A NON-MAPPING VALUE COUNTS AS ONE, not as its length. Every writer of ``errors`` today puts a
    ``dict[str, str]`` there, so the branch is unreachable; it exists because ``len()`` of a string
    would silently return a character count, and "the server refused 47 answers" for one refused
    field is the kind of number nobody can trace back to a bug.
    """
    return sum(
        len(fields) if isinstance(fields, Mapping) else 1 for fields in errors.values()
    )


async def save_stage(workshop_id: str, spec: StageSpec, payload: Any, user: Any) -> dict[str, Any]:
    """Write one stage, returning HOW MUCH was stored, what failed validation and what was dropped.

    **IT DOES NOT RETURN THE STORED VALUES THEMSELVES, and this sentence used to say it did.**
    There was a `stored` dict built here for every non-`_custom` entry of every save, two write
    sites and no read site, dropped on the floor at the return — while this docstring, the
    comments beside the two write sites and the route's own docstring all described a response
    field that had never been on the wire. Android had already MEASURED the absence and written
    it down as fact (`DwStageRefusal.kt`: "The save response does not carry it — measured",
    listing the same key set), and built its three-state `DwHeld.UNRECORDED` around the gap.

    The reply carries counts, refusals and drift — `saved`, `created`, `updated`, `removed`,
    `errors`, `refusedAnswers`, `droppedKeys`, `droppedCustomKeys`, `completeness`,
    `transcriptionsQueued`, `transcriptionConsentRefusal`, `schemaVersion`,
    `customSchemaVersion` — and a client that needs the values back reads
    `GET /{id}/stages/{key}`. That IS a real cost: hydration can legitimately change what was
    written (a MONEY value normalised, an ENUM token the target field will not admit dropped, a
    photograph resolved by `_reference_photos`), so the form goes on showing the client's guess
    until somebody makes that second request. Echoing the values back instead is an additive
    change both clients would ignore safely, and it is written up as a follow-up rather than
    done here for two reasons: `stored` carries no row identity, so a client cannot address it
    to a row without `_clientKey`/`_entryId` being put back beside each entry (and a CREATE has
    no id until after the transaction), and it doubles the response of every stage save for a
    fleet that is often on one bar. What is NOT defensible is code and prose disagreeing about
    the wire, which is what this deletion ended. Do not reintroduce the variable without
    returning it.
    """
    # THE DESIGNER'S OWN QUESTIONS, LOADED BEFORE ANYTHING IS VALIDATED, because the answers to them
    # arrive in the same payload as the registry ones and are validated in the same pass.
    #
    # NOT `load_definition_or_empty`. A definition this server could not read would make every
    # custom key in the payload an unknown key — dropped, reported as drift, and the designer's
    # fieldwork gone with a 200 beside it. Failing the save is the honest outcome: the phone retries,
    # and nothing is lost. The read paths make the opposite choice, deliberately, and say so.
    #
    # GATHERED WITH THE OTHER TWO READS RATHER THAN AWAITED IN FRONT OF THEM. The three are
    # independent — the definition, this stage's rows, the workshop header — and the database is in
    # another region: one round trip measured 756ms against tables whose server-side time is
    # 0.04-0.24ms, so a third sequential read would have added most of a second to EVERY stage save
    # on the fleet, including for the workshops with no custom section at all, which is nearly all of
    # them. This is a write a designer is standing there waiting for. See `services/concurrency`.
    #
    # SOFT-DELETED ROWS ARE INCLUDED IN `existing`, and that is the whole point of not filtering on
    # deletedAt. The unique index is (designWorkshopId, entityKey, clientKey) and does NOT carry
    # deletedAt, so a soft-deleted row still occupies its client key. Matching only live rows
    # made the matcher blind to it and the save fell through to an INSERT, which the index
    # refused — surfacing as a bare 500 that failed the ENTIRE stage.
    #
    # The path is not exotic. A designer deletes a sketch and undoes it; or a phone that never
    # received the acknowledgement for a sync replays the queue it still holds. Either way the
    # correct behaviour is to RESURRECT the row the client is re-asserting, which is also what
    # keeps the sketch's id — and so the prototypes and reviews that reference it — intact.
    # Inserting a fresh row would have orphaned every one of those references.
    definition, existing, header_row = await gather_reads(
        custom_sections.load_definition(workshop_id),
        db.dwstageentry.find_many(
            where={"designWorkshopId": workshop_id, "stageKey": spec.key}
        ),
        db.designworkshop.find_unique(where={"id": workshop_id}),
    )
    custom_specs = definition.fields_for(spec.key)
    # The reserved client key this stage's singleton rows and its `_custom` container are written
    # under, so the unique index enforces one of each. See `singleton_client_key`.
    singleton_key = singleton_client_key(spec.key)
    workshop_status = str(getattr(header_row, "status", "DRAFT") or "DRAFT")
    live = [row for row in existing if row.deletedAt is None]
    by_id = {row.id: row for row in live}
    # Keyed over ALL rows, live or not; a live row wins a collision because it is written last.
    by_client_key = {
        (row.entityKey, row.clientKey): row
        for row in sorted(existing, key=lambda r: r.deletedAt is None)
        if row.clientKey
    }

    entities = {e.key: e for e in spec.entities}
    errors: dict[str, Any] = {}
    dropped: list[str] = []
    touched_ids: set[str] = set()
    touched_entities: set[str] = set()
    # ENTITIES WHOSE ENTRIES IN THIS PAYLOAD SAID `merge: true`, HELD SEPARATELY FROM
    # `touched_entities` BECAUSE THEY ANSWER OPPOSITE QUESTIONS.
    #
    # `touched_entities` means "the payload named this entity", and it ARMS the sweep. `merge`
    # means, in `StageEntryIn`'s own words, "I am sending every key I HAVE, not every key there
    # IS" — set "when, and only when, the client knows it has not seen the server's copy". A
    # payload can assert both at once, and until this set existed the server obeyed the second
    # and ignored the first: `entry.merge` was read at exactly one place (the key-level merge
    # below) and had no bearing on which ROWS survived.
    #
    # That combination is not hypothetical. It is the shipped Android BLOCKER of 2026-08-13
    # ("the handset's whole sweep gate was spelled as silence"): the phone sent `merge: true`
    # with `replaceCollections` omitted, the absent key defaulted to TRUE, and three rows the
    # phone had never downloaded were soft-deleted under a 200 reporting `removed: 3`. That
    # client bug was closed by removing a kotlinx default. The server-side contradiction that
    # turned it into row deletion was not, and the same shape is reachable from an older build,
    # a script, or any direct caller that sets merge and leaves `replaceCollections` alone.
    #
    # See the sweep below for why an `emptiedEntities` name still wins: that is a deliberate
    # statement about a whole collection, not an inference drawn from silence.
    merged_entities: set[str] = set()
    # Client keys claimed by an earlier entry of THIS payload, so a client that generated the
    # same id twice collides here rather than inside the unique index as a 500.
    claimed_client_keys: set[tuple[str, str]] = set()
    promoted: dict[str, Any] = {}

    creates: list[dict[str, Any]] = []
    updates: list[tuple[str, dict[str, Any]]] = []
    # Validation, then hydration, then the write — in that order and in three passes, not one.
    # Hydration reads the artisan and product tables, so doing it inside the loop would issue
    # one query per row of a thirty-row participant list. Promotion has to come after it,
    # because stage 1's craft picker is what fills in the craftName that PROMOTED_COLUMNS
    # copies onto the workshop header: computing the promoted values first wrote the pre-picker
    # blank into the column, and the workshop list showed no craft for a record whose stage 1
    # plainly named one.
    pending: list[PendingEntry] = []
    # The singleton entries this payload has already claimed, so a second entry for the same
    # singleton folds into the first rather than becoming a second row. See the fold below.
    pending_singletons: dict[str, PendingEntry] = {}

    # THE RESERVED CONTAINER, HANDLED BEFORE THE ENTITY LOOP AND NOT INSIDE IT.
    #
    # `_custom` is not a registry entity and never will be — that is the whole design (see
    # `services/custom_sections`): it is a `DwStageEntry` row of its own, one per (workshop, stage),
    # whose `data` is the designer's answers keyed by their own field keys. Handling it here rather
    # than teaching the loop below about it keeps three things true at once: `promoted_values` and
    # `hydrate_entries` never see it, `dropped` never gains its entity key, and the collection sweep
    # cannot reach it because `collection_keys` is derived from `spec.entities`.
    #
    # THE INVARIANT THAT MUST NOT BE WIDENED: the sweep is `(touched_entities | emptiedEntities) &
    # collection_keys`, and `_custom` can never be in `collection_keys`. A later change that widened
    # that set to "every entityKey the workshop has rows for" would soft-delete a workshop's entire
    # custom record on the next save that did not mention it — the same shape as the incident
    # recorded below, where four cost sheets, two buyer links and six prototypes went.
    custom_entry = next(
        (e for e in payload.entries if e.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None
    )
    # The sentinel-keyed row first, for the reason the singleton fallback below gives: on a workshop
    # that already holds two `_custom` rows, "the first live one" alternates between them and each
    # save writes half the answers into a row the next read may not choose.
    custom_row = next(
        (r for r in live if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY
         and r.clientKey == singleton_key), None
    ) or next(
        (r for r in live if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None
    ) or next(
        (r for r in existing if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None
    )
    # EVERY DECISION ABOUT THE CONTAINER IS MADE IN ONE PURE CALL — what to store, what to refuse
    # and what to report — so the combination of merge, rejected-value preservation and the submit
    # gate is covered by plain pytest with no Postgres, which is where the subtlety actually lives.
    custom_write = custom_sections.plan_custom_write(
        custom_specs,
        # None and {} are different instructions: no entry at all writes no row (which is what a
        # client one release behind sends), while an empty container is a designer clearing every
        # answer and IS written.
        sent=None if custom_entry is None else (custom_entry.data or {}),
        previous=dict(custom_row.data or {}) if custom_row is not None else {},
        merge=bool(custom_entry is not None and custom_entry.merge),
        submit=payload.submit,
    )
    custom_dropped = list(custom_write.dropped)
    custom_to_store = custom_write.data

    if custom_write.errors:
        # Under the reserved key itself, which preserves the existing error shape — `entity.key` for
        # a singleton, `f"{entity.key}[{index}]"` for a collection row — so both clients' existing
        # error rendering works unchanged. There is no collision with a core field key because the
        # bucket is separate.
        errors[custom_sections.CUSTOM_ENTITY_KEY] = custom_write.errors

    for index, entry in enumerate(payload.entries):
        if entry.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            # Not appended to `dropped`: this is not an entity this build does not know, it is the
            # reserved one, and reporting it as drift would fire "this phone is running a newer
            # field registry than the server" on every save of every workshop that has a custom
            # section — destroying the one drift signal this repository has.
            continue
        entity = entities.get(entry.entityKey)
        if entity is None:
            # An entity this build does not know: recorded, not fatal. See the module docstring
            # of app/schemas/design_workshops.py for why a stage sync must never 422 wholesale.
            dropped.append(entry.entityKey)
            continue
        touched_entities.add(entity.key)

        known = {f.key for f in entity.fields}
        # Underscore-prefixed keys are the sync protocol's own — _clientKey, _entryId, _ordinal
        # — not workshop data. Reporting them as dropped fields would put a line in every
        # response for something working exactly as designed, and would train whoever reads
        # droppedKeys to ignore it, which is the one thing it must not become: it is how a
        # server notices a phone is running a newer registry than it is.
        dropped.extend(
            f"{entity.key}.{k}" for k in entry.data
            if k not in known and not k.startswith("_")
        )

        clean, entry_errors = validate_entry(
            entity, entry.data, enforce_required=payload.submit
        )
        if entry_errors:
            key = entity.key if entity.cardinality is Cardinality.SINGLETON \
                else f"{entity.key}[{index}]"
            errors[key] = entry_errors
            # Keep going. A stage with one bad number still saves its other twenty fields; the
            # alternative loses everything the designer typed because of one typo.

        if entry.merge:
            merged_entities.add(entity.key)

        row = None
        if entry.entryId:
            row = by_id.get(entry.entryId)
            # THE ENTRY ID ARM IS GUARDED THE WAY THE OTHER TWO ALREADY WERE, and it was the only
            # one that was not. `by_client_key` is keyed by `(entityKey, clientKey)` and the
            # singleton fallback filters on `r.entityKey == entity.key`; `by_id` is keyed by row
            # id ALONE, over every live row of the stage whatever entity it belongs to.
            #
            # WRONG ENTITY. Stage 5 declares `processStep`, `tool` and `rawMaterial`. A caller
            # that lets an `_entryId` cross collections — a bulk import, a repair script, a draft
            # migration that re-keys rows — sends a `tool` entry carrying a `processStep` row's
            # id, and nothing downstream recovers: the UPDATE writes `data`, `ordinal` and
            # `deletedAt` and never `entityKey`, so the process-step row keeps its entity while
            # its answers become a tool's, `validate_entry` cannot object because it validated
            # against the entity the PAYLOAD named, and the real tool row — named by nothing in
            # `touched_ids` — is soft-deleted by the sweep as a row the client no longer has. One
            # 200 reading `saved: 1, removed: 1`, one row of fieldwork replaced by another
            # entity's answers and one deleted, and the corrupted row then prints in the
            # process-step table of the .docx with a tool's fields in it.
            #
            # TWICE IN ONE PAYLOAD. Two entries carrying one id both resolved through `by_id`,
            # both became `(row_id, …)` update tuples, and the transaction applied them in order
            # so the SECOND entry's data won wholesale — one row destroyed, `saved: 2` reported,
            # nothing in `dropped` and nothing in `errors`. `_clientKey` has been guarded against
            # exactly this since the duplicate-key 500 (three lines below); `entryId` has no
            # unique index to fail loudly, so it lost the answers quietly instead. The path is
            # ordinary the moment anything copies a row's `data` to make a second row: `_entryId`
            # is put INTO that data by the stage read and by `assemble_workshop_data`.
            #
            # `touched_ids` IS THE CLAIM SET and no second set is kept, because it is populated
            # at exactly one place — the match below — and therefore holds precisely the rows
            # earlier entries of this payload have already taken. That also catches the mixed
            # case (entry 1 matched a row by client key, entry 2 names the same row by id),
            # which a set of only-entryId claims would miss.
            #
            # BOTH ARMS FALL THROUGH RATHER THAN FAILING THE ENTRY. Setting `row = None` sends it
            # to the clientKey lookup and then to a create, so the designer's answers are still
            # written — under their real identity, or as a new row — which is the same recovery
            # the duplicate-clientKey branch chose and for the same reason. The refusal is
            # reported in `dropped`, which is the channel both clients already render.
            if row is not None and row.entityKey != entity.key:
                dropped.append(
                    f"{entity.key}._entryId={entry.entryId} (belongs to {row.entityKey})"
                )
                row = None
            elif row is not None and row.id in touched_ids:
                dropped.append(f"{entity.key}._entryId={entry.entryId} (duplicate in payload)")
                row = None
        client_key = str(entry.data["_clientKey"]) if entry.data.get("_clientKey") else None
        if row is None and client_key:
            row = by_client_key.get((entity.key, client_key))
            if row is None and (entity.key, client_key) in claimed_client_keys:
                # A SECOND entry in THIS payload already claimed that key. Two rows cannot share
                # one client key — the unique index says so — and letting this one through
                # produced a raw 500 that failed the whole stage. The row is still saved, under
                # no client key, so the designer's work survives; the collision is reported so a
                # client generating duplicate ids can be found and fixed.
                dropped.append(f"{entity.key}._clientKey={client_key} (duplicate in payload)")
                client_key = None
        if row is None and entity.cardinality is Cardinality.SINGLETON:
            # A stage's singleton is unique by entity, so a live one is preferred and a
            # soft-deleted one is resurrected rather than duplicated.
            #
            # THE SENTINEL-KEYED ROW IS PREFERRED AHEAD OF BOTH, and that is what makes this choice
            # DETERMINISTIC on a workshop that already carries a duplicate from before the index
            # could enforce one. `live` arrives in whatever order the read returned, so "the first
            # live row" is the same coin toss that let two rows drift apart in the first place — the
            # save would update one of them on Tuesday and the other on Wednesday. The row holding
            # `SINGLETON_CLIENT_KEY` is the one the backfill migration picked, and picking it every
            # time is what makes an existing pair CONVERGE instead of alternating.
            row = (
                next((r for r in live
                      if r.entityKey == entity.key and r.clientKey == singleton_key), None)
                or next((r for r in live if r.entityKey == entity.key), None)
                or next((r for r in existing if r.entityKey == entity.key), None)
            )

        ordinal = entry.ordinal if entry.ordinal is not None else index
        if entity.cardinality is Cardinality.SINGLETON:
            ordinal = 0
            # THE RESERVED KEY, WRITTEN HERE AND NOT EARLIER, so the matching above still honours
            # whatever key the client sent — a phone that created this row offline under its own
            # UUID must still be able to find it. From this line on the row is addressed by
            # (workshop, entity), which is what the singleton IS, and the unique index enforces it.
            # A client-sent key on a singleton is therefore not stored; nothing needs it, because
            # the fallback above matches the singleton by entity whatever key it carries.
            client_key = singleton_key

        previous: dict[str, Any] = {}
        previous_provenance: dict[str, Any] = {}
        if row is not None:
            touched_ids.add(row.id)
            previous = dict(row.data or {})
            previous_provenance = entry_provenance.entry_provenance(row)
            if entry_errors:
                # A REJECTED FIELD MUST NOT DESTROY THE VALUE ALREADY STORED UNDER IT.
                #
                # `validate_entry` omits a field it could not read, so writing `clean` wholesale
                # deleted the good value the designer had saved earlier: type "6500", save,
                # later fat-finger "65OO", and the price is not merely un-updated, it is GONE —
                # while the response reports a validation error, which reads as "your edit was
                # rejected", not as "your earlier answer was deleted". The rejected keys keep
                # whatever the row already held; every other key on the entry still saves,
                # because losing twenty good fields to one typo is the failure this branch was
                # written to avoid in the first place.
                for bad_key in entry_errors:
                    if bad_key in previous:
                        clean[bad_key] = previous[bad_key]

        if entry.merge and previous:
            # "I AM SENDING EVERY KEY I HAVE, NOT EVERY KEY THERE IS."
            #
            # A client that has never downloaded this stage holds a blank form, and a blank form
            # is indistinguishable on the wire from a stage somebody deliberately emptied. Both
            # clients show a banner saying so and both promised that what is left blank would not
            # overwrite an answer recorded elsewhere; without this branch they could not keep it,
            # because the update below replaces a singleton's `data` WHOLESALE. Typing one field
            # into an unread stage deleted every other field the office had written — in place,
            # with no RecordRevision to recover it — and `_coerce_promoted` then nulled
            # craftName, state, district and the dates along with it, so the workshop fell out of
            # every filter and the report cover printed blank.
            #
            # Applied HERE, before `pending`, so the merged dict is the one that reaches BOTH the
            # row and the promoted columns — two readers that must not disagree about what was
            # just written. (There was a third, a `stored` echo block; it was never returned and
            # has been deleted. See this function's docstring.)
            #
            # `clean` wins every key it holds: this fills the gaps in what the client sent, it
            # never overrides it. An empty string the designer actually typed is a value and stays.
            clean = {**previous, **clean}

        held = pending_singletons.get(entity.key)
        if held is not None:
            # TWO ENTRIES FOR ONE SINGLETON IN ONE PAYLOAD, FOLDED INTO ONE ROW RATHER THAN
            # WRITTEN AS TWO.
            #
            # `_clientKey` and `_entryId` have both been guarded against a duplicate-in-payload
            # since the collisions they caused; a singleton needs no key to be addressed, so it had
            # no guard at all and the second entry simply became a second row — one INSERT the
            # unique index could not refuse, because both carried a null key. That is the same
            # duplicate this constant exists to end, arriving from inside a single request instead
            # of from two.
            #
            # FOLDED, NOT DROPPED: the later entry's keys win and the earlier entry's keys survive
            # where the later one is silent, which is the same "`clean` wins every key it holds"
            # rule the merge branch above applies. Losing a designer's answers to a client that
            # serialised one form twice would be a worse outcome than the duplicate row. The
            # collision is still REPORTED, because a client sending two entries for one singleton
            # is a bug somebody should be able to find.
            held.data.update(clean)
            dropped.append(f"{entity.key} (second singleton entry in payload, folded into the first)")
            continue

        item = PendingEntry(
            entity=entity, data=clean, previous=previous,
            row_id=row.id if row is not None else None,
            ordinal=ordinal, client_key=client_key,
            previous_provenance=previous_provenance,
            # ONLY A SINGLETON. A collection row's key belongs to the client that made it and is
            # how that client finds its own row again after an offline sync; overwriting it would
            # strand the phone's copy and duplicate the row on the next replay.
            adopt_client_key=(
                _reserved_key_upgrade(row, entity.key, singleton_key, existing)
                if entity.cardinality is Cardinality.SINGLETON else None
            ),
        )
        pending.append(item)
        if entity.cardinality is Cardinality.SINGLETON:
            pending_singletons[entity.key] = item
        if client_key:
            claimed_client_keys.add((entity.key, client_key))

    # The chosen references write their display fields onto the entries here, BEFORE anything is
    # serialised for the database and before the promoted columns are read out of them. `clean`
    # is mutated in place, so the same dict reaches the row and the promoted columns.
    #
    # NOTE THAT THE CLIENT DOES NOT SEE THE RESULT OF THIS until it reads the stage back: the
    # save response carries counts, not values (see the docstring). Hydration is exactly where
    # that costs something, because coercion can change what the client sent.
    await hydrate_entries(pending)

    for item in pending:
        promoted.update(promoted_values(item.entity.key, item.data))
        # COMPUTED AFTER HYDRATION AND BEFORE THE WRITE, which is the only window in which both
        # halves of the answer exist: `item.hydrated` is populated by the pass above and `item.data`
        # is final. Doing it before hydration would attribute every copied field to the designer who
        # picked the record; doing it after the write would have nothing left to read.
        field_provenance = entry_provenance.merge_entry_provenance(
            previous=item.previous_provenance,
            previous_data=item.previous,
            new_data=item.data,
            hydrated=item.hydrated,
            user=user,
        )
        if item.row_id is not None:
            # deletedAt is cleared unconditionally: the client is asserting this row exists, and
            # for a row that was never deleted writing None over None costs nothing. Clearing it
            # only when the row looked deleted would mean reading a value that another request
            # may have changed between the SELECT above and this UPDATE.
            updates.append((
                item.row_id,
                {
                    "data": _json(item.data),
                    "ordinal": item.ordinal,
                    "deletedAt": None,
                    "fieldProvenance": _json(field_provenance),
                    # Present only when this row is a singleton that predates the reserved key;
                    # `_reserved_key_upgrade` returns None in every other case and the column is
                    # then not written at all.
                    **({"clientKey": item.adopt_client_key} if item.adopt_client_key else {}),
                },
            ))
        else:
            creates.append({
                "designWorkshopId": workshop_id,
                "stageKey": spec.key,
                "entityKey": item.entity.key,
                "ordinal": item.ordinal,
                "data": _json(item.data),
                "fieldProvenance": _json(field_provenance),
                "clientKey": item.client_key,
                "createdById": user.id,
            })

    # THE CUSTOM ROW'S OWN WRITE, built exactly like a singleton's and deliberately reusing all
    # three of the rules the loop above applies — because the row's keys are TOP LEVEL, which is the
    # single property that made this design cheaper than nesting the container inside a core entry:
    #
    #   * the rejected-value preservation loop works verbatim. Typing "65OO" over a saved "6500"
    #     must not delete the good value, and the keys it indexes `previous` by are the same keys;
    #   * the shallow `{**previous, **clean}` merge is already correct for a never-read client,
    #     where a nested container would have needed a recursive merge written for one key;
    #   * `MAX_FIELD_KEYS` already bounds it, where a nested object is one key and could have
    #     carried ten thousand.
    #
    # A soft-deleted custom row is RESURRECTED rather than duplicated, exactly as a singleton is:
    # the row is addressed by (workshop, stage, `_custom`) and there is only ever one of it. A
    # payload that carried no custom entry writes nothing here at all — which is what makes a client
    # one release behind harmless rather than destructive.
    if custom_to_store is not None:
        # THE RESERVED ROW GETS PROVENANCE ON THE SAME RULE, with the hydration half empty because
        # a custom field can never be hydrated: `_custom` is invisible to `hydrate_entries` by
        # construction (it is not a registry entity, so it never enters `pending`). Every stamp here
        # is therefore a designer stamp or a carried one — which is exactly the requirement applied
        # to a question one designer wrote and another answered. Leaving this row out would have
        # made the custom section the one place on the workshop where "who set this" has no answer,
        # and it is the section most likely to be filled in by whoever is holding the phone.
        custom_provenance = entry_provenance.merge_entry_provenance(
            previous=entry_provenance.entry_provenance(custom_row) if custom_row else {},
            previous_data=dict(custom_row.data or {}) if custom_row is not None else {},
            new_data=custom_to_store,
            hydrated={},
            user=user,
        )
        if custom_row is not None:
            # THE CONTAINER ADOPTS THE RESERVED KEY ON THE SAME RULE A SINGLETON DOES, because it is
            # one and because every `_custom` row written before the key existed is a row the index
            # cannot see. A second `_custom` row is a second copy of every answer the designer wrote
            # to their own questions, with nothing in the read path to say which one it is showing.
            custom_key_upgrade = _reserved_key_upgrade(
                custom_row, custom_sections.CUSTOM_ENTITY_KEY, singleton_key, existing
            )
            updates.append((
                custom_row.id,
                {
                    "data": _json(custom_to_store),
                    "ordinal": 0,
                    "deletedAt": None,
                    "fieldProvenance": _json(custom_provenance),
                    **({"clientKey": custom_key_upgrade} if custom_key_upgrade else {}),
                },
            ))
        else:
            creates.append({
                "designWorkshopId": workshop_id,
                "stageKey": spec.key,
                "entityKey": custom_sections.CUSTOM_ENTITY_KEY,
                "ordinal": 0,
                "data": _json(custom_to_store),
                "fieldProvenance": _json(custom_provenance),
                # THE SAME RESERVED KEY A SINGLETON GETS, because this row is one: there is exactly
                # one `_custom` row per (workshop, stage) and `entityKey` is the reserved literal,
                # so `@@unique([designWorkshopId, entityKey, clientKey])` covers it once the key is
                # not null. It matters MORE here than on a registry singleton, because a second
                # `_custom` row is a second copy of every answer the designer wrote to their own
                # questions and nothing in the read path would say which one it was showing.
                "clientKey": singleton_key,
                "createdById": user.id,
            })

    # Rows the client no longer has, in the entities it actually sent. Restricting the sweep to
    # touched entities is what keeps a web form editing one collection from deleting another.
    removed: list[str] = []
    if payload.replaceCollections:
        # THE SWEEP IS SCOPED BY WHAT THE PAYLOAD NAMES — the entities it sent rows for, plus the
        # ones it explicitly says it has emptied. Nothing else.
        #
        # It was briefly scoped by the STAGE SPEC instead, so `replaceCollections` swept every
        # collection the stage declares whether or not the payload had ever mentioned it. That
        # was written for a real failure (below) and caused a worse one: a caller that sent one
        # entity's rows silently soft-deleted every OTHER collection of the same stage. PUT
        # COSTING_MARKET_LINKAGE with a single costSheet row — `replaceCollections` omitted, and
        # the schema default is TRUE — deleted every buyerLink, costMaterialLine and
        # costLabourLine on the server; the response said `removed: 1` and the UI said the stage
        # was saved. The flagship seeded workshop lost its four cost sheets, its two buyer links
        # and its six prototypes that way, and its report printed 28 unattributed material lines
        # with no cost sheet, no product, no total and no price. A payload that never named an
        # entity must never be able to delete it — that is the contract `StageSaveIn` documents.
        #
        # THE FAILURE THE SPEC-WIDE SWEEP WAS WRITTEN FOR is still fixed, by `emptiedEntities`.
        # `touched_entities` is only ever populated from the entries actually sent, so a
        # collection with ZERO incoming rows is invisible in `entries`: the web builds them from
        # `collections[entity.key] ?? []` and the phone from `.orEmpty()`. That is exactly the
        # shape both clients send once the designer deletes the LAST row of a collection, and it
        # used to report `removed: 0` while the rows stayed alive — back on the next load, and
        # printed in the .docx handed to the ministry. With no per-row delete endpoint no client
        # action could remove them. A client that empties a collection now NAMES it, which the
        # web already tracks per entity as `removedFrom` and only has to send.
        #
        # A singleton is never swept whichever list names it: it is updated in place or created,
        # never deleted by omission — otherwise saving a stage's collection would erase its
        # narrative.
        collection_keys = {
            e.key for e in spec.entities if e.cardinality is Cardinality.COLLECTION
        }
        # AN ENTITY WHOSE OWN ENTRIES SAID `merge: true` IS NOT SWEPT, because the payload is then
        # making two contradictory claims about it and only one of them can be honoured: "I have
        # not seen the server's copy" (the entry) and "delete every row I did not name" (the
        # absent `replaceCollections`, whose default is TRUE). Obeying the destructive half of a
        # contradiction is how three rows a phone had never downloaded were soft-deleted under a
        # 200. See `merged_entities` above for the incident.
        #
        # `emptiedEntities` IS SUBTRACTED BACK IN AFTERWARDS AND THAT ORDER MATTERS. Naming a
        # collection as emptied is an explicit statement about the whole of it — the web tracks it
        # per entity as `removedFrom` and the phone as `emptiedEntities` — and it is the ONLY way
        # a client can delete the last row of a collection, there being no per-row delete
        # endpoint. A client that both empties a collection and merges it is not contradicting
        # itself; it is saying "I hold none of these, and I mean none".
        sweep_entities = (
            (touched_entities - merged_entities) | set(payload.emptiedEntities)
        ) & collection_keys
        # Over `live`, not `existing`: a row that was already soft-deleted must not be counted
        # as newly removed, or every save would report a growing `removed` tally for rows
        # deleted weeks ago and rewrite their deletedAt to today, destroying the record of when
        # the designer actually removed them.
        for row in live:
            if row.entityKey not in sweep_entities:
                continue
            if row.id in touched_ids:
                continue
            entity = entities.get(row.entityKey)
            if entity is None or entity.cardinality is Cardinality.SINGLETON:
                continue
            removed.append(row.id)

    async def write_everything() -> None:
        async with db.tx() as tx:
            # One transaction, because a 22-stage submit is a many-statement write and a failure
            # halfway through would otherwise leave a stage that is neither the old data nor the
            # new. This was the FIRST place in the repository that needed one; it has not been the
            # only one since, and the sentence claiming it was outlived both of the writes that
            # copied it — `custom_sections.apply_definition_plan` (a supersede is a create and a
            # back-pointer, and a failure between them strands a retired field pointing at nothing)
            # and `api/routes/data_access._upsert_grant` (a delete-then-insert that briefly showed a
            # colleague an export missing the records whose scope rows were not back yet). Both cite
            # this call site as their precedent, so a reader arriving from either would have been
            # told the precedent does not exist.
            #
            # A FUNCTION RATHER THAN A BARE `async with`, so the whole of it can be RE-RUN. See the
            # caller: a unique violation aborts a Postgres transaction outright, so recovering from
            # one inside the block is not possible without savepoints the driver does not expose.
            # `creates` and `updates` are read from the enclosing scope at call time, which is what
            # lets the recovery rewrite them between the two attempts.
            for row_id, data in updates:
                await tx.dwstageentry.update(where={"id": row_id}, data=data)
            for data in creates:
                await tx.dwstageentry.create(data=data)
            if removed:
                await tx.dwstageentry.update_many(
                    where={"id": {"in": removed}}, data={"deletedAt": datetime.now(UTC)}
                )
            header: dict[str, Any] = {"schemaVersion": registry_version()}
            header.update(_coerce_promoted(promoted, touched_entities))
            # DRAFT is the ONLY status a stage save advances, and it advances it exactly once. The
            # record now has content, and a list that still calls it a draft after two weeks of
            # capture is misleading — but forcing IN_PROGRESS unconditionally silently demoted a
            # workshop the designer had marked COMPLETE, or SUBMITTED, or ARCHIVED, every time
            # anybody touched a stage. Correcting a typo in a submitted report should not un-submit
            # it. The later statuses are the designer's to set, through PATCH, and only theirs.
            if workshop_status == "DRAFT":
                header["status"] = "IN_PROGRESS"
            await tx.designworkshop.update(where={"id": workshop_id}, data=header)

    try:
        await write_everything()
    except UniqueViolationError:
        # THE RACE THE SENTINEL TURNS FROM SILENT CORRUPTION INTO A RETRY.
        #
        # Two designers share a workshop — `DesignWorkshopViewer` exists so they can — and both save
        # the same stage. Each read the rows, each found no singleton, and each planned an INSERT.
        # Before `SINGLETON_CLIENT_KEY` both inserts succeeded (null keys are distinct under the
        # index) and the workshop was left with two rows whose answers no read path chose between
        # deterministically. Now the second INSERT is REFUSED, which is the outcome to want — but a
        # refusal a designer sees as a 500 is not an improvement over the duplicate, because their
        # answers are still not stored.
        #
        # So the loser re-reads and applies its work as an UPDATE to the row the winner just made.
        # RE-RUNNING THE WHOLE TRANSACTION, not resuming the aborted one: a constraint violation
        # puts a Postgres transaction into the aborted state where every further statement fails
        # with 25P02, and the driver gives no savepoint to roll back to. The re-run is safe because
        # every statement in it is idempotent by construction — the updates address rows by id, the
        # sweep writes one timestamp, and the header write is a plain assignment.
        #
        # ONCE. A second violation is not this race — it means something is generating colliding
        # keys — and swallowing it in a loop would turn a bug into a hang on the request a designer
        # is waiting on. It is raised, and the route answers 500 as it did before.
        creates, updates = await _absorb_key_collisions(
            workshop_id, spec.key, creates, updates
        )
        await write_everything()

    rows = await entry_rows(workshop_id, stage_key=spec.key)
    completeness = workshop_completeness(rows, definition=definition).get(spec.key)
    # AN AUDIO FIELD ON A STAGE IS A RECORDING, AND A RECORDING GETS TRANSCRIBED. This is the one
    # line that puts a workshop clip into exactly the pipeline an interview recording has always
    # used — the same job table, the same off-peak window, the same provider chain and backoff. It
    # is here, on the save, rather than at upload time because the upload does not yet know the
    # clip is workshop audio: the phone records into the generic media route and only this write
    # says "that file is the artisan's spoken explanation for stage 5". It is idempotent (a clip
    # that has a transcript, or a job already queued, is skipped) and it never raises, so a queue
    # that is unavailable cannot fail a designer's stage save. See services/workshop_transcripts.py.
    #
    # AND IT IS GATED ON THE ARTISAN'S CONSENT, which is what `design_workshop_id` is doing here. Until
    # this argument existed, this line reported `transcriptionsQueued: 1` on a workshop whose
    # `dictationConsent` was NOT_RECORDED — in the same minute that `POST /{id}/dictate` refused the
    # same workshop with a 409 and the sentence "nobody has recorded yet whether recordings from this
    # workshop may be sent". The id is right here in the path, so nothing needs resolving: it is handed
    # down and `enqueue_media_processing_jobs` refuses any clip whose workshop has not answered GRANTED.
    queued = await enqueue_stage_transcriptions(
        rows, user.id, viewer=user, design_workshop_id=workshop_id
    )
    # WHY NOTHING WAS QUEUED, WHEN THE REASON IS A PERSON'S DECISION RATHER THAN A NIGHT NOT YET COME.
    #
    # `transcriptionsQueued: 0` is ambiguous by construction — it is also what a re-save of an
    # already-queued stage reports, which is the common case and is fine. Silence is not fine when the
    # cause is the consent gate: the recording sits on the transcripts screen with no text, and
    # `report_annexures.missing_transcript_note` counts it among the clips that "have no transcript
    # yet", so a designer waits for a night that never arrives and reports the feature as broken. It is
    # not broken; nobody has asked the artisan. That is a sentence, and it belongs on the screen the
    # designer is looking at when they attach the recording.
    #
    # ASKED ONLY WHEN THIS STAGE ACTUALLY HOLDS AUDIO, so no read is spent on the twenty stages that
    # carry none, and no message is shown to a designer who was not trying to transcribe anything.
    consent_note = ""
    if audio_references(rows):
        verdict = await dictation_consent.workshop_send_verdict(workshop_id, about=spec.key)
        if not verdict.may_send:
            consent_note = verdict.refusal or ""
    return {
        "stageKey": spec.key,
        "saved": len(creates) + len(updates),
        "created": len(creates),
        "updated": len(updates),
        "removed": len(removed),
        "errors": errors,
        # THE HEADLINE NUMBER, COUNTED ONCE ON THE SERVER SO THE TWO SURFACES CANNOT DISAGREE ABOUT
        # IT. `errors` is a nested map and both clients were deriving their own total from it — the
        # web counting scopes, Android counting fields — so one refused stage entry with three bad
        # values was "1 answer" on a laptop and "3 answers" on the phone. See `refused_answer_count`
        # for why fields is the right reading. `errors` is unchanged and stays the authority on WHICH
        # box is marked; this is only the total, which nothing else in the response carried.
        "refusedAnswers": refused_answer_count(errors),
        # IT ALSO CARRIES PAYLOAD-SHAPE COMPLAINTS, AND THAT IS A KNOWN IMPURITY RATHER THAN AN
        # OVERSIGHT. Three entries in this list are not registry drift at all: a duplicate
        # `_clientKey`, a duplicate `_entryId`, and a second entry for one singleton folded into the
        # first. All three mean "this client serialised the same thing twice", which is a client bug
        # and not a version skew — so a browser that double-submits one form shows the "newer field
        # registry than the server" banner, which is the wrong sentence.
        #
        # LEFT SHARING THE CHANNEL, DELIBERATELY. The alternative is a fourth response key, and a
        # response key is only worth its cost when a client renders it: `droppedCustomKeys` earns
        # that because custom drift happens on EVERY save of a workshop with a custom section and
        # has a different remedy (reload, not report). These three happen only when a client is
        # malfunctioning, at which point "something about this save was refused, tell somebody" is
        # very nearly the right message and being seen at all matters more than being phrased
        # exactly. Silently dropping them instead would leave a designer's second copy vanishing
        # with a 200 beside it and nothing anywhere to find it by.
        #
        # WHAT WOULD CHANGE THE ANSWER: if any of the three ever became routine — a shipped client
        # that duplicates on ordinary use — the banner would start crying wolf on healthy saves and
        # these would need their own key, for exactly the reason the note below gives for custom
        # keys. Until then the channel is diluted by three bug reports, not by normal traffic.
        "droppedKeys": sorted(set(dropped)),
        # CUSTOM DRIFT GETS ITS OWN FIELD AND ITS OWN SENTENCE, AND NEVER `droppedKeys`.
        #
        # `droppedKeys` is the only client/server registry-drift signal this repository has, and both
        # clients render it in exactly those words: "this phone is running a newer field registry
        # than the server". A custom key the server's definition does not carry is a DIFFERENT fact
        # with a different remedy — the definition was edited, not the app — and feeding it into that
        # signal would fire the banner on every save of every workshop that has a custom section.
        # The people who read that banner would learn to ignore it, which is precisely what the note
        # above the dropped-key computation says must never happen.
        #
        # Safe on every client that has never heard of it: Android decodes with
        # `ignoreUnknownKeys = true` and the web reads only the keys it names.
        "droppedCustomKeys": sorted(set(custom_dropped)),
        "completeness": completeness,
        "transcriptionsQueued": len(queued),
        # The gate's own sentence when this workshop's recordings may not be sent, and "" otherwise.
        #
        # A STRING AND NOT A BOOLEAN, for the reason `dictation_consent.gate_refusal` gives: the two
        # states that refuse have different next moves — one is answered by asking the artisan, the
        # other only by the artisan changing their mind — and a flag would collapse them into one
        # message a client would have to invent. Empty rather than absent so a client can branch on
        # truthiness, and additive so every shipped build ignores it (Android decodes with
        # `ignoreUnknownKeys = true`; the web reads only the keys it names).
        "transcriptionConsentRefusal": consent_note,
        "schemaVersion": registry_version(),
        # The digest of the definition this save was validated against, so a client can tell its
        # cached copy is stale without a second request. Empty for a workshop that has none, which
        # is a different fact from "I hold nothing" and is why it is not omitted.
        "customSchemaVersion": definition.version,
    }


async def _absorb_key_collisions(
    workshop_id: str,
    stage_key: str,
    creates: list[dict[str, Any]],
    updates: list[tuple[str, dict[str, Any]]],
) -> tuple[list[dict[str, Any]], list[tuple[str, dict[str, Any]]]]:
    """Turn every INSERT whose reserved key another request has just taken into an UPDATE of it.

    It also withdraws an UPDATE's *adoption* of the reserved key when the re-read shows that key now
    belongs to another row — the same collision arriving from the other direction. See the note on
    that loop below.

    Called only after a :class:`UniqueViolationError` has aborted the write, and it re-reads rather
    than reasoning from what was planned: the whole premise is that the stage's rows changed under
    this request between its read and its write, so the pre-collision picture is exactly the thing
    that cannot be trusted.

    ``createdById`` IS DELIBERATELY NOT CARRIED ACROSS. The row exists because the other designer's
    save made it, and that is who created it; rewriting the column would credit this request with a
    row it lost the race for, on a table whose per-field provenance is what the report attributes
    answers by. What this request contributes is its VALUES, and `fieldProvenance` — computed before
    the first attempt, from this designer's edits — travels with them.

    A create whose key is still free stays a create. That is the ordinary case for every collection
    row in the payload, which carries either its own client key or none, and which this must not
    disturb: the collision was one row's, and re-running the transaction re-runs all of them.
    """
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id, "stageKey": stage_key}
    )
    # Soft-deleted rows included, for the reason the matcher in `save_stage` includes them: a
    # deleted row still OCCUPIES its client key under the index, so the row that refused the insert
    # may well be one nothing is currently showing. `deletedAt: None` below resurrects it, which is
    # what the client asserting the row exists means.
    taken = {(row.entityKey, row.clientKey): row.id for row in rows if row.clientKey}

    # AN UPDATE CAN BE THE THING THAT WAS REFUSED, TOO, and it is refused for the mirror-image
    # reason. `_reserved_key_upgrade` decides from the pre-write read whether an unkeyed singleton
    # may adopt the reserved key, and a competing save can take that key in the window between the
    # two. Retrying the identical UPDATE would then be refused identically and the designer would
    # get a 500 — losing the save, which is the outcome this whole function exists to avoid — so
    # the adoption is DROPPED when the re-read shows the key now belongs to a different row. Only
    # the adoption: `data`, `ordinal`, `deletedAt` and `fieldProvenance` are the designer's work and
    # go in untouched. The row keeps its null key and the singleton matcher, which prefers the keyed
    # row and then falls back to the entity, still finds one row to write; the pair converges on the
    # winner from the next save on, which is the same convergence the matcher gives every other
    # pre-existing duplicate.
    rows_by_id = {row.id: row for row in rows}
    absorbed: list[tuple[str, dict[str, Any]]] = []
    for row_id, planned in updates:
        adopted = planned.get("clientKey")
        row = rows_by_id.get(row_id)
        holder = taken.get((row.entityKey, adopted)) if adopted and row else None
        fields = (
            {key: value for key, value in planned.items() if key != "clientKey"}
            if holder is not None and holder != row_id
            else planned
        )
        absorbed.append((row_id, fields))

    still_creates: list[dict[str, Any]] = []
    for data in creates:
        row_id = taken.get((data["entityKey"], data.get("clientKey")))
        if row_id is None:
            still_creates.append(data)
            continue
        absorbed.append((
            row_id,
            {
                "data": data["data"],
                "ordinal": data["ordinal"],
                "deletedAt": None,
                "fieldProvenance": data["fieldProvenance"],
            },
        ))
    return still_creates, absorbed


def _json(data: dict[str, Any]) -> Any:
    """Wrap a dict for a Prisma Json column.

    A raw dict reaching a Json column is a 500, not a 422 — the driver raises rather than
    validating — so every write goes through here.
    """
    from prisma import Json

    return Json(data)


def _coerce_promoted(promoted: dict[str, Any], touched_entities: set[str]) -> dict[str, Any]:
    """Convert promoted values to the column types ``DesignWorkshop`` declares.

    A column whose source entity was part of this save but whose value is now blank is set back
    to NULL rather than skipped. Skipping it made a promoted column write-once: a designer who
    typed the wrong sanction number, cleared the field and saved found the list still showing
    the wrong number, with the JSON and the column permanently disagreeing about the same fact —
    the exact drift the promoted columns' single-writer rule exists to prevent.

    Columns whose entity was NOT in this payload are left alone entirely: saving stage 13 must
    not blank the cover fields that stage 1 owns.

    ``title`` IS THE ONE EXCEPTION, and not out of taste: it is the only promoted column
    ``DesignWorkshop`` declares NOT NULL, so nulling it is not a blank cell, it is a Prisma
    ``MissingRequiredValueError`` — a bare 500 that fails THE ENTIRE STAGE SAVE. Every ordinary
    path reaches it. A designer opens stage 1 on a workshop created from the title box, fills in
    the craft and the cluster, and saves before typing the workshop title into the stage's own
    field: `workshopTitle` is blank, `title` is set to NULL, and two dozen filled-in fields are
    lost to a 500 with no indication which of them caused it. The workshop keeps the title it
    has until the stage supplies another one.
    """
    out: dict[str, Any] = {}
    for path, column in PROMOTED_COLUMNS.items():
        entity_key = path.partition(".")[0]
        if entity_key not in touched_entities:
            continue
        value = promoted.get(column)
        if value in (None, ""):
            if column == "title":
                continue
            out[column] = None
        elif column in ("startDate", "endDate"):
            try:
                out[column] = datetime.fromisoformat(str(value)[:10]).replace(tzinfo=UTC)
            except ValueError:
                out[column] = None
        else:
            out[column] = str(value)[:220]
    return out


# --------------------------------------------------------------------------------------
# Completeness
# --------------------------------------------------------------------------------------


def workshop_completeness(
    entries: list[Any], *, definition: Any = None
) -> dict[str, Any]:
    """Score every stage from the entry rows, in one pass.

    ``definition`` is this workshop's custom sections, as ``custom_sections.load_definition``
    returns them. **Optional, and its absence means "this workshop has no designer-defined fields"
    rather than "do not count them"** — which is true of every workshop that has never had one, and
    is what lets the five call sites be converted without a flag day. Every call site that can reach
    the definition passes it; a caller that could not would silently report a stage as complete that
    the submit gate then refuses, so there is deliberately no third state here to get wrong.

    **THIS IS ALSO WHERE "ALL 25 PHOTOGRAPHS ARE REQUIRED" IS ENFORCED, AND THE ONLY PLACE IT IS.**
    A field declaring ``min_items`` (stage 4's two motif galleries) is counted as required by
    ``stage_completeness`` and is not filled until it holds that many, so ``isComplete`` goes false
    and ``missing`` names it with its shortfall — "Traditional motif photographs (20 of 25)". No
    save path knows about the floor: ``coerce_value`` and ``validate_entry`` are untouched by it, so
    a designer with twenty photographs and no signal still saves twenty. Anyone tempted to move the
    rule closer to the write should read ``stage_schema.FieldSpec.min_items`` first, which records
    the two ways that would have destroyed field work — Android dropping a 4xx instead of queueing
    it, and ``save_stage`` restoring the rejected key from ``previous``.
    """
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}
    # THE RESERVED ROW IS PICKED OUT HERE, and this is the third of the four places the design's
    # price is paid. Without it the `_custom` row falls into the `else` branch below and is scored
    # as a COLLECTION row of an entity the registry does not know — which is harmless in that the
    # collection loop only walks `spec.collections`, and wrong in that the answers would then be
    # counted by nothing at all.
    custom_values: dict[str, dict[str, Any]] = {}
    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}

    for row in entries:
        data = dict(row.data or {})
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            custom_values[row.stageKey] = data
        elif cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.stageKey] = data
        else:
            collections.setdefault(row.stageKey, {}).setdefault(row.entityKey, []).append(data)

    fields_by_stage = definition.fields_by_stage() if definition is not None else {}

    out: dict[str, Any] = {}
    for spec in stages():
        score = stage_completeness(
            spec, singletons.get(spec.key, {}), collections.get(spec.key, {}),
            custom_fields=fields_by_stage.get(spec.key, ()),
            custom_values=custom_values.get(spec.key, {}),
        )
        out[spec.key] = {
            "stageKey": score.stage_key,
            "number": spec.number,
            "title": score.title,
            "requiredTotal": score.required_total,
            "requiredFilled": score.required_filled,
            "optionalTotal": score.optional_total,
            "optionalFilled": score.optional_filled,
            "percent": score.percent,
            "isComplete": score.is_complete,
            "collectionCounts": score.collection_counts,
            "missing": list(score.missing),
        }
    return out


# --------------------------------------------------------------------------------------
# Reports
# --------------------------------------------------------------------------------------


def assemble_workshop_data(record: Any, entries: list[Any]) -> WorkshopData:
    """Turn stage entry rows into the shape the report builder reads.

    The same shape the phone assembles from its local draft, which is what makes the on-device
    report and the server report the same document rather than two similar ones.
    """
    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}

    # ── SORTED BY `ordinal` ALONE, AND A TIE IS DELIBERATELY LEFT TO THE STABLE SORT ──────────────
    #
    # A `(ordinal, createdAt)` tuple was tried here on 2026-08-26 and REVERTED the same day. It was
    # meant to close a real but narrow gap — `save_stage` writes `ordinal` only for the rows the
    # payload names, so a row a colleague added on the handset after this browser last read the stage
    # keeps its old ordinal and can collide with one the designer has just dragged into that place.
    # Two reasons it made things worse, both found by review rather than by reasoning:
    #
    # 1. IT MOVES SINGLETONS, WHERE THE "TIE" IS NOT A RACE BUT THE NORMAL STATE. Every singleton row
    #    carries `ordinal = 0`, and the fold below is last-wins — so ordering singleton rows by
    #    `createdAt` deterministically pins THIS reader to the newest duplicate. The other two readers
    #    of the same rows do not sort at all: `workshop_completeness` iterates `entries` directly, and
    #    so does `_stages_payload`. `SINGLETON_CLIENT_KEY`'s docstring states the invariant plainly —
    #    all three take last-write-wins over one arbitrary order, and therefore AGREE on any single
    #    read. Moving one of the three meant the stage form and the readiness score read one row while
    #    the .docx submitted to the ministry read the other, permanently, on any workshop carrying a
    #    pre-2026-08-22 duplicate pair. That is a far worse defect than the one being fixed.
    #
    # 2. `entries` IS `list[Any]`, AND THE ATTRIBUTE IS NOT GUARANTEED TO EXIST. Not a nullability
    #    question — an ABSENT one. `tests/test_report_sketch_prototype_mapping.py` builds its rows as
    #    `SimpleNamespace` without `createdAt`, so the tuple raised `AttributeError` and took out the
    #    one test asserting "the designer's arrangement is the order the report prints them in" — the
    #    single assertion protecting the printed row order of a filed report.
    #
    # SO THE COLLECTION-TIE GAP IS STILL OPEN, and it is recorded rather than half-closed. Shutting it
    # properly means moving all three readers together, in one change, with a tie-break that cannot
    # reorder singletons — not a tuple in one of them.
    for row in sorted(entries, key=lambda r: r.ordinal):
        data = dict(row.data or {})
        # THE ROW'S OWN ID TRAVELS WITH IT, under the same `_`-prefixed name the stage GET uses.
        #
        # Without it the builder cannot resolve an intra-workshop reference, and eleven REF fields
        # that the report prints as table columns printed a raw cuid instead of a name: a cost
        # sheet headed `cmsik2jg8000eh8xc1lcy661a` rather than "Bandha table runner". The target of
        # every one of those refs is another row of THIS workshop and is already in `entries` — the
        # only thing missing was the key to match it by. The registry never declares a field
        # beginning with `_`, so this cannot collide with captured data and is ignored by every
        # renderer that walks the field list.
        data["_entryId"] = row.id
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            # THE RESERVED ROW BELONGS IN NEITHER BUCKET, and this is the fourth of the four places
            # the design's price is paid. Left to fall through it would land in `collections` as a
            # collection of an entity the registry does not know — never printed, because
            # `_render_stage` walks `spec.collections`, and never scored, because the completeness
            # loop does the same, so a designer's answers would be silently absent from the document
            # they were captured for. They reach the report on their own attribute instead, paired
            # with the definition that says what each key was asking: see
            # `attach_report_custom_sections`, which reads these same rows.
            continue
        if cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.stageKey] = data
        else:
            collections.setdefault(row.stageKey, {}).setdefault(row.entityKey, []).append(data)

    return WorkshopData(
        workshop_id=record.id,
        title=record.title,
        singletons=singletons,
        collections=collections,
        # THE `_custom` ROW IS EXCLUDED FROM THE BUCKETS ABOVE BUT NOT FROM THIS ONE, deliberately.
        # Its answers reach the report on their own attribute through
        # `attach_report_custom_sections`, which reads the same rows and needs the same attribution;
        # dropping its stamps here would make the custom section the only part of the document with
        # no answer to "who wrote this". Keying by entry id is what makes that free — a map that
        # nothing indexes by bucket cannot be confused by a row that lives in neither.
        field_provenance=entry_provenance.resolve_entry_provenance(entries),
        generated_at=datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
    )


def reference_ids(entries: list[Any]) -> dict[str, set[str]]:
    """``model -> the ids that model's REF fields hold``, found through the registry.

    Pure, and separated from the loading below so it can be tested without a database. Walking
    the registry rather than scanning every value for something cuid-shaped is the same rule
    :func:`_media_ids` follows and for the same reason: a new REF field is picked up the day it
    is declared, and a text field that happens to contain an id is never mistaken for one.

    Only the external models in :data:`REFERENCE_MODELS` are collected. A ``Dw…`` ref_model points
    at another entry OF THIS SAME WORKSHOP — ``prototype.sketchRef`` at a sketch — and that record
    is already in ``entries``; looking it up as though it were an artisan would query a delegate
    that does not exist.
    """
    ref_keys: dict[str, dict[str, str]] = {}
    for spec in stages():
        for entity in spec.entities:
            keys = {f.key: f.ref_model for f in entity.fields
                    if f.type is FieldType.REF and f.ref_model in REFERENCE_MODELS}
            if keys:
                ref_keys[entity.key] = keys

    found: dict[str, set[str]] = {}
    for row in entries:
        keys = ref_keys.get(row.entityKey)
        if not keys:
            continue
        data = row.data or {}
        for key, model in keys.items():
            value = data.get(key)
            if isinstance(value, str) and value:
                found.setdefault(model, set()).add(value)
    return found


def _reference_place(row: Any, model: str) -> tuple[str, str, str]:
    """``(place, district, state)`` for one referenced record, from wherever it states them.

    THE STATED ADDRESS, NEVER THE PROVENANCE COORDINATE. ``Location`` carries both — where the
    device was when the record was captured, and where the subject says they live — and the two
    are routinely a hundred kilometres apart, because a researcher interviews six artisans in one
    afternoon at a cooperative hall. Reading the fix would draw every one of those six on the
    hall, and the map would report a workshop whose artisans all live in the same village.

    ``place`` is the free-text fallback the artisan table carried before the stated-address
    columns existed, and it is still the only thing filled in on the older half of the corpus.

    ONLY ``Artisan`` CAN ANSWER THE DISTRICT AND THE STATE, AND THAT IS DELIBERATE. This function
    is called for EVERY model, but it reads ``row.location`` and only ``REFERENCE_MODELS["Artisan"]``
    includes that relation — so for a product, a tool, a process or a craft the last two elements
    are always ``""`` and the first falls through to the denormalised ``place`` string. Nobody sees
    it today: ``report_builder._artisan_points`` is the sole consumer of the district and state and
    it filters to ``ref_model == "Artisan"``.

    THAT PARAGRAPH USED TO END "DO NOT FIX IT BY ADDING ``include={"location": True}`` TO THE OTHER
    MODELS", AND THE INCLUDE IS NOW ON TWO OF THEM. The warning was correct about the mechanism and
    wrong to conclude the data could not cross. Its reasoning was that this function prefers
    ``location.village`` over ``place`` and runs at RENDER time, so loading the relation would change
    the place printed in already-submitted documents. True — of THIS function. A product's and a
    tool's stated address now reaches the workshop through their ``data`` lambdas at SAVE time,
    into boxes of their own, where hydration only fills blanks and the boxes are new.

    So the include is on, and the guard at the top of the body is what keeps this function's answer
    identical to what it was: ``place`` for a product or a tool comes from the denormalised column
    whether or not the relation is loaded, and the district and state stay empty for every model but
    ``Artisan``. The behaviour this docstring describes is now enforced by a condition instead of by
    the accident of a relation nobody had switched on — which is the actual lesson, because relying
    on that accident is how an include added two thousand lines away rewrites a ministry's document.
    """
    # ── THE GUARD, AND WHY IT IS AN ARGUMENT RATHER THAN A CONDITION ─────────────────────────
    #
    # This function runs at RENDER time (`load_report_references` -> `attach_report_references`), so
    # whatever it returns for `place` is printed into a document that may already have been
    # submitted. Until now only `REFERENCE_MODELS["Artisan"]` included the `location` relation, so
    # for every other model `location` was absent, the first element fell through to the
    # denormalised `place` string, and the paragraph above warned in capitals against "fixing" that
    # by switching the include on — because doing so would silently start preferring
    # `location.village` and change the place printed in reports already filed.
    #
    # The warning was right about the render path and wrong to conclude the data could not cross.
    # A product's and a tool's STATED address now DO reach the workshop — through their `data`
    # lambdas at SAVE time, into boxes of their own — which is a different path with none of this
    # hazard, because hydration only fills blanks and those boxes are new. Switching the include on
    # for those two models is what makes that possible, and this guard is what stops the render path
    # noticing: `place` for a product or a tool keeps coming from the denormalised column exactly as
    # it did before, and the district and state stay empty for everything except an artisan.
    #
    # KEYED ON THE MODEL, not on whether `location` happens to be loaded, and that distinction is
    # the whole point: "the relation is absent" was an accident of configuration that this function
    # was relying on for its behaviour, and relying on an accident is how switching an include two
    # thousand lines away rewrites a ministry's document.
    if model != "Artisan":
        return (str(getattr(row, "place", "") or ""), "", "")
    location = getattr(row, "location", None)
    village = str(getattr(location, "village", "") or "") if location is not None else ""
    district = str(getattr(location, "district", "") or "") if location is not None else ""
    state = str(getattr(location, "state", "") or "") if location is not None else ""
    return (village or str(getattr(row, "place", "") or ""), district, state)


async def _load_one_reference_model(
    spec: ReferenceModel, ids: set[str], model: str
) -> tuple[list[Any], dict[str, str]]:
    """One reference model's rows and their photographs, or ``([], {})`` if it could not be read.

    Split out of :func:`load_report_references` so the three models can be gathered. The failure
    boundary is deliberately HERE, around one model's pair of reads, exactly where the loop it
    replaced put it.
    """
    try:
        rows = await getattr(db, spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=spec.include or None
        )
        return rows, await _reference_photos(spec, [r.id for r in rows])
    except Exception:
        # Blind, and it has to be: Prisma raises a different class for a delegate whose table
        # has been renamed, a connection that dropped mid-report and a record whose include
        # no longer matches the schema, and the answer to all three is the same. One
        # unjoinable model must not lose a report that is the end of two weeks of fieldwork.
        logger.exception("Could not load %s references for a workshop report", model)
        return [], {}


async def load_report_references(entries: list[Any]) -> dict[str, ReferencedRecord]:
    """Everything the report needs about the records its REF fields point AT, in one query each.

    Two facts, and neither of them can come from the stage entry itself:

    * THE PHOTOGRAPH OF A RECORD *THESE TWO MAPPINGS* DO NOT SEED. ``prototype.productRef`` and
      ``existingProduct.artisanRef`` copy a name and nothing else, deliberately: the parent
      product's photograph is not a photograph of the PROTOTYPE, and an artisan's portrait is not a
      photograph of a product. Without this load the report described a prototype of a documented
      product with the product's photograph nowhere in it, one join away in the media table.

      THIS USED TO SAY "a gallery of the designer's photographs that a seeded picture would
      overwrite", AND THAT OVERWRITE CANNOT HAPPEN. ``hydrate_entries`` never overwrites a gallery
      — "A gallery is never overwritten, only seeded when empty" — and its clear-on-re-point loop
      skips ``target.type.is_multi`` for exactly that reason. What a seeded picture would do is
      STAND IN FOR the designer's photographs while the gallery is still empty, which is a weaker
      claim and the true one. It is also worth knowing while reading this that hydration DOES seed
      one gallery: ``existingProduct.productRef`` maps ``photo`` -> ``productPhotos``, because the
      documented product's own photograph IS a photograph of the documented product.
    * WHERE AN ARTISAN LIVED, FOR THE ROWS WHOSE OWN COPY IS NOT THERE. This bullet used to say
      "No roster field holds a district — the participant row records a village as free text — so
      the map of who came from where cannot be drawn from the entries", and reading that as true
      is what kept the ministry's map wrong. ``REFERENCE_HYDRATION["participant.artisanRef"]``
      copies ``village``, ``district``, ``state``, ``pincode`` and ``address`` onto the roster row
      at SAVE time and ``participant`` declares all five, so the row carries a frozen stated
      address and ``report_builder.ReportBuilder._artisan_points`` reads that first. What this load
      carries is the FALLBACK: rows hydrated before that mapping widened, and rows where the
      picker was never used because the artisan walked in on day two and was typed by hand. Do not
      delete it — those rows drop off the map — and do not promote it back to first choice, which
      is what let the map and the participant table two pages earlier disagree about one artisan.

    One ``find_many`` per model, not one per row: a roster of forty artisans is one query. The
    three models are then GATHERED rather than walked in sequence — see the comment below. Failure
    of any single model is swallowed to a log line rather than raised, because a report is the end
    of two weeks of fieldwork and losing it entirely over a picture that could not be joined is
    the wrong trade — the map loses pins, the caption says how many, and the document still prints.
    """
    wanted = reference_ids(entries)
    out: dict[str, ReferencedRecord] = {}
    # THE THREE MODELS ARE GATHERED, NOT LOOPED. Each is two dependent reads (the rows, then one
    # photograph per row) and nothing in one model's pair informs another's, so sequentially this
    # was up to six round trips at ~756ms each on the cross-region link this deployment runs
    # (`services/concurrency`) for work the graph allows in two. The `try/except` stays INSIDE the
    # per-model coroutine, which is what keeps the documented guarantee intact: one unjoinable
    # model still loses only its own references, never the report.
    models = sorted(wanted)
    results = await gather_reads(
        *(_load_one_reference_model(REFERENCE_MODELS[model], wanted[model], model)
          for model in models)
    )
    for model, (rows, photos) in zip(models, results, strict=True):
        spec = REFERENCE_MODELS[model]
        for row in rows:
            place, district, state = _reference_place(row, model)
            out[str(row.id)] = ReferencedRecord(
                model=model,
                label=spec.label(row),
                # `.id` off the ReferencePhoto, never the object: `ReferencedRecord.photo` is a
                # bare media id that goes on to `media_resolver`, and the caption reaches the
                # report through the stage entry's own `*Caption` field instead.
                photo=(found.id if (found := photos.get(row.id)) else ""),
                place=place,
                district=district,
                state=state,
            )
    return out


async def attach_report_references(data: WorkshopData, entries: list[Any]) -> list[str]:
    """Load the referenced records onto ``data`` and return the media ids they contributed.

    The ids come BACK rather than being fetched here because they have to reach
    :func:`media_resolver`, and a photograph the builder places but the resolver never looked up
    resolves to None and is silently dropped — which is exactly the "the picture is in the
    database and not in the report" failure this whole path exists to end. Returning them makes
    the dependency visible at the one call site instead of hiding it in two functions that have
    to be kept in step.
    """
    data.references = await load_report_references(entries)
    return [r.photo for r in data.references.values() if r.photo]


def workshop_media_ids(entries: list[Any]) -> set[str]:
    """Every media id this workshop's stage entries reference. The public name for :func:`_media_ids`.

    **A NAME AND NOT A SECOND IMPLEMENTATION**, added when the AI verbs needed to answer "is this
    photograph one of THIS workshop's" before spending provider credit on it. The route could not
    import ``_media_ids`` — a leading underscore across a module boundary is a promise being broken —
    and it must not walk the registry itself, because a second walker is a second thing to forget
    when a media field is added. The plan's §4 records what five independent media walkers already
    cost this repository; this is deliberately not a sixth.
    """
    return _media_ids(entries)


def _media_ids(entries: list[Any]) -> set[str]:
    """Every media id referenced anywhere in the record, found through the registry.

    Walking the registry rather than scanning every value for something id-shaped means a new
    media field is picked up automatically, and a text field that happens to contain a cuid is
    not mistaken for a photograph.
    """
    media_keys: dict[str, set[str]] = {}
    # RICH_TEXT fields carry media too, and NOT in the field's own value: a designer who places a
    # photograph inside a narrative puts its id in an IMAGE block, several levels down in the
    # document JSON. Walking only the media-typed fields would miss every one of them, the
    # resolver would answer None, and `to_report_blocks` would drop the picture — silently, in
    # exactly the way a placed-in-the-prose photograph is most likely to be noticed missing.
    rich_keys: dict[str, set[str]] = {}
    for spec in stages():
        for entity in spec.entities:
            keys = {f.key for f in entity.fields if f.type.is_media}
            if keys:
                media_keys[entity.key] = keys
            rich = {f.key for f in entity.fields if f.is_rich_text}
            if rich:
                rich_keys[entity.key] = rich

    found: set[str] = set()
    for row in entries:
        data = row.data or {}
        for key in media_keys.get(row.entityKey, ()):
            value = data.get(key)
            if isinstance(value, str) and value:
                found.add(value)
            elif isinstance(value, (list, tuple)):
                found.update(str(v) for v in value if v)
        for key in rich_keys.get(row.entityKey, ()):
            found.update(rich_text.media_ids(data.get(key)))
    return found


# ── HOW MUCH OF THIS BOX'S MEMORY ONE REPORT'S PHOTOGRAPHS MAY OCCUPY ──────────────────────────
#
# `MediaIndex.prefetch` had NO cap in any dimension: not per image, not in aggregate, not on the
# number of images. It read N whole objects into a dict and that dict stayed live across the whole
# render, in the single-worker WEB process, reached from `POST /design-workshops/{id}/report`. That
# is strictly worse than the single oversized read docs/SCALABILITY.md §5.1 is built around — one
# read is one object, this is all of them at once — and §5.1 did not name it.
#
# The aggregate is what actually matters here and the per-image cap is the guard on the tail: a
# report is dozens of photographs of ordinary size (median 2.01 MiB, p90 14.28 MiB MEASURED across
# this repository's media), so it is the SUM that reaches into the hundreds of megabytes, and one
# 668 MiB object among them that finishes the box.
#
# Both are lowered further by what the box says is free — see `services/memory_budget`. On a machine
# with memory to spare (every development box, where no free-memory source exists) these constants
# ARE the answer and nothing changes. On the 1 GiB pilot under load the budget shrinks and the tail
# of a large report is left out — VISIBLY, as a warning naming the count, never silently.
REPORT_IMAGE_BUDGET_BYTES = 96 * 1024 * 1024
REPORT_IMAGE_MAX_BYTES = 16 * 1024 * 1024


class MediaIndex:
    """Every image the record references, resolved once.

    Two lookups, both pure by the time a renderer sees them: ``ref(media_id)`` gives the
    geometry, ``blob(ImageRef)`` gives the bytes. Both are plain dict reads, because the
    renderers are deliberately synchronous — that is what lets the Kotlin port be a
    transliteration rather than a reimplementation — and forty awaits cannot happen inside one.

    The object keys are held rather than the bytes until :meth:`prefetch` is called, so a
    template that excludes photographs never pays to download forty of them.

    ``withheld`` is every media id the record NAMED that the lookup did not hand back — either the
    row is gone or the caller is not entitled to that uploader's files. The renderer cannot tell
    the difference and neither can this class; what matters is that the caller turns the count into
    a warning instead of printing a report that is quietly short of photographs.

    ``oversize`` is the same idea for a different cause, and :meth:`prefetch` fills it in: every
    photograph left out because embedding it would have taken this process past its memory budget.
    A separate list from ``withheld`` because the two need different sentences — a withheld photo is
    a permission or a deletion and nothing the designer can act on, while an oversize one is this
    box's ceiling on this run, which a smaller upload or a quieter moment would clear.
    """

    def __init__(
        self,
        refs: dict[str, ImageRef],
        keys: dict[str, str],
        *,
        withheld: tuple[str, ...] = (),
        sizes: dict[str, int] | None = None,
    ) -> None:
        self._refs = refs
        self._keys = keys
        self._blobs: dict[str, bytes | None] = {}
        # The DECLARED size of each image, off its media row, where the row carried one. A client's
        # claim rather than a fact — nothing in this codebase reconciles it against the stored
        # object — so it is used for one thing only: skipping a fetch that was going to be refused
        # anyway. The budget is spent against the REAL length of what arrives.
        self._sizes = sizes or {}
        self.withheld = withheld
        self.oversize: list[str] = []
        self.budget_spent = 0

    def ref(self, media_id: str) -> ImageRef | None:
        return self._refs.get(media_id)

    def blob(self, image: ImageRef) -> bytes | None:
        return self._blobs.get(image.source)

    def prefetch(self, wanted: tuple[ImageRef, ...], *, budget: int | None = None) -> None:
        """Download the bytes of exactly the images the built document referenced, WITHIN A BUDGET.

        Synchronous, and called from inside ``asyncio.to_thread`` along with the render itself.
        ``get_object_bytes`` is a blocking boto3 call, so running it on the worker thread is both
        correct and what the rest of this codebase does with an S3 read it cannot stream.

        **AND THIS ONE CANNOT BE STREAMED, WHICH IS WHY THE FIX IS A BUDGET AND NOT A TEMP FILE.**
        The renderers take the bytes through :meth:`blob`, synchronously, one dict read at a time —
        that is what lets the Kotlin port be a transliteration — so an image has to BE ``bytes`` by
        the time ``render_pdf`` asks for it. Spooling forty images to forty temp files would move the
        problem to the disk and still have to read each one back. What was actually wrong here was
        never the shape of the read; it was that there was no limit on how many of them were held.

        **THIS LOOP USED TO HAVE NO CAP IN ANY DIMENSION** — not per image, not in aggregate, not on
        the number of images — and everything it fetched stayed live in ``self._blobs`` across the
        whole render. See the constants above for what that meant on a 1 GiB box. Now a running
        total is kept and an image that would take it past the budget is left out.

        **LEFT OUT, AND SAID SO.** Every skipped id goes on ``self.oversize``, which
        ``render_report`` turns into a warning naming the count. Silently returning a report short
        of photographs is the one outcome this must never have: the designer is about to attach the
        file to an email, and a picture that is missing without a word is indistinguishable from one
        that was never taken.

        **THE ORDER OF ``wanted`` IS THE PRIORITY ORDER**, and that falls out of the document rather
        than being chosen here: ``document.images`` is in the order the renderer will place them, so
        a budget that runs out costs the LAST pictures in the report and never the first. Nothing is
        sorted, deliberately — sorting by size would fit more pictures in and would decide which
        page loses one on a criterion no reader could guess.
        """
        from app.services.memory_budget import budget_bytes
        from app.services.s3 import get_object_bytes

        # ONE READING OF FREE MEMORY FOR THE WHOLE LOOP, not one per image. The budget is a promise
        # about this render's total, and re-deriving it mid-loop would let the total drift with
        # whatever else the box happened to be doing between two photographs.
        ceiling = budget if budget is not None else budget_bytes(REPORT_IMAGE_BUDGET_BYTES)
        per_image = min(REPORT_IMAGE_MAX_BYTES, ceiling)
        for image in wanted:
            if image.source in self._blobs:
                continue
            key = self._keys.get(image.source)
            if not key:
                self._blobs[image.source] = None
                continue
            declared = self._sizes.get(image.source, 0)
            if declared and (
                declared > per_image or self.budget_spent + declared > ceiling
            ):
                # Refused without a round trip. The declared size is a claim, so it can only be
                # trusted in this direction: a row that says it is too big is not worth fetching to
                # find out, while a row that says it is small is checked against what arrives.
                self._blobs[image.source] = None
                self.oversize.append(image.source)
                continue
            try:
                blob = get_object_bytes(key)
            except Exception:  # noqa: BLE001 - one unreadable photo must not fail the export
                # Boto3 raises a different class for a missing key, a permission problem and a
                # timeout, and the answer to all three is the same: leave the picture out and
                # let the caller report it as a warning. A designer waiting in a field for a
                # report does not benefit from an exception naming the S3 error code.
                self._blobs[image.source] = None
                continue
            size = len(blob)
            if size > per_image or self.budget_spent + size > ceiling:
                # THE REAL LENGTH, WHICH IS THE ONE THE BUDGET IS SPENT AGAINST. Dropping the
                # reference before continuing so the bytes are collectable immediately rather than
                # staying alive until the loop variable is rebound on the next photograph.
                blob = None
                self._blobs[image.source] = None
                self.oversize.append(image.source)
                continue
            self.budget_spent += size
            self._blobs[image.source] = blob


async def media_resolver(
    entries: list[Any], *, viewer: Any, extra_ids: Iterable[str] = ()
) -> MediaIndex:
    """One query for every image in the record the caller is entitled to be handed.

    ``extra_ids`` are photographs that belong to a record the report REFERENCES rather than to a
    field of the record itself — an artisan's portrait behind a roster row, the catalogue picture
    of the product a prototype was based on. They cannot be found by walking the registry's media
    fields because they are not IN the entries at all; :func:`attach_report_references` is what
    discovers them, and passing them here is what stops the builder placing an image the resolver
    has never heard of and the renderer therefore drops without a word.

    ``viewer`` IS REQUIRED, AND THAT IS THE FIX FOR A REAL LEAK. The ids come from stage data,
    which is whatever a client wrote there: ``GET /api/media`` hands every signed-in account the id
    of every photograph in the repository while deliberately stripping the URL, so pasting a
    stranger's id into an IMAGE field and pressing "Generate report" used to put their photograph —
    an artisan's portrait, a photographed Aadhaar card — into the .docx that came back. Embedding
    the bytes in a document the caller downloads IS taking the file, so the predicate is the one
    every other download surface uses (``export.py``, ``data_browser.py``):
    ``owned_or_granted_where(user, owner_field="uploadedById")``. It is a keyword with NO default
    so that a future call site cannot resolve media by forgetting to ask who is asking.

    The filter is AND-composed under the id list rather than merged into it, because it is an
    ``OR`` of its own and a flat merge would let it overwrite the ids.
    """
    from app.services.records import owned_or_granted_where

    ids = _media_ids(entries) | {str(i) for i in extra_ids if i}
    if not ids:
        return MediaIndex({}, {})

    where: dict[str, Any] = {"id": {"in": sorted(ids)}}
    entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
    if entitled:
        where = {"AND": [where, entitled]}
    rows = await db.mediafile.find_many(where=where)
    refs: dict[str, ImageRef] = {}
    keys: dict[str, str] = {}
    # Carried through so `MediaIndex.prefetch` can refuse an image the row already says is too big
    # WITHOUT a round trip. Read off the query that was already being made, so it costs nothing.
    sizes: dict[str, int] = {}
    for row in rows:
        # Only pictures. A PDF or an audio file linked to a stage is real data, but embedding
        # it as an image would put a broken frame in the report.
        if str(getattr(row, "mediaType", "")) not in ("IMAGE", "MediaType.IMAGE"):
            continue
        extra = row.extraMetadata or {}
        rotation = _int_or_zero(extra.get("orientationDegrees") or extra.get("rotation"))
        refs[row.id] = ImageRef(
            source=row.id,
            width_px=_int_or_zero(extra.get("width")),
            height_px=_int_or_zero(extra.get("height")),
            rotation_deg=rotation if rotation in (90, 180, 270) else 0,
            mime_type=row.mimeType or "image/jpeg",
        )
        keys[row.id] = row.objectKey
        sizes[row.id] = _int_or_zero(getattr(row, "sizeBytes", 0))
    # Asked for and not returned: deleted, or another uploader's file this caller may not take.
    # One query cannot tell those apart and a second one would cost a cross-region round trip on
    # the path `_report_inputs` exists to keep short, so the caller's warning names neither.
    return MediaIndex(
        refs, keys, withheld=tuple(sorted(ids - {row.id for row in rows})), sizes=sizes
    )


def _int_or_zero(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


async def attach_report_transcripts(
    data: WorkshopData, entries: list[Any], *, viewer: Any, requested: bool | None = None
) -> list[str]:
    """Load the workshop's transcripts onto ``data`` when this report is meant to carry them.

    Returns the warnings to show beside the download — a recording still being transcribed is a
    gap in an annexure the designer explicitly asked for, and they should hear about it from the
    generator rather than by counting paragraphs in a 60-page document.

    Reads the toggle in the same order the whole report pipeline reads everything else: the request
    wins if it said anything, the saved stage-20 settings otherwise, and OFF when neither spoke.
    Nothing is loaded at all in the off case, so a deployment that never turns this on does not pay
    a query for it.

    ``viewer`` gates which recordings may be read at all — see :func:`media_resolver` for why an
    AUDIO id on a stage is not proof the caller may have the file. A clip that is refused is
    reported as a warning rather than dropped, because an annexure two recordings short looks
    exactly like two recordings that were never made.
    """
    if not wants_transcripts(requested, data.singleton("REPORT_GENERATION")):
        return []
    items = await load_transcript_items(entries, viewer=viewer)
    warnings: list[str] = []
    refused = len(audio_references(entries)) - len(items)
    if refused > 0:
        warnings.append(
            f"{refused} recording(s) attached to this workshop could not be included in the "
            "transcript annexure: they were uploaded by another account, or the file is gone."
        )
    if not items:
        return warnings
    attach_transcripts(data, items)
    return warnings + annexure_warnings(items)


async def attach_report_ai_layers(
    data: WorkshopData,
    workshop_id: str,
    *,
    viewer: Any,
    readable_media: Callable[[set[str]], Awaitable[set[str]]],
    requested: bool = False,
) -> list[str]:
    """Load this workshop's ACCEPTED AI layers onto ``data``. Returns the warnings to show.

    NOTHING IS LOADED UNLESS THE REPORT WAS ASKED FOR THE ANNEXURE, so a deployment that never turns
    this on never pays a query for it — the same rule :func:`attach_report_transcripts` follows, and
    for the same reason.

    ── ``viewer`` IS NOT OPTIONAL, AND ITS ABSENCE WAS A LIVE DISCLOSURE DEFECT ──────────────────

    A layer's text is a COPY OF A TRANSCRIPT, and a transcript is the CONTENT of a recording. Who may
    read one is decided **per file** by ``owned_or_granted_where(user, owner_field="uploadedById")``
    and NOT by who may open the workshop. ``ai_layers.accepted_layers`` states in capitals that its
    read is not entitlement-filtered and that its caller must be.

    WHAT THAT PER-FILE GATE ADMITS WAS CORRECTED HERE ON 2026-08-27, AND THE WORKED EXAMPLE UNDER IT
    WAS REPLACED, BECAUSE BOTH HAD GONE BACKWARDS. This paragraph used to end "and those two sets
    genuinely differ, because a ``DesignWorkshopViewer`` grant carries read and stage writes and says
    nothing whatever about media", and the example was "designer A uploads the interviews and accepts
    their layers; designer B holds only a viewer grant and no data-access grant from A. On the
    AI-layers screen every row comes back ``textWithheld``. ``GET /design-workshops/{id}/transcripts``
    refuses B the same text." Both are FALSE, and had been since ``owned_or_granted_where`` grew a
    THIRD arm keyed on the media TAG rather than on the uploader
    (``records._design_workshop_media_branches``): it admits every ``MediaFile`` whose
    ``linkedRecordType`` is ``designWorkshop`` and whose ``linkedRecordId`` is a workshop this account
    may open. B, holding a viewer grant on the workshop those interviews are tagged to, is therefore
    SHOWN them — here, on the transcripts endpoint, in the annexure, and (since 2026-08-27, through
    ``records.media_url_scope``) as a ``url`` on ``GET /media``.
    ``backend/tests/test_media_entitlement.py`` pins both directions, in
    ``test_a_granted_co_designer_is_shown_the_workshops_own_recordings`` and
    ``test_a_designer_with_no_grant_is_still_refused_the_same_recording``. The stale sentence is
    recorded rather than merely deleted because of what it cost: it described a real withholding as
    arbitrary, which is exactly how a reader talks themselves into removing one. It also did not live
    here alone — the same claim had been copied into the AI-layer services, their tests and both
    clients, so correcting the one file a bug report named would have left a grep answering the old
    way from everywhere else. ``api/routes/design_workshops.list_ai_layers`` carries the same
    correction, written on the same date; the two are meant to be read together and must move
    together.

    THE TWO SETS STILL DIFFER, IN THE OTHER DIRECTION, AND THAT IS WHY ``viewer`` IS NOT OPTIONAL. A
    grant admits THIS WORKSHOP'S TAGGED FILES AND NOTHING ELSE. A stage field stores a media id, and
    nothing obliges that id to name a file tagged to this workshop — so a layer registered here can
    stand on a recording its uploader filed under a different workshop or under none, typed onto a
    stage by anybody who may edit one. Those are gated on uploader identity exactly as they were, and
    the only key to them is a ``DataAccessGrant`` from that uploader — the grant that means "may take
    that account's data at large", which a workshop grant is not and never becomes.

    So, the example that DOES happen: designer A uploads an interview and files it under a workshop of
    A's, and its id is typed onto a stage of workshop W; designer B holds a viewer grant on W and no
    data-access grant from A. The tag arm does not reach it — it is tagged to A's workshop, not to W —
    so on the AI-layers screen every layer standing on that recording comes back ``textWithheld``, and
    ``GET /design-workshops/{id}/transcripts`` refuses B the same text (``load_transcript_items``
    gathers the ids off the stages and then applies the same predicate). This function had no
    ``viewer`` at all and copied ``row.text`` verbatim: **B could tick "Include machine-assisted
    text", generate, and receive the complete transcript in a .docx they keep — in the very same file
    whose transcript annexure correctly omitted it and said so.**

    ``readable_media`` is passed IN rather than imported because the predicate lives in the routes
    module beside the endpoint that already applies it (``_readable_media_ids``); one definition,
    two callers, and this service keeps its rule about not importing the API layer.

    **PROVENANCE SURVIVES WITHHOLDING; ONLY THE CONTENT GOES.** That is the whole reason a withheld
    layer is not simply dropped. Which tier and which model produced a passage, and who accepted it,
    is exactly what a reviewer needs and is not the recording's content — and a layer silently absent
    from an annexure is indistinguishable from a layer nobody ever made.

    THE FILTER IS ``ai_layers.accepted_layers`` AND NOT A ``where`` WRITTEN HERE. That function is
    the single definition of what "accepted" means, and it says why in its own docstring: the day
    acceptance grows a condition — an expiry, a second signature, a withdrawal that must be honoured
    mid-render — a report carrying its own copy of the rule would go on printing by the old one and
    nothing would say so. **It is now actually called.** It was not, for a day: this function read
    ``workshop_layers`` and re-derived acceptance from ``acceptedAt``, so five files described a
    single definition that nothing used and the guarantee was documentation. The live set decides
    what prints; the full set is still read, because the warnings need it.

    THE WARNINGS ARE COMPUTED FROM EVERY LIVE LAYER, not from the accepted ones, which is why this
    reads the workshop twice-over rather than only the accepted set. A layer left out for want of an
    acceptance is the one omission that looks like a bug to the designer who turned the annexure on:
    they read the summary on the acceptance screen an hour ago and it is missing from a sixty-page
    document. That sentence is the whole reason this function is not three lines.

    NO PROVENANCE IS INVENTED HERE. ``provider`` and ``modelId`` are NOT NULL columns that may hold
    the literal ``UNRECORDED``, because the media queue has never persisted which of its four
    providers produced a transcript; the renderer turns that into "the model was not recorded" and
    this function passes it through untouched.
    """
    if not requested:
        return []
    from app.services.ai_layers import (
        LayerRuleViolation,
        accepted_layers,
        chain_roots,
        media_ids_to_check,
        source_of,
        workshop_layers,
    )
    from app.services.report_ai_layers import (
        AiLayerItem,
        annexure_warnings as ai_warnings,
        attach_ai_layers,
    )

    try:
        rows = await workshop_layers(workshop_id)
        # THE SINGLE DEFINITION OF "ACCEPTED", ASKED RATHER THAN RE-DERIVED. Membership of this set
        # is what decides whether a layer may print; `acceptedAt` on the row decides only what the
        # warnings say about it.
        printable = {
            str(getattr(row, "id", "") or "")
            for row in await accepted_layers(workshop_id)
        }
    except Exception:
        # Blind, exactly as :func:`attach_report_questionnaires` is blind, and for its reason: one
        # unreadable annexure must not take away a designer's ability to generate any report at all.
        logger.exception("ai layers could not be loaded for workshop %s", workshop_id)
        return [
            "The machine-assisted text could not be read for this report, so the annexure is not "
            "included. Everything else in this document is unaffected."
        ]

    # WHAT EACH LAYER ULTIMATELY STANDS ON, walked through the chain by `chain_roots`, because a
    # SUMMARY three rungs up is still the content of the audio at the bottom of it.
    #
    # `chain_roots` AND NOT `media_roots`, and the difference is a defect this loader would otherwise
    # have shipped the day the verbs landed. `media_roots` answers None for "no recording", which had
    # one meaning — the chain is broken, so withhold — and now has two: a PROOFREAD or an EXPANDED of
    # a designer's own note stands on words, not on a recording, and has no media entitlement to
    # check. Read through the old function every one of those would be withheld from the designer who
    # wrote the note, in their own report, with the annexure printing "the text of this passage is
    # not printed in this copy" over a paragraph they typed themselves.
    roots = chain_roots(rows)
    wanted = media_ids_to_check(roots)
    try:
        readable = await readable_media(wanted) if wanted else set()
    except Exception:
        # FAIL CLOSED. An entitlement check that could not run is not permission — the content is
        # withheld and the provenance still prints, which is the same answer a refused check gives.
        logger.exception("media entitlement could not be resolved for workshop %s", workshop_id)
        readable = set()

    items: list[AiLayerItem] = []
    withheld_count = 0
    for row in rows:
        layer_id = str(getattr(row, "id", "") or "")
        source_kind = source_id = source_text = ""
        try:
            stored = source_of(row)
            source_kind, source_id = stored.kind.value, stored.id
            source_text = stored.text or ""
        except LayerRuleViolation:
            # A row whose source cannot be read still prints, with the origin left blank. Dropping
            # it would be worse: it is accepted text that a person put their name to, and a reader
            # who cannot trace it is better served than one who never learns it is there.
            pass
        # A layer whose root could not be established at all is withheld, not printed. That is the
        # fail-closed direction, and `ChainRoot.withheld_from` is the single place the whole gate is
        # decided so this loader and the list endpoint cannot answer it differently.
        root = roots.get(layer_id)
        text_withheld = root is None or root.withheld_from(readable)
        if text_withheld:
            withheld_count += 1
        items.append(AiLayerItem(
            layer_id=layer_id,
            kind=str(getattr(row, "kind", "") or ""),
            tier=str(getattr(row, "tier", "") or ""),
            provider=str(getattr(row, "provider", "") or ""),
            model_id=str(getattr(row, "modelId", "") or ""),
            model_version=str(getattr(row, "modelVersion", "") or ""),
            language=str(getattr(row, "language", "") or ""),
            source_language=str(getattr(row, "sourceLanguage", "") or ""),
            target_language=str(getattr(row, "targetLanguage", "") or ""),
            produced_at=_iso_or_blank(getattr(row, "producedAt", None)),
            # `printable`, not `acceptedAt is not None`. See the docstring.
            accepted=layer_id in printable,
            accepted_at=_iso_or_blank(getattr(row, "acceptedAt", None)),
            accepted_by=_acceptor_name(row),
            accepted_by_id=str(getattr(row, "acceptedById", "") or ""),
            source_kind=source_kind,
            source_id=source_id,
            # THE NOTE A VERB WAS RUN OVER IS THE "MADE FROM" EVIDENCE FOR THAT LAYER, so it reaches
            # the page as the source LABEL — there is no row for a reader to look up instead. It goes
            # with the content when the text is withheld, for `layer_payload`'s reason: serving the
            # passage while withholding the correction of it hands over the same words in a slightly
            # worse spelling.
            source_label="" if text_withheld else source_text,
            text="" if text_withheld else str(getattr(row, "text", "") or ""),
            text_withheld=text_withheld,
        ))

    attach_ai_layers(data, items)
    warnings = ai_warnings(items)
    # NAMED, NEVER SILENT — the same rule `attach_report_transcripts` follows one function above for
    # exactly this case. A designer who cannot read a colleague's recordings should learn that from
    # the generator, not by counting headings in a sixty-page document.
    printable_withheld = sum(
        1 for item in items if item.text_withheld and item.accepted
    )
    if printable_withheld:
        warnings.append(
            f"{printable_withheld} accepted machine-assisted passage(s) are named in the annexure "
            "with their model and provenance, but their text is not printed: they stand on "
            "recordings uploaded by another account. Ask whoever uploaded them for access to their "
            "media, or ask them to generate this report."
        )
    return warnings


def _acceptor_name(row: Any) -> str:
    """The acceptor's display name when the row carried the relation, else "".

    RESOLVED FROM AN INCLUDE, NOT FROM A SECOND QUERY PER LAYER. ``accepted_layers`` and
    ``workshop_layers`` may or may not have been asked to include the relation; when they were not,
    this returns "" and :attr:`AiLayerItem.acceptor` prints the account id **labelled as an account
    id**. Both are honest; only a bare unlabelled cuid is not, and that is what the annexure printed
    for a day beneath a lead paragraph promising a named person.
    """
    accepted_by = getattr(row, "acceptedBy", None)
    if accepted_by is None:
        return ""
    for attribute in ("fullName", "name", "email"):
        value = getattr(accepted_by, attribute, None)
        if value and str(value).strip():
            return str(value).strip()
    return ""


def _iso_or_blank(value: Any) -> str:
    """A datetime as an ISO string, or "" when it was never recorded.

    Blank rather than a fabricated ``now()``: ``producedAt`` is null precisely when nobody knows
    when the model ran — a transcript produced last March and registered as a layer today would
    otherwise carry today's date, which is the invented fact rule 2 exists to prevent.
    """
    if value is None:
        return ""
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


async def attach_report_custom_sections(
    data: WorkshopData, entries: list[Any], workshop_id: str
) -> list[str]:
    """Load this workshop's own sections and their answers onto ``data``. Returns the warnings.

    THIS FUNCTION LOADS; IT DOES NOT PLACE. Where each section prints is decided by
    ``report_templates.apply_report_settings``, which is the single arbiter of the running order and
    was made one because three call sites used to decide for themselves and disagreed. It reads the
    sections back off ``data`` through ``custom_sections_of`` — the same tuple attached here, so the
    template and the renderer cannot be looking at two different definitions of one workshop.

    NO TOGGLE TO CONSULT, and for ``attach_report_questionnaires``' reason rather than by omission:
    a designer who added a question to this workshop and answered it has already opted in twice
    over. There is nothing here that could happen TO a report — unlike a transcript, which the media
    queue produces from a recording made for some other purpose, or an annexure of model prose.

    THE ANSWERS ARE READ FROM ``entries`` AND NOT LOADED AGAIN. They are already in hand — the
    ``_custom`` row of each stage is an ordinary ``DwStageEntry`` that ``entry_rows`` returned with
    everything else — so the only query this function makes is for the DEFINITION, which is the half
    that says what each stored key was asking.

    A DEFINITION THAT COULD NOT BE READ COSTS THE SECTIONS AND NOT THE REPORT. That is the opposite
    of the choice ``save_stage`` makes with the same loader, and the two are deliberately different:
    a save that silently dropped a designer's answers as unknown keys would lose fieldwork, while a
    report is the end of two weeks of it and must not be refused over an appendix. The designer is
    told either way.
    """
    definition = await custom_sections.load_definition_or_empty(workshop_id)
    if definition.is_empty:
        return []

    values_by_stage: dict[str, dict[str, Any]] = {
        row.stageKey: dict(row.data or {})
        for row in entries
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY
    }

    items: list[CustomSectionItem] = []
    for section in definition.sections:
        item = CustomSectionItem(
            key=section.key,
            title=section.title,
            stage_key=section.stage_key,
            description=section.description,
            sort_order=section.sort_order,
            fields=tuple(
                CustomReportField(
                    key=f.key,
                    label=f.label,
                    type=f.type.value,
                    unit=f.unit,
                    options=tuple((o.value, o.display) for o in f.options),
                    required=f.required,
                    # THE DESIGNER'S CHOICE OF CAPTURE TIER, WHICH THIS LOOP USED TO DROP.
                    #
                    # `CustomFieldSpec.tier` is a real choice in the section editor ("Which
                    # capture tier this question belongs to"), it is validated, it is stored and
                    # it is serialised to both clients — and six attributes were copied here and
                    # this was not, so every custom question arrived at the renderer carrying the
                    # BASIC default and was printed by every template. Every REGISTRY field
                    # passes `ReportBuilder._visible` (`spec.tier.rank <= template.max_tier.rank`);
                    # a designer's own question passed no gate at all. COMPACT_SUMMARY is the only
                    # template whose `max_tier` is not ADVANCED, and its own description promises
                    # "Basic-tier fields only, one photograph per prototype" — so it correctly
                    # suppressed every Standard and Advanced registry field and then printed the
                    # designer's Standard-tier answers underneath in full. One document, two
                    # rules, one declared attribute.
                    #
                    # `.value` AND NOT THE ENUM, because `CustomReportField` is a flat value
                    # object with no server type inside it — the phone builds the same shape from
                    # its own cached definition, and a renderer that could reach `Tier` would not
                    # be transliterable. See that class's docstring.
                    #
                    # THE SCORER IS UNAFFECTED and that is checked rather than assumed:
                    # `custom_scoring` hands `stage_completeness` every field of the stage with no
                    # tier test, deliberately, because completeness is a fact about the workshop
                    # and not about the file being generated. Only the RENDERER filters.
                    tier=f.tier.value,
                    # A RETIRED SECTION'S FIELDS ARE RETIRED, whatever their own flag says, and this
                    # is not cosmetic. A section's flag and its fields' flags are two facts, and only
                    # one of them answers "is this asked" for a field under a section nobody is being
                    # asked — a row retired by hand, restored from a backup, or written by a build
                    # before `plan_definition`'s section RETIRE carried a RETIRE plan for every live
                    # field under it, which is what makes this belt and braces rather than the only
                    # guard. The completeness annexure re-scores every stage through
                    # `custom_scoring`, which reads exactly this flag. Passing the field's own flag
                    # through made the annexure count a required question of a section nobody is
                    # being asked, so a document printed "3 required fields outstanding" for a stage
                    # the readiness screen and `workshop_completeness` both called complete: one
                    # workshop, two
                    # arithmetics, which is the defect this repository has already shipped once.
                    # Marked here rather than in the loader because it is the REPORT's reading of a
                    # retired section: the answers still print, under the wording they were given,
                    # and the marker beside them says they are no longer asked.
                    retired=f.retired or section.retired,
                )
                for f in section.fields
            ),
            values=values_by_stage.get(section.stage_key, {}),
        )
        if section.retired and not item.answered_count:
            # A retired section nobody ever answered is not evidence of anything, and printing an
            # empty heading for every block a designer thought better of would fill a submitted
            # report with the history of its own form.
            #
            # ASKED THROUGH `answered_count` AND NOT THROUGH THE TRUTHINESS OF THE STORED VALUES.
            # The first version tested `values.get(key)` directly, which reads a recorded ZERO as no
            # answer — so a retired "How many looms?" answered "0", which is a finding and a
            # perfectly ordinary one in a cluster where the looms have been sold, was dropped out of
            # the document altogether. `answered_count` is `_has_answer`, which this module uses for
            # every other decision about the same values.
            continue
        items.append(item)

    attach_custom_sections(data, items)
    warnings: list[str] = []
    # THE WARNING ASKS THE RENDERER'S OWN QUESTION AND NOT A SECOND ONE THAT LOOKS LIKE IT.
    #
    # Everything in `items` is attached and spliced into the template unconditionally; whether a
    # section PRINTS is decided solely by `append_custom_section`, which appends nothing when
    # `has_content` is false. `has_content` is true for an answered section OR one with a live
    # required field — deliberately, so an unanswered required question prints "Not recorded."
    # and its absence is visible in the document.
    #
    # This list used to be `not item.answered_count and any(not f.retired …)`, which is true for
    # EXACTLY the class `has_content` prints: zero answers plus at least one live required field.
    # So a designer who added "Dye bath log" with one required question and did not reach it got
    # a .docx containing the heading and "Dye source — Not recorded.", beside a warning saying
    # "1 of this workshop's own section(s) … are not in this file: Dye bath log". They then
    # either hunt for a bug that is not there, or submit the file believing the ministry's copy
    # does not carry the empty block. The warning's whole purpose, per the note below, is the
    # difference between "the feature is broken" and "we did not get to those questions" — and
    # as written it manufactured the first.
    #
    # `has_content` is a property of `CustomSectionItem`, which is already the type of everything
    # in `items`, so this is the renderer's answer rather than a copy of its rule. Do not
    # "simplify" it back into a hand-rolled test: a second copy of the rule is how the two came
    # apart in the first place.
    #
    # IT IS THE TIER-BLIND READING (`has_content` == `has_content_at(ALL_TIERS)`) BECAUSE THIS
    # FUNCTION HAS NO TEMPLATE TO ASK. `apply_report_settings` is what splices these sections in
    # and it can only do that once it has been handed the definition this load produces, so the
    # template is resolved above us and never reaches here. The one case the two still part on is
    # a section whose every question is above COMPACT_SUMMARY's cap: the renderer prints nothing
    # and this warning says nothing. That is the quiet direction of the two, and closing it means
    # threading `max_tier` down from `_report_inputs` — written up rather than done, because the
    # loader taking a rendering argument is a design change and not a fix.
    unanswered = [item for item in items if not item.has_content]
    if unanswered:
        # NAMED RATHER THAN SILENT, for the reason the AI annexure's warning gives: a block the
        # designer added themselves and then finds missing from a sixty-page document reads as a
        # bug in the app. Saying which one, and that it is empty rather than lost, is the difference
        # between "the feature is broken" and "we did not get to those questions".
        warnings.append(
            f"{len(unanswered)} of this workshop's own section(s) have no answers recorded and are "
            f"not in this file: "
            + ", ".join(sorted(item.title for item in unanswered)[:4])
            + ("…" if len(unanswered) > 4 else "")
        )
    return warnings


async def attach_report_questionnaires(data: WorkshopData, workshop_id: str) -> list[str]:
    """Load this workshop's own questionnaires onto ``data``. Returns the warnings to show.

    NO TOGGLE TO CONSULT, unlike :func:`attach_report_transcripts`, and that is the design decision
    rather than a missing parameter. A transcript is produced automatically by the media queue from a
    recording made for some other purpose, so an annexure of them is something that could happen TO a
    designer — hence ``includeTranscripts``. A questionnaire sitting has no such path: the designer
    built the form, attached it to THIS workshop from a dropdown, and typed the answers in.
    **Attaching it is the opt-in**, and ``PATCH {isActive: false}`` or detaching it is the way out.
    See ``report_questionnaires``' module docstring for the argument in full.

    THE QUERY IS PAID ONLY WHEN THE TEMPLATE DRAWS THE SECTION — the caller checks that, exactly as
    it already does for the map's district anchors, because five of six templates carrying a section
    is not the same as six.

    A questionnaire attached but never answered raises a warning rather than printing an empty
    heading: the designer chose that form for this workshop and would otherwise have to notice the
    shortfall themselves in a sixty-page document.
    """
    from app.services.questionnaire_forms import report_items

    try:
        items = await report_items(workshop_id)
    except Exception:
        # Blind, and for the reason ``_load_one_reference_model`` is blind: one unreadable annexure
        # must not lose a report that is the end of two weeks of fieldwork. The designer is told.
        logger.exception("Could not load questionnaires for workshop %s", workshop_id)
        return [
            "The questionnaire annexure could not be loaded and was left out of this report."
        ]
    if not items:
        return []
    attach_questionnaires(data, items)
    return questionnaire_warnings(items)


def resolve_template_id(requested: Any, settings: Mapping[str, Any] | None, record: Any) -> str:
    """Which template this report is built from: the request, then stage 20, then the header.

    STAGE 20'S OWN PICKER WAS READ BY NOTHING. "Report template" is required and BASIC, so the
    completeness gate demands it and the annexure counts it as satisfied — and both the preview
    and the generate route resolved the template as ``payload/query templateId or
    record.templateId``, skipping the answer entirely. A designer worked through 22 stages,
    reached stage 20, chose "Photo catalogue" because the form insisted, generated the report and
    got the DCH standard one. Nothing anywhere said the field was inert.

    Same precedence as every other stage-20 setting — see ``resolve_accent`` and
    ``wants_transcripts``: the request wins when it says anything, so a designer can produce one
    file from a different template without editing their saved answer; the saved answer applies
    otherwise; and where neither speaks the workshop header's own ``templateId`` stands, which is
    what keeps a record that never opened stage 20 printing exactly the report it always did.

    AN UNKNOWN ID IS IGNORED RATHER THAN OBEYED, for the reason ``fontPreset`` and ``themeAccent``
    are: a token from a newer client must not silently produce a different document, and
    ``get_template`` falls back to the DCH standard for anything it does not know — which would
    turn "this build has not heard of that template" into "your chosen template is the default"
    with no way to tell the two apart.
    """
    from app.schemas.design_workshops import REPORT_TEMPLATE_IDS

    for candidate in (requested, (settings or {}).get("templateId")):
        token = str(candidate).strip() if candidate not in (None, "") else ""
        if token in REPORT_TEMPLATE_IDS:
            return token
    return str(getattr(record, "templateId", "") or "")


def report_meta(record: Any, template_id: str,
                settings: Mapping[str, Any] | None = None) -> ReportMeta:
    """The cover page and the running furniture.

    ``settings`` is the stage-20 ``reportSettings`` entry. Every value it can carry is an OVERRIDE
    of something derived from the record, and each one falls back to exactly what this function
    returned before the overrides existed — so a workshop that never opened stage 20 gets the same
    cover it always got.

    These were stored and ignored for the same reason the section toggles were: the form wrote
    them, the database kept them, and no line of the pipeline ever read them. "Report title" was
    the worst of them. A designer whose workshop is recorded as "DPDW Barpali Jan-26" and who
    typed a proper title for the submitted document watched the report print the internal
    shorthand on its cover, every time, with no indication that the field they filled in was
    inert.
    """
    template = get_template(template_id)
    s = settings or {}

    def text(key: str) -> str:
        raw = s.get(key)
        return str(raw).strip() if raw not in (None, "") else ""

    subtitle_parts = [p for p in (record.craftName, record.clusterName, record.state) if p]
    derived_subtitle = (
        " — ".join([*subtitle_parts[:1], ", ".join(subtitle_parts[1:])])
        if len(subtitle_parts) > 1 else (subtitle_parts[0] if subtitle_parts else "")
    )

    # The page size is an enum on the wire and a free string in the JSON column, so an unknown
    # token falls back rather than raising: a report that prints on the wrong paper is a nuisance,
    # and a report that refuses to print because a phone sent "A4 " is a lost afternoon.
    page_size = template.page_size
    if text("pageSize"):
        try:
            page_size = PageSize(text("pageSize"))
        except ValueError:
            pass

    return ReportMeta(
        title=text("reportTitle") or record.title,
        subtitle=text("reportSubtitle") or derived_subtitle,
        author=record.designerName or "",
        organisation=text("organisationLine") or record.implementingAgency or template.organisation,
        template_id=template.id,
        template_name=template.name,
        workshop_id=record.workshopCode or record.id,
        generated_at=datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        page_size=page_size,
        header_text=text("headerText")
        or " — ".join(p for p in (record.craftName, record.clusterName) if p),
        footer_text=text("footerText") or f"{template.name} · {record.workshopCode or record.id}",
    )


def render_report(data: WorkshopData, template_id: str, resolver: Any, record: Any,
                  fmt: str, options: Any) -> tuple[bytes, list[str], int | None]:
    """Build and render, synchronously. The route calls this inside ``asyncio.to_thread``.

    Loading the media BYTES happens here too, and only for the images the document actually
    references — a template that excludes photographs must not pay to fetch forty of them.
    """
    settings = data.singleton("REPORT_GENERATION")

    # ``replace`` and NOT ``ReportMeta(**{**meta.__dict__, ...})``.
    #
    # ReportMeta is a frozen dataclass with ``slots=True``, and a slotted instance HAS NO
    # ``__dict__`` — the attribute lookup raises AttributeError. So each of these three lines was
    # an unconditional 500 the moment the request carried the field it guards, and the report page
    # sends all three as soon as stage 20 has a page size, a running header or a running footer
    # saved. The designers who hit it were the ones who had filled the settings in most carefully.
    #
    # It reached a person as a lie, which is the part worth remembering: ``isTransient`` in the web
    # client counts any 5xx as "probably the network", so the screen said the DOCX "cannot be
    # generated without a connection" while the server was up, answering, and failing on this line.
    # The designer was sent to look at their signal. See ``e2e/report-download.spec.ts``.
    meta = report_meta(record, template_id, settings)
    if options is not None and getattr(options, "pageSize", None):
        try:
            meta = replace(meta, page_size=PageSize(options.pageSize))
        except ValueError:
            pass
    if options is not None and getattr(options, "headerText", None):
        meta = replace(meta, header_text=options.headerText)
    if options is not None and getattr(options, "footerText", None):
        meta = replace(meta, footer_text=options.footerText)

    # THE COLOUR, RESOLVED THE SAME WAY THE PAPER IS: the request wins, then the saved stage-20
    # answer, and where neither spoke the template's own palette is left exactly as it was — which
    # is what keeps this feature from silently restyling every report a deployment has ever
    # generated. ``theme_from_accent`` derives the other seven colours and cannot raise, so a
    # malformed hex costs the designer their chosen colour and nothing else; the report is still
    # produced, in the indigo it has always been produced in.
    template = get_template(template_id)
    accent = resolve_accent(
        getattr(options, "themeAccent", None) if options is not None else None,
        settings,
    )
    theme = theme_from_accent(accent, base=template.theme) if accent else template.theme

    # THE TYPEFACE, resolved the same way a third time. It reaches the .docx and not the .pdf —
    # `report_docx` writes the family into `w:rFonts` and Word resolves it, while `report_pdf`
    # must embed a face that can actually draw Odia, Devanagari and the rupee sign and so chooses
    # by probing the filesystem. Substituting silently there would hand a designer two files that
    # look nothing alike, so the mismatch is reported as a warning further down instead.
    fonts = resolve_font(
        getattr(options, "fontPreset", None) if options is not None else None,
        settings,
    )
    if fonts:
        theme = replace(theme, heading_font=fonts[0], body_font=fonts[1])

    # THE SHAPE, resolved the same way again: which sections survive, whether photographs print
    # and how many to a row, whether headings are numbered. Same precedence, same "silent where
    # nobody asked" guarantee. ``includePhotographs`` is the one the request can also speak to,
    # because ReportGenerateIn has carried that flag since before stage 20 existed.
    template = apply_report_settings(
        template,
        settings,
        include_photographs=(getattr(options, "includePhotographs", None)
                             if options is not None else None),
        # THE ONE SECTION THIS FUNCTION ADDS RATHER THAN REMOVES, and the only one driven by the
        # request alone. It is not in any template and has no stage-20 answer behind it: an annexure
        # of machine-written text is a decision made per document, by the person who is about to
        # hand that document to somebody. See `SpecialSection.ANNEXURE_AI_LAYERS`.
        include_ai_layers=(getattr(options, "includeAiLayers", None)
                           if options is not None else None),
        # THE DESIGNER'S OWN SECTIONS, READ BACK OFF THE DATA THEY WERE LOADED ONTO rather than
        # loaded a second time. One definition reaches both the template and the renderer, so the
        # section this places after stage 13 is the same section the builder then draws — two loads
        # could straddle a definition edit and leave a heading with nothing under it.
        #
        # NO TOGGLE AND NO REQUEST FLAG. A workshop with no custom sections passes an empty tuple
        # and gets the identity return, so every existing report — and every one of the 38 pinned
        # `apply_report_settings` cases in the 485 KB Kotlin fixture — is untouched.
        custom_sections=custom_sections_of(data),
    )

    document, warnings = build_report(data, template_id, resolver.ref, meta=meta, theme=theme,
                                      template=template)

    # Only now, when the document is built, is it known which images it actually contains — a
    # template that excludes photographs must not pay to download forty of them.
    resolver.prefetch(document.images)
    # WHAT THE MEMORY BUDGET REFUSED, read defensively because `resolver` is typed `Any` here and
    # the report tests pass a stand-in with three methods and no attributes. `MediaIndex.prefetch`
    # fills this in; anything else answers the empty tuple and this costs nothing.
    over_budget = tuple(getattr(resolver, "oversize", ()) or ())

    if fmt == "PDF":
        blob, dropped = render_pdf(document, resolver.blob)
        page_count = _pdf_page_count(blob)
    else:
        blob, dropped = render_docx(document, resolver.blob)
        page_count = None

    warnings.extend(_dropped_warnings(dropped))
    # AFTER `_dropped_warnings`, deliberately, because it EXPLAINS PART OF THE COUNT THAT SENTENCE
    # JUST GAVE. A photograph the budget refused is left as `None` in the resolver, so the renderer
    # counts it among the ones it could not draw and says "N photograph(s) could not be included";
    # that is true but it reads as N failed downloads. This says which of them were a decision by
    # this server rather than a file it could not fetch, and it says so second so the two sentences
    # are read in that order.
    if over_budget:
        warnings.append(
            f"{len(over_budget)} of those photograph(s) were left out because embedding them would "
            f"have taken this server past the memory it has available for one report. The record "
            f"still holds every one of them; generating the report again when the server is less "
            f"busy, or with a template that prints fewer pictures, will include more."
        )
    if fmt == "PDF":
        warnings.extend(_font_warnings())
    if fonts and fmt == "PDF":
        # Said plainly, and only for the format it is true of. A designer who picks Garamond and
        # opens a PDF set in something else will otherwise conclude the setting is broken — which
        # is the same trap the seven stored-and-ignored settings were, arrived at from the other
        # direction. The .docx in the same download IS in their typeface.
        warnings.append(
            f"The PDF is set in the server's own typeface, not {fonts[1]}. A PDF must embed a "
            f"face that can draw Odia, Devanagari and the rupee sign, and that is chosen from "
            f"what is installed on the server. The Word document does use {fonts[1]}."
        )
    return blob, warnings, page_count


def _font_warnings() -> list[str]:
    """Tell the designer what this PDF will print as empty boxes, BEFORE they attach it.

    A codepoint the bound face has no glyph for is drawn as .notdef — a box — and nothing raises,
    nothing is logged per request and the file opens perfectly. The deployed image shipped with
    no fonts installed at all, so it fell through to ReportLab's vendored Vera and one workshop's
    167-page report carried 1,031 boxes: the craft's name in Odia on page 1, "unit realisation
    stands at □2,800 to □6,500" on page 14, and a ten-item quality checklist on page 134 in which
    every line began with a box instead of a tick. The .docx of the SAME workshop carried all 501
    rupee signs, 46 ticks, 14 crosses and 467 Odia codepoints correctly. Six warnings came back
    on that request and not one of them was about fonts.

    Said as a warning rather than a refusal, and for the same reason the map is: a designer with a
    submission deadline needs the document. But they must be told, because this is a defect they
    can act on — the .docx in the same download is correct, and it is the file to send.
    """
    from app.services.report_pdf import register_fonts

    fonts = register_fonts()
    said: list[str] = []
    if fonts.missing_glyphs:
        listed = ", ".join(f"{character} — {purpose}" for character, purpose in
                           fonts.missing_glyphs)
        said.append(
            f"This PDF prints empty boxes where these characters belong: {listed}. The server has "
            f"no font that can draw them. The Word document in the same download is correct."
        )
    if fonts.missing_scripts:
        # NAMED, because "no Indic font" was true of a server that drew Devanagari perfectly and
        # printed every Odia craft name as boxes — one Noto text face carries one script, and the
        # designer needs to know whether theirs is the one that is missing.
        scripts = ", ".join(s.value.title() for s in fonts.missing_scripts)
        said.append(
            f"This PDF prints empty boxes for text in these scripts: {scripts}. The server has no "
            f"font for them, so a craft name in the local language will not be legible. The Word "
            f"document in the same download is correct."
        )
    return said


def _dropped_warnings(dropped: list[str]) -> list[str]:
    """Say WHICH KIND of thing is missing from the file, because they are not the same problem.

    Every one of these used to come back as "N photograph(s) could not be included in the file",
    and a renderer drops three quite different things. A photograph is a fetch that failed and
    the designer can re-upload it. A locator MAP is missing geometry on the server, which no
    designer can do anything about and which they must not go looking for a photo to explain —
    that message sent people hunting for a missing image on workshops that have no photographs at
    all, while the actual hole was a numbered section printed empty. A CHART is a figure the
    rasteriser could not draw, which means the section's numbers are in the table and not in the
    picture beside it.

    The ids are the ones the two writers append: ``map:india``, ``chart:<kind>``, ``figure:N``
    (a chart placed by the PDF's figure path) and, for a photograph, the media id itself.
    """
    if not dropped:
        return []
    said: list[str] = []
    if any(item.startswith("map:") for item in dropped):
        said.append(
            "The locator map could not be drawn, so the section that places the workshop and its "
            "artisans is empty in this file. Nothing is wrong with the workshop's data — the "
            "boundary geometry is missing on the server."
        )
    figures = sum(1 for item in dropped if item.startswith(("chart:", "figure:")))
    if figures:
        said.append(
            f"{figures} figure(s) could not be drawn. Their numbers are still in the tables."
        )
    photographs = sum(
        1 for item in dropped if not item.startswith(("map:", "chart:", "figure:"))
    )
    if photographs:
        said.append(f"{photographs} photograph(s) could not be included in the file.")
    return said


def _pdf_page_count(blob: bytes) -> int | None:
    """Count pages without a PDF library, by counting page objects in the trailer.

    ReportLab already knows the number, but it is not exposed on the returned bytes; parsing it
    back out is cheaper than threading the count through two renderers whose only difference
    would then be this one return value.
    """
    count = blob.count(b"/Type /Page") + blob.count(b"/Type/Page")
    pages_obj = blob.count(b"/Type /Pages") + blob.count(b"/Type/Pages")
    total = count - pages_obj
    return total if total > 0 else None


async def attach_district_anchors(data: WorkshopData) -> int:
    """Give the report builder a position for every district this repository can place.

    THE MAP'S SECOND DEAD HALF. ``WorkshopData.references`` was declared and read and never
    constructed, which cost the map its artisan pins; this is the same shape of gap one level
    down. ``place_atlas`` is a hand-checked table of a few dozen craft towns, so anywhere it does
    not name — which is most of India — every artisan folded onto the state capital and the figure
    asserted that a Bargarh cluster came from Bhubaneswar. It rendered cleanly, which is exactly
    why it survived.

    ``geography.DistrictAnchors`` already solves this for ``/map``: it seeds from the atlas and
    then learns each district's position from every pinned ``Location`` A RECORD STILL POINTS AT,
    and ``address.DISTRICTS_BY_STATE`` names all 795 districts so any of them can be matched. All
    that was missing was handing the result to the report.

    That qualification is load-bearing rather than pedantic, and it is the read below's second
    predicate: the table also holds the rows every earlier save of the same record left behind
    (``records.attach_location`` inserts, never updates), and letting those vote means a corrected
    pin keeps pulling its district toward the position it was corrected away from — in a figure
    printed into a document a ministry officer reads. See ``geography.REFERENCED_BY_A_RECORD``.

    Flattened to a plain dict here rather than passed as the object, because ``report_builder`` is
    ALSO the on-device builder: it may not query and may not import something that can. Returns
    the number of districts placed, which the caller can log.

    CAPPED, THE WAY ``/map`` IS. There is no index that can serve this predicate — the schema
    refuses one on ``district`` on purpose (``prisma/schema.prisma``) — so the read is a scan whose
    cost is the size of the archive rather than the size of the workshop. ``/map`` answers a
    20,000-pin repository with a coarser map; before this cap the report path answered it by
    materialising 20,000 Prisma models on the single-worker box, which ``api/routes/export.py``
    describes as one unbounded ``find_many`` away from an OOM. Degrading is the right failure: an
    anchor is a district's AVERAGE position, so the districts that lose rows off the end of the cap
    keep the atlas seed or a slightly coarser learned point, and none of them loses its pin.

    The caller is expected to skip this load entirely when the template draws no map — four of the
    six do not. See ``_report_inputs``.
    """
    from app.services.geography import (
        MAX_ANCHOR_ROWS,
        REFERENCED_BY_A_RECORD,
        DistrictAnchors,
        district_key,
    )

    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
    rows = await db.location.find_many(
        # REFERENCED ROWS ONLY, the same predicate `/map` learns from — see
        # `geography.REFERENCED_BY_A_RECORD`. `attach_location` inserts a fresh row on every save,
        # update included, and nothing deletes the old one; without this the abandoned rows keep
        # voting, so a district anchor printed in a .docx sits between where the researcher put the
        # pin and where they had first put it by mistake. The two readers must ask the identical
        # question or one district lands in two places in two products of the same data.
        where={
            "AND": [
                {"subjectLatitude": {"not": None}, "district": {"not": None}},
                REFERENCED_BY_A_RECORD,
            ]
        },
        # A STABLE ORDER, because the read is capped: without one, two reports generated a minute
        # apart could learn from two different slices and place the same district differently.
        order={"id": "asc"},
        take=MAX_ANCHOR_ROWS,
    )
    anchors.learn(rows or [])

    points: dict[str, tuple[float, float]] = {}
    for state, districts in DISTRICTS_BY_STATE.items():
        for district in districts:
            point = anchors.anchor(state, district)
            if point:
                points[district_key(state, district)] = point
    data.district_points = points
    return len(points)
