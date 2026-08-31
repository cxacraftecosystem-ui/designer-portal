"""The grievance / suggestion / recommendation register: the write, the reader, and the redressal.

WHAT THIS PINS, AND WHY EACH ONE IS HERE RATHER THAN BEING OBVIOUS.

1. **A report is CREATED, never upserted.** This is the whole reason the table exists beside
   ``Feedback``, whose ``userId`` is ``@unique``: a person who reported a bug on Monday and filed a
   grievance on Friday overwrote the bug report. If somebody ever "tidies" this route into an upsert
   for symmetry with ``PUT /feedback/me``, the register silently starts destroying its own history
   and no screen would look any different.
2. **The closed lists are validated against ONE module**, and a refusal names the members. A
   misspelled filter that returned an empty page would read as "there are no grievances", which is
   this repository's most-repeated failure class.
3. **Resolving requires words and acknowledging does not.** An institution that may close a
   grievance without saying how has a queue-clearing button rather than a redressal mechanism.
4. **A resolved report is terminal.** The table holds ONE note, so a second decision would overwrite
   the sentence its author was given — which is precisely the sentence that must survive.
5. **Both transitions stamp a NAME as well as a time.** "Your grievance was seen" with nobody's name
   on it is a claim an institution can make about a queue nobody read.

NOTHING HERE TOUCHES A DATABASE — ``db`` is replaced by a delegate that records what it was asked
for, which is what lets a test assert about the WRITE rather than only about the response. Same
harness, and the same reasoning, as ``test_feedback_paging``.
"""

import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services.feedback_vocabulary import FEEDBACK_KINDS, FEEDBACK_STATUSES


def _user(role: str = "RESEARCHER", ident: str = "u1") -> SimpleNamespace:
    return SimpleNamespace(
        id=ident,
        email=f"{ident}@example.test",
        name=f"User {ident}",
        role=role,
        canReview=False,
        canDownloadDataset=False,
        canViewProvenance=False,
    )


def _row(**overrides: Any) -> SimpleNamespace:
    base: dict[str, Any] = {
        "id": "r1",
        "userId": "u1",
        "kind": "GRIEVANCE",
        "severity": "HIGH",
        "area": "MEDIA",
        "subject": "Uploads stop at 90%",
        "details": "Every photograph over 4 MB stalls.",
        "client": "WEB",
        "clientVersion": "web",
        "platform": "Chromium",
        "pagePath": "/artisans/new",
        "status": "SUBMITTED",
        "acknowledgedById": None,
        "acknowledgedAt": None,
        "resolvedById": None,
        "resolvedAt": None,
        "responseNote": None,
        "createdAt": "2026-08-30T09:00:00",
        "updatedAt": "2026-08-30T09:00:00",
        "user": SimpleNamespace(id="u1", name="User u1", email="u1@example.test", role="RESEARCHER"),
        "acknowledgedBy": None,
        "resolvedBy": None,
    }
    base.update(overrides)
    return SimpleNamespace(**base)


class _Reports:
    """The FeedbackReport delegate, recording every call so the write can be asserted about."""

    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows if rows is not None else []
        self.created: list[dict[str, Any]] = []
        self.updated: list[tuple[dict[str, Any], dict[str, Any]]] = []
        self.wheres: list[Any] = []
        self.counts: list[Any] = []

    async def create(self, data: dict[str, Any], include: dict | None = None, **_: Any) -> Any:
        self.created.append(data)
        return _row(**{k: v for k, v in data.items() if k != "user"})

    async def find_many(
        self,
        where: Any = None,
        order: Any = None,
        include: dict | None = None,
        skip: int | None = None,
        take: int | None = None,
        **_: Any,
    ) -> list[Any]:
        self.wheres.append(where)
        start = skip or 0
        return self.rows[start:] if take is None else self.rows[start : start + take]

    async def count(self, where: Any = None, **_: Any) -> int:
        self.counts.append(where)
        return len(self.rows)

    async def find_unique(self, where: dict[str, Any], **_: Any) -> Any:
        return next((r for r in self.rows if r.id == where.get("id")), None)

    async def update(self, where: dict[str, Any], data: dict[str, Any], include: dict | None = None, **_: Any) -> Any:
        self.updated.append((where, data))
        current = await self.find_unique(where)
        if current is None:
            return None
        merged = dict(vars(current))
        for key, value in data.items():
            if isinstance(value, dict) and "connect" in value:
                # The relation write, as Prisma would record it: the FK column plus the loaded row.
                merged[f"{key}Id"] = value["connect"]["id"]
                merged[key] = SimpleNamespace(id=value["connect"]["id"], name="Admin", email="a@x.test", role="ADMIN")
            else:
                merged[key] = value
        return SimpleNamespace(**merged)


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


