# Recording how designers use the platform: what is collected before anybody has been asked

> ## STATUS, 2026-08-30: THE FLOW SHIPPED. THE DEFAULT BELOW IS UNCHANGED AND ITS REASON IS NOT.
>
> **This banner was rewritten on 2026-08-30. Everything below it is the 2026-08-29 argument and is
> deliberately unaltered** — see "How this document is kept true" at the foot: this is a decision
> record, the argument in it is frozen, and only the status line moves.
>
> * **The consent flow now exists**, which is the review trigger this document named first. Four
>   columns on `User` (`usageConsent`, `usageConsentAt`, `usageConsentBasis`, `usageConsentVersion`),
>   the append-only `UsageConsentDecision` log, a versioned notice, four `/api/usage/consent*` routes
>   and a gate reported at sign-in. Migration `20260830090000_usage_consent_and_decision_log`. The
>   argument for its shape — and in particular for recording that a grant at the door is a **condition
>   of access** rather than a free choice — is in **`docs/DECISION-usage-consent-at-sign-in.md`**,
>   which is now the document to read first.
> * **`DEFAULT_UNASKED_COLLECTION` is STILL `ANONYMOUS`**, and it was revisited rather than left
>   alone. **Its justification has been replaced.** The argument below — "`NOTHING` is the safest and
>   it is not free … choosing `NOTHING` means this system still cannot answer either half, for as long
>   as it takes to ship a consent screen on two clients" — is spent, because the screen exists. The
>   argument now is narrower and stronger: **the largest unasked population is people who are not
>   signed in at all**, `NOTHING` would stop recording them, and that would silently delete the one
>   capability the schema names by name as worth having — *"the sign-in page is slow for the people
>   who cannot get in"*. A request with no account attached identifies nobody, so there is nobody for
>   a consent question to protect on it.
> * **`GRANTED` now fires, for the first time.** Rows from a consenting account are attributed and
>   carry `consentState = "GRANTED"`. Until 2026-08-30 that token had never been written.
>   `GET /api/usage/me` reports something for the first time.
> * **Both "known unverified" items at the foot of this page are CLOSED.** The refusal is no longer
>   process-local — it is a column, read by `resolve_consent` off the row every request already
>   loads, so it survives a restart and reaches a second worker — and the decision log exists, so
>   "granted on the 3rd, withdrawn on the 9th" is answerable. The mid-INSERT window is unchanged and
>   is still stated honestly.
> * **STILL OPEN, and named in this document's own review triggers:** what happens to the rows
>   gathered before anybody was asked. They carry `consentState` NULL, **nothing backfills them**, and
>   the decision belongs to the owner. See the last section of the sign-in document.
> * **Nothing is deleted by the flow's arrival.** `DwDictationConsent` and the audio consent path are
>   untouched; this remains a second, separate consent question about a different kind of data.

**Decision:** collect the request and not the person, until somebody with the authority to decide has
decided otherwise. Recorded because the default here is a choice that is very easy to make by
accident — every analytics library on the market ships attributed-by-default — and because the
person who should be making it is not the person who wrote the instrumentation.

## Why this is a consent question at all

Everything else this repository holds about a person is something that person typed in. This is the
first thing it holds that the system **noticed about them without being asked**. That is a new
category of personal data in this codebase, and the codebase already has a considered answer for the
one other place it collects something similar: `backend/app/services/dictation_consent.py`, which
gates an artisan's recorded voice leaving a device behind an explicit, per-workshop, three-state
answer with an append-only decision log.

The asymmetry is the point. It would be a strange system that refuses to send an artisan's voice
anywhere without a recorded answer, and builds a per-colleague trail of everyone who works on it
without asking anyone. Whatever is decided, it should be decided on purpose.

## The three options, and what each one costs

`UnaskedCollection` in `backend/app/services/usage.py` names all three as real, selectable values so
that the alternatives are visible rather than hypothetical. Changing the default is changing one
constant.

