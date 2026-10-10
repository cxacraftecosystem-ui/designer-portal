"""E-MAIL: the Amazon SES sender and the templates it sends.

This module knows how to turn a message KIND and its parameters into a subject, a plain-text body
and a small HTML body, and how to hand those to SES. It knows nothing about queues, retries or who
asked: that is :mod:`app.services.email_outbox`, which is the only caller of :func:`send` and the
only writer of the send log.

── WHEN MAIL IS ON ───────────────────────────────────────────────────────────────────────────────

Exactly when ``MAIL_FROM_ADDRESS`` is set (:func:`mail_configured`). Unset is a complete, quiet
product: nothing is queued, ``GET /preferences/notifications`` answers ``available: false``, and
both clients hide every e-mail control without a sentence about it. There is no half-configured
state to describe on a screen.

── WHAT IS NEVER WRITTEN DOWN ────────────────────────────────────────────────────────────────────

No body, and no link, ever reaches a log line: the outbox logs the message id, its kind and the
recipient's ACCOUNT id. The set-password link in particular is a credential
(``credential_links``): it is rendered into the body in memory, inside the worker, and the
ciphertext that carried it to the worker is cleared as soon as the message is SENT or FAILED.

── SES, AND WHY SESv2 ────────────────────────────────────────────────────────────────────────────

``boto3.client("sesv2").send_email`` with ``Content.Simple`` — subject, text and HTML parts, UTF-8,
built by SES into a proper multipart/alternative message. The credentials are the same
``AWS_ACCESS_KEY_ID``/``AWS_SECRET_ACCESS_KEY`` the media bucket uses; that IAM user needs
``ses:SendEmail`` on the verified identity (docs/ENVIRONMENT.md lists the owner's steps). A
throttle or an SES-side fault is TRANSIENT and is retried by the outbox; a rejected message or an
unverified sender is PERMANENT and is not, because sending it again will be refused again.
"""

from __future__ import annotations

import html
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from functools import lru_cache
from typing import Any

from app.core.config import Settings, get_settings

logger = logging.getLogger(__name__)

#: Message kinds. Stored on ``EmailMessage.kind``; renaming one orphans the rows already queued.
PASSWORD_LINK = "PASSWORD_LINK"
REVIEW_SUGGESTION = "REVIEW_SUGGESTION"
REVIEW_SENT_BACK = "REVIEW_SENT_BACK"
KINDS = frozenset({PASSWORD_LINK, REVIEW_SUGGESTION, REVIEW_SENT_BACK})

PRODUCT_NAME = "Design Prototype Workshop"

#: India Standard Time has no daylight saving, so a fixed offset is exact and needs no tz database
#: (which a Windows development box does not ship).
IST = timezone(timedelta(hours=5, minutes=30), "IST")

#: SESv2 error codes after which sending the same message again cannot succeed.
PERMANENT_ERROR_CODES = frozenset(
    {
        "MessageRejected",
        "MailFromDomainNotVerifiedException",
        "BadRequestException",
        "NotFoundException",
        "AccountSuspendedException",
    }
)


class SendFailed(Exception):
    """SES did not accept the message. ``permanent`` says whether trying again could help."""

    def __init__(self, code: str, *, permanent: bool) -> None:
        super().__init__(code)
        self.code = code
        self.permanent = permanent


def mail_configured(settings: Settings | None = None) -> bool:
    settings = settings or get_settings()
    return bool((settings.mail_from_address or "").strip())


def _from_header(settings: Settings) -> str:
    name = (settings.mail_from_name or "").strip().replace('"', "")
    address = (settings.mail_from_address or "").strip()
    return f'"{name}" <{address}>' if name else address


@lru_cache(maxsize=4)
def _ses_client(region: str, access_key: str | None, secret_key: str | None) -> Any:
    import boto3
    from botocore.config import Config

    return boto3.client(
        "sesv2",
        region_name=region,
        aws_access_key_id=access_key or None,
        aws_secret_access_key=secret_key or None,
        config=Config(retries={"max_attempts": 2, "mode": "standard"}, connect_timeout=5, read_timeout=15),
    )


