"""Cross-researcher tiered data access + edit auditing.

A DataAccessGrant lets one researcher (the GRANTEE) act on another researcher's (the OWNER's) uploaded
records at a tier: DOWNLOAD (download/export) < COMMENT (+ comment) < EDIT (+ change fields). Admins and
a record's own creator always have full (EDIT) access. Every field-changing edit is captured in a
RecordRevision so an admin can reconstruct the original values and every subsequent edit, with author
-- EXCEPT for the identity and contact columns named in REVISION_REDACTED_FIELDS, where the ledger
records THAT the field changed, who changed it and when, but not the value. The argument for that one
exception is written out above the set; it is the difference between auditing a retraction and
undoing it.
"""
from typing import Any

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from prisma import Json

# Strictly increasing privilege. A tier includes every action of the tiers below it.
TIER_ORDER = {"DOWNLOAD": 1, "COMMENT": 2, "EDIT": 3}

# Human-readable, shown in UIs so a user knows exactly what each tier confers when requesting/granting.
TIER_DESCRIPTIONS = {
    "DOWNLOAD": "Minimum — download this researcher's data (the whole set, or the shared subset).",
    "COMMENT": "Medium — everything in Download, plus leave comments on their entries.",
    # This string is served over the API by ``routes/data_access.py`` and is the text an owner reads
    # while deciding whether to grant EDIT, so it has to describe the ledger they will actually get.
    # It named the REVISION_REDACTED_FIELDS exception the moment that exception existed: promising an
    # auditability the portal no longer provides, on the screen where consent is given, is the one
    # place a stale comment becomes a lie told to a member of the public.
    "EDIT": "Maximum — everything in Comment, plus edit fields on their entries (every change is "
    "tracked with its author; admins see the original for all but identity and contact fields, "
    "where only the fact and direction of the change is kept).",
}


def _enum_str(value: Any) -> str:
    return str(getattr(value, "value", value))


def tier_at_least(tier: str | None, minimum: str) -> bool:
    if not tier:
        return False
    return TIER_ORDER.get(tier, 0) >= TIER_ORDER.get(minimum, 99)


async def active_grant(grantee_id: str, owner_id: str) -> Any:
    """The (owner, grantee) DataAccessGrant row, with its subset items, or None."""
    if not grantee_id or not owner_id:
        return None
    return await db.dataaccessgrant.find_unique(
        where={"ownerId_granteeId": {"ownerId": owner_id, "granteeId": grantee_id}},
        include={"scopeItems": True},
    )


def _grant_covers(grant: Any, record_type: str, record_id: str) -> bool:
    if grant.allData:
        return True
    for item in grant.scopeItems or []:
        if item.recordType.lower() == record_type.lower() and item.recordId == record_id:
            return True
    return False


async def effective_tier_for_record(
    user: Any, owner_id: str | None, record_type: str, record_id: str | None
) -> str | None:
    """The highest tier `user` holds over one record owned by `owner_id`.

    Admins and the record's own creator implicitly have EDIT. Otherwise it's the tier of an active
    GRANTED grant from the owner to the user that covers this record (all-data, or the subset list).
    Returns None when the user has no access beyond plain viewing.
    """
    from app.core.deps import get_value, is_admin

    if is_admin(user):
        return "EDIT"
    uid = get_value(user, "id")
    if owner_id and uid == owner_id:
        return "EDIT"
    grant = await active_grant(uid, owner_id) if owner_id else None
    if not grant or _enum_str(grant.status) != "GRANTED":
        return None
    if record_id is not None and not _grant_covers(grant, record_type, record_id):
        return None
    return _enum_str(grant.tier)


# Infrastructural fields whose churn should not be logged as a meaningful edit.
REVISION_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "updatedAt",
    "createdAt",
    "createdById",
    "recordedAt",
    "recordedTimezone",
}


