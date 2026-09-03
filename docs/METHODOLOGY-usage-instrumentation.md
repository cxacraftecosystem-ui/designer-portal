# How platform use is measured, and what those measurements can and cannot support

> ## STATUS, 2026-08-30: CONSENT IS ASKED AT SIGN-IN, AND ATTRIBUTED ROWS EXIST FOR THE FIRST TIME.
>
> * **Built and wired.** One row per served request, recorded by `UsageEventMiddleware` in
>   `backend/app/main.py`, buffered and written in batches by `backend/app/services/usage.py`, read
>   back through **thirteen endpoints on twelve paths** in `backend/app/api/routes/usage.py` (§4
>   lists them). The table is
>   `UsageEvent` in `backend/prisma/schema.prisma`, migrated 2026-08-29; the consent store is
>   `User.usageConsent*` plus `UsageConsentDecision`, migrated 2026-08-30.
> * **The consent flow exists.** Agreeing is asked at sign-in on both credential paths and again
>   whenever the notice version moves; it is a **condition of access**, which means a grant collected
>   there is not freely-given consent — so the *circumstance* is stored beside the answer
>   (`REQUIRED_AT_SIGN_IN` vs `OFFERED_IN_SETTINGS`) rather than concealed. The argument is
>   **`docs/DECISION-usage-consent-at-sign-in.md`**, which anybody quoting these figures should read.
> * **Attributed rows now exist.** A consenting account's requests carry its id and
>   `consentState = "GRANTED"`. §3's old prediction — *"`GET /api/usage/me` will report nothing for
>   anybody until a consent flow ships"* — stopped being true on 2026-08-30.
> * **Unchanged: `usage.DEFAULT_UNASKED_COLLECTION = ANONYMOUS`,** which now governs an account that
>   has not yet answered — overwhelmingly, requests with no account at all. Its justification was
>   rewritten rather than its value; see the banner of `docs/DECISION-usage-consent-default.md`.
> * **A window spanning 2026-08-30 mixes two populations.** Rows before it carry `consentState` NULL
>   (nobody was asked) and no name; rows after it carry a name only where consent was granted.
>   **Nothing backfills the NULLs**, and whether they are deleted is still open.
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
| `userId` | the account | NULL unless that account's consent is `GRANTED`. **NULL on every row written before 2026-08-30** — see §3 |
| `consentState` | the consent this row was collected under | `"GRANTED"`, or NULL meaning **nobody was asked**. NULL on every row written before 2026-08-30, and nothing backfills them |
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

**Since 2026-08-30 a consent flow exists, and it is asked at sign-in.** The rule in
`usage.collection_plan` is unchanged and now actually reaches all three branches:

| Recorded answer | What is recorded |
|---|---|
| `GRANTED` | the request **and** the account id; `consentState` = `"GRANTED"` |
| `REFUSED` | **nothing at all** — not an anonymous row either |
| `NOT_RECORDED` | the request **without** the identity; `userId` NULL, `consentState` NULL |

**AGREEING IS A CONDITION OF ACCESS, AND THAT IS RECORDED RATHER THAN CONCEALED.** A person who will
not agree cannot use the product, which under GDPR Art. 7(4) means the grant is not freely given —
consent is not free where performance of the service is made conditional on it and the processing is
not necessary for the service. So `User.usageConsentBasis` stores the circumstance beside the answer,
on the account and on every decision-log row: `REQUIRED_AT_SIGN_IN` is the turnstile,
`OFFERED_IN_SETTINGS` is the free choice, and every withdrawal is one of the latter. **Anybody
reporting these figures must say which they were.** The full argument is
`docs/DECISION-usage-consent-at-sign-in.md`.

`usage.NOTICE_VERSION` records **which text** was on screen, so a reword cannot claim agreement to
wording nobody saw; a version bump makes the gate ask again without reclassifying the answer already
given.

Four consequences that anybody reporting these figures has to carry with them:

1. **A window spanning 2026-08-30 mixes two populations.** Before it, no row carried a name at all;
   after it, a row carries one only where that account had agreed. The two are told apart by
   `consentState` and by nothing else, and a figure computed across the boundary is a figure over an
   inconsistent denominator.