| Option | What is recorded | What it answers | What it costs |
|---|---|---|---|
| `NOTHING` | nothing at all | nothing, until a screen ships on web **and** Android | requirement 22-25 measures nothing in the meantime |
| `ANONYMOUS` **(in force)** | route, status, duration, client — `userId` NULL | "which screens are reached" and "where is it slow", in full | cannot answer "what did this person do last week" |
| `ATTRIBUTED` | the above **plus** the account id | also the per-person question | builds a trail of named colleagues who were never asked |

Under **all three**, `consentState` stays NULL — including `ATTRIBUTED`. NULL means nobody was asked,
and that is true whether or not the row carries a name. **No row this module writes ever claims a
consent that was not given.** The token `"GRANTED"` is written in exactly one circumstance: an
account whose recorded answer is `GRANTED`. There are none today.

## Why `ANONYMOUS` and not one of the other two

`NOTHING` is the safest and it is not free. The requirement is to understand how designers move
through the platform and where it is slow, and nothing in this system can currently answer either
half — there is no page-view, screen-view, session or navigation record anywhere, on either client.
Route, status and duration with no name attached answer the "where is it slow" half completely and
the "how do they move" half in aggregate, and they do it with nobody's name in the table.

`ATTRIBUTED` is what most products do by default and it is the one thing this module will not do by
default, for the asymmetry described above.

So the default is the option that buys the measurement without the personal data. It is a floor, not
a ceiling: the owner may raise it to `ATTRIBUTED` or lower it to `NOTHING`, and neither change needs
this document rewritten — only the constant and this table's bolded row.

## The collection rule, once there *are* answers

`collection_plan()` is the whole policy, in one function:

* **GRANTED** — recorded and attributed, `consentState = "GRANTED"`.
* **REFUSED** — **nothing is recorded, not even anonymously.** This is the sharpest of the three
  decisions. Keeping a refuser's rows as "anonymous" is what a system does when it wants the number
  more than it wants the answer: on a route two people use, anonymity is a label rather than a
  property, and a refusal that changes nothing the person would recognise is a preference, not a
  permission.
* **NOT_RECORDED** — whatever the default above says.

### What a refusal costs the numbers, and who has to say it

Because a refusal stops collection rather than only stopping attribution, **every aggregate this
system produces describes everyone who did not refuse, and not everyone.** Anybody publishing these
figures has to say that in the same breath as the figures. It is the honest cost of a real refusal
and it is written here so that nobody discovers it while writing a methods section.

The same paragraph applies to two further limits that are properties of the measurement rather than
of the consent rule, and both are easy to misread:

* **`durationMs` measures the server and nothing else.** No network time, no render time, nothing a
  person actually waited for. A reader who forgets this will conclude the app is fast while a
  designer watches a spinner on a 2G connection in a courtyard.
* **A route touched by fewer than five identified accounts is reported as `null`, not as a number.**
  `MIN_IDENTIFIED_USERS_FOR_ROUTE`, matching `workshop_analytics.MIN_WORKSHOPS_FOR_RATE`. "Who opened
  the artisan screen at 2 a.m." is answerable from a page labelled *aggregates* the moment a route
  has one user in the window, and the window is chosen by whoever is asking. Rows with no account
  attached do not count towards that floor — they identify nobody, so they protect nobody, and
  counting them would suppress the sign-in routes, which are the ones the schema names as worth
  watching.

## What a withdrawal has to reach, and what is still missing

`usage.withdraw(user_id)` follows `dictation_consent.cancel_pending_transcriptions`, whose argument
transfers exactly: recording a refusal closes the gate on future collection, but a request observed
four seconds ago is sitting in an in-process buffer and would be written **after** the person asked
to stop. So a withdrawal:

1. stops new events for that account immediately;
2. empties that account's rows out of the buffer, and filters them again in the moment before a write;
3. **deletes** the rows already stored — not blanking `userId`, because the schema refuses `SetNull`
   by name: it would make NULL mean both "nobody was signed in" and "this person is gone", and every
   count of unauthenticated traffic would then quietly include ex-colleagues' requests. Deleting
   reuses the answer `onDelete: Cascade` already gives for the same data.

**Two pieces are missing and are not hidden.** There is no durable store for the answer — the refusal
lives in a process-local set and does not survive a restart or reach a second worker — and there is
no decision log, so "granted on the 3rd, withdrawn on the 9th" is not answerable. Both need a
migration this workflow may not write. Whoever builds the flow should read `DwWorkshopConsentDecision`
and `dictation_consent.py` first: three states and not a boolean; a log that survives a withdrawal
because a withdrawal must not erase the answer earlier collection was made under; two clocks, one for
when the person answered and one for when the server heard it; and refusals that are **sentences
naming a next move**, never codes.

## The route template, and why it is not the path

`"/design-workshops/{workshop_id}/stages/{stage_key}"`, never
`"/design-workshops/3f9c…/stages/sketches"`. Two reasons, both load-bearing and both in the schema:

1. **A raw path is a shadow copy of who looked at whose fieldwork.** Every record id in this API
   travels in the path, so a table of raw paths is a per-designer reading list of other people's
   artisans, sketches and interviews — assembled with no access check, kept for ever, and readable by
   anybody who can query the table. There is no query-string column for the same reason: `?q=` carries
   whatever somebody typed into a search box.
2. **Without it nothing aggregates.** A million distinct paths group into a million groups of one.

`usage.py` refuses the value rather than trusting its caller, in two layers of deliberately different
strength. `register_known_templates()` installs the app's own route table as an allow-list, and while
it is populated nothing outside it can be stored — a guarantee. With no allow-list installed, shape
rules apply: cuids, UUIDs, long hex runs, bare numbers and anything not beginning with a letter are
refused, which covers every id shape this API mints. Those rules **cannot** catch a record id that
happens to read like a word, which is why the allow-list is the wiring to prefer and why this
paragraph says so rather than claiming the regex is enough. Verified 2026-08-29 against every route
declared in `backend/app/api/routes/` — 271 decorator path literals, 176 of them distinct: no false
rejections.

## Why the writes are batched, in numbers

`DATABASE_CONNECTION_LIMIT` is 10, cut to it from 40 after a pooler's shared connection budget was
exhausted and crash-looped this deployment. One Prisma round trip measured **756 ms** against tables
whose server-side execution is 0.04–0.24 ms (`backend/app/services/concurrency.py`), so cost is
latency, paid once per statement and nearly independent of row count.

* **200 rows per `create_many`.** The amortised connection cost is 1/200th of a request. At 1,000 the
  marginal gain is nothing and a row waits five times longer to become durable.
* **Flushed every 5 seconds** when the size threshold has not been reached — the same cadence as
  `MEDIA_QUEUE_INTERVAL_SECONDS`, so an operator reads one background rhythm off the box. A clean
  shutdown drains fully; an unclean one loses at most five seconds.
* **A 5,000-row ceiling, and the buffer drops rather than grows.** About 5 MB, or roughly four minutes
  of total database unavailability at 20 requests a second. An unbounded queue behind a database that
  has gone away is a memory leak with a timer on it, and instrumentation gathered for a research paper
  must never be the reason the field's app falls over. **Dropped rows are counted and logged** — the
  oldest go first, so the window describing the outage survives — because a dataset that loses rows
  without saying how many is a dataset that cannot be checked.
* **A failed flush never reaches a request.** It is caught, logged at warning, retried once and then
  abandoned. The response is already on the wire; a measurement that can 500 a designer's sketch
  upload is worse than no measurement.
* **`createdAt` is stamped when the request finished, not when the row was written.** The column's
  `@default(now())` is deliberately unused: a row can sit in the buffer for seconds normally and for
  minutes during an outage, and dating rows to the flush would destroy the ordering that is the entire
  reason per-request rows are kept instead of a daily rollup.