# -- COLUMNS WHOSE CHANGE IS AUDITED BUT WHOSE VALUE IS NOT --------------------------------------
# Clearing a nullable scalar is how a researcher acts on "take my number off your system". Until
# this set existed, the request that did it copied the retracted number INTO the row written below,
# because the skip set above holds only infrastructural churn and says nothing about the subject of
# the record. The API answered 200, the column went NULL, and the number the subject asked to be rid
# of was now in an append-only table with no delete endpoint: the app reported success while making
# a fresh copy in the one table nobody prunes.
#
# THE LEDGER WAS NEVER THE ONLY SECOND COPY, and an earlier draft of this comment claimed it was.
# Three of the five columns below -- ``address``, ``email``, ``phone`` -- are ALSO carried verbatim
# into every workshop stage entry that references the artisan: ``stage_definitions`` declares them
# as ``fromref("address"/"email"/"phone", ...)`` and ``design_workshops``'s Artisan reference ``data``
# lambda feeds them straight off the row. Hydration copies at SAVE time and the report never
# re-resolves, so clearing ``Artisan.phone`` still leaves the number in every ``DwStageEntry.data``
# that already referenced her and in every report generated from one. THIS SET DOES NOT CLOSE THAT,
# deliberately: a document already handed to a ministry officer must not change because somebody
# edited a record afterwards. The ledger is closed here because it is the copy that nothing about
# the product needs -- not because it was the only one. A retraction request that has to reach the
# stage entries is a separate, owner-level piece of work.
#
# BOTH PRESSURES HERE ARE REAL, AND THIS SET IS WHERE THEY WERE TRADED OFF. Neither is a nuisance to
# be simplified away later:
#
#   * AN AUDIT TRAIL ON A GOVERNMENT RECORD EXISTS SO THAT A CHANGE CANNOT BE MADE INVISIBLY.
#     Blanket-exempting contact columns -- moving them into REVISION_SKIP_FIELDS, which is the
#     one-line version of this fix -- would let an EDIT-tier grantee overwrite an artisan's phone
#     number with their own and leave no trace at all. ``GET /api/data-access/revisions`` is the
#     only surface an admin has for "who did what to this record", and a silent column is a hole in
#     it, not a privacy feature.
#   * A SUBJECT'S RIGHT TO HAVE PII REMOVED IS NOT SERVED BY MOVING IT WHERE THEY CANNOT LOOK.
#     Retention inside an audit table the subject has no access to is still retention, and calling
#     the request that performs it a success is the part that misleads the researcher who ran it.
#
# So the ledger keeps everything about the edit EXCEPT the value: WHICH field changed (the key),
# WHO changed it (``editedById``), WHEN (``createdAt``), and WHICH DIRECTION it moved -- set,
# replaced, or cleared. A malicious edit to a phone number still leaves a row naming its author and
# the minute they did it; what it no longer leaves is the number.
#
# WHAT THAT COSTS, STATED PLAINLY INSTEAD OF WAVED AWAY -- because an owner cannot sign off on a
# trade-off the comment denies exists. For ``phone``, ``email`` and ``address`` the cost really is
# small: the action available on a suspicious contact edit is "ask that editor what they changed and
# why", not "read the old number back out of the log". For ``aadhaarNumber`` and
# ``pehchanCardNumber`` it is NOT small. Both are UNIQUE deduplication columns
# (``routes/artisans._IDENTITY_CONSTRAINTS``, with a 409 "you already have this artisan" path built
# on them) and both are globally clearable. The Aadhaar is carried into NO stage entry at any
# masking, by design, so the RecordRevision ``old`` was the last copy of a previous Aadhaar number
# anywhere in the system: after this change an EDIT-tier grantee, or a professor outranking the
# author, can repoint or clear one, the previous identifier is UNRECOVERABLE, and the freed unique
# key admits a duplicate artisan with nothing left to trace the collision back to.
# (``pehchanCardNumber`` is one degree better only where a workshop exists: its masked last four
# already rides into ``participant.artisanCardNo`` on every stage entry that referenced her.)
#
# THE ALTERNATIVE IS A LIVE OWNER DECISION, NOT AN OVERSIGHT. Storing
# ``records.mask_identity_number(old_value)`` for those two columns instead of the flat placeholder
# would keep the last four for recovery and collision tracing while dropping the full identifier.
# It is not done here because house rule 5 is unconditional about the Aadhaar -- it crosses at NO
# masking, which is exactly why ``design_workshops`` refuses to carry it while carrying the masked
# Pehchan card -- and giving the two identity columns different treatment inside one set is worse
# than one rule that holds everywhere. Widening to the masked form is a small edit if the owner
# decides the recovery path outweighs that; it must be their decision, not a drive-by.
#
# WHY THESE FIVE AND NOT MORE. Every column here is a direct identifier of, or a route to, a living
# person -- the columns a "delete my details" request actually names. All five exist on ``Artisan``
# and, among the record types that reach ``record_revision`` (artisan, craft, product, tool,
# process, workshop, questionnaire), on no other model, so the match-by-column-name this function
# does is artisan-scoped in practice rather than by luck. Two near misses are deliberately OUT:
#   * ``notes``, ``dos``, ``donts``, ``localName``. Free text that may happen to contain something
#     personal, but it is not what a retraction targets, and it is exactly where a malicious edit
#     hides meaning -- the old text is the only way to see what was quietly taken out of a record.
#   * ``dateOfBirth``. The closest call. It is personal, but it is load-bearing derived data
#     (``participant.age`` on a workshop report comes from it) rather than a way to reach or single
#     out the person on its own. Adding it here is a one-line change if the owner wants the wider
#     line drawn.
#
# THIS SET DOES NOT REACH BACKWARDS, AND THAT IS AN OWNER DECISION SITTING IN THE TABLE RIGHT NOW.
# Every ``RecordRevision`` written BEFORE this set existed still holds the real old value for these
# five columns, in full, in an append-only table with no delete endpoint. Nothing below changes those
# rows: the code stopped making new copies, it did not remove the copies already made. So a subject
# who asked for their number to be removed a month ago is in the position this set exists to prevent,
# and the only fix is a data migration -- an UPDATE over the ``changes`` JSON of a GOVERNMENT AUDIT
# TABLE, rewriting history to say less than it said. That is emphatically not a drive-by:
#   * it is irreversible, and the value it destroys is the only remaining record of what an editor
#     changed on those rows (for the two identity columns, ``records._mask_identity_node`` explains
#     why the same values are also the last trace of a previous Aadhaar);
#   * a partial run leaves the ledger inconsistent about what it means, with no marker saying which
#     rows were rewritten and which were always redacted;
#   * "we altered the audit log" is a sentence that needs an owner's signature on it, whatever the
#     reason, and a good reason is exactly when it needs one most.
# Written down here rather than acted on. The reversible half is already done: nothing NEW is copied.
#
# THE READER COPES WITH THIS SHAPE, which was checked rather than assumed. THERE ARE TWO READERS,
# AND THIS COMMENT SAID THERE WAS ONE until an audit went and looked:
#   * ``frontend/components/CollabPanel.tsx`` renders every entry as
#     ``String(change.old ?? "-") -> String(change.new ?? "-")``;
#   * ``RecordCollabSection`` in ``android/app/src/main/java/com/designprototype/workshop/
#     MainActivity.kt`` renders ``"$field: ${jsonText(change.old)} -> ${jsonText(change.new)}"`` under
#     its "Edit history" heading, fed by ``WorkshopRepository.recordRevisions`` and typed as
#     ``ApiModels.RecordRevisionDto.changes: Map<String, RevisionChange>``.
# So the placeholders below print as an ordinary before/after line on BOTH clients with no change to
# the rendering. They are worded as descriptions rather than left null ON PURPOSE: a null pair
# renders as "- -> -" on either of them, which reads as "nothing happened" on the screens an admin
# opens to find out that something did.
#
# THE HANDSET IS NOT YET AT PARITY, AND THAT IS AN OPEN ITEM RATHER THAN A CLAIM OF DONE. Two gaps,
# both measured, both in files this unit does not own:
#   * the web panel's caption naming the redaction exception has no Android counterpart, so a handset
#     shows "aadhaarNumber: (value recorded) -> (cleared)" with nothing on screen saying why;
#   * ``RevisionChange`` models only ``old``/``new`` as ``JsonElement?``, and ``ApiClient``'s
#     ``ignoreUnknownKeys = true`` therefore DROPS the ``redacted`` flag -- not a crash, but the
#     handset cannot mark these rows as placeholders, which is the job the flag was added for.
# Neither is a leak: the placeholders carry no value, so the worst case is a reader who is not told
# that a value was withheld. Raised for the Android owner.
#
# AND NEITHER READER COPED UNTIL THE ENCODE WAS FIXED TO LET THE SHAPE THROUGH. ``changes`` is
# keyed by COLUMN NAME, and ``records.public_encode`` masks ``aadhaarNumber``/``pehchanCardNumber`` by
# that same key name -- so for exactly the two identity columns the entry below was handed to a
# masker that normalises with ``str(value)``, and ``GET /api/data-access/revisions`` served the
# literal string ``"XXXX XXXX rue}"`` (the tail of ``…'redacted': True}``, spaces stripped) instead
# of a dict. ``change.old`` on a string is undefined, so on the web those two rows rendered as
# "- -> -" -- the very reading this wording was chosen to avoid, on the two columns that matter most.
# The handset was handed the same string where its DTO declares ``RevisionChange``, an object; what
# kotlinx did with it was not measured here, and the fix removes the question rather than answering
# it. ``records``'s
# ``_mask_identity_node`` now RECOGNISES an audit entry under an identity key instead of stringifying
# it, and re-derives the placeholder pair through :func:`_redacted_change` rather than believing a
# ``redacted`` key it was handed; ``tests/test_revision_pii_redaction`` drives ``public_encode`` over
# a real blob so the write and the read are pinned together.
# The panel's CAPTION did have to change: it promised that "original values are the first before of
# each field", which stopped being true for these five the moment this set existed. Anything else
# that repeats that promise has to name the exception too -- ``TIER_DESCRIPTIONS["EDIT"]`` above and
# the ``list_revisions`` docstring do, and docs/DATA_MODEL.md spells it out.
# STILL OPEN: docs/SECURITY.md still describes "an append-only ``RecordRevision`` audit trail
# recording ``{field: {old, new}}`` per edit" with no exception, which is the security document
# making the promise this set breaks. docs/ is outside this unit's files; raised for the docs owner,
# and the one line it needs is a pointer to ``access.REVISION_REDACTED_FIELDS``.
REVISION_REDACTED_FIELDS = {
    "aadhaarNumber",
    "pehchanCardNumber",
    "phone",
    "email",
    "address",
}


