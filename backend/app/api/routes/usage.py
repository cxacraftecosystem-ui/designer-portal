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

* **`GET /usage/me`** — the caller's own use, and only ever the caller's own. `get_current_user`,
  because a person reading what the system recorded about them needs permission from nobody. There is
  no `?userId=` on it. Pointing it at somebody else is not a parameter this module withholds; it is a
  route that does not exist.
* **`GET /usage/routes`** and **`GET /usage/collection`** — cross-account, aggregate only, behind
  `require_usage_reader` (Admin and above; see `deps.can_read_usage` for why not Researcher, which is
  the floor the research use case would prefer). No user id leaves either of them: the distinct-people
  count is folded into an integer inside `services/usage.py`, so a route module physically cannot leak
  one by accident.
* **Nowhere** — one account's trail read by another account, at any rank. If that is ever wanted it is
  a new route with its own dependency and its own written argument, NOT a query parameter added to the
  three above. A parameter is how a boundary gets crossed by somebody who never read the paragraph
  explaining it.

THE GATE IS THE DEPENDENCY, and `frontend/lib/permissions.ts` merely mirrors it. This repository has
twice shipped a UI guard over an open endpoint — the link disappeared and the URL stayed open — which
is why `analytics.py` says the same thing at the top of its own module.

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

from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.deps import get_current_user, get_value, require_usage_reader
from app.services import usage
from app.services.pagination import normalize_pagination, page_payload

router = APIRouter(prefix="/usage", tags=["usage"])


