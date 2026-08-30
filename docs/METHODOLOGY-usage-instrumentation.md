# How platform use is measured, and what those measurements can and cannot support

> ## STATUS, 2026-08-29: COLLECTING, ANONYMOUSLY, WITH THE CONSENT QUESTION STILL OPEN.
>
> * **Built and wired.** One row per served request, recorded by `UsageEventMiddleware` in
>   `backend/app/main.py`, buffered and written in batches by `backend/app/services/usage.py`, read
>   back through `GET /api/usage/me`, `GET /api/usage/routes` and `GET /api/usage/collection` in
>   `backend/app/api/routes/usage.py`. The table is `UsageEvent` in `backend/prisma/schema.prisma`,
>   migrated 2026-08-29.
> * **In force:** requests are recorded **without the identity** — which screen, what it answered, how
>   long the server took, and no name. `usage.DEFAULT_UNASKED_COLLECTION = ANONYMOUS`.
> * **Not built, and not decided here:** the consent flow itself — what is asked, when, on which
>   screen. `docs/DECISION-usage-consent-default.md` carries that argument and the cost of each
>   option. Every row written so far carries `consentState` NULL, which the schema defines as
>   **nobody was asked**.
> * **Nothing is deleted and no existing behaviour changes.** The audio-consent path
>   (`DwDictationConsent`) is untouched; this is a second, separate question about a different kind
>   of data.

This is the methodology note requirement 26 asks for. It is written to be quoted next to a figure:
what was collected, what was deliberately not, who may read it, how long it is kept, and — the
section that makes the rest of it usable — **what conclusions these numbers cannot support**.

There is a machine-readable version of most of it at `GET /api/usage/collection`, which reports the
policy actually in force on the running deployment and the number of observations that deployment
has lost. Prefer it over this document when the two disagree: a document describes an intended
design, and the endpoint describes what actually ran.

---

## 1. What is collected

One row per HTTP request that reached the API, written after the response was finished. Nine
columns, and that is the whole of it:

| Column | What it holds | Note |
|---|---|---|
| `routeTemplate` | `/design-workshops/{workshop_id}/stages/{stage_key}` | The matched route's **template**, never the interpolated path |
| `method` | `GET`, `POST`, `PATCH`, `DELETE` | |
| `statusCode` | what the client received | 500 when the handler crashed; 499 when the client hung up before an answer |
| `durationMs` | whole milliseconds, server side only | measured on a monotonic clock, not a wall clock |
| `clientApp` | `web`, `android` or `api` | from a header. **Today it is `api` for every row** — see §6 |
| `userId` | the account | **NULL on every row so far** — see §3 |
| `consentState` | the consent this row was collected under | **NULL on every row so far**: nobody was asked |
| `createdAt` | when the request **finished** | not when the row was written — see §5 |
| `id` | a generated cuid | so a whole batch can be inserted without consulting the database |

## 2. What is deliberately not collected

* **The interpolated path.** Every record id in this API travels in the path, so a table of raw paths
  would be a per-designer reading list of other people's artisans, sketches and interviews —
  assembled with no access check, kept for ever, and readable by anybody who could query the table.
  The template is also the only form in which "which screens do designers reach" has an answer at
  all: a million distinct paths group into a million groups of one.
  `backend/app/services/usage.py` refuses the value rather than trusting its caller, in two layers —
  an allow-list built from the application's own route table at startup, and shape rules underneath
  it. The shape rules cannot catch a record id that happens to read like a word; the allow-list can,
  and that is why the allow-list is the one that is wired up.
* **Query strings.** `?q=` carries whatever somebody typed into a search box. There is no column for
  it and the template validator refuses any value containing one.
* **Request and response bodies, headers other than the client label, IP addresses, user agents.**
  None of them has a column.
* **Anything from the health probes or the `/usage` read routes.** `/health` and `/health/ready`
  arrive on a monitoring timer rather than because anybody navigated anywhere; at one probe every few
  seconds they would be the two most-used "screens" in the product for ever. The `/usage` reads are
  excluded because recording them would make the dataset partly a record of itself — a dashboard left
  open would raise "requests per day" on its own, and the rows would be indistinguishable from real
  ones afterwards. **The cost of that second exclusion, stated: this table cannot show that the usage
  endpoints are slow.** The server log and `GET /api/usage/collection`'s loss counters are where that
  shows instead. The list is published by that endpoint, so the omission is part of the method rather
  than something a reader has to infer from a suspiciously flat graph.
