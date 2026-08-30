# Implementation plan

Working document. Survives session restarts. Companion to `RECON_FINDINGS.md`,
which holds the 13 completed reconnaissance area maps with `path:line` citations.

---

## 0. Session context — read this first after a restart

**The repository moved.** It used to live at `F:\Portal_Development_Designer` on a
USB-attached HDD. That enclosure failed mid-session: its USB-SATA bridge crashed
and wrote its own Bulk-Only Transport frames (`USBC`, `dCBWDataTransferLength =
0x1000`) onto the platters, destroying MFT records 0-3 and `$MFTMirr`. The volume
no longer mounts. The disk itself is healthy; the caddy is the fault.

| What | Where |
|---|---|
| Working copy | `C:\dev\designer-portal` (clone of `github.com/cxacraftecosystem-ui/designer-portal`, at `7c60e81` "Cut 0.0.3") |
| Centre of Excellence site | `C:\dev\cxa-cms` (clone of `github.com/cxacraftecosystem-ui/cxa-cms`) |
| Recon findings | `RECON_FINDINGS.md` in this repo |
| Recovered secrets | `C:\Recovered-env` |
| Disk recovery kit + volume index | `C:\Users\anujk\Desktop\RESCUE-F` |

**Outstanding recovery work** (not blocking development):

- `designer-portal`'s real `.env` (8,285 B) is not yet recovered — its MFT record
  was destroyed. `RUN-CARVE-ENV.bat` carves it from raw disk by content. `.env.vercel`
  (735 B) *was* recovered, as was the Android signing keystore.
- `RUN-EXTRACT.bat` will pull the remaining ~22 GB using `mft-index2.json`.
- **Do not run chkdsk, format, or any write against that drive.** Reconnect the bare
  disk over native SATA once the laptop is repaired, then finish recovery.
- **Rotate the credentials that came off it**: `AWS_SECRET_ACCESS_KEY`,
  `SUPABASE_SERVICE_ROLE_KEY`, `VERCEL_TOKEN`, Stripe, Anthropic, OpenAI.

**Re-index CodeGraph before code work:** `INDEX-CODEGRAPH.bat`, or
`codegraph init .` from the repo root. The old index died with the F: drive.

---

## 1. URGENT — auth: allow-listed designers refused at sign-in

Reported live: `sandycraft3@gmail.com` is on the allow-list but Google sign-in
answers *"Your designer access has been suspended."*

### Diagnosis (verified in code, not inferred)

There are **two independent gates**, and passing one does not pass the other:

| Gate | Table | Scope |
|---|---|---|
| Platform allow-list | `AccessRoster` | everybody |
| **Empanelment** | `DesignerRoster` | only accounts whose role is `DESIGNER` |

`assert_roster_admits()` — `backend/app/api/routes/auth.py:251`:

```python
if role_value(user) != "DESIGNER":
    return
if await roster_allows(getattr(user, "email", None)):
    return
raise HTTPException(403, DESIGNER_SUSPENDED_DETAIL,
                    headers=_access_headers(DESIGNER_SUSPENDED_STATUS))
```

`roster_allows()` — `backend/app/services/designers.py:82`:

```python
row = await db.designerroster.find_first(where={"email": address, "isActive": True})
```

So the account is promoted to `DESIGNER` by `AccessRoster.admitRole`
(`auth.py:347`, `schema.prisma:4061`) and then refused by the empanelment gate,
because being allow-listed never created a `DesignerRoster` row.

**Immediate manual remedy** (owner is doing this by hand): `/admin/designers` →
Restore the suspended row, or empanel the address. `POST /designers/roster`
returns 409 naming the existing row if one is already there. Requires Admin+
(`require_designer_roster_manager`, `deps.py:772`).

Diagnostic/repair script already written: `scripts/fix-designer-empanelment.py`
(`--fix` to write; distinguishes exact-active / exact-suspended / Gmail near-miss).

### Fix 1 — auto-empanel allow-listed designers

**Requirement:** anyone on the allow-list as a designer is empanelled by default.

