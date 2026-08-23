from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    can_manage_crafts,
    get_current_user,
    require_record_creator,
)
from app.schemas.records import ArtisanCreate, ArtisanUpdate, drop_masked_identity_numbers
from app.services.access import guard_record_edit
from app.services.artisan_identity import mask_aadhaar, normalize_aadhaar
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import artisan_workshop_clause, resolve_workshop_ids
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
    include_of,
    merge_field_provenance,
    prose_contains,
    public_encode,
    require_record,
    resubmit_status,
    viewable_where,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    link_workshop_artisan,
    pin_pending_if_late,
    stamp_workshop_submission,
    sync_workshop_artisan,
)

router = APIRouter(prefix="/artisans", tags=["artisans"])

# What an artisan carries on the wire. Reads load these in one parallel wave (see
# services/records.py for why that is worth the indirection); writes still pass the derived
# ``INCLUDE`` to Prisma, so the two can never describe different artisans.
RELATIONS = (
    Relation("craft", "craft", "craftId"),
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("workshop", "workshop", "workshopId"),
)
INCLUDE = include_of(RELATIONS)

# ARTISAN'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null`` through for
# on this model, and the reason a researcher can retract a phone number at all.
#
# WHY THIS IS A PER-MODEL LIST AND NOT MORE ENTRIES IN ``records.CLEARABLE_KEYS``: that set is global
# and ``clean_data`` does not know which table a payload is bound for. ``email`` is nullable here and
# NOT NULL on User, DesignerRoster and AccessRoster, so a global entry would trade one silent no-op
# for a constraint violation on three other tables. See the ``clearable`` section of
# ``clean_data``'s docstring for the whole argument, including why this may only be passed from a
# route that dumps with ``exclude_unset=True`` — ``update_artisan`` below does, and must keep doing.
#
# WHAT IS DELIBERATELY ABSENT, so a later reader does not "complete" the list:
#   * ``name`` and ``place`` — NOT NULL on Artisan.
#   * ``pehchanCardAvailable``, ``status``, ``recordedAt``, ``recordedTimezone`` — NOT NULL.
#   * ``craftId``/``workshopId``/``locationId`` and both identity numbers — already global.
#   * ``craftName`` — not a column at all; ``resolve_craft_id`` turns it into ``craftId``.
#   * ``extraMetadata`` — nullable, but naming it here would change nothing: ``merge_field_provenance``
#     OWNS that column on this route and reassigns it a few lines below the clean, so the null never
#     survives to Prisma either way. Left out rather than listed-and-inert.
#
# ``dos``/``donts`` ARE here even though ``ArtisanCreate`` demands them, and that asymmetry is the
# existing one, not a new one: the columns are nullable precisely because rows recorded before the
# fields existed hold NULL, and ``ArtisanUpdate`` deliberately carries no ``min_length`` on either —
# so an editor can already empty the box today by sending ``""``. NULL is the honest spelling of the
# same edit rather than a state the model did not already have.
#
# ── WHAT A RETRACTION DOES **NOT** ERASE: THREE PLACES, ONE NOW CLOSED, TWO STILL OPEN ───────────
# Clearing the column is not the same as forgetting the value, and anybody reading this list as a
# right-to-erasure mechanism should know exactly how far it reaches. The residues below are
# PRE-EXISTING for ``aadhaarNumber`` and ``pehchanCardNumber``, which were globally clearable long
# before this list existed; naming the contact and free-text columns here widens them to those too.
# The two that remain are audit and report surfaces, and quietly redacting one of those is its own
# kind of wrong — so closing EITHER OF THOSE TWO is an OWNER DECISION, not a follow-up patch. The
# first residue was different in kind: it was a second copy the product had no use for, and it is
# now closed in code. Do not read this block as three open questions.
#
#   1. CLOSED, for the identity and contact columns only. ``services/access.record_revision`` used
#      to write the OLD value into an immutable ``RecordRevision.changes`` blob for every column not
#      in ``REVISION_SKIP_FIELDS`` (which holds only infrastructural churn), so the phone number the
#      subject asked to have removed was copied INTO the ledger by the request that removed it.
#      ``access.REVISION_REDACTED_FIELDS`` now covers aadhaarNumber / pehchanCardNumber / phone /
#      email / address: the ledger keeps which field changed, who changed it, when and in which
#      direction, and drops the value. THE REST OF THIS LIST IS NOT CLOSED, and the set is wider than
#      just the free text: ``notes``, ``dos``, ``donts`` and ``localName`` are excluded deliberately
#      (the old text is the only way to see what a malicious edit quietly removed), and so are
#      ``gender``, ``dateOfBirth``, ``craftStartDate`` and ``experienceYears`` — ``dateOfBirth`` is
#      named above that set as the closest call, being personal but load-bearing derived data
#      (``participant.age``). ``craftStartDate`` is the same shape one column later and is excluded
#      for the same reason: it is the feeder ``participant.experienceYears`` derives from, and it is
#      a fact about a trade rather than a route to a person.
#      Widening the set is a one-line edit and an owner's call; read the argument first.
#      The closure only holds because the READER end was fixed with it. Every response goes through
#      ``records.public_encode``, which masks ``aadhaarNumber``/``pehchanCardNumber`` BY KEY NAME —
#      and ``changes`` is keyed by column name too, so those two entries (dicts) were handed to a
#      masker that normalises with ``str(value)`` and ``GET /api/data-access/revisions`` served the
#      string ``"XXXX XXXX rue}"``. ``CollabPanel`` reads ``change.old`` off that, gets undefined,
#      and printed the row as "— → —": for the two identity columns the redaction had cancelled
#      itself out on both screens that read the ledger — the web panel and Android's
#      ``RecordCollabSection`` "Edit history", which does the same ``change.old``/``change.new``
#      render. ``records._mask_identity_node`` is what stops a container under an identity key being
#      flattened; see it for what a HISTORICAL ledger row (written before the redaction, still
#      holding a real number) is served as, and why. The handset is not yet at parity on the CAPTION
#      naming this exception, nor on the ``redacted`` flag (``RevisionChange`` does not model it, so
#      ``ignoreUnknownKeys`` drops it) — both are raised in ``access.REVISION_REDACTED_FIELDS``'s
#      block as open Android items, and neither is a leak: the placeholders carry no value.
#   2. STILL OPEN, AND LARGER THAN THE LEDGER EVER WAS. ``address``, ``email`` and ``phone`` are
#      carried verbatim into every workshop stage entry that references the artisan
#      (``stage_definitions``' ``fromref("address"/"email"/"phone", ...)``, fed by the Artisan
#      reference ``data`` lambda in ``services/design_workshops``), and the masked Pehchan card
#      rides across as ``participant.artisanCardNo``. Hydration copies at SAVE time and the report
#      never re-resolves, so clearing the column here leaves the value in every ``DwStageEntry.data``
#      and every generated report that already referenced her. That is the settled architecture
#      working as intended — a document handed to a ministry officer must not change because a
#      record was edited afterwards — which is exactly why erasing it is an owner decision and not a
#      bug fix. THE AADHAAR IS NOW IN THIS RESIDUE, and that sentence used to say the opposite: it
#      read "The Aadhaar is not in this residue: it is carried into no stage entry at any masking."
#      The owner reversed that on 2026-08-24 (``stage_definitions``' ``participant.aadhaarNumber``,
#      which carries the decision, what they were shown, and what would reverse it again), so the
#      masked last four ride into ``participant.aadhaarNumber`` on every stage entry that referenced
#      her, exactly as the Pehchan card already did. WHAT THAT CHANGES FOR THIS ROUTE IS WHAT IT CAN
#      HONESTLY PROMISE: clearing ``Artisan.aadhaarNumber`` here removes the twelve digits from the
#      record and from nothing else -- "XXXX XXXX 9012" survives in every ``DwStageEntry.data`` that
#      referenced her and in every report already generated from one, because hydration copied at
#      save time and the report never re-resolves. That is the same settled architecture the two
#      paragraphs above describe, now applying to one more column; it is NOT a new decision made
#      here, and it is not a bug in this handler. It does mean a "delete my details" request
#      touching this column leaves four digits behind wherever a design workshop rostered her, and
#      whoever answers such a request has to be able to say so.
#   3. STILL OPEN, AND BY DESIGN RATHER THAN BY OVERSIGHT — an attribution outliving the value it
#      attributes is the price of "an untouched field keeps the contributor it already had".
#      ``merge_field_provenance`` skips a cleared field entirely (its loop reads
#      ``if field in PROVENANCE_SKIP_FIELDS or is_empty_value(value): continue``, and
#      ``deps.is_empty_value(None)`` is True), so ``extraMetadata.fieldProvenance.phone`` keeps its
#      old ``{by, byName, at}`` stamp on a column that is now NULL. Not the number, but the web
#      client's "Field contributions" panel builds its rows from whatever keys that object holds,
#      so it still lists a ``phone`` row naming who entered the retracted number and when.
_CLEARABLE_COLUMNS = (
    "localName",
    "gender",
    "phone",
    "email",
    "address",
    "notes",
    "dateOfBirth",
    # CLEARABLE for the same reason ``dateOfBirth`` is: a date typed into the wrong box has to be
    # retractable from the form that typed it, and an omitted key on a PATCH means "leave it alone".
    # Clearing it does not blank the artisan's experience — it hands the answer back to the stated
    # ``experienceYears`` number, then to the legacy metadata, which is what the precedence in
    # ``REFERENCE_MODELS["Artisan"].data`` is for.
    "craftStartDate",
    "experienceYears",
    "dos",
    "donts",
)

