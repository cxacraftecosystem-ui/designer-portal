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
too large for the first change was recorded in [OPEN_FINDINGS.md](OPEN_FINDINGS.md) and then built by a
second change the same day (backend idempotency keys, corrections under a version precondition,
offline questionnaire authoring and the rest); those entries are now under *Closed on 2026-10-16*.

Citations are `file:line` into the tree **as it stood on 2026-10-16, after the fixes**. They drift; see
the last section.

---

## 1. The machinery every screen uses

| Piece | Where | What it guarantees |
|---|---|---|
| Records outbox | `android/app/src/main/java/com/designprototype/workshop/data/Offline.kt:1177` (`OfflineOutbox`) | One JSON queue in `filesDir/outbox/`, written atomically (temp, `fd.sync()`, rename), quarantined — never deleted — when damaged. Media is copied into `filesDir/outbox/media/` at queue time. Each entry is stamped with the account that captured it and is only ever sent as that account. |
| Outbox replay | `android/app/src/main/java/com/designprototype/workshop/data/WorkshopRepository.kt:7651` (`replayEntry`) | Create, then upload each file, persisting progress per file. A transient failure (`isTransient`, line 7715) stops the pass with nothing marked failed; an answered refusal is recorded on that entry and shown in the outbox tray. Creates of workshop/product/tool/process carry a client key so a lost answer does not duplicate the record. |
| Design-workshop drafts | `android/app/src/main/java/com/designprototype/workshop/data/WorkshopDraftStore.kt:666` (`WorkshopDraftStore`) | One directory per workshop under `filesDir/workshops/`, the stage screen saving on an 800 ms debounce (`persistLocally`, `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/StageScreen.kt:3363`). Synced by `WorkshopSyncEngine` with a payload signature per stage, so an edit that never landed is re-sent and nothing local is ever deleted by a sync. |
| **Background sync (new)** | `android/app/src/main/java/com/designprototype/workshop/data/BackgroundSync.kt:51` (`BackgroundSync`) | A persisted, network-constrained `JobScheduler` job — periodic every ~15 minutes, plus a one-shot requested by every outbox enqueue (`Offline.kt:1290`), every workshop-draft write (`WorkshopDraftStore.kt:991`) and every queued join card. It calls the same `syncOutbox` the activity calls, so everything above drains with the app closed and after a reboot. |
| **Form drafts (new)** | `android/app/src/main/java/com/designprototype/workshop/data/FormDraftStore.kt:58` (`FormDraftStore`), `android/app/src/main/java/com/designprototype/workshop/ui/FormDraftPersistence.kt:116` (`rememberFormDraft`) | What a form holds BEFORE Save, on disk per account, on a 600 ms debounce and on leaving the screen. Restored when the form is reopened, with a "Restored what you entered…" line and a Start over button. Picked files are copied into app storage so they are still readable after the process that picked them is gone. |
| **Read cache (new)** | `android/app/src/main/java/com/designprototype/workshop/data/ReadCache.kt:49` (`ReadCache`) | Live first; on a connectivity-class failure only (no signal, 408, 429, 5xx) the last copy this account read is shown, with the date it was read. An answered refusal (401/403/404/422) is never papered over. Deleted at sign-out (`WorkshopRepository.kt:4303`). |
| **Idempotent creates (second change)** | `backend/app/core/idempotency.py`, `android/app/src/main/java/com/designprototype/workshop/data/Offline.kt` (`outboxCarriesIdempotencyKey`) | Every create — online or from the outbox — sends an `Idempotency-Key`; the server keeps the first answer 30 days per account and returns it for a repeat. An online create whose answer is lost moves into the outbox under the same key, so it is written once however often it is sent. |
| **Corrections under a precondition (second change)** | `android/app/src/main/java/com/designprototype/workshop/data/Offline.kt` (`restrictCorrectionBody`) | A queued correction sends only its changed fields and the record's `updatedAt`; a record that moved is refused (409 `record_changed`) and held for "Review changes" in the tray. |
| **Temporary ids (second change)** | `android/app/src/main/java/com/designprototype/workshop/data/QuestionnaireAuthoring.kt` (`TempIdMap`) | Questionnaires, sections and questions made offline get client ids; queued children reference them, and the server ids are resolved at replay, in order. |
| Device banner | `android/app/src/main/java/com/designprototype/workshop/data/OfflineBanner.kt:168` (`outboxDeviceBanner`) | Shown on every signed-in screen while anything is queued: "N saved on this device — will be sent when you have a signal", refusals, and another account's entries. |

