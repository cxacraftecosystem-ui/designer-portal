"""The rating ledger's RULES: who may see what, what a replay does, and how a round is ranked.

No database and no generated Prisma client. Almost everything asserted here is a pure function in
``app/services/design_ratings.py`` — :func:`access_for`, :func:`rating_plan`, :func:`rank`,
:func:`rating_payload` — which is why that module was written as plans and predicates over a flat
:class:`RatingSubject` rather than as coroutines that write. The round trip through Postgres and
HTTP is ``test_design_ratings_api``; this module is the matrix, and it runs everywhere including
CI, where there is no database at all.

THE ONE EXCEPTION IS the vanished-row test at the foot of this module, which drives :func:`apply_rating` through ``asyncio.run`` against a stub delegate. It is here and
not in the round-trip module because the case it covers — the ledger row disappearing under a
write — cannot be produced on demand against a real database, and the alternative was leaving a
500 where the whole surface answers 404.

WHAT IS PINNED HERE, and each of these is a way the feature could ship looking finished while
leaking or misbehaving.

**THE PERMISSION MATRIX IS EXHAUSTIVE OVER ITS OWN INPUT SPACE.** Every role the platform has,
crossed with membership, authorship OF THE ROW, ownership OF THE WORKSHOP, both rounds and both
states of the per-piece pool gate — 160 combinations — checked against invariants that are
STATEMENTS OF THE RULE rather than a second copy of the implementation. Alongside them sit named
scenarios with hand-written expectations, because an invariant suite alone would pass against a
function that returned False for everything.

The last two of those axes used to be one, and separating them is what a review cost: with
``workshop_author_id`` pinned in every case, no combination could tell "wrote this sketch" from
"runs the workshop it sits in", and the self-rating refusal was reading the second. Only an admin
may create a workshop, so the effect was that the admin who started one could not rate a single
piece in it. See :data:`AXES`.

**THE IDENTITY SWITCH IS ASSERTED IN BOTH POSITIONS.** ``POOL_RATINGS_NAME_THEIR_RATER`` is the one
open owner decision on this surface. The suite parametrises over both values, so whichever way the
owner settles it the tests state the consequence instead of quietly encoding today's default.

**A STALE DELIVERY IS NOT AN AMENDMENT.** ``DwReviewRating`` has no ``clientKey``, so the ordering
comes from the device clock — and the tunnel that restores a stale score if arrival order is
trusted is asserted directly, along with the two cases the clock rule deliberately cannot order.

**AND A REDELIVERY IS RECOGNISED THROUGH THE COLUMN'S OWN TRUNCATION**, which is the one thing in
this module that came from a failure rather than from a review. ``ratedAt`` is ``TIMESTAMP(3)`` and
the query engine drops the microseconds, so a stored clock is never equal to the value that wrote
it and an exact comparison makes every replay look like an amendment. The round-trip module caught
it the first time it was allowed to run; the two millisecond tests beside the tunnel one keep it
caught in milliseconds rather than in seven minutes.

**AND A RATING CANNOT BE WRITTEN INTO A STAGE ROW.** The guard is on the plan, so the refusal is
asserted directly rather than inferred from the absence of a call site.
"""

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.services.design_ratings import (
    MAX_DEVICE_CLOCK_SKEW,
    MAX_SCORE,
    MIN_SCORE,
    POOL_OPENS_WHEN_FIELD,
    RATING_DELEGATE,
    RATING_TABLE,
    Operation,
    RatingRound,
    RatingRuleViolation,
    RatingSubject,
    RatingSubjectGone,
    RatingWritePlan,
    access_for,
    is_own_record,
    is_row_author,
    pool_is_open,
    pool_visible,
    rank,
    ranked_payload,
    rating_payload,
    rating_plan,
    round_score,
    visible_rows,
)

#: Every role the platform has, not only the ones this feature admits. PROFESSOR is the one that
#: matters most: it sits ABOVE DESIGNER in the rank ladder, so any "this tier and above" spelling
#: of the eligibility rule lets a professor rate prototypes and the SET this code actually uses
#: does not. A test with only RESEARCHER in it passes against the wrong rule.
ALL_ROLES = ("MASTER_ADMIN", "ADMIN", "DESIGNER", "PROFESSOR", "RESEARCHER")

DESIGN_ROLES = ("MASTER_ADMIN", "ADMIN", "DESIGNER")
ADMIN_ROLES = ("MASTER_ADMIN", "ADMIN")

RATER = "usr-rater"
AUTHOR = "usr-author"
CREATOR = "usr-creator"

NOW = datetime(2026, 8, 20, 9, 0, tzinfo=UTC)


def user(role: str, user_id: str = RATER) -> SimpleNamespace:
    return SimpleNamespace(id=user_id, role=role)


def subject(
    *,
    author_id: str | None = AUTHOR,
    workshop_author_id: str | None = CREATOR,
    pool_open: bool = False,
    entry_id: str = "entry-1",
    ordinal: int = 0,
) -> RatingSubject:
    return RatingSubject(
        entry_id=entry_id,
        entity_key="prototype",
        workshop_id="wk-1",
        pool_open=pool_open,
        label="Kansa bowl",
        ordinal=ordinal,
        author_id=author_id,
        workshop_author_id=workshop_author_id,
    )


def ledger_row(
    *,
    rating_id: str = "rat-1",
    reviewer_id: str = RATER,
    score: int = 4,
    entry_id: str = "entry-1",
    rated_at: datetime | None = None,
    created: datetime | None = None,
) -> SimpleNamespace:
    return SimpleNamespace(
        id=rating_id,
        stageEntryId=entry_id,
        entityKey="prototype",
        designWorkshopId="wk-1",
        round="PEER",
        reviewerId=reviewer_id,
        score=score,
        comment="Rim is heavy",
        suggestion="Thin the rim by 2 mm",
        ratedAt=rated_at,
        createdAt=created or NOW,
        updatedAt=created or NOW,
    )


