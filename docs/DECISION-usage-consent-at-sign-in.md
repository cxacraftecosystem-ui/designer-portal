# Asking for usage consent at the door: what is stored, and why a turnstile is recorded as one

> ## STATUS, 2026-08-30: BUILT, AND THE OPEN ITEM IS AT THE BOTTOM OF THIS PAGE.
>
> * **Built:** four columns on `User` (`usageConsent`, `usageConsentAt`, `usageConsentBasis`,
>   `usageConsentVersion`), the append-only `UsageConsentDecision` log, the versioned notice, four
>   `/api/usage/consent*` routes, and the gate reported at sign-in on both credential paths.
>   Migration `20260830090000_usage_consent_and_decision_log`.
> * **Decided here:** that agreeing is a **condition of access**, that this is therefore **not
>   freely-given consent**, and that the system records that fact rather than concealing it.
> * **Superseded, in part:** `docs/DECISION-usage-consent-default.md` said "NOT built, and not
>   decided by this note: what is asked, when it is asked, on which screen, and who may see the
>   results." Three of those four are decided here. Its argument is not rewritten — it is a decision
>   record — and its own review triggers named this document's arrival.
> * **STILL OPEN, and the owner should decide it explicitly:** what happens to the rows gathered
>   before anybody was asked. See the last section. Nothing in this wave touched them.

---

## The requirement, and the problem with it

The owner asked for a blocking checkbox at sign-in: refuse it and you cannot use the product.

**That is a condition of access, not freely-given consent.** Under GDPR Art. 7(4) and the
DPDP-style regimes this deployment sits under, consent is not free where performance of the service
is made conditional on it and the processing is not necessary for the service. Recording browsing
navigation is not necessary to run a design workshop; the product worked without it until 2026-08-29.

**The requirement is not refused.** It is implemented, and the system is built so that a later reader
can see exactly what kind of agreement each one was. The alternative — storing `GRANTED` and nothing
else — would have been this codebase forging the precise distinction it built a three-state enum to
preserve. It would truthfully record an answer and misleadingly imply a choice.

Three things make it defensible, and all three are code rather than prose.

### 1. The record carries the CIRCUMSTANCE, not just the answer

`UsageConsentBasis` is stored on the account **and on every decision-log row**:

| Value | What it means |
|---|---|
| `REQUIRED_AT_SIGN_IN` | The turnstile. The person could not proceed without it. |
| `OFFERED_IN_SETTINGS` | A free choice, on their own settings screen, where saying no costs nothing. |

A regulator, an ethics board or a methods section can therefore see that nine thousand grants were a
turnstile and that the withdrawals were the free ones. Without this column the system would record
`GRANTED` truthfully and imply consent falsely, which is worse than not asking.

**A fourth `UsageConsent` member was the obvious alternative and is refused.** It would break
`collection_plan`'s three-way rule — the whole of the collection policy, in one function — and the
documented meaning of `UsageEvent.consentState`, in one edit. The circumstance is a second fact about
one answer, so it is a second column.

### 2. A real withdrawal exists and costs nothing

`POST /api/usage/consent/withdraw`, from the account's own settings, at any time:

* records `REFUSED` with basis `OFFERED_IN_SETTINGS` and a dated log row;
* stops recording immediately (`collection_plan`'s `REFUSED` branch records **nothing at all**, not
  even an anonymous row);
* throws away anything observed and not yet written;
* **DELETES** the rows already stored.

**It does not sign anybody out and removes no capability.** `consent_gate` reports
`required: false` for a `REFUSED` account precisely so a client does not put the question back in
front of somebody who has just answered it.

That asymmetry is the whole design. The gate makes you agree to get in; the settings card lets you
take it back and keep working. **If withdrawing also locked the account out, the withdrawal would be
theatre and the flow would be indefensible.**

### 3. The text is versioned, and a reword asks again

`usage.NOTICE_VERSION` is stored on the account and on every log row. When it moves,
`consent_gate` reports `required: true` and names the version the person actually agreed to, so a
reword cannot silently claim agreement to wording nobody saw.

**What a version bump does NOT do is reclassify the stored answer.** `resolve_consent` still reads
`GRANTED`, and recording continues under the answer already given. Flipping a stale grant to
`NOT_RECORDED` would mean a wording change moved every aggregate's population mid-window and blanked
every designer's own `/usage/me` overnight — to enforce something the version column already answers
by being stored. What the version prevents is a record *claiming* somebody agreed to text they never
saw; that is prevented by keeping the version, not by deleting the agreement.

---

## The gate ADMITS the sign-in and reports "consent required". It does not refuse.

This is the decision most likely to be second-guessed, so here is the whole argument. The blocking
half lives in the **clients**, which will not leave the consent screen until
`user.usageConsentGate.required` is false. The server hands over a session and says plainly that an
answer is owed.