### Triggers, before and after

| Trigger | Before 2026-10-16 | After |
|---|---|---|
| Sign-in, 12/45 s loop, connectivity callback | Yes — `MainActivity.kt:1812`, `MainActivity.kt:1850` | Unchanged |
| Join-card flush at process start and on network | Yes — `android/app/src/main/java/com/designprototype/workshop/ui/DwInductionFlusher.kt:128` (`watch`) | Unchanged |
| **App closed, phone rebooted** | **No** — every queue waited for the app to be reopened | `BackgroundSync` job (`BackgroundSync.kt:124`, `OutboxSyncJobService`) |
| Retry and backoff | 12 s while anything is queued, in-process only | Plus the platform's exponential backoff from 30 s when a background pass leaves work waiting on a working network (`backgroundSyncShouldRetry`, `BackgroundSync.kt:137`) |

**Why JobScheduler and not WorkManager.** The comments this app carried against a background
scheduler (`WorkshopSync.kt`, `DwInductionFlusher.kt`) objected to the dependency and its bundled
Room/SQLite in a build whose APK size is argued line by line. `JobScheduler` is the platform service
WorkManager uses at this minSdk (26): persisted across reboots (`RECEIVE_BOOT_COMPLETED`, a normal
permission), network-constrained, with platform backoff — at zero APK bytes. Two passes can no longer
race: the outbox and inspection-note mutexes are now process-wide
(`WorkshopRepository.kt:941`, `PROCESS_OUTBOX_SYNC_MUTEX`), and the workshop engine already had one.

---

## 2. The matrix

Legend for **Survives death**: *disk* = written to app storage before the user is told it is safe;
*memory* = lost if Android kills the process; *saved-state* = `rememberSaveable`, which survives a
background kill but not a swipe-away or force-stop.

### 2.1 Capture — the record forms and media

