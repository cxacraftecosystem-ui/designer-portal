# Open findings

**Status: 0 open, 29 closed.** Last re-checked against the tree on 2026-08-08.

This file used to hold 29 defects. Every one of them was re-read against the working tree on
2026-08-08: twenty-eight had already been fixed, and the twenty-ninth was closed by the pass that
produced this rewrite. **Nothing in this register is outstanding.** The tables below are kept so the
next reader can re-check the closures rather than take this file's word for them, and so a defect
class that has already cost this repository once is not re-litigated from scratch.

**Read this before adding to it.** This register was cited from running source
(`frontend/lib/designWorkshopStore.ts` pointed a maintainer here for a residue that had been closed
the same day), so it is not inert documentation — somebody follows the pointer. A findings document
that has drifted from the code is worse than no findings document, because it sends the next reader
hunting for bugs that are gone and teaches them that the register is noise. **If you close something
here, mark it closed in the same commit that closes it.**

---

## Open

**None.** Add one here with the same shape the closed entries use — consequence, evidence read
verbatim with `file:line`, and the fix — and delete this line when you do.

---

## Closed on 2026-08-08 — the last surviving item

### [MEDIUM] ENHANCEMENT — The field copy never says it is the abridged one, and when the designer is offline the office's export log does not say so either (android)

**Both halves are now closed.**

- *The provenance line* was closed earlier: it is built at
  `android/…/report/ReportSettings.kt` (`fieldCopyNote`), reaches the cover from
  `ui/designworkshop/ReportScreen.kt`, and is covered by
  `android/app/src/test/…/ReportSettingsLedgerTest.kt`. Commit `5886fd9`.
- *The export log* was the last open item in this file and is closed by `cfec845`.
  `WorkshopRepository.recordDesignWorkshopExport` was a bare pass-through to the API wrapped
  in a `runCatching` at the call site, so an export made with no signal — which is the ordinary case,
  because the exports that matter most are made in a village at the close of a workshop, minutes
  before the file is handed to a visiting ministry officer — was recorded nowhere, and the officer's
  copy existed against an empty log. It now enqueues on the offline outbox (`PendingEntry` of type
  `designWorkshopExport`, replayed by `createFromEntry`) using the same `isTransient` triage as every
  other queued write. It still records the fact and never the bytes: a designer on a metered field
  connection is not charged thirty megabytes to prove a report was made, and the checksum is what
  matches the file later.

---

## Closed earlier — the twenty-eight

Grouped as the original register grouped them. Each line names the evidence that closes it, so a
reader can re-check in one grep rather than taking this file's word for it.

### group-a — the stage save path

| Finding | Closed by |
|---|---|
| [CRITICAL] A stage saved from a client that never downloaded it REPLACES the singleton row, deleting fields that client never read | `6119378` — `merge` is a per-entry flag on `StageSaveIn` (`backend/app/schemas/design_workshops.py`) honoured at `services/design_workshops.py` (`if entry.merge and previous:`), driven from `serverLoadedAt === null` on web and `!isAuthoritative(...)` on Android |
| [HIGH] A row deleted while a background sync PUT is in flight loses its deletion flag | `50f1ab9` — `removedFrom` is now computed the same way `dirtyAt` is, in both `markStagePushed` and the push transform |
| [MEDIUM] "Save and sync this stage now" starts an 800 ms timer instead of saving | `175ef63` — the button calls `persistLocally` directly and a dispose-time write lands the snapshot |
| [MEDIUM] A stage the server answers with 5xx is reported as "the connection dropped" | `175ef63` — an answered 5xx is a per-stage failure with the stage named, and no longer sets `stoppedOffline` |
| [MEDIUM] `putDraftStage` resolves confirmed media refs OUTSIDE the transaction its comment claims | `50f1ab9` — the `dwlocal:` → server-id map is read in the same readwrite transaction that puts the draft |
| [MEDIUM] A stage refused for a SCHEMA mismatch is blamed on the designer's answers | `9f7486f` — `isSchemaRefusal` (`frontend/lib/offline.ts`, matching pydantic's `extra_forbidden`) splits the sentence; an extra-input refusal now says the app and the repository are out of step and that no edit will clear it |

