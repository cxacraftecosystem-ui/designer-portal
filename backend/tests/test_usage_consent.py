"""Usage consent: what is stored, what a withdrawal reaches, and what the sign-in gate does.

THE FAILURE THIS FILE EXISTS TO STOP IS A CONSENT RECORD THAT CANNOT SUPPORT ITS OWN CLAIM. The gate
at sign-in makes agreeing a condition of access, which under GDPR Art. 7(4) is not freely-given
consent; a system that stored ``GRANTED`` and nothing else would file nine thousand turnstiles as
nine thousand free choices, and no later reader could tell them apart. So the first tests below are
about the two columns that carry the CIRCUMSTANCE and the TEXT — ``usageConsentBasis`` and
``usageConsentVersion`` — and both assert that the value reached the log row as well as the account,
because a history that cannot say which of a person's answers were freely given is the same defect
one level down.

THE SECOND CLASS OF FAILURE IS A WITHDRAWAL THAT IS THEATRE. Three tests cover it: a refusal deletes
the stored rows and empties the buffer (following ``cancel_pending_transcriptions``, which is named
in the code and in the assertions), it does NOT erase the earlier grant from the log, and — the one
that would have shipped as a silent bug — **agreeing again after a withdrawal actually resumes
recording**, because ``_WITHDRAWN`` is a process-local set that no column can reach.

THE THIRD IS THE GATE ITSELF. It must fire on BOTH credential paths at the single point they join,
it must ADMIT rather than refuse — a 403 before the token is minted is a gate nobody can get through,
because recording an answer needs a bearer token — and it must never be able to lock out the
break-glass master admin, which is the account the whole platform allow-list argument rests on.

NOTHING HERE TOUCHES A DATABASE. ``db`` is replaced by delegates that capture what each model was
asked to write, which is the only way to assert what would have been STORED rather than what was
computed on the way there.
"""

import sys
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.routes import auth as auth_routes, usage as usage_routes
from app.core import deps
from app.schemas.auth import LoginRequest
from app.services import usage

#: A cuid of the shape this API mints, used as the account id throughout.
USER_ID = "ckv9r2m4x0001qz8h3n7d2f5g"

#: A moment safely inside every window the tests ask for, fixed rather than relative to "now" so a
#: run cannot straddle a boundary and start asserting a different number of anything.
NOW = datetime(2026, 8, 30, 12, 0, tzinfo=UTC)


# --------------------------------------------------------------------------------------
# The fakes
# --------------------------------------------------------------------------------------


class _UserTable:
    """``db.user``. Holds one row, remembers every update, and hands back the updated row.

    It APPLIES the update rather than merely recording it, because half these tests are about what
    the next read sees — a fake that captured the write and returned the old row would let a missing
    cache invalidation or a wrong column name pass unnoticed.
    """

    def __init__(self, row: Any) -> None:
        self.row = row
        self.updates: list[dict[str, Any]] = []

    async def update(self, *, where: dict[str, Any], data: dict[str, Any]) -> Any:
        self.updates.append({"where": dict(where), "data": dict(data)})
        for key, value in data.items():
            setattr(self.row, key, value)
        return self.row

    async def find_unique(self, *, where: dict[str, Any]) -> Any:
        # Matched on either key, because the two sign-in branches look the row up differently: the
        # password path by email, everything else by id.
        for key in ("id", "email"):
            if key in where:
                return self.row if where[key] == getattr(self.row, key, None) else None
        return None

    async def find_first(self, **_: Any) -> Any:
        return self.row


class _ConsentDecisionTable:
    """``db.usageconsentdecision``. Append-only, exactly as the model is."""

    def __init__(self) -> None:
        self.rows: list[Any] = []

    async def create(self, *, data: dict[str, Any]) -> Any:
        row = SimpleNamespace(id=f"d-{len(self.rows)}", createdAt=NOW, **data)
        self.rows.append(row)
        return row

    async def find_many(self, **kwargs: Any) -> list[Any]:
        wanted = (kwargs.get("where") or {}).get("userId")
        rows = [row for row in self.rows if getattr(row, "userId", None) == wanted]
        return list(reversed(rows))[: kwargs.get("take") or 50]


class _EventTable:
    """``db.usageevent``, remembering what was written and what was deleted."""

    def __init__(self) -> None:
        self.batches: list[list[dict[str, Any]]] = []
        self.deletes: list[dict[str, Any]] = []
        self.stored = 0

    async def create_many(self, data: Any) -> int:
        rows = list(data)
        self.batches.append(rows)
        return len(rows)

    async def delete_many(self, *, where: dict[str, Any]) -> int:
        self.deletes.append(dict(where))
        deleted, self.stored = self.stored, 0
        return deleted

    @property
    def rows(self) -> list[dict[str, Any]]:
        return [row for batch in self.batches for row in batch]