@pytest.fixture
def table(monkeypatch: pytest.MonkeyPatch):
    delegate = _Reports()
    fake_db = SimpleNamespace(feedbackreport=delegate, feedback=SimpleNamespace(), user=SimpleNamespace())
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", fake_db)
    _CURRENT["user"] = _user()
    yield delegate
    _CURRENT["user"] = None


def _call(method: str, path: str, **kwargs: Any) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://feedback.test") as client:
            return await client.request(method, f"/api{path}", **kwargs)

    return asyncio.run(run())


# ── The vocabulary is served, so neither client compiles its own copy ──────────────────────────


def test_the_vocabulary_is_served_with_labels_and_in_order(table: _Reports) -> None:
    """Both clients render their dropdowns from this. If it ever answers bare strings, each client
    has to invent "Grievance" for itself and the two will one day disagree about one submission."""
    body = _call("GET", "/feedback/vocabulary").json()

    assert [choice["value"] for choice in body["kind"]] == list(FEEDBACK_KINDS)
    assert body["kind"][0] == {"value": "SUGGESTION", "label": "Suggestion"}
    # Low to high. A scale that arrives shuffled is a scale each client re-sorts by its own idea of
    # the ranking, and the two orders then disagree about what "worse" looks like.
    assert [choice["value"] for choice in body["severity"]] == ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    assert list(body) == ["kind", "severity", "area", "status", "client"]


# ── The write ──────────────────────────────────────────────────────────────────────────────────


def test_filing_a_report_CREATES_and_never_upserts(table: _Reports) -> None:
    """THE REGRESSION THIS TABLE EXISTS FOR.

    ``Feedback.userId`` is ``@unique`` and ``PUT /feedback/me`` upserts on it, so a person's second
    submission destroyed their first. Nothing about that is visible from a screen — the form saves,
    says "thank you", and the earlier grievance is gone. So this asserts the shape of the CALL: a
    create, with the author connected, and no ``where`` anywhere near it.
    """
    response = _call(
        "POST",
        "/feedback/reports",
        json={
            "kind": "GRIEVANCE",
            "severity": "HIGH",
            "area": "MEDIA",
            "subject": "Uploads stop at 90%",
            "details": "Every photograph over 4 MB stalls.",
            "client": "WEB",
            "clientVersion": "web",
            "platform": "Chromium",
            "pagePath": "/artisans/new",
        },
    )

    assert response.status_code == 201, response.text
    assert len(table.created) == 1
    written = table.created[0]
    assert written["user"] == {"connect": {"id": "u1"}}
    assert written["kind"] == "GRIEVANCE"
    assert written["pagePath"] == "/artisans/new"
    # No status in the payload the route composed: it is the column default, so a client cannot file
    # something already marked resolved.
    assert "status" not in written


def test_an_unanswered_optional_list_is_stored_as_NULL_not_as_an_empty_string(table: _Reports) -> None:
    """A dropdown left alone submits "". Stored raw it would be a member of no vocabulary, and the
    research cut would carry a category that is neither a severity nor an absence."""
    _call(
        "POST",
        "/feedback/reports",
        json={"kind": "SUGGESTION", "severity": "", "area": "", "subject": "s", "details": "d"},
    )

    assert table.created[0]["severity"] is None
    assert table.created[0]["area"] is None