1. **A 403 at the door is a gate nobody can get through.** The only way to record an answer is
   `POST /api/usage/consent`, which needs a bearer token. Refuse before minting one and an
   un-consented account can never consent — the product becomes permanently unusable for every
   account that has not answered, which on the day this ships is every account there has ever been.
   That alone settles it.
2. **The client cannot show the consent screen without a session.** It needs the notice, and for
   somebody who answered an older version it needs the answer they gave, so the screen can say "this
   has changed" rather than "please agree".
3. **THE BREAK-GLASS MASTER ADMIN MUST NOT BE REACHABLE BY THIS.** `assert_access_admits` exempts
   that account by name because *"a break-glass that lives in the same table it is protecting against
   is not a break-glass"*, and the argument for widening the platform allow-list to everybody rests
   entirely on there always being one account that can get in and let people back in. A consent
   refusal at this door would be a **second** lockout, on a column no allow-list screen can edit,
   reachable by a bug in one boolean — and it would need its own exemption, i.e. a second break-glass
   to keep in step with the first. Reporting needs no exemption at all.
4. **Nothing can strand an account, so there is nothing for an admin to undo.** The answer is the
   account's own to give, at a route that needs no permission from anybody, with no admin step in
   between.

If a server-side belt is ever wanted, it belongs as a dependency on the **protected** routes, never
as a refusal at this door — for reason 1.

**There is deliberately no route by which an administrator records somebody else's usage consent.**
A consent an admin can enter on a colleague's behalf is not a consent.

---

## `DEFAULT_UNASKED_COLLECTION` was revisited on 2026-08-30 and is UNCHANGED. Its reason is not.

The value stays `ANONYMOUS`. **The argument for it has been replaced**, and that is worth recording
because a constant whose justification has quietly expired is the kind of thing nobody re-reads.

**The old argument (2026-08-29):** *"`NOTHING` is the safest and it is not free … choosing `NOTHING`
means this system still cannot answer either half, for as long as it takes to design, build and ship
a consent screen on web and on Android."* That argument is spent. The screen exists.

**The new argument (2026-08-30):** the unasked population is no longer "the whole fleet". It is two
groups, and the larger one by far is **people who are not signed in at all** — the sign-in page
itself, the public router, every unauthenticated request. `NOTHING` would stop recording them, and
that would silently delete the one capability the schema names by name as worth having: *"the sign-in
page is slow for the people who cannot get in"*, which `MIN_IDENTIFIED_USERS_FOR_ROUTE` also has a
whole paragraph protecting. A request with no account attached identifies nobody, so there is nobody
for a consent question to protect on it.

The second group is small and transient: the handful of requests between a session being minted and
the consent screen being answered. Those are recorded without a name, exactly as before.

**What did change is that `GRANTED` now fires.** Rows from a consenting account are attributed, with
`consentState = "GRANTED"`, which is the first time that token has ever been written. `GET
/api/usage/me` reports something for the first time. The prediction in
`METHODOLOGY-usage-instrumentation.md` §3 — *"`GET /usage/me` will report nothing for anybody until a
consent flow ships"* — stopped being true on 2026-08-30.

---

## What was built, in one table

