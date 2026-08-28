package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.designprototype.workshop.data.WorkshopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * **WHERE A SAVED TRACE LANDS, AND HOW IT REACHES THE PHONE IN FRONT OF THE DESIGNER.**
 *
 * ── THE ROUTE, AND WHY IT IS NOT A NEW ONE ────────────────────────────────────────────────────
 *
 * Bytes in, public Downloads folder out, share sheet beside it. Every step of that already exists in
 * this application and none of it is reimplemented here:
 *
 *   `WorkshopRepository.persistFileToDownloads` — MediaStore on Q+ with the `IS_PENDING` handshake,
 *       a raw external path on 26/27/28 behind a checked `WRITE_EXTERNAL_STORAGE`, and an app-private
 *       fallback under `filesDir` when that permission was refused. Its own KDoc says why it must not
 *       be copied: "everything below … was learned from field failures, and a second copy of it would
 *       be a second copy to get wrong". `report/ReportExport.kt` publishes its .docx and .pdf through
 *       that exact function for that exact reason, and so does the `.dpwq` questionnaire handoff.
 *   `WorkshopRepository.shareUriForSavedFile` — the grantable Uri, re-derived from MediaStore on Q+
 *       and from the FileProvider below it. Nullable and never assumed.
 *   `ACTION_SEND` through `Intent.createChooser` — the OS's own share sheet, which is the canonical
 *       door to Quick Share, Bluetooth object push, a cable and a shared folder, and which costs
 *       **no new permission**: the manifest declares nothing about Bluetooth or Wi-Fi at all, because
 *       the operating system owns the radios. `ui/questionnaires/QuestionnaireHandoffUi.kt` makes the
 *       whole argument; `ReportScreen` and `RecordCodeCard` use the same three lines.
 *
 * ── WHY BOTH DOORS AND NOT ONE ────────────────────────────────────────────────────────────────
 *
 * Saving and sharing answer different questions and this feature needs both. A designer emailing a
 * PDF to a print shop wants the share sheet; a designer who will plug the phone into a laptop this
 * evening wants the file in Downloads where a file manager can find it. The save is unconditional and
 * the share is offered on top of it, so the second is never the only route to the first — which is the
 * failure `mediaStoreDownloadUri`'s KDoc records from the other direction, where a null Uri made every
 * Share button in the app invisible on Android 10 and above.
 *
 * ── FILE PROVENANCE THIS DELIBERATELY DOES NOT RECORD ─────────────────────────────────────────
 *
 * `ReportExport.Result` carries `sizeBytes` and `checksumSha256`, and its KDoc explains that they are
 * there for a screen on a different client: the web's report history compares two exports by checksum.
 * **A traced drawing has no such history and no endpoint that takes one.** Computing a SHA-256 here
 * would be a second read of the file for a field nothing reads, so the size is carried (it is free —
 * the byte array's own length) and the checksum is not. If a "you saved this drawing twice" surface is
 * ever built, this is where the hash goes and `ReportExport.sha256Of` is the spelling to copy.
 *
 * ── WHAT IS NOT HERE, AND WHERE IT IS ─────────────────────────────────────────────────────────
 *
 * Nothing here uploads anything, and nothing here attaches anything to the record. Filing the SVG on
 * `sketch.lineArtFile` goes through the ordinary `attach` door — the same one a camera photograph uses
 * — so eager pre-upload, per-file retry and the offline draft store all already apply to it. That is
 * the trace panel's wiring, not this file's; see this lane's followups.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Where the temp file goes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The cache subdirectory a half-written export lives in.
 *
 * A SUBDIRECTORY AND NOT `cacheDir` ITSELF, for one reason worth a line. `ReportExport.publish` writes
 * its temp file straight into `cacheDir` under the report's own name, and `WorkshopRepository`'s five
 * download paths do the same with theirs. Those names cannot collide with a trace export's today —
 * they are all `DesignWorkshop_*` and this one is the photograph's stem — but "cannot collide today"
 * is a property of two naming rules that were never written to agree, and the failure if they ever do
 * is one export's bytes inside another export's file. A directory of our own costs nothing and removes
 * the question.
 *
 * It sits under `cacheDir`, which `res/xml/file_paths.xml` already publishes as `cache-path name=
 * "capture_cache" path="."`, so the FileProvider can reach anything left in it if a future caller ever
 * needs to hand one over directly. Nothing does today: the file is copied out and deleted before this
 * function returns.
 */
private const val DW_TRACE_EXPORT_CACHE_DIR = "dw-trace-export"

/* ────────────────────────────────────────────────────────────────────────────
 * The result
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Where a saved export landed and how to hand it on.
 *
 * [savedTo] is what `persistFileToDownloads` returns and what every other download path in this app
 * shows: `"Downloads/<name>"` on Q+, an absolute path below it. It is NOT a Uri, deliberately — that
 * function's own KDoc explains that widening its return type would change what five screens display.
 *
 * [shareUri] is nullable and MUST be gated on rather than assumed. `shareUriForSavedFile` re-derives a
 * MediaStore row with a query that can come back empty, and a null handed to `FileProvider` throws
 * `IllegalArgumentException` at the moment the designer taps Share.
 *
 * [storedName] is the name MediaProvider ACTUALLY used, which is not always the one that was asked
 * for: a colliding `DISPLAY_NAME` is silently uniquified to `name (1).ext`. It is read back out of
 * [savedTo] rather than assumed, because the whole reason `persistFileToDownloads` re-queries it is
 * the defect where every Share control in the app handed over the PREVIOUS export. Showing the
 * requested name beside a file that is not on disk would be the same lie one screen further out.
 */
data class DwTraceExportSaved(
    val savedTo: String,
    val shareUri: Uri?,
    val storedName: String,
    val mime: String,
    val sizeBytes: Long,
)

/* ────────────────────────────────────────────────────────────────────────────
 * Saving
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Write [bytes] into the device's Downloads folder as [fileName] and say where it went.
 *
 * ── WRITE COMPLETELY, SYNC, THEN PUBLISH ──────────────────────────────────────────────────────
 *
 * `flush()` then `fd.sync()` then hand over, which is `ReportExport.publish`'s discipline and
 * `OfflineOutbox.write`'s before it. The publish step copies this temp file into a MediaStore row that
 * becomes visible to every app on the device the moment `IS_PENDING` clears, and a copy whose source
 * bytes are still only in the page cache publishes a TRUNCATED file — which Illustrator reports as a
 * corrupt SVG, indistinguishable from every other way a trace can go wrong. The temp file is deleted
 * in a `finally`, so a failed save does not leave a half-built drawing filling the cache directory of
 * a phone that is already short of space.
 *
 * ── ONE SAVE AT A TIME, AND THAT IS THE CALLER'S JOB ──────────────────────────────────────────
 *
 * `QuestionnaireHandoffCard`'s KDoc states the rule and the reason: building a file "ends in
 * `persistFileToDownloads`, which is the same MediaStore write the two .xlsx downloads make into the
 * same folder — two of those racing is how one of them ends up truncated with no error anywhere, so
 * all three controls take turns through one flag." [DwSketchTraceExportCard] holds that flag for this
 * feature. This function does not serialise anything itself, because a lock here would be invisible to
 * the other three writers and would therefore be a false sense of one.
 *
 * ── THROWS RATHER THAN REFUSES, WHICH IS THE OTHER WAY ROUND FROM THE EXPORTER ────────────────
 *
 * `DwTraceExportOutcome.Refused` exists because a writer that cannot write a format has a sentence
 * somebody wrote. A flash that is full, a revoked permission or a MediaStore insert that fails does
 * not: those are the cases nobody wrote a sentence for, which is exactly what an exception is for.
 * The card catches and prints, as `QuestionnaireHandoffCard` does with `apiErrorMessage`.
 */
suspend fun dwSaveTraceExport(
    context: Context,
    repository: WorkshopRepository,
    bytes: ByteArray,
    fileName: String,
    mime: String,
): DwTraceExportSaved = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val staging = File(appContext.cacheDir, DW_TRACE_EXPORT_CACHE_DIR).apply { mkdirs() }
    val tmp = File(staging, fileName)
    try {
        FileOutputStream(tmp).use { fos ->
            val buffered = BufferedOutputStream(fos)
            buffered.write(bytes)
            buffered.flush()
            fos.fd.sync()
        }
        val savedTo = repository.persistFileToDownloads(appContext, tmp, fileName, mime)
        DwTraceExportSaved(
            savedTo = savedTo,
            shareUri = repository.shareUriForSavedFile(appContext, savedTo),
            // The last path segment of what the repository returned. On Q+ that is the name
            // MediaProvider settled on after any uniquifying; below Q it is the name we asked for,
            // because a raw file copy overwrites rather than uniquifies.
            storedName = savedTo.substringAfterLast('/').ifBlank { fileName },
            mime = mime,
            // The array's own length, not the file's. They are the same number and this one costs no
            // syscall; a stat here would also be a second answer to one question.
            sizeBytes = bytes.size.toLong(),
        )
    } finally {
        runCatching { tmp.delete() }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Sharing
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The `ACTION_SEND` intent for a saved export.
 *
 * THE FORMAT'S OWN MIME TYPE AND NOT A WILDCARD. `image/svg+xml`, `application/pdf`,
 * `application/postscript`, `image/vnd.dxf`, `image/png` — read off the table, which the web's own
 * spec checks against `ExportOptions.mimeType` rather than trusting. A declared type is what lets the
 * receiving app offer the right handlers. A wildcard type would instead offer every app on the
 * phone, and the questionnaire handoff's KDoc makes the same argument from the receiving side
 * about why a custom subtype must stay one.
 *
 * THAT WILDCARD IS DELIBERATELY NOT SPELLED OUT ABOVE. It contains a star followed by a slash,
 * which ends a KDoc block wherever it appears — including inside backticks. Writing it literally
 * closed this comment at that character and turned every line below into a top-level syntax
 * error, which is how it was found. Describe it; do not type it.
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` IS NOT OPTIONAL: without it the receiving app gets a Uri it has no
 * permission to read, and the share silently produces an empty attachment. That sentence appears
 * verbatim beside all three existing share buttons in this app.
 *
 * NO `FLAG_ACTIVITY_NEW_TASK`. The chooser is started from the activity the designer is looking at and
 * should come back to it; `MainActivity:2959` adds that flag for a case that is not this one.
 *
 * ── WHAT THE SHARE SHEET CANNOT TELL US, WHICH THE CARD SAYS OUT LOUD ─────────────────────────
 *
 * `ACTION_SEND` is fire-and-forget: nothing here learns which target was chosen. A designer with no
 * signal who picks a chat app gets a send that fails later, inside that app, and looks like our
 * defect. `QuestionnaireHandoffUi` states this in words rather than trying to detect it, "because the
 * chooser does not tell us and guessing would be worse", and [DwSketchTraceExportCard] carries the
 * same sentence for the same reason.
 */
fun dwTraceExportShareIntent(saved: DwTraceExportSaved): Intent? {
    val uri = saved.shareUri ?: return null
    return Intent(Intent.ACTION_SEND).apply {
        type = saved.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, saved.storedName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * What to say when there is no grantable Uri.
 *
 * NOT A DEAD END. On the API levels where this app has no Uri to hand over, the file is in the public
 * Downloads folder and any file manager or share sheet can pick it up from there — the same sentence
 * the questionnaire handoff and `ReportScreen` both fall back to, kept identical so a designer meets
 * one wording for one situation.
 */
const val DW_TRACE_EXPORT_NO_SHARE_SENTENCE: String =
    "Open it from the Downloads folder to send it — any app's file picker or share sheet will find " +
        "it there."

/**
 * The warning under the share button.
 *
 * Verbatim from `QuestionnaireHandoffUi`, because it is the same fact about the same mechanism on the
 * same phone, and two phrasings of it would be two accounts of one limitation.
 */
const val DW_TRACE_EXPORT_SHARE_CAVEAT: String =
    "Pick nearby share, Bluetooth or a cable if you have no signal. A chat app will look like it " +
        "worked and then send nothing until you are back online — the share sheet does not tell this " +
        "app which one you chose, so it cannot warn you afterwards."