# ------------------------------------------------------------------------------------------
# The matrix, exhaustively:
#   role x membership x authorship of the row x ownership of the workshop x round x the pool gate
# ------------------------------------------------------------------------------------------


def matrix_cases():
    for role in ALL_ROLES:
        for member in (False, True):
            for own in (False, True):
                for runs_workshop in (False, True):
                    for round_ in (RatingRound.PEER, RatingRound.POOL):
                        for opened in (False, True):
                            yield role, member, own, runs_workshop, round_, opened


MATRIX = tuple(matrix_cases())

#: The names of the matrix's axes, so a parametrize list is one word rather than six repeated.
#:
#: ``runs_workshop`` IS A SIXTH AXIS AND IT WAS ADDED AFTER A REVIEW, because its absence hid a real
#: bug for a whole wave. Every case used to pin ``workshop_author_id=CREATOR``, so "own" and "wrote
#: this row" were the same fact in all 80 combinations — and the self-rating refusal, which read the
#: wider of the two, silently locked the workshop's own creator out of rating anything inside it.
#: Only an ADMIN may create a workshop, so that was every admin who had ever started one. Vary the
#: two independently or the invariants below cannot tell them apart.
AXES = ("role", "member", "own", "runs_workshop", "round_", "opened")


def access_of(role, member, own, runs_workshop, round_, opened):
    return access_for(
        subject(
            author_id=RATER if own else AUTHOR,
            workshop_author_id=RATER if runs_workshop else CREATOR,
            pool_open=opened,
        ),
        user(role),
        round_,
        is_member=member,
    )


@pytest.mark.parametrize(AXES, MATRIX)
def test_no_role_outside_the_design_workshop_set_is_given_anything(
    role, member, own, runs_workshop, round_, opened
):
    """A PROFESSOR outranks a DESIGNER and still may not touch this surface.

    ``can_run_design_workshops`` is a SET, not a rank threshold — see
    ``deps.DESIGN_WORKSHOP_ROLES`` — and this is the assertion that catches the day somebody
    "simplifies" it into ``role_rank(user) >= DESIGNER``. Membership and authorship are crossed in
    deliberately: a stale ``createdById`` on a row must not become a standing grant for an account
    whose role has since changed.
    """
    if role in DESIGN_ROLES:
        return
    access = access_of(role, member, own, runs_workshop, round_, opened)
    assert not access.in_round
    assert not access.may_rate
    assert not access.may_read_ledger
    assert not access.sees_rater_identity
    assert not access.visible


@pytest.mark.parametrize(AXES, MATRIX)
def test_the_ledger_is_exactly_admins_and_the_records_own_author(
    role, member, own, runs_workshop, round_, opened
):
    """The owner's sentence, as an invariant over the whole space.

    *"Admins and master admins see who rated what, when and how; designers see the same for their
    own records only."* So ``may_read_ledger`` must be TRUE for every admin and for every author of
    the record, FALSE for everybody else — and it must not depend on the round, on membership, or
    on whether the piece has been opened to the pool. A peer is not an auditor of their colleague's
    reviews, in either round.

    THE WORKSHOP'S CREATOR READS IT TOO, which is the second clause of ``is_own_record`` and is
    asserted here rather than only in its own scenario: a workshop is the unit the ministry funds
    and indexes under their name. That clause belongs to READING and to nothing else — see
    :func:`test_may_rate_never_exceeds_being_in_the_round`, which pins that it does not reach the
    self-rating refusal.
    """
    access = access_of(role, member, own, runs_workshop, round_, opened)
    if role not in DESIGN_ROLES:
        assert not access.may_read_ledger
        return
    assert access.may_read_ledger is (role in ADMIN_ROLES or own or runs_workshop)


@pytest.mark.parametrize(AXES, MATRIX)
def test_a_name_is_never_shown_to_somebody_who_may_not_read_the_ledger(
    role, member, own, runs_workshop, round_, opened
):
    """``sees_rater_identity`` implies ``may_read_ledger``, always and in every combination.

    The two are separate fields precisely so that identity can be withheld from somebody who may
    read the rows — the pool case. The implication must never run the other way: an account that
    cannot see the rows at all being marked as allowed to see the names is a redaction flag that
    would go live the moment a future endpoint returned rows without consulting :func:`visible_rows`
    first.
    """
    access = access_of(role, member, own, runs_workshop, round_, opened)
    assert not access.sees_rater_identity or access.may_read_ledger


@pytest.mark.parametrize(AXES, MATRIX)
def test_may_rate_never_exceeds_being_in_the_round(
    role, member, own, runs_workshop, round_, opened
):
    """Rating is a subset of round membership, and self-rating is refused inside it.

    Written as an invariant rather than a scenario because ``may_rate`` is the only field that
    WRITES anything, so an implementation that let it drift above ``in_round`` would put scores on
    a ranking from people who were never admitted to the round.

    **AND THE REFUSAL IS EXACTLY AUTHORSHIP OF THE ROW — NEITHER WIDER NOR NARROWER.** ``may_rate``
    is ``in_round`` minus the author, and nothing else may subtract from it. The wider spelling
    (``is_own_record``, which also counts whoever created the workshop) is what the first version of
    this module used, and because only an admin may create a workshop it meant the admin who ran the
    workshop was the single account that could never rate a piece in it — answered, worse, with "this
    is your own record" about a prototype another designer drew. Asserted in BOTH directions here:
    an author never rates, and everybody else in the round always does.
    """
    access = access_of(role, member, own, runs_workshop, round_, opened)
    assert not access.may_rate or access.in_round
    if access.is_author:
        assert not access.may_rate
    elif access.in_round:
        assert access.may_rate


