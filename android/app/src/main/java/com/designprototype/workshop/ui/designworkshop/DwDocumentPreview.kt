package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.UUID

/**
 * An uploaded document, rendered on the handset where the platform can render it.
 *
 * The owner's instruction of 2026-08-25 asks for two documents to be uploadable and previewable —
 * the designer's CV on the profile screen, and the market survey write-up on stage 8 — with the
 * exception stated in the same breath: *"If the uploaded Market Survey is a PDF, it should be
 * rendered/previewable within the application. Rendering is not mandatory for non-PDF document
 * formats."* This is the handset's half, built to the same split as the web's
 * `components/media/DocumentPreview.tsx`.
 *
 * ── WHY `PdfRenderer` AND NOT A LIBRARY, AND WHY ONLY THE FIRST PAGE ────────────────────────────
 *
 * `android.graphics.pdf.PdfRenderer` is in the platform from API 21 and this app's `minSdk` is 26,
 * so it costs nothing to ship and nothing to keep current. A PDF library would be several hundred
 * kilobytes added to an APK that already crossed 64 MiB and tripped its own size guard for real when
 * CameraX went in.
 *
 * ONE PAGE, deliberately. The question this control answers is "is this the right document" — a
 * designer checking that their CV is on file, or that the survey they attached is the survey and not
 * last year's. That is answered by the first page and by the filename. A scrolling multi-page reader
 * would be a document viewer, which every one of these handsets already has and which the Open
 * button hands the file to. The strip under the page says how many pages there are, so nobody reads
 * one page as the whole document.
 *
 * ── WHY THE REMOTE FILE IS DOWNLOADED RATHER THAN STREAMED ─────────────────────────────────────
 *
 * `PdfRenderer` requires a seekable `ParcelFileDescriptor`; it cannot read a stream. So a document
 * that lives on the server is fetched once into `cacheDir` and rendered from there. `cacheDir` and
 * NOT `filesDir` is right for this one and is the opposite of the rule the capture path follows: a
 * capture in `cacheDir` is bytes that exist nowhere else and that Android may reclaim without
 * warning, whereas this is a cache of something the server holds — losing it costs one refetch.
 *
 * The URL needs no credentials: `MediaFile.url` is a pre-signed link, which is exactly why the
 * profile columns store a media id and resolve it per read rather than storing the URL.
 *
 * THAT CACHE IS KEYED ON THE MEDIA ID AND BOUNDED — see [dwDocCacheName] and
 * [DW_DOC_PREVIEW_CACHE_BYTES]. Both were defects: keyed on the FILENAME it drew a replaced CV's old
 * first page under the new file's name, and nothing ever removed anything from the directory.
 *
 * ── EVERY FAILURE IS A SENTENCE, NEVER AN EMPTY FRAME ──────────────────────────────────────────
 *
 * A document that will not render is the ordinary case here, not an exceptional one: it may be a
 * .docx, the account may not be entitled to the bytes (`MediaFile.url` is gated server-side at the
 * encoder, so a row arrives complete with its filename and no url), or there may be no signal. Each
 * of those gets its own worded state, because they need different things from whoever is reading —
 * and none of them may look like a document that failed to upload.
 */

/**
 * What the card knows about the file it was pointed at.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * EVERY VARIANT CARRIES [onDevice], AND THAT IS THE FIX FOR A DEAD Open BUTTON
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Until 2026-08-26 the states carried only what was needed to DRAW them, and the Open button had to
 * guess at the bytes: it read `localFile ?: (state as? DocState.Page)?.let { null }` — an expression
 * that is `localFile` and nothing else, because `?.let { null }` is null for every input there is. So
 * a remote PDF that had already been fetched into `cacheDir` and was AT THAT MOMENT RENDERED ON
 * SCREEN handed the platform `Uri.parse(remoteUrl)` instead: an https URL, which with no signal opens
 * nothing while the decoded bytes sit in the cache directory a few lines away. Open did nothing on a
 * document the app was visibly displaying.
 *
 * Holding the file in the state makes that unguessable rather than guessed — and [Page] declares it
 * NON-NULL, so the compiler now refuses a rendered page that cannot say where its bytes came from.
 * A separate `var cachedFile` beside `state` would have been a second source of truth for one fact,
 * which is how the two come to disagree.
 */
private sealed interface DocState {
    /**
     * The document's bytes ON THIS HANDSET, where this state has them — a file just picked, or the
     * cache entry a fetch left behind. Null means there is no copy here, not that there is no file.
     */
    val onDevice: File?

    data object Loading : DocState {
        override val onDevice: File? get() = null
    }

