"""One-tap suggestions for a stage, built from what the workshop already holds.

``GET /design-workshops/{id}/stages/{stageKey}/assist``. Nothing here writes: every suggestion is
applied by the designer's tap on either client, only into boxes that are blank, and stays editable.

Two shapes, so one card on each client can draw them all:

``FILL``      set ``values`` on one record — the stage's singleton (``rowKey`` null) or the row whose
              ``_entryId``/``_clientKey`` is ``rowKey``. Only blank boxes are offered.
``ADD_ROWS``  append ``rows`` to ``entityKey``. Each row carries its own values; the client mints
              the client key, exactly as for a row the designer added.

Items covered (owner decisions, 2026-10-17): A14 (stages 20 and 21), A16 (stage 13 costs), C6 ("Fill
the schedule"), C7 (certificates for those who completed), C10 (stage 17 costs and material lines
from the prototype), B10 (planned follow-ups and due visits). The counts of C7/C8 are returned in
``computed`` for display; they are stored by the save, see ``stage_computed``.
"""

from __future__ import annotations

import calendar
from collections.abc import Mapping, Sequence
from datetime import UTC, date, datetime, timedelta
from typing import Any

from app.services import stage_computed as sc
from app.services.cost_integrity import TOLERANCE_RUPEES
from app.services.market_analysis import as_number
from app.services.stage_schema import ENUMS, enum_label

FOLLOW_UP_MONTHS = {"M3": 3, "M6": 6, "M12": 12}

_STAGES = {
    "setup": sc.SETUP_STAGE,
    "roster": sc.ROSTER_STAGE,
    "survey": sc.SURVEY_STAGE,
    "prototypes": "PROTOTYPE_DEVELOPMENT",
    "final": "FINAL_PROTOTYPE_DOCUMENTATION",
    "costing": "COSTING_MARKET_LINKAGE",
    "closing": sc.CLOSING_STAGE,
    "report": "REPORT_GENERATION",
    "archive": "DATA_QUALITY_ARCHIVE",
    "followup": "POST_WORKSHOP_FOLLOWUP",
}


def _blank(value: Any) -> bool:
    return value is None or value == "" or value == []


def _rupees(value: float) -> str:
    return f"₹{value:,.2f}"


def _money_text(value: float) -> str:
    return f"{value:.2f}"


def _day(value: Any) -> date | None:
    try:
        return date.fromisoformat(str(value or "")[:10])
    except ValueError:
        return None


def _short_date(day: date) -> str:
    return f"{day.day} {day.strftime('%b %Y')}"


def add_months(start: date, months: int) -> date:
    """``start`` plus whole months, clamped to the month's last day (31 Jan + 1 month = 28 Feb)."""
    month_index = start.month - 1 + months
    year, month = start.year + month_index // 12, month_index % 12 + 1
    return date(year, month, min(start.day, calendar.monthrange(year, month)[1]))


def _key(row: Mapping[str, Any]) -> str:
    return str(row.get("_entryId") or row.get("_clientKey") or "")


def _keys(row: Mapping[str, Any]) -> set[str]:
    return {str(v) for k in ("_entryId", "_clientKey") if (v := row.get(k))}


def _refers(value: Any, row: Mapping[str, Any]) -> bool:
    return bool(value) and str(value) in _keys(row)


