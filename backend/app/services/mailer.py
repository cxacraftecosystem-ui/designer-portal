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
#: Somebody got past the password and then entered too many wrong verification codes. A security
#: notice: always sent (no opt-out), carries no code and no address, only when the pause ends.
SECOND_FACTOR_LOCKED = "SECOND_FACTOR_LOCKED"
#: Security notices about the account's password (2026-10-17). Always sent (no opt-out), to the
#: account's own address; they carry no password and no link, only what happened and when.
#: PASSWORD_ADDED: a provider-only account chose its first password. PASSWORD_CHANGED: the password
#: was changed, set through a link, or replaced by an administrator's temporary one.
PASSWORD_ADDED = "PASSWORD_ADDED"
PASSWORD_CHANGED = "PASSWORD_CHANGED"
PASSWORD_NOTICES = frozenset({PASSWORD_ADDED, PASSWORD_CHANGED})

#: Designers' notices (2026-10-18), governed by ``UserPreference.emailWorkshopUpdates``.
REPORT_APPROVED = "REPORT_APPROVED"
REPORT_HANDED_ON = "REPORT_HANDED_ON"
WORKSHOP_ASSIGNED = "WORKSHOP_ASSIGNED"
SETUP_REMINDER = "SETUP_REMINDER"
WORKSHOP_UPDATES = frozenset({REPORT_APPROVED, REPORT_HANDED_ON, WORKSHOP_ASSIGNED, SETUP_REMINDER})

#: Access and comment notices (2026-10-18), governed by ``UserPreference.emailAccessNotices``.
ACCESS_REQUESTED = "ACCESS_REQUESTED"
ACCESS_DECIDED = "ACCESS_DECIDED"
RECORD_COMMENT = "RECORD_COMMENT"
ACCESS_NOTICES = frozenset({ACCESS_REQUESTED, ACCESS_DECIDED, RECORD_COMMENT})

#: Officials' notices (2026-10-18), governed by ``UserPreference.emailHandInNotices``: one message
#: per hand-in, or the day's hand-ins gathered into one digest.
REPORT_HANDED_IN = "REPORT_HANDED_IN"
HAND_IN_DIGEST = "HAND_IN_DIGEST"

#: An approved report e-mailed to the office under an expiring link (2026-10-18). Not a notification:
#: it goes to the addresses an administrator set, and the link travels sealed like a password link.
REPORT_DELIVERY = "REPORT_DELIVERY"

#: The Monday summary for reviewers and officials (services/weekly_digest.py). Opt-out through
#: ``UserPreference.weeklyDigest``; carries counts, workshop titles and app paths, nothing personal.
WEEKLY_DIGEST = "WEEKLY_DIGEST"
#: A month's Ministry export packs are ready (2026-10-18). One digest per subscribed recipient per
#: period. Not a notification under a preference: subscribing on /ministry-exports is the opt-in and
#: that page is where one unsubscribes. Carries no presigned link and no attachment — the button opens
#: the signed-in page, which mints a five-minute link on click.
MINISTRY_PACKS_READY = "MINISTRY_PACKS_READY"

KINDS = frozenset(
    {
        PASSWORD_LINK,
        REVIEW_SUGGESTION,
        REVIEW_SENT_BACK,
        SECOND_FACTOR_LOCKED,
        *PASSWORD_NOTICES,
        *WORKSHOP_UPDATES,
        *ACCESS_NOTICES,
        REPORT_HANDED_IN,
        HAND_IN_DIGEST,
        REPORT_DELIVERY,
        WEEKLY_DIGEST,
        MINISTRY_PACKS_READY,
    }
)

#: The kinds whose sealed secret is a link with an ``expiresAt``: not sent once it has expired.
EXPIRING_LINK_KINDS = frozenset({PASSWORD_LINK, REPORT_DELIVERY})

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


def locked_until_words(value: Any) -> str:
    """When a two-step pause ends, in words: the documentation portal's helper, by name, in this
    product's own clock (:func:`format_moment`, India Standard Time)."""
    return format_moment(value) if value else "later today"


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


# --------------------------------------------------------------------------------------
# The Monday summary (services/weekly_digest.py)
# --------------------------------------------------------------------------------------