    /** A PDF's first page, plus how many there are, plus the file it was rendered FROM. */
    data class Page(
        val bitmap: Bitmap,
        val pages: Int,
        override val onDevice: File,
    ) : DocState

    /**
     * Renderable as an image — a photographed or scanned sheet.
     *
     * The model is a `File` when the sheet was just picked and a URL string when Coil is fetching it,
     * so the cast is the whole of the question "are the bytes here": a remote image's copy belongs to
     * Coil's own cache, which this file has no handle on and must not pretend to.
     */
    data class Picture(val model: Any) : DocState {
        override val onDevice: File? get() = model as? File
    }

    /**
     * Stored, but not something this app draws. Carries why, in words — and the file where there IS
     * one, because "this device could not render the document" is the state that most needs Open to
     * work, and it is reached with the bytes already in the cache.
     */
    data class NotDrawn(
        val reason: String,
        override val onDevice: File? = null,
    ) : DocState
}

/**
 * What Open hands to the platform, decided ONCE for both the button's presence and its press.
 *
 * The dead expression this replaces was invisible partly because the two were decided separately:
 * the `if` asked "is there a file or a url" and the `onClick` asked "which", so an `onClick` that
 * could only ever answer "url" still sat under a button drawn for a file. One function, called once,
 * cannot drift that way — and it is pure, so `DwDocumentOpenTargetTest` can pin the rule that the
 * copy on this handset always wins. `internal` for that test and for nothing else, exactly as
 * [dwDocCacheName] is.
 */
internal sealed interface DwOpenTarget {
    /** Bytes on this handset. Handed over through the app's own FileProvider, never as `file://`. */
    data class OnDevice(val file: File) : DwOpenTarget

    /** No copy here — the pre-signed link is all there is, and following it needs a connection. */
    data class Remote(val url: String) : DwOpenTarget
}

/**
 * Which copy Open should offer, or null when there is nothing to offer and no button may be drawn.
 *
 * A FILE ON THIS DEVICE ALWAYS BEATS THE URL, whichever file it is. That ordering is the whole
 * finding: a designer with no signal can read the CV they just picked, and — the case that was
 * broken — the survey whose bytes this card already fetched to draw its first page.
 *
 * `justPicked` still comes ahead of `onDevice` rather than being folded into it. They differ on one
 * real path: a document picked in this session is not yet the media row the cache is keyed on, so
 * while an upload is in flight the two can be different files and the one the designer is looking at
 * is theirs.
 *
 * NULL-NESS IS THE ONLY TEST APPLIED TO [remoteUrl] — deliberately no `isNotBlank()`. The composable
 * decides "stored but withheld" on `remoteUrl == null` too, and a second, stricter idea of absence
 * here would let the button vanish while the card above it says the document can be fetched. One
 * rule for one fact, even when the stricter rule looks safer.
 */
internal fun dwOpenTarget(justPicked: File?, onDevice: File?, remoteUrl: String?): DwOpenTarget? {
    val here = justPicked ?: onDevice
    if (here != null) return DwOpenTarget.OnDevice(here)
    return remoteUrl?.let { DwOpenTarget.Remote(it) }
}

/**
 * "Stored, and this account may not have the bytes" — ONE SENTENCE, FOR EVERY KIND OF FILE.
 *
 * `MediaFile.url` absent is the encoder's entitlement answer rather than an error, and it is not a
 * fact about the file's TYPE: a withheld .docx is withheld exactly as a withheld PDF is. The web
 * settles it that way structurally — `DocumentPreview.tsx` tests `!file.url` ABOVE its
 * `isRenderablePdf` split, so one sentence covers both arms — and until 2026-08-26 this client tested
 * it only inside the PDF and image arms. The non-PDF arm promised *"Stored and downloadable … opens
 * in whatever program handles it on your device"* about a file it had nothing to open, and drew no
 * Open button beside the promise. `DesignerProfileScreen` keeps `cvDescriptor` alive through exactly
 * that answer so this sentence can be worded, which is how the gap was reachable at all.
 *
 * The noun rather than the filename is a deliberate PLATFORM DIFFERENCE from the web, which names the
 * file: this card prints the filename on its own row underneath, so naming it here would say it
 * twice on a phone-width line.
 */
internal fun dwWithheldFileNote(noun: String): String =
    "$noun is stored, but this account may not open the file itself."

/**
 * The sentence for a stored file this app will never draw inline — a .docx, an .odt, a .zip.
 *
 * @param openableHere whether anything on this handset can actually be handed over: a file in the
 *   cache or one just picked, or a URL to follow. When nothing can, the promise of a download is a
 *   promise the card cannot keep, so it falls back to [dwWithheldFileNote] — the SAME sentence the
 *   PDF arm already used for the same fact, rather than a second wording for it.
 */