def _label(row: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        text = str(row.get(key) or "").strip()
        if text:
            return text
    return "Untitled"


def fill(
    suggestion_id: str,
    entity_key: str,
    row: Mapping[str, Any] | None,
    title: str,
    detail: str,
    values: Mapping[str, Any],
    *,
    singleton: bool = False,
) -> dict[str, Any] | None:
    """A FILL suggestion over the blank boxes of ``row`` only, or None when nothing is blank."""
    current = row or {}
    offered = {k: v for k, v in values.items() if not _blank(v) and _blank(current.get(k))}
    if not offered:
        return None
    return {
        "id": suggestion_id,
        "kind": "FILL",
        "entityKey": entity_key,
        "rowKey": None if singleton else (_key(current) or None),
        "title": title,
        "detail": detail,
        "values": offered,
    }


def add_rows(
    suggestion_id: str, entity_key: str, title: str, detail: str, rows: list[dict[str, Any]]
) -> dict[str, Any] | None:
    if not rows:
        return None
    return {
        "id": suggestion_id,
        "kind": "ADD_ROWS",
        "entityKey": entity_key,
        "rowKey": None,
        "title": title,
        "detail": detail,
        "rows": rows,
    }


# --------------------------------------------------------------------------------------
# Per-stage builders — pure, over the workshop's grouped data
# --------------------------------------------------------------------------------------


class Workshop:
    """The grouped stage data one assist call reads: singletons and collection rows by entity."""

    def __init__(
        self,
        singletons: Mapping[str, Mapping[str, Any]],
        collections: Mapping[str, Sequence[Mapping[str, Any]]],
        *,
        template_id: str = "",
        exports: Sequence[Mapping[str, Any]] = (),
        media: Mapping[str, int] | None = None,
        sittings: Sequence[Mapping[str, Any]] = (),
        today: date | None = None,
    ) -> None:
        self.singletons = singletons
        self.collections = collections
        self.template_id = template_id
        self.exports = exports
        self.media = media or {}
        self.sittings = sittings
        self.today = today or datetime.now(UTC).date()

    def one(self, entity: str) -> Mapping[str, Any]:
        return self.singletons.get(entity) or {}

    def rows(self, entity: str) -> Sequence[Mapping[str, Any]]:
        return self.collections.get(entity) or ()

    def days(self) -> list[int]:
        return sc.workshop_days(list(self.rows(sc.SCHEDULE_DAY)), self.one("workshopSetup"))


def schedule_suggestions(w: Workshop) -> list[dict[str, Any]]:
    """C6 "Fill the schedule": one row per workshop day not yet scheduled, dated from the start."""
    setup = w.one("workshopSetup")
    start = _day(setup.get("startDate"))
    duration = sc.setup_duration(setup)
    if start is None or not duration:
        return []
    have = {sc._int(r.get("dayNumber")) for r in w.rows(sc.SCHEDULE_DAY)}
    rows = [
        {"dayNumber": n, "date": (start + timedelta(days=n - 1)).isoformat()}
        for n in range(1, duration + 1)
        if n not in have
    ]
    plural = "day" if len(rows) == 1 else "days"
    out = add_rows(
        "schedule:fill",
        sc.SCHEDULE_DAY,
        "Fill the schedule",
        f"Adds {len(rows)} {plural} from {_short_date(start)}, numbered and dated.",
        rows,
    )
    return [out] if out else []


def prototype_cost_suggestions(w: Workshop) -> list[dict[str, Any]]:
    """A16 on stage 13: a prototype's material cost from its material lines, on a tap."""
    out: list[dict[str, Any]] = []
    for proto in w.rows("prototype"):
        lines = [m for m in w.rows("materialUsage") if _refers(m.get("prototypeRef"), proto)]
        amounts = [as_number(m.get("amount")) for m in lines]
        if not lines or any(a is None for a in amounts):
            continue
        total = round(sum(a for a in amounts if a is not None), 2)
        declared = as_number(proto.get("materialCost"))
        if declared is not None and abs(declared - total) <= TOLERANCE_RUPEES:
            continue
        name = _label(proto, "name", "prototypeCode")
        title = f"Use {_rupees(total)} from the lines"
        detail = (
            f"{name}: {len(lines)} material line{'s' if len(lines) != 1 else ''} add up to "
            f"{_rupees(total)}"
            + (f"; {_rupees(declared)} is typed." if declared is not None else ".")
        )
        out.append(
            {
                "id": f"prototype:materialCost:{_key(proto)}",
                "kind": "FILL",
                "entityKey": "prototype",
                "rowKey": _key(proto),
                "title": title,
                "detail": detail,
                "values": {"materialCost": _money_text(total)},
                "replaces": declared is not None,
            }
        )
    return out


def _prototype_for_sheet(w: Workshop, sheet: Mapping[str, Any]) -> Mapping[str, Any] | None:
    product = next((p for p in w.rows("finalProduct") if _refers(sheet.get("productRef"), p)), None)
    if product is None:
        return None
    return next((p for p in w.rows("prototype") if _refers(product.get("prototypeRef"), p)), None)


def costing_suggestions(w: Workshop) -> list[dict[str, Any]]:
    """C10 on stage 17: the prototype's costs, and optionally its material lines."""
    out: list[dict[str, Any]] = []
    for sheet in w.rows("costSheet"):
        proto = _prototype_for_sheet(w, sheet)
        if proto is None:
            continue
        name = _label(proto, "name", "prototypeCode")
        values = {
            k: _money_text(n)
            for k in ("materialCost", "labourCost")
            if (n := as_number(proto.get(k))) is not None
        }
        suggestion = fill(
            f"costSheet:fromPrototype:{_key(sheet)}",
            "costSheet",
            sheet,
            "Use the prototype's costs",
            f"From {name}: "
            + ", ".join(
                f"{'material' if k == 'materialCost' else 'labour'} {_rupees(float(v))}"
                for k, v in values.items()
            )
            + ".",
            values,
        )
        if suggestion:
            out.append(suggestion)
        has_lines = any(_refers(m.get("costSheetRef"), sheet) for m in w.rows("costMaterialLine"))
        usage = [m for m in w.rows("materialUsage") if _refers(m.get("prototypeRef"), proto)]
        if usage and not has_lines and _key(sheet):
            rows = [
                {
                    "costSheetRef": _key(sheet),
                    "item": m.get("material"),
                    "quantity": m.get("quantity"),
                    "unit": m.get("unit"),
                    **({"rate": m.get("rate")} if not _blank(m.get("rate")) else {}),
                    **({"amount": m.get("amount")} if not _blank(m.get("amount")) else {}),
                }
                for m in usage
                if not _blank(m.get("material"))
            ]
            lines = add_rows(
                f"costSheet:materialLines:{_key(sheet)}",
                "costMaterialLine",
                "Copy the prototype's material lines",
                f"{len(rows)} line{'s' if len(rows) != 1 else ''} from {name}.",
                rows,
            )
            if lines:
                out.append(lines)
    return out


def certificate_suggestions(w: Workshop) -> list[dict[str, Any]]:
    """C7: a certificate row for each participant present every day who has none yet."""
    days = w.days()
    if not days:
        return []
    have = {str(c.get("participantRef") or "") for c in w.rows(sc.CERTIFICATE)}
    rows = []
    for person in sc._named(w.rows(sc.PARTICIPANT)):
        if _keys(person) & have or not _key(person):
            continue
        attended = sc.attended_days(person, days)
        if attended == len(days):
            rows.append({"participantRef": _key(person), "daysAttended": attended})
    out = add_rows(
        "certificate:completed",
        sc.CERTIFICATE,
        "Add certificates for everyone who completed",
        f"{len(rows)} participant{'s' if len(rows) != 1 else ''} present on all {len(days)} days.",
        rows,
    )
    return [out] if out else []


def report_suggestions(w: Workshop) -> list[dict[str, Any]]:
    """A14 on stage 20: the template the workshop was created with."""
    if not w.template_id or w.template_id not in ENUMS["REPORT_TEMPLATE"]:
        return []
    out = fill(
        "reportSettings:template",
        "reportSettings",
        w.one("reportSettings"),
        "Use the workshop's template",
        enum_label("REPORT_TEMPLATE", w.template_id),
        {"templateId": w.template_id},
        singleton=True,
    )
    return [out] if out else []


def archive_suggestions(w: Workshop) -> list[dict[str, Any]]:
    """A14 on stage 21: "report saved" from the record of files made; "media saved" from storage."""
    out: list[dict[str, Any]] = []
    archive = w.one("archive")
    latest = next(iter(w.exports), None)
    if latest is not None:
        made = _day(latest.get("generatedAt"))
        pages = latest.get("pageCount")
        parts = [
            f"Saved {_short_date(made)}" if made else "Saved",
            str(latest.get("format") or "").upper(),
        ]
        if pages:
            parts.append(f"{pages} page{'s' if pages != 1 else ''}")
        suggestion = fill(
            "archive:reportSaved",
            "archive",
            archive,
            "Tick “Final report saved”",
            ", ".join(p for p in parts if p) + ".",
            {"reportSaved": True},
            singleton=True,
        )
        if suggestion:
            out.append(suggestion)
    referenced, stored = int(w.media.get("referenced", 0)), int(w.media.get("stored", 0))
    if referenced and stored >= referenced:
        suggestion = fill(
            "archive:mediaSaved",
            "archive",
            archive,
            "Tick “Media saved”",
            f"All {referenced} file{'s' if referenced != 1 else ''} are uploaded.",
            {"mediaSaved": True},
            singleton=True,
        )
        if suggestion:
            out.append(suggestion)
    return out


def _follow_up_base(w: Workshop) -> date | None:
    return _day(w.one("workshopSetup").get("endDate")) or _day(w.one("closing").get("closingDate"))


def follow_up_plan(w: Workshop) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """B10: the planned visits to add, and the planned visits now due with no result recorded."""
    base = _follow_up_base(w)
    planned = list(w.rows("plannedFollowUp"))
    done = {
        (str(f.get("productRef") or ""), str(f.get("interval") or "")) for f in w.rows("followUp")
    }
    to_plan: list[dict[str, Any]] = []
    if base is not None:
        have = {(str(p.get("productRef") or ""), str(p.get("interval") or "")) for p in planned}
        for product in w.rows("finalProduct"):
            ref = _key(product)
            if not ref:
                continue
            for interval, months in FOLLOW_UP_MONTHS.items():
                if (ref, interval) in have or (ref, interval) in done:
                    continue
                to_plan.append(
                    {
                        "productRef": ref,
                        "interval": interval,
                        "dueDate": add_months(base, months).isoformat(),
                    }
                )
    due = []
    products = {k: p for p in w.rows("finalProduct") for k in _keys(p)}
    for plan in planned:
        when = _day(plan.get("dueDate"))
        key = (str(plan.get("productRef") or ""), str(plan.get("interval") or ""))
        if when is None or when > w.today or key in done or key[0] not in products:
            continue
        due.append(
            {
                "planKey": _key(plan),
                "productRef": key[0],
                "productName": _label(products[key[0]], "name", "productCode"),
                "interval": key[1],
                "intervalLabel": enum_label("FOLLOWUP_INTERVAL", key[1]),
                "dueDate": when.isoformat(),
            }
        )
    return to_plan, sorted(due, key=lambda d: d["dueDate"])


def follow_up_suggestions(w: Workshop) -> list[dict[str, Any]]:
    to_plan, due = follow_up_plan(w)
    out: list[dict[str, Any]] = []
    if to_plan:
        products = len({r["productRef"] for r in to_plan})
        plan = add_rows(
            "followUp:plan",
            "plannedFollowUp",
            "Plan follow-ups",
            f"3, 6 and 12 months after the workshop for {products} "
            f"product{'s' if products != 1 else ''}. Only the dates are stored.",
            to_plan,
        )
        if plan:
            out.append(plan)
    for visit in due:
        when = _day(visit["dueDate"])
        out.append(
            {
                "id": f"followUp:due:{visit['productRef']}:{visit['interval']}",
                "kind": "ADD_ROWS",
                "entityKey": "followUp",
                "rowKey": None,
                "title": f"Record the {visit['intervalLabel']} follow-up",
                "detail": f"{visit['productName']}, due {_short_date(when) if when else visit['dueDate']}.",
                "rows": [
                    {
                        "productRef": visit["productRef"],
                        "interval": visit["interval"],
                        "followUpDate": visit["dueDate"],
                    }
                ],
                "due": True,
            }
        )
    return out


def computed_values(w: Workshop, stage_key: str) -> dict[str, Any]:
    """The counts a stage draws read-only, as the save will store them."""
    days = w.days()
    if stage_key == sc.ROSTER_STAGE:
        values = sc.roster_values(list(w.rows(sc.PARTICIPANT)), days)
        return {
            "singleton": {
                "totalArtisans": values["ROSTER_COUNT"],
                "womenParticipants": values["ROSTER_WOMEN"],
            },
            "rows": {
                sc.PARTICIPANT: {
                    _key(p): {"attendedDays": sc.attended_days(p, days)}
                    for p in w.rows(sc.PARTICIPANT)
                    if _key(p)
                }
            },
        }
    if stage_key == sc.SURVEY_STAGE:
        sittings = sum(int(s.get("count") or 0) for s in w.sittings)
        return {
            "singleton": {
                "responsesCollected": sc.survey_responses(
                    list(w.rows(sc.SURVEY_RESPONSE)), sittings
                )
            },
            "rows": {},
        }
    if stage_key == sc.CLOSING_STAGE:
        participants = list(w.rows(sc.PARTICIPANT))
        return {
            "singleton": {
                "participantsCompleted": sc.roster_values(participants, days)["ROSTER_COMPLETED"]
            },
            "rows": {
                sc.CERTIFICATE: {
                    _key(c): {
                        "daysAttended": sc.attended_days(person, days)
                        if (person := sc.participant_for(c.get("participantRef"), participants))
                        else None
                    }
                    for c in w.rows(sc.CERTIFICATE)
                    if _key(c)
                }
            },
        }
    return {"singleton": {}, "rows": {}}


_BUILDERS = {
    sc.ROSTER_STAGE: (schedule_suggestions,),
    "PROTOTYPE_DEVELOPMENT": (prototype_cost_suggestions,),
    "COSTING_MARKET_LINKAGE": (costing_suggestions,),
    sc.CLOSING_STAGE: (certificate_suggestions,),
    "REPORT_GENERATION": (report_suggestions,),
    "DATA_QUALITY_ARCHIVE": (archive_suggestions,),
    "POST_WORKSHOP_FOLLOWUP": (follow_up_suggestions,),
}


def build_assist(w: Workshop, stage_key: str) -> dict[str, Any]:
    suggestions = [s for builder in _BUILDERS.get(stage_key, ()) for s in builder(w)]
    payload: dict[str, Any] = {
        "stageKey": stage_key,
        "suggestions": suggestions,
        "computed": computed_values(w, stage_key),
    }
    if stage_key in (sc.ROSTER_STAGE, sc.CLOSING_STAGE):
        payload["attendanceDays"] = w.days()
    if stage_key == sc.ROSTER_STAGE:
        # A new schedule row's date is the start date plus its day number less one (C6).
        payload["startDate"] = str(w.one("workshopSetup").get("startDate") or "")[:10] or None
    if stage_key == sc.SURVEY_STAGE:
        payload["sittings"] = list(w.sittings)
    if stage_key == "POST_WORKSHOP_FOLLOWUP":
        payload["followUpsDue"] = follow_up_plan(w)[1]
    return payload


# --------------------------------------------------------------------------------------
# Loading
# --------------------------------------------------------------------------------------

#: Which stages' rows each stage's assist reads. Read narrowly: a stage open is on the critical path.
_READS = {
    sc.ROSTER_STAGE: (sc.SETUP_STAGE, sc.ROSTER_STAGE),
    sc.SURVEY_STAGE: (sc.SURVEY_STAGE,),
    "PROTOTYPE_DEVELOPMENT": ("PROTOTYPE_DEVELOPMENT",),
    "COSTING_MARKET_LINKAGE": (
        "PROTOTYPE_DEVELOPMENT",
        "FINAL_PROTOTYPE_DOCUMENTATION",
        "COSTING_MARKET_LINKAGE",
    ),
    sc.CLOSING_STAGE: (sc.SETUP_STAGE, sc.ROSTER_STAGE, sc.CLOSING_STAGE),
    "REPORT_GENERATION": ("REPORT_GENERATION",),
    "DATA_QUALITY_ARCHIVE": ("DATA_QUALITY_ARCHIVE",),
    "POST_WORKSHOP_FOLLOWUP": (
        sc.SETUP_STAGE,
        "FINAL_PROTOTYPE_DOCUMENTATION",
        sc.CLOSING_STAGE,
        "POST_WORKSHOP_FOLLOWUP",
    ),
}


def group_rows(
    rows: Sequence[Any],
) -> tuple[dict[str, dict[str, Any]], dict[str, list[dict[str, Any]]]]:
    from app.services.stage_schema import Cardinality, stages

    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, list[dict[str, Any]]] = {}
    for row in sorted(rows, key=lambda r: r.ordinal):
        data = dict(row.data or {})
        data["_entryId"] = row.id
        if row.clientKey:
            data["_clientKey"] = row.clientKey
        if cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.entityKey] = data
        elif row.entityKey in cardinality:
            collections.setdefault(row.entityKey, []).append(data)
    return singletons, collections


