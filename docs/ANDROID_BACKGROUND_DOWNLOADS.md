# Android data browser and background downloads

The Android **Data** screen in `com.designprototype.workshop` (Design Workshop). It is the same Kotlin as
the Field Repository app: the shared tree is `android/app/src/{main,test,androidTest}/java/<pkg>/explorer/`,
copied with `python scripts/explorer_android.py --to ../documentation-portal` and pinned by
`shared/android-explorer.sha256`. Only `explorer/portal/ExplorerPortal.kt` differs per app.

## Contents

| | |
|---|---|
| [1. The screen](#1-the-screen) | tree first, search as its toolbar, one place for downloads |
| [2. How a download runs](#2-how-a-download-runs) | WorkManager for zips, DownloadManager for single files |
| [3. Permissions](#3-permissions) | what the manifest asks for and why |
| [4. Play Console declaration](#4-play-console-declaration) | the exact text to paste |
| [5. Server contract](#5-server-contract) | the routes the app calls |
| [6. Tests](#6-tests) | unit and emulator |
| [How this document is kept true](#how-this-document-is-kept-true) | |

## 1. The screen

As of 0.0.13 (owner-approved boards AndroidFolder, AndroidDownload, AndroidDownloads, AndroidSearch,
AndroidSelect, AndroidPreview, AndroidAudio, AndroidAudioPlayer, AndroidPreflight, AndroidTablet,
AndroidLargeText, AndroidDark, PhoneTables, States, SearchBoth, ReportOptions and WorkshopOverview).

| Part | What it does |
|---|---|
| Toolbar | *Jump to a record* with its filter chips always visible; *Search everything* (top-bar icon, or the keyboard's search key) opens the unified search |
| Organise by | a card with the grouping chips and the description of the active one (review-status chips too in Field Repository) |
| Folder card | breadcrumb, record name, `N files · size`, *New since you looked +N*, the first four recorded fields and *Show all N fields* |
| Download card | seven include chips (Text, Images, Videos, Audio, Transcripts, Documents, Other) with their sizes, the selected size, *Report (.xlsx)* and *Download folder*, and *All transcripts, every format* |
| Preflight | a sheet before downloads over 500 MB, on mobile data or when space is short: mobile-data warning, *Wait for Wi-Fi* (WorkManager unmetered constraint), storage left after, the save location with *Change* (a folder picked through the system picker, kept with a persisted permission and remembered) or *Use Downloads instead*, and a smaller download without videos |
| Progress | an on-screen card for the running download: percent, bytes, files N of M, speed and network, time left, the current file with its own bar, a note when files will retry at the end, Pause or Resume, and Cancel; then *Recent files* (saved rows green, failed rows red) with *All N*, the full list of files kept for the job, and the save path; a paused or dropped zip picks up at the next file instead of starting again |
| Result | *Saved N files* in green with Open and Share, or a red card with Retry |
| Tabs | Files, Tables and Transcripts; the tab resets to Files only when the folder changes |
| Files | thumbnails, type, size and whether a transcript exists, with *Save* on the row; a saved file turns green with *Open*, a failed one red with *Retry*; a running save shows its percent and bytes |
| Tables | row numbers from the full sheet, also while searching; sheet picker, per-sheet counts (matches while searching), a server-side search of every row (debounced, never a rebuild), rows as cards with *All N columns*, *This table (.csv)* and *Full report (.xlsx)*; the state lives outside the list, so scrolling never reopens, closes or reloads it, and a refresh keeps the rows on screen |
| Saved views | `https://<web host>/data?viewId=<id>` opens the app (app link on the same host as `/set-password`) and restores the folder, review status, search and tab; `?path=` opens a folder |
| Report options | sheets with counts, answers in Original, English or Both, every row or only the filtered ones, file links, file name, *Remember these choices*, and saved views with *Copy link* |
| Transcripts | this folder's transcripts with a search inside them, Ready, Translating and Failed pills, a language filter, *Read*, *.docx*, *Try again* on a failed one (a red row), *Transcripts (.zip)* and CSV exports |
| Preview | video with the transcript following playback (the current line tinted), Transcript, English and Details tabs, previous and next, *Save*, *Transcript (.docx)* and *Share* |
| Audio | *Clear voices* when the server holds an isolated copy (hidden otherwise); waveform with tap to seek, speed, back and forward 10 s, speaker chips and the transcript following playback; playback continues in a Media3 session (lock screen, notification, headphone buttons, the current line) and a mini player while browsing |
| Selection | long press; a bar with the count and size, type pills, *Select all N*, a breakdown by type, *Share* and *Download N · size* |
| Workshop overview | on a workshop folder: totals, review and transcript counts, and artisans least complete first with one square per section; a square opens that artisan's record at that questionnaire section |
| States | loading skeletons shaped like the page, an empty folder card, an amber offline card with the age of the saved copy, and a red *didn't load* card that retries in 8 s with *Try now* |
| Tablet | at 840 dp and wider, the tree on the left and the folder, download card and a two-column file grid on the right |
| Large text | at 200 % text the rows stack, buttons go full width and nothing is cut off |
| Downloads | the top-bar sheet: running cards with percent and the progress line, paused cards with Resume, saved cards green with Share and Open, failed cards red with Retry; a notification tap opens it |
| Notifications | `Title · 62%`, the progress line, the current file and speed, with Pause, Cancel and Open; finished `Saved <title>` with Open and Share; failed `<file> didn't download` with Retry and Details |

Folder downloads include everything by default. Field Repository downloads include every review status;
the status chips change only what the tree lists. Reports and transcript zips are rebuilt by the server,
so they are never resumed with `Range`, and their bytes are checked for the zip signature before they are
saved; a busy server is retried with backoff.

## 2. How a download runs

| Kind | Runs in | Survives |
|---|---|---|
| Folder, selection, report, transcript zips | `ExplorerWorker`, a WorkManager worker promoted to a `dataSync` foreground service with a progress notification and a Cancel action | leaving the screen, switching apps, locking the phone, a custom tab or another activity on top, process death (WorkManager reschedules) |
| One media file | the system `DownloadManager`, given the signed storage URL the API redirects to | the same, and app updates |
| One media file whose API does not redirect | `ExplorerWorker` as above | the same |

Each file is fetched up to three times and resumed with `Range` from the byte it stopped at. A file that
still fails is left out of the zip and listed. Up to four files are fetched at once; a file of 64 MB or more
is fetched on its own. Audio asked for as `.mp4` comes back either converted or as the original, which is
saved under its real name (`X-Conversion: original`, or its first bytes on an older server). A cancelled or
failed download deletes its scratch folder and its pending entry in Downloads; a sweep on every visit
removes anything a killed process left behind.

## 3. Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | run a long download as a foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | the foreground service type for a user-started transfer (Android 14 and later) |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `ExplorerAudioService`, the Media3 session that keeps a recording playing with the screen off |
| `POST_NOTIFICATIONS` | progress and finished notifications; asked for when the first download starts; downloads run without it |
| `ACCESS_NETWORK_STATE` | the mobile-data warning, *Wait for Wi-Fi*, and the offline state |
| `INTERNET` | unchanged |

`DownloadManager` and saving to Downloads through `MediaStore` need no permission on Android 10 and later.

## 4. Play Console declaration

Play Console, *App content*, *Foreground service permissions*: tick **Data sync** and paste the text below.

> **Task:** Download research data the user asked for.
>
> **Description:** When a signed-in researcher taps *Download* on the Data screen, the app saves a zip of the
> chosen folder or records (photos, videos, audio, transcripts and a spreadsheet report) to the phone's
> Downloads folder. Archives can be several gigabytes, so the download runs as a foreground service of type
> `dataSync` with a visible progress notification and a Cancel button, and it keeps going when the user
> switches app or locks the screen. The service starts only from that tap, stops when the file is saved or
> cancelled, and never runs on its own.
>
> **User impact if deferred or interrupted:** The archive would be left incomplete, and the researcher would
> have to start a multi-gigabyte download again from the beginning.

Attach a screen recording that shows: opening *Data*, tapping *Download*, *Start download*, the progress
notification with Cancel, pressing Home, and the finished notification with Open.

## 5. Server contract

`shared/data-explorer-contract.json` is the source. The app calls:

| Route | Use |
|---|---|
| `GET /data/tree?path&slim=1&cursor&status` | one page of a folder |
| `GET /data/media/{id}/transcript` | one transcript, when opened |
| `GET /data/stats`, `POST /data/stats` | count and size before a download |
| `GET /data/manifest?stream=1`, `POST /data/manifest` | the file list of a folder or a selection, streamed |
| `GET /data/media/{id}/download?format=mp4&fallback=original` | audio conversion with a fallback |
| `GET /data/media/{id}/thumbnail?size=256` and `/preview` | listing thumbnails and previews |
| `GET /transcripts/{id}/download?format=zip` | one transcript, every format |
| `GET /data/transcript-bundle?path` | every transcript of a folder, every format |
| `GET /data/transcripts/languages` | the transcript language filter |
| `GET /data/report?format=xlsx&path` | the report; `503` with `Retry-After` shows *Busy, retrying in 60 s* and retries once |

## 6. Tests

| Test | Covers |
|---|---|
| `explorer/ExplorerEngineTest` | zip contents, three tries and a failed-file list, `Range` resume, audio fallback names, space check, cancel clean-up, selection, busy retry, a 20,000-row manifest within 64 MB of heap |
| `explorer/ExplorerStateTest` | paging, one transcript fetch per open, memory and offline copies, landing, selection, locate, restricted, one load per start and per folder, tab reset on folder change, selection size |
| `explorer/ExplorerTablesStateTest` | tables asked once per folder, debounced row search that keeps the rows on screen, no old rows in a new folder, remembered tables, busy backoff, a failed refresh keeps the rows |
| `explorer/ExplorerProgressTest`, `explorer/ExplorerPreflightTest` | speed, time left, recent files, pause; when the preflight sheet shows, space left, the smaller download |
| `explorer/ExplorerPartsTest` | cache file names, sizes, busy countdown, file names from `Content-Disposition`, job list |
| `explorer/ExplorerScreenUiTest` (emulator) | the folder at a glance, sizes on the include chips, selection with size and type pills, thumbnails, preview tabs, all-formats zip, transcript on demand, paging, table cards and search, tables that stay put while scrolling, the language filter, a notification tap opening Downloads, 200 % text, tablet panes |
| `explorer/ExplorerSheetsUiTest` (emulator) | mobile data and space warnings, Open and Share, per-row spinners, a saved row's Open, the progress card, the preflight sheet |
| `explorer/ExplorerBackgroundDownloadTest` (emulator) | a zip continues after Home, a file continues after `moveTaskToBack`, Cancel leaves nothing behind |

## How this document is kept true

The screen is `explorer/ui/ExplorerScreen.kt`, the download engine `explorer/data/ExplorerEngine.kt`, the
background runner `explorer/data/ExplorerWorker.kt`, and the permissions `android/app/src/main/AndroidManifest.xml`.
`SharedExplorerTreeTest` fails when either app's copy drifts, and the tests in section 6 pin each behaviour
above. Change the declaration text here and in the Play Console together.