# Unique columns that identify a real-world person, and the human phrasing for each. A collision on
# one of these is the deduplication working as designed, so it has to read as "you already have this
# artisan" rather than as a database error.
_IDENTITY_CONSTRAINTS = {
    "aadhaarNumber": "Aadhaar number",
    "pehchanCardNumber": "Artisan Pehchan Card number",
}


def _violated_identity_field(error: Exception) -> str | None:
    """Which identity column a Prisma unique-constraint error was raised on, if any."""
    text = str(error)
    if "unique" not in text.lower():
        return None
    for field in _IDENTITY_CONSTRAINTS:
        if field in text:
            return field
    return None


async def _identity_conflict(
    field: str, value: str, exclude_id: str | None = None, existing: Any = None
) -> HTTPException:
    """A 409 naming the artisan that already holds this number, so the researcher can go to them.

    The existing artisan's name and place are safe to return — the caller already possesses the
    identity number, and without them the message is a dead end ("duplicate" with nowhere to go).
    The number itself is echoed back MASKED.

    Pass ``existing`` when the caller has already loaded the clashing artisan: the pre-flight guard
    below has it in hand, and re-reading it would cost another cross-region round trip to learn
    something we already know.
    """
    if existing is None:
        existing = await db.artisan.find_first(where={field: value}, include={"craft": True})
    detail: dict[str, Any] = {
        "code": "artisan_identity_conflict",
        "field": field,
        "message": f"Another artisan is already recorded with this {_IDENTITY_CONSTRAINTS[field]}.",
    }
    if existing is not None and existing.id != exclude_id:
        detail["existingArtisan"] = {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
        }
        detail["message"] = (
            f"{existing.name} ({existing.place}) is already recorded with this "
            f"{_IDENTITY_CONSTRAINTS[field]}. Open that artisan instead of creating a duplicate."
        )
    if field == "aadhaarNumber":
        detail["maskedValue"] = mask_aadhaar(value)
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)