## How this document is kept true

**This is a decision record, so the argument in it is frozen and is not rewritten to agree with later
code.** What has to stay true is the *status line*: which of the three options is actually in force.
That lives in exactly one place — `usage.DEFAULT_UNASKED_COLLECTION` in
`backend/app/services/usage.py` — and it is reported by the running deployment at
`GET /api/usage/collection` under `consent.unaskedPolicy`. If that value and this document disagree,
**the code is right and this file's banner is stale**; fix the banner, and keep the argument below it
exactly as it was, including whichever case it lost.

| Claim | How to check |
|---|---|
| `ANONYMOUS` is in force | `usage.DEFAULT_UNASKED_COLLECTION`, and `GET /api/usage/collection` on the running box. Pinned by `test_the_default_policy_records_the_request_and_drops_the_name` in `backend/tests/test_usage_tracking.py` |
| A refusal produces no row, not an anonymous one | `usage.collection_plan` — the `REFUSED` branch returns `record=False` |
| `GRANTED` is written in one circumstance only | the same function: it is the only branch that sets `consent_state` at all |
| ~~No consent flow exists~~ **A consent flow exists, since 2026-08-30** | `grep -n "usageConsent" backend/prisma/schema.prisma` now returns hits, which this row said would be "the day this whole document needs re-reading" — it was, and the banner above is the result. `usage.resolve_consent` reads `CONSENT_ATTRIBUTE` off the `User` row and the column is there. `GET /api/usage/collection` → `consent.flowExists` is `true`, and `backend/tests/test_usage_consent.py` pins the column names against the generated Prisma model |
| A withdrawal empties the buffer as well as the table | `usage.withdraw`, and the filter in `usage.flush` that runs again in the moment before the write. **Since 2026-08-30 that function is the in-process half only** — `usage.record_consent` is the door, and it writes the durable answer and the log row before calling it |
| The route path literals checked against the shape rules | re-run the count: `grep -rEoh '@router\.(get\|post\|put\|patch\|delete)\(\s*"[^"]*"' backend/app/api/routes/*.py \| wc -l` (271 on 2026-08-29; 176 distinct). The number moves whenever a route is added, so the claim that matters is that no literal is *rejected* — `test_building_the_app_installs_the_real_route_table_as_an_allow_list` asserts that over the live route table rather than over a count |

**Review triggers:** any change to `DEFAULT_UNASKED_COLLECTION`; ~~a consent column reaching the
`User` model~~ (**fired 2026-08-30**); ~~a consent screen shipping on either client~~ (**the server
half fired 2026-08-30; the two client screens are still to come**); a decision about what happens to
the rows gathered before anybody was asked; a retention period being set. The first three would make
the banner false within a single commit, which is why they are named first — and two of them did.

**Known unverified — BOTH CLOSED ON 2026-08-30, and the third is unchanged.** The refusal was
process-local, with no column to persist it in; there is now `User.usageConsent`, read by
`resolve_consent` off the row every request already loads, so it survives a restart and reaches a
second worker. There was no decision log; there is now `UsageConsentDecision`, so "granted on the 3rd,
withdrawn on the 9th" is answerable. **The mid-INSERT window is unchanged and still stated honestly:**
a batch already handed to Prisma when the withdrawal runs cannot be seen by either the purge or the
delete. Every other path is closed — `record_event` refuses a withdrawn account immediately and
`flush` filters the batch again in the moment before it writes.

**And one that is new, recorded here because nothing else would have found it.** `usage._WITHDRAWN`
is a process-local set checked *ahead of* the consent rule, so it is a refusal that can outlive the
answer that produced it: without `usage.resume()`, an account that withdraws and later agrees again
stays in the set for the life of the worker, with the column reading `GRANTED`, every aggregate
correct, and not one row written. `record_consent` calls `resume()` on every grant, and
`test_agreeing_again_after_a_withdrawal_actually_resumes_recording` pins it.
