# Android offline audit — 2026-10-16

**What this is.** A screen-by-screen review of the Android app (`android/app/`) against one rule the
owner set on 2026-10-16:

> On every screen, anything a user enters or captures is kept safely on the device until there is
> internet, and then syncs — with nothing lost across app kills, reboots, sign-out/sign-in or app
> updates. Read-only screens show the last cached data offline instead of failing.

Every screen is recorded with what it writes, where pending data lives, whether it survives the
process dying, how and when it syncs, how conflicts are handled, what the user sees offline and
whether its reads are cached — and classified **OK**, **GAP** (the rule is not met) or **RISK** (met,
but with a way to lose work). The fixes made in the same change are listed under each row; what was
too large is in [OPEN_FINDINGS.md](OPEN_FINDINGS.md) under *Open*, opened 2026-10-16.

Citations are `file:line` into the tree **as it stood on 2026-10-16, after the fixes**. They drift; see
the last section.

---

## 1. The machinery every screen uses

| Piece | Where | What it guarantees |
|---|---|---|
| Records outbox | `android/app/src/main/java/com/designprototype/workshop/data/Offline.kt:1117` (`OfflineOutbox`) | One JSON queue in `filesDir/outbox/`, written atomically (temp, `fd.sync()`, rename), quarantined — never deleted — when damaged. Media is copied into `filesDir/outbox/media/` at queue time. Each entry is stamped with the account that captured it and is only ever sent as that account. |
| Outbox replay | `android/app/src/main/java/com/designprototype/workshop/data/WorkshopRepository.kt:7422` (`replayEntry`) | Create, then upload each file, persisting progress per file. A transient failure (`isTransient`, line 7715) stops the pass with nothing marked failed; an answered refusal is recorded on that entry and shown in the outbox tray. Creates of workshop/product/tool/process carry a client key so a lost answer does not duplicate the record. |
| Design-workshop drafts | `android/app/src/main/java/com/designprototype/workshop/data/WorkshopDraftStore.kt:666` (`WorkshopDraftStore`) | One directory per workshop under `filesDir/workshops/`, the stage screen saving on an 800 ms debounce (`persistLocally`, `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/StageScreen.kt:3304`). Synced by `WorkshopSyncEngine` with a payload signature per stage, so an edit that never landed is re-sent and nothing local is ever deleted by a sync. |
| **Background sync (new)** | `android/app/src/main/java/com/designprototype/workshop/data/BackgroundSync.kt:51` (`BackgroundSync`) | A persisted, network-constrained `JobScheduler` job — periodic every ~15 minutes, plus a one-shot requested by every outbox enqueue (`Offline.kt:1280`), every workshop-draft write (`WorkshopDraftStore.kt:991`) and every queued join card. It calls the same `syncOutbox` the activity calls, so everything above drains with the app closed and after a reboot. |
| **Form drafts (new)** | `android/app/src/main/java/com/designprototype/workshop/data/FormDraftStore.kt:58` (`FormDraftStore`), `android/app/src/main/java/com/designprototype/workshop/ui/FormDraftPersistence.kt:108` (`rememberFormDraft`) | What a form holds BEFORE Save, on disk per account, on a 600 ms debounce and on leaving the screen. Restored when the form is reopened, with a "Restored what you entered…" line and a Start over button. Picked files are copied into app storage so they are still readable after the process that picked them is gone. |
| **Read cache (new)** | `android/app/src/main/java/com/designprototype/workshop/data/ReadCache.kt:49` (`ReadCache`) | Live first; on a connectivity-class failure only (no signal, 408, 429, 5xx) the last copy this account read is shown, with the date it was read. An answered refusal (401/403/404/422) is never papered over. Deleted at sign-out (`WorkshopRepository.kt:3952`). |
| Device banner | `android/app/src/main/java/com/designprototype/workshop/data/OfflineBanner.kt:168` (`outboxDeviceBanner`) | Shown on every signed-in screen while anything is queued: "N saved on this device — will be sent when you have a signal", refusals, and another account's entries. |

### Triggers, before and after

