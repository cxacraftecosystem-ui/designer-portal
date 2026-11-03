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



def matrix_cases():
    for role in ALL_ROLES:
        for member in (False, True):
            for own in (False, True):
                for runs_workshop in (False, True):
                    for round_ in (RatingRound.PEER, RatingRound.POOL):
                        for opened in (False, True):
                            yield role, member, own, runs_workshop, round_, opened


MATRIX = tuple(matrix_cases())

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
    access = access_of(role, member, own, runs_workshop, round_, opened)
    if role not in DESIGN_ROLES:
        assert not access.may_read_ledger
        return
    assert access.may_read_ledger is (role in ADMIN_ROLES or own or runs_workshop)


@pytest.mark.parametrize(AXES, MATRIX)
def test_a_name_is_never_shown_to_somebody_who_may_not_read_the_ledger(
    role, member, own, runs_workshop, round_, opened
):
    access = access_of(role, member, own, runs_workshop, round_, opened)
    assert not access.sees_rater_identity or access.may_read_ledger


@pytest.mark.parametrize(AXES, MATRIX)
def test_may_rate_never_exceeds_being_in_the_round(
    role, member, own, runs_workshop, round_, opened
):
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
    if round_ is not RatingRound.POOL or opened:
        return
    access = access_of(role, member, own, runs_workshop, round_, opened)
    admitted = role in DESIGN_ROLES and (member or role in ADMIN_ROLES)
    assert access.in_round is admitted



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
    assert pool_is_open(data) is expected


def test_a_sketch_is_gated_by_its_own_date_exactly_as_a_prototype_is():
    assert not pool_is_open({"sketchNo": "S1", "name": "Rim study"})
    assert pool_is_open({
        "sketchNo": "S2", "name": "Lamp elevation", POOL_OPENS_WHEN_FIELD: "2026-08-12",
    })


def test_the_pool_list_hides_unopened_pieces_from_a_stranger_and_not_from_the_room():
    pieces = [
        subject(entry_id="done", pool_open=True),
        subject(entry_id="wip", pool_open=False),
    ]
    stranger = pool_visible(pieces, is_member=False, admin=False)
    assert [p.entry_id for p in stranger] == ["done"]
    assert len(pool_visible(pieces, is_member=True, admin=False)) == 2
    assert len(pool_visible(pieces, is_member=False, admin=True)) == 2



def test_a_workshop_peer_rates_and_sees_the_score_but_not_the_scorers():
    access = access_for(subject(), user("DESIGNER"), RatingRound.PEER, is_member=True)
    assert access.in_round
    assert access.may_rate
    assert not access.may_read_ledger
    assert not access.sees_rater_identity
    assert access.visible


def test_the_author_of_a_sketch_sees_its_ledger_even_after_losing_the_workshop():
    access = access_for(
        subject(author_id=RATER), user("DESIGNER"), RatingRound.PEER, is_member=False
    )
    assert not access.in_round
    assert not access.may_rate
    assert access.may_read_ledger
    assert access.visible


def test_the_workshop_creator_reads_the_ledger_of_the_rows_inside_it():
    row = subject(author_id="somebody-else", workshop_author_id=RATER)
    assert is_own_record(row, user("DESIGNER"))
    assert access_for(row, user("DESIGNER"), RatingRound.PEER, is_member=True).may_read_ledger


def test_running_a_workshop_is_not_authorship_and_does_not_cost_the_creator_their_vote():
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

    drew_it = access_for(row, user("DESIGNER", AUTHOR), RatingRound.PEER, is_member=True)
    assert drew_it.is_author
    assert not drew_it.may_rate
    assert drew_it.may_read_ledger


def test_is_row_author_reads_the_row_alone_and_never_the_workshop():
    assert is_row_author(subject(author_id=RATER), user("DESIGNER"))
    assert not is_row_author(subject(author_id=AUTHOR, workshop_author_id=RATER), user("DESIGNER"))
    assert not is_row_author(subject(author_id=None), user("DESIGNER"))
    assert not is_row_author(
        subject(author_id=None), SimpleNamespace(id=None, role="DESIGNER")
    )


def test_a_null_author_is_nobodys_own_record():
    orphan = subject(author_id=None, workshop_author_id=None)
    assert not is_own_record(orphan, user("DESIGNER"))
    assert not is_own_record(orphan, SimpleNamespace(id=None, role="DESIGNER"))


