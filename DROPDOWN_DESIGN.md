# DROPDOWN_DESIGN.md

**One design for requirements 9-13, 30 and 31.** They are one cluster because they are one question —
*what does a control that offers a list do when the list is not the whole list?* — and answering it
three times produces three incompatible answers to one question, which is how a reader learns that
none of the sentences on these screens mean much.

Written from three reconnaissance passes plus a verification pass over the cited source at
`7c60e81`. Every claim carries a `path:line`. Where a recon report's line numbers disagreed with the
tree they were re-derived and the verified number is the one printed here — in particular the two
roster models, which the roster recon cited 39 and 125 lines low.

**On the line numbers.** They were read from the working tree on 2026-08-29, which already carried
uncommitted work from six concurrent workflows — including edits to `schema.prisma`, `access.py`,
`designers.py`, `design_workshops.py`, `deps.py` and `MainActivity.kt`. **Every citation names a
symbol or a quoted sentence as well as a line**; where the two disagree, the symbol is the claim and
the number is a hint. Re-derive with `codegraph explore` or a grep for the quoted text before editing,
and never delete a line because its number matched.

**STATUS, 2026-08-31: MOST OF THIS DOCUMENT IS BUILT. Read a section against the tree before
building it.**

This line said *"Nothing in this document has been implemented"* from the day it was written until
2026-08-31, and it was true then and false for months afterwards. It is corrected here rather than
deleted because the failure it caused is the one worth naming: a headline claiming a shipped design
is unbuilt sends the next reader to build it again, and a second implementation of a picker
vocabulary is exactly the condition §1.2 is about — six label shapes and nine "none" strings, arrived
at one honest re-implementation at a time.

What is in the tree, checked against it rather than remembered:

| | Where it landed | How to check in one command |
|---|---|---|
| §2's single vocabulary | `frontend/lib/workshopOptions.ts` and `android/.../ui/WorkshopOptions.kt` — the four "none" constants, the label builder, the group headings, the six sentences | both files exist |
| E1 `serverQuery`, E2 `bulk`, E4 `noneLabel` | `components/ui/SearchableSelect.tsx` | `grep -n "serverQuery\|bulk?:\|noneLabel" components/ui/SearchableSelect.tsx` |
| E3 `searchable` + `emptyMessage` on Kotlin | `ui/SearchableSelect.kt`; the threshold now decides in exactly one place | `grep -rn "options.size >= SEARCH_THRESHOLD" android/app/src/main` returns ONE hit |
| §3.1 / §3.2 (B1, B2, B3) | computed asterisks, the honest offline sentence, and `fetchedAt` beside `address-reference.json` | `grep -n "REFERENCE_CACHE_STAMP_FILE" ui/LocationFields.kt` |
| §3.2's bundled state list | `BUNDLED_STATES`, derived from `report/ReportMap.STATE_SEATS` rather than typed out | `grep -n "INDIAN_STATES_AND_UNION_TERRITORIES" android/app/src/main -r` |
| §3.3 C1 on Android | `loadCachedRegister` and its four wrappers in `MainActivity.kt`, over `DwReferenceStore` | `grep -n "loadCraftRegister" MainActivity.kt` |
| §3.3 on the web | `frontend/lib/referenceCache.ts` — the fourth IndexedDB database, `model__owner__filter`, no expiry, an empty fetch never overwrites a populated cache | that file exists |
| §3.4 | `MyAiKeysScreen` and `SearchScreen` both route through `SearchableSelectField`; `AccessRosterScreen` stays a menu, as this document pins it | `grep -rn "ExposedDropdownMenuBox" android/app/src/main` returns nothing but a comment |
| §3.5 | the six sentences, byte for byte, on both clients | `workshopListNotice` / `registerListNotice` / `addressListNotice` |
| §3.7 O1 | Android's `danglingField` / `repick` / `unfiled`; and now the web's `repickOutboxEntry` and the outbox's third button | `grep -n "danglingField" frontend/lib/offline.ts data/Offline.kt` |
| §4 | `sort` and `dir` on both roster routes, plus the designer roster's roles/institutions grammar | `grep -n "sort: str | None" backend/app/api/routes/access.py` |

**What is NOT claimed by this table.** It says a mechanism is in the tree; it does not say every one
of the twenty-one controls in §1.1 has been migrated onto it. §2.8's *"`pageSize === RENDER_CAP`,
always"* and §2.3's one label shape are call-site sweeps, and a sweep is finished only when the grep
in §5.1 comes back empty — which is why §5.1 is written as greps rather than as a list of tickboxes.
Run them; do not trust this paragraph, or the one above it, which is the mistake this whole block is
a correction of.

---

## 0. The five rules everything below is an application of

These are not new. Each already exists in the repository, in code, with the failure it prevents
written beside it. This document's only claim is that reqs 9-13, 30 and 31 are all the same five
rules applied to different surfaces.

| | Rule | Where it is already written | The failure it prevents |
|---|---|---|---|
| **R1** | **Empty means everything, BY ABSENCE.** Never by an all-ticked state. | `components/WorkshopScopeSelect.tsx:129-135` (`queryValue` returns `undefined`, not `""`); `services/record_filters.py:56-81` and `:243-275` (`None` = do not filter); `components/search/SearchFilters.tsx:51-64` | "Nothing ticked" and "everything ticked" both meaning "all" leaves a filter with two spellings for one state and no way to tell a default from a deliberate choice. |
| **R2** | **A field may only be mandatory where it is answerable.** | `components/forms/LocationFields.tsx:880` and `:892-893` — both required flags end in `&& options.length > 0` | A required closed list with no members refuses the submit before the offline outbox is ever reached. The interview and its photographs die with the tab (`LocationFields.tsx:176-179`). |
| **R3** | **The control must say WHICH it is doing.** A silently empty picker reads as "there are none". | `IMPLEMENTATION_PLAN.md:311-313`; `components/data/cappedList.ts:33-37`; `SearchableSelect.kt:741-751` (two facts, two sentences) | Absence read as non-existence. Named by the frontend contract as "the single most repeated bug class in this repo" (`.claude/skills/field-repo-frontend/SKILL.md:22-43`, non-negotiable 10). |
| **R4** | **Every cap, truncation or narrowing is stated on screen, with the number.** | `components/data/cappedList.ts:33-37`, `:136-166`; `DesignWorkshopSelect.tsx:216-225`; `DesignerRosterScreen.kt:277-293` | 196 workshops → 100 fetched → 80 drawn → silence. `cappedList.ts:13-25` counts the live tables: MediaFile 2530, Artisan 749, Workshop 196, Craft 178. |
| **R5** | **Filtering and searching happen where the corpus is.** | `app/(protected)/admin/access/page.tsx:31-41` ("there is **no `.filter()` over a fetched page anywhere in this file**"); `design-review/page.tsx:322-330` | A client-side box over a server-truncated page answers "No matches" about records that exist. |

Two more, specific to this cluster, derived below and used throughout:

- **R6 — A stale ACCESS list is wrong in the permissive direction.** A cache of "which workshops may
  I submit to" still reads a revoked grant as a grant. `WorkshopRepository.kt:3918-3923`,
  `DesignWorkshopPicker.kt:46-49`, `AdoptLocalDraftDialog.tsx:30-40`. **This makes req 31's option
  "cache the last successful fetch" FORBIDDEN for the two workshop pickers, not merely unattractive.**
- **R7 — An empty picker and a dangling foreign key are opposite failures with opposite remedies.**
  The first is fixed *before* the save, by offering something answerable or standing the field down.
  The second is fixed *after* the drain, on the record already on the device, by re-picking.
  `Offline.kt:141-186`. They must never be collapsed into one message.

---

# 1. INVENTORY

## 1.1 The web's workshop pickers — 21 controls over 2 tables

"Workshop" names **two tables** and every control belongs to exactly one.
`Workshop` = the ordinary field/training visit, gated by `WorkshopAssignment` through
`resolve_workshop_access`, carrying a submission window. `DesignWorkshop` = the 22-stage
design-and-prototype record, gated by `load_workshop_or_404` (creator / admin /
`DesignWorkshopViewer`). The argument for why one column cannot carry both is
`components/forms/DesignWorkshopSelect.tsx:6-26`. **Four record forms mount BOTH, stacked**
(`ArtisanForm.tsx:1125` + `:1136`).

### A. `DesignWorkshop` pickers — options from `GET /design-workshops`

| # | Control | Asked / drawn | Search | Cap stated | Empty-vs-failed |
|---|---|---|---|---|---|
| 1 | `components/forms/DesignWorkshopSelect.tsx:239` — mounted 6× (`ArtisanForm:1136`, `ProductForm:849`, `ToolForm:958`, `ProcessForm:1131`, `media/page.tsx:562`, `questionnaire/page.tsx:1076`) | 80 / 80 (`:74`) | ~~`searchable={false}` `:262` + `capHint` `:263`~~ → the panel's own box, wired to `GET /design-workshops?search=` | yes, through `workshopCutSentence` | ~~**no — a failed list IS an empty list.** `.catch(()=>null)` then `page?.items ?? []` at `:168,176`, so a network failure renders *"You are on no design workshop yet"* `:257`~~ → **CLOSED.** Now a three-state `WorkshopListState` through `lib/workshopOptions`; pinned by `dropdown-sweep-unit.spec.ts` ("a failed design-workshop read stops rendering as an account with no design workshops") |
| 2 | `design-workshops/page.tsx:1495` `ContinueOnAllocatedWorkshop` | 50 / 50 (`:1465`) | `searchable` `:1513` | never | collapsed — the control returns `null` at `:1487` and vanishes |
| 3 | `sketches-and-prototypes/page.tsx:736` | **100 / 80** (`:236`) | `searchable` forced | prints "the first 100 of N" while drawing 80 — the dead band `selectFilter.ts:81-86` exists to kill | correct, `null` and `[]` kept apart |
| 4 | `design-review/page.tsx:659` | 80 / 80, `CHOOSER_PAGE = RENDER_CAP` `:245` | `false` + a server `SearchInput` above, 300 ms | yes | correct — five states, `ListFailure` splits unreachable from refusal |
| 5 | `settings/DesignWorkshopViewersPanel.tsx:609` | **100 / 80** (`:96`) | `searchable` | same dead band, `:650-654` | correct |
| 6 | `settings/DesignWorkshopInspectorsPanel.tsx:528` | 80 / 80 | `false` + server search | yes | correct; pins the chosen row `:334-337` |
| 7 | `designworkshop/AdoptLocalDraftDialog.tsx:372` | 80 / 80 | `false` + server search | yes, plus an offline "partial" panel `:388-394` | correct — five branches `:251-263` |
| 8 | `questionnaires/page.tsx:418` | **100 / 80** (`:147`) | `searchable` | **never, at either level** | nothing at all |
| 9 | `questionnaires/[id]/page.tsx:539` | **100 / 80** (`:161`) | `searchable` | never | nothing |
| 10 | `questionnaires/UploadDialog.tsx:194` | prop from #8 | `searchable` | never | field hidden when empty `:190` |
| 11 | `questionnaires/ReuseDialog.tsx:339` | prop | `searchable` | prose only, no numbers `:361-373` | sentence instead of a control |

### B. `Workshop` pickers — options from `GET /workshops`

| # | Control | Asked / drawn | Scope | Cap stated |
|---|---|---|---|---|
| 12 | `components/forms/WorkshopSelect.tsx:445` (`ComboBox`), mounted 6× | 100 / 80 | `accessibleOnly=true` `:244`; the record's own workshop merged back in `:372` | 100-vs-total only `:459` |
| 13 | `components/WorkshopScopeSelect.tsx:232` (`MultiSelectDropdown`), mounted 5× | ~~**100 / 80**~~ → `WORKSHOP_OPTION_PAGE_SIZE` for both | none — deliberately a READ scope | ~~**no notice of any kind**, and on error it falls through to "all workshops" over an empty list~~ → **CLOSED.** `CappedListNotice` for the cut, and a `WorkshopListState` whose failure sentence names the widened scope; the opening is `workshopListNotice`'s through `WorkshopListVoice.reassurance` rather than a second copy (2026-08-31) |
| 14 | `designworkshop/StageWorkshopField.tsx:232` | 100 / 80, memoised 60 s | `accessibleOnly` | none (it stores the TITLE, not an id) |
| 15 | `components/FunnelFilters.tsx:246`, mounted 4× | 100 / 80 | none | 100-vs-total `:282` |
| 16 | `design-workshops/page.tsx:931` | 100 / 80, **`workshopType=DESIGN_PROTOTYPE`** — the only server-side type filter in the app | `accessibleOnly` | never |
| 17 | `settings/tasks/page.tsx:290` | 200 / 80, server `search` | — | yes: the only **server-sent truncation flag** in the app, `:307` |
| 18 | `settings/WorkshopRosterPanel.tsx:242` | 100 / 80 | none (admin screen) | never |
| 19 | `settings/WorkshopAccessRequestPanel.tsx:167` (multi) | 200 / 80 | `/workshops/requestable` | **cannot** — a bare array with no `total` on the wire |
| 20 | `media/page.tsx:537` | 100 / 80 | none | yes |
| 21 | `data/page.tsx:931` | 100 / 80 | none | yes — the only one naming a **pager**, `:1005` |

**Searched and absent:** no design-workshop picker on `/crafts`, and correctly so — `model Craft`
carries `workshopId` and no `designWorkshopId` column, unlike Artisan, Product, Tool, Media,
Interview, Questionnaire and Process. No workshop picker on `/tasks`, `/scan`,
`/workshop-access/manage` or `/sharing`. No `Workshop` picker inside `StageReferenceField.tsx`.
Searched `components/media/MediaRepositoryPicker.tsx`, `components/media/ExistingMedia.tsx`,
`components/tasks/AssignmentBuilder.tsx` and `app/(protected)/design-workshops/[id]/**` — absent;
all take `workshopId` as a prop from the route or a page above.

## 1.2 How they disagree — the four columns that must collapse to one