def send(*, to: str, subject: str, text: str, html_body: str, settings: Settings | None = None) -> str:
    """Hand one message to SES and return its message id. Blocking: call it from a thread.

    Raises :class:`SendFailed` for anything SES (or the network in front of it) refused.
    """
    from botocore.exceptions import BotoCoreError, ClientError

    settings = settings or get_settings()
    if not mail_configured(settings):
        raise SendFailed("MailNotConfigured", permanent=False)
    request: dict[str, Any] = {
        "FromEmailAddress": _from_header(settings),
        "Destination": {"ToAddresses": [to]},
        "Content": {
            "Simple": {
                "Subject": {"Data": subject, "Charset": "UTF-8"},
                "Body": {
                    "Text": {"Data": text, "Charset": "UTF-8"},
                    "Html": {"Data": html_body, "Charset": "UTF-8"},
                },
            }
        },
    }
    if (settings.mail_reply_to or "").strip():
        request["ReplyToAddresses"] = [settings.mail_reply_to.strip()]
    if (settings.mail_ses_configuration_set or "").strip():
        request["ConfigurationSetName"] = settings.mail_ses_configuration_set.strip()
    client = _ses_client(
        settings.mail_ses_region, settings.aws_access_key_id, settings.aws_secret_access_key
    )
    try:
        answer = client.send_email(**request)
    except ClientError as exc:
        code = str(exc.response.get("Error", {}).get("Code") or "ClientError")
        raise SendFailed(code, permanent=code in PERMANENT_ERROR_CODES) from None
    except BotoCoreError as exc:  # network, endpoint, credentials resolution
        raise SendFailed(type(exc).__name__, permanent=False) from None
    return str(answer.get("MessageId") or "")


# --------------------------------------------------------------------------------------
# Templates
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Rendered:
    subject: str
    text: str
    html: str


def app_url(path: str = "", settings: Settings | None = None) -> str:
    base = str((settings or get_settings()).next_public_app_url).rstrip("/")
    return f"{base}{path}"


def format_moment(value: Any) -> str:
    """"10 October 2026, 2:30 pm IST" from an aware datetime or an ISO string."""
    moment = value
    if isinstance(value, str):
        try:
            moment = datetime.fromisoformat(value)
        except ValueError:
            return value
    if not isinstance(moment, datetime):
        return str(value)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    local = moment.astimezone(IST)
    hour = local.hour % 12 or 12
    suffix = "am" if local.hour < 12 else "pm"
    return f"{local.day} {local.strftime('%B %Y')}, {hour}:{local.minute:02d} {suffix} IST"


def _greeting(name: Any) -> str:
    first = str(name or "").strip()
    return f"Dear {first}," if first else "Hello,"


def _html(paragraphs: list[str], *, button: tuple[str, str] | None = None, quote: str | None = None) -> str:
    """A deliberately plain HTML part: system fonts, one column, one button, no images, no tracking.

    Every interpolated value arrives here already escaped by the caller's use of :func:`_e`.
    """
    parts = [
        '<!doctype html><html><body style="margin:0;padding:24px;background:#f6f5f2;'
        'font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;">',
        '<div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #e3e0da;'
        'border-radius:8px;padding:24px;">',
        f'<p style="margin:0 0 16px;font-size:13px;letter-spacing:.04em;color:#6b6457;">'
        f"{_e(PRODUCT_NAME)}</p>",
    ]
    for index, paragraph in enumerate(paragraphs):
        parts.append(f'<p style="margin:0 0 14px;font-size:15px;line-height:1.55;">{paragraph}</p>')
        if quote is not None and index == 1:
            parts.append(
                '<blockquote style="margin:0 0 14px;padding:10px 14px;border-left:3px solid #b5651d;'
                f'background:#faf6f1;font-size:15px;line-height:1.55;white-space:pre-wrap;">{quote}</blockquote>'
            )
    if button is not None:
        label, href = button
        parts.append(
            f'<p style="margin:20px 0;"><a href="{href}" style="display:inline-block;background:#b5651d;'
            'color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:6px;font-size:15px;">'
            f"{label}</a></p>"
            '<p style="margin:0 0 14px;font-size:13px;line-height:1.5;color:#6b6457;">'
            f'If the button does not open, copy this address into your browser:<br>{href}</p>'
        )
    parts.append("</div></body></html>")
    return "".join(parts)