**THE ONE RULE THAT MUST NOT BE GOT WRONG: create only where NO row exists.**
Never reactivate a suspended `DesignerRoster` row. Suspension is a deliberate
revocation — the roster suspends rather than deletes precisely so the record
survives — and auto-reviving from the allow-list would silently undo every
revocation an admin has ever made. Allow-listing grants empanelment to people who
never had one; it does not overturn a withdrawal.

**Work:**

1. New service function in `backend/app/services/designers.py`:
   ```python
   async def ensure_empanelled(email, *, actor_id=None, note=None) -> bool:
       """Create an ACTIVE DesignerRoster row iff no row exists. Never reactivates."""
   ```
   Must stamp `addedById` and a `notes` value recording that this was automatic,
   so an audit can tell an admin's empanelment from a derived one.

2. Call it in `login()` — `auth.py`, **between** the Google promotion and
   `assert_roster_admits(user)` at `:439`. That is the only point where the final
   role and the admitted `access` row are both in hand, and it covers both the
   password and Google branches.

3. Call it from the allow-list approval path (`backend/app/api/routes/access.py`)
   when a row is approved or edited with `admitRole == DESIGNER`, so the roster
   screen shows the person before their first sign-in rather than after.

4. **Backfill** for people already stuck: every `AccessRoster` row with
   `status == ACTIVE` and `admitRole == DESIGNER`, plus every `User` with
   `role == DESIGNER`, that has no `DesignerRoster` row at all. Ship as a script
   under `scripts/`, dry-run by default, printing what it would create.

5. Tests: extend `backend/tests/test_platform_access_gate.py` — it already pins
   these refusals and asserts `set(body) == {"detail"}` on the refusal body.
   Add: allow-listed designer with no roster row now signs in; **suspended roster
   row still refuses** (the regression guard for the rule above).

### Fix 2 — Gmail alias canonicalisation

**The trap:** `normalise_email()` (`designers.py:72`) is only `.strip().lower()`,
and it is the canonical key for **both** rosters. Google is the only sign-in path
for designers. So `sandy.craft3@gmail.com` and `sandycraft3@gmail.com` are one
mailbox to Google and two different keys here — a row that looks correct on the
admin screen and can never match. This is a latent cause of exactly the refusal
reported above, and it will recur.

**Work:**

1. Add `canonical_email()` beside `normalise_email()`: strip dots and any
   `+suffix` from the local part, fold `googlemail.com` → `gmail.com`, **for
   Gmail domains only** — dots are significant elsewhere.
2. Apply on **write** in both rosters, so no new unmatchable row can be created.
3. Apply on **lookup** in `roster_allows`, `designer_empanelment_admits` and
   `access_row`, checking both the literal and canonical forms (one indexed
   `IN` query, not a scan) so rows written before this still resolve.
4. Backfill script that **reports collisions rather than merging them**. Two
   existing rows can canonicalise to one address; silently merging destroys one
   admin's record. Report, let a human decide.
5. Admin screen warns when a new roster email is Gmail-equivalent to an existing
   row, instead of failing on the unique index with no explanation on screen.

**Do not** change what the refusal *sentences* say. `auth.py:40-113` documents a
deliberate, owner-approved decision to keep the four refusals distinct, and
`test_platform_access_gate.py` pins them.

---

## 2. The 27 requested changes

Status as of the recon pass. Full evidence in `RECON_FINDINGS.md`.

