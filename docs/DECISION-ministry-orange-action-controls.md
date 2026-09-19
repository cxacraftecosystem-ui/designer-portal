# The ministry accent reaches the action controls — and the two measurements that did not go with it

**Date:** ruled 2026-09-20; recorded 2026-09-20, in the release that implemented it.
**Status:** in force. It overrules **one clause** of
[DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md) §3 — the one that
calls the orange "a SURFACE accent … never an action control" — and nothing else in that record. Its
carve-out, its client split and its three rejections are untouched, and a dated pointer now sits
beside that paragraph rather than inside it.
**Applies to:** the scoped `[data-surface="ministry"]` block at the end of
`frontend/app/globals.css`, the `cta-ministry` rung in `frontend/tailwind.config.ts`, and the overlay
stamp in `frontend/components/dialogs/FieldDialog.tsx`.
**Does not apply to** the global `:focus-visible` outline, `--bg-0`, `--card`, or any portalled
surface that is not a dialog. §3 is about nothing else, and it is the half of this decision most
likely to be over-read as "a ministry page is orange now".

## The instruction

Owner, 2026-09-20: the ministry's own screens should wear the ministry's own colour, **buttons
included**, and "the pages should have a light accent" — the second clause quoted as it is preserved
on the `.section-band` rule in `frontend/app/globals.css`, because it is the half that decides how
far "light accent" is allowed to reach.

That is a product decision about what the ministry's screens look like. It is not a finding that the
previous rule was wrong, and this record is written so that the difference survives: the RULING is
reversed, the two MEASUREMENTS it rested on are not, and both are answered below rather than waived.

## 1. What was overruled, kept verbatim, because a reversal is not a correction

The old rule was written twice — once as prose in the CSS and once as an executable test — and both
copies are preserved rather than deleted. The same treatment `GuideTrackSwitch`'s header gives its
own overruled argument, and for the same reason: the day somebody proposes putting the rule back,
this is the argument they will be making, and they should meet it in its own words rather than
reconstruct it from a diff.

**The paragraph, still in the ministry block's header in `frontend/app/globals.css`:**

> "── SURFACE ACCENT ONLY. NO ORANGE ON ANY ACTION CONTROL. ──
> `.field-button`, `.field-button-secondary`, `.field-input`, `.file-trigger`, the global
> `:focus-visible` outline, `--purple-700` and `shadow-cta` are ALL deliberately absent from
> this block. Purple-700 remains the only action colour in the product, on a ministry page
> exactly as on every other. The arithmetic is in tailwind.config.ts beside the ramp:
> `ministry-700` #923e0d and `amber-800` #92400e are ΔE 0.004 apart — the same colour — and
> amber is drawn on all four of these screens, so an orange button would sit beside an
> indistinguishable 'Withdrawn' pill and, through `hover:shadow-cta`, throw a saturated PURPLE
> glow while doing it."

**The test, whose name was the rule in one line.** `frontend/e2e/ministry-surface-unit.spec.ts`
carried a case called

> "the ministry accent is spent on no action control"

which enforced it by forbidding the strings `.field-button`, `.field-button-secondary`,
`.field-input`, `.file-trigger` and `:focus-visible` anywhere in the block. The repository's own
shorthand for that rule is **OQ-2**, and it is still named as OQ-2 on the ministry desk card's test
in `frontend/e2e/ministry-desk-unit.spec.ts`, where it survives in the one place it was not
overruled — see §5.

**That file was rewritten, not deleted.** Deleting it would have destroyed the argument and the two
measurements together, and the measurements are the part that is still load-bearing. The test now
reads "the ministry accent reaches the action controls, and stops where it was told to", quotes the
old reasoning inside itself, and asserts the reversal as PRESENCE rather than as permission: "the
block no longer mentions `.field-button`" is invisible in review, so each of the four controls is
required to be there.

**The third copy is frozen and cross-referenced instead.**
[DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md) §3 says the same
thing in prose. That document's own maintenance rule is that "the argument in it is frozen and is not
rewritten to agree with later code", so its paragraph stands exactly as written and a dated note
beside it points here. A decision record that quietly agrees with the current code teaches nobody
what changed.

## 2. What was NOT overruled, and why each survives the reversal rather than being an oversight

### 2.1 The global `:focus-visible` outline stays purple

