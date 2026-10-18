"""THE COVERAGE MAP (designer portal): the folds, the filters, the gaps, the peer merge and the doors.

The first part needs no database: ``services/coverage`` folds already-read rows, so the counting
rules are asserted over a hand-built ``Corpus``. The last part seeds a private database and asks the
routes as each post, because "a regional director sees only their states" and "only the master admin
and the Ministry Admin map a place" are only true if the route says so.
"""

from __future__ import annotations

import asyncio
import json
import re
import uuid
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db

from app.services import coverage, cross_portal_codes, lgd
from app.services.cross_portal_client import PeerUnavailable

BARMER = lgd.district_lgd("Rajasthan", "Barmer")
JAIPUR = lgd.district_lgd("Rajasthan", "Jaipur")
KACHCHH = lgd.district_lgd("Gujarat", "Kachchh")
RAJASTHAN = lgd.state_lgd("Rajasthan")
GUJARAT = lgd.state_lgd("Gujarat")
AJRAKH = cross_portal_codes.shared_craft_code("Ajrakh")


def _loc(state: str | None, district: str | None, pin: tuple[float, float] | None = None) -> Any:
    return SimpleNamespace(
        id=f"loc-{state}-{district}-{pin}",
        state=state,
        district=district,
        subjectLatitude=pin[1] if pin else None,
        subjectLongitude=pin[0] if pin else None,
    )


def _dw(i: int, state: str, district: str, *, kind: str = "DESIGN_PROTOTYPE_DEVELOPMENT", craft: str = "Ajrakh",
        start: datetime | None = None, cluster: str | None = "Barmer Ajrakh") -> Any:
    return SimpleNamespace(
        id=f"dw{i}",
        title=f"Workshop {i}",
        state=state,
        district=district,
        workshopKind=kind,
        craftName=craft,
        clusterName=cluster,
        startDate=start or datetime(2025, 6, 1, tzinfo=UTC),
        createdAt=datetime(2025, 5, 1, tzinfo=UTC),
    )


def _plan(i: int, state: str, district: str, year: int = 2026, craft: str = "Ajrakh") -> Any:
    return SimpleNamespace(
        id=f"plan{i}",
        planYear=year,
        workshopNo=f"P{i}",
        plannedTitle=None,
        workshopKind="DESIGN_PROTOTYPE_DEVELOPMENT",
        craftName=craft,
        clusterName=None,
        state=state,
        district=district,
        designWorkshopId=None,
    )


def _corpus(**kwargs: Any) -> coverage.Corpus:
    corpus = coverage.Corpus()
    for key, value in kwargs.items():
        setattr(corpus, key, value)
    return corpus


def _barmer_corpus() -> coverage.Corpus:
    barmer = _loc("Rajasthan", "Barmer")
    pinned = _loc("Rajasthan", "Jaipur", (75.8, 26.9))
    return _corpus(
        locations={barmer.id: barmer, pinned.id: pinned},
        craft_codes_by_id={"c-ajrakh": AJRAKH, "c-none": None},
        workshop_codes={"w1": {AJRAKH}},
        design_workshops=[_dw(1, "Rajasthan", "Barmer"), _dw(2, "Rajasthan", "Barmer", kind="SKILL_UPGRADATION")],
        plans=[_plan(1, "Rajasthan", "Barmer"), _plan(2, "Gujarat", "Kutch")],
        field_rows={
            "artisans": [
                {"id": "a1", "name": "Ishaq", "locationId": barmer.id, "place": "Barmer", "craftId": "c-ajrakh",
                 "createdAt": "2025-07-01T00:00:00Z"},
                {"id": "a2", "name": "Nobody", "locationId": None, "place": "Atlantis", "craftId": None,
                 "createdAt": "2025-07-01T00:00:00Z"},
            ],
            "workshops": [
                {"id": "w1", "title": "Field workshop", "locationId": pinned.id, "place": "Jaipur",
                 "date": "2024-12-01T00:00:00Z", "startDate": None, "createdAt": "2024-12-01T00:00:00Z"},
            ],
            "products": [
                {"id": "p1", "productName": "Stole", "locationId": barmer.id, "place": "", "craftId": "c-ajrakh",
                 "createdAt": "2025-08-01T00:00:00Z"},
            ],
            "tools": [],
            "media": [
                {"id": "m1", "originalFilename": "a.jpg", "locationId": barmer.id, "craftId": None,
                 "createdAt": "2025-08-01T00:00:00Z"},
            ],
        },
    )