internal fun dwUndrawnDocumentNote(noun: String, openableHere: Boolean): String =
    if (openableHere) {
        "Stored and downloadable. Only a PDF can be shown inside the app, so this one opens " +
            "in whatever program handles it on your device."
    } else {
        dwWithheldFileNote(noun)
    }

/** Is this file a PDF, by the two signals a client actually has? */
private fun looksLikePdf(name: String?, mimeType: String?): Boolean {
    val mime = mimeType?.trim()?.lowercase().orEmpty()
    if (mime == "application/pdf") return true
    // A LOOSE SECOND TEST ON PURPOSE, and only where the mime is absent or generic. A document
    // picked through a content resolver that answered `application/octet-stream` is a real and
    // common case on this platform, and refusing to render a file plainly called `cv.pdf` because of
    // it would be the card failing on the evidence its reader can see. The web file makes the same
    // allowance in the same words.
    if (mime.isNotEmpty() && mime != "application/octet-stream") return false
    return name?.trim()?.lowercase()?.endsWith(".pdf") == true
}

/** Is it something Coil can draw? */
private fun looksLikeImage(name: String?, mimeType: String?): Boolean {
    if (mimeType?.startsWith("image/") == true) return true
    val lower = name?.trim()?.lowercase() ?: return false
    return listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif").any { lower.endsWith(it) }
}

/**
 * The first page of a PDF as a bitmap.
 *
 * ON `Dispatchers.IO` AND NEVER ON THE MAIN THREAD. `PdfRenderer.openPage` plus a `render` of an A4
 * page at this scale is tens of milliseconds on a field handset and rather more on a scanned
 * document with a photograph on page one — enough to drop frames on a screen the designer is
 * scrolling.
 *
 * THE BITMAP IS PRE-FILLED WHITE, which is not cosmetic. `PdfRenderer` composites onto whatever the
 * bitmap already holds and a fresh ARGB_8888 bitmap is transparent, so black text renders onto
 * transparency and the page reads as an empty frame on a dark-themed handset.
 */
private suspend fun renderFirstPage(file: File, widthPx: Int): DocState = withContext(Dispatchers.IO) {
    runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount < 1) {
                    return@use DocState.NotDrawn("This PDF has no pages.", onDevice = file)
                }
                renderer.openPage(0).use { page ->
                    val scale = widthPx.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    DocState.Page(bitmap, renderer.pageCount, onDevice = file)
                }
            }
        }
    }.getOrElse {
        /*
          A PDF this platform refuses (encrypted, or malformed) is reported as itself rather than as
          a missing file, and it goes back CARRYING THE FILE — which is what makes the second half of
          that sentence true. "Open it to read it" was written for a file the platform had; a remote
          document reaching this line has its bytes in `cacheDir` and used to be opened by URL
          instead, so on the encrypted-PDF-with-no-signal path the card told the reader to do the one
          thing it had just made impossible.
        */
        DocState.NotDrawn("This device could not render the document. Open it to read it.", onDevice = file)
    }
}

/** Where a fetched document is kept. One directory, so the sweep below has one thing to measure. */
private fun docPreviewDir(context: Context): File =
    File(context.cacheDir, "dw-doc-preview").apply { mkdirs() }

/**
 * The cache file's name, DERIVED FROM THE MEDIA ID AND FROM NOTHING ELSE.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THIS WAS KEYED ON THE FILENAME UNTIL 2026-08-26, AND THAT SERVED THE WRONG DOCUMENT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The key was `"${name.hashCode()}-${name.takeLast(40)}"` and the line after it returned an existing
 * file untouched. A filename is not an identity: two files can share one, and in this product they
 * routinely do.
 *
 *  * A designer replaces their CV with a corrected `cv.pdf`. That is a NEW media row with a new id —
 *    `MediaFile` rows are never rewritten in place, which is the whole reason the profile columns
 *    store an id and resolve a fresh pre-signed url per read. Same filename, so the same cache file,
 *    so the first page drawn is the SUPERSEDED CV, durably, until Android happens to reclaim
 *    `cacheDir`.
 *  * Two workshops' `survey.pdf` viewed on one handset: the second one shows the first one's page.
 *
 * And the damage lands on the one control whose entire purpose is answering "is this the right
 * document" — with the filename row underneath printing the NEW name over the OLD page, which is
 * worse than showing nothing, because it is a confident wrong answer rather than a missing one.
 *
 * THE MEDIA ID IS THE RIGHT KEY BECAUSE IT IS WHAT IDENTIFIES THE BYTES. It is immutable and it is
 * one-to-one with a stored object, so a cache hit on it is a cache hit on the same document by
 * definition — there is no version of this key that can go stale. The pre-signed url cannot be the
 * key either: it carries an expiring signature, so it changes on every read of the same file and
 * would cache the same document under a new name every few minutes.
 *
 * SANITISED, BECAUSE AN ID GOES INTO A PATH HERE. Today every id is a cuid and passes through
 * unchanged; a future id containing `/` or `..` would otherwise be a write outside this directory.
 * The hash is taken over the WHOLE id, so two ids that sanitise to the same tail — or to nothing at
 * all — still get two files.
 *
 * `internal` AND NOT `private` FOR ONE REASON: this and [dwTrimDocPreviewCache] are part of the pure
 * half of a control whose defects are all silent — a stale cache hit renders a page, it does not
 * throw — so they are asserted from `DwDocumentCacheTest`, which needs to see them. [dwOpenTarget],
 * [dwWithheldFileNote] and [dwUndrawnDocumentNote] are `internal` for exactly the same reason and
 * their own suite; nothing else in the module calls any of the five, and the file's own
 * `docPreviewDir`, `looksLikePdf`, `cacheRemote` and `DocState` stay private.
 */