#: How many workshop titles the e-mail names under Waiting and Overdue; the page shows five.
DIGEST_EMAIL_ITEMS = 3
_DIGEST_LABEL = "margin:0 0 2px;font-size:13px;letter-spacing:.06em;text-transform:uppercase;color:#6b6457;"
_DIGEST_FIGURE = "margin:0;font-size:22px;line-height:1.3;font-weight:600;color:#1f2328;"
_DIGEST_NOTE = "margin:2px 0 0;font-size:13px;line-height:1.5;color:#6b6457;"


def _count(value: Any) -> int:
    try:
        return max(int(value or 0), 0)
    except (TypeError, ValueError):
        return 0


def digest_title(waiting: Any, overdue: Any) -> str:
    """The subject and the in-app title, one sentence for both."""
    w, o = _count(waiting), _count(overdue)
    return f"Your week: {w} waiting, {o} overdue" if o else f"Your week: {w} waiting"


def coverage_words(coverage: dict[str, Any] | None) -> tuple[str, str, str]:
    """``(label, figure, note)`` for the coverage block: "Reports in", "71%", "41 of 58"."""
    cov = coverage or {}
    label = str(cov.get("label") or "Coverage")
    value = _count(cov.get("value"))
    of = cov.get("of")
    if of is None:
        return label, str(value), ""
    percent = cov.get("percent")
    figure = f"{_count(percent)}%" if percent is not None else str(value)
    return label, figure, f"{value} of {_count(of)}"


# The lines whose workshops are listed by title. "and N more" counts only these, so a sanction order
# or a record to review is never passed off as one more workshop.
_TITLED_LINES = {"waiting": ("approvals", "inspections"), "overdue": ("stages", "reviews")}


def _titled_total(section: dict[str, Any], key: str) -> int:
    keys = _TITLED_LINES.get(key, ())
    return sum(
        _count(line.get("count"))
        for line in section.get("lines") or []
        if isinstance(line, dict) and line.get("key") in keys
    )


def _digest_blocks(sections: list[dict[str, Any]], coverage: dict[str, Any] | None) -> list[dict[str, Any]]:
    """The four blocks in their fixed order, from the stored payload."""
    by_key = {str(s.get("key")): s for s in sections or [] if isinstance(s, dict)}
    blocks: list[dict[str, Any]] = []
    for key, label in (("waiting", "Waiting for you"), ("overdue", "Overdue"), ("new", "New this week")):
        section = by_key.get(key) or {}
        total = _count(section.get("total"))
        items = [i for i in (section.get("items") or []) if isinstance(i, dict)] if key != "new" else []
        shown = items[:DIGEST_EMAIL_ITEMS]
        blocks.append({"label": label, "figure": str(total), "note": "", "items": shown,
                       "more": max(_titled_total(section, key) - len(shown), 0) if shown else 0})
    label, figure, note = coverage_words(coverage)
    blocks.append({"label": label, "figure": figure, "note": note, "items": [], "more": 0})
    return blocks


def _digest_html(
    greeting: str,
    blocks: list[dict[str, Any]],
    *,
    button: tuple[str, str],
    footer: tuple[str, str],
    settings: Settings | None = None,
) -> str:
    """The summary's HTML part, in :func:`_html`'s shell: the same page, card, button and fonts.

    Four compact blocks, each a small uppercase label over one figure, with at most three linked
    workshop titles under Waiting and Overdue. Every value is escaped here; nothing arrives escaped.
    """
    parts = [
        '<!doctype html><html><body style="margin:0;padding:24px;background:#f6f5f2;'
        'font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;">',
        '<div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #e3e0da;'
        'border-radius:8px;padding:24px;">',
        f'<p style="margin:0 0 16px;font-size:13px;letter-spacing:.04em;color:#6b6457;">'
        f"{_e(PRODUCT_NAME)}</p>",
        f'<p style="margin:0 0 18px;font-size:15px;line-height:1.55;">{_e(greeting)}</p>',
    ]
    for block in blocks:
        parts.append('<div style="margin:0 0 16px;padding:0 0 14px;border-bottom:1px solid #efece6;">')
        parts.append(f'<p style="{_DIGEST_LABEL}">{_e(block["label"])}</p>')
        parts.append(f'<p style="{_DIGEST_FIGURE}">{_e(block["figure"])}</p>')
        if block.get("note"):
            parts.append(f'<p style="{_DIGEST_NOTE}">{_e(block["note"])}</p>')
        for item in block.get("items") or []:
            href = app_url(str(item.get("link") or ""), settings)
            parts.append(
                f'<p style="margin:6px 0 0;font-size:14px;line-height:1.45;"><a href="{_e(href)}" '
                f'style="color:#b5651d;text-decoration:none;">{_e(item.get("title"))}</a></p>'
            )
        if block.get("more"):
            parts.append(f'<p style="{_DIGEST_NOTE}">and {_e(block["more"])} more</p>')
        parts.append("</div>")
    label, href = button
    parts.append(
        f'<p style="margin:20px 0;"><a href="{_e(href)}" style="display:inline-block;background:#b5651d;'
        'color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:6px;font-size:15px;">'
        f"{_e(label)}</a></p>"
    )
    words, settings_href = footer
    parts.append(
        f'<p style="margin:0;font-size:13px;line-height:1.5;color:#6b6457;">{_e(words)} '
        f'<a href="{_e(settings_href)}" style="color:#6b6457;">Settings</a>.</p>'
    )
    parts.append("</div></body></html>")
    return "".join(parts)