2. **Every aggregate describes everyone who did not refuse, not everyone.** A refusal removes the
   rows rather than anonymising them, because on a route two people use, anonymity is a label and not
   a property — a refusal that changes nothing the person would recognise is a preference, not a
   permission. The moment there is one refusal, this sentence has to appear beside the numbers.
3. **`consentState` NULL is load-bearing and must not be defaulted or backfilled.** It is what makes
   the rows gathered before anybody was asked findable — and therefore deletable — on the day somebody
   decides they should be. The consent flow does not touch them. The token `"GRANTED"` is written in
   exactly one circumstance: an account whose recorded answer is `GRANTED`.
4. **AN ATTRIBUTED DATASET IS NOT A STABLE ONE.** Withdrawing consent **deletes** that account's
   stored rows. A figure computed from attributed rows in March is therefore not reproducible in
   April if somebody withdrew in between, and nothing survives to say what was removed except the
   dated decision in that person's own consent log. That is the price of a withdrawal that means
   something, and it has to be stated in any methods section that quotes an attributed figure.

The full argument for each of the three possible defaults for the *unasked* is in
`docs/DECISION-usage-consent-default.md`, whose banner records that the value did not change on
2026-08-30 and that its justification did.

## 4. Who may read what

| Route | Who | What comes back |
|---|---|---|
| `GET /api/usage/consent/notice` | **anybody, with no session at all** | the versioned text a person is agreeing to. No figures about anybody |
| `GET`/`POST /api/usage/consent`, `POST /api/usage/consent/withdraw` | the account itself, about itself | its own answer and its own decision log |
| `GET /api/usage/me` | the account itself, **and nobody else at any rank** | that account's own use, aggregated by screen |
| `GET /api/usage/me/trail` | the account itself, **and nobody else at any rank** | that account's own requests, in order |
| `GET /api/usage/routes` | Admin and above (`deps.can_read_usage`) | per-screen aggregates. **No user id, by construction** |
| `GET /api/usage/timeline` | Admin and above | requests and error rate over time, by hour or day |
| `GET /api/usage/latency` | Admin and above | p50/p95/p99 per screen |
| `GET /api/usage/clients` | Admin and above | the web/android/api split |
| `GET /api/usage/screens` | Admin and above | busiest and slowest, ranked over a named, capped scope |
| `GET /api/usage/collection` | Admin and above | this method and the deployment's loss counters. No figures about anybody |
| `GET /api/usage/accounts/{user_id}/trail` | **the master admin alone** (`deps.can_read_person_usage`), and only where that account's answer is `GRANTED` | one named person's requests, in order |

The notice endpoint is ungated because a person deciding whether to agree has not agreed yet and, on
the web sign-in screen, has no token — a gate there would mean the only way to see what you are
agreeing to is to agree first.

**The last row is new as of 2026-08-30 and replaces a sentence that used to read "there is no route
by which one account's trail can be read by another account, at any rank".** That sentence was a
promise about *how* such a route could arrive — *"a new route with its own dependency and its own
written argument, not a `?userId=` added to the three above, because a parameter is how a boundary
gets crossed by somebody who never read the paragraph explaining it"* — and every clause of it was
honoured: a new path segment, a new predicate (`can_read_person_usage`) that no other power in
`deps.py` shares, one rank **above** the aggregates rather than beside them, and still no `?userId=`
anywhere in the module. The rank argument is in that predicate's docstring; the argument for gating it
additionally on the subject's own consent is that a trail of somebody who refused, or who was never
asked, must not be readable by anyone — and the route says so with a **sentence** rather than
returning an empty list, because an empty list is read as "this person has never used the app".

