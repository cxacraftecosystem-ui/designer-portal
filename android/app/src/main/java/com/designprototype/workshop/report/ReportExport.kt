package com.designprototype.workshop.report

import android.content.Context
import android.net.Uri
import com.designprototype.workshop.data.WorkshopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The public entry point for exporting a [ReportDocument] from the device, entirely offline.
 *
 * Nothing here talks to the API. [DocxWriter] needs only `java.util.zip` and [PdfWriter] only
 * `android.graphics`, so a researcher standing in a workshop with no signal gets the same .docx and
 * the same .pdf the server would have produced — which is the whole point of porting the renderers
 * rather than calling an endpoint. The only thing this file adds is where the bytes land.
 *
 * The publish step reuses [WorkshopRepository.persistFileToDownloads] rather than reimplementing it.
 * That function already carries the Q+ `IS_PENDING` handshake, the pre-Q permission check and the
 * app-private fallback that keeps a refused permission from destroying an export the designer just
 * waited out a progress bar for. A second copy of that logic would be a second copy to get wrong,
 * and it would get wrong exactly the handsets nobody develops on.
 *
 * THE REPORT IS PUBLISHED AND NOT RETAINED, and that is a decision. `WorkshopDraftStore` used to
 * carry a whole export-retention subsystem — `DraftExport`, `exports`, `exportsDir`, `exportFile`,
 * `registerExport`, `exportCount` — complete, documented, and with no call site anywhere in
 * `android/app/src`: no `DraftExport` was ever constructed, so `draft.exports` was permanently empty
 * and the "this PDF is older than your edits" warning it was built around could not fire. It has
 * been removed rather than wired, because the capability it promised is one Downloads already
 * provides: [Result.displayLocation] is where the file went and [Result.shareUri] covers the one
 * case it could not go there. Wiring it instead would have kept a SECOND full copy of every
 * generated report — tens of megabytes for a photo-heavy workshop — inside `filesDir` on the same
 * space-constrained handsets that make [DocxWriter] refuse to hold its own media parts in memory,
 * and it had no retention rule at all: `registerExport` deduplicated by file name, and the file name
 * carries a timestamp, so nothing would ever have been replaced. Anyone reviving it needs the
 * retention rule first, not the store.
 */
object ReportExport {

    /** File-name stamp, matching the dataset zip and the .xlsx report so exports sort together. */
    private val STAMP = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault())

    /**
     * Where an export landed and what could not be put in it.
     *
     * [displayLocation] is what [WorkshopRepository.persistFileToDownloads] returns and what every
     * other download path in this app shows the user: "Downloads/<name>" on Q+, an absolute path
     * below it. It is NOT a Uri, and deliberately so — on Q+ the MediaStore row's Uri is consumed
     * and discarded inside that helper, and changing its contract to hand one back would change what
     * four existing download paths display. [shareUri] is the honest Uri: non-null only when the file
     * had to go to app-private storage (a pre-Q device that refused WRITE_EXTERNAL_STORAGE), which is
     * the only case where the file is unreachable without a FileProvider grant.
     *
     * [droppedImages] is never empty-checked by this object: a report missing one photo is still
     * worth having in the field, so a photo that would not load is reported, not thrown.
     */
    data class Result(
        val displayLocation: String,
        val shareUri: Uri?,
        val fileName: String,
        val mimeType: String,
        val droppedImages: List<String>,
    )

    /**
     * Write [document] as a .docx into the device's Downloads folder.
     *
     * [loadImage] resolves an [ImageRef] to bytes — on this side that is normally a read of the local
     * media copy by absolute path. It is called on the IO dispatcher and may block.
     */
    suspend fun exportDocx(
        context: Context,
        repository: WorkshopRepository,
        document: ReportDocument,
        loadImage: ImageLoader,
        fileName: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        // A MapBlock rasterises the country from assets that need an AssetManager to reach, and the
        // rasteriser is deliberately free of Context. Installing here rather than at application start
        // is what stops the map appearing in an export launched from one screen and silently missing
        // from one launched from another.
        installReportBoundaryAssets(context)
        publish(
            context = context,
            repository = repository,
            name = fileName ?: defaultName(document, "docx"),
            mimeType = DOCX_MIME,
        ) { out -> renderDocx(document, loadImage, out) }
    }

    /** Write [document] as a PDF into the device's Downloads folder. */
    suspend fun exportPdf(
        context: Context,
        repository: WorkshopRepository,
        document: ReportDocument,
        loadImage: ImageLoader,
        fileName: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        installReportBoundaryAssets(context)
        publish(
            context = context,
            repository = repository,
            name = fileName ?: defaultName(document, "pdf"),
            mimeType = PDF_MIME,
        ) { out -> renderPdf(document, loadImage, out) }
    }

    /**
     * Render into a cache file, flush it to the flash, then hand it to the repository to publish.
     *
     * WRITE COMPLETELY BEFORE PUBLISHING, and `fd.sync()` before either. This is the same discipline
     * `OfflineOutbox.write` uses and for the same reason: the publish step copies the temp file into
     * a MediaStore row that becomes visible to every app on the device the moment IS_PENDING clears,
     * and a copy whose source bytes are still only in the page cache publishes a truncated .docx —
     * which Word reports as "the file is corrupt", indistinguishable from every other way this
     * pipeline can go wrong. The temp file is deleted in a `finally` so a failed export does not
     * leave a half-built report filling the cache directory of a phone that is already short of space.
     */
    private fun publish(
        context: Context,
        repository: WorkshopRepository,
        name: String,
        mimeType: String,
        render: (OutputStream) -> List<String>,
    ): Result {
        val tmp = File(context.cacheDir, name)
        try {
            val dropped = FileOutputStream(tmp).use { fos ->
                val buffered = BufferedOutputStream(fos)
                val result = render(buffered)
                buffered.flush()
                fos.fd.sync()
                result
            }
            val location = repository.persistFileToDownloads(context, tmp, name, mimeType)
            return Result(
                displayLocation = location,
                shareUri = repository.shareUriForSavedFile(context, location),
                fileName = name,
                mimeType = mimeType,
                droppedImages = dropped,
            )
        } finally {
            tmp.delete()
        }
    }

    /**
     * "DesignWorkshop_<title>_<stamp>.<ext>", with everything a file system might object to removed.
     *
     * The character filter is the same one the data-browser download uses. A craft name carrying a
     * slash ("Ikat/Bandha") or a colon produced a MediaStore insert that failed with a bare
     * IllegalArgumentException after the whole report had already been rendered.
     */
    private fun defaultName(document: ReportDocument, ext: String): String {
        val stem = document.meta.title
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(60)
            .ifEmpty { "report" }
        return "DesignWorkshop_${stem}_${STAMP.format(Instant.now())}.$ext"
    }
}