def _may_read_full_aadhaar(user: Any, artisan: Any) -> bool:
    """May this caller see an artisan's UNMASKED Aadhaar number?

    Aadhaar is regulated personal data, so plain "is signed in" is not the right bar — without this,
    a crowdsource volunteer could read any artisan's full number straight off ``GET /artisans/{id}``.
    The line is drawn at the people who actually need it to do the work: the researcher who recorded
    the artisan, and professor-and-above (which includes admins) who supervise and correct the data.
    Everyone else sees the same masked form the exports show.
    """
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return True
    return bool(get_value(user, "id")) and get_value(artisan, "createdById") == get_value(user, "id")


def _mask_artisan_identity(payload: Any, user: Any, artisan: Any) -> Any:
    """Replace ``aadhaarNumber`` with its masked form for callers not entitled to the raw value.

    Applied to the ENCODED payload, after ``public_encode``, so it covers the response no matter how
    the row was loaded. The masked string is deliberately the same ``XXXX XXXX 9012`` the data
    browser and the workbook show, so one artisan reads identically wherever they appear.
    """
    if not isinstance(payload, dict) or "aadhaarNumber" not in payload:
        return payload
    if _may_read_full_aadhaar(user, artisan):
        return payload
    payload["aadhaarNumber"] = mask_aadhaar(payload.get("aadhaarNumber"))
    return payload


