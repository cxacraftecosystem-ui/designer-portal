# Ministry and administrative surfaces are web-only — the one carve-out from Android parity

**Date:** ruled 2026-09-15; recorded 2026-09-16, after 0.0.12 shipped against it.
**Status:** in force.
**Applies to:** the screens that CURATE a design & prototype workshop — the annual plan, the sanction
register, workshop oversight and the ministry desk — and to administrative screens generally.
**Does not apply to** the record forms (artisan, product, tool, process, interview), which still
require both clients. That second sentence is the half of this decision most likely to be over-read,
and §4 is about nothing else.

## The ruling

Owner, 2026-09-15:

> ministry-facing and administrative features are web + backend + database only — no Android parity,
> because that work is done in offices on desktops.

## 1. The rule this is a carve-out from, stated first so the size of the exception is visible

This repository's default is that the handset and the browser do the same things, in the same words.
It is not a preference expressed once; it is enforced in five separate places, and a reader who has
met any of them will read a web-only screen as a gap:

* **Wording and structure come FROM Android**, not the other way round. The frontend skill's
  Android-parity section says so in three words and then lists every dashboard tile, every menu row
  and every button verb the web is obliged to match.
* **The role ladder is machine-checked across all three trees.** `backend/tests/test_role_ladder_parity.py`
  holds a registry of hand-kept mirrors — web, Android and `README.md` — and fails when one lags.
* **A whole document exists because this comparison kept being re-derived by hand and kept coming out
  wrong** — [SKETCHES-PROTOTYPES-PARITY.md](SKETCHES-PROTOTYPES-PARITY.md), whose opening argument is
  that an over-claim in *either* direction is expensive, and asymmetrically so.
* **The offline contract is a parity contract.** The outbox, the dangling-reference re-pick and the
  register caches are specified as "what one client does, the other must" — [AUDIT-2026-08-30.md](AUDIT-2026-08-30.md)
  §7 is an asymmetry table of exactly that, and its own framing is that a fix on either client is a
  specification for the other.
* **The walkthrough decks are held equal field-by-field** by `backend/tests/test_walkthrough_fields_parity.py`,
  which reads the web register and the Kotlin one and compares them.

Against all of that, "this feature is web-only" is a claim that has to be argued rather than
asserted. This file is the argument.

## 2. What the carve-out actually is: the AUTHORING side, and only that

The distinction that makes this safe is not *ministry data versus designer data*. It is **who types
it in versus who reads it in the field.**

| | Authored on | Consumed on |
|---|---|---|
| The annual plan | web only | — (it becomes a workshop, and the workshop is on both) |
| A sanction order | web only | the designer's account, sign-in link and workshop arrive on both |
| Who supervises a workshop (AD / RD) | web only | — (an oversight row is a read scope, not a field) |
| The designer team on a workshop | web only | the workshop appears on the designer's handset because of it |
| The office's artisan roster (.xlsx) | web only | the stage-3 participant rows it creates, on both |
| The ministry desk | web only | — (a launcher) |
| Type of workshop, sanction order number and date | seeded by the ministry door | **edited and printed on both**, stage 1 |

**Every row of the right-hand column is real and shipped.** A workshop opened from an annual-plan
row, from a sanction order or from the oversight screen is created through one function —
`open_design_workshop` — which writes the row, the designers' viewer grants and the stage-1 prefill
seed together. A designer then opens it on a phone in a courtyard and cannot tell which of the three
doors it came through, which is the property that makes the carve-out invisible where it matters.
The office's artisan upload is the sharpest case: an officer fills in a pro-forma at a desk, and what
the designer meets on the handset is a stage-3 roster of named participants with nothing on screen
about spreadsheets.

So the sentence is: **ministry work is authored on a desktop and consumed on a phone**, and only the
authoring half is web-only.

## 3. What is covered

Four web surfaces, their backend routers and their tables:

| Surface | Router | What it curates |
|---|---|---|
| `/annual-plan` | `backend/app/api/routes/annual_plan.py` | the year's planned workshops, and promoting a row into a real one |
| `/sanction-orders` | `backend/app/api/routes/sanction_orders.py` | the order that authorises and funds a workshop, the designers it names, and their first sign-in links |
| `/officers` | `backend/app/api/routes/design_workshop_oversight.py` | who runs, supervises and inspects each workshop; the artisan roster upload |
| `/officers/monitored` | the same router | an officer's read of the workshops they are accountable for |

Plus the ministry desk card on the dashboard (`frontend/components/dashboard/MinistryDeskCard.tsx`),
which is a launcher for those four and nothing else, and the directorate walkthrough deck
(`frontend/components/guide/directorateSteps.ts`), which teaches them.

**The orange surface accent belongs to this carve-out and marks its edge.** `ministry-*` exists as a
ramp in `frontend/tailwind.config.ts` for exactly these screens, and it is a SURFACE accent —
grounds, borders, the header chip, the desk tiles — never an action control. There is no Android
counterpart to the ramp because there is no Android screen to paint. That is a consequence of this
decision, not a second one.

## 4. What this does NOT cover — and this release is the demonstration

**Ordinary record forms still require both clients.** Artisan, tool, product, process and interview
are field capture: they are filled in a courtyard, often with no signal, by a person holding a phone.
A web-only record form would not be a smaller feature, it would be a feature the product's primary
user cannot reach.

0.0.12 is the proof rather than the promise. The same release that shipped four web-only ministry
surfaces also shipped the tool record form's cascade to **all four clients** — this repository's web
and Android, and the field repository's web and Android — across backend, web and handset:
toolkit-name to english-name mirroring; multi-select linked craft over the new `ToolCraft` join and
multi-select linked artisan over `ToolArtisan`; and the centimetre/inch pairing where Height (cm) and
Height (inches), Width (cm) and Breadth (inches) convert at ×2.54 rounded to two places, propagating
only from the box being typed in and never on load. Four clients, one rule, one set of refusal
sentences. Nobody argued for a web-only tool form, and nobody should.

The line between the two halves is **who is standing where**. If the work happens at a desk with a
keyboard and a spreadsheet open beside it, it is in this carve-out. If it happens with an artisan in
front of you, it is not, and no amount of "it is administrative data" changes that — the artisan
roster upload is administrative *and* its output is consumed on the phone, which is why the upload is
web-only and the participant rows are not.

## 5. Why an office is different from a courtyard

The handset exists for one situation this application is unusually specific about: no signal, one
hand, a person waiting. Every hard thing in the Android client — the outbox, the dangling-reference
re-pick, the register caches, the on-device measurement, the offline report — is paid for by that
situation.

None of it is paid for here:

* **A sanction register is read against paper.** The officer has the order in front of them and is
  typing a number off it. A phone-shaped version of that screen is a worse instrument, not a portable
  one.
* **The upload paths are spreadsheets.** Both ministry imports — the annual plan and the sanction
  sheet — take an `.xlsx` an office already keeps. A file picker on a handset is not the constraint;
  the review-and-confirm screen, which is a wide table of proposed rows and refusals, is.
* **The screens are wide by nature.** Oversight puts five panels and a workshop picker on one page
  precisely so a mis-click's refusal is legible beside the control that caused it.
* **Nobody is offline.** The failure these screens must survive is a stale tab, not a dead network,
  and the sanction import is stateless-by-design for that reason: every refusal is re-run per row at
  confirm time, so a stale row is refused rather than recorded wrongly.

Building them on Android would therefore cost the whole offline apparatus — outbox types, local
drafts, conflict rules, a second copy of every refusal sentence — to serve a user who has a desk.

## 6. The three options that were rejected

### Build them on Android too, on the general parity rule