* **No name for FastAPI's own documentation routes.** `scope["route"]` is written by FastAPI's
  `APIRoute` and by nothing else, and `/docs`, `/redoc`, `/openapi.json` and `/docs/oauth2-redirect`
  are plain Starlette routes. They are served — where `BACKEND_EXPOSE_DOCS` is on, which is local
  development and the dev cluster — and the recorder has no template to write for them, so they land
  under the `<unmatched>` placeholder beside genuine 404s. They are deliberately **not** in the
  startup allow-list, so `GET /api/usage/routes` does not list them: a screen that can only ever
  report zero reads as "nobody uses this" rather than as "this is not measured", and those are
  opposite facts. `<unmatched>` therefore means "no route name was available", which is a 404 in
  production and a 404 or a documentation page in development.

## 3. Consent, and what the default costs the numbers

Watching a designer navigate is a **new category of personal data** in this repository. Everything
else it holds about a person is something that person typed in; this is something the system noticed
about them without being asked. The codebase already models consent explicitly where it collects a
recording — `dictation_consent.py` gates an artisan's recorded voice behind an explicit,
per-workshop, three-state answer with an append-only decision log — and it would be a strange system
that refuses to send an artisan's voice anywhere without a recorded answer while building a
per-colleague trail of everyone who works on it.

So: **no consent flow exists, and this instrumentation does not pretend otherwise.** Until one does,
the rule in `usage.collection_plan` is:

| Recorded answer | What is recorded |
|---|---|
| `GRANTED` | the request **and** the account id; `consentState` = `"GRANTED"` |
| `REFUSED` | **nothing at all** — not an anonymous row either |
| `NOT_RECORDED` (everybody, today) | the request **without** the identity; `userId` NULL, `consentState` NULL |

Three consequences that anybody reporting these figures has to carry with them:

1. **No figure produced today can be attributed to a person, because no row carries one.**
   `GET /api/usage/me` will report nothing for anybody until a consent flow ships. That is the honest
   consequence of the default, not a fault, and the endpoint says so in its own response rather than
   returning an empty list to be read as "you have never used the app".
2. **Every aggregate describes everyone who did not refuse, not everyone.** A refusal removes the
   rows rather than anonymising them, because on a route two people use, anonymity is a label and not
   a property — a refusal that changes nothing the person would recognise is a preference, not a
   permission. There are no refusals today (nobody has been asked), so the two populations currently
   coincide; the day they stop coinciding, this sentence is the one that has to appear beside the
   numbers.
3. **`consentState` NULL is load-bearing and must not be defaulted to anything.** It is what makes
   the rows gathered before the decision findable — and therefore deletable — on the day somebody
   decides they should be. The token `"GRANTED"` is written in exactly one circumstance: an account
   whose recorded answer is `GRANTED`.

The full argument, including what each of the three possible defaults costs, is in
`docs/DECISION-usage-consent-default.md`.

## 4. Who may read what

| Route | Who | What comes back |
|---|---|---|
| `GET /api/usage/me` | the account itself, **and nobody else at any rank** | that account's own use, aggregated by screen |
| `GET /api/usage/routes` | Admin and above (`deps.can_read_usage`) | per-screen aggregates. **No user id, by construction** |
| `GET /api/usage/collection` | Admin and above | this method and the deployment's loss counters. No figures about anybody |

**There is no route by which one account's trail can be read by another account, at any rank.** Not
an admin's, not a researcher's. If that is ever wanted it must be a new route with its own dependency
and its own written argument — not a `?userId=` added to the three above, because a parameter is how
a boundary gets crossed by somebody who never read the paragraph explaining it.

The gate is the server-side dependency. `frontend/lib/permissions.ts` mirrors it and does not
enforce it; this repository has twice shipped a UI guard over an open endpoint, where the link
disappeared and the URL stayed open.

**Admin and not Researcher**, although the research use case would prefer the lower floor, for two
precedents already in `backend/app/core/deps.py`: `can_manage_designer_roster` gates a *read* at
Admin purely because the roster reveals colleagues' institutional standing, and a record of what
colleagues *did* is more revealing than that; and `/api/analytics/design-workshops` — a comparison of
craft outcomes, which observes no person at all — is already admin-only. A feature that observes
colleagues cannot be gated more loosely than one that observes cloth. `can_read_usage` is its own
predicate precisely so that this decision can be revisited in one line once there is a consent flow
to revisit it against.