async def _guard_identity_conflicts(data: dict[str, Any], exclude_id: str | None = None) -> None:
    """Reject a duplicate Aadhaar/Pehchan number BEFORE writing, with a message worth reading.

    The unique indexes are the real guarantee — this is the pre-check that turns a would-be 500 into
    an actionable 409. The write is still wrapped in its own handler, because two researchers can
    submit the same artisan in the same instant and only the index can settle that race.

    Both numbers are checked in ONE query, and that query already loads what the 409 needs to say.
    Checking them one at a time cost three sequential cross-region round trips on the way to every
    saved artisan — two probes and a third to look up the artisan being reported — for a question a
    single ``OR`` answers.
    """
    checks = {field: data[field] for field in _IDENTITY_CONSTRAINTS if data.get(field)}
    if not checks:
        return
    clashes = await db.artisan.find_many(
        where={"OR": [{field: value} for field, value in checks.items()]},
        include={"craft": True},
    )
    for field, value in checks.items():
        for clash in clashes:
            if getattr(clash, field, None) == value and clash.id != exclude_id:
                raise await _identity_conflict(field, value, exclude_id, existing=clash)


async def resolve_craft_id(data: dict[str, Any], current_user: Any) -> dict[str, Any]:
    craft_name = data.pop("craftName", None)
    if data.get("craftId") or not craft_name:
        return data
    existing = await db.craft.find_unique(where={"name": craft_name})
    if existing:
        data["craftId"] = existing.id
        return data
    # A free-text craft name that matches nothing would otherwise mint a Craft through the artisan
    # form — the same write POST /crafts guards, reached sideways. Same predicate, so the two can
    # never disagree: Professor and above.
    if not can_manage_crafts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                f"Craft '{craft_name}' does not exist yet. Select an existing craft, or ask a "
                "professor or an admin to add it."
            ),
        )
    created = await db.craft.create(data={"name": craft_name, "createdById": current_user.id})
    data["craftId"] = created.id
    return data