Rejected on cost against use, and the cost is not the screen. It is the second store. Every write
surface on the handset carries an outbox type, a replay rule and a dangling-reference remedy; a
sanction order that queued offline would mint a `User`, an `AccessRoster` row, a `DesignerRoster`
row, a `DesignWorkshop`, a viewer grant and a 72-hour credential link **on replay, hours later**,
against a register that may have gained the same order number in the meantime. The server refuses a
duplicate order number by unique key, so the failure is not corruption — it is a designer's account
that was promised in a courtyard and never existed. That is a bad trade for a screen nobody would
open from a courtyard.

### A read-only Android mirror, "so an officer can check on the road"

Rejected because read-only is exactly what the handset already gives an officer, through a different
door, and a second one would be the widening this repository keeps refusing by name. The three
directorate tiers joined `DESIGN_WORKSHOP_ROLES` on 2026-09-14 — the WRITE set — so an officer who
holds a workshop already opens it on a phone and sees its stages. What `/officers/monitored` adds is
the *supervision* scope, and `backend/app/services/design_workshop_oversight.py`'s own header argues
at length that the oversight table must not be reachable from the viewer predicate or the inspector
one. A handset mirror would be a third reader of that table with a fourth idea of what it confers.

### Manufacture the walkthrough deck so the two clients' deck counts match

Rejected, and this is the one that was nearly shipped. The web registers three guide decks — the
designer's fortnight, the directorate deck and the inspector deck — and the handset registers two.
The Android walkthrough register carries the grep rather than the opinion, run on 2026-09-15 from
`android/app/src/main` over `*.kt`: `annual[ _-]?plan` → 0 hits; `/officers` → 0; `monitored` → 0;
`sanction-orders|sanctionOrders|SanctionOrder` → one hypothetical inside a KDoc;
`canReadWorkshopOversight|OFFICER_ROLES|canSeeMinistryDesk` → 0. Four of the directorate deck's five
cards name screens that do not exist on the handset in any form.

A deck of dead links is **worse than a missing deck**, because what an officer concludes from a menu
row they cannot find is that they cannot find it — not that it was never built. And the fix that
suggests itself is the dangerous one: adding `OFFICER_ROLES` to the handset's `FieldPermissions`
would be widening this app's role surface to make a screen render. The web reached the same
conclusion about the same handset and wrote it into the deck's own recap — *"Four of these five
screens are on the web only"* — which is the honest shape: the deck that exists says where its
screens are.

## 7. What had to be true for the carve-out to be safe, and was checked

1. **The handset loses no capability it had.** It does not. Nothing was removed from Android in
   0.0.12; four screens were added to the web that never existed anywhere.
2. **The handset's role surface was not widened to render anything.** `OFFICER_ROLES`,
   `canReadWorkshopOversight` and `canSeeMinistryDesk` have zero Android hits, deliberately. The
   three directorate tiers exist there as rank constants, display labels, roster filter chips and
   membership of `DESIGN_WORKSHOP_ROLES` — and as no screen, no route and no repository method.
3. **The handset mirrors the DOOR it calls, not the set of all doors.** There are now three creation
   doors for a design workshop with three different gates, and only one of them —
   `POST /design-workshops` — is the one the phone calls. `DW_WORKSHOP_CREATOR_ROLES` in
   `android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCreation.kt` is still
   byte-for-byte `deps.DESIGN_WORKSHOP_CREATOR_ROLES`, and it is still correct: a MINISTRY_ADMIN is
   genuinely refused the door the handset knocks on. Mirroring the *union* of the three gates would
   have made the phone's courtyard pre-check wrong in the permissive direction, which is the one
   direction it must never be wrong in — that pre-check exists so a designer finds out in the
   courtyard instead of a fortnight later at sync.
4. **A new server key is inert on the handset.** The Android JSON reader is configured
   `ignoreUnknownKeys = true` in `android/app/src/main/java/com/designprototype/workshop/data/ApiClient.kt`,
   so the `sanctionOrder` provenance block this release added to the workshop read costs the phone
   nothing and breaks no decode. The carve-out therefore does not need a second API shape, and must
   not grow one.