| Screen | Writes | Pending data lives | Survives death | Syncs | Conflicts | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|---|---|
| Craft form — `MainActivity.kt:10331` (`CraftForm`) | create/update craft + media | Before Save: **form draft** (was memory). After Save offline: outbox | disk (was memory) | outbox triggers + background job | correction replays whole record (see §4) | "saved on this device" sentence, banner | lookups via `DwReferenceStore` | **OK** (was GAP, fixed) |
| Artisan form — `MainActivity.kt:10493` (`ArtisanForm`) | create/update artisan + media | same | disk (was memory) | same | same; no client key on artisan creates by design | same | crafts register cached | **OK** (was GAP, fixed) |
| Workshop form — `MainActivity.kt:11311` (`WorkshopForm`) | create/update workshop | same | disk (was memory) | same | client key on create | same | artisans cached | **OK** (was GAP, fixed) |
| Product form — `MainActivity.kt:11570` (`ProductForm`) | create/update product + media, grid shots, measurement markers | same; markers ride the draft (`DwMeasurementMarkers.toDraft`) | disk (was memory) | same | client key on create | same | registers cached | **OK** (was GAP, fixed) |
| Tool form — `MainActivity.kt:12021` (`ToolForm`) | create/update tool + media + stage captures | same, both attachment sets | disk (was memory) | same | client key on create | same | registers cached | **OK** (was GAP, fixed) |
| Process form — `MainActivity.kt:13019` (`ProcessForm`) | create/update process, steps, per-step media | same, steps and their media included | disk (was memory) | same | client key on create | same | product/artisan registers cached | **OK** (was GAP, fixed) |
| Interview form — `MainActivity.kt:18117` (`QuestionnaireForm`) | interview + answers + clips + transcripts | same; answers, clips and the machine's transcript text (so a restored transcript is not taken as typed) | disk (was memory) | same | duplicate artisan-set offer on answer | same | sections cached | **OK** (was GAP, fixed) |
| Any record form, online save on a failing connection — `MainActivity.kt:10372` (`createKey`) | the create or correction | the form (disk); then the outbox under the SAME `Idempotency-Key` the online attempt sent | disk | outbox + background job | the server answers a repeat of the key from the first record (backend `app/core/idempotency.py`), so a create whose answer was lost is not written twice | "saved on this device" | — | **OK** (was RISK, fixed in the second change) |
| Miscellaneous media — `MainActivity.kt:17171` (`AndroidMediaForm`) | loose media upload | Before: picker in **memory**, offline upload just **failed**. Now: draft, and queued to the outbox when offline or when the signal goes mid-batch (`queueForLater`, `MainActivity.kt:17171`; `WorkshopRepository.kt:7525`, `queueLooseMedia`) with its design workshop and GPS fix | disk (was memory) | outbox + background job | media rows; a lost answer can duplicate a row | "saved on this device" sentence | recent-media list live only | **OK** (was GAP, fixed) |
| Stage screen — `StageScreen.kt:3363` (`persistLocally`) | stage values, rows, media | `WorkshopDraftStore`, 800 ms debounce, flushed on leave | disk | engine via `syncOutbox`; now also background | signature per stage, refusals per field, nothing local deleted | chip and device sync banner | the draft is the source | **OK** (was RISK — synced only while the app was open) |
| Photo intake — `PhotoIntakeScreen.kt:111` (`importMedia`) | workshop photographs | `WorkshopDraftStore.importMedia` into the workshop directory | disk | engine, now also background | as stage | as stage | draft | **OK** (was RISK, as above) |
| Workshop list / stage index — `WorkshopListScreen.kt:440` | offline-started workshops, consent | `WorkshopDraftStore` | disk | engine | create is write-back ordered | "kept on this phone" | local drafts listed offline | **OK** (was RISK, as above) |
| Report — `ReportScreen.kt:501` | generated .docx/.pdf, export log | export files under the workshop directory; the delivery note via `recordDesignWorkshopExport` (`WorkshopRepository.kt:1226`) to the outbox | disk | outbox | replay keeps the courtyard time | report built on the phone | draft | **OK** (was RISK, as above) |
| Designer profile — `DesignerProfileScreen.kt:832` (`profileDraft`), `WorkshopRepository.kt:2974` (`saveDesignerProfileOrQueue`) | profile text, photo, signature, CV | text in a form draft; on Save with no signal (or a dropped request) the same whole-object PUT is queued as `designerProfile` | disk | outbox + background job | the route replaces the one row; the person's own profile, so last write wins | "kept on this phone" | **not cached by the repository's own rule** | **OK** (was GAP) |
| Camera and recorder captures (all record forms) — `MainActivity.kt:22704` (`createAppFile`) | photo/video/audio files | Before: `cacheDir/field-captures`, **reclaimable by Android without notice**. Now: `filesDir/field-captures`, swept after 14 days when no draft refers to them | disk (was purgeable) | with the form | — | — | — | **OK** (was RISK, fixed) |

### 2.2 Design workshop — the rest

