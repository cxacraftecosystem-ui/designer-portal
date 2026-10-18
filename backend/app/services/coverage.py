"""The coverage map: what has been documented where, keyed by LGD code, and where the gaps are.

``GET /map/points`` answers "where did this record come from" with points. This module answers the
question a ministry asks of the whole programme — which districts have held a workshop, which crafts
have never been documented, how many artisans and records each district holds — with one row per
administrative UNIT (an LGD district, or a state where a record names no district).

HOW A ROW BECOMES A COUNT. One fold per bucket over rows that come out of the same ``where`` clauses
the map and the search box use (``record_filters.build_record_wheres``), so the visibility rule stays
in one place and this module never composes a raw ``where`` for a record table. Each row's place is
resolved through ``services/lgd.resolve`` when the map is read; nothing is stored. The two
designer-portal-only buckets — design workshops (``DesignWorkshop``, scoped by
``ministry_dashboard.scope_clause``) and planned workshops (``AnnualPlanEntry``, scoped by
``annual_plan.plan_scope``) — are folded the same way from their own state/district columns.

WHAT IS NEVER ADDED TOGETHER. Planned and held workshops are separate counts. The Field Repository's
figures (``peer``) are a separate row on each unit and are never summed into this portal's, because
the same artisan can exist in both without a consented link.

THE FOLDS ARE PURE FUNCTIONS over already-fetched rows (``fold_*``), so the counting rules are tested
without a database; :func:`compute` does the reads and hands them over.

Filters: craft codes (``Craft.craftCode``, or a craft NAME through the shared spelling table and this
portal's ``CraftSpelling`` rows), scheme (``DesignWorkshop.workshopKind`` / ``AnnualPlanEntry.workshopKind``
— field records carry no scheme and are not narrowed by it), and financial year (1 April to 31 March,
India Standard Time).
"""

from __future__ import annotations

import asyncio
import json
import time
from collections import Counter
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any

from app.scale.replica import read_via_replica
from app.services import cross_portal_codes, lgd
from app.services.concurrency import gather_reads
from app.services.record_filters import build_record_wheres, design_workshop_where

#: Rows read per bucket. A larger corpus degrades into a flagged, truncated map, not a timeout.
ROW_CAP = 20_000
#: Markers returned. Beyond this the map flags ``markersTruncated``.
MARKER_CAP = 500
#: Seconds a computed answer is reused for the same caller scope and parameters.
CACHE_SECONDS = 60
#: Seconds a Field Repository answer is reused.
PEER_CACHE_SECONDS = 300
#: Record stubs per bucket page in the unit panel.
PAGE_SIZE = 20
#: Unmatched spellings an administrator sees inline on the map payload.
UNMATCHED_SAMPLE = 20

LEVEL_STATE = "STATE"
LEVEL_DISTRICT = "DISTRICT"
LEVELS = (LEVEL_STATE, LEVEL_DISTRICT)

#: The field buckets, in the order the panel lists them. Same names as ``record_filters.RECORD_TYPES``.
FIELD_BUCKETS = ("workshops", "artisans", "products", "tools", "media")
DW_BUCKET = "designWorkshops"
PLAN_BUCKET = "plannedWorkshops"
ALL_BUCKETS = (DW_BUCKET, PLAN_BUCKET, *FIELD_BUCKETS)
#: The Field Repository's buckets, aggregate counts only.
PEER_BUCKETS = ("workshops", "artisans", "products", "tools", "media")

#: Which model a raw spelling came from, for the unmatched report.
MODEL_FOR_BUCKET = {
    DW_BUCKET: "DesignWorkshop",
    PLAN_BUCKET: "AnnualPlanEntry",
    "workshops": "Location",
    "artisans": "Location",
    "products": "Location",
    "tools": "Location",
    "media": "Location",
}

HREF_FOR_BUCKET = {
    DW_BUCKET: "/design-workshops/{id}",
    PLAN_BUCKET: "/annual-plan",
    "workshops": "/workshops/{id}",
    "artisans": "/artisans/{id}",
    "products": "/products/{id}",
    "tools": "/tools/{id}",
    "media": "/media/{id}",
}

KIND_DESIGN_WORKSHOP = "DESIGN_WORKSHOP"
KIND_PLANNED = "PLANNED"
KIND_CLUSTER = "CLUSTER"
KIND_WORKSHOP = "WORKSHOP"
_KIND_ORDER = {KIND_DESIGN_WORKSHOP: 0, KIND_WORKSHOP: 1, KIND_CLUSTER: 2, KIND_PLANNED: 3}

PRECISION_PIN = "SUBJECT_PIN"
PRECISION_CENTROID = "DISTRICT_CENTROID"

PEER_OFF = "OFF"
PEER_OK = "OK"
PEER_UNAVAILABLE = "UNAVAILABLE"

IST = timezone(timedelta(hours=5, minutes=30))

UnitKey = tuple[int, int]


# --------------------------------------------------------------------------------------------
# Financial years
# --------------------------------------------------------------------------------------------


def fy_of(value: datetime | None) -> int | None:
    """The financial year a moment falls in, by its START year: 2026 is FY 2026-27.

    The year turns at 00:00 on 1 April in India Standard Time, whatever zone the value carries. A
    naive value is read as UTC, which is how Prisma hands back a ``timestamp(3)`` column.
    """
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    local = value.astimezone(IST)
    return local.year if local.month >= 4 else local.year - 1


def fy_label(fy: int) -> str:
    return f"{fy}-{(fy + 1) % 100:02d}"


# --------------------------------------------------------------------------------------------
# Filters
# --------------------------------------------------------------------------------------------


def split_values(raw: Iterable[str] | None) -> list[str]:
    """Repeated and comma-joined spellings of one parameter, trimmed and de-duplicated in order."""
    out: list[str] = []
    for value in raw or ():
        for part in str(value).split(","):
            part = part.strip()
            if part and part not in out:
                out.append(part)
    return out