## 8. What this decision does not license

* **It is not "ministry data is web-only".** The data is on both clients; see §2.
* **It is not permission to skip Android on anything administrative-sounding.** The test is where the
  person is standing, not what the feature is called.
* **It is not a freeze.** The day an officer genuinely needs a register in the field, the deck, the
  predicate and the screen arrive together — which is exactly how the Android walkthrough register
  states its own condition for deleting the paragraph that keeps the directorate deck out.
* **It says nothing about the field repository.** The sibling repository's two clients follow the
  same rule for the same reason, and the tool cascade shipping to all four of them in this release is
  what that looks like in practice.

## How this document is kept true

**This is a decision record: the argument in it is frozen and is not rewritten to agree with later
code.** What has to stay true is the status line — that the four surfaces are still web-only — and
the table below.

| Claim | How to check |
|---|---|
| No ministry screen exists on the handset | From `android/app/src/main`, `grep -rniE 'annual[ _-]?plan\|/officers\|monitored\|sanctionOrders' --include=*.kt`. A hit that is not a KDoc hypothetical means this decision has been reversed and this banner is stale |
| The handset's role surface was not widened for one | `grep -rn "OFFICER_ROLES\|canReadWorkshopOversight\|canSeeMinistryDesk" android/app/src/main` — zero is the expected answer |
| The handset registers two decks and the web three | `WALKTHROUGH_DECKS` in `android/app/src/main/java/com/designprototype/workshop/ui/WalkthroughSteps.kt` against the track register in `frontend/components/guide/tracks.ts`. The Kotlin file's own header carries the dated grep that justifies the difference |
| The directorate deck admits the gap on screen rather than hiding it | `DIRECTORATE_TRACK`'s recap lead in `frontend/components/guide/tracks.ts` — it names the four web-only screens in words a reader meets |
| The record forms are still on both clients | `frontend/components/forms/ToolForm.tsx` against the tool form in `android/app/src/main/java/com/designprototype/workshop/MainActivity.kt`, and the two walkthrough registers held equal by `backend/tests/test_walkthrough_fields_parity.py` |
| The handset's create pre-check still mirrors the door it calls | `DW_WORKSHOP_CREATOR_ROLES` in `android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCreation.kt` against `DESIGN_WORKSHOP_CREATOR_ROLES` in `backend/app/core/deps.py`. They must be equal; they must **not** be widened to cover the annual-plan or oversight doors |
| A new server key stays inert on the phone | `ignoreUnknownKeys` in `android/app/src/main/java/com/designprototype/workshop/data/ApiClient.kt` |
| Who may reach each web surface | [PERMISSIONS.md](PERMISSIONS.md) §4.6 and §5, which are the gates; this document decides only which CLIENT draws them |

**Review triggers:** any proposal to put an annual plan, a sanction register or an oversight screen
on the handset (it must answer §6's three rejections, and the second one in particular); any addition
to the handset's `FieldPermissions` naming a directorate-only predicate; any move to make a RECORD
form web-only, which is §4 and is the opposite decision wearing this one's clothes; a fourth ministry
surface landing on the web, which should be added to §3's table rather than left to be inferred; and
the day the Android walkthrough register's grep returns a hit, which is the tell that somebody has
started building the other half without this file having been reopened.

**Known unverified.** Nobody has asked a Ministry Admin, an Assistant Director or a Regional Director
whether they would use a phone for this work. The ruling is the owner's reading of how these offices
operate, and it is recorded here as a ruling rather than promoted to a finding. The cheapest thing
that would change it is one officer asking for the sanction register on a handset — and §6's second
rejection is the one to re-read first if they do, because a read-only mirror is what they will ask
for and it is the option with the least obvious cost.