| Screen | Writes | Pending data lives | Survives death | Syncs | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|---|
| Cards & tags / join — `WorkshopCodesScreen.kt:230`, `DwWorkshopJoin.kt:420` (`record`) | join requests | `dw-pending-inductions.json` | disk | flusher at start and on network, now also the background job; the queued sentence now says it is sent "even if the app has been closed" | queued sentence | stages from `WorkshopDraftStore.load` | **OK** (was RISK — sent only with the app open) |
| Inspection list / detail — `InspectionListScreen.kt:157` (`readInspectableWorkshops`) | — | — | — | — | the saved copy | `DwInspectionStore.saveList` (`DesignWorkshopInspectionFeedback.kt:441`) and `saveDetail` | **OK** |
| Inspection notes — `InspectionFeedbackPanel.kt:97` (`noteDraft`) | suggestions, send-backs | filed: `DwInspectionStore`; typed and not yet filed: was **saved-state** only, now also a form draft | disk (was saved-state) | `syncInspectionNotes` (`WorkshopRepository.kt:2353`) from `syncOutbox`, now also in the background | queued list on the panel | detail cached | **OK** (was RISK — the typing survived a background kill but not a swipe-away; fixed) |
| Design review — `DesignReviewScreen.kt:598` (`designRatingRound`), `DesignReviewScreen.kt:1625` (`designRatingLedger`) | ratings; the held order | ratings: outbox (`submitDesignRating`, `WorkshopRepository.kt:2632`); order: the stage draft | disk | outbox / engine | the last ranking and history with their date | **ReadCache** for the ranking and the ledger | **OK** (was GAP) |
| Report history — `DwReportHistoryScreen.kt:211` | — | — | — | — | Before: an error. Now: the last copy with its date | **ReadCache (new)** | **OK** (was GAP, fixed) |
| Provenance — `DwProvenanceScreen.kt:200` | — | — | — | — | Before: an error. Now: the last copy with its date | **ReadCache (new)** | **OK** (was GAP, fixed) |
| Viewers, inspectors — `WorkshopViewersScreen.kt:388` (`setDesignWorkshopViewers`), `WorkshopInspectorsScreen.kt:341` (`setDesignWorkshopInspectors`) | access grants | online only | — | — | the save error in words | no | **OK by decision** (§3) |
| Sketches & prototypes — `SketchesAndPrototypesScreen.kt:282` (`DW_SKETCH_CHOOSER_OFFLINE`) | sketch rows, through the stage draft | `WorkshopDraftStore` | disk | engine | the last workshop list with its date | **ReadCache** for the chooser; review tab from the draft | **OK** (was GAP) |
| Designer roster, access roster — `DesignerRosterScreen.kt:628` (`suspendDesigner`), `AccessRosterScreen.kt:679` (`approveAccessRequest`) | who may sign in, empanelment | online only | — | — | "could not reach" | **not cached, by the repository's own rule** | **OK by decision** (§3) |

### 2.3 Questionnaires

| Screen | Writes | Pending data lives | Survives death | Syncs | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|---|
| Questionnaire list — `QuestionnaireListScreen.kt:690` | a new form (the row only) via `createCustomQuestionnaireOrQueue` (`WorkshopRepository.kt:3533`) | outbox | disk | outbox | Before: "could not connect" and **no rows**. Now: the last unfiltered list with its date | **ReadCache (new)**; a search is never answered from the device | **OK** (was GAP, fixed) |
| Questionnaire detail — `QuestionnaireDetailScreen.kt:572` (`ADD_QUESTION`), `QuestionnaireAuthoring.kt:32` (`OFFLINE_QFORM_OP`) | sections, questions, renames, removals | each step is an outbox entry under a temporary id; children of a queued section — or of a questionnaire started offline — are queued against its temporary id and resolved to the server id at replay | disk (including the temp-id map) | outbox in queue order + background job; creates carry an `Idempotency-Key` | a child whose parent was refused is refused in its turn, with a sentence saying why | the steps drawn on the form at once | form cached | **OK** (was RISK) — a copy (reuse) is queued too; re-uploading a workbook and starting a sitting need a connection, by decision (§3, 11) |
| Answer a questionnaire — `QuestionnaireAnswerScreen.kt:208` | answers + notes | Before: **memory**, while the screen said "your typing is still here — save when you have a signal" (`QuestionnaireAnswerScreen.kt:262`). Now: a form draft | disk (was memory) | **sent only when the person presses Save on the live form** — by decision, §3 | cached-copy notice | form cached | **OK** (was GAP, fixed — the promise on screen is now true) |
| Consolidated questionnaire — `ConsolidatedQuestionnaireScreen.kt:275` | — | — | — | — | Before: an error. Now: the last copy with its date | **ReadCache (new)** for the artisan list and each document | **OK** (was GAP, fixed) |

