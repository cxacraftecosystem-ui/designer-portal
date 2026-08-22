"""Field-level provenance for design-workshop stage entries: who last set THIS field.

=================================================================================================
WHAT THIS MODULE IS FOR, AND WHAT WAS ALREADY THERE BEFORE IT
=================================================================================================

The requirement, in the product owner's words, was: records are shared between designers; there
is a single canonical copy rather than a duplicate per designer; only the changed fields are
stored individually for the designers who make the edits; provenance stays with the ORIGINAL
author unless a field is edited, in which case that field's provenance moves to the editor; and
admins and master admins see all of it.

THREE OF THOSE FIVE CLAUSES WERE ALREADY TRUE OF THE RECORD TABLES, and this module deliberately
does not reimplement them. It is worth saying exactly where they live, because the expensive
mistake available here was building a second, parallel sharing-and-provenance system beside a
working one:

  * SHARED, SINGLE CANONICAL COPY. ``records.viewable_where`` returns ``{}`` — every signed-in
    account may read every ``Artisan``, ``ProductDocumentation``, ``ToolDocumentation``,
    ``Process``, ``Craft`` and ``Workshop`` row in the repository. There is one row per record and
    there has never been a per-designer duplicate of one. Read the banner above that function: the
    repository's rule is "everybody may LOOK at every record while taking data out stays earned".
  * ONLY THE CHANGED FIELDS, PER EDITOR. ``access.record_revision`` writes one ``RecordRevision``
    per edit holding exactly ``{field: {old, new}}`` for the fields that actually changed, stamped
    with ``editedById``. Nothing else is duplicated.
  * PER-FIELD PROVENANCE THAT MOVES ON EDIT. ``records.merge_field_provenance`` stores
    ``extraMetadata.fieldProvenance = {field: {by, byName, at}}`` on every record type, and its
    rule is already the requirement's rule verbatim: on create every non-empty field is attributed
    to the author; on update ONLY the fields whose value actually changes are re-attributed, and
    unchanged fields keep the original contributor. ``frontend/components/FieldProvenance.tsx``
    renders it.

WHAT WAS MISSING IS THE SURFACE THIS REPOSITORY EXISTS FOR: the 22-stage design workshop. A
``DwStageEntry`` carries a ``createdById`` — who created the ROW — and nothing else. A workshop is
run by two designers sharing one set of rows through ``DesignWorkshopViewer``, and 107 field-pairs
across 8 mappings are COPIED onto those rows from the shared records by ``hydrate_entries``. So on
the one surface where a duplicate per designer genuinely exists, there was no answer at all to
"who set this field" — not for the co-designer who typed over a hydrated value, and not for the
researcher whose record supplied it in the first place. That is the gap this module closes.

=================================================================================================
THE BOUNDARY: REFERENCE HYDRATION COPIES, THIS MODULE DOES NOT UN-COPY IT
=================================================================================================

``stage_schema.REFERENCE_HYDRATION`` and ``design_workshops.REFERENCE_MODELS`` exist to COPY a
record's fields onto a stage entry at save time. The requirement above says "do not duplicate".
Those are opposite policies over the same data and BOTH ARE RIGHT, because they govern two
different kinds of object. Writing that down is the point of this section; a later change that
quietly makes one of them wrong is the failure this paragraph exists to prevent.

  A SHARED RECORD IS A LIVING ROW.  ``Artisan`` is the current best knowledge of a person. It is
  corrected, merged into a duplicate discovered later, deleted when a researcher cleans up a
  double entry. Everybody reads the same row, and when it changes, everybody's reading changes.
  There is exactly one copy and there must never be a second, because a second copy is a second
  answer to "what is this artisan's phone number" with nothing to say which is current. This is
  the half the requirement is about, and it is already how the record tables work.

  A STAGE ENTRY IS A DATED OBSERVATION.  A workshop report is a historical document, generated
  months or years after the workshop and submitted to an office that keeps it. Its participant
  table is the proof of who attended, on the day. If it resolved the artisan's name through the id
  at render time, then every ordinary correction to the live record would silently rewrite a
  submitted document, and a deletion would render as a blank cell in the table that is the proof.
  So the name is copied at save time and the report prints the copy; the id stays beside it for
  ever as the join key. The long note above ``REFERENCE_HYDRATION`` argues this in full.

THE BOUNDARY, THEN, IS THE VALUE/AUTHORSHIP LINE, and it runs like this:

  * THE VALUE IS COPIED AND STAYS COPIED. This module does not make a stage entry sparse, does not
    resolve a hydrated field through its ``refId`` at read time, and does not remove a single one
    of the 107 field-pairs. Doing any of those would be the exact defect ``REFERENCE_HYDRATION``
    was written to prevent, and it would arrive on the surface where it costs the most: a .docx
    already in a ministry's files.
  * AUTHORSHIP IS NOT COPIED — IT IS ATTRIBUTED. A hydrated field's value belongs to the workshop
    for ever, but its AUTHOR is the person who recorded the canonical record, not the designer who
    happened to pick it from a dropdown. That is the requirement's "provenance stays with the
    original author". The moment a designer edits that field, the value diverges from the record
    and the authorship moves to the designer — the requirement's "unless a field is edited".

So the copy-on-write model this repository can actually hold is: ONE canonical record row (already
true), a per-workshop stage entry whose values may diverge from it (already true, and load-bearing
for the report), and a SPARSE per-field provenance map that says, for each field, whether the value
is still the record's and whose it is (this module). "Only the changed fields stored individually
for the designer who made the edit" is expressed as: only the diverged fields carry a designer's
stamp; everything else points back at the record and its author.

WHAT WAS DELIBERATELY NOT BUILT, and why, is recorded at the bottom of this docstring under
"THE PRIVATE OVERLAY".

=================================================================================================
THE STAMP
=================================================================================================

``DwStageEntry.fieldProvenance`` is ``{fieldKey: stamp}`` and is SPARSE — a field with no
answerable author carries no key at all. Two sources:

  ``source: "reference"``  the value was written by ``hydrate_entries`` from a shared record.
                           ``by`` is that RECORD's ``createdById``; ``refModel``/``refId``/
                           ``refKey`` name the row and column it came from, so an admin can ask
                           what the canonical value is TODAY and see whether the workshop has
                           diverged from it.
  ``source: "designer"``   a person working on this workshop typed or changed the value. ``by`` is
                           that person.

AN ABSENT KEY IS AN HONEST ANSWER AND NOT A BUG. Every stage entry saved before this column
existed has values whose author nobody recorded. The tempting move — attributing them to whoever
saves the row next — would be a fabricated audit trail on a document submitted to a ministry, and
it would be indistinguishable from a real one. So an unchanged field with no carried stamp stays
unstamped, for ever, until somebody actually edits it. See :func:`merge_entry_provenance`.

=================================================================================================
THE PRIVATE OVERLAY — NOT BUILT, AND THIS IS WHY
=================================================================================================

One reading of "only the changed fields would be stored individually for the particular designers
who make the edits" is a PRIVATE overlay: designer B edits a shared artisan's phone number, B sees
their value, A keeps seeing the canonical one. That is not built here, and it is not a shortcut —
it is a policy question that contradicts two rules this repository has already settled in writing,
and only the product owner can overturn them:

  1. ON THE RECORD TABLES it would undo the pooling. ``viewable_where``'s banner says a repository
     that hides itself from the people filling it is "precisely backwards — the whole point of
     pooling the fieldwork is that everyone can see the pool". A private per-designer value means
     two designers looking at one artisan see two different phone numbers with nothing on either
     screen to say so, and the record's ``status``/review queue (which approves ONE value) has no
     way to say which one it approved.
  2. ON THE STAGE ENTRIES it would break co-authorship. ``DesignWorkshopViewer`` exists precisely
     so "two designers run one workshop" — the grant's own comment says a designer leaving
     mid-season used to take the record with them. Hiding one co-designer's edits from the other
     would give one workshop two divergent reports and no rule for which one is submitted.

The requirement's own words are satisfiable without it, and are satisfied here: a per-WORKSHOP
stage entry already diverges from the canonical record without touching it, so one designer's
correction is invisible in another designer's workshop while both remain visible to the workshop
they were made in. That is the isolation the requirement asks for, at the boundary this data model
actually has. If the product owner does want per-DESIGNER privacy inside one shared workshop, that
is a different feature with a different name, and it needs an answer to "which value does the
submitted report print" before a line of it is written.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

#: The value came from a shared canonical record via ``hydrate_entries``. ``by`` is that record's
#: author, NOT the designer who picked it.
SOURCE_REFERENCE = "reference"

#: A person working on this workshop typed or changed the value.
SOURCE_DESIGNER = "designer"


@dataclass(frozen=True, slots=True)
class HydrationSource:
    """Where one hydrated field's value came from, recorded as ``hydrate_entries`` writes it.

    Carried on :class:`design_workshops.PendingEntry` rather than returned, because hydration is a
    whole-payload pass over thirty participants at once and the caller needs the answer per row.

    ``author_id`` is the canonical record's ``createdById``. It is nullable for the same reason the
    column is: ``createdBy`` is ``SetNull`` on several of these models, so a record whose recorder's
    account was deleted still has to be attributable to "the record", just not to a person.
    """

    model: str
    record_id: str
    source_key: str
    author_id: str | None


def merge_entry_provenance(
    *,
    previous: Mapping[str, Any] | None,
    previous_data: Mapping[str, Any] | None,
    new_data: Mapping[str, Any],
    hydrated: Mapping[str, HydrationSource] | None,
    user: Any,
    now: datetime | None = None,
) -> dict[str, Any]:
    """The stage entry's new ``fieldProvenance``, given what it held and what is being written.

    This is the requirement's sentence expressed as code, and the three branches are in priority
    order because they answer three different questions about the same key:

    1. **Hydration wrote it on this save** -> the canonical record's author owns it. Hydration only
       ever fills a BLANK (or clears and refills a row whose reference was re-pointed), so this
       branch firing means the designer had not answered the field and the record did.
    2. **The value is unchanged from what the row already held** -> the stamp is carried forward
       untouched. This is "provenance stays with the original author": a designer who opens a
       stage, corrects one box and saves does not become the author of the other twenty.
    3. **Anything else** — a new key, or a value that differs from what was stored -> the person
       making this save owns it. This is "that field's provenance moves to the editor", and it is
       what makes a hydrated field's authorship move from the record's recorder to the designer the
       moment the designer types over it.

    A KEY THAT IS UNCHANGED AND CARRIES NO STAMP GETS NONE. Every row written before this column
    existed is in that state, and attributing those values to whoever saves the row next would
    manufacture an audit trail for a document that goes to a ministry — indistinguishable, on
    screen, from one that was actually recorded. Absent is the honest third answer and both clients
    render it as "not recorded" rather than as a name.

    Empty values and the sync protocol's own ``_``-prefixed keys (``_entryId``, ``_ordinal``,
    ``_clientKey``) are never stamped: the first is the absence of an answer, and the second is not
    workshop data at all — stamping it would put three phantom rows in every provenance panel.

    Pure, and takes ``now`` for the same reason ``derive_age`` does: a test must be able to state
    the instant rather than race the clock.
    """
    from app.core.deps import get_value, is_empty_value, values_match

    carried_in: Mapping[str, Any] = previous or {}
    before: Mapping[str, Any] = previous_data or {}
    written: Mapping[str, HydrationSource] = hydrated or {}
    at = (now or datetime.now(UTC)).isoformat()

    designer_stamp = {
        "by": get_value(user, "id"),
        "byName": get_value(user, "name"),
        "at": at,
        "source": SOURCE_DESIGNER,
    }

    out: dict[str, Any] = {}
    for key, value in new_data.items():
        if key.startswith("_") or is_empty_value(value):
            continue

        source = written.get(key)
        if source is not None:
            out[key] = {
                "by": source.author_id,
                # Left unresolved on the write path ON PURPOSE. Resolving it would cost a User
                # query inside a save a designer is waiting on with one bar of signal, and it would
                # freeze a display name that ``resolve_display_names`` can read fresh on every read
                # for nothing. The designer branch below keeps its name only because the save
                # already holds the whole user object.
                "byName": None,
                "at": at,
                "source": SOURCE_REFERENCE,
                "refModel": source.model,
                "refId": source.record_id,
                "refKey": source.source_key,
            }
            continue

        had = before.get(key)
        if not is_empty_value(had) and values_match(had, value):
            existing = carried_in.get(key)
            if isinstance(existing, dict):
                out[key] = dict(existing)
            # else: unchanged, and nobody ever recorded who set it. Deliberately unstamped.
            continue

        out[key] = dict(designer_stamp)

    return out


def entry_provenance(row: Any) -> dict[str, Any]:
    """One stage entry row's provenance map, tolerating every shape the column has held.

    ``None`` for a row written before the column existed, and ``{}`` for a row whose every field is
    unattributable, are the same answer to a reader and are both returned as ``{}``. A non-dict —
    which nothing writes, but a hand-edited row or a restored dump could hold — is discarded rather
    than propagated, because a reader that receives a list where it expects a map raises inside a
    report render rather than at the boundary.
    """
    stored = getattr(row, "fieldProvenance", None)
    return dict(stored) if isinstance(stored, dict) else {}


def resolve_entry_provenance(rows: Iterable[Any]) -> dict[str, dict[str, Any]]:
    """``{entryId: {fieldKey: stamp}}`` for a set of stage entry rows.

    THE ONE FUNCTION EVERY READER CALLS. It is keyed by entry id rather than by position because
    the collection readers sort their rows differently — ``_stages_payload`` sorts by ``_ordinal``
    after grouping, ``assemble_workshop_data`` sorts before it, and the phone sorts on its own
    draft — so a positional map would be silently misaligned on one of the three, attributing one
    participant's edits to another. An id-keyed map cannot be misaligned by a re-sort.
    """
    return {row.id: entry_provenance(row) for row in rows}


def provenance_user_ids(maps: Iterable[Mapping[str, Any]]) -> set[str]:
    """Every user id named by a set of provenance maps, for a single name lookup."""
    ids: set[str] = set()
    for stamps in maps:
        for stamp in stamps.values():
            if isinstance(stamp, dict) and stamp.get("by"):
                ids.add(str(stamp["by"]))
    return ids


async def resolve_display_names(maps: Iterable[dict[str, Any]]) -> None:
    """Fill in ``byName`` on every stamp that has an id but no name, in ONE query. Mutates.

    WHY THE NAME IS RESOLVED ON READ AND NOT STORED ON WRITE. A ``reference`` stamp names the
    canonical record's author, and the save path holds only that record's ``createdById`` — turning
    it into a name there would be an extra query on a write a designer is waiting on, paid on every
    save of every stage. Reading it here costs one query for the whole workshop, and it has the
    better property besides: a researcher who changes their display name is shown under their
    current name everywhere, instead of under whatever it was on the day each field was hydrated.

    A stamp whose ``by`` names a deleted account keeps its id and gets no name. That is deliberate
    and both clients render it as "an account no longer on record" — the alternative, dropping the
    stamp, would erase the fact that the field WAS attributed, which is the more useful half.
    """
    from app.core.db import db

    materialised = list(maps)
    wanted = {
        str(stamp["by"])
        for stamps in materialised
        for stamp in stamps.values()
        if isinstance(stamp, dict) and stamp.get("by") and not stamp.get("byName")
    }
    if not wanted:
        return
    users = await db.user.find_many(where={"id": {"in": sorted(wanted)}})
    names = {u.id: u.name for u in users}
    for stamps in materialised:
        for stamp in stamps.values():
            if not isinstance(stamp, dict) or stamp.get("byName"):
                continue
            name = names.get(str(stamp.get("by")))
            if name:
                stamp["byName"] = name


# -------------------------------------------------------------------------------------------
# The admin picture: the workshop's value beside the canonical record's CURRENT value
# -------------------------------------------------------------------------------------------


async def canonical_divergence(
    rows: Iterable[Any],
) -> dict[str, dict[str, dict[str, Any]]]:
    """``{entryId: {fieldKey: {stored, canonical, diverged}}}`` for every ``reference`` stamp.

    THIS IS THE HALF OF "ADMINS SEE ALL OF IT" THAT NOTHING ELSE CAN ANSWER. Every other reader is
    shown the workshop's own value, which is correct — the report is a dated observation and must
    print what was captured on the day. But an admin auditing the archive needs the other column
    too: this workshop says the artisan's village is Barpali, the canonical record says Bargarh,
    and the reference stamp is what makes the comparison possible at all. Without it a divergence
    is invisible, because a hydrated value and a typed value look identical once stored.

    ``canonical`` is ``None`` when the record has been deleted — which is not an error and is
    precisely the case ``REFERENCE_HYDRATION`` exists for: the workshop still holds what the
    designer saw, and the honest rendering is "the record this came from no longer exists".

    One query per referenced MODEL, not per field or per row: a thirty-participant stage names one
    model and thirty ids.
    """
    from app.core.db import db
    from app.services.design_workshops import REFERENCE_MODELS, _reference_photos

    materialised = list(rows)

    wanted: dict[str, set[str]] = {}
    for row in materialised:
        for stamp in entry_provenance(row).values():
            if not isinstance(stamp, dict) or stamp.get("source") != SOURCE_REFERENCE:
                continue
            model, record_id = stamp.get("refModel"), stamp.get("refId")
            if model in REFERENCE_MODELS and record_id:
                wanted.setdefault(str(model), set()).add(str(record_id))

    resolved: dict[str, dict[str, dict[str, Any]]] = {}
    for model, ids in wanted.items():
        spec = REFERENCE_MODELS[model]
        records = await getattr(db, spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=spec.include or None
        )
        # THE PHOTOGRAPH IS LOADED TOO, and skipping it was a false positive rather than a saving.
        # ``photo`` and ``photoCaption`` are in the hydration mapping and come from this lookup, not
        # from a column — so calling ``spec.data(rec, None)`` would compute a canonical value of
        # ``None`` for them on every record that has a picture, and EVERY portrait in the archive
        # would be reported to an admin as having diverged from its record. It is one query per
        # model, the same one ``hydrate_entries`` makes, and it is the difference between a
        # divergence report an admin can act on and one they learn to ignore.
        photos = await _reference_photos(spec, [rec.id for rec in records])
        # Through the SAME ``data`` lambda hydration used, so "the canonical value" is spelled the
        # way it would be spelled if the field were hydrated again today. Reading the Prisma column
        # directly would compare a masked Pehchan number against an unmasked one, and an
        # inches-measured tool against its centimetre conversion, and report both as divergence.
        resolved[model] = {rec.id: spec.data(rec, photos.get(rec.id)) for rec in records}

    out: dict[str, dict[str, dict[str, Any]]] = {}
    for row in materialised:
        stored_data = dict(getattr(row, "data", None) or {})
        per_field: dict[str, dict[str, Any]] = {}
        for key, stamp in entry_provenance(row).items():
            if not isinstance(stamp, dict) or stamp.get("source") != SOURCE_REFERENCE:
                continue
            model, record_id = stamp.get("refModel"), stamp.get("refId")
            # THE SAME GUARD THE GATHERING LOOP ABOVE APPLIES, AND ITS ABSENCE HERE WAS A LIE WAITING
            # FOR ITS DAY. That loop only asks the database about a stamp whose model is in
            # ``REFERENCE_MODELS`` and which names a record; this one did not, so a stamp naming a
            # model this build no longer knows found nothing in ``resolved`` and was reported as
            # ``recordDeleted: True`` — "the record this came from no longer exists" — for a record
            # nobody ever looked for. Nothing writes such a stamp today, because ``hydrate_entries``
            # only stamps when ``spec.ref_model in REFERENCE_MODELS``. It goes live the day a model
            # is renamed or dropped from that table while old stamps remain, which is precisely the
            # archive this endpoint is read against.
            #
            # Reported as NEITHER diverged NOR deleted: an unresolvable reference is a fact about
            # this build's registry, not about the record, and inventing either answer would send an
            # admin looking for a deletion that did not happen.
            looked_up = model in REFERENCE_MODELS and bool(record_id)
            source = resolved.get(str(model), {}).get(str(record_id)) if looked_up else None
            canonical = None if source is None else source.get(str(stamp.get("refKey")))
            stored = stored_data.get(key)
            per_field[key] = {
                "stored": stored,
                "canonical": canonical,
                # ``str`` on both sides: a Decimal column and the coerced string the entry holds are
                # the same answer, and reporting them as divergence would fire on every money field
                # in the archive.
                "diverged": source is not None and str(stored) != str(canonical),
                "recordDeleted": looked_up and source is None,
            }
        if per_field:
            out[row.id] = per_field
    return out