#: The five wordings :func:`_redacted_change` writes, as constants rather than five literals inside
#: the function. They are named here because a SECOND module has to recognise them: ``changes`` is
#: keyed by column name, so ``records._mask_identity_node`` meets these placeholders on the way out
#: and must be able to tell one from a stored value WITHOUT trusting the ``redacted`` flag beside it
#: (see :data:`REDACTED_PLACEHOLDER_PAIRS`).
_REDACTED_HAD_VALUE = "(value recorded)"
_REDACTED_CLEARED = "(cleared)"
_REDACTED_REPLACED = "(value replaced)"
_REDACTED_EMPTY = "(empty)"
_REDACTED_STILL_EMPTY = "(still empty)"

#: Every ``(old, new)`` pair :func:`_redacted_change` can produce. THE POINT OF THE SET IS THAT IT IS
#: CLOSED: a reader holding it can decide "this entry is already one of ours" by comparing against
#: four constant pairs, instead of believing a ``redacted: True`` key that any client can write into
#: an ``extraMetadata`` blob. ``tests/test_revision_pii_redaction`` drives all four branches of the
#: function and asserts each result lands in here, so the two cannot drift apart.
REDACTED_PLACEHOLDER_PAIRS = frozenset(
    {
        (_REDACTED_EMPTY, _REDACTED_STILL_EMPTY),
        (_REDACTED_HAD_VALUE, _REDACTED_CLEARED),
        (_REDACTED_HAD_VALUE, _REDACTED_REPLACED),
        (_REDACTED_EMPTY, _REDACTED_HAD_VALUE),
    }
)