**There is no durable audit table for that read.** `/usage/*` is in `UNRECORDED_TEMPLATES`, so the
usage table cannot record it without becoming partly a record of itself. What exists is one server log
line naming the reader, the subject and the window. A real audit row is a schema decision somebody
should take on purpose, and it is named here rather than implied.

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
* **Buffered, not written per request.** `DATABASE_CONNECTION_LIMIT` is **10 in the code and 5 on the
  deployment** — cut to 10 from 40 after a pooler's shared connection budget was exhausted and
  crash-looped this deployment, then set explicitly to 5 in production
  ([ENVIRONMENT.md](ENVIRONMENT.md)). One round trip to the database measured 756 ms against tables
  whose server-side execution is 0.04–0.24 ms, so an INSERT on the request path would take one of
  those connections and add most of a second to every response in order to record that the response
  was slow. Rows are buffered in memory and written 200 at a time, at least every 5 seconds, with a
  5,000-row ceiling.
  **Correction, 2026-09-03:** the database was co-located with the API box on **2026-09-02** and a
  round trip is now one or two milliseconds, so the "most of a second" half of that argument is
  history — `backend/app/services/concurrency.py` says the same about every figure it quotes. The
  buffering stands on what did not change: one statement per request against a pool that went from
  10 to 5, which makes the connection bound tighter rather than looser.
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
  range is **required** on every read; the widest window any of them will answer over is **366
  days**; one request may name at most **50** route templates; a timeline returns at most **750**
  buckets; a trail page is at most **200** rows. Every one of those is **refused with a sentence
  naming the number**, not quietly narrowed or truncated — narrowing would answer a different
  question from the one asked and label the answer with the original dates, and a truncated series
  looks exactly like a period in which nothing happened.
* **Percentiles are computed over the raw column and cannot be reconstructed from anything stored.**
  `avgDurationMs` everywhere else is a count-weighted mean of per-group means — exact as a mean, and
  carrying no information whatever about a tail. A screen averaging 120 ms with a p95 of four seconds
  is broken for one request in twenty and reads as healthy in `/api/usage/routes`. That is why
  `/api/usage/latency` is a separate statement rather than a serializer change, and why
  `/api/usage/screens` says in its own response that its "slowest" ranking is on the mean and cannot
  see a tail.
* **The withholding floor applies to every metric, per emitted row.** A timeline bucket, a latency
  row, a client row and a ranking entry are each withheld on their own count of identified accounts —
  not on the series' count, because the window is chosen by whoever is asking and can be narrowed
  until one person is left in it. Withheld entries are **excluded** from the rankings rather than
  placed in them: `null` sorts as 0 through JavaScript's comparator and through Kotlin's `?: 0`, so a
  naive "slowest first" would put every refused screen at the fast end of the list.
* **No new index was added for any of it.** Every one of these reads is `routeTemplate IN (...)` plus
  a date range, or `userId =` plus a date range — bounded probes on the two indexes that already
  exist. There is still deliberately no index on `createdAt` alone, which is why `/api/usage/screens`
  ranks a **named, capped** set of templates and says so, rather than discovering the set from the
  table.

## 6. What is not yet wired, and would change the numbers if it were

* **`clientApp` is `api` on every row, and it is now VISIBLE that it is.** Neither
  `frontend/lib/api.ts` nor the Android network layer sends the `x-client-app` header, and an
  unlabelled client is honestly an unknown client. Until one of them does, **web and Android traffic
  cannot be told apart** — which matters more than it looks, because "how do they navigate" has
  different answers on a laptop and on a handset that runs offline for a fortnight at a time, and
  averaging the two describes a designer who does not exist. `GET /api/usage/clients` reports the
  split and currently reports one client; that endpoint exists precisely so the gap is visible to the
  people who can close it, which is two header lines.
* ~~**No consent flow, so no attributed rows.**~~ **Fired 2026-08-30.** The flow exists and attributed
  rows exist; §3 has the consequences, including the one that is new — a withdrawal DELETES that
  account's rows, so an attributed figure is not reproducible after one.
* **No client-side instrumentation on either client.** Everything here is server-side. §7 says what
  that excludes. **The consent notice's own list of what is collected would have to change** the day
  a client starts sending anything of its own, and `usage.NOTICE_VERSION` would have to move with it.
* ~~**`usage.withdraw()` exists but its refusal is process-local.**~~ **Closed 2026-08-30.** The
  answer is `User.usageConsent`, read by `resolve_consent` off the row every request already loads, so
  it survives a restart and reaches a second worker; `UsageConsentDecision` is the log, so "granted on
  the 3rd, withdrawn on the 9th" is answerable. `usage.withdraw()` is now the in-process half of a
  larger act — `usage.record_consent()` is the door. **The mid-INSERT window is unchanged:** a batch
  already handed to Prisma when the withdrawal runs cannot be seen by either the purge or the delete.