@pytest.mark.parametrize(AXES, MATRIX)
def test_a_piece_still_in_peer_review_admits_nobody_new_to_the_pool(
    role, member, own, runs_workshop, round_, opened
):
    """The gate the owner asked for: *"the whole pool of designers once prototypes are finalised"*.

    While ``peerRoundClosedAt`` is blank on a piece, its POOL round must admit exactly the people
    its PEER round already admits — the workshop's own party and admins — and nobody else. An
    unfinished prototype shown to the whole country cannot be un-shown by closing the round
    afterwards, which is why this is asserted over the whole space rather than in one scenario.

    THE ROLE GATE COMES FIRST, and the first spelling of this test forgot it: a RESEARCHER or a
    PROFESSOR holding a viewer grant is a member of the workshop and still not somebody this
    surface admits, so "member" alone is not the expectation.
    """
    if round_ is not RatingRound.POOL or opened:
        return
    access = access_of(role, member, own, runs_workshop, round_, opened)
    admitted = role in DESIGN_ROLES and (member or role in ADMIN_ROLES)
    assert access.in_round is admitted


# ------------------------------------------------------------------------------------------
# The per-piece pool gate, read off the row
# ------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("data", "expected"),
    [
        ({POOL_OPENS_WHEN_FIELD: "2026-08-01"}, True),
        ({POOL_OPENS_WHEN_FIELD: ""}, False),
        ({POOL_OPENS_WHEN_FIELD: "   "}, False),
        ({POOL_OPENS_WHEN_FIELD: None}, False),
        ({}, False),
        (None, False),
        ("not a mapping", False),
    ],
)
def test_the_pool_opens_only_on_a_non_empty_date(data, expected):
    """It FAILS CLOSED, and that direction is the whole argument.

    A value this cannot read means the peer round is still running: a designer who disagrees fills
    the field in and the round opens. The opposite failure publishes an unfinished prototype to
    every designer in the country and cannot be undone.
    """
    assert pool_is_open(data) is expected


def test_a_sketch_is_gated_by_its_own_date_exactly_as_a_prototype_is():
    """One gate, read off whichever row it was handed. There is no per-entity branch to test.

    ``peerRoundClosedAt`` is declared on ``sketch`` as well as ``prototype``, so a sketch nobody has
    dated is closed for the ordinary reason and a sketch somebody HAS dated is open — the same two
    answers, from the same function, with no knowledge of the entity anywhere in it.

    THIS TEST USED TO ASSERT THE OPPOSITE, and the difference is worth stating: the field was once
    on ``prototype`` alone, so level 2 could never open on a sketch at all. That read as a decision
    but was an omission — ``sketchReview.reviewRound`` and ``RATEABLE_ENTITIES`` both already
    assumed a sketch had a second round. The conservative half survives unchanged, and it is the
    first assertion here: a sketch with no date reaches nobody. ``test_design_ratings_api`` pins the
    registry half, and now pins it as an EQUALITY against the rateable set rather than a single
    entity name.
    """
    assert not pool_is_open({"sketchNo": "S1", "name": "Rim study"})
    assert pool_is_open({
        "sketchNo": "S2", "name": "Lamp elevation", POOL_OPENS_WHEN_FIELD: "2026-08-12",
    })


def test_the_pool_list_hides_unopened_pieces_from_a_stranger_and_not_from_the_room():
    """:func:`pool_visible`. A member's own ranking must not disagree with their own stage list.

    A workshop's designers already see every prototype on every other screen; narrowing the list
    for them would produce a review tab that silently disagrees with the stage beside it. For a
    stranger the narrowing IS the gate.
    """
    pieces = [
        subject(entry_id="done", pool_open=True),
        subject(entry_id="wip", pool_open=False),
    ]
    stranger = pool_visible(pieces, is_member=False, admin=False)
    assert [p.entry_id for p in stranger] == ["done"]
    assert len(pool_visible(pieces, is_member=True, admin=False)) == 2
    assert len(pool_visible(pieces, is_member=False, admin=True)) == 2


# ------------------------------------------------------------------------------------------
# Named scenarios, with expectations written out by hand
# ------------------------------------------------------------------------------------------


def test_a_workshop_peer_rates_and_sees_the_score_but_not_the_scorers():
    """The ordinary case, and the one the whole redaction layer exists for.

    A co-designer with a viewer grant, rating a colleague's prototype among their peers: they are
    in the round, they may rate, and they may NOT read who else rated it. That last clause is the
    difference between a review tab and a leaderboard of colleagues' opinions of each other.
    """
    access = access_for(subject(), user("DESIGNER"), RatingRound.PEER, is_member=True)
    assert access.in_round
    assert access.may_rate
    assert not access.may_read_ledger
    assert not access.sees_rater_identity
    assert access.visible


def test_the_author_of_a_sketch_sees_its_ledger_even_after_losing_the_workshop():
    """Authorship is a property of the ROW, not of current workshop access.

    A designer whose viewer grant has been removed — a season ended, a handover happened — still
    drew the piece and is still the person the reviews are about. They are not in the peer round
    any more (they cannot rate), and their own record is emphatically not "not found" to them.
    """
    access = access_for(
        subject(author_id=RATER), user("DESIGNER"), RatingRound.PEER, is_member=False
    )
    assert not access.in_round
    assert not access.may_rate
    assert access.may_read_ledger
    assert access.visible


def test_the_workshop_creator_reads_the_ledger_of_the_rows_inside_it():
    """The second clause of :func:`is_own_record`, asserted on its own — and it is a READING rule.

    A workshop is the unit the ministry funds and indexes under its creator's name. A creator who
    could not read the reviews of work filed inside their own workshop would have to ask an admin
    for a report on their own project.

    IT IS NOT A CLAIM OF AUTHORSHIP, which is what the next test pins. This clause says the creator
    may SEE; it must never be read as "the creator made this", because the only thing that follows
    from making something here is being refused a vote on it.
    """
    row = subject(author_id="somebody-else", workshop_author_id=RATER)
    assert is_own_record(row, user("DESIGNER"))
    assert access_for(row, user("DESIGNER"), RatingRound.PEER, is_member=True).may_read_ledger