#: The keys one of these entries has, and no others. Read with :data:`REDACTED_PLACEHOLDER_PAIRS` by
#: the reader-side recogniser; kept beside the writer so adding a key here is impossible to do
#: without seeing that something else matches on it.
REDACTED_CHANGE_KEYS = frozenset({"old", "new", "redacted"})


def _redacted_change(old_value: Any, new_value: Any) -> dict[str, Any]:
    """The audit entry for a :data:`REVISION_REDACTED_FIELDS` column: direction of travel, no value.

    The four transitions get four distinct wordings so that no entry ever has ``old`` equal to
    ``new``. A consumer that decides whether to draw a row by diffing the two still sees a change on
    every one of them, which a shared "(redacted)" placeholder on both sides would have hidden.

    ``redacted`` rides along so a machine reader can tell one of these placeholders from a stored
    value that happens to look like one -- but the flag is a CONVENIENCE for a renderer, never a
    security decision, because the same key can appear in a client-written Json column. Anything
    deciding whether it may echo a value compares against :data:`REDACTED_PLACEHOLDER_PAIRS`.

    ALSO CALLED FROM THE READ PATH. ``records._mask_identity_node`` runs this over a HISTORICAL
    ledger entry (one written before :data:`REVISION_REDACTED_FIELDS` existed, which still holds the
    real values) so the served row gets the same vocabulary as a new one instead of a pair of
    identical masks. That is why the wordings are derived from the two values' emptiness rather than
    from anything only the writer knows.
    """
    from app.core.deps import is_empty_value

    had = not is_empty_value(old_value)
    has = not is_empty_value(new_value)
    if not had and not has:
        # Two spellings of empty, reached because ``values_match(None, "")`` is False: a payload
        # sending ``phone: ""`` at an already-NULL column lands here. Nothing was retracted, so the
        # pair must not say "(cleared)" -- that asserted a retraction on a column that held nothing,
        # on the one screen an admin reads to find out what was done to a record. The entry is kept
        # rather than dropped so that the redacted columns log the same ``None``/``""`` churn every
        # other column already logs; making it a no-op HERE would quietly give five fields a
        # different diffing rule from the rest of the row.
        return {"old": _REDACTED_EMPTY, "new": _REDACTED_STILL_EMPTY, "redacted": True}
    if not has:
        return {"old": _REDACTED_HAD_VALUE, "new": _REDACTED_CLEARED, "redacted": True}
    if had:
        return {"old": _REDACTED_HAD_VALUE, "new": _REDACTED_REPLACED, "redacted": True}
    return {"old": _REDACTED_EMPTY, "new": _REDACTED_HAD_VALUE, "redacted": True}