| # | Requirement | Recon | Notes |
|---|---|---|---|
| 1 | Update landing page for recent changes | ✅ area 3 + 5 | 13 bands inventoried; no raster assets in repo |
| 2 | Update web walkthrough | ✅ area 4 | 19 steps; coverage gaps identified |
| 3 | Walkthrough on Android | ✅ area 2 | ~~No onboarding exists on Android at all~~ **WRONG — see correction below** |
| 4 | Evaluate a guided tour | — | No tour library in deps; build on framer-motion/radix, do not add one |
| 5 | Sketches: single upload feeding both cards | ✅ area 7 | |
| 6 | Make the two processing cards consistent | ✅ area 7 | |
| 7 | Same for Prototypes where applicable | ✅ area 8 | |
| 8 | File-format description full width | ✅ area 1 | **Root cause: `max-w-prose` at `SketchTraceField.tsx:1656`.** Android twin already correct |
| 9-13 | Unified workshop selection | ❌ **MISSING** | The one agent that died. **Re-run first.** |
| 14 | Experience → Years + Months | ✅ area 10 | |
| 15 | IIT KGP + DC Handicrafts logos | ✅ area 3 | Assets at `C:\Users\anujk\Desktop\*.svg/.png`; optimiser written |
| 16 | Logo links | ✅ | IIT `https://www.iitkgp.ac.in/`, DCH `https://handicrafts.nic.in/`, CoE `https://cxa-cms.vercel.app/` |
| 17 | Centre of Excellence integration | ✅ area 13 | **Decision: redirect, not import** (owner's call). No `/centre-of-excellence` route exists — the CoE page is the site root |
| 18 | Singular sketch upload (emphasis) | ✅ area 7 | |
| 19 | Android walkthrough parity (emphasis) | ✅ area 2 | |
| 20 | Cross-client consistency | ✅ | |
| 21-26 | Feedback + behaviour tracking + methodology | ✅ areas 6, 11 | Feedback exists; **zero** navigation/usage tracking on either client |
| 27 | Edit a design prototype workshop | ✅ area 12 | |

### Correction to row 3, made 2026-08-29 while implementing it

**"No onboarding exists on Android at all" was false, and it is the one line in this table that
would have caused real damage.** It is a mis-summary of `RECON_FINDINGS.md` area 2, which says the
opposite in its own first sentence — a first-run walkthrough ALREADY EXISTS and is fully wired:
`NavDestination.WALKTHROUGH` is an ungated root entry opening a 12-step Material3 dialog behind a
SharedPreferences flag — and whose first listed trap is, verbatim, **"THERE IS ALREADY A
WALKTHROUGH. Do not build a second one."**

The implementing agent checked the claim against the live tree instead of trusting this table, found
the existing feature, and re-scoped from "build onboarding" to "upgrade the onboarding that ships".
Had it trusted this row, Android would now carry two walkthroughs disagreeing about what the product
does — the exact "two answers to one question" failure `MainActivity.kt:575-586` records deleting
once already.

So requirement 3/19 was never a greenfield screen. What it actually needed, and what was done:
the dialog lifted out of `MainActivity` into `ui/WalkthroughScreen.kt` + `ui/WalkthroughSteps.kt`,
the step content brought up to parity with the web's journey, a Settings entry point added beside
the existing menu one, and the first-frame flash fixed — the seen-flag is now read in the
initialiser rather than in a `LaunchedEffect`, which had let the real screen paint for one frame
before the walkthrough arrived over it.

**The lesson for the rest of this table: it is a summary, and at least one of its cells inverted the
finding it summarised. Check `RECON_FINDINGS.md` — and then the code — before building from a row
here.**

### Immediately actionable, already pinpointed

- **Req 8**: delete `max-w-prose` from the `<ul>` at
  `frontend/components/sketches/upload/SketchTraceField.tsx:1656`. 65ch against a
  12px font ≈ 390-420px while every ancestor is full width. Android's
  `DwSketchTraceExportCard.kt` already renders it in a `Column(fillMaxWidth())`
  and carries the same five strings verbatim, so parity needs no Android change.
- **Req 11 (grouped workshop select)**: `SelectOption` already supports `group`,
  rendering a heading over a run of rows — `frontend/components/ui/selectFilter.ts::groupRows`.
  Do not build a new control.
- **Req 22-25**: the instrumentation slot is identified and empty — a pure-ASGI
  middleware between `install_rate_limit(app)` (`backend/app/main.py:584`) and
  `add_middleware(CORSMiddleware, ...)` (`:585`): inside CORS, outside the router.
  Per-user identity attaches at `get_current_user` (`deps.py:624`). The two halves
  must be stitched through `scope["state"]` — middleware cannot see the user, the
  DI point cannot see the status or duration.

### Known traps (from recon; full list in `RECON_FINDINGS.md`)

- The name "analytics" is taken by the cross-workshop **content** comparison at
  every layer. A usage-analytics feature needs its own prefix (`/usage`).
- A DB write per request will fight `DATABASE_CONNECTION_LIMIT = 10`. Batch it.
- `deps.py:778` contains a live documentation defect: it claims `firstSeenAt`
  reveals who has *stopped* using the app. It cannot — it is write-once.
- `frontend/app/(protected)/activity/page.tsx:194-204` renders the "Processes"
  group **twice** (two identical literals). One-line deletion.
- Behaviour tracking of designers is a new personal-data category with no consent
  path, in a codebase that already models consent explicitly for audio.

---

## 2A. Additional requirements — added 2026-08-29

`sandycraft3@gmail.com` was **a missing `DesignerRoster` row**, not a Gmail
near-miss, and adding it on `/admin/designers` fixed the sign-in. That settles the
priority between the two auth fixes: **Fix 1 (auto-empanelment) is the one that
matters in practice**; Fix 2 remains worth doing as prevention.

### 28. Wire in auto-empanelment for existing allow-listed designers

Confirmed as wanted. Build exactly as specified in §1 Fix 1 — including the
create-only-where-no-row-exists rule, which is what stops it reviving revocations.
The backfill in step 4 is the part that clears the people already stuck behind
this today, so it is not optional.

### 29. My designer profile is missing district and the map point

**Verified.** `model DesignerProfile` (`schema.prisma:3946-3990`) carries a flat
`addressLine, city, state, pincode` and **no `district`, no coordinates**. Every
other record page uses `model Location` (`schema.prisma:473`), which splits
address into two groups on purpose:

- **PROVENANCE** — `latitude, longitude, altitude, accuracy, capturedAt,
  placeName, address`. Where the DEVICE was. Written automatically.
- **STATED ADDRESS** — `state, district, village, pincode, subjectLatitude,
  subjectLongitude`. Where the SUBJECT is. Only a person may write it.

That split exists because fifteen live artisan records carry Kharagpur
coordinates for artisans in Bagru, Kutch and Rudraprayag — GPS fixes of the desk
the record was typed at, read as the subject's address. Read the model docstring
before touching this.

**Recommended approach: relate `DesignerProfile` to `Location`** rather than adding
loose columns. That is what "like the rest of the record pages" actually means, and
it brings `LocationFields`, the district picker, the map pin and the
coordinate/state mismatch flagging for free instead of reimplementing them.

**Traps** (from `.claude/skills/field-repo-frontend`, §12.5):

- **An edit form must NEVER auto-capture location.** `isEditForm = initial !== undefined`,
  and **omitting `initial` is the only thing that switches auto-capture on**. The
  designer profile is always an edit of your own record, so it must pass `initial`
  — otherwise it stamps whatever desk the designer is sitting at onto their profile,
  which is the exact bug the two-group split exists to end.
- MapTiler's Indian hierarchy: `region` is the STATE, `subregion` is the DISTRICT.
  `county` is the trap — it answers "Sanganer Tehsil" for Bagru.
- A blank geocoded pincode must be **written**, not skipped.
- A geocoded district may only be written where the geocoded state stands.
- District stands down from required when the list is empty (offline) —
  `stateRequired`/`districtRequired` both end in `&& options.length > 0`.

**Scope:** Prisma migration; `backend/app/api/routes/designers.py` profile
read/write; `frontend/app/(protected)/designers/profile/page.tsx` mounting
`LocationFields`; the Android designer profile screen; and the report builder if
the profile address is printed (check `report_templates.py` before asserting it is).

### 30. Filter and sort on the access list and designer list pages

Both `/admin/access` and `/admin/designers` are flat lists today. Add filtering
and sorting on **web and Android**:

- **date** — added / requested / decided / joined / first-seen, as a **range**
- **role(s)** — multi-select over the eight-tier ladder; for the access list this
  is `admitRole`, for the designer roster it is the linked account's role
- **status** — `AccessStatus` (ACTIVE/PENDING/REJECTED/SUSPENDED) and the designer
  roster's active/suspended
- **institution**, and free-text search over email/name
- sortable columns with a stable secondary sort

**Rules that already bind here and must not be broken:**

- **Empty means everything**, by absence — the convention `WorkshopScopeSelect` and
  `filters.types` already follow. "Nothing ticked" and "everything ticked" must not
  both exist and mean the same thing.
- **Suspended/rejected rows stay listed by default.** `/admin/designers`' own header
  says why: an admin arrives *because* somebody cannot log in, and the row refusing
  them is the one they need to see. A filter must not default them out of view.
- **Any cap or truncation must be stated on screen** (non-negotiable 10). If the
  filtered set is cut, say so and say by how much.
- Filter server-side, not in the browser, or a client-side box over a
  server-truncated page answers "No matches" about records that exist.
- `SearchInput` currently sets `role="searchbox"` with no label — give the new
  controls real labels.

### 31. How dropdowns behave on Android when offline

Open design question, to settle before building 30 and the unified workshop select
(reqs 9-13). The precedent already in the codebase is `OFFLINE_STATES` in
`LocationFields`: the state list came only from `GET /reference/address`, so offline
the list was empty, a **required** closed list had no members, native validation
refused the submit, and the interview plus its photographs died with the tab. The
fix was a bundled fallback list plus *a field may only be mandatory where it is
answerable* — the district stands down from required when its list is empty.

Decide, and write down, for each dropdown class:

1. **Bundled constant vocabularies** (status, role, gender, yes/no) — always
   available offline; no work.
2. **Reference data** (states, districts) — bundled fallback exists for states;
   districts cannot be bundled (795 of them) and stand down from required.
3. **Record-backed lists** (workshops, crafts, artisans) — these are the hard case.
   Options: cache the last successful fetch in Room and mark it stale with a date;
   allow free-text entry queued for reconciliation; or disable with a reason.
   **Whatever is chosen, the control must say which it is doing** — a silently
   empty picker reads as "there are none", which is the single most repeated bug
   class in this repo.
4. `SearchableSelect.kt` derives `searchable` from `options.size >= 8`, matching
   the web's `SEARCH_THRESHOLD`; an offline-shrunken list must not silently lose
   its filter box.

Cross-check against the offline outbox (`lib/offline.ts` and the Android twin):
a queued record referencing an id that never existed server-side is a different
failure from one whose dropdown was empty.

### 32. Housekeeping — remove the memory entry when this is done

`C:\Users\anujk\.claude\projects\F--Portal-Development-Designer\memory\repo-moved-to-c-drive.md`
and its pointer line in `MEMORY.md` exist only to carry the drive failure and the
new repo path across sessions. **Delete both once the recovery is finished and the
work here has landed** — a memory describing a resolved incident becomes misleading
context for every future session.

---

## 3. Order of work

1. Re-index CodeGraph on `C:\dev\designer-portal`.
2. **Auth Fix 1 — auto-empanelment, including the backfill** (req 28). Other
   allow-listed designers are hitting the refusal `sandycraft3@gmail.com` hit; the
   backfill is what clears them. Then Fix 2 (Gmail canonicalisation) as prevention.
3. Re-run the workshop-dropdown recon (reqs 9-13), the one missing area, and settle
   the offline-dropdown design (req 31) — reqs 9-13, 30 and 31 all depend on it.
4. Quick wins already pinpointed: req 8 (`max-w-prose`), the duplicated Processes
   group, the `deps.py:778` doc defect.
5. Designer profile address parity — req 29 (schema + web + Android).
6. Roster filtering and sorting — req 30 (web + Android).
7. Sketches: reqs 5, 6, 7, 18 (web), then Android parity.
8. Landing page + logos: reqs 1, 15, 16, 17.
9. Walkthrough: req 2 (web), then req 3/19 (Android) — the largest single piece.
10. Reqs 14, 27.
11. Research instrumentation: reqs 21-26, with the consent question settled first.
12. Housekeeping: req 32 — delete the `repo-moved-to-c-drive` memory and its
    `MEMORY.md` pointer once recovery is finished and this work has landed.

**Sequencing note.** Steps 2 and 3 are ordered that way deliberately: the auth
backfill unblocks real people today, while reqs 9-13, 30 and 31 are one cluster —
every one of them is about how a dropdown or a filter behaves, including with no
network, so designing them together avoids three incompatible answers to the same
question.

## 4. Verification

```
cd frontend  && npm run typecheck && npm run lint
cd backend   && ruff check app && pytest -q
cd android   && ./gradlew.bat :app:compileDebugKotlin
```

Android tests need JDK 17 — 19+ fails `DwWorkshopCodesTest:280` for a
`Double.toString` reason, not a code defect. Backend tests need Postgres on
`127.0.0.1:55442`.