### 2.4 Everything else

| Screen | Writes | Pending data lives | Survives death | Offline the user sees | Reads cached | Class |
|---|---|---|---|---|---|---|
| My tasks — `MainActivity.kt:21219` | status changes, progress | online only (§3) | — | Before: "couldn't be loaded". Now: the last list with its date | **ReadCache (new)** | **OK** (was GAP, fixed) |
| My activity — `MainActivity.kt:13800` (`MyActivityScreen`) | — | — | — | the last list with its date | **ReadCache** (`CachedActivityRow`) | **OK** (was GAP) |
| App feedback rating — `MainActivity.kt:15023` (`FeedbackScreen`) | `PUT /feedback/me` | Before: memory, and an offline Save **failed**. Now: a draft, and queued to the outbox as `appFeedback` (`WorkshopRepository.kt:235`, `OFFLINE_APP_FEEDBACK`) — an upsert, so a replay never duplicates | disk (was memory) | "kept on this device" | stored rating live | **OK** (was GAP, fixed) |
| Report something — `MainActivity.kt:14801` (`FeedbackReportSection`) | bug/suggestion report | a draft; on Send with no signal, queued as `feedbackReport` under an `Idempotency-Key`; the form's choices are cached | disk | "kept on this phone" | vocabulary via **ReadCache** | **OK** (was GAP) |
| Update existing / record editor — `MainActivity.kt:9641` (`RecordPickerScreen`), `MainActivity.kt:10056` (`EditScreen`) | corrections | the picker list and each record opened before are cached (`cachedRecordForCorrection`); a correction made on them is queued with ONLY its changed fields (`changedKeys`) and the record's `updatedAt` (`baseUpdatedAt`) | disk | the server refuses with 409 `record_changed` if anybody changed the record since; the tray then offers **Review changes** — each changed field, mine against theirs, then *Send mine* (only my fields, rebased) or *Keep theirs*, which drops only the field changes — queued photographs and recordings stay and are added to the record. A refusal whose record already holds every value the correction sends (its PATCH landed and the answer was lost) is re-based and sent without asking. An interview correction is queued only when the interview it was composed against carries its version, and the server compares that version to the millisecond | **ReadCache** | **OK** (was GAP) — an interview correction is queued the same way since 2026-10-17 (§3, decision 8) |
| View data, data browser — `MainActivity.kt:15683` (`ViewDataScreen`), `DataBrowserScreen.kt:422` | admin review actions | online only (§3) | — | the last lists and folders with their date | **ReadCache** (`loadViewEntriesCached`, `data-tree`) | **OK** (was GAP) |
| Map — `MapScreen.kt:501` | — | — | — | Before: "the map could not be loaded". Now: the last map for the same scope and level, with its date; the outline and borders are bundled | **ReadCache (new)** | **OK** (was GAP, fixed) |
| Search, scan a code — `SearchScreen.kt:554`, `RecordCodeLookup.kt:159` (`lookUpRecordCode`) | — (a join card scanned here is queued, as above) | — | — | "no connection" in words | no — a query, not a list (§3) | **OK by decision** |
| Sharing, workshop access, users, assign tools, task board — `MainActivity.kt:20218` (`SharingForm`), `MainActivity.kt:20540` (`WorkshopAccessScreen`), `MainActivity.kt:20218` (`UserManagementForm`), `MainActivity.kt:14029` (`ToolAssignScreen`), `TaskAdminScreen.kt:679` (`createTaskBatch`) | permission grants, user administration, assignments | online only | — | errors in words; failed people stay ticked | no | **OK by decision** (§3) |
| Appearance — `AppearanceScreen.kt:287` (`savePreferences`) | display preferences | applied on the device at once and marked pending with its time (`markPending`) | disk | resent at the next sync or sign-in | **last write wins by timestamp** (`localPreferencesWin`): a change made here wins over the server row only when it is the later one | "saved on this device" | device copy | **OK** (was RISK) |
| My AI keys, API keys, usage recording, consent — `MyAiKeysScreen.kt:163` (`setMyAiKey`), `ApiKeysScreen.kt:349` (`setSecret`), `UsageRecordingScreen.kt:289` (`recordConsent`) | secrets, consent | online only | — | errors in words | no | **OK by decision** — a secret is never written to disk to wait for a signal, and consent is recorded by the server or not at all (§3) |
| Speech & AI — `SpeechAndAiScreen.kt:228` | — (device-local) | device | disk | — | device | **OK** |
| Sign-in, password gate, consent gate, terms, walkthrough — `MainActivity.kt:2262` (`LoginScreen`), `PasswordGate.kt:147` (`PasswordGateScreen`), `UsageConsentGate.kt:302` | credentials, consent | not persisted, by design (a password is never written to disk) | — | sign-in needs a connection | the published notice is kept | **OK by decision** |
| Dashboard, settings hub, review queue — `MainActivity.kt:5578` (`DashboardScreen`), `MainActivity.kt:15572` (`AdminHubScreen`), `InspectionReviewQueue.kt:57` (`InspectionAwaitingSection`), `MainActivity.kt:15885` (`ReviewApprovalCard`) | record approvals (admin) | online only | — | errors in words | the inspector queue is cached | **OK** |