### group-b — entitlement and media

| Finding | Closed by |
|---|---|
| [HIGH] SECURITY — report generation and the transcript annexure fetch ANY MediaFile by client-supplied id | `0d4da23` — both `mediafile.find_many` calls are AND-composed with `owned_or_granted_where(viewer, owner_field="uploadedById")`, threaded through as a keyword with no default so a call site cannot silently skip it |
| [MEDIUM] SECURITY — `MediaFile.url` is taken verbatim from the upload payload | `92e4ae0` / `0d4da23` lane — the field is kept (removing it would 422 every installed build) and ignored: the stored URL is always derived from the object key |
| [MEDIUM] PERFORMANCE — one extra query per newly-attached audio clip on every stage save | `0d4da23` lane — one `mediaprocessingjob.find_many` over the candidate ids replaces the per-clip `find_first` (`backend/app/services/workshop_transcripts.py`) |

### group-c — what the phone's report contains

| Finding | Closed by |
|---|---|
| [HIGH] The infographic renderer ships in the APK and no chart block is ever constructed | `5886fd9` — `SpecialSection.CHART -> renderCharts(...)` in `ReportScreen.kt` |
| [MEDIUM] The completeness annexure is a 28-line table over scores already computed | `5886fd9` — `SpecialSection.COMPLETENESS` is built |
| [MEDIUM] The map block is never constructed | `5886fd9` — `SpecialSection.MAP -> renderMap(...)`, region-only, matching the server's empty-point case |
| [MEDIUM] The field copy never says it is the abridged one | `5886fd9` (provenance line) + this rewrite's commit (export log) — see above |
| [LOW] The export-retention subsystem is fully implemented and has no call site | Removed rather than wired, with the reasoning written into `report/ReportExport.kt`: retaining a second full copy of every report inside `filesDir` on a space-constrained handset, with no retention rule, is not what the capability was worth |
| [LOW] Correct the report-settings ledger — it nominates the transcript annexure on a false premise | `ccc3acd` — the ledger no longer claims the handset holds transcripts, and the questionnaire annexure is carried in the catalogue and declared unbuildable on the phone |

### group-d — export cost on the handset

| Finding | Closed by |
|---|---|
| [HIGH] The .docx writer holds every photograph's full original bytes on the heap at once | `5886fd9` — `MediaPart` holds an `ImageRef`, not a `ByteArray`; bytes are re-asked for and streamed into the zip entry |
| [LOW] The PDF export reads every photograph's whole file three times | `5886fd9` — same lane; the size cache is no longer cleared wholesale |

### group-e — report inputs and the backend read path

| Finding | Closed by |
|---|---|
| [HIGH] The report input load is up to ten sequential round trips | `0d4da23` — `_report_inputs` uses `gather_reads` (`backend/app/api/routes/design_workshops.py`) |
| [MEDIUM] The one-photo-per-record lookup caps its read at 4xN rows GLOBALLY | `0d4da23` lane — `_reference_photos` asks per parent instead of spending one global budget oldest-first |
| [MEDIUM] Report preview and generate scan the whole Location table uncapped on all six templates | `0d4da23` lane — the anchor load is skipped for a template carrying no map, and capped when it runs |
| [LOW] The design-workshop module docstring states a permission rule the module does not implement | Rewritten in place — `backend/app/api/routes/design_workshops.py` now describes the four clauses it enforces, including that `can_run_design_workshops` is a SET and a Professor is outside it |

### group-f — grants, storage and the analytics rationale