def _account(**overrides: Any) -> Any:
    """An account row of the shape Prisma hands back, with the four consent columns present.

    NOT_RECORDED and three NULLs is what every account in the database carried on the day the column
    landed, so it is the default here too — a fixture that started from GRANTED would never exercise
    the state the whole three-value enum exists for.
    """
    row = SimpleNamespace(
        id=USER_ID,
        email="designer@example.org",
        name="A Designer",
        role="DESIGNER",
        passwordHash="not-a-real-hash",
        usageConsent="NOT_RECORDED",
        usageConsentAt=None,
        usageConsentBasis=None,
        usageConsentVersion=None,
    )
    for key, value in overrides.items():
        setattr(row, key, value)
    return row


@pytest.fixture
def store(monkeypatch: pytest.MonkeyPatch) -> Iterator[SimpleNamespace]:
    """A fake ``db`` across every module that holds a reference to the real one, plus a clean buffer.

    The loop over ``sys.modules`` is the same one ``test_usage_tracking`` uses and for the same
    reason: modules do ``from app.core.db import db``, so patching ``core_db.db`` alone would leave
    every one of them pointing at the real client.
    """
    users = _UserTable(_account())
    decisions = _ConsentDecisionTable()
    events = _EventTable()
    fake_db = SimpleNamespace(user=users, usageconsentdecision=decisions, usageevent=events)

    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", fake_db)

    usage.reset_buffer()
    usage.register_known_templates(())
    yield SimpleNamespace(users=users, decisions=decisions, events=events, db=fake_db)
    usage.reset_buffer()
    usage.register_known_templates(())


_CALLER: dict[str, Any] = {"user": None}


def _consent_app() -> FastAPI:
    """The usage router with the identity overridden and every gate left real."""
    application = FastAPI()
    application.include_router(usage_routes.router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]
    return application


@pytest.fixture
def caller() -> Iterator[dict[str, Any]]:
    _CALLER["user"] = None
    yield _CALLER
    _CALLER["user"] = None


async def _request(application: FastAPI, method: str, path: str, **kwargs: Any) -> httpx.Response:
    transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://usage.test") as client:
        return await client.request(method, path, **kwargs)


# --------------------------------------------------------------------------------------
# What is stored
# --------------------------------------------------------------------------------------


async def test_a_grant_records_the_circumstance_and_the_text_and_not_only_the_answer(
    store: SimpleNamespace,
) -> None:
    """**THE FOUR COLUMNS, AND WHY A BOOLEAN WITH A DATE WOULD NOT DO.**

    A grant collected at the sign-in gate is a CONDITION OF ACCESS: the person could not proceed
    without it. Under GDPR Art. 7(4) that is not freely-given consent, and a record that stored only
    ``GRANTED`` would invite exactly the claim it cannot support. So ``usageConsentBasis`` carries
    the circumstance and ``usageConsentVersion`` carries which text was on screen — and **both reach
    the log row as well as the account**, which is the half that would be easy to omit and that makes
    a history able to say which of somebody's answers were freely given.
    """
    outcome = await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )

    assert outcome.decision is usage.UsageConsent.GRANTED
    assert outcome.withdrawal is None, "a grant deletes nothing"

    written = store.users.updates[0]["data"]
    assert written[usage.CONSENT_ATTRIBUTE] == "GRANTED"
    assert written[usage.CONSENT_BASIS_ATTRIBUTE] == "REQUIRED_AT_SIGN_IN"
    assert written[usage.CONSENT_VERSION_ATTRIBUTE] == usage.NOTICE_VERSION
    assert written[usage.CONSENT_AT_ATTRIBUTE] == NOW

    logged = store.decisions.rows[0]
    assert logged.decision == "GRANTED"
    assert logged.basis == "REQUIRED_AT_SIGN_IN", (
        "the circumstance is on the log row too. Without it a history can say what somebody "
        "answered and not whether they were free to answer otherwise, which is the distinction "
        "this whole vocabulary exists to keep."
    )
    assert logged.noticeVersion == usage.NOTICE_VERSION
    assert logged.recordedAt is None, (
        "the answer was given straight against the server, so there is no device clock to record — "
        "copying `at` in here would later read as 'a device reported this', which is false"
    )


