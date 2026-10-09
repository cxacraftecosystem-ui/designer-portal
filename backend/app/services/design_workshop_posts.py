"""Who holds which post on ONE design & prototype workshop, and the separation of duties between them.

=======================================================================================
THE RULING THIS MODULE CARRIES (owner's decision, 2026-10-09)
=======================================================================================

MASTER_ADMIN, ADMIN and MINISTRY_ADMIN may be APPOINTED, workshop by workshop, as its designer, its
Assistant Director, its Regional Director or its inspector — through the same pickers as everybody
else. Nothing about their rank does this: an appointment is a row, and the row is the whole of the
authority, exactly as it is for an INSPECTOR or an ASSISTANT_DIRECTOR. What makes that safe is a
short list of rules about one workshop at a time, and they are written down HERE, once, because four
write paths and one loader have to agree about them:

1. **NOBODY APPOINTS THEMSELVES**, to any of the four posts — **AND NOBODY TAKES THEMSELVES OFF** an
   inspection or oversight post (2026-10-09, :func:`self_release_refusal`, 409): another assigner has
   to, which is what rule 5's refusal tells a holder to ask for. A holder who could drop their own
   post could then write the workshop, with the deleted row the only record they ever held it.
2. **ONE PERSON IS NEVER BOTH THE ASSISTANT DIRECTOR AND THE REGIONAL DIRECTOR** of one workshop.
3. **AN INSPECTOR IS NEVER ALSO THE WORKSHOP'S ASSISTANT OR REGIONAL DIRECTOR.**
4. **NOBODY INSPECTS OR SUPERVISES A WORKSHOP THEY AUTHORED.** Authorship is holding designer access
   to it (a ``DesignWorkshopViewer`` row) or having written its stages — NOT having created it.
   Sanctioning officers and administrators open workshops as an administrative act, so
   ``createdById`` says nothing about who did the work; ``design_workshops.stage_writers`` says
   exactly which stage writes count and why the opening's prefill does not.
5. **WHOEVER HOLDS AN INSPECTION OR OVERSIGHT POST ON A WORKSHOP DOES NOT WRITE IT** — its CONTENT or
   its DESIGNER TEAM, through any door, the admin routes included (:func:`refuse_a_holders_write`
   lists every door that asks). Its content includes the records filed under it and the files it
   holds, so the record forms and the media routes ask too. They read it, on their own surface or
   through the admin arm's read.
6. **SO DESIGNER ACCESS IS REFUSED TO ANYBODY HOLDING ONE OF THOSE POSTS**, which is rule 5 enforced
   at the grant as well as at the write.

WHAT A POST HOLDER KEEPS, decided with the same ruling (2026-10-09) so nobody narrows it by analogy:
every read; appointing OTHER people to posts — and taking OTHER people off them — under rules 1-4;
restoring a deleted workshop; and generating its report, with the export ledger row that records
one. None of those writes what the workshop says or who writes it.

THE STATUS CODES SAY WHICH KIND OF REFUSAL IT IS. A rule above broken by an appointment answers 409
with a sentence naming the rule: nothing is wrong with the account, it is the workshop's present
arrangement that is in the way. A write refused by rule 5 answers 403 naming the post. The refusals
the write paths already had — no such account, a role that may not hold the post, an address the
allow-list bars — stay 422, and when both kinds arise in one request the 422 carries every sentence
so the administrator still makes one trip (:func:`raise_refusals`).

WHAT HOLDING A POST DOES **NOT** CONFER, so nobody reads it in: an Assistant or Regional Director
post is view-and-monitor. The approve, revise and hand-on edges have no route
(``schemas/design_workshop_review_loop.DECISION_EDGES``) and none is built here.

THE INSPECTION TABLE IS READ THROUGH ITS OWN MODULE. ``tests/test_dw_inspector_scope_gate.py`` keeps
that table's name out of every file but the inspection feature's own, so this module asks
``design_workshop_inspectors.inspection_holders_among`` — a REFUSAL input, never a grant — and imports
it at call time, because that module imports this one for :data:`SERVING_ADMIN_ROLES`.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import role_value
from app.services.concurrency import gather_reads

#: The three administrator tiers that may be APPOINTED to a post on one workshop. Owner's decision,
#: 2026-10-09.
#:
#: The same three members as ``deps.ACCOUNT_PROVISIONER_ROLES`` and as
#: ``design_workshop_oversight.OVERSIGHT_ASSIGNER_ROLES``, and deliberately a third name: who may BE
#: appointed, who may provision accounts and who may APPOINT are three decisions that happen to
#: agree today. ``is_admin`` stays exactly {MASTER_ADMIN, ADMIN}; nothing here widens it.
SERVING_ADMIN_ROLES = frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})

#: The four posts. The two oversight posts are spelled as the ``DwOversightCapacity`` enum spells
#: them, so an oversight row's capacity IS its post with no translation table to drift.
ASSISTANT_DIRECTOR = "ASSISTANT_DIRECTOR"
REGIONAL_DIRECTOR = "REGIONAL_DIRECTOR"
INSPECTOR = "INSPECTOR"
DESIGNER = "DESIGNER"

OVERSIGHT_POSTS = frozenset({ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR})

#: The posts whose holder reads a workshop and must not write it (rules 5 and 6).
SUPERVISORY_POSTS = OVERSIGHT_POSTS | {INSPECTOR}

POST_LABELS = {
    ASSISTANT_DIRECTOR: "Assistant Director",
    REGIONAL_DIRECTOR: "Regional Director",
    INSPECTOR: "inspector",
    DESIGNER: "designer",
}

#: The two kinds of authorship evidence rule 4 recognises.
DESIGNER_ACCESS = "DESIGNER_ACCESS"
STAGE_WRITES = "STAGE_WRITES"

#: The order posts are named in a sentence, so two requests about one person read the same.
_SPOKEN_ORDER = (ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR, INSPECTOR, DESIGNER)


@dataclass(frozen=True)
class Standing:
    """What one account already is on one workshop, as the rules read it.

    ``posts`` is the supervisory posts the account holds AS THE CHANGE BEING VALIDATED WOULD LEAVE
    THEM — so an Assistant Director being moved to Regional Director in the same request is not
    refused for holding both. ``authored`` is the rule-4 evidence: :data:`DESIGNER_ACCESS`,
    :data:`STAGE_WRITES`, both or neither.
    """

    posts: frozenset[str] = frozenset()
    authored: frozenset[str] = frozenset()


def _labels(posts: Iterable[str]) -> str:
    """The posts as words, in one stable order: ``Assistant Director and inspector``."""
    held = set(posts)
    named = [POST_LABELS[post] for post in _SPOKEN_ORDER if post in held]
    if len(named) <= 1:
        return "".join(named)
    return ", ".join(named[:-1]) + " and " + named[-1]


def _as(post: str) -> str:
    """The post as the object of a sentence: ``its Assistant Director``."""
    return f"its {POST_LABELS[post]}"


def _evidence(authored: frozenset[str]) -> str:
    if authored >= {DESIGNER_ACCESS, STAGE_WRITES}:
        return "holds designer access to this workshop and has written its stages"
    if DESIGNER_ACCESS in authored:
        return "holds designer access to this workshop"
    return "has written this workshop's stages"


def separation_refusals(
    *, person: str, post: str, standing: Standing, self_appointed: bool
) -> list[str]:
    """The sentences for every separation-of-duties rule this appointment would break. Pure.

    ``person`` is how the account is named in the sentence — the callers pass "Name (email)", the
    form every other refusal on these screens already uses. An empty list means the appointment is
    allowed as far as THESE rules go; the account-level rules are the caller's.

    Every sentence names the rule it enforces, because the administrator reading it has to be able to
    tell "pick somebody else" from "take them off the other post first".
    """
    refusals: list[str] = []
    if self_appointed:
        refusals.append(
            f"{person} is the account making this appointment, and nobody appoints themselves to a "
            f"workshop: another administrator has to name them {_as(post)}."
        )
    others = standing.posts - {post}
    if post in SUPERVISORY_POSTS:
        if standing.authored:
            refusals.append(
                f"{person} {_evidence(standing.authored)}, so they cannot be {_as(post)}: nobody "
                f"inspects or supervises work they authored. Creating a workshop is not authoring "
                f"it; holding designer access to it or writing its stages is."
            )
        if post in OVERSIGHT_POSTS and others & OVERSIGHT_POSTS:
            refusals.append(
                f"{person} would be both the Assistant Director and the Regional Director of this "
                f"workshop. One person cannot hold both posts on one workshop."
            )
        if post == INSPECTOR and others & OVERSIGHT_POSTS:
            refusals.append(
                f"{person} is this workshop's {_labels(others & OVERSIGHT_POSTS)}, so they cannot "
                f"also inspect it. One person cannot both supervise a workshop and inspect it."
            )
        if post in OVERSIGHT_POSTS and INSPECTOR in others:
            refusals.append(
                f"{person} inspects this workshop, so they cannot also be {_as(post)}. One person "
                f"cannot both supervise a workshop and inspect it."
            )
    elif post == DESIGNER and standing.posts & SUPERVISORY_POSTS:
        refusals.append(
            f"{person} is this workshop's {_labels(standing.posts & SUPERVISORY_POSTS)}, so they "
            f"cannot also be given designer access to it: whoever inspects or supervises a workshop "
            f"does not write it. Take them off that post first if they are to work on it instead."
        )
    return refusals


def write_refusal(posts: Iterable[str]) -> str:
    """The 403 sentence for somebody who holds a supervisory post on the workshop they tried to write."""
    return (
        f"You are this workshop's {_labels(posts)}, so you can read it but not change it: whoever "
        f"inspects or supervises a workshop does not write it. Ask whoever made the appointment to "
        f"take you off that post if you need to work on it."
    )


def self_release_refusal(posts: Iterable[str]) -> str:
    """The 409 sentence for somebody taking THEMSELVES off an inspection or oversight post (rule 1).

    :func:`write_refusal` tells a holder to ask whoever made the appointment; this is what makes that
    true. A holder who could drop their own post in one call could then write the workshop, and the
    row that recorded the post is deleted with it — so nothing would show they ever held it.
    """
    return (
        f"You are this workshop's {_labels(posts)}, and nobody takes themselves off a post: another "
        f"administrator has to take you off. Nothing was changed."
    )


def raise_refusals(account_refusals: list[str], separation: list[str]) -> None:
    """Raise the one answer a write path owes, or nothing.

    422 when anything is wrong with an ACCOUNT, carrying every sentence — the separation ones too — so
    the administrator learns everything in one trip; 409 when the only thing in the way is how the
    workshop is staffed. Repeated sentences are said once: naming one person in both oversight slots
    breaks rule 2 from each slot's side, and it is one fact.
    """
    unique = list(dict.fromkeys(separation))
    if account_refusals:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=" ".join([*account_refusals, *unique, "Nothing was changed."]),
        )
    if unique:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=" ".join([*unique, "Nothing was changed."]),
        )


# --------------------------------------------------------------------------------------
# Reading what one workshop's posts are
# --------------------------------------------------------------------------------------


def could_hold_a_supervisory_post(user: Any) -> bool:
    """Is this account's role one that may hold an oversight or inspection post at all?

    The write gate's cheap first question: a DESIGNER can hold no such post, so the stage save a
    designer is standing there waiting for — the hot path — costs no query. A row held by an account
    whose role has since moved outside every holder set is honoured nowhere (both read surfaces test
    the role first), and it is not consulted here either.
    """
    from app.services.design_workshop_inspectors import INSPECTION_HOLDER_ROLES
    from app.services.design_workshop_oversight import OVERSIGHT_HOLDER_ROLES

    return role_value(user) in (INSPECTION_HOLDER_ROLES | OVERSIGHT_HOLDER_ROLES)


async def refuse_a_holders_write(workshop_id: str, user: Any) -> None:
    """403 naming the post when ``user`` inspects or supervises the workshop they are about to write.

    Rule 5, at the write, and ONE implementation for every door that writes a workshop's CONTENT or
    its DESIGNER TEAM — the owner's ruling, made precise on 2026-10-09:

    * content: ``design_workshops.load_workshop_or_404(for_edit=True)`` — every stage save, the
      workshop's edit and delete, the rest of the designer routes, the admin arm of that loader
      included, every record filed INTO the workshop on a create or a move, because that loader is
      the record forms' filing gate, and every sitting or answer recorded on a questionnaire form
      attached to it; the oversight screen's artisan-list import and unlink, which reach stage 3's
      participants and the artisan link through their own loader;
    * the records filed under it, which are its content too
      (``record_design_workshop.assert_may_write_a_record_filed_under``): ANY PATCH of an artisan,
      product, process, tool or interview filed there — an unfile and a move out included — its
      DELETE, an interview merge with either side there, a tool's artisan links, and every edit of a
      questionnaire form attached to it (rename, deactivate, detach or move, re-upload, its sections
      and questions, a sitting's own fields);
    * the files it holds (:func:`refuse_a_holders_media_write`, over every way
      :func:`media_design_workshop_ids` finds): ``DELETE /media/{id}``, the transcript's set, refine
      and transcribe-now, the identity photograph's decision either way, and a relink out of the
      workshop or into it;
    * a record or file of it reached through the unfiled-records report (2026-10-09,
      ``services/workshop_inference``): filing one under a crafts workshop and discarding one, and
      the bulk map, which leaves the rows of a held workshop out (:func:`media_held_among`) rather
      than refusing the whole run;
    * the designer team: ``PUT /design-workshops/{id}/viewers``, the oversight screen's two
      designer doors (naming a designer also copies their profile into stage 1), approving an access
      request onto the workshop, and printing a join card for it — a card is "equivalent to an admin
      adding somebody", in its own module's words.

    What does NOT come here is listed in the module header: appointing OTHER people, restore, and
    the report and its export ledger. ``load_workshop_or_404`` takes ``barred_to_post_holders=False``
    for the one of those that loads for edit.

    403 AND NOT 404: the caller has already been admitted and is looking at this workshop, so they
    are owed the reason, which names the post. A role that can hold no such post is answered from
    memory, so a designer's stage save — the hot path — pays no query.
    """
    if not could_hold_a_supervisory_post(user):
        return
    user_id = str(getattr(user, "id", "") or "")
    held = (await supervisory_posts_among(workshop_id, [user_id])).get(user_id)
    if held:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=write_refusal(held))


async def supervisory_posts_among(
    workshop_id: str, user_ids: Iterable[str]
) -> dict[str, frozenset[str]]:
    """``{user id: the supervisory posts they hold on this workshop}``; absent means none.

    Two indexed reads, gathered: the oversight rows by workshop, and the inspection rows through the
    inspection feature's own reader (see the module header for why it is asked rather than read).
    The ids must be real account ids — every caller has already looked them up.
    """
    from app.services.design_workshop_inspectors import inspection_holders_among

    ids = sorted({uid for uid in user_ids if uid})
    if not workshop_id or not ids:
        return {}
    rows, inspecting = await gather_reads(
        db.designworkshopoversight.find_many(
            where={"designWorkshopId": workshop_id, "userId": {"in": ids}}
        ),
        inspection_holders_among(workshop_id, ids),
    )
    held: dict[str, set[str]] = {}
    for row in rows:
        held.setdefault(row.userId, set()).add(str(getattr(row.capacity, "value", row.capacity)))
    for uid in inspecting:
        held.setdefault(uid, set()).add(INSPECTOR)
    return {uid: frozenset(posts) for uid, posts in held.items()}


async def authorship_among(
    workshop_id: str, user_ids: Iterable[str]
) -> dict[str, frozenset[str]]:
    """``{user id: rule-4 evidence}`` for the accounts that authored this workshop; absent means none.

    A viewer row is :data:`DESIGNER_ACCESS`; a counted stage write is :data:`STAGE_WRITES`. The
    creator is deliberately not consulted — see the module header.
    """
    from app.services.design_workshop_viewers import viewer_ids_among
    from app.services.design_workshops import stage_writers

    ids = sorted({uid for uid in user_ids if uid})
    if not workshop_id or not ids:
        return {}
    viewers, writers = await gather_reads(
        viewer_ids_among(workshop_id, ids), stage_writers([workshop_id], ids)
    )
    evidence: dict[str, set[str]] = {}
    for uid in viewers:
        evidence.setdefault(uid, set()).add(DESIGNER_ACCESS)
    for _workshop, uid in writers:
        evidence.setdefault(uid, set()).add(STAGE_WRITES)
    return {uid: frozenset(kinds) for uid, kinds in evidence.items()}


# --------------------------------------------------------------------------------------
# A file's workshops: rule 5 at the media doors (2026-10-09)
# --------------------------------------------------------------------------------------
#
# A stage photograph, a recording and its transcript are a workshop's content as much as the stage
# that names them: the report embeds the photographs and prints the transcripts in its annexure. But
# the media routes write a FILE, not a workshop, and most of those files say which workshop they
# belong to in no single column — so the question "may this holder change this file" first needs
# "whose content is it", answered below in one place for every media door.

#: The link types a file can hang off a RECORD by, and the table holding that record's own
#: ``designWorkshopId``. ``processstep`` is answered through its process (:func:`link_filing`), and
#: ``craft`` and ``workshop`` are absent because neither is ever filed under a design workshop.
_FILED_RECORD_TABLES: dict[str, str] = {
    "artisan": "artisan",
    "product": "productdocumentation",
    "tool": "tooldocumentation",
    "questionnaire": "questionnaireinterview",
    "process": "process",
    "media": "mediafile",
}

#: The spellings the clients and ``media._relink_delegate`` accept for one link type.
_LINK_SPELLINGS: dict[str, str] = {"questionnaireinterview": "questionnaire", "misc": "media"}

#: ``MediaFile``'s typed parent columns. Read as well as the tag, and for
#: ``dictation_consent.interview_workshop_id``'s reason: the server writes them, and an interview merge
#: repoints ``questionnaireInterviewId`` while the tag goes on naming the row the merge deleted.
_TYPED_PARENTS: dict[str, str] = {
    "artisanId": "artisan",
    "productId": "product",
    "toolId": "tool",
    "questionnaireInterviewId": "questionnaire",
}


def _media_bearing_fields() -> tuple[list[str], list[str]]:
    """``(entity keys, field keys)`` of every stage field that can hold a media id: the media-typed
    fields, and the RICH_TEXT fields whose IMAGE blocks hold one several levels down.

    The same registry walk as ``design_workshops._media_ids`` — the set the report RESOLVES — so a
    photograph the report prints cannot be one this gate fails to see.
    """
    from app.services.stage_schema import stages

    entity_keys: set[str] = set()
    field_keys: set[str] = set()
    for spec in stages():
        for entity in spec.entities:
            keys = {f.key for f in entity.fields if f.type.is_media or f.is_rich_text}
            if keys:
                entity_keys.add(entity.key)
                field_keys |= keys
    return sorted(entity_keys), sorted(field_keys)


async def stage_media_workshop_ids(media_id: str) -> set[str]:
    """Every design workshop whose live stage entries hold this media id, in any field that can.

    ``dictation_consent.stage_attached_workshop_ids``' statement widened from the AUDIO fields to every
    field :func:`_media_bearing_fields` names — that one is the consent gate's, deliberately the set
    the queue transcribes, and must not grow. ``jsonb_path_exists`` with ``$.**`` and not ``@>``,
    because a RICH_TEXT value keeps its pictures inside block objects (``{"media": "<id>"}``) where no
    containment test on the field's own value reaches; the recursive accessor matches the bare id, a
    list of ids and an IMAGE block alike. Soft-deleted entries do not count: a stage row a designer
    removed no longer says the workshop holds the file.
    """
    media_id = str(media_id or "").strip()
    if not media_id:
        return set()
    entity_keys, field_keys = _media_bearing_fields()
    if not entity_keys:
        return set()
    rows = await db.query_raw(
        'SELECT DISTINCT e."designWorkshopId" AS id FROM "DwStageEntry" e '
        'WHERE e."deletedAt" IS NULL AND e."entityKey" = ANY($2::text[]) AND EXISTS ('
        '  SELECT 1 FROM jsonb_each(e."data") kv WHERE kv.key = ANY($3::text[])'
        "  AND jsonb_path_exists(kv.value, '$.** ? (@ == $id)', jsonb_build_object('id', $1::text)))",
        media_id,
        entity_keys,
        field_keys,
    )
    return {str(row["id"]) for row in rows if row.get("id")}


async def _ai_layer_workshop_ids(media_id: str) -> set[str]:
    """The workshops whose live AI layers were made from this file. They go with it — the layer's
    ``sourceMedia`` is ``onDelete: Cascade`` — so deleting the file deletes their captions too."""
    if not media_id:
        return set()
    rows = await db.dwailayer.find_many(where={"sourceMediaId": media_id, "deletedAt": None})
    return {str(row.designWorkshopId) for row in rows if getattr(row, "designWorkshopId", None)}


async def link_filing(link_type: str | None, record_id: str | None) -> set[str]:
    """The design workshop the record one media link names is FILED under, as a set; empty for none.

    A record filed under a workshop is that workshop's content (``record_design_workshop``), and its
    photographs and recordings are part of the record — so a file that hangs off one is the
    workshop's too, which is also how ``dictation_consent.interview_workshop_id`` reads a sitting's
    clip. A step is answered through its process. A misc file linked to another misc file is
    answered with that file's own column and tag, one hop and no further. Never more than two
    primary-key reads, and a parent that is gone answers nothing rather than raising.
    """
    kind = str(link_type or "").strip().lower()
    kind = _LINK_SPELLINGS.get(kind, kind)
    record_id = str(record_id or "").strip()
    if kind == "processstep" and record_id:
        step = await db.processstep.find_unique(where={"id": record_id})
        kind, record_id = "process", str(getattr(step, "processId", None) or "")
    table = _FILED_RECORD_TABLES.get(kind)
    if table is None or not record_id:
        return set()
    row = await getattr(db, table).find_unique(where={"id": record_id})
    if row is None:
        return set()
    found = {str(getattr(row, "designWorkshopId", None) or "")}
    if table == "mediafile":
        from app.services.dictation_consent import tagged_workshop_id

        found.add(tagged_workshop_id(row) or "")
    return {workshop_id for workshop_id in found if workshop_id}


async def media_design_workshop_ids(media: Any) -> set[str]:
    """Every design workshop this stored file belongs to — what a write to it would change.

    FIVE WAYS, because a file names its workshop in no single column:

    1. ``designWorkshopId``, set when a designer FILES a miscellaneous upload under one;
    2. the ``designWorkshop`` tag both clients put on a stage capture, in either spelling
       (``dictation_consent.tagged_workshop_id``);
    3. the live stage entries that hold its id, in any media field or inside rich text
       (:func:`stage_media_workshop_ids`) — the only evidence for a capture uploaded untagged and
       attached afterwards, which ``dictation_consent`` records is the ordinary order of events;
    4. the live AI layers made from it, which its delete would take with it;
    5. the record it hangs off, when that record is filed under a workshop (:func:`link_filing`).

    One wave of reads. It is asked only for an account that could hold a post at all — see
    :func:`refuse_a_holders_media_write` — so a designer's own upload and delete pay nothing.
    """
    from app.services.dictation_consent import tagged_workshop_id

    media_id = str(getattr(media, "id", "") or "")
    found = {str(getattr(media, "designWorkshopId", None) or ""), tagged_workshop_id(media) or ""}
    links: set[tuple[str, str]] = set()
    for column, kind in _TYPED_PARENTS.items():
        parent = str(getattr(media, column, None) or "").strip()
        if parent:
            links.add((kind, parent))
    tag = str(getattr(media, "linkedRecordType", None) or "").strip().lower()
    tagged = str(getattr(media, "linkedRecordId", None) or "").strip()
    if tag and tagged:
        links.add((_LINK_SPELLINGS.get(tag, tag), tagged))
    answers = await gather_reads(
        stage_media_workshop_ids(media_id),
        _ai_layer_workshop_ids(media_id),
        *(link_filing(kind, parent) for kind, parent in sorted(links)),
    )
    for workshop_ids in answers:
        found |= workshop_ids
    return {workshop_id for workshop_id in found if workshop_id}


async def refuse_a_holders_media_write(
    media: Any, user: Any, *, relinked_to: tuple[str, str] | None = None
) -> None:
    """:func:`refuse_a_holders_write` for every workshop this file belongs to — and, on a relink, the
    one it would arrive under (``relinked_to`` is the new link's type and record id).

    Asked by every media door that changes a file: delete, the transcript's set, refine and
    transcribe-now, the identity photograph's decision either way, and the relink, at both ends.
    Refine persists nothing and is asked anyway: its only use is the edit that follows, which a
    holder may not make, and it spends a provider call on the way.

    An administrator who holds no post on any of those workshops keeps every power these doors give
    them, and a role that can hold no post is answered from memory before anything is read.
    """
    if not could_hold_a_supervisory_post(user):
        return
    reads = [media_design_workshop_ids(media)]
    if relinked_to is not None:
        reads.append(link_filing(*relinked_to))
    workshop_ids: set[str] = set().union(*await gather_reads(*reads))
    for workshop_id in sorted(workshop_ids):
        await refuse_a_holders_write(workshop_id, user)


# --------------------------------------------------------------------------------------
# Many rows at once: the unfiled records' bulk map (2026-10-09)
# --------------------------------------------------------------------------------------
#
# ``workshop_inference.apply_workshop_mapping`` stamps a crafts workshop onto every row the ladder
# resolved, hundreds at a time, and a row that belongs to a design workshop is that workshop's content
# like any other: its inspector or director does not write it, by that door either. Asked row by row
# through :func:`media_design_workshop_ids`, a file would cost three or four reads, so the two
# functions below ask the same questions once for the whole set — and first ask whether the caller
# holds any post at all, which almost every administrator does not.


async def supervisory_workshops_of(user: Any) -> set[str]:
    """Every design workshop ``user`` holds an inspection or oversight post on; empty for none.

    Two indexed reads, gathered, and none for a role that can hold no post. A row held by an account
    whose role has since moved outside every holder set is not counted, as nowhere else counts it.
    """
    if not could_hold_a_supervisory_post(user):
        return set()
    from app.services.design_workshop_inspectors import inspected_workshop_ids

    user_id = str(getattr(user, "id", "") or "")
    if not user_id:
        return set()
    overseen, inspected = await gather_reads(
        db.designworkshopoversight.find_many(where={"userId": user_id}),
        inspected_workshop_ids(user_id),
    )
    return {str(row.designWorkshopId) for row in overseen if row.designWorkshopId} | set(inspected)


#: :func:`stage_media_workshop_ids`' statement asked of MANY files and only the workshops that
#: matter: which of the ids in ``$1`` the live stage entries of the workshops in ``$2`` hold, in any
#: field that can. ``$3`` and ``$4`` are :func:`_media_bearing_fields`, exactly as there.
_STAGED_AMONG_SQL = (
    "SELECT DISTINCT m.id AS id FROM unnest($1::text[]) AS m(id) WHERE EXISTS ("
    '  SELECT 1 FROM "DwStageEntry" e'
    '  WHERE e."designWorkshopId" = ANY($2::text[]) AND e."deletedAt" IS NULL'
    '  AND e."entityKey" = ANY($3::text[]) AND EXISTS ('
    '    SELECT 1 FROM jsonb_each(e."data") kv WHERE kv.key = ANY($4::text[])'
    "    AND jsonb_path_exists(kv.value, '$.** ? (@ == $id)', jsonb_build_object('id', m.id))))"
)


async def _staged_among(media_ids: list[str], workshop_ids: list[str]) -> set[str]:
    """Which of these files the live stage entries of these workshops hold. One round trip."""
    if not media_ids or not workshop_ids:
        return set()
    entity_keys, field_keys = _media_bearing_fields()
    if not entity_keys:
        return set()
    rows = await db.query_raw(_STAGED_AMONG_SQL, media_ids, workshop_ids, entity_keys, field_keys)
    return {str(row["id"]) for row in rows if row.get("id")}


async def _links_filed_under(
    links: set[tuple[str, str]], workshop_ids: set[str]
) -> set[tuple[str, str]]:
    """Which of these ``(link type, record id)`` pairs name a record filed under one of these
    workshops: :func:`link_filing` for many links at once — one read per table, a step answered
    through its process, a misc file through its own column and tag, one hop and no further."""
    from app.services.dictation_consent import tagged_workshop_id

    by_kind: dict[str, set[str]] = {}
    for kind, record_id in links:
        by_kind.setdefault(kind, set()).add(record_id)
    steps_of: dict[str, set[str]] = {}
    step_ids = by_kind.pop("processstep", set())
    if step_ids:
        for step in await db.processstep.find_many(where={"id": {"in": sorted(step_ids)}}):
            process_id = str(getattr(step, "processId", None) or "")
            if process_id:
                steps_of.setdefault(process_id, set()).add(str(step.id))
                by_kind.setdefault("process", set()).add(process_id)
    asked = [(kind, ids) for kind, ids in sorted(by_kind.items()) if kind in _FILED_RECORD_TABLES]
    answers = await gather_reads(
        *(
            getattr(db, _FILED_RECORD_TABLES[kind]).find_many(where={"id": {"in": sorted(ids)}})
            for kind, ids in asked
        )
    )
    filed: set[tuple[str, str]] = set()
    for (kind, _ids), rows in zip(asked, answers, strict=True):
        for row in rows:
            named = {str(getattr(row, "designWorkshopId", None) or "")}
            if kind == "media":
                named.add(tagged_workshop_id(row) or "")
            if named & workshop_ids:
                filed.add((kind, str(row.id)))
                filed.update(("processstep", step) for step in steps_of.get(str(row.id), ()))
    return filed


async def media_held_among(media_rows: Iterable[Any], workshop_ids: Iterable[str]) -> set[str]:
    """The ids of the files among ``media_rows`` that belong to any of ``workshop_ids`` — by any of
    the five ways :func:`media_design_workshop_ids` reads, in a fixed number of reads however many
    files there are.

    The two columns are answered from memory; the stage entries of those workshops, the live AI
    layers made from the files and the records the files hang off are each asked once, gathered.
    ``tests/test_admin_serve_as.py`` holds the answer equal to :func:`media_design_workshop_ids`
    file by file, over every way a file can belong.
    """
    from app.services.dictation_consent import tagged_workshop_id

    held = {str(workshop_id) for workshop_id in workshop_ids if workshop_id}
    rows = [row for row in media_rows if str(getattr(row, "id", "") or "")]
    if not held or not rows:
        return set()
    found: set[str] = set()
    owners: dict[tuple[str, str], set[str]] = {}
    for row in rows:
        media_id = str(row.id)
        named = {str(getattr(row, "designWorkshopId", None) or ""), tagged_workshop_id(row) or ""}
        if named & held:
            found.add(media_id)
            continue
        for column, kind in _TYPED_PARENTS.items():
            parent = str(getattr(row, column, None) or "").strip()
            if parent:
                owners.setdefault((kind, parent), set()).add(media_id)
        tag = str(getattr(row, "linkedRecordType", None) or "").strip().lower()
        tagged = str(getattr(row, "linkedRecordId", None) or "").strip()
        if tag and tagged:
            owners.setdefault((_LINK_SPELLINGS.get(tag, tag), tagged), set()).add(media_id)
    pending = sorted(str(row.id) for row in rows if str(row.id) not in found)
    if not pending:
        return found
    workshops = sorted(held)
    staged, layers, filed = await gather_reads(
        _staged_among(pending, workshops),
        db.dwailayer.find_many(
            where={
                "sourceMediaId": {"in": pending},
                "deletedAt": None,
                "designWorkshopId": {"in": workshops},
            }
        ),
        _links_filed_under(set(owners), held),
    )
    found |= staged
    found |= {str(layer.sourceMediaId) for layer in layers if layer.sourceMediaId}
    for link in filed:
        found |= owners.get(link, set())
    return found