@router.get("")
async def list_artisans(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craft: str | None = None,
    craftId: str | None = None,
    workshopId: str | None = None,
    place: str | None = None,
    statusFilter: str | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    # The workshop SCOPE, plural, from the shared filter vocabulary — repeatable or comma-joined ids
    # plus the reserved value "none". Distinct from the singular ``workshopId`` above, which stays
    # because every existing form-picker link uses it; when both are sent both narrow.
    #
    # It is broader than the singular filter on purpose: it also counts an artisan who SAT IN an
    # interview taken at the workshop, through the shared ``artisan_workshop_clause``, so the
    # consolidated-questionnaire index and the completion matrix cannot disagree about who was there.
    workshopIds: list[str] | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so the
    # free-text search OR and the workshop OR can never overwrite one another — nor the row-visibility
    # filter, which joins the same AND.
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"place": contains(search)},
            # ``notes`` is one of the columns that can now hold a rich-text document, so it goes
            # through ``prose_contains`` rather than ``contains``. The other three cannot — a name,
            # a local name and a place are single-line fields with no editor behind them — and they
            # stay on the cheaper single-clause filter on purpose.
            prose_contains("notes", search),
            {"craft": {"is": {"name": contains(search)}}},
        ]
    if craft:
        where["craft"] = {"is": {"name": contains(craft)}}
    if craftId:
        where["craftId"] = craftId
    if workshopId:
        # Either reading counts: the artisan's own workshopId column, or the WorkshopArtisan join
        # (relation named ``workshops``) that carried the link before the column existed.
        and_filters.append({"OR": [
            {"workshopId": workshopId},
            {"workshops": {"some": {"workshopId": workshopId}}},
        ]})
    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        and_filters.append(artisan_workshop_clause(*resolved_workshops))
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    total, items = await count_and_page(
        db.artisan,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    # A list page hands back many artisans at once, so mask each row the caller is not entitled to
    # read raw — otherwise one request yields every id AND every full Aadhaar on the page.
    # The encoder masks per node against this viewer, so a list of a hundred artisans is one
    # decision per artisan rather than one blanket decision for the page: the two the caller
    # recorded come back raw, the rest masked.
    return page_payload(public_encode(items, current_user), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_artisan(
    payload: ArtisanCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    data = clean_data(payload.model_dump())
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    # Workshop entries: enforce assignment, then flag + pin a late submission for admin approval.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    # Aadhaar / Pehchan are the dedup keys: point the researcher at the existing artisan instead of
    # letting the unique index surface as a 500.
    await _guard_identity_conflicts(data)
    try:
        created = await db.artisan.create(data=data, include=INCLUDE)
    except Exception as exc:
        field = _violated_identity_field(exc)
        if field is None:
            raise
        # Lost the race against a concurrent submission of the same person.
        raise await _identity_conflict(field, data[field]) from exc
    # The explicit column ADDS to the WorkshopArtisan join every existing query still reads through.
    await link_workshop_artisan(created.workshopId, created.id)
    # The creator is by definition entitled, but pass the viewer anyway rather than relying on
    # that: a future change to who may create an artisan would otherwise silently leak.
    return public_encode(created, current_user)


@router.get("/{artisan_id}")
async def get_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    await hydrate_relations([artisan], RELATIONS)
    # Masked unless this caller is entitled to the raw number (creator, or professor+) — decided
    # inside the encoder now, so the same rule covers this route and every route that merely embeds
    # an Artisan relation without thinking about it.
    return public_encode(artisan, current_user)


@router.patch("/{artisan_id}")
async def update_artisan(
    artisan_id: str,
    payload: ArtisanUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    # Read BEFORE anything writes: the mirrored WorkshopArtisan row is keyed on the workshop the
    # artisan is leaving, and after the update that value is gone. See the sync call at the end of
    # this route for the unlink this pairs with.
    previous_workshop_id = artisan.workshopId
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    # A caller shown a masked number who saves without touching it means "leave it alone" — for the
    # Pehchan card as much as for the Aadhaar, since both are masked on the way out to them.
    data = drop_masked_identity_numbers(data)
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != artisan.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(artisan, current_user, data, "artisan")
    await apply_status_policy_update(current_user, artisan, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=artisan)
    pin_pending_if_late(data, current_user, check=check, record=artisan)
    merge_field_provenance(data, current_user, previous=artisan)
    resubmit_status(artisan, current_user, data)
    # "Card available = Yes" on an edit is only valid if a number exists — either arriving in this
    # PATCH or already stored. The create-time validator cannot make this call, because a PATCH
    # carrying just the flag is legitimate when the record already holds a number.
    #
    # There are two ways a PATCH can break that pair, and the schema validator can see neither: it is
    # handed only the keys the client actually sent, so it never learns what is on the row.
    #   * the answer turns to Yes while no number exists here or on the record, and
    #   * the number is CLEARED while the answer stays Yes — the mirror image, and the one that
    #     otherwise slips through as {"pehchanCardNumber": null} on its own.
    # A PATCH touching neither key is deliberately left alone: rows that predate this feature carry
    # the column's DEFAULT of Yes with no number, and an unrelated edit to one must not be refused.
    answered_yes = data.get("pehchanCardAvailable") is True
    clears_number = "pehchanCardNumber" in data and not data["pehchanCardNumber"]
    still_yes = data.get("pehchanCardAvailable", artisan.pehchanCardAvailable) is True
    if answered_yes or (clears_number and still_yes):
        number = data.get("pehchanCardNumber", artisan.pehchanCardNumber)
        if not number:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "Enter the Artisan Pehchan Card number, or set the card to 'No' if the artisan "
                    "does not hold one."
                ),
            )
    await _guard_identity_conflicts(data, exclude_id=artisan_id)
    try:
        updated = await db.artisan.update(where={"id": artisan_id}, data=data, include=INCLUDE)
    except Exception as exc:
        field = _violated_identity_field(exc)
        if field is None:
            raise
        raise await _identity_conflict(field, data[field], exclude_id=artisan_id) from exc
    # BIDIRECTIONAL, unlike the create path's one-way ``link_workshop_artisan``. An edit can REMOVE
    # a workshop, and until this call the removal stopped at the column: the join row this mirror
    # wrote the first time survived, so every query reading the roster kept the artisan in a
    # workshop their own page reported them as having left. Passing the previous value is what
    # makes the delete possible at all — ``updated.workshopId`` is null in exactly the case that
    # needs cleaning up. See ``sync_workshop_artisan`` for what it deliberately does NOT try to
    # tell apart.
    await sync_workshop_artisan(previous_workshop_id, updated.workshopId, updated.id)
    return public_encode(updated, current_user)


@router.get("/lookup/aadhaar")
async def lookup_artisan_by_aadhaar(
    number: str, _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Is this Aadhaar already on an artisan? The form's pre-flight duplicate check.

    Lets the researcher find out while they are still typing, instead of after filling a whole form
    and hitting a 409 on save. Returns ``{found, artisan?}`` and never 404s — "not found" is the
    expected, successful answer. Only the identifying fields of the existing artisan come back, and
    only to a signed-in user who already holds the number they searched with.
    """
    normalized = normalize_aadhaar(number)
    if not normalized:
        return {"found": False, "artisan": None}
    existing = await db.artisan.find_first(
        where={"aadhaarNumber": normalized}, include={"craft": True, "workshop": True}
    )
    if existing is None:
        return {"found": False, "artisan": None}
    return {
        "found": True,
        "artisan": {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
            "workshop": getattr(getattr(existing, "workshop", None), "title", None),
        },
    }


@router.get("/{artisan_id}/questionnaire")
async def get_artisan_questionnaire(artisan_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Everything recorded against this artisan in the questionnaire, gathered per artisan.

    Because one interview is shared across an exact set of artisans, a recording/note/answer made for
    a group (or a larger superset) belongs to EACH member individually — so it surfaces here for every
    artisan in the set, letting each be validated on their own, as part of a subset, or for the whole
    set. Returns: ``answered`` (non-empty answers across every interview the artisan belongs to) and
    ``interviews`` (each interview the artisan is in, with its recordings/media, notes, and the other
    artisans it was recorded with). Deletion of any media stays uploader-or-admin; the interview row is
    admin-only — enforced on the media/questionnaire routes, not here.
    """
    # The artisan check, the answers and the interviews are three independent reads, so they run
    # together — the 404 is still decided first, it just no longer holds the other two behind a
    # cross-region round trip of its own. Every interview the artisan belongs to (alone, in a subset,
    # or in a larger set) comes back with its recordings and co-artisans, so the same content is
    # validatable for this artisan individually.
    artisan, responses, interview_rows = await gather_reads(
        db.artisan.find_unique(where={"id": artisan_id}),
        db.questionnaireresponse.find_many(
            where={
                "interview": {"is": {"artisans": {"some": {"artisanId": artisan_id}}}},
                "answerText": {"not": None},
            },
            include={"question": True, "interview": True, "answeredBy": True},
            order={"createdAt": "asc"},
        ),
        db.questionnaireinterview.find_many(
            where={"artisans": {"some": {"artisanId": artisan_id}}},
            include={"artisans": {"include": {"artisan": True}}, "media": True},
            order={"createdAt": "desc"},
        ),
    )
    if not artisan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    answered: list[dict[str, Any]] = []
    for response in responses:
        if not (response.answerText and response.answerText.strip()):
            continue
        question = response.question
        interview = response.interview
        answered_by = response.answeredBy
        answered.append(
            {
                "responseId": response.id,
                "questionId": response.questionId,
                "prompt": question.prompt if question else None,
                "sectionCode": question.sectionCode if question else None,
                "sectionTitle": question.sectionTitle if question else None,
                "sortOrder": question.sortOrder if question else 0,
                "answerText": response.answerText,
                "notes": response.notes,
                "interviewId": response.interviewId,
                "interviewTitle": interview.title if interview else None,
                "interviewDate": interview.interviewDate if interview else None,
                "answeredByName": answered_by.name if answered_by else None,
            }
        )
    answered.sort(key=lambda item: ((item.get("sectionCode") or ""), item.get("sortOrder") or 0))

    interviews: list[dict[str, Any]] = []
    for interview in interview_rows:
        co_artisans = [
            link.artisan.name
            for link in (interview.artisans or [])
            if link.artisan and link.artisanId != artisan_id
        ]
        interviews.append(
            {
                "interviewId": interview.id,
                "title": interview.title,
                "notes": interview.notes,
                "interviewDate": interview.interviewDate,
                "place": interview.place,
                "language": interview.language,
                "status": interview.status,
                "artisanCount": len(interview.artisans or []),
                "coArtisans": co_artisans,
                "media": public_encode(interview.media or []),
            }
        )
    return public_encode(
        {
            "artisanId": artisan_id,
            "answered": answered,
            "total": len(answered),
            "interviews": interviews,
        }
    )


@router.delete("/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.artisan, artisan_id)
    await db.artisan.delete(where={"id": artisan_id})