async def test_the_column_name_is_the_one_the_recorder_reads_off_the_row(
    store: SimpleNamespace,
) -> None:
    """``CONSENT_ATTRIBUTE`` IS A CONTRACT WITH A ``getattr``, AND ``getattr`` CONTRACTS FAIL SILENTLY.

    ``resolve_consent`` reads the column by name off the already-loaded ``User`` row. A miss does not
    raise: it returns None, resolves to NOT_RECORDED, and collection for the entire fleet silently
    reverts to anonymous with no error, no log line and nothing going red. So the name is pinned
    against the generated Prisma model — which is the thing a rename would actually change.
    """
    from prisma import models as prisma_models

    fields = set(prisma_models.User.model_fields)
    for attribute in (
        usage.CONSENT_ATTRIBUTE,
        usage.CONSENT_AT_ATTRIBUTE,
        usage.CONSENT_BASIS_ATTRIBUTE,
        usage.CONSENT_VERSION_ATTRIBUTE,
    ):
        assert attribute in fields, (
            f"usage.py reads {attribute!r} off a User row and the column is not there. A getattr "
            f"miss resolves to NOT_RECORDED, so this does not raise anywhere — it just stops "
            f"attributing anything, for everybody."
        )
    decision_fields = set(prisma_models.UsageConsentDecision.model_fields)
    assert {"userId", "decision", "basis", "noticeVersion", "recordedAt", "createdAt"} <= (
        decision_fields
    )


async def test_not_recorded_is_refused_as_a_decision_with_the_next_move_in_the_sentence(
    store: SimpleNamespace,
) -> None:
    """NOT_RECORDED IS THE ABSENCE OF AN ANSWER, NOT ONE SOMEBODY CAN GIVE — ``decision_plans``'
    rule one consent question over, and not pedantry.

    "Somebody deliberately recorded that nobody has been asked" is not a state a person can be in,
    and a route that allowed it would leave a gate unable to tell a withdrawn consent from an account
    nobody has opened. The refusal names REFUSED as the way to take an answer back, because a refusal
    that does not name a next move teaches people to stop reading them.
    """
    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.consent_decision_plans(
            user_id=USER_ID,
            decision=usage.UsageConsent.NOT_RECORDED,
            basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
            notice_version=usage.NOTICE_VERSION,
            at=NOW,
        )

    assert "REFUSED" in str(refusal.value)
    assert store.users.updates == [], "nothing was written"


async def test_a_consent_may_not_be_written_onto_the_observations_themselves(
    store: SimpleNamespace,
) -> None:
    """**THE CONSTRUCTION GUARD.** ``UsageEvent`` is not in ``CONSENT_WRITABLE_TABLES``.

    The tempting wrong move here is the one the audio path had to refuse for stage rows: writing the
    answer onto the rows it is about. It would be an UPDATE across a consenting account's hundred
    thousand observations on a request somebody is waiting on, and — decisively — the rows collected
    BEFORE the answer would come to claim it, destroying the one distinction ``consentState`` exists
    for. The guard is CODE rather than prose, so a later change has to delete a check in a visible
    diff.
    """
    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.ConsentWritePlan(
            table=usage.OBSERVATION_TABLE,
            operation=usage.ConsentOperation.UPDATE,
            where={"id": USER_ID},
            data={"consentState": "GRANTED"},
        )

    assert usage.OBSERVATION_TABLE in str(refusal.value)
    assert "User" in str(refusal.value), "the refusal names where it should have been written"

    # The second half of the guard: even a plan that somehow carried the name has nowhere to be
    # applied, because there is no writer for it.
    with pytest.raises(usage.UsageRuleViolation):
        usage._consent_model(usage.OBSERVATION_TABLE)


async def test_a_clock_in_the_future_is_refused_rather_than_quietly_corrected(
    store: SimpleNamespace,
) -> None:
    """A SUBSTITUTED TIMESTAMP IS A FABRICATED FACT ABOUT WHEN SOMEBODY CONSENTED.

    ``MAX_DEVICE_CLOCK_SKEW`` is generous — fifteen minutes, so no honest handset is refused — and
    past it the answer is refused with the next move rather than stored against ``now()``. The same
    rule, the same constant and the same argument as ``dictation_consent``.
    """
    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.consent_decision_plans(
            user_id=USER_ID,
            decision=usage.UsageConsent.GRANTED,
            basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
            notice_version=usage.NOTICE_VERSION,
            at=NOW,
            recorded_at=NOW + timedelta(days=30),
        )

    detail = str(refusal.value)
    assert "in the future" in detail
    assert "not stored with a corrected time" in detail

    # Inside the tolerance it is stored exactly as sent — including its offset, which is the only
    # clue about where the answer was taken down.
    plans = usage.consent_decision_plans(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
        recorded_at=NOW + timedelta(minutes=5),
    )
    assert plans.decision.data["recordedAt"] == NOW + timedelta(minutes=5)
    assert plans.account.data[usage.CONSENT_AT_ATTRIBUTE] == NOW + timedelta(minutes=5), (
        "what lands on the account is when the PERSON answered, not when the server heard it"
    )


