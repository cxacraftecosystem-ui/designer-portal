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