**A withholding floor sits under the aggregates as well as the gate.** Any route used by fewer than
five *identified* accounts in the window comes back with every metric `null` and `withheld: true` —
because "who opened the artisan screen at 2 a.m." is answerable from a page labelled *aggregates* the
moment a route has one user in it, and the window is chosen by whoever is asking. That is a
**refusal, never a zero**: `null` coerces to 0 through arithmetic and through `??`, so a consumer
must branch on `withheld` rather than fall back. Routes with *no* identified accounts report freely —
sign-in traffic is almost entirely unauthenticated, and "the sign-in page is slow for the people who
cannot get in" is one of the things this record exists to be able to show.

## 5. How the measurement is made, and the caps on reading it back

* **Where.** A pure-ASGI middleware mounted outside the router and inside CORS. Outside the router
  because `scope["route"]` — the template — does not exist until the router has matched; there is no
  position below the router from which the template is knowable, which is why this cannot be a
  dependency.
* **What the duration covers.** From the middleware entering to the response being finished. See §7.
* **Buffered, not written per request.** `DATABASE_CONNECTION_LIMIT` is 10, cut to it from 40 after a
  pooler's shared connection budget was exhausted and crash-looped this deployment. One round trip to
  the database measured 756 ms against tables whose server-side execution is 0.04–0.24 ms, so an
  INSERT on the request path would take one of ten connections and add most of a second to every
  response in order to record that the response was slow. Rows are buffered in memory and written 200
  at a time, at least every 5 seconds, with a 5,000-row ceiling.
* **`createdAt` is stamped when the request finished, not when the row was written.** A row can sit
  in the buffer for seconds normally and for minutes during an outage; dating rows to the flush would
  destroy the ordering that is the entire reason per-request rows are kept instead of a daily rollup.
* **Losses are counted, not silent — and there are two different losses, which
  `GET /api/usage/collection` reports under two different names because they mean opposite things.**
  A dataset that loses rows without saying how many is a dataset nobody can check.
  * `abandonedAfterFailedWrites` is the one that moves **when the database is away**. A drained batch
    is offered twice and then written off, so **roughly five seconds of unavailability is survivable
    and everything past that is lost** at the rate the writer drains the buffer. Simulated against
    the real writer at 20 requests a second with every write refused: the first row is lost after
    **5.25 seconds**, and four minutes in, **4,720 of 4,800 rows are gone**. The buffer does not fill
    up and wait — it is emptied by the writer whether or not the write lands. Bounding the attempts
    is deliberate: one row Postgres will never accept must not be able to block every legitimate row
    behind it for the life of the process. `usage.FLUSH_MAX_ATTEMPTS` is the constant that sets this,
    and the only one that would change it.
  * `droppedAtCeiling` is the one that moves **when this process is producing faster than it can
    write**. The buffer reaches its 5,000-row ceiling and the **oldest** rows are dropped, because
    the newest describe the trouble and are what anybody will look at. During a database outage this
    counter stays at zero; reading it as the outage figure would report a four-minute outage as no
    loss at all.
  * A clean shutdown drains what is buffered when it starts; an unclean one loses at most five
    seconds. A batch already handed to Prisma when the worker is cancelled is lost with it — at most
    one statement's worth.
* **The read caps, and they are stated in every response rather than left to be discovered.** A date
  range is **required**; the widest window either aggregate will answer over is **366 days**; one
  request may name at most **50** route templates. A window wider than the cap is **refused with a
  sentence naming the number**, not quietly narrowed — narrowing would answer a different question
  from the one asked and label the answer with the original dates.

## 6. What is not yet wired, and would change the numbers if it were

* **`clientApp` is `api` on every row.** Neither `frontend/lib/api.ts` nor the Android network layer
  sends the `x-client-app` header yet, and an unlabelled client is honestly an unknown client. Until
  one of them does, **web and Android traffic cannot be told apart** — which matters more than it
  looks, because "how do they navigate" has different answers on a laptop and on a handset that runs
  offline for a fortnight at a time, and averaging the two describes a designer who does not exist.
* **No consent flow, so no attributed rows.** §3.
* **No client-side instrumentation on either client.** Everything here is server-side. §7 says what
  that excludes.
* **`usage.withdraw()` exists but its refusal is process-local.** It stops new events, drops that
  account's buffered rows and deletes its stored ones — but there is no column to persist the
  refusal across a restart, and no decision log, so "granted on the 3rd, withdrawn on the 9th" is not
  answerable. Both need a migration.