def _digest_text(greeting: str, blocks: list[dict[str, Any]], link: str, footer: str, settings_link: str,
                 settings: Settings | None = None) -> str:
    lines = [greeting, ""]
    for block in blocks:
        figure = f"{block['figure']} ({block['note']})" if block.get("note") else block["figure"]
        lines.append(f"{block['label']}: {figure}")
        for item in block.get("items") or []:
            lines.append(f"  - {item.get('title')}: {app_url(str(item.get('link') or ''), settings)}")
        if block.get("more"):
            lines.append(f"  and {block['more']} more")
    lines += ["", f"Open your digest: {link}", "", f"{footer} {settings_link}", "", f"— {PRODUCT_NAME}"]
    return "\n".join(lines) + "\n"


def _e(value: Any) -> str:
    return html.escape(str(value or ""), quote=True)


def subject_for(kind: str, params: dict[str, Any]) -> str:
    """The subject alone — stored on the outbox row so the send log reads without rendering."""
    title = str(params.get("workshopTitle") or "your design workshop").strip()
    if kind == PASSWORD_LINK:
        if params.get("context") == "SANCTION":
            return f"Your workshop is ready: “{title}”"
        if params.get("purpose") == "INVITE":
            return f"Your {PRODUCT_NAME} account is ready"
        return f"Set a new password for {PRODUCT_NAME}"
    if kind == REVIEW_SUGGESTION:
        return f"A correction was suggested on “{title}”"
    if kind == REVIEW_SENT_BACK:
        return f"“{title}” has been sent back for corrections"
    if kind == SECOND_FACTOR_LOCKED:
        return f"Someone used your {PRODUCT_NAME} password"
    if kind == PASSWORD_ADDED:
        return f"A password was added to your {PRODUCT_NAME} account"
    if kind == PASSWORD_CHANGED:
        return f"Your {PRODUCT_NAME} password was changed"
    if kind == REPORT_APPROVED:
        return f"“{title}” has been approved"
    if kind == REPORT_HANDED_ON:
        return f"“{title}” has been handed on to the office"
    if kind == WORKSHOP_ASSIGNED:
        return f"You have been given a workshop: “{title}”"
    if kind == SETUP_REMINDER:
        return f"Reminder: finish setting up “{title}”"
    if kind == ACCESS_REQUESTED:
        who = str(params.get("requesterName") or "Someone").strip()
        if params.get("scope") == "workshop":
            return f"{who} asked to join “{title}”"
        return f"{who} asked for access to your data"
    if kind == ACCESS_DECIDED:
        granted = params.get("decision") == "GRANTED"
        if params.get("scope") == "workshop":
            return f"You can now open “{title}”" if granted else f"Your request to join “{title}” was declined"
        return "Your data access request was granted" if granted else "Your data access request was declined"
    if kind == RECORD_COMMENT:
        who = str(params.get("authorName") or "Someone").strip()
        return f"{who} commented on {params.get('recordLabel') or 'your record'}"
    if kind == REPORT_HANDED_IN:
        if params.get("resubmission"):
            return f"“{title}” was resubmitted (round {params.get('round') or '?'})"
        return f"“{title}” was handed in for review"
    if kind == HAND_IN_DIGEST:
        count = len(params.get("items") or [])
        return f"{count} report{'s' if count != 1 else ''} handed in for review"
    if kind == REPORT_DELIVERY:
        return f"Approved report: “{title}”"
    if kind == WEEKLY_DIGEST:
        return digest_title(params.get("waiting"), params.get("overdue"))
    if kind == MINISTRY_PACKS_READY:
        return f"Ministry packs for {params.get('periodLabel') or 'last month'} are ready"
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
        if params.get("context") == "SANCTION":
            # A sanction order named this person (2026-10-18): the officer's e-mail, sent for them.
            order = str(params.get("sanctionOrderNo") or "").strip()
            title = str(params.get("workshopTitle") or "your design workshop")
            lead = (
                f"Sanction order {order} names you as the designer of “{title}”."
                if order
                else f"You are the designer of “{title}”."
            )
            if params.get("purpose") == "INVITE":
                lead += " Choose your password with the link below, then sign in with this e-mail address."
                label = "Choose your password"
            else:
                lead += " Set your password with the link below. It signs you out everywhere else."
                label = "Set your password"
        elif params.get("purpose") == "INVITE":
            lead = (
                f"An administrator has made you an account on {PRODUCT_NAME}. "
                "Choose your password with the link below, then sign in with this e-mail address."
            )
            label = "Choose your password"
        elif params.get("requestedBy") == "self":
            # "Forgot your password?" — the person asked, so the message says so, and says that the
            # second step (if it is on) is still asked for at sign-in afterwards.
            lead = (
                f"You asked for a link to set a new password for {PRODUCT_NAME}. Setting it signs you "
                "out everywhere you are signed in. If two-step verification is on for your account, "
                "you will still need your authenticator app to sign in afterwards."
            )
            label = "Set a new password"
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

    if kind == SECOND_FACTOR_LOCKED:
        until = locked_until_words(params.get("lockedUntil"))
        lead = (
            f"Someone entered your {PRODUCT_NAME} password and then several wrong verification codes. "
            f"To protect your account, two-step sign-in is paused until {until}."
        )
        act = (
            "If this was not you, change your password now — or ask an administrator to change it — "
            "because whoever did this knows it. If you sign in with Google, Microsoft or Yahoo, change "
            "the password of that account."
        )
        # Only the six-digit pause leaves a way in; a recovery-code pause says nothing it cannot keep.
        later = ["Your recovery codes still work while the pause lasts."] if params.get("codeKind") != "recovery" else []
        link = app_url("/login", settings)
        text = "\n\n".join([greeting, lead, act, *later, sign_off]) + "\n"
        return Rendered(
            subject,
            text,
            _html([_e(greeting), _e(lead), _e(act), *(_e(line) for line in later)], button=("Open the sign-in page", _e(link))),
        )

    if kind in PASSWORD_NOTICES:
        when = format_moment(params.get("at"))
        if kind == PASSWORD_ADDED:
            lead = (
                f"A password was added to your {PRODUCT_NAME} account on {when}. You can now sign in "
                "with your e-mail address and that password, as well as the way you signed in before."
            )
        elif params.get("by") == "administrator":
            lead = (
                f"An administrator set a temporary password on your {PRODUCT_NAME} account on {when}. "
                "You will be asked to choose your own when you next sign in."
            )
        else:
            lead = f"The password of your {PRODUCT_NAME} account was changed on {when}."
        act = (
            "If this wasn't you, ask an administrator to sign you out everywhere and reset your "
            "password."
        )
        link = app_url("/login", settings)
        text = "\n\n".join([greeting, lead, act, sign_off]) + "\n"
        return Rendered(
            subject, text, _html([_e(greeting), _e(lead), _e(act)], button=("Open the sign-in page", _e(link)))
        )

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

    if kind == REPORT_DELIVERY:
        if not secret:
            raise ValueError("A report-delivery message needs its link")
        title = str(params.get("workshopTitle") or "the design workshop")
        office = str(params.get("officeName") or "the office").strip()
        approver = str(params.get("approverName") or "The approving authority").strip()
        order = str(params.get("sanctionOrderNo") or "").strip()
        lead = f"{approver} has approved the report “{title}” and handed it on to {office}."
        if order:
            lead += f" Sanction order {order}."
        rule = f"Download it with the link below. The link expires on {format_moment(params.get('expiresAt'))}."
        unasked = "If this reached you by mistake, delete it; the link opens only this report."
        text = "\n\n".join(["Hello,", lead, secret, rule, unasked, sign_off]) + "\n"
        body = _html(
            [_e("Hello,"), _e(lead), _e(rule), _e(unasked)],
            button=("Download the report", _e(secret)),
        )
        return Rendered(subject, text, body)

    if kind == MINISTRY_PACKS_READY:
        period = str(params.get("periodLabel") or "last month")
        lead = f"The Ministry packs for {period} are ready:"
        items = []
        for scheme in params.get("schemes") or []:
            if not isinstance(scheme, dict):
                continue
            count = int(scheme.get("workshops") or 0)
            items.append(f"{scheme.get('label') or 'A scheme'} — {count} workshop{'s' if count != 1 else ''}")
        link = str(params.get("url") or app_url("/ministry-exports", settings))
        why = "You get this because you subscribed on Ministry exports, where you can also unsubscribe."
        text_parts = [greeting, lead]
        if items:
            text_parts.append("\n".join(f"• {item}" for item in items))
        text_parts += [f"Open the packs: {link}", why, sign_off]
        paragraphs = [_e(greeting), _e(lead)]
        if items:
            paragraphs.append("<br>".join(f"• {_e(item)}" for item in items))
        paragraphs.append(_e(why))
        body = _html(paragraphs, button=("Open the packs", _e(link)))
        return Rendered(subject, "\n\n".join(text_parts) + "\n", body)

    notice = _notice_parts(kind, params)
    if notice is not None:
        lead, quote, items, (label, path), why = notice
        link = app_url(path, settings)
        text_parts = [greeting, lead]
        if quote:
            text_parts.append("\n".join(f"> {line}" for line in quote.splitlines()))
        if items:
            text_parts.append("\n".join(f"• {item}" for item in items))
        text_parts += [f"{label}: {link}", why, sign_off]
        paragraphs = [_e(greeting), _e(lead)]
        if items:
            paragraphs.append("<br>".join(f"• {_e(item)}" for item in items))
        paragraphs.append(_e(why))
        body = _html(paragraphs, button=(_e(label), _e(link)), quote=_e(quote) if quote else None)
        return Rendered(subject, "\n\n".join(text_parts) + "\n", body)

    if kind == WEEKLY_DIGEST:
        blocks = _digest_blocks(list(params.get("sections") or []), params.get("coverage"))
        link = app_url(f"/digest/{params.get('digestId') or ''}", settings)
        settings_link = app_url("/settings", settings)
        footer = "You get this every Monday. Turn it off in Settings."
        text = _digest_text(greeting, blocks, link, footer, settings_link, settings)
        body = _digest_html(
            greeting, blocks, button=("Open your digest", link),
            footer=("You get this every Monday. Turn it off in", settings_link), settings=settings,
        )
        return Rendered(subject, text, body)

    raise ValueError(f"Unknown e-mail kind {kind!r}")


