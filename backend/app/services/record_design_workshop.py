"""Filing a repository record under a Design & Prototype Workshop, and the one gate that guards it.

===================================================================================================
WHAT THIS MODULE IS FOR
===================================================================================================

The owner's instruction of 2026-08-28: *"Ensure that all of the following record types can be linked
to a Design and Prototype Workshop: Artisans, Products, Process, Tools, Questionnaires,
Miscellaneous Media, Consolidated Questionnaires."*

Six of those are repository record types with a new nullable ``designWorkshopId`` column (see
``20260828090000_record_design_workshop_link``); the seventh, the designer-authored
``Questionnaire``, already had one, and the consolidated questionnaire has no store of its own and
is scoped through the interviews it reads.

Six record types times two verbs is twelve write paths that all have to answer the same question
before they write that column, and this module is the one place that answers it.

===================================================================================================
WHY A GATE AT ALL — THE COLUMN IS NOT A NOTE, IT IS A CLAIM ABOUT SOMEBODY ELSE'S WORKSHOP
===================================================================================================

``workshopId`` beside it is gated by ``workshop_access.enforce_workshop_submission``, which reads
``WorkshopAssignment``. This column is a different scope with different machinery — a design
workshop is reached through ``load_workshop_or_404``: its creator, an admin, or the holder of a
``DesignWorkshopViewer`` row — and NOTHING would have checked it if this module did not exist.

Left ungated, a client could post ``{"designWorkshopId": "<somebody else's cuid>"}`` on an ordinary
artisan create and file their record into a stranger's workshop. That is not a data-leak in the
reading direction (the artisan row is already readable by every signed-in account — see
``records.viewable_where``), and it is worse than one in the writing direction: the record would
appear inside another designer's scoped lists, be counted by their totals, and be offered to their
report as material they never gathered.

===================================================================================================
IT CALLS ``load_workshop_or_404``. IT DOES NOT REIMPLEMENT THE RULE, AND THAT IS THE POINT
===================================================================================================

``questionnaire_forms._require_attachable_workshop`` already had to be written once, and its
docstring is the reason this module has no predicate of its own. Three routes there asked only
whether a workshop ROW EXISTED — ``require_record`` is a bare ``find_unique``: no creator test, no
``DesignWorkshopViewer`` test, no admin test, not even a ``deletedAt`` filter — so one ordinary call
attached a questionnaire to a stranger's workshop, and a stranger's sittings printed as an annexure
of another designer's report in a .docx submitted to a ministry. That function ends with an
instruction addressed to whoever came next:

    "If a fourth attachment route is ever added, it calls this; do not reach for ``require_record``
     again because it is one line shorter."

Twelve more write paths arrived on 2026-08-28. They call the same helper that function calls.

``for_edit=True``, matching that precedent and ``save_stage_data``. Filing a record under a workshop
puts it inside that workshop's scoped lists and totals, which is a change to somebody else's record,
so it is exactly as hard as editing one of its stages: creator, admin, or grant-holder.

Two behaviours come with the helper and both are wanted:

* **404, never 403**, for a workshop the caller may not see — "a 403 would confirm that the id exists
  to precisely the caller being turned away."
* **409 for a soft-deleted workshop**, with "Restore it before editing", rather than a 404. Every
  read in the design-workshop family filters ``deletedAt: null``, so filing a record under a deleted
  workshop would file it under something none of its own surfaces can show; the admin who can still
  see the trash restores it first, and the sentence tells them so.

The helper brings a third since 2026-10-09: **403 for whoever holds the workshop's inspection or one
of its two director posts**, who read it and do not write it. So filing a record INTO such a workshop
— on a create or on a move — is refused to them by the same line, with nothing added here.

===================================================================================================
A RECORD FILED UNDER A WORKSHOP IS ITS CONTENT, AND ITS HOLDERS WRITE NONE OF IT (2026-10-09)
===================================================================================================

The owner's ruling: **the workshop's inspector and its Assistant and Regional Directors do not write
its content, and the records filed under it are its content** — its scoped lists, its totals, the
material its report is offered. So :func:`assert_may_write_a_record_filed_under` is asked by EVERY
write to a record that already exists, about the workshop the stored row names: any PATCH, whatever
it carries — an unfile and a move out are only two of its shapes — the DELETE, an interview merge
with either side filed there, a tool's artisan links, and every edit of a questionnaire form attached
to one. The PATCH asks it through :func:`assert_payload_workshop`, whose ``filed_under`` is required
so no record form can leave it out.

Without it, a holder who may edit somebody else's record (the PROFESSOR floor, which every
administrator tier clears) could change through the record forms exactly what the oversight
screen's own unlink and the stage saves refuse them. It asks
``design_workshop_posts.refuse_a_holders_write`` and nothing else — no rights over the workshop — so
a designer taking their own record back from a workshop they were removed from is exactly as free as
before, and a role that can hold no post pays no query.

===================================================================================================
WHAT THIS DOES NOT DO
===================================================================================================

* **It does not enforce a scope on reads.** Filing a record under a workshop does not narrow who may
  read that record — ``records.viewable_where`` returns ``{}`` and that is unchanged. The column is a
  filing label, not an access rule, and treating it as one would be the "two access systems on one
  field" the schema comment refuses.
* **It does not touch ``workshopId``.** A record may carry both, either or neither. The two answer
  different questions and neither implies the other.
* **It does not decide the DEFAULT.** ``GET /design-workshops/default-for-me`` does that, and it is a
  suggestion the client prefills, never a value this module supplies.
"""