def test_running_a_workshop_is_not_authorship_and_does_not_cost_the_creator_their_vote():
    """THE BUG A REVIEW FOUND, pinned in the place it could have been caught.

    ``is_own_record`` admits the workshop's creator — correctly, for READING a ledger. Feeding that
    same predicate into the self-rating refusal meant the creator could not rate anything inside
    their own workshop, and since ``deps.can_create_design_workshops`` admits only ADMIN and
    MASTER_ADMIN, "the creator" is always an admin. So the person who runs a workshop was the one
    account refused a vote in it, and the 403 told them a prototype another designer drew was
    "your own record".

    The two predicates are asserted apart here: the creator READS the ledger and RATES the piece;
    the designer who actually entered it reads and does not rate.
    """
    row = subject(author_id=AUTHOR, workshop_author_id=RATER)
    creator = user("ADMIN")

    assert is_own_record(row, creator)
    assert not is_row_author(row, creator)

    access = access_for(row, creator, RatingRound.PEER, is_member=True)
    assert access.in_round
    assert access.may_rate, "the workshop's creator did not draw this piece and may rate it"
    assert access.may_read_ledger
    assert access.is_own_record
    assert not access.is_author

    # The row's actual author, for contrast: the same two reads, the opposite write.
    drew_it = access_for(row, user("DESIGNER", AUTHOR), RatingRound.PEER, is_member=True)
    assert drew_it.is_author
    assert not drew_it.may_rate
    assert drew_it.may_read_ledger


def test_is_row_author_reads_the_row_alone_and_never_the_workshop():
    """The narrow predicate, on its own, including the null case both clauses have to survive."""
    assert is_row_author(subject(author_id=RATER), user("DESIGNER"))
    assert not is_row_author(subject(author_id=AUTHOR, workshop_author_id=RATER), user("DESIGNER"))
    assert not is_row_author(subject(author_id=None), user("DESIGNER"))
    assert not is_row_author(
        subject(author_id=None), SimpleNamespace(id=None, role="DESIGNER")
    )


def test_a_null_author_is_nobodys_own_record():
    """Rows written before ``createdById`` was populated carry None on both clauses.

    ``None == None`` would otherwise make every such row everybody's own record for any caller
    whose id was also missing — which is exactly the shape a half-built user object has.
    """
    orphan = subject(author_id=None, workshop_author_id=None)
    assert not is_own_record(orphan, user("DESIGNER"))
    assert not is_own_record(orphan, SimpleNamespace(id=None, role="DESIGNER"))


def test_an_unrelated_designer_reaches_a_finished_pieces_pool_round_and_nothing_else():
    """Level 2, in one assertion: the wider audience, and only the wider audience.

    They may rate and see the aggregate; they may not read the ledger, and they were never given
    anything on the PEER round of the same piece. The second half is what stops the pool from
    becoming a back door into a workshop's internal review.
    """
    piece = subject(pool_open=True)
    stranger = user("DESIGNER")
    pool = access_for(piece, stranger, RatingRound.POOL, is_member=False)
    peer = access_for(piece, stranger, RatingRound.PEER, is_member=False)
    assert pool.in_round and pool.may_rate and not pool.may_read_ledger
    assert not peer.in_round and not peer.visible


def test_one_finished_prototype_does_not_open_its_unfinished_neighbours():
    """The reason the gate is per piece, asserted as the registry field's own note states it:
    *"A workshop-level flag would open the pool round on nine unfinished prototypes the day the
    tenth was done."*"""
    stranger = user("DESIGNER")
    done = access_for(subject(pool_open=True), stranger, RatingRound.POOL, is_member=False)
    wip = access_for(subject(pool_open=False), stranger, RatingRound.POOL, is_member=False)
    assert done.in_round
    assert not wip.in_round


def test_a_designer_may_not_rate_their_own_prototype():
    """``SELF_RATING_IS_REFUSED``. A self-awarded five is not peer review.

    They keep everything else — the record is visible and its ledger is theirs to read — which is
    what makes this a refusal to WRITE rather than a refusal to see.
    """
    access = access_for(
        subject(author_id=RATER, pool_open=True), user("DESIGNER"), RatingRound.POOL,
        is_member=True,
    )
    assert not access.may_rate
    assert access.visible
    assert access.may_read_ledger


@pytest.mark.parametrize("role", ADMIN_ROLES)
@pytest.mark.parametrize("round_", [RatingRound.PEER, RatingRound.POOL])
@pytest.mark.parametrize("switch", [False, True])
def test_an_admin_sees_the_names_in_both_rounds_whatever_the_switch_says(
    monkeypatch, role, round_, switch
):
    """*"Admins and master admins see who rated what, when and how"* — unconditionally.

    ``POOL_RATINGS_NAME_THEIR_RATER`` is the DESIGNER's question and must never reach the admin
    branch. Parametrised over both of its values so that flipping it cannot silently blind the
    audit view, which is the one view that exists to answer "who rated this".
    """
    monkeypatch.setattr(
        "app.services.design_ratings.POOL_RATINGS_NAME_THEIR_RATER", switch
    )
    access = access_for(subject(pool_open=True), user(role), round_, is_member=False)
    assert access.may_read_ledger
    assert access.sees_rater_identity


@pytest.mark.parametrize("switch", [False, True])
def test_the_owner_switch_decides_only_the_pool_round_for_the_records_own_author(
    monkeypatch, switch
):
    """THE ONE OPEN OWNER DECISION, asserted in both positions.

    PEER always names the rater to the designer whose record it is — peers share a room and a
    suggestion signed by nobody cannot be discussed. POOL follows the switch. If the owner settles
    it the other way, ``design_ratings.POOL_RATINGS_NAME_THEIR_RATER`` is the whole change and this
    test already states the consequence.
    """
    monkeypatch.setattr(
        "app.services.design_ratings.POOL_RATINGS_NAME_THEIR_RATER", switch
    )
    mine = subject(author_id=RATER, pool_open=True)
    peer = access_for(mine, user("DESIGNER"), RatingRound.PEER, is_member=True)
    pool = access_for(mine, user("DESIGNER"), RatingRound.POOL, is_member=True)
    assert peer.sees_rater_identity
    assert pool.sees_rater_identity is switch