async def test_an_unrecognised_notice_version_is_stored_verbatim_rather_than_refused(
    store: SimpleNamespace,
) -> None:
    """THE ONE DELIBERATE ASYMMETRY: an unknown version is ACCEPTED.

    A handset can hold a cached notice for a fortnight and a rollback can put an older one back in
    front of people. Refusing their answer would lock them out of a product whose door this consent
    is. What the record must be true about is WHICH TEXT THEY SAW — and the honest answer to that is
    the version they say they saw, stored as sent and never rewritten to today's.
    """
    plans = usage.consent_decision_plans(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version="1999-01-01.7",
        at=NOW,
    )
    assert plans.decision.data["noticeVersion"] == "1999-01-01.7"
    assert plans.account.data[usage.CONSENT_VERSION_ATTRIBUTE] == "1999-01-01.7"

    # An EMPTY version is a different thing and is refused: a record that cannot say what was agreed
    # to is worse than none.
    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.consent_decision_plans(
            user_id=USER_ID,
            decision=usage.UsageConsent.GRANTED,
            basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
            notice_version="   ",
            at=NOW,
        )
    assert "which version" in str(refusal.value)


# --------------------------------------------------------------------------------------
# Withdrawal
# --------------------------------------------------------------------------------------


async def test_a_withdrawal_stops_collection_empties_the_buffer_and_deletes_what_was_stored(
    store: SimpleNamespace,
) -> None:
    """**WHAT MAKES A WITHDRAWAL A WITHDRAWAL**, following the audio path by name.

    ``cancel_pending_transcriptions`` exists because nine clips queued under a grant given on the 3rd
    would otherwise go out on the night of the 9th — *"a consent that cannot recall what it already
    authorised is a preference, not a permission."* Here the queue is this module's own buffer and
    the archive is the table, so all three have to happen: the answer is stored, the buffered rows
    are thrown away before they can be written, and the stored rows are DELETED rather than unnamed.

    DELETED AND NOT BLANKED, which the schema settles: ``SetNull`` would make NULL mean both "nobody
    was signed in" and "this person withdrew", so every count of unauthenticated traffic would
    quietly include them.
    """
    usage.record_event(
        route_template="/design-workshops/{workshop_id}",
        method="GET",
        status_code=200,
        duration_ms=12,
        user_id=USER_ID,
        consent=usage.UsageConsent.GRANTED,
    )
    assert usage.buffer_stats()["buffered"] == 1
    store.events.stored = 7

    outcome = await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )

    assert store.users.updates[0]["data"][usage.CONSENT_ATTRIBUTE] == "REFUSED"
    assert outcome.withdrawal is not None
    assert outcome.withdrawal.buffered_dropped == 1, "observed and not yet written: thrown away"
    assert outcome.withdrawal.stored_deleted == 7
    assert outcome.withdrawal.stored_delete_ran is True
    assert store.events.deletes == [{"userId": USER_ID}], (
        "the delete names the account, and there is no update blanking userId anywhere"
    )
    assert usage.buffer_stats()["buffered"] == 0
    assert usage.is_withdrawn(USER_ID) is True

    # And nothing further is recorded, in this process, without any database read at all.
    assert (
        usage.record_event(
            route_template="/design-workshops/{workshop_id}",
            method="GET",
            status_code=200,
            duration_ms=12,
            user_id=USER_ID,
            consent=usage.UsageConsent.GRANTED,
        )
        is False
    )