def _unit(payload: dict[str, Any], district: int | None) -> dict[str, Any]:
    return next(u for u in payload["units"] if u["districtLgd"] == district)


def _payload(corpus: coverage.Corpus, filters: coverage.Filters | None = None, *, admin: bool = False) -> dict:
    fold = coverage.fold_corpus(corpus, filters or coverage.Filters())
    return coverage.build_payload(fold, is_admin=admin, boundaries={"shapesVersion": "x"}, catalogue={})


# --------------------------------------------------------------------------------------------
# Counts
# --------------------------------------------------------------------------------------------


def test_counts_per_unit_for_each_bucket():
    payload = _payload(_barmer_corpus())
    barmer = _unit(payload, BARMER)
    assert barmer["counts"]["designWorkshops"] == 2
    assert barmer["counts"]["plannedWorkshops"] == 1
    assert barmer["counts"]["artisans"] == 1
    assert barmer["counts"]["products"] == 1
    assert barmer["counts"]["media"] == 1
    assert barmer["counts"]["crafts"] == 1
    assert barmer["craftCodes"] == [AJRAKH]
    assert barmer["clusters"] == ["Barmer Ajrakh"]
    assert barmer["polygon"] == "OWN" and barmer["state"] == "Rajasthan" and barmer["district"] == "Barmer"
    jaipur = _unit(payload, JAIPUR)
    assert jaipur["counts"]["workshops"] == 1
    # The alias "Kutch" resolves to Kachchh.
    assert _unit(payload, KACHCHH)["counts"]["plannedWorkshops"] == 1


def test_planned_and_held_are_never_added_together():
    barmer = _unit(_payload(_barmer_corpus()), BARMER)
    assert barmer["counts"]["designWorkshops"] == 2 and barmer["counts"]["plannedWorkshops"] == 1


def test_units_are_sparse():
    payload = _payload(_barmer_corpus())
    assert {u["districtLgd"] for u in payload["units"]} == {BARMER, JAIPUR, KACHCHH}
    assert payload["register"]["districtCount"] == 795 and payload["register"]["stateCount"] == 36


def test_state_level_rolls_districts_up():
    payload = _payload(_barmer_corpus(), coverage.Filters(level="STATE"))
    rajasthan = next(u for u in payload["units"] if u["stateLgd"] == RAJASTHAN)
    assert rajasthan["districtLgd"] is None
    assert rajasthan["counts"]["designWorkshops"] == 2 and rajasthan["counts"]["workshops"] == 1


def test_markers_use_the_pin_then_the_centroid():
    payload = _payload(_barmer_corpus())
    field = next(m for m in payload["markers"] if m["kind"] == "WORKSHOP")
    assert field["precision"] == "SUBJECT_PIN" and (field["lon"], field["lat"]) == (75.8, 26.9)
    held = [m for m in payload["markers"] if m["kind"] == "DESIGN_WORKSHOP"]
    if coverage.centroids().get(BARMER):
        assert held and all(m["precision"] == "DISTRICT_CENTROID" for m in held)
        assert held[0]["href"] == "/design-workshops/dw1"
        assert any(m["kind"] == "CLUSTER" for m in payload["markers"])


# --------------------------------------------------------------------------------------------
# Filters
# --------------------------------------------------------------------------------------------


def test_scheme_filter_narrows_design_workshops_and_leaves_field_records_out():
    payload = _payload(_barmer_corpus(), coverage.Filters(schemes=("SKILL_UPGRADATION",)))
    barmer = _unit(payload, BARMER)
    assert barmer["counts"]["designWorkshops"] == 1
    assert barmer["counts"]["artisans"] == 0 and barmer["counts"]["plannedWorkshops"] == 0


def test_craft_filter():
    corpus = _barmer_corpus()
    corpus.design_workshops.append(_dw(3, "Rajasthan", "Barmer", craft="Something unheard of"))
    payload = _payload(corpus, coverage.Filters(craft_codes=(AJRAKH,)))
    barmer = _unit(payload, BARMER)
    assert barmer["counts"]["designWorkshops"] == 2
    assert barmer["counts"]["media"] == 0  # the photo carries no craft
    assert barmer["counts"]["artisans"] == 1