def _e(value: Any) -> str:
    return html.escape(str(value or ""), quote=True)


def subject_for(kind: str, params: dict[str, Any]) -> str:
    """The subject alone — stored on the outbox row so the send log reads without rendering."""
    title = str(params.get("workshopTitle") or "your design workshop").strip()
    if kind == PASSWORD_LINK:
        if params.get("purpose") == "INVITE":
            return f"Your {PRODUCT_NAME} account is ready"
        return f"Set a new password for {PRODUCT_NAME}"
    if kind == REVIEW_SUGGESTION:
        return f"A correction was suggested on “{title}”"
    if kind == REVIEW_SENT_BACK:
        return f"“{title}” has been sent back for corrections"
    raise ValueError(f"Unknown e-mail kind {kind!r}")


def render(
    kind: str, params: dict[str, Any], *, secret: str | None = None, settings: Settings | None = None
) -> Rendered:
    """Subject, plain text and HTML for one message. ``secret`` is the set-password link, if any."""
    settings = settings or get_settings()
    subject = subject_for(kind, params)
    greeting = _greeting(params.get("recipientName"))
    sign_off = f"— {PRODUCT_NAME}"

    if kind == PASSWORD_LINK:
        if not secret:
            raise ValueError("A password-link message needs its link")
        expires = format_moment(params.get("expiresAt"))
        if params.get("purpose") == "INVITE":
            lead = (
                f"An administrator has made you an account on {PRODUCT_NAME}. "
                "Choose your password with the link below, then sign in with this e-mail address."
            )
            label = "Choose your password"
        else:
            lead = (
                f"An administrator has issued you a link to set a new password for {PRODUCT_NAME}. "
                "Setting it signs you out everywhere you are signed in."
            )
            label = "Set a new password"
        rule = f"The link works once and expires on {expires}."
        unasked = "If you were not expecting this, ignore this message; nothing changes until the link is used."
        text = "\n\n".join([greeting, lead, secret, rule, unasked, sign_off]) + "\n"
        body = _html(
            [_e(greeting), _e(lead), _e(rule), _e(unasked)],
            button=(_e(label), _e(secret)),
        )
        return Rendered(subject, text, body)

    if kind in (REVIEW_SUGGESTION, REVIEW_SENT_BACK):
        title = str(params.get("workshopTitle") or "your design workshop")
        inspector = str(params.get("inspectorName") or "An inspecting officer")
        stage = str(params.get("stageTitle") or "").strip()
        where = f" on “{stage}”" if stage else ""
        if kind == REVIEW_SENT_BACK:
            lead = (
                f"{inspector} has sent “{title}” back for corrections{where}. "
                "The report now reads Needs revision; saving your corrections hands it back in."
            )
        else:
            lead = (
                f"{inspector} has filed a correction suggestion on “{title}”{where}. "
                "The report stays where it is; the suggestion is waiting for you on the workshop."
            )
        note = str(params.get("note") or "").strip()
        link = app_url(f"/design-workshops/{params.get('workshopId')}", settings)
        why = (
            "You are receiving this because you work on this workshop. "
            "You can turn these e-mails off in Settings."
        )
        text_parts = [greeting, lead]
        if note:
            text_parts.append("\n".join(f"> {line}" for line in note.splitlines()))
        text_parts += [f"Open the workshop: {link}", why, sign_off]
        body = _html(
            [_e(greeting), _e(lead), _e(why)],
            button=("Open the workshop", _e(link)),
            quote=_e(note) if note else None,
        )
        return Rendered(subject, "\n\n".join(text_parts) + "\n", body)

    raise ValueError(f"Unknown e-mail kind {kind!r}")