internal fun dwDocCacheName(mediaId: String): String {
    val safe = mediaId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.takeLast(60)
    return "${mediaId.hashCode().toUInt()}-${safe.ifEmpty { "id" }}"
}

/**
 * How much flash this cache may hold.
 *
 * ── WHY A BOUND AT ALL ─────────────────────────────────────────────────────────────────────────
 *
 * Nothing removed these files. `cacheDir` is reclaimed by the platform, but only under storage
 * pressure and at a moment nobody controls, so on a handset that never gets that close to full this
 * directory only ever grows. It is not a two-file directory either: a coordinator opening the roster
 * looks at many designers' CVs, and every workshop has its own market-survey write-up, so the
 * working set is "every document this handset has ever previewed" and each one can be several
 * megabytes of scanned sheets.
 *
 * A BYTE BUDGET AND NOT A FILE COUNT, because the number of files is not what hurts a 32 GB handset
 * shared by a district team — 24 MiB is a few dozen ordinary CVs and about four scanned ones, which
 * is far more than any one reading session needs and small beside the APK.
 *
 * OLDEST FETCH FIRST, and no LRU bookkeeping. Touching mtimes on every hit would buy a slightly
 * better hit rate and one more thing to be wrong.
 *
 * ── WHY THIS NEEDS NO SENTENCE ON SCREEN, AND WHAT THAT ARGUMENT RESTS ON ──────────────────────
 *
 * An eviction cannot lose anything: the server holds the bytes and the next open refetches them. So
 * the worst degraded path an eviction can produce is "opened again with no signal", and that path
 * ALREADY prints "No connection, so the … could not be fetched to show here." — nothing is silently
 * dropped and nothing untrue is claimed, which is what Rule 10 asks.
 *
 * THAT ARGUMENT IS ONLY SOUND WHILE EVICTION IS RARE AND BOUNDED, AND UNTIL 2026-08-26 IT WAS
 * NEITHER. It was written next to a trim that counted `.part` files it refused to delete, so a single
 * interrupted 25 MB scanned CV put this directory permanently over budget: every later fetch ran the
 * trim, deleted every real cached entry, and still exited over the line. The cache was then
 * permanently EMPTY — every open of every document re-downloading on a metered field connection, for
 * ever, with no sentence anywhere because "an eviction costs one refetch" had quietly become "every
 * read costs a refetch". A cost that stops being one-off stops being invisible, and that is the shape
 * of degradation Rule 10 exists to catch.
 *
 * The repair is in [dwTrimDocPreviewCache] and [cacheRemote] — the premise is restored rather than a
 * sentence added, because a cache that evicts once per new document genuinely has nothing to say.
 */
internal const val DW_DOC_PREVIEW_CACHE_BYTES = 24L * 1024L * 1024L

/**
 * What an in-progress download is named while it is being written.
 *
 * ONE CONSTANT AND NOT A LITERAL IN THREE PLACES: [cacheRemote] builds the name, this file's trim
 * recognises it, and `DwDocumentCacheTest` has to write files the trim will treat the same way the
 * real fetch does. A suffix that drifted between those three would be a rule that silently stopped
 * applying — and the rule here is "never rename this over a real cache entry", which is not the kind
 * of rule to leave to three string literals agreeing.
 *
 * `internal` for exactly the reason [DW_DOC_PREVIEW_CACHE_BYTES] is: the suite asserts the behaviour
 * this name selects, so it has to be able to say the name.
 */
internal const val DW_DOC_PART_SUFFIX = ".part"