def test_craft_name_reaches_its_code_through_an_admin_spelling():
    corpus = _corpus(design_workshops=[_dw(1, "Rajasthan", "Barmer", craft="Barmeri chhapai")],
                     admin_spellings={cross_portal_codes.normalise("Barmeri chhapai"): AJRAKH})
    payload = _payload(corpus, coverage.Filters(craft_codes=(AJRAKH,)))
    assert _unit(payload, BARMER)["counts"]["designWorkshops"] == 1


def test_financial_year_turns_at_midnight_ist_on_1_april():
    last_minute = datetime(2026, 3, 31, 18, 29, tzinfo=UTC)  # 23:59 IST, 31 March
    first_minute = datetime(2026, 3, 31, 18, 30, tzinfo=UTC)  # 00:00 IST, 1 April
    assert coverage.fy_of(last_minute) == 2025
    assert coverage.fy_of(first_minute) == 2026
    assert coverage.fy_label(2025) == "2025-26" and coverage.fy_label(2099) == "2099-00"
    corpus = _corpus(design_workshops=[
        _dw(1, "Rajasthan", "Barmer", start=last_minute),
        _dw(2, "Rajasthan", "Barmer", start=first_minute),
    ])
    assert _unit(_payload(corpus, coverage.Filters(fys=(2025,))), BARMER)["counts"]["designWorkshops"] == 1
    assert _unit(_payload(corpus, coverage.Filters(fys=(2026,))), BARMER)["counts"]["designWorkshops"] == 1
    years = [y["fy"] for y in _payload(corpus)["catalogue"]["years"]]
    assert 2025 in years and 2026 in years


def test_plan_year_is_the_financial_year():
    payload = _payload(_barmer_corpus(), coverage.Filters(fys=(2026,)))
    assert _unit(payload, BARMER)["counts"]["plannedWorkshops"] == 1
    assert _unit(payload, BARMER)["counts"]["designWorkshops"] == 0


def test_state_filter_and_officer_scope():
    payload = _payload(_barmer_corpus(), coverage.Filters(state_lgd=GUJARAT))
    assert {u["stateLgd"] for u in payload["units"]} == {GUJARAT}
    corpus = _barmer_corpus()
    corpus.allowed_states = frozenset({RAJASTHAN})
    scoped = _payload(corpus)
    assert {u["stateLgd"] for u in scoped["units"]} == {RAJASTHAN}
    assert {row["stateLgd"] for row in scoped["gaps"]["districtsWithoutWorkshop"]["byState"]} == {RAJASTHAN}
    assert scoped["gaps"]["statesWithoutWorkshop"] == []


def test_parse_filters_refuses_nonsense_and_accepts_comma_joined():
    filters = coverage.parse_filters(level="state", craft_code=["ajrakh,bagru", "ajrakh"], scheme=["other"],
                                     fy=["2025", "2024"], state_lgd=8, peer=True)
    assert filters.level == "STATE" and filters.craft_codes == ("ajrakh", "bagru")
    assert filters.schemes == ("OTHER",) and filters.fys == (2024, 2025) and filters.peer
    for bad in ({"level": "VILLAGE"}, {"fy": ["twenty"]}, {"state_lgd": 999}):
        args = {"level": None, "craft_code": None, "scheme": None, "fy": None, "state_lgd": None, "peer": False}
        args.update(bad)
        with pytest.raises(ValueError):
            coverage.parse_filters(**args)


# --------------------------------------------------------------------------------------------
# Gaps
# --------------------------------------------------------------------------------------------


def test_crafts_never_documented_excludes_a_code_documented_in_any_bucket():
    never = {c["code"] for c in _payload(_barmer_corpus())["gaps"]["craftsNeverDocumented"]}
    assert AJRAKH not in never
    assert len(never) == len(cross_portal_codes.catalogue()) - 1
    only_planned = _corpus(plans=[_plan(1, "Rajasthan", "Barmer")])
    assert AJRAKH in {c["code"] for c in _payload(only_planned)["gaps"]["craftsNeverDocumented"]}


