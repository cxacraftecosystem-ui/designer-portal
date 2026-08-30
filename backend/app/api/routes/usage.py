"""Reading the usage record back: which screens are reached, and where they are slow.

**`/usage`, NOT `/analytics`, AND THE COLLISION IS THE REASON.** `/api/analytics/design-workshops`
is a cross-workshop CONTENT comparison — adoption rates, follow-up at 3, 6 and 12 months,
cost-to-price ratios — served to `/admin/analytics` under the nav label "Cross-workshop analytics".
It observes craft outcomes and no person at all. This module observes people. A second, unrelated
meaning of the word "analytics" would leave every future reader to work out which one a name meant,
in a codebase where one of the two is a privacy surface. The schema settles it in as many words:
"The prefix here is `Usage`; the route is `/usage`."

It is also not AI-credit usage. `DwAiVerbDailyUsage` and `DwDictationDailyUsage` are SPEND METERS
counting how many proofreads and transcriptions one designer has bought today; this is navigation.

── WHO MAY READ WHAT, AND WHY IT IS SPLIT THAT WAY ──────────────────────────────────────────────

The sensitive thing in this feature is not the aggregate. It is the row-level trail of a NAMED
designer: what they opened, in what order, at what hour. So the split is not "admins see more" but
"nobody sees another person's trail at all":

* **`GET /usage/me`** and **`GET /usage/me/trail`** — the caller's own use, and only ever the
  caller's own. `get_current_user`, because a person reading what the system recorded about them needs
  permission from nobody. There is no `?userId=` on either. Pointing them at somebody else is not a
  parameter this module withholds; it is a route that does not exist.
* **`GET /usage/routes`**, **`/usage/timeline`**, **`/usage/latency`**, **`/usage/clients`**,
  **`/usage/screens`** and **`GET /usage/collection`** — cross-account, aggregate only, behind
  `require_usage_reader` (Admin and above; see `deps.can_read_usage` for why not Researcher, which is
  the floor the research use case would prefer). No user id leaves any of them: every distinct-people
  count is folded into an integer inside `services/usage.py`, so a route module physically cannot leak
  one by accident.
* **`GET /usage/accounts/{user_id}/trail`** — ONE NAMED COLLEAGUE'S request-by-request log, and the
  most sensitive read in this feature. **The master admin alone**, behind its OWN dependency
  (`deps.require_person_usage_reader`), and additionally refused unless that account's own answer is
  `GRANTED`.

  This bullet used to read **"Nowhere"**, and it is worth saying what replaced it and why, because
  the old bullet was not merely a description — it was a promise. It said: *"If that is ever wanted it
  is a new route with its own dependency and its own written argument, NOT a query parameter added to
  the three above. A parameter is how a boundary gets crossed by somebody who never read the paragraph
  explaining it."* Every clause of that was honoured. It is a new route on a new path segment; it has
  a new predicate (`can_read_person_usage`) that no other power in `deps.py` shares, so widening it
  cannot widen anything else; it is one rung ABOVE the aggregates rather than beside them, on
  `can_read_usage`'s own argument taken one step further — a named person's minute-by-minute trail is
  strictly more revealing than an aggregate that cannot say who; and there is still no `?userId=`
  anywhere in this module.

  **THE RANK IS NOT THE WHOLE GATE, AND THE SECOND HALF IS THE ONE NOBODY CAN GRANT.** The subject's
  own consent must be `GRANTED`. That is unavoidable rather than merely policy — `collection_plan`
  attributes rows under `GRANTED` alone, so an account that refused or was never asked has no
  attributed rows to read — but the route says so with a SENTENCE rather than returning an empty
  list, which is the exact defect `/usage/me`'s own docstring names: an empty list "would be read as
  'you have never used the app'." Three distinct answers, three distinct next moves.

  WHAT IS STILL MISSING, NAMED RATHER THAN IMPLIED: there is no durable audit TABLE recording who
  read whose trail. The usage table cannot record it — `/usage/*` is in `UNRECORDED_TEMPLATES`
  precisely so the dataset is not a record of itself — so each read writes a server log line naming
  the reader, the subject and the window, and that is all. A real audit row is a schema decision
  somebody should take on purpose.

THE GATE IS THE DEPENDENCY, and `frontend/lib/permissions.ts` merely mirrors it. This repository has
twice shipped a UI guard over an open endpoint — the link disappeared and the URL stayed open — which
is why `analytics.py` says the same thing at the top of its own module.

── CONSENT: FOUR ROUTES, AND ONE OF THEM IS DELIBERATELY UNGATED ─────────────────────────────────

`GET /usage/consent/notice` has **no dependency at all**. It is the text a person reads while
deciding whether to agree, and at that moment on the web sign-in screen they have no token — so a
gate there would mean the only way to see what you are agreeing to is to agree first. It carries no
figures about anybody: it is the published method, computed from the policy in force.

`GET /usage/consent`, `POST /usage/consent` and `POST /usage/consent/withdraw` are
`get_current_user` and nothing more, on `/usage/me`'s argument exactly: recording your own answer
about your own data needs permission from nobody, and routing it through an admin gate would mean
asking an administrator for permission to refuse.

── THE RULES THIS MODULE OBEYS ──────────────────────────────────────────────────────────────────

**A DATE RANGE IS REQUIRED AND CAPPED, AND EVERY RESPONSE STATES THE CAP.** Not because a caller
cannot be trusted with a wide window, but because a cap nobody is told about is indistinguishable
from a dataset that ends there — the failure `analytics.ROW_CAP` records ("A CAP THAT IS ANNOUNCED IS
A CAP; A CAP THAT IS SILENT IS A LIE") and the one `X-Truncated` was added to `GET /feedback` for. A
window wider than the cap is REFUSED with a sentence naming the number, rather than quietly narrowed:
narrowing would answer a different question than the one asked and label the answer with the original
dates.

**AN AGGREGATE OVER THREE PEOPLE IS NOT AN AGGREGATE.** `services/usage.py` withholds any route with
between one and `MIN_IDENTIFIED_USERS_FOR_ROUTE` identified accounts in the window, reporting `null`
and `withheld: true` rather than a number. That is a REFUSAL and never a zero — note that `null`
coerces to 0 through arithmetic and through `??`, so a client must branch on `withheld`, exactly as
`workshopAnalytics.ts` does for the rates it renders as a bare em dash.

**THE PAGINATION IS THE HOUSE'S.** `normalize_pagination` / `page_payload`, as every other list in
this API, rather than a bespoke cursor invented for one screen.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import Field

from app.core.db import db
from app.core.deps import (
    get_current_user,
    get_value,
    invalidate_cached_user,
    require_person_usage_reader,
    require_usage_reader,
)
from app.schemas.common import APIModel
from app.services import usage
from app.services.pagination import normalize_pagination, page_payload

router = APIRouter(prefix="/usage", tags=["usage"])
logger = logging.getLogger(__name__)


#: This module's own route templates, named once so `app/main.py` can build the recorder's skip set
#: from the same list a reader of this file sees. See :data:`UNRECORDED_TEMPLATES`.
#:
#: **EVERY NEW ROUTE IN THIS MODULE BELONGS HERE, AND THE OMISSION IS SILENT.** A read route left out
#: is recorded like any other screen, so the table becomes partly a record of itself: refreshing a
#: usage dashboard would raise "requests per day", and in any window containing an analyst the
#: busiest route would be the analyst. `tests/test_usage_tracking.py` walks the router against this
#: set, because nothing else would notice.
USAGE_READ_TEMPLATES: frozenset[str] = frozenset(
    {
        "/usage/me",
        "/usage/me/trail",
        "/usage/routes",
        "/usage/timeline",
        "/usage/latency",
        "/usage/clients",
        "/usage/screens",
        "/usage/collection",
        "/usage/consent",
        "/usage/consent/notice",
        "/usage/consent/withdraw",
        "/usage/accounts/{user_id}/trail",
    }
)


#: **WHAT THE RECORDER DELIBERATELY DOES NOT WRITE.** Imported by `app/main.py`, which skips exactly
#: these, and reported by :func:`collection_method` so the omission is part of the published method
#: rather than a surprise somebody derives from a suspiciously flat graph. Each entry is an argument:
#:
#: * `/health` and `/health/ready` are polled by CloudFront and by uptime alerting on a fixed timer.
#:   They are traffic nobody navigated to, arriving at a rate set by a monitoring configuration; at
#:   one probe every few seconds they would be the two most-used "screens" in the product for ever,
#:   putting a machine's timer at the top of the answer to "which screens do designers reach".
#: * The three `/usage` read routes are skipped because recording them would make the dataset partly a
#:   record of itself. Every refresh of a page showing usage figures would add rows to the table those
#:   figures come from, so "requests per day" would rise with how long somebody left the dashboard
#:   open, and in any window containing an analyst the busiest route would be the analyst. That is a
#:   feedback loop, not an observation, and it cannot be corrected for afterwards because the rows are
#:   indistinguishable from real ones.
#:
#: THE COST OF THE SECOND ONE, STATED: this table cannot show that the usage endpoints themselves are
#: slow. `GET /usage/collection` reports the writer's own counters instead, and the server log carries
#: the rest.
UNRECORDED_TEMPLATES: frozenset[str] = (
    frozenset({"/health", "/health/ready"}) | USAGE_READ_TEMPLATES
)


def _as_utc(moment: datetime) -> datetime:
    """A query parameter that can be compared with a stored `timestamptz`. Naive means UTC.

    `?from=2026-08-01T00:00:00` — no offset — is a perfectly ordinary thing for a client to send and
    `datetime.fromisoformat` accepts it, producing a NAIVE datetime. `services/usage` refuses those
    outright, for the right reason: a naive value compared against a `timestamptz` is silently read in
    the server's own timezone, which produces a window that is wrong by hours rather than an error
    anybody notices. Reading a missing offset as UTC is the assumption the rest of this repository
    already makes out loud — `dictation_consent._as_utc` and `dictation_cap.ist_day` both do it — and
    it is stated back to the caller in the `window` block of every response so a client that meant
    something else can see that it did not get it.
    """
    return moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)


def _window(raw_from: datetime, raw_to: datetime) -> tuple[datetime, datetime]:
    """The window both read arms take, refused before any database work rather than inside a query.

    THE RULE IS CHECKED TWICE, ON PURPOSE, AND THE TWO COPIES SAY DIFFERENT THINGS BECAUSE THEY ARE
    ADDRESSED TO DIFFERENT PEOPLE. ``usage._ensure_window`` is the authority and refuses the same two
    conditions for any caller, including one that never came through HTTP; it cannot name ``from`` and
    ``to``, because it does not know a query string exists. This one is written for the person holding
    the URL: it names the parameters they typed and quotes the width they asked for, so the next
    attempt can be right first time instead of by bisection. Every route arm still routes the
    service's own :class:`usage.UsageRuleViolation` to a 400 as well, so tightening the rule THERE
    tightens it here even if this function is never updated — the duplication is a better message,
    never a second source of truth. ``MAX_RANGE_DAYS`` is read from the service rather than retyped,
    so the two cannot disagree about the number.
    """
    since, until = _as_utc(raw_from), _as_utc(raw_to)
    if since >= until:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"'from' must be earlier than 'to'; this request asked for {since.isoformat()} to "
                f"{until.isoformat()}."
            ),
        )
    if until - since > timedelta(days=usage.MAX_RANGE_DAYS):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"A usage window may be at most {usage.MAX_RANGE_DAYS} days; this one is "
                f"{(until - since).days}. Ask for a narrower period, or build a longer report from a "
                f"rollup designed for it — there is deliberately no index that serves a whole-archive "
                f"scan of this table."
            ),
        )
    return since, until


def _window_block(since: datetime, until: datetime) -> dict[str, Any]:
    """The same window description on every response, so the cap is never something a caller has to
    already know. ``maxDays`` is here on the successful answer and not only in the refusal, because a
    bound learned by tripping over it is a bound that gets tripped over."""
    return {
        "from": since,
        "to": until,
        "days": round((until - since).total_seconds() / 86400, 2),
        "maxDays": usage.MAX_RANGE_DAYS,
        # Half-open, so two adjacent windows neither overlap nor drop the row on the boundary.
        "interval": "[from, to)",
        "naiveDatesReadAs": "UTC",
    }


@router.get("/me")
async def my_usage(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """What this platform recorded about the person asking, over a window they choose.

    **THE ONLY ROUTE IN THIS MODULE THAT EMITS A USER ID, AND IT EMITS ONLY THE CALLER'S OWN.** The
    account is taken from the bearer token and never from a parameter, so there is nothing here to
    point at a colleague — which is why this arm needs no gate beyond being signed in. A designer
    asking what was noticed about them is not exercising a privilege.

    IT IS STILL AN AGGREGATE, not a log. It answers "which screens, how often, how fast, how often
    broken" for one account; it does not replay the order somebody moved through the app in, although
    the rows underneath could. That is deliberate — a route that renders one person's afternoon
    minute by minute is a shape somebody will eventually want pointed at a different person, and the
    argument for adding it should have to be made against a module that does not already do it.

    THERE IS NO WITHHOLDING FLOOR HERE, and its absence is not an oversight. A floor exists to stop
    one person being picked out of a group; there is no group to hide in when the subject is the
    reader, and applying one would mean telling somebody that too few people used a screen for them to
    be told about their own use of it.

    A NOTE ON WHAT MAY BE MISSING FROM YOUR OWN ANSWER, AND IT IS NO LONGER "everything".

    **THIS PARAGRAPH USED TO SAY THIS ROUTE REPORTS NOTHING FOR ANYBODY, AND UNTIL 2026-08-30 THAT
    WAS TRUE.** There was no consent flow, `usage.DEFAULT_UNASKED_COLLECTION` recorded every request
    without the identity, and so no row anywhere carried a name for this route to find. The flow now
    exists — `usage.collection_plan` attributes a request whose account has answered GRANTED — so
    the honest statement is narrower and depends on who is asking:

    * **GRANTED** — the rows are attributed and this route reports them, from the moment the answer
      was recorded. A window that begins before that is genuinely empty at the start.
    * **REFUSED** — nothing was kept and whatever had been collected was DELETED when they declined,
      so this is empty and will stay empty. That is what they asked for.
    * **NOBODY HAS ASKED THEM YET** — the requests are recorded without the identity, exactly as
      before, so this is empty and the reason is `DEFAULT_UNASKED_COLLECTION` rather than anything
      about them.

    The response carries `collection` for the reason it always did: an empty list with no sentence
    beside it is read as "you have never used the app", and for two of those three states that is
    the opposite of the truth. `GET /usage/consent` carries the `gate`, whose `reason` names which
    of the three this account is in — and `GET /usage/me/trail`, which is the log rather than the
    aggregate, returns that gate on every response for exactly this purpose.
    """
    since, until = _window(raw_from, raw_to)
    # ``or ""`` rather than a bare ``str(...)``: an account object with no id would otherwise be
    # queried for the literal string "None", which matches nothing and reads as "this person has
    # never used the app". Empty reaches ``usage_for_user``'s own refusal, which says what was
    # missing. It should be unreachable — ``get_current_user`` returns a row read by primary key —
    # and that is exactly why the failure has to be a sentence rather than a plausible empty answer.
    user_id = str(get_value(current_user, "id") or "")
    try:
        report = await usage.usage_for_user(user_id, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "userId": report["userId"],
        "window": _window_block(since, until),
        "requests": report["requests"],
        "routes": report["routes"],
        "collection": _collection_summary(),
        "notes": [
            "Screens are route templates, not features: one template can serve several things a "
            "person would name differently, and one screen can call several templates.",
            "Durations are server time only — no network, no rendering, nothing you actually waited "
            "for.",
        ],
    }


@router.get("/routes")
async def usage_by_route(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(usage.MAX_TEMPLATES_PER_QUERY, ge=1, le=usage.MAX_TEMPLATES_PER_QUERY),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    """Per-screen aggregates across every account: how often, how fast, how often broken.

    **NO USER ID COMES BACK FROM HERE, BY CONSTRUCTION AND NOT BY CONVENTION.**
    `usage.usage_for_routes` computes the distinct-account count inside the service and returns only
    the integer, so this route has no identity to emit even if a later edit tried to.

    **WHY THE ROUTES ARE PAGED FROM THE APPLICATION'S OWN ROUTE TABLE RATHER THAN DISCOVERED FROM THE
    DATA.** "Every route in the window" is a scan across the whole table, and the schema deliberately
    builds no `createdAt`-only index to serve one — it would be paid for on every insert into what
    will be by far the highest-write table here, for a report nobody asked for. So the question is
    turned around: the list of screens comes from the route table this application actually mounted
    (registered at startup by `main._mounted_route_templates`), and each page of it is asked about by
    name, which is a bounded set of index probes on `@@index([routeTemplate, createdAt])`. A screen
    with no traffic in the window comes back with zeroes rather than being absent — which is a real
    answer, and one a data-driven list could not give.

    `?template=` narrows it to named screens instead, for a caller who knows what they are asking
    about. Every value is validated by the service before it reaches a query, so this cannot be used
    as an oracle by probing it with interpolated paths.

    THE TOTALS ARE LABELLED `totalsForThisPage` AND THE NAME IS DOING WORK. They are the sum over the
    routes on this page, excluding every withheld one — not a system total, which this endpoint
    cannot produce and must not appear to. A field called `total` next to a paged list is read as the
    latter by everybody, every time.
    """
    since, until = _window(raw_from, raw_to)

    if template:
        # The caller named the screens. Deduplicated and ordered so the same request is the same
        # page, and left to the service to validate — one rule, one place.
        wanted = sorted({value.strip() for value in template if value and value.strip()})
        source = "requested"
        if not wanted:
            # `?template=` with nothing in it. Refused rather than answered with an empty page,
            # because an empty page is the correct answer to "page 99 of 3" and this is not that:
            # a caller who asked for nothing and was told "no results" would read it as "these
            # screens were never used".
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    "'template' was given but every value was blank. Name at least one route "
                    "template, or omit the parameter to page through every mounted route."
                ),
            )
    else:
        # The allow-list installed at startup, minus what is never recorded: a row that is
        # structurally always zero reads as "nobody uses this screen" rather than as "this screen is
        # not measured", and the two are opposite facts.
        wanted = sorted(usage.known_templates() - UNRECORDED_TEMPLATES)
        source = "mounted"

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    window = wanted[skip : skip + clean_size]

    routes: list[dict[str, Any]] = []
    if window:
        try:
            report = await usage.usage_for_routes(window, since, until)
        except usage.UsageRuleViolation as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
        routes = report["routes"]

    payload = page_payload(routes, len(wanted), clean_page, clean_size)
    payload["window"] = _window_block(since, until)
    payload["routeSource"] = source
    payload["limits"] = {
        "maxWindowDays": usage.MAX_RANGE_DAYS,
        "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
        "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
    }
    payload["totalsForThisPage"] = _page_totals(routes)
    payload["notMeasured"] = sorted(UNRECORDED_TEMPLATES)
    payload["notes"] = [
        (
            f"A screen used by fewer than {usage.MIN_IDENTIFIED_USERS_FOR_ROUTE} identified accounts "
            f"in this window is withheld: every figure is null and 'withheld' is true. That is a "
            f"refusal, not a zero — null becomes 0 through arithmetic and through ??, so branch on "
            f"'withheld' rather than falling back."
        ),
        (
            "A screen with no identified accounts at all is reported in full. Sign-in traffic is "
            "almost entirely unauthenticated, and 'the sign-in page is slow for the people who "
            "cannot get in' is one of the things this record exists to be able to show."
        ),
        (
            "'totalsForThisPage' is the sum over the routes on this page only, withheld ones "
            "excluded. It is not a total for the platform, and no arm of this API produces one."
        ),
        (
            "Durations are server time: from the middleware entering to the response being finished. "
            "No network, no rendering. A screen that looks fast here can still be a spinner in a "
            "courtyard on a 2G connection."
        ),
    ]
    return payload


def _page_totals(routes: list[dict[str, Any]]) -> dict[str, Any]:
    """Sum the page, skipping the withheld. Withheld entries carry `None` in every metric, so adding
    them would raise; counting them as zero would be worse — it would fold a refusal into an answer
    and report a smaller number as if it were the truth. So they are excluded and COUNTED, and the
    count rides in the result where a reader can see how much of the page it is missing."""
    counted = [entry for entry in routes if not entry.get("withheld")]
    return {
        "routes": len(counted),
        "routesWithheld": len(routes) - len(counted),
        "requests": sum(int(entry.get("requests") or 0) for entry in counted),
        "ok": sum(int(entry.get("ok") or 0) for entry in counted),
        "clientErrors": sum(int(entry.get("clientErrors") or 0) for entry in counted),
        "serverErrors": sum(int(entry.get("serverErrors") or 0) for entry in counted),
    }


def _collection_summary() -> dict[str, Any]:
    """The three sentences every response should be read next to. Kept in one function so the
    self-read and the method endpoint cannot describe the same deployment differently."""
    plan = usage.collection_plan(usage.UsageConsent.NOT_RECORDED)
    return {
        "unaskedPolicy": usage.DEFAULT_UNASKED_COLLECTION.value,
        "attributesUnaskedRequests": plan.attribute,
        "explanation": plan.reason,
    }


#: What this deployment actually records, COMPUTED from the policy rather than asserted.
#:
#: **THE FUNCTION MOVED INTO ``services/usage.py`` ON 2026-08-30 AND THE MOVE IS THE POINT.** These
#: sentences are now read by two callers at two different gates: ``GET /usage/collection`` behind
#: ``require_usage_reader``, and ``GET /usage/consent/notice`` behind NOTHING AT ALL — because a
#: person deciding whether to agree has not agreed yet, and on the web sign-in screen has no token
#: either. One list, two gates. Writing the copy a second time in TSX and a third in Kotlin is how
#: two clients come to describe one decision differently, and a consent notice that does not match
#: the collection is not a smaller kind of correct — it is a consent to something else.
#:
#: The alias is kept so the name a reader of this file greps for still resolves here. See
#: ``usage.collects`` for the whole argument, including the one it was rewritten to end: the
#: account-id line used to be a CONSTANT claiming attribution follows consent, which is true of
#: exactly one of the three policies this module ships.
_collects = usage.collects


@router.get("/collection")
async def collection_method(_: Any = Depends(require_usage_reader)) -> dict[str, Any]:
    """How this record was made, machine-readable, so a figure and its method can be quoted together.

    **THIS IS THE HALF OF REQUIREMENT 26 THAT A PROSE DOCUMENT CANNOT DO.**
    `docs/METHODOLOGY-usage-instrumentation.md` explains the design; this reports the state of THIS
    deployment right now — which consent policy is actually in force, what the caps actually are, and,
    critically, **how many observations this process has lost**. A dataset that loses rows without
    saying how many is a dataset nobody can check, and a methodology section that describes an
    intended design rather than the running one is how a paper ends up reporting a number its own
    system never produced.

    ONE PROCESS, AND IT SAYS SO. The counters below are this worker's, because the buffer is
    per-process by design (a shared advisory lock would elect one flusher and strand every other
    worker's rows). Deployment runs a single worker today; on more than one, these figures describe
    the process that answered this request and not the fleet, which `losses.scope` states in words
    rather than leaving a reader to assume the number of workers is one.

    IT DELIBERATELY REPORTS NO NUMBERS ABOUT PEOPLE. Nothing here is a count of accounts, requests or
    routes — it is the method, the caps and the losses. That is why it can sit behind the same gate as
    the aggregates without widening what that gate hands over.
    """
    stats = usage.buffer_stats()
    return {
        # Derived from the policy in force, not written out here — see `_collects` for the two
        # one-line policy changes that would otherwise have left this list describing a deployment
        # that is not the one answering the request.
        "collects": _collects(),
        "doesNotCollect": usage.does_not_collect(),
        "notMeasured": sorted(UNRECORDED_TEMPLATES),
        "consent": {
            "unaskedPolicy": usage.DEFAULT_UNASKED_COLLECTION.value,
            "options": [option.value for option in usage.UnaskedCollection],
            # TRUE SINCE 2026-08-30. It was False for as long as there was no column, no route and
            # no screen, and a reader who quoted this endpoint in a methods section during that
            # period was quoting an honest No.
            "flowExists": True,
            "noticeVersion": usage.NOTICE_VERSION,
            "bases": [option.value for option in usage.UsageConsentBasis],
            "askedAt": (
                "At sign-in, on both clients and on both credentials, and again whenever the notice "
                "version moves. Agreeing is a CONDITION OF ACCESS, which means a grant collected "
                "there is not freely given — so the circumstance is stored beside the answer as "
                "REQUIRED_AT_SIGN_IN, and every withdrawal is stored as OFFERED_IN_SETTINGS. A "
                "system that recorded a turnstile as a free choice would be forging the one "
                "distinction this vocabulary exists to preserve."
            ),
            "withdrawalCosts": (
                "Nothing. Withdrawing does not sign anybody out and removes no capability. That "
                "asymmetry is what makes the condition of access above defensible rather than "
                "merely documented: an agreement that cannot be taken back is not an agreement."
            ),
            "explanation": _collection_summary()["explanation"],
            "consentStateWritten": (
                "NULL on every row written so far. NULL means NOBODY WAS ASKED, which is the only "
                "thing that makes these rows findable and deletable on the day somebody decides "
                "they should be. The token GRANTED is written in exactly one circumstance: an "
                "account whose recorded answer is GRANTED. Nothing backfills the earlier NULLs, and "
                "whether those rows are deleted is an open decision recorded in the document below."
            ),
            "refusalCost": (
                "A refusing account is not recorded at all, not even anonymously. Every aggregate "
                "therefore describes everyone who did not refuse, and anybody reporting these "
                "figures has to say so."
            ),
            "document": "docs/DECISION-usage-consent-at-sign-in.md",
            "priorDocument": "docs/DECISION-usage-consent-default.md",
        },
        "readableBy": usage.readable_by(),
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
            "maxTimelineBuckets": usage.MAX_TIMELINE_BUCKETS,
            "maxTrailRows": usage.MAX_TRAIL_ROWS,
            "rowsPerWrite": usage.FLUSH_ROWS,
            "flushIntervalSeconds": usage.FLUSH_INTERVAL_SECONDS,
            "bufferCeiling": usage.BUFFER_CEILING,
        },
        "losses": {
            # Scoped rather than counted. A process cannot honestly report how many siblings it has
            # — asking the operating system would answer for this box and not for the deployment —
            # so the sentence states what these numbers cover and leaves the reader to know how many
            # boxes they run.
            # CORRECTED 2026-08-30: the sentence said "Deployment runs a single uvicorn worker
            # today", which is true of ONE PROCESS and false of the DEPLOYMENT. Every target runs
            # `--workers 1` (Dockerfile, `infra/terraform/user_data.sh`, `deployment-api.yaml`) —
            # that is a rule about not putting a supervisor in front of uvicorn, not a statement
            # about how many boxes there are — and `infra/k8s/overlays/prod/kustomization.yaml`
            # sets `replicas: 2` with an HPA above it. So a reader quoting `droppedAtCeiling` from
            # this endpoint as a deployment-wide loss figure was understating it by a factor of the
            # replica count, on the one endpoint that exists so figures can be quoted honestly.
            "scope": (
                "this worker process, since it started, and NOT the deployment. Every process runs "
                "uvicorn with --workers 1, but the deployment runs more than one of them (two "
                "replicas in production, and an autoscaler above that), and each keeps its own "
                "buffer and its own counters. These numbers describe the single process that "
                "answered this request; there is deliberately no fleet total, because a process "
                "cannot honestly report how many siblings it has."
            ),
            "buffered": stats["buffered"],
            "written": stats["written"],
            "droppedAtCeiling": stats["dropped"],
            "abandonedAfterFailedWrites": stats["abandoned"],
            "failedFlushes": stats["failedFlushes"],
            "explanation": (
                "Rows are buffered in memory and written in batches. The two counters above are "
                "different failures and must not be added together or read for one another. "
                "'abandonedAfterFailedWrites' is the one that moves when the DATABASE is away: a "
                "batch is offered twice and then written off, so about five seconds of "
                "unavailability is survivable and everything past that is lost at the rate the "
                "writer drains it — the buffer does not fill up and wait. 'droppedAtCeiling' is the "
                "one that moves when this PROCESS is producing faster than it can write: the buffer "
                "reaches its 5,000-row ceiling and the OLDEST rows go, because the newest describe "
                "the trouble and are what anybody will look at. Every loss of either kind is "
                "counted here rather than being silent."
            ),
        },
        "knownLimitations": [
            "Server duration is not user-perceived latency: no network, no rendering, no time on a "
            "handset. A fast number here is compatible with a slow experience in the field.",
            "A route template is not a feature. One template serves several things a person would "
            "name differently, and one screen calls several templates.",
            "A count of accounts is a floor and never a headcount, and since 2026-08-30 it is no "
            "longer zero — which is the more dangerous state, because while every row carried a "
            "NULL userId a per-person claim could not be made by accident. Attributed rows now "
            "exist for consenting accounts, so a distinct-id count returns a real number; it "
            "counts people who were asked, agreed, and then made a request, and it silently "
            "excludes every unauthenticated request, every row written before the flow shipped, "
            "everyone who has not yet answered (recorded without their identity) and everyone who "
            "refused (not recorded at all).",
            "The record starts on the day it was deployed. There is no history before it and none "
            "can be reconstructed.",
            "Only requests that reached this API are here. Anything served from a cache, an offline "
            "draft or a client-side navigation is invisible to it.",
            "The '<unmatched>' template means 'no route name was available', which is a 404 in "
            "production and, where BACKEND_EXPOSE_DOCS is on, also FastAPI's own documentation "
            "pages: /docs, /openapi.json and /redoc are plain Starlette routes, so they are served "
            "without the router ever naming them. They are deliberately absent from "
            "'/usage/routes' rather than listed there reporting a permanent zero.",
            "Rows written before 2026-08-30 carry consentState NULL because nobody had been asked, "
            "and nothing backfills them. Any figure spanning that date mixes rows collected under "
            "an agreement with rows collected before there was one to give, and the two are told "
            "apart by that column and by nothing else.",
            "A person's own trail can be read back by them and by the master admin, and it is "
            "DELETED outright when they withdraw. So a trail is not a stable dataset: a figure "
            "computed from attributed rows in March is not reproducible in April if somebody "
            "withdrew in between, and no record of what was removed survives beyond the dated "
            "decision in their consent log.",
        ],
        "retention": usage.retention_note(),
        "document": "docs/METHODOLOGY-usage-instrumentation.md",
    }


# --------------------------------------------------------------------------------------
# CONSENT: the notice, the answer, and taking it back
#
# The bodies are declared HERE rather than in `app/schemas/`, on `review.MediaReviewEdit`'s
# precedent: the shape belongs to exactly one endpoint pair, it is four fields, and putting it in a
# shared schema module would separate the field list from the paragraph explaining why each field
# exists. `APIModel` all the same, so `extra="forbid"` refuses a client that invents a field and a
# typo becomes a 422 rather than a silently dropped value — which, on a consent, would mean an
# answer recorded under a basis or a version nobody sent.
# --------------------------------------------------------------------------------------


class UsageConsentIn(APIModel):
    """One account's answer about being observed.

    ``decision`` — GRANTED or REFUSED. NOT_RECORDED is refused with a sentence: it is the absence of
    an answer, not one somebody can give.

    ``basis`` — REQUIRED_AT_SIGN_IN or OFFERED_IN_SETTINGS. **NOT DEFAULTED, AND THAT IS THE WHOLE
    POINT OF THE FIELD.** A default would be guessed by this server about a circumstance only the
    client knows, and the circumstance is precisely what separates a turnstile from a free choice.
    The client that put the checkbox in front of a person is the only thing that can say which screen
    it was on.

    ``noticeVersion`` — what the client actually showed. Required, and stored verbatim; see
    ``usage.consent_decision_plans`` for why an unrecognised version is accepted rather than refused.

    ``recordedAt`` — when the box was ticked, as the client's clock reported it. Optional: null means
    the answer was given straight against this server, where the log row's ``createdAt`` is the same
    moment and a copy would later read as "a device reported this", which would be false.
    """

    decision: str
    basis: str
    noticeVersion: str = Field(min_length=1, max_length=64)
    recordedAt: datetime | None = None
    note: str | None = Field(default=None, max_length=500)


class UsageConsentWithdrawIn(APIModel):
    """A withdrawal. Deliberately NOT a `UsageConsentIn` with `decision="REFUSED"` typed by hand.

    No ``decision`` field, because there is only one thing this endpoint does; and no ``basis``,
    because a withdrawal is by construction OFFERED_IN_SETTINGS — the turnstile is at the door and
    nobody withdraws at a door they are trying to get through. Both are supplied by the route, which
    means a client cannot file a withdrawal as though it had been demanded of somebody.
    """

    noticeVersion: str = Field(min_length=1, max_length=64)
    recordedAt: datetime | None = None
    note: str | None = Field(default=None, max_length=500)


def _consent_enum(raw: str, kind: type, *, what: str) -> Any:
    """One token from the wire into one of the two consent enums, or a 422 naming the alternatives.

    A 422 and not a 400, matching ``record_dictation_consent``: everything refusable here is a
    statement about the BODY rather than about the account's state.
    """
    try:
        return kind(str(raw or "").strip().upper())
    except ValueError as exc:
        allowed = ", ".join(member.value for member in kind)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{raw!r} is not one of the {what} this server records. Send one of: {allowed}.",
        ) from exc


@router.get("/consent/notice")
async def consent_notice() -> dict[str, Any]:
    """What a person is agreeing to, versioned. **UNGATED, AND THAT IS DELIBERATE.**

    THE ONLY ROUTE IN THIS MODULE WITH NO DEPENDENCY AT ALL. It is read on a sign-in screen by
    somebody who has no token — on the web the checkbox sits above the credential form, and the whole
    point of the gate is that the person has not agreed yet — so any gate here would mean the only
    way to see what you are agreeing to is to agree first. That is not a consent flow, it is a
    signature on a folded page.

    IT CARRIES NO FIGURES ABOUT ANYBODY, which is what makes that safe. Every field is the published
    method: what is collected (computed from the policy in force, not asserted), what is deliberately
    not, that agreeing is required, what a duration is not, who may read what, what a withdrawal
    does, and the retention answer — which is that there is not one. ``GET /usage/collection``
    reports the same policy behind ``require_usage_reader`` and adds the deployment's loss counters,
    which is the part that is not a person's business.

    **ONE SOURCE, AND IT IS THIS SERVER.** Two sign-in screens and two settings cards render from
    this payload. The alternative — the copy written once in TSX and again in Kotlin — is how one
    decision comes to be described two ways, and here that would not be an inconsistency but two
    different consents.
    """
    return usage.consent_notice()


@router.get("/consent")
async def my_consent(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """This account's own answer, its history, and whether it must be asked again.

    ``get_current_user`` AND NOTHING MORE, on ``/usage/me``'s argument: reading your own answer about
    your own data needs permission from nobody, and routing it through an admin gate would mean
    asking an administrator what you had agreed to.

    ``gate`` IS THE FIELD A CLIENT RENDERS FROM, and it is computed here so that no client computes
    it. "Have they agreed, and to the current text" is two facts and one answer; the moment the web
    and the handset each derive that answer for themselves, the two disagree on the first deploy that
    bumps the notice version and only one of them is updated.

    THE HISTORY COMES BACK WITH IT, and that is what stops a client rendering this as a checkbox —
    ``accept_ai_layer``'s argument one feature over. A consent that shows only its current value
    invites a toggle; a consent that shows "granted on the 3rd at sign-in, withdrawn on the 9th in
    settings" is visibly a record of decisions.
    """
    user_id = str(get_value(current_user, "id") or "")
    history = await usage.consent_history(user_id)
    return {
        "userId": user_id,
        "consent": usage.consent_record(current_user),
        "gate": usage.consent_gate(current_user),
        "notice": usage.consent_notice(),
        "decisions": [usage.consent_decision_payload(row) for row in history],
    }


async def _record_consent(
    *,
    current_user: Any,
    decision: usage.UsageConsent,
    basis: usage.UsageConsentBasis,
    notice_version: str,
    recorded_at: datetime | None,
    note: str | None,
) -> dict[str, Any]:
    """The body both consent writes share, so the two doors cannot come to behave differently.

    **THE CACHE INVALIDATION IS THE LINE THAT WOULD BE MISSED, AND IT IS WHY THIS IS ONE FUNCTION.**
    ``deps`` caches the authenticated ``User`` row for five seconds so that ``get_current_user`` does
    not pay a cross-region round trip per request. ``resolve_consent`` reads the consent OFF THAT
    CACHED ROW. Without ``invalidate_cached_user`` here, a person who agrees goes on being recorded
    anonymously for up to five seconds, and — far worse — a person who WITHDRAWS goes on being
    recorded for up to five seconds after asking not to be, with `withdraw()` having already deleted
    the rows that existed when they asked. Five seconds of a request-by-request trail written after
    somebody said stop is exactly the failure the buffer purge exists to prevent, arriving through
    the one door the purge cannot see.
    """
    user_id = str(get_value(current_user, "id") or "")
    try:
        outcome = await usage.record_consent(
            user_id=user_id,
            decision=decision,
            basis=basis,
            notice_version=notice_version,
            recorded_at=recorded_at,
            note=note,
        )
    except usage.UsageRuleViolation as exc:
        # 422 and never 409: everything this can refuse is a statement about the BODY — a decision
        # nobody can record, a missing version, a clock in the future — rather than about the state
        # of the account, which `get_current_user` has already settled.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    invalidate_cached_user(user_id)
    # Re-read rather than reusing the row `record_consent` returned. The update's return value is
    # correct today, and re-reading is what keeps this response identical to what the NEXT request
    # will see through the (now cold) cache — so a client that renders from this payload and a client
    # that re-fetches `/me` cannot disagree about whether the answer landed.
    account = await db.user.find_unique(where={"id": user_id})
    subject = account if account is not None else current_user

    body: dict[str, Any] = {
        "userId": user_id,
        "consent": usage.consent_record(subject),
        "gate": usage.consent_gate(subject),
        "decisions": [
            usage.consent_decision_payload(row) for row in await usage.consent_history(user_id)
        ],
    }
    if outcome.withdrawal is not None:
        # WHAT THE WITHDRAWAL ACTUALLY REACHED, returned rather than assumed. A person who asks for
        # their record to be deleted is entitled to be told whether it was — and `withdraw()` never
        # raises, so a failed delete would otherwise be indistinguishable from a successful one from
        # out here. `storedDeleteRan: false` means collection has stopped and the deletion has not
        # happened; the sentence says so rather than leaving a zero to be read as "there was nothing
        # to delete".
        body["withdrawal"] = {
            "bufferedDropped": outcome.withdrawal.buffered_dropped,
            "storedDeleted": outcome.withdrawal.stored_deleted,
            "storedDeleteRan": outcome.withdrawal.stored_delete_ran,
            "explanation": (
                "Recording has stopped for this account, anything observed and not yet written has "
                "been thrown away, and the rows already stored were deleted."
                if outcome.withdrawal.stored_delete_ran
                else (
                    "Recording has stopped for this account and anything not yet written has been "
                    "thrown away, but the rows already stored could NOT be deleted — the database "
                    "refused the delete. Nothing further is being collected. Ask an administrator "
                    "to re-run the withdrawal; repeating it is safe and is what completes the "
                    "deletion."
                )
            ),
        }
    return body


@router.post("/consent")
async def record_my_consent(
    payload: UsageConsentIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Record this account's own answer about being observed. GRANTED or REFUSED.

    ── WHAT THIS FOLLOWS ───────────────────────────────────────────────────────────────────────

    ``POST /design-workshops/{id}/dictation-consent``, and the correspondence is close enough to be
    worth reading side by side. **ITS OWN ROUTE AND NEVER A PATCH**: a value whose entire point is
    who set it, when, under what circumstances and to which text cannot ride a generic field-copy
    loop, which would record none of the four. **TWO WRITES AND BOTH COME BACK**: the columns are the
    current answer the recorder reads on every request, the log row is the history, and returning the
    log is what stops a client rendering this as a checkbox. **REFUSED IS HOW AN AGREEMENT IS
    WITHDRAWN** — another decision, another log row, and the gate closes on the next request; there
    is deliberately no route that un-records an answer back to NOT_RECORDED, because a gate cannot
    tell a withdrawal from an account nobody has opened if the two are stored the same way.
    **``recordedAt`` IS THE COURTYARD MOMENT**, and a time in the future is refused rather than
    corrected.

    ── AND THE ONE THING IT DOES THAT THE AUDIO ROUTE HAS NO NEED OF ───────────────────────────

    A REFUSED decision here reaches the data ALREADY COLLECTED, exactly as a REFUSED decision there
    reaches the recordings already queued — *"a consent that cannot recall what it already authorised
    is a preference, not a permission."* There it cancels pending transcriptions; here
    ``usage.withdraw`` empties this process's buffer and DELETES the account's stored rows. Guarded
    to REFUSED in the service and for the same reason the audio guard exists: run on a grant, it
    would destroy the record the grant was given in order to keep.

    ── WHO MAY CALL IT ─────────────────────────────────────────────────────────────────────────

    Anybody, about themselves, and nobody about anybody else. There is no ``userId`` in the path or
    the body; the account comes from the bearer token. **A consent an administrator can enter on a
    colleague's behalf is not a consent**, which is why no admin-facing spelling of this exists and
    why adding one would need its own argument rather than a parameter.
    """
    decision = _consent_enum(payload.decision, usage.UsageConsent, what="answers")
    basis = _consent_enum(payload.basis, usage.UsageConsentBasis, what="circumstances")
    return await _record_consent(
        current_user=current_user,
        decision=decision,
        basis=basis,
        notice_version=payload.noticeVersion,
        recorded_at=payload.recordedAt,
        note=payload.note,
    )


@router.post("/consent/withdraw")
async def withdraw_my_consent(
    payload: UsageConsentWithdrawIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Take it back: stop recording this account, and delete what has already been recorded.

    **A NAMED DOOR RATHER THAN A PARAMETER, ALTHOUGH IT IS THE SAME ACT AS
    ``POST /usage/consent`` WITH REFUSED.** Both funnel through one function, so the two can never
    diverge; what this spelling buys is that the most consequential operation in the feature has a
    URL that says what it does. A client author reading the route list finds it; a client author
    reading a ``decision`` enum finds a value.

    IT SUPPLIES THE BASIS ITSELF, and never takes one from the client. Every withdrawal is
    ``OFFERED_IN_SETTINGS`` by construction — nobody withdraws at a door they are trying to get
    through — so there is no way for a client to file a withdrawal as though it had been demanded of
    somebody. That matters because the basis is the column that separates the turnstile from the free
    choice, and the free choices are the half that makes the turnstile defensible.

    **IT COSTS THE PERSON NOTHING, AND THAT IS THE POINT OF THE WHOLE DESIGN.** No sign-out, no lost
    capability, no re-consent demanded on the next request: ``consent_gate`` reports
    ``required: false`` for a REFUSED account precisely so a client does not put the question back in
    front of somebody who has just answered it. The gate at sign-in makes agreeing a condition of
    access, which is not free consent; this route is what a person retains, and an agreement that
    cannot be taken back without losing access is not an agreement.

    WHAT IT DOES, in the order it does it: writes REFUSED and a dated log row; stops recording in
    this process immediately; throws away anything observed and not yet written; DELETES the rows
    already stored. What it does NOT do is erase the dated decisions — a withdrawal must not rewrite
    the answer the earlier collection was actually made under, which is exactly why the log exists.
    """
    return await _record_consent(
        current_user=current_user,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=payload.noticeVersion,
        recorded_at=payload.recordedAt,
        note=payload.note,
    )


# --------------------------------------------------------------------------------------
# The richer aggregates
#
# Four routes, all `require_usage_reader`, all reading over a NAMED, CAPPED set of route templates
# and none of them with an "every route" spelling — see `_requested_templates`, which is the one
# place that rule is enforced.
# --------------------------------------------------------------------------------------


def _requested_templates(template: list[str] | None) -> list[str]:
    """The screens an aggregate is being asked about: named by the caller, or the mounted list.

    **THERE IS NO "EVERY ROUTE" ANSWER AND THE CAP IS NOT NEGOTIABLE.** Every one of these
    statements is `routeTemplate IN (...)` plus a date range — a bounded set of probes on
    `@@index([routeTemplate, createdAt])`. Drop the IN and it is a whole-window scan across every
    user and every route, which the schema deliberately builds no index for because it "would be
    paid for on every insert into what will be by far the highest-write table in this schema".

    So a caller who names nothing gets the FIRST `MAX_TEMPLATES_PER_QUERY` mounted templates, in
    sorted order, minus what is never recorded — and every response says which set it used and how
    many were left out, rather than presenting a slice as the whole. `GET /usage/routes` pages the
    same list, which is how a caller walks all of it.

    THE NEVER-RECORDED TEMPLATES ARE EXCLUDED HERE AND NOT LEFT TO REPORT ZERO: a row that is
    structurally always zero reads as "nobody uses this screen" rather than as "this screen is not
    measured", and the two are opposite facts.
    """
    if template:
        wanted = sorted({value.strip() for value in template if value and value.strip()})
        if not wanted:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    "'template' was given but every value was blank. Name at least one route "
                    "template, or omit the parameter to report on the first "
                    f"{usage.MAX_TEMPLATES_PER_QUERY} mounted routes."
                ),
            )
        if len(wanted) > usage.MAX_TEMPLATES_PER_QUERY:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    f"At most {usage.MAX_TEMPLATES_PER_QUERY} route templates may be named in one "
                    f"request; this one named {len(wanted)}. That bound is an index strategy rather "
                    f"than a preference: the IN list becomes a bitmap of index probes, which is "
                    f"cheap while it is short."
                ),
            )
        return wanted
    mounted = sorted(usage.known_templates() - UNRECORDED_TEMPLATES)[
        : usage.MAX_TEMPLATES_PER_QUERY
    ]
    if not mounted:
        # THE ALLOW-LIST IS EMPTY, which in production means the startup registration did not run
        # or refused the whole route table (`main._mounted_route_templates` logs at ERROR when it
        # cannot vouch for the list). Refused with the cause named rather than answered as "these
        # screens were never used", which is what an empty page would be read as — and which is the
        # opposite of the truth, since with no allow-list the recorder is still writing rows.
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                "This deployment has no registered route table, so there is no default set of "
                "screens to report on. Name the screens with 'template', or look at the startup log "
                "for why the usage allow-list was not installed."
            ),
        )
    return mounted


def _template_block(wanted: list[str], named: bool) -> dict[str, Any]:
    """The same "which screens is this about" description on every new aggregate, so a slice can
    never be mistaken for the whole. `left out` is a COUNT and not a list: naming the omitted
    templates would double the payload to repeat something `GET /usage/routes` already pages."""
    mounted = usage.known_templates() - UNRECORDED_TEMPLATES
    return {
        "templates": wanted,
        "source": "requested" if named else "mounted",
        "count": len(wanted),
        "mountedTotal": len(mounted),
        "notIncluded": max(0, len(mounted) - len(wanted)) if not named else 0,
        "maxPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
    }


_WITHHELD_NOTE = (
    "Every figure here is withheld — null, with 'withheld' true — wherever fewer than "
    "{floor} identified accounts are behind it. That is a REFUSAL and never a zero: null becomes 0 "
    "through arithmetic and through ??, so branch on 'withheld' rather than falling back. A chart is "
    "the sharp case, because a plotted zero looks like a measurement while a gap looks like a gap."
)

_SERVER_TIME_NOTE = (
    "Durations are server time: from the middleware entering to the response being finished. No "
    "network, no rendering. A screen that looks fast here can still be a spinner in a courtyard on a "
    "2G connection."
)


@router.get("/timeline")
async def usage_over_time(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    bucket: str = Query("day"),
    template: list[str] | None = Query(None),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    """Requests and error rate over time, bucketed by day or hour. Two of the metrics in one series.

    **THE ERROR RATE IS IN THE SAME SERIES AS THE TRAFFIC AND MUST NOT BE DRAWN ON THE SAME AXIS.**
    They are returned together because they come from one statement over one index range, and
    separating the request would pay a second round trip to recompute what the first already had.
    A client that renders them as a dual-axis overlay is drawing two unrelated scales into one
    picture, which is the first thing this environment's charting standard refuses.

    AN EMPTY BUCKET IS A ZERO, NOT A GAP. A missing point is read as "no data here"; a zero is read
    as "nothing happened here", and only the second is true of an hour this API was awake for. But a
    WITHHELD bucket is a gap and never a zero — see the note in the response, and note that the two
    look identical to a chart that treats null as 0.

    ``errorRate`` IS NULL WHERE THERE WERE NO REQUESTS, deliberately: 0/0 is not "nothing went
    wrong", it is "nothing happened", and a line drawn through it puts a reassuring flat zero across
    every night and every outage in which this API answered nothing at all.

    THE BUCKET COUNT IS CAPPED AND THE CAP IS STATED. Hourly over a year is 8,784 rows describing an
    index range that touched the whole year; it is refused with the arithmetic in the sentence rather
    than truncated, because a truncated series looks exactly like a period in which nothing happened.
    """
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_timeline(wanted, since, until, bucket=bucket)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "window": _window_block(since, until),
        "bucket": report["bucket"],
        # Named "scope" on all four new aggregates, deliberately: they answer about a NAMED, CAPPED
        # set of screens and never about the platform, and one key spelled one way is how a client
        # author reads that off any of them without checking.
        "scope": _template_block(wanted, bool(template)),
        "series": report["series"],
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxBuckets": usage.MAX_TIMELINE_BUCKETS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            "A bucket with no traffic is reported as zero rather than omitted. A bucket that is "
            "WITHHELD is a different fact and carries nulls — do not draw the two the same way.",
            "'errorRate' is null where there were no requests. It is the share of requests that "
            "answered 4xx or 5xx, between 0 and 1.",
            "Buckets are UTC calendar hours or days, labelled by the moment they start. They are "
            "not local-time days, and a report that straddles a timezone will not agree with one "
            "computed in that timezone.",
            _SERVER_TIME_NOTE,
        ],
    }


@router.get("/latency")
async def usage_latency(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    """Median and tail latency per screen: p50, p95, p99. **The numbers no other route can produce.**

    THE AVERAGES REPORTED EVERYWHERE ELSE IN THIS MODULE CANNOT PRODUCE A PERCENTILE, and this is
    not a matter of effort. `avgDurationMs` is a count-weighted mean of per-group means — exact as a
    mean, and carrying no information about a tail. A screen averaging 120 ms with a p95 of four
    seconds is broken for one request in twenty and looks healthy in `GET /usage/routes`. The
    percentile has to be computed over the raw column, which is why this is its own statement.

    P95 IS THE ONE TO READ. p50 says what an ordinary request costs; p99 is often one request and one
    cold cache. p95 is where a screen stops working for people without being slow enough for anybody
    to file a bug, which is the failure this whole feature was asked to be able to see.

    STILL SERVER TIME. A p99 here is the ninety-ninth percentile of what this API took, not of what
    anybody waited for.
    """
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_latency(wanted, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "window": _window_block(since, until),
        "scope": _template_block(wanted, bool(template)),
        "percentiles": report["percentiles"],
        "routes": report["routes"],
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            "A screen with no traffic in the window reports null percentiles and 'withheld' false. "
            "That is 'there is no distribution', which is a different fact from 'the distribution "
            "is not being shown' — the two must not render alike.",
            "Percentiles are computed over the raw durations in the window and are NOT derivable "
            "from the averages reported by /usage/routes. Do not reconstruct one from the other.",
            _SERVER_TIME_NOTE,
        ],
    }


@router.get("/clients")
async def usage_by_client(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    """The web / Android / api split. **Honest, and currently almost entirely 'api'.**

    THE COLUMN HAS ALWAYS EXISTED AND NOTHING SENDS THE HEADER. `clientApp` is written on every row
    from `x-client-app`, normalised in one place to web/android/api, and as of 2026-08-30 neither
    `frontend/lib/api.ts` nor the Android network layer sends it — so every row records the `api`
    fallback, which is honest: an unlabelled client IS an unknown client.

    **THIS ENDPOINT SHIPS ANYWAY, AND THAT IS THE ARGUMENT.** The schema's reason for the column is
    that "how do they navigate" has different answers on a laptop and on a handset that runs offline
    for a fortnight at a time, and that without it the two are averaged into a designer who does not
    exist. An endpoint reporting 100% `api` makes that gap visible to the people who can close it —
    two header lines, one in each client — while a missing endpoint makes it invisible and leaves the
    averaged figure to be quoted as though it described somebody.

    EVERY KNOWN CLIENT IS EMITTED WHETHER OR NOT IT APPEARS, so a client with no traffic reads as a
    zero rather than as an absence, and so the shape of the answer does not change on the day a
    client starts sending the header.
    """
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_clients(wanted, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    unlabelled = next(
        (
            row
            for row in report["clients"]
            if row["clientApp"] == usage.DEFAULT_CLIENT_APP and (row.get("requests") or 0) > 0
        ),
        None,
    )
    return {
        "window": _window_block(since, until),
        "scope": _template_block(wanted, bool(template)),
        "clients": report["clients"],
        "header": usage.CLIENT_APP_HEADER,
        "known": sorted(usage.CLIENT_APPS),
        "fallback": usage.DEFAULT_CLIENT_APP,
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            (
                f"'{usage.DEFAULT_CLIENT_APP}' is what a request that did not send the "
                f"'{usage.CLIENT_APP_HEADER}' header records as, NOT a separate kind of client. "
                "Neither the web nor the Android layer sends that header yet, so traffic filed "
                "under it is web and Android traffic that could not say which it was."
            )
            if unlabelled is not None
            else (
                f"'{usage.DEFAULT_CLIENT_APP}' is what a request that did not send the "
                f"'{usage.CLIENT_APP_HEADER}' header records as, NOT a separate kind of client."
            ),
            "Counts of people cannot be added across clients: one person using both the web and the "
            "handset is identified in both rows, so summing 'identifiedUsers' counts them twice.",
            _SERVER_TIME_NOTE,
        ],
    }


@router.get("/screens")
async def busiest_and_slowest_screens(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    limit: int = Query(10, ge=1, le=usage.MAX_TEMPLATES_PER_QUERY),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    """The busiest screens and the slowest ones, ranked here so no client ranks them wrongly.

    **THE RANKING IS THE FEATURE, AND IT IS ON THE SERVER FOR ONE SPECIFIC REASON.**
    `GET /usage/routes` already returns everything needed to sort — so this route earns its place
    only by doing the sort correctly, and the correct sort is the one thing a client is most likely
    to get wrong. A withheld route carries `null` in every metric; `null` sorts as 0 through
    JavaScript's comparator and through Kotlin's `?: 0`, so a naive "slowest first" ranking puts
    every screen the server REFUSED to report at the fast end of the list and a naive "busiest"
    ranking buries them at the bottom. Either way a refusal is rendered as a measurement. Withheld
    routes are excluded from both orderings HERE and reported separately, with a count.

    **IT RANKS A NAMED, CAPPED SET AND SAYS SO — IT DOES NOT DISCOVER THE SET FROM THE DATA.**
    "The busiest screen in the product" would be a `GROUP BY routeTemplate` with no template filter,
    which is a scan of the whole window; the schema refuses to build the `createdAt`-only index that
    would serve one, and adding a query no index can serve is exactly what this wave was told not to
    do. So the scope is the templates the caller named, or the first `MAX_TEMPLATES_PER_QUERY`
    mounted ones, and `scope.notIncluded` says how many screens are outside the answer. A caller who
    wants the whole ranking walks `GET /usage/routes`' pages and sorts what comes back — with the
    same null rule, which is why this route's own note states it.

    SLOWEST IS RANKED ON THE MEAN, NOT ON THE TAIL, and that is a limitation rather than a choice:
    it reuses the one statement `/usage/routes` already pays for. `GET /usage/latency` is the honest
    answer to "which screens are slow", because a mean cannot see a tail — and this route's own notes
    say so rather than letting a ranking imply more than it measured.
    """
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_for_routes(wanted, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    routes = report["routes"]
    # EXCLUDED, NOT SORTED TO THE BOTTOM. A withheld row has null everywhere, and every ranking rule
    # that "handles" nulls invents a position for a figure the server declined to state.
    rankable = [row for row in routes if not row.get("withheld")]
    busiest = sorted(
        (row for row in rankable if (row.get("requests") or 0) > 0),
        key=lambda row: (-int(row.get("requests") or 0), str(row.get("routeTemplate") or "")),
    )[:limit]
    # A screen with no traffic has `avgDurationMs` None because there was nothing to average, which
    # is a third state again — neither withheld nor slow — and it is filtered out rather than ranked
    # as instantaneous.
    slowest = sorted(
        (row for row in rankable if row.get("avgDurationMs") is not None),
        key=lambda row: (
            -int(row.get("avgDurationMs") or 0),
            str(row.get("routeTemplate") or ""),
        ),
    )[:limit]

    return {
        "window": _window_block(since, until),
        "scope": _template_block(wanted, bool(template)),
        "limit": limit,
        "busiest": busiest,
        "slowest": slowest,
        "withheld": {
            "routes": sum(1 for row in routes if row.get("withheld")),
            "explanation": (
                f"Screens used by fewer than {usage.MIN_IDENTIFIED_USERS_FOR_ROUTE} identified "
                f"accounts are excluded from BOTH rankings rather than placed in them. They are "
                f"counted here so the ranking can be read as covering less than the whole scope."
            ),
        },
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            "This is a ranking of the screens in 'scope', not of the product. 'scope.notIncluded' "
            "is how many mounted screens are outside it. There is deliberately no route that ranks "
            "every screen: that is a whole-window scan and no index here serves one.",
            "'slowest' is ranked on the MEAN server duration, which cannot see a tail. A screen "
            "whose mean is 120 ms and whose p95 is four seconds is broken for one request in twenty "
            "and will not appear here. /usage/latency is the honest answer to 'which screens are "
            "slow'.",
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            _SERVER_TIME_NOTE,
        ],
    }


# --------------------------------------------------------------------------------------
# THE TRAILS: the caller's own, and — behind its own dependency — one named colleague's
# --------------------------------------------------------------------------------------


def _trail_notes() -> list[str]:
    """The sentences both trail routes carry. Shared so the self-read and the cross-account read
    cannot come to describe the same rows differently — which would be the worst place in this module
    for two descriptions of one dataset, because one of the two readers is the subject."""
    return [
        "This is a LOG and not an aggregate: it replays the order requests arrived in. Everything "
        "else under /usage is a count.",
        "Only requests that reached this API are here. A cached page, an offline draft or a "
        "client-side navigation is invisible to it, so an absence of rows is not an absence of "
        "work.",
        "Rows are attributed only where consent was GRANTED at the moment they were recorded. A "
        "period before an account agreed is genuinely empty here, and that is not the same fact as "
        "'nothing was done'.",
        _SERVER_TIME_NOTE,
    ]


@router.get("/me/trail")
async def my_trail(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    limit: int = Query(usage.MAX_TRAIL_ROWS, ge=1, le=usage.MAX_TRAIL_ROWS),
    offset: int = Query(0, ge=0),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Everything this platform recorded about the person asking, request by request.

    **BUILT BEFORE THE CROSS-ACCOUNT ROUTE, AND ON PURPOSE.** It needs no new permission — a person
    reading what the system recorded about them exercises no privilege — and it is what makes the
    consent notice's promise true rather than aspirational: "you can see exactly what we hold about
    you" is only honest once this exists. It also produces the serializer the cross-account route
    reuses, so the two cannot show the same rows differently.

    ``GET /usage/me`` IS THE AGGREGATE AND THIS IS THE LOG, and both exist because the first one's
    docstring named the reason: it "does not replay the order somebody moved through the app in,
    although the rows underneath could. That is deliberate — a route that renders one person's
    afternoon minute by minute is a shape somebody will eventually want pointed at a different
    person, and the argument for adding it should have to be made against a module that does not
    already do it." That argument has now been made, in writing, in this module's docstring and in
    `deps.can_read_person_usage` — and the route that points at a different person is a different
    route, at master admin, gated additionally on that person's own consent.

    NO WITHHOLDING FLOOR, and its absence is not an oversight: there is no group to hide in when the
    subject is the reader, and applying one would mean telling somebody too few people used a screen
    for them to be shown their own use of it.
    """
    since, until = _window(raw_from, raw_to)
    user_id = str(get_value(current_user, "id") or "")
    try:
        report = await usage.trail_for_user(
            user_id, since, until, limit=limit, offset=offset
        )
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "userId": user_id,
        "window": _window_block(since, until),
        "limit": report["limit"],
        "offset": report["offset"],
        "maxRows": usage.MAX_TRAIL_ROWS,
        "events": report["events"],
        "consent": usage.consent_record(current_user),
        "collection": _collection_summary(),
        # An empty list here would be read as "you have never used the app", which is the defect
        # `/usage/me` names in its own docstring. The gate says whether the emptiness has a cause the
        # reader can act on — and for a REFUSED account it always does: they asked for exactly this.
        "gate": usage.consent_gate(current_user),
        "notes": _trail_notes(),
    }


@router.get("/accounts/{user_id}/trail")
async def account_trail(
    user_id: str,
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    limit: int = Query(usage.MAX_TRAIL_ROWS, ge=1, le=usage.MAX_TRAIL_ROWS),
    offset: int = Query(0, ge=0),
    reader: Any = Depends(require_person_usage_reader),
) -> dict[str, Any]:
    """ONE NAMED COLLEAGUE'S request-by-request trail. **The most sensitive read in this feature.**

    ── WHO MAY READ IT, AND WHY THAT IS DEFENSIBLE ─────────────────────────────────────────────

    **The master admin, and nobody else at any rank** — `deps.can_read_person_usage`, which is a NEW
    predicate rather than a widened `can_read_usage`. The argument, in full, because a route like
    this must not exist on the strength of somebody once wanting it:

    `can_read_usage` puts the AGGREGATES at Admin on two precedents in `deps.py`:
    `can_manage_designer_roster` gates a READ at Admin purely because the roster reveals colleagues'
    institutional standing, and a usage table "is strictly more revealing than that, because it is a
    record of what people did rather than of what an administrator wrote down about them"; and
    `/analytics/design-workshops` — a comparison of CRAFT OUTCOMES, observing no person at all — is
    already admin-only, because "a feature that observes colleagues cannot be gated more loosely than
    one that observes cloth."

    A named person's minute-by-minute trail is strictly more revealing again than the aggregate that
    argument was made about. The aggregate cannot say who; this says nothing else. So it cannot sit
    at the same rank, and the ladder already has the precedent one rung up: the master admin is the
    single account that reviews everyone's work, and the single account the platform allow-list can
    never bar. One person, in one institution, answerable for the read.

    **AND THE RANK IS ONLY HALF THE GATE.** The subject's own consent must be GRANTED. That is
    partly unavoidable — `collection_plan` attributes rows under GRANTED alone, so an account that
    refused or was never asked has no attributed rows at all — but it is enforced explicitly and
    ANSWERED WITH A SENTENCE, because the alternative is an empty list, and an empty list is read as
    "this person has never used the app". Three states, three different next moves:

    * **GRANTED** — here is the trail.
    * **NOT_RECORDED** — nobody has asked them yet. Nothing was ever attributed. 409, and the
      remedy is that they answer at sign-in; nobody else can answer for them.
    * **REFUSED** — they declined, and whatever was collected was DELETED when they did. 409, and
      there is no remedy at all: asking again is not a thing this product does, and telling an
      administrator to go and ask is how somebody learns that a refusal is negotiable.

    ── WHAT IT DOES NOT DO ─────────────────────────────────────────────────────────────────────

    IT IS NOT A `?userId=` ON ANYTHING. Its own path segment, its own dependency, its own argument —
    the three conditions this module's docstring set when it said the "Nowhere" bullet could only be
    replaced this way.

    NO WITHHOLDING FLOOR, because there is no group to hide in: the subject is named in the URL, and
    a floor here would be a rule that pretends to protect somebody it has already identified. The
    floor's job is done by the gate above it.

    **THE READ IS LOGGED TO THE SERVER LOG AND THERE IS NO AUDIT TABLE, WHICH IS SAID RATHER THAN
    IMPLIED.** The usage table cannot record this read — `/usage/*` is in `UNRECORDED_TEMPLATES` so
    the dataset is not a record of itself — so what exists is one INFO line naming the reader, the
    subject and the window. A durable audit row is a schema decision somebody should take on purpose,
    and pretending one exists would be worse than not having it.
    """
    since, until = _window(raw_from, raw_to)
    subject_id = str(user_id or "").strip()
    subject = await db.user.find_unique(where={"id": subject_id}) if subject_id else None
    if subject is None:
        # 404 and not an empty trail. "No such account" and "this account did nothing" are different
        # facts, and a reader who cannot tell them apart will report the second.
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No account with id {subject_id!r}.",
        )

    state = usage.resolve_consent(subject)
    if state is not usage.UsageConsent.GRANTED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                (
                    "This account has DECLINED to have its use of the platform recorded. Nothing "
                    "was kept, and whatever had been collected was deleted when they declined, so "
                    "there is no trail to read and there will not be one. Asking them again is not "
                    "a thing this product does — a refusal that can be reopened by an administrator "
                    "is not a refusal."
                )
                if state is usage.UsageConsent.REFUSED
                else (
                    "Nobody has asked this account yet whether its use of the platform may be "
                    "recorded, so no request of theirs has ever been attributed and there is no "
                    "trail to read. They are asked at their next sign-in and the answer is theirs "
                    "alone — it cannot be given on their behalf."
                )
            ),
        )

    try:
        report = await usage.trail_for_user(
            subject_id, since, until, limit=limit, offset=offset
        )
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    # ONE LINE, AT INFO, AND IT IS THE ONLY RECORD THIS READ LEAVES. Named fields rather than a
    # sentence so it is greppable, and the reader FIRST because "who read whose" is the question
    # anybody comes to this log with.
    logger.info(
        "usage: %s read the request trail of %s for %s to %s (%s row(s))",
        get_value(reader, "id"),
        subject_id,
        since.isoformat(),
        until.isoformat(),
        len(report["events"]),
    )

    return {
        "userId": subject_id,
        "window": _window_block(since, until),
        "limit": report["limit"],
        "offset": report["offset"],
        "maxRows": usage.MAX_TRAIL_ROWS,
        "events": report["events"],
        # ECHOED SO THE ROWS CAN BE READ AGAINST THE ANSWER THEY WERE COLLECTED UNDER. The account's
        # current consent is not the same fact as the consent on each row: somebody may have agreed
        # part-way through the window, in which case the earlier part is genuinely empty and the
        # rows that exist all say GRANTED.
        "subjectConsent": usage.consent_record(subject),
        "readBy": get_value(reader, "id"),
        "notes": [
            "THIS IS ONE NAMED PERSON'S TRAIL, not an aggregate. It is readable by the master "
            "admin alone, only while that person's own answer is GRANTED, and every read of it is "
            "written to the server log naming the reader, the subject and the window.",
            "'consentState' on each row is the answer THAT ROW was collected under. It can differ "
            "from the account's current answer, and a window that begins before they agreed is "
            "genuinely empty at the start.",
            "There is no withholding floor here: the subject is named in the request, so there is "
            "no group for a floor to protect them inside.",
            *_trail_notes(),
        ],
    }
