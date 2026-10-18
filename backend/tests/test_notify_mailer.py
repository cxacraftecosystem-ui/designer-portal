"""The 2026-10-18 notice templates and the pure helpers around them — no database.

Every new kind renders a subject, a text part and an HTML part; the office's report link is a sealed
secret and is refused without one; the daily digest's boundary is 09:00 India time; the office
addresses are cleaned and refused by entry; a hand-in preference outside the three words reads as
EACH; the outbox's status words collapse to the three a screen shows.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.services import email_outbox, mailer, report_delivery

PARAMS = {
    mailer.REPORT_APPROVED: {"workshopId": "w1", "workshopTitle": "Bagru prints", "actorName": "Meera", "note": "Good."},
    mailer.REPORT_HANDED_ON: {"workshopId": "w1", "workshopTitle": "Bagru prints", "officeName": "DC (H)", "emailed": True},
    mailer.WORKSHOP_ASSIGNED: {"workshopId": "w1", "workshopTitle": "Bagru prints", "sanctionOrderNo": "SO/1"},
    mailer.SETUP_REMINDER: {
        "workshopId": "w1",
        "workshopTitle": "Bagru prints",
        "actorName": "Officer",
        "missing": ["Venue", "Start date"],
    },
    mailer.ACCESS_REQUESTED: {"scope": "workshop", "workshopId": "w1", "workshopTitle": "Bagru prints", "requesterName": "Asha"},
    mailer.ACCESS_DECIDED: {"scope": "data", "decision": "GRANTED", "actorName": "Ravi", "tier": "COMMENT"},
    mailer.RECORD_COMMENT: {"authorName": "Asha", "recordType": "craft", "recordId": "c1", "recordLabel": "your craft record", "body": "Natural dye?"},
    mailer.REPORT_HANDED_IN: {"workshopId": "w1", "workshopTitle": "Bagru prints", "round": 2, "resubmission": True, "approver": True},
    mailer.HAND_IN_DIGEST: {"items": [{"workshopId": "w1", "workshopTitle": "Bagru prints", "round": 1}], "approver": False},
}


@pytest.mark.parametrize("kind", sorted(PARAMS))
def test_every_new_notice_renders(kind: str) -> None:
    rendered = mailer.render(kind, {"recipientName": "Priya", **PARAMS[kind]})
    assert rendered.subject and rendered.text.startswith("Dear Priya,")
    assert "<html>" in rendered.html and "Settings" in rendered.text
    assert mailer.subject_for(kind, PARAMS[kind]) == rendered.subject


def test_the_notices_say_the_specific_thing() -> None:
    reminder = mailer.render(mailer.SETUP_REMINDER, PARAMS[mailer.SETUP_REMINDER])
    assert "• Venue" in reminder.text and "/design-workshops/w1/stages/WORKSHOP_SETUP" in reminder.text
    handed_in = mailer.render(mailer.REPORT_HANDED_IN, PARAMS[mailer.REPORT_HANDED_IN])
    assert "round 2" in handed_in.text and "/design-workshop-approvals/w1" in handed_in.text
    comment = mailer.render(mailer.RECORD_COMMENT, PARAMS[mailer.RECORD_COMMENT])
    assert "> Natural dye?" in comment.text and "/crafts/c1" in comment.text
    digest = mailer.render(mailer.HAND_IN_DIGEST, PARAMS[mailer.HAND_IN_DIGEST])
    assert "Bagru prints — handed in" in digest.text and "/design-workshop-inspections" in digest.text


def test_the_office_link_is_a_secret_and_says_when_it_expires() -> None:
    params = {"workshopTitle": "Bagru prints", "officeName": "DC (H)", "expiresAt": "2026-10-25T06:30:00+00:00"}
    with pytest.raises(ValueError):
        mailer.render(mailer.REPORT_DELIVERY, params)
    rendered = mailer.render(mailer.REPORT_DELIVERY, params, secret="https://x.example/report-download?token=abc")
    assert "https://x.example/report-download?token=abc" in rendered.text
    assert "25 October 2026, 12:00 pm IST" in rendered.text
    assert mailer.REPORT_DELIVERY in mailer.EXPIRING_LINK_KINDS
    assert "token" not in mailer.subject_for(mailer.REPORT_DELIVERY, params)


def test_a_sanction_link_names_the_order() -> None:
    rendered = mailer.render(
        mailer.PASSWORD_LINK,
        {"context": "SANCTION", "purpose": "INVITE", "sanctionOrderNo": "SO/9", "workshopTitle": "Kantha", "expiresAt": "2026-10-20T00:00:00+00:00"},
        secret="https://x.example/set-password?token=t",
    )
    assert "Sanction order SO/9 names you" in rendered.text
    assert rendered.subject == "Your workshop is ready: “Kantha”"


def test_the_digest_boundary_is_nine_in_the_morning_india_time() -> None:
    before_nine = datetime(2026, 10, 18, 3, 0, tzinfo=UTC)  # 08:30 IST
    after_nine = datetime(2026, 10, 18, 4, 0, tzinfo=UTC)  # 09:30 IST
    assert email_outbox.digest_cutoff(before_nine) == datetime(2026, 10, 17, 3, 30, tzinfo=UTC)
    assert email_outbox.digest_cutoff(after_nine) == datetime(2026, 10, 18, 3, 30, tzinfo=UTC)


def test_hand_in_modes_and_status_words() -> None:
    assert email_outbox.hand_in_mode(None) == "EACH"
    assert email_outbox.hand_in_mode(SimpleNamespace(emailHandInNotices="daily")) == "DAILY"
    assert email_outbox.hand_in_mode(SimpleNamespace(emailHandInNotices="HOURLY")) == "EACH"
    when = datetime(2026, 10, 18, tzinfo=UTC)
    row = SimpleNamespace(id="m1", status="SENDING", createdAt=when, sentAt=None, error=None)
    assert email_outbox.delivery_status(row)["status"] == "QUEUED"
    row.status, row.error = "FAILED", "SES refused it: MessageRejected"
    assert email_outbox.delivery_status(row)["error"] == "SES refused it: MessageRejected"
    assert email_outbox.delivery_status(None) is None


def test_office_addresses_are_cleaned_and_refused_by_entry() -> None:
    kept, refused = report_delivery.clean_addresses(["A@Example.org; b@example.org", "a@example.org", "nope"])
    assert kept == ["a@example.org", "b@example.org"] and refused == ["nope"]


def test_an_address_longer_than_smtp_allows_is_refused_without_a_slow_match() -> None:
    long_address = "a@" + "b." * 2000 + "!"
    kept, refused = report_delivery.clean_addresses([long_address, "x@" + "y" * 250 + ".org"])
    assert kept == [] and refused == [long_address, "x@" + "y" * 250 + ".org"]


def test_each_preference_governs_its_own_kinds() -> None:
    assert email_outbox.PREFERENCE_FOR_KIND[mailer.REPORT_APPROVED] == "emailWorkshopUpdates"
    assert email_outbox.PREFERENCE_FOR_KIND[mailer.RECORD_COMMENT] == "emailAccessNotices"
    assert mailer.REPORT_DELIVERY not in email_outbox.PREFERENCE_FOR_KIND, "the office's copy is not a notice"
    assert mailer.PASSWORD_LINK not in email_outbox.PREFERENCE_FOR_KIND