def test_a_captured_field_the_client_could_not_determine_is_NULL_too(table: _Reports) -> None:
    """"We do not know" and "it is blank" must be ONE answer in the export, not two a researcher has
    to reconcile — and a report must never be refused for want of a platform string."""
    _call(
        "POST",
        "/feedback/reports",
        json={"kind": "BUG", "subject": "s", "details": "d", "platform": "", "clientVersion": ""},
    )

    assert table.created[0]["platform"] is None
    assert table.created[0]["clientVersion"] is None
    assert table.created[0]["client"] is None


def test_an_unknown_kind_is_refused_and_the_refusal_NAMES_the_members(table: _Reports) -> None:
    """A refusal that only says "invalid kind" leaves a client author guessing at spellings against
    a server they cannot read."""
    response = _call("POST", "/feedback/reports", json={"kind": "COMPLAINT", "subject": "s", "details": "d"})

    assert response.status_code == 422
    detail = response.json()["detail"]
    assert "COMPLAINT" in detail
    for member in FEEDBACK_KINDS:
        assert member in detail
    assert not table.created


def test_a_kind_is_required(table: _Reports) -> None:
    """It decides which queue an administrator works. A report nobody can route is a report that is
    not in a register."""
    assert _call("POST", "/feedback/reports", json={"kind": "", "subject": "s", "details": "d"}).status_code == 422


# ── The reader ─────────────────────────────────────────────────────────────────────────────────


def test_the_inbox_is_admin_and_not_master_admin(table: _Reports) -> None:
    """A redressal mechanism in which exactly one account in the institution can acknowledge
    anything is a mechanism that will not redress anything.

    This also closes a live defect: ``/admin``'s "User feedback" tile is ADMIN-visible and pointed at
    a master-admin-only list, so an admin who followed it reached a screen with no inbox on it.
    """
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")
    assert _call("GET", "/feedback/reports").status_code == 200

    _CURRENT["user"] = _user(role="PROFESSOR", ident="p1")
    assert _call("GET", "/feedback/reports").status_code == 403


def test_a_misspelled_filter_is_refused_rather_than_returning_an_empty_page(table: _Reports) -> None:
    """``?kind=greivance`` returning [] reads as "there are no grievances" — absence presented as
    non-existence, which is this repository's most-repeated failure class."""
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")

    assert _call("GET", "/feedback/reports", params={"kind": "greivance"}).status_code == 422
    assert _call("GET", "/feedback/reports", params={"status": "OPEN"}).status_code == 422


def test_the_open_count_is_over_the_whole_table_not_the_filtered_page(table: _Reports) -> None:
    """An admin who has narrowed to grievances must not be told the queue is empty while eleven bug
    reports sit unread. The count's own ``where`` is asserted, because a count that merely happened
    to agree with the page here would be wrong the moment a filter was applied."""
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")
    table.rows = [_row()]

    body = _call("GET", "/feedback/reports", params={"kind": "GRIEVANCE"}).json()

    assert body["openCount"] == 1
    assert table.counts[-1] == {"status": "SUBMITTED"}


def test_a_reporter_reads_only_their_own_and_the_scope_is_not_a_parameter(table: _Reports) -> None:
    """The route takes no ``userId``. A parameter would be a permission check waiting to be
    forgotten; there is nothing here to forget, because the only row set it can express is the
    caller's own — asserted against the composed ``where`` rather than against the response."""
    table.rows = [_row()]

    body = _call("GET", "/feedback/reports/mine").json()

    assert body["items"][0]["subject"] == "Uploads stop at 90%"
    assert table.wheres[-1] == {"userId": "u1"}
    # "Open" for a REPORTER means "not resolved", which includes acknowledged: a report somebody has
    # read and not answered is exactly the one a redressal mechanism loses. The inbox counts
    # SUBMITTED instead, because there the question is "what has nobody picked up".
    assert table.counts[-1] == {"AND": [{"userId": "u1"}, {"NOT": {"status": "RESOLVED"}}]}