def redacted_placeholder(old_value: Any, new_value: Any) -> dict[str, Any]:
    """The placeholder pair a READER should serve in place of a redacted column's stored values.

    The same four wordings the writer uses, deliberately: a HISTORICAL ledger row (written before
    :data:`REVISION_REDACTED_FIELDS` existed, and therefore still holding the real old value) must
    reach a reader looking exactly like a row written today, or the edit-history screen would tell an
    admin which rows predate the redaction and nothing else useful. ``records._mask_identity_node``
    is the caller; it is a separate public name from :func:`_redacted_change` only so the read path is
    not reaching into another module's private helper.
    """
    return _redacted_change(old_value, new_value)


async def record_revision(record: Any, user: Any, data: dict[str, Any], record_type: str) -> None:
    """Append an immutable RecordRevision for whichever fields in `data` actually change `record`.

    Call with the cleaned update payload BEFORE field-provenance is merged in, so provenance bookkeeping
    is not mistaken for a content edit. No-op when nothing meaningful changed.

    Fields in :data:`REVISION_REDACTED_FIELDS` are logged as a change WITHOUT their value -- read the
    argument above that set before widening or narrowing it.
    """
    from app.core.deps import get_value, values_match

    changes: dict[str, Any] = {}
    for field, new_value in data.items():
        if field in REVISION_SKIP_FIELDS:
            continue
        old_value = get_value(record, field)
        if values_match(old_value, new_value):
            continue
        if field in REVISION_REDACTED_FIELDS:
            changes[field] = _redacted_change(old_value, new_value)
        else:
            changes[field] = {"old": jsonable_encoder(old_value), "new": jsonable_encoder(new_value)}
    if not changes:
        return
    await db.recordrevision.create(
        data={
            "recordType": record_type.lower(),
            "recordId": get_value(record, "id"),
            "editedById": get_value(user, "id"),
            "changes": Json(changes),
        }
    )