### Count

42 rows in §2. **After both changes of 2026-10-16: 42 OK** (6 of them *OK by decision*), **0 GAP**,
**0 RISK**. **Before** them the same rows read **9 OK, 23 GAP, 10 RISK**.

The first change closed eighteen GAPs and seven RISKs: work lost to a process kill before Save, offline
saves that failed instead of queueing, read-only screens that showed an error where they had a list,
queues that waited for the app to be reopened, captures in a cache Android empties on its own, and an
inspector's note kept only in saved state. The second change closed the six it had deferred — a create
whose answer is lost, offline correction of a record, the five lists still uncached, questionnaire
authoring, the designer profile and "Report something", and appearance changes made offline.

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
7. **A record IS opened for correction from a cached copy — because the correction is now safe.**
   It is sent as a PATCH of only the fields the person changed, carrying the record's `updatedAt` as
   `expectedUpdatedAt` (`records.assert_expected_updated_at`, already on the six record routes). If
   anybody changed the record since, the server refuses it and the tray says so; nothing is
   overwritten in silence. This closes the MEDIUM finding of 2026-10-10 for the six record types.
8. **An INTERVIEW correction is queued too (since 2026-10-17).** It needed a connection while
   another interviewer's answers, which live in their own table, did not move the interview's
   `updatedAt`. Now every answer write moves it (`upsert_responses`), and the PATCH claims the version
   with a compare-and-set inside its transaction (`records.claim_expected_updated_at`), so two
   corrections composed against one version cannot both pass. The correction is queued with only its
   changed keys and that version, and a moved interview is held for **Review changes**, answers named
   by their question (`InterviewCorrection.kt`). Pinned by `backend/tests/test_interview_version.py`
   and `InterviewCorrectionTest`.
9. **Idempotency keys are honoured on record creates only** (`CREATE_ROUTES` in
   `backend/app/core/idempotency.py`), kept 30 days, and a first request still running is taken over
   only after 15 minutes, by one conditional update. Sign-in, token, password, secret and AI-key
   routes are never stored, whatever header they carry. That includes the two-step verification routes under
   `/api/auth/two-factor/` that arrived with the 2026-10-16 merge of `integrate/designer-release`:
   setup answers a secret and verify answers a token, so neither is ever written to the table.
   Copying a questionnaire as a template (`/questionnaires/{id}/reuse`) joined the list on
   2026-10-17, when the handset began to queue it.
10. **Backups keep work and drop sessions.** `android/app/src/main/res/xml/backup_rules.xml` and
    `data_extraction_rules.xml` exclude the sign-in token and cached account (`field_repository_auth`,
    where any two-step state also lives), the cached lists and the staged-object journal, and keep the
    outbox, drafts and photographs.