async def test_a_withdrawal_does_not_erase_the_answer_the_collection_was_made_under(
    store: SimpleNamespace,
) -> None:
    """"GRANTED ON THE 3RD, WITHDRAWN ON THE 9TH" IS ONLY ANSWERABLE FROM A LOG.

    ``DwWorkshopConsentDecision``'s argument, and it matters MORE here: there, transcripts made under
    the grant survive the withdrawal and explain themselves; here the observations are DELETED, so
    this log is the only evidence left that they were ever collected with an agreement behind them.
    The columns say REFUSED. The log says both, in order, each with its own circumstance.
    """
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW + timedelta(days=6),
        note="I would rather not.",
    )

    assert usage.resolve_consent(store.users.row) is usage.UsageConsent.REFUSED
    history = [
        usage.consent_decision_payload(row)
        for row in await usage.consent_history(USER_ID)
    ]
    assert [entry["decision"] for entry in history] == ["REFUSED", "GRANTED"], "newest first"
    assert [entry["basis"] for entry in history] == [
        "OFFERED_IN_SETTINGS",
        "REQUIRED_AT_SIGN_IN",
    ], (
        "the withdrawal was a free choice and the grant was a turnstile. A history that cannot "
        "distinguish them cannot say whether anybody was ever asked freely."
    )
    assert history[0]["note"] == "I would rather not."


async def test_agreeing_again_after_a_withdrawal_actually_resumes_recording(
    store: SimpleNamespace,
) -> None:
    """**THE BUG THAT WOULD HAVE SHIPPED SILENTLY, AND THE REASON ``resume`` EXISTS.**

    ``_WITHDRAWN`` is a process-local set, checked FIRST in ``record_event`` and again in ``flush``,
    ahead of the consent rule and deliberately — it is what stops rows already in this process's
    buffer without a database read. It is therefore a refusal that can outlive the answer that
    produced it: without ``resume``, a person who withdraws on Monday and agrees again on Tuesday
    stays in the set until this worker restarts, with ``usageConsent`` reading GRANTED, ``/usage/me``
    reporting nothing, and no log line anywhere saying why.

    Nothing about the durable half would catch this. The column is right, the log is right, every
    aggregate is right, and not one row is written.
    """
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    assert usage.is_withdrawn(USER_ID) is True

    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW + timedelta(days=1),
    )

    assert usage.is_withdrawn(USER_ID) is False
    recorded = usage.record_event(
        route_template="/design-workshops/{workshop_id}",
        method="GET",
        status_code=200,
        duration_ms=12,
        user_id=USER_ID,
        consent=usage.UsageConsent.GRANTED,
    )
    assert recorded is True
    row = next(iter(usage._BUFFER))
    assert row["userId"] == USER_ID
    assert row["consentState"] == "GRANTED"


# --------------------------------------------------------------------------------------
# Re-consent when the text changes
# --------------------------------------------------------------------------------------