def test_every_report_carries_the_servers_own_words_for_its_stored_values(table: _Reports) -> None:
    """Both clients render the label and file against the value, so neither holds a copy of what a
    GRIEVANCE is called."""
    table.rows = [_row()]

    item = _call("GET", "/feedback/reports/mine").json()["items"][0]

    assert item["kind"] == "GRIEVANCE" and item["kindLabel"] == FEEDBACK_KINDS["GRIEVANCE"]
    assert item["statusLabel"] == FEEDBACK_STATUSES["SUBMITTED"]


def test_a_value_from_a_retired_category_still_prints_rather_than_raising(table: _Reports) -> None:
    """The second reason this is not a Prisma enum. A dropped enum member is a read error on the
    OLDEST grievances — the ones a register exists to keep legible."""
    table.rows = [_row(kind="TRANSLATION")]

    item = _call("GET", "/feedback/reports/mine").json()["items"][0]

    assert item["kind"] == "TRANSLATION"
    assert item["kindLabel"] == "TRANSLATION"


# ── The redressal ──────────────────────────────────────────────────────────────────────────────


def test_acknowledging_stamps_a_NAME_as_well_as_a_time(table: _Reports) -> None:
    """"Your grievance was seen" with nobody's name on it is a claim an institution can make about a
    queue nobody read."""
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")
    table.rows = [_row()]

    response = _call("POST", "/feedback/reports/r1/acknowledge", json={"note": "Looking into it."})

    assert response.status_code == 200, response.text
    _, written = table.updated[-1]
    assert written["status"] == "ACKNOWLEDGED"
    assert written["acknowledgedBy"] == {"connect": {"id": "a1"}}
    assert written["acknowledgedAt"] is not None
    assert written["responseNote"] == "Looking into it."


def test_acknowledging_without_a_note_is_allowed(table: _Reports) -> None:
    """It promises only that a named person read it, which is true without further words."""
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")
    table.rows = [_row()]

    assert _call("POST", "/feedback/reports/r1/acknowledge", json={"note": None}).status_code == 200


def test_resolving_without_a_note_is_REFUSED(table: _Reports) -> None:
    """THE ONE PLACE THIS REGISTER INSISTS ON WORDS. An institution that may close a grievance
    without saying how has a queue-clearing button, not a redressal mechanism."""
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")
    table.rows = [_row()]

    response = _call("POST", "/feedback/reports/r1/resolve", json={"note": "   "})

    assert response.status_code == 422
    assert "note" in response.json()["detail"]
    assert not table.updated


def test_resolving_straight_from_submitted_also_records_that_it_was_read(table: _Reports) -> None:
    """A report that went from filed to finished in one step WAS read, so the reporter's own list can
    always answer "when was this seen" instead of showing a blank beside a resolved report."""
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")
    table.rows = [_row()]

    _call("POST", "/feedback/reports/r1/resolve", json={"note": "Fixed in this week's build."})

    _, written = table.updated[-1]
    assert written["status"] == "RESOLVED"
    assert written["resolvedBy"] == {"connect": {"id": "a1"}}
    assert written["acknowledgedAt"] is not None
    assert written["acknowledgedBy"] == {"connect": {"id": "a1"}}


def test_a_resolved_report_cannot_be_decided_again(table: _Reports) -> None:
    """The table holds ONE note, so a second pass would overwrite the sentence its author was given —
    and it is precisely the person told "resolved: we have changed the form" who must still be able
    to read that a month later. Refusing plainly beats silently rewriting history."""
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")
    table.rows = [_row(status="RESOLVED", responseNote="Fixed in this week's build.")]

    response = _call("POST", "/feedback/reports/r1/resolve", json={"note": "Actually, no."})

    assert response.status_code == 409
    assert not table.updated


def test_deciding_a_report_that_no_longer_exists_is_a_404_and_writes_nothing(table: _Reports) -> None:
    _CURRENT["user"] = _user(role="ADMIN", ident="a1")

    assert _call("POST", "/feedback/reports/gone/acknowledge", json={}).status_code == 404
    assert not table.updated


def test_a_reporter_cannot_resolve_their_own_report(table: _Reports) -> None:
    """The decision is the institution's. Without this the register would let anybody mark their own
    grievance answered, which is the same as having no status at all."""
    table.rows = [_row()]

    assert _call("POST", "/feedback/reports/r1/resolve", json={"note": "done"}).status_code == 403
    assert not table.updated