It was not part of the instruction — buttons were — and it is the one mark that is identical on
every screen in the product. `frontend/app/globals.css` draws `outline: 2px solid var(--purple-700)`
on every focused anchor, button and tabbable element in the app. A reader who tabs across a ministry
page and then a designer's page should not have to learn that the focus ring means the same thing in
two colours. `MinistryDeskCard`'s own tile comment makes exactly this call for exactly this reason,
and it makes it sharper: the desk tiles are `<Link>`s, so an orange `ring` on one would be drawn
immediately INSIDE the purple `outline` the browser already gives it, and the tile would wear two
accent colours at once on a single keyboard focus.

**The blast radius is the real argument.** The one-line way to turn every ministry focus ring orange
is to re-point `--purple-700` inside the scoped block, and that property is not only the focus
outline. `.audio-range:focus-visible` reads it, and so does `.fr-flash-row[data-flash="true"]`'s
"this row, just now" outline. Both are app-wide meanings that happen to be drawn with the app's
action colour; neither is a ministry mark, and neither would survive being repainted by a route.
`--purple-700` is therefore still never re-pointed in that block, and the test asserts it by name.

> **A note on how that guard used to be written, because it is the kind of failure this repository
> collects.** The old assertion was `not.toContain("--purple-700 ")` — with a trailing space. A
> re-pointing declaration is written `--purple-700: oklch(…)` and a usage is `var(--purple-700)`;
> neither contains the token followed by a space. The single guard on the single edit that would
> turn every focus ring in the product orange could never have fired, from the day it was written.
> It is spelled `--purple-700:` now, alongside `--bg-0:` and `--card:`, which already ended in a
> colon and already worked.

### 2.2 `--bg-0` and `--card` are never re-pointed — the mobile address bar has no code path back

This is the constraint that decides how far "the pages should have a light accent" can go, and it is
not a taste argument. Verified by reading `frontend/lib/preferences.ts`:

* `THEME_COLOR` is a two-entry record, `light: "#f7f6fb"` and `dark: "#110f19"`, and its own comment
  says to keep it in step with `--bg-0`. It is the browser-chrome colour.
* `applyPreferences` is the ONLY place that writes it at runtime. It resolves the theme, stamps
  `data-theme` and the three accessibility flags on `<html>`, then walks every
  `meta[name="theme-color"]`, strips the `media` attribute the `viewport` export put there and sets
  `content` to `THEME_COLOR[resolved]`.
* Its only other copy is `PREFERENCES_BOOT_SCRIPT`, the blocking pre-hydration twin, which runs once
  per document load.

So the address-bar colour is rewritten on a PREFERENCE change and on a cold load, and on nothing
else. A client-side navigation to a ministry route does not touch it — there is no navigation hook in
that file at all. Repainting the page canvas orange would therefore produce a screen whose canvas is
orange and whose address bar is lavender, with **no code path in the product able to correct it**,
and the discrepancy would persist for the whole session. The accent that WAS granted is
`.section-band`, which is a band on a page and not the page: `rounded-lg bg-surface-50` everywhere
else, `bg-ministry-50 dark:bg-ministry-950/30` on the surface.

Its first consumer is the scope sentence on `frontend/app/(protected)/ministry-dashboard/page.tsx` —
the line that says whether the caller is reading the whole estate or their own postings — and that
page asks for the recipe by name rather than spelling `bg-surface-50`, precisely so the accent
arrives without the page knowing the ramp exists. The other four ministry screens pick it up on the
day they use the recipe, which is what scoping recipe classes rather than editing pages buys.

## 3. The two measurements the old ruling rested on, and how each is answered

Neither was disputed. Both are real, both were re-checked, and both are now enforced in the test that
used to enforce the rule they supported.

### 3.1 `hover:shadow-cta` is a Tailwind TOKEN and cannot be scoped — answered at the source

`.field-button` carries `hover:shadow-cta`, and `shadow-cta` is a `theme.extend.boxShadow` entry
compiled into a utility class, not a CSS custom property. No `[data-surface="ministry"]` rule can
re-point it. The base token is a literal `oklch` at **hue 305** — purple — so an orange primary
spending it would throw a saturated purple glow on hover. That is not a stylistic quibble; it is the
one thing in the old paragraph that was an arithmetic fact about the build.

The answer is a second rung, `boxShadow["cta-ministry"]` in `frontend/tailwind.config.ts`:

```
"cta-ministry": "0 8px 24px oklch(0.47 0.127 45 / 0.29)"
```

Its comment carries the arithmetic, and it is worth reproducing because each of the three numbers was
derived rather than picked:

* **The geometry is `cta`'s, untouched** — same `0 8px 24px` offset and blur. The two shadows
  therefore sit at the same visual depth, and a reader moving between a ministry page and any other
  meets one elevation language rather than two.
* **Lightness is `ministry-700`'s own 0.47, identical to `purple-700`'s.** The ministry ramp's
  lightnesses are purple's rung for rung, which is why `purple-300 → ministry-300` swaps 1:1
  anywhere in the app.
* **Chroma is the ramp's own gamut-clipped 0.127 and not purple's 0.198.** Purple's chroma is
  outside sRGB at hue 45 and a browser clips it silently — the ladder would stop holding with
  nothing on screen to say so. Every rung of the ramp went through the same re-derivation:
  `min(purple's chroma, 0.94 × the sRGB gamut maximum at hue 45)`.
* **Alpha moved 0.28 → 0.29, and that is the one number that changed.** `ministry-700` is 8.6%
  lighter in relative luminance than `purple-700`, so at equal alpha the orange glow reads weaker
  against the same page. One hundredth restores equal perceived weight. It is written down because
  an unexplained 0.29 beside an 0.28 reads as a typo and gets "fixed" back.

The rule spends `hover:shadow-cta-ministry`, and the test checks both halves: the twin is present,
and — after deleting every occurrence of `shadow-cta-ministry` from the block — `shadow-cta` is
absent. A substring test in one direction would have passed on the wrong string.

### 3.2 `ministry-700` and `amber-800` are ΔE 0.004 apart — answered by ROLE, not by hue

`ministry-700` resolves to **#923e0d** and `amber-800` — the "Withdrawn" and "Awaiting designer
details" pills — to **#92400e**: ΔE 0.004 in OKLab, which is to say the same colour. Amber is drawn
on these screens. The measurement stands; the ramp was not re-derived to move it, because moving it
would break the rung-for-rung relationship with purple that the whole ramp is built on.

**What the old paragraph actually measured is that ministry INK and amber INK are the same colour.**
The rules that landed never put the two inks in the same position:

* **The primary is a FILLED ground carrying WHITE text** — `bg-ministry-700 text-white`. An amber
  status pill is `bg-amber-100` carrying `amber-800` text: a pale ground with dark ink. A dark ground
  with light ink and a pale ground with dark ink are different SHAPES before they are different
  colours, and a status pill also carries a WORD, so rule 5 of the frontend contract — a signal
  carried only by colour is a signal some readers never get — is satisfied twice over.
* **The secondary deliberately keeps `ink-900`.** This is the one control where the collision would
  actually have bitten, because a secondary button IS a pale ground with dark ink — the same shape as
  the pill. Only its border and hover wash take the ramp:
  `hover:border-ministry-300 hover:bg-ministry-50 dark:hover:bg-ministry-950/40`. Two inches apart on
  `/annual-plan` and `/sanction-orders`, one a thing to press and the other a fact about a workshop,
  ministry ink and amber ink would have been indistinguishable. Leaving the label in the app's
  ordinary ink keeps the shapes apart: a pill has no border and no hover. The test asserts this on
  the secondary's rule alone rather than on the block, because "no ministry ink here" is false of the
  block — `.file-trigger` legitimately carries `text-ministry-700` — and true of that one rule.

### 3.3 The states the cascade would otherwise have handed us

Not one of the owner's measurements, but the defect the implementation had to avoid and the reason
both button rules look verbose. `.field-button:hover` compiles to specificity (0,2,0) — exactly the
weight of `[data-surface="ministry"] .field-button` — and the ministry block is LAST in the file, so
it wins the source-order tie on `background-color`. A rule that set only the resting ground would
have painted the same orange on hover (no hover response at all on the surface) and the same orange
when DISABLED, erasing the disabled affordance on every button on five screens. Every state the base
recipe carries is restated: `hover:bg-ministry-800`, `disabled:bg-line-200`, `disabled:text-ink-500`,
`disabled:shadow-none`. The test pins all four.

## 4. The portal consequence: a dialog now stamps itself

`FieldDialog` `createPortal`s its overlay to `document.body`, which is OUTSIDE `<main>` by
construction, so no ancestor scope can reach it. The ministry block's header records that as
deliberate for dropdown panels and toasts — those are app chrome that happens to have been raised
from a ministry page. **A dialog is not that**, and the reversal is what made the difference visible:
the confirming button inside a dialog is the second half of the act whose trigger the reader just
pressed on the page, so an unstamped overlay would ship an orange trigger on the page and a purple
primary in the dialog it opens. One action in two accent colours is worse than either colour alone.