async def guard_record_edit(record: Any, user: Any, data: dict[str, Any], record_type: str) -> bool:
    """Authorize a field-changing edit and audit it. Returns True if the user is privileged (admin,
    owner, a professor+ outranking the record's author, or an EDIT-tier grantee) and may change any
    populated field/relation; False for an ordinary contributor (who may only fill empty fields —
    enforced here, raising 403 on a locked field). Always records a revision of the fields that
    change. Pass the cleaned `data` before provenance is merged.
    """
    from app.core.deps import (
        assert_can_contribute_fields,
        get_value,
        is_admin,
        may_edit_lower_ranked_record,
    )

    owner_id = get_value(record, "createdById")
    uid = get_value(user, "id")
    privileged = is_admin(user) or (owner_id is not None and uid == owner_id)
    if not privileged:
        # "A professor may edit the data of anyone ranked below them" — checked before the grant
        # lookup because it costs nothing for the ranks it does not apply to, so nobody below
        # Professor pays an extra query for a clause that can only ever answer no for them.
        if await may_edit_lower_ranked_record(user, owner_id) or (
            await effective_tier_for_record(user, owner_id, record_type, get_value(record, "id"))
            == "EDIT"
        ):
            privileged = True
        else:
            assert_can_contribute_fields(record, user, data)
    await record_revision(record, user, data, record_type)
    return privileged


async def assert_can_comment(user: Any, owner_id: str | None, record_type: str, record_id: str) -> None:
    """COMMENT tier (or owner/admin) required to comment on a record."""
    tier = await effective_tier_for_record(user, owner_id, record_type, record_id)
    if not tier_at_least(tier, "COMMENT"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need comment access to this researcher's data. Request it from them.",
        )


async def owner_download_scope(user: Any, owner_id: str) -> dict[str, set[str]] | None:
    """Authorize downloading `owner_id`'s data and return what is covered.

    Returns None when ALL of the owner's data is allowed (admin, the global dataset-download
    permission, the owner themselves, or an active all-data DOWNLOAD+ grant). Returns
    {recordType: {recordIds}} when only a subset grant applies. Raises 403 when there is no access.
    """
    from app.core.deps import can_download_dataset, get_value, is_admin

    if is_admin(user) or can_download_dataset(user) or get_value(user, "id") == owner_id:
        return None
    grant = await active_grant(get_value(user, "id"), owner_id)
    if not (grant and _enum_str(grant.status) == "GRANTED" and tier_at_least(_enum_str(grant.tier), "DOWNLOAD")):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need download access to this researcher's data. Request it from them.",
        )
    if grant.allData:
        return None
    scope: dict[str, set[str]] = {}
    for item in grant.scopeItems or []:
        scope.setdefault(item.recordType.lower(), set()).add(item.recordId)
    return scope