## 7. Limitations — what these numbers cannot support

This is the section that makes the rest of the document a methodology rather than a feature list. Every
item is a conclusion somebody will otherwise draw.

1. **Server duration is not user-perceived latency, and the gap is not small.** `durationMs` measures
   the server and nothing else: no DNS, no TLS handshake, no request upload, no response download, no
   parsing, no rendering, no time spent on a handset. This application is used in villages on mobile
   connections; `docs/SCALABILITY.md` sizes that link at 40 kB/s, at which a 14 KB list response
   spends about a third of a second on the wire alone and a 400 KB one spends ten. **A median of 40 ms
   here is entirely compatible with a designer watching a spinner for eight seconds in a courtyard.**
   Any sentence of the form "the platform responds in X ms" must say *the server* responds in X ms, or
   it is false. Measuring the other part requires client-side instrumentation, which does not exist on
   either client.
2. **A route template is not a feature, and counting templates is not counting features.** One
   template serves several things a person would name differently — `/design-workshops/{id}` is opened
   by the workshop list, by a deep link, by a report preview and by a background refresh — and one
   screen calls several templates, so a single "visit" can be five rows or one depending on how the
   page was built. **Ratios between templates are not ratios between activities.** Where a claim is
   about a *feature*, the mapping from feature to templates has to be stated explicitly and defended;
   it cannot be read off this table.
3. **Unauthenticated rows have no user, and today no row has one.** Counts of *people* cannot be
   derived from these rows at all: not by counting rows, not by counting distinct ids (there are
   none), not by assuming a session. Even after a consent flow ships, `userId` will be NULL for every
   unauthenticated request — sign-in, the public router, an expired token — so a count of distinct
   accounts is a count of *identified* accounts and is a floor, never a total.
4. **There is no session, and no way to reconstruct one.** There is no session id, no client-generated
   correlation id and no `lastSeenAt` anywhere in this schema. "How many visits", "how long a visit
   lasted" and "where somebody dropped out of a flow" are not answerable, and inferring them from
   timestamp gaps invents a threshold that the data cannot justify.
5. **Only requests that reached this API are here.** Anything served from a client-side cache, an
   offline draft in IndexedDB, a service worker, or an in-app navigation that fetched nothing is
   invisible. The Android client is explicitly built to work offline for long periods, so **absence of
   rows is not absence of work** — it may be a designer working all week in a village with no signal,
   whose requests then arrive in one burst on the day they reach a town.
6. **The record starts on the day it was deployed.** There is no history before it and none can be
   reconstructed. Any before/after comparison must have its "before" measured after that date.
7. **The population is not a sample of anything.** These are the people who have accounts on this
   deployment, using it for their own work. It is a census of a self-selected group, not a sample of
   designers, and nothing here generalises beyond it without an argument made elsewhere.
8. **A denominator drawn from `Feedback` will not match.** Self-reported feedback is one upserted row
   per account — every resubmission overwrites the previous answers — so it has no history, no
   repeated measures and no task context. It answers a different question about a different
   population and the two cannot be joined on anything meaningful today.
9. **The figures are per deployment and per process.** The loss counters at
   `GET /api/usage/collection` describe the worker that answered the request. Deployment runs a single
   worker today; on more than one, those counters are a fraction of the fleet's and the endpoint says
   so rather than leaving it to be assumed.
10. **Withheld routes are missing from every total, and are counted so the hole is visible.** The
    page totals in `GET /api/usage/routes` exclude withheld routes and report how many were excluded.
    Treating a withheld route as zero — which is what happens if a consumer reads `null` through
    arithmetic — reports a smaller number as though it were the truth.

## 8. Retention

**There is no retention policy, and this sentence exists so that its absence is not mistaken for a
decision that was made.** Nothing deletes these rows on a schedule. Two things do delete them:

* deleting an account deletes its usage rows (`onDelete: Cascade` on `UsageEvent.user`) — chosen over
  `SetNull` because NULL already means "nobody was signed in", and letting it also mean "the person
  has since been deleted" would make every count of unauthenticated traffic quietly include
  ex-colleagues' requests;
* `usage.withdraw(user_id)` deletes one account's stored rows and drops its buffered ones — with the
  two gaps named in §6.

Whoever sets a retention period should set it together with the consent flow, because the two
questions are the same question asked twice: how long may something be kept that nobody agreed to.

## 9. Where to look