async def _sittings(workshop_id: str) -> list[dict[str, Any]]:
    """Each market-survey form's sitting count and latest date: one form read and one grouped count.

    NO RESPONDENT NAMES. This answers anyone who may read the workshop, a wider audience than the
    questionnaire's own sittings route admits (owner, admin or co-designer), and neither client
    shows a name here; ``respondents`` stays in the payload, empty, for clients that read it.
    """
    from app.core.db import db

    forms = await db.questionnaire.find_many(
        where={"designWorkshopId": workshop_id, "isActive": True, "kind": "MARKET_SURVEY"},
        order={"createdAt": "asc"},
    )
    if not forms:
        return []
    groups = await db.questionnaireformentry.group_by(
        by=["questionnaireId"],
        where={"questionnaireId": {"in": [f.id for f in forms]}},
        count=True,
        max={"createdAt": True},
    )
    by_form = {str(g.get("questionnaireId") or ""): g for g in groups}
    out = []
    for form in forms:
        group = by_form.get(form.id) or {}
        latest = (group.get("_max") or {}).get("createdAt")
        out.append(
            {
                "questionnaireId": form.id,
                "title": form.title,
                "count": int((group.get("_count") or {}).get("_all") or 0),
                "latest": _latest_day(latest),
                "respondents": [],
            }
        )
    return out


