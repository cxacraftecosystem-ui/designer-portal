"""The Monday summary without a database: the IST week and send window, the subject, and the e-mail.

``services/weekly_digest.py`` and the ``WEEKLY_DIGEST`` template in ``services/mailer.py``.
"""

from __future__ import annotations

import asyncio
import re
from datetime import UTC, date, datetime

from app.api.routes import review as review_route
from app.services import mailer, review_queue, weekly_digest


def _payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "digestId": "dg_1",
        "recipientName": "Meera",
        "weekStart": "2026-10-19",
        "waiting": 4,
        "overdue": 2,
        "newThisWeek": 11,
        "sections": [
            {
                "key": "waiting",
                "label": "Waiting for you",
                "total": 4,
                "lines": [
                    {
                        "key": "approvals",
                        "label": "Reports to approve",
                        "count": 4,
                        "link": "/design-workshop-approvals",
                    }
                ],
                "items": [
                    {
                        "title": "Kantha, Bolpur",
                        "link": "/design-workshop-approvals/ws_1",
                        "since": "2026-10-09",
                    },
                    {
                        "title": "Dhokra, Bastar",
                        "link": "/design-workshop-approvals/ws_2",
                        "since": None,
                    },
                    {
                        "title": "Ikat, Pochampally",
                        "link": "/design-workshop-approvals/ws_3",
                        "since": None,
                    },
                    {
                        "title": "Bidri, Bidar",
                        "link": "/design-workshop-approvals/ws_4",
                        "since": None,
                    },
                ],
            },
            {"key": "overdue", "label": "Overdue", "total": 2, "lines": [], "items": []},
            {"key": "new", "label": "New this week", "total": 11, "lines": []},
        ],
        "coverage": {
            "label": "Reports in",
            "value": 41,
            "of": 58,
            "percent": 71,
            "scope": "Every workshop",
        },
    }
    payload.update(overrides)
    return payload


# ── Time ──────────────────────────────────────────────────────────────────────────────────────────


def test_the_week_is_read_in_india_time_across_the_utc_midnight() -> None:
    # Sunday 23:00 UTC is Monday 04:30 IST: already the new week.
    assert weekly_digest.week_start_for(datetime(2026, 10, 18, 23, 0, tzinfo=UTC)) == date(
        2026, 10, 19
    )
    # Sunday 18:29 UTC is Sunday 23:59 IST: still the old one.
    assert weekly_digest.week_start_for(datetime(2026, 10, 18, 18, 29, tzinfo=UTC)) == date(
        2026, 10, 12
    )
    # A Wednesday belongs to its Monday.
    assert weekly_digest.week_start_for(datetime(2026, 10, 21, 9, 0, tzinfo=UTC)) == date(
        2026, 10, 19
    )


def test_the_send_window_is_monday_from_eight_to_midnight_ist() -> None:
    # Monday 19 October 2026, IST = UTC + 5:30.
    assert not weekly_digest.in_send_window(datetime(2026, 10, 19, 2, 29, tzinfo=UTC))  # 07:59 IST
    assert weekly_digest.in_send_window(datetime(2026, 10, 19, 2, 30, tzinfo=UTC))  # 08:00 IST
    assert weekly_digest.in_send_window(datetime(2026, 10, 19, 18, 29, tzinfo=UTC))  # 23:59 IST
    assert not weekly_digest.in_send_window(
        datetime(2026, 10, 19, 18, 30, tzinfo=UTC)
    )  # Tue 00:00 IST


def test_this_week_is_the_seven_days_before_monday_eight_ist() -> None:
    window = weekly_digest.window_for(date(2026, 10, 19))
    assert window.end == datetime(2026, 10, 19, 2, 30, tzinfo=UTC)
    assert window.start == datetime(2026, 10, 12, 2, 30, tzinfo=UTC)
    assert window.today == datetime(2026, 10, 19, tzinfo=UTC)