from __future__ import annotations

from typing import Any

from app.services import design_workshop_posts

#: The column name, spelled once. Every schema, route and test that names it reads this.
DESIGN_WORKSHOP_KEY = "designWorkshopId"


async def assert_may_file_under(workshop_id: str | None, user: Any) -> None:
    """Refuse unless ``user`` may file a record under ``workshop_id``.

    ``None`` and the empty string are both "no workshop" and are allowed without a query — that is
    the ordinary case for every record recorded outside a design workshop, and it must not cost a
    round trip. The empty string is caught HERE rather than left to the loader for the reason
    ``QuestionnaireUpload`` records against itself: ``""`` is falsy, so a guard written as
    ``if payload.designWorkshopId:`` skips the check, and the value then travels on to a write that
    fails somewhere far less legible.

    THE IMPORT IS DEFERRED, and that is not laziness. ``app.services.design_workshops`` imports
    ``app.services.records``, and this module is imported BY the record routes; keeping the import
    inside the call means the record modules never sit in that chain at import time. The same
    treatment, for the same reason, that ``design_workshops`` itself gives
    ``records.owned_or_granted_where``.
    """
    if not workshop_id:
        return

    from app.services.design_workshops import load_workshop_or_404

    await load_workshop_or_404(workshop_id, user, for_edit=True)


async def assert_may_write_a_record_filed_under(workshop_id: str | None, user: Any) -> None:
    """Refuse (403, naming the post) when ``user`` inspects or supervises the workshop a record is
    FILED UNDER. Everybody else may, and is asked nothing about the workshop.

    Asked by every write to a record that already exists — see the module header for the list —
    with the workshop its STORED row names. ``None`` and ``""`` are a record filed under no workshop,
    and nothing is asked. A role that can hold no post is answered by the shared gate from memory,
    so a designer saving their own record pays no query.
    """
    if not workshop_id:
        return
    await design_workshop_posts.refuse_a_holders_write(workshop_id, user)


async def assert_payload_workshop(
    data: dict[str, Any], user: Any, *, filed_under: str | None
) -> None:
    """The record forms' workshop gate, in two halves: the workshop the stored row is filed under,
    asked on EVERY call (:func:`assert_may_write_a_record_filed_under`), and the workshop the payload
    files it under, asked when the payload names one (:func:`assert_may_file_under`).

    ``filed_under`` IS THE WORKSHOP THE STORED ROW NAMES, and it is required so no write path can
    leave it out by accident: an UPDATE passes its row's ``designWorkshopId``, a CREATE passes
    ``None`` because a row that does not exist yet is filed nowhere. Its half is asked whatever the
    PATCH carries, because the holder it refuses may not change ANY of the record (2026-10-09), and
    first, so a holder moving the record is told about their post rather than about the destination.

    THE DESTINATION IS KEYED ON PRESENCE. ``exclude_unset=True`` means the key is present only when
    the caller actually sent it, so an UPDATE that does not mention the workshop is not re-validated
    — which matters because a record filed under a workshop a designer has since been removed from
    must still be editable. Only a caller who SENDS the link has to hold it, and re-sending the id
    the row already holds is asked about exactly as it always was.

    An explicit ``None`` unfiles the record and is allowed to everybody but the workshop's inspector
    and its two directors: taking your own record out of a workshop needs no permission on that
    workshop, and refusing it would strand a record filed by mistake under a workshop the filer was
    later removed from.
    """
    await assert_may_write_a_record_filed_under(filed_under, user)
    if DESIGN_WORKSHOP_KEY not in data:
        return
    await assert_may_file_under(data.get(DESIGN_WORKSHOP_KEY), user)