#: This module's own route templates, named once so `app/main.py` can build the recorder's skip set
#: from the same list a reader of this file sees. See :data:`UNRECORDED_TEMPLATES`.
USAGE_READ_TEMPLATES: frozenset[str] = frozenset(
    {"/usage/me", "/usage/routes", "/usage/collection"}
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

    A NOTE ON WHAT IS MISSING FROM YOUR OWN ANSWER TODAY. Until a consent flow exists this deployment
    records requests WITHOUT the identity (`usage.DEFAULT_UNASKED_COLLECTION`), so this route will
    report nothing for anybody: the rows exist, and none of them carries a name. That is the honest
    consequence of the default rather than a fault, `GET /usage/collection` says so in the same words,
    and it is why the response below carries `collection` rather than leaving an empty list to be read
    as "you have never used the app".
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


def _collects() -> list[str]:
    """What this deployment actually records, COMPUTED from the policy rather than asserted.

    **THE ACCOUNT-ID LINE USED TO BE A CONSTANT SAYING "ONLY WHERE CONSENT HAS BEEN RECORDED AS
    GRANTED", AND THAT IS TRUE OF EXACTLY ONE OF THE THREE POLICIES THIS MODULE SHIPS.**
    ``usage.DEFAULT_UNASKED_COLLECTION`` is documented as overrulable in one line and
    ``UnaskedCollection`` names all three values on purpose, so both of the others are one edit away:

    * ``ATTRIBUTED`` records the id for people nobody ever asked. The old sentence would then have
      gone on telling a reader of the published method that attribution follows consent, on the same
      page whose ``consent.unaskedPolicy`` field said ATTRIBUTED — and the prose is the half a person
      reads.
    * ``NOTHING`` records no row at all, and this whole list would have gone on enumerating seven
      things that were not being collected.

    Either would be the one failure this endpoint exists to prevent: a methodology that describes an
    intended design rather than the running one. So the list is derived from ``collection_plan`` —
    the same function the recorder itself calls, on the same constant — and a flip changes what is
    published in the same edit that changes what is recorded, because it is the same line of code.

    NOTE WHAT DOES *NOT* VARY: ``consentState`` stays NULL under all three policies, so no row ever
    claims a consent that was not given whatever this returns. That claim is a constant in
    ``collection_method`` below because it IS constant.
    """
    plan = usage.collection_plan(usage.UsageConsent.NOT_RECORDED)
    if not plan.record:
        return [
            "NOTHING. This deployment's policy for accounts nobody has asked is "
            f"{usage.DEFAULT_UNASKED_COLLECTION.value}, nobody has been asked, and no consent flow "
            "exists — so no request is recorded at all and no row is being written. The columns "
            "below describe what this instrumentation WOULD collect if the policy were changed. "
            "See 'consent'.",
        ]

    if plan.attribute:
        account = (
            "The account id, on EVERY signed-in request — including from accounts nobody has asked. "
            "This deployment's policy for the unasked is "
            f"{usage.DEFAULT_UNASKED_COLLECTION.value}. The rows still record consentState NULL, "
            "which means nobody was asked; they are attributed and unconsented, and anybody "
            "reporting figures drawn from them has to say so. See 'consent' below."
        )
    else:
        account = (
            "The account id, ONLY where consent has been recorded as granted — which is no request "
            "today, because nobody has been asked. See 'consent' below."
        )

    return [
        "The matched route TEMPLATE — /design-workshops/{workshop_id}, never the interpolated "
        "path. Record ids travel in paths here, so a table of raw paths would be a per-designer "
        "reading list of other people's fieldwork.",
        "The HTTP method.",
        "The status code the client received.",
        "Server duration in whole milliseconds.",
        "Which client said it was, from a header: web, android, or api for anything that did "
        "not say — which is every client today, because neither the web nor the Android layer "
        "sends the header yet.",
        account,
        "The moment the request finished.",
    ]


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
        "doesNotCollect": [
            "The interpolated path, so no record id is ever stored.",
            "Query strings — '?q=' carries whatever somebody typed into a search box.",
            "Request or response bodies, headers other than the client label, IP addresses, "
            "user agents, or anything a person typed.",
            "Anything at all from the routes in 'notMeasured'.",
        ],
        "notMeasured": sorted(UNRECORDED_TEMPLATES),
        "consent": {
            "unaskedPolicy": usage.DEFAULT_UNASKED_COLLECTION.value,
            "options": [option.value for option in usage.UnaskedCollection],
            "flowExists": False,
            "explanation": _collection_summary()["explanation"],
            "consentStateWritten": (
                "NULL on every row written so far. NULL means NOBODY WAS ASKED, which is the only "
                "thing that makes these rows findable and deletable on the day somebody decides "
                "they should be. The token GRANTED is written in exactly one circumstance: an "
                "account whose recorded answer is GRANTED. There are none."
            ),
            "refusalCost": (
                "A refusing account is not recorded at all, not even anonymously. Every aggregate "
                "therefore describes everyone who did not refuse, and anybody reporting these "
                "figures has to say so."
            ),
            "document": "docs/DECISION-usage-consent-default.md",
        },
        "readableBy": {
            "/usage/me": "the account itself, and nobody else at any rank",
            "/usage/routes": "Admin and above (deps.can_read_usage) — aggregates only, no user ids",
            "/usage/collection": "Admin and above — this document, no figures about anybody",
        },
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
            "rowsPerWrite": usage.FLUSH_ROWS,
            "flushIntervalSeconds": usage.FLUSH_INTERVAL_SECONDS,
            "bufferCeiling": usage.BUFFER_CEILING,
        },
        "losses": {
            # Scoped rather than counted. A process cannot honestly report how many siblings it has
            # — asking the operating system would answer for this box and not for the deployment —
            # so the sentence states what these numbers cover and leaves the reader to know how many
            # boxes they run.
            "scope": (
                "this worker process, since it started. Deployment runs a single uvicorn worker "
                "today; if that ever changes, these counters describe only the process that "
                "answered this request and not the fleet."
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
            "Unauthenticated requests carry no account, and today NO request carries one — see "
            "'consent'. Counts of people cannot be derived from these rows.",
            "The record starts on the day it was deployed. There is no history before it and none "
            "can be reconstructed.",
            "Only requests that reached this API are here. Anything served from a cache, an offline "
            "draft or a client-side navigation is invisible to it.",
            "The '<unmatched>' template means 'no route name was available', which is a 404 in "
            "production and, where BACKEND_EXPOSE_DOCS is on, also FastAPI's own documentation "
            "pages: /docs, /openapi.json and /redoc are plain Starlette routes, so they are served "
            "without the router ever naming them. They are deliberately absent from "
            "'/usage/routes' rather than listed there reporting a permanent zero.",
        ],
        "retention": (
            "There is no retention policy and nothing deletes these rows on a schedule; that is a "
            "decision nobody has made yet, and this sentence exists so it is not mistaken for one "
            "that was. Deleting an account deletes its rows (onDelete: Cascade), and "
            "usage.withdraw() deletes one account's rows on request."
        ),
        "document": "docs/METHODOLOGY-usage-instrumentation.md",
    }