def test_outside_the_window_the_pass_returns_before_any_query(monkeypatch) -> None:
    class NoDatabase:
        def __getattr__(self, name: str) -> object:
            raise AssertionError(f"queried {name} outside the window")

    monkeypatch.setattr(weekly_digest, "db", NoDatabase())
    monkeypatch.setattr(weekly_digest, "_last_check", None)
    tuesday = datetime(2026, 10, 20, 4, 0, tzinfo=UTC)
    answer = asyncio.run(weekly_digest.run_weekly_digests(tuesday))
    assert answer == {"ran": False, "reason": "outside window"}
    # Inside the window, a second ask within ten minutes is throttled, also without a query.
    monkeypatch.setattr(weekly_digest, "_last_check", datetime(2026, 10, 19, 3, 0, tzinfo=UTC))
    again = asyncio.run(weekly_digest.run_weekly_digests(datetime(2026, 10, 19, 3, 5, tzinfo=UTC)))
    assert again == {"ran": False, "reason": "throttled"}


def test_the_kill_switch_beats_force() -> None:
    from app.core.config import get_settings

    off = get_settings().model_copy(update={"weekly_digest_enabled": False})
    answer = asyncio.run(weekly_digest.run_weekly_digests(force=True, settings=off))
    assert answer == {"ran": False, "reason": "disabled"}


# ── Mail ──────────────────────────────────────────────────────────────────────────────────────────


def test_the_subject_names_overdue_only_when_there_is_some() -> None:
    assert (
        mailer.subject_for(mailer.WEEKLY_DIGEST, {"waiting": 4, "overdue": 2})
        == "Your week: 4 waiting, 2 overdue"
    )
    assert (
        mailer.subject_for(mailer.WEEKLY_DIGEST, {"waiting": 4, "overdue": 0})
        == "Your week: 4 waiting"
    )
    assert mailer.WEEKLY_DIGEST in mailer.KINDS


def test_every_title_is_escaped() -> None:
    hostile = _payload()
    hostile["sections"][0]["items"][0]["title"] = "<script>alert(1)</script>"  # type: ignore[index]
    rendered = mailer.render(mailer.WEEKLY_DIGEST, hostile)
    assert "<script>" not in rendered.html
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in rendered.html


def test_the_html_and_the_text_carry_the_same_figures_and_no_image() -> None:
    rendered = mailer.render(mailer.WEEKLY_DIGEST, _payload())
    assert rendered.subject == "Your week: 4 waiting, 2 overdue"
    for figure in ("4", "2", "11", "71%", "41 of 58"):
        assert f">{figure}<" in rendered.html, figure
        assert figure in rendered.text, figure
    assert "Waiting for you: 4" in rendered.text and "Overdue: 2" in rendered.text
    assert "New this week: 11" in rendered.text and "Reports in: 71% (41 of 58)" in rendered.text
    # Three titles, then the rest as a number.
    assert "Bidri" not in rendered.html and "Bidri" not in rendered.text
    assert "and 1 more" in rendered.html and "and 1 more" in rendered.text
    assert "/digest/dg_1" in rendered.html and "/digest/dg_1" in rendered.text
    assert "Open your digest" in rendered.html
    assert "You get this every Monday." in rendered.text and "/settings" in rendered.html
    assert not re.search(r"<img\b", rendered.html, re.IGNORECASE)


def test_the_in_app_line_reads_like_the_subject() -> None:
    assert (
        weekly_digest.summary_line(_payload()) == "4 waiting · 2 overdue · 11 new · Reports in 71%"
    )
    nothing_ended = _payload(coverage={"label": "Reports in", "value": 0, "of": 0, "percent": None})
    assert weekly_digest.summary_line(nothing_ended) == "4 waiting · 2 overdue · 11 new"


# ── The record-review predicate, now shared ──────────────────────────────────────────────────────


def test_the_review_ladder_moved_without_changing() -> None:
    assert review_route._reviewable_creator_roles is review_queue.reviewable_creator_roles
    assert review_queue.reviewable_creator_roles({"role": "MASTER_ADMIN"}) is None
    assert set(review_queue.reviewable_creator_roles({"role": "INSPECTOR"}) or []) == {
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "DESIGNER",
    }
    assert review_queue.pending_where({"role": "CROWDSOURCE_VOLUNTEER"}) is None


def test_the_digest_counts_the_same_six_record_types_the_queue_lists() -> None:
    names = tuple(
        type(delegate).__name__.removesuffix("Actions").lower()
        for _type, delegate, _fields in review_route._PENDING_SOURCES
    )
    assert names == review_queue.PENDING_MODELS