* **There is no durable audit of who read whose trail.** `GET /api/usage/accounts/{user_id}/trail`
  writes one server log line and nothing else, because `/usage/*` is deliberately unrecorded and the
  table therefore cannot log a read of itself. An audit row is a schema decision nobody has taken.

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
3. **A count of accounts is a floor and never a headcount — and since 2026-08-30 it is no longer
   zero, which is the more dangerous state.** While `userId` was NULL on every row, a per-person
   claim was impossible and therefore could not be made by accident. Attributed rows now exist — a
   consenting account's requests carry its id — so `COUNT(DISTINCT "userId")` finally returns a
   number, and **that number is the count of people who were asked, agreed, and then made a request;
   it is not the count of people who used the platform.** Four populations are missing from it and
   none of them is visible in the figure: every unauthenticated request (sign-in, the public router,
   an expired token) has no account to carry; every row written before the flow shipped has none;
   everyone who has not yet answered is recorded *without* their identity under
   `DEFAULT_UNASKED_COLLECTION`; and everyone who **refused is not recorded at all**, so they are
   absent from the table rather than merely unnamed. Anybody publishing a headcount has to say which
   of those it excludes — a distinct-id count reported as "designers using the platform" describes a
   census of a population it never covered, and reads as authoritative precisely because it is now a
   real number rather than a NULL.
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
* **withdrawing consent** deletes that account's stored rows and drops its buffered ones —
  `POST /api/usage/consent/withdraw` → `usage.record_consent` → `usage.withdraw`, with the one
  remaining gap (a batch already handed to Prisma) named in §6.

Whoever sets a retention period should set it beside the consent notice, because the notice a person
already agreed to says there is not one — `usage.retention_note()` is the single sentence both the
notice and `GET /api/usage/collection` publish, so setting a period means editing that function and
moving `usage.NOTICE_VERSION`, which asks the whole fleet again. That is the intended cost: how long
something is kept is part of what somebody agreed to.

## 9. Where to look