So the overlay carries `data-surface={ministrySurface(pathname) ? "ministry" : undefined}`, derived
from the pathname rather than passed in — every existing call site is correct without being edited,
and the next one cannot forget. No `!blocked` guard is owed here, unlike AppShell's stamp, because a
dialog can only be opened by a page that is already being served.

**The dialogs this fixes**, found by grepping `field-button` under the two ministry route folders and
keeping the ones that actually render a `FieldDialog` — three, each with a `.field-button` primary
and a `.field-button-secondary` beside it:

| Dialog | Its title on screen | Raised from |
|---|---|---|
| `frontend/app/(protected)/annual-plan/UploadPlanDialog.tsx` | "Upload the annual plan" | `/annual-plan` |
| `frontend/app/(protected)/annual-plan/PromoteDialog.tsx` | "Open this workshop" | `/annual-plan` |
| `frontend/app/(protected)/sanction-orders/UploadSanctionDialog.tsx` | "Upload a sheet of sanction orders" | `/sanction-orders` |

**And the near miss that shows why the fix had to be in `FieldDialog` rather than at the call sites.**
`frontend/app/(protected)/sanction-orders/SanctionImportReview.tsx` carries the same pair of controls
— a secondary "Cancel" and a `.field-button` reading "Record N orders" — and needed nothing, because
it is drawn as a `panel` section on the page rather than in a modal. Its own header says why: two
hundred proposed rows do not belong in a dialog. So two controls that look identical in the source,
one folder apart, sat on opposite sides of a portal boundary, and nothing on screen distinguished
them. Deriving the stamp from the pathname inside the component is what makes that distinction stop
mattering.

## 5. What is still purple on a ministry page, and is not a gap

Every one of these is outside the scope by a mechanism, and each was decided rather than missed:

* **Portalled dropdown panels** — `AnchoredPopover` / `SearchableSelect` — **and toasts.** They
  `createPortal` to `document.body` like the dialog does, and unlike the dialog they are app chrome
  rather than the second half of an act begun on the page. Left purple on purpose.
* **The island nav's active pill.** Global chrome shared with every route, rendered before `<main>`
  and outside the scope entirely. A nav that changed colour under you would be saying something about
  the nav rather than about the page.
* **The `RouteLocked` refusal panel**, and by a different mechanism: `frontend/components/AppShell.tsx`
  computes `const ministry = !blocked && ministrySurface(pathname);`, so the attribute is stamped only
  when the page it guards is actually being served. The padlock is shown to somebody who is **not** a
  ministry account, and painting the ministry accent onto it puts the mark in front of exactly the
  person it is not for. The three self-refusal panels inside `/officers` carry the same argument.
* **The ministry desk card's tiles' focus ring**, on `/dashboard`. The card opts into the surface by
  putting `data-surface="ministry"` on its own panel element — which works because every rule in the
  block is written twice, as a descendant and as a self-match — and its tiles take the ramp on chip,
  hover border and walkthrough link while keeping `focus-visible:ring-purple-700`. **OQ-2 survives
  intact on that card**, stated forward rather than backward: there is no button and no input on it
  today, and its test forbids one from being painted from this ramp later.
* **`--bg-0` and `--card`**, which is §2.2, and which is the reason the accent on a page is a band.

## 6. What this decision does not license

* **It is not "the ministry surface is orange".** It is five recipe classes plus the ones that were
  already there. A bare descendant rule on a utility class — `[data-surface="ministry"] .text-purple-700`
  or the same for `.bg-purple-50` — remains forbidden, and the reason is unchanged by this ruling:
  `StatusBadge` paints NEEDS_REVISION and IN_PROGRESS with a shared purple treatment, and the SAME
  workshop row is drawn on `/design-workshops`, on `/design-workshop-inspections` and on
  `/officers/monitored`. Such a rule would make one workshop read purple on a designer's screen and
  orange on an officer's.
* **It does not widen the surface.** Which routes are ministry is `ROUTE_GUARDS`' `ministry: true`
  flag in `frontend/lib/permissions.ts`, five rows today — `/ministry-dashboard`, `/annual-plan`,
  `/officers`, `/officers/monitored` and `/sanction-orders`. This decision changes what the accent
  is spent on, not who gets it.
* **It does not reach the field repository or the handset.** There is no Android counterpart to the
  ramp because there is no Android ministry screen to paint, which is
  [DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md)'s consequence and
  not a second decision.