/**
 * How long a `.part` file may sit untouched before the trim treats it as abandoned rather than live.
 *
 * ── WHY A TIME AND NOT A FLAG ──────────────────────────────────────────────────────────────────
 *
 * There is nobody to set a flag. The only `.part` that can outlive its own fetch now is one whose
 * PROCESS DIED mid-download — [cacheRemote] deletes its own on every other exit — and a dead process
 * writes no bookkeeping on its way out. So "is anything still writing this?" has to be answered from
 * the file itself, and the only thing the file says is when it was last written to.
 *
 * MTIME AND NOT CREATION TIME, which is the whole reason this window can be short. A live download
 * advances its own mtime continuously: `copyTo` writes through an 8 KiB buffer, so even a 25 MB
 * scanned CV crawling in at 20 KB/s over a 2G field link touches the file several times a second for
 * the twenty minutes it takes. The question is therefore not "has this download had long enough?"
 * (unanswerable — the field connections this app is built for have no upper bound) but "has anything
 * touched this in the last quarter of an hour?", which on any live stream is unambiguously yes.
 *
 * FIFTEEN MINUTES because the one case it can get wrong is a fetch whose socket has HUNG — bytes
 * neither arriving nor failing. Reaping that one's file is not a loss: on Linux the writer keeps its
 * descriptor and its later `renameTo` simply fails, so the attempt ends as a fetch that did not
 * complete, which is what a fifteen-minute-silent socket is. Erring the other way — never reaping —
 * is what produced the permanently-wedged cache described above.
 *
 * A CLOCK THAT JUMPS BACKWARDS (NTP, or a designer correcting the handset's date) makes the age
 * negative, and a negative age is not stale, so the wrong answer this can give is "kept a moment
 * longer". That is the harmless direction: the next successful fetch runs the sweep again.
 */
internal const val DW_DOC_PART_STALE_MILLIS = 15L * 60L * 1000L

/**
 * Sweep abandoned downloads, then delete the oldest fetches until the directory is inside its budget.
 * Never touches [keep], and never touches a download that is still being written.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * A `.part` USED TO BE COUNTED AND NEVER DELETED, AND THAT COMBINATION WEDGED THE WHOLE CACHE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The half of that pair which is right is NEVER DELETING A LIVE ONE: a `.part` is a download in
 * flight, and a second card fetching a second document while this one finishes would otherwise have
 * its half-written file removed from under the stream — turning a working fetch into "No connection".
 *
 * The half that was wrong was COUNTING it anyway, justified as "the budget is a statement about flash
 * actually occupied". **Counting bytes this function refuses to delete makes its own exit condition
 * unreachable.** One interrupted 25 MB scanned CV — the ordinary lost-signal case, and until today
 * that `.part` was also never cleaned up — left the directory permanently over
 * [DW_DOC_PREVIEW_CACHE_BYTES]. Every later successful fetch then ran this loop, deleted EVERY real
 * cached file, and still returned over budget: a permanently empty cache, every document
 * re-downloading on a metered connection for ever, and nothing on screen because each individual
 * eviction looked like the cheap one the docblock above describes.
 *
 * SO THE BUDGET NOW GOVERNS WHAT THIS CACHE RETAINS, NOT WHAT THE DIRECTORY MOMENTARILY HOLDS. Bytes
 * in flight are counted by nothing: they are about to become either a renamed cache entry (counted by
 * the next trim, which is this same call — [cacheRemote] trims AFTER its rename) or a deleted
 * failure. The peak on flash is therefore the budget plus whatever is downloading right now, which is
 * one or two documents, bounded, and transient — where the old accounting's error was unbounded and
 * permanent. Making the loop's goal always reachable is worth more than a byte-exact total, because
 * the only thing the total is used for is deciding when to stop deleting.
 *
 * ── AND ABANDONED ONES ARE REAPED, BECAUSE NOTHING ELSE WILL ───────────────────────────────────
 *
 * The old claim that "a `.part` left behind by a killed process is overwritten by the next fetch of
 * the same id, so nothing accumulates" was false in both halves. Different ids leave different files,
 * so an orphan is never in the way of the fetch that would overwrite it — and [cacheRemote] now names
 * each attempt uniquely (see its docblock: two attempts on ONE id are real and used to share one
 * path), so no `.part` is ever overwritten by anything. The next successful fetch is the only visitor
 * this directory gets, so this is where the sweep has to live, and [DW_DOC_PART_STALE_MILLIS] is what
 * separates "abandoned" from "in flight".
 */