| Trigger | Before 2026-10-16 | After |
|---|---|---|
| Sign-in, 12/45 s loop, connectivity callback | Yes — `MainActivity.kt:1812`, `MainActivity.kt:1850` | Unchanged |
| Join-card flush at process start and on network | Yes — `android/app/src/main/java/com/designprototype/workshop/ui/DwInductionFlusher.kt:127` (`watch`) | Unchanged |
| **App closed, phone rebooted** | **No** — every queue waited for the app to be reopened | `BackgroundSync` job (`BackgroundSync.kt:126`, `OutboxSyncJobService`) |
| Retry and backoff | 12 s while anything is queued, in-process only | Plus the platform's exponential backoff from 30 s when a background pass leaves work waiting on a working network (`backgroundSyncShouldRetry`, `BackgroundSync.kt:114`) |

**Why JobScheduler and not WorkManager.** The comments this app carried against a background
scheduler (`WorkshopSync.kt`, `DwInductionFlusher.kt`) objected to the dependency and its bundled
Room/SQLite in a build whose APK size is argued line by line. `JobScheduler` is the platform service
WorkManager uses at this minSdk (26): persisted across reboots (`RECEIVE_BOOT_COMPLETED`, a normal
permission), network-constrained, with platform backoff — at zero APK bytes. Two passes can no longer
race: the outbox and inspection-note mutexes are now process-wide
(`WorkshopRepository.kt:923`, `PROCESS_OUTBOX_SYNC_MUTEX`), and the workshop engine already had one.

---

## 2. The matrix

Legend for **Survives death**: *disk* = written to app storage before the user is told it is safe;
*memory* = lost if Android kills the process; *saved-state* = `rememberSaveable`, which survives a
background kill but not a swipe-away or force-stop.

### 2.1 Capture — the record forms and media