# ------------------------------------------------------------------------------------------
# Redaction: which rows, and how much of a row
# ------------------------------------------------------------------------------------------


def test_a_peer_gets_only_their_own_row_out_of_the_ledger():
    """:func:`visible_rows` is the first of the two redaction layers: WHICH rows.

    A peer who may not read the ledger still needs their own row back — the amend flow has nothing
    to show a designer otherwise, and the client would end up holding the only copy.
    """
    access = access_for(subject(), user("DESIGNER"), RatingRound.PEER, is_member=True)
    rows = [
        ledger_row(rating_id="mine", reviewer_id=RATER),
        ledger_row(rating_id="theirs", reviewer_id="usr-other"),
    ]
    shown = visible_rows(rows, viewer_id=RATER, access=access)
    assert [row.id for row in shown] == ["mine"]


def test_a_row_a_peer_may_see_carries_no_other_persons_name():
    """:func:`rating_payload` is the second layer: HOW MUCH of a row.

    The name is ABSENT rather than null, so no client can render a blank byline where a person
    should be, and a future endpoint that forgets to redact cannot pass a name through by accident.
    The caller's own row is exempt: they wrote it.
    """
    access = access_for(subject(), user("DESIGNER"), RatingRound.PEER, is_member=True)
    theirs = rating_payload(
        ledger_row(reviewer_id="usr-other"), viewer_id=RATER, access=access
    )
    mine = rating_payload(ledger_row(reviewer_id=RATER), viewer_id=RATER, access=access)

    assert "reviewerId" not in theirs
    assert theirs["score"] == 4
    assert theirs["mine"] is False

    assert mine["reviewerId"] == RATER
    assert mine["mine"] is True


def test_both_clocks_travel_on_every_row():
    """"When" is ambiguous between the courtyard and the sync, so both are sent.

    Sending one would decide it silently, and on this fleet they can be a fortnight apart —
    ``DwAiLayer.producedAt`` versus ``createdAt``, and ``DwWorkshopConsentDecision.recordedAt``
    versus ``createdAt``, for the same reason.
    """
    access = access_for(subject(), user("ADMIN"), RatingRound.PEER, is_member=False)
    row = ledger_row(rated_at=datetime(2026, 8, 6, 11, 30, tzinfo=UTC))
    payload = rating_payload(row, viewer_id="usr-admin", access=access)
    assert payload["ratedAt"] == "2026-08-06T11:30:00+00:00"
    assert payload["createdAt"] == "2026-08-20T09:00:00+00:00"


# ------------------------------------------------------------------------------------------
# The write plan: the table guard, the bounds, and the stale delivery
# ------------------------------------------------------------------------------------------


def test_a_rating_cannot_be_planned_into_a_stage_row():
    """The guard that makes "a rating is not a stage field" true by construction.

    A later change that wants ratings inside ``DwStageEntry.data`` has to delete this check, which
    is a visible act in a diff and a red test rather than a quiet new call site.
    """
    with pytest.raises(RatingRuleViolation) as raised:
        RatingWritePlan(
            table="DwStageEntry", operation=Operation.CREATE, data={"score": 5}
        )
    assert "DwStageEntry" in str(raised.value)
    assert RATING_TABLE in str(raised.value)


def test_an_update_must_name_the_single_row_it_changes():
    with pytest.raises(RatingRuleViolation):
        RatingWritePlan(table=RATING_TABLE, operation=Operation.UPDATE, data={"score": 5})
    with pytest.raises(RatingRuleViolation):
        RatingWritePlan(
            table=RATING_TABLE,
            operation=Operation.CREATE,
            data={"score": 5},
            where={"id": "rat-1"},
        )


def _plan(**overrides):
    args = {
        "subject": subject(),
        "round_": RatingRound.PEER,
        "reviewer_id": RATER,
        "score": 4,
        "comment": "Rim is heavy",
        "suggestion": None,
        "at": NOW,
        "rated_at": None,
        "existing": None,
    }
    args.update(overrides)
    return rating_plan(**args)


def test_a_first_rating_is_a_create_that_carries_the_denormalised_columns():
    outcome = _plan()
    assert outcome.replayed is False
    assert outcome.plan is not None
    assert outcome.plan.operation is Operation.CREATE
    assert outcome.plan.table == RATING_TABLE
    # The three denormalised columns exist so a round's ledger is one indexed read; a create that
    # omitted any of them would leave rows that the ranking query simply never finds.
    assert outcome.plan.data["designWorkshopId"] == "wk-1"
    assert outcome.plan.data["entityKey"] == "prototype"
    assert outcome.plan.data["round"] == "PEER"
    assert outcome.plan.data["reviewerId"] == RATER
    # Never client-settable: both are database clocks, and a client that could set them could date
    # a judgement to whenever it liked.
    assert "createdAt" not in outcome.plan.data
    assert "updatedAt" not in outcome.plan.data


def test_the_same_capture_arriving_twice_writes_nothing():
    """THE OFFLINE CASE. The outbox delivered the same capture again; the server already has it.

    A no-op that answers with the stored row, not an error: the device did the right thing, and a
    409 here would paint a red line on a phone for a rating that is safely recorded.
    """
    moment = NOW - timedelta(days=3)
    stored = ledger_row(rated_at=moment, score=4)
    outcome = _plan(existing=stored, score=4, rated_at=moment)
    assert outcome.replayed is True
    assert outcome.plan is None
    assert outcome.existing_id == "rat-1"


