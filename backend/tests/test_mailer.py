"""The SES sender and the templates (``app/services/mailer.py``), with SES stubbed.

No network and no database: ``_ses_client`` is replaced by a recorder, so what is asserted is the
exact SESv2 request this module builds — the From and Reply-To headers, the region, the
configuration set, both body parts — and how a refusal is classified for the outbox's retry rule.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import pytest
from botocore.exceptions import ClientError, EndpointConnectionError

from app.core.config import get_settings
from app.services import mailer

LINK = "https://dpw.example.org/set-password?token=abc.def"


def _settings(**overrides: Any) -> Any:
    base = {
        "mail_from_address": "no-reply@dpw.example.org",
        "mail_from_name": "Design Prototype Workshop",
        "mail_reply_to": "help@dpw.example.org",
        "mail_ses_region": "ap-south-1",
        "mail_ses_configuration_set": None,
        "next_public_app_url": "https://dpw.example.org",
    }
    base.update(overrides)
    return get_settings().model_copy(update=base)


class _FakeSes:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.requests: list[dict[str, Any]] = []

    def send_email(self, **request: Any) -> dict[str, Any]:
        self.requests.append(request)
        if self.error is not None:
            raise self.error
        return {"MessageId": "ses-message-1"}


@pytest.fixture
def ses(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    seen: dict[str, Any] = {"fake": _FakeSes()}

    def client(region: str, access_key: Any, secret_key: Any) -> _FakeSes:
        seen["region"] = region
        return seen["fake"]

    monkeypatch.setattr(mailer, "_ses_client", client)
    return seen


def _client_error(code: str) -> ClientError:
    return ClientError({"Error": {"Code": code, "Message": "no"}}, "SendEmail")


def test_mail_is_on_exactly_when_a_from_address_is_set() -> None:
    assert mailer.mail_configured(_settings())
    assert not mailer.mail_configured(_settings(mail_from_address=None))
    assert not mailer.mail_configured(_settings(mail_from_address="   "))


def test_the_request_carries_both_parts_the_sender_and_the_reply_to(ses: dict[str, Any]) -> None:
    message_id = mailer.send(
        to="asha@example.org", subject="Subject", text="Plain", html_body="<p>Html</p>",
        settings=_settings(mail_ses_configuration_set="dpw-events"),
    )
    assert message_id == "ses-message-1"
    assert ses["region"] == "ap-south-1"
    (request,) = ses["fake"].requests
    assert request["FromEmailAddress"] == '"Design Prototype Workshop" <no-reply@dpw.example.org>'
    assert request["ReplyToAddresses"] == ["help@dpw.example.org"]
    assert request["Destination"] == {"ToAddresses": ["asha@example.org"]}
    assert request["ConfigurationSetName"] == "dpw-events"
    body = request["Content"]["Simple"]["Body"]
    assert body["Text"]["Data"] == "Plain" and body["Html"]["Data"] == "<p>Html</p>"
    assert request["Content"]["Simple"]["Subject"]["Charset"] == "UTF-8"


def test_no_reply_to_and_no_configuration_set_when_unset(ses: dict[str, Any]) -> None:
    mailer.send(
        to="a@example.org", subject="s", text="t", html_body="h",
        settings=_settings(mail_reply_to=None),
    )
    (request,) = ses["fake"].requests
    assert "ReplyToAddresses" not in request
    assert "ConfigurationSetName" not in request


@pytest.mark.parametrize(
    ("error", "permanent"),
    [
        (_client_error("MessageRejected"), True),
        (_client_error("MailFromDomainNotVerifiedException"), True),
        (_client_error("TooManyRequestsException"), False),
        (_client_error("InternalFailure"), False),
        (EndpointConnectionError(endpoint_url="https://email.ap-south-1.amazonaws.com"), False),
    ],
)
def test_a_refusal_is_classified_for_the_retry_rule(
    ses: dict[str, Any], error: Exception, permanent: bool
) -> None:
    ses["fake"].error = error
    with pytest.raises(mailer.SendFailed) as raised:
        mailer.send(to="a@example.org", subject="s", text="t", html_body="h", settings=_settings())
    assert raised.value.permanent is permanent


def test_sending_without_mail_configured_refuses_before_ses(ses: dict[str, Any]) -> None:
    with pytest.raises(mailer.SendFailed):
        mailer.send(
            to="a@example.org", subject="s", text="t", html_body="h",
            settings=_settings(mail_from_address=None),
        )
    assert ses["fake"].requests == []


def test_an_invitation_carries_the_link_in_the_body_and_never_in_the_subject() -> None:
    params = {"purpose": "INVITE", "recipientName": "Asha", "expiresAt": "2026-10-12T09:00:00+00:00"}
    rendered = mailer.render(mailer.PASSWORD_LINK, params, secret=LINK, settings=_settings())
    assert LINK not in rendered.subject
    assert LINK in rendered.text
    assert "token=abc.def" in rendered.html
    assert "12 October 2026, 2:30 pm IST" in rendered.text
    assert rendered.text.startswith("Dear Asha,")
    assert rendered.subject == "Your Design Prototype Workshop account is ready"


def test_a_password_message_without_its_link_cannot_be_rendered() -> None:
    with pytest.raises(ValueError):
        mailer.render(mailer.PASSWORD_LINK, {"purpose": "RESET"}, secret=None, settings=_settings())


def test_a_review_notice_quotes_the_note_escaped_and_links_the_workshop() -> None:
    params = {
        "recipientName": "Asha",
        "workshopId": "ws_1",
        "workshopTitle": "Kantha, Bolpur",
        "inspectorName": "Ravi Nair",
        "note": "Stage 7 <costs> do not add up.",
        "stageTitle": "Costing",
    }
    rendered = mailer.render(mailer.REVIEW_SENT_BACK, params, settings=_settings())
    assert rendered.subject == "“Kantha, Bolpur” has been sent back for corrections"
    assert "https://dpw.example.org/design-workshops/ws_1" in rendered.text
    assert "> Stage 7 <costs> do not add up." in rendered.text
    assert "&lt;costs&gt;" in rendered.html and "<costs>" not in rendered.html
    assert "turn these e-mails off in Settings" in rendered.text
    suggestion = mailer.render(mailer.REVIEW_SUGGESTION, params, settings=_settings())
    assert "filed a correction suggestion" in suggestion.text


def test_moments_are_written_in_india_standard_time() -> None:
    assert mailer.format_moment(datetime(2026, 10, 10, 18, 45, tzinfo=UTC)) == (
        "11 October 2026, 12:15 am IST"
    )