def test_an_unrelated_designer_reaches_a_finished_pieces_pool_round_and_nothing_else():
    piece = subject(pool_open=True)
    stranger = user("DESIGNER")
    pool = access_for(piece, stranger, RatingRound.POOL, is_member=False)
    peer = access_for(piece, stranger, RatingRound.PEER, is_member=False)
    assert pool.in_round and pool.may_rate and not pool.may_read_ledger
    assert not peer.in_round and not peer.visible


def test_one_finished_prototype_does_not_open_its_unfinished_neighbours():
    stranger = user("DESIGNER")
    done = access_for(subject(pool_open=True), stranger, RatingRound.POOL, is_member=False)
    wip = access_for(subject(pool_open=False), stranger, RatingRound.POOL, is_member=False)
    assert done.in_round
    assert not wip.in_round


def test_a_designer_may_not_rate_their_own_prototype():
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
    monkeypatch.setattr(
        "app.services.design_ratings.POOL_RATINGS_NAME_THEIR_RATER", switch
    )
    mine = subject(author_id=RATER, pool_open=True)
    peer = access_for(mine, user("DESIGNER"), RatingRound.PEER, is_member=True)
    pool = access_for(mine, user("DESIGNER"), RatingRound.POOL, is_member=True)
    assert peer.sees_rater_identity
    assert pool.sees_rater_identity is switch



def test_a_peer_gets_only_their_own_row_out_of_the_ledger():
    access = access_for(subject(), user("DESIGNER"), RatingRound.PEER, is_member=True)
    rows = [
        ledger_row(rating_id="mine", reviewer_id=RATER),
        ledger_row(rating_id="theirs", reviewer_id="usr-other"),
    ]
    shown = visible_rows(rows, viewer_id=RATER, access=access)
    assert [row.id for row in shown] == ["mine"]


def test_a_row_a_peer_may_see_carries_no_other_persons_name():
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
    access = access_for(subject(), user("ADMIN"), RatingRound.PEER, is_member=False)
    row = ledger_row(rated_at=datetime(2026, 8, 6, 11, 30, tzinfo=UTC))
    payload = rating_payload(row, viewer_id="usr-admin", access=access)
    assert payload["ratedAt"] == "2026-08-06T11:30:00+00:00"
    assert payload["createdAt"] == "2026-08-20T09:00:00+00:00"



def test_a_rating_cannot_be_planned_into_a_stage_row():
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
    assert outcome.plan.data["designWorkshopId"] == "wk-1"
    assert outcome.plan.data["entityKey"] == "prototype"
    assert outcome.plan.data["round"] == "PEER"
    assert outcome.plan.data["reviewerId"] == RATER
    assert "createdAt" not in outcome.plan.data
    assert "updatedAt" not in outcome.plan.data


def test_the_same_capture_arriving_twice_writes_nothing():
    moment = NOW - timedelta(days=3)
    stored = ledger_row(rated_at=moment, score=4)
    outcome = _plan(existing=stored, score=4, rated_at=moment)
    assert outcome.replayed is True
    assert outcome.plan is None
    assert outcome.existing_id == "rat-1"


def test_a_redelivery_is_a_replay_even_though_the_ledger_truncated_the_clock_it_stored():
    captured = (NOW - timedelta(days=3)).replace(microsecond=451879)
    stored = ledger_row(rated_at=captured.replace(microsecond=451000), score=4)
    outcome = _plan(existing=stored, score=4, rated_at=captured)
    assert outcome.replayed is True, "the second delivery of one capture must write nothing"
    assert outcome.plan is None
    assert outcome.existing_id == "rat-1"


def test_an_amendment_a_millisecond_later_is_still_an_amendment():
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
    for immutable in ("stageEntryId", "round", "reviewerId", "designWorkshopId"):
        assert immutable not in outcome.plan.data


def test_a_rating_typed_against_the_server_is_always_applied():
    stored = ledger_row(rated_at=NOW - timedelta(days=1), score=4)
    assert _plan(existing=stored, score=1, rated_at=None).plan is not None

    clockless = ledger_row(rated_at=None, score=4)
    assert _plan(existing=clockless, score=1, rated_at=NOW - timedelta(days=9)).plan is not None