# ── The research export ────────────────────────────────────────────────────────────────────────
#
# "We need to properly document the feedback that comes in for the sake of the research as well."
# Documented means analysable by somebody who is not looking at a screen, so both feedback tables
# are in the dataset registry — which gives them a paged JSON route, an .ndjson stream, a .csv
# stream and a catalogue line, under the credential a research mirror already holds.


def test_both_feedback_tables_are_downloadable_and_have_a_csv_form() -> None:
    """A register nobody can extract is a register that documents nothing.

    The CSV matters specifically: these two tables are not RECORDS, so they have no registry
    ``kind`` — and the registry was the only route to a ``.csv`` until they declared their own
    columns. Asserting the header here is what stops a later "tidy-up" removing the second form and
    quietly leaving the grievance register as .ndjson only.
    """
    from app.api.routes.datasets import DATASETS, csv_header

    for name in ("feedback", "feedback-reports"):
        dataset = DATASETS[name]
        header = csv_header(dataset)
        assert header is not None, f"{name} lost its CSV form"
        # "ID" first, exactly as every registry-backed dataset does it: the only stable key a
        # downstream sheet can join these tables on.
        assert header[0] == "ID"
        assert len(header) == 1 + len(dataset.flat_columns)


def test_a_workshop_selection_cannot_compose_a_predicate_these_tables_have_no_column_for() -> None:
    """THE 500 THIS AVOIDS. Feedback is about the software, not about a workshop, so neither table
    has a ``workshopId``. On the default "column" filter a request carrying ``workshopIds`` would ask
    Postgres for a column that does not exist and fail the whole download — and a mirror job passing
    its usual filters is exactly the caller that would hit it."""
    from app.api.routes.datasets import DATASETS, _build_where

    for name in ("feedback", "feedback-reports"):
        where = _build_where(
            DATASETS[name],
            workshop_ids=["w1", "none"],
            created_by=None,
            updated_since=None,
            created_since=None,
        )
        assert where == {}, f"{name} narrowed by a workshop it has no column for: {where}"


def test_the_export_prints_labels_and_names_the_people_who_answered() -> None:
    """A CSV of raw keys makes a researcher build their own legend, and the legend they build is a
    copy of a vocabulary that can then drift. And "was this seen" is answerable from a status, while
    "by whom, and when" — the question a grievance register exists to answer — is only answerable if
    the actors survive into the extract."""
    from app.api.routes.datasets import DATASETS

    row = _row(
        severity=None,
        acknowledgedAt="2026-08-30T10:00:00",
        acknowledgedBy=SimpleNamespace(name="Asha", email="asha@x.test"),
    )
    cells = {header: getter(row) for header, getter in DATASETS["feedback-reports"].flat_columns}

    assert cells["Kind"] == FEEDBACK_KINDS["GRIEVANCE"]
    assert cells["Status"] == FEEDBACK_STATUSES["SUBMITTED"]
    assert cells["Reported by"] == "User u1"
    assert cells["Acknowledged by"] == "Asha"
    # An unanswered optional list is EMPTY, never the string "None": a spreadsheet will happily
    # filter on "None", and a researcher counting grievances by severity would find a category
    # sitting between CRITICAL and HIGH that nobody ever chose.
    assert cells["Severity"] == ""


def test_an_actor_whose_account_was_deleted_prints_empty_beside_a_populated_timestamp() -> None:
    """Both actor columns are ON DELETE SET NULL, so this pair is a real state and not a bug: it was
    acknowledged, and the account that did it is gone. The extract has to survive it rather than
    raise in the middle of a multi-thousand-row stream."""
    from app.api.routes.datasets import DATASETS

    row = _row(acknowledgedAt="2026-08-30T10:00:00", acknowledgedBy=None)
    cells = {header: getter(row) for header, getter in DATASETS["feedback-reports"].flat_columns}

    assert cells["Acknowledged by"] == ""
    assert cells["Acknowledged at"] == "2026-08-30T10:00:00"