**Label shape — six.** `title` plus `hint: craft · cluster/state · date` (#1 `:196-206`, #2
`:1501-1508` re-implemented inline rather than imported, Android `DesignWorkshopPicker.kt:250-264`) ·
`title · date` (#3, #4, #5, #6, #12 `:152-156`, #13 `:152-156`, #18) · `title · date` plus
`hint: workshopCode` (#4) · `title · craft · cluster, district` (#7) · **`title` alone** (#8, #9, #10,
#11, #15, #20, #21) · `title · place` (#16 `:936-939`, #17) · `title · date · place — standing` (#19).
`design-review/page.tsx:270-278` states the trigger for lifting the helper into
`lib/designWorkshops.ts`: *"If a fourth caller wants it."* **There are seven.**

**The "none" row — nine strings, and one client that cannot draw one at all.**
`"Not filed under a design workshop"` (#1 `:251`) · `"Not attached to a workshop"` (#8, #9, #10) ·
`"Don't attach it yet"` (#11 `:346`) · `"Not linked to a workshop"` (#12 `:130`, #13 `:186`) ·
`"Not recorded"` (#14 `:73`) · `"All workshops"` (#15, #17) ·
`"Do not link a workshop — type the details below"` (#16 `:935`) ·
`"Choose the workshop this belongs to…"` (#7 `:232`).

**A defect no recon report names, found in verification: the web cannot un-file a record from a
design workshop.** `WorkshopSelect.tsx:428` prepends `{ value: "", label: NO_WORKSHOP_LABEL }` to its
options. `DesignWorkshopSelect.tsx:196-206` maps rows only and prepends nothing, and
`SearchableSelect` draws `placeholder` as trigger text with **no clear row inside the panel**
(searched `components/ui/SearchableSelect.tsx` for a `value: ""` row — absent). So of the two pickers
stacked on one form, the first offers a way back to "no workshop" and the second does not, and a
record filed by mistake cannot be corrected on the web at all. Android's `includeNone = true`
(`DesignWorkshopPicker.kt:274-278`; the primitive at `SearchableSelect.kt:240-246` and `:306`) can.
Section 2.7 fixes it in the primitive rather than in nine call sites.

**Sort — five orders for one question.** Every `DesignWorkshop` picker inherits the server's
`createdAt desc` (`design_workshops.py:1318`) and **none re-sorts**, so "most recent" means most
recently *entered*, not most recently *run* — the mistake `WorkshopSelect.tsx:132-139` documents and
fixes for ordinary workshops. Ordinary-workshop pickers split: #12, #13, #16 and #18 re-sort with
`sortWorkshopsByOccurrence` (`WorkshopSelect.tsx:137-144`); #15 with an inline copy; #17 takes the
server's `date desc`; #20 sorts `createdAt desc`; #21 takes whatever arrives.
`FunnelFilters.tsx:52-60` states the live counts and warns that a client-side re-sort cannot recover
a row the server already cut.

**Where the search box points — three incompatible answers, all shipping today.**
Client-side over a server-truncated page: **sixteen controls** (#2, #3, #5, #8-#16, #18-#21).
`searchable={false}` plus a server `SearchInput` above plus a `capHint` naming it: #4, #6, #7 — the
corrected pattern. Server search folded into the same request with the filter box left on: #17 alone.

**Archived and soft-deleted.** No picker sends `statusFilter`, and `DESIGN_WORKSHOP_STATUSES` is
`{DRAFT, IN_PROGRESS, COMPLETE, SUBMITTED, ARCHIVED}` (`schemas/design_workshops.py:42-44`), so every
picker offers submitted and archived workshops with no marker at all. Soft-deleted rows are excluded
by `where["deletedAt"] = None` for everyone who does not pass `includeDeleted`
(`design_workshops.py:1267-1271`), and that flag is admin-only (`:1261-1262`) and sent by no picker.

## 1.3 Android — every dropdown, by class

39 call sites of `SearchableSelectField` / `SearchableMultiSelectField` across 26 files, plus four
raw `DropdownMenu`s and one `ExposedDropdownMenuBox`. Record-form dropdowns route through two
adapters in `MainActivity.kt`: `DropdownField` `:5640-5657` and `CheckboxMultiSelectField`
`:6235-6252`.

**(a) Bundled constant vocabularies — cannot be empty. No work.** Status `MainActivity.kt:5660`,
gender `:7916`, Pehchan card `:8069`, workshop type `:8295`, record type `:11515` and `:12997`,
interview language `:13898`, dictation language `RecordProseField.kt:708`, recording mode `:13932`,
sharing tier `SharingBatch.kt:261`, country dial code `PhoneField.kt:356` (252 rows), roles
`AccessRosterScreen.kt:864-878` and `MainActivity.kt:14765`, trace presets
`DwSketchTracePresets.kt:127`, stage ENUM and MULTI_ENUM `FieldRenderer.kt:504-517`.
**The stage registry is Android's true `OFFLINE_STATES`, and it is stronger than the web's:**
`StageSchemaStore` resolves memory, then `filesDir`, then **`assets/design-workshop-schema.json`**,
documented as "never from the network". A second bundled-floor precedent at exactly the shape req 31
wants is `MainActivity.kt:15143-15145` — `workshopLevelOptions` falls back to the compiled-in
`workshopAccessLevels` (`:15112`) when the served list is empty.

**(b) Reference data — states and districts.** `LocationFields.kt:1274-1292` (state) and `:1294-1305`
(district), sourced from `AddressReferenceCache`: a JSON file at `filesDir/address-reference.json`
(`:255`), read at `:265-269`, written at `:272-278`, read **before** the request goes out and never
blanked by an empty answer (`:289-300`), with **no expiry, deliberately** (`:248-253`).
`DesignerProfileScreen.kt:1726-1741` is the best-behaved picker of this class in the app: an empty
closed list stands down to the free-text town box, and says why.

**(c) Record-backed lists — the hard case.** Workshop `MainActivity.kt:5891-5928`; design workshop
`DesignWorkshopPicker.kt:245-323`; craft `:7889-7901`; artisan `:9624-9642`; product `:9646-9660`;
tool `:10115-10119`; the four "linked craft/artisan" pickers `:8600`, `:8620`, `:9023`, `:9043`;
browse and re-link `:6826`, `:11160`, `:11536`, `:11549`; feedback `:11029`; admin assignment
`:15213`, `:15312`, `:15391`; triage `:16452`; the three questionnaire pickers; the stage REF field
`DwReferenceField.kt:762-780`; report template `ReportScreen.kt:813`; sharing
`SharingBatch.kt:252-461`.

**(d) Does not fit the four classes.** `MyAiKeysScreen.kt:343-357` — the only
`ExposedDropdownMenuBox`, outside the shared picker entirely: no threshold, no `SelectOption`, no
empty state. `SearchScreen.kt:1355-1400` — a hand-rolled anchored menu duplicating
`SelectTrigger` + `DropdownMenu` with `"▾"` and `"✓"` string glyphs. `AccessRosterScreen.kt:864-878`
— a raw `DropdownMenu` over the eight-tier role ladder, sitting **exactly on `SEARCH_THRESHOLD`**.
Filter chips, which are not dropdowns: `AccessRosterScreen.kt:757-780`,
`DesignerRosterScreen.kt:325-329`, `WorkshopScope.kt`, `DataBrowserScreen.kt`, `MapScreen.kt`.

## 1.4 What an empty picker renders on Android right now

```kotlin
// SearchableSelect.kt:234-296 — the whole !searchable branch. options.isEmpty() draws:
if (!searchable) {
    DropdownMenu(expanded = menuOpen, ...) {
        if (includeNone) { DropdownMenuItem(...) }   // :240
        options.forEach { option -> ... }            // :247 — zero iterations
        createAction?.let { action -> ... }          // :269 — usually null
    }
}
// There is NO else, NO empty item, NO sentence.
```

The only empty arms anywhere are `SearchableSelect.kt:354-355` (multi-select, and only when
`createAction == null`) and `:735-757` — **inside the sheet**, which means only at eight options or
more. That sheet arm is the one place in the app where the two facts are correctly separated:
`if (searching) "Nothing matches “${query.trim()}”." else "This list is empty."` at `:748`, with the
reason written above it at `:741-746`.

**Fourteen silent single-selects, and six `emptyMessage`s that assert non-existence** —
`"No crafts available."` (`:10125`), `"No crafts available yet. Create a craft first."` (`:8335`),
`"No workshops to request yet."` (`:15394`), `"No other researchers to ask."`
(`SharingBatch.kt:257`), `"No colleagues to share with."` (`:417`), and the default
`"No options available."` (`SearchableSelect.kt:335`). Every one is a claim about the repository made
from a read that may have failed.

**Four already-correct patterns to generalise:** the four-branch placeholder
(`SketchesAndPrototypesScreen.kt:418-423`, `DesignReviewScreen.kt:272-277`); the cause-named notice
(`DwWorkshopCreation.kt:490-508` and `:526-536`, mounted at `WorkshopListScreen.kt:1825-1855`); the
stand-down with a selection exception (`WorkshopListScreen.kt:1434-1465`, `:1942-1961`); and the
provenance line (`DwReferenceField.kt:1046-1101`).

## 1.5 The two roster lists as they stand

| | `/admin/access` | `/admin/designers` |
|---|---|---|
| Web page | `app/(protected)/admin/access/page.tsx` (841 lines) | `app/(protected)/admin/designers/page.tsx` (562 lines) |
| Endpoint | `GET /access/roster` — `backend/app/api/routes/access.py:114-168` | `GET /designers/roster` — `backend/app/api/routes/designers.py:98-139` |
| Params on the wire | `page`, `pageSize`, `search`, `status` **and nothing else** (`:116-119`) | `page`, `pageSize`, `search`, `activeOnly` **and nothing else** (`:100-103`) |
| Page size | `PAGE_SIZE = 20` `:83`, queue `QUEUE_PAGE_SIZE = 8` `:92`; server default 50, declared `le=200` but clamped to 100 by `normalize_pagination` (`services/pagination.py:4,9`) | `PAGE_SIZE = 20` `:63`; the same clamp |
| Order | `with_id_tiebreak({"createdAt": "desc"})` `:165` — bypasses `count_and_page`, and says so at `:164` | `with_id_tiebreak({"createdAt": "desc"})` `:136` — the same, `:133-135` |
| Filters today | one `SearchInput` `:602` and one `Dropdown` over `STATUS_OPTIONS` `:103-109`, mounted `:611-622` | one `SearchInput` `:409` and one `Dropdown` over `STANDING_OPTIONS` `:70-73`, mounted `:418-429` |
| Sort control | **none** | **none** |
| Payload | `access_payload` (`services/access_roster.py:434-455`): `status`, `admitRole`, `joinedAt`, `requestedAt`, `attemptCount`, `lastAttemptAt`, `decidedAt`, `firstSeenAt`, `createdAt` — **no institution** | `roster_payload` (`services/designers.py:227-241`): `institution`, `isActive`, `revokedAt`, `firstSeenAt`, `createdAt` — **no role, no userId** |
| Cap stated on screen | the queue ceiling, `:478-484` | the directory cap, `:432-437` |
| Android | `AccessRosterScreen.kt` (927 lines) — **server-filtered, server-paged**; `ACCESS_PAGE_SIZE = 15` `:113`, five status chips `:750-780`, debounce 400 ms `:125` | `DesignerRosterScreen.kt` (744 lines) — **walks the whole table and filters ON THE DEVICE**: `walkPagedListing` 100 × 5 = a 500-row ceiling, client sort `:156-159`, client filter `:331-333`, `matches()` `:497-502` |
| Android wire | `search` and `status` declared (`WorkshopRepositoryApi.kt:1779-1785`) | **`search` and `activeOnly` deliberately NOT declared** (`:1729-1737`) |

**Both tables, verified column by column.** `model DesignerRoster` at
`backend/prisma/schema.prisma:3945-3973`; its complete index set is `@@index([isActive])` `:3971` and
`@@index([addedById])` `:3972`. `model AccessRoster` at `:4168-4235`; its complete index set is
`@@index([status])` `:4231`, `@@index([status, requestedAt])` `:4232`, `@@index([addedById])` `:4233`
and `@@index([decidedById])` `:4234`. **`AccessRoster` has no `institution` column** — columns
verified individually across `:4169-4226`: `email`, `status`, `admitRole`, `joinedAt`,
`requestedAt`, `attemptCount`, `lastAttemptAt`, `decidedAt`, `decidedById`, `firstSeenAt`,
`fullName`, `notes`, `createdAt`, `updatedAt`, `addedById`.

**The eight-tier ladder**, canonical at `backend/app/core/deps.py:44-88`: `CROWDSOURCE_VOLUNTEER` 10,
`FIELD_CONTRIBUTOR` 20, `RESEARCHER` 30, `DESIGNER` 35, `INSPECTOR` 37, `PROFESSOR` 40, `ADMIN` 50,
`MASTER_ADMIN` 60. Labels at `deps.py:90-103`, mirrored byte-exact at
`frontend/lib/permissions.ts:104-117`, with the picker order at `:119-122` — `ROLES_BY_RANK`,
*"All roles, highest tier first — the display order for pickers"* — and in three Kotlin tables. Five
copies, machine-diffed by `backend/tests/test_role_ladder_parity.py`.

## 1.6 The endpoints, as they are

| Route | Filtering it accepts | Page size | Cap communicable? |
|---|---|---|---|
| `GET /design-workshops` `api/routes/design_workshops.py:1226` | `search` (OR over `title, craftName, clusterName, workshopCode` `:1289-1294`), `statusFilter` (enum-checked `:1277`), `craftName`, `state`, `mineOnly`, `includeDeleted`/`deletedOnly` (403 for non-admins `:1261`) | `Query(20, ge=1)` `:1228` — **no `le=`**, silently clamped to 100 | yes, `page_payload`. **But `find_many` at `:1321` orders by `{"createdAt": "desc"}` with NO `with_id_tiebreak`** — an offset-paged read over a non-unique key, which `records.py:1364-1369` describes as silently missing and repeating rows |
| `GET /design-workshops/default-for-me` `:1329` | reads both doors, later wins; returns `{workshopId, title, accessAt, reason}` | one row | n/a. Client: `lib/designWorkshopDefault.ts`, memoised 60 s, failures not cached |
| `GET /workshops` `api/routes/workshops.py:185` | `search`, `place`, `dateFrom`/`dateTo`, `statusFilter`, `workshopType` (422 on unknown), `createdBy`, `accessibleOnly` | `Query(20, ge=1, le=100)` — 422 above 100 | yes |
| `GET /workshops/requestable` `:438` | none; every workshop plus the caller's standing | `le=WORKSHOP_REQUEST_MAX = 200` | **no — a bare array, no `total`, no flag** |
| `GET /tasks/options` `api/routes/tasks.py:1197` | one `search` folded into the WHERE, not applied after the take | `TASK_OPTION_WORKSHOP_LIMIT = 200` | **yes, and it is the best in the repo** — `workshopsTruncated` is exact, read one past the cap |
| `GET /access/roster` `api/routes/access.py:114` | `search`, `status` (422 on unknown `:136-140`) | 50 default, clamped 100 | `total` only; **no truncation flag** |
| `GET /designers/roster` `api/routes/designers.py:98` | `search`, `activeOnly` | 50 default, clamped 100 | `total` only; **no truncation flag** |
| `GET /designers/directory` `:251` | `search`, `includeSuspended`; filters to `WORKSHOP_CAPABLE_ROLES` `:286` | `DIRECTORY_TAKE = 500` `:86` | **no — a bare array**, stated at `:288-291`; both clients infer the cut from `len(rows) >= 500` |

**Searched and absent:** there is **no sort or order query parameter anywhere in the backend**.
Searched `backend/app/api/routes/*.py` for `sort=`, `sortOrder`, `orderBy`, `order_by` as query
parameters and `backend/app/schemas/*.py` for `sortBy`/`sort_by`/`orderBy` — absent. Every
`sortOrder` hit is the questionnaire/process **step ordering column**, unrelated. Req 30's sort
parameter is greenfield on the wire.

There is **no sortable-table primitive on the web**. Searched `frontend/**` (excluding
`node_modules`) for `aria-sort`, `sortBy`, `sortKey`, `sortDir`, `onSort`, `SortableTh` — zero hits
outside `lib/trace/engine/vectorize.ts:274` (`orderByRegion`, a contour-ordering function). The only
shared `<th>` is `components/ResizableTh.tsx:8-14`, fourteen lines of resize handling with no sort
affordance.

There is **no Room and no SQL layer at all on Android**. Searched `android/**` for
`androidx.room`, `room-runtime`, `RoomDatabase`, `@Entity`, `@Dao` — **zero hits**. Req 31's phrasing
"cache the last successful fetch in Room" describes a facility this app does not have; §3.3 says what
to use instead.
---

# 2. THE UNIFIED WORKSHOP SELECT (requirements 9-13)

**One design. Both clients. Two lists, one vocabulary.**

## 2.0 What "unified" means here, and what it does not

`DesignWorkshopSelect.tsx:6-26` is an explicit, correct argument against merging the two tables into
one control: two tables, two access systems, two option scopes, and one of them needs a submission
pre-flight the other structurally cannot have — *"Adding a pre-flight here would be a request that
could only ever say yes"* (`:22-26`). The Kotlin twin repeats it verbatim at
`DesignWorkshopPicker.kt:24-44`.

So the ruling is: **unify the CONTROL and the VOCABULARY; never unify the LISTS.** The four record
forms keep two stacked pickers. What stops being different is everything a reader can see — the label
shape, the group headings, the sort, the "none" row, the empty sentence, the truncation sentence and
where the search box points.

## 2.1 The primitive it is built on

**`components/ui/SearchableSelect.tsx`, reached through `components/ui/Dropdown.tsx`, with grouping
by `components/ui/selectFilter.ts::groupRows`. No new control.** The plan is explicit about this
(`IMPLEMENTATION_PLAN.md:182-184`) and the primitive already carries most of the union:

- `SelectOption` has `label`, `hint` (drawn *and* searched, `selectFilter.ts:52-53`), `group`
  (`:54-55`) and `disabled` (`:56`).
- `groupRows` (`:233-253`) buckets by `group` **keeping each row's index into the rendered array**,
  which is what `highlight`, `aria-activedescendant`, the option element ids and the Enter/Space
  commit all index (`:220-231`). Ungrouped rows draw first (`:249-252`).
- The heading renders as `role="group"` with `aria-label`, and the inner `<ul role="none">` keeps the
  options owned by the listbox (`SearchableSelect.tsx:979-985`, with the reason at `:936-942`).
- Truncation at `RENDER_CAP = 80` with off-window selections pinned forward, and the pin set held as
  a **snapshot** rather than the live selection (`:198-231`, pinned by
  `e2e/dropdown-sweep-unit.spec.ts:332`).
- `capNoticeSentence` (`selectFilter.ts:199-212`), `CAP_HINT_WITH_SEARCH` `:172`,
  `CAP_HINT_WITHOUT_SEARCH` `:188`.
- Diacritic and whitespace folding in `fold` (`:101-108`) and ranked matching in `filterOptions`
  (`:132`).
- Exactly one control in the app uses `group` today: the photo-intake destination picker,
  `design-workshops/[id]/photos/page.tsx:105-139`, pinned by `e2e/dropdown-sweep-unit.spec.ts:245`.

Three capabilities in the union are **genuinely not carryable today**, each with the citation that
proves it. These are the whole extension, and they are small.

### E1 — the search term cannot leave `SearchableSelect`

`components/data/cappedList.ts:127-133` states it as a deliberate omission:

> *"There is deliberately no `"search"` arm yet. Giving these pickers the server-side `search=` all
> the list routes already accept means threading a search term out of `SearchableSelect`, which is a
> shared primitive this change does not own."*

Three shipping controls work around it by turning `searchable` off and mounting a second
`SearchInput` above the field (#4 `design-review/page.tsx:716`, #6
`DesignWorkshopInspectorsPanel.tsx:525,547`, #7 `AdoptLocalDraftDialog.tsx:366,380`). The cost is the
panel's `fold()` diacritic matching, its `hint` ranking, its `role="status"` live region and its
"No matches" arm, all of which belong to the box that is now switched off. Sixteen other controls
took the other branch and kept a filter box that searches only the first page — which
`design-review/page.tsx:322-330` names by its consequence: *"typing a real workshop's title that
happens to sit on page 4 answered 'No matches' — absence reading as non-existence."*

**The extension.** One optional prop on `SearchableSelectProps` and `SearchableMultiSelectProps`:

```ts
/**
 * The panel's filter box drives a SERVER query rather than filtering the array it was handed.
 *
 * Present means: `withSearch` is forced true; the local `filterOptions` pass is BYPASSED, because
 * `options` already IS the answer to `value` and filtering it again would drop rows the server
 * matched on a column the label does not show (`workshopCode`); and the empty arm becomes
 * three-way — pending, matched-nothing-on-the-server, nothing-here-at-all.
 *
 * The caller owns the debounce (300 ms) and the generation counter; `apiFetch` carries no
 * AbortSignal, so an out-of-order answer is discarded by generation, exactly as
 * `design-review/page.tsx:365-412` already does.
 */
serverQuery?: {
  value: string;
  onChange: (term: string) => void;
  pending: boolean;
  /** The server says more rows match than this page carries. */
  truncated?: boolean;
};
```

Two consequences to write down rather than discover:

1. **The local fold is lost on a server-searched control.** The server's `search` is
   `contains(...)` → `ILIKE '%term%'` over `title, craftName, clusterName, workshopCode`
   (`design_workshops.py:1285-1294`), with no unaccenting, no whitespace collapse and no ranking. So
   "Ahmedabad" finds "Ahmedābād" in the browser today and will not once the box goes to the server.
   The fix is `unaccent` in Postgres, specified as **W-B3** in §5 and raised in §6 as a cost question,
   not left as a silent regression.
2. **The pin snapshot must be re-taken when the ANSWER changes, not per keystroke.** `useSelectList`
   recomputes pins on open and on query change (`SearchableSelect.tsx:207-222`). With a server query
   the options array is replaced when each answer lands, so the snapshot is taken on `options`
   identity change instead. Without this a multi-select past the cap renumbers rows under a
   stationary highlight — the exact defect `:207-215` describes, on a permissions control.

### E2 — `MultiSelectDropdown` cannot suppress "Select all N"

`SearchableSelect.tsx:1120-1140` builds the bulk button unconditionally, labelled
`` `Select all ${bulk.length}` `` / `` `Clear all ${bulk.length}` `` (`:1123-1129`), and
`Dropdown.tsx:106-151` exposes no prop to turn it off (props are `values, onChange, options,
placeholder, emptyLabel, disabled, className, ariaLabel, describedBy, searchable, capHint,
confirmOnSelect, confirmLabel`). Wired to a filter, that button produces the state R1 forbids: all
ticked and nothing ticked, both meaning "everything". `WorkshopScopeSelect` sidesteps it with its own
"All records" button that sets `[]` (`:201-212`).

**The extension:** `bulk?: boolean` on `SearchableMultiSelectProps` and `MultiSelectDropdown`,
defaulting to `true` so no existing caller changes. Every FILTER passes `bulk={false}` and offers the
absence state as its own control. Used by req 30's role and institution pickers as well as by this one.

### E3 — Kotlin has no per-call-site `searchable`, and its menu branch has no empty arm

`android/.../ui/SearchableSelect.kt:218` is `val searchable = options.size >= SEARCH_THRESHOLD` with
no override, and `SearchableSelect.tsx:513-520` already names that as *"a divergence to close rather
than a difference to design around"*. It has two heads:

- `DesignWorkshopPicker.kt:151` asks for 20 rows, which is over the threshold, so **the handset draws
  a filter box over a server-truncated page — precisely what `DesignWorkshopSelect.tsx:259-262`
  refuses to do on the web.**
- The `!searchable` branch (`SearchableSelect.kt:234-296`) has no empty arm at all, so a list that
  shrinks below eight loses, in one step, the filter box, the `countLine` live region (`:823-828`),
  the `"This list is empty."` sentence (`:748`), the Select-all row and the IME commit path. With
  `options.isEmpty()`, `includeNone = false` and no `createAction` — the configuration at
  `SketchesAndPrototypesScreen.kt:424`, `DesignReviewScreen.kt:282`, `DwSketchChooserUpload.kt:634`,
  `ReportScreen.kt:816`, `MainActivity.kt:9629`, `:9656`, `:10119`, `:16457` — **tapping the trigger
  opens a zero-item popup with no words in it.**

**The extension:**

```kotlin
@Composable
fun SearchableSelectField(
    label: String,
    options: List<SelectOption>,
    selectedValue: String,
    // ...
    /**
     * `null` lets [SEARCH_THRESHOLD] decide — correct for a vocabulary written in this file.
     * Pass `true` where the list is BACKED BY RECORDS, `false` to overrule a long list whose
     * options are one server-truncated page. Same rule, same words, as
     * `SearchableSelectProps.searchable` on the web.
     */
    searchable: Boolean? = null,
    /** Drawn in BOTH surfaces when there is nothing to pick. Never "there are none" — see §3.5. */
    emptyMessage: String = "This list is empty.",
    onSelect: (String) -> Unit,
)
```

with `val useSheet = searchable ?: (options.size >= SEARCH_THRESHOLD)` and an `else` arm in the
`DropdownMenu` that draws `emptyMessage` as a disabled item when `options.isEmpty()`.

**`SEARCH_THRESHOLD = 8` itself does not move, on either client.** It is measured
(`selectFilter.ts:59-71`, `SearchableSelect.kt:112-120`) and it is only ever the default; what
changes is that record-backed lists stop letting it decide. See §3.6.

## 2.2 The single source of options

One module per client. Two loaders inside it — the lists stay apart — and one vocabulary.

**Web: `frontend/lib/workshopOptions.ts`** (new). It is the home
`design-review/page.tsx:270-278` names for the label helper, and it takes the eight duplicated
copies with it.

```ts
export type WorkshopListState =
  | { kind: "loading" }
  | { kind: "failed" }                                  // the read did not answer
  | { kind: "ok"; rows: T[]; total: number };           // may be empty, which is a FACT

/** The four constants that replace nine strings. Each means something different. */
export const NO_DESIGN_WORKSHOP  = "Not filed under a design workshop";
export const NO_FIELD_WORKSHOP   = "Not linked to a workshop";
export const ATTACH_LATER        = "Don't attach it yet";
export const TYPE_DETAILS_INSTEAD = "Do not link a workshop — type the details below";

export function designWorkshopOptions(
  rows: DwSummary[],
  opts: { currentUserId?: string; group: boolean; offPage?: DwSummary | null }
): SelectOption[];

export function fieldWorkshopOptions(
  rows: Workshop[],
  opts: { group: boolean; offPage?: Workshop | null }
): SelectOption[];

/** The ONE sentence under the control. §3.5 gives the strings. */
export function workshopListNotice(
  state: WorkshopListState,
  kind: "design" | "field",
  online: boolean
): string;

/** The `emptyLabel` handed to the Dropdown — never a claim the state does not support. */
export function workshopEmptyLabel(state: WorkshopListState, kind: "design" | "field"): string;
```

and the two fetching hooks that own the page size, the debounce and the generation counter:

```ts
export function useDesignWorkshopOptions(opts?: { serverSearch?: boolean }): WorkshopOptionSource;
export function useFieldWorkshopOptions(opts?: { accessibleOnly?: boolean }): WorkshopOptionSource;
```

Both ask for `pageSize: RENDER_CAP` — **never 100 into a control that draws 80**, which is the dead
band `selectFilter.ts:81-86` exists to kill and which #3, #5, #8, #9, #10 and #13 all still have.

**Android: `android/.../ui/WorkshopOptions.kt`** (new), the byte-parallel twin — the same four
constants, the same label builder, the same group headings, the same notice sentences, pure and
JVM-testable in the way `DwWorkshopCreation.kt:482-488` argues for.

## 2.3 The label format — one answer, applied everywhere

> **`label` is the title alone. Everything that tells two workshops apart goes in `hint`.**

```
label : row.title.trim() || "Untitled workshop"
hint  : the present members of, joined by " · ":
          DesignWorkshop : [ statusWord?, craftName, clusterName ?? state, day(startDate) ]
          Workshop       : [ statusWord?, place,     day(startDate ?? date ?? createdAt)   ]
```

`day()` is the first ten characters of the ISO string (`DesignWorkshopSelect.tsx:203`,
`DesignWorkshopPicker.kt:229-233`). `statusWord` is `"Archived"`, `"Submitted"` or `"Ended"` and is
present only for those rows — see §2.6.

**Why this and not `title · date`.** `filterOptions` (`selectFilter.ts:132`) ranks a label-prefix
match above a word-prefix above a mid-word above a hint match. Folding the date into the label gives
every row a shared suffix and demotes nothing, but it also makes the label the wrong length for a
handset row and leaves nowhere for a third fact. Keeping the title alone in `label` is what makes
typing a title beat a coincidental craft match, and `hint` is searched as well as shown
(`selectFilter.ts:52-53`), so nothing becomes unreachable. It is also the shape both clients' most
recent pickers already use — `DesignWorkshopSelect.tsx:196-206` and `DesignWorkshopPicker.kt:250-264`
— so it is a convergence rather than a fourth opinion.

**`workshopCode` is deliberately not in the hint.** It is a code an admin reads off a join card, not a
fact that tells two workshops apart on screen, and a phone row has no space for it. It stays
reachable because the server's `search` already covers it (`design_workshops.py:1289-1294`) and §2.8
puts the box on the server. #4's `hint: workshopCode` (`design-review/page.tsx:465`) is dropped.

## 2.4 The grouping — one answer

> **Group by whether the workshop is still open. All-or-nothing per render.**

| Group | `DesignWorkshop` | `Workshop` |
|---|---|---|
| *(ungrouped, first)* | the "none" row only | the "none" row only |
| `"Already on this record"` | the off-page recovered row, when there is one (§2.9) | same |
| `"Open"` | `DRAFT`, `IN_PROGRESS`, `COMPLETE` | `endDate` absent or in the future |
| `"Submitted and archived"` | `SUBMITTED`, `ARCHIVED` | — |
| `"Ended"` | — | `endDate` in the past |

`groupRows` orders buckets by **first appearance with ungrouped first** (`selectFilter.ts:229-231`,
`:249-252`), so building the array in that order gives exactly that reading order with no primitive
change. **All-or-nothing** means: if any row needs a heading, every workshop row gets one — otherwise
the ungrouped-first rule would file the open workshops above the headings and read as a fourth,
unnamed category. When only one class is present, no headings render at all, because `groupRows`
returns a single null bucket when nothing carries a group (`:234-235`).

**Why not group by door — "workshops you created" versus "workshops you were added to".** It is
derivable on both clients (`DwSummary.createdById` at `lib/designWorkshops.ts:592`,
`DesignWorkshopDto.createdById` at `StageSchema.kt:1552`), and `default-for-me` already distinguishes
the two (`GRANTED` / `CREATED`). It is rejected because the two doors are indistinguishable *in
consequence*: both open the same workshop with the same filing rights, so the heading would separate
rows on a fact the reader cannot act on. Status is the axis a reader must act on — new fieldwork does
not belong in a submitted workshop — and two axes cannot both be the grouping.

## 2.5 The sort order — one answer

> **By occurrence, newest first, inside each group. Never by creation.**

```
DesignWorkshop : startDate ?? createdAt
Workshop       : startDate ?? date ?? createdAt      // workshopOccurrenceDate, WorkshopSelect.tsx:137-139
```

then, to break a tie, `title` ascending, then `id` ascending. ISO-8601 strings compare
chronologically, as they already do on both clients (`WorkshopSelect.tsx:141-144`).

The reason is written in the repo and applies verbatim to design workshops, which currently ignore it:
*"a workshop entered into the system last is not the workshop that ran last"*
(`WorkshopSelect.tsx:132-135`). Every `DesignWorkshop` picker today inherits `createdAt desc` from
`design_workshops.py:1318` and none re-sorts.

**And the server-side half, which is not optional.** `design_workshops.py:1319-1322` pages with
`order = {"createdAt": "desc"}` and **no `with_id_tiebreak`**. `records.py:1364-1369` states what that
costs: *"OFFSET PAGING OVER A NON-TOTAL ORDER MISSES ROWS AND REPEATS OTHERS, AND BOTH ARE SILENT."*
Any paged walk of design workshops — Android's `designWorkshopOptionsAcrossPages`, the adopt dialog's
list, the sketches hub — is walking an unstable order today. Fixing it is one call (**W-B1**).

`FunnelFilters.tsx:52-60` is the standing warning that a client-side re-sort **cannot recover a row
the server already cut**. This design does not rely on one: the page size is `RENDER_CAP`, the sort is
applied to the page, and §2.8 states the cut.

## 2.6 Archived, closed and deleted — one answer

| Row | Offered? | Drawn how | Why |
|---|---|---|---|
| Soft-deleted `DesignWorkshop` | **never** | — | `list_design_workshops` excludes them unless `includeDeleted` (`design_workshops.py:1267-1271`), which is admin-only (`:1261-1262`) and which no picker may send. A picker that offered one would file live fieldwork into the trash. |
| `SUBMITTED` / `ARCHIVED` `DesignWorkshop` | **yes** | under `"Submitted and archived"`, `hint` prefixed `Submitted` / `Archived`. **Not `disabled`.** | A designer legitimately corrects a record already filed under a submitted workshop, and the server does not refuse it. Disabling would convert a read-only fact into a wrong write — `WorkshopSelect.tsx:361-370`'s argument, applied to the other table. |
| Ended `Workshop` | **yes** | under `"Ended"`, `hint` prefixed `Ended` | The existing pre-flight already says what saving into one means (`WorkshopSelect.tsx:436-438`, `:482-489`, `LateSubmissionDialog`). The group heading is what stops the reader picking one by accident; the dialog is what stops them saving into one by accident. |
| Ended `Workshop` this account is not assigned to | **yes, and marked** | the existing red sentence `WorkshopSelect.tsx:476-481` | Unchanged. |

**The pre-flight stays on the `Workshop` picker alone** — `GET /workshops/{id}/submission-check`
(`WorkshopSelect.tsx:20-30`). `DesignWorkshopSelect.tsx:22-26` gives the reason it must not be added
to the other: a design workshop has no window and no assignment roster, so the request could only ever
say yes.

## 2.7 The "none" row — a primitive concern, four constants

`SearchableSelect` gains `noneLabel?: string`. When set, the panel draws a first, **ungrouped** row
with `value: ""` carrying that label, and the trigger falls back to it when `value === ""`. This is
what Android's `includeNone` already does (`SearchableSelect.kt:192-202`, `:240-246`, `:306`), and it
closes the divergence found in §1.2: today `WorkshopSelect.tsx:428` hand-builds the row and
`DesignWorkshopSelect.tsx:196` does not, so the web cannot un-file a record from a design workshop
at all.

The nine strings collapse to four, exported from `lib/workshopOptions.ts` / `WorkshopOptions.kt`, each
with a genuinely different meaning:

| Constant | String | Where "" means this |
|---|---|---|
| `NO_DESIGN_WORKSHOP` | `"Not filed under a design workshop"` | #1, #8, #9, #10, and the Android `DesignWorkshopField` |
| `NO_FIELD_WORKSHOP` | `"Not linked to a workshop"` | #12, #14, and `WorkshopField` |
| `ATTACH_LATER` | `"Don't attach it yet"` | #11 — a copy operation where the answer can be deferred |
| `TYPE_DETAILS_INSTEAD` | `"Do not link a workshop — type the details below"` | #16 — the create flow, where free text is the alternative |

**`"All workshops"` is not on this list.** A control that FILTERS a screen expresses "everything" by
absence and not by a none-row (R1) — that is `WorkshopScopeSelect`'s convention
(`WorkshopScopeSelect.tsx:129-135`, `:191`, `:201-212`) and it is what #13, #15 and #17 must use.
`"Not recorded"` (#14) and `"Choose the workshop this belongs to…"` (#7) are duplicates of
`NO_FIELD_WORKSHOP` and of the trigger placeholder respectively, and are dropped.

**And the two clients hold one half each of a working unfile, which is worth setting out because it
changes what each side's work is.** The server accepts an explicit null as "unfile":
`designWorkshopId` is in `services/records.py:627-640`'s `CLEARABLE_KEYS`, added with the column
itself and with the failure spelled out — *"without this entry `{"designWorkshopId": null}` would be
stripped as an unset optional, the save would return 200, the form would show it unfiled, and the old
link would survive in the database."*

- **The web can SEND the clearance and cannot DRAW the row.** `DesignWorkshopSelect.tsx:196-206`
  prepends nothing. `noneLabel` (E4) is the whole fix; nothing else changes.
- **Android can DRAW the row and cannot SEND the clearance.** `includeNone = true`
  (`DesignWorkshopPicker.kt:274-278`) draws it, and `DesignWorkshopPicker.kt:80-92` states what
  happens when it is used: `value` returns null for "none", `ApiClient.json` has
  `explicitNulls = false`, the key is omitted, and the API's `exclude_unset=True` reads an absent key
  as "leave the stored value alone". *"A designer clearing the box, pressing Save, being told it
  saved, and finding the workshop still there — which is the 'exit zero is not evidence' class of
  defect wearing a form."* It is inherited, not introduced: `WorkshopPickerState.value()` has the
  same shape for `workshopId`.

The file says closing it *"means a sentinel on the wire for both columns at once, which is a transport
change and not this one's"*. This IS the change that owns it: a picker that offers "none" and cannot
mean it is the same class of lie as a picker that offers nothing and cannot say why. **A5** in §5
carries it, and it must land in the same release as the Android picker work, not after — otherwise
this design ships a row on both clients that works on one.

## 2.8 Truncation, and where the box points

Three rules, in order:

1. **`pageSize === RENDER_CAP`, always.** One number governs the fetch and the render, so two
   truncation sentences with two different totals cannot exist (`selectFilter.ts:81-86`). This alone
   fixes #3, #5, #8, #9, #10, #11, #13.
2. **Every picker over a record-backed list gets `serverQuery` (E1).** The box goes to the server's
   `search`, which both routes already accept (`design_workshops.py:1285-1294`,
   `workshops.py:185-260`). This is what makes the sixteen client-side boxes honest instead of
   switching them off.
3. **The sentence names the box, because the box now reaches past the cut.**
   `components/data/cappedList.ts` gains its `"search"` arm — legitimate now, by the module's own
   stated test at `:181-188`: *"This notice is drawn on `/settings/tasks`, where the term goes into
   the request… If somebody later points this function at a picker whose box filters locally, the
   sentence becomes the defect it was written to close — check the request before reusing it."* The
   request is checked; the term goes in.

```ts
export type CutReach = "none" | "pager" | "search";
// cappedListNotice(cut, "search") ->
//   `Showing ${loaded} of ${total} ${noun} — type in the box above to reach the rest,
//    which are searched on the server.`
```

`capHint` on a `serverQuery` control becomes `CAP_HINT_WITH_SEARCH` again ("Keep typing to narrow the
list"), and it is true for the first time.

**#19 `WorkshopAccessRequestPanel` cannot say anything, and that is a server defect, not a client
one.** `GET /workshops/requestable` returns a bare array with no `total`
(`api/routes/workshops.py:438`, cap `WORKSHOP_REQUEST_MAX = 200`). It must gain an envelope or a
`truncated` flag — **W-B2**. Until it does, that one control is honestly silent and this document
says so rather than pretending.

## 2.9 Off-page value recovery — a required prop, never a default

```ts
offPage: "recover" | "refuse"     // required; no default
```

- **`"recover"`** is `useRecordOffPage` (`components/forms/recordPickers.ts:62`, used at
  `WorkshopSelect.tsx:372`): the record's stored workshop is fetched by id through the open
  `GET /workshops/{id}` and merged in, **outside the access scope**, drawn under
  `"Already on this record"`. The argument is `WorkshopSelect.tsx:361-370` — *"Withholding it does not
  withhold anything… hiding the row would convert a read-only fact into a wrong write."* Re-implemented
  today at `StageWorkshopField.tsx:54-61` and `DesignWorkshopInspectorsPanel.tsx:334-337`; all three
  become one call.
- **`"refuse"`** is `AdoptLocalDraftDialog`'s (`:30-40`): no merge, and the action held until the
  server has confirmed — because the write is one-way and unrepeatable.

**Why it cannot have a default.** The two behaviours differ on a fact only the caller holds: whether
the control describes a read that is already true, or authorises a write that is not yet. A control
cannot know which, and a default would silently pick one. This is the conflict the recon flagged
between "say what the list is" and "recover the off-page row"; the resolution is that the recovered
row gets its own heading, so the scope sentence under the control stays true of every row the heading
`"Open"` covers.

**The DW list is narrower than the DW door**, and a picker's silence therefore proves nothing:
`list_design_workshops` filters `deletedAt` (`design_workshops.py:1267-1271`) while
`load_workshop_or_404` admits an admin to a soft-deleted row. #3 resolves this by asking the API about
the single id (`sketches-and-prototypes/page.tsx:449-522`); that is exactly what `offPage: "recover"`
generalises.

## 2.10 The smart default

`GET /design-workshops/default-for-me` (`design_workshops.py:1329`) answers with an **id**, and the
picker applies it **as an id**. It never infers a default from the list's ordering.

That resolves the three-meanings-of-recent conflict: `default-for-me` answers by grant or authorship
`createdAt`, the list arrives `createdAt desc`, and this design re-sorts by occurrence — so the
default's row is very often not the first row, and a positional default would pick the wrong one.
`ContinueOnAllocatedWorkshop` already guards against the mismatch by refusing to apply a default the
page does not carry (`design-workshops/page.tsx:1470-1477`); with `offPage: "recover"` the id is
recovered instead of dropped, which is strictly better — the designer gets the workshop they were
allocated rather than a blank box.

Two setters, not a boolean — `setWorkshopId` marks the form dirty, `prefillWorkshopId` does not
(`DesignWorkshopSelect.tsx:76-93`, `:102-112`) — and the prefill note stays
(`designWorkshopDefaultNote`, mounted `:184`, `:226`; Kotlin `designWorkshopPrefillNote`
`DesignWorkshopPicker.kt:197-224`). `undefined` versus `null` for `initial` keeps meaning create
versus edit (`:116-123`).

## 2.11 The conflicts in the union, resolved

| # | Conflict | Ruling | Why |
|---|---|---|---|
| C1 | Server-side search vs client-side `hint` matching and `fold()` | **The server's search is the search.** `filterOptions` is bypassed when `serverQuery` is set. | They cannot both be the box. One box that sees past the page is the rule `AdoptLocalDraftDialog.tsx:41-49` and `DesignWorkshopViewersPanel.tsx:60-65` already set. The diacritic cost is real and is paid by `unaccent` (**W-B3**), not hidden. |
| C2 | "Say what the list is" vs off-page recovery | **Required `offPage` prop; the recovered row gets the heading `"Already on this record"`.** | Only the caller knows whether this is a read or a one-way write (`WorkshopSelect.tsx:361-370` vs `AdoptLocalDraftDialog.tsx:30-40`). The heading keeps the scope sentence true of everything under `"Open"`. |
| C3 | Smart default vs sort order | **The default is an id, applied by id, recovered if off-page.** | Three different meanings of "most recent" reach one control (§2.10). |
| C4 | Multi-select vs truncation pinning | **Re-snapshot pins on `options` identity change, not per keystroke.** | A server-searched multi re-runs the query per debounced keystroke; the live-selection pin bug (`SearchableSelect.tsx:207-215`) is a granting-access-to-the-wrong-colleague bug on the viewer picker. |
| C5 | `DesignWorkshop` vs `Workshop` in one control | **Unify the control and the vocabulary; never the lists.** | `DesignWorkshopSelect.tsx:6-26`, `DesignWorkshopPicker.kt:24-44`. Two tables, two access systems, and only one of them can have a pre-flight. |
| C6 | Android's threshold divergence | **E3: add the override and the empty arm.** | `SearchableSelect.tsx:513-520` already calls it a divergence to close. Today the handset puts a filter box over a truncated page that the web refuses to. |
| C7 | Web cannot clear a design-workshop selection; Android can | **E4: `noneLabel` in the primitive** (§2.7). | Two pickers stacked on one form disagreeing about whether an answer is reversible. |
| C8 | `GET /design-workshops` pages without a tiebreak | **W-B1: `with_id_tiebreak`.** | `records.py:1364-1369`. Silent, and every paged walk of that list is affected. |

## 2.12 What every current caller changes

**Web — `DesignWorkshop` (11 controls).**

| # | Change |
|---|---|
| 1 `DesignWorkshopSelect.tsx` | Becomes the reference mount. Options from `designWorkshopOptions`; `noneLabel={NO_DESIGN_WORKSHOP}`; `serverQuery`; `offPage: "recover"`; `emptyLabel` from `workshopEmptyLabel` — **which fixes the failure-reads-as-non-existence bug at `:168,176,254-258`**. |
| 2 `design-workshops/page.tsx:1495` | Delete the inline label builder `:1501-1508`; `pageSize: RENDER_CAP` (was 50); stop returning `null` on empty (`:1487`) and draw the notice instead; keep applying the default by id but let `offPage` recover it. |
| 3 `sketches-and-prototypes/page.tsx` | `CHOOSER_PAGE = RENDER_CAP` (was 100 `:236`); delete the page-level "first 100 of N" sentence `:783-788` in favour of the panel's one sentence. |
| 4 `design-review/page.tsx` | Move its `SearchInput` into the panel as `serverQuery`; delete the local `workshopLabel` `:280-284`; drop `hint: workshopCode` `:465`. |
| 5 `DesignWorkshopViewersPanel.tsx` | `DESIGN_WORKSHOP_PAGE = RENDER_CAP` (was 100 `:96`); adopt `serverQuery`; the client-side workshop-type filter over a separately capped `GET /workshops?pageSize=200` (`:229-247`) stays but its notice `:645-649` must remain — an access-administration control that narrows on a second list's truncation. |
| 6 `DesignWorkshopInspectorsPanel.tsx` | `SearchInput` becomes `serverQuery`; its hand-rolled pin `:334-337` becomes `offPage: "recover"`. |
| 7 `AdoptLocalDraftDialog.tsx` | `SearchInput` becomes `serverQuery`; `offPage: "refuse"`; the amber offline panel `:388-394` stays and becomes the §3.5 `empty-because-offline` sentence. |
| 8, 9 `questionnaires/**` | `pageSize: RENDER_CAP` (was 100); `noneLabel={NO_DESIGN_WORKSHOP}`; add the notice and the empty label they have never had. |
| 10 `UploadDialog.tsx` | Stop hiding the field when the list is empty (`:190`) — draw it disabled with the sentence (R3). |
| 11 `ReuseDialog.tsx` | `noneLabel={ATTACH_LATER}`; replace the unconditional prose `:361-373` with `cappedListNotice`. |

**Web — `Workshop` (10 controls).**

| # | Change |
|---|---|
| 12 `WorkshopSelect.tsx` | Options from `fieldWorkshopOptions`; the hand-built none row `:428` becomes `noneLabel={NO_FIELD_WORKSHOP}`; `serverQuery`; keep the pre-flight, the scope paragraph `:469-475` and the `onInput` swallow `:443`. |
| 13 `WorkshopScopeSelect.tsx` | `bulk={false}` (E2) so "All records" `:201-212` is the only way to say everything; **add the truncation notice it has never had** — the sharpest single defect in the app, five screens including `/search` and `/map`; on a failed fetch stop falling through to "all workshops" over `[]` and say the read failed. |
| 14 `StageWorkshopField.tsx` | Options from `fieldWorkshopOptions`; keep the title-storage and the free-text degrade `:194-227`, which §3.3 endorses; its hand-rolled off-page merge `:54-61` becomes `offPage: "recover"`. |
| 15 `FunnelFilters.tsx` | Delete the inline occurrence re-sort `:97`; `bulk={false}`; the default stays "most recent workshop" but the notice `:282` must report `RENDER_CAP`-vs-total, not 100-vs-total. |
| 16 `design-workshops/page.tsx:931` | Keep `workshopType=DESIGN_PROTOTYPE` (the only server-side type filter in the app); `noneLabel={TYPE_DETAILS_INSTEAD}`; add the truncation notice. |
| 17 `settings/tasks/page.tsx` | Already correct in kind. Fold its own search box into `serverQuery`; keep `flagCutNotice` `:307`. |
| 18 `WorkshopRosterPanel.tsx` | Options from `fieldWorkshopOptions`; add the notice; keep the `sorted[0]` auto-select. |
| 19 `WorkshopAccessRequestPanel.tsx` | `bulk={false}`; keep the `disabled` already-granted rows `:246-250`; add the notice **once W-B2 lands** and not before. |
| 20, 21 `media/page.tsx`, `data/page.tsx` | Options from `fieldWorkshopOptions` (they lose their `title`-only labels and `createdAt` sort); keep `reach: "pager"` on #21. |

**Android (11 controls).** `WorkshopField` (`MainActivity.kt:5891-5928`) gains the scope sentence its
web twin prints in all three states (`WorkshopSelect.tsx:469-475`) — its own header at `:5883-5885`
claims *"Web parity with `<WorkshopSelect>`, and that now includes the LIST"*, which is parity of the
list and not of the sentence. `DesignWorkshopField` (`DesignWorkshopPicker.kt:245-323`) takes
`searchable = false` plus the cap sentence — it is one truncated page of 20, so a filter box over it
is the thing the web refuses to draw — and its `state.listed` gate at `:313-321` splits into the four
sentences of §3.5, ending the silence on failure. All eleven take their options from
`WorkshopOptions.kt`. `SketchesAndPrototypesScreen.kt:403-427`, `DesignReviewScreen.kt:259-287` and
`WorkshopListScreen.kt:1837-1855` are already correct in kind and change only to import the shared
label and the shared sentences.
---

# 3. THE OFFLINE CONTRACT (requirement 31)

Two rules bind everything in this section, by name.

**R2 — a field may only be mandatory where it is answerable.** The precedent is
`components/forms/LocationFields.tsx:880` and `:892-893`, where both required flags end in
`&& options.length > 0`, and the file states why the state clause is there even though
`OFFLINE_STATES` means it should never fire (`:886-891`): *"the invariant is what matters — this card
never demands an answer it is not offering — and a later change that narrowed or dropped the bundled
list would otherwise reintroduce a lost interview in silence."* The incident it closed is at
`:176-179`: a required closed list with no members, native validation refusing the submit,
`saveOrQueue` never reached, and *"the interview and its photographs die with the tab."*

**R3 — the control must say which it is doing.** `IMPLEMENTATION_PLAN.md:311-313`. A silently empty
picker reads as "there are none".

**Android needs R2 in two halves, because it has no client-side required-validation to stand down
from on the address card.** The asterisks there are literal label text —
`LocationFields.kt:1275` `label = "State / union territory *"` and `:1295` `label = "District *"` —
and searching `android/**/*.kt` for `stateRequired`, `districtRequired`, or an `isNotEmpty()` guard on
a required flag returns nothing. So the rule ports as:

- **R2a — never LABEL a field required while its list is empty.** The `*` becomes computed, not typed.
- **R2b — never let a CLIENT-SIDE validator block a save on an empty closed list.** Violated today —
  see §3.3, the process form.

## 3.1 Class (a) — bundled constant vocabularies

**Rule.** Always answerable. **May be required.** Says nothing, because there is no fact to report.
No work.

Covers every control in §1.3(a) on Android and every constant-built `options` array on the web. The
one that could in principle be empty is the stage ENUM branch (`FieldRenderer.kt:504-517`), and it
cannot be, because `StageSchemaStore` resolves to the bundled APK asset
`assets/design-workshop-schema.json` when memory and `filesDir` both miss. That three-tier store is
Android's strongest offline guarantee and nothing else in the app has it.

**One item of work, and it is a widening rather than a fix:** the `workshopLevelOptions` pattern
(`MainActivity.kt:15143-15145`, floor at `:15112`) is a **served vocabulary with a compiled-in
floor** — exactly the shape req 31 asks for. Any *served enum* added later (report template ids,
sharing tiers if they ever move server-side) uses that shape rather than becoming a class-(c) list.

## 3.2 Class (b) — reference data (states and districts)

**Rule.** Answerable from the device once it has ever been online; on the web the state list is
answerable always. **May be required only while the list is non-empty** — R2, computed on both
clients. Says which of the four things it is doing, per §3.5, and the cached case carries a date.

| | Web | Android |
|---|---|---|
| State | `OFFLINE_STATES` bundled (`LocationFields.tsx:201-203`), consumed at `:863`, `StageAddressField.tsx:227`, `StageRecordingPlace.tsx:203`, `DesignerProfileForm.tsx:214`. **Correct today.** | `AddressReferenceCache`, a JSON file at `filesDir/address-reference.json` (`LocationFields.kt:255-279`), read before the request goes out and never blanked by an empty answer (`:289-300`). **Empty on a fresh install.** |
| District | cannot be bundled — 795 names, revised several times a year, meaningful only per state (`LocationFields.tsx:189-190`); stands down from required at `:892-893`. **Correct today.** | same cache; disabled plus a four-branch placeholder `:1299-1303` plus the best offline paragraph in the app `:1306-1316`. **Does not stand down; the `*` is literal.** |

**Android work, three items.**

1. **B1 — make the asterisk computed.** `LocationFields.kt:1275` and `:1295` take
   `label = fieldLabel("State / union territory", required = stateOptions.isNotEmpty() && …)`.
   Same expression shape as the web's, same reason.
2. **B2 — `"Loading the state list…"` is false forever on a never-online phone.**
   `LocationFields.kt:1280-1284` shows it whenever both served lists are empty, which on a fresh
   install is a permanent state, not a transient one: nothing is loading. It reads as something to
   wait through. Replace with the §3.5 `empty-because-offline` sentence, which the district three
   lines below already gets right.
3. **B3 — `AddressReferenceCache` has no `fetchedAt`.** Every other cache in the app records and shows
   one; this one records only the payload and its `version` (`LocationFields.kt:272-278`). Without it,
   class (b) cannot print the `cached-and-stale` sentence with a date. One field, mirroring
   `DwReferenceStore.kt:115-116` and `:411` (`Instant.now()` on write, device clock, *"Displayed,
   never used to decide whether the cache may be used"*).

**The model to copy for the general case is already in the app:**
`DesignerProfileScreen.kt:1726-1741` — when the district list is empty the closed control is hidden,
the free-text `Town / city` box above it (`:1719-1724`) is the answer, and a sentence says why. That
is R2 and R3 together, in one screen, working.

## 3.3 Class (c) — record-backed lists: the decision, per list

Req 31 offers three options. **They are not interchangeable, and the choice is forced by what the
list MEANS, not by how big it is.**

> **R6 — a stale ACCESS list is wrong in the permissive direction.** A cached "which workshops may I
> submit to" reads a revoked grant as a grant. `WorkshopRepository.kt:3918-3923`:
> *"There is deliberately no cached, bundled or last-known fallback behind it… a picker is the one
> control that must not offer what it cannot honour."* Repeated at `MainActivity.kt:5860-5864`,
> `DesignWorkshopPicker.kt:46-49`, `WorkshopSelect.tsx:38-44`, and argued at length for the adopt
> dialog at `AdoptLocalDraftDialog.tsx:30-40`.

So: **caching is forbidden for the access-scoped lists and correct for the register-scoped ones.**
The design document says so rather than listing "cache it in Room" as an open option, and there is no
Room to list it in anyway (searched `android/**` for `androidx.room`, `RoomDatabase`, `@Entity`,
`@Dao` — zero hits; `android/app/build.gradle.kts` declares neither Room nor SQLDelight nor
`SQLiteOpenHelper`).

| List | Decision | Why |
|---|---|---|
| `Workshop` — "workshops I may submit to" (`MainActivity.kt:5891`, `WorkshopSelect.tsx`) | **Disable with a reason.** Never cache. | R6. `accessibleOnly=true` resolves `WorkshopAssignment` rows the client never sees. |
| `DesignWorkshop` (`DesignWorkshopPicker.kt:245`, `DesignWorkshopSelect.tsx`) | **Disable with a reason.** Never cache. | R6. `visible_to_clause` is a grant set. |
| Eligible viewers / inspectors / designer directory / user lists | **Disable with a reason.** Never cache. | R6. These are permissions controls. |
| Craft register | **Cache**, via the ALL-scoped `DwReferenceStore` key. Plus the free-text escape that already exists. | Not an access list. The artisan form already accepts a typed `newCraftName` (`MainActivity.kt:7903-7912`) and `hasCraft` (`:7657`) is satisfied by it — req 31's option (b), already shipped, in one place. |
| Artisan register | **Cache**, ALL-scoped `DwReferenceStore` key. | `DwReferenceStore.kt:39-51` already shares this list across every workshop on the device *for exactly this reason*: *"A designer who starts a brand-new workshop in a village… still gets the artisan register that some earlier workshop on this same phone downloaded."* |
| Products for an artisan | **Cache**, narrowed key, re-narrowed on device by `narrowedTo` (`DwReferenceStore.kt:118-129`, which keeps blank-`filterValue` options rather than dropping them). | The cascade already works offline inside a stage; the record form is the only place it does not. |
| Tool register | **Cache**, ALL-scoped key. | Same. |
| Report templates | **Cache.** | A served vocabulary with no access meaning. |
| Workshop access levels | **Already has a bundled floor** (`MainActivity.kt:15143-15145`). No work. | The class-(a) shape. |

**The store is `DwReferenceStore`, not Room, and not a new layer.** One JSON document per key under
`filesDir/dw-references/` (`DwReferenceStore.kt:314`), `fetchedAt`-stamped on write with the device
clock (`:411`), **no expiry** — and the reason is exactly the one req 31 needs (`:54-58`):

> *"A stale artisan list is worth immeasurably more than no artisan list, and there is no clock on a
> phone that can tell 'two weeks old because nothing changed' from 'two weeks old because there has
> been no signal for two weeks'. `fetchedAt` is recorded and SHOWN so the designer can judge it;
> nothing in this file ever deletes on the strength of it."*

And it is already surfaced: `ReferenceProvenance` (`DwReferenceField.kt:1046-1101`) draws three
distinct sentences — never downloaded, the age line, and truncation in error colour — with the
argument this whole class turns on: *"A list last refreshed an hour ago that does not contain Ram
Kumar means Ram Kumar has no artisan record and one should be created; the same list refreshed nine
days ago means nothing of the kind."*

**So the cheapest correct change on the whole Android client is C1: point the record forms at the
ALL-scoped `DwReferenceStore` entries.** `WorkshopRepository.kt:3849-3868` — `crafts()`, `artisans()`,
`products()`, `tools()` — are bare `api.*(pageSize = 100)` calls with no cache at all. The identical
artisan register is durable inside a design-workshop stage and evaporates on the record form. Closing
that needs no new storage layer, no Room, no KSP processor, and no schema.

**And it defuses the worst live defect in this class.** `MainActivity.kt:9624-9642` is
`"Artisan *"`, `includeNone = false`, no sentence; `:9432` sets `artisanError` when the id is blank;
`:9445-9447` computes `blocked` and **returns before the save coroutine is launched**. That is the
`OFFLINE_STATES` incident reproduced on Android: a required closed list with no members offline, a
client-side validator refusing the submit, and the record plus its step media lost with the screen.
R2b forbids it. Two changes, both required, neither sufficient alone:

- the picker gets options offline (C1), so the field is answerable in the ordinary case; and
- the validator stands down when the list is empty, so the save reaches the outbox in the
  extraordinary one:

```kotlin
// MainActivity.kt, process form
val artisanRequired = artisans.isNotEmpty()          // R2
if (artisanRequired && artisanId.isBlank()) artisanError = "Please select an artisan"
```

with the same treatment for `"Product *"` at `:9433`, `"Tool *"` at `:10139` and `"Craft *"` at
`:7726` (the last already survives through its free-text escape).

## 3.4 Class (d) — the three controls outside the pattern

**Rule.** They route through `SearchableSelectField`, or they carry the same four sentences
themselves. There is no third option: a control that cannot say which of the four states it is in is a
control that reads as "there are none".

- `MyAiKeysScreen.kt:343-357` (`ExposedDropdownMenuBox`) — route through `SearchableSelectField`.
  Its vocabulary is a constant, so it lands in class (a) and needs nothing further.
- `SearchScreen.kt:1355-1400` (hand-rolled `DropdownMenu` with `"▾"`/`"✓"` glyphs) — route through
  `SearchableSelectField`. Mixed sources; the ones backed by records take §3.5's sentences.
- `AccessRosterScreen.kt:864-878` (raw `DropdownMenu` over the role ladder) — **stays a menu**, and
  §4 pins it there: eight options is exactly `SEARCH_THRESHOLD`, so routing it through the shared
  field would flip it to a sheet, and E3's `searchable = false` is what holds it still. Class (a):
  the ladder is a constant and cannot be empty.

## 3.5 The five sentences

Five, not four: the plan names bundled, cached-and-stale, empty-because-offline and genuinely-empty,
and the repository needs a fifth for **the read failed while the device is online**, which is a
different fact with a different next move and which today is either silent
(`DesignWorkshopPicker.kt:309-321`) or actively mis-stated (`DesignWorkshopSelect.tsx:254-258`).

These strings are the contract. Both clients use them byte for byte; `{noun}` is the caller's plural
("design workshops", "artisans", "districts").

| State | Sentence | Field required? | Control |
|---|---|---|---|
| **bundled** | *(nothing)* | **may be** | enabled |
| **cached-and-stale** | `"{n} {noun} on this device, last refreshed {date}. If the one you want is missing, refresh with a connection before concluding it is not on record."` | **may be** | enabled |
| **empty-because-offline** | `"This device has not received the {noun} list yet, so there is nothing to pick here. That is not a claim that there are none. Connect once and the list is kept on the device from then on."` | **no — stands down** | disabled |
| **could-not-be-listed** (online, the read failed) | `"The {noun} list could not be loaded, so this is not showing what exists. Nothing you have entered is at risk — this record can be saved without it."` | **no — stands down** | disabled |
| **genuinely-empty, scoped** | `"No {noun} are open to this account. An administrator can give you access to one."` | **no — stands down** | disabled |
| **genuinely-empty, unscoped** | `"No {noun} have been recorded yet."` | **no — stands down** | disabled |

The last two are deliberately different sentences. `"No workshops are open to this account"` is a
statement about a scope and its next move is an admin; `"No workshops have been recorded yet"` is a
statement about the repository and its next move is to create one. Collapsing them is what produces
`"No crafts available."` (`MainActivity.kt:10125`) and `"No workshops to request yet."` (`:15394`) —
claims about the repository made from a read that may have failed.

**Where "offline" comes from.** Not from a network probe. It is the classification the outbox already
makes: `WorkshopRepository.isTransient` (`:5890-5906`) treats `IOException` and 401/408/429/5xx as
transient, everything else as a refusal. A transient failure with no queued-send in flight is the
`empty-because-offline` state; a non-transient one is `could-not-be-listed`. On the web the same split
is `lib/offline.ts`'s.

**`createAction` survives every one of these states.** `SearchableSelect.kt:204-212` states the rule
and it is the right one: *"A cluster whose artisan register holds three names takes the anchored menu,
and three names is precisely the case where the artisan being looked for is the one that was never
documented."* An empty picker that can still make the record is not a dead end. The web has no
equivalent and does not need one on these forms.

## 3.6 What happens to `searchable = options.size >= 8` when a list shrinks offline

**The threshold does not change. What changes is that it stops deciding for record-backed lists on
either client.** `SEARCH_THRESHOLD = 8` stays exactly what both files already say it is — the
measured line between a closed vocabulary a reader takes in at a glance and a corpus they hunt through
(`selectFilter.ts:59-71`, `SearchableSelect.kt:104-120`) — and it stays *only ever the default*
(`selectFilter.ts:69-71`).

| Situation | Web | Android |
|---|---|---|
| Constant vocabulary (class a) | pass nothing; the threshold decides. Unchanged. | pass nothing; the threshold decides. Unchanged. |
| Record-backed list, options are the whole answer | `searchable` (true) | `searchable = true` (E3) |
| Record-backed list, options are one server-truncated page with `serverQuery` | `serverQuery` forces the box on | `searchable = true`, box drives the server |
| Record-backed list, options are one server-truncated page with **no** server search | `searchable={false}` plus a `capHint` naming what does reach the rest — `DesignWorkshopSelect.tsx:259-263`'s pattern | `searchable = false` plus the cap sentence (E3). **This is what `DesignWorkshopField` must become**: it asks for 20 (`DesignWorkshopPicker.kt:151`), which is over the threshold, so the handset draws a filter box over a truncated page that the web deliberately refuses to draw. |
| Any of the above, shrunk to zero offline | the panel keeps its box and prints the §3.5 sentence in the `emptyLabel` slot | **E3's `else` arm** prints it in the menu branch too, so the sentence no longer disappears with the sheet |

The failure E3 prevents, stated plainly: **without it, a list that crosses below eight loses the
filter box, the "N options / M of N match" live region, the "This list is empty." sentence, the
Select-all row and the IME commit path, in one step, with nothing on screen to say so** — and with
`options.isEmpty()`, `includeNone = false` and no `createAction`, tapping the trigger opens a
zero-item popup containing no words at all. That is the literal reading of *"a silently empty picker
reads as 'there are none'"*.

The second thing E3 fixes is the district picker, which changes shape with the answer above it —
Goa has 2 districts, Sikkim 6, Uttar Pradesh 75 — while the web forces `searchable` on both address
fields (`LocationFields.tsx:1826`, `:1869`) precisely to stop that, with the reason at `:1863-1878`:
*"A reader cannot learn a control that changes shape with the answer above it."*

## 3.7 A queued record referencing an id that never existed (R7)

**What happens today.** A 404 — or the 422 a `require_record` existence check produces — is not
transient (`WorkshopRepository.isTransient:5890-5906`), not a 409 (`WorkshopSync.isConflictRefusal`),
and not `extra_forbidden` (`schemaSkew`). It therefore falls to `Offline.kt:141-186`'s kind 3,
"REFUSED, ON A PERSON": `markFailure(..., skewRun = null, conflict = false)`
(`WorkshopRepository.kt:5542-5556`), `blocksRetry` returns true (`WorkshopSync.kt:769-773`), and the
entry is **parked forever** in `OfflineOutboxTray` behind two buttons — *Try again*, which will fetch
the identical 404, and *Throw away*, which destroys the last copy of the record and its photographs.

`Offline.kt:170-172` describes that exact shape, about the 409 it was written to close: *"a Try again
button that could only ever fetch the identical answer — a dead end wearing the costume of a remedy."*
The 409 got its own arm and a route out. **A dangling foreign key did not.** Neither the tray nor
`outboxFailureRows` names *which field's id* is dangling, and there is no re-pick action — which is
the one remedy that would work, because the record is still on the phone with its payload intact.

**Why this is nearly unreachable through pickers today, and must stay that way.** The app refuses to
mint client-side ids a picker can offer: offline, `WorkshopField` and `DesignWorkshopField` are empty
so a queued record files under *no* workshop rather than a fabricated one
(`MainActivity.kt:5860-5864`); the craft escape is free **text**, not a local craft id (`:7903-7912`);
and although design-workshop drafts carry `local-`/`dwlocal-` keys, `remoteIdOf`
(`WorkshopSync.kt:949`) treats them as "no id" and **no picker sources them**. The one place a dangling
reference survives and reports success is `_custom` section media (`DwCustomSections.kt:90-97`), and
MEDIA/REF/GEO/RICH_TEXT are excluded from `V1_CUSTOM_TYPES` (`:104-108`) precisely to prevent it.

**The rule this yields, and it is binding on §3.3.** The two failures have opposite remedies: an empty
dropdown is fixed **before** the save, by offering something answerable or standing the field down; a
dangling id is fixed **after** the drain, on the record already on the device, by re-picking. So:

> **Any "free text queued for reconciliation" option added under req 31 must ship the after-the-drain
> arm at the same time.** Without it, it converts a class-(c) empty-picker problem into a kind-3
> permanent refusal, which is strictly worse: the entry blocks its own retry and the only offered
> escape deletes fieldwork.

**The after-the-drain arm — O1**, and it is small:

1. `PendingEntry` gains `danglingField: String?` (`Offline.kt:73-190`), set by `markFailure` when the
   refusal is a 404/422 naming a missing reference. It is a fifth outcome beside the six at
   `:141-186`, not a re-use of one of them.
2. `blocksRetry` keeps returning true for it — retrying is still pointless — but
   `OfflineOutboxTray.kt` draws a third button, **Re-pick it**, which opens the record's form seeded
   from the queued payload with that one field cleared and focused.
3. The sentence names the field: `"This record points at a {field} that is not on the server. Nothing
   is lost — open it, choose one that is, and it will send."`
4. `discard` stays exactly as it is, person-confirmed only (`Offline.kt:709`,
   `OfflineOutboxTray.kt:70-72`: *"Nothing automatic in this app may delete a queued entry that has
   not been sent"*).

**Where the two failures meet the same screen, they use different words.** The empty picker says one
of §3.5's sentences and the record still saves. The dangling id says O1's sentence and the record is
already saved, locally, and needs one field changed. Neither sentence may be reused for the other.
---

# 4. ROSTER FILTER AND SORT (requirement 30)

Both lists, both clients, one grammar. Everything here reuses machinery that already exists; the only
greenfield is the `sort`/`dir` pair, which no route in this backend has today.

## 4.1 The wire — every parameter, both routes

Additive. Every parameter already on the wire keeps its exact meaning, because Android sends
`status` and `search` today (`WorkshopRepositoryApi.kt:1779-1785`) and the web sends `activeOnly`
(`lib/designers.ts:153-158`), and a rollout in which the clients lag the server must not change what
those mean.

### `GET /access/roster` — `backend/app/api/routes/access.py:114`

| Parameter | Encoding | Absent means | Prisma `where` |
|---|---|---|---|
| `page`, `pageSize` | int | 1, 50 (clamped to 100) | — |
| `search` | one string | no text filter | `{"OR": [{"email": contains(t)}, {"fullName": contains(t)}, {"notes": contains(t)}]}` — unchanged, `:142-152` |
| `status` | **repeated OR comma**: `?status=PENDING&status=SUSPENDED` or `?status=PENDING,SUSPENDED` | **every status** | `{"status": {"in": [...]}}`; a single value still produces `{"status": {"in": ["PENDING"]}}`, which is behaviourally identical to today's `{"status": wanted}` |
| `roles` | repeated OR comma, over the eight tokens plus the reserved `default` | **every tier, including the default one** | see §4.4 |
| `dateField` | one enum: `added` \| `requested` \| `decided` \| `joined` \| `firstSeen` | no date filter | selects the column |
| `dateFrom`, `dateTo` | ISO-8601 instants | open at that end | `add_date_range(where, column, dateFrom, dateTo)` — `services/records.py:1237-1244` |
| `sort` | one enum, §4.3 | `added` | — |
| `dir` | `asc` \| `desc` | `desc` | — |

### `GET /designers/roster` — `backend/app/api/routes/designers.py:98`

| Parameter | Encoding | Absent means | Prisma `where` |
|---|---|---|---|
| `page`, `pageSize` | int | 1, 50 (clamped to 100) | — |
| `search` | one string | no text filter | `{"OR": [{"email": …}, {"fullName": …}, {"institution": …}]}` — unchanged, `:116-125` |
| `activeOnly` | bool | both standings | **kept exactly as-is.** `true` is `standing=active` |
| `standing` | `active` \| `suspended` | **both** | `{"isActive": True}` / `{"isActive": False}`. Sending both `activeOnly=true` and `standing=suspended` is a **422**, not a silent winner |
| `roles` | repeated OR comma, over the eight tokens plus the reserved `never-signed-in` | **every tier** | see §4.4 |
| `institutions` | repeated OR comma, plus the reserved `none` | **every institution, including rows with none** | `{"institution": {"in": [...]}}`, OR-ed with `{"institution": None}` when `none` is present |
| `dateField` | one enum: `added` \| `firstSeen` \| `revoked` | no date filter | selects the column |
| `dateFrom`, `dateTo` | ISO-8601 instants | open at that end | `add_date_range` |
| `sort`, `dir` | §4.3 | `added`, `desc` | — |

### Three parsing rules, all of them precedents

1. **Two spellings, both accepted.** Repeated parameters and one comma-joined value, exactly as
   `resolve_types` and `resolve_workshop_ids` do, and for the stated reason
   (`services/record_filters.py:246-249`): *"the web and Android build query strings differently, and
   a filter that quietly covered everything because it was spelled the other way would look exactly
   like the filter not working."*
2. **An unknown token is a 422 naming the valid values, never a silent omission.** `resolve_types`
   `:251-254`: *"Dropping it would answer a request for 'artisan' (singular, a plausible typo) with a
   perfectly well-formed empty result… a wrong answer dressed as a correct one."* The single-value
   helper already exists — `enum_filter_or_422` (`services/records.py:960-980`) — and the plural
   sibling is one function beside it:

   ```python
   def enum_filter_list_or_422(
       raw: list[str] | None, allowed: frozenset[str], *, field: str
   ) -> set[str] | None:
       """Multi-valued filter over an enum column. ``None`` means DO NOT FILTER.

       Absent, empty, or all-blank is ``None`` and not an empty set: a caller that means "no
       statuses at all" has nothing to ask for, and the default state of the control is
       "everything" — which must not be spelled the same way as a mistake.
       """
   ```
   Home: `backend/app/services/record_filters.py`, beside `resolve_types`, because that is where this
   repository's filter grammar lives.
3. **Absent, empty and all-blank all mean "do not filter".** `resolve_workshop_ids:65-67`. On the
   client, `buildQuery` already drops `undefined`, `null` **and `""`** (`frontend/lib/api.ts:323-330`),
   which is why `STATUS_OPTIONS`/`STANDING_OPTIONS` get absence for free from `value: ""` today and
   why the new multi-selects get it from an empty array.

### One date range per request, not five

Req 30 lists five date fields on the access list. **`dateField` + `dateFrom` + `dateTo` — one range,
one named column — rather than five From/To pairs.** Reasons, in order: `dateFrom`/`dateTo` is the
spelling eight existing list routes already use (`map_points.py`, `media.py`, `processes.py`,
`products.py`, `questionnaire.py`, `search.py`, `tools.py`, `workshops.py`), each paired with one
`add_date_range` call; five simultaneous ranges is a query nobody has asked for; and five ranges means
five index requirements instead of one per column, on tables that today have none at all on any date
column. The control is a "Date" picker plus one range widget, which is also one control instead of
five stacked ones.

## 4.2 The Prisma shape, assembled

Both routes move onto `count_and_page` (`services/records.py:1395-1419`), which applies
`with_id_tiebreak` on the way through. Both currently hand-roll `asyncio.gather` and call the tiebreak
themselves, and both say so in a comment (`access.py:160-164`, `designers.py:133-135`) — moving them
is the cheap correctness win and it is where the new ordering must land anyway.

```python
# access.py — the shape, not the whole function
where: dict[str, Any] = {}

statuses = enum_filter_list_or_422(status, FILTERABLE, field="status")
if statuses is not None:
    where["status"] = {"in": sorted(statuses)}

if search:
    where["OR"] = [{"email": contains(t)}, {"fullName": contains(t)}, {"notes": contains(t)}]
    #  ^ THE ONE `OR` KEY IS TAKEN. Every clause below AND-composes into where["AND"] and is never
    #    assigned to where["OR"] — two assignments and the later silently wins, which is the warning
    #    at design_workshops.py:1304-1307 and services/records.owned_or_granted_where.

roles = enum_filter_list_or_422(roles_raw, ACCESS_ROLE_FILTER_TOKENS, field="roles")
if roles is not None:
    arms: list[dict[str, Any]] = []
    named = sorted(roles - {"default"})
    if named:
        arms.append({"admitRole": {"in": named}})
    if "default" in roles:
        # THE RESERVED NINTH OPTION. `admitRole` NULL means "the platform default, the lowest rung"
        # (schema.prisma:4177-4186), and both clients already render it as its own phrase —
        # access/page.tsx:818 and AccessRosterScreen.kt:684. A picker with eight rows and no ninth
        # cannot express it, and ticking all eight would silently exclude every default-tier
        # admission: the identical failure UNASSIGNED_WORKSHOP was invented for
        # (record_filters.py:47-53).
        arms.append({"admitRole": None})
    where.setdefault("AND", []).append({"OR": arms})

if dateField:
    column = ACCESS_DATE_COLUMNS[dateField]      # 422 on an unknown key
    add_date_range(where, column, dateFrom, dateTo)

order = with_id_tiebreak({ACCESS_SORT_COLUMNS[sort]: dir})   # 422 on an unknown key
total, rows = await count_and_page(
    db.accessroster, where=where, skip=skip, take=clean_size, order=order
)
return page_payload([access_payload(r) for r in rows], total, clean_page, clean_size)
```

`ACCESS_ROLE_FILTER_TOKENS = frozenset(ROLE_RANK) | {"default"}` — built from `deps.ROLE_RANK`
(`core/deps.py:44-88`) so it cannot drift from the ladder, and pinned by the existing
`backend/tests/test_role_ladder_parity.py` sweep.

## 4.3 Sort, and the stable secondary sort

**The stable secondary sort is `id`, on every column, on both routes, via
`with_id_tiebreak`** (`services/records.py:1361-1392`). It is not a new idea and it is not optional:

> *"OFFSET PAGING OVER A NON-TOTAL ORDER MISSES ROWS AND REPEATS OTHERS, AND BOTH ARE SILENT… THE
> TIES HERE ARE NOT HYPOTHETICAL. `createdAt` is what almost every list in this API sorts by, it has
> no unique index, and the access-roster migration inserted every grandfathered row with one
> `CURRENT_TIMESTAMP` — four hundred people sharing a single sort key."* — `records.py:1364-1375`

`with_id_tiebreak` follows the direction of the caller's last clause, so a newest-first list stays
newest-first inside a tie group and an A-Z list stays A-Z (`:1382-1385`), and it returns the order
unchanged if `id` is already named (`:1388-1389`).

| Route | `sort` token | Column | Default dir | Notes |
|---|---|---|---|---|
| access | `added` **(default)** | `createdAt` | desc | |
| | `email` | `email` | asc | |
| | `name` | `fullName` | asc | NULLs sort last on asc |
| | `standing` | `status` | asc | enum order; ties broken by `id` |
| | `joined` | `joinedAt` | desc | |
| | `requested` | `requestedAt` | desc | the queue order an admin works oldest-first is `dir=asc` |
| | `decided` | `decidedAt` | desc | |
| | `firstSeen` | `firstSeenAt` | desc | |
| | `attempts` | `attemptCount` | desc | "who is hammering the door" |
| designers | `added` **(default)** | `createdAt` | desc | |
| | `email` | `email` | asc | |
| | `name` | `fullName` | asc | |
| | `institution` | `institution` | asc | |
| | `firstSeen` | `firstSeenAt` | desc | |
| | `revoked` | `revokedAt` | desc | |

**Nullable sort keys, stated rather than discovered.** Postgres puts NULLs last on `asc` and first on
`desc`. So `sort=firstSeen&dir=desc` floats every **outstanding invitation** to the top — which is
exactly what Android's client-side sort does deliberately today
(`DesignerRosterScreen.kt:152-159`: *"An admin opens this screen to answer 'who have I added who has
not turned up'"*). That view survives as a named, paged sort instead of a device-side reordering of
whichever 500 rows happened to arrive.

**The two clients disagree about the default order today** — the web renders the server's
`createdAt desc`, Android re-sorts to outstanding-then-name. **Ruling: the server's `added desc` is
the default on both, and Android's `sortedWith` at `:156-159` is deleted.** One list, one order; the
invitation-first reading is one tap away and is now correct across pages, which the device-side sort
never was.

## 4.4 Role filtering on the DESIGNER roster

This is the one filter in req 30 that is not a column.

**The finding.** `model DesignerRoster` (`schema.prisma:3945-3973`) has **no role column and no user
relation** — its only `User` relation is `addedBy` (`:3968-3969`), the admin who added the row, not
the designer. `roster_payload` (`services/designers.py:227-241`) sends **no role and no userId**, and
the Android DTO carries a standing warning against putting one back (`ApiModels.kt`: a `userId` field
that was always null rendered the "Open designer profile" action for nobody, shipping an unreachable
admin editor). The join is by lower-cased email, and it is nullable in both directions: a roster row
may name somebody with no account (the normal case before first sign-in, `schema.prisma:3947-3949`),
and `DesignerRoster.email` is lower-cased on write while `User.email` is not.

**`GET /designers/directory` cannot serve this filter, and that settles the alternative the recon
raised.** It filters to `WORKSHOP_CAPABLE_ROLES` (`designers.py:79`, `:286`), which is
`sorted(DESIGN_WORKSHOP_ROLES)` = `{ADMIN, DESIGNER, MASTER_ADMIN}` (`core/deps.py:180`). A roster row
whose account is a `RESEARCHER` or an `INSPECTOR` is invisible to it. Filtering the roster through the
directory would therefore answer "no designers hold that tier" about rows that exist — R5's failure,
one layer down — on top of the directory's own 500-row envelope-less cap (`designers.py:86`,
`:288-291`).

**So: two queries, in the route, following `active_roster_emails` exactly.**

```python
#: How many accounts the role filter will read before it stops.
#:
#: A DIFFERENT QUANTITY FROM A PAGE SIZE, for design_workshop_viewers.ACTIVE_ROSTER_READ_LIMIT's
#: reason (:100-115): these emails are folded into the roster query's WHERE, so an account that
#: falls off the end does not truncate a page — it makes a matching DESIGNER VANISH from the list
#: as though they had never been empanelled. A backstop against an unbounded read, not a working
#: limit. Hitting it is logged at ERROR and reported on the wire.
ROLE_MATCH_READ_LIMIT = 50_000

roles = enum_filter_list_or_422(roles_raw, DESIGNER_ROLE_FILTER_TOKENS, field="roles")
role_match_truncated = False
if roles is not None:
    arms: list[dict[str, Any]] = []
    named = sorted(roles - {"never-signed-in"})
    if named:
        accounts = await db.user.find_many(
            where={"role": {"in": named}}, take=ROLE_MATCH_READ_LIMIT + 1
        )
        role_match_truncated = len(accounts) > ROLE_MATCH_READ_LIMIT
        if role_match_truncated:
            accounts = accounts[:ROLE_MATCH_READ_LIMIT]
            logger.error(
                "more than %s accounts hold the filtered roles, so only part of that set was "
                "read; designer roster rows whose account fell past that cut are missing from "
                "this list for every filter that names those roles",
                ROLE_MATCH_READ_LIMIT,
            )
        emails = sorted({normalise_email(a.email) for a in accounts})
        # `mode: "insensitive"` because `admitted` is lower-cased and `User.email` is not — the
        # same clause designer_directory:302 uses, for the same reason.
        arms.append({"email": {"in": emails, "mode": "insensitive"}})
    if "never-signed-in" in roles:
        arms.append({"firstSeenAt": None})
    where.setdefault("AND", []).append({"OR": arms or [{"id": {"in": []}}]})
```

**The reserved token is `never-signed-in`, not `no-account`, and the difference is the point.**
"Has no account" is not a fact this system stores; what it stores is
`DesignerRoster.firstSeenAt` — *"Set the first time an account with this email signs in, so an admin
can see which invitations are outstanding rather than guessing"* (`schema.prisma:3962-3964`). Labelling
the option **"Has never signed in"** makes it answerable from a column, needs no second query, and
says something true. Labelling it "No account" would need an unbounded `NOT IN` over every account the
repository has ever had and would still be wrong for a provisioned account that has not signed in.

**The truncation reaches the wire.** `page_payload` has no room for a flag
(`services/pagination.py:14-21`), so both routes return
`page_payload(...) | {"roleMatchTruncated": bool}`. It is additive, and on a deployment that predates
it the client reads `undefined`, which `flagCutNotice` treats as "nothing to say" for exactly this
reason (`cappedList.ts:196-199`). Rendered by `flagCutNotice(truncated, "designers", term)`
(`:195-204`) — the shape built for a boolean-only cut beside a real server search, which is what this
is.

**A note the screen must carry, because it changes what the filter MEANS.** A roster row whose account
is an `ADMIN` is not roster-gated at all (`designers.py:295-298`, `services/designers.py:82`), so
"filter by role = Admin" over this list answers "which empanelled addresses belong to admins", not
"which admins may sign in". The label in §4.8 says so.

**A role filter must never use `assignableRoles`.** `frontend/lib/permissions.ts:139-141` filters the
ladder to tiers at or below the caller's, which is correct for the `admitRole` *picker* on the same
screen (`access/page.tsx:571-575`, `:782-786`) and wrong for a *filter*: an admin must be able to
filter for rows carrying a tier they could not grant, or a master-admin row becomes invisible to every
admin. The filter iterates `ROLES_BY_RANK` (`permissions.ts:119-122`) in full.

## 4.5 Institution

**On the designer roster: a multi-select over a served vocabulary.** `DesignerRoster.institution`
(`schema.prisma:3954`) is free text, so an exact-match filter is only usable behind a picker of the
values that actually exist. New endpoint, and it is the only new one in req 30:

```
GET /designers/roster/institutions          -> {"items": [...], "total": n, "truncated": bool}
```
`SELECT DISTINCT institution FROM "DesignerRoster" WHERE institution IS NOT NULL ORDER BY institution`,
`take = 200 + 1`, trimmed and flagged in the `GET /tasks/options` manner
(`api/routes/tasks.py:1243-1245` — read one past the cap, so the flag is exact). Gated by
`require_designer_roster_manager`. The picker over it is a class-(c) list and takes §3.5's sentences
and E2's `bulk={false}` like every other filter.

The reserved token `none` covers `institution IS NULL` — the `UNASSIGNED_WORKSHOP` precedent
(`record_filters.py:47-53`), for the same reason: without it, ticking every institution silently drops
every row that has none.

**On the access list: it does not exist, and it is not added by a join.** `AccessRoster` has no
`institution` column (verified column by column, `schema.prisma:4169-4226`). A join to
`DesignerRoster.institution` by lower-cased email would filter the access list down to *the subset
that is also empanelled as a designer* while presenting itself as an institution filter — silently
hiding exactly the pending strangers the screen exists to decide about, which is precisely what
`access/page.tsx:98-101` says the widest default is there to prevent. So the access screen does not
offer the filter, and the filter row carries one line saying why:

> `"Institution is not recorded on the allow-list — it is a designer-roster field. Filter by it on the designer roster."`

If the owner wants it, the honest form is a nullable admin-typed `AccessRoster.institution` column, a
one-column migration. That is §6, Q1 — a decision, not a gap in this document.

## 4.6 The four binding rules, as testable statements

Each is written so it can be pasted into a test name. Backend tests go in
`backend/tests/test_roster_filters.py` (new); the web's in
`frontend/e2e/roster-filters-unit.spec.ts` (new, the `dropdown-sweep-unit.spec.ts` shape — pure
assertions over source, because none of this is reachable from a click); Android's in
`RosterFilterWireTest.kt` (new, beside `DesignerRosterWireTest.kt`).

**(i) Empty means everything, BY ABSENCE — not by an all-ticked state.**

- `test_absent_roles_returns_every_tier` — `GET /access/roster` with no `roles` returns rows of every
  `admitRole` including NULL, and the built `where` contains **no** `admitRole` key at all.
- `test_all_eight_ticked_is_not_the_same_request_as_none_ticked` — `?roles=<all eight>` **excludes**
  rows with `admitRole IS NULL`, and absent `roles` includes them. The two must produce different row
  counts on a fixture that has one of each, or the reserved `default` option is not doing its job.
- `test_empty_and_all_blank_roles_do_not_filter` — `?roles=` and `?roles=,,` both behave as absent
  (`resolve_workshop_ids:65-67`'s rule).
- `filters send nothing when nothing is chosen` — the client-side twin: with every multi-select
  cleared, `buildQuery` emits a URL containing none of `roles`, `status`, `institutions`
  (`lib/api.ts:323-330`).
- `every filter multi-select passes bulk={false}` — a source assertion over
  `app/(protected)/admin/**` , because `SearchableSelect.tsx:1123-1129` would otherwise ship a
  "Select all 8" button that produces the state this rule forbids.

**(ii) Suspended and rejected rows stay listed BY DEFAULT.**

- `test_default_access_roster_lists_rejected_and_suspended` — already the server's behaviour
  (`access.py:122-127`) and now pinned against the new `status` multi-select.
- `test_default_designer_roster_lists_suspended` — likewise (`designers.py:106-111`).
- `no filter control defaults to a narrowing value` — a source assertion that the initial state of
  every new filter on both pages is the empty one.
- Android: `AccessStatusFilter` keeps `null to "Everyone"` as a **chip like the others**
  (`AccessRosterScreen.kt:750-766`), because *"A filter you can enter and not leave is how a screen
  ends up looking empty for reasons nothing on it explains"* (`:753-755`), and
  `DesignerRosterScreen`'s standing chip keeps its rule at `:321-324`.
- **The rule is written down in four places already** — `admin/designers/page.tsx:27-29`,
  `designers.py:106-111`, `access/page.tsx:98-101`, `DesignerRosterScreen.kt:321-324` — and a new
  filter must not contradict any of them.

**(iii) Any cap or truncation is STATED ON SCREEN, with the number.**

- `test_role_match_truncation_is_reported_on_the_wire` — a fixture past `ROLE_MATCH_READ_LIMIT`
  returns `roleMatchTruncated: true` and logs at ERROR.
- `test_institutions_endpoint_reports_its_own_cut` — one row past the cap sets `truncated`.
- `a truncated role match renders flagCutNotice` — and `undefined` renders nothing
  (`cappedList.ts:196-199`).
- `the directory cap notice survives` — `admin/designers/page.tsx:432-437` and
  `DesignerRosterScreen.kt:298-304` are not removed by this work.
- Android: **`DesignerRosterScreen`'s walk notice `:277-293` is deleted along with the walk** — it
  describes a device-side truncation that will no longer exist. Deleting the notice without deleting
  the walk, or the reverse, is the failure mode; they land in one commit.

**(iv) Filtering is SERVER-SIDE.**

- `test_every_roster_filter_is_in_the_where` — the route builds a `where` for each parameter and
  applies no post-`take` filtering, the same property `designers.py:275-285` protects for the
  directory: *"a post-take drop is exactly what breaks that inference — it reports a COMPLETE list
  that is missing people."*
- `no .filter() over a fetched page` — a source sweep over both web page files, which
  `access/page.tsx:31-41` already asserts in prose and which now has a test.
- `DesignerRosterScreen makes no on-device filter or sort` — a source assertion that
  `:156-159` and `:331-333` are gone.
- `api.designerRoster declares search, standing, roles, institutions, dateField, dateFrom, dateTo, sort, dir`
  — `WorkshopRepositoryApi.kt:1734-1737`. **Everything in req 30 for that screen is contingent on
  this one interface change**, and the comment at `:1729-1732` recording the deliberate opposite trade
  is rewritten to say what changed and why.

## 4.7 Indexes

Verified against the schema and the migrations: `DesignerRoster` has exactly
`@@index([isActive])` (`schema.prisma:3971`) and `@@index([addedById])` (`:3972`); `AccessRoster` has
exactly `@@index([status])` (`:4231`), `@@index([status, requestedAt])` (`:4232`),
`@@index([addedById])` (`:4233`) and `@@index([decidedById])` (`:4234`). Nothing else.

**Note that both tables' DEFAULT SORT is already unindexed today** — `createdAt desc` on both, with
`ORDER BY … LIMIT/OFFSET` re-running the whole sort per page.

| Table | Serves | Index | Status |
|---|---|---|---|
| `AccessRoster` | default sort + the `added` range | `@@index([createdAt])` | **missing — add** |
| | status chip in the default order (the common query) | `@@index([status, createdAt])` | **missing — add** |
| | role filter in the default order | `@@index([admitRole, createdAt])` | **missing — add.** Bare `admitRole` is 8 values plus NULL and would rarely be chosen; composited with the sort key it is |
| | `requested` range with no status | `@@index([requestedAt])` | **missing — add.** The leading-column rule means `[status, requestedAt]` cannot serve an unqualified range |
| | status filter alone | `@@index([status])` | exists `:4231` |
| | queue: status + requested | `@@index([status, requestedAt])` | exists `:4232` |
| | `joined` / `decided` / `firstSeen` ranges and sorts | `@@index([joinedAt])`, `@@index([decidedAt])`, `@@index([firstSeenAt])` | **missing — add one per `dateField` value that ships** |
| `DesignerRoster` | default sort + the `added` range | `@@index([createdAt])` | **missing — add** |
| | standing chip in the default order | `@@index([isActive, createdAt])` | **missing — add** |
| | `firstSeen` range and sort, and the `never-signed-in` token | `@@index([firstSeenAt])` | **missing — add.** This is the column the whole screen exists to show (`schema.prisma:3962-3964`) |
| | institution filter and sort | `@@index([institution])` | **missing — add.** Also serves the `SELECT DISTINCT` behind §4.5's endpoint |
| | `revoked` range | `@@index([revokedAt])` | **missing — add if that `dateField` ships** |
| | standing alone | `@@index([isActive])` | exists `:3971` |

**Free-text search cannot be indexed as written.** `contains()` builds `ILIKE '%x%'`
(`services/records.py:831`), and a leading wildcard cannot use a btree. Only a `pg_trgm` GIN index
would help and **there is none anywhere in this schema** (searched the migrations for `pg_trgm` and
`USING gin` — absent). The mitigation is that both searches are already server-side and paged, so the
cost is bounded by the page, not by the corpus. Adding `pg_trgm` is out of scope here and is §6, Q4.

One migration, `backend/prisma/migrations/<ts>_roster_filter_indexes/migration.sql`, carrying every
`CREATE INDEX` above. It is additive and takes no locks that matter on tables of this size (about 400
access rows, about 1,300 designer rows per `design_workshop_viewers.py:106`).

## 4.8 Real labels for every new control

`SearchInput` sets `role="searchbox"` with **no `aria-label` and no `<label>`** — the whole component
is 50 lines and its props are `value`, `onChange`, `onSubmit`, `placeholder`
(`components/SearchInput.tsx:9-19`), so the placeholder is its only accessible name. That is asserted
as behaviour by `e2e/design-workshop-viewers.spec.ts:514-527`, which tabs onto the box and polls for
`role === "searchbox"` *because* there is no label to poll for.

**The fix is additive and does not touch that spec.** `SearchInput` gains
`ariaLabel?: string`, defaulting to `undefined`, applied as `aria-label` only when present. All 17
existing callers keep the exact DOM they have today, including the one the spec walks; the two roster
pages pass one.

| Control | Label |
|---|---|
| Access search box | `"Search the allow-list by email, name or note"` |
| Designer search box | `"Search the roster by email, name or institution"` |
| Access standing (multi) | `"Standing"` |
| Access role (multi) | `"Tier they join at"` |
| Designer standing | `"Standing"` |
| Designer role (multi) | `"Tier of the linked account"` — with the hint below it: `"Matched by email. An admin's row is not gated by this roster, so filtering for Admin lists empanelled addresses that belong to admins."` |
| Designer institution (multi) | `"Institution"` |
| Date field (single) | `"Which date"` |
| Date range | fieldset `"Date range"`, with `"From"` and `"To"` as sibling `<label htmlFor>` elements beside `<DateField id>` — **never wrapping the field in a `<label>`**, which folds the calendar button's name into the input's accessible name (`components/forms/DateTimeField.tsx:31-44`) |
| Sortable column header | the column name, with `aria-sort="ascending" \| "descending" \| "none"` on the `<th>` — **the first `aria-sort` in this frontend** (zero occurrences today) |
| Clear-all-filters button | `"Clear every filter"`, shown only when at least one is set |

## 4.9 The controls, both clients

**Web.** Both pages keep their monolithic shape (there is no roster component under
`frontend/components/admin/` — it holds only `deletedWorkshops.ts` and `DeletedWorkshopsCard.tsx`),
and the filter row becomes a shared component so the two pages cannot word one thing two ways:

- `frontend/components/admin/RosterFilterBar.tsx` (new) — the search box, the multi-selects, the
  date-field picker, the range, and the clear-all button. Every `MultiSelectDropdown` in it passes
  `bulk={false}` (E2), `confirmOnSelect={false}` (`Dropdown.tsx:141-151` — *"Set false where the
  control filters a list in place rather than answering a form field"*) and
  `advanceOnSelect={false}`, which both pages already pass to their single-selects
  (`access/page.tsx:619-621`, `designers/page.tsx:426-428`).
- `frontend/components/data/SortableTh.tsx` (new) — `ResizableTh` plus a sort button and `aria-sort`.
  Fourteen lines of existing `<th>` (`components/ResizableTh.tsx:8-14`) plus the affordance; it is
  used by both rosters and is available to `/users` and the record lists later.
- The date range reuses `SearchFilters`' preset vocabulary: `RANGE_IDS`
  (`components/search/SearchFilters.tsx:37-49` — `any, today, 7d, 30d, 90d, month, year, custom`) and
  `resolveRange` (`:95-121`), which turns presets into concrete instants **in the browser**, because
  only the browser knows the reader's clock, and which already carries the local-midnight fix
  (`parseDateInput` `:78-87`). Lift those two exports into
  `frontend/components/data/dateRange.ts` and re-export from `SearchFilters` so nothing moves twice.
  **Do not reuse `components/forms/DateRangeField.tsx`** — despite the name it is the workshop-duration
  *form field*, emitting three hidden inputs, not a filter.
- URL round-trip, in the shape `filtersFromSearchParams` / `filtersToLinkParams`
  (`SearchFilters.tsx:175-200`) and `workshopScopeLinkParams` (`WorkshopScopeSelect.tsx:258-263`)
  already use, so a filtered roster is a link an admin can paste to a colleague.

**Android.**

- `AccessRosterScreen.kt` keeps its five status chips (`:750-780`) and gains a **filter sheet** behind
  a single "Filters" button: the role multi-select, the date-field picker, the range, and the sort.
  Chips stay for status because they are the one filter an admin toggles constantly.
- `DesignerRosterScreen.kt` is the large change: **delete the walk, the device-side sort (`:156-159`)
  and the device-side filter (`:331-333`)**; fetch one page with the same parameters as the web; keep
  the standing chip and its rule (`:321-324`); delete the walk notice `:277-293` in the same commit.
- The role picker uses `SearchableSelectField` with **`searchable = false`** (E3). Eight options is
  exactly `SEARCH_THRESHOLD`, so without the override the ladder would open as a bottom sheet on one
  screen and an anchored menu on another the day a tier is added or removed. `:864-878`'s raw
  `DropdownMenu` for the `admitRole` *picker* stays a menu for the same reason.
- The date range reuses `FieldDateRangeField` (`ui/DateTimeFields.kt:612-677`) — one heading, two typed
  boxes, a `DateRangePicker` dialog `:696-748`, and bidirectional clamping (`clampEnd` `:680-681`,
  `clampStart` `:684-685`) so an end before a start cannot be entered — and the preset vocabulary from
  `SearchScreen.kt`'s `SearchRange` (`:153-162`), whose `resolveDateRange(today)` (`:247-268`) resolves
  at **request** time *"so a screen left open overnight does not keep searching yesterday"*.
- Sorting is a single-select, not tappable headers: these are card lists, not tables.
- Tests that bind and must be updated together: `AccessRosterNavTest.kt`, `AccessRosterWireTest.kt`,
  `DesignerRosterWireTest.kt`, `AppNavigationBadgeTest.kt`, `DashboardTileParityTest.kt`.
---

# 5. WHAT EACH IMPLEMENTATION AGENT DOES

## 5.0 How this is parcelled, and the two rules that keep it parallel

**Single-owner files.** These are touched by exactly one parcel, whatever else that parcel needs to
reach into. Two agents in one of them is a merge conflict in a 17,000-line file.

| File | Owner |
|---|---|
| `android/.../MainActivity.kt` | **A4** — including the `WorkshopField` sentence at `:5891-5928`, which A2 specifies and A4 applies |
| `frontend/components/ui/SearchableSelect.tsx` | **W1** |
| `frontend/components/data/cappedList.ts` | **W2** |
| `android/.../ui/SearchableSelect.kt` | **A1** |
| `backend/app/services/records.py` | **B1** |
| `backend/prisma/migrations/**` | **B5** |

**Two waves, and the second cannot start early.** W1, A1 and B1 change primitives that everything
else calls. They land first, alone, with their own tests green. Everything in wave 2 is parallel.

```
WAVE 1  (must land first, independent of each other)
  B1  backend primitives          W1  web select primitive      A1  Kotlin select primitive
        │                               │                             │
WAVE 2  ▼ (all parallel)               ▼                             ▼
  B2 access route            W2 cappedList "search" arm     A2 workshop pickers
  B3 designer route          W3 lib/workshopOptions.ts      A3 LocationFields (class b)
  B4 DW/workshops routes     W4 DW callers (11)             A4 MainActivity: C1 + R2b + WorkshopField
  B5 index migration         W5 Workshop callers (10)       A5 outbox O1 + the unfile sentinel
                             W6 roster shared controls      A6 AccessRosterScreen
                             W7 /admin/access               A7 DesignerRosterScreen + the Retrofit API
                             W8 /admin/designers            A8 the three class-(d) controls
```

W7 and W8 additionally need B2 and B3 on the wire to be exercisable end to end; they can be written
against the contract in §4.1 before those land.

---

## Backend

### B1 — the filter grammar (wave 1)
**Territory:** `backend/app/services/record_filters.py`, `backend/app/services/records.py`.
1. Add `enum_filter_list_or_422(raw, allowed, *, field)` to `record_filters.py`, beside
   `resolve_types` (`:243-275`) whose grammar it copies: both spellings, absent/empty/all-blank means
   `None` (do not filter), unknown token is a 422 naming the valid values.
2. Nothing else in `records.py` changes; it is listed as territory only because
   `enum_filter_or_422` (`:960-980`) is its single-value sibling and the two docstrings must
   cross-reference.
3. Tests: `backend/tests/test_record_filters.py` — both spellings, the three absent forms, the 422.

### B2 — `GET /access/roster` (wave 2)
**Territory:** `backend/app/api/routes/access.py`, `backend/app/services/access_roster.py`.
Implement §4.1 and §4.2 for the access list: `status` becomes multi-valued (a single value must stay
behaviourally identical — Android sends one today, `WorkshopRepositoryApi.kt:1783`), add `roles` with
the reserved `default`, `dateField`/`dateFrom`/`dateTo`, `sort`/`dir`. Move onto `count_and_page`
(`records.py:1395-1419`) and delete the hand-rolled `asyncio.gather` and the comment at `:160-164`
that says this read bypasses it. Return `roleMatchTruncated` — always `false` on this route, because
the access role filter is a real column and needs no second read; it is on the envelope so both
rosters answer the same shape. Tests: §4.6 (i)-(iv) for this route.

### B3 — `GET /designers/roster` and the institutions endpoint (wave 2)
**Territory:** `backend/app/api/routes/designers.py`, `backend/app/services/designers.py`.
Implement §4.1, §4.2 and **§4.4's two-query role filter**, copying `active_roster_emails`'
shape exactly (`services/design_workshop_viewers.py:366-410`): `take = CAP + 1`, trim, log at ERROR,
return the flag. Add `standing` beside `activeOnly` with the 422 on disagreement. Add
`GET /designers/roster/institutions` per §4.5. Move onto `count_and_page`. Do **not** touch
`designer_directory` — §4.4 records why it cannot serve the role filter, and its 500-row cap and its
`WORKSHOP_CAPABLE_ROLES` clause (`:79`, `:286`) stay exactly as they are.

### B4 — the two workshop list routes (wave 2)
**Territory:** `backend/app/api/routes/design_workshops.py`, `backend/app/api/routes/workshops.py`.
1. **W-B1:** `design_workshops.py:1319-1322` — wrap the order in `with_id_tiebreak`. One line, and it
   is the difference between a stable paged walk and one that silently repeats and skips
   (`records.py:1364-1369`). Every paged walk of design workshops on either client is affected.
2. **W-B2:** `workshops.py:438` `GET /workshops/requestable` returns a bare array with no `total`
   (`:479-481`), so `WorkshopAccessRequestPanel` (#19) physically cannot state its cap. Give it
   `page_payload` or, if the bare array must stay for an older client, a `truncated` flag read one
   past `WORKSHOP_REQUEST_MAX` in the `GET /tasks/options` manner (`tasks.py:1243-1245`).
3. `pageSize` on `design_workshops.py:1228` gains `le=100` so it refuses rather than silently clamps,
   matching `workshops.py:229`.

### B5 — the index migration (wave 2, single file)
**Territory:** `backend/prisma/schema.prisma` (the two `@@index` blocks at `:3971-3972` and
`:4231-4234` only) and one new migration directory.
Every index in §4.7. Additive; no column changes. Do not touch any other model.

### B6 — `unaccent` (gated on §6 Q2, do not start unasked)
**Territory:** a migration enabling the extension, plus `services/records.py::contains`.
Only if the owner says yes. §2.11 C1 records what is lost without it.

---

## Web

### W1 — the select primitive (wave 1)
**Territory:** `frontend/components/ui/SearchableSelect.tsx`,
`frontend/components/ui/Dropdown.tsx`, `frontend/components/ui/selectFilter.ts`.
1. **E1 `serverQuery`** on both `SearchableSelectProps` and `SearchableMultiSelectProps`: forces
   `withSearch`, bypasses `filterOptions`, three-way empty arm, and the pin snapshot re-taken on
   `options` identity change (C4) rather than on every query change.
2. **E2 `bulk?: boolean`** (default `true`) on `SearchableMultiSelect` and `MultiSelectDropdown`.
3. **E4 `noneLabel?: string`** on `SearchableSelect` and `ComboBox`: a first, ungrouped `value: ""`
   row, matching Android's `includeNone` (`SearchableSelect.kt:192-202`).
4. `selectFilter.ts` is unchanged except for doc updates — `groupRows`, `capNoticeSentence`,
   `SEARCH_THRESHOLD` and `RENDER_CAP` all carry this design as written.
5. Tests: extend `frontend/e2e/dropdown-sweep-unit.spec.ts` — the grouped-index invariant at `:347-361`
   and the pin snapshot at `:332` must both still hold, plus new cases for the none row, for
   `bulk={false}`, and for the three-way empty arm.

### W2 — the truncation vocabulary (wave 2)
**Territory:** `frontend/components/data/cappedList.ts`,
`frontend/components/data/CappedListNotice.tsx`.
Add `"search"` to `CutReach` (`:117-134`) and its sentence to `cappedListNotice` (`:157-166`), with
the guard `:181-188` already states: it is legitimate only where the term goes into the request.
Update that header to say the arm now exists and what qualifies a caller for it.

### W3 — `frontend/lib/workshopOptions.ts` (wave 2, new file)
**Territory:** the new file only. Everything in §2.2-§2.10: the four `NO_*` constants, the two option
builders, `workshopListNotice`, `workshopEmptyLabel`, and the two fetching hooks. Both hooks ask for
`pageSize: RENDER_CAP`. Pure functions separated from the hooks so they are testable without a DOM,
the shape `selectFilter.ts:16-20` argues for. New spec `frontend/e2e/workshop-options-unit.spec.ts`
covering the label shape, the group order, the sort, and all six sentences of §3.5.

### W4 — the eleven `DesignWorkshop` callers (wave 2)
**Territory:** `components/forms/DesignWorkshopSelect.tsx`,
`app/(protected)/design-workshops/page.tsx` (the `ContinueOnAllocatedWorkshop` block only),
`app/(protected)/sketches-and-prototypes/page.tsx`, `app/(protected)/design-review/page.tsx`,
`components/settings/DesignWorkshopViewersPanel.tsx`,
`components/settings/DesignWorkshopInspectorsPanel.tsx`,
`components/designworkshop/AdoptLocalDraftDialog.tsx`, `app/(protected)/questionnaires/page.tsx`,
`app/(protected)/questionnaires/[id]/page.tsx`, `components/questionnaires/UploadDialog.tsx`,
`components/questionnaires/ReuseDialog.tsx`.
Apply §2.12's DesignWorkshop table row by row. The two that are behaviour changes rather than
refactors, and which must be called out in the commit message: **#1's failed-list-reads-as-empty**
(`DesignWorkshopSelect.tsx:168,176,254-258`) and **#1 gaining `noneLabel`, which is the first time the
web can un-file a record from a design workshop** (§2.7).

### W5 — the ten `Workshop` callers (wave 2)
**Territory:** `components/forms/WorkshopSelect.tsx`, `components/WorkshopScopeSelect.tsx`,
`components/designworkshop/StageWorkshopField.tsx`, `components/FunnelFilters.tsx`,
`app/(protected)/design-workshops/page.tsx` (the "Start from a recorded workshop" block only),
`app/(protected)/settings/tasks/page.tsx`, `components/settings/WorkshopRosterPanel.tsx`,
`components/settings/WorkshopAccessRequestPanel.tsx`, `app/(protected)/media/page.tsx`,
`app/(protected)/data/page.tsx`.
Apply §2.12's Workshop table. **The highest-value single fix in this parcel is #13**
`WorkshopScopeSelect`: 196 workshops, 100 fetched, 80 drawn, nothing said, on five screens including
`/search` and `/map`, plus a failed fetch that falls through to "all workshops" over an empty array
(`:109-113`). It violates R4 outright.
**Coordination note:** W4 and W5 both touch `app/(protected)/design-workshops/page.tsx`, in
non-overlapping blocks (`:1460-1517` versus `:501-948`). Whichever lands second rebases.

### W6 — the shared roster controls (wave 2)
**Territory:** `frontend/components/SearchInput.tsx`, `frontend/components/data/SortableTh.tsx` (new),
`frontend/components/data/dateRange.ts` (new), `frontend/components/admin/RosterFilterBar.tsx` (new),
`frontend/components/search/SearchFilters.tsx` (re-export only).
Everything in §4.8 and §4.9's web half. `SearchInput` gains `ariaLabel?: string` — **additive, and
`e2e/design-workshop-viewers.spec.ts:514-527` must still pass unchanged**, which it does as long as
that caller passes none. `SortableTh` carries the first `aria-sort` in this frontend. `dateRange.ts`
lifts `RANGE_IDS`, `RANGE_OPTIONS`, `parseDateInput` and `resolveRange` out of `SearchFilters.tsx`
(`:37-121`) and `SearchFilters` re-exports them so nothing moves twice.

### W7 — `/admin/access` (wave 2)
**Territory:** `frontend/app/(protected)/admin/access/page.tsx`,
`frontend/lib/accessRoster.ts`.
Mount `RosterFilterBar` and `SortableTh`; thread every §4.1 parameter through `listAccessRoster`;
render `flagCutNotice` for `roleMatchTruncated`; keep the queue's independent fetch, its ceiling
notice (`:472-484`) and the step-back guard (`:156`, `:179`). **Preserve `:31-41`'s invariant** — no
`.filter()` over a fetched page anywhere in the file — and extend that comment to cover the new
controls. Add the §4.5 sentence explaining why institution is not offered here.

### W8 — `/admin/designers` (wave 2)
**Territory:** `frontend/app/(protected)/admin/designers/page.tsx`,
`frontend/lib/designers.ts`.
The same, plus the institutions picker and the role picker with its §4.8 hint. **The module docstring
at `:27-29` is the constraint, not decoration:** *"SUSPENDED ROWS ARE LISTED BY DEFAULT."* Keep the
directory cap notice (`:432-437`) — it is about a different cap and this work does not remove it.

---

## Android

### A1 — the select primitive (wave 1)
**Territory:** `android/.../ui/SearchableSelect.kt`.
**E3**, both halves: the `searchable: Boolean? = null` override on `SearchableSelectField` and
`SearchableMultiSelectField`, and an `else` arm in the `!searchable` `DropdownMenu` branch
(`:234-296`) drawing `emptyMessage` as a disabled item when `options.isEmpty()`. `SEARCH_THRESHOLD`
at `:120` does not move; its comment gains the sentence that record-backed lists now pass the flag.
Keep `createAction` reachable in both surfaces (`:204-212`, `:269-294`, `:771-796`) — it is the escape
that survives every empty state in §3.5.
Test: a JVM/compose test that an empty non-searchable field draws its `emptyMessage`, which is the
regression this parcel exists to prevent.

### A2 — the workshop pickers (wave 2)
**Territory:** `android/.../ui/WorkshopOptions.kt` (new),
`android/.../ui/DesignWorkshopPicker.kt`, `SketchesAndPrototypesScreen.kt`, `DesignReviewScreen.kt`,
`QuestionnaireListScreen.kt`, `QuestionnaireDetailScreen.kt`, `QuestionnaireReuseUi.kt`,
`QuestionnaireWorkshopPicker.kt`, `designworkshop/WorkshopListScreen.kt` (the move-draft picker only).
Build `WorkshopOptions.kt` as the byte-parallel twin of `lib/workshopOptions.ts` — same four
constants, same label shape, same headings, same sentences — pure and JVM-tested, the way
`DwWorkshopCreation.kt:482-488` argues for. Point all of these at it. `DesignWorkshopField` takes
`searchable = false` plus the cap sentence (§3.6), and its `state.listed` gate at `:309-321` splits
into §3.5's five states, ending the silence on a failed list. **Hand A4 the exact patch for
`WorkshopField` at `MainActivity.kt:5891-5928`; do not edit that file.**

### A3 — the address card (wave 2)
**Territory:** `android/.../ui/LocationFields.kt`, `android/.../ui/DesignerProfileScreen.kt`.
§3.2's B1, B2 and B3: the computed asterisk, the honest sentence in place of
`"Loading the state list…"` (`:1280-1284`), and `fetchedAt` on `AddressReferenceCache`
(`:272-278`) mirroring `DwReferenceStore.kt:411`. Give the designer-profile state picker
(`DesignerProfileScreen.kt:1195-1204`) the empty sentence it has never had; leave `:1726-1741`
exactly as it is — it is the model.

### A4 — the record forms (wave 2) — **owns `MainActivity.kt`**
**Territory:** `android/.../MainActivity.kt`, `android/.../data/WorkshopRepository.kt` (the record
fetchers at `:3849-3868` only), `android/.../data/DwReferenceStore.kt`.
1. **C1:** point `crafts()`, `artisans()`, `products()`, `productsForArtisan()` and `tools()` at the
   ALL-scoped `DwReferenceStore` entries (`DwReferenceStore.kt:39-51`), which are already keyed by
   model alone and already shared across every workshop on the device. **No new storage layer, no
   Room, no KSP.** Leave `workshopsIMaySubmitTo()` (`:3929-3930`) and `designWorkshops()` alone — R6
   forbids caching them, and `:3918-3923` says so.
2. **R2b:** the four required-closed-list validators stand down when their list is empty —
   artisan `:9432`, product `:9433`, tool `:10139`, craft `:7726`. §3.3 gives the expression.
   `:9445-9447` currently returns before the save coroutine is launched; that is the
   `OFFLINE_STATES` incident reproduced.
3. Every silent single-select in §1.4 gets its §3.5 sentence, and every `emptyMessage` that asserts
   non-existence is replaced.
4. Apply A2's patch to `WorkshopField` `:5891-5928` — the scope sentence its web twin prints in all
   three states.

### A5 — the outbox, and the unfile sentinel (wave 2)
**Territory:** `android/.../data/Offline.kt`, `android/.../data/WorkshopSync.kt`,
`android/.../data/ApiClient.kt`, `android/.../ui/OfflineOutboxTray.kt`,
`android/.../ui/DesignWorkshopPicker.kt` (the `value()` accessor only — coordinate with A2).
1. **O1:** §3.7's fifth outcome — `danglingField`, the **Re-pick it** button, and the sentence that
   names the field. `discard` stays person-confirmed (`Offline.kt:709`,
   `OfflineOutboxTray.kt:70-72`).
2. **The unfile sentinel** (§2.7): `explicitNulls = false` on `ApiClient.json` means a cleared
   workshop is an omitted key and the server leaves the stored value alone, so Android's `includeNone`
   row reports success and does nothing (`DesignWorkshopPicker.kt:80-92`). The server already accepts
   an explicit null — `designWorkshopId` and `workshopId` are both in `CLEARABLE_KEYS`
   (`services/records.py:627-640`). Fix both columns at once, as that comment says it must be.

### A6 — `AccessRosterScreen` (wave 2)
**Territory:** `android/.../ui/AccessRosterScreen.kt`.
§4.9's Android half for this screen: keep the five chips and their rule (`:750-766`), add the filter
sheet, the sort, and `flagCutNotice`'s Kotlin twin for `roleMatchTruncated`. The role picker takes
`searchable = false` (E3); `:864-878`'s raw menu for the `admitRole` *picker* stays a menu.

### A7 — `DesignerRosterScreen` and the Retrofit interface (wave 2)
**Territory:** `android/.../ui/designworkshop/DesignerRosterScreen.kt`,
`android/.../data/WorkshopRepositoryApi.kt`, `android/.../data/WorkshopRepository.kt` (the roster
functions at `:1920-1934` only — coordinate with A4, which owns `:3849-3868` of the same file).
**The largest single Android change in req 30.** Declare `search`, `standing`, `roles`,
`institutions`, `dateField`, `dateFrom`, `dateTo`, `sort` and `dir` on `designerRoster`
(`WorkshopRepositoryApi.kt:1734-1737`) and rewrite the comment at `:1729-1732` that records the
opposite trade. Delete `walkPagedListing`, the device-side sort (`:156-159`), the device-side filter
(`:331-333`) and the walk notice (`:277-293`) **in one commit** — a notice describing a truncation
that no longer happens is worse than none. Keep the standing chip and its rule (`:321-324`) and the
directory-cap notice (`:298-304`).

### A8 — the three controls outside the pattern (wave 2)
**Territory:** `android/.../ui/MyAiKeysScreen.kt`, `android/.../ui/SearchScreen.kt`.
§3.4: route both through `SearchableSelectField`. `AccessRosterScreen.kt:864-878` is A6's and stays a
menu by design.

---

## 5.1 What "done" looks like

**Each line is a check, and the ones that pass today are marked so — because a criterion with no
verdict beside it is what let this document's headline claim nothing was built while most of it
was.** A mark is a statement about the tree on 2026-08-31 and about nothing else: re-run the command,
do not trust the tick.

- ☐ No picker anywhere asks for a page size other than `RENDER_CAP` / the Android page constant, and
  no screen prints two truncation sentences with two different totals. **Open** — this is the
  call-site sweep §2.8 rule 1 describes, and §1.1 lists the controls still asking for 100 into a
  control that draws 80 (#3, #5, #8, #9, #10, #13).
- ☐ Grepping the web for a workshop label built inline returns nothing: `lib/workshopOptions.ts` is
  the only place a title and a hint are assembled. **Open** — the module exists and is the home; the
  migration of every caller onto it is not finished.
- ☑ Grepping Android for `options.size >= SEARCH_THRESHOLD` returns one hit, inside
  `SearchableSelect.kt`. **Passes** — verified 2026-08-31; E3's `searchable` override is what made it
  one, and the record-backed call sites now pass the flag rather than letting the count decide.
- ☐ Every dropdown that can be empty renders one of §3.5's six sentences, and no control anywhere
  says "there are none" from a read that may have failed. **Substantially done, not finished.** The
  two workshop pickers, the address card, the four register controls on the record forms, the search
  filters and the AI-model picker all do. What has NOT been swept is every remaining `emptyMessage`
  literal on both clients — `grep -rn "No .* available" android/app/src/main` is the fastest way to
  find the next one, and each hit is a claim about a repository made from a read that may have timed
  out.
- ☑ Both roster routes accept every §4.1 parameter, page through `count_and_page`, and neither client
  runs a `.filter()` or a `sortedWith` over a fetched page. **Passes** — `sort`/`dir` are declared on
  `access.py` and `designers.py`, with the roles/institutions grammar beside them.
- ☑ `roleMatchTruncated` and the institutions endpoint's `truncated` both reach a sentence on screen.
  **Passes** — verified 2026-08-31: `components/admin/RosterFilterBar.tsx` turns them into
  `roleMatchCutNotice` and `institutionCutNotice`, both roster pages mount it, and the two Android
  roster screens carry the same pair through `ui/RosterFilters.kt`.

**Three that were not on this list and should have been**, because each is a whole section's point
rather than a call-site sweep:

- ☑ **The web keeps the four registers on the device, and keeps NEITHER access list.** `grep -n
  "ReferenceRegister" frontend/lib/referenceCache.ts` — the type is a closed union of the four, so
  R6 is enforced by the compiler and not by a comment. A `designWorkshop` cache does not compile.
- ☑ **A queued record whose id 404s has a way out on both clients.** `grep -rn "danglingField"` finds
  it in `android/.../data/Offline.kt` and `frontend/lib/offline.ts`; the re-pick list is fetched LIVE
  on both, because it is an access-adjacent question (R6) and a cached answer would offer a grant
  revoked in March.
- ☑ **`GET /reference/address`'s `version` is honoured by both clients**, which was the one
  server-provided invalidation signal in the API and which nothing read. `reference.py`'s docstring
  now records that it is load-bearing, so a state added without a bump is a defect rather than a
  tidiness question.

---

# 6. OPEN QUESTIONS FOR THE OWNER

Five, and only these. Everything else in this document is settled from the code.

**Q1 — Institution on the allow-list: accept the ruling, or add the column?**
`AccessRoster` has no `institution` column (`schema.prisma:4168-4235`). §4.5 rules that the filter is
offered on the designer roster only, because a join by email would silently narrow the access list to
the subset that is also empanelled while presenting itself as an institution filter. The alternative
is a nullable, admin-typed `AccessRoster.institution` — one column, one migration, one form field, and
it would then be filterable exactly like the designer roster's. **Default if no answer: the ruling.**

**Q2 — Is `unaccent` worth a Postgres extension?**
Moving the workshop pickers' search boxes to the server (§2.8) is what makes them honest, and it costs
the browser's diacritic folding (`selectFilter.ts:101-108`): "Ahmedabad" finds "Ahmedābād" today and
will not. The fix is `CREATE EXTENSION unaccent` plus `unaccent()` inside `contains`
(`services/records.py:831`), which affects every search box in the product, not just this one.
**Default if no answer: ship the server search without it and note the regression in the release
notes**, because a box that reaches page 4 with a missing accent is still better than one that cannot
reach page 4 at all.

**Q3 — Which date fields ship on the access list?**
Five are available — added, requested, decided, joined, first-seen — and each costs an index (§4.7).
Three are obviously useful to an admin holding a message from somebody who cannot sign in: **added**,
**requested**, **first-seen**. **Default if no answer: those three**, with `decided` and `joined`
held back until somebody asks.

**Q4 — `pg_trgm` for the free-text boxes?**
`ILIKE '%x%'` cannot use a btree and there is no trigram index anywhere in this schema. At about 400
access rows and about 1,300 designer rows this does not matter; it will at ten times that.
**Default if no answer: no**, revisit when a roster passes ten thousand rows.

**Q5 — Does the invitation-first view stay the Android default?**
§4.3 rules that both clients default to `added desc` and Android's device-side sort
(`DesignerRosterScreen.kt:156-159`) is deleted, with "outstanding invitations first" surviving as a
named sort. That comment argues an admin opens this screen to answer *"who have I added who has not
turned up"*. If that is right often enough, the Android default could instead be
`sort=firstSeen&dir=desc` — the same rows, now paged correctly, but a client whose default order
differs from the web's. **Default if no answer: one default on both clients, `added desc`.**