def test_an_amendment_with_no_device_clock_leaves_the_stored_one_alone():
    stored = ledger_row(rated_at=NOW - timedelta(days=1), score=5)
    amendment = _plan(existing=stored, score=3, rated_at=None)
    assert amendment.plan is not None
    assert amendment.plan.operation is Operation.UPDATE
    assert amendment.plan.data["score"] == 3
    assert "ratedAt" not in amendment.plan.data, amendment.plan.data

    created = _plan(existing=None, rated_at=None)
    assert created.plan is not None
    assert created.plan.operation is Operation.CREATE
    assert created.plan.data["ratedAt"] is None


def test_a_clockless_amendment_does_not_disarm_the_tunnel_rule_for_the_next_delivery():
    courtyard = NOW - timedelta(days=3)
    stored = ledger_row(rated_at=courtyard, score=5)

    amendment = _plan(existing=stored, score=3, rated_at=None)
    assert amendment.plan is not None
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
    with pytest.raises(RatingRuleViolation):
        _plan(score=True)


def test_a_rating_with_no_reviewer_is_refused():
    with pytest.raises(RatingRuleViolation):
        _plan(reviewer_id="")


def test_a_device_clock_in_the_future_is_refused_rather_than_corrected():
    with pytest.raises(RatingRuleViolation):
        _plan(rated_at=NOW + MAX_DEVICE_CLOCK_SKEW + timedelta(minutes=1))


def test_a_clock_inside_the_tolerated_skew_is_kept_verbatim():
    moment = NOW + MAX_DEVICE_CLOCK_SKEW - timedelta(minutes=1)
    outcome = _plan(rated_at=moment)
    assert outcome.plan is not None
    assert outcome.plan.data["ratedAt"] == moment


def test_a_naive_device_clock_is_read_as_utc_rather_than_crashing():
    outcome = _plan(rated_at=datetime(2026, 8, 19, 9, 0))  # noqa: DTZ001
    assert outcome.plan is not None

    stored = ledger_row(rated_at=datetime(2026, 8, 19, 9, 0))  # noqa: DTZ001
    assert _plan(existing=stored, rated_at=datetime(2026, 8, 18, 9, 0)).plan is None  # noqa: DTZ001



def test_the_default_order_is_by_score_and_the_placed_order_is_the_designers():
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
    subjects = [subject(entry_id="a")]
    rows = [
        ledger_row(rating_id="r1", entry_id="a", score=5, reviewer_id="u1"),
        ledger_row(rating_id="r2", entry_id="a", score=2, reviewer_id="u2"),
    ]
    ranked = rank(subjects, rows)[0]
    assert ranked.score == pytest.approx(3.5)
    assert ranked.count == 2


def test_an_unrated_prototype_is_ranked_last_and_not_dropped():
    subjects = [subject(entry_id="rated", ordinal=0), subject(entry_id="blank", ordinal=1)]
    rows = [ledger_row(rating_id="r1", entry_id="rated", score=1, reviewer_id="u1")]
    ranked = {r.subject.entry_id: r for r in rank(subjects, rows)}
    assert ranked["blank"].score is None
    assert ranked["blank"].count == 0
    assert ranked["rated"].default_position == 1
    assert ranked["blank"].default_position == 2


def test_ties_are_broken_deterministically_and_not_by_scan_order():
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
    assert forward == {"y": 1, "x": 2}


def test_a_rating_of_another_subject_never_contributes_to_this_ones_score():
    subjects = [subject(entry_id="a")]
    rows = [
        ledger_row(rating_id="r1", entry_id="a", score=5, reviewer_id="u1"),
        ledger_row(rating_id="r2", entry_id="elsewhere", score=1, reviewer_id="u1"),
    ]
    assert rank(subjects, rows)[0].score == pytest.approx(5.0)



def test_the_raw_ordinal_travels_only_to_the_people_who_see_the_whole_collection():
    ranked = rank([subject(entry_id="a", ordinal=7)], [])[0]

    stranger = ranked_payload(ranked, mine=None, show_ordinal=False)
    assert "ordinal" not in stranger, stranger
    assert stranger["placedPosition"] == 1
    assert stranger["defaultPosition"] == 1

    insider = ranked_payload(ranked, mine=None, show_ordinal=True)
    assert insider["ordinal"] == 7


def test_both_routes_round_the_average_through_the_same_helper():
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

    assert issubclass(RatingSubjectGone, RatingRuleViolation)


async def _apply_against(delegate, outcome):
    import app.services.design_ratings as module

    original = module.db
    module.db = SimpleNamespace(**{RATING_DELEGATE: delegate})
    try:
        return await module.apply_rating(outcome)
    finally:
        module.db = original
