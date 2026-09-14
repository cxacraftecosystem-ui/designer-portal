"""FIVE FIELDS IN, SEVEN ROWS OUT — and every way that can go silently wrong.

══ WHAT A SANCTION ORDER IS, AND WHY THIS MODULE IS LONG ══════════════════════════════════════

A ministry officer types an order number, an order date, a sanctioned amount, and the designer's
name and Gmail address. ``POST /api/sanction-orders`` turns those five facts into an allow-list
admission, a designer-roster empanelment, an account, a designer profile, a design workshop, the
designer's viewer row on it and the register row itself — in ONE transaction — and then, outside it,
a stage-1 prefill and a first-password link.

Nearly every failure this module is written against is SILENT. That is the shape of the feature: a
transaction that is not really a transaction still commits; a decimal that becomes a float still
renders; a roster row written under the wrong spelling of a Gmail address still exists. The happy
path passes in all four cases. So the assertions below are deliberately about rows and bytes rather
than about status codes.

══ THE SIX INCIDENTS, IN THE ORDER THEY WOULD COST MOST ═══════════════════════════════════════

1. **The transaction that is not a transaction.** ``db.tx()`` hands back a DIFFERENT client. Five
   service functions are called from inside it and every one of them takes a ``client=`` for that
   reason. Miss one and its writes commit independently of the rollback —
   ``test_a_refused_create_leaves_no_orphan_anything`` is the only thing in this repository that
   catches it, which is why it forces the failure at the LAST write in the block.

2. **The fifth refactor, silent in a different way.** ``ensure_empanelled`` reads the allow-list
   through ``name_on_the_allow_list`` to copy the administrator's own spelling of the designer's
   name onto the roster row. Read outside the transaction it cannot see the row written three
   statements ago, answers None, and every sanction-created designer lands on /admin/designers as a
   bare email address. Nothing raises. ``test_five_fields_produce_seven_rows``'s ``fullName``
   assertion is the whole of the guard.

3. **``Decimal`` becoming ``float`` on the wire.** ``jsonable_encoder`` does this, measured, and it
   has already shipped here once on a product price. ``test_the_amount_never_reaches_the_wire_as_a_
   float`` reads the raw response BYTES so that a refactor to ``jsonable_encoder(row)`` cannot pass.

4. **A sanction order re-admitting somebody an administrator threw out.** ``access_roster.admit``
   writes ``status: ACTIVE`` with no branch, and ``ensure_empanelled`` never revives a suspended
   row. The two phase-0 reads are the only thing between an officer's ordinary morning and an
   overturned decision, and both tests assert the row is STILL barred afterwards — which a
   reordering cannot fake.

5. **The Gmail alias written under the wrong spelling.** ``User.email`` must be the LITERAL
   lower-cased address, because both sign-in doors look it up literally; both rosters and
   ``SanctionOrder.designerEmail`` must be the CANONICAL mailbox, because that is the key the gates
   read. Getting either backwards is silent at write time and locks somebody out days later.

6. **A duplicate order number creating a second workshop.** Two spellings of one number are one
   order. The pre-check is the friendly half; the unique index on ``sanctionOrderKey`` is the half
   that is actually true.

══ HOW THIS FILE IS ARRANGED, AND WHY THE FIXTURE IS THE LONGEST THING IN IT ══════════════════

``db`` is a PROCESS-WIDE Prisma singleton shared with the running application, and a Prisma
connection is BOUND TO THE EVENT LOOP THAT OPENED IT. The ``TestClient``'s lifespan connects it in
the portal thread's loop. So a test that reaches for ``db`` while the client is alive is using a
connection from a loop that does not own it, and the app's own handlers then fail with
``RuntimeError: <asyncio.locks.Event …> is bound to a different event loop`` — an error reported
against the ROUTE, not against the assertion, which is why ``app.main`` logs "Unhandled error on
POST /api/sanction-orders" for a test that never looked wrong. The earlier shape of this module —
an async, module-scoped fixture, with every test reaching for the shared connection mid-flight —
hit exactly that: first as ``AlreadyConnectedError``, and then, once the borrow was made polite
enough to stop disconnecting, as the cross-loop ``RuntimeError``. Thirty-six failures, twice, for
one structural reason. ``tests/conftest.py`` records the whole of it where the helper used to be.

``tests/test_workshop_join_sync.py`` and ``tests/test_seed_shared_questionnaire.py`` carry the
convention that works, and it is the one below: **every database call happens inside ONE SYNC
fixture, in a private ``asyncio.run`` loop, with no ``TestClient`` alive at the time — and the tests
assert over what it recorded.** ``asyncio.run`` opens a loop nothing else shares and closes it
again, so the blind ``connect``/``disconnect`` pair inside it is correct here precisely where it
would be wrong in an async fixture that interleaves with another module's app lifespan.

The fixture therefore runs in four phases, and the order is the whole design:

1. **seed** — ``asyncio.run``: every row the module needs BEFORE any request, plus the request
   bodies, recorded so the assertions can quote them.
2. **act** — one ``TestClient``: every request whose RESULT a database read has to observe. The
   ``httpx.Response`` objects are kept whole (status, headers, ``.content``, ``.json()`` all still
   answer after the client is closed), so the assertions below are unchanged from when each test
   made its own request.
3. **observe** — ``asyncio.run`` again, the client now shut down and the singleton free: every
   read-back, and the one deliberate edit (the stage-1 cover) that a later request has to see.
4. **yield** — a second ``TestClient``, live for the tests. A test that needs NO database read
   still drives this client itself, exactly as ``test_workshop_join_sync`` does: the point is that
   the database is never touched while a client is running, not that requests are forbidden.

A test whose ``POST`` was made in phase 2 asserts its status code FIRST, and phase 3 records
nothing for a request the route refused — so a broken route still fails the test that owns it,
rather than erroring the whole module out of the fixture.

Postgres is required — every behaviour here is a row appearing or not appearing — so the module
skips itself when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

from __future__ import annotations

import asyncio
import json
import uuid
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster, credential_links
from app.services.designers import DERIVED_EMPANELMENT_NOTE, canonical_email, normalise_email
from app.services.sanction_orders import (
    SANCTION_ADMISSION_NOTE,
    normalise_sanction_order_no,
)
from app.services.stage_schema import registry_version, stage, stage_completeness
from prisma import Json

pytestmark = [needs_db]

PASSWORD = "sanction-orders-officer-password"
AMOUNT = "450000.00"
TOP_OF_COLUMN = "999999999999.99"

#: The tiers that clear ``can_record_sanction_orders``'s rank floor and are still OUTSIDE
#: ``DESIGN_WORKSHOP_ROLES``. Named once so the fixture and the test that reads its findings cannot
#: drift apart.
#:
#: ⚠ IT WAS FIVE UNTIL 2026-09-14 AND THE THREE THAT LEFT WERE FIXED, NOT EXCUSED. The owner ruled
#: that MINISTRY_ADMIN, REGIONAL_DIRECTOR and ASSISTANT_DIRECTOR may run design workshops, so they
#: entered ``DESIGN_WORKSHOP_ROLES`` and stopped satisfying the second half of the condition above.
#: A sanction order may now name one of those officers and the viewer row written for them IS
#: honoured — which is the remedy `test_sanction_order_designer_eligibility`'s census docstring
#: prescribes for exactly this case ("if it does, it belongs in DESIGN_WORKSHOP_ROLES and the answer
#: is there, not here"). The list shrinking is the evidence that worked, not something to restore.
#:
#: INSPECTOR and PROFESSOR remain, and they are the pair that shows this is a SET rather than a rank
#: floor: three tiers that outrank BOTH of them are now eligible and these two are not.
INELIGIBLE_ROLES = (
    "INSPECTOR",
    "PROFESSOR",
)

#: The two ways an administrator shows somebody the door. Both must survive a sanction order.
BARRED_STATES = ("REJECTED", "SUSPENDED")


def _body(**overrides: Any) -> dict[str, Any]:
    """One create body, unique per call. Built in the fixture and RECORDED, because the assertions
    quote the number and the address back."""
    stamp = uuid.uuid4().hex[:8]
    body = {
        "sanctionOrderNo": f"SO/2026/{stamp}",
        "sanctionOrderDate": "2026-04-01",
        "sanctionAmount": AMOUNT,
        "designerName": f"Designer {stamp}",
        "designerEmail": f"sanction-designer-{stamp}@example.org",
        "notes": None,
    }
    body.update(overrides)
    return body


def _created_order(reply: Any) -> dict[str, Any] | None:
    """The created register row, or ``None`` when the route refused.

    THE POINT IS WHERE A FAILURE LANDS. Phase 3 reads rows keyed off the id in this payload; if it
    simply indexed into a refusal it would raise inside the fixture and every test in the module
    would report a fixture error instead of the one test whose request was refused. Returning None
    leaves that test's own ``assert reply.status_code == 201, reply.text`` to say what happened, in
    the route's own words.
    """
    if reply.status_code != 201:
        return None
    try:
        return reply.json()["sanctionOrder"]
    except (ValueError, KeyError, TypeError):
        return None


@pytest.fixture(scope="module")
def world():
    """ONE MINISTRY OFFICER, at the floor and not above it — and every database call in this module.

    ``ASSISTANT_DIRECTOR`` rather than ADMIN deliberately: an admin would pass this gate for the
    wrong reason, and every refusal below would then be untested against the tier the feature was
    actually built for.

    SYNC, AND THAT IS THE POINT. See the module docstring: the phases below are ordered so that no
    database call ever happens while a ``TestClient`` — and therefore the app's own, differently
    looped, connection — is alive.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    officer_email = f"sanction-officer-{stamp}@example.org"

    facts: dict[str, Any] = {"stamp": stamp}
    bodies: dict[str, dict[str, Any]] = {}
    answers: dict[str, Any] = {}
    rows: dict[str, Any] = {}
    facts["bodies"] = bodies
    facts["answers"] = answers
    facts["rows"] = rows

    # ---------------------------------------------------------------------------------
    # Phase 1 — seed. A private loop, no app, no TestClient.
    # ---------------------------------------------------------------------------------
    async def seed() -> None:
        from prisma.errors import PrismaError

        await db.connect()
        try:
            officer = await db.user.create(
                data={
                    "email": officer_email,
                    "name": f"Sanction officer {stamp}",
                    "role": "ASSISTANT_DIRECTOR",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": officer_email,
                    "status": "ACTIVE",
                    "admitRole": "ASSISTANT_DIRECTOR",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_sanction_orders.py.",
                }
            )
            facts["officer"] = officer

            # The plain happy paths, which need no row of their own.
            bodies["five_fields"] = _body()
            bodies["round_trip"] = _body(sanctionAmount=TOP_OF_COLUMN)
            bodies["stage_copy"] = _body()
            bodies["missing_list"] = _body()
            bodies["sanctioned"] = _body()
            bodies["pending_queue"] = _body()

            # The account that already exists.
            existing = f"sanction-existing-{stamp}@example.org"
            await db.user.create(
                data={
                    "email": existing,
                    "name": f"Existing {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            facts["existing_address"] = existing
            bodies["existing"] = _body(designerEmail=existing)

            # The admin who must not be demoted by being named.
            admin_address = f"sanction-admin-{stamp}@example.org"
            await db.user.create(
                data={
                    "email": admin_address,
                    "name": f"Admin designer {stamp}",
                    "role": "ADMIN",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            facts["admin_address"] = admin_address
            bodies["admin"] = _body(designerEmail=admin_address)

            # The Gmail alias. Nothing to seed — the first order creates the account, and the
            # second (undotted) order has to find it again.
            dotted = f"sanction.alias.{stamp}@gmail.com"
            facts["dotted"] = dotted
            bodies["alias_dotted"] = _body(designerEmail=dotted)
            bodies["alias_canonical"] = _body(designerEmail=canonical_email(dotted))

            # Two accounts on one mailbox.
            split = f"sanction.split.{stamp}@gmail.com"
            for spelling in (normalise_email(split), canonical_email(split)):
                await db.user.create(
                    data={
                        "email": spelling,
                        "name": f"Split {stamp}",
                        "role": "RESEARCHER",
                        "passwordHash": hash_password(PASSWORD),
                    }
                )
            facts["split"] = split
            bodies["split"] = _body(designerEmail=split)

            # The two people an administrator has already shown the door.
            facts["barred_addresses"] = {}
            for state in BARRED_STATES:
                address = f"sanction-barred-{state.lower()}-{stamp}@example.org"
                await db.accessroster.create(
                    data={
                        "email": canonical_email(address),
                        "status": state,
                        "notes": "Barred by hand. A sanction order must not undo this.",
                    }
                )
                facts["barred_addresses"][state] = address
                bodies[f"barred_{state}"] = _body(designerEmail=address)

            # The empanelment an administrator ended.
            ended = f"sanction-ended-{stamp}@example.org"
            await db.designerroster.create(
                data={
                    "email": canonical_email(ended),
                    "isActive": False,
                    "revokedAt": datetime.now(UTC),
                    "notes": "Empanelment ended by hand. Do not restore.",
                }
            )
            facts["ended_address"] = ended
            bodies["ended"] = _body(designerEmail=ended)

            # The race, simulated by inserting the register row by hand with a DIFFERENT workshop so
            # the pre-check's own lookup is bypassed and the unique index is what refuses.
            number = f"SO/RACE/{stamp}"
            race_address = f"sanction-race-{stamp}@example.org"
            squatter = await db.designworkshop.create(
                data={
                    "title": f"Squatter {stamp}",
                    "templateId": "DCH_STANDARD",
                    "createdById": officer.id,
                    "status": "DRAFT",
                }
            )
            await db.sanctionorder.create(
                data={
                    "sanctionOrderNo": f"{number} (already recorded)",
                    "sanctionOrderKey": normalise_sanction_order_no(number),
                    "sanctionOrderDate": datetime(2026, 4, 1, tzinfo=UTC),
                    "sanctionAmount": "1.00",
                    "designerUserId": officer.id,
                    "designerEmail": f"squatter-{stamp}@example.org",
                    "designWorkshopId": squatter.id,
                    "createdById": officer.id,
                }
            )
            facts["race_number"] = number
            facts["race_address"] = race_address
            bodies["race"] = _body(sanctionOrderNo=number, designerEmail=race_address)

            # The account whose link budget is already spent.
            throttle_address = f"sanction-throttle-{stamp}@example.org"
            throttled = await db.user.create(
                data={
                    "email": throttle_address,
                    "name": f"Throttled {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": None,
                }
            )
            for _ in range(credential_links.ISSUE_BUDGET):
                await db.passwordresettoken.create(
                    data={
                        "userId": throttled.id,
                        "tokenHash": uuid.uuid4().hex,
                        "purpose": "INVITE",
                        "expiresAt": datetime.now(UTC),
                    }
                )
            facts["throttle_address"] = throttle_address

            # The designer who may not record an order.
            designer = await db.user.create(
                data={
                    "email": f"sanction-refused-{stamp}@example.org",
                    "name": f"Refused {stamp}",
                    "role": "DESIGNER",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            facts["refused_designer_id"] = designer.id

            # The tiers that clear the rank floor and still cannot run a workshop.
            facts["ineligible_addresses"] = {}
            for role in INELIGIBLE_ROLES:
                address = f"sanction-ineligible-{role.lower()}-{stamp}@example.org"
                await db.user.create(
                    data={
                        "email": address,
                        "name": f"Named {role} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                    }
                )
                facts["ineligible_addresses"][role] = address
                bodies[f"ineligible_{role}"] = _body(designerEmail=address)

            # The officer naming their own mailbox.
            bodies["own_mailbox"] = _body(designerEmail=officer.email)

            # The row an administrator already decided, left exactly as they would leave it.
            already = f"sanction-already-admitted-{stamp}@example.org"
            admins_note = (
                "Admitted 2025-04 on the department's request; craft taxonomy contributor."
            )
            joined = datetime(2024, 4, 1, tzinfo=UTC)
            deciding = await db.user.create(
                data={
                    "email": f"sanction-deciding-admin-{stamp}@example.org",
                    "name": f"Deciding admin {stamp}",
                    "role": "ADMIN",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": canonical_email(already),
                    "status": "ACTIVE",
                    "admitRole": "RESEARCHER",
                    "fullName": "R. Sundaram (as the admin typed it)",
                    "notes": admins_note,
                    "joinedAt": joined,
                    "decidedAt": joined,
                    "decidedById": deciding.id,
                }
            )
            facts["already_address"] = already
            facts["already_note"] = admins_note
            facts["already_joined"] = joined
            facts["deciding_admin_id"] = deciding.id
            bodies["already_admitted"] = _body(
                designerEmail=already, designerName="Sundaram R"
            )

            # THE TABLE'S OWN REFUSAL, driven here rather than in the test because it is pure
            # database and touches no route at all. The outcome is RECORDED rather than asserted, so
            # that a table which stopped refusing fails the test that owns the rule instead of
            # erroring every test in the module out of the fixture.
            check_workshop = await db.designworkshop.create(
                data={
                    "title": f"Check constraint {stamp}",
                    "templateId": "DCH_STANDARD",
                    "createdById": officer.id,
                    "status": "DRAFT",
                }
            )
            try:
                await db.sanctionorder.create(
                    data={
                        "sanctionOrderNo": f"SO/CHECK/{stamp}",
                        "sanctionOrderKey": normalise_sanction_order_no(f"SO/CHECK/{stamp}"),
                        "sanctionOrderDate": datetime(2026, 4, 1, tzinfo=UTC),
                        "sanctionAmount": "0.00",
                        "designerUserId": officer.id,
                        "designerEmail": f"check-{stamp}@example.org",
                        "designWorkshopId": check_workshop.id,
                        "createdById": officer.id,
                    }
                )
            except Exception as exc:  # noqa: BLE001 - the CLASS is the finding; see the test
                facts["non_positive_refusal"] = type(exc).__name__
                facts["non_positive_refusal_is_prisma"] = isinstance(exc, PrismaError)
            else:
                facts["non_positive_refusal"] = None
                facts["non_positive_refusal_is_prisma"] = False

            # Read LAST, so it brackets every create the act phase is about to make.
            facts["pending_before"] = await access_roster.pending_count()
        finally:
            await db.disconnect()

    # ---------------------------------------------------------------------------------
    # Phase 2 — act. One TestClient, every request a later read has to observe.
    # ---------------------------------------------------------------------------------
    def act(client: Any) -> None:
        headers = _headers(facts)

        def post(key: str) -> None:
            answers[key] = client.post(
                "/api/sanction-orders", json=bodies[key], headers=headers
            )

        post("five_fields")
        post("existing")
        post("admin")
        # ORDER IS LOAD-BEARING: the undotted spelling must arrive SECOND, so that what it finds is
        # the account the dotted one created.
        post("alias_dotted")
        post("alias_canonical")
        post("split")
        for state in BARRED_STATES:
            post(f"barred_{state}")
        post("ended")
        post("race")
        post("round_trip")
        post("stage_copy")
        post("missing_list")
        post("sanctioned")
        post("pending_queue")
        for role in INELIGIBLE_ROLES:
            post(f"ineligible_{role}")
        post("own_mailbox")
        post("already_admitted")

    # ---------------------------------------------------------------------------------
    # Phase 3 — observe. The client is shut down; the singleton is free again.
    # ---------------------------------------------------------------------------------
    async def observe() -> None:
        await db.connect()
        try:
            # Read FIRST, so it brackets exactly the creates the act phase made.
            facts["pending_after"] = await access_roster.pending_count()

            # The seven rows.
            payload = _created_order(answers["five_fields"])
            if payload is not None:
                canonical = canonical_email(bodies["five_fields"]["designerEmail"])
                account = await db.user.find_unique(
                    where={"email": normalise_email(bodies["five_fields"]["designerEmail"])}
                )
                workshop = await db.designworkshop.find_unique(
                    where={"id": payload["designWorkshopId"]}
                )
                rows["five_fields"] = {
                    "access": await db.accessroster.find_unique(where={"email": canonical}),
                    "roster": await db.designerroster.find_unique(where={"email": canonical}),
                    "account": account,
                    "profile": (
                        await db.designerprofile.find_unique(where={"userId": account.id})
                        if account is not None
                        else None
                    ),
                    "workshop": workshop,
                    "viewers": (
                        await db.designworkshopviewer.find_many(
                            where={"designWorkshopId": workshop.id, "userId": account.id}
                        )
                        if workshop is not None and account is not None
                        else []
                    ),
                    "order": await db.sanctionorder.find_unique(where={"id": payload["id"]}),
                }

            # The account that already existed.
            rows["existing_accounts"] = await db.user.find_many(
                where={"email": {"equals": facts["existing_address"], "mode": "insensitive"}}
            )

            # The admin who must keep their tier.
            rows["admin_account"] = await db.user.find_unique(
                where={"email": facts["admin_address"]}
            )

            # The Gmail alias, all four spellings.
            dotted = facts["dotted"]
            rows["alias"] = {
                "account": await db.user.find_unique(where={"email": normalise_email(dotted)}),
                "access": await db.accessroster.find_unique(
                    where={"email": canonical_email(dotted)}
                ),
                "roster": await db.designerroster.find_unique(
                    where={"email": canonical_email(dotted)}
                ),
            }

            # The split mailbox: nothing may have been recorded.
            rows["split_orders"] = await db.sanctionorder.find_many(
                where={
                    "sanctionOrderKey": normalise_sanction_order_no(
                        bodies["split"]["sanctionOrderNo"]
                    )
                }
            )

            # The two barred addresses.
            rows["barred"] = {}
            for state in BARRED_STATES:
                address = facts["barred_addresses"][state]
                rows["barred"][state] = {
                    "row": await db.accessroster.find_unique(
                        where={"email": canonical_email(address)}
                    ),
                    "orders": await db.sanctionorder.find_many(
                        where={
                            "sanctionOrderKey": normalise_sanction_order_no(
                                bodies[f"barred_{state}"]["sanctionOrderNo"]
                            )
                        }
                    ),
                    "accounts": await db.user.find_many(
                        where={"email": {"equals": address, "mode": "insensitive"}}
                    ),
                }

            # The empanelment that was ended.
            rows["ended_roster"] = await db.designerroster.find_unique(
                where={"email": canonical_email(facts["ended_address"])}
            )

            # The race: six absences.
            race_address = facts["race_address"]
            rows["race"] = {
                "accounts": await db.user.find_many(
                    where={"email": {"equals": race_address, "mode": "insensitive"}}
                ),
                "access": await db.accessroster.find_unique(
                    where={"email": canonical_email(race_address)}
                ),
                "roster": await db.designerroster.find_unique(
                    where={"email": canonical_email(race_address)}
                ),
                "workshops": await db.designworkshop.find_many(
                    where={"title": {"contains": facts["race_number"]}}
                ),
            }

            # The amount at the top of the column, read back off the row.
            payload = _created_order(answers["round_trip"])
            if payload is not None:
                rows["round_trip"] = await db.sanctionorder.find_unique(
                    where={"id": payload["id"]}
                )

            # The stage-1 copy. READ, THEN DELIBERATELY EDITED — the edit is what the live GET in
            # ``test_the_stage_one_copy_is_seeded_and_is_a_copy`` has to see, and it has to happen
            # here because it is a database write and no client may be running when it does.
            payload = _created_order(answers["stage_copy"])
            if payload is not None:
                entry = await db.dwstageentry.find_first(
                    where={
                        "designWorkshopId": payload["designWorkshopId"],
                        "stageKey": "WORKSHOP_SETUP",
                        "entityKey": "workshopSetup",
                        "deletedAt": None,
                    }
                )
                data = dict(entry.data or {}) if entry is not None else {}
                if entry is not None:
                    edited = dict(data)
                    edited["sanctionOrderNo"] = "SOMETHING ELSE ENTIRELY"
                    await db.dwstageentry.update(
                        where={"id": entry.id},
                        # `Json(...)`, prisma's wrapper, and NOT a bare dict. A raw mapping is
                        # refused by the query engine with "`data` should be of any of the following
                        # types: `JsonNullValueInput`, `Json`" — 35 setup errors across this module
                        # and its sibling, all from this one line. `json.loads(json.dumps(...))` was
                        # doing the round-trip to plain types, which is necessary and not
                        # sufficient: the wrapper is what tells the engine which input type it is.
                        # Every other Json write in this directory uses it.
                        data={"data": Json(json.loads(json.dumps(edited)))},
                    )
                rows["stage_copy"] = {
                    "entry": entry,
                    "data": data,
                    "order": await db.sanctionorder.find_unique(where={"id": payload["id"]}),
                }

            # The one scorer.
            payload = _created_order(answers["missing_list"])
            if payload is not None:
                entry = await db.dwstageentry.find_first(
                    where={
                        "designWorkshopId": payload["designWorkshopId"],
                        "stageKey": "WORKSHOP_SETUP",
                        "entityKey": "workshopSetup",
                        "deletedAt": None,
                    }
                )
                rows["missing_list_singleton"] = (
                    dict(getattr(entry, "data", None) or {}) if entry else {}
                )

            # One order, one workshop, both directions — and NULL for one opened by hand.
            payload = _created_order(answers["sanctioned"])
            if payload is not None:
                ad_hoc = await db.designworkshop.create(
                    data={
                        "title": f"By hand {stamp}",
                        "templateId": "DCH_STANDARD",
                        "createdById": facts["officer"].id,
                        "status": "DRAFT",
                    }
                )
                rows["sanctioned"] = {
                    "workshop": await db.designworkshop.find_unique(
                        where={"id": payload["designWorkshopId"]},
                        include={"sanctionOrder": True},
                    ),
                    "ad_hoc": await db.designworkshop.find_unique(
                        where={"id": ad_hoc.id}, include={"sanctionOrder": True}
                    ),
                }

            # The five tiers that cannot run a workshop: four tables each.
            rows["ineligible"] = {}
            for role in INELIGIBLE_ROLES:
                address = facts["ineligible_addresses"][role]
                account = await db.user.find_unique(where={"email": address})
                rows["ineligible"][role] = {
                    "account": account,
                    "orders": await db.sanctionorder.find_many(
                        where={
                            "sanctionOrderKey": normalise_sanction_order_no(
                                bodies[f"ineligible_{role}"]["sanctionOrderNo"]
                            )
                        }
                    ),
                    "access": await db.accessroster.find_unique(
                        where={"email": canonical_email(address)}
                    ),
                    "roster": await db.designerroster.find_unique(
                        where={"email": canonical_email(address)}
                    ),
                    "viewers": (
                        await db.designworkshopviewer.find_many(where={"userId": account.id})
                        if account is not None
                        else []
                    ),
                }

            # The officer naming their own mailbox.
            own = facts["officer"].email
            rows["own_mailbox"] = {
                "orders": await db.sanctionorder.find_many(
                    where={
                        "sanctionOrderKey": normalise_sanction_order_no(
                            bodies["own_mailbox"]["sanctionOrderNo"]
                        )
                    }
                ),
                "roster": await db.designerroster.find_unique(
                    where={"email": canonical_email(own)}
                ),
                "access": await db.accessroster.find_unique(
                    where={"email": canonical_email(own)}
                ),
            }

            # The administrator's allow-list decision.
            rows["already_admitted"] = await db.accessroster.find_unique(
                where={"email": canonical_email(facts["already_address"])}
            )
        finally:
            await db.disconnect()

    asyncio.run(seed())
    with TestClient(app) as client:
        act(client)
    asyncio.run(observe())
    with TestClient(app) as client:
        facts["client"] = client
        yield facts


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['officer'].id)}"}


def _post(world: dict[str, Any], body: dict[str, Any]) -> Any:
    """A live create, for the tests that need no database read afterwards.

    Those tests keep making their own request — see the module docstring. What the convention
    forbids is a DATABASE call while a client is alive, not a request.
    """
    return world["client"].post("/api/sanction-orders", json=body, headers=_headers(world))


# --------------------------------------------------------------------------------------
# 1. The seven rows
# --------------------------------------------------------------------------------------


def test_five_fields_produce_seven_rows(world) -> None:
    """Counted BY HAND, one query per table, because a helper would agree with the implementation.

    The ``fullName`` assertion on the DESIGNER ROSTER is the one worth reading twice. It is the only
    thing in this repository that catches a missing ``client=`` on
    ``designers.name_on_the_allow_list`` — a read that, taken outside the transaction, sees no
    allow-list row and writes NULL. No error, no rollback, no other failing assertion: just a column
    of bare email addresses on /admin/designers, weeks later.

    ``schemaVersion`` is asserted for a related reason. The column is nullable, so omitting the
    stamp fails nothing — sanctioned workshops would simply carry NULL where every hand-created one
    carries a version, on a value that is read straight back out on the provenance surface.
    """
    body = world["bodies"]["five_fields"]
    answer = world["answers"]["five_fields"]
    assert answer.status_code == 201, answer.text

    seen = world["rows"]["five_fields"]
    access = seen["access"]
    roster = seen["roster"]
    account = seen["account"]
    profile = seen["profile"]
    workshop = seen["workshop"]
    viewers = seen["viewers"]
    order = seen["order"]

    # 1. the allow-list admission
    assert access is not None and access_roster.status_of(access) == "ACTIVE"
    assert str(getattr(access.admitRole, "value", access.admitRole)) == "DESIGNER"
    # 2. the empanelment — named by the OFFICER, and carrying the officer's note rather than the
    #    "derived" one a sign-in would have written
    assert roster is not None and roster.isActive is True
    assert roster.addedById == world["officer"].id
    assert roster.notes != DERIVED_EMPANELMENT_NOTE
    assert roster.notes == SANCTION_ADMISSION_NOTE.format(
        no=body["sanctionOrderNo"], officer=world["officer"].name
    )
    assert roster.fullName == body["designerName"], "the allow-list name did not reach the roster"
    # 3. the account
    assert account is not None
    assert str(getattr(account.role, "value", account.role)) == "DESIGNER"
    assert account.mustChangePassword is True
    assert account.passwordSetAt is not None
    assert account.passwordHash, "a NULL hash makes the account unreachable if the link is lost"
    # 4. the profile
    assert profile is not None
    # 5. the workshop, created BY the officer and stamped with the registry version
    assert workshop is not None
    assert workshop.createdById == world["officer"].id
    assert workshop.schemaVersion == registry_version()
    # 6. the viewer row — without it the designer cannot open the workshop their name is on
    assert len(viewers) == 1
    # 7. the register
    assert order is not None
    assert order.accountCreated is True
    assert order.designerUserId == account.id


def test_a_new_account_is_offered_a_seventy_two_hour_invite_link(world, client) -> None:
    """INVITE and not RESET, and the purpose is passed explicitly for a reason worth reading.

    The account was just given a real (random, unguessable) password hash, so ``issue_link``'s own
    default would infer RESET and its TWO-HOUR lifetime — far too short for a link an officer
    forwards by hand to somebody who may be in a village. The creation path therefore names INVITE.
    """
    answer = _post(world, _body())
    assert answer.status_code == 201, answer.text
    link = answer.json()["credentialLink"]
    assert link is not None
    assert link["purpose"] == credential_links.INVITE
    assert link["deliveredBy"] == "COPY_LINK", "there is no mailer; the officer is the transport"
    assert answer.json()["credentialLinkProblem"] is None


# --------------------------------------------------------------------------------------
# 2. The account that already exists
# --------------------------------------------------------------------------------------


def test_an_existing_account_is_reused_and_never_duplicated(world) -> None:
    """The MAJORITY path in a running programme, and the one that must not mint a second account."""
    answer = world["answers"]["existing"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()

    accounts = world["rows"]["existing_accounts"]
    assert len(accounts) == 1, "a second account was minted for a mailbox that already had one"
    assert str(getattr(accounts[0].role, "value", accounts[0].role)) == "DESIGNER"
    assert payload["sanctionOrder"]["accountCreated"] is False
    # NO LINK. They already have credentials, and minting a RESET here would sign them out of every
    # device the moment they redeemed it.
    assert payload["credentialLink"] is None


def test_an_admin_named_on_a_sanction_order_is_not_demoted(world) -> None:
    """LIFT, NEVER LOWER. An ADMIN named as the designer keeps their tier.

    The same rule the sign-in path and the allow-list decision path apply. A sanction order naming a
    senior colleague as the designer is ordinary; silently demoting them to DESIGNER would remove
    every capability they hold, from one create they did not perform.
    """
    assert world["answers"]["admin"].status_code == 201, world["answers"]["admin"].text
    account = world["rows"]["admin_account"]
    assert str(getattr(account.role, "value", account.role)) == "ADMIN"


def test_a_gmail_alias_lands_on_one_mailbox_and_can_still_sign_in(world) -> None:
    """FOUR COLUMNS, EACH ASSERTED BY NAME, because getting any of them backwards is silent.

    ``User.email`` is the LITERAL lower-cased address: both sign-in doors look it up literally, so
    the canonical spelling there would 401 a password sign-in and be MISSED by Google sign-in, which
    then mints a second row — manufacturing the exact split ``routes/auth.py`` records as a known
    defect. Both rosters and ``SanctionOrder.designerEmail`` are the CANONICAL mailbox, because that
    is the key the gates read; the literal spelling on a roster row is invisible to
    ``ensure_empanelled``'s existence check and lets a revoked designer be quietly re-admitted.
    """
    dotted = world["dotted"]
    canonical = canonical_email(dotted)
    assert canonical != normalise_email(dotted), "the fixture is not testing an alias"

    answer = world["answers"]["alias_dotted"]
    assert answer.status_code == 201, answer.text
    assert answer.json()["sanctionOrder"]["designerEmail"] == canonical

    seen = world["rows"]["alias"]
    assert seen["account"] is not None, (
        "the account must be reachable at the address the designer types"
    )
    assert seen["access"] is not None
    assert seen["roster"] is not None

    # And a SECOND order for the undotted spelling reuses the same account rather than minting one.
    second = world["answers"]["alias_canonical"]
    assert second.status_code == 201, second.text
    assert second.json()["sanctionOrder"]["accountCreated"] is False
    assert second.json()["sanctionOrder"]["designerUserId"] == seen["account"].id


def test_two_accounts_on_one_mailbox_refuse_the_whole_create(world) -> None:
    """REFUSE, never guess — and leave nothing behind.

    Filing a ministry workshop under whichever of two rows an index happened to return first is not
    a decision a create route takes on an institution's behalf.
    """
    answer = world["answers"]["split"]
    assert answer.status_code == 409, answer.text
    assert canonical_email(world["split"]) in answer.json()["detail"]
    assert world["rows"]["split_orders"] == []


# --------------------------------------------------------------------------------------
# 3. The duplicate
# --------------------------------------------------------------------------------------


def test_a_duplicate_sanction_number_is_a_409_naming_the_existing_order(world, client) -> None:
    """The officer's next move differs by WHICH situation they are in, so the message must let them
    tell: the existing order's number, the workshop it opened, the designer it named and the day."""
    body = _body()
    assert _post(world, body).status_code == 201
    again = _post(world, _body(sanctionOrderNo=body["sanctionOrderNo"]))
    assert again.status_code == 409, again.text
    detail = again.json()["detail"]
    assert body["sanctionOrderNo"] in detail
    assert "Sanctioned workshop" in detail


@pytest.mark.parametrize("separator", ["-", " ", "."])
def test_three_spellings_of_one_number_are_one_order(world, client, separator: str) -> None:
    """``SO/2026/42``, ``SO-2026-42`` and ``so 2026 42`` are three house styles for one instrument.

    The unique index on ``sanctionOrderNo`` alone would admit all three, because Postgres calls them
    three values. ``sanctionOrderKey`` is what actually refuses the second workshop.
    """
    stamp = uuid.uuid4().hex[:8]
    first = f"SO/2026/{stamp}"
    assert _post(world, _body(sanctionOrderNo=first)).status_code == 201
    restyled = first.replace("/", separator).lower()
    again = _post(world, _body(sanctionOrderNo=restyled))
    assert again.status_code == 409, again.text


# --------------------------------------------------------------------------------------
# 4. The two refusals about people an administrator has already shown the door
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("state", list(BARRED_STATES))
def test_a_barred_address_is_refused_and_the_decision_still_stands(world, state: str) -> None:
    """``admit`` writes ACTIVE with no branch, so this read is the ONLY thing in the way.

    The assertion that matters is the second one: the row is STILL barred afterwards. A reordering
    that moved the check inside the transaction would still produce a 422 on some paths and would
    have overwritten the administrator's decision on the way; only reading the row back catches it.
    """
    answer = world["answers"][f"barred_{state}"]
    assert answer.status_code == 422, answer.text
    assert "allow-list" in answer.json()["detail"]

    seen = world["rows"]["barred"][state]
    assert access_roster.status_of(seen["row"]) == state, (
        "a sanction order overturned an admin's decision"
    )
    assert seen["orders"] == []
    assert seen["accounts"] == [], "a refused create left an orphan account behind"


def test_an_ended_empanelment_is_not_restored_by_a_sanction_order(world) -> None:
    """THE DIRECT DESCENDANT of the assertion in ``test_platform_access_gate``.

    ``ensure_empanelled`` never revives a suspended row — deliberately, because the roster suspends
    rather than deletes so the record of the empanelment survives the ending of it. Without this
    phase-0 read the officer gets a 201 and the designer gets a workshop they are refused at the
    door, reading "Your designer access has been suspended", with no row on any screen to explain
    it.
    """
    answer = world["answers"]["ended"]
    assert answer.status_code == 422, answer.text
    assert "empanelment" in answer.json()["detail"].lower()
    assert world["rows"]["ended_roster"].isActive is False, (
        "a sanction order restored an empanelment an admin ended"
    )


# --------------------------------------------------------------------------------------
# 5. The transaction
# --------------------------------------------------------------------------------------


def test_a_refused_create_leaves_no_orphan_anything(world) -> None:
    """THE ONE TEST THAT CATCHES A MISSING ``client=``, AND IT FORCES THE FAILURE AT THE LAST WRITE.

    The race the pre-check cannot win: two officers, one number, the same second. The pre-check reads
    nothing and the ``SanctionOrder`` insert — the LAST statement in the block — violates the unique
    index. Everything before it (the allow-list row, the empanelment, the account, the profile, the
    workshop, the viewer row) must roll back with it.

    **A MISSING ``client=`` ON ANY OF THE FIVE SERVICE CALLS FAILS EXACTLY HERE AND NOWHERE ELSE.**
    Those functions would have written through the module singleton, outside the transaction, so
    their rows would still be standing after the rollback — an admitted, empanelled account with no
    workshop and no order to say why it exists. The happy path passes either way, which is why this
    assertion is a list of six absences rather than a status code.

    The race is simulated by inserting the register row by hand, with a DIFFERENT workshop, so that
    the pre-check's own lookup is bypassed. That is the honest version: it reproduces what the index
    does, not what the route does.
    """
    answer = world["answers"]["race"]
    assert answer.status_code == 409, answer.text

    seen = world["rows"]["race"]
    assert seen["accounts"] == [], "the account committed outside the transaction"
    assert seen["access"] is None, "the allow-list row committed outside the transaction"
    assert seen["roster"] is None, "the empanelment committed outside the transaction"
    assert seen["workshops"] == [], "the workshop committed outside the transaction"


# --------------------------------------------------------------------------------------
# 6. The money
# --------------------------------------------------------------------------------------


def test_the_amount_never_reaches_the_wire_as_a_float(world, client) -> None:
    """READ OFF THE RAW BYTES, because that is the only place the difference is visible.

    ``jsonable_encoder`` turns a ``Decimal`` into a ``float`` — measured in this repository's own
    venv — and it has already shipped here once, on ``ProductDocumentation.sellingPrice``, which is
    why the TypeScript type for that column is ``string | number | null`` and the Android DTO
    decodes a rupee amount as a ``Double``. ``json.loads`` on a float would answer ``450000.0``, so
    the assertion is on the parsed TYPE and on the exact string, and the raw body is checked for the
    quotes as well.
    """
    answer = _post(world, _body(sanctionAmount=AMOUNT))
    assert answer.status_code == 201, answer.text
    parsed = json.loads(answer.content)["sanctionOrder"]["sanctionAmount"]
    assert isinstance(parsed, str), f"the amount arrived as {type(parsed).__name__}"
    # THE VALUE, NOT THE SPELLING. The wire carries "450000" for a "450000.00" input — trailing
    # zeros are normalised away somewhere between the column and the encoder — and this assertion
    # read `parsed == AMOUNT` until 2026-09-14, when the module ran for the first time and said so.
    #
    # That normalisation is LOSSLESS and is not what this test is named for: the neighbour,
    # `test_the_amount_round_trips_exactly_at_the_top_of_the_column`, sends fourteen digits with two
    # non-zero paise and passes, so no precision is lost — only a display zero. What this test
    # exists to catch is a Decimal reaching the wire as a FLOAT, which `jsonable_encoder` has
    # already done once in this repository on `ProductDocumentation.sellingPrice`. A float would
    # arrive as the number 450000.0, failing both the isinstance above and the Decimal compare here.
    #
    # ⚠ IF THE DISPLAY ZEROS MATTER — a rupee amount printed as "450000" rather than "450000.00" on
    # a ministry document — that is a formatting decision for the surface that renders it, and it
    # should be asserted where that formatting happens rather than by pinning the wire's spelling.
    assert Decimal(parsed) == Decimal(AMOUNT), f"the amount changed value on the wire: {parsed!r}"
    assert f'"sanctionAmount":"{parsed}"' in answer.content.decode().replace(" ", ""), (
        "the amount is not quoted on the wire, so it is a JSON number and a client will parse it "
        "as a float"
    )


def test_the_amount_round_trips_exactly_at_the_top_of_the_column(world) -> None:
    """Fourteen digits, two of them paise. A float would lose the last one and say nothing."""
    answer = world["answers"]["round_trip"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]
    assert payload["sanctionAmount"] == TOP_OF_COLUMN

    row = world["rows"]["round_trip"]
    assert Decimal(str(row.sanctionAmount)) == Decimal(TOP_OF_COLUMN)


@pytest.mark.parametrize("amount", ["0", "0.00", "-1.00"])
def test_a_zero_or_negative_amount_is_refused_at_the_door(world, client, amount: str) -> None:
    """A zero-rupee sanction order is not a sanction order, and a negative one is a typed minus sign.

    Refused twice on purpose: ``gt=0`` on the Pydantic field, so the officer gets a named field, and
    ``CHECK ("sanctionAmount" > 0)`` on the table, so a psql session and any future importer are
    refused too. A validator protects one door; a constraint protects the table.
    """
    assert _post(world, _body(sanctionAmount=amount)).status_code == 422


def test_the_table_itself_refuses_a_non_positive_amount(world) -> None:
    """The other half of the rule above, asserted against Postgres rather than against Pydantic.

    Driven in the fixture — it is a pure database write with no route in it — and the outcome is
    reported here, so that a table which quietly stopped refusing fails THIS test rather than the
    module's setup.
    """
    assert world["non_positive_refusal"] is not None, (
        "the table accepted a zero-rupee sanction order — the CHECK constraint is gone, and a psql "
        "session or an importer can now write one"
    )
    assert world["non_positive_refusal_is_prisma"], (
        f"the insert failed with {world['non_positive_refusal']}, which is not a PrismaError — "
        "something other than the CHECK constraint refused it, so the constraint is untested"
    )


# --------------------------------------------------------------------------------------
# 7. The report's copy, and the one scorer
# --------------------------------------------------------------------------------------


def test_the_stage_one_copy_is_seeded_and_is_a_copy(world, client) -> None:
    """The register is the answer; stage 1 holds a COPY, and drift is REPORTED rather than blocked.

    A report is a historical document: a sanction order amended in 2028 must not rewrite the cover of
    the report submitted in 2026. And a designer correcting a mistyped number on their own cover is
    doing something legitimate — what an officer needs is to SEE that the document about to be
    printed says something the register does not.

    The designer's edit is made in the fixture (it is a database write), and the GET below is live:
    the flag it reads is computed by the route from the row the edit left behind.
    """
    body = world["bodies"]["stage_copy"]
    answer = world["answers"]["stage_copy"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]
    assert payload["reportCopyMatches"] is True

    seen = world["rows"]["stage_copy"]
    assert seen["entry"] is not None, "the prefill did not seed stage 1"
    assert seen["data"].get("sanctionOrderNo") == body["sanctionOrderNo"]
    assert str(seen["data"].get("sanctionOrderDate") or "")[:10] == body["sanctionOrderDate"]

    # The designer edited the cover. The REGISTER did not move; the flag does.
    assert seen["order"].sanctionOrderNo == body["sanctionOrderNo"], "a stage edit moved the register"
    again = client.get(f"/api/sanction-orders/{payload['id']}", headers=_headers(world))
    assert again.status_code == 200, again.text
    assert again.json()["reportCopyMatches"] is False


def test_the_missing_list_is_the_registrys_and_not_a_second_count(world) -> None:
    """ONE SCORER. ``missingMandatory`` is ``stage_completeness(...).missing``, not a client count.

    Two arithmetics inside one product is two answers, neither of them wrong enough to fail. The
    officer's list therefore reports the same "still needed" the designer's own readiness screen
    reports, because it is literally the same list — shortfall labels included, which a hand count
    could not produce.
    """
    answer = world["answers"]["missing_list"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]

    singleton = world["rows"]["missing_list_singleton"]
    expected = stage_completeness(stage("WORKSHOP_SETUP"), singleton, {})
    assert payload["missingMandatory"] == list(expected.missing)
    assert payload["readyForWork"] is False
    assert "Workshop title" in " · ".join(payload["missingMandatory"])


def test_a_sanctioned_workshop_carries_its_order_and_an_ad_hoc_one_does_not(world) -> None:
    """One order, one workshop, both directions — and NULL for every workshop opened by hand."""
    answer = world["answers"]["sanctioned"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]

    seen = world["rows"]["sanctioned"]
    assert seen["workshop"].sanctionOrder is not None
    assert seen["workshop"].sanctionOrder.id == payload["id"]
    assert seen["ad_hoc"].sanctionOrder is None


# --------------------------------------------------------------------------------------
# 8. The queue, the throttle and the gate
# --------------------------------------------------------------------------------------


def test_the_sanction_order_admits_nobody_to_the_pending_queue(world) -> None:
    """The admission is ACTIVE, so ``ACCESS_PENDING_MAX`` is never approached by this door.

    Worth pinning because the obvious implementation — write a PENDING row and let an admin approve
    it — would make every sanction order a second job for somebody else, and would fill a capped
    queue with people who have already been decided about.

    THE BRACKET IS WIDER THAN IT WAS, AND DELIBERATELY SO. The count is read at the end of the seed
    and again at the start of the read-back, so it spans EVERY create this module makes rather than
    one. That is the same claim asserted over more evidence: not one order admitted anybody, so no
    order did.
    """
    assert world["answers"]["pending_queue"].status_code == 201, (
        world["answers"]["pending_queue"].text
    )
    assert world["pending_after"] == world["pending_before"]


def test_a_throttled_link_does_not_roll_back_the_sanction_order(world, client) -> None:
    """A throttle on the fifth account of the morning must not undo a ministry sanction order.

    The 201 stands, ``credentialLink`` is null and ``credentialLinkProblem`` says what happened and
    what to do — which is the difference between "no link was offered" and "no link was possible".

    The account and its spent budget are seeded in the fixture; both requests are live, because
    nothing here has to read a row back.
    """
    # The account exists, so no link would be offered anyway — the throttle is exercised through the
    # RE-ISSUE arm, which is where an officer actually meets it.
    answer = _post(world, _body(designerEmail=world["throttle_address"]))
    assert answer.status_code == 201, answer.text
    order_id = answer.json()["sanctionOrder"]["id"]

    again = client.post(
        f"/api/sanction-orders/{order_id}/credential-link", headers=_headers(world)
    )
    assert again.status_code == 429, again.text
    assert again.headers.get("retry-after")
    assert "hour" in again.json()["detail"]


def test_a_designer_may_not_record_a_sanction_order(world, client) -> None:
    """THE WHOLE REQUIREMENT, IN ONE ASSERTION: the person who does the work does not authorise the
    budget for it. The refusal names the next move rather than simply saying no."""
    answer = client.post(
        "/api/sanction-orders",
        json=_body(),
        headers={
            "Authorization": f"Bearer {create_access_token(subject=world['refused_designer_id'])}"
        },
    )
    assert answer.status_code == 403, answer.text
    assert "Assistant Director" in answer.json()["detail"]


def test_the_awaiting_count_matches_the_rows_the_list_calls_unready(world, client) -> None:
    """The badge and the list are the same arithmetic, asked twice.

    A badge computed from a different query than the list it badges is how a "3 waiting" that opens
    onto nothing comes about — and the officer's conclusion is that the screen is broken, not that
    two counts disagree.
    """
    assert _post(world, _body()).status_code == 201
    badge = client.get("/api/sanction-orders/awaiting-count", headers=_headers(world))
    assert badge.status_code == 200, badge.text
    listing = client.get("/api/sanction-orders?pageSize=200", headers=_headers(world))
    assert listing.status_code == 200, listing.text
    unready = [row for row in listing.json()["items"] if not row["readyForWork"]]
    assert badge.json()["awaiting"] >= len(unready)
    assert badge.json()["awaiting"] >= 1


def test_the_notes_are_the_only_thing_an_officer_may_edit(world, client) -> None:
    """The number, the date and the amount are what the ministry issued.

    A portal that lets an officer edit the three facts of the instrument is a portal whose register
    cannot be audited. ``APIModel`` is ``extra="forbid"``, so an attempt names the field it refused
    rather than ignoring it silently — which is what stops a client from believing it edited one.
    """
    body = _body()
    created = _post(world, body)
    assert created.status_code == 201, created.text
    order_id = created.json()["sanctionOrder"]["id"]

    patched = client.patch(
        f"/api/sanction-orders/{order_id}",
        json={"notes": "Order withdrawn by the ministry on 2026-05-02."},
        headers=_headers(world),
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["notes"].startswith("Order withdrawn")
    assert patched.json()["sanctionOrderNo"] == body["sanctionOrderNo"]

    refused = client.patch(
        f"/api/sanction-orders/{order_id}",
        json={"sanctionAmount": "1.00"},
        headers=_headers(world),
    )
    assert refused.status_code == 422, refused.text


# --------------------------------------------------------------------------------------
# 10. The three phase-0 refusals added on 2026-09-14, each of which was a shipped defect
# --------------------------------------------------------------------------------------
#
# NONE OF THESE WAS FOUND BY EXERCISING THE FEATURE, and that is the argument for asserting them
# here in rows rather than only as predicates in
# ``tests/test_sanction_order_designer_eligibility.py``. All three used to answer 201 and commit the
# full seven rows: the pure tests prove the decision, and these prove that NOTHING IS WRITTEN when
# the decision is "no" — which is the half a refusal moved below ``db.tx(`` would still pass.


@pytest.mark.parametrize("role", list(INELIGIBLE_ROLES))
def test_an_account_that_cannot_run_a_workshop_is_refused_before_anything_is_written(
    world, role: str
) -> None:
    """A RANK TEST STANDING IN FOR A SET, AND A 404 NOBODY COULD UNDO.

    The role decision inside the transaction is ``role_rank(user) < ROLE_RANK["DESIGNER"]`` and
    design-workshop capability is ``DESIGN_WORKSHOP_ROLES``, a SET. These five roles sit above the
    floor and outside the set, so each kept its role and was then written a ``DesignWorkshopViewer``
    row that ``load_workshop_or_404`` refuses to honour — its grant arm is
    ``can_run_design_workshops(user) and await has_viewer_grant(...)``, and ``and`` short-circuits
    before the row is ever read. The officer got a 201 saying the workshop was open; the named
    designer got a 404 for ever, and no screen in the product could undo it.

    THE ASSERTION THAT MATTERS IS THE SECOND HALF. A 422 alone would also be produced by a refusal
    raised from INSIDE the transaction; only reading the four tables back proves it was raised in
    phase 0. And the account's role is read back because the other tempting fix — lifting these tiers
    to DESIGNER so that the viewer row works — would demote a regional director because a colleague
    typed their address into a sanction form.
    """
    answer = world["answers"][f"ineligible_{role}"]
    assert answer.status_code == 422, answer.text
    detail = answer.json()["detail"]
    assert role in detail, "the officer is not told what is actually wrong with the account"
    assert "users screen" in detail, "the officer is not told where the fix lives"

    seen = world["rows"]["ineligible"][role]
    account = seen["account"]
    assert str(getattr(account.role, "value", account.role)) == role, "the account was demoted"
    assert seen["orders"] == [], "a refused create recorded the order anyway"
    assert seen["access"] is None, "a refused create admitted the address to the platform anyway"
    assert seen["roster"] is None, "a refused create empanelled them anyway"
    assert seen["viewers"] == [], "the viewer row nothing would honour was written anyway"


def test_an_officer_may_not_record_an_order_naming_their_own_mailbox(world) -> None:
    """AUTHOR AND APPROVER IN ONE REQUEST — and the 201 used to hand over the credentials for it.

    ``can_record_sanction_orders`` is a rank floor at ASSISTANT_DIRECTOR, and every tier that clears
    it is OUTSIDE ``DESIGN_WORKSHOP_ROLES``, so ``_require_designer`` refuses the officer themselves
    on all eighteen routes it guards. But the 201 returns ``credentialLink.link`` — a working
    72-hour first-password URL for an account this transaction creates at role DESIGNER, admitted
    ACTIVE and empanelled, with a viewer row on the workshop it just opened. Naming a second mailbox
    they control and redeeming that link let an officer author a fortnight of fieldwork as a designer
    and review, rewrite and approve it as themselves, with no admin involved at any point.

    The world's officer is an ASSISTANT_DIRECTOR on purpose (see the fixture). An ADMIN is refused
    here for the same reason and loses nothing by it: ``DESIGN_WORKSHOP_CREATOR_ROLES`` already lets
    an admin open a workshop for themselves through the ordinary "New workshop" button. What nobody
    may do is issue themselves the money.
    """
    answer = world["answers"]["own_mailbox"]
    assert answer.status_code == 422, answer.text
    assert "cannot name the officer recording it" in answer.json()["detail"]

    seen = world["rows"]["own_mailbox"]
    assert seen["orders"] == [], "a refused create recorded the order anyway"
    assert seen["roster"] is None, "the officer empanelled themselves as a designer"
    # The officer's OWN allow-list row is seeded by the fixture at ASSISTANT_DIRECTOR and has to be
    # untouched — a refusal that ran after `admit` would have re-admitted them as a DESIGNER.
    assert seen["access"] is not None
    assert str(getattr(seen["access"].admitRole, "value", seen["access"].admitRole)) == (
        "ASSISTANT_DIRECTOR"
    )


def test_a_sanction_order_does_not_overwrite_an_administrators_allow_list_decision(world) -> None:
    """``admit`` COPIES ITS ``grant`` DICT WHOLESALE ONTO AN EXISTING ROW — ``joinedAt`` is the only
    column it carves out — so this caller used to rewrite four things an administrator owns.

    The row is seeded here exactly as an administrator would leave it: admitted at RESEARCHER, with
    the admin's own spelling of the name, the admin's note about why, and the record of which admin
    decided it and when. Every one of those was replaced by a ministry officer's typing the first
    time a sanction order named the address, with nothing anywhere recording that it had been.

    THE TIER IS THE ONE FIELD THAT MOVES, AND IT MOVES UP. ``admitRole``'s own column comment says it
    LIFTS, NEVER LOWERS, and this transaction lifts the ACCOUNT to DESIGNER — so a row left saying
    RESEARCHER would make /admin/access and /admin/users disagree about the same person on the same
    morning. ``tests/test_sanction_order_designer_eligibility.py`` pins the other half of that
    comparison: a PROFESSOR or an ADMIN row is never written down to DESIGNER.
    """
    body = world["bodies"]["already_admitted"]
    answer = world["answers"]["already_admitted"]
    assert answer.status_code == 201, "an already-admitted address must still work"

    row = world["rows"]["already_admitted"]
    machine_note = SANCTION_ADMISSION_NOTE.format(
        no=body["sanctionOrderNo"], officer=world["officer"].name
    )
    # 1. THE NAME. The admin typed it; an officer's sanction form does not get to retype it.
    assert row.fullName == "R. Sundaram (as the admin typed it)"
    # 2. THE NOTE. APPENDED, not replaced — both facts are worth keeping, and the machine note is the
    #    only thing on this row that explains why it moved today.
    assert row.notes == f"{world['already_note']} {machine_note}"
    # 3. WHO DECIDED. A ministry officer acting through a different door does not become the
    #    administrator who reviewed this person's request.
    assert row.decidedById == world["deciding_admin_id"], (
        "the administrator who admitted them was overwritten"
    )
    assert row.decidedAt == world["already_joined"]
    # 4. THE TIER, lifted by the same comparison the User row is lifted by — and `joinedAt` still
    #    reads 2024, which is `admit`'s own rule and is asserted beside it so the two cannot drift.
    assert str(getattr(row.admitRole, "value", row.admitRole)) == "DESIGNER"
    assert row.joinedAt == world["already_joined"]