def _latest_day(value: Any) -> str | None:
    """A grouped ``createdAt`` maximum as yyyy-mm-dd; Prisma hands it back as a datetime or text."""
    if value is None:
        return None
    if hasattr(value, "date"):
        return value.date().isoformat()
    return str(value)[:10] or None


async def _media_counts(workshop_id: str) -> dict[str, int]:
    from app.core.db import db
    from app.services.design_workshops import entry_rows, workshop_media_ids

    every = workshop_media_ids(await entry_rows(workshop_id))
    ids = {i for i in every if not i.startswith("dwlocal:")}
    referenced = len(every)
    if not ids:
        return {"referenced": referenced, "stored": 0}
    stored = await db.mediafile.count(where={"id": {"in": sorted(ids)}})
    return {"referenced": referenced, "stored": stored}


async def load_assist(record: Any, stage_key: str) -> dict[str, Any]:
    from app.core.db import db

    reads = _READS.get(stage_key, ())
    rows = (
        await db.dwstageentry.find_many(
            where={
                "designWorkshopId": record.id,
                "stageKey": {"in": list(reads)},
                "deletedAt": None,
            }
        )
        if reads
        else []
    )
    singletons, collections = group_rows(rows)
    exports: list[dict[str, Any]] = []
    media: dict[str, int] = {}
    sittings: list[dict[str, Any]] = []
    if stage_key == "DATA_QUALITY_ARCHIVE":
        found = await db.dwreportexport.find_many(
            where={"designWorkshopId": record.id}, order={"generatedAt": "desc"}, take=1
        )
        exports = [
            {
                "format": r.format,
                "pageCount": r.pageCount,
                "generatedAt": r.generatedAt.isoformat() if r.generatedAt else None,
            }
            for r in found
        ]
        media = await _media_counts(record.id)
    if stage_key == sc.SURVEY_STAGE:
        sittings = await _sittings(record.id)
    workshop = Workshop(
        singletons,
        collections,
        template_id=str(getattr(record, "templateId", "") or ""),
        exports=exports,
        media=media,
        sittings=sittings,
    )
    return build_assist(workshop, stage_key)