* **It does not waive a `dark:` pair.** The ramp is literal and does not invert. `text-ministry-700`
  on a dark `bg-card` is 2.44:1, under the 4.5:1 floor; `dark:text-ministry-300` is 10.06:1. The
  primary button is the one rule that owes none, and the absence is argued rather than assumed: white
  on `ministry-700` measures 7.20:1 and is the same 7.20:1 in either theme, because neither the
  ground nor the ink is themed — the identical position `.field-button`'s own `bg-purple-700
  text-white` is in.

## How this document is kept true

**This is a decision record: the argument in it is frozen and is not rewritten to agree with later
code.** What has to stay true is the status line — that the reversal is in force and that §2's two
survivals are still absent from the block — and the table below. Both halves are executable.

| Claim | How to check |
|---|---|
| The accent reaches `.field-button`, `.field-button-secondary`, `.field-input` and `.file-trigger` | `frontend/e2e/ministry-surface-unit.spec.ts`, "the ministry accent reaches the action controls, and stops where it was told to" — asserted as PRESENCE, so a rule quietly dropped fails |
| The hover glow is the hue-45 twin and never the purple one | The same test: `shadow-cta-ministry` present, and `shadow-cta` absent once the twin's name is deleted from the string |
| Both button rules restate hover and disabled, so the (0,2,0) tie cannot erase the disabled affordance | The same test, over `.field-button`'s own rule rather than the whole block |
| The secondary keeps `ink-900`, which is where ΔE 0.004 would have bitten | The same test: no `text-ministry-` inside the secondary's rule |
| `:focus-visible`, `--purple-700`, `--bg-0` and `--card` are still never re-pointed | The same test's final loop, spelled with the trailing colon that makes it able to fire |
| The mobile address bar argument still holds | `THEME_COLOR` and `applyPreferences` in `frontend/lib/preferences.ts`. If a navigation hook ever writes `theme-color`, §2.2 is reopened and an orange canvas becomes arguable |
| The `cta-ministry` arithmetic is still the ramp's own | `boxShadow` in `frontend/tailwind.config.ts` against the `ministry` ramp above it: lightness 0.47 shared with `purple-700`, chroma 0.127 gamut-clipped at hue 45, alpha 0.29 |
| Every ministry ink and ground carries its `dark:` pair, and the primary's absence is the argued one | `frontend/e2e/ministry-surface-unit.spec.ts`, "every ministry rule carries its dark pair" |
| AppShell stamps the surface only on a page it is serving | The same file, "AppShell stamps the attribute, and only on a page it is serving" — it pins the `!blocked` half by its source line |
| A dialog raised from a ministry page carries the surface with it | The same file, "a dialog raised from a ministry page carries the surface with it", against `frontend/components/dialogs/FieldDialog.tsx` |
| The desk card stays a SURFACE accent, and OQ-2 still forbids an action control on it | `frontend/e2e/ministry-desk-unit.spec.ts`, "the accent is a surface accent: the tiles turn, the focus ring does not" and "every ministry ink and ground on the card carries its dark pair" |
| The block still targets recipe classes only | `frontend/e2e/ministry-surface-unit.spec.ts`, "the scoped block never reaches a utility class" |
| Which routes are ministry, and who may open them | `ROUTE_GUARDS` in `frontend/lib/permissions.ts`, and [PERMISSIONS.md](PERMISSIONS.md) — this document decides only what colour they are drawn in |

**Review triggers:** any proposal to re-point `--purple-700`, `--bg-0` or `--card` inside the scoped
block (it must answer §2, and §2.2 in particular, because that one has no fix on the client); any new
portalled surface that raises the "is this app chrome or the second half of an act?" question §4
answers for dialogs; any action control added to the ministry desk card, which is the one place OQ-2
is still in force; a sixth ministry surface, which inherits all of this without an edit and should be
checked for a `.section-band` it could be using; and any proposal to restore the old ruling, which
should start by reading §1's two quotations rather than this file's summary of them.

**Known unverified.** Nobody has put an orange primary and an amber "Withdrawn" pill in front of an
officer and asked. §3.2's answer is a reasoning from role and shape — filled dark ground with white
ink versus pale ground with dark ink, plus a word on the pill — and it is recorded as a defence of a
measurement rather than promoted to a finding. The cheapest thing that would change it is one officer
on `/sanction-orders` reading a secondary button as a status; §3.2 is the paragraph to reopen if they
do, because the secondary is the control the two inks were kept apart on.