def test_districts_without_workshop_is_795_minus_covered():
    gaps = _payload(_barmer_corpus())["gaps"]
    # Barmer (design workshops) and Jaipur (a field workshop) are covered; a plan alone covers nothing.
    assert gaps["districtsWithoutWorkshop"]["count"] == 795 - 2
    rajasthan = next(r for r in gaps["districtsWithoutWorkshop"]["byState"] if r["stateLgd"] == RAJASTHAN)
    names = {row["name"] for row in rajasthan["without"]}
    assert "Barmer" not in names and "Jaipur" not in names and rajasthan["total"] == len(rajasthan["without"]) + 2
    assert {"lgd": RAJASTHAN, "name": "Rajasthan"} not in gaps["statesWithoutWorkshop"]
    assert len(gaps["statesWithoutWorkshop"]) == 35


def test_truncated_and_markers_truncated_flags():
    many = [_dw(i, "Rajasthan", "Barmer") for i in range(coverage.MARKER_CAP + 5)]
    corpus = _corpus(design_workshops=many)
    corpus.truncated = True
    payload = _payload(corpus)
    assert payload["truncated"] is True
    if coverage.centroids().get(BARMER):
        assert payload["markersTruncated"] is True and len(payload["markers"]) == coverage.MARKER_CAP
    rows, cut = coverage._capped(list(range(coverage.ROW_CAP + 1)))
    assert cut and len(rows) == coverage.ROW_CAP


def test_unmatched_spellings_are_counted_and_sampled_for_admins_only():
    corpus = _barmer_corpus()
    corpus.design_workshops.append(_dw(9, "Rajasthan", "Kachchh"))
    payload = _payload(corpus)
    # Kachchh written under Rajasthan is unmatched; "Atlantis" with no state or district is unplaced.
    assert payload["unmatched"]["count"] == 1 and payload["unmatched"]["unplaced"] == 1
    assert "sample" not in payload["unmatched"]
    sample = _payload(corpus, admin=True)["unmatched"]["sample"]
    assert {"model": "DesignWorkshop", "rawState": "Rajasthan", "rawDistrict": "Kachchh", "rows": 1} in sample
    suggestion = coverage.suggestion_for("Rajasthan", "Kachchh")
    assert suggestion["districtLgd"] == KACHCHH and suggestion["stateLgd"] == GUJARAT
    assert coverage.suggestion_for("Rajasthan", "Kachhh") is None  # no fuzzing


# --------------------------------------------------------------------------------------------
# Names only
# --------------------------------------------------------------------------------------------

FORBIDDEN_KEY = re.compile(r'"(aadhaar\w*|pehchan\w*|phone\w*|address\w*|village|pincode|street)"', re.IGNORECASE)


def test_no_payload_carries_identity_or_address_keys():
    corpus = _barmer_corpus()
    corpus.field_rows["artisans"][0].update({"aadhaarNumber": "123412341234", "phone": "9999999999"})
    payload = _payload(corpus, admin=True)
    fold = coverage.fold_corpus(corpus, coverage.Filters())
    stubs = {bucket: {str(key): rows for key, rows in units.items()} for bucket, units in fold.stubs.items()}
    for text in (json.dumps(payload, default=str), json.dumps(stubs, default=str)):
        assert not FORBIDDEN_KEY.search(text)
        assert "123412341234" not in text and "9999999999" not in text
    for columns in coverage.FIELD_COLUMNS.values():
        assert not any(FORBIDDEN_KEY.search(f'"{c}"') for c in columns)


# --------------------------------------------------------------------------------------------
# The Field Repository's counts
# --------------------------------------------------------------------------------------------