| | |
|---|---|
| The recorder | `backend/app/main.py` — `UsageEventMiddleware`, and the registration comment above `add_middleware` |
| The identity stitch | `backend/app/core/deps.py` — `get_current_user` |
| The writer, consent rules, validation | `backend/app/services/usage.py` |
| The read API | `backend/app/api/routes/usage.py` |
| The gate | `backend/app/core/deps.py` — `can_read_usage` / `require_usage_reader` |
| The table | `backend/prisma/schema.prisma` — `model UsageEvent` |
| The gate on one person's trail | `backend/app/core/deps.py` — `can_read_person_usage` / `require_person_usage_reader` |
| The consent store | `backend/prisma/schema.prisma` — `User.usageConsent*`, `enum UsageConsent`, `enum UsageConsentBasis`, `model UsageConsentDecision` |
| The consent flow | `backend/app/services/usage.py` — `consent_notice`, `consent_gate`, `record_consent`, `withdraw`, `resume` |
| The gate at sign-in | `backend/app/api/routes/auth.py` — `serialize_user` and the block above the token mint in `login` |
| The consent argument | `docs/DECISION-usage-consent-at-sign-in.md`, then `docs/DECISION-usage-consent-default.md` |
| The tests | `backend/tests/test_usage_tracking.py`, `test_usage_consent.py`, `test_usage_metrics.py` |

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
| §3 the consent default for the unasked | `usage.DEFAULT_UNASKED_COLLECTION`, pinned by `test_the_default_policy_records_the_request_and_drops_the_name`. **If that test is edited, this document is out of date** |
| §3 that a grant at the door is recorded as a condition of access | `usage.UsageConsentBasis`, and `GET /api/usage/collection` → `consent.askedAt`. Pinned by `test_a_grant_records_the_circumstance_and_the_text_and_not_only_the_answer` |
| §3 that a reword asks again without reclassifying the answer | `usage.consent_gate` compares the version and `resolve_consent` does not. Pinned by `test_a_new_notice_version_asks_again_without_reclassifying_the_stored_answer` |
| §4 the notice names every read route | `usage.readable_by()`, walked against the router by `test_every_read_route_in_this_module_is_named_in_the_notice`. **A new read route makes the notice people already agreed to false unless it is added in the same commit** |
| §2 the template, never the path | `usage.ensure_route_template` plus the startup allow-list, pinned by `test_one_request_records_one_event_with_the_template_not_the_path` and `test_a_404_records_a_placeholder_and_never_the_url_that_was_asked_for` |
| §2 what is not measured | `UNRECORDED_TEMPLATES` in `backend/app/api/routes/usage.py` — one list, read by the recorder and published by the endpoint, so they cannot drift |
| §4 who may read what | `deps.can_read_usage` / `deps.require_usage_reader`, pinned by `test_the_aggregates_refuse_a_designer_and_say_where_their_own_data_is` and by `test_every_new_aggregate_states_its_caps_and_is_refused_to_a_designer` |
| §4 one person's trail is master-admin-only AND consent-gated | `deps.can_read_person_usage`, pinned by `test_the_account_trail_refuses_every_rank_below_master_admin` (which parametrises over ADMIN on purpose) and `test_the_account_trail_refuses_a_non_consenting_subject_with_a_sentence` |
| §4 the withholding floor | `usage.MIN_IDENTIFIED_USERS_FOR_ROUTE`, pinned by `test_a_screen_too_few_people_used_is_withheld_rather_than_reported_as_a_number` |
| §5 the caps and the batching numbers | the constants block in `backend/app/services/usage.py`; all of them are echoed by `GET /api/usage/collection` under `limits` |
| §5 losses are counted | `GET /api/usage/collection` → `losses`, from `usage.buffer_stats()` |
| §6 `clientApp` is `api` on every row | `grep -r "x-client-app" frontend/lib android/` — **the day that returns a hit, §1 and §6 both change** |

**Review triggers**, each of which invalidates a specific paragraph rather than the document as a
whole: a change to `DEFAULT_UNASKED_COLLECTION` or the arrival of a consent flow (§3, §6, and the
status banner); either client starting to send `x-client-app` (§1, §6); a new column on `UsageEvent`
(§1); a change to `MIN_IDENTIFIED_USERS_FOR_ROUTE`, `MAX_RANGE_DAYS` or `MAX_TEMPLATES_PER_QUERY`
(§4, §5); **any new read route at all** (§4, and `usage.readable_by()`, which is part of the notice a
person already agreed to), or a `?userId=` on any existing one (§4 — and see the paragraph there
before adding one); a change to `usage.NOTICE_VERSION`, which asks the whole fleet again (§3); any
retention or deletion job (§8, and it means editing the notice); client-side instrumentation on either client
(§7.1 and §7.5, which are the two limitations that exist *because* everything here is server-side).

**Known unverified.** The 756 ms round trip and the 40 kB/s field link are quoted from
`backend/app/services/concurrency.py` and `docs/SCALABILITY.md` and were not re-measured for this
document. **The 756 ms is now definitively historic** — the database was co-located on 2026-09-02 and
that module says so above its own figures — and nothing in this repository has been re-timed since
the move, so it is not "unverified" so much as superseded. The loss counters have never been exercised against a real outage — only against a fake
that refuses writes (`test_a_database_that_refuses_the_write_never_reaches_a_request` and
`test_an_outage_abandons_batches_and_never_reaches_the_buffer_ceiling`), so the *counting* is pinned
and the *behaviour of a real Postgres going away mid-flush* is not. **The 5.25-second and 4,720-row
figures in §5 come from that same fake**, driven at 20 requests a second through the real
`run_flush_worker` cadence with the clock simulated rather than waited out: they are exact for the
writer's logic and say nothing about how a real Postgres fails, which can be slower (a connection
that hangs rather than refuses) and would move the first number. Nothing in
§7 is measured at all: it is a list of conclusions the data cannot support, and it is kept true by
being argued from the columns rather than from a benchmark.