internal fun dwTrimDocPreviewCache(keep: File) {
    val everything = keep.parentFile?.listFiles()?.filter { it.isFile }.orEmpty()
    val now = System.currentTimeMillis()

    // ONE PASS, TWO JOBS, IN THIS ORDER: the sweep runs before the budget is even measured, so a
    // directory that is over budget ONLY because of orphaned parts costs nothing and evicts nothing.
    val cached = ArrayList<File>(everything.size)
    for (file in everything) {
        if (!file.name.endsWith(DW_DOC_PART_SUFFIX)) {
            cached += file
            continue
        }
        // A live download is left alone and is NOT added to `cached`, so it is neither deleted nor
        // counted — see the docblock. An abandoned one goes, and a delete that fails (another
        // process holding it, a read-only directory) is not worth reporting: it is retried by the
        // next fetch, and it can no longer keep this loop from reaching its budget either way.
        if (now - file.lastModified() > DW_DOC_PART_STALE_MILLIS) file.delete()
    }

    var total = cached.sumOf { it.length() }
    if (total <= DW_DOC_PREVIEW_CACHE_BYTES) return
    val candidates = cached
        .filter { it != keep }
        .sortedBy { it.lastModified() }
    for (file in candidates) {
        if (total <= DW_DOC_PREVIEW_CACHE_BYTES) return
        val size = file.length()
        if (file.delete()) total -= size
    }
    /*
      FALLING OUT OF THAT LOOP STILL OVER BUDGET IS NOW POSSIBLE IN EXACTLY ONE CASE, and it is the
      honest one: [keep] alone is bigger than the whole budget — a single scanned document over
      24 MiB. It is not evicted, because it is the file the card is about to render, and evicting it
      would turn a successful fetch into an empty frame (the case `over budget the oldest fetches are
      evicted and the new one is kept` pins). Nothing is wedged by it: the next document's fetch finds
      this one as an ordinary, older, evictable entry. Deliberately not a sentence on screen — the
      designer got the document they asked for, at its real size, and nothing was dropped or refused.
    */
}

/**
 * Fetch a pre-signed URL into `cacheDir` once, under [mediaId]. Returns null when there is no signal.
 *
 * WRITTEN TO A `.part` FILE AND RENAMED, which is the same defect class as the key above in its other
 * direction: writing straight to the target left a download cut off half-way — a dropped bar of
 * signal, a killed process — sitting there with a positive length, and the `exists() && length > 0`
 * hit above then returned that truncated file for ever. A truncated PDF renders as "This device could
 * not render the document", permanently, for a document that is perfectly fine on the server. The
 * rename is atomic and within one directory, so a partial file is never reachable under the real key.
 *
 * ── EVERY ATTEMPT GETS ITS OWN NAME, AND CLEANS UP AFTER ITSELF ────────────────────────────────
 *
 * The `.part` name carries a [UUID] DISCRIMINATOR — `<cache name>.<uuid>.part`, built from
 * [DW_DOC_PART_SUFFIX] rather than from a literal so the constant's own docblock stays true — and
 * not one fixed path per [mediaId]. TWO CONCURRENT FETCHES OF ONE ID ARE ORDINARY, not exotic: the
 * same document is previewed by two cards on one screen (a designer's CV beside the same designer's
 * CV in a roster row), and a `LaunchedEffect` keyed on the url re-launches on a fresh pre-signed url
 * while the first fetch is still reading its socket. On one shared path those two interleave their
 * bytes into the same file and then whichever finishes first renames the mixture under the real key —
 * a corrupt document reachable under a valid name, which is EXACTLY the defect the `.part` scheme
 * above exists to prevent, arriving through the scheme's own working directory. A per-attempt name
 * makes that unrepresentable: neither attempt can see the other's file, and the loser's rename simply
 * replaces a byte-identical entry.
 *
 * THE DISCRIMINATOR GOES BEFORE THE SUFFIX, not after, because the sweep in
 * [dwTrimDocPreviewCache] recognises a download in flight by `endsWith(DW_DOC_PART_SUFFIX)`.
 * `name.<uuid>.part` is still a `.part` to that test; `name.part.<uuid>` would be an unrecognised
 * file that the trim counted against the budget and evicted as if it were a finished document —
 * pulling a live download out from under its own stream, which is the half of the old accounting the
 * sweep's docblock says was right all along.
 *
 * AND ANY FAILURE DELETES ITS OWN PARTIAL. The `try`/`catch` wraps the whole download, not just the
 * rename: the ordinary case this cache exists for is a bar of signal dropping mid-`copyTo`, which
 * throws an `IOException` out of the middle of the stream. Deleting only on a failed rename left that
 * one on disk — and now that names are unique, nothing would ever overwrite it, so it would sit there
 * until [DW_DOC_PART_STALE_MILLIS] elapsed and the next successful fetch swept it. `Throwable` and
 * not `IOException`, and rethrown rather than swallowed: the point is that the file goes on EVERY
 * failing exit — the failed rename included, which is the one case that used to be covered — and the
 * caller still learns the fetch failed through the `null` below.
 *
 * NO `CancellationException` GUARD IS NEEDED IN THIS `runCatching`, OR IN [renderFirstPage]'S. Both
 * sit INSIDE their `withContext(Dispatchers.IO)` and contain no suspension point at all — a socket
 * read and a `PdfRenderer` call are blocking, not cancellable — so a cancellation cannot be thrown
 * into either block. It surfaces where it should: `withContext` itself throws it on resume, at the
 * `LaunchedEffect` that called this.
 */