| Screen | Writes | Pending data lives | Survives death | Syncs | Conflicts | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|---|---|
| Craft form — `MainActivity.kt:10287` (`CraftForm`) | create/update craft + media | Before Save: **form draft** (was memory). After Save offline: outbox | disk (was memory) | outbox triggers + background job | correction replays whole record (see §4) | "saved on this device" sentence, banner | lookups via `DwReferenceStore` | **OK** (was GAP, fixed) |
| Artisan form — `MainActivity.kt:10425` (`ArtisanForm`) | create/update artisan + media | same | disk (was memory) | same | same; no client key on artisan creates by design | same | crafts register cached | **OK** (was GAP, fixed) |
| Workshop form — `MainActivity.kt:11225` (`WorkshopForm`) | create/update workshop | same | disk (was memory) | same | client key on create | same | artisans cached | **OK** (was GAP, fixed) |
| Product form — `MainActivity.kt:11465` (`ProductForm`) | create/update product + media, grid shots, measurement markers | same; markers ride the draft (`DwMeasurementMarkers.toDraft`) | disk (was memory) | same | client key on create | same | registers cached | **OK** (was GAP, fixed) |
| Tool form — `MainActivity.kt:11897` (`ToolForm`) | create/update tool + media + stage captures | same, both attachment sets | disk (was memory) | same | client key on create | same | registers cached | **OK** (was GAP, fixed) |
| Process form — `MainActivity.kt:12879` (`ProcessForm`) | create/update process, steps, per-step media | same, steps and their media included | disk (was memory) | same | client key on create | same | product/artisan registers cached | **OK** (was GAP, fixed) |
| Interview form — `MainActivity.kt:17870` (`QuestionnaireForm`) | interview + answers + clips + transcripts | same; answers, clips and the machine's transcript text (so a restored transcript is not taken as typed) | disk (was memory) | same | duplicate artisan-set offer on answer | same | sections cached | **OK** (was GAP, fixed) |
| Any record form, online save on a failing connection | — | the form (now disk) | disk | **not queued**: the user is told to try again | — | error message, typing kept | — | **RISK** (deferred, §4) |
| Miscellaneous media — `MainActivity.kt:16924` (`AndroidMediaForm`) | loose media upload | Before: picker in **memory**, offline upload just **failed**. Now: draft, and queued to the outbox when offline or when the signal goes mid-batch (`queueForLater`, `MainActivity.kt:17011`; `WorkshopRepository.kt:6991`, `queueLooseMedia`) with its design workshop and GPS fix | disk (was memory) | outbox + background job | media rows; a lost answer can duplicate a row | "saved on this device" sentence | recent-media list live only | **OK** (was GAP, fixed) |
| Stage screen — `StageScreen.kt:3304` (`persistLocally`) | stage values, rows, media | `WorkshopDraftStore`, 800 ms debounce, flushed on leave | disk | engine via `syncOutbox`; now also background | signature per stage, refusals per field, nothing local deleted | chip and device sync banner | the draft is the source | **OK** (was RISK — synced only while the app was open) |
| Photo intake — `PhotoIntakeScreen.kt:111` (`importMedia`) | workshop photographs | `WorkshopDraftStore.importMedia` into the workshop directory | disk | engine, now also background | as stage | as stage | draft | **OK** (was RISK, as above) |
| Workshop list / stage index — `WorkshopListScreen.kt:440` | offline-started workshops, consent | `WorkshopDraftStore` | disk | engine | create is write-back ordered | "kept on this phone" | local drafts listed offline | **OK** (was RISK, as above) |
| Report — `ReportScreen.kt:339` | generated .docx/.pdf, export log | export files under the workshop directory; the delivery note via `recordDesignWorkshopExport` (`WorkshopRepository.kt:1204`) to the outbox | disk | outbox | replay keeps the courtyard time | report built on the phone | draft | **OK** (was RISK, as above) |
| Designer profile — `DesignerProfileScreen.kt:832` (`profileDraft`); "Deliberately NOT offline-capable" at `DesignerProfileScreen.kt:134` | profile text, photo, signature, CV | Before: text in **memory**; media copied to `filesDir/designer-profile/media`. Now: text in a form draft | disk (was memory) | save is online only; draft waits | server answer re-seeds the form | load failure said in words | **not cached by design** (designer tier, `WorkshopRepository` "NOTHING IN THIS BLOCK FALLS BACK") | **RISK** (was GAP; draft fixed, send online-only — deferred, §4) |
| Camera and recorder captures (all record forms) — `MainActivity.kt:22420` (`createAppFile`) | photo/video/audio files | Before: `cacheDir/field-captures`, **reclaimable by Android without notice**. Now: `filesDir/field-captures`, swept after 14 days when no draft refers to them | disk (was purgeable) | with the form | — | — | — | **OK** (was RISK, fixed) |

### 2.2 Design workshop — the rest

| Screen | Writes | Pending data lives | Survives death | Syncs | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|---|
| Cards & tags / join — `WorkshopCodesScreen.kt:230`, `DwWorkshopJoin.kt:420` (`record`) | join requests | `dw-pending-inductions.json` | disk | flusher at start and on network, now also the background job; the queued sentence now says it is sent "even if the app has been closed" | queued sentence | stages from `WorkshopDraftStore.load` | **OK** (was RISK — sent only with the app open) |
| Inspection list / detail — `InspectionListScreen.kt:157` (`readInspectableWorkshops`) | — | — | — | — | the saved copy | `DwInspectionStore.saveList` (`DesignWorkshopInspectionFeedback.kt:441`) and `saveDetail` | **OK** |
| Inspection notes — `InspectionFeedbackPanel.kt:97` (`noteDraft`) | suggestions, send-backs | filed: `DwInspectionStore`; typed and not yet filed: was **saved-state** only, now also a form draft | disk (was saved-state) | `syncInspectionNotes` (`WorkshopRepository.kt:2349`) from `syncOutbox`, now also in the background | queued list on the panel | detail cached | **OK** (was RISK — the typing survived a background kill but not a swipe-away; fixed) |
| Design review — `DesignReviewScreen.kt:589` (`designRatingRound`), `DesignReviewScreen.kt:1603` (`designRatingLedger`) | ratings; the held order | ratings: outbox (`submitDesignRating`, `WorkshopRepository.kt:2610`); order: the stage draft | disk | outbox / engine | "Saved on this phone" | held rows from the draft; **the ranking and the ledger are not cached** | **GAP** (read side — deferred, §4) |
| Report history — `DwReportHistoryScreen.kt:211` | — | — | — | — | Before: an error. Now: the last copy with its date | **ReadCache (new)** | **OK** (was GAP, fixed) |
| Provenance — `DwProvenanceScreen.kt:200` | — | — | — | — | Before: an error. Now: the last copy with its date | **ReadCache (new)** | **OK** (was GAP, fixed) |
| Viewers, inspectors — `WorkshopViewersScreen.kt:388` (`setDesignWorkshopViewers`), `WorkshopInspectorsScreen.kt:341` (`setDesignWorkshopInspectors`) | access grants | online only | — | — | the save error in words | no | **OK by decision** (§3) |
| Sketches & prototypes — `SketchesAndPrototypesScreen.kt:267` (`DW_SKETCH_CHOOSER_OFFLINE`) | sketch rows, through the stage draft | `WorkshopDraftStore` | disk | engine | the chooser says it is offline | review tab from the draft; **the workshop chooser is not cached** | **GAP** (read side — deferred, §4) |
| Designer roster, access roster — `DesignerRosterScreen.kt:628` (`suspendDesigner`), `AccessRosterScreen.kt:679` (`approveAccessRequest`) | who may sign in, empanelment | online only | — | — | "could not reach" | **not cached, by the repository's own rule** | **OK by decision** (§3) |