def test_a_redelivery_is_a_replay_even_though_the_ledger_truncated_the_clock_it_stored():
    """THE MILLISECOND — the defect the round-trip test caught the first time it could run.

    ``DwReviewRating.ratedAt`` is ``TIMESTAMP(3)`` and the Prisma query engine truncates a datetime
    to milliseconds before Postgres sees it, so the stored clock is NEVER the value that wrote it:
    a capture sent as ``…451879`` reads back as ``…451000``. Compared exactly, the same capture
    redelivered is therefore strictly NEWER than the row it created — ``rating_plan`` calls it an
    amendment, the row is rewritten, ``updatedAt`` moves, and the device is told ``replayed: false``
    for a rating the server already holds. The unique index keeps the outbox to one ROW; nothing but
    this comparison keeps it to one JUDGEMENT.

    Written against the pure planner rather than only end to end, because the round trip takes
    seven minutes and this is the assertion that fails in milliseconds when somebody restores the
    exact comparison. See ``design_ratings.LEDGER_CLOCK_RESOLUTION``, and the migration's own note
    on the column for why widening it to ``TIMESTAMP(6)`` does not help.
    """
    captured = (NOW - timedelta(days=3)).replace(microsecond=451879)
    stored = ledger_row(rated_at=captured.replace(microsecond=451000), score=4)
    outcome = _plan(existing=stored, score=4, rated_at=captured)
    assert outcome.replayed is True, "the second delivery of one capture must write nothing"
    assert outcome.plan is None
    assert outcome.existing_id == "rat-1"


def test_an_amendment_a_millisecond_later_is_still_an_amendment():
    """The other edge of the same rule, so the tolerance cannot quietly grow.

    A millisecond is the resolution the column keeps, so a capture one millisecond newer is a
    different moment and must be applied. Anything coarser — rounding to the second, say — would
    start swallowing real amendments, and this is the test that would fail.
    """
    stored = ledger_row(rated_at=(NOW - timedelta(days=3)).replace(microsecond=451000), score=4)
    outcome = _plan(
        existing=stored,
        score=2,
        rated_at=(NOW - timedelta(days=3)).replace(microsecond=452000),
    )
    assert outcome.replayed is False
    assert outcome.plan is not None
    assert outcome.plan.operation is Operation.UPDATE


def test_a_replay_carrying_a_stale_score_does_not_overwrite_the_amendment():
    """THE TUNNEL. The bug that arrives if arrival order is trusted.

    A designer rates a prototype 5, amends it to 3, then drives through a tunnel and the queued
    ORIGINAL is delivered. A plain upsert makes that an UPDATE and silently restores the 5. Because
    the delivery carries the device clock of the capture it belongs to, it is recognised as older
    than what the server holds and dropped instead.
    """
    original = NOW - timedelta(days=3)
    amendment = NOW - timedelta(days=2)
    amended = ledger_row(rated_at=amendment, score=3)
    late = _plan(existing=amended, score=5, rated_at=original)
    assert late.plan is None
    assert late.replayed is True


def test_a_newer_device_clock_is_a_genuine_amendment():
    stored = ledger_row(rated_at=NOW - timedelta(days=3), score=4)
    outcome = _plan(existing=stored, score=2, rated_at=NOW - timedelta(days=1))
    assert outcome.replayed is False
    assert outcome.plan is not None
    assert outcome.plan.operation is Operation.UPDATE
    assert outcome.plan.where == {"id": "rat-1"}
    assert outcome.plan.data["score"] == 2
    # An amendment never rewrites which subject, round or reviewer the row belongs to: those are
    # the unique key, and a plan that carried them could move a rating onto another prototype.
    for immutable in ("stageEntryId", "round", "reviewerId", "designWorkshopId"):
        assert immutable not in outcome.plan.data


def test_a_rating_typed_against_the_server_is_always_applied():
    """One of the two cases the clock rule deliberately cannot order, asserted so it is not a
    surprise.

    A delivery with no ``ratedAt`` has no courtyard moment to compare — the person is typing it
    into the server as it happens — so it wins. The same holds when the STORED row is the one
    without a clock. Both are documented on ``design_ratings.rating_plan`` as the honest limit of
    what a device clock can buy in place of a per-capture token.
    """
    stored = ledger_row(rated_at=NOW - timedelta(days=1), score=4)
    assert _plan(existing=stored, score=1, rated_at=None).plan is not None

    clockless = ledger_row(rated_at=None, score=4)
    assert _plan(existing=clockless, score=1, rated_at=NOW - timedelta(days=9)).plan is not None


def test_an_amendment_with_no_device_clock_leaves_the_stored_one_alone():
    """APPLIED, BUT NOT ERASING. A designer amending a score from a desk sends no ``ratedAt``.

    The first version of this module put ``"ratedAt": None`` in the UPDATE body regardless, which
    wrote NULL over the courtyard moment — deleting the only record of when the judgement was
    actually made, and contradicting ``rating_payload``'s own "BOTH CLOCKS, ALWAYS". So the key is
    absent from the plan rather than present and empty; a CREATE still carries it, because there
    ``None`` is the honest answer.
    """
    stored = ledger_row(rated_at=NOW - timedelta(days=1), score=5)
    amendment = _plan(existing=stored, score=3, rated_at=None)
    assert amendment.plan is not None
    assert amendment.plan.operation is Operation.UPDATE
    assert amendment.plan.data["score"] == 3
    assert "ratedAt" not in amendment.plan.data, amendment.plan.data

    # A CREATE is the other case and keeps it: there is no stored moment to protect, and None on a
    # new row genuinely means "typed against the server, no courtyard moment".
    created = _plan(existing=None, rated_at=None)
    assert created.plan is not None
    assert created.plan.operation is Operation.CREATE
    assert created.plan.data["ratedAt"] is None


def test_a_clockless_amendment_does_not_disarm_the_tunnel_rule_for_the_next_delivery():
    """THE CONSEQUENCE OF THE ABOVE, and the reason it is a blocking bug rather than a lost field.

    ``_is_stale_delivery`` needs a clock on the STORED row to recognise a stale one. Null that
    column out during an amendment and every later delivery looks fresh — so the queued original
    arriving out of a tunnel is applied as an UPDATE and the amended 3 becomes a 5 again, silently,
    which is the single failure this whole module exists to prevent.

    Written as the sequence a phone actually produces: capture 5 in the courtyard, amend to 3 at a
    desk with no device clock, then let the original arrive late.
    """
    courtyard = NOW - timedelta(days=3)
    stored = ledger_row(rated_at=courtyard, score=5)

    amendment = _plan(existing=stored, score=3, rated_at=None)
    assert amendment.plan is not None
    # The row as Postgres holds it AFTER that amendment: the score moved, the clock did not, because
    # the plan did not name it. Applying the plan by hand is the point — a fixture asserting the
    # clock survived would be asserting the fixture.
    after = ledger_row(rated_at=courtyard, score=3)
    for key, value in amendment.plan.data.items():
        setattr(after, key, value)
    assert after.score == 3
    assert after.ratedAt == courtyard

    late = _plan(existing=after, score=5, rated_at=courtyard)
    assert late.replayed is True, "the queued original must not overwrite the amendment"
    assert late.plan is None