private suspend fun cacheRemote(context: Context, mediaId: String, url: String): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val target = docPreviewDir(context).resolve(dwDocCacheName(mediaId))
            if (target.exists() && target.length() > 0L) return@runCatching target
            val partial = File(target.parentFile, "${target.name}.${UUID.randomUUID()}$DW_DOC_PART_SUFFIX")
            try {
                URL(url).openStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (!partial.renameTo(target)) error("could not move the fetched document into place")
            } catch (failure: Throwable) {
                // EVERY FAILING EXIT CLEANS UP ITS OWN FILE, the failed rename included — see the
                // docblock. A dropped connection throws out of the middle of `copyTo`, and while the
                // rename was the only guarded step that partial stayed on flash until the trim's
                // staleness window reaped it.
                partial.delete()
                throw failure
            }
            dwTrimDocPreviewCache(target)
            target
        }.getOrNull()
    }

@Composable
internal fun DwDocumentPreview(
    /** The stored media id, or blank when nothing is on file. */
    mediaId: String,
    /** What this document IS, in the reader's words — "CV", "market survey". */
    noun: String,
    /** A local copy, when the file was just picked in this session. Rendered in preference. */
    localFile: File?,
    /** The server's pre-signed URL, when it is willing to serve one back. */
    remoteUrl: String?,
    /** The filename, for the sentence and for the extension test. */
    displayName: String?,
    /** The stored mime type, where the caller has one. */
    mimeType: String? = null,
) {
    val context = LocalContext.current
    var state by remember(mediaId, localFile, remoteUrl) { mutableStateOf<DocState>(DocState.Loading) }
    /**
     * True once the Open button has found nothing on this handset willing to take the file.
     *
     * RULE 10, ON A PATH THAT USED TO BE SILENT. The `runCatching` around `startActivity` below stops
     * an ActivityNotFoundException taking the screen down, and until 2026-08-26 that was all it did:
     * the designer pressed Open, absolutely nothing happened, and the app looked broken over a
     * handset that simply has no PDF viewer installed — which on a wiped field device is common. A
     * refusal has to say it is a refusal. Reset per document, and cleared by a press that works, so
     * installing a viewer and pressing again does not leave a stale sentence behind.
     */
    var openFailed by remember(mediaId, localFile, remoteUrl) { mutableStateOf(false) }

    LaunchedEffect(mediaId, localFile, remoteUrl, displayName, mimeType) {
        val name = displayName ?: localFile?.name
        when {
            mediaId.isBlank() && localFile == null -> state = DocState.NotDrawn("No $noun on file.")

            looksLikeImage(name, mimeType) -> {
                val model: Any? = localFile ?: remoteUrl
                state = if (model != null) DocState.Picture(model)
                else DocState.NotDrawn(dwWithheldFileNote(noun))
            }

            looksLikePdf(name, mimeType) -> {
                // KEYED ON `mediaId` AND NOT ON `name`: see [dwDocCacheName]. `mediaId` is non-blank
                // wherever this line runs — the first branch of this `when` has already answered the
                // only case in which it can be blank alongside a null `localFile`.
                val file = localFile ?: remoteUrl?.let { cacheRemote(context, mediaId, it) }
                state = when {
                    file != null -> renderFirstPage(file, widthPx = 900)
                    remoteUrl == null ->
                        // STORED BUT NOT READABLE FROM HERE. `MediaFile.url` absent is the encoder's
                        // entitlement answer, not an error, and it needs different words from a
                        // failed download: there is nothing to retry. See [dwWithheldFileNote] — the
                        // non-PDF arm below now says this in the same words, for the same reason.
                        DocState.NotDrawn(dwWithheldFileNote(noun))
                    else -> DocState.NotDrawn("No connection, so the $noun could not be fetched to show here.")
                }
            }

            /*
              NOT A PDF AND NOT AN IMAGE — a .docx, an .odt, a scan in a container this app will not
              open. The type is only HALF the question and the other half used to be skipped: whether
              there is anything to hand over at all. See [dwUndrawnDocumentNote]. An admin opening a
              designer's profile whose CV is a .docx, on an account the encoder withheld the url from,
              read a promise of a download beside no button to take it — while the arm directly above
              had worded that exact case correctly for a PDF since the day it shipped.
            */
            else -> state = DocState.NotDrawn(
                reason = dwUndrawnDocumentNote(
                    noun = noun,
                    openableHere = localFile != null || remoteUrl != null,
                ),
                /*
                  THE STATE SAYS WHERE ITS BYTES ARE, like every other variant, rather than leaving
                  the reader of the state to go and look at the composable's parameters. `dwOpenTarget`
                  would find this same file through `justPicked`, so the two cannot disagree — what
                  this buys is that the state is complete on its own, which is the property whose
                  absence made the Open button guess in the first place.

                  Nothing is CACHED for this arm: only [renderFirstPage]'s PDFs are fetched, because
                  spending a designer's data on bytes this card cannot draw would buy nothing.
                */
                onDevice = localFile,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
        ) {
            when (val shown = state) {
                is DocState.Loading -> CircularProgressIndicator(modifier = Modifier.size(22.dp))
                is DocState.Page -> Image(
                    painter = BitmapPainter(shown.bitmap.asImageBitmap()),
                    contentDescription = "$noun, first page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
                is DocState.Picture -> AsyncImage(
                    model = shown.model,
                    contentDescription = noun,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
                is DocState.NotDrawn -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.field.muted,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(shown.reason, color = MaterialTheme.field.muted, fontSize = 12.sp)
                }
            }
        }

        // The filename, the page count where there is one, and a way out to a real viewer. Drawn for
        // every state that has a file at all, because a reader who cannot use the inline page still
        // needs to know WHICH document is on file.
        if (mediaId.isNotBlank() || localFile != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    buildString {
                        append(displayName ?: localFile?.name ?: noun)
                        (state as? DocState.Page)?.let { append(" · ${it.pages} page${if (it.pages == 1) "" else "s"}") }
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                /*
                  WHICH COPY, DECIDED ONCE — for whether the button exists AND for what the press
                  hands over. See [dwOpenTarget]: `state.onDevice` is the file this card fetched to
                  draw the page, which the expression here used to throw away.
                */
                val target = dwOpenTarget(
                    justPicked = localFile,
                    onDevice = state.onDevice,
                    remoteUrl = remoteUrl,
                )
                if (target != null) {
                    OutlinedButton(onClick = {
                        /*
                          HANDED TO THE PLATFORM, and a copy on this handset is preferred over the
                          URL: a designer with no signal can still read the CV they just picked, and
                          the survey whose bytes are already in `cacheDir` because this card drew its
                          first page from them.

                          THROUGH THE APP'S OWN FileProvider, because a bare `file://` Uri handed to
                          another app is a FileUriExposedException on every API level this app
                          supports. The authority is built from the package name exactly as
                          `dwCaptureUri` and `durableFileUri` build theirs — the same declaration in
                          the manifest, reached from a file that cannot see either of those two
                          (both are private to their screens).

                          THE CACHE FILE IS SAFE TO SHARE THIS WAY: `dw-doc-preview` sits under
                          `cacheDir`, which the manifest's provider paths already cover for the
                          capture flow, and [dwTrimDocPreviewCache] never evicts the entry a card is
                          holding open. An eviction under a reader's viewer would cost one refetch,
                          not the document — the server has the bytes.
                        */
                        val uri = when (target) {
                            is DwOpenTarget.OnDevice -> FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                target.file,
                            )
                            is DwOpenTarget.Remote -> Uri.parse(target.url)
                        }
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, if (looksLikePdf(displayName, mimeType)) "application/pdf" else "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        // `runCatching`: a handset with no PDF viewer installed throws
                        // ActivityNotFoundException, which must not take the profile screen down over
                        // a button somebody pressed out of curiosity. NOT swallowed silently, though —
                        // see [openFailed]. (Nothing suspends in here, so no cancellation can reach
                        // this `runCatching`.)
                        runCatching { context.startActivity(intent) }
                            .onSuccess { openFailed = false }
                            .onFailure { openFailed = true }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text("Open", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            // A POLITE LIVE REGION, because this sentence APPEARS IN ANSWER TO A PRESS. A reader who
            // cannot see the layout gets nothing at all from a line that quietly materialises below
            // the button they just activated — which would leave them with the same "nothing
            // happened" the sentence exists to end. The box is drawn whether or not there is anything
            // in it, so the region is stable across the change, which is what makes the announcement
            // fire. Same construction as the review card's character-cap notice.
            Box(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                }
            ) {
                if (openFailed) {
                    Text(
                        "Nothing installed on this device offered to open it, so the file was not " +
                            "handed over. Install a document viewer, or open the $noun on the web " +
                            "portal.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}