def _settings(**overrides: Any) -> Any:
    values = {
        "cross_portal_coverage_enabled": True,
        "cross_portal_token": "t" * 40,
        "field_repository_api_url": "https://fr.example/api",
        "field_repository_web_url": "https://fr.example",
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _peer_answer(**overrides: Any) -> dict[str, Any]:
    answer = {
        "asOf": "2026-10-18T00:00:00Z",
        "listVersion": lgd.register().list_version,
        "units": [
            {"stateLgd": RAJASTHAN, "districtLgd": BARMER,
             "counts": {"workshops": 1, "artisans": 9, "products": 2, "tools": 1, "media": 12}},
            {"stateLgd": RAJASTHAN, "districtLgd": 999_999, "counts": {"workshops": 1}},
        ],
        "craftCodesDocumented": ["bagru"] if cross_portal_codes.is_known_code("bagru") else [],
    }
    answer.update(overrides)
    return answer


def _run_peer(monkeypatch: pytest.MonkeyPatch, behaviour: Any, filters: coverage.Filters) -> dict[str, Any]:
    async def fake_peer_call(settings: Any, method: str, path: str, **kwargs: Any):
        assert (method, path) == ("GET", "/coverage")
        assert kwargs["gate"] is coverage.coverage_peer_active
        if isinstance(behaviour, Exception):
            raise behaviour
        return 200, behaviour

    monkeypatch.setattr("app.services.cross_portal_client.peer_call", fake_peer_call)
    coverage.PEER_CACHE.clear()
    coverage.FOLD_CACHE.clear()
    corpus = _barmer_corpus()

    async def fake_corpus(_user: Any) -> coverage.Corpus:
        return corpus

    monkeypatch.setattr(coverage, "load_corpus", fake_corpus)
    user = SimpleNamespace(id="u-peer", role="MINISTRY_ADMIN")
    return asyncio.run(coverage.coverage(user, filters, settings=_settings(), is_admin=False))


def test_peer_ok_is_a_separate_row_never_summed(monkeypatch: pytest.MonkeyPatch):
    payload = _run_peer(monkeypatch, _peer_answer(), coverage.Filters(peer=True))
    assert payload["peer"]["status"] == "OK" and payload["peer"]["unknownUnits"] == 1
    barmer = _unit(payload, BARMER)
    assert barmer["peer"] == {"workshops": 1, "artisans": 9, "products": 2, "tools": 1, "media": 12}
    assert barmer["counts"]["artisans"] == 1  # this portal's own, unchanged
    if cross_portal_codes.is_known_code("bagru"):
        assert "bagru" not in {c["code"] for c in payload["gaps"]["craftsNeverDocumented"]}


def test_peer_timeout_and_bad_signature_are_unavailable(monkeypatch: pytest.MonkeyPatch):
    for failure in (PeerUnavailable("timeout"), PeerUnavailable("bad signature")):
        payload = _run_peer(monkeypatch, failure, coverage.Filters(peer=True))
        assert payload["peer"]["status"] == "UNAVAILABLE"
        assert _unit(payload, BARMER)["peer"] is None
        assert _unit(payload, BARMER)["counts"]["designWorkshops"] == 2


def test_peer_list_version_mismatch_drops_unknown_units(monkeypatch: pytest.MonkeyPatch):
    answer = _peer_answer(listVersion=lgd.register().list_version + 1)
    answer["units"].append({"stateLgd": 99, "districtLgd": 0, "counts": {"artisans": 3}})
    payload = _run_peer(monkeypatch, answer, coverage.Filters(peer=True))
    assert payload["peer"]["status"] == "OK" and payload["peer"]["unknownUnits"] == 2
    assert _unit(payload, BARMER)["peer"]["artisans"] == 9


def test_peer_is_off_without_the_flag_token_or_address():
    assert coverage.coverage_peer_active(_settings())
    assert not coverage.coverage_peer_active(_settings(cross_portal_coverage_enabled=False))
    assert not coverage.coverage_peer_active(_settings(cross_portal_token="short"))
    assert not coverage.coverage_peer_active(_settings(field_repository_api_url=""))
    status, answer = asyncio.run(
        coverage.fetch_peer(_settings(cross_portal_coverage_enabled=False), coverage.Filters(peer=True), None)
    )
    assert (status, answer) == ("OFF", None)


def test_peer_href_carries_the_unit_and_filters():
    href = coverage.peer_href(_settings(), coverage.Filters(craft_codes=("ajrakh",), fys=(2025,)), 8, BARMER)
    assert href == f"https://fr.example/map?view=coverage&stateLgd=8&districtLgd={BARMER}&craftCode=ajrakh&fy=2025"
    assert coverage.peer_href(_settings(field_repository_web_url=None), coverage.Filters(), 8, 0) is None


# --------------------------------------------------------------------------------------------
# The unit panel
# --------------------------------------------------------------------------------------------


def test_unit_records_pages_each_bucket(monkeypatch: pytest.MonkeyPatch):
    corpus = _corpus(design_workshops=[_dw(i, "Rajasthan", "Barmer") for i in range(coverage.PAGE_SIZE + 3)])

    async def fake_corpus(_user: Any) -> coverage.Corpus:
        return corpus

    monkeypatch.setattr(coverage, "load_corpus", fake_corpus)
    user = SimpleNamespace(id="u", role="MINISTRY_ADMIN")
    answer = asyncio.run(coverage.unit_records(user, coverage.Filters(), RAJASTHAN, BARMER, bucket=None,
                                               cursor=None, settings=_settings()))
    bucket = answer["buckets"]["designWorkshops"]
    assert bucket["total"] == coverage.PAGE_SIZE + 3 and len(bucket["items"]) == coverage.PAGE_SIZE
    assert set(bucket["items"][0]) == {"id", "title", "href", "craftCode", "fy"}
    page2 = asyncio.run(coverage.unit_records(user, coverage.Filters(), RAJASTHAN, BARMER,
                                              bucket="designWorkshops", cursor=bucket["nextCursor"],
                                              settings=_settings()))
    assert len(page2["buckets"]["designWorkshops"]["items"]) == 3
    assert page2["buckets"]["designWorkshops"]["nextCursor"] is None
    assert answer["peerHref"].startswith("https://fr.example/map?view=coverage")
    wrong = asyncio.run(coverage.unit_records(user, coverage.Filters(), GUJARAT, BARMER, bucket=None,
                                              cursor=None, settings=_settings()))
    assert wrong is None


# --------------------------------------------------------------------------------------------
# The doors, against a database
# --------------------------------------------------------------------------------------------


def _auth(user_id: str) -> dict[str, str]:
    from app.core.security import create_access_token

    return {"Authorization": f"Bearer {create_access_token(subject=user_id)}"}


ROLES = {
    "ma": "MINISTRY_ADMIN",
    "master": "MASTER_ADMIN",
    "rd": "REGIONAL_DIRECTOR",
    "ad": "ASSISTANT_DIRECTOR",
    "designer": "DESIGNER",
    "admin": "ADMIN",
}


@pytest.fixture(scope="module")
def doors():
    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import hash_password
    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    raw_state = f"Marwar {stamp}"
    facts: dict[str, Any] = {"users": {}}

    async def seed() -> None:
        await db.connect()
        try:
            for key, role in ROLES.items():
                email = f"coverage-{key}-{stamp}@example.org"
                user = await db.user.create(
                    data={"email": email, "name": f"Coverage {key}", "role": role,
                          "passwordHash": hash_password("coverage-test-password")}
                )
                await db.accessroster.create(
                    data={"email": email, "status": "ACTIVE", "admitRole": role, "joinedAt": datetime.now(UTC),
                          "notes": "Seeded by tests/test_map_coverage.py."}
                )
                facts["users"][key] = user.id
            await db.regionaldirectorstate.create(
                data={"userId": facts["users"]["rd"], "state": "Gujarat", "assignedById": facts["users"]["ma"]}
            )
            for state, district in (("Rajasthan", "Barmer"), ("Gujarat", "Kachchh"), (raw_state, "Somewhere")):
                await db.designworkshop.create(
                    data={"title": f"Coverage {stamp} {district}", "state": state, "district": district,
                          "workshopKind": "DESIGN_PROTOTYPE_DEVELOPMENT", "craftName": "Ajrakh",
                          "createdById": facts["users"]["master"]}
                )
            await db.annualplanentry.create(
                data={"planYear": 2093, "workshopNo": f"COV/{stamp}", "workshopNoKey": f"COV/{stamp}".upper(),
                      "state": "Gujarat", "district": "Kachchh", "craftName": "Ajrakh"}
            )
        finally:
            await db.disconnect()

    asyncio.run(seed())
    coverage.clear_caches()
    users = facts["users"]
    answers: dict[str, Any] = {}
    with TestClient(app) as client:

        def call(method: str, path: str, actor: str, **kwargs: Any) -> Any:
            return client.request(method, f"/api/map{path}", headers=_auth(users[actor]), **kwargs)

        for actor in ROLES:
            answers[f"coverage-{actor}"] = call("GET", "/coverage", actor)
            answers[f"unmatched-{actor}"] = call("GET", "/coverage/unmatched", actor)
        body = {"rawState": raw_state, "rawDistrict": "Somewhere", "stateLgd": RAJASTHAN, "districtLgd": BARMER}
        answers["alias-designer"] = call("POST", "/coverage/aliases", "designer", json=body)
        answers["alias-admin"] = call("POST", "/coverage/aliases", "admin", json=body)
        answers["alias-wrong-state"] = call("POST", "/coverage/aliases", "ma", json={**body, "stateLgd": GUJARAT})
        answers["alias-ok"] = call("POST", "/coverage/aliases", "ma", json=body)
        answers["alias-again"] = call("POST", "/coverage/aliases", "master", json=body)
        answers["unmatched-after"] = call("GET", "/coverage/unmatched", "master")
        answers["records"] = call("GET", f"/coverage/units/{RAJASTHAN}/{BARMER}/records", "ma")
        answers["records-bad"] = call("GET", f"/coverage/units/{GUJARAT}/{BARMER}/records", "ma")
        alias_id = answers["alias-ok"].json().get("id") if answers["alias-ok"].status_code == 201 else None
        answers["delete-designer"] = call("DELETE", f"/coverage/aliases/{alias_id}", "designer")
        answers["delete-ok"] = call("DELETE", f"/coverage/aliases/{alias_id}", "master")

    async def read_back() -> None:
        await db.connect()
        try:
            facts["revisions"] = await db.recordrevision.find_many(
                where={"recordType": "geoalias", "recordId": alias_id or "-"}
            )
            facts["alias_left"] = await db.geoalias.find_unique(where={"id": alias_id or "-"})
        finally:
            await db.disconnect()

    asyncio.run(read_back())
    coverage.clear_caches()
    return {"answers": answers, "facts": facts, "raw_state": raw_state, "stamp": stamp}


@needs_db
def test_only_the_ministry_reads_the_coverage_map(doors):
    answers = doors["answers"]
    for actor in ("ma", "master", "rd", "ad"):
        assert answers[f"coverage-{actor}"].status_code == 200, actor
    for actor in ("designer", "admin"):
        assert answers[f"coverage-{actor}"].status_code == 403, actor


@needs_db
def test_a_regional_director_sees_only_their_states(doors):
    units = doors["answers"]["coverage-rd"].json()["units"]
    assert units and {u["stateLgd"] for u in units} == {GUJARAT}
    gaps = doors["answers"]["coverage-rd"].json()["gaps"]
    assert {row["stateLgd"] for row in gaps["districtsWithoutWorkshop"]["byState"]} <= {GUJARAT}
    everything = doors["answers"]["coverage-ma"].json()["units"]
    assert {RAJASTHAN, GUJARAT} <= {u["stateLgd"] for u in everything}


@needs_db
def test_only_the_master_admin_and_ministry_admin_map_places(doors):
    answers = doors["answers"]
    for actor in ("ma", "master"):
        assert answers[f"unmatched-{actor}"].status_code == 200
    for actor in ("rd", "ad", "designer", "admin"):
        assert answers[f"unmatched-{actor}"].status_code == 403, actor
    assert answers["alias-designer"].status_code == 403
    assert answers["alias-admin"].status_code == 403
    assert answers["delete-designer"].status_code == 403
    raw = doors["raw_state"]
    before = answers["unmatched-ma"].json()["items"]
    assert any(item["rawState"] == raw for item in before)
    after = answers["unmatched-after"].json()["items"]
    assert not any(item["rawState"] == raw for item in after)


@needs_db
def test_alias_writes_are_checked_and_audited(doors):
    answers = doors["answers"]
    assert answers["alias-wrong-state"].status_code == 422
    assert answers["alias-ok"].status_code == 201
    assert answers["alias-ok"].json()["districtLgd"] == BARMER
    assert answers["alias-again"].status_code == 409
    assert answers["delete-ok"].status_code == 204
    assert doors["facts"]["alias_left"] is None
    assert len(doors["facts"]["revisions"]) == 2  # the write and the delete


@needs_db
def test_unit_records_route(doors):
    answers = doors["answers"]
    assert answers["records"].status_code == 200
    items = answers["records"].json()["buckets"]["designWorkshops"]["items"]
    assert any(doors["stamp"] in item["title"] for item in items)
    assert not FORBIDDEN_KEY.search(answers["records"].text)
    assert answers["records-bad"].status_code == 404