11. **Uploading an edited workbook and starting a sitting need a connection; copying a questionnaire
    does not (2026-10-17).** A re-upload replaces the questionnaire's questions with the file's and
    answers with the report of what it kept, reworded and retired; applied hours later it would retire
    questions somebody added on the web in between as "not in the file", and the report would reach
    nobody. A sitting's answers are only sent from the live form (decision 3), so a sitting started
    offline could not be answered until there was a signal anyway. Both say so under the control and
    when tapped (`QuestionnaireOfflineCopy.kt`). A copy changes nothing on the original, so it is a
    queued step (`QFormOp.REUSE`) behind any step still waiting for the source, with an
    `Idempotency-Key`.

---

## 3a. The release review's fixes (2026-10-16)

The designer release review found one blocker and four follow-ups in this work; all are fixed, with
tests.

1. **Blocker — the outbox fallback sent a different body under the same key.** When an online create
   lost its connection, the form re-ran its save to queue it, and the re-run rebuilt the body with a
   fresh `recordedAt` (and, for product, tool, workshop and process, a fresh `clientKey`). Had the first
   request landed, the replay was a different request under the same `Idempotency-Key`: the server
   answered 422, the outbox parked it as refused, and its photographs were never attached. Now every
   submit mints a new key and its fallback carries the exact body and key of the attempt it follows
   (`OnlineAttempt` in `MainActivity.kt`); a queued entry that carries an online key gets no second
   `clientKey`; the online artisan create applies the same identity default as its replay. And if a
   replay is ever refused as a different request anyway (an older build's entry), the outbox asks
   `GET /api/idempotency-keys/{key}` (backend `routes/idempotency_keys.py`) for the record the first
   send made and carries on to its photographs. Pinned end to end by
   `android/app/src/test/java/com/designprototype/workshop/data/OutboxReplayEndToEndTest.kt`.
2. **The version check raced.** Two corrections composed against one version could both pass the
   comparison against a row read before the transaction. The seven routes now re-read the row with
   `SELECT … FOR UPDATE` inside the transaction (`records.lock_and_assert_expected_updated_at`); the
   second waits and is refused. Pinned by `backend/tests/test_record_update_precondition_lock.py`,
   including a two-transaction race on a local database.
3. **False conflicts.** A correction that lands re-bases every later queued correction to the same
   record on its new `updatedAt`, and a stale refusal whose review would be empty is re-based and sent
   again at once (once) instead of being shown as an empty "Review changes"; the tray does the same if a
   review it opens turns out empty.
4. **Smaller ones.** The "copy saved on this phone" notes in the record editor, view data and the media
   screen are drawn as `CachedCopyNote` rather than sent as messages; a stored answer that could not be
   saved releases its running marker; draft writes ask for a background pass at most once a minute
   while online; and the Aadhaar migration's `CREATE TYPE`s are guarded so a re-run does not fail.

---

## 4. Deferred

Nothing. The six items the first change deferred were built by the second and are closed in
[OPEN_FINDINGS.md](OPEN_FINDINGS.md) under *Closed on 2026-10-16*. What remains online-only does so
by decision (§3: administrative writes, secrets and consent, answering a questionnaire, uploading a
workbook and starting a sitting, searches).

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

The second change adds `android/app/src/test/java/com/designprototype/workshop/data/OfflineDeferralsTest.kt`
(idempotency keys on every create and no correction, the in-progress 409, changed-fields-only PATCHes
with the precondition, recognising `record_changed`, "Review changes", temporary ids and their map,
pending authoring drawn on the form, finding a created section or question) and
`android/app/src/test/java/com/designprototype/workshop/ui/PreferencesSyncTest.kt` (last write wins), and
on the server `backend/tests/test_idempotency_key.py` (replay, per-account keys, different body 422,
in-progress 409, 15-minute take-over by exactly one repeat, the create allow-list, 30-day keys) and two
interview cases in `backend/tests/test_record_update_precondition.py`.

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