### 2.3 Questionnaires

| Screen | Writes | Pending data lives | Survives death | Syncs | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|---|
| Questionnaire list — `QuestionnaireListScreen.kt:168` | a new form (the row only) via `createCustomQuestionnaireOrQueue` (`WorkshopRepository.kt:3475`) | outbox | disk | outbox | Before: "could not connect" and **no rows**. Now: the last unfiltered list with its date | **ReadCache (new)**; a search is never answered from the device | **OK** (was GAP, fixed) |
| Questionnaire detail — `QuestionnaireDetailScreen.kt:557` (`addCustomQuestion`) | sections, questions, edits, re-uploads | online only | memory (a prompt being typed) | — | refusal in words | form cached (`customQuestionnaireCached`, `WorkshopRepository.kt:3053`) | **RISK** — authoring offline needs server ids threaded between queued creates (deferred, §4) |
| Answer a questionnaire — `QuestionnaireAnswerScreen.kt:208` | answers + notes | Before: **memory**, while the screen said "your typing is still here — save when you have a signal" (`QuestionnaireAnswerScreen.kt:262`). Now: a form draft | disk (was memory) | **sent only when the person presses Save on the live form** — by decision, §3 | cached-copy notice | form cached | **OK** (was GAP, fixed — the promise on screen is now true) |
| Consolidated questionnaire — `ConsolidatedQuestionnaireScreen.kt:275` | — | — | — | — | Before: an error. Now: the last copy with its date | **ReadCache (new)** for the artisan list and each document | **OK** (was GAP, fixed) |

### 2.4 Everything else