async def test_a_new_notice_version_asks_again_without_reclassifying_the_stored_answer(
    store: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    """**RE-CONSENT ON A REWORD, AND THE HALF OF IT THAT IS EASY TO GET WRONG.**

    Bumping the version must make the gate ask again — otherwise a reword silently claims agreement
    to wording nobody saw, which is the entire reason the column exists.

    It must NOT flip the stored answer to NOT_RECORDED. That would mean a wording change
    reclassified the whole fleet mid-window: every aggregate's population would move on a deploy,
    and every designer's own ``/usage/me`` would go blank overnight — to enforce something the
    version column already answers by being stored. So ``resolve_consent`` still reads GRANTED and
    recording continues under the answer already given, while ``consent_gate`` reports
    ``required: true`` and names the version they agreed to.
    """
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    account = store.users.row
    settled = usage.consent_gate(account)
    assert settled["required"] is False

    old_version = usage.NOTICE_VERSION
    monkeypatch.setattr(usage, "NOTICE_VERSION", "2027-01-01.1")

    asked_again = usage.consent_gate(account)
    assert asked_again["required"] is True
    assert asked_again["state"] == "GRANTED", "they did agree; the text moved"
    assert asked_again["agreedVersion"] == old_version
    assert old_version in asked_again["reason"]
    assert usage.resolve_consent(account) is usage.UsageConsent.GRANTED, (
        "collection continues under the answer already given. Flipping this to NOT_RECORDED would "
        "blank every designer's own usage on a deploy that changed a sentence."
    )
    assert usage.collection_plan(usage.resolve_consent(account)).attribute is True


async def test_a_refusal_is_not_asked_again_and_costs_the_account_nothing(
    store: SimpleNamespace,
) -> None:
    """A REFUSAL HAS ALREADY BEEN ANSWERED. Putting the question back in front of somebody who has
    just declined is how a product teaches people that "no" is negotiable — the same argument
    ``gate_refusal`` makes for keeping NOT_RECORDED and REFUSED as two different sentences.

    And the gate reports ``required: false``, which is what makes the turnstile at the door
    defensible: withdrawing costs nothing, so the agreement is one a person genuinely retains.
    """
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    gate = usage.consent_gate(store.users.row)

    assert gate["state"] == "REFUSED"
    assert gate["required"] is False
    assert "costs this account nothing" in gate["reason"]


# --------------------------------------------------------------------------------------
# The routes
# --------------------------------------------------------------------------------------


async def test_the_notice_is_ungated_and_carries_what_a_person_must_be_told(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    """THE ONLY ROUTE IN THE MODULE WITH NO DEPENDENCY, AND IT HAS TO BE.

    It is read on a sign-in screen by somebody with no token — the whole point of the gate is that
    they have not agreed yet — so a gate here would mean the only way to see what you are agreeing to
    is to agree first.

    The content assertions are not decoration. THAT IT IS REQUIRED must be stated plainly rather
    than implied by a disabled button; what a duration is NOT is the single most misread number in
    the feature; and the withdrawal section must say that the stored rows are DELETED, because a
    person agreeing is entitled to know what taking it back actually reaches.
    """
    caller["user"] = None  # no session at all
    response = await _request(_consent_app(), "GET", "/usage/consent/notice")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["version"] == usage.NOTICE_VERSION
    assert body["required"] is True
    assert "cannot sign in without agreeing" in body["requiredSentence"]
    assert "not a free choice" in body["requiredSentence"]
    assert any("route TEMPLATE" in line for line in body["collects"])
    assert any("account id" in line for line in body["collects"])
    assert any("IP addresses" in line for line in body["doesNotCollect"])
    assert "not what you waited for" in body["durationCaveat"]
    assert any("DELETES" in line for line in body["withdrawal"]["does"])
    assert "does not sign you out" in body["withdrawal"]["costsNothing"]
    assert "no retention policy" in body["retention"]


async def test_every_read_route_in_this_module_is_named_in_the_notice(
    store: SimpleNamespace,
) -> None:
    """**"WHO CAN READ IT" IS A PROMISE MADE TO A PERSON, NOT DOCUMENTATION OF THE GATES.**

    It is shown to somebody at the moment they decide whether to agree. A route that reads usage data
    and is not named there makes the notice false for everybody who has already answered — and the
    omission is invisible, because nothing else in the system compares the two.
    """
    named = set(usage.readable_by())
    served = {
        route.path
        for route in usage_routes.router.routes
        if "GET" in getattr(route, "methods", set())
        # The notice describes what is READ ABOUT PEOPLE. The consent routes are a person's own
        # answer rather than a reading of their behaviour, and listing them would pad the promise
        # with rows that answer a different question.
        and not getattr(route, "path", "").startswith("/usage/consent")
    }
    assert served == named, (
        f"routes not named in usage.readable_by(): {sorted(served - named)}; named but not served: "
        f"{sorted(named - served)}"
    )


async def test_recording_an_answer_needs_no_permission_and_drops_the_cached_identity(
    store: SimpleNamespace, caller: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    """TWO CLAIMS, AND THE SECOND IS THE ONE THAT WOULD BE MISSED.

    First: recording your own answer about your own data is ``get_current_user`` and nothing more —
    routing it through an admin gate would mean asking an administrator for permission to refuse.

    Second: ``deps`` caches the authenticated ``User`` row for five seconds, and ``resolve_consent``
    reads the consent OFF THAT CACHED ROW. Without the invalidation, a person who WITHDRAWS goes on
    being recorded for up to five seconds after asking not to be — with ``withdraw()`` having already
    deleted the rows that existed when they asked. Five seconds of a trail written after somebody
    said stop is exactly what the buffer purge exists to prevent, arriving through the one door the
    purge cannot see.
    """
    dropped: list[str | None] = []
    monkeypatch.setattr(usage_routes, "invalidate_cached_user", dropped.append)
    caller["user"] = store.users.row

    response = await _request(
        _consent_app(),
        "POST",
        "/usage/consent",
        json={
            "decision": "GRANTED",
            "basis": "REQUIRED_AT_SIGN_IN",
            "noticeVersion": usage.NOTICE_VERSION,
        },
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["consent"]["state"] == "GRANTED"
    assert body["consent"]["basis"] == "REQUIRED_AT_SIGN_IN"
    assert body["gate"]["required"] is False
    assert body["decisions"][0]["decision"] == "GRANTED"
    assert dropped == [USER_ID], (
        "the cached identity was not dropped; resolve_consent would go on reading the pre-answer "
        "row for the life of the cache entry"
    )


async def test_the_withdraw_route_supplies_its_own_basis_and_reports_what_it_deleted(
    store: SimpleNamespace, caller: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    """A WITHDRAWAL IS BY CONSTRUCTION A FREE CHOICE, so the route supplies the basis and the client
    cannot file one as though it had been demanded. Nobody withdraws at a door they are trying to get
    through.

    And it reports what the deletion actually reached. ``withdraw()`` never raises, so a failed
    delete would otherwise be indistinguishable from a successful one from outside — and a person who
    asks for their record to be deleted is entitled to be told whether it was.
    """
    monkeypatch.setattr(usage_routes, "invalidate_cached_user", lambda _user_id: None)
    caller["user"] = store.users.row
    store.events.stored = 3

    response = await _request(
        _consent_app(),
        "POST",
        "/usage/consent/withdraw",
        json={"noticeVersion": usage.NOTICE_VERSION, "note": "no thanks"},
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["consent"]["state"] == "REFUSED"
    assert body["consent"]["basis"] == "OFFERED_IN_SETTINGS", (
        "the route chose the basis; there is no field on the body for a client to choose it with"
    )
    assert body["withdrawal"]["storedDeleted"] == 3
    assert body["withdrawal"]["storedDeleteRan"] is True
    assert "deleted" in body["withdrawal"]["explanation"]
    assert body["gate"]["required"] is False, "a withdrawal is not answered by asking again"


async def test_an_answer_nobody_can_record_is_a_422_naming_the_alternatives(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    """A REFUSAL THAT NAMES THE ALTERNATIVES, because the caller is a client author reading it once.

    422 rather than 409, matching ``record_dictation_consent``: everything refusable here is a
    statement about the BODY rather than about the account's state.
    """
    caller["user"] = store.users.row
    application = _consent_app()

    unknown = await _request(
        application,
        "POST",
        "/usage/consent",
        json={
            "decision": "MAYBE",
            "basis": "REQUIRED_AT_SIGN_IN",
            "noticeVersion": usage.NOTICE_VERSION,
        },
    )
    unrecordable = await _request(
        application,
        "POST",
        "/usage/consent",
        json={
            "decision": "NOT_RECORDED",
            "basis": "REQUIRED_AT_SIGN_IN",
            "noticeVersion": usage.NOTICE_VERSION,
        },
    )

    assert unknown.status_code == 422
    assert "GRANTED" in unknown.json()["detail"] and "REFUSED" in unknown.json()["detail"]
    assert unrecordable.status_code == 422
    assert "not an answer somebody can record" in unrecordable.json()["detail"]
    assert store.users.updates == []


# --------------------------------------------------------------------------------------
# The sign-in gate
# --------------------------------------------------------------------------------------


@pytest.fixture
def sign_in(monkeypatch: pytest.MonkeyPatch, store: SimpleNamespace) -> Iterator[dict[str, Any]]:
    """``POST /auth/login`` with everything but the consent concern stubbed out.

    The admission gates are replaced rather than exercised — they have their own tests, and what is
    under test here is the ONE point the password path and the Google path join. Both branches are
    driven through the real :func:`auth_routes.login`, because "it fires on both paths" is exactly
    the claim that would rot if either branch were tested through a helper of its own.
    """

    async def _admits(_email: str, *, is_master: bool) -> Any:
        return SimpleNamespace(status="ACTIVE")

    async def _noop(*_args: Any, **_kwargs: Any) -> Any:
        return None

    monkeypatch.setattr(auth_routes, "assert_access_admits", _admits)
    monkeypatch.setattr(auth_routes, "assert_roster_admits", _noop)
    monkeypatch.setattr(auth_routes, "mark_roster_seen", _noop)
    monkeypatch.setattr(auth_routes, "ensure_empanelled", _noop)
    monkeypatch.setattr(auth_routes.access_roster, "mark_access_seen", _noop)
    monkeypatch.setattr(auth_routes.access_roster, "admits", lambda _row: True)
    monkeypatch.setattr(auth_routes, "verify_password", lambda _raw, _hash: True)
    monkeypatch.setattr(
        auth_routes, "create_access_token", lambda **_kwargs: "a-real-looking-token"
    )
    yield {}


async def test_the_sign_in_gate_reports_on_both_credential_paths_at_the_point_they_join(
    store: SimpleNamespace, sign_in: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    """**BOTH DOORS, ONE GATE, AND THE SAME PAYLOAD OUT OF EACH.**

    ``POST /auth/login`` takes both credentials; the Google branch is a different function and a
    different set of writes. A gate written into the two branches instead of below them is a rule
    written twice, and a rule written twice is one door that quietly stops enforcing it. So the
    assertion is that the two responses agree field for field on the gate.

    THE GOOGLE PATH IS THE ONE THAT MATTERS, because it is the path researchers actually use.
    """
    account = store.users.row

    async def _google(_token: str) -> tuple[Any, Any]:
        return account, SimpleNamespace(status="ACTIVE")

    monkeypatch.setattr(auth_routes, "login_with_google", _google)

    by_password = await auth_routes.login(
        LoginRequest(email=account.email, password="a-password")
    )
    by_google = await auth_routes.login(LoginRequest(googleIdToken="a-google-token"))

    for answer in (by_password, by_google):
        gate = answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
        assert gate["required"] is True
        assert gate["state"] == "NOT_RECORDED"
        assert gate["noticeVersion"] == usage.NOTICE_VERSION
        assert gate["answerAt"] == "POST /api/usage/consent"
    assert (
        by_password["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
        == by_google["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
    ), "the two credential paths must not describe the same account's consent differently"

    # And the four columns ride along for free, which is the plumbing fact the whole feature rests
    # on: `serialize_user` is `jsonable_encoder` over the row, so no route change was needed.
    assert by_password["user"]["usageConsent"] == "NOT_RECORDED"
    assert "passwordHash" not in by_password["user"], "still stripped, as it always was"


async def test_the_sign_in_gate_admits_rather_than_refuses_so_the_answer_can_be_given(
    store: SimpleNamespace, sign_in: dict[str, Any]
) -> None:
    """**THE DECISION, PINNED: IT REPORTS, IT DOES NOT REFUSE.**

    A 403 before the token is minted would be a gate nobody can get through. The only way to record
    an answer is ``POST /api/usage/consent``, which needs a bearer token — so refusing an
    un-consented account at the door means it can never consent, which on the day this ships is every
    account there has ever been. The blocking half is the clients', at the screen; the server's job
    is to hand over a session and say plainly that an answer is owed.

    Asserted as a STATUS as well as a payload, because the tempting "fix" for a blocking requirement
    is a raise on this line, and it would look correct in review.
    """
    account = store.users.row
    answer = await auth_routes.login(LoginRequest(email=account.email, password="a-password"))

    assert answer["accessToken"] == "a-real-looking-token", (
        "an un-consented account MUST come away with a session — it is the only way it can reach "
        "POST /usage/consent to give the answer being demanded of it"
    )
    assert answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]["required"] is True
    reason = answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]["reason"]
    assert "condition of using the platform" in reason
    assert "WITHOUT any name" in reason


async def test_the_sign_in_gate_cannot_lock_out_the_break_glass_master_admin(
    store: SimpleNamespace, sign_in: dict[str, Any]
) -> None:
    """**THE BREAK-GLASS, AND WHY REPORTING NEEDS NO EXEMPTION FOR IT.**

    ``assert_access_admits`` exempts the master admin BY NAME, because "a break-glass that lives in
    the same table it is protecting against is not a break-glass" — and the whole argument for
    widening the platform gate to everybody rests on there always being one account that can get in
    and let people back in.

    A consent refusal at this door would be a SECOND lockout, on a column no allow-list screen can
    edit, and it would need its own exemption — a second break-glass to keep in step with the first.
    Because the gate reports instead, there is nothing to exempt: the master admin signs in with a
    token, is told an answer is owed, and nothing about their access depends on the answer.
    """
    master = _account(
        id="master-1",
        email="master@example.org",
        role="MASTER_ADMIN",
        usageConsent="NOT_RECORDED",
    )
    store.users.row = master

    answer = await auth_routes.login(LoginRequest(email=master.email, password="a-password"))

    assert answer["accessToken"] == "a-real-looking-token"
    assert answer["user"]["role"] == "MASTER_ADMIN"
    assert answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]["required"] is True
    assert deps.is_break_glass_master(master) is True


async def test_a_consented_account_signs_in_with_the_gate_closed(
    store: SimpleNamespace, sign_in: dict[str, Any]
) -> None:
    """The other half of the pair, so "required is always true" cannot pass this file.

    Also the case that proves the version comparison is doing work at sign-in rather than only in
    settings: the account agreed to the CURRENT text, so nothing is asked.
    """
    store.users.row = _account(
        usageConsent="GRANTED",
        usageConsentAt=NOW,
        usageConsentBasis="REQUIRED_AT_SIGN_IN",
        usageConsentVersion=usage.NOTICE_VERSION,
    )

    answer = await auth_routes.login(
        LoginRequest(email=store.users.row.email, password="a-password")
    )

    gate = answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
    assert gate["required"] is False
    assert gate["agreedVersion"] == usage.NOTICE_VERSION
    assert gate["basis"] == "REQUIRED_AT_SIGN_IN"