| Finding | Closed by |
|---|---|
| [MEDIUM] Data-access grants rebuild their scope with delete-then-N-inserts outside any transaction | `0d4da23` — `_upsert_grant` runs inside `async with db.tx()` with a single `create_many` |
| [MEDIUM] PERFORMANCE — a fresh boto3 S3 client is constructed for every single object | `0d4da23` lane — `_client()` is `@lru_cache`d in `backend/app/services/s3.py` |
| [LOW] The analytics module's performance rationale contradicts an index that shipped in a migration | Rewritten in place — `backend/app/api/routes/analytics.py` now says the index exists and points at `schema.prisma` as the single source of truth |

### group-g — web accessibility

| Finding | Closed by |
|---|---|
| [HIGH] The two hand-rolled modal overlays have no dialog role, focus trap, Escape, or backdrop guard | `a8c0900` — `CollabDialog` is built on `FieldDialog`; the Assign researchers overlay went the same way |
| [MEDIUM] The dialog system's header guarantees both reduced-motion switches; it read only the OS one | `a8c0900` — `FieldDialog` and `AppShell` both use `useAppReducedMotion()` |
| [MEDIUM] The review decision-note textarea has no accessible name | `a8c0900` — `useId()` pairs `htmlFor`/`id`, with the explanatory paragraph wired to `aria-describedby` |

### group-h / group-i — dead weight and navigability

| Finding | Closed by |
|---|---|
| [MEDIUM] `refOptions` — up to five requests per stage, built, threaded through three components, never read | `a8c0900` — deleted, with a note in `FieldInput.tsx` telling the next reader not to add it back |
| [MEDIUM] Android lists what is missing as inert text; the web turns the same list into links | `175ef63` — the missing-field list is navigable on the handset |

---

## What this pass added

The 2026-08-08 re-check also turned up nine defects that were **not** in this register. All nine are
fixed; they are recorded here so the next reader knows the sweep happened rather than re-finding
them.

- **My Activity under-reported on Android, and the sharing screen's record picker with it.** Both
  fetched page one of every list and sifted it client-side on `createdById`. Reading the repository
  is open, so page one is the newest hundred rows of the whole archive. MEASURED against the running
  API as `designer@example.org`: `/api/artisans` total=431 with page one spanning 34 distinct
  creators and none of that designer's own; `/api/media` total=854 across 18 uploaders, likewise
  none. That designer owns two records and both screens showed zero, with My Activity saying "You
  haven't recorded anything yet." Fixed by passing `createdBy` (`uploadedBy` for media) — every
  endpoint already accepted it, so there was no server work.
- **My Activity's record types disagreed between the clients.** Android had Processes and no Media;
  the web had Media and no Processes, while both ship a "Document process" and an "Upload media"
  menu entry. Android gained Media, the web gained Processes.
- **Android's dataset download handed over a truncated archive with no warning.** The server has
  always sent `truncated`; `DatasetManifestDto` dropped it. It is not derivable from the counts — a
  capped manifest is internally consistent, so "4,312/4,312 files" is true of an archive missing
  everything past the cap.
- **Tasks said "Nothing is assigned to you right now." when the request had failed**, on a handset
  that usually has no signal, with the offline banner underneath saying the opposite. The screen now
  distinguishes "loaded and empty" from "could not load", and says so when a list on screen is the
  one last fetched.
- **Tasks had no CANCELLED chip and no counts**, while the API, the DTO and `taskStatusLabel` all
  knew about CANCELLED — so a cancelled task assigned to you could be seen under "All" and never
  filtered for.
- **`designWorkshopStore.ts` cited this file** for a residue closed the same day, and quoted a
  refusal banner the code no longer produces.

**Not a divergence, checked and left alone:** Android's "Assigned by me" view has no web equivalent
and is gated on `isAdmin && adminChrome`, which correctly mirrors `backend/app/api/routes/tasks.py`
(`view=created` raises 403 unless the caller is an admin) and `frontend/lib/permissions.ts`
(`canAssignTasks = hasRank(user, "ADMIN")`). No permission was invented on either client.
