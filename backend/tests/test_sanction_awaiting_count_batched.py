"""``GET /sanction-orders/awaiting-count`` reads stage 1 ONCE for every order, not once per order.

The badge is asked on every page an officer or an admin opens. It used to call
``_stage_one_state`` per sanction order — a query each — so 74 orders cost 75 round trips, and it
was the slowest request on the stage form's open (``frontend/e2e/stage-form-speed.spec.ts``). The
count must not change by being batched: the same rows, the same pick, the same scorer. No database.
"""

from types import SimpleNamespace

from app.services import sanction_orders

COMPLETE = {
    "workshopTitle": "Bagru block printing",
    "workshopKind": "DESIGN",
    "schemeName": "NHDP",
    "craftName": "Block printing",
    "clusterName": "Bagru",
    "state": "Rajasthan",
    "district": "Jaipur",
    "venue": "Community hall",
    "startDate": "2026-09-01",
    "endDate": "2026-09-14",
    "designerName": "A designer",
    "implementingAgency": "An agency",
    "sponsor": "A sponsor",
}


class _Table:
    def __init__(self, rows):
        self.rows = rows
        self.calls: list[dict] = []

    async def find_many(self, where=None, order=None, **_kwargs):
        self.calls.append({"where": where, "order": order})
        if where is None:
            return list(self.rows)
        wanted = set(where["designWorkshopId"]["in"])
        return [
            row
            for row in sorted(self.rows, key=lambda r: r.ordinal)
            if row.designWorkshopId in wanted
            and row.stageKey == where["stageKey"]
            and row.deletedAt is None
        ]


def _entry(workshop_id, data, *, entity="workshopSetup", ordinal=0, deleted=False):
    return SimpleNamespace(
        designWorkshopId=workshop_id,
        stageKey=sanction_orders.STAGE_ONE_KEY,
        entityKey=entity,
        ordinal=ordinal,
        data=data,
        deletedAt="2026-10-01" if deleted else None,
    )


async def test_the_badge_reads_stage_one_once_and_counts_what_the_scorer_calls_unready(monkeypatch):
    orders = _Table([SimpleNamespace(designWorkshopId=f"w{i}") for i in range(5)])
    entries = _Table(
        [
            _entry("w0", COMPLETE),
            _entry("w1", {"workshopTitle": "Half filled"}),
            # w2 has no stage-1 row at all, w3 only a deleted one: both are waiting.
            _entry("w3", COMPLETE, deleted=True),
            _entry("w4", COMPLETE, ordinal=1),
            _entry("w4", {}, entity="somethingElse", ordinal=0),
        ]
    )
    monkeypatch.setattr(
        sanction_orders, "db", SimpleNamespace(sanctionorder=orders, dwstageentry=entries)
    )

    assert await sanction_orders.awaiting_count() == 3
    assert len(entries.calls) == 1, "one stage-1 read for every order"
    assert entries.calls[0]["where"]["designWorkshopId"]["in"] == ["w0", "w1", "w2", "w3", "w4"]


async def test_the_batched_pick_is_the_single_reads_pick(monkeypatch):
    entries = _Table(
        [
            _entry("w1", {"workshopTitle": "second"}, ordinal=2),
            _entry("w1", {"workshopTitle": "first"}, ordinal=1),
        ]
    )
    monkeypatch.setattr(sanction_orders, "db", SimpleNamespace(dwstageentry=entries))
    found = await sanction_orders._stage_one_singletons(["w1", None, "w1"])
    assert found == {"w1": {"workshopTitle": "first"}}
    assert await sanction_orders._stage_one_singletons([None]) == {}


async def test_the_officers_list_reads_stage_one_once_per_page_and_matches_the_single_read(monkeypatch):
    """The list built each row with its own stage-1 read — the badge's N+1, one route over."""
    entries = _Table(
        [
            _entry("w0", COMPLETE),
            _entry("w1", {"workshopTitle": "Half filled", "sanctionOrderNo": "SO-1"}),
        ]
    )
    monkeypatch.setattr(sanction_orders, "db", SimpleNamespace(dwstageentry=entries))

    # C1 (2026-10-17): the planned-workshop read is one query per PAGE too, through the annual
    # plan's module. Counted here so the batched and the single path are held to it alike.
    planned_reads: list[list[str]] = []

    async def planned_rows(workshop_ids):
        planned_reads.append(list(workshop_ids))
        return {"w1": {"id": "plan_1", "workshopNo": "DPW/1", "planYear": 2026, "planYearLabel": "2026-27", "plannedTitle": None}}

    monkeypatch.setattr(sanction_orders.annual_plan, "planned_rows_for_workshops", planned_rows)

    async def one_at_a_time(workshop_id):
        found = (await sanction_orders._stage_one_singletons([workshop_id])).get(workshop_id)
        spec = sanction_orders.stage(sanction_orders.STAGE_ONE_KEY)
        return found, sanction_orders.stage_completeness(spec, found or {}, {})

    rows = [
        SimpleNamespace(
            id=f"so{i}",
            sanctionOrderNo=f"SO-{i}",
            sanctionOrderKey=f"SO-{i}",
            sanctionOrderDate=None,
            sanctionAmount="1000.00",
            designerUserId="u",
            designerEmail="d@example.invalid",
            accountCreated=True,
            notes=None,
            designWorkshopId=workshop_id,
            createdById="o",
            designers=[],
        )
        for i, workshop_id in enumerate(["w0", "w1", "w2", "w0"])
    ]

    batched = await sanction_orders.sanction_payloads(rows)
    assert len(entries.calls) == 1, "one stage-1 read for the whole page"
    assert entries.calls[0]["where"]["designWorkshopId"]["in"] == ["w0", "w1", "w2"]
    assert len(planned_reads) == 1, "one planned-workshop read for the whole page"
    assert [item["plannedWorkshop"] and item["plannedWorkshop"]["id"] for item in batched] == [
        None,
        "plan_1",
        None,
        None,
    ]

    monkeypatch.setattr(sanction_orders, "_stage_one_state", one_at_a_time)
    single = [await sanction_orders.sanction_payload(row) for row in rows]
    assert batched == single
    assert [item["readyForWork"] for item in batched] == [True, False, False, True]
