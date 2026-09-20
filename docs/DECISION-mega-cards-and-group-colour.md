# DECISION — the dashboard's sections became collapsible, colour-coded mega cards

**Status:** ruled and shipped, 2026-09-20.
**Owner ruling, verbatim:**

> Each of the megacard is supposed to be a larger card that would stay minimized unless it is clicked
> upon, colour code so that it is easier for people to understand and navigate, there should be two
> cards in a row for a megacard only on the larger screens, and 1 card on mobile screens, implement
> this for both web and android, use the mango colour from this place for the ministry one,
> https://transaction-flow-analyser.vercel.app/ … in addition to all of this, this functionality
> should be there in the record questionnaire page as well … My questionnaires, and the card size for
> this one currently is also different, fix that.

Sister documents: [DECISION-ministry-orange-action-controls.md](DECISION-ministry-orange-action-controls.md)
(the ministry accent's own boundary), [DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md)
(why the ministry screens have no Android half, which this ruling does **not** overturn).

---

## 1. What changed

`/dashboard` had twenty-one tiles under four headings, in one column, at every width. The headings
grouped the tiles and nothing else: a reader looking for one destination scrolled past twenty.

Each heading is now a **mega card** — `frontend/components/dashboard/MegaCard.tsx`:

| | Before | After |
|---|---|---|
| State | always open | **collapsed on a first visit**, remembered afterwards |
| Rail | `grid gap-6`, one column at every width | `grid items-start gap-6 lg:grid-cols-2` |
| Tiles inside | `grid-cols-2 md:grid-cols-3` | `grid-cols-1 sm:grid-cols-2` |
| Colour | none — every chip `bg-purple-800` | one **tone** per group |
| Shut-card content | — | title, one-line note, and a count of what it holds |

`/questionnaire` got the same four cards (capture, recorded interviews, completion matrix, builder)
and the same rail. Android got the same four groups, the same collapse and the same tones.

---

## 2. The colour boundary, which is the only part of this that needed a ruling

Non-negotiable 1 of the frontend contract is **purple-700 is the only action colour**, with the five
`ministry: true` routes as the single scoped exception. "Colour code the cards" collides with that
rule head-on if it is implemented the obvious way, so it was implemented the way
`components/dashboard/MinistryDeskCard.tsx` already spends the `ministry` ramp, and no other way.

**A tone may paint exactly three things:**

* an `aria-hidden` **icon chip** — `bg-X-100 text-X-700 dark:bg-X-950/40 dark:text-X-300`
* a **hover border** — `hover:border-X-300`
* the **chevron ink**, which is the same ink as the chip

**A tone may not paint:** a button, an input, a focus ring, a left-edge accent rule, or a card
ground. The global `:focus-visible` outline stays `var(--purple-700)` on every control on every
screen — a coloured `ring-2` would be drawn immediately *inside* that purple outline and the card's
primary control would wear two accent colours on one keyboard focus.

**The left-edge rule is specifically forbidden.** `app/globals.css` carries a tombstone for the
"ministry spine" that was removed on 2026-09-17 at the owner's direction; a group tone on a
`border-l-2` is the same idea wearing a different word. `dashboard-megacard-unit.spec.ts` asserts its
absence by name.

**And the tone is never the only channel.** Non-negotiable 5: *a signal that only exists as colour is
a signal some readers never get.* Every mega card carries three non-colour channels — the group
**title**, its one-line **note**, and a **count** of what is inside it — so a reader who cannot
separate teal from indigo loses nothing at all. That is also why each group has a distinct **icon**:
it is what a reader navigating by shape picks out of a rail of shut cards.

---

## 3. The five ramps

Four new literal OKLCH ramps in `frontend/tailwind.config.ts`, derived on **purple's own eleven
lightnesses** with `chroma = min(purple's chroma at that rung, 0.94 × the sRGB gamut maximum at this
hue)` — the identical rule the `ministry` ramp was built with, so a 700 is the same *weight* in every
family and swapping a chip changes hue only.

| Group | Ramp | Hue | Chip ink (light) |
|---|---|---|---|
| For designers | `purple` (existing) | 305 | filled `purple-800`, white ink |
| Records | `archive` | 195 | `#136868` |
| Miscellaneous | `errand` | 255 | `#0e59aa` |
| Admin | `steward` | 15 | `#a71439` |
| Ministry | `mango` | 71 | `#7c500e` |

**None is named after a stock Tailwind scale**, and that is not aesthetics. A colour key that
collides with a stock scale **deep-merges** with it: `amber` in this very config is the standing
proof — only 100/500/800 are brand, and `amber-50` silently resolves to a stock value that does not
pair with them. `ministry` is not called "orange" for the same reason. Each of these is named for its
**scope**.

---

## 4. The mango, measured rather than eyeballed

The owner named a site. Its stylesheet carries:

```
:root  --primary: 39 100% 50%   --accent: 25 95% 55%   --background: 39 100% 97%
.dark  --primary: 39 80% 40%    --accent: 25 75% 45%
```

Converted:

| | hsl | hex | OKLCH |
|---|---|---|---|
| primary, light | `39 100% 50%` | `#FFA600` | `oklch(0.794 0.171 71.2)` |
| primary, dark | `39 80% 40%` | `#B87E14` | `oklch(0.637 0.129 75.1)` |
| accent, light | `25 95% 55%` | `#F97A1F` | `oklch(0.715 0.180 49.5)` |

So **hue 71 is the mango**, and `mango-500` — `oklch(0.648 0.131 71)` — reproduces that site's dark
primary to about ΔL 0.011 / ΔC 0.002. Its light primary sits between rungs 300 and 400 and is
deliberately *not* pinned to a rung: a ladder with one rung off it is a ladder nobody can reason
about.

Its accent lands at hue 49.5 — which is this repository's **existing `ministry` ramp** at hue 45. The
two palettes already agreed, which is the reason the next paragraph is not a compromise.

### ⚠ `mango` EXTENDS the ministry surface. It does not replace the `ministry` ramp.

Three reasons, and the first is disqualifying on its own:

1. **Contrast.** `#FFA600` against white is **1.96:1**. The ministry ramp's action colour has to
   carry white text on a filled ground — `ministry-700` does, mango's bright rungs cannot.
2. **Blast radius.** The hue-45 ramp is what every `dark:` pair, the `cta-ministry` shadow, the
   `[data-surface="ministry"]` block in `globals.css` and three test files are written against.
   Re-pointing it repaints all five ministry routes at once.
3. **It was never asked for.** The ruling says *"use the mango colour … for the ministry one"* — "the
   ministry one" is the ministry **mega card**, which is one card on one screen.

So `ministry` stays the action colour on the five ministry routes, and `mango` is the ministry mega
card's navigation **mark**: a chip, an ink, a hover border.

---

## 5. Collapsed by default, and the one exception

The ruling is obeyed exactly on `/dashboard`: `useMegaCards("dashboard")` is passed no defaults, so
every card is shut on a first visit. The reader's own choices are then remembered in `localStorage`,
because a returning reader who opens Records every time should not have to open it every time — the
first visit obeys the ruling, every visit after it obeys the reader.

**`/questionnaire`'s capture card opens by default, and that is a stated exception rather than a
softening.** That page is a *work* surface, not a menu:

* `/questionnaire?new=1` is what the dashboard tile and `components/guide/steps.ts` both link to, and
  it expects to land on the create form. The page does not read `?new=1` at all — it relies on the
  form being visible.
* `e2e/questionnaire-capture.spec.ts` asserts the first instrument section is visible **with no
  clicks**.

A collapsed form would send both to a shut card with nothing on screen to say the page had loaded.
The other three cards on that screen are shut.

### What this cost, said out loud

`e2e/feature-entry-points.spec.ts` asserted that the **Map** and **Consolidated questionnaire** tiles
were visible on `/dashboard` with no interaction — both are `misc` tiles, and `MegaCard` *unmounts*
its panel while collapsed. That spec exists because *"a feature a user cannot find is a feature that
was not built"*, so weakening it silently would have been the wrong repair.

It was made **stronger** instead. It now asserts that the group card exists, that it is `aria-expanded="false"` on a first
visit (the ruling itself, which nothing else was watching), that opening it is one click, and only
then that the tile and its link are there. A build that shipped the cards open, or filed `Map` under
a group that does not exist, or broke the toggle, now fails — and none of those three were observable
before.

---

## 6. Two in a row, and where it does not apply

`lg:grid-cols-2` on the rail, one column below it. `items-start` on the rail and `h-fit` on the card
are **both** required: `grid` stretches every cell by default, so without them a collapsed card
renders as a tall empty box beside an open one, which reads as a section that failed to load rather
than one that is closed. The card cannot see the rail and the rail cannot see the card, which is why
it takes two declarations.

Inside a mega card the tiles are `grid-cols-1 sm:grid-cols-2`. The old `grid-cols-2 md:grid-cols-3`
was written for a full-width section and packs three tiles into what is now half a page.

**On `/questionnaire` two of the four cards are `span="full"`,** and the reason is content that
genuinely cannot be halved rather than content that would prefer not to be: the capture form is ONE
`<form>` element by contract (two parsers slice from its opening tag to the first `</form>`), and the
recorded-interviews table carries `min-w-[980px]`. Either in half a page is a horizontal scrollbar
inside a card inside a rail. The completion matrix and the builder take half the rail each.

**The completion matrix moved down that page** as a consequence — it was "top of the page, collapsed
by default" and is now third. A half-width card cannot sit above a full-width one without leaving a
hole beside it, and the two things a reader comes to `/questionnaire` to *do* are record an interview
and find one they recorded. It is still collapsed, still one click, and now sits beside the builder,
which is the other panel an administrator rather than an interviewer opens.

---

## 7. "My questionnaires" was the wrong size, and the owner was right

It shipped on 2026-09-20 as a full-width `<Link>` **sibling** of the tile grid. Measured, it differed
from a `DashboardCard` in eight ways at once:

| | the row | a tile |
|---|---|---|
| width | 100% of the section | 33% (md) / 50% |
| radius | `rounded-md` (12px) | `rounded-lg` (16px) |
| ground | opaque `bg-card` | glass `bg-card/70` + `GlassSurface` |
| layout | `flex items-start` row | `flex flex-col` |
| chip | 36px pale, 18px glyph | 40px filled, 20px white glyph |
| title | `text-sm` | `text-base leading-snug` |
| buttons | none | one or two |
| resting height | ≈83px | ≈142px / ≈184px |

It is now a **grid child** drawn by `components/dashboard/EntryPointCard.tsx`, which restates
`DashboardCard`'s shell exactly. `dashboard-megacard-unit.spec.ts` compares the two shell strings, so
a change to the tile's padding, radius, ground or shadow that is not mirrored fails.

### ⚠ It may still never become a tile

`android/app/src/test/java/com/designprototype/workshop/DashboardTileParityTest.kt` asserts
`WEB_ONLY == emptyList()` in **both** directions —
every web tile label must also be an Android card label — and the handset has no `EntryMode` for
`/questionnaires`. A tile here would go red on `main` rather than on the change that added it,
because that suite is not in the frontend gate. `dashboard-tile-parity-unit.spec.ts` pins
`"/questionnaires": false` in a closed FAMILY literal from this side.

Its two-line description is kept, and it is the one thing this card has that a tile does not. That
sentence is the only place in the product that tells a designer the two instruments are different
things, and `app/(protected)/questionnaires/page.tsx` and `components/guide/steps.ts` both depend on
that distinction being stated rather than inferred.

---

## 8. Android

`/dashboard` is on both clients, so the mega cards are on both. `DashGroup` mirrors `TILE_GROUPS`
title-for-title and note-for-note, that same parity test was extended to compare the web's
`group:` scalar against the Kotlin `group =` argument tile by tile, and the collapse is
`AnimatedVisibility` with `snap()` under reduced motion.

**No Android ministry screen was built and none should be.**
[DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md) §3 stands: *"There is
no Android counterpart to the ramp because there is no Android screen to paint."* The 2026-09-20 note
on that document overruled only its "never an action control" clause. The `mango` ramp is therefore
web-only, exactly as `ministry` is.

---

## 9. What this did not do

* **It did not make `/questionnaire` record against a chosen custom form.** That is a schema change
  and is argued in `components/questionnaire/InstrumentPicker.tsx`'s header:
  `QuestionnaireInterview` has no instrument pointer, `QuestionnaireResponse.questionId` is
  `Restrict`-FK'd to the global question table, and `artisanSetKey` is `@unique` repository-wide so
  one artisan set answering two instruments would fold both onto one row.
* **It did not touch the `tiles` array.** Its order is read as raw text by three parsers in three
  languages; grouping is a scalar on each tile and a renderer, exactly as it was.
* **It did not reinstate the ministry spine.**
* **It did not re-point `--bg-0` or `--card`.** A tinted canvas would leave the mobile address bar
  the wrong colour with no code path in the product able to correct it; `THEME_COLOR` is rewritten on
  a preference change and a cold load only, never on a navigation.

---

## How this document is kept true

**This is a decision record: the argument in it is frozen and is not rewritten to agree with later
code.** What has to stay true is §2's boundary, §4's refusal to replace the `ministry` ramp, and §5's
one stated exception. Every row below is executable.

| Claim | How to check |
|---|---|
| Each of the four ramps is eleven rungs on ONE hue, literal OKLCH, wired into `colors` | `frontend/e2e/dashboard-megacard-unit.spec.ts`, "declares eleven rungs on one hue" and "every ramp the config declares is actually wired into `colors`" |
| No ramp is named after a stock Tailwind scale | The same file, "no ramp is named after a stock Tailwind scale, because those DEEP-MERGE" |
| `mango-500` is still the named site's dark primary, and `ministry-700` is untouched | The same file, "the mango is the one the owner named, and it did not replace the ministry ramp" |
| The tone reaches no button, input or focus ring, and no left-edge rule | The same file, "no tone ever reaches a button, an input or a focus ring" and "the left-edge accent rule is not reinstated" |
| Every pale chip carries its `dark:` pair | The same file, "every pale tone chip carries its `dark:` pair" |
| The class strings are written out and never interpolated | The same file, "the tone maps are written out in full and never interpolated" — Tailwind scans source text, so `bg-${'{'}tone{'}'}-100` emits no CSS and fails silently |
| The colour is never the only channel | The same file, "the colour is never the only channel" and "each dashboard group declares a distinct tone and its own icon" |
| Collapsed on a first visit, on `/dashboard` | `frontend/e2e/feature-entry-points.spec.ts` — `openGroup` asserts `aria-expanded="false"` before it clicks |
| Remembered afterwards, and a failed write cannot clobber a real choice | `frontend/e2e/dashboard-megacard-unit.spec.ts`, "the hook starts with everything shut and only then reads storage" and "a failed write cannot clobber the reader's real choices" |
| The one exception is `/questionnaire`'s capture card and nothing else | `frontend/e2e/questionnaire-instrument-picker-unit.spec.ts`, "the capture card opens on a first visit and the other three do not" — it also asserts the dashboard passes no defaults |
| Two in a row on `lg`, one below; `items-start` and `h-fit` both present | `frontend/e2e/dashboard-megacard-unit.spec.ts`, "the rail is two columns from `lg` and one below it" and "`items-start` is present, or a shut card stretches to its open neighbour" |
| The card does not clip its own toggle's focus ring | The same file, "the card does not clip its own focus ring, and the panel clips itself" |
| Glass does not nest | The same file, "the mega card is a panel and not glass, because glass may not nest" |
| Reduced motion is honoured on the JS path | The same file, "reduced motion is honoured on the JS path, which CSS cannot reach" |
| "My questionnaires" is a grid child at `DashboardCard`'s exact shell, and still not a tile | The same file, §6's four tests — the shell string is compared against the real one, so an unmirrored change to the tile fails |
| An empty group still renders nothing | The same file, "an empty group still renders nothing at all" |
| The `tiles` array is untouched and the three parsers still find it | The same file, §5; plus `frontend/e2e/dashboard-tile-parity-unit.spec.ts` and `backend/tests/test_annual_plan_web_surface.py` |
| Android's groups match the web's, tile for tile | `android/app/src/test/java/com/designprototype/workshop/DashboardTileParityTest.kt` |
| The ministry surface is still web-only | [DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md) §3, which this ruling does not touch |