@pytest.mark.parametrize("score", [MIN_SCORE - 1, MAX_SCORE + 1, 0, 99, -3])
def test_a_score_outside_the_scale_is_refused(score):
    with pytest.raises(RatingRuleViolation):
        _plan(score=score)


def test_a_boolean_is_not_a_score():
    """``True`` is an ``int`` in Python, and ``1 <= True <= 5`` is true.

    A client that posted ``{"score": true}`` would otherwise store a 1 and call it a judgement.
    """
    with pytest.raises(RatingRuleViolation):
        _plan(score=True)


def test_a_rating_with_no_reviewer_is_refused():
    with pytest.raises(RatingRuleViolation):
        _plan(reviewer_id="")


def test_a_device_clock_in_the_future_is_refused_rather_than_corrected():
    """A judgement dated to next week is not a judgement anybody made.

    Refused rather than silently rewritten to "now", because rewriting it is the fabrication the
    two-clock split exists to prevent. It also matters more here than beside a consent: a future
    clock would make every later amendment look stale and be dropped.
    """
    with pytest.raises(RatingRuleViolation):
        _plan(rated_at=NOW + MAX_DEVICE_CLOCK_SKEW + timedelta(minutes=1))


def test_a_clock_inside_the_tolerated_skew_is_kept_verbatim():
    moment = NOW + MAX_DEVICE_CLOCK_SKEW - timedelta(minutes=1)
    outcome = _plan(rated_at=moment)
    assert outcome.plan is not None
    assert outcome.plan.data["ratedAt"] == moment


def test_a_naive_device_clock_is_read_as_utc_rather_than_crashing():
    """Both clients send an offset; a hand-rolled request or an older build may not.

    Comparing a naive datetime with an aware one raises ``TypeError`` in Python, so without the
    normalisation the skew check and the staleness check would both 500 instead of answering. The
    missing tzinfo IS the test, so the DTZ001 suppression below is the assertion rather than a lint
    concession.
    """
    outcome = _plan(rated_at=datetime(2026, 8, 19, 9, 0))  # noqa: DTZ001
    assert outcome.plan is not None

    stored = ledger_row(rated_at=datetime(2026, 8, 19, 9, 0))  # noqa: DTZ001
    assert _plan(existing=stored, rated_at=datetime(2026, 8, 18, 9, 0)).plan is None  # noqa: DTZ001


# ------------------------------------------------------------------------------------------
# The ranking
# ------------------------------------------------------------------------------------------


def test_the_default_order_is_by_score_and_the_placed_order_is_the_designers():
    """THE WHOLE FEATURE IS THE GAP BETWEEN THE TWO POSITIONS.

    *"sorted by score by default, with the designer having the final say"*. Three prototypes whose
    scores disagree with the arrangement a designer dragged them into: the list comes back in the
    designer's order, and every row states where the scores would have put it.
    """
    subjects = [
        subject(entry_id="a", ordinal=0),
        subject(entry_id="b", ordinal=1),
        subject(entry_id="c", ordinal=2),
    ]
    rows = [
        ledger_row(rating_id="r1", entry_id="a", score=2, reviewer_id="u1"),
        ledger_row(rating_id="r2", entry_id="b", score=5, reviewer_id="u1"),
        ledger_row(rating_id="r3", entry_id="c", score=4, reviewer_id="u1"),
    ]
    ranked = rank(subjects, rows)
    assert [r.subject.entry_id for r in ranked] == ["a", "b", "c"]
    assert {r.subject.entry_id: r.placed_position for r in ranked} == {"a": 1, "b": 2, "c": 3}
    assert {r.subject.entry_id: r.default_position for r in ranked} == {"b": 1, "c": 2, "a": 3}


def test_the_average_is_the_plain_mean_of_the_rows_given():
    """Unweighted, because it is the only aggregate a designer can check against the numbers on
    their own screen — and a ranking somebody cannot verify is a ranking they will not trust."""
    subjects = [subject(entry_id="a")]
    rows = [
        ledger_row(rating_id="r1", entry_id="a", score=5, reviewer_id="u1"),
        ledger_row(rating_id="r2", entry_id="a", score=2, reviewer_id="u2"),
    ]
    ranked = rank(subjects, rows)[0]
    assert ranked.score == pytest.approx(3.5)
    assert ranked.count == 2


def test_an_unrated_prototype_is_ranked_last_and_not_dropped():
    """``None`` is not zero: nobody has judged it badly, nobody has judged it.

    It still appears — a list that hid the pieces most in need of a review would be the opposite of
    what a review tab is for — and it sorts BELOW a piece somebody scored 1.
    """
    subjects = [subject(entry_id="rated", ordinal=0), subject(entry_id="blank", ordinal=1)]
    rows = [ledger_row(rating_id="r1", entry_id="rated", score=1, reviewer_id="u1")]
    ranked = {r.subject.entry_id: r for r in rank(subjects, rows)}
    assert ranked["blank"].score is None
    assert ranked["blank"].count == 0
    assert ranked["rated"].default_position == 1
    assert ranked["blank"].default_position == 2