| Piece | Where |
|---|---|
| Three-state answer, per account | `User.usageConsent`, enum `UsageConsent` |
| When they answered | `User.usageConsentAt` (the client's clock where one reported it) |
| Turnstile or free choice | `User.usageConsentBasis`, enum `UsageConsentBasis` |
| Which text | `User.usageConsentVersion`, minted by `usage.NOTICE_VERSION` |
| The history, append-only | `UsageConsentDecision` — decision, basis, noticeVersion, note, **two clocks** |
| The notice itself | `usage.consent_notice()`, computed from the policy in force |
| The gate a client renders | `usage.consent_gate()`, returned on `/auth/login` and `/me` as `usageConsentGate` |
| The one write door | `usage.record_consent()` — two writes, then the withdrawal on a refusal |
| Stopping collection | `usage.withdraw()` (buffer purge + delete) and `usage.resume()` |

**Two clocks, on `DwWorkshopConsentDecision`'s model.** `recordedAt` is when the box was ticked as
the client reported it; `createdAt` is when the server heard. Android signs people in
offline-capable contexts, so the two can differ by a fortnight, and collapsing them fabricates one —
*"a signature dated to the day it was filed."* A `recordedAt` more than fifteen minutes in the future
is refused rather than corrected, because a substituted timestamp is a fabricated fact about when
somebody consented.

**One trap that has no analogue in the audio path**, recorded because it would have shipped silently:
`usage._WITHDRAWN` is a process-local set checked *ahead of* the consent rule. A person who withdraws
and later agrees again stays in it for the life of the worker unless something removes them — the
column would read `GRANTED`, every aggregate would be right, and not one row would be written.
`usage.resume()` exists for that, and `record_consent` calls it on every grant.

---

## Who may read what, after this wave

| Route | Who |
|---|---|
| `GET /api/usage/consent/notice` | **anybody, with no session** — a person deciding whether to agree has not agreed yet |
| `GET/POST /api/usage/consent`, `POST /api/usage/consent/withdraw` | the account itself, about itself, and nobody about anybody else |
| `GET /api/usage/me`, `GET /api/usage/me/trail` | the account itself, and nobody else at any rank |
| `GET /api/usage/routes`, `/timeline`, `/latency`, `/clients`, `/screens`, `/collection` | Admin and above (`deps.can_read_usage`) — aggregates only, no user ids |
| `GET /api/usage/accounts/{user_id}/trail` | **the master admin alone** (`deps.can_read_person_usage`), and only where that account's own answer is `GRANTED` |

The last row is new and replaces the "**Nowhere**" bullet that stood in `routes/usage.py` until this
wave. That bullet was a promise about *how* such a route could arrive: *"a new route with its own
dependency and its own written argument, NOT a query parameter added to the three above."* Every
clause was honoured — new path segment, new predicate that no other power shares, one rank **above**
the aggregates, and no `?userId=` anywhere in the module. The argument for the rank is in
`deps.can_read_person_usage`; the argument for gating it additionally on the subject's own consent is
that a trail of somebody who refused, or who was never asked, must not be readable by anyone, and the
route says so with a **sentence** rather than returning an empty list.

**There is no durable audit table for that read.** The usage table cannot record it — `/usage/*` is
in `UNRECORDED_TEMPLATES` so the dataset is not a record of itself — so what exists is one server log
line naming the reader, the subject and the window. A real audit row is a schema decision somebody
should take on purpose, and it is named here rather than implied.

---

## THE OPEN ITEM: the rows gathered before anybody was asked

Every `UsageEvent` written between 2026-08-29 and 2026-08-30 carries `consentState` NULL, which the
schema defines as **nobody was asked**. **Nothing in this wave backfills them, and nothing must.**
That NULL is the only thing that makes them findable — and therefore deletable — as a set.

The owner has one decision to make, and it is answerable in one line:

* **Keep them.** They are anonymous (`userId` NULL under `ANONYMOUS`), they answer "which screens
  and where is it slow" for the period before the flow, and every figure drawn from a window
  containing that period has to say that it mixes pre-consent and post-consent rows.
* **Delete them.** `DELETE FROM "UsageEvent" WHERE "consentState" IS NULL AND "createdAt" < '<the
  day the flow shipped>'` — and the record starts on the day people were asked.

This document does not decide it. `DECISION-usage-consent-default.md` named it as an open item and as
a review trigger, and it is still open.

---

## How this document is kept true

**This is a decision record: the argument in it is frozen and is not rewritten to agree with later
code.** What has to stay true is the status banner.

| Claim | How to check |
|---|---|
| Agreeing is a condition of access, and the record says so | `usage.UsageConsentBasis`, and `GET /api/usage/collection` → `consent.askedAt` |
| The gate admits rather than refuses | `auth.login` returns a token for a `NOT_RECORDED` account. Pinned by `test_the_sign_in_gate_admits_rather_than_refuses_so_the_answer_can_be_given` |
| The break-glass master admin cannot be locked out by it | `test_the_sign_in_gate_cannot_lock_out_the_break_glass_master_admin` |
| Withdrawing costs nothing and deletes what was stored | `usage.record_consent`'s REFUSED branch → `usage.withdraw`. `test_a_withdrawal_stops_collection_empties_the_buffer_and_deletes_what_was_stored` |
| A reword asks again without reclassifying the answer | `usage.consent_gate` compares the version; `resolve_consent` does not. `test_a_new_notice_version_asks_again_without_reclassifying_the_stored_answer` |
| `DEFAULT_UNASKED_COLLECTION` is still `ANONYMOUS` | `usage.DEFAULT_UNASKED_COLLECTION`, and `GET /api/usage/collection` → `consent.unaskedPolicy` |
| Nothing backfills the pre-consent NULLs | `grep -n "consentState" backend/prisma/migrations/20260830090000_usage_consent_and_decision_log/migration.sql` — it appears only in a comment |
| No route records one person's consent on another's behalf | `POST /usage/consent` takes the account from the bearer token; there is no `userId` in the path or the body |

**Review triggers:** any change to `NOTICE_VERSION` (it means the notice moved, and the whole fleet is
asked again); a fifth `/usage` read route, which makes `usage.readable_by()` — and therefore the
notice a person already agreed to — false unless it is added in the same commit; a decision about the
pre-consent rows; a retention period being set; and any proposal to refuse the sign-in rather than
report on it, which must argue against the four numbered reasons above and in particular against the
break-glass one.