@dataclass(frozen=True)
class Filters:
    level: str = LEVEL_DISTRICT
    craft_codes: tuple[str, ...] = ()
    schemes: tuple[str, ...] = ()
    fys: tuple[int, ...] = ()
    state_lgd: int | None = None
    peer: bool = False

    def payload(self) -> dict[str, Any]:
        return {
            "level": self.level,
            "craftCode": list(self.craft_codes),
            "scheme": list(self.schemes),
            "fy": list(self.fys),
            "stateLgd": self.state_lgd,
        }

    def key(self) -> tuple:
        return (self.level, self.craft_codes, self.schemes, self.fys, self.state_lgd, self.peer)


def parse_filters(
    *,
    level: str | None,
    craft_code: list[str] | None,
    scheme: list[str] | None,
    fy: list[str] | None,
    state_lgd: int | None,
    peer: bool,
) -> Filters:
    """Validate the query. Raises ``ValueError`` with a sentence for a value that cannot mean anything."""
    chosen = (level or LEVEL_DISTRICT).strip().upper()
    if chosen not in LEVELS:
        raise ValueError("level must be STATE or DISTRICT.")
    years: list[int] = []
    for value in split_values(fy):
        try:
            year = int(value)
        except ValueError:
            raise ValueError(f"'{value}' is not a financial year. Send the start year, e.g. 2025.") from None
        if not 1990 <= year <= 2100:
            raise ValueError(f"'{value}' is not a financial year. Send the start year, e.g. 2025.")
        if year not in years:
            years.append(year)
    if state_lgd is not None and lgd.state_name(state_lgd) is None:
        raise ValueError(f"{state_lgd} is not an LGD state code.")
    return Filters(
        level=chosen,
        craft_codes=tuple(split_values(craft_code)),
        schemes=tuple(value.upper() for value in split_values(scheme)),
        fys=tuple(sorted(years)),
        state_lgd=state_lgd,
        peer=peer,
    )


# --------------------------------------------------------------------------------------------
# Craft codes
# --------------------------------------------------------------------------------------------


def craft_code_lookup(admin_spellings: dict[str, str]) -> Callable[[str | None], str | None]:
    """A craft NAME to its shared code: the shared file first, then this portal's admin spellings."""

    def lookup(name: str | None) -> str | None:
        code = cross_portal_codes.shared_craft_code(name)
        if code is not None:
            return code
        key = cross_portal_codes.normalise(name)
        return admin_spellings.get(key) if key else None

    return lookup


def _cluster_key(name: str | None) -> str:
    return cross_portal_codes.normalise(name)


# --------------------------------------------------------------------------------------------
# The fold
# --------------------------------------------------------------------------------------------


@dataclass
class Unit:
    key: UnitKey
    counts: Counter = field(default_factory=Counter)
    craft_codes: set[str] = field(default_factory=set)
    crafts: set[str] = field(default_factory=set)
    clusters: dict[str, str] = field(default_factory=dict)
    peer: Counter | None = None


@dataclass
class Fold:
    filters: Filters
    allowed_states: frozenset[int] | None = None
    aliases: lgd.AliasTable = field(default_factory=dict)
    centroids: dict[int, tuple[float, float]] = field(default_factory=dict)
    units: dict[UnitKey, Unit] = field(default_factory=dict)
    documented: set[str] = field(default_factory=set)
    years: set[int] = field(default_factory=set)
    markers: list[dict[str, Any]] = field(default_factory=list)
    #: (model, rawState, rawDistrict) -> rows that could not be placed at district level.
    unmatched: Counter = field(default_factory=Counter)
    #: Rows whose place was blank or prose nothing could resolve.
    unplaced: int = 0
    #: bucket -> unit -> record stubs (names only), for the unit panel.
    stubs: dict[str, dict[UnitKey, list[dict[str, Any]]]] = field(default_factory=dict)
    truncated: bool = False
    peer_status: str = PEER_OFF
    peer_as_of: str | None = None
    peer_unknown_units: int = 0
    peer_list_version: int | None = None

    # ------------------------------------------------------------------ helpers

    def unit(self, key: UnitKey) -> Unit:
        found = self.units.get(key)
        if found is None:
            found = self.units[key] = Unit(key=key)
        return found

    def in_scope(self, state_code: int | None) -> bool:
        if state_code is None:
            return False
        if self.allowed_states is not None and state_code not in self.allowed_states:
            return False
        return self.filters.state_lgd is None or state_code == self.filters.state_lgd

    def wants_year(self, fy: int | None) -> bool:
        if fy is not None:
            self.years.add(fy)
        return not self.filters.fys or fy in self.filters.fys

    def wants_craft(self, code: str | None) -> bool:
        return not self.filters.craft_codes or code in self.filters.craft_codes

    def wants_scheme(self, scheme: str | None) -> bool:
        return not self.filters.schemes or (scheme or "").upper() in self.filters.schemes

    def place(
        self, model: str, raw_state: str | None, raw_district: str | None, rows: int, place: str | None = None
    ) -> lgd.Resolution:
        """Resolve one raw place and record it in the unmatched report when it falls short."""
        resolution = lgd.resolve(raw_state, raw_district, self.aliases, place)
        state_text = (raw_state or "").strip()
        district_text = (raw_district or "").strip()
        if resolution.matched_by == lgd.UNMATCHED:
            if state_text or district_text:
                self.unmatched[(model, state_text or None, district_text or None)] += rows
            else:
                self.unplaced += rows
        elif (
            resolution.district_lgd is None
            and district_text
            and not resolution.pending
            and resolution.matched_by in (lgd.STATE_ONLY, lgd.GEO_ALIAS)
        ):
            self.unmatched[(model, state_text or None, district_text)] += rows
        return resolution

    def centroid_for(self, key: UnitKey) -> tuple[float, float] | None:
        district = key[1]
        if not district:
            return None
        found = self.centroids.get(district)
        if found is not None:
            return found
        name, state = lgd.district_name(district), lgd.state_name(key[0])
        unit = lgd.lgd_for_unit(state, name) if name else None
        parent = unit.get("parentLgd") if unit else None
        return self.centroids.get(parent) if parent else None

    def add_stub(self, bucket: str, key: UnitKey, stub: dict[str, Any]) -> None:
        self.stubs.setdefault(bucket, {}).setdefault(key, []).append(stub)

    def add_marker(self, marker: dict[str, Any]) -> None:
        self.markers.append(marker)