| Screen | Writes | Pending data lives | Survives death | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|
| My tasks — `MainActivity.kt:21219` | status changes, progress | online only (§3) | — | Before: "couldn't be loaded". Now: the last list with its date | **ReadCache (new)** | **OK** (was GAP, fixed) |
| My activity — `MainActivity.kt:13714` (`MyActivityScreen`) | — | — | — | "This list needs a connection" | no | **GAP** (deferred, §4) |
| App feedback rating — `MainActivity.kt:14791` (`FeedbackScreen`) | `PUT /feedback/me` | Before: memory, and an offline Save **failed**. Now: a draft, and queued to the outbox as `appFeedback` (`WorkshopRepository.kt:235`, `OFFLINE_APP_FEEDBACK`) — an upsert, so a replay never duplicates | disk (was memory) | "kept on this device" | stored rating live | **OK** (was GAP, fixed) |
| Report something — `MainActivity.kt:14579` (`FeedbackReportSection`) | bug/suggestion report | Before: memory. Now: a draft | disk (was memory) | the send fails in words; the report waits in the draft | vocabulary live | **RISK** (draft fixed; send online-only — deferred, §4) |
| Update existing / record editor — `MainActivity.kt:9641` (`RecordPickerScreen`), `MainActivity.kt:10037` (`EditScreen`), `MainActivity.kt:10215` (`InterviewEditLoader`) | corrections | outbox as a correction once the record is open, and now a draft per record id | disk | **the picker and the record are read live, so a record cannot be opened offline** | no | **GAP** (deferred, §4 — tied to the open MEDIUM finding on corrections overwriting a colleague's edit) |
| View data, data browser — `MainActivity.kt:15451` (`ViewDataScreen`), `DataBrowserScreen.kt:422` | admin review actions | online only | — | load errors | no | **GAP** (deferred, §4) |
| Map — `MapScreen.kt:501` | — | — | — | Before: "the map could not be loaded". Now: the last map for the same scope and level, with its date; the outline and borders are bundled | **ReadCache (new)** | **OK** (was GAP, fixed) |
| Search, scan a code — `SearchScreen.kt:554`, `RecordCodeLookup.kt:159` (`lookUpRecordCode`) | — (a join card scanned here is queued, as above) | — | — | "no connection" in words | no — a query, not a list (§3) | **OK by decision** |
| Sharing, workshop access, users, assign tools, task board — `MainActivity.kt:20281` (`SharingForm`), `MainActivity.kt:20751` (`WorkshopAccessScreen`), `MainActivity.kt:19959` (`UserManagementForm`), `MainActivity.kt:13807` (`ToolAssignScreen`), `TaskAdminScreen.kt:679` (`createTaskBatch`) | permission grants, user administration, assignments | online only | — | errors in words; failed people stay ticked | no | **OK by decision** (§3) |
| Appearance — `AppearanceScreen.kt:271` (`savePreferences`) | display preferences | applied and kept on the device first; the server copy is sent once | disk | an error line | device copy | **RISK** — a server copy that failed to send is not retried, so another device does not get it (deferred, §4) |
| My AI keys, API keys, usage recording, consent — `MyAiKeysScreen.kt:163` (`setMyAiKey`), `ApiKeysScreen.kt:349` (`setSecret`), `UsageRecordingScreen.kt:289` (`recordConsent`) | secrets, consent | online only | — | errors in words | no | **OK by decision** — a secret is never written to disk to wait for a signal, and consent is recorded by the server or not at all (§3) |
| Speech & AI — `SpeechAndAiScreen.kt:228` | — (device-local) | device | disk | — | device | **OK** |
| Sign-in, password gate, consent gate, terms, walkthrough — `MainActivity.kt:2262` (`LoginScreen`), `PasswordGate.kt:147` (`PasswordGateScreen`), `UsageConsentGate.kt:302` | credentials, consent | not persisted, by design (a password is never written to disk) | — | sign-in needs a connection | the published notice is kept | **OK by decision** |
| Dashboard, settings hub, review queue — `MainActivity.kt:5578` (`DashboardScreen`), `MainActivity.kt:15352` (`AdminHubScreen`), `InspectionReviewQueue.kt:57` (`InspectionAwaitingSection`), `MainActivity.kt:15800` (`ReviewApprovalCard`) | record approvals (admin) | online only | — | errors in words | the inspector queue is cached | **OK** |

### Count

42 rows in §2. **After** the fixes: **32 OK** (6 of them *OK by decision*), **5 GAP**, **5 RISK**.
**Before** the fixes the same rows read **9 OK, 23 GAP, 10 RISK**. Eighteen GAPs were closed and seven
RISKs: every GAP closed was work lost to a process kill before Save, an offline save that failed
instead of queueing, or a read-only screen that showed an error where it had a list to show; the
RISKs closed were queues that waited for the app to be reopened, camera captures in a cache Android
empties on its own, and an inspector's note kept only in saved state. Two GAPs became RISKs (the
designer profile and "Report something": the typing is now kept, the send is still online-only).

---

## 3. Decisions made in this review (the owner delegated them)