def test_ties_are_broken_deterministically_and_not_by_scan_order():
    """Two pieces on the same average must not swap places between two reads of unchanged data.

    With a list that gets CUT for display, an unstable tiebreak is what decides which sketch a
    designer never sees — the trap ``test_design_workshop_viewers`` documents for the eligible-viewer
    picker. The order is fixed by sample size, then the placed order, then the id, so the answer is
    the same whichever way the rows arrive.
    """
    subjects = [subject(entry_id="x", ordinal=5), subject(entry_id="y", ordinal=1)]
    rows = [
        ledger_row(rating_id="r1", entry_id="x", score=4, reviewer_id="u1"),
        ledger_row(rating_id="r2", entry_id="y", score=4, reviewer_id="u1"),
        ledger_row(rating_id="r3", entry_id="y", score=4, reviewer_id="u2"),
    ]
    forward = {r.subject.entry_id: r.default_position for r in rank(subjects, rows)}
    backward = {
        r.subject.entry_id: r.default_position
        for r in rank(list(reversed(subjects)), list(reversed(rows)))
    }
    assert forward == backward
    # y is rated by two people and x by one: same average, larger sample first.
    assert forward == {"y": 1, "x": 2}


def test_a_rating_of_another_subject_never_contributes_to_this_ones_score():
    """The aggregate is grouped by ``stageEntryId``, and the round query is already filtered.

    Asserted anyway, because a grouping keyed by the wrong column produces a plausible-looking
    ranking that is simply somebody else's scores.
    """
    subjects = [subject(entry_id="a")]
    rows = [
        ledger_row(rating_id="r1", entry_id="a", score=5, reviewer_id="u1"),
        ledger_row(rating_id="r2", entry_id="elsewhere", score=1, reviewer_id="u1"),
    ]
    assert rank(subjects, rows)[0].score == pytest.approx(5.0)


# ------------------------------------------------------------------------------------------
# The payload the ranked list sends, and the two refusals the write path can still hit
# ------------------------------------------------------------------------------------------


def test_the_raw_ordinal_travels_only_to_the_people_who_see_the_whole_collection():
    """``placedPosition`` is a position within what the caller was GIVEN; ``ordinal`` is not.

    The pool narrowing happens before the ranking precisely so a stranger is not handed "placed 3
    of 3" for the one prototype they may know about. Sending the raw ``DwStageEntry.ordinal``
    beside it gives the same count away by the back door: a pool reader shown one opened piece at
    ordinal 7 has learned the workshop holds at least eight it may not open. So the key is absent
    for them and present for the workshop's own party and admins.
    """
    ranked = rank([subject(entry_id="a", ordinal=7)], [])[0]

    stranger = ranked_payload(ranked, mine=None, show_ordinal=False)
    assert "ordinal" not in stranger, stranger
    # They still get everything a client needs to draw the list.
    assert stranger["placedPosition"] == 1
    assert stranger["defaultPosition"] == 1

    insider = ranked_payload(ranked, mine=None, show_ordinal=True)
    assert insider["ordinal"] == 7


def test_both_routes_round_the_average_through_the_same_helper():
    """One precision for one piece of work, whichever request asked for it.

    ``ranked_payload`` used to round inline while the ledger route called a helper of its own whose
    docstring claimed both routes used it — two sites, which is the drift the docstring said it
    prevented. Asserted as an equality between the two outputs rather than against a literal, so it
    keeps holding if ``SCORE_DECIMALS`` is ever changed.
    """
    rows = [
        ledger_row(rating_id="r1", entry_id="a", score=5, reviewer_id="u1"),
        ledger_row(rating_id="r2", entry_id="a", score=4, reviewer_id="u2"),
        ledger_row(rating_id="r3", entry_id="a", score=4, reviewer_id="u3"),
    ]
    ranked = rank([subject(entry_id="a")], rows)[0]
    assert ranked.score == pytest.approx(13 / 3)
    assert ranked_payload(ranked, mine=None, show_ordinal=True)["score"] == round_score(
        ranked.score
    )
    assert round_score(None) is None


def test_a_row_that_vanished_between_the_read_and_the_write_is_a_refusal_and_not_a_crash():
    """``update`` and ``find_unique`` answer None when the row is gone, and it CAN be gone.

    :func:`existing_rating` ran a network round trip earlier, and deleting the sketch or its
    workshop cascades onto the ledger. Handing that None back reaches ``rating_payload``, whose
    first line reads an attribute off it — so the designer would get a 500 traceback on the one
    surface where every other unreachable record answers "Record not found". Both branches are
    driven here against a delegate that returns None, which no real database will do on demand.
    """

    class _Vanishing:
        async def update(self, **_kwargs):
            return None

        async def find_unique(self, **_kwargs):
            return None

    delegate = _Vanishing()

    def run(coro):
        return asyncio.run(coro)

    stored = ledger_row(rated_at=NOW - timedelta(days=2), score=5)
    amendment = _plan(existing=stored, score=3, rated_at=NOW - timedelta(days=1))
    assert amendment.plan is not None and amendment.plan.operation is Operation.UPDATE

    with pytest.raises(RatingSubjectGone):
        run(_apply_against(delegate, amendment))

    replay = _plan(existing=stored, score=5, rated_at=NOW - timedelta(days=2))
    assert replay.plan is None and replay.replayed is True
    with pytest.raises(RatingSubjectGone):
        run(_apply_against(delegate, replay))

    # It is a RatingRuleViolation as well, so nothing that already catches the parent stops
    # catching it — the route's own 422 arm is what that protects.
    assert issubclass(RatingSubjectGone, RatingRuleViolation)


async def _apply_against(delegate, outcome):
    """Run :func:`apply_rating` with the module's delegate swapped for a stub.

    Patched on ``design_ratings.db`` rather than on ``_ledger`` itself, so the real lookup — the
    ``getattr(db, RATING_DELEGATE)`` that raises :class:`RatingLedgerUnavailable` when the client
    has no such model — is the code under test here too.
    """
    import app.services.design_ratings as module

    original = module.db
    module.db = SimpleNamespace(**{RATING_DELEGATE: delegate})
    try:
        return await module.apply_rating(outcome)
    finally:
        module.db = original