def _key(resolution: lgd.Resolution) -> UnitKey:
    return (int(resolution.state_lgd or 0), int(resolution.district_lgd or 0))


#: The display column(s) of each field bucket, first non-blank wins (``map_points._title``'s table).
FIELD_TITLE_COLUMNS: dict[str, tuple[str, ...]] = {
    "artisans": ("name",),
    "workshops": ("title",),
    "products": ("productName",),
    "tools": ("toolkitName", "englishName"),
    "media": ("originalFilename",),
}

#: Every column a field bucket's read asks for. A ``group_by`` over exactly these, so the database
#: hands back nothing else: no Aadhaar, no Pehchan, no phone, no address line.
FIELD_COLUMNS: dict[str, tuple[str, ...]] = {
    "artisans": ("id", "name", "locationId", "place", "craftId", "workshopId", "createdAt"),
    "workshops": ("id", "title", "locationId", "place", "date", "startDate", "createdAt"),
    "products": ("id", "productName", "locationId", "place", "craftId", "createdAt"),
    "tools": ("id", "toolkitName", "englishName", "locationId", "place", "craftId", "createdAt"),
    "media": ("id", "originalFilename", "locationId", "craftId", "createdAt"),
}


def _get(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def as_datetime(value: Any) -> datetime | None:
    """A ``group_by`` row hands timestamps back as ISO strings; a model row as datetimes."""
    if value is None or isinstance(value, datetime):
        return value
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def field_title(bucket: str, row: Any) -> str:
    for column in FIELD_TITLE_COLUMNS[bucket]:
        value = _get(row, column)
        if value:
            return str(value)
    return "Untitled record"


def _pin(location: Any) -> tuple[float, float] | None:
    if location is None:
        return None
    lat, lon = getattr(location, "subjectLatitude", None), getattr(location, "subjectLongitude", None)
    if lat is None or lon is None:
        return None
    return (float(lon), float(lat))


def fold_field_rows(
    fold: Fold,
    bucket: str,
    rows: list[Any],
    locations: dict[str, Any],
    craft_codes_by_id: dict[str, str | None],
    workshop_codes: dict[str, set[str]] | None = None,
) -> None:
    """One field bucket, one row at a time: filters, place, count, stub, and a marker for a workshop."""
    model = MODEL_FOR_BUCKET[bucket]
    is_workshop = bucket == "workshops"
    for row in rows:
        if is_workshop:
            when = as_datetime(_get(row, "startDate")) or as_datetime(_get(row, "date"))
        else:
            when = as_datetime(_get(row, "createdAt"))
        fy = fy_of(when)
        if not fold.wants_year(fy):
            continue
        if is_workshop:
            codes = set((workshop_codes or {}).get(_get(row, "id"), set()))
            if fold.filters.craft_codes and not codes & set(fold.filters.craft_codes):
                continue
            craft_ids: set[str] = set()
        else:
            craft_id = _get(row, "craftId")
            code = craft_codes_by_id.get(craft_id) if craft_id else None
            if not fold.wants_craft(code):
                continue
            codes = {code} if code else set()
            craft_ids = {craft_id} if craft_id else set()
        location_id = _get(row, "locationId")
        location = locations.get(location_id) if location_id else None
        resolution = fold.place(
            model,
            getattr(location, "state", None),
            getattr(location, "district", None),
            1,
            _get(row, "place"),
        )
        if not fold.in_scope(resolution.state_lgd):
            continue
        key = _key(resolution)
        unit = fold.unit(key)
        unit.counts[bucket] += 1
        unit.craft_codes.update(codes)
        fold.documented.update(codes)
        unit.crafts.update(codes or {f"id:{cid}" for cid in craft_ids})
        row_id = str(_get(row, "id"))
        title = field_title(bucket, row)
        first = sorted(codes)[0] if codes else None
        href = HREF_FOR_BUCKET[bucket].format(id=row_id)
        fold.add_stub(bucket, key, {"id": row_id, "title": title, "href": href, "craftCode": first, "fy": fy})
        if not is_workshop:
            continue
        pin = _pin(location)
        position = pin or fold.centroid_for(key)
        if position is not None:
            fold.add_marker(
                {
                    "kind": KIND_WORKSHOP,
                    "id": row_id,
                    "title": title,
                    "clusterName": None,
                    "craftCode": first,
                    "stateLgd": key[0],
                    "districtLgd": key[1] or None,
                    "lon": round(position[0], 4),
                    "lat": round(position[1], 4),
                    "precision": PRECISION_PIN if pin else PRECISION_CENTROID,
                    "href": href,
                }
            )


def _cluster(fold: Fold, unit: Unit, name: str | None) -> None:
    key = _cluster_key(name)
    if key and key not in unit.clusters:
        unit.clusters[key] = " ".join(str(name).split())


def fold_design_workshops(
    fold: Fold, rows: list[Any], craft_code: Callable[[str | None], str | None]
) -> None:
    """``DesignWorkshop`` rows, already narrowed by the caller's scope."""
    for row in rows:
        when = getattr(row, "startDate", None) or getattr(row, "createdAt", None)
        fy = fy_of(when)
        if not fold.wants_year(fy):
            continue
        if not fold.wants_scheme(getattr(row, "workshopKind", None)):
            continue
        code = craft_code(getattr(row, "craftName", None))
        if not fold.wants_craft(code):
            continue
        resolution = fold.place("DesignWorkshop", row.state, row.district, 1)
        if not fold.in_scope(resolution.state_lgd):
            continue
        key = _key(resolution)
        unit = fold.unit(key)
        unit.counts[DW_BUCKET] += 1
        if code:
            unit.craft_codes.add(code)
            unit.crafts.add(code)
            fold.documented.add(code)
        _cluster(fold, unit, getattr(row, "clusterName", None))
        title = getattr(row, "title", None) or "Untitled workshop"
        fold.add_stub(
            DW_BUCKET,
            key,
            {"id": row.id, "title": title, "href": f"/design-workshops/{row.id}", "craftCode": code, "fy": fy},
        )
        position = fold.centroid_for(key)
        if position is not None:
            fold.add_marker(
                {
                    "kind": KIND_DESIGN_WORKSHOP,
                    "id": row.id,
                    "title": title,
                    "clusterName": getattr(row, "clusterName", None),
                    "craftCode": code,
                    "stateLgd": key[0],
                    "districtLgd": key[1] or None,
                    "lon": position[0],
                    "lat": position[1],
                    "precision": PRECISION_CENTROID,
                    "href": f"/design-workshops/{row.id}",
                }
            )


def fold_plans(fold: Fold, rows: list[Any], craft_code: Callable[[str | None], str | None]) -> None:
    """``AnnualPlanEntry`` rows that are not withdrawn. ``planYear`` IS the financial year."""
    for row in rows:
        fy = getattr(row, "planYear", None)
        if not fold.wants_year(fy):
            continue
        if not fold.wants_scheme(getattr(row, "workshopKind", None)):
            continue
        code = craft_code(getattr(row, "craftName", None))
        if not fold.wants_craft(code):
            continue
        resolution = fold.place("AnnualPlanEntry", row.state, row.district, 1)
        if not fold.in_scope(resolution.state_lgd):
            continue
        key = _key(resolution)
        unit = fold.unit(key)
        unit.counts[PLAN_BUCKET] += 1
        _cluster(fold, unit, getattr(row, "clusterName", None))
        promoted = getattr(row, "designWorkshopId", None)
        title = getattr(row, "plannedTitle", None) or f"Planned workshop {getattr(row, 'workshopNo', '')}".strip()
        href = f"/design-workshops/{promoted}" if promoted else "/annual-plan"
        fold.add_stub(PLAN_BUCKET, key, {"id": row.id, "title": title, "href": href, "craftCode": code, "fy": fy})
        position = fold.centroid_for(key)
        if position is not None:
            fold.add_marker(
                {
                    "kind": KIND_PLANNED,
                    "id": row.id,
                    "title": title,
                    "clusterName": getattr(row, "clusterName", None),
                    "craftCode": code,
                    "stateLgd": key[0],
                    "districtLgd": key[1] or None,
                    "lon": position[0],
                    "lat": position[1],
                    "precision": PRECISION_CENTROID,
                    "href": href,
                }
            )


def fold_peer(fold: Fold, answer: dict[str, Any]) -> None:
    """Merge the Field Repository's aggregate counts as a SEPARATE row on each unit.

    A unit whose code this register does not know (the two portals on different register versions)
    is dropped and counted in ``peer_unknown_units`` rather than guessed at.
    """
    reg = lgd.register()
    fold.peer_as_of = answer.get("asOf")
    fold.peer_list_version = answer.get("listVersion")
    for row in answer.get("units") or []:
        try:
            state_code = int(row.get("stateLgd") or 0)
            district_code = int(row.get("districtLgd") or 0)
        except (TypeError, ValueError):
            fold.peer_unknown_units += 1
            continue
        if state_code not in reg.state_name or (
            district_code and reg.district_row.get(district_code, (None,))[0] != state_code
        ):
            fold.peer_unknown_units += 1
            continue
        if not fold.in_scope(state_code):
            continue
        counts = row.get("counts") or {}
        unit = fold.unit((state_code, district_code))
        if unit.peer is None:
            unit.peer = Counter()
        for bucket in PEER_BUCKETS:
            try:
                unit.peer[bucket] += int(counts.get(bucket) or 0)
            except (TypeError, ValueError):
                continue
    for code in answer.get("craftCodesDocumented") or []:
        if isinstance(code, str) and (not fold.filters.craft_codes or code in fold.filters.craft_codes):
            fold.documented.add(code)


def add_cluster_markers(fold: Fold) -> None:
    """One CLUSTER marker per named cluster per district, at the district's representative point."""
    for key, unit in fold.units.items():
        position = fold.centroid_for(key)
        if position is None:
            continue
        for cluster_key, name in sorted(unit.clusters.items()):
            fold.add_marker(
                {
                    "kind": KIND_CLUSTER,
                    "id": f"{key[1]}:{cluster_key}",
                    "title": name,
                    "clusterName": name,
                    "craftCode": sorted(unit.craft_codes)[0] if unit.craft_codes else None,
                    "stateLgd": key[0],
                    "districtLgd": key[1] or None,
                    "lon": position[0],
                    "lat": position[1],
                    "precision": PRECISION_CENTROID,
                    "href": f"/design-workshops?q={cluster_key.replace(' ', '+')}",
                }
            )


# --------------------------------------------------------------------------------------------
# Building the answer
# --------------------------------------------------------------------------------------------

_COUNT_KEYS = (DW_BUCKET, PLAN_BUCKET, "workshops", "artisans", "crafts", "products", "tools", "media")


def _held(unit: Unit, with_peer: bool) -> bool:
    if unit.counts[DW_BUCKET] or unit.counts["workshops"]:
        return True
    return bool(with_peer and unit.peer and unit.peer["workshops"])


def _unit_payload(fold: Fold, key: UnitKey, units: list[Unit]) -> dict[str, Any]:
    state = lgd.state_name(key[0])
    district = lgd.district_name(key[1]) if key[1] else None
    shape = lgd.lgd_for_unit(state, district)
    counts: Counter = Counter()
    codes: set[str] = set()
    crafts: set[str] = set()
    clusters: dict[str, str] = {}
    peer: Counter | None = None
    for unit in units:
        counts.update(unit.counts)
        codes |= unit.craft_codes
        crafts |= unit.crafts
        for cluster_key, name in unit.clusters.items():
            clusters.setdefault(cluster_key, name)
        if unit.peer is not None:
            peer = (peer or Counter()) + unit.peer
            if peer is not None:
                for bucket in PEER_BUCKETS:
                    peer.setdefault(bucket, 0)
    payload_counts = {name: int(counts.get(name, 0)) for name in _COUNT_KEYS}
    payload_counts["crafts"] = len(crafts)
    return {
        "stateLgd": key[0],
        "districtLgd": key[1] or None,
        "state": state,
        "district": district,
        "polygon": shape["polygon"],
        "parentLgd": shape["parentLgd"],
        "counts": payload_counts,
        "peer": {bucket: int(peer.get(bucket, 0)) for bucket in PEER_BUCKETS} if peer is not None else None,
        "craftCodes": sorted(codes),
        "clusters": sorted(clusters.values()),
    }


def _has_any(payload: dict[str, Any]) -> bool:
    if any(payload["counts"].get(name) for name in _COUNT_KEYS if name != "crafts"):
        return True
    return bool(payload["peer"] and any(payload["peer"].values()))


def build_units(fold: Fold) -> list[dict[str, Any]]:
    grouped: dict[UnitKey, list[Unit]] = {}
    for key, unit in fold.units.items():
        target = (key[0], 0) if fold.filters.level == LEVEL_STATE else key
        grouped.setdefault(target, []).append(unit)
    payloads = [_unit_payload(fold, key, units) for key, units in grouped.items()]
    return sorted((p for p in payloads if _has_any(p)), key=lambda p: (p["stateLgd"], p["districtLgd"] or 0))


def build_gaps(fold: Fold) -> dict[str, Any]:
    reg = lgd.register()
    with_peer = fold.peer_status == PEER_OK
    held_districts: set[tuple[str, str]] = set()
    held_states: set[int] = set()
    for key, unit in fold.units.items():
        if not _held(unit, with_peer):
            continue
        held_states.add(key[0])
        if key[1]:
            row = reg.district_row.get(key[1])
            if row:
                held_districts.add((row[1], row[2]))

    catalogue = cross_portal_codes.catalogue()
    if fold.filters.craft_codes:
        catalogue = [c for c in catalogue if c["code"] in fold.filters.craft_codes]
    never = [c for c in catalogue if c["code"] not in fold.documented]

    from app.services.address import DISTRICTS_BY_STATE

    by_state: list[dict[str, Any]] = []
    total_without = 0
    for state_code in sorted(reg.state_name):
        if not fold.in_scope(state_code):
            continue
        state = reg.state_name[state_code]
        names = DISTRICTS_BY_STATE.get(state, ())
        without = [
            {"lgd": reg.district_by_key.get((state, name)), "name": name}
            for name in names
            if (state, name) not in held_districts
        ]
        if without:
            total_without += len(without)
            by_state.append({"stateLgd": state_code, "state": state, "total": len(names), "without": without})
    states_without = [
        {"lgd": code, "name": reg.state_name[code]}
        for code in sorted(reg.state_name)
        if fold.in_scope(code) and code not in held_states
    ]
    return {
        "craftsNeverDocumented": never,
        "districtsWithoutWorkshop": {"count": total_without, "byState": by_state},
        "statesWithoutWorkshop": states_without,
    }


def build_markers(fold: Fold) -> tuple[list[dict[str, Any]], bool]:
    ordered = sorted(fold.markers, key=lambda m: (_KIND_ORDER.get(m["kind"], 9), m["title"] or "", m["id"]))
    return ordered[:MARKER_CAP], len(ordered) > MARKER_CAP


def unmatched_items(fold: Fold) -> list[dict[str, Any]]:
    items = [
        {"model": model, "rawState": raw_state, "rawDistrict": raw_district, "rows": rows}
        for (model, raw_state, raw_district), rows in fold.unmatched.items()
    ]
    return sorted(items, key=lambda i: (-i["rows"], i["model"], i["rawState"] or "", i["rawDistrict"] or ""))


def suggestion_for(raw_state: str | None, raw_district: str | None) -> dict[str, Any] | None:
    """A suggestion only from ``canonical_state`` plus an EXACT district of another state. No fuzzing."""
    from app.services.address import DISTRICTS_BY_STATE, canonical_district

    state_code = lgd.state_lgd(raw_state)
    text = (raw_district or "").strip()
    if not text:
        return None
    homes = [
        (state, found)
        for state in DISTRICTS_BY_STATE
        if (found := canonical_district(state, text)) is not None
    ]
    if len(homes) != 1:
        return None
    state, district = homes[0]
    code = lgd.district_lgd(state, district)
    if code is None:
        return None
    reason = (
        f"{district} is a district of {state}"
        + (f", not {lgd.state_name(state_code)}" if state_code and lgd.state_name(state_code) != state else "")
        + "."
    )
    return {"stateLgd": lgd.state_lgd(state), "districtLgd": code, "reason": reason}


def build_payload(fold: Fold, *, is_admin: bool, boundaries: dict[str, Any], catalogue: dict[str, Any]) -> dict:
    reg = lgd.register()
    markers, markers_truncated = build_markers(fold)
    unmatched: dict[str, Any] = {"count": sum(fold.unmatched.values()), "unplaced": fold.unplaced}
    if is_admin:
        unmatched["sample"] = unmatched_items(fold)[:UNMATCHED_SAMPLE]
    years = sorted(set(fold.years) | set(fold.filters.fys) | {fy_of(datetime.now(UTC))}, reverse=True)
    peer: dict[str, Any] = {"status": fold.peer_status, "asOf": fold.peer_as_of}
    if fold.peer_unknown_units:
        peer["unknownUnits"] = fold.peer_unknown_units
    if fold.peer_status == PEER_OK and fold.filters.schemes:
        peer["schemeIgnored"] = True
    return {
        "asOf": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "register": {
            "lgdAsOf": reg.as_of,
            "listVersion": reg.list_version,
            "districtCount": reg.district_count,
            "stateCount": reg.state_count,
        },
        "boundaries": boundaries,
        "filters": fold.filters.payload(),
        "catalogue": {**catalogue, "years": [{"fy": y, "label": fy_label(y)} for y in years if y is not None]},
        "units": build_units(fold),
        "markers": markers,
        "markersTruncated": markers_truncated,
        "gaps": build_gaps(fold),
        "unmatched": unmatched,
        "peer": peer,
        "truncated": fold.truncated,
    }


# --------------------------------------------------------------------------------------------
# The in-process cache
# --------------------------------------------------------------------------------------------


class TtlCache:
    """A small dictionary with an expiry per entry. Per process, which is the whole deployment."""

    def __init__(self, seconds: int, limit: int = 256) -> None:
        self.seconds = seconds
        self.limit = limit
        self._store: dict[Any, tuple[float, Any]] = {}
        self._lock = asyncio.Lock()

    def get(self, key: Any) -> Any | None:
        hit = self._store.get(key)
        if hit is None:
            return None
        if hit[0] < time.monotonic():
            self._store.pop(key, None)
            return None
        return hit[1]

    def put(self, key: Any, value: Any) -> None:
        if len(self._store) >= self.limit:
            oldest = min(self._store, key=lambda k: self._store[k][0])
            self._store.pop(oldest, None)
        self._store[key] = (time.monotonic() + self.seconds, value)

    def clear(self) -> None:
        self._store.clear()

    @property
    def lock(self) -> asyncio.Lock:
        return self._lock


FOLD_CACHE = TtlCache(CACHE_SECONDS)
PEER_CACHE = TtlCache(PEER_CACHE_SECONDS, limit=64)


def clear_caches() -> None:
    """Called after an alias is written or deleted, so the next read re-resolves every place."""
    FOLD_CACHE.clear()
    CORPUS_CACHE.clear()


# --------------------------------------------------------------------------------------------
# Reading the corpus
# --------------------------------------------------------------------------------------------

BOUNDARIES_DIR = Path(__file__).resolve().parents[1] / "data" / "boundaries"
CENTROIDS_FILE = BOUNDARIES_DIR / "district-centroids.json"
SHAPES_MANIFEST_FILE = BOUNDARIES_DIR / "shapes-manifest.json"
DEPICTION = "Survey of India"

#: Location rows read per ``IN`` list, so a full bucket never builds one enormous statement.
_LOCATION_CHUNK = 5_000

#: Rows read once per caller scope and reused by every filter for :data:`CACHE_SECONDS`.
CORPUS_CACHE = TtlCache(CACHE_SECONDS, limit=64)

_DELEGATES = {
    "artisans": "artisan",
    "workshops": "workshop",
    "products": "productdocumentation",
    "tools": "tooldocumentation",
    "media": "mediafile",
}


@lru_cache(maxsize=1)
def centroids() -> dict[int, tuple[float, float]]:
    """``district LGD -> (lon, lat)``, a point inside the district's own polygon."""
    try:
        raw = json.loads(CENTROIDS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return {int(code): (float(point[0]), float(point[1])) for code, point in raw.items()}


@lru_cache(maxsize=1)
def boundaries_info() -> dict[str, Any]:
    try:
        manifest = json.loads(SHAPES_MANIFEST_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        manifest = {}
    return {"shapesVersion": manifest.get("shapesVersion"), "depiction": DEPICTION}


def scheme_catalogue() -> list[dict[str, str]]:
    from app.services.stage_schema import ENUMS

    return [{"value": value, "label": label} for value, label in ENUMS["WORKSHOP_KIND"].items()]


def catalogue_payload() -> dict[str, Any]:
    return {"crafts": cross_portal_codes.catalogue(), "schemes": scheme_catalogue()}


@dataclass
class Corpus:
    """Every row one caller's map is folded from, before any coverage filter is applied."""

    field_rows: dict[str, list[Any]] = field(default_factory=dict)
    locations: dict[str, Any] = field(default_factory=dict)
    craft_codes_by_id: dict[str, str | None] = field(default_factory=dict)
    workshop_codes: dict[str, set[str]] = field(default_factory=dict)
    admin_spellings: dict[str, str] = field(default_factory=dict)
    aliases: dict[tuple[str, str], tuple[int, int | None]] = field(default_factory=dict)
    design_workshops: list[Any] = field(default_factory=list)
    plans: list[Any] = field(default_factory=list)
    allowed_states: frozenset[int] | None = None
    truncated: bool = False


def _capped(rows: list[Any] | None) -> tuple[list[Any], bool]:
    rows = list(rows or [])
    return rows[:ROW_CAP], len(rows) > ROW_CAP


async def load_aliases(client: Any) -> dict[tuple[str, str], tuple[int, int | None]]:
    rows = await client.geoalias.find_many()
    return {
        (row.rawStateKey, row.rawDistrictKey or ""): (int(row.stateLgd), row.districtLgd)
        for row in rows
    }


async def _locations(client: Any, ids: set[str]) -> dict[str, Any]:
    wanted = sorted(i for i in ids if i)
    found: dict[str, Any] = {}
    for start in range(0, len(wanted), _LOCATION_CHUNK):
        chunk = wanted[start : start + _LOCATION_CHUNK]
        for row in await client.location.find_many(where={"id": {"in": chunk}}):
            found[row.id] = row
    return found


async def _allowed_states(client: Any, user: Any, scoped_workshops: list[Any]) -> frozenset[int] | None:
    """``None`` for the whole country; else the states this officer answers for.

    A Regional Director's assigned states, plus the states of every workshop the officer was named
    on (an Assistant Director has no state assignment, only workshops).
    """
    from app.core.deps import role_value
    from app.services import ministry_dashboard

    if ministry_dashboard.sees_whole_estate(user):
        return None
    states: set[int] = set()
    if role_value(user) == "REGIONAL_DIRECTOR":
        for row in await client.regionaldirectorstate.find_many(where={"userId": str(user.id)}):
            code = lgd.state_lgd(row.state)
            if code is not None:
                states.add(code)
    for row in scoped_workshops:
        code = lgd.state_lgd(getattr(row, "state", None))
        if code is not None:
            states.add(code)
    return frozenset(states)


def scope_key(user: Any) -> str:
    from app.services import ministry_dashboard

    if ministry_dashboard.sees_whole_estate(user):
        return "estate"
    return f"user:{getattr(user, 'id', '')}"


async def load_corpus(user: Any) -> Corpus:
    """One wave of reads for the caller's scope; cached for :data:`CACHE_SECONDS`."""
    key = scope_key(user)
    cached = CORPUS_CACHE.get(key)
    if cached is not None:
        return cached
    corpus = await read_via_replica(lambda client: read_corpus(client, user))
    CORPUS_CACHE.put(key, corpus)
    return corpus


async def _empty() -> list[Any]:
    return []


async def read_corpus(client: Any, user: Any) -> Corpus:
    """The reads themselves. Every record ``where`` comes from ``build_record_wheres``."""
    from app.services import annual_plan, ministry_dashboard

    wheres = await build_record_wheres(user)
    dw_where = design_workshop_where({})
    scope = ministry_dashboard.scope_clause(user)
    if scope is not None:
        dw_where["AND"].append(scope)
    plan_states = await annual_plan.plan_scope(user)
    plan_where: dict[str, Any] = {"withdrawnAt": None}
    if plan_states is not None:
        plan_where["state"] = {"in": plan_states}

    results = await gather_reads(
        *(
            getattr(client, _DELEGATES[bucket]).group_by(
                by=list(FIELD_COLUMNS[bucket]),
                where=wheres[bucket],
                take=ROW_CAP + 1,
                order={"id": "asc"},
            )
            for bucket in FIELD_BUCKETS
        ),
        client.designworkshop.find_many(where=dw_where, take=ROW_CAP + 1, order={"id": "asc"}),
        client.annualplanentry.find_many(where=plan_where, take=ROW_CAP + 1, order={"id": "asc"})
        if plan_states is None or plan_states
        else _empty(),
        client.craft.find_many(),
        client.craftspelling.find_many(),
        load_aliases(client),
    )
    corpus = Corpus()
    location_ids: set[str] = set()
    for bucket, rows in zip(FIELD_BUCKETS, results[: len(FIELD_BUCKETS)], strict=True):
        kept, cut = _capped(rows)
        corpus.field_rows[bucket] = kept
        corpus.truncated |= cut
        location_ids.update(str(_get(r, "locationId")) for r in kept if _get(r, "locationId"))
    dws, plans, crafts, spellings, aliases = results[len(FIELD_BUCKETS) :]
    corpus.design_workshops, cut_dw = _capped(dws)
    corpus.plans, cut_plans = _capped(plans)
    corpus.truncated |= cut_dw or cut_plans
    corpus.aliases = aliases
    corpus.craft_codes_by_id = {c.id: getattr(c, "craftCode", None) for c in crafts or []}
    for craft in crafts or []:
        if getattr(craft, "workshopId", None) and getattr(craft, "craftCode", None):
            corpus.workshop_codes.setdefault(craft.workshopId, set()).add(craft.craftCode)
    for row in corpus.field_rows.get("artisans", []):
        craft_id, workshop_id = _get(row, "craftId"), _get(row, "workshopId")
        code = corpus.craft_codes_by_id.get(craft_id) if craft_id else None
        if code and workshop_id:
            corpus.workshop_codes.setdefault(workshop_id, set()).add(code)
    corpus.admin_spellings = {
        row.spelling: row.craftCode for row in spellings or [] if getattr(row, "craftCode", None)
    }
    corpus.locations = await _locations(client, location_ids)
    corpus.allowed_states = await _allowed_states(client, user, corpus.design_workshops)
    return corpus


def fold_corpus(corpus: Corpus, filters: Filters) -> Fold:
    """Every bucket folded under ``filters``. Pure over the corpus, so cheap to repeat per filter."""
    fold = Fold(
        filters=filters,
        allowed_states=corpus.allowed_states,
        aliases=corpus.aliases,
        centroids=centroids(),
    )
    fold.truncated = corpus.truncated
    craft_code = craft_code_lookup(corpus.admin_spellings)
    fold_design_workshops(fold, corpus.design_workshops, craft_code)
    fold_plans(fold, corpus.plans, craft_code)
    # Field records carry no scheme, so a scheme filter leaves them out rather than pretending.
    if not filters.schemes:
        for bucket in FIELD_BUCKETS:
            fold_field_rows(
                fold,
                bucket,
                corpus.field_rows.get(bucket, []),
                corpus.locations,
                corpus.craft_codes_by_id,
                corpus.workshop_codes,
            )
    add_cluster_markers(fold)
    return fold


# --------------------------------------------------------------------------------------------
# The Field Repository's counts
# --------------------------------------------------------------------------------------------


def coverage_peer_active(settings: Any) -> bool:
    """The coverage feed needs its own flag, the shared token and the Field Repository's address."""
    from app.services import cross_portal

    if not getattr(settings, "cross_portal_coverage_enabled", False):
        return False
    if not cross_portal._secret_ok(getattr(settings, "cross_portal_token", None)):
        return False
    return bool((getattr(settings, "field_repository_api_url", None) or "").strip())


async def fetch_peer(
    settings: Any, filters: Filters, requested_by: str | None
) -> tuple[str, dict[str, Any] | None]:
    """``(status, answer)``. Any failure is ``UNAVAILABLE`` and the map draws this portal's data."""
    from app.services.cross_portal_client import PeerUnavailable, peer_call

    if not coverage_peer_active(settings):
        return PEER_OFF, None
    key = (filters.craft_codes, filters.fys, filters.schemes)
    cached = PEER_CACHE.get(key)
    if cached is not None:
        return PEER_OK, cached
    params: dict[str, str] = {}
    if filters.craft_codes:
        params["craftCode"] = ",".join(filters.craft_codes)
    if filters.fys:
        params["fy"] = ",".join(str(y) for y in filters.fys)
    if filters.schemes:
        params["scheme"] = ",".join(filters.schemes)
    try:
        status_code, answer = await peer_call(
            settings,
            "GET",
            "/coverage",
            params=params or None,
            requested_by=requested_by,
            gate=coverage_peer_active,
        )
    except PeerUnavailable:
        return PEER_UNAVAILABLE, None
    if status_code != 200 or not isinstance(answer, dict):
        return PEER_UNAVAILABLE, None
    PEER_CACHE.put(key, answer)
    return PEER_OK, answer


# --------------------------------------------------------------------------------------------
# The three answers the routes return
# --------------------------------------------------------------------------------------------


async def coverage(user: Any, filters: Filters, *, settings: Any, is_admin: bool) -> dict[str, Any]:
    """The whole ``GET /map/coverage`` body, cached per (scope, parameters)."""
    cache_key = (scope_key(user), filters.key(), is_admin)
    cached = FOLD_CACHE.get(cache_key)
    if cached is not None:
        return cached
    corpus = await load_corpus(user)
    fold = fold_corpus(corpus, filters)
    if filters.peer:
        peer_status, answer = await fetch_peer(settings, filters, getattr(user, "id", None))
        fold.peer_status = peer_status
        if answer is not None:
            fold_peer(fold, answer)
    payload = build_payload(
        fold, is_admin=is_admin, boundaries=boundaries_info(), catalogue=catalogue_payload()
    )
    FOLD_CACHE.put(cache_key, payload)
    return payload


def peer_href(settings: Any, filters: Filters, state_code: int, district_code: int) -> str | None:
    """The same unit on the Field Repository's own coverage map, when the feed is on."""
    from urllib.parse import urlencode

    base = (getattr(settings, "field_repository_web_url", None) or "").strip().rstrip("/")
    if not base or not coverage_peer_active(settings):
        return None
    query = [("view", "coverage"), ("stateLgd", str(state_code))]
    if district_code:
        query.append(("districtLgd", str(district_code)))
    query.extend(("craftCode", code) for code in filters.craft_codes)
    query.extend(("fy", str(year)) for year in filters.fys)
    return f"{base}/map?{urlencode(query)}"


def _offset(cursor: str | None) -> int:
    try:
        return max(0, int(cursor or 0))
    except ValueError:
        return 0


async def unit_records(
    user: Any,
    filters: Filters,
    state_code: int,
    district_code: int,
    *,
    bucket: str | None,
    cursor: str | None,
    settings: Any,
) -> dict[str, Any] | None:
    """The records behind one unit, a page per bucket. ``None`` when the codes name no unit."""
    if lgd.state_name(state_code) is None:
        return None
    if district_code and lgd.district_state(district_code) != state_code:
        return None
    corpus = await load_corpus(user)
    fold = fold_corpus(corpus, filters)
    whole_state = filters.level == LEVEL_STATE
    keys = sorted(
        key for key in fold.units if key[0] == state_code and (whole_state or key[1] == district_code)
    )
    target = (state_code, 0 if whole_state else district_code)
    unit = _unit_payload(fold, target, [fold.units[key] for key in keys])
    offset = _offset(cursor)
    buckets: dict[str, Any] = {}
    for name in [bucket] if bucket else list(ALL_BUCKETS):
        stubs: list[dict[str, Any]] = []
        for key in keys:
            stubs.extend(fold.stubs.get(name, {}).get(key, []))
        if not stubs and not bucket:
            continue
        stubs.sort(key=lambda s: (-(s.get("fy") or 0), s["title"].lower(), s["id"]))
        start = offset if bucket else 0
        following = start + PAGE_SIZE
        buckets[name] = {
            "total": len(stubs),
            "items": stubs[start:following],
            "nextCursor": str(following) if following < len(stubs) else None,
        }
    return {
        "unit": unit,
        "buckets": buckets,
        "peerHref": peer_href(settings, filters, state_code, 0 if whole_state else district_code),
    }


async def unmatched_report(user: Any) -> dict[str, Any]:
    """Every raw spelling that could not be placed at district level, busiest first."""
    corpus = await load_corpus(user)
    fold = fold_corpus(corpus, Filters())
    items = unmatched_items(fold)
    for item in items:
        item["suggestion"] = suggestion_for(item["rawState"], item["rawDistrict"])
    reg = lgd.register()
    # The codes the mapping form picks from, so the client needs no copy of the register.
    register_payload = {
        "states": [{"lgd": code, "name": name} for code, name in sorted(reg.state_name.items())],
        "districts": [
            {"lgd": code, "stateLgd": row[0], "name": row[2]}
            for code, row in sorted(reg.district_row.items(), key=lambda kv: (kv[1][1], kv[1][2]))
        ],
    }
    return {"items": items, "total": len(items), "register": register_payload}