1. **Background sync is a platform `JobScheduler` job, not WorkManager.** Same guarantees, no new
   dependency — the objection on record was the dependency, not the behaviour.
2. **Administrative writes stay online-only** — sharing, roles, workshop grants, viewer and inspector
   appointments, the access roster, task assignment, record approvals. Each is a statement about who
   may do what; replayed hours later against a server whose state moved, a queued grant or revoke is a
   security change nobody is watching. These screens say in words that they need a connection, and
   what was typed stays on screen. **Secrets and consent too:** an API key typed with no signal is not
   written to disk to wait, and usage consent is recorded by the server or not at all.
3. **A custom-questionnaire answer is still sent only by a person pressing Save on the live form.**
   The existing rule (an answer must not be recorded against wording the phone cannot confirm is still
   in use) stands; the typing is now on disk, so the screen's promise — "your typing is still here,
   save when you have a signal" — is true across an app kill.
4. **Cached lists are per account and deleted at sign-out; drafts and the outbox are not.** A cache
   is a copy of the server and costs nothing to lose; a draft or a queued entry is somebody's work.
   Drafts are filed under a hash of the account id, so the next person on a shared phone is never
   offered them and the owner gets them back on signing in again.
5. **A search is never answered from the device.** An old answer to a query reads as current.
6. **Camera captures move from `cacheDir` to `filesDir`.** Android reclaims the cache without notice;
   a photograph in an unsaved form has to outlive a storage squeeze. Unreferenced captures are reclaimed
   after 14 days.
7. **A record is not opened for correction from a cached copy.** It would be cheap to cache the
   picker and the record, but a queued correction replays the whole record with no version check (the
   open MEDIUM finding of 2026-10-10), so editing a stale copy offline would revert a colleague's
   later edits to fields the designer never touched. That finding has to close first.

---

## 4. Deferred — recorded in OPEN_FINDINGS.md

Each is an entry under *Open*, opened 2026-10-16, with the files and what closing it takes:

- **GAP** — A record cannot be opened for correction offline (decision 7).
- **GAP** — Five read-only lists still fail offline: my activity, view data, the data browser, design
  review's ranking and ledger, and the sketches workshop chooser.
- **RISK** — A record form whose online save fails mid-request is not queued: it cannot tell a request
  that never left from one whose answer was lost, and artisan and craft creates carry no client key.
- **RISK** — Questionnaire authoring (sections, questions) is online-only.
- **RISK** — The designer profile and "Report something" keep a draft but send only online.
- **RISK** — An appearance change made offline is applied on the device but its server copy is not
  retried, and the next sign-in's reconcile prefers the server's older row.

---

## 5. Tests

`android/app/src/test/java/com/designprototype/workshop/data/FormDraftStoreTest.kt`,
`android/app/src/test/java/com/designprototype/workshop/data/ReadCacheTest.kt` and
`android/app/src/test/java/com/designprototype/workshop/data/OfflineEverywhereTest.kt` pin the new
persistence and sync logic: a draft reads back whole, only for its owner, survives a damaged file and
a newer build's file; nothing typed keeps no draft; late-arriving questions do not erase restored
answers; captures are swept only when old and unreferenced; the read cache serves a copy only when
nothing reached the server; a background pass retries only work a connection could move; queued app
feedback and loose media round-trip with their workshop and GPS fix while a record form's queued file
sends the same body as before; and an accepted measurement keeps its marker across a restored draft.

---

## How this document is kept true

It is a **dated snapshot**, like the audits beside it in the index: the matrix describes the tree on
2026-10-16 and is not updated as screens change. The line citations are checked by
`docs/tools/check-docs.mjs` for bounds and drift on every run; when one drifts, re-cite it to the
symbol named beside it rather than editing the classification. A new screen that writes anything
should reuse `rememberFormDraft` and the outbox (or record why it is online-only in §3), and a new
read-only list should go through `ReadCache` — at which point it belongs in the next audit, not in
this one. The deferred items live in [OPEN_FINDINGS.md](OPEN_FINDINGS.md), which is the register to
update when one is closed.