#: Why the reader got a notice, and how to stop it — one sentence per preference.
_WHY_WORKSHOP = "You get these because you work on this workshop. You can turn them off in Settings."
_WHY_ACCESS = "You can turn these e-mails off in Settings."
_WHY_HAND_IN = (
    "You get these because you review this report. In Settings you can switch to one daily digest "
    "or turn them off."
)

_RECORD_PATHS = {
    "artisan": "/artisans/{id}",
    "craft": "/crafts/{id}",
    "workshop": "/workshops/{id}",
    "product": "/products/{id}",
    "tool": "/tools/{id}",
    "process": "/processes/{id}",
    "questionnaire": "/questionnaire/interviews/{id}",
    "questionnaireinterview": "/questionnaire/interviews/{id}",
    "media": "/media/{id}",
}


def record_path(record_type: Any, record_id: Any) -> str:
    """The read-only page of one record, for a comment notice's button."""
    template = _RECORD_PATHS.get(str(record_type or "").lower(), "/sharing")
    return template.replace("{id}", str(record_id or ""))


def _notice_parts(
    kind: str, params: dict[str, Any]
) -> tuple[str, str | None, list[str], tuple[str, str], str] | None:
    """``(lead, quote, items, (button label, path), why)`` for the plain notices, or ``None``."""
    title = str(params.get("workshopTitle") or "your design workshop")
    workshop_path = f"/design-workshops/{params.get('workshopId')}"
    actor = str(params.get("actorName") or "").strip()
    note = str(params.get("note") or "").strip() or None

    if kind == REPORT_APPROVED:
        who = actor or "The approving authority"
        lead = f"{who} has approved “{title}”. It goes to the office next."
        return lead, note, [], ("Open the workshop", workshop_path), _WHY_WORKSHOP
    if kind == REPORT_HANDED_ON:
        office = str(params.get("officeName") or "the office").strip()
        lead = f"“{title}” has been approved and handed on to {office}."
        if params.get("emailed"):
            lead += " The report was e-mailed to the office."
        return lead, note, [], ("Open the workshop", workshop_path), _WHY_WORKSHOP
    if kind == WORKSHOP_ASSIGNED:
        order = str(params.get("sanctionOrderNo") or "").strip()
        if order:
            lead = f"Sanction order {order} names you as a designer of “{title}”. Sign in as you usually do."
        else:
            who = actor or "An administrator"
            lead = f"{who} has added you to “{title}”."
        return lead, None, [], ("Open the workshop", workshop_path), _WHY_WORKSHOP
    if kind == SETUP_REMINDER:
        who = actor or "A ministry officer"
        missing = [str(item) for item in (params.get("missing") or []) if str(item).strip()]
        lead = f"{who} asks you to finish setting up “{title}”."
        if missing:
            lead += " Still needed on Workshop Setup:"
        stage = str(params.get("stageKey") or "WORKSHOP_SETUP")
        return lead, note, missing, ("Open Workshop Setup", f"{workshop_path}/stages/{stage}"), _WHY_WORKSHOP
    if kind == ACCESS_REQUESTED:
        who = str(params.get("requesterName") or "Someone").strip()
        if params.get("scope") == "workshop":
            lead = f"{who} asked to join “{title}”."
            return lead, note, [], ("Review the request", "/workshop-access/manage"), _WHY_ACCESS
        tier = str(params.get("tier") or "").strip().lower()
        lead = f"{who} asked for {tier + ' ' if tier else ''}access to your data."
        return lead, note, [], ("Review the request", "/sharing"), _WHY_ACCESS
    if kind == ACCESS_DECIDED:
        granted = params.get("decision") == "GRANTED"
        decider = actor or ("An administrator" if params.get("scope") == "workshop" else "The owner")
        if params.get("scope") == "workshop":
            lead = (
                f"{decider} let you into “{title}”." if granted else f"{decider} declined your request to join “{title}”."
            )
            target = (workshop_path, "Open the workshop") if granted else ("/workshop-access", "Open Workshop access")
            return lead, note, [], (target[1], target[0]), _WHY_ACCESS
        tier = str(params.get("tier") or "").strip().lower()
        lead = (
            f"{decider} granted you {tier + ' ' if tier else ''}access to their data."
            if granted
            else f"{decider} declined your request for access to their data."
        )
        return lead, note, [], ("Open Sharing", "/sharing"), _WHY_ACCESS
    if kind == RECORD_COMMENT:
        who = str(params.get("authorName") or "Someone").strip()
        label = str(params.get("recordLabel") or "your record")
        lead = f"{who} commented on {label}."
        path = record_path(params.get("recordType"), params.get("recordId"))
        return lead, str(params.get("body") or "").strip() or None, [], ("Open the record", path), _WHY_ACCESS
    if kind == REPORT_HANDED_IN:
        designer = str(params.get("designerName") or "Its designer").strip()
        round_no = params.get("round")
        if params.get("resubmission"):
            lead = f"{designer} has resubmitted “{title}” with corrections (round {round_no})."
        else:
            lead = f"{designer} has handed in “{title}” for review."
        path = _official_path(params)
        return lead, None, [], ("Open the report", path), _WHY_HAND_IN
    if kind == HAND_IN_DIGEST:
        items = []
        for item in params.get("items") or []:
            if not isinstance(item, dict):
                continue
            name = str(item.get("workshopTitle") or "A design workshop")
            what = f"resubmitted, round {item.get('round')}" if item.get("resubmission") else "handed in"
            items.append(f"{name} — {what}")
        lead = "Reports handed in for your review since the last digest:"
        path = "/design-workshop-approvals" if params.get("approver") else "/design-workshop-inspections"
        return lead, None, items, ("Open your queue", path), _WHY_HAND_IN
    return None


def _official_path(params: dict[str, Any]) -> str:
    """Where an official opens a handed-in report: the approvals screen for the approving authority,
    the inspection screen for an inspector."""
    workshop_id = params.get("workshopId")
    if params.get("approver"):
        return f"/design-workshop-approvals/{workshop_id}"
    return f"/design-workshop-inspections/{workshop_id}"