| | |
|---|---|
| The recorder | `backend/app/main.py` — `UsageEventMiddleware`, and the registration comment above `add_middleware` |
| The identity stitch | `backend/app/core/deps.py` — `get_current_user` |
| The writer, consent rules, validation | `backend/app/services/usage.py` |
| The read API | `backend/app/api/routes/usage.py` |
| The gate | `backend/app/core/deps.py` — `can_read_usage` / `require_usage_reader` |
| The table | `backend/prisma/schema.prisma` — `model UsageEvent` |
| The consent argument | `docs/DECISION-usage-consent-default.md` |
| The tests | `backend/tests/test_usage_tracking.py` |

## How this document is kept true

This describes one subsystem, so it has one source of truth: `backend/app/services/usage.py` and the
middleware in `backend/app/main.py`. **Prefer `GET /api/usage/collection` over this file wherever the
two disagree** — it is generated from the same constants at request time, on the running deployment,
and it is the reason §1, §5 and §8 can be checked rather than believed. A methodology section that
describes an intended design rather than the running one is how a paper ends up reporting a number
its own system never produced; that endpoint exists so this document cannot become that.

| Claim | How to check |
|---|---|
| §1 the column list | `model UsageEvent` in `backend/prisma/schema.prisma`, and the row built in `usage.record_event` |
| §1 "`userId` NULL on every row so far", §3 the consent default | `usage.DEFAULT_UNASKED_COLLECTION`, pinned by `test_the_default_policy_records_the_request_and_drops_the_name`. **If that test is edited, this document is out of date** |
| §2 the template, never the path | `usage.ensure_route_template` plus the startup allow-list, pinned by `test_one_request_records_one_event_with_the_template_not_the_path` and `test_a_404_records_a_placeholder_and_never_the_url_that_was_asked_for` |
| §2 what is not measured | `UNRECORDED_TEMPLATES` in `backend/app/api/routes/usage.py` — one list, read by the recorder and published by the endpoint, so they cannot drift |
| §4 who may read what | `deps.can_read_usage` / `deps.require_usage_reader`, pinned by `test_the_aggregates_refuse_a_designer_and_say_where_their_own_data_is` |
| §4 the withholding floor | `usage.MIN_IDENTIFIED_USERS_FOR_ROUTE`, pinned by `test_a_screen_too_few_people_used_is_withheld_rather_than_reported_as_a_number` |
| §5 the caps and the batching numbers | the constants block in `backend/app/services/usage.py`; all of them are echoed by `GET /api/usage/collection` under `limits` |
| §5 losses are counted | `GET /api/usage/collection` → `losses`, from `usage.buffer_stats()` |
| §6 `clientApp` is `api` on every row | `grep -r "x-client-app" frontend/lib android/` — **the day that returns a hit, §1 and §6 both change** |

**Review triggers**, each of which invalidates a specific paragraph rather than the document as a
whole: a change to `DEFAULT_UNASKED_COLLECTION` or the arrival of a consent flow (§3, §6, and the
status banner); either client starting to send `x-client-app` (§1, §6); a new column on `UsageEvent`
(§1); a change to `MIN_IDENTIFIED_USERS_FOR_ROUTE`, `MAX_RANGE_DAYS` or `MAX_TEMPLATES_PER_QUERY`
(§4, §5); a fourth read route, or a `?userId=` on any existing one (§4 — and see the paragraph there
before adding one); any retention or deletion job (§8); client-side instrumentation on either client
(§7.1 and §7.5, which are the two limitations that exist *because* everything here is server-side).

**Known unverified.** The 756 ms round trip and the 40 kB/s field link are quoted from
`backend/app/services/concurrency.py` and `docs/SCALABILITY.md` and were not re-measured for this
document. The loss counters have never been exercised against a real outage — only against a fake
that refuses writes (`test_a_database_that_refuses_the_write_never_reaches_a_request` and
`test_an_outage_abandons_batches_and_never_reaches_the_buffer_ceiling`), so the *counting* is pinned
and the *behaviour of a real Postgres going away mid-flush* is not. **The 5.25-second and 4,720-row
figures in §5 come from that same fake**, driven at 20 requests a second through the real
`run_flush_worker` cadence with the clock simulated rather than waited out: they are exact for the
writer's logic and say nothing about how a real Postgres fails, which can be slower (a connection
that hangs rather than refuses) and would move the first number. Nothing in
§7 is measured at all: it is a list of conclusions the data cannot support, and it is kept true by
being argued from the columns rather than from a benchmark.
